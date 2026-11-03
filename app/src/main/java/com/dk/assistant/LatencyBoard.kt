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
        stage("$engine: पहला टुकड़ा (token/chunk)", r.firstPiece)
        stage("$engine: पहले वाक्य की आवाज़ बनी (TTS)", r.firstVoice)
        stage("$engine: बजना शुरू", r.play)
        return r
    }

    // ---- नवंबर 2026 (Smart turn): हर चरण का median / p95 (आख़िरी [STAGE_KEEP] बारियाँ) + बात-खत्म की गिनती
    private const val STAGE_KEEP = 100
    private val stages = LinkedHashMap<String, ArrayDeque<Long>>()
    private val counts = LinkedHashMap<String, Int>()

    fun stage(name: String, ms: Long) {
        if (ms < 0 || ms > 120_000) return
        synchronized(stages) { val q = stages.getOrPut(name) { ArrayDeque() }; q.addLast(ms); while (q.size > STAGE_KEEP) q.removeFirst() }
    }

    fun count(name: String) = synchronized(counts) { counts[name] = (counts[name] ?: 0) + 1 }

    /** nearest-rank percentile */
    fun pct(xs: List<Long>, q: Double): Long {
        if (xs.isEmpty()) return -1
        val s = xs.sorted(); val k = kotlin.math.ceil(q * s.size).toInt().coerceIn(1, s.size)
        return s[k - 1]
    }

    /** TurnTimeline (turn पूरा होने पर): सब "आपका बोलना खत्म" से */
    fun timeline(t: TurnTimeline) {
        val s = t.speechEndAt
        if (s <= 0) return
        fun d(x: Long) = if (x >= s) x - s else -1
        stage("1 बोलना खत्म → turn भेजा (endpoint)", d(t.endpointAt))
        stage("2 बोलना खत्म → पहला server जवाब (token)", d(t.eventAt))
        stage("3 बोलना खत्म → पहला ऑडियो टुकड़ा", d(t.audioAt))
        stage("4 बोलना खत्म → पहली आवाज़ बजी", d(t.playAt))
        stage("5 बोलना खत्म → पूरा जवाब (turnComplete)", d(t.doneAt))
        if (t.endpointAt in s..t.playAt) stage("turn भेजा → पहली आवाज़ बजी", t.playAt - t.endpointAt)
    }

    fun stageText(): String {
        val st = synchronized(stages) { stages.mapValues { it.value.toList() } }
        val ct = synchronized(counts) { counts.toMap() }
        if (st.isEmpty() && ct.isEmpty()) return ""
        val a = st.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> "$k: median ${pct(v, 0.5)} ms • p95 ${pct(v, 0.95)} ms (n=${v.size})" }
        val b = ct.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        return listOf(a, b).filter { it.isNotBlank() }.joinToString("\n\n")
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
        val st = stageText()
        return a.asReversed().joinToString("\n") { line(it) } + "\n\n" + avg + (if (st.isNotBlank()) "\n\n$st" else "")
    }

    fun clear() { synchronized(rows) { rows.clear() }; synchronized(stages) { stages.clear() }; synchronized(counts) { counts.clear() } }
}
