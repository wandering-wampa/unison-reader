package dev.homescreenreader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.graphics.PathParser
import com.google.android.material.button.MaterialButton

/** Small native design system shared by the library and reading sheets. */
/**
 * The Unison look, as tokens. Light is the Reading Room: cream paper, ink text, one deep-blue action
 * colour; dark is the Night room: blue-black paper, cream text, teal actions. Teal means "live or in
 * sync" and nothing else; the active tab is ink on cream rather than a second blue; headings are the
 * brand's ink. The reading page keeps its own papers (ReadingColors) and borrows the teal band.
 */
class BookishUi(val context: Context) {
    private val night = ReadingColors.dark(context)
    private fun c(light: String, dark: String) = Color.parseColor(if (night) dark else light)
    val paper = c("#FBF6EA", "#0B2231")
    val surface = c("#F3EDDE", "#0F2D40")
    val surfaceHigh = c("#EAE2CF", "#173B4D")
    val line = c("#E6DECB", "#1E4056")
    val ink = c("#1E2B33", "#F3EEDF")
    val heading = c("#062F47", "#F3EEDF")
    val muted = c("#5F6E78", "#A6B4BE")
    val accent = c("#064D73", "#00B7BC")
    val onAccent = c("#FFFFFF", "#062F47")
    val selected = c("#062F47", "#FFF5DE")
    val onSelected = c("#FFF5DE", "#062F47")
    val live = Color.parseColor("#00B7BC")
    val liveText = c("#0B5F62", "#5FD3D6")
    val highlight = c("#B3E3E0", "#125657")
    val warn = Color.parseColor("#E0A83A")
    val error = c("#B4412B", "#F0A090")
    /** The old names, kept so every call site reads the same colour: the accent and its text. */
    val olive get() = accent
    val onOlive get() = onAccent
    val serif: Typeface = Typeface.createFromAsset(context.assets, "fonts/Literata-Regular.ttf")
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    /** A filled shape that answers a touch with a ripple, for a button whose background is its own colour. */
    fun pressable(color: Int, radius: Int = 16, ripple: Int = ink) =
        android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(ripple, 60)), shape(color, radius), shape(Color.WHITE, radius))
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float = 16f, literary: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (literary) heading else ink)
        typeface = if (literary) serif else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun caption(value: String) = text(value, 11f).apply { letterSpacing = .13f; setTextColor(muted) }
    fun space(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun rule(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1))) }
    fun button(value: String, primary: Boolean = false, action: () -> Unit) =
        MaterialButton(context).apply {
            text = value; isAllCaps = false; textSize = 14f
            minWidth = 0; minimumWidth = 0; minHeight = dp(48)
            insetTop = dp(3); insetBottom = dp(3); cornerRadius = dp(14)
            setPadding(dp(16), 0, dp(16), 0)
            backgroundTintList = ColorStateList.valueOf(if (primary) olive else surface)
            setTextColor(if (primary) onOlive else ink)
            setOnClickListener { action() }
        }
    fun icon(name: String, label: String, color: Int = ink, action: () -> Unit) =
        android.widget.ImageButton(context).apply {
            setImageDrawable(BookishIcon(name, color))
            contentDescription = label; tooltipText = label
            setPadding(dp(13), dp(13), dp(13), dp(13))
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attrs.getDrawable(0); attrs.recycle()
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { action() }
        }
    /**
     * A book's cover in a slot of the given size (the slot never changes size, so no row or layout moves). A loaded image
     * gives the rounded, shadowed card its own shape, between square and 2:3 (width:height) at the slot's width; [gravity]
     * says where the card sits in the slot. No image, or a portrait cover, fills the slot as it always did. A shadow set
     * with `elevation` lands on the card.
     */
    fun cover(book: Book, width: Int, height: Int, gravity: Int = Gravity.CENTER, fitHeight: Boolean = false): CoverSlot {
        val slot = CoverSlot(this, width, height, gravity, fitHeight).apply { contentDescription = "Cover of ${book.title}" }
        val bitmap = if (book.cover.isNotBlank()) coverBitmap(book.cover, dp(width)) else null
        if (bitmap != null) slot.showBitmap(bitmap)
        else slot.showPlaceholder(text(book.title, 18f, true).apply { this.gravity = Gravity.CENTER; setPadding(dp(12), dp(16), dp(12), dp(16)) })
        return slot
    }
}

/**
 * The slot a cover is drawn in: a transparent frame of fixed size holding the card (rounded corners, surface colour, the
 * shadow). The card is the whole slot until an image is shown; then it takes the image's shape, so no band of the card's
 * colour shows above or below a square cover. An image can arrive after the view is built: show it with [showFile] or [showBitmap].
 */
class CoverSlot(private val ui: BookishUi, widthDp: Int, heightDp: Int, private val place: Int, fitHeight: Boolean = false) : FrameLayout(ui.context) {
    private val slotWidth = ui.dp(widthDp); private val slotHeight = ui.dp(heightDp)
    private val card = FrameLayout(ui.context).apply { background = ui.shape(ui.surface, 5); clipToOutline = true }
    init {
        clipChildren = false; clipToPadding = false
        // With `fitHeight` (the Home hero, alone) the slot is as tall as its card; every list row keeps a fixed slot so rows stay aligned.
        layoutParams = LinearLayout.LayoutParams(slotWidth, if (fitHeight) -2 else slotHeight)
        addView(card, LayoutParams(slotWidth, slotHeight, place))
    }
    override fun setElevation(elevation: Float) { card.elevation = elevation }
    override fun getElevation() = card.elevation
    private fun cardHeight(height: Int) { (card.layoutParams as LayoutParams).height = height; card.requestLayout() }
    fun showPlaceholder(label: View) { card.removeAllViews(); cardHeight(slotHeight); card.addView(label, LayoutParams(-1, -1)) }
    fun showBitmap(bitmap: Bitmap) = show(ImageView(context).apply { setImageBitmap(bitmap) }, bitmap.width, bitmap.height)
    /** An image file; a file that cannot be read leaves what was shown. */
    fun showFile(file: java.io.File) {
        // Downsampled and cached like every other cover, never a full-size decode on the main thread.
        showBitmap(coverBitmap(file.path, slotWidth) ?: return)
    }
    private fun show(image: ImageView, imageWidth: Int, imageHeight: Int) {
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        val shape = (imageWidth.toFloat() / imageHeight.coerceAtLeast(1)).coerceIn(2f / 3f, 1f)
        card.removeAllViews(); card.addView(image, LayoutParams(-1, -1))
        cardHeight(minOf(slotHeight, (slotWidth / shape).toInt()))
    }
}

/** Original geometric line icons, rendered sharply at Android density. */
class BookishIcon(private val name: String, private val tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.STROKE; strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val data = when (name) {
            "back" -> "M15 5 L8 12 L15 19"
            "close" -> "M6 6 L18 18 M18 6 L6 18"
            "plus" -> "M12 5 V19 M5 12 H19"
            "search" -> "M16 16 L21 21 M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10"
            "book" -> "M12 5 C9 3 5 3 3 4 V20 C6 19 9 19 12 21 C15 19 18 19 21 20 V4 C18 3 15 3 12 5 V21"
            "headphones" -> "M4 14 V11 A8 8 0 0 1 20 11 V14 M4 12 H7 V20 H4 Z M17 12 H20 V20 H17 Z"
            "play" -> "M8 4 L20 12 L8 20 Z"
            "pause" -> "M8 5 V19 M16 5 V19"
            "contents" -> "M8 6 H21 M8 12 H21 M8 18 H21 M3 6 H3.1 M3 12 H3.1 M3 18 H3.1"
            "more" -> "M5 12 H5.1 M12 12 H12.1 M19 12 H19.1"
            "settings" -> "M10.34 4.79 L10.33 2.55 L13.67 2.55 L13.66 4.79 A7.4 7.4 0 0 1 15.92 5.72 L17.51 4.14 L19.86 6.49 L18.28 8.08 A7.4 7.4 0 0 1 19.21 10.34 L21.45 10.33 L21.45 13.67 L19.21 13.66 A7.4 7.4 0 0 1 18.28 15.92 L19.86 17.51 L17.51 19.86 L15.92 18.28 A7.4 7.4 0 0 1 13.66 19.21 L13.67 21.45 L10.33 21.45 L10.34 19.21 A7.4 7.4 0 0 1 8.08 18.28 L6.49 19.86 L4.14 17.51 L5.72 15.92 A7.4 7.4 0 0 1 4.79 13.66 L2.55 13.67 L2.55 10.33 L4.79 10.34 A7.4 7.4 0 0 1 5.72 8.08 L4.14 6.49 L6.49 4.14 L8.08 5.72 A7.4 7.4 0 0 1 10.34 4.79 Z M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12"
            "chevron" -> "M9 5 L16 12 L9 19"
            "down" -> "M5 9 L12 16 L19 9"
            "home" -> "M4 11 L12 4 L20 11 V20 H14 V14 H10 V20 H4 Z"
            "library" -> "M5 4 V20 M10 4 V20 M14 6 L19 20 M3 20 H21"
            "previous" -> "M6 5 V19 M19 5 L9 12 L19 19 Z"
            "next" -> "M18 5 V19 M5 5 L15 12 L5 19 Z"
            "moon" -> "M19 15 A8 8 0 0 1 9 4 A9 9 0 1 0 19 15"
            "chart" -> "M5 20 V11 M10 20 V5 M15 20 V13 M20 20 V8"
            "phone" -> "M7 3 H17 A1 1 0 0 1 18 4 V20 A1 1 0 0 1 17 21 H7 A1 1 0 0 1 6 20 V4 A1 1 0 0 1 7 3 M11 18 H13"
            "folder" -> "M3 6 A1 1 0 0 1 4 5 H9 L11 7 H20 A1 1 0 0 1 21 8 V18 A1 1 0 0 1 20 19 H4 A1 1 0 0 1 3 18 Z"
            "devices" -> "M3 5 H15 V14 H3 Z M1 17 H16 M18 9 H22 V19 H18 Z"
            "people" ->"M9 11 A3 3 0 1 0 9 5 A3 3 0 1 0 9 11 M3 19 C3 14 15 14 15 19 M16 5 A3 3 0 0 1 16 11 M18 14 C20 15 21 16.5 21 19"
            "lock" -> "M6 11 H18 V20 H6 Z M8 11 V8 A4 4 0 0 1 16 8 V11"
            "swap" -> "M4 8 H18 M14 4 L18 8 L14 12 M20 16 H6 M10 12 L6 16 L10 20"
            "doc" -> "M6 3 H14 L18 7 V21 H6 Z M14 3 V7 H18 M9 12 H15 M9 16 H15"
            "server" -> "M4 4 H20 V10 H4 Z M4 14 H20 V20 H4 Z M8 7 H8.1 M8 17 H8.1"
            else -> "M5 19 L12 5 L19 19 M8 14 H16"
        }
        PathParser.createPathFromPathData(data)?.let { canvas.drawPath(it, paint) }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A ring turning round a button, drawn over it (the view's foreground) while what was pressed is on its way. It keeps
 * itself turning for as long as it is shown; with the phone's animations switched off it is a still, open ring.
 */
class BookishRing(tint: Int, private val inset: Int, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()
    override fun draw(canvas: Canvas) {
        val side = minOf(bounds.width(), bounds.height()) - 2f * inset - stroke
        if (side <= 0) return
        box.set(bounds.exactCenterX() - side / 2, bounds.exactCenterY() - side / 2, bounds.exactCenterX() + side / 2, bounds.exactCenterY() + side / 2)
        val turning = android.animation.ValueAnimator.areAnimatorsEnabled()
        canvas.drawArc(box, if (turning) (android.os.SystemClock.uptimeMillis() % 1000) * .36f else -60f, 250f, false, paint)
        if (turning && isVisible) invalidateSelf()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Cover images already decoded, by file, date and width: lists redraw often (every search keystroke) and must not decode each cover again. */
private val coverCache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
}

/** A cover decoded at about the width it is shown at (never the full-size file), or null when the file cannot be read. */
private fun coverBitmap(path: String, widthPx: Int): android.graphics.Bitmap? {
    val file = java.io.File(path)
    val key = path + "|" + file.lastModified() + "|" + widthPx
    coverCache.get(key)?.let { return it }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
    val bitmap = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    coverCache.put(key, bitmap)
    return bitmap
}
