package com.dk.assistant.voice.engine

import com.dk.assistant.avatar.Mood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * नवंबर 2026 (मालिक): "सारे TTS में साँस लेने, रुकने और उतार-चढ़ाव जैसी इंसानी बात."
 * Piper / HQ के लिए (Gemini TTS / Live को prompt से): 
 *  - भाव के हिसाब से रफ़्तार / सुर / ठहराव (उदास = धीमा, थोड़ा नीचा, लंबा ठहराव; खुश = थोड़ा तेज़ और ऊँचा)
 *  - हर वाक्य की रफ़्तार में ±3% का हल्का फ़र्क (एक जैसी मशीनी लय नहीं)
 *  - लंबी बात में बीच-बीच में बहुत धीमी, मुलायम साँस (~0.2 s) - वाक्य शुरू होने से पहले
 * Settings → आवाज़ → "Piper में हल्की साँस और ठहराव" (शुरू में चालू). Pure - JVM test.
 */
object HumanVoice {
    const val PREF = "piper_breath"
    const val RATE = VoiceEngine.RATE

    /** इतने अक्षर बोलने के बाद अगली लंबी बात से पहले साँस */
    const val BREATH_EVERY_CHARS = 110
    const val BREATH_MIN_WORDS = 5
    /** इतनी देर चुप्पी = नई बात (गिनती फिर से) */
    const val NEW_REPLY_MS = 4_000L

    data class Tune(val speedMul: Float, val pitchMul: Float, val gapPct: Int)

    fun tune(m: Mood): Tune = when (m) {
        Mood.SAD -> Tune(0.92f, 0.97f, 140)
        Mood.EMOTIONAL -> Tune(0.94f, 0.98f, 130)
        Mood.HAPPY -> Tune(1.04f, 1.02f, 90)
        Mood.ANGRY -> Tune(1.03f, 1.0f, 85)
        else -> Tune(1f, 1f, 100)
    }

    /** हर वाक्य की रफ़्तार का छोटा फ़र्क (उसी वाक्य पर हमेशा वही) - 0.97..1.03 */
    fun jitter(text: String): Float {
        val h = abs(text.trim().hashCode() % 61)          // 0..60
        return 0.97f + h / 1000f
    }

    /** सवाल = आख़िर में थोड़ा ऊँचा सुर (Piper ख़ुद कम उठाता है) */
    fun questionLift(text: String): Float = if (text.trimEnd().endsWith("?")) 1.015f else 1f

    /** वाक्यों के बीच ठहराव भाव के हिसाब से */
    fun gap(base: Int, m: Mood): Int = (base * tune(m).gapPct / 100).coerceIn(0, 1500)

    /** साँस कब - एक जवाब में कितना बोला जा चुका, उसके हिसाब से */
    class Breather {
        private var chars = 0
        private var lastAt = 0L

        /** @return true = इस वाक्य से पहले साँस */
        fun next(text: String, now: Long): Boolean {
            if (lastAt == 0L || now - lastAt > NEW_REPLY_MS) chars = 0
            lastAt = now
            val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val breathe = chars >= BREATH_EVERY_CHARS && words >= BREATH_MIN_WORDS
            if (breathe) chars = 0
            chars += text.trim().length
            return breathe
        }

        fun reset() { chars = 0; lastAt = 0L }
    }

    /**
     * मुलायम साँस: बहुत धीमा, छना हुआ (low-pass) शोर, धीरे शुरू और धीरे खत्म. 24 kHz, 16-bit mono.
     * @param seed एक ही वाक्य पर वही आवाज़ (test)
     */
    fun breath(ms: Int = 220, seed: Int = 7, peak: Float = 0.035f): ByteArray {
        val n = (RATE * ms.coerceIn(120, 400) / 1000)
        val out = ByteArray(n * 2)
        var x = seed.toLong() and 0xffffffffL
        var lp = 0f; var lp2 = 0f
        for (i in 0 until n) {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            val white = (x / 1073741823.5f) - 1f                // -1..1
            lp += 0.18f * (white - lp)                         // साँस की हवा जैसी (ऊँची सीटी नहीं)
            lp2 += 0.35f * (lp - lp2)
            val env = sin(PI * i / n).toFloat().let { it * it } // धीरे शुरू, धीरे खत्म
            val v = (lp2 * env * peak * 4f).coerceIn(-1f, 1f)
            val s = (v * 32767).toInt()
            out[2 * i] = (s and 0xff).toByte(); out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    fun breathMs(text: String): Int = 180 + abs(text.hashCode() % 81)   // 180..260
}
