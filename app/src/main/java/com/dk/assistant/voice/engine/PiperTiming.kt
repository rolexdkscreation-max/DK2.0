package com.dk.assistant.voice.engine

/**
 * नवंबर 2026 (मालिक: "Piper TTS इस्तेमाल नहीं हो पा रहा, देर करके सीधे Gemini पर चला जाता है - Piper कितनी देर में
 * बोलता है, बोल भी रहा है या नहीं, पता नहीं चलता").
 *
 * जड़: बँटवारे की "पहली आवाज़ की दौड़" ([com.dk.assistant.SplitRescue]) Gemini TTS (नेट) के लिए बनी थी - Live की अपनी
 * आवाज़ तैयार होने के 900 ms में चुना इंजन न बजे तो Live की आवाज़. Piper फ़ोन में ही बनता है: पहले Live की लिखावट
 * (आवाज़ से पीछे) आती है, फिर 2+ शब्द का टुकड़ा, फिर बनना (HQ = Supertonic, 8 चरण) - यह अक्सर 900 ms से ज़्यादा.
 * नतीजा: हर जवाब में Live जीत जाता था, Piper का टुकड़ा बीच में रोक दिया जाता था (गलती नहीं - इसलिए कहीं दिखता भी नहीं).
 *
 * अब: Piper के लिए अलग, उसके नापे समय के हिसाब से सीमा ([firstBudget]), और हर वाक्य का असली हिसाब (pure, JVM test):
 * पहली आवाज़ कितने ms में, पूरा वाक्य कितने में, कितनी बार Live ने बीच में लिया, रोके जाने तक Piper कितनी देर चला.
 */
object PiperTiming {
    /** Piper का कोई नाप नहीं (पहली बार) - Live की आवाज़ तैयार होने के बाद इतना रुको */
    const val FIRST_BUDGET_MS = 3_000L
    /** नाप हो तो: नापा समय + इतना (लिखावट की देरी + टुकड़ा बनना) */
    const val SLACK_MS = 1_200L
    const val MIN_BUDGET_MS = 2_000L
    /** इससे ज़्यादा कभी नहीं (बहुत धीमा फ़ोन - फिर Live की आवाज़, ताकि DK चुप न लगे) */
    const val MAX_BUDGET_MS = 4_500L
    /** पहला टुकड़ा बना ही नहीं (Live की आवाज़ वाली जाँच न चली हो तब भी) */
    const val FIRST_STUCK_MS = 4_000L
    /** बीच का टुकड़ा (पिछला बज चुका) इतनी देर तक नहीं बना */
    const val NEXT_STUCK_MS = 5_000L

    /** pure: पहली आवाज़ की दौड़ में Piper को कितना समय ([lastFirstMs] = पिछले वाक्यों में पहली आवाज़, -1 = नाप नहीं) */
    fun firstBudget(lastFirstMs: Long): Long =
        if (lastFirstMs < 0) FIRST_BUDGET_MS else (lastFirstMs + SLACK_MS).coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)

    fun stuck(waitedMs: Long, first: Boolean): Boolean = waitedMs >= if (first) FIRST_STUCK_MS else NEXT_STUCK_MS

    fun firstTooSlow(now: Long, liveSince: Long, started: Boolean, open: Boolean, lastFirstMs: Long): Boolean =
        open && !started && liveSince > 0 && now - liveSince >= firstBudget(lastFirstMs)

    // ------------------------------------------------------------------ नाप (इस ऐप-सत्र में)
    @Volatile var lastFirstMs = -1L; private set
    @Volatile var lastTotalMs = -1L; private set
    @Volatile var lastAudioMs = -1L; private set
    @Volatile var lastChars = 0; private set
    @Volatile var lastLoadMs = -1L; private set
    @Volatile var spoken = 0; private set
    @Volatile var cutByLive = 0; private set
    @Volatile var lastCutAfterMs = -1L; private set
    /** कुछ वाक्यों का औसत - एक धीमा वाक्य सीमा न बिगाड़े */
    private val recent = ArrayDeque<Long>()

    /** एक वाक्य Piper ने पूरा बनाया */
    @Synchronized fun spoke(firstMs: Long, totalMs: Long, audioMs: Long, chars: Int, loadMs: Long = -1L) {
        lastFirstMs = firstMs; lastTotalMs = totalMs; lastAudioMs = audioMs; lastChars = chars
        if (loadMs >= 0) lastLoadMs = loadMs
        spoken++
        // मॉडल लोड वाला पहला वाक्य औसत में नहीं (वह एक ही बार)
        if (loadMs < 0 && firstMs >= 0) { recent.addLast(firstMs); while (recent.size > 5) recent.removeFirst() }
    }

    /** Piper अभी बना ही रहा था और रोका गया (Live ने जवाब ले लिया / आप बोले) */
    @Synchronized fun cut(afterMs: Long) { cutByLive++; lastCutAfterMs = afterMs }

    /** दौड़ के लिए: हाल के वाक्यों की औसत पहली आवाज़ (-1 = नाप नहीं) */
    @Synchronized fun typicalFirstMs(): Long = if (recent.isEmpty()) -1L else recent.sum() / recent.size

    @Synchronized fun reset() {
        lastFirstMs = -1; lastTotalMs = -1; lastAudioMs = -1; lastChars = 0; lastLoadMs = -1
        spoken = 0; cutByLive = 0; lastCutAfterMs = -1; recent.clear()
    }

    /** pure: "Piper: पहली आवाज़ 0.84 s • वाक्य 1.90 s (आवाज़ 2.40 s, 38 अक्षर) • Live ने बीच में लिया 2 बार" */
    fun line(): String = buildString {
        if (spoken == 0 && cutByLive == 0) { append("Piper ने इस बार अभी कोई वाक्य नहीं बोला"); return@buildString }
        append("Piper: ")
        if (lastFirstMs >= 0) {
            append("पहली आवाज़ ").append(s(lastFirstMs))
            if (lastTotalMs >= 0) append(" • वाक्य ").append(s(lastTotalMs))
            if (lastAudioMs > 0) append(" (आवाज़ ").append(s(lastAudioMs)).append(", ").append(lastChars).append(" अक्षर)")
        } else append("अभी पूरा वाक्य नहीं बना")
        if (lastLoadMs >= 0) append(" • मॉडल लोड ").append(s(lastLoadMs))
        append(" • बोले ").append(spoken).append(" वाक्य")
        if (cutByLive > 0) {
            append(" • Live ने बीच में लिया ").append(cutByLive).append(" बार")
            if (lastCutAfterMs >= 0) append(" (Piper ").append(s(lastCutAfterMs)).append(" से बना रहा था)")
        }
    }

    /** Live ने जवाब लिया - स्क्रीन पर साफ़ कारण */
    fun rescueWhy(waitedMs: Long, failed: Boolean): String =
        if (failed) "Piper ने आवाज़ नहीं बनाई"
        else "Piper ${s(waitedMs)} में नहीं बोला (सीमा ${s(firstBudget(typicalFirstMs()))}" +
            (if (lastFirstMs >= 0) ", पिछला वाक्य ${s(lastFirstMs)} में" else "") + ")"

    fun s(ms: Long): String = String.format(java.util.Locale.US, "%.2f s", ms / 1000.0)
}
