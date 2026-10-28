package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Google Maps:
 *  - kisi jagah ka rasta dikhana (directions)
 *  - navigation shuru karna (navigate)
 *  - jagah / paas ki cheezein dikhana (map_search)
 *  - doori naapna (distance) - Maps ka rasta khol kar screen se km + samay padhta hai
 */
object Maps {
    private const val PKG = "com.google.android.apps.maps"

    private fun installed(c: Context) = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    private fun start(c: Context, uri: String, preferMaps: Boolean = true): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferMaps && installed(c)) i.setPackage(PKG)
        c.startActivity(i); true
    } catch (e: Exception) { DKLog.d("Maps: ${e.message}"); false }

    private fun webMode(mode: String) = when (mode.lowercase()) {
        "walking", "walk" -> "walking"
        "transit", "bus", "train" -> "transit"
        "two_wheeler", "two-wheeler", "bike" -> "two-wheeler"
        "bicycling", "cycle" -> "bicycling"
        else -> "driving"
    }

    private fun navMode(mode: String) = when (webMode(mode)) {
        "walking" -> "w"; "two-wheeler" -> "l"; "bicycling" -> "b"; else -> "d"
    }

    fun directions(c: Context, to: String, from: String = "", mode: String = "driving"): String {
        if (to.isBlank()) return "ERROR: jagah ka naam nahi mila"
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
        if (from.isNotBlank()) sb.append("&origin=").append(Uri.encode(from))
        sb.append("&destination=").append(Uri.encode(to)).append("&travelmode=").append(webMode(mode))
        return if (start(c, sb.toString())) "maps route opened: ${if (from.isBlank()) "my location" else from} -> $to (${webMode(mode)})"
        else "ERROR: maps nahi khula"
    }

    fun navigate(c: Context, to: String, mode: String = "driving"): String {
        if (to.isBlank()) return "ERROR: jagah ka naam nahi mila"
        if (start(c, "google.navigation:q=${Uri.encode(to)}&mode=${navMode(mode)}")) return "navigation started to $to"
        return directions(c, to, "", mode)
    }

    fun search(c: Context, query: String): String {
        if (query.isBlank()) return if (start(c, "geo:0,0")) "maps opened" else "ERROR: maps nahi khula"
        if (start(c, "geo:0,0?q=${Uri.encode(query)}")) return "map showing '$query'"
        return if (start(c, "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}", false)) "map showing '$query'"
        else "ERROR: maps nahi khula"
    }

    // ------------------------------------------------------------ Maps screen se doori/samay padhna
    private val KM = Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|किमी|कि\\.?\\s?मी\\.?|कि॰\\s?मी॰|किलोमीटर)", RegexOption.IGNORE_CASE)
    private val METER = Regex("(?<![\\d.])(\\d{2,4})\\s*(m|मी॰|मी\\.|मीटर)(?![a-zA-Z])")
    private val TIME = Regex("((\\d+)\\s*(?:hr|h|घं॰|घं\\.|घंटे|घंटा|घं)\\s*)?(\\d+)\\s*(?:min|मिनट|मि॰|मि\\.)", RegexOption.IGNORE_CASE)
    private val HOURS_ONLY = Regex("(\\d+)\\s*(?:hr|h|घं॰|घंटे|घंटा)(?![a-zA-Z])", RegexOption.IGNORE_CASE)

    /** Screen ke text me pehli doori + samay. null = abhi nahi dikha. */
    fun readDistance(screenText: String): String? {
        // har line alag dekho - pehli line jisme km/m ho
        val lines = screenText.lines()
        var dist: String? = null; var time: String? = null
        for (l in lines) {
            if (dist == null) {
                KM.find(l)?.let { dist = it.groupValues[1].replace(',', '.') + " किलोमीटर" }
                    ?: METER.find(l)?.let { dist = it.groupValues[1] + " मीटर" }
            }
            if (time == null) {
                TIME.find(l)?.let { m ->
                    val h = m.groupValues[2]; val mi = m.groupValues[3]
                    time = (if (h.isNotBlank()) "$h घंटे " else "") + "$mi मिनट"
                } ?: HOURS_ONLY.find(l)?.let { time = it.groupValues[1] + " घंटे" }
            }
            if (dist != null && time != null) break
        }
        if (dist == null) return null
        return if (time != null) "$dist, लगभग $time" else dist
    }

    // ------------------------------------------------------------ bolkar map commands (bina AI, turant)
    private val NOISE = listOf("मुझे ", "ज़रा ", "जरा ", "प्लीज़ ", "please ", "गूगल मैप में ", "गूगल मैप पर ", "google map पर ",
        "google maps पर ")

    private fun modeOf(t: String): String = when {
        listOf("बाइक", "स्कूटी", "स्कूटर", "टू व्हीलर", "two wheeler", "bike", "मोटरसाइकिल").any { t.contains(it) } -> "two_wheeler"
        listOf("पैदल", "walking", "walk").any { t.contains(it) } -> "walking"
        listOf("बस से", "ट्रेन से", "मेट्रो", "transit").any { t.contains(it) } -> "transit"
        else -> "driving"
    }

    private val MODE_WORDS = Regex("(बाइक से|स्कूटी से|स्कूटर से|पैदल|कार से|गाड़ी से|गाडी से|बस से|ट्रेन से|मेट्रो से|टू व्हीलर से)")
    private val SUFFIX = listOf(" का", " की", " के", " तक", " से", " को", " पर", " में")
    private val PREFIX = listOf("यहाँ से ", "यहां से ", "मुझे ", "अभी ", "जरा ", "ज़रा ")

    private const val distWordsAll = "(?:की दूरी|का डिस्टेंस|की डिस्टेंस|कितनी दूर|कितना दूर|कितने किलोमीटर|कितना किलोमीटर|कितने km|distance|दूरी)"

    private fun cleanPlace(p: String): String {
        var s = p.replace(MODE_WORDS, " ").replace(Regex("\\s+"), " ").trim(' ', ',', '.', '।', '?')
        var changed = true
        while (changed) {
            changed = false
            SUFFIX.forEach { w -> if (s.endsWith(w)) { s = s.removeSuffix(w).trim(); changed = true } }
            PREFIX.forEach { w -> if (s.startsWith(w)) { s = s.removePrefix(w).trim(); changed = true } }
        }
        return s
    }

    private fun isHere(p: String) = p.isBlank() || listOf("यहाँ", "यहां", "here", "मेरी लोकेशन", "मेरी जगह", "मैं जहाँ हूँ", "अभी जहाँ", "current location", "my location")
        .any { p.trim() == it || p.contains(it) }

    private fun a(type: String, vararg kv: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("type", type); kv.forEach { o.put(it.first, it.second) }; return o
    }

    // ---- v4: "मैप पर X सर्च करके डायरेक्शन लगा दो" जैसी बोली - जगह का नाम साफ़ निकले (पहले पूरा वाक्य ही जगह बन जाता था)
    /** शुरू का "मैप पर / गूगल मैप्स खोलो / open google maps" - हटाओ और याद रखो कि बात मैप की है */
    private val LEAD = Regex("^(?:(?:open|खोलो|ओपन करो|ओपन) )?(?:(?:गूगल|google) )?(?:मैप्स|मैप|maps|map|नक्शे|नक्शा)(?: (?:ऐप|app))?" +
        "(?: (?:खोलो|खोल दो|खोलकर|खोल के|खोलके|ओपन करो|ओपन करके|ओपन कर के|open करो|open करके|open|and))?(?: (?:में|मे|पर|पे|on|in))? ")
    /** आखिर का "on google maps" / "मैप में" */
    private val TAIL = Regex(" (?:on|in) (?:google )?maps?$| (?:गूगल )?(?:मैप्स|मैप|नक्शे) (?:में|पर|पे)$")
    /** "डायरेक्शन" के बोलचाल/सुनने में गलत रूप ("डिस्ट्रेक्शन") भी */
    private val DIR = Regex("(डायरेक्शन्स|डायरेक्शंस|डायरेक्शन|डाइरेक्शन|डिरेक्शन|डायरेक्सन|डिस्ट्रेक्शन|डिस्ट्रक्शन|directions|direction)")
    private val SEARCH_VERB = listOf("सर्च करके", "सर्च कर के", "सर्च करो", "सर्च कर दो", "सर्च कर", "सर्च", "search करके", "search karo", "search",
        "खोजकर", "खोज कर", "खोज के", "खोजो", "खोज दो", "ढूंढकर", "ढूँढकर", "ढूंढ कर", "ढूँढ कर", "ढूंढ के", "ढूँढ के", "ढूंढो", "ढूँढो", "ढूंढ दो", "ढूँढ दो",
        "find", "locate", "दिखाओ", "दिखा दो", "show me", "show")
    private val ACT_WORDS = listOf("लगा दो", "लगा दे", "लगा देना", "लगाओ", "लगाना", "लगा", "चालू करो", "चालू कर दो", "चालू", "शुरू करो", "शुरू कर दो", "शुरू",
        "स्टार्ट करो", "स्टार्ट", "start", "सेट करो", "सेट कर दो", "set", "बताओ", "बता दो", "करो", "कर दो", "दो",
        "खोलो", "खोल दो", "ओपन करो", "ओपन", "open", "के लिए", "के लिये", "please", "get", "the", "to", "for", "me")
    private val MAP_WORDS = listOf("गूगल मैप्स", "गूगल मैप", "google maps", "google map", "मैप्स", "मैप", "maps", "map", "नक्शे", "नक्शा", "नेविगेशन", "navigation", "navigate", "नेविगेट")
    private val STRIP = Regex("(?<=^| )(?:" + (SEARCH_VERB + ACT_WORDS + MAP_WORDS).sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?= |$)")

    /** बोली बात से सिर्फ़ जगह का नाम: खोजो/लगा दो/मैप/नेविगेशन जैसे शब्द हटाकर */
    fun place(p: String): String {
        var s = p.replace(DIR, " ").replace(Regex("\\s+"), " ").trim()
        var prev: String
        do {
            prev = s
            s = cleanPlace(s.replace(STRIP, " ").replace(Regex("\\s+"), " ").trim())
        } while (s != prev && s.isNotBlank())
        return s
    }

    private val WORD_CALL = Regex("(?<![\\p{L}\\p{M}])(?:कॉल|कॉल्स|call|calls)(?![\\p{L}\\p{M}])")
    private val MAP_SAID = Regex("^(?:(?:open|खोलो|ओपन करो|ओपन) )?(?:(?:गूगल|google) )?(?:मैप्स|मैप|maps|map|नक्शे|नक्शा)(?: |$)")
    private val OTHER_APP = Regex("(यूट्यूब|youtube|व्हाट्सएप|व्हाट्सऐप|whatsapp|इंस्टाग्राम|instagram|फेसबुक|facebook|मैसेज|message|गाना|song|अलार्म|alarm|फ़ोटो|फोटो|photo)")

    /** "मैप खोलो और X…" / "open google maps and X" -> "मैप पर X…" (null = ऐसा नहीं) */
    fun mergeOpen(t: String): String? {
        val m = Regex("^(?:(?:open|खोलो|ओपन करो|ओपन) )?(?:(?:गूगल|google) )?(?:मैप्स|मैप|maps|map|नक्शा)(?: (?:ऐप|app))?" +
            "(?: (?:खोलो|खोल दो|ओपन करो|ओपन कर दो|open करो|open|खोलकर|खोल के|खोलके))?\\s*(?:,\\s*)?(?:और|फिर|and then|and|then|aur|phir|उसके बाद)\\s+(.+)$")
            .find(t.lowercase().trim()) ?: return null
        val rest = m.groupValues[1].trim()
        if (rest.isBlank() || OTHER_APP.containsMatchIn(rest) || WORD_CALL.containsMatchIn(rest)) return null
        val mapIntent = DIR.containsMatchIn(rest) || SEARCH_VERB.any { rest.contains(it) } ||
            listOf("नेविगेशन", "navigat", "नेविगेट", "रास्ता", "रस्ता", "route", "रूट", "जाना है", "ले चलो", "पहुँचा", "पहुंचा", "दूरी", "दूर", "पास में", "नज़दीक", "नजदीक", "near", "लोकेशन", "location")
                .any { rest.contains(it) }
        return if (mapIntent) "मैप पर $rest" else null
    }

    fun parse(raw: String): QuickCommands.Quick? {
        var t = raw.lowercase().trim().trim('.', '।', '!', '?', ',')
        val t0 = t
        NOISE.forEach { t = t.replace(it, " ") }
        t = t.replace(Regex("\\s+"), " ").trim()
        if (listOf(" और ", " फिर ", " then ", " उसके बाद ").any { " $t ".contains(it) }) return null
        // v4: पूरा शब्द "कॉल" - "कॉलेज रोड" / "कॉलोनी" जगह का नाम है, कॉल नहीं
        if (WORD_CALL.containsMatchIn(t) || t.contains("मैसेज") || t.contains("whatsapp")) return null
        val mode = modeOf(t)
        var onMap = MAP_SAID.containsMatchIn(t0)
        LEAD.find(t)?.let { t = t.substring(it.range.last + 1).trim(); onMap = true }
        TAIL.find(t)?.let { t = t.substring(0, it.range.first).trim(); onMap = true }
        if (t.isBlank()) return null
        val go = listOf("लगा", "चालू", "शुरू", "स्टार्ट", "start", "सेट", "set").any { t.contains(it) }

        // ---- "X का डायरेक्शन लगा दो" / "X सर्च करके डायरेक्शन लगा दो" / "directions to X"
        if (DIR.containsMatchIn(t) && !Regex(distWordsAll).containsMatchIn(t)) {
            val to = place(t)
            if (to.isBlank() || isHere(to)) return null
            return if (go) QuickCommands.Quick(a("navigate", "to" to to, "mode" to mode), "$to का नेविगेशन शुरू कर रहा हूँ।")
            else QuickCommands.Quick(a("directions", "to" to to, "from" to "", "mode" to mode), "$to का रास्ता मैप पर खोल दिया।")
        }
        // ---- English: "navigate to X", "take me to X", "route to X"
        Regex("^(?:navigate|take me|drive me|start navigation) (?:to )?(.+)$").find(t)?.let { m ->
            val to = place(m.groupValues[1]); if (to.isNotBlank()) return QuickCommands.Quick(a("navigate", "to" to to, "mode" to mode), "$to का नेविगेशन शुरू कर रहा हूँ।")
        }
        Regex("^(?:show |get |find )?(?:the )?(?:route|way) (?:to|for) (.+)$").find(t)?.let { m ->
            val to = place(m.groupValues[1]); if (to.isNotBlank()) return QuickCommands.Quick(a("directions", "to" to to, "from" to "", "mode" to mode), "$to का रास्ता मैप पर खोल दिया।")
        }

        // ---- doori: "A से B की दूरी", "B कितनी दूर है", "यहाँ से B कितने किलोमीटर"
        val distWords = "(?:की दूरी|का डिस्टेंस|की डिस्टेंस|कितनी दूर|कितना दूर|कितने किलोमीटर|कितना किलोमीटर|कितने km|distance)"
        if (Regex(distWords).containsMatchIn(t) || t.contains("दूरी")) {
            Regex("^(.+?) से (.+?) (?:तक )?(?:की )?$distWords").find(t)?.let { m ->
                val from = cleanPlace(m.groupValues[1]); val to = cleanPlace(m.groupValues[2])
                if (to.isNotBlank()) return QuickCommands.Quick(a("distance", "from" to (if (isHere(from)) "" else from), "to" to to, "mode" to mode), "")
            }
            Regex("^(.+?) (?:तक )?(?:की )?$distWords").find(t)?.let { m ->
                val to = cleanPlace(m.groupValues[1])
                if (to.isNotBlank() && !isHere(to)) return QuickCommands.Quick(a("distance", "from" to "", "to" to to, "mode" to mode), "")
            }
            Regex("(?:दूरी|distance) (?:नापो|बताओ|नाप दो|बता दो)?\\s*(.+?) (?:तक|की)$").find(t)?.let { m ->
                val to = cleanPlace(m.groupValues[1])
                if (to.isNotBlank()) return QuickCommands.Quick(a("distance", "from" to "", "to" to to, "mode" to mode), "")
            }
            return null
        }

        // ---- paas ki jagah: "पास में पेट्रोल पंप", "नज़दीकी ATM", "near me hospital"
        if (listOf("पास में", "पास का", "पास की", "पास के", "नज़दीक", "नजदीक", "नज़दीकी", "नजदीकी", "near me", "nearby", "आसपास", "आस पास").any { t.contains(it) }) {
            val q = t.replace(Regex("(पास में|पास का|पास की|पास के|नज़दीकी|नजदीकी|नज़दीक|नजदीक|near me|nearby|आसपास|आस पास|कहाँ है|कहां है|कोई|दिखाओ|बताओ|ढूंढो|ढूँढो|सर्च करो|खोजो|मैप|map|है|हैं)"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (q.isNotBlank()) return QuickCommands.Quick(a("map_search", "query" to "$q near me"), "मैप पर दिखा रहा हूँ।")
        }

        // ---- navigation: "B ले चलो", "B के लिए नेविगेशन चालू करो", "नेविगेशन B"
        Regex("^(.+?) (?:ले चलो|लेकर चलो|ले के चलो|पहुँचाओ|पहुंचाओ|पहुंचा दो|पहुँचा दो)$").find(t)?.let { m ->
            val to = cleanPlace(m.groupValues[1]); if (to.isNotBlank()) return QuickCommands.Quick(a("navigate", "to" to to, "mode" to mode), "$to का नेविगेशन शुरू कर रहा हूँ।")
        }
        if (t.contains("नेविगेशन") || t.contains("navigation") || t.contains("नेविगेट")) {
            // v4: "नासिक स्टेशन खोजकर नेविगेशन शुरू करो" -> "नासिक स्टेशन" (पहले "खोजकर" भी जगह के नाम में जाता था)
            val to = place(cleanPlace(t.replace(Regex("(के लिए|के लिये|नेविगेशन|navigation|नेविगेट|चालू करो|चालू कर दो|शुरू करो|शुरू कर दो|स्टार्ट करो|start|करो|लगाओ|लगा दो|पर)"), " ")))
            if (to.isNotBlank()) return QuickCommands.Quick(a("navigate", "to" to to, "mode" to mode), "$to का नेविगेशन शुरू कर रहा हूँ।")
            return null
        }

        // ---- rasta / map: "B का रास्ता दिखाओ", "B का मैप लगाओ", "मुझे B जाना है"
        Regex("^(.+?) (?:का|की|के) (?:रास्ता|रस्ता|मैप|map|नक्शा|लोकेशन|location|route|रूट)(?: .*)?$").find(t)?.let { m ->
            val to = cleanPlace(m.groupValues[1])
            if (to.isNotBlank() && !isHere(to)) {
                val start = listOf("चालू", "शुरू", "स्टार्ट", "start").any { t.contains(it) }
                return if (start) QuickCommands.Quick(a("navigate", "to" to to, "mode" to mode), "$to का नेविगेशन शुरू कर रहा हूँ।")
                else QuickCommands.Quick(a("directions", "to" to to, "from" to "", "mode" to mode), "$to का रास्ता मैप पर खोल दिया।")
            }
        }
        Regex("^(.+?) (?:से|से लेकर) (.+?) (?:तक )?(?:का |की )?(?:रास्ता|रस्ता|मैप|route|रूट)(?: .*)?$").find(t)?.let { m ->
            val from = cleanPlace(m.groupValues[1]); val to = cleanPlace(m.groupValues[2])
            if (to.isNotBlank()) return QuickCommands.Quick(a("directions", "to" to to, "from" to (if (isHere(from)) "" else from), "mode" to mode), "$to का रास्ता मैप पर खोल दिया।")
        }
        Regex("^(.+?) (?:जाना है|जाना हैं|कैसे जाऊँ|कैसे जाऊं|कैसे जाएं|कैसे जाएँ|कैसे पहुंचूं|कैसे पहुँचूँ)$").find(t)?.let { m ->
            val to = cleanPlace(m.groupValues[1])
            if (to.isNotBlank() && to.split(' ').size <= 6) return QuickCommands.Quick(a("directions", "to" to to, "from" to "", "mode" to mode), "$to का रास्ता मैप पर खोल दिया।")
        }
        Regex("^(.+?) (?:मैप|map|नक्शे) (?:में|पर) (?:दिखाओ|खोलो|ढूंढो|ढूँढो|सर्च करो|खोजो)$").find(t)?.let { m ->
            val q = cleanPlace(m.groupValues[1]); if (q.isNotBlank()) return QuickCommands.Quick(a("map_search", "query" to q), "मैप पर दिखा रहा हूँ।")
        }
        // ---- v4: "मैप पर X सर्च करो" / "search X on google maps" / "मैप में X दिखाओ" -> सीधे X मैप पर
        if (onMap) {
            val q = place(t)
            if (q.isNotBlank() && !isHere(q) && q.split(' ').size <= 8) return QuickCommands.Quick(a("map_search", "query" to q), "$q मैप पर दिखा रहा हूँ।")
        }
        return null
    }
}
