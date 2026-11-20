package com.dk.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** API key se us company ke saare models ki list laata hai. */
object ModelLister {
    private val http = com.dk.assistant.NetFix.builder().callTimeout(20, TimeUnit.SECONDS).build()

    /** नवंबर 2026: कंपनी की API का दिया साफ़ नाम (Gemini displayName / OpenRouter name) - id -> नाम. Settings में मॉडल चुनते समय दिखता है */
    val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val SKIP = listOf("embedding", "embed", "tts", "whisper", "audio", "image", "imagen", "veo", "moderation",
        "dall-e", "transcribe", "realtime", "native-audio", "live", "aqa", "guard", "rerank", "ocr", "search-preview",
        // 14 नवंबर 2026: Groq की सूची में आवाज़ / जाँच वाले मॉडल (चैट नहीं लेते = "मॉडल ने सवाल नहीं लिया")
        "orpheus", "playai", "distil", "safeguard", "compound")

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

    /**
     * 20 नवंबर 2026 (मालिक: चिप की लिस्ट अपने-आप): कंपनी की पूरी लाइव सूची, हर मॉडल की जानकारी के साथ
     * (बनने की तारीख, आवाज़ वाला?, बंद होने वाला?). छाँटना [com.dk.assistant.dkai.ModelCatalog] करता है.
     * Gemini: generateContent (टेक्स्ट) + bidiGenerateContent (Live/आवाज़). बाकी: OpenAI-जैसा /models.
     */
    suspend fun catalog(p: Provider, key: String, baseUrl: String): List<com.dk.assistant.dkai.ModelCatalog.Meta> = withContext(Dispatchers.IO) {
        if (p.isGemini) geminiMeta(key)
        else try { openAiMeta(baseUrl.trimEnd('/').ifBlank { p.baseUrl.trimEnd('/') }, key) } catch (e: AiException) {
            if (e.code == 404 || e.code == 405) Providers.fallbackModels(p.id).map { com.dk.assistant.dkai.ModelCatalog.Meta(it) }.ifEmpty { throw e } else throw e
        }
    }

    private fun geminiMeta(key: String): List<com.dk.assistant.dkai.ModelCatalog.Meta> {
        val out = mutableListOf<com.dk.assistant.dkai.ModelCatalog.Meta>()
        var token = ""
        repeat(5) {
            val o = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                (if (token.isNotBlank()) "&pageToken=$token" else ""), mapOf("x-goog-api-key" to key))
            val arr = o.optJSONArray("models") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                val voice = methods.contains("bidiGenerateContent")
                if (!methods.contains("generateContent") && !voice) continue
                val id = m.optString("name").removePrefix("models/")
                val name = m.optString("displayName")
                if (name.isNotBlank()) names[id] = name
                val dep = m.optString("description").contains("deprecat", ignoreCase = true) || name.contains("deprecat", ignoreCase = true)
                out.add(com.dk.assistant.dkai.ModelCatalog.Meta(id, 0L, voice = voice && !methods.contains("\"generateContent\""), deprecated = dep, name = name))
            }
            token = o.optString("nextPageToken")
            if (token.isBlank()) return out
        }
        return out
    }

    private fun openAiMeta(base: String, key: String): List<com.dk.assistant.dkai.ModelCatalog.Meta> {
        val anthropic = base.contains("anthropic.com")
        val o = if (anthropic) get("https://api.anthropic.com/v1/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
            else get("$base/models", mapOf("Authorization" to "Bearer $key"))
        val arr = o.optJSONArray("data") ?: o.optJSONArray("models") ?: org.json.JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
            val name = m.optString("name").ifBlank { m.optString("display_name") }
            if (name.isNotBlank()) names[id] = name
            // OpenAI-जैसे: created (सेकंड); Anthropic: created_at (ISO). Groq: active=false = बंद; OpenRouter: expiration_date = बंद होने वाला
            val created = m.optLong("created", 0L).takeIf { it > 0 }
                ?: m.optString("created_at").takeIf { it.length >= 10 }?.let { iso -> runCatching { java.time.Instant.parse(iso).epochSecond }.getOrNull() } ?: 0L
            val dep = (m.has("active") && !m.optBoolean("active", true)) ||
                (m.has("expiration_date") && !m.isNull("expiration_date") && m.optString("expiration_date").isNotBlank()) ||
                m.optBoolean("deprecated", false)
            com.dk.assistant.dkai.ModelCatalog.Meta(id, created, deprecated = dep, name = name)
        }
    }

    /** OpenRouter key फ्री tier की है? (GET /api/v1/key -> data.is_free_tier) */
    fun openRouterKeyInfo(key: String): String {
        val b = Request.Builder().url("https://openrouter.ai/api/v1/key").header("Authorization", "Bearer $key")
        http.newCall(b.build()).execute().use { r -> return if (r.isSuccessful) r.body?.string().orEmpty() else "" }
    }

    /** Gemini key paid है? - paid मॉडल पर 1 token की छोटी कोशिश (दिन में एक बार). (code, body) */
    fun geminiProbe(key: String, model: String): Pair<Int, String> {
        val body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("role", "user")
            .put("parts", org.json.JSONArray().put(JSONObject().put("text", "hi")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r -> return r.code to (r.body?.string().orEmpty().take(2000)) }
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
