package com.dk.assistant

/**
 * v3.41: DK लाइव का मॉडल "सबसे नया" नहीं, "सबसे तेज़" (Android import नहीं -> JVM unit test).
 *
 * जड़: पहले discoverModels सबसे बड़ा version नंबर (3.8) पहले लगाता था. नया = हमेशा तेज़ नहीं (3.8-live में
 * सोचना बंद नहीं होता) - जवाब देर से आता था.
 * अब: हर मॉडल का असली "बोलना खत्म → पहली आवाज़" समय फ़ोन पर नापकर याद (औसत). जिसका समय सबसे कम वही पहले.
 * जो अभी नापा नहीं गया उसका अंदाज़ा नाम से (flash-lite < flash < native-audio < बाकी < thinking) - इसलिए
 * नया मॉडल भी एक बार आज़माया जाता है और नपते ही अपनी असली जगह पर आ जाता है.
 * limit खत्म (blocked) वाले सबसे पीछे - Live उन्हें छोड़कर अगला सबसे तेज़ लगाता है.
 */
object LiveModelRank {
    /** नापे समय का औसत: नया नमूना कितना असर करे (0.3 = पिछले ~3 बार का ध्यान) */
    const val ALPHA = 0.3
    /** इससे ज़्यादा देरी = नेटवर्क/tool की गड़बड़ी, मॉडल की नहीं -> मत गिनो */
    const val MAX_SAMPLE_MS = 8_000L

    /** ये मॉडल बातचीत नहीं करते (सिर्फ़ लिखना / अनुवाद / robot) */
    fun usable(m: String) = !m.contains("translat") && !m.contains("transcri") && !m.contains("robotic")

    private fun ver(m: String) = Regex("(\\d+(?:\\.\\d+)?)").find(m)?.value?.toDoubleOrNull() ?: 0.0

    /** बिना नापे मॉडल का अंदाज़ा (ms) - सिर्फ़ क्रम के लिए */
    fun guessMs(m: String): Long = when {
        m.contains("thinking") -> 3000
        m.contains("flash-lite") || m.contains("lite") -> 750
        m.contains("flash") -> 900
        m.contains("native-audio") -> 1000
        m.contains("live") -> 1100
        else -> 1600
    }

    /** नया नमूना जोड़ो -> नया औसत (null = नमूना गिनने लायक नहीं) */
    fun blend(old: Long?, sample: Long): Long? {
        if (sample <= 0 || sample > MAX_SAMPLE_MS) return null
        if (old == null || old <= 0) return sample
        return (old * (1 - ALPHA) + sample * ALPHA).toLong()
    }

    /**
     * Live मॉडलों का क्रम: सबसे तेज़ पहले.
     * @param found इस key पर सच में मिले मॉडल (खाली = list नहीं आई)
     * @param fallback docs वाले जाने-माने मॉडल (list न आए तब भी)
     * @param latency नापा औसत समय (मॉडल -> ms)
     * @param blocked limit खत्म वाले
     */
    fun order(found: List<String>, fallback: List<String>, latency: Map<String, Long>, blocked: (String) -> Boolean = { false }): List<String> {
        fun score(m: String) = latency[m]?.takeIf { it > 0 } ?: guessMs(m)
        fun fastFirst(l: List<String>) = l.filter { usable(it) }.distinct()
            .sortedWith(compareBy<String> { score(it) }.thenByDescending { ver(it) })
        // key पर सच में मिले मॉडल पहले; docs वाले (जो इस key पर शायद हों ही नहीं) बाद में - बेकार connection = देरी
        val sorted = (fastFirst(found) + fastFirst(fallback)).distinct()
        val (bad, ok) = sorted.partition(blocked)
        return ok + bad
    }

    /** Settings में दिखाने के लिए: "gemini-x: 640 ms" */
    fun describe(order: List<String>, latency: Map<String, Long>, max: Int = 4): String =
        order.take(max).joinToString("\n") { m -> "• $m: " + (latency[m]?.let { "$it ms" } ?: "अभी नापा नहीं") }

    /** "m=123;n=456" <-> map (SharedPreferences में एक ही string) */
    fun encode(map: Map<String, Long>): String = map.entries.joinToString(";") { "${it.key}=${it.value}" }
    fun decode(s: String): Map<String, Long> = s.split(';').mapNotNull { p ->
        val i = p.lastIndexOf('='); if (i <= 0) null else p.substring(i + 1).toLongOrNull()?.let { p.substring(0, i) to it }
    }.toMap()
}
