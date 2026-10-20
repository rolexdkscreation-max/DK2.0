package com.dk.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** API key se us company ke saare models ki list laata hai. */
object ModelLister {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private val SKIP = listOf("embedding", "embed", "tts", "whisper", "audio", "image", "imagen", "veo", "moderation",
        "dall-e", "transcribe", "realtime", "native-audio", "live", "aqa", "guard", "rerank", "ocr", "search-preview")

    suspend fun fetch(p: Provider, key: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val list = if (p.isGemini) gemini(key) else openAi(baseUrl.trimEnd('/'), key)
        val clean = list.filter { m -> SKIP.none { m.lowercase().contains(it) } }.distinct()
        val sorted = when (p.id) {
            // OpenRouter: sirf FREE models (paise na kate)
            "openrouter" -> listOf("openrouter/free") + clean.filter { it.endsWith(":free") }.sorted()
            "gemini" -> clean.sortedWith(compareByDescending<String> { it.contains("flash") }.thenByDescending { it })
            else -> clean.sorted()
        }
        sorted.ifEmpty { list }
    }

    private fun get(url: String, headers: Map<String, String>): JSONObject {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = try { JSONObject(s).optJSONObject("error")?.optString("message") ?: s.take(150) } catch (e: Exception) { s.take(150) }
                throw AiException(r.code, msg)
            }
            return JSONObject(s)
        }
    }

    private fun gemini(key: String): List<String> {
        val out = mutableListOf<String>()
        var token = ""
        repeat(5) {
            val o = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                (if (token.isNotBlank()) "&pageToken=$token" else ""), mapOf("x-goog-api-key" to key))
            val arr = o.optJSONArray("models")
            for (i in 0 until (arr?.length() ?: 0)) {
                val m = arr!!.optJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent")) out.add(m.optString("name").removePrefix("models/"))
            }
            token = o.optString("nextPageToken")
            if (token.isBlank()) return out
        }
        return out
    }

    private fun openAi(base: String, key: String): List<String> {
        val o = get("$base/models", mapOf("Authorization" to "Bearer $key"))
        val arr = o.optJSONArray("data") ?: o.optJSONArray("models")
        return (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
    }
}
