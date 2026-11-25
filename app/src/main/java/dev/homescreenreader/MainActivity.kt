package dev.homescreenreader

import android.content.*
import java.io.File
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
/** The household's server, filled in on the sign-in screen; Change reveals the field for another. */
/** The address the sign-in form starts with: `unison.defaultServer` in local.properties (not in the repository), or nothing. */
private const val DEFAULT_SERVER = BuildConfig.DEFAULT_SERVER

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var state: ReadingState
    private lateinit var db: Library
    private var current: Book? = null
    private var reader: FormattedReader? = null
    private var detailStatus: TextView? = null
    private var syncLine: TextView? = null
    private val syncLines = mutableListOf<Pair<TextView, Book>>()
    private var redisplay: (() -> Unit)? = null
    private var shelfSignature = ""
    /** What the library screens show that can change underneath them; the tick re-renders only when this moves. */
    private fun shelfSignatureNow() = db.books().joinToString { it.id + readiness(it) + state.finished(it.id) + state.onShelf(it.id) }
    private var overviewRefresh: Runnable? = null
    private var attach: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastPosition = -1
    private var lastServerPush = 0L
    // A reading session: the time the reader is open while narration is not playing, and what was read.
    private var readingBook: String? = null
    private var readStart = 0L
    private var readMs = 0L
    private var readPassage = 0
    private var pagesTurned = 0
    private var lastSessionFlush = 0L
    private fun closeReading() {
        val id = readingBook ?: return
        readingBook = null
        // A minute or more of reading forward teaches the pace used for "time left"; jumps (contents, search) fall outside the plausible range.
        current?.takeIf { it.id == id && readMs >= 60_000 }?.let { b ->
            val w = wordsBefore(b); val from = readPassage.coerceIn(0, b.passages.size); val to = state.passage(id).coerceIn(0, b.passages.size)
            val sample = (w[to] - w[from]) / (readMs / 60000f)
            if (to > from && w[to] - w[from] >= 150 && sample in 80f..700f) state.readWpm = (state.readWpm.takeIf { it > 0 } ?: 230f) * .7f + sample * .3f
        }
        if (readMs >= 3000) state.serverWork(id)?.let { state.queueSession(it, "read", readStart, readStart + readMs, (state.passage(id) - readPassage).coerceAtLeast(0), pagesTurned) }
    }
    private var serverShelfGeneration = 0
    /** Whether the sentence at [lastPosition] carries the narration highlight. */
    private var highlighted = false
    private var wasPlaying = false
    private val ui by lazy { BookishUi(this) }
    private var pageFrame: ReaderPageFrame? = null
    private var readingShell: FrameLayout? = null
    private var readingChrome: LinearLayout? = null
    private var chapterLabel: TextView? = null
    private var readingFooter: TextView? = null
    private var miniTime: TextView? = null
    private var miniPlay: ImageButton? = null
    private var followButton: MaterialButton? = null
    private var listenButton: MaterialButton? = null
    private var programmaticGoAt = 0L
    private var chosenAt = 0L
    private var brightnessPill: TextView? = null
    private var readerInsets: androidx.core.graphics.Insets? = null
    private var browsedAt = 0L
    private var bottomChrome: LinearLayout? = null
    private var listeningDock: LinearLayout? = null
    private var dockTime: TextView? = null
    private var dockPause: MaterialButton? = null
    private var chromeVisible = false
    private var pageFraction = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = ReadingState(this)
        db = Library(this)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // In the reader the first Back puts the controls away; otherwise Back is the screen before this one.
                    // From the Library, Back is Home; from Home, out of the app.
                    if (reader != null && chromeVisible) toggleChrome(false)
                    else if (reader == null && trail.lastOrNull() == "library" && state.libraryTab != "shelf") goHome()
                    else goBack(backTarget)
                }
            },
        )
        attach = savedInstanceState?.getString("attach")
        if (BuildConfig.DEBUG) DebugServer.preparing = getSharedPreferences("debug", 0).getString("simulatePreparing", null)?.split(':')?.let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 50) }
        // A fresh start opens at Home; a redrawn screen stays where it was.
        if (savedInstanceState == null) state.libraryTab = "shelf"
        // unisonreader://connect?url=…&token=… from a browser or adb connects this app to a UnisonServer.
        // It only asks: nothing changes until the person agrees (see connectByLink), and a redrawn screen does not ask again.
        val link = intent?.data?.takeIf { savedInstanceState == null && it.scheme == "unisonreader" && it.host == "connect" }
        if (link != null) intent.data = null
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        // "Play <book> on Unison": the audiobook whose title or author holds the words asked for (else the last one played) starts.
        val asked = intent?.takeIf { savedInstanceState == null && it.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH }?.let { i ->
            val words = i.getStringExtra(android.app.SearchManager.QUERY).orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val books = db.books().filter { it.tracks.isNotEmpty() }
            (books.firstOrNull { b -> val hay = (b.title + " " + b.author).lowercase(); words.isNotEmpty() && words.all { it in hay } } ?: books.firstOrNull { it.id == state.active })?.id
        }
        if (asked != null) PlaybackService.send(this, "play", asked)
        // Redrawn (a rotation, a theme or text-size change, or brought back by the system): the same screen, with the
        // screens behind it, the library's search and filter, and how far it was scrolled.
        val before = savedInstanceState?.getStringArrayList("trail")?.takeIf { it.isNotEmpty() }
        val id = if (before != null) before.last().takeIf { it.startsWith("book:") || it.startsWith("reader:") }?.substringAfter(':') else asked ?: intent.getStringExtra("book")
        val b = id?.let { db.load(it) }
        Alignment.autoStart(this, b ?: state.active.takeIf { it.isNotBlank() }?.let { db.load(it) })
        if (before != null && savedInstanceState != null) {
            librarySearch = savedInstanceState.getString("librarySearch").orEmpty()
            libraryFilter = savedInstanceState.getString("libraryFilter") ?: libraryFilter
            booksShown = savedInstanceState.getInt("booksShown", booksShown)
            trail.addAll(before)
            while (trail.isNotEmpty() && !open(trail.last())) trail.removeAt(trail.lastIndex)
            if (trail.isEmpty()) { if (state.serverToken.isBlank() && !state.standalone) signInScreen() else library() }
            else if (trail.last() == before.last()) { val y = savedInstanceState.getInt("scroll"); screenScroll?.let { s -> s.post { s.scrollTo(0, y) } } }
        }
        else if (b != null) {
            if (savedInstanceState?.getBoolean("reader") == true || intent.getBooleanExtra("read", false)) showReader(b) else details(b)
            if (intent.getBooleanExtra("listen", false)) listen(b)
        } else if (state.serverToken.isBlank() && !state.standalone) signInScreen()
        else library()
        if (link != null) connectByLink(link.getQueryParameter("url"), link.getQueryParameter("token"))
    }

    /**
     * A `unisonreader://connect` link asks to point this app at a server with a key. Any page or app can make such a
     * link, so the person is shown where it leads and nothing changes unless they agree. Agreeing is a change of
     * account: what this phone held for the previous server's books is forgotten, as at any other sign-in.
     */
    private fun connectByLink(url: String?, token: String?) {
        if (url.isNullOrBlank() || token.isNullOrBlank()) return
        val address = ServerClient.normalize(url)
        val host = Uri.parse(address).host ?: address
        val plain = address.startsWith("http://")
        MaterialAlertDialogBuilder(this).setTitle("Connect to $host?")
            .setMessage("A link asks this app to use the server at $address" + (if (plain) ", over a connection that is not encrypted" else "") + ".\n\nOnly continue if you were expecting this. Your books and places would then come from that server, and what this phone holds for the current server's books is forgotten.")
            .setPositiveButton("Connect") { _, _ ->
                PlaybackService.forget(); stopService(Intent(this, PlaybackService::class.java))
                state.forgetPersonalState()
                state.serverUrl = address; state.serverToken = token; state.serverUser = ""; state.serverRole = ""; state.lastAccount = ""; state.standalone = false
                serverWorks = null; serverShelf = null; serverSeriesProgress = null; serverLists = null; shelfReconciled = false; statsLine = null
                note("Connected to $host"); library()
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("attach", attach)
        out.putStringArrayList("trail", ArrayList(trail))
        out.putString("librarySearch", librarySearch); out.putString("libraryFilter", libraryFilter); out.putInt("booksShown", booksShown)
        out.putInt("scroll", if (reader == null) screenScroll?.scrollY ?: 0 else 0)
    }

    /**
     * The screens the person came through, oldest first, each as a route that `open` can draw again; the last is the
     * one showing. Back (the key, the gesture and every on-screen arrow) returns to the one before, wherever that was.
     */
    private val trail = ArrayList<String>()
    /** How far each screen left behind was scrolled, so coming back lands where the person was. */
    private val scrolls = HashMap<String, Int>()
    private var screenScroll: ScrollView? = null
    private var librarySearch = ""

    /**
     * Called first by every screen. A screen already on the trail is returned to (what was above it is dropped), so a
     * screen that sends the person "up" needs no knowledge of how they arrived; a new screen goes on top; a screen
     * redrawing itself changes nothing.
     */
    private fun arrive(route: String) {
        val leaving = trail.lastOrNull()
        if (leaving != null && leaving != route) screenScroll?.let { scrolls[leaving] = it.scrollY }
        val signedOut = state.serverToken.isBlank() && !state.standalone
        if (route == "library" || (route == "signin" && signedOut)) { trail.clear(); trail.add(route); return }
        val at = trail.indexOf(route)
        if (at >= 0) { while (trail.size > at + 1) trail.removeAt(trail.lastIndex); return }
        // Opened straight from outside (a widget, a notification): the library is behind it.
        if (trail.isEmpty() && !signedOut) trail.add("library")
        // The reader always has its book's page behind it.
        if (route.startsWith("reader:")) { val page = "book:" + route.substringAfter(':'); if (page !in trail) trail.add(page) }
        // One year of the statistics replaces another.
        if (route.startsWith("year:") && trail.lastOrNull()?.startsWith("year:") == true) trail.removeAt(trail.lastIndex)
        trail.add(route)
    }

    /** Draws the screen a route names. False when it can no longer be shown (its book is gone from this device). */
    private fun open(route: String): Boolean {
        val arg = route.substringAfter(':', "")
        fun book() = db.load(arg.substringBefore('|'))
        when (route.substringBefore(':')) {
            "library" -> library()
            "signin" -> signInScreen()
            "shelf" -> shelfPage()
            "series" -> seriesPage(arg)
            "author" -> authorPage(arg)
            "narrator" -> narratorPage(arg)
            "player" -> { val b = book() ?: return false; if (b.tracks.isEmpty()) return false; playerScreen(b) }
            "work" -> workDetail(worksSeen[arg] ?: serverWorks?.firstOrNull { it.id == arg } ?: return false)
            "kind" -> shelfScreen(runCatching { Readiness.valueOf(arg) }.getOrNull() ?: return false)
            "book" -> details(book() ?: return false)
            "reader" -> { val b = book() ?: return false; if (b.passages.isEmpty()) return false; showReader(b) }
            "marks" -> marksScreen(book() ?: return false)
            "overview" -> syncOverview(book() ?: return false)
            "search" -> searchScreen(book() ?: return false, arg.substringAfter('|') == "true")
            "allMarks" -> allMarksScreen()
            "settings" -> settings()
            "stats" -> statsScreen()
            "year" -> yearScreen(arg.toIntOrNull())
            "downloads" -> downloadsScreen()
            "devices" -> devicesScreen()
            "users" -> usersScreen()
            "lists" -> listsScreen()
            "list" -> listScreen(arg)
            "wanted" -> wantedScreen()
            "lookup" -> lookupScreen()
            "found" -> { if (lookupHits.getOrNull(arg.toIntOrNull() ?: -1) == null) return false; foundScreen(arg.toInt()) }
            else -> return false
        }
        return true
    }

    /** Back: the screen before this one, scrolled as it was left. With nothing behind, `otherwise` (a screen's own parent) or out of the app. */
    private fun goBack(otherwise: (() -> Unit)? = null) {
        while (trail.size > 1) {
            trail.removeAt(trail.lastIndex)
            val to = trail.last()
            if (open(to)) { val y = scrolls[to] ?: 0; if (y > 0) screenScroll?.let { s -> s.post { s.scrollTo(0, y) } }; return }
        }
        if (otherwise != null) { trail.clear(); otherwise() } else finish()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        handler.post(followTick)
        playerUpdate?.let { handler.removeCallbacks(it); handler.post(it) }
        // Whatever was changed without a connection goes up when the app comes forward.
        kotlin.concurrent.thread { runCatching { Server.settle(this) } }
    }

    override fun onPause() {
        // The reading session pauses with the app; what is read after resuming counts towards a new one.
        if (readingBook != null) { closeReading(); current?.let { b -> if (reader != null) { readingBook = b.id; readStart = System.currentTimeMillis(); readMs = 0; readPassage = state.passage(b.id); pagesTurned = 0 } } }
        kotlin.concurrent.thread { Server.settle(this) }
        current?.id?.let { id -> if (state.serverWork(id) != null) kotlin.concurrent.thread { Server.push(this, id) } }
        handler.removeCallbacks(tick)
        handler.removeCallbacks(followTick)
        playerUpdate?.let { handler.removeCallbacks(it) }
        super.onPause()
    }

    /** Five times a second while following narration: move the highlight (and the page) as the audio reaches each sentence, leading by a quarter second so page turns land on time. */
    private val followTick = object : Runnable {
        override fun run() {
            val b = current
            if (b != null && reader != null && state.follow(b.id) && PlaybackService.playingBook() == b.id && PlaybackService.playing()) {
                // Smooth follow engages as soon as narration is heard to be playing, not on the slower chrome tick.
                applyFollowScroll(b, true)
                val pos = PlaybackService.livePosition()
                if (pos >= 0) {
                    var a = db.atTime(b.id, pos)
                    if (a != null && pos >= a.end - 250) db.atTime(b.id, a.end)?.let { a = it }
                    val p = a?.passage
                    // The sentence narration starts with was placed before the audio began, so it is
                    // not yet highlighted: highlight it now rather than waiting for the next one.
                    if (p != null && (p != lastPosition || !highlighted)) { state.audio(b.id, pos, p); goTo(p) }
                }
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onDestroy() {
        reader?.destroy()
        db.close()
        super.onDestroy()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 16f) = ui.text(text, size).apply {
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(text: String, action: () -> Unit) = ui.button(text, false, action)

    /** Where the system Back key goes from a screen that is not a book's; set by the page header. */
    private var backTarget: (() -> Unit)? = null

    /** The bar along the bottom of every ordinary screen (Home, Library), and above it what is playing. */
    private var navBar: LinearLayout? = null
    private var miniBar: LinearLayout? = null
    private var miniBarBook: Book? = null
    private var miniBarCover: FrameLayout? = null
    private var miniBarTitle: TextView? = null
    private var miniBarLine: TextView? = null
    private var miniBarPlay: ImageButton? = null
    private var playerUpdate: Runnable? = null

    private fun goHome() { librarySearch = ""; state.libraryTab = "shelf"; library() }
    private fun goLibrary() { librarySearch = ""; state.libraryTab = state.libraryBrowse; library() }

    /** Home and Library, on every ordinary screen: Home is the shelf (what is being read and listened to), Library the whole collection. */
    private fun bottomBar(): LinearLayout {
        val route = trail.lastOrNull().orEmpty().substringBefore(':')
        val section = when {
            route == "library" -> if (state.libraryTab == "shelf") "home" else "library"
            route in setOf("lists", "list", "wanted", "lookup", "found") -> "lists"
            route in setOf("shelf", "allMarks", "stats", "year") -> "home"
            route in setOf("series", "author", "narrator", "work", "kind") -> "library"
            else -> ""
        }
        val bar = ui.column()
        ui.rule(bar)
        val row = ui.row()
        fun item(key: String, icon: String, label: String, action: () -> Unit) {
            val on = section == key
            val cell = ui.column().apply {
                gravity = Gravity.CENTER; isClickable = true; isSelected = on; setPadding(0, dp(8), 0, dp(8))
                contentDescription = label; setOnClickListener { action() }
            }
            cell.addView(ImageView(this).apply { setImageDrawable(BookishIcon(icon, if (on) ui.ink else ui.muted)); setPadding(dp(20), dp(4), dp(20), dp(4)); if (on) background = ui.shape(ui.surfaceHigh, 16) }, LinearLayout.LayoutParams(dp(64), dp(32)))
            cell.addView(ui.text(label, 12f).apply { gravity = Gravity.CENTER; setTextColor(if (on) ui.ink else ui.muted); setPadding(0, dp(4), 0, 0); if (on) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        item("home", "home", "Home") { goHome() }
        item("library", "library", "Library") { goLibrary() }
        // Want to read, collections and the wanted list live on the server: without one there is nothing to show.
        if (state.serverUrl.isNotBlank()) item("lists", "contents", "Lists") { listsScreen() }
        bar.addView(row)
        return bar
    }

    /** What is playing (or paused in the player), one tap from its own screen. Hidden until the player holds a book. */
    private fun miniPlayer(): LinearLayout {
        val bar = ui.row().apply { visibility = View.GONE; background = ui.shape(ui.surface, 14); setPadding(dp(8), dp(6), dp(4), dp(6)); isClickable = true; contentDescription = "Open audiobook player"; setOnClickListener { miniBarBook?.let { playerScreen(it) } } }
        miniBarCover = FrameLayout(this).also { bar.addView(it, LinearLayout.LayoutParams(dp(32), dp(48))) }
        val texts = ui.column().apply { setPadding(dp(12), 0, dp(4), 0) }
        miniBarTitle = ui.text("", 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }.also { texts.addView(it) }
        miniBarLine = ui.text("", 12f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }.also { texts.addView(it) }
        bar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        miniBarPlay = ui.icon("play", "Play audiobook") { miniBarBook?.let { PlaybackService.send(this, "toggle", it.id); refreshMiniBar(); handler.postDelayed({ refreshMiniBar() }, 200) } }.also { bar.addView(it) }
        miniBar = bar; miniBarBook = null
        refreshMiniBar()
        return bar
    }

    /**
     * A play button's face. It turns to pause on the press itself, with a ring turning round it until the sound comes,
     * so a press is seen at once while a book takes its seconds to start; pressing again takes the start back.
     */
    private fun showPlay(button: android.widget.ImageButton, bookId: String, color: Int) {
        val playing = PlaybackService.playingBook() == bookId && PlaybackService.playing()
        val face = if (playing) "pause" else if (PlaybackService.starting(bookId)) "starting" else "play"
        if (button.tag == face) return
        button.tag = face
        button.setImageDrawable(BookishIcon(if (face == "play") "play" else "pause", color))
        button.foreground = if (face == "starting") BookishRing(color, dp(4), dp(2).toFloat()) else null
        button.contentDescription = if (face == "play") "Play audiobook" else "Pause audiobook"
    }

    private fun refreshMiniBar() {
        val bar = miniBar ?: return
        val id = PlaybackService.playingBook()
        if (id == null) { if (bar.visibility != View.GONE) bar.visibility = View.GONE; miniBarBook = null; return }
        var b = miniBarBook
        if (b?.id != id) {
            b = db.load(id) ?: return
            miniBarBook = b
            miniBarCover?.let { it.removeAllViews(); it.addView(ui.cover(b, 32, 48), FrameLayout.LayoutParams(-1, -1)) }
            miniBarTitle?.text = b.title
        }
        val playing = PlaybackService.playing()
        val position = PlaybackService.livePosition().takeIf { it >= 0 } ?: state.audio(b.id)
        miniBarLine?.text = listOfNotNull(b.chapters.lastOrNull { it.start <= position }?.title?.trim()?.takeIf { it.isNotBlank() }, Alignment.clock(position) + if (playing || PlaybackService.starting(b.id)) "" else "  ·  paused").joinToString("  ·  ")
        miniBarPlay?.let { showPlay(it, b.id, ui.ink) }
        if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE
    }

    private fun screen(title: String, scroll: Boolean = true, bars: Boolean = true): LinearLayout {
        closeReading(); backTarget = null; onSettings = false
        playerUpdate?.let { handler.removeCallbacks(it) }; playerUpdate = null
        pageFrame?.cancelTurn(); reader?.destroy(); reader = null; pageFrame = null
        readingShell = null; readingChrome = null; readingFooter = null; chapterLabel = null
        miniTime = null; miniPlay = null; followButton = null; detailStatus = null; syncLine = null
        listenButton = null; bottomChrome = null
        syncLines.clear(); redisplay = null; overviewRefresh?.let { handler.removeCallbacks(it) }; overviewRefresh = null
        listeningDock = null; dockTime = null; dockPause = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = -1f }
        val outer = ui.column().apply { setBackgroundColor(ui.paper) }
        root = ui.column()
        outer.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        // Signed out there is nowhere to go yet; the player has the whole screen to itself.
        val withBars = bars && (state.serverToken.isNotBlank() || state.standalone)
        navBar = null; miniBar = null; miniBarBook = null
        if (withBars) {
            outer.addView(miniPlayer(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
            navBar = bottomBar().also { outer.addView(it) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { _, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(dp(24), edges.top + dp(12), dp(24), if (withBars) 0 else edges.bottom + dp(12))
            navBar?.setPadding(edges.left, 0, edges.right, edges.bottom); insets
        }
        setContentView(outer)
        // A root attached after the first one receives no inset pass on its own.
        ViewCompat.requestApplyInsets(outer)
        WindowInsetsControllerCompat(window, outer).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = !ReadingColors.dark(this@MainActivity)
            isAppearanceLightNavigationBars = !ReadingColors.dark(this@MainActivity)
        }
        if (title.isNotBlank()) { root.addView(ui.text(title, 30f, true)); ui.space(root, 16) }
        val body = ui.column()
        screenScroll = null
        if (scroll) root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body); screenScroll = this }, LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return body
    }

    private fun row(parent: LinearLayout, vararg buttons: View) {
        val r = LinearLayout(this)
        buttons.forEach {
            r.addView(
                it,
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
            )
        }
        parent.addView(r)
    }

    private fun alert(message: String) {
        MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton("OK", null).show()
    }

    private enum class Readiness { READY, PREPARING, MISSING }

    /** A completed sync that matched almost nothing usually means the ebook and audiobook are different books. */
    private fun poorlyMatched(b: Book) = b.passages.isNotEmpty() && db.anchorCount(b.id) * 5 < b.passages.size

    private fun readiness(b: Book): Readiness = when {
        b.passages.isEmpty() || b.tracks.isEmpty() -> Readiness.MISSING
        // A book from the server arrives with the server's alignment; the device never syncs it itself.
        state.serverWork(b.id) != null -> if (db.anchorCount(b.id) > 0 && !state.syncPending(b.id)) Readiness.READY else Readiness.PREPARING
        db.prep(b.id).second == "Synced" && getSharedPreferences("alignment-versions", 0).getInt(b.id, 0) == Alignment.VERSION && !poorlyMatched(b) -> Readiness.READY
        else -> Readiness.PREPARING
    }

    /** The one line under a book that says what it needs or how far along it is. */
    private fun shelfLine(b: Book): String = when (readiness(b)) {
        // A server book with one half is whole as it is; only a book imported here can be given its other half on the phone.
        Readiness.MISSING -> if (state.serverWork(b.id) != null) (if (b.passages.isEmpty()) "Audiobook · no ebook on your server" else "Ebook · no audiobook on your server") else if (b.passages.isEmpty()) "Needs the ebook to sync" else "Needs an audiobook to sync"
        else -> syncSummary(b)
    }

    // ---- The library: My shelf, the whole collection, series and authors. Server data is cached per session.
    private var libraryFilter = "all"
    private var serverWorks: List<ServerClient.Work>? = null
    private var serverSeriesList: List<ServerClient.SeriesSummary>? = null
    private var serverAuthorsList: List<ServerClient.PersonSummary>? = null
    private var serverFetchedAt = 0L
    private var serverError: String? = null
    private var libraryGeneration = 0
    private var booksShown = 40
    private var shelfReconciled = false
    private var statsLine: String? = null
    private var statsLineAt = 0L
    private fun clockish(ms: Long): String { val m = ms / 60000; return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }

    /** This person's reading and listening: totals, streaks, the last two weeks, top books, hours of the day. */
    private fun hoursText(ms: Long): String { val h = ms / 3600000.0; return if (h >= 10) "${Math.round(h)} h" else if (h >= 1) "%.1f h".format(h) else "${ms / 60000} min" }

    /** The year as one picture (1080 x 1350), the same one the web draws: the numbers, the three books with most time, a square per day. */
    private fun yearPoster(r: org.json.JSONObject, covers: List<android.graphics.Bitmap?>): android.graphics.Bitmap {
        val w = 1080; val h = 1350
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(bitmap)
        val serif = ui.text("", 1f, true).typeface; val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val ink = 0xFF06293D.toInt(); val blue = 0xFF064D73.toInt(); val teal = 0xFF00B7BC.toInt(); val muted = 0xFF5E6B70.toInt()
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun text(s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface?, spacing: Float = 0f, maxWidth: Float = 0f) {
            p.typeface = face; p.textSize = size; p.color = color; p.letterSpacing = spacing
            var t = s; if (maxWidth > 0) while (t.length > 3 && p.measureText(t) > maxWidth) t = t.dropLast(2).trimEnd() + "…"
            c.drawText(t, x, y, p)
        }
        c.drawColor(0xFFF8F5ED.toInt())
        p.color = blue; c.drawRect(0f, 0f, w.toFloat(), 14f, p)
        text("UNISON  ·  YEAR IN REVIEW", 80f, 110f, 26f, muted, Typeface.create(sans, Typeface.BOLD), .2f)
        val year = r.getInt("year")
        text(year.toString(), 72f, 300f, 190f, ink, serif)
        text("${state.serverUser.ifBlank { "A" }}’s year with books", 80f, 356f, 34f, muted, serif)
        val listen = r.getLong("listenMs"); val read = r.getLong("readMs"); val finished = r.getJSONArray("finished").length(); val active = r.getInt("daysActive"); val streak = r.getInt("longestStreak")
        fun stat(x: Float, value: String, label: String) { text(value, x, 500f, 84f, blue, serif); text(label, x, 544f, 28f, muted, sans) }
        stat(80f, hoursText(listen + read), "with books"); stat(420f, finished.toString(), if (finished == 1) "book finished" else "books finished"); stat(760f, active.toString(), if (active == 1) "day with a book" else "days with books")
        text(listOfNotNull("${hoursText(listen)} listening", "${hoursText(read)} reading", if (streak > 0) "longest streak $streak ${if (streak == 1) "day" else "days"}" else null).joinToString("   ·   "), 80f, 620f, 30f, ink, sans, maxWidth = w - 160f)
        text("MOST TIME WITH", 80f, 706f, 24f, muted, Typeface.create(sans, Typeface.BOLD), .2f)
        val books = r.getJSONArray("books"); val cw = 190f; val ch = 285f; val gap = (w - 160 - 3 * cw) / 2
        for (i in 0 until minOf(3, books.length())) {
            val b = books.getJSONObject(i); val x = 80 + i * (cw + gap); val y = 736f
            p.color = 0xFFE4DFD2.toInt(); c.drawRect(x, y, x + cw, y + ch, p)
            covers.getOrNull(i)?.let { img ->
                val s = maxOf(cw / img.width, ch / img.height); val sw = cw / s; val sh = ch / s
                c.drawBitmap(img, android.graphics.Rect(((img.width - sw) / 2).toInt(), ((img.height - sh) / 2).toInt(), ((img.width + sw) / 2).toInt(), ((img.height + sh) / 2).toInt()), android.graphics.RectF(x, y, x + cw, y + ch), p.apply { color = 0xFF000000.toInt() })
            }
            text(b.getString("title"), x, y + ch + 42, 28f, ink, serif, maxWidth = minOf(cw + gap - 30, w - 60 - x))
            text(hoursText(b.getLong("listenMs") + b.getLong("readMs")), x, y + ch + 76, 24f, muted, sans)
        }
        if (books.length() == 0) text("Nothing listened to or read yet this year.", 80f, 800f, 30f, muted, serif)
        // A square per day, in weeks that start on Monday.
        val days = r.getJSONArray("days"); val first = java.time.LocalDate.of(year, 1, 1); val offset = first.dayOfWeek.value - 1
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i); val ms = d.getLong("listenMs") + d.getLong("readMs")
            p.color = when { ms <= 0L -> 0x297F7F7F; ms < 15 * 60000L -> 0x4D00B7BC; ms < 45 * 60000L -> 0x8500B7BC.toInt(); ms < 90 * 60000L -> 0xC200B7BC.toInt(); else -> teal }
            val col = (i + offset) / 7; val row = (i + offset) % 7; val x = 80 + col * 17.3f; val y = 1165 + row * 17.3f
            c.drawRoundRect(x, y, x + 15, y + 15, 3f, 3f, p)
        }
        val hour = r.optInt("topHour", -1); val author = r.optString("topAuthor")
        text(listOfNotNull(author.takeIf { it.isNotBlank() }?.let { "Most read author: $it" }, hour.takeIf { it >= 0 }?.let { "Favourite hour: ${(it + 11) % 12 + 1} ${if (it < 12) "am" else "pm"}" }).joinToString("   ·   "), 80f, 1318f, 24f, muted, sans, maxWidth = w - 160f)
        p.color = teal; c.drawRect(0f, h - 8f, w.toFloat(), h.toFloat(), p)
        return bitmap
    }

    /** The year in review: the picture, a way to share it, the other years, and what was finished. */
    private fun yearScreen(year: Int?) {
        arrive("year:${year ?: ""}")
        current = null
        val body = screen("")
        pageHeader(body, "READING & LISTENING") { statsScreen() }
        val title = ui.text(year?.let { "Your $it" } ?: "Your year in review", 28f, true); body.addView(title)
        val box = ui.column(); body.addView(box)
        ui.space(box, 16); box.addView(ui.text("Adding it up…", 13f).apply { setTextColor(ui.muted) })
        kotlin.concurrent.thread {
            Server.flushSessions(this)
            val result = runCatching {
                val client = Server.client(state)!!; val r = client.year(year); val books = r.getJSONArray("books")
                // Covers of the three books with most time: the copy on this device, else the server's.
                val covers = (0 until minOf(3, books.length())).map { i ->
                    val work = books.getJSONObject(i).getString("work")
                    runCatching {
                        val local = db.load(Server.bookId(work))?.cover?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                        val file = local?.let { java.io.File(it) } ?: java.io.File(cacheDir, "year-cover-$work").also { if (!it.exists() || it.length() == 0L) client.download("/api/works/$work/cover", it) }
                        android.graphics.BitmapFactory.decodeFile(file.path)
                    }.getOrNull()
                }
                r to covers
            }
            runOnUiThread {
                try {
                if (box.parent == null || !box.isAttachedToWindow) return@runOnUiThread
                box.removeAllViews()
                val (r, covers) = result.getOrElse { box.addView(ui.text("Could not load: ${it.message}", 13f).apply { setTextColor(ui.muted) }); return@runOnUiThread }
                val y = r.getInt("year"); title.text = "Your $y"
                val years = r.getJSONArray("years")
                if (years.length() > 1) {
                    ui.space(box, 10); val chips = ui.row()
                    for (i in 0 until years.length()) { val other = years.getInt(i); chips.addView(ui.button(other.toString(), other == y) { if (other != y) yearScreen(other) }.apply { textSize = 13f; minHeight = dp(38) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
                    box.addView(chips)
                }
                ui.space(box, 16)
                val poster = yearPoster(r, covers)
                box.addView(android.widget.ImageView(this).apply { setImageBitmap(poster); adjustViewBounds = true; contentDescription = "Your $y in review, as a picture"; elevation = dp(2).toFloat() }, LinearLayout.LayoutParams(-1, -2))
                ui.space(box, 14)
                box.addView(ui.button("Share picture", true) {
                    runCatching {
                        val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Unison $y.png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png"); put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Unison") }
                        // One picture per year: the copy from an earlier share is replaced, not multiplied.
                        runCatching { contentResolver.delete(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Unison $y.png", "Pictures/Unison%")) }
                        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                        contentResolver.openOutputStream(uri)!!.use { poster.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share your $y"))
                    }.onFailure { note("Could not save the picture: ${it.message}") }
                }.apply { contentDescription = "Share picture" }, LinearLayout.LayoutParams(-2, -2))
                ui.space(box, 4)
                box.addView(ui.text("Sharing keeps a copy in Pictures/Unison.", 12f).apply { setTextColor(ui.muted) })
                val finished = r.getJSONArray("finished")
                if (finished.length() > 0) {
                    sectionHead(box, "FINISHED IN $y", finished.length())
                    for (i in 0 until finished.length()) {
                        val f = finished.getJSONObject(i); val row = ui.row().apply { setPadding(0, dp(6), 0, dp(6)) }
                        val info = ui.column()
                        info.addView(ui.text(f.getString("title"), 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        info.addView(ui.text(f.getString("author"), 12f).apply { setTextColor(ui.muted) })
                        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(ui.text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.getLong("finishedAt"))), 12f).apply { setTextColor(ui.muted) })
                        box.addView(row)
                    }
                }
                } catch (e: Exception) {
                    // An older server, or an answer cut short: say so rather than close the app.
                    box.removeAllViews(); box.addView(ui.text("Could not show this: the server's answer was not what this version of the app expects.", 13f).apply { setTextColor(ui.muted) })
                }
            }
        }
    }

    private fun statsScreen() {
        arrive("stats")
        current = null
        val body = screen("")
        pageHeader(body, "READING & LISTENING") { settings() }
        body.addView(ui.text("Your year in books", 28f, true))
        ui.space(body, 6)
        body.addView(ui.text(if (state.serverUser.isNotBlank()) "For ${state.serverUser}, from every device you sign in on." else "From every device connected with this server.", 13f).apply { setTextColor(ui.muted) })
        ui.space(body, 12)
        body.addView(ui.button("Your ${java.time.LocalDate.now().year} in review", false) { yearScreen(null) }.apply { contentDescription = "Year in review" }, LinearLayout.LayoutParams(-2, -2))
        val box = ui.column(); body.addView(box)
        ui.space(box, 16); box.addView(ui.text("Adding it up…", 13f).apply { setTextColor(ui.muted) })
        kotlin.concurrent.thread {
            Server.flushSessions(this)
            val result = runCatching { Server.client(state)!!.stats() }
            runOnUiThread {
                try {
                if (box.parent == null) return@runOnUiThread
                box.removeAllViews()
                val r = result.getOrElse { box.addView(ui.text("Could not load: ${it.message}", 13f).apply { setTextColor(ui.muted) }); return@runOnUiThread }
                fun period(label: String, key: String) {
                    val t = r.getJSONObject(key)
                    val row = ui.row().apply { setPadding(0, dp(8), 0, dp(8)) }
                    row.addView(ui.text(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(ui.text("${clockish(t.getLong("listenMs"))} listening   ·   ${clockish(t.getLong("readMs"))} reading", 13f).apply { setTextColor(ui.muted) })
                    box.addView(row)
                }
                sectionHead(box, "TIME WITH BOOKS")
                period("Today", "today"); period("This week", "week"); period("This month", "month"); period("This year", "year"); period("All time", "all")
                sectionHead(box, "HABIT")
                val facts = ui.row()
                for ((n, label) in listOf(r.getInt("streak") to "day streak", r.getInt("longestStreak") to "longest streak", r.getInt("daysActive") to "days with books", r.getInt("finished") to "finished")) {
                    val cell = ui.column().apply { gravity = Gravity.CENTER }
                    cell.addView(ui.text(n.toString(), 26f, true).apply { gravity = Gravity.CENTER })
                    cell.addView(ui.text(label, 11f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) })
                    facts.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                }
                box.addView(facts)
                sectionHead(box, "LAST TWO WEEKS", note = "Each bar is a day: reading on top in grey, listening beneath it in teal.")
                val days = r.getJSONArray("days")
                val recent = (maxOf(0, days.length() - 14) until days.length()).map { days.getJSONObject(it) }
                val peak = recent.maxOfOrNull { it.getLong("listenMs") + it.getLong("readMs") }?.coerceAtLeast(1) ?: 1
                val bars = ui.row().apply { gravity = Gravity.BOTTOM }
                for (d in recent) {
                    val col = ui.column().apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
                    val listen = (d.getLong("listenMs") * 110 / peak).toInt(); val read = (d.getLong("readMs") * 110 / peak).toInt()
                    col.contentDescription = "${d.getString("date")}: ${clockish(d.getLong("listenMs"))} listening, ${clockish(d.getLong("readMs"))} reading"; col.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    col.addView(View(this).apply { setBackgroundColor(ui.muted) }, LinearLayout.LayoutParams(dp(12), dp(read.coerceAtLeast(if (d.getLong("readMs") > 0) 2 else 0))))
                    col.addView(View(this).apply { setBackgroundColor(ui.live) }, LinearLayout.LayoutParams(dp(12), dp(listen.coerceAtLeast(if (d.getLong("listenMs") > 0) 2 else 0))))
                    ui.space(col, 4)
                    col.addView(ui.text(d.getString("date").takeLast(2), 10f).apply { setTextColor(ui.muted) })
                    bars.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                }
                box.addView(bars, LinearLayout.LayoutParams(-1, dp(140)))
                val books = r.getJSONArray("books")
                if (books.length() > 0) {
                    sectionHead(box, "MOST TIME WITH")
                    for (i in 0 until minOf(8, books.length())) {
                        val b = books.getJSONObject(i)
                        val row = ui.row().apply { setPadding(0, dp(6), 0, dp(6)) }
                        val info = ui.column()
                        info.addView(ui.text(b.getString("title"), 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                        info.addView(ui.text(b.getString("author"), 12f).apply { setTextColor(ui.muted) })
                        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(ui.text(clockish(b.getLong("listenMs") + b.getLong("readMs")), 13f).apply { setTextColor(ui.muted) })
                        box.addView(row)
                    }
                }
                sectionHead(box, "TIME OF DAY", note = "When you read and listen, midnight to midnight.")
                val hours = r.getJSONArray("hours")
                val hpeak = (0 until 24).maxOf { hours.getLong(it) }.coerceAtLeast(1)
                val hbars = ui.row().apply { gravity = Gravity.BOTTOM }
                for (h in 0 until 24) hbars.addView(View(this).apply { setBackgroundColor(if (hours.getLong(h) > 0) ui.live else ui.line) }, LinearLayout.LayoutParams(0, dp((hours.getLong(h) * 60 / hpeak).toInt().coerceAtLeast(2)), 1f).apply { marginEnd = dp(2) })
                box.addView(hbars, LinearLayout.LayoutParams(-1, dp(64)))
                val labels = ui.row()
                for (t in listOf("12am", "6am", "noon", "6pm", "")) labels.addView(ui.text(t, 10f).apply { setTextColor(ui.muted) }, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(labels)
                ui.space(box, 30)
                } catch (e: Exception) {
                    // An older server, or an answer cut short: say so rather than close the app.
                    box.removeAllViews(); box.addView(ui.text("Could not show this: the server's answer was not what this version of the app expects.", 13f).apply { setTextColor(ui.muted) })
                }
            }
        }
    }
    private val coverPool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "covers").apply { isDaemon = true } }

    /** One row of the collection: a local book, a server work, or a server work already on this device. */
    private inner class Entry(val book: Book?, val work: ServerClient.Work?) {
        val id: String get() = book?.id ?: Server.bookId(work!!.id)
        val title: String get() = book?.title ?: work!!.title
        val author: String get() = book?.author?.ifBlank { null } ?: work?.author?.ifBlank { null } ?: "Unknown author"
        val series: List<ServerClient.SeriesRef> get() = work?.series ?: emptyList()
        val local: Boolean get() = book != null
        val finished: Boolean get() = book != null && state.finished(book.id) > 0
        val ready: Boolean get() = if (book != null) readiness(book) == Readiness.READY else work!!.shelf == "ready"
        val line: String
            get() = when {
                book != null -> shelfLine(book)
                work!!.shelf == "ready" -> "Synced on your server · tap to add"
                work.shelf == "preparing" -> when {
                    work.status.startsWith("Ebook could not be read") || work.status.startsWith("Failed") -> work.status
                    work.passages == 0 -> "Waiting for the ebook to be read"
                    work.syncedPercent > 0 -> "Syncing on your server · ${work.syncedPercent}% · tap to add"
                    else -> "Queued for syncing on your server · tap to add"
                }
                // One half alone is still a book: an ebook to read, an audiobook to listen to.
                work.hasEbook && !work.hasAudio -> if (work.ebookFormat == "epub") "Ebook on your server · tap to add" else "Ebook on your server (${work.ebookFormat.ifBlank { "unknown format" }}) · this app reads EPUB"
                else -> "Audiobook on your server · tap to add"
            }
        fun open() { if (book != null) details(book) else workDetail(work!!) }
    }

    /** The whole collection: every server work (with its local copy when there is one) plus local-only books. */
    private fun entries(): List<Entry> {
        val local = db.books()
        val byId = local.associateBy { it.id }
        val fromServer = (serverWorks ?: emptyList()).map { w -> Entry(byId[Server.bookId(w.id)], w) }
        val serverIds = fromServer.map { it.id }.toSet()
        return (fromServer + local.filter { it.id !in serverIds }.map { Entry(it, null) }).sortedBy { it.title.lowercase() }
    }

    /** Fetches the server's works, series and authors once a minute at most, then runs `then` on the UI thread. */
    private fun withServerData(force: Boolean = false, then: () -> Unit) {
        val client = Server.client(state)
        if (client == null) { serverWorks = null; serverSeriesList = null; serverAuthorsList = null; then(); return }
        if (!force && serverWorks != null && SystemClock.elapsedRealtime() - serverFetchedAt < 60_000) { then(); return }
        val generation = libraryGeneration
        kotlin.concurrent.thread {
            val result = runCatching { Triple(client.works(), client.series(), client.authors()) }
            val shelf = runCatching { client.shelf() }.getOrNull()
            // A title, author or narrator corrected on the server reaches the copies kept on this device.
            // So does a cover an admin chose (or put back) there: the copy kept beside the book is fetched again.
            result.getOrNull()?.first?.forEach { w ->
                val id = Server.bookId(w.id)
                if (state.serverWork(id) != null && w.cover && w.coverVersion != state.coverVersion(id)) runCatching {
                    val kept = db.load(id)?.cover.orEmpty()
                    if (kept.isNotBlank()) { client.download("/api/works/${w.id}/cover", File(kept)); state.coverVersion(id, w.coverVersion) }
                }
            }
            result.getOrNull()?.first?.forEach { w -> val id = Server.bookId(w.id); if (state.serverWork(id) != null) runCatching { db.rename(id, w.title, w.author.ifBlank { db.load(id)?.author.orEmpty() }, w.narrator.ifBlank { db.load(id)?.narrator.orEmpty() }) } }
            val progress = if (shelf != null) runCatching { client.seriesProgress() }.getOrNull() else null
            // Books added while the server was still syncing them take what it has matched since.
            for (id in state.serverBooks()) if (state.syncPending(id)) runCatching { Server.refreshSync(this, id) }
            // Books this person asked for that have arrived since they were last told.
            val arrived = runCatching { client.requests() }.getOrNull()?.filter { it.mine && it.status == "arrived" }
            // Once per session the shelf here and the server's are brought into step: what this device still owes is
            // sent, and the rest follows the server, so a book taken off the shelf elsewhere stays off.
            if (shelf != null && !shelfReconciled) {
                shelfReconciled = true
                runCatching { Server.reconcileShelf(this, shelf) }
            }
            runOnUiThread {
                result.onSuccess { (w, se, au) -> serverWorks = w; serverSeriesList = se; serverAuthorsList = au; serverFetchedAt = SystemClock.elapsedRealtime(); serverError = null }
                    .onFailure { serverError = it.message ?: it.javaClass.simpleName }
                if (shelf != null) serverShelf = shelf
                if (progress != null) serverSeriesProgress = progress
                // A token the server no longer knows (expired after a year idle, or signed out from another device) sends the phone back to sign in.
                if (result.exceptionOrNull()?.message?.contains("answered 401") == true && state.serverToken.isNotBlank()) {
                    state.serverToken = ""; serverWorks = null; serverShelf = null; serverSeriesProgress = null
                    // Only from the library: a book being read is left alone, and the library asks when it is next shown.
                    if (generation == libraryGeneration && trail.lastOrNull() == "library") signInScreen("Your sign-in on this phone has expired. Sign in again to continue.")
                    return@runOnUiThread
                }
                if (generation == libraryGeneration) then()
                if (arrived != null) announceArrivals(arrived)
            }
        }
    }

    /**
     * Says once, when the library is next shown, that a book this person asked for is in the library now, with the way
     * to it. The first look only notes what had arrived before, so an old list is not announced all over again.
     */
    private fun announceArrivals(arrived: List<ServerClient.Wanted>) {
        val ids = arrived.map { it.id.toString() }.toSet()
        val seen = state.arrivalsSeen
        state.arrivalsSeen = ids
        if (seen == null || gone()) return
        val fresh = arrived.filter { it.id.toString() !in seen }
        when {
            fresh.isEmpty() -> Unit
            fresh.size == 1 -> fresh[0].let { q -> note("${q.title} is in the library now", if (q.work != null) "Open" else "See", 10000) { if (q.work != null) openWork(q.work!!) else wantedScreen() } }
            else -> note("${fresh.size} books you asked for are in the library now", "See", 10000) { wantedScreen() }
        }
    }

    /** A cover for a server work: cached from the server the first time it is shown. */
    private fun serverCover(work: ServerClient.Work, width: Int, height: Int): View {
        val frame = FrameLayout(this).apply { background = ui.shape(ui.surface, 5); clipToOutline = true; layoutParams = LinearLayout.LayoutParams(dp(width), dp(height)); contentDescription = "Cover of ${work.title}" }
        val file = File(cacheDir, if (work.coverVersion > 0) "covers/${work.id}-${work.coverVersion}.jpg" else "covers/${work.id}.jpg")
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (file.isFile) { image.setImageURI(Uri.fromFile(file)); frame.addView(image, FrameLayout.LayoutParams(-1, -1)) }
        else {
            frame.addView(ui.text(work.title, if (width > 80) 18f else 12f, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }, FrameLayout.LayoutParams(-1, -1))
            if (work.cover) Server.client(state)?.let { client ->
                coverPool.execute {
                    if (!frame.isAttachedToWindow && frame.parent == null) return@execute
                    runCatching { if (!file.isFile) client.download("/api/works/${work.id}/cover", file) }
                    if (file.isFile) runOnUiThread { if (frame.isAttachedToWindow) { frame.removeAllViews(); image.setImageURI(Uri.fromFile(file)); frame.addView(image, FrameLayout.LayoutParams(-1, -1)) } }
                }
            }
        }
        return frame
    }

    private fun coverFor(e: Entry, width: Int, height: Int): View = if (e.book != null && (e.book.cover.isNotBlank() || e.work == null)) ui.cover(e.book, width, height) else serverCover(e.work!!, width, height)

    /** A list row for an entry: cover, title, series, author, one status line; tap opens it. */
    private fun entryRow(parent: LinearLayout, e: Entry, badge: String? = null, line: String? = null, live: Boolean = false) {
        val row = ui.row().apply { gravity = Gravity.TOP; setPadding(0, dp(8), 0, dp(12)); isClickable = true; contentDescription = "Open ${e.title}"; setOnClickListener { e.open() } }
        if (e.book != null) row.setOnLongClickListener { bookOptions(e.book); true }
        row.addView(coverFor(e, 54, 82))
        val info = ui.column().apply { setPadding(dp(17), 0, dp(8), 0) }
        val titleRow = ui.row().apply { gravity = Gravity.TOP }
        if (badge != null) titleRow.addView(ui.text(badge, 12f).apply { setTextColor(ui.liveText); setPadding(0, dp(5), dp(8), 0) })
        titleRow.addView(ui.text(e.title, 18f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(titleRow)
        ui.space(info, 4)
        val under = listOfNotNull(e.author, e.series.firstOrNull()?.label).joinToString("  ·  ")
        info.addView(ui.text(under, 13f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        ui.space(info, 6)
        val status = ui.text(line ?: e.line, 12f).apply { setTextColor(if (live) ui.liveText else ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        info.addView(status); if (live && e.book != null) syncLines += status to e.book
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.icon("chevron", "Open ${e.title}", ui.muted) { e.open() })
        parent.addView(row)
    }

    private fun sectionHead(parent: LinearLayout, title: String, count: Int? = null, note: String? = null, trailing: View? = null, unit: String = "book", onTap: (() -> Unit)? = null) {
        ui.space(parent, 22)
        val row = ui.row().apply { setPadding(0, dp(4), 0, dp(4)); if (onTap != null) { minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "Open $title"; setOnClickListener { onTap() } } }
        row.addView(ui.caption(title), LinearLayout.LayoutParams(0, -2, 1f))
        if (count != null) row.addView(ui.text("$count ${if (count == 1 || unit.endsWith("s")) unit else unit + "s"}", 12f).apply { setTextColor(ui.muted) })
        if (trailing != null) row.addView(trailing)
        parent.addView(row)
        if (note != null) { ui.space(parent, 6); parent.addView(ui.text(note, 13f).apply { setTextColor(ui.muted) }) }
        ui.space(parent, 10)
    }

    private fun library() {
        arrive("library")
        current = null
        libraryGeneration++
        // The first time the shelf exists, books already in progress on this device go onto it.
        val seeded = getSharedPreferences("ui", 0)
        if (!seeded.getBoolean("shelfSeeded", false)) {
            for (b in db.books()) if (state.finished(b.id) == 0L && (state.passage(b.id) > 0 || state.audio(b.id) > 0 || b.id == state.active)) state.addToShelf(b.id)
            seeded.edit().putBoolean("shelfSeeded", true).apply()
        }
        val body = screen("")
        val mast = ui.row()
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        mast.addView(ImageView(this).apply { setImageResource(if (night) R.drawable.unison_mark_dark else R.drawable.unison_mark_light); contentDescription = "Unison" }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        mast.addView(ui.caption("UNISON  /  READER"), LinearLayout.LayoutParams(0, -2, 1f))
        if (Alignment.onDevice) mast.addView(ui.icon("plus", "Import files") { pick(null) })
        mast.addView(ui.icon("settings", "Settings") { settings() })
        body.addView(mast)
        // Home is the shelf alone; the Library has the search, its four categories and, for Books, the filters.
        val home = state.libraryTab == "shelf"
        if (!home) state.libraryBrowse = state.libraryTab
        val search = EditText(this).apply {
            hint = "Search titles, authors, series, narrators"; setTextColor(ui.ink); setHintTextColor(ui.muted)
            textSize = 14f; setSingleLine(); background = ui.shape(ui.surface, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            contentDescription = "Search the library"
        }
        val tabButtons = HashMap<String, MaterialButton>()
        val filterButtons = HashMap<String, MaterialButton>()
        // The filters are built once and stay where they were scrolled: only their colours change when the list is drawn again.
        val chips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        if (!home) {
            ui.space(body, 12)
            body.addView(ui.text("Your library", 34f, true)); ui.space(body, 8)
            body.addView(ui.text("A good place to lose yourself.", 14f).apply { setTextColor(ui.muted) })
            ui.space(body, 18)
            body.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
            // Coming back from a result, or redrawn: the search is as it was left.
            if (librarySearch.isNotEmpty()) search.setText(librarySearch)
            ui.space(body, 14)
            // Books · Series · Authors · Narrators
            val tabs = ui.row()
            for ((key, label) in listOf("books" to "Books", "series" to "Series", "authors" to "Authors", "narrators" to "Narrators")) {
                val b = ui.button(label, state.libraryTab == key) { state.libraryTab = key; state.libraryBrowse = key; booksShown = 40; search.setText(""); redisplay?.invoke() }.apply { textSize = 13f; minHeight = dp(42); setPadding(dp(4), 0, dp(4), 0); setSingleLine(true); contentDescription = "$label tab" }
                tabButtons[key] = b
                tabs.addView(b, LinearLayout.LayoutParams(0, -2, if (key == "narrators") 1.25f else 1f).apply { if (key != "narrators") marginEnd = dp(6) })
            }
            body.addView(tabs)
            val chipRow = ui.row()
            for ((key, label) in listOf("all" to "All", "ready" to "Ready", "preparing" to "Syncing", "ebook" to "Ebook only", "audio" to "Audio only", "device" to "Downloaded")) {
                val b = ui.button(label, libraryFilter == key) { libraryFilter = key; booksShown = 40; redisplay?.invoke() }.apply { minimumWidth = dp(48); textSize = 12f; minHeight = dp(36); setPadding(dp(12), 0, dp(12), 0); insetTop = 0; insetBottom = 0; contentDescription = "Filter $label" }
                filterButtons[key] = b
                chipRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            chips.addView(chipRow)
            body.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        detailStatus = label(ImportService.status, 13f).also { it.setTextColor(ui.muted); it.visibility = if (ImportService.status.isBlank()) View.GONE else View.VISIBLE; body.addView(it) }
        val content = ui.column(); body.addView(content)
        fun render() {
            content.removeAllViews(); syncLines.clear()
            for ((key, b) in tabButtons) { b.isSelected = state.libraryTab == key; b.backgroundTintList = ColorStateList.valueOf(if (state.libraryTab == key) ui.selected else ui.surface); b.setTextColor(if (state.libraryTab == key) ui.onSelected else ui.ink) }
            for ((key, b) in filterButtons) { b.isSelected = libraryFilter == key; b.backgroundTintList = ColorStateList.valueOf(if (libraryFilter == key) ui.olive else ui.surface); b.setTextColor(if (libraryFilter == key) ui.onOlive else ui.ink) }
            shelfSignature = shelfSignatureNow()
            val query = search.text.toString().trim()
            chips.visibility = if (!home && query.isBlank() && state.libraryTab == "books") View.VISIBLE else View.GONE
            if (home) { renderShelf(content); return }
            if (query.isNotBlank()) { renderSearch(content, query); return }
            when (state.libraryTab) {
                "series" -> renderSeries(content)
                "authors" -> renderAuthors(content)
                "narrators" -> renderNarrators(content)
                else -> renderBooks(content)
            }
        }
        redisplay = { render() }
        render()
        withServerData { if (redisplay != null) render() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { librarySearch = s?.toString().orEmpty(); render() }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        if (Alignment.onDevice && getSharedPreferences("import-job", 0).contains("uris")) body.addView(button("Resume interrupted import") {
            val prefs = getSharedPreferences("import-job", 0); val a = JSONArray(prefs.getString("uris", "[]"))
            startImports(ArrayList(List(a.length()) { a.getString(it) }), prefs.getString("attach", null), true)
        })
        if (db.cleanup().isNotEmpty()) body.addView(button("Review original-file cleanup") { alert(db.cleanup().joinToString("\n\n") { it.second + "\n" + it.first }) })
    }

    private fun serverLine(parent: LinearLayout) {
        when {
            state.serverToken.isBlank() -> {
                ui.space(parent, 24)
                parent.addView(ui.text("Connect to your UnisonServer to browse its whole library here.", 13f).apply { setTextColor(ui.muted) })
                ui.space(parent, 10)
                parent.addView(ui.button("Sign in to UnisonServer", false) { signInScreen() }, LinearLayout.LayoutParams(-2, -2))
            }
            serverWorks == null && serverError == null -> { ui.space(parent, 24); parent.addView(ui.text("Reaching ${ServerClient.normalize(state.serverUrl)}…", 13f).apply { setTextColor(ui.muted) }) }
            serverError != null -> { ui.space(parent, 24); parent.addView(ui.text("Server unreachable: $serverError", 13f).apply { setTextColor(ui.muted) }) }
        }
    }

    /** Home: what you are reading or listening to, what you have queued, and what you have finished. */
    private fun renderShelf(content: LinearLayout) {
        val local = db.books().associateBy { it.id }
        val shelved = state.shelf().mapNotNull { local[it] }
        val reading = shelved.filter { state.finished(it.id) == 0L }.sortedWith(compareByDescending<Book> { it.id == state.active }.thenByDescending { state.shelvedAt(it.id) })
        val finished = db.books().filter { state.finished(it.id) > 0 }.sortedByDescending { state.finished(it.id) }
        val finishedElsewhere = (serverShelf ?: emptyList()).filter { it.state == "finished" && Server.bookId(it.work) !in local }.mapNotNull { e -> serverWorks?.firstOrNull { it.id == e.work }?.let { it to (e.finishedAt ?: e.added) } }
        // Continue your story is the book in progress: opening a book's page makes it active, but a book not yet
        // begun (a next-in-series just added, say) does not take the place of the one being read. Among begun books,
        // the active one, then the one the server saw read or listened to last.
        val begun = reading.filter { state.passage(it.id) > 0 || state.audio(it.id) > 0 }
        val lastMoved = (serverShelf ?: emptyList()).associate { Server.bookId(it.work) to (it.position?.updated ?: 0L) }
        val active = begun.firstOrNull { it.id == state.active } ?: begun.maxByOrNull { lastMoved[it.id] ?: 0L }
            ?: reading.firstOrNull { it.id == state.active } ?: reading.firstOrNull()
        if (active != null) {
            ui.space(content, 28); content.addView(ui.caption("CONTINUE YOUR STORY")); ui.space(content, 18)
            val hero = ui.row().apply { gravity = Gravity.TOP }
            hero.addView(ui.cover(active, 112, 170).apply { elevation = dp(3).toFloat(); setOnClickListener { details(active) } })
            val info = ui.column().apply { setPadding(dp(20), dp(3), 0, 0) }
            info.addView(ui.text(active.title, 23f, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            ui.space(info, 10); info.addView(ui.text(active.author, 13f).apply { setTextColor(ui.muted) })
            ui.space(info, 18)
            val percentage = if (active.passages.isNotEmpty()) state.passage(active.id) * 100 / active.passages.size else if (active.duration > 0) (state.audio(active.id) * 100 / active.duration).toInt() else 0
            info.addView(ui.text("$percentage% through your story", 12f).apply { setTextColor(ui.muted) })
            ui.space(info, 9)
            info.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = percentage; progressTintList = ColorStateList.valueOf(ui.olive); progressBackgroundTintList = ColorStateList.valueOf(ui.line)
            }, LinearLayout.LayoutParams(-1, dp(3)))
            ui.space(info, 14)
            info.addView(ui.text(if (active.tracks.isNotEmpty() && active.passages.isNotEmpty()) "EBOOK  +  AUDIO" else if (active.tracks.isNotEmpty()) "AUDIOBOOK" else "EBOOK", 10f).apply { letterSpacing = .08f; setTextColor(ui.olive) })
            ui.space(info, 6)
            info.addView(ui.text(shelfLine(active), 12f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            hero.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(hero)
            ui.space(content, 22)
            val actions = ui.row()
            // The server is asked for the newest place first (a second and a half at most): the button pressed says it is opening.
            val continueLabel = if (active.passages.isNotEmpty()) "Continue reading" else "Continue listening"
            lateinit var go: MaterialButton
            go = ui.button(continueLabel, true) {
                withCurrentPlace(active, { go.text = if (it) "Opening…" else continueLabel }) { if (active.passages.isNotEmpty()) showReader(active) else playerScreen(active) }
            }
            actions.addView(go, LinearLayout.LayoutParams(0, dp(54), 1f))
            // Listening is a common way in to a book: the headphones are a button of
            // their own, as tall as Continue reading and wide enough to hit without aiming.
            if (active.tracks.isNotEmpty() && active.passages.isNotEmpty()) {
                lateinit var ear: android.widget.ImageButton
                ear = ui.icon("headphones", "Open audiobook player") { state.active = active.id; withCurrentPlace(active, { ear.foreground = if (it) BookishRing(ui.ink, dp(3), dp(2).toFloat()) else null }) { playerScreen(active) } }
                    .apply { background = ui.pressable(ui.surface, 14); setPadding(dp(32), dp(12), dp(32), dp(12)) }
                actions.addView(ear, LinearLayout.LayoutParams(dp(88), dp(48)).apply { marginStart = dp(10) })
            }
            content.addView(actions); ui.space(content, 30); ui.rule(content)
        }
        if (state.serverUrl.isNotBlank()) {
            ui.space(content, 22)
            val line = ui.text("Your reading & listening  ›", 13f).apply { setTextColor(ui.olive); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "Your statistics"; setOnClickListener { statsScreen() } }
            content.addView(line)
            statsLine?.let { line.text = it }
            // Shown at once from the last answer, and asked for again once that is a minute old.
            if (statsLine == null || SystemClock.elapsedRealtime() - statsLineAt > 60_000) kotlin.concurrent.thread {
                statsLineAt = SystemClock.elapsedRealtime()
                val text = runCatching {
                    Server.flushSessions(this); val r = Server.client(state)!!.stats(); val week = r.getJSONObject("week")
                    "This week  ·  ${clockish(week.getLong("listenMs"))} listening  ·  ${clockish(week.getLong("readMs"))} reading" + (if (r.optInt("streak") > 0) "  ·  ${r.optInt("streak")}-day streak" else "") + "  ›"
                }.getOrNull() ?: return@thread
                statsLine = text
                runOnUiThread { if (line.isAttachedToWindow) line.text = text }
            }
        }
        // Shelved on another device and not here yet; they count, and the shelf view brings them over.
        val elsewhere = (serverShelf ?: emptyList()).filter { it.state != "finished" && Server.bookId(it.work) !in local }.mapNotNull { e -> serverWorks?.firstOrNull { it.id == e.work } }
        val upNextIds = nextInSeries(serverSeriesProgress).map { Server.bookId(it.first.id) }.toSet()
        val inProgress = reading.count { it.id !in upNextIds } + elsewhere.count { Server.bookId(it.id) !in upNextIds }
        val finishedCount = finished.size + finishedElsewhere.size
        // The shelf itself is its own screen; here, one row into it.
        if (inProgress == 0 && finishedCount == 0) {
            // An empty shelf still leads to My shelf when a server is connected: Lists and the wanted list live there.
            if (state.serverUrl.isNotBlank()) sectionHead(content, "ON MY SHELF", 0, trailing = ui.icon("chevron", "Open my shelf", ui.muted) { shelfPage() }) { shelfPage() }
            else sectionHead(content, "ON MY SHELF", 0)
            content.addView(ui.text("Nothing here yet. Browse Books, Series or Authors and add what you want to read next; it waits for you here.", 14f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) })
            ui.space(content, 14)
            content.addView(ui.button("Browse the library", true) { goLibrary() }, LinearLayout.LayoutParams(-2, -2))
        } else {
            ui.space(content, 22)
            val row = ui.row().apply { setPadding(0, dp(10), 0, dp(10)); isClickable = true; contentDescription = "Open my shelf"; setOnClickListener { shelfPage() } }
            val info = ui.column()
            info.addView(ui.caption("ON MY SHELF"))
            ui.space(info, 6)
            val upNext = upNextIds.size
            info.addView(ui.text(listOfNotNull("$inProgress in progress".takeIf { inProgress > 0 }, "$upNext next in series".takeIf { upNext > 0 }, "$finishedCount finished".takeIf { finishedCount > 0 }).joinToString("  ·  "), 15f))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.icon("chevron", "Open my shelf", ui.muted) { shelfPage() })
            content.addView(row)
        }
        serverLine(content)
    }

    /** My shelf as a wall of covers: what I am reading, then what I have finished. Tap opens, long-press for options. */
    private fun shelfPage() {
        arrive("shelf")
        current = null
        val body = screen("")
        pageHeader(body, "MY SHELF") { library() }
        val local = db.books()
        val reading = state.shelf().mapNotNull { id -> local.firstOrNull { it.id == id } }.filter { state.finished(it.id) == 0L }.sortedWith(compareByDescending<Book> { it.id == state.active }.thenByDescending { state.shelvedAt(it.id) })
        val finished = local.filter { state.finished(it.id) > 0 }.sortedByDescending { state.finished(it.id) }
        // Shelved on another device and not here yet: shown with the server's cover; a tap opens its page to bring it over.
        val elsewhere = (serverShelf ?: emptyList()).filter { it.state != "finished" && Server.bookId(it.work) !in local.map { b -> b.id } }.mapNotNull { e -> serverWorks?.firstOrNull { it.id == e.work } }
        fun grid(parent: LinearLayout, entries: List<Entry>, line: (Entry) -> String) {
            for (rowEntries in entries.chunked(3)) {
                val row = ui.row().apply { gravity = Gravity.TOP }
                for (e in rowEntries) {
                    val cell = ui.column().apply { setPadding(0, dp(6), dp(12), dp(14)); isClickable = true; contentDescription = "Open ${e.title}"; setOnClickListener { e.open() }; if (e.book != null) setOnLongClickListener { bookOptions(e.book); true } }
                    cell.addView(coverFor(e, 100, 150).apply { elevation = dp(2).toFloat() })
                    ui.space(cell, 8)
                    cell.addView(ui.text(e.title, 13f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    cell.addView(ui.text(line(e), 11f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                }
                for (i in rowEntries.size until 3) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                parent.addView(row)
            }
        }
        // A next-in-series book not yet started sits in its own section below, not among the books in progress.
        val upNext = nextInSeries(serverSeriesProgress).map { Server.bookId(it.first.id) }.toSet()
        val inProgress = reading.filter { it.id !in upNext }.map { Entry(it, serverWorks?.firstOrNull { w -> Server.bookId(w.id) == it.id }) } + elsewhere.filter { Server.bookId(it.id) !in upNext }.map { Entry(null, it) }
        body.addView(ui.text("My shelf", 34f, true)); ui.space(body, 6)
        val highlightsLink = if (local.any { state.marks(it.id).length() > 0 } || state.serverUrl.isNotBlank()) ui.text("My highlights  ›", 14f).apply { setTextColor(ui.olive); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "My highlights"; setOnClickListener { allMarksScreen() } } else null
        val finishedCount = finished.size + (serverShelf ?: emptyList()).count { e -> e.state == "finished" && local.none { it.id == Server.bookId(e.work) } && serverWorks?.any { it.id == e.work } == true }
        body.addView(ui.text("${inProgress.size} ${if (inProgress.size == 1) "book" else "books"} in progress" +(if (finishedCount > 0) "  ·  $finishedCount finished" else ""), 14f).apply { setTextColor(ui.muted) })
        highlightsLink?.let { body.addView(it) }
        if (state.serverUrl.isNotBlank()) body.addView(ui.text("Lists  ›", 14f).apply { setTextColor(ui.olive); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "Lists"; setOnClickListener { listsScreen() } })
        ui.space(body, 10)
        if (inProgress.isEmpty()) { ui.space(body, 12); body.addView(ui.text("Nothing on the shelf. Add books from Books, Series or Authors.", 14f).apply { setTextColor(ui.muted) }) }
        else grid(body, inProgress) { e -> val b = e.book; when { b == null -> "On another device"; b.passages.isNotEmpty() -> "${state.passage(b.id) * 100 / b.passages.size}%"; b.duration > 0 -> "${(state.audio(b.id) * 100 / b.duration).toInt()}%"; else -> "" } }
        // A finished book in a series offers the next one; fetched fresh, since a book may have just been finished.
        val nextBox = ui.column(); body.addView(nextBox)
        fun showNext(progress: List<ServerClient.SeriesProgress>?) {
            nextBox.removeAllViews()
            val next = nextInSeries(progress)
            if (next.isEmpty()) return
            sectionHead(nextBox, "NEXT IN YOUR SERIES", next.size)
            val byWork = next.associate { it.first.id to it.second }
            val shelved = (serverShelf ?: emptyList()).map { it.work }.toSet() + state.shelf().mapNotNull { state.serverWork(it) }
            grid(nextBox, next.map { (w, _) -> Entry(local.firstOrNull { it.id == Server.bookId(w.id) }, w) }) { e -> byWork[e.work?.id]?.let { (if (e.work!!.id in shelved) "On my shelf · " else "") + "${it.finished} of ${it.total} finished" } ?: "" }
        }
        showNext(serverSeriesProgress)
        Server.client(state)?.let { client -> kotlin.concurrent.thread {
            val progress = runCatching { client.seriesProgress() }.getOrNull() ?: return@thread
            runOnUiThread {
                val before = nextInSeries(serverSeriesProgress).map { it.first.id }.toSet()
                serverSeriesProgress = progress
                if (!nextBox.isAttachedToWindow) return@runOnUiThread
                // A changed set moves books between the sections, so the page is drawn again; otherwise only the section.
                if (nextInSeries(progress).map { it.first.id }.toSet() != before) shelfPage() else showNext(progress)
            }
        } }
        // Finished books that are not kept on this phone (finished on the web or another device, or taken off this one
        // since) are the server's to tell: they belong here as much as the ones that are.
        val localIds = local.map { it.id }.toSet()
        val finishedAt = HashMap<String, Long>()
        val finishedElsewhere = (serverShelf ?: emptyList()).filter { it.state == "finished" && Server.bookId(it.work) !in localIds }
            .mapNotNull { e -> serverWorks?.firstOrNull { it.id == e.work }?.also { finishedAt[Server.bookId(it.id)] = e.finishedAt ?: e.added } }
        for (b in finished) finishedAt[b.id] = state.finished(b.id)
        val allFinished = (finished.map { Entry(it, serverWorks?.firstOrNull { w -> Server.bookId(w.id) == it.id }) } + finishedElsewhere.map { Entry(null, it) }).sortedByDescending { finishedAt[it.id] ?: 0L }
        sectionHead(body, "FINISHED", allFinished.size)
        if (allFinished.isEmpty()) body.addView(ui.text("Nothing finished yet. Mark as finished is in a book's options.", 13f).apply { setTextColor(ui.muted) })
        else grid(body, allFinished) { e -> "Finished " + java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(finishedAt[e.id] ?: 0L)) }
        ui.space(body, 24)
        body.addView(ui.text("Long-press a cover for its options, including Remove from my shelf.", 12f).apply { setTextColor(ui.muted) })
    }

    /** The whole collection with filter chips. */
    private fun renderBooks(content: LinearLayout) {
        val all = entries()
        val shown = all.filter { e ->
            when (libraryFilter) {
                "ready" -> e.ready
                "preparing" -> (e.book != null && readiness(e.book) == Readiness.PREPARING) || (e.work?.shelf == "preparing")
                "ebook" -> if (e.book != null) e.book.tracks.isEmpty() && e.book.passages.isNotEmpty() else e.work!!.hasEbook && !e.work.hasAudio
                "audio" -> if (e.book != null) e.book.passages.isEmpty() && e.book.tracks.isNotEmpty() else e.work!!.hasAudio && !e.work.hasEbook
                // Everything the book has is on this phone: the text always is once a book is added; the audio only when it was downloaded (or imported here).
                "device" -> e.book != null && (e.book.tracks.isEmpty() || state.serverWork(e.book.id) == null || state.serverDownloaded(e.book.id))
                "finished" -> e.finished
                else -> true
            }
        }
        sectionHead(content, when (libraryFilter) { "all" -> "EVERYTHING"; "ready" -> "READY TO READ & LISTEN"; "preparing" -> "GETTING READY"; "ebook" -> "EBOOK ONLY"; "audio" -> "AUDIOBOOK ONLY"; "device" -> "DOWNLOADED"; else -> "FINISHED" }, shown.size)
        if (shown.isEmpty()) content.addView(ui.text(if (all.isEmpty()) if (Alignment.onDevice) "No books yet. Import one, or connect to your server." else "No books yet. Connect to your server in Settings." else if (libraryFilter == "device") "Nothing is downloaded. A book with audio streams from your server until you choose Download on its page (On this device)." else "Nothing matches this filter.", 14f).apply { setTextColor(ui.muted) })
        // Long lists arrive in pages: a thousand rows at once stalls the screen.
        for (e in shown.take(booksShown)) entryRow(content, e, live = e.book != null && readiness(e.book) == Readiness.PREPARING)
        if (shown.size > booksShown) { ui.space(content, 10); content.addView(ui.button("Show ${minOf(60, shown.size - booksShown)} more of ${shown.size}", false) { booksShown += 60; redisplay?.invoke() }, LinearLayout.LayoutParams(-2, -2)) }
        serverLine(content)
    }

    private fun renderSeries(content: LinearLayout) {
        val list = serverSeriesList
        serverLine(content)
        if (list == null) return
        sectionHead(content, "SERIES", list.size, unit = "series")
        for (s in list) {
            val row = ui.row().apply { setPadding(0, dp(10), 0, dp(10)); isClickable = true; contentDescription = "Open series ${s.name}"; setOnClickListener { seriesPage(s.name) } }
            val info = ui.column()
            info.addView(ui.text(s.name, 18f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            ui.space(info, 4)
            info.addView(ui.text(listOfNotNull(s.authors.joinToString(", ").ifBlank { "Various authors" }, "${s.works} ${if (s.works == 1) "book" else "books"}", "${s.ready} synced".takeIf { s.ready > 0 }, "${s.duration / 3600000} h of audio".takeIf { s.duration > 0 }).joinToString("  ·  "), 12f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.icon("chevron", "Open series ${s.name}", ui.muted) { seriesPage(s.name) })
            content.addView(row)
        }
    }

    private fun renderAuthors(content: LinearLayout) {
        val list = serverAuthorsList
        serverLine(content)
        if (list == null) return
        sectionHead(content, "AUTHORS", list.size, unit = "author")
        var letter = ""
        for (a in list) {
            val initial = a.name.substringAfterLast(' ').take(1).uppercase()
            if (initial != letter) { letter = initial; ui.space(content, 10); content.addView(ui.caption(letter)); ui.space(content, 4) }
            val row = ui.row().apply { setPadding(0, dp(8), 0, dp(8)); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "Open author ${a.name}"; setOnClickListener { authorPage(a.name) } }
            row.addView(ui.text(a.name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text("${a.works}${if (a.ready > 0) " · ${a.ready} synced" else ""}", 12f).apply { setTextColor(ui.muted) })
            content.addView(row)
        }
    }

    /** Who reads an entry aloud: the server's list of names, else the one line kept with a book on this device. */
    private fun narratorsOf(e: Entry): List<String> =
        e.work?.narrators?.takeIf { it.isNotEmpty() } ?: listOfNotNull((e.work?.narrator?.takeIf { it.isNotBlank() } ?: e.book?.narrator)?.trim()?.takeIf { it.isNotBlank() })

    /** Every narrator in the collection by family name, each with how many books they read and how many of those are synced. */
    private fun narratorList(all: List<Entry> = entries()): List<ServerClient.PersonSummary> {
        val counts = HashMap<String, IntArray>()
        for (e in all) for (n in narratorsOf(e)) { val c = counts.getOrPut(n) { IntArray(2) }; c[0]++; if (e.ready) c[1]++ }
        return counts.map { (name, c) -> ServerClient.PersonSummary(name, c[0], c[1]) }.sortedWith(compareBy({ it.name.substringAfterLast(' ').lowercase() }, { it.name.lowercase() }))
    }

    private fun renderNarrators(content: LinearLayout) {
        serverLine(content)
        val list = narratorList()
        if (list.isEmpty() && serverWorks == null && Server.client(state) != null) return
        sectionHead(content, "NARRATORS", list.size, unit = "narrator")
        if (list.isEmpty()) content.addView(ui.text("No audiobook here names its narrator.", 14f).apply { setTextColor(ui.muted) })
        var letter = ""
        for (n in list) {
            val initial = n.name.substringAfterLast(' ').take(1).uppercase()
            if (initial != letter) { letter = initial; ui.space(content, 10); content.addView(ui.caption(letter)); ui.space(content, 4) }
            val row = ui.row().apply { setPadding(0, dp(8), 0, dp(8)); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "Open narrator ${n.name}"; setOnClickListener { narratorPage(n.name) } }
            row.addView(ui.text(n.name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.text("${n.works}${if (n.ready > 0) " · ${n.ready} synced" else ""}", 12f).apply { setTextColor(ui.muted) })
            content.addView(row)
        }
    }

    /** A narrator: everything they read, series first and in order, then the other titles. */
    private fun narratorPage(name: String) {
        arrive("narrator:$name")
        current = null
        val body = screen("")
        pageHeader(body, "NARRATOR") { state.libraryTab = "narrators"; library() }
        body.addView(ui.text(name, 28f, true).apply { maxLines = 2 })
        val list = ui.column(); body.addView(list)
        fun render() {
            list.removeAllViews()
            val books = entries().filter { e -> narratorsOf(e).any { it.equals(name, true) } }
                .sortedWith(compareBy<Entry>({ it.series.firstOrNull() == null }, { it.series.firstOrNull()?.name?.lowercase().orEmpty() }, { it.series.firstOrNull()?.sequence ?: Double.MAX_VALUE }, { it.title.lowercase() }))
            if (books.isEmpty()) {
                ui.space(list, 16)
                list.addView(ui.text(if (serverWorks == null && serverError == null && Server.client(state) != null) "Loading…" else "Nothing read by $name is in the library now.", 13f).apply { setTextColor(ui.muted) })
                return
            }
            val hours = books.sumOf { it.work?.duration ?: it.book?.duration ?: 0L } / 3600000
            ui.space(list, 6)
            list.addView(ui.text(listOfNotNull("${books.size} ${if (books.size == 1) "book" else "books"}", "$hours h of audio".takeIf { hours > 0 }).joinToString("  ·  "), 13f).apply { setTextColor(ui.muted) })
            var group: String? = "\u0000"
            for (e in books) {
                val series = e.series.firstOrNull()
                if (series?.name != group) { group = series?.name; sectionHead(list, group?.uppercase() ?: "OTHER BOOKS") }
                entryRow(list, e, badge = series?.sequence?.let { "#" + if (it == Math.floor(it)) it.toLong().toString() else it.toString() })
            }
        }
        render()
        // Opened before the library answered (a redrawn screen): drawn again when it has.
        withServerData { if (trail.lastOrNull() == "narrator:$name" && list.parent != null) render() }
    }

    private fun renderSearch(content: LinearLayout, query: String) {
        val q = query.lowercase()
        val all = entries()
        val books = all.filter { e -> e.title.lowercase().contains(q) || e.author.lowercase().contains(q) || e.series.any { it.name.lowercase().contains(q) } || (e.work?.narrators?.any { it.lowercase().contains(q) } == true) || (e.book?.narrator?.lowercase()?.contains(q) == true) }
        val series = serverSeriesList?.filter { it.name.lowercase().contains(q) } ?: emptyList()
        val authors = serverAuthorsList?.filter { it.name.lowercase().contains(q) } ?: emptyList()
        val narrators = narratorList(all).filter { it.name.lowercase().contains(q) }
        if (series.isNotEmpty()) { sectionHead(content, "SERIES"); for (s in series.take(6)) content.addView(ui.button("${s.name}  ·  ${s.works}", false) { seriesPage(s.name) }, LinearLayout.LayoutParams(-2, -2)) }
        if (authors.isNotEmpty()) { sectionHead(content, "AUTHORS"); for (a in authors.take(6)) content.addView(ui.button("${a.name}  ·  ${a.works}", false) { authorPage(a.name) }, LinearLayout.LayoutParams(-2, -2)) }
        if (narrators.isNotEmpty()) { sectionHead(content, "NARRATORS"); for (n in narrators.take(6)) content.addView(ui.button("${n.name}  ·  ${n.works}", false) { narratorPage(n.name) }, LinearLayout.LayoutParams(-2, -2)) }
        sectionHead(content, "BOOKS", books.size)
        if (books.isEmpty()) {
            content.addView(ui.text("No books match.", 14f).apply { setTextColor(ui.muted) })
            if (Server.client(state) != null) { ui.space(content, 10); content.addView(ui.button("Ask for it", false) { askForBook(query.trim()) }, LinearLayout.LayoutParams(-2, -2)) }
        }
        for (e in books.take(60)) entryRow(content, e)
    }

    private fun pageHeader(body: LinearLayout, caption: String, back: () -> Unit) {
        backTarget = back
        val bar = ui.row()
        bar.addView(ui.icon("back", "Back") { goBack(back) })
        bar.addView(ui.caption(caption), LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(bar); ui.space(body, 18)
    }

    /** A series: its books in sequence order, each with its state and a way in. */
    private fun seriesPage(name: String) {
        arrive("series:$name")
        current = null
        val body = screen("")
        pageHeader(body, "SERIES") { state.libraryTab = "series"; library() }
        body.addView(ui.text(name, 28f, true).apply { maxLines = 2 })
        val summary = serverSeriesList?.firstOrNull { it.name == name }
        if (summary != null) { ui.space(body, 6); body.addView(ui.text(listOf(summary.authors.joinToString(", "), "${summary.works} books", "${summary.ready} synced").filter { it.isNotBlank() }.joinToString("  ·  "), 13f).apply { setTextColor(ui.muted) }) }
        val progressLine = ui.text("", 13f).apply { setTextColor(ui.liveText); visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        body.addView(progressLine)
        val list = ui.column(); body.addView(list)
        ui.space(list, 16); list.addView(ui.text("Loading…", 13f).apply { setTextColor(ui.muted) })
        val client = Server.client(state) ?: return
        kotlin.concurrent.thread {
            val result = runCatching { client.seriesWorks(name) }
            val progress = runCatching { client.seriesProgress() }.getOrNull()
            runOnUiThread {
                if (current != null || list.parent == null) return@runOnUiThread
                list.removeAllViews()
                val works = result.getOrElse { list.addView(ui.text("Could not load the series: ${it.message}", 13f).apply { setTextColor(ui.muted) }); return@runOnUiThread }
                if (progress != null) serverSeriesProgress = progress
                val p = progress?.firstOrNull { it.name.equals(name, true) }
                if (p != null) { progressLine.text = "${p.finished} of ${p.total} finished"; progressLine.visibility = View.VISIBLE }
                val local = db.books().associateBy { it.id }
                val finishedHere = (serverShelf ?: emptyList()).filter { it.state == "finished" }.map { it.work }.toSet()
                fun row(w: ServerClient.Work) {
                    val seq = w.series.firstOrNull { it.name.equals(name, true) }?.sequence
                    val badge = seq?.let { "#" + if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
                    val e = Entry(local[Server.bookId(w.id)], w)
                    entryRow(list, e, badge = badge, line = if (e.finished || w.id in finishedHere) "Finished" else null)
                }
                // After the last finished book: the one being read (READING NOW) or, once that is finished, the next (NEXT UP).
                val pinned = (p?.next ?: p?.current)?.let { id -> works.firstOrNull { it.id == id } }
                if (pinned != null) { sectionHead(list, if (p?.next != null) "NEXT UP" else "READING NOW"); row(pinned); sectionHead(list, "ALL BOOKS", works.size) } else ui.space(list, 10)
                for (w in works) row(w)
            }
        }
    }

    /** An author: series first, in order, then standalone titles. */
    private fun authorPage(name: String) {
        arrive("author:$name")
        current = null
        val body = screen("")
        pageHeader(body, "AUTHOR") { state.libraryTab = "authors"; library() }
        body.addView(ui.text(name, 28f, true).apply { maxLines = 2 })
        val list = ui.column(); body.addView(list)
        ui.space(list, 16); list.addView(ui.text("Loading…", 13f).apply { setTextColor(ui.muted) })
        val client = Server.client(state) ?: return
        kotlin.concurrent.thread {
            val result = runCatching { client.authorWorks(name) }
            runOnUiThread {
                if (current != null || list.parent == null) return@runOnUiThread
                list.removeAllViews()
                val works = result.getOrElse { list.addView(ui.text("Could not load the author: ${it.message}", 13f).apply { setTextColor(ui.muted) }); return@runOnUiThread }
                val local = db.books().associateBy { it.id }
                var group: String? = "\u0000"
                for (w in works) {
                    val series = w.series.firstOrNull()
                    val g = series?.name
                    if (g != group) { group = g; sectionHead(list, g?.uppercase() ?: "OTHER BOOKS") }
                    entryRow(list, Entry(local[Server.bookId(w.id)], w), badge = series?.sequence?.let { "#" + if (it == Math.floor(it)) it.toLong().toString() else it.toString() })
                }
            }
        }
    }

    /** A server work: everything known about it, and the way onto this device and the shelf. */
    private fun workDetail(work: ServerClient.Work) {
        worksSeen[work.id] = work; arrive("work:${work.id}")
        current = null
        val localBook = db.load(Server.bookId(work.id))
        val body = screen("")
        pageHeader(body, "ON YOUR SERVER") { library() }
        body.addView(serverCover(work, 146, 218).apply { elevation = dp(5).toFloat() }, LinearLayout.LayoutParams(dp(146), dp(218)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ui.space(body, 22)
        body.addView(ui.text(work.title, 26f, true).apply { gravity = Gravity.CENTER; maxLines = 3 })
        if (work.subtitle.isNotBlank()) { ui.space(body, 4); body.addView(ui.text(work.subtitle, 15f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) }) }
        ui.space(body, 8)
        val authorsRow = ui.row().apply { gravity = Gravity.CENTER }
        for ((i, a) in (work.authors.ifEmpty { listOf(work.author) }).withIndex()) authorsRow.addView(ui.text((if (i > 0) ", " else "") + a, 16f).apply { setTextColor(ui.olive); minimumHeight = dp(48); minimumWidth = dp(48); gravity = Gravity.CENTER; isClickable = true; contentDescription = "Open author $a"; setOnClickListener { authorPage(a) } })
        body.addView(authorsRow)
        if (work.narrators.isNotEmpty()) {
            val readBy = ui.row().apply { gravity = Gravity.CENTER }
            readBy.addView(ui.text("Read by ", 14f).apply { setTextColor(ui.muted) })
            for ((i, n) in work.narrators.withIndex()) readBy.addView(ui.text((if (i > 0) ", " else "") + n, 14f).apply { setTextColor(ui.olive); minimumHeight = dp(48); gravity = Gravity.CENTER; isClickable = true; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = "Open narrator $n"; setOnClickListener { narratorPage(n) } })
            body.addView(readBy)
        }
        if (work.series.isNotEmpty()) {
            ui.space(body, 10)
            val chips = ui.row().apply { gravity = Gravity.CENTER }
            for (s in work.series) chips.addView(ui.button(s.label, false) { seriesPage(s.name) }.apply { textSize = 12f; minHeight = dp(34); insetTop = 0; insetBottom = 0; setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            body.addView(chips)
        }
        ui.space(body, 14)
        val facts = listOfNotNull(
            work.duration.takeIf { it > 0 }?.let { Alignment.clock(it) },
            work.passages.takeIf { it > 0 }?.let { "%,d sentences".format(it) },
            work.publishedYear.takeIf { it.isNotBlank() },
            work.rating.takeIf { it > 0 }?.let { "★ " + (if (it == Math.floor(it)) it.toLong().toString() else "%.1f".format(it)) },
        )
        if (facts.isNotEmpty()) body.addView(ui.text(facts.joinToString("  ·  "), 13f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) })
        ui.space(body, 8)
        body.addView(ui.text(Entry(localBook, work).line, 13f).apply { gravity = Gravity.CENTER; setTextColor(if (work.shelf == "ready") ui.liveText else ui.muted) })
        ui.space(body, 20)
        val actions = ui.row()
        if (localBook != null) {
            if (localBook.passages.isNotEmpty()) actions.addView(ui.button("Read", true) { showReader(localBook) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
            if (localBook.tracks.isNotEmpty()) actions.addView(ui.button("Listen", false) { state.active = localBook.id; details(localBook); playerScreen(localBook) }, LinearLayout.LayoutParams(0, dp(54), 1f))
            body.addView(actions); ui.space(body, 10)
            body.addView(ui.button(if (state.onShelf(localBook.id)) "Remove from my shelf" else "Add to my shelf", false) { if (state.onShelf(localBook.id)) shelfRemove(localBook.id) else shelfAdd(localBook.id); workDetail(work) }, LinearLayout.LayoutParams(-1, dp(48)))
            ui.space(body, 6)
            body.addView(ui.button("Book details", false) { details(localBook) }, LinearLayout.LayoutParams(-1, dp(48)))
        } else if (work.hasAudio || (work.hasEbook && work.ebookFormat == "epub")) {
            // Both halves synced, or one half alone: an ebook to read, an audiobook to listen to.
            val readable = work.hasEbook && work.ebookFormat == "epub"
            actions.addView(ui.button("Add to my shelf", true) { addServerWork(work, false, toShelf = true) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { if (work.hasAudio) marginEnd = dp(8) })
            if (work.hasAudio) actions.addView(ui.button("Download", false) { addServerWork(work, true, toShelf = true) }, LinearLayout.LayoutParams(0, dp(54), 1f))
            body.addView(actions)
            ui.space(body, 6)
            body.addView(ui.text(when {
                !work.hasAudio -> "The ebook alone: your server has no audiobook for it. Add it to read it here."
                work.shelf == "preparing" -> "Your server is still matching the text to the audio. You can read and listen now; the text follows the narration for what is matched so far, and the rest arrives by itself."
                !readable -> "The audiobook alone: your server has no ebook this app can read for it. Add streams the audio; Download keeps it on this device."
                else -> "Add streams the audio from your server; Download keeps it on this device."
            }, 12f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) })
        } else {
            body.addView(ui.text("This ebook is ${work.ebookFormat.ifBlank { "in a format" }.let { if (it == "in a format") it else "a ${it.uppercase()} file" }} this app cannot open: it reads EPUB.", 13f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) })
        }
        if (Server.client(state) != null) {
            // Want to read is for a book not picked up yet; collections take any book.
            ui.space(body, 6)
            val more = ui.row()
            var wantButton: TextView? = null
            fun wanted() = serverLists?.firstOrNull { it.kind == "want" }?.works?.contains(work.id) == true
            fun label() { wantButton?.text = if (wanted()) "✓ Want to read" else "Want to read" }
            if (localBook == null || !state.onShelf(localBook.id)) {
                wantButton = ui.button("Want to read", false) {
                    val on = !wanted()
                    onServer({ it.collect("want", work.id, on); it.collections() }) { serverLists = it; label(); note(if (on) "${work.title} is on Want to read" else "${work.title} is off Want to read") }
                }
                more.addView(wantButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            }
            more.addView(ui.button("Collections", false) { collectionsSheet(work.id, work.title) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(more); label()
            // Quietly: a book's page opened without a connection says nothing about it.
            onServer({ it.collections() }, quiet = true) { serverLists = it; label() }
        }
        if (work.genres.isNotEmpty()) {
            ui.space(body, 22)
            val wrap = ui.row()
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wrap) }
            for (g in work.genres.take(8)) wrap.addView(ui.text(g, 12f).apply { setTextColor(ui.muted); background = ui.shape(ui.surface, 10); setPadding(dp(10), dp(5), dp(10), dp(5)) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            body.addView(scroll)
        }
        if (work.description.isNotBlank()) {
            ui.space(body, 20); body.addView(ui.caption("ABOUT")); ui.space(body, 8)
            val about = ui.text(work.description, 15f, true).apply { setLineSpacing(dp(3).toFloat(), 1f); maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END; isClickable = true }
            about.setOnClickListener { about.maxLines = if (about.maxLines == 7) Int.MAX_VALUE else 7 }
            body.addView(about)
            ui.space(body, 4); body.addView(ui.text("Tap to read more", 12f).apply { setTextColor(ui.muted) })
        }
        if (work.publisher.isNotBlank()) { ui.space(body, 14); body.addView(ui.text("Published by ${work.publisher}${if (work.publishedYear.isNotBlank()) ", ${work.publishedYear}" else ""}", 12f).apply { setTextColor(ui.muted) }) }
        ui.space(body, 30)
    }

    private fun shelfAction(book: Book): Pair<String, () -> Unit>? = when (if (Alignment.onDevice) readiness(book) else Readiness.READY) {
        // Without on-device sync (the default build) there is nothing to add or start from here.
        Readiness.READY -> null
        Readiness.MISSING -> (if (book.passages.isEmpty()) "Add ebook" else "Add audiobook") to { pick(book.id) }
        Readiness.PREPARING -> when {
            PreparationService.running && PreparationService.bookId == book.id -> null
            db.prep(book.id).second == "Synced" -> "Check pairing" to { bookOptions(book); Unit }
            else -> "Sync now" to { Alignment.resume(this, book); handler.postDelayed({ redisplay?.invoke() }, 600); Unit }
        }
    }

    /** A whole shelf on its own screen: a cover grid of every book in that state. */
    private fun shelfScreen(kind: Readiness) {
        arrive("kind:${kind.name}")
        current = null
        val body = screen("")
        val bar = ui.row()
        backTarget = { library() }
        bar.addView(ui.icon("back", "Back") { goBack { library() } })
        bar.addView(ui.caption("YOUR LIBRARY"), LinearLayout.LayoutParams(0, -2, 1f))
        if (Alignment.onDevice) bar.addView(ui.icon("plus", "Import files") { pick(null) })
        body.addView(bar); ui.space(body, 16)
        val (title, note) = when (kind) {
            Readiness.READY -> "Ready to read & listen" to "Both halves are here and every sentence is matched. Pick up either one; your place is shared."
            Readiness.PREPARING -> "Getting ready" to "Reading and listening are being matched sentence by sentence. You can read or listen meanwhile; a book moves to the ready shelf when it finishes."
            Readiness.MISSING -> "Missing a piece" to if (Alignment.onDevice) "These work on their own. Add the other half and they sync automatically." else "These work on their own."
        }
        body.addView(ui.text(title, 30f, true)); ui.space(body, 8)
        body.addView(ui.text(note, 14f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) })
        val grid = ui.column(); body.addView(grid)
        fun render() {
            grid.removeAllViews(); syncLines.clear()
            val books = db.books().filter { readiness(it) == kind }
            shelfSignature = shelfSignatureNow()
            ui.space(grid, 18)
            grid.addView(ui.text("${books.size} ${if (books.size == 1) "book" else "books"}", 12f).apply { setTextColor(ui.muted) })
            ui.space(grid, 18)
            if (books.isEmpty()) {
                ui.space(grid, 30)
                grid.addView(ui.text("Nothing here right now.", 22f, true))
                ui.space(grid, 10)
                grid.addView(ui.text(when (kind) {
                    Readiness.READY -> "Books appear here once an ebook and its audiobook are synced."
                    Readiness.PREPARING -> "Paired books appear here while they sync."
                    Readiness.MISSING -> "Every book has both halves. Nice shelf."
                }, 14f).apply { setTextColor(ui.muted) })
                return
            }
            val gap = 12
            val cell = (resources.configuration.screenWidthDp - 48 - 2 * gap) / 3
            for (rowBooks in books.chunked(3)) {
                val row = ui.row().apply { gravity = Gravity.TOP }
                for ((i, book) in rowBooks.withIndex()) {
                    val column = ui.column().apply { isClickable = true; contentDescription = "Open ${book.title}"; setOnClickListener { details(book) } }
                    column.addView(ui.cover(book, cell, cell * 3 / 2).apply { elevation = dp(2).toFloat() })
                    ui.space(column, 10)
                    column.addView(ui.text(book.title, 14f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    ui.space(column, 3)
                    column.addView(ui.text(book.author.ifBlank { "Unknown author" }, 11f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    ui.space(column, 6)
                    val paired = book.passages.isNotEmpty() && book.tracks.isNotEmpty()
                    val live = kind == Readiness.PREPARING
                    column.addView(ui.text(shelfLine(book), 11f).apply {
                        setTextColor(if (live) ui.liveText else ui.muted); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        if (paired) { isClickable = true; contentDescription = "Sync overview for ${book.title}"; setOnClickListener { syncOverview(book) } }
                    }.also { if (live) syncLines += it to book })
                    shelfAction(book)?.let { action ->
                        ui.space(column, 8)
                        column.addView(ui.button(action.first, kind == Readiness.MISSING) { action.second() }.apply {
                            textSize = 12f; minHeight = dp(36); setPadding(dp(10), 0, dp(10), 0); contentDescription = "${action.first} for ${book.title}"
                        }, LinearLayout.LayoutParams(-1, -2))
                    }
                    row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(gap) })
                }
                for (i in rowBooks.size until 3) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(gap) })
                grid.addView(row); ui.space(grid, 22)
            }
        }
        redisplay = { render() }
        render()
    }

    /**
     * Before opening a server book straight from the shelf: its newest place from the server, taken, or the question
     * when two devices left it far apart; then `open`. The server gets a second and a half (offline, the book opens at
     * once); an answer after the book has opened never moves it quietly, since the reader or player is already at the
     * old place: a newer place then comes as the question too.
     */
    private fun withCurrentPlace(b: Book, busy: (Boolean) -> Unit = {}, open: () -> Unit) {
        if (state.serverWork(b.id) == null || state.serverToken.isBlank()) { open(); return }
        // One wait at a time: a second press while the first is still asking opens nothing twice.
        if (placeAsked) return
        placeAsked = true; busy(true)
        var opened = false
        val answer = { r: Server.Pulled ->
            val remote = (r as? Server.Pulled.Newer)?.remote ?: (r as? Server.Pulled.Apart)?.remote
            when {
                !opened -> { opened = true; placeAsked = false; busy(false); when (r) { is Server.Pulled.Newer -> { takePlace(b, r.remote); open() }; is Server.Pulled.Apart -> askWhichPlace(b, r.remote) { open() }; else -> open() } }
                remote != null -> askWhichPlace(b, remote) { there -> if (there && reader != null && current?.id == b.id) showReader(b) }
            }
        }
        kotlin.concurrent.thread { val r = runCatching { Server.sync(this, b.id) }.getOrDefault(Server.Pulled.Nothing); runOnUiThread { if (!gone()) answer(r) } }
        handler.postDelayed({ if (!opened && !gone()) { opened = true; placeAsked = false; busy(false); open() } }, 1500)
    }
    /** True while a book's newest place is being asked for before it opens (`withCurrentPlace`). */
    private var placeAsked = false

    /** Takes another device's place; a player holding this book (paused) moves with it. */
    private fun takePlace(b: Book, remote: ServerClient.Position) {
        Server.keepRemote(this, b.id, remote)
        if (PlaybackService.playingBook() == b.id) PlaybackService.send(this, "seek", b.id, remote.audio)
    }

    /** The text's chapter and how far through when there is a sentence; otherwise the audio's chapter and time. */
    private fun placeText(b: Book, passage: Int, audio: Long): String = when {
        passage <= 0 && audio <= 0 -> "the beginning"
        passage > 0 && b.passages.isNotEmpty() -> listOfNotNull(b.toc.filter { it.second <= passage }.maxByOrNull { it.second }?.first?.trim(), "${passage * 100 / b.passages.size}% through").joinToString(", ")
        else -> {
            // Heard, not read: the ebook's chapter of the sentence narrated there (as the web names it), else the audio's own mark.
            val heard = db.atTime(b.id, audio)?.passage
            val chapter = heard?.let { s -> b.toc.filter { it.second <= s }.maxByOrNull { it.second }?.first?.trim() }?.takeIf { it.isNotBlank() } ?: b.chapters.lastOrNull { it.start <= audio }?.title?.trim()
            listOfNotNull(chapter, Alignment.clock(audio)).joinToString(", ")
        }
    }

    /** Two devices left the book far apart: the person picks the place. Nothing moves until they do. `then(true)` after Go there. */
    private fun askWhichPlace(b: Book, remote: ServerClient.Position, then: (Boolean) -> Unit) {
        // A dialog cannot be shown on a screen that was rotated away or closed; the question comes again next time.
        if (gone()) return
        // One question at a time: a second answer about the same book while it is showing adds nothing.
        if (askingPlace == b.id) return
        askingPlace = b.id
        val there = placeText(b, remote.passage, remote.audio)
        val here = placeText(b, state.passage(b.id), state.audio(b.id))
        val who = remote.by.ifBlank { "Another device" }
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(remote.updated, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
        MaterialAlertDialogBuilder(this).setTitle("Two places in ${b.title}")
            .setMessage("$who left it at $there, $ago.\n\nThis phone is at $here.")
            .setPositiveButton("Go there") { _, _ -> takePlace(b, remote); then(true) }
            .setNegativeButton("Stay here") { _, _ -> kotlin.concurrent.thread { Server.push(this, b.id, force = true) }; then(false) }
            .setOnCancelListener { then(false) }
            .setOnDismissListener { askingPlace = null }
            .show()
    }
    private var askingPlace: String? = null

    /** The server's address as the sign-in screen shows it; a plain http address says what that means. */
    private fun serverLabel(typed: String): String {
        val address = ServerClient.normalize(typed)
        return if (address.startsWith("http://")) "$address  ·  not encrypted: your password and books can be read on the network" else address.removePrefix("https://")
    }

    /** True once this screen instance is finishing or destroyed (a rotation, a theme change): background answers must stop there. */
    private fun gone() = isFinishing || isDestroyed

    private fun details(b: Book) {
        arrive("book:${b.id}")
        if (state.serverWork(b.id) != null) kotlin.concurrent.thread {
            val pulled = runCatching { Server.sync(this, b.id) }.getOrDefault(Server.Pulled.Nothing); val marks = runCatching { Server.pullMarks(this, b.id) }.getOrDefault(false)
            // Added while the server was still syncing it: what has been matched since.
            val grew = runCatching { Server.refreshSync(this, b.id) }.getOrDefault(false)
            runOnUiThread {
                // The person has moved on (to its bookmarks, its sync screen, another book): the answer waits for next time.
                if (gone() || current?.id != b.id || trail.lastOrNull() !in setOf("book:${b.id}", "reader:${b.id}")) return@runOnUiThread
                // Still on the book's page: a newer place is taken and shown. Already reading: the text never moves by
                // itself, so it comes as the question.
                val reopen = { there: Boolean -> if (there && current?.id == b.id) { if (reader == null) details(b) else showReader(b) } }
                when (pulled) {
                    is Server.Pulled.Newer -> if (reader == null) { takePlace(b, pulled.remote); details(b) } else askWhichPlace(b, pulled.remote, reopen)
                    is Server.Pulled.Apart -> askWhichPlace(b, pulled.remote, reopen)
                    else -> if ((marks || grew) && reader == null) details(db.load(b.id) ?: b)
                }
            }
        }
        current = b; state.active = b.id
        val body = screen("")
        val bar = ui.row()
        backTarget = { library() }
        bar.addView(ui.icon("back", "Back") { goBack { library() } })
        bar.addView(ui.caption("IN YOUR LIBRARY"), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.icon("more", "Book options") { bookOptions(b) }); body.addView(bar)
        ui.space(body, 20)
        val cover = ui.cover(b, 146, 218).apply { elevation = dp(5).toFloat() }
        body.addView(cover, LinearLayout.LayoutParams(dp(146), dp(218)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ui.space(body, 28)
        body.addView(ui.text(b.title, 29f, true).apply { gravity = Gravity.CENTER })
        ui.space(body, 10)
        body.addView(ui.text(b.author, 15f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) })
        if (b.narrator.isNotBlank()) { ui.space(body, 8); body.addView(ui.text("Read by ${b.narrator}", 12f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) }) }
        ui.space(body, 24)
        val actions = ui.row()
        if (b.passages.isNotEmpty()) actions.addView(ui.button("Read", true) { showReader(b) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        if (b.tracks.isNotEmpty()) actions.addView(ui.button("Listen", b.passages.isEmpty()) { playerScreen(b) }, LinearLayout.LayoutParams(0, dp(54), 1f))
        body.addView(actions); ui.space(body, 24); ui.rule(body); ui.space(body, 12)
        body.addView(settingRow(if (state.onShelf(b.id)) "On my shelf" else "Not on my shelf", if (state.onShelf(b.id)) "Since ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(state.shelvedAt(b.id)))} · tap to remove" else "Tap to add it to what you are reading next", "book") { if (state.onShelf(b.id)) shelfRemove(b.id) else shelfAdd(b.id); details(b) })
        // An audiobook alone has no pages: its progress is the Audiobook row below.
        if (b.passages.isNotEmpty() || state.finished(b.id) > 0) body.addView(settingRow("Reading progress", if (state.finished(b.id) > 0) "Finished on ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(state.finished(b.id)))}" else "${if (b.passages.isEmpty()) 0 else state.passage(b.id) * 100 / b.passages.size}% of your book", "book") { if (b.passages.isNotEmpty()) showReader(b) })
        if (b.tracks.isNotEmpty()) body.addView(settingRow("Audiobook", "${Alignment.clock(state.audio(b.id))} of ${Alignment.clock(b.duration)}" + (if (state.serverWork(b.id) != null) (if (state.serverDownloaded(b.id)) " · downloaded" else " · streaming from your server") else ""), "headphones") { playerScreen(b) })
        body.addView(settingRow("Bookmarks & highlights", state.marks(b.id).length().let { if (it == 0) "None yet · long-press a sentence, then use the reader's menu" else "$it ${if (it == 1) "mark" else "marks"}" }, "contents") { marksScreen(b) })
        if (state.serverWork(b.id) != null) body.addView(settingRow("On this device", "${sizeText(Server.sizeOnDevice(this, b.id))}${if (b.tracks.isNotEmpty() && !state.serverDownloaded(b.id)) " · tap to download the audio for offline" else " · tap to manage"}", "plus") {
            if (b.tracks.isNotEmpty() && !state.serverDownloaded(b.id)) downloadAudioFor(b) { details(db.load(b.id) ?: b) } else downloadsScreen()
        })
        if (b.toc.isNotEmpty()) body.addView(settingRow("Contents", "${b.toc.size} sections", "contents") { choose(b.toc.map { it.first }) { i -> state.select(b.id, b.toc[i].second); showReader(b) } })
        // Added as one half alone, and the server has since got the other and matched them: one tap brings it over.
        state.serverWork(b.id)?.let { id -> serverWorks?.firstOrNull { it.id == id } }?.takeIf { w -> w.shelf == "ready" && ((b.tracks.isEmpty() && w.hasAudio) || (b.passages.isEmpty() && w.hasEbook && w.ebookFormat == "epub")) }?.let { w ->
            body.addView(settingRow(if (b.tracks.isEmpty()) "The audiobook is on your server now" else "The ebook is on your server now", "Tap to bring it here; your place is kept", "plus") { addServerWork(w, false, toShelf = false) })
        }
        if (b.tracks.isNotEmpty() && b.passages.isNotEmpty()) body.addView(settingRow("Reading & listening", syncSummary(b), "settings") { syncOverview(b) })
        else if (Alignment.onDevice) body.addView(settingRow(if (b.passages.isEmpty()) "Add the ebook" else "Add the audiobook", "Both halves together sync automatically", "plus") { pick(b.id) })
    }

    private fun settingRow(title: String, subtitle: String, icon: String, action: () -> Unit): View {
        val row = ui.row().apply { setPadding(0, dp(9), 0, dp(9)); isClickable = true; setOnClickListener { action() } }
        row.addView(ui.icon(icon, title, ui.olive, action))
        val texts = ui.column()
        texts.addView(ui.text(title, 15f)); ui.space(texts, 5)
        texts.addView(ui.text(subtitle, 12f).apply { setTextColor(ui.muted); if (title == "Reading & listening") syncLine = this })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.icon("chevron", "Open $title", ui.muted, action)); return row
    }

    private fun sheet(title: String, build: (LinearLayout, BottomSheetDialog) -> Unit) {
        val dialog = BottomSheetDialog(this)
        val body = ui.column().apply { setPadding(dp(24), dp(12), dp(24), dp(28)); setBackgroundColor(ui.paper) }
        val bar = ui.row(); bar.addView(ui.text(title, 25f, true), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.icon("close", "Close $title") { dialog.dismiss() }); body.addView(bar); ui.space(body, 16)
        build(body, dialog)
        dialog.setContentView(ScrollView(this).apply { addView(body) })
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun bookOptions(b: Book) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (state.onShelf(b.id)) "Remove from my shelf" else "Add to my shelf") to {
            if (state.onShelf(b.id)) { shelfRemove(b.id); note("${b.title} is off your shelf") } else { shelfAdd(b.id); note("${b.title} is on your shelf") }
            if (current?.id == b.id && reader == null) details(b) else redisplay?.invoke()
        }
        items += (if (state.finished(b.id) > 0) "Mark as unfinished" else "Mark as finished") to { markFinished(b, state.finished(b.id) == 0L) }
        items += "Discard progress" to { discardProgress(b) }
        if (b.passages.isNotEmpty()) items += "Search in this book" to { searchScreen(b, false) }
        state.serverWork(b.id)?.let { w -> items += "Collections" to { collectionsSheet(w, b.title) } }
        if (state.serverWork(b.id) == null && Alignment.onDevice) items += "Attach files" to { pick(b.id) }
        items += "Reading appearance" to { readingOptions {} }
        if (b.tracks.isNotEmpty() && state.serverWork(b.id) == null && Alignment.onDevice) {
            items += "Correct track order" to { order(b) }
            items += "Correct ebook / audiobook pairing" to {
                val candidates = db.books().filter { it.id != b.id && it.tracks.isEmpty() }
                choose(candidates.map { it.title } + "Separate audiobook entry") { n ->
                    val target = if (n < candidates.size) candidates[n] else Book(java.util.UUID.randomUUID().toString(), b.title + " — audiobook", b.author)
                    stopService(Intent(this, PlaybackService::class.java)); val paired = db.moveAudio(b, target); Alignment.autoStart(this, paired); details(paired)
                }
            }
        }
        choose(items.map { it.first }) { i -> items[i].second() }
    }

    /** A mark of a server book was added, changed or removed here: recorded as owed at once, then sent (now, or when there is a connection again). */
    private fun owe(b: Book, passage: Int, kind: String, remove: Boolean) {
        if (state.serverWork(b.id) == null) return
        state.markOwed(b.id, passage, kind, remove)
        kotlin.concurrent.thread { Server.settle(this) }
    }

    /** Adds or removes a bookmark or highlight on a sentence, draws it, and mirrors it to the server. */
    private fun toggleMark(b: Book, passage: Int, kind: String) {
        if (passage !in b.passages.indices) return
        val had = passage in state.markIds(b.id, kind)
        if (had) state.removeMark(b.id, passage, kind) else state.setMark(b.id, passage, kind, "", b.passages[passage].text)
        reader?.setMarks(state.markIds(b.id, "highlight"))
        owe(b, passage, kind, had)
        note(when { had && kind == "highlight" -> "Highlight removed"; had -> "Bookmark removed"; kind == "highlight" -> "Sentence highlighted"; else -> "Page bookmarked" })
    }

    /** A book's bookmarks and highlights: tap to go there, add a note, or remove. */
    private fun marksScreen(b: Book) {
        arrive("marks:${b.id}")
        current = b
        val body = screen("")
        pageHeader(body, "BOOKMARKS & HIGHLIGHTS") { details(b) }
        body.addView(ui.text(b.title, 24f, true).apply { maxLines = 2 })
        val list = ui.column(); body.addView(list)
        fun render() {
            list.removeAllViews()
            val a = state.marks(b.id)
            val marks = (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.getInt("passage") }
            if (marks.isEmpty()) { ui.space(list, 16); list.addView(ui.text("Nothing marked yet. In the reader, long-press a sentence and use the ⋯ menu to highlight it, or bookmark the page you are on.", 14f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) }); return }
            ui.space(list, 10)
            list.addView(ui.button("Share as Markdown", false) { shareMarkdown("${b.title} - highlights", marksMarkdown(b)) }, LinearLayout.LayoutParams(-2, -2))
            for (kind in listOf("bookmark", "highlight")) {
                val ofKind = marks.filter { it.getString("kind") == kind }
                if (ofKind.isEmpty()) continue
                sectionHead(list, if (kind == "bookmark") "BOOKMARKS" else "HIGHLIGHTS", ofKind.size, unit = kind)
                for (m in ofKind) {
                    val p = m.getInt("passage")
                    val row = ui.column().apply { setPadding(0, dp(8), 0, dp(10)); isClickable = true; setOnClickListener { state.select(b.id, p.coerceIn(0, (b.passages.size - 1).coerceAtLeast(0))); foundPassage = p; showReader(b) } }
                    val before = b.toc.filter { it.second <= p }; val nearest = before.maxOfOrNull { it.second }
                    val where = before.singleOrNull { it.second == nearest }?.first?.takeIf { it.isNotBlank() }
                    row.addView(ui.text(listOfNotNull(where, "${100 * p / b.passages.size.coerceAtLeast(1)}%", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(m.optLong("created")))).joinToString("  ·  "), 11f).apply { setTextColor(ui.muted) })
                    ui.space(row, 4)
                    row.addView(ui.text(m.optString("text").ifBlank { b.passages.getOrNull(p)?.text ?: "" }, 15f, true).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; if (kind == "highlight") setBackgroundColor(0x40ECC94B) })
                    if (m.optString("note").isNotBlank()) { ui.space(row, 4); row.addView(ui.text(m.optString("note"), 13f).apply { setTextColor(ui.muted) }) }
                    ui.space(row, 8)
                    val actions = ui.row()
                    actions.addView(ui.button(if (m.optString("note").isBlank()) "Add a note" else "Edit note", false) {
                        val input = EditText(this).apply { setText(m.optString("note")); hint = "Your note"; minLines = 2 }
                        MaterialAlertDialogBuilder(this).setTitle("Note").setView(ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
                            .setPositiveButton("Save") { _, _ -> state.setMark(b.id, p, kind, input.text.toString().trim(), m.optString("text"), m.optLong("created")); owe(b, p, kind, false); render() }
                            .setNegativeButton("Cancel", null).show()
                    }.apply { textSize = 12f; minHeight = dp(36); insetTop = 0; insetBottom = 0 }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    actions.addView(ui.button("Remove", false) { state.removeMark(b.id, p, kind); owe(b, p, kind, true); render() }.apply { textSize = 12f; minHeight = dp(36); insetTop = 0; insetBottom = 0 }, LinearLayout.LayoutParams(-2, -2))
                    row.addView(actions)
                    list.addView(row)
                }
            }
        }
        render()
        if (state.serverWork(b.id) != null) kotlin.concurrent.thread { if (Server.pullMarks(this, b.id)) runOnUiThread { if (list.parent != null) render() } }
    }

    private var paceLine: TextView? = null
    private var wordCache: Pair<String, IntArray>? = null
    /** Words before each sentence (and, last, in the whole book). */
    private fun wordsBefore(b: Book): IntArray {
        // Same book and the same number of sentences: a book repaired on the server gets a new count.
        wordCache?.takeIf { it.first == b.id && it.second.size == b.passages.size + 1 }?.let { return it.second }
        val w = IntArray(b.passages.size + 1); val space = Regex("\\s+")
        for (i in b.passages.indices) w[i + 1] = w[i] + b.passages[i].text.trim().split(space).size
        wordCache = b.id to w; return w
    }

    /** Time left in the chapter and the book: at the narration's speed while it plays, else at this reader's own pace. */
    private fun paceText(b: Book): String {
        val n = b.passages.size; if (n == 0) return ""
        val playing = PlaybackService.playingBook() == b.id && PlaybackService.playing()
        val audio = state.audio(b.id)
        val here = (if (playing) db.atTime(b.id, audio)?.passage ?: state.passage(b.id) else state.passage(b.id)).coerceIn(0, n)
        val end = b.toc.map { it.second }.filter { it > here }.minOrNull() ?: n
        val chapter: Float?; val book: Float
        if (playing && b.duration > 0) {
            val speed = state.speed(b.id).toFloat().coerceAtLeast(.5f)
            val endMs = if (end >= n) b.duration else db.nearestAfter(b.id, end, 40)?.start
            chapter = endMs?.let { (it - audio).coerceAtLeast(0) / speed / 60000f }
            book = (b.duration - audio).coerceAtLeast(0) / speed / 60000f
        } else {
            val w = wordsBefore(b); val wpm = state.readWpm.takeIf { it > 0 } ?: 230f
            chapter = (w[end] - w[here]) / wpm; book = (w[n] - w[here]) / wpm
        }
        fun f(m: Float) = if (m < 1) "under a minute" else if (m < 60) "${Math.round(m)} min" else "${(m / 60).toInt()} h ${"%02d".format((m % 60).toInt())} min"
        return listOfNotNull(chapter?.let { "${f(it)} left in chapter" }, "${f(book)} in book").joinToString("  ·  ")
    }

    /** One book's marks as Markdown: highlights as quotes with their notes, then bookmarks (the web client writes the same). */
    private fun marksMarkdown(b: Book): String {
        val a = state.marks(b.id)
        return marksMarkdown(b.title, b.author, b.passages.size, (0 until a.length()).map { a.getJSONObject(it) },
            { p -> b.toc.filter { it.second <= p }.maxByOrNull { it.second }?.first?.trim()?.takeIf { it.isNotBlank() } }, { p -> b.passages.getOrNull(p)?.text ?: "" })
    }
    /** `chapter` names a sentence's chapter when known; `textOf` is the sentence itself for a mark that carries no text. */
    private fun marksMarkdown(title: String, author: String, total: Int, all: List<org.json.JSONObject>, chapter: (Int) -> String?, textOf: (Int) -> String): String {
        val marks = all.sortedBy { it.getInt("passage") }
        fun where(m: org.json.JSONObject): String { val p = m.getInt("passage"); return listOfNotNull(chapter(p), if (total > 0) "${100 * p / total}%" else null).joinToString(" · ") }
        fun quote(m: org.json.JSONObject) = m.optString("text").ifBlank { textOf(m.getInt("passage")) }.replace(Regex("\\s+"), " ").trim()
        fun note(m: org.json.JSONObject) = m.optString("note").replace(Regex("\\s*\\n\\s*"), " ").trim()
        val out = StringBuilder("# $title\n"); if (author.isNotBlank()) out.append("*$author*\n"); out.append('\n')
        val hl = marks.filter { it.getString("kind") == "highlight" }; val bm = marks.filter { it.getString("kind") == "bookmark" }
        if (hl.isNotEmpty()) { out.append("## Highlights\n\n"); for (m in hl) { out.append("> ${quote(m)}\n>\n> — ${where(m)}\n\n"); if (note(m).isNotEmpty()) out.append("Note: ${note(m)}\n\n") } }
        if (bm.isNotEmpty()) { out.append("## Bookmarks\n\n"); for (m in bm) out.append("- ${where(m)}: “${quote(m).take(140)}”${if (note(m).isNotEmpty()) " (${note(m)})" else ""}\n"); out.append('\n') }
        return out.toString()
    }
    private fun shareMarkdown(subject: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), "Share highlights"))
    }

    /** A server book that is not on this device: its page, where it can be added. */
    private fun openWork(work: String) {
        kotlin.concurrent.thread {
            val result = runCatching { Server.client(state)!!.detail(work).work }
            runOnUiThread { result.onSuccess { workDetail(it) }.onFailure { note("Could not open the book: ${it.message}") } }
        }
    }

    /**
     * Everything this person marked, a book at a time: the books on this device from the device (a tap opens the reader
     * there), and, once the server has answered, the books marked elsewhere that are not kept here (a tap opens the
     * book's page).
     */
    private fun allMarksScreen() {
        arrive("allMarks")
        current = null
        val body = screen("")
        pageHeader(body, "MY SHELF") { shelfPage() }
        body.addView(ui.text("Highlights", 34f, true)); ui.space(body, 6)
        val summary = ui.text("", 14f).apply { setTextColor(ui.muted) }; body.addView(summary)
        val list = ui.column(); body.addView(list)
        class Group(val title: String, val author: String, val total: Int, val marks: List<org.json.JSONObject>, val book: Book?, val work: String?)
        var remote = JSONArray()
        fun render() {
            list.removeAllViews()
            val books = db.books()
            val local = books.filter { state.marks(it.id).length() > 0 }.map { b -> val a = state.marks(b.id); Group(b.title, b.author, 0, (0 until a.length()).map { a.getJSONObject(it) }, b, null) }
            val here = books.mapNotNull { state.serverWork(it.id) }.toSet()
            val away = (0 until remote.length()).map { remote.getJSONObject(it) }.filter { it.getString("work") !in here }.map { w ->
                val a = w.getJSONArray("marks"); Group(w.getString("title"), w.optString("author"), w.optInt("passages"), (0 until a.length()).map { a.getJSONObject(it) }, null, w.getString("work"))
            }
            val groups = (local + away).filter { it.marks.isNotEmpty() }.sortedByDescending { g -> g.marks.maxOf { it.optLong("created") } }
            if (groups.isEmpty()) { summary.text = "Nothing marked yet. In a book, long-press a sentence and use the reader's ⋯ menu."; return }
            val all = groups.flatMap { it.marks }; val highlights = all.count { it.getString("kind") == "highlight" }
            summary.text = "$highlights ${if (highlights == 1) "highlight" else "highlights"}  ·  ${all.size - highlights} ${if (all.size - highlights == 1) "bookmark" else "bookmarks"}  ·  ${groups.size} ${if (groups.size == 1) "book" else "books"}"
            ui.space(list, 10)
            list.addView(ui.button("Share all as Markdown", false) {
                shareMarkdown("Unison highlights", groups.joinToString("\n---\n\n") { g -> g.book?.let { db.load(it.id) }?.let(::marksMarkdown) ?: marksMarkdown(g.title, g.author, g.total, g.marks, { null }, { "" }) })
            }, LinearLayout.LayoutParams(-2, -2))
            for (g in groups) {
                sectionHead(list, g.title.uppercase(), null, note = if (g.book == null) "Not on this phone" else null) { if (g.book != null) db.load(g.book.id)?.let { marksScreen(it) } else openWork(g.work!!) }
                for (m in g.marks.sortedBy { it.getInt("passage") }) {
                    val p = m.getInt("passage"); val highlight = m.getString("kind") == "highlight"
                    val row = ui.column().apply {
                        setPadding(0, dp(8), 0, dp(10)); isClickable = true
                        setOnClickListener {
                            if (g.book == null) openWork(g.work!!)
                            else db.load(g.book.id)?.let { full -> if (full.passages.isNotEmpty()) { state.select(full.id, p.coerceIn(0, full.passages.size - 1)); foundPassage = p; showReader(full) } else details(full) }
                        }
                    }
                    row.addView(ui.text(m.optString("text").ifBlank { "…" }, 15f, true).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; if (highlight) setBackgroundColor(0x40ECC94B) })
                    if (m.optString("note").isNotBlank()) { ui.space(row, 4); row.addView(ui.text(m.optString("note"), 13f).apply { setTextColor(ui.muted) }) }
                    ui.space(row, 4)
                    row.addView(ui.text(listOfNotNull(if (highlight) null else "Bookmark", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(m.optLong("created")))).joinToString("  ·  "), 11f).apply { setTextColor(ui.muted) })
                    list.addView(row)
                }
            }
        }
        render()
        // Marks made on other devices arrive for the server books kept here; the books marked but not kept here come as a list.
        kotlin.concurrent.thread {
            val changed = db.books().filter { state.serverWork(it.id) != null }.map { Server.pullMarks(this, it.id) }.any { it }
            val fetched = runCatching { Server.client(state)?.allMarks() }.getOrNull()
            if (changed || fetched != null) runOnUiThread { if (fetched != null) remote = fetched; if (list.parent != null && list.isAttachedToWindow) render() }
        }
    }

    /** The sentence a search result opened, for the reader to mark once; -1 when none. */
    private var foundPassage = -1
    private val searchQueries = HashMap<String, String>()
    private val searchScroll = HashMap<String, Int>()

    /** Lower case, plain quotes and dashes, no accents, one character for one so match offsets carry over. */
    private fun foldForSearch(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) out.append(when {
            c == '‘' || c == '’' || c == 'ʼ' -> '\''
            c == '“' || c == '”' -> '"'
            c == '–' || c == '—' -> '-'
            c == ' ' || c.isWhitespace() -> ' '
            c.code < 128 -> c.lowercaseChar()
            else -> java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)[0].lowercaseChar()
        })
        return out.toString()
    }

    /** Search inside a book: every sentence with the words, in reading order; a tap opens its page with the sentence marked. */
    private fun searchScreen(b: Book, fromReader: Boolean) {
        arrive("search:${b.id}|$fromReader")
        val body = screen("")
        // Not the book's own page: Back follows the header, and a late answer from the server leaves this screen alone.
        current = null
        pageHeader(body, "SEARCH IN BOOK") { if (fromReader) showReader(b) else details(b) }
        body.addView(ui.text(b.title, 24f, true).apply { maxLines = 2 }); ui.space(body, 14)
        val input = EditText(this).apply {
            hint = "Words, or \"an exact phrase\""; setTextColor(ui.ink); setHintTextColor(ui.muted)
            textSize = 14f; setSingleLine(); background = ui.shape(ui.surface, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Search in this book"
            setText(searchQueries[b.id].orEmpty()); setSelection(text.length)
        }
        body.addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        val summary = ui.text("", 12f).apply { setTextColor(ui.muted); setPadding(0, dp(14), 0, dp(4)) }
        body.addView(summary)
        val list = ui.column(); body.addView(list)
        val scroller = body.parent as? ScrollView
        val folded by lazy { b.passages.map { foldForSearch(it.text) } }
        var shown = 50
        fun render() {
            list.removeAllViews()
            searchQueries[b.id] = input.text.toString()
            // Words in any order within a sentence; in quotes, the exact phrase.
            val typed = foldForSearch(input.text.toString().trim()).replace(Regex(" +"), " ")
            val exact = typed.length > 2 && typed.startsWith('"') && typed.endsWith('"')
            val query = typed.trim('"').trim()
            if (query.length < 2) { summary.text = ""; return }
            val terms = if (exact || ' ' !in query) listOf(query) else query.split(' ').filter { it.length >= 2 }.distinct().ifEmpty { listOf(query) }
            val hits = folded.indices.filter { i -> terms.all { it in folded[i] } }
            val words = wordsBefore(b)
            summary.text = when (hits.size) { 0 -> "Nothing found"; 1 -> "1 sentence"; else -> "%,d sentences".format(hits.size) }
            for (p in hits.take(shown)) {
                val text = b.passages[p].text; val at = terms.minOf { folded[p].indexOf(it) }
                // A long sentence shows the part around the first match.
                val from = if (text.length > 240 && at > 100) text.lastIndexOf(' ', at - 60) + 1 else 0
                val to = if (text.length - from > 240) text.indexOf(' ', from + 220).let { if (it < 0) text.length else it } else text.length
                val lead = if (from > 0) "…" else ""
                val snippet = android.text.SpannableString(lead + text.substring(from, to) + if (to < text.length) "…" else "")
                for (term in terms) {
                    var i = folded[p].indexOf(term)
                    while (i >= 0 && i < to) {
                        if (i >= from) {
                            val start = i - from + lead.length; val end = minOf(i + term.length, to) - from + lead.length
                            snippet.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, end, 0)
                            snippet.setSpan(android.text.style.BackgroundColorSpan(0x4000B7BC), start, end, 0)
                        }
                        i = folded[p].indexOf(term, i + term.length)
                    }
                }
                val row = ui.column().apply {
                    setPadding(0, dp(10), 0, dp(12)); isClickable = true
                    setOnClickListener {
                        searchScroll[b.id] = scroller?.scrollY ?: 0
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                        // The reader's own choice of place, like a page turn: narration, if it plays, carries on where it is.
                        state.select(b.id, p); foundPassage = p; showReader(b)
                    }
                }
                val before = b.toc.filter { it.second <= p }; val nearest = before.maxOfOrNull { it.second }
                val where = before.lastOrNull { it.second == nearest }?.first?.trim()?.takeIf { it.isNotBlank() }
                row.addView(ui.text(listOfNotNull(where, "${100L * words[p] / words[b.passages.size].coerceAtLeast(1)}%").joinToString("  ·  "), 11f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                ui.space(row, 4)
                row.addView(ui.text("", 15f, true).apply { setText(snippet); setLineSpacing(dp(2).toFloat(), 1f) })
                list.addView(row); ui.rule(list)
            }
            if (hits.size > shown) { ui.space(list, 10); list.addView(ui.button("Show more", false) { shown += 100; render() }) }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { shown = 50; render() }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        input.setOnEditorActionListener { v, _, _ -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true }
        render()
        if (input.text.isEmpty()) {
            input.requestFocus()
            input.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0) }
        } else searchScroll[b.id]?.let { y -> scroller?.post { scroller.scrollTo(0, y) } }
    }

    /** Finished: the position goes to the end, playback stops, and the book says so; undone with "Mark as unfinished". */
    private fun markFinished(b: Book, finished: Boolean) {
        if (!finished) { state.setFinished(b.id, 0); state.shelfOwed(b.id, true); if (state.serverWork(b.id) != null) kotlin.concurrent.thread { Server.pushShelf(this, b.id) }; note("${b.title} is back in progress"); if (current?.id == b.id && reader == null) details(b) else redisplay?.invoke(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Mark as finished?")
            .setMessage("${b.title} will be set to its end for reading and listening, and playback stops.")
            .setPositiveButton("Finish") { _, _ ->
                // The service lets go of the book first: stopping, it would otherwise save its own position over the end.
                PlaybackService.forget(); stopService(Intent(this, PlaybackService::class.java))
                // What was read up to here is recorded before the place jumps to the end, so the jump is not counted as reading.
                closeReading()
                state.position(b.id, (b.passages.size - 1).coerceAtLeast(0), b.duration)
                state.setFinished(b.id, System.currentTimeMillis()); state.shelfOwed(b.id, true)
                lastPosition = state.passage(b.id)
                if (state.serverWork(b.id) != null) kotlin.concurrent.thread { Server.push(this, b.id); Server.pushShelf(this, b.id) }
                note("Finished ${b.title}")
                // From inside the reader too: left open, its next page turn would move the place off the end again.
                if (current?.id == b.id) details(b) else redisplay?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Discards the position: back to the first sentence and the start of the audio, playback stopped. */
    private fun discardProgress(b: Book) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard progress?")
            .setMessage("${b.title} goes back to the beginning for reading and listening. The book and its sync stay.")
            .setPositiveButton("Discard") { _, _ ->
                // As when finishing: the stopping service must not save its position over the new beginning.
                PlaybackService.forget(); stopService(Intent(this, PlaybackService::class.java))
                state.position(b.id, 0, 0)
                state.setFinished(b.id, 0); state.shelfOwed(b.id, true)
                state.follow(b.id, true)
                lastPosition = 0
                if (state.serverWork(b.id) != null) kotlin.concurrent.thread { Server.push(this, b.id); Server.pushShelf(this, b.id) }
                note("Progress discarded for ${b.title}")
                if (current?.id == b.id) details(b) else redisplay?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Full-screen view of one book's synchronization: progress, numbers, and every chapter's state. */
    private fun syncOverview(b: Book) {
        arrive("overview:${b.id}")
        current = b
        val body = screen("")
        val bar = ui.row()
        bar.addView(ui.icon("back", "Back to book") { details(b) })
        bar.addView(ui.caption("READING & LISTENING"), LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(bar); ui.space(body, 18)
        body.addView(ui.text(b.title, 26f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        ui.space(body, 6); body.addView(ui.text(b.author, 13f).apply { setTextColor(ui.muted) })
        ui.space(body, 20)
        val headline = ui.text("", 17f).apply { maxLines = 2 }; body.addView(headline)
        ui.space(body, 10)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(ui.olive); progressBackgroundTintList = ColorStateList.valueOf(ui.line)
        }
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        ui.space(body, 16)
        val action = ui.button("Sync now", true) {}; body.addView(action)
        ui.space(body, 20); ui.rule(body); ui.space(body, 8)
        fun stat(title: String): TextView {
            val row = ui.row().apply { setPadding(0, dp(9), 0, dp(9)) }
            row.addView(ui.text(title, 14f), LinearLayout.LayoutParams(0, -2, 1f))
            val value = ui.text("", 14f).apply { setTextColor(ui.muted); gravity = Gravity.END }
            row.addView(value, LinearLayout.LayoutParams(0, -2, 1.2f)); body.addView(row); return value
        }
        val sentences = stat("Sentences matched")
        val chapters = stat("Chapters synced")
        val audio = stat("Audio synced")
        val spent = stat("Time spent")
        ui.space(body, 8); ui.rule(body); ui.space(body, 20)
        body.addView(ui.caption("CHAPTERS")); ui.space(body, 12)
        val units = Alignment.units(b)
        val groups = units.groupBy { it.chapter }.toSortedMap()
        val chapterRows = groups.map { (_, parts) ->
            val row = ui.row().apply { setPadding(0, dp(7), 0, dp(7)) }
            val title = parts.first().title.ifBlank { "Chapter" }
            row.addView(ui.text(title, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            val state = ui.text("", 12f).apply { setTextColor(ui.muted); gravity = Gravity.END; maxLines = 1 }
            row.addView(state, LinearLayout.LayoutParams(0, -2, 1.1f))
            row.setOnClickListener { PlaybackService.send(this, "play", b.id, parts.first().startMs); playerScreen(b) }
            row.contentDescription = "Listen from $title"
            body.addView(row); parts to state
        }
        ui.space(body, 24)
        body.addView(ui.text(if (state.serverWork(b.id) != null) "Your server matched every sentence to its place in the narration. Tap a chapter to listen from its start." else if (!Alignment.onDevice) "This book was matched to its narration on this device by an earlier version of the app. Tap a chapter to listen from its start." else "Every sentence is matched to its place in the narration on this device, chapter by chapter, starting where you are listening. Tap a chapter to listen from its start. Syncing runs in the background and usually finishes in well under an hour; completed chapters are kept if you pause.", 13f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) })
        ui.space(body, 24)
        fun duration(ms: Long): String { val s = ms / 1000; return if (s < 60) "$s s" else if (s < 3600) "${s / 60} min ${s % 60} s" else "${s / 3600} h ${s / 60 % 60} min" }
        val refresh = object : Runnable {
            override fun run() {
                if (current?.id != b.id) return
                val prep = db.prep(b.id)
                val running = PreparationService.running && PreparationService.bookId == b.id
                val synced = prep.second == "Synced"
                val done = db.doneUnits(b.id)
                val stats = db.unitStats(b.id)
                val live = Alignment.live?.takeIf { it.book == b.id }
                val syncedMs = prep.first.coerceAtMost(b.duration)
                val liveMs = live?.pieceMs ?: 0L
                headline.text = syncSummary(b)
                progress.progress = if (b.duration > 0) ((syncedMs + liveMs) * 1000 / b.duration).toInt().coerceIn(0, 1000) else 0
                // Only a book synced on this device can be synced again here: a server book carries the server's sync,
                // and the default build has no speech model.
                action.visibility = if (Alignment.onDevice && state.serverWork(b.id) == null) View.VISIBLE else View.GONE
                action.text = if (running) "Pause syncing" else if (synced) "Sync again" else "Sync now"
                action.contentDescription = "${action.text} for ${b.title}"
                action.setOnClickListener {
                    if (running) Alignment.pause(this@MainActivity, b.id)
                    else {
                        if (synced) { db.invalidateAlignment(b.id); getSharedPreferences("alignment-versions", 0).edit().remove(b.id).apply() }
                        Alignment.resume(this@MainActivity, b)
                    }
                    handler.removeCallbacks(this); handler.postDelayed(this, 500)
                }
                val matched = db.anchorCount(b.id)
                sentences.text = "${"%,d".format(matched)} of ${"%,d".format(b.passages.size)}" + if (b.passages.isNotEmpty()) " · ${100 * matched / b.passages.size}%" else ""
                chapters.text = "${groups.count { (_, parts) -> parts.all { it.key in done } }} of ${groups.size}"
                audio.text = "${Alignment.clock(syncedMs)} of ${Alignment.clock(b.duration)}"
                val rate = if (prep.third > 0) syncedMs.toDouble() / prep.third else 0.0
                spent.text = if (prep.third == 0L) "—" else duration(prep.third) + if (rate > 0) " · ${"%.0f".format(rate)}× realtime" else ""
                for ((parts, view) in chapterRows) {
                    val all = parts.all { it.key in done }
                    val part = parts.firstOrNull { it.key == live?.unit }
                    view.text = when {
                        all -> {
                            val count = db.anchorCountBetween(b.id, parts.first().startMs, parts.last().endMs)
                            val took = parts.sumOf { stats[it.key]?.elapsed ?: 0L }
                            if (count == 0) "Synced" else "Synced · ${"%,d".format(count)} sentences" + if (took > 0) " · ${duration(took)}" else ""
                        }
                        part != null -> "Syncing · ${(100 * (parts.filter { it.key in done }.sumOf { it.coreMs } + liveMs) / parts.sumOf { it.coreMs }.coerceAtLeast(1)).coerceIn(0, 99)}%"
                        parts.any { it.key in done } -> "Partly synced"
                        running -> "Queued"
                        prep.second.startsWith("Paused") -> "Paused"
                        else -> "Not synced"
                    }
                    view.setTextColor(if (part != null) ui.liveText else ui.muted)
                }
                handler.postDelayed(this, 1000)
            }
        }
        overviewRefresh = refresh
        handler.post(refresh)
    }

    /** One-line synchronization state for the shelf and book details. */
    private fun syncSummary(b: Book): String {
        val p = db.prep(b.id)
        val running = PreparationService.running && PreparationService.bookId == b.id
        val percent = if (b.duration > 0) (100 * p.first.coerceAtMost(b.duration) / b.duration).coerceIn(0, 100) else 0
        return when {
            state.serverWork(b.id) != null && state.syncPending(b.id) -> "Syncing on your server · ${"%,d".format(db.anchorCount(b.id))} of ${"%,d".format(b.passages.size)} sentences matched so far"
            state.serverWork(b.id) != null -> if (db.anchorCount(b.id) > 0) "Synced on your server · ${"%,d".format(db.anchorCount(b.id))} sentences matched · ${if (state.serverDownloaded(b.id)) "downloaded" else "streaming"}" else "Waiting for the server's sync"
            running -> p.second.ifBlank { "Syncing" }
            p.second == "Synced" && poorlyMatched(b) -> "Only ${if (b.passages.isEmpty()) 0 else 100 * db.anchorCount(b.id) / b.passages.size}% matched · check pairing"
            p.second == "Synced" -> "Synced · ${"%,d".format(db.anchorCount(b.id))} sentences matched"
            p.second.startsWith("Paused") -> "Sync paused · $percent% done"
            p.first > 0 -> "Partly synced · $percent% done"
            else -> "Not synced yet"
        }
    }

    private fun status(b: Book): String {
        val p = db.prep(b.id)
        val units = Alignment.units(b)
        val done = db.doneUnits(b.id).size
        return "Passage ${"%,d".format(state.passage(b.id) + 1)} of ${"%,d".format(b.passages.size)} · listening at ${Alignment.clock(state.audio(b.id))}\n${"%,d".format(db.anchorCount(b.id))} sentences matched · $done of ${units.size} chapter parts synced\nSynced ${Alignment.clock(p.first.coerceAtMost(b.duration))} of ${Alignment.clock(b.duration)}"
    }

    /**
     * Starts narration where the reader is: a sentence they long-pressed since the last page turn,
     * else the paused audio spot if it is still on the visible page, else the top of the page.
     */
    private fun listen(b: Book) {
        if (b.tracks.isEmpty()) { alert("Attach an audiobook in book details."); return }
        if (chosenAt > browsedAt && chosenAt > 0) { listenFrom(b, state.passage(b.id), false); return }
        val reader = reader
        if (reader == null) { listenFrom(b, state.passage(b.id), false); return }
        reader.visible { first, last ->
            val narrated = db.atTime(b.id, state.audio(b.id))?.passage
            when {
                narrated != null && first >= 0 && last >= first && narrated in first..last -> listenFrom(b, narrated, true)
                // Paused in the last sentence or two of the page before this one (the page was turned
                // when the ear reached its end): pick up inside that sentence rather than skip its tail.
                narrated != null && first >= 0 && narrated in (first - 2) until first -> listenFrom(b, narrated, true)
                first >= 0 -> listenFrom(b, first, false)
                else -> listenFrom(b, state.passage(b.id), false)
            }
        }
    }

    /**
     * Starts narration at `passage`: its measured sentence, or the next measured one within twelve
     * sentences, never anything earlier. With `resumeInside`, audio that stopped inside that very
     * sentence picks up where it stopped. Listen here passes the page's first sentence, or the
     * sentence the audio stopped in when that is still on the page.
     */
    private fun listenFrom(b: Book, passage: Int, resumeInside: Boolean) {
        val audioNow = state.audio(b.id)
        val exact = db.atPassage(b.id, passage)
        val near = exact ?: db.nearestAfter(b.id, passage, 12)
        val target = near?.start
        if (target == null) {
            val syncing = PreparationService.running && PreparationService.bookId == b.id
            MaterialAlertDialogBuilder(this)
                .setMessage(if (state.syncPending(b.id)) "Your server has not matched this part of the book to its audio yet. Resume at the last listening position?" else if (syncing) "This part of the book is not synced yet; syncing is still running in the background. Resume at the last listening position?" else "This passage has no measured audio match. Resume at the last listening position?")
                .setPositiveButton("Resume audio") { _, _ -> PlaybackService.send(this, "play", b.id) }
                .setNegativeButton("Keep reading", null)
                .show()
            return
        }
        val start = if (resumeInside && exact != null && audioNow in exact.start until exact.end) audioNow else target
        PlaybackService.send(this, "play", b.id, start)
        reader?.clearSelection()
        state.follow(b.id, true)
        chosenAt = 0
        near?.let { state.audio(b.id, start, it.passage); goTo(it.passage) }
        refreshReadingChrome(b)
        // A big jump away from where the audio was: a small way back, and nothing otherwise.
        if (audioNow > 0 && kotlin.math.abs(audioNow - start) > 90_000) note("Was at ${Alignment.clock(audioNow)}", "Resume", 4000) { PlaybackService.send(this, "play", b.id, audioNow) }
    }

    /** A quiet, palette-matched notice with an optional single action. */
    private fun note(text: String, action: String? = null, duration: Int = 6000, onAction: () -> Unit = {}) {
        val host = readingShell ?: root
        val bg = ReadingColors.background(this, state); val fg = ReadingColors.foreground(this, state)
        val bar = com.google.android.material.snackbar.Snackbar.make(host, text, duration)
        bar.setBackgroundTint(androidx.core.graphics.ColorUtils.blendARGB(fg, bg, .08f)); bar.setTextColor(bg); bar.setActionTextColor(bg)
        // Above the reader's controls, or above the bottom bar (and the mini player) on an ordinary screen: never over them.
        (bottomChrome?.takeIf { chromeVisible } ?: if (readingShell == null) (miniBar?.takeIf { it.visibility == View.VISIBLE && it.isAttachedToWindow } ?: navBar?.takeIf { it.isAttachedToWindow }) else null)?.let { bar.anchorView = it }
        if (action != null) bar.setAction(action) { onAction() }
        bar.show()
    }

    /**
     * Now listening: the audiobook's own screen. The cover, where the narration is in the chapter and the book, the
     * transport with chapter skips, the speed, the sleep timer and the chapters; Read from here opens the text at the
     * sentence being spoken. Back returns to wherever it was opened from.
     */
    private fun playerScreen(b: Book) {
        if (b.tracks.isEmpty()) return
        arrive("player:${b.id}")
        current = b; state.active = b.id
        val body = screen("", bars = false)
        backTarget = { library() }
        val end = android.text.TextUtils.TruncateAt.END
        val bar = ui.row()
        bar.addView(ui.icon("down", "Close player") { goBack { library() } })
        bar.addView(ui.caption("NOW LISTENING").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.icon("book", "Book details") { details(b) })
        body.addView(bar)
        // The screen fits exactly: everything below has its own height, and the cover is given whatever is left, so
        // nothing scrolls. Only when even a small cover does not fit (a very short screen, very large text) does it scroll.
        screenScroll?.isFillViewport = true
        val coverBox = FrameLayout(this).apply { minimumHeight = dp(120); setPadding(0, dp(10), 0, dp(14)); clipToPadding = false; clipChildren = false }
        var coverShown = 0
        coverBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val room = ((bottom - top - coverBox.paddingTop - coverBox.paddingBottom) / resources.displayMetrics.density).toInt().coerceIn(96, 360)
            if (room != coverShown) {
                coverShown = room
                coverBox.post { coverBox.removeAllViews(); coverBox.addView(ui.cover(b, room * 2 / 3, room).apply { elevation = dp(6).toFloat() }, FrameLayout.LayoutParams(dp(room * 2 / 3), dp(room), Gravity.CENTER)) }
            }
        }
        body.addView(coverBox, LinearLayout.LayoutParams(-1, 0, 1f))
        ui.space(body, 8)
        body.addView(ui.text(b.title, 24f, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = end })
        ui.space(body, 8)
        body.addView(ui.text(b.author, 14f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted); maxLines = 1; ellipsize = end })
        if (b.narrator.isNotBlank()) { ui.space(body, 6); body.addView(ui.text("Read by ${b.narrator}", 12f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted); maxLines = 1; ellipsize = end }) }
        ui.space(body, 20)
        fun now() = (if (PlaybackService.playingBook() == b.id) PlaybackService.livePosition().takeIf { it >= 0 } else null) ?: state.audio(b.id)
        val chapter = ui.text("", 13f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = end }; body.addView(chapter)
        val elapsed = ui.text("", 12f)
        val left = ui.text("", 12f).apply { setTextColor(ui.muted) }
        val total = ui.text(Alignment.clock(b.duration), 12f).apply { setTextColor(ui.muted); gravity = Gravity.END }
        val seek = SeekBar(this).apply {
            max = 1000; contentDescription = "Audiobook seek"
            progressTintList = ColorStateList.valueOf(ui.olive); thumbTintList = ColorStateList.valueOf(ui.olive)
        }
        var dragging = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) elapsed.text = Alignment.clock(p * b.duration / 1000) }
            override fun onStopTrackingTouch(s: SeekBar) { PlaybackService.send(this@MainActivity, "seek", b.id, s.progress * b.duration / 1000); dragging = false }
        })
        body.addView(seek, LinearLayout.LayoutParams(-1, dp(46)))
        val times = ui.row()
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(left); times.addView(total, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(times); ui.space(body, 18)
        val controls = ui.row().apply { gravity = Gravity.CENTER }
        val skips = b.chapters.size > 1
        // Previous chapter: the start of this one, or of the one before when this one has only just begun.
        if (skips) controls.addView(ui.icon("previous", "Previous chapter") {
            val p = now(); val i = b.chapters.indexOfLast { it.start <= p }
            PlaybackService.send(this, "seek", b.id, if (i > 0 && p - b.chapters[i].start < 3000) b.chapters[i - 1].start else b.chapters.getOrNull(i)?.start ?: 0L)
        })
        controls.addView(button("−15s") { PlaybackService.send(this, "back", b.id) })
        lateinit var play: android.widget.ImageButton
        play = ui.icon("play", "Play audiobook", ui.onOlive) {
            PlaybackService.send(this, "toggle", b.id); showPlay(play, b.id, ui.onOlive)
            handler.postDelayed({ if (playerUpdate != null && play.isAttachedToWindow) showPlay(play, b.id, ui.onOlive) }, 200)
        }.apply { background = ui.pressable(ui.olive, 36, ui.onOlive); setPadding(dp(22), dp(22), dp(22), dp(22)) }
        controls.addView(play, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(14), 0, dp(14), 0) })
        controls.addView(button("+30s") { PlaybackService.send(this, "forward", b.id) })
        if (skips) controls.addView(ui.icon("next", "Next chapter") {
            val p = now(); b.chapters.firstOrNull { it.start > p }?.let { PlaybackService.send(this, "seek", b.id, it.start) }
        })
        body.addView(controls); ui.space(body, 22)
        val extras = ui.row()
        fun small(v: MaterialButton) = v.apply { textSize = 13f; setSingleLine(true); ellipsize = end; setPadding(dp(6), 0, dp(6), 0) }
        lateinit var speed: MaterialButton
        // The speed is this book's, kept on the server too so the web plays it at the same speed.
        speed = small(button("${state.speed(b.id)}× speed") { val speeds = listOf(.75f, .8f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f); choose(speeds.map { "$it×" }) { state.speed(b.id, speeds[it], local = true); PlaybackService.send(this, "speed", b.id); speed.text = "${state.speed(b.id)}× speed"; kotlin.concurrent.thread { Server.pushSpeed(this, b.id) } } })
        extras.addView(speed, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        // Minutes or the end of the chapter (-1); while it runs, a shake extends it.
        val sleep = small(button("Sleep timer") { val minutes = listOf(0L, 15L, 30L, 45L, 60L) + (if (b.chapters.isNotEmpty()) listOf(-1L) else emptyList()); choose(minutes.map { when (it) { 0L -> "Off"; -1L -> "End of chapter"; else -> "$it minutes" } }) { PlaybackService.send(this, "sleep", b.id, minutes = minutes[it]) } })
        sleep.contentDescription = "Sleep timer. While it runs, shake the phone to extend it."
        extras.addView(sleep, LinearLayout.LayoutParams(0, dp(50), 1f))
        if (b.chapters.isNotEmpty()) extras.addView(small(button("Chapters") { choose(b.chapters.map { it.title }) { PlaybackService.send(this, "play", b.id, b.chapters[it].start) } }), LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        body.addView(extras)
        if (b.passages.isNotEmpty()) { ui.space(body, 6); body.addView(ui.button("Read from here", true) { showReader(b); followNarration(b) }, LinearLayout.LayoutParams(-1, dp(54))) }
        val update = object : Runnable {
            override fun run() {
                if (playerUpdate !== this) return
                val position = now()
                if (!dragging) { elapsed.text = Alignment.clock(position); seek.progress = if (b.duration > 0) (position * 1000 / b.duration).toInt() else 0 }
                chapter.text = b.chapters.lastOrNull { it.start <= position }?.title?.trim()?.takeIf { it.isNotBlank() } ?: "Audiobook"
                val rate = state.speed(b.id)
                left.text = clockish(((b.duration - position).coerceAtLeast(0) / rate).toLong()) + " left" + if (rate != 1f) " at $rate×" else ""
                val sleeping = if (PlaybackService.playingBook() == b.id) PlaybackService.sleepLeft() else -1L
                sleep.text = when { sleeping < 0 -> "Sleep timer"; PlaybackService.sleepByChapter() -> "Ch. end ${Alignment.clock(sleeping)}"; else -> "Sleep ${Alignment.clock(sleeping)}" }
                // A speed set on the web arrives after the screen opens.
                "$rate× speed".let { if (speed.text != it) speed.text = it }
                showPlay(play, b.id, ui.onOlive)
                handler.postDelayed(this, 500)
            }
        }
        playerUpdate = update; update.run()
    }

    private fun showReader(b: Book) {
        if (b.passages.isEmpty()) return
        arrive("reader:${b.id}"); screenScroll = null; backTarget = null
        playerUpdate?.let { handler.removeCallbacks(it) }; playerUpdate = null
        closeReading()
        readingBook = b.id; readStart = System.currentTimeMillis(); readMs = 0; readPassage = state.passage(b.id); pagesTurned = 0
        current = b; state.active = b.id
        pageFrame?.cancelTurn(); reader?.destroy(); detailStatus = null; syncLine = null
        miniTime = null; miniPlay = null; followButton = null; listenButton = null; bottomChrome = null
        listeningDock = null; dockTime = null; dockPause = null
        val bg = ReadingColors.background(this, state); val fg = ReadingColors.foreground(this, state)
        val shell = FrameLayout(this).apply { setBackgroundColor(bg) }; readingShell = shell
        val layout = ui.column()
        chapterLabel = ui.text(b.title, 10f).apply {
            setTextColor(fg); alpha = .72f; gravity = Gravity.CENTER; letterSpacing = .1f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(30), 0, dp(30), 0)
        }
        chapterLabel?.visibility = if (state.scrollMode) View.VISIBLE else View.GONE
        layout.addView(chapterLabel, LinearLayout.LayoutParams(-1, dp(30)))
        val formatted = FormattedReader(this, b) { p ->
            // A relocation the app itself requested echoes the page's first sentence back; that is
            // not the reader browsing away, so it must not cancel narration-follow.
            if (state.follow(b.id) && SystemClock.elapsedRealtime() - programmaticGoAt < 3000) lastPosition = state.passage(b.id)
            else { state.select(b.id, p); lastPosition = p; refreshReadingChrome(b) }
        }
        reader = formatted
        val frame = ReaderPageFrame(this, formatted)
        pageFrame = frame
        frame.onToggle = { toggleChrome(!chromeVisible) }
        ViewCompat.addAccessibilityAction(frame, "Next page") { _, _ -> frame.turn(1); true }
        ViewCompat.addAccessibilityAction(frame, "Previous page") { _, _ -> frame.turn(-1); true }
        ViewCompat.addAccessibilityAction(frame, "Show or hide the reading controls") { _, _ -> toggleChrome(!chromeVisible); true }
        val frameReady = formatted.onReady
        var found = foundPassage; foundPassage = -1
        formatted.onReady = { frameReady(); formatted.setMarks(state.markIds(b.id, "highlight")); if (found >= 0) formatted.found(found) }
        formatted.onChosen = { p ->
            chosenAt = SystemClock.elapsedRealtime()
            state.select(b.id, p); lastPosition = p; refreshReadingChrome(b)
            if (b.tracks.isNotEmpty()) note("Narration will start from this sentence", "Listen") { listenFrom(b, p, false) }
        }
        frame.onBrowse = {
            pagesTurned++
            browsedAt = SystemClock.elapsedRealtime()
            if (found >= 0) { found = -1; formatted.found(-1) }
            // Turning a page is reading on: the controls get out of the way; a tap brings them back.
            if (chromeVisible) toggleChrome(false)
            state.follow(b.id, false)
            if (state.pauseOnTurn && PlaybackService.playingBook() == b.id && PlaybackService.playing()) PlaybackService.send(this, "pause", b.id)
            refreshReadingChrome(b)
        }
        frame.brightnessNow = {
            window.attributes.screenBrightness.takeIf { it >= 0f }
                ?: runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(.5f)
        }
        frame.onBrightness = { value ->
            window.attributes = window.attributes.apply { screenBrightness = value }
            brightnessPill?.let { it.text = "Brightness ${(value * 100).toInt()}%"; it.visibility = View.VISIBLE; it.alpha = 1f }
        }
        frame.onBrightnessEnd = { brightnessPill?.animate()?.alpha(0f)?.setStartDelay(500)?.setDuration(250)?.withEndAction { brightnessPill?.visibility = View.GONE }?.start() }
        // The right edge: text size, applied the way the appearance sheet applies it, only when the value moves.
        frame.textSizeNow = { state.font }
        frame.onTextSize = { size ->
            if (size != state.font) { state.font = size; pageFrame?.cancelTurn(); reader?.restyle() }
            brightnessPill?.let { it.text = "Text size $size"; it.visibility = View.VISIBLE; it.alpha = 1f }
        }
        frame.onTextSizeEnd = frame.onBrightnessEnd
        frame.scrollOverride = false
        frame.onLeaveFollow = { leaveSmoothFollow(b) }
        formatted.onLocation = { chapter, fraction ->
            chapterLabel?.text = chapter.takeUnless { it.equals("Contents", true) }?.ifBlank { b.title } ?: b.title
            pageFraction = fraction
            readingFooter?.text = "${(fraction * 100).toInt()}%   ·   ${b.title}"
        }
        layout.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        readingFooter = ui.text("${state.passage(b.id) * 100 / b.passages.size}%   ·   ${b.title}", 11f).apply {
            setTextColor(fg); alpha = .72f; gravity = Gravity.CENTER; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(24), 0, dp(24), 0)
        }
        readingFooter?.visibility = if (state.scrollMode) View.VISIBLE else View.GONE
        layout.addView(readingFooter, LinearLayout.LayoutParams(-1, dp(34)))
        if (b.tracks.isNotEmpty()) {
            val dock = ui.row().apply {
                visibility = View.GONE
                setPadding(dp(16), 0, dp(16), 0)
                setBackgroundColor(androidx.core.graphics.ColorUtils.blendARGB(bg, fg, .05f))
            }
            // Same control, same place as the control row: Pause at the left, the headphones at the right.
            dockPause = ui.button("Pause", true) { PlaybackService.send(this, "pause", b.id) }
                .apply { setSingleLine(true); setPadding(dp(16), 0, dp(16), 0); minHeight = dp(44); contentDescription = "Pause narration" }
            dock.addView(dockPause, LinearLayout.LayoutParams(-2, -2))
            dockTime = ui.text("", 12f).apply { setTextColor(fg); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), 0, dp(6), 0); setOnClickListener { if (state.follow(b.id)) playerScreen(b) else followNarration(b) } }
            dock.addView(dockTime, LinearLayout.LayoutParams(0, -2, 1f))
            dock.addView(ui.icon("headphones", "Expand audiobook player", fg) { playerScreen(b) })
            listeningDock = dock
        }
        shell.addView(layout, FrameLayout.LayoutParams(-1, -1))
        // The dock overlays the bottom of the page (it carries the progress), so playback never resizes the page.
        listeningDock?.let { shell.addView(it, FrameLayout.LayoutParams(-1, dp(50)).apply { gravity = Gravity.BOTTOM }) }
        brightnessPill = ui.text("", 13f).apply {
            setTextColor(bg); background = ui.shape(androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 220), 18)
            setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE
        }
        shell.addView(brightnessPill, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; topMargin = dp(56) })
        val chrome = ui.column().apply { visibility = View.GONE }; readingChrome = chrome
        val top = ui.row().apply { setBackgroundColor(bg); elevation = dp(3).toFloat(); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        top.addView(ui.icon("back", "Back to book", fg) { details(b) })
        top.addView(ui.text(b.title, 17f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(fg) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.icon("search", "Search in this book", fg) { searchScreen(b, true) })
        top.addView(ui.icon("contents", "Contents", fg) { formatted.contents() })
        top.addView(ui.icon("font", "Reading appearance", fg) { readingOptions {} })
        top.addView(ui.icon("more", "Reading options", fg) {
            val highlighted = state.passage(b.id) in state.markIds(b.id, "highlight")
            choose(listOf("Book details", if (highlighted) "Remove highlight from this sentence" else "Highlight this sentence", "Bookmark this page", "Bookmarks & highlights", "Mark as finished", "Discard progress", "Hide controls")) { when (it) {
                0 -> details(b)
                1 -> toggleMark(b, state.passage(b.id), "highlight")
                2 -> reader?.visible { first, _ -> runOnUiThread { if (first >= 0) toggleMark(b, first, "bookmark") else note("This page has no text to bookmark") } }
                3 -> marksScreen(b)
                4 -> markFinished(b, true); 5 -> discardProgress(b); 6 -> toggleChrome(false)
            } }
        })
        chrome.addView(top)
        chrome.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = ui.column().apply { setBackgroundColor(bg); elevation = dp(4).toFloat(); setPadding(dp(16), dp(8), dp(16), dp(12)) }
        bottomChrome = bottom
        paceLine = ui.text("", 11f).apply { gravity = Gravity.CENTER; setTextColor(fg); alpha = .6f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(8)); contentDescription = "Time left" }
        bottom.addView(paceLine)
        if (b.tracks.isEmpty()) {
            bottom.addView(ui.text("Tap the center to hide controls  ·  tap an edge or swipe to turn", 11f).apply { gravity = Gravity.CENTER; setTextColor(fg); alpha = .6f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        } else {
            // One row, one position: listen from this page, or pause; Follow appears only while
            // narration continues somewhere you are not reading.
            val mini = ui.row()
            listenButton = ui.button("Listen here", true) {
                if (PlaybackService.playingBook() == b.id && PlaybackService.playing() || PlaybackService.starting(b.id)) { PlaybackService.send(this, "pause", b.id); handler.postDelayed({ if (current?.id == b.id) refreshReadingChrome(b) }, 200) } else listen(b)
            }.apply { setSingleLine(true); setPadding(dp(16), 0, dp(16), 0); minHeight = dp(48); contentDescription = "Listen from this page" }
            mini.addView(listenButton, LinearLayout.LayoutParams(-2, -2))
            miniTime = ui.text("Audiobook", 13f).apply { setTextColor(fg); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), 0, dp(6), 0); setOnClickListener { playerScreen(b) } }
            mini.addView(miniTime, LinearLayout.LayoutParams(0, -2, 1f))
            followButton = button("Follow") { followNarration(b) }.apply { setSingleLine(true); setPadding(dp(14), 0, dp(14), 0); minHeight = dp(48); visibility = View.GONE }
            mini.addView(followButton, LinearLayout.LayoutParams(-2, -2))
            mini.addView(ui.icon("headphones", "Expand audiobook player", fg) { playerScreen(b) })
            bottom.addView(mini)
        }
        chrome.addView(bottom); shell.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            // Edge to edge: the page (and its curl) covers the whole screen; only the text and the
            // native bars keep clear of the cutout and the gesture bar.
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            readerInsets = bars
            chrome.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            (brightnessPill?.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = dp(56) + bars.top; brightnessPill?.layoutParams = it }
            applyReaderInsets()
            insets
        }
        setContentView(shell)
        ViewCompat.requestApplyInsets(shell)
        WindowInsetsControllerCompat(window, shell).apply {
            isAppearanceLightStatusBars = androidx.core.graphics.ColorUtils.calculateLuminance(bg) > .5
            isAppearanceLightNavigationBars = isAppearanceLightStatusBars
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        if (state.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastPosition = state.passage(b.id); chromeVisible = false
        refreshReadingChrome(b)
        if (!getPreferences(MODE_PRIVATE).getBoolean("readingHint", false)) {
            Toast.makeText(this, if (b.tracks.isNotEmpty()) "Tap the center for controls. Long-press a sentence to listen from it." else "Tap the center for controls. Tap an edge or swipe to turn.", Toast.LENGTH_LONG).show()
            getPreferences(MODE_PRIVATE).edit().putBoolean("readingHint", true).apply()
        }
    }

    private fun followNarration(b: Book) {
        state.follow(b.id, true)
        db.atTime(b.id, state.audio(b.id))?.let { state.audio(b.id, state.audio(b.id), it.passage); goTo(it.passage) }
        refreshReadingChrome(b)
    }

    /** Moves the page for the app's own reasons (narration, follow, listen here), not the reader's. */
    private fun goTo(p: Int) {
        programmaticGoAt = SystemClock.elapsedRealtime()
        lastPosition = p
        highlighted = reader?.go(p) == true
    }
    private fun toggleChrome(show: Boolean) {
        chromeVisible = show
        if (show) current?.let { b -> paceLine?.let { val t = paceText(b); it.text = t; it.contentDescription = "Time left: $t" } }
        val fg = ReadingColors.foreground(this, state); val bg = ReadingColors.background(this, state)
        readingChrome?.let { chrome ->
            for (index in listOf(0, chrome.childCount - 1)) chrome.getChildAt(index)?.setBackgroundColor(bg)
            fun tint(view: View) {
                when (view) {
                    is MaterialButton -> if (view === listenButton || view === dockPause) { view.setTextColor(ReadingColors.onAccent(this, state)); view.backgroundTintList = ColorStateList.valueOf(ReadingColors.accent(this, state)) } else { view.setTextColor(fg); view.backgroundTintList = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.blendARGB(bg, fg, .09f)) }
                    is ImageButton -> view.imageTintList = ColorStateList.valueOf(fg)
                    is TextView -> view.setTextColor(fg)
                    is ViewGroup -> for (i in 0 until view.childCount) tint(view.getChildAt(i))
                }
            }
            tint(chrome); chrome.visibility = if (show) View.VISIBLE else View.GONE
            listeningDock?.let { dock ->
                if (dock.visibility != View.GONE) dock.visibility = if (show) View.INVISIBLE else View.VISIBLE
                dock.setBackgroundColor(androidx.core.graphics.ColorUtils.blendARGB(bg, fg, .05f)); tint(dock)
            }
        }
    }
    /** Where the narration is relative to the page being read. */
    private fun parity(b: Book): String {
        if (state.follow(b.id)) return "In sync"
        val narrated = db.atTime(b.id, state.audio(b.id))?.passage ?: return "Audio not synced"
        val reading = state.passage(b.id)
        if (narrated == reading) return "In sync"
        fun chapter(p: Int) = b.toc.indexOfLast { it.second <= p }
        val apart = chapter(narrated) - chapter(reading)
        val direction = if (narrated > reading) "ahead" else "behind"
        return when (kotlin.math.abs(apart)) {
            0 -> "Audio $direction"
            1 -> "Audio a chapter $direction"
            else -> "Audio ${kotlin.math.abs(apart)} chapters $direction"
        }
    }

    /** Gives the system-bar insets to whichever views touch the screen edges right now. */
    private fun applyReaderInsets() {
        val bars = readerInsets ?: return
        val frame = pageFrame ?: return
        val topStrip = chapterLabel?.visibility == View.VISIBLE
        val bottomStrip = readingFooter?.visibility == View.VISIBLE
        chapterLabel?.let { it.setPadding(dp(30) + bars.left, bars.top, dp(30) + bars.right, 0); it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { height = dp(30) + bars.top } }
        readingFooter?.let { it.setPadding(dp(24) + bars.left, 0, dp(24) + bars.right, bars.bottom); it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { height = dp(34) + bars.bottom } }
        listeningDock?.let { it.setPadding(dp(16) + bars.left, 0, dp(16) + bars.right, bars.bottom); it.layoutParams = (it.layoutParams as FrameLayout.LayoutParams).apply { height = dp(50) + bars.bottom } }
        frame.setContentInsets(bars.left, if (topStrip) 0 else bars.top, bars.right, if (bottomStrip) 0 else bars.bottom)
    }

    /**
     * Smooth follow switches the page to a continuous layout when narration is followed, and keeps
     * it while paused: pausing changes nothing on screen but the highlight. Pages return with the
     * next page turn (see [leaveSmoothFollow]) or when the book is reopened.
     */
    private fun applyFollowScroll(b: Book, playing: Boolean) {
        val r = reader ?: return
        if (r.scrollOverride) return
        val wanted = state.followScroll && !state.scrollMode && playing && state.follow(b.id)
        if (!wanted) return
        r.scrollOverride = true
        pageFrame?.scrollOverride = true
        pageFrame?.cancelTurn()
        programmaticGoAt = SystemClock.elapsedRealtime()
        r.restyle()
    }

    /**
     * A page turn while paused in the smooth-follow layout brings the pages back: the frame turns
     * from the screen as it is to the next page, and the reader switches flow underneath the turn.
     */
    private fun leaveSmoothFollow(b: Book): Boolean {
        val r = reader ?: return false
        if (!r.scrollOverride || (PlaybackService.playingBook() == b.id && PlaybackService.playing())) return false
        r.scrollOverride = false
        programmaticGoAt = SystemClock.elapsedRealtime()
        return true
    }

    private fun refreshReadingChrome(b: Book) {
        (if (state.scrollMode) View.VISIBLE else View.GONE).let { v -> if (chapterLabel?.visibility != v) chapterLabel?.visibility = v; if (readingFooter?.visibility != v) readingFooter?.visibility = v }
        val playing = PlaybackService.playingBook() == b.id && PlaybackService.playing()
        applyFollowScroll(b, playing)
        // Pausing changes one thing on screen: the narration highlight goes away.
        if (wasPlaying && !playing) { reader?.clearHighlight(); highlighted = false }
        wasPlaying = playing
        val audio = state.audio(b.id)
        // From the press until the sound comes the button says so, and pressing it again takes the start back.
        val starting = !playing && PlaybackService.starting(b.id)
        listenButton?.let { it.text = if (playing) "Pause" else if (starting) "Starting…" else "Listen here"; it.contentDescription = if (playing || starting) "Pause narration" else "Listen from this page" }
        miniTime?.text = if (playing) "${Alignment.clock(audio)}  ·  ${parity(b)}" else if (audio > 0) "Audio at ${Alignment.clock(audio)}  ·  ${state.speed(b.id)}×" else "Audiobook  ·  ${state.speed(b.id)}×"
        followButton?.visibility = if (playing && !state.follow(b.id)) View.VISIBLE else View.GONE
        if (chromeVisible) paceLine?.let { val t = paceText(b); it.text = t; it.contentDescription = "Time left: $t" }
        // While listening, the dock is the only bottom line: it carries the page percent the footer would show.
        dockTime?.text = "${(pageFraction * 100).toInt()}%  ·  ${Alignment.clock(audio)}  ·  ${parity(b)}" + if (state.follow(b.id)) "" else "  ·  Follow"
        dockTime?.contentDescription = if (state.follow(b.id)) "Expand audiobook player" else "Return to narration"
        listeningDock?.let { dock ->
            dock.visibility = if (!playing) View.GONE else if (chromeVisible) View.INVISIBLE else View.VISIBLE
            // In paged flow the chapter and progress live inside the page (they curl with it); the
            // native strips appear only in scrolled flow, and the footer never beside the dock.
            // Follow-scroll must look exactly like the pages, so the native strips appear only in
            // the reader's own continuous mode.
            val scrolled = state.scrollMode
            chapterLabel?.visibility = if (scrolled) View.VISIBLE else View.GONE
            readingFooter?.visibility = if (scrolled && !playing) View.VISIBLE else View.GONE
            applyReaderInsets()
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysTurn() && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) pageFrame?.turn(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    /** Volume keys turn pages in the reader when that is switched on, except while this book is being narrated: then they are the volume. */
    private fun volumeKeysTurn() = reader != null && state.volumeTurns && !(PlaybackService.playing() && PlaybackService.playingBook() == current?.id)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysTurn() && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun choose(items: List<String>, action: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setItems(items.toTypedArray()) { _, i -> action(i) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readingOptions(done: () -> Unit) {
        ReadingAppearance(this) {
            pageFrame?.cancelTurn()
            reader?.restyle()
            readingShell?.setBackgroundColor(ReadingColors.background(this, state))
            val fg = ReadingColors.foreground(this, state)
            chapterLabel?.setTextColor(fg); readingFooter?.setTextColor(fg)
            if (reader != null) {
                toggleChrome(false)
                readingShell?.let { shell -> WindowInsetsControllerCompat(window, shell).apply {
                    isAppearanceLightStatusBars = androidx.core.graphics.ColorUtils.calculateLuminance(ReadingColors.background(this@MainActivity, state)) > .5
                    isAppearanceLightNavigationBars = isAppearanceLightStatusBars
                } }
                if (state.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            done()
        }.show()
    }

    private var onSettings = false

    private fun settings() {
        arrive("settings")
        current = null
        val body = screen("")
        onSettings = true
        pageHeader(body, "UNISON  /  READER") { library() }
        body.addView(ui.text("Settings", 34f, true)); ui.space(body, 18)
        val signedIn = state.serverToken.isNotBlank()
        val host = ServerClient.normalize(state.serverUrl).removePrefix("https://").removePrefix("http://")

        // Who is signed in, and where.
        if (signedIn) {
            val card = settingsGroup(body, null)
            val r = ui.row().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
            val who = state.serverUser.ifBlank { "Server token" }
            r.addView(TextView(this).apply {
                text = who.take(1).uppercase(); gravity = Gravity.CENTER; textSize = 17f; setTextColor(ui.onAccent)
                background = ui.shape(ui.accent, 999)
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
            val names = ui.column()
            names.addView(ui.text(who, 17f))
            names.addView(ui.text((if (state.serverRole == "admin" || state.serverUser.isBlank()) "Admin  ·  " else "") + host, 13f).apply { setTextColor(ui.muted) })
            r.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            r.contentDescription = "Signed in as $who on $host. Opens your signed-in devices"
            // The card is the account: a tap shows where it is signed in (switching accounts and signing out have their own rows).
            r.background = selectable(); r.isClickable = true; r.setOnClickListener { devicesScreen() }
            r.addView(ui.text("›", 20f).apply { setTextColor(ui.muted) })
            card.addView(r)
        }

        val themes = listOf("system" to "Match phone", "light" to "Light", "dark" to "Dark")
        val looks = listOf("paper" to "Paper", "white" to "White", "night" to "Dusk", "black" to "Black", "system" to "Matches the app")
        val faces = listOf("literata" to "Literata", "serif" to "Classic", "sans" to "Sans", "publisher" to "Original type")
        settingsGroup(body, "APPEARANCE").apply {
            // The app's light or dark, apart from the phone's; the reader's paper has its own choice below.
            settingsRow(this, "moon", "Theme", themes.first { it.first == state.appTheme }.second) {
                choose(themes.map { it.second }) { state.appTheme = themes[it].first; ReaderApplication.applyTheme(state.appTheme); settings() }
            }
            settingsRow(this, "book", "Reading appearance", listOfNotNull(looks.firstOrNull { it.first == state.theme }?.second, faces.firstOrNull { it.first == state.typeface }?.second, "${state.font} pt").joinToString("  ·  ")) { readingOptions { settings() } }
        }

        settingsGroup(body, "YOUR READING").apply {
            if (signedIn) {
                val week = settingsRow(this, "chart", "Reading and listening", statsLine?.removePrefix("This week  ·  ")?.removeSuffix("  ›") ?: "This week, streaks and time of day") { statsScreen() }
                if (statsLine == null) kotlin.concurrent.thread {
                    val text = runCatching {
                        val r = Server.client(state)!!.stats(); val w = r.getJSONObject("week")
                        "${clockish(w.getLong("listenMs"))} listening  ·  ${clockish(w.getLong("readMs"))} reading" + (if (r.optInt("streak") > 0) "  ·  ${r.optInt("streak")}-day streak" else "")
                    }.getOrNull() ?: return@thread
                    runOnUiThread { if (week.isAttachedToWindow) week.text = text }
                }
            }
            val device = settingsRow(this, "phone", "On this device", "Counting…") { downloadsScreen() }
            kotlin.concurrent.thread {
                val books = db.books(); val bytes = books.sumOf { Server.sizeOnDevice(this@MainActivity, it.id) }
                runOnUiThread { device.text = "${books.size} ${if (books.size == 1) "book" else "books"}  ·  ${sizeText(bytes)}" }
            }
            if (Alignment.onDevice) settingsRow(this, "folder", "Library folder", if (state.tree.isBlank()) "Private app storage" else Uri.decode(state.tree).substringAfterLast(':').substringAfterLast('/').ifBlank { "Chosen folder" }) {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Library folder")
                    .setMessage("Where new imports are kept. Books already imported stay where they are. Text positions and alignment are kept privately on this device either way.")
                    .setPositiveButton("Choose a folder") { _, _ ->
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 2)
                    }
                    .setNeutralButton("Use private storage") { _, _ -> state.tree = ""; settings() }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        settingsGroup(body, "SERVER").apply {
            if (!signedIn) settingsRow(this, "server", "Sign in to UnisonServer", "Your server's library, places and shelf on this phone") { signInScreen() }
            else {
                val devices = settingsRow(this, "devices", "Signed-in devices", "Phones and browsers using your account") { devicesScreen() }
                kotlin.concurrent.thread {
                    val n = runCatching { Server.client(state)!!.devices().size }.getOrNull() ?: return@thread
                    runOnUiThread { devices.text = "$n ${if (n == 1) "device" else "devices"}" }
                }
                settingsRow(this, "watch", "Wear OS sign-in", "Sign your watch in with the code it shows") { wearSignIn() }
                if (state.serverRole == "admin" || state.serverUser.isBlank()) settingsRow(this, "people", "People on your server", "Accounts, roles and passwords") { usersScreen() }
                if (state.serverUser.isNotBlank()) settingsRow(this, "lock", "Change password", null) {
                    val form = ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
                    val p0 = EditText(this@MainActivity).apply { hint = "Current password"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    val p1 = EditText(this@MainActivity).apply { hint = "New password (at least eight characters)"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    val p2 = EditText(this@MainActivity).apply { hint = "Again"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                    form.addView(p0); form.addView(p1); form.addView(p2)
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle("Change password").setView(form)
                        .setPositiveButton("Change") { _, _ ->
                            if (p0.text.isEmpty()) { alert("Your current password is needed too."); return@setPositiveButton }
                            if (p1.text.toString() != p2.text.toString()) { alert("The two new entries differ."); return@setPositiveButton }
                            kotlin.concurrent.thread { val r = runCatching { Server.client(state)!!.changePassword(p0.text.toString(), p1.text.toString()) }; runOnUiThread { r.onSuccess { note("Password changed") }.onFailure { val m = it.message.orEmpty(); alert(if ("answered 403" in m) "The current password is wrong. Nothing was changed." else "Couldn't change it: ${m.substringAfter(": ", m)}") } } }
                        }.setNegativeButton("Cancel", null).show()
                }
                settingsRow(this, "swap", "Sign in as someone else", host) { signInScreen() }
            }
        }

        settingsGroup(body, "ABOUT").apply {
            settingsRow(this, "doc", "Libraries and licenses", null) {
                val files = assets.list("licenses").orEmpty().sorted()
                choose(files) { index -> alert(assets.open("licenses/${files[index]}").bufferedReader().use { it.readText() }) }
            }
        }

        // Signing out stands apart at the end, and asks first.
        if (signedIn) {
            ui.space(body, 10)
            val out = settingsGroup(body, null)
            out.addView(ui.text("Sign out", 16f).apply {
                gravity = Gravity.CENTER; setTextColor(ui.error); setPadding(dp(16), dp(16), dp(16), dp(16)); minHeight = dp(52)
                background = selectable(); isClickable = true; contentDescription = "Sign out of $host"
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle("Sign out of $host?")
                        .setMessage("This phone stops syncing your shelf and places until you sign in again.")
                        .setPositiveButton("Sign out") { _, _ ->
                            val client = Server.client(state); val mine = state.serverToken.take(8)
                            kotlin.concurrent.thread { runCatching { client?.signOutDevice(mine) } }
                            state.serverToken = ""; state.serverUser = ""; state.serverRole = ""; state.standalone = false; serverWorks = null; serverShelf = null; serverSeriesProgress = null; serverLists = null
                            signInScreen("Signed out.")
                        }.setNegativeButton("Cancel", null).show()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        ui.space(body, 18)
        body.addView(ui.caption("UnisonReader ${packageManager.getPackageInfo(packageName, 0).versionName}").apply { gravity = Gravity.CENTER })
        ui.space(body, 12)
    }

    private fun selectable(): android.graphics.drawable.Drawable? {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attrs.getDrawable(0).also { attrs.recycle() }
    }

    /** A rounded block of settings under a small capitals caption, in the style of the shelf's sections. */
    private fun settingsGroup(body: LinearLayout, caption: String?): LinearLayout {
        if (caption != null) {
            ui.space(body, 20)
            body.addView(ui.caption(caption).apply { setPadding(dp(6), 0, 0, dp(8)) })
        }
        val group = ui.column().apply { background = ui.shape(ui.surface, 20); clipToOutline = true }
        body.addView(group, LinearLayout.LayoutParams(-1, -2))
        return group
    }

    /** One setting: icon, name, its current value beneath, and a chevron. Returns the value line, to fill in later. */
    private fun settingsRow(group: LinearLayout, icon: String, title: String, value: String?, action: () -> Unit): TextView {
        if (group.childCount > 0) group.addView(View(this).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(56) })
        val r = ui.row().apply { setPadding(dp(16), dp(13), dp(12), dp(13)); minimumHeight = dp(60); background = selectable(); isClickable = true }
        r.addView(android.widget.ImageView(this).apply { setImageDrawable(BookishIcon(icon, ui.accent)) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        val words = ui.column()
        words.addView(ui.text(title, 16f))
        val line = ui.text(value ?: "", 13f).apply { setTextColor(ui.muted); visibility = if (value == null) View.GONE else View.VISIBLE }
        words.addView(line)
        r.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(android.widget.ImageView(this).apply { setImageDrawable(BookishIcon("chevron", ui.muted)) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        r.contentDescription = if (value == null) title else "$title, $value"
        r.setOnClickListener { action() }
        line.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { r.contentDescription = "$title, $e" }
        })
        group.addView(r, LinearLayout.LayoutParams(-1, -2))
        return line
    }

    private fun order(b: Book) {
        choose(b.tracks.mapIndexed { i, t -> "${i+1}. ${t.name}" }) { i ->
            choose(listOf("Move earlier", "Move later")) { direction ->
                val next = if (direction == 0) i - 1 else i + 1
                if (next in b.tracks.indices) {
                    val tracks = b.tracks.toMutableList()
                    java.util.Collections.swap(tracks, i, next)
                    val changed = b.reordered(tracks)
                    db.save(changed)
                    db.invalidateAlignment(b.id)
                    stopService(Intent(this, PlaybackService::class.java))
                    Alignment.autoStart(this, changed)
                    details(changed)
                }
            }
        }
    }

    private fun pick(id: String?) {
        attach = id
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                ),
            1,
        )
    }

    private fun startImports(uris: ArrayList<String>, target: String?, resume: Boolean = false) {
        if (!Alignment.onDevice) return
        startForegroundService(
            Intent(this, ImportService::class.java)
                .putStringArrayListExtra("uris", uris)
                .putExtra("attach", target)
                .putExtra("resume", resume)
        )
        library()
    }

    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 2) {
            data.data?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                state.tree = it.toString()
                settings()
            }
            return
        }
        if (requestCode == 1) {
            val uris =
                data.clipData?.let { c -> List(c.itemCount) { c.getItemAt(it).uri } }
                    ?: listOfNotNull(data.data)
            uris.forEach {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    if (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                        contentResolver.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                }
            }
            startImports(ArrayList(uris.map { it.toString() }), attach)
        }
    }

    private var previousImport = ""
    private val tick =
        object : Runnable {
            override fun run() {
                val b = current
                PlaybackService.takeFailure()?.let { note(it) }
                refreshMiniBar()
                if (b != null) {
                    syncLine?.text = syncSummary(b)
                    refreshReadingChrome(b)
                    if (reader != null && readingBook == b.id && !(PlaybackService.playingBook() == b.id && PlaybackService.playing())) readMs += 1000
                    if (SystemClock.elapsedRealtime() - lastSessionFlush > 60_000) { lastSessionFlush = SystemClock.elapsedRealtime(); kotlin.concurrent.thread { Server.settle(this@MainActivity); if (state.syncPending(b.id)) runCatching { Server.refreshSync(this@MainActivity, b.id) } } }
                    if (state.serverWork(b.id) != null && SystemClock.elapsedRealtime() - lastServerPush > 20_000) {
                        lastServerPush = SystemClock.elapsedRealtime()
                        kotlin.concurrent.thread { Server.push(this@MainActivity, b.id) }
                    }
                    val p = state.passage(b.id)
                    val unmatched =
                        state.follow(b.id) &&
                            PlaybackService.playingBook() == b.id &&
                            PlaybackService.playing() &&
                            db.atTime(b.id, state.audio(b.id)) == null
                    if (unmatched) {
                        reader?.clearHighlight()
                        lastPosition = -1; highlighted = false
                    } else if (p != lastPosition) {
                        goTo(p)
                    }
                } else {
                    detailStatus?.text = ImportService.status
                    detailStatus?.visibility = if (ImportService.status.isBlank()) View.GONE else View.VISIBLE
                    for ((view, book) in syncLines) view.text = shelfLine(book) + if (view.maxLines == 1) "  ›" else ""
                    if (redisplay != null && shelfSignatureNow() != shelfSignature) redisplay?.invoke()
                    if (!ImportService.busy && ImportService.status != previousImport) {
                        previousImport = ImportService.status
                        library()
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }

    /** Address, name and password for the server; a successful sign-in keeps a token for this device. */
    /** A text field in the library's style. */
    private fun field(hint: String, password: Boolean = false, initial: String = ""): EditText = EditText(this).apply {
        this.hint = hint; setText(initial); setSingleLine(); textSize = 16f
        setTextColor(ui.ink); setHintTextColor(ui.muted); background = ui.shape(ui.surface, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }

    /**
     * The sign-in screen: the first thing a fresh install shows, and where Sign out and an expired token
     * return to. The household's server is filled in; Change reveals the address for another one.
     */
    private fun signInScreen(message: String? = null) {
        arrive("signin")
        val body = screen("", scroll = true, bars = false)
        backTarget = { if (state.standalone || db.books().isNotEmpty()) library() else finish() }
        ui.space(body, 48)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        body.addView(ImageView(this).apply { setImageResource(if (night) R.drawable.unison_mark_dark else R.drawable.unison_mark_light); contentDescription = "Unison" }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ui.space(body, 18)
        body.addView(ui.caption("UNISON  /  READER").apply { gravity = Gravity.CENTER })
        ui.space(body, 10)
        body.addView(ui.text("Sign in", 34f, true).apply { gravity = Gravity.CENTER })
        ui.space(body, 8)
        body.addView(ui.text("Your ebooks and audiobooks, in unison.", 15f).apply { setTextColor(ui.muted); gravity = Gravity.CENTER })
        ui.space(body, 32)
        val address = field("Server address, e.g. unison.example.com", initial = state.serverUrl.ifBlank { DEFAULT_SERVER }).apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI; visibility = View.GONE }
        val serverLine = ui.row()
        val serverText = ui.text("Server · ${serverLabel(address.text.toString())}", 13f).apply { setTextColor(ui.muted) }
        serverLine.addView(serverText, LinearLayout.LayoutParams(0, -2, 1f))
        serverLine.addView(ui.button("Change", false) { address.visibility = View.VISIBLE; serverLine.visibility = View.GONE; address.requestFocus() }.apply { textSize = 13f; minHeight = dp(36) })
        body.addView(serverLine); body.addView(address, LinearLayout.LayoutParams(-1, -2)); ui.space(body, 12)
        val name = field("Your name", initial = state.serverUser)
        val password = field("Password", password = true)
        body.addView(name, LinearLayout.LayoutParams(-1, -2)); ui.space(body, 10)
        body.addView(password, LinearLayout.LayoutParams(-1, -2)); ui.space(body, 10)
        val error = ui.text(message ?: "", 14f).apply { setTextColor(if (message != null && message.contains("expired")) ui.error else ui.muted); visibility = if (message == null) View.GONE else View.VISIBLE; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        body.addView(error)
        ui.space(body, 6)
        lateinit var signIn: MaterialButton
        fun attempt() {
            val url = address.text.toString().trim(); val who = name.text.toString().trim(); val secret = password.text.toString()
            if (url.isBlank() || who.isBlank() || secret.isBlank()) { error.text = "Your name and password are both needed."; error.setTextColor(ui.error); error.visibility = View.VISIBLE; return }
            signIn.isEnabled = false; signIn.text = "Signing in…"; error.visibility = View.GONE
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(password.windowToken, 0)
            kotlin.concurrent.thread {
                val result = runCatching { ServerClient(url, "", state.deviceId).login(who, secret, android.os.Build.MODEL) }
                runOnUiThread {
                    result.onSuccess { (token, user) ->
                        // The account on the server is the truth for shelf, places and marks. Whatever this device
                        // held for server books belonged to whoever was signed in before (a token-only connection is the
                        // built-in admin) and goes now, so nothing is mirrored into this account by mistake; the
                        // account's own shelf shows at once and each book's place comes back when it is opened.
                        // The same person signing in again (an expired sign-in, or after signing out) keeps it all: it may
                        // hold progress the phone has not been able to send yet.
                        val account = ServerClient.normalize(url).lowercase() + "|" + user.name.lowercase()
                        val before = state.lastAccount.ifBlank { if (state.serverUser.isNotBlank() && state.serverUrl.isNotBlank()) ServerClient.normalize(state.serverUrl).lowercase() + "|" + state.serverUser.lowercase() else "" }
                        val same = before == account
                        if (!same) {
                            // The service lets go of its book before anything is cleared, so nothing it does while
                            // stopping is recorded under the new account.
                            PlaybackService.forget(); stopService(Intent(this, PlaybackService::class.java))
                            state.forgetPersonalState()
                        }
                        state.serverUrl = url; state.serverToken = token; state.serverUser = user.name; state.serverRole = user.role; state.lastAccount = account; state.standalone = false
                        serverWorks = null; serverShelf = null; serverSeriesProgress = null; serverLists = null; shelfReconciled = false; statsLine = null
                        note(if (same) "Signed in as ${user.name}." else "Signed in as ${user.name}. Your shelf and places come from the server."); library()
                    }.onFailure {
                        signIn.isEnabled = true; signIn.text = "Sign in"
                        val m = it.message.orEmpty()
                        error.text = when {
                            m.contains("401") -> "Wrong name or password."
                            m.contains("429") -> m.substringAfter(": ", "Too many attempts. Wait a little and try again.")
                            m.contains("Unable to resolve host") || m.contains("Failed to connect") -> "Could not reach ${ServerClient.normalize(url).removePrefix("https://")}. Check the address and your connection."
                            else -> "Could not sign in: $m"
                        }
                        error.setTextColor(ui.error); error.visibility = View.VISIBLE
                    }
                }
            }
        }
        signIn = ui.button("Sign in", true) { attempt() }
        password.setOnEditorActionListener { _, _, _ -> attempt(); true }
        body.addView(signIn, LinearLayout.LayoutParams(-1, -2))
        ui.space(body, 18)
        // Without a server the default build can only show books an earlier version imported; offered when there are some.
        if (Alignment.onDevice || db.books().isNotEmpty()) body.addView(ui.button("Use this phone without a server", false) { state.standalone = true; library() }.apply { backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); setTextColor(ui.muted); textSize = 13f }, LinearLayout.LayoutParams(-1, -2))
        ui.space(body, 24)
        body.addView(ui.caption("UnisonReader ${packageManager.getPackageInfo(packageName, 0).versionName}").apply { gravity = Gravity.CENTER })
        (if (name.text.isBlank()) name else password).requestFocus()
    }

    private fun sizeText(bytes: Long): String = when { bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)); bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20); else -> "%d KB".format((bytes shr 10).coerceAtLeast(1)) }

    /** Fetches a streamed server book's audio for offline listening, with progress in the status line. */
    /** Books being added or downloaded right now, so a second tap does not start a second copy into the same files. */
    private val fetching = HashSet<String>()

    private fun downloadAudioFor(b: Book, then: () -> Unit) {
        if (!fetching.add(b.id)) { note("${b.title} is already downloading"); return }
        note("Downloading ${b.title}…")
        val from = trail.lastOrNull()
        kotlin.concurrent.thread {
            val result = runCatching { Server.downloadAudio(this, b.id) { message -> runOnUiThread { detailStatus?.let { it.text = "${b.title}: $message"; it.visibility = View.VISIBLE } } } }
            runOnUiThread {
                fetching.remove(b.id)
                if (gone()) return@runOnUiThread
                detailStatus?.let { it.text = ""; it.visibility = View.GONE }; result.onSuccess { note("${b.title} is on this device") }.onFailure { alert("Could not download: ${it.message}") }
                // Redraw only the screen it was started from; someone reading by now stays in their book.
                if (trail.lastOrNull() == from) then()
            }
        }
    }

    /** Removes a book from this device after a confirmation; a server book stays on the server and on your shelf there. */
    private fun removeFromDevice(b: Book, then: () -> Unit) {
        val server = state.serverWork(b.id) != null
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${b.title} from this device?")
            .setMessage(if (server) "Its files here go. It stays on your server and on your shelf there, and can be brought back in a tap." else "This book was imported on this device. Its files and sync here go for good.")
            .setPositiveButton("Remove") { _, _ ->
                if (PlaybackService.playingBook() == b.id) stopService(Intent(this, PlaybackService::class.java))
                db.remove(b.id); state.removeFromShelf(b.id)
                if (state.active == b.id) state.active = db.books().firstOrNull()?.id ?: ""
                note("Removed ${b.title} from this device"); then()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** What the library takes on this device, book by book, with audio downloads and removals. */
    private fun downloadsScreen() {
        arrive("downloads")
        current = null
        val body = screen("")
        pageHeader(body, "ON THIS DEVICE") { settings() }
        detailStatus = label("", 13f).also { it.setTextColor(ui.muted); it.visibility = View.GONE; body.addView(it) }
        val books = db.books().map { it to Server.sizeOnDevice(this, it.id) }.sortedByDescending { it.second }
        body.addView(ui.text("${books.size} ${if (books.size == 1) "book" else "books"} · ${sizeText(books.sumOf { it.second })}", 15f))
        ui.space(body, 6)
        body.addView(ui.text("Server books stream unless their audio is downloaded; downloaded audio plays without a connection. Removing a server book here leaves it on your server.", 13f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) })
        for ((b, size) in books) {
            val server = state.serverWork(b.id) != null
            val downloaded = !server || state.serverDownloaded(b.id)
            ui.space(body, 14)
            val row = ui.row().apply { gravity = Gravity.TOP }
            row.addView(ui.cover(b, 44, 66))
            val info = ui.column().apply { setPadding(dp(14), 0, 0, 0) }
            info.addView(ui.text(b.title, 16f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(ui.text(listOfNotNull(sizeText(size), when { !server -> "imported here"; downloaded -> "audio downloaded"; b.tracks.isNotEmpty() -> "audio streams"; else -> null }).joinToString("  ·  "), 12f).apply { setTextColor(ui.muted) })
            ui.space(info, 8)
            val actions = ui.row()
            if (server && b.tracks.isNotEmpty()) actions.addView(ui.button(if (downloaded) "Remove audio" else "Download audio", false) {
                if (downloaded) {
                    // Its files are about to go: a player holding them stops first (the place is already saved).
                    if (PlaybackService.playingBook() == b.id) { PlaybackService.forget(); stopService(Intent(this, PlaybackService::class.java)) }
                    Server.removeAudio(this, b.id); note("Audio for ${b.title} streams again"); downloadsScreen()
                } else downloadAudioFor(b) { downloadsScreen() }
            }.apply { textSize = 12f; minHeight = dp(36); insetTop = 0; insetBottom = 0 }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            actions.addView(ui.button("Remove from device", false) { removeFromDevice(b) { downloadsScreen() } }.apply { textSize = 12f; minHeight = dp(36); insetTop = 0; insetBottom = 0 }, LinearLayout.LayoutParams(-2, -2))
            info.addView(actions)
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
        }
        ui.space(body, 30)
    }

    /** Every device signed in as this person, with the way to sign one out. */
    private fun devicesScreen() {
        arrive("devices")
        current = null
        val body = screen("")
        pageHeader(body, "SIGNED-IN DEVICES") { settings() }
        body.addView(ui.text("Each device you sign in on holds its own key. Sign one out here if it is lost or no longer yours.", 14f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) })
        val list = ui.column(); body.addView(list)
        ui.space(list, 16); list.addView(ui.text("Loading…", 13f).apply { setTextColor(ui.muted) })
        kotlin.concurrent.thread {
            val result = runCatching { Server.client(state)!!.devices() }
            runOnUiThread {
                if (list.parent == null) return@runOnUiThread
                list.removeAllViews(); ui.space(list, 12)
                val devices = result.getOrElse { list.addView(ui.text("Could not load: ${it.message}", 13f).apply { setTextColor(ui.muted) }); return@runOnUiThread }
                val mine = state.serverToken.take(8) + "…"
                for (d in devices) {
                    val row = ui.row().apply { setPadding(0, dp(10), 0, dp(10)) }
                    val info = ui.column()
                    info.addView(ui.text(d.device.ifBlank { "Unnamed device" } + if (d.token == mine) "  (this one)" else "", 16f, true))
                    info.addView(ui.text("Last seen ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(d.lastSeen))}", 12f).apply { setTextColor(ui.muted) })
                    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    if (d.token != mine) row.addView(ui.button("Sign out", false) { kotlin.concurrent.thread { runCatching { Server.client(state)!!.signOutDevice(d.token) }; runOnUiThread { devicesScreen() } } }.apply { textSize = 13f; minHeight = dp(40) })
                    list.addView(row)
                }
            }
        }
    }

    /**
     * Wear OS sign-in: the watch shows a six-digit code; typed here, it signs that watch in as this person, so the
     * password is never typed on the watch. The person is shown which device asked, and from where, before saying yes.
     */
    private fun wearSignIn() {
        val client = Server.client(state) ?: return
        fun said(e: Throwable): String { val m = e.message.orEmpty(); return when { m.startsWith("Server answered") && ": " in m -> m.substringAfter(": "); m.startsWith("Server answered 404") || e is org.json.JSONException -> "Your server needs an update for this"; else -> "Could not reach your server" } }
        val input = EditText(this).apply {
            hint = "Code on the watch"; setSingleLine(); textSize = 22f; letterSpacing = .2f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        MaterialAlertDialogBuilder(this).setTitle("Wear OS sign-in").setMessage("Open Unison on the watch and type the code it shows.")
            .setView(ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton("Continue") { _, _ ->
                val code = input.text.toString().filter { it.isDigit() }
                if (code.length != 6) { note("The code is six digits"); return@setPositiveButton }
                kotlin.concurrent.thread {
                    val asked = runCatching { client.linkInfo(code) }
                    runOnUiThread {
                        if (gone()) return@runOnUiThread
                        val d = asked.getOrElse { note(said(it)); return@runOnUiThread }
                        MaterialAlertDialogBuilder(this).setTitle("Sign in ${d.device}?")
                            .setMessage("${d.device} asked for this code from ${d.address}. It will be signed in as ${state.serverUser.ifBlank { "you" }}, with your shelf and your places. Approve only a code you can see on your own watch.")
                            .setPositiveButton("Sign it in") { _, _ ->
                                kotlin.concurrent.thread { val r = runCatching { client.linkApprove(code) }; runOnUiThread { if (!gone()) note(r.fold({ "${d.device} is signed in" }, { said(it) })) } }
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** The household's accounts, for an admin: who there is, add someone, remove someone. */
    private fun usersScreen() {
        arrive("users")
        current = null
        val body = screen("")
        pageHeader(body, "PEOPLE ON YOUR SERVER") { settings() }
        body.addView(ui.text("Each person has their own shelf, positions and finished list. Members sign in with their name and password on any device.", 14f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) })
        val list = ui.column(); body.addView(list)
        ui.space(body, 16)
        body.addView(ui.button("Add a family member", true) {
            val form = ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
            val name = EditText(this).apply { hint = "Name"; setSingleLine() }
            val password = EditText(this).apply { hint = "Password (at least eight characters)"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            form.addView(name); form.addView(password)
            MaterialAlertDialogBuilder(this).setTitle("Add a family member").setView(form)
                .setPositiveButton("Add") { _, _ ->
                    val who = name.text.toString().trim(); val secret = password.text.toString()
                    kotlin.concurrent.thread {
                        val result = runCatching { Server.client(state)!!.createUser(who, secret) }
                        runOnUiThread { result.onSuccess { note("${it.name} can sign in now"); usersScreen() }.onFailure { alert("Could not add: ${it.message}") } }
                    }
                }.setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(-2, -2))
        ui.space(list, 16); list.addView(ui.text("Loading…", 13f).apply { setTextColor(ui.muted) })
        kotlin.concurrent.thread {
            val result = runCatching { Server.client(state)!!.users() }
            runOnUiThread {
                if (list.parent == null) return@runOnUiThread
                list.removeAllViews(); ui.space(list, 12)
                val users = result.getOrElse { list.addView(ui.text("Could not load: ${it.message}", 13f).apply { setTextColor(ui.muted) }); return@runOnUiThread }
                for (u in users) {
                    val row = ui.row().apply { setPadding(0, dp(10), 0, dp(10)) }
                    val info = ui.column()
                    info.addView(ui.text(u.name + if (u.name.equals(state.serverUser, true)) "  (you)" else "", 17f, true))
                    info.addView(ui.text(if (u.role == "admin") "Admin" else "Member", 12f).apply { setTextColor(ui.muted) })
                    row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    // Someone who forgot their password gets a new one from an admin; your own is changed in Settings, with the current one.
                    if (!u.name.equals(state.serverUser, true)) row.addView(ui.button("Set password", false) {
                        val secret = EditText(this).apply { hint = "New password (at least eight characters)"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                        val box = ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(secret) }
                        MaterialAlertDialogBuilder(this).setTitle("New password for ${u.name}").setMessage("They sign in with it from now on. Devices already signed in stay signed in.").setView(box)
                            .setPositiveButton("Set") { _, _ ->
                                val typed = secret.text.toString()
                                if (typed.length < 8) note("Eight characters at least")
                                else onServer({ it.setUserPassword(u.id, typed) }) { note("Password set for ${u.name}") }
                            }.setNegativeButton("Cancel", null).show()
                    }.apply { textSize = 13f; minHeight = dp(40); contentDescription = "Set password for ${u.name}" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                    if (u.id != 1L && !u.name.equals(state.serverUser, true)) row.addView(ui.button("Remove", false) {
                        MaterialAlertDialogBuilder(this).setTitle("Remove ${u.name}?").setMessage("Their shelf, positions and finished list on the server go with them.")
                            .setPositiveButton("Remove") { _, _ -> kotlin.concurrent.thread { runCatching { Server.client(state)!!.deleteUser(u.id) }; runOnUiThread { usersScreen() } } }
                            .setNegativeButton("Cancel", null).show()
                    }.apply { textSize = 13f; minHeight = dp(40) })
                    list.addView(row)
                }
            }
        }
    }

    // ---- My shelf, mirrored to the server for the signed-in person
    private var serverShelf: List<ServerClient.ShelfEntry>? = null
    private var serverSeriesProgress: List<ServerClient.SeriesProgress>? = null

    /**
     * The next book of each series, offered only once the book before it is finished; shelved or not, newest finish
     * first. The server never names a book already started; one started or finished here since the last fetch is skipped.
     */
    private fun nextInSeries(progress: List<ServerClient.SeriesProgress>?): List<Pair<ServerClient.Work, ServerClient.SeriesProgress>> {
        val works = serverWorks?.associateBy { it.id } ?: return emptyList()
        val local = db.books().associateBy { it.id }
        fun begun(work: String) = local[Server.bookId(work)]?.let { b -> state.finished(b.id) > 0 || state.passage(b.id) > 0 || state.audio(b.id) > 0 } == true
        return (progress ?: emptyList()).filter { it.finished > 0 }.mapNotNull { p -> p.next?.takeIf { !begun(it) }?.let { works[it] }?.let { it to p } }.distinctBy { it.first.id }
    }
    // ---- lists: Want to read, collections made by hand, and the household's wanted list (all kept on the server)

    /** The person's lists as the server last gave them; null until asked for. */
    private var serverLists: List<ServerClient.ListInfo>? = null

    /** Runs a server call off the main thread and hands its result to the screen; a failure is said in a line, never thrown. */
    private fun <T> onServer(call: (ServerClient) -> T, failed: () -> Unit = {}, quiet: Boolean = false, then: (T) -> Unit) {
        val client = Server.client(state) ?: run { if (!quiet) note("Connect to your server first"); failed(); return }
        kotlin.concurrent.thread {
            val r = runCatching { call(client) }
            runOnUiThread { r.onSuccess(then).onFailure { e -> val m = e.message.orEmpty(); if (!quiet) note(when { m.startsWith("Server answered 404") || e is org.json.JSONException -> "Your server needs an update for this"; m.startsWith("Server answered") && ": " in m -> m.substringAfter(": "); else -> "Could not reach your server" }); failed() } }
        }
    }

    private fun askText(title: String, hint: String, initial: String = "", ok: String, then: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); this.hint = hint; setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton(ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotBlank() }?.let(then) }.setNegativeButton("Cancel", null).show()
    }

    private fun booksText(n: Int) = when (n) { 0 -> "Nothing yet"; 1 -> "1 book"; else -> "$n books" }

    /** My shelf → Lists: Want to read, the collections, and the way to the wanted list. */
    private fun listsScreen() {
        arrive("lists")
        current = null
        val body = screen("")
        pageHeader(body, "WANT TO READ & COLLECTIONS") { goHome() }
        body.addView(ui.text("Lists", 34f, true)); ui.space(body, 10)
        val list = ui.column(); body.addView(list)
        fun render() {
            list.removeAllViews()
            val lists = serverLists
            if (lists == null) { list.addView(ui.text("Loading…", 13f).apply { setTextColor(ui.muted) }); return }
            for (c in lists) list.addView(settingRow(c.name, booksText(c.works.size), if (c.kind == "want") "contents" else "book") { listScreen(c.key) })
            ui.space(list, 14)
            list.addView(ui.button("New collection", false) { askText("New collection", "Its name", ok = "Make") { name -> onServer({ it.createCollection(name) }) { c -> serverLists = (serverLists ?: emptyList()) + c; listScreen(c.key) } } }, LinearLayout.LayoutParams(-2, -2))
            ui.space(list, 22)
            list.addView(settingRow("The wanted list", "Books the library does not have yet", "search") { wantedScreen() })
        }
        render()
        onServer({ it.collections() }, failed = { if (serverLists == null && list.isAttachedToWindow) { list.removeAllViews(); list.addView(ui.text("Your lists are kept on your server, and it did not answer.", 13f).apply { setTextColor(ui.muted) }) } }) { serverLists = it; if (list.isAttachedToWindow) render() }
    }

    /** One list's books; `key` is "want" or a collection's number. */
    private fun listScreen(key: String) {
        arrive("list:$key")
        current = null
        val body = screen("")
        pageHeader(body, "LISTS") { listsScreen() }
        val title = ui.text(serverLists?.firstOrNull { it.key == key }?.name ?: "", 34f, true).apply { maxLines = 2 }
        body.addView(title); ui.space(body, 6)
        val count = ui.text("", 14f).apply { setTextColor(ui.muted) }; body.addView(count)
        val list = ui.column(); body.addView(list)
        fun render() {
            list.removeAllViews()
            val c = serverLists?.firstOrNull { it.key == key } ?: run { count.text = if (serverLists == null) "Loading…" else "This collection is gone."; return }
            title.text = c.name
            val works = serverWorks?.associateBy { it.id } ?: emptyMap()
            val local = db.books().associateBy { it.id }
            val entries = c.works.mapNotNull { id -> works[id]?.let { Entry(local[Server.bookId(id)], it) } }
            count.text = if (entries.isEmpty()) "No books" else booksText(entries.size)
            ui.space(list, 10)
            if (entries.isEmpty()) list.addView(ui.text(if (c.kind == "want") "Nothing yet. On a book's page, choose Want to read." else "Nothing yet. On a book's page, choose Collections.", 14f).apply { setTextColor(ui.muted) })
            for (e in entries) entryRow(list, e)
            ui.space(list, 16)
            val actions = ui.row()
            fun small(text: String, action: () -> Unit) = actions.addView(ui.button(text, false, action).apply { textSize = 12f; minHeight = dp(36); insetTop = 0; insetBottom = 0 }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            if (entries.isNotEmpty()) small("Take a book off") {
                choose(entries.map { it.title }) { i -> val w = entries[i].work!!; onServer({ it.collect(key, w.id, false) }) { serverLists = serverLists?.map { l -> if (l.key == key) l.without(w.id) else l }; note("${w.title} is off ${c.name}"); render() } }
            }
            if (c.kind == "list") {
                small("Rename") { askText("Rename this collection", "Its name", c.name, "Rename") { name -> onServer({ it.renameCollection(key, name) }) { r -> serverLists = serverLists?.map { l -> if (l.key == key) r else l }?.sortedWith(compareBy<ServerClient.ListInfo> { it.kind != "want" }.thenBy { it.name.lowercase() }); render() } } }
                small("Remove collection") {
                    MaterialAlertDialogBuilder(this).setMessage("Remove “${c.name}”? The collection goes; its books stay in the library.")
                        .setPositiveButton("Remove") { _, _ -> onServer({ it.deleteCollection(key) }) { serverLists = serverLists?.filter { it.key != key }; listsScreen() } }.setNegativeButton("Cancel", null).show()
                }
            }
            list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        }
        render()
        withServerData { onServer({ it.collections() }) { serverLists = it; if (list.isAttachedToWindow) render() } }
    }

    /** Which collections a book is in: boxes that save as they are ticked, and a way to make a new one around it. */
    private fun collectionsSheet(workId: String, title: String) {
        onServer({ it.collections() }) { lists ->
            serverLists = lists
            sheet("Collections") { body, _ ->
                body.addView(ui.text(title, 14f).apply { setTextColor(ui.muted) }); ui.space(body, 10)
                val box = ui.column(); body.addView(box)
                fun render() {
                    box.removeAllViews()
                    val mine = serverLists.orEmpty().filter { it.kind == "list" }
                    if (mine.isEmpty()) box.addView(ui.text("No collections yet.", 14f).apply { setTextColor(ui.muted) })
                    for (c in mine) box.addView(android.widget.CheckBox(this).apply {
                        text = c.name; textSize = 16f; setTextColor(ui.ink); isChecked = workId in c.works; minHeight = dp(48); contentDescription = c.name
                        setOnClickListener {
                            val on = isChecked
                            onServer({ it.collect(c.key, workId, on) }, failed = { isChecked = !on }) { serverLists = serverLists?.map { l -> if (l.key == c.key) (if (on) l.with(workId) else l.without(workId)) else l } }
                        }
                    })
                    ui.space(box, 10)
                    box.addView(ui.button("New collection", false) {
                        askText("New collection", "Its name", ok = "Make") { name -> onServer({ val c = it.createCollection(name); it.collect(c.key, workId, true); c.with(workId) }) { c -> serverLists = serverLists.orEmpty() + c; render() } }
                    }, LinearLayout.LayoutParams(-2, -2))
                }
                render()
            }
        }
    }

    /** The household's wanted list: what is still wanted, what arrived, what was declined; anyone asks, and takes back their own. */
    private fun wantedScreen() {
        arrive("wanted")
        current = null
        val body = screen("")
        pageHeader(body, "LISTS") { listsScreen() }
        body.addView(ui.text("The wanted list", 34f, true)); ui.space(body, 6)
        body.addView(ui.text("Books the library does not have yet.", 14f).apply { setTextColor(ui.muted) }); ui.space(body, 12)
        val list = ui.column()
        val kinds = mapOf("both" to "Ebook and audiobook", "ebook" to "Ebook", "audiobook" to "Audiobook")
        var load: () -> Unit = {}
        fun render(all: List<ServerClient.Wanted>) {
            list.removeAllViews()
            for ((status, head) in listOf("wanted" to "STILL WANTED", "arrived" to "ARRIVED", "declined" to "DECLINED")) {
                val of = all.filter { it.status == status }
                if (of.isEmpty() && status != "wanted") continue
                sectionHead(list, head, of.size)
                if (of.isEmpty()) list.addView(ui.text("Nothing is waiting.", 13f).apply { setTextColor(ui.muted) })
                for (q in of) {
                    val row = ui.column().apply { setPadding(0, dp(8), 0, dp(10)); if (q.work != null) { isClickable = true; contentDescription = "Open ${q.title}"; setOnClickListener { openWork(q.work!!) } } }
                    row.addView(ui.text(q.title, 17f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    if (q.author.isNotBlank()) row.addView(ui.text(q.author, 13f).apply { setTextColor(ui.muted) })
                    ui.space(row, 4)
                    row.addView(ui.text(listOfNotNull(kinds[q.kind], q.requester.takeIf { it.isNotBlank() }?.let { "asked by $it" }, if (q.source == "discord") "on Discord" else null, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(q.created))).joinToString("  ·  "), 11f).apply { setTextColor(ui.muted) })
                    if (q.note.isNotBlank()) { ui.space(row, 4); row.addView(ui.text(q.note, 13f).apply { setTextColor(ui.muted) }) }
                    if (q.mine && status == "wanted") { ui.space(row, 8); row.addView(ui.button("Take it back", false) { onServer({ it.removeRequest(q.id) }) { note("${q.title} is off the wanted list"); load() } }.apply { textSize = 12f; minHeight = dp(36); insetTop = 0; insetBottom = 0; contentDescription = "Take back ${q.title}" }, LinearLayout.LayoutParams(-2, -2)) }
                    list.addView(row)
                }
            }
        }
        load = { onServer({ it.requests() }) { all -> if (list.isAttachedToWindow) render(all) } }
        body.addView(ui.button("Ask for a book", true) { askForBook("") { load() } }, LinearLayout.LayoutParams(-2, dp(48)))
        body.addView(list)
        load()
    }

    /** What the last lookup found, so its screens can be drawn again (Back, a rotation) without asking the catalogues twice. */
    private var lookupWords = ""
    private var lookupHits: List<ServerClient.Found> = emptyList()
    private var lookupDone: () -> Unit = {}

    /** Asking for a book starts with looking it up: the words typed are searched, and a result opens the book's own page. */
    private fun askForBook(title: String, done: () -> Unit = {}) {
        lookupDone = done
        // Words handed over (from the library's search) start a new lookup; opened without any, the last one is still there.
        if (title.isNotBlank() && title.trim() != lookupWords) { lookupWords = title.trim(); lookupHits = emptyList() }
        lookupScreen()
    }

    /** A cover from the lookup, fetched by way of the server and kept in the cache; the title stands in until it is there. */
    private fun foundCover(f: ServerClient.Found, width: Int, height: Int): View {
        val frame = FrameLayout(this).apply { background = ui.shape(ui.surface, 5); clipToOutline = true; layoutParams = LinearLayout.LayoutParams(dp(width), dp(height)); contentDescription = "Cover of ${f.title}" }
        val file = File(cacheDir, "lookup/${f.cover.hashCode().toUInt().toString(16)}.img")
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        fun show() { frame.removeAllViews(); image.setImageURI(Uri.fromFile(file)); frame.addView(image, FrameLayout.LayoutParams(-1, -1)) }
        if (f.cover.isNotBlank() && file.isFile) show()
        else {
            frame.addView(ui.text(f.title, if (width > 80) 18f else 11f, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }, FrameLayout.LayoutParams(-1, -1))
            if (f.cover.isNotBlank()) Server.client(state)?.let { client ->
                coverPool.execute {
                    runCatching { if (!file.isFile) client.lookupCover(f.cover, file) }
                    if (file.isFile) runOnUiThread { if (frame.isAttachedToWindow) show() }
                }
            }
        }
        return frame
    }

    private fun foundLine(f: ServerClient.Found) = when {
        f.work != null && f.hasEbook && f.hasAudio -> "On your server  ·  ebook and audiobook"
        f.work != null && f.hasEbook -> "On your server  ·  ebook only"
        f.work != null -> "On your server  ·  audiobook only"
        f.wanted -> "Not on your server  ·  already on the wanted list"
        else -> null
    }

    /** Look a book up: a search box, and what Audible and Open Library answer, each row opening the book's page. */
    private fun lookupScreen() {
        arrive("lookup")
        current = null
        val body = screen("")
        pageHeader(body, "THE WANTED LIST") { wantedScreen() }
        body.addView(ui.text("Ask for a book", 34f, true)); ui.space(body, 6)
        body.addView(ui.text("Look it up first, then ask for the one you mean.", 14f).apply { setTextColor(ui.muted) }); ui.space(body, 14)
        val words = field("Title, or title and author", initial = lookupWords).apply { contentDescription = "Look up a book"; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        body.addView(words)
        ui.space(body, 8)
        val list = ui.column()
        fun render(errors: List<String> = emptyList()) {
            list.removeAllViews()
            if (lookupHits.isEmpty()) {
                if (lookupWords.isNotBlank()) { ui.space(list, 16); list.addView(ui.text(if (errors.isEmpty()) "Nothing found for “$lookupWords”. Try fewer words, or ask by title below." else "The lookup could not be reached (${errors.joinToString("; ")}). You can still ask by title below.", 14f).apply { setTextColor(ui.muted); setLineSpacing(dp(3).toFloat(), 1f) }) }
            } else sectionHead(list, "FOUND", lookupHits.size)
            for ((i, f) in lookupHits.withIndex()) {
                val row = ui.row().apply { gravity = Gravity.TOP; setPadding(0, dp(8), 0, dp(12)); isClickable = true; contentDescription = "Open ${f.title}"; setOnClickListener { foundScreen(i) } }
                row.addView(foundCover(f, 54, 82))
                val info = ui.column().apply { setPadding(dp(17), 0, dp(8), 0) }
                info.addView(ui.text(f.title, 18f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                ui.space(info, 4)
                info.addView(ui.text(listOfNotNull(f.authors.joinToString(", ").ifBlank { null }, f.series.firstOrNull()?.label).joinToString("  ·  ").ifBlank { "Unknown author" }, 13f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                ui.space(info, 6)
                val have = foundLine(f)
                // Always said, one way or the other: what the server has of this book.
                info.addView(ui.text(have ?: "Not on your server", 12f).apply { setTextColor(if (f.work != null) ui.liveText else ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                ui.space(info, 3)
                info.addView(ui.text(listOfNotNull(f.year.ifBlank { null }, "from ${f.source}").joinToString("  ·  "), 11f).apply { setTextColor(ui.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(ui.icon("chevron", "Open ${f.title}", ui.muted) { foundScreen(i) })
                list.addView(row)
            }
            ui.space(list, 18)
            list.addView(ui.text("Not there? Ask by title  ›", 14f).apply { setTextColor(ui.olive); minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; contentDescription = "Ask by title"; setOnClickListener { askByTitle(words.text.toString().trim()) } })
        }
        fun search() {
            val typed = words.text.toString().trim()
            if (typed.length < 2) { note("A title to look up"); return }
            lookupWords = typed; lookupHits = emptyList()
            list.removeAllViews(); ui.space(list, 16); list.addView(ui.text("Looking it up…", 14f).apply { setTextColor(ui.muted) })
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(words.windowToken, 0)
            onServer({ it.lookup(typed) }, failed = { if (list.isAttachedToWindow && lookupWords == typed) render(listOf("no answer from your server")) }) { (found, errors) ->
                if (lookupWords != typed) return@onServer
                lookupHits = found
                if (list.isAttachedToWindow) render(errors)
            }
        }
        words.setOnEditorActionListener { _, action, event -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { search(); true } else false }
        body.addView(ui.button("Look it up", true) { search() }, LinearLayout.LayoutParams(-2, dp(48)))
        body.addView(list)
        // Words handed over (from the library's own search) are looked up at once; results already here are shown again.
        if (lookupHits.isNotEmpty()) render() else if (lookupWords.isNotBlank()) search() else render()
    }

    /** One book the lookup found: everything the catalogue says about it, and the way to ask the library for it. */
    private fun foundScreen(index: Int) {
        val f = lookupHits.getOrNull(index) ?: return
        arrive("found:$index")
        current = null
        val body = screen("")
        pageHeader(body, "ASK FOR A BOOK") { lookupScreen() }
        body.addView(foundCover(f, 146, 218).apply { elevation = dp(5).toFloat() }, LinearLayout.LayoutParams(dp(146), dp(218)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        ui.space(body, 22)
        body.addView(ui.text(f.title, 26f, true).apply { gravity = Gravity.CENTER; maxLines = 3 })
        if (f.subtitle.isNotBlank()) { ui.space(body, 4); body.addView(ui.text(f.subtitle, 15f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) }) }
        if (f.authors.isNotEmpty()) { ui.space(body, 8); body.addView(ui.text(f.authors.joinToString(", "), 16f).apply { gravity = Gravity.CENTER }) }
        if (f.narrators.isNotEmpty()) { ui.space(body, 6); body.addView(ui.text("Read by ${f.narrators.joinToString(", ")}", 14f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) }) }
        if (f.series.isNotEmpty()) { ui.space(body, 6); body.addView(ui.text(f.series.joinToString("  ·  ") { it.label }, 14f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) }) }
        val facts = listOfNotNull(f.year.ifBlank { null }, f.publisher.ifBlank { null }, "from ${f.source}")
        ui.space(body, 10); body.addView(ui.text(facts.joinToString("  ·  "), 13f).apply { gravity = Gravity.CENTER; setTextColor(ui.muted) })
        ui.space(body, 10); body.addView(ui.text(foundLine(f) ?: "Not on your server", 14f).apply { gravity = Gravity.CENTER; setTextColor(if (f.work != null) ui.liveText else ui.muted); contentDescription = "What your server has: " + (foundLine(f) ?: "nothing") })
        ui.space(body, 20)
        // What to ask for: both by default; when the library has one form already, the other.
        val kinds = listOf("both" to "Ebook and audiobook", "ebook" to "Ebook", "audiobook" to "Audiobook")
        var kind = when { f.work != null && f.hasEbook && !f.hasAudio -> "audiobook"; f.work != null && f.hasAudio && !f.hasEbook -> "ebook"; else -> "both" }
        val whole = f.work != null && f.hasEbook && f.hasAudio
        if (f.work != null) { body.addView(ui.button("Open it in your library", whole) { openWork(f.work!!) }, LinearLayout.LayoutParams(-1, dp(54))); ui.space(body, 8) }
        if (!whole) {
            val actions = ui.row()
            lateinit var pick: MaterialButton
            pick = ui.button(kinds.first { it.first == kind }.second, false) { choose(kinds.map { it.second }) { i -> kind = kinds[i].first; pick.text = kinds[i].second } }.apply { contentDescription = "Which form to ask for"; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END }
            actions.addView(pick, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
            lateinit var ask: MaterialButton
            ask = ui.button(if (f.wanted) "Already wanted" else "Request", true) {
                ask.isEnabled = false
                onServer({ it.ask(f.title, f.authors.firstOrNull().orEmpty(), kind, "") }, failed = { ask.isEnabled = true }) { q ->
                    note(if (q.status == "arrived") "The library already has ${q.title}" else "${q.title} is on the wanted list")
                    lookupWords = ""; lookupHits = emptyList()
                    val done = lookupDone; lookupDone = {}
                    wantedScreen(); done()
                }
            }.apply { isEnabled = !f.wanted }
            actions.addView(ask, LinearLayout.LayoutParams(0, dp(54), 1f))
            body.addView(actions)
        }
        if (f.genres.isNotEmpty()) {
            ui.space(body, 22)
            val wrap = ui.row()
            for (g in f.genres.take(8)) wrap.addView(ui.text(g, 12f).apply { setTextColor(ui.muted); background = ui.shape(ui.surface, 10); setPadding(dp(10), dp(5), dp(10), dp(5)) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(wrap) })
        }
        if (f.description.isNotBlank()) {
            ui.space(body, 20); body.addView(ui.caption("ABOUT")); ui.space(body, 8)
            body.addView(ui.text(f.description, 15f, true).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        }
        ui.space(body, 30)
    }

    /** Asking without a lookup, for a book no catalogue knows: title, author, and which form. */
    private fun askByTitle(title: String) {
        val t = field("Title", initial = title); val a = field("Author")
        val kinds = listOf("both" to "Ebook and audiobook", "ebook" to "Ebook", "audiobook" to "Audiobook")
        var kind = "both"
        val pick = ui.button(kinds[0].second, false) {}
        pick.setOnClickListener { choose(kinds.map { it.second }) { i -> kind = kinds[i].first; pick.text = kinds[i].second } }
        val box = ui.column().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        box.addView(t); ui.space(box, 8); box.addView(a); ui.space(box, 8); box.addView(pick, LinearLayout.LayoutParams(-2, -2))
        MaterialAlertDialogBuilder(this).setTitle("Ask for a book").setView(box)
            .setPositiveButton("Ask") { _, _ ->
                val name = t.text.toString().trim()
                if (name.isBlank()) note("A title, please")
                else onServer({ it.ask(name, a.text.toString().trim(), kind, "") }) { q -> note(if (q.status == "arrived") "The library already has ${q.title}" else "${q.title} is on the wanted list"); lookupWords = ""; lookupHits = emptyList(); val done = lookupDone; lookupDone = {}; wantedScreen(); done() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun shelfAdd(id: String) {
        state.addToShelf(id); state.shelfOwed(id, true)
        // A book on the shelf has been picked up: the server takes it off Want to read, and so does the copy kept here.
        state.serverWork(id)?.let { w -> serverLists = serverLists?.map { if (it.kind == "want") it.without(w) else it } }
        if (state.serverWork(id) != null) kotlin.concurrent.thread { Server.pushShelf(this, id) } }
    private fun shelfRemove(id: String) { state.removeFromShelf(id); state.shelfOwed(id, true); if (state.serverWork(id) != null) kotlin.concurrent.thread { Server.pushShelf(this, id) } }

    /** Brings a server work onto this device, streaming its audio or downloading it, then opens its details. */
    private fun addServerWork(work: ServerClient.Work, download: Boolean, toShelf: Boolean = true) {
        if (!fetching.add(work.id)) { note("${work.title} is already on its way"); return }
        note(if (download) "Downloading ${work.title}…" else "Adding ${work.title}…")
        kotlin.concurrent.thread {
            val result = runCatching { Server.add(this, work, download) { message -> runOnUiThread { detailStatus?.let { it.text = "${work.title}: $message"; it.visibility = View.VISIBLE } } } }
            runOnUiThread {
                fetching.remove(work.id)
                if (gone()) return@runOnUiThread
                detailStatus?.let { it.text = ""; it.visibility = View.GONE }
                result.onSuccess { book ->
                    if (toShelf) shelfAdd(book.id)
                    note(if (book.tracks.isEmpty()) "${book.title} is on your shelf" else if (download) "${book.title} is on this device and on your shelf" else "${book.title} is on your shelf, streaming from your server")
                    // Still on the book's server page: it becomes the book's own page. Gone elsewhere (reading
                    // another book, say): the note is enough and nothing moves.
                    if (trail.lastOrNull() == "work:${work.id}") { trail.removeAt(trail.lastIndex); details(book) } else if (trail.lastOrNull() == "book:${book.id}") details(book) else redisplay?.invoke()
                }.onFailure { alert("Could not add ${work.title}: ${it.message}") }
            }
        }
    }
}

/** Server books looked at in this run of the app, so Back can return to one whose page came from a single request. */
private val worksSeen = HashMap<String, ServerClient.Work>()
