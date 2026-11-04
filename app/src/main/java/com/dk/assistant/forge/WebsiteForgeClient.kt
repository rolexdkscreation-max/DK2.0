package com.dk.assistant.forge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DK AI ब्रीफ़ Part 14: Gemini streamGenerateContent?alt=sse से वेबसाइट का HTML टुकड़ों में.
 * key सिर्फ़ header (x-goog-api-key) में; key/prompt/HTML कभी log नहीं.
 */
object WebsiteForgeClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    val http: OkHttpClient by lazy {
        com.dk.assistant.NetFix.builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class ForgeHttpException(val code: Int, val reason: String?) : IOException(SseParser.friendlyError(code, reason))

    /** v3.167: हर key की अपनी मॉडल सूची (वेबसाइट की key बदले तो पुरानी key की सूची नहीं) */
    private val cachedModels = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    /** इस process में 404/बंद मिले मॉडल - दोबारा नहीं आज़माते */
    private val gone = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * ListModels से वेबसाइट लिखने लायक मॉडल, अच्छे से बुरे क्रम में।
     * (जड़ - v3.59: पहले सूची का *पहला* "flash" चुनते थे = अक्सर gemini-2.x, जो नए users को 404
     * "no longer available" देता है, और दूसरा मॉडल आज़माते ही नहीं थे -> वेबसाइट कभी नहीं बनती थी।)
     */
    suspend fun resolveModels(apiKey: String): List<String> = cachedModels[apiKey] ?: withContext(Dispatchers.IO) {
        val listed = try {
            val req = Request.Builder().url("$BASE/models?pageSize=1000").header("x-goog-api-key", apiKey).get().build()
            await(http.newCall(req)).use { r ->
                val b = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    // key ही गलत/बंद = असली वजह दिखाओ; बाकी (भीड़ वगैरह) में जाने-पहचाने मॉडल आज़माओ
                    if (r.code == 400 || r.code == 401 || r.code == 403) throw ForgeHttpException(r.code, SseParser.extractError(b))
                    emptyList()
                } else rankModels(JSONObject(b).optJSONArray("models") ?: JSONArray())
            }
        } catch (e: ForgeHttpException) { throw e } catch (e: Exception) { emptyList() }
        val all = (listed + FALLBACKS).distinct()
        if (listed.isNotEmpty()) cachedModels[apiKey] = all
        all
    }

    /** ListModels न मिले तो (अक्टूबर 2026 के नए users वाले मॉडल) */
    val FALLBACKS = listOf(com.dk.assistant.GeminiModels.CODER, "gemini-3.5-flash-lite", "gemini-flash-latest")

    /** pure: मॉडल सूची में से पहला चुनाव (test होता है) */
    fun chooseModel(models: JSONArray): String? = rankModels(models).firstOrNull()

    /**
     * pure: generateContent वाले text मॉडल, क्रम: नए Flash (3.8 -> 3.5 ...) -> नए बाकी (pro) -> पुराने 2.x Flash -> पुराने बाकी.
     * live/image/tts/audio/embedding वाले नहीं।
     */
    fun rankModels(models: JSONArray): List<String> {
        val gen = (0 until models.length()).mapNotNull { models.optJSONObject(it) }.filter { o ->
            val ms = o.optJSONArray("supportedGenerationMethods") ?: return@filter false
            (0 until ms.length()).any { ms.optString(it) == "generateContent" }
        }.map { it.optString("name").removePrefix("models/") }.filter { n ->
            val l = n.lowercase()
            n.isNotBlank() && listOf("live", "image", "tts", "audio", "embedding", "aqa", "veo", "imagen", "robotics", "computer-use")
                .none { l.contains(it) }
        }.distinct()
        fun ver(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        fun rank(n: String): Int {
            val l = n.lowercase()
            val legacy = com.dk.assistant.GeminiModels.isLegacy(n)
            val flash = l.contains("flash")
            return (if (legacy) 2 else 0) + (if (flash) 0 else 1)
        }
        return gen.sortedWith(compareBy<String>({ rank(it) }, { -ver(it) }, { if (it.contains("preview") || it.contains("exp")) 1 else 0 },
            { if (it == com.dk.assistant.GeminiModels.CODER) 0 else 1 }))
    }

    /** इस गलती पर अगला मॉडल आज़माएँ? (बंद/न मिला/उस मॉडल की limit/भीड़) */
    fun tryNextModel(code: Int, reason: String?): Boolean {
        val r = reason.orEmpty()
        return com.dk.assistant.GeminiModels.isGone(code, r) || code == 429 || code == 500 || code == 503 ||
            (code == 400 && (r.contains("not supported", true) || r.contains("thinking", true) || r.contains("max_output", true) ||
                r.contains("maxOutputTokens", true) || r.contains("model", true)))
    }

    /** pure: request body (test होता है) */
    fun requestBody(model: String, prompt: String): JSONObject = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        put("generationConfig", JSONObject().apply {
            put("temperature", 0.9)
            put("maxOutputTokens", 65536)
            if (model.contains("2.5")) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        })
    }

    /**
     * HTML stream करो. हर नया टुकड़ा onText में. return = finishReason (null = पता नहीं).
     * coroutine cancel हुआ = HTTP call भी cancel.
     */
    suspend fun stream(apiKey: String, prompt: String, prefer: String = "", onText: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val models = withPreferred(rankForWebsite(resolveModels(apiKey)).filterNot { it in gone }.ifEmpty { resolveModels(apiKey) }, prefer)
        var lastErr: ForgeHttpException? = null
        for (model in models.take(4)) {
            var gotText = false
            try {
                return@withContext streamOnce(apiKey, model, prompt) { t -> gotText = true; onText(t) }
            } catch (e: ForgeHttpException) {
                // HTML आना शुरू हो चुका = मॉडल चल रहा है; बीच में बदलना नहीं
                if (gotText || !tryNextModel(e.code, e.reason)) throw e
                if (com.dk.assistant.GeminiModels.isGone(e.code, e.reason.orEmpty()) || e.code == 400) gone.add(model)
                com.dk.assistant.DKLog.d("Forge: मॉडल $model नहीं चला (${e.code}) - अगला मॉडल")
                lastErr = e
            }
        }
        throw lastErr ?: ForgeHttpException(0, "NO_MODEL")
    }

    /**
     * v3.167: वेबसाइट की अपनी keys (Key 1, फिर बैकअप Key 2) - दूसरी कोई key नहीं.
     * Key 1 गलत/बंद/limit खत्म हो और अभी HTML आना शुरू न हुआ हो = Key 2.
     */
    suspend fun streamKeys(keys: List<String>, prefer: String, prompt: String, onText: (String) -> Unit): String? {
        val list = keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        var lastErr: Exception? = null
        for ((i, k) in list.withIndex()) {
            var gotText = false
            try {
                return stream(k, prompt, prefer) { t -> gotText = true; onText(t) }
            } catch (e: ForgeHttpException) {
                if (gotText || i == list.lastIndex || !nextKey(e.code, e.reason)) throw e
                com.dk.assistant.DKLog.d("Forge: वेबसाइट Key ${i + 1} नहीं चली (${e.code}) - बैकअप key")
                lastErr = e
            }
        }
        throw lastErr ?: ForgeHttpException(401, "API_KEY_INVALID")
    }

    /** pure: इस गलती पर वेबसाइट की बैकअप key आज़माएँ? (key गलत/बंद, limit, भीड़, कोई मॉडल नहीं) */
    fun nextKey(code: Int, reason: String?): Boolean =
        code == 400 || code == 401 || code == 403 || code == 429 || code == 0 || tryNextModel(code, reason)

    /** pure: पसंद का मॉडल सबसे पहले, बाकी उसी क्रम में (खाली पसंद = सूची जैसी है) */
    fun withPreferred(models: List<String>, prefer: String): List<String> {
        val p = prefer.trim().removePrefix("models/")
        return if (p.isBlank()) models else (listOf(p) + models.filter { it != p })
    }

    /**
     * pure: वेबसाइट बनाने के लिए सबसे अच्छे से कम: नया Pro -> नया Flash -> नया Lite -> पुराने 2.x.
     * (Pro की free limit खत्म हो तो stream अपने-आप अगले पर; [rankModels] वाला क्रम वैसा ही रहता है)
     */
    fun rankForWebsite(models: List<String>): List<String> {
        fun ver(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        fun tier(n: String): Int {
            val l = n.lowercase()
            if (com.dk.assistant.GeminiModels.isLegacy(n)) return 4
            if (l.contains("lite")) return 2
            if (l.contains("pro")) return 0
            if (l.contains("flash")) return 1
            return 3
        }
        return models.distinct().withIndex().sortedWith(compareBy<IndexedValue<String>>({ tier(it.value) },
            { if (it.value.contains("latest")) 1 else 0 }, { -ver(it.value) },
            { if (it.value.contains("preview") || it.value.contains("exp")) 1 else 0 }, { it.index })).map { it.value }
    }

    /**
     * Settings के लिए: इस key पर सचमुच चलने वाला सबसे अच्छा वेबसाइट मॉडल (छोटा सा जाँच-अनुरोध) + पूरी सूची.
     * key गलत = ForgeHttpException.
     */
    suspend fun pickBest(apiKey: String): Pair<String?, List<String>> = withContext(Dispatchers.IO) {
        val all = rankForWebsite(resolveModels(apiKey))
        var best: String? = null
        for (m in all.take(6)) {
            val req = Request.Builder().url("$BASE/models/$m:generateContent").header("x-goog-api-key", apiKey)
                .post(JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "Reply OK"))))).toString()
                    .toRequestBody("application/json".toMediaType())).build()
            val code = try { await(http.newCall(req)).use { r -> if (r.isSuccessful) 200 else r.code } } catch (_: Exception) { -1 }
            if (code == 200) { best = m; break }
            if (code == 401 || code == 403) throw ForgeHttpException(code, "API_KEY_INVALID")
        }
        best to all
    }

    private suspend fun streamOnce(apiKey: String, model: String, prompt: String, onText: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(requestBody(model, prompt).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req)
        var finish: String? = null
        val resp = await(call)
        try {
            resp.use { r ->
                if (!r.isSuccessful) throw ForgeHttpException(r.code, SseParser.extractError(r.body?.string().orEmpty()))
                val src = r.body?.source() ?: throw IOException("empty body")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    SseParser.extractError(line)?.let { throw ForgeHttpException(if (it.startsWith("503")) 503 else 0, it) }
                    val t = SseParser.extractText(line)
                    if (t.isNotEmpty()) onText(t)
                    SseParser.extractFinishReason(line)?.let { finish = it }
                }
            }
        } finally {
            if (!isActive) call.cancel()
        }
        if (finish != null && finish != "STOP") com.dk.assistant.DKLog.d("Forge: finishReason=$finish ($model)")
        com.dk.assistant.DKLog.d("Forge: मॉडल $model")
        finish
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
        })
    }
}
