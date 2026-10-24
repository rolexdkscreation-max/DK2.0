package com.dk.assistant.onboard

/**
 * v3.58 (Part 11): पहली बार खोलने का परिचय (4 पेज) कब दिखे - सिर्फ़ हिसाब (JVM test)।
 * पुराने उपयोगकर्ता (अपडेट करके आए) को कभी नहीं दिखता - उनका ऐप पहले जैसा।
 */
object OnboardingRules {
    const val PAGES = 4

    /**
     * @param onboarded पहले परिचय पूरा/छोड़ दिया
     * @param freshInstall पहली बार install (अपडेट नहीं)
     * @param hasUserName नाम पहले से भरा है (मतलब ऐप पहले इस्तेमाल हुआ)
     */
    fun shouldShow(onboarded: Boolean, freshInstall: Boolean, hasUserName: Boolean): Boolean =
        !onboarded && freshInstall && !hasUserName

    /** अगले पेज का नंबर; आख़िरी के बाद null = परिचय खत्म */
    fun next(page: Int): Int? = if (page + 1 < PAGES) page + 1 else null
}

/** घर की स्क्रीन पर समय के हिसाब से अभिवादन */
object Greeting {
    fun word(hour: Int): String = when (hour) {
        in 4..11 -> "सुप्रभात"
        in 17..21 -> "शुभ संध्या"
        else -> "नमस्ते"
    }

    fun of(hour: Int, user: String): String {
        val w = word(hour)
        return if (user.isBlank()) w else "$w, ${user.trim()}"
    }
}
