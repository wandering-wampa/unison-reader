package dev.homescreenreader.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.homescreenreader.ServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unison on the watch. Signed in by a code approved on the phone or the web, it shows the audiobooks on the person's
 * shelf, puts the ones they choose on the watch, and plays them through the watch's headphones with the place kept
 * in step with every other device.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UnisonWatch() }
    }
}

// The Unison look at night, which is the only light a watch has: black glass, cream text, teal for what is live.
private val Teal = Color(0xFF00B7BC)
private val Cream = Color(0xFFF3EEDF)
private val Muted = Color(0xFFA6B4BE)
private val DeepBlue = Color(0xFF062F47)
private val UnisonColors = ColorScheme(
    primary = Teal, primaryDim = Color(0xFF0A8F93), primaryContainer = Color(0xFF125657), onPrimary = DeepBlue, onPrimaryContainer = Cream,
    secondary = Cream, secondaryDim = Muted, secondaryContainer = Color(0xFF173B4D), onSecondary = DeepBlue, onSecondaryContainer = Cream,
    surfaceContainerLow = Color(0xFF0B2231), surfaceContainer = Color(0xFF0F2D40), surfaceContainerHigh = Color(0xFF173B4D),
    onSurface = Cream, onSurfaceVariant = Muted, background = Color.Black, onBackground = Cream,
)

/** Work that must finish whatever screen is showing: asking the server, sending a place. */
private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Listening from the watch's own speaker was chosen, until the app is closed. */
private var speakerChosen = false

@Composable
private fun UnisonWatch() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val nav = rememberSwipeDismissableNavController()
    // The address the sign-in screen asks: kept here so that screen starts over when it is changed on the next one.
    var server by remember { mutableStateOf(store.serverUrl) }
    MaterialTheme(colorScheme = UnisonColors) {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = if (store.signedIn) "home" else "link") {
                composable("link") { LinkScreen(store, server, signedIn = { nav.navigate("home") { popUpTo("link") { inclusive = true } } }, changeServer = { nav.navigate("server") }) }
                composable("server") { ServerScreen(store) { server = store.serverUrl; nav.popBackStack() } }
                composable("home") { HomeScreen(store, nav) }
                composable("book/{id}") { BookScreen(store, it.arguments?.getString("id").orEmpty(), nav) }
                composable("places/{id}") { PlacesScreen(store, it.arguments?.getString("id").orEmpty(), nav) }
                composable("output/{id}") { OutputScreen(it.arguments?.getString("id").orEmpty(), nav) }
                composable("player/{id}") { PlayerScreen(store, it.arguments?.getString("id").orEmpty(), nav) }
                composable("chapters/{id}") { ChaptersScreen(store, it.arguments?.getString("id").orEmpty(), nav) }
                composable("speed/{id}") { SpeedScreen(store, it.arguments?.getString("id").orEmpty(), nav) }
                composable("volume") { VolumeScreen() }
                composable("settings") { SettingsScreen(store, nav) }
                composable("signout") { SignOutScreen(store, nav) }
            }
        }
    }
}

// ---------------------------------------------------------------- small pieces

/** Runs `block` now and every `ms` while the screen is in view. */
@Composable
private fun Every(ms: Long, block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    LaunchedEffect(owner, ms) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { current(); delay(ms) } } }
}

/** A list page: the standard scrolling column of a watch, with the time above it. */
@Composable
private fun Page(first: Int = 0, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = first)
    ScreenScaffold(scrollState = state) { padding ->
        // A page starts at its heading, under the time, rather than with the heading in the middle of the glass.
        ScalingLazyColumn(state = state, contentPadding = padding, autoCentering = null, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))

/** A ring turning round something that was pressed and is on its way. */
@Composable
private fun Ring(modifier: Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val turn by rememberInfiniteTransition(label = "ring").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        drawArc(color, turn, 250f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A skip button's face: the turning arrow with its seconds inside. */
@Composable
private fun Skip(icon: Int, seconds: String, label: String) = Box(contentAlignment = Alignment.Center) {
    Icon(painterResource(icon), label, modifier = Modifier.size(34.dp))
    Text(seconds, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
}

private fun clock(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
private fun span(ms: Long): String { val m = (ms.coerceAtLeast(0) + 30_000) / 60_000; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
private fun left(b: WatchBook, position: Long, speed: Float) = if (position >= b.duration - 1000) "Finished" else span(((b.duration - position) / speed).toLong()) + " left"
private fun megabytes(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${(bytes + 500_000) / 1_000_000} MB"
private fun speedText(v: Float) = (if (v == v.toInt().toFloat()) "${v.toInt()}.0" else v.toString()) + "×"
private fun host(url: String) = url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

private val HEADPHONES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
)
private fun outputs(context: Context) = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
private fun headphones(context: Context) = outputs(context).any { it.type in HEADPHONES }
private fun speaker(context: Context) = outputs(context).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

/** Starts the book, by way of the headphones question when nothing is connected to hear it through. */
private fun play(context: Context, nav: NavHostController, id: String, position: Long = -1) {
    if (!headphones(context) && !speakerChosen) { nav.navigate("output/$id") { popUpTo("book/{id}") }; return }
    PlaybackService.send(context, "play", id, position)
    if (nav.currentDestination?.route != "player/{id}") nav.navigate("player/$id") { popUpTo("book/{id}") }
}

// ---------------------------------------------------------------- signing in

private sealed class Link {
    object Asking : Link()
    object NoServer : Link()
    class Code(val code: String) : Link()
    object Ended : Link()
    class Failed(val why: String) : Link()
}

/**
 * Wear OS sign-in. The watch asks the server for a code and shows it; the person types it in Unison on their phone (or
 * on the web) and approves; the watch, asking every couple of seconds, is then given its own key. No password is typed here.
 */
@Composable
private fun LinkScreen(store: Store, server: String, signedIn: () -> Unit, changeServer: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf<Link>(Link.Asking) }
    var attempt by remember { mutableIntStateOf(0) }
    val done by rememberUpdatedState(signedIn)
    LaunchedEffect(attempt, server) {
        if (server.isBlank()) { phase = Link.NoServer; return@LaunchedEffect }
        phase = Link.Asking
        val client = ServerClient(server, "", store.deviceId)
        val started = withContext(Dispatchers.IO) { runCatching { client.linkStart(Build.MODEL.orEmpty().ifBlank { "Wear OS watch" }) } }.getOrElse { e ->
            val m = e.message.orEmpty()
            phase = Link.Failed(when { m.startsWith("Server answered 404") || m.startsWith("Server answered 401") -> "This server needs an update before a watch can sign in.";m.startsWith("Server answered") && ": " in m -> m.substringAfter(": "); else -> "Could not reach ${host(server)}." })
            return@LaunchedEffect
        }
        phase = Link.Code(started.code)
        val until = android.os.SystemClock.elapsedRealtime() + 11 * 60_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            delay(started.poll * 1000L)
            val answer = withContext(Dispatchers.IO) { runCatching { client.linkClaim(started.secret) } }
            // The code ended, or was used: a new one is needed. Anything else is a moment without a connection.
            if (answer.exceptionOrNull()?.message?.startsWith("Server answered 4") == true) break
            answer.getOrNull()?.let { (token, user) -> store.signIn(token, user.name); done(); return@LaunchedEffect }
        }
        phase = Link.Ended
    }
    // The code stays lit while it waits to be typed on the phone.
    val showing = phase is Link.Code
    DisposableEffect(showing) {
        val window = (context as? ComponentActivity)?.window
        if (showing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Page {
        item { ListHeader { Text("Wear OS sign-in") } }
        when (val p = phase) {
            is Link.Asking -> item { Note("Asking ${host(server)} for a code…") }
            is Link.NoServer -> item { Note("Which server is your library on?") }
            is Link.Code -> {
                item { Text(p.code.take(3) + " " + p.code.drop(3), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "Code " + p.code }) }
                item { Note("In Unison on your phone, open Settings, then Wear OS sign-in, and type this code.") }
            }
            is Link.Ended -> { item { Note("That code has ended.") }; item { Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("New code") } } }
            is Link.Failed -> { item { Note(p.why) }; item { Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Try again") } } }
        }
        item { FilledTonalButton(onClick = changeServer, modifier = Modifier.fillMaxWidth(), secondaryLabel = { Text(host(server).ifBlank { "Not set" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }) { Text("Server") } }
    }
}

/** The server's address, typed once on a watch built without one. */
@Composable
private fun ServerScreen(store: Store, done: () -> Unit) {
    var text by remember { mutableStateOf(host(store.serverUrl)) }
    val save = { if (text.isNotBlank()) { store.serverUrl = text.trim(); done() } }
    Page {
        item { ListHeader { Text("Server address") } }
        item {
            BasicTextField(
                value = text, onValueChange = { text = it.filter { c -> !c.isWhitespace() } }, singleLine = true,
                textStyle = TextStyle(color = Cream, fontSize = 15.sp, textAlign = TextAlign.Center),
                // The keyboard's own tick only closes the keyboard: what was typed reaches this field after that tick is
                // handled, so saving there would keep the old address. The button below saves.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 12.dp).semantics { contentDescription = "Server address" },
            )
        }
        item { Note("Like unison.example.org. Tap the field to type.") }
        item { Button(onClick = save, modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank()) { Text("Use this server") } }
    }
}

// ---------------------------------------------------------------- the shelf

@Composable
private fun HomeScreen(store: Store, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun listed(): List<WatchBook> {
        val shelf = store.shelf
        val all = store.books()
        // The shelf's own order (what is being read first), books kept on the watch after leaving the shelf last; the book last listened to here leads.
        return all.sortedWith(compareByDescending<WatchBook> { store.listened(it.id) }.thenBy { b -> shelf.indexOf(b.id).let { if (it < 0) Int.MAX_VALUE else it } })
    }
    var books by remember { mutableStateOf(listed()) }
    var refreshing by remember { mutableStateOf(false) }
    var reached by remember { mutableStateOf(true) }
    var playingId by remember { mutableStateOf(PlaybackService.playingBook()) }
    var tick by remember { mutableIntStateOf(0) }
    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            reached = withContext(Dispatchers.IO) { Sync.refreshShelf(context) }
            refreshing = false
            if (!store.signedIn) { PlaybackService.forget(context); nav.navigate("link") { popUpTo("home") { inclusive = true } } } else books = listed()
        }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { books = listed(); refresh() } }
    Every(1000) { playingId = PlaybackService.playingBook(); tick++ }
    // The media notification is how the watch shows its own controls for the book; it needs the person's yes once.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    Page {
        item { ListHeader { Text("Audiobooks") } }
        playingId?.let { id -> store.book(id) }?.let { b ->
            item(key = "now") {
                Button(onClick = { nav.navigate("player/${b.id}") }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_headphones), null) },
                    secondaryLabel = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }) { Text(if (PlaybackService.playing()) "Now listening" else "Paused") }
            }
        }
        if (books.isEmpty()) item { Note(if (refreshing) "Fetching your shelf…" else if (!reached) "Could not reach your server." else "No audiobooks on your shelf yet. Put one on your shelf in the phone app or on the web.") }
        for (b in books) item(key = b.id) {
            tick
            val onWatch = store.downloaded(b)
            val line = listOf(left(b, store.audio(b.id), store.speed(b.id)), if (onWatch) "on watch" else DownloadService.progress(b.id)?.let { "downloading" }).filterNotNull().joinToString("  ·  ")
            FilledTonalButton(onClick = { nav.navigate("book/${b.id}") }, modifier = Modifier.fillMaxWidth(), secondaryLabel = { Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis) }) { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (books.isNotEmpty() && !reached && !refreshing) item { Note("No connection: books on the watch still play.") }
        item { FilledTonalButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth(), enabled = !refreshing, icon = { Icon(painterResource(R.drawable.ic_refresh), null) }) { Text(if (refreshing) "Refreshing…" else "Refresh") } }
        item { FilledTonalButton(onClick = { nav.navigate("settings") }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_settings), null) }) { Text("Settings") } }
    }
}

// ---------------------------------------------------------------- one book

@Composable
private fun BookScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book = remember(id) { store.book(id) }
    if (book == null) { Page { item { Note("This book is no longer on the watch.") } }; return }
    var tick by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Every(500) { tick++; DownloadService.takeProblem(id)?.let { problem = it } }
    // The speed may have been changed on another device; it is here by the time Listen is pressed.
    LaunchedEffect(id) { withContext(Dispatchers.IO) { runCatching { Sync.pullSpeed(context, id) } } }
    fun listen() {
        if (opening) return
        if (PlaybackService.playingBook() == id && (PlaybackService.playing() || PlaybackService.starting(id))) { nav.navigate("player/$id"); return }
        if (store.elsewhere(id) != null) { nav.navigate("places/$id"); return }
        opening = true
        scope.launch {
            // The newest place, from whichever device left it: the server gets two and a half seconds, then the book opens as it is here.
            val asked = background.async { Sync.pull(context, id) }
            val pulled = withTimeoutOrNull(2500) { asked.await() }
            opening = false
            when (pulled) {
                is Sync.Pulled.Newer -> { Sync.take(context, id, pulled.audio, pulled.updated); play(context, nav, id) }
                is Sync.Pulled.Apart -> { store.elsewhere(id, pulled.audio, pulled.updated, pulled.by); nav.navigate("places/$id") }
                is Sync.Pulled.Nothing -> play(context, nav, id)
                null -> {
                    play(context, nav, id)
                    // An answer that comes after the book has started never moves it: far from here, it becomes the question next time.
                    background.launch { (asked.await() as? Sync.Pulled.Apart)?.let { store.elsewhere(id, it.audio, it.updated, it.by) } }
                }
            }
        }
    }
    Page {
        item { Text(book.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
        item { tick; Note(listOfNotNull(book.author.takeIf { it.isNotBlank() }, left(book, store.audio(id), store.speed(id))).joinToString("  ·  ")) }
        item {
            tick
            val here = PlaybackService.playingBook() == id && (PlaybackService.playing() || PlaybackService.starting(id))
            Button(onClick = { listen() }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(if (here) R.drawable.ic_headphones else R.drawable.ic_play), null) }) {
                Text(if (opening) "Opening…" else if (here) "Now listening" else if (store.audio(id) > 0) "Continue" else "Listen")
            }
        }
        item {
            tick
            val progress = DownloadService.progress(id)
            when {
                progress != null -> FilledTonalButton(onClick = { DownloadService.cancel(context, id) }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_close), null) },
                    secondaryLabel = { Text("Tap to stop") }) { Text(if (progress < 0) "Waiting to download" else "Downloading ${(progress * 100).toInt()}%") }
                store.downloaded(book) -> FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_check), null) },
                    secondaryLabel = { Text(megabytes(store.audioBytes(id))) }) { Text("On this watch") }
                else -> FilledTonalButton(onClick = { problem = null; DownloadService.start(context, id) }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_download), null) },
                    secondaryLabel = { Text(if (store.audioBytes(id) > 0) "Goes on where it stopped" else "Listen offline") }) { Text("Download") }
            }
        }
        problem?.let { item { Note(it, MaterialTheme.colorScheme.error) } }
        if (book.chapters.size > 1) item { FilledTonalButton(onClick = { nav.navigate("chapters/$id") }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_chapters), null) }) { Text("Chapters") } }
        item {
            tick
            if (store.audioBytes(id) > 0 && DownloadService.progress(id) == null && PlaybackService.playingBook() != id)
                FilledTonalButton(onClick = { store.removeAudio(id); tick++ }, modifier = Modifier.fillMaxWidth(), secondaryLabel = { Text("The book stays on your shelf") }) { Text("Remove download") }
        }
    }
}

/** Two devices left the book far apart: the person says which place is theirs. Leaving the screen chooses nothing, and it asks again. */
@Composable
private fun PlacesScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val book = remember(id) { store.book(id) }
    val there = remember(id) { store.elsewhere(id) }
    if (book == null || there == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    fun where(position: Long) = listOfNotNull(book.chapterAt(position)?.title?.trim()?.takeIf { it.isNotBlank() }, clock(position)).joinToString(", ")
    Page {
        item { ListHeader { Text("Two places") } }
        item { Note("${book.title} was left in two places.") }
        item {
            Button(onClick = { Sync.take(context, id, there.first, there.second); play(context, nav, id) }, modifier = Modifier.fillMaxWidth(),
                secondaryLabel = { Text(listOfNotNull(there.third.takeIf { it.isNotBlank() }, where(there.first)).joinToString(": "), maxLines = 2, overflow = TextOverflow.Ellipsis) }) { Text("Go there") }
        }
        item {
            FilledTonalButton(onClick = { store.clearElsewhere(id); background.launch { runCatching { Sync.push(context, id, force = true) } }; play(context, nav, id) }, modifier = Modifier.fillMaxWidth(),
                secondaryLabel = { Text("This watch: " + where(store.audio(id)), maxLines = 2, overflow = TextOverflow.Ellipsis) }) { Text("Stay here") }
        }
    }
}

/** Nothing is connected to listen through: connect headphones, or say the watch's own speaker will do. */
@Composable
private fun OutputScreen(id: String, nav: NavHostController) {
    val context = LocalContext.current
    Every(1000) { if (headphones(context)) play(context, nav, id) }
    Page {
        item { ListHeader { Text("No headphones") } }
        item { Note("Nothing is connected to listen through.") }
        item {
            Button(onClick = {
                // The watch's own Bluetooth page, which closes by itself once something connects.
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("EXTRA_CONNECTION_ONLY", true).putExtra("EXTRA_CLOSE_ON_CONNECT", true).putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)) }
            }, modifier = Modifier.fillMaxWidth(), icon = { Icon(painterResource(R.drawable.ic_headphones), null) }) { Text("Connect headphones") }
        }
        if (speaker(context)) item { FilledTonalButton(onClick = { speakerChosen = true; play(context, nav, id) }, modifier = Modifier.fillMaxWidth()) { Text("Play on the watch") } }
    }
}

// ---------------------------------------------------------------- now listening

@Composable
private fun PlayerScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val book = remember(id) { store.book(id) }
    if (book == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var now by remember { mutableLongStateOf(store.audio(id)) }
    var playing by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(store.speed(id)) }
    var said by remember { mutableStateOf<String?>(null) }
    var volumeShown by remember { mutableLongStateOf(0L) }
    fun look() {
        val mine = PlaybackService.playingBook() == id
        now = (if (mine) PlaybackService.livePosition().takeIf { it >= 0 } else null) ?: store.audio(id)
        playing = mine && PlaybackService.playing(); starting = !playing && PlaybackService.starting(id)
        speed = store.speed(id)
        PlaybackService.takeFailure()?.let { said = it }
        if (playing) said = null
    }
    Every(500) { look() }
    val scope = rememberCoroutineScope()
    fun send(command: String) { PlaybackService.send(context, command, id); look(); scope.launch { delay(200); look() } }
    val cover by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = store.cover(id).takeIf { it.isFile } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 400) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    // The crown is the volume while the book's own screen shows.
    val focus = remember { FocusRequester() }
    var turned by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScreenScaffold {
        Box(
            Modifier.fillMaxSize().onRotaryScrollEvent { e ->
                turned += e.verticalScrollPixels
                while (kotlin.math.abs(turned) >= 48f) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (turned > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    turned -= if (turned > 0) 48f else -48f
                    volumeShown = android.os.SystemClock.elapsedRealtime()
                }
                true
            }.focusRequester(focus).focusable(),
            contentAlignment = Alignment.Center,
        ) {
            cover?.let { Image(it, null, contentScale = ContentScale.Crop, alpha = .22f, modifier = Modifier.fillMaxSize()) }
            CircularProgressIndicator(progress = { if (book.duration > 0) (now.toFloat() / book.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxSize().padding(2.dp), strokeWidth = 4.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                Text(book.chapterAt(now)?.title?.trim()?.takeIf { it.isNotBlank() } ?: book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                val showVolume = android.os.SystemClock.elapsedRealtime() - volumeShown < 1500
                Text(
                    said ?: if (showVolume) "Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" else left(book, now, speed),
                    style = MaterialTheme.typography.bodySmall, color = if (said != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { send("back") }) { Skip(R.drawable.ic_back15, "15", "Back 15 seconds") }
                    Box(contentAlignment = Alignment.Center) {
                        // Pause shows on the press itself, with a ring turning round it until the sound comes.
                        FilledIconButton(onClick = { send("toggle") }, modifier = Modifier.size(58.dp)) { Icon(painterResource(if (playing || starting) R.drawable.ic_pause else R.drawable.ic_play), if (playing || starting) "Pause audiobook" else "Play audiobook") }
                        if (starting) Ring(Modifier.size(66.dp))
                    }
                    IconButton(onClick = { send("forward") }) { Skip(R.drawable.ic_forward30, "30", "Forward 30 seconds") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CompactButton(onClick = { nav.navigate("speed/$id") }, colors = ButtonDefaults.filledTonalButtonColors(), label = { Text(speedText(speed)) })
                    if (book.chapters.size > 1) IconButton(onClick = { nav.navigate("chapters/$id") }, modifier = Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_chapters), "Chapters") }
                    IconButton(onClick = { nav.navigate("volume") }, modifier = Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_volume_up), "Volume") }
                }
            }
        }
    }
}

@Composable
private fun ChaptersScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val book = remember(id) { store.book(id) }
    if (book == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val position = remember { (if (PlaybackService.playingBook() == id) PlaybackService.livePosition().takeIf { it >= 0 } else null) ?: store.audio(id) }
    val current = book.chapters.indexOfLast { it.start <= position }
    Page(first = (current + 1).coerceAtLeast(0)) {
        item { ListHeader { Text("Chapters") } }
        for ((i, c) in book.chapters.withIndex()) item(key = i) {
            val go = {
                nav.popBackStack()
                play(context, nav, id, c.start)
            }
            val label: @Composable () -> Unit = { Text(c.title.trim().ifBlank { "Chapter ${i + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (i == current) Button(onClick = go, modifier = Modifier.fillMaxWidth(), secondaryLabel = { Text(clock(c.start)) }) { label() }
            else FilledTonalButton(onClick = go, modifier = Modifier.fillMaxWidth(), secondaryLabel = { Text(clock(c.start)) }) { label() }
        }
    }
}

/** The book's own speed, kept on the server so the phone and the web play it at the same speed. */
@Composable
private fun SpeedScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val speeds = listOf(.75f, .8f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)
    val now = store.speed(id)
    Page(first = speeds.indexOfFirst { it == now }.coerceAtLeast(0) + 1) {
        item { ListHeader { Text("Speed") } }
        for (v in speeds) item(key = v) {
            val choose = {
                store.speed(id, v, local = true)
                if (PlaybackService.playingBook() == id) PlaybackService.send(context, "speed", id)
                background.launch { runCatching { Sync.settle(context) } }
                nav.popBackStack(); Unit
            }
            if (v == now) Button(onClick = choose, modifier = Modifier.fillMaxWidth()) { Text(speedText(v)) }
            else FilledTonalButton(onClick = choose, modifier = Modifier.fillMaxWidth()) { Text(speedText(v)) }
        }
    }
}

@Composable
private fun VolumeScreen() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    Every(500) { level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
    fun step(direction: Int) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0); level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
    ScreenScaffold {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { level.toFloat() / max }, modifier = Modifier.fillMaxSize().padding(2.dp), strokeWidth = 4.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Volume", style = MaterialTheme.typography.titleSmall)
                Text("$level of $max", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledIconButton(onClick = { step(AudioManager.ADJUST_LOWER) }) { Icon(painterResource(R.drawable.ic_volume_down), "Quieter") }
                    FilledIconButton(onClick = { step(AudioManager.ADJUST_RAISE) }) { Icon(painterResource(R.drawable.ic_volume_up), "Louder") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(store: Store, nav: NavHostController) {
    val context = LocalContext.current
    val books = remember { store.books() }
    val bytes = remember { books.sumOf { store.audioBytes(it.id) } }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    Page {
        item { ListHeader { Text("Settings") } }
        item { Note("Signed in as ${store.user.ifBlank { "you" }} on ${host(store.serverUrl)}") }
        item { Note(if (bytes > 0) "${megabytes(bytes)} of audiobooks on this watch" else "No audiobooks downloaded to this watch") }
        item { FilledTonalButton(onClick = { nav.navigate("signout") }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") } }
        item { Note("Unison $version") }
    }
}

@Composable
private fun SignOutScreen(store: Store, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    Page {
        item { ListHeader { Text("Sign out?") } }
        item { Note("The audiobooks downloaded to this watch are removed. Your shelf and your places stay on your server.") }
        item {
            Button(onClick = {
                if (!leaving) {
                    leaving = true
                    scope.launch {
                        val client = store.client(); val key = store.token.take(8)
                        PlaybackService.forget(context)
                        DownloadService.cancelAll()
                        // What the watch still owes goes up first, then the server forgets this watch's key: eight seconds for both,
                        // and with no connection the watch signs out all the same (the key can be removed from the device list).
                        val last = background.async { runCatching { Sync.settle(context) }; runCatching { client?.signOutDevice(key) } }
                        withTimeoutOrNull(8000) { last.await() }
                        store.signOut()
                        nav.navigate("link") { popUpTo("home") { inclusive = true } }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (leaving) "Signing out…" else "Sign out") }
        }
        item { FilledTonalButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
    }
}
