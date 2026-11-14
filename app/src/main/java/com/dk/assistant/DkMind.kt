package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * 14 नवंबर 2026 (मालिक): "DK का अपना छोटा दिमाग - कोई key न चले तो उसी से काम करे. जिस यूज़र से बात करे उसकी बातचीत
 * सेव रखे, रोज़ सीखता रहे, जितना सीखे उतना उसी के फ़ोन पर. बैकअप में भी जाए, रिस्टोर पर वापस आए. ऐप में दिखे नहीं.
 * पहले अपना दिमाग देखे, न मिले तो API. रियल-टाइम / खोज वाली बात सिर्फ़ API से. सटीक ही जवाब - कोई गलत जवाब नहीं."
 *
 * यह उस दिमाग का नियम वाला हिस्सा है (Android import नहीं = पूरा JVM test). फ़ोन पर रखने का काम [LocalBrain].
 *
 *  - [Entry]       = एक सीखी हुई बात: सवाल (साफ़ किया) -> DK का जवाब, कब सीखा, कितनी बार वही बात आई.
 *  - [teachable]   = यह जोड़ी सीखने लायक है? (बदलने वाली / खोज वाली / गलती वाली / आधी बात नहीं).
 *  - [stable]      = API चलती हो तब भी अपने दिमाग से पहले जवाब दे सकते हैं? (बदलने वाली बात कभी नहीं).
 *  - [match]       = सबसे मिलता सवाल - अंक और "नहीं" अलग हों तो कभी नहीं ("5 का पहाड़ा" ≠ "6 का पहाड़ा").
 *  - [merge]       = फ़ोन + बैकअप जोड़ना: कुछ नहीं मिटता, वही सवाल हो तो नया जवाब.
 */
object DkMind {
    data class Entry(val q: String, val a: String, val t: Long, val n: Int = 1)

    /** फ़ोन पर इतनी बातें (≈ कुछ MB) - इससे ज़्यादा हों तो सबसे पुरानी, कम बार आई पहले हटें */
    const val MAX = 20_000
    /** API चलते हुए अपने दिमाग का जवाब पहले - इतना पुराना हो तो नहीं (API से ताज़ा) */
    const val FRESH_MS = 45L * 86_400_000L
    /** key न चले तब: मिलता-जुलता सवाल कम से कम इतना मिले */
    const val FUZZY_MIN = 0.72
    const val MAX_ANSWER = 900

    /** बदलने वाली बात - इसका जवाब याद से पक्का नहीं (समय, कीमत, खबर, कौन किस पद पर, नया / latest...) */
    private val CHANGING = listOf(
        "आज", "अभी", "कल", "परसों", "इस हफ्ते", "इस साल", "इस महीने", "मौसम", "भाव", "रेट", "दाम", "कीमत", "प्राइस", "price",
        "न्यूज", "न्यूज़", "खबर", "ख़बर", "समाचार", "news", "स्कोर", "score", "मैच", "टाइम", "समय", "बजे", "तारीख", "डेट", "date",
        "लेटेस्ट", "latest", "नया", "नई", "नए", "नये", "करंट", "current", "मौजूदा", "वर्तमान", "प्रधानमंत्री", "मुख्यमंत्री", "राष्ट्रपति",
        "pm", "cm", "ceo", "सीईओ", "चुनाव", "election", "रिजल्ट", "result", "जीता", "जीती", "winner", "शेयर", "stock", "बिटकॉइन", "bitcoin",
        "पेट्रोल", "डीजल", "सोना", "चाँदी", "gold", "ट्रेन", "फ्लाइट", "flight", "रिलीज", "release", "लॉन्च", "launch", "ट्रेंड", "trend",
        "वायरल", "viral", "बैटरी", "battery", "लोकेशन", "location", "कहाँ हूँ",
    )
    private val YEAR = Regex("(^|\\D)(19|20)\\d\\d(\\D|$)")
    /** पिछली बात पर टिकी आधी बात ("इसका", "और बताओ") - अकेले उसका जवाब किसी काम का नहीं */
    private val CONTEXT = listOf("ये", "यह", "वो", "वह", "इसका", "उसका", "इसके", "उसके", "इसे", "उसे", "इनका", "उनका", "this", "that", "it")
    private val CONTEXT_PHRASE = listOf("और बताओ", "फिर से", "पहले वाला", "ऊपर वाला", "दोबारा बोलो", "क्या बोला", "क्या कहा")
    /** फ़ोन का काम (खोलो, भेजो, लगाओ...) - जवाब बातें नहीं, काम है */
    private val ACTION = listOf("खोलो", "खोल दो", "बंद करो", "चालू करो", "भेजो", "भेज दो", "लगाओ", "लगा दो", "कॉल करो", "फोन करो", "मिलाओ",
        "चलाओ", "बजाओ", "सर्च", "खोजो", "ढूंढो", "ढूँढो", "पता करो", "रिसर्च", "डाउनलोड", "open", "call", "send", "play", "search", "set")
    /** यह जवाब गलती / माफ़ी / अधूरा है - सीखने लायक नहीं */
    private val BAD_ANSWER = listOf("माफ़ कीजिए", "माफ कीजिए", "माफ़ करें", "पक्का नहीं", "पता नहीं", "नहीं पता", "नहीं बता", "जवाब नहीं आ",
        "api", "key", "इंटरनेट", "नेट नहीं", "error", "गलती", "limit", "लिमिट", "सेटिंग्स", "फिर से बोलिए", "दोबारा बोलिए", "समझ नहीं",
        "कर दिया", "खोल दिया", "भेज दिया", "लगा दिया", "[", "http")
    private val NEG = listOf("नहीं", "ना", "मत", "न", "not", "no", "don't", "never")

    fun words(q: String): List<String> = q.split(' ').filter { it.isNotBlank() }
    private fun hasAny(q: String, list: List<String>): Boolean {
        val w = words(q).toSet()
        return list.any { if (it.contains(' ')) q.contains(it) else w.contains(it) || (it.length >= 4 && q.contains(it)) }
    }
    private fun nums(q: String): List<String> = Regex("\\d+(?:\\.\\d+)?").findAll(q).map { it.value }.toList()
    private fun negs(q: String): Boolean = words(q).any { it in NEG }

    /** यह सवाल बदलने वाला / खोज वाला है = सिर्फ़ API (अपने दिमाग से कभी नहीं) */
    fun changing(q: String): Boolean = hasAny(q, CHANGING) || YEAR.containsMatchIn(q)

    /** सवाल (साफ़ किया हुआ) सीखने / याद से जवाब देने लायक है? */
    fun askable(q: String): Boolean {
        val w = words(q)
        if (w.size < 2 || q.length < 6) return false
        if (hasAny(q, CONTEXT) || CONTEXT_PHRASE.any { q.contains(it) }) return false
        if (hasAny(q, ACTION)) return false
        return !changing(q)
    }

    /** यह जोड़ी सीखें? (search / tool वाला जवाब बाहर से ही नहीं आता) */
    fun teachable(q: String, a: String): Boolean {
        val ans = a.trim()
        if (!askable(q) || ans.length < 4 || ans.length > MAX_ANSWER) return false
        val low = ans.lowercase()
        return BAD_ANSWER.none { low.contains(it) }
    }

    /** DK लाइव की बारी सीखें? - दोनों तरफ़ बात हो, खोज नहीं, कोई फ़ोन का काम न चल रहा / अभी-अभी न चला हो */
    const val LIVE_TOOL_GAP_MS = 15_000L
    fun liveTeach(user: String, reply: String, searched: Boolean, toolRunning: Boolean, sinceToolMs: Long): Boolean =
        user.isNotBlank() && reply.isNotBlank() && !searched && !toolRunning && sinceToolMs > LIVE_TOOL_GAP_MS

    /** API चलती हो तब भी अपने दिमाग से पहले? - वही सवाल, ताज़ा सीखा, बदलने वाला नहीं */
    fun stable(e: Entry, now: Long): Boolean = askable(e.q) && now - e.t <= FRESH_MS

    /** दो सवाल कितने मिलते हैं (0..1). अंक या "नहीं" अलग = 0 (गलत जवाब से बचाव) */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (nums(a) != nums(b) || negs(a) != negs(b)) return 0.0
        val x = words(a).filter { it.length > 1 }.toSet(); val y = words(b).filter { it.length > 1 }.toSet()
        val uni = (x + y).size
        return if (uni == 0) 0.0 else (x intersect y).size.toDouble() / uni
    }

    /**
     * सबसे मिलता सीखा जवाब. exact = बिल्कुल वही सवाल ही (API चलती हो तब). नहीं तो [FUZZY_MIN] से ज़्यादा मिलता.
     * बराबर मिलें तो जो बार-बार आया / नया - वह.
     */
    fun match(all: List<Entry>, q: String, exact: Boolean, now: Long): Entry? {
        if (!askable(q)) return null
        all.lastOrNull { it.q == q }?.let { return if (!exact || stable(it, now)) it else null }
        if (exact) return null
        var best: Entry? = null; var score = 0.0
        for (e in all) {
            val s = similarity(q, e.q)
            if (s > score || (s == score && best != null && (e.n > best.n || (e.n == best.n && e.t > best.t)))) { score = s; best = e }
        }
        return best?.takeIf { score >= FUZZY_MIN }
    }

    /** नई बात जोड़ो: वही सवाल = नया जवाब, गिनती +1. [MAX] से ज़्यादा = सबसे कम काम की पुरानी हटे */
    fun add(all: List<Entry>, q: String, a: String, now: Long): List<Entry> {
        val old = all.firstOrNull { it.q == q }
        val out = all.filter { it.q != q } + Entry(q, a.trim(), now, (old?.n ?: 0) + 1)
        return trim(out)
    }

    fun trim(list: List<Entry>): List<Entry> {
        if (list.size <= MAX) return list
        val drop = list.sortedWith(compareBy<Entry> { it.n }.thenBy { it.t }).take(list.size - MAX).toSet()
        return list.filter { it !in drop }
    }

    /** फ़ोन + बैकअप: कुछ नहीं मिटता; वही सवाल = जो नया सीखा वह जवाब, गिनती जुड़ती है */
    fun merge(local: List<Entry>, backup: List<Entry>): List<Entry> {
        val m = LinkedHashMap<String, Entry>()
        for (e in (backup + local).sortedBy { it.t }) {
            val o = m[e.q]
            m[e.q] = if (o == null) e else e.copy(n = maxOf(e.n, o.n) + if (e.a != o.a) 1 else 0)
        }
        return trim(m.values.sortedBy { it.t })
    }

    fun toJson(list: List<Entry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("q", it.q).put("a", it.a).put("t", it.t).put("n", it.n)) }
    }.toString()

    /** पुरानी साझा फ़ाइल (dk_learned.json) से सिर्फ़ सवाल-जवाब वाली बातें (उसमें [LearnedWays] के तरीके भी हो सकते हैं) */
    fun oldEntries(s: String?): List<Entry> = fromJson(s).filter { it.t > 0 }

    fun fromJson(s: String?): List<Entry> = try {
        val a = JSONArray(s.orEmpty().ifBlank { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Entry(o.optString("q"), o.optString("a"), o.optLong("t"), o.optInt("n", 1).coerceAtLeast(1))
                .takeIf { it.q.isNotBlank() && it.a.isNotBlank() }
        }
    } catch (_: Exception) { emptyList() }
}
