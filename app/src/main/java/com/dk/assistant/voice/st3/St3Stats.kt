package com.dk.assistant.voice.st3

/**
 * 19 नवंबर 2026 (मालिक: "हर वाक्य का समय लॉग करो - बनने में कितना लगा, आवाज़ कितनी लंबी; बनना आवाज़ से धीमा हो तो
 * बताओ"): ऑफ़लाइन आवाज़ का नाप. हर वाक्य = एक [Row]. Settings -> आवाज़ें -> "3." में दिखता है + डिबग लॉग में.
 * Android के बिना (pure) - JVM test.
 */
object St3Stats {
    data class Row(val chars: Int, val synthMs: Long, val audioMs: Long, val steps: Int, val threads: Int,
                   val xnn: Boolean = false, val stepsMs: Long = -1, val wavMs: Long = -1) {
        /** बनने का समय / आवाज़ की लंबाई. 1 से ज़्यादा = बनना बजने से धीमा = बीच में चुप्पी पक्की */
        val rtf: Double get() = if (audioMs <= 0) 0.0 else synthMs.toDouble() / audioMs
        val slow: Boolean get() = rtf > 1.0
    }

    private const val KEEP = 30
    private val rows = ArrayDeque<Row>()
    /** पिछले जवाब में: जवाब का पहला text आने से पहली आवाज़ बनने तक (ms). 0 = अभी नहीं नापा */
    @Volatile var lastFirstMs = 0L
        private set
    /** Settings का टेस्ट: बटन दबाने से पहली आवाज़ तक (ms) */
    @Volatile var lastTestFirstMs = 0L
        private set

    @Synchronized fun add(r: Row) { rows.addLast(r); while (rows.size > KEEP) rows.removeFirst() }
    @Synchronized fun recent(n: Int = 8): List<Row> = rows.takeLast(n)
    @Synchronized fun clear() { rows.clear() }
    fun firstAudio(ms: Long) { if (ms in 0..60_000) lastFirstMs = ms }
    fun testFirstAudio(ms: Long) { if (ms in 0..60_000) lastTestFirstMs = ms }

    /** लॉग की एक लाइन */
    fun line(r: Row): String = "ऑफ़लाइन आवाज़: ${r.chars} अक्षर, ${r.steps} कदम, ${r.threads} थ्रेड${if (r.xnn) " XNN" else ""}, बनी ${r.synthMs} ms में, " +
        "आवाज़ ${r.audioMs} ms (${pct(r.rtf)})" + (if (r.stepsMs >= 0) " [कदम ${r.stepsMs} ms, लहर ${r.wavMs} ms]" else "") + if (r.slow) " - धीमा: बनना बजने से ज़्यादा समय ले रहा है" else ""

    /** स्क्रीन के लिए छोटा सार */
    fun summary(list: List<Row>): String {
        if (list.isEmpty()) return "अभी कोई नाप नहीं - टेस्ट वाक्य सुनाएँ या बात करें"
        val synth = list.sumOf { it.synthMs }; val audio = list.sumOf { it.audioMs }
        val avg = if (audio <= 0) 0.0 else synth.toDouble() / audio
        val slow = list.count { it.slow }
        val head = "पिछले ${list.size} वाक्य: बनने में कुल ${synth} ms, आवाज़ कुल ${audio} ms (औसत ${pct(avg)}) • ${list.last().threads} थ्रेड"
        return head + when {
            slow > 0 && list.last().steps <= 5 -> "\n✗ $slow वाक्य बनने में अपनी आवाज़ से ज़्यादा समय - कदम पहले से 5 हैं. यह फ़ोन (बातचीत के बाकी काम के साथ) इस मॉडल के लिए धीमा है - \"थ्रेड फिर से जाँचें\" दबाएँ."
            slow > 0 -> "\n✗ $slow वाक्य बनने में अपनी आवाज़ से ज़्यादा समय - बीच में चुप्पी आएगी. \"कदम\" कम करें (जैसे 5) या हल्का मॉडल चाहिए."
            avg > 0.6 -> "\n! बनना आवाज़ के करीब है - धीमे पलों में छोटी चुप्पी आ सकती है"
            else -> "\n✓ बनना बजने से तेज़ - अगला वाक्य समय पर तैयार"
        }
    }

    /** 0.42 -> "आवाज़ का 42%" */
    fun pct(rtf: Double): String = "आवाज़ का ${(rtf * 100).toInt()}% समय"

    // ------------------------------------------------------------ थ्रेड चुनना
    /** किन थ्रेड-गिनतियों को आज़माएँ: 4 से 6 (फ़ोन में जितने core हों उससे ज़्यादा नहीं); कम core = जितने हैं */
    fun threadChoices(cores: Int): List<Int> = if (cores < 4) listOf(cores.coerceAtLeast(1)) else (4..minOf(6, cores)).toList()

    /** नापे हुए (थ्रेड -> ms) में सबसे तेज़; बराबरी (5% के अंदर) पर कम थ्रेड (फ़ोन कम गरम, बाकी ऐप को CPU) */
    fun fastest(times: Map<Int, Long>): Int? {
        if (times.isEmpty()) return null
        val best = times.values.minOrNull() ?: return null
        return times.filter { it.value <= best * 1.05 }.keys.minOrNull()
    }
}
