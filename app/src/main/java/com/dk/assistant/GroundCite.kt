package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * 13 नवंबर 2026 (मालिक): चैट के जवाब में "स्रोत" - Gemini googleSearch का groundingMetadata.
 *
 * - groundingChunks[].web {uri, title} = स्रोत
 * - groundingSupports[].segment.endIndex (UTF-8 **बाइट** में, अक्षर में नहीं!) + groundingChunkIndices = किस वाक्य का कौन-सा स्रोत
 * - webSearchQueries, searchEntryPoint.renderedContent (Google की शर्त: जवाब के नीचे दिखाना)
 *
 * metadata खाली / कोई web chunk नहीं = null (कोई चिप, कोई सूची नहीं; अपने से बनाया लिंक कभी नहीं).
 * यह pure हिस्सा है (Android नहीं) - JUnit से जाँचा जाता है. दिखाने वाला हिस्सा [GroundView].
 */
object GroundCite {
    /** एक वाक्य/बुलेट जिसे स्रोत मिला: [anchor] = उसका text (साफ़ किए जवाब में जगह ढूँढने को), [end] = कच्चे जवाब में अक्षर-जगह (-1 = पता नहीं) */
    data class Cite(val anchor: String, val end: Int, val chunks: List<Int>)

    data class G(
        val chunks: List<Pair<String, String>>,     // (title, uri) - groundingChunks के क्रम में (index वही)
        val cites: List<Cite>,
        val queries: List<String> = emptyList(),
        val entryHtml: String = "",
        val raw: String = "",
        /** DK लाइव (आवाज़): लिखा रूप बोले गए से बना - जगह न मिले तो चिप नीचे एक कतार में */
        val live: Boolean = false,
    )

    // ---------------------------------------------------------------- UTF-8 बाइट <-> अक्षर (TextEncoder / TextDecoder जैसा)

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** बाइट-जगह को अक्षर की सीमा पर लाओ (बीच में कटे अक्षर से पीछे) */
    private fun boundary(b: ByteArray, i: Int): Int {
        var k = i.coerceIn(0, b.size)
        while (k > 0 && k < b.size && (b[k].toInt() and 0xC0) == 0x80) k--
        return k
    }

    /** UTF-8 बाइट [byteIdx] = text में कौन-सा अक्षर-index (Kotlin String = UTF-16) */
    fun charIndex(text: String, byteIdx: Int): Int {
        val b = utf8(text)
        return String(b, 0, boundary(b, byteIdx), Charsets.UTF_8).length
    }

    /** बाइट [start, end) का text (TextDecoder से वापस जोड़ना) */
    fun sliceBytes(text: String, start: Int, end: Int): String {
        val b = utf8(text)
        val s = boundary(b, start); val e = boundary(b, end)
        return if (e <= s) "" else String(b, s, e - s, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- पढ़ना

    /** groundingMetadata पढ़ो. [raw] = वही जवाब जिस पर Gemini ने बाइट गिने (Live में "") */
    fun parse(meta: JSONObject?, raw: String, live: Boolean = false): G? {
        val arr = meta?.optJSONArray("groundingChunks") ?: return null
        val chunks = (0 until arr.length()).map { i ->
            val w = arr.optJSONObject(i)?.optJSONObject("web")
            val uri = w?.optString("uri")?.trim().orEmpty()
            if (!uri.startsWith("http")) "" to ""
            else w!!.optString("title").trim().ifBlank { w.optString("domain").trim() }.ifBlank { Grounding.host(uri) } to uri
        }
        if (chunks.none { it.second.isNotEmpty() }) return null
        val cites = ArrayList<Cite>()
        meta.optJSONArray("groundingSupports")?.let { sup ->
            for (i in 0 until sup.length()) {
                val s = sup.optJSONObject(i) ?: continue
                val idx = s.optJSONArray("groundingChunkIndices")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }.orEmpty()
                    .filter { it in chunks.indices && chunks[it].second.isNotEmpty() }.distinct()
                if (idx.isEmpty()) continue
                val seg = s.optJSONObject("segment") ?: continue
                val endB = seg.optInt("endIndex", -1)
                val startB = seg.optInt("startIndex", 0)
                val (anchor, end) = if (raw.isNotEmpty() && endB > 0 && endB <= utf8(raw).size)
                    sliceBytes(raw, startB, endB).ifBlank { seg.optString("text") } to charIndex(raw, endB)
                else seg.optString("text") to -1
                if (anchor.isBlank() && end < 0) continue
                cites.add(Cite(anchor, end, idx))
            }
        }
        val q = meta.optJSONArray("webSearchQueries")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val html = meta.optJSONObject("searchEntryPoint")?.optString("renderedContent").orEmpty()
        return G(chunks, cites, q, html, raw, live)
    }

    /** Live में metadata टुकड़ों में आता है - जोड़ो (दूसरे के index आगे खिसकाकर) */
    fun merge(a: G?, b: G?): G? {
        if (a == null) return b
        if (b == null) return a
        val off = a.chunks.size
        return G(a.chunks + b.chunks, a.cites + b.cites.map { it.copy(chunks = it.chunks.map { i -> i + off }, end = -1) },
            (a.queries + b.queries).distinct(), b.entryHtml.ifBlank { a.entryHtml }, "", a.live || b.live)
    }

    /** नीचे की "स्रोत" सूची: हर स्रोत एक बार (वही link / वही शीर्षक दोबारा नहीं) */
    fun unique(g: G?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        g?.chunks?.forEach { s ->
            if (s.second.isEmpty()) return@forEach
            if (out.none { it.second == s.second || (it.first.isNotBlank() && it.first.equals(s.first, ignoreCase = true)) }) out.add(s)
        }
        return out
    }

    // ---------------------------------------------------------------- चिप पर नाम

    private val SUFFIX = setOf("com", "org", "net", "co", "in", "gov", "nic", "edu", "ac", "io", "ai", "info", "app", "dev", "uk", "us",
        "me", "tv", "biz", "int", "mil", "au", "ca", "de", "fr", "jp", "cn", "ru", "br", "eu", "res", "ind", "news", "blog", "site", "online", "xyz")

    /** वेबसाइट का सीधा नाम: github, huggingface, wikipedia (www. / .com हटाकर) */
    fun shortName(title: String, url: String): String {
        val d = Grounding.site(title, url).trim().lowercase()
        if (d.isBlank()) return ""
        if (!Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(d)) return Grounding.label(title.ifBlank { d }, 18)
        var parts = d.removePrefix("www.").removePrefix("m.").split('.').filter { it.isNotBlank() }
        while (parts.size > 1 && parts.last() in SUFFIX) parts = parts.dropLast(1)
        return parts.lastOrNull().orEmpty()
    }

    // ---------------------------------------------------------------- जगह: किस अक्षर के बाद कौन-से चिप

    private fun plain(s: String) = s.replace(Regex("[*#`]"), "").replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "")

    /**
     * दिखने वाले text में चिप की जगहें (बढ़ते क्रम में) - हर जगह पर उस वाक्य के स्रोत (index, बिना दोहराव).
     * text वही कच्चा जवाब = बाइट से निकली सीधी जगह; साफ़ किया (markdown/[1] हटे) = वाक्य का आख़िरी हिस्सा ढूँढकर.
     */
    fun points(text: String, g: G?): List<Pair<Int, List<Int>>> {
        if (g == null || g.cites.isEmpty() || text.isBlank()) return emptyList()
        val same = g.raw.isNotEmpty() && text == g.raw
        val at = java.util.TreeMap<Int, LinkedHashSet<Int>>()
        var from = 0
        for (c in (if (same) g.cites.sortedBy { it.end } else g.cites)) {
            var pos = -1
            if (same && c.end in 1..text.length) pos = c.end
            else {
                val a = plain(c.anchor).trim()
                if (a.isNotEmpty()) for (n in listOf(32, 16)) {
                    val tail = a.takeLast(n).trim()
                    if (tail.length < 3) continue
                    var i = text.indexOf(tail, from)
                    if (i < 0) i = text.indexOf(tail)
                    if (i >= 0) { pos = i + tail.length; break }
                }
            }
            if (pos <= 0) continue
            // शब्द के बीच नहीं; वाक्य का चिह्न (। . ! ?) साथ रहे
            while (pos < text.length && (text[pos].isLetterOrDigit() || mark(text[pos]) || text[pos] in "।.!?)\"”")) pos++
            from = pos
            at.getOrPut(pos) { LinkedHashSet() }.addAll(c.chunks)
        }
        return at.entries.map { it.key to it.value.toList() }
    }

    private fun mark(ch: Char): Boolean = Character.getType(ch).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

    /** एक जगह के चिप: (नाम, link) - एक ही वेबसाइट दो बार नहीं */
    fun chips(g: G, idx: List<Int>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (i in idx) {
            val (t, u) = g.chunks.getOrNull(i) ?: continue
            if (u.isEmpty()) continue
            val n = shortName(t, u)
            if (n.isBlank()) continue
            if (out.none { it.first == n }) out.add(n to u)
        }
        return out
    }

    /** DK लाइव: जगह न मिले तो सारे स्रोत एक कतार में (हर वेबसाइट एक बार) */
    fun allChips(g: G?): List<Pair<String, String>> = if (g == null) emptyList() else chips(g, g.chunks.indices.toList())

    /** Gemini ने googleSearch tool मना किया (मॉडल न माने / खोज की limit) - तब बिना tool वही सवाल, ताकि जवाब न रुके */
    fun toolRejected(code: Int, msg: String): Boolean =
        (code == 400 || code == 403 || code == 429) && Regex("tool|google.?search|grounding", RegexOption.IGNORE_CASE).containsMatchIn(msg)

    // ---------------------------------------------------------------- सहेजना (चैट फ़ाइल)

    fun toJson(g: G): JSONObject = JSONObject()
        .put("c", JSONArray().apply { g.chunks.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("s", JSONArray().apply { g.cites.forEach { put(JSONObject().put("a", it.anchor).put("e", it.end).put("i", JSONArray(it.chunks))) } })
        .put("q", JSONArray(g.queries)).put("h", g.entryHtml).put("r", g.raw).put("l", g.live)

    fun fromJson(o: JSONObject?): G? {
        if (o == null) return null
        return try {
            val c = o.optJSONArray("c") ?: return null
            val chunks = (0 until c.length()).map { i -> c.optJSONArray(i).let { (it?.optString(0).orEmpty()) to (it?.optString(1).orEmpty()) } }
            val s = o.optJSONArray("s") ?: JSONArray()
            val cites = (0 until s.length()).mapNotNull { i -> s.optJSONObject(i)?.let { x ->
                val a = x.optJSONArray("i") ?: JSONArray()
                Cite(x.optString("a"), x.optInt("e", -1), (0 until a.length()).map { a.optInt(it) })
            } }
            val q = o.optJSONArray("q") ?: JSONArray()
            G(chunks, cites, (0 until q.length()).map { q.optString(it) }, o.optString("h"), o.optString("r"), o.optBoolean("l"))
                .takeIf { it.chunks.any { ch -> ch.second.isNotEmpty() } }
        } catch (_: Exception) { null }
    }
}
