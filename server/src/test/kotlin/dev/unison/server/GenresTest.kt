package dev.unison.server

import dev.homescreenreader.Track
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Format scoping of the list routes' catalogue calls, and genre browsing. */
class GenresTest {
    private fun withCatalog(body: (Catalog, (String, String, Boolean, Boolean, List<String>, String?, Double?) -> Unit) -> Unit) {
        val root = Files.createTempDirectory("unison-genres-").toFile()
        val c = Catalog(File(root, "catalog.db"))
        try {
            fun work(id: String, author: String, ebook: Boolean, audio: Boolean, genres: List<String>, series: String?, seq: Double?) {
                c.upsertWork(id, id, author, id, author.lowercase())
                if (ebook) c.setEbook(id, "/e/$id.epub", "epub", 1, null, 100)
                if (audio) c.setAudio(id, "/a/$id", 1, "Nora", listOf(Track("/a/$id/1.mp3", "1", 60_000)), emptyList(), null)
                c.setMeta(id, 3, Meta(genres = genres, authors = listOf(author), narrators = if (audio) listOf("Nora") else null, series = series?.let { listOf(SeriesRef(it, seq)) }))
            }
            body(c, ::work)
        } finally { c.connection.close(); root.deleteRecursively() }
    }

    @Test fun `spellings merge and the display name is the most frequent spelling`() = withCatalog { c, work ->
        work("a", "Ann", true, false, listOf("Science Fiction"), null, null)
        work("b", "Bob", true, false, listOf("science-fiction", "Audiobook"), null, null)
        work("c", "Cy", true, false, listOf("Science fiction"), null, null)
        work("d", "Di", true, false, listOf("Science Fiction"), null, null)
        work("e", "Ed", true, false, listOf("page-turner", ""), null, null)
        work("f", "Flo", true, false, listOf("tie", "Tie"), null, null)
        work("g", "Gus", true, false, listOf("Audiobooks"), null, null)
        val g = c.genres()
        assertEquals(listOf("Science Fiction" to 4, "Page Turner" to 1, "Tie" to 1), g.map { it.name to it.works })
        assertTrue(g.none { it.name.lowercase().startsWith("audiobook") })
    }

    @Test fun `a work with both formats counts in both, one-format works in theirs`() = withCatalog { c, work ->
        work("both", "Ann", true, true, listOf("Fantasy"), null, null)
        work("ebook", "Bob", true, false, listOf("Fantasy"), null, null)
        work("audio", "Cy", false, true, listOf("Fantasy", "Horror"), null, null)
        assertEquals(listOf(3), c.genres().filter { it.name == "Fantasy" }.map { it.works })
        assertEquals(listOf("Fantasy" to 2), c.genres("ebook").map { it.name to it.works })
        assertEquals(listOf("Fantasy" to 2, "Horror" to 1), c.genres("audio").map { it.name to it.works })
        assertFailsWith<IllegalArgumentException> { c.genres("video") }
        assertFailsWith<IllegalArgumentException> { c.genreWorks("fantasy", "video") }
    }

    @Test fun `main is every genre in a small library`() = withCatalog { c, work ->
        for (i in 1..6) work("w$i", "A$i", true, false, listOf(if (i <= 5) "Big" else "Small"), null, null)
        assertEquals(listOf(true, true), c.genres().map { it.main })
    }

    @Test fun `main is 5 or more works once 12 genres reach 5`() = withCatalog { c, work ->
        for (g in 1..12) for (i in 1..5) work("g$g-$i", "A", true, false, listOf("Genre $g"), null, null)
        work("lone", "B", true, false, listOf("Rare"), null, null)
        val all = c.genres()
        assertEquals(13, all.size)
        assertEquals(12, all.count { it.main })
        assertFalse(all.single { it.name == "Rare" }.main)
    }

    @Test fun `genreWorks matches by key and sorts by author, series, sequence, title`() = withCatalog { c, work ->
        work("1", "bob", true, false, listOf("Science Fiction"), null, null)
        work("2", "Ann", true, false, listOf("science-fiction"), "Zed", 2.0)
        work("3", "Ann", false, true, listOf("SCIENCE  FICTION"), "Zed", 1.0)
        work("4", "Ann", true, false, listOf("Science Fiction"), null, null)
        work("5", "Ann", true, false, listOf("Fantasy"), null, null)
        assertEquals(listOf("3", "2", "4", "1"), c.genreWorks("science_fiction").map { it.id })
        assertEquals(listOf("2", "4", "1"), c.genreWorks("Science Fiction", "ebook").map { it.id })
        assertEquals(listOf("3"), c.genreWorks("science fiction", "audio").map { it.id })
        assertTrue(c.genreWorks("audiobook").isEmpty())
    }

    @Test fun `people, series and their works are scoped by format and unchanged without one`() = withCatalog { c, work ->
        work("both", "Ann", true, true, listOf("X"), "Saga", 1.0)
        work("ebook", "Bob", true, false, listOf("X"), "Saga", 2.0)
        work("audio", "Cy", false, true, listOf("X"), "Tales", 1.0)
        assertEquals(listOf("Ann", "Bob", "Cy"), c.people("author").map { it.name })
        assertEquals(listOf("Ann", "Bob"), c.people("author", "ebook").map { it.name })
        assertEquals(listOf("Ann", "Cy"), c.people("author", "audio").map { it.name })
        assertEquals(listOf("Nora" to 2), c.people("narrator").map { it.name to it.works })
        assertEquals(listOf("Nora" to 2), c.people("narrator", "audio").map { it.name to it.works })
        assertEquals(listOf("Nora" to 1), c.people("narrator", "ebook").map { it.name to it.works })
        assertEquals(listOf("Saga" to 2, "Tales" to 1), c.series().map { it.name to it.works })
        assertEquals(listOf("Saga" to 2), c.series("ebook").map { it.name to it.works })
        assertEquals(listOf("Saga" to 1, "Tales" to 1), c.series("audio").map { it.name to it.works })
        assertEquals(listOf("both", "ebook"), c.seriesWorks("Saga").map { it.id })
        assertEquals(listOf("both"), c.seriesWorks("saga", "audio").map { it.id })
        assertTrue(c.seriesWorks("Tales", "ebook").isEmpty())
        assertEquals(listOf("both"), c.personWorks("author", "ann").map { it.id })
        assertTrue(c.personWorks("author", "Cy", "ebook").isEmpty())
        assertEquals(listOf("audio"), c.personWorks("author", "Cy", "audio").map { it.id })
    }
}
