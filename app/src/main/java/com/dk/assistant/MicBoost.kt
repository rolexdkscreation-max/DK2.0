package com.dk.assistant

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Dheemi awaaz ko tez karna (v3.7).
 *
 * Phone ka mic dheere bolne par bahut halki awaaz deta hai - tab Gemini/Whisper ko lagta hai ki koi bola hi nahi.
 * Yeh class har tukde ki awaaz naapti hai:
 *  - aas-paas ka shor (noise floor) dheere-dheere seekhti hai
 *  - jab awaaz shor se upar ho (koi bol raha hai) to use ek theek level (~2500 rms) tak badhati hai, max 6 guna
 *  - shor ko zyada tez nahi karti (gain par shor ke hisaab se limit)
 *  - tez awaaz par turant gain kam (fatna/clip nahi), dheemi par dheere-dheere badhao
 *  - aakhir me naram limiter: kabhi bhi awaaz phatti nahi
 */
class MicBoost(
    private val target: Double = 2500.0,
    private val maxGain: Double = 6.0,
) {
    @Volatile var noise = 120.0
        private set
    @Volatile var gain = 1.0
        private set

    /** buf ke pehle n samples ko jagah par hi tez karo. Wapas: asli (bina badhaye) rms. */
    fun apply(buf: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
        val rms = sqrt(sum / n)
        // shor: neeche turant, upar bahut dheere (dheemi baat ko shor na maan le)
        noise = if (rms < noise) noise * 0.85 + rms * 0.15 else noise * 0.997 + rms * 0.003
        if (noise < 20.0) noise = 20.0
        // shor ko 450 se upar mat le jao (pankha/TV tez na ho)
        val cap = (450.0 / noise).coerceIn(1.0, maxGain)
        val speech = rms > maxOf(noise * 1.6, 60.0)
        val want = if (speech) (target / rms).coerceIn(1.0, cap) else minOf(gain, cap)
        gain = if (want < gain) want else gain + (want - gain) * 0.08
        if (gain <= 1.02) return rms
        val g = gain
        for (i in 0 until n) {
            val x = buf[i] * g
            // 22000 ke upar naram mod (tanh) - phatne ki awaaz nahi
            val y = if (abs(x) <= 22000.0) x else Math.signum(x) * (22000.0 + 10767.0 * tanh((abs(x) - 22000.0) / 10767.0))
            buf[i] = y.toInt().coerceIn(-32767, 32767).toShort()
        }
        return rms
    }

    companion object {
        /** Poori record hui baat (PCM16 LE) ko ek saath theek level tak laao - Whisper/Gemini ko saaf sunaai de. */
        fun normalize(pcm: ByteArray): ByteArray {
            val n = pcm.size / 2
            if (n == 0) return pcm
            var peak = 1
            var sum = 0.0
            for (i in 0 until n) {
                val s = (pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)
                val v = s.toShort().toInt()
                val a = abs(v); if (a > peak) peak = a
                sum += v.toDouble() * v
            }
            val rms = sqrt(sum / n)
            // peak ko ~-3 dB tak, par rms 4000 se upar nahi aur 8 guna se zyada nahi
            val g = minOf(23000.0 / peak, 4000.0 / maxOf(rms, 1.0), 8.0)
            if (g <= 1.1) return pcm
            val out = ByteArray(pcm.size)
            for (i in 0 until n) {
                val s = ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toInt()
                val y = (s * g).toInt().coerceIn(-32767, 32767)
                out[i * 2] = (y and 0xff).toByte(); out[i * 2 + 1] = (y shr 8 and 0xff).toByte()
            }
            return out
        }
    }
}
