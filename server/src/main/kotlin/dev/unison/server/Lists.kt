package dev.unison.server

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.Serializable

/** A list of books one person keeps. `kind` is "want" for the built-in Want to read, "list" for a collection made by hand. */
@Serializable data class Collection(val id: Long, val name: String, val kind: String, val created: Long, val updated: Long, val works: List<String>)
/**
 * A book somebody would like the library to have. `kind`: ebook, audiobook or both. `status`: wanted, arrived
 * (the scanner found it, or the admin said so) or declined. `source`: web or app (and `discord`, kept for a channel feed that is no longer part of the server).
 */
@Serializable data class BookRequest(
    val id: Long, val title: String, val author: String, val note: String, val kind: String, val status: String, val source: String,
    val requester: String, val mine: Boolean, val created: Long, val resolved: Long, val work: String?,
)

/** One still-wanted request as other programs on the host read it (the wanted file, see docs/SERVER.md). */
@Serializable data class WantedItem(val id: Long, val title: String, val author: String, val kind: String, val note: String, val requester: String, val created: Long)
/** The wanted file: everything still wanted, oldest first. `version` changes only if a field's meaning does. */
@Serializable data class WantedFile(val version: Int, val written: Long, val requests: List<WantedItem>)

/**
 * What people keep beside the shelf: collections made by hand and Want to read (per person), and the
 * household's wanted list of books the library does not have yet. All of it lives in the server's own
 * database; the libraries are never touched.
 */
class Lists(private val db: Connection) {
    init {
        synchronized(db) {
            db.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS collections (id INTEGER PRIMARY KEY AUTOINCREMENT, user INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, name TEXT NOT NULL COLLATE NOCASE, kind TEXT NOT NULL DEFAULT 'list', created INTEGER NOT NULL, updated INTEGER NOT NULL, UNIQUE (user, name))")
                s.execute("CREATE TABLE IF NOT EXISTS collection_works (collection INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE, work TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY (collection, work))")
                s.execute("CREATE TABLE IF NOT EXISTS requests (id INTEGER PRIMARY KEY AUTOINCREMENT, user INTEGER REFERENCES users(id) ON DELETE SET NULL, requester TEXT NOT NULL DEFAULT '', title TEXT NOT NULL, author TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'both', source TEXT NOT NULL DEFAULT 'web', external TEXT UNIQUE, status TEXT NOT NULL DEFAULT 'wanted', created INTEGER NOT NULL, resolved INTEGER NOT NULL DEFAULT 0, work TEXT)")
                s.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                // Whether a request has been said in the Discord channel (WantedPost); added to databases made before it.
                if (!s.executeQuery("PRAGMA table_info(requests)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toList() }.contains("posted")) s.execute("ALTER TABLE requests ADD COLUMN posted INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = synchronized(db) {
        db.prepareStatement(sql).use { p -> args.forEachIndexed { i, a -> p.setObject(i + 1, a) }; p.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } } }
    }
    private fun exec(sql: String, vararg args: Any?): Int = synchronized(db) {
        db.prepareStatement(sql).use { p -> args.forEachIndexed { i, a -> p.setObject(i + 1, a) }; p.executeUpdate() }
    }

    fun setting(key: String): String? = query("SELECT value FROM settings WHERE key = ?", key) { it.getString(1) }.firstOrNull()
    fun setSetting(key: String, value: String) { exec("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", key, value) }

    // ---- collections and Want to read

    /** The person's Want to read list, made the first time it is asked for. */
    private fun wantId(user: Long): Long = synchronized(db) {
        query("SELECT id FROM collections WHERE user = ? AND kind = 'want'", user) { it.getLong(1) }.firstOrNull() ?: run {
            val now = System.currentTimeMillis()
            exec("INSERT INTO collections (user, name, kind, created, updated) VALUES (?, ?, 'want', ?, ?)", user, WANT, now, now)
            query("SELECT id FROM collections WHERE user = ? AND kind = 'want'", user) { it.getLong(1) }.first()
        }
    }

    /** `key` is a collection's number, or "want" for Want to read. Only the person's own lists answer. */
    private fun idOf(user: Long, key: String): Long =
        if (key == "want") wantId(user)
        else key.toLongOrNull()?.takeIf { id -> query("SELECT 1 FROM collections WHERE id = ? AND user = ?", id, user) { 1 }.isNotEmpty() } ?: throw IllegalArgumentException("Unknown collection")

    /** Want to read first, then the collections by name; each with its books, newest added first. `known` drops books the library no longer has. */
    fun collections(user: Long, known: (String) -> Boolean = { true }): List<Collection> {
        wantId(user)
        val works = query("SELECT cw.collection, cw.work FROM collection_works cw JOIN collections c ON c.id = cw.collection WHERE c.user = ? ORDER BY cw.added DESC, cw.rowid DESC", user) { it.getLong(1) to it.getString(2) }
            .filter { known(it.second) }.groupBy({ it.first }, { it.second })
        return query("SELECT id, name, kind, created, updated FROM collections WHERE user = ? ORDER BY CASE kind WHEN 'want' THEN 0 ELSE 1 END, name COLLATE NOCASE", user) {
            Collection(it.getLong(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), works[it.getLong(1)] ?: emptyList())
        }
    }
    fun collection(user: Long, key: String, known: (String) -> Boolean = { true }): Collection { val id = idOf(user, key); return collections(user, known).first { it.id == id } }

    private fun cleanName(user: Long, name: String, except: Long? = null): String {
        val n = name.trim().replace(Regex("\\s+"), " ")
        require(n.length in 1..60 && n.none { it.isISOControl() }) { "A collection's name is one to sixty characters" }
        require(query("SELECT id FROM collections WHERE user = ? AND name = ? COLLATE NOCASE", user, n) { it.getLong(1) }.none { it != except }) { "You already have a collection called $n" }
        return n
    }
    fun createCollection(user: Long, name: String): Collection {
        wantId(user)
        val n = cleanName(user, name); val now = System.currentTimeMillis()
        require(query("SELECT COUNT(*) FROM collections WHERE user = ?", user) { it.getInt(1) }.first() < 200) { "That is a lot of collections; remove one first" }
        exec("INSERT INTO collections (user, name, kind, created, updated) VALUES (?, ?, 'list', ?, ?)", user, n, now, now)
        return collections(user).first { it.name.equals(n, true) }
    }
    fun renameCollection(user: Long, key: String, name: String): Collection {
        val id = idOf(user, key)
        require(query("SELECT kind FROM collections WHERE id = ?", id) { it.getString(1) }.first() == "list") { "Want to read keeps its name" }
        exec("UPDATE collections SET name = ?, updated = ? WHERE id = ?", cleanName(user, name, id), System.currentTimeMillis(), id)
        return collections(user).first { it.id == id }
    }
    fun deleteCollection(user: Long, key: String) {
        val id = idOf(user, key)
        require(query("SELECT kind FROM collections WHERE id = ?", id) { it.getString(1) }.first() == "list") { "Want to read stays; take its books off instead" }
        exec("DELETE FROM collection_works WHERE collection = ?", id); exec("DELETE FROM collections WHERE id = ?", id)
    }
    fun addWork(user: Long, key: String, work: String) {
        val id = idOf(user, key); val now = System.currentTimeMillis()
        if (exec("INSERT OR IGNORE INTO collection_works (collection, work, added) VALUES (?, ?, ?)", id, work, now) > 0) exec("UPDATE collections SET updated = ? WHERE id = ?", now, id)
    }
    fun removeWork(user: Long, key: String, work: String) {
        val id = idOf(user, key)
        if (exec("DELETE FROM collection_works WHERE collection = ? AND work = ?", id, work) > 0) exec("UPDATE collections SET updated = ? WHERE id = ?", System.currentTimeMillis(), id)
    }
    /** A book put on the shelf has been picked up: it leaves Want to read. */
    fun pickedUp(user: Long, work: String) { exec("DELETE FROM collection_works WHERE work = ? AND collection IN (SELECT id FROM collections WHERE user = ? AND kind = 'want')", work, user) }
    fun resetPersonal(user: Long): Int = exec("DELETE FROM collection_works WHERE collection IN (SELECT id FROM collections WHERE user = ?)", user) + exec("DELETE FROM collections WHERE user = ?", user)

    // ---- the wanted list

    /** Told after anything about the wanted list changes (asked, settled, declined, wanted again, removed). */
    @Volatile var onRequestsChanged: (() -> Unit)? = null
    private fun requestsChanged() { runCatching { onRequestsChanged?.invoke() } }

    /** Still wanted and not yet said in the Discord channel, oldest first. */
    fun unposted(): List<WantedItem> = query("SELECT * FROM requests WHERE status = 'wanted' AND posted = 0 ORDER BY created, id") { request(it, -1) }.map { WantedItem(it.id, it.title, it.author, it.kind, it.note, it.requester, it.created) }
    fun markPosted(id: Long) { exec("UPDATE requests SET posted = 1 WHERE id = ?", id) }
    fun markAllPosted() { exec("UPDATE requests SET posted = 1") }

    /** What is still wanted, oldest first, for the wanted file. */
    fun wantedFile(): WantedFile = WantedFile(1, System.currentTimeMillis(),
        query("SELECT * FROM requests WHERE status = 'wanted' ORDER BY created, id") { request(it, -1) }.map { WantedItem(it.id, it.title, it.author, it.kind, it.note, it.requester, it.created) })

    private fun request(r: ResultSet, asker: Long) = BookRequest(
        r.getLong("id"), r.getString("title"), r.getString("author"), r.getString("note"), r.getString("kind"), r.getString("status"), r.getString("source"),
        r.getString("requester"), r.getObject("user")?.let { (it as Number).toLong() } == asker, r.getLong("created"), r.getLong("resolved"), r.getString("work"),
    )
    /** Everything asked for, what is still wanted first, newest first within each. The household shares one list. */
    fun requests(asker: Long): List<BookRequest> = query("SELECT * FROM requests ORDER BY CASE status WHEN 'wanted' THEN 0 ELSE 1 END, CASE status WHEN 'wanted' THEN created ELSE resolved END DESC, id DESC") { request(it, asker) }
    fun request(id: Long, asker: Long): BookRequest? = query("SELECT * FROM requests WHERE id = ?", id) { request(it, asker) }.firstOrNull()

    /**
     * Adds a request. `external` (an id from wherever the request came from, when it has one) makes a repeat of the same message a no-op.
     * The same title and author already wanted is not added twice: the existing request answers.
     */
    fun addRequest(user: Long?, requester: String, title: String, author: String, note: String, kind: String, source: String, external: String? = null): BookRequest? {
        val t = title.trim().replace(Regex("\\s+"), " "); val a = author.trim().replace(Regex("\\s+"), " ")
        require(t.length in 1..200) { "A title, please" }
        require(a.length <= 120 && note.length <= 500) { "That is too long" }
        require(kind in KINDS) { "Kind is ebook, audiobook or both" }
        if (external != null && query("SELECT 1 FROM requests WHERE external = ?", external) { 1 }.isNotEmpty()) return null
        val same = query("SELECT id, title, author FROM requests WHERE status = 'wanted'") { Triple(it.getLong(1), it.getString(2), it.getString(3)) }
            .firstOrNull { Scanner.titleKey(it.second) == Scanner.titleKey(t) && (a.isBlank() || it.third.isBlank() || Scanner.authorKey(it.third) == Scanner.authorKey(a)) }
        if (same != null && Scanner.titleKey(t).isNotBlank()) return request(same.first, user ?: -1)
        exec("INSERT INTO requests (user, requester, title, author, note, kind, source, external, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", user, requester.take(60), t, a, note.trim(), kind, source, external, System.currentTimeMillis())
        requestsChanged()
        return query("SELECT * FROM requests ORDER BY id DESC LIMIT 1") { request(it, user ?: -1) }.first()
    }
    fun setRequestStatus(id: Long, status: String, work: String? = null) {
        require(status in STATUSES) { "Status is wanted, arrived or declined" }
        require(exec("UPDATE requests SET status = ?, resolved = ?, work = ? WHERE id = ?", status, if (status == "wanted") 0 else System.currentTimeMillis(), if (status == "arrived") work else null, id) > 0) { "Unknown request" }
        requestsChanged()
    }
    fun deleteRequest(id: Long) { exec("DELETE FROM requests WHERE id = ?", id); requestsChanged() }

    /**
     * After a scan: a wanted book the library now has, in the form asked for, has arrived. Matched by the
     * scanner's own title and author keys (the author is skipped when the request names none and one title answers).
     * Returns the requests that arrived.
     */
    fun settle(works: List<Work>): List<BookRequest> {
        val wanted = query("SELECT * FROM requests WHERE status = 'wanted'") { request(it, -1) }
        if (wanted.isEmpty()) return emptyList()
        val arrived = ArrayList<BookRequest>()
        for (r in wanted) {
            val hit = works.filter { BookIdentity.matches(it, r.title, listOf(r.author)) }
                .filter { w -> when (r.kind) { "ebook" -> w.hasEbook; "audiobook" -> w.hasAudio; else -> w.hasEbook && w.hasAudio } }
                .singleOrNull() ?: continue
            setRequestStatus(r.id, "arrived", hit.id)
            request(r.id, -1)?.let { arrived += it }
        }
        return arrived
    }

    companion object {
        const val WANT = "Want to read"
        val KINDS = setOf("ebook", "audiobook", "both")
        val STATUSES = setOf("wanted", "arrived", "declined")

    }
}
