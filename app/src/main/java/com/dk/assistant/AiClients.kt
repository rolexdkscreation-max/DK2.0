package com.dk.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Turn(val role: String, val text: String)   // role = "user" ya "model"

class AiException(val code: Int, message: String) : IOException(message)

/** Har AI company se baat karne ka ek hi tareeka. */
interface AiClient {
    val label: String
    fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

private val JSON_TYPE = "application/json".toMediaType()

private fun execute(req: Request): String = http.newCall(req).execute().use { r ->
    val body = r.body?.string() ?: ""
    if (!r.isSuccessful) {
        val msg = try {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(250)
        } catch (e: Exception) { body.take(250) }
        throw AiException(r.code, msg)
    }
    body
}

/** Google Gemini (apna alag format). */
class GeminiClient(private val key: String, private val model: String) : AiClient {
    override val label = "Gemini ($model)"

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        val contents = JSONArray()
        history.forEach {
            contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (imageB64 != null) parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)))
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val resp = JSONObject(execute(req))
        val p = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(0, "Gemini ne khali jawab diya")
        val sb = StringBuilder()
        for (i in 0 until p.length()) {
            val o = p.optJSONObject(i) ?: continue
            if (!o.optBoolean("thought", false)) sb.append(o.optString("text"))
        }
        return sb.toString()
    }
}

/** OpenAI, Grok, Groq, OpenRouter, Mistral, Z.ai, Custom - sab OpenAI jaisa format use karte hain. */
class OpenAiCompatClient(
    private val name: String,
    private val baseUrl: String,
    private val key: String,
    private val model: String
) : AiClient {
    override val label = "$name ($model)"

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach {
            msgs.put(JSONObject().put("role", if (it.role == "user") "user" else "assistant").put("content", it.text))
        }
        if (imageB64 != null) {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")))
            msgs.put(JSONObject().put("role", "user").put("content", content))
        } else {
            msgs.put(JSONObject().put("role", "user").put("content", prompt))
        }
        val body = JSONObject().put("model", model).put("messages", msgs)

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant")
            .header("X-Title", "DK Assistant")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val resp = JSONObject(execute(req))
        val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(0, "$name ne khali jawab diya")
        val c = msg.opt("content")
        val text = when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
            else -> ""
        }
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}
