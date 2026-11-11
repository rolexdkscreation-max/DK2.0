package com.dk.assistant.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import com.dk.assistant.DKState
import com.dk.assistant.Prefs

/**
 * v3.57 (Part 10): DK सुन/बोल रहा हो तो स्क्रीन के चारों किनारों पर चमक। छूने पर कुछ नहीं होता।
 * v4.x: रंग-बिरंगी चमक - रंग एक के बाद एक किनारों पर घूमते हैं, और चमक ठीक स्क्रीन के किनारे से
 *       चिपककर चलती है - कोनों की गोलाई के साथ-साथ (थोड़ा दूर नहीं)।
 * चमक की परतें: बाहर चौड़ी हल्की धुंध -> अंदर पतली तेज़ रेखा (ठीक किनारे पर)।
 * DK चुप/बंद हो तो कुछ नहीं बनाता (बैटरी नहीं खाता)। Shader एक ही बार बनता है, हर frame सिर्फ़ घुमाया जाता है।
 */
class EdgeGlowView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val box = RectF()
    private val m = Matrix()
    private var shader: SweepGradient? = null
    private var corner = 0f
    private var show = false
    private var shown = 0f
    private var lastT = 0L
    private var spin = 0f
    private var reduced = false
    private var enabledCache = true
    private var cacheT = 0L
    private val onState: () -> Unit = { post { refresh() } }

    /** परतें: चौड़ाई (dp), तेज़ी - बाहर से अंदर */
    private val layers = arrayOf(floatArrayOf(30f, 0.10f), floatArrayOf(18f, 0.18f), floatArrayOf(9f, 0.34f), floatArrayOf(4f, 0.62f), floatArrayOf(1.8f, 1f))

    override fun onAttachedToWindow() { super.onAttachedToWindow(); DKState.listen(onState); refresh() }
    override fun onDetachedFromWindow() { DKState.unlisten(onState); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        corner = screenCorner()
        shader = if (w > 0 && h > 0) SweepGradient(w / 2f, h / 2f, EdgeGlowRules.RAINBOW, null) else null
        path.rewind()
        // रेखा ठीक किनारे पर: बीच की पतली तेज़ रेखा पूरी दिखे, इसलिए उसकी आधी मोटाई अंदर
        val inset = 0.9f * dp
        box.set(inset, inset, w - inset, h - inset)
        val r = (corner - inset).coerceAtLeast(0f)
        path.addRoundRect(box, r, r, Path.Direction.CW)
    }

    /** स्क्रीन के कोने की असली गोलाई (Android 12+); पुराने फ़ोन पर सिस्टम वाला नाप, वो भी न मिले तो सीधा कोना */
    private fun screenCorner(): Float {
        if (Build.VERSION.SDK_INT >= 31) {
            val ins = rootWindowInsets
            if (ins != null) {
                var best = 0
                for (pos in intArrayOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                        RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)) {
                    ins.getRoundedCorner(pos)?.let { if (it.radius > best) best = it.radius }
                }
                if (best > 0) return best.toFloat()
            }
        }
        return try {
            val id = android.content.res.Resources.getSystem().getIdentifier("rounded_corner_radius", "dimen", "android")
            if (id != 0) android.content.res.Resources.getSystem().getDimension(id) else 0f
        } catch (_: Exception) { 0f }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - cacheT > 1500) { cacheT = now; enabledCache = Prefs.edgeGlow(context); reduced = Motion.reduced(context) }
        show = EdgeGlowRules.color(enabledCache, DKState.live, DKState.shownMode()) != null
        if (show || shown > 0f) { lastT = 0L; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceAtMost(0.05f)
        lastT = now
        val mode = DKState.shownMode()
        val target = EdgeGlowRules.color(enabledCache, DKState.live, mode) != null
        show = target
        // धीरे से आना-जाना (~250ms)
        shown = when {
            reduced -> if (target) 1f else 0f
            target -> (shown + dt * 4f).coerceAtMost(1f)
            else -> (shown - dt * 4f).coerceAtLeast(0f)
        }
        val sh = shader ?: return
        if (shown <= 0f) return
        val inten = EdgeGlowRules.intensity(mode, DKState.level, now / 1000f, reduced) * shown
        // रंग एक के बाद एक घूमें
        spin = (spin + 360f * EdgeGlowRules.flowSpeed(mode, DKState.level, reduced) * dt) % 360f
        m.setRotate(spin, width / 2f, height / 2f)
        sh.setLocalMatrix(m)
        paint.shader = sh
        for (l in layers) {
            paint.strokeWidth = l[0] * dp
            paint.alpha = (255 * l[1] * (0.35f + 0.65f * inten)).toInt().coerceIn(0, 255)
            c.drawPath(path, paint)
        }
        if (!reduced || !target) postInvalidateOnAnimation()
    }
}
