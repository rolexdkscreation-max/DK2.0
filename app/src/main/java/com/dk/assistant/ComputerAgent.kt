package com.dk.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * DK कंप्यूटर का session: स्क्रीन बंद हो तब भी काम, बातचीत (आगे के सवाल उसी संदर्भ में), कतार, "पूछो" कार्ड.
 * Roles: u = आप, d = जवाब, s = कदम, f = फ़ाइल, e = गलती
 */
object ComputerSession {
    data class Line(val role: String, val text: String)
    val lines = mutableListOf<Line>()
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var job: Job? = null
    @Volatile var listener: (() -> Unit)? = null
    /** स्क्रीन खुली है? (नहीं तो काम पूरा होने पर notification) */
    @Volatile var visible = false
    val busy get() = job?.isActive == true
    private var agent: ComputerAgent? = null
    private val queue = ArrayDeque<Triple<String, Boolean, Boolean>>()
    /** अभी की योजना + प्रगति (ऊपर की animation पट्टी और checklist) */
    @Volatile var plan: ComputerPlan.Plan? = null
    /** अभी का कदम (animation पट्टी में) */
    @Volatile var now: String = ""
    fun showPlan(p: ComputerPlan.Plan?) { plan = p; listener?.invoke() }
    /** अभी वाला काम आवाज़ से आया? (तो पूरा होने पर / इजाज़त के लिए बोलकर बताओ) */
    @Volatile var voiceRunning = false

    /** फ़ोन का संवेदनशील काम करने से पहले पूछना */
    class Ask(val text: String, val answer: CompletableDeferred<Boolean>)
    @Volatile var ask: Ask? = null

    fun add(role: String, t: String) {
        lines.add(Line(role, t)); while (lines.size > 400) lines.removeAt(0); listener?.invoke()
        if (role == "s") { now = t; TaskStatus.step(TaskStatus.PC, t) }     // असली कदम (live status)
        ComputerSaved.dirty()
    }

    // ---- v3.88: ऐप बंद / नेटवर्क टूटा = काम की हालत फ़ोन में सेव; लौटने पर वहीं से आगे
    @Volatile var runningTask: String? = null
        private set
    fun agentMsgs(): List<Pair<String, String>> = agent?.exportMsgs().orEmpty()

    /** पिछली बार अधूरा रहा काम (ऐप बंद हुआ था / नेटवर्क टूटा) - null = कुछ नहीं */
    fun pendingResume(c: Context): ComputerSaved.State? =
        if (busy) null else ComputerSaved.load(c.applicationContext)?.takeIf { it.running && it.task.isNotBlank() }

    /** स्क्रीन पर पिछली बातचीत वापस (ऐप दोबारा खुला और यहाँ कुछ नहीं) */
    fun restoreLines(c: Context) {
        if (lines.isNotEmpty() || busy) return
        val s = ComputerSaved.load(c.applicationContext) ?: return
        lines.addAll(s.lines.map { Line(it.first, it.second) })
        if (agent == null && s.msgs.isNotEmpty()) agent = ComputerAgent(c.applicationContext).also { it.importMsgs(s.msgs) }
    }

    /** अधूरा काम वहीं से आगे (पुरानी बातचीत AI के पास, हो चुके कदम दोबारा नहीं) */
    fun resume(c: Context): String? {
        val s = pendingResume(c) ?: return "कोई अधूरा काम नहीं"
        val ctx = c.applicationContext
        if (agent == null) agent = ComputerAgent(ctx).also { it.importMsgs(s.msgs) }
        add("s", "↻ पिछला अधूरा काम वहीं से आगे: ${s.task.take(80)}")
        return start(ctx, RESUME_NOTE + s.task, background = !visible)
    }
    private const val RESUME_NOTE = "(पिछला काम ऐप बंद होने / नेटवर्क टूटने से बीच में रुक गया था। जो कदम हो चुके उन्हें दोबारा मत करो, वहीं से आगे पूरा करो।) "

    /** नेटवर्क लौटा - नेटवर्क की वजह से रुका काम अपने-आप आगे (और notification) */
    fun resumeIfNetPaused(c: Context) {
        val s = pendingResume(c) ?: return
        if (!s.netPaused) return
        notifyUser(c.applicationContext, "DK कंप्यूटर: नेटवर्क आ गया", "रुका हुआ काम आगे बढ़ा रहा हूँ — ${s.task.take(120)}")
        resume(c)?.let { add("e", it) }
    }

    /** काम शुरू करो (या कतार में). null = शुरू/कतार, वरना वजह */
    fun start(c: Context, task: String, background: Boolean = false, voice: Boolean = false): String? {
        val ctx = c.applicationContext
        OneKeyGate.check(ctx, OneKeyGate.Work.COMPUTER)?.let { return it }
        if (!Prefs.computerOn(ctx)) return "DK कंप्यूटर बंद है — Settings → DK कंप्यूटर में चालू करें"
        if (CoderAi.forComputer(ctx) { _, _ -> } == null) return "DK कंप्यूटर की AI key नहीं मिली — Settings → AI दिमाग → DK कंप्यूटर में key डालें"
        if (busy) {
            queue.addLast(Triple(task, background, voice))
            add("u", (if (voice) "🎤 " else "") + task); add("s", "कतार में रखा (${queue.size}) — अभी वाला काम पूरा होते ही शुरू होगा")
            return null
        }
        add("u", (if (voice) "🎤 " else "") + task.removePrefix(RESUME_NOTE).let { if (it != task) "↻ $it" else it })
        TaskStatus.start(TaskStatus.PC, "DK कंप्यूटर", "DK Computer", task.removePrefix(RESUME_NOTE))
        runningTask = task.removePrefix(RESUME_NOTE)
        ComputerSaved.mark(ctx, running = true, netPaused = false)
        voiceRunning = voice
        plan = null; now = "समझ रहा हूँ…"
        val a = agent ?: ComputerAgent(ctx).also { agent = it }
        val j = scope.launch {
            try {
                val out = a.run(task, background)
                out.files.forEach { add("f", it) }
                // v5 (मालिक): जवाब के नीचे "कौन-सा मॉडल चला / क्या गलती हुई" वाली रिपोर्ट नहीं - सिर्फ़ असली जवाब
                add("d", out.text)
                TaskStatus.end(TaskStatus.PC, if (out.question) TaskStatus.State.WAITING else TaskStatus.State.DONE, out.text)
                ComputerSaved.mark(ctx, running = false, netPaused = false)
                if (!visible || background) notifyUser(ctx, if (out.question) "DK कंप्यूटर को जानकारी चाहिए" else "DK कंप्यूटर: काम पूरा", out.text.take(300))
                if (voice) speak(ComputerVoice.spoken(out.text, out.files.size, out.question))
            } catch (e: CancellationException) {
                add("e", "काम रोक दिया।")
                TaskStatus.end(TaskStatus.PC, TaskStatus.State.CANCELLED, "काम रोक दिया")
                ComputerSaved.mark(ctx, running = false, netPaused = false)
                throw e
            } catch (e: Exception) {
                val m = (e.message ?: e.javaClass.simpleName).take(500)
                // v3.88: नेटवर्क ही नहीं था = काम खोया नहीं; नेटवर्क आते ही अपने-आप वहीं से आगे
                if (!NetProbe.online(ctx)) {
                    add("e", "नेटवर्क चला गया — काम यहीं सेव है। नेटवर्क आते ही अपने-आप वहीं से आगे होगा।")
                    TaskStatus.end(TaskStatus.PC, TaskStatus.State.WAITING, "नेटवर्क का इंतज़ार")
                    ComputerSaved.mark(ctx, running = true, netPaused = true)
                    OfflineQueue.install(ctx)
                    if (!visible || background) notifyUser(ctx, "DK कंप्यूटर: नेटवर्क का इंतज़ार", "नेटवर्क आते ही काम वहीं से आगे होगा")
                    if (voice) speak("नेटवर्क चला गया है। काम सेव है, नेटवर्क आते ही आगे कर दूँगी।")
                    return@launch
                }
                ComputerSaved.mark(ctx, running = false, netPaused = false)
                // v5 (मालिक): तकनीकी गलतियाँ / रिपोर्ट स्क्रीन पर नहीं - सिर्फ़ डिबग लॉग में; यूज़र को एक साफ़ लाइन
                DKLog.d("DK कंप्यूटर: काम रुका - $m\n" + a.reportText(m))
                val shown = ComputerVoice.failShort(m)
                add("e", shown)
                TaskStatus.end(TaskStatus.PC, TaskStatus.State.FAILED, shown)
                if (!visible || background) notifyUser(ctx, "DK कंप्यूटर: काम रुका", shown.take(200))
                if (voice) speak(shown)
            }
        }
        job = j
        j.invokeOnCompletion { cause ->
            scope.launch {
                ask?.answer?.complete(false); ask = null; voiceRunning = false
                if (cause is CancellationException) { if (queue.isNotEmpty()) add("s", "कतार के ${queue.size} काम भी हटा दिए"); queue.clear() }
                listener?.invoke()
                if (!busy) queue.removeFirstOrNull()?.let { (t, bg, v) -> runQueued(ctx, t, bg, v) }
            }
        }
        listener?.invoke()
        return null
    }

    private fun runQueued(c: Context, t: String, bg: Boolean, v: Boolean) {
        // कतार वाला काम पहले ही "आप" वाली लाइन में दिख चुका - दोबारा नहीं
        val before = lines.size
        start(c, t, bg, v)?.let { add("e", it) }
        if (lines.size > before && lines.getOrNull(before)?.role == "u") lines.removeAt(before)
        add("s", "कतार का अगला काम शुरू — ${t.take(80)}")
    }

    fun stop() { job?.cancel(); ask?.answer?.complete(false) }

    /** नई बातचीत: पुराना संदर्भ और स्क्रीन साफ़ */
    fun reset() { stop(); queue.clear(); agent = null; lines.clear(); runningTask = null; listener?.invoke(); ComputerSaved.clear() }

    /** स्क्रीन पर हाँ/नहीं कार्ड (और स्क्रीन बंद हो तो notification). 3 मिनट में जवाब नहीं = नहीं */
    suspend fun confirm(c: Context, text: String): Boolean {
        val a = Ask(text, CompletableDeferred())
        ask = a
        listener?.invoke()
        if (!visible) notifyUser(c, "DK कंप्यूटर: इजाज़त चाहिए", text.take(200))
        if (voiceRunning) speak("DK कंप्यूटर को इजाज़त चाहिए: ${text.take(160)}। बोलिए हाँ या नहीं।")
        TaskStatus.waiting(TaskStatus.PC, "इजाज़त चाहिए: $text")
        val r = withTimeoutOrNull(3 * 60_000L) { a.answer.await() } ?: false
        TaskStatus.step(TaskStatus.PC, if (r) "इजाज़त मिली" else "इजाज़त नहीं मिली")
        ask = null
        listener?.invoke()
        return r
    }

    fun answer(yes: Boolean) { ask?.answer?.complete(yes) }

    /** आवाज़ से काम आया हो तो DK बोलकर बताए (Live बातचीत चल रही हो तो नहीं - वहाँ notification) */
    private fun speak(t: String) {
        val svc = DKAccessibilityService.instance ?: return
        if (DKState.live || t.isBlank()) return
        svc.scope.launch { try { svc.speaker.speak(t) } catch (_: Exception) {} }
    }

    fun notifyUser(c: Context, title: String, text: String) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("dk_computer") == null)
            nm.createNotificationChannel(NotificationChannel("dk_computer", "DK कंप्यूटर", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 7401, Intent(c, ComputerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, "dk_computer") else android.app.Notification.Builder(c))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true)
        nm.notify(7401, b.build())
    } catch (_: Exception) {}
}

/**
 * कई कदम वाला एजेंट: AI हर कदम पर एक टूल चुनता है (JSON), DK टूल चलाकर नतीजा वापस देता है,
 * जब तक काम पूरा न हो ("done"). किसी भी AI (Gemini / Groq / OpenRouter / Custom) पर चलता है;
 * limit खत्म हो तो CoderAi अपने-आप दूसरे मॉडल/API पर जाता है.
 */
class ComputerAgent(private val c: Context) {
    data class Call(val tool: String, val args: JSONObject, val thought: String, val raw: String, val step: Int? = null)
    data class Result(val text: String, val files: List<String>, val report: String, val question: Boolean = false)

    /** पूरी बातचीत (user = काम/टूल का नतीजा, model = AI का JSON) - आगे के सवाल इसी संदर्भ में */
    private val msgs = mutableListOf<Pair<String, String>>()
    private val issues = mutableListOf<CoderReport.Item>()
    private val files = mutableListOf<String>()
    private var pendingFail: String? = null

    private fun issue(p: String, f: String) = synchronized(issues) {
        if (issues.none { it.problem == p && it.fix == f }) issues.add(CoderReport.Item(p.take(220), f.take(220)))
        while (issues.size > 12) issues.removeAt(0)
    }

    fun reportText(error: String? = null): String = CoderReport.format(synchronized(issues) { issues.toList() }, error)

    private fun step(t: String) = ComputerSession.add("s", t)

    private fun addUser(t: String) {
        // लगातार दो user संदेश नहीं (कुछ API मना करती हैं) - जोड़ दो
        if (msgs.lastOrNull()?.first == "user") msgs[msgs.size - 1] = "user" to (msgs.last().second + "\n\n" + t) else msgs.add("user" to t)
    }

    /** अभी की योजना (user को checklist, AI को याद) */
    private var plan: ComputerPlan.Plan? = null
    /** इस काम में चले टूल (जाँचकर्ता को सबूत) */
    private val did = mutableListOf<String>()
    /** मॉडल की पढ़ने की क्षमता (अक्षर) */
    private var budget = CoderContext.DEFAULT_BUDGET

    private fun setPlan(p: ComputerPlan.Plan?) { plan = p; ComputerSession.showPlan(p) }

    /** v3.88: काम की हालत सेव/वापस (ऐप बंद होने पर भी वहीं से आगे) */
    fun exportMsgs(): List<Pair<String, String>> = synchronized(msgs) { msgs.toList() }
    fun importMsgs(l: List<Pair<String, String>>) = synchronized(msgs) { msgs.clear(); msgs.addAll(l) }

    suspend fun run(task: String, background: Boolean): Result {
        synchronized(issues) { issues.clear() }
        files.clear(); pendingFail = null; did.clear(); setPlan(null)
        // v5 (मालिक): गलतियाँ स्क्रीन पर नहीं; Gemini -> Groq -> OpenRouter -> Extra में मॉडल बदले तो सिर्फ़ इतना दिखे कि अब कौन-सा मॉडल काम कर रहा है
        val ai = CoderAi.forComputer(c) { p, f ->
            issue(p, f)
            ComputerVoice.switchedTo(f)?.let { step("⚙ अब $it पर काम चल रहा है") }
        } ?: throw IllegalStateException("AI key नहीं मिली")
        budget = ai.budget
        val cap = ComputerPlan.resultCap(budget)
        step("🧠 ${ai.label} से काम शुरू…")
        // 1) v3.88: योजना साथ-साथ बनती है - पहला कदम उसका इंतज़ार नहीं करता (पहले: लंबी योजना के बाद ही काम)
        val before = msgs.takeLast(6).joinToString("\n") { (r, t) -> (if (r == "user") "USER/TOOL: " else "DK: ") + t.take(600) }
        val planJob = CoroutineScope(currentCoroutineContext()).async { makePlan(ai, task, before) }.also { planJ = it }
        var planSent = false
        addUser((if (msgs.isEmpty()) "TASK: " else "USER: ") + task + if (background) "\n(यह तय समय पर अपने-आप चला काम है — user अभी स्क्रीन पर नहीं है)" else "")
        val max = Prefs.computerMaxSteps(c)
        var bad = 0; var tools = 0; var verifyRound = 0
        for (n in 1..max) {
            // योजना बन गई = AI को अगले कदम से पहले बता दो (एक ही बार)
            if (!planSent && planJob.isCompleted) { planSent = true; plan?.let { addUser(ComputerPlan.forAi(it)) } }
            compact()
            ComputerSaved.dirty()
            val reply = withContext(Dispatchers.IO) { ai.chat(system(), msgs) }
            val call = parse(reply)
            if (call == null) {
                bad++
                msgs.add("model" to reply.take(4000))
                if (bad >= 3) { issue("AI ने बार-बार गलत format में जवाब दिया", "उसी जवाब को आखिरी नतीजा माना"); return finish(reply.trim()) }
                issue("AI ने गलत format (JSON नहीं) में जवाब दिया", "सही format में दोबारा माँगा")
                addUser("FORMAT ERROR: reply with exactly ONE JSON object like {\"thought\":\"...\",\"step\":1,\"tool\":\"...\",\"args\":{...}} and nothing else.")
                continue
            }
            msgs.add("model" to call.raw.take(maxOf(6000, cap)))
            plan?.let { p -> ComputerPlan.advance(p, call.step).takeIf { it != p }?.let { setPlan(it) } }
            if (call.thought.isNotBlank()) step("💭 " + call.thought.take(300))
            when (call.tool) {
                "done" -> {
                    val ans = call.args.optString("answer").ifBlank { call.args.optString("text") }
                    // 2) आख़िरी जाँच: काम सच में पूरा? कमी हो तो खुद आगे काम
                    if (!planJob.isCompleted) planJob.cancel()      // बहुत छोटा काम - योजना की ज़रूरत ही नहीं पड़ी
                    if (ComputerPlan.needsVerify(plan, tools, verifyRound)) {
                        verifyRound++
                        val miss = verify(ai, task, ans)
                        if (miss.isNotEmpty() && n < max) {
                            step("🧐 जाँच: अभी कमी है — " + miss.joinToString("; ").take(240) + " → पूरा कर रहा हूँ")
                            addUser("QUALITY CHECK — the answer is NOT complete yet. Missing:\n" + miss.joinToString("\n") { "- $it" } +
                                "\nContinue working with tools to fix exactly these points, then send done again with the COMPLETE final answer.")
                            continue
                        }
                        step("🧐 जाँच: काम पूरा ✓")
                    }
                    return finish(ans)
                }
                "ask_user" -> return finish(call.args.optString("question").ifBlank { "कृपया और जानकारी दें।" }, question = true)
                "update_plan" -> {
                    val st = call.args.optJSONArray("steps")
                    val list = if (st == null) emptyList() else (0 until st.length()).map { st.optString(it) }
                    val p0 = plan
                    val p1 = if (p0 == null) ComputerPlan.parse(JSONObject().put("steps", st ?: org.json.JSONArray()).toString()) else ComputerPlan.revise(p0, list)
                    if (p1 != null && p1 != p0) {
                        setPlan(p1)
                        ComputerSession.add("p", "📋 योजना बदली:\n" + ComputerPlan.checklist(p1, title = false))
                        addUser("PLAN UPDATED.\n" + ComputerPlan.forAi(p1))
                    } else addUser("ERROR: update_plan needs {\"steps\":[\"...\"]}")
                }
                else -> {
                    tools++
                    val res = try { runTool(call) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        "ERROR: " + (e.message ?: e.javaClass.simpleName).take(300)
                    }
                    synchronized(did) { did.add("${call.tool} ${call.args.toString().take(160)} → " + if (res.startsWith("ERROR")) res.take(120) else "ok (${res.length} chars)") }
                    if (res.startsWith("ERROR")) {
                        step("⚠ ${call.tool}: " + res.removePrefix("ERROR:").trim().take(140))
                        pendingFail = "${call.tool} नहीं चला: " + res.removePrefix("ERROR:").trim().take(140)
                    } else pendingFail?.let { issue(it, "AI ने दूसरा तरीका अपनाया (${call.tool}) और काम आगे बढ़ा"); pendingFail = null }
                    addUser("TOOL RESULT (${call.tool}) [step $n/$max]:\n" + res.take(cap) + if (res.length > cap) "\n…(नतीजा लंबा था — ज़रूरत हो तो छोटे हिस्सों में दोबारा माँगें)" else "")
                }
            }
        }
        // कदमों की सीमा: जो मिला उससे जवाब
        issue("कदमों की सीमा ($max) पूरी हो गई", "अब तक मिली जानकारी से जवाब दिया (Settings में सीमा बढ़ा सकते हैं)")
        addUser("STEP LIMIT reached. Reply now with the done JSON: the best complete answer from what you have, and clearly say what is still left.")
        val last = withContext(Dispatchers.IO) { ai.chat(system(), msgs) }
        val call = parse(last)
        msgs.add("model" to last.take(6000))
        return finish(call?.args?.optString("answer")?.ifBlank { null } ?: last.trim())
    }

    /** योजना बनाना - न बने तो पुराना तरीका (बिना checklist) चलता रहे */
    private suspend fun makePlan(ai: CoderAi, task: String, before: String) {
        step("📋 काम शुरू कर दिया — साथ-साथ योजना भी बना रहा हूँ…")
        val txt = try {
            withContext(Dispatchers.IO) {
                ai.chat(PLAN_SYS, listOf("user" to (if (before.isNotBlank()) "CONVERSATION SO FAR (short):\n$before\n\n" else "") +
                    "NEW TASK FROM THE USER:\n$task\n\nTOOLS YOU WILL HAVE: " + TOOLS.filter { (id, _) -> Prefs.computerTool(c, if (id == "read_urls") "read_url" else id) }.joinToString { it.first } +
                    "\nToday: " + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("hi", "IN")).format(java.util.Date())))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { issue("योजना नहीं बन पाई (${(e.message ?: "").take(80)})", "सीधे काम शुरू किया"); return }
        val p = ComputerPlan.parse(txt) ?: return
        setPlan(p)
        ComputerSession.add("p", ComputerPlan.checklist(p))
    }

    /** जाँचकर्ता: जवाब काम के हर हिस्से को पूरा करता है? कमी = list (खाली = पास) */
    private suspend fun verify(ai: CoderAi, task: String, answer: String): List<String> {
        step("🧐 जाँच रहा हूँ कि काम सच में पूरा और सही है…")
        val ev = synchronized(did) { did.takeLast(40).joinToString("\n") }
        val txt = try {
            withContext(Dispatchers.IO) {
                ai.chat(VERIFY_SYS, listOf("user" to "TASK:\n$task\n\n" + (plan?.let { "PLAN:\n" + it.steps.mapIndexed { i, x -> "${i + 1}. $x" }.joinToString("\n") + "\nDELIVERABLE: ${it.deliverable}\n\n" } ?: "") +
                    "TOOLS USED:\n${ev.ifBlank { "(none)" }}\n\nFILES CREATED: ${files.joinToString().ifBlank { "(none)" }}\n\nFINAL ANSWER:\n${answer.take(30_000)}"))
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return emptyList() }
        return ComputerPlan.verdict(txt).missing
    }

    /** साथ-साथ बन रही योजना (काम खत्म = उसे भी रोको, ताकि जवाब उसका इंतज़ार न करे) */
    private var planJ: Job? = null

    private fun finish(text: String, question: Boolean = false): Result {
        planJ?.let { if (!it.isCompleted) it.cancel() }; planJ = null
        pendingFail?.let { issue(it, "इसके बिना जितना हो सका उतना जवाब दिया") }; pendingFail = null
        if (!question) plan?.let { setPlan(ComputerPlan.finishAll(it)) }
        return Result(text.ifBlank { "(खाली जवाब)" }, files.toList(), reportText(), question)
    }

    /** पुराने टूल-नतीजे छोटे करो ताकि बातचीत AI की सीमा में रहे (बड़े मॉडल पर बहुत बड़ी बातचीत भी पूरी) */
    private fun compact() {
        val limit = ComputerPlan.compactAt(budget); val keep = ComputerPlan.keepOld(budget)
        var total = msgs.sumOf { it.second.length }
        var i = 0
        while (total > limit && i < msgs.size - 4) {
            val (r, t) = msgs[i]
            if (t.length > keep + 200) { msgs[i] = r to (t.take(keep) + "\n…(पुराना नतीजा छोटा किया)"); total -= t.length - msgs[i].second.length }
            i++
        }
    }

    private suspend fun runTool(call: Call): String {
        val a = call.args
        if (call.tool in TOOL_IDS && !Prefs.computerTool(c, call.tool)) return "ERROR: यह टूल Settings में बंद है — दूसरा तरीका अपनाएँ"
        return when (call.tool) {
            "web_search" -> {
                val q = a.optString("query").ifBlank { return "ERROR: query खाली" }
                step("🔎 खोज: $q")
                withContext(Dispatchers.IO) { ComputerTools.formatSearch(ComputerTools.search(c, q, a.optInt("n", 6).coerceIn(1, 10)) { p, f -> issue(p, f); step("🔎 " + f.removePrefix("अपने आप ").replace("खोज पर गया", "से खोज चल रही है").let { "अब $it" }) }) }
            }
            "read_url" -> {
                val u = a.optString("url").ifBlank { return "ERROR: url खाली" }
                val from = a.optInt("from", 0).coerceAtLeast(0)
                step("🌐 पढ़ रहा हूँ: ${u.take(90)}" + if (from > 0) " (आगे का हिस्सा)" else "")
                withContext(Dispatchers.IO) { ComputerTools.readUrl(u, ComputerPlan.resultCap(budget), from) }
            }
            "read_urls" -> {
                if (!Prefs.computerTool(c, "read_url")) return "ERROR: read_url टूल Settings में बंद है"
                val arr = a.optJSONArray("urls") ?: return "ERROR: urls खाली"
                val us = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }.distinct().take(4)
                if (us.isEmpty()) return "ERROR: urls खाली"
                step("🌐 ${us.size} पेज एक साथ पढ़ रहा हूँ")
                val each = ComputerPlan.resultCap(budget) / us.size
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.coroutineScope {
                        us.map { u -> async { try { ComputerTools.readUrl(u, each) } catch (e: Exception) { "URL: $u\nERROR: ${(e.message ?: "").take(120)}" } } }
                            .map { it.await() }
                    }
                }.joinToString("\n\n========\n\n")
            }
            "run_js" -> { step("⚙ JavaScript चला रहा हूँ"); ComputerTools.runJs(c, a.optString("code")) }
            "write_file" -> {
                val f = withContext(Dispatchers.IO) { ComputerTools.write(c, a.optString("name"), a.optString("content")) }
                if (f.name !in files) files.add(f.name)
                step("📄 फ़ाइल बनाई: ${f.name}"); "saved ${f.name} (${f.length()} bytes)"
            }
            "make_pdf" -> {
                val f = withContext(Dispatchers.IO) { ComputerTools.makePdf(c, a.optString("name").ifBlank { "report.pdf" }, a.optString("title"), a.optString("content")) }
                if (f.name !in files) files.add(f.name)
                step("📕 PDF बनाई: ${f.name}"); "saved ${f.name} (${f.length() / 1024} KB)"
            }
            "read_file" -> withContext(Dispatchers.IO) { ComputerTools.read(c, a.optString("name"), ComputerPlan.resultCap(budget)) }
            "list_files" -> ComputerTools.list(c).take(40).joinToString("\n") { "${it.name} (${it.length() / 1024} KB)" }.ifBlank { "(कोई फ़ाइल नहीं)" }
            "phone_task" -> phone(a.optString("request"))
            "coding_task" -> {
                if (!Prefs.coderOn(c)) return "ERROR: कोडिंग एजेंट बंद है (Settings → कोडिंग एजेंट)"
                step("👨‍💻 कोडिंग टीम को काम दिया")
                CoderTeam.start(c, a.optString("task"))?.let { "ERROR: $it" }
                    ?: "Started in background. Coding team (Builder → Reviewer → PR) will notify the user when done. Do not wait for it."
            }
            "remember" -> { val f = a.optString("fact"); if (Memory.addFact(c, f)) { step("🧩 याद रखा: ${f.take(80)}"); "saved" } else "already known / not saved" }
            "schedule" -> {
                val t = a.optString("task").ifBlank { return "ERROR: task खाली" }
                val at = ComputerTools.whenMs(System.currentTimeMillis(), a.optInt("minutes", 0).takeIf { it > 0 }, a.optString("at"))
                    ?: return "ERROR: minutes (जैसे 30) या at (\"HH:mm\") दें"
                ComputerTools.schedule(c, t, at).also { step("⏰ $it") }
            }
            // v3.88: एक-दूसरे पर निर्भर न रहने वाले (सिर्फ़ पढ़ने वाले) काम एक साथ
            "parallel" -> {
                val arr = a.optJSONArray("calls") ?: return "ERROR: calls खाली"
                val calls = (0 until minOf(arr.length(), PARALLEL_MAX)).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o -> Call(o.optString("tool"), o.optJSONObject("args") ?: JSONObject(), "", o.toString()) }
                }
                if (calls.isEmpty()) return "ERROR: calls खाली"
                calls.firstOrNull { it.tool !in PARALLEL_SAFE }?.let {
                    return "ERROR: parallel में सिर्फ़ ये टूल: ${PARALLEL_SAFE.joinToString()} ('${it.tool}' अलग से चलाएँ)"
                }
                step("⚡ ${calls.size} काम एक साथ चला रहा हूँ")
                kotlinx.coroutines.coroutineScope {
                    calls.map { cl -> async { "[${cl.tool}] " + try { runTool(cl) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR: " + (e.message ?: "").take(160) } } }
                        .map { it.await() }
                }.joinToString("\n\n========\n\n")
            }
            else -> "ERROR: unknown tool '${call.tool}'. Use only the listed tools."
        }
    }

    private suspend fun phone(req: String): String {
        if (req.isBlank()) return "ERROR: request खाली"
        val svc = DKAccessibilityService.instance ?: return "ERROR: DK की Accessibility बंद है — फ़ोन कंट्रोल नहीं हो सकता (Settings → Accessibility)"
        if (Prefs.computerConfirm(c) && sensitive(req)) {
            step("❓ इजाज़त माँग रहा हूँ: $req")
            if (!ComputerSession.confirm(c, "DK कंप्यूटर यह फ़ोन पर करना चाहता है:\n\n$req\n\nकरूँ?"))
                return "ERROR: user ने इजाज़त नहीं दी (या जवाब नहीं आया). Do NOT retry this action; tell the user."
        }
        step("📱 फ़ोन पर: ${req.take(100)}")
        val runner = try { svc.agent } catch (_: UninitializedPropertyAccessException) { return "ERROR: फ़ोन एजेंट अभी तैयार नहीं" }
        val before = DKState.lastReply
        val j = runner.run(req, speak = false)
        withTimeoutOrNull(150_000) { j.join() } ?: run { j.cancel(); return "ERROR: फ़ोन का काम 150 सेकंड में पूरा नहीं हुआ" }
        return DKState.lastReply.takeIf { it != before && it.isNotBlank() }?.let { "phone agent: $it" } ?: "phone agent finished"
    }

    private fun system(): String {
        val now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("hi", "IN")).format(java.util.Date())
        val tools = TOOLS.filter { (id, _) -> Prefs.computerTool(c, if (id == "read_urls") "read_url" else id) }.joinToString("\n") { "- ${it.second}" }
        val facts = try { Memory.factsBlock(c) } catch (_: Exception) { "" }
        val name = Prefs.assistantName(c)
        return """
You are "$name कंप्यूटर" (DK Computer), an autonomous agent running on the user's Android phone. You complete ANY task the user gives (research, study, writing, business, money, health info, travel, shopping comparison, tech help, maths, data, files, phone actions, code) end-to-end, step by step, using tools, until it is FULLY done — like a careful expert of that field sitting at a computer.

Reply with EXACTLY ONE JSON object and nothing else (no markdown fences):
{"thought":"1-2 simple Hindi lines for the user: what you do now and why","step":N,"tool":"<tool name>","args":{...}}
("step" = number of the plan step you are working on now.)

TOOLS:
$tools
- update_plan {"steps":["...","..."]} — change the plan when you learn something new (keeps done steps)
- ask_user {"question":"..."} — only when you truly cannot continue without the user's input
- done {"answer":"final answer"} — when the task is complete

RULES:
- Follow YOUR PLAN step by step, carefully and precisely. Finish each step properly before the next. Keep going until done; never stop half-way.
- Read sources COMPLETELY: if a page says "बाकी … अक्षर", continue with read_url "from" when the rest matters. Read several sources at once with read_urls.
- Facts, prices, news, people, places: ALWAYS verify with web_search, then read_url the best sources for details. Never invent facts, numbers, links or quotes.
- Do several searches for research tasks (different angles); compare sources; prefer official/primary sources.
- If a tool returns ERROR, do not repeat the same call blindly — fix the arguments or use another tool/source.
- For math, data, dates, unit conversion, sorting, tables: use run_js instead of guessing.
- When the user asks for a document/report/list/file, create it with write_file (md/csv/html/json/txt) or make_pdf, and mention the file name in the answer.
- Phone actions (open app, send message, call, settings) go through phone_task with a clear Hindi command. Never make purchases or payments.
- Code changes in the user's GitHub repo go through coding_task.
- GUIDE THE USER: if the task is something the user must do themselves (how-to, setup, a form, an app/website process, a government service, repair, study plan), give exact numbered steps: where to go / what to tap, what to type, what they will see, common mistakes, and what to do if a step fails. Check the CURRENT process with web_search first (apps and websites change).
- Health / legal / money topics: give careful, sourced information and clearly say when a doctor / lawyer / expert must be consulted.
- Final answer: in the user's language (default Hindi, Devanagari), clear and well structured:
  "✅ नतीजा" — the direct answer in 1-3 lines first;
  then details with short headings / bullets / tables;
  "🪜 आगे आप क्या करें" — numbered steps when the user has something to do;
  "🔗 स्रोत" — the important source links as [title](url);
  "⚠ जो नहीं हो पाया" — honestly, if anything is left.
- Before done, check yourself: every part of the task answered? numbers verified? requested file created? A quality checker will review your answer.
- Today: $now (India time).
${if (facts.isNotBlank()) "\nWHAT YOU KNOW ABOUT THE USER:\n$facts" else ""}
""".trim()
    }

    companion object {
        /** Settings में चालू/बंद होने वाले टूल */
        val TOOL_IDS = listOf("web_search", "read_url", "run_js", "write_file", "make_pdf", "read_file", "list_files",
            "phone_task", "coding_task", "remember", "schedule")

        private val TOOLS = listOf(
            "web_search" to "web_search {\"query\":\"...\",\"n\":6} — search the web; returns titles, URLs, snippets",
            "read_url" to "read_url {\"url\":\"https://...\",\"from\":0} — read a web page as text (long page: continue with from=<offset it tells you>)",
            "read_urls" to "read_urls {\"urls\":[\"https://...\",\"https://...\"]} — read up to 4 pages at once (faster research)",
            "run_js" to "run_js {\"code\":\"...\"} — run JavaScript (no network/DOM); value of the last expression and console.log are returned",
            "write_file" to "write_file {\"name\":\"report.md\",\"content\":\"...\"} — save a file (.md .txt .csv .html .json); user can open/share it",
            "make_pdf" to "make_pdf {\"name\":\"report.pdf\",\"title\":\"...\",\"content\":\"markdown text\"} — create a PDF document",
            "read_file" to "read_file {\"name\":\"...\"} — read a file you saved earlier",
            "list_files" to "list_files {} — list saved files",
            "phone_task" to "phone_task {\"request\":\"Hindi command, e.g. YouTube खोलकर lofi songs चलाओ\"} — control the phone (apps, messages, calls, settings)",
            "coding_task" to "coding_task {\"task\":\"...\"} — give a code change to DK's coding team (user's GitHub repo); runs in background",
            "remember" to "remember {\"fact\":\"...\"} — save a long-term fact about the user",
            "parallel" to "parallel {\"calls\":[{\"tool\":\"web_search\",\"args\":{...}},{\"tool\":\"read_url\",\"args\":{...}}]} — run up to 4 INDEPENDENT read-only calls at once (web_search, read_url, read_file, list_files, run_js); much faster",
            "schedule" to "schedule {\"task\":\"...\",\"minutes\":30} or {\"task\":\"...\",\"at\":\"18:30\"} — run a DK Computer task later (reminders, later checks); saved on the phone, survives restart"
        )

        /** v3.88: parallel में सिर्फ़ पढ़ने वाले टूल (फ़ोन/फ़ाइल लिखना/कोडिंग एक-एक करके ही) */
        val PARALLEL_SAFE = setOf("web_search", "read_url", "read_file", "list_files", "run_js")
        const val PARALLEL_MAX = 4

        /** योजना बनाने वाला */
        const val PLAN_SYS = """You are the PLANNER of DK Computer (an AI agent on the user's Android phone with tools: web search, reading web pages, running JavaScript, creating files/PDF, phone control, coding team, reminders).
Understand the user's NEW TASK exactly (use the conversation for context) and make a careful step-by-step plan.
Reply ONLY JSON:
{"understood":"Hindi, 1-3 simple lines: what the user wants","steps":["Hindi: concrete step 1","..."],"deliverable":"Hindi: what the user will get at the end (answer / file / phone action / guide)"}
Rules: 2-10 concrete steps in order (what to search, which sources to read and compare, what to calculate, which file to create, what to verify, final answer with guidance). A simple greeting or chat = 1 step. Never plan purchases or payments."""

        /** आख़िरी जाँच करने वाला */
        const val VERIFY_SYS = """You are the QUALITY CHECKER of DK Computer. Check strictly whether the FINAL ANSWER completes the TASK.
Check: every part of the task answered; facts / prices / dates backed by sources found with tools (not guessed); requested files created; numbers calculated (not guessed); if the user must do something, clear numbered steps are given; answer is in the user's language and well structured.
Reply ONLY JSON: {"ok":true|false,"missing":["Hindi: exactly what is missing or wrong (max 5)"]}
Be fair: if the task is simple and the answer is complete, ok=true with empty missing. Do not ask for things the user did not want."""

        /** AI का जवाब -> टूल कॉल (```json fence, आगे-पीछे का text, "answer" बिना tool भी चलेगा) */
        fun parse(reply: String): Call? {
            val t = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val o = try { JSONObject(t) } catch (_: Exception) {
                val i = t.indexOf('{'); val j = t.lastIndexOf('}')
                if (i < 0 || j <= i) return null
                try { JSONObject(t.substring(i, j + 1)) } catch (_: Exception) { return null }
            }
            var tool = o.optString("tool").trim()
            val args = o.optJSONObject("args") ?: JSONObject()
            if (tool.isBlank() && o.has("answer")) { tool = "done"; args.put("answer", o.optString("answer")) }
            if (tool.isBlank()) return null
            // कुछ मॉडल args ऊपर ही रख देते हैं
            if (o.optJSONObject("args") == null) o.keys().forEach { k -> if (k != "tool" && k != "thought") args.put(k, o.get(k)) }
            args.remove("step")
            return Call(tool, args, o.optString("thought"), o.toString(), o.optInt("step", 0).takeIf { it > 0 })
        }

        /** मैसेज, कॉल, पेमेंट, पोस्ट, डिलीट जैसे काम = पहले पूछो */
        fun sensitive(req: String): Boolean {
            val s = req.lowercase()
            return SENSITIVE.any { s.contains(it) }
        }
        private val SENSITIVE = listOf(
            "message", "msg", "sms", "send", "whatsapp", "mail", "call", "dial", "pay", "upi", "buy", "order", "purchase",
            "delete", "remove", "post", "tweet", "share", "transfer", "book",
            "मैसेज", "संदेश", "भेज", "कॉल", "फ़ोन कर", "फोन कर", "पेमेंट", "भुगतान", "पैसे", "खरीद", "ऑर्डर", "डिलीट", "हटा", "मिटा",
            "पोस्ट", "शेयर", "ट्रांसफर", "बुक", "ईमेल", "मेल"
        )
    }
}
