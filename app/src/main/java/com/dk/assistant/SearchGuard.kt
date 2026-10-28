package com.dk.assistant

/**
 * v3.87: खोज (web_search) पक्की करने के नियम - Android के बिना (JVM test)।
 *
 * असली वजह जो मिली (कोड जाँच से):
 *  1. फ़ोन वाली खोज हमेशा "DK चैट" की Gemini key (key_gemini) से चलती थी - DK Live की अपनी key
 *     (live_key_gemini) कभी नहीं। चैट में Groq/OpenRouter चुना हो या चैट की Gemini key खाली/ख़त्म हो
 *     = Live में हर खोज fail -> "SEARCH FAILED" -> DK बोलती "सर्च टूल काम नहीं किया"।
 *  2. खोज के timeout (withTimeoutOrNull) बंद-धागे (blocking) वाली HTTP कॉल को रोक नहीं पाते थे, एक खोज
 *     3-4 मॉडल × 35 सेकंड तक लटक सकती थी - Live का 40 सेकंड वाला इंतज़ार पहले ख़त्म = "timeout"।
 *  3. fail होने पर Gemini को कहा जाता था "search did not work" बताओ - दोबारा कोशिश कभी नहीं।
 */
object SearchGuard {

    /** fail होने पर DK यही बोले - "टूल" जैसा शब्द कभी नहीं */
    const val USER_LINE = "सर, अभी ताज़ा जानकारी नहीं निकल पाई, एक बार फिर कोशिश करूँ?"

    enum class Fail { NO_KEY, BAD_KEY, QUOTA, NETWORK, TIMEOUT, EMPTY, MODEL, OTHER }

    /** HTTP code + संदेश -> वजह (लॉग में साफ़ दिखे, और तय हो कि दूसरी key आज़माएँ या नहीं) */
    fun classify(code: Int, msg: String?): Fail {
        val m = msg.orEmpty().lowercase()
        return when {
            m.contains("no_key") || m.contains("unregistered callers") -> Fail.NO_KEY
            code == 401 || ((code == 400 || code == 403) && (m.contains("api key") || m.contains("api_key") || m.contains("permission_denied"))) -> Fail.BAD_KEY
            code == 429 || m.contains("quota") || m.contains("resource_exhausted") || m.contains("rate limit") -> Fail.QUOTA
            m.contains("timeout") || m.contains("timed out") -> Fail.TIMEOUT
            code == 404 || m.contains("not found") || m.contains("is not supported") -> Fail.MODEL
            m.contains("khali") || m.contains("empty") -> Fail.EMPTY
            code <= 0 && (m.contains("unable to resolve") || m.contains("failed to connect") || m.contains("network") ||
                m.contains("connection") || m.contains("ssl") || m.contains("host")) -> Fail.NETWORK
            else -> Fail.OTHER
        }
    }

    fun hindi(f: Fail): String = when (f) {
        Fail.NO_KEY -> "Gemini key नहीं डली"
        Fail.BAD_KEY -> "Gemini key ग़लत या बंद है"
        Fail.QUOTA -> "आज की Gemini सीमा (quota) ख़त्म"
        Fail.NETWORK -> "इंटरनेट नहीं मिला"
        Fail.TIMEOUT -> "जवाब समय पर नहीं आया"
        Fail.EMPTY -> "Google से ख़ाली जवाब"
        Fail.MODEL -> "मॉडल खोज नहीं करता"
        Fail.OTHER -> "अनजानी गड़बड़"
    }

    /** इस गड़बड़ में दूसरी key से फ़ायदा है (key/quota) - नेटवर्क में नहीं */
    fun tryNextKey(f: Fail) = f == Fail.NO_KEY || f == Fail.BAD_KEY || f == Fail.QUOTA || f == Fail.MODEL

    /**
     * खोज की keys क्रम में: जिस हिस्से (Live/चैट) से खोज हुई उसकी अपनी Gemini key पहले,
     * दूसरी Gemini key सिर्फ़ बचाव (पहली न हो / ग़लत हो / quota ख़त्म)। ख़ाली और दोहराई हुई keys नहीं।
     */
    fun keys(fromLive: Boolean, chatGeminiKey: String, liveGeminiKey: String, searchKey: String = ""): List<String> =
        // v3.90: खोज की अपनी key (डली हो) सबसे पहले; न चले तो पुराने जैसा Live/चैट वाली key
        (listOf(searchKey) + if (fromLive) listOf(liveGeminiKey, chatGeminiKey) else listOf(chatGeminiKey, liveGeminiKey))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /**
     * v3.90: यूज़र को वजह भी (पहले सिर्फ़ "जानकारी नहीं मिली" - क्यों, पता ही नहीं चलता था).
     * आसान शब्दों में, बिना "API/टूल" जैसे शब्दों के, और आगे क्या करें.
     */
    fun reasonHi(f: Fail): String = when (f) {
        Fail.NO_KEY -> "खोज के लिए Gemini की चाबी (key) सेटिंग में नहीं डली है"
        Fail.BAD_KEY -> "सेटिंग में डली Gemini key ग़लत या बंद है"
        Fail.QUOTA -> "आज की Google खोज की मुफ़्त सीमा अभी भर गई है, थोड़ी देर बाद फिर चलेगी"
        Fail.NETWORK -> "इंटरनेट नहीं मिल रहा"
        Fail.TIMEOUT -> "इंटरनेट धीमा है, वेबसाइटों से जवाब समय पर नहीं आया"
        Fail.EMPTY -> "किसी भी वेबसाइट पर इसकी पक्की जानकारी नहीं मिली"
        Fail.MODEL -> "सेटिंग में चुना Gemini मॉडल इंटरनेट खोज नहीं करता"
        Fail.OTHER -> "Google की तरफ़ से जवाब नहीं आया"
    }

    fun userLine(f: Fail): String = when (f) {
        Fail.NO_KEY, Fail.BAD_KEY, Fail.MODEL -> "सर, अभी जानकारी नहीं निकल पाई, क्योंकि ${reasonHi(f)}। सेटिंग में ठीक कर दीजिए, फिर तुरंत खोज हो जाएगी।"
        Fail.NETWORK -> "सर, अभी जानकारी नहीं निकल पाई, क्योंकि ${reasonHi(f)}। नेट चालू करके फिर पूछिए।"
        else -> "सर, अभी जानकारी नहीं निकल पाई, क्योंकि ${reasonHi(f)}। एक बार फिर कोशिश करूँ?"
    }

    /** Live के web_search का नतीजा जब Google खोज + दूसरी वेबसाइटें (बिना key वाली) सब आज़माने के बाद भी न मिले */
    fun failResult(q: String, f: Fail): String =
        "NO_RESULT (the phone tried Google search and other websites). Tell the user exactly this, in your usual voice and gender form, nothing else: \"${userLine(f)}\" " +
            "Do not use the words tool, API or error. Do NOT guess today's facts. If the user then says yes / हाँ / कर लो, call web_search again with the same query: \"${q.take(150)}\"."

    /** स्रोत तभी, जब सच में खोज से जवाब आया */
    fun sourcesLine(answer: String, sources: List<Pair<String, String>>): String =
        if (answer.isBlank() || sources.isEmpty()) "" else
            "\nSOURCES (the phone shows these links on screen; speak only the answer): " +
                sources.take(4).joinToString(", ") { it.first.ifBlank { it.second } }
}
