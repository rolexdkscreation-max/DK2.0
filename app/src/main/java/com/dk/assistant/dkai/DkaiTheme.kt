package com.dk.assistant.dkai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** DKAI के रंग / आकार (मालिक की design spec) */
object DkaiTheme {
    const val BG = 0xFF0B101D.toInt()            // Deep Space
    const val CARD = 0xFF161E31.toInt()
    const val BORDER = 0xFF26324D.toInt()
    const val BLUE = 0xFF3B82F6.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val VIOLET = 0xFFA855F7.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GRAY = 0xFF94A3B8.toInt()
    const val GREEN = 0xFF22C55E.toInt()
    const val MINT = 0xFFA7F3D0.toInt()
    const val MINT_TEXT = 0xFF065F46.toInt()
    const val AMBER = 0xFFF59E0B.toInt()
    const val RED = 0xFFEF4444.toInt()
    const val RED_BG = 0xFF1E1218.toInt()
    const val PILL = 0xFF1F2940.toInt()
    const val PINK = 0xFFF472B6.toInt()

    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val SANS_MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val MONO: Typeface = Typeface.MONOSPACE

    fun dp(c: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()
    fun dpf(c: Context, v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)

    fun round(c: Context, fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(c, radiusDp)
        if (stroke != 0) setStroke(dp(c, strokeDp).coerceAtLeast(1), stroke)
    }

    fun oval(fill: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); if (stroke != 0) setStroke(strokePx, stroke)
    }

    /** छूने पर हल्की चमक (Material ripple) */
    fun press(d: Drawable, mask: Drawable? = null, color: Int = 0x33FFFFFF): RippleDrawable = RippleDrawable(ColorStateList.valueOf(color), d, mask ?: d)

    fun text(c: Context, s: String, sizeSp: Float, color: Int = WHITE, face: Typeface = SANS): TextView = TextView(c).apply {
        text = s; textSize = sizeSp; setTextColor(color); typeface = face; includeFontPadding = false
    }

    /** पिल बटन (गहरा, गोल) */
    fun pill(c: Context, s: String, fill: Int = PILL, color: Int = WHITE, sizeSp: Float = 13f): TextView = text(c, s, sizeSp, color, SANS_MED).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 12f), dp(c, 7f), dp(c, 12f), dp(c, 7f))
        background = press(round(c, fill, 999f, BORDER))
        isClickable = true; isFocusable = true
    }

    fun icon(c: Context, kind: DkaiIcon.Kind, color: Int, sizeDp: Float = 22f): DkaiIcon = DkaiIcon(kind, color).apply {
        val s = dp(c, sizeDp); setBounds(0, 0, s, s)
    }

    fun touchMin(c: Context) = dp(c, 44f)

    fun gone(v: View, hide: Boolean) { v.visibility = if (hide) View.GONE else View.VISIBLE }
}

/**
 * Feather जैसे पतली लकीर वाले icons (24x24 का SVG path, हमारे रंग में) - असली native vector, कोई PNG नहीं.
 */
class DkaiIcon(val kind: Kind, tint: Int, private val strokeW: Float = 2f) : Drawable() {
    enum class Kind(val path: String, val fill: Boolean = false) {
        CHAT("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"),
        BUILDER("M12 2L2 7l10 5 10-5-10-5z M2 17l10 5 10-5 M2 12l10 5 10-5"),
        AGENT("M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M9 9h6v6H9z M9 1v3 M15 1v3 M9 20v3 M15 20v3 M20 9h3 M20 14h3 M1 9h3 M1 14h3"),
        PIPELINE("M6 3v12 M21 6a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M18 9a9 9 0 0 1-9 9"),
        BROADCAST("M14.12 9.88a3 3 0 1 1-4.24 4.24a3 3 0 1 1 4.24-4.24z M16.24 7.76a6 6 0 0 1 0 8.49 M7.76 16.24a6 6 0 0 1 0-8.49 M19.07 4.93a10 10 0 0 1 0 14.14 M4.93 19.07a10 10 0 0 1 0-14.14"),
        HOME("M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M9 22V12h6v10"),
        FILES("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"),
        EDITOR("M16 18l6-6-6-6 M8 6l-6 6 6 6"),
        TERMINAL("M4 17l6-6-6-6 M12 19h8"),
        PREVIEW("M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z"),
        GITHUB("M9 19c-5 1.5-5-2.5-7-3 M16 22v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77A5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22"),
        MIC("M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z M19 10v2a7 7 0 0 1-14 0v-2 M12 19v4 M8 23h8"),
        MIC_OFF("M1 1l22 22 M9 9v3a3 3 0 0 0 5.12 2.12 M15 9.34V4a3 3 0 0 0-5.94-.6 M17 16.95A7 7 0 0 1 5 12v-2 M19 10v2a7 7 0 0 1-.11 1.23 M12 19v4 M8 23h8"),
        PLUS("M12 5v14 M5 12h14"),
        X("M18 6L6 18 M6 6l12 12"),
        DOWN("M6 9l6 6 6-6"),
        UP("M18 15l-6-6-6 6"),
        SEND("M12 19V5 M5 12l7-7 7 7"),
        MENU("M3 12h18 M3 6h18 M3 18h18"),
        FEMALE("M17 8a5 5 0 1 1-10 0a5 5 0 1 1 10 0z M12 13v9 M9 19h6"),
        MALE("M15 14a5 5 0 1 1-10 0a5 5 0 1 1 10 0z M13.5 10.5L20 4 M15 4h5v5"),
        FILE("M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M13 2v7h7"),
        FOLDER_PLUS("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z M12 11v6 M9 14h6"),
        FILE_PLUS("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M12 18v-6 M9 15h6"),
        TRASH("M3 6h18 M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6 M10 11v6 M14 11v6 M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"),
        REFRESH("M23 4v6h-6 M20.49 15a9 9 0 1 1-2.12-9.36L23 10"),
        SAVE("M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z M17 21v-8H7v8 M7 3v5h8"),
        PLAY("M5 3l14 9-14 9V3z"),
        STOP("M6 6h12v12H6z"),
        BACK("M19 12H5 M12 19l-7-7 7-7"),
        UPLOAD("M16 16l-4-4-4 4 M12 12v9 M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"),
        DOWNLOAD("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3"),
        EDIT("M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7 M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"),
        CHECK("M20 6L9 17l-5-5"),
        CHEVRON_RIGHT("M9 18l6-6-6-6"),
        LOCK("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z M7 11V7a5 5 0 0 1 10 0v4"),
        WRAP("M3 6h18 M3 12h15a3 3 0 1 1 0 6h-4 M16 16l-2 2 2 2 M3 18h7"),
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (kind.fill) Paint.Style.FILL else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = tint
    }
    private val src: Path = try { androidx.core.graphics.PathParser.createPathFromPathData(kind.path) } catch (_: Throwable) { Path() }
    private val drawn = Path()
    private val m = Matrix()

    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val s = minOf(b.width(), b.height()) / 24f
        m.reset(); m.setScale(s, s); m.postTranslate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        src.transform(m, drawn)
        paint.strokeWidth = strokeW * s
        canvas.drawPath(drawn, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
}
