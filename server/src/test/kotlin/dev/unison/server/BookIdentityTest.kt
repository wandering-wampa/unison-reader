package dev.unison.server

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BookIdentityTest {
    private fun library(block: (Catalog, Lists) -> Unit) {
        val dir = Files.createTempDirectory("unison-identity-").toFile()
        val catalog = Catalog(File(dir, "catalog.db"))
        try {
            catalog.connection.createStatement().use { it.execute("CREATE TABLE users (id INTEGER PRIMARY KEY)") }
            block(catalog, Lists(catalog.connection))
        } finally { catalog.connection.close(); dir.deleteRecursively() }
    }

    @Test fun `settlement uses the catalog identity behind a prefixed display title`() = library { catalog, lists ->
        val key = Scanner.titleKey("Leviathan Wakes")
        val author = "James S. A. Corey"
        val id = Scanner.sha("$key|${Scanner.authorKey(author)}")
        catalog.upsertWork(id, "Book 1 - Leviathan Wakes", author, key, Scanner.authorKey(author))
        catalog.setEbook(id, "/ebooks/book.epub", "epub", 1, null, 1)
        val request = lists.addRequest(null, "Reader", "Leviathan Wakes", author, "", "ebook", "web")!!
        assertEquals(listOf(request.id), lists.settle(catalog.works()).map { it.id })
        assertEquals(id, lists.request(request.id, -1)!!.work)
        assertTrue(lists.settle(catalog.works()).isEmpty())
    }

    @Test fun `same surname is not the same author`() = library { catalog, lists ->
        catalog.upsertWork("wrong", "Dune", "Brian Herbert", "dune", "brianherbert")
        catalog.setEbook("wrong", "/ebooks/wrong.epub", "epub", 1, null, 1)
        val request = lists.addRequest(null, "Reader", "Dune", "Frank Herbert", "", "ebook", "web")!!
        assertTrue(lists.settle(catalog.works()).isEmpty())
        assertEquals("wanted", lists.request(request.id, -1)!!.status)
    }

    @Test fun `settlement requires the requested medium`() = library { catalog, lists ->
        catalog.upsertWork("one", "Dune", "Frank Herbert", "dune", "frankherbert")
        catalog.setEbook("one", "/ebooks/book.epub", "epub", 1, null, 1)
        val request = lists.addRequest(null, "Reader", "Dune", "Frank Herbert", "", "both", "web")!!
        assertTrue(lists.settle(catalog.works()).isEmpty())
        assertEquals("wanted", lists.request(request.id, -1)!!.status)
    }

    @Test fun `asking again for a wanted book widens the kind and adds the new note`() = library { _, lists ->
        var changes = 0
        lists.onRequestsChanged = { changes++ }
        val first = lists.addRequest(null, "Reader", "Dune", "Frank Herbert", "", "ebook", "web")!!
        assertEquals(1, changes)
        // Nothing new: nothing changes and nobody is told.
        assertEquals(first.id, lists.addRequest(null, "Other", "dune", "Frank Herbert", "", "ebook", "web")!!.id)
        assertEquals(1, changes)
        val widened = lists.addRequest(null, "Other", "Dune", "frank herbert", "the 2021 edition", "audiobook", "web")!!
        assertEquals(first.id, widened.id)
        assertEquals("both", widened.kind)
        assertEquals("the 2021 edition", widened.note)
        assertEquals(2, changes)
        // A note already contained is not added again; a different one goes on a new line; both stays both.
        assertEquals("the 2021 edition", lists.addRequest(null, "Other", "Dune", "Frank Herbert", "2021 edition", "both", "web")!!.note)
        assertEquals(2, changes)
        assertEquals("the 2021 edition\nnarrated by a cast", lists.addRequest(null, "Other", "Dune", "Frank Herbert", " narrated by a cast ", "ebook", "web")!!.note)
        assertEquals("both", lists.request(first.id, -1)!!.kind)
        assertEquals(3, changes)
        assertEquals(1, lists.requests(-1).size)
    }

    @Test fun `a note that would not fit is not added to a wanted book`() = library { _, lists ->
        val long = "x".repeat(450)
        val first = lists.addRequest(null, "Reader", "Dune", "", long, "ebook", "web")!!
        val again = lists.addRequest(null, "Other", "Dune", "", "y".repeat(100), "ebook", "web")!!
        assertEquals(first.id, again.id)
        assertEquals(long, again.note)
    }

    private fun scanner(catalog: Catalog) = Scanner(Config(File("audio"), File("ebooks"), File("data"), File("model"), "", 0, 0), catalog)
    private fun ebook(title: String, author: String, series: List<SeriesRef> = emptyList(), suffix: String = "") =
        Scanner.EbookEntry(title, author, File("/fixture/ebooks/$title$suffix.epub"), "epub", null, Meta(authors = listOf(author), series = series))
    private fun audio(title: String, author: String, suffix: String = "") =
        Scanner.AudioEntry(title, author, File("/fixture/audio/$author/$title$suffix"))

    @Test fun `scanner pairs the reported title variants without manual overrides`() = library { catalog, _ ->
        val cases = listOf(
            audio("His & Hers", "Alice Feeney") to ebook("His and Hers", "Alice Feeney"),
            audio("Ender's Game 20th Anniversary Edition", "Orson Scott Card") to ebook("Ender's Game", "Orson Scott Card"),
            audio("Everything's Eventual", "Stephen King") to ebook("Everything's Eventual: 14 Dark Tales", "Stephen King"),
            audio("The Last Time I Lied: A Novel", "Riley Sager") to ebook("The Last Time I Lied", "Riley Sager"),
            audio("Rivers of London Bk 6", "Ben Aaronovitch") to ebook("The Hanging Tree", "Ben Aaronovitch", listOf(SeriesRef("Rivers of London", 6.0))),
            audio("Doors of Sleep Journals of Zaxony Delatree Book 1", "Tim Pratt") to ebook("Doors of Sleep", "Tim Pratt", listOf(SeriesRef("Journals of Zaxony Delatree", 1.0))),
            audio("Book 1 - Leviathan Wakes", "James S A Corey") to ebook("Leviathan Wakes", "James S. A. Corey"),
        )
        for ((a, e) in cases) assertEquals(e.path, scanner(catalog).pairingPlan(listOf(e), listOf(a)).matches[a.dir.path]?.path, a.title)
    }

    @Test fun `different performances subtitles authors and series volumes stay separate`() = library { catalog, _ ->
        val book = ebook("Dune", "Frank Herbert")
        for (title in listOf("Dune Abridged", "Dune (Dramatized)", "Dune: A Graphic Novel", "Dune Messiah", "Dune (Illustrated)")) {
            assertTrue(scanner(catalog).pairingPlan(listOf(book), listOf(audio(title, "Frank Herbert"))).matches.isEmpty(), title)
        }
        assertTrue(scanner(catalog).pairingPlan(listOf(book), listOf(audio("Dune", "Brian Herbert"))).matches.isEmpty())
        val sixth = ebook("The Hanging Tree", "Ben Aaronovitch", listOf(SeriesRef("Rivers of London", 6.0)))
        assertTrue(scanner(catalog).pairingPlan(listOf(sixth), listOf(audio("Rivers of London Bk 5", "Ben Aaronovitch"))).matches.isEmpty())
        assertFalse(BookIdentity.sameBook(BookIdentity.Book("Stories: 12 Tales", listOf("An Author")), BookIdentity.Book("Stories: 14 Tales", listOf("An Author"))))
    }

    @Test fun `two audio copies or two ebook editions require a choice`() = library { catalog, _ ->
        val book = ebook("Dune", "Frank Herbert")
        val a = audio("Dune", "Frank Herbert")
        assertTrue(scanner(catalog).pairingPlan(listOf(book), listOf(a, audio("Dune", "Frank Herbert", " second"))).matches.isEmpty())
        assertTrue(scanner(catalog).pairingPlan(listOf(book, ebook("Dune", "Frank Herbert", suffix = " second")), listOf(a)).matches.isEmpty())
    }

    @Test fun `existing pair wins over another audio copy and keeps its work identity`() = library { catalog, _ ->
        val book = ebook("Dune", "Frank Herbert")
        val a = audio("Dune", "Frank Herbert")
        catalog.upsertWork("stable", book.title, book.author, "dune", "frankherbert")
        catalog.setEbook("stable", book.path.path, "epub", 1, null, 1)
        catalog.setAudio("stable", a.dir.path, 1, "", emptyList(), emptyList(), null)
        val plan = scanner(catalog).pairingPlan(listOf(book), listOf(a, audio("Dune", "Frank Herbert", " second")))
        assertEquals(mapOf(a.dir.path to book), plan.matches)
        assertEquals(setOf(audio("Dune", "Frank Herbert", " second").dir.path), plan.duplicates)
        assertEquals("stable", catalog.work("stable")!!.id)
    }

    @Test fun `manual audio-only and pairing choices remain authoritative`() = library { catalog, _ ->
        val book = ebook("Dune", "Frank Herbert")
        val a = audio("Dune", "Frank Herbert")
        catalog.setAudioOnly("dune", "frankherbert", true)
        assertTrue(scanner(catalog).pairingPlan(listOf(book), listOf(a)).matches.isEmpty())
        catalog.setPairing("dune", "frankherbert", book.path.path)
        assertEquals(book, scanner(catalog).pairingPlan(listOf(book), listOf(a)).matches[a.dir.path])
    }

    @Test fun `lookup and settlement share stored identity even after a title overlay`() = library { catalog, _ ->
        catalog.upsertWork("stable", "Book 1 - Leviathan Wakes", "James S A Corey", "leviathanwakes", "jamessacorey")
        val work = catalog.work("stable")!!.copy(title = "My display title")
        assertTrue(BookIdentity.matches(work, "Leviathan Wakes", listOf("James S. A. Corey")))
        assertFalse(BookIdentity.matches(work, "Leviathan Wakes", listOf("Another Corey")))
        assertFalse(kotlinx.serialization.json.Json.encodeToString(Work.serializer(), work).contains("titleKey"))
    }

    @Test fun `structured sidecar identity can resolve a folder without weakening author checks`() = library { catalog, _ ->
        val dir = Files.createTempDirectory("unison-sidecar-").toFile()
        try {
            File(dir, "metadata.json").writeText("""{"title":"Where the Red Fern Grows","authors":["Wilson Rawls"]}""")
            val a = Scanner.AudioEntry("Where the Red Fern Grows", "Rawls", dir)
            val e = ebook("Where the Red Fern Grows", "Wilson Rawls")
            assertEquals(e, scanner(catalog).pairingPlan(listOf(e), listOf(a)).matches[dir.path])
            assertTrue(scanner(catalog).pairingPlan(listOf(ebook(e.title, "Another Rawls")), listOf(a)).matches.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `a title-only request does not settle to an arbitrary author`() = library { catalog, lists ->
        for (name in listOf("First Author", "Second Author")) {
            catalog.upsertWork(name, "Same Title", name, "sametitle", Scanner.authorKey(name))
            catalog.setEbook(name, "/ebooks/$name.epub", "epub", 1, null, 1)
        }
        lists.addRequest(null, "Reader", "Same Title", "", "", "ebook", "web")
        assertTrue(lists.settle(catalog.works()).isEmpty())
    }

    @Test fun `lookup preserves meaningful edition distinctions and normalizes author initials`() {
        val work = Work("dune", "Dune", "Frank Herbert")
        assertFalse(BookIdentity.matches(work, "Dune (Illustrated)", listOf("Frank Herbert")))
        assertTrue(BookIdentity.matches(Work("book", "Book", "James S.A. Corey"), "Book", listOf("Corey, James S. A.")))
    }
}
