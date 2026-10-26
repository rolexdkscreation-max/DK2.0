package com.dk.assistant.voice.engine

import android.content.Context
import android.widget.LinearLayout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * इंजन 1 (डिफ़ॉल्ट): Gemini TTS - Cloudflare Worker "dk-brain" के ज़रिए.
 * Gemini की API key सिर्फ़ Worker में (secret GEMINI_API_KEY) - ऐप में नहीं. ऐप Worker को
 * {text, voice, style} भेजता है, Worker बहती (streaming) 24 kHz PCM लौटाता है (worker/dk-brain).
 *
 * हर टुकड़े से पहले वही एक स्टाइल-निर्देश जाता है ("धीमी, कोमल, भावुक आवाज़ में, प्यार से बोलो:") -
 * पूरे जवाब में आवाज़ एक जैसी रहे. HTTP/2 का एक ही गर्म कनेक्शन (हर टुकड़े पर नया नहीं).
 */
object GeminiTtsEngine : VoiceEngine {
    override val id = VoiceEngines.GEMINI_TTS
    override val title = "Gemini TTS"
    override val note = "भावना/स्टाइल का निर्देश मानता है • key Cloudflare Worker (dk-brain) में"
    override val split = true
    override val defaultOn = true

    const val DEFAULT_STYLE = "धीमी, कोमल, भावुक आवाज़ में, प्यार से बोलो:"
    const val DEFAULT_VOICE = "Kore"
    private val VOICES = listOf(
        "Kore" to "Kore — मज़बूत, साफ़ (महिला)",
        "Leda" to "Leda — जवान, ताज़ी (महिला)",
        "Aoede" to "Aoede — हल्की, खुली (महिला)",
        "Sulafat" to "Sulafat — गर्मजोशी भरी (महिला)",
        "Vindemiatrix" to "Vindemiatrix — कोमल, प्यारी (महिला)",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)                 // बीच में 8 s चुप = टूटा मानो (Gemini TTS पर लौटो/फ़ोन)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()
    private val calls = Collections.synchronizedSet(HashSet<Call>())

    override fun voices() = VOICES
    override fun voice(c: Context): String =
        VoiceEngines.sp(c).getString("gemini_tts_voice", null)?.takeIf { v -> VOICES.any { it.first == v } } ?: DEFAULT_VOICE
    override fun setVoice(c: Context, v: String) { VoiceEngines.sp(c).edit().putString("gemini_tts_voice", v).apply() }

    fun style(c: Context): String = VoiceEngines.sp(c).getString("gemini_tts_style", null) ?: DEFAULT_STYLE
    fun workerUrl(c: Context): String = (VoiceEngines.sp(c).getString("worker_url", "") ?: "").trim().trimEnd('/')
    private fun token(c: Context): String = (VoiceEngines.sp(c).getString("worker_token", "") ?: "").trim()

    override fun notReady(c: Context): String? {
        val u = workerUrl(c)
        return when {
            u.isBlank() -> "Cloudflare Worker (dk-brain) का पता नहीं डाला"
            !u.startsWith("https://") -> "Worker का पता https:// से शुरू होना चाहिए"
            else -> null
        }
    }

    /** छोटा GET /ping - TLS + HTTP/2 कनेक्शन पहले से खुला रहे (पहले टुकड़े पर हाथ मिलाने की देरी नहीं) */
    override fun warm(c: Context) {
        if (notReady(c) != null) return
        val r = Request.Builder().url(workerUrl(c) + "/ping").get().apply { auth(this, c) }.build()
        try { http.newCall(r).execute().close() } catch (_: Exception) {}
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        notReady(c)?.let { throw IOException(it) }
        val body = JSONObject().put("text", text).put("voice", voice).put("style", com.dk.assistant.CalmPersona.ttsStyle(com.dk.assistant.avatar.MoodState.voiceStyle(style(c)))).toString()   // v3.71: भाव का निर्देश जुड़ता है
        val req = Request.Builder().url(workerUrl(c) + "/tts")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { auth(this, c) }.build()
        val call = http.newCall(req)
        calls.add(call)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) {
                    val msg = try { r.body?.string()?.take(160) } catch (_: Exception) { null }
                    throw IOException("Worker ${r.code}: ${msg.orEmpty()}")
                }
                val rate = Regex("rate=(\\d+)").find(r.header("Content-Type").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: VoiceEngine.RATE
                val src = r.body?.byteStream() ?: throw IOException("Worker ने खाली जवाब दिया")
                val buf = ByteArray(4800)                   // ~100 ms - आते ही आगे
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val piece = buf.copyOf(n)
                    out.write(if (rate == VoiceEngine.RATE) piece else Resample.to24k(piece, rate))
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) return                    // stop() - चुपचाप
            throw e
        } finally { calls.remove(call) }
    }

    override fun stop() { synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() } }

    private fun auth(b: Request.Builder, c: Context) { token(c).takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") } }

    override fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {
        ui.field(box, "Cloudflare Worker (dk-brain) का पता", workerUrl(c), "https://dk-brain.<नाम>.workers.dev") { v ->
            VoiceEngines.sp(c).edit().putString("worker_url", v.trim()).apply(); VoiceEngines.clearFailed(id)
        }
        ui.field(box, "Worker का ऐप-टोकन (APP_TOKEN, ज़रूरी नहीं)", token(c), "Worker में APP_TOKEN रखा हो तो वही", secret = true) { v ->
            VoiceEngines.sp(c).edit().putString("worker_token", v.trim()).apply(); VoiceEngines.clearFailed(id)
        }
        ui.field(box, "भावना / स्टाइल (हर टुकड़े से पहले यही एक निर्देश)", style(c), DEFAULT_STYLE, multiLine = true) { v ->
            VoiceEngines.sp(c).edit().putString("gemini_tts_style", v.trim()).apply()
        }
        ui.hint(box, "Gemini की key ऐप में नहीं - Worker में: wrangler secret put GEMINI_API_KEY (worker/dk-brain/README.md)")
    }
}
