package com.dk.assistant

/**
 * 14 नवंबर 2026 (मालिक): "DK YouTube खोलो और पवन सिंह का जो नया वाला सॉन्ग आया है उसे सर्च करो" = YouTube पर
 * "पवन सिंह का नया सॉन्ग" - बोली की भरती ("जो", "वाला", "आया है", "उसे", "डीके", "उह") खोज में नहीं जानी चाहिए.
 * जड़: [LocalRouter] सिर्फ़ ऐप/सर्च के शब्द हटाता था, बाकी पूरा वाक्य YouTube में चला जाता था = गलत नतीजे.
 * Pure (Android नहीं) - JUnit से जाँच. "का / की / के" और नाम / गाना / जगह जैसे काम के शब्द कभी नहीं हटते.
 */
object SearchFocus {
    /** बोलने की भरती - पूरा शब्द हो तभी हटे (गानों के नाम में आने वाले आम शब्द जैसे "है", "जो", "ना" अकेले नहीं हटते) */
    private val FILLER = listOf(
        "डीके", "डी के", "दीके", "dk", "उह", "उम्म", "हम्म", "umm", "uh", "hmm",
        "जो भी है", "या जो भी", "jo bhi hai",
        "आया हुआ है", "आया है", "आई है", "आए हैं", "आये हैं", "निकला है", "निकली है",
        "रिलीज़ हुआ है", "रिलीज हुआ है", "रिलीज़ हुई है", "रिलीज हुई है", "aaya hai", "aayi hai", "aya hai", "ayi hai",
        "वो वाला", "वो वाली", "मुझे", "मेरे को", "mujhe", "ज़रा", "जरा", "zara"
    )
    /** "का जो नया" -> "का नया" */
    private val KA_JO = Regex("(?<=\\s)(का|की|के|ka|ki|ke)\\s+(जो|jo)(?=\\s)")
    /** "नया वाला" -> "नया" */
    private val WALA = Regex("(?<=\\s)(नया|नई|नए|नये|लेटेस्ट|ताज़ा|ताजा|पुराना|पुरानी|latest|new|naya|nayi)\\s+(वाला|वाली|वाले|wala|wali|wale)(?=\\s)")
    /** आख़िर में बचा "उसे / इसे" (सर्च करो का कर्म) */
    private val TAIL = Regex("\\s+(उसे|उसको|इसे|इसको|usko|isko)$")
    /** बोली-पहचान की आम गलती */
    private val FIX = listOf("छट" to "छठ", "chhat" to "chhath")

    fun core(raw: String): String {
        var q = " " + raw.replace(Regex("[,।!?]+"), " ").replace(Regex("\\s+"), " ").trim() + " "
        q = KA_JO.replace(q) { it.groupValues[1] }
        q = WALA.replace(q) { it.groupValues[1] }
        FILLER.sortedByDescending { it.length }.forEach { f -> q = q.replace(Regex("(?i)(?<=\\s)" + Regex.escape(f) + "(?=\\s)"), " ") }
        FIX.forEach { (a, b) -> q = q.replace(Regex("(?<=\\s)" + Regex.escape(a) + "(?=\\s)"), b) }
        q = q.replace(Regex("\\s+"), " ").trim()
        repeat(2) { q = TAIL.replace(q, "").trim() }
        // आख़िर में अकेला "का / की / के" बचा = बेकार
        q = q.replace(Regex("\\s+(का|की|के|ka|ki|ke)$"), "").trim()
        return q.ifBlank { raw.trim() }
    }

    /** ख़बर वाला (1 दिन का) फ़िल्टर लगा था और नतीजा खाली = एक बार बिना फ़िल्टर */
    fun wideRetry(news: Boolean, code: Int, message: String?): Boolean = news && code == 0 && message == "empty"
}
