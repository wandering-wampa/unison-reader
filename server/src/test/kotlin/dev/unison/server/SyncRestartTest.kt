package dev.unison.server

import dev.homescreenreader.Anchor
import dev.homescreenreader.Book
import dev.homescreenreader.BookText
import dev.homescreenreader.Track
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Sync again and Pause while a sync runs, and who may ask for them. */
class SyncRestartTest {
    private fun withCatalog(block: (Catalog, SyncService) -> Unit) {
        val root = Files.createTempDirectory("unison-restart-").toFile()
        val catalog = Catalog(File(root, "catalog.db"))
        try {
            val config = Config(File(root, "audio"), File(root, "ebooks"), root, File(root, "model"), "", 1, 0)
            block(catalog, SyncService(config, catalog, Scanner(config, catalog)))
        } finally { catalog.connection.close(); root.deleteRecursively() }
    }

    /** A paired work with two units, one done with two anchors, as if a sync were half way. */
    private fun halfSynced(c: Catalog, id: String) {
        c.upsertWork(id, id, "Author", id, "author")
        c.setEbook(id, "/e/$id.epub", "epub", 1, null, 100)
        c.setAudio(id, "/a/$id", 1, "", listOf(Track("/a/$id/1.mp3", "1", 60_000)), emptyList(), null)
        for (i in 0..1) c.connection.prepareStatement("INSERT INTO units (work, key, idx, title, start_ms, end_ms, core_ms, done) VALUES (?, ?, ?, ?, 0, 1000, 1000, 0)").use {
            it.setString(1, id); it.setString(2, "k$i"); it.setInt(3, i); it.setString(4, "u$i"); it.executeUpdate()
        }
        c.unitDone(id, "k0", listOf(Anchor(0, 0, 10, 1.0), Anchor(1, 10, 20, 1.0)), 1)
        c.setStatus(id, "Syncing · 1 of 2 parts")
    }

    /** A running job with two workers still to finish; nothing is submitted, the test plays the workers. */
    private fun running(c: Catalog, sync: SyncService, id: String): SyncService.Job {
        halfSynced(c, id)
        val book = Book(id, id, "Author")
        val job = SyncService.Job(c.work(id)!!, book, BookText(book), 2, 0L)
        job.remaining.set(2)
        sync.adopt(job)
        return job
    }

    @Test fun `sync again while syncing waits for the workers, then wipes and starts over`() = withCatalog { c, sync ->
        val job = running(c, sync, "w")
        sync.restart("w")
        assertTrue(sync.isSyncing("w"), "the job is still running")
        assertTrue(job.cancel.get())
        assertEquals(2, c.anchorCount("w"), "nothing is wiped while workers still run")
        // A draining worker may still finish its unit; the wipe comes after it.
        c.unitDone("w", "k1", listOf(Anchor(2, 20, 30, 1.0)), 1)
        sync.finish(job)
        assertTrue(sync.isSyncing("w"))
        sync.finish(job)
        assertFalse(sync.isSyncing("w"))
        assertEquals(0, c.anchorCount("w"), "alignment wiped, including what a draining worker wrote")
        assertTrue(c.units("w").isEmpty())
        assertNotEquals("Paused", c.work("w")!!.status)
    }

    @Test fun `a pause after a pending restart wins and keeps the alignment`() = withCatalog { c, sync ->
        val job = running(c, sync, "w")
        sync.restart("w")
        sync.cancel("w")
        sync.finish(job); sync.finish(job)
        assertFalse(sync.isSyncing("w"))
        assertEquals("Paused", c.work("w")!!.status)
        assertEquals(2, c.anchorCount("w"))
        assertEquals(2, c.units("w").size)
    }

    @Test fun `sync again with nothing running wipes at once`() = withCatalog { c, sync ->
        halfSynced(c, "w")
        sync.restart("w")
        assertEquals(0, c.anchorCount("w"))
        assertTrue(c.units("w").isEmpty())
    }
}
