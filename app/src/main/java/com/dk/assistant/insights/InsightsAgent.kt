package com.dk.assistant.insights

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dk.assistant.BrainManager
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.GeminiModels
import com.dk.assistant.Prefs
import com.dk.assistant.TaskStatus
import com.dk.assistant.unlock
import com.dk.assistant.agent.android.AccessibilityScreenDriver
import com.dk.assistant.connect.Catalog
import com.dk.assistant.connect.ConnectorRunner
import com.dk.assistant.connect.ConnectorStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * नवंबर 2026: "सोशल रिव्यू" - एक बार बोलो, DK पूरा काम करके बताए.
 *
 * हर प्लेटफ़ॉर्म पर (सेटिंग्स → सोशल रिव्यू में तरीका):
 *  - API वाला (पक्का): कनेक्टर जुड़ा हो तो आधिकारिक API से आँकड़े (YouTube Analytics / Instagram + Facebook Graph)
 *  - स्क्रीन वाला: ऐप खोलकर डैशबोर्ड / Insights / YouTube Studio पढ़ना ([ScreenCollector])
 *  - दोनों (डिफ़ॉल्ट): दोनों के आँकड़े मिलाकर (API के नंबर पक्के, स्क्रीन से account status / जो API नहीं देता)
 * फिर इंटरनेट से ट्रेंडिंग, आँकड़ों का हिसाब ([Insights.analyze]) और AI से पूरी हिंदी रिपोर्ट.
 * नतीजा: चैट में पूरी रिपोर्ट + Download/DK Reports में फ़ाइल + बोलकर सार (DK लाइव हो तो वही बोलती है).
 */
object InsightsAgent {
    const val KEY = "insights"
    private const val PREFS = "dk_insights"

    enum class Mode(val id: String, val hi: String) {
        BOTH("both", "दोनों (API + स्क्रीन)"), API("api", "सिर्फ़ API (पक्का)"), SCREEN("screen", "सिर्फ़ स्क्रीन देखकर");
        companion object { fun of(s: String?) = values().firstOrNull { it.id == s } ?: BOTH }
    }

    fun mode(c: Context, p: Platform): Mode = Mode.of(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode_${p.id}", null))
    fun setMode(c: Context, p: Platform, m: Mode) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode_${p.id}", m.id).apply()
    fun trendsOn(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("trends", true)
    fun setTrends(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("trends", on).apply()
    fun niche(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("niche", "").orEmpty()
    fun setNiche(c: Context, s: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("niche", s.trim().take(120)).apply()
    fun lastReport(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_report", "").orEmpty()

    @Volatile private var job: Job? = null
    @Volatile private var spokenForLive: String? = null
    val busy get() = job?.isActive == true
    /** नवंबर 2026 (सच की जाँच): इस रिव्यू में कौन-सा ऐप सच में स्क्रीन पर खुला (null = अभी नहीं), फ़ोन लॉक पर रुका है?, कब शुरू हुआ */
    @Volatile var openedApp: String? = null
        private set
    @Volatile var lockedNow: Boolean = false
        private set
    @Volatile var startedAt: Long = 0L
        private set
    @Volatile private var apiOnlyRun = false
    @Volatile private var lastEnd: Pair<Boolean, String>? = null
    @Volatile private var lastProblem = ""

    /** DK लाइव को काम खत्म होने पर जाने वाला नोट (पूरा सार, ताकि वह बोलकर बताए) */
    fun liveNote(j: TaskStatus.Job): String? {
        val s = spokenForLive?.takeIf { it.isNotBlank() } ?: return null
        spokenForLive = null
        return "SYSTEM NOTE: DK's social media review (\"${j.task}\") has REALLY finished (state: ${j.state.en}). " +
            "Speak this review summary to the user now, naturally, in the reply language, without adding new numbers: \"$s\". " +
            "Then say that the full report with every reel/video is in the DK chat and in Download/DK Reports."
    }

    fun stop(): Boolean { val j = job; if (j?.isActive == true) { j.cancel(); return true }; return false }

    /**
     * DK लाइव का social_insights tool.
     * नवंबर 2026 (मालिक: "झूठ न बोले"): पहले बिना जाँचे हमेशा "STARTED ON SCREEN" लौटता था. अब शुरू करके कुछ सेकंड देखता है
     * कि ऐप सच में स्क्रीन पर खुला या नहीं (फ़ोन लॉक / ऐप नहीं / खुला नहीं) - और DK लाइव को वही सच ([InsightsTruth.toolReply]).
     */
    suspend fun liveTool(svc: DKAccessibilityService, args: org.json.JSONObject): String {
        if (args.optString("action").equals("stop", true)) return if (stop()) "STOPPED: the social review was stopped. Tell the user." else "Nothing was running. Tell the user honestly that no review was running."
        if (busy) return InsightsTruth.toolReply(InsightsTruth.Proof.BUSY, "", openedApp.orEmpty(), TaskStatus.get(KEY)?.last.orEmpty())
        val arr = args.optJSONArray("platforms")
        val ps = (0 until (arr?.length() ?: 0)).mapNotNull { Platform.of(arr?.optString(it)) }.distinct()
        val words = args.optString("request").trim()
        val req = InsightsVoice.parse(words)?.let { r -> if (ps.isEmpty()) r else r.copy(platforms = ps) }
            ?: InsightsVoice.Request(ps.ifEmpty { Platform.values().toList() }, words.ifBlank { "सोशल रिव्यू" })
        val msg = start(svc, req, null)
        // सबूत का इंतज़ार: ऐप खुला / API चला / फ़ोन लॉक / काम खत्म (गलती) - 14 सेकंड तक
        val until = System.currentTimeMillis() + PROOF_WAIT_MS
        while (System.currentTimeMillis() < until && openedApp == null && !apiOnlyRun && busy) kotlinx.coroutines.delay(300)
        val end = lastEnd
        val why = lastProblem.ifBlank { end?.second.orEmpty() }
        val proof = InsightsTruth.decide(openedApp != null, apiOnlyRun && busy, lockedNow, !busy && end != null, end?.first == true, why)
        val step = if (busy) TaskStatus.get(KEY)?.last.orEmpty() else why
        DKLog.d("सोशल रिव्यू सच-जाँच: $proof (${openedApp ?: "-"}) $step")
        // "STARTED ON SCREEN" सिर्फ़ तब जब ऐप सच में खुला. No connector is needed (स्क्रीन वाला तरीका अकेला काफ़ी).
        return InsightsTruth.toolReply(proof, msg, openedApp.orEmpty(), step)
    }
    private const val PROOF_WAIT_MS = 14_000L

    /**
     * काम शुरू - तुरंत लौटता है; काम यूज़र के सामने स्क्रीन पर होता है (ऊपर [com.dk.assistant.WorkAwake] की पट्टी, स्क्रीन जगी). [speak] = DK लाइव बंद हो तो नतीजा बोलने के लिए (null = सिर्फ़ चैट/लाइव).
     */
    fun start(svc: DKAccessibilityService, req: InsightsVoice.Request, speak: (suspend (String) -> Unit)?): String {
        if (busy) return "सोशल रिव्यू पहले से चल रहा है - पूरा होते ही बताऊँगी।"
        val names = req.platforms.joinToString(", ") { it.hi }
        TaskStatus.start(KEY, "सोशल रिव्यू", "DK social review (on screen)", req.text)
        openedApp = null; lockedNow = false; apiOnlyRun = false; lastEnd = null; lastProblem = ""; startedAt = System.currentTimeMillis()
        job = svc.scope.launch(Dispatchers.Default) {
            var state = TaskStatus.State.NOT_DONE
            var short = ""
            try {
                val r = run(svc, req)
                state = if (r.anyData) TaskStatus.State.DONE else TaskStatus.State.NOT_DONE
                short = r.spoken.take(380)
                withContext(Dispatchers.Main) {
                    DKState.reply(r.report)
                    try { svc.startActivity(Intent(svc, com.dk.assistant.ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) {}
                }
                if (DKState.live) spokenForLive = r.spoken
                else speak?.invoke(r.spoken)
            } catch (e: CancellationException) {
                state = TaskStatus.State.NOT_DONE; short = "रिव्यू बीच में रोका गया"
                DKState.reply("सोशल रिव्यू रोक दिया।")
            } catch (e: Exception) {
                DKLog.d("सोशल रिव्यू गलती: ${e.javaClass.simpleName} ${e.message?.take(160)}")
                com.dk.assistant.DKBug.report("insights", e, "सोशल रिव्यू")
                short = "रिव्यू पूरा नहीं हुआ: ${e.message?.take(120)}"
                DKState.replyFailed(short)
                if (!DKState.live) speak?.invoke(short)
            } finally {
                lastEnd = (state == TaskStatus.State.DONE) to short
                lockedNow = false
                TaskStatus.end(KEY, state, short)
            }
        }
        return "ठीक है, $names का पूरा रिव्यू अभी आपके सामने करती हूँ। मैं ऐप और उसका डैशबोर्ड खोलकर हर ${req.platforms.first().itemHi} के आँकड़े देखूँगी - " +
            "स्क्रीन पर देखते रहिए, ऊपर पट्टी में हर कदम दिखेगा। कुछ मिनट लगेंगे, बीच में फ़ोन न छुएँ। पूरा होते ही बताऊँगी।"
    }

    data class Result(val report: String, val spoken: String, val anyData: Boolean)

    suspend fun run(svc: DKAccessibilityService, req: InsightsVoice.Request): Result {
        val c = svc.applicationContext
        val all = mutableListOf<PlatformData>()
        val driver = AccessibilityScreenDriver(svc)
        val collector = ScreenCollector(
            driver = driver,
            openApp = { pkg -> openApp(svc, pkg).also { ok -> if (ok) openedApp = Platform.values().firstOrNull { pkg in it.pkgs }?.hi ?: pkg } },
            installed = { pkg -> try { svc.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false } },
            brain = { prompt -> stepBrain(svc, prompt) },
            log = { DKLog.d(it) },
            cancelled = { job?.isCancelled == true },
            progress = { TaskStatus.step(KEY, it); DKState.hint(it) },
            // नवंबर 2026: जहाँ DK दबा रहा है, वहाँ स्क्रीन पर गोला (मालिक देखे "कहाँ")
            mark = { x, y -> com.dk.assistant.WorkAwake.mark(x, y) },
        )
        var usedScreen = false
        for (p in req.platforms) {
            val m = mode(c, p)
            var d = PlatformData(p)
            if (m != Mode.SCREEN && apiConnected(c, p)) {
                TaskStatus.step(KEY, "${p.hi}: API से आँकड़े ला रही हूँ")
                if (m == Mode.API) apiOnlyRun = true
                d = d.merge(withContext(Dispatchers.IO) {
                    when (p) {
                        Platform.YOUTUBE -> ConnectorRunner.youtubeInsights(c)
                        Platform.INSTAGRAM -> ConnectorRunner.instagramInsights(c)
                        Platform.FACEBOOK -> ConnectorRunner.facebookInsights(c)
                    }
                })
            } else if (m == Mode.API) DKLog.d("सोशल रिव्यू ${p.en}: कनेक्टर नहीं जुड़ा - ऐप की स्क्रीन से देख रही हूँ")
            // स्क्रीन: तरीका "दोनों"/"स्क्रीन", या API से कुछ नहीं मिला
            if (m == Mode.SCREEN || m == Mode.BOTH || (m == Mode.API && d.empty)) {
                // नवंबर 2026 (मालिक): "मेरे सामने करे" - स्क्रीन बंद/लॉक हो तो पहले जगाओ (PIN सिर्फ़ मालिक की इजाज़त से, unlock() का नियम)
                if (!usedScreen) wakeForScreen(svc)
                TaskStatus.step(KEY, "${p.hi}: ऐप खोलकर आँकड़े पढ़ रही हूँ")
                // API से सब मिल गया तो स्क्रीन पर कम कदम (account status + जो छूटा)
                if (d.posts.size >= 6) { collector.detailTarget = 2; collector.listTarget = 4 } else { collector.detailTarget = 4; collector.listTarget = 8 }
                d = d.merge(collector.collect(p))
                usedScreen = true
            }
            DKLog.d("सोशल रिव्यू ${p.en}: ${d.posts.size} आइटम, खाता ${!d.account.empty}, समस्या ${d.problems.size}")
            all += d
        }
        if (usedScreen) try { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Exception) {}
        val analyses = all.map { Insights.analyze(it) }
        val anyData = all.any { !it.empty }

        // ट्रेंडिंग (इंटरनेट)
        var trends = ""
        if (anyData && trendsOn(c)) {
            TaskStatus.step(KEY, "ट्रेंडिंग टॉपिक खोज रही हूँ")
            val q = trendQuery(all, niche(c))
            trends = withTimeoutOrNull(45_000) { try { BrainManager(svc).searchAnswer(q) } catch (e: Exception) { DKLog.d("ट्रेंड खोज: ${e.message}"); "" } }.orEmpty()
        }

        TaskStatus.step(KEY, "रिपोर्ट बना रही हूँ")
        var report = ""
        var spoken = ""
        if (anyData) {
            val ai = withContext(Dispatchers.IO) { reportBrain(svc, InsightsReport.prompt(all, analyses, trends, req.text)) }
            if (!ai.isNullOrBlank()) { val (r, s) = InsightsReport.split(ai); report = r; spoken = s }
        }
        if (report.isBlank()) report = InsightsReport.fallback(all, analyses, trends)
        if (spoken.isBlank()) spoken = InsightsReport.fallbackSpoken(all, analyses)
        val problems = all.flatMap { d -> d.problems.map { "${d.platform.hi}: $it" } }
        lastProblem = problems.joinToString("; ")
        if (problems.isNotEmpty()) report += "\n\n---\n**कहाँ दिक्कत आई:**\n" + problems.joinToString("\n") { "- $it" }
        val src = all.joinToString("; ") { "${it.platform.en}: ${it.sources.joinToString(" + ").ifBlank { "कुछ नहीं" }}" }
        report += "\n\n_आँकड़े कहाँ से: ${src}_"
        save(c, report)
        return Result(report, spoken, anyData)
    }

    fun apiConnected(c: Context, p: Platform): Boolean = try {
        val con = ConnectorStore.connected(c)
        when (p) { Platform.YOUTUBE -> Catalog.YOUTUBE in con; Platform.INSTAGRAM -> Catalog.INSTAGRAM in con; Platform.FACEBOOK -> Catalog.FB_PAGE in con }
    } catch (_: Exception) { false }

    private fun trendQuery(all: List<PlatformData>, niche: String): String {
        val titles = all.flatMap { d -> Insights.ordered(d.posts).take(6).map { it.title.take(50) } }.filter { it.isNotBlank() }.take(10)
        return "Trending topics this week in India for Instagram Reels, Facebook Reels and YouTube Shorts" +
            (if (niche.isNotBlank()) " in the niche: $niche" else "") +
            (if (titles.isNotEmpty()) ". The creator's recent content: " + titles.joinToString(" | ") else "") +
            ". List current trending topics, formats, audio/sounds and hashtags with short reasons, and content ideas that fit this creator."
    }

    private suspend fun openApp(svc: DKAccessibilityService, pkg: String): Boolean {
        val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            withContext(Dispatchers.Main) { svc.startActivity(i) }
            svc.waitForeground(8_000) { it == pkg }
        } catch (e: Exception) { DKLog.d("सोशल रिव्यू: ऐप नहीं खुला - ${e.message}"); false }
    }

    // ------------------------------------------------------------ AI
    private val http = com.dk.assistant.NetFix.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private fun geminiKey(c: Context) = Prefs.liveGeminiKeys(c).firstOrNull { it.isNotBlank() }

    /** स्क्रीनशॉट: Android 11+ और मालिक ने इजाज़त दी हो (सेटिंग्स → "काम में स्क्रीन देखना" / स्क्रीन देखना) */
    /** नवंबर 2026: रिव्यू = मालिक ने खुद कहा "सच में चेक करो" - चार्ट / आँकड़े स्क्रीन की फ़ोटो से भी (सिर्फ़ इसी काम में) */
    private fun canShot(svc: DKAccessibilityService) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private suspend fun wakeForScreen(svc: DKAccessibilityService) {
        val pm = svc.getSystemService(android.os.PowerManager::class.java)
        val km = svc.getSystemService(android.app.KeyguardManager::class.java)
        if (pm?.isInteractive == true && km?.isKeyguardLocked != true) return
        TaskStatus.step(KEY, "स्क्रीन जगा रही हूँ")
        try { DKLog.d("सोशल रिव्यू: " + svc.unlock()) } catch (e: Exception) { DKLog.d("सोशल रिव्यू: स्क्रीन नहीं जगी ${e.message}") }
        if (km?.isKeyguardLocked == true) {
            // नवंबर 2026: लॉक पर ऐप खोलने का दिखावा नहीं - मालिक के अनलॉक करने तक (45 सेकंड) रुको, पट्टी पर साफ़ लिखा
            lockedNow = true
            TaskStatus.step(KEY, "फ़ोन लॉक है - कृपया अनलॉक करें, फिर मैं आपके सामने ऐप खोलूँगी")
            val until = System.currentTimeMillis() + 45_000
            while (km?.isKeyguardLocked == true && System.currentTimeMillis() < until && job?.isCancelled != true) kotlinx.coroutines.delay(500)
            lockedNow = false
            if (km?.isKeyguardLocked != true) TaskStatus.step(KEY, "फ़ोन खुल गया - अब ऐप खोल रही हूँ")
        }
    }

    private suspend fun stepBrain(svc: DKAccessibilityService, prompt: String): String? {
        val shot = if (canShot(svc)) try { svc.screenshotBase64() } catch (_: Exception) { null } else null
        geminiKey(svc)?.let { key ->
            withContext(Dispatchers.IO) { gemini(key, prompt, shot, json = true, maxTokens = 2048, models = (listOf(GeminiModels.CODER) + GeminiModels.CODER_CHAIN).distinct()) }?.let { return it }
        }
        // Gemini key नहीं = जो दिमाग़ (Groq / OpenRouter …) लगा है, उसी से
        return try { BrainManager(svc).teach("Reply with ONLY one JSON object.", prompt, shot) } catch (e: Exception) { DKLog.d("सोशल रिव्यू AI: ${e.message?.take(80)}"); null }
    }

    private suspend fun reportBrain(svc: DKAccessibilityService, prompt: String): String? {
        geminiKey(svc)?.let { key ->
            gemini(key, prompt, null, json = false, maxTokens = 8192, models = (GeminiModels.CODER_CHAIN + GeminiModels.LIGHT_CHAIN).distinct())?.let { return it }
        }
        return try { BrainManager(svc).teach("You write clear Hindi social media analytics reports.", prompt, null) } catch (e: Exception) { DKLog.d("रिपोर्ट AI: ${e.message?.take(80)}"); null }
    }

    private fun gemini(key: String, ask: String, jpegB64: String?, json: Boolean, maxTokens: Int, models: List<String>): String? {
        val parts = JSONArray().put(JSONObject().put("text", ask))
        if (jpegB64 != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpegB64)))
        val cfg = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", if (json) 0.1 else 0.5)
        if (json) cfg.put("responseMimeType", "application/json")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))).put("generationConfig", cfg)
        for (m in models) {
            try {
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val txt = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { DKLog.d("सोशल रिव्यू Gemini $m: HTTP ${r.code}"); null }
                    else JSONObject(r.body?.string().orEmpty()).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.takeIf { o -> !o.optBoolean("thought") }?.optString("text").orEmpty() } }
                }
                if (!txt.isNullOrBlank()) return txt.trim()
            } catch (e: Exception) { DKLog.d("सोशल रिव्यू Gemini $m: ${e.javaClass.simpleName}") }
        }
        return null
    }

    // ------------------------------------------------------------ फ़ाइल
    private fun save(c: Context, report: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_report", report.take(60_000)).apply()
        val name = "सोशल रिव्यू " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date()) + ".md"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DK Reports")
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
                c.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
            } else {
                val dir = java.io.File(c.getExternalFilesDir(null), "DK Reports").apply { mkdirs() }
                java.io.File(dir, name).writeText(report)
            }
            DKLog.d("सोशल रिव्यू: रिपोर्ट सेव - Download/DK Reports/$name")
        } catch (e: Exception) { DKLog.d("सोशल रिव्यू: फ़ाइल सेव नहीं - ${e.message}") }
    }
}
