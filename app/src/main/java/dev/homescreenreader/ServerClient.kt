package dev.homescreenreader

import android.content.Context
import java.io.File
import org.json.JSONObject

/** The app's side of the server: adding a server work to the local library, and keeping the position in step. */
object Server {
    fun client(state: ReadingState): ServerClient? =
        state.serverUrl.takeIf { it.isNotBlank() && state.serverToken.isNotBlank() }?.let { ServerClient(it, state.serverToken, state.deviceId) }

    /** Local book id for a server work: stable, so adding twice updates rather than duplicates. */
    fun bookId(workId: String) = "srv-$workId"

    /**
     * Brings a synced server work into the local library: the EPUB (parsed here with the same parser the
     * server used, so sentence ids agree), the server's alignment, the cover, and the audio either as
     * streamed URLs or as downloaded files. Blocking; call off the main thread.
     */
    fun add(context: Context, work: ServerClient.Work, download: Boolean, progress: (String) -> Unit = {}): Book {
        val state = ReadingState(context)
        val client = client(state) ?: error("Not connected to a server")
        val id = bookId(work.id)
        val dir = File(context.filesDir, "library/$id").apply { mkdirs() }
        val detail = client.detail(work.id)
        require(detail.work.hasAudio || (detail.work.hasEbook && work.ebookFormat == "epub")) { "This book has neither an EPUB nor audio on the server" }
        val audioOnly = !(detail.work.hasEbook && work.ebookFormat == "epub")
        val epub = File(dir, "book.epub")
        val local = if (audioOnly) Book(id, work.title, work.author) else {
            progress("Fetching the ebook…")
            client.download("/api/works/${work.id}/ebook", epub)
            progress("Reading the ebook…")
            Ebooks.parse(epub, File(context.cacheDir, "server-$id"), id)
        }
        // The sentence boundaries must be the server's: its alignment indexes them, and the device's
        // sentence splitter can disagree with the server's by a few sentences, which would leave the
        // narration highlight a few sentences off. The page finds sentences by their text, so the
        // server's list works there directly.
        val remote = if (audioOnly) null else runCatching { client.passages(work.id) }.getOrNull()
        val parsed = if (remote != null && remote.first.isNotEmpty()) local.copy(passages = remote.first, toc = remote.second.ifEmpty { local.toc }) else local
        val cover = File(dir, "cover.jpg")
        if (work.cover) runCatching { client.download("/api/works/${work.id}/cover", cover) }
        val tracks = detail.tracks.mapIndexed { i, t ->
            val uri = if (download) {
                progress("Downloading audio ${i + 1} of ${detail.tracks.size}…")
                val file = File(dir, "audio/${"%03d".format(i)}-${t.name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)}")
                client.download("/api/works/${work.id}/audio/$i", file) { done, total -> if (total > 0) progress("Downloading audio ${i + 1} of ${detail.tracks.size} · ${100 * done / total}%") }
                file.toURI().toString()
            } else client.audioUrl(work.id, i)
            Track(uri, t.name, t.duration)
        }
        val book = parsed.copy(
            id = id, title = work.title.ifBlank { parsed.title }, author = work.author.ifBlank { parsed.author }, narrator = work.narrator,
            tracks = tracks, chapters = detail.chapters, cover = if (cover.isFile) cover.path else "", ebookUri = if (audioOnly) "" else epub.toURI().toString(), ebookFormat = if (audioOnly) "" else "epub",
        )
        if (!audioOnly && tracks.isNotEmpty()) progress("Fetching the sync…")
        // An ebook with no audiobook on the server has nothing to be matched to.
        val anchors = if (audioOnly || tracks.isEmpty()) emptyList() else client.alignment(work.id)
        Library(context).use { db ->
            db.save(book)
            db.invalidateAlignment(id)
            db.unitDone(id, "server", anchors, 0, book.duration)
            db.status(id, "Synced")
        }
        context.getSharedPreferences("alignment-versions", 0).edit().putInt(id, Alignment.VERSION).commit()
        state.setServerWork(id, work.id, download)
        // Added before the server finished matching text to audio: what it matches from here on is fetched as it comes (refreshSync).
        state.syncPending(id, !audioOnly && tracks.isNotEmpty() && detail.work.shelf != "ready")
        // Start where the server last saw you, if it has seen you.
        detail.position?.let { p -> state.position(id, p.passage, p.audio); state.markPushed(id, p.passage, p.audio, p.updated) }
        return book
    }

    /** Sends the local position of a server-linked book when it has changed since the last send. */
    /** Sends a speed chosen here. Blocking. */
    fun pushSpeed(context: Context, bookId: String) {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return
        val client = client(state) ?: return
        if (!state.speedUnsent(bookId)) return
        runCatching { client.putSpeed(workId, state.speed(bookId)) }.onSuccess { state.speedSent(bookId) }
    }

    /** Takes the book's speed from the server unless one chosen here is still unsent (then sends it). True when it changed. Blocking. */
    fun pullSpeed(context: Context, bookId: String): Boolean {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return false
        val client = client(state) ?: return false
        if (state.speedUnsent(bookId)) { pushSpeed(context, bookId); return false }
        val remote = runCatching { client.speed(workId) }.getOrNull() ?: return false
        if (remote == state.speed(bookId)) return false
        state.speed(bookId, remote, local = false)
        return true
    }

    /**
     * Sends this device's place. Another device moved the book since this one last heard: the place is still sent when
     * the two are close or the book is playing here; far apart, it is held, and the person is asked when the book is
     * next opened. `force` (the person chose this place) sends it regardless. Blocking.
     */
    fun push(context: Context, bookId: String, force: Boolean = false) {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return
        val client = client(state) ?: return
        val passage = state.passage(bookId); val audio = state.audio(bookId)
        if (!force && state.pushed(bookId) == "$passage:$audio") return
        val answer = if (force) runCatching { client.putPosition(workId, passage, audio) }.getOrNull()?.let { ServerClient.Saved(true, it) }
            else runCatching { client.putPosition(workId, passage, audio, state.serverUpdated(bookId)) }.getOrNull()
        answer ?: return
        if (answer.saved) { state.markPushed(bookId, passage, audio, answer.position.updated); return }
        val playingHere = PlaybackService.playingBook() == bookId && PlaybackService.playing()
        if (playingHere || !apart(passage, audio, answer.position)) push(context, bookId, force = true)
    }

    /** Bytes a book occupies on this device. */
    fun sizeOnDevice(context: Context, bookId: String): Long = File(context.filesDir, "library/$bookId").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Fetches a streamed server book's audio onto the device and points its tracks at the files. Blocking. */
    fun downloadAudio(context: Context, bookId: String, progress: (String) -> Unit = {}) {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: error("Not a server book")
        val client = client(state) ?: error("Not connected to a server")
        Library(context).use { db ->
            val book = db.load(bookId) ?: error("Book not found")
            val dir = File(context.filesDir, "library/$bookId")
            val fetched = ArrayList<File>()
            val tracks = try {
                book.tracks.mapIndexed { i, t ->
                    if (t.uri.startsWith("file:")) t else {
                        progress("Downloading audio ${i + 1} of ${book.tracks.size}…")
                        val file = File(dir, "audio/${"%03d".format(i)}-${t.name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)}")
                        fetched.add(file)
                        client.download("/api/works/$workId/audio/$i", file) { done, total -> if (total > 0) progress("Downloading audio ${i + 1} of ${book.tracks.size} · ${100 * done / total}%") }
                        Track(file.toURI().toString(), t.name, t.duration)
                    }
                }
            } catch (e: Throwable) {
                // The book still streams; the part-files of this attempt would only take space nobody can free.
                fetched.forEach { it.delete() }
                throw e
            }
            db.save(book.copy(tracks = tracks))
        }
        state.setServerWork(bookId, workId, true)
    }

    /** Drops a server book's downloaded audio and streams it again. */
    fun removeAudio(context: Context, bookId: String) {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return
        val client = client(state) ?: return
        Library(context).use { db ->
            val book = db.load(bookId) ?: return
            db.save(book.copy(tracks = book.tracks.mapIndexed { i, t -> Track(client.audioUrl(workId, i), t.name, t.duration) }))
        }
        File(context.filesDir, "library/$bookId/audio").deleteRecursively()
        state.setServerWork(bookId, workId, false)
    }

    /** One sender at a time for everything this device owes the server, so nothing is sent twice or trimmed mid-send. */
    private val owing = Any()

    /** A refusal that sending again cannot change (a mark on a sentence the book no longer has, say): the entry is dropped. */
    private fun refused(e: Throwable?) = Regex("Server answered (4\\d\\d)").find(e?.message.orEmpty())?.groupValues?.get(1)?.let { it !in setOf("401", "408", "429") } == true

    /**
     * Sends what this device owes the server: reading sessions, bookmark and highlight changes, shelf changes. Each
     * leaves its record only once the server has it, so changes made offline go up the next time this runs with a
     * connection (when the app comes forward or goes back, every minute while reading, and before marks are
     * compared). Blocking; safe to call often and from several threads.
     */
    fun settle(context: Context) {
        val state = ReadingState(context)
        val client = client(state) ?: return
        synchronized(owing) {
            flushSessions(context)
            for (entry in state.marksOwed()) {
                val part = entry.split('|'); if (part.size != 4) { state.markSettled(entry); continue }
                val workId = state.serverWork(part[0]); val passage = part[1].toIntOrNull()
                if (workId == null || passage == null) { state.markSettled(entry); continue }
                val sent = runCatching {
                    if (part[3] == "del") client.deleteMark(workId, part[2], passage)
                    else { val a = state.marks(part[0]); for (i in 0 until a.length()) { val m = a.getJSONObject(i); if (m.getInt("passage") == passage && m.getString("kind") == part[2]) client.putMark(workId, m) } }
                }
                if (sent.isSuccess || refused(sent.exceptionOrNull())) state.markSettled(entry) else break
            }
            for (id in state.serverBooks()) if (state.shelfOwed(id)) pushShelf(context, id)
        }
    }

    /**
     * Brings a book's marks into step with the server's. What this device still owes is sent first and is never
     * undone by the answer. Beyond that the server is followed: its marks are added here, a note changed elsewhere is
     * taken, and a mark removed elsewhere goes here too. The first comparison of a book treats marks only this device
     * has as not yet sent rather than as removed. Returns true if anything here changed.
     */
    fun pullMarks(context: Context, bookId: String): Boolean {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return false
        val client = client(state) ?: return false
        settle(context)
        val remote = runCatching { client.marks(workId) }.getOrNull() ?: return false
        val owed = state.marksOwed().mapNotNull { e -> e.split('|').takeIf { it.size == 4 && it[0] == bookId }?.let { (it[1].toIntOrNull() ?: -1) to it[2] } }.toSet()
        val local = state.marks(bookId)
        val mine = (0 until local.length()).map { local.getJSONObject(it) }.associateBy { it.getInt("passage") to it.getString("kind") }
        val theirs = (0 until remote.length()).map { remote.getJSONObject(it) }.associateBy { it.getInt("passage") to it.getString("kind") }
        var changed = false
        for ((key, m) in theirs) {
            if (key in owed) continue
            val here = mine[key]
            if (here == null || here.optString("note") != m.optString("note")) {
                state.setMark(bookId, key.first, key.second, m.optString("note"), m.optString("text").ifBlank { here?.optString("text").orEmpty() }, m.optLong("created").takeIf { it > 0 } ?: here?.optLong("created")?.takeIf { it > 0 } ?: System.currentTimeMillis())
                changed = true
            }
        }
        val seeded = state.marksSeeded(bookId)
        var owes = false
        for (key in mine.keys) {
            if (key in theirs || key in owed) continue
            if (seeded) { state.removeMark(bookId, key.first, key.second); changed = true }
            else { state.markOwed(bookId, key.first, key.second, false); owes = true }
        }
        if (!seeded) state.marksSeeded(bookId, true)
        if (owes) settle(context)
        return changed
    }

    /** Sends the queued sessions; on success exactly those leave the queue. Safe to call often. */
    fun flushSessions(context: Context) {
        val state = ReadingState(context)
        val client = client(state) ?: return
        synchronized(owing) {
            val pending = state.pendingSessions()
            if (pending.length() == 0) return
            runCatching { client.postSessions(pending) }.onSuccess { state.clearSessions(pending) }
        }
    }

    /** Mirrors one book's place on this person's shelf to the server: reading, queued, finished with its date, or gone. */
    fun pushShelf(context: Context, bookId: String) {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return
        val client = client(state) ?: return
        runCatching {
            when {
                state.finished(bookId) > 0 -> client.shelve(workId, "finished", state.finished(bookId))
                !state.onShelf(bookId) -> client.unshelve(workId)
                state.passage(bookId) > 0 || state.audio(bookId) > 0 -> client.shelve(workId, "reading", null)
                else -> client.shelve(workId, "queued", null)
            }
        }.onSuccess { state.shelfOwed(bookId, false) }
    }

    /**
     * Brings this device's shelf into step with the server's (a fresh answer of `GET /api/me/shelf`). A change made
     * here that has not reached the server yet is sent again. Everything else follows the server: a book taken off
     * the shelf, or marked unfinished, on another device stops being shelved or finished here too. Blocking.
     */
    fun reconcileShelf(context: Context, shelf: List<ServerClient.ShelfEntry>) {
        val state = ReadingState(context)
        val byWork = shelf.associateBy { it.work }
        val ids = state.serverBooks()
        // Once, on the first run that keeps this record: whatever is shelved or finished here counts as not yet
        // confirmed, so nothing a previous version failed to send is dropped.
        if (!state.shelfOwedSeeded) { for (id in ids) if (state.onShelf(id) || state.finished(id) > 0) state.shelfOwed(id, true); state.shelfOwedSeeded = true }
        // The answer in hand was fetched before these are sent, so it says nothing about them: they are left alone.
        val owed = ids.filter { state.shelfOwed(it) }.toSet()
        for (id in owed) pushShelf(context, id)
        for (id in ids) {
            if (id in owed) continue
            val entry = byWork[state.serverWork(id) ?: continue]
            if (entry == null && state.onShelf(id)) state.removeFromShelf(id)
            if (state.finished(id) > 0 && entry?.state != "finished") state.setFinished(id, 0)
            if (entry?.state == "finished" && state.finished(id) == 0L) state.setFinished(id, entry.finishedAt ?: entry.added)
        }
    }

    /**
     * For a book added while the server was still matching its text to its audio: takes what the server has matched
     * since (and its sentence list, if it had not read the ebook yet when the book was added), until the server says
     * it is done. Blocking. True when something about the book here changed.
     */
    fun refreshSync(context: Context, bookId: String): Boolean {
        val state = ReadingState(context)
        if (!state.syncPending(bookId)) return false
        val workId = state.serverWork(bookId) ?: return false
        val client = client(state) ?: return false
        // The work first, the alignment second: when the work says it is done, the alignment fetched after it is whole.
        val detail = runCatching { client.detail(workId) }.getOrNull() ?: return false
        val anchors = runCatching { client.alignment(workId) }.getOrNull() ?: return false
        var changed = false
        Library(context).use { db ->
            val book = db.load(bookId) ?: return false
            if (detail.work.passages > 0 && book.passages.size != detail.work.passages) {
                val remote = runCatching { client.passages(workId) }.getOrNull()
                if (remote != null && remote.first.size == detail.work.passages) { db.save(book.copy(passages = remote.first, toc = remote.second.ifEmpty { book.toc })); changed = true }
            }
            if (anchors.size != db.anchorCount(bookId)) {
                db.invalidateAlignment(bookId); db.unitDone(bookId, "server", anchors, 0, book.duration); db.status(bookId, "Synced"); changed = true
            }
        }
        if (detail.work.shelf == "ready") { state.syncPending(bookId, false); changed = true }
        return changed
    }

    /** Takes the server's position when another device moved it since this one last synced. Returns true if adopted. */
    /** A server book parsed on the device before the server's sentence list was used: takes the server's list. Returns true if it changed. */
    fun repairPassages(context: Context, bookId: String): Boolean {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return false
        val client = client(state) ?: return false
        val repaired = context.getSharedPreferences("server-repairs", 0)
        if (repaired.getBoolean(bookId, false)) return false
        val count = runCatching { client.detail(workId).work.passages }.getOrNull() ?: return false
        var changed = false
        Library(context).use { db ->
            val book = db.load(bookId) ?: return false
            if (count > 0 && book.passages.size != count) {
                val remote = runCatching { client.passages(workId) }.getOrNull() ?: return false
                if (remote.first.size == count) { db.save(book.copy(passages = remote.first, toc = remote.second.ifEmpty { book.toc })); changed = true }
            }
        }
        repaired.edit().putBoolean(bookId, true).apply()
        return changed
    }

    /**
     * What a pull found: nothing new; a newer place from another device, nothing having moved here (the caller takes
     * it while nothing is open on the old place); or two places apart for the person to choose between.
     */
    sealed class Pulled {
        object Nothing : Pulled()
        class Newer(val remote: ServerClient.Position) : Pulled()
        class Apart(val remote: ServerClient.Position) : Pulled()
    }

    /**
     * Compares the book's place with the server's newest from any device; changes nothing itself except sending this
     * device's place when that is the answer. Newer than this device's last exchange and nothing moved here since:
     * Newer. Both moved, close together: this device's is kept and sent. Both moved and far apart (another device ran
     * on while this one was offline, say): Apart, for the person to choose. A book playing here keeps its place. Blocking.
     */
    fun sync(context: Context, bookId: String): Pulled {
        val state = ReadingState(context)
        val workId = state.serverWork(bookId) ?: return Pulled.Nothing
        val client = client(state) ?: return Pulled.Nothing
        runCatching { repairPassages(context, bookId) }
        val remote = runCatching { client.position(workId) }.getOrNull() ?: return Pulled.Nothing
        if (remote.updated <= state.serverUpdated(bookId)) return Pulled.Nothing
        val passage = state.passage(bookId); val audio = state.audio(bookId)
        // Nothing sent yet (just added, or after a sign-in) and still at the start: nothing has moved here.
        val sent = state.pushed(bookId)
        val localChanged = if (sent.isEmpty()) passage > 0 || audio > 0 else sent != "$passage:$audio"
        if (!localChanged) return Pulled.Newer(remote)
        val playingHere = PlaybackService.playingBook() == bookId && PlaybackService.playing()
        if (playingHere || !apart(passage, audio, remote)) { push(context, bookId); return Pulled.Nothing }
        return Pulled.Apart(remote)
    }

    /** Far enough apart to ask: two minutes of audio, or thirty sentences (compared when both sides have a sentence, or either has no audio place). */
    fun apart(passage: Int, audio: Long, remote: ServerClient.Position): Boolean {
        val audioFar = audio > 0 && remote.audio > 0 && kotlin.math.abs(audio - remote.audio) > 120_000
        // Sentences are compared when both sides have one, or when there is no audio to go by.
        val textComparable = (passage > 0 && remote.passage > 0) || audio <= 0 || remote.audio <= 0
        return audioFar || (textComparable && kotlin.math.abs(passage - remote.passage) > 30)
    }

    /** The other device's place, chosen. */
    fun keepRemote(context: Context, bookId: String, remote: ServerClient.Position) {
        val state = ReadingState(context)
        // A place with audio but no sentence (listened to where the text was not open): the sentence narrated there.
        val passage = if (remote.passage > 0 || remote.audio <= 0) remote.passage
            else Library(context).use { db -> db.anchors(bookId).lastOrNull { it.start <= remote.audio }?.passage } ?: 0
        state.position(bookId, passage, remote.audio)
        state.markPushed(bookId, passage, remote.audio, remote.updated)
    }
}
