package com.dk.assistant.forge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * DK AI ब्रीफ़ Part 14: कम RAM वाले फ़ोन या WebGL न चले तो Canvas वाला Forge scene - वही state से चलता है.
 * reduced motion = स्थिर चित्र (हर बदलाव पर एक बार बनता है).
 */
class ForgeFallbackView(context: Context, private val reducedMotion: Boolean) : View(context) {
    var progress = 0f
        set(v) { field = v; invalidate() }
    var stage = 0
        set(v) { field = v; invalidate() }
    var failed = false
        set(v) { field = v; invalidate() }
    var sections = 0
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val t0 = System.nanoTime()

    init { contentDescription = "Website Forge" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = if (reducedMotion) 0f else (System.nanoTime() - t0) / 1e9f
        c.drawColor(ForgeTheme.BG)
        val cx = w / 2; val cy = h * 0.42f; val r = min(w, h) * 0.13f
        val accent = if (failed) 0xFF8A8AA0.toInt() else ForgeTheme.ACCENT
        // glow
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r * 3.2f, intArrayOf((accent and 0x00FFFFFF) or 0x66000000, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 3.2f, p)
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.2f, intArrayOf(0xFFFFFFFF.toInt(), accent, ForgeTheme.LOTUS), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * (1f + 0.04f * sin(t * 3f)), p)
        p.shader = null
        // rings
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.05f
        for (i in 0 until 3) {
            p.color = if (i == 1) ForgeTheme.ACCENT else ForgeTheme.LAGOON
            p.alpha = 170
            c.save(); c.rotate(t * (20f + i * 15f) + i * 60f, cx, cy)
            arc.set(cx - r * (1.9f + i * 0.35f), cy - r * (0.7f + i * 0.2f), cx + r * (1.9f + i * 0.35f), cy + r * (0.7f + i * 0.2f))
            c.drawOval(arc, p); c.restore()
        }
        // glyph-like particles spiralling in
        p.style = Paint.Style.FILL
        val n = if (failed) 12 else 48
        for (i in 0 until n) {
            val k = ((i * 0.618f + t * 0.25f) % 1f)
            val rr = r * (4.2f - 3.2f * k); val a = i * 2.4f + k * 6f + t * 0.3f
            p.color = if (i % 2 == 0) ForgeTheme.LAGOON else ForgeTheme.ACCENT
            p.alpha = (255 * (1f - k)).toInt().coerceIn(0, 255)
            c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr * 0.55f, r * 0.04f, p)
        }
        // section slabs
        for (i in 0 until min(sections, 10)) {
            val sx = w * (0.12f + (i % 5) * 0.17f); val sy = h * (0.08f + (i / 5) * 0.08f)
            p.color = ForgeTheme.SURFACE; p.alpha = 200
            c.drawRoundRect(sx, sy, sx + w * 0.14f, sy + h * 0.055f, 12f, 12f, p)
            p.color = ForgeTheme.ACCENT; p.alpha = 160
            c.drawRect(sx + 10f, sy + 14f, sx + w * 0.1f, sy + 20f, p)
        }
        // progress arc + 6 stage dots
        val pr = r * 2.9f
        arc.set(cx - pr, cy - pr, cx + pr, cy + pr)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.strokeCap = Paint.Cap.ROUND
        p.color = 0x33FFFFFF; c.drawArc(arc, -90f, 360f, false, p)
        p.color = accent; c.drawArc(arc, -90f, 360f * progress.coerceIn(0f, 1f), false, p)
        p.style = Paint.Style.FILL
        for (i in 0 until 6) {
            val a = Math.toRadians((i * 60.0) - 90.0)
            p.color = if (i <= stage) (if (i == stage) ForgeTheme.ACCENT else ForgeTheme.LAGOON) else 0xFF444466.toInt()
            c.drawCircle(cx + (cos(a) * pr).toFloat(), cy + (sin(a) * pr).toFloat(), r * 0.12f, p)
        }
        if (!reducedMotion && !failed && isShown) postInvalidateOnAnimation()
    }
}

/** Forge के रंग - "a pearl of light in the night" (गहरी indigo रात, एक marigold accent) */
object ForgeTheme {
    const val BG = 0xFF06050E.toInt()
    const val BG_TOP = 0xFF15113A.toInt()
    const val SURFACE = 0xFF17143A.toInt()
    const val RAISED = 0xFF221E4D.toInt()
    const val ACCENT = 0xFFFFB547.toInt()
    const val ACCENT2 = 0xFFFF8A3D.toInt()
    const val TEXT = 0xFFEEE9FF.toInt()
    const val TEXT2 = 0xFFA7A0CC.toInt()
    const val LOTUS = 0xFFFF6FAE.toInt()
    const val LAGOON = 0xFF3FE0D0.toInt()
    const val ERROR = 0xFFFF7A85.toInt()

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** Forge.init को जाने वाला theme JSON (scene में दूसरा palette नहीं) */
    fun json(): String = org.json.JSONObject().apply {
        put("background", hex(BG)); put("backgroundGradientTop", hex(BG_TOP)); put("surface", hex(SURFACE))
        put("accent", hex(ACCENT)); put("accentSecondary", hex(ACCENT2)); put("textPrimary", hex(TEXT))
        put("lotus", hex(LOTUS)); put("lagoon", hex(LAGOON))
    }.toString()
}
