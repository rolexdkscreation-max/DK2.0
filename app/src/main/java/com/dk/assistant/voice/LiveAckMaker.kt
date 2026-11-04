package com.dk.assistant.voice

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.GeminiLive
import com.dk.assistant.Prefs
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Yes Boss" वगैरह DK की अपनी Gemini Live आवाज़ में एक बार बनाकर फ़ोन में ([HeyDk.liveAckFile]). नियम [LiveAckRules].
 * सिर्फ़ तब जब Live बंद हो (एक छोटा अलग connection, कुछ सेकंड; Live/caption के connection से टकराव नहीं).
 * बोलने वाला हिस्सा/Live का setup नहीं बदला - यह अलग, सिर्फ़ फ़ाइल बनाता है.
 */
object LiveAckMaker {
    private val busy = AtomicBoolean(false)
    @Volatile private var failAt = 0L
    @Volatile var lastResult = ""
        private set

    /** कोई ack बनना बाकी है? */
    fun missing(c: Context): Boolean = HeyDk.clipTexts(c).any { !HeyDk.hasLiveAck(c, it) }

    /** पीछे से बनाओ अगर ज़रूरत (Live बंद हो तब). कहीं से भी बुलाओ */
    fun ensureAsync(c: Context, why: String, delayMs: Long = 0L) {
        val app = c.applicationContext
        Thread {
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                val now = System.currentTimeMillis()
                if (!LiveAckRules.shouldMake(missing(app), DKState.live, Prefs.liveGeminiKeys(app).isNotEmpty(), now - failAt, busy.get())) return@Thread
                make(app, why)
            } catch (_: Exception) {}
        }.start()
    }

    /** IO thread पर. @return कितने बने */
    fun make(c: Context, why: String): Int {
        if (!busy.compareAndSet(false, true)) return 0
        try {
            val keys = Prefs.liveGeminiKeys(c)
            if (keys.isEmpty()) return 0
            val voice = Prefs.geminiVoice(c)
            val todo = HeyDk.clipTexts(c).filter { !HeyDk.hasLiveAck(c, it) }
            if (todo.isEmpty()) return 0
            for (model in LiveAckRules.models(Prefs.liveModel(c), GeminiLive.FALLBACK_MODELS)) {
                val made = try { session(c, keys[0], model, com.dk.assistant.LiveVoice.start(model, voice, Prefs.liveExtrasOff(c)).voice, voice, todo) } catch (e: Exception) { DKLog.d("Yes Boss आवाज़ ($model): ${e.message}"); -1 }
                if (made > 0) {
                    lastResult = "$made बने ($model, $voice)"
                    DKLog.d("power बटन वाला '${todo.joinToString()}' DK की Live आवाज़ ($voice) में बना - $lastResult ($why)")
                    failAt = 0L
                    return made
                }
            }
            failAt = System.currentTimeMillis()
            lastResult = "नहीं बना"
            DKLog.d("power बटन वाली आवाज़ नहीं बनी ($why) - ${LiveAckRules.RETRY_MS / 60_000} मिनट बाद फिर; तब तक टिंग")
            return 0
        } finally { busy.set(false) }
    }

    private fun setup(model: String, voice: String): String = JSONObject().put("setup", JSONObject()
        .put("model", "models/$model")
        .put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", LiveAckRules.INSTRUCTION))))
        .put("outputAudioTranscription", JSONObject())).toString()

    /** एक connection पर सारे बोल एक के बाद एक. @return कितने सही बने (-1 = जुड़ा ही नहीं) */
    private fun session(c: Context, key: String, model: String, liveVoice: String, voice: String, texts: List<String>): Int {
        val ev = LinkedBlockingQueue<JSONObject>()
        val closed = JSONObject().put("_closed", true)
        val req = Request.Builder().url(GeminiLive.URL).header("x-goog-api-key", key).build()
        val ws = com.dk.assistant.LiveNet.http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { try { ev.put(JSONObject(text)) } catch (_: Exception) {} }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { try { ev.put(JSONObject(bytes.utf8())) } catch (_: Exception) {} }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ev.put(closed) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ev.put(closed) }
        })
        try {
            ws.send(setup(model, liveVoice))
            while (true) {
                val o = ev.poll(8, TimeUnit.SECONDS) ?: return -1
                if (o.has("_closed")) return -1
                if (o.has("setupComplete")) break
            }
            var made = 0
            for (t in texts) {
                ws.send(JSONObject().put("realtimeInput", JSONObject().put("text", LiveAckRules.prompt(t))).toString())
                val pcm = ByteArrayOutputStream(); val said = StringBuilder()
                val until = System.currentTimeMillis() + LiveAckRules.TURN_TIMEOUT_MS
                var done = false
                while (!done) {
                    val left = until - System.currentTimeMillis(); if (left <= 0) break
                    val o = ev.poll(left, TimeUnit.MILLISECONDS) ?: break
                    if (o.has("_closed")) return if (made > 0) made else -1
                    val sc = o.optJSONObject("serverContent") ?: continue
                    sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { ps ->
                        for (i in 0 until ps.length()) ps.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                            ?.takeIf { it.isNotEmpty() }?.let { pcm.write(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
                    }
                    sc.optJSONObject("outputTranscription")?.optString("text")?.let { said.append(it) }
                    if (sc.optBoolean("turnComplete")) done = true
                }
                val clip = LiveAckRules.trim(pcm.toByteArray())
                if (done && LiveAckRules.ok(t, said.toString(), clip.size)) {
                    val f = HeyDk.liveAckFile(c, voice, t); f.parentFile?.mkdirs(); f.writeBytes(clip); made++
                } else DKLog.d("'$t' की आवाज़ ठीक नहीं बनी (बोला: '${said.toString().trim()}', ${clip.size * 1000L / (LiveAckRules.RATE * 2)} ms) - छोड़ी")
            }
            return made
        } finally { try { ws.close(1000, "ack बना") } catch (_: Exception) {} }
    }
}
