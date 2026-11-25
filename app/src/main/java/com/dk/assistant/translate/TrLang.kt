package com.dk.assistant.translate

/**
 * 25 नवंबर 2026 (मालिक: "DK ट्रांसलेटर - सामने वाला किसी भी भाषा में बोले, मुझे हिंदी में सुनाई दे"):
 * सामने वाले की भाषाओं की सूची + लिखे text से भाषा पहचान (language filter) + लिपि बदलना.
 *
 * - [guess]: Live की लिखावट (inputTranscription) से भाषा. लिपि से (तमिल / तेलुगु / बांग्ला / गुजराती / पंजाबी / अंग्रेज़ी),
 *   और देवनागरी में मराठी बनाम हिंदी - पहचान वाले शब्दों से (आहे / नाही / मला  बनाम  है / नहीं / मुझे).
 * - [toDeva] / [fromDeva]: Indic लिपियाँ Unicode में एक ही क्रम में हैं - ऑफ़लाइन मॉडल (IndicTrans2) देवनागरी में
 *   सोचता है, और स्क्रीन पर तमिल / तेलुगु अनुवाद के नीचे हिंदी (देवनागरी) में पढ़ने लायक रूप.
 *
 * Android import नहीं -> JVM test ([com.dk.assistant.translate.DkTranslatorTest]).
 */
object TrLang {
    /** code = हमारा छोटा नाम; it2 = IndicTrans2 का नाम (ऑफ़लाइन); bcp = Gemini / Android की भाषा; base = लिपि का Unicode शुरू */
    data class L(val code: String, val hi: String, val en: String, val bcp: String, val it2: String, val base: Int)

    const val AUTO = "auto"
    const val HINDI = "hi"
    const val UNKNOWN = "?"
    private const val DEVA = 0x0900

    val HI = L(HINDI, "हिंदी", "Hindi", "hi-IN", "hin_Deva", DEVA)

    /** मराठी पहले (मालिक: "पहले मराठी ↔ हिंदी पूरी तरह") - बाकी उसके बाद */
    val ALL: List<L> = listOf(
        L("mr", "मराठी", "Marathi", "mr-IN", "mar_Deva", DEVA),
        L("ta", "तमिल", "Tamil", "ta-IN", "tam_Taml", 0x0B80),
        L("te", "तेलुगु", "Telugu", "te-IN", "tel_Telu", 0x0C00),
        L("bn", "बंगाली", "Bengali", "bn-IN", "ben_Beng", 0x0980),
        L("gu", "गुजराती", "Gujarati", "gu-IN", "guj_Gujr", 0x0A80),
        L("pa", "पंजाबी", "Punjabi", "pa-IN", "pan_Guru", 0x0A00),
        L("kn", "कन्नड़", "Kannada", "kn-IN", "kan_Knda", 0x0C80),
        L("ml", "मलयालम", "Malayalam", "ml-IN", "mal_Mlym", 0x0D00),
        L("or", "ओड़िया", "Odia", "or-IN", "ory_Orya", 0x0B00),
        L("en", "अंग्रेज़ी", "English", "en-IN", "eng_Latn", 0),
        L("ur", "उर्दू", "Urdu", "ur-IN", "urd_Arab", 0),
    )

    /** dropdown में: पहले "Auto-detect", फिर सूची */
    val CHOICES: List<String> = listOf(AUTO) + ALL.map { it.code }

    fun of(code: String): L? = if (code == HINDI) HI else ALL.firstOrNull { it.code == code }

    fun label(code: String): String = if (code == AUTO) "Auto-detect (अपने-आप पहचाने)" else of(code)?.let { "${it.hi} (${it.en})" } ?: code

    /** ऑफ़लाइन (IndicTrans2 indic-indic) से कौन-सी भाषा चल सकती है: अनुवाद सिर्फ़ भारतीय लिपियों में (अंग्रेज़ी/उर्दू -> हिंदी ठीक, उल्टा नहीं) */
    fun offlineTo(code: String): Boolean = of(code)?.let { it.base != 0 } ?: false
    fun offlineFrom(code: String): Boolean = of(code) != null && code != "ur"

    private val MR = setOf("आहे", "आहेत", "आहात", "आहोत", "नाही", "नाहीत", "काय", "मी", "तुम्ही", "तू", "आणि", "होते", "होता", "मला", "तुला",
        "करतो", "करते", "करतात", "झाले", "झाला", "कसं", "कसे", "कुठे", "माझं", "माझा", "माझी", "तुमचं", "तुमचा", "आम्ही", "आपण", "पण",
        "जायचं", "जायचे", "येतो", "येते", "बघ", "बघा", "सांगा", "सांग", "नको", "किती", "इथे", "तिथे", "आता", "उद्या", "काल", "बरं", "म्हणजे")
    private val HIW = setOf("है", "हैं", "हूँ", "हूं", "नहीं", "क्या", "मैं", "और", "था", "थी", "थे", "मुझे", "तुम्हें", "को", "का", "की", "के",
        "रहा", "रही", "रहे", "में", "से", "भी", "यह", "वह", "कहाँ", "कहां", "कैसे", "क्यों", "आप", "हम", "मेरा", "मेरी", "तुम", "करो", "करना",
        "जाना", "गया", "गई", "होगा", "दो", "बताओ", "अभी", "कल", "थोड़ा", "भाई", "चलो", "देखो", "बोलो", "सुनो")

    /** एक अक्षर किस लिपि का: "deva", "latin", "arab", या भाषा का code (ta/te/bn/gu/pa/kn/ml/or) */
    private fun script(ch: Char): String? {
        val c = ch.code
        if (c in DEVA..0x097F) return "deva"
        if (ch in 'a'..'z' || ch in 'A'..'Z') return "latin"
        if (c in 0x0600..0x06FF) return "arab"
        for (l in ALL) if (l.base != 0 && l.base != DEVA && c in l.base..(l.base + 0x7F)) return l.code
        return null
    }

    /** text की मुख्य लिपि ("" = कुछ नहीं) */
    fun mainScript(t: String): String {
        val n = HashMap<String, Int>()
        for (ch in t) script(ch)?.let { n[it] = (n[it] ?: 0) + 1 }
        return n.maxByOrNull { it.value }?.key ?: ""
    }

    private fun words(t: String) = t.split(Regex("[\\s,.!?।॥;:\"'()\\-]+")).filter { it.isNotEmpty() }

    /** देवनागरी text: मराठी के पहचान-शब्द ज़्यादा = "mr", हिंदी के = "hi", बराबर / कुछ नहीं = "" (पक्का नहीं) */
    fun marathiOrHindi(t: String): String {
        val w = words(t)
        val mr = w.count { it in MR || it.endsWith("ायचं") || it.endsWith("तोय") || it.endsWith("णार") }
        val hi = w.count { it in HIW }
        return when { mr > hi -> "mr"; hi > mr -> HINDI; else -> "" }
    }

    /**
     * लिखावट से भाषा: "hi" / भाषा का code / "" (पक्का नहीं - बहुत छोटा या बराबरी) / [UNKNOWN] (कोई लिपि नहीं).
     * देवनागरी में मराठी और हिंदी के अलावा कुछ नहीं माना जाता (नेपाली/कोंकणी = मराठी जैसा मानकर "" भी हो सकता है).
     */
    fun guess(t: String): String = when (val s = mainScript(t)) {
        "" -> UNKNOWN
        "deva" -> marathiOrHindi(t)
        "latin" -> if (looksHinglish(t)) HINDI else "en"
        "arab" -> "ur"
        else -> s
    }

    /** रोमन लिपि में लिखी हिंदी ("kya haal hai") - Live कभी-कभी हिंदी को रोमन में लिखता है */
    private val HINGLISH = setOf("hai", "hain", "nahi", "nahin", "kya", "main", "mujhe", "aap", "tum", "kaise", "kahan", "kyon", "kyun", "acha",
        "accha", "theek", "bhai", "haan", "matlab", "raha", "rahi", "karo", "batao", "chalo", "mera", "meri", "hum", "kar", "ke", "ki", "ko")
    fun looksHinglish(t: String): Boolean { val w = words(t.lowercase()); return w.isNotEmpty() && w.count { it in HINGLISH } * 3 >= w.size }

    /**
     * Language filter (मालिक: "सिर्फ़ चुनी भाषा का अनुवाद, मेरी हिंदी पूरी तरह अनदेखी"):
     * PLAY = अनुवाद सुनाओ, DROP = हिंदी (मेरी बात) - चुपचाप छोड़ो, WAIT = अभी पक्का नहीं, UNSURE = दूसरी भाषा (समझ नहीं आई).
     */
    enum class Verdict { PLAY, DROP, WAIT, UNSURE }

    fun verdict(chosen: String, heard: String, final: Boolean): Verdict {
        val g = guess(heard)
        return when {
            g == HINDI -> Verdict.DROP
            g == UNKNOWN -> if (final) Verdict.UNSURE else Verdict.WAIT
            // बराबरी (देवनागरी, पहचान-शब्द नहीं): चुनी भाषा देवनागरी वाली (मराठी) / Auto = पूरी बात पर सुनाओ; तमिल वगैरह चुनी = यह हिंदी ही है
            g == "" -> if (!final) Verdict.WAIT else if (chosen == AUTO || of(chosen)?.base == DEVA) Verdict.PLAY else Verdict.DROP
            chosen == AUTO || g == chosen -> Verdict.PLAY
            // देवनागरी में "mr" चुना था और पहचान "" - ऊपर; बाकी: चुनी से अलग भाषा
            else -> if (final) Verdict.UNSURE else Verdict.WAIT
        }
    }

    /** अनुवाद लगभग वही जो सुना (हिंदी को "हिंदी में" दोहराया) = मेरी हिंदी थी -> DROP */
    fun sameAsHeard(heard: String, out: String): Boolean {
        val a = words(heard).toSet(); val b = words(out).toSet()
        if (a.size < 2 || b.size < 2) return false
        val inter = a.intersect(b).size.toDouble()
        return inter / a.union(b).size >= 0.6
    }

    /** किसी Indic लिपि का text -> देवनागरी (बाकी अक्षर वैसे ही) */
    fun toDeva(t: String): String {
        val sb = StringBuilder(t.length)
        for (ch in t) {
            val c = ch.code
            val l = ALL.firstOrNull { it.base != 0 && it.base != DEVA && c in (it.base + 1)..(it.base + 0x7F) }
            sb.append(if (l != null) (c - l.base + DEVA).toChar() else ch)
        }
        return sb.toString()
    }

    /** देवनागरी text -> भाषा की लिपि (मराठी / हिंदी = वैसा ही) */
    fun fromDeva(t: String, code: String): String {
        val l = of(code) ?: return t
        if (l.base == 0 || l.base == DEVA) return t
        val sb = StringBuilder(t.length)
        for (ch in t) {
            val c = ch.code
            // । और ॥ सब भारतीय लिपियों में देवनागरी वाले ही इस्तेमाल होते हैं
            sb.append(if (c in (DEVA + 1)..0x0963 || c in 0x0966..0x097F) (c - DEVA + l.base).toChar() else ch)
        }
        return sb.toString()
    }

    /** स्क्रीन पर तमिल / तेलुगु वगैरह के नीचे "हिंदी में पढ़ें" वाली पंक्ति (देवनागरी हो तो "") */
    fun readInHindi(t: String): String {
        val s = mainScript(t)
        return if (s.isEmpty() || s == "deva" || s == "latin" || s == "arab") "" else toDeva(t)
    }
}
