package dev.homescreenreader

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.text.Normalizer
import kotlin.math.*

/**
 * The sync engine, shared by the app and UnisonServer: a small CTC acoustic model (NVIDIA
 * QuartzNet 15x5, English, int8) emits per-character log-probabilities on a 20 ms grid; a greedy
 * decode is matched to the book with unique five-word phrases into a monotonic chain of anchors;
 * the text between anchors is force-aligned with CTC Viterbi, giving every sentence a measured
 * start and end. Audio decoding and storage belong to each host.
 */

object SpeechModel {
    const val CLASSES = 29
    const val BLANK = 28
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = 320
    const val VOCAB = " abcdefghijklmnopqrstuvwxyz'"
    const val ASSET = "models/quartznet15x5-en.int8.onnx"
    /** Measured median delay between speech onset and the first emitted character. */
    const val START_LAG_MS = 270L

    fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    /** Opens the model from its ONNX bytes; the app reads them from assets, the server from its image. */
    fun open(bytes: ByteArray, threads: Int = threads()): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtEnvironment.getEnvironment().createSession(bytes, options)
    }
}

/** Runs the acoustic model over 16 kHz audio in overlapping windows on a global 20 ms grid. */
class Emitter(private val session: OrtSession) {
    private val env = OrtEnvironment.getEnvironment()
    private val window = 38 * 50
    private val context = 2 * 50

    /**
     * Writes emissions for `frames` frames of `pcm` (starting at sample `firstSample`) into
     * `out` at frame index `outFrame`. Audio before/after the requested span inside `pcm` is
     * used as acoustic context only.
     */
    fun emit(pcm: FloatArray, firstSample: Int, frames: Int, out: FloatArray, outFrame: Int) {
        val total = pcm.size / SpeechModel.SAMPLES_PER_FRAME
        val base = firstSample / SpeechModel.SAMPLES_PER_FRAME
        var f = 0
        while (f < frames) {
            val a = max(0, base + f - context)
            val b = min(total, base + f + window + context)
            val length = (b - a) * SpeechModel.SAMPLES_PER_FRAME
            if (length <= 0) break
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm, a * SpeechModel.SAMPLES_PER_FRAME, length), longArrayOf(1, 1, length.toLong()))
            tensor.use {
                session.run(mapOf("signal" to it)).use { result ->
                    val value = result[0] as OnnxTensor
                    val produced = value.info.shape[1].toInt()
                    val buffer = value.floatBuffer
                    val keep0 = base + f - a
                    val keep1 = min(produced, min(keep0 + window, keep0 + frames - f))
                    if (keep1 > keep0) {
                        buffer.position(keep0 * SpeechModel.CLASSES)
                        buffer.get(out, (outFrame + f) * SpeechModel.CLASSES, (keep1 - keep0) * SpeechModel.CLASSES)
                    }
                    f += window
                }
            }
        }
    }
}

class Spoken(val word: String, val start: Int, val end: Int)

/** Normalized book text with unique-phrase lookup. Built once per book. */
class BookText(book: Book) {
    val words: Array<String>
    val owner: IntArray
    val passageFirst = IntArray(book.passages.size) { -1 }
    val passageLast = IntArray(book.passages.size) { -1 }
    private val hashes: LongArray
    private val positions: IntArray

    init {
        val w = ArrayList<String>()
        val o = ArrayList<Int>()
        book.passages.forEachIndexed { p, s ->
            val tokens = normalize(s.text)
            if (tokens.isNotEmpty()) {
                passageFirst[p] = w.size
                passageLast[p] = w.size + tokens.size - 1
            }
            tokens.forEach { w += it; o += p }
        }
        words = w.toTypedArray()
        owner = o.toIntArray()
        val n = max(0, words.size - PHRASE + 1)
        val keys = LongArray(n) { hash(words, it) }
        val order = (0 until n).sortedBy { keys[it] }
        hashes = LongArray(n) { keys[order[it]] }
        positions = IntArray(n) { order[it] }
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && hashes[j + 1] == hashes[i]) j++
            if (j > i) for (k in i..j) positions[k] = -1
            i = j + 1
        }
    }

    /** Position of the unique phrase starting at `index` in `spoken`, or -1 if absent or ambiguous. */
    fun unique(spoken: List<Spoken>, index: Int): Int {
        val h = hash(spoken, index)
        var lo = 0
        var hi = hashes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                hashes[mid] < h -> lo = mid + 1
                hashes[mid] > h -> hi = mid - 1
                else -> return positions[mid]
            }
        }
        return -1
    }

    companion object {
        const val PHRASE = 5
        private val ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split(' ')
        private val TENS = "x x twenty thirty forty fifty sixty seventy eighty ninety".split(' ')
        private val ABBREVIATIONS = mapOf("mr" to "mister", "mrs" to "missus", "dr" to "doctor", "st" to "saint", "jr" to "junior", "sr" to "senior", "vs" to "versus", "etc" to "etcetera", "ms" to "miz", "mt" to "mount")
        private val TOKEN = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}]+)?|\\d+(?:,\\d{3})+|\\d+")

        fun numberWords(n: Long): String = when {
            n < 20 -> ONES[n.toInt()]
            n < 100 -> TENS[(n / 10).toInt()] + if (n % 10 == 0L) "" else " " + ONES[(n % 10).toInt()]
            n < 1000 -> ONES[(n / 100).toInt()] + " hundred" + if (n % 100 == 0L) "" else " " + numberWords(n % 100)
            n in 1100..1999 && n % 100 != 0L -> numberWords(n / 100) + " " + numberWords(n % 100)
            n < 1000000 -> numberWords(n / 1000) + " thousand" + if (n % 1000 == 0L) "" else " " + numberWords(n % 1000)
            else -> n.toString().map { ONES[it - '0'] }.joinToString(" ")
        }

        /** Lowercase ASCII words using only the model's alphabet; digits are spelled out. */
        fun normalize(text: String): List<String> {
            val out = ArrayList<String>()
            for (m in TOKEN.findAll(text)) {
                val raw = m.value.replace('’', '\'').replace(",", "")
                if (raw.all { it.isDigit() }) {
                    val n = raw.toLongOrNull() ?: continue
                    out += numberWords(n).split(' ')
                    continue
                }
                val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
                val clean = ascii.filter { it in 'a'..'z' || it == '\'' }.trim('\'')
                if (clean.isEmpty()) continue
                out += ABBREVIATIONS[clean] ?: clean
            }
            return out
        }

        private fun mix(h0: Long, s: String): Long {
            var h = h0
            for (c in s) h = (h xor c.code.toLong()) * 1099511628211L
            return (h xor 0x1FL) * 1099511628211L
        }

        fun hash(words: Array<String>, index: Int): Long {
            var h = -3750763034362895579L
            for (i in index until index + PHRASE) h = mix(h, words[i])
            return h
        }

        fun hash(spoken: List<Spoken>, index: Int): Long {
            var h = -3750763034362895579L
            for (i in index until index + PHRASE) h = mix(h, spoken[i].word)
            return h
        }
    }
}

/** Aligns one unit of emissions to the book and returns per-sentence audio intervals. */
class UnitAligner(private val text: BookText) {
    class Result(val anchors: List<Anchor>, val spokenWords: Int, val anchorHits: Int, val chainLength: Int, val segments: Int, val skipped: Int, val firstWord: Int, val lastWord: Int)

    private var back = ByteArray(0)

    fun greedy(lp: FloatArray, frames: Int): List<Spoken> {
        val out = ArrayList<Spoken>()
        val word = StringBuilder()
        var start = 0
        var prev = -1
        for (f in 0 until frames) {
            var best = 0
            var bestValue = lp[f * SpeechModel.CLASSES]
            for (c in 1 until SpeechModel.CLASSES) {
                val v = lp[f * SpeechModel.CLASSES + c]
                if (v > bestValue) { bestValue = v; best = c }
            }
            if (best != prev && best != SpeechModel.BLANK) {
                if (best == 0) {
                    if (word.isNotEmpty()) { out += Spoken(word.toString(), start, f); word.setLength(0) }
                } else {
                    if (word.isEmpty()) start = f
                    word.append(SpeechModel.VOCAB[best])
                }
            }
            prev = best
        }
        if (word.isNotEmpty()) out += Spoken(word.toString(), start, frames)
        return out
    }

    /**
     * @param unitStartMs absolute audio time of frame 0
     * @param leadMargin frames at the start whose sentences are discarded (piece boundary), 0 at a real chapter start
     * @param trailMargin same for the end of the unit
     */
    fun align(lp: FloatArray, frames: Int, unitStartMs: Long, leadMargin: Int, trailMargin: Int): Result {
        val spoken = greedy(lp, frames)
        val empty = Result(emptyList(), spoken.size, 0, 0, 0, 0, -1, -1)
        if (spoken.size < BookText.PHRASE) return empty
        // Unique-phrase hits in audio order.
        val hitAudio = ArrayList<Int>()
        val hitText = ArrayList<Int>()
        for (j in 0..spoken.size - BookText.PHRASE) {
            val p = text.unique(spoken, j)
            if (p >= 0) { hitAudio += j; hitText += p }
        }
        if (hitAudio.isEmpty()) return empty
        // Longest chain increasing in text position (patience sorting).
        val tails = ArrayList<Int>()
        val tailHit = ArrayList<Int>()
        val previous = IntArray(hitAudio.size) { -1 }
        for (h in hitAudio.indices) {
            val ti = hitText[h]
            var lo = 0
            var hi = tails.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (tails[mid] < ti) lo = mid + 1 else hi = mid }
            if (lo == tails.size) { tails += ti; tailHit += h } else { tails[lo] = ti; tailHit[lo] = h }
            previous[h] = if (lo > 0) tailHit[lo - 1] else -1
        }
        val chain = ArrayList<Int>()
        var h = tailHit.last()
        while (h != -1) { chain += h; h = previous[h] }
        chain.reverse()
        // Reject anchors implying an implausible narration rate between neighbours.
        val clean = ArrayList<Int>()
        for (c in chain) {
            if (clean.isNotEmpty()) {
                val last = clean.last()
                val frameDelta = spoken[hitAudio[c]].start - spoken[hitAudio[last]].start
                val wordDelta = hitText[c] - hitText[last]
                if (frameDelta <= 0 || wordDelta <= 0) continue
                val wordsPerSecond = wordDelta / (frameDelta / 50.0)
                if (wordsPerSecond !in 0.5..8.0) continue
            }
            clean += c
        }
        if (clean.size < 2) return Result(emptyList(), spoken.size, hitAudio.size, clean.size, 0, 0, -1, -1)
        // Thin to roughly every 30 words.
        val thin = ArrayList<Int>()
        thin += clean.first()
        for (c in clean.drop(1)) if (hitText[c] - hitText[thin.last()] >= 30) thin += c
        if (thin.last() != clean.last()) thin += clean.last()
        // Segments of text between anchors, aligned to the frames between the anchors.
        var textA = text.passageFirst[text.owner[hitText[thin.first()]]]
        // Headings and section numbers directly before the first sentence are usually narrated.
        var guard = 0
        while (guard < 2 && textA > 0) {
            val p = text.owner[textA - 1]
            val length = text.passageLast[p] - text.passageFirst[p] + 1
            if (length in 1..4) { textA = text.passageFirst[p]; guard++ } else break
        }
        val rangeStart = textA
        var frameA = 0
        val wordStart = IntArray(text.words.size)
        val wordEnd = IntArray(text.words.size)
        val wordScore = FloatArray(text.words.size)
        val timed = HashSet<Int>()
        var segments = 0
        var skipped = 0
        fun segment(a: Int, b: Int, fa: Int, fb: Int) {
            segments++
            if (b < a || fb <= fa) { skipped++; return }
            val chars = (a..b).sumOf { text.words[it].length + 1 } - 1
            val states = 2 * chars + 1
            val t = fb - fa
            if (t.toLong() * states > MAX_CELLS || t < chars) { skipped++; return }
            val labels = IntArray(chars)
            var ci = 0
            for (wi in a..b) {
                if (wi > a) labels[ci++] = 0
                for (ch in text.words[wi]) labels[ci++] = SpeechModel.VOCAB.indexOf(ch).coerceAtLeast(0)
            }
            val r = viterbi(lp, fa, t, labels) ?: run { skipped++; return }
            if (r.score < MIN_SEGMENT_SCORE) { skipped++; return }
            ci = 0
            for (wi in a..b) {
                val n = text.words[wi].length
                wordStart[wi] = fa + r.first[ci]
                wordEnd[wi] = fa + r.last[ci + n - 1] + 1
                wordScore[wi] = r.score
                timed += wi
                ci += n + 1
            }
        }
        for (c in thin.drop(1)) {
            val anchorText = hitText[c]
            val anchorFrame = spoken[hitAudio[c]].start
            segment(textA, anchorText - 1, frameA, anchorFrame)
            textA = anchorText
            frameA = anchorFrame
        }
        val textB = text.passageLast[text.owner[hitText[thin.last()]]]
        segment(textA, textB, frameA, frames)
        // Sentence intervals.
        val firstWord = rangeStart
        val pa = text.owner[firstWord]
        val pb = text.owner[textB]
        val starts = LongArray(pb - pa + 1) { -1 }
        val ends = LongArray(pb - pa + 1)
        val scores = FloatArray(pb - pa + 1)
        for (p in pa..pb) {
            val f = text.passageFirst[p]
            if (f < 0) continue
            val l = text.passageLast[p]
            var s = Int.MAX_VALUE
            var e = -1
            var chars = 0
            var sc = 0f
            var n = 0
            for (wi in f..l) {
                if (wi !in timed) continue
                s = min(s, wordStart[wi]); e = max(e, wordEnd[wi]); chars += text.words[wi].length; sc += wordScore[wi]; n++
            }
            if (n == 0 || n < l - f + 1) continue
            val minimum = if (chars > 6) 25 * chars else SpeechModel.FRAME_MS
            if ((e - s) * SpeechModel.FRAME_MS < minimum) continue
            if (s < leadMargin || e > frames - trailMargin) continue
            starts[p - pa] = s.toLong(); ends[p - pa] = e.toLong(); scores[p - pa] = sc / n
        }
        // Lag correction, then make consecutive sentences contiguous so narration never falls
        // into an unmatched gap between two matched sentences.
        val anchors = ArrayList<Anchor>()
        var previousEnd = -1L
        val ordered = (pa..pb).filter { starts[it - pa] >= 0 }
        for ((k, p) in ordered.withIndex()) {
            var startMs = unitStartMs + starts[p - pa] * SpeechModel.FRAME_MS - SpeechModel.START_LAG_MS
            var endMs = unitStartMs + ends[p - pa] * SpeechModel.FRAME_MS
            if (previousEnd >= 0 && startMs < previousEnd) startMs = previousEnd
            startMs = max(startMs, unitStartMs)
            if (k + 1 < ordered.size) {
                val next = ordered[k + 1]
                val nextStart = max(unitStartMs + starts[next - pa] * SpeechModel.FRAME_MS - SpeechModel.START_LAG_MS, endMs)
                endMs = nextStart
            }
            if (endMs <= startMs) endMs = startMs + SpeechModel.FRAME_MS
            previousEnd = endMs
            anchors += Anchor(p, startMs, endMs, exp(scores[p - pa].toDouble()).coerceIn(0.0, 1.0))
        }
        return Result(anchors, spoken.size, hitAudio.size, clean.size, segments, skipped, firstWord, textB)
    }

    private class Path(val first: IntArray, val last: IntArray, val score: Float)

    /** CTC Viterbi alignment of `labels` to frames [offset, offset + t) of `lp`. */
    private fun viterbi(lp: FloatArray, offset: Int, t: Int, labels: IntArray): Path? {
        val l = labels.size
        val s = 2 * l + 1
        if (t < l) return null
        val ext = IntArray(s) { if (it % 2 == 1) labels[(it - 1) / 2] else SpeechModel.BLANK }
        val skip = BooleanArray(s) { it >= 3 && it % 2 == 1 && labels[(it - 1) / 2] != labels[(it - 3) / 2] }
        if (back.size < t * s) back = ByteArray(t * s)
        var score = FloatArray(s) { NEG }
        var next = FloatArray(s)
        val c = SpeechModel.CLASSES
        score[0] = lp[offset * c + ext[0]]
        score[1] = lp[offset * c + ext[1]]
        for (f in 1 until t) {
            val row = f * s
            val e = (offset + f) * c
            for (i in 0 until s) {
                var best = score[i]
                var arg = 0
                if (i >= 1 && score[i - 1] > best) { best = score[i - 1]; arg = 1 }
                if (skip[i] && score[i - 2] > best) { best = score[i - 2]; arg = 2 }
                next[i] = if (best <= NEG) NEG else best + lp[e + ext[i]]
                back[row + i] = arg.toByte()
            }
            val swap = score; score = next; next = swap
        }
        var state = if (score[s - 1] >= score[s - 2]) s - 1 else s - 2
        val best = score[state]
        if (best <= NEG) return null
        val first = IntArray(l) { -1 }
        val last = IntArray(l) { -1 }
        for (f in t - 1 downTo 0) {
            if (state % 2 == 1) {
                val li = (state - 1) / 2
                first[li] = f
                if (last[li] < 0) last[li] = f
            }
            state -= back[f * s + state]
        }
        return Path(first, last, best / t)
    }

    companion object {
        private const val NEG = -1e30f
        private const val MAX_CELLS = 24L * 1024 * 1024
        /** Mean per-frame log-probability below which a segment is treated as not narrated. */
        private const val MIN_SEGMENT_SCORE = -1.5f
    }
}

/** How a book is cut into synchronization units: one per chapter, long chapters in bounded pieces. */
object SyncPlan {
    /** A chapter, or a bounded piece of a long chapter, that is synchronized as one unit. */
    data class SyncUnit(val index: Int, val chapter: Int, val title: String, val startMs: Long, val endMs: Long, val leadEdge: Boolean, val trailEdge: Boolean, val coreMs: Long) {
        val key get() = "$startMs-$endMs"
    }

    /** Alignment data version; a change invalidates stored alignments in the app and the server alike. */
    const val VERSION = 3

    private const val MAX_UNIT_MS = 40L * 60 * 1000
    /** Overlap between the pieces of a long chapter; half of it is the alignment margin on each inner edge. */
    const val OVERLAP_MS = 30_000L

    fun units(book: Book): List<SyncUnit> {
        val duration = book.duration
        if (duration <= 0) return emptyList()
        val starts = (book.chapters.map { it.start.coerceIn(0, duration) }.distinct().sorted().ifEmpty { listOf(0L) }).toMutableList()
        if (starts.first() != 0L) starts.add(0, 0L)
        val units = ArrayList<SyncUnit>()
        for ((ci, s) in starts.withIndex()) {
            val e = starts.getOrElse(ci + 1) { duration }
            if (e - s < 1000) continue
            val title = book.chapters.lastOrNull { it.start <= s }?.title ?: "Part ${ci + 1}"
            val pieces = ceil((e - s).toDouble() / MAX_UNIT_MS).toInt().coerceAtLeast(1)
            val length = (e - s) / pieces
            for (k in 0 until pieces) {
                val ps = s + k * length
                val pe = if (k == pieces - 1) e else ps + length
                val lead = k == 0
                val trail = k == pieces - 1
                units += SyncUnit(units.size, ci, title, if (lead) ps else max(s, ps - OVERLAP_MS), if (trail) pe else min(e, pe + OVERLAP_MS), lead, trail, pe - ps)
            }
        }
        return units
    }
}
