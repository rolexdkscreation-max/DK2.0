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
              private val fallbacks: List<CoderAi> = emptyList(), note: String = "",
              /** रिपोर्ट कहाँ जाए (कोडिंग टीम = CoderReport, DK कंप्यूटर = उसकी अपनी रिपोर्ट) */
              private val sink: (String, String) -> Unit = { p, f -> CoderReport.add(p, f) }) {
    private val selfLabel = "${provider.name} ($model$note)"
    /** Abhi jo AI chal rahi hai (limit par switch hua ho to wahi dikhe) */
    val label: String get() = sticky()?.takeIf { l -> fallbacks.any { it.selfLabel == l } } ?: selfLabel

    /**
     * Ek API ki limit/quota (429) ya server dikkat ho to agla model/API - kaam beech me na ruke.
     * Pehle chuna hua, phir baaki API. Sab ki limit ek saath khatm ho to ek baar thoda rukkar dobara.
     */
    fun chat(system: String, msgs: List<Pair<String, String>>): String {
        var attempt = 1
        while (true) {
            try {
                val r = chatChain(system, msgs)
                if (attempt > 1) sink("AI API अस्थायी गलती (नेटवर्क/सर्वर)", "अपने-आप दोबारा कोशिश की ($attempt/${Retry.ATTEMPTS}) — सफल")
                return r
            } catch (e: java.io.IOException) {
                // सारी keys की limit (429) या key/मॉडल की गलती (4xx) = दोबारा कोशिश बेकार, तुरंत रिपोर्ट
                val code = (e as? AiException)?.code ?: -1
                if (attempt >= Retry.ATTEMPTS || !Retry.isTransient(code, e.message.orEmpty())) {
                    if (attempt > 1) sink("AI API $attempt बार fail (${reason(e)})", "${Retry.ATTEMPTS} बार अपने-आप कोशिश के बाद भी नहीं चला — आपको रिपोर्ट किया")
                    throw e
                }
                DKLog.d("Coder: AI ${reason(e)} - अपने-आप दोबारा (${attempt + 1}/${Retry.ATTEMPTS})")
                Thread.sleep(Retry.backoffMs(attempt) * 2)
                attempt++
            }
        }
    }

    /** एक बार पूरी chain: चुनी API → बाकी API (limit/server गलती पर अगली) */
    private fun chatChain(system: String, msgs: List<Pair<String, String>>): String {
        val chain = listOf(this) + fallbacks
        val ordered = orderChain(chain.map { it.selfLabel }, sticky()).map { l -> chain.first { it.selfLabel == l } }
        val errs = mutableListOf<Pair<CoderAi, java.io.IOException>>()
        var n429 = 0
        for (ai in ordered) {
            // 429 के बाद: थोड़ा रुककर (2s, 4s, 8s) अगला मॉडल/API - एक साथ सब पर धावा नहीं
            if (n429 > 0 && (errs.lastOrNull()?.second as? AiException)?.code == 429) Thread.sleep(CoderContext.backoffMs(n429))
            try {
                val r = ai.chatOne(system, msgs)
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
            }
        }
        // सब fail. सब की limit (429) खत्म = लगातार कोशिश नहीं - रुककर user को बताओ
        val wait = quotaWaitSec(errs.map { ((it.second as? AiException)?.code ?: -1) to ((it.second as? AiException)?.retryAfter ?: 0) })
        val tried = errs.joinToString { "${it.first.provider.name}: ${(it.second as? AiException)?.code ?: "network"}" }
        if (wait > 0) {
            sink("सारी API keys और मॉडल की limit एक साथ खत्म (429)", "लगातार कोशिश नहीं की — काम रोककर आपको बताया")
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
    private fun chatOne(system: String, msgs: List<Pair<String, String>>): String {
        val k = "${provider.id}|${key.takeLast(6)}|$model"
        var (budget, maxTok) = sizeCap[k] ?: (CoderContext.DEFAULT_BUDGET to 16000)
        var shrinks = 0
        while (true) {
            val room = (budget - system.length).coerceAtLeast(CoderContext.MIN_BUDGET)
            val send = if (CoderContext.total(msgs) > room) CoderContext.compact(msgs, room) else msgs
            try {
                return if (provider.isGemini) gemini(system, send) else openAi(system, send, maxTok)
            } catch (e: AiException) {
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

    private fun gemini(system: String, msgs: List<Pair<String, String>>): String {
        // चुना मॉडल -> 3.8 Flash (coding के लिए सबसे अच्छा) -> बाकी नए मॉडल. 2.5 वाले backup नहीं (नए users के लिए बंद)
        val all = GeminiModels.order(model, listOf(GeminiModels.CODER) + GeminiClient.BACKUP_MODELS)
        // jis model ki limit abhi khatm hui thi use thodi der chhodo (har kadam par wahi 429 dobara na khaayein)
        val now = System.currentTimeMillis()
        val order = all.filter { (cool["${key.takeLast(6)}|$it"] ?: 0L) < now }.ifEmpty { all }
        val errs = mutableListOf<Pair<String, AiException>>()
        for (m in order) {
            var waited = false
            while (true) {
                try {
                    val r = geminiOnce(m, system, msgs)
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

    private fun geminiOnce(m: String, system: String, msgs: List<Pair<String, String>>): String {
        val contents = JSONArray()
        msgs.forEach { (r, t) ->
            contents.put(JSONObject().put("role", if (r == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
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

    private fun openAi(system: String, msgs: List<Pair<String, String>>, maxTok: Int): String {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { (r, t) -> arr.put(JSONObject().put("role", if (r == "user") "user" else "assistant").put("content", t)) }
        val body = JSONObject().put("model", model).put("messages", arr).put("max_tokens", maxTok).put("temperature", 0.2)
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "medium")
        val req = Request.Builder().url(base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/dk-assistant").header("X-Title", "DK Coding Agent")
            .post(body.toString().toRequestBody(JSON)).build()
        val raw = try { exec(req) } catch (e: AiException) {
            // kuch free models itne lambe jawab nahi dete -> chhota maango
            if (e.code == 400 && maxTok > 4000 && (e.message ?: "").contains("token", true)) return openAi(system, msgs, maxTok / 2)
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
        fun forComputer(c: Context, sink: (String, String) -> Unit): CoderAi? = forScope(c, ApiScopes.PC, sink)

        /** कोडिंग एजेंट की AI: सिर्फ़ Settings → AI दिमाग → कोडिंग एजेंट की अपनी key. null = key नहीं */
        fun fromPrefs(c: Context): CoderAi? = forScope(c, ApiScopes.CODER) { p, f -> CoderReport.add(p, f) }

        /**
         * एक हिस्से की AI: उसकी चुनी कंपनी + उसी की key. दूसरी कंपनी/दूसरे हिस्से की key पर कभी नहीं.
         * Limit (429) / मॉडल बंद: Gemini अपने अंदर उसी key के दूसरे मॉडल; Groq/OpenRouter/Custom के लिए
         * उसी key के बैकअप मॉडल fallback में.
         */
        fun forScope(c: Context, scope: String, sink: (String, String) -> Unit): CoderAi? {
            val p = Providers.byId(Prefs.scopeActive(c, scope)) ?: return null
            val key = Prefs.scopeKey(c, scope, p.id)
            val model = Prefs.scopeModel(c, scope, p.id)
            val base = Prefs.scopeBase(c, scope, p.id)
            if (key.isBlank() || model.isBlank() || (!p.isGemini && base.isBlank())) return null
            val fb = if (p.isGemini) emptyList() else Prefs.scopeBackups(c, scope, p.id)
                .map { m -> CoderAi(p, key, m, base, note = ", उसी key का दूसरा मॉडल", sink = sink) }
            return CoderAi(p, key, model, base, fb, sink = sink)
        }

        /** 413 के बाद हर API/मॉडल की request सीमा (अक्षर, maxTokens) - अगली बार पहले से छोटी */
        private val sizeCap = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

        /** सारी keys की limit खत्म: साफ़ हिंदी - क्या हुआ और आगे क्या करें */
        fun allLimitsMessage(waitSec: Int, tried: String, noFallback: Boolean): String =
            "सारी API keys और मॉडल की limit अभी खत्म है (429), इसलिए काम रोक दिया — लगातार कोशिश नहीं की।\n" +
                "आगे: लगभग ${if (waitSec >= 60) "${waitSec / 60} मिनट" else "$waitSec सेकंड"} बाद काम फिर भेजें" +
                (if (noFallback) ", या Settings → AI दिमाग में इसी हिस्से की मुख्य API बदलकर दूसरी फ्री key (जैसे Groq / OpenRouter) डालें" else "") + "।" +
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
    }

    /** App restart ke baad pichhla khula PR wapas (Merge card ke liye). GitHub par band/merge mila to mergePr/push khud saaf karte hain */
    fun restorePr(p: GitHub.Pull) {
        if (!safePr || SafePr.isBlocked(p.head, baseBranch)) return
        pr = p; branch = p.head; prError = ""; pushedToBranch = true
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
            pr?.let { p -> try { gh.editPullBody(repo, p.number, SafePr.prBody(lastSummary, changeList().ifBlank { lastChangeText }, SafePr.protectedIn(staged.keys), backupBranch, rootCause)) } catch (_: Exception) {} }
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
                SafePr.prBody(lastSummary, lastChangeText, emptyList(), backupBranch, rootCause))
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
                msgs.add("user" to "PROJECT CONTEXT (read and follow before any change):\n" + projectCtx.take(6000))
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
                    val per = (CoderContext.CHUNK_CHARS * 2) / ps.size.coerceAtLeast(1)
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
                    return if (part.length > CoderContext.CHUNK_CHARS) CoderContext.chunk(t, a, path = path) else part
                }
                onStep("पढ़ रहा हूँ: $path")
                // बड़ी फ़ाइल पूरी नहीं - हिस्सों में (हर हिस्सा line नंबर के साथ, अगला हिस्सा कैसे पढ़ें)
                return if (t.length > CoderContext.CHUNK_CHARS) CoderContext.chunk(t, 1, path = path) else t
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
                if (current(path) == null) return "NOT FOUND: $path"
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
                return diffAll().ifBlank { "(no staged changes)" }.let { if (it.length > 40_000) it.take(40_000) + "\n…(diff cut)" else it }
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
        val d = diffAll().let { if (it.length > 20_000) it.take(20_000) + "\n…(diff cut - use read)" else it }
        rmsgs.add("user" to "REVIEW ROUND $round\nUSER TASK: $task\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE CRITERIA:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (projectCtx.isNotBlank()) "PROJECT RULES:\n${projectCtx.take(5000)}\n" else "") +
            SafePr.protectedIn(staged.keys).let { if (it.isEmpty()) "" else "PROTECTED FILES CHANGED: ${it.joinToString()} (issue unless the task explicitly asks)\n" } +
            "STATIC CHECK: " + static.ifBlank { "OK (brackets/XML/JSON fine)" } + "\n\nSTAGED DIFF:\n$d")
        var bad = 0
        for (step in 1..(maxSteps + 2)) {
            ensureActive()
            if (step > 1) delay(STEP_DELAY_MS)
            val reply = try { ai.chat(reviewerSystem(), if (CoderContext.total(rmsgs) > CoderContext.DEFAULT_BUDGET) CoderContext.compact(rmsgs) else rmsgs) } catch (e: AiException) { throw AiException(e.code, "Reviewer ${ai.label}: ${e.message}") }
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
            rmsgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n" + result.take(CoderContext.CHUNK_CHARS))
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
        if (safePr && pr == null) {
            prError = ""
            pr = try {
                gh.openPullFor(repo, branch) ?: gh.createPull(repo, branch, baseBranch, msg, SafePr.prBody(lastSummary, changeText, prot, backupBranch, rootCause))
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
        lastMergeSha = ""
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
        if (runs.any { it.status != "completed" }) return@withContext "Build/tests अभी चल रहे हैं — पूरे होने दें"
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
    suspend fun watchBuild(sha: String, onStep: (String) -> Unit): Build? = withContext(Dispatchers.IO) {
        if (sha.isBlank()) return@withContext null
        val t0 = System.currentTimeMillis()
        var said = ""
        var reruns = 0
        delay(8_000)
        while (System.currentTimeMillis() - t0 < 20 * 60_000L) {
            ensureActive()
            val runs = try { gh.runsFor(repo, sha) } catch (e: GitHub.Err) {
                onStep(if (e.code == 403 || e.code == 404) "Build नहीं देख पाया — token में Actions: Read की इजाज़त दें" else "Build नहीं देख पाया: ${e.message}")
                return@withContext null
            } catch (e: Exception) { delay(10_000); continue }
            if (runs.isEmpty()) {
                if (System.currentTimeMillis() - t0 > 90_000) return@withContext null
                delay(8_000); continue
            }
            if (runs.all { it.status == "completed" }) {
                val bad = runs.firstOrNull { it.conclusion !in listOf("success", "skipped", "neutral") }
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
        return CoderContext.compact(msgs, CoderContext.DEFAULT_BUDGET, CoderContext.KEEP_TAIL, (rules + st).trim())
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
