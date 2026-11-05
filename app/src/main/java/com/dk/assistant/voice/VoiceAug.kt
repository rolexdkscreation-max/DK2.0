package com.dk.assistant.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v4.7 मालिक: "दूर से भी बोला तो मैच होना चाहिए, स्लो बोला तो भी मैच होना चाहिए"।
 *
 * सिखाते समय मालिक पास से, सामान्य रफ़्तार में बोलता है. इसलिए हर रिकॉर्ड किए बार से दो और नमूने बनाते हैं:
 *  - [far]: जैसे कमरे के उस पार से - आवाज़ धीमी, दीवारों की गूँज, ऊँची आवाज़ें (sss) कम, कमरे का शोर ज़्यादा
 *  - [slow]: वही बात ~1.35 गुना धीरे (फ़्रेम खींचकर - आवाज़ का सुर नहीं बदलता)
 * सीमा (base) सिर्फ़ असली बार से बनती है ([VoiceCmdMatch.Model.buildAug]) - सख़्ती वही, पकड़ ज़्यादा.
 * Android नहीं - सिर्फ़ अंक, ताकि test हो सके.
 */
object VoiceAug {
    const val RATE = 16_000
    /** धीमा: कितना खींचें */
    const val SLOW = 1.35f
    /** दूर: आवाज़ इतनी धीमी (~ -14 dB) */
    const val FAR_GAIN = 0.2f
    /** दूर: बोली के मुकाबले शोर (SNR ~ 12 dB) - कमरे का अपना शोर न मिले तो */
    const val FAR_NOISE = 0.25f

    /** PCM16 (little-endian) -> -1..1 */
    fun toFloat(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
        ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
    }

    fun toPcm(x: FloatArray): ByteArray {
        val out = ByteArray(x.size * 2)
        for (i in x.indices) {
            val v = (x[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (v and 0xff).toByte(); out[i * 2 + 1] = (v shr 8 and 0xff).toByte()
        }
        return out
    }

    fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0.0; for (v in x) s += v * v
        return sqrt(s / x.size).toFloat()
    }

    /**
     * दूर से बोली जैसी आवाज़.
     * @param noise कमरे का असली शोर (रिकॉर्डिंग की चुप्पी वाले हिस्से) - null = हल्का बनावटी शोर
     * @param seed हर बार एक-सा नतीजा (test)
     */
    fun far(x: FloatArray, noise: FloatArray? = null, seed: Int = 7): FloatArray {
        if (x.isEmpty()) return x
        // 1) दीवारों की गूँज: 3 देर से आती, घटती परछाइयाँ (23 / 41 / 67 ms)
        val taps = intArrayOf(RATE * 23 / 1000, RATE * 41 / 1000, RATE * 67 / 1000)
        val gains = floatArrayOf(0.45f, 0.30f, 0.18f)
        val y = FloatArray(x.size)
        for (i in x.indices) {
            var v = x[i]
            for (k in taps.indices) { val j = i - taps[k]; if (j >= 0) v += x[j] * gains[k] }
            y[i] = v
        }
        // 2) दूरी पर ऊँची आवाज़ें पहले मरती हैं: हल्का low-pass
        var lp = 0f
        for (i in y.indices) { lp += 0.55f * (y[i] - lp); y[i] = lp }
        // 3) धीमी
        val r0 = rms(x)
        val r1 = rms(y).coerceAtLeast(1e-6f)
        val g = FAR_GAIN * r0 / r1
        for (i in y.indices) y[i] *= g
        // 4) कमरे का शोर वही रहता है - बोली धीमी हुई तो शोर बराबर का लगता है
        val target = rms(y) * FAR_NOISE
        if (noise != null && noise.size > RATE / 10 && rms(noise) > 1e-5f) {
            val n0 = rms(noise)
            val ng = max(1f, target / n0).let { min(it, 6f) }   // असली शोर, ज़रूरत हो तो थोड़ा बढ़ाकर
            for (i in y.indices) y[i] += noise[i % noise.size] * ng
        } else {
            var s = seed.toLong() * 6364136223846793005L + 1442695040888963407L
            for (i in y.indices) {
                s = s * 6364136223846793005L + 1442695040888963407L
                val u = ((s ushr 33).toInt() and 0xffff) / 65535f - 0.5f
                y[i] += u * target * 3.46f      // एक-सा बँटा शोर: rms = फैलाव/√12
            }
        }
        return y
    }

    /** MFCC फ़्रेम खींचकर धीमा (बीच के फ़्रेम जोड़कर) - सुर वही, रफ़्तार कम */
    fun slow(f: Array<FloatArray>, factor: Float = SLOW): Array<FloatArray> {
        if (f.size < 2 || factor <= 1f) return f
        val n = (f.size * factor).toInt()
        val d = f[0].size
        return Array(n) { j ->
            val p = j * (f.size - 1).toFloat() / (n - 1)
            val a = p.toInt().coerceAtMost(f.size - 2); val t = p - a
            FloatArray(d) { i -> f[a][i] * (1 - t) + f[a + 1][i] * t }
        }
    }
}
