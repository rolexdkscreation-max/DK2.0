package com.dk.assistant

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bola hua audio -> text (bina beep wale mode ke liye).
 *  1. Groq Whisper (Groq key ho to) - sabse tez, Hindi badhiya
 *  2. Gemini (Gemini key ho to)
 */
object Transcriber {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(22, TimeUnit.SECONDS).build()

    // Whisper kabhi-kabhi chup/shor par yeh shabd "sun" leta hai
    private val JUNK = listOf("धन्यवाद", "शुक्रिया", "thank you", "thanks for watching", "सब्सक्राइब", "subscribe",
        "please subscribe", "you", "बाय", "ok.", "...", "।")

    fun available(c: Context) = Prefs.key(c, "groq").isNotBlank() || Prefs.key(c, "gemini").isNotBlank()

    suspend fun transcribe(c: Context, pcm: ByteArray): String? = withContext(Dispatchers.IO) {
        val wav = GeminiTts.pcmToWav(pcm, Ear.RATE)
        val seconds = pcm.size / 2.0 / Ear.RATE
        val groq = Prefs.key(c, "groq")
        val gem = Prefs.key(c, "gemini")
        var text: String? = null
        if (groq.isNotBlank()) text = try { groqWhisper(groq, wav) } catch (e: Exception) { DKLog.d("Groq STT: ${e.message}"); null }
        if (text == null && gem.isNotBlank()) text = try { gemini(gem, wav) } catch (e: Exception) { DKLog.d("Gemini STT: ${e.message}"); null }
        val t = text?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (seconds < 2.0 && JUNK.any { t.lowercase().trim(' ', '.', '!') == it.trim('.') }) return@withContext null
        t
    }

    @Volatile private var groqModel = "whisper-large-v3"

    private fun groqWhisper(key: String, wav: ByteArray): String = try { groqWhisper(key, wav, groqModel) } catch (e: AiException) {
        // model band/limit -> turbo
        if (groqModel != "whisper-large-v3-turbo" && (e.code == 404 || e.code == 400 || e.code == 429)) {
            groqModel = "whisper-large-v3-turbo"; groqWhisper(key, wav, groqModel)
        } else throw e
    }

    private fun groqWhisper(key: String, wav: ByteArray, model: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            // large-v3 (turbo nahi) = Hindi/Hinglish me sabse sahi; Groq par phir bhi ~1 sec
            .addFormDataPart("model", model)
            .addFormDataPart("language", "hi")
            // chhota sa ishaara: lamba prompt Whisper shor par khud "bol" deta hai
            .addFormDataPart("prompt", "हिंदी और English मिली बातचीत, देवनागरी में।")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw AiException(r.code, s.take(200))
            val o = JSONObject(s)
            val segs = o.optJSONArray("segments") ?: return o.optString("text")
            // sirf asli boli hui baat - shor/khaali hisse (no_speech) chhod do
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val g = segs.optJSONObject(i) ?: continue
                val noSpeech = g.optDouble("no_speech_prob", 0.0)
                val logp = g.optDouble("avg_logprob", 0.0)
                if (noSpeech > 0.6 && logp < -0.8) continue
                if (logp < -2.0) continue   // bilkul ulta-pulta (shor)
                sb.append(g.optString("text"))
            }
            return sb.toString()
        }
    }

    @Volatile private var gemModel: String? = null

    private fun gemini(key: String, wav: ByteArray): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", "Transcribe this audio exactly as spoken. Write Hindi in Devanagari and English words in English. " +
                "Only the main speaker's words; skip background noise, TV and cut-off fragments. " +
                "Output ONLY the transcript text. If there is no clear human speech, output nothing."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        var last: Exception? = null
        for (m in (listOfNotNull(gemModel) + listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")).distinct()) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { r ->
                    val s = r.body?.string() ?: ""
                    if (!r.isSuccessful) throw AiException(r.code, s.take(200))
                    gemModel = m
                    val p = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    return (0 until (p?.length() ?: 0)).joinToString("") { p!!.optJSONObject(it)?.optString("text") ?: "" }
                }
            } catch (e: AiException) {
                last = e
                if (e.code == 401 || e.code == 403) throw e
            }
        }
        throw last ?: AiException(0, "Gemini STT fail")
    }
}
