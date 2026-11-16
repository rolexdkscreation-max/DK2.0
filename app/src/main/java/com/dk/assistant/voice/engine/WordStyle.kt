package com.dk.assistant.voice.engine

/**
 * v3.69 (नवंबर 2026 से नाम WordStyle, पहले Edge TTS के लिए था - फिर Kokoro, 16 नवंबर 2026 से फ़ोन की आवाज़ PhoneVoice के लिए): जो इंजन भावना का निर्देश
 * नहीं समझता, उसके "भावना / स्टाइल" बॉक्स के शब्दों से सिर्फ़ गति (%) और पिच (Hz) बदलती है.
 * "धीमी", "कोमल" = गति कम, पिच नीचा; "तेज़", "जोश" = गति ज़्यादा. नतीजा स्लाइडर के मान में जुड़ता है.
 * Android import नहीं -> JVM test.
 */
object WordStyle {
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

    /** स्लाइडर + बॉक्स = आख़िरी गति और पिच (-50..50 %, -30..30 Hz) */
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

    /**
     * 16 नवंबर 2026 (मालिक: "भावुक / स्टाइल वाला कोड जुड़ना चाहिए"): Kokoro हटने के बाद भावना का निर्देश न समझने वाला
     * इंजन = फ़ोन की अपनी आवाज़ ([com.dk.assistant.PhoneVoice]). स्लाइडर (गति/पिच %, 100 = सामान्य) × बॉक्स के शब्द =
     * Android TextToSpeech की setSpeechRate / setPitch (1.0 = सामान्य, 0.5..2.0).
     */
    fun phone(speedPct: Int, pitchPct: Int, style: String): Pair<Float, Float> {
        val (dr, dp) = adjust(style)
        val rate = (speedPct / 100f) * (1f + dr / 100f)
        val pitch = (pitchPct / 100f) * (1f + dp / 100f)
        return rate.coerceIn(0.5f, 2f) to pitch.coerceIn(0.5f, 2f)
    }
}
