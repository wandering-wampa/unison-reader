package dev.homescreenreader

import android.content.*
import android.database.sqlite.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.*

class Library(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "library.db", null, 4) {
    private val files = File(context.filesDir, "catalog").apply { mkdirs() }
    private val libraryFiles = File(context.filesDir, "library")

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE imports(hash TEXT PRIMARY KEY, book TEXT NOT NULL, source TEXT NOT NULL, cleanup TEXT NOT NULL, format TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE anchors(book TEXT, passage INTEGER, start INTEGER, end INTEGER, confidence REAL, PRIMARY KEY(book,passage))"
        )
        db.execSQL("CREATE INDEX audio_position ON anchors(book,start)")
        db.execSQL(
            "CREATE TABLE preparation(book TEXT PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, elapsed INTEGER NOT NULL DEFAULT 0, done TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE units(book TEXT, unit TEXT, sentences INTEGER NOT NULL DEFAULT 0, elapsed INTEGER NOT NULL DEFAULT 0, audio INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(book,unit))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2)
            db.execSQL("ALTER TABLE imports ADD COLUMN format TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3)
            db.execSQL("ALTER TABLE preparation ADD COLUMN done TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 4)
            db.execSQL(
                "CREATE TABLE units(book TEXT, unit TEXT, sentences INTEGER NOT NULL DEFAULT 0, elapsed INTEGER NOT NULL DEFAULT 0, audio INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(book,unit))"
            )
    }

    @Synchronized
    fun save(book: Book) {
        val file = File(files, "${book.id}-${java.util.UUID.randomUUID()}.json")
        file.outputStream().use { out ->
            out.write(encode(book).toString().toByteArray())
            out.fd.sync()
        }
        writableDatabase.replaceOrThrow(
            "books",
            null,
            ContentValues().apply {
                put("id", book.id)
                put("data", file.name)
            },
        )
        // Do not publish an uncommitted catalog record to other Library instances.
        cache.remove(book.id)
        // Earlier copies of this book's record are no longer the catalogue's. One written in the last minute is left
        // for now, in case another part of the app has just been told its name and is about to read it.
        val old = System.currentTimeMillis() - 60_000
        files.listFiles { f -> f.name.startsWith(book.id + "-") && f.name.endsWith(".json") && f.name != file.name && f.lastModified() < old }?.forEach { it.delete() }
    }

    fun load(id: String): Book? {
        cache[id]?.let {
            return it
        }
        val descriptor =
            readableDatabase
                .rawQuery(
                    "SELECT length(data),substr(data,1,128) FROM books WHERE id=?",
                    arrayOf(id),
                )
                .use { if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null }
                ?: return null
        val raw =
            if (descriptor.second.startsWith("{")) {
                buildString {
                    var start = 1
                    while (start <= descriptor.first) {
                        readableDatabase
                            .rawQuery(
                                "SELECT substr(data,?,500000) FROM books WHERE id=?",
                                arrayOf(start.toString(), id),
                            )
                            .use {
                                it.moveToFirst()
                                append(it.getString(0))
                            }
                        start += 500000
                    }
                }
            } else File(files, descriptor.second).readText()
        var b = decode(JSONObject(raw))
        if (b.ebookUri.isEmpty())
            libraryFiles
                .resolve(id)
                .listFiles()
                ?.firstOrNull { it.extension in listOf("epub", "mobi", "azw3") }
                ?.let { b = b.copy(ebookUri = it.toURI().toString()) }
        if (descriptor.second.startsWith("{")) save(b) else cache[id] = b
        return b
    }

    /**
     * A server book's title, author and narrator as the server gives them now (an admin corrected them there).
     * Nothing else about the book changes. True when something was different.
     */
    fun rename(id: String, title: String, author: String, narrator: String): Boolean {
        val b = load(id) ?: return false
        if (title.isBlank() || (b.title == title && b.author == author && b.narrator == narrator)) return false
        val old = readableDatabase.rawQuery("SELECT data FROM books WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
        save(b.copy(title = title, author = author, narrator = narrator))
        if (old != null && !old.startsWith("{")) File(files, old).delete()
        return true
    }

    /** Forgets a book on this device: its catalog record, alignment and files. The server (if any) is untouched. */
    fun remove(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT data FROM books WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) { val name = it.getString(0); if (!name.startsWith("{")) File(files, name).delete() } }
            db.delete("books", "id=?", arrayOf(id))
            db.delete("anchors", "book=?", arrayOf(id)); db.delete("units", "book=?", arrayOf(id)); db.delete("preparation", "book=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        cache.remove(id)
        libraryFiles.resolve(id).deleteRecursively()
    }

    fun books(): List<Book> =
        readableDatabase
            .rawQuery("SELECT id FROM books", null)
            .use { c -> buildList { while (c.moveToNext()) load(c.getString(0))?.let { add(it) } } }
            .sortedBy { it.title }

    fun duplicate(hash: String): String? =
        readableDatabase.rawQuery("SELECT book FROM imports WHERE hash=?", arrayOf(hash)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun committedSource(source: String): String? =
        readableDatabase.rawQuery("SELECT book FROM imports WHERE source=?", arrayOf(source)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun recordImport(hash: String, book: String, source: String, cleanup: String, format: String) {
        writableDatabase.replaceOrThrow(
            "imports",
            null,
            ContentValues().apply {
                put("hash", hash)
                put("book", book)
                put("source", source)
                put("cleanup", cleanup)
                put("format", format)
            },
        )
    }

    fun cleanup(): List<Pair<String, String>> =
        readableDatabase
            .rawQuery("SELECT source,cleanup FROM imports WHERE cleanup!='removed'", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    fun cleanupResult(source: String, result: String) {
        writableDatabase.execSQL(
            "UPDATE imports SET cleanup=? WHERE source=?",
            arrayOf(result, source),
        )
    }

    fun anchors(book: String): List<Anchor> =
        readableDatabase
            .rawQuery(
                "SELECT passage,start,end,confidence FROM anchors WHERE book=? ORDER BY start",
                arrayOf(book),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Anchor(c.getInt(0), c.getLong(1), c.getLong(2), c.getDouble(3))
                    )
                }
            }

    fun atTime(book: String, time: Long): Anchor? =
        readableDatabase
            .rawQuery(
                "SELECT passage,start,end,confidence FROM anchors WHERE book=? AND start<=? AND end>? ORDER BY start DESC LIMIT 1",
                arrayOf(book, time.toString(), time.toString()),
            )
            .use {
                if (it.moveToFirst())
                    Anchor(it.getInt(0), it.getLong(1), it.getLong(2), it.getDouble(3))
                else null
            }

    fun atPassage(book: String, p: Int): Anchor? =
        readableDatabase
            .rawQuery(
                "SELECT passage,start,end,confidence FROM anchors WHERE book=? AND passage=?",
                arrayOf(book, p.toString()),
            )
            .use {
                if (it.moveToFirst())
                    Anchor(it.getInt(0), it.getLong(1), it.getLong(2), it.getDouble(3))
                else null
            }

    /** The closest measured sentence at or before `p`, within `maxDistance` sentences. */
    /** The first measured sentence at or after `p`, within `maxDistance` sentences: where listening from `p` begins. */
    fun nearestAfter(book: String, p: Int, maxDistance: Int): Anchor? =
        readableDatabase
            .rawQuery(
                "SELECT passage,start,end,confidence FROM anchors WHERE book=? AND passage>=? AND passage<=? ORDER BY passage ASC LIMIT 1",
                arrayOf(book, p.toString(), (p + maxDistance).toString()),
            )
            .use {
                if (it.moveToFirst())
                    Anchor(it.getInt(0), it.getLong(1), it.getLong(2), it.getDouble(3))
                else null
            }

    fun nearestBefore(book: String, p: Int, maxDistance: Int): Anchor? =
        readableDatabase
            .rawQuery(
                "SELECT passage,start,end,confidence FROM anchors WHERE book=? AND passage<=? AND passage>=? ORDER BY passage DESC LIMIT 1",
                arrayOf(book, p.toString(), (p - maxDistance).toString()),
            )
            .use {
                if (it.moveToFirst())
                    Anchor(it.getInt(0), it.getLong(1), it.getLong(2), it.getDouble(3))
                else null
            }

    fun anchorCount(book: String): Int =
        readableDatabase.rawQuery("SELECT count(*) FROM anchors WHERE book=?", arrayOf(book)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Matched sentences whose narration starts inside [startMs, endMs). */
    fun anchorCountBetween(book: String, startMs: Long, endMs: Long): Int =
        readableDatabase
            .rawQuery("SELECT count(*) FROM anchors WHERE book=? AND start>=? AND start<?", arrayOf(book, startMs.toString(), endMs.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    class UnitStat(val sentences: Int, val elapsed: Long, val audio: Long)

    /** Per-unit results of completed synchronization, keyed by unit. */
    fun unitStats(book: String): Map<String, UnitStat> =
        readableDatabase
            .rawQuery("SELECT unit,sentences,elapsed,audio FROM units WHERE book=?", arrayOf(book))
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), UnitStat(c.getInt(1), c.getLong(2), c.getLong(3))) } }

    /** Units (chapters or bounded pieces) whose sentences are already synchronized. */
    fun doneUnits(book: String): Set<String> =
        readableDatabase
            .rawQuery("SELECT done FROM preparation WHERE book=?", arrayOf(book))
            .use { if (it.moveToFirst()) it.getString(0) else "" }
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Commits one synchronized unit atomically: its sentence intervals replace any earlier
     * measurement for the same sentences, and the unit is recorded as done.
     */
    fun unitDone(book: String, unit: String, anchors: List<Anchor>, elapsed: Long, audioMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            anchors.forEach { a ->
                db.insertWithOnConflict(
                    "anchors",
                    null,
                    ContentValues().apply {
                        put("book", book)
                        put("passage", a.passage)
                        put("start", a.start)
                        put("end", a.end)
                        put("confidence", a.confidence)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val done = (doneUnits(book) + unit).joinToString(",")
            db.execSQL(
                "INSERT OR REPLACE INTO units(book,unit,sentences,elapsed,audio) VALUES(?,?,?,?,?)",
                arrayOf(book, unit, anchors.size, elapsed, audioMs),
            )
            db.execSQL(
                "INSERT INTO preparation(book,cursor,status,elapsed,done) VALUES(?,?,'Syncing',?,?) ON CONFLICT(book) DO UPDATE SET cursor=preparation.cursor+excluded.cursor,elapsed=preparation.elapsed+excluded.elapsed,done=excluded.done",
                arrayOf(book, audioMs, elapsed, done),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun prep(book: String): Triple<Long, String, Long> =
        readableDatabase
            .rawQuery("SELECT cursor,status,elapsed FROM preparation WHERE book=?", arrayOf(book))
            .use {
                if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getLong(2))
                else Triple(0, "Not prepared", 0)
            }

    fun status(book: String, status: String) {
        writableDatabase.execSQL(
            "INSERT INTO preparation(book,status) VALUES(?,?) ON CONFLICT(book) DO UPDATE SET status=excluded.status",
            arrayOf(book, status),
        )
    }

    fun invalidateAlignment(book: String) {
        writableDatabase.delete("anchors", "book=?", arrayOf(book))
        writableDatabase.delete("preparation", "book=?", arrayOf(book))
        writableDatabase.delete("units", "book=?", arrayOf(book))
    }
    fun moveAudio(from: Book, to: Book): Book {
        require(from.tracks.isNotEmpty() && to.tracks.isEmpty())
        val paired =
            to.copy(
                tracks = from.tracks,
                chapters = from.chapters,
                narrator = from.narrator,
                cover = to.cover.ifBlank { from.cover },
            )
        writableDatabase.beginTransaction()
        try {
            save(paired)
            if (from.passages.isEmpty()) {
                writableDatabase.delete("books", "id=?", arrayOf(from.id))
                cache.remove(from.id)
            } else save(from.copy(tracks = emptyList(), chapters = emptyList(), narrator = ""))
            invalidateAlignment(from.id)
            invalidateAlignment(to.id)
            writableDatabase.execSQL(
                "UPDATE imports SET book=? WHERE book=?" +
                    if (from.passages.isEmpty()) ""
                    else
                        " AND (format IN ('zip','m4b','mp3','m4a') OR (format='' AND (source LIKE '%.zip' OR source LIKE '%.m4b' OR source LIKE '%.mp3' OR source LIKE '%.m4a')))",
                arrayOf(to.id, from.id),
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return paired
    }

    companion object {
        private val cache = ConcurrentHashMap<String, Book>()

        private fun encode(b: Book) =
            JSONObject()
                .put("id", b.id)
                .put("title", b.title)
                .put("author", b.author)
                .put("narrator", b.narrator)
                .put("cover", b.cover)
                .put("ebookUri", b.ebookUri)
                .put("ebookFormat", b.ebookFormat)
                .put(
                    "passages",
                    JSONArray(
                        b.passages.map {
                            JSONArray(listOf(it.text, it.html, it.section, it.offset, it.block))
                        }
                    ),
                )
                .put("toc", JSONArray(b.toc.map { JSONArray(listOf(it.first, it.second)) }))
                .put(
                    "tracks",
                    JSONArray(b.tracks.map { JSONArray(listOf(it.uri, it.name, it.duration)) }),
                )
                .put(
                    "chapters",
                    JSONArray(b.chapters.map { JSONArray(listOf(it.title, it.start)) }),
                )

        private fun decode(j: JSONObject): Book {
            fun rows(key: String) =
                j.getJSONArray(key).let { a -> List(a.length()) { a.getJSONArray(it) } }
            return Book(
                j.getString("id"),
                j.getString("title"),
                j.getString("author"),
                j.optString("narrator"),
                rows("passages").map {
                    Passage(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), if (it.length() > 4) it.getInt(4) else -1)
                },
                rows("toc").map { it.getString(0) to it.getInt(1) },
                rows("tracks").map { Track(it.getString(0), it.getString(1), it.getLong(2)) },
                rows("chapters").map { Chapter(it.getString(0), it.getLong(1)) },
                j.optString("cover"),
                j.optString("ebookUri"),
                j.optString("ebookFormat", "epub"),
            )
        }
    }
}

class ReadingState(context: Context) {
    private val prefs = context.getSharedPreferences("reading-v2", Context.MODE_PRIVATE)
    var active: String
        get() = prefs.getString("active", "").orEmpty()
        set(v) {
            prefs.edit().putString("active", v).commit()
        }

    var tree: String
        get() = prefs.getString("tree", "").orEmpty()
        set(v) {
            prefs.edit().putString("tree", v).commit()
        }

    var font: Int
        get() = prefs.getInt("font", 22)
        set(v) {
            prefs.edit().putInt("font", v.coerceIn(14, 40)).commit()
        }

    /** The app's own light or dark: "system" (follow the phone), "light" or "dark". The reader's paper is separate. */
    var appTheme: String
        get() = prefs.getString("appTheme", "system")!!
        set(v) {
            prefs.edit().putString("appTheme", v).commit()
        }

    var theme: String
        get() = prefs.getString("theme", "system")!!
        set(v) {
            prefs.edit().putString("theme", v).commit()
        }

    var serif: Boolean
        get() = prefs.getBoolean("serif", true)
        set(v) {
            prefs.edit().putBoolean("serif", v).commit()
        }

    /** The last speed chosen anywhere: where a book never given its own speed starts. */
    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) {
            prefs.edit().putFloat("speed", v).commit()
        }

    /** This book's speed: its own, or the last speed chosen. */
    fun speed(id: String): Float = prefs.getFloat("$id.speed", speed)
    /** `local`: chosen on this device and not yet sent to the server. */
    fun speed(id: String, v: Float, local: Boolean) { prefs.edit().putFloat("$id.speed", v).putBoolean("$id.speedUnsent", local).apply { if (local) putFloat("speed", v) }.commit() }
    fun speedUnsent(id: String) = prefs.getBoolean("$id.speedUnsent", false)
    fun speedSent(id: String) { prefs.edit().putBoolean("$id.speedUnsent", false).commit() }

    var typeface: String
        get() = prefs.getString("typeface", if (serif) "literata" else "sans")!!
        set(v) { prefs.edit().putString("typeface", v).putBoolean("serif", v != "sans").apply() }
    var lineHeight: Int
        get() = prefs.getInt("lineHeight", 150)
        set(v) { prefs.edit().putInt("lineHeight", v.coerceIn(115, 200)).apply() }
    var margin: Int
        get() = prefs.getInt("margin", 24)
        set(v) { prefs.edit().putInt("margin", v.coerceIn(8, 48)).apply() }
    var paragraphSpace: Int
        get() = prefs.getInt("paragraphSpace", 6)
        set(v) { prefs.edit().putInt("paragraphSpace", v.coerceIn(0, 24)).apply() }
    var justify: Boolean
        get() = prefs.getBoolean("justify", false)
        set(v) { prefs.edit().putBoolean("justify", v).apply() }
    var animation: String
        get() = prefs.getString("animation", "curl")!!
        set(v) { prefs.edit().putString("animation", v).apply() }
    var turnDuration: Int
        get() = prefs.getInt("turnDuration", 380)
        set(v) { prefs.edit().putInt("turnDuration", v.coerceIn(120, 700)).apply() }
    var scrollMode: Boolean
        get() = prefs.getBoolean("scrollMode", false)
        set(v) { prefs.edit().putBoolean("scrollMode", v).apply() }
    var volumeTurns: Boolean
        get() = prefs.getBoolean("volumeTurns", true)
        set(v) { prefs.edit().putBoolean("volumeTurns", v).apply() }
    var tapLayout: String
        get() = prefs.getString("tapLayout", "edges")!!
        set(v) { prefs.edit().putString("tapLayout", v).apply() }
    var brightnessGesture: Boolean
        get() = prefs.getBoolean("brightnessGesture", true)
        set(v) { prefs.edit().putBoolean("brightnessGesture", v).apply() }
    /** A vertical drag on the right edge changes the text size. */
    var textSizeGesture: Boolean
        get() = prefs.getBoolean("textSizeGesture", true)
        set(v) { prefs.edit().putBoolean("textSizeGesture", v).apply() }
    var keepAwake: Boolean
        get() = prefs.getBoolean("keepAwake", true)
        set(v) { prefs.edit().putBoolean("keepAwake", v).apply() }
    var followScroll: Boolean
        get() = prefs.getBoolean("followScroll", true)
        set(v) { prefs.edit().putBoolean("followScroll", v).apply() }
    var pauseOnTurn: Boolean
        get() = prefs.getBoolean("pauseOnTurn", false)
        set(v) { prefs.edit().putBoolean("pauseOnTurn", v).apply() }

    /** UnisonServer connection: address (scheme optional), bearer token, and this device's name for positions. */
    var serverUrl: String
        get() = prefs.getString("serverUrl", "").orEmpty()
        set(v) { prefs.edit().putString("serverUrl", v.trim()).commit() }
    var serverToken: String
        get() = prefs.getString("serverToken", "").orEmpty()
        set(v) { prefs.edit().putString("serverToken", v.trim()).commit() }
    /** Bookmarks and highlights of a book: one per sentence and kind, with an optional note. */
    fun marks(id: String): JSONArray = runCatching { JSONArray(prefs.getString("$id.marks", "[]")) }.getOrDefault(JSONArray())
    fun markIds(id: String, kind: String): List<Int> { val a = marks(id); return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("kind") == kind }.map { it.getInt("passage") } }
    fun setMark(id: String, passage: Int, kind: String, note: String, text: String, created: Long = System.currentTimeMillis()) {
        val a = marks(id); val out = JSONArray()
        for (i in 0 until a.length()) { val m = a.getJSONObject(i); if (!(m.getInt("passage") == passage && m.getString("kind") == kind)) out.put(m) }
        out.put(JSONObject().put("passage", passage).put("kind", kind).put("note", note).put("text", text.take(600)).put("created", created))
        prefs.edit().putString("$id.marks", out.toString()).commit()
    }
    fun removeMark(id: String, passage: Int, kind: String) {
        val a = marks(id); val out = JSONArray()
        for (i in 0 until a.length()) { val m = a.getJSONObject(i); if (!(m.getInt("passage") == passage && m.getString("kind") == kind)) out.put(m) }
        prefs.edit().putString("$id.marks", out.toString()).commit()
    }
    /** Listening and reading sessions recorded on this device and not yet sent to the server. */
    /**
     * Another person signed in on this device: everything personal about server books goes (positions,
     * follow, finished, shelf, marks, what was pushed) and queued sessions are dropped, so nothing of the
     * previous person is mirrored into the new account. Books imported locally keep their positions.
     */
    /** The local ids of the books that came from the server. */
    fun serverBooks(): List<String> = prefs.all.keys.filter { it.endsWith(".serverWork") }.map { it.removeSuffix(".serverWork") }

    /** Shelf changes (on or off the shelf, finished or not) made here that the server has not confirmed yet. */
    fun shelfOwed(id: String): Boolean = id in (prefs.getStringSet("shelfOwed", emptySet()) ?: emptySet())
    fun shelfOwed(id: String, owed: Boolean) {
        synchronized(ReadingState::class.java) {
            val now = HashSet(prefs.getStringSet("shelfOwed", emptySet()) ?: emptySet())
            if (if (owed) now.add(id) else now.remove(id)) prefs.edit().putStringSet("shelfOwed", now).commit()
        }
    }

    var shelfOwedSeeded: Boolean
        get() = prefs.getBoolean("shelfOwedSeeded", false)
        set(v) { prefs.edit().putBoolean("shelfOwedSeeded", v).commit() }

    /** Who last signed in on this device, as "server|name": a sign-in by the same person keeps what the device holds. */
    var lastAccount: String
        get() = prefs.getString("lastAccount", "").orEmpty()
        set(v) { prefs.edit().putString("lastAccount", v).commit() }

    fun forgetPersonalState() {
        val ids = prefs.all.keys.filter { it.endsWith(".serverWork") }.map { it.removeSuffix(".serverWork") }
        val e = prefs.edit()
        e.remove("shelfOwed"); e.remove("marksOwed")
        for (id in ids) for (k in listOf("passage", "audio", "follow", "finished", "shelved", "marks", "marksSeeded", "pushed", "serverUpdated", "speed", "speedUnsent", "listened")) e.remove("$id.$k")
        e.putString("shelf", JSONArray(shelf().filter { it !in ids }).toString())
        e.remove("sessions"); e.remove("readWpm"); e.remove("arrivalsSeen")
        if (active in ids) e.remove("active")
        e.commit()
    }

    /**
     * The requests on the wanted list this person has already been told have arrived (null before the first look, when
     * what had arrived already is taken as known rather than announced).
     */
    var arrivalsSeen: Set<String>?
        get() = prefs.getStringSet("arrivalsSeen", null)?.toSet()
        set(v) { prefs.edit().putStringSet("arrivalsSeen", v).apply() }

    fun pendingSessions(): JSONArray = runCatching { JSONArray(prefs.getString("sessions", "[]")) }.getOrDefault(JSONArray())
    fun queueSession(work: String, kind: String, started: Long, ended: Long, sentences: Int = 0, pages: Int = 0) {
        if (ended - started < 3000) return
        synchronized(ReadingState::class.java) {
            val a = pendingSessions()
            a.put(JSONObject().put("work", work).put("kind", kind).put("started", started).put("ended", ended).put("sentences", sentences).put("pages", pages))
            // A long offline stretch keeps at most a few hundred; the oldest go first.
            while (a.length() > 400) a.remove(0)
            prefs.edit().putString("sessions", a.toString()).commit()
        }
    }
    /** Takes exactly the sessions that were sent out of the queue; ones queued meanwhile stay. */
    fun clearSessions(sent: JSONArray) {
        synchronized(ReadingState::class.java) {
            val gone = HashSet<String>(); for (i in 0 until sent.length()) gone.add(sent.get(i).toString())
            val a = pendingSessions(); val rest = JSONArray()
            for (i in 0 until a.length()) if (!gone.remove(a.get(i).toString())) rest.put(a.get(i))
            prefs.edit().putString("sessions", rest.toString()).commit()
        }
    }

    /** Bookmarks and highlights changed here that the server has not confirmed, as "book|passage|kind|put" or "…|del". */
    fun marksOwed(): List<String> = (prefs.getStringSet("marksOwed", emptySet()) ?: emptySet()).sorted()
    fun markOwed(id: String, passage: Int, kind: String, remove: Boolean) {
        synchronized(ReadingState::class.java) {
            val now = HashSet(prefs.getStringSet("marksOwed", emptySet()) ?: emptySet())
            now.remove("$id|$passage|$kind|put"); now.remove("$id|$passage|$kind|del")
            now.add("$id|$passage|$kind|" + if (remove) "del" else "put")
            prefs.edit().putStringSet("marksOwed", now).commit()
        }
    }
    fun markSettled(entry: String) {
        synchronized(ReadingState::class.java) {
            val now = HashSet(prefs.getStringSet("marksOwed", emptySet()) ?: emptySet())
            if (now.remove(entry)) prefs.edit().putStringSet("marksOwed", now).commit()
        }
    }
    /** True once this book's marks have been compared with the server's under the owed record (see Server.pullMarks). */
    fun marksSeeded(id: String) = prefs.getBoolean("$id.marksSeeded", false)
    fun marksSeeded(id: String, v: Boolean) { prefs.edit().putBoolean("$id.marksSeeded", v).commit() }
    var serverUser: String
        get() = prefs.getString("serverUser", "").orEmpty()
        set(v) { prefs.edit().putString("serverUser", v).commit() }
    var serverRole: String
        get() = prefs.getString("serverRole", "").orEmpty()
        set(v) { prefs.edit().putString("serverRole", v).commit() }
    /** The owner chose to use this device without a server, so the sign-in screen stays out of the way. */
    var standalone: Boolean
        get() = prefs.getBoolean("standalone", false)
        set(v) { prefs.edit().putBoolean("standalone", v).commit() }
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: (android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "") + "-" + java.util.UUID.randomUUID().toString().take(8)).also { prefs.edit().putString("deviceId", it).commit() }

    /** Which server work a local book came from, and whether its audio was downloaded (else streamed). */
    fun serverWork(id: String): String? = prefs.getString("$id.serverWork", null)
    fun serverDownloaded(id: String) = prefs.getBoolean("$id.serverDownloaded", false)
    /** True for a book added while the server was still matching its text to its audio: what it has matched since is fetched until it is done. */
    fun syncPending(id: String) = prefs.getBoolean("$id.syncPending", false)
    fun syncPending(id: String, v: Boolean) { prefs.edit().putBoolean("$id.syncPending", v).commit() }
    /** The version of the server's cover this device keeps for a book (0: the library's own; otherwise when an admin chose it). */
    fun coverVersion(id: String): Long = prefs.getLong("$id.coverVersion", 0)
    fun coverVersion(id: String, v: Long) { prefs.edit().putLong("$id.coverVersion", v).commit() }
    fun setServerWork(id: String, work: String, downloaded: Boolean) { prefs.edit().putString("$id.serverWork", work).putBoolean("$id.serverDownloaded", downloaded).commit() }
    /** Position bookkeeping for the server: the pair last sent, and the server's timestamp last seen. */
    fun pushed(id: String): String = prefs.getString("$id.pushed", "").orEmpty()
    fun serverUpdated(id: String): Long = prefs.getLong("$id.serverUpdated", 0)
    fun markPushed(id: String, passage: Int, audio: Long, updated: Long) { prefs.edit().putString("$id.pushed", "$passage:$audio").putLong("$id.serverUpdated", updated).commit() }
    /** My shelf: the books this person is reading or means to read next, in the order added. Per device until accounts exist. */
    fun shelf(): List<String> = runCatching { JSONArray(prefs.getString("shelf", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    fun onShelf(id: String) = id in shelf()
    fun addToShelf(id: String) { if (!onShelf(id)) prefs.edit().putString("shelf", JSONArray(shelf() + id).toString()).putLong("$id.shelved", System.currentTimeMillis()).commit() }
    fun removeFromShelf(id: String) { prefs.edit().putString("shelf", JSONArray(shelf().filter { it != id }).toString()).commit() }
    fun shelvedAt(id: String): Long = prefs.getLong("$id.shelved", 0)
    var libraryTab: String
        get() = prefs.getString("libraryTab", "shelf")!!
        set(v) { prefs.edit().putString("libraryTab", v).apply() }
    /** The Library category last browsed (books, series, authors, narrators): where the Library button returns to. */
    var libraryBrowse: String
        get() = prefs.getString("libraryBrowse", "books")!!
        set(v) { prefs.edit().putString("libraryBrowse", v).apply() }
    /** When the book was marked finished (epoch ms), or 0. */
    fun finished(id: String): Long = prefs.getLong("$id.finished", 0)
    fun setFinished(id: String, at: Long) { prefs.edit().putLong("$id.finished", at).commit() }
    /** Sets both halves of the position without touching follow (a position taken from the server). */
    fun position(id: String, passage: Int, audio: Long) { prefs.edit().putInt("$id.passage", passage).putLong("$id.audio", audio).commit() }

    fun passage(id: String) = prefs.getInt("$id.passage", 0)

    /** The server's series for the books kept here, as last fetched (JSON: book id to a list of name and sequence), for the car when offline. */
    var seriesCache: String
        get() = prefs.getString("seriesCache", "{}") ?: "{}"
        set(v) { prefs.edit().putString("seriesCache", v).apply() }

    /** When the book's audio last played on this device (the car's Recent list). */
    fun listened(id: String): Long = prefs.getLong("$id.listened", 0)
    fun listened(id: String, at: Long) { prefs.edit().putLong("$id.listened", at).apply() }

    /** This reader's pace in words a minute, learned from reading sessions on this device; 0 until one has been measured. */
    var readWpm: Float
        get() = prefs.getFloat("readWpm", 0f)
        set(v) { prefs.edit().putFloat("readWpm", v).apply() }

    fun audio(id: String) = prefs.getLong("$id.audio", 0)

    fun follow(id: String) = prefs.getBoolean("$id.follow", true)

    fun select(id: String, p: Int) {
        prefs.edit().putInt("$id.passage", p).putBoolean("$id.follow", false).commit()
    }

    fun follow(id: String, v: Boolean) {
        prefs.edit().putBoolean("$id.follow", v).commit()
    }

    fun audio(id: String, time: Long, p: Int?) {
        val e = prefs.edit().putLong("$id.audio", time)
        if (p != null && follow(id)) e.putInt("$id.passage", p)
        // Written in the background: this runs every second while audio plays, and the value is readable at once.
        e.apply()
    }
}
