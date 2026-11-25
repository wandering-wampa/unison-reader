package dev.unison.server

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class FolderEbooksTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4)

    /** A tiny but complete EPUB: mimetype, container, an OPF with the given metadata lines and a cover item, one chapter. */
    private fun epub(file: File, metadata: String, cover: Boolean = true, manifestCover: String = """<item id="cover-img" href="images/cover.png" media-type="image/png"/>""") {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { z ->
            fun add(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            add("mimetype", "application/epub+zip".toByteArray())
            add("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            add("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">$metadata</metadata>
<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>${if (cover) manifestCover else ""}</manifest>
<spine><itemref idref="c1"/></spine></package>""".toByteArray())
            add("OEBPS/c1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>It was a quiet morning. Nothing happened.</p></body></html>""".toByteArray())
            if (cover) add("OEBPS/images/cover.png", png)
        }
    }

    private class Env(val root: File) {
        val ebooks = File(root, "ebooks").apply { mkdirs() }
        val audio = File(root, "audio").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val catalog = Catalog(File(data, "catalog.db"))
        val scanner = Scanner(Config(audio, ebooks, data, File(root, "model"), "", 0, 0), catalog)
        fun close() { catalog.connection.close(); root.deleteRecursively() }
    }

    private fun env(block: (Env) -> Unit) {
        val e = Env(Files.createTempDirectory("unison-folder-").toFile())
        try { block(e) } finally { e.close() }
    }

    @Test fun `title, authors, series, description and cover come from the EPUB`() = env { e ->
        epub(File(e.ebooks, "Some/Book One.epub"), """
            <dc:title>The First Door</dc:title><dc:creator>Ada Writer</dc:creator><dc:creator>Bo Second</dc:creator>
            <dc:publisher>Small Press</dc:publisher><dc:language>en</dc:language><dc:date>2019-05-03T00:00:00+00:00</dc:date>
            <dc:description>&lt;p&gt;A &lt;b&gt;quiet&lt;/b&gt; start.&lt;/p&gt;</dc:description>
            <meta name="calibre:series" content="The Doors"/><meta name="calibre:series_index" content="2.0"/>
            <meta name="cover" content="cover-img"/>""")
        val books = e.scanner.folderBooks()
        assertEquals(1, books.size)
        val b = books.single()
        assertEquals("The First Door", b.title)
        assertEquals("Ada Writer", b.author)
        assertEquals("epub", b.format)
        assertEquals(listOf("Ada Writer", "Bo Second"), b.meta.authors)
        assertEquals("The Doors", b.meta.series?.single()?.name)
        assertEquals(2.0, b.meta.series?.single()?.sequence)
        assertEquals("A quiet start.", b.meta.description)
        assertEquals("Small Press", b.meta.publisher)
        assertEquals("en", b.meta.language)
        assertEquals("2019", b.meta.publishedYear)
        val cover = assertNotNull(b.cover)
        assertContentEquals(png, cover.readBytes())
        assertTrue(cover.toPath().startsWith(e.data.toPath()), "the cover lives under the data folder")
        assertEquals(emptyList(), e.ebooks.walkTopDown().filter { it.isFile && !it.name.endsWith(".epub") }.toList(), "nothing was written beside the books")
    }

    @Test fun `EPUB 3 collections give the series and its position`() = env { e ->
        epub(File(e.ebooks, "x.epub"), """
            <dc:title>Third</dc:title><dc:creator>Cy</dc:creator>
            <meta property="belongs-to-collection" id="c01">Epic Run</meta>
            <meta refines="#c01" property="collection-type">series</meta>
            <meta refines="#c01" property="group-position">3</meta>
            <meta property="cover-image-unused">x</meta>""", manifestCover = """<item id="img" href="images/cover.png" media-type="image/png" properties="cover-image"/>""")
        val b = e.scanner.folderBooks().single()
        assertEquals("Epic Run", b.meta.series?.single()?.name)
        assertEquals(3.0, b.meta.series?.single()?.sequence)
        assertNotNull(b.cover)
    }

    @Test fun `without metadata the file name and the parent folder stand in`() = env { e ->
        epub(File(e.ebooks, "Jo Folder/A Plain Name.epub"), "", cover = false)
        epub(File(e.ebooks, "Loose Book.epub"), "<dc:title>  </dc:title>", cover = false)
        val byTitle = e.scanner.folderBooks().associateBy { it.title }
        assertEquals("Jo Folder", byTitle.getValue("A Plain Name").author)
        assertEquals("Unknown", byTitle.getValue("Loose Book").author)
        assertNull(byTitle.getValue("Loose Book").cover)
    }

    @Test fun `dot-files are skipped and an unchanged file is not opened again`() = env { e ->
        epub(File(e.ebooks, "A/one.epub"), "<dc:title>One</dc:title><dc:creator>Ann</dc:creator>")
        epub(File(e.ebooks, ".hidden/two.epub"), "<dc:title>Two</dc:title>")
        epub(File(e.ebooks, "A/._one.epub"), "<dc:title>Ghost</dc:title>")
        File(e.ebooks, "A/notes.txt").writeText("not a book")
        val first = e.scanner.folderBooks()
        assertEquals(listOf("One"), first.map { it.title })
        // The same entry object comes back while the stamp is the same: nothing was read again.
        assertSame(first.single(), e.scanner.folderBooks().single())
        val f = File(e.ebooks, "A/one.epub")
        epub(f, "<dc:title>One, Revised</dc:title><dc:creator>Ann</dc:creator>")
        f.setLastModified(f.lastModified() + 5000)
        assertEquals("One, Revised", e.scanner.folderBooks().single().title)
    }

    @Test fun `a file that is not an EPUB does not break the scan`() = env { e ->
        File(e.ebooks, "Pat/broken.epub").apply { parentFile.mkdirs() }.writeText("this is not a zip")
        File(e.ebooks, "empty.epub").createNewFile()
        epub(File(e.ebooks, "Fine/good.epub"), "<dc:title>Good One</dc:title><dc:creator>Gil</dc:creator>")
        e.scanner.scan()
        val works = e.catalog.works()
        assertEquals(setOf("Good One", "broken", "empty"), works.map { it.title }.toSet())
        assertTrue(works.all { it.hasEbook })
        val good = works.single { it.title == "Good One" }
        assertEquals("", good.status)
        assertTrue(good.passages > 0)
        assertEquals("Pat", works.single { it.title == "broken" }.author)
        assertTrue(works.single { it.title == "broken" }.status.startsWith("Ebook could not be read"))
        assertEquals(0, e.scanner.scan(), "a second pass changes nothing")
    }

    @Test fun `with a metadata db Calibre wins and loose EPUBs are not mixed in`() = env { e ->
        val dir = File(e.ebooks, "Cal Author/Calibre Book (1)")
        epub(File(dir, "Calibre Book - Cal Author.epub"), "<dc:title>Embedded Title</dc:title><dc:creator>Embedded Author</dc:creator>")
        epub(File(e.ebooks, "Stray/stray.epub"), "<dc:title>Stray</dc:title>")
        DriverManager.getConnection("jdbc:sqlite:${File(e.ebooks, "metadata.db").path}").use { db ->
            db.createStatement().use { s ->
                for (sql in listOf(
                    "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, path TEXT, has_cover INT, series_index REAL, pubdate TEXT)",
                    "CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_authors_link (book INT, author INT)",
                    "CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_series_link (book INT, series INT)",
                    "CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_tags_link (book INT, tag INT)",
                    "CREATE TABLE ratings (id INTEGER PRIMARY KEY, rating INT)", "CREATE TABLE books_ratings_link (book INT, rating INT)",
                    "CREATE TABLE comments (id INTEGER PRIMARY KEY, book INT, text TEXT)",
                    "CREATE TABLE publishers (id INTEGER PRIMARY KEY, name TEXT)", "CREATE TABLE books_publishers_link (book INT, publisher INT)",
                    "CREATE TABLE languages (id INTEGER PRIMARY KEY, lang_code TEXT)", "CREATE TABLE books_languages_link (book INT, lang_code INT)",
                    "CREATE TABLE data (id INTEGER PRIMARY KEY, book INT, format TEXT, name TEXT)",
                    "INSERT INTO books VALUES (1, 'Calibre Title', 'Cal Author/Calibre Book (1)', 0, 1.0, '2001-01-01')",
                    "INSERT INTO authors VALUES (1, 'Cal Author')", "INSERT INTO books_authors_link VALUES (1, 1)",
                    "INSERT INTO data VALUES (1, 1, 'EPUB', 'Calibre Book - Cal Author')",
                )) s.execute(sql)
            }
        }
        e.scanner.scan()
        val works = e.catalog.works()
        assertEquals(listOf("Calibre Title"), works.map { it.title })
        assertEquals("Cal Author", works.single().author)
        assertFalse(File(e.data, "ebook-covers").exists(), "Calibre mode never writes folder-mode covers")
    }
}
