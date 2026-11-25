package com.dk.assistant.voice.st3

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 25 नवंबर 2026 (मालिक: "फ़ोन की आवाज़ पूरी है फिर भी ऑफ़लाइन आवाज़ बहुत धीमी सुनाई देती है, Gemini की तेज़").
 * जड़: Supertonic का मॉडल जो लहर देता है उसे सीधे बजाया जाता था - उसका स्तर Gemini की आवाज़ से करीब 8-10 dB नीचे
 * (बोलते हिस्से का RMS ~0.04-0.06, Gemini ~0.15). फ़ोन का volume वही, पर लहर ही धीमी.
 * अब हर टुकड़ा बजाने से पहले Gemini जितने स्तर ([TARGET_RMS]) पर, और ऊपर की चोटियाँ नरम सीमा से (फटे / चटके नहीं).
 * Android import नहीं -> JVM test.
 */
object St3Loud {
    const val TARGET_RMS = 0.15f
    /** इससे ज़्यादा नहीं बढ़ाते (बहुत धीमा / खाली टुकड़ा शोर न बने) */
    const val MAX_GAIN = 6f
    /** इसके ऊपर नरम सीमा (tanh) - 1.0 कभी पार नहीं */
    const val KNEE = 0.85f

    fun rms(w: FloatArray): Float {
        var s = 0.0; var n = 0
        for (x in w) if (abs(x) > 0.01f) { s += x * x; n++ }
        return if (n == 0) 0f else sqrt(s / n).toFloat()
    }

    /** बोलने लायक स्तर पर (धीमी आवाज़ ऊपर; पहले से तेज़ को कम नहीं करते) */
    fun speech(w: FloatArray): FloatArray {
        val r = rms(w)
        if (r <= 0f) return w
        return lift(w, (TARGET_RMS / r).coerceIn(1f, MAX_GAIN))
    }

    /**
     * भाव वाली लाइन ([St3Emotion.perform] - पहले ही [St3Emotion.TARGET_RMS] पर, फिर Mood का gain): एक ही गुना ऊपर,
     * ताकि फुसफुसाहट / चीख का आपसी फ़र्क बना रहे.
     */
    fun mood(w: FloatArray): FloatArray = lift(w, TARGET_RMS / St3Emotion.TARGET_RMS)

    /** [g] गुना + नरम सीमा */
    fun lift(w: FloatArray, g: Float): FloatArray {
        if (g == 1f) return w
        val room = 1f - KNEE
        return FloatArray(w.size) { i ->
            val y = w[i] * g
            val a = abs(y)
            if (a <= KNEE) y else sign(y) * (KNEE + room * tanh(((a - KNEE) / room).toDouble()).toFloat())
        }
    }
}
