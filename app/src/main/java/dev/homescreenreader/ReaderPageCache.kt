package dev.homescreenreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

/** Three-page, memory-only cache. A sibling WebView lays out pages without moving
 * the live selection. Images and GPU meshes slide together after committed turns. */
internal class ReaderPageCache(
    private val frame: ReaderPageFrame,
    private val live: FormattedReader,
    private val surface: CurlSurface,
    private val idle: () -> Boolean,
) {
    val renderer = live.cacheRenderer().apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false; isFocusableInTouchMode = false
    }
    private val state = ReadingState(frame.context)
    private data class Page(val cfi: String, val image: Bitmap)
    private val pages = mutableMapOf<Int,Page>()
    private val known = mutableSetOf<Int>()
    private var requestedCfi = ""
    private var currentCfi = ""
    private var layout = ""
    private var running = false
    private var request = 0
    private var destroyed = false
    var onPrepared: () -> Unit = {}
    var generation = 0
        private set
    val currentImage get() = pages[0]?.image
    private fun current() = currentCfi == requestedCfi && layout == layoutKey()
    fun target(direction: Int) = pages[direction]?.cfi
    fun isReady(direction: Int) = current() && pages.containsKey(0) && pages.containsKey(direction) && surface.hasPage(generation,0) && surface.hasPage(generation,direction)
    fun isEdge(direction: Int) = current() && direction in known && direction !in pages
    fun isComplete() = current() && (-1..1).all { it in known } && surface.hasPage(generation,0)
    init {
        renderer.onReady = { schedule() }
        renderer.onCachePage = cachePage@ { id, offset, cfi ->
            if(destroyed)return@cachePage
            val cacheGeneration = generation
            if (id != request || !idle() || destroyed) renderer.cacheCaptured(id)
            else if (cfi.isEmpty()) { known.add(offset); renderer.cacheCaptured(id) }
            else renderer.afterPaint {
                if(destroyed)return@afterPaint
                if (id != request || !idle() || destroyed) renderer.cacheCaptured(id)
                else {
                    val bitmap = frame.capture(renderer)
                    if (bitmap == null) renderer.cacheCaptured(id)
                    else surface.cachePage(cacheGeneration,offset,bitmap.copy(Bitmap.Config.ARGB_8888,false),ReadingColors.background(frame.context,state)) { uploaded ->
                        if (uploaded && id == request && cacheGeneration == generation && !destroyed) {
                            pages.put(offset,Page(cfi,bitmap))?.image?.recycle(); known.add(offset)
                        } else bitmap.recycle()
                        if (!destroyed) renderer.cacheCaptured(id)
                    }
                }
            }
        }
        renderer.onCacheDone = { id -> if (id == request) { running=false; if(isComplete())onPrepared(); schedule() } }
    }
    fun location(cfi: String) { if (cfi.isNotBlank()) requestedCfi=cfi; schedule() }
    private val prepare = Runnable { prepare() }
    fun schedule() {
        frame.removeCallbacks(prepare)
        if (!destroyed) frame.postDelayed(prepare,32)
    }
    private fun layoutKey() = "${frame.width}:${frame.height}:${state.theme}:${state.font}:${state.typeface}:${state.lineHeight}:${state.margin}:${state.paragraphSpace}:${state.justify}:${state.scrollMode}:${live.gridShiftKey()}"
    private fun prepare() {
        if (destroyed || frame.windowVisibility != View.VISIBLE || !idle() || !live.isReaderReady || !renderer.isReaderReady || frame.width <= 0 || frame.height <= 0 || requestedCfi.isBlank()) return
        if (state.scrollMode || state.animation !in listOf("curl","soft-curl")) {
            if (pages.isNotEmpty() || running) invalidate()
            return
        }
        val key = layoutKey()
        if (currentCfi != requestedCfi || key != layout) {
            val shift = if (key == layout) pages.entries.firstOrNull { it.value.cfi == requestedCfi }?.key else null
            renderer.cancelCache(); running=false; ++generation
            val retained = mutableMapOf<Int,Page>()
            val retainedKnown = mutableSetOf<Int>()
            pages.forEach { (offset,page) ->
                val next=offset-(shift?:10)
                if(next in -1..1)retained[next]=page else page.image.recycle()
            }
            if(shift!=null)known.mapTo(retainedKnown){it-shift}
            known.clear(); known.addAll(retainedKnown.filter{it in -1..1})
            pages.clear(); pages.putAll(retained)
            currentCfi=requestedCfi; layout=key; surface.resetCache(generation,shift)
        }
        if (!running && !isComplete()) {
            val offsets=listOf(0,1,-1).filter{it !in known}
            if(offsets.isEmpty())return
            running=true; renderer.prepareCache(currentCfi,++request,offsets)
        }
        if(isComplete())onPrepared()
    }
    /** Keep completed pages; stop unfinished work from competing with touch rendering. */
    fun pause() { frame.removeCallbacks(prepare); ++request; renderer.cancelCache(); running=false }
    fun invalidate() {
        if(destroyed)return
        pause(); ++generation; currentCfi=""; layout=""; known.clear()
        pages.values.forEach{it.image.recycle()}; pages.clear(); surface.resetCache(generation)
    }
    fun destroy() {
        destroyed=true; pause(); pages.values.forEach{it.image.recycle()}; pages.clear()
        frame.removeView(renderer); renderer.destroy()
    }
}
