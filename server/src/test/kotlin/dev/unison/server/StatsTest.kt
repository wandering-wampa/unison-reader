package dev.unison.server

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.*

/** Which sessions Stats keeps, and that titles for the reports and details for one work are read for just those works. */
class StatsTest {
    private val now = System.currentTimeMillis()
    private fun env(block: (Catalog, Stats) -> Unit) {
        val dir = Files.createTempDirectory("unison-stats-").toFile()
        val catalog = Catalog(File(dir, "catalog.db"))
        try { block(catalog, Stats(catalog.connection, ZoneOffset.UTC) { now }) } finally { catalog.connection.close(); dir.deleteRecursively() }
    }
    private fun session(started: Long, ms: Long, kind: String = "listen") = SessionIn("w", kind, started, started + ms)

    @Test fun `a normal session counts, a very short or very long or future one does not`() = env { _, stats ->
        val minute = 60_000L; val hour = 60 * minute
        assertEquals(1, stats.record(1, "d", listOf(session(now - 2 * hour, hour))))
        assertEquals(0, stats.record(1, "d", listOf(session(now - 3 * hour, 2000))))
        assertEquals(0, stats.record(1, "d", listOf(session(now - 40 * hour, 24 * hour + 1))))
        assertEquals(1, stats.record(1, "d", listOf(session(now - 50 * hour, 24 * hour))))
        // Ending up to ten minutes after the server's clock is allowed, later is not.
        assertEquals(1, stats.record(1, "d", listOf(session(now - 5 * minute, 15 * minute, "read"))))
        assertEquals(0, stats.record(1, "d", listOf(session(now - 5 * minute, 15 * minute + 1, "listen"))))
        assertEquals(0, stats.record(1, "d", listOf(session(now + hour, hour))))
        assertEquals(3, stats.report(1, 0, ZoneOffset.UTC) { emptyMap() }.all.sessions)
    }

    @Test fun `reports ask for the titles of all the works they need in one call`() = env { catalog, stats ->
        for (w in listOf("a", "b", "c")) catalog.upsertWork(w, "Title $w", "Author $w", "title$w", "author$w")
        val start = now - 3_600_000L
        stats.record(1, "d", listOf(SessionIn("a", "listen", start, start + 600_000), SessionIn("b", "read", start, start + 300_000), SessionIn("gone", "read", start, start + 100_000)))
        val asked = ArrayList<Set<String>>()
        val lookup = { ids: Set<String> -> asked += ids; catalog.worksByIds(ids).associate { it.id to (it.title to it.author) } }
        val report = stats.report(1, 0, ZoneOffset.UTC, lookup)
        assertEquals(1, asked.size)
        assertEquals(listOf("a", "b", "gone"), report.books.map { it.work })
        assertEquals(listOf("Title a", "Title b", "A book no longer on the server"), report.books.map { it.title })
        asked.clear()
        val year = stats.year(1, null, listOf("c" to start), ZoneOffset.UTC, lookup)
        assertEquals(1, asked.size)
        assertEquals(setOf("a", "b", "gone", "c"), asked.single())
        assertEquals(listOf("Title c"), year.finished.map { it.title })
        assertEquals("Author a", year.topAuthor)
    }

    @Test fun `a single work comes back with its series, people and override, and a set only reads its own rows`() = env { catalog, _ ->
        for (w in listOf("a", "b")) catalog.upsertWork(w, "Title $w", "Author $w", "title$w", "author$w")
        catalog.setMeta("a", 3, Meta(series = listOf(SeriesRef("Saga", 2.0)), authors = listOf("Author a", "Co Author"), narrators = listOf("Reader a")))
        catalog.setMeta("b", 3, Meta(series = listOf(SeriesRef("Other", 1.0))))
        catalog.setOverrides("a", mapOf("publisher" to "Small Press"))
        catalog.setOverrides("b", mapOf("title" to "Renamed"))
        val a = catalog.worksByIds(listOf("a")).single()
        assertEquals(a, catalog.work("a"))
        assertEquals(listOf(SeriesRef("Saga", 2.0)), a.series)
        assertEquals(listOf("Author a", "Co Author"), a.authors)
        assertEquals(listOf("Reader a"), a.narrators)
        assertEquals("Small Press", a.publisher)
        assertEquals(listOf("publisher"), a.edited)
        assertEquals("Renamed", catalog.work("b")!!.title)
        // The scoped and the whole-catalogue paths agree.
        assertEquals(catalog.works().associateBy { it.id }, catalog.worksByIds(listOf("a", "b", "nope")).associateBy { it.id })
        assertEquals(catalog.work("a"), catalog.worksByIds((0..Catalog.SCOPED_MAX + 5).map { "x$it" } + "a").single())
    }
}
