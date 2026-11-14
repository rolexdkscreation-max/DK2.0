package com.dk.assistant.research

import com.dk.assistant.AiException
import com.dk.assistant.WebSearch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 14 नवंबर 2026: डीप रिसर्च का इंटरनेट वाला हिस्सा (Exa / Tavily / Linkup / Firecrawl / Gemini).
 * Key सिर्फ़ request header में - कभी log / संदेश में नहीं. Blocking - IO thread से.
 * गलती = AiException(code) -> runner उस टूल को इस रिसर्च में छोड़ देता है.
 */
object DeepResearchNet {
    private val http by lazy {
        com.dk.assistant.NetFix.builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json".toMediaType()

    private fun post(url: String, auth: Pair<String, String>, body: JSONObject): String =
        http.newCall(Request.Builder().url(url).header(auth.first, auth.second)
            .post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AiException(r.code, WebSearch.errorText(b).ifBlank { "HTTP ${r.code}" })
            b
        }

    private fun bearer(k: String) = "Authorization" to "Bearer $k"

    // ------------------------------------------------------------------ खोज
    fun exa(key: String, q: String, n: Int = 8): List<DeepResearch.Hit> = DeepResearch.parseExa(
        post("https://api.exa.ai/search", "x-api-key" to key, JSONObject().put("query", q).put("numResults", n).put("type", "auto")
            .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 600)))))

    fun tavily(key: String, q: String, news: Boolean, n: Int = 8): List<DeepResearch.Hit> = DeepResearch.parseTavily(
        post("https://api.tavily.com/search", bearer(key), JSONObject().put("query", q).put("max_results", n)
            .put("search_depth", "basic").put("topic", if (news) "news" else "general")))

    fun linkup(key: String, q: String, n: Int = 8): List<DeepResearch.Hit> = DeepResearch.parseLinkup(
        post("https://api.linkup.so/v1/search", bearer(key), JSONObject().put("q", q).put("depth", "standard")
            .put("outputType", "searchResults").put("maxResults", n)))

    // ------------------------------------------------------------------ पूरा पेज
    fun firecrawl(key: String, url: String): Triple<String, String, String>? = DeepResearch.parseFirecrawl(
        post("https://api.firecrawl.dev/v2/scrape", bearer(key), JSONObject().put("url", url)
            .put("formats", JSONArray().put("markdown")).put("onlyMainContent", true)))

    fun tavilyExtract(key: String, urls: List<String>): Map<String, String> = DeepResearch.parseTavilyExtract(
        post("https://api.tavily.com/extract", bearer(key), JSONObject().put("urls", JSONArray(urls))))

    fun exaContents(key: String, urls: List<String>): Map<String, String> = DeepResearch.parseExaContents(
        post("https://api.exa.ai/contents", "x-api-key" to key, JSONObject().put("urls", JSONArray(urls))
            .put("text", JSONObject().put("maxCharacters", DeepResearch.PAGE_CHARS * 2))))

    // ------------------------------------------------------------------ Gemini (लंबा जवाब, सादा text)
    fun gemini(key: String, model: String, prompt: String, maxTokens: Int, json: Boolean = false): String {
        val gen = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", 0.3)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", gen)
        val raw = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", "x-goog-api-key" to key, body)
        val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val t = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.filter { !it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }.trim()
        if (t.isEmpty()) throw AiException(0, "khali jawab")
        return t
    }

    // ------------------------------------------------------------------ Settings का "जाँचें" (क्रेडिट नहीं कटता)
    /** Linkup: बचे क्रेडिट / Firecrawl: टीम के क्रेडिट - key सही? */
    fun check(tool: DeepResearch.Tool, key: String): String {
        val url = when (tool) {
            DeepResearch.Tool.LINKUP -> "https://api.linkup.so/v1/credits/balance"
            DeepResearch.Tool.FIRECRAWL -> "https://api.firecrawl.dev/v2/team/credit-usage"
            else -> return "—"
        }
        return try {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()).execute().use { r ->
                DeepResearch.checkMessage(tool, r.code)
            }
        } catch (e: Exception) { DeepResearch.checkMessage(tool, 0) }
    }
}
