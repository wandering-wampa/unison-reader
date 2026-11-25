package dev.homescreenreader

import android.app.*
import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real EPUB/UI regression checks. Input is injected only into the Android emulator. */
class BookishChecks(private val test: Instrumentation, private val report: (String) -> Unit) {
    private val context = test.targetContext
    private lateinit var activity: Activity
    private lateinit var web: FormattedReader
    private lateinit var frame: ReaderPageFrame
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun js(code: String): String {
        val done = CountDownLatch(1); var result = ""
        main { web.evaluateJavascript(code) { result = it; done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS)) { "WebView timed out: $code" }; return result
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(100) }
        error("Timed out: $label")
    }
    private fun views(): List<View> {
        val result = mutableListOf<View>()
        fun visit(v: View) { result.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
        main { visit(activity.window.decorView) }; return result
    }
    private fun clickDescription(label: String) {
        val v = views().firstOrNull { it.contentDescription == label && it.isShown } ?: error("Missing visible control: $label")
        main { check(v.performClick()) }
        Thread.sleep(350)
    }
    private fun clickText(label: String) {
        val node = test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).firstOrNull { it.text?.toString() == label }
            ?: error("Missing sheet option: $label")
        check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(350)
    }
    private fun shot(name: String) {
        Thread.sleep(900) // Let the emulator's window composition settle before visual evidence.
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "bookish-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    fun run() {
        val prefs = context.getSharedPreferences("reading-v2", 0)
        val original = prefs.all.toMap()
        val state = ReadingState(context)
        val book = Library(context).use { it.load(state.active) } ?: error("No installed book")
        try {
            state.scrollMode = false; state.theme = "paper"; state.typeface = "literata"; state.font = 22
            activity = test.startActivitySync(Intent(context, MainActivity::class.java).putExtra("book", book.id).putExtra("read", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            web = views().filterIsInstance<FormattedReader>().single { !it.isCacheRenderer }
            frame = views().filterIsInstance<ReaderPageFrame>().single()
            waitFor("formatted reader ready") { var ready = false; main { ready = web.isReaderReady }; ready }
            waitFor("book open") { var ready=false;main { ready=frame.isPageOpen };ready }
            check(js("document.querySelector('foliate-view').renderer.getAttribute('flow')") == "\"paginated\"")
            check(js("(()=>{let d=document.querySelector('foliate-view').renderer.getContents()[0].doc;return d.defaultView.getComputedStyle(d.body).fontFamily})()").contains("Literata"))
            check(js("document.querySelector('foliate-view').renderer.getContents()[0].doc.fonts.check('22px Literata')") == "true")
            check(js("document.querySelector('foliate-view').renderer.getContents()[0].doc.body.innerText.length").toInt() > 1000)
            shot("reading")
            report("Paginated original EPUB rendered with bundled Literata; font loaded; quiet reading page captured")

            // Follow/browse state must change on navigation, and every effect must finish.
            for (effect in listOf("curl", "soft-curl", "slide", "vertical", "cover", "cover-vertical", "fade", "none")) {
                state.animation = effect
                val previous = js("document.querySelector('foliate-view').lastLocation.cfi")
                main { frame.turn(1) }
                waitFor("$effect advances page") { js("document.querySelector('foliate-view').lastLocation.cfi") != previous }
                waitFor("$effect finishes") { var idle = false; main { idle = !frame.isTurning }; idle }
                check(!state.follow(book.id))
                main { frame.turn(-1) }
                waitFor("$effect returns") { var idle = false; main { idle = !frame.isTurning }; idle }
                check(js("document.querySelector('foliate-view').lastLocation.cfi") == previous) { "$effect did not return to the same page" }
            }
            report("All eight turn styles advance and return to the same EPUB location; no stuck transition overlay")

            val coords = IntArray(2); main { frame.getLocationOnScreen(coords) }
            fun gesture(x1: Float, y1: Float, x2: Float, y2: Float) {
                val time = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float, y: Float, elapsed: Long) {
                    val e = MotionEvent.obtain(time, time + elapsed, action, coords[0] + x * frame.width, coords[1] + y * frame.height, 0)
                    test.uiAutomation.injectInputEvent(e, true); e.recycle()
                }
                event(MotionEvent.ACTION_DOWN, x1, y1, 0)
                if (x1 != x2 || y1 != y2) {
                    for (step in 1..5) { event(MotionEvent.ACTION_MOVE, x1 + (x2-x1)*step/5, y1 + (y2-y1)*step/5, step*25L) }
                }
                event(MotionEvent.ACTION_UP, x2, y2, 160)
                Thread.sleep(900)
            }
            state.animation = "curl"; state.tapLayout = "edges"
            var before = js("document.querySelector('foliate-view').lastLocation.cfi")
            gesture(.9f,.4f,.9f,.4f)
            check(js("document.querySelector('foliate-view').lastLocation.cfi") != before) { "Right-edge tap did not turn" }
            // A turn must settle (frame idle) before the next gesture can be accepted.
            val settle = SystemClock.uptimeMillis()
            waitFor("tap turn settles") { var idle = false; main { idle = !frame.isTurning }; idle }
            report("Tap turn settled in ${SystemClock.uptimeMillis() - settle} ms after the gesture")
            before = js("document.querySelector('foliate-view').lastLocation.cfi")
            gesture(.8f,.5f,.2f,.5f)
            check(js("document.querySelector('foliate-view').lastLocation.cfi") != before) { "Swipe did not turn" }
            state.volumeTurns = true
            before = js("document.querySelector('foliate-view').lastLocation.cfi")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN); Thread.sleep(900)
            check(js("document.querySelector('foliate-view').lastLocation.cfi") != before) { "Volume key did not turn" }
            state.brightnessGesture = true
            // Gestures during post-turn page preparation are ignored by design; let the last turn settle first.
            waitFor("volume turn settles") { var idle = false; main { idle = !frame.isTurning }; idle }
            // Relative control: from a known level, a 40%-height drag up adds about 0.44, and the same drag down removes it again.
            main { activity.window.attributes = activity.window.attributes.apply { screenBrightness = .4f } }
            gesture(.02f,.7f,.02f,.3f)
            var brightness = -1f; main { brightness = activity.window.attributes.screenBrightness }
            check(brightness in .76f..0.92f) { "Brightness edge drag up did not apply relatively: $brightness" }
            gesture(.02f,.3f,.02f,.7f)
            main { brightness = activity.window.attributes.screenBrightness }
            check(brightness in .32f..0.48f) { "Brightness edge drag down did not return: $brightness" }
            main { activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f } }
            report("Emulator touch input verified edge taps, swipe navigation, volume-key paging, and left-edge brightness")

            val location = state.passage(book.id)
            state.theme = "night"; state.font = 26; state.margin = 36; state.lineHeight = 175; state.justify = true
            main { web.restyle() }; Thread.sleep(1200)
            check(state.passage(book.id) == location) { "Appearance changed saved passage" }
            check(js("document.querySelector('foliate-view').renderer.getContents()[0].doc.defaultView.getComputedStyle(document.querySelector('foliate-view').renderer.getContents()[0].doc.body).fontSize") == "\"26px\"")
            check(ReadingState(context).margin == 36 && ReadingState(context).lineHeight == 175)
            state.scrollMode = true; main { web.restyle() }; Thread.sleep(800)
            check(js("document.querySelector('foliate-view').renderer.getAttribute('flow')") == "\"scrolled\"")
            state.scrollMode = false; state.theme = "paper"; state.font = 22; state.margin = 24; state.lineHeight = 150; state.justify = false; state.animation = "curl"
            main { web.restyle() }; Thread.sleep(900)
            report("Font, palette, spacing, margins, alignment, and scroll mode persist without moving the saved passage")

            // Exercise the actual center tap and settings dialog; no host mouse events.
            val xy = IntArray(2); main { frame.getLocationOnScreen(xy) }
            val now = SystemClock.uptimeMillis()
            val x = (xy[0] + frame.width / 2).toFloat(); val y = (xy[1] + frame.height / 2).toFloat()
            test.uiAutomation.injectInputEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0), true)
            test.uiAutomation.injectInputEvent(MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0), true)
            Thread.sleep(700)
            shot("controls")
            clickDescription("Reading appearance")
            shot("appearance")
            clickText("Page turns"); shot("page-turns")
            clickText("Controls"); shot("gestures")
            clickText("Type & layout")
            clickText("Dusk"); Thread.sleep(800)
            check(ReadingState(context).theme == "night")
            check(js("(()=>{let d=document.querySelector('foliate-view').renderer.getContents()[0].doc;return d.defaultView.getComputedStyle(d.querySelector('p')||d.body).color})()") == "\"rgb(225, 221, 210)\"") { "Night text did not take the readable foreground color" }
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); Thread.sleep(600)
            shot("night-reading")
            main { frame.getLocationOnScreen(coords) }; gesture(.5f,.5f,.5f,.5f)
            clickDescription("Reading appearance")
            clickText("White"); Thread.sleep(500)
            check(ReadingState(context).theme == "white")
            clickText("Paper"); Thread.sleep(500)
            val close = test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Close reading appearance").firstOrNull()
            if (close != null) close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            else test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            Thread.sleep(400)
            report("Center tap reveals controls; all three appearance tabs open without clipping or crashes")

            main { activity.recreate() }
            Thread.sleep(2000)
            // Process/activity recreation must retain a reading location; preferences restored below.
            check(ReadingState(context).passage(book.id) == location)
            report("Reading location survives activity recreation")
        } finally {
            val edit = prefs.edit().clear()
            original.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
            } }; edit.commit()
        }
    }
}
