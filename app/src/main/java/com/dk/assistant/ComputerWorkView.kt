package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DK कंप्यूटर काम कर रहा है - ऊपर की animation.
 * ORB = धड़कता चमकता केंद्र + घूमती चाप + परिक्रमा करते 3 बिंदु ("सोच रहा है");
 * BAR = प्रगति पट्टी (योजना के कितने कदम पूरे) + उस पर चलती चमक.
 * ~30fps, सिर्फ़ दिखते समय चलता है (बैटरी / lag नहीं).
 */
class ComputerWorkView(c: Context, private val bar: Boolean) : View(c) {
    private val dp = resources.displayMetrics.density
    private val accent = 0xFF9FD3FF.toInt()
    private val accent2 = 0xFF3D7BFF.toInt()
    private val pink = 0xFFFF9DB0.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var glow: RadialGradient? = null
    private var glowR = 0f
    private var shine: LinearGradient? = null
    private var shineW = 0
    private val t0 = System.currentTimeMillis()
    @Volatile var running = false
        set(v) { field = v; if (v) postInvalidateOnAnimation() }
    /** 0..1 प्रगति (BAR) */
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    override fun onDraw(cv: Canvas) {
        val t = (System.currentTimeMillis() - t0) / 1000f
        if (bar) drawBar(cv, t) else drawOrb(cv, t)
        if (running && isShown) postInvalidateDelayed(FRAME_MS)
    }

    private fun drawOrb(cv: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2; val r = minOf(w, h) / 2
        val pulse = 0.5f + 0.5f * sin(t * 3.2f)
        // चमक
        val gr = r * (0.9f + 0.1f * pulse)
        if (glow == null || glowR != r) {
            glow = RadialGradient(0f, 0f, r, intArrayOf(0x993D7BFF.toInt(), 0x333D7BFF, 0x003D7BFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP); glowR = r
        }
        cv.save(); cv.translate(cx, cy); cv.scale(gr / r, gr / r)
        p.shader = glow; cv.drawCircle(0f, 0f, r, p); p.shader = null
        cv.restore()
        // केंद्र
        p.color = accent; p.alpha = (180 + 75 * pulse).toInt()
        cv.drawCircle(cx, cy, r * (0.26f + 0.05f * pulse), p)
        p.color = 0xFFFFFFFF.toInt(); p.alpha = 230
        cv.drawCircle(cx, cy, r * 0.11f, p)
        // घूमती चापें
        arc.strokeWidth = 2.6f * dp
        val ar = r * 0.62f
        rect.set(cx - ar, cy - ar, cx + ar, cy + ar)
        arc.color = accent; arc.alpha = 220
        cv.drawArc(rect, (t * 220f) % 360f, 100f, false, arc)
        arc.color = pink; arc.alpha = 200
        cv.drawArc(rect, (t * 220f + 180f) % 360f, 60f, false, arc)
        // परिक्रमा करते बिंदु
        val orb = r * 0.86f
        for (i in 0 until 3) {
            val a = t * (1.6f + i * 0.35f) + i * (2 * PI.toFloat() / 3)
            p.color = if (i == 1) pink else accent; p.alpha = 235
            cv.drawCircle(cx + orb * cos(a), cy + orb * sin(a), (2.6f + i * 0.6f) * dp, p)
        }
    }

    private fun drawBar(cv: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat(); val rr = h / 2
        // पटरी
        p.shader = null; p.color = 0x22FFFFFF
        rect.set(0f, 0f, w, h); cv.drawRoundRect(rect, rr, rr, p)
        // भरा हिस्सा (कम से कम थोड़ा - काम शुरू है)
        val fw = (w * fraction).coerceAtLeast(h * 2)
        p.color = accent2; p.alpha = 255
        rect.set(0f, 0f, fw, h); cv.drawRoundRect(rect, rr, rr, p)
        // चलती चमक
        if (shine == null || shineW != width) {
            shine = LinearGradient(0f, 0f, w * 0.3f, 0f, intArrayOf(0x00FFFFFF, 0xCC9FD3FF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP); shineW = width
        }
        val x = ((t * 0.55f) % 1f) * (w * 1.3f) - w * 0.3f
        cv.save(); cv.clipRect(0f, 0f, w, h); cv.translate(x, 0f)
        p.shader = shine; rect.set(0f, 0f, w * 0.3f, h); cv.drawRect(rect, p); p.shader = null
        cv.restore()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && running) postInvalidateOnAnimation()
    }

    companion object {
        const val FRAME_MS = 33L
    }
}
