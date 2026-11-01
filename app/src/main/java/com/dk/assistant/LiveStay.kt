package com.dk.assistant

import android.content.Context

/**
 * नवंबर 2026 (मालिक: "एक बार जुड़ने के बाद ✕ दबाने तक जुड़ा रहे; बीच में कटे तो पीछे से चुपचाप जुड़े, बातचीत वहीं से").
 * लॉग: हर 1011 ("Internal error occurred") से 0.5-1.8s पहले बीच बातचीत में text भेजा गया था; 0-6s में टूटना, 7 बार के बाद हार.
 * यहाँ के नियम छोटे और जाँचने लायक (बिना Android) - GeminiLive इन्हीं से चलता है.
 */
object LiveStay {
    /** "Gemini ने आवाज़ नहीं सुनी" की सीमा: पहला जवाब अक्सर 2-4s में आता है, इसलिए 6s से कम नहीं (पहले 1.8-2.5s) */
    const val NO_REPLY_MS = 6_000L

    /** बीच बातचीत में बचाव वाला text (caption "नहीं सुना", "अटका - दोबारा", "टूटने से पहले की बात", बीच का सवाल) - डिफ़ॉल्ट बंद */
    const val PREF_TEXT_RESCUE = "live_text_rescue"
    fun textRescueOn(c: Context): Boolean = Prefs.bool(c, PREF_TEXT_RESCUE, false)

    /** टूटकर दोबारा जुड़ने का इंतज़ार: 1, 2, 4, 8, 15s (अधिकतम 15) + 0-20% random. कभी हार नहीं मानते (✕ ही रोकता है) */
    private val STEPS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    const val MAX_BACKOFF_MS = 15_000L
    fun backoffMs(drop: Int, rnd: Double = Math.random()): Long {
        if (drop <= 0) return 250L                      // लंबा चला / goAway = तुरंत (चुपचाप)
        val base = STEPS[(drop - 1).coerceAtMost(STEPS.size - 1)]
        return (base + (base * 0.2 * rnd.coerceIn(0.0, 1.0)).toLong()).coerceAtMost(MAX_BACKOFF_MS + 3_000L)
    }

    /** इतनी बार जल्दी टूटा = यूज़र को एक बार "नेटवर्क कमज़ोर है" (स्क्रीन पर) */
    const val WEAK_NET_TELL_AT = 3

    /** 2s से कम चला और ठीक पहले (2s में) text भेजा था = वही text दोबारा नहीं (वरना टूटने का चक्कर) */
    fun skipResend(lastedMs: Long, textAgoAtDropMs: Long): Boolean = lastedMs < 2_000L && textAgoAtDropMs in 0L..2_000L

    /** goAway का timeLeft ("10s", "9.5s", "" ) -> ms; न समझे तो -1 */
    fun timeLeftMs(s: String?): Long {
        val t = s?.trim().orEmpty().removeSuffix("s")
        return t.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L
    }

    /**
     * goAway पर पहले से तैयार (standby) connection तभी लें जब उसी ताज़ा बातचीत (handle) से बना हो;
     * तैयार होने के बाद नई बात हुई (handle बदला) और पुराना अभी ज़िंदा = ताज़ा handle से जोड़ो (संदर्भ न छूटे).
     * पुराना मर चुका = तैयार वाला तुरंत (बीच में आवाज़ न कटे, 0 ms).
     */
    fun useParked(parkedHandle: String?, curHandle: String?, oldAlive: Boolean): Boolean =
        parkedHandle == null || parkedHandle == curHandle || !oldAlive

    /** ✕ से बंद = 1000; बाकी हर कटाव (1006/1011/1001/नेटवर्क बदलना) = अपने-आप कटा, चुपचाप फिर */
    fun userClosed(stoppedByUser: Boolean): Boolean = stoppedByUser
}
