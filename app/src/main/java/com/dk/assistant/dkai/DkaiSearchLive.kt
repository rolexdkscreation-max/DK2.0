package com.dk.assistant.dkai

/**
 * 13 नवंबर 2026 (मालिक - लाइव सर्च कार्ड): सिर्फ़ असली वेब खोज (Live का web_search / वादा-जाँच वाली खोज) चलने पर
 * चैट में "Processing · Searching…" कार्ड. साइटें सिर्फ़ असली नतीजे के स्रोतों से - कोई नकली नाम नहीं.
 * Broadcast की रिसर्च यहाँ नहीं जुड़ती (वहाँ कार्ड नहीं दिखना चाहिए).
 * Android के बिना (JVM test में चलता है).
 */
object DkaiSearchLive {
    /** एक-एक साइट कितनी देर में जुड़े */
    const val STEP_MS = 220L
    /** कुछ अटक जाए तो कार्ड अपने-आप हटे */
    const val MAX_MS = 60_000L
    const val MAX_SITES = 24

    data class Snap(val on: Boolean, val query: String, val sites: List<String>, val shown: Int, val found: Boolean, val stage: String = "") {
        val visited get() = shown
    }

    private var q = ""
    private var startAt = 0L
    private var foundAt = 0L
    private var sites: List<String> = emptyList()
    // 14 नवंबर 2026 (मालिक: "चैट में भी लाइव सर्च दिखे - कौन-कौन सी वेबसाइट पर जा रहा है"): अभी क्या हो रहा + लंबी (डीप) खोज की सीमा
    private var stageText = ""
    private var limitMs = MAX_MS
    /** डीप रिसर्च (कई मिनट) = कार्ड इतनी देर तक */
    const val DEEP_MS = 300_000L

    @Synchronized fun start(query: String, now: Long = System.currentTimeMillis(), maxMs: Long = MAX_MS) {
        q = query.replace(Regex("\\s+"), " ").trim().take(80); startAt = now; foundAt = 0L; sites = emptyList(); stageText = ""; limitMs = maxMs
    }

    /** अभी कौन-सा कदम ("Tavily पर खोज रहा है…", "पढ़ा: ndtv.com") */
    @Synchronized fun stage(t: String) { if (startAt != 0L) stageText = t.trim().take(90) }

    /** खोज के बीच मिलती वेबसाइटें - पुरानी के बाद नई जोड़ो (दोहराव नहीं) */
    @Synchronized fun add(names: List<String>, now: Long = System.currentTimeMillis()) {
        if (startAt == 0L) return
        val more = (sites + hosts(names)).distinct().take(MAX_SITES)
        if (foundAt == 0L && more.isNotEmpty()) foundAt = now
        sites = more
    }

    /** असली नतीजा आया: उसकी वेबसाइटें ([com.dk.assistant.Grounding.sites] से - Google के redirect की जगह असली नाम) */
    @Synchronized fun found(names: List<String>, now: Long = System.currentTimeMillis()) {
        if (startAt == 0L) return
        sites = hosts(names); foundAt = now
    }

    @Synchronized fun cancel() { startAt = 0L; foundAt = 0L; sites = emptyList(); q = ""; stageText = ""; limitMs = MAX_MS }

    @Synchronized fun startedAt() = startAt

    @Synchronized fun active(now: Long = System.currentTimeMillis()): Boolean {
        if (startAt == 0L) return false
        if (now - startAt > limitMs) { cancel(); return false }
        return true
    }

    @Synchronized fun snap(now: Long = System.currentTimeMillis()): Snap {
        if (!active(now)) return Snap(false, "", emptyList(), 0, false)
        return Snap(true, q, sites, shown(sites.size, foundAt, now), foundAt > 0L, stageText)
    }

    /** साइटें एक-एक करके: नतीजा आने के बाद हर [STEP_MS] पर एक और */
    fun shown(n: Int, foundAt: Long, now: Long): Int {
        if (foundAt <= 0L || n <= 0) return 0
        return minOf(n.toLong(), (now - foundAt).coerceAtLeast(0L) / STEP_MS + 1).toInt()
    }

    /** "https://www.aaj.tv/news/1" -> "aaj.tv", "www.youtube.com" -> "youtube.com" (दोहराव हटाकर, क्रम वही) */
    fun hosts(names: List<String>): List<String> = names.mapNotNull { n ->
        val t = n.trim()
        when {
            t.isBlank() -> null
            t.contains("://") || (!t.contains(' ') && t.contains('/')) -> host(t)
            !t.contains(' ') && t.contains('.') -> host(t)
            else -> t.take(28)
        }
    }.distinct().take(MAX_SITES)

    fun host(url: String): String? {
        val u = url.trim()
        if (u.isBlank()) return null
        val h = u.substringAfter("://", u).substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase().removePrefix("www.").removePrefix("m.")
        return h.takeIf { it.contains('.') && it.length >= 4 && !it.contains(' ') }
    }
}
