package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.TreeMap

/**
 * 13 नवंबर 2026 (मालिक): वेब खोज के जवाब और स्रोत - तीनों प्रदाता (Tavily -> Exa -> Google) एक ही साँचे में.
 *
 * 1. [normalizeResults] = हर प्रदाता के कच्चे नतीजे -> [Src] {id, title, url, site, date, snippet, provider}
 * 2. जवाब में [1] / [2][3] निशान -> दिखाते समय वाक्य के अंत में छोटे चिप (site), नीचे "स्रोत (N)" सूची (सिर्फ़ इस्तेमाल हुए)
 * 3. Google ग्राउंडिंग: groundingSupports[].segment.endIndex **UTF-8 बाइट** में - [insertMarkers] बाइट पर काटकर [n] डालता है
 *    (TextEncoder = toByteArray(UTF_8), TextDecoder = String(bytes, UTF_8)), ताकि आगे का काम बाकी प्रदाताओं जैसा ही रहे.
 *
 * Pure हिस्सा (Android नहीं) - JUnit से जाँचा जाता है. दिखाने वाला हिस्सा [GroundView]. Key कभी यहाँ नहीं आती.
 */
object WebCite {
    const val TAVILY = "Tavily"
    const val EXA = "Exa"
    const val GOOGLE = "Google"
    /** एक खोज में कितने नतीजे (5-6) */
    const val MAX = 6

    data class Src(val id: Int, val title: String, val url: String, val site: String, val date: String, val snippet: String, val provider: String)

    /** खोज-क्रम की सेवा का दिखने वाला नाम (Gemini + Google Search = "Google") */
    fun nameOf(p: WebSearch.Provider): String = when (p) {
        WebSearch.Provider.TAVILY -> TAVILY
        WebSearch.Provider.EXA -> EXA
        WebSearch.Provider.GEMINI -> GOOGLE
    }

    // ------------------------------------------------------------------ 1. साझा साँचा

    private fun JSONObject.s(k: String): String = optString(k).trim().let { if (it == "null") "" else it }

    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /** url से छोटा नाम: www. और .com हटाकर (apnews, cnn); Google के redirect link पर title (जैसे "ndtv.com") से */
    fun site(url: String, title: String = ""): String = GroundCite.shortName(title, url).ifBlank { Grounding.host(url) }

    /** तारीख़ एक रूप में: 2026-11-13 (पता न चले = जैसी मिली, छोटी) */
    fun date(d: String): String {
        val t = d.trim()
        if (t.isBlank() || t == "null") return ""
        Regex("^(\\d{4}-\\d{2}-\\d{2})").find(t)?.let { return it.groupValues[1] }
        return try {
            java.time.ZonedDateTime.parse(t, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString()
        } catch (_: Exception) { t.take(16) }
    }

    /** Gemini का जवाब / उसका candidate / सीधा groundingMetadata - तीनों से groundingMetadata */
    fun groundingMeta(raw: JSONObject): JSONObject? = when {
        raw.has("groundingChunks") -> raw
        raw.optJSONObject("groundingMetadata") != null -> raw.optJSONObject("groundingMetadata")
        else -> raw.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("groundingMetadata")
    }

    fun normalizeResults(provider: String, raw: String): List<Src> =
        try { normalizeResults(provider, JSONObject(raw)) } catch (_: Exception) { emptyList() }

    /**
     * हर प्रदाता के कच्चे नतीजे -> [Src]:
     * - Tavily: results[].title, url, content, published_date
     * - Exa: results[].title, url, publishedDate, highlights[] (या text, या summary)
     * - Google: Gemini ग्राउंडिंग = groundingMetadata.groundingChunks[].web.{uri,title}; Custom Search API = items[].title, link, snippet
     * Tavily/Exa/CSE: सिर्फ़ http link, वही link दोबारा नहीं, ज़्यादा से ज़्यादा [MAX]; id 1 से.
     * Google ग्राउंडिंग: id = chunk का क्रम + 1 (groundingChunkIndices उसी क्रम पर) - इसलिए कुछ नहीं छाँटा जाता.
     */
    fun normalizeResults(provider: String, raw: JSONObject?): List<Src> {
        if (raw == null) return emptyList()
        if (provider == GOOGLE) {
            groundingMeta(raw)?.optJSONArray("groundingChunks")?.let { arr ->
                return (0 until arr.length()).mapNotNull { i ->
                    val w = arr.optJSONObject(i)?.optJSONObject("web") ?: return@mapNotNull null
                    val uri = w.s("uri")
                    if (!uri.startsWith("http")) return@mapNotNull null
                    val title = w.s("title").ifBlank { w.s("domain") }.ifBlank { Grounding.host(uri) }
                    Src(i + 1, title, uri, site(uri, title), "", "", GOOGLE)
                }
            }
        }
        val rows: List<List<String>> = when (provider) {
            TAVILY -> objs(raw.optJSONArray("results")).map { listOf(it.s("title"), it.s("url"), it.s("content"), it.s("published_date")) }
            EXA -> objs(raw.optJSONArray("results")).map { r ->
                val hl = r.optJSONArray("highlights")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() && it != "null" } }
                    .orEmpty().joinToString(" … ")
                listOf(r.s("title"), r.s("url"), hl.ifBlank { r.s("text") }.ifBlank { r.s("summary") }, r.s("publishedDate"))
            }
            GOOGLE -> objs(raw.optJSONArray("items")).map { listOf(it.s("title"), it.s("link"), it.s("snippet"), "") }
            else -> emptyList()
        }
        val out = ArrayList<Src>()
        for ((title, url, text, d) in rows) {
            if (!url.startsWith("http") || out.any { it.url == url }) continue
            val snip = text.replace(Regex("\\s+"), " ").trim().take(600)
            out.add(Src(out.size + 1, title.ifBlank { Grounding.host(url) }, url, site(url, title), date(d), snip, provider))
            if (out.size >= MAX) break
        }
        return out
    }

    // ------------------------------------------------------------------ 2. होमपेज वाले नतीजे = अगला प्रदाता

    /** link सिर्फ़ वेबसाइट का होमपेज है (path खाली या सिर्फ़ "/") */
    fun homepage(url: String): Boolean {
        val m = Regex("^https?://[^/?#]+(/[^?#]*)?").find(url.trim()) ?: return false
        val p = m.groupValues[1]
        return p.isEmpty() || p == "/"
    }

    fun onlyHomepages(srcs: List<Src>): Boolean = srcs.isNotEmpty() && srcs.all { homepage(it.url) }

    /** इस प्रदाता के नतीजे काम के नहीं: खाली / सारे होमपेज -> अगले प्रदाता पर (नतीजे आपस में नहीं मिलाते) */
    fun useless(srcs: List<Src>): Boolean = srcs.isEmpty() || onlyHomepages(srcs)

    // ------------------------------------------------------------------ 3. खोज का सवाल

    private val NEWS = Regex("(?i)\\b(news|headlines?|breaking)\\b|ख़बर|खबर|समाचार|न्यूज़|न्यूज|सुर्ख़ि|सुर्खि|बातमी")

    /** ख़बरों वाला सवाल? (Tavily topic=news + time_range=day, Exa category=news + startPublishedDate) */
    fun isNews(q: String): Boolean = NEWS.containsMatchIn(q)

    /** पिछले एक दिन की शुरुआत (ISO 8601, UTC) - Exa startPublishedDate */
    fun sinceIso(nowMs: Long, hours: Int = 24): String =
        java.time.Instant.ofEpochMilli(nowMs - hours * 3_600_000L).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    fun rewriteSystem(dateEn: String, news: Boolean): String =
        "Rewrite the user's message (it may be Hindi, Hinglish, or have speech-recognition mistakes) into ONE clear, specific English web search query. " +
            "Today is $dateEn - include this date (or its month and year) in the query. Keep every name, place, team, product and number. " +
            (if (news) "It is a news question: ask for the latest news of today. " else "") +
            "Output ONLY the query text: one line, no quotes, no explanation, at most 25 words."

    /** मॉडल का लिखा खोज-वाक्य साफ़ करो; बेकार हो = सवाल + आज की तारीख़ */
    fun cleanQuery(out: String?, question: String, dateEn: String): String {
        val line = out.orEmpty().lines().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("^(?i)(search\\s+)?query\\s*:\\s*"), "").trim().trim('"', '\'', '`', '“', '”').replace(Regex("\\s+"), " ").take(300)
        if (line.length < 3) return fallbackQuery(question, dateEn)
        val year = Regex("\\d{4}").find(dateEn)?.value
        return if (year != null && !line.contains(year)) "$line $dateEn" else line
    }

    fun fallbackQuery(question: String, dateEn: String): String = "${question.trim().take(300)} ($dateEn)"

    // ------------------------------------------------------------------ 4. जवाब बनाने का एक ही तरीका

    /** मालिक के चार नियम (शब्दशः) */
    val RULES = listOf(
        "सिर्फ़ इन्हीं स्रोतों से जवाब दो।",
        "हर वाक्य या बुलेट के अंत में [1] या [2][3] की तरह संख्या लगाओ।",
        "जो बात स्रोतों में नहीं है वह मत लिखो, और संख्याएँ मत गढ़ो।",
        "अगर स्रोत सिर्फ़ वेबसाइटों के होमपेज हैं और खबर का ब्यौरा नहीं है, तो कहो कि ठोस खबर नहीं मिली।",
    )

    fun answerSystem(nowLine: String, name: String, user: String): String = buildString {
        append("You are $name, a Hindi assistant")
        if (user.isNotBlank()) append(" talking to $user")
        append(". $nowLine\n")
        append("Below are numbered web search sources [1], [2], ... for the user's question. Rules:\n")
        RULES.forEach { append("- ").append(it).append("\n") }
        append("- (Same in English) Answer ONLY from these sources. End EVERY sentence or bullet with its source number(s) like [1] or [2][3], ")
        append("placed just before the full stop / । (example: \"भारत ने मैच जीता [1]।\"). Never write a fact that is not in the sources, never invent numbers. ")
        append("If the sources are only website homepages without the actual news details, say that no concrete news was found.\n")
        append("- Write in natural Hindi (Devanagari; English names ok), 2-6 short sentences or bullets, most important first. ")
        append("No URLs, no headings, no bold, no source list at the end (the app shows it).")
    }

    fun answerInput(question: String, srcs: List<Src>, query: String = ""): String = buildString {
        append("USER QUESTION: ").append(question.trim()).append("\n")
        if (query.isNotBlank()) append("SEARCH QUERY USED: ").append(query.trim()).append("\n")
        append("\nSOURCES:\n")
        for (s in srcs) {
            append("[").append(s.id).append("] ").append(s.title.ifBlank { s.site })
            append(" (").append(s.site); if (s.date.isNotBlank()) append(", ").append(s.date); append(")")
            if (homepage(s.url)) append(" [HOMEPAGE ONLY]")
            append("\n")
            if (s.snippet.isNotBlank()) append(s.snippet.take(700)).append("\n")
            append("\n")
        }
    }.trim()

    /** जवाब बनाने वाला AI न चले: स्रोतों के ही वाक्य (अपनी तरफ़ से कुछ नहीं), हर एक पर उसका [n] */
    fun extractive(srcs: List<Src>): String =
        srcs.filter { it.snippet.isNotBlank() && !homepage(it.url) }.take(3).joinToString("\n") { s ->
            val first = Regex("^(.{20,240}?[.!?।])(\\s|$)").find(s.snippet)?.groupValues?.get(1) ?: s.snippet.take(220)
            "• ${first.trim()} [${s.id}]"
        }

    // ------------------------------------------------------------------ [n] निशान

    private val MARK = Regex("\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)\\]")
    private val PUNCT = "।.!?,;:"

    private fun idsOf(group: String): List<Int> = group.split(',').mapNotNull { it.trim().toIntOrNull() }

    /** जवाब में जिन स्रोतों के निशान हैं (पहली बार आने के क्रम में) */
    fun ids(text: String): List<Int> = MARK.findAll(text).flatMap { idsOf(it.groupValues[1]) }.distinct().toList()

    /** बोलने / याद रखने के लिए: सारे [n] हटाओ */
    fun strip(text: String): String = text.replace(Regex("[ \\t]*" + MARK.pattern), "")

    /** स्ट्रीमिंग में: पूरे निशान हटाओ और आख़िर का अधूरा निशान ("[1", "[2, ") भी - चिप जवाब पूरा होने पर */
    fun streamSafe(text: String): String = strip(text).replace(Regex("[ \\t]*\\[\\d{0,2}(?:\\s*,\\s*\\d{0,2})*$"), "")

    /** जो संख्या किसी स्रोत की नहीं वह निशान हटाओ (नकली लिंक कभी नहीं) */
    private val MARK_SP = Regex("([ \\t]*)" + MARK.pattern)

    fun sanitize(text: String, valid: Set<Int>): String = MARK_SP.replace(text) { m ->
        val ok = idsOf(m.groupValues[2]).filter { it in valid }.distinct()
        if (ok.isEmpty()) "" else m.groupValues[1] + ok.joinToString("") { "[$it]" }
    }

    /** दिखाने के लिए: निशान हटा text + हर चिप की जगह (अक्षर-index) और वहाँ के स्रोत. पास-पास के निशान एक जगह; वाक्य-चिह्न चिप से पहले. */
    data class Layout(val text: String, val at: List<Pair<Int, List<Int>>>)

    fun layout(text: String): Layout {
        val sb = StringBuilder()
        val at = TreeMap<Int, LinkedHashSet<Int>>()
        var i = 0
        while (true) {
            val m = MARK.find(text, i) ?: break
            sb.append(text, i, m.range.first)
            while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
            val got = LinkedHashSet(idsOf(m.groupValues[1]))
            var j = m.range.last + 1
            // [1][2] / [1] [2] = एक ही जगह
            while (true) {
                var k = j
                while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
                val n = MARK.find(text, k)
                if (n == null || n.range.first != k) break
                got.addAll(idsOf(n.groupValues[1])); j = n.range.last + 1
            }
            while (j < text.length && text[j] in PUNCT) { sb.append(text[j]); j++ }
            at.getOrPut(sb.length) { LinkedHashSet() }.addAll(got)
            i = j
        }
        sb.append(text, i, text.length)
        return Layout(sb.toString(), at.entries.map { it.key to it.value.toList() })
    }

    // ------------------------------------------------------------------ 5. Google ग्राउंडिंग: बाइट पर [n]

    /**
     * [supports] = (endIndex बाइट में, groundingChunkIndices). TextEncoder की तरह बाइट बनाओ, हर endIndex पर
     * "[i+1]" डालो (बीच में कटा अक्षर = उस अक्षर के अंत पर), TextDecoder की तरह वापस जोड़ो. [offset] = id में जोड़ (कई खोज मिलाते समय).
     */
    fun insertMarkers(raw: String, supports: List<Pair<Int, List<Int>>>, offset: Int = 0): String {
        val b = raw.toByteArray(Charsets.UTF_8)
        val at = TreeMap<Int, LinkedHashSet<Int>>()
        for ((e, idx) in supports) {
            if (e <= 0 || e > b.size || idx.isEmpty()) continue
            var k = e
            while (k < b.size && (b[k].toInt() and 0xC0) == 0x80) k++
            at.getOrPut(k) { LinkedHashSet() }.addAll(idx.filter { it >= 0 })
        }
        if (at.isEmpty()) return raw
        val out = ByteArrayOutputStream(b.size + at.size * 4)
        var prev = 0
        for ((e, idx) in at) {
            if (idx.isEmpty()) continue
            out.write(b, prev, e - prev)
            out.write(idx.sorted().joinToString("") { "[${it + 1 + offset}]" }.toByteArray(Charsets.UTF_8))
            prev = e
        }
        out.write(b, prev, b.size - prev)
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** groundingMetadata.groundingSupports -> (endIndex बाइट, chunk indices) */
    fun supportsOf(meta: JSONObject?): List<Pair<Int, List<Int>>> = objs(meta?.optJSONArray("groundingSupports")).mapNotNull { s ->
        val e = s.optJSONObject("segment")?.optInt("endIndex", -1) ?: -1
        val idx = s.optJSONArray("groundingChunkIndices")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }.orEmpty().filter { it >= 0 }
        if (e <= 0 || idx.isEmpty()) null else e to idx
    }

    /** Google ग्राउंडिंग के chunk -> [Src] (id = क्रम + 1 + [offset]) */
    fun googleSrcs(g: GroundCite.G, offset: Int = 0): List<Src> = g.chunks.mapIndexedNotNull { i, (t, u) ->
        if (!u.startsWith("http")) null else Src(offset + i + 1, t, u, site(u, t), "", "", GOOGLE)
    }

    /**
     * Google के जवाब में [n] डालो: [text] कच्चे जवाब (g.raw) का हिस्सा है = बाइट से सीधी जगह;
     * text साफ़ किया हुआ (markdown हटा) = वाक्य का आख़िरी हिस्सा ढूँढकर ([GroundCite.points]).
     */
    fun markGoogle(text: String, g: GroundCite.G, offset: Int = 0): String {
        if (text.isBlank()) return text
        val raw = g.raw
        val at = if (raw.isNotEmpty()) raw.indexOf(text) else -1
        if (at >= 0 && g.cites.any { it.endByte > 0 }) {
            val off = raw.substring(0, at).toByteArray(Charsets.UTF_8).size
            val len = text.toByteArray(Charsets.UTF_8).size
            val sup = g.cites.filter { it.endByte > 0 }.map { (it.endByte - off) to it.chunks }.filter { it.first in 1..len }
            return insertMarkers(text, sup, offset)
        }
        val pts = GroundCite.points(text, g)
        if (pts.isEmpty()) return text
        val sb = StringBuilder(text)
        for ((pos, idx) in pts.asReversed()) sb.insert(pos.coerceIn(0, sb.length), idx.sorted().joinToString("") { "[${it + 1 + offset}]" })
        return sb.toString()
    }

    // ------------------------------------------------------------------ 6/7. दिखाने के लिए एक रूप (सब प्रदाता)

    /** एक जवाब: [text] में [n] निशान (हो सकते हैं), [srcs] = उसी एक प्रदाता के स्रोत */
    data class Card(val text: String, val srcs: List<Src>, val provider: String, val failed: List<String>, val entryHtml: String, val live: Boolean) {
        /** जवाब में सच में किसी स्रोत का निशान है */
        val marked: Boolean get() = ids(text).any { id -> srcs.any { it.id == id } }
    }

    /** खोज नहीं हुई (g = null) / कोई असली link नहीं = null: कोई चिप, कोई सूची नहीं */
    fun card(text: String, g: GroundCite.G?): Card? {
        if (g == null) return null
        val srcs = g.srcs.ifEmpty { googleSrcs(g) }.filter { it.url.startsWith("http") }
        if (srcs.isEmpty()) return null
        val valid = srcs.map { it.id }.toSet()
        val t = if (g.srcs.isEmpty() && ids(text).isEmpty()) markGoogle(text, g) else text
        return Card(sanitize(t, valid), srcs, g.provider.ifBlank { GOOGLE }, g.failed, g.entryHtml, g.live)
    }

    /** नीचे की सूची: सिर्फ़ जवाब में इस्तेमाल हुए स्रोत (कोई निशान नहीं - जैसे DK लाइव = दिए गए सारे) */
    fun used(card: Card): List<Src> {
        if (!card.marked) return card.srcs.distinctBy { it.url }
        val u = ids(card.text).toSet()
        return card.srcs.filter { it.id in u }.sortedBy { it.id }.distinctBy { it.url }
    }

    fun title(n: Int): String = "स्रोत ($n)"

    /** हेडर में प्रदाता: "Exa" + फ़ॉलबैक हुआ हो तो छोटे अक्षरों वाला हिस्सा "(Tavily फ़ेल)" */
    fun failNote(failed: List<String>): String = if (failed.isEmpty()) "" else "(" + failed.distinct().joinToString(", ") + " फ़ेल)"

    fun providerLine(provider: String, failed: List<String>): String = listOf(provider, failNote(failed)).filter { it.isNotBlank() }.joinToString(" ")

    /** Tavily / Exa / Google के [Src] -> [GroundCite.G] (चैट में सहेजा जाता है, सारे UI वही पढ़ते हैं) */
    fun toG(srcs: List<Src>, provider: String, failed: List<String> = emptyList(), entryHtml: String = "", live: Boolean = false): GroundCite.G =
        GroundCite.G(srcs.map { it.title to it.url }, emptyList(), emptyList(), entryHtml, "", live, provider, failed, srcs)

    /** DK लाइव के tool जवाब के लिए: क्रम वाले स्रोत (Live खुद बोलकर जवाब बनाता है) */
    fun liveReport(srcs: List<Src>): String = buildString {
        for (s in srcs) {
            append(s.id).append(". ").append(s.title.ifBlank { s.site }).append(" (").append(s.site)
            if (s.date.isNotBlank()) append(", ").append(s.date); append(")")
            if (s.snippet.isNotBlank()) append(": ").append(s.snippet.take(500))
            append("\n")
        }
    }.trim()

    // ------------------------------------------------------------------ सहेजना

    fun toJson(s: Src): JSONObject = JSONObject().put("i", s.id).put("t", s.title).put("u", s.url).put("s", s.site)
        .put("d", s.date).put("n", s.snippet).put("p", s.provider)

    fun fromJson(o: JSONObject): Src? {
        val u = o.optString("u"); if (!u.startsWith("http")) return null
        return Src(o.optInt("i"), o.optString("t"), u, o.optString("s"), o.optString("d"), o.optString("n"), o.optString("p"))
    }
}
