package com.dk.assistant.voice.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * नवंबर 2026 (मालिक): "Worker का पता / token न डालना पड़े" - Cloudflare Worker (dk-brain) पूरी तरह हटाया.
 * ऐप अब सीधे Google के Gemini TTS (Interactions API, stream) से बात करता है, DK लाइव वाली उसी Gemini key से
 * (मुख्य + बैकअप 2 + 3, [com.dk.assistant.Prefs.liveGeminiKeys]). key सिर्फ़ `x-goog-api-key` header में, URL में कभी नहीं.
 *
 * जड़ (DKI-9DC74D): Worker में APP_TOKEN secret नहीं = हर /tts पर 500; और ऐप में "APP_TOKEN, ज़रूरी नहीं" लिखा था.
 * Worker से key की सुरक्षा कुछ नहीं बढ़ती थी - DK लाइव की वही key पहले से फ़ोन में (ताले में, backup में नहीं).
 *
 * यह फ़ाइल pure logic है (Android import नहीं) -> JVM test ([GeminiTtsDirectTest]). Worker (index.js) वाला ही तरीका:
 * वही API revision, 3.8 मॉडलों में style `speech_metadata` में, पुराने मॉडलों में text से पहले, SSE `step.delta` audio.
 */
object GeminiTtsDirect {
    const val API = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val API_REVISION = "2026-05-20"
    /** कनेक्शन गर्म रखने के लिए (key नहीं जाती) */
    const val HOST = "https://generativelanguage.googleapis.com/"
    /** Worker का DEFAULT_MODELS वाला क्रम (पहला न चले तो अगला) */
    val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview")
    val VOICES = setOf("Kore", "Leda", "Aoede", "Sulafat", "Vindemiatrix", "Zephyr", "Puck", "Charon", "Fenrir", "Callirrhoe", "Despina", "Achernar")
    /** v4.3: DK की एक ही आवाज़ (Live जैसी) */
    const val DEFAULT_VOICE = "Sulafat"
    const val MAX_TEXT = 600
    /** v3.80: कोमल आवाज़ का पूरा निर्देश (Worker में भी 600) */
    const val MAX_STYLE = 600
    /** इससे कम आवाज़ = असल में कुछ नहीं बोला (खाली/कटा जवाब) */
    const val MIN_PCM_BYTES = 2_000

    fun voiceOrDefault(v: String?): String = v?.takeIf { it in VOICES } ?: DEFAULT_VOICE

    /** जो पिछली बार चला वह पहले, फिर चुना मॉडल सबसे पहले (सूची में हो तो), बाकी क्रम से; बंद (limit/404) वाले बाद में नहीं - हटे */
    fun models(good: String?, want: String?, skip: (String) -> Boolean = { false }): List<String> {
        var list = if (good != null && good in MODELS) listOf(good) + MODELS.filter { it != good } else MODELS
        val w = want?.trim().orEmpty()
        if (w.isNotEmpty() && w in MODELS) list = listOf(w) + list.filter { it != w }
        return list.filterNot(skip)
    }

    /** Worker के buildBody जैसा ही */
    fun body(model: String, text: String, voice: String, style: String): String {
        val t = text.trim().take(MAX_TEXT)
        val s = style.trim().take(MAX_STYLE)
        val v38 = model.startsWith("gemini-3.8")
        val o = JSONObject().put("model", model)
        if (v38 && s.isNotEmpty()) {
            val part = JSONObject().put("type", "text").put("text", t)
                .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", s)))
            o.put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(part))))
        } else {
            o.put("input", if (s.isNotEmpty()) "$s $t" else t)
        }
        return o.put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voiceOrDefault(voice)))))
            .put("stream", true)
            .toString()
    }

    /** SSE की एक लाइन (`data: {...}`) -> PCM bytes; audio नहीं = null */
    fun pcmFromLine(line: String): ByteArray? {
        val l = line.trim()
        if (!l.startsWith("data:")) return null
        val d = l.substring(5).trim()
        if (d.isEmpty() || d == "[DONE]") return null
        val ev = try { JSONObject(d) } catch (_: Exception) { return null }
        val delta = ev.optJSONObject("delta") ?: return null
        val type = ev.optString("event_type").ifEmpty { ev.optString("type") }
        if (type != "step.delta" || delta.optString("type") != "audio") return null
        val data = delta.optString("data").ifEmpty { return null }
        return try { stripWav(java.util.Base64.getMimeDecoder().decode(data)) } catch (_: Exception) { null }
    }

    /** stream नहीं, पूरा JSON मिला - उसमें के सारे audio टुकड़े जोड़कर */
    fun pcmFromJson(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    if (o.optString("type") == "audio" && o.optString("data").isNotEmpty()) {
                        try { out.write(stripWav(java.util.Base64.getMimeDecoder().decode(o.optString("data")))) } catch (_: Exception) {}
                    }
                    for (k in o.keys()) walk(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(try { JSONObject(s) } catch (_: Exception) { null })
        return out.toByteArray()
    }

    /** RIFF/WAV header हो तो हटाओ (raw PCM ही आगे) */
    fun stripWav(u: ByteArray): ByteArray {
        if (u.size > 44 && u[0] == 'R'.code.toByte() && u[1] == 'I'.code.toByte() && u[2] == 'F'.code.toByte() && u[3] == 'F'.code.toByte()) {
            var i = 12
            while (i + 8 <= u.size) {
                val id = String(u, i, 4, Charsets.US_ASCII)
                val size = (u[i + 4].toInt() and 0xff) or ((u[i + 5].toInt() and 0xff) shl 8) or
                    ((u[i + 6].toInt() and 0xff) shl 16) or ((u[i + 7].toInt() and 0xff) shl 24)
                if (id == "data") return u.copyOfRange(i + 8, u.size)
                if (size < 0) break
                i += 8 + size
            }
            return u.copyOfRange(44, u.size)
        }
        return u
    }

    /** इस मॉडल का क्या करें - Worker जैसा: 404/400/429/5xx = अगला मॉडल; 401/403 = यह key बेकार, अगली key */
    enum class Next { MODEL, KEY, STOP }
    fun next(code: Int, body: String? = null): Next = when {
        code == 401 || code == 403 -> Next.KEY
        code == 400 && (body ?: "").lowercase().contains("api key") -> Next.KEY     // Google: 400 "API key not valid"
        code == 404 || code == 400 || code == 429 || code >= 500 -> Next.MODEL
        else -> Next.STOP
    }

    /** साफ़ हिंदी कारण (पहले WorkerWhy) */
    fun why(code: Int, body: String?): String {
        val b = (body ?: "").lowercase()
        return when {
            code == 401 || code == 403 || b.contains("api key not valid") -> "DK लाइव की Gemini key गलत है या उस पर TTS की इजाज़त नहीं"
            code == 429 -> "Gemini TTS की लिमिट खत्म (सब keys / मॉडल) - थोड़ी देर बाद"
            code == 404 -> "Gemini TTS मॉडल उपलब्ध नहीं"
            code == 400 -> "Gemini TTS को भेजी सेटिंग गलत है (आवाज़ या text)"
            code >= 500 -> "Google का Gemini TTS अभी नहीं चल रहा (server ${code})"
            else -> "Gemini TTS ने गलती दी (code $code)"
        }
    }

    /** जुड़ ही नहीं पाया / समय सीमा */
    fun network(msg: String?): String {
        val m = (msg ?: "").lowercase()
        return when {
            m.contains("unable to resolve") || m.contains("unknownhost") -> "Google (Gemini) तक नहीं पहुँचे - इंटरनेट देखें"
            m.contains("timeout") || m.contains("timed out") -> "Gemini TTS जवाब नहीं दे रहा (समय सीमा खत्म)"
            else -> "Gemini TTS से जुड़ नहीं पाए (इंटरनेट देखें)"
        }
    }

    const val NO_KEY = "DK लाइव की Gemini key नहीं डाली (Settings → AI दिमाग → DK लाइव)"
}
