package com.dk.assistant

/**
 * 22 नवंबर 2026 (मालिक: "हिंदी/इंग्लिश सेट है तो DK सिर्फ़ हिंदी-इंग्लिश में बात करे. दूसरी भाषा के लिए दो बार बोला जाए
 * तब DK पूछे 'क्या आप इस भाषा में बात करना चाहते हैं?' - हाँ बोले तभी उस भाषा में").
 *
 * जड़: Live मॉडल आवाज़ से भाषा खुद पकड़ता है - मराठी/भोजपुरी लहजे या ग़लत सुनी बात (उर्दू/बंगाली लिपि में लिखा सुनना)
 * पर वह बिना पूछे उसी भाषा में जवाब देने लगता था. Prompt का नियम ([Lang.rule]) + यह पहरा: DK का जवाब किसी और
 * भाषा में गया और यूज़र ने हाँ नहीं कहा = SYSTEM NOTE से वापस चुनी भाषा में.
 *
 * Android import नहीं -> JVM test.
 */
object LangLock {
    /** किन भाषा-सेटिंग पर ताला (Auto / भोजपुरी / मराठी = यूज़र ने खुद दूसरी भाषा चुनी है, ताला नहीं) */
    fun locked(code: String): Boolean = Lang.norm(code).let { it == Lang.HINGLISH || it == Lang.HINDI || it == Lang.ENGLISH }

    /** हिंदी नाम (DK का सवाल इसी में) */
    val NAMES: Map<String, String> = linkedMapOf(
        "marathi" to "मराठी", "bhojpuri" to "भोजपुरी", "urdu" to "उर्दू", "bengali" to "बंगाली", "punjabi" to "पंजाबी",
        "gujarati" to "गुजराती", "odia" to "ओड़िया", "tamil" to "तमिल", "telugu" to "तेलुगु", "kannada" to "कन्नड़",
        "malayalam" to "मलयालम", "nepali" to "नेपाली", "spanish" to "स्पेनिश", "french" to "फ़्रेंच", "german" to "जर्मन",
        "arabic" to "अरबी", "chinese" to "चीनी", "japanese" to "जापानी", "russian" to "रूसी",
    )

    /** भाषा के नाम से पहचान (हिंदी या रोमन में लिखा) */
    private val ALIAS: List<Pair<String, String>> = listOf(
        "मराठी" to "marathi", "marathi" to "marathi", "भोजपुरी" to "bhojpuri", "bhojpuri" to "bhojpuri", "उर्दू" to "urdu", "urdu" to "urdu",
        "बंगाली" to "bengali", "बांग्ला" to "bengali", "bengali" to "bengali", "bangla" to "bengali", "पंजाबी" to "punjabi", "punjabi" to "punjabi",
        "गुजराती" to "gujarati", "gujarati" to "gujarati", "ओड़िया" to "odia", "उड़िया" to "odia", "odia" to "odia", "oriya" to "odia",
        "तमिल" to "tamil", "tamil" to "tamil", "तेलुगु" to "telugu", "telugu" to "telugu", "कन्नड़" to "kannada", "kannada" to "kannada",
        "मलयालम" to "malayalam", "malayalam" to "malayalam", "नेपाली" to "nepali", "nepali" to "nepali",
        "स्पेनिश" to "spanish", "spanish" to "spanish", "फ्रेंच" to "french", "फ़्रेंच" to "french", "french" to "french",
        "जर्मन" to "german", "german" to "german", "अरबी" to "arabic", "arabic" to "arabic", "चाइनीज़" to "chinese", "chinese" to "chinese",
        "जापानी" to "japanese", "japanese" to "japanese", "रशियन" to "russian", "russian" to "russian",
    )

    private val MARATHI = setOf("आहे", "आहेत", "आहेस", "नाही", "मला", "तुम्हाला", "तुम्ही", "काय", "झाले", "झालं", "पाहिजे", "सांगा",
        "करतो", "करते", "केलं", "केले", "मी", "आम्ही", "तुला", "कसा", "कशी", "होतं", "बरं", "आता")
    private val BHOJPURI = setOf("बानी", "रउआ", "रउरा", "हमनी", "बाटे", "बाड़ी", "बाड़े", "बा", "कइसे", "कइसन", "गइल", "भइल", "रहल", "करत", "तनी", "हउवे")
    private val SPLIT = Regex("[\\s,.!?।॥:;\"'()\\-]+")

    /** Hindi नाम -> "क्या आप मराठी में बात करना चाहते हैं?" */
    fun question(lang: String): String = "क्या आप ${NAMES[lang] ?: lang} में बात करना चाहते हैं?"

    /** इस लिखावट में कौन-सी (हिंदी/इंग्लिश से अलग) भाषा - नहीं = null */
    fun foreign(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        var letters = 0
        val count = HashMap<String, Int>()
        for (ch in t) {
            if (!ch.isLetter()) continue
            letters++
            val l = when (ch) {
                in '\u0600'..'\u06FF', in '\u0750'..'\u077F' -> "urdu"
                in '\u0980'..'\u09FF' -> "bengali"
                in '\u0A00'..'\u0A7F' -> "punjabi"
                in '\u0A80'..'\u0AFF' -> "gujarati"
                in '\u0B00'..'\u0B7F' -> "odia"
                in '\u0B80'..'\u0BFF' -> "tamil"
                in '\u0C00'..'\u0C7F' -> "telugu"
                in '\u0C80'..'\u0CFF' -> "kannada"
                in '\u0D00'..'\u0D7F' -> "malayalam"
                in '\u4E00'..'\u9FFF' -> "chinese"
                in '\u3040'..'\u30FF' -> "japanese"
                in '\u0400'..'\u04FF' -> "russian"
                else -> null
            } ?: continue
            count[l] = (count[l] ?: 0) + 1
        }
        count.maxByOrNull { it.value }?.let { (l, n) -> if (letters > 0 && n * 3 >= letters) return l }
        val words = t.split(SPLIT).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        fun hits(s: Set<String>) = words.count { it in s }
        val need = if (words.size <= 4) 1 else 2
        val m = hits(MARATHI)
        val b = hits(BHOJPURI)
        // "आता"/"बा" जैसे शब्द हिंदी में भी आ सकते हैं - अकेला कमज़ोर शब्द काफ़ी नहीं
        if (m >= need && m >= b && !(m == 1 && words.any { it == "आता" })) return "marathi"
        if (b >= need && !(b == 1 && words.any { it == "बा" || it == "करत" })) return "bhojpuri"
        return null
    }

    /** यूज़र ने खुद किसी भाषा में बोलने को कहा? ("मराठी में बोलो", "speak in tamil", "marathi me baat karo") */
    fun asked(text: String?): String? {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        for ((name, code) in ALIAS) {
            val n = Regex.escape(name)
            val r1 = Regex("(^|[^\\p{L}\\p{M}])$n\\s*(भाषा\\s*)?(में|मे|मध्ये|me|mein|mai|language\\s*(me|mein|में))\\s*(ही\\s*)?(बोल|बात|जवाब|बता|bol|baat|jawab|bata|reply|speak|talk)")
            val r2 = Regex("(speak|talk|reply|answer)\\s+(to me\\s+)?in\\s+$n")
            if (r1.containsMatchIn(t) || r2.containsMatchIn(t)) return code
        }
        return null
    }

    /** हिंदी/इंग्लिश में वापस आने को कहा? */
    fun backHome(text: String?): Boolean {
        val t = text?.lowercase().orEmpty()
        return listOf("हिंदी में", "हिन्दी में", "इंग्लिश में", "english में", "hindi me", "hindi mein", "english me", "in hindi", "in english")
            .any { t.contains(it) }
    }

    private val YES = setOf("हाँ", "हां", "हा", "जी", "हाँजी", "हांजी", "बिल्कुल", "ठीक", "yes", "haan", "han", "ha", "ji", "ok", "okay", "हो", "होय")
    private val NO = setOf("नहीं", "नही", "ना", "no", "nahi", "nahin", "नको")
    fun yes(text: String?): Boolean {
        val w = text?.lowercase()?.split(SPLIT)?.filter { it.isNotBlank() }.orEmpty()
        return w.isNotEmpty() && w.size <= 6 && w.any { it in YES } && w.none { it in NO }
    }

    /** एक बातचीत की हालत (Live का एक session) */
    class State {
        /** यूज़र ने हाँ कहकर चुनी भाषा (null = चुनी हुई सेटिंग वाली) */
        var approved: String? = null
        /** जिस भाषा की बात चल रही (लगातार कितनी बार) */
        var pending: String? = null
        var count: Int = 0
        /** DK ने पूछ लिया - अगली बात में हाँ/ना */
        var askedFor: String? = null
        fun reset() { approved = null; pending = null; count = 0; askedFor = null }
    }

    /**
     * एक पूरी बारी (यूज़र ने क्या कहा, DK ने क्या कहा) -> Live को भेजने वाला SYSTEM NOTE (या null).
     * [home] = "Hindi/Hinglish" जैसा नाम जिसमें वापस आना है.
     */
    fun turn(st: State, code: String, user: String, reply: String): String? {
        if (!locked(code)) { st.reset(); return null }
        // 1) DK ने पिछली बार पूछा था -> हाँ = वही भाषा, बाकी = चुनी भाषा ही
        st.askedFor?.let { a ->
            if (user.isNotBlank()) {
                if (yes(user)) { st.approved = a; st.pending = null; st.count = 0 }
                st.askedFor = null
            }
        }
        if (backHome(user)) { st.approved = null; st.pending = null; st.count = 0; st.askedFor = null }
        // 2) यूज़र की बात में दूसरी भाषा (बोलकर या माँगकर) - लगातार दो बार गिनो
        val ul = asked(user) ?: foreign(user)
        if (ul != null && ul != st.approved) {
            if (ul == st.pending) st.count++ else { st.pending = ul; st.count = 1 }
        } else if (user.isNotBlank() && ul == null && st.askedFor == null) {
            st.pending = null; st.count = 0
        }
        // 3) DK ने खुद पूछ लिया?
        val dkAsked = reply.contains("में बात करना चाहते") || reply.contains("में बात करें") || reply.contains("want to talk in")
        if (dkAsked) { st.askedFor = st.pending ?: ul ?: st.askedFor; return null }
        // 4) DK का जवाब किसी और भाषा में (हाँ नहीं कहा) = वापस
        val rl = foreign(reply)
        val home = if (Lang.norm(code) == Lang.ENGLISH) "English" else "Hindi/Hinglish"
        if (rl != null && rl != st.approved) {
            val ask = st.pending?.takeIf { st.count >= 2 }
            return if (ask != null) {
                st.askedFor = ask
                "SYSTEM NOTE (not from the user): you just replied in ${rl.replaceFirstChar { it.uppercase() }}, but the user's language setting is $home and they " +
                    "have not said yes to another language. Do not use that language. Say briefly in Hindi: \"${question(ask)}\" and switch only if they say हाँ."
            } else {
                "SYSTEM NOTE (not from the user): you just replied in ${rl.replaceFirstChar { it.uppercase() }} by mistake. The user's language setting is $home - " +
                    "never switch on your own. Say your last answer again, briefly, in $home only, without mentioning this note."
            }
        }
        // 5) दो बार दूसरी भाषा, DK ने नहीं पूछा (चुनी भाषा में ही जवाब दिया) - अब पूछे
        val a = st.pending
        if (st.count >= 2 && a != null && st.askedFor == null) {
            st.askedFor = a
            return "SYSTEM NOTE (not from the user): the user spoke / asked for ${a.replaceFirstChar { it.uppercase() }} twice. Ask now, briefly, in Hindi only: " +
                "\"${question(a)}\" Switch to it only if they clearly say हाँ; otherwise keep $home."
        }
        return null
    }
}
