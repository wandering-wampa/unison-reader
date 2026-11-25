package dev.unison.server

import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.Serializable

/** What the first scan found, for the setup page: counts, and what explains an empty library. */
@Serializable class SetupLibrary(
    val scanned: Boolean, val ebooks: Int, val audiobooks: Int, val paired: Int,
    val calibre: Boolean, val ebookFolderEmpty: Boolean, val audiobookFolderEmpty: Boolean,
)
/** `needed` while nobody can sign in by name; `key` when this caller must also send the setup key. */
@Serializable class SetupStatus(val needed: Boolean, val key: Boolean = false, val minPassword: Int = Users.MIN_PASSWORD, val library: SetupLibrary? = null)
@Serializable class SetupRequest(val password: String, val key: String = "", val device: String = "", val web: Boolean = false)

/**
 * The first run. A new server has an `admin` nobody can sign in as, so the web client asks for that
 * account's password before anything else (`GET` and `POST /api/setup`). Whoever reaches a new server
 * first would otherwise own it, so the request is trusted on its own only from the machine itself or a
 * private network; from any other address it must carry the setup key, which is the install's first
 * key (`data/token`, or `UNISON_TOKEN`) and so is known only to someone who can read the server's files.
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

    /** The setup key is compared whole, in time that does not depend on where it first differs. */
    fun keyMatches(given: String, key: String): Boolean {
        val a = Users.sha256(given.trim()); val b = Users.sha256(key)
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    fun library(config: Config, works: List<Work>, scanned: Boolean) = SetupLibrary(
        scanned = scanned,
        ebooks = works.count { it.hasEbook }, audiobooks = works.count { it.hasAudio }, paired = works.count { it.hasEbook && it.hasAudio },
        calibre = File(config.ebooks, "metadata.db").isFile,
        ebookFolderEmpty = config.ebooks.list().isNullOrEmpty(), audiobookFolderEmpty = config.audiobooks.list().isNullOrEmpty(),
    )
}
