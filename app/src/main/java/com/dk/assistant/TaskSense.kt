package com.dk.assistant

/**
 * नवंबर 2026 (मालिक): "DK में यह समझ होनी चाहिए कि कौन-सी बात कमांड है, कौन-सी सवाल, कौन-सी जवाब.
 * 'WhatsApp पर मैसेज भेजो' - किसे? क्या? न बोला हो तो पूछे. 'DK, WhatsApp पर धीरज कुमार को हेलो मैसेज भेजो' = सीधे भेजे."
 *
 * यह परत (pure, बिना Android - JVM test) हर बात को पहले समझती है:
 *  - [kind]: काम (TASK) / सवाल (QUESTION) / DK के पूछे सवाल का जवाब (REPLY) / आम बातचीत (CHAT)
 *  - [need]: काम है पर ज़रूरी चीज़ छूटी (किसे, क्या, कब, कौन-सा ऐप, क्या सर्च, कहाँ) = वही एक छोटा सवाल
 *  - [answer]: पूछे सवाल का जवाब भरो - सब भर गया = पूरा साफ़ हुक्म ([Need.command]) जो पुराने रास्ते से चलता है
 *
 * नियम: पक्का दिखे कि चीज़ छूटी है तभी पूछो (नाम / संदेश बोला है तो दोबारा नहीं). शक हो = पहले जैसा (AI / फ़ोन समझे).
 */
object TaskSense {
    enum class Kind { TASK, QUESTION, REPLY, CHAT }

    /** AI (Live + बाकी) के लिए वही समझ - हर बात पहले पहचानो */
    const val PROMPT = "UNDERSTAND FIRST (owner's rule): before answering, decide what the user's message is. " +
        "(1) A TASK (he wants something DONE - send, call, open, play, set, remind, search, navigate...): if every needed detail is there, do it at once with the right tool. " +
        "If a REQUIRED detail is missing - WHO (contact), WHAT (message text), WHEN (time), WHICH app, WHAT to search, WHERE to go - ask ONLY for that one thing in one short line " +
        "(e.g. 'WhatsApp पर मैसेज भेजो' -> 'किसे मैसेज भेजूँ?', then 'धीरज कुमार को क्या मैसेज भेजूँ?'). Never guess or invent a contact or message, and never ask for what he already said " +
        "('WhatsApp पर धीरज कुमार को हेलो मैसेज भेजो' = send at once, no question). " +
        "(2) A QUESTION (he wants to KNOW something - क्या/कैसे/क्यों/कब/कौन/कितना, 'बताओ', 'समझाओ'): answer it; do not run a phone task unless the answer needs one. " +
        "'क्या तुम WhatsApp खोल सकते हो?' = a question about ability - say yes and offer, or do it if he clearly wants it. " +
        "(3) A REPLY to the question YOU just asked (a name, a time, a message, हाँ/ना): it is NOT a new topic - fill it into the pending task and finish that task now. " +
        "(4) CHAT (feelings, small talk, opinions): talk back naturally and warmly, no tools."

    enum class Task { MESSAGE, CALL, REMINDER, OPEN_APP, SEARCH, NAVIGATE, EMAIL }
    enum class Slot { CONTACT, TEXT, WHEN, APP, QUERY, PLACE }

    /** एक अधूरा काम: क्या पता है ([have]) और अभी क्या पूछना है ([missing] का पहला) */
    data class Need(
        val task: Task,
        val have: Map<Slot, String>,
        val missing: List<Slot>,
        /** "WhatsApp" / "SMS" / "Telegram" / "YouTube" / "Google" / "" */
        val via: String = "",
        val video: Boolean = false,
    ) {
        val done get() = missing.isEmpty()
        val question get() = ask(this)
        /** सब भर गया = पुराने रास्ते (LocalRouter / AI / whatsapp_task) के लिए साफ़ हुक्म */
        val command get() = build(this)
    }

    /** जवाब का नतीजा: भरा (शायद अभी और पूछना), रद्द, या यह तो नया हुक्म है */
    sealed class Reply {
        data class Filled(val need: Need) : Reply()
        object Cancel : Reply()
        data class NewCommand(val text: String) : Reply()
    }

    // ---------------- शब्द ----------------
    private const val WAKE = "(?:hey\\s+|ok\\s+|अरे\\s+|हे\\s+)?(?:डीके|डी\\s*के|दीके|डिके|डीजे|dk|d\\.?\\s*k\\.?|जार्विस|jarvis)"
    private val WAKE_RX = Regex("^\\s*$WAKE[\\s,।!.]*", RegexOption.IGNORE_CASE)
    private val YEH_DK = Regex("^\\s*(?:यह|ये|ye|yeh)\\s+(?:डीके|dk)\\s+(?:है|hai)[\\s,।]*", RegexOption.IGNORE_CASE)

    private const val MSG = "(?:मैसेज|मेसेज|मैसेज़|मेसेज़|मसेज|संदेश|message|msg|sms|एसएमएस|टेक्स्ट|text)"
    private const val SEND = "(?:भेज\\S*|कर\\s*दो|करो|कर|करना|कीजिए|करिए|send|bhej\\S*|karo|kar\\s*do|लिख\\S*|डाल\\S*)"
    private val MSG_SEND = Regex("$MSG\\s*$SEND", RegexOption.IGNORE_CASE)
    private val SEND_MSG = Regex("(?:send|भेजो|भेज\\s*दो)\\s+(?:a\\s+)?$MSG", RegexOption.IGNORE_CASE)
    private val MSG_READ = Regex("(पढ़|पढो|पढ़ो|read|दिखा|unread|किसका|किसके|आया|आए|आये|कितने)", RegexOption.IGNORE_CASE)

    private val APP_ON = Regex("(?:^|\\s)(व्हाट्सएप|व्हाट्सऐप|व्हाट्सअप|वॉट्सऐप|वाट्सएप|वाट्सअप|वाट्सप|whatsapp|whats\\s*app|टेलीग्राम|telegram|इंस्टाग्राम|instagram|insta|फेसबुक|facebook|messenger|एसएमएस|sms)\\s*(?:पर|पे|से|में|par|pe|se|on|में\\s*से)?(?=\\s|$)", RegexOption.IGNORE_CASE)

    private const val CALL_W = "(?:कॉल|काल|call|फोन|फ़ोन|phone)"
    private const val CALL_DO = "(?:करो|कर\\s*दो|कर|लगाओ|लगा\\s*दो|लगाना|मिलाओ|मिला\\s*दो|कीजिए|करिए|karo|kar\\s*do|lagao|laga\\s*do|milao)"
    private val CALL_RX = Regex("(?:वीडियो\\s*|video\\s*|ऑडियो\\s*|वॉइस\\s*|voice\\s*)?$CALL_W\\s*$CALL_DO", RegexOption.IGNORE_CASE)
    private val CALL_EN = Regex("^\\s*(?:make\\s+a\\s+|place\\s+a\\s+)?(?:video\\s+)?call\\s*(?:to\\s+)?", RegexOption.IGNORE_CASE)
    private val CALL_NOT = Regex("(उठा|काट|कट|रिसीव|receive|answer|reject|end|बंद|आया|आई|आ\\s*रहा|किसका|कौन|लॉक|lock|अनलॉक|unlock|चालू|ऑन|ऑफ|on\\b|off\\b|स्विच|switch|रिकॉर्ड|record|स्पीकर|speaker|म्यूट|mute|होल्ड|hold)", RegexOption.IGNORE_CASE)
    private val VIDEO = Regex("(वीडियो|video)", RegexOption.IGNORE_CASE)

    private val REMIND = Regex("(याद\\s*दिला\\S*|रिमाइंडर|रिमाइंड|reminder|remind)", RegexOption.IGNORE_CASE)
    private val TIME = Regex("(\\d|बजे|baje|मिनट|minute|min\\b|घंटे|घंटा|hour|सेकंड|second|बाद|later|कल|आज|परसों|सुबह|शाम|रात|दोपहर|tomorrow|today|tonight|morning|evening|night|\\bam\\b|\\bpm\\b|सोमवार|मंगलवार|बुधवार|गुरुवार|शुक्रवार|शनिवार|रविवार|monday|tuesday|wednesday|thursday|friday|saturday|sunday|एक|दो|तीन|चार|पांच|पाँच|छह|सात|आठ|नौ|दस|ग्यारह|बारह|आधे|डेढ़|ढाई)", RegexOption.IGNORE_CASE)

    private val OPEN_APP = Regex("^\\s*(?:कोई\\s+|एक\\s+)?(?:ऐप|एप|एप्प|app|application|एप्लीकेशन)\\s*(?:खोलो|खोल\\s*दो|खोलना|ओपन\\s*करो|ओपन|open|kholo|chalu\\s*karo|चालू\\s*करो)\\s*$|^\\s*open\\s+(?:an?\\s+)?app\\s*$", RegexOption.IGNORE_CASE)

    private val SEARCH = Regex("(सर्च|search|ढूँढ\\S*|ढूंढ\\S*|खोजो|खोज\\s*दो|find)", RegexOption.IGNORE_CASE)
    private val SEARCH_SITE = Regex("(यूट्यूब|youtube|यू\\s*ट्यूब|गूगल|google|क्रोम|chrome|फ्लिपकार्ट|flipkart|अमेज़न|अमेजन|amazon|मैप्स?|maps?)", RegexOption.IGNORE_CASE)

    private val NAV = Regex("(रास्ता|रस्ता|नेविगेशन|navigation|navigate|directions|डायरेक्शन|ले\\s*चलो|लेके\\s*चलो)", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("(ईमेल|ई-मेल|इमेल|email|e-mail|जीमेल|gmail|मेल\\s+(?:भेज|कर|लिख))", RegexOption.IGNORE_CASE)

    private val CANCEL = Regex("^\\s*(रहने\\s*दो|रहने\\s*दीजिए|छोड़ो|छोड़\\s*दो|छोडो|कैंसल|cancel|नहीं\\s*भेजना|मत\\s*भेजो|मत\\s*करो|नहीं\\s*करना|कुछ\\s*नहीं|नहीं|no|nahi|nahin|stop|रुको|बस)\\s*[.!।]*\\s*$", RegexOption.IGNORE_CASE)
    private val NEG = Regex("(?:^|\\s)(मत|नहीं|नही|don't|dont|do\\s+not|mat|nahi)(?:\\s|$)", RegexOption.IGNORE_CASE)

    private val Q_WORD = Regex("(?:^|\\s)(क्या|क्यों|क्यूँ|कैसे|कैसा|कैसी|कब|कहाँ|कहां|किधर|कौन|कौनसा|कौन\\s*सा|कितना|कितने|कितनी|किसने|किसका|किसको|kya|kyu|kyon|kaise|kab|kahan|kaun|kitna|what|why|how|when|where|who|which|is|are|do|does|can|could|should|will)(?=\\s|$|\\?)", RegexOption.IGNORE_CASE)
    private val TELL = Regex("(बताओ|बताइए|बता\\s*दो|बताना|समझाओ|समझा\\s*दो|explain|tell\\s+me|batao)", RegexOption.IGNORE_CASE)
    private val DO_VERB = Regex("(?:^|\\s)(करो|कर\\s*दो|कीजिए|करिए|करना|भेजो|भेज\\s*दो|खोलो|खोल\\s*दो|लगाओ|लगा\\s*दो|चलाओ|चला\\s*दो|बजाओ|बंद\\s*करो|चालू\\s*करो|बढ़ाओ|बढ़ा\\s*दो|घटाओ|कम\\s*करो|ज़्यादा\\s*करो|सेट\\s*करो|डिलीट|मिटाओ|हटाओ|दिखाओ|ढूँढो|ढूंढो|खोजो|सर्च|डाउनलोड|शेयर|ले\\s*लो|लो|उठाओ|काटो|पढ़ो|सुनाओ|बनाओ|बना\\s*दो|जलाओ|जला\\s*दो|बुझाओ|बुझा\\s*दो|जगाओ|रोको|रोक\\s*दो|हटा\\s*दो|लगा\\s*दो|सुनाओ|गाओ|याद\\s*दिला\\S*|open|send|call|play|set|turn|start|stop|close|show|search|find|delete|download|share|make|create|book|order|karo|kar\\s*do|kholo|bhejo|lagao|chalao)(?=\\s|$|[,.!।])", RegexOption.IGNORE_CASE)

    // ---------------- 1. बात का प्रकार ----------------

    /**
     * [dkAsked] = DK की आख़िरी बात (सवाल था? तो छोटा जवाब = REPLY). [askedAgoMs] = वह कितनी देर पहले बोली.
     * ध्यान: "मौसम कैसा है बताओ" = QUESTION (जानकारी), "WhatsApp खोलो" = TASK, "धीरज कुमार" (DK ने "किसे भेजूँ?" पूछा था) = REPLY.
     */
    fun kind(text: String, dkAsked: String = "", askedAgoMs: Long = Long.MAX_VALUE): Kind {
        val t = strip(text)
        if (t.isBlank()) return Kind.CHAT
        val words = t.split(Regex("\\s+")).size
        val asked = dkAsked.trim().let { it.endsWith("?") || it.endsWith("？") || Regex("(किसे|किसको|क्या\\s+(?:भेजूँ|भेजूं|लिखूँ|लिखूं|बोलूँ|सर्च)|कब|कौन|कितने|कहाँ|कहां)[^।.]*$").containsMatchIn(it) }
        if (asked && askedAgoMs < 90_000 && words <= 12 && detect(t) == null && !(Q_WORD.containsMatchIn(t) && t.endsWith("?"))) return Kind.REPLY
        if (detect(t) != null) return Kind.TASK
        val q = t.endsWith("?") || t.endsWith("？") || Q_WORD.containsMatchIn(t) || TELL.containsMatchIn(t)
        val doIt = DO_VERB.containsMatchIn(t)
        return when {
            doIt && !Q_WORD.containsMatchIn(t) && !TELL.containsMatchIn(t) -> Kind.TASK
            q -> Kind.QUESTION
            doIt -> Kind.TASK
            else -> Kind.CHAT
        }
    }

    // ---------------- 2. काम की पहचान + छूटी चीज़ें ----------------

    /** काम है और कुछ ज़रूरी छूटा = [Need] (पहला सवाल [Need.question]); पूरा / पहचान नहीं / शक = null (पहले जैसा) */
    fun need(text: String): Need? = detect(strip(text))?.takeIf { !it.done }

    /** काम पहचानो (भरा हो या अधूरा) */
    fun detect(text0: String): Need? {
        val text = strip(text0)
        if (text.isBlank() || text.length > 160) return null
        if (NEG.containsMatchIn(text)) return null
        // "क्या तुम मैसेज भेज सकते हो?" = क्षमता का सवाल / विनम्र हुक्म - पहले जैसा (AI समझे), सवाल-जवाब यहाँ नहीं
        if (text.endsWith("?") || Regex("(सकते|सकती|सकता|सकोगे|सकोगी|can\\s+you|could\\s+you|will\\s+you)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        // बहुत सारे काम एक साथ ("अलार्म लगाओ फिर मैसेज भेजो") = कतार (TaskQueue) संभाले
        if (Regex("(?:^|\\s)(फिर|उसके\\s+बाद|and\\s+then|then)(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return email(text) ?: message(text) ?: call(text) ?: reminder(text) ?: openApp(text) ?: search(text) ?: navigate(text)
    }

    private fun message(t: String): Need? {
        if (!(MSG_SEND.containsMatchIn(t) || SEND_MSG.containsMatchIn(t))) return null
        if (MSG_READ.containsMatchIn(t) && !Regex("भेज|send|bhej", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val via = via(t)
        var s = APP_ON.replace(" $t ", " ")
        // "धीरज कुमार का मैसेज करो" (सुनने में "को" की जगह "का") = "को"
        s = Regex("\\s+(?:का|के|की|ka|ke)\\s+(?=(?:एक\\s+)?$MSG)", RegexOption.IGNORE_CASE).replace(s, " को ")
        val (c, m) = contactAndText(s)
        if (c == null) {
            // "को" नहीं: बचा शब्द नाम है या संदेश - पक्का नहीं = पहले जैसा (AI समझे). कुछ बचा ही नहीं = दोनों पूछो
            val left = clean(removeMsgWords(s))
            if (left.isNotBlank()) return null
            return Need(Task.MESSAGE, emptyMap(), listOf(Slot.CONTACT, Slot.TEXT), via)
        }
        val have = mutableMapOf(Slot.CONTACT to c)
        if (m.isNotBlank()) have[Slot.TEXT] = m
        return Need(Task.MESSAGE, have, if (m.isBlank()) listOf(Slot.TEXT) else emptyList(), via)
    }

    private fun email(t: String): Need? {
        if (!EMAIL.containsMatchIn(t)) return null
        if (!Regex("भेज|send|लिख|write|कर\\s*दो|करो|bhej", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        if (MSG_READ.containsMatchIn(t) && !Regex("भेज|send|bhej|लिख", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val s = " " + EMAIL.replace(Regex("(?:जीमेल|gmail)\\s*(?:पर|से|में|on)?", RegexOption.IGNORE_CASE).replace(t, " "), " ") + " "
        val ko = Regex("^\\s*(.*?)\\s+(?:को|ko|to)\\s+(.*)$", RegexOption.IGNORE_CASE).find(s)
        if (ko == null) {
            val left = clean(removeMsgWords(s))
            if (left.isNotBlank()) return null
            return Need(Task.EMAIL, emptyMap(), listOf(Slot.CONTACT, Slot.TEXT))
        }
        val c = name(ko.groupValues[1]); val m = msgText(ko.groupValues[2])
        if (c.isBlank()) return Need(Task.EMAIL, if (m.isBlank()) emptyMap() else mapOf(Slot.TEXT to m), listOfNotNull(Slot.CONTACT, if (m.isBlank()) Slot.TEXT else null))
        return Need(Task.EMAIL, mapOf(Slot.CONTACT to c) + if (m.isBlank()) emptyMap() else mapOf(Slot.TEXT to m), if (m.isBlank()) listOf(Slot.TEXT) else emptyList())
    }

    private fun call(t: String): Need? {
        val hit = CALL_RX.find(t) ?: CALL_EN.find(t)?.takeIf { it.value.isNotBlank() && t.trim().lowercase().startsWith("call") || it.value.contains("make", true) || it.value.contains("place", true) }
        if (hit == null) return null
        if (CALL_NOT.containsMatchIn(t)) return null
        val via = via(t)
        val video = VIDEO.containsMatchIn(t)
        var s = APP_ON.replace(" $t ", " ")
        s = s.replace(CALL_RX, " ").replace(CALL_EN, " ")
        s = Regex("(?:^|\\s)(वीडियो|video|ऑडियो|वॉइस|voice|का|की|के|को|ko|ka|ki|ke|to|मेरे|मेरी|मेरा|my|ज़रा|जरा|please|प्लीज|अभी|abhi)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(" $s ", " ")
        val c = name(s)
        return if (c.isBlank()) Need(Task.CALL, emptyMap(), listOf(Slot.CONTACT), via, video)
        else Need(Task.CALL, mapOf(Slot.CONTACT to c), emptyList(), via, video)
    }

    private fun reminder(t: String): Need? {
        if (!REMIND.containsMatchIn(t)) return null
        if (Regex("(कौन|कितने|दिखा|सारे|list|हटा|delete|डिलीट|बंद|cancel)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        var s = REMIND.replace(" $t ", " ")
        s = Regex("(?:^|\\s)(मुझे|मुझको|me|mujhe|एक|a|लगाओ|लगा\\s*दो|लगाना|सेट|set|करो|कर\\s*दो|करना|दो|देना|दीजिए|please|प्लीज|ज़रा|जरा)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(" $s ", " ")
        s = clean(s)
        val hasTime = TIME.containsMatchIn(s)
        val what = clean(stripTime(s)).removeSuffix(" की").removeSuffix(" का").removeSuffix(" के").trim().removePrefix("की ").removePrefix("कि ").trim()
        val have = mutableMapOf<Slot, String>()
        if (what.isNotBlank()) have[Slot.TEXT] = what
        if (hasTime) have[Slot.WHEN] = timePart(s)
        val miss = listOfNotNull(if (what.isBlank()) Slot.TEXT else null, if (!hasTime) Slot.WHEN else null)
        return Need(Task.REMINDER, have, miss)
    }

    private fun openApp(t: String): Need? =
        if (OPEN_APP.containsMatchIn(t)) Need(Task.OPEN_APP, emptyMap(), listOf(Slot.APP)) else null

    private fun search(t: String): Need? {
        if (!SEARCH.containsMatchIn(t)) return null
        val site = SEARCH_SITE.find(t)?.value.orEmpty()
        var s = " " + SEARCH_SITE.replace(SEARCH.replace(t, " "), " ") + " "
        s = Regex("(?:^|\\s)(पर|पे|में|से|par|pe|me|on|in|कुछ|something|करो|कर\\s*दो|करना|कीजिए|दो|लो|for|करके|बताओ)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(s, " ")
        val q = clean(s)
        if (q.isNotBlank()) return null          // क्या सर्च करना है बोला = पहले जैसा
        return Need(Task.SEARCH, emptyMap(), listOf(Slot.QUERY), viaSite(site))
    }

    private fun navigate(t: String): Need? {
        if (!NAV.containsMatchIn(t)) return null
        var s = " " + NAV.replace(t, " ") + " "
        s = Regex("(?:^|\\s)(का|की|के|मुझे|me|बताओ|बता\\s*दो|दिखाओ|शुरू|चालू|करो|कर\\s*दो|start|show|to|for|मैप्स?|maps?|पर|में|google|गूगल)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(s, " ")
        val p = clean(s)
        if (p.isNotBlank()) return null
        return Need(Task.NAVIGATE, emptyMap(), listOf(Slot.PLACE))
    }

    // ---------------- 3. जवाब भरना ----------------

    /** DK ने [n].question पूछा था, user ने [answer] कहा */
    fun answer(n: Need, answer: String): Reply {
        val a = strip(answer)
        if (a.isBlank() || CANCEL.matches(a)) return Reply.Cancel
        // जवाब में ही पूरा नया काम ("नहीं, मम्मी को कॉल करो") = नया हुक्म
        val fresh = Regex("^\\s*(?:नहीं|नही|no|nahi|अरे\\s+नहीं|रुको)[\\s,।]+", RegexOption.IGNORE_CASE).replace(a, "").trim()
        detect(fresh)?.let { d -> if (d.task != n.task || fresh.split(Regex("\\s+")).size > 4) return Reply.NewCommand(fresh) }
        val slot = n.missing.firstOrNull() ?: return Reply.Filled(n)
        val have = n.have.toMutableMap()
        when (slot) {
            Slot.CONTACT -> {
                // "धीरज कुमार को हेलो बोलो" = नाम + संदेश एक साथ
                val (c, m) = contactAndText(" $a ")
                if (c != null && c.isNotBlank()) { have[Slot.CONTACT] = c; if (m.isNotBlank() && Slot.TEXT in n.missing) have[Slot.TEXT] = m }
                else have[Slot.CONTACT] = name(a)
            }
            Slot.TEXT -> have[Slot.TEXT] = if (n.task == Task.REMINDER) clean(stripTime(a)).ifBlank { a } else msgText(a).ifBlank { a }
            Slot.WHEN -> have[Slot.WHEN] = a
            Slot.APP -> have[Slot.APP] = clean(Regex("(?:^|\\s)(ऐप|app|खोलो|open|वाला|वाली)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(" $a ", " ")).ifBlank { a }
            Slot.QUERY -> have[Slot.QUERY] = clean(Regex("(?:^|\\s)(सर्च\\s*करो|search|ढूँढो|ढूंढो)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(" $a ", " ")).ifBlank { a }
            Slot.PLACE -> have[Slot.PLACE] = clean(Regex("(?:^|\\s)(जाना\\s*है|चलना\\s*है|का\\s*रास्ता|तक)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(" $a ", " ")).ifBlank { a }
        }
        // reminder: जवाब में समय भी हो ("कल सुबह दूध लाना") = दोनों भरो
        if (n.task == Task.REMINDER && slot == Slot.TEXT && Slot.WHEN in n.missing && TIME.containsMatchIn(a)) have[Slot.WHEN] = timePart(a)
        val miss = n.missing.filter { it !in have || have[it].isNullOrBlank() }
        return Reply.Filled(n.copy(have = have, missing = miss))
    }

    // ---------------- सवाल + हुक्म ----------------

    fun ask(n: Need): String {
        val c = n.have[Slot.CONTACT].orEmpty()
        val on = if (n.via.isNotBlank() && n.via != "SMS") "${n.via} पर " else if (n.via == "SMS") "SMS में " else ""
        return when (n.missing.firstOrNull()) {
            Slot.CONTACT -> when (n.task) {
                Task.CALL -> "${on}किसे ${if (n.video) "वीडियो कॉल" else "कॉल"} करूँ?"
                Task.EMAIL -> "किसे ईमेल भेजूँ?"
                else -> if (n.via == "SMS") "किसे SMS भेजूँ?" else "${on}किसे मैसेज भेजूँ?"
            }
            Slot.TEXT -> when (n.task) {
                Task.REMINDER -> "किस बात की याद दिलाऊँ?"
                Task.EMAIL -> "$c को ईमेल में क्या लिखूँ?"
                else -> "$c को क्या मैसेज भेजूँ?"
            }
            Slot.WHEN -> "कब याद दिलाऊँ? जैसे \"शाम 6 बजे\" या \"10 मिनट बाद\"।"
            Slot.APP -> "कौन-सा ऐप खोलूँ?"
            Slot.QUERY -> if (n.via.isNotBlank()) "${n.via} पर क्या सर्च करूँ?" else "क्या सर्च करूँ?"
            Slot.PLACE -> "कहाँ जाना है?"
            null -> ""
        }
    }

    fun build(n: Need): String {
        val h = n.have
        val on = if (n.via.isNotBlank()) "${n.via} पर " else ""
        return when (n.task) {
            Task.MESSAGE -> "$on${h[Slot.CONTACT]} को मैसेज भेजो कि ${h[Slot.TEXT]}"
            Task.CALL -> "$on${h[Slot.CONTACT]} को ${if (n.video) "वीडियो कॉल" else "कॉल"} करो"
            Task.EMAIL -> "${h[Slot.CONTACT]} को ईमेल भेजो कि ${h[Slot.TEXT]}"
            Task.REMINDER -> "मुझे ${h[Slot.WHEN]} ${h[Slot.TEXT]} याद दिलाना"
            Task.OPEN_APP -> "${h[Slot.APP]} खोलो"
            Task.SEARCH -> "$on${h[Slot.QUERY]} सर्च करो"
            Task.NAVIGATE -> "${h[Slot.PLACE]} का रास्ता बताओ"
        }.replace(Regex("\\s+"), " ").trim()
    }

    /** Live (Gemini) के लिए: phone_task अधूरा आया = पहले पूछो, फिर पूरा हुक्म दोबारा */
    fun liveNote(n: Need): String =
        "NEED_INFO: Do NOT do this task yet - a required detail is missing. Ask the user exactly this one short question in his language: \"${n.question}\" " +
            "and wait. When he answers, call phone_task again with the FULL request (the task + his answer), " +
            "e.g. \"${build(n.copy(have = n.have + n.missing.associateWith { "<उनका जवाब>" }, missing = emptyList()))}\". Never guess the missing detail."

    /** बाकी AI (BrainManager) के लिए: यह बात आपके पूछे सवाल का जवाब है */
    fun replyNote(dkAsked: String): String =
        "NOTE: The user is ANSWERING your last question (\"${dkAsked.take(160)}\"). Combine his answer with the task you were doing and finish that task now - " +
            "do not treat it as a new chat topic, and do not ask the same thing again."

    // ---------------- मदद ----------------

    /** "डीके," / "यह डीके है," / "हे डीके" हटाओ */
    fun strip(text: String): String {
        var t = text.trim()
        t = YEH_DK.replace(t, "")
        t = WAKE_RX.replace(t, "")
        return t.trim().trim(',', '।').trim()
    }

    private fun via(t: String): String {
        val m = APP_ON.find(" $t ")?.groupValues?.get(1)?.lowercase() ?: return ""
        return when {
            m.contains("टेली") || m.contains("tele") -> "Telegram"
            m.contains("इंस्टा") || m.contains("insta") -> "Instagram"
            m.contains("फेस") || m.contains("face") || m.contains("messenger") -> "Messenger"
            m.contains("sms") || m.contains("एसएमएस") -> "SMS"
            else -> "WhatsApp"
        }
    }

    private fun viaSite(s: String): String {
        val m = s.lowercase()
        return when {
            m.isBlank() -> ""
            m.contains("यू") || m.contains("you") -> "YouTube"
            m.contains("गूगल") || m.contains("google") || m.contains("क्रोम") || m.contains("chrome") -> "Google"
            m.contains("फ्लिप") || m.contains("flip") -> "Flipkart"
            m.contains("अमे") || m.contains("amazon") -> "Amazon"
            m.contains("मैप") || m.contains("map") -> "Maps"
            else -> s
        }
    }

    /** (नाम या null अगर "को" नहीं, संदेश) */
    private fun contactAndText(s0: String): Pair<String?, String> {
        val s = " " + s0.trim() + " "
        val ko = Regex("^\\s*(.*?)\\s+(?:को|ko|to)\\s+(.*)$", RegexOption.IGNORE_CASE).find(s)
            ?: Regex("^\\s*(.*?)\\s+(?:को|ko)\\s*$", RegexOption.IGNORE_CASE).find(s)?.let { return name(it.groupValues[1]) to "" }
            ?: return null to ""
        val c = name(ko.groupValues[1])
        return c to msgText(ko.groupValues[2])
    }

    private val PRONOUN = setOf("उसे", "उसको", "उन्हें", "उनको", "इसे", "इसको", "उस", "उन", "him", "her", "them", "usko", "unko")
    private fun name(s: String): String {
        val w = clean(s).split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in NAME_NOISE }
        if (w.isEmpty() || w.all { it.lowercase() in PRONOUN }) return ""
        return w.takeLast(3).joinToString(" ")
    }
    private val NAME_NOISE = setOf("फिर", "और", "तो", "प्लीज", "please", "जरा", "ज़रा", "मेरे", "मेरी", "मेरा", "my", "अभी", "abhi", "send", "message", "मैसेज", "भेजो", "को", "ko", "to", "जी")

    /** "हेलो मैसेज भेजो" -> "हेलो", "मैसेज भेजो कि मैं आ रहा हूँ" -> "मैं आ रहा हूँ" */
    private fun msgText(s: String): String {
        val ki = Regex("(?:^|\\s)(?:कि|that|लिखो|बोलो|बोल\\s*दो|लिख\\s*दो|:)\\s+(.+)$", RegexOption.IGNORE_CASE).find(" $s ")
        val m = if (ki != null) ki.groupValues[1] else removeMsgWords(s)
        return clean(m).trim('"', '\'', '“', '”').trim().removePrefix("कि ").removePrefix("that ").trim()
    }

    private fun removeMsgWords(s: String): String {
        var t = " $s "
        t = Regex("(?:^|\\s)(?:एक\\s+|a\\s+)?$MSG(?=\\s|$)", RegexOption.IGNORE_CASE).replace(t, " ")
        t = Regex("(?:^|\\s)(भेज\\S*|कर\\s*दो|करो|करना|कीजिए|करिए|कर|send|bhej\\S*|karo|kar\\s*do|लिख\\S*|डाल\\S*|दो|देना|दे|पर|पे|par|pe|on|the|ईमेल|email|मेल)(?=\\s|$)", RegexOption.IGNORE_CASE).replace(t, " ")
        return t
    }

    private fun stripTime(s: String): String =
        Regex("(?:^|\\s)(\\S*\\d\\S*|बजे|baje|मिनट|minutes?|min|घंटे|घंटा|hours?|सेकंड|seconds?|बाद|later|कल|आज|परसों|सुबह|शाम|रात|दोपहर|tomorrow|today|tonight|morning|evening|night|am|pm|को|पर|में|सोमवार|मंगलवार|बुधवार|गुरुवार|शुक्रवार|शनिवार|रविवार|एक|दो|तीन|चार|पांच|पाँच|छह|सात|आठ|नौ|दस|ग्यारह|बारह|आधे|डेढ़|ढाई|in|at|after)(?=\\s|$)", RegexOption.IGNORE_CASE)
            .replace(" $s ", " ")

    private fun timePart(s: String): String {
        val w = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        val keep = w.filter { TIME.containsMatchIn(it) || it in setOf("को", "पर", "के") }
        return keep.joinToString(" ").trim().removeSuffix(" को").removeSuffix(" पर").removeSuffix(" के").trim().ifBlank { s.trim() }
    }

    private fun clean(s: String) = s.replace(Regex("[,।!?.]+"), " ").replace(Regex("\\s+"), " ").trim()
}
