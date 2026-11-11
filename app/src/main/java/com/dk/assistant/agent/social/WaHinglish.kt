package com.dk.assistant.agent.social

/**
 * नवंबर 2026 (मालिक): "DK WhatsApp पे किसी से बात करे तो Hinglish में लिखे" - Hindi, पर Roman/English अक्षरों में
 * ("Kal milte hain bhai"), देवनागरी नहीं. Live कभी संदेश देवनागरी में दे दे (या बातचीत से भरा संदेश हिंदी लिपि में हो)
 * तो भेजने से पहले AI से Hinglish में बदलवाओ. यहाँ सिर्फ़ pure नियम (JVM test); AI call GeminiLiveTools में.
 */
object WaHinglish {
    const val SYSTEM = "Rewrite the given WhatsApp message in Hinglish: the same Hindi words written in Roman/English letters, " +
        "keeping English words as they are (e.g. 'कल 5 बजे मिलते हैं, ok?' -> 'Kal 5 baje milte hain, ok?'). Keep exactly the same meaning, " +
        "names, numbers, emojis and tone - do not add, remove or answer anything. Output ONLY the rewritten message."

    /** देवनागरी अक्षर हैं = बदलना है */
    fun need(msg: String): Boolean = msg.any { it in '\u0900'..'\u097F' }

    /** AI का जवाब -> भेजने लायक Hinglish; गड़बड़ (खाली / अब भी देवनागरी / बहुत लंबा) = null (मूल संदेश ही जाए) */
    fun clean(raw: String?, original: String): String? {
        var t = raw?.trim().orEmpty()
        if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()) t = t.substring(1, t.length - 1).trim()
        t = t.removePrefix("Hinglish:").trim()
        if (t.isBlank() || need(t)) return null
        if (t.length > original.length * 3 + 20) return null
        return t
    }
}
