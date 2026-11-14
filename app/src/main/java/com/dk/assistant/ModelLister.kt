package com.dk.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** API key se us company ke saare models ki list laata hai. */
object ModelLister {
    private val http = com.dk.assistant.NetFix.builder().callTimeout(20, TimeUnit.SECONDS).build()

    /** नवंबर 2026: कंपनी की API का दिया साफ़ नाम (Gemini displayName / OpenRouter name) - id -> नाम. Settings में मॉडल चुनते समय दिखता है */
    val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val SKIP = listOf("embedding", "embed", "tts", "whisper", "audio", "image", "imagen", "veo", "moderation",
        "dall-e", "transcribe", "realtime", "native-audio", "live", "aqa", "guard", "rerank", "ocr", "search-preview")

    suspend fun fetch(p: Provider, key: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        // नवंबर 2026: Z.ai की /models सूची न खुले (404) = उसके जाने-माने फ्री मॉडल. गलत key (401/403) = वही गलती
        val list = if (p.isGemini) gemini(key) else try { openAi(baseUrl.trimEnd('/'), key) } catch (e: AiException) {
            if (e.code == 404 || e.code == 405) Providers.fallbackModels(p.id).ifEmpty { throw e } else throw e
        }
        val clean = list.filter { m -> SKIP.none { m.lowercase().contains(it) } }.distinct()
        val sorted = when (p.id) {
            // OpenRouter: sirf FREE models (paise na kate)
            "openrouter" -> listOf("openrouter/free") + clean.filter { it.endsWith(":free") }.sorted()
            "gemini" -> clean.sortedWith(compareByDescending<String> { it.contains("flash") }.thenByDescending { it })
            // Z.ai / NVIDIA: तेज़ फ्री मॉडल सबसे ऊपर
            "zai", "nvidia" -> (Providers.fallbackModels(p.id).filter { it in clean } + clean.sorted()).distinct()
            else -> clean.sorted()
        }
        sorted.ifEmpty { list }.ifEmpty { Providers.fallbackModels(p.id) }
    }

    /** 14 नवंबर 2026 (मालिक): AI दिमाग → "हर काम का मॉडल" में DK लाइव के लिए - इस key के सारे Live (bidiGenerateContent) मॉडल, नए पहले */
    suspend fun liveModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val o = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to key))
        val arr = o.optJSONArray("models")
        val out = mutableListOf<String>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val m = arr!!.optJSONObject(i) ?: continue
            if (m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("bidiGenerateContent") != true) continue
            val id = m.optString("name").removePrefix("models/")
            out.add(id); m.optString("displayName").takeIf { it.isNotBlank() }?.let { names[id] = it }
        }
        out.distinct().sortedDescending()
    }

    /** Bina chhante poori list (key sahi hai ya nahi + whisper hai ya nahi) */
    suspend fun fetchRaw(p: Provider, key: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        if (p.isGemini) gemini(key) else openAi(baseUrl.trimEnd('/'), key)
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
                if (methods.contains("generateContent")) {
                    val id = m.optString("name").removePrefix("models/")
                    out.add(id)
                    m.optString("displayName").takeIf { it.isNotBlank() }?.let { names[id] = it }
                }
            }
            token = o.optString("nextPageToken")
            if (token.isBlank()) return out
        }
        return out
    }

    private fun openAi(base: String, key: String): List<String> {
        // Claude: मॉडल की सूची x-api-key से (chat OpenAI-जैसे पते पर Bearer से चलती है)
        if (base.contains("anthropic.com")) {
            val o = get("https://api.anthropic.com/v1/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
            val arr = o.optJSONArray("data")
            return (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val m = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                m.optString("display_name").takeIf { it.isNotBlank() }?.let { names[id] = it }
                id
            }
        }
        val o = get("$base/models", mapOf("Authorization" to "Bearer $key"))
        val arr = o.optJSONArray("data") ?: o.optJSONArray("models")
        return (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val m = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
            (m.optString("name").ifBlank { m.optString("display_name") }).takeIf { it.isNotBlank() }?.let { names[id] = it }
            id
        }
    }
}
