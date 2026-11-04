package com.dk.assistant

import android.content.Context

/**
 * नवंबर 2026 (मालिक): "JARVIS की आवाज़ पूरे JARVIS की तरह नहीं लग रही - एकदम वैसी लगे".
 *
 * फ़िल्म वाले JARVIS की आवाज़ = शांत, गहरी ब्रिटिश आवाज़ + उस पर हल्की "मशीनी" परत (छोटी गूँज, चमकदार साफ़ किनारा).
 * Gemini की आवाज़ (Charon...) + JARVIS वाला अंदाज़ (prompt) पहले से था; यह वह बची हुई परत है जो सीधे बजती आवाज़ पर लगती है:
 *  1. नीचे की भारी गूँज हटाना (high-pass ~90 Hz) - आवाज़ साफ़, "speaker" जैसी नहीं
 *  2. दो बहुत छोटी गूँजें (11 ms + 23 ms) = वही हल्की धातु जैसी AI रंगत
 *  3. एक हल्की कमरे जैसी पूँछ (61 ms) = "पूरे घर में बोलता" JARVIS
 *  4. नरम सीमा (soft clip) - कभी फटे नहीं
 * किसी असली इंसान (अभिनेता) की आवाज़ की नकल नहीं - सिर्फ़ रंगत. Settings → JARVIS मोड → "JARVIS वाली मशीनी परत" से बंद हो सकती है.
 *
 * Android import सिर्फ़ [enabled] में - [process] pure (JVM test).
 */
object JarvisFx {
    const val PREF = "jarvis_fx"
    private const val RATE = 24000   // GeminiLive.OUT_RATE
    private val TAPS = intArrayOf(RATE * 11 / 1000, RATE * 23 / 1000, RATE * 61 / 1000)
    private val GAINS = floatArrayOf(0.22f, 0.13f, 0.08f)
    private const val DRY = 0.82f
    private val HIST = RATE * 61 / 1000 + 1

    private val ring = FloatArray(HIST)
    private var pos = 0
    private var hpX = 0f
    private var hpY = 0f
    private const val HP_A = 0.9768f  // one-pole high-pass, ~90 Hz @ 24 kHz

    fun enabled(c: Context): Boolean = JarvisMode.on(c) && Prefs.bool(c, PREF, true)

    /** टोका / नया जवाब - पुरानी गूँज अगले जवाब में न आए */
    @Synchronized fun reset() { ring.fill(0f); pos = 0; hpX = 0f; hpY = 0f }

    /** PCM16 little-endian mono -> नया array (input नहीं बदलता - वही टुकड़ा दोबारा बज सकता है) */
    @Synchronized fun process(pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val x = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toFloat()
            val hp = HP_A * (hpY + x - hpX); hpX = x; hpY = hp
            ring[pos] = hp
            var y = hp * DRY
            for (k in TAPS.indices) y += ring[(pos - TAPS[k] + HIST) % HIST] * GAINS[k]
            pos = (pos + 1) % HIST
            val v = softClip(y).toInt()
            out[i] = (v and 0xff).toByte(); out[i + 1] = (v shr 8 and 0xff).toByte()
            i += 2
        }
        if (pcm.size % 2 == 1) out[pcm.size - 1] = pcm[pcm.size - 1]
        return out
    }

    private fun softClip(y: Float): Float {
        val lim = 30000f
        return if (y > lim) lim + (y - lim) / (1f + (y - lim) / 2767f) else if (y < -lim) -lim + (y + lim) / (1f - (y + lim) / 2767f) else y
    }
}
