package dev.homescreenreader

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import fi.harism.curl.CurlMesh
import fi.harism.curl.CurlPage
import javax.microedition.khronos.egl.*
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** A real cylindrical XYZ paper mesh, composited in the normal Android view hierarchy.
 * Only the render thread owns EGL, meshes and texture bitmaps. Idle pages render no frames.
 * Geometry is from Harri Smatt's Apache-2.0 android-pagecurl; see docs/THIRD_PARTY.md.
 */
internal class CurlSurface(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("Reader paper mesh").apply { start() }
    private val render = Handler(thread.looper)
    private var egl: EGL10? = null
    private var display: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var surface: EGLSurface? = null
    private var gl: GL10? = null
    private var sheet = CurlMesh(40)
    private var underneath = CurlMesh(1)
    private var usingCache = false
    private var npot = false
    private val cachedPages = mutableMapOf<Int, CurlMesh>()
    @Volatile private var cacheGeneration = 0
    @Volatile private var readyMask = 0
    private var loaded = false
    private var pixelWidth = 1
    private var pixelHeight = 1
    private var color = Color.WHITE
    private var direction = 1
    private var soft = false
    private var progress = 0f
    private var originY = 0f
    private var fingerY = 0f
    private val curlPos = PointF()
    private val curlDir = PointF()
    @Volatile private var serial = 0
    @Volatile private var presented = -1
    private var ready: (() -> Unit)? = null
    var onFailure: () -> Unit = {}
    var onSurfaceReady: () -> Unit = {}
    @Volatile var framesDrawn = 0L
        private set
    @Volatile private var interacting = false

    init { isOpaque = false; surfaceTextureListener = this; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun prepareSurface() { alpha = 1f }
    private fun bit(offset: Int) = 1 shl (offset + 1)
    fun hasPage(generation: Int, offset: Int) = cacheGeneration == generation && readyMask and bit(offset) != 0
    fun resetCache(generation: Int, shift: Int? = null) {
        cacheGeneration = generation
        readyMask = if (shift == null) 0 else (-1..1).filter { it + shift in -1..1 && readyMask and bit(it + shift) != 0 }.fold(0) { mask, i -> mask or bit(i) }
        render.post {
            val retained = mutableMapOf<Int, CurlMesh>()
            cachedPages.forEach { (offset, mesh) ->
                val next = offset - (shift ?: 10)
                if (next in -1..1) retained[next] = mesh else gl?.let(mesh::release)
            }
            cachedPages.clear(); cachedPages.putAll(retained)
        }
    }
    /** Upload while idle, retaining all three page meshes across gestures. */
    fun cachePage(generation: Int, offset: Int, page: Bitmap, paper: Int, complete: (Boolean) -> Unit) {
        render.post {
            val g = gl
            if (generation != cacheGeneration || interacting || g == null) { page.recycle(); post { complete(false) }; return@post }
            val mesh = CurlMesh(40)
            val success = runCatching {
                mesh.texturePage.setNpotSupported(npot)
                mesh.setRect(RectF(0f,pixelHeight.toFloat()/pixelWidth,1f,0f))
                mesh.setFlipTexture(false)
                val back = Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888)
                Canvas(back).apply { drawColor(paper); drawBitmap(page,0f,0f,Paint().apply { alpha=22 }) }
                mesh.texturePage.setTexture(page,CurlPage.SIDE_FRONT)
                mesh.texturePage.setTexture(back,CurlPage.SIDE_BACK)
                mesh.onDrawFrame(g)
                // Exercise shadow/lighting shader variants before a finger ever needs them.
                mesh.curl(PointF(.6f,pixelHeight.toFloat()/pixelWidth/2),PointF(-1f,0f),.13)
                mesh.onDrawFrame(g); mesh.reset(); g.glFinish()
                if (generation == cacheGeneration) {
                    cachedPages.put(offset,mesh)?.release(g)
                    readyMask = readyMask or bit(offset)
                    true
                } else { mesh.release(g); false }
            }.getOrElse { mesh.release(g); android.util.Log.e("ReaderCurl","Page preloading failed",it); false }
            post { complete(success) }
        }
    }
    fun cachedPages(generation: Int, dir: Int, paper: Int, softCurl: Boolean, y: Float, amount: Float, dragY: Float, onReady: () -> Unit): Boolean {
        if (!hasPage(generation,0) || !hasPage(generation,dir)) return false
        val id = ++serial; ready = onReady; interacting = true
        render.post {
            if (id != serial || generation != cacheGeneration) return@post
            if (!usingCache) { gl?.let { sheet.release(it); underneath.release(it) } }
            usingCache = true
            sheet = cachedPages.getValue(if (dir > 0) 0 else -1)
            underneath = cachedPages.getValue(if (dir > 0) 1 else 0)
            direction=dir; color=paper; soft=softCurl; originY=y; fingerY=dragY; progress=amount
            loaded=true; draw(id)
        }
        return true
    }
    /** Takes ownership of both bitmaps. */
    fun pages(old: Bitmap, next: Bitmap, dir: Int, paper: Int, softCurl: Boolean, y: Float, amount: Float, dragY: Float, onReady: () -> Unit) {
        val id = ++serial; ready = onReady; interacting = true
        render.post {
            if (id != serial || gl == null) {
                old.recycle(); next.recycle(); post { if (id == serial) onFailure() }; return@post
            }
            runCatching {
                if (usingCache) {
                    sheet = CurlMesh(40); underneath = CurlMesh(1); usingCache = false
                    sheet.texturePage.setNpotSupported(npot); underneath.texturePage.setNpotSupported(npot)
                }
                direction = dir; color = paper; soft = softCurl; originY = y; fingerY = dragY; progress = amount
                val page = if (dir > 0) old else next
                val base = if (dir > 0) next else old
                val back = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                Canvas(back).apply { drawColor(paper); drawBitmap(page, 0f, 0f, Paint().apply { alpha = 22 }) }
                val rect = RectF(0f, pixelHeight.toFloat() / pixelWidth, 1f, 0f)
                sheet.setRect(rect); sheet.setFlipTexture(false)
                sheet.texturePage.setTexture(page, CurlPage.SIDE_FRONT)
                sheet.texturePage.setTexture(back, CurlPage.SIDE_BACK)
                underneath.setRect(rect); underneath.setFlipTexture(false)
                underneath.texturePage.setTexture(base, CurlPage.SIDE_BOTH)
                // Upload first: CurlMesh resets its geometry when a new texture is uploaded.
                underneath.onDrawFrame(gl); sheet.onDrawFrame(gl)
                loaded = true; draw(id)
            }.onFailure { android.util.Log.e("ReaderCurl", "Cannot prepare paper textures", it); post { if (id == serial) onFailure() } }
        }
    }
    fun position(amount: Float, y: Float) {
        val id = serial
        render.removeCallbacks(drawFrame)
        render.post { if (id == serial) { progress = amount.coerceIn(0f, 1f); fingerY = y } }
        render.post(drawFrame)
    }
    private val drawFrame = Runnable { draw(serial) }
    fun clear() {
        val id = ++serial; ready = null; alpha = 0f; interacting = false
        render.removeCallbacks(drawFrame)
        render.post { if (id == serial) { loaded = false; if (!usingCache) gl?.let { sheet.release(it); underneath.release(it) } } }
    }
    private fun draw(id: Int) {
        val g = gl ?: return
        if (!loaded || id != serial) return
        runCatching {
            val h = pixelHeight.toFloat() / pixelWidth
            g.glClearColor(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f, 1f)
            g.glClear(GL10.GL_COLOR_BUFFER_BIT)
            underneath.reset(); underneath.onDrawFrame(g)
            val p = if (direction > 0) progress else 1f - progress
            if (p < .0001f) { sheet.reset(); sheet.onDrawFrame(g) }
            else if (p < .9999f) {
                // Curl follows both axes; the left edge remains bound to the book.
                // Radius is physical (not device pressure, which is unreliable on emulators).
                val x = 1f - p
                val startY = h * (1f - originY)
                val y = startY + ((originY - fingerY) * h).coerceIn(-.45f, .45f) * sin(PI * p).toFloat()
                curlPos.set(x, y); curlDir.set(x - 1f, y - startY)
                val distance = hypot(curlDir.x, curlDir.y).coerceAtLeast(.0001f)
                var radius = if (soft) .22 else .13
                val length = radius * PI
                if (distance >= length) {
                    radius = max(min(x.toDouble(), radius), 0.0)
                    curlPos.y -= (curlDir.y * (distance - length) / (2 * distance)).toFloat()
                } else {
                    val translate = radius * sin(PI * sqrt(distance / length))
                    curlPos.x += (curlDir.x * translate / distance).toFloat()
                    curlPos.y += (curlDir.y * translate / distance).toFloat()
                }
                curlPos.x = curlPos.x.coerceAtLeast(0f)
                if (curlDir.y != 0f) {
                    val spineY = curlPos.y + curlPos.x * curlDir.x / curlDir.y
                    if (curlDir.y < 0 && spineY < h) curlDir.set(curlPos.y - h, -curlPos.x)
                    else if (curlDir.y > 0 && spineY > 0) curlDir.set(-curlPos.y, curlPos.x)
                }
                val norm = hypot(curlDir.x, curlDir.y).coerceAtLeast(.0001f)
                curlDir.set(curlDir.x/norm, curlDir.y/norm)
                sheet.curl(curlPos, curlDir, radius); sheet.onDrawFrame(g)
            }
            // Shadow blending must not make the TextureView translucent and leak the
            // live page through the cached next page. Keep RGB, seal the final alpha.
            g.glColorMask(false,false,false,true); g.glClearColor(0f,0f,0f,1f)
            g.glClear(GL10.GL_COLOR_BUFFER_BIT); g.glColorMask(true,true,true,true)
            check(egl!!.eglSwapBuffers(display, surface)) { "EGL swap failed" }
            presented = id; framesDrawn++
        }.onFailure { loaded = false; android.util.Log.e("ReaderCurl", "Paper renderer failed", it); post { if (id == serial) onFailure() } }
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        render.post {
            runCatching {
                val e = EGLContext.getEGL() as EGL10; egl = e
                val d = e.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY); display = d
                check(e.eglInitialize(d, IntArray(2)))
                val configs = arrayOfNulls<EGLConfig>(1)
                check(e.eglChooseConfig(d, intArrayOf(EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,EGL10.EGL_ALPHA_SIZE,8,EGL10.EGL_SURFACE_TYPE,EGL10.EGL_WINDOW_BIT,EGL10.EGL_RENDERABLE_TYPE,1,EGL10.EGL_NONE), configs, 1, IntArray(1)))
                val c = e.eglCreateContext(d, configs[0], EGL10.EGL_NO_CONTEXT, intArrayOf(0x3098,1,EGL10.EGL_NONE)); eglContext = c
                val s = e.eglCreateWindowSurface(d, configs[0], texture, null); surface = s
                check(e.eglMakeCurrent(d, s, s, c))
                gl = c.gl as GL10
                sheet = CurlMesh(40); underneath = CurlMesh(1)
                npot = gl!!.glGetString(GL10.GL_EXTENSIONS).contains("GL_OES_texture_npot")
                sheet.texturePage.setNpotSupported(npot); underneath.texturePage.setNpotSupported(npot)
                gl!!.apply { glShadeModel(GL10.GL_SMOOTH); glDisable(GL10.GL_DEPTH_TEST); glDisable(GL10.GL_CULL_FACE); glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST) }
                resize(width, height)
                // Compile the paper shaders during reader startup, before the first drag.
                sheet.setRect(RectF(0f, height.toFloat()/width, 1f, 0f))
                sheet.texturePage.setTexture(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888), CurlPage.SIDE_FRONT)
                sheet.texturePage.setTexture(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888), CurlPage.SIDE_BACK)
                sheet.onDrawFrame(gl)
                sheet.curl(PointF(.6f,height.toFloat()/width/2),PointF(-1f,0f),.13)
                sheet.onDrawFrame(gl); gl!!.glFinish(); sheet.release(gl)
                gl!!.glClearColor(0f,0f,0f,0f); gl!!.glClear(GL10.GL_COLOR_BUFFER_BIT)
                e.eglSwapBuffers(d,s)
                post { onSurfaceReady() }
            }.onFailure { android.util.Log.e("ReaderCurl", "EGL initialization failed", it); post { onFailure() } }
        }
    }
    private fun resize(w: Int, h: Int) {
        pixelWidth = w.coerceAtLeast(1); pixelHeight = h.coerceAtLeast(1)
        gl?.apply {
            glViewport(0, 0, w, h); glMatrixMode(GL10.GL_PROJECTION); glLoadIdentity()
            // Orthographic projection keeps the flat page pixel-aligned. The mesh still
            // bends in XYZ; curved normals provide lighting and height casts shadows.
            glOrthof(0f, 1f, 0f, h.toFloat()/pixelWidth, -2f, 2f)
            glMatrixMode(GL10.GL_MODELVIEW); glLoadIdentity()
        }
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { render.post { resize(width, height) } }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (presented == serial) ready?.let { ready = null; alpha = 1f; it() }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        ++serial; ready = null
        readyMask = 0
        render.post {
            loaded = false
            gl?.let { g ->
                if (!usingCache) { sheet.release(g); underneath.release(g) }
                cachedPages.values.forEach { it.release(g) }; cachedPages.clear()
            }; gl = null; usingCache = false
            egl?.let { e ->
                e.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                e.eglDestroySurface(display, surface); e.eglDestroyContext(display, eglContext); e.eglTerminate(display)
            }
            egl = null; texture.release()
        }
        return false
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); thread.quitSafely() }
}
