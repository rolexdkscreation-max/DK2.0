package com.dk.assistant

/**
 * DK AI ब्रीफ़ Part 03: यूज़र ने किस भाषा में बोला/लिखा - हिंदी (देवनागरी), Hinglish (रोमन में हिंदी) या English.
 * "Auto" भाषा (Lang.AUTO) में DK हर बार उसी भाषा में जवाब देता है जिसमें यूज़र ने बात की.
 * Android import नहीं -> JVM test.
 */
object LanguageDetector {
    enum class Detected { HINDI, HINGLISH, ENGLISH, UNKNOWN }

    /** रोमन लिपि में लिखे आम हिंदी शब्द */
    val HINGLISH_MARKERS: Set<String> = setOf(
        "hai", "hain", "ho", "hoon", "hu", "tha", "thi", "kya", "kyun", "kyon", "kyu", "kaise", "kaisa", "kaisi",
        "nahi", "nahin", "nhi", "haan", "mujhe", "mujhko", "tumhe", "tumko", "tum", "aap", "aapko", "mera", "meri",
        "mere", "tera", "teri", "tere", "yaar", "bhai", "accha", "acha", "achha", "theek", "thik", "matlab", "karna", "karo",
        "kar", "karke", "raha", "rahi", "rahe", "chahiye", "batao", "bata", "suno", "dekho", "abhi", "phir", "fir", "lekin",
        "bahut", "bohot", "thoda", "kuch", "kuchh", "sab", "wala", "wali", "wale", "kab", "kahan", "kaha", "kaun", "kitna",
        "kitne", "aur", "bhi", "toh", "chalo", "kholo", "khol", "banao", "bana", "bolo", "haal", "jaldi",
        "kal", "aaj", "subah", "raat", "ghar", "paani", "khana", "samajh", "pata", "liye", "se", "ka", "ki", "ke", "ko", "mein", 
    )

    private val SPLIT = Regex("[^a-zA-Z']+")

    fun detect(text: String?): Detected {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return Detected.UNKNOWN
        if (t.any { it in '\u0900'..'\u097F' }) return Detected.HINDI
        val words = t.lowercase().split(SPLIT).filter { it.isNotBlank() }
        if (words.isEmpty()) return Detected.UNKNOWN
        val hits = words.count { it.trim('\'') in HINGLISH_MARKERS }
        return if (words.size <= 4) {
            if (hits >= 1) Detected.HINGLISH else Detected.ENGLISH
        } else {
            if (hits >= 2 || hits.toDouble() / words.size >= 0.15) Detected.HINGLISH else Detected.ENGLISH
        }
    }

    /** पहचानी भाषा -> Lang का code (UNKNOWN = null, यानी पिछली भाषा ही रहे) */
    fun toLangCode(d: Detected): String? = when (d) {
        Detected.HINDI -> Lang.HINDI
        Detected.HINGLISH -> Lang.HINGLISH
        Detected.ENGLISH -> Lang.ENGLISH
        Detected.UNKNOWN -> null
    }
}
