package com.dk.assistant

/**
 * v3.45: निजी जानकारी कभी याद (सेव) नहीं - पासवर्ड, PIN, OTP, बैंक खाता, कार्ड, CVV, आधार, PAN, UPI PIN.
 * (Android import नहीं -> JVM unit test)
 * [isSensitive] = पूरा तथ्य मत रखो. [redact] = सारांश में से नंबर/पासवर्ड हटाकर बाकी बात रखो.
 */
object Sensitive {
    private val WORDS = Regex(
        "(password|passcode|पासवर्ड|पासकोड|\\bpin\\b|पिन(?!\\s*कोड)|\\botp\\b|ओटीपी|cvv|सीवीवी|upi\\s*pin|" +
            "card\\s*(no|number)|कार्ड\\s*(का)?\\s*(नंबर|नम्बर)|account\\s*(no|number)|खाता\\s*(संख्या|नंबर|नम्बर)|अकाउंट\\s*(नंबर|नम्बर)|" +
            "ifsc|आधार\\s*(नंबर|नम्बर|कार्ड)?|aadhaa?r|\\bpan\\s*(card|no|number)?\\b|पैन\\s*(कार्ड|नंबर)|net\\s*banking|नेट\\s*बैंकिंग|" +
            "secret\\s*key|api\\s*key|एपीआई\\s*की)",
        RegexOption.IGNORE_CASE
    )
    /** 12-19 अंकों का लगातार नंबर (कार्ड / आधार / खाता) - बीच में space/- चल सकता है */
    private val LONG_NUM = Regex("(?<!\\d)(?:\\d[ -]?){11,18}\\d(?!\\d)")
    /** PAN: ABCDE1234F */
    private val PAN = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
    /** 9-11 अंक = खाता नंबर हो सकता है (10 अंक मोबाइल नंबर ठीक है) */
    private val ACCOUNT = Regex("(?<!\\d)\\d{9}(?!\\d)|(?<!\\d)\\d{11}(?!\\d)")

    fun isSensitive(s: String): Boolean =
        WORDS.containsMatchIn(s) || LONG_NUM.containsMatchIn(s) || PAN.containsMatchIn(s) || ACCOUNT.containsMatchIn(s)

    /** सारांश/अधूरी बात में से निजी नंबर हटाओ; पासवर्ड वाला पूरा वाक्य हटाओ */
    fun redact(s: String): String {
        val parts = s.split(Regex("(?<=[।.!?\\n])"))
        val kept = parts.filterNot { WORDS.containsMatchIn(it) }.joinToString("")
        return kept.replace(LONG_NUM, "[नंबर हटाया]").replace(PAN, "[नंबर हटाया]").replace(ACCOUNT, "[नंबर हटाया]").trim()
    }
}
