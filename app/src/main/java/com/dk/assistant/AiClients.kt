package com.dk.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Turn(val role: String, val text: String)   // role = "user" ya "model"

/** Internet search ka jawab + sources (title, link) */
data class SearchResult(val text: String, val sources: List<Pair<String, String>>)

class AiException(val code: Int, message: String) : IOException(message)

/** Har AI company se baat karne ka ek hi tareeka. */
interface AiClient {
    val label: String
    fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String

    /**
     * Jawab tukdon me (jaise-jaise AI likhta hai) - pehla vakya aate hi DK bolna shuru kar deta hai.
     * onDelta har naye tukde ke saath. Poora text return.
     */
    fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        val t = complete(system, history, prompt, null); onDelta(t); return t
    }
}

/** Race me haar gaya (dusri key ne pehle jawab de diya) - chupchap band */
class LoserException : RuntimeException("loser")

/** Baatcheet ke liye: jaldi haar maano, dusri key try karo */
private val fastHttp = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/** SSE (data: ...) lines padho. */
private fun sse(req: Request, onData: (String) -> Unit) {
    val call = fastHttp.newCall(req)
    call.execute().use { r ->
        if (!r.isSuccessful) {
            val body = r.body?.string() ?: ""
            val msg = try { JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(250) }
                catch (e: Exception) { body.take(250) }
            throw AiException(r.code, msg)
        }
        val src = r.body?.source() ?: throw AiException(0, "khali stream")
        try {
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.removePrefix("data:").trim()
                if (d == "[DONE]") break
                if (d.isNotEmpty()) onData(d)
            }
        } catch (e: LoserException) { call.cancel(); throw e }
    }
}

/** <think>...</think> wala hissa (sochne wale model) bolna nahi */
private class ThinkFilter {
    private var inThink = false
    private val pend = StringBuilder()
    fun feed(s: String): String {
        pend.append(s)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val e = pend.indexOf("</think>")
                if (e < 0) { if (pend.length > 8) pend.delete(0, pend.length - 8); return out.toString() }
                pend.delete(0, e + 8); inThink = false
            } else {
                val b = pend.indexOf("<think>")
                if (b < 0) {
                    // adhoora "<thi" bacha ke rakho
                    val keep = (1..6).firstOrNull { k -> pend.length >= k && "<think>".startsWith(pend.substring(pend.length - k)) } ?: 0
                    out.append(pend, 0, pend.length - keep); pend.delete(0, pend.length - keep)
                    return out.toString()
                }
                out.append(pend, 0, b); pend.delete(0, b + 7); inThink = true
            }
        }
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS)      // 35 sec se zyada wait nahi - phir dusra AI
    .retryOnConnectionFailure(true)
    .build()

private val JSON_TYPE = "application/json".toMediaType()

private fun execute(req: Request): String = http.newCall(req).execute().use { r ->
    val body = r.body?.string() ?: ""
    if (!r.isSuccessful) {
        val msg = try {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(250)
        } catch (e: Exception) { body.take(250) }
        throw AiException(r.code, msg)
    }
    body
}

/** Google Gemini (apna alag format). Ek model fail ho to dusre Gemini model try karta hai. */
class GeminiClient(private val key: String, private val model: String) : AiClient {
    /** true = baatcheet: sabse kam sochna, chhota jawab = tez */
    var fast = false
    /** true = sirf yahi model (auto-setup me naapne ke liye) */
    var only = false
    /** Auto-setup ke paaye tez free models (purane naam ki jagah) */
    var backups: List<String> = BACKUP_MODELS
    override var label = "Gemini ($model)"
        private set

    companion object {
        // Inki free limit alag-alag hoti hai - ek ki khatam ho to dusra chal jata hai
        val BACKUP_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash")
        /** Mushkil sawaal (research) ke liye zyada samajhdar model pehle */
        val DEEP_MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash")
        /** Sabse mushkil sawaal (gehri soch): sabse taakatwar model pehle, na chale to agla */
        val THINK_MODELS = listOf("gemini-4-argon", "gemini-3.1-pro-preview", "gemini-3.8-flash")   // argon/pro = paid key; free key par 3.8 Flash (high thinking)
    }

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        val models = if (only) listOf(model) else (listOf(model) + backups).distinct()
        var last: AiException? = null
        for (m in models) {
            try {
                val r = call(m, system, history, prompt, imageB64)
                label = "Gemini ($m)"
                return r
            } catch (e: AiException) {
                last = e
                val tryNext = e.code == 404 || e.code == 429 || e.code >= 500 ||
                    (e.code == 400 && (e.message ?: "").contains("model", ignoreCase = true) &&
                        !(e.message ?: "").contains("API key", ignoreCase = true))
                DKLog.d("Gemini $m -> ${e.code} ${e.message}")
                if (!tryNext) throw e
            }
        }
        throw last ?: AiException(0, "Gemini fail")
    }

    override fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        // chuna hua model + ek backup (zyada nahi - warna der hoti hai; dusri key race me hai)
        val models = if (only) listOf(model) else (listOf(model) + backups).distinct().take(3)
        var last: AiException? = null
        for (m in models) {
            var thinking = true
            var attempt = 0
            while (attempt < 2) {
                attempt++
                var gotAny = false
                try {
                    val full = StringBuilder()
                    streamOnce(m, system, history, prompt, thinking) { d -> gotAny = true; full.append(d); onDelta(d) }
                    label = "Gemini ($m)"
                    return full.toString()
                } catch (e: AiException) {
                    if (gotAny) throw e
                    last = e
                    DKLog.d("Gemini stream $m -> ${e.code} ${e.message}")
                    // "thinking" na samjhe to bina uske dobara (wahi model)
                    if (e.code == 400 && thinking && (e.message ?: "").contains("think", true)) { thinking = false; continue }
                    val tryNext = e.code == 404 || e.code == 429 || e.code >= 500 ||
                        (e.code == 400 && (e.message ?: "").contains("model", true) && !(e.message ?: "").contains("API key", true))
                    if (!tryNext) throw e
                    break   // agla model
                }
            }
        }
        throw last ?: AiException(0, "Gemini fail")
    }

    private fun streamOnce(m: String, system: String, history: List<Turn>, prompt: String, thinking: Boolean, onDelta: (String) -> Unit) {
        val contents = JSONArray()
        history.forEach {
            contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        val gen = JSONObject().put("maxOutputTokens", 700)
        if (thinking && m.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        if (thinking && m.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        sse(req) { d ->
            val o = try { JSONObject(d) } catch (_: Exception) { return@sse }
            o.optJSONObject("error")?.let { throw AiException(it.optInt("code", 0), it.optString("message")) }
            val p = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@sse
            for (i in 0 until p.length()) {
                val x = p.optJSONObject(i) ?: continue
                if (!x.optBoolean("thought", false)) x.optString("text").takeIf { it.isNotEmpty() }?.let(onDelta)
            }
        }
    }

    /** Google Search se taaza/sahi jawab (grounding). Sirf text. */
    fun searchAnswer(system: String, history: List<Turn>, question: String): String =
        search(system, history, question, deep = false).text

    /**
     * Google Search se jawab + kahan se mila (sources).
     * deep = mushkil sawaal: zyada sochne wala model, kai sources se jaanch, lamba jawab.
     */
    fun search(system: String, history: List<Turn>, question: String, deep: Boolean, think: Boolean = false): SearchResult {
        var last: AiException? = null
        val order = when {
            think -> (THINK_MODELS + DEEP_MODELS + listOf(model) + BACKUP_MODELS).distinct()
            deep -> (DEEP_MODELS + listOf(model) + BACKUP_MODELS).distinct()
            else -> (listOf(model) + BACKUP_MODELS).distinct()
        }
        for (m in order) {
          var withThinking = true
          for (attempt in 0..1) {
            try {
                val contents = JSONArray()
                history.takeLast(if (deep) 8 else 6).forEach {
                    contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
                }
                contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question))))
                val gen = JSONObject().put("maxOutputTokens", if (think) 6000 else if (deep) 3000 else 1024)
                if (withThinking) {
                    val lvl = if (think) "high" else if (deep) "medium" else "low"
                    if ((m.startsWith("gemini-3") || m.startsWith("gemini-4")) && !m.contains("lite")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", lvl))
                    if (m.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", if (think) 8192 else if (deep) 2048 else 0))
                }
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                    .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                    .put("generationConfig", gen)
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(JSON_TYPE)).build()
                val cand = JSONObject(execute(req)).optJSONArray("candidates")?.optJSONObject(0)
                val p = cand?.optJSONObject("content")?.optJSONArray("parts") ?: throw AiException(0, "khali jawab")
                val sb = StringBuilder()
                for (i in 0 until p.length()) { val o = p.optJSONObject(i) ?: continue; if (!o.optBoolean("thought")) sb.append(o.optString("text")) }
                // kin websites se jaankari mili
                // sirf tab jab sach me search hua (groundingMetadata) - warna khali = koi source nahi dikhega
                val sources = Grounding.sources(cand.optJSONObject("groundingMetadata"))
                label = (if (think) "Gemini गहरी सोच" else "Gemini+Search") + " ($m)"
                if (sb.isBlank()) throw AiException(0, "khali jawab")
                return SearchResult(sb.toString(), sources)
            } catch (e: AiException) {
                last = e
                val msg = (e.message ?: "").lowercase()
                // galat key = sab model fail honge -> ruko. (403 "model ki ijazat nahi" = agla model try karo)
                if (e.code == 401 || (e.code == 403 && (msg.contains("api key") || msg.contains("api_key")))) throw e
                // is model ko sochne ki setting pasand nahi -> bina uske ek baar aur
                if (e.code == 400 && withThinking && msg.contains("think")) { withThinking = false; continue }
                break
            }
          }
        }
        throw last ?: AiException(0, "search fail")
    }

    private fun call(m: String, system: String, history: List<Turn>, prompt: String, imageB64: String?,
                     thinking: Boolean = true): String {
        val contents = JSONArray()
        history.forEach {
            contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (imageB64 != null) parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)))
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val gen = JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", if (fast) 600 else 2048)
        // Gemini 3 model "sochte" hain = dheere. Baatcheet me sabse kam (minimal), kaam me low.
        val heavy = m.startsWith("gemini-3")
        if (thinking && heavy) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", if (fast) "minimal" else "low"))
        if (thinking && m.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", if (fast) 0 else 512))

        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val raw = try { execute(req) } catch (e: AiException) {
            if (e.code == 400 && thinking && (e.message ?: "").contains("think", true))
                return call(m, system, history, prompt, imageB64, thinking = false)
            throw e
        }
        val resp = JSONObject(raw)
        val p = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(0, "Gemini ne khali jawab diya: " +
                (resp.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""))
        val sb = StringBuilder()
        for (i in 0 until p.length()) {
            val o = p.optJSONObject(i) ?: continue
            if (!o.optBoolean("thought", false)) sb.append(o.optString("text"))
        }
        return sb.toString()
    }
}

/** Groq, OpenRouter, Custom - sab OpenAI jaisa format use karte hain. */
class OpenAiCompatClient(
    private val name: String,
    private val baseUrl: String,
    private val key: String,
    private val model: String
) : AiClient {
    override val label = "$name ($model)"
    var fast = false

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach {
            msgs.put(JSONObject().put("role", if (it.role == "user") "user" else "assistant").put("content", it.text))
        }
        if (imageB64 != null) {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")))
            msgs.put(JSONObject().put("role", "user").put("content", content))
        } else {
            msgs.put(JSONObject().put("role", "user").put("content", prompt))
        }
        val body = JSONObject().put("model", model).put("messages", msgs)
        // gpt-oss jaise "sochne wale" model: baatcheet me kam socho = tez
        if (model.contains("gpt-oss")) body.put("reasoning_effort", if (fast) "low" else "medium")

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant")
            .header("X-Title", "DK Assistant")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val resp = JSONObject(execute(req))
        val msg = resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(0, "$name ne khali jawab diya")
        val c = msg.opt("content")
        val text = when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
            else -> ""
        }
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    override fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", if (it.role == "user") "user" else "assistant").put("content", it.text)) }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put("max_tokens", 900)
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant")
            .header("X-Title", "DK Assistant")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val full = StringBuilder()
        val tf = ThinkFilter()
        sse(req) { d ->
            val o = try { JSONObject(d) } catch (_: Exception) { return@sse }
            o.optJSONObject("error")?.let { throw AiException(it.optInt("code", 0), it.optString("message")) }
            val c = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content")
            val txt = if (c is String) c else ""
            if (txt.isNotEmpty()) { val clean = tf.feed(txt); if (clean.isNotEmpty()) { full.append(clean); onDelta(clean) } }
        }
        return full.toString()
    }
}
