package com.dk.assistant

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v3.90: बिना key वाली दूसरी खोज (बचाव) - जब Gemini की Google खोज न चले (मुफ़्त सीमा भरी / key ग़लत / धीमा नेट).
 * पहले ऐसे में DK सीधे "माफ़ करिए, जानकारी नहीं मिल पाई" बोल देती थी.
 *  - Google News (RSS) - ताज़ा ख़बरें, सरकारी योजना/नियम के अपडेट, तारीख़ और अख़बार के नाम के साथ
 *  - Wikipedia (हिंदी + English) - आम जानकारी (जगह, व्यक्ति, चीज़ें)
 * नतीजा Live मॉडल को जाता है, वही बोलकर जवाब बनाता है. parse वाले हिस्से pure (JVM test), HTTP [fetch] में.
 */
object OpenSearch {
    data class Hit(val title: String, val site: String, val url: String, val date: String, val text: String)

    private fun enc(q: String) = URLEncoder.encode(q.trim().take(200), "UTF-8")

    fun newsUrl(q: String) = "https://news.google.com/rss/search?q=${enc(q)}&hl=hi&gl=IN&ceid=IN:hi"
    fun wikiUrl(q: String, lang: String) = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=${enc(q)}" +
        "&gsrlimit=2&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&exsentences=4&format=json&formatversion=2"

    private fun unxml(s: String) = s.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private fun tag(x: String, t: String) = Regex("<$t[^>]*>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1)?.let { unxml(it) }.orEmpty()
    private val RFC = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    private val OUT = SimpleDateFormat("d MMM yyyy", Locale.US)

    /** Google News RSS -> सबसे नई ख़बरें पहले (ज़्यादा से ज़्यादा [max]) */
    fun parseNews(xml: String, max: Int = 8): List<Hit> {
        val items = ITEM.findAll(xml).map { m ->
            val x = m.groupValues[1]
            val src = tag(x, "source")
            val srcUrl = Regex("<source url=\"([^\"]+)\"").find(x)?.groupValues?.get(1).orEmpty()
            var title = tag(x, "title")
            if (src.isNotBlank() && title.endsWith(" - $src")) title = title.removeSuffix(" - $src")
            val at = try { synchronized(RFC) { RFC.parse(tag(x, "pubDate"))?.time } } catch (_: Exception) { null } ?: 0L
            Triple(Hit(title, src.ifBlank { Grounding.host(srcUrl) }, srcUrl.ifBlank { tag(x, "link") },
                if (at > 0) synchronized(OUT) { OUT.format(java.util.Date(at)) } else "", ""), at, title)
        }.filter { it.third.isNotBlank() }.toList()
        return items.sortedByDescending { it.second }.map { it.first }.distinctBy { it.title.lowercase() }.take(max)
    }

    /** Wikipedia API (formatversion=2) -> सार */
    fun parseWiki(json: String, lang: String): List<Hit> = try {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: org.json.JSONArray()
        (0 until pages.length()).mapNotNull { i ->
            val p = pages.optJSONObject(i) ?: return@mapNotNull null
            val ex = p.optString("extract").trim()
            if (ex.length < 40) null else Hit(p.optString("title"), "$lang.wikipedia.org", p.optString("fullurl"), "", ex.take(700)) to p.optInt("index", 99)
        }.sortedBy { it.second }.map { it.first }
    } catch (_: Exception) { emptyList() }

    /** Live मॉडल को देने लायक: हर बात के साथ वेबसाइट और तारीख़ */
    fun compose(news: List<Hit>, wiki: List<Hit>): String = buildString {
        if (news.isNotEmpty()) {
            append("LATEST NEWS HEADLINES (newest first):\n")
            news.forEach { append("- ").append(it.title).append(" (").append(it.site.ifBlank { "news" }).append(if (it.date.isNotBlank()) ", ${it.date}" else "").append(")\n") }
        }
        if (wiki.isNotEmpty()) {
            append("REFERENCE (Wikipedia):\n")
            wiki.forEach { append("- ").append(it.title).append(": ").append(it.text.replace('\n', ' ')).append(" (").append(it.site).append(")\n") }
        }
    }.trim()

    fun sources(news: List<Hit>, wiki: List<Hit>): List<Pair<String, String>> =
        (news.take(5) + wiki.take(2)).filter { it.url.isNotBlank() }.map { it.site.ifBlank { it.title } to it.url }
}
