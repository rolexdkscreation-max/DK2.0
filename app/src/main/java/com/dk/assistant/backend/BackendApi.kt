package com.dk.assistant.backend

import android.content.Context
import com.dk.assistant.LoserException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DK बैकएंड (Cloudflare Worker) से बात - हर अनुरोध के साथ सिर्फ़ Firebase ID token (Bearer).
 * पता [BackendConfig] से - यहाँ कुछ भी hard-code नहीं.
 */
object BackendApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http by lazy {
        com.dk.assistant.NetFix.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build()
    }

    /** आख़िरी बार backend ने जो सीमा बताई (Settings / 1 मिनट वाली चेतावनी) */
    @Volatile var lastQuota: BackendWire.Quota? = null
    @Volatile var lastLevel: String = ""

    private fun req(c: Context, path: String, force: Boolean = false): Request.Builder =
        Request.Builder().url(BackendConfig.url + path).header("Authorization", "Bearer " + BackendSession.idToken(c, force))

    /** 401 = token पुराना -> एक बार नया लेकर फिर */
    private fun <T> call(c: Context, path: String, build: (Request.Builder) -> Request.Builder, read: (okhttp3.Response) -> T): T {
        for (attempt in 0..1) {
            val r = http.newCall(build(req(c, path, force = attempt == 1)).build()).execute()
            r.use {
                headers(it)
                if (it.code == 401 && attempt == 0) return@use
                if (!it.isSuccessful) throw BackendWire.error(it.code, it.body?.string())
                return read(it)
            }
        }
        throw BackendException(401, BackendWire.AUTH_INVALID, "पहचान नहीं हुई")
    }

    private fun headers(r: okhttp3.Response) {
        r.header("x-dk-level")?.let { lastLevel = it }
        val v = r.header("x-dk-voice-left")?.toIntOrNull()
        val ch = r.header("x-dk-chat-left")?.toIntOrNull()
        if (v != null || ch != null) lastQuota = BackendWire.Quota(v ?: -1, ch ?: -1, r.header("x-dk-warn") == "1")
    }

    private fun getJson(c: Context, path: String): String = call(c, path, { it.get() }) { it.body?.string().orEmpty() }
    private fun send(c: Context, path: String, method: String, body: RequestBody?): String =
        call(c, path, { it.method(method, body) }) { it.body?.string().orEmpty() }
    private fun postJson(c: Context, path: String, o: JSONObject) = send(c, path, "POST", o.toString().toRequestBody(JSON))

    /** चैट - टुकड़ों में (SSE). जवाब का पूरा text लौटाता है */
    fun chatStream(c: Context, body: JSONObject, onDelta: (String) -> Unit): String {
        val sb = StringBuilder()
        call(c, "/ai/chat", { it.post(body.put("stream", true).toString().toRequestBody(JSON)).header("Accept", "text/event-stream") }) { r ->
            val src = r.body?.source() ?: throw BackendException(0, "EMPTY", "सर्वर से खाली जवाब")
            while (true) {
                val line = src.readUtf8Line() ?: break
                when (val ev = BackendWire.sse(line)) {
                    is BackendWire.Ev.Text -> { sb.append(ev.t); try { onDelta(ev.t) } catch (e: LoserException) { r.close(); throw e } }
                    is BackendWire.Ev.Done -> {
                        ev.quota?.let { lastQuota = it }
                        if (ev.error != null && sb.isEmpty()) throw BackendException(503, "AI_UNAVAILABLE", "अभी कोई AI जवाब नहीं दे पाया")
                        break
                    }
                    null -> Unit
                }
            }
        }
        return sb.toString()
    }

    fun chat(c: Context, body: JSONObject): String {
        val b = postJson(c, "/ai/chat", body.put("stream", false))
        val o = JSONObject(b)
        BackendWire.quota(o.optJSONObject("quota"))?.let { lastQuota = it }
        return o.optString("text")
    }

    /** DK लाइव का टिकट (अपनी Gemini key नहीं) - [com.dk.assistant.LiveDkServer] ही बुलाता है */
    /** [next] = 22 नवंबर 2026: मियाद से पहले अगला टिकट (चालू के ठीक बाद से) - बिना कटे connection बदलने के लिए ([LiveDkServer.renew]) */
    fun liveToken(c: Context, next: Boolean = false): BackendWire.LiveTicket =
        BackendWire.liveTicket(postJson(c, "/live/token", if (next) JSONObject().put("next", true) else JSONObject()))
            .also { t -> t.quota?.let { lastQuota = it } }

    /** मुफ़्त वाले की आवाज़ (अपनी key) का समय - [VoiceGate] ही बुलाता है */
    fun voiceGrant(c: Context): BackendWire.VoiceGrant =
        BackendWire.voiceGrant(postJson(c, "/voice/grant", JSONObject())).also { g -> lastLevel = g.level; g.quota?.let { lastQuota = it } }

    /** 20 नवंबर 2026: इमेज - सर्वर पर Cloudflare FLUX (सिर्फ़ मालिक) -> {image: base64, mime, left_approx}. सिर्फ़ [com.dk.assistant.FluxImage] बुलाता है */
    fun image(c: Context, prompt: String): JSONObject = JSONObject(postJson(c, "/image", JSONObject().put("prompt", prompt)))
    fun imageStatus(c: Context): JSONObject = JSONObject(getJson(c, "/image/status"))

    /** [deep] = डीप रिसर्च: सर्वर Google वाली खोज नहीं करता (सिर्फ़ Tavily/Exa/Linkup/Firecrawl). [only] = सिर्फ़ यह एक कंपनी */
    fun search(c: Context, q: String, deep: Boolean, only: String = ""): String =
        postJson(c, "/search", JSONObject().put("query", q).put("n", if (deep) 8 else 5)
            .apply {
                if (deep) put("deep", true)
                if (only.isNotBlank()) put("only", only)
            })

    /** 18 नवंबर 2026: डीप रिसर्च में वेबसाइटें पूरी पढ़ना (सर्वर की Tavily/Exa/Firecrawl) - url -> पाठ */
    fun read(c: Context, urls: List<String>): Map<String, String> =
        BackendWire.readPages(postJson(c, "/search/read", JSONObject().put("urls", org.json.JSONArray(urls.take(8)))))

    fun status(c: Context): BackendWire.Status = BackendWire.status(getJson(c, "/license/status")).also {
        val wasOwner = BackendSession.owner(c)
        lastLevel = it.level; it.quota?.let { q -> lastQuota = q }; BackendSession.markOwner(c, it.owner)
        WelcomeBoss.noteStatus(c, wasOwner, BackendSession.owner(c))
        Plan.note(c, it.level, it.expiresAt)
        Plan.noteServerAi(c, it.serverAi)
    }

    fun activate(c: Context, key: String): BackendWire.Status {
        postJson(c, "/license/activate", JSONObject().put("key", key.trim().uppercase()))
        com.dk.assistant.LiveDkServer.clearStop()      // नया लाइसेंस = आज की रोक हटी, Live फिर
        VoiceGate.clear(c)
        return status(c)
    }

    // ---- मालिक का admin (backend खुद मालिक जाँचता है; ऐप में बस दिखाना)
    fun adminCreate(c: Context, level: String, days: Int, maxDevices: Int, note: String): Pair<String, String> {
        val o = JSONObject(postJson(c, "/admin/licenses", JSONObject().put("level", level).put("days", days).put("max_devices", maxDevices).put("note", note)))
            .optJSONObject("license") ?: JSONObject()
        return o.optString("key") to o.optString("id")
    }
    fun adminList(c: Context) = BackendWire.licenses(getJson(c, "/admin/licenses"))
    fun adminRevoke(c: Context, id: String) { postJson(c, "/admin/licenses/$id/revoke", JSONObject()) }

    // ---- काम (कोडिंग / कंप्यूटर) - History + बाद में वहीं से
    fun taskPut(c: Context, o: JSONObject) { postJson(c, "/tasks", o) }
    fun taskPatch(c: Context, id: String, o: JSONObject) { send(c, "/tasks/$id", "PATCH", o.toString().toRequestBody(JSON)) }
    fun tasks(c: Context) = BackendWire.tasks(getJson(c, "/tasks"))
}
