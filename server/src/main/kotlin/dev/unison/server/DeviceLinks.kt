package dev.unison.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable class LinkStart(val device: String = "")
/** What the device is given: the code it shows, the secret it keeps and asks with, when the code stops working, and how often to ask (seconds). */
@Serializable class LinkStarted(val code: String, val secret: String, val expires: Long, val poll: Int)
@Serializable class LinkClaim(val secret: String)
/** `status` is "waiting" until somebody approves the code, then "approved" with the device's own token, once. */
@Serializable class LinkStatus(val status: String, val token: String? = null, val user: User? = null)
/** What the person approving is shown about the device that asked. */
@Serializable class LinkInfo(val device: String, val address: String, val created: Long)

/**
 * Signing in a device that is no good for typing a password on (a watch). The device asks for a code and shows it; a
 * person already signed in on their phone or on the web types that code and approves it; the device, which has been
 * asking with a secret only it holds, is then given a token of its own for that person's account. The password never
 * reaches the device.
 *
 * A code is six digits, lasts ten minutes and works once. Codes live in memory only: a restart drops the ones waiting
 * and the device asks for another. Nothing is stored about the secret but its SHA-256, and the token is made at the
 * moment the device collects it, so no token ever waits here. Knowing a code gives nothing without the secret; typing
 * a code needs a sign-in, wrong codes are slowed like wrong passwords (in `Api.kt`), and the person approving is shown
 * which device asked and from where before they say yes.
 */
class DeviceLinks(private val users: Users) {
    private class Pending(val code: String, val device: String, val address: String, val created: Long, @Volatile var expires: Long, @Volatile var user: Long? = null)

    /** By the SHA-256 of the device's secret. */
    private val pending = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()

    private fun live(): List<Pending> {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { it.value.expires <= now }
        return pending.values.toList()
    }

    /** A new code for a device, or null when too many are waiting (five from one address, a hundred in all). */
    @Synchronized fun start(device: String, address: String): LinkStarted? {
        val waiting = live()
        if (waiting.size >= MAX_TOTAL || waiting.count { it.address == address } >= MAX_PER_ADDRESS) return null
        val taken = waiting.map { it.code }.toSet()
        var code: String
        do { code = "%06d".format(random.nextInt(1_000_000)) } while (code in taken)
        val secret = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val name = device.filter { !it.isISOControl() }.trim().take(60).ifBlank { "Watch" }
        pending[Users.sha256(secret)] = Pending(code, name, address, now, now + LIFETIME_MS)
        return LinkStarted(code, secret, now + LIFETIME_MS, POLL_SECONDS)
    }

    private fun byCode(code: String): Pending? { val digits = code.filter { it.isDigit() }; return live().firstOrNull { it.code == digits && it.user == null } }

    /** The device waiting behind a code nobody has approved yet, or null. */
    fun info(code: String): LinkInfo? = byCode(code)?.let { LinkInfo(it.device, it.address, it.created) }

    /** `user` lets the device behind this code sign in as them. The device is given two minutes more to collect, at least. */
    @Synchronized fun approve(code: String, user: Long): LinkInfo? {
        val p = byCode(code) ?: return null
        p.user = user
        p.expires = maxOf(p.expires, System.currentTimeMillis() + COLLECT_MS)
        return LinkInfo(p.device, p.address, p.created)
    }

    /** The device asks whether its code was approved: null when the code is gone, otherwise waiting, or approved with its token (then the code is spent). */
    @Synchronized fun claim(secret: String): LinkStatus? {
        if (secret.length != 64) return null
        live()
        val key = Users.sha256(secret)
        val p = pending[key] ?: return null
        val user = p.user?.let { users.byId(it) } ?: return if (p.user == null) LinkStatus("waiting") else { pending.remove(key); null }
        pending.remove(key)
        return LinkStatus("approved", users.issue(user.id, p.device), user)
    }

    companion object {
        const val LIFETIME_MS = 10 * 60_000L
        const val COLLECT_MS = 2 * 60_000L
        const val POLL_SECONDS = 2
        const val MAX_PER_ADDRESS = 5
        const val MAX_TOTAL = 100
    }
}
