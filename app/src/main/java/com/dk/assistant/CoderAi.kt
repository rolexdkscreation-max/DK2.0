package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// यह class पहले CodingAgent.kt में थी; सिर्फ़ जगह बदली है (नवंबर 2026), code वही।

/**
 * Coding ke liye alag AI (lambe jawab, lamba timeout). Settings me alag key/model ho to woh,
 * warna wahi AI jo baatcheet me chal rahi hai.
 */
class CoderAi(val provider: Provider, private val key: String, val model: String, private val base: String,
              /** Doosre API (Groq/OpenRouter/Custom/doosri Gemini key) - limit khatm ho to inpar apne aap */
              private val fallbacks: List<CoderAi> = emptyList(), private val note: String = "",
              /** रिपोर्ट कहाँ जाए (कोडिंग टीम = CoderReport, DK कंप्यूटर = उसकी अपनी रिपोर्ट) */
              private val sink: (String, String) -> Unit = { p, f -> CoderReport.add(p, f) }) {
    private val selfLabel = "${provider.name} ($model$note)"
    /** v5 (DK कंप्यूटर की chain): इसी API के बैकअप / बिना बैकअप वाली अकेली कॉपी / key-base (chain बनाने को) */
    internal fun fallbackList(): List<CoderAi> = fallbacks
    internal fun alone(): CoderAi = CoderAi(provider, key, model, base, emptyList(), note, sink).also { it.think = think }

    /** नवंबर 2026: Settings → AI दिमाग का "सोचने का स्तर" ([ThinkLevel]). AUTO = पहले जैसा */
    @Volatile var think: String = ThinkLevel.AUTO
    /** यही स्तर पूरी chain (बैकअप keys/मॉडल/API) पर */
    internal fun applyThink(level: String): CoderAi { think = level; fallbacks.forEach { it.applyThink(level) }; return this }
    internal fun keyForChain(): String = key
    internal fun baseForChain(): String = base
    /** ऑटोनोमस मोड वाली key का निशान (खाली = आम कोडिंग एजेंट). key नहीं, सिर्फ़ आखिरी 4 अक्षर */
    val autoTag: String = if (note.contains("ऑटोनोमस")) "auto|${provider.id}|${key.takeLast(4)}" else ""
    private fun capKey() = "${provider.id}|${key.takeLast(6)}|$model"
    /** एक request में यह मॉडल कितने अक्षर पढ़ सकता है (413 के बाद जो सीमा याद हुई वही) - एजेंट इसी हिसाब से पूरी फ़ाइलें भेजते हैं */
    val budget: Int get() = sizeCap[capKey()]?.first ?: CoderContext.budgetFor(provider.isGemini, model)
    /** Abhi jo AI chal rahi hai (limit par switch hua ho to wahi dikhe) */
    val label: String get() = sticky()?.takeIf { l -> fallbacks.any { it.selfLabel == l } } ?: selfLabel

    /**
     * Ek API ki limit/quota (429) ya server dikkat ho to agla model/API - kaam beech me na ruke.
     * Pehle chuna hua, phir baaki API. Sab ki limit ek saath khatm ho to ek baar thoda rukkar dobara.
     */
    /** true = सारी limit खत्म होने पर रुककर फिर कोशिश (कोडिंग एजेंट). false = तुरंत बताओ (DK कंप्यूटर) */
    @Volatile var waitOnLimit = false

    /** v3.6: Gemini मॉडलों का क्रम (चुना मॉडल पहले, फिर ये) - हल्के एजेंट = LIGHT_CHAIN */
    private var chain: List<String> = GeminiModels.CODER_CHAIN

    /**
     * v3.6 (owner का नियम): हल्के एजेंटों के लिए वही keys (5 तक, वही fallback), पर हल्का मॉडल.
     * Gemini नहीं (Groq/OpenRouter...) = वही AI (उनके मॉडल का नाम Gemini वाला नहीं हो सकता).
     */
    fun withModel(m: String, modelChain: List<String> = GeminiModels.LIGHT_CHAIN): CoderAi {
        if (!provider.isGemini || m.isBlank() || m == model) return this
        return CoderAi(provider, key, m, base, fallbacks.map { it.withModel(m, modelChain) }, note, sink).also {
            it.waitOnLimit = waitOnLimit; it.chain = modelChain; it.applyThink(think)
        }
    }

    fun chat(system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia> = emptyList()): String {
        var attempt = 1
        var waitedSec = 0
        while (true) {
            try {
                val r = chatChain(system, msgs, media)
                if (attempt > 1) sink("AI API अस्थायी गलती (नेटवर्क/सर्वर)", "अपने-आप दोबारा कोशिश की ($attempt/${Retry.ATTEMPTS}) — सफल")
                if (waitedSec > 0) {
                    sink("सारी keys और मॉडल की limit खत्म थी (429)", "काम रोका नहीं — ${waitedSec} सेकंड रुककर फिर चला")
                    onLimitWait?.invoke("✓ limit खुल गई — काम आगे चल रहा है")
                }
                return r
            } catch (e: java.io.IOException) {
                val code = (e as? AiException)?.code ?: -1
                // सारी keys + सारे मॉडल की limit (429) एक साथ: काम रोकने की जगह limit खुलने तक रुको (हर मिनट वाली limit जल्दी खुलती है),
                // कुल [LIMIT_WAIT_MAX_SEC] तक. फिर भी नहीं = दिन की limit - तब रुककर बताओ (काम चौकी में बचा रहता है, बाद में आगे)
                val w = if (waitOnLimit) nextLimitWait(code, (e as? AiException)?.retryAfter ?: 0, waitedSec) else 0
                if (w > 0) {
                    DKStatus.post(DKStatus.Kind.LIMIT_ALL)
                    onLimitWait?.invoke("⏳ API की limit अभी खत्म — ${w} सेकंड रुककर अपने-आप फिर कोशिश (कुल रुके: ${waitedSec / 60} मिनट)")
                    Thread.sleep(w * 1000L)
                    waitedSec += w
                    continue
                }
                // key/मॉडल की गलती (4xx) = दोबारा कोशिश बेकार, तुरंत रिपोर्ट
                if (attempt >= Retry.ATTEMPTS || !Retry.isTransient(code, e.message.orEmpty())) {
                    if (attempt > 1) sink("AI API $attempt बार fail (${reason(e)})", "${Retry.ATTEMPTS} बार अपने-आप कोशिश के बाद भी नहीं चला — आपको रिपोर्ट किया")
                    throw e
                }
                DKLog.d("Coder: AI ${reason(e)} - अपने-आप दोबारा (${attempt + 1}/${Retry.ATTEMPTS})")
                DKStatus.post(DKStatus.Kind.RETRY)
                Thread.sleep(Retry.backoffMs(attempt) * 2)
                attempt++
            }
        }
    }

    /** एक बार पूरी chain: चुनी API → बाकी API (limit/server गलती पर अगली) */
    private fun chatChain(system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia>): String {
        val chain = listOf(this) + fallbacks
        val ordered = orderChain(chain.map { it.selfLabel }, sticky()).map { l -> chain.first { it.selfLabel == l } }
        val errs = mutableListOf<Pair<CoderAi, java.io.IOException>>()
        var n429 = 0
        for (ai in ordered) {
            // 429 के बाद: थोड़ा रुककर (2s, 4s, 8s) अगला मॉडल/API - एक साथ सब पर धावा नहीं
            if (n429 > 0 && (errs.lastOrNull()?.second as? AiException)?.code == 429) Thread.sleep(CoderContext.backoffMs(n429))
            try {
                val r = ai.chatOne(system, msgs, media)
                val prev = sticky() ?: selfLabel
                if (ai.selfLabel != prev) {
                    val why = errs.lastOrNull()?.let { (who, e) -> "${who.selfLabel}: ${reason(e)}" } ?: "$prev अब नहीं चल रहा था"
                    DKLog.d("Coder: $why -> ${ai.selfLabel} पर स्विच")
                    sink(why, "अपने आप ${ai.selfLabel} पर स्विच किया, काम बिना रुके जारी रहा")
                }
                setSticky(if (ai === this) null else ai.selfLabel)
                return r
            } catch (e: java.io.IOException) {
                // AiException (429/413/5xx/401...) ya network timeout - agla API
                errs.add(ai to e)
                if ((e as? AiException)?.code == 429) n429++
                if (chain.size > 1) DKLog.d("Coder: ${ai.selfLabel} -> ${reason(e)}, अगला API")
                if (ai !== ordered.last()) DKStatus.post(DKStatus.forCode((e as? AiException)?.code ?: -1))
            }
        }
        // सब fail. सब की limit (429) खत्म = लगातार कोशिश नहीं - रुककर user को बताओ
        val wait = quotaWaitSec(errs.map { ((it.second as? AiException)?.code ?: -1) to ((it.second as? AiException)?.retryAfter ?: 0) })
        val tried = errs.joinToString { "${it.first.provider.name}: ${(it.second as? AiException)?.code ?: "network"}" }
        if (wait > 0) {
            DKStatus.post(DKStatus.Kind.LIMIT_ALL)
            sink("सारी API keys और मॉडल की limit एक साथ खत्म (429)", "limit खुलने तक रुककर फिर कोशिश (कुल ${LIMIT_WAIT_MAX_SEC / 60} मिनट तक) — काम रोका नहीं")
            throw AiException(429, allLimitsMessage(wait, tried, fallbacks.isEmpty()), wait)
        }
        // पहले चुने API की असली वजह, साथ में बाकी API का हाल
        val main = errs.firstOrNull { it.first === this } ?: errs.first()
        val others = errs.filter { it !== main }.joinToString { "${it.first.provider.name}: ${(it.second as? AiException)?.code ?: "network"}" }
        val e = main.second
        val msg = (e.message ?: e.javaClass.simpleName) + if (others.isNotBlank()) "\n(दूसरे API भी नहीं चले: $others)" else
            if (fallbacks.isEmpty()) "\n(सुझाव: Settings → AI दिमाग में इसी हिस्से की दूसरी फ्री key (जैसे Groq / OpenRouter) डालकर उसे मुख्य चुनें)" else ""
        throw if (e is AiException) AiException(e.code, msg, e.retryAfter) else java.io.IOException(msg, e)
    }

    /**
     * एक API पर request. 413 / "बहुत बड़ी" मिले तो वही request दोबारा नहीं - इतिहास छोटा करके (ज़्यादा से ज़्यादा 2 बार)
     * और इस मॉडल की सीमा याद रखो ताकि अगले कदम पहले से छोटे जाएँ.
     */
    private fun chatOne(system: String, msgs: List<Pair<String, String>>, media: List<CoderMedia> = emptyList()): String {
        val k = capKey()
        var (budget, maxTok) = sizeCap[k] ?: (CoderContext.budgetFor(provider.isGemini, model) to 16000)
        var shrinks = 0
        // फ़ोटो/वीडियो: Gemini = पूरा वीडियो (छोटा हो तो) या frames; बाकी = सिर्फ़ फ़ोटो/frames
        var parts = CoderMedia.allParts(media, provider.isGemini)
        while (true) {
            val room = (budget - system.length).coerceAtLeast(CoderContext.MIN_BUDGET)
            val send = if (CoderContext.total(msgs) > room) CoderContext.compact(msgs, room) else msgs
            try {
                return if (provider.isGemini) gemini(system, send, parts) else openAi(system, send, maxTok, parts)
            } catch (e: AiException) {
                // यह मॉडल फ़ोटो/वीडियो नहीं ले सकता -> बिना फ़ोटो (लिखा विवरण साथ जाता है), काम न रुके
                if (parts.isNotEmpty() && e.code in listOf(400, 413, 415, 422)) {
                    parts = emptyList()
                    sink("$selfLabel: फ़ोटो/वीडियो नहीं ले सका (${e.code})", "बिना फ़ोटो दोबारा भेजा — फ़ोटो का लिखा विवरण साथ गया")
                    continue
                }
                if (!CoderContext.tooLarge(e.code, e.message.orEmpty()) || shrinks >= 2) throw e
                shrinks++
                val (c2, t2) = CoderContext.shrink(CoderContext.total(send) + system.length, maxTok, e.message.orEmpty())
                budget = c2; maxTok = t2
                sizeCap[k] = budget to maxTok
                DKLog.d("Coder: $selfLabel -> ${e.code} request बड़ी, अब $budget अक्षर / $maxTok tokens")
                sink("$selfLabel: request बहुत बड़ी (${e.code})", "पुराना इतिहास और बड़ी फ़ाइलें छोटी करके ${budget / 1000}k अक्षर की request भेजी — वही बड़ी request दोबारा नहीं")
            }
        }
    }

    private fun gemini(system: String, msgs: List<Pair<String, String>>, parts: List<Pair<String, String>> = emptyList()): String {
        // चुना मॉडल -> 3.8 Flash (coding के लिए सबसे अच्छा) -> बाकी नए मॉडल. 2.5 वाले backup नहीं (नए users के लिए बंद)
        // 404 "no longer available" दे चुके मॉडल (phone में याद) दोबारा नहीं - पहले हर कदम पर 2.5-pro का 404
        val kt = key.takeLast(6)
        val all = GeminiModels.orderSkippingGone(model, chain) {
            Cooldown.blocked(GeminiModels.goneKey(it, kt))
        }
        // jis model ki limit abhi khatm hui thi use thodi der chhodo (har kadam par wahi 429 dobara na khaayein)
        val now = System.currentTimeMillis()
        val order = all.filter { (cool["${key.takeLast(6)}|$it"] ?: 0L) < now }.ifEmpty { all }
        val errs = mutableListOf<Pair<String, AiException>>()
        for (m in order) {
            var waited = false
            while (true) {
                try {
                    val r = geminiOnce(m, system, msgs, parts)
                    if (m != model && errs.isNotEmpty())
                        sink("Gemini ${errs.joinToString { it.first }}: ${reason(errs.last().second)}", "उसी API के $m मॉडल पर अपने आप स्विच किया")
                    return r
                } catch (e: AiException) {
                    if (e.code !in listOf(404, 429, 500, 503)) throw e
                    if (e.code == 429) {
                        // limit: rukne ki jagah turant agla model (sab khatm ho to chat() ek baar rukta hai)
                        cool["${key.takeLast(6)}|$m"] = System.currentTimeMillis() + (e.retryAfter.coerceIn(30, 600) * 1000L)
                        errs.add(m to e); DKLog.d("Coder: $m -> 429, अगला मॉडल")
                        // अगले मॉडल से पहले छोटा इंतज़ार (backoff)
                        if (m != order.last()) Thread.sleep(1500)
                        break
                    }
                    val w = GeminiModels.waitBeforeRetry(e.code, e.retryAfter)
                    if (!waited && w > 0) {
                        waited = true
                        DKLog.d("Coder: $m -> ${e.code}, ${w}s रुककर दोबारा")
                        Thread.sleep(w * 1000L + 500)
                        continue
                    }
                    errs.add(m to e)
                    if (GeminiModels.isGone(e.code, e.message.orEmpty())) {
                        Cooldown.block(GeminiModels.goneKey(m, kt), GeminiModels.GONE_MS)
                        DKLog.d("Coder: $m बंद है (${e.code}) - 7 दिन तक नहीं आज़माएँगे")
                    }
                    DKLog.d("Coder: $m -> ${e.code} ${e.message?.take(120)}, अगला मॉडल")
                    break
                }
            }
        }
        // सबसे काम की वजह दिखाओ (पहले सिर्फ़ आखिरी - "2.5 बंद है" - दिखती थी और असली वजह छिप जाती थी)
        val (bm, be) = GeminiModels.best(errs, { it.code }, { it.message.orEmpty() }) ?: throw AiException(0, "Gemini fail")
        val tried = errs.joinToString { "${it.first}: ${it.second.code}" }
        throw AiException(be.code, "$bm — " + GeminiModels.hindi(be.code, be.message.orEmpty()) + "\n(आज़माए: $tried)", be.retryAfter)
    }

    private fun geminiOnce(m: String, system: String, msgs: List<Pair<String, String>>, media: List<Pair<String, String>> = emptyList()): String {
        val contents = JSONArray()
        val at = msgs.indexOfFirst { it.first == "user" }
        msgs.forEachIndexed { i, (r, t) ->
            val ps = JSONArray().put(JSONObject().put("text", t))
            // फ़ोटो/वीडियो पहले user संदेश (काम) के साथ
            if (i == at) media.forEach { (mime, b64) -> ps.put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64))) }
            contents.put(JSONObject().put("role", if (r == "user") "user" else "model").put("parts", ps))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(system)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 32000).put("temperature", 0.2))
        val tc = ThinkLevel.geminiConfig(m, think)
        tc?.let { body.getJSONObject("generationConfig").put("thinkingConfig", it) }
        fun req() = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
        val o = JSONObject(try { exec(req()) } catch (e: AiException) {
            // चुना "सोचने का स्तर" यह मॉडल न समझे (400) = उसी मॉडल से बिना उसके
            if (GeminiModels.thinkingRejected(e.code, e.message.orEmpty(), tc != null)) {
                body.getJSONObject("generationConfig").remove("thinkingConfig"); exec(req())
            } else throw e
        })
        val p = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(0, "Gemini ने खाली जवाब दिया")
        val sb = StringBuilder()
        for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) sb.append(x.optString("text")) }
        return sb.toString()
    }

    private fun openAi(system: String, msgs: List<Pair<String, String>>, maxTok: Int, media: List<Pair<String, String>> = emptyList(), noThink: Boolean = false): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", Lang.sys(system)))
        val at = msgs.indexOfFirst { it.first == "user" }
        msgs.forEachIndexed { i, (r, t) ->
            val content: Any = if (i == at && media.isNotEmpty()) JSONArray().put(JSONObject().put("type", "text").put("text", t)).also { a ->
                media.filter { it.first.startsWith("image/") }.forEach { (mime, b64) ->
                    a.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
                }
            } else t
            arr.put(JSONObject().put("role", if (r == "user") "user" else "assistant").put("content", content))
        }
        val body = JSONObject().put("model", model).put("messages", arr).put("max_tokens", maxTok).put("temperature", 0.2)
        val thought = !noThink && ThinkLevel.applyOpenAi(body, base, model, think, "medium")
        fun req() = Request.Builder().url(base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant").header("X-Title", "DK Coding Agent")
            .post(body.toString().toRequestBody(JSON)).build()
        val raw = try { exec(req()) } catch (e: AiException) {
            // मॉडल ने सोचने का स्तर नहीं समझा (400) = उसी मॉडल से बिना उसके
            if (thought && ThinkLevel.rejected(e.code, e.message.orEmpty())) return openAi(system, msgs, maxTok, media, noThink = true)
            // kuch free models itne lambe jawab nahi dete -> chhota maango
            if (e.code == 400 && maxTok > 4000 && (e.message ?: "").contains("token", true)) return openAi(system, msgs, maxTok / 2, media, noThink)
            throw e
        }
        val msg = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(0, "${provider.name} ने खाली जवाब दिया")
        return msg.optString("content").replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    private fun exec(req: Request): String = http.newCall(req).execute().use { r ->
        val s = r.body?.string() ?: ""
        if (!r.isSuccessful) {
            val m = try { JSONObject(s).optJSONObject("error")?.optString("message") ?: s.take(200) } catch (_: Exception) { s.take(200) }
            throw AiException(r.code, m, GeminiModels.retryDelaySec(s))
        }
        s
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = com.dk.assistant.NetFix.builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

        /** DK कंप्यूटर की AI: सिर्फ़ Settings → AI दिमाग → DK कंप्यूटर की अपनी key. [sink] = कंप्यूटर की अपनी रिपोर्ट. */
        // v5 (मालिक): "DK कंप्यूटर पहले Gemini से कोशिश करे, फिर Groq, फिर OpenRouter, फिर Extra API - जिससे भी बने, उससे काम हो".
        // DK कंप्यूटर की अपनी keys (Settings → AI दिमाग → DK कंप्यूटर) में से जिसकी भी key डली है, सब इसी क्रम में एक chain में.
        fun forComputer(c: Context, sink: (String, String) -> Unit): CoderAi? {
            if (Prefs.oneKeyOn(c)) return forScope(c, ApiScopes.PC, sink)
            val parts = PC_CHAIN.mapNotNull { id -> forScope(c, ApiScopes.PC, sink, id) }
            val first = parts.firstOrNull() ?: return forScope(c, ApiScopes.PC, sink)
            val rest = first.fallbackList() + parts.drop(1).flatMap { listOf(it.alone()) + it.fallbackList() }
            return CoderAi(first.provider, first.keyForChain(), first.model, first.baseForChain(), rest, sink = sink).applyThink(ThinkLevel.get(c, ApiScopes.PC))
        }

        /** DK कंप्यूटर की fallback chain का क्रम (मालिक का नियम) */
        val PC_CHAIN = listOf("gemini", "openrouter", "custom", "groq")

        /** कोडिंग एजेंट की AI: सिर्फ़ Settings → AI दिमाग → कोडिंग एजेंट की अपनी key. null = key नहीं */
        // API/मॉडल बदलना, retry, request छोटी करना = अंदर की बात: यूज़र की रिपोर्ट में नहीं, सिर्फ़ डिबग लॉग में
        // ऑटोनोमस मोड ने कोडिंग शुरू की हो: उसकी अपनी key -> (खाली) कोडिंग एजेंट की key -> (वो भी नहीं) DK की मुख्य key.
        // ऑटोनोमस बंद / उसका काम नहीं = पहले जैसा, सिर्फ़ कोडिंग एजेंट की key.
        fun fromPrefs(c: Context): CoderAi? = com.dk.assistant.auto.AutoMode.coderOverride(c)
            ?: forScope(c, ApiScopes.CODER) { p, f -> DKLog.d("कोडिंग AI: $p → $f") }
            ?: com.dk.assistant.auto.AutoMode.coderMainFallback(c)

        /**
         * एक हिस्से की AI: उसकी चुनी कंपनी + उसी की key. दूसरी कंपनी/दूसरे हिस्से की key पर कभी नहीं.
         * Limit (429) / मॉडल बंद: Gemini अपने अंदर उसी key के दूसरे मॉडल; Groq/OpenRouter/Custom के लिए
         * उसी key के बैकअप मॉडल fallback में.
         */
        fun forScope(c: Context, scope: String, sink: (String, String) -> Unit): CoderAi? =
            forScope(c, scope, sink, Prefs.scopeActive(c, scope))

        fun forScope(c: Context, scope: String, sink: (String, String) -> Unit, providerId: String): CoderAi? {
            val p = Providers.byId(providerId) ?: return null
            // कोडिंग एजेंट + Gemini: 5 तक keys (key 1 के सारे मॉडल की limit खत्म -> key 2 के सारे मॉडल -> key 3 -> key 4 -> key 5)
            val gemKeys = if (p.isGemini && scope == ApiScopes.CODER) Prefs.coderGeminiKeys(c) else emptyList()
            val key = gemKeys.firstOrNull() ?: Prefs.scopeKey(c, scope, p.id)
            var model = Prefs.scopeModel(c, scope, p.id)
            // Settings का चुना Gemini मॉडल बंद हो चुका (404) -> setting खुद 3.8 Flash पर (हर बार 404 नहीं)
            if (p.isGemini && key.isNotBlank()) {
                val kt = key.takeLast(6)
                GeminiModels.replacementFor(model) { Cooldown.blocked(GeminiModels.goneKey(it, kt)) }?.let { nm ->
                    DKLog.d("Coder: Settings का मॉडल $model बंद है - $nm पर बदला")
                    DKStatus.post(DKStatus.Kind.MODEL_CHANGE)
                    sink("$model मॉडल Google ने बंद कर दिया (404)", "Settings → ${ApiScopes.title(scope)} का मॉडल अपने-आप $nm किया")
                    Prefs.edit(c) { putString(ApiScopes.pref(scope, "model", p.id), nm) }
                    model = nm
                }
            }
            val base = Prefs.scopeBase(c, scope, p.id)
            if (key.isBlank() || model.isBlank() || (!p.isGemini && base.isBlank())) return null
            val fb = if (p.isGemini) gemKeys.drop(1).mapIndexed { i, k -> CoderAi(p, k, model, base, note = ", Gemini key ${i + 2}", sink = sink) }
                else Prefs.scopeBackups(c, scope, p.id)
                .map { m -> CoderAi(p, key, m, base, note = ", उसी key का दूसरा मॉडल", sink = sink) }
            return CoderAi(p, key, model, base, fb, sink = sink).also { it.waitOnLimit = scope == ApiScopes.CODER }.applyThink(ThinkLevel.get(c, scope))
        }

        /** सारी keys की limit पर कुल कितना रुकें (फिर भी बंद = दिन की limit) */
        const val LIMIT_WAIT_MAX_SEC = 12 * 60
        /** limit खुलने के इंतज़ार की खबर (कोडिंग स्क्रीन पर कदम) */
        @Volatile var onLimitWait: ((String) -> Unit)? = null

        /** सब 429: अगली बार कितना रुकें (0 = अब और नहीं). Google का retryDelay हो तो वही (20..120 सेकंड), वरना 60 */
        fun nextLimitWait(code: Int, retryAfter: Int, waitedSec: Int, maxSec: Int = LIMIT_WAIT_MAX_SEC): Int {
            if (code != 429 || waitedSec >= maxSec) return 0
            val w = if (retryAfter > 0) retryAfter.coerceIn(20, 120) else 60
            return w.coerceAtMost(maxSec - waitedSec).coerceAtLeast(1)
        }

        /** 413 के बाद हर API/मॉडल की request सीमा (अक्षर, maxTokens) - अगली बार पहले से छोटी */
        private val sizeCap = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

        /** सारी keys की limit खत्म: साफ़ हिंदी - क्या हुआ और आगे क्या करें */
        fun allLimitsMessage(waitSec: Int, tried: String, noFallback: Boolean): String =
            "सारी API keys और मॉडल की limit खत्म है (429) — ${LIMIT_WAIT_MAX_SEC / 60} मिनट तक रुक-रुककर कोशिश की, फिर भी नहीं खुली (शायद आज की limit)। " +
                "बेवजह लगातार कोशिश नहीं की।\n" +
                "किया हुआ काम बचा है (इतिहास में)। आगे: लगभग ${if (waitSec >= 60) "${waitSec / 60} मिनट" else "$waitSec सेकंड"} बाद \"वहीं से आगे\" दबाएँ या काम फिर भेजें" +
                (if (noFallback) ", या Settings → AI दिमाग → कोडिंग एजेंट में Gemini key 2 से key 5 (दूसरे Google project की) या दूसरी फ्री key (जैसे Groq / OpenRouter) डालें" else "") + "।" +
                if (tried.isNotBlank()) "\n(आज़माए: $tried)" else ""

        /** Gemini model ki limit kab tak (key ke aakhri 6 akshar|model -> time) */
        private val cool = java.util.concurrent.ConcurrentHashMap<String, Long>()
        @Volatile private var stickyLabel: String? = null
        @Volatile private var stickyUntil = 0L
        /** Switch ke baad 10 minute usi API par (har kadam par pehle wala 429 na de); phir pehle wale ko dobara mauka */
        private fun sticky(): String? = stickyLabel?.takeIf { System.currentTimeMillis() < stickyUntil }
        private fun setSticky(l: String?) { stickyLabel = l; stickyUntil = if (l == null) 0L else System.currentTimeMillis() + 10 * 60_000L }

        /** Jo abhi chal raha (sticky) woh pehle, baaki apne kram me */
        fun orderChain(labels: List<String>, sticky: String?): List<String> =
            if (sticky == null || sticky !in labels) labels else listOf(sticky) + labels.filter { it != sticky }

        /** Sab fail aur SAB 429 (limit) -> kitna rukein (sec), 0 = mat ruko. Google ka retryDelay ho to woh (20..90), warna 60 */
        fun quotaWaitSec(errs: List<Pair<Int, Int>>): Int {
            if (errs.isEmpty() || errs.any { it.first != 429 }) return 0
            val hinted = errs.map { it.second }.filter { it > 0 }
            return if (hinted.isEmpty()) 60 else hinted.minOrNull()!!.coerceIn(20, 90)
        }

        /** Chhoti Hindi wajah report ke liye */
        fun reason(e: Exception): String {
            val code = (e as? AiException)?.code
            return when (code) {
                429 -> "free limit / कोटा खत्म (429)"
                401, 403 -> "key गलत या इजाज़त नहीं ($code)"
                404 -> "मॉडल नहीं मिला (404)"
                413 -> "बात बहुत लंबी, इस API की सीमा से बड़ी (413)"
                500, 502, 503, 504 -> "सर्वर पर भीड़/दिक्कत ($code)"
                null -> "नेटवर्क दिक्कत (${e.javaClass.simpleName})"
                else -> "गलती $code: ${e.message?.lineSequence()?.firstOrNull()?.take(80)}"
            }
        }
    }
}
