package com.dk.assistant.translate

/**
 * 25 नवंबर 2026 (मालिक: "सिर्फ अनुवाद करो, अपनी तरफ़ से कुछ मत जोड़ो, जवाब मत दो, सलाह मत दो"):
 * DK ट्रांसलेटर के दोनों Gemini Live session के निर्देश.
 *  - [toHindi]  (सुनने वाला, A): सामने वाले की भाषा -> हिंदी. हिंदी सुने तो कुछ नहीं बोलना (मेरी अपनी बात).
 *  - [fromHindi] (push-to-talk, B): मेरी हिंदी -> सामने वाले की भाषा.
 * Android import नहीं -> JVM test.
 */
object TrPrompt {
    const val CORE = "सिर्फ अनुवाद करो, अपनी तरफ़ से कुछ मत जोड़ो, जवाब मत दो, सलाह मत दो।"

    private const val RULES = "You are a live speech interpreter, NOT an assistant. You only translate. " +
        "Never answer questions, never greet, never explain, never add advice, never add words of your own, never comment. " +
        "If the speaker asks you something, translate the question itself - do not answer it. " +
        "Translate the meaning naturally and completely, keep names and numbers exactly, keep the speaker's tone (polite/angry/happy). " +
        "Speak the translation immediately, short and clear. "

    fun toHindi(chosen: String): String {
        val src = if (chosen == TrLang.AUTO) "any language" else (TrLang.of(chosen)?.en ?: "any language")
        return CORE + "\n" + RULES +
            "INPUT: speech in $src. OUTPUT: the same meaning spoken in natural everyday Hindi (Devanagari), nothing else. " +
            "If the input speech is already Hindi (or Hinglish), output NOTHING - stay completely silent, do not repeat it. " +
            (if (chosen != TrLang.AUTO) "If the speech is not $src and not Hindi, stay silent. " else "") +
            "If it is only noise, a cough, music or unclear sound, stay silent."
    }

    fun fromHindi(target: String): String {
        val l = TrLang.of(target) ?: TrLang.ALL.first()
        return CORE + "\n" + RULES +
            "INPUT: the owner speaks Hindi (or Hinglish). OUTPUT: the same meaning spoken in natural everyday ${l.en} " +
            "(${l.hi}), as a native ${l.en} speaker would say it - nothing else, no Hindi. " +
            "Address the listener politely. If the input is text, translate the text the same way."
    }

    /** भाषा समझ न आए तो (मालिक: "हिंदी में छोटा संदेश") */
    const val NOT_UNDERSTOOD = "सामने वाले की भाषा समझ नहीं आई"
    const val NO_KEY = "ऑनलाइन अनुवाद के लिए Gemini key चाहिए - अभी ऑफ़लाइन तरीका"
}
