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
}
