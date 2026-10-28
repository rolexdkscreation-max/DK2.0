package com.dk.assistant.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.dk.assistant.DKState
import com.dk.assistant.Prefs

/**
 * v3.57 (Part 10): DK सुन/बोल रहा हो तो स्क्रीन के चारों किनारों पर हल्की चमक (~26dp)।
 * सुनना/सोचना = बैंगनी, बोलना = गुलाबी। छूने पर कुछ नहीं होता (नीचे का ऐप वैसे ही चलता है)।
 * DK चुप/बंद हो तो कुछ नहीं बनाता (बैटरी नहीं खाता)।
 */
class EdgeGlowView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val band = 26f * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color: Int? = null
    private var shown = 0f
    private var lastT = 0L
    private var reduced = false
    private var enabledCache = true
    private var cacheT = 0L
    private val onState: () -> Unit = { post { refresh() } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); DKState.listen(onState); refresh() }
    override fun onDetachedFromWindow() { DKState.unlisten(onState); super.onDetachedFromWindow() }

    private fun refresh() {
        val now = System.currentTimeMillis()
        if (now - cacheT > 1500) { cacheT = now; enabledCache = Prefs.edgeGlow(context); reduced = Motion.reduced(context) }
        val c = EdgeGlowRules.color(enabledCache, DKState.live, DKState.shownMode())
        if (c != null) color = c
        if (c != null || shown > 0f) { lastT = 0L; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceAtMost(0.05f)
        lastT = now
        val target = EdgeGlowRules.color(enabledCache, DKState.live, DKState.shownMode())
        if (target != null) color = target
        // धीरे से आना-जाना (~250ms)
        shown = when {
            reduced -> if (target != null) 1f else 0f
            target != null -> (shown + dt * 4f).coerceAtMost(1f)
            else -> (shown - dt * 4f).coerceAtLeast(0f)
        }
        val col = color ?: return
        if (shown <= 0f) return
        val inten = EdgeGlowRules.intensity(DKState.shownMode(), DKState.level, now / 1000f, reduced) * shown
        val a = (200 * inten).toInt().coerceIn(0, 255)
        val solid = (col and 0x00FFFFFF) or (a shl 24)
        val clear = col and 0x00FFFFFF
        val w = width.toFloat(); val h = height.toFloat()
        // ऊपर, नीचे, बाएँ, दाएँ
        edge(c, 0f, 0f, 0f, band, solid, clear, 0f, 0f, w, band)
        edge(c, 0f, h, 0f, h - band, solid, clear, 0f, h - band, w, h)
        edge(c, 0f, 0f, band, 0f, solid, clear, 0f, 0f, band, h)
        edge(c, w, 0f, w - band, 0f, solid, clear, w - band, 0f, w, h)
        if (!reduced || target == null) postInvalidateOnAnimation()
    }

    private fun edge(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, from: Int, to: Int,
                     l: Float, t: Float, r: Float, b: Float) {
        paint.shader = LinearGradient(x0, y0, x1, y1, from, to, Shader.TileMode.CLAMP)
        c.drawRect(l, t, r, b, paint)
    }
}
