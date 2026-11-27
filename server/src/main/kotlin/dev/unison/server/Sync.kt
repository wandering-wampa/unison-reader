package dev.unison.server

import ai.onnxruntime.OrtSession
import dev.homescreenreader.Book
import dev.homescreenreader.BookText
import dev.homescreenreader.Emitter
import dev.homescreenreader.SpeechModel
import dev.homescreenreader.SyncPlan
import dev.homescreenreader.UnitAligner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * What a finished sync is called. A work whose units are all done is poorly matched when under a fifth of its
 * sentences were found; the Android app judges its own on-device sync by the same rule (`poorlyMatched` in
 * MainActivity), so keep the two in step. The status starts with "Failed" on purpose: released apps show such a
 * status as it is, and the library scan leaves a Failed work alone.
 */
object SyncVerdict {
    fun poorlyMatched(anchors: Int, passages: Int) = passages > 0 && anchors * 5 < passages
    fun status(anchors: Int, passages: Int) =
        if (poorlyMatched(anchors, passages)) "Failed: only ${100 * anchors / passages}% of the text was found in the audio" else "Synced"
}

/**
 * Runs the shared sync engine over paired works with a pool of workers, one ONNX session each, so
 * several chapters align at once. Audio is decoded per piece with ffmpeg from the read-only library.
 */
class SyncService(private val config: Config, private val catalog: Catalog, private val scanner: Scanner) {
    private val log = LoggerFactory.getLogger("Sync")
    private val modelBytes by lazy { config.model.readBytes() }
    private val threadsPerWorker = max(1, Runtime.getRuntime().availableProcessors() / config.workers)
    private val pool = Executors.newFixedThreadPool(config.workers) { r -> Thread(r, "align").apply { isDaemon = true } }
    private val session = ThreadLocal.withInitial { SpeechModel.open(modelBytes, threadsPerWorker) }

    @Serializable class Live(val work: String, val title: String, val unitsDone: Int, val units: Int, val current: List<String>, val startedAt: Long)

    internal class Job(val work: Work, val book: Book, val text: BookText, val total: Int, val startedAt: Long) {
        val cancel = AtomicBoolean(false)
        val remaining = AtomicInteger(0)
        val done = AtomicInteger(0)
        val current = ConcurrentHashMap.newKeySet<String>()
        val failed = AtomicBoolean(false)
        /** Set by [restart] while the job runs: the last worker to finish wipes the alignment and starts the work again. */
        @Volatile var restartAfter = false
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    fun live(): List<Live> = jobs.values.map { Live(it.work.id, it.work.title, catalog.units(it.work.id).count { u -> u.done }, it.total, it.current.toList(), it.startedAt) }
    fun isSyncing(id: String) = jobs.containsKey(id)

    /** Queues every paired work that is not fully synced. */
    fun queueStale() {
        for (w in catalog.works()) if (w.hasEbook && w.hasAudio && w.passages > 0 && w.status != "Synced" && !w.status.startsWith("Failed") && !jobs.containsKey(w.id)) request(w.id)
    }

    /**
     * Once per start: a work stored as "Synced" whose finished alignment matched under a fifth of its sentences
     * (servers before this rule kept such works as Synced) gets the failed status. Reads only the database: no
     * re-alignment, no book is opened. Leaves a work that is being synced alone. Returns the works changed.
     */
    @Synchronized
    fun rejudgeSynced(): Int {
        var changed = 0
        for (w in catalog.works()) {
            if (!w.hasEbook || !w.hasAudio || w.status != "Synced" || jobs.containsKey(w.id)) continue
            val units = catalog.units(w.id)
            if (units.isEmpty() || !units.all { it.done }) continue
            val anchors = catalog.anchorCount(w.id)
            if (!SyncVerdict.poorlyMatched(anchors, w.passages)) continue
            val status = SyncVerdict.status(anchors, w.passages)
            catalog.setStatus(w.id, status)
            log.info("${w.title}: was Synced, now $status")
            changed++
        }
        log.info("Start-up check of synced works: $changed changed")
        return changed
    }

    /** Plans a work's units and submits the ones not yet done. */
    @Synchronized
    fun request(id: String): Boolean {
        if (jobs.containsKey(id)) return true
        val work = catalog.work(id) ?: return false
        if (!work.hasEbook || !work.hasAudio) return false
        val book = scanner.bookFor(work) ?: return false
        if (book.passages.isEmpty() || book.tracks.isEmpty()) return false
        val plan = SyncPlan.units(book)
        catalog.planUnits(id, plan, SyncPlan.VERSION)
        val pending = catalog.units(id).filter { !it.done }
        if (pending.isEmpty()) { catalog.setStatus(id, SyncVerdict.status(catalog.anchorCount(id), work.passages)); return true }
        val job = Job(work, book, BookText(book), plan.size, System.currentTimeMillis())
        job.remaining.set(pending.size)
        job.done.set(plan.size - pending.size)
        jobs[id] = job
        catalog.setStatus(id, "Syncing · ${job.done.get()} of ${job.total} parts")
        log.info("Sync ${work.title}: ${pending.size} of ${plan.size} parts to do, ${config.workers} workers × $threadsPerWorker threads")
        for (u in pending) {
            val unit = plan.first { it.key == u.key }
            pool.submit { runUnit(job, unit) }
        }
        return true
    }

    /** Pauses a running job after its current chapters; a restart still waiting for them is dropped, so the pause wins. */
    @Synchronized
    fun cancel(id: String) { jobs[id]?.let { it.restartAfter = false; it.cancel.set(true) } }

    /**
     * Drops a work's alignment and starts over (after an ebook change, say). While the work is syncing, the workers are
     * told to stop and the job is marked: [finish] does the wipe and the new start once the last one is done, so nothing
     * a draining worker writes survives the wipe. Otherwise it wipes and starts at once.
     */
    @Synchronized
    fun restart(id: String) {
        val job = jobs[id]
        if (job != null) { job.restartAfter = true; job.cancel.set(true); return }
        catalog.resetSync(id)
        request(id)
    }

    /** Registers a job without planning or submitting anything: for tests of the cancel and restart rules. */
    internal fun adopt(job: Job) { synchronized(this) { jobs[job.work.id] = job } }

    private fun runUnit(job: Job, unit: SyncPlan.SyncUnit) {
        val id = job.work.id
        if (job.cancel.get() || job.failed.get() || catalog.missingSince(id) != null) { finish(job); return }
        job.current += unit.title
        val started = System.currentTimeMillis()
        try {
            val result = alignUnit(job, unit)
            if (result != null) {
                catalog.unitDone(id, unit.key, result.anchors, System.currentTimeMillis() - started)
                job.done.incrementAndGet()
                val audioMs = unit.endMs - unit.startMs
                log.info("${job.work.title} · ${unit.title}: ${result.anchors.size} sentences from ${audioMs / 1000}s of audio in ${(System.currentTimeMillis() - started) / 1000}s (${audioMs / max(1, System.currentTimeMillis() - started)}× realtime), spoken=${result.spokenWords} hits=${result.anchorHits} chain=${result.chainLength} skipped=${result.skipped}")
            }
        } catch (e: Throwable) {
            log.error("${job.work.title} · ${unit.title} failed", e)
            job.failed.set(true)
            catalog.setStatus(id, "Failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            job.current -= unit.title
            if (!job.failed.get() && !job.cancel.get()) catalog.setStatus(id, "Syncing · ${job.done.get()} of ${job.total} parts")
            finish(job)
        }
    }

    internal fun finish(job: Job) {
        if (job.remaining.decrementAndGet() > 0) return
        val id = job.work.id
        // Removing the job and reading the flag happen together, so a restart sees either a running job or none.
        // The wipe and the new start stay inside the same lock: restart and cancel see the old job or the new one, never the gap.
        // The lock is re-entrant for request; the pool's queue is unbounded, so submitting never waits.
        val again = synchronized(this) {
            jobs.remove(id)
            if (job.restartAfter) {
                log.info("${job.work.title}: starting over")
                catalog.resetSync(id)
                if (!request(id)) log.info("${job.work.title}: cannot start over, the book is not ready")
                true
            } else false
        }
        if (again) return
        val units = catalog.units(id)
        val status = when {
            job.failed.get() -> catalog.work(id)?.status ?: "Failed"
            units.all { it.done } -> SyncVerdict.status(catalog.anchorCount(id), catalog.work(id)?.passages ?: job.work.passages)
            job.cancel.get() -> "Paused"
            else -> "Partly synced"
        }
        catalog.setStatus(id, status)
        log.info("${job.work.title}: $status (${units.count { it.done }} of ${units.size} parts, ${catalog.anchorCount(id)} sentences)")
    }

    /** The app's alignUnit, with ffmpeg in place of MediaCodec and no producer thread (workers already run in parallel). */
    private fun alignUnit(job: Job, unit: SyncPlan.SyncUnit): UnitAligner.Result? {
        val book = job.book
        val frames = ((unit.endMs - unit.startMs) / SpeechModel.FRAME_MS).toInt()
        val lp = FloatArray(frames * SpeechModel.CLASSES)
        val emitter = Emitter(session.get())
        var cursor = unit.startMs
        while (cursor < unit.endMs) {
            if (job.cancel.get()) return null
            val e = min(unit.endMs, cursor + PIECE_MS)
            val ds = max(0, cursor - CONTEXT_MS)
            val de = min(book.duration, e + CONTEXT_MS)
            val pcm = decodeRange(book, ds, de)
            emitter.emit(pcm, ((cursor - ds) * 16).toInt(), ((e - cursor) / SpeechModel.FRAME_MS).toInt(), lp, ((cursor - unit.startMs) / SpeechModel.FRAME_MS).toInt())
            cursor = e
        }
        val margin = (SyncPlan.OVERLAP_MS / 2 / SpeechModel.FRAME_MS).toInt()
        return UnitAligner(job.text).align(lp, frames, unit.startMs, if (unit.leadEdge) 0 else margin, if (unit.trailEdge) 0 else margin)
    }

    /** Decodes [startMs, endMs) of the book across track boundaries into one 16 kHz mono buffer. */
    private fun decodeRange(book: Book, startMs: Long, endMs: Long): FloatArray {
        val out = FloatArray(((endMs - startMs) * 16).toInt())
        var base = 0L
        for (t in book.tracks) {
            val ts = base
            val te = base + t.duration
            base = te
            val s = max(startMs, ts)
            val e = min(endMs, te)
            if (e <= s) continue
            val pcm = decode(t.uri, s - ts, e - s)
            val at = ((s - startMs) * 16).toInt()
            System.arraycopy(pcm, 0, out, at, min(pcm.size, out.size - at))
        }
        return out
    }

    /** ffmpeg decodes one span of one file to 16 kHz mono float samples, read straight from its pipe. */
    private fun decode(path: String, startMs: Long, durationMs: Long): FloatArray {
        val process = ProcessBuilder(
            "ffmpeg", "-nostdin", "-v", "error", "-ss", "%.3f".format(startMs / 1000.0), "-t", "%.3f".format(durationMs / 1000.0),
            "-i", path, "-vn", "-f", "f32le", "-ac", "1", "-ar", "16000", "pipe:1",
        ).start()
        val stderr = StringBuilder()
        val errThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }.apply { isDaemon = true; start() }
        val bytes = ByteArrayOutputStream(((durationMs * 16) * 4).toInt().coerceAtLeast(1024))
        process.inputStream.use { it.copyTo(bytes) }
        if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); error("ffmpeg timed out decoding $path") }
        errThread.join(1000)
        if (process.exitValue() != 0) error("ffmpeg failed on $path: ${stderr.toString().trim().lines().lastOrNull()}")
        val buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val expected = (durationMs * 16).toInt()
        val out = FloatArray(expected)
        buffer.get(out, 0, min(expected, buffer.remaining()))
        return out
    }

    companion object {
        private const val PIECE_MS = 300_000L
        private const val CONTEXT_MS = 2_000L
    }
}
