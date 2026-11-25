package dev.unison.server

import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** What the first scan found, for the setup page: counts, and what explains an empty library. */
@Serializable class SetupLibrary(
    val scanned: Boolean, val ebooks: Int, val audiobooks: Int, val paired: Int,
    val calibre: Boolean, val ebookFolderEmpty: Boolean, val audiobookFolderEmpty: Boolean,
)
/** `needed` while nobody can sign in by name; `key` when this caller must also send the setup key; `reset` when the admin's password was reset, which needs the key from every address. */
@Serializable class SetupStatus(val needed: Boolean, val key: Boolean = false, val reset: Boolean = false, val minPassword: Int = Users.MIN_PASSWORD, val library: SetupLibrary? = null)
@Serializable class SetupRequest(val password: String, val key: String = "", val device: String = "", val web: Boolean = false)

/**
 * The first run. A new server has an `admin` nobody can sign in as, so the web client asks for that
 * account's password before anything else (`GET` and `POST /api/setup`). Whoever reaches a new server
 * first would otherwise own it, so the request is trusted on its own only from the machine itself or a
 * private network; from any other address it must carry the setup key, which is the install's first
 * key (`data/token`, or `UNISON_TOKEN`) and so is known only to someone who can read the server's files.
 * Completing the setup ends that key: it stops signing in and its file is removed.
 *
 * A forgotten admin password has the same way back. A file named `reset-admin` in the data folder, looked for once
 * at start-up, clears the built-in admin's password and signs that account out everywhere; the setup page then asks
 * for a new password. Because other accounts may exist, and a container's callers all look private, the setup key is
 * then needed from every address, the machine itself included. The key is `data/token`, which a set-up server no
 * longer has, so the start-up that read the file has already written a new one (or the key is `UNISON_TOKEN`). It
 * never signs in during a reset. Nothing is remembered across a restart: if the server stops before a new
 * password is set, the file is made again.
 */
object Setup {
    /**
     * True when the request comes from this machine or a private network, as far as the server can tell:
     * the connection itself, and every address a proxy says it carried the request for. A proxy's word is
     * only ever used to refuse, never to allow, so a forged header gains nothing; anything that does not
     * read as an address counts as public.
     */
    fun local(remote: String, forwardedFor: List<String>): Boolean = private(remote) && forwardedFor.all { private(it) }

    /** The addresses in the headers proxies use to name the caller: `X-Forwarded-For`, `X-Real-IP`, `Forwarded`, and the two Cloudflare sends. */
    fun forwarded(header: (String) -> List<String>): List<String> {
        val plain = listOf("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP").flatMap { header(it) }.flatMap { it.split(',') }
        val standard = header("Forwarded").flatMap { it.split(',', ';') }.map { it.trim() }.filter { it.startsWith("for=", ignoreCase = true) }.map { it.substring(4) }
        return (plain + standard).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Loopback, the private IPv4 ranges, link-local, and IPv6 unique-local addresses. Names are never looked up. */
    fun private(address: String): Boolean {
        var a = address.trim().removeSurrounding("\"")
        if (a.startsWith("[")) a = a.substringAfter('[').substringBefore(']')
        a = a.substringBefore('%')
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(a)
        val v6 = a.contains(':') && a.all { it.isDigit() || it in "abcdefABCDEF:." }
        if (!v4 && !v6) return false
        val ip = runCatching { InetAddress.getByName(a) }.getOrNull() ?: return false
        return ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress || (ip is Inet6Address && (ip.address[0].toInt() and 0xfe) == 0xfc)
    }

    /**
     * Acts on a file named `reset-admin` in the data folder, once, at start-up. The file is deleted first and, if that
     * fails, nothing else is done, so one file can never fire twice. Its contents are ignored. True when the admin's
     * password was reset; on a server nobody has set up there is nothing to reset and the first run is the ordinary one.
     */
    fun resetAdmin(config: Config, users: Users): Boolean {
        val log = LoggerFactory.getLogger("Setup")
        val file = File(config.data, "reset-admin")
        if (!file.exists()) return false
        if (!file.delete()) { log.error("The file $file asks for the admin password to be reset, but it cannot be deleted, so nothing was done"); return false }
        if (!users.resetAdmin("reset-admin file")) { log.info("The file reset-admin was found and removed, but no account has a password yet, so there is nothing to reset"); return false }
        log.warn("The admin password was reset because the file reset-admin was found in the data folder, and the file is removed. The setup page now asks for a new password and the setup key, the contents of the file named token in the data folder.")
        return true
    }

    /** The setup key is compared whole, in time that does not depend on where it first differs. */
    fun keyMatches(given: String, key: String): Boolean {
        val a = Users.sha256(given.trim()); val b = Users.sha256(key)
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * Removes the key file once nothing accepts the key any more: after setup, and at start-up on a server that is
     * set up and has no sign-in under that key. A server set up before the key ended keeps its key and its file.
     * A key given by `UNISON_TOKEN` has no file and is left to the environment.
     */
    fun dropKeyFile(config: Config, users: Users) {
        if (users.setupNeeded() || users.keyActive()) return
        File(config.data, "token").takeIf { it.isFile }?.delete()
    }

    fun library(config: Config, works: List<Work>, scanned: Boolean) = SetupLibrary(
        scanned = scanned,
        ebooks = works.count { it.hasEbook }, audiobooks = works.count { it.hasAudio }, paired = works.count { it.hasEbook && it.hasAudio },
        calibre = File(config.ebooks, "metadata.db").isFile,
        ebookFolderEmpty = config.ebooks.list().isNullOrEmpty(), audiobookFolderEmpty = config.audiobooks.list().isNullOrEmpty(),
    )
}
