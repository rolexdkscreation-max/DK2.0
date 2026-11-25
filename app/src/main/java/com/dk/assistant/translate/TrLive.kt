package com.dk.assistant.translate

import android.util.Base64
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiLive
import com.dk.assistant.Quiet
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 25 नवंबर 2026 (मालिक: "पहले से चल रहे Gemini Live API; दो दिशाओं के लिए दो अलग session"):
 * DK ट्रांसलेटर की एक दिशा का Gemini Live जुड़ाव (A = सामने वाला -> हिंदी, B = मेरी हिंदी -> सामने वाले की भाषा).
 *
 * - जवाब सिर्फ़ AUDIO (बोला हुआ अनुवाद) + दोनों तरफ़ की लिखावट (inputAudioTranscription / outputAudioTranscription).
 * - activityHandling = NO_INTERRUPTION: सामने वाला बोलता रहे तब भी चल रहा अनुवाद पूरा सुनाई दे.
 * - फ़ोन सिर्फ़ असली आवाज़ भेजता है (चुप्पी नहीं) और ठहराव पर audioStreamEnd ([TrEngine]).
 * - टूटे / goAway = चुपचाप दोबारा जुड़ो (मालिक: "नेटवर्क कटे तो चुपचाप दोबारा जुड़े"). मॉडल / key न चले = अगला मॉडल.
 */
class TrLive(
    private val tag: String,
    private val key: String,
    private val models: List<String>,
    private val prompt: String,
    private val voice: String,
    private val cb: Cb,
) {
    interface Cb {
        fun onInText(t: String)
        fun onOutText(t: String)
        fun onAudio(pcm: ByteArray)
        fun onTurnDone()
        fun onState(ready: Boolean)
        fun onDead(why: String)
    }

    companion object {
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** setup JSON (pure-ish: सिर्फ़ org.json -> test) */
        fun setupJson(model: String, prompt: String, voice: String): String {
            val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
            // 2.5 native-audio पहले "सोचता" है = देर; अनुवाद में सोचना नहीं चाहिए
            if (model.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val setup = JSONObject()
                .put("model", "models/$model")
                .put("generationConfig", gen)
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                .put("realtimeInputConfig", JSONObject()
                    .put("automaticActivityDetection", JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        .put("prefixPaddingMs", 100)
                        .put("silenceDurationMs", 400))
                    .put("activityHandling", "NO_INTERRUPTION"))
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                // लंबी बातचीत में भी session न रुके
                .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            return JSONObject().put("setup", setup).toString()
        }
    }

    private val http = com.dk.assistant.NetFix.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var stopped = true
    @Volatile var dead = false
        private set
    @Volatile private var mi = 0
    @Volatile private var fails = 0
    @Volatile private var upAt = 0L
    private val pending = ArrayDeque<String>()

    private fun log(m: String) = DKLog.d("Translator[$tag]: $m")

    fun start() {
        if (key.isBlank() || models.isEmpty()) { dead = true; cb.onDead("key नहीं"); return }
        stopped = false; dead = false
        connect()
    }

    fun stop() {
        stopped = true; ready = false
        try { ws?.close(1000, "bye") } catch (e: Exception) { Quiet.note(e) }
        ws = null
        synchronized(pending) { pending.clear() }
    }

    /** 16 kHz PCM16 का टुकड़ा (सिर्फ़ असली आवाज़) */
    fun send(pcm: ByteArray) {
        if (stopped || dead) return
        val msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=${GeminiLive.IN_RATE}\",\"data\":\"" +
            Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}"
        val w = ws
        if (w == null || !ready) { synchronized(pending) { pending.addLast(msg); while (pending.size > 75) pending.removeFirst() }; return }   // 3 s तक
        w.send(msg)
    }

    /** बात का हिस्सा खत्म (ठहराव) - सर्वर अभी अनुवाद करे */
    fun streamEnd() {
        if (stopped || dead) return
        val m = "{\"realtimeInput\":{\"audioStreamEnd\":true}}"
        val w = ws
        if (w == null || !ready) { synchronized(pending) { pending.addLast(m) }; return }
        try { w.send(m) } catch (e: Exception) { Quiet.note(e) }
    }

    /** लिखा हुआ (मेरी हिंदी टाइप की) - उसी तरह अनुवाद */
    fun sendText(t: String) {
        if (stopped || dead || t.isBlank()) return
        val m = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t)))))
            .put("turnComplete", true)).toString()
        val w = ws
        if (w == null || !ready) { synchronized(pending) { pending.addLast(m) }; return }
        w.send(m)
    }

    private fun connect() {
        if (stopped || dead) return
        ready = false; cb.onState(false)
        val m = models[mi.coerceIn(0, models.size - 1)]
        ws = http.newWebSocket(Request.Builder().url(URL).header("x-goog-api-key", key).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson(m, prompt, voice)) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) onServer(text, m) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (webSocket === ws) onServer(bytes.utf8(), m) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (e: Exception) { Quiet.note(e) }
                if (webSocket === ws) dropped(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) dropped(response?.code ?: 0, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun dropped(code: Int, reason: String) {
        val was = ready
        ready = false; ws = null; cb.onState(false)
        if (stopped) return
        log("टूटा code=$code $reason")
        val r = reason.lowercase()
        if (!was && (r.contains("model") || r.contains("not found") || r.contains("not supported") || r.contains("permission") ||
                r.contains("api key") || code == 404 || code == 403 || code == 1007 || code == 1008)) {
            if (mi + 1 < models.size) { mi++; connect(); return }
            dead = true; cb.onDead(reason.ifBlank { "code $code" }); return
        }
        fails = if (was && System.currentTimeMillis() - upAt > 20_000) 0 else fails + 1
        // चुपचाप दोबारा: 0.3, 0.6, 1.2 ... 8 s तक, रुकना नहीं (नेट लौटते ही फिर चलने लगे)
        val wait = minOf(8_000L, 300L shl minOf(fails, 5))
        Thread {
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
            if (!stopped) connect()
        }.apply { isDaemon = true; name = "dk-tr-retry"; start() }
    }

    private fun onServer(msg: String, m: String) {
        val o = try { JSONObject(msg) } catch (e: Exception) { Quiet.note(e); return }
        if (o.has("setupComplete")) {
            ready = true; upAt = System.currentTimeMillis(); fails = 0
            log("जुड़ गया [$m]"); cb.onState(true)
            val list = synchronized(pending) { pending.toList().also { pending.clear() } }
            ws?.let { w -> list.forEach { w.send(it) } }
            return
        }
        if (o.has("goAway")) {
            val old = ws
            ws = null; ready = false
            try { old?.close(1000, "goAway") } catch (e: Exception) { Quiet.note(e) }
            if (!stopped) connect()
            return
        }
        val sc = o.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { cb.onInText(it) }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { cb.onOutText(it) }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (!d.optString("mimeType").startsWith("audio")) continue
                val b64 = d.optString("data")
                if (b64.isNotEmpty()) try { cb.onAudio(Base64.decode(b64, Base64.DEFAULT)) } catch (e: Exception) { Quiet.note(e) }
            }
        }
        if (sc.optBoolean("turnComplete", false)) cb.onTurnDone()
    }
}
