package dev.homescreenreader.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.homescreenreader.ServerClient

/**
 * The watch's side of the server: which audiobooks are on the person's shelf, what the watch needs of each, and the
 * place kept in step with the phone and the web by the same rule those follow. A newer place from another device is
 * taken when nothing moved here since the last exchange; when both moved and are close, this one is kept and sent; far
 * apart (two minutes of audio), the person is asked. A book playing here keeps its place. Everything here blocks: call
 * it off the main thread.
 */
object Sync {
    /** The server no longer knows this watch (signed out from the device list, or idle for a year). */
    private fun signedOut(e: Throwable?) = e?.message?.startsWith("Server answered 401") == true

    /**
     * The shelf, as audiobooks: every book the person is reading or has queued that has audio, with what the watch
     * keeps of it brought up to date. False when the server could not be reached (the last list stays).
     */
    fun refreshShelf(context: Context): Boolean {
        val store = Store(context)
        val client = store.client() ?: return false
        val entries = runCatching { client.shelf() }.getOrElse { if (signedOut(it)) store.signOut(); return false }.filter { it.state != "finished" }
        val ids = ArrayList<String>()
        for (e in entries) {
            val known = store.book(e.work)
            val stale = System.currentTimeMillis() - store.checked(e.work) > 12 * 3600_000L
            // A work the server no longer has (its files were renamed or removed) is not offered; one it could not be asked about is kept as it was.
            val book = if (known == null && !stale) null else if (stale) runCatching { fetchBook(store, client, e.work) }.getOrElse { if (it.message?.startsWith("Server answered 404") == true) null else known } else known
            if (book == null) continue
            ids.add(book.id)
            e.position?.let { consider(context, book.id, it, apply = true) }
        }
        store.shelf = ids
        // A book that left the shelf (finished, or taken off) leaves the watch too, unless its audio was put here on purpose.
        for (b in store.books()) if (b.id !in ids && store.audioBytes(b.id) == 0L && PlaybackService.playingBook() != b.id) store.forget(b.id)
        settle(context)
        return true
    }

    /** What the server says about one book, kept: its tracks and chapters, its cover, and which sentence each moment of audio belongs to. Null when it has no audio. */
    fun fetchBook(store: Store, client: ServerClient, id: String): WatchBook? {
        val d = client.detail(id)
        store.checked(id, System.currentTimeMillis())
        if (!d.work.hasAudio || d.tracks.isEmpty()) return null
        val book = WatchBook(id, d.work.title, d.work.author, d.work.narrator, d.tracks.sortedBy { it.index }.map { WatchBook.Part(it.name, it.duration) }, d.chapters)
        // Different files on the server than the ones downloaded: the copy here is of something else.
        store.book(id)?.let { old -> if (old.tracks.map { it.duration } != book.tracks.map { it.duration } && PlaybackService.playingBook() != id) store.removeAudio(id) }
        store.save(book)
        if (d.work.cover && (!store.cover(id).isFile || store.coverVersion(id) != d.work.coverVersion))
            runCatching { client.download("/api/works/$id/cover", store.cover(id)) }.onSuccess { store.coverVersion(id, d.work.coverVersion) }
        if (d.work.hasEbook && d.sentencesAligned > 0 && (d.sentencesAligned != store.anchorCount(id) || !store.hasAnchors(id)))
            runCatching { client.alignment(id) }.onSuccess { a -> store.saveAnchors(id, a.map { it.start to it.passage }.sortedBy { it.first }); store.anchorCount(id, d.sentencesAligned) }
        return book
    }

    sealed class Pulled {
        object Nothing : Pulled()
        /** A newer place from another device, nothing having moved here: `audio` is where to go on from, `passage` the text's place that came with it. */
        class Newer(val audio: Long, val passage: Int, val updated: Long, val by: String) : Pulled()
        /** Two places far apart, for the person to choose between. */
        class Apart(val audio: Long, val passage: Int, val updated: Long, val by: String) : Pulled()
    }

    fun apart(a: Long, b: Long) = kotlin.math.abs(a - b) > 120_000

    /**
     * Where a place saved by another device is in the audio: its audio time, as the phone's and the web's players take
     * it. The text's place is a separate thing (someone can be reading in one part and listening in another) and never
     * decides where the watch starts; only a book that was read and never listened to, which has no audio time at all,
     * starts where the reading is.
     */
    private fun audioOf(store: Store, id: String, p: ServerClient.Position): Long =
        if (p.audio <= 0 && p.passage > 0) store.audioAt(id, p.passage) ?: 0 else p.audio

    /**
     * The sentence the watch sends with its audio place. Other devices take a place whole, text and audio, so this is
     * what the phone's text opens at after listening on the watch. Where the text and the audio were together when the
     * watch took the place, they stay together (the sentence being narrated); where they were apart, the text is left
     * exactly where it was. The watch never moves someone's reading place on its own.
     */
    private fun passageFor(store: Store, id: String, audio: Long): Int =
        if (store.together(id) && store.hasAnchors(id)) store.passageAt(id, audio) else store.passage(id)

    /**
     * What another device's newest place means here. With `apply`, a newer place is taken at once when nothing moved
     * here (and the book is not playing), and two places far apart are kept for the question the book's page asks.
     */
    fun consider(context: Context, id: String, remote: ServerClient.Position, apply: Boolean): Pulled {
        val store = Store(context)
        if (remote.updated <= store.serverUpdated(id)) return Pulled.Nothing
        val there = audioOf(store, id, remote)
        val here = store.audio(id); val sent = store.pushed(id)
        val moved = if (sent < 0) here > 0 else sent != here
        val playingHere = PlaybackService.playingBook() == id && (PlaybackService.playing() || PlaybackService.starting(id))
        if (!moved && !playingHere) {
            if (apply) take(context, id, there, remote.passage, remote.updated)
            return Pulled.Newer(there, remote.passage, remote.updated, remote.by)
        }
        if (playingHere || !apart(here, there)) return Pulled.Nothing
        if (apply) store.elsewhere(id, there, remote.passage, remote.updated, remote.by)
        return Pulled.Apart(there, remote.passage, remote.updated, remote.by)
    }

    /** The server's newest place for a book, considered (not applied). */
    fun pull(context: Context, id: String): Pulled {
        val client = Store(context).client() ?: return Pulled.Nothing
        val remote = runCatching { client.position(id) }.getOrNull() ?: return Pulled.Nothing
        return consider(context, id, remote, apply = false)
    }

    /** The other device's place becomes this watch's; a player holding the book (paused) moves with it. */
    fun take(context: Context, id: String, audio: Long, passage: Int, updated: Long) {
        val store = Store(context)
        // Text and audio count as together when the text is within thirty sentences of what is narrated there (or there is no text place at all).
        val together = passage <= 0 || (store.hasAnchors(id) && kotlin.math.abs(store.passageAt(id, audio) - passage) <= 30)
        store.textPlace(id, passage, together)
        store.audio(id, audio); store.markPushed(id, audio, updated); store.clearElsewhere(id)
        if (PlaybackService.playingBook() == id) PlaybackService.send(context, "seek", id, audio)
    }

    /** The server answered, even if with a refusal: it can be reached. Anything else is a missing connection. */
    private fun answered(e: Throwable?) = e == null || e.message?.startsWith("Server answered") == true

    /**
     * Sends this watch's place when it has moved. Refused because another device moved the book meanwhile: it is still
     * sent when the two are close or the book is playing here; far apart it is held, and the person is asked. `force`
     * (the person chose this place) sends it regardless. False when the server could not be reached at all.
     */
    fun push(context: Context, id: String, force: Boolean = false): Boolean {
        val store = Store(context)
        val client = store.client() ?: return false
        val epoch = Store.epoch
        val audio = store.audio(id)
        if (!force && (store.pushed(id) == audio || (store.pushed(id) < 0 && audio == 0L))) return true
        val passage = passageFor(store, id, audio)
        if (force) {
            val sent = runCatching { client.putPosition(id, passage, audio) }
            sent.onSuccess { if (Store.epoch == epoch) { store.markPushed(id, audio, it.updated); store.clearElsewhere(id) } }
            return answered(sent.exceptionOrNull())
        }
        val answer = runCatching { client.putPosition(id, passage, audio, store.serverUpdated(id)) }.getOrElse { if (signedOut(it) && Store.epoch == epoch) store.signOut(); return answered(it) }
        // Signed out while the server was answering: the answer belongs to nobody here any more.
        if (Store.epoch != epoch) return true
        if (answer.saved) { store.markPushed(id, audio, answer.position.updated); return true }
        val there = audioOf(store, id, answer.position)
        val playingHere = PlaybackService.playingBook() == id && PlaybackService.playing()
        if (playingHere || !apart(audio, there)) return push(context, id, force = true)
        store.elsewhere(id, there, answer.position.passage, answer.position.updated, answer.position.by)
        return true
    }

    private val owing = Any()

    /**
     * Everything this watch owes the server: places that moved with no connection, listening sessions, a speed chosen
     * here. It stops at the first thing the server cannot be reached for, so a watch with no connection tries once,
     * not once for everything it holds. Safe to call often.
     */
    fun settle(context: Context) {
        val store = Store(context)
        val client = store.client() ?: return
        val epoch = Store.epoch
        synchronized(owing) {
            val pending = store.pendingSessions()
            if (pending.length() > 0) {
                val sent = runCatching { client.postSessions(pending) }
                if (sent.isSuccess) { if (Store.epoch == epoch) store.clearSessions(pending) } else if (!answered(sent.exceptionOrNull())) return
            }
            for (b in store.books()) {
                if (Store.epoch != epoch) return
                if (store.elsewhere(b.id) == null && !push(context, b.id)) return
                if (store.speedUnsent(b.id)) {
                    val sent = runCatching { client.putSpeed(b.id, store.speed(b.id)) }
                    if (sent.isSuccess) { if (Store.epoch == epoch) store.speedSent(b.id) } else if (!answered(sent.exceptionOrNull())) return
                }
            }
        }
    }

    /** The book's speed from the server, unless one chosen here has not been sent yet. True when it changed here. */
    fun pullSpeed(context: Context, id: String): Boolean {
        val store = Store(context)
        val client = store.client() ?: return false
        if (store.speedUnsent(id)) { runCatching { client.putSpeed(id, store.speed(id)) }.onSuccess { store.speedSent(id) }; return false }
        val remote = runCatching { client.speed(id) }.getOrNull() ?: return false
        if (remote == store.speed(id)) return false
        store.speed(id, remote, local = false)
        return true
    }
}

/**
 * Wi-Fi or LTE for the heavy traffic. Beside its phone a watch sends everything through the phone's Bluetooth, which is
 * too slow for an audiobook; while a book downloads or streams, the watch asks for a network of its own and the whole
 * app uses it. With neither to be had, the ordinary connection carries on. Away from the phone the watch is on Wi-Fi
 * or LTE already and this changes nothing.
 */
object Net {
    private val holders = HashSet<String>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized fun hold(context: Context, who: String) {
        holders.add(who)
        if (callback != null) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val made = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runCatching { manager.bindProcessToNetwork(network) } }
            override fun onLost(network: Network) { runCatching { manager.bindProcessToNetwork(null) } }
        }
        if (runCatching { manager.requestNetwork(request, made) }.isSuccess) callback = made
    }

    @Synchronized fun release(context: Context, who: String) {
        holders.remove(who)
        if (holders.isNotEmpty()) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
        runCatching { manager.bindProcessToNetwork(null) }
    }

    fun online(context: Context): Boolean = context.getSystemService(ConnectivityManager::class.java).activeNetwork != null
}
