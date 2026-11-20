package com.dk.assistant.voice.st3

/**
 * 20 नवंबर 2026 (मालिक: "कोई भी नंबर, जैसे 245, या 4 बजकर 24 मिनट, सही से नहीं बोल पाता - सुनाई ही नहीं देता"):
 * जड़ - ऑफ़लाइन आवाज़ (Supertonic 3) को अंक ("4:24", "245") सीधे जाते थे; मॉडल हिंदी में अंक साफ़ नहीं पढ़ता.
 * अब बोलने से पहले अंक -> हिंदी शब्द:
 *  - समय "4:24" = "चार बजकर चौबीस मिनट", "4:00" = "चार बजे", "16:20" = "शाम चार बजकर बीस मिनट", AM/PM भी
 *  - गिनती "245" = "दो सौ पैंतालीस", "1,25,000" = "एक लाख पच्चीस हज़ार", "2.5" = "दो दशमलव पाँच", "40%" = "चालीस प्रतिशत",
 *    "₹500" = "पाँच सौ रुपये"
 *  - फ़ोन नंबर / लंबे अंक (7+ अंक, या 0 से शुरू) = एक-एक अंक ("9 8 7..." = "नौ आठ सात ...")
 *  - अक्षर से जुड़े अंक ("4G", "MP3", "1st") वैसे ही.
 * Android import नहीं - JVM test ([com.dk.assistant.St3NumbersTest]).
 */
object St3Numbers {
    private val UNDER_100 = listOf(
        "शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ",
        "दस", "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस",
        "बीस", "इक्कीस", "बाईस", "तेईस", "चौबीस", "पच्चीस", "छब्बीस", "सत्ताईस", "अट्ठाईस", "उनतीस",
        "तीस", "इकतीस", "बत्तीस", "तैंतीस", "चौंतीस", "पैंतीस", "छत्तीस", "सैंतीस", "अड़तीस", "उनतालीस",
        "चालीस", "इकतालीस", "बयालीस", "तैंतालीस", "चवालीस", "पैंतालीस", "छियालीस", "सैंतालीस", "अड़तालीस", "उनचास",
        "पचास", "इक्यावन", "बावन", "तिरपन", "चौवन", "पचपन", "छप्पन", "सत्तावन", "अट्ठावन", "उनसठ",
        "साठ", "इकसठ", "बासठ", "तिरसठ", "चौंसठ", "पैंसठ", "छियासठ", "सड़सठ", "अड़सठ", "उनहत्तर",
        "सत्तर", "इकहत्तर", "बहत्तर", "तिहत्तर", "चौहत्तर", "पचहत्तर", "छिहत्तर", "सतहत्तर", "अठहत्तर", "उनासी",
        "अस्सी", "इक्यासी", "बयासी", "तिरासी", "चौरासी", "पचासी", "छियासी", "सत्तासी", "अट्ठासी", "नवासी",
        "नब्बे", "इक्यानवे", "बानवे", "तिरानवे", "चौरानवे", "पंचानवे", "छियानवे", "सत्तानवे", "अट्ठानवे", "निन्यानवे",
    )

    /** इससे लंबे अंक = एक-एक अंक (फ़ोन नंबर, OTP, खाता) */
    const val DIGIT_BY_DIGIT_FROM = 7

    private const val NB = "(?<![\\p{L}\\p{M}\\d.])"          // पहले अक्षर/अंक नहीं
    private const val NA = "(?![\\p{L}\\p{M}\\d])"              // बाद में अक्षर/अंक नहीं
    private val TIME = Regex("$NB([01]?\\d|2[0-3]):([0-5]\\d)(?::[0-5]\\d)?\\s*(am|pm|a\\.m\\.|p\\.m\\.|AM|PM|A\\.M\\.|P\\.M\\.|बजे)?$NA")
    private val MONEY = Regex("(₹|Rs\\.?|INR)\\s?(\\d[\\d,]*(?:\\.\\d+)?)$NA")
    private val NUM = Regex("$NB(\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.(\\d+))?(\\s?%)?$NA")

    /** 0..99,99,99,99,999 तक हिंदी शब्दों में; उससे बड़ा = एक-एक अंक */
    fun words(n: Long): String {
        if (n < 0) return "माइनस " + words(-n)
        if (n < 100) return UNDER_100[n.toInt()]
        if (n >= 1_000_000_000_000L) return digits(n.toString())
        val parts = ArrayList<String>()
        var r = n
        val crore = r / 10_000_000; r %= 10_000_000
        if (crore > 0) parts += words(crore) + " करोड़"
        val lakh = r / 100_000; r %= 100_000
        if (lakh > 0) parts += words(lakh) + " लाख"
        val th = r / 1000; r %= 1000
        if (th > 0) parts += words(th) + " हज़ार"
        val h = r / 100; r %= 100
        if (h > 0) parts += UNDER_100[h.toInt()] + " सौ"
        if (r > 0) parts += UNDER_100[r.toInt()]
        return parts.joinToString(" ")
    }

    /** "987" = "नौ आठ सात" */
    fun digits(s: String): String = s.filter { it.isDigit() }.map { UNDER_100[it - '0'] }.joinToString(" ")

    /** "4:24" -> "चार बजकर चौबीस मिनट". [mark] = am/pm/बजे (या null) */
    fun time(h24: Int, m: Int, mark: String?): String {
        val k = mark?.lowercase()?.replace(".", "")?.takeIf { it != "बजे" }      // "4:24 बजे" = बिना am/pm जैसा
        val pm = k == "pm" || (k == null && h24 >= 12)
        val am = k == "am" || (k == null && h24 == 0)                            // "0:30" = रात बारह बजकर तीस
        val h = if (am || pm) (h24 % 12).let { if (it == 0) 12 else it } else h24
        // दिन का हिस्सा - सिर्फ़ जब पक्का पता हो (am/pm लिखा या 24 घंटे वाला समय)
        val hour24 = when { pm && h24 < 12 -> h24 + 12; am && h24 == 12 -> 0; else -> h24 }
        val part = if (!am && !pm) "" else when (hour24) {
            in 4..11 -> "सुबह "
            in 12..15 -> "दोपहर "
            in 16..19 -> "शाम "
            else -> "रात "
        }
        val hw = words(h.toLong())
        return part + if (m == 0) "$hw बजे" else "$hw बजकर ${words(m.toLong())} मिनट"
    }

    /** text में अंक -> बोलने लायक हिंदी शब्द. अंक न हों = वैसा ही */
    fun speak(text: String): String {
        var t = devanagariDigits(text)
        if (t.none { it.isDigit() }) return text
        t = TIME.replace(t) { m ->
            time(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null })
        }
        t = MONEY.replace(t) { m -> amount(m.groupValues[2].replace(",", "")) + " रुपये" }
        t = NUM.replace(t) { m ->
            val whole = m.groupValues[1]
            val frac = m.groupValues[2]
            val pct = if (m.groupValues[3].isNotEmpty()) " प्रतिशत" else ""
            val plain = whole.replace(",", "")
            val spoken = when {
                !whole.contains(',') && frac.isEmpty() && (plain.length >= DIGIT_BY_DIGIT_FROM || (plain.length >= 3 && plain.startsWith("0"))) -> digits(plain)
                else -> amount(if (frac.isEmpty()) plain else "$plain.$frac")
            }
            spoken + pct
        }
        return t
    }

    /** "2.5" = "दो दशमलव पाँच", "245" = "दो सौ पैंतालीस" */
    private fun amount(s: String): String {
        val dot = s.indexOf('.')
        val whole = if (dot < 0) s else s.substring(0, dot)
        val n = whole.toLongOrNull() ?: return digits(whole)
        val w = if (whole.length > 12) digits(whole) else words(n)
        return if (dot < 0 || dot == s.lastIndex) w else "$w दशमलव ${digits(s.substring(dot + 1))}"
    }

    private fun devanagariDigits(s: String): String =
        if (s.none { it in '०'..'९' }) s else buildString { s.forEach { append(if (it in '०'..'९') '0' + (it - '०') else it) } }
}
