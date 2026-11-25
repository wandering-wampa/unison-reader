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
import android.os.SystemClock
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.Icon
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

// The Unison look at night, which is the only light a watch has: black glass, cream text, deep-blue surfaces, teal for
// what is live and for the one thing to press. Titles are set in Literata, as they are on the phone and the web.
private val Teal = Color(0xFF00B7BC)
private val Cream = Color(0xFFF3EEDF)
private val Muted = Color(0xFFA6B4BE)
private val DeepBlue = Color(0xFF062F47)
private val Surface = Color(0xFF0F2D40)
private val SurfaceHigh = Color(0xFF173B4D)
private val Danger = Color(0xFFF0A090)
private val UnisonColors = ColorScheme(
    primary = Teal, primaryDim = Color(0xFF0A8F93), primaryContainer = Color(0xFF125657), onPrimary = DeepBlue, onPrimaryContainer = Cream,
    secondary = Cream, secondaryDim = Muted, secondaryContainer = SurfaceHigh, onSecondary = DeepBlue, onSecondaryContainer = Cream,
    surfaceContainerLow = Color(0xFF0B2231), surfaceContainer = Surface, surfaceContainerHigh = SurfaceHigh,
    onSurface = Cream, onSurfaceVariant = Muted, background = Color.Black, onBackground = Cream,
)
private val Literata = FontFamily(Font(R.font.literata_regular, FontWeight.Normal), Font(R.font.literata_semibold, FontWeight.SemiBold))

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
                composable("speed/{id}") { SpeedScreen(store, it.arguments?.getString("id").orEmpty()) }
                composable("volume") { VolumeScreen() }
                composable("settings") { SettingsScreen(store, nav) }
                composable("signout") { SignOutScreen(store, nav) }
            }
        }
    }
}

// ---------------------------------------------------------------- the pieces every screen is made of

/** Runs `block` now and every `ms` while the screen is in view. */
@Composable
private fun Every(ms: Long, block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    LaunchedEffect(owner, ms) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { current(); delay(ms) } } }
}

/** A list page: the scrolling column of a watch, starting at its heading under the time. */
@Composable
private fun Page(first: Int = 0, content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = first)
    ScreenScaffold(scrollState = state) { padding ->
        ScalingLazyColumn(state = state, contentPadding = padding, autoCentering = null, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** A page's heading: small, spaced capitals, as on the phone. */
@Composable
private fun Heading(text: String, color: Color = Muted) =
    Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 0.14.em, fontWeight = FontWeight.Medium, color = color, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp))

@Composable
private fun Note(text: String, color: Color = Muted) =
    Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))

private enum class Tone { Plain, Primary, Quiet }

/**
 * A row to press: an icon or a cover, a line of words and a smaller one under it, in one rounded shape. The one thing
 * a screen is for is `Primary` (teal); the rest are `Plain`; `Quiet` has no shape at all. `progress` draws a thin
 * line along the bottom (a download on its way, how far through a book).
 */
@Composable
private fun Tile(
    label: String, modifier: Modifier = Modifier, sub: String? = null, icon: Int? = null, cover: ImageBitmap? = null, trailing: String? = null,
    tone: Tone = Tone.Plain, serif: Boolean = false, progress: Float? = null, enabled: Boolean = true, lines: Int = 1, onClick: (() -> Unit)? = null,
) {
    val ink = when (tone) { Tone.Primary -> DeepBlue; else -> Cream }
    val soft = when (tone) { Tone.Primary -> DeepBlue.copy(alpha = .78f); else -> Muted }
    val fill = when (tone) { Tone.Primary -> Teal; Tone.Plain -> Surface; Tone.Quiet -> Color.Transparent }
    Box(
        modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(if (enabled) fill else fill.copy(alpha = .55f))
            .then(if (onClick != null && enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = if (cover != null) 8.dp else 14.dp, end = 14.dp, top = 7.dp, bottom = 7.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (cover != null) { Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(7.dp))); Spacer(Modifier.width(9.dp)) }
            else if (icon != null) { Icon(painterResource(icon), null, tint = if (tone == Tone.Primary) DeepBlue else Teal, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(label, color = ink, fontSize = 14.sp, lineHeight = 17.sp, fontFamily = if (serif) Literata else null, fontWeight = if (tone == Tone.Primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = lines, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, color = soft, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); Text(trailing, color = soft, fontSize = 11.sp, maxLines = 1) }
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(if (tone == Tone.Primary) DeepBlue else Teal))
    }
}

/** A round button with one icon (or a word or two) in it. */
@Composable
private fun Round(label: String, size: Dp = 44.dp, icon: Int? = null, text: String? = null, filled: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).background(if (filled) Teal else Surface).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (icon != null) Icon(painterResource(icon), null, tint = if (filled) DeepBlue else Cream, modifier = Modifier.size(size * .46f))
        else if (text != null) Text(text, color = if (filled) DeepBlue else Cream, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }

/** A ring turning round something that was pressed and is on its way. */
@Composable
private fun Turning(modifier: Modifier, color: Color = Cream) {
    val turn by rememberInfiniteTransition(label = "ring").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        drawArc(color, turn, 250f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** How far through something, as a ring: a faint whole circle and the part that is done, from the top. */
@Composable
private fun Progress(fraction: Float, modifier: Modifier, stroke: Dp = 3.dp, color: Color = Teal, track: Color = SurfaceHigh) = Canvas(modifier) {
    val w = stroke.toPx()
    val box = Size(size.width - w, size.height - w)
    drawArc(track, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = box, style = Stroke(w))
    drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft = Offset(w / 2, w / 2), size = box, style = Stroke(w, cap = StrokeCap.Round))
}

/** A book's cover from the watch's own copy, decoded off the main thread at about the size it is shown; null while it loads or when there is none. */
@Composable
private fun cover(store: Store, id: String, px: Int): ImageBitmap? {
    val loaded by produceState<ImageBitmap?>(Covers.cached(id, px), id, px) {
        if (value == null) value = withContext(Dispatchers.IO) { Covers.load(store, id, px) }
    }
    return loaded
}

private object Covers {
    private val cache = HashMap<String, ImageBitmap>()
    fun cached(id: String, px: Int): ImageBitmap? = synchronized(cache) { cache["$id@$px"] }
    fun load(store: Store, id: String, px: Int): ImageBitmap? = runCatching {
        val file = store.cover(id).takeIf { it.isFile } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { synchronized(cache) { if (cache.size > 24) cache.clear(); cache["$id@$px"] = it } }
    }.getOrNull()
}

private fun clock(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
private fun span(ms: Long): String { val m = (ms.coerceAtLeast(0) + 30_000) / 60_000; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
private fun left(b: WatchBook, position: Long, speed: Float) = if (position >= b.duration - 1000) "Finished" else span(((b.duration - position) / speed).toLong()) + " left"
private fun megabytes(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${(bytes + 500_000) / 1_000_000} MB"
private fun speedText(v: Float) = (if (v == v.toInt().toFloat()) "${v.toInt()}.0" else v.toString()) + "×"
private fun host(url: String) = url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
private fun fraction(b: WatchBook, position: Long) = if (b.duration > 0) (position.toFloat() / b.duration).coerceIn(0f, 1f) else 0f

/** A chapter's name as a person would say it: many audiobooks call their chapters "031" or leave them blank. */
private fun chapterName(b: WatchBook, index: Int): String {
    val raw = b.chapters.getOrNull(index)?.title?.trim().orEmpty()
    return when { raw.isBlank() -> "Chapter ${index + 1}"; raw.all { it.isDigit() } -> "Chapter ${raw.toLongOrNull() ?: raw}"; else -> raw }
}
private fun chapterIndex(b: WatchBook, position: Long) = b.chapters.indexOfLast { it.start <= position }
private fun chapterNow(b: WatchBook, position: Long): String? = chapterIndex(b, position).takeIf { it >= 0 }?.let { chapterName(b, it) }

private val HEADPHONES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
)
private fun outputs(context: Context) = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
private fun headphones(context: Context) = outputs(context).any { it.type in HEADPHONES }
private fun speaker(context: Context) = outputs(context).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

/** Starts the book, by way of the headphones question when nothing is connected to hear it through. The questions on the way leave the stack, so Back from the player is where listening was asked for. */
private fun play(context: Context, nav: NavHostController, id: String, position: Long = -1) {
    val here = nav.currentDestination?.route
    if (!headphones(context) && !speakerChosen) {
        if (here == "places/{id}") nav.popBackStack()
        nav.navigate("output/$id")
        return
    }
    PlaybackService.send(context, "play", id, position)
    if (here == "output/{id}" || here == "places/{id}") nav.popBackStack()
    if (here != "player/{id}") nav.navigate("player/$id")
}

/**
 * The way into listening, from the shelf or a book's page. The newest place comes first, from whichever device left it:
 * the server gets two and a half seconds, then the book opens as it is here. A place far from this watch's becomes the
 * question (Two places); an answer that comes after the book has started never moves it.
 */
private class Listening(private val context: Context, private val store: Store, private val nav: NavHostController, private val scope: CoroutineScope) {
    var opening by mutableStateOf<String?>(null)
        private set

    fun start(id: String) {
        if (opening != null) return
        if (PlaybackService.playingBook() == id && (PlaybackService.playing() || PlaybackService.starting(id))) { nav.navigate("player/$id"); return }
        if (store.elsewhere(id) != null) { nav.navigate("places/$id"); return }
        opening = id
        scope.launch {
            val asked = background.async { Sync.pull(context, id) }
            val pulled = withTimeoutOrNull(2500) { asked.await() }
            opening = null
            when (pulled) {
                is Sync.Pulled.Newer -> { Sync.take(context, id, pulled.audio, pulled.passage, pulled.updated); play(context, nav, id) }
                is Sync.Pulled.Apart -> { store.elsewhere(id, pulled.audio, pulled.passage, pulled.updated, pulled.by); nav.navigate("places/$id") }
                is Sync.Pulled.Nothing -> play(context, nav, id)
                null -> {
                    play(context, nav, id)
                    background.launch { (asked.await() as? Sync.Pulled.Apart)?.let { store.elsewhere(id, it.audio, it.passage, it.updated, it.by) } }
                }
            }
        }
    }
}

@Composable
private fun rememberListening(store: Store, nav: NavHostController): Listening {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(nav) { Listening(context, store, nav, scope) }
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
            phase = Link.Failed(when { m.startsWith("Server answered 404") || m.startsWith("Server answered 401") -> "This server needs an update before a watch can sign in."; m.startsWith("Server answered") && ": " in m -> m.substringAfter(": "); else -> "Could not reach ${host(server)}." })
            return@LaunchedEffect
        }
        phase = Link.Code(started.code)
        val until = SystemClock.elapsedRealtime() + 11 * 60_000
        while (SystemClock.elapsedRealtime() < until) {
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
        item { Heading("Wear OS sign-in") }
        when (val p = phase) {
            is Link.Asking -> item { Note("Asking ${host(server)} for a code…") }
            is Link.NoServer -> item { Note("Which server is your library on?") }
            is Link.Code -> {
                item { Text(p.code.take(3) + " " + p.code.drop(3), fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em, color = Teal, modifier = Modifier.semantics { contentDescription = "Code " + p.code }) }
                item { Note("In Unison on your phone, open Settings, then Wear OS sign-in, and type this code.", Cream) }
            }
            is Link.Ended -> { item { Note("That code has ended.") }; item { Tile("New code", tone = Tone.Primary, icon = R.drawable.ic_refresh) { attempt++ } } }
            is Link.Failed -> { item { Note(p.why) }; item { Tile("Try again", tone = Tone.Primary, icon = R.drawable.ic_refresh) { attempt++ } } }
        }
        item { Tile("Server", sub = host(server).ifBlank { "Not set" }, onClick = changeServer) }
    }
}

/** The server's address, typed once on a watch built without one. */
@Composable
private fun ServerScreen(store: Store, done: () -> Unit) {
    var text by remember { mutableStateOf(host(store.serverUrl)) }
    Page {
        item { Heading("Server address") }
        item {
            BasicTextField(
                value = text, onValueChange = { text = it.filter { c -> !c.isWhitespace() } }, singleLine = true,
                textStyle = TextStyle(color = Cream, fontSize = 14.sp, textAlign = TextAlign.Center),
                // The keyboard's own tick only closes the keyboard: what was typed reaches this field after that tick is
                // handled, so saving there would keep the old address. The button below saves.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 14.dp).semantics { contentDescription = "Server address" },
            )
        }
        item { Note("Like unison.example.org. Tap the field to type.") }
        item { Tile("Use this server", tone = Tone.Primary, icon = R.drawable.ic_check, enabled = text.isNotBlank()) { store.serverUrl = text.trim(); done() } }
    }
}

// ---------------------------------------------------------------- the shelf

@Composable
private fun HomeScreen(store: Store, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listening = rememberListening(store, nav)
    fun listed(): List<WatchBook> {
        val shelf = store.shelf
        // The book last listened to here leads; then the shelf's own order (what is being read first); books kept on the watch after leaving the shelf last.
        return store.books().sortedWith(compareByDescending<WatchBook> { store.listened(it.id) }.thenBy { b -> shelf.indexOf(b.id).let { if (it < 0) Int.MAX_VALUE else it } })
    }
    var books by remember { mutableStateOf(listed()) }
    var refreshing by remember { mutableStateOf(false) }
    var reached by remember { mutableStateOf(true) }
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
    Every(1000) { tick++ }
    // The media notification is how the watch shows its own controls for the book; it needs the person's yes once.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    // The book in the player leads the page, whatever was listened to last.
    val current = PlaybackService.playingBook()?.let { id -> books.firstOrNull { it.id == id } } ?: books.firstOrNull()
    Page {
        item { Heading("Unison") }
        if (current != null) item(key = "hero") { tick; Hero(store, current, opening = listening.opening == current.id) { listening.start(current.id) } }
        if (books.isEmpty()) item { Note(if (refreshing) "Fetching your shelf…" else if (!reached) "Could not reach your server." else "No audiobooks on your shelf yet. Put one on your shelf in the phone app or on the web.") }
        val rest = books.filter { it.id != current?.id }
        if (rest.isNotEmpty()) item { Heading("On your shelf") }
        for (b in rest) item(key = b.id) {
            tick
            val line = listOfNotNull(left(b, store.audio(b.id), store.speed(b.id)), if (store.downloaded(b)) "on watch" else DownloadService.progress(b.id)?.let { "downloading" }).joinToString("  ·  ")
            Tile(b.title, sub = line, cover = cover(store, b.id, 96), icon = R.drawable.ic_headphones, serif = true, progress = fraction(b, store.audio(b.id)).takeIf { it > 0f }, lines = 2) { nav.navigate("book/${b.id}") }
        }
        if (books.isNotEmpty() && !reached && !refreshing) item { Note("No connection. Books on the watch still play.") }
        item {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Round("Refresh", icon = R.drawable.ic_refresh) { refresh() }
                    if (refreshing) Turning(Modifier.size(48.dp), Teal)
                }
                Round("Settings", icon = R.drawable.ic_settings) { nav.navigate("settings") }
            }
        }
    }
}

/** The book to go on with: its cover behind its name, where it is, and one press to listen. A second, smaller press opens the book's page. */
@Composable
private fun Hero(store: Store, b: WatchBook, opening: Boolean, listen: () -> Unit) {
    val position = store.audio(b.id)
    val here = PlaybackService.playingBook() == b.id
    val playing = here && PlaybackService.playing()
    val art = cover(store, b.id, 300)
    Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(26.dp)).background(Surface).clickable(role = Role.Button, onClick = listen).semantics { contentDescription = if (playing) "Now listening" else if (position > 0) "Continue ${b.title}" else "Listen to ${b.title}" }) {
        if (art != null) {
            Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20B2231), Color(0xB30B2231), Color(0x660B2231)))))
        }
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (opening) "Opening" else if (playing) "Now listening" else if (here) "Paused" else if (position > 0) "Continue" else "Listen").uppercase(), color = Teal, fontSize = 9.sp, letterSpacing = 0.14.em, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(b.title, color = Cream, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(chapterNow(b, position).takeIf { position > 0 }, left(b, position, store.speed(b.id))).joinToString("  ·  "), color = Cream.copy(alpha = .78f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) { Icon(painterResource(if (playing) R.drawable.ic_headphones else R.drawable.ic_play), null, tint = DeepBlue, modifier = Modifier.size(18.dp)) }
                if (opening) Turning(Modifier.size(44.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction(b, position)).height(3.dp).background(Teal))
    }
}

// ---------------------------------------------------------------- one book

@Composable
private fun BookScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val listening = rememberListening(store, nav)
    val book = remember(id) { store.book(id) }
    if (book == null) { Page { item { Note("This book is no longer on the watch.") } }; return }
    var tick by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    Every(500) { tick++; DownloadService.takeProblem(id)?.let { problem = it } }
    // The speed may have been changed on another device; it is here by the time Listen is pressed.
    LaunchedEffect(id) { withContext(Dispatchers.IO) { runCatching { Sync.pullSpeed(context, id) } } }
    val art = cover(store, id, 220)
    Page {
        if (art != null) item { Image(art, "Cover of ${book.title}", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))) }
        item { Text(book.title, color = Cream, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) }
        if (book.author.isNotBlank()) item { Note(book.author) }
        item {
            tick
            val position = store.audio(id)
            val here = PlaybackService.playingBook() == id && (PlaybackService.playing() || PlaybackService.starting(id))
            val opening = listening.opening == id
            Tile(
                if (opening) "Opening…" else if (here) "Now listening" else if (position > 0) "Continue" else "Listen",
                sub = listOfNotNull(chapterNow(book, position).takeIf { position > 0 }, left(book, position, store.speed(id))).joinToString("  ·  "),
                icon = if (here) R.drawable.ic_headphones else R.drawable.ic_play, tone = Tone.Primary, progress = fraction(book, position).takeIf { it > 0f },
            ) { listening.start(id) }
        }
        item {
            tick
            val progress = DownloadService.progress(id)
            when {
                progress != null -> Tile(if (progress < 0) "Waiting to download" else "Downloading ${(progress * 100).toInt()}%", sub = "Tap to stop", icon = R.drawable.ic_close, progress = progress.coerceAtLeast(0f)) { DownloadService.cancel(context, id) }
                store.downloaded(book) -> Tile("On this watch", sub = megabytes(store.audioBytes(id)) + "  ·  plays offline", icon = R.drawable.ic_check)
                else -> Tile("Download", sub = if (store.audioBytes(id) > 0) "Goes on where it stopped" else "Listen offline", icon = R.drawable.ic_download) { problem = null; DownloadService.start(context, id) }
            }
        }
        problem?.let { item { Note(it, Danger) } }
        if (book.chapters.size > 1) item { tick; Tile("Chapters", sub = "${book.chapters.size} in this book", icon = R.drawable.ic_chapters) { nav.navigate("chapters/$id") } }
        item {
            tick
            if (store.audioBytes(id) > 0 && DownloadService.progress(id) == null && PlaybackService.playingBook() != id)
                Tile("Remove download", sub = "The book stays on your shelf", icon = R.drawable.ic_trash, tone = Tone.Quiet) { store.removeAudio(id); tick++ }
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
    fun where(position: Long) = listOfNotNull(chapterNow(book, position), clock(position)).joinToString(", ")
    Page {
        item { Heading("Two places") }
        item { Note("${book.title} was left in two places.", Cream) }
        item { Tile("Go there", sub = where(there.audio), trailing = there.by.takeIf { it.isNotBlank() && it.length <= 12 }, tone = Tone.Primary) { Sync.take(context, id, there.audio, there.passage, there.updated); play(context, nav, id) } }
        if (there.by.length > 12) item { Note("Left there by ${there.by}") }
        item { Tile("Stay here", sub = where(store.audio(id)), trailing = "this watch") { store.clearElsewhere(id); background.launch { runCatching { Sync.push(context, id, force = true) } }; play(context, nav, id) } }
    }
}

/** Nothing is connected to listen through: connect headphones, or say the watch's own speaker will do. */
@Composable
private fun OutputScreen(id: String, nav: NavHostController) {
    val context = LocalContext.current
    Every(1000) { if (headphones(context)) play(context, nav, id) }
    Page {
        item { Heading("No headphones") }
        item { Note("Nothing is connected to listen through.", Cream) }
        item {
            Tile("Connect headphones", icon = R.drawable.ic_headphones, tone = Tone.Primary) {
                // The watch's own Bluetooth page, which closes by itself once something connects.
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("EXTRA_CONNECTION_ONLY", true).putExtra("EXTRA_CLOSE_ON_CONNECT", true).putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)) }
            }
        }
        if (speaker(context)) item { Tile("Play on the watch", icon = R.drawable.ic_volume) { speakerChosen = true; play(context, nav, id) } }
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
    val art = cover(store, id, 420)
    // The crown is the volume while the book's own screen shows.
    val focus = remember { FocusRequester() }
    var turned by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // How far through the chapter, for the ring round the play button; through the book when it has no chapters.
    val index = chapterIndex(book, now)
    val chapterStart = book.chapters.getOrNull(index)?.start ?: 0L
    val chapterEnd = book.chapters.getOrNull(index + 1)?.start ?: book.duration
    val within = if (chapterEnd > chapterStart) ((now - chapterStart).toFloat() / (chapterEnd - chapterStart)).coerceIn(0f, 1f) else 0f
    ScreenScaffold {
        Box(
            Modifier.fillMaxSize().onRotaryScrollEvent { e ->
                turned += e.verticalScrollPixels
                while (kotlin.math.abs(turned) >= 48f) {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (turned > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    turned -= if (turned > 0) 48f else -48f
                    volumeShown = SystemClock.elapsedRealtime()
                }
                true
            }.focusRequester(focus).focusable(),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xE6000000), Color(0xC7061722), Color(0xF2000000)))))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(if (index >= 0) chapterName(book, index) else book.title, color = Cream, fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                val showVolume = SystemClock.elapsedRealtime() - volumeShown < 1500
                Text(
                    said ?: if (showVolume) "Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" else left(book, now, speed),
                    color = if (said != null) Danger else Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Skip(R.drawable.ic_back15, "15", "Back 15 seconds") { send("back") }
                    Box(contentAlignment = Alignment.Center) {
                        // Pause shows on the press itself; the ring is the chapter, and turns while the sound is on its way.
                        if (starting) Turning(Modifier.size(70.dp)) else Progress(within, Modifier.size(70.dp))
                        Box(Modifier.size(56.dp).clip(CircleShape).background(Teal).clickable(role = Role.Button) { send("toggle") }.semantics { contentDescription = if (playing || starting) "Pause audiobook" else "Play audiobook" }, contentAlignment = Alignment.Center) {
                            Icon(painterResource(if (playing || starting) R.drawable.ic_pause else R.drawable.ic_play), null, tint = DeepBlue, modifier = Modifier.size(24.dp))
                        }
                    }
                    Skip(R.drawable.ic_forward30, "30", "Forward 30 seconds") { send("forward") }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (book.chapters.size > 1) Round("Chapters", size = 38.dp, icon = R.drawable.ic_chapters) { nav.navigate("chapters/$id") }
                    Round("Speed", size = 38.dp, text = speedText(speed)) { nav.navigate("speed/$id") }
                    Round("Volume", size = 38.dp, icon = R.drawable.ic_volume) { nav.navigate("volume") }
                }
            }
        }
    }
}

/** A skip button: the turning arrow with its seconds inside. */
@Composable
private fun Skip(icon: Int, seconds: String, label: String, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = Cream, modifier = Modifier.size(34.dp))
        Text(seconds, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Cream, modifier = Modifier.padding(top = 3.dp))
    }

@Composable
private fun ChaptersScreen(store: Store, id: String, nav: NavHostController) {
    val context = LocalContext.current
    val book = remember(id) { store.book(id) }
    if (book == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val position = remember { (if (PlaybackService.playingBook() == id) PlaybackService.livePosition().takeIf { it >= 0 } else null) ?: store.audio(id) }
    val current = chapterIndex(book, position)
    Page(first = (current + 1).coerceAtLeast(0)) {
        item { Heading("Chapters") }
        for ((i, c) in book.chapters.withIndex()) item(key = i) {
            // One line each: the chapter and when it starts; the one being listened to is marked.
            Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (i == current) Color(0xFF125657) else Surface).clickable(role = Role.Button) { nav.popBackStack(); play(context, nav, id, c.start) }) {
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i == current) { Box(Modifier.size(6.dp).clip(CircleShape).background(Teal)); Spacer(Modifier.width(8.dp)) }
                    Text(chapterName(book, i), color = Cream, fontSize = 13.sp, fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(clock(c.start), color = if (i == current) Cream.copy(alpha = .8f) else Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

private val SPEEDS = listOf(.75f, .8f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)

/** The book's own speed, kept on the server so the phone and the web play it at the same speed: one step slower or faster at a press, or with the crown. */
@Composable
private fun SpeedScreen(store: Store, id: String) {
    val context = LocalContext.current
    var speed by remember { mutableFloatStateOf(store.speed(id)) }
    fun step(by: Int) {
        val at = SPEEDS.indexOfFirst { it >= speed - .001f }.let { if (it < 0) SPEEDS.lastIndex else it }
        val next = SPEEDS[(at + by).coerceIn(0, SPEEDS.lastIndex)]
        if (next == speed) return
        speed = next
        store.speed(id, next, local = true)
        if (PlaybackService.playingBook() == id) PlaybackService.send(context, "speed", id)
    }
    // Sent once, when the screen is left, rather than at every step on the way to the speed wanted.
    DisposableEffect(Unit) { onDispose { background.launch { runCatching { Sync.settle(context) } } } }
    val focus = remember { FocusRequester() }
    var turned by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScreenScaffold {
        Box(Modifier.fillMaxSize().onRotaryScrollEvent { e ->
            turned += e.verticalScrollPixels
            while (kotlin.math.abs(turned) >= 64f) { step(if (turned > 0) 1 else -1); turned -= if (turned > 0) 64f else -64f }
            true
        }.focusRequester(focus).focusable(), contentAlignment = Alignment.Center) {
            Progress((SPEEDS.indexOf(speed).coerceAtLeast(0)).toFloat() / SPEEDS.lastIndex, Modifier.fillMaxSize().padding(5.dp), stroke = 4.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Heading("Speed")
                Text(speedText(speed), color = Cream, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { contentDescription = "Speed " + speedText(speed) })
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Round("Slower", icon = R.drawable.ic_minus) { step(-1) }
                    Round("Faster", icon = R.drawable.ic_plus) { step(1) }
                }
            }
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
    val focus = remember { FocusRequester() }
    var turned by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScreenScaffold {
        Box(Modifier.fillMaxSize().onRotaryScrollEvent { e ->
            turned += e.verticalScrollPixels
            while (kotlin.math.abs(turned) >= 48f) { step(if (turned > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER); turned -= if (turned > 0) 48f else -48f }
            true
        }.focusRequester(focus).focusable(), contentAlignment = Alignment.Center) {
            Progress(level.toFloat() / max, Modifier.fillMaxSize().padding(5.dp), stroke = 4.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Heading("Volume")
                Text("$level", color = Cream, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { contentDescription = "Volume $level of $max" })
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Round("Quieter", icon = R.drawable.ic_minus) { step(AudioManager.ADJUST_LOWER) }
                    Round("Louder", icon = R.drawable.ic_plus) { step(AudioManager.ADJUST_RAISE) }
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
        item { Heading("Settings") }
        item { Tile(store.user.ifBlank { "Signed in" }, sub = host(store.serverUrl)) }
        item { Tile("On this watch", sub = if (bytes > 0) megabytes(bytes) + " of audiobooks" else "No audiobooks downloaded") }
        item { Tile("Sign out", icon = R.drawable.ic_close) { nav.navigate("signout") } }
        item { Note("Unison $version") }
    }
}

@Composable
private fun SignOutScreen(store: Store, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    Page {
        item { Heading("Sign out?") }
        item { Note("The audiobooks downloaded to this watch are removed. Your shelf and your places stay on your server.", Cream) }
        item {
            Tile(if (leaving) "Signing out…" else "Sign out", tone = Tone.Primary) {
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
            }
        }
        item { Tile("Cancel") { nav.popBackStack() } }
    }
}
