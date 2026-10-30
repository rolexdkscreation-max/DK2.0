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
    internal fun alone(): CoderAi = CoderAi(provider, key, model, base, emptyList(), note, sink)
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
            it.waitOnLimit = waitOnLimit; it.chain = modelChain
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
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
            .header("x-goog-api-key", key).post(body.toString().toRequestBody(JSON)).build()
        val o = JSONObject(exec(req))
        val p = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(0, "Gemini ने खाली जवाब दिया")
        val sb = StringBuilder()
        for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) sb.append(x.optString("text")) }
        return sb.toString()
    }

    private fun openAi(system: String, msgs: List<Pair<String, String>>, maxTok: Int, media: List<Pair<String, String>> = emptyList()): String {
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
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "medium")
        val req = Request.Builder().url(base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant").header("X-Title", "DK Coding Agent")
            .post(body.toString().toRequestBody(JSON)).build()
        val raw = try { exec(req) } catch (e: AiException) {
            // kuch free models itne lambe jawab nahi dete -> chhota maango
            if (e.code == 400 && maxTok > 4000 && (e.message ?: "").contains("token", true)) return openAi(system, msgs, maxTok / 2, media)
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
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

        /** DK कंप्यूटर की AI: सिर्फ़ Settings → AI दिमाग → DK कंप्यूटर की अपनी key. [sink] = कंप्यूटर की अपनी रिपोर्ट. */
        // v5 (मालिक): "DK कंप्यूटर पहले Gemini से कोशिश करे, फिर Groq, फिर OpenRouter, फिर Extra API - जिससे भी बने, उससे काम हो".
        // DK कंप्यूटर की अपनी keys (Settings → AI दिमाग → DK कंप्यूटर) में से जिसकी भी key डली है, सब इसी क्रम में एक chain में.
        fun forComputer(c: Context, sink: (String, String) -> Unit): CoderAi? {
            if (Prefs.oneKeyOn(c)) return forScope(c, ApiScopes.PC, sink)
            val parts = PC_CHAIN.mapNotNull { id -> forScope(c, ApiScopes.PC, sink, id) }
            val first = parts.firstOrNull() ?: return forScope(c, ApiScopes.PC, sink)
            val rest = first.fallbackList() + parts.drop(1).flatMap { listOf(it.alone()) + it.fallbackList() }
            return CoderAi(first.provider, first.keyForChain(), first.model, first.baseForChain(), rest, sink = sink)
        }

        /** DK कंप्यूटर की fallback chain का क्रम (मालिक का नियम) */
        val PC_CHAIN = listOf("gemini", "groq", "openrouter", "custom")

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
        fun forScope(c: Context, scope: String, sink: (String, String) -> Unit, providerId: String = Prefs.scopeActive(c, scope)): CoderAi? {
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
            return CoderAi(p, key, model, base, fb, sink = sink).also { it.waitOnLimit = scope == ApiScopes.CODER }
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

/**
 * Coding agent (v2): poora repo EK zip se phone me (tez padhna + asli grep line number ke saath),
 * ek baar me kai files padhna, kai edit ek saath, whitespace-sahansheel edit, diff se khud jaanch,
 * "done" se pehle syntax jaanch (bracket / XML / JSON), aur push ke baad GitHub Actions build dekhkar galti khud theek.
 */
class CodingAgent(private val gh: GitHub, var repo: String, var branch: String, private val ai: CoderAi) {
    /** ऑटोनोमस मोड की key वाला agent (अलग key = नया agent बने) */
    val aiNote get() = ai.autoTag
    /** v4.4: यह मॉडल एक बार में कितना पढ़ सके (2 लाख / 5 लाख) */
    private val budget: Int get() = ai.budget

    /** User ki chuni hui (main) branch. Surakshit PR mode me kaam hamesha alag feature branch par, PR isi me */
    val baseBranch = branch
    /** सुरक्षित PR मोड: main par seedha push kabhi nahi -> feature branch -> PR -> build/tests -> user Merge */
    var safePr = true
    /** User ने खुद पुराना फ़ीचर बदलने/हटाने को कहा (जानबूझकर बदलाव) - तब पहले backup, फिर बदलाव */
    var intentional = false
    /** पुराने फ़ीचर वाले हिस्से का backup (branch) - ज़रूरत पड़ने पर वापस लाने के लिए */
    var backupBranch = ""
        private set
    /** Bug/build की असली जड़ (root cause) - PR और रिपोर्ट में */
    var rootCause = ""
        private set
    /** इस feature branch पर अब तक कुछ push हुआ? (नहीं = खाली branch, काम के बाद हटा दो) */
    private var pushedToBranch = false
    /** Is kaam ka khula PR (null = abhi nahi bana) */
    var pr: GitHub.Pull? = null
        private set
    /** PR nahi ban paya to wajah (token permission etc.) */
    var prError = ""
        private set
    /** Aakhri merge ka commit sha (main par release APK build dekhne ke liye) */
    var lastMergeSha = ""
        private set
    /** checks चल रहे थे → इंतज़ार किया, सब पास → merge नहीं किया, user से हाँ दोबारा माँगी (गलती नहीं) */
    var lastMergeAsk = false
        private set
    /** PR dobara banane ke liye (pehli baar token ki wajah se na bana ho) */
    private var lastPushMsg = ""
    private var lastChangeText = ""
    private var lastTask = ""
    private var lastSummary = ""
    /** AGENTS.md + workflows - Reviewer ko bhi dikhate hain */
    private var projectCtx = ""

    /** stopped = एजेंट खुद रुका (एक ही चेतावनी बार-बार) - बदलाव push नहीं करने, user को वजह बतानी */
    data class Outcome(val summary: String, val commit: String, val question: Boolean, val stopped: Boolean = false)
    data class Build(val ok: Boolean, val url: String, val log: String)
    data class Call(val o: JSONObject, val file: String?, val finds: List<String>, val replaces: List<String>)
    /** Reviewer ka faisla */
    data class Review(val pass: Boolean, val issues: List<String>, val tested: String, val complete: Boolean = true)

    /** Team mode: Builder kabhi user se nahi poochta (khud sahi andaza), aur extra cheez sirf "suggest" karta hai */
    var teamMode = false
    /** Builder/Reviewer ko koi zaroori/faydemand cheez dikhi jo user ne nahi maangi -> (title, kyun, kaam) */
    var onSuggest: ((String, String, String) -> Unit)? = null

    /** path -> naya content (null = mitana) - abhi push nahi hua */
    val staged = LinkedHashMap<String, String?>()
    private val original = HashMap<String, String?>()
    private val msgs = mutableListOf<Pair<String, String>>()
    private var files: List<GitHub.Entry>? = null
    /** Poore repo ki copy (zip se) - null = nahi mili, tab har file API se */
    private var snap: Map<String, String>? = null
    private var checkWarned = false
    /** इस काम में कौन-सी फ़ाइलें पढ़ीं - बिना पढ़े पूरी फ़ाइल दोबारा नहीं लिखनी */
    private val readInTask = mutableSetOf<String>()
    /** Aakhri push ka commit sha (build dekhne ke liye) */
    var lastSha = ""
        private set

    /** एक काम में ज़्यादा से ज़्यादा कदम (Settings → कोडिंग एजेंट; default 5) */
    var maxSteps = DEFAULT_STEPS
    /** इस repo में लिखने की इजाज़त एक बार जाँच ली (हर push पर नहीं) */
    private var writeChecked = false

    fun reset() {
        staged.clear(); original.clear(); msgs.clear(); files = null; snap = null; checkWarned = false
        branch = baseBranch; pr = null; prError = ""; lastTask = ""; lastSummary = ""; projectCtx = ""
        lastPushMsg = ""; lastChangeText = ""
        intentional = false; backupBranch = ""; rootCause = ""; pushedToBranch = false
        workNote = ""; lastMergeAsk = false
    }

    /**
     * "आगे करो": [reset] जैसा, पर उसी रिपो की फ़ोन में रखी फ़ाइलें (snapshot) बची रहें - पूरा रिपो दोबारा न उतरे.
     * snapshot तभी बचे जब वह उसी branch का हो जिस पर आगे बढ़ना है (या branch अभी बनी ही नहीं थी).
     */
    fun resetKeepFiles(resumeBranch: String) {
        val keep = snap != null && (resumeBranch.isBlank() || resumeBranch == branch)
        val s = snap; val f = files
        reset()
        if (keep) { snap = s; files = f }
    }

    /** App restart ke baad pichhla khula PR wapas (Merge card ke liye). GitHub par band/merge mila to mergePr/push khud saaf karte hain */
    fun restorePr(p: GitHub.Pull) {
        if (!safePr || SafePr.isBlocked(p.head, baseBranch)) return
        pr = p; branch = p.head; prError = ""; pushedToBranch = true
    }

    /**
     * "वहीं से आगे" (ऐप बंद होने के बाद): पिछली feature branch वापस - GitHub पर हो तभी. उस पर push हुआ माना
     * (ताकि खाली समझकर मिटे नहीं). @return true = branch मिली
     */
    fun restoreBranch(name: String): Boolean {
        if (!safePr || SafePr.isBlocked(name, baseBranch)) return false
        val ok = try { gh.remoteBranchSha(repo, name).isNotBlank() } catch (_: Exception) { false }
        if (ok) { branch = name; pushedToBranch = true }
        return ok
    }

    /**
     * काम शुरू होते ही (कोड बदलने से पहले): main से नई feature branch. सारा काम, push और टेस्ट इसी में;
     * main को merge तक हाथ नहीं लगता. पहले से खुला PR/feature branch हो तो वही.
     * @return branch का नाम
     */
    suspend fun ensureBranch(task: String): String = withContext(Dispatchers.IO) {
        if (!safePr) return@withContext branch
        // PR GitHub पर ही merge/बंद हो गया हो -> पुरानी branch नहीं, नई
        pr?.let { old ->
            val open = try { gh.pullState(repo, old.number).open } catch (_: Exception) { true }
            if (!open) { pr = null; branch = baseBranch; pushedToBranch = false }
        }
        if (!SafePr.isBlocked(branch, baseBranch)) return@withContext branch
        var name = SafePr.branchName(task)
        try { gh.createBranch(repo, name, baseBranch) } catch (e: GitHub.Err) {
            // isi minute me isi naam ki branch pehle se (422) -> naam ke aage chhota number
            if (e.code != 422) throw e
            name = "$name-" + (System.currentTimeMillis() / 1000 % 100000)
            gh.createBranch(repo, name, baseBranch)
        }
        branch = name; pushedToBranch = false
        // पहली बार (msgs खाली) system prompt में ही branch है; बाद में बताओ
        if (msgs.isNotEmpty()) {
            msgs.add("user" to "NOTE: working branch is now \"$name\" (created from $baseBranch). All work and pushes go here; a PR goes to $baseBranch.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"ठीक है\"}")
        }
        name
    }

    // ---------------------------------------------------------------- 7-एजेंट सिस्टम के हाथ (पुराना एजेंट इन्हें इस्तेमाल नहीं करता)
    /** true = नया PR draft बने (7-एजेंट: टेस्ट के लिए draft, पास होने पर ही review के लिए तैयार). पुराना एजेंट: false */
    @Volatile var draftPr = false
    /** true = push पर PR नहीं (7-एजेंट: पहले branch पर build, पास हो तभी teamOpenPr). पुराना एजेंट: false */
    @Volatile var prLater = false

    /** रिपो की फ़ाइलें फ़ोन में (पहले से हों तो कुछ नहीं) */
    suspend fun teamLoad(onStep: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (files != null || snap != null) return@withContext
        onStep("$repo ($branch) की फ़ाइलें ला रहा हूँ…")
        snap = try { gh.snapshot(repo, branch).also { onStep("पूरा रिपो फ़ोन में आ गया (${it.size} फ़ाइलें)") } } catch (e: Exception) { null }
        files = snap?.let { m -> m.map { GitHub.Entry(it.key, it.value.length) } } ?: gh.tree(repo, branch)
    }
    fun teamList(): List<String> = allPaths()
    fun teamRead(path: String): String? = try { current(path) } catch (e: GitHub.Err) { if (e.code == 404) null else throw e }
    fun teamStage(path: String, content: String?) { remember(path); staged[path] = content }
    /** PR के विवरण के लिए: काम, क्या बदला, जड़ */
    fun teamInfo(task: String, summary: String, root: String) { lastTask = task; lastSummary = summary; rootCause = root }

    /** 📒 एजेंट की काम-कॉपी (markdown) - PR के विवरण में. PR न बना हो तो यहीं रहती है और PR बनते ही उसमें जाती है */
    var workNote = ""
        private set

    /** काम-कॉपी PR के विवरण में: सिर्फ़ काम-कॉपी वाला हिस्सा बदलता है, बाकी विवरण (किसी और का लिखा भी) अछूता */
    /** v3.89: काम-कॉपी सिर्फ़ याद रखो (फ़ोन में सहेजी जा चुकी) - अगली बार PR खुलने/बदलने पर उसी में जाएगी, अलग API call नहीं */
    fun teamKeepNote(md: String) { workNote = md }

    suspend fun teamSaveNote(md: String) = withContext(Dispatchers.IO) {
        workNote = md
        val p = pr ?: return@withContext
        try {
            val body = gh.pullDetail(repo, p.number)?.body.orEmpty()
            val nb = WorkNote.inBody(body, md)
            if (nb != body) gh.editPullBody(repo, p.number, nb)
        } catch (e: Exception) { DKLog.d("Coder: काम-कॉपी PR में नहीं लिखी: ${e.message?.take(120)}") }
    }

    /** खुले PR का विवरण (पिछली काम-कॉपी पढ़ने के लिए). "" = PR नहीं / पढ़ नहीं पाया */
    suspend fun teamPrBody(): String = withContext(Dispatchers.IO) {
        val p = pr ?: return@withContext ""
        try { gh.pullDetail(repo, p.number)?.body.orEmpty() } catch (_: Exception) { "" }
    }
    /** पूरा रिपो (बदलाव समेत) - नाम-जाँच के लिए. null = snapshot नहीं (बड़ा रिपो) */
    fun teamSnapshot(): Map<String, String>? = snap?.let { m ->
        HashMap(m).apply { staged.forEach { (p, c) -> if (c == null) remove(p) else put(p, c) } }
    }

    // ---------------------------------------------------------------- v4.4: पूरे काम का हिसाब (main से) + टूटी फ़ाइल वापस
    private val baseCache = HashMap<String, String?>()

    /** base branch (main) पर फ़ाइल = इस काम से पहले की असली शक्ल (दोबारा चले काम में भी सही) */
    fun baseRead(path: String): String? = synchronized(baseCache) {
        if (baseCache.containsKey(path)) return baseCache[path]
        val r = try { gh.read(repo, path, baseBranch) } catch (_: Exception) { null }
        baseCache[path] = r; r
    }

    /** इस काम ने main से क्या बदला: पहले के push (GitHub compare) + अभी के staged. हटाई/आधी फ़ाइलें पहचान के साथ */
    fun taskChanges(): List<CodeGuard.Change> {
        val m = LinkedHashMap<String, CodeGuard.Change>()
        if (branch.isNotBlank() && branch != baseBranch) try {
            gh.compareFiles(repo, baseBranch, branch).forEach { m[it.path] = it }
        } catch (e: Exception) { DKLog.d("Coder: main से तुलना नहीं मिली: ${e.message}") }
        for ((p, c) in staged.toMap()) {
            val b = baseRead(p)
            if (b == null && c == null) { m.remove(p); continue }
            val bl = CodeGuard.lines(b); val al = CodeGuard.lines(c)
            m[p] = CodeGuard.Change(p, if (c == null) "removed" else if (b == null) "added" else "modified", maxOf(0, al - bl), maxOf(0, bl - al))
        }
        return m.values.map { ch -> if (ch.status == "added" || ch.deletions < 30) ch else ch.copy(baseLines = CodeGuard.lines(baseRead(ch.path))) }
    }

    /** डिबगर के लिए: पूरे काम का हिसाब (हटाई/आधी फ़ाइलें सबसे ऊपर) */
    fun footprintText(): String = try { CodeGuard.footprint(taskChanges(), baseBranch) } catch (_: Exception) { "" }

    /** v4.4: build fail की जड़ = इसी काम ने फ़ाइल हटाई/आधी की -> main से हूबहू वापस (staged). लौटाता है क्या वापस आया */
    fun restoreDamaged(log: String): List<CodeGuard.Damage> {
        val d = try { CodeGuard.damage(log, taskChanges()) { baseRead(it) } } catch (e: Exception) { DKLog.d("Coder: नुकसान जाँच नहीं हुई: ${e.message}"); emptyList() }
        d.forEach { teamStage(it.path, it.baseContent) }
        if (d.isNotEmpty()) DKLog.d("Coder: main से वापस लाई: " + d.joinToString { it.path })
        return d
    }

    /** v4.4: रिव्यूअर को पक्की (बिना AI) चेतावनियाँ - हटाई/आधी फ़ाइल और उल्टी शर्तें */
    private fun guardNotes(): String = buildString {
        for ((p, c) in staged.toMap()) {
            val b = original[p]
            if (c == null && b != null) append("!! FILE DELETED: $p (${CodeGuard.lines(b)} lines) - an issue unless the task really needs this whole file gone and nothing uses it\n")
            else if (CodeGuard.shrunk(b, c)) append("!! FILE MOSTLY EMPTIED: $p (${CodeGuard.lines(b)} -> ${CodeGuard.lines(c)} lines) - check nothing needed was lost\n")
            CodeGuard.flips(b, c).forEach { append("!! CONDITION FLIPPED in $p: $it - a bug unless the task asks to invert it\n") }
        }
    }

    /** v4.4: फ़ाइल हटाने से पहले - बाकी रिपो में इस्तेमाल है? (रिपो की copy न हो तो GitHub खोज) */
    fun deleteBlock(path: String, content: String?): String? {
        if (content == null) return null
        val gone = staged.filterValues { it == null }.keys + path
        teamSnapshot()?.let { return CodeGuard.deleteProblem(path, content, it, gone) }
        val users = try { gh.search(repo, CodeGuard.stem(path)).filter { it != path && it !in gone } } catch (_: Exception) { emptyList() }
        return if (users.isEmpty()) null else "$path is still used by: ${users.take(6).joinToString()} - deleting it breaks the build. Remove ONLY the code the task asks for (small FIND/REPLACE edits), keep the file."
    }

    /** रिपो के GitHub Actions workflows: (फ़ाइल का नाम, content) */
    fun teamWorkflows(): List<Pair<String, String>> = allPaths()
        .filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }
        .mapNotNull { p -> (try { current(p) } catch (_: Exception) { null })?.let { p.substringAfterLast('/') to it } }

    /** इस branch पर workflow चलाओ (PR से पहले build). @return "" = चल गया, वरना वजह */
    suspend fun teamDispatch(workflowFile: String): String = withContext(Dispatchers.IO) {
        try { gh.dispatchWorkflow(repo, workflowFile, branch); "" } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    /** build पास होने के बाद PR खोलो. @return "" = बना (pr में), वरना वजह */
    suspend fun teamOpenPr(draft: Boolean): String = withContext(Dispatchers.IO) {
        if (pr != null) return@withContext ""
        val prot = SafePr.protectedIn(staged.keys)
        val body = SafePr.prBody(lastSummary, lastChangeText, prot, backupBranch, rootCause, workNote)
        val title = lastPushMsg.ifBlank { lastTask.take(80) }
        try {
            pr = gh.openPullFor(repo, branch) ?: if (draft) gh.createDraftPull(repo, branch, baseBranch, title, body)
                else gh.createPull(repo, branch, baseBranch, title, body)
            prError = ""; ""
        } catch (e: Exception) {
            prError = (e.message ?: e.javaClass.simpleName).take(200) + " — token में Pull requests: Read and write दें, या GitHub पर $branch से PR खुद बनाएँ"
            prError
        }
    }

    /** टेस्ट पास: draft PR → review के लिए तैयार. @return "" = ठीक, वरना वजह */
    suspend fun teamReady(): String = withContext(Dispatchers.IO) {
        val p = pr ?: return@withContext "PR नहीं है"
        try { if (gh.isDraft(repo, p.number)) gh.markReady(repo, p.number); "" } catch (e: Exception) { e.message ?: "draft नहीं हटा" }
    }

    /** काम में कुछ push नहीं हुआ (सिर्फ़ सवाल/fail) -> बनी हुई खाली feature branch हटा दो, GitHub साफ़ रहे */
    suspend fun dropEmptyBranch() = withContext(Dispatchers.IO) {
        if (!safePr || pushedToBranch || pr != null || staged.isNotEmpty() || SafePr.isBlocked(branch, baseBranch)) return@withContext
        val br = branch
        try { gh.deleteBranch(repo, br); DKLog.d("Coder: खाली branch $br हटाई") } catch (e: Exception) { DKLog.d("Coder: खाली branch नहीं हटी: ${e.message}") }
        branch = baseBranch
    }

    /**
     * पुराना फ़ीचर जानबूझकर बदलना/हटाना: बदलाव push होने से पहले main की अभी की हालत backup branch में
     * (backup/dk-…). ज़रूरत हो तो उसी से पुराना फ़ीचर वापस. एक काम में एक बार.
     * @return backup branch, या "" (नहीं बना - वजह log में; काम फिर भी PR में ही रहता है, main सुरक्षित)
     */
    suspend fun backupOld(task: String): String = withContext(Dispatchers.IO) {
        if (backupBranch.isNotBlank()) return@withContext backupBranch
        var name = SafePr.backupName(task)
        try {
            try { gh.createBranch(repo, name, baseBranch) } catch (e: GitHub.Err) {
                if (e.code != 422) throw e
                name = "$name-" + (System.currentTimeMillis() / 1000 % 100000)
                gh.createBranch(repo, name, baseBranch)
            }
            backupBranch = name
            pr?.let { p -> try { gh.editPullBody(repo, p.number, SafePr.prBody(lastSummary, changeList().ifBlank { lastChangeText }, SafePr.protectedIn(staged.keys), backupBranch, rootCause, workNote)) } catch (_: Exception) {} }
            name
        } catch (e: Exception) { DKLog.d("Coder: backup branch नहीं बनी: ${e.message}"); "" }
    }

    /** Push ho gaya (feature branch) par PR nahi bana -> screen par "PR फिर बनाएँ" */
    val canRetryPr get() = safePr && pr == null && prError.isNotBlank() && !SafePr.isBlocked(branch, baseBranch)

    /** PR dobara banao (token ki ijazat theek karne ke baad) */
    suspend fun retryPr(): String = withContext(Dispatchers.IO) {
        pr?.let { return@withContext "PR #${it.number} पहले से बना है: ${it.url}" }
        if (!safePr || SafePr.isBlocked(branch, baseBranch)) return@withContext "अभी कोई feature branch push नहीं हुई"
        prError = ""
        pr = try {
            gh.openPullFor(repo, branch) ?: gh.createPull(repo, branch, baseBranch, lastPushMsg.ifBlank { "DK: code changes" },
                SafePr.prBody(lastSummary, lastChangeText, emptyList(), backupBranch, rootCause, workNote))
        } catch (e: Exception) {
            prError = (e.message ?: e.javaClass.simpleName).take(200) +
                " — token में Pull requests: Read and write दें, या GitHub पर $branch से PR खुद बनाएँ"
            null
        }
        pr?.let { "✓ PR #${it.number} बन गया — tests + build पूरे होने पर \"Merge करें\" दबाएँ\n${it.url}" } ?: "PR फिर नहीं बना: $prError"
    }

    private fun system() = """
You are DK Coder, an expert software engineer working DIRECTLY on the GitHub repository "$repo" (branch "$branch") through tools.
The user speaks Hindi/Hinglish. Think carefully, read the relevant code before changing it, keep the existing style, and make complete, COMPILING changes.
If this is an Android/Kotlin project it is built by GitHub Actions after push: every import, type, parameter and bracket must be correct.

Reply with EXACTLY ONE tool call per message: a JSON object on the first line, optionally followed by content blocks. No other text.
Tools:
{"tool":"list","path":"optional/folder"}                  -> list files (all if path empty)
{"tool":"read","path":"a/File.kt"}                         -> read one file
{"tool":"read","paths":["a/A.kt","b/B.kt"]}                -> read up to 6 files at once (faster - prefer this)
{"tool":"read","path":"a/Big.kt","start":200,"end":320}    -> read only these lines (shown with line numbers; do NOT copy the "123| " prefix into edits)
{"tool":"grep","pattern":"words or regex","path":"optional/folder","regex":false} -> search ALL files, returns path:line: text
{"tool":"write","path":"file/path.ext"}                    -> create or fully replace a file; COMPLETE new content after it:
<<<FILE
...entire file content...
FILE>>>
{"tool":"edit","path":"file/path.ext"}                     -> replace exact unique snippet(s); you may give SEVERAL FIND/REPLACE pairs in one edit (applied in order):
<<<FIND
exact existing text (enough lines to be unique)
FIND>>>
<<<REPLACE
new text
REPLACE>>>
{"tool":"delete","path":"file/path.ext"}                   -> delete a file
{"tool":"diff"}                                            -> see all your staged changes as a diff (review before done)
{"tool":"ask","question":"Hindi question"}                 -> ONLY if the request is truly unclear; waits for the user${if (teamMode) " (TEAM MODE: do NOT use ask - make the most reasonable assumption and mention it in the summary)" else ""}
{"tool":"suggest","title":"short Hindi title","why":"simple Hindi: why it is needed/useful","task":"exact English task to implement it"} -> PROPOSE something the user did NOT ask for but that is technically needed or clearly better (missing permission, crash risk, security, data loss, obvious UX gap). It is NOT implemented; the user decides later. Then continue the main task.
Your FIRST tool call of every task must also carry "plan": one short Hindi sentence in this style -
  "पहले <कौन-सा कोड> पढ़ रहा हूँ (<क्या ढूँढ रहा हूँ: X, Y कहाँ होते हैं>), फिर <क्या ठीक/बनाऊँगा>" e.g.
  {"tool":"read","paths":["a/A.kt","b/B.kt"],"plan":"पहले कोडिंग एजेंट का कोड पढ़ रहा हूँ (push, loop और retry कहाँ होते हैं), फिर सब एक साथ ठीक करूँगा"}
{"tool":"done","summary":"Hindi summary (short bullet lines; if this fixed a bug/error also add: दिक्कत: <real cause>  हल: <what you changed>)","commit":"short English commit message","root_cause":"ONLY for bug/error/build/test fixes: file:line - what was really wrong and why"}

Rules:
- Work like a careful senior engineer: FIRST understand (read the exact code where the problem/feature lives, find the real cause),
  THEN make the COMPLETE change in every place it is needed (not half; no TODOs), THEN done. Never edit code you have not read.
- Steps are limited ($maxSteps, plus 1 extra for every file you successfully change). Be efficient: read/grep the needed files together in ONE call,
  then change EVERY file the task needs (one edit/write per file, all FIND/REPLACE pairs of that file together), then done. "diff" is optional.
- COMPLETE means wired in: if you create a new class/file, also update all callers/places that must use it (grep for them). A new file that nothing uses is NOT done.
- If the task names several files/features (e.g. A, B and C must use X), change ALL of them before done.
- Large files are shown in numbered chunks; read only the chunk/lines you need (start/end), not the whole file again.
- Changes are only STAGED; the app pushes them to GitHub (API commit) automatically AFTER "done". Do NOT try to push, and never claim it is pushed. Call done when the edits are staged.
- Check your own work before done (imports, names, brackets, unused leftovers).
- Never put secrets/API keys in code. Do not delete files unless the user asked or it is clearly required.
- REMOVING A FEATURE/OPTION/SETTING (v4.4): remove ONLY its own lines (its UI row, its pref key/function, its uses) with small edits.
  NEVER delete or rewrite a shared file (Prefs, Settings/Activities, strings/translation files like *.tsv/*.xml) for it - deleting a file that is still used is blocked automatically.
  "write" of an existing file that drops more than half of its lines is also blocked: use edit with FIND/REPLACE.
- Be precise: never invert a condition (if (!x) -> if (x)), never drop a "!", else-branch, return, argument or call unless the task needs exactly that.
  Before done, run "diff" and check EVERY removed line: was removing it really part of the task?
- BUILD FAILED with many "Unresolved reference"/"cannot find symbol" errors: first check whether THIS task deleted or emptied the file that defined those names
  (the task FOOTPRINT shows it). Then the fix is to bring that file back exactly from the base branch - never re-create it piece by piece. Read ALL error lines, not only the first.
- NEVER add features, settings, files or behaviour the user did not ask for. If you think something extra is needed or useful, use "suggest" (max 2 per task, only if it really matters) and do NOT implement it.
- If the user only asks a question about the code, read what you need and answer in "done" summary with commit "".
- If a task says the BUILD FAILED, read the exact files/lines from the log, fix the real cause, and do not change unrelated code.
- ROOT CAUSE FIRST for every bug, error, failed build or failed test (also bugs the user reports): read the code/log, find WHY it happens,
  then fix that cause (not the symptom), and give "root_cause" in done. Never delete, skip or weaken existing tests to make them pass.
- Do NOT touch existing working features: change only the files/lines the task needs. No refactors, renames, reformatting or "cleanups" of other code.
- Protected files (.github/workflows/*, AGENTS.md, gradle.properties, settings.gradle*, gradlew, keystores): change them ONLY if the user explicitly asked for it.${safeRules()}
""".trimIndent()

    private fun safeRules() = if (!safePr) "" else """

SAFE PR MODE (always follow):
- Work flow: understand the repo first (PROJECT CONTEXT: AGENTS.md rules, existing agents, GitHub Actions workflows) -> change code -> "diff" self-check -> done.
- Your changes are NEVER pushed to "$baseBranch". The app commits them to a NEW feature branch (feature/dk-…), opens a Pull Request to "$baseBranch",
  and GitHub Actions runs the unit tests + debug build on that PR. If it fails you get the log and fix it on the same branch.
- Only the user merges the PR after review. Never say it is merged or released.
- If you add logic that can be unit tested without Android (pure Kotlin), add/extend a JUnit test in app/src/test/java/... in the same style.
- Old features must keep working: the PR runs ALL existing unit tests. Do not delete or weaken existing tests.${if (intentional) """
- INTENTIONAL CHANGE: the user explicitly asked to change/remove an EXISTING feature. That is allowed (a backup of $baseBranch was taken first).
  Change/remove exactly that feature (and its now-obsolete tests if it is removed) and nothing else.""" else ""}"""

    /** Repo ki pehchaan: AGENTS.md, workflows, maujooda agents (snapshot na mile to API se) */
    private fun loadProjectContext(): String = try {
        SafePr.projectContext(snap ?: contextFromApi())
    } catch (e: Exception) { DKLog.d("Coder: project context nahi mila: ${e.message}"); "" }

    private fun contextFromApi(): Map<String, String> {
        val all = (files ?: emptyList()).map { it.path }
        val want = all.filter { it == "AGENTS.md" || (it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml"))) }.take(8)
        val out = LinkedHashMap<String, String>()
        all.filter { it.endsWith(".kt") }.forEach { out[it] = "" }
        want.forEach { out[it] = gh.read(repo, it, baseBranch).orEmpty() }
        return out
    }

    /** Ek kaam chalao (ya pichhle "ask" ka jawab). onStep = UI ke liye chhoti line */
    suspend fun run(task: String, onStep: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        if (msgs.isEmpty()) {
            onStep("$repo ($branch) की फ़ाइलें ला रहा हूँ…")
            snap = try { gh.snapshot(repo, branch).also { onStep("पूरा repo फ़ोन में आ गया (${it.size} फ़ाइलें)") } }
                   catch (e: Exception) { DKLog.d("Coder: zip nahi mila (${e.message}) - har file API se"); null }
            files = snap?.let { m -> m.map { GitHub.Entry(it.key, it.value.length) } } ?: gh.tree(repo, branch)
            // पूरी list नहीं - सिर्फ़ ज़रूरी (code/res/build) फ़ाइलें; बाकी list/grep से
            val list = importantPaths(files!!.map { it.path }, 250).joinToString("\n")
            msgs.add("user" to "Repository files (${files!!.size}, showing the important ones):\n$list" + (if (files!!.size > 250) "\n…(more: use list with a folder, or grep)" else ""))
            msgs.add("model" to "{\"tool\":\"list\",\"path\":\"\"}")
            projectCtx = loadProjectContext()
            if (projectCtx.isNotBlank()) {
                onStep("repo के नियम, agents और GitHub Actions समझ लिए")
                msgs.add("user" to "PROJECT CONTEXT (read and follow before any change):\n" + projectCtx.take(maxOf(6000, budget / 20)))
                msgs.add("model" to "{\"tool\":\"diff\"}")
                msgs.add("user" to "RESULT (diff):\n(no staged changes)")
            }
            msgs.add("user" to "OK. Waiting for the task.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"क्या करना है?\"}")
        }
        checkWarned = false
        readInTask.clear()
        var planShown = false
        if (!task.startsWith("BUILD FAILED") && !task.startsWith("REVIEWER") && !task.startsWith("Coordinator:")) lastTask = task
        msgs.add("user" to "TASK FROM USER: $task" +
            if (staged.isNotEmpty()) "\n(Already staged, not pushed: ${staged.keys.joinToString()})" else "")
        var step = 0
        val guard = WarnGuard(2)
        /** एक ही चेतावनी लगातार 2 बार -> रुककर वजह बताओ (बार-बार का चक्र नहीं) */
        fun repeated(w: String): Outcome? = if (!guard.hit(w)) null else
            Outcome("एजेंट रुक गया: एक ही चेतावनी लगातार 2 बार आई —\n\"${w.lineSequence().first().take(200)}\"\n" +
                "आगे: काम को छोटे और साफ़ हिस्से में लिखकर फिर भेजें (कौन-सी फ़ाइल, क्या बदलना है)।", "", false, stopped = true)
        var looks = 0          // बदलाव से पहले के पढ़ने वाले कदम (समझने का हिस्सा)
        var changed = false
        var unusedWarned = false
        val fixTask = SafePr.isFixTask(task)
        var rootWarned = false
        val changedFiles = mutableSetOf<String>()   // हर बदली फ़ाइल = 1 अतिरिक्त कदम (कई फ़ाइलों वाला काम आधा न रहे)
        while (true) {
            ensureActive()
            // पहले समझना: बदलाव से पहले के 2 पढ़ने वाले कदम सीमा में नहीं गिने जाते
            if (++step > stepLimit(maxSteps, looks, changedFiles.size)) return@withContext Outcome("$maxSteps कदम हो गए, यहीं रोका। अब तक के बदलाव नीचे हैं। " +
                "(ज़्यादा कदम चाहिए तो Settings → कोडिंग एजेंट → \"एक काम में ज़्यादा से ज़्यादा कदम\")", "DK: partial changes", false)
            // हर कदम के बीच थोड़ा रुको (free API की प्रति-मिनट limit जल्दी खत्म न हो)
            if (step > 1) delay(STEP_DELAY_MS)
            val reply = try { ai.chat(system(), view()) } catch (e: AiException) {
                throw AiException(e.code, "${ai.label}: ${e.message}")
            }
            msgs.add("model" to reply)
            val call = parse(reply)
            if (call == null) {
                val w = "ERROR: your reply had no valid JSON tool call on the first line. Reply with exactly one tool call."
                repeated(w)?.let { onStep("चेतावनी: AI ने दो बार गलत जवाब दिया — रुक गया"); return@withContext it }
                onStep("…")
                msgs.add("user" to w)
                continue
            }
            val o = call.o
            val tool = o.optString("tool"); val path = o.optString("path").trim().trimStart('/')
            // पहले बताओ क्या करने वाला है (एक बार): AI की "plan" लाइन, न हो तो पहले कदम से बनी
            if (!planShown && tool != "ask") {
                planShown = true
                val pl = o.optString("plan").trim().take(300).ifBlank { autoPlan(tool, path, o.optJSONArray("paths")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), o.optString("pattern")) }
                if (pl.isNotBlank()) { onStep("योजना: $pl"); if (CoderReport.plan().isBlank()) CoderReport.plan(pl) }
            }
            if (tool == "ask") return@withContext Outcome(o.optString("question").ifBlank { "थोड़ा और बताइए?" }, "", true)
            if (tool == "done") {
                // staged बदलाव यहाँ push नहीं होते - "done" के बाद ऐप GitHub API से एक commit में भेजता है.
                // (पहले यहाँ "staged हैं पर push नहीं हुए" वाली जाँच थी: done हर बार लौट जाता था = अंतहीन चक्र)
                val problems = check()
                if (problems.isNotEmpty() && !checkWarned) {
                    checkWarned = true
                    onStep("जाँच में दिक्कत मिली, ठीक करवा रहा हूँ…")
                    msgs.add("user" to "CHECK FAILED before done (these would likely break the build):\n$problems\n" +
                        "Fix them (read the file again), or call done again if you are sure they are false alarms.")
                    continue
                }
                // नई फ़ाइल बनी पर कहीं इस्तेमाल नहीं = काम अधूरा (एक बार याद दिलाओ)
                val unused = unusedNewFiles(staged.filter { (p, c) -> c != null && original.containsKey(p) && original[p] == null }.mapValues { it.value!! },
                    staged.filterValues { it != null }.mapValues { it.value!! })
                if (unused.isNotEmpty() && !unusedWarned) {
                    unusedWarned = true
                    onStep("नई फ़ाइल अभी कहीं इस्तेमाल नहीं हुई — बाकी जगह भी जोड़ने को कहा")
                    msgs.add("user" to "INCOMPLETE before done: you created ${unused.joinToString { "${it.first} (${it.second})" }} but no other changed file uses it. " +
                        "Grep for the places the task mentions and update them to use it now. Call done again only if the task really asked for just this file.")
                    continue
                }
                // bug / reviewer / build की गलती: पहले असली जड़ (root cause), तभी fix माना जाए
                val rc = o.optString("root_cause").trim()
                if (fixTask && staged.isNotEmpty() && rc.length < 8 && !rootWarned) {
                    rootWarned = true
                    onStep("पहले समस्या की जड़ (root cause) बताने को कहा")
                    msgs.add("user" to "ROOT CAUSE REQUIRED before done: this task fixes a bug/failure. First make sure you found the REAL cause " +
                        "(read the exact code/log lines). Then call done again with \"root_cause\": \"file:line - what was wrong and why\". " +
                        "If your change only hides the symptom, fix the real cause first.")
                    continue
                }
                if (rc.isNotBlank()) { rootCause = rc.take(400); CoderReport.add("जड़ (root cause): ${rc.take(200)}", "बिल्डर ने यही असली वजह ठीक की") }
                if (staged.isNotEmpty()) lastSummary = o.optString("summary")
                return@withContext Outcome(o.optString("summary").ifBlank { "हो गया।" },
                    o.optString("commit").ifBlank { if (staged.isEmpty()) "" else "DK: code changes" }, false)
            }
            val result = try { runTool(tool, path, call, onStep) } catch (e: GitHub.Err) { "GITHUB ERROR: ${e.message}" }
            if (result.startsWith("ERROR") || result.startsWith("NOT FOUND") || result.startsWith("GITHUB ERROR")) {
                repeated("$tool: $result")?.let { onStep("चेतावनी दोबारा आई — रुक गया"); return@withContext it }
            } else {
                guard.ok()
                if (tool in listOf("write", "edit", "delete")) { changed = true; changedFiles.add(path) }
                else if (!changed && tool in listOf("list", "read", "grep", "search")) looks++
            }
            msgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n$result")
        }
        Outcome("", "", false)
    }

    private fun allPaths(): List<String> {
        val gone = staged.filterValues { it == null }.keys
        val base = files?.map { it.path } ?: snap?.keys?.toList() ?: emptyList()
        return (base + staged.filterValues { it != null }.keys).distinct().filter { it !in gone }
    }

    private fun runTool(tool: String, path: String, c: Call, onStep: (String) -> Unit): String {
        val o = c.o
        when (tool) {
            "list" -> {
                onStep("फ़ाइलें देखीं: ${path.ifBlank { "/" }}")
                if (files == null) files = gh.tree(repo, branch)
                return allPaths().filter { path.isBlank() || it.startsWith(path.trimEnd('/') + "/") || it == path }
                    .take(800).joinToString("\n").ifBlank { "(no files)" }
            }
            "read" -> {
                val many = o.optJSONArray("paths")
                if (many != null && many.length() > 0) {
                    val ps = (0 until many.length()).map { many.optString(it).trim().trimStart('/') }.filter { it.isNotBlank() }.take(6)
                    onStep("पढ़ रहा हूँ: " + ps.joinToString(", ") { it.substringAfterLast('/') })
                    readInTask.addAll(ps)
                    val per = (CoderContext.readChars(budget) * 2) / ps.size.coerceAtLeast(1)   // v4.4: मॉडल जितना पढ़ सके
                    return ps.joinToString("\n\n") { p ->
                        val t = current(p)
                        "===== $p =====\n" + when {
                            t == null -> "NOT FOUND"
                            t.length > per -> CoderContext.chunk(t, 1, per, p)
                            else -> t
                        }
                    }
                }
                val t = current(path) ?: return "NOT FOUND: $path" + similar(path)
                readInTask.add(path)
                val start = o.optInt("start", 0); val end = o.optInt("end", 0)
                if (start > 0 || end > 0) {
                    val lines = t.lines()
                    val a = start.coerceIn(1, lines.size.coerceAtLeast(1)); val b = (if (end > 0) end else a + 150).coerceIn(a, lines.size)
                    onStep("पढ़ रहा हूँ: $path ($a-$b)")
                    val part = "(${lines.size} lines total)\n" + (a..b).joinToString("\n") { "$it| ${lines[it - 1]}" }
                    // बहुत लंबी range भी एक हिस्से (chunk) से बड़ी नहीं
                    return if (part.length > CoderContext.readChars(budget)) CoderContext.chunk(t, a, CoderContext.readChars(budget), path) else part
                }
                onStep("पढ़ रहा हूँ: $path")
                // बड़ी फ़ाइल पूरी नहीं - हिस्सों में (हर हिस्सा line नंबर के साथ, अगला हिस्सा कैसे पढ़ें)
                return if (t.length > CoderContext.readChars(budget)) CoderContext.chunk(t, 1, CoderContext.readChars(budget), path) else t
            }
            "grep", "search" -> {
                val q = o.optString("pattern").ifBlank { o.optString("query") }
                if (q.isBlank()) return "ERROR: pattern missing"
                onStep("खोज: $q")
                return grep(q, path, o.optBoolean("regex", false))
            }
            "write" -> {
                if (path.isBlank()) return "ERROR: path missing"
                val content = c.file ?: return "ERROR: missing <<<FILE ... FILE>>> block"
                // पहले समझो, फिर बदलो: मौजूदा फ़ाइल को बिना पढ़े पूरा नहीं बदलना (नई फ़ाइल ठीक है)
                if (path !in readInTask && !staged.containsKey(path) && current(path) != null)
                    return "ERROR: $path already exists and you have not read it in this task. Read it first (or use edit with FIND/REPLACE). Nothing staged."
                // v4.4: पूरी फ़ाइल दोबारा लिखते समय आधी से ज़्यादा गायब = गलती (बचा कोड छूट गया) - edit से छोटा बदलाव करो
                val old = current(path)
                if (CodeGuard.shrunk(old, content))
                    return "ERROR: NOT staged. This write would remove ${CodeGuard.lines(old) - CodeGuard.lines(content)} of ${CodeGuard.lines(old)} lines of $path " +
                        "(more than half the file). Keep every other line exactly - use edit with FIND/REPLACE to change only what the task needs."
                remember(path)
                staged[path] = content
                onStep("लिखा: $path (${content.lines().size} लाइन)")
                return "OK staged $path (${content.length} chars)"
            }
            "edit" -> {
                if (c.finds.isEmpty() || c.finds.size != c.replaces.size)
                    return "ERROR: need matching <<<FIND ... FIND>>> and <<<REPLACE ... REPLACE>>> blocks (${c.finds.size} FIND, ${c.replaces.size} REPLACE)"
                var cur = current(path) ?: return "NOT FOUND: $path" + similar(path)
                val notes = mutableListOf<String>()
                for ((k, f) in c.finds.withIndex()) {
                    val r = c.replaces[k]
                    if (f.isEmpty()) return "ERROR: FIND #${k + 1} is empty. Nothing applied."
                    val n = cur.split(f).size - 1
                    cur = when {
                        n == 1 -> cur.replace(f, r)
                        n > 1 -> return "ERROR: FIND #${k + 1} occurs $n times in $path. Include more surrounding lines. Nothing applied."
                        else -> fuzzyReplace(cur, f, r)?.also { notes.add("#${k + 1} matched ignoring indentation") }
                            ?: return "ERROR: FIND #${k + 1} not found in $path. Read the file again (use start/end) and copy the exact text. Nothing applied." + near(cur, f)
                    }
                }
                remember(path); staged[path] = cur
                onStep("बदला: $path" + if (c.finds.size > 1) " (${c.finds.size} जगह)" else "")
                return "OK edited $path (${c.finds.size} change${if (c.finds.size > 1) "s" else ""})" + if (notes.isNotEmpty()) " - " + notes.joinToString() else ""
            }
            "delete" -> {
                val cur = current(path) ?: return "NOT FOUND: $path"
                // v4.4: इस्तेमाल हो रही फ़ाइल कभी नहीं हटती (पहले "option हटाओ" पर पूरी Prefs.kt/SettingsActivity.kt हट गई थीं)
                deleteBlock(path, cur)?.let { return "ERROR: NOT deleted. $it" }
                remember(path); staged[path] = null
                onStep("हटाया: $path"); return "OK staged delete $path"
            }
            "suggest" -> {
                val t = o.optString("title").trim(); val w = o.optString("why").trim(); val tk = o.optString("task").trim()
                if (t.isBlank() || tk.isBlank()) return "ERROR: suggest needs title, why and task"
                onSuggest?.invoke(t, w, tk)
                onStep("सुझाव: $t")
                return "OK, suggestion sent to the user. Do NOT implement it now. Continue the main task."
            }
            "diff" -> {
                onStep("अपने बदलाव जाँच रहा हूँ…")
                return diffAll().ifBlank { "(no staged changes)" }.let { val m = maxOf(40_000, budget / 3); if (it.length > m) it.take(m) + "\n…(diff cut)" else it }
            }
            else -> return "ERROR: unknown tool '$tool'"
        }
    }

    // ---------------------------------------------------------------- files
    /** Staged ho to wahi, phir repo ki copy, warna GitHub se */
    private fun current(path: String): String? =
        if (staged.containsKey(path)) staged[path]
        else original.getOrPut(path) { snap?.get(path) ?: if (snap != null && files?.none { it.path == path } == true) null else gh.read(repo, path, branch) }

    private fun remember(path: String) { if (!original.containsKey(path)) original[path] = try { snap?.get(path) ?: gh.read(repo, path, branch) } catch (_: Exception) { null } }

    private fun similar(path: String): String {
        val name = path.substringAfterLast('/')
        val hits = allPaths().filter { it.endsWith("/$name") || it.substringAfterLast('/').equals(name, true) }.take(5)
        return if (hits.isEmpty()) "" else "\nDid you mean: " + hits.joinToString()
    }

    /** Saari files me khojo: path:line: text */
    private fun grep(q: String, folder: String, regex: Boolean): String {
        val rx = if (regex) try { Regex(q) } catch (e: Exception) { return "ERROR: bad regex: ${e.message}" } else null
        val out = StringBuilder(); var hits = 0; var filesHit = 0
        val paths = allPaths().filter { folder.isBlank() || it.startsWith(folder.trimEnd('/') + "/") }
        if (snap == null && staged.isEmpty()) {
            // repo ki copy nahi hai -> GitHub search (kam bharosemand)
            val r = try { gh.search(repo, q) } catch (_: Exception) { emptyList() }
            val byName = paths.filter { it.contains(q, true) }
            return (r + byName).distinct().take(40).joinToString("\n").ifBlank { "No results." }
        }
        for (p in paths) {
            val t = (if (staged.containsKey(p)) staged[p] else snap?.get(p)) ?: continue
            var inFile = 0
            t.lineSequence().forEachIndexed { i, line ->
                val m = if (rx != null) rx.containsMatchIn(line) else line.contains(q, true)
                if (m && hits < 120 && inFile < 25) { out.append("$p:${i + 1}: ${line.trim().take(220)}\n"); hits++; inFile++ }
            }
            if (inFile > 0) filesHit++
            if (p.contains(q, true) && inFile == 0) out.append("$p (file name matches)\n")
        }
        return if (out.isEmpty()) "No results for '$q'." else (if (hits >= 120) "(first 120 matches)\n" else "") + out.toString()
    }

    /** FIND exact nahi mila: har line ke aage-peeche ke space chhodkar milao (sirf ek jagah mile to) */
    private fun fuzzyReplace(cur: String, find: String, rep: String): String? {
        val fl = find.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (fl.isEmpty()) return null
        val cl = cur.lines()
        val hits = (0..cl.size - fl.size).filter { s -> fl.indices.all { cl[s + it].trim() == fl[it] } }
        if (hits.size != 1) return null
        val s = hits[0]
        return (cl.subList(0, s) + rep.lines() + cl.subList(s + fl.size, cl.size)).joinToString("\n")
    }

    /** FIND na mile to sabse milti-julti jagah dikhao (model ko sahi text copy karne me madad) */
    private fun near(cur: String, find: String): String {
        val first = find.lines().map { it.trim() }.firstOrNull { it.length > 6 } ?: return ""
        val cl = cur.lines()
        val i = cl.indexOfFirst { it.trim() == first }.takeIf { it >= 0 } ?: cl.indexOfFirst { it.contains(first.take(25)) }.takeIf { it >= 0 } ?: return ""
        return "\nClosest place (lines ${i + 1}-${minOf(cl.size, i + 8)}):\n" + (i until minOf(cl.size, i + 8)).joinToString("\n") { cl[it] }
    }

    // ---------------------------------------------------------------- jaanch
    /** Push se pehle: bracket balance (Kotlin/Java), XML aur JSON sahi hai? */
    fun check(): String {
        val sb = StringBuilder()
        staged.forEach { (p, c) ->
            if (c == null) return@forEach
            val ext = p.substringAfterLast('.', "").lowercase()
            val err = when (ext) {
                "kt", "kts", "java", "gradle" -> brackets(c)
                "xml" -> try {
                    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(c.byteInputStream()); null
                } catch (e: Exception) { "XML error: ${e.message?.take(200)}" }
                "json" -> try { if (c.trimStart().startsWith("[")) JSONArray(c) else JSONObject(c); null } catch (e: Exception) { "JSON error: ${e.message?.take(200)}" }
                else -> null
            }
            if (err != null) sb.append("- $p: $err\n")
            if (c.contains("<<<FIND") || c.contains("FIND>>>") || c.contains("<<<FILE") || c.contains("REPLACE>>>"))
                sb.append("- $p: contains leftover <<<FIND/FILE markers\n")
        }
        // पुराने features के tests चुपके से हटाए/कम नहीं (user ने खुद फ़ीचर हटाने को न कहा हो)
        SafePr.testGuard(staged, staged.keys.associateWith { original[it] ?: snap?.get(it) }, intentional && SafePr.removesOldFeature(lastTask))
            .forEach { sb.append("- $it\n") }
        return sb.toString()
    }

    /** Kotlin/Java: {} () [] ka hisaab (string/comment ke andar wale chhodkar). null = theek */
    private fun brackets(src: String): String? {
        val stack = ArrayDeque<Pair<Char, Int>>()
        var i = 0; var line = 1
        val n = src.length
        val pairs = mapOf('}' to '{', ')' to '(', ']' to '[')
        while (i < n) {
            val ch = src[i]
            when {
                ch == '\n' -> line++
                src.startsWith("//", i) -> { while (i < n && src[i] != '\n') i++; continue }
                src.startsWith("/*", i) -> { val e = src.indexOf("*/", i + 2); if (e < 0) return "unclosed /* comment (line $line)"; line += src.substring(i, e).count { it == '\n' }; i = e + 2; continue }
                src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3); if (e < 0) return "unclosed \"\"\" string (line $line)"; line += src.substring(i, e).count { it == '\n' }; i = e + 3; while (i < n && src[i] == '"') i++; continue }
                ch == '"' -> {
                    // saada string: \ escape; ${ ... } ke andar ke quotes ko bhi sambhalo
                    i++; var depth = 0
                    while (i < n) {
                        val c = src[i]
                        if (c == '\\') { i += 2; continue }
                        if (c == '\n' && depth == 0) return "string not closed on line $line"
                        if (c == '$' && i + 1 < n && src[i + 1] == '{') { depth++; i += 2; continue }
                        if (depth > 0 && c == '}') { depth--; i++; continue }
                        if (depth > 0 && c == '"') { val e = src.indexOf('"', i + 1); if (e < 0) break; i = e + 1; continue }
                        if (c == '"' && depth == 0) break
                        i++
                    }
                }
                ch == '\'' -> {
                    val e = if (i + 1 < n && src[i + 1] == '\\') src.indexOf('\'', i + 2) else i + 2
                    if (e in (i + 1)..(i + 8) && e < n && src[e] == '\'') { i = e + 1; continue }
                }
                ch == '{' || ch == '(' || ch == '[' -> stack.addLast(ch to line)
                ch == '}' || ch == ')' || ch == ']' -> {
                    val top = stack.removeLastOrNull() ?: return "extra '$ch' on line $line"
                    if (top.first != pairs[ch]) return "'${top.first}' from line ${top.second} closed by '$ch' on line $line"
                }
            }
            i++
        }
        return stack.lastOrNull()?.let { "'${it.first}' opened on line ${it.second} is never closed" }
    }

    // ---------------------------------------------------------------- diff
    /** Saare staged badlaav unified diff jaise (2 line context) */
    fun diffAll(): String = staged.entries.joinToString("\n") { (p, c) ->
        val o = original[p]
        when {
            c == null -> "--- $p (DELETED, ${o?.lines()?.size ?: 0} lines)"
            o == null -> "+++ $p (NEW FILE, ${c.lines().size} lines)\n" + c.lines().take(400).joinToString("\n") { "+$it" }
            else -> "=== $p\n" + diff(o, c)
        }
    }

    private fun diff(a0: String, b0: String): String {
        val a = a0.lines(); val b = b0.lines()
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf); val bm = b.subList(pre, b.size - suf)
        // (line no, op, text)  op: ' ' '-' '+'
        val ops = ArrayList<Triple<Int, Char, String>>()
        if (am.size.toLong() * bm.size <= 2_500_000L) {
            val n = am.size; val m = bm.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                dp[i][j] = if (am[i] == bm[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            var i = 0; var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && am[i] == bm[j] -> { ops.add(Triple(pre + j + 1, ' ', am[i])); i++; j++ }
                    j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j]) -> { ops.add(Triple(pre + j + 1, '+', bm[j])); j++ }
                    else -> { ops.add(Triple(pre + j + 1, '-', am[i])); i++ }
                }
            }
        } else {
            am.forEach { ops.add(Triple(pre + 1, '-', it)) }
            bm.forEachIndexed { k, t -> ops.add(Triple(pre + k + 1, '+', t)) }
        }
        val ctxBefore = a.subList(maxOf(0, pre - 2), pre).mapIndexed { k, t -> Triple(maxOf(0, pre - 2) + k + 1, ' ', t) }
        val ctxAfter = b.subList(b.size - suf, minOf(b.size, b.size - suf + 2)).mapIndexed { k, t -> Triple(b.size - suf + k + 1, ' ', t) }
        val all = ctxBefore + ops + ctxAfter
        // sirf badlaav ke aas-paas ki 2 line
        val keep = BooleanArray(all.size)
        all.forEachIndexed { k, t -> if (t.second != ' ') for (x in (k - 2)..(k + 2)) if (x in all.indices) keep[x] = true }
        val sb = StringBuilder(); var gap = false
        all.forEachIndexed { k, t ->
            if (keep[k]) { if (gap) sb.append("@@ line ${t.first}\n"); gap = false; sb.append(t.second).append(t.third).append('\n') }
            else gap = true
        }
        return sb.toString().trimEnd()
    }

    /** Badlaav ki chhoti list: "+ naya", "- hataya", "~ badla (+a -b)" */
    fun changeList(): String = staged.entries.joinToString("\n") { (p, c) ->
        val o = original[p]
        when {
            c == null -> "−  $p (हटाया)"
            o == null -> "+  $p (नई, ${c.lines().size} लाइन)"
            else -> {
                val d = diff(o, c).lines()
                "~  $p (+${d.count { it.startsWith("+") }} −${d.count { it.startsWith("-") }})"
            }
        }
    }

    // ---------------------------------------------------------------- Reviewer agent
    private fun reviewerSystem() = """
You are DK Reviewer, a strict senior code reviewer AND tester for repository "$repo" (branch "$branch").
The Builder agent has STAGED changes (not pushed) for the user's task. Your job, without asking anyone:
1. Read the diff and the surrounding code. Use grep to confirm every function/class/field/import the new code uses really exists with that signature.
2. "Test" by tracing execution: walk through the feature step by step (normal path, empty/null input, errors, threads/coroutines, Android lifecycle, permissions). You cannot run code - reason carefully like a debugger.
3. Find: compile errors, bugs, logic mistakes, crashes, missing pieces of the task, and ANY change the user did not ask for (unrequested extras are an issue).
4. Do not nitpick style. Only real problems that would break the build, the feature, or existing behaviour.
5. Existing working features must stay untouched: any edit to code unrelated to the task, or to protected files
   (.github/workflows/*, AGENTS.md, gradle.properties, settings.gradle*, gradlew, keystores) that the user did not explicitly ask for, is an issue.
Reply with EXACTLY ONE tool call per message (JSON on the first line, no other text):
{"tool":"list","path":""}  {"tool":"read","path":"..."}  {"tool":"read","paths":["...","..."]}  {"tool":"read","path":"...","start":1,"end":80}
{"tool":"grep","pattern":"...","path":"","regex":false}  {"tool":"diff"}
{"tool":"suggest","title":"Hindi","why":"Hindi","task":"English"}   -> something important the user did not ask for (not an issue, just a proposal)
{"tool":"verdict","pass":true,"issues":[],"tested":"Hindi: what you checked/traced"}
{"tool":"verdict","pass":false,"issues":["path:line - problem - exact fix"],"tested":"Hindi: what you checked"}
6. Regression: old features must keep working. A deleted/weakened existing test, or a fix that hides a symptom instead of the real cause, is an issue.
7. LINE BY LINE (v4.4): compare every removed line with what replaced it. An inverted condition (a "!" added/removed, == vs !=, < vs >=, && vs ||), a dropped else/return/argument/call,
   or a changed default value is an issue unless the task asks for it. Lines marked "!!" above were found automatically - check each one.
8. A deleted or mostly emptied file is an issue if anything still uses its classes/functions/keys (grep them). Removing an option means removing ONLY that option's code.
pass=true ONLY if you are confident it compiles and the task works completely.${if (intentional) "\nINTENTIONAL CHANGE: the user explicitly asked to change/remove an existing feature (backup taken). Changing/removing exactly that feature is NOT an issue." else ""}
""".trimIndent()

    /**
     * Reviewer: staged badlaav ko bina user se pooche jaanchta hai (padhta, grep, raasta trace) aur faisla deta hai.
     * Static jaanch (bracket/XML/JSON) pehle se jod di jaati hai.
     */
    suspend fun review(task: String, acceptance: List<String>, round: Int, onStep: (String) -> Unit): Review = withContext(Dispatchers.IO) {
        if (staged.isEmpty()) return@withContext Review(true, emptyList(), "कोई बदलाव नहीं था", true)
        val rmsgs = mutableListOf<Pair<String, String>>()
        val static = check()
        val d = diffAll().let { val m = maxOf(20_000, budget / 3); if (it.length > m) it.take(m) + "\n…(diff cut - use read)" else it }   // v4.4: पूरा diff
        rmsgs.add("user" to "REVIEW ROUND $round\nUSER TASK: $task\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE CRITERIA:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (projectCtx.isNotBlank()) "PROJECT RULES:\n${projectCtx.take(maxOf(5000, budget / 20))}\n" else "") +
            SafePr.protectedIn(staged.keys).let { if (it.isEmpty()) "" else "PROTECTED FILES CHANGED: ${it.joinToString()} (issue unless the task explicitly asks)\n" } +
            "STATIC CHECK: " + static.ifBlank { "OK (brackets/XML/JSON fine)" } + "\n" + guardNotes() + "\nSTAGED DIFF:\n$d")
        var bad = 0
        for (step in 1..(maxSteps + 2)) {
            ensureActive()
            if (step > 1) delay(STEP_DELAY_MS)
            val reply = try { ai.chat(reviewerSystem(), if (CoderContext.total(rmsgs) > budget) CoderContext.compact(rmsgs, budget) else rmsgs) } catch (e: AiException) { throw AiException(e.code, "Reviewer ${ai.label}: ${e.message}") }
            rmsgs.add("model" to reply)
            val call = parse(reply)
            if (call == null) {
                if (++bad >= 3) break
                rmsgs.add("user" to "ERROR: reply with exactly one JSON tool call."); continue
            }
            bad = 0
            val o = call.o; val tool = o.optString("tool"); val path = o.optString("path").trim().trimStart('/')
            if (tool == "verdict") {
                val arr = o.optJSONArray("issues") ?: JSONArray()
                val issues = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                val pass = o.optBoolean("pass", issues.isEmpty()) && issues.isEmpty() && static.isBlank()
                val allIssues = if (static.isNotBlank()) issues + static.lines().filter { it.isNotBlank() }.map { it.removePrefix("- ") } else issues
                return@withContext Review(pass, allIssues, o.optString("tested"), true)
            }
            val result = when (tool) {
                "list", "read", "grep", "search", "diff", "suggest" -> try { runTool(tool, path, call) { st -> onStep("रिव्यूअर: $st") } } catch (e: GitHub.Err) { "GITHUB ERROR: ${e.message}" }
                else -> "ERROR: reviewer is READ-ONLY. Use list/read/grep/diff, then verdict."
            }
            rmsgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n" + result.take(CoderContext.readChars(budget)))
        }
        // Reviewer faisla nahi de paya: static jaanch hi aadhaar
        Review(static.isBlank(), static.lines().filter { it.isNotBlank() }.map { it.removePrefix("- ") }, "रिव्यूअर पूरा जवाब नहीं दे पाया — सिर्फ़ static जाँच हुई", false)
    }

    // ---------------------------------------------------------------- push + build
    /**
     * Push: saare staged ek commit me.
     * Surakshit PR mode: main par kabhi nahi -> pehli baar nayi feature branch banti hai, phir PR (ya pehle wala PR update).
     */
    suspend fun push(message: String): String = withContext(Dispatchers.IO) {
        val msg = message.ifBlank { "DK: code changes" }
        val changeText = changeList()
        val prot = SafePr.protectedIn(staged.keys)
        // काम की feature branch (आमतौर पर काम शुरू होते ही बन चुकी; न बनी हो तो अब). PR बंद/merge हो गया हो तो नई
        ensureBranch(lastTask.ifBlank { msg })
        if (safePr && SafePr.isBlocked(branch, baseBranch)) throw IllegalStateException("सुरक्षित PR मोड: $branch पर सीधे push मना है")
        // token में Contents: Read and write है? (एक बार) - न हो तो साफ़ बताओ क्या बदलना है
        if (!writeChecked) {
            gh.checkWrite(repo)?.let { throw IllegalStateException(it) }
            writeChecked = true
        }
        // v4.4: आख़िरी पहरा - इस्तेमाल हो रही फ़ाइल हटाने वाला commit कभी नहीं (किसी भी रास्ते से आया हो)
        staged.filterValues { it == null }.keys.forEach { p -> deleteBlock(p, original[p] ?: baseRead(p))?.let { throw IllegalStateException("push रोका: $it") } }
        // GitHub API से सीधा commit (git push नहीं); commit() खुद पक्का करता है कि branch नए commit पर पहुँची
        val url = gh.commit(repo, branch, staged.toMap(), msg)
        pushedToBranch = true
        lastSha = url.substringAfterLast('/')
        lastPushMsg = msg; lastChangeText = changeText
        // ab GitHub par yahi naya code hai (repo ki copy bhi)
        val m = snap?.toMutableMap()
        staged.forEach { (p, c) -> original[p] = c; if (m != null) { if (c == null) m.remove(p) else m[p] = c } }
        if (m != null) { snap = m; files = m.map { GitHub.Entry(it.key, it.value.length) } } else files = null
        staged.clear()
        msgs.add("user" to "PUSHED to $branch: $url. The repository now contains these changes.")
        msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"और कुछ?\"}")
        if (safePr && pr == null && !prLater) {
            prError = ""
            pr = try {
                gh.openPullFor(repo, branch) ?: if (draftPr) gh.createDraftPull(repo, branch, baseBranch, msg, SafePr.prBody(lastSummary, changeText, prot, backupBranch, rootCause, workNote))
                else gh.createPull(repo, branch, baseBranch, msg, SafePr.prBody(lastSummary, changeText, prot, backupBranch, rootCause, workNote))
            } catch (e: Exception) {
                prError = (e.message ?: e.javaClass.simpleName).take(200) +
                    " — token में Pull requests: Read and write दें, या GitHub पर $branch से PR खुद बनाएँ"
                DKLog.d("Coder: PR nahi bana: $prError"); null
            }
        }
        url
    }

    /**
     * Review ke baad user ka "Merge": sirf tab jab PR khula ho, takraav na ho, aur aakhri commit ke
     * saare GitHub Actions (unit tests + build) safal hon. Merge ke baad branch hatao aur agent main par wapas.
     * @return Hindi nateeja
     */
    suspend fun mergePr(onStep: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        lastMergeSha = ""; lastMergeAsk = false
        val p = pr ?: return@withContext "कोई PR नहीं है"
        var st = gh.pullState(repo, p.number)
        // PR के tests के बाद main आगे बढ़ गया? तो पुराने tests पर merge नहीं: branch को नए main से update करो,
        // सारे tests फिर चलाओ (पुराने + नए features साथ), पास हों तभी merge
        if (st.open && !st.merged && st.mergeable != false) {
            val behind = try { gh.behindBy(repo, baseBranch, p.head.ifBlank { branch }) } catch (_: Exception) { 0 }
            if (behind > 0) {
                onStep("$baseBranch $behind commit आगे बढ़ गया — PR branch update करके tests दोबारा चला रहा हूँ…")
                try { gh.updateBranch(repo, p.number, st.headSha) } catch (e: GitHub.Err) {
                    return@withContext "$baseBranch आगे बढ़ गया है और PR branch update नहीं हो पाई (${e.message}) — merge नहीं किया"
                }
                // GitHub नया head commit बनाता है - उसका इंतज़ार, फिर उसी के tests
                var newSha = st.headSha
                for (i in 0 until 20) { delay(3_000); newSha = try { gh.pullState(repo, p.number).headSha } catch (_: Exception) { newSha }; if (newSha != st.headSha) break }
                if (newSha == st.headSha) return@withContext "PR branch update हो रही है — थोड़ी देर बाद फिर \"merge कर दो\" कहें"
                val b = watchBuild(newSha, onStep) ?: return@withContext "नए $baseBranch के साथ tests का नतीजा नहीं मिला — merge नहीं किया, थोड़ी देर बाद फिर कहें"
                if (!b.ok) return@withContext "नए $baseBranch के साथ tests fail हुए — merge नहीं किया (पुराना और नया code साथ नहीं चल रहा)\n${b.url}"
                st = gh.pullState(repo, p.number)
            }
        }
        if (st.merged) { reset(); return@withContext "PR #${p.number} पहले ही merge हो चुका है" }
        if (!st.open) { reset(); return@withContext "PR #${p.number} GitHub पर बंद कर दिया गया है — अगला काम नई branch और नए PR में होगा" }
        if (st.mergeable == false) return@withContext "PR में $baseBranch से टकराव (conflict) है — पहले उसे ठीक करें"
        val runs = try { gh.runsFor(repo, st.headSha) } catch (e: GitHub.Err) {
            return@withContext "Build/tests नहीं देख पाया (${e.message}) — token में Actions: Read दें"
        }
        if (runs.isEmpty()) return@withContext "इस PR पर अभी कोई build/tests नहीं चला — थोड़ी देर बाद फिर कोशिश करें"
        if (runs.any { it.status != "completed" }) {
            // checks अभी चल रहे: यह गलती नहीं - पूरे होने तक इंतज़ार, फिर नतीजा बताकर merge की हाँ दोबारा माँगो
            onStep("⏳ PR #${p.number} के checks अभी चल रहे हैं — पूरे होने तक इंतज़ार कर रहा हूँ (merge अभी नहीं)…")
            val b = watchBuild(st.headSha, onStep)
                ?: return@withContext "⏳ PR #${p.number} के checks अभी चल रहे हैं (20 मिनट में पूरे नहीं हुए) — merge नहीं किया। पूरे होने पर फिर \"merge करो\" बोलें"
            if (!b.ok) return@withContext "Build/tests fail हैं — merge नहीं किया\n${b.url}"
            lastMergeAsk = true
            return@withContext "✓ PR #${p.number} के सारे checks अब पास हो गए (build + tests)।\nMerge करूँ? \"हाँ, merge करो\" बोलें — तभी merge होकर नई APK बनेगी।\n${p.url}"
        }
        runs.firstOrNull { it.conclusion !in listOf("success", "skipped", "neutral") }?.let {
            return@withContext "Build/tests fail हैं (${it.name}) — merge नहीं किया\n${it.url}"
        }
        val sha = gh.mergePull(repo, p.number, "${lastTask.ifBlank { p.head.ifBlank { "DK changes" } }.take(90)} (#${p.number})")
        lastMergeSha = sha
        val br = branch
        try { gh.deleteBranch(repo, br) } catch (_: Exception) {}
        reset()
        "✓ PR #${p.number} $baseBranch में merge हो गया (${sha.take(7)}), branch $br हटा दी"
    }

    /**
     * Push ke baad GitHub Actions build ka intezaar. null = is repo me build (workflow) nahi / dekh nahi paya.
     */
    suspend fun watchBuild(sha: String, onStep: (String) -> Unit): Build? = watchBuildFor(sha, null, onStep)

    /**
     * डिप्लॉयमेंट एजेंट: merge (sha) के बाद APK release.
     * push से build चला और नई release बनी = ठीक. नहीं चला / release पुरानी = release workflow खुद चलाओ (एक बार) और फिर जाँचो.
     * @return (build नतीजा या null, release या null, "" = release पक्की वरना कमी)
     */
    suspend fun deployRelease(sha: String, mergedMs: Long, onStep: (String) -> Unit): Triple<Build?, SevenPlus.Deploy.Rel?, String> {
        var b = watchBuild(sha, onStep)
        if (b != null && !b.ok) return Triple(b, null, "release build fail")
        var rel = withContext(Dispatchers.IO) { try { gh.latestRelease(repo) } catch (_: Exception) { null } }
        var why = SevenPlus.Deploy.verify(rel, sha, mergedMs)
        if (why.isBlank()) return Triple(b, rel, "")
        val wfs = withContext(Dispatchers.IO) {
            try {
                gh.tree(repo, baseBranch).map { it.path }
                    .filter { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }
                    .mapNotNull { p -> gh.read(repo, p, baseBranch)?.let { p.substringAfterLast('/') to it } }
            } catch (_: Exception) { emptyList() }
        }
        val wf = SevenPlus.Deploy.releaseWorkflow(wfs) ?: return Triple(b, rel, "$why — release बनाने वाला workflow नहीं मिला")
        onStep("🚀 डिप्लॉयमेंट: $why — APK build अपने-आप नहीं चला, $wf खुद चला रहा हूँ ($baseBranch)")
        val e = withContext(Dispatchers.IO) { try { gh.dispatchWorkflow(repo, wf, baseBranch); "" } catch (x: Exception) { x.message ?: x.javaClass.simpleName } }
        if (e.isNotBlank()) return Triple(b, rel, "$why — $wf नहीं चला पाया: ${e.take(150)} (token में Actions: Read and write दें)")
        b = watchBuildFor(sha, "workflow_dispatch", onStep) ?: return Triple(null, rel, "$why — हाथ से चलाए build का नतीजा नहीं दिखा")
        if (!b.ok) return Triple(b, null, "release build fail")
        rel = withContext(Dispatchers.IO) { try { gh.latestRelease(repo) } catch (_: Exception) { null } }
        why = SevenPlus.Deploy.verify(rel, sha, mergedMs)
        return Triple(b, rel, why)
    }

    /** event = सिर्फ़ इस तरह से चले runs ("pull_request" / "workflow_dispatch"); null = सब */
    suspend fun watchBuildFor(sha: String, event: String?, onStep: (String) -> Unit): Build? = withContext(Dispatchers.IO) {
        if (sha.isBlank()) return@withContext null
        val t0 = System.currentTimeMillis()
        var said = ""
        var reruns = 0
        delay(8_000)
        while (System.currentTimeMillis() - t0 < 20 * 60_000L) {
            ensureActive()
            val runs = try { gh.runsFor(repo, sha).filter { event == null || it.event == event } } catch (e: GitHub.Err) {
                onStep(if (e.code == 403 || e.code == 404) "Build नहीं देख पाया — token में Actions: Read की इजाज़त दें" else "Build नहीं देख पाया: ${e.message}")
                return@withContext null
            } catch (e: Exception) { delay(10_000); continue }
            if (runs.isEmpty()) {
                if (System.currentTimeMillis() - t0 > 90_000) return@withContext null
                delay(8_000); continue
            }
            // v3.89: कोई भी एक जाँच फ़ेल हो चुकी तो बाक़ी (जैसे 8-10 मिनट वाली emulator जाँच) का इंतज़ार नहीं -
            // नतीजा वैसे भी फ़ेल ही है; तुरंत log लेकर डिबगर को (PR #105 में हर राउंड ~8 मिनट बेकार जाते थे)
            val failedEarly = runs.firstOrNull { it.status == "completed" && it.conclusion !in listOf("success", "skipped", "neutral") }
            if (failedEarly != null || runs.all { it.status == "completed" }) {
                val bad = failedEarly
                    ?: return@withContext Build(true, runs.first().url, "")
                val log = try { gh.failureLog(repo, bad.id) } catch (e: Exception) { "(log नहीं मिला: ${e.message})" }
                // GitHub की अपनी दिक्कत (नेटवर्क/runner) - code नहीं बदलना; वही job अपने-आप दोबारा (2 बार तक)
                if (Retry.isInfraFailure(log) && reruns < Retry.ATTEMPTS - 1) {
                    reruns++
                    val ok = try { gh.rerunFailed(repo, bad.id); true } catch (e: Exception) { DKLog.d("Coder: rerun नहीं हुआ: ${e.message}"); false }
                    if (ok) {
                        onStep("Build GitHub की नेटवर्क/runner दिक्कत से fail — अपने-आप दोबारा चला रहा हूँ ($reruns/${Retry.ATTEMPTS - 1})")
                        CoderReport.add("GitHub Actions नेटवर्क/runner दिक्कत से fail (code की गलती नहीं)", "वही job अपने-आप दोबारा चलाई")
                        said = ""; delay(15_000); continue
                    }
                }
                return@withContext Build(false, bad.url, log)
            }
            val st = runs.joinToString { "${it.name}: " + if (it.status == "in_progress") "चल रहा" else "कतार में" }
            if (st != said) { said = st; onStep("Build: $st (${(System.currentTimeMillis() - t0) / 1000}s)") }
            delay(12_000)
        }
        null
    }

    fun discard() {
        staged.clear()
        msgs.add("user" to "User DISCARDED all staged changes. Repository is unchanged.")
        msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"ठीक है, क्या बदलूँ?\"}")
    }

    /**
     * AI को भेजने वाला छोटा इतिहास: शुरू की repo list + अभी का काम + पिछले कुछ संदेश (पूरा chat history हर बार नहीं).
     * staged फ़ाइलों का नाम हमेशा साथ, ताकि छोटा करने पर भी एजेंट को पता रहे कि क्या बदल चुका.
     */
    private fun view(): List<Pair<String, String>> {
        // repo के नियम (AGENTS.md आदि) छोटा करने पर भी साथ रहें - पर तभी जब वे पुराने हिस्से में चले गए हों
        val rules = if (projectCtx.isBlank() || msgs.size <= CoderContext.KEEP_TAIL + 6) "" else "PROJECT RULES (short):\n" + projectCtx.take(3000) + "\n\n"
        val st = if (staged.isEmpty()) "" else "STAGED (not pushed yet, the app pushes after done): " + staged.keys.joinToString()
        // v4.4: मॉडल की पूरी क्षमता (पहले हमेशा 60 हज़ार - बड़ी फ़ाइलें/लंबे काम का इतिहास कट जाता था)
        return CoderContext.compact(msgs, budget, CoderContext.KEEP_TAIL, (rules + st).trim())
    }

    companion object {
        /** एक काम में default कदम (Settings में 3-30) */
        const val DEFAULT_STEPS = 5
        /** हर कदम के बीच रुकना (ms) */
        const val STEP_DELAY_MS = 1500L
        /** बदलाव से पहले समझने (पढ़ने) के लिए अलग कदम */
        const val READ_BONUS = 2

        /** कुल कदम: Settings वाले + समझने के (ज़्यादा से ज़्यादा 2) + हर बदली फ़ाइल का 1 (ज़्यादा से ज़्यादा 8) */
        fun stepLimit(maxSteps: Int, looks: Int, changedFiles: Int): Int = maxSteps + minOf(looks, READ_BONUS) + minOf(changedFiles, 8)

        /**
         * नई Kotlin/Java फ़ाइलें जिनका मुख्य नाम (class/object/interface) किसी और बदली फ़ाइल में नहीं आता.
         * @return (path, नाम)
         */
        fun unusedNewFiles(newFiles: Map<String, String>, allChanged: Map<String, String>): List<Pair<String, String>> =
            newFiles.mapNotNull { (p, c) ->
                if (!(p.endsWith(".kt") || p.endsWith(".java")) || p.contains("/test/")) return@mapNotNull null
                val name = Regex("""(?m)^\s*(?:(?:public|internal|private|data|enum|sealed|abstract|open|final)\s+)*(?:class|object|interface)\s+([A-Za-z_]\w*)""")
                    .find(c)?.groupValues?.get(1) ?: return@mapNotNull null
                val used = allChanged.any { (op, oc) -> op != p && Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(oc) }
                if (used) null else p to name
            }

        /** AI ने "plan" नहीं दी तो पहले कदम से हिंदी योजना: "पहले … पढ़ रहा हूँ, फिर …" */
        fun autoPlan(tool: String, path: String, paths: List<String>, pattern: String): String {
            val names = (paths.ifEmpty { listOf(path) }).map { it.substringAfterLast('/') }.filter { it.isNotBlank() }.distinct()
            val what = when (tool) {
                "read" -> if (names.isEmpty()) "" else "पहले ${names.take(4).joinToString(", ")} का कोड पढ़ रहा हूँ"
                "grep", "search" -> if (pattern.isBlank()) "" else "पहले कोड में \"${pattern.take(40)}\" कहाँ है, ढूँढ रहा हूँ"
                "list" -> "पहले repo की फ़ाइलें देख रहा हूँ"
                "edit", "write", "delete" -> if (names.isEmpty()) "" else "${names.first()} में बदलाव कर रहा हूँ"
                else -> ""
            }
            if (what.isBlank()) return ""
            return if (tool in listOf("edit", "write", "delete")) "$what, फिर जाँचकर काम पूरा करूँगा" else "$what, फिर समझकर पूरा काम ठीक करूँगा"
        }

        /** शुरू में भेजी जाने वाली फ़ाइलें: पहले code/res/build की, फिर बाकी; [max] तक */
        fun importantPaths(all: List<String>, max: Int): List<String> {
            val skip = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico", ".jar", ".zip", ".apk", ".so", ".ttf", ".otf", ".mp3", ".wav")
            val code = listOf(".kt", ".java", ".kts", ".gradle", ".xml", ".md", ".yml", ".yaml", ".json", ".toml", ".properties", ".txt")
            val ok = all.filter { p -> skip.none { p.endsWith(it, true) } }
            val first = ok.filter { p -> code.any { p.endsWith(it, true) } }
            return (first + ok.filter { it !in first }).take(max)
        }

        /** Pehli line ka JSON + <<<FILE / FIND / REPLACE>>> blocks (kai FIND/REPLACE jode ho sakte hain) */
        fun parse(reply: String): Call? {
            val txt = reply.trim().replace(Regex("^```(?:json)?\\s*"), "")
            val head = txt.substringBefore("<<<")
            val s = head.indexOf('{'); if (s < 0) return null
            var depth = 0; var inStr = false; var esc = false; var end = -1
            for (i in s until head.length) {
                val ch = head[i]
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
                when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
            }
            if (end < 0) return null
            val o = try { JSONObject(head.substring(s, end + 1)) } catch (_: Exception) { return null }
            fun all(tag: String) = Regex("(?s)<<<$tag\\r?\\n(.*?)\\r?\\n?$tag>>>").findAll(txt).map { it.groupValues[1] }.toList()
            return Call(o, all("FILE").firstOrNull(), all("FIND"), all("REPLACE"))
        }
    }
}
