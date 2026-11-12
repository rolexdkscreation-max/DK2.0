package com.dk.assistant.voice.engine

import kotlin.math.roundToInt

/**
 * नवंबर 2026 (नया Supertonic 3): सिर्फ़ स्पीड और पिच (मालिक: "बाकी कोई option नहीं") - कोई रिवर्ब / बास / ठहराव / साँस नहीं.
 * pure (JVM test).
 */
object SupertonicAudio {
    /**
     * पिच बिना रफ़्तार बदले: आवाज़ [speed]/[pitch] रफ़्तार पर बनाओ, फिर sample-rate को [pitch] गुना मानकर 24 kHz पर लाओ.
     * (पिच 1.2 = 20% ऊँची; उतनी ही तेज़ भी होती - इसलिए बनाते समय 1.2 गुना धीमी). दोनों 1 = कोई बदलाव नहीं.
     */
    fun genSpeed(speed: Float, pitch: Float): Float = (speed.coerceIn(0.5f, 2f) / pitch.coerceIn(0.7f, 1.4f)).coerceIn(0.3f, 3f)

    fun srcRate(rate: Int, pitch: Float): Int = (rate * pitch.coerceIn(0.7f, 1.4f)).roundToInt()

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

/**
 * HQ (44.1 kHz) आवाज़ को 24 kHz पर लाने से पहले हल्का low-pass (2 biquad = 4th order Butterworth) - खरखराहट (aliasing) नहीं.
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
