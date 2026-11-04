package com.dk.assistant.agent.social

/**
 * v3.59: "WhatsApp पर राहुल को मैसेज भेजो" phone_task (स्क्रीन पर अंदाज़े से चलने वाला एजेंट) में न जाए।
 * जड़: मॉडल यह काम phone_task को देता था, वह कई बार भेजे बिना रुक जाता था और मॉडल "भेज दिया" बोल देता था।
 * अब ऐसा अनुरोध whatsapp_task (wa.me से सीधे चैट + भेजने के बाद स्क्रीन पर जाँच) को जाता है - तेज़ और पक्का।
 */
object WaRoute {
    private val WA = listOf("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "वॉट्सएप", "व्हाट्सअप", "whats app", "watsapp", "whatsap")
    private val SEND = listOf("मैसेज", "मेसेज", "संदेश", "message", "msg", "भेज", "भेजो", "send", "bhej", "लिख", "बोल दो", "कह दो", "बता दो", "text")
    private val NOT = listOf("पढ़", "padh", "read", "unread", "कौन सा मैसेज", "क्या मैसेज", "कोई मैसेज", "म्यूट", "mute", "खोलो सिर्फ़", "call", "कॉल", "वीडियो कॉल")

    fun isWhatsAppSend(request: String): Boolean {
        val t = request.lowercase()
        if (WA.none { t.contains(it) }) return false
        if (NOT.any { t.contains(it) } && !t.contains("भेज") && !t.contains("send")) return false
        return SEND.any { t.contains(it) }
    }

    /** नवंबर 2026: "राहुल को WhatsApp पर आधार वाली PDF / फोटो / फ़ाइल भेजो" = फ़ाइल भेजना (send_file), संदेश नहीं */
    private val FILE = listOf("फाइल", "फ़ाइल", "file", "pdf", "पीडीएफ", "फोटो", "फ़ोटो", "photo", "image", "इमेज", "तस्वीर", "वीडियो", "video",
        "डॉक्यूमेंट", "document", "वेबसाइट का लिंक", "गेम", "game", "स्क्रीनशॉट", "screenshot")

    fun isFileSend(request: String): Boolean {
        val t = request.lowercase()
        return (t.contains("भेज") || t.contains("send") || t.contains("bhej") || t.contains("शेयर") || t.contains("share")) && FILE.any { t.contains(it) }
    }

    /** phone_task का जवाब: मॉडल whatsapp_task बुलाए */
    fun redirect(request: String): String = if (isFileSend(request))
        "not_done: WhatsApp files must use the whatsapp_task tool, not phone_task. Nothing was sent. Call whatsapp_task now with " +
            "action=send_file, contact=<the person's name or number from the request> and file=<which file, in the user's words> " +
            "(request was: \"" + request.take(200).replace("\"", "'") + "\"). If the contact or the file is missing, ask the user first."
    else
        "not_done: WhatsApp messages must use the whatsapp_task tool, not phone_task. Nothing was sent. Call whatsapp_task now with " +
            "action=send_message, contact=<the person's name or number from the request> and message=<the exact text to send> " +
            "(request was: \"" + request.take(200).replace("\"", "'") + "\"). If the contact or the text is missing, ask the user first."
}
