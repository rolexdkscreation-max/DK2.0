package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * नवंबर 2026 (मालिक, संदर्भ चित्र): होम के नीचे का चमकता नियॉन माइक - बीच में माइक, चारों ओर फैलते छल्ले.
 * छल्ले असली DKState से: खाली = धीमे-हल्के, सुनते समय आवाज़ के ज़ोर से तेज़/बड़े. कम-एनिमेशन में स्थिर.
 * सिर्फ़ दिखावट; दबाने का काम MainActivity (openVoice) ही करता है.
 */
class NeonMicView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mic = context.getDrawable(R.drawable.ic_mic)?.mutate()
    private var key = -1
    private var lvl = 0f

    init {
        isClickable = true; isFocusable = true
        if (contentDescription == null) contentDescription = "बोलने के लिए टैप करें"
    }

    override fun onDraw(c: Canvas) {
        val T = com.dk.assistant.ui.NeonTheme
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val R = minOf(w, h) * 0.30f
        val l = T.look
        val a = T.mix(l.main, T.CYAN, 0.55f); val b = l.partner
        if (key != T.version) {
            key = T.version
            glow.shader = RadialGradient(cx, cy, minOf(w, h) / 2f, intArrayOf(T.alpha(a, 0x88), T.alpha(b, 0x22), T.alpha(b, 0)),
                floatArrayOf(0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
            core.shader = RadialGradient(cx - R * 0.3f, cy - R * 0.35f, R * 1.4f, intArrayOf(T.mix(a, 0xFFFFFFFF.toInt(), 0.35f), a, T.mix(b, 0xFF0A0F3A.toInt(), 0.3f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            mic?.setTint(0xFF06102A.toInt())
        }
        val still = T.reducedMotion()
        val listening = DKState.mode == Mode.LISTENING || DKState.live
        lvl += ((if (listening) DKState.level else 0f) - lvl) * 0.2f
        c.drawCircle(cx, cy, minOf(w, h) / 2f, glow)
        // फैलते छल्ले
        val t = if (still) 0f else SystemClock.uptimeMillis() / 1000f
        val spd = if (listening) 0.9f + lvl else 0.35f
        val dpx = resources.displayMetrics.density
        for (i in 0 until 3) {
            val f = ((t * spd + i / 3f) % 1f)
            val rr = R * (1.05f + f * (0.62f + lvl * 0.3f))
            ring.strokeWidth = dpx * (1.6f - f)
            ring.color = T.alphaF(a, (1f - f) * (if (listening) 0.9f else 0.55f))
            c.drawCircle(cx, cy, rr, ring)
        }
        c.drawCircle(cx, cy, R, core)
        mic?.let { d ->
            val s = (R * 0.62f).toInt()
            d.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt()); d.draw(c)
        }
        if (!still && isShown) postInvalidateDelayed(if (listening) 16L else 40L)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) invalidate() }
}
