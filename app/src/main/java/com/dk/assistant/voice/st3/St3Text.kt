package com.dk.assistant.voice.st3

import java.text.Normalizer

/**
 * 19 नवंबर 2026: Supertonic 3 के लिए text तैयार करना - आधिकारिक कोड (supertone-inc/supertonic, `java/Helper.java`
 * और Python SDK `supertonic/core.py`) जैसा ही, ताकि मॉडल को वही मिले जिस पर वह सीखा है:
 * NFKD -> इमोजी हटाओ -> चिह्न बदलो -> विराम-चिह्न के पास की जगह ठीक -> अंत में विराम-चिह्न न हो तो "." -> `<hi>…</hi>`.
 *
 * `<laugh>` / `<breath>` / `<sigh>` जैसे टैग वैसे ही रहते हैं (मॉडल इन्हें पहचानता है) - [chunk] इन्हें बीच से नहीं काटता.
 * Android import नहीं -> JVM test ([St3TextTest]).
 */
object St3Text {
    val LANGS = setOf("en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it",
        "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na")

    private val REPLACE = listOf(
        "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "\u201C" to "\"", "\u201D" to "\"", "\u2018" to "'", "\u2019" to "'",
        "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ",
    )
    private val EXPR = listOf("@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ")
    private val SPECIAL = Regex("[♥☆♡©\\\\]")
    private val ENDS = Regex(".*[.!?;:,'\"\u201C\u201D\u2018\u2019)\\]}…。」』】〉》›»]$", RegexOption.DOT_MATCHES_ALL)
    private val SPACES = Regex("\\s+")

    private fun emoji(cp: Int): Boolean = cp in 0x1F600..0x1F64F || cp in 0x1F300..0x1F5FF || cp in 0x1F680..0x1F6FF ||
        cp in 0x1F700..0x1F77F || cp in 0x1F780..0x1F7FF || cp in 0x1F800..0x1F8FF || cp in 0x1F900..0x1F9FF ||
        cp in 0x1FA00..0x1FA6F || cp in 0x1FA70..0x1FAFF || cp in 0x2600..0x26FF || cp in 0x2700..0x27BF || cp in 0x1F1E6..0x1F1FF

    /** आधिकारिक preprocessText जैसा */
    fun preprocess(text0: String, lang: String = St3Config.LANG): String {
        require(lang in LANGS) { "भाषा $lang Supertonic 3 में नहीं" }
        var t = Normalizer.normalize(text0, Normalizer.Form.NFKD)
        t = buildString { t.codePoints().forEach { if (!emoji(it)) appendCodePoint(it) } }
        for ((a, b) in REPLACE) t = t.replace(a, b)
        t = t.replace(SPECIAL, "")
        for ((a, b) in EXPR) t = t.replace(a, b)
        t = t.replace(" ,", ",").replace(" .", ".").replace(" !", "!").replace(" ?", "?")
            .replace(" ;", ";").replace(" :", ":").replace(" '", "'")
        while (t.contains("\"\"")) t = t.replace("\"\"", "\"")
        while (t.contains("''")) t = t.replace("''", "'")
        t = t.replace(SPACES, " ").trim()
        if (!ENDS.matches(t)) t += "."
        return "<$lang>$t</$lang>"
    }

    /**
     * तैयार text -> मॉडल के अंक. जो अक्षर मॉडल नहीं जानता (indexer में -1 या सूची से बाहर) वह छोड़ दिया जाता है
     * (आधिकारिक कोड में वहाँ -1 चला जाता और मॉडल गड़बड़ाता).
     */
    fun ids(processed: String, indexer: LongArray): LongArray {
        val out = ArrayList<Long>(processed.length)
        processed.codePoints().forEach { cp -> if (cp in indexer.indices) indexer[cp].takeIf { it >= 0 }?.let { out.add(it) } }
        return out.toLongArray()
    }

    /** text में कोई ऐसा अक्षर जो मॉडल नहीं जानता? (जाँच/लॉग के लिए) */
    fun unknown(text: String, indexer: LongArray): Set<String> = buildSet {
        preprocess(text).codePoints().forEach { cp -> if (cp !in indexer.indices || indexer[cp] < 0) add(String(Character.toChars(cp))) }
    }

    private val SENT_END = Regex("(?<=[.!?।])\\s+")
    private val TAG = Regex("<[a-z]+>")

    /**
     * लंबा text -> [maxLen] तक के टुकड़े (आधिकारिक chunkText जैसा: पैराग्राफ़ -> वाक्य -> अल्पविराम -> शब्द).
     * हिंदी का "।" भी वाक्य का अंत. `<laugh>` जैसे टैग शब्द माने जाते हैं, इसलिए बीच से कभी नहीं कटते.
     */
    fun chunk(text: String, maxLen: Int = 300): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (para0 in t.split(Regex("\\n\\s*\\n"))) {
            val para = para0.trim().replace(SPACES, " ")
            if (para.isEmpty()) continue
            if (para.length <= maxLen) { out.add(para); continue }
            val cur = StringBuilder()
            fun flush() {
                if (cur.isNotBlank()) out.add(cur.toString().trim())
                cur.setLength(0)
            }
            fun addPiece(p: String, sep: String) {
                if (cur.isNotEmpty() && cur.length + sep.length + p.length > maxLen) flush()
                if (cur.isNotEmpty()) cur.append(sep)
                cur.append(p)
            }
            for (s0 in para.split(SENT_END)) {
                val s = s0.trim(); if (s.isEmpty()) continue
                if (s.length <= maxLen) { addPiece(s, " "); continue }
                flush()
                for (part0 in s.split(",")) {
                    val part = part0.trim(); if (part.isEmpty()) continue
                    if (part.length <= maxLen) { addPiece(part, ", "); continue }
                    flush()
                    for (w in part.split(' ')) if (w.isNotEmpty()) addPiece(w, " ")
                    flush()
                }
                flush()
            }
            flush()
        }
        return out
    }

    /** टैग पूरे हैं? (`<laugh` जैसा आधा टैग टुकड़े के अंत में नहीं होना चाहिए) - test के लिए */
    fun tagsWhole(chunk: String): Boolean = chunk.count { it == '<' } == chunk.count { it == '>' } && TAG.findAll(chunk).count() == chunk.count { it == '<' }
}
