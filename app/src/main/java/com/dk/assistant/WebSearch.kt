package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * v3.93: इंटरनेट खोज का बैकअप क्रम (Fallback) - Tavily (मुख्य) -> Exa (दूसरा) -> Gemini + Google Search (आख़िरी).
 *
 * - जिस सेवा की key खाली हो = छोड़ो, अगली पर.
 * - जो सेवा फेल हो (key गलत / limit / उसकी तरफ़ की गड़बड़ी) = 1 घंटे "अभी बंद", फिर दोबारा आज़माओ.
 *   फ़ोन का इंटरनेट बंद / धीमा हो तो सेवा को बंद नहीं मानते (उसमें सेवा की ग़लती नहीं).
 * - एक ही सवाल का नतीजा 10 मिनट याद (cache) - बार-बार खोज नहीं. एक सवाल = ज़्यादा से ज़्यादा 1 सफल खोज, 6 नतीजे.
 * - Exa से सिर्फ़ नतीजों का सार (summary) - पूरे पेज की सामग्री (text) कभी नहीं माँगते.
 * - कौन-सा स्रोत किस सेवा से आया, वह याद ([tag]/[viaOf]) - जवाब के नीचे छोटे अक्षरों में दिखाने के लिए.
 *
 * Keys यहाँ कभी नहीं आतीं/लिखी जातीं - सिर्फ़ [WebSearchNet] की request header में. Android import नहीं -> JVM test.
 */
object WebSearch {
    enum class Provider(val label: String) { TAVILY("Tavily"), EXA("Exa"), GEMINI("Gemini") }

    const val COOLDOWN_MS = 60 * 60 * 1000L
    const val CACHE_MS = 10 * 60 * 1000L
    /** हर खोज में नतीजे - 5 से 8 के बीच */
    const val RESULTS = 6

    const val TAVILY_KEY_URL = "https://tavily.com"
    const val EXA_KEY_URL = "https://exa.ai"

    data class Hit(val title: String, val url: String, val text: String, val date: String = "")
    data class Found(val answer: String, val hits: List<Hit>)

    // ---------- क्रम + 1 घंटे की रोक ----------
    private val downUntil = ConcurrentHashMap<Provider, Long>()

    /** किस क्रम में आज़माना है: key वाली + अभी-बंद नहीं. */
    fun order(tavily: Boolean, exa: Boolean, gemini: Boolean, now: Long = System.currentTimeMillis()): List<Provider> =
        listOfNotNull(Provider.TAVILY.takeIf { tavily }, Provider.EXA.takeIf { exa }, Provider.GEMINI.takeIf { gemini })
            .filter { !isDown(it, now) }

    fun isDown(p: Provider, now: Long = System.currentTimeMillis()): Boolean = (downUntil[p] ?: 0L) > now
    private val downWhy = ConcurrentHashMap<Provider, SearchGuard.Fail>()
    fun markDown(p: Provider, f: SearchGuard.Fail = SearchGuard.Fail.OTHER, now: Long = System.currentTimeMillis()) {
        downUntil[p] = now + COOLDOWN_MS; downWhy[p] = f
    }
    fun clearDown(p: Provider) { downUntil.remove(p); downWhy.remove(p) }
    fun resetAll() { downUntil.clear(); downWhy.clear(); cache.clear(); via.clear() }
    /** सब सेवाएँ "अभी बंद" हों तो असली वजह (key नहीं डली नहीं कहना) */
    fun downReason(now: Long = System.currentTimeMillis()): SearchGuard.Fail? =
        downUntil.entries.filter { it.value > now }.firstNotNullOfOrNull { downWhy[it.key] }

    /**
     * यह गड़बड़ी सेवा की है (1 घंटे बंद करो)? फ़ोन का इंटरनेट/समय-सीमा = नहीं.
     * Gemini (कई key + कई मॉडल, अपना बचाव पहले से) = सिर्फ़ key/limit वाली गड़बड़ी पर.
     */
    fun coolsDown(p: Provider, f: SearchGuard.Fail): Boolean = when {
        f == SearchGuard.Fail.NETWORK || f == SearchGuard.Fail.TIMEOUT || f == SearchGuard.Fail.EMPTY -> false
        p == Provider.GEMINI -> f == SearchGuard.Fail.BAD_KEY || f == SearchGuard.Fail.QUOTA || f == SearchGuard.Fail.NO_KEY
        else -> true
    }

    // ---------- 10 मिनट का cache ----------
    private data class Cached(val result: SearchResult, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun norm(q: String): String = q.lowercase().replace(Regex("[\\p{Punct}।?!,.]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun cached(q: String, now: Long = System.currentTimeMillis()): SearchResult? {
        val k = norm(q); val c = cache[k] ?: return null
        if (now - c.at > CACHE_MS) { cache.remove(k); return null }
        return c.result
    }

    fun put(q: String, r: SearchResult, now: Long = System.currentTimeMillis()) {
        if (r.text.isBlank()) return
        if (cache.size > 60) cache.entries.filter { now - it.value.at > CACHE_MS }.forEach { cache.remove(it.key) }
        cache[norm(q)] = Cached(r, now)
    }

    // ---------- कौन-सा स्रोत किस सेवा से ----------
    private val via = ConcurrentHashMap<String, Provider>()

    fun tag(sources: List<Pair<String, String>>, p: Provider) {
        if (via.size > 300) via.clear()
        sources.forEach { (_, u) -> if (u.isNotBlank()) via[u] = p }
    }

    /** स्रोतों के ऊपर छोटे अक्षरों वाला नाम: "Tavily" / "Exa" / "Gemini" - पता न हो (जैसे बिना-key बचाव खोज) = null */
    fun viaOf(sources: List<Pair<String, String>>): String? = sources.firstNotNullOfOrNull { via[it.second] }?.label

    // ---------- खोज ज़रूरी है? ----------
    /**
     * सामान्य ज्ञान / पढ़ाई / कहानी / गणित / बातचीत = खोज नहीं. आज की तारीख़, ख़बर, भाव, मौसम, स्कोर, ताज़ा जानकारी = खोज.
     * [SearchPolicy.needsSearch] वाले शब्द ही इस्तेमाल - अलग नियम नहीं बनाया.
     */
    fun skip(q: String): Boolean = SearchPolicy.timeless(q) && !SearchPolicy.fresh(q)

    // ---------- Tavily ----------
    fun tavilyBody(q: String, n: Int = RESULTS): JSONObject = JSONObject()
        .put("query", q.trim().take(380))
        .put("max_results", n.coerceIn(5, 8))
        .put("search_depth", "basic")          // 1 क्रेडिट (advanced = 2)
        .put("include_answer", "basic")
        .put("include_raw_content", false)
        .put("include_images", false)

    fun parseTavily(raw: String): Found {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("results") ?: JSONArray()
        val hits = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = r.optString("url"); if (url.isBlank()) return@mapNotNull null
            Hit(r.optString("title").trim(), url, r.optString("content").trim().take(700), r.optString("published_date").let { if (it == "null") "" else it })
        }
        return Found(o.optString("answer").let { if (it == "null") "" else it.trim() }, hits)
    }

    // ---------- Exa ----------
    /** सिर्फ़ सार (summary) - text/highlights नहीं माँगते (पूरा पेज = ज़्यादा क्रेडिट) */
    fun exaBody(q: String, n: Int = RESULTS): JSONObject = JSONObject()
        .put("query", q.trim().take(380))
        .put("numResults", n.coerceIn(5, 8))
        .put("type", "auto")
        .put("contents", JSONObject().put("summary", JSONObject().put("query", q.trim().take(200))))

    fun parseExa(raw: String): Found {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("results") ?: JSONArray()
        val hits = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = r.optString("url"); if (url.isBlank()) return@mapNotNull null
            Hit(r.optString("title").trim(), url, r.optString("summary").trim().take(700), r.optString("publishedDate").let { if (it == "null") "" else it.take(10) })
        }
        return Found("", hits)
    }

    fun sources(f: Found): List<Pair<String, String>> = f.hits.take(Grounding.MAX).map { (it.title.ifBlank { Grounding.host(it.url) }) to it.url }

    /** Live के web_search tool / चैट में मिलाने के लिए नतीजे - छोटे, बिना URL की भीड़ के */
    fun report(f: Found): String = buildString {
        if (f.answer.isNotBlank()) append("SHORT ANSWER: ").append(f.answer.take(600)).append("\n")
        f.hits.forEachIndexed { i, h ->
            append(i + 1).append(". ").append(h.title.ifBlank { Grounding.host(h.url) })
            append(" (").append(Grounding.host(h.url)); if (h.date.isNotBlank()) append(", ").append(h.date.take(10)); append(")")
            if (h.text.isNotBlank()) append(": ").append(h.text.replace(Regex("\\s+"), " ").take(500))
            append("\n")
        }
    }.trim()

    /** चैट में मिलाने वाला AI न चले तो - सीधा सबसे काम का हिस्सा */
    fun plainAnswer(f: Found): String =
        f.answer.ifBlank { f.hits.firstOrNull { it.text.isNotBlank() }?.text?.replace(Regex("\\s+"), " ")?.take(400).orEmpty() }

    // ---------- गड़बड़ी के कोड -> साफ़ वजह ----------
    /** HTTP code -> गड़बड़ी का प्रकार (हर सेवा के अपने कोड: Tavily 432/433 = limit, Exa 402 = क्रेडिट खत्म) */
    fun failOf(p: Provider, code: Int, msg: String?): SearchGuard.Fail = when {
        p == Provider.TAVILY && (code == 432 || code == 433) -> SearchGuard.Fail.QUOTA
        p == Provider.EXA && code == 402 -> SearchGuard.Fail.QUOTA
        p != Provider.GEMINI && (code == 401 || code == 403) -> SearchGuard.Fail.BAD_KEY
        else -> SearchGuard.classify(code, msg)
    }

    /** "जाँचें" बटन का संदेश - असली वजह + कोड. हर गड़बड़ी को "limit" नहीं कहते. */
    fun checkMessage(p: Provider, code: Int, msg: String? = null): String {
        if (code in 200..299) return "✓ चालू है — ${p.label} से खोज तैयार"
        val c = if (code > 0) " (code $code)" else ""
        return when (if (code <= 0) netKind(msg) else failOf(p, code, msg)) {
            SearchGuard.Fail.BAD_KEY, SearchGuard.Fail.NO_KEY -> "✗ Key गलत है$c — ${p.label} की सही key डालें"
            SearchGuard.Fail.QUOTA -> if (code == 429 && p == Provider.TAVILY) "✗ limit खत्म है$c — बहुत ज़्यादा request, थोड़ी देर बाद" else "✗ limit खत्म है$c"
            SearchGuard.Fail.NETWORK, SearchGuard.Fail.TIMEOUT -> "✗ इंटरनेट की दिक्कत है — ${p.label} तक पहुँच नहीं हो पाई"
            else -> "✗ ${p.label} में गड़बड़ी$c" + (msg?.takeIf { it.isNotBlank() }?.let { " — " + it.replace(Regex("\\s+"), " ").take(90) } ?: "")
        }
    }

    private fun netKind(msg: String?): SearchGuard.Fail {
        val m = msg.orEmpty().lowercase()
        return if (m.contains("timeout") || m.contains("timed out")) SearchGuard.Fail.TIMEOUT else SearchGuard.Fail.NETWORK
    }

    /** error body से छोटा संदेश (key कभी नहीं - body में key नहीं आती, फिर भी "tvly-"/"AIza" जैसे टुकड़े हटाओ) */
    fun errorText(raw: String): String {
        val m = try {
            val o = JSONObject(raw)
            o.optJSONObject("detail")?.optString("error")?.takeIf { it.isNotBlank() }
                ?: o.optString("detail").takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: raw
        } catch (_: Exception) { raw }
        return redact(m).replace(Regex("\\s+"), " ").take(160)
    }

    fun redact(s: String): String = s.replace(Regex("(tvly-|AIza)[A-Za-z0-9_\\-]{6,}"), "***")
        .replace(Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "***")

    /** Tavily /usage: plan/paygo भर गया = limit खत्म (बिना खोज-क्रेडिट के जाँच) */
    fun tavilyUsageFull(raw: String): Boolean = try {
        val o = JSONObject(raw)
        val k = o.optJSONObject("key"); val a = o.optJSONObject("account")
        fun full(u: Int, l: Int) = l > 0 && u >= l
        val keyFull = k != null && full(k.optInt("usage"), k.optInt("limit"))
        val planFull = a != null && full(a.optInt("plan_usage"), a.optInt("plan_limit")) &&
            (a.optInt("paygo_limit") <= 0 || a.optInt("paygo_usage") >= a.optInt("paygo_limit"))
        keyFull || planFull
    } catch (_: Exception) { false }

    // ---------- एक ही सवाल पर साथ-साथ दो खोज न हों (Live की पहले-से खोज + web_search) ----------
    private val inflight = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<SearchResult>>()

    suspend fun once(q: String, block: suspend () -> SearchResult): SearchResult {
        val k = norm(q)
        val mine = kotlinx.coroutines.CompletableDeferred<SearchResult>()
        val other = inflight.putIfAbsent(k, mine)
        if (other != null) {
            try { return other.await() } catch (e: kotlinx.coroutines.CancellationException) {
                // पहली खोज वाला रुक गया (जैसे Live का पहले-से खोज) - हम अभी चालू हैं तो ख़ुद खोजो
                if (!kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job].let { it == null || it.isActive }) throw e
                return block()
            }
        }
        return try { block().also { mine.complete(it) } }
        catch (e: Throwable) { mine.completeExceptionally(e); throw e }
        finally { inflight.remove(k, mine) }
    }

    /** तीनों न चलीं - DK अपनी जानकारी से जवाब दे, पर साफ़ बताए */
    const val ALL_FAILED_LINE = "अभी इंटरनेट खोज नहीं हो पाई"
}
