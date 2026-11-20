package com.dk.assistant.voice.st3

/**
 * 20 नवंबर 2026 (मालिक, लॉग: 14:24 के बाद "बनी" का समय "आवाज़" का 100-141%, शुरू में 50-60% - बीच में 1.4-3 s चुप्पी):
 * ऑफ़लाइन आवाज़ (Supertonic 3) पर बोझ का हिसाब. तीन काम:
 *  1. **धीमा पकड़ो, अपने-आप हल्का:** लगातार [SLOW_RUN] टुकड़े बजने से धीमे (rtf > 1) = [Mode.LIGHT] (कम कदम [LIGHT_STEPS],
 *     छोटे हिस्से). हल्के में भी लगातार [SLOW_RUN] धीमे = [Mode.GEMINI] (इस जवाब का बाकी Gemini की आवाज़ से). [GOOD_RUN] तेज़
 *     टुकड़े (rtf < [GOOD_RTF]) = वापस आम. नया जवाब = GEMINI से LIGHT (फिर से आज़माओ), बाकी वैसा ही.
 *  2. **फ़ोन गर्म (thermal throttling):** Android का thermal status ([heatName]) हर लॉग लाइन में; [HOT_STATUS]+ = सीधे हल्का.
 *  3. **बोलते समय भारी काम टालो:** आवाज़ बन/बज रही है ([busy]) = याद का सार, standby connection जैसे पीछे के काम रुकें.
 * Android import नहीं - JVM test ([St3LoadTest]).
 */
object St3Load {
    enum class Mode(val hi: String) { NORMAL("आम"), LIGHT("हल्का"), GEMINI("Gemini आवाज़") }

    const val SLOW_RUN = 2
    const val GOOD_RUN = 4
    const val GOOD_RTF = 0.7
    /** हल्के तरीके में कदम (आम में 5 से नीचे नहीं जाते - वहाँ आवाज़ थोड़ी धातु-सी, इसलिए सिर्फ़ धीमे पल में) */
    const val LIGHT_STEPS = 4
    /** हल्के तरीके में हिस्से बनाते समय आगे की तैयार आवाज़ का इतना ही भरोसा (= छोटे हिस्से) */
    const val LIGHT_LEAD = 0.6
    /** Android PowerManager.THERMAL_STATUS_SEVERE (3) से - इतना गर्म = सीधे हल्का */
    const val HOT_STATUS = 3

    @Volatile var mode = Mode.NORMAL
        private set
    private var slowRun = 0
    private var goodRun = 0
    /** इस जवाब के टुकड़े (सार के लिए) */
    private val answerRows = ArrayList<St3Stats.Row>()
    @Volatile var heatStatus = -1
    @Volatile var headroom = Float.NaN

    /** एक टुकड़ा बना. लौटाता है: तरीका बदला तो लॉग की लाइन, नहीं तो null */
    @Synchronized fun onRow(r: St3Stats.Row): String? {
        answerRows += r
        if (r.audioMs <= 300) return null                // बहुत छोटा टुकड़ा - नाप भरोसे लायक नहीं
        if (r.slow) { slowRun++; goodRun = 0 } else { slowRun = 0; if (r.rtf < GOOD_RTF) goodRun++ else goodRun = 0 }
        val before = mode
        mode = next(mode, slowRun, goodRun, heatStatus)
        if (mode != before) { slowRun = 0; goodRun = 0 }
        return if (mode == before) null else change(before, mode, r)
    }

    /** तरीका कैसे बदले. pure */
    fun next(m: Mode, slowRun: Int, goodRun: Int, heat: Int): Mode = when {
        m == Mode.NORMAL && (slowRun >= SLOW_RUN || heat >= HOT_STATUS) -> Mode.LIGHT
        m == Mode.LIGHT && slowRun >= SLOW_RUN -> Mode.GEMINI
        m == Mode.LIGHT && goodRun >= GOOD_RUN && heat < HOT_STATUS -> Mode.NORMAL
        else -> m
    }

    private fun change(from: Mode, to: Mode, r: St3Stats.Row): String = "ऑफ़लाइन आवाज़: तरीका ${from.hi} -> ${to.hi} (" + when (to) {
        Mode.LIGHT -> "लगातार $SLOW_RUN टुकड़े बजने से धीमे / फ़ोन गर्म - अब $LIGHT_STEPS कदम, छोटे हिस्से"
        Mode.GEMINI -> "हल्के तरीके में भी धीमा - इस जवाब का बाकी Gemini की आवाज़ से"
        Mode.NORMAL -> "लगातार $GOOD_RUN टुकड़े तेज़ - फिर से आम कदम"
    } + ", आख़िरी ${St3Stats.pct(r.rtf)}, फ़ोन: ${heatName(heatStatus)})"

    /** हल्के तरीके में कदम: [steps] से ऊपर नहीं, [LIGHT_STEPS] तक. pure */
    fun stepsFor(m: Mode, steps: Int): Int = if (m == Mode.NORMAL) steps else minOf(steps, LIGHT_STEPS)

    /** हल्के तरीके में हिस्से बाँटते समय आगे की तैयार आवाज़ कम मानो (= छोटे हिस्से). pure */
    fun leadFor(m: Mode, leadMs: Long): Long = if (m == Mode.NORMAL) leadMs else (leadMs * LIGHT_LEAD).toLong()

    /** इस जवाब का बाकी Gemini की आवाज़ से? */
    val toGemini: Boolean get() = mode == Mode.GEMINI

    /**
     * नया जवाब: पिछले का सार (लॉग के लिए - पहले/बाद के आँकड़े) लौटाओ और गिनती साफ़. Gemini पर गए थे = अब हल्के से फिर आज़माओ.
     */
    @Synchronized fun newAnswer(firstMs: Long): String? {
        val s = summary(answerRows, firstMs, mode, heatStatus)
        answerRows.clear(); slowRun = 0; goodRun = 0
        if (mode == Mode.GEMINI) mode = Mode.LIGHT
        return s
    }

    /** एक जवाब का सार. pure */
    fun summary(rows: List<St3Stats.Row>, firstMs: Long, m: Mode, heat: Int): String? {
        if (rows.isEmpty()) return null
        val synth = rows.sumOf { it.synthMs }; val audio = rows.sumOf { it.audioMs }
        val avg = if (audio <= 0) 0.0 else synth.toDouble() / audio
        val worst = rows.maxOf { it.rtf }
        return "ऑफ़लाइन आवाज़ (पिछला जवाब): ${rows.size} टुकड़े, बनी/आवाज़ औसत ${(avg * 100).toInt()}% (सबसे धीमा ${(worst * 100).toInt()}%), " +
            "धीमे ${rows.count { it.slow }}, पहली आवाज़ ${if (firstMs > 0) "$firstMs ms" else "-"}, तरीका ${m.hi}, फ़ोन: ${heatName(heat)}"
    }

    /** Android thermal status -> हिंदी. pure */
    fun heatName(status: Int): String = when (status) {
        -1 -> "पता नहीं"
        0 -> "सामान्य"
        1 -> "हल्का गर्म"
        2 -> "गर्म"
        3 -> "बहुत गर्म"
        4 -> "बहुत ज़्यादा गर्म"
        else -> "ख़तरनाक गर्म"
    }

    /** thermal बदला तो लॉग की लाइन. pure-ish (सिर्फ़ यही object) */
    @Synchronized fun onHeat(status: Int, room: Float): String? {
        val before = heatStatus
        heatStatus = status; headroom = room
        if (status == before || status < 0) return null
        val r = if (room.isNaN()) "" else ", headroom ${String.format(java.util.Locale.US, "%.2f", room)}"
        return "ऑफ़लाइन आवाज़: फ़ोन ${heatName(status)} (thermal $status$r)" + if (status >= 2) " - CPU धीमा हो सकता है, आवाज़ बनने का समय बढ़ेगा" else ""
    }

    // ------------------------------------------------------------ बोलते समय भारी काम टालो
    @Volatile private var busyUntil = 0L
    /** आवाज़ बन/बज रही है - इतने तक भारी काम नहीं */
    fun mark(until: Long) { if (until > busyUntil) busyUntil = until }
    fun busy(now: Long = System.currentTimeMillis()): Boolean = now < busyUntil

    /** भारी काम को रुकना चाहिए? (बोल रहा + अभी तक [maxWaitMs] नहीं रुके) pure-ish */
    const val SPARE_DEFER_MS = 30_000L
    const val MEMORY_DEFER_MS = 60_000L

    fun shouldDefer(now: Long, waitedMs: Long, maxWaitMs: Long): Boolean = busy(now) && waitedMs < maxWaitMs

    /**
     * 20 नवंबर 2026 (मालिक का 15:53 लॉग): थ्रेड जाँच (6 बार मॉडल खोलना-बंद, ~15-25 s lock) अभी चलाएँ? मॉडल पहले से खुला
     * (बातचीत चल रही) या आवाज़ बन/बज रही = नहीं. pure
     */
    fun benchNow(modelOpen: Boolean, busy: Boolean): Boolean = !modelOpen && !busy

    /** (test के लिए) सब साफ़ */
    @Synchronized fun reset() { mode = Mode.NORMAL; slowRun = 0; goodRun = 0; answerRows.clear(); heatStatus = -1; headroom = Float.NaN; busyUntil = 0L }
}
