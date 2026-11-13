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
    /** 13 नवंबर 2026 (मालिक): IDE Settings में Obsidian Dark / Luminous Light - रंग अब चुनी थीम से */
    const val PREF_THEME = "dkai_theme"
    @Volatile var light = false
    fun load(c: Context) { light = com.dk.assistant.Prefs.str(c, PREF_THEME, "dark") == "light" }
    fun setLight(c: Context, on: Boolean) { com.dk.assistant.Prefs.edit(c) { putString(PREF_THEME, if (on) "light" else "dark") }; light = on }
    private fun t(dark: Long, lite: Long): Int = (if (light) lite else dark).toInt()

    val BG get() = t(0xFF0B101D, 0xFFF4F6FB)            // Deep Space / Luminous
    val CARD get() = t(0xFF161E31, 0xFFFFFFFF)
    val BORDER get() = t(0xFF26324D, 0xFFDCE3EE)
    const val BLUE = 0xFF3B82F6.toInt()
    val CYAN get() = t(0xFF00E5FF, 0xFF0891B2)
    const val VIOLET = 0xFFA855F7.toInt()
    /** लिखावट का मुख्य रंग (अँधेरे में सफ़ेद, उजाले में गहरा) */
    val WHITE get() = t(0xFFFFFFFF, 0xFF0F172A)
    /** नीले/लाल बटन के ऊपर - हमेशा सफ़ेद */
    const val ON = 0xFFFFFFFF.toInt()
    val GRAY get() = t(0xFF94A3B8, 0xFF64748B)
    const val GREEN = 0xFF22C55E.toInt()
    const val MINT = 0xFFA7F3D0.toInt()
    const val MINT_TEXT = 0xFF065F46.toInt()
    const val AMBER = 0xFFF59E0B.toInt()
    const val RED = 0xFFEF4444.toInt()
    val RED_BG get() = t(0xFF1E1218, 0xFFFDECEC)
    val PILL get() = t(0xFF1F2940, 0xFFEEF2F7)
    const val PINK = 0xFFF472B6.toInt()
    /** नीचे की टैब-पट्टी / गहरा डिब्बा / चुनी पंक्ति / हल्की लिखावट / hint */
    val NAV get() = t(0xFF0D1324, 0xFFFFFFFF)
    val FIELD get() = t(0xFF0B1324, 0xFFF1F5F9)
    val SEL get() = t(0xFF1E2A47, 0xFFE0E7FF)
    val SUB get() = t(0xFFCBD5E1, 0xFF334155)
    val HINT get() = t(0xFF64748B, 0xFF94A3B8)

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
    fun pill(c: Context, s: String, fill: Int = PILL, color: Int = WHITE, sizeSp: Float = 13f): TextView = text(c, s, sizeSp, if (color == WHITE && accent(fill)) ON else color, SANS_MED).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 12f), dp(c, 7f), dp(c, 12f), dp(c, 7f))
        background = press(round(c, fill, 999f, BORDER))
        isClickable = true; isFocusable = true
    }

    fun icon(c: Context, kind: DkaiIcon.Kind, color: Int, sizeDp: Float = 22f): DkaiIcon = DkaiIcon(kind, color).apply {
        val s = dp(c, sizeDp); setBounds(0, 0, s, s)
    }

    /** भरा हुआ रंगीन बटन? (उस पर लिखावट हमेशा सफ़ेद) */
    fun accent(fill: Int) = fill == BLUE || fill == RED || fill == GREEN || fill == VIOLET || fill == 0xFF1D4ED8.toInt()

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
        COPY("M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2z M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"),
        CHEVRON_RIGHT("M9 18l6-6-6-6"),
        LOCK("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z M7 11V7a5 5 0 0 1 10 0v4"),
        WRAP("M3 6h18 M3 12h15a3 3 0 1 1 0 6h-4 M16 16l-2 2 2 2 M3 18h7"),
        // 13 नवंबर 2026: DKAI अपग्रेड के नए icon
        SEARCH("M11 19a8 8 0 1 0 0-16a8 8 0 0 0 0 16z M21 21l-4.35-4.35"),
        SHARE("M21 5a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M9 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M21 19a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M8.59 13.51l6.83 3.98 M15.41 6.51l-6.82 3.98"),
        GEAR("M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"),
        USER("M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M16 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0z"),
        TOOL("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"),
        GLOBE("M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z M2 12h20 M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"),
        WALLET("M3 4h18a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M1 10h22"),
        SPARKLE("M12 3l1.9 5.6l5.6 1.9l-5.6 1.9L12 18l-1.9-5.6L4.5 10.5l5.6-1.9z M19 3v4 M17 5h4"),
        IMAGE("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M10 8.5a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0z M21 15l-5-5L5 21"),
        MUSIC("M9 18V5l12-2v13 M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M21 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z"),
        ARCHIVE("M21 8v13H3V8 M1 3h22v5H1z M10 12h4"),
        DOC("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8 M10 9H8"),
        PACKAGE("M16.5 9.4L7.5 4.21 M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z M3.27 6.96L12 12.01l8.73-5.05 M12 22.08V12"),
        SERVER("M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z M6 6h.01 M6 18h.01"),
        SPLIT("M3 3h18v18H3z M12 3v18"),
        USERS("M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2 M13 7a4 4 0 1 1-8 0a4 4 0 1 1 8 0z M23 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75"),
        PAUSE("M6 4h4v16H6z M14 4h4v16h-4z", true),
        PLAY_FILL("M7 4l13 8-13 8z", true),
        RECORD("M12 5a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", true),
        FORWARD("M5 12h14 M12 5l7 7-7 7"),
        MORE("M12 13a1 1 0 1 0 0-2a1 1 0 0 0 0 2z M12 6a1 1 0 1 0 0-2a1 1 0 0 0 0 2z M12 20a1 1 0 1 0 0-2a1 1 0 0 0 0 2z"),
        SORT("M3 6h18 M7 12h10 M10 18h4"),
        LINK("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"),
        UNDO("M3 7v6h6 M21 17a9 9 0 0 0-9-9a9 9 0 0 0-6 2.3L3 13"),
        ZAP("M13 2L3 14h9l-1 8 10-12h-9l1-8z"),
        MOON("M21 12.79A9 9 0 1 1 11.21 3a7 7 0 0 0 9.79 9.79z"),
        SUN("M17 12a5 5 0 1 1-10 0a5 5 0 1 1 10 0z M12 1v2 M12 21v2 M4.22 4.22l1.42 1.42 M18.36 18.36l1.42 1.42 M1 12h2 M21 12h2 M4.22 19.78l1.42-1.42 M18.36 5.64l1.42-1.42"),
        DATABASE("M21 5c0 1.66-4 3-9 3s-9-1.34-9-3s4-3 9-3s9 1.34 9 3z M21 12c0 1.66-4 3-9 3s-9-1.34-9-3 M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"),
        CODE_FILE("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M10 13l-2 2 2 2 M14 13l2 2-2 2"),
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
