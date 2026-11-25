package dev.homescreenreader

import android.app.*
import android.content.Intent
import android.net.Uri
import java.util.concurrent.Executors

class ImportService : Service() {
    private val queue = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (busy) return START_NOT_STICKY
        val uris = intent?.getStringArrayListExtra("uris") ?: return START_NOT_STICKY
        val attach = intent.getStringExtra("attach")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("imports", "Library imports", NotificationManager.IMPORTANCE_LOW)
        )
        fun show(text: String) {
            status = text
            manager.notify(
                43,
                Notification.Builder(this, "imports")
                    .setSmallIcon(R.drawable.ic_unison_notification)
                    .setContentTitle("Importing into your library")
                    .setContentText(text)
                    .setOngoing(true)
                    .build(),
            )
        }
        startForeground(
            43,
            Notification.Builder(this, "imports")
                .setSmallIcon(R.drawable.ic_unison_notification)
                .setContentTitle("Importing book")
                .build(),
        )
        busy = true
        // Persist the request before any file mutation. A killed process leaves sources intact
        // until commit.
        val pending = getSharedPreferences("import-job", 0)
        pending
            .edit()
            .putString("uris", org.json.JSONArray(uris).toString())
            .putString("attach", attach)
            .commit()
        queue.execute {
            try {
                var target = attach
                for ((index, uri) in uris.withIndex()) {
                    val recovered =
                        if (intent.getBooleanExtra("resume", false))
                            Library(this).use { db -> db.committedSource(uri)?.let { db.load(it) } }
                        else null
                    val result =
                        if (recovered != null)
                            Importer.Result(
                                recovered,
                                "Recovered verified import. Review original-file cleanup in Settings.",
                            )
                        else Importer.import(this, Uri.parse(uri), target, ::show)
                    target = result.book.id
                    status = result.message
                    // A book that now has both text and audio starts syncing right away.
                    Library(this).use { db -> Alignment.autoStart(this, db.load(result.book.id)) }
                    pending
                        .edit()
                        .putString("uris", org.json.JSONArray(uris.drop(index + 1)).toString())
                        .putString("attach", target)
                        .commit()
                }
                pending.edit().clear().commit()
            } catch (e: Exception) {
                status = "Import paused: ${e.message}. Original kept unless already committed."
            } finally {
                busy = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        @Volatile var busy = false
        @Volatile var status = ""
    }
}
