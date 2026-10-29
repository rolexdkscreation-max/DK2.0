package com.dk.assistant

import org.json.JSONObject

/**
 * v4.1: बिना API वाला कमांड इंजन - matcher (intent + slots). [QuickCommands] / [LocalRouter] पहले से बहुत कुछ करते हैं
 * (ऐप खोलो, टॉर्च, आवाज़, लॉक, होम, बैक, स्क्रॉल, स्क्रीनशॉट, कॉल…); यहाँ सिर्फ़ जो गायब था:
 *  अलार्म / टाइमर, कैमरा / फ़ोटो खींचो / सेल्फी, समय / तारीख / दिन / बैटरी, "X पर टैप करो", एयरप्लेन मोड,
 *  "X सेटिंग खोलो" (नोटिफिकेशन सेटिंग पहले नोटिफिकेशन पट्टी खोल देता था), SMS भेजो (पहले बोलकर पूछो).
 * हिंदी / अंग्रेज़ी / हिंग्लिश. शक हो = null (तब पुराना रास्ता / AI).
 * चलाने वाला हिस्सा अलग: [OfflineExec]. Android import नहीं (सिर्फ़ org.json) -> JVM test.
 */
object OfflineCommands {

    // ------------------------------------------------------------------ साफ़-सफ़ाई

    private val DEV_DIGITS = "०१२३४५६७८९"

    /** देवनागरी अंक -> 0-9, शून्य-चौड़े निशान हटाओ, नुक़्ता/चंद्रबिंदु के छोटे फ़र्क एक जैसे, भराव शब्द हटाओ */
    fun clean(raw: String): String {
        var t = raw.lowercase()
            .map { c -> val i = DEV_DIGITS.indexOf(c); if (i >= 0) ('0' + i) else c }.joinToString("")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace('ँ', 'ं').replace("़", "")
        t = " " + t.replace(Regex("[!?।,\"'“”]+"), " ") + " "
        for (f in FILLER) t = t.replace(" $f ", " ")
        return t.replace(Regex("\\s+"), " ").trim().trim('.')
    }

    private val FILLER = listOf("प्लीज", "please", "pls", "plz", "जरा", "जल्दी से", "जल्दी", "तुरंत", "भाई", "यार", "जी", "bhai", "yaar",
        "ji", "मेरा", "मेरे", "मेरी", "mera", "meri", "mere", "my", "the", "बस", "abhi", "अभी")

    private fun has(t: String, w: Collection<String>) = w.any { t.contains(it) }
    private fun hasWord(t: String, w: Collection<String>): Boolean {
        val ws = t.split(Regex("[\\s,.!?।:]+")).toSet()
        return w.any { it in ws }
    }
    private fun a(type: String, vararg kv: Pair<String, Any>) = JSONObject().put("type", type).apply { kv.forEach { put(it.first, it.second) } }
    private fun q(type: String, vararg kv: Pair<String, Any>) = QuickCommands.Quick(a(type, *kv), "")

    // ------------------------------------------------------------------ मुख्य matcher

    /** एक टुकड़ा (वाक्य) -> काम. null = यहाँ का नहीं */
    fun parse(raw: String): QuickCommands.Quick? {
        val t = clean(raw)
        if (t.length < 2) return null
        if (has(" $t ", NEG)) return null
        settingsPage(t)?.let { return it }
        airplane(t)?.let { return it }
        timer(t)?.let { return it }
        alarm(t)?.let { return it }
        camera(t)?.let { return it }
        info(t)?.let { return it }
        tapText(t)?.let { return it }
        return null
    }

    private val NEG = listOf(" मत ", " mat ", "don't", " dont ", " do not ", "नहीं करना", "मत करो", " ना करो ")

    // ------------------------------------------------------------------ E. सेटिंग का पेज

    private val SETTING_WORD = listOf("सेटिंग", "setting", "सेटिंग्स", "settings")
    private val SET_VERBS = listOf("खोलो", "खोल दो", "खोलिए", "ओपन", "open", "kholo", "dikhao", "दिखाओ", "चलाओ", "में जाओ", "पर जाओ", "jao", "go to", "show")
    /** इतने शब्द बचें तो ही (लंबी बात = AI) */
    private fun settingsPage(t: String): QuickCommands.Quick? {
        if (!has(t, SETTING_WORD)) return null
        // "सेटिंग में जाकर वाईफाई बंद करो" जैसे कई कदम = पुराना रास्ता
        if (has(t, listOf(" और ", " फिर ", " then ", " and ", " aur ", " phir ", "बदल", "change", "बढा", "घटा", "कम कर", "ज्यादा"))) return null
        var page = " $t "
        (SETTING_WORD + SET_VERBS).sortedByDescending { it.length }.forEach { page = page.replace(it, " ") }
        page = " " + page.replace(Regex("\\s+"), " ").trim() + " "
        // छोटे शब्द सिर्फ़ पूरे ("की" को "कीबोर्ड" में से नहीं)
        listOf("की", "का", "के", "वाली", "वाला", "पेज", "page", "को", "मेनू", "menu", "में", "पर", "pe", "me", "करो", "कर दो", "karo")
            .forEach { w -> while (page.contains(" $w ")) page = page.replace(" $w ", " ") }
        page = page.replace(Regex("\\s+"), " ").trim()
        if (page.split(' ').size > 4) return null
        return QuickCommands.Quick(a("open_settings", "page" to page), "हो गया सर।")
    }

    // ------------------------------------------------------------------ D. एयरप्लेन मोड

    private fun airplane(t: String): QuickCommands.Quick? {
        if (!has(t, listOf("एयरप्लेन", "एरोप्लेन", "airplane", "aeroplane", "flight mode", "फ्लाइट मोड", "हवाई जहाज मोड", "plane mode"))) return null
        // Android किसी ऐप को एयरप्लेन मोड सीधे बदलने नहीं देता -> उसका पेज
        return QuickCommands.Quick(a("open_settings", "page" to "airplane"), "सर, एयरप्लेन मोड का पेज खोल दिया। Android सीधे बदलने नहीं देता, वहाँ से बटन दबा दीजिए।")
    }

    // ------------------------------------------------------------------ F. अलार्म / टाइमर

    private val NUM_WORDS = linkedMapOf(
        "बारह" to 12, "ग्यारह" to 11, "दस" to 10, "नौ" to 9, "आठ" to 8, "सात" to 7, "छह" to 6, "छः" to 6, "छे" to 6, "पांच" to 5,
        "चार" to 4, "तीन" to 3, "दो" to 2, "एक" to 1,
        "twelve" to 12, "eleven" to 11, "ten" to 10, "nine" to 9, "eight" to 8, "seven" to 7, "six" to 6, "five" to 5,
        "four" to 4, "three" to 3, "two" to 2, "one" to 1,
        "पैंतालीस" to 45, "चालीस" to 40, "पैंतीस" to 35, "तीस" to 30, "पच्चीस" to 25, "बीस" to 20, "पंद्रह" to 15,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "पचास" to 50, "साठ" to 60)

    private val UNIT_NEXT = setOf("बजे", "बजकर", "बज", "baje", "bajkar", "o'clock", "oclock", "am", "pm", "घंटे", "घंटा", "ghante", "ghanta",
        "मिनट", "minat", "minute", "minutes", "min", "सेकंड", "सेकेंड", "second", "seconds", "hour", "hours")
    private val UNIT_PREV = setOf("साढे", "सवा", "पौने", "sadhe", "saade", "sawa", "paune", "बजकर", "bajkar", "at")

    /** "सात बजे" -> "7 बजे". सिर्फ़ जब आगे इकाई (बजे/मिनट/घंटे) या पीछे साढ़े/सवा हो - "लगा दो" का "दो" अंक नहीं */
    fun numbersToDigits(t: String): String {
        val w = t.split(' ')
        return w.indices.joinToString(" ") { i ->
            val n = NUM_WORDS[w[i]]
            if (n != null && (w.getOrNull(i + 1) in UNIT_NEXT || w.getOrNull(i - 1) in UNIT_PREV)) n.toString() else w[i]
        }
    }

    data class Clock(val hour: Int, val minute: Int)

    /**
     * बोली बात से अलार्म का समय (24 घंटे). सुबह/दोपहर/शाम/रात, am/pm, "साढ़े 7", "सवा 7", "पौने 7", "7 बजकर 20 मिनट", "7:20".
     * am/pm न बोले = अगला आने वाला वह समय ([nowH]:[nowM] के बाद).
     */
    fun alarmTime(raw: String, nowH: Int, nowM: Int): Clock? {
        var t = numbersToDigits(clean(raw)).replace("डेढ़", "साढ़े 1").replace("डेढ", "साढ़े 1").replace("ढाई", "साढ़े 2")
        t = t.replace(Regex("(\\d)\\s*(बजे|baje|बजकर|bajkar|o'clock|oclock)"), "$1 $2")
        var h: Int; var m = 0
        val hm = Regex("(?<![\\d.])(\\d{1,2})\\s*[:.]\\s*(\\d{2})(?!\\d)").find(t)
        val bajkar = Regex("(\\d{1,2}) (?:बजकर|bajkar|baj kar|बज के|बजके) (\\d{1,2})").find(t)
        val hour = Regex("(?<![\\d.:])(\\d{1,2})(?![\\d:])").find(t)
        when {
            hm != null -> { h = hm.groupValues[1].toInt(); m = hm.groupValues[2].toInt() }
            bajkar != null -> { h = bajkar.groupValues[1].toInt(); m = bajkar.groupValues[2].toInt() }
            hour != null -> h = hour.groupValues[1].toInt()
            else -> return null
        }
        val pre = Regex("(साढ़े|साढे|sadhe|saade|सवा|sawa|पौने|paune)\\s*$h(?!\\d)").find(t)?.groupValues?.get(1)
        when {
            pre == null -> {}
            pre.startsWith("सा") || pre.startsWith("sa") && pre != "sawa" -> m = 30
            pre == "सवा" || pre == "sawa" -> m = 15
            else -> { m = 45; h -= 1; if (h == 0) h = 12 }
        }
        if (h !in 0..23 || m !in 0..59) return null
        val pm = has(t, listOf("शाम", "रात", "दोपहर", "pm", "p m", "evening", "night", "afternoon", "shaam", "raat", "dopahar"))
        val am = has(t, listOf("सुबह", "सवेरे", "am", "a m", "morning", "subah", "savere", "भोर"))
        if (h in 1..12) {
            when {
                has(t, listOf("रात", "raat", "night")) && h in 1..4 -> {}                 // रात 2 बजे = 2:00
                has(t, listOf("रात", "raat", "night")) && h == 12 -> h = 0
                has(t, listOf("दोपहर", "dopahar", "afternoon")) && h == 12 -> {}
                pm -> if (h != 12) h += 12
                am -> if (h == 12) h = 0
                else -> {
                    // कुछ नहीं बोला: अगले 12 घंटे में जो पहले आए
                    val a1 = h % 12; val a2 = a1 + 12
                    val nowMin = nowH * 60 + nowM
                    fun ahead(x: Int) = ((x * 60 + m) - nowMin + 1440) % 1440
                    h = if (ahead(a1) <= ahead(a2)) a1 else a2
                }
            }
        }
        return Clock(h, m)
    }

    private val ALARM = listOf("अलार्म", "alarm", "अलार्म", "उठा देना", "उठा दो", "जगा देना", "जगा दो", "wake me", "utha dena", "jaga dena")
    private val OFF = listOf("बंद", "हटा", "डिलीट", "मिटा", "कैंसल", "cancel", "off", "stop", "रोको", "delete", "remove", "band")

    private fun alarm(t: String): QuickCommands.Quick? {
        if (!has(t, ALARM) || has(t, OFF) || has(t, listOf("कितने", "कौन सा", "kitne", "list", "दिखाओ", "देखो"))) return null
        // "10 मिनट बाद अलार्म" = घड़ी का समय नहीं (AI / टाइमर)
        if (has(t, listOf("मिनट बाद", "घंटे बाद", "minute baad", "minutes baad", "in 1", "in 2", "in 3", "in 4", "in 5", "in 6", "in 7", "in 8", "in 9", "minutes later", "hour later"))) return null
        val set = has(t, listOf("लगा", "लगाओ", "सेट", "set", "laga", "रख", "कर दो", "करो", "उठा", "जगा", "wake", "utha", "jaga"))
        if (!set) return null
        val c = Calendar0.now()
        val time = alarmTime(t, c.first, c.second) ?: return q("alarm_set", "ask" to true)
        return q("alarm_set", "hour" to time.hour, "minute" to time.minute)
    }

    /** टाइमर के सेकंड: "5 मिनट", "डेढ़ घंटा", "30 सेकंड", "1 घंटा 20 मिनट", "आधा घंटा" */
    fun timerSeconds(raw: String): Int? {
        var t = numbersToDigits(clean(raw))
        t = t.replace("आधा घंटा", "30 मिनट").replace("आधे घंटे", "30 मिनट").replace("half an hour", "30 minutes")
            .replace("डेढ़ घंटे", "90 मिनट").replace("डेढ़ घंटा", "90 मिनट").replace("डेढ घंटे", "90 मिनट").replace("डेढ घंटा", "90 मिनट")
            .replace("ढाई घंटे", "150 मिनट").replace("ढाई मिनट", "150 सेकंड").replace("डेढ़ मिनट", "90 सेकंड").replace("डेढ मिनट", "90 सेकंड")
        var sec = 0; var any = false
        Regex("(\\d{1,3})\\s*(घंटे|घंटा|hours|hour|hrs|hr|ghante|ghanta)").findAll(t).forEach { sec += it.groupValues[1].toInt() * 3600; any = true }
        Regex("(\\d{1,3})\\s*(मिनट|minutes|minute|mins|min|minat)").findAll(t).forEach { sec += it.groupValues[1].toInt() * 60; any = true }
        Regex("(\\d{1,3})\\s*(सेकंड|सेकेंड|seconds|second|secs|sec)").findAll(t).forEach { sec += it.groupValues[1].toInt(); any = true }
        return if (any && sec in 1..86_399) sec else null
    }

    private fun timer(t: String): QuickCommands.Quick? {
        if (!has(t, listOf("टाइमर", "timer", "countdown", "काउंटडाउन"))) return null
        if (has(t, OFF)) return null
        val s = timerSeconds(t) ?: return q("timer_set", "ask" to true)
        return q("timer_set", "seconds" to s)
    }

    // ------------------------------------------------------------------ F. कैमरा

    private fun camera(t: String): QuickCommands.Quick? {
        val selfie = has(t, listOf("सेल्फी", "selfie", "फ्रंट कैमरा", "front camera"))
        val photoWord = has(t, listOf("फोटो", "फ़ोटो", "photo", "pic", "तस्वीर", "picture", "पिक्चर"))
        val takeVerb = has(t, listOf("खींचो", "खींच", "खिंचो", "खीच", "ले लो", "लो", "लीजिए", "click", "take", "kheencho", "khicho", "capture", "निकालो"))
        if (photoWord && has(t, listOf("दिखा", "show", "खोल", "open", "भेज", "send", "गैलरी", "gallery", "डिलीट", "मिटा", "आखिरी", "last", "latest"))) return null
        if ((photoWord || selfie) && takeVerb) return q("camera", "photo" to true, "front" to selfie)
        if (has(t, listOf("कैमरा", "camera", "कैमरे")) && has(t, listOf("खोल", "open", "चालू", "kholo", "start", "on"))) return q("camera", "photo" to false, "front" to selfie)
        if (selfie && t.split(' ').size <= 3) return q("camera", "photo" to true, "front" to true)
        return null
    }

    // ------------------------------------------------------------------ F. समय / तारीख / बैटरी

    private val ASKING = listOf("क्या", "कितने", "कितना", "कितनी", "कौन", "बताओ", "बता", "what", "how much", "tell", "kya", "kitne", "kitni", "kaun", "batao", "हुआ", "हुए", "है")
    private fun info(t: String): QuickCommands.Quick? {
        if (t.split(' ').size > 7) return null
        if (!has(t, ASKING) && !has(t, listOf("time", "टाइम", "समय"))) return null
        val battery = has(t, listOf("बैटरी", "battery")) ||
            has(t, listOf("कितना चार्ज", "चार्ज कितना", "charge kitna", "kitna charge", "how much charge"))
        if (battery) {
            if (has(t, listOf("सेविंग", "saver", "saving", "सेटिंग", "setting", "मोड", "mode", "क्यों", "why", "बचा", "बढा", "खराब", "जल्दी खत्म"))) return null
            return q("info", "what" to "battery")
        }
        if (has(t, listOf("कितने बजे", "कितना बजा", "कितने बज", "kitne baje", "kitna baja", "what time", "time kya", "टाइम क्या", "टाइम कितना",
                "समय क्या", "समय कितना", "time बताओ", "टाइम बताओ", "समय बताओ", "time batao", "current time", "time hua", "टाइम हुआ", "time is it")))
            return if (has(t, listOf("अमेरिका", "london", "लंदन", "दुबई", "dubai", "america", "usa", "japan", "में ", " in "))) null else q("info", "what" to "time")
        if (has(t, listOf("कौन सा दिन", "कौनसा दिन", "क्या दिन", "kaun sa din", "what day", "which day", "आज दिन", "aaj din", "वार क्या")))
            return q("info", "what" to "day")
        if (has(t, listOf("तारीख", "तिथि", "डेट", "date", "tarikh", "tareekh")) && !has(t, listOf("जन्म", "birth", "बर्थ", "चुनाव", "exam", "रिलीज", "release", "छुट्टी")))
            return q("info", "what" to "date")
        return null
    }

    // ------------------------------------------------------------------ C. स्क्रीन पर लिखे पर टैप

    private val TAP = Regex("^(?:स्क्रीन पर |screen pe |screen par )?(.{1,40}?) (?:पर|पे|par|pe|on|को) (?:टैप|tap|क्लिक|click|प्रेस|press|दबाओ|दबा दो|दबाइए|daba do|dabao)(?: करो| कर दो| करिए| karo| kar do| कीजिए)?$")
    private val TAP_EN = Regex("^(?:tap|click|press)(?: on)? (.{1,40})$")

    private fun tapText(t: String): QuickCommands.Quick? {
        val m = TAP.find(t)?.groupValues?.get(1) ?: TAP_EN.find(t)?.groupValues?.get(1) ?: return null
        val x = m.trim().removePrefix("बटन ").removeSuffix(" बटन").removeSuffix(" button").removeSuffix(" वाले").removeSuffix(" वाला").trim()
        if (x.isBlank() || x.split(' ').size > 5) return null
        // "डिलीट पर टैप" / "पेमेंट पर क्लिक" जैसे ख़तरनाक बटन = पुराना रास्ता (वहाँ पक्की पुष्टि)
        if (has(x, listOf("डिलीट", "delete", "मिटा", "remove", "हटा", "reset", "रीसेट", "pay", "पे करें", "पेमेंट", "buy", "खरीद", "order", "ऑर्डर", "uninstall", "अनइंस्टॉल", "format", "erase")))
            return null
        return q("tap_text", "text" to x)
    }

    // ------------------------------------------------------------------ F. SMS (पहले बोलकर पूछो)

    private val SMS = listOf("एसएमएस", "sms", "एस एम एस", "s m s", "text message", "टेक्स्ट मैसेज")
    private val SMS_RX = Regex("^(.{1,30}?) (?:को|ko|to) (?:एक )?(?:एसएमएस|sms|एस एम एस|s m s|text message|टेक्स्ट मैसेज)(?: भेजो| भेज दो| करो| कर दो| send करो| send karo| bhejo| bhej do| send)?(?: कि| की| ki| that|:)? ?(.*)$")
    private val SMS_EN = Regex("^(?:send )?(?:an? )?(?:sms|text message|text) to (.{1,30}?)(?: saying| that|:)? (.+)$")

    /** पूरी बात (और/फिर पर टुकड़े करने से पहले) - SMS का मैसेज ख़ुद "और" रख सकता है */
    fun whole(raw: String): QuickCommands.Quick? {
        val t0 = raw.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "").trim().trim('.', '।', '!')
        val low = t0.lowercase()
        if (!has(low, SMS)) return null
        if (has(" $low ", NEG) && !Regex("(कि|ki|that) .*मत").containsMatchIn(low)) return null
        var name: String; var text: String
        val m = SMS_RX.find(low)
        if (m != null) {
            name = m.groupValues[1]; text = t0.substring(t0.length - m.groupValues[2].length).trim()
        } else {
            val e = SMS_EN.find(low) ?: return null
            name = e.groupValues[1]; text = t0.substring(t0.length - e.groupValues[2].length).trim()
        }
        name = name.replace(Regex("^(?:डीके|dk|हे|hey)\\s+"), "").trim()
        text = text.replace(Regex("\\s*(भेज दो|भेजो|send kar do|send karo|send)$", RegexOption.IGNORE_CASE), "").trim().trim('"')
        if (name.isBlank() || name.split(' ').size > 4) return null
        return q("sms_send", "name" to name, "text" to text)
    }

    // ------------------------------------------------------------------ हाँ / ना वाली पुष्टि (SMS)

    data class Pending(val kind: String, val number: String, val name: String, val text: String, val until: Long)
    @Volatile var pending: Pending? = null
    const val CONFIRM_MS = 30_000L

    private val YES = listOf("हां", "हाँ", "हा", "haan", "han", "ha", "yes", "yeah", "ok", "okay", "ओके", "ठीक है", "भेज दो", "भेजो", "send", "send karo",
        "कर दो", "हां भेजो", "हाँ भेजो", "जी हां", "बिल्कुल", "sure", "हां भेज दो", "हाँ भेज दो", "हां sms भेजो", "हाँ, sms भेजो", "हाँ sms भेजो")
    private val NO = listOf("नहीं", "ना", "nahi", "nahin", "no", "mat", "मत भेजो", "मत", "रहने दो", "cancel", "कैंसल", "रुको", "stop", "नहीं भेजो", "sms मत भेजो")

    /** SMS के "भेजूँ?" के बाद: हाँ = sms_confirm(yes), ना = sms_confirm(no). कुछ और = null (पूछा हुआ भूल जाओ नहीं) */
    fun confirm(raw: String, now: Long): QuickCommands.Quick? {
        val p = pending ?: return null
        if (now > p.until) { pending = null; return null }
        val t = clean(raw).replace(Regex("^(?:डीके|dk|डी के)\\s+"), "").trim().trim('.')
        if (t.isBlank() || t.split(' ').size > 5) return null
        val no = NO.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") } || t.contains("मत भेज") || t.contains("नहीं भेज")
        val yes = !no && YES.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") }
        if (!yes && !no) return null
        return q("sms_confirm", "yes" to yes)
    }

    /** SMS के ठीक पहले पूछने वाला वाक्य */
    fun askSms(name: String, text: String) = "सर, $name को यह SMS भेजूँ: \"$text\"? हाँ या ना बोलिए।"
}

/** JVM test में भी चलने वाली अभी की घड़ी (घंटा, मिनट) */
internal object Calendar0 {
    @Volatile var fixed: Pair<Int, Int>? = null
    fun now(): Pair<Int, Int> = fixed ?: java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) to it.get(java.util.Calendar.MINUTE) }
}
