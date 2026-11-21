package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक का log: "सिर्फ़ ~200 ms की तेज़ आवाज़ (कभी गूँज=2109) पर DK की आवाज़ रुक जाती है"):
 * जड़ - DK बोलते समय तीन तेज़ रास्ते सीधे रोकते थे: कॉल-mode [DuplexBarge] (0.2 s), speaker [SpeakerBarge] (0.32 s) और
 * caption बंद/धीमा होने पर [BargeGate] की लंबाई (0.7 s). अब ये रास्ते सिर्फ़ DK की आवाज़ धीमी (duck) करते हैं.
 * पूरा रोकना तभी: कम से कम [STOP_MS] लगातार आवाज़ (DK की गूँज घटाकर - [residual]) और मालिक की आवाज़ का मिलान
 * (आवाज़ पहचान बंद हो तो मिलान हो ही नहीं सकता - तब सिर्फ़ लगातार आवाज़), या पक्के शब्द (caption - पहले जैसा, यह class नहीं छूती).
 * आवाज़ [QUIET_MS] रुकी / [MAX_DUCK_MS] बीते = DK की आवाज़ फिर पूरी, रोका नहीं.
 * Android import नहीं - JVM test (LiveVoiceBargeFixTest).
 */
class SoftBarge {
    enum class Act { NONE, STOP, UNDUCK }

    companion object {
        /** duck के समय DK की आवाज़ (0-1) */
        const val DUCK_VOLUME = 0.35f
        /** इतनी लगातार आवाज़ (+ मालिक) = पूरा रोको */
        const val STOP_MS = 1_000L
        /** लगातार आवाज़ के बीच इतना छोटा ठहराव चलेगा (साँस / शब्दों के बीच) */
        const val GAP_MS = 200L
        /** duck के बाद इतनी देर आवाज़ नहीं = शोर था, आवाज़ फिर पूरी */
        const val QUIET_MS = 700L
        /** duck इससे ज़्यादा नहीं (मिलान नहीं आया = मालिक नहीं) */
        const val MAX_DUCK_MS = 4_000L

        /** माइक की आवाज़ में से DK की अपनी गूँज का अंदाज़ा घटाओ (ऊर्जा के स्तर पर). pure */
        fun residual(rms: Double, echo: Double): Double = maxOf(0.0, rms - maxOf(0.0, echo))
    }

    var ducked = false; private set
    var voicedMs = 0L; private set
    var why = ""; private set
    private var gapMs = 0L
    private var duckAt = 0L

    /** तेज़ रास्ते ने "यूज़र है?" कहा - रोकने की जगह duck. true = अभी-अभी duck हुआ */
    fun trigger(src: String, alreadyMs: Long, now: Long): Boolean {
        if (ducked) return false
        ducked = true; why = src; voicedMs = alreadyMs; gapMs = 0L; duckAt = now
        return true
    }

    /** duck के समय हर chunk. [voiced] = पक्की इंसानी आवाज़ गूँज घटाकर भी तेज़; [owner] = मालिक मिला (या पहचान बंद) */
    fun feed(voiced: Boolean, owner: Boolean, chunkMs: Long, now: Long): Act {
        if (!ducked) return Act.NONE
        if (voiced) { voicedMs += chunkMs; gapMs = 0L } else { gapMs += chunkMs; if (gapMs > GAP_MS) voicedMs = 0L }
        if (voicedMs >= STOP_MS && owner) { reset(); return Act.STOP }
        if (gapMs >= QUIET_MS || now - duckAt >= MAX_DUCK_MS) { reset(); return Act.UNDUCK }
        return Act.NONE
    }

    fun reset() { ducked = false; voicedMs = 0L; gapMs = 0L; duckAt = 0L }
}
