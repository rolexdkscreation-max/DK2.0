package com.dk.assistant.agent.core

/**
 * 14 नवंबर 2026 (मालिक): DK लाइव के बड़े काम के लिए मालिक की साफ़ "हाँ" जाँचना।
 * AI के कहने पर काम शुरू नहीं होगा - ऐप खुद मालिक की अपनी आवाज़ / लिखे शब्द जाँचेगा।
 */
object ConfirmGate {
    val YES_WORDS = setOf(
        "हाँ", "हां", "हा", "जी", "हाँजी", "ठीक", "कर", "करो", "दो", "चलो", "बिल्कुल", "बिलकुल", "शुरू", "आगे", "बढ़ो", "भाई",
        "yes", "yeah", "yep", "ok", "okay", "haan", "han", "ha", "ji", "sure", "go", "ahead", "do", "it", "start", "theek", "hai", "kar", "karo", "chalo"
    )

    val NO_WORDS = setOf(
        "नहीं", "नही", "ना", "मत", "रद्द", "रुको", "रहने", "छोड़ो", "बंद",
        "no", "nahi", "nahin", "na", "cancel", "stop", "dont", "don't", "wait"
    )

    /**
     * जाँचे कि क्या [userText] मालिक की स्पष्ट "हाँ" है जो [askedAt] के बाद बोली/लिखी गई।
     */
    fun accept(askedAt: Long, userAt: Long, userText: String): Boolean {
        if (userAt < askedAt) return false
        val words = userText.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        
        val hasNo = words.any { it in NO_WORDS }
        if (hasNo) return false

        val hasYes = words.any { it in YES_WORDS }
        return hasYes
    }
}