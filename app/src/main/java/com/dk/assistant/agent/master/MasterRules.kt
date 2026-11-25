package com.dk.assistant.agent.master

/**
 * 24 नवंबर 2026 (मालिक: "Universal AI Action Agent - एक केंद्रीय DK AI मास्टर कंट्रोलर, उसके नीचे विशेषज्ञ एजेंट"):
 * केंद्रीय दिमाग का pure हिस्सा (Android नहीं -> JVM test). असली फ़ोन वाला हिस्सा [DkMaster].
 *
 * बहाव: कमांड -> इरादा ([classify]) -> जोखिम ([risks]) -> प्लान ([plan]) -> यूज़र को प्लान दिखाओ ([planText]) ->
 * विशेषज्ञ एजेंट (पहले से बने: फ़ोन कंट्रोल / DK कंप्यूटर ब्राउज़र / ShopAsk / FormFill / SocialAgent) -> हर कदम ->
 * गलती पर दोबारा (एजेंट खुद) -> पैसे / आख़िरी कदम से पहले पुष्टि -> नतीजा जाँचो -> रिपोर्ट + लॉग ([ActionLog]).
 *
 * कड़ा नियम: कोई विशेषज्ञ खुद से खरीदारी, भुगतान, बुकिंग या वापस न होने वाला काम नहीं करेगा - पहले साफ़ पुष्टि.
 * बुकिंग एजेंट कभी ऑनलाइन भुगतान नहीं करता (पेमेंट पेज यूज़र को). फ़ॉर्म एजेंट निजी जानकारी कभी खुद नहीं बनाता.
 */
object MasterRules {
    /** विशेषज्ञ एजेंट - [hi] स्क्रीन पर दिखने वाला नाम */
    enum class Specialist(val id: String, val hi: String) {
        PHONE("phone", "फ़ोन कंट्रोल (Accessibility)"),
        BROWSER("browser", "ब्राउज़र"),
        SHOPPING("shopping", "शॉपिंग"),
        BOOKING("booking", "बुकिंग"),
        FORM("form", "फ़ॉर्म भरना"),
        SOCIAL("social", "सोशल"),
    }

    /** रुककर पूछना ज़रूरी */
    enum class Risk(val hi: String) {
        MONEY("पैसे / भुगतान"),
        PASSWORD("पासवर्ड"),
        OTP("OTP / कोड"),
        CAPTCHA("CAPTCHA"),
        IDENTITY("पहचान (आधार / PAN / बैंक)"),
        IRREVERSIBLE("वापस न होने वाला काम"),
    }

    data class Step(val hi: String, val confirm: Boolean = false)

    data class Plan(val specialist: Specialist, val risks: Set<Risk>, val steps: List<Step>) {
        /** कहीं भी पुष्टि वाला कदम */
        val needsConfirm get() = steps.any { it.confirm }
    }

    private fun norm(s: String) = " " + s.lowercase().replace(Regex("[,.!?।\"']+"), " ").replace(Regex("\\s+"), " ").trim() + " "
    private fun has(l: String, words: List<String>) = words.any { l.contains(it) }

    private val BOOKING = listOf(" बुक", " book", "booking", "टिकट", "ticket", "ट्रेन", "train", "फ्लाइट", "flight", "बस टिकट", "bus ticket",
        "होटल", "hotel", "irctc", "रिज़र्वेशन", "रिजर्वेशन", "reservation", "कैब", " cab ", " ola ", " uber ", "rapido", "मूवी टिकट", "movie ticket")
    private val SHOPPING = listOf("खरीद", "खरीदना", "ख़रीद", "buy", "order", "ऑर्डर", "कार्ट", "cart", "flipkart", "फ्लिपकार्ट", "amazon", "अमेज़न", "अमेजन",
        "meesho", "मीशो", "myntra", "मिंत्रा", "ajio", "shopping", "शॉपिंग", "दाम", "कीमत", "price", "सस्ता")
    private val FORM = listOf("फॉर्म", "फ़ॉर्म", "form", "भर दो", "भरो", " fill ", "आवेदन", "apply", "रजिस्ट्रेशन", "registration", "sign up", "साइन अप")
    private val BROWSER = listOf("वेबसाइट", "website", "साइट", "site", "ब्राउज़र", "browser", "chrome", "क्रोम", "गूगल पर", "google par", "लिंक", "link", ".com", "www")
    private val SOCIAL = listOf("इंस्टाग्राम", "instagram", "insta", "फेसबुक", "facebook", "यूट्यूब", "youtube", "reel", "रील", "पोस्ट", "post", "स्टोरी", "story")

    /** कमांड -> कौन-सा विशेषज्ञ (क्रम: बुकिंग > शॉपिंग > फ़ॉर्म > सोशल > ब्राउज़र > फ़ोन) */
    fun classify(req: String): Specialist {
        val l = norm(req)
        return when {
            has(l, BOOKING) -> Specialist.BOOKING
            has(l, SHOPPING) -> Specialist.SHOPPING
            has(l, FORM) -> Specialist.FORM
            has(l, SOCIAL) -> Specialist.SOCIAL
            has(l, BROWSER) -> Specialist.BROWSER
            else -> Specialist.PHONE
        }
    }

    private val MONEY = listOf("पैसे", "पैसा", "रुपये", "रुपए", "₹", "rs ", "rupees", "payment", "पेमेंट", "भुगतान", "pay ", "upi", "यूपीआई",
        "gpay", "phonepe", "फोनपे", "paytm", "पेटीएम", "ट्रांसफर", "transfer", "भेजो पैसे", "recharge", "रिचार्ज", "बिल भर", "checkout", "चेकआउट")
    private val PASSWORD = listOf("पासवर्ड", "password", "पिन ", " pin ", "passcode")
    private val OTP = listOf("otp", "ओटीपी", "कोड बता", "code bata", "verification code", "वेरिफिकेशन कोड")
    private val CAPTCHA = listOf("captcha", "कैप्चा", "i am not a robot")
    private val IDENTITY = listOf("आधार", "aadhaar", "aadhar", "पैन कार्ड", "pan card", "pan number", "बैंक खाता", "bank account", "account number",
        "ifsc", "पासपोर्ट", "passport", "cvv", "कार्ड नंबर", "card number", "डेबिट कार्ड", "credit card", "क्रेडिट कार्ड")
    private val IRREVERSIBLE = listOf("डिलीट", "delete", "मिटा", "हटा दो सब", "format", "फ़ॉर्मेट", "uninstall", "अनइंस्टॉल", "रिसेट", "reset",
        "बुक कर", "book kar", "ऑर्डर कर", "order kar", "place order", "खरीद लो", "buy now", "submit", "सबमिट", "पोस्ट कर", "publish")

    fun risks(req: String): Set<Risk> {
        val l = norm(req)
        val r = LinkedHashSet<Risk>()
        if (has(l, MONEY)) r += Risk.MONEY
        if (has(l, PASSWORD)) r += Risk.PASSWORD
        if (has(l, OTP)) r += Risk.OTP
        if (has(l, CAPTCHA)) r += Risk.CAPTCHA
        if (has(l, IDENTITY)) r += Risk.IDENTITY
        if (has(l, IRREVERSIBLE)) r += Risk.IRREVERSIBLE
        return r
    }

    /** संवेदनशील जानकारी किसी और को देना / भेजना ("मेरा OTP उसको भेज दो") - बिना इजाज़त कभी नहीं */
    fun sharesSecret(req: String): Boolean {
        val l = norm(req)
        val secret = has(l, OTP) || has(l, PASSWORD) || has(l, IDENTITY)
        val send = has(l, listOf("भेज", "send", "बता दो", "bata do", "शेयर", "share", "दे दो", "forward", "फॉरवर्ड", "लिख दो", "डाल दो", "paste"))
        return secret && send
    }

    /** कमांड -> दिखाने लायक प्लान. पैसे / बुकिंग / आख़िरी कदम = पुष्टि वाला कदम */
    fun plan(req: String): Plan {
        val sp = classify(req)
        val rk = risks(req)
        val steps = when (sp) {
            Specialist.BOOKING -> listOf(
                Step("सही ऐप / वेबसाइट खोलना"), Step("तारीख, जगह, लोग भरना (जो नहीं पता वह आपसे पूछूँगा)"),
                Step("विकल्प दिखाना और आपसे चुनवाना"), Step("बुक करने से पहले आपसे पक्का पूछना", confirm = true),
                Step("पेमेंट पेज आपको सौंपना - भुगतान आप खुद करेंगे", confirm = true),
            )
            Specialist.SHOPPING -> listOf(
                Step("शॉपिंग ऐप खोलना (कौन-सा, आपसे पूछकर)"), Step("चीज़ ढूँढना - आपका बजट / साइज़ साथ में"),
                Step("स्क्रीन पर सच में दिखे दाम बताना (सबसे सस्ते पहले)"),
                Step("कार्ट में डालने से पहले पूछना", confirm = true), Step("चेकआउट / पेमेंट आप खुद करेंगे", confirm = true),
            )
            Specialist.FORM -> listOf(
                Step("फ़ॉर्म खोलकर खाने पढ़ना"), Step("जो जानकारी पता है वही भरना - बाकी आपसे पूछना (खुद कुछ नहीं बनाऊँगा)"),
                Step("भरा हुआ फ़ॉर्म दिखाना"), Step("सबमिट से पहले आपसे पक्का पूछना", confirm = true),
            )
            Specialist.SOCIAL -> listOf(
                Step("ऐप खोलना"), Step("काम तैयार करना (कैप्शन / फ़ोटो / मैसेज)"), Step("पोस्ट / भेजने से पहले पूर्वावलोकन और पुष्टि", confirm = true),
            )
            Specialist.BROWSER -> listOf(
                Step("ब्राउज़र में पेज खोलना"), Step("पहले पेज पढ़ना, फिर सिर्फ़ दिखे हुए बटन / खाने इस्तेमाल करना"),
                Step("हर कदम के बाद जाँचना कि पेज सच में बदला"), Step("नतीजा बताना (जो पेज ने दिखाया वही)"),
            )
            Specialist.PHONE -> listOf(Step("ऐप / स्क्रीन खोलना"), Step("काम करना"), Step("जाँचना कि सच में हुआ"))
        }.let { s ->
            // पैसे / वापस न होने वाला / पासवर्ड वगैरह = आख़िरी कदम हमेशा पुष्टि वाला
            if (rk.isNotEmpty() && s.none { it.confirm }) s.dropLast(1) + s.last().copy(confirm = true) else s
        }
        return Plan(sp, rk, steps)
    }

    /** स्क्रीन / बोलने के लिए छोटा प्लान */
    fun planText(p: Plan): String = buildString {
        append("प्लान (${p.specialist.hi}): ")
        append(p.steps.mapIndexed { i, s -> "${i + 1}) ${s.hi}${if (s.confirm) " [पुष्टि]" else ""}" }.joinToString(" • "))
        if (p.risks.isNotEmpty()) append(" — सावधानी: ").append(p.risks.joinToString(", ") { it.hi })
        append(" — रोकने के लिए \"रुको\" बोलिए।")
    }

    /**
     * Gemini / एजेंट को साथ में जाने वाला नियम (प्लान वाले काम पर). यह सिर्फ़ ज़्यादा सख़्ती जोड़ता है, कोई छूट नहीं.
     */
    fun agentNote(p: Plan): String = buildString {
        append("DK MASTER RULES for this task (${p.specialist.id}): ")
        append("never buy, pay, book, submit, post or do anything irreversible without the owner's clear yes right before that step; ")
        when (p.specialist) {
            Specialist.BOOKING -> append("never make the online payment - stop on the payment page and hand it to the owner; ")
            Specialist.SHOPPING -> append("stop before checkout / payment and hand it to the owner (unless he himself said proceed / order / cash on delivery); ").append(ShopRules.AGENT_NOTE).append("; ")
            Specialist.FORM -> append("never invent personal details - ask the owner for anything you don't know; ")
            Specialist.BROWSER -> append("read the page before acting, use only what is on the screen, check after each tap that the page changed, try one other way then tell the owner; ")
            else -> Unit
        }
        append("on password / OTP / CAPTCHA / identity screens stop and ask the owner; when unsure ask, don't guess.")
    }

    /** "रुको" / "stop" - चालू काम तुरंत रोकना */
    private val STOP = Regex("^(dk |डीके |डी के )?(रुको|रुक जाओ|रुक|बस करो|बस|रोक दो|रोको|stop|ruko|ruk jao|cancel|कैंसल|band karo|बंद करो)( |$)")
    fun isStop(t: String): Boolean = STOP.containsMatchIn(norm(t).trim())

    /** पुष्टि दिखाएँ? सिर्फ़ संवेदनशील जानकारी भेजने पर यहीं (शुरू में); बाकी पुष्टि आख़िरी कदम पर एजेंट पूछता है */
    fun confirmUpfront(req: String): Boolean = sharesSecret(req)
}
