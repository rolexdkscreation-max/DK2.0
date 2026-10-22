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
        val chain = listOf(this) + fallbacks
        for (attempt in 0..1) {
            val ordered = orderChain(chain.map { it.selfLabel }, sticky()).map { l -> chain.first { it.selfLabel == l } }
            val errs = mutableListOf<Pair<CoderAi, java.io.IOException>>()
            for (ai in ordered) {
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
                    // AiException (429/5xx/401...) ya network timeout - agla API
                    errs.add(ai to e)
                    if (chain.size > 1) DKLog.d("Coder: ${ai.selfLabel} -> ${reason(e)}, अगला API")
                }
            }
            val wait = quotaWaitSec(errs.map { ((it.second as? AiException)?.code ?: -1) to ((it.second as? AiException)?.retryAfter ?: 0) })
            if (attempt == 0 && wait > 0) {
                sink("सारे मॉडल/API की limit एक साथ खत्म (429)", "${wait} सेकंड रुककर दोबारा कोशिश की")
                DKLog.d("Coder: सब 429 - ${wait}s रुककर फिर")
                Thread.sleep(wait * 1000L)
                continue
            }
            // पहले चुने API की असली वजह, साथ में बाकी API का हाल
            val main = errs.firstOrNull { it.first === this } ?: errs.first()
            val others = errs.filter { it !== main }.joinToString { "${it.first.provider.name}: ${(it.second as? AiException)?.code ?: "network"}" }
            val e = main.second
            val msg = (e.message ?: e.javaClass.simpleName) + if (others.isNotBlank()) "\n(दूसरे API भी नहीं चले: $others)" else
                if (fallbacks.isEmpty()) "\n(सुझाव: Settings में Groq या OpenRouter की फ्री key भी डालें - limit खत्म होने पर एजेंट अपने आप उस पर चला जाएगा)" else ""
            throw if (e is AiException) AiException(e.code, msg, e.retryAfter) else java.io.IOException(msg, e)
        }
        throw AiException(0, "AI fail")
    }

    private fun chatOne(system: String, msgs: List<Pair<String, String>>): String =
        if (provider.isGemini) gemini(system, msgs) else openAi(system, msgs, 16000)

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
                        errs.add(m to e); DKLog.d("Coder: $m -> 429, अगला मॉडल"); break
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

        /**
         * DK कंप्यूटर की AI: Settings → DK कंप्यूटर (या "same" = बातचीत वाली), बाकी keys fallback में.
         * [sink] = कंप्यूटर की अपनी रिपोर्ट.
         */
        fun forComputer(c: Context, sink: (String, String) -> Unit): CoderAi? {
            val pid = Prefs.computerProvider(c)
            val p = Providers.byId(if (pid == "same" || Providers.byId(pid) == null) Prefs.activeProvider(c) else pid) ?: return null
            val key = Prefs.key(c, p.id)
            val model = Prefs.computerModel(c).ifBlank { Prefs.model(c, p.id) }
            val base = Prefs.baseUrl(c, p.id)
            if (key.isBlank() || model.isBlank() || (!p.isGemini && base.isBlank())) {
                // चुनी हुई में key नहीं - जो भी key पड़ी हो उससे चलाओ
                val any = Providers.all.firstOrNull { q -> Prefs.key(c, q.id).isNotBlank() && Prefs.model(c, q.id).isNotBlank() && (q.isGemini || Prefs.baseUrl(c, q.id).isNotBlank()) } ?: return null
                return build(c, any, Prefs.key(c, any.id), Prefs.model(c, any.id), Prefs.baseUrl(c, any.id), sink)
            }
            return build(c, p, key, model, base, sink)
        }

        private fun build(c: Context, p: Provider, key: String, model: String, base: String, sink: (String, String) -> Unit): CoderAi {
            val fb = if (!Prefs.fallback(c)) emptyList() else Providers.all.mapNotNull { q ->
                val k = Prefs.key(c, q.id); val m = Prefs.model(c, q.id); val b = Prefs.baseUrl(c, q.id)
                if (k.isBlank() || m.isBlank() || (!q.isGemini && b.isBlank())) null
                else if (q.id == p.id && k == key) null
                else CoderAi(q, k, m, b, note = if (q.id == p.id) ", दूसरी key" else "", sink = sink)
            }
            return CoderAi(p, key, model, base, fb, sink = sink)
        }

        /** Settings se coding wali AI. null = key nahi */
        fun fromPrefs(c: Context): CoderAi? {
            val pid = Prefs.coderProvider(c)
            val same = pid == "same" || Providers.byId(pid) == null
            val p = Providers.byId(if (same) Prefs.activeProvider(c) else pid) ?: return null
            val key = (if (same) "" else Prefs.coderKey(c)).ifBlank { Prefs.key(c, p.id) }
            val model = (if (same) "" else Prefs.coderModel(c)).ifBlank { Prefs.model(c, p.id) }
            val base = (if (same) "" else Prefs.coderBase(c)).ifBlank { Prefs.baseUrl(c, p.id) }
            if (key.isBlank() || model.isBlank() || (!p.isGemini && base.isBlank())) return null
            // baaki API jinki key padi hai (Settings -> "fallback" chalu ho to) - ek ki limit khatm, doosre par kaam jaari
            val fb = if (!Prefs.fallback(c)) emptyList() else Providers.all.mapNotNull { q ->
                val k = Prefs.key(c, q.id); val m = Prefs.model(c, q.id); val b = Prefs.baseUrl(c, q.id)
                if (k.isBlank() || m.isBlank() || (!q.isGemini && b.isBlank())) null
                else if (q.id == p.id && k == key) null          // wahi API + wahi key = koi fayda nahi
                else CoderAi(q, k, m, b, note = if (q.id == p.id) ", दूसरी key" else "")
            }
            return CoderAi(p, key, model, base, fb)
        }

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
    var safePr = false
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

    data class Outcome(val summary: String, val commit: String, val question: Boolean)
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
    /** Aakhri push ka commit sha (build dekhne ke liye) */
    var lastSha = ""
        private set

    /** Verify all local branches are pushed to remote before testing */
    suspend fun verifyRemotePush(): Boolean = withContext(Dispatchers.IO) {
        try {
            val local = gh.localBranchSha(branch)
            val remote = gh.remoteBranchSha(repo, branch)
            local == remote
        } catch (e: Exception) {
            DKLog.d("Coder: push verification failed: ${e.message}")
            false
        }
    }

    fun reset() {
        staged.clear(); original.clear(); msgs.clear(); files = null; snap = null; checkWarned = false
        branch = baseBranch; pr = null; prError = ""; lastTask = ""; lastSummary = ""; projectCtx = ""
        lastPushMsg = ""; lastChangeText = ""
    }

    /** App restart ke baad pichhla khula PR wapas (Merge card ke liye). GitHub par band/merge mila to mergePr/push khud saaf karte hain */
    fun restorePr(p: GitHub.Pull) {
        if (!safePr || SafePr.isBlocked(p.head, baseBranch)) return
        pr = p; branch = p.head; prError = ""
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
                SafePr.prBody(lastSummary, lastChangeText, emptyList()))
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
{"tool":"done","summary":"Hindi summary (short bullet lines; if this fixed a bug/error also add: दिक्कत: <real cause>  हल: <what you changed>)","commit":"short English commit message"}

Rules:
- Work fast: grep to find code, read several files in one call, make several edits in one call.
- Changes are only STAGED; the app pushes them as one commit after "done". Never claim it is pushed.
- Before "done" with changes, call "diff" once and check your own work (imports, names, brackets, unused leftovers).
- Never put secrets/API keys in code. Do not delete files unless the user asked or it is clearly required.
- NEVER add features, settings, files or behaviour the user did not ask for. If you think something extra is needed or useful, use "suggest" (max 2 per task, only if it really matters) and do NOT implement it.
- If the user only asks a question about the code, read what you need and answer in "done" summary with commit "".
- If a task says the BUILD FAILED, read the exact files/lines from the log, fix the real cause, and do not change unrelated code.
- Do NOT touch existing working features: change only the files/lines the task needs. No refactors, renames, reformatting or "cleanups" of other code.
- Protected files (.github/workflows/*, AGENTS.md, gradle.properties, settings.gradle*, gradlew, keystores): change them ONLY if the user explicitly asked for it.${safeRules()}
""".trimIndent()

    private fun safeRules() = if (!safePr) "" else """

SAFE PR MODE (always follow):
- Work flow: understand the repo first (PROJECT CONTEXT: AGENTS.md rules, existing agents, GitHub Actions workflows) -> change code -> "diff" self-check -> done.
- Your changes are NEVER pushed to "$baseBranch". The app commits them to a NEW feature branch (feature/dk-…), opens a Pull Request to "$baseBranch",
  and GitHub Actions runs the unit tests + debug build on that PR. If it fails you get the log and fix it on the same branch.
- Only the user merges the PR after review. Never say it is merged or released.
- If you add logic that can be unit tested without Android (pure Kotlin), add/extend a JUnit test in app/src/test/java/... in the same style."""

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
            val list = files!!.take(500).joinToString("\n") { it.path }
            msgs.add("user" to "Repository files (${files!!.size}):\n$list" + (if (files!!.size > 500) "\n…(more, use list)" else ""))
            msgs.add("model" to "{\"tool\":\"list\",\"path\":\"\"}")
            projectCtx = loadProjectContext()
            if (projectCtx.isNotBlank()) {
                onStep("repo के नियम, agents और GitHub Actions समझ लिए")
                msgs.add("user" to "PROJECT CONTEXT (read and follow before any change):\n$projectCtx")
                msgs.add("model" to "{\"tool\":\"diff\"}")
                msgs.add("user" to "RESULT (diff):\n(no staged changes)")
            }
            msgs.add("user" to "OK. Waiting for the task.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"क्या करना है?\"}")
        }
        checkWarned = false
        if (!task.startsWith("BUILD FAILED") && !task.startsWith("REVIEWER") && !task.startsWith("Coordinator:")) lastTask = task
        msgs.add("user" to "TASK FROM USER: $task" +
            if (staged.isNotEmpty()) "\n(Already staged, not pushed: ${staged.keys.joinToString()})" else "")
        var step = 0
        var bad = 0
        while (true) {
            ensureActive()
            if (++step > MAX_STEPS) return@withContext Outcome("$MAX_STEPS कदम हो गए, यहीं रोका। अब तक के बदलाव नीचे हैं।", "DK: partial changes", false)
            trimContext()
            val reply = try { ai.chat(system(), msgs) } catch (e: AiException) {
                throw AiException(e.code, "${ai.label}: ${e.message}")
            }
            msgs.add("model" to reply)
            val call = parse(reply)
            if (call == null) {
                if (++bad >= 4) return@withContext Outcome("AI सही तरीके से जवाब नहीं दे रहा (4 बार)। दूसरा मॉडल चुनकर फिर कोशिश करें।", "", false)
                onStep("…")
                msgs.add("user" to "ERROR: your reply had no valid JSON tool call on the first line. Reply with exactly one tool call.")
                continue
            }
            bad = 0
            val o = call.o
            val tool = o.optString("tool"); val path = o.optString("path").trim().trimStart('/')
            if (tool == "ask") return@withContext Outcome(o.optString("question").ifBlank { "थोड़ा और बताइए?" }, "", true)
            if (tool == "done") {
                val problems = check()
                if (problems.isNotEmpty() && !checkWarned) {
                    checkWarned = true
                    onStep("जाँच में दिक्कत मिली, ठीक करवा रहा हूँ…")
                    msgs.add("user" to "CHECK FAILED before done (these would likely break the build):\n$problems\n" +
                        "Fix them (read the file again), or call done again if you are sure they are false alarms.")
                    continue
                }
                if (staged.isNotEmpty()) {
                    onStep("चेतावनी: कुछ बदलाव staged हैं पर push नहीं हुए।")
                    msgs.add("user" to "ERROR: You have staged changes that are not pushed. Please push them before finishing.")
                    continue
                }
                if (safePr && !verifyRemotePush()) {
                    onStep("चेतावनी: स्थानीय बदलाव रिमोट पर पुश नहीं हुए हैं।")
                    msgs.add("user" to "ERROR: Local changes are not pushed to remote. Please push your changes before finishing.")
                    continue
                }
                if (staged.isNotEmpty()) lastSummary = o.optString("summary")
                return@withContext Outcome(o.optString("summary").ifBlank { "हो गया।" },
                    o.optString("commit").ifBlank { if (staged.isEmpty()) "" else "DK: code changes" }, false)
            }
            val result = try { runTool(tool, path, call, onStep) } catch (e: GitHub.Err) { "GITHUB ERROR: ${e.message}" }
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
                    val per = 60_000 / ps.size.coerceAtLeast(1)
                    return ps.joinToString("\n\n") { p ->
                        val t = current(p)
                        "===== $p =====\n" + when {
                            t == null -> "NOT FOUND"
                            t.length > per -> t.take(per) + "\n…(cut; read this file alone or with start/end)"
                            else -> t
                        }
                    }
                }
                val t = current(path) ?: return "NOT FOUND: $path" + similar(path)
                val start = o.optInt("start", 0); val end = o.optInt("end", 0)
                if (start > 0 || end > 0) {
                    val lines = t.lines()
                    val a = start.coerceIn(1, lines.size.coerceAtLeast(1)); val b = (if (end > 0) end else a + 150).coerceIn(a, lines.size)
                    onStep("पढ़ रहा हूँ: $path ($a-$b)")
                    return "(${lines.size} lines total)\n" + (a..b).joinToString("\n") { "$it| ${lines[it - 1]}" }
                }
                onStep("पढ़ रहा हूँ: $path")
                return if (t.length > 60_000) t.take(60_000) + "\n…(file cut at 60000 chars; read with start/end for the rest)" else t
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
pass=true ONLY if you are confident it compiles and the task works completely.
""".trimIndent()

    /**
     * Reviewer: staged badlaav ko bina user se pooche jaanchta hai (padhta, grep, raasta trace) aur faisla deta hai.
     * Static jaanch (bracket/XML/JSON) pehle se jod di jaati hai.
     */
    suspend fun review(task: String, acceptance: List<String>, round: Int, onStep: (String) -> Unit): Review = withContext(Dispatchers.IO) {
        if (staged.isEmpty()) return@withContext Review(true, emptyList(), "कोई बदलाव नहीं था", true)
        val rmsgs = mutableListOf<Pair<String, String>>()
        val static = check()
        val d = diffAll().let { if (it.length > 30_000) it.take(30_000) + "\n…(diff cut - use read)" else it }
        rmsgs.add("user" to "REVIEW ROUND $round\nUSER TASK: $task\n" +
            (if (acceptance.isNotEmpty()) "ACCEPTANCE CRITERIA:\n" + acceptance.joinToString("\n") { "- $it" } + "\n" else "") +
            (if (projectCtx.isNotBlank()) "PROJECT RULES:\n${projectCtx.take(5000)}\n" else "") +
            SafePr.protectedIn(staged.keys).let { if (it.isEmpty()) "" else "PROTECTED FILES CHANGED: ${it.joinToString()} (issue unless the task explicitly asks)\n" } +
            "STATIC CHECK: " + static.ifBlank { "OK (brackets/XML/JSON fine)" } + "\n\nSTAGED DIFF:\n$d")
        var bad = 0
        for (step in 1..REVIEW_STEPS) {
            ensureActive()
            val reply = try { ai.chat(reviewerSystem(), rmsgs) } catch (e: AiException) { throw AiException(e.code, "Reviewer ${ai.label}: ${e.message}") }
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
            rmsgs.add("user" to "RESULT ($tool${if (path.isNotBlank()) " $path" else ""}):\n" + result.take(50_000))
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
        // PR GitHub par hi merge/band ho gaya ho -> purani branch nahi, nayi branch + naya PR
        pr?.let { old ->
            val open = try { gh.pullState(repo, old.number).open } catch (_: Exception) { true }
            if (!open) { pr = null; branch = baseBranch }
        }
        if (safePr && SafePr.isBlocked(branch, baseBranch)) {
            var name = SafePr.branchName(lastTask.ifBlank { msg })
            try { gh.createBranch(repo, name, baseBranch) } catch (e: GitHub.Err) {
                // isi minute me isi naam ki branch pehle se (422) -> naam ke aage chhota number
                if (e.code != 422) throw e
                name = "$name-" + (System.currentTimeMillis() / 1000 % 100000)
                gh.createBranch(repo, name, baseBranch)
            }
            branch = name
            msgs.add("user" to "NOTE: working branch is now \"$name\" (created from $baseBranch). All pushes go here; a PR goes to $baseBranch.")
            msgs.add("model" to "{\"tool\":\"ask\",\"question\":\"ठीक है\"}")
        }
        if (safePr && SafePr.isBlocked(branch, baseBranch)) throw IllegalStateException("सुरक्षित PR मोड: $branch पर सीधे push मना है")
        val url = gh.commit(repo, branch, staged.toMap(), msg)
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
                gh.openPullFor(repo, branch) ?: gh.createPull(repo, branch, baseBranch, msg, SafePr.prBody(lastSummary, changeText, prot))
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
    suspend fun mergePr(): String = withContext(Dispatchers.IO) {
        lastMergeSha = ""
        val p = pr ?: return@withContext "कोई PR नहीं है"
        val st = gh.pullState(repo, p.number)
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

    /** Purane lambe nateeje chhote karo (free models ka context chhota hota hai) */
    private fun trimContext() {
        var total = msgs.sumOf { it.second.length }
        var i = 4
        while (total > 140_000 && i < msgs.size - 6) {
            val (r, t) = msgs[i]
            if (t.length > 1500) {
                val short = t.take(400) + "\n…(old content removed to save space; read again if needed)"
                total -= t.length - short.length; msgs[i] = r to short
            }
            i++
        }
    }

    companion object {
        const val MAX_STEPS = 60
        const val REVIEW_STEPS = 25

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
