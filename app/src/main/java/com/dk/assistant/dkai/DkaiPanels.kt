package com.dk.assistant.dkai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.dk.assistant.GitHub
import com.dk.assistant.SettingsActivity
import com.dk.assistant.UiLang
import com.dk.assistant.dkai.DkaiTheme.dp
import java.io.File

/** पैनल को स्क्रीन से क्या चाहिए (एक पैनल से दूसरे पर जाना) */
interface DkaiNav {
    val act: Activity
    fun openInEditor(f: File)
    fun openPreview(f: File)
    fun openFiles(dir: File)
    fun runInTerminal(cmd: String, cwd: File)
    fun toast(s: String)
}

/** सब पैनल की नींव: ऊपर शीर्षक-पट्टी, नीचे सामग्री */
abstract class DkaiPanel(protected val nav: DkaiNav) {
    protected val c: Activity get() = nav.act
    protected val ui = Handler(Looper.getMainLooper())
    val root: LinearLayout by lazy { LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }.also { build(it) } }
    protected abstract fun build(box: LinearLayout)
    /** टैब खुला */
    open fun onShow() {}
    /** back दबाया: true = पैनल ने संभाला */
    open fun onBack(): Boolean = false

    protected fun d(v: Float) = dp(c, v)

    protected fun bar(title: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(16f), d(4f), d(8f), d(4f)) }
        val t = DkaiTheme.text(c, title, 16f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return row to t
    }

    protected fun iconBtn(kind: DkaiIcon.Kind, desc: String, tint: Int = DkaiTheme.WHITE, onTap: () -> Unit): ImageView = ImageView(c).apply {
        setImageDrawable(DkaiIcon(kind, tint)); scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = d(11f); setPadding(p, p, p, p)
        background = DkaiTheme.press(DkaiTheme.oval(0x00000000), DkaiTheme.oval(0xFFFFFFFF.toInt()))
        contentDescription = desc; isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(DkaiTheme.touchMin(c), DkaiTheme.touchMin(c))
    }

    protected fun card(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER)
        setPadding(d(14f), d(12f), d(14f), d(12f))
    }

    protected fun empty(msg: String, btn: String? = null, onBtn: (() -> Unit)? = null): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(d(24f), d(40f), d(24f), d(24f))
        addView(DkaiTheme.text(c, msg, 14f, DkaiTheme.GRAY).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) })
        if (btn != null) addView(DkaiTheme.pill(c, btn, DkaiTheme.BLUE).apply { setOnClickListener { onBtn?.invoke() }; minHeight = d(44f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(16f) })
    }

    protected fun ask(title: String, hint: String, start: String = "", ok: (String) -> Unit) {
        val e = EditText(c).apply { setText(start); this.hint = hint; setSingleLine(); setSelection(text.length) }
        val box = FrameLayout(c).apply { setPadding(d(20f), d(8f), d(20f), 0); addView(e) }
        AlertDialog.Builder(c).setTitle(title).setView(box)
            .setPositiveButton("ठीक") { _, _ -> ok(e.text.toString()) }.setNegativeButton("रद्द", null).show()
    }

    protected fun bg(work: () -> Unit) = Thread { try { work() } catch (_: Throwable) {} }.start()
    protected fun main(r: () -> Unit) = ui.post { if (!c.isFinishing && !c.isDestroyed) r() }

    protected fun sizeOf(n: Long): String = when {
        n < 1024 -> "$n B"; n < 1024 * 1024 -> "${n / 1024} KB"; else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576f)
    }
}

// =====================================================================================================================
/** Files: तीनों जड़ के फ़ोल्डर - खोलो, नई फ़ाइल/फ़ोल्डर, नाम बदलो, हटाओ, GitHub रिपो में बदलाव भेजो */
class FilesPanel(nav: DkaiNav) : DkaiPanel(nav) {
    private lateinit var list: LinearLayout
    private lateinit var path: TextView
    private lateinit var roots: LinearLayout
    private lateinit var repoBar: LinearLayout
    private lateinit var repoTxt: TextView
    var cwd: File? = null; private set
    private lateinit var projPill: TextView
    private lateinit var searchBox: EditText
    private var sort = DkaiKit.Sort.NAME
    private var query = ""

    companion object {
        /** रंगीन प्रकार-निशान: HTML / CSS / JS / MD / TXT / ICO … (फ़ोल्डर = पीला फ़ोल्डर) */
        fun badge(c: android.content.Context, name: String, dir: Boolean): View {
            val k = DkaiKit.kindOf(name, dir)
            if (dir) return ImageView(c).apply {
                setImageDrawable(DkaiIcon(DkaiIcon.Kind.FILES, if (k == DkaiKit.Kind.CONFIG) DkaiTheme.VIOLET else DkaiTheme.AMBER))
                scaleType = ImageView.ScaleType.CENTER_INSIDE; minimumHeight = dp(c, 22f)
            }
            val ext = name.substringAfterLast('.', "").uppercase(java.util.Locale.ROOT).take(4).ifEmpty { "FILE" }
            val col = when (k) {
                DkaiKit.Kind.WEB -> 0xFFF97316.toInt(); DkaiKit.Kind.STYLE -> DkaiTheme.BLUE; DkaiKit.Kind.SCRIPT -> 0xFFEAB308.toInt()
                DkaiKit.Kind.CODE -> DkaiTheme.VIOLET; DkaiKit.Kind.DATA -> DkaiKit.EMERALD; DkaiKit.Kind.IMAGE -> DkaiTheme.PINK
                DkaiKit.Kind.AUDIO -> DkaiTheme.MINT; DkaiKit.Kind.DOC -> DkaiTheme.GRAY; else -> DkaiTheme.GRAY
            }
            return DkaiTheme.text(c, ext, 9f, col, DkaiTheme.SANS_BOLD).apply {
                gravity = Gravity.CENTER; setPadding(dp(c, 3f), dp(c, 3f), dp(c, 3f), dp(c, 3f))
                background = DkaiTheme.round(c, (col and 0x00FFFFFF) or 0x26000000, 6f, (col and 0x00FFFFFF) or 0x66000000)
            }
        }
    }

    override fun build(box: LinearLayout) {
        val (row, _) = bar("Files")
        row.addView(iconBtn(DkaiIcon.Kind.SEARCH, "खोजें") { searchBox.visibility = if (searchBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (searchBox.visibility == View.VISIBLE) searchBox.requestFocus() else { searchBox.setText(""); query = ""; refresh() } })
        row.addView(iconBtn(DkaiIcon.Kind.SORT, "क्रम बदलें") { sort = DkaiKit.nextSort(sort); nav.toast("क्रम: ${sort.label}"); refresh() })
        row.addView(iconBtn(DkaiIcon.Kind.FILE_PLUS, "नई फ़ाइल") { newItem(false) })
        row.addView(iconBtn(DkaiIcon.Kind.FOLDER_PLUS, "नया फ़ोल्डर") { newItem(true) })
        row.addView(iconBtn(DkaiIcon.Kind.REFRESH, "ताज़ा करें") { refresh() })
        box.addView(row)
        // प्रोजेक्ट चुनने वाली पट्टी (2048 ▾ + पूरा रास्ता)
        projPill = DkaiTheme.text(c, "", 13f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply {
            minHeight = d(44f); gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), d(6f), d(12f), d(6f)); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.FIELD, 12f, DkaiTheme.BORDER)); isClickable = true; contentDescription = "प्रोजेक्ट चुनें"
            setOnClickListener { (nav as? DkaiScreen)?.let { s -> DkaiModals.projects(s) { p -> open(p) } } }
        }
        box.addView(projPill, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })
        searchBox = DkaiUi.field(c, "फ़ाइल खोजें (इस प्रोजेक्ट में)…").apply { visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun afterTextChanged(e: Editable?) { query = e?.toString().orEmpty().trim(); refresh() }
            }) }
        box.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })
        roots = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(6f)) }
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(roots) })
        val pathRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4f), 0, d(16f), 0) }
        pathRow.addView(iconBtn(DkaiIcon.Kind.BACK, "ऊपर का फ़ोल्डर", DkaiTheme.GRAY) { up() })
        path = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY, DkaiTheme.MONO).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        pathRow.addView(path, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(pathRow)
        repoBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = DkaiTheme.round(c, DkaiTheme.SEL, 14f, DkaiTheme.BORDER); setPadding(d(12f), d(4f), d(4f), d(4f))
        }
        repoBar.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.GITHUB, DkaiTheme.CYAN)) }, LinearLayout.LayoutParams(d(18f), d(18f)))
        repoTxt = DkaiTheme.text(c, "", 12f, DkaiTheme.WHITE).apply { setPadding(d(8f), 0, d(8f), 0); maxLines = 2 }
        repoBar.addView(repoTxt, LinearLayout.LayoutParams(0, -2, 1f))
        repoBar.addView(DkaiTheme.pill(c, "Push + PR", DkaiTheme.BLUE).apply { minHeight = d(40f); setOnClickListener { pushRepo() } })
        box.addView(repoBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(2f), d(12f), d(6f)) })
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(12f), d(16f)) }
        box.addView(ScrollView(c).apply { isFillViewport = true; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun onShow() { if (cwd == null) cwd = DkaiWorkspace.workspace(c); refresh() }

    override fun onBack(): Boolean {
        val d = cwd ?: return false
        val r = DkaiWorkspace.rootOf(c, d) ?: return false
        if (d.canonicalPath == r.dir.canonicalPath) return false
        up(); return true
    }

    fun open(dir: File) { cwd = dir; if (::list.isInitialized) refresh() }

    private fun up() {
        val d = cwd ?: return
        val r = DkaiWorkspace.rootOf(c, d) ?: return
        if (d.canonicalPath == r.dir.canonicalPath) return
        cwd = d.parentFile; refresh()
    }

    fun refresh() {
        val dir = cwd ?: DkaiWorkspace.workspace(c).also { cwd = it }
        val root = DkaiWorkspace.rootOf(c, dir) ?: DkaiWorkspace.roots(c).first().also { cwd = it.dir }
        roots.removeAllViews()
        for (r in DkaiWorkspace.roots(c)) {
            val on = r.dir.canonicalPath == root.dir.canonicalPath
            roots.addView(DkaiTheme.pill(c, r.name, if (on) DkaiTheme.BLUE else DkaiTheme.PILL, if (on) DkaiTheme.WHITE else DkaiTheme.GRAY, 12f).apply {
                minHeight = d(40f); setOnClickListener { cwd = r.dir; refresh() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8f) })
        }
        val cur = cwd!!
        val rel = cur.canonicalPath.removePrefix(root.dir.canonicalPath).trimStart('/')
        path.text = root.name + if (rel.isEmpty()) "" else " / " + rel.replace("/", " / ")
        val proj = DkaiWorkspace.activeProject(c)
        projPill.text = (proj?.name ?: "प्रोजेक्ट चुनें") + "  ▾\n" + DkaiKit.shownPath(cur)
        // GitHub से लाया रिपो?
        val repo = DkaiWorkspace.repoDirOf(c, cur)
        repoBar.visibility = if (repo != null) View.VISIBLE else View.GONE
        if (repo != null) {
            val m = DkaiWorkspace.meta(repo)
            repoTxt.text = "${m?.repo} • ${m?.branch}\nबदलाव गिन रहा हूँ…"
            bg { val n = DkaiWorkspace.pending(repo).size; main { repoTxt.text = "${m?.repo} • ${m?.branch}\n" + if (n == 0) "कोई बदलाव नहीं" else "$n फ़ाइल बदली" } }
        }
        list.removeAllViews()
        if (query.isNotEmpty()) {
            val hits = cur.walkTopDown().maxDepth(8).onEnter { it.name != ".git" && it.name != "node_modules" }
                .filter { it != cur && it.name.contains(query, true) }.take(200).toList()
            if (hits.isEmpty()) { list.addView(empty("\"$query\" से कोई फ़ाइल नहीं मिली")); return }
            for (f in hits) list.addView(row(f, f.relativeTo(cur).path))
            return
        }
        val raw = DkaiWorkspace.list(cur)
        val byName = raw.associateBy { it.name }
        val items = DkaiKit.sorted(raw.map { DkaiKit.Entry(it.name, it.isDirectory, it.lastModified(), if (it.isFile) it.length() else 0L) }, sort).mapNotNull { byName[it.name] }
        if (items.isEmpty()) { list.addView(empty("यह फ़ोल्डर खाली है।\nऊपर + से नई फ़ाइल बनाइए।", "नई फ़ाइल") { newItem(false) }); return }
        for (f in items.take(400)) list.addView(row(f))
        if (items.size > 400) list.addView(DkaiTheme.text(c, "+${items.size - 400} और", 12f, DkaiTheme.GRAY).apply { setPadding(d(8f), d(8f), 0, 0) })
    }

    private fun row(f: File, shown: String = f.name): View {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(52f)
            setPadding(d(12f), d(6f), d(4f), d(6f))
            background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 12f, DkaiTheme.BORDER))
            isClickable = true; isFocusable = true
        }
        r.addView(badge(c, f.name, f.isDirectory), LinearLayout.LayoutParams(d(36f), -2))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0) }
        col.addView(DkaiTheme.text(c, shown, 14f, DkaiTheme.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val sub = if (f.isDirectory) "${f.list()?.size ?: 0} चीज़ें" else sizeOf(f.length())
        col.addView(DkaiTheme.text(c, sub, 11f, DkaiTheme.GRAY).apply { setPadding(0, d(3f), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(iconBtn(DkaiIcon.Kind.MENU, "${f.name} के विकल्प", DkaiTheme.GRAY) { options(f) })
        r.setOnClickListener { if (f.isDirectory) { cwd = f; refresh() } else nav.openInEditor(f) }
        r.setOnLongClickListener { options(f); true }
        r.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8f) }
        return r
    }

    private fun options(f: File) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (f.isFile) acts.add("Editor में खोलें" to { nav.openInEditor(f) })
        if (f.isFile && DkaiCore.isHtml(f.name)) acts.add("Preview में चलाएँ" to { nav.openPreview(f) })
        if (f.isFile && f.name.endsWith(".sh")) acts.add("Terminal में चलाएँ" to { nav.runInTerminal("sh '${f.name}'", f.parentFile ?: f) })
        if (f.isDirectory) acts.add("Open Folder" to { cwd = f; refresh() })
        if (f.isDirectory) acts.add("यहाँ Terminal खोलें" to { nav.runInTerminal("ls -la", f) })
        if (f.isDirectory && f.parentFile?.canonicalPath == DkaiWorkspace.projectsRoot(c).canonicalPath) acts.add("Active Project बनाएँ" to { DkaiWorkspace.setProject(c, f); nav.toast("प्रोजेक्ट: ${f.name}"); refresh() })
        acts.add("नाम बदलें" to {
            ask("नाम बदलें", "नया नाम", f.name) { n ->
                if (DkaiWorkspace.rename(f, n) == null) nav.toast("नाम नहीं बदला (गलत नाम या पहले से मौजूद)") else refresh()
            }
        })
        acts.add("Copy Absolute Path" to { DkaiUi.copy(c, "path", DkaiKit.shownPath(f)) })
        acts.add("Delete (हटाएँ)" to {
            AlertDialog.Builder(c).setTitle("\"${f.name}\" हटाएँ?").setMessage(if (f.isDirectory) "फ़ोल्डर और उसके अंदर का सब कुछ हमेशा के लिए हट जाएगा।" else "यह फ़ाइल हमेशा के लिए हट जाएगी।")
                .setPositiveButton("हटाएँ") { _, _ -> if (DkaiWorkspace.delete(f)) { nav.toast("हटा दिया"); refresh() } else nav.toast("हटा नहीं पाया") }
                .setNegativeButton("रद्द", null).show()
        })
        // Delete लाल रंग में (M5 context menu)
        val names = acts.map { (t, _) -> if (t.startsWith("Delete")) android.text.SpannableString(t).apply { setSpan(android.text.style.ForegroundColorSpan(DkaiTheme.RED), 0, t.length, 0) } else t }
        AlertDialog.Builder(c).setTitle(f.name).setItems(names.toTypedArray<CharSequence>()) { _, i -> acts[i].second() }.show()
    }

    private fun newItem(folder: Boolean) {
        val dir = cwd ?: return
        ask(if (folder) "नया फ़ोल्डर" else "नई फ़ाइल", if (folder) "फ़ोल्डर का नाम" else "जैसे index.html") { n ->
            val f = DkaiWorkspace.create(dir, n, folder)
            if (f == null) nav.toast("नहीं बना (गलत नाम या पहले से मौजूद)")
            else { refresh(); if (!folder) nav.openInEditor(f) }
        }
    }

    private fun pushRepo() {
        val repo = DkaiWorkspace.repoDirOf(c, cwd ?: return) ?: return
        val gh = GitHub.fromPrefs(c) ?: run { nav.toast("पहले GitHub token जोड़ें (सेटिंग्स → कोडिंग एजेंट)"); return }
        bg {
            val ch = DkaiWorkspace.pending(repo)
            main {
                if (ch.isEmpty()) { nav.toast("कोई बदलाव नहीं - भेजने को कुछ नहीं"); return@main }
                val lines = ch.entries.take(30).joinToString("\n") { (p, v) -> (if (v == null) "− " else "± ") + p } + if (ch.size > 30) "\n…+${ch.size - 30}" else ""
                val e = EditText(c).apply { setText("DKAI: ${ch.size} फ़ाइल बदली"); setSingleLine() }
                val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20f), d(8f), d(20f), 0)
                    addView(TextView(c).apply { text = lines; typeface = Typeface.MONOSPACE; textSize = 12f })
                    addView(e) }
                AlertDialog.Builder(c).setTitle("नई branch + PR बनाएँ?").setView(ScrollView(c).apply { addView(box) })
                    .setPositiveButton("भेजें") { _, _ ->
                        nav.toast("GitHub पर भेज रहा हूँ…")
                        bg {
                            val r = try { Result.success(DkaiWorkspace.push(c, gh, repo, e.text.toString().ifBlank { "DKAI changes" })) } catch (x: Throwable) { Result.failure(x) }
                            main {
                                r.onSuccess { p ->
                                    refresh()
                                    AlertDialog.Builder(c).setTitle("✓ PR बन गया").setMessage("${p.files} फ़ाइल • branch ${p.branch}\n${p.prUrl}")
                                        .setPositiveButton("PR खोलें") { _, _ -> try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.prUrl))) } catch (_: Exception) {} }
                                        .setNegativeButton("ठीक", null).show()
                                }.onFailure { x -> nav.toast("नहीं भेज पाया: ${x.message?.take(120)}") }
                            }
                        }
                    }.setNegativeButton("रद्द", null).show()
            }
        }
    }
}

// =====================================================================================================================
/** Editor: monospace, सेव, लाइन/अक्षर गिनती, wrap, HTML = Preview, .sh = Terminal */
class EditorPanel(nav: DkaiNav) : DkaiPanel(nav) {
    var file: File? = null; private set
    private lateinit var edit: EditText
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var emptyBox: View
    private lateinit var previewBtn: View
    private lateinit var runBtn: View
    private var saved = ""
    private var wrap = false
    private lateinit var gutter: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var review: LinearLayout
    private lateinit var reviewTxt: TextView
    private var tabs = listOf<String>()
    private val hl = Runnable { paintSyntax() }
    private val onCopilot: () -> Unit = { ui.post { if (!c.isDestroyed) paintReview() } }
    val dirty get() = ::edit.isInitialized && file != null && edit.text.toString() != saved

    override fun build(box: LinearLayout) {
        val (row, t) = bar("Editor"); title = t
        previewBtn = iconBtn(DkaiIcon.Kind.PREVIEW, "Preview में चलाएँ") { file?.let { save(); nav.openPreview(it) } }
        runBtn = iconBtn(DkaiIcon.Kind.PLAY, "Terminal में चलाएँ") { file?.let { save(); nav.runInTerminal("sh '${it.name}'", it.parentFile ?: it) } }
        row.addView(previewBtn); row.addView(runBtn)
        row.addView(iconBtn(DkaiIcon.Kind.WRAP, "लाइन लपेटें") { wrap = !wrap; applyWrap() })
        row.addView(iconBtn(DkaiIcon.Kind.SAVE, "सेव करें", DkaiTheme.CYAN) { if (save()) nav.toast("सेव हो गया") })
        box.addView(row)
        // खुली फ़ाइलों के टैब
        tabRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(4f)) }
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })
        // Preview / Save / AI Edit
        val acts = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(6f)) }
        acts.addView(DkaiTheme.pill(c, "▶ Preview", DkaiTheme.PILL, DkaiKit.EMERALD, 12f).apply { minHeight = d(38f)
            setOnClickListener { file?.let { save(); if (DkaiCore.isHtml(it.name)) nav.openPreview(it) else nav.toast("Preview सिर्फ़ HTML के लिए") } } })
        acts.addView(DkaiTheme.pill(c, "Save", DkaiTheme.PILL, DkaiTheme.CYAN, 12f).apply { minHeight = d(38f); setOnClickListener { if (save()) nav.toast("सेव हो गया") } },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(6f) })
        acts.addView(DkaiTheme.pill(c, "✦ AI Edit", DkaiTheme.BLUE, DkaiTheme.ON, 12f).apply { minHeight = d(38f); setOnClickListener { aiEdit() } },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(6f) })
        box.addView(acts)
        // Review Changes पट्टी (Copilot के बदलाव)
        review = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(d(12f), d(8f), d(12f), d(8f))
            background = DkaiTheme.round(c, DkaiTheme.SEL, 12f, DkaiTheme.BLUE) }
        reviewTxt = DkaiTheme.text(c, "", 12f, DkaiTheme.WHITE).apply { maxLines = 3 }
        review.addView(reviewTxt)
        review.addView(LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, d(6f), 0, 0)
            addView(DkaiTheme.pill(c, "Undo Task", DkaiTheme.RED_BG, DkaiTheme.RED, 12f).apply { minHeight = d(38f); setOnClickListener { DkaiCopilot.undo(); reload(); paintReview() } })
            addView(DkaiTheme.pill(c, "Keep all changes", DkaiKit.EMERALD, DkaiTheme.ON, 12f).apply { minHeight = d(38f); setOnClickListener { DkaiCopilot.keep(); paintReview() } },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) }) })
        box.addView(review, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })
        val frame = FrameLayout(c)
        val codeRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; background = DkaiTheme.round(c, 0xFF0F1626.toInt(), 14f, DkaiTheme.BORDER) }
        gutter = TextView(c).apply { typeface = DkaiTheme.MONO; textSize = 13f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.END
            setPadding(d(8f), d(10f), d(6f), d(10f)); isVerticalScrollBarEnabled = false; setLineSpacing(0f, 1f) }
        codeRow.addView(gutter, LinearLayout.LayoutParams(-2, -1))
        edit = EditText(c).apply {
            typeface = DkaiTheme.MONO; textSize = 13f; setTextColor(0xFFE2E8F0.toInt()); setHintTextColor(DkaiTheme.GRAY)
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(d(6f), d(10f), d(12f), d(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isVerticalScrollBarEnabled = true; setHorizontallyScrolling(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable?) { stat(); paintGutter(); ui.removeCallbacks(hl); ui.postDelayed(hl, 450) }
            })
            setOnScrollChangeListener { _, _, y, _, _ -> gutter.scrollTo(0, y) }
        }
        UiLang.keep(edit)
        codeRow.addView(edit, LinearLayout.LayoutParams(0, -1, 1f))
        frame.addView(codeRow, FrameLayout.LayoutParams(-1, -1))
        emptyBox = empty("कोई फ़ाइल खुली नहीं।\nFiles से फ़ाइल चुनिए या नई बनाइए।", "Files खोलें") { nav.openFiles(DkaiWorkspace.workspace(c)) }
            .apply { setBackgroundColor(DkaiTheme.BG) }
        frame.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))
        box.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), 0) })
        // चिन्ह-पट्टी: TAB ( ) { } [ ] < > / = ;
        val sym = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), d(6f), d(12f), 0) }
        for (k in DkaiKit.SYMBOLS) sym.addView(DkaiTheme.pill(c, k, DkaiTheme.PILL, DkaiTheme.WHITE, 13f).apply {
            typeface = DkaiTheme.MONO; minHeight = d(38f); minWidth = d(40f); gravity = Gravity.CENTER; contentDescription = "डालें $k"
            setOnClickListener { insert(DkaiKit.symbolText(k)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(sym) })
        status = DkaiTheme.text(c, "", 11f, DkaiTheme.GRAY, DkaiTheme.MONO).apply { setPadding(d(16f), d(6f), d(16f), d(6f)) }
        box.addView(status)
        show()
    }

    override fun onShow() {
        if (!DkaiCopilot.listeners.contains(onCopilot)) DkaiCopilot.listeners.add(onCopilot)
        paintReview()
    }

    private fun insert(t: String) {
        if (file == null) return
        val a = edit.selectionStart.coerceAtLeast(0); val b = edit.selectionEnd.coerceAtLeast(0)
        edit.text.replace(minOf(a, b), maxOf(a, b), t)
        edit.requestFocus()
    }

    private fun paintGutter() {
        if (!::gutter.isInitialized) return
        gutter.visibility = if (wrap || file == null) View.GONE else View.VISIBLE
        if (gutter.visibility == View.VISIBLE) { val g = DkaiKit.gutter(edit.text.toString()); if (gutter.text.toString() != g) gutter.text = g }
    }

    /** रंगीन code (keyword / string / comment / tag / number) - टाइप रुकने पर */
    private fun paintSyntax() {
        val f = file ?: return
        val e = edit.text
        for (sp in e.getSpans(0, e.length, android.text.style.ForegroundColorSpan::class.java)) e.removeSpan(sp)
        for (sp in DkaiKit.highlight(e.toString(), f.name)) {
            if (sp.end > e.length) continue
            val col = when (sp.tok) {
                DkaiKit.Tok.KEYWORD -> 0xFFC084FC.toInt(); DkaiKit.Tok.STRING -> 0xFF34D399.toInt(); DkaiKit.Tok.COMMENT -> 0xFF64748B.toInt()
                DkaiKit.Tok.TAG -> 0xFF60A5FA.toInt(); DkaiKit.Tok.NUMBER -> 0xFFFBBF24.toInt()
            }
            e.setSpan(android.text.style.ForegroundColorSpan(col), sp.start, sp.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun paintTabs() {
        if (!::tabRow.isInitialized) return
        tabRow.removeAllViews()
        val cur = file?.absolutePath
        for (p in tabs) {
            val f = File(p); val on = p == cur
            tabRow.addView(DkaiTheme.pill(c, f.name + if (on) "  ×" else "", if (on) DkaiTheme.SEL else DkaiTheme.PILL, if (on) DkaiTheme.WHITE else DkaiTheme.GRAY, 12f).apply {
                minHeight = d(36f); contentDescription = f.name
                setOnClickListener {
                    if (!on) confirmLeave { open(f) }
                    else confirmLeave { tabs = tabs - p; val n = tabs.lastOrNull(); if (n != null) open(File(n)) else { file = null; show(); paintTabs() } }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        }
    }

    private fun paintReview() {
        if (!::review.isInitialized) return
        val r = DkaiCopilot.review
        review.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null) reviewTxt.text = "Review Changes • " + DkaiCopilot.reviewText(r)
        reload()
    }

    /** Copilot ने यही फ़ाइल बदली हो तो दोबारा पढ़ो (बिना सेव बदलाव न हों तब) */
    private fun reload() {
        val f = file ?: return
        if (dirty) return
        val (txt, _) = DkaiWorkspace.readText(f)
        if (txt != null && txt != saved) { saved = txt; edit.setText(txt); show() }
    }

    private fun aiEdit() {
        val f = file ?: run { nav.toast("पहले फ़ाइल खोलें"); return }
        ask("✦ AI Edit • ${f.name}", "क्या बदलना है? (जैसे: dark mode बटन जोड़ो)") { t ->
            if (t.isBlank()) return@ask
            save()
            val proj = DkaiWorkspace.repoDirOf(c, f) ?: DkaiWorkspace.activeProject(c)?.takeIf { DkaiCore.inside(it, f) } ?: f.parentFile ?: return@ask
            val rel = try { f.relativeTo(proj).path } catch (_: Exception) { f.name }
            nav.toast("${DkaiKit.COPILOT} बदल रहा है…")
            DkaiCopilot.run(c, proj, "Edit the file $rel: $t") { r, err ->
                if (err != null) nav.toast("AI Edit: ${err.take(120)}") else { nav.toast("✓ ${r?.changes?.size ?: 0} फ़ाइल बदली - Undo / Keep चुनें"); reload(); paintReview() }
            }
        }
    }

    fun open(f: File): Boolean {
        val (txt, why) = DkaiWorkspace.readText(f)
        if (txt == null) { nav.toast(why ?: "नहीं खुली"); return false }
        file = f; saved = txt
        root            // पहली बार: पैनल अभी बनाओ
        edit.setText(txt); edit.setSelection(0); show()
        tabs = DkaiKit.openTabs(tabs, f.absolutePath, 6); paintTabs()
        return true
    }

    /** दूसरी फ़ाइल खोलने से पहले: बिना सेव बदलाव? */
    fun confirmLeave(then: () -> Unit) {
        if (!dirty) { then(); return }
        AlertDialog.Builder(c).setTitle("${file?.name} में बदलाव सेव नहीं हुए").setMessage("सेव करें?")
            .setPositiveButton("सेव करें") { _, _ -> save(); then() }
            .setNegativeButton("छोड़ें") { _, _ -> saved = edit.text.toString(); then() }
            .setNeutralButton("रद्द", null).show()
    }

    fun save(): Boolean {
        val f = file ?: return false
        return try { val s = edit.text.toString(); f.writeText(s); saved = s; stat(); true } catch (e: Exception) { nav.toast("सेव नहीं हुआ: ${e.message}"); false }
    }

    private fun applyWrap() { edit.setHorizontallyScrolling(!wrap); paintGutter(); nav.toast(if (wrap) "लाइन लपेटना चालू" else "लाइन लपेटना बंद") }

    private fun show() {
        val f = file
        emptyBox.visibility = if (f == null) View.VISIBLE else View.GONE
        title.text = f?.name ?: "Editor"
        previewBtn.visibility = if (f != null && DkaiCore.isHtml(f.name)) View.VISIBLE else View.GONE
        runBtn.visibility = if (f != null && f.name.endsWith(".sh")) View.VISIBLE else View.GONE
        stat(); paintGutter(); ui.removeCallbacks(hl); ui.postDelayed(hl, 120)
    }

    private fun stat() {
        if (!::status.isInitialized) return
        val f = file ?: run { status.text = ""; return }
        val s = edit.text
        val lines = s.count { it == '\n' } + 1
        val pos = edit.selectionStart.coerceAtLeast(0)
        val ln = s.subSequence(0, pos.coerceAtMost(s.length)).count { it == '\n' } + 1
        title.text = f.name + if (dirty) " •" else ""
        status.text = "Ln $ln • $lines lines • ${s.length} chars • ${if (dirty) "बिना सेव" else "सेव"}"
    }
}

// =====================================================================================================================
/**
 * Terminal: असली shell (ऐप के अपने फ़ोल्डर में) - 13 नवंबर 2026 (मालिक): और एडवांस / पावरफुल.
 * output लाइव, ■ रोकें (Ctrl+C), चलती कमांड को जवाब (stdin) भेजें, Tab से नाम पूरा, ↑ ↓ इतिहास (फ़ोन में सेव),
 * जल्दी वाले बटन, output copy, alias / export / wget / curl / zip / unzip / tree / sysinfo ([DkaiShell]).
 * DK लाइव की चलाई कमांड भी यहीं दिखती है (वही सत्र).
 */
class TerminalPanel(nav: DkaiNav) : DkaiPanel(nav) {
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var promptTv: TextView
    private lateinit var runBtn: ImageView
    private val buf = StringBuilder()
    private var hIdx = 0
    @Volatile private var proc: Process? = null
    @Volatile private var busy = false
    private var stdin: java.io.Writer? = null
    val cwd: File? get() = DkaiShell.cwd
    private val home get() = DkaiWorkspace.workspace(c)
    private val fromLive = object : (String) -> Unit {
        override fun invoke(s: String) {
            if (c.isDestroyed) { DkaiShell.listeners.remove(this); return }       // पुरानी स्क्रीन - पकड़कर न रखें
            ui.post { if (::out.isInitialized) { print(s); prompt() } }
        }
    }

    override fun build(box: LinearLayout) {
        DkaiShell.load(c)
        hIdx = DkaiShell.history.size
        val (row, _) = bar("Terminal")
        row.addView(iconBtn(DkaiIcon.Kind.UP, "पिछली कमांड", DkaiTheme.GRAY) { hist(-1) })
        row.addView(iconBtn(DkaiIcon.Kind.DOWN, "अगली कमांड", DkaiTheme.GRAY) { hist(1) })
        row.addView(iconBtn(DkaiIcon.Kind.COPY, "output copy करें", DkaiTheme.GRAY) { copyOut() })
        row.addView(iconBtn(DkaiIcon.Kind.TRASH, "साफ़ करें", DkaiTheme.GRAY) { buf.setLength(0); out.text = "" })
        box.addView(row)
        out = TextView(c).apply {
            typeface = DkaiTheme.MONO; textSize = 12f; setTextColor(0xFFCBD5E1.toInt()); setTextIsSelectable(true)
            setPadding(d(12f), d(10f), d(12f), d(10f)); setLineSpacing(0f, 1.15f)
        }
        UiLang.keep(out)
        scroll = ScrollView(c).apply { background = DkaiTheme.round(c, 0xFF070B14.toInt(), 14f, DkaiTheme.BORDER); addView(out) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), d(6f)) })

        // जल्दी वाली कमांड
        val chips = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), 0) }
        for (q in QUICK) chips.addView(DkaiTheme.pill(c, q, DkaiTheme.PILL, 0xFF93C5FD.toInt(), 12f).apply {
            typeface = DkaiTheme.MONO; minHeight = d(34f); contentDescription = "चलाएँ: $q"
            setOnClickListener { input.setText(q); submit() }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })

        val inRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.CARD, 14f, DkaiTheme.BORDER); setPadding(d(12f), 0, d(2f), 0)
        }
        promptTv = DkaiTheme.text(c, "~ $", 13f, DkaiTheme.GREEN, DkaiTheme.MONO)
        inRow.addView(promptTv)
        input = EditText(c).apply {
            typeface = DkaiTheme.MONO; textSize = 13f; setTextColor(DkaiTheme.WHITE); setHintTextColor(DkaiTheme.HINT); hint = "command लिखें (help)"
            background = null; setSingleLine(); setPadding(d(8f), d(12f), d(8f), d(12f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, a, ev -> if (a == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false }
        }
        UiLang.keep(input)
        inRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        runBtn = iconBtn(DkaiIcon.Kind.PLAY, "चलाएँ", DkaiTheme.CYAN) { if (input.text.isBlank() && (proc != null || busy)) stop() else submit() }
        inRow.addView(runBtn)
        box.addView(inRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })

        // कीबोर्ड में न मिलने वाले बटन
        val keys = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(8f)) }
        for (k in KEYS) keys.addView(DkaiTheme.pill(c, k, 0xFF111827.toInt(), if (k == "^C") DkaiTheme.RED else DkaiTheme.WHITE, 13f).apply {
            typeface = DkaiTheme.MONO; minHeight = d(36f); minWidth = d(44f); gravity = Gravity.CENTER
            contentDescription = when (k) { "Tab" -> "नाम पूरा करें"; "^C" -> "चलती कमांड रोकें"; else -> "लिखें $k" }
            setOnClickListener { key(k) }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(keys) })

        print("DKAI Terminal — ऐप का अपना Linux shell (Android sh/toybox) + DK की कमांड (wget, curl, zip, tree, sysinfo…).\n" +
            "फ़ोल्डर: Workspace. 'help' लिखें. DK लाइव से भी बोलकर चला सकते हैं.\n\n")
        prompt()
        DkaiShell.listeners.add(fromLive)
    }

    override fun onShow() { if (::input.isInitialized) { input.requestFocus(); prompt() } }

    fun run(cmd: String, dir: File) {
        root            // पहली बार: पैनल अभी बनाओ
        if (dir.isDirectory && DkaiWorkspace.rootOf(c, dir) != null) DkaiShell.cwd = dir
        prompt(); input.setText(cmd); submit()
    }

    private fun hist(dir: Int) {
        val h = DkaiShell.history
        if (h.isEmpty()) return
        hIdx = DkaiCore.historyAt(h, hIdx, dir)
        input.setText(h.getOrNull(hIdx) ?: ""); input.setSelection(input.text.length)
    }

    private fun prompt() { if (::promptTv.isInitialized) promptTv.text = (if (proc != null) "›" else DkaiShell.prompt(c) + " $") }

    private fun print(s: String) {
        if (s.isEmpty()) return
        buf.append(s)
        if (buf.length > 80_000) buf.delete(0, buf.length - 60_000)
        out.text = buf
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyOut() {
        try {
            (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("DKAI Terminal", buf.toString().takeLast(60_000)))
            nav.toast("Terminal का output copy हो गया")
        } catch (_: Exception) { nav.toast("copy नहीं हुआ") }
    }

    private fun key(k: String) {
        when (k) {
            "Tab" -> tab()
            "^C" -> stop()
            else -> { val st = input.selectionStart.coerceAtLeast(0); input.text.insert(st, k) }
        }
    }

    /** Tab: आख़िरी शब्द का फ़ाइल / फ़ोल्डर नाम पूरा */
    private fun tab() {
        val line = input.text.toString()
        val word = line.substring(line.lastIndexOf(' ') + 1)
        val base = DkaiShell.dir(c)
        val inDir = if (word.contains('/')) File(if (word.startsWith("/")) File("/") else base, word.substringBeforeLast('/')) else base
        val names = (inDir.listFiles()?.toList() ?: emptyList()).map { it.name + if (it.isDirectory) "/" else "" } +
            (if (!line.contains(' ')) DkaiTerm.BUILTINS.toList() + DkaiShell.aliases.keys else emptyList())
        val (done, many) = DkaiTerm.complete(line, names)
        input.setText(done); input.setSelection(done.length)
        if (many.isNotEmpty()) print(many.joinToString("  ") + "\n")
    }

    private fun submit() {
        val raw = input.text.toString()
        input.setText("")
        // चलती कमांड जवाब माँग रही (read / y/n) = यह लाइन उसी को
        if (proc != null) {
            try { stdin?.apply { write(raw + "\n"); flush() }; print(raw + "\n") } catch (_: Exception) { print("(कमांड जवाब नहीं ले रही)\n") }
            return
        }
        val typed = raw.trim()
        if (typed.isEmpty()) return
        if (busy) { nav.toast("पहले वाली कमांड चल रही है - ■ से रोकें"); return }
        val cmd = DkaiShell.prepare(c, typed)
        if (cmd == null) { print("${DkaiShell.prompt(c)} $ $typed\nइतिहास में ऐसी कमांड नहीं\n"); return }
        DkaiShell.remember(c, cmd)
        hIdx = DkaiShell.history.size
        val dir = DkaiShell.dir(c)
        print("${DkaiCore.prompt(dir, home)} $ $typed\n" + (if (cmd != typed) "→ $cmd\n" else ""))
        val h = DkaiTerm.head(cmd); val arg = cmd.removePrefix(h).trim()
        when (h) {
            "clear" -> { buf.setLength(0); out.text = ""; return }
            "open" -> { val f = File(dir, arg); if (f.isFile) nav.openInEditor(f) else print("open: फ़ाइल नहीं मिली\n"); return }
            "preview" -> { val f = File(dir, arg); if (f.isFile) nav.openPreview(f) else print("preview: फ़ाइल नहीं मिली\n"); return }
            "cd" -> { DkaiShell.builtin(c, cmd)?.let { print(it) }; prompt(); return }
        }
        if (DkaiTerm.isBuiltin(cmd)) {
            busy = true; setRunning(true)
            Thread {
                val r = try { DkaiShell.builtin(c, cmd) ?: "" } catch (e: Exception) { "नहीं हुआ: ${e.message}\n" }
                ui.post { print(r); busy = false; setRunning(false); prompt() }
            }.start()
            return
        }
        exec(cmd, dir)
    }

    private fun exec(cmd: String, dir: File) {
        val p = try { DkaiShell.start(c, cmd, dir) } catch (e: Exception) { print("चल नहीं पाई: ${e.message}\n"); return }
        proc = p; stdin = try { p.outputStream.bufferedWriter() } catch (_: Exception) { null }
        setRunning(true); prompt()
        Thread {
            try {
                val r = p.inputStream.bufferedReader()
                val chunk = CharArray(2048)
                while (true) {
                    val n = r.read(chunk); if (n < 0) break
                    val s = DkaiTerm.stripAnsi(String(chunk, 0, n)); ui.post { print(s) }
                }
                val code = p.waitFor()
                ui.post {
                    if (code == 127) print("[exit 127] यह कमांड फ़ोन में नहीं है - 'help' देखें\n") else if (code != 0) print("[exit $code]\n")
                    proc = null; stdin = null; setRunning(false); prompt()
                }
            } catch (_: Throwable) { ui.post { proc = null; stdin = null; setRunning(false); prompt() } }
        }.start()
    }

    fun stop() {
        val p = proc ?: return
        try { p.destroy() } catch (_: Exception) {}
        print("^C\n")
    }

    private fun setRunning(on: Boolean) {
        runBtn.setImageDrawable(DkaiIcon(if (on) DkaiIcon.Kind.STOP else DkaiIcon.Kind.PLAY, if (on) DkaiTheme.RED else DkaiTheme.CYAN))
        runBtn.contentDescription = if (on) "रोकें (खाली हो तो) / जवाब भेजें" else "चलाएँ"
    }

    companion object {
        const val HELP = DkaiTerm.HELP
        val QUICK = listOf("ls -la", "tree", "sysinfo", "history", "du -sh *", "df -h", "ps -A", "env", "help")
        val KEYS = listOf("Tab", "^C", "|", "/", "-", "~/", ">", "&&", "*", "$", "\"")
    }
}

// =====================================================================================================================
/** Preview: HTML/वेबसाइट को असली WebView में चलाओ (JS, CSS, दूसरी फ़ाइलें उसी फ़ोल्डर से) */
class PreviewPanel(nav: DkaiNav) : DkaiPanel(nav) {
    var file: File? = null; private set
    private lateinit var frame: FrameLayout
    private lateinit var title: TextView
    private lateinit var log: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var url: EditText
    private lateinit var autopilot: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerTxt: TextView
    private lateinit var drawerBtn: TextView
    private var web: android.webkit.WebView? = null
    private var errors = 0
    private val logs = ArrayList<String>()
    private var opened = listOf<String>()          // खुले टैब (पूरे रास्ते)
    private var state = "READY"

    override fun build(box: LinearLayout) {
        val (row, t) = bar("Preview"); title = t
        row.addView(iconBtn(DkaiIcon.Kind.FILES, "HTML फ़ाइल चुनें") { pick() })
        row.addView(iconBtn(DkaiIcon.Kind.REFRESH, "फिर से चलाएँ", DkaiTheme.CYAN) { load() })
        box.addView(row)
        // ब्राउज़र जैसी टैब पट्टी
        tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(4f)) }
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        // URL पट्टी: localhost:8080 + ✦ AI Sparkle / Share / Reload
        val ub = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.FIELD, 22f, DkaiTheme.BORDER); setPadding(d(12f), 0, d(2f), 0) }
        ub.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.LOCK, DkaiKit.EMERALD)) }, LinearLayout.LayoutParams(d(14f), d(14f)))
        url = EditText(c).apply { textSize = 13f; typeface = DkaiTheme.MONO; setTextColor(DkaiTheme.WHITE); background = null; setSingleLine()
            setPadding(d(8f), d(6f), d(4f), d(6f)); imeOptions = EditorInfo.IME_ACTION_GO; inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(text.toString()); true } else false } }
        UiLang.keep(url)
        ub.addView(url, LinearLayout.LayoutParams(0, -2, 1f))
        ub.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SPARKLE, "AI Sparkle: ${DkaiKit.COPILOT} Enhancement", DkaiTheme.VIOLET, 40f) { sparkle() })
        ub.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SHARE, "शेयर करें", DkaiTheme.GRAY, 40f) { shareIt() })
        ub.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.REFRESH, "Reload", DkaiTheme.CYAN, 40f) { web?.reload() ?: load() })
        box.addView(ub, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })
        autopilot = DkaiTheme.text(c, "", 11.5f, DkaiKit.EMERALD, DkaiTheme.SANS_BOLD).apply { setPadding(d(16f), 0, d(16f), d(6f)) }
        box.addView(autopilot)
        frame = FrameLayout(c).apply { background = DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 14f); clipToOutline = true }
        box.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), 0) })
        log = DkaiTheme.text(c, "", 11f, DkaiTheme.GRAY, DkaiTheme.MONO).apply { setPadding(d(16f), d(6f), d(16f), d(2f)); maxLines = 2 }
        box.addView(log)
        // Console Logs (N) दराज़ + AI Inspect
        val bot = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), d(2f), d(12f), d(6f)) }
        drawerBtn = DkaiTheme.pill(c, "Console Logs (0)", DkaiTheme.PILL, DkaiTheme.SUB, 12f).apply { minHeight = d(40f)
            setOnClickListener { drawer.visibility = if (drawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE; paintLogs() } }
        bot.addView(drawerBtn)
        bot.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        bot.addView(DkaiTheme.pill(c, "✦ AI Inspect", DkaiTheme.BLUE, DkaiTheme.ON, 12f).apply { minHeight = d(40f); setOnClickListener { inspect() } })
        box.addView(bot)
        drawer = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(d(10f), d(8f), d(10f), d(8f))
            background = DkaiTheme.round(c, DkaiTheme.CARD, 12f, DkaiTheme.BORDER) }
        drawerTxt = DkaiTheme.text(c, "", 11f, DkaiTheme.SUB, DkaiTheme.MONO).apply { setTextIsSelectable(true) }
        drawer.addView(MaxScroll(c, d(180f)).apply { addView(drawerTxt) })
        drawer.addView(LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL
            addView(DkaiTheme.pill(c, "Clear", DkaiTheme.PILL, DkaiTheme.GRAY, 11.5f).apply { setOnClickListener { logs.clear(); errors = 0; paintLogs() } })
            addView(DkaiTheme.pill(c, "Copy", DkaiTheme.PILL, DkaiTheme.GRAY, 11.5f).apply { setOnClickListener { DkaiUi.copy(c, "console", logs.joinToString("\n")) } },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(6f) }) })
        box.addView(drawer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(6f)) })
        load()
    }

    override fun onShow() { if (file == null) file = guess(); load() }

    override fun onBack(): Boolean { val w = web ?: return false; if (w.canGoBack()) { w.goBack(); return true }; return false }

    fun open(f: File) { file = f; if (::frame.isInitialized) load() }

    /** कोई फ़ाइल न चुनी हो: App Studio / Workspace का सबसे नया index.html */
    private fun guess(): File? = DkaiWorkspace.activeProject(c)?.let { DkaiWorkspace.entryOf(it) }
        ?: htmlFiles().firstOrNull { it.name.equals("index.html", true) } ?: htmlFiles().firstOrNull()

    private fun htmlFiles(): List<File> = DkaiWorkspace.roots(c).flatMap { r ->
        r.dir.walkTopDown().maxDepth(5).onEnter { it.name != "node_modules" && it.name != ".git" }.filter { it.isFile && DkaiCore.isHtml(it.name) }.take(60).toList()
    }.sortedByDescending { it.lastModified() }

    private fun pick() {
        val fs = htmlFiles()
        if (fs.isEmpty()) { nav.toast("कोई HTML फ़ाइल नहीं मिली - Files में index.html बनाइए"); return }
        val names = fs.map { f -> (DkaiWorkspace.rootOf(c, f)?.let { r -> r.name + "/" + f.canonicalPath.removePrefix(r.dir.canonicalPath).trimStart('/') } ?: f.name) }
        AlertDialog.Builder(c).setTitle("कौन-सी फ़ाइल चलाएँ?").setItems(names.toTypedArray()) { _, i -> file = fs[i]; load() }.show()
    }

    /** फ़ाइल किस प्रोजेक्ट की? (DKAIProjects/<नाम>) - न हो तो उसका फ़ोल्डर */
    private fun projectOf(f: File): File {
        val pr = try { DkaiWorkspace.projectsRoot(c).canonicalFile } catch (_: Exception) { null }
        var d: File? = f.canonicalFile.parentFile
        while (d != null && pr != null) { if (d.parentFile?.canonicalPath == pr.path) return d; d = d.parentFile }
        DkaiWorkspace.repoDirOf(c, f)?.let { return it }
        return f.parentFile ?: f
    }

    private fun addLog(s: String, err: Boolean) {
        if (err) errors++
        logs.add((if (err) "✗ " else "› ") + s); while (logs.size > 300) logs.removeAt(0)
        log.text = "console: ${s.take(140)}" + if (errors > 0) "  • $errors error" else ""
        paintLogs()
    }

    private fun paintLogs() {
        if (!::drawerBtn.isInitialized) return
        drawerBtn.text = "Console Logs (${logs.size})" + if (errors > 0) " • $errors ✗" else ""
        drawerBtn.setTextColor(if (errors > 0) DkaiTheme.RED else DkaiTheme.SUB)
        if (drawer.visibility == View.VISIBLE) drawerTxt.text = if (logs.isEmpty()) "(अभी कोई console संदेश नहीं)" else logs.joinToString("\n")
    }

    private fun paintState(s: String) {
        state = s
        val on = DkaiServer.running()
        autopilot.text = "● AUTOPILOT " + (if (on) "RUNNING" else "OFFLINE") + " [$s]"
        autopilot.setTextColor(if (!on) DkaiTheme.GRAY else if (s == "READY") DkaiKit.EMERALD else DkaiTheme.AMBER)
    }

    private fun paintTabs() {
        tabs.removeAllViews()
        val cur = file?.absolutePath
        for (p in opened) {
            val f = File(p); val on = p == cur
            tabs.addView(DkaiTheme.pill(c, f.name + "  ×", if (on) DkaiTheme.SEL else DkaiTheme.PILL, if (on) DkaiTheme.WHITE else DkaiTheme.GRAY, 12f).apply {
                minHeight = d(36f); contentDescription = f.name
                setOnClickListener { if (on) { opened = opened - p; if (opened.isEmpty()) { file = null } else file = File(opened.last()); load() } else { file = f; load() } }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        }
        tabs.addView(DkaiTheme.pill(c, "+", DkaiTheme.PILL, DkaiTheme.CYAN, 13f).apply { minHeight = d(36f); contentDescription = "नया टैब"; setOnClickListener { pick() } })
    }

    private fun go(typed: String) {
        val t = typed.trim(); if (t.isEmpty()) return
        val w = ensureWeb() ?: return
        val u = if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file:")) t else if (t.startsWith("localhost")) "http://$t" else "https://$t"
        (nav as? DkaiScreen)?.hideKeyboard()
        w.loadUrl(u)
    }

    private fun sparkle() {
        val s = nav as? DkaiScreen ?: return
        val f = file ?: run { pick(); return }
        DkaiModals.enhance(s, projectOf(f), f) { r -> if (r != null) { addLog("${DkaiKit.COPILOT}: ${r.changes.size} फ़ाइल बदली", false); load() } }
    }

    private fun shareIt() {
        val f = file ?: return
        DkaiUi.shareText(c, "${f.name} • ${url.text}\n${DkaiKit.BRAND} Preview (${DkaiKit.shownPath(f)})")
    }

    /** AI Inspect: console की गलतियाँ + फ़ाइल -> Copilot से ठीक करवाओ */
    private fun inspect() {
        val f = file ?: run { nav.toast("पहले HTML चलाएँ"); return }
        val errs = logs.filter { it.startsWith("✗") }.takeLast(20)
        val cmd = if (errs.isEmpty()) "Inspect ${f.name}: find visual/layout/JS bugs, broken links, accessibility and mobile responsiveness issues and fix them."
            else "Fix these browser console errors in ${f.name}:\n" + errs.joinToString("\n")
        addLog("AI Inspect चल रहा है…", false); paintState("INSPECTING")
        DkaiCopilot.run(c, projectOf(f), cmd) { r, err ->
            if (err != null) { addLog("AI Inspect: $err", true); paintState("READY") }
            else { addLog("AI Inspect: ${r?.changes?.size ?: 0} फ़ाइल ठीक की (GitHub → Root Project में Undo / Keep)", false); load() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): android.webkit.WebView? {
        web?.let { return it }
        val w = try { android.webkit.WebView(c) } catch (e: Throwable) { log.text = "WebView नहीं चला: ${e.message}"; return null }
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        run { w.settings.allowFileAccess = true; w.settings.allowFileAccessFromFileURLs = true }
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(v: android.webkit.WebView?, u: String?, fav: android.graphics.Bitmap?) { main { paintState("LOADING"); u?.let { url.setText(it.removePrefix("http://")) } } }
            override fun onPageFinished(v: android.webkit.WebView?, u: String?) { main { paintState("READY") } }
        }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m == null) return false
                addLog(m.message().take(300) + if (m.lineNumber() > 0) "  (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})" else "",
                    m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                return true
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(-1, -1)); web = w
        return w
    }

    private fun load() {
        if (!::frame.isInitialized) return
        val f = file
        if (f == null || !f.isFile) {
            frame.removeAllViews(); web = null
            frame.addView(empty("चलाने को कोई HTML नहीं चुनी।\nऊपर फ़ोल्डर वाले बटन से फ़ाइल चुनें।", "फ़ाइल चुनें") { pick() }.apply { setBackgroundColor(DkaiTheme.BG) })
            title.text = "Preview"; url.setText(""); paintTabs(); paintState("IDLE"); return
        }
        opened = DkaiKit.openTabs(opened, f.absolutePath, 5)
        paintTabs()
        if (web == null) frame.removeAllViews()
        val w = ensureWeb() ?: return
        errors = 0; logs.clear(); paintLogs(); title.text = f.name; log.text = "चल रहा है: ${f.name}"
        // असली localhost:8080 - प्रोजेक्ट फ़ोल्डर परोसो (नहीं खुला तो file:// से)
        val root = projectOf(f)
        val err = DkaiServer.start(root)
        val rel = try { f.canonicalFile.relativeTo(root.canonicalFile).path.replace('\\', '/') } catch (_: Exception) { null }
        if (err == null && rel != null && !rel.startsWith("..")) {
            val u = DkaiKit.localUrl(DkaiKit.PORT, "/" + rel.split('/').joinToString("/") { Uri.encode(it) })
            url.setText(u.removePrefix("http://")); w.loadUrl(u)
        } else {
            if (err != null) addLog(err, true)
            url.setText(Uri.fromFile(f).toString()); w.loadUrl(Uri.fromFile(f).toString())
        }
        paintState("LOADING")
    }
}

// =====================================================================================================================
/** GitHub: रिपो की सूची -> Workspace में लाओ -> Editor में बदलो -> नई branch + PR (main पर सीधे नहीं) */
class GithubPanel(nav: DkaiNav) : DkaiPanel(nav) {
    private lateinit var list: LinearLayout
    private lateinit var who: TextView
    private lateinit var search: EditText
    private var repos: List<GitHub.Repo> = emptyList()
    private var loading = false

    override fun build(box: LinearLayout) {
        val (row, _) = bar("GitHub")
        row.addView(iconBtn(DkaiIcon.Kind.REFRESH, "रिपो फिर से लाएँ", DkaiTheme.CYAN) { repos = emptyList(); load() })
        box.addView(row)
        who = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY).apply { setPadding(d(16f), 0, d(16f), d(6f)) }
        box.addView(who)
        search = EditText(c).apply {
            hint = "रिपो खोजें"; setSingleLine(); textSize = 14f; setTextColor(DkaiTheme.WHITE); setHintTextColor(DkaiTheme.HINT)
            background = DkaiTheme.round(c, DkaiTheme.CARD, 14f, DkaiTheme.BORDER); setPadding(d(14f), d(10f), d(14f), d(10f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
        }
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(8f)) })
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(12f), d(16f)) }
        box.addView(ScrollView(c).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun onShow() { load() }

    private fun load() {
        val gh = GitHub.fromPrefs(c)
        if (gh == null) {
            who.text = ""; search.visibility = View.GONE
            list.removeAllViews()
            list.addView(empty("GitHub से जोड़ने के लिए token चाहिए।\nसेटिंग्स → कोडिंग एजेंट में GitHub token डालिए।", "token जोड़ें") {
                c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "coder"))
            })
            return
        }
        search.visibility = View.VISIBLE
        if (repos.isNotEmpty()) { render(); return }
        if (loading) return
        loading = true
        list.removeAllViews(); list.addView(DkaiTheme.text(c, "रिपो ला रहा हूँ…", 13f, DkaiTheme.GRAY).apply { setPadding(d(8f), d(16f), 0, 0) })
        bg {
            val r = try { Result.success(gh.me() to gh.reposAll(3)) } catch (e: Throwable) { Result.failure(e) }
            main {
                loading = false
                r.onSuccess { (me, rs) -> repos = rs; who.text = "@$me • ${rs.size} रिपो"; render() }
                    .onFailure { e -> list.removeAllViews(); list.addView(empty("GitHub से नहीं जुड़ पाया:\n${e.message?.take(160)}", "फिर कोशिश") { load() }) }
            }
        }
    }

    private fun render() {
        if (!::list.isInitialized || repos.isEmpty() && loading) return
        list.removeAllViews()
        // पहले से लाए रिपो
        val local = DkaiWorkspace.list(DkaiWorkspace.workspace(c)).filter { it.isDirectory && DkaiWorkspace.meta(it) != null }
        if (local.isNotEmpty()) {
            list.addView(DkaiTheme.text(c, "WORKSPACE में", 11f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply { letterSpacing = 0.1f; setPadding(d(4f), d(4f), 0, d(8f)) })
            for (dir in local) list.addView(row(DkaiWorkspace.meta(dir)?.repo ?: dir.name, "फ़ोल्डर खोलें • बदलाव भेजें", DkaiIcon.Kind.FILES) { nav.openFiles(dir) })
        }
        val q = search.text.toString().trim().lowercase()
        val shown = repos.filter { q.isEmpty() || it.fullName.lowercase().contains(q) }
        list.addView(DkaiTheme.text(c, "आपके रिपो", 11f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply { letterSpacing = 0.1f; setPadding(d(4f), d(10f), 0, d(8f)) })
        if (shown.isEmpty()) list.addView(DkaiTheme.text(c, if (repos.isEmpty()) "कोई रिपो नहीं" else "\"$q\" नहीं मिला", 13f, DkaiTheme.GRAY).apply { setPadding(d(8f), d(8f), 0, 0) })
        for (r in shown.take(150)) list.addView(row(r.fullName, (if (r.private) "private" else "public") + " • ${r.branch}", if (r.private) DkaiIcon.Kind.LOCK else DkaiIcon.Kind.GITHUB) { askImport(r) })
    }

    private fun row(name: String, sub: String, icon: DkaiIcon.Kind, onTap: () -> Unit): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(56f)
        setPadding(d(14f), d(8f), d(10f), d(8f))
        background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 12f, DkaiTheme.BORDER)); isClickable = true; isFocusable = true
        addView(ImageView(c).apply { setImageDrawable(DkaiIcon(icon, DkaiTheme.CYAN)) }, LinearLayout.LayoutParams(d(20f), d(20f)))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0)
            addView(DkaiTheme.text(c, name, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            addView(DkaiTheme.text(c, sub, 11f, DkaiTheme.GRAY).apply { setPadding(0, d(3f), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHEVRON_RIGHT, DkaiTheme.GRAY)) }, LinearLayout.LayoutParams(d(18f), d(18f)))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8f) }
    }

    private fun askImport(r: GitHub.Repo) {
        val name = r.fullName.substringAfter('/')
        val exists = File(DkaiWorkspace.workspace(c), name).exists()
        AlertDialog.Builder(c).setTitle(r.fullName)
            .setMessage("रिपो की text फ़ाइलें Workspace/$name में लाऊँ?" + if (exists) "\n\nध्यान दें: पहले से लाया फ़ोल्डर नए से बदल जाएगा (बिना भेजे बदलाव हट जाएँगे)।" else "")
            .setPositiveButton("लाएँ") { _, _ -> importRepo(r) }.setNegativeButton("रद्द", null).show()
    }

    private fun importRepo(r: GitHub.Repo) {
        val gh = GitHub.fromPrefs(c) ?: return
        nav.toast("${r.fullName} ला रहा हूँ…")
        bg {
            val res = try { Result.success(DkaiWorkspace.importRepo(c, gh, r.fullName)) } catch (e: Throwable) { Result.failure(e) }
            main {
                res.onSuccess { (dir, skipped) ->
                    nav.toast("✓ ला दिया" + if (skipped > 0) " ($skipped फोटो/बड़ी फ़ाइलें छोड़ीं)" else "")
                    nav.openFiles(dir)
                }.onFailure { e -> nav.toast("नहीं ला पाया: ${e.message?.take(120)}") }
            }
        }
    }
}
