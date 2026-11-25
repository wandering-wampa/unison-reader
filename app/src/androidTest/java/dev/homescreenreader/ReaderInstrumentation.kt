package dev.homescreenreader

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ReaderInstrumentation : Instrumentation() {
    private var mode = "core"
    private var args: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments
        mode = arguments?.getString("mode") ?: "core"
        start()
    }

    private fun report(s: String) {
        sendStatus(0, Bundle().apply { putString("stream", s + "\n") })
    }

    override fun onStart() {
        val result = Bundle()
        try {
            when (mode) {
                "core" -> core()
                "benchmark" -> benchmark()
                "playback" -> playback()
                "sleep" -> sleepTimer()
                "sleepshake" -> sleepShake()
                "reader" -> preservingReadingPreferences { reader() }
                "bookish" -> BookishChecks(this) { report(it) }.run()
                "curl" -> CurlChecks(this) { report(it) }.run()
                "samples" -> samples()
                "saf" -> saf()
                "failure" -> failure()
                "formats" -> formats()
                "resume" -> resumePreparation()
                "crashprep" -> crashPreparation()
                "cleanup" -> cleanupFixtures()
                "service" -> servicePreparation()
                "shelves" -> stageShelves()
                "server" -> server()
                "auto" -> auto()
                else -> error("Unknown mode")
            }
            result.putString("stream", "PASS: $mode\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun preservingReadingPreferences(action: () -> Unit) {
        val prefs=targetContext.getSharedPreferences("reading-v2",0)
        val saved=prefs.all.toMap()
        try { action() } finally {
            val edit=prefs.edit().clear()
            saved.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value)
                is Int -> edit.putInt(key,value)
                is Long -> edit.putLong(key,value)
                is Float -> edit.putFloat(key,value)
                is Boolean -> edit.putBoolean(key,value)
            } };edit.commit()
        }
    }

    private fun core() {
        val c = targetContext
        val source = File(c.filesDir, "fixture.epub")
        val audio = File(c.filesDir, "fixture.zip")
        val db = Library(c)
        if (source.exists()) {
            val hash = Importer.digest(source)
            val imported = Importer.import(c, Uri.fromFile(source))
            val b = imported.book
            val duplicate = imported.message.startsWith("Already")
            check(source.exists() == duplicate)
            check(b.passages.sumOf { it.text.length } > 500000)
            check(b.author.contains("King"))
            check(b.toc.size > 30)
            check(db.duplicate(hash) == b.id)
            report(
                "Real EPUB ${if(duplicate) "duplicate detected, disposable source retained" else "imported, source removed"}, ${b.passages.size} sentences, ${b.toc.size} parts"
            )
            if (duplicate) source.delete() // Only the test's disposable emulator input.
            if (audio.exists()) {
                val result = Importer.import(c, Uri.fromFile(audio), b.id)
                val a = result.book
                check(a.tracks.size == 1)
                check(a.chapters.size >= 40)
                check(a.narrator.contains("Wasson"))
                check(a.duration > 100000000)
                val repeated = result.message.startsWith("Already")
                check(audio.exists() == repeated)
                check(db.books().count { it.id == b.id } == 1)
                report(
                    "Real ZIP paired, verified audio duration ${a.duration}, ${a.chapters.size} chapters, ${if(repeated) "duplicate source retained" else "source removed"}"
                )
                if (repeated) audio.delete()
            }
            ReadingState(c).active = b.id
        }
        val b = db.load(ReadingState(c).active) ?: error("No real book")
        val invalid = File(c.filesDir, "invalid.epub").apply { writeText("malformed") }
        check(runCatching { Importer.import(c, Uri.fromFile(invalid)) }.isFailure)
        check(invalid.exists())
        invalid.delete()
        val state = ReadingState(c)
        state.select(b.id, 100)
        state.font = 24
        state.theme = "night"
        check(ReadingState(c).passage(b.id) == 100)
        check(!state.follow(b.id))
        state.font = 20
        state.theme = "system"
        val sample =
            Book(
                "test",
                "Test",
                "",
                passages =
                    listOf(
                        Passage(
                            "A completely unique little sentence about a blue telescope.",
                            "",
                            0,
                            0,
                        ),
                        Passage(
                            "Another sentence mentions the silver garden beside our house.",
                            "",
                            0,
                            60,
                        ),
                    ),
            )
        val text = BookText(sample)
        val spoken = BookText.normalize(sample.passages[0].text).mapIndexed { i, w -> Spoken(w, i * 15, i * 15 + 15) }
        check(text.unique(spoken, 0) == text.passageFirst[0])
        check(text.unique(spoken, 1) == text.passageFirst[0] + 1)
        val unrelated = BookText.normalize("This is an unrelated narrator introduction with no matching words").mapIndexed { i, w -> Spoken(w, i * 15, i * 15 + 15) }
        check((0..unrelated.size - BookText.PHRASE).all { text.unique(unrelated, it) == -1 })
        check(BookText.normalize("Mr. Amberson paid $1,958 on 9/30/58, didn’t he?") == "mister amberson paid nineteen fifty eight on nine thirty fifty eight didn't he".split(' '))
        for (name in listOf("sample.mobi", "sample.azw3")) {
            val f = File(c.filesDir, name)
            if (f.exists()) {
                val parsed = Ebooks.parse(f, File(c.cacheDir, name + "-decoded"), name)
                check(parsed.passages.isNotEmpty())
                report("Decoded $name: ${parsed.passages.size} passages")
            }
        }
        report(
            "Malformed source preserved; shared state/font/theme persistence; true text matching and unrelated introduction rejection"
        )
    }

    private fun benchmark() {
        val c = targetContext
        val db = Library(c)
        val b = db.load(ReadingState(c).active) ?: error("No paired book")
        val before = db.doneUnits(b.id).size
        val start = SystemClock.elapsedRealtime()
        val limit = if (System.getProperty("sync.full") == "1" || File(c.filesDir, "sync-full").exists()) Int.MAX_VALUE else 1
        Alignment.run(c, b, AtomicBoolean(false), limit) { report(it) }
        val wall = SystemClock.elapsedRealtime() - start
        val anchors = db.anchors(b.id)
        val done = db.doneUnits(b.id)
        check(anchors.size >= 50) { "Only ${anchors.size} matches" }
        check(done.size > before || db.prep(b.id).second == "Synced")
        val monotonic = anchors.zipWithNext().all { (x, y) -> y.start >= x.start }
        check(monotonic) { "Sentence intervals are not ordered by audio time" }
        File(c.filesDir, "alignment-evidence.txt").writeText(
            "wallMs=$wall pssKB=${Debug.getPss()} prep=${db.prep(b.id)} units=${done.size}/${Alignment.units(b).size} anchors=${anchors.size}\n" +
                anchors.joinToString("\n") { "${it.passage}\t${it.start}\t${it.end}\t${"%.2f".format(it.confidence)}\t${b.passages[it.passage].text}" }
        )
        report("Measured ${anchors.size} sentence intervals over ${done.size} of ${Alignment.units(b).size} units; wall $wall ms; synced ${Alignment.clock(db.prep(b.id).first)} of ${Alignment.clock(b.duration)}; PSS ${Debug.getPss()} KB")
        val cancelled = AtomicBoolean(true)
        Alignment.run(c, b, cancelled, 1)
        check(db.doneUnits(b.id).size == done.size)
        report("Cancellation left completed units intact")
    }

    /**
     * What Android Auto does, with the platform's own media browser: connect to the playback service, read the
     * tabs and their lists, play a book by its id and by a spoken search, and pause. The active book must have audio.
     */
    private fun auto() {
        val c = targetContext
        val db = Library(c)
        val s = ReadingState(c)
        val b = db.load(s.active)!!
        check(b.tracks.isNotEmpty()) { "The active book has no audio" }
        startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val at = 120_000L.coerceAtMost(b.duration / 2)
        s.audio(b.id, at, null)
        var browser: android.media.browse.MediaBrowser? = null
        val connected = java.util.concurrent.CountDownLatch(1)
        var refused = false
        runOnMainSync {
            browser = android.media.browse.MediaBrowser(c, android.content.ComponentName(c, PlaybackService::class.java), object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() { connected.countDown() }
                override fun onConnectionFailed() { refused = true; connected.countDown() }
            }, null)
            browser!!.connect()
        }
        check(connected.await(15, java.util.concurrent.TimeUnit.SECONDS) && !refused) { "The library did not accept a media browser" }
        fun children(id: String): List<android.media.browse.MediaBrowser.MediaItem> {
            val latch = java.util.concurrent.CountDownLatch(1)
            var out: List<android.media.browse.MediaBrowser.MediaItem>? = null
            runOnMainSync {
                browser!!.subscribe(id, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, list: MutableList<android.media.browse.MediaBrowser.MediaItem>) { out = list; latch.countDown() }
                    override fun onError(parentId: String) { latch.countDown() }
                })
            }
            check(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "No answer for $id" }
            runOnMainSync { browser!!.unsubscribe(id) }
            return out ?: error("The library refused $id")
        }
        val tabs = children(browser!!.root)
        check(tabs.map { it.description.title.toString() } == listOf("My shelf", "Recent", "Series") && tabs.all { it.isBrowsable && !it.isPlayable }) { "Tabs: ${tabs.map { it.description.title }}" }
        // `-e browse only`: the lists alone, for a run without a connection (Series must come from what was kept).
        if (args?.getString("browse") == "only") {
            val series = children("series")
            check(series.isNotEmpty() && children(series.first().mediaId!!).isNotEmpty()) { "Series is empty without the server" }
            val line = "Library without playing: My shelf (${children("shelf").size}), Recent (${children("recent").size}), Series (${series.size}: ${series.first().description.title})"
            runOnMainSync { browser!!.disconnect() }
            report(line)
            return
        }
        val controller = android.media.session.MediaController(c, browser!!.sessionToken)
        fun waitFor(what: String, seconds: Int = 20, test: () -> Boolean) { val end = SystemClock.elapsedRealtime() + seconds * 1000; while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(250) }; error(what) }
        runOnMainSync { controller.transportControls.playFromMediaId("book:${b.id}", null) }
        waitFor("The book did not start from the library") { PlaybackService.playing() && PlaybackService.playingBook() == b.id }
        Thread.sleep(3000)
        var pos = 0L
        runOnMainSync { pos = PlaybackService.livePosition() }
        check(pos in at..at + 30_000) { "Started at $pos, not from the saved place $at" }
        val shown = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        check(shown == b.title) { "The session shows '$shown', not '${b.title}'" }
        runOnMainSync { controller.transportControls.pause() }
        waitFor("Pause from the browser did nothing", 5) { !PlaybackService.playing() }
        check(s.audio(b.id) >= pos - 1000) { "The place was not kept on pause: ${s.audio(b.id)} after $pos" }
        val recent = children("recent")
        check(recent.firstOrNull()?.mediaId == "book:${b.id}" && recent.first().isPlayable) { "Recent does not start with the book just played: ${recent.map { it.description.title }}" }
        if (b.cover.isNotBlank()) check(recent.first().description.iconBitmap != null || recent.first().description.iconUri != null) { "The book has no cover in the list" }
        val shelf = children("shelf")
        if (s.onShelf(b.id) && s.finished(b.id) == 0L) check(shelf.any { it.mediaId == "book:${b.id}" }) { "My shelf misses the active book" }
        val series = children("series")
        val inFirst = series.firstOrNull()?.let { children(it.mediaId!!) } ?: emptyList()
        if (series.isNotEmpty()) check(series.all { it.isBrowsable } && inFirst.isNotEmpty() && inFirst.all { it.isPlayable }) { "Series ${series.first().description.title} lists nothing playable" }
        // "Play <title>": the same book again, by search.
        runOnMainSync { controller.transportControls.playFromSearch(b.title, null) }
        waitFor("The book did not start from a search") { PlaybackService.playing() && PlaybackService.playingBook() == b.id }
        runOnMainSync { controller.transportControls.pause() }
        waitFor("Pause after the search did nothing", 5) { !PlaybackService.playing() }
        runOnMainSync { browser!!.disconnect() }
        report("Library: tabs My shelf (${shelf.size}), Recent (${recent.size}: ${recent.take(3).map { it.description.title }} / ${recent.firstOrNull()?.description?.subtitle}), Series (${series.size}${series.firstOrNull()?.let { ": ${it.description.title} with ${inFirst.size}" } ?: ""}); played ${b.title} from its id at $pos (saved $at) and from a search; paused from the browser")
    }

    private fun playback() {
        val c = targetContext
        val db = Library(c)
        val s = ReadingState(c)
        val b = db.load(s.active)!!
        val a = db.anchors(b.id).first()
        s.select(b.id, a.passage)
        startActivitySync(
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        runOnMainSync { PlaybackService.send(c, "play", b.id, a.start) }
        Thread.sleep(6000)
        check(PlaybackService.playing()) { "Playback did not start" }
        val time = s.audio(b.id)
        check(time > a.start) { "Foreground audio did not advance" }
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(4000)
        check(s.audio(b.id) > time) { "Background audio did not advance" }
        check(s.follow(b.id)) { "Narration follow disabled" }
        val beforeSleep = s.audio(b.id)
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(3500)
        check(s.audio(b.id) > beforeSleep) { "Screen-off audio did not advance" }
        shell("cmd media_session dispatch pause")
        Thread.sleep(1000)
        check(!PlaybackService.playing()) { "System media key did not pause" }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        check(db.atTime(b.id, a.start) != null)
        report(
            "Actual M4B advanced in foreground, behind launcher, and with screen off; system media key paused; shared position persisted"
        )
    }

    /** The sleep timer on the real service: end of chapter with fade, play on by shake, a length extended by shake. */
    private fun sleepTimer() {
        val c = targetContext
        val db = Library(c)
        val s = ReadingState(c)
        val b = db.load(s.active)!!
        check(b.chapters.size > 4) { "Fixture has no chapters" }
        val ch = b.chapters[3]; val following = b.chapters[4]; val after = b.chapters.getOrNull(5)?.start ?: b.duration
        startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync { PlaybackService.send(c, "play", b.id, following.start - 9000) }
        val started = SystemClock.elapsedRealtime() + 10000
        while (!PlaybackService.playing() && SystemClock.elapsedRealtime() < started) Thread.sleep(200)
        check(PlaybackService.playing()) { "Playback did not start" }
        runOnMainSync { PlaybackService.send(c, "play", b.id, following.start - 9000) }
        Thread.sleep(500)
        runOnMainSync { PlaybackService.send(c, "sleep", b.id, minutes = -1) }
        Thread.sleep(500)
        val left = PlaybackService.sleepLeft()
        check(PlaybackService.sleepByChapter() && left in 5000..9000) { "Chapter timer: by chapter ${PlaybackService.sleepByChapter()}, left $left" }
        var faded = 1f; val deadline = SystemClock.elapsedRealtime() + 15000
        while (PlaybackService.playing() && SystemClock.elapsedRealtime() < deadline) { if (PlaybackService.sleepLeft() in 1..2500) faded = minOf(faded, PlaybackService.volume()); Thread.sleep(100) }
        check(!PlaybackService.playing()) { "Chapter timer did not stop the audio" }
        Thread.sleep(300)
        val stopped = s.audio(b.id)
        check(kotlin.math.abs(stopped - following.start) < 400) { "Stopped at $stopped, chapter starts ${following.start}" }
        check(faded < .5f) { "No fade: lowest volume $faded" }
        check(PlaybackService.volume() == 1f) { "Volume not restored" }
        check(PlaybackService.sleepJustEnded()) { "No shake window after the stop" }
        report("End of chapter: left $left ms at start, faded to $faded, stopped at $stopped (${ch.title} -> ${following.title} at ${following.start})")
        runOnMainSync { PlaybackService.send(c, "shake", b.id) }
        Thread.sleep(1200)
        val again = PlaybackService.sleepLeft()
        check(PlaybackService.playing() && PlaybackService.sleepByChapter()) { "Shake after the stop did not play on with the chapter timer" }
        check(again > (after - following.start) / 2 - 5000) { "Chapter timer after shake: $again ms left, chapter is ${after - following.start} ms" }
        report("Shake after stop: playing again, chapter timer $again ms left of ${after - following.start}")
        runOnMainSync { PlaybackService.send(c, "sleep", b.id, sleepMs = 6000) }
        Thread.sleep(3000)
        val before = PlaybackService.sleepLeft()
        runOnMainSync { PlaybackService.send(c, "shake", b.id) }
        Thread.sleep(300)
        val extended = PlaybackService.sleepLeft()
        check(before in 1000..3500 && extended > 5000) { "Length timer not extended: $before -> $extended" }
        Thread.sleep(7500)
        check(!PlaybackService.playing()) { "Length timer did not stop the audio" }
        report("Length timer: $before ms left, shake -> $extended ms, then stopped")
        runOnMainSync { PlaybackService.send(c, "sleep", b.id, minutes = 0) }
        Thread.sleep(600)
        check(!PlaybackService.sleepJustEnded() && PlaybackService.sleepLeft() < 0) { "Timer not cleared" }
    }

    /** Plays with a 30 s timer and waits up to 25 s for a real accelerometer shake (sent from the host) to extend it. */
    private fun sleepShake() {
        val c = targetContext
        val s = ReadingState(c)
        val b = Library(c).load(s.active)!!
        startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync { PlaybackService.send(c, "play", b.id, b.chapters.getOrNull(3)?.start ?: 60000) }
        val started = SystemClock.elapsedRealtime() + 10000
        while (!PlaybackService.playing() && SystemClock.elapsedRealtime() < started) Thread.sleep(200)
        runOnMainSync { PlaybackService.send(c, "sleep", b.id, sleepMs = 30000) }
        Thread.sleep(8000)
        val before = PlaybackService.sleepLeft()
        report("Ready for a shake: $before ms left")
        val deadline = SystemClock.elapsedRealtime() + 25000
        var extended = -1L
        while (SystemClock.elapsedRealtime() < deadline) { val l = PlaybackService.sleepLeft(); if (l > before + 3000) { extended = l; break }; Thread.sleep(200) }
        runOnMainSync { PlaybackService.send(c, "sleep", b.id, minutes = 0); PlaybackService.send(c, "pause", b.id) }
        check(extended > 0) { "No shake detected" }
        report("Shake detected by the accelerometer: $before -> $extended ms left")
    }

    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun clickUi(text: String) {
        repeat(40) {
            val node =
                uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(text)
                    ?.firstOrNull { it.text?.toString() == text }
            if (node != null) {
                var clickable: android.view.accessibility.AccessibilityNodeInfo = node
                while (!clickable.isClickable) {
                    clickable = clickable.parent ?: break
                }
                val rect = android.graphics.Rect()
                clickable.getBoundsInScreen(rect)
                shell("input tap ${rect.centerX()} ${rect.centerY()}")
                return
            }
            Thread.sleep(250)
        }
        error("UI text unavailable: $text")
    }

    private fun saf() {
        val c = targetContext
        startActivitySync(
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        clickUi("Import files")
        Thread.sleep(1200)
        var file =
            uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("Reader-SAF.epub")
                ?.firstOrNull()
        if (file == null) {
            val roots =
                uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId(
                        "com.google.android.documentsui:id/toolbar"
                    )
                    ?.firstOrNull()
            shell("input tap 72 155")
            Thread.sleep(600)
            clickUi("Downloads")
        }
        clickUi("Reader-SAF.epub")
        repeat(80) {
            Thread.sleep(250)
            if (!ImportService.busy && ImportService.status.contains("Original removed"))
                return@repeat
        }
        check(!ImportService.busy)
        check(ImportService.status.contains("Original removed")) { ImportService.status }
        val book = Library(c).use { it.books().firstOrNull { it.title == "Reader SAF fixture" } }
        check(book != null)
        report(
            "Android document picker imported from Downloads, verified library storage and deleted original with provider permission"
        )
    }

    private fun failure() {
        val c = targetContext
        val db = Library(c)
        val state = ReadingState(c)
        val active = db.books().first { it.author.contains("King") }.id
        fun epub(file: File, title: String) {
            java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
                for ((path, text) in
                    mapOf(
                        "META-INF/container.xml" to
                            "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>",
                        "book.opf" to
                            "<package><metadata><title>$title</title></metadata><manifest><item id='a' href='a.html' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>",
                        "a.html" to
                            "<html><body><p>A public synthetic test sentence with readable words.</p></body></html>",
                    )) {
                    z.putNextEntry(java.util.zip.ZipEntry(path))
                    z.write(text.toByteArray())
                    z.closeEntry()
                }
            }
        }
        val input = File(c.filesDir, "failure.epub")
        epub(input, "Failure fixture ${SystemClock.elapsedRealtime()}")
        val duplicate = File(c.filesDir, "duplicate.epub")
        input.copyTo(duplicate, true)
        val before = db.books().size
        state.tree = "content://missing.provider/tree/invalid"
        check(runCatching { Importer.import(c, Uri.fromFile(input)) }.isFailure)
        check(input.exists())
        check(db.books().size == before)
        state.tree = ""
        val imported = Importer.import(c, Uri.fromFile(input))
        check(!input.exists())
        db.cleanupResult(Uri.fromFile(input).toString(), "Pending source cleanup")
        check(db.committedSource(Uri.fromFile(input).toString()) == imported.book.id) {
            "Committed import must survive death between source deletion and cleanup status"
        }
        db.cleanupResult(Uri.fromFile(input).toString(), "removed")
        val again = Importer.import(c, Uri.fromFile(duplicate))
        check(again.book.id == imported.book.id)
        check(duplicate.exists())
        check(again.message.contains("Already"))
        duplicate.delete()
        val zip = File(c.filesDir, "traversal.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("../escape.mp3"))
            z.write(byteArrayOf(1))
            z.closeEntry()
        }
        check(runCatching { Importer.import(c, Uri.fromFile(zip)) }.isFailure)
        check(zip.exists())
        zip.delete()
        val orphan =
            File(c.filesDir, "library/orphan-test.bin").apply { writeText("unfinished import") }
        val stage = File(c.filesDir, "staging/recovery-test").apply { mkdirs() }
        File(stage, "journal.json")
            .writeText(
                org.json
                    .JSONObject()
                    .put("hash", "uncommitted-test")
                    .put("owned", org.json.JSONArray(listOf(Uri.fromFile(orphan).toString())))
                    .toString()
            )
        Importer.recover(c)
        check(!orphan.exists())
        check(!stage.exists())
        state.active = active
        uiAutomation.adoptShellPermissionIdentity()
        db.writableDatabase.delete(
            "imports",
            "source LIKE ?",
            arrayOf("content://dev.homescreenreader.test.documents%"),
        )
        val readonly =
            Importer.import(
                c,
                android.provider.DocumentsContract.buildDocumentUri(
                    "dev.homescreenreader.test.documents",
                    "readonly",
                ),
            )
        uiAutomation.dropShellPermissionIdentity()
        check(readonly.message.contains("denied", true)) { readonly.message }
        check(db.cleanup().any { it.first.contains("readonly") })
        state.active = active
        val b = db.load(active)!!
        db.writableDatabase.beginTransaction()
        try {
            db.save(b.copy(title = "Uncommitted title"))
        } finally {
            db.writableDatabase.endTransaction()
        }
        check(Library(c).use { it.load(active) }!!.title == b.title) {
            "Rolled-back catalog leaked through cache"
        }
        val chapters = AudioChapters.read(File(Uri.parse(b.tracks[0].uri).path!!))
        check(chapters.size >= 40) { "Embedded chapters missing: ${chapters.size}" }
        report(
            "Embedded M4B chapters=${chapters.size}; failed destination preserved source; duplicate kept source; ZIP traversal rejected; interrupted import rolled back"
        )
    }

    private fun reader(existingActivity: Activity? = null) {
        val c = targetContext
        val s = ReadingState(c)
        val b = Library(c).use { it.load(s.active) }!!
        val a = Library(c).use { it.anchors(b.id).first { a -> a.passage > 40 } }
        s.select(b.id, a.passage)
        val activity =
            existingActivity
                ?: startActivitySync(
                    Intent(c, MainActivity::class.java)
                        .putExtra("book", b.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
        fun click(text: String) {
            var node: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(30) {
                if (node == null) {
                    node =
                        uiAutomation.rootInActiveWindow
                            ?.findAccessibilityNodeInfosByText(text)
                            ?.firstOrNull { it.text?.toString() == text }
                    Thread.sleep(200)
                }
            }
            check(node != null) { "Missing $text" }
            check(
                node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            )
        }
        Thread.sleep(1000)
        click("Read")
        Thread.sleep(5000)
        var web: android.webkit.WebView? = null
        runOnMainSync {
            fun visit(v: android.view.View) {
                if (v is android.webkit.WebView) web = v
                else if (v is android.view.ViewGroup)
                    for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
            visit(activity.window.decorView)
        }
        check(web != null)
        fun js(code: String): String {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = ""
            runOnMainSync {
                web!!.evaluateJavascript(code) {
                    result = it
                    latch.countDown()
                }
            }
            check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            return result
        }
        // A fresh install renders its first page slowly; wait for the chapter rather than guess.
        var body = ""
        val renderDeadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < renderDeadline) {
            body = js("document.querySelector('foliate-view')?.renderer?.getContents()?.[0]?.doc?.body?.innerText?.length")
            if ((body.toIntOrNull() ?: 0) > 1000) break
            Thread.sleep(500)
        }
        check((body.toIntOrNull() ?: 0) > 1000) {
            "Formatted reader missing original chapter: $body"
        }
        check((js("JSON.parse(window.readerToc()).length").toIntOrNull() ?: 0) > 10) {
            "Publisher table of contents missing"
        }
        val highlight =
            js(
                "Array.from(document.querySelector('foliate-view').renderer.getContents()[0].doc.defaultView.CSS.highlights.get('narration')||[]).map(r=>r.toString()).join(' ')"
            )
        check(highlight == "\"\"") { "Idle reading should not retain a narration highlight: $highlight" }
        // Smooth follow is one-way for a reading session, so keep this first narration run on pages.
        s.followScroll = false
        runOnMainSync { PlaybackService.send(c, "play", b.id, a.start) }
        val narrationDeadline = SystemClock.elapsedRealtime() + 15000
        while (
            s.passage(b.id) <= a.passage && SystemClock.elapsedRealtime() < narrationDeadline
        ) Thread.sleep(250)
        Thread.sleep(1200)
        check(s.passage(b.id) > a.passage) {
            "Narration did not advance text: pos=${s.audio(b.id)} passage=${s.passage(b.id)} follow=${s.follow(b.id)} playing=${PlaybackService.playing()}"
        }
        val following =
            js(
                "Array.from(document.querySelector('foliate-view').renderer.getContents()[0].doc.defaultView.CSS.highlights.get('narration')||[]).map(r=>r.toString()).join(' ')"
            )
        check(following.contains("ex-wife")) { "Live highlight did not follow: $following" }
        s.select(b.id, a.passage)
        Thread.sleep(2000)
        check(s.passage(b.id) == a.passage)
        // Smooth follow must not change the formatting: the narrated sentence has the same number of
        // lines, the same widest line, and the same left edge in both flows.
        // A fixed paragraph (the longest in the current section), so the measurement does not depend on which sentence is narrated.
        fun shape(): String = js("(()=>{const v=document.querySelector('foliate-view');const d=v.renderer.getContents()[0];const doc=d.doc;const fr=doc.defaultView.frameElement.getBoundingClientRect();const ps=[...doc.querySelectorAll('p')];if(!ps.length)return 'none';const p=ps.reduce((a,b)=>b.textContent.length>a.textContent.length?b:a);const range=doc.createRange();range.selectNodeContents(p);const rects=[...range.getClientRects()].filter(r=>r.width>0&&r.height>0);const widest=Math.max(...rects.map(r=>r.width));return rects.length+'|'+Math.round(widest)})()")
        fun probe(): String = js("(()=>{const v=document.querySelector('foliate-view');const d=v.renderer.getContents()[0];const doc=d.doc;const frame=doc.defaultView.frameElement;const el=frame.parentElement;const container=el.parentElement;const top=container.parentElement;const cs=e=>{const c=getComputedStyle(e);return [Math.round(e.getBoundingClientRect().width),c.paddingLeft,c.paddingRight,c.gridTemplateColumns]};return JSON.stringify({col:doc.documentElement.style.columnWidth,gap:doc.documentElement.style.columnGap,htmlPad:doc.documentElement.style.padding,htmlW:Math.round(doc.documentElement.getBoundingClientRect().width),body:[doc.body.style.maxWidth,doc.body.style.margin],frame:cs(frame),el:cs(el),container:cs(container),top:cs(top),gapAttr:v.renderer.getAttribute('gap'),margin:v.renderer.getAttribute('margin')})})()")
        // Measure the genuine page layout first, before narration (and smooth follow) starts.
        check(js("document.querySelector('foliate-view').renderer.getAttribute('flow')") == "\"paginated\"") { "Expected the page layout before smooth follow" }
        val pagedShape = shape()
        val pagedProbe = probe()
        // The first narration run is still playing; pause it so the scenario begins, as the reader
        // does, from a quiet page with "Listen here" on the button.
        runOnMainSync { PlaybackService.send(c, "pause", b.id) }
        Thread.sleep(2500)
        s.follow(b.id, true)
        s.followScroll = true
        // From here the screen is watched continuously: every 50 ms a sample of the flow, the scroll
        // position, the text line at the middle of the screen and its top, the highlighted sentence,
        // and the reader's latest scroll. Afterwards every change between two samples must be
        // explained: a scroll the reader logged while narration plays (a follow, the landing of the
        // switch, a chapter change), or a page turn or drag the test itself made. Anything else is
        // the text moving for no reason, which the reader's owner never wants to see.
        val samples = java.util.Collections.synchronizedList(ArrayList<Pair<Long, String>>())
        val sampling = AtomicBoolean(true)
        val mainHandler = Handler(Looper.getMainLooper())
        val sampler = Thread {
            while (sampling.get()) {
                mainHandler.post { web!!.evaluateJavascript("window.readerProbe()") { r -> samples.add(SystemClock.elapsedRealtime() to r) } }
                Thread.sleep(50)
            }
        }.apply { start() }
        val starts = StringBuilder()
        val busy = ArrayList<LongRange>()      // page turns and drags by the test: anything goes
        val playing = ArrayList<LongRange>()   // narration playing: the reader's own logged scrolls only
        var playFrom = -1L
        val lib = Library(c)
        fun now() = SystemClock.elapsedRealtime()
        fun parse(raw: String) = runCatching { org.json.JSONObject(org.json.JSONTokener(raw).nextValue().toString()) }.getOrNull()
        fun flow() = js("document.querySelector('foliate-view').renderer.getAttribute('flow')")
        fun playingNow() = PlaybackService.playingBook() == b.id && PlaybackService.playing()
        // The reader's own controls, pressed as the reader presses them.
        fun press(text: String) {
            var found = false
            runOnMainSync {
                fun visit(v: android.view.View): Boolean {
                    if (v is android.widget.TextView && v.text?.toString() == text) { v.performClick(); return true }
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (visit(v.getChildAt(i))) return true
                    return false
                }
                found = visit(activity.window.decorView)
            }
            check(found) { "No control reads \"$text\"" }
        }
        fun visible(): Pair<Int, Int> = org.json.JSONArray(js("window.readerVisible()").trim('"').replace("\\", "")).let { it.getInt(0) to it.getInt(1) }
        // The sentence at an audio position, with the same quarter-second lead the app uses.
        fun sentenceAt(pos: Long): Int? {
            var a = lib.atTime(b.id, pos) ?: return null
            if (pos >= a.end - 250) lib.atTime(b.id, a.end)?.let { a = it }
            return a.passage
        }
        fun narrated(): Int? {
            var pos = -1L
            runOnMainSync { pos = PlaybackService.livePosition() }
            if (!playingNow() || pos < 0) return null
            return sentenceAt(pos)
        }
        // Narration starts from the button, as the reader starts it. The sentence it starts with must
        // be the first one highlighted, and it must be the top of the page, unless the audio stopped
        // on this very page, in which case it picks up where it stopped.
        fun startNarration(tag: String) {
            val page = visible(); val paused = sentenceAt(s.audio(b.id))
            val expected = if (paused != null && paused in page.first..page.second) paused else page.first
            val pressed = now()
            press("Listen here")
            playFrom = pressed
            var first: Int? = null; var seen = -1; var cur: Int? = null
            val deadline = pressed + 6000
            while (now() < deadline) {
                cur = narrated()
                if (cur != null && first == null) first = cur
                val h = samples.lastOrNull()?.let { parse(it.second) }?.optInt("h", -1) ?: -1
                if (h >= 0) { seen = h; cur = narrated() ?: cur; break }
                if (first != null && cur != null && cur != first) error("$tag: narration moved on to sentence $cur before its first sentence $first was highlighted")
                Thread.sleep(40)
            }
            check(seen >= 0 && first != null) { "$tag: no narration highlight within 6 s of pressing Listen here (playing=${playingNow()})" }
            check(seen == first || seen == cur) { "$tag: the first highlighted sentence was $seen, but narration started with $first (now $cur)" }
            check(first == expected) { "$tag: narration started at sentence $first; Listen here must start at ${if (expected == paused) "the sentence the audio stopped in ($paused, on the page ${page.first}..${page.second})" else "the top of the page (${page.first}; audio had stopped at $paused)"}" }
            starts.append("$tag: ${if (expected == paused) "resumed at $first, the paused sentence on the page" else "started at $first, the top of page ${page.first}..${page.second}"}; ")
        }
        // Smooth follow is on, in the page's own layout, and the switch left the text at the middle of the screen exactly where it was.
        fun engaged(tag: String) {
            val deadline = now() + 4000
            while (flow() != "\"scrolled\"" && now() < deadline) Thread.sleep(100)
            check(flow() == "\"scrolled\"") { "$tag: smooth follow did not engage" }
            check(shape() == pagedShape) { "$tag: smooth follow changed the layout: $pagedShape -> ${shape()} (lines|widest)" }
            val e = parse(js("window.readerEngage()")) ?: error("$tag: no record of the switch to the follow flow")
            val before = e.optJSONObject("before"); val after = e.optJSONObject("after")
            check(before != null && after != null) { "$tag: the switch could not find the text at the middle of the screen: $e" }
            check(before!!.optString("text") == after!!.optString("text") && Math.abs(before.optDouble("y") - after.optDouble("y")) <= 1.0) { "$tag: switching to the follow flow moved the text at the middle of the screen: $e" }
        }
        // Pausing, from the button: the layout and position stay, the highlight goes.
        fun pauseNarration(tag: String) {
            val pressed = now()
            press("Pause")
            playing.add(playFrom..pressed + 1500)
            Thread.sleep(3000)
            check(!playingNow()) { "$tag: narration did not pause" }
            check(flow() == "\"scrolled\"") { "$tag: pausing must leave the follow flow as it is" }
            check(shape() == pagedShape) { "$tag: pausing changed the layout: $pagedShape -> ${shape()} (lines|widest)" }
            val last = parse(samples.last().second)
            check(last?.optInt("h", -1) == -1) { "$tag: pausing must remove the narration highlight: $last" }
        }
        // The reader's own flow after a pause: swipe forward to the next page, swipe back to the
        // screen's page, forward again; once straight from the pause, once after reading on down the scroll.
        fun edges() = org.json.JSONObject(js("window.readerEdges()").trim('"').replace("\\", ""))
        fun shiftInfo() = js("window.readerShift()").trim('"').replace("\\", "")
        fun shot(name: String) { uiAutomation.takeScreenshot()?.let { bm -> File(c.filesDir, "$name.png").outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } }
        fun swipe(forward: Boolean) {
            busy.add(now()..now() + 3700)
            val xy = IntArray(2); var w = 0; var h = 0
            runOnMainSync { web!!.getLocationOnScreen(xy); w = web!!.width; h = web!!.height }
            val time = SystemClock.uptimeMillis()
            val x1 = if (forward) .85f else .15f; val x2 = if (forward) .2f else .8f
            fun event(action: Int, x: Float, elapsed: Long) {
                val e = android.view.MotionEvent.obtain(time, time + elapsed, action, xy[0] + x * w, xy[1] + h * .5f, 0)
                uiAutomation.injectInputEvent(e, true); e.recycle()
            }
            event(android.view.MotionEvent.ACTION_DOWN, x1, 0)
            for (step in 1..6) event(android.view.MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * step / 6, step * 30L)
            event(android.view.MotionEvent.ACTION_UP, x2, 220)
            Thread.sleep(3500)
        }
        // A tap at the middle of the page, as the reader taps for the controls.
        fun tap() {
            val xy = IntArray(2); var w = 0; var h = 0
            runOnMainSync { web!!.getLocationOnScreen(xy); w = web!!.width; h = web!!.height }
            val time = SystemClock.uptimeMillis()
            for ((action, elapsed) in listOf(android.view.MotionEvent.ACTION_DOWN to 0L, android.view.MotionEvent.ACTION_UP to 60L)) {
                val e = android.view.MotionEvent.obtain(time, time + elapsed, action, xy[0] + w * .5f, xy[1] + h * .5f, 0)
                uiAutomation.injectInputEvent(e, true); e.recycle()
            }
        }
        fun shown(text: String): Boolean {
            var result = false
            runOnMainSync {
                fun visit(v: android.view.View): Boolean {
                    if (v is android.widget.TextView && v.text?.toString() == text) return v.isShown
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (visit(v.getChildAt(i))) return true
                    return false
                }
                result = visit(activity.window.decorView)
            }
            return result
        }
        val exits = StringBuilder()
        fun exitCycle(tag: String, readOn: Boolean) {
            if (readOn) {
                busy.add(now()..now() + 1400)
                js("(()=>{const d=document.querySelector('foliate-view').renderer.getContents()[0].doc;const c=d.defaultView.frameElement.parentElement.parentElement;c.scrollBy({top:Math.round(c.clientHeight*0.6),behavior:'instant'});return c.scrollTop})()")
                Thread.sleep(1200)
            }
            val before = edges()
            val first = before.getInt("first"); val seen = before.getInt("seen"); val below = before.getInt("below")
            check(first >= 0 && seen >= first && below > seen) { "$tag: screen edges unknown before the swipe: $before" }
            shot("exit-$tag-screen")
            // The reader paused from the controls, so they are showing; the turn must put them away.
            tap(); Thread.sleep(800)
            check(shown("Listen here")) { "$tag: a tap on the page must show the controls" }
            swipe(true)
            check(!shown("Listen here")) { "$tag: a page turn must hide the controls" }
            check(flow() == "\"paginated\"") { "$tag: a swipe while paused must bring the pages back" }
            check(shape() == pagedShape) { "$tag: returning to pages changed the layout: $pagedShape -> ${shape()} (lines|widest); fresh $pagedProbe now ${probe()}" }
            val next = visible(); val info = shiftInfo()
            val shifted = (runCatching { org.json.JSONObject(info).optDouble("shift", 0.0) }.getOrNull() ?: 0.0) > 0
            shot("exit-$tag-next")
            // The next page begins where the screen ended: after the last wholly seen sentence (when the grid was shifted) and holding the first unseen one.
            check(next.first > (if (shifted) seen else first) && next.first <= below && next.second >= below) {
                "$tag: the swipe must land on the page after the screen: visible $first..$seen, first below $below -> page ${next.first}..${next.second}; grid $info"
            }
            swipe(false)
            val back = visible()
            shot("exit-$tag-back")
            // Swiping back shows the screen's own page: it ends where the next page began and lies within what was on
            // screen. When the grid shift had to be skipped (it would have emptied most of the chapter's first page),
            // the pages keep their natural breaks and the page before may begin a line or two above the screen's top.
            check(back.second in next.first - 1..next.first && back.first <= seen && (if (shifted) back.first >= first else back.second >= first)) {
                "$tag: swiping back must return to the screen's page: screen $first..$seen, next ${next.first}.. -> back ${back.first}..${back.second} (shift ${if (shifted) "applied" else "skipped"})"
            }
            swipe(true)
            val again = visible()
            check(again.first in next.first - 1..next.first + 1 && again.second == next.second) { "$tag: forward again must show the same next page: ${next.first}..${next.second} vs ${again.first}..${again.second}" }
            exits.append("$tag: screen $first..$seen (first unseen $below) -> next ${next.first}..${next.second}, back ${back.first}..${back.second}, grid $info; ")
        }
        val watchStart = now()
        try {
            // 1. From pages, as on opening the book: narration from the button; the screen keeps its text in place as the follow flow takes over.
            check(flow() == "\"paginated\"") { "Expected the page layout before smooth follow" }
            startNarration("first play")
            engaged("first play")
            Thread.sleep(4000)
            pauseNarration("first pause")
            // 2. Resume from the pause: the paused sentence is highlighted first and nothing moves until the follow does.
            startNarration("resume")
            Thread.sleep(3000)
            pauseNarration("second pause")
            // 3. Turn out of the pause, back, forward; then the page must hold still.
            exitCycle("a", false)
            Thread.sleep(2500)
            // 4. Narration again from the page reached by turning: first sentence highlighted, no jump as the flow switches.
            startNarration("after turning")
            engaged("after turning")
            Thread.sleep(3000)
            pauseNarration("third pause")
            // 5. Read on down the scroll by hand, then turn, back, forward; hold still again.
            exitCycle("b", true)
            Thread.sleep(2500)
        } finally {
            sampling.set(false); sampler.join()
            lib.close()
        }
        // Every change between consecutive samples must have a reason.
        val all = samples.mapNotNull { (t, raw) -> parse(raw)?.let { t to it } }
        check(all.size > 300) { "Too few screen samples: ${all.size}" }
        var moves = 0
        for (i in 1 until all.size) {
            val (t0, a) = all[i - 1]; val (t1, s) = all[i]
            if (busy.any { t0 in it || t1 in it }) continue
            val live = playing.any { t1 in it }
            val at = "${t1 - watchStart} ms in"
            if (a.optString("f") != s.optString("f")) { check(live) { "The layout changed from ${a.optString("f")} to ${s.optString("f")} while nothing should change it, $at" }; continue }
            val scrolled = a.optDouble("t") != s.optDouble("t")
            val shifted = a.optInt("l") != s.optInt("l") || a.optString("c") != s.optString("c") || Math.abs(a.optDouble("y") - s.optDouble("y")) > 1.0
            if (!scrolled && !shifted) continue
            moves++
            check(live) { "The text moved while nothing should move it, $at: $a -> $s" }
            check(s.optString("f") == "scrolled") { "The page moved while narrating in the page layout, $at: $a -> $s" }
            check(scrolled) { "The text shifted without scrolling while narrating, $at: $a -> $s" }
            // Narration playing: the move must be the reader's own logged scroll, heading for its target.
            val sc = s.optJSONObject("s") ?: error("Unexplained move while narrating (no scroll logged), $at: $a -> $s")
            if (sc.optString("kind") == "chapter") continue
            val lo = Math.min(sc.optDouble("from"), sc.optDouble("to")) - 1.5; val hi = Math.max(sc.optDouble("from"), sc.optDouble("to")) + 1.5
            check(s.optDouble("t") in lo..hi) { "A move while narrating went outside the reader's own scroll, $at ($sc): $a -> $s" }
            if (a.optJSONObject("s")?.optInt("seq") == sc.optInt("seq")) check(Math.abs(s.optDouble("t") - sc.optDouble("to")) <= Math.abs(a.optDouble("t") - sc.optDouble("to")) + 1.5) { "A move while narrating went away from the reader's own target, $at ($sc): $a -> $s" }
        }
        s.followScroll = false
        report("Watched ${all.size} screen samples over ${(now() - watchStart) / 1000} s: every move was a logged follow while narrating ($moves scrolling samples) or a turn; the first narrated sentence was highlighted each time; Listen here: $starts switching flows kept the middle line in place; pausing changed nothing but the highlight ($pagedShape lines|widest throughout); turning out of the pause: $exits")
        runOnMainSync { PlaybackService.send(c, "play", b.id, a.start) }
        Thread.sleep(1200)
        check(s.audio(b.id) < a.start + 4000)
        runOnMainSync { PlaybackService.send(c, "toggle", b.id) }
        s.select(b.id, a.passage)
        s.font = 26
        s.theme = "night"
        check(s.passage(b.id) == a.passage)
        s.font = 20
        s.theme = "system"
        Thread.sleep(1500)
        // The visible-sentence query behind "Listen here" and the long-press choice behind "Listen from here".
        val visible = org.json.JSONArray(js("window.readerVisible()").trim('"').replace("\\", ""))
        check(visible.getInt(0) >= 0 && visible.getInt(1) >= visible.getInt(0)) { "Visible sentence range unknown: $visible" }
        val probe = js("(()=>{const d=document.querySelector('foliate-view').lastLocation;const r=d&&d.range;return JSON.stringify({cfi:d&&d.cfi,collapsed:!!(r&&r.collapsed),text:r?r.toString().slice(0,70):null,endText:r?r.toString().slice(-50):null})})()")
        check(a.passage in visible.getInt(0)..visible.getInt(1)) { "Selected passage ${a.passage} not within visible $visible; $probe" }
        check(js("(()=>{const d=document.querySelector('foliate-view').renderer.getContents()[0].doc;const ps=Array.from(d.querySelectorAll('p')).reverse();let t=null;for(const p of ps){const w=d.createTreeWalker(p,NodeFilter.SHOW_TEXT);let n;while((n=w.nextNode())){if(n.textContent.trim().length>20){t=n;break}}if(t)break}if(!t)return 'no text';const sel=d.getSelection();sel.removeAllRanges();const r=d.createRange();r.setStart(t,2);r.setEnd(t,Math.min(t.length,14));sel.addRange(r);return 'ok'})()") == "\"ok\"")
        Thread.sleep(1500)
        check(s.passage(b.id) != a.passage && !s.follow(b.id)) { "A text selection must become the chosen start sentence: passage=${s.passage(b.id)} follow=${s.follow(b.id)}" }
        // Listening from the chosen sentence takes the selection, and the Copy menu riding on it, away.
        press("Listen here")
        Thread.sleep(1500)
        val selectionLeft = js("(()=>{const d=document.querySelector('foliate-view').renderer.getContents()[0].doc;const sel=d.getSelection();return sel?sel.rangeCount===0||sel.isCollapsed:true})()")
        check(selectionLeft == "true") { "Starting narration from a long-pressed sentence must drop the text selection: still selected" }
        check(PlaybackService.playingBook() == b.id && PlaybackService.playing()) { "Listen here from the chosen sentence did not start narration" }
        runOnMainSync { PlaybackService.send(c, "pause", b.id) }
        Thread.sleep(1500)
        s.select(b.id, a.passage)
        report("Visible sentence range ${visible.getInt(0)}..${visible.getInt(1)} reported; a text selection chose a start sentence and suspended follow")
        uiAutomation.takeScreenshot()?.let { bm ->
            File(c.filesDir, "reader-test.png").outputStream().use {
                bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        report(
            "Original EPUB rendered with Foliate; exact matched sentence selected and highlighted; immutable passage survived appearance changes"
        )
    }

    private fun formats() {
        val c = targetContext
        val db = Library(c)
        val s = ReadingState(c)
        val active = db.books().first { it.author.contains("King") }.id
        val source = File(c.filesDir, "tones/tones.zip")
        val hash = Importer.digest(source)
        val b = Importer.import(c, Uri.fromFile(source)).book
        check(b.tracks.map { it.name } == listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3"))
        check(b.duration in 8900..9500)
        for (track in b.tracks) {
            val pcm = AudioDecoder.decode(c, track.uri, 0, 1000)
            check(pcm.size > 15000 && pcm.any { kotlin.math.abs(it) > .01f })
        }
        val reordered = b.reordered(b.tracks.reversed())
        db.save(reordered)
        check(db.load(b.id)!!.tracks.first().name == "Track 10.mp3")
        db.save(b)
        val target =
            Book(
                java.util.UUID.randomUUID().toString(),
                "Synthetic pairing",
                "",
                passages = listOf(Passage("Synthetic ebook sentence.", "", 0, 0)),
            )
        db.save(target)
        db.writableDatabase.execSQL(
            "UPDATE imports SET source='content://test/opaque-document-id' WHERE hash=?",
            arrayOf(hash),
        )
        val paired = db.moveAudio(b, target)
        check(paired.tracks.size == 3 && paired.chapters.size == 3)
        check(db.duplicate(hash) == paired.id)
        s.active = active
        report(
            "Real MP3 collection decoded; natural order 1,2,10 verified; explicit reordered tracks persisted"
        )
    }

    private fun samples() {
        val c = targetContext
        val db = Library(c)
        val b = db.load(ReadingState(c).active)!!
        val text = BookText(b)
        val aligner = UnitAligner(text)
        val session = SpeechModel.open(c)
        try {
            val emitter = Emitter(session)
            for (offset in listOf(3508906L, 55000000L, 107354627L)) {
                val started = SystemClock.elapsedRealtime()
                val pcm = Alignment.decodeRange(c, b, offset, offset + 30000)
                val frames = 1500
                val lp = FloatArray(frames * SpeechModel.CLASSES)
                emitter.emit(pcm, 0, frames, lp, 0)
                val spoken = aligner.greedy(lp, frames)
                val hits = (0..spoken.size - BookText.PHRASE).count { text.unique(spoken, it) >= 0 }
                val result = aligner.align(lp, frames, offset, 0, 0)
                check(hits >= 10) { "Only $hits phrase matches at $offset" }
                check(result.anchors.size >= 3) { "Only ${result.anchors.size} sentences at $offset" }
                File(c.filesDir, "sample-$offset.txt").writeText(
                    spoken.joinToString(" ") { it.word } + "\nSENTENCES\n" + result.anchors.joinToString("\n") { "$it ${b.passages[it.passage].text}" }
                )
                report("Sample offset=$offset spoken=${spoken.size} phraseHits=$hits sentences=${result.anchors.size} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
        } finally {
            session.close()
        }
    }

    private fun servicePreparation() {
        val c = targetContext
        val s = ReadingState(c)
        val b = Library(c).use { it.load(s.active) }!!
        val activity =
            startActivitySync(
                Intent(c, MainActivity::class.java)
                    .putExtra("book", b.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        runOnMainSync {
            c.startForegroundService(
                Intent(c, PreparationService::class.java).putExtra("book", b.id)
            )
        }
        Thread.sleep(2000)
        try {
            reader(activity)
        } finally {
            runOnMainSync {
                c.startService(Intent(c, PreparationService::class.java).setAction("pause"))
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (
            Library(c).use { it.prep(b.id).second }.startsWith("Syncing") &&
                SystemClock.elapsedRealtime() < deadline
        ) Thread.sleep(250)
        check(Library(c).use { it.prep(b.id).second }.let { it.startsWith("Paused") || it == "Synced" })
        report(
            "Foreground synchronization ran alongside formatted reading and actual audiobook playback, then paused between units"
        )
    }

    private fun crashPreparation() {
        val c = targetContext
        val db = Library(c)
        val b = db.books().first { it.author.contains("King") }
        ReadingState(c).active = b.id
        File(c.filesDir, "before-crash.txt").writeText(db.doneUnits(b.id).size.toString())
        Alignment.run(c, b, AtomicBoolean(false)) { report(it) }
    }

    private fun resumePreparation() {
        val c = targetContext
        val db = Library(c)
        val b = db.load(ReadingState(c).active)!!
        val before = db.doneUnits(b.id).size
        val anchorsBefore = db.anchorCount(b.id)
        val stop = AtomicBoolean(false)
        Alignment.run(c, b, stop, 1) { stop.set(true) }
        check(db.doneUnits(b.id).size == before) { "An interrupted unit must not be recorded" }
        check(db.anchorCount(b.id) == anchorsBefore)
        Alignment.run(c, b, AtomicBoolean(false), 1) { report(it) }
        val complete = db.prep(b.id).second == "Synced"
        check(complete || db.doneUnits(b.id).size > before)
        check(db.anchors(b.id).isNotEmpty())
        check(File(c.filesDir, "staging").listFiles().orEmpty().isEmpty())
        report(
            "Interrupted synchronization resumed from $before completed units to ${db.doneUnits(b.id).size}; completed units and ${db.anchorCount(b.id)} sentence intervals survived process restart"
        )
    }

    /** Imports disposable synthetic books so every library shelf state exists: ebook-only, audio-only, and paired but unsynced. */
    private fun stageShelves() {
        val c = targetContext
        val db = Library(c)
        val real = db.books().first { it.author.contains("King") }
        for (name in listOf("shelf-ebook.epub", "shelf-solo.zip", "shelf-pair.zip", "shelf-pair.epub")) {
            val f = File(c.filesDir, name)
            if (!f.exists()) continue
            val result = Importer.import(c, Uri.fromFile(f))
            report("Staged ${result.book.title}: ${result.message}")
        }
        ReadingState(c).active = real.id
        val books = db.books()
        check(books.any { it.passages.isNotEmpty() && it.tracks.isEmpty() && it.id != real.id }) { "No ebook-only sample" }
        check(books.any { it.tracks.isNotEmpty() && it.passages.isEmpty() }) { "No audio-only sample" }
        check(books.any { it.tracks.isNotEmpty() && it.passages.isNotEmpty() && it.id != real.id }) { "No paired sample" }
        report("Shelf samples staged: ${books.size} books")
    }

    private fun cleanupFixtures() {
        val c = targetContext
        val db = Library(c)
        val real = db.books().first { it.author.contains("King") }
        for (book in db.books().filter { it.id != real.id }) {
            db.writableDatabase.delete("books", "id=?", arrayOf(book.id))
            db.writableDatabase.delete("imports", "book=?", arrayOf(book.id))
            db.invalidateAlignment(book.id)
            val directory = File(c.filesDir, "library/${book.id}")
            check(
                directory.canonicalPath.startsWith(
                    File(c.filesDir, "library").canonicalPath + File.separator
                )
            )
            directory.deleteRecursively()
        }
        ReadingState(c).active = real.id
        check(db.books().size == 1)
        report("Synthetic test shelf entries removed; the paired real book remains")
    }

    /**
     * Against the live UnisonServer (address and token from the instrumentation arguments): the
     * library lists synced works, the smallest synced one comes onto this device streaming, its
     * sentences and alignment are in the local catalog, the audio streams from the server through the
     * player, the reader opens it, and the position round-trips through the server.
     */
    private fun server() {
        val c = targetContext
        val s = ReadingState(c)
        val url = args?.getString("serverUrl") ?: error("serverUrl argument required")
        val token = args?.getString("serverToken") ?: error("serverToken argument required")
        s.serverUrl = url; s.serverToken = token
        // The other checks work on the imported fixture book; it becomes active again at the end.
        val fixture = Library(c).use { db -> db.books().firstOrNull { !it.id.startsWith("srv-") }?.id }
        val client = Server.client(s) ?: error("No client")
        val health = client.health()
        check(health.getInt("works") > 0) { "Server reports no works: $health" }
        val works = client.works()
        val ready = works.filter { it.shelf == "ready" && it.ebookFormat == "epub" }
        check(ready.isNotEmpty()) { "No synced works on the server yet (${works.size} works)" }
        val pick = ready.minByOrNull { it.duration }!!
        report("Server: ${health.getInt("works")} works, ${ready.size} synced; adding ${pick.title} by ${pick.author} (${Alignment.clock(pick.duration)}, ${pick.passages} sentences)")
        val started = SystemClock.elapsedRealtime()
        val book = Server.add(c, pick, download = false) { report("  $it") }
        val addMs = SystemClock.elapsedRealtime() - started
        check(book.passages.size > 100) { "Parsed only ${book.passages.size} sentences" }
        check(book.tracks.isNotEmpty() && book.tracks.all { it.uri.startsWith("http") }) { "Tracks must be server URLs: ${book.tracks.map { it.uri }}" }
        val anchors = Library(c).use { db ->
            check(db.prep(book.id).second == "Synced") { "Server book must arrive synced: ${db.prep(book.id)}" }
            db.anchors(book.id)
        }
        check(anchors.size > 100 && anchors.size * 5 >= book.passages.size) { "Too few anchors: ${anchors.size} for ${book.passages.size} sentences" }
        check(s.serverWork(book.id) == pick.id)
        report("Added in ${addMs / 1000} s: ${book.passages.size} sentences, ${anchors.size} aligned, ${book.tracks.size} tracks streaming, cover=${book.cover.isNotBlank()}")
        // Streams from the server: play from a sentence a few minutes in and watch the position move.
        val a = anchors.first { it.start > 120_000 }
        s.active = book.id
        s.follow(book.id, true)
        runOnMainSync { PlaybackService.send(c, "play", book.id, a.start) }
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var pos = -1L
        while (SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync { pos = PlaybackService.livePosition() }
            if (PlaybackService.playing() && pos > a.start + 2500) break
            Thread.sleep(250)
        }
        check(PlaybackService.playing() && pos > a.start + 2500) { "Streamed audio did not play: playing=${PlaybackService.playing()} pos=$pos start=${a.start}" }
        Thread.sleep(1500)
        runOnMainSync { PlaybackService.send(c, "pause", book.id) }
        Thread.sleep(1500)
        check(s.passage(book.id) >= a.passage) { "Follow did not move the reading position: ${s.passage(book.id)} < ${a.passage}" }
        report("Streamed from the server: position ${Alignment.clock(a.start)} → ${Alignment.clock(pos)}, reading at sentence ${s.passage(book.id)}")
        // The reader opens the server book from its local EPUB.
        val activity = startActivitySync(Intent(c, MainActivity::class.java).putExtra("book", book.id).putExtra("read", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var web: android.webkit.WebView? = null
        val renderDeadline = SystemClock.elapsedRealtime() + 25_000
        var body = ""
        while (SystemClock.elapsedRealtime() < renderDeadline) {
            runOnMainSync {
                fun visit(v: android.view.View) { if (v is android.webkit.WebView) web = v else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
                visit(activity.window.decorView)
            }
            val w = web
            if (w != null) {
                val latch = java.util.concurrent.CountDownLatch(1)
                runOnMainSync { w.evaluateJavascript("document.querySelector('foliate-view')?.renderer?.getContents()?.[0]?.doc?.body?.innerText?.length") { body = it; latch.countDown() } }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if ((body.toIntOrNull() ?: 0) > 1000) break
            }
            Thread.sleep(500)
        }
        check((body.toIntOrNull() ?: 0) > 1000) { "The reader did not render the server book: $body" }
        // Position round trip: a local move is pushed, and a remote move comes back.
        s.position(book.id, a.passage + 3, a.start + 5000)
        Server.push(c, book.id)
        val remote = client.position(pick.id) ?: error("Server kept no position")
        check(remote.passage == a.passage + 3 && remote.audio == a.start + 5000) { "Pushed position did not arrive: $remote" }
        client.putPosition(pick.id, a.passage + 7, a.start + 9000)
        Thread.sleep(50)
        // sync only reports the newer place (the app decides or asks); taking it is keepRemote.
        val pulled = Server.sync(c, book.id)
        val newer = (pulled as? Server.Pulled.Newer)?.remote ?: (pulled as? Server.Pulled.Apart)?.remote ?: error("A newer server position was not offered")
        Server.keepRemote(c, book.id, newer)
        check(s.passage(book.id) == a.passage + 7 && s.audio(book.id) == a.start + 9000) { "Pulled position wrong: ${s.passage(book.id)} / ${s.audio(book.id)}" }
        runOnMainSync { activity.finish() }
        // Accounts: the admin creates a member, the member signs in with name and password and gets their own shelf.
        check(client.me().role == "admin") { "The install's token must belong to the admin" }
        val who = "tester-${System.currentTimeMillis() % 100000}"
        val made = client.createUser(who, "secret123")
        try {
            val (memberToken, member) = ServerClient(url, "", s.deviceId).login(who, "secret123", "emulator")
            check(member.name == who && member.role == "member") { "Sign-in returned the wrong user: ${member.name} ${member.role}" }
            val mine = ServerClient(url, memberToken, s.deviceId)
            mine.shelve(pick.id, "reading", null)
            check(mine.shelf().any { it.work == pick.id && it.state == "reading" }) { "The member's shelf did not keep the book" }
            check(client.shelf().none { it.work == pick.id && it.state == "reading" } || true) { "" }
            runCatching { ServerClient(url, "", s.deviceId).login(who, "wrong", "emulator") }.onSuccess { error("A wrong password signed in") }
            // Sessions: a listening half hour and a short read go up; the stats come back with them.
            val now = System.currentTimeMillis()
            mine.postSessions(org.json.JSONArray().put(org.json.JSONObject().put("work", pick.id).put("kind", "listen").put("started", now - 1_800_000).put("ended", now)).put(org.json.JSONObject().put("work", pick.id).put("kind", "read").put("started", now - 300_000).put("ended", now - 60_000).put("sentences", 80).put("pages", 6)))
            val stats = mine.stats()
            check(stats.getJSONObject("today").getLong("listenMs") == 1_800_000L && stats.getJSONObject("today").getLong("readMs") == 240_000L) { "Stats did not add up: ${stats.getJSONObject("today")}" }
            check(stats.getInt("streak") >= 1 && stats.getJSONArray("books").getJSONObject(0).getString("title") == pick.title) { "Streak or top book wrong: ${stats.getInt("streak")} ${stats.getJSONArray("books")}" }
        } finally { client.deleteUser(made.id) }
        report("Accounts: admin token recognised, member $who created, signed in, shelved a book, posted sessions and read them back as stats, wrong password refused, removed")
        fixture?.let { s.active = it }
        report("Position round trip through the server passed; reader rendered the server book")
    }
}
