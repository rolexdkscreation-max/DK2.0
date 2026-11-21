package com.dk.assistant

import android.content.Context

/**
 * 21 नवंबर 2026 (मालिक: "DK Live की आवाज़ का slider - 100 / 50 / 30% - बाय डिफ़ॉल्ट ऑफ रखना"):
 * Settings → आवाज़ → बातचीत का तरीका. चालू करने पर DK Live की आवाज़ (बजने से ठीक पहले, PCM16) चुने हुए % पर.
 * बंद (डिफ़ॉल्ट) = पहले जैसा, कुछ नहीं छूता. फ़ोन का volume नहीं बदलता - सिर्फ़ DK Live की अपनी आवाज़.
 * Android import सिर्फ़ Context (scale pure - JVM test).
 */
object LiveVoiceLevel {
    const val PREF_ON = "live_voice_level_on"
    const val PREF_PCT = "live_voice_level_pct"
    const val MIN = 10
    const val MAX = 100
    const val STEP = 10
    const val DEF = 100

    fun on(c: Context): Boolean = Prefs.bool(c, PREF_ON, false)
    fun setOn(c: Context, v: Boolean) = Prefs.edit(c) { putBoolean(PREF_ON, v) }
    fun pct(c: Context): Int = clamp(Prefs.long(c, PREF_PCT, DEF.toLong()).toInt())
    fun setPct(c: Context, p: Int) = Prefs.edit(c) { putLong(PREF_PCT, clamp(p).toLong()) }

    /** लागू होने वाला % (बंद = 100). pure */
    fun active(on: Boolean, pct: Int): Int = if (on) clamp(pct) else MAX

    fun clamp(p: Int): Int = ((p.coerceIn(MIN, MAX) + STEP / 2) / STEP * STEP).coerceIn(MIN, MAX)

    /** PCM16 little-endian को [pct]% पर. 100 = वही array (कोई copy नहीं). pure */
    fun scale(b: ByteArray, pct: Int): ByteArray {
        if (pct >= MAX) return b
        val out = ByteArray(b.size)
        var i = 0
        while (i + 1 < b.size) {
            val v = ((b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            val s = (v * pct / 100).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (s and 0xff).toByte(); out[i + 1] = ((s shr 8) and 0xff).toByte()
            i += 2
        }
        if (b.size % 2 == 1) out[b.size - 1] = b[b.size - 1]
        return out
    }

    fun apply(c: Context, b: ByteArray): ByteArray = scale(b, active(on(c), pct(c)))

    fun label(on: Boolean, p: Int): String = if (!on) "बंद — DK Live की आवाज़ पूरी (पहले जैसी)" else when (p) {
        100 -> "100% — पूरी आवाज़"
        50 -> "50% — आधी"
        30 -> "30% — धीमी"
        else -> "$p%"
    }
}
