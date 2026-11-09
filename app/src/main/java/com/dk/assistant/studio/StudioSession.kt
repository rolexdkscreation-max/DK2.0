package com.dk.assistant.studio

import android.content.Context
import android.content.Intent
import com.dk.assistant.CoderAi
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ऐप स्टूडियो का चलता काम (कोडिंग एजेंट और DK कंप्यूटर दोनों का साझा).
 * बनाना / बदलना / गलती ठीक करना = AI से फ़ाइलें -> project फ़ोल्डर -> हर फ़ाइल लिखते ही preview ताज़ा ([version]).
 */
object StudioSession {
    const val WHO_CODER = "coder"
    const val WHO_COMPUTER = "computer"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var job: Job? = null

    @Volatile var project: StudioProject? = null
    val busy: Boolean get() = job?.isActive == true
    /** हर बदली फ़ाइल पर +1 - preview इसी से ताज़ा होता है */
    @Volatile var version = 0; private set
    /** अभी का कदम + पिछले कदम (स्क्रीन पर) */
    val steps = mutableListOf<String>()
    @Volatile var lastResult: String = ""; private set
    @Volatile var listener: (() -> Unit)? = null
    /** काम पूरा होने पर (कोडिंग / कंप्यूटर स्क्रीन को नतीजा) */
    @Volatile var onDone: ((who: String, text: String, ok: Boolean) -> Unit)? = null
    /** बनने के बाद preview में गलती आई = एक बार अपने-आप ठीक करो (हर काम पर) */
    @Volatile var autoFixLeft = 0

    private fun step(t: String) {
        synchronized(steps) { steps.add(t); while (steps.size > 60) steps.removeAt(0) }
        DKLog.d("स्टूडियो: $t")
        com.dk.assistant.TaskStatus.step(StudioLink.KEY, t)      // नवंबर 2026: DK लाइव / notification को असली कदम
        listener?.invoke()
    }

    fun stepsNow(): List<String> = synchronized(steps) { steps.toList() }

    /** किसकी AI: कोडिंग एजेंट की key, या DK कंप्यूटर की (न हो तो दूसरे की) */
    fun aiFor(c: Context, who: String): CoderAi? {
        val coder = { CoderAi.fromPrefs(c) }
        val pc = { CoderAi.forComputer(c) { p, f -> DKLog.d("स्टूडियो AI: $p → $f") } }
        return if (who == WHO_CODER) coder() ?: pc() else pc() ?: coder()
    }

    /** काम कहाँ से आया (नतीजा वहीं): कोडिंग स्क्रीन / कंप्यूटर स्क्रीन / आवाज़. स्टूडियो स्क्रीन के अपने बदलाव = कहीं नहीं */
    @Volatile private var reportTo: String? = null
    @Volatile private var reportVoice = false

    /** कोडिंग एजेंट / DK कंप्यूटर / आवाज़ से नया ऐप - नतीजा वापस वहीं */
    fun startFrom(c: Context, request: String, who: String, voice: Boolean = false): String? {
        val e = start(c, request, who)
        if (e == null) { reportTo = who; reportVoice = voice }
        return e
    }

    private fun report(text: String, ok: Boolean) {
        val to = reportTo ?: return
        reportTo = null
        val role = if (ok) "d" else "e"
        if (to == WHO_CODER) com.dk.assistant.CoderSession.add(role, text) else com.dk.assistant.ComputerSession.add(role, text)
        if (reportVoice) {
            val say = if (ok) "आपका ऐप तैयार है, स्टूडियो में चल रहा है। चेक कर लीजिए, और जो बदलना हो वहीं बोल दीजिए।" else "ऐप नहीं बन पाया। " + text.lineSequence().first().take(140)
            // नवंबर 2026: Live चालू = TaskStatus के खत्म होने पर GeminiLive खुद SYSTEM NOTE भेजता है (StudioLink.liveNote) - दो बार नहीं
            if (!com.dk.assistant.DKState.live) {
                com.dk.assistant.DKState.reply(say)
                com.dk.assistant.DKAccessibilityService.instance?.let { svc -> svc.scope.launch { try { svc.speaker.speak(say) } catch (_: Exception) {} } }
            }
        }
    }

    fun open(c: Context, p: StudioProject?) {
        if (p != null) project = p
        c.startActivity(Intent(c, StudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** नया ऐप. return = गलती का संदेश (काम शुरू नहीं हुआ) या null */
    fun start(c: Context, request: String, who: String, openScreen: Boolean = true): String? {
        val ctx = c.applicationContext
        if (busy) return "ऐप स्टूडियो में अभी एक काम चल रहा है — पूरा होने दें या वहाँ \"रोकें\" दबाएँ।"
        aiFor(ctx, who) ?: return "ऐप बनाने के लिए AI key नहीं है — Settings → AI दिमाग → ${if (who == WHO_CODER) "कोडिंग एजेंट" else "DK कंप्यूटर"} में key डालें।"
        val kind = StudioCore.kindOf(request)
        val p = StudioProject.create(ctx, StudioCore.slug(request, System.currentTimeMillis()))
        p.title = request.take(60); p.kind = kind; p.who = who; p.request = request; p.saveMeta()
        project = p
        synchronized(steps) { steps.clear() }
        autoFixLeft = 1
        launch(ctx, p, request, emptyList(), isNew = true)
        if (openScreen) open(ctx, p)
        return null
    }

    /** उसी project में बदलाव (preview स्क्रीन से लिखकर / बोलकर) */
    fun change(c: Context, text: String): String? {
        val p = project ?: return "पहले कोई project खोलें।"
        if (busy) return "अभी काम चल रहा है — थोड़ा रुकिए।"
        if (text.isBlank()) return null
        autoFixLeft = 1
        launch(c.applicationContext, p, text, emptyList(), isNew = false)
        return null
    }

    /** preview की गलतियाँ ठीक करो */
    fun fix(c: Context, errors: List<String>, auto: Boolean = false): String? {
        val p = project ?: return "पहले कोई project खोलें।"
        if (busy || errors.isEmpty()) return null
        if (auto) { if (autoFixLeft <= 0) return null; autoFixLeft-- }
        launch(c.applicationContext, p, "", errors, isNew = false)
        return null
    }

    fun stop() { job?.cancel(); step("⏹ रोक दिया") }

    private fun launch(c: Context, p: StudioProject, text: String, errors: List<String>, isNew: Boolean) {
        // नवंबर 2026 (मालिक): स्टूडियो का काम भी TaskStatus में - DK लाइव (task_status), notification, इतिहास सबको पता
        com.dk.assistant.TaskStatus.start(StudioLink.KEY, StudioLink.WHO, StudioLink.WHO_EN, when {
            isNew -> "नया ऐप: $text"
            errors.isNotEmpty() -> "\"${p.title}\" के preview की ${errors.size} गलती ठीक करना"
            else -> "\"${p.title}\" में बदलाव: $text"
        })
        job = scope.launch {
            var cancelled = false
            val r = try { run(c, p, text, errors, isNew) } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; "रोक दिया।" to false }
            catch (e: Exception) { ("ऐप स्टूडियो में गलती: " + (e.message ?: e.javaClass.simpleName).take(200)) to false }
            lastResult = r.first
            step(if (r.second) "✅ पूरा" else "⚠ " + r.first.lineSequence().first().take(120))
            com.dk.assistant.TaskStatus.end(StudioLink.KEY, when {
                cancelled -> com.dk.assistant.TaskStatus.State.CANCELLED
                r.second -> com.dk.assistant.TaskStatus.State.DONE
                else -> com.dk.assistant.TaskStatus.State.FAILED
            }, r.first.lineSequence().first())
            try { report(r.first, r.second) } catch (_: Exception) {}
            try { onDone?.invoke(p.who, r.first, r.second) } catch (_: Exception) {}
            listener?.invoke()
        }
        listener?.invoke()
    }

    private suspend fun ask(ai: CoderAi, system: String, msgs: List<Pair<String, String>>): String =
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { ai.chat(system, msgs) }

    /** return = (नतीजा, ठीक हुआ?) */
    private suspend fun run(c: Context, p: StudioProject, text: String, errors: List<String>, isNew: Boolean): Pair<String, Boolean> {
        val ai = aiFor(c, p.who) ?: return "AI key नहीं मिली।" to false
        val sys = StudioCore.system(p.kind)
        val budget = (ai.budget * 0.6).toInt().coerceIn(20_000, 600_000)
        step(when {
            isNew -> "📐 समझा: ${p.kind.hi} — \"${text.take(80)}\" • ${ai.label}"
            errors.isNotEmpty() -> "🩺 preview की ${errors.size} गलती ठीक कर रहा हूँ"
            else -> "✏ बदलाव: \"${text.take(80)}\""
        })
        val stages = if (isNew && p.kind.native) listOf(0, 1) else listOf(0)
        var summary = ""
        var wrote = 0
        for (st in stages) {
            val task = if (isNew) StudioCore.buildTask(text, p.kind, st) else StudioCore.changeTask(text, p.kind, errors)
            val ctxFiles = if (isNew && st == 0) "" else withContext(Dispatchers.IO) { StudioCore.context(p.all(), budget) } + "\n\n"
            step(if (isNew && p.kind.native) (if (st == 0) "🎨 1/2: ऐप की स्क्रीनें (preview) लिख रहा हूँ…" else "📦 2/2: असली ${p.kind.hi} का code लिख रहा हूँ…") else "🧠 code लिख रहा हूँ…")
            val msgs = mutableListOf("user" to ctxFiles + task)
            var reply = ask(ai, sys, msgs)
            var parsed = StudioCore.parse(reply)
            if (parsed.files.isEmpty() && parsed.deleted.isEmpty()) {
                step("↻ जवाब में फ़ाइलें नहीं थीं — दोबारा माँग रहा हूँ")
                msgs.add("model" to reply.take(4000))
                msgs.add("user" to "Your reply had no files in the required format. Reply again with every file as ```lang path blocks (complete content), then SUMMARY:.")
                reply = ask(ai, sys, msgs); parsed = StudioCore.parse(reply)
            }
            wrote += apply(p, parsed)
            if (parsed.summary.isNotBlank()) summary = parsed.summary
        }
        if (isNew && !p.hasPreview()) {
            step("↻ preview की मुख्य फ़ाइल (${StudioCore.entryFor(p.kind)}) नहीं बनी — बनवा रहा हूँ")
            val ctxFiles = withContext(Dispatchers.IO) { StudioCore.context(p.all(), budget) }
            val parsed = StudioCore.parse(ask(ai, sys, listOf("user" to "$ctxFiles\n\nThe preview entry file ${StudioCore.entryFor(p.kind)} is missing. Write it now (complete), using the existing files, then SUMMARY:.")))
            wrote += apply(p, parsed)
        }
        if (wrote == 0) return "कोई फ़ाइल नहीं बनी/बदली — AI ने code नहीं दिया। दोबारा कोशिश करें या बात थोड़ी साफ़ लिखें।" to false
        if (!p.hasPreview()) return "फ़ाइलें बनीं ($wrote) पर preview वाली ${StudioCore.entryFor(p.kind)} नहीं बनी।" to false
        return StudioCore.doneText(p.title, p.kind, p.files().size, summary) to true
    }

    /** फ़ाइलें एक-एक करके लिखो (हर एक पर preview ताज़ा) */
    private suspend fun apply(p: StudioProject, r: StudioCore.Reply): Int {
        var n = 0
        for ((path, body) in r.files) {
            val w = withContext(Dispatchers.IO) { p.write(path, body) } ?: continue
            n++; version++
            step("📄 $w (${maxOf(1, body.length / 1024)} KB)")
            delay(80)
        }
        for (d in r.deleted) if (withContext(Dispatchers.IO) { p.delete(d) }) { n++; version++; step("🗑 $d") }
        return n
    }
}
