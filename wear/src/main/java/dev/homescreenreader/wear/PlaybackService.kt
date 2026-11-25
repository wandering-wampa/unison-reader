package dev.homescreenreader.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.homescreenreader.ServerClient

/**
 * Plays an audiobook on the watch: from the files downloaded to it, or streamed from the server. The watch's own media
 * controls, its headphones' buttons and the app's player screen all drive this one player. The place is kept every
 * second, sent to the server every twenty seconds while playing and on every pause, and the time listened is queued
 * for the statistics, as on the phone.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private var book: WatchBook? = null
    private var listenStart = 0L
    private var lastSent = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        // Streamed audio carries this watch's key; a redirect from https to http (or back) is refused, since the request carries the sign-in.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
        val sources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme?.startsWith("http") == true && store.signedIn && spec.uri.toString().startsWith(ServerClient.normalize(store.serverUrl)))
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${store.token}", "X-Device" to store.deviceId))
            else spec
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(sources)).setSeekBackIncrementMs(15000).setSeekForwardIncrementMs(30000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            // Headphones that drop out stop the book rather than let it play on from the watch's speaker.
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlayingSnapshot = isPlaying
                    val b = book
                    if (isPlaying) { if (listenStart == 0L) listenStart = System.currentTimeMillis(); b?.let { store.listened(it.id, System.currentTimeMillis()) } }
                    else { closeListening(); persist(); sendPlace(); handler.postDelayed(releaseNet, 60_000) }
                    if (isPlaying) handler.removeCallbacks(releaseNet)
                }
                override fun onEvents(player: Player, events: Player.Events) { startingSnapshot = startingNow() }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("UnisonWatch", "Playback failed", error)
                    failure = if (error.errorCode in 2000..2999) "Couldn't reach the audiobook. Check the connection and try again." else "The audiobook couldn't be played (${error.errorCodeName})."
                }
            })
        }
        // The watch's media controls show these beside play and pause: 15 seconds back and 30 forward, not track jumps.
        val skipBackCommand = SessionCommand("unison.skip_back", Bundle.EMPTY)
        val skipForwardCommand = SessionCommand("unison.skip_forward", Bundle.EMPTY)
        val skipBack = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15).setDisplayName("Back 15 seconds").setSessionCommand(skipBackCommand).setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW).build()
        val skipForward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setDisplayName("Forward 30 seconds").setSessionCommand(skipForwardCommand).setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW).build()
        val hidden = Player.Commands.Builder().addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands { val b = super.getAvailableCommands().buildUpon(); for (i in 0 until hidden.size()) b.remove(hidden.get(i)); return b.build() }
            override fun isCommandAvailable(command: Int) = !hidden.contains(command) && super.isCommandAvailable(command)
        }
        session = MediaSession.Builder(this, sessionPlayer).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // This app, the system (its media controls, the notification, Bluetooth) and nothing else.
                val known = controller.packageName == packageName || controller.isTrusted || session.isMediaNotificationController(controller)
                if (!known) { android.util.Log.w("UnisonWatch", "Refused media controller ${controller.packageName}"); return MediaSession.ConnectionResult.reject() }
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(skipBackCommand).add(skipForwardCommand).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(playerCommands).setAvailableSessionCommands(sessionCommands).setMediaButtonPreferences(listOf(skipBack, skipForward)).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    "unison.skip_back" -> seek(position() - 15000)
                    "unison.skip_forward" -> seek(position() + 30000)
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            /** Play pressed on the headphones with nothing loaded: the book last listened to, from its place. */
            override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val selected = store.book(store.active)?.takeIf { it.tracks.isNotEmpty() && store.signedIn } ?: return Futures.immediateFailedFuture(IllegalStateException("No audiobook"))
                book = selected
                val (index, position) = startOf(selected, store.audio(selected.id))
                handler.post { if (book?.id == selected.id) player.setPlaybackSpeed(store.speed(selected.id)) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(itemsFor(selected), index, position))
            }
        }).setMediaButtonPreferences(listOf(skipBack, skipForward))
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        // Nothing in the app binds a controller (the screens drive the service with intents), so the session is added here;
        // without that Media3 never shows its notification, and the watch would have no media controls for the book.
        addSession(session)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setNotificationId(NOTIFICATION).setChannelId(CHANNEL).setChannelName(R.string.playback_channel).build()
                .apply { setSmallIcon(R.drawable.ic_unison_notification) }
        )
        handler.post(tick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val command = intent?.getStringExtra("command") ?: return result
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW))
        val selected = store.book(intent.getStringExtra("book") ?: store.active)
        // Android wants a foreground notification at once; this one is already a media notification, and Media3 replaces it.
        startForeground(
            NOTIFICATION,
            androidx.core.app.NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_unison_notification)
                .setContentTitle(selected?.title ?: getString(R.string.app_name)).setContentText("Audiobook")
                .setStyle(MediaStyleNotificationHelper.MediaStyle(session)).setOnlyAlertOnce(true).build(),
        )
        if (selected == null || selected.tracks.isEmpty() || !store.signedIn) {
            askedAt = 0
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val switched = book?.id != selected.id
        if (switched) {
            closeListening(); persist()
            // The new book's own place is read before the player changes, and the player is loaded already standing there.
            val (index, position) = startOf(selected, store.audio(selected.id))
            book = selected; store.active = selected.id
            player.setMediaItems(itemsFor(selected), index, position)
            player.prepare()
            player.setPlaybackSpeed(store.speed(selected.id))
        }
        when (command) {
            "play" -> { val position = intent.getLongExtra("position", -1); if (position >= 0) seek(position); start() }
            // A second press while the book is still starting takes the first one back.
            "toggle" -> if (!switched && (player.isPlaying || startingNow())) player.pause() else start()
            "pause" -> player.pause()
            "back" -> seek(position() - 15000)
            "forward" -> seek(position() + 30000)
            "seek" -> seek(intent.getLongExtra("position", 0))
            "speed" -> player.setPlaybackSpeed(store.speed(selected.id))
        }
        startingSnapshot = startingNow(); askedAt = 0
        persist()
        return START_NOT_STICKY
    }

    private fun startingNow() = player.playWhenReady && !player.isPlaying && player.playerError == null && player.playbackState == Player.STATE_BUFFERING

    /** Play. A player that stopped on an error ignores a plain play; it is prepared again first, so it plays or fails afresh and says so. */
    private fun start() {
        val b = book ?: return
        // A book that is not on the watch streams: over Wi-Fi or LTE, not through the phone's Bluetooth.
        if (!store.downloaded(b)) Net.hold(this, "play")
        handler.removeCallbacks(releaseNet)
        if (player.playerError != null) player.prepare()
        // At the very end, play starts the last chapter again rather than doing nothing.
        if (player.playbackState == Player.STATE_ENDED) seek(b.chapters.lastOrNull()?.start ?: 0)
        player.play()
    }

    private val releaseNet = Runnable { if (!player.isPlaying) Net.release(this, "play") }

    private fun closeListening() {
        val b = book
        if (b != null && listenStart > 0) store.queueSession(b.id, listenStart, System.currentTimeMillis())
        listenStart = 0
    }

    fun position(): Long {
        val b = book ?: return 0
        return b.tracks.take(player.currentMediaItemIndex.coerceAtLeast(0)).sumOf { it.duration } + player.currentPosition.coerceAtLeast(0)
    }

    private fun seek(position: Long) {
        val b = book ?: return
        val (index, within) = startOf(b, position)
        player.seekTo(index, within)
        persist()
    }

    private fun sendPlace() {
        val id = book?.id ?: return
        lastSent = SystemClock.elapsedRealtime()
        // With no connection the one try for this book is enough; with one, whatever else is owed goes too.
        kotlin.concurrent.thread { runCatching { if (Sync.push(this, id)) Sync.settle(this) } }
    }

    /** The book's tracks, each from its file on the watch when it is there, else from the server. */
    private fun itemsFor(b: WatchBook): List<MediaItem> {
        val client = store.client()
        val cover = store.cover(b.id).takeIf { it.isFile }?.let { Uri.fromFile(it) }
        return b.tracks.mapIndexed { i, t ->
            val file = store.trackFile(b.id, i)
            val uri = if (file.isFile) Uri.fromFile(file) else Uri.parse(client?.audioUrl(b.id, i) ?: "")
            MediaItem.Builder().setUri(uri).setMediaId(b.id + ":" + i).setMediaMetadata(
                MediaMetadata.Builder().setTitle(b.title).setArtist(b.narrator.ifBlank { b.author }).setAlbumTitle(b.author).setSubtitle(t.name)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).setIsBrowsable(false).setIsPlayable(true).setArtworkUri(cover).build()
            ).build()
        }
    }

    /** A place in book milliseconds as a track and a position within it. */
    private fun startOf(b: WatchBook, at: Long): Pair<Int, Long> {
        var position = at.coerceIn(0, (b.duration - 1).coerceAtLeast(0)); var index = 0
        while (index < b.tracks.lastIndex && position >= b.tracks[index].duration) { position -= b.tracks[index].duration; index++ }
        return index to position
    }

    /** True while the player is loaded with this book's tracks (not another's mid-switch, and not empty). */
    private fun holds(b: WatchBook) = player.currentMediaItem?.mediaId?.startsWith(b.id + ":") == true

    private fun persist() {
        val b = book ?: return
        if (!holds(b)) return
        store.audio(b.id, position())
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player.isPlaying) { persist(); if (SystemClock.elapsedRealtime() - lastSent > 20_000) sendPlace() }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        closeListening(); persist()
        handler.removeCallbacksAndMessages(null)
        Net.release(this, "play")
        session.release(); player.release()
        instance = null; isPlayingSnapshot = false; startingSnapshot = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 10
        private var instance: PlaybackService? = null
        @Volatile private var isPlayingSnapshot = false
        @Volatile private var startingSnapshot = false
        @Volatile private var askedAt = 0L
        @Volatile private var askedBook: String? = null
        @Volatile private var failure: String? = null

        fun playing() = isPlayingSnapshot
        fun playingBook() = instance?.book?.id
        /** True from the press that asks this book to play until sound comes, the start is taken back, or it fails. */
        fun starting(book: String): Boolean = (askedAt > 0 && askedBook == book && SystemClock.elapsedRealtime() - askedAt < 5000) || (instance?.book?.id == book && startingSnapshot)
        /** The player's place in book milliseconds, or -1 when no book is loaded. Main thread only. */
        fun livePosition(): Long = instance?.let { if (it.book == null) -1L else it.position() } ?: -1L
        /** Why playback last stopped by itself, to be said once. */
        fun takeFailure(): String? = failure?.also { failure = null }

        /** Signing out: playback stops and the service forgets its book at once, so nothing it does while shutting down is kept for the next person. */
        fun forget(context: Context) {
            instance?.let { s -> s.listenStart = 0; s.book = null; runCatching { s.player.stop(); s.player.clearMediaItems() } }
            isPlayingSnapshot = false; startingSnapshot = false; askedAt = 0
            context.stopService(Intent(context, PlaybackService::class.java))
        }

        fun send(context: Context, command: String, book: String, position: Long = -1) {
            when (command) {
                "play" -> { askedBook = book; askedAt = SystemClock.elapsedRealtime() }
                "toggle" -> if (playingBook() == book && (playing() || starting(book))) askedAt = 0 else { askedBook = book; askedAt = SystemClock.elapsedRealtime() }
                "pause" -> askedAt = 0
            }
            context.startForegroundService(Intent(context, PlaybackService::class.java).putExtra("command", command).putExtra("book", book).putExtra("position", position))
        }
    }
}
