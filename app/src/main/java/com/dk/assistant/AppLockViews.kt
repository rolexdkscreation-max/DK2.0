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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.min

/**
 * 21 नवंबर 2026: ऐप लॉक / टच मोड के futuristic (निऑन) PIN पैड और पैटर्न.
 * PIN कभी दिखता नहीं - सिर्फ़ ● बिंदु. दोनों अपना काम [onDone] से बताते हैं.
 */
/**
 * 22 नवंबर 2026 (मालिक: "PIN वाला पैनल थोड़ा और फ़्यूचरिस्टिक बनाओ"): HUD जैसा पैड - षट्कोण (hex) बटन, घूमता स्कैन,
 * कोनों पर HUD ब्रैकेट, ऊपर षट्कोण खाने (कितने अंक डाले - अंक कभी नहीं), दबाने पर फैलती लहर.
 * गलत = लाल + हिलना ([reject]), सही = हरी लहर ([grant]).
 * [autoLen] > 0 = इतने अंक होते ही ख़ुद [onDone] (OK की ज़रूरत नहीं) - तब OK की जगह "CLR" (सब मिटाओ).
 */
class NeonPinPad(context: Context, private val maxLen: Int = AppLockRules.PIN_MAX, private val autoLen: Int = 0) : View(context) {
    /** पूरा PIN (OK दबाया / [autoLen] अंक हुए) */
    var onDone: ((String) -> Unit)? = null
    var tone = HoloScanView.SCAN
        set(v) { field = v; invalidate() }
    private val sb = StringBuilder()
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * d; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val hex = Path()
    private var pressed = -1
    private var tapKey = -1
    private var tapAt = 0L
    private var pulseAt = 0L
    private val born = System.currentTimeMillis()
    private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", if (autoLen > 0) "CLR" else "OK")

    val length get() = sb.length
    fun clear() { sb.setLength(0); invalidate() }

    /** गलत: लाल चमक + पैड हिलता है, फिर नीला */
    fun reject() {
        tone = HoloScanView.BAD; sb.setLength(0)
        animate().cancel(); translationX = 0f
        val s = 10 * d
        animate().translationX(s).setDuration(SHAKE_MS).withEndAction {
            animate().translationX(-s).setDuration(SHAKE_MS).withEndAction {
                animate().translationX(s / 2).setDuration(SHAKE_MS).withEndAction { animate().translationX(0f).setDuration(SHAKE_MS).start() }.start()
            }.start()
        }.start()
        postDelayed({ tone = HoloScanView.SCAN }, BAD_MS)
    }

    /** सही: हरी लहर */
    fun grant() { tone = HoloScanView.OK; pulseAt = System.currentTimeMillis(); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val dotsH = (TOP_DP * d).toInt()
        setMeasuredDimension(width, dotsH + (width * 4 / 3).coerceAtMost((360 * d).toInt()))
    }

    private fun cell(): Triple<Float, Float, Float> {
        val top = TOP_DP * d
        val cw = width / 3f; val ch = (height - top) / 4f
        return Triple(cw, ch, top)
    }

    /** नुकीले सिरे ऊपर वाला षट्कोण */
    private fun hexPath(cx: Float, cy: Float, r: Float): Path {
        hex.reset()
        for (k in 0 until 6) {
            val a = Math.toRadians(60.0 * k - 90.0)
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (k == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close(); return hex
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val t = (now - born) / 1000f
        val (cw, ch, top) = cell()
        drawFrame(c, t)
        drawSlots(c, t)
        val r = min(cw, ch) * 0.40f
        for ((i, k) in keys.withIndex()) {
            val cx = (i % 3) * cw + cw / 2; val cy = top + (i / 3) * ch + ch / 2
            val on = i == pressed
            val p = hexPath(cx, cy, r)
            // भीतर हल्की चमक, बाहर पतली रेखा, दबाने पर मोटी चमक
            fill.color = HoloScanView.alpha(tone, if (on) 0.28f else 0.07f); c.drawPath(p, fill)
            if (on) { glow.color = HoloScanView.alpha(tone, 0.35f); c.drawPath(p, glow) }
            ring.color = HoloScanView.alpha(tone, if (on) 1f else 0.6f); c.drawPath(p, ring)
            // भीतरी छोटा षट्कोण (HUD)
            ring.color = HoloScanView.alpha(tone, 0.18f); c.drawPath(hexPath(cx, cy, r * 0.78f), ring)
            // दबाने के बाद फैलती लहर
            val age = now - tapAt
            if (i == tapKey && age < RIPPLE_MS) {
                val f = age / RIPPLE_MS.toFloat()
                ring.color = HoloScanView.alpha(tone, 0.8f * (1f - f)); c.drawPath(hexPath(cx, cy, r * (1f + 0.45f * f)), ring)
            }
            val special = k == "OK" || k == "CLR"
            txt.color = when { k == "OK" -> HoloScanView.OK; special -> HoloScanView.alpha(tone, 0.9f); else -> 0xFFE6FBFF.toInt() }
            txt.textSize = if (special) r * 0.42f else r * 0.66f
            c.drawText(k, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
        }
        // सही PIN पर हरी लहर पूरे पैड पर
        val pa = now - pulseAt
        if (pulseAt > 0 && pa < PULSE_MS) {
            val f = pa / PULSE_MS.toFloat()
            glow.color = HoloScanView.alpha(HoloScanView.OK, 0.6f * (1f - f))
            c.drawCircle(width / 2f, top + (height - top) / 2f, (height - top) * 0.7f * f, glow)
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** कोनों के HUD ब्रैकेट + ऊपर से नीचे चलती स्कैन-रेखा + छोटी लिखावट */
    private fun drawFrame(c: Canvas, t: Float) {
        val L = 18 * d; val w = width.toFloat(); val h = height.toFloat(); val i = 2 * d
        ring.color = HoloScanView.alpha(tone, 0.85f)
        c.drawLine(i, i, i + L, i, ring); c.drawLine(i, i, i, i + L, ring)
        c.drawLine(w - i, i, w - i - L, i, ring); c.drawLine(w - i, i, w - i, i + L, ring)
        c.drawLine(i, h - i, i + L, h - i, ring); c.drawLine(i, h - i, i, h - i - L, ring)
        c.drawLine(w - i, h - i, w - i - L, h - i, ring); c.drawLine(w - i, h - i, w - i, h - i - L, ring)
        val top = TOP_DP * d
        val y = top + (h - top) * ((t / SCAN_S) % 1f)
        fill.color = HoloScanView.alpha(tone, 0.10f); c.drawRect(i, y - 10 * d, w - i, y, fill)
        ring.color = HoloScanView.alpha(tone, 0.35f); c.drawLine(i, y, w - i, y, ring)
        txt.textSize = 9 * d; txt.color = HoloScanView.alpha(tone, 0.7f)
        val label = when (tone) { HoloScanView.OK -> "ACCESS GRANTED"; HoloScanView.BAD -> "ACCESS DENIED"; else -> "DK SECURE INPUT // LIVE" }
        c.drawText(label, w / 2f, 12 * d, txt)
    }

    /** ऊपर षट्कोण खाने - भरे = कितने अंक (अंक नहीं दिखते) */
    private fun drawSlots(c: Canvas, t: Float) {
        val n = sb.length.coerceAtLeast(if (autoLen > 0) autoLen else AppLockRules.PIN_MIN)
        val gap = 24 * d
        val sx = width / 2f - (n - 1) * gap / 2f
        val y = 34 * d
        val beat = 0.6f + 0.4f * sin(t * 4f)
        for (i in 0 until n) {
            val x = sx + i * gap
            ring.color = HoloScanView.alpha(tone, 0.8f); c.drawPath(hexPath(x, y, 8 * d), ring)
            if (i < sb.length) {
                glow.color = HoloScanView.alpha(tone, 0.25f); c.drawCircle(x, y, 6 * d, glow)
                fill.color = tone; c.drawPath(hexPath(x, y, 5 * d), fill)
            } else if (i == sb.length) {
                fill.color = HoloScanView.alpha(tone, 0.25f * beat); c.drawPath(hexPath(x, y, 5 * d), fill)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val (cw, ch, top) = cell()
        val i = if (e.y < top) -1 else ((e.y - top) / ch).toInt().coerceIn(0, 3) * 3 + (e.x / cw).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = i; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (i >= 0 && i == pressed) { tapKey = i; tapAt = System.currentTimeMillis(); press(keys[i]) }
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
            "CLR" -> sb.setLength(0)
            "OK" -> if (sb.isNotEmpty()) { val p = sb.toString(); sb.setLength(0); onDone?.invoke(p) }
            else -> if (sb.length < maxLen) {
                sb.append(k)
                if (autoLen > 0 && sb.length == autoLen) { val p = sb.toString(); sb.setLength(0); onDone?.invoke(p) }
            }
        }
        invalidate()
    }

    private companion object {
        const val TOP_DP = 54
        const val SHAKE_MS = 45L
        const val BAD_MS = 650L
        const val RIPPLE_MS = 380L
        const val PULSE_MS = 500L
        const val SCAN_S = 2.6f
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
