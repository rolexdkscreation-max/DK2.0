package com.dk.assistant

/**
 * v3.75: DK Live "सर्च करके बताता हूँ / एक मिनट देखता हूँ / कर रहा हूँ" बोलकर रुक जाता था -
 * असल में न सर्च, न काम (Live मॉडल वादे वाली लाइन बोलकर बारी खत्म कर देता है, tool बुलाता ही नहीं).
 * यह pure जाँच (JUnit से): जवाब सिर्फ़ वादा है? तो फ़ोन खुद सर्च करे / काम करवाए.
 */
object LivePromise {
    enum class Kind { NONE, SEARCH, ACTION }

    private val SEARCH_PROMISE = listOf(
        "सर्च करके", "सर्च कर के", "सर्च करती", "सर्च करता", "खोजकर", "खोज कर", "खोज के", "खोजता", "खोजती",
        "ढूंढ", "ढूँढ", "पता करके", "पता कर के", "पता करता", "पता करती", "देखकर बता", "देख कर बता", "देख के बता",
        "देखता हूँ", "देखता हूं", "देखती हूँ", "देखती हूं", "चेक करके", "चेक कर के", "चेक करता", "चेक करती",
        "रिसर्च करके", "रिसर्च कर के", "एक मिनट", "1 मिनट", "एक सेकंड", "1 सेकंड", "थोड़ा रुको", "थोड़ा रुकिए", "रुकिए",
        "search karke", "search kar ke", "dekh ke bata", "dekhkar bata", "pata karke", "check karke", "ek minute", "ek second",
        "let me check", "let me search", "let me look", "let me find", "i'll check", "i will check", "i'll search", "i will search",
        "i'll look", "one moment", "one sec", "just a moment", "just a second", "give me a minute", "give me a second",
        "سرچ کر", "دیکھ کے بتا", "دیکھ کر بتا", "ایک منٹ", "پتا کر"
    )
    private val DOING_PROMISE = listOf(
        "कर रहा हूँ", "कर रहा हूं", "कर रही हूँ", "कर रही हूं", "कर देता हूँ", "कर देता हूं", "कर देती हूँ", "कर देती हूं",
        "खोल रहा", "खोल रही", "खोलता हूँ", "खोलता हूं", "खोलती हूँ", "खोलती हूं", "सर्च कर रहा", "सर्च कर रही",
        "kar raha hu", "kar rahi hu", "khol raha", "khol rahi", "abhi karta", "abhi karti",
        "i'm doing", "i am doing", "doing it", "opening", "searching",
        "کر رہا ہوں", "کھول رہا"
    )
    /** user ने फ़ोन पर कोई काम कहा (ऐप/सर्च/खोलो) */
    private val PHONE_ASK = listOf(
        "खोलो", "खोल दो", "ओपन", "open", "kholo", "chalao", "चलाओ", "लगाओ", "कॉल", "call", "भेजो", "send",
        "क्रोम", "chrome", "ब्राउज़र", "ब्राउजर", "browser", "ऐप", "एप", " app", "यूट्यूब", "youtube", "वेबसाइट", "website",
        "सर्च करो", "सर्च कर दो", "search karo", "search kar do", "टाइप", "type", "दबाओ", "tap", "click",
        "کھولو", "کروم", "سرچ کرو"
    )
    /**
     * v3.90: Live मॉडल बिना खोजे ही "माफ़ करिए, मुझे जानकारी नहीं मिल पाई / मुझे नहीं पता" बोल देता था
     * (web_search बुलाया ही नहीं). ऐसा जवाब = फ़ोन खुद खोजे और सही जवाब दिलवाए.
     */
    private val REFUSAL = listOf(
        "जानकारी नहीं मिल", "जानकारी नहीं है", "जानकारी नहीं दे", "जानकारी उपलब्ध नहीं", "पक्की जानकारी नहीं", "सही जानकारी नहीं",
        "जानकारी नहीं निकल", "कुछ नहीं मिला", "नहीं मिल पाई", "नहीं मिल पाया", "नहीं मिल सकी", "पता नहीं चल पाया", "पता नहीं चल सका",
        "मुझे नहीं पता", "मुझे पता नहीं", "मैं नहीं जानता", "मैं नहीं जानती", "नहीं बता सकता", "नहीं बता सकती", "नहीं बता पाऊँगा", "नहीं बता पाऊँगी",
        "नहीं बता पाऊंगा", "नहीं बता पाऊंगी", "मेरे पास इसकी जानकारी", "मेरे पास जानकारी नहीं", "मेरे पास कोई जानकारी", "real-time", "रियल टाइम जानकारी",
        "jaankari nahi mil", "jankari nahi mil", "mujhe nahi pata", "pata nahi chal",
        "i don't know", "i do not know", "couldn't find", "could not find", "no information", "don't have information",
        "don't have access to real", "i'm not sure about", "not able to find",
        "معلومات نہیں مل", "مجھے نہیں پتا"
    )

    /** जवाब "जानकारी नहीं मिली / पता नहीं" वाला है (और याद-वाला "याद नहीं" नहीं) */
    fun refuses(reply: String): Boolean {
        val r = reply.trim()
        if (r.isEmpty() || r.length > MAX_REFUSAL_CHARS) return false
        if (r.contains("याद नहीं")) return false
        return has(r, REFUSAL)
    }
    const val MAX_REFUSAL_CHARS = 260

    /** अपनी निजी बात ("मेरा नाम क्या है", "मैंने क्या कहा") इंटरनेट पर नहीं खोजी जाती */
    private val PERSONAL = listOf("मेरा ", "मेरी ", "मेरे ", "मैंने", "मुझे क्या", "हमने", "तुमने", "तुम्हारा", "तुम्हारी", "mera ", "meri ", "maine", " my ", "i said")

    /** यूज़र ने कोई जानकारी माँगी (फ़ोन का काम या अपनी निजी बात नहीं) */
    fun asksInfo(user: String): Boolean {
        val u = user.trim()
        return u.length >= 6 && !has(u, PHONE_ASK) && !has(u, PERSONAL)
    }

    /** इतने से लंबा जवाब = उसमें असली बात भी है, सिर्फ़ वादा नहीं */
    const val MAX_PROMISE_CHARS = 180

    fun has(t: String, w: List<String>): Boolean { val x = " ${t.lowercase()} "; return w.any { x.contains(it) } }

    /** जवाब में सिर्फ़ वादा है? किस तरह का (सर्च या फ़ोन का काम) */
    fun kind(reply: String, user: String): Kind {
        val r = reply.trim()
        if (r.isEmpty()) return Kind.NONE
        if (r.length > MAX_PROMISE_CHARS) return if (refuses(r) && asksInfo(user)) Kind.SEARCH else Kind.NONE
        val search = has(r, SEARCH_PROMISE)
        val doing = has(r, DOING_PROMISE)
        if (!search && !doing) return if (refuses(r) && asksInfo(user)) Kind.SEARCH else Kind.NONE
        return if (has(user, PHONE_ASK)) Kind.ACTION else Kind.SEARCH
    }

    /** screen पर दिखने वाली लाइन - DK अभी क्या कर रहा है */
    fun hintFor(tool: String, arg: String = ""): String {
        val a = arg.trim().take(40)
        return when (tool) {
            "web_search" -> "इंटरनेट पर खोज रहा है" + (if (a.isNotEmpty()) ": $a" else "") + "…"
            "google_search" -> "इंटरनेट पर खोज रहा है…"
            "deep_think" -> "गहराई से सोच रहा है…"
            "phone_task" -> "फ़ोन पर काम कर रहा है" + (if (a.isNotEmpty()) ": $a" else "") + "…"
            "computer_task" -> "DK Computer काम शुरू कर रहा है…"
            "coding_task" -> "कोडिंग टीम को काम दे रहा है…"
            "build_website" -> "वेबसाइट बना रहा है…"
            "whatsapp_task", "social_media_task" -> "संदेश/ऐप का काम कर रहा है…"
            "notifications", "notification_reply" -> "नोटिफ़िकेशन देख रहा है…"
            "connector_task" -> "जुड़े ऐप से जानकारी ला रहा है…"
            "local_info" -> "जगह/मौसम देख रहा है…"
            else -> ""
        }
    }

    /** सर्च हो गया - Gemini अब जवाब बोले */
    fun searchNote(question: String, result: String): String =
        "SYSTEM NOTE: you told the user you would search/check (or that you could not find / do not know it) but you did not call any tool. " +
            "If you said you could not find it, first say one short line like \"रुकिए, मिल गया\". The phone searched the internet for: \"" +
            question.take(200) + "\". RESULT: \"" + result.take(2500) + "\". Now give the user the answer from this result in their language, " +
            "short and clear (2-4 sentences). Do NOT say you will search again and do not invent anything beyond the result."

    fun searchFailNote(question: String, why: SearchGuard.Fail = SearchGuard.Fail.OTHER): String =
        "SYSTEM NOTE: you promised to search but did not call any tool, and the phone's own internet search for \"" + question.take(200) +
            "\" did not work. Start with \"" + WebSearch.ALL_FAILED_LINE + "\", then answer from your own knowledge and say it may not be the latest " +
            "(never invent exact live figures). If the user asks why: \"" + SearchGuard.userLine(why) + "\" Never mention a tool, an API or a key by name. " +
            "If the user says search again, call web_search with that question.\n"

    /** फ़ोन का काम बोला "कर रहा हूँ" पर कुछ नहीं किया */
    /** v3.6: फ़ोन ने खुद phone_task चलाया (Gemini ने वादा किया पर tool नहीं बुलाया) - असली नतीजा */
    fun actionDoneNote(user: String, result: String): String =
        "SYSTEM NOTE: you said you would do \"" + user.take(200) + "\" but did not call a tool, so the phone ran phone_task itself. " +
            "Real result: " + result.take(600) + " - Tell the user this real result in 1 short sentence. Do NOT call phone_task again for this."

    /** नतीजा सफल? (DONE / OK से शुरू, ERROR/FAIL नहीं) */
    fun actionOk(result: String): Boolean {
        val r = result.trim().uppercase()
        return !r.startsWith("ERROR") && !r.startsWith("FAIL") && !r.contains("NOT_DONE") && !r.startsWith("NOT ")
    }

    fun actionNote(user: String): String =
        "SYSTEM NOTE: you said you are doing it, but you did NOT call any tool - nothing happened on the phone. Call phone_task RIGHT NOW " +
            "with request = \"" + user.take(300) + "\" (the user's exact words). Do not say it is done until the tool result says DONE."
}
