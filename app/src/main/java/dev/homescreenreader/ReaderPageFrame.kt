package dev.homescreenreader

import android.animation.*
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.*

/** Routes page gestures without interfering with WebView selection or continuous scroll. */
class ReaderPageFrame(context: Context, val reader: FormattedReader) : FrameLayout(context) {
    private val state = ReadingState(context)
    private val curl = CurlSurface(context)
    private val overlay = TurnSurface(context)
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var intercept = false
    private var bright = false
    private var busy = false
    private val cache = ReaderPageCache(this,reader,curl) { !busy }
    private val opening = LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
        setBackgroundColor(ReadingColors.background(context,state))
        val ui=BookishUi(context)
        addView(ProgressBar(context),LinearLayout.LayoutParams(ui.dp(32),ui.dp(32)))
        addView(ui.text("Opening book…",14f).apply { gravity=Gravity.CENTER; setTextColor(ReadingColors.foreground(context,state)) },LinearLayout.LayoutParams(-2,-2).apply { topMargin=ui.dp(16) })
    }
    internal val isPageOpen get() = opening.visibility != VISIBLE
    private var curling = false
    private var prepared = false
    private var resolving = false
    private var token = 0
    private var direction = 1
    private var progress = 0f
    private var fingerY = .5f
    private var release: Boolean? = null
    private var velocity: VelocityTracker? = null
    private var animation: ValueAnimator? = null
    var onToggle: () -> Unit = {}
    var onBrowse: () -> Unit = {}
    var onBrightness: (Float) -> Unit = {}
    /** Supplies the brightness in effect when a left-edge drag begins (0..1). */
    var brightnessNow: () -> Float = { .5f }
    var onBrightnessEnd: () -> Unit = {}
    private var brightStart = .5f
    /** A right-edge drag: the text size in effect when it began, and each size it passes through. */
    var textSizeNow: () -> Int = { 22 }
    var onTextSize: (Int) -> Unit = {}
    var onTextSizeEnd: () -> Unit = {}
    private var sizing = false
    private var sizeStart = 22
    /** Continuous scrolling imposed while narration is followed, independent of the saved setting. */
    var scrollOverride = false
    /**
     * Asked when a page turn is attempted while the smooth-follow layout is showing. Returning true
     * means narration is paused and the turn may bring the pages back: the screen as it is becomes
     * the outgoing page and the next page arrives with the turn, nothing snaps first.
     */
    var onLeaveFollow: () -> Boolean = { false }
    private var exitFollow = false
    private fun leaveFollow(): Boolean {
        if (!scrollOverride) return true
        if (!onLeaveFollow()) return false
        scrollOverride = false; exitFollow = true
        return true
    }
    internal val isTurning get() = busy
    internal val curlProgress get() = progress
    internal val pageCacheReady get() = cache.isComplete()
    internal fun cachedTarget(direction: Int) = cache.target(direction)
    internal var lastTurnUsedCache = false
        private set
    internal var lastResponseMs = -1L
        private set
    private var gestureStartedAt = 0L
    private var resetCacheOnIdle = false
    /**
     * System-bar and cutout insets applied as margins of the page views only, so the frame, the
     * curl surfaces, and every captured page texture span the whole screen while text stays clear
     * of the camera cutout and the gesture bar.
     */
    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        var changed = false
        for (view in listOf(reader, cache.renderer)) {
            val lp = view.layoutParams as? LayoutParams ?: continue
            if (lp.leftMargin != left || lp.topMargin != top || lp.rightMargin != right || lp.bottomMargin != bottom) {
                lp.setMargins(left, top, right, bottom); view.layoutParams = lp; changed = true
            }
        }
        if (changed) { requestLayout(); if (isPageOpen) { cancelTurn(); cache.invalidate() } }
    }

    /** Draws `view` into a frame-sized bitmap at its own position, on the page colour. */
    internal fun capture(view: View): Bitmap? = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(ReadingColors.background(context, state))
            canvas.translate(view.left.toFloat(), view.top.toFloat())
            view.draw(canvas)
        }
    }.getOrNull()

    init {
        addView(cache.renderer, LayoutParams(-1,-1))
        addView(reader, LayoutParams(-1, -1))
        addView(curl, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1)); overlay.visibility = GONE
        addView(opening,LayoutParams(-1,-1))
        reader.onTap = { pageTap() }
        reader.onReady = {
            if(!isCurl() || state.scrollMode || !ValueAnimator.areAnimatorsEnabled()) revealPage()
            else { cache.schedule(); postDelayed(openingTimeout,8000) }
        }
        cache.onPrepared = { revealPage() }
        reader.onPageChanged = { cache.location(it) }
        reader.onTurnReady = { finishFlatTurn() }
        reader.onPreviewReady = { id, changed ->
            if (curling && id == token && !resolving) {
                if (!changed) resolve(false)
                else if (!lastTurnUsedCache) reader.afterPaint {
                    if (curling && id == token && !resolving) {
                        val next = snapshot()
                        val old = overlay.oldPage?.copy(Bitmap.Config.ARGB_8888, false)
                        if (next == null || old == null) { next?.recycle(); old?.recycle(); resolve(false) }
                        else {
                            curl.pages(old, next, direction, ReadingColors.background(context, state), state.animation == "soft-curl", downY / height, progress, fingerY) {
                                if (curling && id == token && !resolving) {
                                    prepared = true; overlay.visibility = GONE; removeCallbacks(timeout)
                                    lastResponseMs = SystemClock.uptimeMillis() - gestureStartedAt
                                    release?.let { settle(it) }
                                }
                            }
                            curl.position(progress, fingerY)
                        }
                    }
                }
            }
        }
        reader.onPreviewResolved = { id ->
            if (curling && id == token) reader.afterPaint { if (id == token) cleanUp() }
        }
        curl.onFailure = { revealPage(); if (curling && !resolving) resolve(false) }
        curl.onSurfaceReady = {
            if (isAttachedToWindow) {
                if (busy) { resetCacheOnIdle=true; cancelTurn() }
                else { cache.invalidate(); cache.schedule() }
            }
        }
    }
    private val openingTimeout = Runnable { revealPage() }
    private fun revealPage() { removeCallbacks(openingTimeout); opening.visibility=GONE }
    private fun isCurl() = state.animation == "curl" || state.animation == "soft-curl"
    private fun pageTap() {
        if (busy) return
        val x = downX / width.coerceAtLeast(1)
        when {
            state.scrollMode || state.tapLayout == "off" -> onToggle()
            x < .25f && state.tapLayout == "edges" -> turn(-1)
            x > .73f -> turn(1)
            else -> onToggle()
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isPageOpen) return true
        if (busy) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = SystemClock.uptimeMillis()
                intercept = false; bright = false; sizing = false
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || SystemClock.uptimeMillis() - downAt > 400) return false
                val dx = event.x - downX; val dy = event.y - downY
                val threshold = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
                val edge = 24 * resources.displayMetrics.density
                bright = state.brightnessGesture && downX < edge && abs(dy) > abs(dx) && abs(dy) > threshold
                sizing = !bright && state.textSizeGesture && downX > width - edge && abs(dy) > abs(dx) && abs(dy) > threshold
                val vertical = state.animation == "vertical" || state.animation == "cover-vertical"
                val swipe = if (vertical) abs(dy) > threshold && abs(dy) > abs(dx) else abs(dx) > threshold && abs(dx) > abs(dy)
                val pageSwipe = !state.scrollMode && swipe && leaveFollow()
                if (bright || sizing || pageSwipe) {
                    intercept = true
                    if (bright) brightStart = brightnessNow().coerceIn(.03f, 1f)
                    if (sizing) sizeStart = textSizeNow()
                    if (!bright && !sizing && isCurl() && ValueAnimator.areAnimatorsEnabled()) {
                        beginCurl(if (dx < 0) 1 else -1, abs(dx)/width, event.y/height)
                        track(event)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isPageOpen) return true
        if (!intercept && !busy) return super.onTouchEvent(event)
        if (busy && (!curling || release != null || resolving)) return true
        velocity?.addMovement(event)
        if (bright) {
            // Relative control: a full-height drag spans the whole range from wherever it started.
            if (event.actionMasked == MotionEvent.ACTION_MOVE) onBrightness((brightStart + (downY - event.y) / height * 1.1f).coerceIn(.03f, 1f))
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) onBrightnessEnd()
        } else if (sizing) {
            // Up is bigger: one point per fourteenth of the screen, so a full drag spans the whole range.
            if (event.actionMasked == MotionEvent.ACTION_MOVE) onTextSize((sizeStart + ((downY - event.y) / (height / 14f)).toInt()).coerceIn(14, 40))
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) onTextSizeEnd()
        } else if (curling) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) finishGesture(false)
                    else track(event)
                }
                MotionEvent.ACTION_UP -> {
                    track(event)
                    velocity?.computeCurrentVelocity(1000)
                    val speed = -(velocity?.xVelocity ?: 0f) * direction / resources.displayMetrics.density
                    finishGesture(if (speed < -650f) false else progress > .34f || progress > .06f && speed > 650f)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishGesture(false)
            }
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            val delta = if (state.animation == "vertical" || state.animation == "cover-vertical") event.y - downY else event.x - downX
            if (abs(delta) > 28 * resources.displayMetrics.density) turn(if (delta < 0) 1 else -1)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { velocity?.recycle(); velocity = null }
        return true
    }
    private fun track(event: MotionEvent) {
        progress = (direction * (downX - event.x) / width).coerceIn(0f, 1f)
        fingerY = (event.y / height).coerceIn(0f, 1f)
        curl.position(progress, fingerY)
    }
    private fun snapshot() = capture(reader)
    private fun beginCurl(dir: Int, amount: Float = 0f, y: Float = downY / height) {
        if (busy || !reader.isReaderReady || width <= 0 || height <= 0) return
        if (cache.isEdge(dir)) return
        gestureStartedAt = SystemClock.uptimeMillis(); lastResponseMs = -1
        val exit = exitFollow; exitFollow = false
        // Leaving smooth follow: the cached images show pages, not the screen, so the live view is used.
        lastTurnUsedCache = !exit && cache.isReady(dir)
        val page = (if (lastTurnUsedCache) cache.currentImage else snapshot()) ?: return
        cache.pause()
        busy = true; curling = true; prepared = false; resolving = false; release = null
        direction = dir; progress = amount.coerceIn(0f,1f); fingerY = y
        overlay.release(); overlay.ownsBitmap = !lastTurnUsedCache; overlay.oldPage = page; overlay.effect = "cover"; overlay.direction = 1; overlay.progress = 0f
        overlay.visibility = VISIBLE; overlay.invalidate()
        curl.prepareSurface()
        ++token
        if (lastTurnUsedCache) {
            val id=token
            curl.cachedPages(cache.generation,dir,ReadingColors.background(context,state),state.animation=="soft-curl",downY/height,progress,fingerY) {
                if (curling && id == token && !resolving) {
                    prepared=true; overlay.visibility=GONE; removeCallbacks(timeout)
                    lastResponseMs=SystemClock.uptimeMillis()-gestureStartedAt
                    release?.let{settle(it)}
                }
            }
        }
        reader.preview(dir, token, if (lastTurnUsedCache) cache.target(dir) else null, exit)
        postDelayed(timeout, 5000)
    }
    private fun finishGesture(commit: Boolean) {
        if (release != null) return
        release = commit
        if (prepared) settle(commit)
    }
    private fun settle(commit: Boolean) {
        if (resolving) return
        stopAnimation()
        val start = progress; val end = if (commit) 1f else 0f
        val y = fingerY; val home = downY / height
        animation = ValueAnimator.ofFloat(start, end).apply {
            duration = (state.turnDuration * abs(end - start)).toLong().coerceAtLeast(120)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                curl.position(progress, y + (home - y) * it.animatedFraction)
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { resolve(commit) } })
            start()
        }
    }
    private fun resolve(commit: Boolean) {
        if (resolving) return
        resolving = true; stopAnimation()
        removeCallbacks(timeout); postDelayed(timeout, 5000)
        if (commit) onBrowse()
        reader.resolvePreview(token, commit)
    }
    fun turn(direction: Int) {
        if (busy || !reader.isReaderReady || width <= 0 || height <= 0) return
        if (!leaveFollow()) return
        if (isCurl() && !state.scrollMode && ValueAnimator.areAnimatorsEnabled()) {
            downY = height * .66f
            beginCurl(direction); finishGesture(true); return
        }
        val exit = exitFollow; exitFollow = false
        onBrowse()
        if (state.animation == "none" || state.scrollMode || !ValueAnimator.areAnimatorsEnabled()) { busy = true; reader.step(direction, exit); postDelayed(timeout, 2500); return }
        cache.pause(); busy = true; overlay.release(); overlay.ownsBitmap = true; overlay.oldPage = snapshot()
        if (overlay.oldPage == null) { busy = false; reader.step(direction, exit); return }
        overlay.direction = direction; overlay.effect = state.animation; overlay.progress = 0f
        overlay.visibility = VISIBLE; overlay.invalidate(); reader.step(direction, exit); postDelayed(timeout, 2500)
    }
    private val timeout = Runnable { if (curling && !resolving) resolve(false) else cleanUp() }
    private fun finishFlatTurn() {
        if (!busy || curling) return
        if (overlay.visibility != VISIBLE) { cleanUp(); return }
        reader.afterPaint {
            if (!busy || curling || !isAttachedToWindow) return@afterPaint
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = state.turnDuration.toLong(); interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { overlay.progress = it.animatedValue as Float; overlay.invalidate() }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { cleanUp() } }); start()
            }
        }
    }
    private fun stopAnimation() { animation?.removeAllListeners(); animation?.cancel(); animation = null }
    private fun cleanUp() {
        removeCallbacks(timeout); stopAnimation(); curl.clear()
        busy = false; curling = false; prepared = false; resolving = false
        overlay.visibility = GONE; overlay.release()
        if(resetCacheOnIdle) { resetCacheOnIdle=false; cache.invalidate() }
        cache.schedule()
    }
    fun cancelTurn() { if (curling && !resolving) resolve(false) else if (!curling) cleanUp() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if (busy && (w != oldw || h != oldh)) cancelTurn() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE)cache.schedule() else { cache.pause(); cancelTurn() }
    }
    override fun onDetachedFromWindow() { removeCallbacks(openingTimeout); cancelTurn(); cleanUp(); cache.destroy(); velocity?.recycle(); velocity = null; super.onDetachedFromWindow() }
}

/** The other six effects retain their inexpensive snapshot compositor. */
private class TurnSurface(context: Context) : View(context) {
    var oldPage: Bitmap? = null
    var ownsBitmap = true
    var progress = 0f
    var direction = 1
    var effect = "cover"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun release() { if (ownsBitmap) oldPage?.recycle(); oldPage = null }
    override fun onDraw(canvas: Canvas) {
        val bitmap = oldPage ?: return
        val w = width.toFloat(); val h = height.toFloat(); val p = progress
        paint.alpha = 255
        when (effect) {
            "fade" -> { paint.alpha = ((1-p)*255).toInt(); canvas.drawBitmap(bitmap, 0f, 0f, paint) }
            "slide", "vertical" -> {
                val vertical = effect == "vertical"
                canvas.drawBitmap(bitmap, if (vertical) 0f else -direction*w*p, if (vertical) -direction*h*p else 0f, paint)
            }
            else -> {
                val save = canvas.save()
                if (effect == "cover-vertical") {
                    if (direction > 0) canvas.clipRect(0f, 0f, w, h*(1-p)) else canvas.clipRect(0f, h*p, w, h)
                } else {
                    if (direction > 0) canvas.clipRect(0f, 0f, w*(1-p), h) else canvas.clipRect(w*p, 0f, w, h)
                }
                canvas.drawBitmap(bitmap, 0f, 0f, paint); canvas.restoreToCount(save)
            }
        }
    }
}
