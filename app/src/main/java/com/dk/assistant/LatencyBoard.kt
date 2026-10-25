package com.dk.assistant

/**
 * v3.71: बोलने की देरी का हिसाब (सेटिंग्स → डिबग लॉग). हर बारी के तीन समय, सब "आपका बोलना ख़त्म" से:
 *  1. जवाब का पहला टुकड़ा (text या Live का ऑडियो) फ़ोन तक आया
 *  2. पहले वाक्य की आवाज़ बनकर आई (Live की सीधी आवाज़ में यह = 1)
 *  3. आवाज़ बजना शुरू हुई
 * Android import नहीं -> JVM test.
 */
object LatencyBoard {
    data class Row(val engine: String, val firstPiece: Long, val firstVoice: Long, val play: Long, val at: Long)

    private const val KEEP = 12
    private val rows = ArrayDeque<Row>()

    /** समय ms में (epoch); 0 = पता नहीं */
    fun record(engine: String, speechEnd: Long, piece: Long, voice: Long, play: Long, now: Long = System.currentTimeMillis()): Row? {
        if (speechEnd <= 0 || play <= 0 || play < speechEnd || play - speechEnd > 60_000) return null
        fun d(t: Long) = if (t in speechEnd..play) t - speechEnd else -1
        val r = Row(engine, d(piece), d(voice), play - speechEnd, now)
        synchronized(rows) { rows.addLast(r); while (rows.size > KEEP) rows.removeFirst() }
        return r
    }

    fun line(r: Row): String {
        fun f(v: Long) = if (v >= 0) "$v ms" else "?"
        return "${r.engine}: पहला टुकड़ा ${f(r.firstPiece)} • पहले वाक्य की आवाज़ बनी ${f(r.firstVoice)} • बजना शुरू ${f(r.play)}"
    }

    fun all(): List<Row> = synchronized(rows) { rows.toList() }

    /** हर इंजन का औसत "बजना शुरू" (सबसे नई बारियाँ) */
    fun averages(): Map<String, Long> = all().groupBy { it.engine }.mapValues { (_, v) -> v.map { it.play }.average().toLong() }

    fun text(): String {
        val a = all()
        if (a.isEmpty()) return "अभी कोई बारी नहीं - आवाज़ स्क्रीन पर कुछ बोलें, फिर \"ताज़ा करें\""
        val avg = averages().entries.joinToString("\n") { (e, ms) -> "औसत ($e): बजना शुरू $ms ms" }
        return a.asReversed().joinToString("\n") { line(it) } + "\n\n" + avg
    }

    fun clear() = synchronized(rows) { rows.clear() }
}
