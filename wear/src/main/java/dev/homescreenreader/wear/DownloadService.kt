package dev.homescreenreader.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Puts audiobooks on the watch, one at a time, so they play with no connection. It keeps going with the screen off, asks
 * for Wi-Fi or LTE rather than the phone's Bluetooth, and a dropped connection costs only what had not arrived: each
 * track is picked up where it stopped (three more tries, a little further apart each time, before it gives up and says so).
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notice("Downloading", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id = intent?.getStringExtra("book")
        synchronized(queue) {
            if (id != null) {
                if (intent.getBooleanExtra("cancel", false)) { queue.remove(id); if (current == id) stopCurrent = true }
                else if (id !in queue) { queue.add(id); problems.remove(id) }
            }
            // One worker at a time; it gives the role up (under this lock) only when it finds nothing left to do.
            if (worker == null) worker = kotlin.concurrent.thread(name = "unison-download") { work() }
        }
        return START_NOT_STICKY
    }

    private fun notice(text: String, percent: Int): Notification =
        Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_unison_notification).setContentTitle(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun work() {
        val store = Store(this)
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unison:download")
        try {
            while (true) {
                val id = synchronized(queue) { queue.firstOrNull().also { current = it; stopCurrent = false; fraction = 0f; if (it == null) worker = null } } ?: break
                Net.hold(this, "download")
                // Long enough for a long book on a slow line; let go as soon as the book is done.
                wake.acquire(4 * 3600_000L)
                val failed = runCatching { fetch(store, id) }.exceptionOrNull()
                if (wake.isHeld) wake.release()
                synchronized(queue) {
                    if (failed != null) problems[id] = said(failed)
                    queue.remove(id); current = null
                }
            }
        } finally {
            if (wake.isHeld) wake.release()
            // Unless another book was asked for in this very moment (a new worker has the role then), the service is done.
            synchronized(queue) { if (worker === Thread.currentThread()) worker = null; if (worker == null) { Net.release(this, "download"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
    }

    private fun said(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "ENOSPC" in m || "No space" in m -> "Not enough room on the watch"
            m.startsWith("Server answered 401") -> "Sign in again"
            m.startsWith("Server answered") -> "The server refused the download"
            else -> "The connection dropped. Try again."
        }
    }

    private fun fetch(store: Store, id: String) {
        val client = store.client() ?: error("Server answered 401")
        val book = store.book(id) ?: return
        val manager = getSystemService(NotificationManager::class.java)
        val total = book.duration.coerceAtLeast(1)
        var before = 0L; var shown = -1
        for ((i, track) in book.tracks.withIndex()) {
            val file = store.trackFile(id, i)
            if (!file.isFile) {
                var tries = 0
                while (true) {
                    val done = runCatching {
                        client.resume("/api/works/$id/audio/$i", file, stop = { stopCurrent }) { got, size ->
                            if (size > 0) fraction = ((before + track.duration * (got.toDouble() / size)) / total).toFloat().coerceIn(0f, 1f)
                            val percent = (fraction * 100).toInt()
                            if (percent != shown) { shown = percent; manager.notify(NOTIFICATION, notice(book.title, percent)) }
                        }
                    }
                    if (done.isSuccess) { if (done.getOrThrow()) break else return }
                    val e = done.exceptionOrNull()!!
                    // A refusal will be the same next time; a dropped line may not be.
                    if (e.message?.startsWith("Server answered") == true || "ENOSPC" in e.message.orEmpty() || ++tries > 3) throw e
                    Thread.sleep(tries * 8000L)
                    if (stopCurrent) return
                }
            }
            before += track.duration
            fraction = (before.toDouble() / total).toFloat()
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 11
        private val queue = ArrayList<String>()
        private val problems = HashMap<String, String>()
        private var worker: Thread? = null
        @Volatile private var current: String? = null
        @Volatile private var stopCurrent = false
        @Volatile private var fraction = 0f

        /** How far a book's download is, 0 to 1; -1 when it is waiting its turn; null when it is not being downloaded. */
        fun progress(id: String): Float? = synchronized(queue) { if (current == id) fraction else if (id in queue) -1f else null }
        /** Why a book's last download stopped, to be said once. */
        fun takeProblem(id: String): String? = synchronized(queue) { problems.remove(id) }

        /** Signing out: nothing more is fetched, and the track in hand stops at its next block. */
        fun cancelAll() { synchronized(queue) { queue.clear(); problems.clear(); stopCurrent = true } }

        fun start(context: Context, id: String) { context.startForegroundService(Intent(context, DownloadService::class.java).putExtra("book", id)) }
        /** Stops a book's download; what has arrived of the track in hand stays for the next try, until the audio is removed. */
        fun cancel(context: Context, id: String) { if (progress(id) != null) context.startForegroundService(Intent(context, DownloadService::class.java).putExtra("book", id).putExtra("cancel", true)) }
    }
}
