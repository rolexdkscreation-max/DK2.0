package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक): "DK YouTube का वीडियो चलाओ और उसे फुल स्क्रीन में कर देना. अगर कोई ऐड आ रहा है तो ऐड स्किप कर देना -
 * वहाँ रुककर देखे कि ऐड आ रहा है तो स्किप करे, नहीं आ रहा तो चला दे और बता दे कि हो गया."
 *
 * यहाँ सिर्फ़ pure नियम (Android import नहीं -> JVM test):
 *  - [extras]: बात में से "फुल स्क्रीन" / "ऐड स्किप" वाले हिस्से अलग - ताकि YouTube की खोज में ये शब्द न जाएँ और
 *    "… और फुल स्क्रीन कर दो" अलग काम (AI को) न बने (पहले पूरी बात धीमे रास्ते पर जाती थी)
 *  - [isSkip] / [isAd] / [isFullBtn] / [isExitFull]: YouTube की स्क्रीन के बटन पहचानना (id + लिखा + description)
 *  - [report]: Live को सच्चा नतीजा ("ऐड स्किप किया" / "ऐड नहीं आया" / "फुल स्क्रीन किया")
 * असली टैप [DKAccessibilityService.ytAfterPlay] (DKAccessibilityYt.kt) करता है.
 */
object YtAssist {

    data class Extras(val text: String, val full: Boolean, val skipAd: Boolean)

    private const val FULL_WORD = "(?:फुल|फ़ुल|फूल|फुल्ल|full|ful)\\s*-?\\s*(?:स्क्रीन|स्क्रिन|screen)|फुलस्क्रीन|फ़ुलस्क्रीन|fullscreen|पूरी\\s+स्क्रीन|बड़ी\\s+स्क्रीन|लैंडस्केप|landscape"
    private const val AD_WORD = "(?:ऐड|एड|ऐड्स|एड्स|विज्ञापन|ad|ads|advertisement|add)"
    private const val DO_TAIL = "(?:\\s+(?:में|मे|पर|पे|पे\\s+ही|में\\s+ही|mein|me|pe|par))?(?:\\s+(?:कर\\s+दीजिए|कर\\s+देना|कर\\s+दिया|कर\\s+दो|कर\\s+दे|कर\\s+के|करके|करना|करिए|करें|करो|कीजिए|कर|चला\\s+देना|चला\\s+दो|चलाना|चलाओ|दिखाओ|dikhao|kar\\s+dena|kar\\s+do|kardo|karo|kar))?(?:\\s+(?:देना|दो|दीजिए|ना|प्लीज|please))?"
    /** हिस्सा पूरे शब्द पर ख़त्म ("करो" का आधा "कर" नहीं) */
    private const val END = "(?=$|[\\s,।.!?])"

    /** "और उसे फुल स्क्रीन में कर देना" / "फुल स्क्रीन करके चलाओ" */
    private val FULL_PART = Regex(
        "(?:(?:,|।)?\\s*(?:और|फिर|aur|and|then)\\s+)?(?:(?:उसे|इसे|उसको|इसको|वीडियो\\s+को|video\\s+ko|use|ise)\\s+)?(?:(?:in|on|में)\\s+)?(?:$FULL_WORD)$DO_TAIL$END",
        RegexOption.IGNORE_CASE)

    /** "अगर कोई ऐड आ रहा है तो ऐड स्किप कर देना" / "ऐड आए तो स्किप करो" / "ऐड स्किप करना" / "skip the ad" */
    private val AD_PART = Regex(
        "(?:(?:,|।)?\\s*(?:और|फिर|aur|and|then)\\s+)?(?:(?:अगर|जब|if|agar)\\s+)?(?:(?:कोई|कुछ|बीच\\s+में|koi|any)\\s+)?$AD_WORD" +
            "(?:\\s+(?:आ\\s+रहा\\s+है|आ\\s+रहा|आ\\s+रहे\\s+हैं|आता\\s+है|आए|आये|आया|आ\\s+जाए|आ\\s+जाये|आ\\s+गया|आ\\s+गए|हो|है|aaye|aaya|aa\\s+raha|comes|is\\s+there|shows))?" +
            "(?:\\s+(?:तो|to|then))?(?:\\s+(?:उसे|इसे|उसको|use|ise|$AD_WORD))?\\s+(?:स्किप|skip|छोड़|हटा|काट|बंद)$DO_TAIL$END" +
            "|(?:(?:,|।)?\\s*(?:और|फिर|aur|and|then)\\s+)?(?:स्किप|skip)\\s+(?:the\\s+)?$AD_WORD$DO_TAIL$END",
        RegexOption.IGNORE_CASE)

    /**
     * बात में फुल स्क्रीन / ऐड स्किप वाला हिस्सा है? तो बाकी साफ़ बात + झंडे. कुछ नहीं = null (बात वैसी ही).
     * "स्क्रीन शॉट" / "स्क्रीन शेयर" वाले \"स्क्रीन\" को नहीं छूता (सिर्फ़ फुल/पूरी स्क्रीन).
     */
    fun extras(raw: String): Extras? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        var out = t
        var full = false
        var skip = false
        AD_PART.find(out)?.let { m -> skip = true; out = out.removeRange(m.range) }
        FULL_PART.find(out)?.let { m -> full = true; out = out.removeRange(m.range) }
        if (!full && !skip) return null
        out = out.replace(Regex("\\s+"), " ").trim().trim(',', '।', '.', ' ')
            .replace(Regex("\\s+(?:और|फिर|aur|and|then|तो)$", RegexOption.IGNORE_CASE), "").trim()
        return Extras(out, full, skip)
    }

    /** फुल स्क्रीन माँगा? (ऊपर वाला ही नियम) */
    fun wantsFull(t: String): Boolean = extras(t)?.full == true

    private fun low(s: String?) = s.orEmpty().trim().lowercase()
    private fun idOf(id: String?) = low(id).substringAfter(":id/")

    private val SKIP_IDS = listOf("skip_ad_button", "skip_ad", "ad_skip", "skip_button", "modern_skip_ad", "skip_ad_text")
    private val SKIP_TEXT = setOf("skip", "skip ad", "skip ads", "skip ad(s)", "skip ads(s)", "स्किप", "स्किप करें", "स्किप करे",
        "विज्ञापन छोड़ें", "विज्ञापनों को छोड़ें", "विज्ञापन स्किप करें", "छोड़ें")

    /** "Skip / Skip ad / विज्ञापन छोड़ें" वाला बटन (गिनती वाला "5" नहीं - वह अभी दब ही नहीं सकता) */
    fun isSkip(id: String?, text: String?, desc: String?): Boolean {
        val i = idOf(id)
        val tx = low(text); val d = low(desc)
        if (tx.isNotEmpty() && tx.all { it.isDigit() }) return false
        if (SKIP_IDS.any { i.contains(it) } && (tx.isEmpty() || !tx.first().isDigit())) return true
        if (tx in SKIP_TEXT || d in SKIP_TEXT) return true
        return tx.startsWith("skip ad") || d.startsWith("skip ad") || tx.startsWith("विज्ञापन छोड़") || d.startsWith("विज्ञापन छोड़")
    }

    private val AD_IDS = listOf("ad_progress_text", "ad_badge", "ad_countdown", "ad_text", "modern_ad_badge", "ad_info", "ad_cta",
        "player_learn_more_button", "ad_title", "ad_overlay", "skip_ad", "ad_attribution", "ad_preview")
    private val AD_TEXT = Regex("^(?:ad|ads|sponsored|विज्ञापन|प्रायोजित)(?:$|\\s|·|•|:|\\d)", RegexOption.IGNORE_CASE)

    /** वीडियो पर अभी ऐड चल रहा है? (ऐड का बैज / \"Ad · 0:05\" / Skip बटन / Visit advertiser) */
    fun isAd(id: String?, text: String?, desc: String?): Boolean {
        val i = idOf(id)
        if (AD_IDS.any { i.contains(it) }) return true
        val tx = low(text); val d = low(desc)
        if (AD_TEXT.containsMatchIn(tx) || AD_TEXT.containsMatchIn(d)) return true
        return tx.startsWith("visit advertiser") || d.startsWith("visit advertiser") || tx.startsWith("विज्ञापन देने वाले")
    }

    /** \"फुल स्क्रीन\" वाला बटन (बाहर निकलने वाला नहीं) */
    fun isFullBtn(id: String?, desc: String?): Boolean {
        val i = idOf(id); val d = low(desc)
        if (isExitFull(d)) return false
        if (i.contains("fullscreen_button") || i == "fullscreen") return true
        return d.contains("full screen") || d.contains("fullscreen") || d.contains("फ़ुल स्क्रीन") || d.contains("फुल स्क्रीन") ||
            d.contains("पूर्ण स्क्रीन") || d.contains("फ़ुलस्क्रीन")
    }

    /** बटन कह रहा है \"फुल स्क्रीन से बाहर\" = अभी फुल स्क्रीन है */
    fun isExitFull(desc: String?): Boolean {
        val d = low(desc)
        return (d.contains("exit") && (d.contains("full screen") || d.contains("fullscreen"))) ||
            ((d.contains("स्क्रीन") || d.contains("screen")) && (d.contains("बाहर") || d.contains("बंद करें") || d.contains("से निकलें")))
    }

    enum class Ad { NONE, SKIPPED, UNSKIPPABLE }

    /** Live / AgentRunner को सच्चा नतीजा - सिर्फ़ जो सच में हुआ */
    fun report(ad: Ad, fullAsked: Boolean, fullDone: Boolean): String = buildList {
        when (ad) {
            Ad.SKIPPED -> add("an ad came and DK skipped it (ऐड आया था - स्किप कर दिया)")
            Ad.UNSKIPPABLE -> add("an ad came that cannot be skipped - the video starts right after it (ऐड बिना स्किप वाला था)")
            Ad.NONE -> add("no ad came (कोई ऐड नहीं आया)")
        }
        if (fullAsked) add(if (fullDone) "the video is now full screen (फुल स्क्रीन कर दिया)" else "full screen could NOT be turned on (फुल स्क्रीन नहीं हो पाया)")
    }.joinToString("; ", prefix = " • ")

    /** नतीजे में से YouTube वाली लाइन (Live / बोलने के लिए); नहीं = "" */
    fun note(r: String): String = r.substringAfter(" • ", "").trim()

    /** बोलने लायक हिंदी: "ऐड आया था - स्किप कर दिया, फुल स्क्रीन कर दिया" */
    fun noteHi(r: String): String = Regex("\\(([^()]+)\\)").findAll(note(r)).map { it.groupValues[1] }.joinToString(", ")

    /** ऐड दिखा तो Skip बटन का इतना इंतज़ार (YouTube ~5 s बाद देता है) */
    const val SKIP_WAIT_MS = 9_000L
    /** वीडियो खुलने के बाद ऐड दिखने का इतना इंतज़ार */
    const val AD_LOOK_MS = 2_500L
    /** बाद में आने वाले दूसरे ऐड पर पीछे से नज़र */
    const val WATCH_MS = 60_000L
}
