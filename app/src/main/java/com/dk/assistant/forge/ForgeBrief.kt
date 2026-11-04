package com.dk.assistant.forge

import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "वेबसाइट बनाने से पहले कौन सी, कैसी वेबसाइट चाहिए - पूरा डिटेल लेगी, उसी हिसाब से बनाएगी").
 * बनाने से पहले छोटा ब्रीफ़: किस काम की / नाम / सेक्शन / रंग-स्टाइल / संपर्क. जो नहीं बताया वो पूछो;
 * मालिक "तुम खुद तय करो" बोले तो DK खुद तय करे. फिर सब मिलाकर एक साफ़ प्रोफेशनल विवरण Forge को.
 * Android import नहीं (org.json JVM test में भी चलता है).
 */
object ForgeBrief {
    data class Brief(val prompt: String, val type: String, val name: String, val sections: String, val style: String, val contact: String)

    /** पूछने का क्रम + सवाल */
    val FIELDS = linkedMapOf(
        "site_type" to "यह वेबसाइट किस काम / बिज़नेस की है (जैसे कैफ़े, दुकान, पोर्टफ़ोलियो, कोचिंग)?",
        "business_name" to "वेबसाइट पर नाम क्या लिखना है?",
        "sections" to "कौन-कौन से हिस्से चाहिए (जैसे सेवाएँ, मेन्यू, गैलरी, रेट, रिव्यू, संपर्क)?",
        "style" to "रंग और स्टाइल कैसा चाहिए (जैसे काला-सुनहरा प्रीमियम, हल्का साफ़, रंगीन)?",
        "contact" to "संपर्क में क्या डालूँ - फ़ोन / WhatsApp नंबर, पता, ईमेल?",
    )

    private val SELF = listOf("खुद तय", "ख़ुद तय", "अपने हिसाब", "अपने से", "जैसा ठीक लगे", "जो ठीक लगे", "कुछ भी", "तुम देख लो", "आप देख लो", "आप तय",
        "तुम तय", "you decide", "your choice", "anything", "khud", "apne hisab", "skip", "छोड़ो", "रहने दो", "मत पूछो", "default")

    fun selfDecide(s: String?): Boolean { val t = s.orEmpty().lowercase(); return SELF.any { t.contains(it) } }

    fun fromArgs(a: JSONObject) = Brief(a.optString("prompt").trim(), a.optString("site_type").trim(), a.optString("business_name").trim(),
        a.optString("sections").trim(), a.optString("style").trim(), a.optString("contact").trim())

    private fun value(b: Brief, k: String) = when (k) { "site_type" -> b.type; "business_name" -> b.name; "sections" -> b.sections; "style" -> b.style; else -> b.contact }

    fun missing(b: Brief): List<String> = FIELDS.keys.filter { value(b, it).isBlank() }

    /**
     * DK लाइव build_website: ब्रीफ़ अधूरा = null नहीं, मॉडल को "पहले पूछो" वाला जवाब (Forge शुरू नहीं).
     * details_done=true (मालिक ने सब बता दिया / "खुद तय करो") = null -> बनाना शुरू.
     */
    fun liveGate(a: JSONObject): String? {
        if (a.optBoolean("details_done", false) || selfDecide(a.optString("prompt"))) return null
        val miss = missing(fromArgs(a))
        if (miss.size <= 1) return null          // लगभग पूरा - बाकी DK खुद
        return "need_details: the website is NOT being built yet. Before building, get the details from the user - ask these one by one, short, in his language: " +
            miss.joinToString(" | ") { FIELDS[it]!! } +
            " . Fill what he already said yourself. If he says you decide (तुम खुद तय करो), decide sensibly. Then call build_website again with prompt + " +
            "site_type, business_name, sections, style, contact filled and details_done=true. Do NOT say it has started."
    }

    /** बोली / लिखी बात (AgentRunner) में ब्रीफ़ बहुत छोटा है? ("एक वेबसाइट बनाओ", "कैफ़े की वेबसाइट बनाओ") */
    fun vagueText(prompt: String): Boolean {
        if (selfDecide(prompt)) return false
        val words = prompt.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() && it !in FILLER }
        return words.size < 4
    }
    private val FILLER = setOf("dk", "डीके", "एक", "ek", "a", "an", "मेरे", "मेरी", "मेरा", "लिए", "के", "की", "का", "को", "website", "वेबसाइट", "वेब", "साइट", "site",
        "बनाओ", "बना", "दो", "बनाइए", "बनाएँ", "बनाएं", "banao", "bana", "do", "make", "build", "create", "please", "प्लीज़", "प्लीज", "मुझे", "चाहिए", "page", "पेज")

    /** AgentRunner वाला एक सवाल (सब एक साथ) */
    const val TEXT_QUESTION = "वेबसाइट अच्छी बने इसलिए थोड़ा बताइए - किस काम की है, नाम क्या लिखूँ, कौन-कौन से हिस्से चाहिए, रंग-स्टाइल कैसा, और संपर्क में फ़ोन या पता क्या डालूँ? " +
        "या बोलिए \"तुम खुद तय करो\"।"

    /** ब्रीफ़ + मालिक की बात -> Forge के लिए पूरा विवरण */
    fun fullPrompt(b: Brief, extra: String = ""): String = buildString {
        append(b.prompt.ifBlank { b.type.ifBlank { "Professional website" } })
        if (b.type.isNotBlank()) append("\nWebsite type / business: ").append(b.type)
        if (b.name.isNotBlank()) append("\nName shown on the site: ").append(b.name)
        if (b.sections.isNotBlank()) append("\nSections (in this order): ").append(b.sections)
        if (b.style.isNotBlank()) append("\nColours and style: ").append(b.style)
        if (b.contact.isNotBlank()) append("\nContact details (use exactly, make phone click-to-call and a WhatsApp button): ").append(b.contact)
        if (extra.isNotBlank()) append("\nMore from the owner: ").append(extra)
        append("\nQuality: fully professional and mobile-first - real, specific copy (no lorem ipsum), clear hero with call-to-action, " +
            "consistent spacing and typography, working navigation, footer with contact. Anything not given above: decide sensibly for this business.")
    }

    fun fullPrompt(a: JSONObject): String = fullPrompt(fromArgs(a))
}
