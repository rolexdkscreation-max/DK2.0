package com.dk.assistant.voice.engine

/**
 * नवंबर 2026 (मालिक: "हिंदी या हिंग्लिश में बोले तो जवाब उसी भाषा में, उच्चारण टूटे बिना"):
 * Kokoro हिंदी (`lang = hi`) में बोलता है; रोमन अक्षरों वाला हर शब्द English मानकर पढ़ा जाता है -
 * "main aapka kaam kar deta hoon" = "मेन आप-का काम कार डेटा हून" (टूटा उच्चारण). इसलिए बोलने से पहले:
 *
 * - रोमन में लिखे **हिंदी** शब्द -> देवनागरी ("kar" -> "कर", "hoon" -> "हूँ").
 * - **English** शब्द वैसे ही ("reminder", "meeting", "set") - उनका उच्चारण English जैसा ही अच्छा लगता है.
 * - दो-अर्थी शब्द ("main", "to", "me", "is", "hi"...) सिर्फ़ तब बदलते हैं जब उसी वाक्य में पक्के हिंदी शब्द
 *   भी हों - "This is the main point" जैसा English वाक्य बिल्कुल वैसा ही रहता है.
 * - देवनागरी, अंक, निशान - कुछ नहीं बदलता.
 * बिना Android के (JVM test में चलता है).
 */
object HinglishText {
    private val LATIN_WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
    private val SENTENCE = Regex("(?<=[.!?।॥\\n])")

    /** पक्के हिंदी शब्द (English में इनका कोई आम मतलब नहीं) */
    val HINDI: Map<String, String> = mapOf(
        // हाँ / ना / सम्बोधन
        "haan" to "हाँ", "haa" to "हाँ", "han" to "हाँ", "ha" to "हाँ", "ji" to "जी", "jee" to "जी", "nahi" to "नहीं", "nahin" to "नहीं",
        "nai" to "नहीं", "mat" to "मत", "accha" to "अच्छा", "acha" to "अच्छा", "achha" to "अच्छा", "achchha" to "अच्छा",
        "theek" to "ठीक", "thik" to "ठीक", "bilkul" to "बिल्कुल", "zaroor" to "ज़रूर", "jarur" to "ज़रूर", "zarur" to "ज़रूर",
        "shukriya" to "शुक्रिया", "dhanyavaad" to "धन्यवाद", "dhanyawad" to "धन्यवाद", "namaste" to "नमस्ते", "arre" to "अरे", "are" to "अरे",
        "yaar" to "यार", "bhai" to "भाई", "didi" to "दीदी", "sir" to "सर", "madam" to "मैडम", "wah" to "वाह", "waah" to "वाह",
        // सर्वनाम
        "mai" to "मैं", "mein" to "में", "mujhe" to "मुझे", "mujhko" to "मुझको", "mera" to "मेरा", "meri" to "मेरी", "mere" to "मेरे",
        "hum" to "हम", "hame" to "हमें", "hamein" to "हमें", "humein" to "हमें", "hamara" to "हमारा", "hamari" to "हमारी", "hamare" to "हमारे",
        "aap" to "आप", "aapko" to "आपको", "apko" to "आपको", "aapka" to "आपका", "apka" to "आपका", "aapki" to "आपकी", "apki" to "आपकी",
        "aapke" to "आपके", "apke" to "आपके", "aapne" to "आपने", "apne" to "अपने", "apna" to "अपना", "apni" to "अपनी", "tum" to "तुम",
        "tumhe" to "तुम्हें", "tumhara" to "तुम्हारा", "tumhari" to "तुम्हारी", "tu" to "तू", "tera" to "तेरा", "teri" to "तेरी",
        "ye" to "ये", "yeh" to "यह", "woh" to "वह", "wo" to "वो", "vo" to "वो", "isko" to "इसको", "usko" to "उसको", "unko" to "उनको",
        "iska" to "इसका", "uska" to "उसका", "unka" to "उनका", "inka" to "इनका", "kuch" to "कुछ", "kuchh" to "कुछ", "sab" to "सब",
        "koi" to "कोई", "kisi" to "किसी", "kaun" to "कौन", "kya" to "क्या", "kyaa" to "क्या", "kyun" to "क्यों", "kyon" to "क्यों",
        "kyu" to "क्यों", "kaise" to "कैसे", "kaisa" to "कैसा", "kaisi" to "कैसी", "kahan" to "कहाँ", "kaha" to "कहाँ", "kab" to "कब",
        "kitna" to "कितना", "kitni" to "कितनी", "kitne" to "कितने", "jaise" to "जैसे", "waise" to "वैसे", "aise" to "ऐसे", "aisa" to "ऐसा",
        // क्रिया / सहायक
        "hai" to "है", "hain" to "हैं", "hoon" to "हूँ", "hun" to "हूँ", "hu" to "हूँ", "tha" to "था", "thi" to "थी", "thay" to "थे",
        "hoga" to "होगा", "hogi" to "होगी", "honge" to "होंगे", "hota" to "होता", "hoti" to "होती", "hote" to "होते", "hua" to "हुआ",
        "hui" to "हुई", "hue" to "हुए", "raha" to "रहा", "rahi" to "रही", "rahe" to "रहे", "kar" to "कर", "karo" to "करो",
        "karna" to "करना", "karta" to "करता", "karti" to "करती", "karte" to "करते", "karunga" to "करूँगा", "karungi" to "करूँगी",
        "karenge" to "करेंगे", "kijiye" to "कीजिए", "kariye" to "करिए", "kiya" to "किया", "kiye" to "किए", "ki" to "की", "ke" to "के",
        "ka" to "का", "ko" to "को", "se" to "से", "ne" to "ने", "aur" to "और", "lekin" to "लेकिन", "magar" to "मगर", "ya" to "या",
        "phir" to "फिर", "fir" to "फिर", "kyunki" to "क्योंकि", "kyuki" to "क्योंकि", "agar" to "अगर", "toh" to "तो", "bhi" to "भी",
        "sirf" to "सिर्फ़", "bas" to "बस", "abhi" to "अभी", "kabhi" to "कभी", "jab" to "जब", "tab" to "तब", "yahan" to "यहाँ",
        "wahan" to "वहाँ", "upar" to "ऊपर", "neeche" to "नीचे", "andar" to "अंदर", "bahar" to "बाहर", "saath" to "साथ", "sath" to "साथ",
        "liye" to "लिए", "wala" to "वाला", "wali" to "वाली", "wale" to "वाले", "diya" to "दिया", "di" to "दी", "diye" to "दिए",
        "de" to "दे", "do" to "दो", "dena" to "देना", "deta" to "देता", "deti" to "देती", "dete" to "देते", "dunga" to "दूँगा",
        "dungi" to "दूँगी", "dijiye" to "दीजिए", "le" to "ले", "lo" to "लो", "lena" to "लेना", "liya" to "लिया", "lijiye" to "लीजिए",
        "ja" to "जा", "jao" to "जाओ", "jana" to "जाना", "jata" to "जाता", "jati" to "जाती", "jaate" to "जाते", "gaya" to "गया",
        "gayi" to "गई", "gaye" to "गए", "jayega" to "जाएगा", "jaayega" to "जाएगा", "jayegi" to "जाएगी", "aa" to "आ", "aao" to "आओ",
        "aaya" to "आया", "aayi" to "आई", "aaye" to "आए", "aata" to "आता", "aati" to "आती", "aayega" to "आएगा", "bol" to "बोल",
        "bolo" to "बोलो", "bola" to "बोला", "boliye" to "बोलिए", "batao" to "बताओ", "bataiye" to "बताइए", "bata" to "बता",
        "bataya" to "बताया", "dekho" to "देखो", "dekh" to "देख", "dekhiye" to "देखिए", "suno" to "सुनो", "suniye" to "सुनिए",
        "samajh" to "समझ", "samjha" to "समझा", "samjho" to "समझो", "chahiye" to "चाहिए", "chahte" to "चाहते", "chahta" to "चाहता",
        "chahti" to "चाहती", "sakta" to "सकता", "sakti" to "सकती", "sakte" to "सकते", "milega" to "मिलेगा", "milegi" to "मिलेगी",
        "mila" to "मिला", "mil" to "मिल", "chalo" to "चलो", "chal" to "चल", "ruko" to "रुको", "khol" to "खोल", "kholo" to "खोलो",
        "band" to "बंद", "lagao" to "लगाओ", "laga" to "लगा", "lagta" to "लगता", "lagti" to "लगती", "rakho" to "रखो", "rakh" to "रख",
        "bhejo" to "भेजो", "bhej" to "भेज", "bheja" to "भेजा", "socho" to "सोचो", "pata" to "पता",
        // संज्ञा / विशेषण / समय
        "kaam" to "काम", "baat" to "बात", "baatein" to "बातें", "log" to "लोग", "ghar" to "घर", "paani" to "पानी", "khana" to "खाना",
        "din" to "दिन", "raat" to "रात", "subah" to "सुबह", "shaam" to "शाम", "aaj" to "आज", "kal" to "कल", "parso" to "परसों",
        "samay" to "समय", "waqt" to "वक़्त", "baje" to "बजे", "ghanta" to "घंटा", "ghante" to "घंटे", "minat" to "मिनट",
        "mausam" to "मौसम", "garmi" to "गर्मी", "sardi" to "सर्दी", "baarish" to "बारिश", "duniya" to "दुनिया", "zindagi" to "ज़िंदगी",
        "dost" to "दोस्त", "pyaar" to "प्यार", "pyar" to "प्यार", "dil" to "दिल", "khush" to "खुश", "dukh" to "दुख", "achhi" to "अच्छी",
        "acchi" to "अच्छी", "acche" to "अच्छे", "achhe" to "अच्छे", "bura" to "बुरा", "bahut" to "बहुत", "bohot" to "बहुत",
        "bahot" to "बहुत", "zyada" to "ज़्यादा", "jyada" to "ज़्यादा", "kam" to "कम", "thoda" to "थोड़ा", "thodi" to "थोड़ी",
        "jaldi" to "जल्दी", "dheere" to "धीरे", "dhire" to "धीरे", "sahi" to "सही", "galat" to "ग़लत", "naya" to "नया", "nayi" to "नई",
        "purana" to "पुराना", "pehle" to "पहले", "pahle" to "पहले", "baad" to "बाद", "turant" to "तुरंत", "seedha" to "सीधा",
        "ek" to "एक", "teen" to "तीन", "char" to "चार", "paanch" to "पाँच", "das" to "दस", "sau" to "सौ", "hazaar" to "हज़ार",
        "hazar" to "हज़ार", "lakh" to "लाख", "rupaye" to "रुपये", "rupay" to "रुपये", "paise" to "पैसे", "phone" to "फ़ोन",
    )

    /** दो-अर्थी (English में भी आम शब्द) - सिर्फ़ हिंग्लिश वाक्य में बदलते हैं */
    val AMBIGUOUS: Map<String, String> = mapOf(
        "main" to "मैं", "to" to "तो", "me" to "में", "is" to "इस", "us" to "उस", "in" to "इन", "un" to "उन", "hi" to "ही",
        "par" to "पर", "pe" to "पे", "the" to "थे", "na" to "ना", "no" to "नो", "so" to "सो", "jo" to "जो",
        "yah" to "यह", "bhaiya" to "भैया", "sun" to "सुन", "rah" to "रह", "rahiye" to "रहिए", "kahi" to "कहीं", "kahin" to "कहीं",
        "aage" to "आगे", "peeche" to "पीछे", "ab" to "अब", "tak" to "तक", "ho" to "हो", "hona" to "होना",
        "mere" to "मेरे", "pass" to "पास", "paas" to "पास", "baki" to "बाकी", "baaki" to "बाकी", "bad" to "बाद", "mar" to "मार",
        "man" to "मन", "date" to "डेट", "time" to "टाइम", "do" to "दो", "de" to "दे", "die" to "दिए", "gaye" to "गए",
    )

    /** बोलने लायक text: हिंग्लिश वाक्यों के रोमन हिंदी शब्द देवनागरी में. बाकी सब वैसा ही */
    fun toSpeakable(text: String): String {
        if (text.none { it in 'a'..'z' || it in 'A'..'Z' }) return text
        return text.split(SENTENCE).joinToString("") { fixSentence(it) }
    }

    private fun isDevanagari(c: Char) = c in '\u0900'..'\u097F'

    private fun fixSentence(s: String): String {
        val words = LATIN_WORD.findAll(s).map { it.value.lowercase() }.toList()
        if (words.isEmpty()) return s
        val strong = words.count { it in HINDI && it !in ENGLISH_GUARD }
        val hasDeva = s.any { isDevanagari(it) }
        // हिंग्लिश वाक्य: पक्के हिंदी शब्द काफ़ी हों (या वाक्य में देवनागरी भी हो)
        val hinglish = strong >= 2 && strong * 10 >= words.size * 3 || (strong >= 1 && (hasDeva || words.size <= 3))
        if (!hinglish) return s
        return LATIN_WORD.replace(s) { m ->
            val w = m.value.lowercase()
            HINDI[w] ?: AMBIGUOUS[w] ?: m.value
        }
    }

    /** आम English शब्द जो रोमन हिंदी की सूची में भी हैं - इन्हें "पक्का हिंदी" गिनती में नहीं लेते */
    private val ENGLISH_GUARD = setOf("are", "do", "de", "band", "log", "sir", "madam", "phone", "char", "kal", "das", "bas", "tab", "mat", "ha", "han", "lo", "le", "di", "ja", "aa")
}
