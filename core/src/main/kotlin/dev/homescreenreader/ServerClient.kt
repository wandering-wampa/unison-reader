package dev.homescreenreader

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Talks to UnisonServer: the library it holds, the files it serves, and the reading position it keeps. */
/**
 * Debug builds only, for testing a book the server is still syncing when none is: the work named here is shown as
 * half-synced (shelf "preparing", this percent, the first part of its alignment). Set from the `debug` preferences
 * (`simulatePreparing` = "<work id>:<percent>"); never set in a release build.
 */
object DebugServer { @Volatile var preparing: Pair<String, Int>? = null }

class ServerClient(base: String, private val token: String, private val device: String) {
    val base = normalize(base)

    class SeriesRef(val name: String, val sequence: Double?) {
        val label get() = if (sequence == null) name else "$name #${if (sequence == Math.floor(sequence)) sequence.toLong().toString() else sequence.toString()}"
    }
    class SeriesSummary(val name: String, val works: Int, val ready: Int, val duration: Long, val authors: List<String>, val cover: String?)
    class PersonSummary(val name: String, val works: Int, val ready: Int)
    class Work(
        val id: String, val title: String, val author: String, val narrator: String, val hasEbook: Boolean, val hasAudio: Boolean, val ebookFormat: String,
        val duration: Long, val passages: Int, val status: String, val syncedPercent: Int, val shelf: String, val cover: Boolean,
        val subtitle: String = "", val description: String = "", val publishedYear: String = "", val publisher: String = "", val rating: Double = 0.0,
        val genres: List<String> = emptyList(), val series: List<SeriesRef> = emptyList(), val authors: List<String> = emptyList(), val narrators: List<String> = emptyList(),
        /** Not 0 when an admin chose the cover on the server: part of the name the cover is kept under, so a new choice is fetched. */
        val coverVersion: Long = 0,
    )
    class TrackInfo(val index: Int, val name: String, val duration: Long)
    class Detail(val work: Work, val tracks: List<TrackInfo>, val chapters: List<Chapter>, val sentencesAligned: Int, val position: Position?)
    /** `by`: the name of the device that saved it, empty when the server does not say. */
    class Position(val passage: Int, val audio: Long, val updated: Long, val by: String = "")

    fun health(): JSONObject = JSONObject(text("/api/health"))

    fun works(query: String = "", shelf: String? = null): List<Work> {
        val params = listOfNotNull(query.takeIf { it.isNotBlank() }?.let { "q=" + encode(it) }, shelf?.let { "shelf=" + encode(it) }).joinToString("&")
        return rows(text("/api/works" + if (params.isBlank()) "" else "?$params"))
    }
    fun series(): List<SeriesSummary> {
        val a = JSONArray(text("/api/series"))
        return List(a.length()) { i -> val s = a.getJSONObject(i); SeriesSummary(s.getString("name"), s.getInt("works"), s.getInt("ready"), s.getLong("duration"), strings(s.optJSONArray("authors")), s.optString("cover").takeIf { it.isNotBlank() && it != "null" }) }
    }
    fun seriesWorks(name: String): List<Work> = rows(text("/api/series/" + encode(name).replace("+", "%20")))
    fun authors(): List<PersonSummary> = people(text("/api/authors"))
    fun authorWorks(name: String): List<Work> = rows(text("/api/authors/" + encode(name).replace("+", "%20")))
    fun narrators(): List<PersonSummary> = people(text("/api/narrators"))

    private fun rows(json: String): List<Work> {
        val rows = JSONArray(json)
        return List(rows.length()) { i -> val row = rows.getJSONObject(i); work(row.getJSONObject("work"), row.getString("shelf")) }
    }
    private fun people(json: String): List<PersonSummary> {
        val a = JSONArray(json)
        return List(a.length()) { i -> val p = a.getJSONObject(i); PersonSummary(p.getString("name"), p.getInt("works"), p.getInt("ready")) }
    }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }

    fun detail(id: String): Detail {
        val o = JSONObject(text("/api/works/$id"))
        val tracks = o.getJSONArray("tracks").let { a -> List(a.length()) { i -> val t = a.getJSONObject(i); TrackInfo(t.getInt("index"), t.getString("name"), t.getLong("duration")) } }
        val chapters = o.getJSONArray("chapters").let { a -> List(a.length()) { i -> val c = a.getJSONObject(i); Chapter(c.getString("title"), c.getLong("start")) } }
        return Detail(work(o.getJSONObject("work"), o.getString("shelf")), tracks, chapters, o.optInt("sentencesAligned"), o.optJSONObject("position")?.let(::position))
    }

    /** The server's parse of the ebook: its sentences and table of contents, which its alignment indexes. */
    fun passages(id: String): Pair<List<Passage>, List<Pair<String, Int>>> {
        val o = JSONObject(text("/api/works/$id/passages"))
        val p = o.getJSONArray("passages"); val t = o.getJSONArray("toc")
        return List(p.length()) { i -> val x = p.getJSONObject(i); Passage(x.getString("text"), x.optString("html"), x.getInt("section"), x.optInt("offset"), x.optInt("block", -1)) } to
            List(t.length()) { i -> val x = t.getJSONObject(i); x.getString("title") to x.getInt("passage") }
    }

    fun alignment(id: String): List<Anchor> {
        val a = JSONArray(text("/api/works/$id/alignment"))
        val all = List(a.length()) { i -> val x = a.getJSONObject(i); Anchor(x.getInt("passage"), x.getLong("start"), x.getLong("end"), x.getDouble("confidence")) }
        val sim = DebugServer.preparing?.takeIf { it.first == id } ?: return all
        return all.sortedBy { it.passage }.take(all.size * sim.second / 100)
    }

    fun position(id: String): Position? = runCatching { position(JSONObject(text("/api/works/$id/position"))) }.getOrNull()?.takeIf { it.updated > 0 }

    class UserInfo(val id: Long, val name: String, val role: String)
    class ShelfEntry(val work: String, val state: String, val added: Long, val finishedAt: Long?, val position: Position?)
    /** A series in which this person has finished a book: how many of how many, and the work that comes next (if any). */
    class SeriesProgress(val name: String, val total: Int, val finished: Int, val current: String?, val next: String?, val lastFinished: Long)

    /** Name and password for a device token; the client's own token is not needed for this call. */
    fun login(name: String, password: String, deviceName: String): Pair<String, UserInfo> {
        val o = JSONObject(text("/api/login", "POST", JSONObject().put("name", name).put("password", password).put("device", deviceName).toString()))
        return o.getString("token") to userInfo(o.getJSONObject("user"))
    }
    fun me(): UserInfo = userInfo(JSONObject(text("/api/me")).getJSONObject("user"))

    // ---- Signing in a watch by a code: the watch asks for a code and shows it; a person signed in elsewhere approves it.
    /** What a watch is given when it asks to sign in: the code to show, the secret to ask with, when the code ends, and how often to ask (seconds). */
    class LinkStarted(val code: String, val secret: String, val expires: Long, val poll: Int)
    /** The device waiting behind a code, as the person approving sees it. */
    class LinkInfo(val device: String, val address: String, val created: Long)
    /** The watch's side, needing no token: a code for this device. */
    fun linkStart(deviceName: String): LinkStarted =
        JSONObject(text("/api/link/start", "POST", JSONObject().put("device", deviceName).toString())).let { LinkStarted(it.getString("code"), it.getString("secret"), it.getLong("expires"), it.optInt("poll", 2).coerceIn(1, 30)) }
    /** The watch's side: null while nobody has approved the code, then the watch's own token and whose it is, once. A code that has ended throws ("Server answered 410"). */
    fun linkClaim(secret: String): Pair<String, UserInfo>? {
        val o = JSONObject(text("/api/link/claim", "POST", JSONObject().put("secret", secret).toString()))
        return if (o.optString("status") == "approved") o.getString("token") to userInfo(o.getJSONObject("user")) else null
    }
    private fun linkInfo(o: JSONObject) = LinkInfo(o.getString("device"), o.optString("address"), o.optLong("created"))
    /** The signed-in side: which device shows this code. */
    fun linkInfo(code: String): LinkInfo = linkInfo(JSONObject(text("/api/me/link/" + encode(code))))
    /** The signed-in side: yes, sign that device in as me. */
    fun linkApprove(code: String): LinkInfo = linkInfo(JSONObject(text("/api/me/link/" + encode(code), "POST")))
    class Device(val token: String, val device: String, val created: Long, val lastSeen: Long)
    fun devices(): List<Device> { val a = JSONObject(text("/api/me")).getJSONArray("devices"); return List(a.length()) { i -> val d = a.getJSONObject(i); Device(d.getString("token"), d.getString("device"), d.getLong("created"), d.getLong("lastSeen")) } }
    fun signOutDevice(tokenPrefix: String) { text("/api/me/tokens/" + encode(tokenPrefix.removeSuffix("…")), "DELETE") }
    fun changePassword(current: String, password: String) { text("/api/me/password", "POST", JSONObject().put("current", current).put("password", password).toString()) }
    fun shelf(): List<ShelfEntry> {
        val a = JSONArray(text("/api/me/shelf"))
        return List(a.length()) { i -> val e = a.getJSONObject(i); ShelfEntry(e.getString("work"), e.getString("state"), e.getLong("added"), if (e.isNull("finishedAt")) null else e.getLong("finishedAt"), e.optJSONObject("position")?.let(::position)) }
    }
    fun seriesProgress(): List<SeriesProgress> {
        val a = JSONArray(text("/api/me/series"))
        return List(a.length()) { i -> val p = a.getJSONObject(i); SeriesProgress(p.getString("name"), p.getInt("total"), p.getInt("finished"), p.optString("current").takeIf { it.isNotBlank() && it != "null" }, p.optString("next").takeIf { it.isNotBlank() && it != "null" }, p.optLong("lastFinished")) }
    }
    /** The book's speed for this person, or null when they never set one. */
    fun speed(work: String): Float? = JSONObject(text("/api/works/$work/speed")).let { if (it.optLong("updated") > 0) it.getDouble("speed").toFloat() else null }
    fun putSpeed(work: String, speed: Float) { text("/api/works/$work/speed", "PUT", JSONObject().put("speed", speed.toDouble()).toString()) }
    fun shelve(work: String, state: String, finishedAt: Long?) { text("/api/me/shelf/$work", "PUT", JSONObject().put("state", state).apply { if (finishedAt != null) put("finishedAt", finishedAt) }.toString()) }
    fun unshelve(work: String) { text("/api/me/shelf/$work", "DELETE") }
    fun users(): List<UserInfo> { val a = JSONArray(text("/api/users")); return List(a.length()) { userInfo(a.getJSONObject(it)) } }
    fun createUser(name: String, password: String, role: String = "member"): UserInfo = userInfo(JSONObject(text("/api/users", "POST", JSONObject().put("name", name).put("password", password).put("role", role).toString())))
    fun deleteUser(id: Long) { text("/api/users/$id", "DELETE") }
    /** An admin gives someone a new password (their own goes through changePassword, which asks for the current one). */
    fun setUserPassword(id: Long, password: String) { text("/api/users/$id/password", "POST", JSONObject().put("password", password).toString()) }
    private fun userInfo(o: JSONObject) = UserInfo(o.getLong("id"), o.getString("name"), o.getString("role"))

    /** One of the person's lists: Want to read (`kind` "want") or a collection made by hand ("list"), with its books' work ids. */
    class ListInfo(val id: Long, val name: String, val kind: String, val works: List<String>) {
        /** How the server's routes name it. */
        val key get() = if (kind == "want") "want" else id.toString()
        fun with(work: String) = ListInfo(id, name, kind, listOf(work) + works.filter { it != work })
        fun without(work: String) = ListInfo(id, name, kind, works.filter { it != work })
    }
    private fun listInfo(o: JSONObject) = ListInfo(o.getLong("id"), o.getString("name"), o.getString("kind"), strings(o.optJSONArray("works")))
    fun collections(): List<ListInfo> { val a = JSONArray(text("/api/me/collections")); return List(a.length()) { listInfo(a.getJSONObject(it)) } }
    fun createCollection(name: String): ListInfo = listInfo(JSONObject(text("/api/me/collections", "POST", JSONObject().put("name", name).toString())))
    fun renameCollection(key: String, name: String): ListInfo = listInfo(JSONObject(text("/api/me/collections/$key", "PUT", JSONObject().put("name", name).toString())))
    fun deleteCollection(key: String) { text("/api/me/collections/$key", "DELETE") }
    /** Puts a work in a list or takes it out. */
    fun collect(key: String, work: String, on: Boolean) { text("/api/me/collections/$key/works/$work", if (on) "PUT" else "DELETE") }

    /** A book somebody asked the library to get. */
    class Wanted(val id: Long, val title: String, val author: String, val note: String, val kind: String, val status: String, val source: String, val requester: String, val mine: Boolean, val created: Long, val work: String?)
    private fun wanted(o: JSONObject) = Wanted(o.getLong("id"), o.getString("title"), o.optString("author"), o.optString("note"), o.optString("kind", "both"), o.optString("status", "wanted"), o.optString("source"), o.optString("requester"), o.optBoolean("mine"), o.optLong("created"), o.optString("work").takeIf { it.isNotBlank() && it != "null" })
    fun requests(): List<Wanted> { val a = JSONObject(text("/api/requests")).getJSONArray("requests"); return List(a.length()) { wanted(a.getJSONObject(it)) } }
    fun ask(title: String, author: String, kind: String, note: String): Wanted = wanted(JSONObject(text("/api/requests", "POST", JSONObject().put("title", title).put("author", author).put("kind", kind).put("note", note).toString())))
    fun removeRequest(id: Long) { text("/api/requests/$id", "DELETE") }

    /** A book a catalogue outside the house knows, with what the library already has of it (`work`, in which forms) and whether it is already wanted. */
    class Found(
        val source: String, val title: String, val subtitle: String, val authors: List<String>, val narrators: List<String>, val series: List<SeriesRef>,
        val description: String, val year: String, val publisher: String, val genres: List<String>, val cover: String,
        val work: String?, val hasEbook: Boolean, val hasAudio: Boolean, val wanted: Boolean,
    )
    /** Looks the typed words up in the server's catalogues; the second part names a catalogue that could not be reached. */
    fun lookup(words: String): Pair<List<Found>, List<String>> {
        val o = JSONObject(text("/api/lookup?q=" + encode(words)))
        val a = o.getJSONArray("found")
        return List(a.length()) { i ->
            val h = a.getJSONObject(i); val b = h.getJSONObject("book")
            Found(
                b.optString("source"), b.getString("title"), b.optString("subtitle"), strings(b.optJSONArray("authors")), strings(b.optJSONArray("narrators")),
                b.optJSONArray("series")?.let { s -> List(s.length()) { val x = s.getJSONObject(it); SeriesRef(x.getString("name"), if (x.isNull("sequence")) null else x.getDouble("sequence")) } } ?: emptyList(),
                b.optString("description"), b.optString("publishedYear"), b.optString("publisher"), strings(b.optJSONArray("genres")), b.optString("cover"),
                h.optString("work").takeIf { it.isNotBlank() && it != "null" }, h.optBoolean("hasEbook"), h.optBoolean("hasAudio"), h.optBoolean("wanted"),
            )
        } to strings(o.optJSONArray("errors"))
    }
    fun lookupCover(url: String, dest: File) { download("/api/lookup/image?url=" + encode(url), dest) }

    fun marks(work: String): JSONArray = JSONArray(text("/api/me/marks/$work"))
    /** Everything this person marked: one object per work (work, title, author, passages, marks). */
    fun allMarks(): JSONArray = JSONArray(text("/api/me/marks"))
    fun putMark(work: String, m: JSONObject) { text("/api/me/marks/$work", "PUT", m.toString()) }
    fun deleteMark(work: String, kind: String, passage: Int) { text("/api/me/marks/$work/$kind/$passage", "DELETE") }

    fun postSessions(sessions: JSONArray) { text("/api/me/sessions", "POST", sessions.toString()) }
    fun stats(): JSONObject = JSONObject(text("/api/me/stats"))
    /** The year in review; null is this year. */
    fun year(year: Int?): JSONObject = JSONObject(text("/api/me/year" + (year?.let { "/$it" } ?: "")))

    fun putPosition(id: String, passage: Int, audio: Long): Position =
        position(JSONObject(text("/api/works/$id/position", "PUT", JSONObject().put("passage", passage).put("audio", audio).toString())))

    /** A place saved, or refused because another device moved the book after `base`; either way, the place the server answered with. */
    class Saved(val saved: Boolean, val position: Position)

    /** Saves a place unless another device moved the book after `base` (the server time of the place this device last had). */
    fun putPosition(id: String, passage: Int, audio: Long, base: Long): Saved {
        val c = open("/api/works/$id/position?base=$base", "PUT")
        try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("passage", passage).put("audio", audio).toString().toByteArray()) }
            val code = c.responseCode
            val answer = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            check(code in 200..299 || code == 409) { "Server answered $code for the position" }
            return Saved(code != 409, position(JSONObject(answer)))
        } finally { c.disconnect() }
    }

    fun audioUrl(id: String, index: Int) = "$base/api/works/$id/audio/$index"

    /** Streams a served file to disk. */
    fun download(path: String, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        dest.parentFile?.mkdirs()
        val c = open(path, "GET")
        try {
            check(c.responseCode in 200..299) { "Server answered ${c.responseCode} for $path" }
            val total = c.contentLengthLong
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress(done, total)
                    }
                    out.fd.sync()
                }
            }
            check(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) { "Could not place ${dest.name}" }
        } finally { c.disconnect() }
    }

    /**
     * Fetches a served file so that a dropped connection costs only what had not arrived yet: what an earlier try left
     * in `<dest>.part` is kept and the server is asked for the rest (it serves ranges), unless its ETag says the file
     * is no longer the same one, in which case the fetch starts over. `stop` is asked between blocks; when it says
     * yes the part stays for the next try and the answer is false. For large files over a watch's connection.
     */
    fun resume(path: String, dest: File, stop: () -> Boolean = { false }, progress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part"); val tag = File(dest.path + ".etag")
        if (!tmp.isFile || !tag.isFile) { tmp.delete(); tag.delete() }
        var have = if (tmp.isFile) tmp.length() else 0L
        val c = open(path, "GET")
        try {
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            // Bytes as they are on the server: a compressed transfer would make the ranges mean something else.
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            val etag = c.getHeaderField("ETag").orEmpty()
            if (have > 0 && (code == 416 || (code == 206 && etag != tag.readText()))) { c.disconnect(); tmp.delete(); tag.delete(); return resume(path, dest, stop, progress) }
            check(code == 200 || code == 206) { "Server answered $code for $path" }
            if (code == 200) have = 0
            if (etag.isBlank()) tag.delete() else tag.writeText(etag)
            val total = c.contentLengthLong.let { if (it >= 0) have + it else -1 }
            var done = have
            c.inputStream.use { input ->
                java.io.FileOutputStream(tmp, have > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (stop()) { out.fd.sync(); return false }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress(done, total)
                    }
                    out.fd.sync()
                }
            }
            check(total < 0 || done == total) { "The connection ended before ${dest.name} was whole" }
            check(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) { "Could not place ${dest.name}" }
            tag.delete()
            return true
        } finally { c.disconnect() }
    }

    private fun work(w: JSONObject, shelf: String): Work { val sim = DebugServer.preparing?.takeIf { it.first == w.getString("id") }; return Work(
        w.getString("id"), w.getString("title"), w.getString("author"), w.optString("narrator", ""),
        !w.isNull("ebookPath"), !w.isNull("audioDir"), w.optString("ebookFormat", "epub").ifBlank { "epub" }, w.optLong("duration"), w.optInt("passages"),
        if (sim != null) "Syncing" else w.optString("status", ""), sim?.second ?: w.optInt("syncedPercent"), if (sim != null) "preparing" else shelf, w.optBoolean("cover"),
        subtitle = w.optString("subtitle", ""), description = w.optString("description", ""), publishedYear = w.optString("publishedYear", ""),
        publisher = w.optString("publisher", ""), rating = w.optDouble("rating", 0.0), genres = strings(w.optJSONArray("genres")),
        series = w.optJSONArray("series")?.let { a -> List(a.length()) { i -> val s = a.getJSONObject(i); SeriesRef(s.getString("name"), if (s.isNull("sequence")) null else s.getDouble("sequence")) } } ?: emptyList(),
        authors = strings(w.optJSONArray("authors")), narrators = strings(w.optJSONArray("narrators")), coverVersion = w.optLong("coverVersion"),
    ) }

    private fun position(o: JSONObject) = Position(o.getInt("passage"), o.getLong("audio"), o.optLong("updated"), o.optString("by"))

    private fun text(path: String, method: String = "GET", body: String? = null): String {
        val c = open(path, method)
        try {
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val answer = stream?.bufferedReader()?.readText().orEmpty()
            check(code in 200..299) { "Server answered $code for $path${answer.takeIf { it.isNotBlank() }?.let { ": " + runCatching { JSONObject(it).optString("message") }.getOrDefault(it).take(120) } ?: ""}" }
            return answer
        } finally { c.disconnect() }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000; readTimeout = 60_000
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Device", device)
            // Days and hours in the statistics are counted where the reader is, not where the server is.
            setRequestProperty("X-Timezone", java.util.TimeZone.getDefault().id)
            setRequestProperty("Accept", "application/json")
        }

    companion object {
        /** "unison.example.org" → "https://unison.example.org"; trailing slashes go. */
        fun normalize(url: String): String {
            val t = url.trim().trimEnd('/')
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
        fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
