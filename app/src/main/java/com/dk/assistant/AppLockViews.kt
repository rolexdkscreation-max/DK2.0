package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 21 नवंबर 2026: ऐप लॉक / टच मोड के PIN पैड और पैटर्न. PIN कभी दिखता नहीं - सिर्फ़ भरे खाने. दोनों अपना काम [onDone] से बताते हैं.
 *
 * 27 नवंबर 2026 (मालिक: "PIN वाला पैनल DK के UI जैसा हो - उभरे बटन, वही पैनल; ऊपर-नीचे चलने वाला स्कैन नहीं"):
 * पूरा पैड एक उभरा Clay पैनल ([com.dk.assistant.ui.Clay.card] - Settings के कार्ड जैसा), हर अंक एक उभरा गोल Clay बटन
 * ([com.dk.assistant.ui.Clay.circle] - दबाने पर धँसता है), OK = accent वाला गोल बटन, ऊपर धँसे गोल खाने (कितने अंक डाले).
 * पुराना HUD (षट्कोण, घूमता स्कैन, कोनों के ब्रैकेट, अंग्रेज़ी HUD लिखावट, लगातार चलता एनिमेशन) हटा.
 * गलत = लाल + हिलना ([reject]), सही = हरा ([grant]) - वैसे ही.
 * [autoLen] > 0 = इतने अंक होते ही ख़ुद [onDone] (OK की ज़रूरत नहीं) - तब OK की जगह "CLR" (सब मिटाओ).
 */
class NeonPinPad(context: Context, private val maxLen: Int = AppLockRules.PIN_MAX, private val autoLen: Int = 0) : View(context) {
    /** पूरा PIN (OK दबाया / [autoLen] अंक हुए) */
    var onDone: ((String) -> Unit)? = null
    /** भरे खानों और खास बटनों का रंग: थीम का accent (गलत = लाल, सही = हरा) */
    var tone = com.dk.assistant.ui.ClayTheme.t.accent
        set(v) { field = v; invalidate() }
    private val sb = StringBuilder()
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private var pressed = -1
    private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", if (autoLen > 0) "CLR" else "OK")
    /** हर बटन का अपना उभरा Clay गोला (ऐप के बाकी गोल बटनों वाला ही) */
    private val keyBg: List<Drawable> = keys.map { k ->
        com.dk.assistant.ui.Clay.circle(context, KEY_DEPTH_DP, accent = k == "OK").also { it.callback = this }
    }
    /** ऊपर के धँसे गोल खाने (ऐप के text box जैसे) */
    private val slotBg = ArrayList<Drawable>()

    init {
        background = com.dk.assistant.ui.Clay.card(context)
        val p = (PAD_DP * d).toInt(); setPadding(p, p, p, p)
        contentDescription = "PIN"
    }

    val length get() = sb.length
    fun clear() { sb.setLength(0); invalidate() }

    /** गलत: लाल + पैड हिलता है, फिर थीम का रंग */
    fun reject() {
        tone = HoloScanView.BAD; sb.setLength(0)
        animate().cancel(); translationX = 0f
        val s = 10 * d
        animate().translationX(s).setDuration(SHAKE_MS).withEndAction {
            animate().translationX(-s).setDuration(SHAKE_MS).withEndAction {
                animate().translationX(s / 2).setDuration(SHAKE_MS).withEndAction { animate().translationX(0f).setDuration(SHAKE_MS).start() }.start()
            }.start()
        }.start()
        postDelayed({ tone = com.dk.assistant.ui.ClayTheme.t.accent }, BAD_MS)
    }

    /** सही: हरा */
    fun grant() { tone = HoloScanView.OK }

    override fun verifyDrawable(who: Drawable): Boolean = who in keyBg || who in slotBg || super.verifyDrawable(who)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val grid = (inner * 4 / 3).coerceAtMost((360 * d).toInt())
        setMeasuredDimension(width, paddingTop + (TOP_DP * d).toInt() + grid + paddingBottom)
    }

    /** (खाने की चौड़ाई, ऊँचाई, बटनों की शुरुआत) */
    private fun cell(): Triple<Float, Float, Float> {
        val top = paddingTop + TOP_DP * d
        val cw = (width - paddingLeft - paddingRight) / 3f; val ch = (height - paddingBottom - top) / 4f
        return Triple(cw, ch, top)
    }

    private fun keyAt(x: Float, y: Float): Int {
        val (cw, ch, top) = cell()
        if (y < top || cw <= 0f || ch <= 0f) return -1
        val col = ((x - paddingLeft) / cw).toInt().coerceIn(0, 2)
        return ((y - top) / ch).toInt().coerceIn(0, 3) * 3 + col
    }

    private fun setPressedKey(i: Int) {
        if (i == pressed) return
        if (pressed >= 0) keyBg[pressed].state = IntArray(0)
        pressed = i
        if (i >= 0) keyBg[i].state = intArrayOf(android.R.attr.state_pressed)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val ct = com.dk.assistant.ui.ClayTheme.t
        val (cw, ch, top) = cell()
        drawSlots(c)
        val r = min(cw, ch) * KEY_FRAC / 2f
        for ((i, k) in keys.withIndex()) {
            val cx = paddingLeft + (i % 3) * cw + cw / 2; val cy = top + (i / 3) * ch + ch / 2
            keyBg[i].setBounds((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
            keyBg[i].draw(c)
            val special = k == "OK" || k == "CLR" || k == "⌫"
            txt.color = when { k == "OK" -> ct.onAccent; special -> tone; else -> ct.text }
            txt.textSize = if (k == "OK" || k == "CLR") r * 0.46f else r * 0.72f
            c.drawText(k, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
        }
    }

    /** ऊपर धँसे गोल खाने - भरे = कितने अंक (अंक नहीं दिखते) */
    private fun drawSlots(c: Canvas) {
        val n = sb.length.coerceAtLeast(if (autoLen > 0) autoLen else AppLockRules.PIN_MIN)
        while (slotBg.size < n) slotBg.add(com.dk.assistant.ui.Clay.inset(context, com.dk.assistant.ui.ClayDrawable.FULL, 2f).also { it.callback = this })
        val gap = 28 * d
        val sx = paddingLeft + (width - paddingLeft - paddingRight) / 2f - (n - 1) * gap / 2f
        val y = paddingTop + TOP_DP * d / 2f
        val sr = 10 * d
        for (i in 0 until n) {
            val x = sx + i * gap
            slotBg[i].setBounds((x - sr).toInt(), (y - sr).toInt(), (x + sr).toInt(), (y + sr).toInt())
            slotBg[i].draw(c)
            if (i < sb.length) { fill.color = tone; c.drawCircle(x, y, 5 * d, fill) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = keyAt(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressedKey(i)
            MotionEvent.ACTION_MOVE -> if (i != pressed) setPressedKey(-1)
            MotionEvent.ACTION_UP -> {
                val was = pressed
                setPressedKey(-1)
                if (i >= 0 && i == was) press(keys[i])
            }
            MotionEvent.ACTION_CANCEL -> setPressedKey(-1)
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
        const val PAD_DP = 14
        const val SHAKE_MS = 45L
        const val BAD_MS = 650L
        const val KEY_DEPTH_DP = 5f
        /** बटन खाने का कितना हिस्सा घेरे (बीच में उभार-छाया के लिए जगह) */
        const val KEY_FRAC = 0.78f
    }
}

/** 3x3 निऑन पैटर्न. बिंदु 0..8 (ऊपर-बाएँ से). [hidden] = रेखा न दिखे (कोई पीछे से देख न ले) */
class NeonPattern(context: Context) : View(context) {
    var onDone: ((List<Int>) -> Unit)? = null
    var hidden = false
    var tone = com.dk.assistant.ui.ClayTheme.t.accent
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
        val k = com.dk.assistant.ui.ClayTheme.t
        val o = 2.5f * d
        for (i in 0..8) {
            val on = !hidden && i in path
            // 23 नवंबर 2026 (मालिक): Clay - उभरा गोल खाना, जुड़ने पर धँसा
            if (!on) {
                fill.color = k.shadowDark; c.drawCircle(cx(i) + o, cy(i) + o, r * 2.2f, fill)
                fill.color = k.shadowLight; c.drawCircle(cx(i) - o, cy(i) - o, r * 2.2f, fill)
            }
            fill.color = if (on) k.well else k.surface; c.drawCircle(cx(i), cy(i), r * 2.2f, fill)
            ring.color = HoloScanView.alpha(tone, if (on) 1f else 0.3f)
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
