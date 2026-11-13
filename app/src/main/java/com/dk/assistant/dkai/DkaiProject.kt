package com.dk.assistant.dkai

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import com.dk.assistant.*
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक - M5/M6/M7): प्रोजेक्ट वाले modal - Project Directories, GitHub Clone, Copilot Enhancement.
 */
object DkaiModals {

    // ================================================================== Project Directories
    fun projects(s: DkaiScreen, onPick: (File) -> Unit) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.modal(c, "Project Directories", DkaiKit.shownPath(DkaiWorkspace.projectsRoot(c))) { DkaiUi.close(s.root, layer) }
        val root = DkaiWorkspace.projectsRoot(c)
        body.addView(DkaiUi.row(c, DkaiIcon.Kind.FILES, "Root Workspace", DkaiKit.shownPath(root), false, DkaiTheme.AMBER) {
            DkaiUi.close(s.root, layer); s.openFiles(root)
        })
        val active = DkaiWorkspace.activeProject(c)?.absolutePath
        val list = DkaiWorkspace.projects(c)
        if (list.isEmpty()) body.addView(DkaiTheme.text(c, "अभी कोई प्रोजेक्ट नहीं - नीचे से बनाइए या GitHub से Clone कीजिए", 12.5f, DkaiTheme.GRAY).apply { setPadding(DkaiUi.d(c, 4f), DkaiUi.d(c, 4f), 0, DkaiUi.d(c, 10f)) })
        for (p in list) {
            body.addView(DkaiUi.row(c, DkaiIcon.Kind.FOLDER_PLUS, p.name, DkaiKit.shownPath(p), p.absolutePath == active) {
                DkaiWorkspace.setProject(c, p); DkaiUi.close(s.root, layer); onPick(p)
            })
        }
        if (!DkaiWorkspace.allFiles() && android.os.Build.VERSION.SDK_INT >= 30) {
            body.addView(DkaiUi.row(c, DkaiIcon.Kind.LOCK, "/storage/emulated/0/DKAIProjects इस्तेमाल करें", "\"सभी फ़ाइलें\" इजाज़त दें (बिना इजाज़त = ऐप का अपना फ़ोल्डर)", false, DkaiTheme.AMBER) {
                try { c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName))) }
                catch (_: Exception) { try { c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {} }
            })
        }
        body.addView(DkaiUi.button(c, "+ Create New Project Folder") {
            val e = DkaiUi.field(c, "प्रोजेक्ट का नाम (जैसे my-app)")
            val box = FrameLayout(c).apply { setPadding(DkaiUi.d(c, 20f), DkaiUi.d(c, 8f), DkaiUi.d(c, 20f), 0); addView(e) }
            android.app.AlertDialog.Builder(c).setTitle("नया प्रोजेक्ट").setView(box).setPositiveButton("बनाएँ") { _, _ ->
                val f = DkaiWorkspace.createProject(c, e.text.toString())
                if (f == null) s.toast("नहीं बना (गलत नाम या पहले से मौजूद)") else { DkaiUi.close(s.root, layer); s.toast("बन गया: ${f.name}"); onPick(f) }
            }.setNegativeButton("रद्द", null).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = DkaiUi.d(c, 6f) })
        layer = DkaiUi.open(s.root, card, DkaiUi.Place.CENTER)
    }

    // ================================================================== GitHub Clone
    fun clone(s: DkaiScreen, done: (File) -> Unit) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.modal(c, "Clone Repository", "GitHub -> ${DkaiKit.PROJECTS_DIR}") { DkaiUi.close(s.root, layer) }
        val url = DkaiUi.field(c, "https://github.com/owner/repo  या  owner/repo")
        val folder = DkaiUi.field(c, "Local Folder Name")
        body.addView(DkaiUi.label(c, "Repo URL")); body.addView(url, LinearLayout.LayoutParams(-1, -2))
        body.addView(DkaiUi.label(c, "Local Folder Name")); body.addView(folder, LinearLayout.LayoutParams(-1, -2))
        body.addView(DkaiUi.label(c, "Presets"))
        body.addView(DkaiUi.chips(c, DkaiKit.CLONE_PRESETS.map { it.label }, -1) { i ->
            val p = DkaiKit.CLONE_PRESETS[i]; url.setText("https://github.com/${p.repo}"); folder.setText(p.folder)
        })
        val status = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY).apply { setPadding(DkaiUi.d(c, 2f), DkaiUi.d(c, 10f), 0, 0) }
        body.addView(status)
        var running = false
        body.addView(DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Cancel", DkaiTheme.FIELD, DkaiTheme.SUB) { if (!running) DkaiUi.close(s.root, layer) },
            DkaiUi.button(c, "Clone Now", kind = DkaiIcon.Kind.DOWNLOAD) {
                if (running) return@button
                val r = DkaiKit.parseRepo(url.text.toString()) ?: run { status.text = "सही GitHub पता लिखें (owner/repo)"; status.setTextColor(DkaiTheme.RED); return@button }
                val name = DkaiKit.folderFor(url.text.toString(), folder.text.toString())
                if (!DkaiCore.validName(name)) { status.text = "फ़ोल्डर का नाम ठीक नहीं"; status.setTextColor(DkaiTheme.RED); return@button }
                running = true; status.setTextColor(DkaiTheme.CYAN); status.text = "Clone हो रहा है: ${r.first}/${r.second} …"
                Thread {
                    val res = try { Result.success(DkaiZip.clone(c, r.first, r.second, name)) } catch (e: Throwable) { Result.failure(e) }
                    c.runOnUiThread {
                        running = false
                        res.onSuccess { (dir, n) -> DkaiUi.close(s.root, layer); s.toast("✓ ${dir.name}: $n फ़ाइलें"); DkaiCopilot.say("⇩ Clone ${r.first}/${r.second} -> ${dir.name} ($n फ़ाइलें)"); done(dir) }
                            .onFailure { e -> status.setTextColor(DkaiTheme.RED); status.text = "नहीं हुआ: ${e.message?.take(140)}" }
                    }
                }.start()
            }))
        layer = DkaiUi.open(s.root, card, DkaiUi.Place.CENTER)
    }

    // ================================================================== Copilot Enhancement Modal (Preview का ✦)
    fun enhance(s: DkaiScreen, project: File, file: File?, done: (DkaiCopilot.Review?) -> Unit) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val rel = file?.let { f -> try { f.relativeTo(project).path } catch (_: Exception) { f.name } } ?: "index.html"
        val (card, body) = DkaiUi.modal(c, "${DkaiKit.COPILOT} Enhancement", "${project.name} / $rel") { DkaiUi.close(s.root, layer) }
        val picks = LinkedHashSet<Int>()
        val icons = listOf(DkaiIcon.Kind.ZAP, DkaiIcon.Kind.BUILDER, DkaiIcon.Kind.MOON, DkaiIcon.Kind.SPLIT, DkaiIcon.Kind.LINK, DkaiIcon.Kind.SPARKLE)
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 3) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 2) {
                val i = r * 2 + k
                val cell = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(DkaiUi.d(c, 12f), DkaiUi.d(c, 12f), DkaiUi.d(c, 10f), DkaiUi.d(c, 12f))
                    isClickable = true; isFocusable = true; contentDescription = DkaiKit.ENHANCE[i]
                    addView(ImageView(c).apply { setImageDrawable(DkaiIcon(icons[i], DkaiTheme.CYAN)) }, LinearLayout.LayoutParams(DkaiUi.d(c, 20f), DkaiUi.d(c, 20f)))
                    addView(DkaiTheme.text(c, DkaiKit.ENHANCE[i], 12.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply { setPadding(0, DkaiUi.d(c, 8f), 0, 0); setLineSpacing(0f, 1.1f) })
                }
                fun paint() { val on = i in picks
                    cell.background = DkaiTheme.press(DkaiTheme.round(c, if (on) DkaiTheme.SEL else DkaiTheme.FIELD, 14f, if (on) DkaiTheme.BLUE else DkaiTheme.BORDER, if (on) 1.5f else 1f)) }
                paint()
                cell.setOnClickListener { if (!picks.remove(i)) picks.add(i); paint() }
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { if (k == 0) rightMargin = DkaiUi.d(c, 8f) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = DkaiUi.d(c, 8f) })
        }
        body.addView(grid)
        val custom = DkaiUi.field(c, "Custom instruction (जैसे: hero section में gradient button जोड़ो)", multi = true)
        body.addView(custom, LinearLayout.LayoutParams(-1, -2))
        val cdn = CheckBox(c).apply { text = "Auto-Download & Link External CDNs / Assets"; isChecked = true; setTextColor(DkaiTheme.SUB); textSize = 13f }
        body.addView(cdn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = DkaiUi.d(c, 6f) })
        body.addView(DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Cancel", DkaiTheme.FIELD, DkaiTheme.SUB) { DkaiUi.close(s.root, layer) },
            DkaiUi.button(c, "Apply to Project", kind = DkaiIcon.Kind.SPARKLE) {
                val cmd = DkaiKit.enhancePrompt(picks.toList(), custom.text.toString(), cdn.isChecked)
                if (cmd.isBlank()) { s.toast("कम-से-कम एक विकल्प चुनें या निर्देश लिखें"); return@button }
                DkaiUi.close(s.root, layer)
                s.toast("${DkaiKit.COPILOT} काम कर रहा है…")
                DkaiCopilot.run(c, project, "Focus file: $rel. $cmd") { r, err -> if (err != null) s.toast("Copilot: $err"); done(r) }
            }))
        layer = DkaiUi.open(s.root, card, DkaiUi.Place.CENTER)
    }
}

// =====================================================================================================================
/** GitHub टैब = [GitHub रिपो सूची | Root Project (Copilot)] */
class GitTab(nav: DkaiNav, private val gh: GithubPanel, private val rp: RootProjectPanel) : DkaiPanel(nav) {
    private lateinit var frame: FrameLayout
    var showingRoot = true; private set

    override fun build(box: LinearLayout) {
        val seg = DkaiUi.segmented(c, listOf("GitHub", "Root Project"), if (showingRoot) 1 else 0) { i -> pick(i == 1) }
        box.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(8f), d(12f), d(4f)) })
        frame = FrameLayout(c)
        box.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        pick(showingRoot)
    }

    private fun pick(root: Boolean) {
        showingRoot = root
        val v: View = if (root) rp.root else gh.root
        if (v.parent !== frame) { (v.parent as? android.view.ViewGroup)?.removeView(v); frame.addView(v, FrameLayout.LayoutParams(-1, -1)) }
        for (i in 0 until frame.childCount) frame.getChildAt(i).let { it.visibility = if (it === v) View.VISIBLE else View.GONE }
        if (root) rp.onShow() else gh.onShow()
    }

    override fun onShow() { if (::frame.isInitialized) pick(showingRoot) }
}

/** Root Project: अभी का प्रोजेक्ट - Run / Stop / Editor / Clone, DKAI Copilot stream, File Overview */
class RootProjectPanel(nav: DkaiNav) : DkaiPanel(nav) {
    private val s get() = nav as DkaiScreen
    private lateinit var projPill: TextView
    private lateinit var branchPill: TextView
    private lateinit var live: TextView
    private lateinit var stream: TextView
    private lateinit var streamScroll: ScrollView
    private lateinit var files: LinearLayout
    private lateinit var streamBox: LinearLayout
    private lateinit var reviewBar: LinearLayout
    private lateinit var reviewTxt: TextView
    private lateinit var prompt: EditText
    private lateinit var sendBtn: ImageView
    private var overview = false
    private val project get() = DkaiWorkspace.activeProject(c)
    private val onChange: () -> Unit = { ui.post { if (!c.isDestroyed) render() } }

    override fun build(box: LinearLayout) {
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), d(6f), d(12f), d(4f)) }
        projPill = DkaiTheme.pill(c, "Project ▾", DkaiTheme.PILL, DkaiTheme.WHITE, 12.5f).apply { minHeight = d(38f); maxWidth = d(170f); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = "प्रोजेक्ट चुनें"; setOnClickListener { DkaiModals.projects(s) { render() } } }
        top.addView(projPill)
        branchPill = DkaiTheme.pill(c, "⎇ local", DkaiTheme.PILL, DkaiTheme.SUB, 12f).apply { minHeight = d(38f); contentDescription = "Branch"; setOnClickListener { branchInfo() } }
        top.addView(branchPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        top.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        live = DkaiUi.status(c, "LIVE", DkaiKit.EMERALD)
        top.addView(live)
        box.addView(top)

        val acts = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), d(4f), d(12f), d(6f)) }
        fun act(t: String, k: DkaiIcon.Kind, col: Int, go: () -> Unit) = acts.addView(DkaiUi.button(c, t, DkaiTheme.FIELD, col, k) { go() }.apply { textSize = 12.5f; setPadding(d(6f), d(8f), d(6f), d(8f)) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d(6f) })
        act("Run", DkaiIcon.Kind.PLAY, DkaiKit.EMERALD) { runProject() }
        act("Stop", DkaiIcon.Kind.STOP, DkaiTheme.RED) { DkaiServer.stop(DkaiKit.PORT); nav.toast("Server रुका"); render() }
        act("Editor", DkaiIcon.Kind.EDITOR, DkaiTheme.CYAN) { project?.let { p -> DkaiWorkspace.entryOf(p)?.let { nav.openInEditor(it) } ?: nav.openFiles(p) } ?: needProject() }
        act("Clone", DkaiIcon.Kind.DOWNLOAD, DkaiTheme.VIOLET) { DkaiModals.clone(s) { render() } }
        box.addView(acts)

        // पूरी चौड़ाई वाला DKAI Copilot बटन
        val cop = DkaiTheme.text(c, "✦  ${DkaiKit.COPILOT}", 15f, DkaiTheme.ON, DkaiTheme.SANS_BOLD).apply {
            gravity = Gravity.CENTER; minHeight = d(48f)
            background = DkaiTheme.press(android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(DkaiTheme.BLUE, DkaiTheme.VIOLET)).apply { cornerRadius = DkaiTheme.dpf(c, 14f) })
            isClickable = true; isFocusable = true; contentDescription = DkaiKit.COPILOT
            setOnClickListener { val p = project ?: run { needProject(); return@setOnClickListener }; DkaiModals.enhance(s, p, DkaiWorkspace.entryOf(p)) { render() } }
        }
        box.addView(cop, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(2f), d(12f), d(8f)) })

        box.addView(DkaiUi.segmented(c, listOf("${DkaiKit.COPILOT} Stream", "File Overview"), 0) { overview = it == 1; render() },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })

        reviewBar = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(d(12f), d(10f), d(12f), d(10f))
            background = DkaiTheme.round(c, DkaiTheme.SEL, 14f, DkaiTheme.BLUE) }
        reviewTxt = DkaiTheme.text(c, "", 12.5f, DkaiTheme.WHITE)
        reviewBar.addView(DkaiTheme.text(c, "Review Changes", 13.5f, DkaiTheme.CYAN, DkaiTheme.SANS_BOLD))
        reviewBar.addView(reviewTxt.apply { setPadding(0, d(4f), 0, d(8f)) })
        reviewBar.addView(DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Undo Task", DkaiTheme.RED_BG, DkaiTheme.RED, DkaiIcon.Kind.UNDO) { DkaiCopilot.undo(); render() },
            DkaiUi.button(c, "Keep all changes", DkaiKit.EMERALD, DkaiTheme.ON, DkaiIcon.Kind.CHECK) { DkaiCopilot.keep(); render() }).apply { setPadding(0, 0, 0, 0) })
        box.addView(reviewBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })

        val mid = FrameLayout(c)
        streamBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = DkaiTheme.round(c, DkaiTheme.CARD, 14f, DkaiTheme.BORDER); setPadding(d(12f), d(10f), d(12f), d(10f)) }
        stream = DkaiTheme.text(c, "", 12f, DkaiTheme.SUB, DkaiTheme.MONO).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true); UiLang.keep(this) }
        streamScroll = ScrollView(c).apply { addView(stream) }
        streamBox.addView(streamScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        streamBox.addView(DkaiUi.button(c, "Scan & Auto-Fix", DkaiTheme.BLUE, DkaiTheme.ON, DkaiIcon.Kind.ZAP) { command(DkaiKit.chipPrompt("Auto-Fix Code")) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8f) })
        mid.addView(streamBox, FrameLayout.LayoutParams(-1, -1))
        files = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(ScrollView(c).apply { addView(files); tag = "ov" }, FrameLayout.LayoutParams(-1, -1))
        box.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), d(6f)) })

        val chips = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(6f)) }
        for (ch in DkaiKit.COPILOT_CHIPS) chips.addView(DkaiTheme.pill(c, ch, DkaiTheme.PILL, DkaiTheme.CYAN, 12f).apply { minHeight = d(34f); setOnClickListener { command(DkaiKit.chipPrompt(ch)) } },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val inRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), 0, d(12f), d(8f)) }
        prompt = DkaiUi.field(c, "Ask ${DkaiKit.COPILOT} to change this project...", multi = true).apply { minLines = 1; maxLines = 4 }
        inRow.addView(prompt, LinearLayout.LayoutParams(0, -2, 1f))
        sendBtn = DkaiUi.iconBtn(c, DkaiIcon.Kind.SEND, "भेजें", DkaiTheme.ON, 46f, DkaiTheme.BLUE) { val t = prompt.text.toString().trim(); if (t.isNotEmpty()) { command(t); prompt.setText("") } }
        inRow.addView(sendBtn, LinearLayout.LayoutParams(d(46f), d(46f)).apply { leftMargin = d(8f) })
        box.addView(inRow)
    }

    override fun onShow() {
        if (!DkaiCopilot.listeners.contains(onChange)) DkaiCopilot.listeners.add(onChange)
        if (!DkaiServer.listeners.contains(onChange)) DkaiServer.listeners.add(onChange)
        render()
    }

    private fun needProject() { nav.toast("पहले प्रोजेक्ट चुनें / बनाएँ"); DkaiModals.projects(s) { render() } }

    private fun command(t: String) {
        val p = project ?: run { needProject(); return }
        overview = false
        s.hideKeyboard()
        DkaiCopilot.run(c, p, t) { _, err -> if (err != null) nav.toast("Copilot: ${err.take(120)}"); render() }
        render()
    }

    private fun runProject() {
        val p = project ?: run { needProject(); return }
        val err = DkaiServer.start(p)
        if (err != null) { nav.toast(err); return }
        DkaiCopilot.say("▶ Server :${DkaiKit.PORT} -> ${p.name}")
        val entry = DkaiWorkspace.entryOf(p)
        if (entry != null) nav.openPreview(entry) else nav.toast("index.html नहीं मिला - Server चालू: ${DkaiKit.localUrl()}")
    }

    private fun branchInfo() {
        val p = project ?: return
        val m = DkaiWorkspace.meta(p)
        android.app.AlertDialog.Builder(c).setTitle("Branch")
            .setMessage(if (m != null) "${m.repo}\nbranch: ${m.branch}\n\nबदलाव Files → Push + PR से नई branch में जाते हैं (main पर सीधे नहीं)." else "लोकल प्रोजेक्ट (Git branch नहीं)\n${DkaiKit.shownPath(p)}")
            .setPositiveButton("ठीक", null).show()
    }

    fun render() {
        if (!::projPill.isInitialized) return
        val p = project
        projPill.text = (p?.name ?: "Select Project") + "  ▾"
        branchPill.text = "⎇ " + (p?.let { DkaiWorkspace.meta(it)?.branch } ?: "local")
        val on = DkaiServer.running()
        live.text = if (on) "● LIVE" else "● OFFLINE"; live.setTextColor(if (on) DkaiKit.EMERALD else DkaiTheme.GRAY)
        stream.text = if (DkaiCopilot.log.isEmpty()) "${DkaiKit.COPILOT} तैयार।\nनीचे काम लिखें या चिप दबाएँ - AI पूरे प्रोजेक्ट की फ़ाइलें पढ़कर सीधे बदलाव करेगा, फिर आप Undo / Keep चुनें।"
            else DkaiCopilot.log.joinToString("\n") + if (DkaiCopilot.busy) "\n…" else ""
        streamScroll.post { streamScroll.fullScroll(View.FOCUS_DOWN) }
        val r = DkaiCopilot.review
        reviewBar.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null) reviewTxt.text = DkaiCopilot.reviewText(r) + (r.summary.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
        sendBtn.alpha = if (DkaiCopilot.busy) 0.4f else 1f
        streamBox.visibility = if (overview) View.GONE else View.VISIBLE
        (files.parent as View).visibility = if (overview) View.VISIBLE else View.GONE
        if (overview) renderFiles(p)
    }

    private fun renderFiles(p: File?) {
        files.removeAllViews()
        if (p == null) { files.addView(empty("कोई प्रोजेक्ट नहीं चुना", "प्रोजेक्ट चुनें") { needProject() }); return }
        val all = p.walkTopDown().maxDepth(5).onEnter { it.name != ".git" && it.name != "node_modules" }.filter { it.isFile }.take(300).toList()
        files.addView(DkaiTheme.text(c, "${all.size} फ़ाइलें • ${sizeOf(all.sumOf { it.length() })}", 12f, DkaiTheme.GRAY).apply { setPadding(d(4f), d(4f), 0, d(8f)) })
        for (f in all.sortedBy { it.relativeTo(p).path }) {
            val rel = f.relativeTo(p).path
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(44f); setPadding(d(10f), d(4f), d(10f), d(4f))
                background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 10f, DkaiTheme.BORDER)); isClickable = true
                setOnClickListener { if (DkaiKit.opensInEditor(f.name)) nav.openInEditor(f) else nav.openFiles(f.parentFile ?: p) }
            }
            row.addView(FilesPanel.badge(c, f.name, false), LinearLayout.LayoutParams(d(40f), -2))
            row.addView(DkaiTheme.text(c, rel, 12.5f, DkaiTheme.WHITE, DkaiTheme.MONO).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(d(8f), 0, d(8f), 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(DkaiTheme.text(c, sizeOf(f.length()), 11f, DkaiTheme.GRAY))
            files.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
        }
    }
}
