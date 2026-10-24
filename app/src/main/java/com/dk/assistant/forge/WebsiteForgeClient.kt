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
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    class ForgeHttpException(val code: Int, val reason: String?) : IOException(SseParser.friendlyError(code, reason))

    @Volatile private var cachedModel: String? = null

    /** ListModels से पहला "flash" (live/image/tts नहीं) generateContent मॉडल; न मिले तो पहला generateContent */
    suspend fun resolveModel(apiKey: String): String = cachedModel ?: withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/models?pageSize=200").header("x-goog-api-key", apiKey).get().build()
        val body = await(http.newCall(req)).use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ForgeHttpException(r.code, SseParser.extractError(b))
            b
        }
        val m = chooseModel(JSONObject(body).optJSONArray("models") ?: JSONArray())
            ?: throw ForgeHttpException(0, "NO_MODEL")
        cachedModel = m
        m
    }

    /** pure: मॉडल सूची में से चुनाव (test होता है) */
    fun chooseModel(models: JSONArray): String? {
        val gen = (0 until models.length()).mapNotNull { models.optJSONObject(it) }.filter { o ->
            val ms = o.optJSONArray("supportedGenerationMethods") ?: return@filter false
            (0 until ms.length()).any { ms.optString(it) == "generateContent" }
        }.map { it.optString("name") }.filter { it.isNotBlank() }
        val flash = gen.firstOrNull { n ->
            val l = n.lowercase()
            l.contains("flash") && !l.contains("live") && !l.contains("image") && !l.contains("tts") && !l.contains("audio")
        }
        return (flash ?: gen.firstOrNull())?.removePrefix("models/")
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
    suspend fun stream(apiKey: String, prompt: String, onText: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val model = resolveModel(apiKey)
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
        if (finish != null && finish != "STOP") com.dk.assistant.DKLog.d("Forge: finishReason=$finish")
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
