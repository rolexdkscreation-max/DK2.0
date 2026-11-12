package com.dk.assistant.voice.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * नवंबर 2026 (मालिक: Supertonic आवाज़ को अपने हिसाब से): Supertonic की बनी आवाज़ पर बाद में लगने वाले इफ़ेक्ट.
 *
 * - बास: 160 Hz के नीचे low-shelf (0 dB = बंद, ज़्यादा से ज़्यादा +12 dB) - आवाज़ में गहराई.
 * - रिवर्ब: हल्की गूँज (Schroeder: 4 comb + 2 all-pass) - 0 = बंद. आवाज़ भरी-भरी लगे, कमरे जैसी नहीं.
 * - दोनों बंद = आवाज़ बिल्कुल वैसी ही (एक भी sample नहीं बदलता).
 * - ज़्यादा ज़ोर हो तो 0.9 के ऊपर नरम सीमा (soft clip) - फटी आवाज़ नहीं.
 *
 * बिना Android के (JVM test). एक [SupertonicFx] = एक टुकड़ा (वाक्य) - हर टुकड़े पर नया.
 */
class SupertonicFx(private val rate: Int, bassDb: Float = 0f, reverb: Float = 0f) {
    private val bass = bassDb.coerceIn(0f, MAX_BASS_DB)
    private val wet = reverb.coerceIn(0f, 1f)
    val identity: Boolean get() = bass <= 0f && wet <= 0f

    // ---- बास (RBJ low-shelf biquad)
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    /** बास बढ़े तो पूरी आवाज़ थोड़ी धीमी, ताकि ज़ोर बढ़कर फटे नहीं */
    private val bassTrim: Double = if (bass > 0f) 10.0.pow(-bass / 80.0) else 1.0

    // ---- रिवर्ब
    private val combs: Array<DoubleArray>
    private val combPos = IntArray(4)
    private val combLp = DoubleArray(4)
    private val aps: Array<DoubleArray>
    private val apPos = IntArray(2)
    private val feedback = 0.72 + 0.12 * wet
    private val damp = 0.35

    init {
        if (bass > 0f) {
            val a = 10.0.pow(bass / 40.0)
            val w = 2 * PI * BASS_HZ / rate
            val cw = cos(w); val sw = sin(w)
            val alpha = sw / 2 * sqrt(2.0)          // S = 1
            val sa = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * cw + sa
            b0 = a * ((a + 1) - (a - 1) * cw + sa) / a0
            b1 = 2 * a * ((a - 1) - (a + 1) * cw) / a0
            b2 = a * ((a + 1) - (a - 1) * cw - sa) / a0
            a1 = -2 * ((a - 1) + (a + 1) * cw) / a0
            a2 = ((a + 1) + (a - 1) * cw - sa) / a0
        }
        combs = if (wet > 0f) COMB_MS.map { DoubleArray(maxOf(1, (rate * it / 1000).toInt())) }.toTypedArray() else emptyArray()
        aps = if (wet > 0f) AP_MS.map { DoubleArray(maxOf(1, (rate * it / 1000).toInt())) }.toTypedArray() else emptyArray()
    }

    /** एक हिस्सा (-1..1 float). बंद = वही array लौटता है */
    fun process(x: FloatArray): FloatArray {
        if (identity) return x
        val y = FloatArray(x.size)
        for (i in x.indices) y[i] = one(x[i].toDouble()).toFloat()
        return y
    }

    /** रिवर्ब की बची गूँज (टुकड़े के बाद, धीरे-धीरे शांत). रिवर्ब बंद = खाली */
    fun tail(ms: Int = TAIL_MS): FloatArray {
        if (wet <= 0f) return FloatArray(0)
        val n = rate * ms / 1000
        val y = FloatArray(n)
        for (i in 0 until n) y[i] = (one(0.0) * (1.0 - i.toDouble() / n)).toFloat()
        return y
    }

    private fun one(inp: Double): Double {
        var v = inp
        if (bass > 0f) {
            val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v; y2 = y1; y1 = o
            v = o * bassTrim
        }
        if (wet > 0f) {
            var r = 0.0
            for (k in combs.indices) {
                val buf = combs[k]; val p = combPos[k]
                val out = buf[p]
                combLp[k] = out * (1 - damp) + combLp[k] * damp
                buf[p] = v + combLp[k] * feedback
                combPos[k] = if (p + 1 >= buf.size) 0 else p + 1
                r += out
            }
            r /= combs.size
            for (k in aps.indices) {
                val buf = aps[k]; val p = apPos[k]
                val bo = buf[p]
                val o = -r + bo
                buf[p] = r + bo * 0.5
                apPos[k] = if (p + 1 >= buf.size) 0 else p + 1
                r = o
            }
            v = v * (1 - 0.18 * wet) + r * (0.45 * wet)
        }
        return soft(v)
    }

    companion object {
        const val BASS_HZ = 160.0
        const val MAX_BASS_DB = 12f
        const val TAIL_MS = 350
        private val COMB_MS = doubleArrayOf(29.7, 37.1, 41.1, 43.7)
        private val AP_MS = doubleArrayOf(5.0, 1.7)

        /** 0.9 तक जस का तस, उसके ऊपर नरम मोड़ - कभी 1 के पार नहीं */
        fun soft(v: Double): Double {
            val a = abs(v)
            if (a <= 0.9) return v
            val s = 0.9 + 0.1 * tanh((a - 0.9) / 0.1)
            return if (v < 0) -s else s
        }

        /**
         * पिच बिना रफ़्तार बदले: आवाज़ [speed]/[pitch] रफ़्तार पर बनाओ, फिर sample-rate को [pitch] गुना मानकर 24 kHz पर लाओ.
         * (पिच 1.2 = 20% ऊँची; उतनी ही तेज़ भी होती - इसलिए बनाते समय 1.2 गुना धीमी). दोनों 1 = कोई बदलाव नहीं.
         */
        fun genSpeed(speed: Float, pitch: Float): Float = (speed.coerceIn(0.5f, 2f) / pitch.coerceIn(0.7f, 1.4f)).coerceIn(0.3f, 3f)

        fun srcRate(rate: Int, pitch: Float): Int = (rate * pitch.coerceIn(0.7f, 1.4f)).roundToInt()

        /** वाक्यों के बीच का ठहराव: [base] ms × प्रतिशत (100 = पहले जैसा) */
        fun gap(base: Int, pct: Int): Int = (base.toLong() * pct.coerceIn(0, 300) / 100).toInt()

        /** float (-1..1) -> PCM 16-bit LE */
        fun pcm16(x: FloatArray): ByteArray {
            val o = ByteArray(x.size * 2)
            for (i in x.indices) {
                val v = (x[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                o[i * 2] = (v and 0xff).toByte(); o[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            return o
        }
    }
}

/**
 * नवंबर 2026: HQ (44.1 kHz) आवाज़ को 24 kHz पर लाने से पहले हल्का low-pass (2 biquad = 4th order Butterworth).
 * एक वाक्य = एक object (हालत वाक्य भर चलती है).
 */
class SoftLowpass(rate: Int, cutHz: Double) {
    private val s = Array(2) { DoubleArray(4) }
    private val q = doubleArrayOf(0.5412, 1.3066)
    private val co = Array(2) { DoubleArray(5) }

    init {
        for (k in 0..1) {
            val w = 2 * Math.PI * cutHz / rate
            val alpha = Math.sin(w) / (2 * q[k]); val cw = Math.cos(w); val a0 = 1 + alpha
            co[k][0] = (1 - cw) / 2 / a0; co[k][1] = (1 - cw) / a0; co[k][2] = (1 - cw) / 2 / a0
            co[k][3] = -2 * cw / a0; co[k][4] = (1 - alpha) / a0
        }
    }

    fun process(x: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        for (i in x.indices) {
            var v = x[i].toDouble()
            for (k in 0..1) {
                val c = co[k]; val st = s[k]
                val o = c[0] * v + c[1] * st[0] + c[2] * st[1] - c[3] * st[2] - c[4] * st[3]
                st[1] = st[0]; st[0] = v; st[3] = st[2]; st[2] = o
                v = o
            }
            y[i] = v.toFloat()
        }
        return y
    }

    companion object {
        /** [src] = जिस दर पर नमूने बजेंगे (पिच के साथ), [dst] = 24 kHz. कटऑफ़ = नई दर का 45% */
        fun forRates(src: Int, dst: Int): SoftLowpass = SoftLowpass(src, dst * 0.45)
    }
}
