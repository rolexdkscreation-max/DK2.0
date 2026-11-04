package com.dk.assistant.voice.engine

import android.content.Context
import android.widget.LinearLayout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * इंजन 1: Gemini TTS - नवंबर 2026 (मालिक): अब सीधे Google से, Cloudflare Worker (dk-brain) नहीं.
 * DK लाइव वाली Gemini key (मुख्य + बैकअप 2 + 3) से ही - Worker का पता / ऐप-टोकन कुछ नहीं डालना.
 * नियम/शरीर [GeminiTtsDirect] में (JVM test). बहती (streaming) 24 kHz PCM, आते ही आगे.
 *
 * हर टुकड़े से पहले वही एक स्टाइल-निर्देश जाता है ("धीमी, कोमल, भावुक आवाज़ में, प्यार से बोलो:") -
 * पूरे जवाब में आवाज़ एक जैसी रहे. HTTP/2 का एक ही गर्म कनेक्शन (हर टुकड़े पर नया नहीं).
 */
object GeminiTtsEngine : VoiceEngine {
    override val id = VoiceEngines.GEMINI_TTS
    override val title = "Gemini TTS"
    override val note = "भावना/स्टाइल का निर्देश मानता है • DK लाइव वाली Gemini key से (Worker नहीं)"
    override val split = true
    override val defaultOn = false

    /** v3.80: और कोमल, इंसान जैसी - रोबोट/उद्घोषक जैसी नहीं */
    const val DEFAULT_STYLE = "बहुत कोमल, मीठी और अपनेपन भरी आवाज़ में, जैसे कोई अपना प्यार से धीरे-धीरे बात कर रहा हो - स्वाभाविक साँस और ठहराव के साथ, रोबोट जैसा बिल्कुल नहीं:"
    /** v4.3 (मालिक): डिफ़ॉल्ट Sulafat - सीधी Live आवाज़ जैसी ही (पहले Achernar) */
    const val DEFAULT_VOICE = "Sulafat"
    /** v3.80: Gemini TTS के मॉडल - "" = अपने-आप (जो चले) */
    val MODELS = GeminiTtsDirect.MODELS
    private val VOICES = listOf(
        "Sulafat" to "Sulafat — गर्मजोशी भरी, कोमल (महिला)",
        "Achernar" to "Achernar — बहुत नरम, कोमल (महिला)",
        "Vindemiatrix" to "Vindemiatrix — कोमल, प्यारी (महिला)",
        "Despina" to "Despina — मुलायम, मीठी (महिला)",
        "Kore" to "Kore — मज़बूत, साफ़ (महिला)",
        "Leda" to "Leda — जवान, ताज़ी (महिला)",
        "Aoede" to "Aoede — हल्की, खुली (महिला)",
    )

    private val http = com.dk.assistant.NetFix.builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)                // पहला टुकड़ा धीमे नेट पर 8s से ज़्यादा ले सकता है; 15 s चुप = टूटा मानो
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
        .build()
    private val calls = Collections.synchronizedSet(HashSet<Call>())

    override fun voices() = VOICES
    /** v4.3: Live वाली ही आवाज़ (एक ही सेटिंग "gemini_voice") - दोनों जगह बिल्कुल एक जैसी */
    override fun voice(c: Context): String =
        com.dk.assistant.Prefs.geminiVoice(c).takeIf { v -> VOICES.any { it.first == v } } ?: DEFAULT_VOICE
    override fun setVoice(c: Context, v: String) {
        VoiceEngines.sp(c).edit().putString("gemini_tts_voice", v).apply()
        com.dk.assistant.Prefs.edit(c) { putString("gemini_voice", v) }; VoiceEngines.liveChanged()
    }

    fun style(c: Context): String = VoiceEngines.sp(c).getString("gemini_tts_style", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_STYLE

    /** v3.80: चुना मॉडल ("" = अपने-आप). अगले टुकड़े से ही लगता है - चलती बातचीत में भी */
    fun model(c: Context): String = VoiceEngines.sp(c).getString("gemini_tts_model", "") ?: ""
    fun setModel(c: Context, m: String) { VoiceEngines.sp(c).edit().putString("gemini_tts_model", m.trim()).apply() }
    /** पिछली बार किस मॉडल से आवाज़ बनी */
    fun lastModel(c: Context): String = VoiceEngines.sp(c).getString("gemini_tts_model_used", "") ?: ""
    private fun keys(c: Context): List<String> = com.dk.assistant.Prefs.liveGeminiKeys(c).filter { it.isNotBlank() }.distinct()

    /** (key का छोटा नाम|मॉडल) -> कब तक नहीं माँगना (429 limit / 404) - हर टुकड़े पर बेकार request नहीं */
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun slot(key: String, m: String) = com.dk.assistant.GeminiTts.tag(key) + "|" + m

    override fun notReady(c: Context): String? = if (keys(c).isEmpty()) GeminiTtsDirect.NO_KEY else null

    /** छोटा GET - TLS + HTTP/2 कनेक्शन Google से पहले से खुला रहे (key नहीं जाती) */
    override fun warm(c: Context) {
        if (notReady(c) != null) return
        val r = Request.Builder().url(GeminiTtsDirect.HOST).get().build()
        try { http.newCall(r).execute().close() } catch (_: Exception) {}
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        notReady(c)?.let { throw IOException("Gemini TTS: $it") }
        val style = com.dk.assistant.SpeakingStyle.tts(com.dk.assistant.CalmPersona.ttsStyle(com.dk.assistant.avatar.MoodState.voiceStyle(style(c))))   // v4.3: मालिक का बोलने का तरीका   // v3.71: भाव का निर्देश जुड़ता है
        var last = ""
        for (key in keys(c)) {
            val now = System.currentTimeMillis()
            val order = GeminiTtsDirect.models(lastModel(c).ifBlank { null }, model(c)) { (blockedUntil[slot(key, it)] ?: 0L) > now }   // v3.80: चुना मॉडल पहले
            var badKey = false
            for (m in order) {
                if (badKey) break
                val req = Request.Builder().url(GeminiTtsDirect.API)
                    .header("x-goog-api-key", key)
                    .header("Api-Revision", GeminiTtsDirect.API_REVISION)
                    .post(GeminiTtsDirect.body(m, text, voice, style).toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val call = http.newCall(req)
                calls.add(call)
                try {
                    call.execute().use { r ->
                        if (!r.isSuccessful) {
                            val msg = try { r.body?.string()?.take(300) } catch (_: Exception) { null }
                            last = GeminiTtsDirect.why(r.code, msg)
                            when (GeminiTtsDirect.next(r.code, msg)) {
                                GeminiTtsDirect.Next.MODEL -> {
                                    if (r.code == 429 || r.code == 404) blockedUntil[slot(key, m)] = System.currentTimeMillis() +
                                        (if (r.code == 429) com.dk.assistant.GeminiTts.retryMs(msg ?: "") else 6 * 3600_000L)
                                    if (lastModel(c) == m) VoiceEngines.sp(c).edit().remove("gemini_tts_model_used").apply()
                                    return@use
                                }
                                GeminiTtsDirect.Next.KEY -> { badKey = true; return@use }     // यह key बेकार - बाकी मॉडल भी नहीं, अगली key
                                GeminiTtsDirect.Next.STOP -> throw IOException("Gemini TTS: $last (${r.code})")
                            }
                        }
                        if (m != lastModel(c)) VoiceEngines.sp(c).edit().putString("gemini_tts_model_used", m).apply()
                        var got = 0
                        val ct = r.header("Content-Type").orEmpty()
                        val src = r.body ?: throw IOException("Gemini TTS: खाली जवाब")
                        if (ct.contains("event-stream")) {
                            src.byteStream().bufferedReader(Charsets.UTF_8).use { rd ->
                                while (true) {
                                    val line = rd.readLine() ?: break
                                    val pcm = GeminiTtsDirect.pcmFromLine(line) ?: continue
                                    if (pcm.isEmpty()) continue
                                    got += pcm.size; out.write(pcm)                  // आते ही आगे
                                }
                            }
                        } else {
                            val pcm = GeminiTtsDirect.pcmFromJson(src.string())
                            if (pcm.isNotEmpty()) { got += pcm.size; out.write(pcm) }
                        }
                        if (call.isCanceled()) return
                        // DKI-9DC74D: 200 पर भी खाली/कटी आवाज़ = नाकामी (चुपचाप "सफल" नहीं - वरना हर बार फिर माँगना)
                        if (got < GeminiTtsDirect.MIN_PCM_BYTES) throw IOException("Gemini TTS: खाली आवाज़ आई ($m)")
                        return
                    }
                } catch (e: IOException) {
                    if (call.isCanceled()) return                    // stop() - चुपचाप
                    if (e.message?.startsWith("Gemini TTS:") == true) throw e
                    throw IOException("Gemini TTS: ${GeminiTtsDirect.network(e.message)}", e)
                } finally { calls.remove(call) }
            }
        }
        throw IOException("Gemini TTS: ${last.ifBlank { GeminiTtsDirect.why(429, null) }}")
    }

    override fun stop() { synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() } }

    override fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {
        ui.field(box, "भावना / स्टाइल (हर टुकड़े से पहले यही एक निर्देश)", style(c), DEFAULT_STYLE, multiLine = true) { v ->
            VoiceEngines.sp(c).edit().putString("gemini_tts_style", v.trim()).apply()
        }
        ui.hint(box, "कुछ नहीं डालना - DK लाइव वाली Gemini key (मुख्य + बैकअप) से ही आवाज़ बनती है")
    }
}
