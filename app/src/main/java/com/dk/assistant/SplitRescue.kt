package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "Gemini TTS चालू हो तो जवाब बहुत देर से आता है, कभी text आ जाता है पर बोलता नहीं").
 *
 * जड़: बँटवारे (Gemini TTS) में Live अपनी आवाज़ बना ही रहा होता है (AUDIO + लिखावट), पर वह आवाज़ फेंक दी जाती
 * थी और हर टुकड़े पर Gemini TTS को नई request जाती थी. TTS देर करे (1-3 s, धीमा नेट) = जवाब देर से; TTS 429 /
 * नेट गलती दे = वह टुकड़ा चुप (सिर्फ़ लिखा). और जब तक TTS का टुकड़ा "बन रहा" था, माइक DK को "बोलता हुआ"
 * मानकर आपकी आवाज़ Gemini को नहीं भेजता था (पक्की टोका-टाकी के सिवा) - इसलिए "ठीक से सुनता नहीं".
 *
 * अब: इस जवाब की Live आवाज़ फ़ोन में रखी जाती है (फेंकी नहीं). Gemini TTS की पहली आवाज़ [FIRST_BUDGET_MS] में न
 * बजे, या कोई टुकड़ा न बने / बीच में अटके -> बचा जवाब उसी पल Live की अपनी आवाज़ में (वही आवाज़ - Sulafat, दोनों
 * एक ही सेटिंग "gemini_voice"). यह फ़ाइल pure logic (Android नहीं) -> JVM test.
 */
object SplitRescue {
    /** आपकी बात के बाद Live की आवाज़ आ गई, उसके इतने ms में Gemini TTS नहीं बजा = Live की आवाज़ ही बजाओ */
    const val FIRST_BUDGET_MS = 900L
    /** बीच का टुकड़ा इतनी देर तक बना ही नहीं (पिछला बज चुका) = अटका, Live की आवाज़ */
    const val NEXT_STUCK_MS = 2_500L
    /** पहला टुकड़ा ही इतनी देर से अटका (Live वाली जाँच न चली हो तब भी) */
    const val FIRST_STUCK_MS = 1_600L
    /** एक जवाब की ज़्यादा से ज़्यादा इतनी Live आवाज़ रखो (24 kHz 16-bit mono, 2 मिनट) */
    const val MAX_KEEP_BYTES = 24_000 * 2 * 120
    /** बीच से Live आवाज़ पर जाएँ तो थोड़ा पीछे से (Live की लिखावट आवाज़ से पीछे चलती है - शब्द छूटे नहीं) */
    const val BACK_MS = 600

    /** पहली आवाज़ की दौड़: Live तैयार ([liveSince] से), TTS अब तक चुप, रास्ता खुला (कोई रोक नहीं) */
    fun firstTooSlow(now: Long, liveSince: Long, ttsStarted: Boolean, open: Boolean): Boolean =
        open && !ttsStarted && liveSince > 0 && now - liveSince >= FIRST_BUDGET_MS

    /** टुकड़ा कितनी देर से बिना आवाज़ अटका है - Live पर जाएँ? */
    fun stuck(waitedMs: Long, first: Boolean): Boolean = waitedMs >= if (first) FIRST_STUCK_MS else NEXT_STUCK_MS

    /**
     * बचा जवाब लिखावट के [at] अक्षर से शुरू होता है: रखी हुई Live आवाज़ के किस टुकड़े से बजाएँ.
     * [lens] = हर Live टुकड़ा आते समय जवाब की लिखावट कितनी लंबी थी, [sizes] = उसके bytes.
     * Live की लिखावट आवाज़ से पीछे आती है -> पहला टुकड़ा जहाँ लिखावट [at] तक पहुँची, उससे [BACK_MS] पीछे.
     * (शब्द दोहराना ठीक, छूटना नहीं.)
     */
    fun startIndex(at: Int, lens: List<Int>, sizes: List<Int>, rate: Int = 24_000): Int {
        if (at <= 0 || lens.isEmpty()) return 0
        var i = lens.indexOfFirst { it >= at }
        if (i < 0) i = lens.size
        var back = rate * 2 * BACK_MS / 1000
        while (i > 0 && back > 0) { i--; back -= sizes.getOrElse(i) { 0 } }
        return i.coerceIn(0, lens.size)
    }

    /** स्क्रीन / log के लिए साफ़ कारण */
    fun why(failed: Boolean, waitedMs: Long): String =
        if (failed) "Gemini TTS ने आवाज़ नहीं बनाई" else "Gemini TTS ${waitedMs} ms से चुप"
}
