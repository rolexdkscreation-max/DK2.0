package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.33: Local-first कमांड रूटर - पहले फ़ोन पर ही समझो, AI को सिर्फ़ तब जब सच में ज़रूरत हो.
 *
 * जड़ (पहले): QuickCommands "कॉल / सर्च / और / फिर / cart / delete" वाली हर बात को null करता था, इसलिए
 * "राहुल को कॉल करो", "YouTube पर X सर्च करो", "Flipkart खोलो और X सर्च करो", "youtube kholo" (Hinglish),
 * "Hey DK, open YouTube" ("hey" बचा रह जाता था) - सब AI (Gemini/OpenAI…) को जाते थे.
 *
 * अब: (1) QuickCommands जैसा था वैसा ही पहले (कोई पुराना रास्ता नहीं टूटता);
 * (2) नए सीधे काम: कॉन्टैक्ट को कॉल, कॉल लिस्ट, डायलर, सबसे नई फ़ोटो, YouTube सर्च, गाना/वीडियो चलाओ, शॉपिंग ऐप में सर्च,
 *     Hinglish "kholo / open karo";
 * (3) कई-कदम वाली बात ("खोलो, सर्च करो, फिर चलाओ") को कदमों में तोड़कर - सब कदम पक्के हों तो पूरा फ़ोन पर, कुछ बचे तो
 *     पहले हिस्सा फ़ोन पर और बाकी (जैसे "पहला प्रोडक्ट कार्ट में डालो" - स्क्रीन समझनी पड़ती है) AI को.
 *
 * सिर्फ़ तय (structured) action types बनते हैं - कोई shell / code नहीं. Android import नहीं -> JVM unit test.
 */
object LocalRouter {
    /** local = फ़ोन पर तुरंत करने वाला काम; rest = AI को देने वाली पूरी बात (null = कुछ नहीं बचा) */
    data class Split(val local: QuickCommands.Quick?, val rest: String?)

    /** AI को note: यह हिस्सा फ़ोन पर हो चुका - AgentRunner दोबारा local रूट नहीं करता */
    const val DONE_NOTE = "ALREADY DONE ON THE PHONE (locally, do not repeat):"

    const val YT_PKG = "com.google.android.youtube"

    private fun a(type: String, vararg kv: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("type", type); kv.forEach { o.put(it.first, it.second) }; return o
    }

    val SHOPS = linkedMapOf(
        "flipkart" to listOf("flipkart", "फ्लिपकार्ट", "फ़्लिपकार्ट", "फ्लिप कार्ट"),
        "amazon" to listOf("amazon", "अमेज़न", "अमेजन", "अमेज़ॉन", "अमेजॉन"),
        "meesho" to listOf("meesho", "मीशो", "मेसो", "मिशो"),
        "myntra" to listOf("myntra", "मिंत्रा", "मंत्रा"),
        "ajio" to listOf("ajio", "अजियो"),
        "blinkit" to listOf("blinkit", "ब्लिंकिट", "ब्लिंकट", "ब्लिंक इट"),
        "zepto" to listOf("zepto", "ज़ेप्टो", "जेप्टो", "जैप्टो"),
        "bigbasket" to listOf("bigbasket", "big basket", "बिगबास्केट", "बिग बास्केट"),
        "jiomart" to listOf("jiomart", "jio mart", "जियोमार्ट", "जियो मार्ट")
    )
    private val YT = listOf("यूट्यूब", "यू ट्यूब", "youtube", "you tube", "यूटुब")
    private val SEARCH = listOf("सर्च", "search", "ढूंढ", "ढूँढ", "खोजो", "खोजिए", "खोज", "dhundo", "dhoondo", "dhundho", "khojo", "find", "look for", "look up")
    private val PLAY = listOf("play", "प्ले", "चलाओ", "चला दो", "चलाइए", "चलाना", "चला", "बजाओ", "बजा दो", "बजा", "सुनाओ", "सुना दो",
        "लगाओ", "लगा दो", "chalao", "chala do", "bajao", "sunao", "lagao", "laga do")
    private val SONG = listOf("song", "गाना", "गाने", "गीत", "video", "वीडियो", "bhajan", "भजन", "gaana", "gana", "gaane", "gane",
        "music", "म्यूजिक", "म्यूज़िक", "trailer", "ट्रेलर")
    /** इन ऐप का नाम हो तो "गाना चलाओ" YouTube पर नहीं (AI / वही ऐप) */
    private val OTHER_MEDIA = listOf("spotify", "स्पॉटिफाई", "gaana app", "jiosaavn", "saavn", "सावन", "wynk", "विंक", "instagram", "इंस्टाग्राम", "reels", "रील")
    private val NEG = listOf(" मत ", " mat ", "don't", "dont ", "do not", "नहीं करना", "nahi karna", "मत करो", "ना करो", "never ")
    private val GREET = listOf("hey", "hi", "hello", "ok", "okay", "हे", "हेय", "हाय", "हेलो", "ओके", "सुनो", "suno")
    private val FILLER = listOf("प्लीज़", "please", "प्लीज", "ज़रा", "जरा", "जल्दी", "तुरंत", "pls", "plz")
    /** ये सिर्फ़ शुरू/आख़िर में हटाओ - बीच में हों तो नाम का हिस्सा हो सकते हैं ("पापा जी को कॉल करो") */
    private val EDGE_FILLER = listOf("भाई", "यार", "जी", "bhai", "yaar", "ji")

    private val SPLITTER = Regex("\\s*(?:,|।|;|\\band then\\b|\\bthen\\b|\\band\\b|\\bphir\\b|\\baur\\b|\\bafter that\\b| और | फिर | उसके बाद | इसके बाद )\\s*")

    /** बोली बात को साफ़: छोटे अक्षर, wake word / "hey" / filler हटाओ */
    fun normalize(raw: String, wakeWords: List<String>): String {
        var t = " " + raw.lowercase().trim().trim('.', '।', '!', '?', ',') + " "
        wakeWords.sortedByDescending { it.length }.forEach { w ->
            if (w.length >= 2) t = t.replace(" $w ", " ").replace(" $w,", " ").replace(",$w ", " ")
        }
        FILLER.forEach { t = t.replace(" $it ", " ") }
        t = t.replace(Regex("\\s+"), " ").trim().trim(',', ' ')
        // आगे का "hey / ok / सुनो" (wake word के बाद "hey" बचता था -> "hey open youtube" किसी से नहीं मिलता था)
        var changed = true
        while (changed) {
            changed = false
            for (g in GREET + EDGE_FILLER) if (t == g || t.startsWith("$g ") || t.startsWith("$g,")) { t = t.substring(g.length).trim(' ', ','); changed = true }
            for (g in EDGE_FILLER) if (t.endsWith(" $g")) { t = t.dropLast(g.length + 1).trim(' ', ','); changed = true }
        }
        return t.trim('.', '।', '!', '?', ',', ' ')
    }

    private fun hasAny(t: String, w: List<String>) = w.any { t.contains(it) }
    private fun padded(t: String) = " $t "
    /** पूरे शब्द ("it" को "arijit" में नहीं ढूँढना) */
    private fun hasWord(t: String, w: List<String>): Boolean {
        val ws = t.split(Regex("[\\s,.!?।]+")).toSet()
        return w.any { it in ws }
    }
    fun negated(t: String) = NEG.any { padded(t).contains(it) }

    /** पूरा रास्ता: फ़ोन पर कितना हो सकता है + AI को क्या बचा */
    fun split(raw: String, wakeWords: List<String>, fg: String = ""): Split {
        val t = normalize(raw, wakeWords)
        if (t.length < 2 || negated(t)) return Split(null, raw)
        val clauses = clausesOf(t)
        if (clauses.size >= 2) plan(clauses, wakeWords, fg)?.let { return it.copy(rest = it.rest?.let { raw }) }
        single(t, wakeWords, fg)?.let { return Split(it, null) }
        return Split(null, raw)
    }

    /** पूरी बात फ़ोन पर ही हो सकती है? (हाँ = वह काम, नहीं = null) */
    fun route(raw: String, wakeWords: List<String>, fg: String = ""): QuickCommands.Quick? =
        split(raw, wakeWords, fg).takeIf { it.rest == null }?.local

    /** काम वाले शब्द - जिस टुकड़े में इनमें से कुछ नहीं, वह अलग कदम नहीं ("tom and jerry" = एक ही नाम) */
    private val VERBS = SEARCH + PLAY + listOf("open", "खोल", "khol", "launch", "ओपन", "call", "कॉल", "फोन", "फ़ोन", "phone", "dial",
        "show", "दिखा", "dikha", "add", "डाल", "जोड़", "cart", "कार्ट", "जाओ", "भेज", "send", "type", "टाइप", "लिख", "click", "क्लिक",
        "tap", "दबा", "बंद", "close", "back", "वापस", "scroll", "स्क्रॉल", "select", "चुन", "buy", "खरीद", "order", "ऑर्डर", "बढ़ा",
        "turn", "जला", "बुझा", "चालू", "ऑन", "ऑफ", "start", "stop", "रोको", "pause", "delete", "डिलीट", "मिटा", "share", "शेयर",
        "message", "मैसेज", "बता", "tell", "read", "पढ़", "kholo", "karo", "करो", "कर दो")

    /** "और / फिर / , / then" पर टुकड़े; बिना काम-शब्द वाला टुकड़ा पिछले में जोड़ो (अपने जोड़ने वाले शब्द के साथ) */
    fun clausesOf(t: String): List<String> {
        val parts = mutableListOf<String>()
        var last = 0
        var delim = ""
        fun add(piece: String) {
            val p = piece.trim()
            if (p.isEmpty()) return
            if (parts.isNotEmpty() && !hasAny(p, VERBS) && delim.isNotBlank() && delim.trim() !in listOf(",", "।", ";"))
                parts[parts.size - 1] = parts.last() + " " + delim.trim() + " " + p
            else parts.add(p)
        }
        for (m in SPLITTER.findAll(t)) {
            add(t.substring(last, m.range.first)); delim = m.value; last = m.range.last + 1
        }
        add(t.substring(last.coerceAtMost(t.length)))
        return parts
    }

    // ------------------------------------------------------------------ एक कदम

    fun single(t: String, wakeWords: List<String>, fg: String = ""): QuickCommands.Quick? {
        if (t.length < 2 || negated(t)) return null
        // 1) पुराना QuickCommands जैसा था (ऐप खोलो, टॉर्च, आवाज़, YouTube पर चलाओ, कॉल उठाओ…)
        QuickCommands.parse(t, wakeWords)?.let { return it }
        // 2) नए सीधे काम
        if (hasAny(noShop(t), listOf("कार्ट", "cart", "basket", "बास्केट", "buy", "खरीद", "order", "ऑर्डर", "पेमेंट", "payment", "pay "))) return null
        if (hasAny(t, listOf("डिलीट", "delete", "मिटा", "remove", "हटाओ", "हटा दो"))) return null   // मिटाना = पक्की पहचान + पुष्टि (AI/user)
        callLog(t)?.let { return it }
        openVerb(t)?.let { return it }
        callContact(t)?.let { return it }
        latestPhoto(t)?.let { return it }
        shopSearch(t)?.let { return it }
        youtube(t, fg)?.let { return it }
        return null
    }

    private val CALLW = listOf("कॉल", "call", "calls", "कॉल्स", "फोन", "फ़ोन")
    private fun callLog(t: String): QuickCommands.Quick? {
        if (!hasAny(t, CALLW)) return null
        val logW = listOf("recent", "रीसेंट", "history", "हिस्ट्री", "log", "लॉग", "missed", "मिस्ड", "पिछली कॉल", "पिछले कॉल",
            "last calls", "latest calls", "कॉल लिस्ट", "call list", "कौन कौन", "किस किस")
        return if (hasAny(t, logW)) QuickCommands.Quick(a("call_log"), "कॉल लिस्ट खोल दी") else null
    }

    /** Hinglish / English "<app> kholo", "open karo <app>", "launch <app>", "the/my" के साथ */
    private val OPEN_TAIL = Regex("^(?:open\\s+)?(?:the\\s+|my\\s+)?(.+?)(?:\\s+(?:app|ऐप|एप|application))?\\s+(?:kholo|khol do|kholiye|kholna|khol|open karo|open kar do|open kardo|open kro|open kr do|open krdo|chalu karo|chalu kar do|start karo|launch karo|launch kar do)$")
    private val OPEN_HEAD = Regex("^(?:open|launch|ओपन)\\s+(?:the\\s+|my\\s+|मेरा\\s+|मेरे\\s+)?(.+?)(?:\\s+(?:app|application|ऐप))?$")
    private fun openVerb(t: String): QuickCommands.Quick? {
        val m = OPEN_TAIL.find(t) ?: OPEN_HEAD.find(t) ?: return null
        val app = m.groupValues[1].trim()
        if (app.isBlank() || app.split(' ').size > 3) return null
        if (listOf(" पर ", " में ", " को ", " से ", " on ", " in ", " par ", " me ").any { padded(app).contains(it) }) return null
        if (hasAny(app, SEARCH) || hasAny(app, CALLW.filter { it != "फोन" && it != "फ़ोन" })) return null
        return QuickCommands.Quick(a("open_app", "name" to app), "")
    }

    private val CALL_A = Regex("^(?:मेरे |मेरी |मेरा |my )?(.+?)\\s+(?:को|ko|se|से)\\s+(?:कॉल|call|फोन|फ़ोन|phone|fone)(?:\\s+(?:करो|कर दो|करिए|कीजिए|करना|लगाओ|लगा दो|मिलाओ|मिला दो|karo|kar do|kardo|kro|karna|lagao|laga do|milao|kar))?$")
    private val CALL_B = Regex("^(?:कॉल करो|कॉल कर दो|फोन लगाओ|फ़ोन लगाओ|call karo|call|phone|dial|ring|make a call to|place a call to|give a call to)\\s+(?:to\\s+)?(?:my\\s+|मेरे\\s+|मेरी\\s+)?(.+?)(?:\\s+(?:now|right now|अभी|ko|को))?$")
    private val NOT_A_NAME = listOf("this", "that", "it", "him", "her", "back", "log", "history", "recent", "cut", "end", "answer", "receive",
        "reject", "uthao", "utha", "kaat", "kato", "speaker", "waiting", "इस", "उस", "इसे", "उसे", "यह", "ये", "वो", "वह", "इन", "उन",
        "वापस", "बैक", "उठा", "काट", "कट", "स्पीकर", "app", "ऐप", "open", "ओपन", "kholo", "खोलो", "dialer", "डायलर", "settings", "सेटिंग", "मेरे", "मेरी", "मेरा", "my")
    private fun callContact(t: String): QuickCommands.Quick? {
        if (!hasAny(t, CALLW + listOf("dial", "ring"))) return null
        val m = CALL_A.find(t) ?: CALL_B.find(t) ?: return null
        val name = m.groupValues[1].trim().trim(',', '.')
        if (name.length < 2 || name.split(' ').size > 4) return null
        val ws = name.split(' ')
        if (ws.any { it in NOT_A_NAME }) return null
        return QuickCommands.Quick(a("call_contact", "name" to name), "")
    }

    private val PHOTO = listOf("photo", "फोटो", "फ़ोटो", "तस्वीर", "picture", "pic", "image", "इमेज", "selfie", "सेल्फी", "सेल्फ़ी")
    private val LATEST = listOf("latest", "last", "recent", "newest", "लेटेस्ट", "आखिरी", "आख़िरी", "अंतिम", "नई", "नया", "नयी", "ताज़ा",
        "ताजा", "सबसे नई", "abhi wali", "pichli", "पिछली", "last wali", "new")
    private fun latestPhoto(t: String): QuickCommands.Quick? {
        if (!hasAny(t, PHOTO)) return null
        if (hasAny(t, listOf("भेज", "send", "share", "शेयर", "edit", "एडिट", "खींच", "ले लो", "click", "क्लिक", "camera", "कैमरा"))) return null
        if (hasAny(t, LATEST)) return QuickCommands.Quick(a("gallery_latest"), "")
        if (hasAny(t, listOf("show", "दिखा", "dikha", "open", "खोल", "khol", "देख"))) return QuickCommands.Quick(a("open_app", "name" to "gallery"), "")
        return null
    }

    /** "फ्लिपकार्ट" में "कार्ट" छिपा है - दुकान का नाम हटाकर कार्ट/खरीद ढूँढो */
    private fun noShop(t: String): String { var x = t; SHOPS.values.flatten().sortedByDescending { it.length }.forEach { x = x.replace(it, " ") }; return x }

    fun shopOf(t: String): String? = SHOPS.entries.firstOrNull { (_, w) -> w.any { t.contains(it) } }?.key

    private val PARTICLES = Regex("(?:^|\\s)(?:पर|पे|में|मे|मैं|me|par|pe|on|in|for|को|ko|के लिए|ke liye|से|se|the|a|an|for me|करो|कर दो|करिए|कीजिए|करना|करके|karo|kar do|kardo|kar ke|karke|kro|app|ऐप|एप)(?=\\s|$)")
    private fun clean(t: String, remove: List<String>): String {
        var q = " $t "
        remove.sortedByDescending { it.length }.forEach { q = q.replace(it, " ") }
        q = q.replace(Regex("\\s+"), " ")
        repeat(3) { q = q.replace(PARTICLES, " ") }
        return q.replace(Regex("\\s+"), " ").trim().trim(',', '.', '-')
    }

    private fun shopSearch(t: String): QuickCommands.Quick? {
        val shop = shopOf(t) ?: return null
        if (!hasAny(t, SEARCH + listOf("दिखाओ", "dikhao", "show me"))) return null
        val q = clean(t, SHOPS.getValue(shop) + SEARCH + listOf("दिखाओ", "dikhao", "show me", "open", "खोलो", "kholo", "ओपन"))
        if (q.length < 2) return QuickCommands.Quick(a("open_app", "name" to shop), "")
        return QuickCommands.Quick(a("shop_search", "app" to shop, "query" to q), "")
    }

    private fun youtube(t: String, fg: String): QuickCommands.Quick? {
        val yt = hasAny(t, YT)
        val search = hasAny(t, SEARCH)
        val play = hasAny(t, PLAY)
        if (hasAny(t, OTHER_MEDIA) || (shopOf(t) != null)) return null
        // YouTube का नाम + सर्च (और "चलाओ" भी हो तो सीधे पहला वीडियो)
        if (yt && search) {
            val q = clean(t, YT + SEARCH + PLAY + listOf("open", "खोलो", "kholo"))
            if (q.length < 2) return QuickCommands.Quick(a("open_app", "name" to "youtube"), "")
            return if (play) QuickCommands.Quick(a("youtube_play", "query" to q), "")
            else QuickCommands.Quick(a("youtube_search", "query" to q), "YouTube पर खोज दिया")
        }
        // ऐप का नाम नहीं, पर "गाना / वीडियो चलाओ" -> YouTube पर पहला वीडियो
        if (play && hasAny(t, SONG)) {
            val q = clean(t, PLAY + YT)
            if (q.length >= 3 && !hasWord(q, listOf("this", "यह", "ये", "इसे", "वो", "that", "it", "first", "पहला"))) return QuickCommands.Quick(a("youtube_play", "query" to q), "")
        }
        // YouTube सामने खुला है और "X सर्च करो" -> उसी में खोजो
        if (fg == YT_PKG && search) {
            val q = clean(t, SEARCH + YT)
            if (q.length >= 2) return QuickCommands.Quick(a("youtube_search", "query" to q), "YouTube पर खोज दिया")
        }
        return null
    }

    // ------------------------------------------------------------------ कई कदम

    private fun groupOfApp(name: String): String? = when {
        hasAny(name, YT) -> "youtube"
        shopOf(name) != null -> shopOf(name)
        hasAny(name, listOf("gallery", "गैलरी", "photos", "फोटोज़", "फोटो", "फ़ोटो", "album")) -> "gallery"
        else -> null
    }

    private fun refersBack(c: String) = hasWord(c, listOf("first", "पहला", "पहले", "पहली", "it", "this", "that", "उसे", "इसे", "वो", "यह", "ये",
        "top", "result", "रिजल्ट", "video", "वीडियो"))

    private fun plan(clauses: List<String>, wakeWords: List<String>, fg: String): Split? {
        val steps = mutableListOf<QuickCommands.Quick>()
        var ctx: String? = if (fg == YT_PKG) "youtube" else null
        var lastQuery: String? = null
        var leftover = false
        for (c in clauses) {
            val prev = steps.lastOrNull()
            val prevType = prev?.action?.optString("type")
            var q: QuickCommands.Quick? = null
            var replacePrev = false
            when {
                ctx == "youtube" && hasAny(c, SEARCH) -> {
                    val s = clean(c, SEARCH + YT + PLAY)
                    if (s.length >= 2) { lastQuery = s; q = QuickCommands.Quick(a("youtube_search", "query" to s), "YouTube पर खोज दिया"); replacePrev = prevType == "open_app" }
                }
                ctx == "youtube" && hasAny(c, PLAY) && lastQuery != null && refersBack(c) -> {
                    q = QuickCommands.Quick(a("youtube_play", "query" to lastQuery!!), ""); replacePrev = prevType == "youtube_search"
                }
                ctx == "youtube" && hasAny(c, PLAY) && lastQuery == null -> {
                    val s = clean(c, PLAY + YT)
                    if (s.length >= 2 && !refersBack(s)) { q = QuickCommands.Quick(a("youtube_play", "query" to s), ""); replacePrev = prevType == "open_app" }
                }
                ctx != null && SHOPS.containsKey(ctx) && hasAny(c, SEARCH) -> {
                    val s = clean(c, SEARCH + SHOPS.getValue(ctx!!))
                    if (s.length >= 2) { q = QuickCommands.Quick(a("shop_search", "app" to ctx!!, "query" to s), ""); replacePrev = prevType == "open_app" }
                }
                ctx == "gallery" && hasAny(c, PHOTO) && hasAny(c, LATEST) -> {
                    q = QuickCommands.Quick(a("gallery_latest"), ""); replacePrev = prevType == "open_app"
                }
            }
            if (q == null) q = single(c, wakeWords, if (ctx == "youtube") YT_PKG else "")
            if (q == null) { leftover = true; break }
            val type = q.action.optString("type")
            if (type == "youtube_search") lastQuery = q.action.optString("query")
            if (type == "youtube_play" && prevType == "youtube_search") replacePrev = true
            if (replacePrev && steps.isNotEmpty()) steps.removeAt(steps.size - 1)
            steps.add(q)
            ctx = when (type) {
                "open_app" -> groupOfApp(q.action.optString("name"))
                "youtube_search", "youtube_play" -> "youtube"
                "shop_search" -> q.action.optString("app")
                "gallery_latest" -> "gallery"
                else -> ctx
            }
        }
        if (steps.isEmpty()) return null
        // कॉल जैसा (वापस न होने वाला) काम हो और बाकी बात AI को जानी हो -> आधा काम नहीं, पूरा AI को
        if (leftover && steps.any { it.action.optString("type") == "call_contact" }) return null
        val local = if (steps.size == 1) steps[0] else {
            val arr = JSONArray(); steps.forEach { arr.put(it.action) }
            QuickCommands.Quick(a("steps", "steps" to arr), steps.map { it.ack }.filter { it.isNotBlank() }.joinToString(", "))
        }
        return Split(local, if (leftover) "rest" else null)
    }

    // ------------------------------------------------------------------ नतीजे (honest)

    /** इन prefix वाले नतीजे में ":" के बाद user को बोलने वाली साफ़ हिंदी बात होती है */
    private val SAY_PREFIX = listOf("ASK:", "ERROR_NOT_INSTALLED:", "ERROR_LAUNCH:", "ERROR_NO_CONTACT:", "ERROR_PERM:", "ERROR_NO_PHOTO:",
        "CALLING:", "CALL_UNVERIFIED:", "DIALER:", "PHOTO_OPENED:", "GALLERY_OPENED:", "VIDEO_OPENED:", "STEPS_DONE:", "STEP_FAILED:")

    /** फ़ोन ने जो किया उसका साफ़ हिंदी वाक्य (null = यह नतीजा ऐसा नहीं) */
    fun userText(r: String): String? {
        val p = SAY_PREFIX.firstOrNull { r.startsWith(it) } ?: return null
        return r.removePrefix(p).substringBefore("\nINSTALLED:").trim().ifBlank { null }
    }

    /** यह नतीजा आख़िरी है - AI को देने से कुछ नहीं सुधरेगा (ऐप है ही नहीं / कॉन्टैक्ट नहीं / user से पूछना है) */
    fun final(r: String) = listOf("ASK:", "ERROR_NOT_INSTALLED:", "ERROR_NO_CONTACT:", "ERROR_PERM:", "ERROR_NO_PHOTO:", "STEP_FAILED:").any { r.startsWith(it) }

    fun needsUser(r: String) = r.startsWith("ASK:")

    // ------------------------------------------------------------------ कार्ट की जाँच

    fun isCartGoal(goal: String) = hasAny(noShop(goal.lowercase()), listOf("cart", "कार्ट", "basket", "बास्केट", "bag me", "बैग में"))

    /** स्क्रीन पर सच में "कार्ट में जुड़ गया" दिख रहा है? (नहीं दिखा = "जोड़ दिया" नहीं बोलना) */
    fun cartConfirmed(screen: String): Boolean {
        val s = screen.lowercase()
        return listOf("added to cart", "added to bag", "added to basket", "added to your cart", "item added", "items added",
            "go to cart", "go to bag", "view cart", "view bag", "कार्ट में जोड़", "कार्ट में जुड़", "कार्ट में डाल दिया", "बैग में जोड़",
            "added to your bag", "remove from cart").any { s.contains(it) }
    }
}
