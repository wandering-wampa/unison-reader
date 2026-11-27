package dev.unison.server

import java.net.URI
import kotlin.test.*

class CoverRedirectTest {
    private val lookup = MetaLookup()
    private val image = ByteArray(10) { it.toByte() }
    private fun redirect(to: String) = MetaLookup.CoverResponse(302, to, ByteArray(0))
    private val ok = MetaLookup.CoverResponse(200, null, image)

    /** Drives a chain of answers keyed by address, and records which addresses were asked for. */
    private fun chain(vararg answers: Pair<String, MetaLookup.CoverResponse>, start: String, asked: MutableList<String> = ArrayList()): ByteArray {
        val map = answers.toMap()
        return lookup.followCover(start) { uri -> asked += uri.toString(); map[uri.toString()] ?: error("unexpected request") }
    }

    @Test fun `an allowed chain succeeds`() {
        val asked = ArrayList<String>()
        val bytes = chain(
            "https://covers.openlibrary.org/b/id/1-L.jpg" to redirect("https://ia800000.us.archive.org/x.jpg"),
            "https://ia800000.us.archive.org/x.jpg" to redirect("/y.jpg"),
            "https://ia800000.us.archive.org/y.jpg" to ok,
            start = "https://covers.openlibrary.org/b/id/1-L.jpg", asked = asked,
        )
        assertContentEquals(image, bytes)
        assertEquals(3, asked.size)
    }

    @Test fun `a hop to a host that is not allowed is refused before it is requested`() {
        val asked = ArrayList<String>()
        val e = assertFailsWith<IllegalArgumentException> {
            chain("https://covers.openlibrary.org/a.jpg" to redirect("https://intranet.example.org/a.jpg"), start = "https://covers.openlibrary.org/a.jpg", asked = asked)
        }
        assertEquals("Covers come from Audible or Open Library only", e.message)
        assertEquals(listOf("https://covers.openlibrary.org/a.jpg"), asked)
    }

    @Test fun `a lookalike host and an address literal are refused`() {
        for (bad in listOf("https://evilarchive.org/a.jpg", "https://archive.org.example.org/a.jpg", "https://203.0.113.5/a.jpg")) {
            assertFailsWith<IllegalArgumentException>(bad) { chain("https://covers.openlibrary.org/a.jpg" to redirect(bad), start = "https://covers.openlibrary.org/a.jpg") }
        }
    }

    @Test fun `an http hop is refused`() {
        assertFailsWith<IllegalArgumentException> {
            chain("https://covers.openlibrary.org/a.jpg" to redirect("http://archive.org/a.jpg"), start = "https://covers.openlibrary.org/a.jpg")
        }
        assertFailsWith<IllegalArgumentException> { chain(start = "http://archive.org/a.jpg") }
    }

    @Test fun `more than five hops is refused and five is allowed`() {
        fun hop(n: Int) = "https://archive.org/$n.jpg"
        fun run(hops: Int) = chain(*(0 until hops).map { hop(it) to redirect(hop(it + 1)) }.toTypedArray(), hop(hops) to ok, start = hop(0))
        assertContentEquals(image, run(5))
        assertFailsWith<IllegalArgumentException> { run(6) }
    }

    @Test fun `a redirect with no address is refused`() {
        assertFailsWith<IllegalArgumentException> { chain("https://archive.org/a.jpg" to MetaLookup.CoverResponse(302, null, ByteArray(0)), start = "https://archive.org/a.jpg") }
    }
}
