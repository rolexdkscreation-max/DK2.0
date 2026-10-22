package com.dk.assistant

/**
 * ऐप की स्क्रीनों (UI) की भाषा का शब्दकोश - Android import नहीं -> JVM test.
 * code में UI के text हिंदी में लिखे हैं; English चुनी हो तो यहीं से English बनती है (assets/ui_en.tsv).
 * 1) पूरा text शब्दकोश में = सीधा English
 * 2) नहीं तो अंदर के जाने-पहचाने हिस्से (बीच में नाम/नंबर/version वाले text) बदलते हैं -
 *    सिर्फ़ पूरे शब्द पर (\"का\" से \"काम\" नहीं टूटता), बड़े हिस्से पहले.
 */
class UiText(entries: Map<String, String>) {

    private val exact = entries
    /** दो अक्षर वाले छोटे शब्द ("का", "है") बीच में नहीं बदलते - सिर्फ़ पूरा text हो तब */
    private val parts = entries.entries.filter { it.key.length >= MIN_PART && it.key.any { c -> dev(c) } }.sortedByDescending { it.key.length }
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 1500
    }

    val size get() = exact.size

    fun tr(s: String): String {
        if (s.isEmpty() || s.none { dev(it) }) return s
        synchronized(cache) { cache[s]?.let { return it } }
        val out = translate(s)
        synchronized(cache) { cache[s] = out }
        return out
    }

    private fun translate(s: String): String {
        exact[s]?.let { return it }
        val t = s.trim()
        if (t != s) exact[t]?.let { return s.substring(0, s.indexOf(t)) + it + s.substring(s.indexOf(t) + t.length) }
        // कई लाइनें: हर लाइन अलग
        if (s.contains('\n')) return s.split('\n').joinToString("\n") { tr(it) }
        var r = s
        for ((k, v) in parts) {
            if (r.none { dev(it) }) break
            var from = 0
            while (true) {
                val i = r.indexOf(k, from)
                if (i < 0) break
                val end = i + k.length
                val okL = i == 0 || !dev(r[i - 1]); val okR = end >= r.length || !dev(r[end])
                if (okL && okR) { r = r.substring(0, i) + v + r.substring(end); from = i + v.length } else from = i + 1
            }
        }
        return r
    }

    companion object {
        const val MIN_PART = 3
        fun dev(c: Char) = c in '\u0900'..'\u097F'

        /** \"हिंदी<TAB>English\" लाइनें; # = टिप्पणी */
        fun parse(tsv: String): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            for (line in tsv.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val i = line.indexOf('\t'); if (i <= 0) continue
                val k = line.substring(0, i); val v = line.substring(i + 1)
                if (k.isNotBlank() && v.isNotBlank()) m[k] = v
            }
            return m
        }
    }
}
