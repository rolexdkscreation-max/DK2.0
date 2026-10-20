package com.dk.assistant

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Gemini ki bahut natural awaaz (Gemini API key se). WAV bytes deta hai. */
object GeminiTts {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    private val NEW_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
    private val OLD_MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

    @Volatile private var workingModel: String? = null

    fun synthesize(key: String, text: String, voice: String, style: String = ""): ByteArray {
        val order = (listOfNotNull(workingModel) + NEW_MODELS + OLD_MODELS).distinct()
        var last: Exception? = null
        for (m in order) {
            try {
                val wav = if (m in OLD_MODELS) legacy(key, m, text, voice, style) else interactions(key, m, text, voice, style)
                workingModel = m
                return wav
            } catch (e: AiException) {
                last = e
                DKLog.d("Gemini TTS $m -> ${e.code} ${e.message}")
                if (e.code == 401 || e.code == 403) throw e
            }
        }
        throw last ?: AiException(0, "Gemini TTS fail")
    }

    private fun post(url: String, key: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url).header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = try { JSONObject(s).optJSONObject("error")?.optString("message") ?: s.take(200) } catch (e: Exception) { s.take(200) }
                throw AiException(r.code, msg)
            }
            return JSONObject(s)
        }
    }

    /** Naya Interactions API. */
    private fun interactions(key: String, model: String, text: String, voice: String, style: String): ByteArray {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text)
            .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata")
                .put("style", styleText(style)))))
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", content)))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        val o = post("https://generativelanguage.googleapis.com/v1beta/interactions", key, body)
        var data: String? = null; var mime = ""
        val steps = o.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val st = steps.optJSONObject(i) ?: continue
            val c = st.optJSONArray("content") ?: continue
            for (j in 0 until c.length()) {
                val part = c.optJSONObject(j) ?: continue
                if (part.optString("type") == "audio" && part.optString("data").isNotBlank()) {
                    data = part.optString("data"); mime = part.optString("mime_type")
                }
            }
        }
        val bytes = Base64.decode(data ?: throw AiException(0, "audio nahi mila"), Base64.DEFAULT)
        return if (isWav(bytes)) bytes else pcmToWav(bytes, 24000)
    }

    /** Purana generateContent tareeka (PCM 24kHz). */
    private fun legacy(key: String, model: String, text: String, voice: String, style: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Say in a ${styleText(style)} way: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val o = post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", key, body)
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(0, "audio nahi mila")
        for (i in 0 until parts.length()) {
            val inl = parts.optJSONObject(i)?.let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") } ?: continue
            val bytes = Base64.decode(inl.optString("data"), Base64.DEFAULT)
            val rate = Regex("rate=(\\d+)").find(inl.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            return if (isWav(bytes)) bytes else pcmToWav(bytes, rate)
        }
        throw AiException(0, "audio nahi mila")
    }

    fun styleText(s: String) = when (s) {
        "cheerful" -> "cheerful, happy and energetic natural Hindi"
        "calm" -> "calm, soft and soothing natural Hindi"
        "friend" -> "casual, friendly like a close friend, natural Hindi"
        "pro" -> "clear, confident and professional natural Hindi"
        "sweet" -> "sweet, gentle and caring natural Hindi"
        else -> "warm, natural, friendly conversational Hindi, normal speed"
    }

    private fun isWav(b: ByteArray) = b.size > 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    fun pcmToWav(pcm: ByteArray, rate: Int, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(channels); i32(rate); i32(rate * channels * 2); i16(channels * 2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
