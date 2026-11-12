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

    override fun build(box: LinearLayout) {
        val (row, _) = bar("Files")
        row.addView(iconBtn(DkaiIcon.Kind.FILE_PLUS, "नई फ़ाइल") { newItem(false) })
        row.addView(iconBtn(DkaiIcon.Kind.FOLDER_PLUS, "नया फ़ोल्डर") { newItem(true) })
        row.addView(iconBtn(DkaiIcon.Kind.REFRESH, "ताज़ा करें") { refresh() })
        box.addView(row)
        roots = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(12f), 0, d(12f), d(6f)) }
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(roots) })
        val pathRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4f), 0, d(16f), 0) }
        pathRow.addView(iconBtn(DkaiIcon.Kind.BACK, "ऊपर का फ़ोल्डर", DkaiTheme.GRAY) { up() })
        path = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY, DkaiTheme.MONO).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        pathRow.addView(path, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(pathRow)
        repoBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = DkaiTheme.round(c, 0xFF14213A.toInt(), 14f, DkaiTheme.BORDER); setPadding(d(12f), d(4f), d(4f), d(4f))
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
        // GitHub से लाया रिपो?
        val repo = DkaiWorkspace.repoDirOf(c, cur)
        repoBar.visibility = if (repo != null) View.VISIBLE else View.GONE
        if (repo != null) {
            val m = DkaiWorkspace.meta(repo)
            repoTxt.text = "${m?.repo} • ${m?.branch}\nबदलाव गिन रहा हूँ…"
            bg { val n = DkaiWorkspace.pending(repo).size; main { repoTxt.text = "${m?.repo} • ${m?.branch}\n" + if (n == 0) "कोई बदलाव नहीं" else "$n फ़ाइल बदली" } }
        }
        list.removeAllViews()
        val items = DkaiWorkspace.list(cur)
        if (items.isEmpty()) { list.addView(empty("यह फ़ोल्डर खाली है।\nऊपर + से नई फ़ाइल बनाइए।", "नई फ़ाइल") { newItem(false) }); return }
        for (f in items.take(400)) list.addView(row(f))
        if (items.size > 400) list.addView(DkaiTheme.text(c, "+${items.size - 400} और", 12f, DkaiTheme.GRAY).apply { setPadding(d(8f), d(8f), 0, 0) })
    }

    private fun row(f: File): View {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(52f)
            setPadding(d(12f), d(6f), d(4f), d(6f))
            background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 12f, DkaiTheme.BORDER))
            isClickable = true; isFocusable = true
        }
        val kind = if (f.isDirectory) DkaiIcon.Kind.FILES else DkaiIcon.Kind.FILE
        r.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(kind, if (f.isDirectory) DkaiTheme.CYAN else DkaiTheme.GRAY)) }, LinearLayout.LayoutParams(d(20f), d(20f)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0) }
        col.addView(DkaiTheme.text(c, f.name, 14f, DkaiTheme.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
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
        if (f.isDirectory) acts.add("यहाँ Terminal खोलें" to { nav.runInTerminal("ls -la", f) })
        acts.add("नाम बदलें" to {
            ask("नाम बदलें", "नया नाम", f.name) { n ->
                if (DkaiWorkspace.rename(f, n) == null) nav.toast("नाम नहीं बदला (गलत नाम या पहले से मौजूद)") else refresh()
            }
        })
        acts.add("हटाएँ" to {
            AlertDialog.Builder(c).setTitle("\"${f.name}\" हटाएँ?").setMessage(if (f.isDirectory) "फ़ोल्डर और उसके अंदर का सब कुछ हमेशा के लिए हट जाएगा।" else "यह फ़ाइल हमेशा के लिए हट जाएगी।")
                .setPositiveButton("हटाएँ") { _, _ -> if (DkaiWorkspace.delete(f)) { nav.toast("हटा दिया"); refresh() } else nav.toast("हटा नहीं पाया") }
                .setNegativeButton("रद्द", null).show()
        })
        AlertDialog.Builder(c).setTitle(f.name).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
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
    val dirty get() = ::edit.isInitialized && file != null && edit.text.toString() != saved

    override fun build(box: LinearLayout) {
        val (row, t) = bar("Editor"); title = t
        previewBtn = iconBtn(DkaiIcon.Kind.PREVIEW, "Preview में चलाएँ") { file?.let { save(); nav.openPreview(it) } }
        runBtn = iconBtn(DkaiIcon.Kind.PLAY, "Terminal में चलाएँ") { file?.let { save(); nav.runInTerminal("sh '${it.name}'", it.parentFile ?: it) } }
        row.addView(previewBtn); row.addView(runBtn)
        row.addView(iconBtn(DkaiIcon.Kind.WRAP, "लाइन लपेटें") { wrap = !wrap; applyWrap() })
        row.addView(iconBtn(DkaiIcon.Kind.SAVE, "सेव करें", DkaiTheme.CYAN) { if (save()) nav.toast("सेव हो गया") })
        box.addView(row)
        val frame = FrameLayout(c)
        edit = EditText(c).apply {
            typeface = DkaiTheme.MONO; textSize = 13f; setTextColor(0xFFE2E8F0.toInt()); setHintTextColor(DkaiTheme.GRAY)
            gravity = Gravity.TOP or Gravity.START
            background = DkaiTheme.round(c, 0xFF0F1626.toInt(), 14f, DkaiTheme.BORDER)
            setPadding(d(12f), d(10f), d(12f), d(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isVerticalScrollBarEnabled = true; setHorizontallyScrolling(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable?) { stat() }
            })
        }
        UiLang.keep(edit)
        frame.addView(edit, FrameLayout.LayoutParams(-1, -1))
        emptyBox = empty("कोई फ़ाइल खुली नहीं।\nFiles से फ़ाइल चुनिए या नई बनाइए।", "Files खोलें") { nav.openFiles(DkaiWorkspace.workspace(c)) }
            .apply { setBackgroundColor(DkaiTheme.BG) }
        frame.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))
        box.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), 0) })
        status = DkaiTheme.text(c, "", 11f, DkaiTheme.GRAY, DkaiTheme.MONO).apply { setPadding(d(16f), d(6f), d(16f), d(6f)) }
        box.addView(status)
        show()
    }

    fun open(f: File): Boolean {
        val (txt, why) = DkaiWorkspace.readText(f)
        if (txt == null) { nav.toast(why ?: "नहीं खुली"); return false }
        file = f; saved = txt
        root            // पहली बार: पैनल अभी बनाओ
        edit.setText(txt); edit.setSelection(0); show()
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

    private fun applyWrap() { edit.setHorizontallyScrolling(!wrap); nav.toast(if (wrap) "लाइन लपेटना चालू" else "लाइन लपेटना बंद") }

    private fun show() {
        val f = file
        emptyBox.visibility = if (f == null) View.VISIBLE else View.GONE
        title.text = f?.name ?: "Editor"
        previewBtn.visibility = if (f != null && DkaiCore.isHtml(f.name)) View.VISIBLE else View.GONE
        runBtn.visibility = if (f != null && f.name.endsWith(".sh")) View.VISIBLE else View.GONE
        stat()
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
/** Terminal: असली shell (ऐप के अपने फ़ोल्डर में), output लाइव, रोकें, इतिहास ↑ ↓ */
class TerminalPanel(nav: DkaiNav) : DkaiPanel(nav) {
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var promptTv: TextView
    private lateinit var runBtn: ImageView
    private val buf = StringBuilder()
    private val history = ArrayList<String>()
    private var hIdx = 0
    @Volatile private var proc: Process? = null
    var cwd: File? = null; private set
    private val home get() = DkaiWorkspace.workspace(c)

    override fun build(box: LinearLayout) {
        val (row, _) = bar("Terminal")
        row.addView(iconBtn(DkaiIcon.Kind.UP, "पिछली कमांड", DkaiTheme.GRAY) { hist(-1) })
        row.addView(iconBtn(DkaiIcon.Kind.DOWN, "अगली कमांड", DkaiTheme.GRAY) { hist(1) })
        row.addView(iconBtn(DkaiIcon.Kind.TRASH, "साफ़ करें", DkaiTheme.GRAY) { buf.setLength(0); out.text = "" })
        box.addView(row)
        out = TextView(c).apply {
            typeface = DkaiTheme.MONO; textSize = 12f; setTextColor(0xFFCBD5E1.toInt()); setTextIsSelectable(true)
            setPadding(d(12f), d(10f), d(12f), d(10f)); setLineSpacing(0f, 1.15f)
        }
        UiLang.keep(out)
        scroll = ScrollView(c).apply { background = DkaiTheme.round(c, 0xFF070B14.toInt(), 14f, DkaiTheme.BORDER); addView(out) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), d(8f)) })
        val inRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.CARD, 14f, DkaiTheme.BORDER); setPadding(d(12f), 0, d(2f), 0)
        }
        promptTv = DkaiTheme.text(c, "~ $", 13f, DkaiTheme.GREEN, DkaiTheme.MONO)
        inRow.addView(promptTv)
        input = EditText(c).apply {
            typeface = DkaiTheme.MONO; textSize = 13f; setTextColor(DkaiTheme.WHITE); setHintTextColor(0xFF64748B.toInt()); hint = "command लिखें (help)"
            background = null; setSingleLine(); setPadding(d(8f), d(12f), d(8f), d(12f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, a, ev -> if (a == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false }
        }
        UiLang.keep(input)
        inRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        runBtn = iconBtn(DkaiIcon.Kind.PLAY, "चलाएँ", DkaiTheme.CYAN) { if (proc != null) stop() else submit() }
        inRow.addView(runBtn)
        box.addView(inRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), 0, d(12f), d(10f)) })
        if (cwd == null) cwd = home
        print("DKAI Terminal — ऐप का अपना Linux shell (Android sh/toybox).\nफ़ोल्डर: Workspace. 'help' लिखें।\n\n")
        prompt()
    }

    override fun onShow() { if (::input.isInitialized) input.requestFocus() }

    fun run(cmd: String, dir: File) {
        root            // पहली बार: पैनल अभी बनाओ
        cwd = dir; prompt(); input.setText(cmd); submit()
    }

    private fun hist(dir: Int) {
        if (history.isEmpty()) return
        hIdx = DkaiCore.historyAt(history, hIdx, dir)
        input.setText(history.getOrNull(hIdx) ?: ""); input.setSelection(input.text.length)
    }

    private fun prompt() { if (::promptTv.isInitialized) promptTv.text = DkaiCore.prompt(cwd ?: home, home) + " $" }

    private fun print(s: String) {
        buf.append(s)
        if (buf.length > 60_000) buf.delete(0, buf.length - 50_000)
        out.text = buf
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submit() {
        val cmd = input.text.toString().trim()
        input.setText("")
        if (cmd.isEmpty()) return
        if (proc != null) { nav.toast("पहले वाली कमांड चल रही है - ■ से रोकें"); return }
        if (history.lastOrNull() != cmd) history.add(cmd)
        hIdx = history.size
        val dir = cwd ?: home
        print("${DkaiCore.prompt(dir, home)} $ $cmd\n")
        // अपनी कमांड
        DkaiCore.cd(cmd, dir, home)?.let { to ->
            if (to.isDirectory && DkaiWorkspace.rootOf(c, to) != null) { cwd = to; prompt() }
            else print("cd: $to: यह फ़ोल्डर नहीं (या ऐप के फ़ोल्डर से बाहर)\n")
            return
        }
        when {
            cmd == "clear" -> { buf.setLength(0); out.text = ""; return }
            cmd == "help" -> { print(HELP); return }
            cmd.startsWith("open ") -> { val f = File(dir, cmd.removePrefix("open ").trim()); if (f.isFile) nav.openInEditor(f) else print("open: फ़ाइल नहीं मिली\n"); return }
            cmd.startsWith("preview ") -> { val f = File(dir, cmd.removePrefix("preview ").trim()); if (f.isFile) nav.openPreview(f) else print("preview: फ़ाइल नहीं मिली\n"); return }
        }
        exec(cmd, dir)
    }

    private fun exec(cmd: String, dir: File) {
        val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        val p = try {
            ProcessBuilder(sh, "-c", cmd).directory(dir).redirectErrorStream(true).apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = c.cacheDir.absolutePath
                environment()["TERM"] = "dumb"
            }.start()
        } catch (e: Exception) { print("चल नहीं पाई: ${e.message}\n"); return }
        proc = p; setRunning(true)
        Thread {
            try {
                val r = p.inputStream.bufferedReader()
                val chunk = CharArray(2048)
                while (true) {
                    val n = r.read(chunk); if (n < 0) break
                    val s = String(chunk, 0, n); main { print(s) }
                }
                val code = p.waitFor()
                main { if (code != 0) print("[exit $code]\n"); proc = null; setRunning(false) }
            } catch (_: Throwable) { main { proc = null; setRunning(false) } }
        }.start()
    }

    fun stop() { proc?.let { try { it.destroy() } catch (_: Exception) {} ; print("^C\n") } }

    private fun setRunning(on: Boolean) {
        runBtn.setImageDrawable(DkaiIcon(if (on) DkaiIcon.Kind.STOP else DkaiIcon.Kind.PLAY, if (on) DkaiTheme.RED else DkaiTheme.CYAN))
        runBtn.contentDescription = if (on) "रोकें" else "चलाएँ"
    }

    companion object {
        const val HELP = "ऐप का अपना shell (Android का sh + toybox): ls, cat, echo, mkdir, cp, mv, rm, grep, sed, find, wc, date…\n" +
            "cd <फ़ोल्डर>   - Workspace / App Studio / DK Computer के अंदर\n" +
            "open <फ़ाइल>   - Editor में खोलें\n" +
            "preview <फ़ाइल> - HTML को Preview में चलाएँ\n" +
            "clear         - स्क्रीन साफ़\n" +
            "↑ ↓           - पिछली कमांड;  ■ = चलती कमांड रोकें\n\n"
    }
}

// =====================================================================================================================
/** Preview: HTML/वेबसाइट को असली WebView में चलाओ (JS, CSS, दूसरी फ़ाइलें उसी फ़ोल्डर से) */
class PreviewPanel(nav: DkaiNav) : DkaiPanel(nav) {
    var file: File? = null; private set
    private lateinit var frame: FrameLayout
    private lateinit var title: TextView
    private lateinit var log: TextView
    private var web: android.webkit.WebView? = null
    private var errors = 0

    override fun build(box: LinearLayout) {
        val (row, t) = bar("Preview"); title = t
        row.addView(iconBtn(DkaiIcon.Kind.FILES, "HTML फ़ाइल चुनें") { pick() })
        row.addView(iconBtn(DkaiIcon.Kind.REFRESH, "फिर से चलाएँ", DkaiTheme.CYAN) { load() })
        box.addView(row)
        frame = FrameLayout(c).apply { background = DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 14f); clipToOutline = true }
        box.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(12f), 0, d(12f), 0) })
        log = DkaiTheme.text(c, "", 11f, DkaiTheme.GRAY, DkaiTheme.MONO).apply { setPadding(d(16f), d(6f), d(16f), d(6f)); maxLines = 2 }
        box.addView(log)
        load()
    }

    override fun onShow() { if (file == null) file = guess(); load() }

    override fun onBack(): Boolean { val w = web ?: return false; if (w.canGoBack()) { w.goBack(); return true }; return false }

    fun open(f: File) { file = f; if (::frame.isInitialized) load() }

    /** कोई फ़ाइल न चुनी हो: App Studio / Workspace का सबसे नया index.html */
    private fun guess(): File? = htmlFiles().firstOrNull { it.name.equals("index.html", true) } ?: htmlFiles().firstOrNull()

    private fun htmlFiles(): List<File> = DkaiWorkspace.roots(c).flatMap { r ->
        r.dir.walkTopDown().maxDepth(5).onEnter { it.name != "node_modules" && it.name != ".git" }.filter { it.isFile && DkaiCore.isHtml(it.name) }.take(60).toList()
    }.sortedByDescending { it.lastModified() }

    private fun pick() {
        val fs = htmlFiles()
        if (fs.isEmpty()) { nav.toast("कोई HTML फ़ाइल नहीं मिली - Files में index.html बनाइए"); return }
        val names = fs.map { f -> (DkaiWorkspace.rootOf(c, f)?.let { r -> r.name + "/" + f.canonicalPath.removePrefix(r.dir.canonicalPath).trimStart('/') } ?: f.name) }
        AlertDialog.Builder(c).setTitle("कौन-सी फ़ाइल चलाएँ?").setItems(names.toTypedArray()) { _, i -> file = fs[i]; load() }.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): android.webkit.WebView? {
        web?.let { return it }
        val w = try { android.webkit.WebView(c) } catch (e: Throwable) { log.text = "WebView नहीं चला: ${e.message}"; return null }
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        run { w.settings.allowFileAccess = true; w.settings.allowFileAccessFromFileURLs = true }
        w.webViewClient = android.webkit.WebViewClient()
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                if (m == null) return false
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) errors++
                log.text = "console: ${m.message().take(140)}" + if (errors > 0) "  • $errors error" else ""
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
            title.text = "Preview"; return
        }
        if (web == null) frame.removeAllViews()
        val w = ensureWeb() ?: return
        errors = 0; title.text = f.name; log.text = "चल रहा है: ${f.name}"
        w.loadUrl(Uri.fromFile(f).toString())
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
            hint = "रिपो खोजें"; setSingleLine(); textSize = 14f; setTextColor(DkaiTheme.WHITE); setHintTextColor(0xFF64748B.toInt())
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
