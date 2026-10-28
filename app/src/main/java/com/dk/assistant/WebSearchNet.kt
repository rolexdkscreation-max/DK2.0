package com.dk.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v3.93: [WebSearch] का इंटरनेट वाला हिस्सा (OkHttp) - Tavily / Exa की खोज और "जाँचें" बटन.
 * Key सिर्फ़ request header में जाती है - कभी log, संदेश या error में नहीं (error body भी [WebSearch.redact] से).
 * Blocking - IO thread से बुलाओ.
 */
object WebSearchNet {
    private val http by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json".toMediaType()

    private fun call(req: Request): String = http.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw AiException(r.code, WebSearch.errorText(body).ifBlank { "HTTP ${r.code}" })
        body
    }

    /** Tavily से खोज (1 क्रेडिट, 6 नतीजे + छोटा जवाब) */
    fun tavily(key: String, q: String): WebSearch.Found {
        val req = Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $key")
            .post(WebSearch.tavilyBody(q).toString().toRequestBody(JSON)).build()
        return WebSearch.parseTavily(call(req))
    }

    /** Exa से खोज - सिर्फ़ सार (summary), पूरा पेज नहीं */
    fun exa(key: String, q: String): WebSearch.Found {
        val req = Request.Builder().url("https://api.exa.ai/search")
            .header("x-api-key", key)
            .post(WebSearch.exaBody(q).toString().toRequestBody(JSON)).build()
        return WebSearch.parseExa(call(req))
    }

    // ---------------------------------------------------------------- "जाँचें" बटन
    private fun codeOf(req: Request): Pair<Int, String> = http.newCall(req).execute().use { r ->
        r.code to WebSearch.errorText(r.body?.string().orEmpty())
    }

    private fun safely(p: WebSearch.Provider, block: () -> String): String = try { block() }
        catch (e: AiException) { WebSearch.checkMessage(p, e.code, e.message) }
        catch (e: java.io.InterruptedIOException) { WebSearch.checkMessage(p, 0, "timeout") }
        catch (e: Exception) { WebSearch.checkMessage(p, 0, e.message) }

    /** Tavily: /usage (खोज-क्रेडिट नहीं कटता) - key सही? limit बची? */
    fun checkTavily(key: String): String = safely(WebSearch.Provider.TAVILY) {
        val req = Request.Builder().url("https://api.tavily.com/usage").header("Authorization", "Bearer $key").get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.isSuccessful && WebSearch.tavilyUsageFull(body) -> WebSearch.checkMessage(WebSearch.Provider.TAVILY, 432)
                else -> WebSearch.checkMessage(WebSearch.Provider.TAVILY, r.code, WebSearch.errorText(body))
            }
        }.also { if (it.startsWith("✓")) WebSearch.clearDown(WebSearch.Provider.TAVILY) }
    }

    /** Exa: 1 नतीजे वाली सबसे छोटी खोज, बिना सामग्री (सार भी नहीं) */
    fun checkExa(key: String): String = safely(WebSearch.Provider.EXA) {
        val body = org.json.JSONObject().put("query", "today news").put("numResults", 1).put("type", "auto")
        val (code, msg) = codeOf(Request.Builder().url("https://api.exa.ai/search").header("x-api-key", key)
            .post(body.toString().toRequestBody(JSON)).build())
        WebSearch.checkMessage(WebSearch.Provider.EXA, code, msg)
            .also { if (it.startsWith("✓")) WebSearch.clearDown(WebSearch.Provider.EXA) }
    }

    /** Gemini (खोज वाली key): models list - हल्की जाँच, असली code के साथ */
    fun checkGemini(key: String): String = safely(WebSearch.Provider.GEMINI) {
        val (code, msg) = codeOf(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=50")
            .header("x-goog-api-key", key).get().build())
        WebSearch.checkMessage(WebSearch.Provider.GEMINI, code, msg)
            .also { if (it.startsWith("✓")) WebSearch.clearDown(WebSearch.Provider.GEMINI) }
    }
}
