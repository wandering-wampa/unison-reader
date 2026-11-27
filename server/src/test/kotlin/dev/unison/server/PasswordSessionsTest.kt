package dev.unison.server

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PasswordSessionsTest {
    private fun users(block: (Users) -> Unit) {
        val dir = Files.createTempDirectory("unison-pw-").toFile()
        val catalog = Catalog(File(dir, "catalog.db"))
        try { block(Users(catalog.connection, "0123456789abcdef0123456789abcdef0123456789abcdef")) } finally { catalog.connection.close(); dir.deleteRecursively() }
    }

    @Test fun `an admin's reset signs every device of that person out`() = users { users ->
        val person = users.create("someone", "their password", "member")
        val other = users.create("another", "other password", "member")
        val phone = users.issue(person.id, "Phone"); val browser = users.issue(person.id, "Web · Firefox"); val kept = users.issue(other.id, "Phone")
        assertEquals(2, users.setPassword(person.id, "a new password"))
        assertNull(users.authenticate(phone)); assertNull(users.authenticate(browser))
        assertNotNull(users.authenticate(kept), "other people stay signed in")
        assertNotNull(users.login("someone", "a new password", "Phone"))
        assertNull(users.login("someone", "their password", "Phone"))
    }

    @Test fun `a person changing their own password keeps only the session making the change`() = users { users ->
        val person = users.create("someone", "their password", "member")
        val here = users.issue(person.id, "Web · Firefox"); val phone = users.issue(person.id, "Phone"); val watch = users.issue(person.id, "Watch")
        assertEquals(2, users.setPassword(person.id, "a new password", keepToken = here))
        assertEquals(person.id, users.authenticate(here)?.id, "the session that asked stays signed in")
        assertNull(users.authenticate(phone)); assertNull(users.authenticate(watch))
        assertEquals(1, users.tokens(person.id).size)
    }
}
