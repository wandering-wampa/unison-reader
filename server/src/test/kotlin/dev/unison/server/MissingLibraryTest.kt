package dev.unison.server

import dev.homescreenreader.Anchor
import dev.homescreenreader.Track
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** An unreadable library changes nothing; a work whose files vanish is hidden for 30 days, then deleted. */
class MissingLibraryTest {
    private class Env(val root: File) {
        var now = 1_000_000_000L
        val ebooks = File(root, "ebooks").apply { mkdirs() }
        val audio = File(root, "audio").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val catalog = Catalog(File(data, "catalog.db"))
        val scanner = Scanner(Config(audio, ebooks, data, File(root, "model"), "", 0, 0), catalog) { now }
        fun close() { catalog.connection.close(); root.deleteRecursively() }
    }

    private fun env(block: (Env) -> Unit) {
        val e = Env(Files.createTempDirectory("unison-missing-").toFile())
        try { block(e) } finally { e.close() }
    }

    private fun epub(file: File, title: String, author: String) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { z ->
            fun add(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            add("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""")
            add("OEBPS/c1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>It was a quiet morning. Nothing happened.</p></body></html>""")
        }
    }

    /** Two ebook-only works, each with an anchor and a saved position; returns their ids by title. */
    private fun twoWorks(e: Env): Map<String, String> {
        epub(File(e.ebooks, "one.epub"), "Alpha Book", "Ann Author")
        epub(File(e.ebooks, "two.epub"), "Beta Book", "Ben Author")
        e.scanner.scan()
        val ids = e.catalog.works().associate { it.title to it.id }
        assertEquals(setOf("Alpha Book", "Beta Book"), ids.keys)
        for (id in ids.values) {
            e.catalog.unitDone(id, "u", listOf(Anchor(0, 0, 1000, 0.9), Anchor(1, 1000, 2000, 0.9)), 5)
            e.catalog.setPosition(1, id, "device", Position(1, 1500))
        }
        return ids
    }

    private fun assertKept(e: Env, ids: List<String>) {
        for (id in ids) {
            assertEquals(2, e.catalog.anchorCount(id), "anchors of $id")
            assertNotNull(e.catalog.position(1, id, "device"), "position of $id")
        }
    }

    @Test fun `an emptied or missing ebook folder changes nothing`() = env { e ->
        val ids = twoWorks(e)
        File(e.ebooks, "one.epub").delete(); File(e.ebooks, "two.epub").delete()
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("ebook"), e.scanner.unreachable)
        assertEquals(ids.values.toSet(), e.catalog.works().map { it.id }.toSet())
        assertKept(e, ids.values.toList())
        e.ebooks.deleteRecursively()
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("ebook"), e.scanner.unreachable)
        assertEquals(listOf("The ebook folder could not be read at the last check. Nothing was removed."), e.scanner.unreachableNotes())
        assertKept(e, ids.values.toList())
        assertTrue(e.catalog.allIds().all { e.catalog.missingSince(it) == null })
    }

    @Test fun `an emptied or missing audiobook folder changes nothing`() = env { e ->
        val ids = twoWorks(e)
        val audioId = Scanner.sha("seed")
        e.catalog.upsertWork(audioId, "Heard", "Hal", Scanner.titleKey("Heard"), Scanner.authorKey("Hal"))
        e.catalog.setAudio(audioId, File(e.audio, "Hal/Heard").path, 7, "", listOf(Track("a.mp3", "a", 1000)), emptyList(), null)
        e.catalog.unitDone(audioId, "u", listOf(Anchor(0, 0, 1000, 0.9), Anchor(1, 1000, 2000, 0.9)), 5)
        e.catalog.setPosition(1, audioId, "device", Position(1, 1500))
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("audiobook"), e.scanner.unreachable)
        e.audio.deleteRecursively()
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("audiobook"), e.scanner.unreachable)
        assertEquals(3, e.catalog.works().size)
        assertKept(e, ids.values.toList() + audioId)
    }

    @Test fun `a work whose files vanish is hidden but keeps its data, and returns under the same id`() = env { e ->
        val ids = twoWorks(e)
        val beta = ids.getValue("Beta Book")
        val aside = File(e.root, "aside.epub")
        assertTrue(File(e.ebooks, "two.epub").renameTo(aside))
        assertEquals(1, e.scanner.scan())
        assertEquals(emptyList(), e.scanner.unreachable)
        assertEquals(listOf("Alpha Book"), e.catalog.works().map { it.title })
        assertNull(e.catalog.work(beta))
        assertEquals(emptyList(), e.catalog.search("Beta"))
        assertEquals(2, e.catalog.anchorCount(beta))
        assertNotNull(e.catalog.position(1, beta, "device"))
        // Still absent on the next pass: the clock of the grace period does not restart.
        e.now += 1000
        assertEquals(0, e.scanner.scan())
        assertEquals(1_000_000_000L, e.catalog.missingSince(beta))
        // The files come back.
        assertTrue(aside.renameTo(File(e.ebooks, "two.epub")))
        e.scanner.scan()
        assertEquals(setOf("Alpha Book", "Beta Book"), e.catalog.works().map { it.title }.toSet())
        assertEquals(beta, e.catalog.work(beta)?.id)
        assertNull(e.catalog.missingSince(beta))
        assertKept(e, ids.values.toList())
    }

    @Test fun `a work still missing after thirty days is deleted`() = env { e ->
        val ids = twoWorks(e)
        val beta = ids.getValue("Beta Book")
        File(e.ebooks, "two.epub").delete()
        e.scanner.scan()
        e.now += Scanner.MISSING_GRACE_MS - 1
        e.scanner.scan()
        assertNotNull(e.catalog.workIncludingMissing(beta))
        e.now += 2
        assertEquals(1, e.scanner.scan())
        assertNull(e.catalog.workIncludingMissing(beta))
        assertEquals(0, e.catalog.anchorCount(beta))
        assertNull(e.catalog.position(1, beta, "device"))
        assertKept(e, listOf(ids.getValue("Alpha Book")))
    }

    @Test fun `a genuinely empty library on a fresh catalog scans normally`() = env { e ->
        assertEquals(0, e.scanner.scan())
        assertEquals(emptyList(), e.scanner.unreachable)
        assertTrue(e.scanner.scanned)
        epub(File(e.ebooks, "one.epub"), "Alpha Book", "Ann Author")
        assertEquals(1, e.scanner.scan())
        assertEquals(1, e.catalog.works().size)
    }

    @Test fun `an empty library accepted by an admin lets the pass hide its works, and the guard returns when it lists again`() = env { e ->
        val ids = twoWorks(e)
        File(e.ebooks, "one.epub").delete(); File(e.ebooks, "two.epub").delete()
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("ebook"), e.scanner.emptied)
        assertEquals(emptyList(), e.scanner.unreadable)
        assertEquals(listOf("The ebook folder is empty, but the server knows ebooks from it. Nothing was removed."), e.scanner.unreachableNotes())
        assertTrue(e.scanner.acceptEmpty("ebook"))
        assertEquals(2, e.scanner.scan())
        assertEquals(emptyList(), e.scanner.unreachable)
        assertEquals(emptyList(), e.catalog.works())
        assertKept(e, ids.values.toList())
        assertTrue(ids.values.all { e.catalog.workIncludingMissing(it) != null })
        // Something is listed again: the acceptance ends, and emptying the folder again is guarded as before.
        epub(File(e.ebooks, "one.epub"), "Alpha Book", "Ann Author")
        e.scanner.scan()
        assertFalse(e.catalog.acceptedEmpty("ebook"))
        assertEquals(ids.getValue("Alpha Book"), e.catalog.works().single().id)
        File(e.ebooks, "one.epub").delete()
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("ebook"), e.scanner.emptied)
        assertEquals(1, e.catalog.works().size)
    }

    private fun twenty(e: Env): List<File> {
        val files = (1..20).map { File(e.ebooks, "book$it.epub").also { f -> epub(f, "Title Number $it", "Author $it") } }
        e.scanner.scan()
        assertEquals(20, e.catalog.works().size)
        return files
    }

    @Test fun `a listing that lacks many works hides nothing until an admin accepts it`() = env { e ->
        val files = twenty(e)
        val aside = files.take(10).map { f -> File(e.root, f.name).also { assertTrue(f.renameTo(it)) } }
        assertEquals(0, e.scanner.scan())
        assertEquals(20, e.catalog.works().size)
        assertTrue(e.catalog.allIds().all { e.catalog.missingSince(it) == null })
        assertEquals(listOf("10 of the 20 books the server knows were not found at the last check. Nothing was hidden."), e.scanner.unreachableNotes())
        assertEquals(emptyList(), e.scanner.unreachable)
        // Still held on the next pass, and an acceptance only exists once an admin gives it.
        assertEquals(0, e.scanner.scan())
        assertFalse(e.catalog.acceptedEmpty(Scanner.PARTIAL))
        // The books come back: everything is untouched and the note goes.
        aside.forEach { assertTrue(it.renameTo(File(e.ebooks, it.name))) }
        e.scanner.scan()
        assertEquals(20, e.catalog.works().size)
        assertEquals(emptyList(), e.scanner.unreachableNotes())
        assertFalse(e.scanner.acceptEmpty(Scanner.PARTIAL))
    }

    @Test fun `an accepted partial listing goes ahead once, and the guard returns`() = env { e ->
        val files = twenty(e)
        files.take(10).forEach { assertTrue(it.delete()) }
        e.scanner.scan()
        assertTrue(e.scanner.acceptEmpty(Scanner.PARTIAL))
        assertEquals(10, e.scanner.scan())
        assertEquals(10, e.catalog.works().size)
        assertEquals(emptyList(), e.scanner.unreachableNotes())
        assertFalse(e.catalog.acceptedEmpty(Scanner.PARTIAL))
        // The acceptance was used up: losing a large share again is held back again.
        files.drop(10).take(6).forEach { assertTrue(it.delete()) }
        assertEquals(0, e.scanner.scan())
        assertEquals(10, e.catalog.works().size)
        assertEquals(1, e.scanner.unreachableNotes().size)
    }

    @Test fun `a small removal still goes through`() = env { e ->
        val files = twenty(e)
        assertTrue(files[0].delete())
        assertEquals(1, e.scanner.scan())
        assertEquals(19, e.catalog.works().size)
        assertEquals(emptyList(), e.scanner.unreachableNotes())
        // Four of nineteen is under the five-work floor too.
        files.drop(1).take(4).forEach { assertTrue(it.delete()) }
        assertEquals(4, e.scanner.scan())
        assertEquals(15, e.catalog.works().size)
    }

    @Test fun `a Calibre database that cannot be copied counts as an unreadable ebook folder`() = env { e ->
        val ids = twoWorks(e)
        File(e.ebooks, "metadata.db").writeText("not a database")
        // A folder where the copy belongs makes the copy fail.
        File(e.data, "calibre-metadata.db").apply { mkdirs(); File(this, "in the way").writeText("x") }
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("ebook"), e.scanner.unreadable)
        assertEquals(listOf("The ebook folder could not be read at the last check. Nothing was removed."), e.scanner.unreachableNotes())
        assertEquals(2, e.catalog.works().size)
        assertTrue(e.catalog.allIds().all { e.catalog.missingSince(it) == null })
        assertKept(e, ids.values.toList())
    }

    @Test fun `an unreadable library cannot be accepted`() = env { e ->
        twoWorks(e)
        e.ebooks.deleteRecursively()
        assertEquals(0, e.scanner.scan())
        assertEquals(listOf("ebook"), e.scanner.unreadable)
        assertFalse(e.scanner.acceptEmpty("ebook"))
        assertFalse(e.scanner.acceptEmpty("audiobook"))
        assertFalse(e.catalog.acceptedEmpty("ebook"))
        assertEquals(0, e.scanner.scan())
        assertEquals(2, e.catalog.works().size)
    }
}
