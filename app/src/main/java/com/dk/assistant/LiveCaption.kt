package com.dk.assistant

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "आप:" wali line ke liye turant shabd (v3.0).
 *
 * Baatcheet wala Gemini Live model user ki baat ka text aksar baat khatam hone ke BAAD bhejta hai.
 * Isliye wahi mic ki awaaz ek doosre, sirf-likhne wale model (gemini-3.5-transcribe-live) ko bhi
 * jaati hai. Woh bolte-bolte "interim" shabd bhejta hai (jaise "आज" -> "आज मौसम" -> "आज मौसम कैसा"),
 * aur rukne par pakka (final) text.
 *
 * interimInputTranscription = abhi ke hisse ka andaza (har baar poora hissa badalta hai, jodna nahi)
 * inputTranscription        = us hisse ka pakka text (committed me jodo, interim saaf)
 *
 * Yeh na chale (key/limit/model nahi) to chup-chaap band - tab purana tareeka (Live model ka text) chalta hai.
 */
class LiveCaption(
    private val key: String,
    private val onText: (String) -> Unit,
) {
    companion object {
        val MODELS = listOf("gemini-3.5-transcribe-live", "gemini-3.5-transcribe-live-preview")
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var stopped = true
    @Volatile private var mi = 0
    @Volatile private var fails = 0
    @Volatile private var dead = false          // is key par chalta hi nahi
    @Volatile private var upAt = 0L

    private val lock = Any()
    private val committed = StringBuilder()
    private var interim = ""
    @Volatile private var fresh = true           // agla shabd = nayi baat (purana text hatao)

    /** Abhi tak ki baat (pakka + andaza) */
    fun text(): String = synchronized(lock) { if (fresh) "" else join(committed.toString(), interim) }

    private fun join(a: String, b: String) = (a.trim() + " " + b.trim()).trim()

    private fun log(m: String) = DKLog.d("Caption: $m")

    fun start() {
        if (dead) return
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        ready = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    /** DK ne jawab dena shuru kiya - agli baar user bole to naya text */
    fun newUtterance() { fresh = true }

    /** Mic ka 16 kHz PCM16 tukda */
    fun send(pcm: ByteArray) {
        val w = ws ?: return
        if (!ready) return
        if (w.queueSize() > 128 * 1024) return       // network dheema - caption ke liye purani awaaz chhodo
        w.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=${GeminiLive.IN_RATE}\",\"data\":\"" +
            Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}")
    }

    private fun setupJson(m: String): String {
        val setup = JSONObject()
            .put("model", "models/$m")
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
            // languageCodes khaali = Hindi/English/Hinglish apne-aap pehchaane; verbatim = jo bola wahi likho
            .put("inputAudioTranscription", JSONObject().put("languageCodes", JSONArray()))
        return JSONObject().put("setup", setup).toString()
    }

    private fun connect() {
        if (stopped || dead) return
        ready = false
        val m = MODELS[mi.coerceIn(0, MODELS.size - 1)]
        val req = Request.Builder().url("$URL?key=$key").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupJson(m))
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) onServer(text, m) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (webSocket === ws) onServer(bytes.utf8(), m) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Exception) {}
                if (webSocket === ws) dropped(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) dropped(response?.code ?: 0, t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun dropped(code: Int, reason: String) {
        val wasReady = ready
        ready = false
        ws = null
        if (stopped) return
        val r = reason.lowercase()
        log("टूटा code=$code $reason")
        // setup hi nahi hua aur model/key ki dikkat -> agla model, phir band
        if (!wasReady && (r.contains("model") || r.contains("not found") || r.contains("not supported") ||
                r.contains("permission") || r.contains("api key") || r.contains("quota") || r.contains("exhausted") ||
                code == 404 || code == 1007 || code == 1008 || code == 403 || code == 429)) {
            if (mi + 1 < MODELS.size) { mi++; connect(); return }
            dead = true
            log("इस key पर लाइव कैप्शन नहीं चलता - पुराना तरीका चलेगा")
            return
        }
        val lasted = System.currentTimeMillis() - upAt
        fails = if (wasReady && lasted > 20_000) 0 else fails + 1
        if (fails >= 6) { dead = true; log("बार-बार टूट रहा है - कैप्शन बंद"); return }
        val wait = minOf(10_000L, 300L shl fails)
        Thread {
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
            if (!stopped) connect()
        }.apply { isDaemon = true; start() }
    }

    private fun onServer(msg: String, m: String) {
        val o = try { JSONObject(msg) } catch (_: Exception) { return }
        if (o.has("setupComplete")) {
            ready = true; upAt = System.currentTimeMillis()
            log("जुड़ गया [$m]")
            return
        }
        if (o.has("goAway")) {                   // server naya connection chahta hai
            try { ws?.close(1000, "goAway") } catch (_: Exception) {}
            return
        }
        val sc = o.optJSONObject("serverContent") ?: return
        val fin = sc.optJSONObject("inputTranscription")?.optString("text").orEmpty()
        val tmp = sc.optJSONObject("interimInputTranscription")?.optString("text").orEmpty()
        if (fin.isBlank() && tmp.isBlank()) return
        val shown = synchronized(lock) {
            if (fresh) { committed.clear(); interim = ""; fresh = false }
            if (fin.isNotBlank()) {                 // pakka text jeetta hai
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(fin.trim())
                interim = ""
            } else interim = tmp
            join(committed.toString(), interim)
        }
        if (shown.isNotBlank()) onText(shown)
    }
}
