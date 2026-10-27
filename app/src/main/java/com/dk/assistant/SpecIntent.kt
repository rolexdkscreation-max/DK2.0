package com.dk.assistant

/**
 * v3.88: यूज़र अभी बोल ही रहा है (अधूरा text) - उसी से अंदाज़ा कि क्या चाहिए, ताकि डेटा पहले से तैयार रहे.
 * Android import नहीं (JVM test). अंदाज़ा गलत = तैयार डेटा चुपचाप बेकार (कुछ दिखता/बोला नहीं जाता).
 */
object SpecIntent {
    enum class Kind { WEATHER, SEARCH }

    private val WEATHER = Regex(
        "(मौसम|मोसम|तापमान|टेंपरेचर|टेम्परेचर|बारिश|बरसात|ठंड|गर्मी|धूप|weather|mausam|mosam|temperature|rain|baarish|barish)",
        RegexOption.IGNORE_CASE)
    // ताज़ा खबर / स्कोर / भाव - ये सवाल लगभग हमेशा web_search ही बनते हैं
    private val SEARCH = Regex(
        "(खबर|ख़बर|न्यूज़|न्यूज|समाचार|स्कोर|मैच|भाव|रेट|शेयर|सेंसेक्स|निफ्टी|news|khabar|score|match|ipl|cricket|price|rate|sensex|nifty|bitcoin)",
        RegexOption.IGNORE_CASE)
    // खोज तभी जब सच में कुछ पूछा/माँगा हो ("कल मैच देखा, मज़ा आया" = खोज नहीं)
    private val ASK = Regex(
        "(बताओ|बताइए|बताना|बता दो|क्या|कितना|कितने|कितनी|कौन|कैसा|सुनाओ|ताज़ा|ताजा|आज|अभी|लेटेस्ट|batao|bata|kya|kitna|kaun|kaisa|sunao|aaj|abhi|latest|what|who|how|today|tell)",
        RegexOption.IGNORE_CASE)
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val STOP = setOf("डीके", "dk", "हेलो", "हैलो", "hello", "hi", "हाय", "आज", "का", "की", "के", "को", "में", "मे", "है", "हैं",
        "बताओ", "बताइए", "बताना", "बता", "क्या", "कैसा", "कैसी", "ज़रा", "जरा", "मुझे", "प्लीज़", "please", "tell", "me", "the", "what",
        "is", "today", "aaj", "ka", "ki", "ke", "batao", "kya", "hey", "ओके", "ok", "और", "भी", "अभी", "abhi")

    /** null = कोई पक्का अंदाज़ा नहीं (तब कुछ पहले से नहीं) */
    fun detect(text: String): Kind? {
        val t = text.trim()
        if (t.length < 4) return null
        if (WEATHER.containsMatchIn(t)) return Kind.WEATHER
        if (SEARCH.containsMatchIn(t) && ASK.containsMatchIn(t)) return Kind.SEARCH
        return null
    }

    fun words(text: String): Set<String> =
        WORD.findAll(text.lowercase()).map { it.value.replace("\u093C", "") }.filter { it.length > 1 && it !in STOP }.toSet()

    /** Gemini ने जो खोज माँगी वह उसी बात की है जिसके लिए पहले से खोजा था? (कम से कम एक मुख्य शब्द साझा) */
    fun sameTopic(asked: String, prepared: String): Boolean {
        val a = words(asked); val p = words(prepared)
        if (a.isEmpty() || p.isEmpty()) return false
        return a.any { it in p }
    }
}
