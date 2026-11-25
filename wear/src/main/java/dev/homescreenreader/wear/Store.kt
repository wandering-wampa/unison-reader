package dev.homescreenreader.wear

import android.content.Context
import dev.homescreenreader.Chapter
import dev.homescreenreader.ServerClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** An audiobook as the watch knows it: what it is called, its tracks in order, and its chapters in book time. */
class WatchBook(val id: String, val title: String, val author: String, val narrator: String, val tracks: List<Part>, val chapters: List<Chapter>) {
    class Part(val name: String, val duration: Long)
    val duration get() = tracks.sumOf { it.duration }
    fun chapterAt(position: Long): Chapter? = chapters.lastOrNull { it.start <= position }
}

/**
 * Everything the watch keeps: the sign-in, the audiobooks it knows (one folder each under `files/books`, with the
 * book's description, its cover, the sentence each stretch of audio belongs to, and the audio once it is downloaded),
 * each book's place and speed, and what it still owes the server (places, listening sessions).
 *
 * A book's id here is the server's work id. Every value is written at once (`commit`): the playback service and the
 * screens read the same file from different threads.
 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("watch", 0)
    private val root = File(app.filesDir, "books")

    // ---- the sign-in
    var serverUrl: String
        get() = prefs.getString("serverUrl", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) { prefs.edit().putString("serverUrl", v.trim()).commit() }
    val token: String get() = prefs.getString("token", "").orEmpty()
    val user: String get() = prefs.getString("user", "").orEmpty()
    val signedIn get() = token.isNotBlank() && serverUrl.isNotBlank()
    /** This watch among the person's devices: made once, kept for as long as the app's data is. */
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).commit() }
    fun signIn(token: String, user: String) { prefs.edit().putString("token", token).putString("user", user).commit() }
    /** The client for the signed-in person, or null. */
    fun client(): ServerClient? = if (signedIn) ServerClient(serverUrl, token, deviceId) else null

    /** Signing out forgets the person entirely: the key, every place and speed, the books and their audio. The server address and this watch's id stay. */
    fun signOut() {
        val url = prefs.getString("serverUrl", null); val device = deviceId
        epoch++
        prefs.edit().clear().commit()
        prefs.edit().putString("deviceId", device).apply { if (url != null) putString("serverUrl", url) }.commit()
        root.deleteRecursively()
        synchronized(anchors) { anchors.clear() }
    }

    // ---- the books
    private fun safe(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    fun dir(id: String): File { require(safe(id)) { "Not a book id" }; return File(root, id) }
    fun cover(id: String) = File(dir(id), "cover.jpg")
    fun trackFile(id: String, index: Int) = File(dir(id), "audio/%03d".format(index))

    fun save(b: WatchBook) {
        val o = JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("narrator", b.narrator)
            .put("tracks", JSONArray().apply { b.tracks.forEach { put(JSONObject().put("name", it.name).put("duration", it.duration)) } })
            .put("chapters", JSONArray().apply { b.chapters.forEach { put(JSONObject().put("title", it.title).put("start", it.start)) } })
        val file = File(dir(b.id), "book.json"); file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp"); tmp.writeText(o.toString())
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "Could not keep ${b.title}" }
    }
    fun book(id: String): WatchBook? = runCatching {
        if (!safe(id)) return null
        val o = JSONObject(File(dir(id), "book.json").readText())
        val t = o.getJSONArray("tracks"); val c = o.getJSONArray("chapters")
        WatchBook(o.getString("id"), o.getString("title"), o.optString("author"), o.optString("narrator"),
            List(t.length()) { WatchBook.Part(t.getJSONObject(it).optString("name"), t.getJSONObject(it).getLong("duration")) },
            List(c.length()) { Chapter(c.getJSONObject(it).optString("title"), c.getJSONObject(it).getLong("start")) })
    }.getOrNull()
    fun books(): List<WatchBook> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { book(it.name) }

    /** True when every track of the book is on the watch: it plays with no connection at all. */
    fun downloaded(b: WatchBook) = b.tracks.isNotEmpty() && b.tracks.indices.all { trackFile(b.id, it).isFile }
    /** Bytes of the book's audio on the watch, part-files of an unfinished download included. */
    fun audioBytes(id: String): Long = File(dir(id), "audio").listFiles().orEmpty().sumOf { it.length() }
    fun removeAudio(id: String) { File(dir(id), "audio").deleteRecursively() }
    /** The book leaves the watch: its audio, its cover, its description. Its place stays, for when it comes back. */
    fun forget(id: String) { dir(id).deleteRecursively(); synchronized(anchors) { anchors.remove(id) } }

    /** The books on the person's shelf that can be listened to, in the order the server gave them; kept so the list shows with no connection. */
    var shelf: List<String>
        get() = prefs.getString("shelf", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) { prefs.edit().putString("shelf", v.joinToString(",")).commit() }

    /** When the server was last asked about a book (its tracks, chapters, cover and sync), and how much of the sync the watch has. */
    fun checked(id: String): Long = prefs.getLong("$id.checked", 0)
    fun checked(id: String, at: Long) { prefs.edit().putLong("$id.checked", at).commit() }
    fun anchorCount(id: String): Int = prefs.getInt("$id.anchors", 0)
    fun anchorCount(id: String, n: Int) { prefs.edit().putInt("$id.anchors", n).commit() }
    fun coverVersion(id: String): Long = prefs.getLong("$id.cover", -1)
    fun coverVersion(id: String, v: Long) { prefs.edit().putLong("$id.cover", v).commit() }

    // ---- the place and the speed
    /** The book last played here. */
    var active: String
        get() = prefs.getString("active", "").orEmpty()
        set(v) { prefs.edit().putString("active", v).commit() }
    fun audio(id: String): Long = prefs.getLong("$id.audio", 0)
    fun audio(id: String, position: Long) { prefs.edit().putLong("$id.audio", position.coerceAtLeast(0)).commit() }
    /** The place last exchanged with the server (sent or taken), or -1 when none has been: the place moved here when it differs. */
    fun pushed(id: String): Long = prefs.getLong("$id.pushed", -1)
    /** The server's time of the place this watch last had from it: what a save is based on. */
    fun serverUpdated(id: String): Long = prefs.getLong("$id.serverUpdated", 0)
    fun markPushed(id: String, audio: Long, updated: Long) { prefs.edit().putLong("$id.pushed", audio).putLong("$id.serverUpdated", updated).commit() }
    /** Another device's place, far from this watch's, waiting for the person to choose: audio, the server's time, and the device's name. */
    class Elsewhere(val audio: Long, val passage: Int, val updated: Long, val by: String)
    fun elsewhere(id: String): Elsewhere? =
        prefs.getLong("$id.elsewhereAt", 0).takeIf { it > serverUpdated(id) }?.let { Elsewhere(prefs.getLong("$id.elsewhere", 0), prefs.getInt("$id.elsewherePassage", 0), it, prefs.getString("$id.elsewhereBy", "").orEmpty()) }
    fun elsewhere(id: String, audio: Long, passage: Int, updated: Long, by: String) { prefs.edit().putLong("$id.elsewhere", audio).putInt("$id.elsewherePassage", passage).putLong("$id.elsewhereAt", updated).putString("$id.elsewhereBy", by).commit() }
    fun clearElsewhere(id: String) { prefs.edit().remove("$id.elsewhere").remove("$id.elsewherePassage").remove("$id.elsewhereAt").remove("$id.elsewhereBy").commit() }

    /**
     * The text's place that came with the audio place the watch last took, and whether the two were together then
     * (reading and listening in step) or apart (reading in one part, listening in another). The watch sends the text's
     * place back as it was unless they were together; a book never taken from the server starts with them together.
     */
    fun passage(id: String): Int = prefs.getInt("$id.passage", 0)
    fun together(id: String): Boolean = prefs.getBoolean("$id.together", true)
    fun textPlace(id: String, passage: Int, together: Boolean) { prefs.edit().putInt("$id.passage", passage).putBoolean("$id.together", together).commit() }

    /** The book's speed: its own once set (here or on another device), else the speed last chosen on this watch. */
    fun speed(id: String): Float = prefs.getFloat("$id.speed", prefs.getFloat("speed", 1f))
    fun speed(id: String, v: Float, local: Boolean) { prefs.edit().putFloat("$id.speed", v).putBoolean("$id.speedUnsent", local).apply { if (local) putFloat("speed", v) }.commit() }
    fun speedUnsent(id: String) = prefs.getBoolean("$id.speedUnsent", false)
    fun speedSent(id: String) { prefs.edit().putBoolean("$id.speedUnsent", false).commit() }

    /** When the book was last played here, for the order of the list. */
    fun listened(id: String): Long = prefs.getLong("$id.listened", 0)
    fun listened(id: String, at: Long) { prefs.edit().putLong("$id.listened", at).commit() }

    // ---- listening sessions the statistics are made from, kept until the server has them
    fun pendingSessions(): JSONArray = runCatching { JSONArray(prefs.getString("sessions", "[]")) }.getOrDefault(JSONArray())
    fun queueSession(work: String, started: Long, ended: Long) {
        if (ended - started < 3000) return
        synchronized(Store::class.java) {
            val a = pendingSessions()
            a.put(JSONObject().put("work", work).put("kind", "listen").put("started", started).put("ended", ended).put("sentences", 0).put("pages", 0))
            while (a.length() > 400) a.remove(0)
            prefs.edit().putString("sessions", a.toString()).commit()
        }
    }
    /** Exactly the sessions that were sent leave the queue; any queued meanwhile stay. */
    fun clearSessions(sent: JSONArray) {
        synchronized(Store::class.java) {
            val gone = (0 until sent.length()).map { sent.getJSONObject(it).let { s -> s.getString("work") + s.getLong("started") } }.toSet()
            val a = pendingSessions(); val kept = JSONArray()
            for (i in 0 until a.length()) { val s = a.getJSONObject(i); if (s.getString("work") + s.getLong("started") !in gone) kept.put(s) }
            prefs.edit().putString("sessions", kept.toString()).commit()
        }
    }

    // ---- which sentence of the ebook a moment of audio belongs to (the server's alignment), so the place the watch
    // saves opens the text at the right sentence on the phone and the web
    private class Anchors(val starts: LongArray, val passages: IntArray)
    fun saveAnchors(id: String, sorted: List<Pair<Long, Int>>) {
        val file = File(dir(id), "anchors.bin"); file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out -> out.writeInt(sorted.size); for ((start, passage) in sorted) { out.writeLong(start); out.writeInt(passage) } }
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "Could not keep the sync" }
        synchronized(anchors) { anchors.remove(id) }
    }
    fun hasAnchors(id: String) = File(dir(id), "anchors.bin").isFile
    /** The sentence narrated at this moment, or 0 when the book has no text matched to it (or none on this watch yet). */
    fun passageAt(id: String, position: Long): Int {
        val a = synchronized(anchors) {
            anchors.getOrPut(id) {
                runCatching {
                    DataInputStream(File(dir(id), "anchors.bin").inputStream().buffered()).use { input ->
                        val n = input.readInt(); val starts = LongArray(n); val passages = IntArray(n)
                        for (i in 0 until n) { starts[i] = input.readLong(); passages[i] = input.readInt() }
                        Anchors(starts, passages)
                    }
                }.getOrDefault(Anchors(LongArray(0), IntArray(0)))
            }
        }
        var low = 0; var high = a.starts.size - 1; var found = -1
        while (low <= high) { val mid = (low + high) ushr 1; if (a.starts[mid] <= position) { found = mid; low = mid + 1 } else high = mid - 1 }
        return if (found >= 0) a.passages[found] else 0
    }
    /** Where the narration reaches this sentence (or the next one that was matched), or null when the watch cannot say. */
    fun audioAt(id: String, passage: Int): Long? {
        passageAt(id, 0)
        val a = synchronized(anchors) { anchors[id] } ?: return null
        var best = -1
        for (i in a.passages.indices) if (a.passages[i] >= passage && (best < 0 || a.passages[i] < a.passages[best])) { best = i; if (a.passages[i] == passage) break }
        return if (best >= 0) a.starts[best] else null
    }

    companion object {
        /** Goes up at every sign-out: an exchange with the server that began before it must not write its answer down for the next person. */
        @Volatile var epoch = 0
            private set
        private val anchors = HashMap<String, Anchors>()
    }
}
