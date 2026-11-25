package dev.unison.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable data class User(val id: Long, val name: String, val role: String, val created: Long)
@Serializable data class DeviceToken(val token: String, val device: String, val created: Long, val lastSeen: Long)
@Serializable data class AuditEntry(val time: Long, val user: String, val action: String, val address: String, val detail: String)
@Serializable data class Mark(val passage: Int, val kind: String, val note: String = "", val text: String = "", val created: Long = 0)
@Serializable data class ShelfEntry(val work: String, val state: String, val added: Long, val finishedAt: Long?, val position: Position?)

/**
 * Accounts for the household: a name, a password (PBKDF2-SHA256, 120k rounds, per-user salt) and a role
 * (admin or member). Signing in from a device yields a random token for that device; only its SHA-256 is
 * stored, so the database alone cannot sign anyone in. A token that goes unused for a while expires
 * (thirty days for a browser, a year for the app), and each is revocable from the device list. The
 * install's original token belongs to the built-in admin on a fresh install only; first-run setup revokes it,
 * and a server that already has a password never seeds it. On a server set up before that it is revoked like any other device. Sign-ins and account changes are audited.
 */
class Users(private val db: Connection, private val legacyToken: String) {
    private val log = LoggerFactory.getLogger("Users")

    init {
        synchronized(db) {
            db.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, hash TEXT NOT NULL DEFAULT '', salt TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'member', created INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS tokens (token TEXT PRIMARY KEY, user INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, device TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, last_seen INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS shelf (user INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, work TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'queued', added INTEGER NOT NULL, finished_at INTEGER, PRIMARY KEY (user, work))")
                // Bookmarks and highlights are per sentence: one row per (person, work, sentence, kind).
                s.execute("CREATE TABLE IF NOT EXISTS marks (user INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, work TEXT NOT NULL, passage INTEGER NOT NULL, kind TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', text TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, PRIMARY KEY (user, work, passage, kind))")
                s.execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, user TEXT NOT NULL, action TEXT NOT NULL, address TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '')")
                val columns = s.executeQuery("PRAGMA table_info(tokens)").use { r -> buildList { while (r.next()) add(r.getString("name")) } }
                if ("prefix" !in columns) { s.execute("ALTER TABLE tokens ADD COLUMN prefix TEXT NOT NULL DEFAULT ''"); s.execute("ALTER TABLE tokens ADD COLUMN hashed INTEGER NOT NULL DEFAULT 0") }
            }
        }
        // Tokens recorded in clear by earlier versions are replaced by their hashes, keeping the eight characters shown in device lists.
        for ((raw, _) in query("SELECT token, user FROM tokens WHERE hashed = 0") { it.getString(1) to it.getLong(2) })
            exec("UPDATE tokens SET token = ?, prefix = ?, hashed = 1 WHERE token = ?", sha256(raw), raw.take(8), raw)
        // The built-in admin owns every position recorded before accounts existed; the install's original token is seeded on a fresh install only,
        // and never once an account has a password (first-run setup ends it, and a restart must not bring it back).
        if (byName("admin") == null) {
            exec("INSERT INTO users (id, name, role, created) VALUES (1, 'admin', 'admin', ?)", System.currentTimeMillis())
        }
        val admin = byName("admin")!!
        if (setupNeeded() && query("SELECT COUNT(*) FROM tokens") { it.getInt(1) }.first() == 0)
            exec("INSERT OR IGNORE INTO tokens (token, prefix, hashed, user, device, created, last_seen) VALUES (?, ?, 1, ?, 'server', ?, ?)", sha256(legacyToken), legacyToken.take(8), admin.id, System.currentTimeMillis(), System.currentTimeMillis())
    }

    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = synchronized(db) {
        db.prepareStatement(sql).use { p -> args.forEachIndexed { i, a -> p.setObject(i + 1, a) }; p.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } } }
    }
    private fun exec(sql: String, vararg args: Any?): Int = synchronized(db) {
        db.prepareStatement(sql).use { p -> args.forEachIndexed { i, a -> p.setObject(i + 1, a) }; p.executeUpdate() }
    }
    private fun user(r: ResultSet) = User(r.getLong("id"), r.getString("name"), r.getString("role"), r.getLong("created"))

    fun all(): List<User> = query("SELECT id, name, role, created FROM users ORDER BY id") { user(it) }
    fun byId(id: Long): User? = query("SELECT id, name, role, created FROM users WHERE id = ?", id) { user(it) }.firstOrNull()
    fun byName(name: String): User? = query("SELECT id, name, role, created FROM users WHERE name = ? COLLATE NOCASE", name.trim()) { user(it) }.firstOrNull()

    /** Who holds this token, touching its last-seen time; a token idle past its limit is gone. */
    fun authenticate(token: String): User? {
        if (token.length < 16) return null
        val h = sha256(token)
        val row = query("SELECT user, device, last_seen FROM tokens WHERE token = ?", h) { Triple(it.getLong(1), it.getString(2), it.getLong(3)) }.firstOrNull() ?: return null
        val now = System.currentTimeMillis()
        val idle = if (row.second.startsWith("Web")) WEB_IDLE_MS else APP_IDLE_MS
        if (now - row.third > idle) { exec("DELETE FROM tokens WHERE token = ?", h); audit(byId(row.first)?.name ?: "?", "token expired", "", row.second); return null }
        if (now - row.third > 60_000) exec("UPDATE tokens SET last_seen = ? WHERE token = ?", now, h)
        return byId(row.first)
    }

    /** The name a token's device signed in with ("Pixel 9", "Web · Firefox"), or null. */
    fun deviceName(token: String): String? = query("SELECT device FROM tokens WHERE token = ?", sha256(token)) { it.getString(1) }.firstOrNull()

    /** Sign-in attempts are throttled per address and per name: five misses, then a lockout that doubles each miss, up to fifteen minutes. */
    val throttle = Throttle()

    fun audit(user: String, action: String, address: String, detail: String = "") {
        exec("INSERT INTO audit (time, user, action, address, detail) VALUES (?, ?, ?, ?, ?)", System.currentTimeMillis(), user.take(40), action, address.take(60), detail.take(200))
        log.info("$action · $user · $address${if (detail.isNotBlank()) " · $detail" else ""}")
    }
    fun auditLog(limit: Int = 60): List<AuditEntry> = query("SELECT time, user, action, address, detail FROM audit ORDER BY id DESC LIMIT ?", limit) { AuditEntry(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }

    fun create(name: String, password: String, role: String): User {
        require(name.trim().length in 2..40 && name.trim().none { it.isISOControl() }) { "A name is two to forty characters" }
        require(password.length >= MIN_PASSWORD) { "A password is at least $MIN_PASSWORD characters" }
        require(role == "admin" || role == "member") { "Role is admin or member" }
        require(byName(name) == null) { "That name is taken" }
        val salt = salt()
        exec("INSERT INTO users (name, hash, salt, role, created) VALUES (?, ?, ?, ?, ?)", name.trim(), hash(password, salt), salt, role, System.currentTimeMillis())
        return byName(name)!!
    }

    fun setPassword(id: Long, password: String) {
        require(password.length >= MIN_PASSWORD) { "A password is at least $MIN_PASSWORD characters" }
        val salt = salt()
        exec("UPDATE users SET hash = ?, salt = ? WHERE id = ?", hash(password, salt), salt, id)
    }

    fun delete(id: Long) { require(id != 1L) { "The built-in admin stays" }; exec("DELETE FROM users WHERE id = ?", id) }
    /** Admin or member. The built-in admin keeps its role, and the last admin cannot be demoted. */
    fun setRole(id: Long, role: String) {
        require(role == "admin" || role == "member") { "Role must be admin or member" }
        require(!(id == 1L && role != "admin")) { "The built-in admin stays an admin" }
        if (role == "member") require(all().count { it.role == "admin" && it.id != id } > 0) { "Keep at least one admin" }
        exec("UPDATE users SET role = ? WHERE id = ?", role, id)
    }
    fun revokeAll(user: Long): Int = exec("DELETE FROM tokens WHERE user = ?", user)
    /** Shelf and marks gone; positions and sessions live in the catalog and the stats and are cleared beside this. */
    fun resetPersonal(user: Long): Int = exec("DELETE FROM shelf WHERE user = ?", user) + exec("DELETE FROM marks WHERE user = ?", user)

    /** True when this account has a password at all (the built-in admin of a token-only server has none). */
    fun hasPassword(id: Long): Boolean = query("SELECT hash FROM users WHERE id = ?", id) { it.getString(1) }.firstOrNull().orEmpty().isNotBlank()

    /** True while no account has a password, which is a server nobody has set up: nobody can sign in by name. */
    fun setupNeeded(): Boolean = query("SELECT COUNT(*) FROM users WHERE hash != ''") { it.getInt(1) }.first() == 0

    /**
     * The first run: gives the built-in admin its password, once. The check and the change are one statement, so of
     * two requests arriving together only one succeeds; false when some account already had a password.
     */
    fun setUp(password: String): Boolean {
        require(password.length >= MIN_PASSWORD) { "A password is at least $MIN_PASSWORD characters" }
        val salt = salt()
        return synchronized(db) {
            val done = exec("UPDATE users SET hash = ?, salt = ? WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM users WHERE hash != '')", hash(password, salt), salt) == 1
            // Setting up ends the install's first key: from here on it signs nobody in.
            if (done) exec("DELETE FROM tokens WHERE token = ?", sha256(legacyToken))
            done
        }
    }

    /** True while the install's first key (`data/token`, or `UNISON_TOKEN`) still signs someone in. */
    fun keyActive(): Boolean = query("SELECT COUNT(*) FROM tokens WHERE token = ?", sha256(legacyToken)) { it.getInt(1) }.first() > 0

    /** True when this is the account's password. */
    fun checkPassword(id: Long, password: String): Boolean {
        val row = query("SELECT hash, salt FROM users WHERE id = ?", id) { it.getString(1) to it.getString(2) }.firstOrNull()
        if (row == null || row.first.isBlank()) { hash(password, DUMMY_SALT); return false }
        return constantEquals(hash(password, row.second), row.first)
    }

    /** A device token for a correct name and password, or null. A user without a password cannot sign in this way. */
    fun login(name: String, password: String, device: String): Pair<User, String>? {
        val row = query("SELECT id, hash, salt FROM users WHERE name = ? COLLATE NOCASE", name.trim()) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }.firstOrNull()
        // An unknown name costs the same time as a wrong password, so names cannot be probed by timing.
        if (row == null || row.second.isBlank()) { hash(password, DUMMY_SALT); return null }
        if (!constantEquals(hash(password, row.third), row.second)) return null
        return byId(row.first)!! to issue(row.first, device)
    }

    /** A new device token for this person: after a correct password, or when they approved a device's sign-in code (`DeviceLinks`). */
    fun issue(user: Long, device: String): String {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        exec("INSERT INTO tokens (token, prefix, hashed, user, device, created, last_seen) VALUES (?, ?, 1, ?, ?, ?, ?)", sha256(token), token.take(8), user, device.take(60), System.currentTimeMillis(), System.currentTimeMillis())
        return token
    }

    fun tokens(user: Long): List<DeviceToken> = query("SELECT prefix, device, created, last_seen FROM tokens WHERE user = ? ORDER BY last_seen DESC", user) { DeviceToken(it.getString(1) + "…", it.getString(2), it.getLong(3), it.getLong(4)) }
    fun revoke(user: Long, tokenPrefix: String): Int = exec("DELETE FROM tokens WHERE user = ? AND prefix = ?", user, tokenPrefix.removeSuffix("…").take(8))
    /** The device record a raw token belongs to, for telling "this browser" apart in the list. */
    fun prefixOf(token: String) = token.take(8)

    // ---- bookmarks and highlights
    fun marks(user: Long, work: String): List<Mark> = query("SELECT passage, kind, note, text, created FROM marks WHERE user = ? AND work = ? ORDER BY passage", user, work) { Mark(it.getInt(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5)) }
    /** Every mark of one person, by work, in reading order within each. */
    fun allMarks(user: Long): Map<String, List<Mark>> =
        query("SELECT work, passage, kind, note, text, created FROM marks WHERE user = ? ORDER BY work, passage", user) { it.getString(1) to Mark(it.getInt(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(6)) }
            .groupBy({ it.first }, { it.second })
    fun setMark(user: Long, work: String, m: Mark) {
        require(m.kind == "bookmark" || m.kind == "highlight") { "Kind is bookmark or highlight" }
        exec("INSERT INTO marks (user, work, passage, kind, note, text, created) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user, work, passage, kind) DO UPDATE SET note = excluded.note, text = excluded.text", user, work, m.passage, m.kind, m.note.take(2000), m.text.take(600), if (m.created > 0) m.created else System.currentTimeMillis())
    }
    fun removeMark(user: Long, work: String, passage: Int, kind: String) { exec("DELETE FROM marks WHERE user = ? AND work = ? AND passage = ? AND kind = ?", user, work, passage, kind) }

    // ---- the shelf

    fun shelf(user: Long): List<ShelfEntry> = query("SELECT work, state, added, finished_at FROM shelf WHERE user = ? ORDER BY CASE state WHEN 'reading' THEN 0 WHEN 'queued' THEN 1 ELSE 2 END, added DESC", user) {
        ShelfEntry(it.getString(1), it.getString(2), it.getLong(3), it.getObject(4)?.let { v -> (v as Number).toLong() }, null)
    }
    fun shelve(user: Long, work: String, state: String, finishedAt: Long?) {
        require(state in setOf("reading", "queued", "finished")) { "State is reading, queued or finished" }
        exec("INSERT INTO shelf (user, work, state, added, finished_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(user, work) DO UPDATE SET state = excluded.state, finished_at = excluded.finished_at", user, work, state, System.currentTimeMillis(), if (state == "finished") (finishedAt ?: System.currentTimeMillis()) else null)
    }
    fun unshelve(user: Long, work: String) { exec("DELETE FROM shelf WHERE user = ? AND work = ?", user, work) }

    companion object {
        const val MIN_PASSWORD = 8
        const val WEB_IDLE_MS = 30L * 24 * 3600 * 1000
        const val APP_IDLE_MS = 365L * 24 * 3600 * 1000
        private const val DUMMY_SALT = "0123456789abcdef0123456789abcdef"
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun salt() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private fun hash(password: String, salt: String): String {
            val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), 120_000, 256)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
        }
        private fun constantEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}

/** Failed sign-ins per key (an address, a name): after five, each further miss doubles a lockout from 30 s up to 15 min. */
class Throttle {
    private class Entry(var failures: Int = 0, var lockedUntil: Long = 0, var last: Long = System.currentTimeMillis())
    private val entries = ConcurrentHashMap<String, Entry>()

    /** Seconds still to wait when the key is locked out, else null. */
    fun blocked(key: String): Long? {
        prune()
        val e = entries[key] ?: return null
        val left = e.lockedUntil - System.currentTimeMillis()
        return if (left > 0) (left + 999) / 1000 else null
    }
    fun fail(key: String) {
        val now = System.currentTimeMillis()
        val e = entries.getOrPut(key) { Entry() }
        e.failures++; e.last = now
        if (e.failures >= 5) e.lockedUntil = now + minOf(15 * 60_000L, 30_000L shl (e.failures - 5).coerceAtMost(5))
    }
    fun clear(key: String) { entries.remove(key) }
    private fun prune() { val cutoff = System.currentTimeMillis() - 3_600_000; entries.entries.removeIf { it.value.last < cutoff && it.value.lockedUntil < System.currentTimeMillis() } }
}
