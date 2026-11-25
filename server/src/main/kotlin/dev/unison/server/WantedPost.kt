package dev.unison.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Says each new request on the wanted list in one Discord channel, through a channel webhook (no bot, nothing is
 * read from Discord). One message per form asked for, in a fixed order, so that whatever reads the channel can rely on them:
 *
 *     Request · type: ebook · title: The Book by Its Author · from: name
 *
 * A request for both forms is two messages (ebook, audiobook). What the channel does with them is the channel's
 * business. Off unless the webhook's address is in the environment (`UNISON_WANTED_WEBHOOK`); the address is a
 * secret and is only ever read from there. Requests that were on the list before the webhook was first set are not
 * posted; a post that fails is tried again after the next change or scan.
 */
class WantedPost(private val webhook: String, private val lists: Lists) {
    private val log = LoggerFactory.getLogger("WantedPost")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wanted-post").apply { isDaemon = true } }
    @Volatile var lastError: String = ""; private set
    /** One pass waiting at most: several changes in a moment are one pass, not one each. */
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // The first time a webhook is set, what is already on the list stays unsaid: only requests made from here on are posted.
        if (lists.setting(STARTED) == null) { lists.markAllPosted(); lists.setSetting(STARTED, System.currentTimeMillis().toString()) }
    }

    /**
     * Posts whatever has not been said yet, in the background, one message at a time. A moment later, not at once:
     * a request for a book the library already has is settled right after it is made, and is then never posted.
     */
    fun flushSoon() { if (!queued.compareAndSet(false, true)) return; worker.schedule({ queued.set(false); runCatching { flush() }.onFailure { lastError = it.message ?: it.javaClass.simpleName; log.warn("Wanted list not posted: $lastError") } }, 2, TimeUnit.SECONDS) }

    /** One pass, oldest first. Stops at the first failure so the order holds; returns how many requests were posted. */
    fun flush(): Int {
        var posted = 0
        for (r in lists.unposted()) {
            for (kind in if (r.kind == "both") listOf("ebook", "audiobook") else listOf(r.kind)) send(line(r, kind))
            lists.markPosted(r.id); posted++
            // Discord allows a webhook a few messages a second; a pause keeps a burst of requests under that.
            Thread.sleep(700)
        }
        lastError = ""
        return posted
    }

    private fun send(text: String, again: Boolean = true) {
        val body = buildJsonObject {
            put("content", text); put("username", "Unison")
            // A title is never a mention: nobody is pinged by what a request happens to contain.
            put("allowed_mentions", buildJsonObject { put("parse", buildJsonArray { }) })
        }.toString()
        val request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("User-Agent", "UnisonServer (wanted list)")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val answer = http.send(request, HttpResponse.BodyHandlers.ofString())
        when {
            answer.statusCode() in 200..299 -> Unit
            // Asked to slow down: wait as long as it says (never more than a minute), once.
            answer.statusCode() == 429 && again -> {
                val wait = answer.headers().firstValue("Retry-After").orElse("2").toDoubleOrNull() ?: 2.0
                Thread.sleep((wait * 1000).toLong().coerceIn(500, 60_000)); send(text, false)
            }
            else -> throw IllegalStateException("the webhook answered ${answer.statusCode()}")
        }
    }

    companion object {
        private const val STARTED = "wanted.post.started"

        /** The line for one request in one form. Line breaks and the field separator cannot come from a title. */
        fun line(r: WantedItem, kind: String): String {
            fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("·", "-").trim()
            val title = clean(r.title) + clean(r.author).takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty()
            return "Request · type: $kind · title: ${title.take(300)}" + clean(r.requester).takeIf { it.isNotBlank() }?.let { " · from: $it" }.orEmpty()
        }

        /** The poster when `UNISON_WANTED_WEBHOOK` holds an http(s) address, else null (the feature is off). */
        fun fromEnvironment(lists: Lists): WantedPost? {
            val address = System.getenv("UNISON_WANTED_WEBHOOK")?.trim().orEmpty()
            if (address.isBlank()) return null
            require(address.startsWith("https://") || address.startsWith("http://")) { "UNISON_WANTED_WEBHOOK is not a web address" }
            return WantedPost(address, lists)
        }
    }
}
