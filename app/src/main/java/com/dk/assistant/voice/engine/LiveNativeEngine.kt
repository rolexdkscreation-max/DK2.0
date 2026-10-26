package com.dk.assistant.voice.engine

import android.widget.LinearLayout
import android.content.Context
import android.util.Base64
import com.dk.assistant.GeminiLive
import com.dk.assistant.Prefs
import com.dk.assistant.VoiceCatalog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * इंजन 3: "Gemini Live सीधी आवाज़" (native audio) - Gemini Live खुद लिखे और खुद बोले, बँटवारे के बिना.
 * यह DK का पुराना (v3.67 तक का) रास्ता है - चुनने पर GeminiLive वैसा ही चलता है जैसा पहले.
 * डिफ़ॉल्ट बंद. आवाज़ वही "gemini_voice" (पुरानी सेटिंग) जो Live पहले से इस्तेमाल करता है.
 *
 * [speak] सिर्फ़ ▶ सुनो (और फ़ॉलबैक जाँच) के लिए: एक छोटा अलग Live सत्र जो दिया वाक्य ज्यों-का-त्यों बोलता है.
 */
object LiveNativeEngine : VoiceEngine {
    override val id = VoiceEngines.LIVE_NATIVE
    override val title = "Gemini Live सीधी आवाज़"
    override val note = "Live खुद लिखता और खुद बोलता है (native audio) • नीचे का भावना/स्टाइल Live के मुख्य निर्देश में जुड़ता है"
    override val split = false
    override val defaultOn = false

    private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var cur: WebSocket? = null

    override fun voices(): List<Pair<String, String>> {
        val names = listOf("Sulafat", "Kore", "Leda", "Aoede", "Vindemiatrix")
        return names.map { it to "$it — ${VoiceCatalog.GEMINI_INFO[it] ?: ""} (महिला)" }
    }
    override fun voice(c: Context): String = Prefs.geminiVoice(c)
    override fun setVoice(c: Context, v: String) { Prefs.edit(c) { putString("gemini_voice", v) } }

    /** v3.69: Live सीधी आवाज़ का अपना "भावना / स्टाइल" - Live सत्र के system instruction में जुड़ता है (GeminiLive.setupJson) */
    fun style(c: Context): String = VoiceEngines.sp(c).getString("live_native_style", "") ?: ""

    /** system instruction में जोड़ने लायक पंक्ति (खाली स्टाइल = कुछ नहीं) */
    fun styleNote(style: String): String {
        val s = style.trim()
        return if (s.isBlank()) "" else "\n\nVOICE STYLE (how you sound, every reply, start to end): $s"
    }

    override fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {
        ui.field(box, "भावना / स्टाइल (Live के मुख्य निर्देश में)", style(c), "जैसे: धीमी, कोमल, भावुक आवाज़ में, प्यार से बोलो", multiLine = true) { v ->
            VoiceEngines.sp(c).edit().putString("live_native_style", v.trim()).apply()
        }
        ui.hint(box, "Live उसी भाव में बोलेगा। चलती बातचीत में नया निर्देश अगली बार जुड़ने पर लगेगा (DK लाइव बंद करके फिर चालू करें)।")
    }

    override fun notReady(c: Context): String? = if (GeminiLive.key(c).isBlank()) "DK लाइव की Gemini key नहीं डाली" else null

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        val key = GeminiLive.key(c).ifBlank { throw IOException("DK लाइव की Gemini key नहीं") }
        val model = Prefs.liveModel(c).ifBlank { GeminiLive.FALLBACK_MODELS.first() }
        val ready = CountDownLatch(1); val done = CountDownLatch(1)
        var err: String? = null
        var got = false
        val ws = http.newWebSocket(Request.Builder().url("$URL?key=$key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put("setup", JSONObject()
                    .put("model", "models/$model")
                    .put("generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                            .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                        "तुम सिर्फ़ आवाज़ हो। यूज़र जो वाक्य दे, उसे ज्यों-का-त्यों, गर्मजोशी और भावना से, हिंदी में बोलो। " +
                            "एक भी शब्द जोड़ना/बदलना/जवाब देना नहीं। शांत, हमदर्द लहजे में, मध्यम गति से, वाक्यों के बीच हल्के ठहराव के साथ बोलो।" + styleNote(style(c)))))))
                webSocket.send(setup.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(bytes.utf8())
            private fun handle(s: String) {
                val o = try { JSONObject(s) } catch (_: Exception) { return }
                if (o.has("setupComplete")) { ready.countDown(); return }
                val sc = o.optJSONObject("serverContent") ?: return
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                        if (!d.optString("mimeType").startsWith("audio/pcm")) continue
                        val pcm = try { Base64.decode(d.optString("data"), Base64.DEFAULT) } catch (_: Exception) { continue }
                        if (pcm.isNotEmpty()) { got = true; out.write(pcm) }
                    }
                }
                if (sc.optBoolean("turnComplete") || sc.optBoolean("interrupted")) done.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                err = response?.let { "Live HTTP ${it.code}" } ?: t.message; ready.countDown(); done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!got && err == null) err = "Live ने बंद किया ($code ${reason.take(60)})"; ready.countDown(); done.countDown()
            }
        })
        cur = ws
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || err != null) throw IOException(err ?: "Live setup 10 s में नहीं हुआ")
            ws.send(JSONObject().put("clientContent", JSONObject()
                .put("turns", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "यह वाक्य बोलो: $text")))))
                .put("turnComplete", true)).toString())
            if (!done.await(20, TimeUnit.SECONDS) && !got) throw IOException("Live ने 20 s में आवाज़ नहीं दी")
            if (!got && cur === ws) throw IOException(err ?: "Live ने आवाज़ नहीं दी")
        } finally {
            try { ws.close(1000, "bye") } catch (_: Exception) {}
            if (cur === ws) cur = null
        }
    }

    override fun stop() { cur?.let { try { it.cancel() } catch (_: Exception) {} }; cur = null }
}
