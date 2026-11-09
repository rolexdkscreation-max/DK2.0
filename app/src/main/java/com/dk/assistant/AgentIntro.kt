package com.dk.assistant

/**
 * नवंबर 2026 (मालिक): DK कंप्यूटर और कोडिंग एजेंट खुलते ही लंबी "मैं क्या-क्या कर सकता हूँ" सूची नहीं।
 * शुरू में सिर्फ़ एक छोटी लाइन; कोई पूछे "तुम क्या-क्या कर सकते हो" तभी पूरी सूची —
 * और वह भी उस समय चालू टूल देखकर (बंद टूल की बात नहीं)।
 * Pure Kotlin (Android import नहीं) -> JVM test.
 */
object AgentIntro {

    /** खुलते ही दिखने वाली एक लाइन */
    fun computerHello(name: String) = "नमस्ते, मैं $name कंप्यूटर हूँ। बताइए क्या काम करना है।"
    fun coderHello(where: String) = "नमस्ते। $where — बताइए क्या बनाना या ठीक करना है।"

    private val ASK = listOf(
        "क्या कर सकते", "क्या-क्या कर सकते", "क्या क्या कर सकते", "क्या कर सकता", "क्या-क्या कर सकता", "क्या क्या कर सकता",
        "क्या कर सकती", "क्या-क्या कर सकती", "क्या कर लेते", "तुम्हारे फ़ीचर", "तुम्हारे फीचर", "तुम्हारी खूबी", "क्या आता है",
        "kya kar sakte", "kya kya kar sakte", "kya kar sakta", "kya kya kar sakta", "kya kar sakti",
        "what can you do", "what all can you do", "your capabilities", "your features", "what are you capable", "help me understand what you do"
    )

    /** यूज़र सिर्फ़ यह पूछ रहा है कि एजेंट क्या कर सकता है (कोई असली काम नहीं) */
    fun isCapabilityQuestion(text: String): Boolean {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 90) return false
        return ASK.any { t.contains(it) }
    }

    private val COMPUTER_LINES = linkedMapOf(
        "web_search" to "इंटरनेट पर खोज और रिसर्च (स्रोत के लिंक के साथ)",
        "read_url" to "वेबसाइटें पूरी पढ़ना, कई पेज एक साथ",
        "browse" to "असली ब्राउज़र चलाना — बटन, फ़ॉर्म, सर्च बॉक्स वाली साइटें",
        "look" to "फ़ोटो, स्क्रीनशॉट और PDF देखकर समझना",
        "sub_agents" to "बड़ी रिसर्च के लिए कई सहायक एजेंट एक साथ",
        "run_js" to "हिसाब, डेटा, तुलना (कोड चलाकर, अंदाज़े से नहीं)",
        "write_file" to "रिपोर्ट, लिस्ट, टेबल — Markdown / CSV / HTML फ़ाइल",
        "make_pdf" to "PDF रिपोर्ट",
        "make_docx" to "Word फ़ाइल (चिट्ठी, आवेदन, रिज़्यूमे)",
        "make_xlsx" to "Excel शीट (बजट, लिस्ट, तुलना)",
        "make_chart" to "चार्ट / ग्राफ़ (bar, line, pie)",
        "phone_task" to "फ़ोन के काम — ऐप खोलना, मैसेज, कॉल (पहले आपसे पूछकर)",
        "coding_task" to "GitHub पर कोड बदलवाना (कोडिंग टीम)",
        "remember" to "आपकी बातें याद रखना",
        "schedule" to "बाद में या रोज़ अपने-आप काम चलाना / रिमाइंडर",
        "past_tasks" to "पिछले कामों और फ़ाइलों को ढूँढना"
    )

    /** @param enabled इस समय Settings में चालू टूल */
    fun computerCapabilities(name: String, enabled: Collection<String>): String {
        val on = COMPUTER_LINES.filterKeys { it in enabled }.values
        val sb = StringBuilder("मैं $name कंप्यूटर हूँ। अभी ये सब कर सकता हूँ:\n")
        on.forEach { sb.append("• ").append(it).append('\n') }
        sb.append("• कोई भी ऐप बनाना — वेबसाइट, वेब ऐप, गेम, Android (ऐप स्टूडियो में, वहीं preview)\n")
        sb.append("\nकैसे काम करता हूँ: पहले समझकर योजना → एक-एक कदम काम → खुद जाँच → आखिर में साफ़ रिपोर्ट।")
        val off = COMPUTER_LINES.keys.filter { it !in enabled }
        if (off.isNotEmpty()) sb.append("\n(${off.size} टूल Settings → DK कंप्यूटर में बंद हैं।)")
        sb.append("\n\nउदाहरण: \"आज की भारत की 5 बड़ी टेक खबरें स्रोत के साथ\", \"₹15 लाख के होम लोन की EMI 8.5% पर 20 साल\"")
        return sb.toString()
    }

    fun coderCapabilities(seven: Boolean, github: Boolean): String {
        val sb = StringBuilder("कोडिंग एजेंट ये सब कर सकता है:\n")
        sb.append("• नया फ़ीचर बनाना, पुराना बदलना या हटाना (पहले backup)\n")
        sb.append("• बग की जड़ ढूँढकर ठीक करना — फ़ाइल और लाइन के साथ\n")
        sb.append("• फ़ोटो / स्क्रीनशॉट / वीडियो देखकर समझना कि क्या ठीक करना है\n")
        sb.append("• रिपो पढ़कर समझाना कि कोई चीज़ अभी कैसे काम करती है\n")
        if (github) sb.append("• GitHub पर नई branch → PR → सारे tests + build → आपकी मंज़ूरी से merge → नई APK\n")
        else sb.append("• नॉर्मल मोड: कोड हर फ़ाइल के नाम के साथ (कॉपी बटन)\n")
        sb.append("• वेबसाइट / वेब ऐप / गेम ऐप स्टूडियो में बनाना\n")
        if (seven) sb.append("\nटीम: मैनेजर → प्लानर → कोडर → रिव्यूअर → टेस्टर → डिबगर → गिट।")
        sb.append("\nकैसे काम करता हूँ: पहले बताता हूँ क्या समझा — आपकी \"हाँ\" के बाद ही code बदलता है।")
        return sb.toString()
    }
}

/**
 * नवंबर 2026 (मालिक): चैट में सिर्फ़ बातचीत - आपका संदेश, जवाब, फ़ाइल, गलती.
 * "s" (कदम: 🧠 … से काम शुरू, 💭 सोच, ⚡ …) और "p" (योजना कार्ड) चैट के बीच नहीं दिखते;
 * अभी का कदम / योजना ऊपर की प्रगति पट्टी और नीचे "काम चल रहा है" वाले बुलबुले में.
 */
object ChatClean {
    /**
     * @param busy काम अभी चल रहा है
     * @param isLast यह सबसे नई लाइन है
     * "p" कभी नहीं; "s" सिर्फ़ तब जब काम नहीं चल रहा और यह आखिरी लाइन है (जैसे "📝 नॉर्मल मोड चुना" जैसी सूचना)
     */
    fun showInChat(role: String, busy: Boolean, isLast: Boolean): Boolean = when (role) {
        "p" -> false
        "s" -> !busy && isLast
        else -> true
    }

    /** काम चलते समय बुलबुले की एक छोटी लाइन (लंबी सोच/कदम नहीं) */
    fun busyLabel(done: Int?, total: Int?): String =
        if (done != null && total != null && total > 1) "काम चल रहा है… (कदम ${minOf(done + 1, total)}/$total)" else "काम चल रहा है…"
}
