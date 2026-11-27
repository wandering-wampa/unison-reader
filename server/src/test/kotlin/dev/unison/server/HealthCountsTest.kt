package dev.unison.server

import dev.homescreenreader.Track
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The counts /api/health reports come from one query and must equal what the shelf of each work says. */
class HealthCountsTest {
    @Test fun `shelfCounts equals the counts taken from the works`() {
        val root = Files.createTempDirectory("unison-health-").toFile()
        val c = Catalog(File(root, "catalog.db"))
        try {
            fun work(id: String, ebook: Boolean, audio: Boolean, status: String = "") {
                c.upsertWork(id, id, "Author", id, "author")
                if (ebook) c.setEbook(id, "/e/$id.epub", "epub", 1, null, 100)
                if (audio) c.setAudio(id, "/a/$id", 1, "", listOf(Track("/a/$id/1.mp3", "1", 60_000)), emptyList(), null)
                if (status.isNotEmpty()) c.setStatus(id, status)
            }
            work("ready", true, true, "Synced")
            work("ready2", true, true, "Synced")
            work("syncing", true, true, "Syncing · 1 of 2 parts")
            work("failed", true, true, "Failed: only 3% of the text was found in the audio")
            work("fresh", true, true)
            work("ebook", true, false, "Synced")
            work("audio", false, true, "Synced")
            work("audioonly", false, true)
            c.setAudioOnly("audioonly", "author", true)
            work("audioonly-paired", true, true)
            c.setAudioOnly("audioonly-paired", "author", true)
            work("hidden", true, true, "Synced")
            c.markMissing("hidden", 5)
            work("hidden-audio-only", false, true)
            c.setAudioOnly("hidden-audio-only", "author", true)
            c.markMissing("hidden-audio-only", 5)

            val works = c.works()
            val expected = listOf(works.size, works.count { it.shelf == "ready" }, works.count { it.shelf == "preparing" }, works.count { it.shelf == "missing" })
            assertEquals(expected, c.shelfCounts())
            assertEquals(listOf(9, 3, 4, 2), c.shelfCounts(), "the fixture covers every state")
        } finally { c.connection.close(); root.deleteRecursively() }
    }

    @Test fun `an empty catalog counts zero`() {
        val root = Files.createTempDirectory("unison-health-").toFile()
        val c = Catalog(File(root, "catalog.db"))
        try { assertEquals(listOf(0, 0, 0, 0), c.shelfCounts()) } finally { c.connection.close(); root.deleteRecursively() }
    }
}
