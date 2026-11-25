package dev.homescreenreader

import ai.onnxruntime.OrtSession
import dev.homescreenreader.SyncPlan.SyncUnit
import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.Normalizer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * Fast ebook/audiobook synchronization.
 *
 * The audio is decoded in bounded pieces and passed through a small CTC acoustic model
 * (NVIDIA QuartzNet 15x5, English, int8) that emits per-character log-probabilities on a
 * 20 ms grid in a single forward pass — no autoregressive decoding. A greedy decode of
 * those emissions is matched to the ebook with unique five-word phrases, which produces a
 * dense monotonic chain of anchors. The text between anchors is then force-aligned to the
 * audio with CTC Viterbi, giving every sentence a measured start and end time, including
 * sentences the greedy decode misheard. Only sentences whose alignment is physically
 * plausible are stored; unnarrated text stays unmatched rather than guessed.
 */

/** The speech model from the app's assets. */
fun SpeechModel.open(context: Context, threads: Int = threads()): OrtSession =
    open(context.assets.open(SpeechModel.ASSET).use { it.readBytes() }, threads)

/** Bounded audio decoding to 16 kHz mono float, retaining no decoded files. */
object AudioDecoder {
    private const val RATE = 16000

    fun decode(context: Context, uri: String, startMs: Long, durationMs: Long): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floating = false
            val endUs = (startMs + durationMs) * 1000
            var native = FloatArray(((durationMs + 2000) * rate / 1000).toInt())
            var count = 0
            var firstUs = -1L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var stalled = 0
            while (!outputDone) {
                if (!inputDone) {
                    val slot = codec.dequeueInputBuffer(10000)
                    if (slot >= 0) {
                        val buffer = codec.getInputBuffer(slot)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts > endUs + 1000000) {
                            codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(slot, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val slot = codec.dequeueOutputBuffer(info, 10000)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floating =
                        f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (slot >= 0) {
                    stalled = 0
                    val b = codec.getOutputBuffer(slot)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val bytes = if (floating) 4 else 2
                    val frames = info.size / (bytes * channels)
                    val bufferEndUs = info.presentationTimeUs + frames * 1000000L / rate
                    if (bufferEndUs >= startMs * 1000 && frames > 0) {
                        if (firstUs < 0) firstUs = info.presentationTimeUs
                        if (count + frames > native.size) native = native.copyOf(max(native.size * 2, count + frames))
                        for (frame in 0 until frames) {
                            var sum = 0f
                            for (channel in 0 until channels) {
                                val pos = info.offset + (frame * channels + channel) * bytes
                                sum += if (floating) b.getFloat(pos) else b.getShort(pos) / 32768f
                            }
                            native[count++] = sum / channels
                        }
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || bufferEndUs > endUs
                    codec.releaseOutputBuffer(slot, false)
                } else {
                    stalled++
                    check(stalled < 3000) { "Audio decoder stalled" }
                }
            }
            if (count == 0 || firstUs < 0) return FloatArray(0)
            // Position of the requested start inside the native-rate buffer; negative means the
            // decoder began after the requested time, which pads the output with silence.
            val offset = (startMs - firstUs / 1000.0) * rate / 1000.0
            return resample(native, count, rate, offset, (durationMs * RATE / 1000).toInt())
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    /** Lanczos (a = 3) resampling of `count` mono samples at `rate` to 16 kHz. */
    private fun resample(src: FloatArray, count: Int, rate: Int, offset: Double, outLength: Int): FloatArray {
        val out = FloatArray(outLength)
        if (rate == RATE) {
            val start = offset.roundToInt()
            for (i in 0 until outLength) {
                val j = start + i
                out[i] = if (j in 0 until count) src[j] else 0f
            }
            return out
        }
        val step = rate.toDouble() / RATE
        val a = 3
        for (i in 0 until outLength) {
            val x = offset + i * step
            val center = floor(x).toInt()
            var sum = 0.0
            var weight = 0.0
            for (j in center - a + 1..center + a) {
                if (j < 0 || j >= count) continue
                val d = x - j
                val w = if (abs(d) < 1e-9) 1.0 else if (abs(d) >= a) 0.0 else {
                    val pd = PI * d
                    a * sin(pd) * sin(pd / a) / (pd * pd)
                }
                sum += w * src[j]
                weight += w
            }
            out[i] = if (weight > 1e-6) (sum / weight).toFloat() else 0f
        }
        return out
    }
}

/** The bundled CTC acoustic model. Emits log-probabilities for 29 classes every 20 ms. */
object Alignment {
    /** What the running synchronization is doing right now, for live screens. */
    class Live(val book: String, val unit: String, val pieceMs: Long, val startedAt: Long)
    @Volatile var live: Live? = null
    private const val PIECE_MS = 300_000L
    private const val CONTEXT_MS = 2_000L
    const val VERSION = SyncPlan.VERSION
    fun units(book: Book) = SyncPlan.units(book)

    /** Decodes [startMs, endMs) across track boundaries into one 16 kHz buffer. */
    fun decodeRange(context: Context, book: Book, startMs: Long, endMs: Long): FloatArray {
        val out = FloatArray(((endMs - startMs) * 16).toInt())
        var base = 0L
        for (t in book.tracks) {
            val ts = base
            val te = base + t.duration
            base = te
            val s = max(startMs, ts)
            val e = min(endMs, te)
            if (e <= s) continue
            val pcm = AudioDecoder.decode(context, t.uri, s - ts, e - s)
            val at = ((s - startMs) * 16).toInt()
            System.arraycopy(pcm, 0, out, at, min(pcm.size, out.size - at))
        }
        return out
    }

    /** Synchronizes one unit and returns its sentence intervals. */
    fun alignUnit(context: Context, book: Book, text: BookText, emitter: Emitter, aligner: UnitAligner, unit: SyncUnit, cancel: AtomicBoolean, onPiece: (Long) -> Unit = {}): UnitAligner.Result? {
        val frames = ((unit.endMs - unit.startMs) / SpeechModel.FRAME_MS).toInt()
        val lp = FloatArray(frames * SpeechModel.CLASSES)
        val pieces = ArrayList<Pair<Long, Long>>()
        var cursor = unit.startMs
        while (cursor < unit.endMs) { val e = min(unit.endMs, cursor + PIECE_MS); pieces += cursor to e; cursor = e }
        val queue = ArrayBlockingQueue<Any>(1)
        val abandon = AtomicBoolean(false)
        // Hands decoded pieces to inference without ever blocking past a pause or a failure.
        fun offer(item: Any) {
            while (!cancel.get() && !abandon.get()) if (queue.offer(item, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) return
        }
        val producer = Thread {
            try {
                for ((s, e) in pieces) {
                    if (cancel.get() || abandon.get()) break
                    val ds = max(0, s - CONTEXT_MS)
                    val de = min(book.duration, e + CONTEXT_MS)
                    offer(Triple(decodeRange(context, book, ds, de), ((s - ds) * 16).toInt(), ((e - s) / SpeechModel.FRAME_MS).toInt()))
                }
            } catch (e: Throwable) {
                offer(e)
            }
        }
        producer.start()
        try {
            for ((s, _) in pieces) {
                var item: Any? = null
                while (item == null && !cancel.get()) item = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (item == null) return null
                if (item is Throwable) throw item
                @Suppress("UNCHECKED_CAST") val piece = item as Triple<FloatArray, Int, Int>
                val (pcm, first, count) = piece
                emitter.emit(pcm, first, count, lp, ((s - unit.startMs) / SpeechModel.FRAME_MS).toInt())
                onPiece(s + count * SpeechModel.FRAME_MS - unit.startMs)
            }
        } finally {
            abandon.set(true)
            producer.join()
        }
        if (cancel.get()) return null
        val margin = (SyncPlan.OVERLAP_MS / 2 / SpeechModel.FRAME_MS).toInt()
        return aligner.align(lp, frames, unit.startMs, if (unit.leadEdge) 0 else margin, if (unit.trailEdge) 0 else margin)
    }

    /** Picks the next unit: where the listener is, then the chapter being read, else forward order. */
    fun nextUnit(units: List<SyncUnit>, done: Set<String>, preferredMs: List<Long>, lastIndex: Int): SyncUnit? {
        val remaining = units.filter { it.key !in done }
        if (remaining.isEmpty()) return null
        for (ms in preferredMs) remaining.firstOrNull { ms >= it.startMs && ms < it.endMs }?.let { return it }
        return remaining.firstOrNull { it.index > lastIndex } ?: remaining.first()
    }

    /** Rough time remaining using the measured rate of this run, falling back to 40× realtime. */
    fun estimate(remainingAudioMs: Long, processedAudioMs: Long, elapsedMs: Long): String {
        val rate = if (processedAudioMs > 60_000 && elapsedMs > 0) processedAudioMs.toDouble() / elapsedMs else 40.0
        val minutes = ceil(remainingAudioMs / rate / 60000.0).toInt()
        return when {
            minutes <= 1 -> "under a minute left"
            minutes < 60 -> "about $minutes min left"
            else -> "about ${minutes / 60} h ${minutes % 60} min left"
        }
    }

    fun run(
        context: Context,
        book: Book,
        cancel: AtomicBoolean,
        limitUnits: Int = Int.MAX_VALUE,
        onProgress: (String) -> Unit = {},
    ) {
        require(book.passages.isNotEmpty() && book.tracks.isNotEmpty()) { "Attach an ebook and audio first" }
        Library(context).use { db ->
            val versions = context.getSharedPreferences("alignment-versions", 0)
            if (versions.getInt(book.id, 0) != VERSION) {
                db.invalidateAlignment(book.id)
                versions.edit().putInt(book.id, VERSION).commit()
            }
            val units = units(book)
            val done = db.doneUnits(book.id).toMutableSet()
            val state = ReadingState(context)
            if (units.all { it.key in done }) { db.status(book.id, "Synced"); return }
            db.status(book.id, "Syncing · loading speech model")
            onProgress("Loading speech model")
            val text = BookText(book)
            val session = SpeechModel.open(context)
            try {
                val emitter = Emitter(session)
                val aligner = UnitAligner(text)
                var processed = 0L
                val started = SystemClock.elapsedRealtime()
                var count = 0
                var lastIndex = -1
                while (!cancel.get() && count < limitUnits) {
                    val preferred = listOfNotNull(state.audio(book.id), chapterStart(book, state.passage(book.id)))
                    val unit = nextUnit(units, done, preferred, lastIndex) ?: break
                    val remaining = units.filter { it.key !in done }.sumOf { it.endMs - it.startMs }
                    fun progress(pieceMs: Long) {
                        live = Live(book.id, unit.key, pieceMs, started)
                        val percent = (100 * (book.duration - remaining + pieceMs) / book.duration).coerceIn(0, 99)
                        val message = "Syncing ${unit.title.ifBlank { "chapter" }} · $percent% · ${estimate(remaining - pieceMs, processed + pieceMs, SystemClock.elapsedRealtime() - started)}"
                        db.status(book.id, message)
                        onProgress(message)
                    }
                    progress(0)
                    val unitStarted = SystemClock.elapsedRealtime()
                    val result = alignUnit(context, book, text, emitter, aligner, unit, cancel) { progress(it) } ?: break
                    val elapsed = SystemClock.elapsedRealtime() - unitStarted
                    db.unitDone(book.id, unit.key, result.anchors, elapsed, unit.coreMs)
                    done += unit.key
                    processed += unit.endMs - unit.startMs
                    lastIndex = unit.index
                    count++
                    android.util.Log.i("ReaderAlignment", "unit=${unit.index} '${unit.title}' audioMs=${unit.endMs - unit.startMs} elapsedMs=$elapsed spoken=${result.spokenWords} hits=${result.anchorHits} chain=${result.chainLength} segments=${result.segments} skipped=${result.skipped} sentences=${result.anchors.size} words=${result.firstWord}..${result.lastWord} pssKB=${Debug.getPss()}")
                }
                db.status(book.id, if (units.all { it.key in done }) "Synced" else if (cancel.get()) "Paused" else "Partly synced")
            } catch (e: Exception) {
                db.status(book.id, "Paused: ${e.message}")
                throw e
            } finally {
                live = null
                session.close()
            }
        }
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** Start of the audio chapter whose title corresponds to the passage's section heading. */
    fun chapterStart(book: Book, passage: Int): Long? {
        if (book.chapters.isEmpty() || book.toc.isEmpty()) return null
        val heading = book.toc.lastOrNull { it.second <= passage }?.first ?: return null
        fun key(s: String): String {
            val words = BookText.normalize(s)
            val number = Regex("\\d+").find(s)?.value?.toLongOrNull()
            return if (number != null) "#" + number else words.filter { it !in setOf("chapter", "part", "the") }.joinToString(" ")
        }
        val k = key(heading)
        if (k.isBlank()) return null
        return book.chapters.firstOrNull { key(it.title) == k }?.start
    }

    /** Starts background synchronization when a paired book is not synced and not paused by the user. */
    /** Whether this build can sync a book on the device: only the standalone build carries the speech model. */
    val onDevice: Boolean get() = BuildConfig.STANDALONE

    fun autoStart(context: Context, book: Book?) {
        if (!onDevice) return
        if (book == null || book.passages.isEmpty() || book.tracks.isEmpty()) return
        // Books from UnisonServer carry the server's alignment; the device never syncs them itself.
        if (ReadingState(context).serverWork(book.id) != null) return
        if (PreparationService.running) return
        val sync = context.getSharedPreferences("sync", 0)
        if (sync.getBoolean("paused.${book.id}", false)) return
        val complete = Library(context).use { db ->
            context.getSharedPreferences("alignment-versions", 0).getInt(book.id, 0) == VERSION &&
                units(book).all { it.key in db.doneUnits(book.id) }
        }
        if (complete) return
        context.startForegroundService(Intent(context, PreparationService::class.java).putExtra("book", book.id))
    }

    fun pause(context: Context, bookId: String?) {
        if (bookId != null) context.getSharedPreferences("sync", 0).edit().putBoolean("paused.$bookId", true).apply()
        context.startService(Intent(context, PreparationService::class.java).setAction("pause"))
    }

    fun resume(context: Context, book: Book) {
        if (!onDevice) return
        if (ReadingState(context).serverWork(book.id) != null) return
        context.getSharedPreferences("sync", 0).edit().remove("paused.${book.id}").apply()
        context.startForegroundService(Intent(context, PreparationService::class.java).putExtra("book", book.id))
    }
}

class PreparationService : Service() {
    private val cancel = AtomicBoolean(false)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") {
            cancel.set(true)
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val id = intent?.getStringExtra("book") ?: return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("preparation", "Book synchronization", NotificationManager.IMPORTANCE_LOW))
        fun notification(message: String): Notification {
            val percent = Regex("(\\d+)%").find(message)?.groupValues?.get(1)?.toIntOrNull()
            return Notification.Builder(this, "preparation")
                .setSmallIcon(R.drawable.ic_unison_notification)
                .setContentTitle("Syncing reading & listening")
                .setContentText(message)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .apply { if (percent != null) setProgress(100, percent, false) }
                .setContentIntent(PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra("book", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(Notification.Action.Builder(null, "Pause", PendingIntent.getService(this, 8, Intent(this, PreparationService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)).build())
                .build()
        }
        startForeground(42, notification("Loading speech model"))
        running = true
        bookId = id
        cancel.set(false)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reader:preparation").apply { acquire(3 * 60 * 60 * 1000L) }
        kotlin.concurrent.thread {
            try {
                val book = Library(this).use { it.load(id) } ?: error("Book missing")
                Alignment.run(this, book, cancel) { manager.notify(42, notification(it)) }
            } catch (e: Exception) {
                android.util.Log.e("ReaderAlignment", "Synchronization paused", e)
            } finally {
                wake?.let { if (it.isHeld) it.release() }
                running = false
                bookId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                Library(this).use { db ->
                    if (db.prep(id).second == "Synced")
                        manager.notify(44, Notification.Builder(this, "preparation")
                            .setSmallIcon(R.drawable.ic_unison_notification)
                            .setContentTitle("Reading & listening are in sync")
                            .setContentText("${db.anchorCount(id)} sentences matched to the narration")
                            .setAutoCancel(true)
                            .setContentIntent(PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra("book", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                            .build())
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel.set(true)
        stopSelf()
    }

    override fun onDestroy() {
        cancel.set(true)
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        @Volatile var bookId: String? = null
    }
}
