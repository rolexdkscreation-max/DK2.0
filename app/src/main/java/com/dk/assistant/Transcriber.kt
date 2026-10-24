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

    fun available(c: Context) = Prefs.sttKeys(c).isNotEmpty()

    /** Pichhli baar sunne me key ki galti (null = sab theek / bas chup tha). DK isse bolkar batata hai. */
    @Volatile var lastError: String? = null
        private set
    /** limit/galti wali key kuch der aaram kare (key -> kab tak) */
    private val cool = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun transcribe(c: Context, pcm: ByteArray): String? = withContext(Dispatchers.IO) {
        val wav = GeminiTts.pcmToWav(pcm, Ear.RATE)
        val seconds = pcm.size / 2.0 / Ear.RATE
        val audioMs = (seconds * 1000).toLong()
        var text: String? = null
        val errs = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val keys = Prefs.sttKeys(c).sortedBy { (cool[it.second] ?: 0L) > now }   // aaram wali key aakhir me
        for ((pid, k) in keys) {
            text = try {
                if (pid == "groq") groqWhisper(k, wav, clientFor(SttTiming.groqTimeoutMs(audioMs)))
                else gemini(k, wav, Prefs.fastModels(c, "gemini"), clientFor(SttTiming.geminiTimeoutMs(audioMs)))
            } catch (e: Exception) {
                DKLog.d("$pid STT: ${e.message}")
                val code = (e as? AiException)?.code ?: 0
                cool[k] = System.currentTimeMillis() + when (code) { 401, 403 -> 30 * 60_000L; 429 -> 60_000L; else -> 15_000L }
                errs.add((if (pid == "groq") "Groq" else "Gemini") + ": " + ProviderHealth.short(e))
                null
            }
            if (text != null) break
        }
        lastError = if (text == null && errs.isNotEmpty()) errs.joinToString(", ") else null
        val t = text?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (seconds < 2.0 && JUNK.any { t.lowercase().trim(' ', '.', '!') == it.trim('.') }) return@withContext null
        t
    }

    /**
     * आवाज़ की लंबाई के हिसाब से छोटा timeout (पहले हर बार 22s - Groq अटका तो 22s बाद ही Gemini, कुल ~28s).
     * newBuilder = वही connection pool, नया client सस्ता.
     */
    private fun clientFor(callMs: Long): OkHttpClient = http.newBuilder()
        .connectTimeout(minOf(callMs, 4_000L), TimeUnit.MILLISECONDS)
        .readTimeout(callMs, TimeUnit.MILLISECONDS)
        .callTimeout(callMs, TimeUnit.MILLISECONDS).build()

    @Volatile private var groqModel = "whisper-large-v3"

    private fun groqWhisper(key: String, wav: ByteArray, client: OkHttpClient = http): String = try { groqWhisper(key, wav, groqModel, client) } catch (e: AiException) {
        // model band/limit -> turbo
        if (groqModel != "whisper-large-v3-turbo" && (e.code == 404 || e.code == 400 || e.code == 429)) {
            groqModel = "whisper-large-v3-turbo"; groqWhisper(key, wav, groqModel, client)
        } else throw e
    }

    private fun groqWhisper(key: String, wav: ByteArray, model: String, client: OkHttpClient): String {
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
        client.newCall(req).execute().use { r ->
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

    private fun gemini(key: String, wav: ByteArray, extra: List<String> = emptyList(), client: OkHttpClient = http): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", "Transcribe this audio exactly as spoken. Write Hindi in Devanagari and English words in English. " +
                "Only the main speaker's words; skip background noise, TV and cut-off fragments. " +
                "Output ONLY the transcript text. If there is no clear human speech, output nothing."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        var last: Exception? = null
        for (m in (listOfNotNull(gemModel) + extra.take(2) + listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")).distinct()) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(req).execute().use { r ->
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
