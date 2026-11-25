package dev.unison.server

import dev.homescreenreader.Anchor
import dev.homescreenreader.Chapter
import dev.homescreenreader.Track
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlinx.serialization.Serializable

/** A title in the library: the ebook, the audiobook, or both, with its sync state. */
@Serializable
data class Work(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String = "",
    val ebookPath: String? = null,
    val ebookFormat: String? = null,
    val audioDir: String? = null,
    val cover: Boolean = false,
    val duration: Long = 0,
    val passages: Int = 0,
    val status: String = "",
    val syncedPercent: Int = 0,
    val updated: Long = 0,
    val subtitle: String = "",
    val description: String = "",
    val publishedYear: String = "",
    val publisher: String = "",
    val language: String = "",
    val rating: Double = 0.0,
    val genres: List<String> = emptyList(),
    val series: List<SeriesRef> = emptyList(),
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val audioOnly: Boolean = false,
    /** The fields an admin corrected in Unison's own catalogue (the overlay); the libraries keep what they had. */
    val edited: List<String> = emptyList(),
    /** When an admin last chose a cover in the overlay, 0 for the library's own: a client that keeps covers fetches again when it changes. */
    val coverVersion: Long = 0,
    /** Persisted identity survives display-title and metadata overlays; not part of the client API. */
    @kotlinx.serialization.Transient val titleKey: String = Scanner.titleKey(title),
) {
    val hasEbook get() = ebookPath != null
    val hasAudio get() = audioDir != null
    /** Which shelf the app shows it on: Ready, Getting ready, Missing a piece. An audiobook the admin marked audio-only is ready as it is. */
    val shelf
        get() = when {
            hasEbook && hasAudio && status == "Synced" -> "ready"
            hasEbook && hasAudio -> "preparing"
            hasAudio && audioOnly -> "ready"
            else -> "missing"
        }
}

@Serializable data class SeriesRef(val name: String, val sequence: Double?)
/** One person's place in a series: how many of its books they have finished, the one they are on, and the book that comes next. */
@Serializable data class SeriesProgress(val name: String, val total: Int, val finished: Int, val current: String?, val next: String?, val lastFinished: Long)
@Serializable data class SeriesSummary(val name: String, val works: Int, val ready: Int, val duration: Long, val authors: List<String>, val cover: String?)
@Serializable data class PersonSummary(val name: String, val works: Int, val ready: Int)
/** Metadata about a work from one source; blank or null means "nothing from this source". */
class Meta(
    val subtitle: String? = null, val description: String? = null, val publishedYear: String? = null, val publisher: String? = null,
    val language: String? = null, val rating: Double? = null, val genres: List<String>? = null, val series: List<SeriesRef>? = null,
    val authors: List<String>? = null, val narrators: List<String>? = null,
)
@Serializable data class TrackInfo(val index: Int, val name: String, val duration: Long, val path: String)
@Serializable data class ChapterInfo(val title: String, val start: Long)
@Serializable data class UnitInfo(val key: String, val index: Int, val title: String, val startMs: Long, val endMs: Long, val done: Boolean, val elapsedMs: Long, val sentences: Int)
/** `by`: the name of the device that saved it ("Pixel 9", "Web · Firefox"), empty when unknown. */
@Serializable data class Position(val passage: Int, val audio: Long, val updated: Long = 0, val by: String = "")
/** A person's listening speed for one book; `updated` 0 when they never set one. */
@Serializable data class Speed(val speed: Float, val updated: Long = 0)

/** The server's own SQLite database: works, their files, sync state, anchors and positions. */
class Catalog(file: File) {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    /** The same connection, for the account tables that live beside the catalog. */
    val connection: Connection get() = db

    init {
        db.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA foreign_keys=ON")
            s.execute(
                """CREATE TABLE IF NOT EXISTS works (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, narrator TEXT NOT NULL DEFAULT '',
                title_key TEXT NOT NULL, author_key TEXT NOT NULL,
                ebook_path TEXT, ebook_format TEXT, ebook_stamp INTEGER NOT NULL DEFAULT 0,
                audio_dir TEXT, audio_stamp INTEGER NOT NULL DEFAULT 0, cover_path TEXT,
                duration INTEGER NOT NULL DEFAULT 0, passages INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT '', alignment_version INTEGER NOT NULL DEFAULT 0,
                updated INTEGER NOT NULL DEFAULT 0)""",
            )
            s.execute("CREATE TABLE IF NOT EXISTS tracks (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, idx INTEGER NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, duration INTEGER NOT NULL, PRIMARY KEY (work, idx))")
            s.execute("CREATE TABLE IF NOT EXISTS chapters (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, idx INTEGER NOT NULL, title TEXT NOT NULL, start INTEGER NOT NULL, PRIMARY KEY (work, idx))")
            s.execute("CREATE TABLE IF NOT EXISTS units (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, key TEXT NOT NULL, idx INTEGER NOT NULL, title TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, core_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL DEFAULT 0, sentences INTEGER NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (work, key))")
            s.execute("CREATE TABLE IF NOT EXISTS anchors (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, passage INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, confidence REAL NOT NULL, PRIMARY KEY (work, passage))")
            s.execute("CREATE INDEX IF NOT EXISTS anchors_time ON anchors (work, start)")
            s.execute("CREATE TABLE IF NOT EXISTS positions (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, device TEXT NOT NULL, passage INTEGER NOT NULL, audio INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (work, device))")
            s.execute("CREATE TABLE IF NOT EXISTS pairing (title_key TEXT NOT NULL, author_key TEXT NOT NULL, ebook_path TEXT NOT NULL, PRIMARY KEY (title_key, author_key))")
            // An audiobook the admin decided has no ebook to pair with: it stops counting as half a book.
            s.execute("CREATE TABLE IF NOT EXISTS audio_only (title_key TEXT NOT NULL, author_key TEXT NOT NULL, decided INTEGER NOT NULL, PRIMARY KEY (title_key, author_key))")
            // Richer metadata (added after the first release): columns are added to existing catalogs.
            for (column in listOf("subtitle TEXT NOT NULL DEFAULT ''", "description TEXT NOT NULL DEFAULT ''", "published_year TEXT NOT NULL DEFAULT ''", "publisher TEXT NOT NULL DEFAULT ''", "language TEXT NOT NULL DEFAULT ''", "rating REAL NOT NULL DEFAULT 0", "genres TEXT NOT NULL DEFAULT ''", "meta_source INTEGER NOT NULL DEFAULT 0", "series_source INTEGER NOT NULL DEFAULT 0"))
                runCatching { s.execute("ALTER TABLE works ADD COLUMN $column") }
            s.execute("CREATE TABLE IF NOT EXISTS work_series (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, series TEXT NOT NULL, sequence REAL, PRIMARY KEY (work, series))")
            s.execute("CREATE INDEX IF NOT EXISTS work_series_name ON work_series (series)")
            s.execute("CREATE TABLE IF NOT EXISTS work_people (work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, role TEXT NOT NULL, idx INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY (work, role, idx))")
            // Positions became per user; what was recorded before accounts belongs to the built-in admin (user 1).
            s.execute("CREATE TABLE IF NOT EXISTS positions_v2 (user INTEGER NOT NULL, work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, device TEXT NOT NULL, passage INTEGER NOT NULL, audio INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (user, work, device))")
            s.execute("INSERT OR IGNORE INTO positions_v2 (user, work, device, passage, audio, updated) SELECT 1, work, device, passage, audio, updated FROM positions")
            // The signed-in device's name beside each position, so a client can say where a book was moved.
            runCatching { s.execute("ALTER TABLE positions_v2 ADD COLUMN name TEXT NOT NULL DEFAULT ''") }
            s.execute("CREATE INDEX IF NOT EXISTS work_people_name ON work_people (role, name)")
            // Each person's listening speed per book, beside the position, so phone and web agree.
            // The metadata overlay: an admin's corrections, one row per work and field, laid over what the libraries say.
            // Keyed by the work's id (made from its title and author keys), so a correction survives rescans.
            s.execute("CREATE TABLE IF NOT EXISTS overrides (work TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (work, field))")
            s.execute("CREATE TABLE IF NOT EXISTS speeds (user INTEGER NOT NULL, work TEXT NOT NULL REFERENCES works(id) ON DELETE CASCADE, speed REAL NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (user, work))")
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = synchronized(db) {
        db.prepareStatement(sql).use { p ->
            args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
            p.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } }
        }
    }

    private fun exec(sql: String, vararg args: Any?): Int = synchronized(db) {
        db.prepareStatement(sql).use { p ->
            args.forEachIndexed { i, a -> p.setObject(i + 1, a) }
            p.executeUpdate()
        }
    }

    fun <T> transaction(block: () -> T): T = synchronized(db) {
        db.autoCommit = false
        try { block().also { db.commit() } } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true }
    }

    private fun work(r: ResultSet) = Work(
        id = r.getString("id"), title = r.getString("title"), author = r.getString("author"), narrator = r.getString("narrator"),
        ebookPath = r.getString("ebook_path"), ebookFormat = r.getString("ebook_format"), audioDir = r.getString("audio_dir"),
        cover = r.getString("cover_path") != null, duration = r.getLong("duration"), passages = r.getInt("passages"),
        status = r.getString("status"), syncedPercent = r.getInt("synced"), updated = r.getLong("updated"),
        subtitle = r.getString("subtitle"), description = r.getString("description"), publishedYear = r.getString("published_year"),
        publisher = r.getString("publisher"), language = r.getString("language"), rating = r.getDouble("rating"),
        genres = r.getString("genres").split('\u001f').filter { it.isNotBlank() },
        titleKey = r.getString("title_key"),
    )

    private val workColumns = "w.*, COALESCE((SELECT CAST(100 * SUM(CASE WHEN done THEN core_ms ELSE 0 END) / NULLIF(SUM(core_ms), 0) AS INTEGER) FROM units u WHERE u.work = w.id), 0) AS synced"

    /** Attaches series and people to works in two queries, however many works there are, then lays the overlay on top. */
    private fun decorate(works: List<Work>, overlay: Boolean = true): List<Work> {
        if (works.isEmpty()) return works
        val series = HashMap<String, MutableList<SeriesRef>>()
        query("SELECT work, series, sequence FROM work_series ORDER BY series") { r -> series.getOrPut(r.getString(1)) { ArrayList() }.add(SeriesRef(r.getString(2), r.getObject(3)?.let { (it as Number).toDouble() })) }
        val people = HashMap<Pair<String, String>, MutableList<String>>()
        query("SELECT work, role, name FROM work_people ORDER BY idx") { r -> people.getOrPut(r.getString(1) to r.getString(2)) { ArrayList() }.add(r.getString(3)) }
        val audioOnly = query("SELECT w.id FROM works w JOIN audio_only a ON a.title_key = w.title_key AND a.author_key = w.author_key") { it.getString(1) }.toSet()
        val overrides = HashMap<String, MutableMap<String, String>>()
        val coverVersions = HashMap<String, Long>()
        if (overlay) query("SELECT work, field, value, updated FROM overrides") { r -> overrides.getOrPut(r.getString(1)) { HashMap() }[r.getString(2)] = r.getString(3); if (r.getString(2) == "cover") coverVersions[r.getString(1)] = r.getLong(4) }
        return works.map { w ->
            val authors = people[w.id to "author"] ?: listOfNotNull(w.author.takeIf { it.isNotBlank() })
            val narrators = people[w.id to "narrator"] ?: listOfNotNull(w.narrator.takeIf { it.isNotBlank() })
            val plain = w.copy(series = series[w.id] ?: emptyList(), authors = authors, narrators = narrators, audioOnly = w.id in audioOnly)
            overrides[w.id]?.let { applyOverrides(plain, it).let { o -> if ("cover" in o.edited && o.cover) o.copy(coverVersion = coverVersions[w.id] ?: 0) else o } } ?: plain
        }
    }

    private fun applyOverrides(w: Work, o: Map<String, String>): Work {
        fun list(field: String) = o[field]?.split(SEP)?.filter { it.isNotBlank() }
        val authors = list("authors") ?: w.authors
        val narrators = list("narrators") ?: w.narrators
        return w.copy(
            title = o["title"] ?: w.title, subtitle = o["subtitle"] ?: w.subtitle, description = o["description"] ?: w.description,
            publishedYear = o["publishedYear"] ?: w.publishedYear, publisher = o["publisher"] ?: w.publisher, language = o["language"] ?: w.language,
            genres = list("genres") ?: w.genres, authors = authors, narrators = narrators,
            author = if ("authors" in o) authors.firstOrNull().orEmpty() else w.author, narrator = if ("narrators" in o) narrators.joinToString(", ") else w.narrator,
            series = o["series"]?.let { decodeSeries(it) } ?: w.series, cover = w.cover || (o["cover"]?.let { File(it).isFile } == true),
            edited = FIELDS.filter { it in o },
        )
    }

    fun works(): List<Work> = decorate(query("SELECT $workColumns FROM works w ORDER BY author, title") { work(it) }).let { all -> if (all.any { "authors" in it.edited || "title" in it.edited }) all.sortedWith(compareBy<Work> { it.author }.thenBy { it.title }) else all }
    fun work(id: String): Work? = decorate(query("SELECT $workColumns FROM works w WHERE id = ?", id) { work(it) }).firstOrNull()
    /** The work as the libraries describe it, without the overlay. */
    fun sourceWork(id: String): Work? = decorate(query("SELECT $workColumns FROM works w WHERE id = ?", id) { work(it) }, overlay = false).firstOrNull()

    /**
     * Lays an admin's corrections over a work. `changes`: field to its new value, lists joined by [SEP] and series
     * through [encodeSeries]; a value equal to what the libraries say, or null, removes the correction, so a field
     * only stays in the overlay while it differs. Nothing is written to Calibre or beside the audiobooks.
     */
    fun setOverrides(id: String, changes: Map<String, String?>): Work {
        val source = sourceWork(id) ?: throw IllegalArgumentException("Unknown work")
        val original = mapOf(
            "title" to source.title, "subtitle" to source.subtitle, "description" to source.description, "publishedYear" to source.publishedYear,
            "publisher" to source.publisher, "language" to source.language, "genres" to source.genres.joinToString(SEP),
            "authors" to source.authors.joinToString(SEP), "narrators" to source.narrators.joinToString(SEP), "series" to encodeSeries(source.series),
        )
        transaction {
            for ((field, value) in changes) {
                require(field in FIELDS) { "Unknown field $field" }
                if (field == "title") require(value == null || value.isNotBlank()) { "A book needs a title" }
                if (value == null || (field != "cover" && value == original[field])) exec("DELETE FROM overrides WHERE work = ? AND field = ?", id, field)
                else exec("INSERT OR REPLACE INTO overrides (work, field, value, updated) VALUES (?, ?, ?, ?)", id, field, value, System.currentTimeMillis())
            }
            exec("UPDATE works SET updated = ? WHERE id = ?", System.currentTimeMillis(), id)
        }
        return work(id)!!
    }
    fun overrides(id: String): Map<String, String> = query("SELECT field, value FROM overrides WHERE work = ?", id) { it.getString(1) to it.getString(2) }.toMap()

    /**
     * Records metadata from a source with a rank: Calibre (3) over Audiobookshelf's metadata.json (2)
     * over audio tags (1) over folder names (0). A field already set by a stronger source is kept;
     * series and people are replaced only by a source at least as strong as the one that set them.
     */
    fun setMeta(id: String, rank: Int, m: Meta) {
        transaction {
            val current = query("SELECT meta_source FROM works WHERE id = ?", id) { it.getInt(1) }.firstOrNull() ?: return@transaction
            val stronger = rank >= current
            fun field(column: String, value: String?) {
                if (value.isNullOrBlank()) return
                if (stronger) exec("UPDATE works SET $column = ? WHERE id = ?", value, id)
                else exec("UPDATE works SET $column = ? WHERE id = ? AND $column = ''", value, id)
            }
            field("subtitle", m.subtitle); field("description", m.description); field("published_year", m.publishedYear)
            field("publisher", m.publisher); field("language", m.language)
            if (m.rating != null && m.rating > 0 && (stronger || (query("SELECT rating FROM works WHERE id = ?", id) { it.getDouble(1) }.first() == 0.0))) exec("UPDATE works SET rating = ? WHERE id = ?", m.rating, id)
            field("genres", m.genres?.filter { it.isNotBlank() }?.joinToString("\u001f"))
            // Series remember which source set them: a source at least as strong replaces them, even with "none".
            val seriesSource = query("SELECT series_source FROM works WHERE id = ?", id) { it.getInt(1) }.first()
            if (m.series != null && (rank >= seriesSource || query("SELECT COUNT(*) FROM work_series WHERE work = ?", id) { it.getInt(1) }.first() == 0)) {
                exec("DELETE FROM work_series WHERE work = ?", id)
                for (sr in m.series.distinctBy { it.name.lowercase() }) exec("INSERT OR REPLACE INTO work_series (work, series, sequence) VALUES (?, ?, ?)", id, sr.name.trim(), sr.sequence)
                exec("UPDATE works SET series_source = ? WHERE id = ?", rank, id)
            }
            fun people(role: String, rawNames: List<String>?) {
                // "J. K. Rowling" and "J.K. Rowling" are one person: initials lose the space between them.
                val names = rawNames?.map { it.trim().replace(Regex("\\b([A-Z])\\.\\s+(?=[A-Z]\\.)"), "$1.") }
                if (names.isNullOrEmpty()) return
                if (!stronger && query("SELECT COUNT(*) FROM work_people WHERE work = ? AND role = ?", id, role) { it.getInt(1) }.first() > 0) return
                exec("DELETE FROM work_people WHERE work = ? AND role = ?", id, role)
                names.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEachIndexed { i, n -> exec("INSERT OR REPLACE INTO work_people (work, role, idx, name) VALUES (?, ?, ?, ?)", id, role, i, n) }
                if (role == "author") exec("UPDATE works SET author = ? WHERE id = ?", names.first().trim(), id)
                if (role == "narrator") exec("UPDATE works SET narrator = ? WHERE id = ?", names.joinToString(", ") { it.trim() }, id)
            }
            people("author", m.authors); people("narrator", m.narrators)
            if (stronger) exec("UPDATE works SET meta_source = ? WHERE id = ?", rank, id)
        }
    }

    /** Every series with its size, how many of its works are synced, total audio, authors, and a cover work id. */
    fun series(): List<SeriesSummary> {
        val all = works()
        val byId = all.associateBy { it.id }
        val groups = HashMap<String, MutableList<Pair<Work, Double?>>>()
        for (w in all) for (sr in w.series) groups.getOrPut(sr.name) { ArrayList() }.add(w to sr.sequence)
        return groups.entries.map { (name, members) ->
            val sorted = members.sortedWith(compareBy<Pair<Work, Double?>, Double?>(nullsLast()) { it.second })
            SeriesSummary(name, members.size, members.count { it.first.shelf == "ready" }, members.sumOf { it.first.duration },
                members.flatMap { it.first.authors }.distinct().take(3), sorted.firstOrNull { it.first.cover }?.first?.id)
        }.sortedBy { it.name.lowercase() }
    }

    /** Works this person has a position in (read or listened past the start) and has not finished: the books they are on. */
    fun startedWorks(user: Long): Set<String> = query("SELECT DISTINCT work FROM positions_v2 WHERE user = ? AND (passage > 0 OR audio > 0)", user) { it.getString(1) }.toSet()

    /**
     * For each series in which the person has finished or started a book (`finished`: work id to when; `started`:
     * works with a position), in series order: the book after the furthest finished one is `current` if they have
     * started it and `next` if not. Nothing advances past an unfinished book: while the current one is being read
     * there is no next. A series read to its last book has neither, even if an earlier one was skipped.
     */
    fun seriesProgress(finished: Map<String, Long>, started: Set<String>): List<SeriesProgress> {
        val touched = finished.keys + started
        if (touched.isEmpty()) return emptyList()
        val groups = HashMap<String, MutableList<Pair<Work, Double?>>>()
        for (w in works()) for (sr in w.series) groups.getOrPut(sr.name) { ArrayList() }.add(w to sr.sequence)
        return groups.mapNotNull { (name, members) ->
            if (members.none { it.first.id in touched }) return@mapNotNull null
            val ordered = members.sortedWith(compareBy<Pair<Work, Double?>, Double?>(nullsLast()) { it.second }.thenBy { it.first.title }).map { it.first }.distinctBy { it.id }
            val furthest = ordered.indexOfLast { it.id in finished }
            val after = ordered.drop(furthest + 1).firstOrNull { it.id !in finished }
            val current = after?.takeIf { it.id in started }
            val next = after?.takeIf { it.id !in started }
            SeriesProgress(name, ordered.size, ordered.count { it.id in finished }, current?.id, next?.id, ordered.filter { it.id in finished }.maxOfOrNull { finished.getValue(it.id) } ?: 0L)
        }.sortedByDescending { it.lastFinished }
    }

    fun seriesWorks(name: String): List<Work> {
        fun seq(w: Work) = w.series.firstOrNull { it.name.equals(name, ignoreCase = true) }
        return works().filter { seq(it) != null }.sortedWith(compareBy<Work, Double?>(nullsLast()) { seq(it)?.sequence }.thenBy { it.title })
    }

    fun people(role: String): List<PersonSummary> {
        val all = works()
        val groups = HashMap<String, MutableList<Work>>()
        for (w in all) for (n in (if (role == "narrator") w.narrators else w.authors)) groups.getOrPut(n) { ArrayList() }.add(w)
        return groups.entries.map { (name, ws) -> PersonSummary(name, ws.size, ws.count { it.shelf == "ready" }) }.sortedBy { it.name.substringAfterLast(' ').lowercase() + " " + it.name.lowercase() }
    }

    fun personWorks(role: String, name: String): List<Work> =
        works().filter { w -> (if (role == "narrator") w.narrators else w.authors).any { it.equals(name, ignoreCase = true) } }
            .sortedWith(compareBy<Work> { it.series.firstOrNull()?.name?.lowercase() ?: "\uffff" }.thenBy<Work, Double?>(nullsLast()) { it.series.firstOrNull()?.sequence }.thenBy { it.title.lowercase() })

    /** Search over title, subtitle, authors, narrators and series; empty query returns everything. */
    fun search(q: String): List<Work> {
        val needle = q.trim().lowercase()
        if (needle.isBlank()) return works()
        return works().filter { w ->
            w.title.lowercase().contains(needle) || w.subtitle.lowercase().contains(needle) ||
                w.authors.any { it.lowercase().contains(needle) } || w.narrators.any { it.lowercase().contains(needle) } ||
                w.series.any { it.name.lowercase().contains(needle) }
        }
    }
    /** The cover an admin chose in the overlay, else the library's. */
    fun coverPath(id: String): String? = query("SELECT value FROM overrides WHERE work = ? AND field = 'cover'", id) { it.getString(1) }.firstOrNull()?.takeIf { File(it).isFile }
        ?: query("SELECT cover_path FROM works WHERE id = ?", id) { it.getString(1) }.firstOrNull()
    fun stamps(id: String): Pair<Long, Long>? = query("SELECT ebook_stamp, audio_stamp FROM works WHERE id = ?", id) { it.getLong(1) to it.getLong(2) }.firstOrNull()
    fun alignmentVersion(id: String): Int = query("SELECT alignment_version FROM works WHERE id = ?", id) { it.getInt(1) }.firstOrNull() ?: 0
    fun byKeys(titleKey: String, authorKey: String): String? = query("SELECT id FROM works WHERE title_key = ? AND author_key = ?", titleKey, authorKey) { it.getString(1) }.firstOrNull()
    fun byTitleKey(titleKey: String): List<String> = query("SELECT id FROM works WHERE title_key = ?", titleKey) { it.getString(1) }

    fun upsertWork(id: String, title: String, author: String, titleKey: String, authorKey: String) {
        exec(
            "INSERT INTO works (id, title, author, title_key, author_key, updated) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET title = excluded.title, author = excluded.author",
            id, title, author, titleKey, authorKey, System.currentTimeMillis(),
        )
    }

    fun setEbook(id: String, path: String?, format: String?, stamp: Long, cover: String?, passages: Int) {
        exec("UPDATE works SET ebook_path = ?, ebook_format = ?, ebook_stamp = ?, cover_path = COALESCE(?, cover_path), passages = ?, updated = ? WHERE id = ?", path, format, stamp, cover, passages, System.currentTimeMillis(), id)
    }

    fun setAudio(id: String, dir: String?, stamp: Long, narrator: String, tracks: List<Track>, chapters: List<Chapter>, cover: String?) {
        transaction {
            exec("UPDATE works SET audio_dir = ?, audio_stamp = ?, narrator = CASE WHEN ? <> '' THEN ? ELSE narrator END, duration = ?, cover_path = COALESCE(cover_path, ?), updated = ? WHERE id = ?", dir, stamp, narrator, narrator, tracks.sumOf { it.duration }, cover, System.currentTimeMillis(), id)
            exec("DELETE FROM tracks WHERE work = ?", id)
            exec("DELETE FROM chapters WHERE work = ?", id)
            tracks.forEachIndexed { i, t -> exec("INSERT INTO tracks (work, idx, name, path, duration) VALUES (?, ?, ?, ?, ?)", id, i, t.name, t.uri, t.duration) }
            chapters.forEachIndexed { i, c -> exec("INSERT INTO chapters (work, idx, title, start) VALUES (?, ?, ?, ?)", id, i, c.title, c.start) }
        }
    }

    fun tracks(id: String): List<TrackInfo> = query("SELECT idx, name, duration, path FROM tracks WHERE work = ? ORDER BY idx", id) { TrackInfo(it.getInt(1), it.getString(2), it.getLong(3), it.getString(4)) }
    fun chapters(id: String): List<ChapterInfo> = query("SELECT title, start FROM chapters WHERE work = ? ORDER BY idx", id) { ChapterInfo(it.getString(1), it.getLong(2)) }
    fun units(id: String): List<UnitInfo> = query("SELECT key, idx, title, start_ms, end_ms, done, elapsed_ms, sentences FROM units WHERE work = ? ORDER BY idx", id) {
        UnitInfo(it.getString(1), it.getInt(2), it.getString(3), it.getLong(4), it.getLong(5), it.getInt(6) != 0, it.getLong(7), it.getInt(8))
    }

    /** Replaces the unit plan, keeping the done state of units whose key (time span) is unchanged. */
    fun planUnits(id: String, plan: List<dev.homescreenreader.SyncPlan.SyncUnit>, version: Int) {
        transaction {
            if (alignmentVersion(id) != version) {
                exec("DELETE FROM units WHERE work = ?", id)
                exec("DELETE FROM anchors WHERE work = ?", id)
                exec("UPDATE works SET alignment_version = ? WHERE id = ?", version, id)
            }
            val keep = query("SELECT key FROM units WHERE work = ? AND done = 1", id) { it.getString(1) }.toSet()
            exec("DELETE FROM units WHERE work = ? AND done = 0", id)
            for (u in plan) if (u.key !in keep) exec("INSERT OR IGNORE INTO units (work, key, idx, title, start_ms, end_ms, core_ms) VALUES (?, ?, ?, ?, ?, ?, ?)", id, u.key, u.index, u.title, u.startMs, u.endMs, u.coreMs)
            exec("DELETE FROM units WHERE work = ? AND key NOT IN (${plan.joinToString(",") { "'" + it.key + "'" }})", id)
        }
    }

    fun unitDone(id: String, key: String, anchors: List<Anchor>, elapsedMs: Long) {
        transaction {
            for (a in anchors) exec("INSERT OR REPLACE INTO anchors (work, passage, start, end, confidence) VALUES (?, ?, ?, ?, ?)", id, a.passage, a.start, a.end, a.confidence)
            exec("UPDATE units SET done = 1, elapsed_ms = ?, sentences = ? WHERE work = ? AND key = ?", elapsedMs, anchors.size, id, key)
        }
    }

    fun anchors(id: String): List<Anchor> = query("SELECT passage, start, end, confidence FROM anchors WHERE work = ? ORDER BY passage", id) { Anchor(it.getInt(1), it.getLong(2), it.getLong(3), it.getDouble(4)) }
    fun anchorCount(id: String): Int = query("SELECT COUNT(*) FROM anchors WHERE work = ?", id) { it.getInt(1) }.first()

    fun setStatus(id: String, status: String) { exec("UPDATE works SET status = ?, updated = ? WHERE id = ?", status, System.currentTimeMillis(), id) }
    fun resetSync(id: String) { transaction { exec("DELETE FROM units WHERE work = ?", id); exec("DELETE FROM anchors WHERE work = ?", id); exec("UPDATE works SET status = '' WHERE id = ?", id) } }

    fun position(user: Long, id: String, device: String): Position? = query("SELECT passage, audio, updated, name FROM positions_v2 WHERE user = ? AND work = ? AND device = ?", user, id, device) { Position(it.getInt(1), it.getLong(2), it.getLong(3), it.getString(4)) }.firstOrNull()
    /** Where the person last was in the book, on whichever device moved it last. */
    fun latestPosition(user: Long, id: String): Position? = query("SELECT passage, audio, updated, name FROM positions_v2 WHERE user = ? AND work = ? ORDER BY updated DESC LIMIT 1", user, id) { Position(it.getInt(1), it.getLong(2), it.getLong(3), it.getString(4)) }.firstOrNull()
    /** The newest place saved by any of the person's other devices. */
    fun latestElsewhere(user: Long, id: String, device: String): Position? = query("SELECT passage, audio, updated, name FROM positions_v2 WHERE user = ? AND work = ? AND device <> ? ORDER BY updated DESC LIMIT 1", user, id, device) { Position(it.getInt(1), it.getLong(2), it.getLong(3), it.getString(4)) }.firstOrNull()
    fun clearPositions(user: Long): Int = exec("DELETE FROM positions_v2 WHERE user = ?", user) + exec("DELETE FROM speeds WHERE user = ?", user)
    fun speed(user: Long, id: String): Speed? = query("SELECT speed, updated FROM speeds WHERE user = ? AND work = ?", user, id) { Speed(it.getDouble(1).toFloat(), it.getLong(2)) }.firstOrNull()
    fun setSpeed(user: Long, id: String, speed: Float): Speed {
        require(speed in 0.5f..3f) { "Speed is between 0.5 and 3" }
        val now = System.currentTimeMillis()
        exec("INSERT OR REPLACE INTO speeds (user, work, speed, updated) VALUES (?, ?, ?, ?)", user, id, speed.toDouble(), now)
        return Speed(speed, now)
    }
    fun setPosition(user: Long, id: String, device: String, p: Position, name: String = "") { exec("INSERT OR REPLACE INTO positions_v2 (user, work, device, passage, audio, updated, name) VALUES (?, ?, ?, ?, ?, ?, ?)", user, id, device, p.passage, p.audio, System.currentTimeMillis(), name) }

    fun pairingOverride(titleKey: String, authorKey: String): String? = query("SELECT ebook_path FROM pairing WHERE title_key = ? AND author_key = ?", titleKey, authorKey) { it.getString(1) }.firstOrNull()
    fun setPairing(titleKey: String, authorKey: String, ebookPath: String) { exec("INSERT OR REPLACE INTO pairing (title_key, author_key, ebook_path) VALUES (?, ?, ?)", titleKey, authorKey, ebookPath); exec("DELETE FROM audio_only WHERE title_key = ? AND author_key = ?", titleKey, authorKey) }
    fun clearPairing(titleKey: String, authorKey: String) = exec("DELETE FROM pairing WHERE title_key = ? AND author_key = ?", titleKey, authorKey)
    fun setAudioOnly(titleKey: String, authorKey: String, on: Boolean) { if (on) exec("INSERT OR REPLACE INTO audio_only (title_key, author_key, decided) VALUES (?, ?, ?)", titleKey, authorKey, System.currentTimeMillis()) else exec("DELETE FROM audio_only WHERE title_key = ? AND author_key = ?", titleKey, authorKey) }
    fun audioOnly(titleKey: String, authorKey: String): Boolean = query("SELECT 1 FROM audio_only WHERE title_key = ? AND author_key = ?", titleKey, authorKey) { 1 }.isNotEmpty()
    fun keysOf(id: String): Pair<String, String>? = query("SELECT title_key, author_key FROM works WHERE id = ?", id) { it.getString(1) to it.getString(2) }.firstOrNull()

    /**
     * A consistent copy of the whole database (catalogue, accounts, shelves, marks, sessions) written
     * through SQLite's online backup, so the nightly folder copy picks up a file that opens cleanly even
     * though the live database is in WAL mode. Keeps the newest seven.
     */
    fun backup(dir: java.io.File): java.io.File {
        dir.mkdirs()
        val stamp = java.time.LocalDate.now().toString()
        val target = java.io.File(dir, "catalog-$stamp.db")
        synchronized(db) { db.createStatement().use { it.executeUpdate("backup to '${target.path.replace("'", "''")}'") } }
        dir.listFiles { f -> f.name.startsWith("catalog-") && f.name.endsWith(".db") }?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
        return target
    }
    fun backups(dir: java.io.File): List<java.io.File> = dir.listFiles { f -> f.name.startsWith("catalog-") && f.name.endsWith(".db") }?.sortedByDescending { it.name }.orEmpty()

    fun removeWork(id: String) { exec("DELETE FROM works WHERE id = ?", id) }
    fun allIds(): List<String> = query("SELECT id FROM works") { it.getString(1) }

    companion object {
        /** Separates the entries of a list kept in one column. */
        const val SEP = "\u001f"
        /** The fields the overlay can correct. */
        val FIELDS = listOf("title", "subtitle", "authors", "narrators", "series", "description", "publishedYear", "publisher", "language", "genres", "cover")
        fun encodeSeries(series: List<SeriesRef>) = series.joinToString(SEP) { it.name + "\u001e" + (it.sequence?.toString() ?: "") }
        fun decodeSeries(value: String) = value.split(SEP).filter { it.isNotBlank() }.map { e -> SeriesRef(e.substringBefore('\u001e').trim(), e.substringAfter('\u001e', "").toDoubleOrNull()) }.filter { it.name.isNotBlank() }
    }
}
