package com.dk.assistant.template

/**
 * टेम्पलेट कमांड (साँचे): बिना API key / बिना AI के फ़ोन कंट्रोल.
 * बात ऊपर से नीचे साँचों से मिलाई जाती है, पहला मिला वही चलता है; खाली जगहें (___) निकाल ली जाती हैं.
 * कोई साँचा न मिले = null -> DK का पहले जैसा रास्ता (ऑटोनोमस / AI).
 * शुद्ध Kotlin (Android नहीं) -> JVM test.
 */
object TemplateCommands {
    /**
     * action: क्या करना है (TemplateRunner करता है). app: किस ऐप में (shop / custom).
     * patterns: अंदर के साँचे (regex, {} = खाली जगह). अपने बनाए साँचे में सादा "___" लिखा जाता है.
     */
    data class Template(
        val id: String, val name: String, val example: String, val patterns: List<String>, val slots: List<String>,
        val action: String, val app: String = "", val builtin: Boolean = true, val on: Boolean = true
    )

    data class Match(val t: Template, val slots: Map<String, String>) {
        operator fun get(k: String) = slots[k].orEmpty()
    }

    // ---- शब्दों के विकल्प (हिंदी + English + Hinglish)
    private const val WA = "(?:व्हाट्सएप|व्हाट्सऐप|वॉट्सऐप|वाट्सएप|वॉट्सएप|व्हाट्सअप|व्हाट्सप|whatsapp|whats app|watsapp)"
    private const val OPEN = "(?:ओपन करो|ओपन कर दो|ओपन कीजिए|ओपन करिए|ओपन|खोलो|खोल दो|खोलिए|खोलना|open karo|open kar do|open)"
    private const val MSG = "(?:मैसेज|मेसेज|मेसेज़|संदेश|message|msg)"
    private const val SEND = "(?:भेज दो|भेजो|भेज दीजिए|भेज दे|भेजिए|सेंड करो|सेंड कर दो|send karo|send kar do|send|bhej do|bhejo)"
    private const val SEARCH = "(?:सर्च करो|सर्च कर दो|सर्च कीजिए|सर्च|खोजो|ढूंढो|ढूँढो|ढूढो|search karo|search kar do|search)"
    private const val PLAY = "(?:चलाओ|चला दो|बजाओ|बजा दो|लगाओ|लगा दो|प्ले करो|प्ले कर दो|play karo|play kar do|play)"
    private const val CALL = "(?:कॉल करो|कॉल कर दो|कॉल लगाओ|कॉल लगा दो|फोन करो|फोन लगाओ|फ़ोन करो|फ़ोन लगाओ|call karo|call lagao|call)"
    private const val PAR = "(?:पर|पे|में|मे|par|pe|me)"
    private const val IG = "(?:इंस्टाग्राम|इंस्टा|इन्स्टाग्राम|instagram|insta)"
    private const val FB = "(?:फेसबुक|फ़ेसबुक|facebook|fb)"
    private const val YT = "(?:यूट्यूब|यूटूब|यू ट्यूब|youtube|you tube)"
    private const val MAP = "(?:मैप|मैप्स|गूगल मैप|गूगल मैप्स|map|maps|google map|google maps)"
    private const val CART = "(?:कार्ट|कार्ड|बास्केट|बैग|cart|basket|bag)"
    private const val ADD = "(?:में जोड़ो|में जोड़ दो|में डालो|में डाल दो|में ऐड करो|में add करो|me add karo|me dalo|में रखो)"
    private const val VOL = "(?:आवाज|आवाज़|वॉल्यूम|वोल्यूम|volume|sound|साउंड)"
    private const val KARO = "(?:करो|कर दो|कीजिए|karo|kar do)"

    /** दुकान वाले ऐप: id -> बोले जाने वाले नाम */
    val SHOPS: Map<String, List<String>> = linkedMapOf(
        "flipkart" to listOf("flipkart", "फ्लिपकार्ट", "फ़्लिपकार्ट", "फ्लिप कार्ट"),
        "meesho" to listOf("meesho", "मीशो", "मेसो", "मिशो"),
        "amazon" to listOf("amazon", "अमेज़न", "अमेजन", "अमेज़ॉन", "अमेजॉन"),
        "blinkit" to listOf("blinkit", "ब्लिंकिट", "ब्लिंकट", "ब्लिंक इट"),
        "zepto" to listOf("zepto", "ज़ेप्टो", "जेप्टो", "जैप्टो"),
        "instamart" to listOf("instamart", "इंस्टामार्ट", "इन्स्टामार्ट", "swiggy instamart", "स्विगी इंस्टामार्ट"),
        "swiggy" to listOf("swiggy", "स्विगी", "स्वीगी"),
        "zomato" to listOf("zomato", "ज़ोमैटो", "जोमैटो", "जोमाटो")
    )
    private val SHOP = "(" + SHOPS.values.flatten().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it).removePrefix("\\Q").removeSuffix("\\E") } + ")"

    /** अंदर के साँचे - ऊपर से नीचे (पहला मिला वही) */
    val BUILTIN: List<Template> = listOf(
        Template("wa_send", "WhatsApp पर मैसेज", "व्हाट्सएप ओपन करो ___ को ___ मैसेज भेज दो", listOf(
            "$WA(?: $OPEN)?(?: $PAR)? (.+?) को (.+?) $MSG $SEND",
            "$WA(?: $OPEN)?(?: $PAR)? (.+?) को $MSG $SEND (?:कि |की )?(.+)",
            "(.+?) को $WA $PAR (.+?) $MSG $SEND",
            "(.+?) को $WA $PAR $MSG $SEND (?:कि |की )?(.+)"
        ), listOf("नाम", "मैसेज"), "wa_send"),
        Template("wa_open", "WhatsApp खोलो", "व्हाट्सएप खोलो", listOf("$WA $OPEN", "$OPEN $WA"), emptyList(), "open_app", "whatsapp"),
        Template("call", "कॉल करो", "___ को कॉल करो", listOf("(.+?) को $CALL", "$CALL (.+)"), listOf("नाम"), "call"),
        Template("ig_search", "Instagram पर सर्च", "इंस्टाग्राम पर ___ सर्च करो", listOf("$IG $PAR (.+?) $SEARCH", "$IG $OPEN (?:और )?(.+?) $SEARCH"), listOf("खोज"), "app_search", "instagram"),
        Template("ig_open", "Instagram खोलो", "इंस्टाग्राम खोलो", listOf("$IG $OPEN", "$OPEN $IG"), emptyList(), "open_app", "instagram"),
        Template("fb_search", "Facebook पर सर्च", "फेसबुक पर ___ सर्च करो", listOf("$FB $PAR (.+?) $SEARCH", "$FB $OPEN (?:और )?(.+?) $SEARCH"), listOf("खोज"), "app_search", "facebook"),
        Template("fb_open", "Facebook खोलो", "फेसबुक खोलो", listOf("$FB $OPEN", "$OPEN $FB"), emptyList(), "open_app", "facebook"),
        Template("yt_play", "YouTube पर चलाओ", "यूट्यूब पर ___ चलाओ", listOf("$YT $PAR (.+?) $PLAY"), listOf("गाना/वीडियो"), "yt_play"),
        Template("yt_search", "YouTube पर सर्च", "यूट्यूब पर ___ सर्च करो", listOf("$YT $PAR (.+?) $SEARCH"), listOf("खोज"), "yt_search"),
        Template("map_go", "मैप पर रास्ता", "मैप खोलो ___ जाना है", listOf(
            "$MAP $OPEN (?:मुझे )?(.+?) (?:जाना है|जाना हे|जाना|चलना है)",
            "(?:मुझे )?(.+?) (?:का|की|के) रास्ता (?:बताओ|दिखाओ|बता दो|दिखा दो)",
            "(?:मुझे )?(.+?) (?:जाना है|जाना हे) (?:रास्ता बताओ|$MAP $OPEN)"
        ), listOf("जगह"), "navigate"),
        Template("map_dist", "कितनी दूर है", "___ कितनी दूर है", listOf("(?:यहाँ से |यहां से )?(.+?) (?:कितनी|कितना|कितने) (?:दूर|किलोमीटर) (?:है|हे|पड़ेगा|पड़ता है)"), listOf("जगह"), "distance"),
        Template("alarm", "अलार्म लगाओ", "___ बजे का अलार्म लगाओ", listOf(
            "(.+?) (?:बजे|baje) (?:का|के लिए|ka) (?:अलार्म|alarm) (?:लगाओ|लगा दो|सेट करो|सेट कर दो|lagao|set karo)",
            "(?:अलार्म|alarm) (?:लगाओ|लगा दो|सेट करो|सेट कर दो|lagao|set karo) (.+?) (?:बजे|baje)(?: का)?"
        ), listOf("समय"), "alarm"),
        Template("timer", "टाइमर लगाओ", "___ मिनट का टाइमर लगाओ", listOf(
            "(.+?) (?:मिनट|मिनिट|minute|minutes|min) (?:का|के लिए|ka) (?:टाइमर|timer) (?:लगाओ|लगा दो|सेट करो|सेट कर दो|lagao|set karo)",
            "(?:टाइमर|timer) (?:लगाओ|लगा दो|सेट करो|lagao) (.+?) (?:मिनट|मिनिट|minute|minutes|min)(?: का)?"
        ), listOf("मिनट"), "timer"),
        Template("shop_search", "दुकान पर सर्च", "___ पर ___ सर्च करो", listOf("$SHOP $PAR (.+?) $SEARCH", "$SHOP $OPEN (?:और )?(.+?) $SEARCH"), listOf("ऐप", "सामान"), "shop_search"),
        Template("cart_add_this", "इसे कार्ट में जोड़ो", "इसे कार्ट में जोड़ो", listOf("(?:इसे|इसको|ये|यह|isko|ise) $CART $ADD"), emptyList(), "cart_add"),
        Template("cart_add", "___ को कार्ट में डालो", "___ को कार्ट में डालो", listOf("(.+?) (?:को )?$CART $ADD"), listOf("सामान"), "cart_add"),
        Template("cart_open", "कार्ट खोलो", "कार्ट खोलो", listOf("(?:$SHOP (?:का|की|ka) )?$CART $OPEN", "(?:$SHOP (?:का|की|ka) )?$CART (?:दिखाओ|दिखा दो)"), emptyList(), "cart_open"),
        Template("scroll_down", "नीचे स्क्रॉल", "नीचे स्क्रॉल करो", listOf("(?:नीचे|niche) (?:स्क्रॉल|स्क्रोल|scroll) $KARO", "(?:स्क्रॉल|scroll) (?:डाउन|down)(?: $KARO)?", "नीचे जाओ"), emptyList(), "scroll_down"),
        Template("scroll_up", "ऊपर स्क्रॉल", "ऊपर स्क्रॉल करो", listOf("(?:ऊपर|upar) (?:स्क्रॉल|स्क्रोल|scroll) $KARO", "(?:स्क्रॉल|scroll) (?:अप|up)(?: $KARO)?", "ऊपर जाओ"), emptyList(), "scroll_up"),
        Template("back", "पीछे जाओ", "पीछे जाओ", listOf("(?:पीछे जाओ|पीछे चलो|बैक|back|बैक करो|back karo|वापस जाओ)"), emptyList(), "back"),
        Template("home", "होम पर आओ", "होम पर आओ", listOf("(?:होम|home) (?:पर|पे|par|pe) (?:आओ|जाओ|चलो|aao|jao)", "(?:होम स्क्रीन|home screen)(?: $OPEN| पर जाओ)?"), emptyList(), "home"),
        Template("vol_up", "आवाज़ बढ़ाओ", "आवाज़ बढ़ाओ", listOf("$VOL (?:बढ़ाओ|बढ़ा दो|ज्यादा करो|ज़्यादा करो|तेज करो|तेज़ करो|ऊंची करो|ऊँची करो|अप करो|up karo|badhao|up)"), emptyList(), "vol_up"),
        Template("vol_down", "आवाज़ कम करो", "आवाज़ कम करो", listOf("$VOL (?:कम करो|कम कर दो|घटाओ|धीमी करो|धीरे करो|डाउन करो|down karo|kam karo|down)"), emptyList(), "vol_down"),
        Template("vol_set", "आवाज़ ___ करो", "आवाज़ ___ करो", listOf("$VOL (\\d{1,3}|[^\\s]+) (?:प्रतिशत |परसेंट |percent |% )?$KARO"), listOf("स्तर"), "vol_set"),
        Template("call_answer", "कॉल उठाओ (स्पीकर चालू)", "उठाओ / कॉल उठाओ", listOf("(?:कॉल |फोन |फ़ोन )?(?:उठाओ|उठा लो|उठा दो|रिसीव करो)", "(?:call )?(?:uthao|utha lo|receive karo|pick karo)"), emptyList(), "call_answer"),
        Template("call_end", "कॉल काटो", "काट दो / कट करो", listOf("(?:कॉल |फोन |फ़ोन )?(?:काट दो|काटो|कट करो|कट कर दो|रख दो)", "(?:call )?(?:kaat do|cut karo|cut kar do)"), emptyList(), "call_end")
    )

    // ------------------------------------------------------------------ मिलान
    private val DEV_DIGITS = "०१२३४५६७८९"

    /** क़ ख़ ग़ ज़ ड़ ढ़ फ़ य़ (एक अक्षर वाले) -> अक्षर + नुक़्ता, ताकि दोनों तरह लिखा मिल जाए */
    private const val PRE = "\u0958\u0959\u095A\u095B\u095C\u095D\u095E\u095F"
    private const val BASE = "\u0915\u0916\u0917\u091C\u0921\u0922\u092B\u092F"
    fun decompose(s: String): String = buildString { for (c in s) { val i = PRE.indexOf(c); if (i >= 0) append(BASE[i]).append('\u093C') else append(c) } }

    /** छोटे-मोटे फ़र्क मिटाओ: अंक, फ़ालतू चिह्न, "DK/प्लीज़" जैसे शुरू-अंत के शब्द */
    fun clean(s: String): String {
        var t = decompose(s).map { c -> DEV_DIGITS.indexOf(c).let { if (it >= 0) '0' + it else c } }.joinToString("")
        t = t.replace(Regex("[!?।|\"“”]+"), " ").replace(Regex("[.]+(\\s|$)"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(?:(?:डीके|dk|दीके|हे डीके|ओ डीके|प्लीज़|प्लीज|please|जरा|ज़रा|zara|भाई|अरे)[\\s,]+)+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("[\\s,]+(?:प्लीज़|प्लीज|please|जी|ना|न|यार|भाई)$", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', ',')
    }

    /** साँचे के देवनागरी अक्षर: नुक़्ता हो या न हो, ँ/ं दोनों चलें; खाली जगह = कोई भी स्पेस / कॉमा */
    private fun tolerant(p: String): String {
        val sb = StringBuilder()
        for (c in decompose(p).replace("\u093C", "")) {
            when {
                c == 'ँ' || c == 'ं' -> sb.append("[ँं]")
                c == ' ' -> sb.append("[\\s,]+")
                c in '\u0915'..'\u0939' -> sb.append(c).append("\u093C?")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** अपने बनाए साँचे: सादा लिखा + ___ खाली जगह -> regex */
    fun userPattern(simple: String): String =
        simple.trim().split(Regex("_{2,}")).joinToString("(.+?)") { part -> part.replace(Regex("[\\\\^$.|?*+()\\[\\]{}]"), "") }

    private val cache = HashMap<String, Regex>()
    private fun rx(p: String): Regex = synchronized(cache) {
        cache.getOrPut(p) { Regex("^(?:" + tolerant(p) + ")$", setOf(RegexOption.IGNORE_CASE)) }
    }

    /** पहला मिला साँचा (बंद साँचे छोड़कर). null = कोई नहीं */
    fun match(text: String, list: List<Template>): Match? {
        val t = clean(text)
        if (t.isEmpty() || t.length > 300) return null
        for (tpl in list) {
            if (!tpl.on) continue
            for (p in tpl.patterns) {
                val m = try { rx(p).find(t) } catch (_: Exception) { null } ?: continue
                val vals = m.groupValues.drop(1).map { slotClean(it) }
                if (tpl.slots.isNotEmpty() && vals.take(tpl.slots.size).any { it.isBlank() }) continue
                val map = LinkedHashMap<String, String>()
                if (tpl.action == "shop_search") {
                    map["app"] = shopId(vals.getOrElse(0) { "" }) ?: continue
                    map["q"] = vals.getOrElse(1) { "" }
                } else if (tpl.action == "cart_open") {
                    map["app"] = shopId(vals.getOrElse(0) { "" }).orEmpty()
                } else {
                    tpl.slots.forEachIndexed { i, _ -> map[KEYS.getOrElse(i) { "s$i" }] = vals.getOrElse(i) { "" } }
                }
                if (!slotsOk(tpl, map)) continue
                return Match(tpl, map)
            }
        }
        return null
    }

    /** सब साँचों में पहला slot = a, दूसरा = b */
    private val KEYS = listOf("a", "b", "c", "d")

    private fun slotClean(s: String) = s.trim(' ', ',', '"', '\'').removeSuffix(" को").removeSuffix(" का").trim()

    /** गलत मिलान से बचाव (जैसे "आज कितनी दूर है" या 30 शब्दों का नाम) */
    private fun slotsOk(t: Template, m: Map<String, String>): Boolean = when (t.action) {
        "wa_send" -> m["a"].orEmpty().split(' ').size <= 5 && m["b"].orEmpty().isNotBlank()
        "call" -> m["a"].orEmpty().split(' ').size <= 5 && !Regex("(व्हाट्सएप|whatsapp|वीडियो|video)", RegexOption.IGNORE_CASE).containsMatchIn(m["a"].orEmpty())
        "alarm" -> TemplateTime.parseClock(m["a"].orEmpty(), 0, 0) != null
        "timer" -> TemplateTime.minutes(m["a"].orEmpty()) != null
        "vol_set" -> TemplateTime.number(m["a"].orEmpty())?.let { it in 0..100 } == true
        "cart_add" -> m["a"].orEmpty().split(' ').size <= 8
        "map_dist", "navigate" -> m["a"].orEmpty().length in 2..80
        else -> true
    }

    fun shopId(spoken: String): String? {
        val s = spoken.lowercase().trim()
        if (s.isBlank()) return null
        return SHOPS.entries.firstOrNull { (_, names) -> names.any { it.lowercase().replace("\u093C", "") == s.replace("\u093C", "") } }?.key
    }
}

/** समय / गिनती समझना (शुद्ध, test होता है) */
object TemplateTime {
    private val WORDS = mapOf(
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "छे" to 6, "सात" to 7, "आठ" to 8,
        "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "पंद्रह" to 15, "बीस" to 20, "पच्चीस" to 25, "तीस" to 30,
        "चालीस" to 40, "पैंतालीस" to 45, "पचास" to 50, "साठ" to 60, "सौ" to 100,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )

    fun number(s: String): Int? {
        val t = s.trim().lowercase()
        t.toIntOrNull()?.let { return it }
        return WORDS[t]
    }

    /** "5", "पांच", "आधा घंटा", "1 घंटा", "डेढ़" -> मिनट */
    fun minutes(s: String): Int? {
        val t = TemplateCommands.decompose(s).trim().lowercase().replace("\u093C", "")
        if (t.isBlank()) return null
        Regex("^(\\d{1,3})$").find(t)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 1..600 } }
        return number(t)?.takeIf { it in 1..600 }
    }

    data class Clock(val hour: Int, val minute: Int) {
        /** बोलने लायक: "सुबह 7:00 बजे" */
        fun spoken(): String {
            val part = when (hour) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            return "$part $h12:${minute.toString().padStart(2, '0')} बजे"
        }
    }

    /**
     * "सुबह 7", "शाम 6:30", "साढ़े सात", "सवा 8", "पौने 9", "रात 10", "7 am", "19:45".
     * सुबह/शाम न बोला हो तो: अभी के बाद जो पहले आए (now = अभी का घंटा / मिनट).
     */
    fun parseClock(s0: String, nowH: Int, nowM: Int): Clock? {
        var s = TemplateCommands.decompose(s0).lowercase().replace("\u093C", "").replace("ँ", "ं").trim()
        val part = when {
            Regex("(सुबह|सवेरे|सुभा|morning|\\bam\\b|a\\.m)").containsMatchIn(s) -> "am"
            Regex("(दोपहर|afternoon)").containsMatchIn(s) -> "noon"
            Regex("(शाम|evening|\\bpm\\b|p\\.m)").containsMatchIn(s) -> "pm"
            Regex("(रात|night)").containsMatchIn(s) -> "night"
            else -> ""
        }
        s = s.replace(Regex("(सुबह|सवेरे|सुभा|morning|\\bam\\b|a\\.m\\.?|दोपहर|afternoon|शाम|evening|\\bpm\\b|p\\.m\\.?|रात|night|को|के|का|में|बजे|baje)"), " ")
            .replace(Regex("\\s+"), " ").trim()
        var h: Int; var m = 0
        when {
            s == "डेढ" -> { h = 1; m = 30 }
            s == "ढाई" -> { h = 2; m = 30 }
            s.startsWith("साढे ") -> { h = number(s.removePrefix("साढे ").trim()) ?: return null; m = 30 }
            s.startsWith("सवा ") -> { h = number(s.removePrefix("सवा ").trim()) ?: return null; m = 15 }
            s.startsWith("पौने ") -> { h = (number(s.removePrefix("पौने ").trim()) ?: return null) - 1; m = 45; if (h == 0) h = 12 }
            else -> {
                val d = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?$").find(s)
                if (d != null) { h = d.groupValues[1].toInt(); m = d.groupValues[2].toIntOrNull() ?: 0 }
                else h = number(s) ?: return null
            }
        }
        if (h !in 0..23 || m !in 0..59) return null
        if (h > 12) return if (part == "am") null else Clock(h, m)
        val hh = when (part) {
            "am" -> if (h == 12) 0 else h
            "noon" -> if (h == 12) 12 else if (h <= 5) h + 12 else h
            "pm" -> if (h == 12) 12 else h + 12
            "night" -> when { h == 12 -> 0; h <= 4 -> h; else -> h + 12 }
            else -> {
                val a = if (h == 12) 0 else h
                val now = nowH * 60 + nowM
                if (a * 60 + m > now) a else if ((a + 12) * 60 + m > now) a + 12 else a
            }
        }
        return Clock(hh % 24, m)
    }
}
