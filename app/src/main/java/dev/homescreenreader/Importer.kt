package dev.homescreenreader

import android.content.*
import android.media.*
import android.net.Uri
import android.provider.*
import androidx.documentfile.provider.DocumentFile
import java.io.*
import java.security.MessageDigest
import java.util.*
import java.util.zip.ZipFile
import org.json.*

object Importer {
    /** Recover only our journaled destinations. Sources are never deleted during rollback. */
    fun recover(context: Context) {
        Library(context).use { db ->
            File(context.filesDir, "staging")
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .forEach { dir ->
                    val journal = File(dir, "journal.json")
                    if (journal.exists())
                        runCatching {
                            val j = JSONObject(journal.readText())
                            if (db.duplicate(j.getString("hash")) == null) {
                                val a = j.getJSONArray("owned")
                                for (i in 0 until a.length()) {
                                    val uri = Uri.parse(a.getString(i))
                                    if (uri.scheme == "file") {
                                        val f = File(uri.path!!)
                                        require(
                                            f.canonicalPath.startsWith(
                                                File(context.filesDir, "library").canonicalPath +
                                                    File.separator
                                            )
                                        )
                                        f.delete()
                                    } else
                                        DocumentsContract.deleteDocument(
                                            context.contentResolver,
                                            uri,
                                        )
                                }
                            }
                        }
                    dir.deleteRecursively()
                }
        }
    }

    data class Result(val book: Book, val message: String)

    fun name(context: Context, uri: Uri): String =
        if (uri.scheme == "file") File(uri.path!!).name
        else
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "download"

    fun digest(file: File): String = file.inputStream().use { digest(it) }

    private fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val b = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            md.update(b, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun clean(context: Context, uri: Uri): String =
        try {
            val deleted =
                if (uri.scheme == "file") File(uri.path!!).delete()
                else DocumentsContract.deleteDocument(context.contentResolver, uri)
            if (deleted) "removed"
            else "Imported safely; provider refused deletion. Remove the original manually."
        } catch (e: Exception) {
            "Imported safely; Android denied source cleanup. Remove the original in Files. ${e.message.orEmpty()}"
        }

    @Synchronized
    fun import(
        context: Context,
        uri: Uri,
        attach: String? = null,
        progress: (String) -> Unit = {},
    ): Result {
        recover(context)
        val db = Library(context)
        val filename = name(context, uri)
        val ext = filename.substringAfterLast('.').lowercase()
        require(ext in listOf("epub", "mobi", "azw3", "zip", "m4b", "mp3", "m4a")) {
            "Choose EPUB, MOBI, AZW3, M4B, MP3, M4A or an audiobook ZIP"
        }
        val stage = File(context.filesDir, "staging/${UUID.randomUUID()}").apply { mkdirs() }
        val owned = mutableListOf<Uri>()
        var committed = false
        try {
            progress("Copying $filename…")
            val source = File(stage, "source.$ext")
            val incomingDigest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                source.outputStream().use { out ->
                    val b = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        total += n
                        require(total <= 16L * 1024 * 1024 * 1024) { "Import exceeds 16 GB" }
                        incomingDigest.update(b, 0, n)
                        out.write(b, 0, n)
                    }
                    out.fd.sync()
                }
            }
            val hash = digest(source)
            require(hash == incomingDigest.digest().joinToString("") { "%02x".format(it) }) {
                "Copy verification failed; original kept"
            }
            fun journal() {
                val file = File(stage, "journal.json")
                val temp = File(stage, "journal.tmp")
                temp.writeText(
                    JSONObject()
                        .put("hash", hash)
                        .put("owned", JSONArray(owned.map { it.toString() }))
                        .toString()
                )
                check(temp.renameTo(file))
            }
            db.duplicate(hash)?.let { existing ->
                return Result(
                    db.load(existing) ?: error("Missing duplicate record"),
                    "Already in library. Selected source was kept.",
                )
            }
            val ebook = ext in listOf("epub", "mobi", "azw3")
            val id = UUID.randomUUID().toString()
            val parsedDir = File(stage, "parsed").apply { mkdirs() }
            progress("Checking contents…")
            var book =
                if (ebook) Ebooks.parse(source, parsedDir, id)
                else Book(id, filename.substringBeforeLast('.'), "")
            var audioFiles = listOf<File>()
            var chapters = listOf<Chapter>()
            if (ext == "zip") {
                ZipFile(source).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    require(entries.size <= 10000) { "Too many archive entries" }
                    var expanded = 0L
                    for (entry in entries.filter { !it.isDirectory }) {
                        require(
                            !entry.name.startsWith('/') &&
                                !entry.name.contains('\\') &&
                                entry.name.split('/').none { it == ".." }
                        ) {
                            "Unsafe archive path"
                        }
                        val leaf = entry.name.substringAfterLast('/')
                        val type = leaf.substringAfterLast('.').lowercase()
                        if (type !in listOf("m4b", "m4a", "mp3", "json", "jpg", "png")) continue
                        val dest = File(parsedDir, entry.name)
                        require(
                            dest.canonicalPath.startsWith(parsedDir.canonicalPath + File.separator)
                        )
                        dest.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { out ->
                                val b = ByteArray(1024 * 1024)
                                while (true) {
                                    val n = input.read(b)
                                    if (n < 0) break
                                    expanded += n
                                    require(expanded <= 16L * 1024 * 1024 * 1024) {
                                        "Expanded audio exceeds 16 GB"
                                    }
                                    out.write(b, 0, n)
                                }
                            }
                        }
                        if (type in listOf("m4b", "m4a", "mp3")) audioFiles = audioFiles + dest
                        if (leaf == "metadata.json") {
                            require(dest.length() < 2 * 1024 * 1024) { "Metadata too large" }
                            val j = JSONObject(dest.readText())
                            book =
                                book.copy(
                                    title = j.optString("title", book.title),
                                    author =
                                        j.optJSONArray("authors")
                                            ?.let { a ->
                                                List(a.length()) { a.getString(it) }.joinToString()
                                            }
                                            .orEmpty(),
                                    narrator =
                                        j.optJSONArray("narrators")
                                            ?.let { a ->
                                                List(a.length()) { a.getString(it) }.joinToString()
                                            }
                                            .orEmpty(),
                                )
                            j.optJSONArray("chapters")?.let { a ->
                                chapters =
                                    List(a.length()) {
                                        val c = a.getJSONObject(it)
                                        Chapter(
                                            c.optString("title", "Chapter ${it+1}"),
                                            (c.getDouble("start") * 1000).toLong(),
                                        )
                                    }
                            }
                        }
                        if (leaf.lowercase() in listOf("cover.jpg", "cover.png"))
                            book = book.copy(cover = dest.path)
                    }
                }
                require(audioFiles.isNotEmpty()) { "Archive contains no supported audio" }
            } else if (!ebook) audioFiles = listOf(source)
            fun key(s: String) = s.lowercase().replace(Regex("\\d+")) { it.value.padStart(12, '0') }
            audioFiles = audioFiles.sortedBy { key(it.relativeTo(stage).path) }
            val tracks =
                audioFiles.map { f ->
                    val m = MediaMetadataRetriever()
                    try {
                        m.setDataSource(f.path)
                        val duration =
                            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0
                        require(duration > 0) { "Unreadable audio: ${f.name}" }
                        if (book.author.isBlank())
                            book =
                                book.copy(
                                    author =
                                        m.extractMetadata(
                                                MediaMetadataRetriever.METADATA_KEY_ARTIST
                                            )
                                            .orEmpty()
                                )
                        if (ext != "zip")
                            book =
                                book.copy(
                                    title =
                                        m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                            ?: m.extractMetadata(
                                                MediaMetadataRetriever.METADATA_KEY_TITLE
                                            )
                                            ?: book.title
                                )
                        if (book.cover.isBlank())
                            m.embeddedPicture?.let { bytes ->
                                book =
                                    book.copy(
                                        cover =
                                            File(parsedDir, "audio-cover.jpg")
                                                .apply { writeBytes(bytes) }
                                                .path
                                    )
                            }
                        Track(f.path, if (f == source) filename else f.name, duration)
                    } finally {
                        m.release()
                    }
                }
            if (chapters.isEmpty()) {
                var base = 0L
                chapters =
                    tracks.flatMap { t ->
                        val embedded = AudioChapters.read(File(t.uri))
                        val list =
                            if (embedded.isEmpty()) listOf(Chapter(t.name, base))
                            else embedded.map { it.copy(start = it.start + base) }
                        base += t.duration
                        list
                    }
            }
            val existing =
                attach?.let { db.load(it) }
                    ?: db.books().firstOrNull { other ->
                        normalize(other.title) == normalize(book.title) &&
                            ((ebook && other.passages.isEmpty()) ||
                                (!ebook && other.tracks.isEmpty()))
                    }
            if (existing != null) {
                require(
                    if (ebook) existing.passages.isEmpty()
                    else existing.tracks.isEmpty() || ext == "mp3" && attach != null
                ) {
                    "This book already has this format. Create a separate entry or attach to a book without it."
                }
                book =
                    book.copy(
                        id = existing.id,
                        title = existing.title,
                        author = existing.author.ifBlank { book.author },
                        narrator = book.narrator.ifBlank { existing.narrator },
                        passages = if (ebook) book.passages else existing.passages,
                        toc = if (ebook) book.toc else existing.toc,
                        tracks = if (ebook) existing.tracks else tracks,
                        chapters = if (ebook) existing.chapters else chapters,
                        cover = book.cover.ifBlank { existing.cover },
                    )
            } else book = book.copy(tracks = tracks, chapters = chapters)
            val finalDir = File(context.filesDir, "library/${book.id}").apply { mkdirs() }
            val tree = ReadingState(context).tree
            fun store(file: File, display: String): String {
                progress("Verifying $display…")
                if (tree.isBlank()) {
                    val target = File(finalDir, "${UUID.randomUUID()}-$display")
                    owned += Uri.fromFile(target)
                    journal()
                    check(file.renameTo(target)) { "Unable to move into library" }
                    return target.toURI().toString()
                }
                val root =
                    DocumentFile.fromTreeUri(context, Uri.parse(tree))
                        ?: error("Library folder is unavailable")
                val dest =
                    root.createFile("application/octet-stream", "${book.id}-$display")
                        ?: error("Cannot write to selected library folder")
                owned += dest.uri
                journal()
                val expected = digest(file)
                context.contentResolver.openOutputStream(dest.uri, "w")!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                val actual = context.contentResolver.openInputStream(dest.uri)!!.use { digest(it) }
                require(actual == expected) { "Library copy verification failed; original kept" }
                return dest.uri.toString()
            }
            if (ebook) book = book.copy(ebookUri = store(source, filename), ebookFormat = ext)
            else {
                val stored =
                    tracks.mapIndexed { i, t -> t.copy(uri = store(audioFiles[i], t.name)) }
                val previous = existing?.tracks.orEmpty()
                val offset = previous.sumOf { it.duration }
                book =
                    book.copy(
                        tracks = previous + stored,
                        chapters =
                            existing?.chapters.orEmpty() +
                                chapters.map { it.copy(start = it.start + offset) },
                        ebookUri = existing?.ebookUri.orEmpty(),
                        ebookFormat = existing?.ebookFormat ?: "epub",
                    )
            }
            if (book.cover.startsWith(stage.path)) {
                val c = File(finalDir, "cover.jpg")
                File(book.cover).copyTo(c, true)
                book = book.copy(cover = c.path)
            }
            db.writableDatabase.beginTransaction()
            try {
                db.save(book)
                if (existing != null) db.invalidateAlignment(book.id)
                db.recordImport(hash, book.id, uri.toString(), "Pending source cleanup", ext)
                db.writableDatabase.setTransactionSuccessful()
            } finally {
                db.writableDatabase.endTransaction()
            }
            committed = true
            val moveResult =
                if (ext != "zip" && tree.isNotBlank())
                    finishWithProviderMove(context, db, uri, tree, hash, book, ebook)
                else null
            if (moveResult != null) book = moveResult.first
            val cleanup = moveResult?.second ?: clean(context, uri)
            db.cleanupResult(uri.toString(), cleanup)
            ReadingState(context).active = book.id
            return Result(
                book,
                if (cleanup == "removed") "Imported and verified. Original removed." else cleanup,
            )
        } finally {
            if (!committed)
                owned.forEach { u ->
                    runCatching {
                        if (u.scheme == "file") File(u.path!!).delete()
                        else DocumentsContract.deleteDocument(context.contentResolver, u)
                    }
                }
            stage.deleteRecursively()
            db.close()
        }
    }

    /**
     * Keep the verified committed copy until the provider move and catalog replacement succeed.
     * Ordinary single-document grants cannot identify an authorized parent; those use copy/delete.
     */
    private fun finishWithProviderMove(
        c: Context,
        db: Library,
        source: Uri,
        tree: String,
        hash: String,
        book: Book,
        ebook: Boolean,
    ): Pair<Book, String>? {
        val target = DocumentFile.fromTreeUri(c, Uri.parse(tree)) ?: return null
        if (source.authority != target.uri.authority || !DocumentsContract.isDocumentUri(c, source))
            return null
        val flags =
            c.contentResolver
                .query(source, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
        if (flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE == 0) return null
        val sourceId = DocumentsContract.getDocumentId(source)
        val parent =
            c.contentResolver.persistedUriPermissions
                .filter {
                    it.isWritePermission &&
                        it.uri.authority == source.authority &&
                        DocumentsContract.isTreeUri(it.uri)
                }
                .firstNotNullOfOrNull { grant ->
                    runCatching {
                            val accessible =
                                DocumentsContract.buildDocumentUriUsingTree(grant.uri, sourceId)
                            val path =
                                DocumentsContract.findDocumentPath(c.contentResolver, accessible)
                                    ?.path ?: return@runCatching null
                            if (path.size < 2) null
                            else
                                DocumentsContract.buildDocumentUriUsingTree(
                                    grant.uri,
                                    path[path.lastIndex - 1],
                                )
                        }
                        .getOrNull()
                } ?: return null
        if (DocumentsContract.getDocumentId(parent) == DocumentsContract.getDocumentId(target.uri))
            return null
        db.cleanupResult(
            source.toString(),
            "Provider move pending; if interrupted, review the selected library folder for ${name(c,source)}. The verified library copy is safe.",
        )
        val moved =
            runCatching {
                    DocumentsContract.moveDocument(c.contentResolver, source, parent, target.uri)
                }
                .getOrNull() ?: return null
        var resolved = book
        return try {
            require(c.contentResolver.openInputStream(moved)!!.use { digest(it) } == hash) {
                "Moved file verification failed"
            }
            val old = if (ebook) book.ebookUri else book.tracks.last().uri
            val changed =
                if (ebook) book.copy(ebookUri = moved.toString())
                else
                    book.copy(
                        tracks =
                            book.tracks.dropLast(1) +
                                book.tracks.last().copy(uri = moved.toString())
                    )
            db.save(changed)
            resolved = changed
            val removed = DocumentsContract.deleteDocument(c.contentResolver, Uri.parse(old))
            changed to
                if (removed) "removed"
                else
                    "Original moved successfully; provider refused removal of the temporary verified copy: $old"
        } catch (e: Exception) {
            resolved to
                "Original moved to $moved. Verified library copy retained; review both files before cleanup: ${e.message}"
        }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}

object AudioChapters {
    fun read(file: File): List<Chapter> =
        runCatching {
                val result = mutableListOf<Chapter>()
                RandomAccessFile(file, "r").use { f ->
                    fun atoms(end: Long, depth: Int) {
                        if (depth > 8) return
                        while (f.filePointer + 8 <= end) {
                            val begin = f.filePointer
                            var size = f.readInt().toLong() and 0xffffffffL
                            val tag =
                                ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                            if (size == 1L) size = f.readLong()
                            if (size == 0L) size = end - begin
                            if (size < 8 || begin + size > end) return
                            if (tag in listOf("moov", "udta")) atoms(begin + size, depth + 1)
                            if (tag == "chpl" && size < 1024 * 1024) {
                                f.skipBytes(8)
                                val count = f.readUnsignedByte()
                                repeat(count) {
                                    val start = f.readLong() / 10000
                                    val n = f.readUnsignedByte()
                                    val title =
                                        ByteArray(n)
                                            .also { f.readFully(it) }
                                            .toString(Charsets.UTF_8)
                                    result += Chapter(title, start)
                                }
                            }
                            f.seek(begin + size)
                        }
                    }
                    atoms(f.length(), 0)
                }
                if (result.isEmpty()) quickTime(file) else result
            }
            .getOrDefault(emptyList())

    private data class Atom(val type: String, val body: Long, val end: Long)

    private fun quickTime(file: File): List<Chapter> =
        RandomAccessFile(file, "r").use { f ->
            fun atoms(start: Long, end: Long): List<Atom> {
                val result = mutableListOf<Atom>()
                var pos = start
                while (pos + 8 <= end) {
                    f.seek(pos)
                    var size = f.readInt().toLong() and 0xffffffffL
                    val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                    if (size == 1L) size = f.readLong()
                    if (size == 0L) size = end - pos
                    if (size < 8 || pos + size > end) break
                    result += Atom(type, f.filePointer, pos + size)
                    pos += size
                }
                return result
            }
            fun children(a: Atom) = atoms(a.body, a.end)
            fun child(a: Atom, type: String) = children(a).firstOrNull { it.type == type }
            fun uint() = f.readInt().toLong() and 0xffffffffL
            val moov =
                atoms(0, f.length()).firstOrNull { it.type == "moov" } ?: return@use emptyList()
            val traks = children(moov).filter { it.type == "trak" }
            val references = mutableSetOf<Long>()
            for (trak in traks) {
                val ref = child(trak, "tref")?.let { child(it, "chap") } ?: continue
                f.seek(ref.body)
                while (f.filePointer + 4 <= ref.end) references += uint()
            }
            for (trak in traks) {
                val tkhd = child(trak, "tkhd") ?: continue
                f.seek(tkhd.body)
                val version = f.readUnsignedByte()
                f.skipBytes(if (version == 1) 19 else 11)
                val id = uint()
                if (id !in references) continue
                val mdia = child(trak, "mdia") ?: continue
                val mdhd = child(mdia, "mdhd") ?: continue
                f.seek(mdhd.body)
                val mdVersion = f.readUnsignedByte()
                f.skipBytes(if (mdVersion == 1) 19 else 11)
                val scale = uint()
                if (scale == 0L) continue
                val stbl = child(mdia, "minf")?.let { child(it, "stbl") } ?: continue
                val sizes = child(stbl, "stsz") ?: continue
                f.seek(sizes.body + 4)
                val fixed = uint()
                val count = uint().toInt()
                require(count in 1..100000) { "Invalid chapter table" }
                val lengths = IntArray(count) { if (fixed != 0L) fixed.toInt() else uint().toInt() }
                val times = child(stbl, "stts") ?: continue
                f.seek(times.body + 4)
                val runs = uint().toInt()
                require(runs in 1..100000)
                val starts = LongArray(count)
                var sample = 0
                var time = 0L
                repeat(runs) {
                    val n = uint().toInt()
                    val duration = uint()
                    require(n in 0..count)
                    repeat(n) {
                        if (sample < count) starts[sample++] = time * 1000 / scale
                        time += duration
                    }
                }
                val offsets = child(stbl, "stco") ?: child(stbl, "co64") ?: continue
                f.seek(offsets.body + 4)
                val chunks = uint().toInt()
                require(chunks in 1..100000)
                val positions =
                    LongArray(chunks) { if (offsets.type == "co64") f.readLong() else uint() }
                val stsc = child(stbl, "stsc") ?: continue
                f.seek(stsc.body + 4)
                val scCount = uint().toInt()
                require(scCount in 1..100000)
                val sc =
                    List(scCount) {
                        val first = uint().toInt()
                        val per = uint().toInt()
                        uint()
                        first to per
                    }
                val chapters = mutableListOf<Chapter>()
                sample = 0
                for (chunk in positions.indices) {
                    var pos = positions[chunk]
                    val per = sc.lastOrNull { it.first <= chunk + 1 }?.second ?: 0
                    require(per in 0..count)
                    repeat(per) {
                        if (sample < count) {
                            val size = lengths[sample]
                            require(size in 2..100000 && pos >= 0 && pos + size <= f.length())
                            f.seek(pos)
                            val n = f.readUnsignedShort()
                            require(n <= size - 2)
                            val title =
                                ByteArray(n).also { f.readFully(it) }.toString(Charsets.UTF_8)
                            chapters += Chapter(title, starts[sample])
                            pos += size
                            sample++
                        }
                    }
                }
                if (chapters.isNotEmpty()) return@use chapters
            }
            emptyList()
        }
}
