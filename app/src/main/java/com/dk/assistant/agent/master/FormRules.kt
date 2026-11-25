package com.dk.assistant.agent.master

/**
 * 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 5 - "फ़ॉर्म एजेंट").
 * फ़ॉर्म एजेंट का pure हिस्सा (JVM test). असली काम पहले से बना [com.dk.assistant.agent.android.FormFillAgent] (Live का form_fill) ही करता है -
 * यहाँ सिर्फ़ मास्टर के नियम:
 *  - निजी जानकारी कभी खुद नहीं बनाना: नंबर वाला मान (मोबाइल, पिनकोड, आधार, खाता...) तभी भरे जब मालिक ने खुद बोला हो / याद रखवाया हो ([fromOwner])
 *  - पहचान वाले खाने (आधार / PAN / बैंक / कार्ड / पासपोर्ट) = भरने से पहले मालिक की अपनी "हाँ"; CAPTCHA = मालिक खुद ([type])
 *  - सबमिट = मालिक की अपनी "हाँ" (AI का confirm=true काफ़ी नहीं); सबमिट बटन असल में "Pay" हो = कभी नहीं ([submitKind])
 *  - लॉग में खाने का नाम, मान नहीं (पहचान वाले मान छिपाकर - [mask])
 * [com.dk.assistant.plus.FeatureFlags.MASTER_AGENT] बंद = इसका कोई हिस्सा नहीं चलता (फ़ॉर्म भरना बिल्कुल पहले जैसा).
 */
object FormRules {
    enum class Type(val hi: String) { NORMAL(""), CONTACT("संपर्क नंबर"), IDENTITY("पहचान / बैंक"), CAPTCHA("CAPTCHA") }

    private fun norm(s: String) = " " + s.lowercase().replace(Regex("[*:：•_\\-()]+"), " ").replace(Regex("\\s+"), " ").trim() + " "
    private fun has(l: String, w: List<String>) = w.any { l.contains(it) }

    private val CAPTCHA = listOf("captcha", "कैप्चा", "security code", "image code", "type the characters", "enter the text", "verification image", "i'm not a robot", "i am not a robot")
    private val IDENTITY = listOf("aadhaar", "aadhar", "adhaar", "आधार", " uid ", "pan ", "pan no", "pan number", "pan card", "पैन", "passport", "पासपोर्ट",
        "account no", "account number", "a/c", "bank account", "बैंक खाता", "खाता संख्या", "खाता नंबर", "ifsc", "आईएफएससी", "card number", "card no", "कार्ड नंबर",
        "debit card", "credit card", "voter id", "वोटर", "epic no", "driving licence", "driving license", "ड्राइविंग लाइसेंस", "upi id", "यूपीआई", "ssn", "tax id", "gstin")
    private val CONTACT = listOf("mobile", "मोबाइल", "phone", "फ़ोन", "फोन", "contact no", "contact number", "whatsapp", "pincode", "pin code", "postal code", "zip",
        "पिनकोड", "पिन कोड", "alternate number", "नंबर")

    /** खाने के नाम से किस्म. (पासवर्ड / OTP पहले से ही [com.dk.assistant.agent.core.FormFill] में "निजी" - मालिक खुद भरता है) */
    fun type(label: String): Type {
        val l = norm(label)
        return when {
            has(l, CAPTCHA) -> Type.CAPTCHA
            has(l, IDENTITY) -> Type.IDENTITY
            has(l, CONTACT) -> Type.CONTACT
            else -> Type.NORMAL
        }
    }

    private val DEV = "०१२३४५६७८९"

    /** देवनागरी अंक भी -> 0-9 */
    fun ascii(s: String): String = buildString { s.forEach { c -> val i = DEV.indexOf(c); append(if (i >= 0) ('0' + i) else c) } }

    fun digits(s: String): String = ascii(s).filter { it.isDigit() }

    private val WORD_DIGIT = mapOf("शून्य" to "0", "ज़ीरो" to "0", "जीरो" to "0", "zero" to "0", "एक" to "1", "one" to "1", "दो" to "2", "two" to "2",
        "तीन" to "3", "three" to "3", "चार" to "4", "four" to "4", "पाँच" to "5", "पांच" to "5", "five" to "5", "छह" to "6", "छः" to "6", "six" to "6",
        "सात" to "7", "seven" to "7", "आठ" to "8", "eight" to "8", "नौ" to "9", "nine" to "9")

    /** बोले हुए अंक ("नौ आठ सात", "nine eight") -> 9 8 7 */
    fun spoken(text: String): String = text.split(' ').joinToString(" ") { w -> WORD_DIGIT[w.lowercase().trim(',', '.', '।')] ?: w }

    /** मालिक की बात में अंकों के टुकड़े ("98765 43210" / "98765-43210" / "नौ आठ सात" = एक टुकड़ा) */
    fun digitRuns(text: String): List<String> =
        Regex("\\d[\\d\\s\\-.]*\\d|\\d").findAll(ascii(spoken(text))).map { m -> m.value.filter { it.isDigit() } }.filter { it.isNotEmpty() }.toList()

    /** तारीख जैसा मान ("15/08/1995", "15 Aug 1995") - इसे अंकों से नहीं जाँचते (मालिक "15 अगस्त" बोलता है) */
    fun dateLike(v: String): Boolean = Regex("\\d{1,2}\\s*[/.\\-]\\s*\\d{1,2}\\s*[/.\\-]\\s*\\d{2,4}").containsMatchIn(ascii(v)) ||
        Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|जनवरी|फ़रवरी|फरवरी|मार्च|अप्रैल|मई|जून|जुलाई|अगस्त|सितंबर|अक्टूबर|नवंबर|दिसंबर)").containsMatchIn(v)

    /**
     * यह मान मालिक का अपना है? चार या ज़्यादा अंक वाला मान (मोबाइल, पिनकोड, आधार, खाता...) = उसके अंक मालिक की हाल की बात या
     * उसकी याद रखवाई बातों ([owner]) में किसी एक टुकड़े में होने चाहिए. नाम / पता जैसे शब्द यहाँ नहीं जाँचते (हिंदी-अंग्रेज़ी लिखावट अलग हो सकती है).
     */
    fun fromOwner(value: String, owner: String): Boolean {
        val d = digits(value)
        if (d.length < 4 || dateLike(value)) return true
        return digitRuns(owner).any { it.contains(d) }
    }

    /** पहचान वाला मान छिपाकर ("XXXXXXXX1234") - लॉग / बोलने के लिए */
    fun mask(v: String): String {
        val t = v.trim()
        return if (t.length <= 4) "XXXX" else "X".repeat(minOf(t.length - 4, 8)) + t.takeLast(4)
    }

    enum class Submit { NORMAL, BOOK, PAY }

    /** सबमिट बटन असल में क्या करता है */
    fun submitKind(label: String): Submit = when {
        ShopRules.tap(label) == ShopRules.Tap.PAY || norm(label).trim() == "pay" -> Submit.PAY
        BookRules.tap(label) == BookRules.Tap.BOOK || norm(label).trim() == "book" -> Submit.BOOK
        else -> Submit.NORMAL
    }

    /** read के नतीजे के आख़िर में (मास्टर चालू हो तो) */
    fun readNote(labels: List<String>): String {
        val id = labels.filter { type(it) == Type.IDENTITY }
        val cap = labels.filter { type(it) == Type.CAPTCHA }
        return buildString {
            append("\nDK MASTER (form): fill ONLY what the owner himself told you now or earlier saved in DK memory - never invent a name, number, date, address or ID; ")
            append("if you don't know a value, ask him (one field at a time); never fill a field with a sample / dummy value. ")
            if (id.isNotEmpty()) append("Identity / bank fields (${id.joinToString(", ").take(160)}): DK asks his own yes before typing each one. ")
            if (cap.isNotEmpty()) append("CAPTCHA (${cap.joinToString(", ").take(80)}): the owner must solve it himself - never guess it. ")
            append("Submit = DK asks his own yes; a Pay button is never pressed.")
        }
    }

    const val NOT_FROM_OWNER = "NOT_FROM_OWNER: DK MASTER did NOT type this - the number is not something the owner himself said (DK never invents personal details). " +
        "Ask him in one short Hindi line to say this value himself, then call fill again with exactly his words."

    const val CAPTCHA_NOTE = "CAPTCHA: DK never solves or guesses a CAPTCHA. Ask the owner to type it himself on the screen, then continue with the next field."

    const val PAY_NOTE = "STOPPED: this form's last button is a PAYMENT button. DK never pays - nothing was pressed. Tell the owner the form is filled and he should check and pay himself."

    /** सबमिट की पुष्टि में पढ़कर सुनाने लायक (पहचान वाले छिपे हुए) */
    fun readBack(filled: Map<String, String>): String =
        filled.entries.joinToString(", ") { (k, v) -> "$k: " + if (type(k) == Type.IDENTITY) mask(v) else v.take(40) }.take(400)
}
