package dev.homescreenreader

import android.content.Context
import android.graphics.Color

/**
 * The reading palette: page, text, highlight and accent colours for each paper. The papers are chosen
 * for reading (a warm cream, white, a neutral night grey, black) rather than taken from the app's room,
 * so a dark library never forces a dark page; the narration band and the accent follow the brand.
 */
object ReadingColors {
    fun dark(c: Context) =
        c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun nightPaper(c: Context, s: ReadingState) = s.theme in listOf("night", "black") || s.theme == "system" && dark(c)

    fun background(c: Context, s: ReadingState) =
        when (s.theme) {
            "paper" -> Color.rgb(251, 246, 234)
            "white" -> Color.rgb(255, 255, 252)
            "black" -> Color.BLACK
            "night" -> Color.rgb(28, 31, 29)
            else -> if (dark(c)) Color.rgb(28, 31, 29) else Color.rgb(251, 246, 234)
        }

    fun foreground(c: Context, s: ReadingState) =
        if (nightPaper(c, s)) Color.rgb(225, 221, 210) else Color.rgb(43, 45, 37)

    /** The narrated sentence: an opaque teal band, so the bands of neighbouring lines never double up. */
    fun highlight(c: Context) =
        if (ReadingState(c).let { nightPaper(c, it) }) Color.rgb(18, 86, 87) else Color.rgb(179, 227, 224)

    /** A control surface: a quiet lift from the page colour. */
    fun surface(c: Context, s: ReadingState) =
        androidx.core.graphics.ColorUtils.blendARGB(background(c, s), foreground(c, s), .09f)

    /** The action colour on the page's chrome: the brand's blue on light papers, its teal on dark ones. */
    fun accent(c: Context, s: ReadingState) =
        if (nightPaper(c, s)) Color.rgb(0, 183, 188) else Color.rgb(6, 77, 115)

    fun onAccent(c: Context, s: ReadingState) =
        if (nightPaper(c, s)) Color.rgb(6, 47, 71) else Color.WHITE
}
