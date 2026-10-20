package com.dk.assistant

import android.speech.tts.Voice

/** Mahila / purush awaazon ki jaankari. */
object VoiceCatalog {
    /** Gemini ki natural awaazein (Google AI). */
    /** Gemini ki saari 30 natural awaazein (naam to Hindi vivaran). */
    val GEMINI_INFO = linkedMapOf(
        // mahila
        "Kore" to "मज़बूत, साफ़", "Aoede" to "हल्की, खुली", "Leda" to "जवान, ताज़ी", "Zephyr" to "चमकदार",
        "Callirrhoe" to "आरामदायक", "Autonoe" to "चमकदार, साफ़", "Despina" to "मुलायम, स्मूद", "Erinome" to "साफ़",
        "Laomedeia" to "खुशमिज़ाज", "Achernar" to "बहुत नरम", "Gacrux" to "परिपक्व", "Pulcherrima" to "आत्मविश्वासी",
        "Vindemiatrix" to "कोमल, प्यारी", "Sulafat" to "गर्मजोशी भरी",
        // purush
        "Puck" to "खुशमिज़ाज", "Charon" to "जानकार, समझाने वाली", "Fenrir" to "जोशीली", "Orus" to "मज़बूत",
        "Enceladus" to "धीमी, साँस भरी", "Iapetus" to "साफ़", "Umbriel" to "आरामदायक", "Algieba" to "स्मूद",
        "Algenib" to "भारी, खरखरी", "Rasalgethi" to "जानकारी देने वाली", "Alnilam" to "मज़बूत", "Schedar" to "संतुलित",
        "Achird" to "दोस्ताना", "Zubenelgenubi" to "बेफ़िक्र, कैज़ुअल", "Sadachbia" to "ज़िंदादिल", "Sadaltager" to "ज्ञानी"
    )
    val GEMINI_FEMALE = listOf("Kore", "Aoede", "Leda", "Zephyr", "Callirrhoe", "Autonoe", "Despina", "Erinome",
        "Laomedeia", "Achernar", "Gacrux", "Pulcherrima", "Vindemiatrix", "Sulafat")
    val GEMINI_MALE = listOf("Puck", "Charon", "Fenrir", "Orus", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Algenib", "Rasalgethi", "Alnilam", "Schedar", "Achird", "Zubenelgenubi", "Sadachbia", "Sadaltager")

    val STYLES = listOf("natural" to "नेचुरल (सामान्य)", "friend" to "दोस्त जैसी", "cheerful" to "खुशमिज़ाज",
        "calm" to "शांत, मुलायम", "sweet" to "प्यारी, कोमल", "pro" to "प्रोफेशनल")

    fun geminiFor(gender: String) = if (gender == "male") GEMINI_MALE else GEMINI_FEMALE

    // Google TTS ki Hindi awaazon ke code (hi-in-x-XXX): a,c = mahila, d,e = purush
    private val GOOGLE_FEMALE = listOf("hia", "hic")
    private val GOOGLE_MALE = listOf("hid", "hie")

    fun googleGender(v: Voice): String? {
        val n = v.name.lowercase()
        return when {
            GOOGLE_FEMALE.any { n.contains("-x-$it") } -> "female"
            GOOGLE_MALE.any { n.contains("-x-$it") } -> "male"
            n.contains("female") -> "female"
            n.contains("male") -> "male"
            else -> null
        }
    }

    fun label(v: Voice, index: Int): String {
        val g = when (googleGender(v)) { "female" -> "महिला"; "male" -> "पुरुष"; else -> "आवाज़" }
        val net = if (v.isNetworkConnectionRequired) "ऑनलाइन • ज़्यादा नेचुरल" else "ऑफ़लाइन"
        return "$g ${index + 1}  ($net)"
    }

    /** Gender ke hisaab se sabse achhi Google awaaz. */
    fun pickGoogle(voices: List<Voice>, gender: String): Voice? {
        val sorted = voices.sortedWith(
            compareByDescending<Voice> { googleGender(it) == gender }
                .thenByDescending { it.quality }
                .thenByDescending { it.isNetworkConnectionRequired }
        )
        return sorted.firstOrNull()
    }
}
