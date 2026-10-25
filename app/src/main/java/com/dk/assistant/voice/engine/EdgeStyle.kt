package com.dk.assistant.voice.engine

/**
 * v3.69: Edge TTS भावना का निर्देश नहीं समझता - इसलिए "भावना / स्टाइल" बॉक्स के शब्दों से सिर्फ़ गति (%) और पिच (Hz) बदलती है.
 * "धीमी", "कोमल" = गति कम, पिच नीचा; "तेज़", "जोश" = गति ज़्यादा. नतीजा स्लाइडर के मान में जुड़ता है.
 * Android import नहीं -> JVM test.
 */
object EdgeStyle {
    /** (शब्द, गति %, पिच Hz) - हर मिला शब्द एक बार गिना जाता है */
    private val RULES: List<Triple<List<String>, Int, Int>> = listOf(
        Triple(listOf("धीमी", "धीमे", "धीमा", "धीरे", "आराम", "ठहर", "slow", "calm"), -15, 0),
        Triple(listOf("कोमल", "नरम", "मुलायम", "प्यार", "soft", "gentle", "sweet"), -5, -4),
        Triple(listOf("भावुक", "उदास", "दुखी", "गंभीर", "emotional", "sad", "serious"), -8, -3),
        Triple(listOf("शांत", "फुसफुसा", "whisper"), -6, -2),
        Triple(listOf("गहरी", "भारी", "deep"), 0, -8),
        Triple(listOf("तेज़", "तेज", "जल्दी", "fast", "quick"), 15, 0),
        Triple(listOf("जोश", "उत्साह", "excited", "energetic"), 12, 4),
        Triple(listOf("खुश", "चहक", "मस्ती", "happy", "cheerful"), 6, 3),
        Triple(listOf("ऊँची", "ऊंची", "पतली", "high"), 0, 6),
    )

    /** बॉक्स के शब्दों से (गति %, पिच Hz) का बदलाव */
    fun adjust(style: String): Pair<Int, Int> {
        val s = style.lowercase()
        var r = 0; var p = 0
        for ((words, dr, dp) in RULES) if (words.any { s.contains(it) }) { r += dr; p += dp }
        return r.coerceIn(-50, 50) to p.coerceIn(-30, 30)
    }

    /** स्लाइडर + बॉक्स = आख़िरी गति और पिच (Edge की सीमा में) */
    fun apply(rate: Int, pitch: Int, style: String): Pair<Int, Int> {
        val (dr, dp) = adjust(style)
        return (rate + dr).coerceIn(-50, 50) to (pitch + dp).coerceIn(-30, 30)
    }

    fun describe(style: String): String {
        val (r, p) = adjust(style)
        if (r == 0 && p == 0) return "बॉक्स से कोई बदलाव नहीं"
        fun sg(v: Int) = (if (v > 0) "+" else "") + v
        return "बॉक्स से: गति ${sg(r)}%, पिच ${sg(p)} Hz"
    }
}
