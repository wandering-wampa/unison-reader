package dev.homescreenreader

import android.app.*
import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Held gestures are essential here: a completed swipe alone cannot prove finger tracking. */
class CurlChecks(private val test: Instrumentation, private val report: (String) -> Unit) {
    private val context = test.targetContext
    private lateinit var activity: Activity
    private lateinit var frame: ReaderPageFrame
    private lateinit var web: FormattedReader
    private val xy = IntArray(2)
    private var downAt = 0L
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun js(code: String): String {
        val latch = CountDownLatch(1); var value = ""
        main { web.evaluateJavascript(code) { value = it; latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)); return value
    }
    private fun location() = js("document.querySelector('foliate-view').lastLocation.cfi")
    private fun waitFor(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(80) }
        error("Timed out: $label")
    }
    private fun idle() = waitFor("turn settles") { var done = false; main { done = !frame.isTurning }; done }
    private fun views(): List<View> {
        val list = mutableListOf<View>()
        fun visit(v: View) { list.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
        main { visit(activity.window.decorView) }; return list
    }
    private fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) { downAt = SystemClock.uptimeMillis(); main { frame.getLocationOnScreen(xy) } }
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, xy[0] + x*frame.width, xy[1] + y*frame.height, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(test.uiAutomation.injectInputEvent(event, true)); event.recycle()
    }
    private fun shot(name: String) {
        Thread.sleep(250)
        test.uiAutomation.takeScreenshot()!!.let { b -> File(context.filesDir,"curl-$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
    }
    fun run() {
        val prefs = context.getSharedPreferences("reading-v2",0); val saved = prefs.all.toMap()
        val state = ReadingState(context)
        val book = Library(context).use { it.load(state.active) } ?: error("No book")
        try {
            PlaybackService.send(context,"pause",book.id)
            state.animation = "curl"; state.scrollMode = false; state.theme = "paper"; state.follow(book.id,true)
            activity = test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("book",book.id).putExtra("read",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            frame = views().filterIsInstance<ReaderPageFrame>().single(); web = frame.reader
            waitFor("EPUB ready") { var ready=false; main { ready=web.isReaderReady }; ready }
            val initialCfi=location(); val initialPassage=state.passage(book.id)
            val surface = views().filterIsInstance<CurlSurface>().single()
            waitFor("book opens") { var ready=false; main { ready=frame.isPageOpen }; ready }
            main { check(frame.pageCacheReady) { "Book exposed an unprepared first gesture" } }
            check(location()==initialCfi && state.passage(book.id)==initialPassage) { "Preloading changed the live reader" }
            lateinit var originalPixels: android.graphics.Bitmap
            main { originalPixels=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888).also { web.draw(android.graphics.Canvas(it)) } }
            val original = location(); val passage = state.passage(book.id)
            val firstDragAt = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN,.92f,.72f); Thread.sleep(30)
            touch(MotionEvent.ACTION_MOVE,.68f,.67f)
            waitFor("live 3D frame") { var ready=false; main { ready=frame.lastResponseMs>=0 }; ready }
            var response=0L; var cached=false
            main { response=frame.lastResponseMs; cached=frame.lastTurnUsedCache }
            check(cached) { "First drag did not use prepared GPU pages" }
            check(response in 0..120) { "First-drag response regressed: $response ms" }
            report("First cached drag: $response ms from gesture recognition to presented curl; ${SystemClock.uptimeMillis()-firstDragAt} ms including injected input synchronization")
            lateinit var curledPixels: android.graphics.Bitmap
            main { curledPixels=surface.bitmap!! }
            var expectedInk=0;var actualInk=0;var differences=0;var samples=0
            var webLeft=0;var webTop=0; main { webLeft=web.left; webTop=web.top }
            for(y in 0 until originalPixels.height step 3) for(x in 0 until originalPixels.width/4 step 3){
                val a=originalPixels.getPixel(x,y);val b=curledPixels.getPixel(x+webLeft,y+webTop)
                if(android.graphics.Color.red(a)<150)expectedInk++
                if(android.graphics.Color.red(b)<150)actualInk++
                if(kotlin.math.abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))>32)differences++
                check(android.graphics.Color.alpha(b)==255) { "Curl leaks the live page through its shadows" };samples++
            }
            check(expectedInk>50 && actualInk in (expectedInk*.8).toInt()..(expectedInk*1.2).toInt() && differences<samples*.03) { "Cached text differs from original: ink=$actualInk/$expectedInk differences=$differences/$samples" }
            originalPixels.recycle();curledPixels.recycle()
            shot("held-quarter")
            check(state.passage(book.id)==passage && state.follow(book.id)) { "Preview changed saved position/follow" }
            val firstFrame = surface.framesDrawn
            touch(MotionEvent.ACTION_MOVE,.39f,.49f)
            waitFor("finger move redraws mesh") { surface.framesDrawn > firstFrame }
            shot("held-diagonal")
            var p=0f; main { p=frame.curlProgress }; check(p in .5f..0.55f)
            // Reverse direction, hold, then release: must cancel even after crossing midpoint.
            touch(MotionEvent.ACTION_MOVE,.85f,.70f); Thread.sleep(220)
            touch(MotionEvent.ACTION_UP,.85f,.70f); idle()
            check(location()==original && state.passage(book.id)==passage && state.follow(book.id)) { "Reversed drag failed to restore original page/state" }
            report("Held curl follows horizontal/vertical finger movement; reversing cancels without changing saved position or Follow")
            val timings=mutableListOf(response)
            repeat(4){
                touch(MotionEvent.ACTION_DOWN,.92f,.72f);Thread.sleep(25);touch(MotionEvent.ACTION_MOVE,.68f,.67f)
                waitFor("cached response") { var ready=false;main { ready=frame.lastResponseMs>=0 };ready }
                main { check(frame.lastTurnUsedCache);check(frame.lastResponseMs in 0..120);timings.add(frame.lastResponseMs) }
                touch(MotionEvent.ACTION_CANCEL,.68f,.67f);idle();check(location()==original)
            }
            report("Five cached drags: ${timings.joinToString()} ms; flat text pixels match the live EPUB and overlay remains opaque")
            // TextureView may lose its EGL surface when Android hides its window.
            main { surface.visibility=View.GONE }
            Thread.sleep(200)
            main { surface.visibility=View.VISIBLE }
            waitFor("cache rebuilt after surface recreation") { var ready=false;main { ready=frame.pageCacheReady };ready }
            check(location()==original && state.passage(book.id)==passage)
            report("EGL surface recreation refreshes the page cache without moving the reader")

            touch(MotionEvent.ACTION_DOWN,.92f,.65f); Thread.sleep(25); touch(MotionEvent.ACTION_MOVE,.32f,.47f)
            Thread.sleep(350); touch(MotionEvent.ACTION_UP,.32f,.47f); idle()
            check(location()!=original && !state.follow(book.id)) { "Forward curl did not commit" }
            val advanced = location(); val advancedPassage = state.passage(book.id)
            touch(MotionEvent.ACTION_DOWN,.08f,.70f); Thread.sleep(25); touch(MotionEvent.ACTION_MOVE,.62f,.48f)
            Thread.sleep(600); shot("backward-held")
            touch(MotionEvent.ACTION_CANCEL,.62f,.48f); idle()
            check(location()==advanced && state.passage(book.id)==advancedPassage) { "ACTION_CANCEL committed a backward page" }
            touch(MotionEvent.ACTION_DOWN,.08f,.70f); Thread.sleep(25); touch(MotionEvent.ACTION_MOVE,.75f,.55f)
            Thread.sleep(250); touch(MotionEvent.ACTION_UP,.75f,.55f); idle()
            check(location()==original) { "Backward curl did not return to same page" }
            report("Forward/backward commit and Android ACTION_CANCEL preserve exact EPUB locations")
            main { frame.turn(1); frame.turn(1); frame.turn(1) }; idle()
            check(location()==advanced) { "Repeated input committed more than one turn" }
            main { frame.turn(-1) }; idle(); check(location()==original)

            state.font=26;state.theme="night";main { web.restyle() };Thread.sleep(600)
            waitFor("cache refreshed after typography/palette") { var ready=false;main { ready=frame.pageCacheReady };ready }
            val restyled=location()
            touch(MotionEvent.ACTION_DOWN,.92f,.72f);Thread.sleep(25);touch(MotionEvent.ACTION_MOVE,.68f,.67f)
            waitFor("restyled cached response") { var ready=false;main { ready=frame.lastResponseMs>=0 };ready }
            main { check(frame.lastTurnUsedCache);check(frame.lastResponseMs in 0..120) }
            shot("cached-night");touch(MotionEvent.ACTION_CANCEL,.68f,.67f);idle();check(location()==restyled)
            state.font=(saved["font"] as? Int)?:22;state.theme="paper";main { web.restyle() };Thread.sleep(600)
            report("Font/palette changes rebuild cached textures and preserve fast cancellation")

            // A cancelled drag across an EPUB spine item must restore the original chapter.
            js("document.querySelector('foliate-view').renderer.goTo({index:2,anchor:0})")
            Thread.sleep(600); val chapterStart=location(); val cursor=state.passage(book.id)
            waitFor("chapter cache") { var ready=false;main { ready=frame.pageCacheReady };ready }
            var previousChapter="";main { previousChapter=frame.cachedTarget(-1)!! }
            check(js("document.querySelector('foliate-view').resolveNavigation(${org.json.JSONObject.quote(previousChapter)}).index")=="1")
            touch(MotionEvent.ACTION_DOWN,.08f,.65f); Thread.sleep(25); touch(MotionEvent.ACTION_MOVE,.58f,.44f)
            Thread.sleep(600)
            main { check(frame.lastTurnUsedCache) }
            check(js("document.querySelector('foliate-view').renderer.getContents()[0].index")=="2") { "Cached drag moved the live chapter before commit" }
            touch(MotionEvent.ACTION_CANCEL,.58f,.44f); idle()
            check(location()==chapterStart && state.passage(book.id)==cursor)
            js("document.querySelector('foliate-view').renderer.goTo({index:0,anchor:0})")
            Thread.sleep(500); val start=location(); state.follow(book.id,true)
            main { frame.turn(-1) }; idle(); check(location()==start && state.follow(book.id))
            js("(()=>{const v=document.querySelector('foliate-view');return v.renderer.goTo({index:v.book.sections.length-1,anchor:1})})()")
            Thread.sleep(500); val end=location()
            main { frame.turn(1) }; idle(); check(location()==end && state.follow(book.id))
            // Restore content using its exact CFI for layout screenshots.
            js("document.querySelector('foliate-view').goTo($original)"); Thread.sleep(500)
            report("Rapid repeated input, chapter-boundary cancellation, and both book boundaries preserve position")

            state.follow(book.id,true)
            touch(MotionEvent.ACTION_DOWN,.5f,.5f); touch(MotionEvent.ACTION_UP,.5f,.5f); Thread.sleep(1200)
            val listen = views().filterIsInstance<TextView>().single { it.text.toString()=="Listen here" && it.isShown }
            fun fits(v: TextView, label: String) = v.lineCount==1 && v.paint.measureText(label) <= v.width-v.compoundPaddingLeft-v.compoundPaddingRight+1
            main { check(fits(listen,"Listen here")) }
            shot("listen-here")
            val row = listen.parent as LinearLayout
            val follow = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>().single { it.text.toString()=="Follow" }
            // Measure the real row at compact widths and enlarged labels in both states: idle
            // ("Listen here", no Follow) and narrating elsewhere ("Pause" + Follow). Then restore.
            val labels=(0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>()
            val sizes=labels.associateWith { it.textSize }
            main {
                for (narrating in listOf(false,true)) for (widthDp in listOf(288,328,380)) for (scale in listOf(1f,1.3f,1.5f,2f)) {
                    listen.text = if (narrating) "Pause" else "Listen here"; follow.visibility = if (narrating) View.VISIBLE else View.GONE
                    labels.forEach { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizes.getValue(it)*scale) }
                    val w=(widthDp*context.resources.displayMetrics.density).toInt()
                    row.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    row.layout(0,0,w,row.measuredHeight)
                    check(fits(listen, listen.text.toString())) { "${listen.text} clipped at ${widthDp}dp / $scale" }
                    if (narrating) check(fits(follow,"Follow")) { "Follow clipped at ${widthDp}dp / $scale" }
                    for (i in 0 until row.childCount) check(row.getChildAt(i).visibility==View.GONE || row.getChildAt(i).right<=w) { "Transport control offscreen at ${widthDp}dp / $scale (narrating=$narrating)" }
                }
                listen.text = "Listen here"; follow.visibility = View.GONE
                labels.forEach { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizes.getValue(it)) }; row.requestLayout()
            }
            report("Listen here / Pause and Follow stay on one line with all controls inside 288/328/380dp rows at 100–200% text sizes")
            val monitor=test.addMonitor(MainActivity::class.java.name,null,false)
            main { activity.recreate() }
            activity=monitor.waitForActivityWithTimeout(10000) ?: error("Reader did not reopen")
            test.removeMonitor(monitor)
            frame=views().filterIsInstance<ReaderPageFrame>().single();web=frame.reader
            waitFor("reopened book") { var ready=false;main { ready=web.isReaderReady&&frame.isPageOpen };ready }
            val reopened=location()
            touch(MotionEvent.ACTION_DOWN,.92f,.72f);Thread.sleep(25);touch(MotionEvent.ACTION_MOVE,.68f,.67f)
            waitFor("reopened curl") { var ready=false;main { ready=frame.lastResponseMs>=0 };ready }
            var reopenTime=0L;main { check(frame.lastTurnUsedCache);reopenTime=frame.lastResponseMs;check(reopenTime in 0..120) }
            touch(MotionEvent.ACTION_CANCEL,.68f,.67f);idle();check(location()==reopened)
            waitFor("old renderer thread released") { Thread.getAllStackTraces().keys.count{it.name=="Reader paper mesh"&&it.isAlive}==1 }
            report("Activity recreation releases the old renderer; reopened first drag responds in $reopenTime ms")
        } finally {
            main { if (::frame.isInitialized) frame.cancelTurn() }
            val edit=prefs.edit().clear(); saved.forEach { (k,v)->when(v) { is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is Boolean->edit.putBoolean(k,v) } };edit.commit()
        }
    }
}
