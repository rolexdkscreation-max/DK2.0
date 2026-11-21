package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 21 नवंबर 2026: ऐप लॉक / टच मोड के futuristic (निऑन) PIN पैड और पैटर्न.
 * PIN कभी दिखता नहीं - सिर्फ़ ● बिंदु. दोनों अपना काम [onDone] से बताते हैं.
 */
class NeonPinPad(context: Context, private val maxLen: Int = AppLockRules.PIN_MAX, private val autoLen: Int = 0) : View(context) {
    /** पूरा PIN (OK दबाया / [autoLen] अंक हुए) */
    var onDone: ((String) -> Unit)? = null
    var tone = HoloScanView.SCAN
        set(v) { field = v; invalidate() }
    private val sb = StringBuilder()
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private var pressed = -1
    private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")

    val length get() = sb.length
    fun clear() { sb.setLength(0); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val dotsH = (44 * d).toInt()
        setMeasuredDimension(width, dotsH + (width * 4 / 3).coerceAtMost((360 * d).toInt()))
    }

    private fun cell(): Triple<Float, Float, Float> {
        val top = 44 * d
        val cw = width / 3f; val ch = (height - top) / 4f
        return Triple(cw, ch, top)
    }

    override fun onDraw(c: Canvas) {
        val (cw, ch, top) = cell()
        // ऊपर बिंदु (कितने अंक डाले - अंक नहीं)
        val n = sb.length.coerceAtLeast(if (autoLen > 0) autoLen else AppLockRules.PIN_MIN)
        val gap = 20 * d
        val sx = width / 2f - (n - 1) * gap / 2f
        for (i in 0 until n) {
            val x = sx + i * gap; val y = 20 * d
            ring.color = tone; c.drawCircle(x, y, 6 * d, ring)
            if (i < sb.length) { fill.color = tone; c.drawCircle(x, y, 4.2f * d, fill) }
        }
        val r = min(cw, ch) * 0.36f
        txt.textSize = r * 0.72f
        for ((i, k) in keys.withIndex()) {
            val cx = (i % 3) * cw + cw / 2; val cy = top + (i / 3) * ch + ch / 2
            if (i == pressed) { glow.color = HoloScanView.alpha(tone, 0.35f); c.drawCircle(cx, cy, r + 3 * d, glow) }
            ring.color = HoloScanView.alpha(tone, if (i == pressed) 1f else 0.65f)
            c.drawCircle(cx, cy, r, ring)
            fill.color = HoloScanView.alpha(tone, if (i == pressed) 0.22f else 0.06f)
            c.drawCircle(cx, cy, r, fill)
            txt.color = if (k == "OK") HoloScanView.OK else 0xFFE6FBFF.toInt()
            if (k == "OK") txt.textSize = r * 0.5f else txt.textSize = r * 0.72f
            c.drawText(k, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val (cw, ch, top) = cell()
        val i = if (e.y < top) -1 else ((e.y - top) / ch).toInt().coerceIn(0, 3) * 3 + (e.x / cw).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (i >= 0 && i == pressed) press(keys[i])
                pressed = -1; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun press(k: String) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (k) {
            "⌫" -> if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
            "OK" -> if (sb.isNotEmpty()) { val p = sb.toString(); sb.setLength(0); onDone?.invoke(p) }
            else -> if (sb.length < maxLen) {
                sb.append(k)
                if (autoLen > 0 && sb.length == autoLen) { val p = sb.toString(); sb.setLength(0); onDone?.invoke(p) }
            }
        }
        invalidate()
    }
}

/** 3x3 निऑन पैटर्न. बिंदु 0..8 (ऊपर-बाएँ से). [hidden] = रेखा न दिखे (कोई पीछे से देख न ले) */
class NeonPattern(context: Context) : View(context) {
    var onDone: ((List<Int>) -> Unit)? = null
    var hidden = false
    var tone = HoloScanView.SCAN
        set(v) { field = v; invalidate() }
    private val path = ArrayList<Int>()
    private var fx = -1f; private var fy = -1f
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val p = Path()

    fun clear() { path.clear(); fx = -1f; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val s = min(MeasureSpec.getSize(w), (320 * d).toInt())
        setMeasuredDimension(s, s)
    }

    private fun cx(i: Int) = (i % 3) * width / 3f + width / 6f
    private fun cy(i: Int) = (i / 3) * height / 3f + height / 6f

    override fun onDraw(c: Canvas) {
        val r = width / 3f * 0.12f
        if (!hidden && path.isNotEmpty()) {
            p.reset(); p.moveTo(cx(path[0]), cy(path[0]))
            for (k in path.drop(1)) p.lineTo(cx(k), cy(k))
            if (fx >= 0) p.lineTo(fx, fy)
            line.color = HoloScanView.alpha(tone, 0.8f); c.drawPath(p, line)
        }
        for (i in 0..8) {
            val on = !hidden && i in path
            ring.color = HoloScanView.alpha(tone, if (on) 1f else 0.55f)
            c.drawCircle(cx(i), cy(i), r * 2.2f, ring)
            fill.color = if (on) tone else HoloScanView.alpha(tone, 0.7f)
            c.drawCircle(cx(i), cy(i), if (on) r * 1.1f else r * 0.6f, fill)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { path.clear(); hit(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> hit(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                fx = -1f
                val done = path.toList()
                invalidate()
                if (done.isNotEmpty()) onDone?.invoke(done)
                return true
            }
            MotionEvent.ACTION_CANCEL -> clear()
        }
        fx = e.x; fy = e.y; invalidate()
        return true
    }

    private fun hit(x: Float, y: Float) {
        val r = width / 3f * 0.32f
        for (i in 0..8) if (i !in path && hypot(x - cx(i), y - cy(i)) < r) {
            // बीच का बिंदु छूट गया हो (0 -> 2 = 1 भी) तो जोड़ो - असली फ़ोन जैसा
            path.lastOrNull()?.let { a -> mid(a, i)?.let { m -> if (m !in path) path.add(m) } }
            path.add(i)
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun mid(a: Int, b: Int): Int? {
        val ax = a % 3; val ay = a / 3; val bx = b % 3; val by = b / 3
        if ((ax + bx) % 2 != 0 || (ay + by) % 2 != 0) return null
        return ((ay + by) / 2) * 3 + (ax + bx) / 2
    }
}
