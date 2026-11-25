package dev.homescreenreader

import java.io.File
import java.net.URI
import java.text.BreakIterator
import java.util.*
import java.util.zip.ZipFile
import org.json.*
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object Ebooks {
    /** Unpacks a MOBI/AZW3 file into HTML parts (the app installs libmobi here); null where MOBI is unsupported. */
    @Volatile var mobiUnpacker: ((path: String, directory: String) -> String)? = null
    /** Bumped when the parser learns to read files it refused before, so recorded failures are tried again. */
    const val VERSION = 3
    /** The largest ebook accepted, packed and unpacked. The phone keeps the default; the server raises it. */
    @Volatile var maxBytes: Long = 100L * 1024 * 1024

    fun parse(file: File, directory: File, id: String): Book {
        require(file.length() <= maxBytes) { "Ebook exceeds ${maxBytes shr 20} MB" }
        directory.mkdirs()
        if (file.extension.lowercase() in listOf("mobi", "azw3")) {
            val unpack = mobiUnpacker ?: error("MOBI and AZW3 are not supported here")
            val parts = unpack(file.path, directory.path).trim().lines()
            return fromHtml(
                id,
                parts.first(),
                parts.getOrElse(1) { "" },
                parts.drop(2).map { it to File(directory, it).readText() },
            )
        }
        ZipFile(file).use { zip ->
            var total = 0L
            fun read(path: String): ByteArray {
                require(!path.startsWith('/') && !path.split('/').contains("..")) {
                    "Unsafe EPUB path"
                }
                val entry = zip.getEntry(path) ?: error("Missing EPUB resource: $path")
                return zip.getInputStream(entry).use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= maxBytes) { "Expanded EPUB exceeds limit" }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
            fun xml(path: String): org.jsoup.nodes.Document {
                val text = read(path).toString(Charsets.UTF_8)
                require(
                    !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)
                ) {
                    "Unsafe XML declarations"
                }
                return Jsoup.parse(text, "", org.jsoup.parser.Parser.xmlParser())
            }
            // EPUB XML often carries namespace prefixes ("opf:item", "enc:EncryptionMethod"); match on the local name.
            fun org.jsoup.nodes.Element.named(name: String): List<org.jsoup.nodes.Element> =
                allElements.filter { it.tagName().substringAfterLast(':').equals(name, ignoreCase = true) }
            fun org.jsoup.nodes.Element.childrenNamed(parent: String, name: String): List<org.jsoup.nodes.Element> =
                named(parent).flatMap { p -> p.children().filter { it.tagName().substringAfterLast(':').equals(name, ignoreCase = true) } }
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                val methods = xml("META-INF/encryption.xml").named("EncryptionMethod")
                require(
                    methods.isNotEmpty() &&
                        methods.all {
                            it.attr("Algorithm") in
                                setOf(
                                    "http://www.idpf.org/2008/embedding",
                                    "http://ns.adobe.com/pdf/enc#RC",
                                )
                        }
                ) {
                    "DRM-encrypted EPUBs are unsupported"
                }
            }
            val opfPath =
                xml("META-INF/container.xml").named("rootfile").firstOrNull()?.attr("full-path")
                    ?: error("Invalid EPUB container")
            val opf = xml(opfPath)
            // Manifest hrefs are often written with raw spaces, brackets and apostrophes; quote them before resolving.
            fun uri(s: String): URI = try { URI(s) } catch (e: java.net.URISyntaxException) { URI(null, null, s, null) }
            fun resolve(href: String): String {
                val u = uri(opfPath).resolve(uri(href)).normalize()
                require(!u.isAbsolute && u.authority == null) { "Unsafe EPUB path" }
                return u.path ?: error("Unsafe EPUB path")
            }
            val items = opf.childrenNamed("manifest", "item").associateBy { it.attr("id") }
            val parts =
                opf.childrenNamed("spine", "itemref").map { ref ->
                    val item = items[ref.attr("idref")] ?: error("Missing EPUB spine item")
                    val path = resolve(item.attr("href"))
                    path to
                        if (ref.attr("linear") == "no") "" else read(path).toString(Charsets.UTF_8)
                }
            val coverId = opf.named("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            val cover =
                items.values.firstOrNull {
                    it.attr("id") == coverId || it.attr("properties").contains("cover-image")
                }
            val coverFile =
                cover
                    ?.let {
                        File(directory, "cover")
                            .apply { writeBytes(read(resolve(it.attr("href")))) }
                            .path
                    }
                    .orEmpty()
            return fromHtml(
                    id,
                    opf.getElementsByTag("dc:title")
                        .text()
                        .ifBlank { opf.getElementsByTag("title").text() }
                        .ifBlank { file.nameWithoutExtension },
                    opf.getElementsByTag("dc:creator").text(),
                    parts,
                )
                .copy(cover = coverFile)
        }
    }

    private fun fromHtml(
        id: String,
        title: String,
        author: String,
        parts: List<Pair<String, String>>,
    ): Book {
        val passages = mutableListOf<Passage>()
        val toc = mutableListOf<Pair<String, Int>>()
        var blockIndex = 0
        parts.forEachIndexed { section, (_, source) ->
            if (source.isBlank()) return@forEachIndexed
            // Kobo-style XHTML closes <script/> in place; the HTML parser would read the rest of the file as script.
            val doc = Jsoup.parse(source.replace(Regex("<(script|style)\\b[^>]*?/>", RegexOption.IGNORE_CASE), ""))
            doc.select("script,style,iframe,object,form").remove()
            val heading =
                doc.selectFirst("h1,h2,h3")?.text().orEmpty().ifBlank { "Part ${section+1}" }
            toc += heading to passages.size
            var offset = 0
            val blocks =
                doc.body().select("p,h1,h2,h3,h4,li,blockquote,pre,div").filter {
                    it.select("p,h1,h2,h3,h4,li,blockquote,pre,div").size == 1
                }
            val actual = blocks.ifEmpty { listOf(doc.body()) }
            for (block in actual) {
                val text = block.text().replace('\u00a0', ' ').trim()
                if (text.isBlank()) continue
                val iterator = BreakIterator.getSentenceInstance(Locale.US)
                iterator.setText(text)
                var start = iterator.first()
                var end = iterator.next()
                val sentences = mutableListOf<String>()
                while (end != BreakIterator.DONE) {
                    text
                        .substring(start, end)
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { sentences += it }
                    start = end
                    end = iterator.next()
                }
                // Keep inline publisher emphasis on blocks; sentence locations are immutable text
                // offsets.
                val html = Jsoup.clean(block.html(), Safelist.simpleText().addTags("br"))
                if (sentences.size <= 1) passages += Passage(text, html, section, offset, blockIndex)
                else
                    sentences.forEach { sentence ->
                        passages +=
                            Passage(
                                sentence,
                                org.jsoup.nodes.Entities.escape(sentence),
                                section,
                                offset + text.indexOf(sentence),
                                blockIndex,
                            )
                    }
                blockIndex++
                offset += text.length + 1
            }
        }
        require(passages.isNotEmpty()) { "No readable ebook text" }
        return Book(id, title, author, passages = passages, toc = toc)
    }
}
