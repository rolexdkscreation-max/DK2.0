package com.dk.assistant.forge

/**
 * DK AI ब्रीफ़ Part 14: लिखी/बोली बात "वेबसाइट बनाओ" वाली है या नहीं.
 * ज़रूरी: वेबसाइट/वेब पेज/लैंडिंग पेज जैसा विषय + बनाने वाला शब्द (build/create/make/bana/banao/बनाओ...).
 * सवाल ("website kaise banate hain?") और कोडिंग-टीम/ऐप वाले काम नहीं पकड़ता.
 * Android import नहीं -> JVM test.
 */
object ForgeIntent {
    private val SUBJECT = listOf(
        "website", "web site", "webpage", "web page", "landing page", "landing-page", "homepage", "home page",
        "portfolio site", "वेबसाइट", "वेब साइट", "वेबपेज", "वेब पेज", "लैंडिंग पेज", "साइट",
    )
    private val VERB_WORDS = setOf(
        "build", "create", "make", "design", "generate", "develop", "bana", "banao", "banado", "banaa", "banaiye",
        "banaye", "banayen", "banaen", "banana", "banake", "bnao", "bna", "taiyar", "tayyar",
    )
    private val VERB_HINDI = listOf("बना", "बनाओ", "बनाइए", "बनाएं", "बनाएँ", "बनाना", "बनाकर", "तैयार कर", "डिज़ाइन कर", "डिजाइन कर")
    private val QUESTION_START = setOf(
        "how", "what", "why", "which", "who", "when", "where", "can", "could", "should", "does",
        "kya", "kaise", "kyun", "kyon", "kaun", "kab", "kahan", "kitna", "kitne",
    )
    private val QUESTION_HINDI = listOf("कैसे", "क्यों", "कौन", "कितना", "कितने", "कहाँ", "क्या तुम", "क्या आप", "क्या ")
    private val QUESTION_ANY = listOf("kaise bana", "kaise banate", "kaise banaye", "कैसे बना", "how to", "how do", "how can")
    /** ये काम कोडिंग टीम/ऐप/दूसरी जगह के हैं - Forge नहीं */
    private val NOT_FORGE = listOf("coding", "कोडिंग", "github", "repo", "pull request", "कोडिंग एजेंट", "apk", "screenshot", "स्क्रीनशॉट")

    /** मेल खाए तो वेबसाइट का विवरण (साफ़ किया हुआ), नहीं तो null */
    fun extract(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.length < 6) return null
        val low = " " + raw.lowercase().replace(Regex("\\s+"), " ") + " "
        if (SUBJECT.none { low.contains(it) }) return null
        val words = low.split(Regex("[^\\p{L}\\p{M}']+")).filter { it.isNotBlank() }
        val verb = words.any { it in VERB_WORDS } || VERB_HINDI.any { low.contains(it) }
        if (!verb) return null
        if (NOT_FORGE.any { low.contains(it) }) return null
        if (isQuestion(raw, low, words)) return null
        return raw.trimEnd('.', '।', '!', ' ').ifBlank { null }
    }

    fun matches(text: String?): Boolean = extract(text) != null

    private fun isQuestion(raw: String, low: String, words: List<String>): Boolean {
        if (raw.trimEnd().endsWith("?")) return true
        if (QUESTION_ANY.any { low.contains(it) }) return true
        // "DK, ..." जैसे पुकारने वाले शब्द छोड़कर पहला शब्द
        val first = words.firstOrNull { it !in setOf("dk", "डीके", "hey", "ok", "okay", "please", "plz") } ?: return false
        if (first in QUESTION_START) return true
        val t = low.trim().removePrefix("dk").removePrefix("डीके").trim(' ', ',')
        return QUESTION_HINDI.any { t.startsWith(it) }
    }
}
