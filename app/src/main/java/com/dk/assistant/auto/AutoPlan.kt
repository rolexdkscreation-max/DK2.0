package com.dk.assistant.auto

/**
 * ऑटोनोमस मोड: बात किस तरह का काम है (कोडिंग / WhatsApp / Instagram / YouTube / Facebook / अपना जोड़ा काम).
 * शुद्ध Kotlin (Android नहीं) -> JVM test.
 * null = ऑटोनोमस का काम नहीं -> DK पहले जैसा (मौजूदा रास्ता) ही चलेगा.
 */
object AutoPlan {
    enum class Kind(val id: String, val title: String) {
        CODING("coding", "कोडिंग (बग ठीक करना)"),
        WHATSAPP("whatsapp", "WhatsApp मैसेज"),
        INSTAGRAM("instagram", "Instagram"),
        YOUTUBE("youtube", "YouTube पोस्ट"),
        FACEBOOK("facebook", "Facebook पोस्ट"),
        CUSTOM("custom", "अपना काम")
    }

    data class CustomTask(val id: String, val name: String, val instructions: String, val on: Boolean = true)
    data class Plan(val kind: Kind, val task: String, val custom: CustomTask? = null)

    private val CODE_WORDS = listOf(
        "बग", "bug", "कोड में", "कोड को", "कोड ठीक", "का कोड", "सोर्स कोड", "source code", "the code", "my code", "code में", "कोडिंग", "coding", "गिटहब", "github", "रिपॉज़िटरी", "रिपोजिटरी", "रिपो", "repo", "repository",
        "pull request", "पुल रिक्वेस्ट", "कंपाइल", "compile", "बिल्ड एरर", "build error", "crash", "क्रैश", "एरर ठीक", "error fix"
    )
    private val CODE_VERBS = listOf(
        "ठीक", "fix", "सुधार", "बदल", "change", "जोड़", "जोड", "add", "बना", "हटा", "remove", "update", "अपडेट", "लिख", "पढ़", "देख", "check", "जाँच", "जांच"
    )
    private val WA = listOf("व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "whatsapp", "वाट्सएप", "व्हाट्सअप")
    private val IG = listOf("इंस्टाग्राम", "इंस्टा", "instagram", "insta")
    private val YT = listOf("यूट्यूब", "youtube", "यू ट्यूब")
    private val FB = listOf("फेसबुक", "facebook", "फ़ेसबुक")
    /** करने वाली बात (सिर्फ़ "WhatsApp क्या है" जैसा सवाल ऑटोनोमस नहीं) */
    private val ACT = listOf(
        "भेज", "send", "मैसेज", "message", "msg", "पोस्ट", "post", "अपलोड", "upload", "कमेंट", "comment", "लाइक", "like",
        "फॉलो", "follow", "शेयर", "share", "रिप्लाई", "reply", "जवाब दे", "डाल", "स्टोरी", "story", "रील", "reel", "वीडियो डाल",
        "डिलीट", "delete", "पढ़", "read", "चेक कर", "देख कर"
    )

    fun norm(s: String): String = s.lowercase().replace(Regex("[\\s,.!?।:;\"'()]+"), " ").trim()

    private fun has(t: String, list: List<String>) = list.any { t.contains(it) }

    /**
     * enabled = जिन तरह के काम के switch चालू हैं. custom = Settings में जोड़े काम (नाम बोलने पर).
     * क्रम: अपना जोड़ा काम (नाम मिले) -> कोडिंग -> WhatsApp -> Instagram -> YouTube -> Facebook.
     */
    fun classify(goal: String, enabled: Set<Kind>, custom: List<CustomTask> = emptyList()): Plan? {
        val t = norm(goal)
        if (t.length < 3) return null
        custom.filter { it.on && it.name.isNotBlank() }.firstOrNull { t.contains(norm(it.name)) }?.let {
            return Plan(Kind.CUSTOM, goal.trim(), it)
        }
        if (Kind.CODING in enabled && has(t, CODE_WORDS) && has(t, CODE_VERBS)) return Plan(Kind.CODING, goal.trim())
        if (!has(t, ACT)) return null
        return when {
            Kind.WHATSAPP in enabled && has(t, WA) -> Plan(Kind.WHATSAPP, goal.trim())
            Kind.INSTAGRAM in enabled && has(t, IG) -> Plan(Kind.INSTAGRAM, goal.trim())
            Kind.YOUTUBE in enabled && has(t, YT) -> Plan(Kind.YOUTUBE, goal.trim())
            Kind.FACEBOOK in enabled && has(t, FB) -> Plan(Kind.FACEBOOK, goal.trim())
            else -> null
        }
    }

    /** फ़ोन वाले काम के लिए AI को नियम (स्क्रीन देखकर एक-एक कदम) */
    fun phoneNote(p: Plan): String = buildString {
        append("AUTONOMOUS MODE (${p.kind.id}). Work step by step on the phone screen: open the app, read the screen, tap, type, scroll.")
        append(" NEVER type into or tap on password, OTP, PIN, UPI, card or bank screens - stop and tell the user instead.")
        append(" Do not guess a contact: if more than one person matches, use ask.")
        append(" Never pay, buy, order, or change an address.")
        append(" If the same step fails twice, stop and tell the user honestly what blocked you.")
        p.custom?.let { append("\nUSER'S OWN TASK \"${it.name}\" - INSTRUCTIONS: ${it.instructions.take(1500)}") }
    }
}
