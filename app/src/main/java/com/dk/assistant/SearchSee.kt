package com.dk.assistant

import org.json.JSONObject

/**
 * 28 नवंबर 2026 (मालिक): "मैप पे X सर्च करो और देखो है या नहीं", "पवन सिंह का सॉन्ग सर्च करो", "Flipkart पे X सर्च करो",
 * "नीचे स्क्रॉल करो / पसंद नहीं, और दिखाओ" - खोजने के बाद DK खुद स्क्रीन पर आया नतीजा पढ़े, बताए और अगला कदम पूछे
 * (गाना = "चलाऊँ?", शॉपिंग = "कार्ट में ऐड कर दूँ?", मैप = "रास्ता लगा दूँ?"). यूज़र का अपना हुक्म है, इसलिए पूछना नहीं.
 * Android import नहीं (सिर्फ़ org.json) -> JVM unit test. स्क्रीन पढ़ना [GeminiLive.withSeen] में.
 */
object SearchSee {
    enum class Kind { MAP, SONG, SHOP, APP }

    /** खोज के बाद पढ़ने लायक काम */
    val SEARCH_TYPES = setOf("map_search", "youtube_search", "shop_search", "app_search")

    /** स्क्रॉल के बाद नई चीज़ें तभी पढ़ो जब सामने इनमें से कोई ऐप हो (चैट / गैलरी में कभी नहीं) */
    private val SCROLL_APPS = listOf("flipkart", "amazon", "meesho", "myntra", "ajio", "snapdeal", "nykaa", "jiomart", "blinkit", "zepto",
        "bigbasket", "swiggy", "zomato", "youtube", "com.google.android.apps.maps", "music")
    /** इन ऐप में खोज = कभी नहीं पढ़ना (निजी) */
    private val PRIVATE_APPS = listOf("whatsapp", "gallery", "photos", "messag", "mms", "telegram", "instagram", "facebook", "android.gm", "contacts", "dialer")

    /** सामने निजी ऐप (चैट / गैलरी / मैसेज) = कुछ नहीं पढ़ना */
    fun privateApp(pkg: String): Boolean = pkg.lowercase().let { p -> PRIVATE_APPS.any { p.contains(it) } }

    fun kind(action: JSONObject, fg: String = ""): Kind? {
        val type = action.optString("type")
        val app = (action.optString("app") + " " + fg).lowercase()
        return when (type) {
            "map_search" -> Kind.MAP
            "youtube_search" -> Kind.SONG
            "shop_search" -> Kind.SHOP
            "app_search" -> when {
                PRIVATE_APPS.any { app.contains(it) } -> null
                app.contains("youtube") || app.contains("music") || app.contains("spotify") || app.contains("gaana") || app.contains("jiosaavn") -> Kind.SONG
                app.contains("map") -> Kind.MAP
                SCROLL_APPS.any { app.contains(it) } -> Kind.SHOP
                else -> Kind.APP
            }
            "scroll" -> scrollKind(fg)
            else -> null
        }
    }

    private fun scrollKind(fg: String): Kind? {
        val p = fg.lowercase()
        if (p.isBlank() || PRIVATE_APPS.any { p.contains(it) } || SCROLL_APPS.none { p.contains(it) }) return null
        return when {
            p.contains("youtube") || p.contains("music") -> Kind.SONG
            p.contains("maps") -> Kind.MAP
            else -> Kind.SHOP
        }
    }

    /** UI की बेकार लाइनें (नेविगेशन, खाली बटन) */
    private val JUNK = setOf("search", "सर्च", "back", "पीछे", "home", "होम", "more options", "menu", "मेन्यू", "filter", "filters", "sort",
        "explore", "shorts", "subscriptions", "you", "library", "cart", "account", "notifications", "voice search", "clear", "cancel",
        "navigate up", "more", "share", "save", "directions", "start", "call", "website", "categories", "account & lists")
    private val QUOTED = Regex("\"([^\"]{2,})\"")
    private val DESC = Regex("\\(([^()]{2,})\\)")

    /** pure: [DKAccessibilityService.readScreen] वाला text -> स्क्रीन पर दिखे शब्द (क्रम वही, दोहराव नहीं) */
    fun lines(screen: String, max: Int = MAX_LINES): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in screen.lines()) {
            val bits = QUOTED.findAll(raw).map { it.groupValues[1] } + DESC.findAll(raw).map { it.groupValues[1] }
            for (b in bits) {
                val t = b.replace(Regex("\\s+"), " ").trim()
                if (t.length < 2 || t.lowercase() in JUNK || t.all { !it.isLetterOrDigit() }) continue
                out.add(t.take(90))
                if (out.size >= max) return out.toList()
            }
        }
        return out.toList()
    }

    /** pure: Gemini के लिए नतीजा + क्या बोलना है. [seen] खाली = स्क्रीन पर कुछ पढ़ा नहीं गया (झूठ नहीं) */
    fun note(kind: Kind, query: String, seen: List<String>): String {
        val q = query.trim().ifBlank { "वही" }
        if (seen.isEmpty()) return " SCREEN_READ: DK looked at the screen but could not read any results yet (the page may still be loading). " +
            "Tell the user honestly in one short line, e.g. \"खोज दिया, पर नतीजे अभी पढ़ नहीं पाया - एक बार देख लीजिए।\" Never invent results."
        val list = seen.joinToString(" | ")
        val ask = when (kind) {
            Kind.MAP -> "Say clearly whether \"$q\" was found (हाँ, मिल गया / नहीं दिखा) with the first 1-3 places (name, rating, distance if visible), " +
                "then ask one short question: \"रास्ता लगा दूँ?\". If he says yes, call phone_task with \"<place> का रास्ता लगाओ\"."
            Kind.SONG -> "Tell him the first 2-4 song/video titles you see (and the channel if visible), then ask one short question: " +
                "\"कौन सा चलाऊँ - पहला वाला?\". When he says play / चलाओ / हाँ, call phone_task with \"YouTube पर <that exact title> चलाओ\"."
            Kind.SHOP -> "Tell him 2-4 items you see with their prices (only what is written), then ask one short question: " +
                "\"इनमें से कोई कार्ट में ऐड कर दूँ?\". If he says yes / ऐड कर दो, call phone_task with \"<that item> खोलकर Add to cart दबाओ\" (never order or pay). " +
                "If he says नहीं / पसंद नहीं / और दिखाओ, call phone_task with \"नीचे स्क्रॉल करो\" - you will get the new items to tell him."
            Kind.APP -> "Tell him in 1-2 short lines what results you see for \"$q\", then ask what to do next."
        }
        return " SCREEN_READ (the user himself asked for this search, so reading these results is allowed - do NOT ask 'क्या मैं ये देखूँ?'): " +
            "text now on the screen: $list. $ask Use only these words from the screen - never invent names or prices. Speak in the user's language."
    }

    const val MAX_LINES = 28
    /** नतीजे आने तक रुकने का समय (ms) */
    const val SETTLE_MS = 3500L
}
