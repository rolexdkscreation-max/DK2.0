package com.dk.assistant

/**
 * v3.40: स्क्रीन/कैमरा की कौन-सी तस्वीर Gemini को जाए (Android import नहीं -> JVM test).
 *  • हर [minGapMs] से जल्दी नहीं (~1 फ़्रेम/सेकंड - Live API की सलाह)
 *  • स्क्रीन वैसी ही रहे तो हर [idleGapMs] में एक (data कम, फिर भी Gemini को ताज़ा)
 * sig = छोटी (जैसे 16x32) धूसर तस्वीर के pixel - बदलाव नापने के लिए.
 */
class FrameGate(private val minGapMs: Long = 1000, private val idleGapMs: Long = 4000, private val diff: Int = 12) {
    private var lastAt = 0L
    private var lastSig: IntArray? = null

    fun shouldSend(now: Long, sig: IntArray): Boolean {
        if (lastAt != 0L && now - lastAt < minGapMs) return false
        val prev = lastSig
        val changed = prev == null || prev.size != sig.size || changedShare(prev, sig) > 0.01
        if (!changed && now - lastAt < idleGapMs) return false
        return true
    }

    /** तस्वीर सच में गई */
    fun sent(now: Long, sig: IntArray) { lastAt = now; lastSig = sig }

    private fun changedShare(a: IntArray, b: IntArray): Double {
        var n = 0
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > diff) n++
        return n.toDouble() / a.size.coerceAtLeast(1)
    }

    companion object {
        /** लंबी तरफ़ ज़्यादा से ज़्यादा [maxSide] px (बराबर संख्या) */
        fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0) return 2 to 2
            val s = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
            fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
            return even(w * s) to even(h * s)
        }
    }
}
