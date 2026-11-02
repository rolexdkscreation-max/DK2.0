package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * फ़ोन लॉक / अनलॉक: अपना लॉक-स्क्रीन पैटर्न यहाँ बनाइए (3x3 बिंदु, ऊपर बाएँ = 1 … नीचे दाएँ = 9)।
 * Android की तरह बीच वाला बिंदु अपने-आप जुड़ता है ([PhoneLockVoice.between]) - ताकि सेव हुआ क्रम असली जैसा ही रहे।
 */
class PatternPadView(c: Context) : View(c) {
    /** उँगली उठी - बना हुआ क्रम (जैसे "14789") */
    var onDone: ((String) -> Unit)? = null
    private val seq = StringBuilder()
    private var fx = -1f; private var fy = -1f
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC4FC3F7.toInt(); strokeWidth = 6f * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
    }

    val pattern: String get() = seq.toString()
    fun clear() { seq.setLength(0); fx = -1f; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w).coerceAtMost((280 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(s, s)
    }

    private fun center(d: Char) = PhoneLockVoice.cell(d, 0, 0, width, height)

    private fun hit(x: Float, y: Float): Char? {
        val r = min(width, height) / 3f * 0.38f
        return ('1'..'9').firstOrNull { val (cx, cy) = center(it); hypot(x - cx, y - cy) <= r }
    }

    private fun add(d: Char) {
        if (seq.contains(d)) return
        seq.lastOrNull()?.let { last -> PhoneLockVoice.between(last, d)?.let { m -> if (!seq.contains(m)) seq.append(m) } }
        seq.append(d)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { clear(); parent?.requestDisallowInterceptTouchEvent(true); hit(e.x, e.y)?.let { add(it) } }
            MotionEvent.ACTION_MOVE -> hit(e.x, e.y)?.let { add(it) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { fx = -1f; invalidate(); onDone?.invoke(pattern); return true }
        }
        fx = e.x; fy = e.y; invalidate(); return true
    }

    override fun onDraw(cv: Canvas) {
        val r = min(width, height) / 3f * 0.09f
        for (i in 0 until seq.length - 1) {
            val (x1, y1) = center(seq[i]); val (x2, y2) = center(seq[i + 1]); cv.drawLine(x1, y1, x2, y2, line)
        }
        if (fx >= 0 && seq.isNotEmpty()) { val (x, y) = center(seq.last()); cv.drawLine(x, y, fx, fy, line) }
        for (d in '1'..'9') { val (x, y) = center(d); cv.drawCircle(x, y, if (seq.contains(d)) r * 1.6f else r, if (seq.contains(d)) on else dot) }
    }
}
