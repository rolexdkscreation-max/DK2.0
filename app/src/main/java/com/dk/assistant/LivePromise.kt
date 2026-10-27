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
    /** इतने से लंबा जवाब = उसमें असली बात भी है, सिर्फ़ वादा नहीं */
    const val MAX_PROMISE_CHARS = 180

    fun has(t: String, w: List<String>): Boolean { val x = " ${t.lowercase()} "; return w.any { x.contains(it) } }

    /** जवाब में सिर्फ़ वादा है? किस तरह का (सर्च या फ़ोन का काम) */
    fun kind(reply: String, user: String): Kind {
        val r = reply.trim()
        if (r.isEmpty() || r.length > MAX_PROMISE_CHARS) return Kind.NONE
        val search = has(r, SEARCH_PROMISE)
        val doing = has(r, DOING_PROMISE)
        if (!search && !doing) return Kind.NONE
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
        "SYSTEM NOTE: you told the user you would search/check but you did not call any tool. The phone searched the internet for: \"" +
            question.take(200) + "\". RESULT: \"" + result.take(2500) + "\". Now give the user the answer from this result in their language, " +
            "short and clear (2-4 sentences). Do NOT say you will search again and do not invent anything beyond the result."

    fun searchFailNote(question: String): String =
        "SYSTEM NOTE: you promised to search but did not call any tool, and the phone's own search for \"" + question.take(200) +
            "\" did not give a result. Say EXACTLY this, nothing else: \"" + SearchGuard.USER_LINE + "\" Never mention a tool, error or key. " +
            "If the user says yes, call web_search with that question."

    /** फ़ोन का काम बोला "कर रहा हूँ" पर कुछ नहीं किया */
    fun actionNote(user: String): String =
        "SYSTEM NOTE: you said you are doing it, but you did NOT call any tool - nothing happened on the phone. Call phone_task RIGHT NOW " +
            "with request = \"" + user.take(300) + "\" (the user's exact words). Do not say it is done until the tool result says DONE."
}
