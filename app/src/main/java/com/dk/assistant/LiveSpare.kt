package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "Gemini Live ऐसे बनाओ कि server की कोई सीमा हो तो दूसरा connection पहले से तैयार रहे;
 * टूटने से पहले - जैसे ही Gemini उधर से संदेश (goAway) दे - दूसरा connection जुड़ जाए और यूज़र को कुछ पता न चले"):
 *
 * Gemini Live का एक WebSocket connection कुछ ही मिनट (~10) चलता है; कटने से पहले server `goAway` भेजता है.
 * पहले: goAway पर सिर्फ़ तब पीछे से नया connection बनता था जब DK बोल रहा हो; चुप हो तो पहले काटते फिर जोड़ते (छोटा गैप).
 * अब:
 *  1. **पहले से (proactive):** connection [PREPARE_AT_MS] चल गया = goAway का इंतज़ार किए बिना पीछे से उसी बातचीत
 *     (session resumption handle) का दूसरा connection तैयार (standby). DK चुप होते ही उस पर स्विच - 0 ms, याद वही.
 *  2. **goAway पर हमेशा:** DK बोल रहा हो या चुप - तुरंत दूसरा connection. चुप हो तब भी पुराना तभी छोड़ो जब नया तैयार हो
 *     (या समय लगभग खत्म) - बीच में "जुड़ रहा हूँ" वाला गैप नहीं.
 *  3. बीच में बात हुई (handle बदला) = पुराना standby बेकार -> चुप होते ही ताज़ा handle से नया standby.
 *
 *  4. **नवंबर 2026 (मालिक, बिना रुकावट बदलना):** पहले standby उस पल के handle से बनता था; यूज़र एक भी बात बोले = server नया
 *     handle देता = standby पुराना (उस पर जाएँ तो आखिरी बात भूल जाए) -> लगातार बात में स्विच कभी नहीं होता था, server 10 मिनट
 *     पर काटता और सामान्य reconnect (1-3s gap). अब "थामो और बदलो" (freeze): 8 मिनट बाद (या goAway पर) पहले शांत पल में,
 *     जवाब पूरा + server का ताज़ा handle आने के बाद, पुराने connection पर कुछ नया नहीं भेजते (handle पक्का रहे) - माइक की
 *     आवाज़ फ़ोन में जमा ([FREEZE_PENDING_MS] तक) - और उसी ताज़ा handle से दूसरा connection. तैयार होते ही उस पर स्विच और
 *     जमा आवाज़ वहीं भेजी (कोई शब्द नहीं खोता). [FREEZE_MAX_MS] में न बने = जमा आवाज़ पुराने पर ही, बात नहीं रुकती.
 *
 * Pure logic - Android import नहीं -> JVM test ([LiveSpareTest]).
 */
object LiveSpare {
    /** इतना चलने पर goAway से पहले ही दूसरा connection तैयार (Gemini Live का connection ~10 मिनट) */
    const val PREPARE_AT_MS = 8 * 60_000L
    /** एक तैयारी (दौड़) को इतना समय - न बना तो फिर कोशिश */
    const val SPARE_RACE_MS = 10_000L
    /** goAway में समय न लिखा हो तो मानो इतना बाकी */
    const val GOAWAY_DEFAULT_LEFT_MS = 5_000L
    /** goAway: कटने से इतना पहले तक नया न बना = पुराना छोड़कर सामान्य तरीके से जोड़ो (server के काटने से पहले) */
    const val GOAWAY_MARGIN_MS = 700L

    /** goAway का timeLeft (ms, -1 = पता नहीं) -> कितना मानें */
    fun goAwayLeft(leftMs: Long): Long = if (leftMs > 0) leftMs else GOAWAY_DEFAULT_LEFT_MS

    /** दूसरा connection अब बनाना चाहिए? (goAway आया या connection पुराना हो चला) */
    fun due(goAway: Boolean, ageMs: Long): Boolean = goAway || ageMs >= PREPARE_AT_MS

    /**
     * तैयारी शुरू करें? resume handle हो (वरना नया connection = नई बातचीत, याद टूटेगी), keys हों,
     * ताज़ा standby पहले से न हो, और पिछली तैयारी अभी चल न रही हो.
     * standby है पर पुराने handle का (बीच में बात हुई) = सिर्फ़ चुप होने पर बदलो (बोलते-बोलते बार-बार नहीं).
     */
    fun shouldPrepare(stopped: Boolean, haveHandle: Boolean, haveKeys: Boolean, freshSpare: Boolean,
                      staleSpare: Boolean, idle: Boolean, sinceLastTryMs: Long): Boolean =
        !stopped && haveHandle && haveKeys && !freshSpare && sinceLastTryMs >= SPARE_RACE_MS && (!staleSpare || idle)

    /** goAway + DK चुप: नया अभी बन रहा है और समय बाकी = पुराने पर ही रुको (गैप नहीं) */
    fun waitForSpare(spareReady: Boolean, sinceTryMs: Long, msLeft: Long): Boolean =
        !spareReady && sinceTryMs < SPARE_RACE_MS && msLeft > GOAWAY_MARGIN_MS

    /** goAway आए बिना (पहले से) स्विच: connection पुराना + ताज़ा standby तैयार + DK चुप */
    fun rotateNow(ageMs: Long, spareReady: Boolean, idle: Boolean): Boolean =
        ageMs >= PREPARE_AT_MS && spareReady && idle

    /** स्विच के बाद रुकना नहीं (standby तैयार = 0 ms); वरना पुराना backoff */
    fun waitAfterSwitch(seamless: Boolean, backoff: Long): Long = if (seamless) 0L else backoff

    // ---------------------------------------------------------------- 4. थामो और बदलो (freeze)
    /**
     * थामना सिर्फ़ इतना चलने के बाद (या goAway पर). 8-9 मिनट: पुराना तरीका (शांत पल में ताज़ा standby, बिना थामे - आपकी बात
     * कभी नहीं रुकती; offline नाप में ~98% बार यही काफ़ी). 9 मिनट तक न हुआ (लगातार बात) = थामो, server के काटने से पहले पक्का.
     */
    const val FREEZE_AT_MS = 9 * 60_000L
    fun freezeDue(goAway: Boolean, ageMs: Long): Boolean = goAway || ageMs >= FREEZE_AT_MS

    /** थामने के बाद नया connection इतने में न बने = पुराने पर लौटो (जमा आवाज़ वहीं) */
    const val FREEZE_MAX_MS = 4_000L
    /** थामे रहने तक फ़ोन में इतनी आवाज़ जमा (बाकी समय पहले जैसा 3s) */
    const val FREEZE_PENDING_MS = 4_500L
    /** जवाब/आपकी आवाज़ के बाद इतना शांत = थाम सकते हैं (server का ताज़ा handle इसी बीच आता है) */
    const val SETTLE_MS = 400L
    /** handle न आए तब भी इतना शांत = जो handle है वही ताज़ा मानो */
    const val SETTLE_NO_HANDLE_MS = 2_500L
    /** नाकाम थामने के बाद अगली कोशिश इतने बाद (चक्कर नहीं) */
    const val FREEZE_RETRY_MS = 20_000L

    /**
     * शांत पल में थामें? due = [freezeDue] (9 मिनट हुए या goAway). idle = DK चुप, आप चुप, कोई काम नहीं.
     * handleFresh = पिछले जवाब के बाद server का नया handle आ गया (या पहले से कोई जवाब ही नहीं).
     * quietMs = पिछले जवाब/आपकी आवाज़ से कितना शांत.
     */
    fun startFreeze(due: Boolean, idle: Boolean, frozen: Boolean, spareReady: Boolean, haveHandle: Boolean,
                    haveKeys: Boolean, handleFresh: Boolean, quietMs: Long, sinceFreezeEndMs: Long): Boolean =
        due && idle && !frozen && !spareReady && haveHandle && haveKeys && sinceFreezeEndMs >= FREEZE_RETRY_MS &&
            quietMs >= (if (handleFresh) SETTLE_MS else SETTLE_NO_HANDLE_MS)

    enum class Freeze { WAIT, SWITCH, RESUME_OLD, BREAK }

    /**
     * थामे हुए: अब क्या? SWITCH = ताज़ा standby तैयार -> उस पर (जमा आवाज़ वहीं). RESUME_OLD = नहीं बना / handle बदल गया ->
     * पुराने पर लौटो (जमा आवाज़ पुराने पर). BREAK = goAway का समय खत्म / पुराना जा रहा -> सामान्य नया connection (जमा आवाज़ उस पर).
     */
    fun freezeStep(spareReady: Boolean, handleSame: Boolean, preparing: Boolean, frozenMs: Long,
                   goAway: Boolean, msLeft: Long): Freeze = when {
        spareReady && handleSame -> Freeze.SWITCH
        goAway && msLeft <= GOAWAY_MARGIN_MS -> Freeze.BREAK
        !handleSame || !preparing || frozenMs >= FREEZE_MAX_MS -> if (goAway) Freeze.BREAK else Freeze.RESUME_OLD
        else -> Freeze.WAIT
    }

    /** goAway आया, DK अभी-अभी चुप हुआ (handle अभी आ रहा): थामने तक पुराने पर रुको अगर समय बचा है */
    fun goAwaySettling(settling: Boolean, msLeft: Long): Boolean =
        settling && msLeft > FREEZE_MAX_MS + GOAWAY_MARGIN_MS

    /** पिछले जवाब के बाद server ने नया handle दिया? */
    fun handleFresh(handleAt: Long, lastReplyDoneAt: Long): Boolean = lastReplyDoneAt == 0L || handleAt >= lastReplyDoneAt
}
