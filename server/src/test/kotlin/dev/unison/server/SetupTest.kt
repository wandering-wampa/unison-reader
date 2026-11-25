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

    // ---- the reset of a forgotten admin password

    private fun reset(block: (File, Config, Catalog) -> Unit) {
        val dir = Files.createTempDirectory("unison-reset-").toFile()
        val catalog = Catalog(File(dir, "catalog.db"))
        try { block(dir, Config(dir, dir, dir, dir, key, 1, 0), catalog) } finally { catalog.connection.close(); dir.deleteRecursively() }
    }

    @Test fun `without the file nothing changes`() = reset { dir, config, catalog ->
        val users = Users(catalog.connection, key)
        users.setUp("a first password")
        assertFalse(Setup.resetAdmin(config, users))
        assertFalse(users.setupNeeded())
        assertFalse(users.resetPending())
        assertNotNull(users.login("admin", "a first password", "test"))
        assertTrue(users.auditLog().none { it.action == "password reset" })
    }

    @Test fun `the file resets the admin and nobody else`() = reset { dir, config, catalog ->
        val users = Users(catalog.connection, key)
        users.setUp("a first password")
        users.create("someone", "their password", "member")
        val device = users.login("admin", "a first password", "test")!!.second
        users.shelve(1, "a-work", "reading", null)
        File(dir, "reset-admin").writeText("ignored")
        assertTrue(Setup.resetAdmin(config, users))
        assertFalse(File(dir, "reset-admin").exists(), "the file is gone")
        assertTrue(users.setupNeeded())
        assertTrue(users.resetPending())
        assertFalse(users.hasPassword(1))
        assertNull(users.login("admin", "a first password", "test"))
        assertNull(users.authenticate(device), "signed out everywhere")
        assertNotNull(users.login("someone", "their password", "test"), "the other account still signs in")
        assertEquals(1, users.shelf(1).size, "the admin's shelf stays")
        val entry = users.auditLog().first { it.action == "password reset" }
        assertEquals("admin", entry.user); assertEquals("", entry.address); assertTrue("reset-admin" in entry.detail)
        assertNull(users.authenticate(key), "the key is not a sign-in during a reset")
        assertFalse(users.keyActive())
        // The file is gone, so a second start does not reset again.
        assertFalse(Setup.resetAdmin(config, users))
    }

    @Test fun `a reset is completed once, with a new password`() = reset { dir, config, catalog ->
        val users = Users(catalog.connection, key)
        users.setUp("a first password")
        users.create("someone", "their password", "member")
        File(dir, "token").writeText(key)
        File(dir, "reset-admin").writeText("")
        Setup.resetAdmin(config, users)
        Setup.dropKeyFile(config, users)
        assertTrue(File(dir, "token").isFile, "while the reset is pending the key file stays")
        assertFailsWith<IllegalArgumentException> { users.setUp("short") }
        assertTrue(users.resetPending(), "a refused password changes nothing")
        assertTrue(users.setUp("a new password"))
        assertFalse(users.setUp("another password"), "of two requests only one wins")
        assertFalse(users.setupNeeded())
        assertFalse(users.resetPending())
        assertNotNull(users.login("admin", "a new password", "test"))
        assertNull(users.login("admin", "a first password", "test"))
        assertNull(users.authenticate(key))
        Setup.dropKeyFile(config, users)
        assertFalse(File(dir, "token").exists(), "the key file is removed as after a first run")
        assertFalse(users.setUp("a third password"), "the setup is closed again")
    }

    @Test fun `a restart in the middle of a reset forgets it`() {
        val dir = Files.createTempDirectory("unison-reset-").toFile()
        try {
            val config = Config(dir, dir, dir, dir, key, 1, 0)
            val first = Catalog(File(dir, "catalog.db"))
            val users = Users(first.connection, key)
            users.setUp("a first password"); users.create("someone", "their password", "member")
            File(dir, "reset-admin").writeText("")
            Setup.resetAdmin(config, users)
            first.connection.close()
            val second = Catalog(File(dir, "catalog.db"))
            val again = Users(second.connection, key)
            assertFalse(again.setupNeeded(), "the pending state is only in memory")
            assertFalse(again.setUp("a new password"), "and the admin is not given a password behind another account")
            second.connection.close()
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the file on a server nobody has set up does nothing`() = reset { dir, config, catalog ->
        val users = Users(catalog.connection, key)
        File(dir, "reset-admin").writeText("")
        assertFalse(Setup.resetAdmin(config, users))
        assertFalse(File(dir, "reset-admin").exists())
        assertTrue(users.setupNeeded())
        assertFalse(users.resetPending(), "an ordinary first run")
        assertEquals("admin", users.authenticate(key)?.name, "and the first key still signs in")
        assertTrue(users.auditLog().none { it.action == "password reset" })
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
