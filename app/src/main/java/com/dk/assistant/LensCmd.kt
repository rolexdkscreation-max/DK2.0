package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक): "DK स्कैन खोलो" = DK का अपना स्कैन (DK लेंस - कैमरा, जिसमें कुछ भी दिखाकर पूछ सकते हैं).
 * पहले "स्कैन" शब्द DK लेंस से जुड़ा नहीं था -> बात ऐप खोलने / धीमे AI पर चली जाती थी और स्कैन नहीं खुलता था.
 * कैमरा: बिना बोले = पीछे वाला (बैक). "सामने / फ्रंट / सेल्फी करके खोलो" = आगे वाला. "बैक करके खोलो" = पीछे वाला.
 * pure (Android import नहीं) -> JVM test.
 */
object LensCmd {

    data class Cmd(val on: Boolean, val front: Boolean)

    const val TYPE = "dk_lens"

    private val LENS = listOf("स्कैन", "स्कैनर", "स्केन", "scan", "scanner", "लेंस", "लैंस", "लेन्स", "lens", "डीके कैमरा", "dk कैमरा", "dk camera", "डीके camera")
    /** ये दूसरे ऐप के स्कैन हैं (QR / पेमेंट / गूगल लेंस / डॉक्यूमेंट ऐप) - DK स्कैन नहीं */
    private val OTHER = listOf("qr", "क्यूआर", "क्यू आर", "बारकोड", "barcode", "पेमेंट", "payment", "gpay", "जीपे", "google pay", "गूगल पे", "phonepe", "फोनपे", "फ़ोनपे",
        "paytm", "पेटीएम", "google lens", "गूगल लेंस", "गूगल", "google", "whatsapp", "व्हाट्सएप", "camscanner", "कैमस्कैनर", "डॉक्यूमेंट", "document", "pdf", "पीडीएफ",
        "वायरस", "virus", "फिंगर", "finger", "स्कैन करके भेज", "फ़ाइल", "file",
        // चेहरे वाला Boss स्कैन / स्क्रीन शेयर अलग काम हैं
        "फेस", "फ़ेस", "face", "स्क्रीन", "screen", "बॉस", "boss")
    private val OPEN = listOf("खोल", "चालू", "ऑन", "शुरू", "स्टार्ट", "open", "start", " on", "kholo", "khol", "chalu", "करो", "कर दो", "कर दीजिए", "करना", "करके", "दिखा", "लगा")
    private val OFF = listOf("बंद", "off", "close", "हटा", "band", "बन्द", "ऑफ")
    private val FRONT = listOf("सामने", "samne", "saamne", "samane", "फ्रंट", "फ़्रंट", "फ्रन्ट", "front", "आगे", "सेल्फी", "सेल्फ़ी", "selfie",
        "मुझे देख", "मेरा चेहरा", "मेरी तरफ")
    private val BACK = listOf("बैक", "back", "पीछे", "rear", "रियर")

    private val MORE = listOf(" और ", " and ", " फिर ", " then ", "क्या", "बताओ", "बताना", "पढ़", "पढ", "kya", "batao", "what", "?")

    private fun has(t: String, w: List<String>) = w.any { t.contains(it) }

    /** "DK स्कैन खोलो" / "स्कैन सामने करके खोलो" / "डीके लेंस बैक करके खोलो" / "स्कैन बंद करो" -> Cmd; बाकी = null */
    fun parse(raw: String): Cmd? {
        val t = " " + raw.lowercase().replace(Regex("[,.!?।]+"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (t.isBlank() || t.length > 90) return null
        if (!has(t, LENS) || has(t, OTHER)) return null
        // दो काम ("स्कैन खोलो और YouTube चलाओ") / सवाल ("स्कैन करके बताओ ये क्या है") = पुराना रास्ता (AI के पास camera tool है)
        if (has(t, MORE)) return null
        // "स्कैन करके बताओ ये क्या है" जैसी लंबी बात AI ही समझे - यहाँ सिर्फ़ खोलना / बंद करना
        val off = has(t, OFF)
        if (!off && !has(t, OPEN)) return null
        if (off) return Cmd(on = false, front = false)
        // "बैक" पहले: "सामने वाला नहीं, बैक करके खोलो"
        val front = !has(t, BACK) && has(t, FRONT)
        return Cmd(on = true, front = front)
    }

    fun facing(c: Cmd) = if (c.front) "front" else "back"

    /** बोलने वाली छोटी लाइन */
    fun say(c: Cmd) = when {
        !c.on -> "DK स्कैन बंद कर दिया"
        c.front -> "DK स्कैन सामने वाले कैमरे से खोल रहा हूँ"
        else -> "DK स्कैन पीछे वाले कैमरे से खोल रहा हूँ"
    }
}
