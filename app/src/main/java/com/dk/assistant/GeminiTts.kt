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

    /**
     * (key, model) -> kab tak band (429 limit). Free tier: 10 awaaz/din har model HAR KEY - khatam ho to baar-baar mat maango.
     * v4.2: pehle limit sirf model ki thi (key ki nahi) aur sirf mukhya key chalti thi - mukhya key ki limit khatam = DK chup,
     * call aane par bhi ("Gemini आवाज़ की लिमिट खत्म"). Ab har key ki alag, aur bachi hui backup key se awaaz.
     */
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** key ka chhota pehchaan-naam (key khud kahin save/log nahi hoti) */
    internal fun tag(key: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    private fun slot(key: String, m: String) = tag(key) + "|" + m
    private fun cdName(key: String, m: String) = "tts:" + tag(key) + ":" + m
    private fun blocked(key: String, m: String, now: Long) =
        (blockedUntil[slot(key, m)] ?: 0L) > now || Cooldown.blocked(cdName(key, m))

    /** Is key ke sab model band hain to kab khulenge (0 = koi khula hai) */
    fun allBlockedUntil(key: String): Long {
        val now = System.currentTimeMillis()
        val all = (NEW_MODELS + OLD_MODELS).filter { it !in missing }
        if (all.isEmpty()) return now + 6 * 3600_000L
        val times = all.map { maxOf(blockedUntil[slot(key, it)] ?: 0L, Cooldown.until(cdName(key, it))) }
        return if (times.all { it > now }) times.min() else 0L
    }

    /** Sab keys band hon to kab koi khulegi (0 = koi key abhi chal sakti hai) */
    fun allBlockedUntil(keys: List<String>): Long {
        if (keys.isEmpty()) return 0L
        val t = keys.map { allBlockedUntil(it) }
        return if (t.any { it == 0L }) 0L else t.min()
    }

    /**
     * v4.2: mukhya key, phir backup 2, phir backup 3 - jo key chale usi se awaaz.
     * 401/403 (key galat) ya us key ke sab model ki limit khatam -> chupchaap agli key.
     */
    fun synthesizeAny(keys: List<String>, text: String, voice: String, style: String = ""): ByteArray {
        val list = keys.filter { it.isNotBlank() }.distinct()
        if (list.isEmpty()) throw AiException(401, "Gemini की key नहीं है")
        var last: Exception? = null
        for ((i, k) in list.withIndex()) {
            if (allBlockedUntil(k) > 0L) continue
            try {
                val w = synthesize(k, text, voice, style)
                if (i > 0) DKLog.d("Gemini TTS: मुख्य key नहीं चली - बैकअप key ${i + 1} से आवाज़")
                return w
            } catch (e: AiException) {
                last = e
                DKLog.d("Gemini TTS key ${i + 1} नहीं चली (${e.code}) - अगली key")
            }
        }
        throw (last as? AiException) ?: AiException(429, "Gemini TTS की आज की फ्री लिमिट खत्म (सब keys)")
    }

    /** "Please retry in 13h22m27.5s" / "retryDelay":"48s" -> ms */
    internal fun retryMs(msg: String): Long {
        val m = Regex("""retry in\s*(?:(\d+)h)?\s*(?:(\d+)m(?!s))?\s*(?:([\d.]+)s)?""", RegexOption.IGNORE_CASE).find(msg)
        if (m != null) {
            val h = m.groupValues[1].toLongOrNull() ?: 0; val mi = m.groupValues[2].toLongOrNull() ?: 0
            val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val t = h * 3600_000 + mi * 60_000 + (sec * 1000).toLong()
            if (t > 0) return t + 5_000
        }
        Regex("""retryDelay"?\s*:?\s*"?(\d+)s""").find(msg)?.let { return it.groupValues[1].toLong() * 1000 + 5_000 }
        val low = msg.lowercase()
        return if (low.contains("per day") || low.contains("perday") || low.contains("daily")) 3 * 3600_000L else 60_000L
    }

    fun synthesize(key: String, text: String, voice: String, style: String = ""): ByteArray {
        val now = System.currentTimeMillis()
        val order = (listOfNotNull(workingModel) + NEW_MODELS + OLD_MODELS).distinct()
            .filter { it !in missing && !blocked(key, it, now) }
        if (order.isEmpty()) throw AiException(429, "Gemini TTS की आज की फ्री लिमिट खत्म")
        var last: Exception? = null
        for (m in order) {
            try {
                val wav = if (m in OLD_MODELS) legacy(key, m, text, voice, style) else interactions(key, m, text, voice, style)
                workingModel = m
                return wav
            } catch (e: AiException) {
                last = e
                if (e.code == 401 || e.code == 403) { DKLog.d("Gemini TTS $m -> ${e.code} ${e.message}"); throw e }
                when (e.code) {
                    429 -> {
                        val ms = retryMs(e.message ?: "")
                        blockedUntil[slot(key, m)] = System.currentTimeMillis() + ms
                        Cooldown.block(cdName(key, m), ms)          // app band-chalu hone par bhi yaad (har baar 4 model maangkar 1.5s deri nahi)
                        if (workingModel == m) workingModel = null
                        DKLog.d("Gemini TTS $m -> 429 लिमिट, ${ms / 60_000} मिनट तक नहीं माँगूँगा")
                    }
                    404 -> { missing.add(m); if (workingModel == m) workingModel = null; DKLog.d("Gemini TTS $m -> 404 (मॉडल नहीं)") }
                    else -> DKLog.d("Gemini TTS $m -> ${e.code} ${e.message}")
                }
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

    /** v3.76: हर स्टाइल में DK का शांत, मध्यम-ठहराव वाला बोलना (CalmPersona) */
    fun styleText(s: String) = CalmPersona.ttsStyle(baseStyle(s))

    private fun baseStyle(s: String) = when (s) {
        "cheerful" -> "cheerful, happy and energetic natural Hindi"
        "calm" -> "calm, soft and soothing natural Hindi"
        "friend" -> "casual, friendly like a close friend, natural Hindi"
        "pro" -> "clear, confident and professional natural Hindi"
        "sweet" -> "sweet, gentle and caring natural Hindi"
        else -> "warm, natural, friendly conversational Hindi"
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
