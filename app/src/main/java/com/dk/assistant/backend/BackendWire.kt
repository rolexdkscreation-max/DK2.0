package com.dk.assistant.backend

import com.dk.assistant.AiException
import org.json.JSONArray
import org.json.JSONObject

/**
 * backend की गलती. [reason] = मशीन वाला कोड (LICENSE_REQUIRED, NEED_PREMIUM, STT_LIMIT ...), [say] = DK जो बोले/लिखे.
 * AiException से - ताकि पुराना fallback/गलती वाला code (429, 5xx) वैसे ही समझे.
 */
class BackendException(status: Int, val reason: String, message: String, val say: String = message) : AiException(status, message) {
    /** यह गलती = बातचीत रोक दो (आज का मुफ़्त समय/संदेश पूरे) */
    val stopsTalk get() = reason == BackendWire.LICENSE_REQUIRED || reason == BackendWire.NEED_OWN_KEY
    /** दोबारा कोशिश बेकार (लाइसेंस/स्तर) */
    val final get() = reason in setOf(BackendWire.LICENSE_REQUIRED, BackendWire.NEED_PREMIUM, BackendWire.CHAT_LIMIT, BackendWire.NEED_OWN_KEY)
}

/** backend के जवाब पढ़ना - बिना Android (JVM tests) */
object BackendWire {
    const val LICENSE_REQUIRED = "LICENSE_REQUIRED"
    const val NEED_PREMIUM = "NEED_PREMIUM"
    /** मुफ़्त वाले को सर्वर की AI नहीं - अपनी Gemini key डालें (18 नवंबर 2026, मालिक) */
    const val NEED_OWN_KEY = "NEED_OWN_KEY"
    const val CHAT_LIMIT = "CHAT_LIMIT"
    const val STT_LIMIT = "STT_LIMIT"
    const val DB_LIMIT = "DB_LIMIT"
    const val AUTH_INVALID = "AUTH_INVALID"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"

    /** मालिक के शब्द, बिल्कुल वही */
    const val VOICE_DONE = "आज की मुफ़्त बातचीत पूरी हो गई। इससे ज़्यादा बात करने के लिए पहले लाइसेंस की लें।"
    const val PREMIUM_HI = "यह सुविधा प्रीमियम लाइसेंस के साथ मिलती है। Settings → लाइसेंस की में की डालें।"
    const val WARN_HI = "आज की मुफ़्त आवाज़ वाली बातचीत का बस एक मिनट बचा है। उसके बाद भी आप मुझसे चैट में जितनी चाहें बात कर सकते हैं।"
    /** 18 नवंबर 2026 (मालिक): मुफ़्त वाले के 10 मिनट की आवाज़ पूरी = DK यही बोले/लिखे और चैट अपने-आप खुले (चैट पर कोई सीमा नहीं) */
    const val VOICE_DONE_CHAT = "आज की 10 मिनट वाली मुफ़्त आवाज़ पूरी हो गई। अब आप मुझसे चैट में बात कर सकते हैं — जितनी चाहें उतनी, बिना किसी सीमा के। आवाज़ में और बात करने के लिए लाइसेंस की लें।"
    const val THINKING = "सोच रहा हूँ..."

    fun error(status: Int, body: String?): BackendException {
        val o = try { JSONObject(body ?: "") } catch (_: Exception) { null }
        val code = o?.optString("code").orEmpty().ifBlank { if (status == 401) AUTH_INVALID else "HTTP_$status" }
        val msg = o?.optString("error").orEmpty().ifBlank { "सर्वर से जवाब नहीं मिला ($status)" }
        val say = when (code) {
            LICENSE_REQUIRED, NEED_OWN_KEY -> o?.optString("say").orEmpty().ifBlank { msg }
            NEED_PREMIUM -> if (o?.optString("need") == "basic") msg else PREMIUM_HI
            else -> msg
        }
        return BackendException(status, code, msg, say)
    }

    data class Quota(val voiceLeft: Int, val chatLeft: Int, val warn: Boolean)

    fun quota(o: JSONObject?): Quota? = o?.let {
        Quota(it.optInt("voice_left", -1), it.optInt("chat_left", -1), it.optBoolean("warn", false))
    }

    sealed class Ev {
        data class Text(val t: String) : Ev()
        data class Done(val provider: String, val model: String, val quota: Quota?, val error: String?) : Ev()
    }

    /** एक SSE लाइन ("data: {...}") -> घटना; बाकी (खाली/टिप्पणी) = null */
    fun sse(line: String): Ev? {
        if (!line.startsWith("data:")) return null
        val o = try { JSONObject(line.substring(5).trim()) } catch (_: Exception) { return null }
        if (o.optBoolean("done")) {
            return Ev.Done(o.optString("provider"), o.optString("model"), quota(o.optJSONObject("quota")), o.optString("error").ifBlank { null })
        }
        val t = o.optString("t")
        return if (t.isEmpty()) null else Ev.Text(t)
    }

    /** चैट का अनुरोध. keys कभी नहीं - backend ऐप की भेजी key लेता ही नहीं */
    fun chatBody(system: String, history: List<Pair<String, String>>, prompt: String, task: String, mode: String, ticket: String?): JSONObject {
        val msgs = JSONArray()
        history.filter { it.second.isNotBlank() }.forEach { (role, text) ->
            msgs.put(JSONObject().put("role", if (role == "user") "user" else "assistant").put("content", text))
        }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        return JSONObject().put("system", system).put("messages", msgs).put("task", task).put("mode", mode).apply {
            if (!ticket.isNullOrBlank()) put("voice_ticket", ticket)
        }
    }

    data class Stt(val text: String, val seconds: Int, val ticket: String?, val quota: Quota?)

    fun stt(body: String): Stt {
        val o = JSONObject(body)
        return Stt(o.optString("text").trim(), o.optInt("seconds"), o.optString("voice_ticket").ifBlank { null }, quota(o.optJSONObject("quota")))
    }

    /**
     * 18 नवंबर 2026: DK लाइव बिना अपनी key - /live/token का टिकट (Google का ephemeral token, असली key नहीं).
     * पता सिर्फ़ Google का Live WebSocket माना जाता है - कोई और पता = टिकट नहीं (आवाज़ कहीं और न जाए).
     */
    data class LiveTicket(val token: String, val wsUrl: String, val expiresAt: Long, val quota: Quota?)

    const val LIVE_WS_PREFIX = "wss://generativelanguage.googleapis.com/ws/"

    fun liveTicket(body: String): LiveTicket {
        val o = JSONObject(body)
        val t = o.optString("token")
        val u = o.optString("ws_url")
        if (t.isBlank() || !u.startsWith(LIVE_WS_PREFIX)) throw BackendException(0, "BAD_TICKET", "सर्वर से Live का सही टिकट नहीं मिला")
        return LiveTicket(t, u, o.optLong("expires_at"), quota(o.optJSONObject("quota")))
    }

    /** /voice/grant: मुफ़्त वाले की आवाज़ का अगला हिस्सा. now/until = सर्वर की घड़ी */
    data class VoiceGrant(val level: String, val metered: Boolean, val now: Long, val until: Long, val quota: Quota?)

    fun voiceGrant(body: String): VoiceGrant {
        val o = JSONObject(body)
        return VoiceGrant(o.optString("level", "free"), o.optBoolean("metered", true), o.optLong("now"), o.optLong("until"),
            quota(o.optJSONObject("quota")))
    }

    data class Status(val level: String, val expiresAt: Long, val owner: Boolean, val quota: Quota?)

    fun status(body: String): Status {
        val o = JSONObject(body)
        return Status(o.optString("level", "free"), if (o.isNull("expires_at")) 0L else o.optLong("expires_at"), o.optBoolean("owner"),
            quota(o.optJSONObject("quota")))
    }

    fun levelHi(level: String): String = when (level) {
        "owner" -> "मालिक (सब कुछ, कोई सीमा नहीं)"
        "premium" -> "प्रीमियम (सब कुछ)"
        "basic" -> "बेसिक (चैट, खोज, डीप रिसर्च)"
        else -> "मुफ़्त (रोज़ 10 मिनट बातचीत)"
    }

    /**
     * /search का जवाब -> वही कच्चा रूप जो [com.dk.assistant.WebCite.normalizeResults] पहले से पढ़ता है
     * (Tavily/Exa: results[], Google: items[]) - ताकि स्रोत-चिप पुराने तरीके से ही बनें.
     */
    fun searchRaw(body: String): Pair<String, String> {
        val o = JSONObject(body)
        val res = o.optJSONArray("results") ?: JSONArray()
        val results = JSONArray()
        val items = JSONArray()
        for (i in 0 until res.length()) {
            val r = res.optJSONObject(i) ?: continue
            val t = r.optString("title"); val u = r.optString("url"); val s = r.optString("snippet")
            results.put(JSONObject().put("title", t).put("url", u).put("content", s).put("text", s))
            items.put(JSONObject().put("title", t).put("link", u).put("snippet", s))
        }
        // Linkup/Firecrawl के नतीजे भी Tavily जैसे results[] में - ऐप उसी तरह पढ़ता है
        val provider = when (o.optString("provider")) { "exa" -> "Exa"; "google" -> "Google"; else -> "Tavily" }
        return provider to JSONObject().put("results", results).put("items", items).toString()
    }

    /** /search/read का जवाब: url -> पूरा पाठ (खाली पाठ छोड़ो) */
    fun readPages(body: String): Map<String, String> {
        val arr = JSONObject(body).optJSONArray("pages") ?: JSONArray()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val u = p.optString("url"); val t = p.optString("text").trim()
            if (u.startsWith("http") && t.isNotBlank()) out[u] = t
        }
        return out
    }

    /** Settings में की डालते समय रूप की जाँच (असली जाँच backend पर) */
    fun keyShape(k: String): Boolean = Regex("^DK-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(k.uppercase().replace(Regex("\\s+"), ""))

    data class License(val id: String, val level: String, val active: Boolean, val expiresAt: Long, val maxDevices: Int, val devices: Int, val note: String)

    fun licenses(body: String): List<License> {
        val a = JSONObject(body).optJSONArray("licenses") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            License(it.optString("id"), it.optString("level"), it.optInt("active") == 1, it.optLong("expires_at"),
                it.optInt("max_devices"), it.optInt("devices"), it.optString("note").takeIf { n -> n != "null" }.orEmpty())
        }
    }

    data class Task(val id: String, val title: String, val status: String, val step: String, val progress: Int,
                    val branch: String, val prUrl: String, val error: String, val updatedAt: Long, val buildStatus: String)

    fun tasks(body: String): List<Task> {
        val a = JSONObject(body).optJSONArray("tasks") ?: return emptyList()
        fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Task(it.s("id"), it.s("title"), it.s("status"), it.s("step"), it.optInt("progress"), it.s("branch"), it.s("pr_url"),
                it.s("error"), it.optLong("updated_at"), it.optJSONObject("meta")?.optString("build_status").orEmpty())
        }
    }

    fun statusHi(s: String) = when (s) {
        "running" -> "चल रहा है"; "waiting" -> "इंतज़ार"; "done" -> "पूरा"; "failed" -> "फ़ेल"; "stopped" -> "रुका"; "queued" -> "कतार में"
        else -> s
    }
}
