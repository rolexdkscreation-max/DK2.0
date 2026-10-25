package com.dk.assistant.voice

import kotlin.math.sqrt

/**
 * v3.66: मालिक की आवाज़ की पहचान (voiceprint) - शुद्ध गणित, Android के बिना टेस्ट होता है।
 * मॉडल (3D-Speaker CAM++, zh+en) हर आवाज़ से 192 अंकों की "पहचान" बनाता है; दो पहचानों का cosine = कितनी मिलती है।
 *
 * सीमाएँ कंप्यूटर पर नापीं (LibriSpeech dev-clean के 40 लोग, हर व्यक्ति 5 वाक्य से सीखा):
 *   1 सेकंड आवाज़ पर बराबर-गलती (EER) ~1.6% (cosine ~0.34); 0.46 पर असली मालिक 5% छूटा, दूसरे 0.06% पास।
 * फ़ोन का माइक/शोर अलग होता है - इसलिए "सामान्य" थोड़ा ढीला और "सख़्त" ऊँचा।
 */
object VoicePrint {
    const val DIM = 192
    /** सामान्य: मालिक कम छूटे */
    const val TH_NORMAL = 0.33f
    /** सख़्त: दूसरे बहुत कम पास हों (मालिक कभी-कभी दोबारा बोले) */
    const val TH_STRICT = 0.43f
    /** सीखते समय हर वाक्य में कम से कम इतनी आवाज़ */
    const val MIN_ENROLL_SPEECH_MS = 1500
    const val ENROLL_PHRASES = 5
    /** सीखे गए वाक्य आपस में इतने मिलें (वरना कोई और बोला / बहुत शोर) */
    const val ENROLL_MIN_CONSISTENCY = 0.5f

    fun threshold(strict: Boolean) = if (strict) TH_STRICT else TH_NORMAL

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n <= 1e-9f || !n.isFinite()) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na <= 0 || nb <= 0) return 0f
        return (d / sqrt(na * nb)).toFloat()
    }

    /** कई वाक्यों की पहचान का औसत (हर एक पहले normalize) */
    fun mean(list: List<FloatArray>): FloatArray? {
        if (list.isEmpty()) return null
        val d = list[0].size
        if (list.any { it.size != d }) return null
        val m = FloatArray(d)
        for (v in list) { val n = normalize(v); for (i in 0 until d) m[i] += n[i] }
        return normalize(m)
    }

    /** सीखना ठीक हुआ? सबसे कम मिलने वाला वाक्य भी औसत से ENROLL_MIN_CONSISTENCY मिले */
    fun consistency(list: List<FloatArray>): Float {
        val m = mean(list) ?: return 0f
        return list.minOf { cosine(it, m) }
    }

    fun encode(v: FloatArray): String = v.joinToString(",") { String.format(java.util.Locale.US, "%.5f", it) }

    fun decode(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        val p = s.split(',')
        if (p.size != DIM) return null
        val v = FloatArray(DIM)
        for (i in 0 until DIM) { val x = p[i].trim().toFloatOrNull() ?: return null; if (!x.isFinite()) return null; v[i] = x }
        return v
    }

    // ------------------------------------------------------------ दूर की आवाज़ (परत 4)
    /** सामान्य: मालिक की आम आवाज़ से 14 dB (~5 गुना) धीमी = दूर की आवाज़; सख़्त: 9 dB (~2.8 गुना) */
    const val FAR_NORMAL = 0.20
    const val FAR_STRICT = 0.355

    /** इस RMS से धीमी आवाज़ पर DK "बोलना शुरू" नहीं मानेगा (0 = बंद) */
    fun farFloor(ownerLevel: Double, strict: Boolean): Double =
        if (ownerLevel <= 0 || !ownerLevel.isFinite()) 0.0 else (ownerLevel * (if (strict) FAR_STRICT else FAR_NORMAL)).coerceIn(0.0, 2500.0)

    /** बोलते समय के टुकड़ों की RMS का मध्य मान (शोर वाले हिस्से हटाकर) */
    fun speechLevel(rms: List<Double>, noise: Double): Double {
        val s = rms.filter { it > maxOf(noise * 2.5, 300.0) }.sorted()
        return if (s.size < 5) 0.0 else s[s.size / 2]
    }

    /** पहचानी गई मालिक की आवाज़ से स्तर धीरे-धीरे सीखो - सीखे स्तर के आधे से दोगुने के बीच ही */
    fun adaptLevel(enrolled: Double, current: Double, utter: Double): Double {
        if (enrolled <= 0 || utter <= 0) return current
        val base = if (current > 0) current else enrolled
        return (base * 0.9 + utter * 0.1).coerceIn(enrolled * 0.5, enrolled * 2.0)
    }
}
