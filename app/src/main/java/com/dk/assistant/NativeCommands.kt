package com.dk.assistant

import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक: "DK Native Local Command Engine"): फ़ोन की वो आम बातें जो अब तक AI तक जाती थीं
 * या ग़लत "ऐप खोलो" बन जाती थीं - अब फ़ोन पर ही पहचान (बिना Gemini / क्लाउड / इंटरनेट).
 *
 *  - ब्राइटनेस: "ब्राइटनेस बढ़ाओ / कम करो / 50 करो / फुल / सबसे कम / ऑटो ब्राइटनेस चालू"
 *  - ऑटो रोटेट: "ऑटो रोटेट चालू / बंद"
 *  - डू नॉट डिस्टर्ब: "DND चालू / बंद" ("do not" वाला मना-शब्द नहीं गिना जाता)
 *  - जो Android सीधे बदलने नहीं देता (डार्क मोड, लोकेशन, बैटरी सेवर, NFC): उसका सही पेज + सच्ची बात
 *  - "सेटिंग" शब्द के बिना पेज: "डिस्प्ले खोलो", "स्टोरेज दिखाओ", "बैटरी खोलो", "लोकेशन खोलो"
 *
 * जो पहले से है उसे यहाँ दोबारा नहीं: टॉर्च / आवाज़ / वाईफाई / ब्लूटूथ / हॉटस्पॉट / लॉक (QuickCommands),
 * एयरप्लेन + "X सेटिंग" (OfflineCommands), कॉल (LocalRouter), ऐप खोलना (QuickCommands -> AppLauncher).
 * Android import नहीं (सिर्फ़ org.json) -> JVM unit test. चलाना: [NativeActionEngine].
 */
object NativeCommands {
    private fun a(type: String, vararg kv: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("type", type); kv.forEach { o.put(it.first, it.second) }; return o
    }
    private fun q(ack: String, type: String, vararg kv: Pair<String, Any>) = QuickCommands.Quick(a(type, *kv), ack)
    private fun has(t: String, w: List<String>) = w.any { t.contains(it) }

    /** नए काम जिन्हें [NativeActionEngine.run] चलाता है */
    val TYPES = setOf("brightness", "rotation", "dnd")

    private val ON = listOf("चालू", "ऑन", " on", "जला", "start", "स्टार्ट", "enable", "इनेबल", "लगा", "शुरू", "chalu", "activate")
    private val OFF = listOf("बंद", "ऑफ", " off", "बुझा", "हटा", "disable", "डिसेबल", "band", "stop", "रोको", "deactivate")
    private val OPEN = listOf("खोलो", "खोल दो", "खोलिए", "खोल", "ओपन", "open", "kholo", "दिखाओ", "दिखा दो", "dikhao", "show", "में जाओ", "पर जाओ", "go to")
    /** कई कदम / सवाल / किसी को भेजना = यहाँ नहीं (AI या दूसरा रास्ता) */
    private val NOT_HERE = listOf(" और ", " फिर ", " then ", " and ", " aur ", " phir ", "क्यों", "why", "कैसे", "how ", "भेज", "send", "शेयर", "share",
        "मैसेज", "message", "व्हाट्सएप", "whatsapp", "कहाँ", "कहां", "where", "मेरी लोकेशन", "my location", "लाइव लोकेशन", "live location",
        "मैप", "map", "क्या", " kya ", "कितन", "kitna", "kitni")

    private val DIGITS = mapOf('०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4', '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9')

    private fun clean(raw: String): String {
        val t = raw.lowercase().map { DIGITS[it] ?: it }.joinToString("")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "").trim().trim('.', '।', '!', '?', ',')
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** एक टुकड़ा -> सीधा काम. null = यहाँ का नहीं (पुराना रास्ता / AI) */
    fun parse(raw: String): QuickCommands.Quick? {
        val t = clean(raw)
        if (t.length < 2 || t.split(' ').size > 8) return null
        val p = " $t "
        if (NOT_HERE.any { if (it.startsWith(" ")) p.contains(it) else t.contains(it) }) return null
        return brightness(p) ?: rotation(p) ?: dnd(p) ?: restricted(p) ?: page(t)
    }

    // ------------------------------------------------------------------ ब्राइटनेस

    private val BRIGHT = listOf("ब्राइटनेस", "ब्राईटनेस", "ब्राइटनैस", "brightness", "brightnes", "स्क्रीन की रोशनी", "स्क्रीन रोशनी", "रोशनी",
        "चमक", "स्क्रीन की लाइट", "स्क्रीन लाइट", "screen light", "screen brightness", "डिस्प्ले की लाइट", "डिस्प्ले लाइट")
    private val UP = listOf("बढ़ा", "बढा", "ज़्यादा", "ज्यादा", "zyada", "jyada", "तेज़", "तेज", "badha", "increase", " up", "high", "हाई", "बढ़")
    private val DOWN = listOf("कम", "घटा", "धीमी", "धीमा", "kam", "ghata", "decrease", " down", "low", "लो ", "dim", "डिम")
    private val MAX = listOf("फुल", "full", "max", "मैक्स", "पूरी", "सबसे ज्यादा", "सबसे ज़्यादा", "सबसे तेज", "maximum")
    private val MIN = listOf("सबसे कम", "कम से कम", "minimum", " min ", "lowest", "मिनिमम")
    private val AUTO = listOf("ऑटो", "auto", "adaptive", "एडेप्टिव", "अपने आप", "automatic", "ऑटोमैटिक")

    private fun brightness(p: String): QuickCommands.Quick? {
        if (!has(p, BRIGHT)) return null
        // "ब्राइटनेस सेटिंग" = सिर्फ़ पेज (OfflineCommands / QuickCommands का रास्ता वही करता है)
        if (has(p, listOf("सेटिंग", "setting"))) return null
        if (has(p, AUTO)) {
            val on = !has(p, OFF)
            return q(if (on) "ऑटो ब्राइटनेस चालू" else "ऑटो ब्राइटनेस बंद", "brightness", "direction" to "auto", "on" to on)
        }
        Regex("(?<![0-9])([0-9]{1,3})(?![0-9])").find(p)?.let { m ->
            val lvl = m.groupValues[1].toInt().coerceIn(0, 100)
            return q("ब्राइटनेस $lvl प्रतिशत", "brightness", "direction" to "set", "level" to lvl)
        }
        return when {
            has(p, MIN) -> q("ब्राइटनेस सबसे कम", "brightness", "direction" to "min")
            has(p, MAX) -> q("ब्राइटनेस पूरी", "brightness", "direction" to "max")
            has(p, DOWN) -> q("ब्राइटनेस कम", "brightness", "direction" to "down")
            has(p, UP) -> q("ब्राइटनेस बढ़ा दी", "brightness", "direction" to "up")
            has(p, OPEN) || p.trim().split(' ').size <= 2 -> q("", "open_settings", "page" to "brightness")
            else -> null
        }
    }

    // ------------------------------------------------------------------ ऑटो रोटेट

    private fun rotation(p: String): QuickCommands.Quick? {
        if (!has(p, listOf("ऑटो रोटेट", "ऑटो-रोटेट", "auto rotate", "auto-rotate", "autorotate", "रोटेशन", "rotation", "स्क्रीन रोटेट", "screen rotate",
                "स्क्रीन घुमा", "रोटेट"))) return null
        return when {
            has(p, OFF) -> q("ऑटो रोटेट बंद", "rotation", "on" to false)
            has(p, ON) -> q("ऑटो रोटेट चालू", "rotation", "on" to true)
            else -> q("", "open_settings", "page" to "display")
        }
    }

    // ------------------------------------------------------------------ डू नॉट डिस्टर्ब

    /** "do not disturb" में "do not" है - वह मना करना नहीं. [LocalRouter.split] मना-जाँच से पहले इसे बदल देता है */
    private val DND_WORDS = listOf("डू नॉट डिस्टर्ब", "डू नोट डिस्टर्ब", "डु नॉट डिस्टर्ब", "do not disturb", "do-not-disturb", "dont disturb", "don't disturb",
        "डोंट डिस्टर्ब", "डीएनडी", "डी एन डी", "d n d")

    /** बोली के "do not disturb" को एक शब्द "dnd" बनाओ - ताकि मना-जाँच (do not / don't) इसे "मत करो" न समझे */
    fun dndWord(t: String): String {
        var s = t
        DND_WORDS.sortedByDescending { it.length }.forEach { s = s.replace(it, "dnd") }
        return s
    }

    private fun dnd(p: String): QuickCommands.Quick? {
        val s = dndWord(p)
        if (!Regex("(?<![\\p{L}\\p{M}])dnd(?![\\p{L}\\p{M}])").containsMatchIn(s) && !has(s, listOf("साइलेंट मोड नहीं", "परेशान न"))) return null
        return when {
            has(s, OFF) -> q("डू नॉट डिस्टर्ब बंद", "dnd", "on" to false)
            has(s, ON) -> q("डू नॉट डिस्टर्ब चालू", "dnd", "on" to true)
            else -> q("", "open_settings", "page" to "dnd")
        }
    }

    // ------------------------------------------------------------------ जो Android सीधे बदलने नहीं देता

    private fun restricted(p: String): QuickCommands.Quick? {
        val onOff = has(p, ON) || has(p, OFF)
        if (has(p, listOf("डार्क मोड", "डार्क थीम", "dark mode", "dark theme", "नाइट मोड", "night mode", "डार्क चालू", "डार्क बंद", "dark on", "dark off"))) {
            if (!onOff && !has(p, OPEN)) return null
            return q("सर, डार्क मोड का पेज खोल दिया। Android किसी ऐप को डार्क मोड सीधे बदलने नहीं देता, वहाँ से बटन दबा दीजिए।",
                "open_settings", "page" to "dark")
        }
        if (has(p, listOf("लोकेशन", "location", "जीपीएस", "gps", "लोकेशन सर्विस"))) {
            if (!onOff && !has(p, OPEN)) return null
            if (!onOff) return q("", "open_settings", "page" to "location")
            return q("सर, लोकेशन का पेज खोल दिया। Android किसी ऐप को लोकेशन सीधे चालू-बंद नहीं करने देता, वहाँ से बटन दबा दीजिए।",
                "open_settings", "page" to "location")
        }
        if (has(p, listOf("बैटरी सेवर", "battery saver", "पावर सेविंग", "power saving", "power saver", "पावर सेवर", "बैटरी सेविंग", "battery saving"))) {
            if (!onOff && !has(p, OPEN)) return null
            return q(if (onOff) "सर, बैटरी सेवर का पेज खोल दिया। Android सीधे बदलने नहीं देता, वहाँ से बटन दबा दीजिए।" else "",
                "open_settings", "page" to "battery saver")
        }
        if (Regex("(?<![\\p{L}\\p{M}])(nfc|एनएफसी)(?![\\p{L}\\p{M}])").containsMatchIn(p)) {
            if (!onOff && !has(p, OPEN)) return null
            return q(if (onOff) "सर, NFC का पेज खोल दिया। Android सीधे बदलने नहीं देता, वहाँ से बटन दबा दीजिए।" else "",
                "open_settings", "page" to "nfc")
        }
        return null
    }

    // ------------------------------------------------------------------ "सेटिंग" शब्द के बिना पेज

    /** बोली गई पूरी चीज़ (क्रिया हटाकर) ठीक इनमें से एक हो - "साउंडक्लाउड खोलो" / "बैटरी वाला गेम" ऐप ही रहे */
    private val PAGES = linkedMapOf(
        "display" to listOf("डिस्प्ले", "display", "स्क्रीन सेटिंग"),
        "battery" to listOf("बैटरी", "battery", "बैटरी यूसेज", "battery usage", "बैटरी की खपत"),
        "storage" to listOf("स्टोरेज", "storage", "फ़ोन की मेमोरी", "फोन की मेमोरी", "फोन का स्टोरेज", "फ़ोन का स्टोरेज", "phone storage"),
        "sound" to listOf("साउंड", "sound", "साउंड एंड वाइब्रेशन", "sound and vibration"),
        "location" to listOf("लोकेशन", "location", "जीपीएस", "gps"),
        "notification" to listOf("नोटिफिकेशन सेटिंग", "notification setting", "ऐप नोटिफिकेशन", "app notifications"),
        "apps" to listOf("ऐप्स सेटिंग", "apps setting", "सारे ऐप्स की लिस्ट", "इंस्टॉल ऐप्स", "installed apps", "ऐप मैनेजर", "app manager", "apps list"),
        "about" to listOf("फोन के बारे में", "फ़ोन के बारे में", "about phone", "अबाउट फोन", "अबाउट फ़ोन", "फोन की जानकारी", "फ़ोन की जानकारी"),
        "developer" to listOf("डेवलपर ऑप्शन", "डेवलपर ऑप्शंस", "developer options", "developer option"),
        "accessibility" to listOf("एक्सेसिबिलिटी", "accessibility", "सुलभता"),
        "keyboard" to listOf("कीबोर्ड", "keyboard"),
        "language" to listOf("भाषा", "language", "लैंग्वेज"),
        "security" to listOf("सिक्योरिटी", "security", "स्क्रीन लॉक सेटिंग", "पासवर्ड सेटिंग"),
        "privacy" to listOf("प्राइवेसी", "privacy"),
        "date" to listOf("डेट एंड टाइम", "date and time", "तारीख और समय"),
        "vpn" to listOf("vpn", "वीपीएन"),
        "wallpaper" to listOf("वॉलपेपर", "wallpaper"),
        "default apps" to listOf("डिफ़ॉल्ट ऐप्स", "डिफॉल्ट ऐप्स", "default apps"),
        "cast" to listOf("स्क्रीन कास्ट", "screen cast", "स्क्रीन मिररिंग", "screen mirroring"),
    )
    private val SMALL = listOf("की", "का", "के", "वाली", "वाला", "वाले", "पेज", "page", "को", "मेनू", "menu", "में", "पर", "pe", "me", "करो", "कर दो", "karo",
        "मेरा", "मेरी", "मेरे", "my", "the", "फोन", "फ़ोन", "phone")

    private fun page(t: String): QuickCommands.Quick? {
        if (!has(t, OPEN)) return null
        var s = " $t "
        OPEN.sortedByDescending { it.length }.forEach { s = s.replace(Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(it) + "(?![\\p{L}\\p{M}])"), " ") }
        s = " " + s.replace(Regex("\\s+"), " ").trim() + " "
        val exact = s.trim()
        PAGES.forEach { (key, names) -> if (exact in names) return q("", "open_settings", "page" to key) }
        SMALL.forEach { w -> while (s.contains(" $w ")) s = s.replace(" $w ", " ") }
        val rest = s.replace(Regex("\\s+"), " ").trim()
        if (rest.isBlank()) return null
        PAGES.forEach { (key, names) -> if (rest in names) return q("", "open_settings", "page" to key) }
        return null
    }

    // ------------------------------------------------------------------ AI के बिना सच्ची बात

    /** Gemini / स्क्रीन के लिए छोटा वर्णन (LiveFast.describe में) */
    fun describe(a: JSONObject): String? = when (a.optString("type")) {
        "brightness" -> when (a.optString("direction")) {
            "auto" -> if (a.optBoolean("on", true)) "auto brightness on" else "auto brightness off"
            "set" -> "brightness ${a.optInt("level")}%"
            else -> "brightness ${a.optString("direction")}"
        }
        "rotation" -> if (a.optBoolean("on", true)) "auto-rotate on" else "auto-rotate off"
        "dnd" -> if (a.optBoolean("on", true)) "do not disturb on" else "do not disturb off"
        else -> null
    }

    // ------------------------------------------------------------------ हिसाब (पल-पल की देरी, बिना Android)

    data class Run(val type: String, val ms: Long, val ok: Boolean, val via: String, val at: Long)
    private const val KEEP = 20
    private val runs = ArrayDeque<Run>()

    fun record(type: String, ms: Long, ok: Boolean, via: String, now: Long = System.currentTimeMillis()): Run {
        val r = Run(type, ms.coerceAtLeast(0), ok, via, now)
        synchronized(runs) { runs.addLast(r); while (runs.size > KEEP) runs.removeFirst() }
        return r
    }
    fun all(): List<Run> = synchronized(runs) { runs.toList() }
    fun clear() = synchronized(runs) { runs.clear() }
    fun line(r: Run) = "${r.type}: ${r.ms} ms ${if (r.ok) "ठीक" else "नहीं हुआ"} (${r.via})"
    fun text(): String {
        val a = all()
        if (a.isEmpty()) return "फ़ोन के सीधे काम (बिना AI): अभी कोई नहीं"
        val avg = a.map { it.ms }.average().toLong()
        return "फ़ोन के सीधे काम (बिना AI) - औसत $avg ms\n" + a.asReversed().take(8).joinToString("\n") { line(it) }
    }
}
