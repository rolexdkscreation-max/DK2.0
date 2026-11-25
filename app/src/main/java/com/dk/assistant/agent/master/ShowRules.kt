package com.dk.assistant.agent.master

import java.net.URLEncoder

/**
 * 25 नवंबर 2026 (मालिक: "जो काम वो अंदर ही अंदर कर रहा है, मैं बोलूँ दिखाओ तो वो पेज खोल के मुझे दिखाए,
 * चाहे वो कोई भी काम कर रहा हो" + "ऐप नहीं है तो ब्राउज़र में वो काम चालू होना चाहिए").
 * Pure हिस्सा (JVM test): DK ने आख़िरी काम कहाँ किया ([Spot]), "पेज दिखाओ" पहचानना, ऐप न हो तो वेबसाइट का पता.
 * Android वाला हिस्सा = [com.dk.assistant.ShowPage] + [com.dk.assistant.BrowserShowActivity].
 */
object ShowRules {
    /**
     * DK_WEB = DK कंप्यूटर का अपना (छिपा) ब्राउज़र - वही चलता पेज सामने लाते हैं (login / cart / बुकिंग वहीं के वहीं);
     * WEB = फ़ोन के ब्राउज़र में खुला पता; APP = कोई ऐप जिसमें DK ने काम किया (उसी स्क्रीन पर वापस).
     */
    enum class Kind { DK_WEB, WEB, APP }

    data class Spot(val kind: Kind, val target: String, val title: String, val at: Long, val pay: Boolean = false)

    /** इतना पुराना काम = "दिखाओ" पर नहीं खोलते (कुछ और समझ लिया होगा) */
    const val FRESH_MS = 3 * 60 * 60 * 1000L
    const val KEEP = 6

    /** नया पहले; वही जगह दोबारा = ऊपर आ जाए (दोहराव नहीं) */
    fun push(list: List<Spot>, s: Spot): List<Spot> =
        (listOf(s) + list.filterNot { it.kind == s.kind && it.target == s.target }).take(KEEP)

    /** दिखाने लायक सबसे नई जगह (null = हाल का कोई काम नहीं) */
    fun pick(list: List<Spot>, now: Long): Spot? = list.filter { now - it.at in 0..FRESH_MS && it.target.isNotBlank() }.maxByOrNull { it.at }

    private val SHOW = listOf("दिखाओ", "दिखा दो", "दिखाइए", "दिखाइये", "दिखाना", "दिखा दीजिए", "खोल के दे", "खोलकर दे", "खोल के दो", "खोलकर दो",
        "show", "dikhao", "dikha do")
    private val WHAT = listOf("पेज", "page", "पेज़", "विंडो", "window", "ब्राउज़र", "ब्राउजर", "browser",
        "कहाँ है", "कहां है", "कहाँ पर", "कहां पर", "जो कर रहे", "जो कर रही", "क्या कर रहे", "क्या कर रही", "जो चल रहा", "पीछे वाला", "अंदर वाला",
        "पेमेंट वाला", "भुगतान वाला", "आख़िरी वाला", "आखिरी वाला", "लास्ट वाला")

    private val NOT = listOf("शेयर", "share", "वेबसाइट", "website", "instagram", "इंस्टा", "facebook", "फेसबुक", "फ़ेसबुक", "youtube", "यूट्यूब",
        "whatsapp", "व्हाट्सएप", "फोटो", "फ़ोटो", "photo", "वीडियो", "video")

    /** "पेज दिखाओ" / "वो पेज खोल के दो" / "कहाँ है दिखाओ" - दुकान का सामान ("शर्ट दिखाओ") नहीं */
    fun isShowAsk(text: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("[,.!?।\"']+"), " ").replace(Regex("\\s+"), " ").trim() + " "
        // स्क्रीन शेयर / अपनी बनाई वेबसाइट / किसी ऐप का पेज ("Instagram पेज दिखाओ") = अलग काम
        if (NOT.any { t.contains(it) }) return false
        return SHOW.any { t.contains(it) } && WHAT.any { t.contains(it) }
    }

    /** Gemini के लिए नतीजा - सच्चा (खुला तो "खुला", नहीं तो साफ़ "नहीं") */
    fun shownNote(s: Spot): String = "SHOWN: the page/app from DK's last task is now open in front of the owner on his phone" +
        (if (s.title.isNotBlank()) " (\"${s.title.take(80)}\")" else "") + ". Tell him in one short Hindi line, e.g. \"यह रहा, स्क्रीन पर खोल दिया\"." +
        (if (s.pay) " This is the payment step - he pays himself; DK never pays." else "") +
        " DK keeps working in the background if a task is still running."

    const val NOTHING_NOTE = "NOTHING_TO_SHOW: DK has no page or app from a recent task to open. Tell the owner in one short Hindi line " +
        "(\"अभी कोई खुला पेज नहीं है\") and ask what he wants to open. Do NOT pretend something opened."

    const val FAIL_NOTE = "ERROR: the page could not be opened on the screen. Tell the owner in one short Hindi line - never say it opened."

    private fun enc(q: String) = URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")

    /** ऐप न हो = उसी दुकान की वेबसाइट पर वही खोज (फ़ोन के ब्राउज़र में) */
    fun shopWeb(key: String, q: String): String? {
        val e = enc(q)
        return when (key.lowercase()) {
            "flipkart" -> "https://www.flipkart.com/search?q=$e"
            "amazon" -> "https://www.amazon.in/s?k=$e"
            "meesho" -> "https://www.meesho.com/search?q=$e"
            "myntra" -> "https://www.myntra.com/$e"
            "ajio" -> "https://www.ajio.com/search/?text=$e"
            else -> null
        }
    }

    /** बुकिंग का ऐप न हो = उसकी वेबसाइट (नाम लिया ऐप पहले, वरना किस्म से) */
    fun bookWeb(kind: BookRules.Kind, app: String?): String? {
        val a = app.orEmpty().lowercase()
        return when {
            a.contains("irctc") -> "https://www.irctc.co.in/nget/train-search"
            a.contains("ixigo") -> "https://www.ixigo.com"
            a.contains("confirmtkt") -> "https://www.confirmtkt.com"
            a.contains("redbus") -> "https://www.redbus.in"
            a.contains("makemytrip") -> "https://www.makemytrip.com"
            a.contains("goibibo") -> "https://www.goibibo.com"
            a.contains("oyo") -> "https://www.oyorooms.com"
            a.contains("booking.com") -> "https://www.booking.com"
            a.contains("uber") -> "https://m.uber.com"
            a.contains("ola") -> "https://book.olacabs.com"
            a.contains("rapido") -> "https://www.rapido.bike"
            a.contains("bookmyshow") -> "https://in.bookmyshow.com"
            a.contains("district") -> "https://www.district.in"
            else -> when (kind) {
                BookRules.Kind.TRAIN -> "https://www.irctc.co.in/nget/train-search"
                BookRules.Kind.BUS -> "https://www.redbus.in"
                BookRules.Kind.FLIGHT -> "https://www.makemytrip.com/flights/"
                BookRules.Kind.HOTEL -> "https://www.makemytrip.com/hotels/"
                BookRules.Kind.CAB -> "https://m.uber.com"
                BookRules.Kind.MOVIE -> "https://in.bookmyshow.com"
                BookRules.Kind.OTHER -> null
            }
        }
    }
}
