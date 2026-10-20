package com.dk.assistant

import android.speech.tts.Voice

/** Mahila / purush awaazon ki jaankari. */
object VoiceCatalog {
    /** Gemini ki natural awaazein (Google AI). */
    val GEMINI_FEMALE = listOf("Kore", "Aoede", "Leda", "Zephyr", "Despina", "Callirrhoe", "Autonoe", "Sulafat", "Achernar", "Vindemiatrix")
    val GEMINI_MALE = listOf("Puck", "Charon", "Fenrir", "Orus", "Iapetus", "Alnilam", "Achird", "Sadaltager", "Algenib", "Umbriel")

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
