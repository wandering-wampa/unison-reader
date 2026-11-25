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

    private class Job(val work: Work, val book: Book, val text: BookText, val total: Int, val startedAt: Long) {
        val cancel = AtomicBoolean(false)
        val remaining = AtomicInteger(0)
        val done = AtomicInteger(0)
        val current = ConcurrentHashMap.newKeySet<String>()
        val failed = AtomicBoolean(false)
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    fun live(): List<Live> = jobs.values.map { Live(it.work.id, it.work.title, catalog.units(it.work.id).count { u -> u.done }, it.total, it.current.toList(), it.startedAt) }
    fun isSyncing(id: String) = jobs.containsKey(id)

    /** Queues every paired work that is not fully synced. */
    fun queueStale() {
        for (w in catalog.works()) if (w.hasEbook && w.hasAudio && w.passages > 0 && w.status != "Synced" && !w.status.startsWith("Failed") && !jobs.containsKey(w.id)) request(w.id)
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
        if (pending.isEmpty()) { catalog.setStatus(id, "Synced"); return true }
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

    fun cancel(id: String) { jobs[id]?.cancel?.set(true) }

    /** Drops a work's alignment and starts over (after an ebook change, say). */
    fun restart(id: String) { cancel(id); catalog.resetSync(id); request(id) }

    private fun runUnit(job: Job, unit: SyncPlan.SyncUnit) {
        val id = job.work.id
        if (job.cancel.get() || job.failed.get()) { finish(job); return }
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

    private fun finish(job: Job) {
        if (job.remaining.decrementAndGet() > 0) return
        val id = job.work.id
        jobs.remove(id)
        val units = catalog.units(id)
        val status = when {
            job.failed.get() -> catalog.work(id)?.status ?: "Failed"
            units.all { it.done } -> "Synced"
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
