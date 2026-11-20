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
data class SearchResult(val text: String, val sources: List<Pair<String, String>>,
                        /** 13 नवंबर 2026: Gemini googleSearch का groundingMetadata (वाक्य-वाले चिप) - और खोज सेवाओं में null */
                        val g: GroundCite.G? = null)

/** retryAfter = Google ne kitne second baad dobara bola (429), 0 = pata nahi */
open class AiException(val code: Int, message: String, val retryAfter: Int = 0) : IOException(message)

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
private val fastHttp = com.dk.assistant.NetFix.builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * 15 नवंबर 2026 (मालिक: "नॉर्मल चैट में भी प्रॉब्लम"): चैट में Gemini पहले Google पर खोजता है, फिर पहला शब्द भेजता है -
 * 12 सेकंड की रोक में धीमे नेट पर यह टूट जाता था ("सर्वर बहुत धीमा है"). खोज वाले चैट जवाब को ज़्यादा समय.
 */
private val groundHttp = fastHttp.newBuilder()
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(55, TimeUnit.SECONDS)
    .build()

/** SSE (data: ...) lines padho. */
private fun sse(req: Request, http: okhttp3.OkHttpClient = fastHttp, onData: (String) -> Unit) {
    val call = http.newCall(req)
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

private val http = com.dk.assistant.NetFix.builder()
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
        throw AiException(r.code, msg, GeminiModels.retryDelaySec(body))
    }
    body
}

/** Google Gemini (apna alag format). Ek model fail ho to dusre Gemini model try karta hai. */
class GeminiClient(private val key: String, private val model: String) : AiClient {
    var fast = false
    var only = false
    var backups: List<String> = BACKUP_MODELS
    /** नवंबर 2026: Settings → AI दिमाग का "सोचने का स्तर" ([ThinkLevel]). AUTO = पहले जैसा */
    var think: String = ThinkLevel.AUTO
    override var label = "Gemini ($model)"
        private set
    /** 15 नवंबर 2026 (मालिक): एक-key मोड में इस काम की मॉडल-लेन ([OneKeyLanes]) - null = पहले जैसा क्रम */
    var lane: OneKeyLanes.Lane? = null
    /** Settings में मालिक ने यही मॉडल खुद चुना = लेन में भी सबसे पहले */
    var keepFirst = false
    private fun laned(list: List<String>, l: OneKeyLanes.Lane? = lane) = OneKeyLanes.order(l, list, keepFirst)

    companion object {
        val BACKUP_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
        /**
         * 18 नवंबर 2026 (मालिक): डीप रिसर्च / गहरी सोच में सबसे पहले Gemini 4 Hydrogen - नया (12 नवंबर 2026),
         * free tier में भी चलता है और Argon से तेज़/बेहतर. वह न चले (limit/न मिले) तो पुराना क्रम वैसा ही.
         * Argon / 3.1 Pro free tier में नहीं - लाइसेंस वाले की paid key हो तो वे भी आज़माए जाते हैं.
         */
        const val HYDROGEN = "gemini-4-hydrogen"
        val DEEP_MODELS = listOf(HYDROGEN, "gemini-3.8-flash")
        val THINK_MODELS = listOf(HYDROGEN, "gemini-4-argon", "gemini-3.1-pro-preview", "gemini-3.8-flash")
    }

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        val models = if (only) listOf(model) else laned(GeminiModels.order(model, backups))
        var last: AiException? = null
        for (m in models) {
            try {
                val r = try { call(m, system, history, prompt, imageB64) } catch (e: AiException) {
                    // चुना "सोचने का स्तर" यह मॉडल न समझे (400) = उसी मॉडल से बिना उसके
                    if (GeminiModels.thinkingRejected(e.code, e.message.orEmpty(), ThinkLevel.geminiConfig(m, think) != null))
                        call(m, system, history, prompt, imageB64, thinking = false) else throw e
                }
                label = "Gemini ($m)"
                return r
            } catch (e: AiException) {
                if (last == null || GeminiModels.isGone(last.code, last.message.orEmpty())) last = e
                val tryNext = e.code == 404 || e.code == 429 || e.code >= 500 || GeminiModels.modelDenied(e.code, e.message.orEmpty()) ||
                    (e.code == 400 && !(e.message ?: "").contains("API key", ignoreCase = true))
                if (!tryNext) throw e
            }
        }
        throw last ?: AiException(0, "Gemini fail")
    }

    /**
     * 13 नवंबर 2026 (मालिक): चैट के हर Gemini जवाब में googleSearch चालू ([BrainManager.streamChat] में true) - ज़रूरत हो तो मॉडल खुद वेब खोजे.
     * अंदरूनी काम (खोज मिलाना, ऑटो रिप्लाई, key टेस्ट) = false.
     * खोजा = [lastGround] में groundingMetadata (वाक्य-वाले चिप + स्रोत); नहीं खोजा = null.
     */
    var groundChat = false
    @Volatile var lastGround: GroundCite.G? = null
        private set
    @Volatile private var streamMeta: JSONObject? = null

    override fun stream(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        lastGround = null
        val full = streamModels(system, history, prompt, onDelta)
        lastGround = GroundCite.parse(streamMeta, full)
        return full
    }

    private fun streamModels(system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        val models = if (only) listOf(model) else laned(GeminiModels.order(model, backups)).take(3)
        var last: AiException? = null
        for (m in models) {
            var thinking = true
            var gotAny = false
            try {
                val full = StringBuilder()
                streamOnce(m, system, history, prompt, thinking) { gotAny = true; full.append(it); onDelta(it) }
                label = "Gemini ($m)"
                continueCut(m, system, history, prompt, full, onDelta)
                return full.toString()
            } catch (e: AiException) {
                if (gotAny) throw e
                // नवंबर 2026: PR #148 में यह बचाव हट गया था - मॉडल "thinking" न समझे (400) तो उसी मॉडल से बिना thinking एक बार दोबारा
                if (GeminiModels.thinkingRejected(e.code, e.message.orEmpty(), thinking)) {
                    thinking = false
                    try {
                        val full = StringBuilder()
                        streamOnce(m, system, history, prompt, false) { gotAny = true; full.append(it); onDelta(it) }
                        label = "Gemini ($m)"
                        continueCut(m, system, history, prompt, full, onDelta)
                        return full.toString()
                    } catch (e2: AiException) {
                        if (gotAny) throw e2
                        if (last == null || GeminiModels.isGone(last.code, last.message.orEmpty())) last = e2
                        val next2 = e2.code == 404 || e2.code == 429 || e2.code >= 500 ||
                            (e2.code == 400 && (e2.message ?: "").contains("model", true) && !(e2.message ?: "").contains("API key", true))
                        if (!next2) throw e2
                        continue
                    }
                }
                if (last == null || GeminiModels.isGone(last.code, last.message.orEmpty())) last = e
                val tryNext = e.code == 404 || e.code == 429 || e.code >= 500 || GeminiModels.modelDenied(e.code, e.message.orEmpty()) ||
                    (e.code == 400 && !(e.message ?: "").contains("API key", true))
                if (!tryNext) throw e
            }
        }
        throw last ?: AiException(0, "Gemini fail")
    }

    /** आख़िरी stream क्यों रुका (Gemini finishReason: STOP / MAX_TOKENS …) */
    @Volatile private var lastFinish: String? = null

    /**
     * 15 नवंबर 2026 (मालिक): लंबा जवाब (कहानी / चिट्ठी / कोड) token की सीमा पर बीच वाक्य में कट जाता था, बताता भी नहीं था.
     * कटा ([ChatFlow.cutOff]) = वहीं से आगे का हिस्सा उसी बुलबुले में (ज़्यादा से ज़्यादा [ChatFlow.MAX_CONTINUE] बार).
     */
    private fun continueCut(m: String, system: String, history: List<Turn>, prompt: String, full: StringBuilder, onDelta: (String) -> Unit) {
        var n = 0
        while (ChatFlow.cutOff(lastFinish) && n < ChatFlow.MAX_CONTINUE && full.isNotBlank()) {
            n++
            DKLog.d("Gemini ($m): जवाब लंबाई पर कटा - आगे का हिस्सा ($n)")
            val h = history + Turn("user", prompt) + Turn("model", full.toString())
            try { streamRaw(m, system, h, ChatFlow.CONTINUE_PROMPT, false, false) { full.append(it); onDelta(it) } }
            catch (e: Exception) { if (e is LoserException) throw e; DKLog.d("आगे का हिस्सा नहीं आया: ${e.message}"); return }
        }
    }

    /**
     * googleSearch के साथ; मॉडल/खाता tool न माने (400) तो उसी मॉडल से बिना tool एक बार (जवाब रुके नहीं).
     * 15 नवंबर 2026: धीमा नेट / खोज में देर ([ChatFlow.slowNet]) और अभी कुछ नहीं आया = एक बार बिना खोज के (जल्दी जवाब) -
     * एक-key मोड में दूसरी key नहीं होती, पहले यहीं "जवाब नहीं आ पाया" आ जाता था.
     */
    private fun streamOnce(m: String, system: String, history: List<Turn>, prompt: String, thinking: Boolean, onDelta: (String) -> Unit) {
        streamMeta = null
        // 18 नवंबर 2026: यह key खोज नहीं ले सकती (मुफ़्त Gemini key) = सीधे बिना खोज ([SearchBlock])
        if (!groundChat || SearchBlock.blocked(key)) { streamRaw(m, system, history, prompt, thinking, false, onDelta); return }
        var got = false
        try { streamRaw(m, system, history, prompt, thinking, true) { got = true; onDelta(it) } }
        catch (e: AiException) {
            val quota = SearchBlock.retryWithout(e.code)
            if (got || !(GroundCite.toolRejected(e.code, e.message.orEmpty()) || quota)) throw e
            DKLog.d("Gemini ($m): googleSearch tool नहीं चला (${e.code}) - बिना खोज वाला जवाब")
            streamMeta = null
            streamRaw(m, system, history, prompt, thinking, false, onDelta)
            // बिना खोज चल गया = 429 खोज का था (मॉडल की limit नहीं) - कुछ घंटे इस key पर चैट बिना खोज
            if (quota) { SearchBlock.mark(key); DKLog.d("Gemini: इस key पर Google खोज नहीं चलती (मुफ़्त key) - कुछ घंटे चैट बिना खोज") }
        } catch (e: java.io.IOException) {
            if (got || !ChatFlow.slowNet(e)) throw e
            DKLog.d("Gemini ($m): खोज के साथ देर (${e.javaClass.simpleName}) - एक बार बिना खोज")
            streamMeta = null
            streamRaw(m, system, history, prompt, thinking, false, onDelta)
        }
    }

    private fun streamRaw(m: String, system: String, history: List<Turn>, prompt: String, thinking: Boolean, search: Boolean, onDelta: (String) -> Unit) {
        lastFinish = null
        val contents = JSONArray()
        history.forEach {
            contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        val gen = JSONObject().put("maxOutputTokens", 700)
        val chosen = if (thinking) ThinkLevel.geminiConfig(m, think) else null
        if (chosen != null) {
            // Settings में चुना स्तर (Low/Medium/High) - सोच के tokens भी गिने जाते हैं, इसलिए जवाब के लिए जगह बढ़ाई
            gen.put("thinkingConfig", chosen).put("maxOutputTokens", 700 + ThinkLevel.extraTokens(think))
        } else {
            if (thinking && m.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            if (thinking && m.startsWith("gemini-2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(system)))))
            .put("contents", contents)
            .put("generationConfig", gen)
        if (search) body.put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        sse(req, if (search) groundHttp else fastHttp) { d ->
            val o = try { JSONObject(d) } catch (_: Exception) { return@sse }
            o.optJSONObject("error")?.let { throw AiException(it.optInt("code", 0), it.optString("message")) }
            val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: return@sse
            cand.optString("finishReason").takeIf { it.isNotBlank() }?.let { lastFinish = it }
            cand.optJSONObject("groundingMetadata")?.let { streamMeta = it }       // आख़िरी टुकड़े में पूरा आता है
            val p = cand.optJSONObject("content")?.optJSONArray("parts") ?: return@sse
            for (i in 0 until p.length()) {
                val x = p.optJSONObject(i) ?: continue
                if (!x.optBoolean("thought", false)) x.optString("text").takeIf { it.isNotEmpty() }?.let(onDelta)
            }
        }
    }

    fun searchAnswer(system: String, history: List<Turn>, question: String): String =
        search(system, history, question, deep = false).text

    fun search(system: String, history: List<Turn>, question: String, deep: Boolean, think: Boolean = false, maxModels: Int = 99): SearchResult {
        var last: AiException? = null
        val order = when {
            think -> (THINK_MODELS + DEEP_MODELS + GeminiModels.order(model, BACKUP_MODELS)).distinct()
            deep -> (DEEP_MODELS + GeminiModels.order(model, BACKUP_MODELS)).distinct()
            else -> GeminiModels.order(model, BACKUP_MODELS)
        }.let { laned(it, if (think || deep) OneKeyLanes.Lane.HEAVY else lane ?: OneKeyLanes.Lane.TALK) }.take(maxModels.coerceAtLeast(1))
        // 18 नवंबर 2026: यह key Google खोज नहीं ले सकती ([SearchBlock]) = हर मॉडल पर बेकार 429 नहीं, सीधे अगली खोज-सेवा
        // गहरी सोच (think) को खोज ज़रूरी नहीं - वहाँ बिना खोज वही सवाल
        var useTool = !SearchBlock.blocked(key)
        if (!useTool && !think) throw AiException(429, SearchBlock.MSG)
        for (m in order) { var retryNow = false; for (attempt in 0..1) {
            if (attempt == 1 && !retryNow) break
            retryNow = false
            val tried = useTool
            try {
                val contents = JSONArray()
                history.takeLast(if (deep) 8 else 6).forEach {
                    contents.put(JSONObject().put("role", if (it.role == "user") "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
                }
                contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question))))
                val gen = JSONObject().put("maxOutputTokens", if (think) 6000 else if (deep) 3000 else 1024)
                // Hydrogen अपने-आप गहरा सोचता है (सोच के tokens भी गिने जाते हैं) - जवाब कट न जाए: तय स्तर + extra tokens
                ThinkLevel.searchLevel(m, think, deep, this.think)?.let { lv ->
                    gen.put("thinkingConfig", JSONObject().put("thinkingLevel", lv))
                        .put("maxOutputTokens", (if (think) 6000 else 3000) + ThinkLevel.extraTokens(lv))
                }
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(system)))))
                    .put("contents", contents)
                    .put("generationConfig", gen)
                if (useTool) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(JSON_TYPE)).build()
                val cand = JSONObject(execute(req)).optJSONArray("candidates")?.optJSONObject(0)
                val p = cand?.optJSONObject("content")?.optJSONArray("parts") ?: throw AiException(0, "khali jawab")
                val sb = StringBuilder()
                for (i in 0 until p.length()) { val o = p.optJSONObject(i) ?: continue; if (!o.optBoolean("thought")) sb.append(o.optString("text")) }
                val gm = cand.optJSONObject("groundingMetadata")
                val g = GroundCite.parse(gm, sb.toString())
                val sources = if (g != null) GroundCite.unique(g) else Grounding.sources(gm)
                label = (if (think) "Gemini गहरी सोच" else "Gemini+Search") + " ($m)"
                if (sb.isBlank()) throw AiException(0, "khali jawab")
                if (attempt == 1) SearchBlock.mark(key)   // खोज के साथ 429, बिना खोज चला = key खोज नहीं ले सकती
                return SearchResult(sb.toString(), sources, g)
            } catch (e: AiException) {
                // गहरी सोच: खोज के साथ 429 = उसी मॉडल से बिना खोज एक बार (attempt 1)
                if (think && tried && attempt == 0 && SearchBlock.retryWithout(e.code)) { useTool = false; retryNow = true; continue }
                if (last == null || GeminiModels.isGone(last.code, last.message.orEmpty())) last = e
                val msg = (e.message ?: "").lowercase()
                if (e.code == 401 || ((e.code == 403 || e.code == 400) && (msg.contains("api key") || msg.contains("api_key")))) throw e
            }
        } }
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
        if (thinking) ThinkLevel.geminiConfig(m, think)?.let {
            gen.put("thinkingConfig", it).put("maxOutputTokens", (if (fast) 600 else 2048) + ThinkLevel.extraTokens(think))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(system)))))
            .put("contents", contents)
            .put("generationConfig", gen)
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        return JSONObject(execute(req)).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.let { p ->
                val sb = StringBuilder()
                for (i in 0 until p.length()) {
                    val o = p.optJSONObject(i) ?: continue
                    if (!o.optBoolean("thought", false)) sb.append(o.optString("text"))
                }
                sb.toString()
            } ?: throw AiException(0, "Gemini ne khali jawab diya")
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
    var backups: List<String> = emptyList()
    /** नवंबर 2026: Settings → AI दिमाग का "सोचने का स्तर" ([ThinkLevel]). AUTO = पहले जैसा */
    var think: String = ThinkLevel.AUTO
    /** 20 नवंबर 2026 (चिप में OpenAI के नए मॉडल): जवाब की लंबाई वाला field ([Provider.tokenField]) */
    var tokenField: String = "max_tokens"
    @Volatile var usedModel: String = model
        private set

    // 20 नवंबर 2026 (मालिक: "NVIDIA का llama 410 Gone"): 410 = कंपनी ने मॉडल बंद किया -> उसी key का अगला मॉडल (पहले पूरी API रुक जाती थी)
    private fun tryNext(e: AiException) = !RateLimit.accountWide(e) && (e.code == 404 || e.code == 410 || e.code == 429 || e.code == 413 || e.code >= 500) ||
        // 14 नवंबर 2026 (मालिक: "चैट में Groq: मॉडल ने सवाल नहीं लिया - बार-बार"): कोई भी 400 (मॉडल चैट वाला नहीं / कोई
        // setting न ले) = उसी key का अगला मॉडल. सिर्फ़ key की गलती पर नहीं (वह हर मॉडल पर वही)
        (e.code == 400 && !e.message.orEmpty().contains("api key", true) && !e.message.orEmpty().contains("api_key", true))

    private fun mKey(m: String) = "m429_${name}_$m"
    private fun models(): List<String> {
        val all = (listOf(model) + backups).distinct()
        val (ok, wait) = all.partition { !Cooldown.blocked(mKey(it)) }
        return ok + wait
    }
    private fun noteFail(m: String, e: AiException) {
        if (e.code == 429 && !RateLimit.accountWide(e)) Cooldown.block(mKey(m), RateLimit.backoffMs(1, e.retryAfter))
        // नवंबर 2026: मॉडल मौजूद नहीं (404) = 6 घंटे उसे आख़िर में, अगला फ्री मॉडल पहले
        if (e.code == 404) Cooldown.block(mKey(m), 6 * 60 * 60 * 1000L)
        // 410 = हमेशा के लिए बंद मॉडल - 24 घंटे आख़िर में (हर बार उस पर समय न जाए)
        if (e.code == 410) Cooldown.block(mKey(m), 24 * 60 * 60 * 1000L)
    }

    override fun complete(system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        var last: AiException? = null
        for (m in models()) {
            try { return completeOnce(m, system, history, prompt, imageB64).also { usedModel = m } } catch (e: AiException) {
                last = e; noteFail(m, e)
                if (!tryNext(e)) throw e
            }
        }
        throw last ?: AiException(0, "$name: कोई मॉडल नहीं चला")
    }

    private fun completeOnce(model: String, system: String, history: List<Turn>, prompt: String, imageB64: String?): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Lang.sys(system)))
        // खाली पुरानी बात (जवाब नहीं आया था) कई सर्वर 400 से लौटाते हैं - भेजो ही मत
        history.filter { it.text.isNotBlank() }.forEach {
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
        val thought = ThinkLevel.applyOpenAi(body, baseUrl, model, think, if (fast) "low" else "medium")
        fun req() = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant")
            .header("X-Title", "DK Assistant")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val resp = JSONObject(try { execute(req()) } catch (e: AiException) {
            // मॉडल ने सोचने का स्तर नहीं समझा (400) = उसी मॉडल से बिना उसके
            if (thought && ThinkLevel.rejected(e.code, e.message.orEmpty())) { ThinkLevel.strip(body); execute(req()) } else throw e
        })
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
        var last: AiException? = null
        for (m in models()) {
            var sent = false
            try {
                return streamOnce(m, system, history, prompt) { sent = true; onDelta(it) }.also { usedModel = m }
            } catch (e: AiException) {
                last = e; noteFail(m, e)
                if (sent || !tryNext(e)) throw e
            }
        }
        throw last ?: AiException(0, "$name: कोई मॉडल नहीं चला")
    }

    private fun streamOnce(model: String, system: String, history: List<Turn>, prompt: String, onDelta: (String) -> Unit): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Lang.sys(system)))
        history.filter { it.text.isNotBlank() }.forEach { msgs.put(JSONObject().put("role", if (it.role == "user") "user" else "assistant").put("content", it.text)) }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put(tokenField, 900 + ThinkLevel.extraTokens(think))
        val thought = ThinkLevel.applyOpenAi(body, baseUrl, model, think, "low")
        fun req() = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant")
            .header("X-Title", "DK Assistant")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val full = StringBuilder()
        val tf = ThinkFilter()
        val onData: (String) -> Unit = onData@{ d ->
            val o = try { JSONObject(d) } catch (_: Exception) { return@onData }
            o.optJSONObject("error")?.let { throw AiException(it.optInt("code", 0), it.optString("message")) }
            val c = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content")
            val txt = if (c is String) c else ""
            if (txt.isNotEmpty()) { val clean = tf.feed(txt); if (clean.isNotEmpty()) { full.append(clean); onDelta(clean) } }
        }
        try { sse(req(), onData = onData) } catch (e: AiException) {
            // मॉडल ने सोचने का स्तर नहीं समझा (400, कुछ भी आने से पहले) = उसी मॉडल से बिना उसके
            if (thought && full.isEmpty() && ThinkLevel.rejected(e.code, e.message.orEmpty())) { ThinkLevel.strip(body); sse(req(), onData = onData) } else throw e
        }
        return full.toString()
    }
}