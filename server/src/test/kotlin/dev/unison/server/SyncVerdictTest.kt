package dev.unison.server

import dev.homescreenreader.Anchor
import dev.homescreenreader.Track
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SyncVerdictTest {
    @Test fun `plenty of sentences found is Synced`() {
        assertEquals("Synced", SyncVerdict.status(500, 1000))
        assertEquals("Synced", SyncVerdict.status(1000, 1000))
    }

    @Test fun `exactly a fifth is Synced, just under is failed with the whole percent`() {
        assertEquals("Synced", SyncVerdict.status(200, 1000))
        assertEquals("Failed: only 19% of the text was found in the audio", SyncVerdict.status(199, 1000))
        assertEquals("Failed: only 0% of the text was found in the audio", SyncVerdict.status(0, 1000))
        assertEquals("Failed: only 16% of the text was found in the audio", SyncVerdict.status(1, 6))
    }

    @Test fun `a work without sentences is never judged`() {
        assertFalse(SyncVerdict.poorlyMatched(0, 0))
        assertEquals("Synced", SyncVerdict.status(0, 0))
    }

    private fun withCatalog(block: (Catalog, SyncService) -> Unit) {
        val root = Files.createTempDirectory("unison-verdict-").toFile()
        val catalog = Catalog(File(root, "catalog.db"))
        try {
            val config = Config(File(root, "audio"), File(root, "ebooks"), root, File(root, "model"), "", 1, 0)
            block(catalog, SyncService(config, catalog, Scanner(config, catalog)))
        } finally { catalog.connection.close(); root.deleteRecursively() }
    }

    /** A paired work with two finished units, `anchors` sentences found out of 100, and the given stored status. */
    private fun pair(c: Catalog, id: String, anchors: Int, status: String, done: Boolean = true) {
        c.upsertWork(id, id, "Author", id, "author")
        c.setEbook(id, "/e/$id.epub", "epub", 1, null, 100)
        c.setAudio(id, "/a/$id", 1, "", listOf(Track("/a/$id/1.mp3", "1", 60_000)), emptyList(), null)
        for (i in 0..1) c.connection.prepareStatement("INSERT INTO units (work, key, idx, title, start_ms, end_ms, core_ms, done) VALUES (?, ?, ?, ?, 0, 1000, 1000, ?)").use {
            it.setString(1, id); it.setString(2, "k$i"); it.setInt(3, i); it.setString(4, "u$i"); it.setInt(5, if (done) 1 else 0); it.executeUpdate()
        }
        c.unitDone(id, "k0", List(anchors) { Anchor(it, it * 10L, it * 10L + 5, 1.0) }, 1)
        c.setStatus(id, status)
    }

    @Test fun `the start-up pass fails a poorly matched Synced work, leaves the rest, and a second pass changes nothing`() = withCatalog { c, sync ->
        pair(c, "poor", 10, "Synced")
        pair(c, "good", 90, "Synced")
        pair(c, "edge", 20, "Synced")
        pair(c, "other", 0, "Failed: ffmpeg failed")
        pair(c, "running", 0, "Syncing · 1 of 2 parts", done = false)
        assertEquals("ready", c.work("poor")!!.shelf)
        assertEquals(1, sync.rejudgeSynced())
        assertEquals("Failed: only 10% of the text was found in the audio", c.work("poor")!!.status)
        assertEquals("preparing", c.work("poor")!!.shelf)
        assertEquals("Synced", c.work("good")!!.status)
        assertEquals("Synced", c.work("edge")!!.status)
        assertEquals("Failed: ffmpeg failed", c.work("other")!!.status)
        assertEquals("Syncing · 1 of 2 parts", c.work("running")!!.status)
        assertEquals(0, sync.rejudgeSynced())
    }
}
