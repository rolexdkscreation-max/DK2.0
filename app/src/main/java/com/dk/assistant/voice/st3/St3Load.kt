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
    /** हल्के तरीके में कदम. 20 नवंबर 2026 (मालिक: "रोबोट जैसी गूंज"): 4 कदम पर आवाज़ धातु-सी थी, अब 5 से नीचे नहीं -
     *  हल्का तरीका रफ़्तार छोटे हिस्सों ([LIGHT_LEAD]) से पाता है */
    const val LIGHT_STEPS = 5
    /** हल्के तरीके में हिस्से बनाते समय आगे की तैयार आवाज़ का इतना ही भरोसा (= छोटे हिस्से) */
    const val LIGHT_LEAD = 0.6
    /** Android PowerManager.THERMAL_STATUS_MODERATE (2) से - इतना गर्म = सीधे हल्का + कम थ्रेड.
     *  20 नवंबर 2026 (मालिक का लॉग: गर्म फ़ोन पर बनी 6362 ms बनाम आवाज़ 2075 ms): पहले SEVERE (3) से था - देर से जागता */
    const val HOT_STATUS = 2
    /** गर्म फ़ोन पर इतने ही थ्रेड - गर्म (धीमे किए गए) CPU पर ज़्यादा धागे आपस में लड़ते हैं और फ़ोन और गर्म होता है */
    const val HOT_THREADS = 2
    /** एक ही टुकड़ा इतना धीमा (बनी/आवाज़ 200%+) = दूसरे का इंतज़ार नहीं, सीधे हल्का */
    const val VERY_SLOW_RTF = 2.0

    @Volatile var mode = Mode.NORMAL
        private set
    private var slowRun = 0
    private var goodRun = 0
    /** इस जवाब के टुकड़े (सार के लिए) */
    private val answerRows = ArrayList<St3Stats.Row>()
    @Volatile var heatStatus = -1
    @Volatile var headroom = Float.NaN

    // ------------------------------------------------------------ 21 नवंबर 2026: पूरे session का फ़ैसला
    /**
     * मालिक (log: बनना बजने का 108%, फ़ोन गर्म): "बनने का समय बजने के 90% से ऊपर जाए या फ़ोन गर्म हो तो थ्रेड घटाओ. फिर भी
     * धीमा रहे तो बाकी जवाब Gemini की आवाज़ से. यह बदलाव पूरे session के लिए, हर जवाब पर दोबारा मत आज़माओ."
     * [SESSION_SLOW_RTF] से ऊपर लगातार [SLOW_RUN] टुकड़े (या गर्म) = [sessionThreadsCut] (अगले जवाब की शुरुआत में कम थ्रेड
     * से मॉडल दोबारा खुलता है). उसके बाद (कम थ्रेड वाले जवाब में) फिर लगातार [SLOW_RUN] धीमे = [sessionGemini] - Live बंद
     * होने ([newSession]) तक, [newAnswer] इसे नहीं हटाता.
     */
    const val SESSION_SLOW_RTF = 0.9
    @Volatile var sessionThreadsCut = false
        private set
    @Volatile var sessionGemini = false
        private set
    private var slow90Run = 0
    /** थ्रेड घटने के बाद नया जवाब शुरू हुआ (= कम थ्रेड लागू) */
    private var cutApplied = false

    /** नया Live session: फिर से पूरे थ्रेड + ऑफ़लाइन आवाज़ से शुरू */
    @Synchronized fun newSession() { sessionThreadsCut = false; sessionGemini = false; slow90Run = 0; cutApplied = false }

    /** pure: लगातार [run] टुकड़े [SESSION_SLOW_RTF] से धीमे - (थ्रेड घटाओ?, Gemini पर जाओ?) */
    fun sessionStep(run: Int, heat: Int, cut: Boolean, applied: Boolean): Pair<Boolean, Boolean> =
        (cut || run >= SLOW_RUN || heat >= HOT_STATUS) to (cut && applied && run >= SLOW_RUN)

    private fun sessionOnRow(r: St3Stats.Row): String? {
        slow90Run = if (r.rtf > SESSION_SLOW_RTF) slow90Run + 1 else 0
        val (cutNow, gemini) = sessionStep(slow90Run, heatStatus, sessionThreadsCut, cutApplied)
        if (gemini && !sessionGemini) {
            sessionGemini = true
            return "सुधार#2 ऑफ़लाइन आवाज़: थ्रेड घटाकर भी धीमी (${St3Stats.pct(r.rtf)}) - इस पूरे session में बाकी जवाब Gemini की आवाज़ से"
        }
        if (cutNow && !sessionThreadsCut) {
            sessionThreadsCut = true; slow90Run = 0
            return "सुधार#2 ऑफ़लाइन आवाज़: बनना बजने के ${(SESSION_SLOW_RTF * 100).toInt()}% से ऊपर / फ़ोन ${heatName(heatStatus)} " +
                "(${St3Stats.pct(r.rtf)}) - इस session में थ्रेड घटाए ($HOT_THREADS), अगले जवाब से"
        }
        return null
    }

    /** session के फ़ैसले के साथ थ्रेड: घटे = [HOT_THREADS] तक. pure */
    fun threadsFor(base: Int, heat: Int, sessionCut: Boolean): Int =
        if (sessionCut) minOf(base, HOT_THREADS).coerceAtLeast(1) else threadsFor(base, heat)

    /** एक टुकड़ा बना. लौटाता है: तरीका बदला तो लॉग की लाइन, नहीं तो null */
    @Synchronized fun onRow(r: St3Stats.Row): String? {
        answerRows += r
        if (r.audioMs <= 300) return null                // बहुत छोटा टुकड़ा - नाप भरोसे लायक नहीं
        val sess = sessionOnRow(r)
        if (r.slow) { slowRun++; goodRun = 0 } else { slowRun = 0; if (r.rtf < GOOD_RTF) goodRun++ else goodRun = 0 }
        val before = mode
        mode = next(mode, slowRun, goodRun, heatStatus, if (r.slow) r.rtf else 0.0)
        if (mode != before) { slowRun = 0; goodRun = 0 }
        val line = if (mode == before) null else change(before, mode, r)
        return listOfNotNull(line, sess).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** तरीका कैसे बदले. pure */
    fun next(m: Mode, slowRun: Int, goodRun: Int, heat: Int, lastRtf: Double = 0.0): Mode = when {
        m == Mode.NORMAL && (slowRun >= SLOW_RUN || heat >= HOT_STATUS || lastRtf >= VERY_SLOW_RTF) -> Mode.LIGHT
        m == Mode.LIGHT && slowRun >= SLOW_RUN -> Mode.GEMINI
        m == Mode.LIGHT && goodRun >= GOOD_RUN && heat < HOT_STATUS -> Mode.NORMAL
        else -> m
    }

    private fun change(from: Mode, to: Mode, r: St3Stats.Row): String = "ऑफ़लाइन आवाज़: तरीका ${from.hi} -> ${to.hi} (" + when (to) {
        Mode.LIGHT -> "लगातार $SLOW_RUN टुकड़े बजने से धीमे / एक टुकड़ा बहुत धीमा / फ़ोन गर्म - अब $LIGHT_STEPS कदम, छोटे हिस्से"
        Mode.GEMINI -> "हल्के तरीके में भी धीमा - इस जवाब का बाकी Gemini की आवाज़ से"
        Mode.NORMAL -> "लगातार $GOOD_RUN टुकड़े तेज़ - फिर से आम कदम"
    } + ", आख़िरी ${St3Stats.pct(r.rtf)}, फ़ोन: ${heatName(heatStatus)})"

    /** हल्के तरीके में कदम: [steps] से ऊपर नहीं, [LIGHT_STEPS] तक. pure */
    fun stepsFor(m: Mode, steps: Int): Int = if (m == Mode.NORMAL) steps else minOf(steps, LIGHT_STEPS)

    /** हल्के तरीके में हिस्से बाँटते समय आगे की तैयार आवाज़ कम मानो (= छोटे हिस्से). pure */
    fun leadFor(m: Mode, leadMs: Long): Long = if (m == Mode.NORMAL) leadMs else (leadMs * LIGHT_LEAD).toLong()

    /** गर्म फ़ोन = कम थ्रेड ([HOT_THREADS]), ठंडा = नापी हुई गिनती. pure */
    fun threadsFor(base: Int, heat: Int): Int = if (heat >= HOT_STATUS) minOf(base, HOT_THREADS).coerceAtLeast(1) else base

    /** इस जवाब का अभी कोई टुकड़ा नहीं बना (मॉडल दोबारा खोलना हो तो बस यहीं - बीच जवाब में नहीं) */
    val freshAnswer: Boolean get() = synchronized(this) { answerRows.isEmpty() }

    /** इस जवाब का बाकी Gemini की आवाज़ से? */
    val toGemini: Boolean get() = mode == Mode.GEMINI || sessionGemini

    /**
     * नया जवाब: पिछले का सार (लॉग के लिए - पहले/बाद के आँकड़े) लौटाओ और गिनती साफ़. Gemini पर गए थे = अब हल्के से फिर आज़माओ.
     */
    @Synchronized fun newAnswer(firstMs: Long): String? {
        val s = summary(answerRows, firstMs, mode, heatStatus)
        answerRows.clear(); slowRun = 0; goodRun = 0
        if (sessionThreadsCut) cutApplied = true
        if (mode == Mode.GEMINI) mode = Mode.LIGHT
        return s
    }

    /**
     * 28 नवंबर 2026 (मालिक का लॉग: "थ्रेड घटाए (2)" के बाद भी हर टुकड़ा 4 थ्रेड): नया जवाब तब भी गिनो जब ऑफ़लाइन आवाज़
     * चुनी नहीं पर पीछे से ([fallbacks]) बोल सकती है - जैसे Gemini TTS की limit खत्म. pure
     */
    fun newAnswerFor(engine: String, fallbacks: List<String>): Boolean =
        engine == com.dk.assistant.voice.engine.VoiceEngines.ST3_OFFLINE || com.dk.assistant.voice.engine.VoiceEngines.ST3_OFFLINE in fallbacks

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
    @Synchronized fun reset() { newSession(); mode = Mode.NORMAL; slowRun = 0; goodRun = 0; answerRows.clear(); heatStatus = -1; headroom = Float.NaN; busyUntil = 0L }
}
