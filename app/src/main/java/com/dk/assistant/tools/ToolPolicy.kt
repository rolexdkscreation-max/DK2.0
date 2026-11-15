package com.dk.assistant.tools

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 1): टूल एजेंट के pure नियम - JVM test ([ToolRegistryTest]).
 *  - एक काम में ज़्यादा से ज़्यादा [MAX_STEPS] कदम
 *  - हर कदम के बाद जाँच; न हुआ तो दूसरा तरीका - कुल [MAX_TRIES] बार
 *  - फिर भी न हो = साफ़ हिंदी में "कहाँ अटकी" ([stuckSpeech])
 */
object ToolPolicy {
    const val MAX_STEPS = 15
    const val MAX_TRIES = 3

    enum class Kind { OK, ASK, FAIL }

    /** DK के पुराने executors के नतीजे की पहचान ("ERROR…", "STEP_FAILED:", "ASK:", "SAID:" …) */
    fun classify(r: String?): Kind {
        val t = r?.trim().orEmpty()
        if (t.isEmpty()) return Kind.FAIL
        if (t.startsWith("ASK")) return Kind.ASK
        val u = t.uppercase()
        if (u.startsWith("ERROR") || u.startsWith("STEP_FAILED") || u.startsWith("FAIL") || u.startsWith("NOT FOUND") ||
            u.startsWith("NO ") || t.startsWith("नहीं हो") || t.contains("नहीं भेज पाई") || t.contains("नहीं लग पाई") ||
            t.contains("नहीं हुआ")) return Kind.FAIL
        return Kind.OK
    }

    /** "SAID: सर, हो गया" / "ERROR_PERM: …" -> बोलने लायक हिस्सा */
    fun plain(r: String): String = r.trim().replace(Regex("^(SAID|ASK|ERROR_[A-Z_]+|ERROR|STEP_FAILED)\\s*:?\\s*"), "").trim()

    /** ये काम दोहराए नहीं जाते (दूसरी बार = दूसरी कॉल / दूसरा मैसेज चला जाता) */
    val ONCE = setOf(ToolRegistry.CALL, ToolRegistry.WHATSAPP, ToolRegistry.SMS, ToolRegistry.LOCATION,
        ToolRegistry.SCREENSHOT, ToolRegistry.ALARM, ToolRegistry.TIMER, ToolRegistry.TYPE)

    fun tries(tool: String): Int = if (tool in ONCE) 1 else MAX_TRIES

    /** हर tool का हिंदी नाम - "कहाँ अटकी" बताने के लिए */
    val HINDI: Map<String, String> = mapOf(
        ToolRegistry.OPEN_APP to "ऐप खोलना", ToolRegistry.CALL to "कॉल लगाना", ToolRegistry.WHATSAPP to "WhatsApp मैसेज भेजना",
        ToolRegistry.SMS to "SMS भेजना", ToolRegistry.TAP_TEXT to "बटन दबाना", ToolRegistry.SCROLL to "स्क्रॉल करना",
        ToolRegistry.BACK to "पीछे जाना", ToolRegistry.HOME to "होम पर जाना", ToolRegistry.LOCK to "फ़ोन लॉक करना",
        ToolRegistry.SCREENSHOT to "स्क्रीनशॉट लेना", ToolRegistry.FLASHLIGHT to "टॉर्च", ToolRegistry.WIFI to "Wi-Fi बदलना",
        ToolRegistry.BLUETOOTH to "Bluetooth बदलना", ToolRegistry.AIRPLANE to "एयरप्लेन मोड बदलना", ToolRegistry.VOLUME to "आवाज़ बदलना",
        ToolRegistry.BRIGHTNESS to "ब्राइटनेस बदलना", ToolRegistry.MEDIA to "गाना चलाना/रोकना", ToolRegistry.YOUTUBE to "YouTube पर खोजना",
        ToolRegistry.ALARM to "अलार्म लगाना", ToolRegistry.TIMER to "टाइमर लगाना", ToolRegistry.READ_SCREEN to "स्क्रीन पढ़ना",
        ToolRegistry.TYPE to "टाइप करना", ToolRegistry.NOTIFICATIONS to "नोटिफ़िकेशन पढ़ना", ToolRegistry.LOCATION to "लोकेशन भेजना",
        ToolRegistry.BATTERY to "बैटरी बताना",
    )

    fun hindi(tool: String): String = HINDI[tool] ?: tool

    fun stuckSpeech(tool: String, arg: String, why: String, tries: Int): String {
        val what = hindi(tool) + if (arg.isNotBlank()) " (\"${arg.take(40)}\")" else ""
        val reason = plain(why).take(140).ifBlank { "स्क्रीन पर बदलाव नहीं दिखा" }
        return "मैं $what पर अटक गई — $tries बार कोशिश की। वजह: $reason"
    }

    fun limitSpeech(): String = "$MAX_STEPS कदम पूरे हो गए, पर काम पूरा नहीं हुआ। थोड़ा छोटा करके फिर से बोलिए।"
}
