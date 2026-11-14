package com.dk.assistant.dkai

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dk.assistant.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक - Pipeline अपग्रेड): DKAI होम का Pipeline मोड.
 * पहले = लिखा हुआ सीधे GitHub कोडिंग एजेंट को (वो रास्ता अब भी बटन \"GitHub कोडिंग एजेंट को दें\" से).
 * अब = बड़ा लक्ष्य -> Blueprint (कदम) स्क्रीन पर -> एक-एक कदम (पूरा + सेव + syntax जाँच तभी अगला; पिछले का सार + फ़ाइलें आगे)
 *      -> हर कदम Pending / In Progress / Completed / Failed -> Pause / Stop / Retry / Skip / प्लान बदलें
 *      -> आख़िर में पूरी जाँच (फ़ाइलें जुड़ीं, कोष्ठक/टैग, JSON) -> Keep All / Undo All / Preview.
 * pure logic = [DkaiPlan]. काम = चुने हुए DKAI प्रोजेक्ट की फ़ाइलों में (app का अपना user-space).
 */
class DkaiPipeline(private val s: DkaiScreen) {
    private val c = s.activity
    private val ui = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private fun d(v: Float) = DkaiUi.d(c, v)

    private lateinit var layer: DkaiUi.Layer
    private lateinit var state: TextView
    private lateinit var projLine: TextView
    private lateinit var goalText: TextView
    private lateinit var list: LinearLayout
    private lateinit var logText: TextView
    private lateinit var runBtn: TextView
    private lateinit var finalBox: LinearLayout

    private var project: File? = null
    private var goal = ""
    private var steps = mutableListOf<DkaiPlan.Step>()
    private val notes = mutableListOf<String>()
    /** Undo All के लिए: path -> Pipeline से पहले वाला text (null = फ़ाइल नहीं थी) */
    private val originals = LinkedHashMap<String, String?>()
    /** चल रहे कदम से पहले वाला text (Stop = यही वापस) */
    private val stepBefore = LinkedHashMap<String, String?>()
    private var job: Job? = null
    private var running = false
    private var pauseAsked = false
    private var planning = false
    private val log = ArrayList<String>()

    companion object {
        const val FILE = "pipeline.json"
        fun stateFile(p: File) = File(p, DkaiKit.CONFIG_DIR + "/" + FILE)
    }

    // ================================================================== पन्ना
    fun open(newGoal: String?) {
        val (page, body, right) = DkaiUi.page(c, "Pipeline") { close() }
        state = DkaiUi.status(c, "READY", DkaiKit.EMERALD)
        right.addView(state)
        right.addView(DkaiTheme.pill(c, "+ New", DkaiTheme.PILL, DkaiTheme.WHITE, 12.5f).apply {
            minHeight = d(36f); contentDescription = "नया Pipeline लक्ष्य"; setOnClickListener { askGoal() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16f), d(10f), d(16f), d(16f)) }
        projLine = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY).apply { setPadding(d(2f), 0, 0, d(6f)) }
        val pr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pr.addView(projLine, LinearLayout.LayoutParams(0, -2, 1f))
        pr.addView(DkaiTheme.pill(c, "प्रोजेक्ट बदलें", DkaiTheme.PILL, DkaiTheme.WHITE, 12f).apply { minHeight = d(34f); setOnClickListener { pickProject() } })
        col.addView(pr)

        val gc = card()
        gc.addView(DkaiUi.label(c, "लक्ष्य (Goal)").apply { setPadding(0, 0, 0, d(6f)) })
        goalText = DkaiTheme.text(c, "", 14f, DkaiTheme.WHITE).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true); UiLang.keep(this) }
        gc.addView(goalText)
        col.addView(gc, LinearLayout.LayoutParams(-1, -2))

        val bh = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bh.addView(DkaiUi.label(c, "Blueprint / Roadmap"), LinearLayout.LayoutParams(0, -2, 1f))
        bh.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.EDIT, "प्लान बदलें / निर्देश दें", DkaiTheme.CYAN, 38f) { editPlan() })
        col.addView(bh)
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, LinearLayout.LayoutParams(-1, -2))

        finalBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(finalBox, LinearLayout.LayoutParams(-1, -2))

        col.addView(DkaiUi.label(c, "Live log"))
        logText = DkaiTheme.text(c, "", 11.5f, DkaiTheme.SUB, DkaiTheme.MONO).apply { setTextIsSelectable(true); UiLang.keep(this) }
        col.addView(card().apply { addView(logText) }, LinearLayout.LayoutParams(-1, -2))
        body.addView(ScrollView(c).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- नीचे: चलाएँ/रोकें + Stop + GitHub एजेंट
        val bot = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), d(8f), d(12f), d(10f)); setBackgroundColor(DkaiTheme.NAV) }
        runBtn = DkaiUi.button(c, "▶ शुरू करें", DkaiTheme.BLUE) { runOrPause() }
        bot.addView(runBtn, LinearLayout.LayoutParams(0, -2, 1f))
        bot.addView(DkaiUi.button(c, "■ Stop", DkaiTheme.FIELD, DkaiTheme.RED) { stop() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        bot.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.GITHUB, "GitHub कोडिंग एजेंट को दें", DkaiTheme.WHITE, 46f) { toGithubAgent() },
            LinearLayout.LayoutParams(d(46f), d(46f)).apply { leftMargin = d(6f) })
        body.addView(bot, LinearLayout.LayoutParams(-1, -2))

        layer = DkaiUi.open(s.root, page, DkaiUi.Place.FULL, onClose = { release() })

        project = DkaiWorkspace.activeProject(c)
        val saved = project?.let { p -> try { DkaiPlan.fromJson(stateFile(p).takeIf { it.isFile }?.readText()) } catch (_: Exception) { null } }
        when {
            !newGoal.isNullOrBlank() -> start(newGoal)
            saved != null -> { goal = saved.goal; steps = saved.steps.toMutableList(); notes.addAll(saved.notes); say("पिछला Pipeline खुला (${DkaiPlan.progress(steps)} कदम पूरे)") }
            else -> askGoal()
        }
        render()
    }

    private fun card() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(12f), d(14f), d(12f)); background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER) }

    private fun close() = DkaiUi.close(s.root, layer)

    private fun release() { job?.cancel(); job = null; running = false }

    private fun say(t: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log.add("[$time] $t"); while (log.size > 120) log.removeAt(0)
        ui.post { if (::logText.isInitialized) logText.text = log.takeLast(40).joinToString("\n") }
    }

    private fun setState(t: String, col: Int) = ui.post { if (::state.isInitialized) { state.text = "● $t"; state.setTextColor(col) } }

    private fun stColor(st: DkaiPlan.St) = when (st) {
        DkaiPlan.St.PENDING -> DkaiTheme.GRAY; DkaiPlan.St.RUNNING -> DkaiTheme.AMBER; DkaiPlan.St.DONE -> DkaiTheme.GREEN
        DkaiPlan.St.FAILED -> DkaiTheme.RED; DkaiPlan.St.SKIPPED -> DkaiTheme.VIOLET
    }

    private fun render() = ui.post {
        if (!::list.isInitialized) return@post
        projLine.text = "प्रोजेक्ट: " + (project?.name ?: "कोई नहीं (बनेगा)")
        goalText.text = goal.ifBlank { "ऊपर + New से बड़ा लक्ष्य लिखें (जैसे: \"to-do ऐप बनाओ जिसमें add/delete/search हो\")" }
        list.removeAllViews()
        if (planning) list.addView(DkaiTheme.text(c, "AI पूरा काम छोटे कदमों में बाँट रहा है… (अभी कोड नहीं लिखा जाएगा)", 13f, DkaiTheme.AMBER).apply { setPadding(d(2f), d(4f), 0, d(8f)) })
        steps.forEachIndexed { i, st -> list.addView(stepRow(i, st)) }
        runBtn.text = when {
            running && pauseAsked -> "… इस कदम के बाद रुकेगा"
            running -> "⏸ Pause"
            steps.isEmpty() -> "▶ प्लान बनाएँ"
            DkaiPlan.nextIndex(steps) < 0 -> "✓ सब पूरा"
            steps.any { it.status != DkaiPlan.St.PENDING } -> "▶ आगे चलाएँ"
            else -> "▶ शुरू करें"
        }
        val doneAll = steps.isNotEmpty() && DkaiPlan.nextIndex(steps) < 0
        if (!running) setState(when { steps.isEmpty() -> "READY"; doneAll -> "DONE ${DkaiPlan.progress(steps)}"; else -> "PLAN ${DkaiPlan.progress(steps)}" },
            if (doneAll) DkaiTheme.GREEN else DkaiKit.EMERALD)
    }

    private fun stepRow(i: Int, st: DkaiPlan.Step): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
        setPadding(d(12f), d(10f), d(12f), d(10f))
        background = DkaiTheme.press(DkaiTheme.round(c, if (st.status == DkaiPlan.St.RUNNING) DkaiTheme.SEL else DkaiTheme.FIELD, 14f,
            if (st.status == DkaiPlan.St.RUNNING) DkaiTheme.AMBER else DkaiTheme.BORDER))
        isClickable = true; isFocusable = true
        contentDescription = "Step ${i + 1}: ${st.title}, ${st.status.label}"
        addView(DkaiTheme.text(c, "${i + 1}", 13f, DkaiTheme.ON, DkaiTheme.SANS_BOLD).apply {
            gravity = Gravity.CENTER; background = DkaiTheme.oval(stColor(st.status))
        }, LinearLayout.LayoutParams(d(26f), d(26f)).apply { rightMargin = d(10f); topMargin = d(2f) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(DkaiTheme.text(c, st.title, 14.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(DkaiTheme.text(c, st.status.label, 11f, stColor(st.status), DkaiTheme.SANS_BOLD))
        col.addView(top)
        if (st.goal.isNotBlank() && st.goal != st.title) col.addView(DkaiTheme.text(c, st.goal, 12f, DkaiTheme.GRAY).apply { setPadding(0, d(3f), 0, 0) })
        val extra = listOfNotNull(st.summary.takeIf { it.isNotBlank() }, st.files.takeIf { it.isNotEmpty() }?.joinToString(", ", "✎ "), st.note.takeIf { it.isNotBlank() })
        if (extra.isNotEmpty()) col.addView(DkaiTheme.text(c, extra.joinToString("\n"), 11.5f, if (st.status == DkaiPlan.St.FAILED) DkaiTheme.RED else DkaiTheme.SUB).apply { setPadding(0, d(4f), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { stepMenu(i) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8f) }
    }

    // ================================================================== उपयोगकर्ता के controls
    private fun askGoal() {
        val f = DkaiUi.field(c, "बड़ा लक्ष्य / कमांड लिखें…", goal, multi = true)
        android.app.AlertDialog.Builder(c).setTitle("Pipeline लक्ष्य").setView(f)
            .setPositiveButton("प्लान बनाएँ") { _, _ -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let { start(it) } }
            .setNegativeButton("रद्द", null).show()
    }

    private fun runOrPause() {
        if (running) { pauseAsked = true; say("⏸ Pause: यह कदम पूरा होते ही रुकेगा"); render(); return }
        if (planning) return
        if (steps.isEmpty()) { if (goal.isBlank()) askGoal() else start(goal); return }
        if (DkaiPlan.nextIndex(steps) < 0) { finalCheck(); return }
        runLoop()
    }

    private fun stop() {
        if (!running && !planning) { s.toast("अभी कुछ नहीं चल रहा"); return }
        job?.cancel(); job = null
        val reverted = revertStep()
        steps.indexOfFirst { it.status == DkaiPlan.St.RUNNING }.takeIf { it >= 0 }?.let { steps[it] = steps[it].copy(status = DkaiPlan.St.PENDING, note = "■ रोका गया - Retry से फिर चलेगा") }
        running = false; planning = false; pauseAsked = false
        say("■ Stop: अधूरा कदम रद्द" + if (reverted > 0) " ($reverted फ़ाइल पहले जैसी)" else "")
        save(); render()
    }

    private fun stepMenu(i: Int) {
        val st = steps.getOrNull(i) ?: return
        val items = listOf("↻ Retry (यह कदम फिर से)", "⤼ Skip (छोड़ दें)", "✎ यह कदम बदलें", "✕ यह कदम हटाएँ")
        android.app.AlertDialog.Builder(c).setTitle("Step ${i + 1}: ${st.title}").setItems(items.toTypedArray()) { _, k ->
            if (running) { s.toast("पहले Pause / Stop करें"); return@setItems }
            when (k) {
                0 -> { steps[i] = st.copy(status = DkaiPlan.St.PENDING, note = ""); for (j in i + 1 until steps.size) if (steps[j].status == DkaiPlan.St.FAILED) steps[j] = steps[j].copy(status = DkaiPlan.St.PENDING); save(); render(); runLoop(i) }
                1 -> { steps[i] = st.copy(status = DkaiPlan.St.SKIPPED, note = "उपयोगकर्ता ने छोड़ा"); save(); render() }
                2 -> editStep(i)
                3 -> { steps.removeAt(i); save(); render() }
            }
        }.show()
    }

    private fun editStep(i: Int) {
        val st = steps[i]
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(18f), d(8f), d(18f), 0) }
        val t = DkaiUi.field(c, "शीर्षक", st.title); val g = DkaiUi.field(c, "क्या करना है", st.goal, multi = true)
        box.addView(t); box.addView(g, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8f) })
        android.app.AlertDialog.Builder(c).setTitle("Step ${i + 1} बदलें").setView(box)
            .setPositiveButton("सेव") { _, _ -> steps[i] = st.copy(title = t.text.toString().trim().ifEmpty { st.title }, goal = g.text.toString().trim().ifEmpty { st.goal }, status = DkaiPlan.St.PENDING); save(); render() }
            .setNegativeButton("रद्द", null).show()
    }

    /** प्लान में बदलाव: नया निर्देश -> बचे कदम AI फिर से बनाए (पूरे हुए वही रहें) + निर्देश हर अगले कदम को भी */
    private fun editPlan() {
        if (running) { s.toast("पहले Pause / Stop करें"); return }
        val f = DkaiUi.field(c, "जैसे: login पेज भी जोड़ो / रंग नीला रखो / Step 3 पहले करो", multi = true)
        android.app.AlertDialog.Builder(c).setTitle("प्लान बदलें / निर्देश दें").setView(f)
            .setPositiveButton("प्लान दोबारा बनाएँ") { _, _ -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let { replan(it) } }
            .setNeutralButton("सिर्फ़ निर्देश जोड़ें") { _, _ -> f.text.toString().trim().takeIf { it.isNotEmpty() }?.let { notes.add(it); say("✎ निर्देश जुड़ा - अगले हर कदम को मिलेगा"); save() } }
            .setNegativeButton("रद्द", null).show()
    }

    private fun pickProject() {
        if (running) { s.toast("पहले Pause / Stop करें"); return }
        val ps = DkaiWorkspace.projects(c)
        val labels = ps.map { it.name } + "+ नया प्रोजेक्ट"
        android.app.AlertDialog.Builder(c).setTitle("Pipeline किस प्रोजेक्ट में").setItems(labels.toTypedArray()) { _, i ->
            if (i < ps.size) { project = ps[i]; DkaiWorkspace.setProject(c, ps[i]); originals.clear(); render() }
            else { val f = DkaiUi.field(c, "प्रोजेक्ट का नाम")
                android.app.AlertDialog.Builder(c).setTitle("नया प्रोजेक्ट").setView(f).setPositiveButton("बनाएँ") { _, _ ->
                    val p = DkaiWorkspace.createProject(c, f.text.toString()); if (p == null) s.toast("नाम सही नहीं / पहले से है") else { project = p; originals.clear(); render() }
                }.setNegativeButton("रद्द", null).show() }
        }.show()
    }

    private fun ensureProject(): File? {
        project?.takeIf { it.isDirectory }?.let { return it }
        val p = DkaiWorkspace.activeProject(c) ?: DkaiWorkspace.createProject(c, "Pipeline-" + System.currentTimeMillis() / 1000)
        project = p; return p
    }

    /** पुराना रास्ता बचा: लक्ष्य + Blueprint GitHub कोडिंग एजेंट को */
    private fun toGithubAgent() {
        val g = goal.ifBlank { s.toast("पहले लक्ष्य लिखें"); return }
        val plan = if (steps.isEmpty()) "" else "\n\nBlueprint (step by step, finish and verify each step before the next):\n" +
            steps.mapIndexed { i, st -> "${i + 1}. ${st.title} - ${st.goal}" }.joinToString("\n")
        CoderSession.queue.addLast(g + plan)
        c.startActivity(Intent(c, CodingActivity::class.java))
    }

    // ================================================================== काम
    private suspend fun ask(system: String, prompt: String): String? {
        // 14 नवंबर 2026 (मालिक): होम पर चुना मॉडल ही काम करे, नाम/गलती सामने नहीं ([DkaiPick])
        return DkaiPick.ask(c, system, prompt)
    }

    private fun fileList(p: File): List<String> = p.walkTopDown().maxDepth(6)
        .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != DkaiKit.CONFIG_DIR && it.name != "build" }
        .filter { it.isFile }.map { it.relativeTo(p).path }.toList()

    /** 1. Blueprint: पहले सिर्फ़ प्लान (कोड नहीं) */
    private fun start(g: String) {
        if (running || planning) { s.toast("Pipeline अभी चल रहा है"); return }
        val p = ensureProject() ?: run { s.toast("प्रोजेक्ट नहीं बना"); return }
        goal = g.trim(); steps.clear(); notes.clear(); originals.clear(); log.clear()
        planning = true; setState("PLANNING", DkaiTheme.AMBER); render()
        say("▶ लक्ष्य: ${goal.take(160)}"); say("Blueprint बन रहा है (${p.name})…")
        job = scope.launch {
            val raw = try { ask(DkaiPlan.PLAN_SYSTEM, DkaiPlan.planPrompt(goal, withContext(Dispatchers.IO) { fileList(p) })) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            var plan = DkaiPlan.parsePlan(raw)
            if (plan.isEmpty()) { plan = DkaiPlan.defaultPlan(goal); say(if (raw.isNullOrBlank()) "AI से प्लान नहीं मिला - मानक 4 कदम वाला प्लान" else "AI का प्लान पढ़ा नहीं गया - मानक 4 कदम वाला प्लान") }
            steps = plan.toMutableList(); planning = false
            say("✓ Blueprint तैयार: ${steps.size} कदम - देखें, बदलना हो तो ✎, फिर ▶ शुरू करें")
            save(); render()
        }
    }

    private fun replan(instruction: String) {
        notes.add(instruction)
        planning = true; render(); say("✎ प्लान बदल रहा हूँ: ${instruction.take(120)}")
        job = scope.launch {
            val raw = try { ask(DkaiPlan.PLAN_SYSTEM, DkaiPlan.replanPrompt(goal, steps, instruction)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            val fresh = DkaiPlan.parsePlan(raw)
            planning = false
            if (fresh.isEmpty()) say("AI नया प्लान नहीं दे पाया - निर्देश अगले कदमों को मिलेगा")
            else { steps = DkaiPlan.merge(steps, fresh).toMutableList(); say("✓ नया प्लान: ${steps.size} कदम") }
            save(); render()
        }
    }

    /** 2. क्रम से: एक कदम पूरा + जाँच हो तभी अगला */
    private fun runLoop(only: Int = -1) {
        val p = ensureProject() ?: return
        if (running) return
        running = true; pauseAsked = false; render()
        job = scope.launch {
            var allDone = false
            var first = only
            try {
                while (isActive) {
                    if (pauseAsked) { say("⏸ रुका - ▶ आगे चलाएँ से जारी"); break }
                    val i = if (first >= 0 && steps.getOrNull(first)?.status == DkaiPlan.St.PENDING) first else DkaiPlan.nextIndex(steps)
                    first = -1
                    if (i < 0) { allDone = true; break }
                    val ok = runStep(p, i)
                    if (!ok) { say("✗ Step ${i + 1} नहीं हुआ - Retry / Skip / प्लान बदलें"); break }
                }
            } finally { running = false; pauseAsked = false; save(); render() }
            if (allDone) finalCheck()
        }
    }

    private suspend fun runStep(p: File, i: Int): Boolean {
        steps[i] = steps[i].copy(status = DkaiPlan.St.RUNNING, note = ""); stepBefore.clear()
        setState("STEP ${i + 1}/${steps.size}", DkaiTheme.AMBER); render(); save()
        say("▶ Step ${i + 1}: ${steps[i].title}")
        var problems: List<String> = emptyList()
        for (attempt in 0..1) {
            val ctx = withContext(Dispatchers.IO) { DkaiCopilot.context(p) }
            val raw = try { ask(DkaiPlan.stepSystem(), DkaiPlan.stepPrompt(goal, steps, i, notes, ctx, problems)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { say("AI: ${e.message?.take(100)}"); null }
            if (raw.isNullOrBlank()) { steps[i] = steps[i].copy(status = DkaiPlan.St.FAILED, note = "AI से जवाब नहीं मिला (Settings → AI दिमाग की key देखें)"); render(); return false }
            val edits = DkaiKit.parseEdits(raw); val summary = DkaiKit.summaryOf(raw)
            if (edits.isEmpty()) {
                if (raw.trim().startsWith("NO CHANGE", true) || attempt == 1 && problems.isEmpty()) {
                    steps[i] = steps[i].copy(status = DkaiPlan.St.DONE, summary = summary.removePrefix("NO CHANGE").trim(':', ' ', '-').ifBlank { "कोई फ़ाइल बदलने की ज़रूरत नहीं" }, files = emptyList())
                    say("✓ Step ${i + 1}: फ़ाइल बदलने की ज़रूरत नहीं"); render(); save(); return true
                }
                problems = listOf("You did not output any === FILE: === block. Output the full files for this step."); continue
            }
            val changed = withContext(Dispatchers.IO) { apply(p, edits) }
            for (x in changed) say("✎ ${x}")
            // 2b. जाँच (syntax) - कदम सेव + सही तभी अगला
            problems = withContext(Dispatchers.IO) { changed.mapNotNull { path -> File(p, path).takeIf { it.isFile }?.let { f -> DkaiPlan.syntax(path, f.readText())?.let { "$path: $it" } } } }
            if (problems.isEmpty()) {
                val prevFiles = if (attempt == 0) changed else (steps[i].files + changed).distinct()
                steps[i] = steps[i].copy(status = DkaiPlan.St.DONE, summary = summary.take(200), files = prevFiles)
                say("✓ Step ${i + 1} पूरा + जाँच ठीक (${changed.size} फ़ाइल)"); stepBefore.clear(); render(); save(); return true
            }
            steps[i] = steps[i].copy(files = changed)
            say("⚠ जाँच में गलती: ${problems.first().take(140)}" + if (attempt == 0) " - अपने-आप ठीक कर रहा हूँ" else "")
        }
        steps[i] = steps[i].copy(status = DkaiPlan.St.FAILED, note = problems.take(3).joinToString("\n"))
        render(); save(); return false
    }

    /** फ़ाइलें लिखो (प्रोजेक्ट के अंदर ही) + Undo All / Stop के लिए पुराना याद */
    private fun apply(p: File, edits: List<DkaiKit.Edit>): List<String> {
        val out = ArrayList<String>()
        for (e in edits) {
            val f = File(p, e.path)
            if (!DkaiCore.inside(p, f) || e.path.startsWith(DkaiKit.CONFIG_DIR + "/")) continue
            val old = if (f.isFile) try { f.readText() } catch (_: Exception) { null } else null
            if (old == e.content) continue
            if (e.path !in originals) originals[e.path] = old
            if (e.path !in stepBefore) stepBefore[e.path] = old
            f.parentFile?.mkdirs(); f.writeText(e.content); out.add(e.path)
        }
        return out
    }

    private fun revertStep(): Int {
        val p = project ?: return 0
        var n = 0
        for ((path, old) in stepBefore) try { val f = File(p, path); if (old == null) f.delete() else f.writeText(old); if (originals[path] == old) originals.remove(path); n++ } catch (_: Exception) {}
        stepBefore.clear(); return n
    }

    /** 4. आख़िरी जाँच: फ़ाइलें आपस में जुड़ीं + syntax; गलती = एक बार अपने-आप ठीक; फिर Keep All / Undo All / Preview */
    private fun finalCheck() {
        val p = project ?: return
        if (running) return
        running = true; setState("CHECKING", DkaiTheme.AMBER); render(); say("आख़िरी जाँच: सब फ़ाइलें आपस में जुड़ी हैं? syntax?")
        job = scope.launch {
            try {
                val touched = (originals.keys + steps.flatMap { it.files }).toSet()
                var probs = withContext(Dispatchers.IO) { check(p, touched) }
                if (probs.isNotEmpty()) {
                    say("⚠ ${probs.size} गलती - अपने-आप ठीक कर रहा हूँ")
                    val ctx = withContext(Dispatchers.IO) { DkaiCopilot.context(p) }
                    val raw = ask(DkaiKit.COPILOT_SYSTEM, "PROJECT: ${p.name}\n\n$ctx\nCOMMAND: The project was built for this goal: $goal\n" +
                        "The final integration check found these errors. Fix ALL of them (create missing files or fix the references/syntax), keep everything else the same:\n" +
                        probs.take(25).joinToString("\n") { "- $it" })
                    if (!raw.isNullOrBlank()) { val ch = withContext(Dispatchers.IO) { apply(p, DkaiKit.parseEdits(raw)) }; ch.forEach { say("✎ $it") } }
                    probs = withContext(Dispatchers.IO) { check(p, touched + originals.keys) }
                }
                showFinal(p, probs)
            } finally { running = false; save(); render() }
        }
    }

    private fun check(p: File, touched: Set<String>): List<DkaiPlan.Problem> {
        val map = LinkedHashMap<String, String>()
        p.walkTopDown().maxDepth(6).onEnter { it.name != ".git" && it.name != "node_modules" && it.name != DkaiKit.CONFIG_DIR }
            .filter { it.isFile && DkaiCore.isTextName(it.name) && it.length() < 400_000 }
            .forEach { f -> try { map[f.relativeTo(p).path] = f.readText() } catch (_: Exception) {} }
        return DkaiPlan.integrate(map, touched.ifEmpty { null }) { File(p, it).exists() }
    }

    private fun showFinal(p: File, probs: List<DkaiPlan.Problem>) = ui.post {
        finalBox.removeAllViews()
        val box = card().apply { background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, if (probs.isEmpty()) DkaiTheme.GREEN else DkaiTheme.AMBER, 1.5f) }
        box.addView(DkaiTheme.text(c, if (probs.isEmpty()) "✓ Final Build Check: सब ठीक" else "⚠ Final Build Check: ${probs.size} बात बची", 15f,
            if (probs.isEmpty()) DkaiTheme.GREEN else DkaiTheme.AMBER, DkaiTheme.SANS_BOLD))
        val files = originals.keys.toList()
        box.addView(DkaiTheme.text(c, "${files.size} फ़ाइल बनी/बदली" + (if (files.isNotEmpty()) ": " + files.joinToString(", ").take(240) else "") +
            (if (probs.isNotEmpty()) "\n" + probs.take(6).joinToString("\n") { "• $it" } else ""), 12f, DkaiTheme.SUB).apply { setPadding(0, d(6f), 0, d(4f)) })
        box.addView(DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Undo All", DkaiTheme.FIELD, DkaiTheme.RED) { undoAll() },
            DkaiUi.button(c, "Keep All", DkaiTheme.GREEN) { originals.clear(); finalBox.removeAllViews(); say("✓ Keep All: सारे बदलाव रखे"); s.toast("सारे बदलाव रखे गए") }))
        val entry = DkaiWorkspace.entryOf(p)
        if (entry != null) box.addView(DkaiUi.button(c, "Preview", DkaiTheme.BLUE, kind = DkaiIcon.Kind.PREVIEW) { close(); s.openPreview(entry) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8f) })
        box.addView(DkaiUi.button(c, "Files में देखें", DkaiTheme.PILL, DkaiTheme.WHITE, DkaiIcon.Kind.FILES) { close(); s.openFiles(p) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8f) })
        finalBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(4f); bottomMargin = d(4f) })
        say(if (probs.isEmpty()) "✓ सब कदम पूरे, जाँच ठीक - Keep All / Undo All / Preview" else "⚠ जाँच के बाद भी ${probs.size} बात - देखें या Retry")
    }

    private fun undoAll() {
        val p = project ?: return
        if (running) { s.toast("पहले Stop करें"); return }
        var n = 0
        for ((path, old) in originals) try { val f = File(p, path); if (old == null) f.delete() else f.writeText(old); n++ } catch (_: Exception) {}
        originals.clear()
        steps = steps.map { it.copy(status = DkaiPlan.St.PENDING, summary = "", files = emptyList(), note = "") }.toMutableList()
        finalBox.removeAllViews(); say("↶ Undo All: $n फ़ाइल पहले जैसी"); save(); render()
    }

    private fun save() {
        val p = project ?: return
        val json = DkaiPlan.toJson(goal, steps, notes)
        scope.launch(Dispatchers.IO) { try { stateFile(p).apply { parentFile?.mkdirs() }.writeText(json) } catch (_: Exception) {} }
    }
}
