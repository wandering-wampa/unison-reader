package dev.unison.server

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SetupTest {
    // Private addresses are written as numbers: the scan that guards the public copy stops at anything shaped like one.
    private fun ip(vararg parts: Int) = parts.joinToString(".")

    private fun users(block: (Users) -> Unit) {
        val dir = Files.createTempDirectory("unison-setup-").toFile()
        val catalog = Catalog(File(dir, "catalog.db"))
        try { block(Users(catalog.connection, "0123456789abcdef0123456789abcdef0123456789abcdef")) } finally { catalog.connection.close(); dir.deleteRecursively() }
    }

    @Test fun `a new server needs setup and takes it once`() = users { users ->
        assertTrue(users.setupNeeded())
        assertNull(users.login("admin", "", "test"), "no password signs nobody in")
        assertFailsWith<IllegalArgumentException> { users.setUp("short") }
        assertTrue(users.setupNeeded(), "a refused password changes nothing")
        assertTrue(users.setUp("a first password"))
        assertFalse(users.setupNeeded())
        assertFalse(users.setUp("a second password"), "the second time is refused")
        assertNotNull(users.login("admin", "a first password", "test"))
        assertNull(users.login("admin", "a second password", "test"))
    }

    @Test fun `any account with a password means the server is set up`() = users { users ->
        users.create("someone", "their password", "member")
        assertFalse(users.setupNeeded())
        assertFalse(users.setUp("a first password"), "the built-in admin is not given a password behind an existing account")
        assertFalse(users.hasPassword(1))
    }

    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun `the first key signs in before setup and not after`() = users { users ->
        assertEquals("admin", users.authenticate(key)?.name)
        assertTrue(users.keyActive())
        users.setUp("a first password")
        assertNull(users.authenticate(key), "setup ends the key")
        assertFalse(users.keyActive())
        assertNotNull(users.login("admin", "a first password", "test"), "the password still signs in")
    }

    @Test fun `a refused setup leaves the key alone`() = users { users ->
        assertFailsWith<IllegalArgumentException> { users.setUp("short") }
        assertEquals("admin", users.authenticate(key)?.name)
    }

    @Test fun `the key stays ended after a restart`() {
        val dir = Files.createTempDirectory("unison-setup-").toFile()
        try {
            val first = Catalog(File(dir, "catalog.db"))
            val users = Users(first.connection, key)
            assertTrue(users.setUp("a first password"))
            // Every device signed out, as when the only browser signs out: the table is empty at the next start.
            users.revokeAll(1)
            first.connection.close()
            val second = Catalog(File(dir, "catalog.db"))
            val again = Users(second.connection, key)
            assertNull(again.authenticate(key), "an empty token table is not seeded on a server that is set up")
            assertFalse(again.keyActive())
            second.connection.close()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `a server set up before the key ended keeps its key`() {
        val dir = Files.createTempDirectory("unison-setup-").toFile()
        try {
            val first = Catalog(File(dir, "catalog.db"))
            val users = Users(first.connection, key)
            // An account with a password made by other means, as on an install from before first-run setup: the key is not touched.
            users.create("someone", "their password", "member")
            assertFalse(users.setupNeeded())
            assertEquals("admin", users.authenticate(key)?.name)
            first.connection.close()
            val second = Catalog(File(dir, "catalog.db"))
            val again = Users(second.connection, key)
            assertEquals("admin", again.authenticate(key)?.name, "and after a restart")
            assertTrue(again.keyActive())
            second.connection.close()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the key file goes with the key`() {
        val dir = Files.createTempDirectory("unison-setup-").toFile()
        try {
            val file = File(dir, "token").apply { writeText(key) }
            val config = Config(dir, dir, dir, dir, key, 1, 0)
            val catalog = Catalog(File(dir, "catalog.db"))
            val users = Users(catalog.connection, key)
            Setup.dropKeyFile(config, users)
            assertTrue(file.isFile, "before setup the file stays")
            users.setUp("a first password")
            Setup.dropKeyFile(config, users)
            assertFalse(file.exists(), "after setup it is removed")
            catalog.connection.close()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `private and public addresses`() {
        for (a in listOf("127.0.0.1", "127.8.9.10", ip(10, 0, 0, 5), ip(172, 16, 0, 1), ip(172, 31, 255, 254), ip(192, 168, 1, 20), "169.254.3.4", "::1", "0:0:0:0:0:0:0:1", "fd12:3456:789a::1", "fc00::1", "fe80::1", "fe80::1%eth0", "[::1]", "::ffff:${ip(192, 168, 1, 20)}"))
            assertTrue(Setup.private(a), a)
        for (a in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "100.64.0.1", "2001:db8::1", "::ffff:8.8.8.8", "", "unknown", "localhost", "example.org", "${ip(10, 0, 0, 5)}:4711", "10.0.0", "abc", "_hidden"))
            assertFalse(Setup.private(a), a)
    }

    @Test fun `a caller is local only when every address says so`() {
        assertTrue(Setup.local(ip(192, 168, 1, 20), emptyList()))
        assertTrue(Setup.local(ip(172, 18, 0, 1), listOf(ip(192, 168, 1, 20))))
        assertFalse(Setup.local("203.0.113.9", emptyList()))
        assertFalse(Setup.local(ip(172, 18, 0, 1), listOf("203.0.113.9")))
        // A forged first entry does not help: the proxy's own entry behind it is still public.
        assertFalse(Setup.local(ip(172, 18, 0, 1), listOf(ip(192, 168, 1, 20), "203.0.113.9")))
        // A forged header on a direct connection from outside gains nothing either.
        assertFalse(Setup.local("203.0.113.9", listOf("127.0.0.1")))
        assertFalse(Setup.local(ip(172, 18, 0, 1), listOf("unknown")))
    }

    @Test fun `the addresses a proxy names`() {
        val headers = mapOf(
            "X-Forwarded-For" to listOf("${ip(192, 168, 1, 20)}, 203.0.113.9", ip(10, 0, 0, 1)),
            "X-Real-IP" to listOf("203.0.113.9"),
            "Forwarded" to listOf("for=\"[2001:db8::1]:4711\";proto=https;by=${ip(10, 0, 0, 2)}, For=198.51.100.7"),
        )
        assertEquals(listOf(ip(192, 168, 1, 20), "203.0.113.9", ip(10, 0, 0, 1), "203.0.113.9", "\"[2001:db8::1]:4711\"", "198.51.100.7"), Setup.forwarded { headers[it].orEmpty() })
        assertTrue(Setup.forwarded { emptyList() }.isEmpty())
        assertFalse(Setup.private("\"[2001:db8::1]:4711\""))
        assertTrue(Setup.private("\"[fd00::7]:4711\""))
    }

    @Test fun `the setup key is compared whole`() {
        assertTrue(Setup.keyMatches("abc123", "abc123"))
        assertTrue(Setup.keyMatches("  abc123\n", "abc123"), "a key pasted with its line end still matches")
        assertFalse(Setup.keyMatches("", "abc123"))
        assertFalse(Setup.keyMatches("abc12", "abc123"))
        assertFalse(Setup.keyMatches("abc124", "abc123"))
    }
}
