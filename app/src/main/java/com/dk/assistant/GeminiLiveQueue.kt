package com.dk.assistant

import com.dk.assistant.GeminiLive.Companion.AGENT_WAIT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// GeminiLive का हिस्सा: फ़ोन के काम और कामों की कतार (एक के बाद एक काम, WhatsApp, बीच में सवाल).
// यह code पहले GeminiLive.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state GeminiLive में)।

internal suspend fun GeminiLive.phoneTask(req: String): String {
    val running = taskQueue?.takeIf { it.running }
    if (running != null) {
        if (TaskQueue.isStop(req)) { stopQueue("phone_task: \"${req.take(20)}\""); return TaskQueue.stoppedNote() }
        // कतार ने सवाल पूछा था और Gemini ने जवाब phone_task में भेज दिया - वही जवाब
        if (running.asking) { queueAnswer?.complete(req); return "QUEUE_OK: the phone took this as the answer to its question. Say only \"ठीक है\" - no tool call." }
        // काम के बीच नया काम = कतार के आख़िर में
        val n = running.add(TaskQueue.split(req).ifEmpty { listOf(TaskQueue.Item(req, "other")) })
        log("कतार: नया काम आख़िर में जोड़ा (\"${req.take(30)}\", अब $n बाक़ी)")
        return TaskQueue.addedNote(n)
    }
    val items = TaskQueue.split(req)
    if (items.size >= 2) return startQueue(items)
    return phoneTaskOne(req)
}

internal fun GeminiLive.startQueue(items: List<TaskQueue.Item>): String {
    val startedAt = System.currentTimeMillis()
    val q = TaskRunQueue(
        runOne = { runQueued(it) },
        prepare = { prev, next, prevOk -> queuePrepare(prev, next, prevOk) },
        ask = { queueAsk(it) },
        log = { log(it) }
    )
    q.add(items)
    taskQueue = q
    TeacherBoard.exitBlocked = { taskQueue?.running == true }
    log("कतार शुरू: ${items.size} काम - " + items.joinToString(" | ") { "${it.domain}:${it.text.take(25)}" })
    svc.scope.launch {
        // "रुको / बंद करो" - Gemini के भरोसे नहीं, फ़ोन ख़ुद सुनता है
        val watcher = launch {
            var seen = DKState.lastUserAt
            while (isActive) {
                delay(200)
                val at = DKState.lastUserAt
                if (at != seen && at > startedAt) {
                    seen = at
                    if (TaskQueue.isStop(DKState.lastUser.trimEnd('…'))) { stopQueue("यूज़र ने कहा \"${DKState.lastUser.take(20)}\""); break }
                }
            }
        }
        val summary = try { q.runAll() } catch (e: CancellationException) { TaskQueue.summary(q.results, true, q.left()) }
            catch (e: Exception) { log("कतार गड़बड़: ${e.message}"); TaskQueue.summary(q.results, q.stopped, q.left()) }
            finally { watcher.cancel() }
        if (taskQueue === q) taskQueue = null
        log("कतार ख़त्म: $summary")
        if (!waitQuiet(45_000)) { log("Gemini चुप नहीं हुआ (45s) - बीच बातचीत text नहीं भेजा"); return@launch }
        if (stopped || !ready) return@launch
        visionNote(TaskQueue.summaryNote(summary), evidence = true)
    }
    return TaskQueue.startedNote(items)
}

internal fun GeminiLive.stopQueue(why: String) {
    val q = taskQueue ?: return
    if (q.stopped) return
    q.stop()
    log("कतार तुरंत रोकी: $why")
    queueAnswer?.complete("")
    try { if (agent.isBusy) agent.stop() } catch (_: Exception) {}
    try { if (com.dk.assistant.agent.android.SocialAgent.busy()) com.dk.assistant.agent.android.SocialAgent.control(JSONObject().put("command", "cancel")) } catch (_: Exception) {}
}

/** अगले काम से पहले फ़ोन ठीक हालत में: अगला काम स्क्रीन पर चलेगा (AI एजेंट) या पिछला अटका = होम */
internal suspend fun GeminiLive.queuePrepare(prev: TaskQueue.Item, next: TaskQueue.Item, prevOk: Boolean) {
    val direct = next.kind == TaskQueue.Kind.WHATSAPP_SEND || LocalRouter.deleteMedia(next.text) != null ||
        LiveFast.pick(next.text, Prefs.wakeWords(svc), svc.currentPackage()) != null
    if (!prevOk || !direct) {
        try { svc.perform(JSONObject().put("type", "home")) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        log("कतार: \"${prev.text.take(20)}\" के बाद होम (${if (!prevOk) "पिछला अटका" else "अगला स्क्रीन पर"})")
        delay(800)
    } else delay(350)
}

/** कतार का एक काम - पूरा होने तक रुको (Gemini को 7 सेकंड वाला जवाब नहीं, यह पीछे चलता है) */
internal suspend fun GeminiLive.runQueued(item: TaskQueue.Item): String {
    if (item.kind == TaskQueue.Kind.WHATSAPP_SEND) return queueWhatsApp(item)
    // कतार में WhatsApp मैसेज phone_task वाले redirect में न फँसे - सीधे भेजो
    if (com.dk.assistant.agent.social.WaRoute.isWhatsAppSend(item.text)) {
        val (c, m) = TaskQueue.parseWa(item.text)
        if (c.isNotBlank() && m.isNotBlank()) return queueWhatsApp(item.copy(kind = TaskQueue.Kind.WHATSAPP_SEND, contact = c, message = m))
        return "NEEDS USER CHOICE (nothing was done yet): किसे और क्या मैसेज भेजूँ, सर?. Ask the user"
    }
    return phoneTaskOne(item.text, queued = true)
}

internal suspend fun GeminiLive.queueWhatsApp(item: TaskQueue.Item): String {
    val sa = com.dk.assistant.agent.android.SocialAgent
    val args = JSONObject().put("action", "send_message").put("contact", item.contact).put("message", item.message)
    val r = try { sa.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP) } catch (e: CancellationException) { throw e } catch (e: Exception) { return "FAILED: ${e.message}" }
    val o = try { JSONObject(r) } catch (_: Exception) { null }
    if (o == null || o.optString("status") == "error") {
        val code = o?.optString("error").orEmpty(); val msg = o?.optString("message").orEmpty().ifBlank { r.take(120) }
        log("कतार WhatsApp: $code $msg")
        return if (code.contains("contact") || code.contains("ambiguous") || code.contains("not_found") || code.contains("many"))
            "NEEDS USER CHOICE (nothing was done yet): $msg. Ask the user" else "FAILED: $msg"
    }
    val id = o.optString("task_id")
    // भेजने से पहले "हाँ" वाला सवाल SocialAgent के SYSTEM NOTE से Gemini पूछता है - तब तक रुको
    val rep = withTimeoutOrNull(150_000) {
        while (true) {
            val l = sa.lastReport
            if (l != null && (id.isBlank() || l.taskId == id) && l.status.finished) return@withTimeoutOrNull l
            if (taskQueue?.stopped == true) return@withTimeoutOrNull null
            delay(400)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    return when {
        rep == null -> "FAILED: timeout"
        rep.status == com.dk.assistant.agent.social.TaskStatus.COMPLETED || rep.status == com.dk.assistant.agent.social.TaskStatus.SUBMITTED_UNVERIFIED -> "DONE: ${rep.message}"
        rep.status == com.dk.assistant.agent.social.TaskStatus.CANCELLED -> "FAILED: cancel - ${rep.message}"
        else -> "FAILED: ${rep.message}"
    }
}

/** कतार का एक छोटा सवाल - Gemini पूछे, जवाब फ़ोन ख़ुद ले (अगली बात या phone_task) */
internal suspend fun GeminiLive.queueAsk(question: String): String? {
    val since = System.currentTimeMillis()
    val d = CompletableDeferred<String>()
    queueAnswer = d
    visionNote(TaskQueue.askNote(question), evidence = false)
    log("कतार सवाल: $question")
    val ans = withTimeoutOrNull(40_000) {
        while (true) {
            if (d.isCompleted) return@withTimeoutOrNull d.await()
            val at = DKState.lastUserAt
            val u = DKState.lastUser
            // पूरी बात ("…" वाली अधूरी नहीं), सवाल पूछे जाने के बाद
            if (at > since + 1500 && u.isNotBlank() && !u.endsWith("…") && System.currentTimeMillis() - at > 600) return@withTimeoutOrNull u
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }
    queueAnswer = null
    return ans?.trim()?.takeIf { it.isNotBlank() && !TaskQueue.isStop(it) }
}

internal suspend fun GeminiLive.phoneTaskOne(req: String, queued: Boolean = false): String {
    // v3.59: WhatsApp मैसेज = whatsapp_task (पक्का भेजना + जाँच), phone_task नहीं
    if (com.dk.assistant.agent.social.WaRoute.isWhatsAppSend(req)) {
        log("रूटर: WhatsApp मैसेज -> whatsapp_task")
        return com.dk.assistant.agent.social.WaRoute.redirect(req)
    }
    // v3.67: "आखिरी फोटो/स्क्रीनशॉट डिलीट करो" = सीधे फ़ोन पर; Android खुद Allow पूछता है, फिर जाँच कि सच में ट्रैश में गई
    LocalRouter.deleteMedia(req)?.let { q ->
        DKState.set(Mode.WORKING)
        val t0 = System.currentTimeMillis()
        val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
        log("phone_task मिटाना (बिना AI एजेंट) ${System.currentTimeMillis() - t0} ms: ${r.substringBefore(':')}")
        return LiveFast.quickResult(q, r, already = false)
    }
    val wake = Prefs.wakeWords(svc)
    val fg = svc.currentPackage()
    val tc = System.nanoTime()
    val q = LiveFast.pick(req, wake, fg)
    log("रूटर: phone_task \"${req.take(30)}\" -> ${q?.action?.optString("type") ?: "फ़ोन पर पूरा नहीं"} (${(System.nanoTime() - tc) / 1_000_000} ms)")
    var same = fastMemo.same(q, System.currentTimeMillis())
    if (same != LiveFast.Memo.NOT_SAME) {
        val until = System.currentTimeMillis() + 4000
        while (same == null && System.currentTimeMillis() < until) { delay(80); same = fastMemo.same(q, System.currentTimeMillis()) }
        log("phone_task = अभी-अभी किया काम, दोबारा नहीं: $req")
        val r = same
        if (r != null && r != LiveFast.Memo.NOT_SAME) {
            if (LiveFast.ok(q!!.action.optString("type"), r) || LocalRouter.final(r)) return LiveFast.quickResult(q, r, already = true)
            // तुरंत वाला नहीं चला (ऐप नहीं मिला / वीडियो सीधे नहीं चला) -> AgentRunner संभाले
        } else if (r == null) return "STARTED just now and still opening. Tell the user in 2-5 words."
    } else if (q != null && fastMemo.begin(q, System.currentTimeMillis())) {
        DKState.set(Mode.WORKING)
        val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
        fastMemo.finish(q, r)
        log("phone_task सीधे (बिना AI): ${q.action} = ${r.take(60)}")
        // v3.33: ऐप है ही नहीं / कॉन्टैक्ट नहीं / user से पूछना है = AI से कुछ नहीं सुधरेगा -> सीधे सच
        if (LiveFast.ok(q.action.optString("type"), r) || LocalRouter.final(r)) return LiveFast.quickResult(q, r, already = false)
    }
    DKState.set(Mode.WORKING)
    // v3.33: आधा काम फ़ोन पर ("Flipkart खोलो, X सर्च करो") + बाकी ("पहला प्रोडक्ट कार्ट में डालो") AgentRunner को
    var note = ""
    if (q == null) {
        val sp = LocalRouter.split(req, wake, fg)
        val pre = sp.local
        if (pre != null && sp.rest != null && fastMemo.begin(pre, System.currentTimeMillis())) {
            val r = try { svc.perform(pre.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
            fastMemo.finish(pre, r)
            log("phone_task: पहला हिस्सा फ़ोन पर (${pre.action.optString("type")}) = ${r.take(60)}, बाकी AgentRunner")
            if (!LiveFast.ok(pre.action.optString("type"), r) && LocalRouter.final(r)) return LiveFast.quickResult(pre, r, already = false)
            note = LocalRouter.DONE_NOTE + " " + LiveFast.describe(pre.action) + " -> " + r.take(160) +
                "\nContinue the rest of the user's goal from the CURRENT screen."
        }
    }
    val before = DKState.lastReply
    val failBefore = DKState.failedAt
    val job = agent.run(req, speak = false, note = note)
    // v3.41: 20s -> 7s. Gemini tool ke jawab tak CHUP rehta hai - 20s chuppi = "sun hi nahi raha". Ab 7s me
    // "kar raha hoon" bolta hai; kaam peeche chalta rehta hai aur poora hote hi lateNote se batata hai.
    // v3.87: कतार में = पूरा होने तक (90s) रुको, फिर अगला काम; बाद वाला संदेश नहीं
    val finished = withTimeoutOrNull(if (queued) 90_000 else AGENT_WAIT_MS) { job.join() } != null
    if (queued && !finished) job.cancel()
    val reply = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
    if (!finished && !queued) {
        log("phone_task ${AGENT_WAIT_MS / 1000}s में पूरा नहीं - Gemini को सच बताया (चल रहा है), पूरा होने पर बताऊँगा")
        svc.scope.launch {
            val done = withTimeoutOrNull(120_000) { job.join() } != null
            if (!done) job.cancel()
            val rep = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
                ?: if (!done) "It could not be finished in time and was stopped." else null
            // DK बोल रहा हो तो बीच में नहीं - चुप होने तक (10s तक) रुको
            if (!waitQuiet(45_000)) { log("Gemini चुप नहीं हुआ (45s) - बीच बातचीत text नहीं भेजा"); return@launch }
            if (stopped || !ready) return@launch
            val bad = !done || DKState.failedAt != failBefore
            lastEvidenceAt = System.currentTimeMillis()
            try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", LiveFast.lateNote(req, rep, bad))).toString()) } catch (_: Exception) {}
            log("phone_task बाद में पूरा (${if (done) "हुआ" else "रोका"}) - Gemini को बताया")
        }
    }
    return LiveFast.agentResult(finished, reply, failed = finished && DKState.failedAt != failBefore)
}
