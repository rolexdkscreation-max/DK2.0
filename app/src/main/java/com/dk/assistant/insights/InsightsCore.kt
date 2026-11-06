package com.dk.assistant.insights

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * नवंबर 2026 (मालिक: "मेरा पूरा Facebook / Instagram / YouTube रिव्यू करो - किस रील पर कितने व्यूज़,
 * रीच क्यों गिर रही है, क्या चल रहा है, क्या नहीं, ट्रेंडिंग क्या है - एक बार बोलूँ तो पूरा काम करके बताओ").
 *
 * यह हिस्सा Android के बिना (unit test में चलता है):
 *  - [InsightsVoice]  = बात "रिव्यू / चेक / व्यूज़ / रीच" वाली है? किस प्लेटफ़ॉर्म की?
 *  - [Num] / [Pct] / [Dur] = स्क्रीन पर लिखे "1.2K", "3.4 लाख", "4.5%", "0:07", "120 घंटे" -> नंबर
 *  - [Post] / [Account] = एक रील/वीडियो और पूरे खाते के आँकड़े
 *  - [Insights.analyze] = आँकड़ों से पक्की बातें (हाल की रील पहले से कितनी कम/ज़्यादा, सबसे अच्छी/कमज़ोर, शुरुआत कमज़ोर...)
 *  - [InsightsReport] = बिना AI भी पूरी रिपोर्ट + AI के लिए prompt
 *
 * ईमानदारी: कोई भी टूल algorithm की "असली" वजह नहीं जानता - DK आँकड़ों से सबसे संभावित कारण बताती है,
 * और जो आँकड़ा नहीं मिला, उसे "नहीं मिला" ही कहती है (कभी अंदाज़े का नंबर नहीं)।
 */
enum class Platform(val id: String, val hi: String, val en: String, val pkgs: List<String>, val itemHi: String) {
    INSTAGRAM("instagram", "इंस्टाग्राम", "Instagram", listOf("com.instagram.android"), "रील"),
    FACEBOOK("facebook", "फेसबुक", "Facebook", listOf("com.facebook.katana", "com.facebook.lite"), "रील/पोस्ट"),
    /** पहले YouTube Studio (असली analytics वहीं), न हो तो YouTube ऐप (उसमें भी "आपके वीडियो" के आँकड़े) */
    YOUTUBE("youtube", "यूट्यूब", "YouTube", listOf("com.google.android.apps.youtube.creator", "com.google.android.youtube"), "वीडियो");

    companion object {
        fun of(id: String?): Platform? = values().firstOrNull { it.id.equals(id?.trim(), true) || it.en.equals(id?.trim(), true) }
    }
}

/** बोली हुई बात -> रिव्यू का काम (या null) */
object InsightsVoice {
    data class Request(val platforms: List<Platform>, val text: String)

    private val IG = listOf("instagram", "insta", "इंस्टाग्राम", "इन्स्टाग्राम", "इंस्टा", "इन्स्टा", "instagaram", "इंस्टग्राम", "انسٹاگرام", "انسٹا گرام", "انسٹا")
    private val FB = listOf("facebook", "fb ", "फेसबुक", "फ़ेसबुक", "फेस बुक", "fesbuk", "meta business", "فیس بک", "فیسبک")
    private val YT = listOf("youtube", "you tube", "यूट्यूब", "यू ट्यूब", "युट्यूब", "यूटूब", "yt studio", "youtube studio", "यूट्यूब स्टूडियो", "studio", "स्टूडियो", "یوٹیوب", "یو ٹیوب", "یوٹیوب اسٹوڈیو")
    private val ALL = listOf("तीनों", "सबका", "सब का", "सारे सोशल", "सारा सोशल", "सोशल मीडिया", "social media", "all social", "सब प्लेटफ़ॉर्म", "सब प्लेटफार्म", "हर प्लेटफ़ॉर्म", "سوشل میڈیا", "تینوں")

    /** रिव्यू / आँकड़े वाले शब्द - सिर्फ़ "इंस्टाग्राम खोलो" रिव्यू नहीं */
    private val REVIEW = listOf(
        "रिव्यू", "review", "रीव्यू", "एनालिसिस", "analysis", "analyse", "analyze", "एनालाइज़", "एनालाइज",
        "इनसाइट", "insight", "एनालिटिक्स", "analytics", "डैशबोर्ड", "dashboard", "परफॉर्मेंस", "परफॉरमेंस", "performance",
        "व्यूज़", "व्यूज", "views", "व्यू ", "रीच", "reach", "वायरल", "viral", "ग्रोथ", "growth", "स्टैट", "stats",
        "रिपोर्ट", "report", "ऑडिट", "audit", "कितना व्यू", "कितने व्यू", "कितना चल", "क्यों नहीं चल", "क्यों गिर", "गिर रहा", "गिर रही",
        "ट्रेंडिंग", "trending", "सब्सक्राइबर", "subscriber", "फॉलोअर", "follower", "वॉच टाइम", "watch time", "इंप्रेशन", "impression", "ctr",
        // नवंबर 2026: DK लाइव का caption उर्दू लिपि में भी आता है ("انسٹاگرام کا ریویو کرو") - पहले रिव्यू पहचाना ही नहीं जाता था
        "ریویو", "ریویوو", "جائزہ", "تجزیہ", "ویوز", "ریچ", "انسائٹ", "ڈیش بورڈ", "رپورٹ", "فالوور", "سبسکرائبر",
        // नवंबर 2026: कमाई / रिकमेंडेशन / पॉलिसी के सवाल भी रिव्यू ("फेसबुक का मोनेटाइज़ेशन क्यों हटा")
        "मोनेटाइज", "मोनेटाइज़", "monetiz", "monetis", "डीमोनेटाइज", "demonetiz", "रिकमेंडेशन", "रेकमेंडेशन", "recommendation",
        "पॉलिसी", "policy", "एल्गोरिदम", "एल्गोरिथम", "algorithm", "مونیٹائز", "ڈیمونیٹائز", "ریکمنڈیشن", "پالیسی", "الگورتھم",
    )
    /** "चेक करो" अकेला भी रिव्यू है, अगर साथ में "मेरा / मेरे / अकाउंट / रील / वीडियो / चैनल" हो */
    private val CHECK = listOf("चेक", "check", "जाँच", "जांच", "देखकर बता", "देख कर बता", "देखो और बता", "چیک", "جانچ", "دیکھ کر بتا", "دیکھ کے بتا")
    private val MINE = listOf("मेरा", "मेरे", "मेरी", "my ", "अकाउंट", "account", "रील", "reel", "वीडियो", "video", "चैनल", "channel", "पेज", "page", "प्रोफाइल", "profile", "पोस्ट", "post", "शॉर्ट", "short", "میرا", "میری", "میرے", "اکاؤنٹ", "ریل", "ویڈیو", "چینل", "پیج", "پوسٹ")
    /** ये बनाने/भेजने के काम हैं - रिव्यू नहीं (SocialAgent / ऐप वाला रास्ता) */
    private val NOT = listOf(
        "पोस्ट करो", "पोस्ट कर दो", "post kar", "post this", "अपलोड करो", "अपलोड कर दो", "upload kar", "upload this", "शेयर करो", "शेयर कर दो",
        "स्टोरी लगा", "story laga", "मैसेज भेज", "message bhej", "send message", "डीएम भेज", "dm bhej", "कॉल कर", "video call", "वीडियो कॉल",
        "चलाओ", "play ", "गाना", "song", "सर्च करो", "search kar", "लाइव शुरू", "go live",
    )

    private fun has(t: String, w: List<String>) = w.any { t.contains(Num.nk(it)) }

    fun platformsIn(text: String): List<Platform> {
        val t = Num.nk(" " + text.lowercase() + " ")
        if (has(t, ALL)) return Platform.values().toList()
        val out = mutableListOf<Pair<Int, Platform>>()
        fun idx(w: List<String>) = w.map { t.indexOf(Num.nk(it)) }.filter { it >= 0 }.minOrNull()
        idx(IG)?.let { out += it to Platform.INSTAGRAM }
        idx(FB)?.let { out += it to Platform.FACEBOOK }
        // "studio" अकेला YouTube तभी, जब कोई और प्लेटफ़ॉर्म न हो
        val yt = YT.filter { it != "studio" && it != "स्टूडियो" }
        (idx(yt) ?: if (out.isEmpty()) idx(listOf("studio", "स्टूडियो")) else null)?.let { out += it to Platform.YOUTUBE }
        return out.sortedBy { it.first }.map { it.second }
    }

    /**
     * नवंबर 2026 (मालिक: "एक बार बोल दिया रिव्यू रोक दो, तो रोक देगा"): रोकने की बात.
     * अकेला "रुको / बस करो / रोक दो / stop" या "रिव्यू / काम + रोक दो / बंद करो / cancel". "मत रोको" = नहीं.
     * "रिकमेंडेशन बंद हो गया - रिव्यू करो" = रोकना नहीं (बंद "हो गया" है, "करो" नहीं).
     */
    fun isStop(text: String?): Boolean {
        val raw = text?.trim()?.trimEnd('…', '।', '.', '!', '?')?.trim().orEmpty()
        if (raw.isBlank() || raw.length > 70) return false
        val t = Num.nk(" " + raw.lowercase() + " ")
        if (has(t, NO_STOP)) return false
        if (ALONE.matches(raw.lowercase())) return true
        return has(t, STOP_VERB) && has(t, WORK)
    }
    private val ALONE = Regex("^(अरे\\s+|ठीक है\\s*,?\\s*|ok\\s*,?\\s*|okay\\s*,?\\s*|ٹھیک ہے\\s*,?\\s*)?(रुको|रुक\\s*जाओ|रुकिए|बस|बस\\s*करो|बंद\\s*करो|बंद\\s*कर\\s*दो|स्टॉप|stop|cancel|कैंसल|रहने\\s*दो|छोड़ो|छोड़\\s*दो|रोको|रोक\\s*दो|रोक\\s*दीजिए|wait|روکو|روک\\s*دو|رک\\s*جاؤ|رکو|بس\\s*کرو|بند\\s*کرو|بند\\s*کر\\s*دو)(\\s+(करो|जाओ|यार|प्लीज|please|सब|अभी|दो|ना|रे|डीके|dk|karo|now))*$")
    private val STOP_VERB = listOf(
        "रोक दो", "रोको", "रोक दीजिए", "रोक दें", "रोकिए", "बंद करो", "बंद कर दो", "बंद कीजिए", "बंद करें", "बंद कर दीजिए",
        "stop", "cancel", "कैंसल", "बस करो", "छोड़ दो", "रहने दो",
        // "रुको, एक काम करो..." = दूसरा काम है, रोकना नहीं - इसलिए "रुको" यहाँ नहीं (अकेला "रुको" ऊपर ALONE में)
        "روک دو", "روکو", "بند کرو", "بند کر دو", "بس کرو", "چھوڑ دو",
    )
    private val NO_STOP = listOf("मत रोक", "मत रुक", "रोकना मत", "बंद मत", "मत बंद", "don't stop", "dont stop", "do not stop", "نہ روک", "مت روک", "بند نہ", "بند مت")
    private val WORK = listOf("रिव्यू", "review", "रीव्यू", "ریویو", "काम", "kaam", "کام", "जाँच", "जांच", "चेक", "check", "چیک", "एनालिसिस", "analysis", "insight", "इनसाइट")

    fun parse(text: String?): Request? {
        val raw = text?.trim().orEmpty()
        if (raw.length < 6) return null
        val t = Num.nk(" " + raw.lowercase() + " ")
        val ps = platformsIn(raw)
        if (ps.isEmpty()) return null
        if (has(t, NOT)) return null
        // "इंस्टाग्राम रिव्यू रोक दो" = रोकना है, नया रिव्यू नहीं
        if (isStop(raw)) return null
        val review = has(t, REVIEW) || (has(t, CHECK) && has(t, MINE))
        if (!review) return null
        return Request(ps, raw)
    }
}

/** "1.2K" / "12,345" / "3.4 लाख" / "2 करोड़" / "१२३" -> Long */
object Num {
    private val DIGITS = "०१२३४५६७८९"
    fun ascii(s: String): String = buildString { s.forEach { ch -> val i = DIGITS.indexOf(ch); append(if (i >= 0) ('0' + i) else ch) } }

    /** "ज़" दो तरह लिखा जाता है (एक अक्षर / ज + ़) - दोनों एक जैसे */
    fun nk(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

    private val RX = Regex(nk(
        "(-?\\d+(?:[.,]\\d+)*)\\s*(हज़ार|हजार|thousand|लाख|lakh|lac|करोड़|करोड|crore|million|billion|mn|bn|cr|[kKmMbBL](?![a-zA-Z]))?",
    ))

    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = nk(ascii(s)).replace('\u00a0', ' ').trim()
        val m = RX.find(t) ?: return null
        val n = number(m.groupValues[1]) ?: return null
        val u = m.groupValues[2].lowercase()
        val mul = when {
            u in K -> 1_000.0
            u in L -> 100_000.0
            u == "m" || u == "million" || u == "mn" -> 1_000_000.0
            u in CR -> 10_000_000.0
            u == "b" || u == "billion" || u == "bn" -> 1_000_000_000.0
            else -> 1.0
        }
        return (n * mul).roundToLong()
    }

    private val K = setOf("k", "thousand", nk("हज़ार"), nk("हजार"))
    private val L = setOf("l", "lakh", "lac", nk("लाख"))
    private val CR = setOf("crore", "cr", nk("करोड़"), nk("करोड"))

    /** "1,234,567" = हज़ार वाले कॉमा; "1,5" = दशमलव (कुछ भाषाओं में) */
    internal fun number(g: String): Double? {
        val parts = g.split(',')
        val joined = if (parts.size > 1 && parts.drop(1).all { it.length == 3 || (it.contains('.') && it.substringBefore('.').length == 3) }) g.replace(",", "")
        else if (parts.size == 2) g.replace(',', '.') else g.replace(",", "")
        // 1.234.567 (यूरोपीय) = हज़ार वाले बिंदु
        val dots = joined.split('.')
        val clean = if (dots.size > 2 && dots.drop(1).all { it.length == 3 }) joined.replace(".", "") else joined
        return clean.toDoubleOrNull()
    }

    /** इंसान के लिए: 1234 -> "1.2K", 150000 -> "1.5 लाख" नहीं - सब जगह एक जैसा K/M */
    fun short(n: Long?): String {
        if (n == null) return "-"
        val a = abs(n.toDouble())
        val sign = if (n < 0) "-" else ""
        return sign + when {
            a >= 1_000_000_000 -> trim(a / 1_000_000_000) + "B"
            a >= 1_000_000 -> trim(a / 1_000_000) + "M"
            a >= 10_000 -> trim(a / 1_000) + "K"
            a >= 1_000 -> trim(a / 1_000) + "K"
            else -> a.roundToLong().toString()
        }
    }

    private fun trim(d: Double): String {
        val r = (d * 10).roundToLong() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}

/** "4.5%" / "4,5 %" -> 4.5 */
object Pct {
    private val RX = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*%")
    fun parse(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val t = Num.ascii(s)
        RX.find(t)?.let { return it.groupValues[1].replace(',', '.').toDoubleOrNull() }
        // सिर्फ़ नंबर (AI ने % हटा दिया)
        return t.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -100.0..100.0 }
    }
}

/** "0:07" / "1:02:03" / "7s" / "7.5 सेकंड" / "2m 10s" / "120 घंटे" / "3.4K hours" -> सेकंड */
object Dur {
    private val CLOCK = Regex("(\\d+):(\\d{1,2})(?::(\\d{1,2}))?")
    private val PART = Regex("(\\d+(?:[.,]\\d+)?)\\s*([kKmM](?![a-zA-Z]))?\\s*(hours?|hrs?|h(?![a-zA-Z])|घंटे|घंटा|minutes?|mins?|m(?![a-zA-Z])|मिनट|seconds?|secs?|s(?![a-zA-Z])|सेकंड|सेकेंड)")

    fun parse(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val t = Num.ascii(s).lowercase().trim()
        CLOCK.find(t)?.let { m ->
            val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble(); val c = m.groupValues[3]
            return if (c.isNotEmpty()) a * 3600 + b * 60 + c.toDouble() else a * 60 + b
        }
        var total = 0.0
        var any = false
        for (m in PART.findAll(t)) {
            var n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            when (m.groupValues[2].lowercase()) { "k" -> n *= 1_000; "m" -> n *= 1_000_000 }
            val u = m.groupValues[3]
            total += when {
                u.startsWith("h") || u.startsWith("घंट") -> n * 3600
                u.startsWith("m") && !u.startsWith("ms") || u == "मिनट" -> n * 60
                else -> n
            }
            any = true
        }
        if (any) return total
        return t.toDoubleOrNull()   // AI ने सीधे सेकंड लिखे
    }

    fun human(sec: Double?): String {
        if (sec == null) return "-"
        val s = sec.roundToLong()
        return when {
            s >= 3600 -> "${s / 3600} घंटे ${(s % 3600) / 60} मिनट"
            s >= 60 -> "${s / 60} मिनट ${s % 60} सेकंड"
            else -> "$s सेकंड"
        }
    }
}

/** "2d" / "3 दिन पहले" / "1w" / "5 hours ago" / "आज" / "कल" -> कितने दिन पहले (न समझे = null) */
object Ago {
    private val RX = Regex("(\\d+)\\s*(d|day|days|दिन|w|wk|week|weeks|हफ़्ते|हफ्ते|सप्ताह|h|hr|hrs|hour|hours|घंटे|m|min|mins|minutes|मिनट|mo|month|months|महीने|महीना|y|yr|year|years|साल)(?![a-zA-Z])")
    fun days(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val t = Num.ascii(s).lowercase()
        if (t.contains("आज") || t.contains("today") || t.contains("just now") || t.contains("अभी")) return 0.0
        if (t.contains("yesterday") || Regex("(^|\\s)कल(\\s|$)").containsMatchIn(t)) return 1.0
        val m = RX.find(t) ?: return null
        val n = m.groupValues[1].toDouble()
        val u = m.groupValues[2]
        return when {
            u.startsWith("mo") || u.startsWith("month") || u.startsWith("मही") -> n * 30
            u == "m" || u.startsWith("min") || u == "मिनट" -> n / 1440
            u.startsWith("h") || u == "घंटे" -> n / 24
            u.startsWith("w") || u.startsWith("हफ") || u == "सप्ताह" -> n * 7
            u.startsWith("y") || u == "साल" -> n * 365
            else -> n
        }
    }
}

/** एक रील / वीडियो / पोस्ट (जो आँकड़ा नहीं मिला = null) */
data class Post(
    val platform: Platform,
    val title: String,
    val date: String = "",
    val type: String = "",
    val views: Long? = null,
    val reach: Long? = null,
    val likes: Long? = null,
    val comments: Long? = null,
    val shares: Long? = null,
    val saves: Long? = null,
    val follows: Long? = null,
    val impressions: Long? = null,
    val ctrPct: Double? = null,
    val retentionPct: Double? = null,
    val avgWatchSec: Double? = null,
    val watchTimeSec: Double? = null,
    val skipRatePct: Double? = null,
    val durationSec: Double? = null,
    val traffic: String = "",
    val notes: String = "",
    val source: String = "screen",
    /** नवंबर 2026: रील खोलकर पढ़े कमेंट - "कौन: क्या लिखा" (जो स्क्रीन पर दिखा, वही) */
    val commentList: List<String> = emptyList(),
) {
    /** एक ही रील दो बार दिखे (सूची + अंदर) - एक ही गिनो */
    val key: String get() = norm(title).take(36).ifBlank { "#" + norm(date) + "|" + (views ?: -1) }

    val interactions: Long? get() = listOfNotNull(likes, comments, shares, saves).takeIf { it.isNotEmpty() }?.sum()

    /** जो खाली है, वह दूसरे से भरो */
    fun merge(o: Post): Post = copy(
        title = if (title.length >= o.title.length) title else o.title,
        date = date.ifBlank { o.date }, type = type.ifBlank { o.type },
        views = maxOf(views, o.views), reach = maxOf(reach, o.reach), likes = maxOf(likes, o.likes), comments = maxOf(comments, o.comments),
        shares = maxOf(shares, o.shares), saves = maxOf(saves, o.saves), follows = maxOf(follows, o.follows),
        impressions = maxOf(impressions, o.impressions), ctrPct = ctrPct ?: o.ctrPct, retentionPct = retentionPct ?: o.retentionPct,
        avgWatchSec = avgWatchSec ?: o.avgWatchSec, watchTimeSec = watchTimeSec ?: o.watchTimeSec, skipRatePct = skipRatePct ?: o.skipRatePct,
        durationSec = durationSec ?: o.durationSec, traffic = traffic.ifBlank { o.traffic },
        notes = listOf(notes, o.notes).filter { it.isNotBlank() }.distinct().joinToString("; "),
        source = if (source == o.source) source else "screen+api",
        commentList = (commentList + o.commentList).distinct().take(15),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title); if (date.isNotBlank()) put("date", date); if (type.isNotBlank()) put("type", type)
        views?.let { put("views", it) }; reach?.let { put("reach", it) }; likes?.let { put("likes", it) }; comments?.let { put("comments", it) }
        shares?.let { put("shares", it) }; saves?.let { put("saves", it) }; follows?.let { put("follows", it) }
        impressions?.let { put("impressions", it) }; ctrPct?.let { put("ctr_pct", it) }; retentionPct?.let { put("retention_pct", it) }
        avgWatchSec?.let { put("avg_watch_sec", it) }; watchTimeSec?.let { put("watch_time_sec", it) }; skipRatePct?.let { put("skip_rate_pct", it) }
        durationSec?.let { put("duration_sec", it) }
        if (traffic.isNotBlank()) put("traffic", traffic); if (notes.isNotBlank()) put("notes", notes); put("source", source)
        if (commentList.isNotEmpty()) put("comment_list", org.json.JSONArray(commentList))
    }

    companion object {
        private fun <T : Comparable<T>> maxOf(a: T?, b: T?): T? = when { a == null -> b; b == null -> a; else -> if (a >= b) a else b }
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** ["राहुल: बढ़िया"] या [{"user":"राहुल","text":"बढ़िया"}] -> "राहुल: बढ़िया" */
        fun commentsOf(a: org.json.JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i)
                val t = if (o != null) listOf(o.optString("user", o.optString("by")).trim(), o.optString("text", o.optString("comment")).trim())
                    .filter { it.isNotBlank() }.joinToString(": ") else a.optString(i).trim()
                t.replace(Regex("\\s+"), " ").take(160).takeIf { it.isNotBlank() && it != "null" }
            }.distinct().take(15)
        }

        /** AI / स्क्रीन से आया JSON (सब मान string भी हो सकते हैं) -> Post */
        fun fromJson(p: Platform, o: JSONObject, source: String = "screen"): Post? {
            fun s(vararg k: String) = k.firstNotNullOfOrNull { o.opt(it)?.toString()?.trim()?.takeIf { v -> v.isNotEmpty() && v != "null" && v != "-" } }
            fun n(vararg k: String) = s(*k)?.let { Num.parse(it) }
            val title = s("title", "caption", "name").orEmpty().replace(Regex("\\s+"), " ").take(120)
            val post = Post(
                platform = p, title = title, date = s("date", "posted", "time").orEmpty().take(40), type = s("type").orEmpty().lowercase().take(20),
                views = n("views", "plays", "view_count"), reach = n("reach", "accounts_reached", "viewers"), likes = n("likes", "reactions"),
                comments = n("comments"), shares = n("shares"), saves = n("saves", "saved"), follows = n("follows", "new_followers", "subscribers_gained", "subs"),
                impressions = n("impressions"), ctrPct = s("ctr", "ctr_pct")?.let { Pct.parse(it) },
                retentionPct = s("retention", "retention_pct", "avg_view_pct", "average_percentage_viewed")?.let { Pct.parse(it) },
                avgWatchSec = s("avg_watch", "avg_watch_sec", "average_watch_time", "avg_view_duration")?.let { Dur.parse(it) },
                watchTimeSec = s("watch_time", "watch_time_sec", "total_watch_time")?.let { Dur.parse(it) },
                skipRatePct = s("skip_rate", "skip_rate_pct")?.let { Pct.parse(it) },
                durationSec = s("duration", "duration_sec", "length")?.let { Dur.parse(it) },
                traffic = s("traffic", "traffic_sources", "sources").orEmpty().take(200), notes = s("notes", "note").orEmpty().take(200), source = source,
                commentList = commentsOf(o.optJSONArray("comment_list") ?: o.optJSONArray("top_comments")),
            )
            // पूरी तरह खाली = कुछ नहीं
            if (post.title.isBlank() && post.views == null && post.reach == null && post.likes == null) return null
            return post
        }
    }
}

/** पूरे खाते का हाल (प्रोफ़ेशनल डैशबोर्ड / YouTube Studio का पहला पन्ना) */
data class Account(
    val period: String = "",
    val views: Long? = null,
    val reach: Long? = null,
    val interactions: Long? = null,
    val followers: Long? = null,
    val followersChange: Long? = null,
    val watchTimeSec: Double? = null,
    val viewsChangePct: Double? = null,
    val reachChangePct: Double? = null,
    val status: String = "",
    val notes: String = "",
    // नवंबर 2026: प्रोफ़ाइल की पूरी पहचान + कमाई/रिकमेंडेशन की हालत - सिर्फ़ जो स्क्रीन पर लिखा दिखा
    val name: String = "",
    val handle: String = "",
    val following: Long? = null,
    val postsCount: Long? = null,
    val lastPost: String = "",
    val monetization: String = "",
    val recommendation: String = "",
) {
    val empty get() = views == null && reach == null && interactions == null && followers == null && watchTimeSec == null && status.isBlank() && notes.isBlank() &&
        name.isBlank() && handle.isBlank() && following == null && monetization.isBlank() && recommendation.isBlank()

    fun merge(o: Account): Account = Account(
        period = period.ifBlank { o.period }, views = views ?: o.views, reach = reach ?: o.reach, interactions = interactions ?: o.interactions,
        followers = followers ?: o.followers, followersChange = followersChange ?: o.followersChange, watchTimeSec = watchTimeSec ?: o.watchTimeSec,
        viewsChangePct = viewsChangePct ?: o.viewsChangePct, reachChangePct = reachChangePct ?: o.reachChangePct,
        status = listOf(status, o.status).filter { it.isNotBlank() }.distinct().joinToString("; ").take(400),
        notes = listOf(notes, o.notes).filter { it.isNotBlank() }.distinct().joinToString("; ").take(600),
        name = name.ifBlank { o.name }, handle = handle.ifBlank { o.handle }, following = following ?: o.following,
        postsCount = postsCount ?: o.postsCount, lastPost = lastPost.ifBlank { o.lastPost },
        monetization = listOf(monetization, o.monetization).filter { it.isNotBlank() }.distinct().joinToString("; ").take(500),
        recommendation = listOf(recommendation, o.recommendation).filter { it.isNotBlank() }.distinct().joinToString("; ").take(500),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        if (period.isNotBlank()) put("period", period); views?.let { put("views", it) }; reach?.let { put("reach", it) }
        interactions?.let { put("interactions", it) }; followers?.let { put("followers", it) }; followersChange?.let { put("followers_change", it) }
        watchTimeSec?.let { put("watch_time_sec", it) }; viewsChangePct?.let { put("views_change_pct", it) }; reachChangePct?.let { put("reach_change_pct", it) }
        if (status.isNotBlank()) put("status", status); if (notes.isNotBlank()) put("notes", notes)
        if (name.isNotBlank()) put("name", name); if (handle.isNotBlank()) put("handle", handle); following?.let { put("following", it) }
        postsCount?.let { put("posts_count", it) }; if (lastPost.isNotBlank()) put("last_post", lastPost)
        if (monetization.isNotBlank()) put("monetization", monetization); if (recommendation.isNotBlank()) put("recommendation", recommendation)
    }

    companion object {
        fun fromJson(o: JSONObject?): Account {
            if (o == null) return Account()
            fun s(vararg k: String) = k.firstNotNullOfOrNull { o.opt(it)?.toString()?.trim()?.takeIf { v -> v.isNotEmpty() && v != "null" && v != "-" } }
            fun change(v: String?): Long? = v?.let { Num.parse(it) }
            return Account(
                period = s("period").orEmpty().take(40), views = s("views")?.let { Num.parse(it) }, reach = s("reach", "accounts_reached")?.let { Num.parse(it) },
                interactions = s("interactions", "engagement")?.let { Num.parse(it) }, followers = s("followers", "subscribers")?.let { Num.parse(it) },
                followersChange = change(s("followers_change", "net_follows", "subscribers_change", "subscribers_gained")),
                watchTimeSec = s("watch_time", "watch_time_sec")?.let { Dur.parse(it) },
                viewsChangePct = s("views_change", "views_change_pct")?.let { Pct.parse(it) },
                reachChangePct = s("reach_change", "reach_change_pct")?.let { Pct.parse(it) },
                status = s("status", "account_status").orEmpty().take(300), notes = s("notes").orEmpty().take(400),
                name = s("name", "profile_name", "channel_name").orEmpty().take(80), handle = s("handle", "username").orEmpty().take(60),
                following = s("following")?.let { Num.parse(it) }, postsCount = s("posts_count", "posts", "videos_count")?.let { Num.parse(it) },
                lastPost = s("last_post", "latest_post").orEmpty().take(60),
                monetization = s("monetization", "monetization_status").orEmpty().take(400),
                recommendation = s("recommendation", "recommendations").orEmpty().take(400),
            )
        }
    }
}

/** एक प्लेटफ़ॉर्म से जो मिला */
data class PlatformData(
    val platform: Platform,
    val posts: List<Post> = emptyList(),
    val account: Account = Account(),
    val statusChecked: Boolean = false,
    /** स्क्रीन / API - कहाँ से आया, क्या रुका */
    val sources: List<String> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    val empty get() = posts.isEmpty() && account.empty

    fun merge(o: PlatformData): PlatformData {
        val m = LinkedHashMap<String, Post>()
        (posts + o.posts).forEach { p ->
            // API का पूरा caption बनाम स्क्रीन का कटा हुआ = वही रील (शुरू का हिस्सा मिलता है)
            val k = m.keys.firstOrNull { it == p.key } ?: m.keys.firstOrNull { minOf(it.length, p.key.length) >= 12 && (it.startsWith(p.key) || p.key.startsWith(it)) } ?: p.key
            m[k] = m[k]?.merge(p) ?: p
        }
        return PlatformData(platform, m.values.toList(), account.merge(o.account), statusChecked || o.statusChecked,
            (sources + o.sources).distinct(), (problems + o.problems).distinct())
    }
}

/** आँकड़ों से पक्की बातें */
data class Analysis(
    val platform: Platform,
    val count: Int,
    val medianViews: Long?,
    val best: Post?,
    val worst: Post?,
    val recentAvg: Long?,
    val olderAvg: Long?,
    val changePct: Int?,
    val trend: Trend,
    val findings: List<String>,
    val tips: List<String>,
) {
    enum class Trend(val hi: String) { UP("बढ़ रही है"), DOWN("गिर रही है"), FLAT("लगभग एक जैसी है"), UNKNOWN("अभी पक्का नहीं (आँकड़े कम)") }
}

object Insights {
    /** हाल की कितनी रील "नई" गिनें (मालिक: "रिसेंटली में तीन-चार रील") */
    const val RECENT = 4

    fun median(xs: List<Long>): Long? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** posts = नई से पुरानी (जिस क्रम में स्क्रीन पर/ API में आए); तारीख़ पता हो तो उसी से क्रम */
    fun ordered(posts: List<Post>): List<Post> {
        val withAge = posts.map { it to Ago.days(it.date) }
        return if (withAge.count { it.second != null } >= posts.size * 2 / 3 && posts.size >= 2)
            withAge.sortedBy { it.second ?: Double.MAX_VALUE }.map { it.first } else posts
    }

    /** views न मिले तो reach / impressions (Facebook में अक्सर "reach") */
    fun score(p: Post): Long? = p.views ?: p.reach ?: p.impressions

    fun analyze(d: PlatformData): Analysis {
        val p = d.platform
        val posts = ordered(d.posts)
        val scored = posts.filter { score(it) != null }
        val med = median(scored.map { score(it)!! })
        val best = scored.maxByOrNull { score(it)!! }
        val worst = scored.filter { it !== best }.minByOrNull { score(it)!! }
        val recent = scored.take(RECENT)
        val older = scored.drop(RECENT)
        val rAvg = recent.takeIf { it.isNotEmpty() }?.map { score(it)!! }?.average()?.roundToLong()
        val oAvg = older.takeIf { it.size >= 2 }?.map { score(it)!! }?.average()?.roundToLong()
        val change = if (rAvg != null && oAvg != null && oAvg > 0) (((rAvg - oAvg).toDouble() / oAvg) * 100).roundToInt() else null
        val trend = when {
            change == null -> d.account.viewsChangePct?.let { if (it <= -20) Analysis.Trend.DOWN else if (it >= 20) Analysis.Trend.UP else Analysis.Trend.FLAT } ?: Analysis.Trend.UNKNOWN
            change <= -25 -> Analysis.Trend.DOWN
            change >= 25 -> Analysis.Trend.UP
            else -> Analysis.Trend.FLAT
        }
        val f = mutableListOf<String>()
        val tips = LinkedHashSet<String>()
        val item = p.itemHi
        val metric = if (scored.any { it.views != null }) "व्यूज़" else "रीच"

        if (change != null) when (trend) {
            Analysis.Trend.DOWN -> {
                f += "हाल की ${recent.size} $item का औसत $metric ${Num.short(rAvg)} है, जबकि उससे पहले की ${older.size} का ${Num.short(oAvg)} था - यानी ${abs(change)}% कम।"
                tips += "जो $item पहले सबसे ज़्यादा चलीं, उनका विषय, शुरुआत (hook) और लंबाई दोबारा अपनाएँ - नई $item उनसे अलग दिशा में गई हैं।"
            }
            Analysis.Trend.UP -> f += "अच्छी ख़बर: हाल की ${recent.size} $item का औसत $metric ${Num.short(rAvg)} है, पहले से ${change}% ज़्यादा।"
            else -> f += "हाल की $item का औसत $metric (${Num.short(rAvg)}) पहले जैसा ही है (${if (change >= 0) "+" else ""}$change%)।"
        }
        d.account.viewsChangePct?.let { c ->
            if (abs(c) >= 10) f += "डैशबोर्ड के हिसाब से ${d.account.period.ifBlank { "इस अवधि" }} में कुल व्यूज़ ${if (c < 0) "${abs(c).fmt()}% घटे" else "${c.fmt()}% बढ़े"}।"
        }
        d.account.reachChangePct?.let { c ->
            if (abs(c) >= 10) f += "डैशबोर्ड के हिसाब से रीच ${if (c < 0) "${abs(c).fmt()}% घटी" else "${c.fmt()}% बढ़ी"}।"
        }
        if (best != null && med != null && med > 0 && scored.size >= 3) {
            val x = score(best)!!.toDouble() / med
            if (x >= 1.8) f += "सबसे अच्छी: \"${best.title.short()}\" - ${Num.short(score(best))} $metric, आपके आम $item (${Num.short(med)}) से ${x.fmt()} गुना।"
            else f += "सबसे अच्छी: \"${best.title.short()}\" - ${Num.short(score(best))} $metric।"
        }
        if (worst != null && med != null && scored.size >= 3) f += "सबसे कमज़ोर: \"${worst.title.short()}\" - ${Num.short(score(worst))} $metric।"

        // शुरुआत / पूरा देखना
        val shortForm = p != Platform.YOUTUBE || posts.any { it.type.contains("short") }
        val weakHook = posts.filter { it.avgWatchSec != null && it.avgWatchSec < (if (shortForm) 4.0 else 30.0) }
        if (weakHook.isNotEmpty()) {
            f += "${weakHook.size} $item में लोग औसतन ${weakHook.mapNotNull { it.avgWatchSec }.average().fmt()} सेकंड ही देख रहे हैं - शुरुआत लोगों को रोक नहीं पा रही।"
            tips += "पहले 1-2 सेकंड में ही सबसे दिलचस्प पल / सवाल / नतीजा दिखाएँ; लंबा intro, logo या \"हेलो दोस्तों\" हटाएँ।"
        }
        val lowRet = posts.filter { it.retentionPct != null && it.retentionPct < (if (shortForm) 50.0 else 30.0) }
        if (lowRet.isNotEmpty()) {
            f += "${lowRet.size} $item में लोग वीडियो का औसतन ${lowRet.mapNotNull { it.retentionPct }.average().fmt()}% ही देख रहे हैं (कम है)।"
            tips += "वीडियो छोटा और कसा हुआ करें - बीच का धीमा हिस्सा काटें, हर 3-5 सेकंड में कुछ नया (कट / text / आवाज़) रखें।"
        }
        val highSkip = posts.filter { it.skipRatePct != null && it.skipRatePct > 50 }
        if (highSkip.isNotEmpty()) {
            f += "${highSkip.size} $item का skip rate 50% से ज़्यादा है - ज़्यादातर लोग पहले 3 सेकंड में आगे बढ़ गए।"
            tips += "पहला फ्रेम और पहली लाइन बदलें - वहीं सबसे ज़्यादा लोग छोड़ रहे हैं।"
        }
        // YouTube: thumbnail / title
        val lowCtr = posts.filter { it.ctrPct != null && it.ctrPct < 3.0 }
        if (lowCtr.isNotEmpty()) {
            f += "${lowCtr.size} वीडियो का CTR 3% से कम है - thumbnail / title लोगों को क्लिक करने के लिए नहीं खींच रहा।"
            tips += "Thumbnail पर बड़ा चेहरा / एक साफ़ चीज़ और 3-4 बड़े शब्द रखें; title में जिज्ञासा या फ़ायदा साफ़ लिखें।"
        }
        posts.filter { it.ctrPct != null && it.ctrPct >= 8.0 }.takeIf { it.isNotEmpty() }?.let { f += "${it.size} वीडियो का CTR 8% या उससे ज़्यादा है - उनका thumbnail/title तरीका अच्छा चल रहा है।" }

        // शेयर / सेव (Instagram/Facebook में बाँटना ही सबसे ज़्यादा फैलाता है)
        val withShares = posts.filter { it.shares != null && score(it) != null && score(it)!! >= 200 }
        if (withShares.size >= 2 && p != Platform.YOUTUBE) {
            val rate = withShares.sumOf { it.shares!! }.toDouble() / withShares.sumOf { score(it)!! } * 100
            if (rate < 0.5) {
                f += "शेयर बहुत कम हैं (हर 1000 $metric पर ${(rate * 10).fmt()}) - लोग देख रहे हैं पर आगे नहीं भेज रहे, इसलिए नए लोगों तक नहीं पहुँच रही।"
                tips += "ऐसी चीज़ बनाएँ जिसे लोग दोस्तों को भेजें: काम की टिप, relatable पल, \"इसे उसे भेजो जो...\" वाली लाइन।"
            }
        }
        // बातचीत / engagement
        val eng = posts.filter { it.interactions != null && score(it) != null && score(it)!! >= 200 }
        if (eng.size >= 3) {
            val r = eng.sumOf { it.interactions!! }.toDouble() / eng.sumOf { score(it)!! } * 100
            if (r < 2.0) {
                f += "लाइक/कमेंट/शेयर/सेव मिलाकर सिर्फ़ ${r.fmt()}% हैं - दर्शक जुड़ नहीं रहे।"
                tips += "आख़िर में एक सीधा सवाल पूछें / कमेंट में जवाब दें - पहले घंटे में बातचीत से पहुँच बढ़ती है।"
            }
        }
        // नए फ़ॉलोअर
        d.account.followersChange?.let { if (it < 0) f += "इस अवधि में फ़ॉलोअर/सब्सक्राइबर ${abs(it)} घटे हैं।" }
        // कितनी बार डालते हैं
        val ages = posts.mapNotNull { Ago.days(it.date) }.sorted()
        if (ages.size >= 3) {
            val gaps = ages.zipWithNext { a, b -> b - a }
            val maxGap = gaps.maxOrNull() ?: 0.0
            if (ages.first() >= 7) {
                f += "आख़िरी $item ${ages.first().roundToInt()} दिन पहले डाली थी - लगातार न डालने से पहुँच घटती है।"
                tips += "हफ़्ते में कम से कम 3-4 बार, एक तय समय पर डालें।"
            } else if (maxGap >= 7) {
                f += "बीच में ${maxGap.roundToInt()} दिन का gap आया - उसके बाद पहुँच अक्सर धीमी पड़ती है।"
                tips += "हफ़्ते में कम से कम 3-4 बार, एक तय समय पर डालें।"
            }
        }
        // खाते की चेतावनी (रीच गिरने की बड़ी वजह)
        val warn = Regex("(not eligible|can'?t be recommended|cannot be recommended|isn'?t eligible|restricted|violation|violat|strike|copyright|removed|limited|अनुशंसा|सुझाव में नहीं|उल्लंघन|हटाया|सीमित|प्रतिबंध)", RegexOption.IGNORE_CASE)
        val statusText = (d.account.status + " " + posts.joinToString(" ") { it.notes }).trim()
        if (warn.containsMatchIn(statusText)) {
            f += "चेतावनी मिली: \"${d.account.status.ifBlank { posts.first { warn.containsMatchIn(it.notes) }.notes }.short(140)}\" - इससे रील सुझावों (recommendation) से हट सकती हैं और रीच अचानक गिरती है।"
            tips += "Account status में जो चेतावनी है उसे पहले ठीक करें (अपील करें / वह पोस्ट हटाएँ); दूसरों का वीडियो / गाना बिना बदलाव के न डालें।"
        } else if (d.statusChecked) f += "Account status देखा - कोई चेतावनी नहीं दिखी।"
        // Traffic
        posts.firstOrNull { it.traffic.isNotBlank() }?.let { f += "व्यूज़ कहाँ से आ रहे हैं (\"${it.title.short(30)}\"): ${it.traffic.short(120)}" }

        if (tips.isEmpty() && scored.isNotEmpty()) tips += "जो $item सबसे अच्छी चली, उसी तरह के 2-3 नए $item बनाएँ और पहले 2 सेकंड पर सबसे ज़्यादा ध्यान दें।"
        return Analysis(p, posts.size, med, best, worst, rAvg, oAvg, change, trend, f, tips.toList())
    }

    private fun Double.fmt(): String { val r = (this * 10).roundToLong() / 10.0; return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString() }
    private fun String.short(n: Int = 50) = replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }.ifBlank { "(बिना नाम)" }
}

/** रिपोर्ट: AI के लिए prompt + बिना AI वाली पूरी रिपोर्ट + बोलने लायक छोटा हिस्सा */
object InsightsReport {
    const val SPOKEN_MARK = "बोलकर:"

    fun dataJson(all: List<PlatformData>, analyses: List<Analysis>): String {
        val arr = JSONArray()
        all.forEach { d ->
            val a = analyses.firstOrNull { it.platform == d.platform }
            arr.put(JSONObject().put("platform", d.platform.en).put("account", d.account.toJson())
                .put("posts_newest_first", JSONArray().apply { Insights.ordered(d.posts).take(15).forEach { put(it.toJson()) } })
                .put("status_checked", d.statusChecked).put("sources", JSONArray(d.sources)).put("problems", JSONArray(d.problems))
                .put("computed", JSONObject().apply {
                    a?.let { x ->
                        x.medianViews?.let { put("median_views", it) }; x.recentAvg?.let { put("recent_avg", it) }; x.olderAvg?.let { put("older_avg", it) }
                        x.changePct?.let { put("recent_vs_older_pct", it) }; put("trend", x.trend.name); put("facts_hi", JSONArray(x.findings))
                    }
                }))
        }
        return arr.toString()
    }

    fun prompt(all: List<PlatformData>, analyses: List<Analysis>, trends: String, request: String): String = buildString {
        append("You are DK, a social-media growth analyst for an Indian creator. The creator said: \"").append(request.take(300)).append("\".\n")
        append("Below is REAL data DK just collected from their own Instagram / Facebook / YouTube (screen reading of the apps and/or official APIs), ")
        append("plus facts DK already computed. Write the full review in simple Hindi (Devanagari), friendly and direct.\n\n")
        append("STRICT RULES:\n")
        append("- Use ONLY numbers present in the data. Never invent a number. If something is missing say \"यह आँकड़ा नहीं मिला\".\n")
        append("- Reasons must be tied to data (compare posts: views, watch time, retention, shares, saves, CTR, posting gaps, account status warnings). ")
        append("Say clearly when a reason is a likely guess (\"संभावित वजह\"). No one knows the algorithm exactly.\n")
        append("- Be specific: name the reels/videos by their title/caption.\n")
        append("- NEVER LIE: monetization / recommendation status and its reason ONLY from account.monetization / account.recommendation / account.status ")
        append("(copied from the screen). If empty or 'not found' say \"यह स्क्रीन पर नहीं मिला - पक्का पता नहीं\". Never claim demonetization or a ban that is not in the data. ")
        append("Policy and algorithm facts ONLY from the POLICY section below (official help pages) - say that it is the company's public rule, not a secret formula.\n\n")
        // नवंबर 2026 (फटाफट): लंबी रिपोर्ट = लंबा इंतज़ार - छोटी, काम की बातें
        append("Be QUICK and SHORT: the whole report at most ~700 words, short bullet points, no repetition.\n\n")
        append("FORMAT (Markdown):\n")
        append("## सार\n3-5 lines: overall what is happening and the #1 reason.\n")
        append("Then for EACH platform: ## <platform name>\n")
        append("- ### प्रोफ़ाइल  (name, @username, followers, following, posts count, when the last reel/video was posted, its comments count and 3-5 of its comments as 'कौन: क्या लिखा' with what they show - only what is in the data)\n")
        append("- ### मोनेटाइज़ेशन और रिकमेंडेशन  (status exactly as read; if a problem: the reason as read, what that policy means, and step-by-step how to fix / request review; if not read say so)\n")
        append("- a table | रील/वीडियो | तारीख़ | व्यूज़ | रीच | लाइक | कमेंट | शेयर | सेव | औसत देखा | (CTR for YouTube) | for up to 10 items\n")
        append("- ### क्या चल रहा है  (what works, with numbers)\n- ### रीच क्यों गिरी / बढ़ी  (data-backed reasons)\n- ### क्या करें  (3-6 concrete actions)\n")
        append("## पॉलिसी और एल्गोरिदम\nfor each reviewed platform max 4 short bullets: the main monetization rules, what stops recommendations, how the ranking works (from POLICY), with the official links.\n")
        append("## ट्रेंडिंग और नए आइडिया\nUse the trend research below + the creator's own best topics: 5 concrete reel/video ideas with a hook line each.\n")
        append("## अगले 7 दिन का प्लान\nday-wise short plan.\n")
        append("Finally a line that starts exactly with \"").append(SPOKEN_MARK).append("\" followed by a 5-7 sentence spoken summary in Hindi ")
        append("(no markdown, no tables, numbers spoken simply) - this will be read aloud to the creator. ")
        append("It must include the followers count and the monetization / recommendation status exactly as read (or that it could not be read).\n\n")
        append("DATA (JSON):\n").append(dataJson(all, analyses)).append("\n\n")
        append("TREND RESEARCH (from web search, may be empty):\n").append(trends.take(3500).ifBlank { "(nothing found)" }).append("\n")
        append("\nPOLICY (official help pages, Nov 2026):\n")
        all.forEach { append(InsightsPolicy.guide(it.platform)).append("\n\n") }
    }

    /** AI के जवाब से बोलने वाला हिस्सा अलग */
    fun split(ai: String): Pair<String, String> {
        val i = ai.lastIndexOf(SPOKEN_MARK)
        if (i < 0) return ai.trim() to ""
        val spoken = ai.substring(i + SPOKEN_MARK.length).replace(Regex("[*#|`_>]"), " ").replace(Regex("\\s+"), " ").trim()
        return ai.substring(0, i).trim() to spoken
    }

    /** बिना AI (key नहीं / नेट गया) - फिर भी पूरी, सच्ची रिपोर्ट */
    fun fallback(all: List<PlatformData>, analyses: List<Analysis>, trends: String): String = buildString {
        append("# सोशल मीडिया रिव्यू\n\n")
        all.forEach { d ->
            val a = analyses.firstOrNull { it.platform == d.platform } ?: Insights.analyze(d)
            append("## ").append(d.platform.hi).append(" (").append(d.platform.en).append(")\n\n")
            if (d.empty) {
                append("कोई आँकड़ा नहीं मिला।").append(d.problems.joinToString(" ").let { if (it.isBlank()) "" else " वजह: $it" }).append("\n\n")
                return@forEach
            }
            val ac = d.account
            // नवंबर 2026: प्रोफ़ाइल + कमाई + रिकमेंडेशन - जो पढ़ा वही, न पढ़ा = "पता नहीं चला"
            append("### प्रोफ़ाइल, मोनेटाइज़ेशन और रिकमेंडेशन\n")
            InsightsPolicy.profileLines(d).forEach { append("- ").append(it).append("\n") }
            append("\n")
            if (!ac.empty) {
                append("**खाता").append(if (ac.period.isNotBlank()) " (${ac.period})" else "").append(":** ")
                append(listOfNotNull(
                    ac.views?.let { "व्यूज़ ${Num.short(it)}" + (ac.viewsChangePct?.let { c -> " (${if (c >= 0) "+" else ""}${c}%)" } ?: "") },
                    ac.reach?.let { "रीच ${Num.short(it)}" + (ac.reachChangePct?.let { c -> " (${if (c >= 0) "+" else ""}${c}%)" } ?: "") },
                    ac.interactions?.let { "इंटरैक्शन ${Num.short(it)}" }, ac.followers?.let { "फ़ॉलोअर ${Num.short(it)}" },
                    ac.followersChange?.let { "नए फ़ॉलोअर ${if (it >= 0) "+" else ""}$it" }, ac.watchTimeSec?.let { "वॉच टाइम ${Dur.human(it)}" },
                ).joinToString(", ").ifBlank { "-" }).append("\n\n")
            }
            if (d.posts.isNotEmpty()) {
                val yt = d.platform == Platform.YOUTUBE
                append("| ${d.platform.itemHi} | तारीख़ | व्यूज़ | रीच | लाइक | कमेंट | शेयर | सेव | औसत देखा |").append(if (yt) " CTR |" else "").append("\n")
                append("|---|---|---|---|---|---|---|---|---|").append(if (yt) "---|" else "").append("\n")
                Insights.ordered(d.posts).take(12).forEach { p ->
                    append("| ").append(p.title.replace("|", "/").take(40).ifBlank { "-" }).append(" | ").append(p.date.ifBlank { "-" })
                    append(" | ").append(Num.short(p.views)).append(" | ").append(Num.short(p.reach)).append(" | ").append(Num.short(p.likes))
                    append(" | ").append(Num.short(p.comments)).append(" | ").append(Num.short(p.shares)).append(" | ").append(Num.short(p.saves))
                    append(" | ").append(p.avgWatchSec?.let { Dur.human(it) } ?: "-").append(" |")
                    if (yt) append(" ").append(p.ctrPct?.let { "$it%" } ?: "-").append(" |")
                    append("\n")
                }
                append("\n")
            }
            append("**रुझान:** ").append(a.trend.hi).append("\n\n")
            if (a.findings.isNotEmpty()) { append("### क्या दिखा\n"); a.findings.forEach { append("- ").append(it).append("\n") }; append("\n") }
            if (a.tips.isNotEmpty()) { append("### क्या करें\n"); a.tips.forEach { append("- ").append(it).append("\n") }; append("\n") }
            if (d.problems.isNotEmpty()) append("_ध्यान दें:_ ").append(d.problems.joinToString("; ")).append("\n\n")
        }
        if (trends.isNotBlank()) append("## ट्रेंडिंग\n").append(trends.take(2500)).append("\n")
        append("\n## पॉलिसी और एल्गोरिदम (कंपनी के अपने help पेज से)\n")
        all.forEach { append("### ").append(it.platform.hi).append("\n").append(InsightsPolicy.guide(it.platform)).append("\n\n") }
    }

    /** बिना AI बोलने वाला छोटा हिस्सा */
    fun fallbackSpoken(all: List<PlatformData>, analyses: List<Analysis>): String = buildString {
        all.forEach { d ->
            val a = analyses.firstOrNull { it.platform == d.platform } ?: return@forEach
            if (d.empty) { append(d.platform.hi).append(" से आँकड़े नहीं मिल पाए। "); return@forEach }
            append(d.platform.hi).append(": ").append(d.posts.size).append(" ").append(d.platform.itemHi).append(" देखीं, पहुँच ").append(a.trend.hi).append("। ")
            a.findings.take(2).forEach { append(it).append(" ") }
            a.tips.firstOrNull()?.let { append("सबसे पहले: ").append(it).append(" ") }
        }
        append("पूरी रिपोर्ट चैट में और Download/DK Reports फ़ोल्डर में है।")
    }.replace(Regex("[*#|`]"), "").trim()
}
