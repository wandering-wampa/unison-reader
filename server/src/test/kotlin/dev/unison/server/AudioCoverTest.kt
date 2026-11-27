package dev.unison.server

import dev.homescreenreader.Track
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.Assume

/** An audiobook's cover: the four names, another picture, then art embedded in the audio, looked for once per stamp. */
class AudioCoverTest {
    private class Env(val root: File, probed: Boolean) {
        val ebooks = File(root, "ebooks").apply { mkdirs() }
        val audio = File(root, "audio").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        val catalog = Catalog(File(data, "catalog.db"))
        var extractions = 0
        var art: ((File) -> Boolean)? = null
        val scanner = Scanner(
            Config(audio, ebooks, data, File(root, "model"), "", 0, 0), catalog,
            prober = if (probed) ({ files -> Scanner.Probed("", files.map { Track(it.path, it.nameWithoutExtension, 60_000) }, emptyList(), Meta()) }) else null,
            artExtractor = if (probed) ({ _, target -> extractions++; art?.invoke(target) ?: false }) else null,
        )
        val book = File(audio, "Ann Author/A Book").apply { mkdirs() }
        init { File(book, "01.mp3").writeBytes(ByteArray(10)) }
        fun id() = catalog.works().single().id
        fun close() { catalog.connection.close(); root.deleteRecursively() }
    }

    private fun env(probed: Boolean = true, block: (Env) -> Unit) {
        val e = Env(Files.createTempDirectory("unison-audiocover-").toFile(), probed)
        try { block(e) } finally { e.close() }
    }

    private fun picture(file: File, size: Int = 8, format: String = "png") {
        ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), format, file)
    }

    @Test fun `a cover named in capitals is found`() = env { e ->
        picture(File(e.book, "Front.JPG"), format = "jpg")
        e.scanner.scan()
        assertTrue(e.catalog.works().single().cover)
        assertEquals("Front.JPG", File(e.catalog.coverPath(e.id())!!).name)
        assertEquals(0, e.extractions)
    }

    @Test fun `the four names come before other pictures, then cover-like names, then the largest`() = env { e ->
        picture(File(e.book, "scan.png"), 64)
        picture(File(e.book, "cover art.jpg"), 4, "jpg")
        picture(File(e.book, "back.png"), 32)
        e.scanner.scan()
        assertEquals("cover art.jpg", File(e.catalog.coverPath(e.id())!!).name)
    }

    @Test fun `without a preferred name the largest picture wins, and Cover jpg beats both`() = env { e ->
        picture(File(e.book, "small.png"), 4)
        picture(File(e.book, "big.png"), 64)
        e.scanner.scan()
        assertEquals("big.png", File(e.catalog.coverPath(e.id())!!).name)
        picture(File(e.book, "Cover.jpg"), 2, "jpg")
        File(e.book, "01.mp3").writeBytes(ByteArray(11)) // changed audio, so the work is read again
        e.catalog.setCover(e.id(), "") // the earlier find is gone
        e.scanner.scan()
        assertEquals("Cover.jpg", File(e.catalog.coverPath(e.id())!!).name)
    }

    @Test fun `embedded art is asked for once per stamp and a picture added later is still found`() = env { e ->
        e.scanner.scan()
        assertFalse(e.catalog.works().single().cover)
        assertEquals(1, e.extractions)
        repeat(3) { e.scanner.scan() }
        assertEquals(1, e.extractions)
        assertFalse(e.catalog.works().single().cover)
        picture(File(e.book, "folder.png"))
        e.scanner.scan()
        assertTrue(e.catalog.works().single().cover)
        assertEquals(1, e.extractions)
    }

    @Test fun `new audio files are looked at again`() = env { e ->
        e.scanner.scan()
        File(e.book, "02.mp3").writeBytes(ByteArray(12))
        e.scanner.scan()
        assertEquals(2, e.extractions)
    }

    @Test fun `an existing work with unchanged audio gets its cover on the next pass and a bad extraction is thrown away`() = env { e ->
        e.scanner.scan()
        val id = e.id()
        // Garbage is not a picture.
        e.art = { it.writeText("not a picture"); true }
        File(e.book, "01.mp3").writeBytes(ByteArray(13))
        assertEquals(1, e.scanner.scan())
        assertFalse(e.catalog.work(id)!!.cover)
        assertFalse(File(e.data, "audio-covers").listFiles().orEmpty().any { it.length() > 0 })
        val before = e.catalog.work(id)!!.updated
        // The database as an older release left it: the audio is known, the column says nothing was looked at.
        e.art = { picture(it, format = "jpg"); true }
        e.catalog.setCoverChecked(id, 0)
        Thread.sleep(5)
        assertEquals(0, e.scanner.scan())
        val work = e.catalog.work(id)!!
        assertTrue(work.cover)
        assertTrue(work.updated > before)
        assertTrue(File(e.catalog.coverPath(id)!!).isFile)
        assertEquals(3, e.extractions)
    }

    @Test fun `a cover the work already has is kept`() = env { e ->
        val own = File(e.root, "own.png").also { picture(it) }
        e.scanner.scan()
        e.catalog.setCover(e.id(), own.path)
        picture(File(e.book, "cover.png"), 64)
        e.scanner.scan()
        assertEquals(own.path, e.catalog.coverPath(e.id()))
    }

    @Test fun `ffmpeg picture is taken out of a real file`() {
        val ffmpeg = runCatching { ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor(20, TimeUnit.SECONDS) }.getOrDefault(false)
        Assume.assumeTrue("ffmpeg is not installed", ffmpeg)
        env(probed = false) { e ->
            val png = File(e.root, "art.png").also { picture(it, 32) }
            val mp3 = File(e.book, "01.mp3")
            mp3.delete()
            val made = ProcessBuilder(
                "ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi", "-i", "sine=duration=1", "-i", png.path, "-map", "0:a", "-map", "1:v",
                "-c:a", "libmp3lame", "-c:v", "copy", "-id3v2_version", "3", "-disposition:v", "attached_pic", mp3.path,
            ).redirectErrorStream(true).start()
            made.inputStream.readBytes()
            Assume.assumeTrue("ffmpeg could not build a test file", made.waitFor(60, TimeUnit.SECONDS) && made.exitValue() == 0 && mp3.length() > 0)
            e.scanner.scan()
            val work = e.catalog.works().single()
            assertTrue(work.cover)
            val file = File(e.catalog.coverPath(work.id)!!)
            assertTrue(file.path.startsWith(File(e.data, "audio-covers").path))
            assertNotNull(ImageIO.read(file))
        }
    }
}
