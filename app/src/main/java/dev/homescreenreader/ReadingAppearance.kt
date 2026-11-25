package dev.homescreenreader

import android.app.Activity
import android.content.res.ColorStateList
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

class ReadingAppearance(private val activity: Activity, private val changed: () -> Unit) {
    private val ui = BookishUi(activity)
    private val state = ReadingState(activity)
    private val dialog = BottomSheetDialog(activity)
    private val body = ui.column()
    private var tab = 0

    fun show() {
        val root = ui.column().apply {
            setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(16))
            background = ui.shape(ui.paper, 24)
            clipToOutline = true
        }
        val heading = ui.row()
        heading.addView(ui.text("Make it yours", 25f, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.icon("close", "Close reading appearance") { dialog.dismiss() })
        root.addView(heading)
        root.addView(ui.text("Your book. Your way of reading.", 13f).apply { setTextColor(ui.muted) })
        ui.space(root, 14)
        val tabs = ui.row()
        val tabButtons = listOf("Type & layout", "Page turns", "Controls").mapIndexed { i, label ->
            ui.button(label, i == tab) { tab = i; render();
                for (n in 0 until tabs.childCount) {
                    (tabs.getChildAt(n) as com.google.android.material.button.MaterialButton).apply {
                        isSelected = n == i
                        backgroundTintList = ColorStateList.valueOf(if (n == i) ui.olive else ui.surface)
                        setTextColor(if (n == i) ui.onOlive else ui.ink)
                    }
                }
            }.apply { textSize = 12f; setSingleLine(true); setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        }
        val available = activity.resources.displayMetrics.widthPixels - ui.dp(44)
        val tabWidth = tabButtons.maxOf { it.paint.measureText(it.text.toString()).toInt() + ui.dp(26) }
        val fit = tabWidth * tabButtons.size <= available
        tabButtons.forEach { tabs.addView(it, LinearLayout.LayoutParams(if (fit) 0 else tabWidth, -2, if (fit) 1f else 0f).apply { marginEnd = ui.dp(3) }) }
        if (fit) root.addView(tabs) else root.addView(HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = true; addView(tabs) })
        ui.space(root, 8)
        root.addView(ScrollView(activity).apply { isFillViewport = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        root.layoutParams.height = (activity.resources.displayMetrics.heightPixels * .76).toInt()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        render(); dialog.show()
    }

    private fun apply() { changed() }
    private fun title(text: String) { ui.space(body, 16); body.addView(ui.caption(text.uppercase())); ui.space(body, 8) }
    private fun choices(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val available = activity.resources.displayMetrics.widthPixels - ui.dp(44)
        val labelPaint = ui.text("", 12f).paint
        val itemWidth = labels.maxOf { labelPaint.measureText(it).toInt() + ui.dp(22) }.coerceAtLeast(ui.dp(48))
        val columns = (available / itemWidth).coerceAtLeast(1).coerceAtMost(if (labels.size > 4) 3 else labels.size.coerceAtMost(4))
        val rows = labels.indices.chunked(columns)
        rows.forEach { indices ->
            val row = ui.row()
            indices.forEach { i -> row.addView(ui.button(labels[i], i == selected) { onSelect(i); apply(); render() }.apply {
                isSelected = i == selected   // said by a screen reader; the colour alone is not
                textSize = 12f; setSingleLine(true); setPadding(ui.dp(7), 0, ui.dp(7), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) }) }
            body.addView(row)
        }
    }
    private fun slider(label: String, value: Int, min: Int, max: Int, unit: (Int) -> String, update: (Int) -> Unit) {
        ui.space(body, 14)
        val heading = ui.row()
        heading.addView(ui.text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
        val number = ui.text(unit(value), 13f).apply { setTextColor(ui.muted) }
        heading.addView(number); body.addView(heading)
        body.addView(SeekBar(activity).apply {
            this.min = min; this.max = max; progress = value; contentDescription = label
            progressTintList = ColorStateList.valueOf(ui.olive); thumbTintList = ColorStateList.valueOf(ui.olive)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { update(s.progress); apply() }
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { number.text = unit(v) }
            })
        }, LinearLayout.LayoutParams(-1, ui.dp(44)))
    }
    private fun toggle(label: String, checked: Boolean, update: (Boolean) -> Unit) {
        body.addView(MaterialSwitch(activity).apply {
            text = label; textSize = 14f; setTextColor(ui.ink); isChecked = checked
            minHeight = ui.dp(54)
            setOnCheckedChangeListener { _, value -> update(value); apply() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun render() {
        body.removeAllViews()
        when (tab) {
            0 -> {
                title("Page palette")
                val themes = listOf("paper", "white", "night", "black", "system")
                choices(listOf("Paper", "White", "Dusk", "Black", "System"), themes.indexOf(state.theme)) { state.theme = themes[it] }
                title("Typeface")
                val fonts = listOf("literata", "serif", "sans", "publisher")
                choices(listOf("Literata", "Classic", "Sans", "Original"), fonts.indexOf(state.typeface)) { state.typeface = fonts[it] }
                slider("Text size", state.font, 14, 40, { "$it" }) { state.font = it }
                slider("Line spacing", state.lineHeight, 115, 200, { "%.2f".format(it / 100f) }) { state.lineHeight = it }
                slider("Side margins", state.margin, 8, 48, { "$it" }) { state.margin = it }
                slider("Paragraph spacing", state.paragraphSpace, 0, 24, { "$it" }) { state.paragraphSpace = it }
                toggle("Justify paragraphs", state.justify) { state.justify = it }
            }
            1 -> {
                title("Reading mode")
                choices(listOf("Pages", "Continuous scroll"), if (state.scrollMode) 1 else 0) { state.scrollMode = it == 1 }
                title("Turn animation")
                val styles = listOf("curl", "soft-curl", "slide", "vertical", "cover", "cover-vertical", "fade", "none")
                choices(listOf("Paper curl", "Soft curl", "Slide", "Slide up", "Cover", "Cover up", "Fade", "None"), styles.indexOf(state.animation)) { state.animation = styles[it] }
                slider("Turn duration", state.turnDuration, 120, 700, { "$it ms" }) { state.turnDuration = it }
                ui.space(body, 12)
                body.addView(ui.text("Tap the page edges or swipe to turn. Page effects apply in Pages mode.", 13f).apply { setTextColor(ui.muted) })
                ui.space(body, 12)
                toggle("Smooth scroll while following narration", state.followScroll) { state.followScroll = it }
                body.addView(ui.text("While narration is followed, the text scrolls smoothly instead of turning pages, with the same layout, margins and line breaks. Pausing only removes the highlight; pages return with your next page turn, continuing from where the screen ended.", 13f).apply { setTextColor(ui.muted) })
            }
            else -> {
                title("Tap zones")
                val taps = listOf("edges", "right", "off")
                choices(listOf("Left / right", "Right advances", "Swipe only"), taps.indexOf(state.tapLayout)) { state.tapLayout = taps[it] }
                ui.space(body, 8)
                body.addView(ui.text("A center tap shows or hides the reading controls. Long press text to select it.", 13f).apply { setTextColor(ui.muted) })
                ui.space(body, 16)
                toggle("Volume keys turn pages", state.volumeTurns) { state.volumeTurns = it }
                toggle("Left-edge brightness gesture", state.brightnessGesture) { state.brightnessGesture = it }
                toggle("Right-edge text size gesture", state.textSizeGesture) { state.textSizeGesture = it }
                toggle("Keep screen on while reading", state.keepAwake) { state.keepAwake = it }
                toggle("Pause narration when I turn a page", state.pauseOnTurn) { state.pauseOnTurn = it }
            }
        }
        ui.space(body, 20)
    }
}
