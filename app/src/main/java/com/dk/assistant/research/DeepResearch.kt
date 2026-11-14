package com.dk.assistant.research

import org.json.JSONArray
import org.json.JSONObject

/**
 * 14 नवंबर 2026 (मालिक): DK AI की "डीप रिसर्च" (गहरी खोज) - नियम एक जगह (JVM test).
 *
 *  1. सवाल तोड़ो: 3-5 उप-सवाल (मुख्य बात, ताज़ा खबर, अलग राय, आँकड़े)
 *  2. एक साथ खोज: Exa, Tavily, Linkup - हर टूल को अलग शब्दों में
 *  3. duplicate हटाओ, सबसे भरोसेमंद + ताज़े 8-15 लिंक -> Firecrawl से पूरा पेज (snippet पर भरोसा नहीं)
 *  4. जाँचो: बात तभी पक्की जब कम से कम 2 अलग वेबसाइट वही कहें; टकराव = दोनों पक्ष + तारीख़
 *  5. कमी हो तो 2-4 फिर (ज़्यादा से ज़्यादा 3 चक्कर)
 *  6. आख़िर में Gemini सारी जानकारी पढ़कर हिंदी रिपोर्ट
 *
 * कोई टूल error / 429 / क्रेडिट खत्म = इस रिसर्च में छोड़ो, बाकी से काम. एक रिसर्च में ज़्यादा से ज़्यादा 15 पेज.
 */
object DeepResearch {
    const val MAX_PAGES = 15
    const val MIN_PICK = 8
    const val MAX_ROUNDS = 3
    const val MAX_SUBS = 5
    const val PER_DOMAIN = 2
    const val PAGE_CHARS = 3500
    const val TOTAL_CHARS = 42_000

    enum class Tool(val label: String) { EXA("Exa"), TAVILY("Tavily"), LINKUP("Linkup"), FIRECRAWL("Firecrawl") }

    enum class Kind { MAIN, NEWS, OPINION, STATS }

    data class Hit(val title: String, val url: String, val snippet: String, val date: String, val tool: Tool)

    /** एक जैसे लिंक मिलाकर: किन-किन खोजियों ने यह लिंक दिया (ज़्यादा = ज़्यादा भरोसा) */
    data class Link(val title: String, val url: String, val snippet: String, val date: String, val tools: Set<Tool>)

    data class Page(val title: String, val url: String, val text: String, val date: String, val full: Boolean)

    // ------------------------------------------------------------------ कब चलाएँ
    private val ASK = listOf(
        "डीप रिसर्च करो", "डीप रिसर्च कीजिए", "डीप रिसर्च कर", "गहरी खोज करो", "गहरी खोज करके", "गहरी खोज कर",
        "डीप रिसर्च", "डीप सर्च", "गहरी खोज", "गहराई से खोज", "गहन खोज", "गहरी रिसर्च", "पूरी रिसर्च", "रिसर्च करो", "रिसर्च कर",
        "deep research", "deep search", "deepresearch", "research karo", "research kar", "gehri khoj", "gahri khoj"
    )
    private val AUTO = listOf(
        "तुलना", "compare", "comparison", " vs ", " vs. ", "versus", "अलग-अलग राय", "अलग अलग राय", "सभी स्रोत", "कई वेबसाइट",
        "विस्तार से", "पूरी जानकारी", "pros and cons", "फायदे और नुकसान", "फ़ायदे और नुकसान", "रिपोर्ट बनाओ", "विश्लेषण", "analysis",
        // 14 नवंबर 2026 (मालिक: "मैं डीप रिसर्च न भी बोलूँ तो अपना दिमाग लगाए"): सबसे अच्छा चुनना / नए लॉन्च / टियर-प्लान वाले सवाल
        "सबसे अच्छा", "सबसे अच्छी", "बेस्ट", " best ", "top 5", "top 10", "टॉप 5", "टॉप 10", "कौन-कौन से", "कौन कौन से", "ओपन सोर्स", "open source",
        "लॉन्च", "लांच", "launch", "टियर", " tier", "pricing", "प्राइसिंग", "प्लान में क्या", "recommend", "सुझाव दो"
    )
    /** "आज / कल / नया / latest / launch" = ताज़ा जानकारी चाहिए: खोज में तारीख़ की सीमा + आज की तारीख़ */
    private val FRESH = listOf("आज", "कल ", "अभी", "नया", "नई", "नए", "ताज़ा", "ताजा", "लेटेस्ट", "लॉन्च", "लांच", "इस हफ्ते", "इस महीने",
        "latest", "new ", "newest", "today", "yesterday", "this week", "launch", "released", "current", "abhi", "naya", "nayi")

    fun fresh(q: String): Boolean { val l = " " + q.lowercase() + " "; return FRESH.any { l.contains(it) } }

    /** यूज़र ने साफ़ "डीप रिसर्च" माँगा? */
    fun asked(q: String): Boolean { val l = " " + q.lowercase() + " "; return ASK.any { l.contains(it) } }

    /**
     * डीप रिसर्च चलाएँ? माँगा = हाँ. [auto] चालू हो तो कई वेबसाइटों से जोड़ने वाला लंबा सवाल भी.
     * साधारण सवाल (छोटा / "नमस्ते" / "समय क्या है") = नहीं (क्रेडिट बचाओ).
     */
    fun wanted(q: String, auto: Boolean): Boolean {
        val t = q.trim()
        if (t.isEmpty()) return false
        if (asked(t)) return true
        if (!auto) return false
        val l = " " + t.lowercase() + " "
        return t.length >= 25 && AUTO.any { l.contains(it) }
    }

    /** "डीप रिसर्च करो: X" -> "X" */
    fun topic(q: String): String {
        var t = q.trim()
        for (w in ASK.sortedByDescending { it.length }) t = Regex(Regex.escape(w), RegexOption.IGNORE_CASE).replace(t, " ")
        t = t.replace(Regex("^[\\s:,.!?-]*(पर|के बारे में|about|on)\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(पर|के बारे में)\\s*(करो|कीजिए|करें)?\\s*$"), "")
        t = t.replace(Regex("\\s+"), " ").trim().trim(':', ',', '.', '-', '।', ' ')
        return t.ifEmpty { q.trim() }
    }

    // ------------------------------------------------------------------ 1. सवाल तोड़ो
    /** AI न चले तो अपने-आप 4 उप-सवाल (मुख्य, ताज़ा खबर, अलग राय, आँकड़े) */
    fun fallbackSubs(topic: String): List<Pair<Kind, String>> = listOf(
        Kind.MAIN to topic,
        Kind.NEWS to "$topic latest news",
        Kind.OPINION to "$topic different opinions criticism",
        Kind.STATS to "$topic statistics data numbers"
    )

    fun planPrompt(question: String, today: String = ""): String =
        (if (today.isBlank()) "" else "Today is $today. Use this date: for latest/new/launch/current questions put the current month and year in the queries, " +
            "and add one query aimed at the official source (company docs, pricing or changelog page). ") +
        "Break this research question into 3-5 web search sub-questions: main facts, latest news, different opinions, statistics/data. " +
            "Write each as a short English search query. Reply ONLY JSON: {\"subs\":[{\"kind\":\"main|news|opinion|stats\",\"q\":\"...\"}]}\n" +
            "Question: $question"

    fun parsePlan(raw: String, topic: String): List<Pair<Kind, String>> {
        val out = ArrayList<Pair<Kind, String>>()
        try {
            val o = JSONObject(jsonPart(raw))
            val a = o.optJSONArray("subs") ?: JSONArray()
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val q = s.optString("q").trim()
                if (q.isEmpty()) continue
                val k = when (s.optString("kind").lowercase()) { "news" -> Kind.NEWS; "opinion" -> Kind.OPINION; "stats" -> Kind.STATS; else -> Kind.MAIN }
                if (out.none { it.second.equals(q, true) }) out.add(k to q)
            }
        } catch (_: Exception) {}
        if (out.size < 3) return fallbackSubs(topic)
        return out.take(MAX_SUBS)
    }

    // ------------------------------------------------------------------ 2. हर टूल को अलग शब्द
    fun phrase(tool: Tool, kind: Kind, q: String): String = when (tool) {
        // Exa: मतलब से खोज - पूरा वाक्य
        Tool.EXA -> when (kind) {
            Kind.MAIN -> "In-depth explanation of $q"
            Kind.NEWS -> "Recent news article about $q"
            Kind.OPINION -> "Expert analysis and opinions on $q"
            Kind.STATS -> "Report with statistics and data on $q"
        }
        // Tavily: ताज़ा खबर - छोटे शब्द
        Tool.TAVILY -> when (kind) {
            Kind.NEWS -> "$q today"
            else -> q
        }
        // Linkup: अलग वेबसाइटें - सवाल की तरह
        Tool.LINKUP -> when (kind) {
            Kind.MAIN -> "What are the key facts about $q?"
            Kind.NEWS -> "What is the latest update on $q?"
            Kind.OPINION -> "What do critics and supporters say about $q?"
            Kind.STATS -> "What are the latest figures for $q?"
        }
        Tool.FIRECRAWL -> q
    }

    // ------------------------------------------------------------------ 3. duplicate हटाओ + चुनो
    /** एक ही पेज के अलग-अलग रूप (www, utm, #, आख़िरी /) एक */
    fun normalize(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return ""
        u = u.substringBefore('#')
        val scheme = u.substringBefore("://", "https")
        var rest = u.substringAfter("://", u)
        val host = rest.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
        rest = rest.substring(rest.substringBefore('/').substringBefore('?').length)
        val path = rest.substringBefore('?').trimEnd('/')
        val query = rest.substringAfter('?', "").split('&').filter { it.isNotBlank() && !it.lowercase().startsWith("utm_") &&
            !it.lowercase().startsWith("fbclid") && !it.lowercase().startsWith("gclid") && !it.lowercase().startsWith("ref=") }
        return (if (scheme.equals("http", true)) "https" else scheme.lowercase()) + "://" + host + path + (if (query.isEmpty()) "" else "?" + query.joinToString("&"))
    }

    fun domain(url: String): String = normalize(url).substringAfter("://").substringBefore('/').substringBefore('?')

    fun merge(hits: List<Hit>): List<Link> {
        val m = LinkedHashMap<String, Link>()
        for (h in hits) {
            val k = normalize(h.url)
            if (k.isEmpty() || !k.startsWith("http")) continue
            val old = m[k]
            m[k] = if (old == null) Link(h.title.trim(), h.url.trim(), h.snippet.trim(), h.date.trim(), setOf(h.tool))
            else old.copy(
                title = old.title.ifBlank { h.title.trim() },
                snippet = if (h.snippet.length > old.snippet.length) h.snippet.trim() else old.snippet,
                date = old.date.ifBlank { h.date.trim() },
                tools = old.tools + h.tool)
        }
        return m.values.toList()
    }

    /** साल (2026-11-03 / Nov 3, 2026 …) -> अंक; न मिले = 0 */
    fun year(date: String): Int = Regex("(19|20)\\d{2}").find(date)?.value?.toIntOrNull() ?: 0

    private val LOW = listOf("pinterest.", "facebook.com", "instagram.com", "tiktok.com", "x.com", "twitter.com", "quora.com")
    private val HIGH = listOf(".gov", ".gov.in", ".nic.in", ".edu", ".ac.in", "wikipedia.org", "reuters.com", "apnews.com", "bbc.", "who.int",
        "worldbank.org", "imf.org", "nature.com", "thehindu.com", "indianexpress.com", "pib.gov.in", "rbi.org.in")

    fun score(l: Link, nowYear: Int): Int {
        val d = domain(l.url)
        var s = l.tools.size * 30
        if (HIGH.any { d.endsWith(it) || d.contains(it) }) s += 25
        if (LOW.any { d.contains(it) }) s -= 40
        val y = year(l.date)
        if (y > 0) s += when { y >= nowYear -> 20; y == nowYear - 1 -> 12; y >= nowYear - 3 -> 5; else -> 0 }
        s += (l.snippet.length / 100).coerceAtMost(8)
        return s
    }

    /**
     * सबसे भरोसेमंद + ताज़े लिंक: एक वेबसाइट से ज़्यादा से ज़्यादा 2, पहले पढ़े हुए नहीं, [budget] से ज़्यादा नहीं.
     * आम तौर पर 8-15 (कम मिले तो जितने हैं).
     */
    fun pick(links: List<Link>, seen: Set<String>, budget: Int, nowYear: Int, want: Int = MAX_PAGES): List<Link> {
        val n = minOf(want, budget, MAX_PAGES).coerceAtLeast(0)
        if (n == 0) return emptyList()
        val per = HashMap<String, Int>()
        val out = ArrayList<Link>()
        for (l in links.filter { normalize(it.url) !in seen }.sortedByDescending { score(it, nowYear) }) {
            val d = domain(l.url)
            if ((per[d] ?: 0) >= PER_DOMAIN) continue
            per[d] = (per[d] ?: 0) + 1
            out.add(l)
            if (out.size >= n) break
        }
        return out
    }

    // ------------------------------------------------------------------ टूल के जवाब पढ़ना
    fun parseExa(body: String): List<Hit> = arr(body, "results").map { o ->
        val snip = o.optString("text").ifBlank { o.optString("summary") }.ifBlank {
            o.optJSONArray("highlights")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }.orEmpty()
        }
        Hit(o.optString("title"), o.optString("url"), snip.take(600), o.optString("publishedDate"), Tool.EXA)
    }

    fun parseTavily(body: String): List<Hit> = arr(body, "results").map { o ->
        Hit(o.optString("title"), o.optString("url"), o.optString("content").take(600), o.optString("published_date"), Tool.TAVILY)
    }

    fun parseLinkup(body: String): List<Hit> = arr(body, "results").map { o ->
        Hit(o.optString("name").ifBlank { o.optString("title") }, o.optString("url"), o.optString("content").take(600),
            o.optString("date").ifBlank { o.optString("publishedDate") }, Tool.LINKUP)
    }

    /** Firecrawl /v2/scrape -> (शीर्षक, पूरा लिखा, तारीख़) */
    fun parseFirecrawl(body: String): Triple<String, String, String>? = try {
        val d = JSONObject(body).optJSONObject("data")
        val md = d?.optString("markdown").orEmpty()
        if (md.isBlank()) null else {
            val meta = d?.optJSONObject("metadata")
            Triple(meta?.optString("title").orEmpty(), md,
                meta?.optString("publishedTime").orEmpty().ifBlank { meta?.optString("article:published_time").orEmpty() })
        }
    } catch (_: Exception) { null }

    /** Tavily extract -> url -> पूरा लिखा */
    fun parseTavilyExtract(body: String): Map<String, String> =
        arr(body, "results").mapNotNull { o -> o.optString("url").takeIf { it.isNotBlank() }?.let { normalize(it) to o.optString("raw_content") } }
            .filter { it.second.isNotBlank() }.toMap()

    /** Exa contents -> url -> पूरा लिखा */
    fun parseExaContents(body: String): Map<String, String> =
        arr(body, "results").mapNotNull { o -> o.optString("url").takeIf { it.isNotBlank() }?.let { normalize(it) to o.optString("text") } }
            .filter { it.second.isNotBlank() }.toMap()

    private fun arr(body: String, name: String): List<JSONObject> = try {
        val a = JSONObject(body).optJSONArray(name) ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    /** टूल की गलती का संदेश (key कभी नहीं) */
    fun failNote(tool: Tool, code: Int): String = tool.label + " छोड़ा — " + when (code) {
        401, 403 -> "key गलत / बंद"
        402 -> "क्रेडिट खत्म"
        429 -> "limit पूरी (429)"
        0, -1 -> "नेट / समय खत्म"
        else -> "गड़बड़ी ($code)"
    }

    /** Settings के "जाँचें" का संदेश */
    fun checkMessage(tool: Tool, code: Int): String = when (code) {
        in 200..299 -> "✓ ${tool.label} key सही है — डीप रिसर्च में चलेगा"
        401, 403 -> "✗ ${tool.label} key गलत है या बंद है"
        402 -> "✗ ${tool.label} के क्रेडिट खत्म — डीप रिसर्च इसे छोड़कर बाकी टूल से चलेगी"
        429 -> "⚠ ${tool.label} की limit अभी पूरी है (429) — थोड़ी देर बाद"
        0 -> "⚠ नेट धीमा/बंद — ${tool.label} जाँच नहीं हो पाई"
        else -> "⚠ ${tool.label} ने गड़बड़ी बताई ($code)"
    }

    // ------------------------------------------------------------------ 5. कमी हो तो फिर
    fun gapPrompt(question: String, pages: List<Page>): String =
        "Research question: $question\nWe have read these sources:\n" +
            pages.mapIndexed { i, p -> "${i + 1}. ${p.title} (${domain(p.url)}) ${p.date}" }.joinToString("\n") +
            "\nIs any important part of the question still unanswered or supported by only one source? " +
            "If yes, give 1-3 new short English web search queries to fill the gap. If complete, give an empty list. " +
            "Reply ONLY JSON: {\"queries\":[\"...\"]}"

    fun parseGaps(raw: String): List<String> = try {
        val a = JSONObject(jsonPart(raw)).optJSONArray("queries") ?: JSONArray()
        (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.distinct().take(3)
    } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------------ 6. Gemini की हिंदी रिपोर्ट
    fun writerPrompt(question: String, pages: List<Page>, today: String, skipped: List<String>): String {
        val sb = StringBuilder()
        sb.append("तुम DK AI हो। नीचे वेबसाइटों से पूरा पढ़ा गया लिखा है (हर स्रोत का नंबर [n])। आज की तारीख़: ").append(today).append("\n")
        sb.append("यूज़र का सवाल: ").append(question).append("\n\n")
        sb.append("नियम:\n")
        sb.append("- सिर्फ़ इन्हीं स्रोतों से लिखो। अपने मन से कुछ मत जोड़ो। जो बात स्रोत में नहीं है, उसे \"पक्का नहीं\" लिखो।\n")
        sb.append("- कोई बात तभी पक्की मानो जब कम से कम 2 अलग वेबसाइट वही कहें; एक ही स्रोत हो तो \"(एक स्रोत)\" लिखो।\n")
        sb.append("- बातें टकराएँ तो दोनों पक्ष और उनकी तारीख़ लिखो।\n")
        sb.append("- हर खबर / आँकड़े के साथ तारीख़ लिखो (स्रोत में न हो तो \"तारीख़ पक्की नहीं\")।\n")
        sb.append("- हर बात के साथ [1], [2] जैसे स्रोत नंबर।\n\n")
        sb.append("जवाब ठीक इसी ढाँचे में, सरल हिंदी में (markdown के * या # नहीं):\n")
        sb.append("1. सीधा जवाब (2-3 लाइन)\n2. मुख्य बातें (• से शुरू होने वाली लाइनें)\n3. अलग-अलग राय या टकराव (अगर हों; नहीं तो \"कोई बड़ा टकराव नहीं मिला\")\n")
        sb.append("4. स्रोत: [n] शीर्षक — लिंक (सूची)\n5. भरोसा: पक्का / शायद / अधूरा (एक शब्द + छोटा कारण)\n\n")
        if (skipped.isNotEmpty()) sb.append("(इस रिसर्च में छोड़े गए टूल: ").append(skipped.joinToString("; ")).append(")\n\n")
        var used = 0
        pages.forEachIndexed { i, p ->
            val room = (TOTAL_CHARS - used).coerceAtLeast(0)
            if (room < 300) return@forEachIndexed
            val body = clean(p.text).take(minOf(PAGE_CHARS, room))
            used += body.length
            sb.append("[").append(i + 1).append("] ").append(p.title.ifBlank { domain(p.url) }).append("\n")
            sb.append("लिंक: ").append(p.url).append("\n")
            sb.append("तारीख़: ").append(p.date.ifBlank { "पता नहीं" }).append(if (p.full) "" else " (सिर्फ़ छोटा अंश, पूरा पेज नहीं खुला)").append("\n")
            sb.append(body).append("\n\n")
        }
        return sb.toString()
    }

    /** स्रोत की सूची (रिपोर्ट के नीचे; AI भूल जाए तो भी) */
    fun sourceList(pages: List<Page>): String =
        "स्रोत:\n" + pages.mapIndexed { i, p -> "[${i + 1}] ${p.title.ifBlank { domain(p.url) }}${if (p.date.isNotBlank()) " (" + p.date.take(10) + ")" else ""} — ${p.url}" }.joinToString("\n")

    /** रिपोर्ट में स्रोत-सूची न हो तो जोड़ो; बिना markdown */
    fun finish(report: String, pages: List<Page>): String {
        val r = report.replace(Regex("(?m)^#+\\s*"), "").replace("**", "").trim()
        val hasList = pages.isNotEmpty() && pages.take(3).count { r.contains(it.url) } >= minOf(2, pages.size)
        return if (hasList || pages.isEmpty()) r else r + "\n\n" + sourceList(pages)
    }

    /** Gemini की अपनी Google खोज वाला जवाब - साफ़ लिखो कि गहरी खोज नहीं हो पाई */
    const val SHALLOW_NOTE = "ध्यान दें: गहरी खोज (Exa / Tavily / Linkup / Firecrawl) नहीं हो पाई — यह जवाब Gemini की अपनी Google खोज से है। भरोसा: अधूरा"

    fun clean(t: String): String = t.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]*)\\]\\((https?://[^)]*)\\)"), "$1")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun jsonPart(raw: String): String {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        return if (a >= 0 && b > a) t.substring(a, b + 1) else t
    }
}
