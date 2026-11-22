package com.dk.assistant.livewall

/**
 * 22 नवंबर 2026 (मालिक: "वॉलपेपर लगा है तो बैटरी खत्म नहीं होनी चाहिए"): लाइव वॉलपेपर कब और कितनी बार बने.
 * Android-free (JVM test).
 *
 * बचत (डिफ़ॉल्ट): स्क्रीन खुलते / होम-पेज बदलते समय [BURST_MS] तक चलता दृश्य (लहरें, तारे टिमटिमाते),
 *   फिर तस्वीर रुक जाती है - सिर्फ़ हर [STILL_MS] पर एक बार नई (सूरज-चाँद धीरे-धीरे खिसकते हैं).
 * हमेशा चलता: लगातार [SMOOTH_MS] (ज़्यादा बैटरी) - फिर भी बैटरी सेवर / कम बैटरी में अपने-आप बचत.
 * स्क्रीन बंद / वॉलपेपर छिपा (कोई ऐप सामने) = कुछ नहीं बनता.
 */
object LiveWallPower {
    const val MODE_SAVE = "save"
    const val MODE_SMOOTH = "smooth"

    /** चलते दृश्य में एक फ्रेम (ms) - ~20 fps */
    const val ANIM_MS = 50L
    /** "हमेशा चलता" में ~15 fps */
    const val SMOOTH_MS = 66L
    /** खुलते समय कितनी देर चलता दिखे */
    const val BURST_MS = 6_000L
    /** रुकी तस्वीर कितनी देर में नई (सूरज 1 मिनट में ~0.25° चलता है - आँख को पता नहीं चलता) */
    const val STILL_MS = 30_000L
    /** इससे कम बैटरी (चार्जर नहीं) = हमेशा बचत, खुलते समय भी नहीं चलता */
    const val LOW_BATTERY = 20

    fun saving(powerSave: Boolean, battPct: Int, charging: Boolean): Boolean =
        powerSave || (!charging && battPct in 0 until LOW_BATTERY)

    /**
     * अगला फ्रेम कितनी देर बाद. [sinceKickMs] = स्क्रीन खुले / पेज बदले कितनी देर हुई.
     */
    fun nextDelay(mode: String, sinceKickMs: Long, powerSave: Boolean, battPct: Int, charging: Boolean): Long {
        if (saving(powerSave, battPct, charging)) return STILL_MS
        if (mode == MODE_SMOOTH) return SMOOTH_MS
        return if (sinceKickMs in 0 until BURST_MS) ANIM_MS else STILL_MS
    }
}
