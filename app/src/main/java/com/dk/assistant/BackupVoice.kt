package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "Kokoro पूरी तरह हटाओ; बैकअप में Gemini TTS, नहीं तो फ़ोन की / Google की आवाज़ - कोई problem हो तो तुरंत आ जाए"):
 * बैकअप मोड (Direct Gemini Live टूटा) में आवाज़ का pure नियम (JVM test) - [Speaker] इस्तेमाल करता है.
 *
 * क्रम: Gemini TTS -> (न चले / limit / इंटरनेट नहीं / [waitMs] से ज़्यादा देर) फ़ोन की अपनी Google / Android आवाज़ ([PhoneVoice]).
 */
object BackupVoice {
    /** पहला वाक्य: Gemini TTS का इतना ही इंतज़ार, फिर फ़ोन की आवाज़ (यूज़र को लंबी चुप्पी न लगे) */
    const val FIRST_WAIT_MS = 3_500L
    /** अगले वाक्य: पिछला बजते-बजते बन चुका होता है - इससे ज़्यादा = फ़ोन की आवाज़ */
    const val NEXT_WAIT_MS = 5_000L
    /** Gemini TTS नहीं चला (नेट/सर्वर) = इतनी देर सीधे फ़ोन की आवाज़ */
    const val DOWN_MS = 60_000L
    /** limit (429) = कम से कम इतनी देर फ़ोन की आवाज़ (key का खुलना पता हो तो तब तक) */
    const val LIMIT_DOWN_MS = 10 * 60_000L

    fun waitMs(alreadySpoke: Boolean): Long = if (alreadySpoke) NEXT_WAIT_MS else FIRST_WAIT_MS

    /** सीधे फ़ोन की आवाज़ (Gemini TTS आज़माए बिना)? key नहीं / इंटरनेट नहीं / अभी-अभी नहीं चला */
    fun phoneFirst(haveKeys: Boolean, online: Boolean, now: Long, downUntil: Long): Boolean =
        !haveKeys || !online || now < downUntil

    fun downUntil(code: Int, now: Long, keysBlockedUntil: Long): Long =
        if (code == 429) maxOf(now + LIMIT_DOWN_MS, keysBlockedUntil) else now + DOWN_MS
}
