package dev.unison.server

import dev.homescreenreader.Book
import dev.homescreenreader.Chapter
import dev.homescreenreader.Ebooks
import dev.homescreenreader.Passage
import dev.homescreenreader.Track
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.slf4j.LoggerFactory

/**
 * Reads the two libraries and keeps the catalog in step with them. Both roots are read-only to the
 * server (the Docker mounts enforce it): nothing is ever written beside the audiobooks or inside
 * the Calibre library, and Calibre's metadata.db is copied aside before it is opened so SQLite never
 * creates journal files there.
 */
class Scanner(private val config: Config, private val catalog: Catalog) {
    private val log = LoggerFactory.getLogger("Scanner")
    private val audioExtensions = setOf("m4b", "m4a", "mp3", "flac", "ogg", "opus", "aac", "wav")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    class StoredBook(val passages: List<StoredPassage>, val toc: List<StoredToc>)
    @Serializable class StoredPassage(val text: String, val html: String, val section: Int, val offset: Int, val block: Int)
    @Serializable class StoredToc(val title: String, val passage: Int)

    class EbookEntry(val title: String, val author: String, val path: File, val format: String, val cover: File?, val meta: Meta)
    class AudioEntry(val title: String, val author: String, val dir: File)

    /** One pass over both libraries. Returns how many works changed. */
    private val sidecarSeen = HashMap<String, Long>()
    /** Set on the first scan of a process so metadata is (re)read for every work once, then only on change. */
    private var metaRefresh = true
    private var calibreStamp = -1L

    @Synchronized
    fun scan(): Int {
        var changed = 0
        val ebooks = calibreBooks()
        val audio = audiobooks()
        val seen = HashSet<String>()
        for (e in ebooks) {
            val id = workFor(e.title, e.author, seen)
            val stamp = e.path.lastModified() xor e.path.length()
            // An EPUB that never yielded sentences is tried again unless this very parser version already failed on it.
            val known = catalog.work(id)
            val failedHere = "Ebook could not be read (parser ${Ebooks.VERSION})"
            val retry = known != null && known.passages == 0 && e.format == "epub" && !known.status.startsWith(failedHere)
            if (catalog.stamps(id)?.first != stamp || retry) {
                val parsed = if (e.format == "epub") parsePassages(id, e.path) else Result.failure(IllegalStateException("${e.format.uppercase()} is not supported"))
                catalog.setEbook(id, e.path.path, e.format, stamp, e.cover?.path, parsed.getOrDefault(0))
                parsed.exceptionOrNull()?.let { catalog.setStatus(id, "$failedHere: ${it.message}") }
                if (parsed.isSuccess && known?.status?.startsWith("Ebook could not be read") == true) catalog.setStatus(id, "")
                changed++
            }
            if (calibreStamp != catalog.stamps(id)?.first || metaRefresh) catalog.setMeta(id, 3, e.meta)
        }
        val pairings = pairingPlan(ebooks, audio)
        for (a in audio) {
            if (a.dir.path in pairings.duplicates) continue
            val authorK = authorKey(a.author)
            val paired = pairings.matches[a.dir.path]
            val id = if (paired != null) workFor(paired.title, paired.author, seen)
                else workForKeys(a.title, a.author, titleCandidates(a.title, authorK).first(), authorK, seen)
            val files = audioFiles(a.dir)
            val stamp = files.fold(0L) { acc, f -> acc * 31 + f.lastModified() + f.length() }
            if (catalog.stamps(id)?.second != stamp) {
                val probed = probe(files)
                val cover = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png").map { File(a.dir, it) }.firstOrNull { it.isFile }
                catalog.setAudio(id, a.dir.path, stamp, probed.narrator, probed.tracks, probed.chapters, cover?.path)
                catalog.setMeta(id, 1, probed.meta)
                catalog.setMeta(id, 0, folderMeta(a.title))
                changed++
            }
            // Audiobookshelf's sidecar, when it keeps one beside the files, is the best audio-side source.
            val sidecar = File(a.dir, "metadata.json")
            if (sidecar.isFile && (sidecar.lastModified() != sidecarSeen[id] || metaRefresh)) {
                runCatching { catalog.setMeta(id, 2, sidecarMeta(sidecar)) }.onFailure { log.warn("metadata.json in ${a.dir.name}: ${it.message}") }
                sidecarSeen[id] = sidecar.lastModified()
            }
        }
        // Works whose files are gone leave the catalog (their sync data goes with them).
        for (id in catalog.allIds()) if (id !in seen) { catalog.removeWork(id); changed++ }
        metaRefresh = false
        return changed
    }

    /** Plan before mutating: existing pairs and admin choices win; new pairs must be unique both ways. */
    internal data class PairingPlan(val matches: Map<String, EbookEntry>, val duplicates: Set<String>)

    internal fun pairingPlan(ebooks: List<EbookEntry>, audio: List<AudioEntry>): PairingPlan {
        val existing = catalog.works().filter { it.hasAudio }.associateBy { it.audioDir }
        val byPath = ebooks.associateBy { it.path.path }
        val chosen = LinkedHashMap<String, EbookEntry>()
        val blocked = HashSet<String>()
        for (a in audio) {
            val author = authorKey(a.author)
            val override = catalog.pairingOverride(titleKey(a.title), author)
            val prior = existing[a.dir.path]
            val target = override ?: prior?.ebookPath
            if (target != null) byPath[target]?.let { chosen[a.dir.path] = it }
            if (override != null || catalog.audioOnly(titleCandidates(a.title, author).first(), author) || prior?.audioOnly == true)
                blocked += a.dir.path
        }
        val reserved = chosen.values.map { it.path.path }.toSet()
        val proposed = LinkedHashMap<String, EbookEntry>()
        val duplicates = HashSet<String>()
        for (a in audio) {
            if (a.dir.path in chosen || a.dir.path in blocked) continue
            val folder = BookIdentity.Book(a.title, listOf(a.author), folderMeta(a.title).series.orEmpty())
            val sideFile = File(a.dir, "metadata.json")
            val side = if (sideFile.isFile) runCatching {
                val json = JSONObject(sideFile.readText())
                val meta = sidecarMeta(sideFile)
                BookIdentity.Book(json.optString("title"), meta.authors.orEmpty().ifEmpty { listOf(a.author) }, meta.series.orEmpty())
            }.getOrNull() else null
            val hits = ebooks.filter { e ->
                val book = BookIdentity.Book(e.title, e.meta.authors.orEmpty().ifEmpty { listOf(e.author) }, e.meta.series.orEmpty())
                BookIdentity.sameBook(folder, book) || (side != null && BookIdentity.sameBook(side, book))
            }
            val hit = hits.singleOrNull() ?: continue
            if (hit.path.path !in reserved) proposed[a.dir.path] = hit
            // A second untracked copy must neither steal the chosen narration nor appear as a
            // missing ebook. Already catalogued copies keep their IDs and any personal state.
            else if (a.dir.path !in existing) duplicates += a.dir.path
        }
        val contenders = proposed.values.groupingBy { it.path.path }.eachCount()
        chosen.putAll(proposed.filterValues { contenders[it.path.path] == 1 })
        return PairingPlan(chosen, duplicates)
    }

    /** Audiobookshelf's metadata.json: title, subtitle, authors, narrators, series as "Name #3", genres, tags, description, year, publisher. */
    private fun sidecarMeta(file: File): Meta {
        val m = JSONObject(file.readText())
        fun list(key: String): List<String>? = m.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } }
        val authors = list("authors")
        // Audiobookshelf sometimes records the author as a series (a folder read as Author/Series/Book); that is no series.
        val series = list("series")?.map { s -> Regex("^(.*?)\\s*#\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$").find(s)?.let { SeriesRef(it.groupValues[1].trim(), it.groupValues[2].toDouble()) } ?: SeriesRef(s.trim(), null) }
            ?.filter { sr -> authors?.none { it.equals(sr.name, ignoreCase = true) } ?: true }
        return Meta(
            subtitle = m.optString("subtitle").takeIf { it != "null" }, description = m.optString("description").takeIf { it != "null" },
            publishedYear = m.optString("publishedYear").takeIf { it != "null" }, publisher = m.optString("publisher").takeIf { it != "null" },
            language = m.optString("language").takeIf { it != "null" }, genres = ((list("genres") ?: emptyList()) + (list("tags") ?: emptyList())).filter { it != "Audiobook" }.ifEmpty { null },
            series = series, authors = authors, narrators = list("narrators"),
        )
    }

    /** What a folder name alone says: "Title - Series 3", "Series #2 - Title", "Book 2 - Title" (series unknown, sequence kept for later). */
    private fun folderMeta(folder: String): Meta {
        val segments = folder.split(Regex("\\s+-\\s+")).map { it.trim() }
        for (seg in segments.drop(1)) {
            Regex("^(.*?)\\s*(?:#|,?\\s*book)?\\s*([0-9]+(?:\\.[0-9]+)?)$", RegexOption.IGNORE_CASE).find(seg)?.let { m -> if (m.groupValues[1].isNotBlank()) return Meta(series = listOf(SeriesRef(m.groupValues[1].trim(), m.groupValues[2].toDouble()))) }
        }
        Regex("^(.*?)\\s*#\\s*([0-9]+(?:\\.[0-9]+)?)$").find(segments.first())?.let { m -> if (m.groupValues[1].isNotBlank()) return Meta(series = listOf(SeriesRef(m.groupValues[1].trim(), m.groupValues[2].toDouble()))) }
        return Meta()
    }

    /** The work for a title and author, created if new; a pairing override can redirect an audiobook to another ebook's keys. */
    private fun workFor(title: String, author: String, seen: MutableSet<String>): String =
        workForKeys(title, author, titleKey(title), authorKey(author), seen)

    private fun workForKeys(title: String, author: String, titleKey: String, authorKey: String, seen: MutableSet<String>): String {
        val id = sha("$titleKey|$authorKey")
        catalog.upsertWork(id, title.trim(), author.trim(), titleKey, authorKey)
        seen += id
        return id
    }

    private fun parsePassages(id: String, epub: File): Result<Int> {
        val dir = File(config.data, "works/$id").apply { mkdirs() }
        return try {
            val book = Ebooks.parse(epub, File(dir, "parse"), id)
            val stored = StoredBook(book.passages.map { StoredPassage(it.text, it.html, it.section, it.offset, it.block) }, book.toc.map { StoredToc(it.first, it.second) })
            File(dir, "book.json").writeText(json.encodeToString(stored))
            Result.success(book.passages.size)
        } catch (e: Exception) {
            log.warn("Could not parse ${epub.name}: ${e.message}")
            Result.failure(e)
        } finally {
            File(dir, "parse").deleteRecursively()
        }
    }

    /** The parsed sentences of a work, as the aligner and the app see them. */
    fun storedBook(id: String): StoredBook? {
        val file = File(config.data, "works/$id/book.json")
        return if (file.isFile) json.decodeFromString<StoredBook>(file.readText()) else null
    }

    /** A Book for the aligner: the parsed sentences with the catalog's tracks and chapters. */
    fun bookFor(work: Work): Book? {
        val stored = storedBook(work.id) ?: return null
        return Book(
            id = work.id, title = work.title, author = work.author, narrator = work.narrator,
            passages = stored.passages.map { Passage(it.text, it.html, it.section, it.offset, it.block) },
            toc = stored.toc.map { it.title to it.passage },
            tracks = catalog.tracks(work.id).map { Track(it.path, it.name, it.duration) },
            chapters = catalog.chapters(work.id).map { Chapter(it.title, it.start) },
        )
    }

    // ---- Calibre

    private fun calibreBooks(): List<EbookEntry> {
        val source = File(config.ebooks, "metadata.db")
        if (!source.isFile) { log.warn("No metadata.db in ${config.ebooks}; the ebook library is not a Calibre library"); return emptyList() }
        val copy = File(config.data, "calibre-metadata.db")
        Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val out = ArrayList<EbookEntry>()
        calibreStamp = source.lastModified() xor source.length()
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            db.createStatement().use { s ->
                s.executeQuery(
                    """SELECT b.id, b.title, b.path, b.has_cover,
                       (SELECT group_concat(a.name, '|') FROM books_authors_link l JOIN authors a ON a.id = l.author WHERE l.book = b.id) AS authors,
                       (SELECT s.name FROM books_series_link l JOIN series s ON s.id = l.series WHERE l.book = b.id) AS series, b.series_index,
                       (SELECT group_concat(t.name, '|') FROM books_tags_link l JOIN tags t ON t.id = l.tag WHERE l.book = b.id) AS tags,
                       (SELECT r.rating FROM books_ratings_link l JOIN ratings r ON r.id = l.rating WHERE l.book = b.id) AS rating,
                       (SELECT c.text FROM comments c WHERE c.book = b.id) AS description,
                       (SELECT group_concat(p.name, ', ') FROM books_publishers_link l JOIN publishers p ON p.id = l.publisher WHERE l.book = b.id) AS publisher,
                       (SELECT group_concat(la.lang_code, ', ') FROM books_languages_link l JOIN languages la ON la.id = l.lang_code WHERE l.book = b.id) AS language,
                       b.pubdate
                       FROM books b""",
                ).use { r ->
                    while (r.next()) {
                        val bookId = r.getInt(1); val title = r.getString(2); val path = r.getString(3); val hasCover = r.getInt(4) != 0
                        val authorList = r.getString(5)?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                        val author = authorList.firstOrNull() ?: "Unknown"
                        val meta = Meta(
                            description = r.getString(10)?.let { html -> org.jsoup.Jsoup.parse(html).text() },
                            publishedYear = r.getString(13)?.take(4)?.takeIf { it.all(Char::isDigit) && it != "0101" },
                            publisher = r.getString(11), language = r.getString(12),
                            rating = r.getObject(9)?.let { (it as Number).toDouble() / 2 },
                            genres = r.getString(8)?.split('|')?.map { it.trim() }?.filter { it.isNotBlank() },
                            series = r.getString(6)?.let { listOf(SeriesRef(it, r.getObject(7)?.let { v -> (v as Number).toDouble() })) },
                            authors = authorList,
                        )
                        val dir = File(config.ebooks, path)
                        val formats = db.prepareStatement("SELECT format, name FROM data WHERE book = ?").use { p ->
                            p.setInt(1, bookId)
                            p.executeQuery().use { f -> buildList { while (f.next()) add(f.getString(1).lowercase() to f.getString(2)) } }
                        }
                        val chosen = formats.firstOrNull { it.first == "epub" } ?: formats.firstOrNull { it.first in setOf("azw3", "mobi") } ?: continue
                        val file = File(dir, "${chosen.second}.${chosen.first}")
                        if (!file.isFile) continue
                        out += EbookEntry(title, author, file, chosen.first, File(dir, "cover.jpg").takeIf { hasCover && it.isFile }, meta)
                    }
                }
            }
        }
        return out
    }

    // ---- Audiobooks

    private fun audiobooks(): List<AudioEntry> {
        val out = ArrayList<AudioEntry>()
        val authors = config.audiobooks.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: return out
        for (authorDir in authors) {
            val titles = authorDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: continue
            for (titleDir in titles) if (audioFiles(titleDir).isNotEmpty()) out += AudioEntry(titleDir.name, authorDir.name, titleDir)
            // Loose files directly under an author (a single-file book without its own folder).
            if (audioFiles(authorDir).isNotEmpty()) out += AudioEntry(authorDir.name, authorDir.name, authorDir)
        }
        return out
    }

    private fun audioFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in audioExtensions && !it.name.startsWith(".") }?.sortedWith(naturalOrder) ?: emptyList()

    class Probed(val narrator: String, val tracks: List<Track>, val chapters: List<Chapter>, val meta: Meta)

    /** Durations, tags and chapters through ffprobe; a multi-file book without embedded chapters gets one chapter per file. */
    private fun probe(files: List<File>): Probed {
        val tracks = ArrayList<Track>()
        val chapters = ArrayList<Chapter>()
        var narrator = ""
        var offset = 0L
        var meta = Meta()
        for (file in files) {
            val info = ffprobe(file)
            val format = info.optJSONObject("format") ?: JSONObject()
            val duration = (format.optDouble("duration", 0.0) * 1000).toLong()
            val tags = format.optJSONObject("tags") ?: JSONObject()
            if (narrator.isBlank()) narrator = tags.optString("composer", tags.optString("narrator", tags.optString("NARRATOR", "")))
            if (tracks.isEmpty()) {
                fun tag(vararg keys: String) = keys.firstNotNullOfOrNull { k -> tags.optString(k).takeIf { it.isNotBlank() } }
                val seriesTag = tag("series", "SERIES", "mvnm", "MVNM")
                val seq = tag("series-part", "SERIES-PART", "mvin", "MVIN")?.substringBefore('/')?.toDoubleOrNull()
                meta = Meta(
                    description = tag("comment", "description", "COMMENT"), publishedYear = tag("date", "year", "DATE")?.take(4),
                    publisher = tag("publisher", "PUBLISHER"), genres = tag("genre", "GENRE")?.split(';', '/', ',')?.map { it.trim() }?.filter { it.isNotBlank() && it != "Audiobook" },
                    series = seriesTag?.let { listOf(SeriesRef(it, seq)) },
                    authors = tag("artist", "album_artist", "ARTIST")?.split(';', '/')?.map { it.trim() }?.filter { it.isNotBlank() },
                    narrators = tag("composer", "narrator", "NARRATEDBY")?.split(';', '/', ',')?.map { it.trim() }?.filter { it.isNotBlank() },
                )
            }
            tracks += Track(file.path, cleanName(file.nameWithoutExtension), duration)
            val embedded = info.optJSONArray("chapters")
            if (embedded != null && embedded.length() > 0) {
                for (i in 0 until embedded.length()) {
                    val c = embedded.getJSONObject(i)
                    val start = (c.optDouble("start_time", 0.0) * 1000).toLong()
                    val title = c.optJSONObject("tags")?.optString("title", "")?.ifBlank { null } ?: "Chapter ${chapters.size + 1}"
                    chapters += Chapter(title, offset + start)
                }
            } else if (files.size > 1) {
                chapters += Chapter(cleanName(file.nameWithoutExtension), offset)
            }
            offset += duration
        }
        return Probed(narrator, tracks, chapters, meta)
    }

    private fun ffprobe(file: File): JSONObject {
        val process = ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_chapters", file.path)
            .redirectErrorStream(false).start()
        val text = process.inputStream.bufferedReader().readText()
        process.errorStream.close()
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("ffprobe timed out on ${file.name}") }
        return JSONObject(text.ifBlank { "{}" })
    }

    companion object {
        /** Natural order: "Chapter 2" before "Chapter 10". */
        val naturalOrder = Comparator<File> { a, b -> compareNatural(a.name, b.name) }

        fun compareNatural(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]; val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0')) else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size.compareTo(rb.size)
        }

        /** "1| Part One - Blood Sports 1" → "Part One - Blood Sports 1". */
        fun cleanName(name: String): String = name.replace(Regex("^\\s*\\d+\\s*[|._-]\\s*"), "").trim().ifBlank { name }

        /**
         * The key two titles must share to be the same work: lower case, bracketed and "read by" parts
         * dropped, a leading year dropped, only the first " - " segment kept (series suffixes go),
         * leading articles removed, then letters and digits only. "1974 - Carrie (read by Sissy Spacek)"
         * and "Carrie" agree; "11-22-63" and "11/22/63" agree.
         */
        fun titleKey(title: String): String {
            var t = Normalizer.normalize(title, Normalizer.Form.NFKD).lowercase()
            t = t.replace(Regex("[\\[(].*?[\\])]"), " ")
            t = t.replace(Regex("\\bread by\\b.*"), " ")
            t = t.replace(Regex("^\\s*(19|20)\\d\\d\\s*-\\s*"), "")
            t = t.split(" - ").first()
            t = t.replace(Regex("^(the|a|an)\\s+"), "")
            return t.replace(Regex("[^a-z0-9]"), "")
        }

        /**
         * Every " - " segment of a folder name as a title key, in order, skipping series markers
         * ("Book 1", "#2", "Vol 3", a bare number) and the author's own name; the whole name last.
         */
        fun titleCandidates(title: String, authorKey: String): List<String> {
            val segments = title.split(Regex("\\s+-\\s+")).map { titleKey(it) }.filter { it.isNotBlank() }
            val marker = Regex("^(book|bk|vol|volume|part|no|number)?\\d+$")
            val good = segments.filter { !marker.matches(it) && it != authorKey }
            // Last, the same without the words that describe the edition rather than the book ("… abridged",
            // "… (Unabridged)", "… Audiobook"): they only ever help find an ebook, never name a work (the first stays first).
            val plain = withoutEdition(title).split(Regex("\\s+-\\s+")).map { titleKey(it) }.filter { it.isNotBlank() && !marker.matches(it) && it != authorKey }
            return (good + segments + listOf(titleKey(title)) + plain).distinct().ifEmpty { listOf(titleKey(title)) }
        }

        private val EDITION = Regex("\\b(unabridged|abridged|audiobook|audio book|dramati[sz]ed|full cast)\\b", RegexOption.IGNORE_CASE)
        fun withoutEdition(title: String): String = title.replace(EDITION, " ").replace(Regex("\\s+"), " ").trim()

        fun authorKey(author: String): String {
            val first = author.split("&", ",", ";").first()
            return Normalizer.normalize(first, Normalizer.Form.NFKD).lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        fun sha(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
