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
import kotlinx.coroutines.SupervisorJob
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
    /** अभी वाला काम आवाज़ से आया? (तो पूरा होने पर / इजाज़त के लिए बोलकर बताओ) */
    @Volatile var voiceRunning = false

    /** फ़ोन का संवेदनशील काम करने से पहले पूछना */
    class Ask(val text: String, val answer: CompletableDeferred<Boolean>)
    @Volatile var ask: Ask? = null

    fun add(role: String, t: String) {
        lines.add(Line(role, t)); while (lines.size > 400) lines.removeAt(0); listener?.invoke()
        if (role == "s") TaskStatus.step(TaskStatus.PC, t)      // असली कदम (live status)
    }

    /** काम शुरू करो (या कतार में). null = शुरू/कतार, वरना वजह */
    fun start(c: Context, task: String, background: Boolean = false, voice: Boolean = false): String? {
        val ctx = c.applicationContext
        if (!Prefs.computerOn(ctx)) return "DK कंप्यूटर बंद है — Settings → DK कंप्यूटर में चालू करें"
        if (CoderAi.forComputer(ctx) { _, _ -> } == null) return "DK कंप्यूटर की AI key नहीं मिली — Settings → AI दिमाग → DK कंप्यूटर में key डालें"
        if (busy) {
            queue.addLast(Triple(task, background, voice))
            add("u", (if (voice) "🎤 " else "") + task); add("s", "कतार में रखा (${queue.size}) — अभी वाला काम पूरा होते ही शुरू होगा")
            return null
        }
        add("u", (if (voice) "🎤 " else "") + task)
        TaskStatus.start(TaskStatus.PC, "DK कंप्यूटर", "DK Computer", task)
        voiceRunning = voice
        val a = agent ?: ComputerAgent(ctx).also { agent = it }
        val j = scope.launch {
            try {
                val out = a.run(task, background)
                out.files.forEach { add("f", it) }
                add("d", out.text + "\n\n" + out.report)
                TaskStatus.end(TaskStatus.PC, if (out.question) TaskStatus.State.WAITING else TaskStatus.State.DONE, out.text)
                if (!visible || background) notifyUser(ctx, if (out.question) "DK कंप्यूटर को जानकारी चाहिए" else "DK कंप्यूटर: काम पूरा", out.text.take(300))
                if (voice) speak(ComputerVoice.spoken(out.text, out.files.size, out.question))
            } catch (e: CancellationException) {
                add("e", "काम रोक दिया।")
                TaskStatus.end(TaskStatus.PC, TaskStatus.State.CANCELLED, "काम रोक दिया")
                throw e
            } catch (e: Exception) {
                val m = (e.message ?: e.javaClass.simpleName).take(500)
                add("e", "गलती: $m\n\n" + a.reportText(m))
                TaskStatus.end(TaskStatus.PC, TaskStatus.State.FAILED, m)
                if (!visible || background) notifyUser(ctx, "DK कंप्यूटर: काम रुका", m.take(200))
                if (voice) speak("DK कंप्यूटर का काम पूरा नहीं हो पाया। वजह स्क्रीन में लिखी है।")
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
    fun reset() { stop(); queue.clear(); agent = null; lines.clear(); listener?.invoke() }

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
    data class Call(val tool: String, val args: JSONObject, val thought: String, val raw: String)
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

    suspend fun run(task: String, background: Boolean): Result {
        synchronized(issues) { issues.clear() }
        files.clear(); pendingFail = null
        val ai = CoderAi.forComputer(c) { p, f -> issue(p, f) } ?: throw IllegalStateException("AI key नहीं मिली")
        addUser((if (msgs.isEmpty()) "TASK: " else "USER: ") + task + if (background) "\n(यह तय समय पर अपने-आप चला काम है — user अभी स्क्रीन पर नहीं है)" else "")
        step("🧠 ${ai.label} से काम शुरू…")
        val max = Prefs.computerMaxSteps(c)
        var bad = 0
        for (n in 1..max) {
            compact()
            val reply = withContext(Dispatchers.IO) { ai.chat(system(), msgs) }
            val call = parse(reply)
            if (call == null) {
                bad++
                msgs.add("model" to reply.take(4000))
                if (bad >= 3) { issue("AI ने बार-बार गलत format में जवाब दिया", "उसी जवाब को आखिरी नतीजा माना"); return finish(reply.trim()) }
                issue("AI ने गलत format (JSON नहीं) में जवाब दिया", "सही format में दोबारा माँगा")
                addUser("FORMAT ERROR: reply with exactly ONE JSON object like {\"thought\":\"...\",\"tool\":\"...\",\"args\":{...}} and nothing else.")
                continue
            }
            msgs.add("model" to call.raw.take(6000))
            if (call.thought.isNotBlank()) step("💭 " + call.thought.take(200))
            when (call.tool) {
                "done" -> return finish(call.args.optString("answer").ifBlank { call.args.optString("text") })
                "ask_user" -> return finish(call.args.optString("question").ifBlank { "कृपया और जानकारी दें।" }, question = true)
                else -> {
                    val res = try { runTool(call) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        "ERROR: " + (e.message ?: e.javaClass.simpleName).take(300)
                    }
                    if (res.startsWith("ERROR")) {
                        step("⚠ ${call.tool}: " + res.removePrefix("ERROR:").trim().take(140))
                        pendingFail = "${call.tool} नहीं चला: " + res.removePrefix("ERROR:").trim().take(140)
                    } else pendingFail?.let { issue(it, "AI ने दूसरा तरीका अपनाया (${call.tool}) और काम आगे बढ़ा"); pendingFail = null }
                    addUser("TOOL RESULT (${call.tool}) [step $n/$max]:\n" + res.take(7000))
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

    private fun finish(text: String, question: Boolean = false): Result {
        pendingFail?.let { issue(it, "इसके बिना जितना हो सका उतना जवाब दिया") }; pendingFail = null
        return Result(text.ifBlank { "(खाली जवाब)" }, files.toList(), reportText(), question)
    }

    /** पुराने टूल-नतीजे छोटे करो ताकि बातचीत AI की सीमा में रहे */
    private fun compact() {
        var total = msgs.sumOf { it.second.length }
        var i = 0
        while (total > 70_000 && i < msgs.size - 4) {
            val (r, t) = msgs[i]
            if (t.length > 900) { msgs[i] = r to (t.take(700) + "\n…(पुराना नतीजा छोटा किया)"); total -= t.length - msgs[i].second.length }
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
                withContext(Dispatchers.IO) { ComputerTools.formatSearch(ComputerTools.search(c, q, a.optInt("n", 6)) { p, f -> issue(p, f) }) }
            }
            "read_url" -> {
                val u = a.optString("url").ifBlank { return "ERROR: url खाली" }
                step("🌐 पढ़ रहा हूँ: ${u.take(90)}")
                withContext(Dispatchers.IO) { ComputerTools.readUrl(u) }
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
            "read_file" -> withContext(Dispatchers.IO) { ComputerTools.read(c, a.optString("name")) }
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
        val tools = TOOLS.filter { (id, _) -> Prefs.computerTool(c, id) }.joinToString("\n") { "- ${it.second}" }
        val facts = try { Memory.factsBlock(c) } catch (_: Exception) { "" }
        val name = Prefs.assistantName(c)
        return """
You are "$name कंप्यूटर" (DK Computer), an autonomous agent running on the user's Android phone. You complete the user's task end-to-end, step by step, using tools, until it is FULLY done — like a careful human assistant with a computer.

Reply with EXACTLY ONE JSON object and nothing else (no markdown fences):
{"thought":"one short Hindi line: what you do now and why","tool":"<tool name>","args":{...}}

TOOLS:
$tools
- ask_user {"question":"..."} — only when you truly cannot continue without the user's input
- done {"answer":"final answer"} — when the task is complete

RULES:
- Plan first, then act. Break big tasks into steps. Keep going until done; never stop half-way.
- Facts, prices, news, people, places: ALWAYS verify with web_search, then read_url the best sources for details. Never invent facts, numbers, links or quotes.
- Do several searches for research tasks (different angles); compare sources; prefer official/primary sources.
- If a tool returns ERROR, do not repeat the same call blindly — fix the arguments or use another tool/source.
- For math, data, dates, unit conversion, sorting, tables: use run_js instead of guessing.
- When the user asks for a document/report/list/file, create it with write_file (md/csv/html/json/txt) or make_pdf, and mention the file name in the answer.
- Phone actions (open app, send message, call, settings) go through phone_task with a clear Hindi command. Never make purchases or payments.
- Code changes in the user's GitHub repo go through coding_task.
- Final answer: in the user's language (default Hindi, Devanagari), clear and well structured (short headings / bullets), with the important source links as [title](url). Be honest about anything you could not do.
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
            "read_url" to "read_url {\"url\":\"https://...\"} — read a web page as text",
            "run_js" to "run_js {\"code\":\"...\"} — run JavaScript (no network/DOM); value of the last expression and console.log are returned",
            "write_file" to "write_file {\"name\":\"report.md\",\"content\":\"...\"} — save a file (.md .txt .csv .html .json); user can open/share it",
            "make_pdf" to "make_pdf {\"name\":\"report.pdf\",\"title\":\"...\",\"content\":\"markdown text\"} — create a PDF document",
            "read_file" to "read_file {\"name\":\"...\"} — read a file you saved earlier",
            "list_files" to "list_files {} — list saved files",
            "phone_task" to "phone_task {\"request\":\"Hindi command, e.g. YouTube खोलकर lofi songs चलाओ\"} — control the phone (apps, messages, calls, settings)",
            "coding_task" to "coding_task {\"task\":\"...\"} — give a code change to DK's coding team (user's GitHub repo); runs in background",
            "remember" to "remember {\"fact\":\"...\"} — save a long-term fact about the user",
            "schedule" to "schedule {\"task\":\"...\",\"minutes\":30} or {\"task\":\"...\",\"at\":\"18:30\"} — run a DK Computer task later (reminders, later checks); saved on the phone, survives restart"
        )

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
            return Call(tool, args, o.optString("thought"), o.toString())
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
