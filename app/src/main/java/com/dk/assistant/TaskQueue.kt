package com.dk.assistant

import kotlinx.coroutines.delay

/**
 * v3.87: एक साँस में बोले गए कई काम (कम से कम 5) - सुनो, बोले गए क्रम में अलग-अलग काम बनाओ, कतार में रखो।
 *
 * "अलार्म लगाओ, फिर राहुल को व्हाट्सऐप करो, फिर गैलरी से फ़ोटो डिलीट करो, फिर यूट्यूब पर गाना सर्च करो,
 *  फिर मैप में जगह खोजकर नेविगेशन शुरू करो" = 5 काम।
 *
 * एक ही ऐप के अंदर का सिलसिला ("Flipkart खोलो, जूते सर्च करो, पहला कार्ट में डालो") एक ही काम रहता है -
 * बीच में होम दबाकर तोड़ा नहीं जाता। नया काम तभी बनता है जब हिस्सा किसी दूसरे काम/ऐप की बात करे।
 *
 * यह फ़ाइल Android के बिना है (JVM test चलते हैं); असली काम GeminiLive करता है।
 */
object TaskQueue {

    enum class Kind { NORMAL, WHATSAPP_SEND, DELETE }

    data class Item(
        val text: String,
        val domain: String,
        val kind: Kind = Kind.NORMAL,
        /** WhatsApp: किसे और क्या (साफ़ न हो तो खाली - तब एक छोटा सवाल) */
        val contact: String = "",
        val message: String = ""
    )

    /** एक काम का नतीजा */
    data class Result(val item: Item, val ok: Boolean, val why: String = "")

    /** काम से पहले एक छोटा सवाल (सिर्फ़ ख़तरे वाले काम में, और सिर्फ़ जब बात साफ़ न हो) */
    enum class Field { CONTACT, MESSAGE, WHICH_PHOTO, CHOICE }
    data class Ask(val field: Field, val question: String)

    // ---------------- बाँटना ----------------

    /** "फिर / उसके बाद / और फिर / then / , / ।" पर हिस्से (" और " भी, पर संदेश के अंदर नहीं) */
    private val SPLIT = Regex(
        "\\s*(?:[,،;।]|\\s(?:और\\s+फिर|और\\s+उसके\\s+बाद|उसके\\s+बाद|इसके\\s+बाद|फिर|और|तब|then|and\\s+then|after\\s+that|and)\\s)\\s*",
        RegexOption.IGNORE_CASE
    )

    private val LEAD = Regex("^(?:(?:और\\s+)?फिर|उसके\\s+बाद|इसके\\s+बाद|और|then|and)\\s+", RegexOption.IGNORE_CASE)

    private val DOMAINS: List<Pair<String, List<String>>> = listOf(
        "whatsapp" to listOf("व्हाट्सऐप", "व्हाट्सएप", "व्हाट्सअप", "वॉट्सऐप", "वॉट्सएप", "वाट्सएप", "वाट्सऐप", "व्हाट्सप", "whatsapp", "watsapp", "whats app"),
        "sms" to listOf("एसएमएस", "sms"),
        "message" to listOf("मैसेज", "मेसेज", "संदेश", "message", "msg"),
        "call" to listOf("कॉल", "काल कर", "call", "फोन लगा", "फ़ोन लगा", "फोन करो", "फ़ोन करो"),
        "alarm" to listOf("अलार्म", "alarm"),
        "timer" to listOf("टाइमर", "timer"),
        "reminder" to listOf("रिमाइंडर", "reminder", "याद दिला"),
        "maps" to listOf("मैप", "map", "नेविगेशन", "नेविगेट", "navigation", "navigate", "रास्ता", "direction", "लोकेशन"),
        "youtube" to listOf("यूट्यूब", "youtube", "गाना", "गाने", "song", "वीडियो", "video", "म्यूज़िक", "म्यूजिक", "music"),
        "gallery" to listOf("गैलरी", "gallery", "फोटो", "फ़ोटो", "photo", "तस्वीर", "स्क्रीनशॉट", "screenshot", "इमेज", "image"),
        "camera" to listOf("कैमरा", "camera", "सेल्फी", "selfie"),
        "torch" to listOf("टॉर्च", "torch", "flashlight", "फ्लैशलाइट"),
        "volume" to listOf("वॉल्यूम", "volume"),
        "brightness" to listOf("ब्राइटनेस", "brightness"),
        "wifi" to listOf("वाईफाई", "वाई-फाई", "वाईफ़ाई", "wifi", "wi-fi"),
        "bluetooth" to listOf("ब्लूटूथ", "bluetooth"),
        "hotspot" to listOf("हॉटस्पॉट", "hotspot"),
        "settings" to listOf("सेटिंग", "setting")
    )
    /** इनसे शब्द शुरू हो पर ये काम नहीं ("कॉलेज" ≠ कॉल) */
    private val FALSE_START = listOf("कॉलेज", "college", "मैपल", "callback")

    private val OPEN = Regex("(खोल|ओपन|open|launch|चालू कर|शुरू कर)", RegexOption.IGNORE_CASE)
    private val DEL = Regex("(डिलीट|delete|मिटा|हटा|remove|ट्रैश|trash)", RegexOption.IGNORE_CASE)
    private val SEND = Regex("(भेज|send|लिख|बोल\\s*दो|बता\\s*दो|मैसेज\\s*कर|मेसेज\\s*कर|व्हाट्सऐप\\s*कर|व्हाट्सएप\\s*कर|whatsapp\\s*कर|text)", RegexOption.IGNORE_CASE)

    private fun norm(s: String) = s.lowercase()

    /** किस काम/ऐप की बात है; कुछ न मिले = null (पिछले काम का ही अगला क़दम) */
    fun domainOf(clause: String): String? {
        val t = norm(clause)
        if (FALSE_START.any { t.contains(it) } && DOMAINS.none { (d, ws) -> d != "call" && ws.any { t.contains(it) } }) return null
        for ((d, words) in DOMAINS) if (words.any { t.contains(it) }) {
            return when (d) {
                // "राहुल को मैसेज करो" = WhatsApp (DK का आम रास्ता); "SMS भेजो" = sms
                "message" -> if (t.contains("sms") || t.contains("एसएमएस")) "sms" else "whatsapp"
                else -> d
            }
        }
        if (OPEN.containsMatchIn(t)) {
            // "Flipkart खोलो" = app:flipkart
            val w = t.replace(OPEN, " ").split(Regex("\\s+")).firstOrNull { it.length > 1 && it !in FILLER }
            return "app:" + (w ?: "x")
        }
        return null
    }

    private val FILLER = setOf("फिर", "और", "तो", "प्लीज", "please", "जरा", "ज़रा", "एक", "भी", "करो", "करना", "दो", "को", "में", "पर", "से", "app", "ऐप", "एप")

    /** पूरी बात -> बोले गए क्रम में अलग-अलग काम */
    fun split(raw: String): List<Item> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        // हिस्से + उनके बीच का जोड़ (" और ", ", ") - उसी काम में जुड़े तो जोड़ वापस लगे ("मैं और राहुल" न टूटे)
        val parts = mutableListOf<Pair<String, String>>()   // (पहले वाला जोड़, हिस्सा)
        var at = 0; var sep = ""
        for (m in SPLIT.findAll(text)) {
            val p = text.substring(at, m.range.first).trim().replace(LEAD, "")
            if (p.isNotBlank()) { parts += sep to p; sep = m.value } else if (sep.isBlank()) sep = m.value
            at = m.range.last + 1
        }
        text.substring(at).trim().replace(LEAD, "").takeIf { it.isNotBlank() }?.let { parts += sep to it }
        val groups = mutableListOf<Pair<String, StringBuilder>>()
        for ((sp, p) in parts) {
            val joint = sp.trim().let { if (it.isEmpty() || it in listOf(",", "،", ";", "।")) "$it " else " $it " }.let { if (it == " ") " " else it }
            val d = domainOf(p)
            val last = groups.lastOrNull()
            when {
                last == null -> groups += (d ?: "other") to StringBuilder(p)
                d == null -> last.second.append(joint).append(p)           // उसी काम का अगला क़दम / संदेश का बाक़ी हिस्सा
                d == last.first -> last.second.append(joint).append(p)
                // "YouTube खोलो, फिर गाना सर्च करो" = एक ही काम
                last.first.startsWith("app:") && sameApp(last.first, d) -> last.second.append(joint).append(p)
                // WhatsApp संदेश के अंदर आई बात ("राहुल को मैसेज करो कि फ़ोटो भेज दी") - संदेश ही है
                last.first == "whatsapp" && sp.trim().lowercase() in setOf("और", "and") && hasMessageOpen(last.second.toString()) && !OPEN.containsMatchIn(p) && !DEL.containsMatchIn(p) -> last.second.append(joint).append(p)
                else -> groups += d to StringBuilder(p)
            }
        }
        // "यार आज मूड ख़राब है, एक अलार्म लगा दो" - शुरू की आम बात काम नहीं है
        while (groups.size > 1 && groups[0].first == "other" && !isCommand(groups[0].second.toString())) groups.removeAt(0)
        return groups.map { (d, sb) -> build(sb.toString().trim(), d) }
    }

    private fun sameApp(appDomain: String, d: String): Boolean {
        val a = appDomain.removePrefix("app:")
        return DOMAINS.firstOrNull { it.first == d }?.second?.any { a.contains(it) || it.contains(a) } == true
    }

    private fun hasMessageOpen(s: String) = Regex("\\s(कि|that)\\s|लिखो|बोलो", RegexOption.IGNORE_CASE).containsMatchIn(" $s ")

    private fun build(text: String, domain: String): Item {
        val kind = when {
            (domain == "gallery" || domain == "other" || domain.startsWith("app:")) && DEL.containsMatchIn(text) -> Kind.DELETE
            domain == "whatsapp" && (SEND.containsMatchIn(text) || text.contains(" को ")) && !(OPEN.containsMatchIn(text) && !text.contains(" को ")) -> Kind.WHATSAPP_SEND
            else -> Kind.NORMAL
        }
        if (kind != Kind.WHATSAPP_SEND) return Item(text, domain, kind)
        val wa = parseWa(text)
        return Item(text, domain, kind, wa.first, wa.second)
    }

    /** आदेश वाले शब्द: "कर दो", "लगाओ", "खोलो", "भेजो" ... */
    private val COMMAND = Regex(
        "(^|\\s)(\\S*(ओ|ो)|कर\\s*दो|कर\\s*दे|करें|करिए|कीजिए|कीजिये|दीजिए|दे\\s*दो|दो|open|set|send|play|call|turn|start|stop|delete|show|search|find|navigate)(\\s|[।.!?,]|$)",
        RegexOption.IGNORE_CASE
    )
    private val NOT_COMMAND = setOf("हो", "को", "तो", "सो", "जो", "वो", "यो", "लो", "मो", "दो")
    fun isCommand(text: String): Boolean {
        val words = text.lowercase().split(Regex("[\\s,।.!?]+")).filter { it.isNotBlank() }
        if (Regex("(कर\\s*दो|कर\\s*दे|लगा\\s*दो|भेज\\s*दो|चला\\s*दो|दिखा\\s*दो|बता\\s*दो|खोल\\s*दो)").containsMatchIn(text)) return true
        if (words.size >= 2 && words.drop(1).any { it == "दो" || it == "दें" || it == "दीजिए" || it == "डालो" || it == "देना" }) return true
        return words.any { w -> (w.length >= 3 && (w.endsWith("ो") || w.endsWith("ओ")) && w !in NOT_COMMAND && w !in setOf("दोनों", "लोगो", "बोलो?")) ||
            w in setOf("करें", "करिए", "कीजिए", "कीजिये", "दीजिए", "open", "set", "send", "play", "call", "turn", "start", "delete", "show", "search", "find", "navigate") }
    }

    /** दो या ज़्यादा अलग काम = कतार */
    fun isMulti(raw: String) = split(raw).size >= 2

    // ---------------- WhatsApp: किसे, क्या ----------------

    private val WA_NOISE = setOf(
        "व्हाट्सऐप", "व्हाट्सएप", "व्हाट्सअप", "वॉट्सऐप", "वॉट्सएप", "वाट्सएप", "वाट्सऐप", "व्हाट्सप", "whatsapp", "मैसेज", "मेसेज", "message", "msg",
        "पर", "में", "on", "को", "करो", "कर", "दो", "दें", "देना", "भेजो", "भेज", "send", "एक", "ज़रा", "जरा", "प्लीज", "please", "फिर", "और",
        "लिखो", "बोलो", "बोल", "बता", "बताओ", "text", "दीजिए", "करना", "दे", "देो"
    )
    private fun dropNoise(s: String) = s.split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase().trim(',', '।') !in WA_NOISE }.joinToString(" ")

    /** (contact, message) - जो साफ़ न हो वह खाली */
    fun parseWa(text: String): Pair<String, String> {
        val t = " " + text.trim() + " "
        var contact = ""
        var rest = ""
        val ko = Regex("^\\s*(.*?)\\s+को\\s+(.*)$").find(t)
        if (ko != null) {
            contact = cleanName(ko.groupValues[1])
            rest = ko.groupValues[2]
        } else {
            val to = Regex("\\bto\\s+([\\p{L}\\p{M}]+(?:\\s+[\\p{L}\\p{M}]+)?)", RegexOption.IGNORE_CASE).find(t)
            if (to != null) { contact = cleanName(to.groupValues[1].replace(Regex("\\s+(on|in)$", RegexOption.IGNORE_CASE), "")); rest = t.replace(to.value, " ") }
        }
        // "कि मैं आ रहा हूँ" / "लिखो 'hello'"
        val ki = Regex("\\s(?:कि|that|लिखो|बोलो|बोल\\s*दो|लिख\\s*दो)\\s+(.+)$", RegexOption.IGNORE_CASE).find(" $rest ")
        val msg = if (ki != null) ki.groupValues[1].trim().trim('"', '\'', '“', '”')
        else dropNoise(rest).trim().trim('"', '\'')
        return contact to (if (msg.length >= 2) msg else "")
    }

    private fun cleanName(s: String): String {
        val words = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in NAME_FILLER && it.lowercase() !in WA_NOISE }
        return words.takeLast(3).joinToString(" ").trim().trim(',', '।')
    }
    private val NAME_FILLER = setOf("फिर", "और", "तो", "प्लीज", "please", "जरा", "ज़रा", "उसके", "बाद", "then", "send", "message", "whatsapp")

    // ---------------- सवाल (सिर्फ़ ख़तरे वाले काम, सिर्फ़ जब ज़रूरी) ----------------

    private val LATEST = Regex("(आखिरी|आख़िरी|आखरी|लास्ट|last|latest|नई|नया|अभी\\s*वाली|अभी\\s*ली|हाल\\s*की|सबसे\\s*नई|पिछली)", RegexOption.IGNORE_CASE)

    fun preAsk(item: Item): Ask? = when (item.kind) {
        Kind.WHATSAPP_SEND -> when {
            item.contact.isBlank() -> Ask(Field.CONTACT, "किसे मैसेज भेजूँ, सर?")
            item.message.isBlank() -> Ask(Field.MESSAGE, "${item.contact} को क्या मैसेज भेजूँ, सर?")
            else -> null
        }
        // कौन-सी फ़ोटो - "आखिरी फ़ोटो" साफ़ है; सिर्फ़ "फ़ोटो डिलीट करो" पर एक सवाल
        Kind.DELETE -> if (LATEST.containsMatchIn(item.text)) null else Ask(Field.WHICH_PHOTO, "कौन-सी फ़ोटो डिलीट करूँ, सर? सबसे आख़िरी वाली?")
        else -> null
    }

    private val YES = Regex("^(हाँ|हां|हा|हम्म|haan|han|ha|yes|yeah|ok|ओके|ठीक|सही|जी|बिल्कुल|कर\\s*दो|हाँ\\s*जी)", RegexOption.IGNORE_CASE)
    private val NO = Regex("^(नहीं|नही|ना|no|nahi|मत|रहने\\s*दो|छोड़ो|cancel)", RegexOption.IGNORE_CASE)

    fun isNo(answer: String) = NO.containsMatchIn(answer.trim())

    /** जवाब जोड़कर काम पक्का करो; null = यूज़र ने मना किया (काम छोड़ दो) */
    fun applyAnswer(item: Item, ask: Ask, answer: String): Item? {
        val a = answer.trim().trim('।', '.', '?', '!')
        if (a.isBlank()) return null
        return when (ask.field) {
            Field.CONTACT -> {
                if (isNo(a)) null else {
                    val (c, m) = parseWa(if (a.contains(" को ")) a else "$a को ${item.message}")
                    item.copy(contact = c.ifBlank { a }, message = item.message.ifBlank { m })
                }
            }
            Field.MESSAGE -> if (isNo(a) && a.length < 12) null else item.copy(message = a)
            Field.WHICH_PHOTO -> when {
                isNo(a) && !LATEST.containsMatchIn(a) -> null
                LATEST.containsMatchIn(a) -> item.copy(text = "$a फोटो डिलीट करो".replace(Regex("(फोटो|फ़ोटो)\\s+(फोटो|फ़ोटो)"), "फोटो"))
                YES.containsMatchIn(a) -> item.copy(text = "आखिरी फोटो डिलीट करो")
                else -> item.copy(text = "${item.text} ($a)")
            }
            Field.CHOICE -> if (isNo(a) && a.length < 12) null else item.copy(text = "${item.text}। चुनाव: $a", contact = if (item.kind == Kind.WHATSAPP_SEND) a else item.contact)
        }
    }

    // ---------------- रुको / बंद करो ----------------

    private val STOP = Regex("^(अरे\\s+)?(रुको|रुक\\s*जाओ|रुकिए|रुक|बस|बस\\s*करो|बंद\\s*करो|स्टॉप|stop|cancel|कैंसल|रहने\\s*दो|छोड़ो|मत\\s*करो|wait)(\\s+(करो|जाओ|यार|प्लीज|please|सब|अभी|दो|ना|रे|डीके|dk))*\\s*[।.!]*$", RegexOption.IGNORE_CASE)

    /** सिर्फ़ "रुको/बंद करो" - "टॉर्च बंद करो" जैसा काम नहीं */
    fun isStop(text: String): Boolean {
        val t = text.trim().trimEnd('।', '.', '!', '?').trim()
        if (t.isBlank() || t.length > 30) return false
        return STOP.matches(t)
    }

    // ---------------- नतीजा पहचानना (फ़ोन के जवाब से) ----------------

    data class Outcome(val ok: Boolean, val needsUser: String? = null, val why: String = "")

    fun outcome(r: String): Outcome {
        val s = r.trim()
        return when {
            s.startsWith("NEEDS USER CHOICE") -> Outcome(false, needsUser = s.substringAfter(":").substringBefore(". Ask the user").trim().ifBlank { "किसे चुनूँ, सर?" })
            s.startsWith("DONE") || s.startsWith("ALREADY DONE") || s.startsWith("FINISHED. Result") || s.startsWith("QUEUE_OK") -> Outcome(true)
            s.startsWith("FAILED") || s.startsWith("STILL RUNNING") || s.startsWith("FINISHED the steps") || s.startsWith("not_done") || s.startsWith("ERROR") -> Outcome(false, why = whyHindi(s))
            s.contains("\"status\":\"error\"") -> Outcome(false, why = whyHindi(s))
            else -> Outcome(false, why = whyHindi(s))
        }
    }

    private fun whyHindi(s: String): String {
        val l = s.lowercase()
        // फ़ोन ने ख़ुद हिंदी में वजह बताई हो तो वही
        Regex("Phone says: \\\"([^\\\"]+)\\\"|FAILED: ([^A-Za-z]{4,}?)\\s+Tell").find(s)?.let { m ->
            val v = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (v.isNotBlank() && v.any { it in '\u0900'..'\u097F' }) return v.take(80)
        }
        return when {
            l.contains("still running") || l.contains("timeout") || l.contains("समय") -> "समय पर पूरा नहीं हुआ"
            l.contains("not installed") || l.contains("not_installed") || l.contains("ऐप नहीं") -> "ऐप फ़ोन में नहीं मिला"
            l.contains("contact") || l.contains("कॉन्टैक्ट") -> "कॉन्टैक्ट नहीं मिला"
            l.contains("permission") || l.contains("इजाज़त") || l.contains("अनुमति") -> "फ़ोन की इजाज़त नहीं है"
            l.contains("cancel") -> "रोक दिया गया"
            l.contains("no answer") || l.contains("जवाब नहीं") -> "आपका जवाब नहीं मिला"
            l.contains("not confirmed") || l.contains("no confirmation") -> "पक्का नहीं हो पाया"
            else -> "फ़ोन पर पूरा नहीं हो पाया"
        }
    }

    // ---------------- आख़िरी सार ----------------

    fun label(item: Item): String = when (item.domain) {
        "alarm" -> "अलार्म"
        "timer" -> "टाइमर"
        "reminder" -> "रिमाइंडर"
        "whatsapp" -> if (item.kind == Kind.WHATSAPP_SEND) "${item.contact.ifBlank { "" }} को मैसेज".trim() else "व्हाट्सऐप"
        "sms" -> "SMS"
        "call" -> "कॉल"
        "gallery" -> if (item.kind == Kind.DELETE) "फ़ोटो डिलीट" else "गैलरी"
        "youtube" -> "गाना"
        "maps" -> "नेविगेशन"
        "camera" -> "कैमरा"
        "torch" -> "टॉर्च"
        "volume" -> "वॉल्यूम"
        "brightness" -> "ब्राइटनेस"
        "wifi" -> "वाई-फ़ाई"
        "bluetooth" -> "ब्लूटूथ"
        "hotspot" -> "हॉटस्पॉट"
        else -> if (item.kind == Kind.DELETE) "डिलीट" else item.text.take(30)
    }

    fun doneLine(item: Item): String {
        val t = item.text.lowercase()
        val off = Regex("(बंद|off)").containsMatchIn(t)
        return when (item.domain) {
            "alarm" -> if (AlarmOff.parse(item.text) != null) "अलार्म बंद हो गया" else "अलार्म लग गया"
            "timer" -> "टाइमर लग गया"
            "reminder" -> "रिमाइंडर लग गया"
            "whatsapp" -> if (item.kind == Kind.WHATSAPP_SEND) "मैसेज भेज दिया" else "व्हाट्सऐप खुल गया"
            "sms" -> "SMS भेज दिया"
            "call" -> "कॉल लगा दी"
            "gallery" -> if (item.kind == Kind.DELETE) "फ़ोटो डिलीट हो गई" else "गैलरी खुल गई"
            "youtube" -> if (Regex("(सर्च|search|ढूँढ|ढूंढ|खोज)").containsMatchIn(t) && !Regex("(चला|बजा|play|सुना)").containsMatchIn(t)) "गाना सर्च हो गया" else "गाना चल रहा है"
            "maps" -> if (Regex("(नेविगेशन|नेविगेट|navigat|रास्ता|direction|ले\\s*चलो)").containsMatchIn(t)) "नेविगेशन शुरू है" else "मैप पर जगह खुल गई"
            "camera" -> "कैमरा खुल गया"
            "torch" -> if (off) "टॉर्च बंद हो गई" else "टॉर्च जल गई"
            "volume" -> "वॉल्यूम ठीक कर दिया"
            "brightness" -> "ब्राइटनेस ठीक कर दी"
            "wifi" -> if (off) "वाई-फ़ाई बंद हो गया" else "वाई-फ़ाई चालू हो गया"
            "bluetooth" -> if (off) "ब्लूटूथ बंद हो गया" else "ब्लूटूथ चालू हो गया"
            "hotspot" -> if (off) "हॉटस्पॉट बंद हो गया" else "हॉटस्पॉट चालू हो गया"
            else -> if (item.kind == Kind.DELETE) "डिलीट हो गया" else "${item.text.take(40)} - हो गया"
        }
    }

    /** "अलार्म लग गया, मैसेज भेज दिया, फ़ोटो डिलीट हो गई, गाना चल रहा है, नेविगेशन शुरू है।" (+ जो नहीं हुआ, और क्यों) */
    fun summary(results: List<Result>, stopped: Boolean = false, left: Int = 0): String {
        val ok = results.filter { it.ok }.map { doneLine(it.item) }
        val bad = results.filter { !it.ok }.map { "${label(it.item)} नहीं हो पाया (${it.why.ifBlank { "पूरा नहीं हुआ" }})" }
        val sb = StringBuilder()
        if (ok.isNotEmpty()) sb.append(ok.joinToString(", "))
        if (bad.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(", पर "); sb.append(bad.joinToString(", ")) }
        if (stopped) { if (sb.isNotEmpty()) sb.append("। "); sb.append("आपके कहने पर बाक़ी ${if (left > 0) "$left " else ""}काम रोक दिए") }
        if (sb.isEmpty()) sb.append("कोई काम नहीं हुआ")
        return sb.toString().trim().trimEnd('।') + "।"
    }

    // ---------------- Gemini के लिए छोटे संदेश ----------------

    fun startedNote(items: List<Item>): String =
        "QUEUE STARTED (not finished yet): the phone itself will now do these ${items.size} tasks ONE BY ONE in this exact order: " +
            items.mapIndexed { i, it -> "${i + 1}) ${it.text.take(70)}" }.joinToString("; ") + ". " +
            "Say ONLY a very short acknowledgement in your own voice like \"ठीक है, कर रही हूँ।\" - nothing else, no chit-chat. " +
            "Do NOT call phone_task or whatsapp_task again for these tasks. If a SYSTEM NOTE asks you to ask the user something, ask exactly that. " +
            "At the end a SYSTEM NOTE gives the short summary; never say anything is done before that."

    fun addedNote(n: Int): String = "QUEUE_OK: added to the END of the running task list (now $n waiting). Say only \"ठीक है, यह भी कर दूँगी।\" - no tool call."

    fun askNote(q: String): String =
        "SYSTEM NOTE (this is not the user speaking): a task in the running list needs ONE short answer. Ask the user EXACTLY this, nothing more: \"$q\" " +
            "The phone takes their answer itself - after they answer, do NOT call any tool and say nothing (or just \"ठीक है\")."

    fun summaryNote(s: String): String =
        "SYSTEM NOTE (this is not the user speaking): ALL tasks of the list are finished. Say exactly this short summary in your usual voice, nothing else: \"$s\""

    fun stoppedNote(): String = "STOPPED: the task list was stopped at once because the user said stop. Say only \"ठीक है, रोक दिया।\""
}

/**
 * कतार चलाने वाला - हर काम पूरा करके ही अगला; अटके तो छोड़कर आगे; आख़िर में सार।
 * असली फ़ोन वाले काम lambdas में आते हैं (test में नकली)।
 */
class TaskRunQueue(
    /** एक काम करो; फ़ोन का नतीजा (DONE / FAILED / NEEDS USER CHOICE ...) लौटाओ */
    private val runOne: suspend (TaskQueue.Item) -> String,
    /** अगले काम से पहले फ़ोन को ठीक हालत में लाओ (होम / ऐप बंद) */
    private val prepare: suspend (prev: TaskQueue.Item, next: TaskQueue.Item, prevOk: Boolean) -> Unit,
    /** यूज़र से एक छोटा सवाल; जवाब (या null = जवाब नहीं आया) */
    private val ask: suspend (String) -> String?,
    private val log: (String) -> Unit = {}
) {
    private val pending = ArrayDeque<TaskQueue.Item>()
    val results = mutableListOf<TaskQueue.Result>()
    @Volatile var stopped = false
        private set
    @Volatile var running = false
        private set
    @Volatile var asking = false
        private set

    @Synchronized fun add(items: List<TaskQueue.Item>): Int { pending.addAll(items); return pending.size }
    @Synchronized private fun next(): TaskQueue.Item? = pending.removeFirstOrNull()
    @Synchronized fun left(): Int = pending.size

    /** "रुको" - तुरंत; चल रहा काम GeminiLive ख़ुद रोकता है */
    fun stop() { stopped = true }

    private suspend fun askOnce(q: String): String? {
        asking = true
        try { return ask(q)?.trim()?.takeIf { it.isNotBlank() } } finally { asking = false }
    }

    /** सब काम क्रम से; लौटता है = बोलने वाला छोटा सार */
    suspend fun runAll(): String {
        running = true
        var prev: TaskQueue.Item? = null
        var prevOk = true
        try {
            while (!stopped) {
                var item = next() ?: break
                if (prev != null) prepare(prev, item, prevOk)
                if (stopped) { add(listOf(item)); break }
                // ख़तरे वाला काम और बात साफ़ नहीं - सिर्फ़ एक छोटा सवाल
                val pre = TaskQueue.preAsk(item)
                if (pre != null) {
                    val a = askOnce(pre.question)
                    if (stopped) break
                    val fixed = a?.let { TaskQueue.applyAnswer(item, pre, it) }
                    if (fixed == null || TaskQueue.preAsk(fixed)?.field == pre.field) {
                        results += TaskQueue.Result(item, false, if (a == null) "आपका जवाब नहीं मिला" else "आपने मना किया")
                        prev = item; prevOk = false; continue
                    }
                    item = fixed
                    // WhatsApp: नाम मिला, संदेश अब भी खाली -> दूसरा (आख़िरी) सवाल
                    val p2 = TaskQueue.preAsk(item)
                    if (p2 != null) {
                        val a2 = askOnce(p2.question)
                        val f2 = a2?.let { TaskQueue.applyAnswer(item, p2, it) }
                        if (f2 == null) { results += TaskQueue.Result(item, false, if (a2 == null) "आपका जवाब नहीं मिला" else "आपने मना किया"); prev = item; prevOk = false; continue }
                        item = f2
                    }
                }
                log("कतार: ${results.size + 1}. \"${item.text.take(50)}\"")
                var o = TaskQueue.outcome(try { runOne(item) } catch (e: kotlinx.coroutines.CancellationException) { if (stopped) "FAILED: cancel" else throw e } catch (e: Exception) { "FAILED: ${e.message}" })
                if (o.needsUser != null && !stopped) {
                    val a = askOnce(o.needsUser!!)
                    val fixed = a?.let { TaskQueue.applyAnswer(item, TaskQueue.Ask(TaskQueue.Field.CHOICE, o.needsUser!!), it) }
                    o = if (fixed == null) TaskQueue.Outcome(false, why = if (a == null) "आपका जवाब नहीं मिला" else "आपने मना किया")
                    else { item = fixed; TaskQueue.outcome(try { runOne(item) } catch (e: Exception) { "FAILED: ${e.message}" }).let { if (it.needsUser != null) TaskQueue.Outcome(false, why = "साफ़ नहीं हो पाया") else it } }
                }
                if (stopped && !o.ok) break
                results += TaskQueue.Result(item, o.ok, o.why)
                prev = item; prevOk = o.ok
                delay(150)
            }
        } finally { running = false }
        return TaskQueue.summary(results, stopped, left())
    }
}
