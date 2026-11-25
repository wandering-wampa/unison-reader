package dev.homescreenreader

import android.app.*
import android.content.*
import android.os.*
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import org.json.JSONArray
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var db: Library
    private lateinit var state: ReadingState
    private val handler = Handler(Looper.getMainLooper())
    private var book: Book? = null

    // ---- Sleep timer: a length of time or the end of the chapter. The last seconds fade out; a shake while it runs
    // extends it (the length again, or to the end of the following chapter), and a shake within two minutes after it
    // stopped plays on with the same timer. A short buzz answers a shake, since it is likely dark.
    private var sleepAt = 0L              // elapsedRealtime deadline, length mode
    @Volatile private var sleepChapterEnd = -1L     // book milliseconds, chapter mode
    private var sleepLength = 0L          // the last timer chosen: a length in ms, or -1 for the end of the chapter
    @Volatile private var sleepEndedAt = 0L         // when the timer last stopped the audio, for shake to play on
    private var shakeWake: PowerManager.WakeLock? = null
    private var shakeListening = false
    private var shakeFirst = 0L; private var shakePeaks = 0; private var lastShake = 0L
    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / android.hardware.SensorManager.GRAVITY_EARTH
            if (g < 2.3f) return
            val now = SystemClock.elapsedRealtime()
            if (now - shakeFirst > 800) { shakeFirst = now; shakePeaks = 1 } else shakePeaks++
            if (shakePeaks >= 3 && now - lastShake > 2000) { lastShake = now; shakePeaks = 0; handler.post { onShake() } }
        }
    }

    private fun sleepActive() = sleepAt > 0 || sleepChapterEnd >= 0

    // What the screen (and tests) read, from any thread; refreshed on the player's thread at every tick and change.
    @Volatile private var sleepLeftShown = -1L
    @Volatile private var volumeShown = 1f
    private fun showSleep() { sleepLeftShown = sleepRemaining(); volumeShown = player.volume }

    /** Where the chapter playing at `pos` ends, in book milliseconds; a chapter starting within a second counts as this one's end. */
    private fun chapterEndAfter(pos: Long): Long { val b = book ?: return -1; return b.chapters.map { it.start }.filter { it > pos + 1000 }.minOrNull() ?: b.duration }

    /** Milliseconds of listening left on the timer (real time), or -1 with none. */
    private fun sleepRemaining(): Long = when {
        sleepAt > 0 -> (sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        sleepChapterEnd >= 0 -> ((sleepChapterEnd - position()) / player.playbackParameters.speed.coerceAtLeast(.1f)).toLong().coerceAtLeast(0)
        else -> -1
    }

    /** `length`: milliseconds, -1 for the end of the chapter, 0 for off. */
    private fun setSleep(length: Long) {
        sleepAt = 0; sleepChapterEnd = -1; sleepEndedAt = 0; player.volume = 1f
        when {
            length > 0 -> sleepAt = SystemClock.elapsedRealtime() + length
            length < 0 && book?.chapters?.isNotEmpty() == true -> sleepChapterEnd = chapterEndAfter(position())
        }
        if (sleepActive()) { sleepLength = length; listenForShake(true) } else listenForShake(false)
        showSleep()
    }

    private fun endSleep() {
        val chapterEnd = sleepChapterEnd
        player.pause(); player.volume = 1f
        // Stopped at a chapter's end, the next play starts that chapter from its first word.
        if (chapterEnd >= 0) seek(chapterEnd)
        sleepAt = 0; sleepChapterEnd = -1
        sleepEndedAt = SystemClock.elapsedRealtime()
        // Listen for a shake a little longer, with the CPU kept awake for it; the player's own wake lock is gone.
        shakeWake?.let { if (it.isHeld) it.release() }
        shakeWake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unison:sleep-shake").apply { acquire(SHAKE_GRACE_MS) }
        handler.postDelayed({ if (!sleepActive() && sleepEndedAt > 0 && SystemClock.elapsedRealtime() - sleepEndedAt >= SHAKE_GRACE_MS - 50) { sleepEndedAt = 0; listenForShake(false) } }, SHAKE_GRACE_MS)
        persist()
        showSleep()
    }

    private fun onShake() {
        when {
            sleepActive() -> {
                if (sleepAt > 0) sleepAt = SystemClock.elapsedRealtime() + sleepLength
                else sleepChapterEnd = chapterEndAfter(sleepChapterEnd)
                player.volume = 1f
            }
            sleepEndedAt > 0 && SystemClock.elapsedRealtime() - sleepEndedAt < SHAKE_GRACE_MS -> {
                sleepEndedAt = 0
                book?.id?.let { state.follow(it, true) }
                player.play()
                setSleep(sleepLength)
            }
            else -> return
        }
        showSleep()
        buzz()
    }

    private fun buzz() = runCatching {
        getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun listenForShake(on: Boolean) {
        if (on == shakeListening) return
        val sensors = getSystemService(android.hardware.SensorManager::class.java)
        if (on) sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { shakeListening = sensors.registerListener(shakeListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        else { sensors.unregisterListener(shakeListener); shakeListening = false; shakeWake?.let { if (it.isHeld) it.release() }; shakeWake = null }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Library(this)
        state = ReadingState(this)
        // Streamed server audio carries the server token and device name; local files pass straight through.
        // A redirect from https to http (or back) is refused: the request carries this person's sign-in.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
        val sources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme?.startsWith("http") == true && state.serverUrl.isNotBlank() && spec.uri.toString().startsWith(ServerClient.normalize(state.serverUrl)))
                spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${state.serverToken}", "X-Device" to state.deviceId))
            else spec
        }
        player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(sources))
                .setSeekBackIncrementMs(15000)
                .setSeekForwardIncrementMs(30000)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        true,
                    )
                    setHandleAudioBecomingNoisy(true)
                    setWakeMode(C.WAKE_MODE_LOCAL)
                    addListener(
                        object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                isPlayingSnapshot = isPlaying
                                if (isPlaying) { if (listenStart == 0L) listenStart = System.currentTimeMillis(); book?.id?.let { state.listened(it, System.currentTimeMillis()) } } else { closeListening(); persist(); sendPlace() }
                                // Played on by hand after the timer stopped it: the shake window closes.
                                if (isPlaying && !sleepActive() && sleepEndedAt > 0) { sleepEndedAt = 0; listenForShake(false) }
                                persist()
                            }

                            // Told to play and not sounding yet (the book is opening, the stream is filling): the play
                            // buttons show that. Held back by the system (a call, say), the player is ready, not this.
                            override fun onEvents(player: Player, events: Player.Events) { startingSnapshot = startingNow() }

                            override fun onPlayerError(error: PlaybackException) {
                                android.util.Log.e("ReaderAudio", "Playback failed", error)
                                val network = error.errorCode in 2000..2999
                                failure = if (network) "Couldn't reach the audiobook. Check your connection and try again." else "The audiobook couldn't be played (${error.errorCodeName})."
                            }
                        }
                    )
                }
        // The shade, lock screen and watches show these beside play/pause: skips, not track jumps.
        // The system's media card (Android 13 and later) shows play/pause plus custom actions, and would
        // show previous/next track if the player offered them. An audiobook wants 15 s back and 30 s
        // forward there, as session commands with icons, and no track jumps.
        val skipBackCommand = SessionCommand("unison.skip_back", Bundle.EMPTY)
        val skipForwardCommand = SessionCommand("unison.skip_forward", Bundle.EMPTY)
        val skipBack = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15).setDisplayName("Back 15 seconds").setSessionCommand(skipBackCommand).setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW).build()
        val skipForward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setDisplayName("Forward 30 seconds").setSessionCommand(skipForwardCommand).setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW).build()
        val sessionPlayer = object : ForwardingPlayer(player) {
            private val hidden = Player.Commands.Builder().addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
            override fun getAvailableCommands(): Player.Commands { val b = super.getAvailableCommands().buildUpon(); for (i in 0 until hidden.size()) b.remove(hidden.get(i)); return b.build() }
            override fun isCommandAvailable(command: Int) = !hidden.contains(command) && super.isCommandAvailable(command)
        }
        session =
            MediaLibrarySession.Builder(this, sessionPlayer,
                    object : MediaLibrarySession.Callback {
                        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                            // Titles, progress and the play button are for this app, the system (the notification, the
                            // lock screen, Bluetooth, apps the person has given notification access) and the car and
                            // watch apps; any other installed app is turned away.
                            val known = controller.packageName == packageName || controller.isTrusted || session.isMediaNotificationController(controller) ||
                                session.isAutoCompanionController(controller) || session.isAutomotiveController(controller) || controller.packageName in CONTROLLERS
                            if (!known) { android.util.Log.w("ReaderAudio", "Refused media controller ${controller.packageName}"); return MediaSession.ConnectionResult.reject() }
                            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                .removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                                .build()
                            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(skipBackCommand).add(skipForwardCommand).build()
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                                .setAvailablePlayerCommands(playerCommands)
                                .setAvailableSessionCommands(sessionCommands)
                                .setMediaButtonPreferences(listOf(skipBack, skipForward))
                                .build()
                        }
                        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
                            when (customCommand.customAction) {
                                "unison.skip_back" -> seek(position() - 15000)
                                "unison.skip_forward" -> seek(position() + 30000)
                                else -> return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                            }
                            return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        override fun onPlaybackResumption(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): com.google.common.util.concurrent.ListenableFuture<
                            MediaSession.MediaItemsWithStartPosition
                        > {
                            val selected = db.load(state.active)
                            if (selected == null || selected.tracks.isEmpty())
                                return com.google.common.util.concurrent.Futures
                                    .immediateFailedFuture(
                                        IllegalStateException("Import audio first")
                                    )
                            book = selected
                            val (index, position) = startOf(selected, state.audio(selected.id))
                            // Resumed after a restart: the book's own speed, as when it is started from the app.
                            handler.post { if (book?.id == selected.id) player.setPlaybackSpeed(state.speed(selected.id)) }
                            return com.google.common.util.concurrent.Futures.immediateFuture(
                                MediaSession.MediaItemsWithStartPosition(itemsFor(selected), index, position)
                            )
                        }

                        // ---- The library a car (Android Auto) or another media browser sees: My shelf, Recent, Series.
                        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
                            com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Unison"), params))
                        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): com.google.common.util.concurrent.ListenableFuture<LibraryResult<MediaItem>> {
                            val item = when {
                                mediaId.startsWith("book:") -> db.load(mediaId.removePrefix("book:"))?.takeIf { it.tracks.isNotEmpty() }?.let(::bookItem)
                                else -> folder(mediaId, mediaId.substringAfter(':'))
                            }
                            return com.google.common.util.concurrent.Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                        }
                        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): com.google.common.util.concurrent.ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
                            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>()
                            // Off the main thread: covers are read and scaled, and Series asks the server once.
                            kotlin.concurrent.thread {
                                val items = runCatching { children(parentId) }.getOrDefault(emptyList())
                                future.set(LibraryResult.ofItemList(items.drop(page * pageSize).take(pageSize), params))
                            }
                            return future
                        }
                        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): com.google.common.util.concurrent.ListenableFuture<LibraryResult<Void>> {
                            kotlin.concurrent.thread { session.notifySearchResultChanged(browser, query, search(query).size, params) }
                            return com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofVoid())
                        }
                        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): com.google.common.util.concurrent.ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
                            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>()
                            kotlin.concurrent.thread { future.set(LibraryResult.ofItemList(runCatching { search(query).map(::bookItem) }.getOrDefault(emptyList()).drop(page * pageSize).take(pageSize), params)) }
                            return future
                        }
                        /** A book chosen in the car (or asked for by voice) becomes its tracks, at the place it was left. */
                        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                            val asked = mediaItems.firstOrNull()
                            val id = asked?.mediaId?.takeIf { it.startsWith("book:") }?.removePrefix("book:")
                                ?: asked?.requestMetadata?.searchQuery?.let { q -> search(q).firstOrNull()?.id ?: state.active.takeIf { q.isBlank() } }
                                ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                            val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                            kotlin.concurrent.thread {
                                val selected = db.load(id)?.takeIf { it.tracks.isNotEmpty() }
                                if (selected == null) { future.setException(IllegalStateException("No audiobook")); return@thread }
                                // Nobody can be asked which place to keep while driving: a newer place from another device is taken
                                // only when this device has not moved since; places far apart keep this device's.
                                if (state.serverWork(id) != null) runCatching { (Server.sync(this@PlaybackService, id) as? Server.Pulled.Newer)?.let { Server.keepRemote(this@PlaybackService, id, it.remote) } }
                                handler.post {
                                    closeListening(); persist()
                                    book = selected; state.active = selected.id; state.follow(selected.id, true); state.listened(selected.id, System.currentTimeMillis())
                                    val (index, position) = startOf(selected, state.audio(selected.id))
                                    future.set(MediaSession.MediaItemsWithStartPosition(itemsFor(selected), index, position))
                                    handler.post { if (book?.id == selected.id) player.setPlaybackSpeed(state.speed(selected.id)) }
                                    kotlin.concurrent.thread { if (Server.pullSpeed(this@PlaybackService, id)) handler.post { if (book?.id == id) player.setPlaybackSpeed(state.speed(id)) } }
                                }
                            }
                            return future
                        }
                    }
                )
                .setMediaButtonPreferences(listOf(skipBack, skipForward))
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
        // The system's media controls (shade, lock screen, watch) come from this: the Unison icon,
        // the playback channel, the book's cover and title, play/pause and the 15 s / 30 s skips.
        // Nothing in the app binds a controller to this session (the UI drives the service with intents), so
        // the session is registered here; without it Media3 never attaches its notification manager and
        // the shade would show a bare notification instead of the media card with skips.
        addSession(session)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setNotificationId(10).setChannelId("playback").setChannelName(R.string.playback_channel).build()
                .apply { setSmallIcon(R.drawable.ic_unison_notification) }
        )
        handler.post(tick)
    }

    /** What the system shows for a track: the book, who reads it, the author, the cover and the chapter. */
    private fun metadataFor(selected: Book, track: Track): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(selected.title)
            .setArtist(selected.narrator.ifBlank { selected.author })
            .setAlbumTitle(selected.author)
            .setSubtitle(track.name)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(selected.cover.takeIf { it.isNotBlank() }?.let { android.net.Uri.fromFile(java.io.File(it)) })
            .build()

    // ---- The browse tree. Only books with audio that are on this device (streamed or downloaded) can be played from it.
    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build()).build()

    private val thumbs = HashMap<String, ByteArray?>()
    /** The cover as a small JPEG: a browser in another process cannot open the app's own files. */
    private fun thumb(b: Book): ByteArray? = synchronized(thumbs) {
        thumbs.getOrPut(b.id) {
            runCatching {
                val path = b.cover.takeIf { it.isNotBlank() } ?: return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
                val bitmap = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
                java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it); bitmap.recycle() }.toByteArray()
            }.getOrNull()
        }
    }

    private fun bookItem(b: Book): MediaItem {
        val left = (b.duration - state.audio(b.id)).coerceAtLeast(0) / 60000
        val progress = when {
            state.finished(b.id) > 0 -> "Finished"
            state.audio(b.id) <= 0 -> "Not started"
            else -> (if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left")
        }
        return MediaItem.Builder().setMediaId("book:${b.id}").setMediaMetadata(
            MediaMetadata.Builder().setTitle(b.title).setArtist(listOf(b.author, progress).filter { it.isNotBlank() }.joinToString(" · ")).setAlbumTitle(b.author)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).setIsBrowsable(false).setIsPlayable(true)
                .apply { thumb(b)?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }.build()
        ).build()
    }

    private fun audiobooks() = db.books().filter { it.tracks.isNotEmpty() }
    @Volatile private var seriesOf: Map<String, List<ServerClient.SeriesRef>>? = null
    /** Series of the server books kept here, asked from the server once per run of the service. */
    private fun seriesMap(): Map<String, List<ServerClient.SeriesRef>> {
        seriesOf?.let { return it }
        val fresh = Server.client(state)?.let { client -> runCatching { client.works().filter { it.series.isNotEmpty() }.associate { Server.bookId(it.id) to it.series } }.getOrNull() }
        if (fresh != null) {
            seriesOf = fresh
            // Kept for the road: without a connection the Series tab shows what was known last time.
            state.seriesCache = JSONObject().apply { for ((id, list) in fresh) put(id, JSONArray(list.map { JSONObject().put("name", it.name).put("sequence", it.sequence ?: JSONObject.NULL) })) }.toString()
            return fresh
        }
        return runCatching {
            val o = JSONObject(state.seriesCache)
            o.keys().asSequence().associateWith { id -> val a = o.getJSONArray(id); List(a.length()) { i -> a.getJSONObject(i).let { ServerClient.SeriesRef(it.getString("name"), if (it.isNull("sequence")) null else it.getDouble("sequence")) } } }
        }.getOrDefault(emptyMap())
    }

    private fun children(parent: String): List<MediaItem> = when {
        parent == ROOT -> listOf(folder("shelf", "My shelf"), folder("recent", "Recent"), folder("series", "Series"))
        parent == "shelf" -> {
            val shelf = state.shelf().toSet()
            audiobooks().filter { it.id in shelf && state.finished(it.id) == 0L }
                .sortedWith(compareByDescending<Book> { it.id == state.active }.thenByDescending { maxOf(state.listened(it.id), state.shelvedAt(it.id)) }).map(::bookItem)
        }
        parent == "recent" -> audiobooks().filter { state.listened(it.id) > 0 || state.audio(it.id) > 0 }
            .sortedWith(compareByDescending<Book> { state.listened(it.id) }.thenByDescending { it.id == state.active }).take(20).map(::bookItem)
        parent == "series" -> { val here = audiobooks().map { it.id }.toSet(); seriesMap().filterKeys { it in here }.values.flatten().map { it.name }.distinct().sorted().map { folder("series:$it", it) } }
        parent.startsWith("series:") -> {
            val name = parent.removePrefix("series:"); val map = seriesMap()
            audiobooks().filter { b -> map[b.id]?.any { it.name == name } == true }.sortedBy { b -> map[b.id]?.firstOrNull { it.name == name }?.sequence ?: Double.MAX_VALUE }.map(::bookItem)
        }
        else -> emptyList()
    }

    /** Books whose title or author holds every word asked for; an empty question means the book last played. */
    private fun search(query: String): List<Book> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return listOfNotNull(audiobooks().firstOrNull { it.id == state.active })
        return audiobooks().filter { b -> val hay = (b.title + " " + b.author).lowercase(); words.all { it in hay } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val command = intent?.getStringExtra("command") ?: return result
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("playback", getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // Android needs a foreground notification at once; this one is already a media notification, so
        // the shade shows controls even before the player reports a state and Media3 replaces it.
        startForeground(
            10,
            androidx.core.app.NotificationCompat.Builder(this, "playback")
                .setSmallIcon(R.drawable.ic_unison_notification)
                .setContentTitle(db.load(intent.getStringExtra("book") ?: state.active)?.title ?: "UnisonReader")
                .setContentText("Audiobook")
                .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
                .setOnlyAlertOnce(true)
                .build(),
        )
        val selected = db.load(intent.getStringExtra("book") ?: state.active)
        if (selected == null || selected.tracks.isEmpty()) {
            askedAt = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val switched = book?.id != selected.id
        if (switched) {
            closeListening()
            persist()
            // The new book's own place is read before anything about the player changes, and the player is loaded
            // already standing there: replacing a book that is playing stops it, and that stop must never be saved
            // as the new book's position (it used to reset the new book to its beginning).
            val (index, position) = startOf(selected, state.audio(selected.id))
            book = selected
            state.active = selected.id
            player.setMediaItems(itemsFor(selected), index, position)
            player.prepare()
            player.setPlaybackSpeed(state.speed(selected.id))
            // The book's speed may have been changed on the web; it is applied when it arrives.
            val id = selected.id
            kotlin.concurrent.thread { if (Server.pullSpeed(this, id)) handler.post { if (book?.id == id) player.setPlaybackSpeed(state.speed(id)) } }
        }
        when (command) {
            "play" -> {
                val position = intent.getLongExtra("position", -1)
                if (position >= 0) seek(position)
                state.follow(selected.id, true)
                start()
            }
            // A second press while the book is still starting takes the first one back; a book just loaded in place of
            // another starts, whatever the other was doing.
            "toggle" -> if (!switched && (player.isPlaying || startingNow())) player.pause() else start()
            "pause" -> player.pause()
            "back" -> seek(position() - 15000)
            "forward" -> seek(position() + 30000)
            "seek" -> seek(intent.getLongExtra("position", 0))
            "speed" -> player.setPlaybackSpeed(state.speed(selected.id))
            // minutes: 0 off, -1 the end of the chapter; sleepMs (tests) a length in milliseconds.
            "sleep" -> intent.getLongExtra("sleepMs", 0).let { ms -> setSleep(if (ms > 0) ms else intent.getLongExtra("minutes", 0).let { if (it > 0) it * 60000 else it }) }
            "shake" -> onShake()
        }
        // From here on the player itself says whether it is starting; the press no longer has to.
        startingSnapshot = startingNow(); askedAt = 0
        persist()
        return START_NOT_STICKY
    }

    private fun startingNow() = player.playWhenReady && !player.isPlaying && player.playerError == null && player.playbackState == Player.STATE_BUFFERING

    // The wall-clock stretch the player has been playing; queued as one listening session when it stops.
    private var listenStart = 0L
    private fun closeListening() {
        val b = book ?: run { listenStart = 0; return }
        if (listenStart > 0) { state.serverWork(b.id)?.let { state.queueSession(it, "listen", listenStart, System.currentTimeMillis()) } }
        listenStart = 0
    }

    fun position(): Long {
        val b = book ?: return 0
        return b.tracks.take(player.currentMediaItemIndex.coerceAtLeast(0)).sumOf { it.duration } +
            player.currentPosition
    }

    private fun seek(position: Long) {
        val b = book ?: return
        var remainder = position.coerceIn(0, (b.duration - 1).coerceAtLeast(0))
        for ((i, t) in b.tracks.withIndex()) {
            if (remainder < t.duration) {
                player.seekTo(i, remainder)
                return
            }
            remainder -= t.duration
        }
    }

    private var lastSent = 0L

    /**
     * The place to the server, every twenty seconds while playing and on pause, so listening from the player sheet or
     * the lock screen reaches the other devices without the book's page being open. Guarded like every save: another
     * device's newer place far from this one is never overwritten unless this book is playing here.
     */
    private fun sendPlace() {
        val id = book?.id ?: return
        if (state.serverWork(id) == null) return
        lastSent = SystemClock.elapsedRealtime()
        kotlin.concurrent.thread { runCatching { Server.push(this, id) } }
    }

    /** A book's tracks as the player's items; each carries the book's id, which is how a save knows whose position it holds. */
    private fun itemsFor(b: Book) = b.tracks.map { t -> MediaItem.Builder().setUri(t.uri).setMediaId(b.id + ":" + t.name).setMediaMetadata(metadataFor(b, t)).build() }

    /** A place in book milliseconds as a track and a position within it. */
    private fun startOf(b: Book, at: Long): Pair<Int, Long> {
        var position = at.coerceIn(0, (b.duration - 1).coerceAtLeast(0)); var index = 0
        while (index < b.tracks.lastIndex && position >= b.tracks[index].duration) { position -= b.tracks[index].duration; index++ }
        return index to position
    }

    /** True while the player is loaded with this book's tracks (not another book's, mid-switch, and not empty). */
    private fun holds(b: Book) = player.currentMediaItem?.mediaId?.startsWith(b.id + ":") == true

    /**
     * Play. A player that stopped on an error (the stream could not be reached) ignores a plain play, which made the
     * button do nothing; it is prepared again first, so it either plays or fails afresh and says so.
     */
    private fun start() {
        if (player.playerError != null) player.prepare()
        player.play()
    }

    private fun persist() {
        val b = book ?: return
        // Mid-switch the player may still hold the previous book, or nothing: its position is not this book's.
        if (!holds(b)) return
        val pos = position()
        val a = db.atTime(b.id, pos)
        state.audio(b.id, pos, a?.passage)
    }

    private val tick =
        object : Runnable {
            override fun run() {
                if (sleepActive() && player.isPlaying) {
                    val left = sleepRemaining()
                    if (left <= 0) endSleep() else player.volume = if (left < FADE_MS) (left.toFloat() / FADE_MS).coerceIn(.05f, 1f) else 1f
                }
                if (player.isPlaying) { persist(); if (SystemClock.elapsedRealtime() - lastSent > 20_000) sendPlace() }
                showSleep()
                // Quarter-second steps while a timer runs, so a chapter end is met within a word or two.
                handler.postDelayed(this, if (sleepActive()) 250 else 1000)
            }
        }

    override fun onDestroy() {
        closeListening()
        persist()
        listenForShake(false)
        handler.removeCallbacksAndMessages(null)
        session.release()
        player.release()
        db.close()
        instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: PlaybackService? = null
        @Volatile private var isPlayingSnapshot = false

        fun playing() = isPlayingSnapshot

        fun playingBook() = instance?.book?.id

        @Volatile private var startingSnapshot = false
        @Volatile private var askedAt = 0L
        @Volatile private var askedBook: String? = null

        /**
         * True from the press that asks this book to play until sound comes, the start is taken back, or it fails: the
         * play buttons show it, so a press is seen at once while a stream takes its seconds to fill. The press itself
         * counts until the service has acted on it (five seconds at most); after that the player says.
         */
        fun starting(book: String): Boolean =
            (askedAt > 0 && askedBook == book && SystemClock.elapsedRealtime() - askedAt < 5000) || (instance?.book?.id == book && startingSnapshot)

        /** Apps that may browse and control playback besides this one and the system: Android Auto and its simulator, the Wear OS and Pixel Watch apps, Google Assistant, Bluetooth. */
        private val CONTROLLERS = setOf(
            "com.google.android.projection.gearhead", "com.google.android.autosimulator", "com.google.android.carassistant",
            "com.google.android.wearable.app", "com.google.android.apps.wear.companion", "com.google.android.googlequicksearchbox", "com.android.bluetooth", "com.google.android.bluetooth",
            "com.android.systemui",
        )

        /** Why playback last stopped by itself, for the screen to say once; null when there is nothing to say. */
        @Volatile private var failure: String? = null
        fun takeFailure(): String? = failure?.also { failure = null }

        /**
         * Another person is signing in: playback stops and the service forgets its book now, on the caller's (main)
         * thread, so nothing it does while shutting down (a listening session, a last save of the place) is recorded
         * under the new account.
         */
        fun forget() {
            val s = instance ?: return
            s.listenStart = 0
            s.book = null
            runCatching { s.player.stop(); s.player.clearMediaItems() }
            isPlayingSnapshot = false
        }

        private const val ROOT = "root"
        private const val FADE_MS = 6000L
        private const val SHAKE_GRACE_MS = 120_000L

        /** Listening left on the sleep timer in ms, or -1 with none. */
        fun sleepLeft(): Long = instance?.sleepLeftShown ?: -1L
        /** True when the timer runs to a chapter's end. */
        fun sleepByChapter(): Boolean = instance?.let { it.sleepChapterEnd >= 0 } == true
        /** True while a shake would play on after the timer stopped the audio. */
        fun sleepJustEnded(): Boolean = instance?.let { it.sleepEndedAt > 0 } == true
        fun volume(): Float = instance?.volumeShown ?: 1f

        /** The player's current position in book milliseconds, or -1 when no book is loaded. */
        fun livePosition(): Long = instance?.let { if (it.book == null) -1L else it.position() } ?: -1L

        fun send(
            context: Context,
            command: String,
            book: String = ReadingState(context).active,
            position: Long = -1,
            minutes: Long = 0,
            sleepMs: Long = 0,
        ) {
            when (command) {
                "play" -> { askedBook = book; askedAt = SystemClock.elapsedRealtime() }
                "toggle" -> if (playingBook() == book && (playing() || starting(book))) askedAt = 0 else { askedBook = book; askedAt = SystemClock.elapsedRealtime() }
                "pause" -> askedAt = 0
            }
            context.startForegroundService(
                Intent(context, PlaybackService::class.java)
                    .putExtra("command", command)
                    .putExtra("book", book)
                    .putExtra("position", position)
                    .putExtra("minutes", minutes)
                    .putExtra("sleepMs", sleepMs)
            )
        }
    }
}
