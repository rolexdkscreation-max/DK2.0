package com.dk.assistant.dkai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 14 नवंबर 2026 (मालिक: "Perplexity के लोगो जैसा - ऐप खुलते ही DK का लोगो 3D में घूमे").
 * लोगो की कई परतें (पीछे वाली गहरी) = मोटाई; Y-धुरी पर एक पूरा चक्कर, फिर हल्का-सा झूलना.
 * [Logo3DMath] में सारा हिसाब (टेस्ट वाला), यहाँ सिर्फ़ चित्र.
 */
class Logo3D(c: Context, res: Int, private val edgeColor: Int = 0xFF1E3A8A.toInt()) : View(c) {
    private val front: Drawable? = ContextCompat.getDrawable(c, res)?.mutate()
    private val edge: Drawable? = ContextCompat.getDrawable(c, res)?.constantState?.newDrawable()?.mutate()?.apply {
        colorFilter = PorterDuffColorFilter(edgeColor, PorterDuff.Mode.SRC_ATOP)
    }
    private val cam = Camera()
    private val mx = Matrix()
    private var angle = 0f
    private var spin: ValueAnimator? = null
    private var sway: ValueAnimator? = null

    init { contentDescription = "DK लोगो"; setLayerType(LAYER_TYPE_HARDWARE, null) }

    /** एक पूरा 3D चक्कर (दोबारा बुलाओ = फिर से घूमे), फिर धीमा झूलना */
    fun spin(ms: Long = Logo3DMath.SPIN_MS) {
        if (!ValueAnimator.areAnimatorsEnabled()) { angle = 0f; invalidate(); return }
        spin?.cancel(); sway?.cancel()
        spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = ms; interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (isAttachedToWindow && spin === a) startSway() }
            })
            start()
        }
    }

    private fun startSway() {
        sway = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Logo3DMath.SWAY_MS; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { angle = Logo3DMath.sway(it.animatedFraction); invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { spin?.cancel(); sway?.cancel(); spin = null; sway = null; super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val f = front ?: return
        val w = width; val h = height; if (w <= 0 || h <= 0) return
        val size = minOf(w, h); val l = (w - size) / 2; val t = (h - size) / 2
        f.setBounds(l, t, l + size, t + size); edge?.setBounds(l, t, l + size, t + size)
        val cx = w / 2f; val cy = h / 2f
        val depth = size * Logo3DMath.DEPTH
        cam.setLocation(0f, 0f, -size * 3f / 72f)          // कैमरा लोगो से 3 गुना दूर - बड़ा लोगो भी बिना टेढ़ापन
        for (i in Logo3DMath.order(angle, Logo3DMath.LAYERS)) {
            val z = depth * i / (Logo3DMath.LAYERS - 1).coerceAtLeast(1)
            cv.save()
            cam.save(); cam.rotateY(angle); cam.translate(0f, 0f, z - depth / 2f); cam.getMatrix(mx); cam.restore()
            mx.preTranslate(-cx, -cy); mx.postTranslate(cx, cy)
            cv.concat(mx)
            val d = if (i == 0 || i == Logo3DMath.LAYERS - 1) f else (edge ?: f)
            d.draw(cv)
            cv.restore()
        }
    }
}

/** 3D लोगो का हिसाब (Android के बिना - JVM टेस्ट) */
object Logo3DMath {
    const val SPIN_MS = 1100L
    const val SWAY_MS = 5200L
    const val LAYERS = 9
    /** मोटाई = लोगो के आकार का हिस्सा */
    const val DEPTH = 0.10f
    const val SWAY_DEG = 16f

    /** झूलना: 0..1 -> -SWAY_DEG..+SWAY_DEG (0 पर सीधा) */
    fun sway(f: Float): Float = (Math.sin(f * 2 * Math.PI) * SWAY_DEG).toFloat()

    /** पहले दूर वाली परत, आख़िर में पास वाली (आगे से दिखे तो 0 = सबसे पास) */
    fun order(angleDeg: Float, layers: Int): List<Int> {
        val facing = Math.cos(Math.toRadians(angleDeg.toDouble())) >= 0
        val all = (0 until layers).toList()
        return if (facing) all.reversed() else all
    }
}

/**
 * 14 नवंबर 2026 (मालिक: "DK Computer का आइकन, Perplexity Computer जैसा - काम करते समय छोटी-छोटी आँखें इधर-उधर घूमें").
 * छोटा मॉनिटर + दो आँखें. [working] = आँखें घूमें + पलक; नहीं = बीच में, कभी-कभी पलक.
 */
class ComputerEyes(c: Context) : View(c) {
    var color: Int = 0xFF9CA3AF.toInt(); set(v) { field = v; invalidate() }
    var working = false
        set(v) { if (field == v) return; field = v; if (isAttachedToWindow) restart() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val r = RectF()
    private var t = 0f
    private var anim: ValueAnimator? = null

    init { contentDescription = "DK Computer" }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restart() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    private fun restart() {
        anim?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { t = 0f; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (working) EyesMath.WORK_MS else EyesMath.IDLE_MS
            repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedFraction; invalidate() }
            start()
        }
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f || h <= 0f) return
        val s = minOf(w, h); val ox = (w - s) / 2f; val oy = (h - s) / 2f
        line.color = color; fill.color = color; line.strokeWidth = s * 0.085f
        // मॉनिटर
        r.set(ox + s * 0.10f, oy + s * 0.14f, ox + s * 0.90f, oy + s * 0.70f)
        cv.drawRoundRect(r, s * 0.12f, s * 0.12f, line)
        // स्टैंड
        cv.drawLine(ox + s * 0.50f, oy + s * 0.70f, ox + s * 0.50f, oy + s * 0.82f, line)
        cv.drawLine(ox + s * 0.32f, oy + s * 0.86f, ox + s * 0.68f, oy + s * 0.86f, line)
        // आँखें
        val (dx, dy) = EyesMath.look(t, working)
        val open = EyesMath.open(t, working)
        val ex = s * 0.11f; val ey = s * 0.13f * open
        val cy = oy + s * 0.42f + dy * s * 0.06f
        for (cx0 in floatArrayOf(0.36f, 0.64f)) {
            val cx = ox + s * cx0 + dx * s * 0.07f
            r.set(cx - ex / 2f, cy - ey / 2f, cx + ex / 2f, cy + ey / 2f)
            cv.drawRoundRect(r, ex / 2f, ex / 2f, fill)
        }
    }
}

/** आँखों का हिसाब (JVM टेस्ट): कहाँ देखें, कितनी खुली */
object EyesMath {
    const val WORK_MS = 2400L
    const val IDLE_MS = 4200L

    /** -1..1 (x, y). काम पर = इधर-उधर (Lissajous), खाली = बीच */
    fun look(t: Float, working: Boolean): Pair<Float, Float> {
        if (!working) return 0f to 0f
        val a = t * 2 * Math.PI
        return (Math.sin(a * 2).toFloat()) to (Math.sin(a * 3 + 0.6).toFloat() * 0.6f)
    }

    /** 1 = पूरी खुली, पलक के समय छोटी (कभी 0 नहीं - दिखती रहे) */
    fun open(t: Float, working: Boolean): Float {
        val blinkAt = if (working) floatArrayOf(0.30f, 0.80f) else floatArrayOf(0.90f)
        var o = 1f
        for (b in blinkAt) { val d = Math.abs(t - b); if (d < 0.03f) o = minOf(o, 0.15f + d / 0.03f * 0.85f) }
        return o
    }
}
