package com.dk.assistant

import org.json.JSONObject

/**
 * Web search ke sources (v3.9).
 *
 * Gemini jab sach me Google Search karta hai tabhi jawab me `groundingMetadata.groundingChunks[].web {uri, title, domain}`
 * aata hai (REST: candidates[0].groundingMetadata, Live: serverContent.groundingMetadata).
 * Yeh nahi = model ne apne gyaan se jawab diya -> koi source nahi dikhana.
 */
object Grounding {
    const val MAX = 5

    /** (title, url) - sirf asli web chunks; khali/duplicate hatao */
    fun sources(meta: JSONObject?): List<Pair<String, String>> {
        val chunks = meta?.optJSONArray("groundingChunks") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until chunks.length()) {
            val w = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = w.optString("uri").trim()
            if (!uri.startsWith("http")) continue
            val title = w.optString("title").trim().ifBlank { w.optString("domain").trim() }.ifBlank { host(uri) }
            if (title.isBlank()) continue
            if (out.any { it.second == uri || it.first.equals(title, ignoreCase = true) }) continue
            out.add(title to uri)
            if (out.size >= MAX) break
        }
        return out
    }

    /** Search sach me hua? (sources mile) */
    fun grounded(meta: JSONObject?) = sources(meta).isNotEmpty()

    /** Do list jodo (Live me metadata tukdon me aata hai), duplicate hatao */
    fun merge(a: List<Pair<String, String>>, b: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = a.toMutableList()
        for (s in b) if (out.none { it.second == s.second || it.first.equals(s.first, ignoreCase = true) }) out.add(s)
        return out.take(MAX)
    }

    fun host(url: String): String =
        Regex("^https?://([^/?#:]+)").find(url)?.groupValues?.get(1)?.removePrefix("www.").orEmpty()

    /** Chip par chhota naam: "ndtv.com", lamba title kaata hua */
    fun label(title: String, max: Int = 32): String {
        val t = title.trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}

/**
 * Search kab karna hai - "on-demand", "always-on" nahi (v3.9).
 * Yeh sirf pehli tez jaanch hai (keyword): saaf taaza-jaankari wale sawaal turant search par.
 * Baaki sab model khud tay karta hai (system prompt ke niyam + [SEARCH] / googleSearch tool).
 */
object SearchPolicy {
    /** Badalne wali / taaza jaankari ke shabd */
    private val FRESH = listOf(
        "आज का", "आज की", "आज के", "अभी का", "अभी की", "ताज़ा", "ताजा", "लेटेस्ट", "latest", "न्यूज़", "न्यूज", "news",
        "खबर", "ख़बर", "समाचार", "मौसम", "weather", "तापमान", "बारिश होगी", "भाव", "price", "कीमत", "दाम", "स्कोर", "score",
        "किसने जीता", "कौन जीता", "शेयर प्राइस", "stock", "सोने का", "चांदी", "पेट्रोल", "डीज़ल", "डीजल",
        "रिजल्ट", "चुनाव", "election", "कब रिलीज", "ट्रेन का स्टेटस", "pnr", "डॉलर", "dollar", "सेंसेक्स", "निफ्टी",
        "मैच का", "मैच में", "लाइव मैच", "live score", "हाल ही में", "हाल में", "recent", "इस हफ्ते", "इस साल", "this week", "today"
    )
    /** Yeh sawaal gyaan se hi (coding, ganit, samjhana) - inme "price/score" jaisa shabd ho tab bhi search nahi */
    private val TIMELESS = listOf(
        "python", "पाइथन", "java", "जावा", "kotlin", "javascript", "c++", "code", "कोड", "loop", "लूप", "function", "फंक्शन",
        "program", "प्रोग्राम", "algorithm", "एल्गोरिदम", "sql", "html", "css", "variable", "वेरिएबल", "array", "class ",
        "formula", "फॉर्मूला", "सूत्र", "गणित", "हिसाब", "equation", "समीकरण", "मतलब क्या", "अर्थ क्या", "meaning of", "definition",
        "परिभाषा", "समझाओ", "explain", "कैसे लिखें", "कैसे लिखते", "कहानी", "story", "कविता", "poem", "translate", "अनुवाद"
    )

    fun needsSearch(text: String): Boolean {
        val t = text.lowercase()
        if (TIMELESS.any { t.contains(it) } && !t.contains("news") && !t.contains("खबर") && !t.contains("न्यूज")) return false
        if (Regex("^[\\d\\s+\\-*/x×÷().=?]+(कितना|कितने|kitna)?").matches(t.replace("होता है", "").replace("होते हैं", "").trim())) return false
        return FRESH.any { t.contains(it) }
    }

    /** System prompt ke liye niyam (chat, phone-task JSON aur Live - teeno me ek jaisa) */
    fun rules(searchName: String): String =
        "WEB SEARCH POLICY ($searchName is optional - most questions need NO search):\n" +
        "- Answer directly from your own knowledge for: general knowledge, definitions, explanations, how/why, advice, " +
        "coding/programming, maths, stories, translations, grammar, history and science basics, and anything you are confident about.\n" +
        "- Use $searchName ONLY when the answer depends on fresh or changing facts: recent news or events, live data " +
        "(weather, stock/crypto/gold/fuel prices, live scores, match results), today's schedules/status (trains, flights, results, releases), " +
        "or facts that change over time where your knowledge may be outdated (current office holders, latest product versions/prices, new rules or laws).\n" +
        "- The current date and time are given below: answer date/time questions from them, never search for them.\n" +
        "- If you are not sure your knowledge is still current for such a changing fact, then use $searchName; otherwise do not."
}
