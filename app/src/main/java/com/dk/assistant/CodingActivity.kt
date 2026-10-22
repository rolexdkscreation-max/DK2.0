package com.dk.assistant

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coding agent ka session - screen band karne par bhi kaam aur staged badlaav bane rahein */
object CoderSession {
    data class Line(val role: String, val text: String)   // u = aap, d = agent, s = kadam, c = badlaav, e = galti
    val lines = mutableListOf<Line>()
    var agent: CodingAgent? = null
    var job: Job? = null
    var lastCommit = ""
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile var listener: (() -> Unit)? = null
    fun add(role: String, t: String) {
        // एक ही संदेश दो बार नहीं (हर कदम पर वही चेतावनी/गलती)
        if (lines.takeLast(3).any { it.role == role && it.text == t }) return
        lines.add(Line(role, t)); if (lines.size > 300) lines.removeAt(0); listener?.invoke()
    }
    val busy get() = job?.isActive == true
    @Volatile private var appCtx: Context? = null

    /**
     * Har kaam isi se chalao. Kaam KHATAM hone ke baad (busy = false) screen dobara banti hai —
     * pehle listener kaam ke andar hi chalta tha (tab busy = true), isliye "Merge करें" card chhupa reh jaata tha.
     */
    fun launch(c: Context, block: suspend CoroutineScope.() -> Unit): Job {
        appCtx = c.applicationContext
        val j = scope.launch(block = block)
        job = j
        j.invokeOnCompletion { scope.launch { savePr(appCtx); listener?.invoke() } }
        return j
    }

    /** Khula PR yaad rakho - app band/restart hone par bhi "Merge करें" wapas mile */
    fun savePr(c: Context?) {
        val ctx = c ?: return
        val a = agent ?: return
        val p = a.pr
        Prefs.edit(ctx) {
            if (p != null) {
                putString("coder_pr_repo", a.repo); putString("coder_pr_base", a.baseBranch)
                putInt("coder_pr_num", p.number); putString("coder_pr_url", p.url); putString("coder_pr_head", a.branch)
            } else { remove("coder_pr_repo"); remove("coder_pr_base"); remove("coder_pr_num"); remove("coder_pr_url"); remove("coder_pr_head") }
        }
    }

    private fun restorePr(c: Context, a: CodingAgent) {
        if (!a.safePr || Prefs.coderPrRepo(c) != a.repo || Prefs.coderPrBase(c) != a.baseBranch) return
        val n = Prefs.coderPrNum(c); val head = Prefs.coderPrHead(c)
        if (n <= 0 || SafePr.isBlocked(head, a.baseBranch)) return
        a.restorePr(GitHub.Pull(n, Prefs.coderPrUrl(c), head, a.baseBranch))
    }

    /** Kya kami hai (screen aur awaaz dono ke liye) */
    fun problems(c: Context): List<String> = buildList {
        if (!Prefs.coderOn(c)) add("कोडिंग एजेंट बंद है — चालू करें")
        if (Prefs.ghToken(c).isBlank()) add("GitHub token नहीं डाला")
        if (!Prefs.ghRepo(c).contains('/')) add("Repo चुनें (owner/naam)")
        if (CoderAi.fromPrefs(c) == null) add("कोडिंग के लिए AI key नहीं मिली — Settings → AI दिमाग → कोडिंग एजेंट में डालें")
    }

    /** Ek hi agent (staged badlaav + baatcheet) - screen ho ya awaaz */
    fun agentFor(c: Context): CodingAgent? = applySteps(c, agentOrNew(c))

    private fun agentOrNew(c: Context): CodingAgent? {
        val gh = GitHub.fromPrefs(c) ?: return null
        val ai = CoderAi.fromPrefs(c) ?: return null
        val repo = Prefs.ghRepo(c); val br = Prefs.ghBranch(c)
        val a = agent
        // baseBranch = aapki chuni (main); kaam wali feature branch alag ho sakti hai
        if (a != null && a.repo == repo && a.baseBranch == br) return a.also { if (it.pr == null) it.safePr = Prefs.coderSafePr(c) }
        return CodingAgent(gh, repo, br, ai).also { it.safePr = Prefs.coderSafePr(c); restorePr(c, it); agent = it }
    }

    /** Settings वाले कदम हर काम से पहले (agent पहले से बना हो तो भी) */
    private fun applySteps(c: Context, a: CodingAgent?): CodingAgent? = a?.also { it.maxSteps = Prefs.coderMaxSteps(c) }
}

class CodingActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sub: TextView
    private lateinit var edt: EditText
    private lateinit var send: ImageButton
    private lateinit var changes: LinearLayout
    private lateinit var sugBox: LinearLayout
    private val speech = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == RESULT_OK && !t.isNullOrBlank()) { edt.setText(t); doSend() }
    }
    private var shown = 0
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // likhne ki jagah hamesha khuli: keyboard turant aur bhejne ke baad bhi
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (14 * dp).toInt(); setPadding(p, p, p, p) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply { text = "कोडिंग एजेंट"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        sub = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); maxLines = 2 }
        titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(smallBtn("रिपो") { chooseRepo() })
        top.addView(smallBtn("नया") { newSession() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() })
        col.addView(top)

        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (10 * dp).toInt() })

        // staged badlaav + Push / Hatao
        changes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); visibility = View.GONE
        }
        col.addView(changes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })

        // टीम के सुझाव (जो आपने नहीं माँगे) - हाँ/नहीं
        sugBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); visibility = View.GONE
        }
        col.addView(sugBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_pill); setPadding((18 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        edt = EditText(this).apply {
            hint = "क्या बदलना है? (जैसे: Settings में dark mode switch जोड़ो)"
            setHintTextColor(0x66FFFFFF); setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            background = null; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        bar.addView(edt, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() })
        // 🎤 बोलकर काम बताओ (फ़ोन की आवाज़-पहचान; बोलते ही भेज देता है)
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic); setBackgroundResource(R.drawable.glass_circle)
            val p = (11 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "बोलकर बताएँ"
            setOnClickListener { voiceInput() }
        }, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() })
        send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send); setBackgroundResource(R.drawable.btn_accent_circle)
            val p = (11 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "भेजें"
        }
        bar.addView(send, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })

        send.setOnClickListener { if (CoderSession.busy) stopJob() else doSend() }
        edt.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
        setContentView(root)
        Glass.install(this)
        if (CoderSession.lines.isEmpty()) CoderSession.add("d", welcome())
        keepTyping()
    }

    private fun welcome(): String {
        val problems = problems()
        return if (problems.isEmpty() && Prefs.coderTeam(this))
            "नमस्ते। कोडिंग टीम तैयार है — ${Prefs.ghRepo(this)} (${Prefs.ghBranch(this)}).\n" +
                "एक बार बताइए (लिखकर या 🎤 से) क्या बनाना है। बिल्डर लिखेगा → रिव्यूअर खुद जाँचेगा → गड़बड़ी हो तो वापस ठीक → अपने-आप push → GitHub tests + build जाँच → पूरा होने पर बताऊँगा और \"Merge करें\" बटन दिखेगा (merge होते ही नई APK release)।\n" +
                "कोई अतिरिक्त चीज़ ज़रूरी लगी तो खुद नहीं जोड़ूँगा — आपसे पूछूँगा।" + safeNote()
        else if (problems.isEmpty())
            "नमस्ते। मैं ${Prefs.ghRepo(this)} (${Prefs.ghBranch(this)}) पर काम करूँगा।\nबताइए क्या बनाना/बदलना/हटाना है। मैं पहले बदलाव दिखाऊँगा, फिर \"Push करें\" दबाने पर GitHub पर भेजूँगा" +
                (if (Prefs.coderAutoPush(this)) " (अभी: बिना पूछे push चालू)।" else "।") + safeNote()
        else "पहले ये ठीक करें (सेटिंग्स → कोडिंग एजेंट):\n• " + problems.joinToString("\n• ")
    }

    private fun safeNote() = if (Prefs.coderSafePr(this))
        "\n🔒 सुरक्षित PR मोड: ${Prefs.ghBranch(this)} पर सीधे push नहीं — नई feature branch → Pull Request → tests + build → आप review करके \"Merge करें\" दबाएँ।"
    else "\n⚠ सुरक्षित PR मोड बंद है — बदलाव सीधे ${Prefs.ghBranch(this)} पर जाएँगे।"

    private fun problems(): List<String> = CoderSession.problems(this)

    /** Keyboard + cursor hamesha likhne ki jagah par */
    private fun keepTyping() {
        edt.requestFocus()
        edt.post {
            edt.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(edt, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun agent(): CodingAgent? = CoderSession.agentFor(this)

    private fun doSend() {
        val t = edt.text.toString().trim()
        if (t.isEmpty() || CoderSession.busy) return
        edt.setText("")
        keepTyping()
        val p = problems()
        if (p.isNotEmpty()) { CoderSession.add("u", t); CoderSession.add("e", "पहले ठीक करें:\n• " + p.joinToString("\n• ")); return }
        // टीम मोड: बिल्डर -> रिव्यूअर -> कोऑर्डिनेटर, सब अपने-आप
        if (Prefs.coderTeam(this)) { CoderTeam.start(this, t)?.let { CoderSession.add("e", it) }; render(); return }
        val a = agent() ?: return
        CoderSession.add("u", t)
        CoderSession.launch(this) {
            try {
                CoderReport.begin()
                // main पर कभी सीधे नहीं: कोड छूने से पहले नई feature branch; पुराना फ़ीचर बदलना/हटाना = पहले backup
                if (a.safePr) a.ensureBranch(t)
                a.intentional = SafePr.changesOldFeature(t)
                if (a.intentional) a.backupOld(t).takeIf { it.isNotBlank() }?.let { CoderSession.add("s", "पुराने फ़ीचर का backup लिया → $it") }
                fixSigs.clear()
                val out = a.run(t) { step -> CoderSession.scope.launch { CoderSession.add("s", step) } }
                CoderSession.lastCommit = out.commit
                if (out.stopped) {
                    // एजेंट खुद रुका: अपने-आप push नहीं, वजह एक बार रिपोर्ट में
                    CoderSession.add("e", "⚠ काम अधूरा\n\n" + CoderReport.final(false, out.summary + "\n(बदलाव push नहीं किए)"))
                } else {
                    val partial = out.commit == "DK: partial changes"
                    CoderSession.add("d", when {
                        out.question -> out.summary
                        partial -> "⚠ काम अधूरा\n\n" + CoderReport.final(false, out.summary)
                        else -> out.summary + "\n\n" + CoderReport.final(true, out.summary)
                    })
                    if (!out.question && a.staged.isNotEmpty() && Prefs.coderAutoPush(applicationContext)) push(applicationContext)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                CoderSession.add("e", "रोक दिया।")
            } catch (e: Exception) {
                val m = (e.message ?: e.javaClass.simpleName).take(300)
                CoderSession.add("e", "⚠ काम अधूरा\n\n" + CoderReport.final(false, m))
                DKLog.d("Coder गलती: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { try { a.dropEmptyBranch() } catch (_: Exception) {} }
                CoderSession.listener?.invoke()
            }
        }
        render()
    }

    /** इस काम में build/test fail की पहचान (वही गलती बार-बार = अटक गए) */
    private val fixSigs = mutableListOf<String>()

    private fun stopJob() { CoderSession.job?.cancel() }

    private fun voiceInput() {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "क्या बनाना है? बोलिए…")
        try { speech.launch(i) } catch (_: Exception) { toast("फ़ोन में आवाज़-पहचान नहीं मिली") }
    }

    /** Pending सुझाव: क्या, क्यों, और हाँ/नहीं */
    private fun renderSuggestions() {
        sugBox.removeAllViews()
        val list = CoderTeam.pending
        if (list.isEmpty()) { sugBox.visibility = View.GONE; return }
        sugBox.visibility = View.VISIBLE
        sugBox.addView(TextView(this).apply {
            text = "💡 टीम का सुझाव (आपने नहीं माँगा था — हाँ कहें तभी जोड़ेंगे)"; textSize = 13f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        list.take(3).forEach { sg ->
            sugBox.addView(TextView(this).apply {
                text = sg.title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            if (sg.why.isNotBlank()) sugBox.addView(TextView(this).apply { text = sg.why; textSize = 13f; setTextColor(0xD9FFFFFF.toInt()) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
            row.addView(smallBtn("हाँ, जोड़ो", accent = true) { CoderTeam.decide(applicationContext, sg.id, true); render() },
                LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(smallBtn("नहीं") { CoderTeam.decide(applicationContext, sg.id, false); render() },
                LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f))
            sugBox.addView(row)
        }
    }

    private suspend fun push(c: Context, round: Int = 0) {
        val a = CoderSession.agent ?: return
        if (a.staged.isEmpty()) return
        CoderSession.add("s", "GitHub पर push कर रहा हूँ…")
        try {
            val n = a.staged.size
            val url = a.push(CoderSession.lastCommit)
            CoderSession.add("d", "Push हो गया ($n फ़ाइल) → ${a.branch}\n$url" + prLine(a))
            DKLog.d("Coder push: $url")
        } catch (e: Exception) {
            CoderSession.add("e", "Push नहीं हुआ: " + (e.message ?: "").take(300))
            return
        }
        if (Prefs.coderBuildFix(c)) try { watchAndFix(c, round) }
            catch (e: kotlinx.coroutines.CancellationException) { CoderSession.add("s", "Build देखना रोका।"); throw e }
            catch (e: Exception) { CoderSession.add("e", "Build जाँच/सुधार में गलती: " + (e.message ?: e.javaClass.simpleName).take(300)) }
    }

    private fun prLine(a: CodingAgent): String = when {
        a.pr != null -> "\n🔀 PR #${a.pr!!.number} (${a.branch} → ${a.baseBranch}): ${a.pr!!.url}"
        a.safePr && a.prError.isNotBlank() -> "\n⚠ PR नहीं बना: ${a.prError}"
        else -> ""
    }

    /** Review ke baad: pakka poochho, phir merge (sirf build/tests paas hon to) */
    private fun confirmMerge(a: CodingAgent) {
        val p = a.pr ?: return
        android.app.AlertDialog.Builder(this, Glass.DIALOG)
            .setTitle("PR #${p.number} merge करें?")
            .setMessage("${a.branch} → ${a.baseBranch}\n\nक्या आपने GitHub पर बदलाव review कर लिए? Merge तभी होगा जब PR के unit tests + build पास हों।")
            .setPositiveButton("Merge करें") { _, _ ->
                // जाँच (tests/build/conflict) → merge → main पर release APK build देखना → बताना
                CoderTeam.merge(this)?.let { CoderSession.add("e", it) }
                render()
            }
            .setNegativeButton("अभी नहीं", null).show()
    }

    /** Push ke baad GitHub Actions build dekho; fail ho to jad (root cause) dhundhkar agent khud theek kare - paas hone tak (atke to ruke, dekhें FixLoop) */
    private suspend fun watchAndFix(c: Context, round: Int) {
        val a = CoderSession.agent ?: return
        CoderSession.add("s", "GitHub पर build देख रहा हूँ…")
        val b = a.watchBuild(a.lastSha) { st -> CoderSession.scope.launch { CoderSession.add("s", st) } }
        if (b == null) { CoderSession.add("s", "इस repo के build की जानकारी नहीं मिली"); return }
        if (b.ok) { CoderSession.add("d", "Build सफल ✓\n${b.url}"); return }
        CoderSession.add("e", "Build fail हुआ ✗\n${b.url}\n\n" + b.log.lines().filter { it.startsWith("e: ") || it.contains("error", true) }.take(6).joinToString("\n").take(700))
        fixSigs.add(FixLoop.signature(b.log))
        when (FixLoop.next(fixSigs)) {
            FixLoop.Next.STOP_CAP -> { CoderSession.add("e", "${fixSigs.size} बार जड़ ढूँढकर ठीक करने के बाद भी build/tests fail हैं। ऊपर की गलती देखकर बताइए क्या करना है।"); return }
            FixLoop.Next.STOP_STUCK -> { CoderSession.add("e", "वही गलती ${FixLoop.SAME_LIMIT} बार लगातार लौटी — एजेंट अटक गया, इसलिए रोका। इस गलती के बारे में और बताइए।"); return }
            FixLoop.Next.FIX_AGAIN -> {}
        }
        CoderSession.add("s", "पहले गलती की जड़ ढूँढ रहा हूँ, फिर ठीक करके दोबारा test (राउंड ${round + 1})…")
        val out = a.run("BUILD FAILED on GitHub Actions after your last push (commit ${a.lastSha.take(7)}). " +
            "STEP 1: find the ROOT CAUSE in this log (read the mentioned files/lines, understand WHY). STEP 2: fix ONLY that cause, never delete/weaken tests, give root_cause in done.\nBUILD LOG:\n${b.log}") { step ->
            CoderSession.scope.launch { CoderSession.add("s", step) }
        }
        CoderSession.add("d", out.summary)
        CoderSession.lastCommit = out.commit.ifBlank { "Fix build" }
        if (out.question || a.staged.isEmpty()) return
        if (Prefs.coderAutoPush(c)) push(c, round + 1)
        else CoderSession.add("s", "Build ठीक करने वाले बदलाव तैयार हैं — नीचे \"Push करें\" दबाइए")
    }

    /** Staged badlaav ka rangeen diff (+ hara, − laal) */
    private fun showDiff(a: CodingAgent) {
        val d = a.diffAll().ifBlank { "(कोई बदलाव नहीं)" }.take(120_000)
        val sp = android.text.SpannableStringBuilder()
        d.lines().forEach { l ->
            val st = sp.length
            sp.append(l).append('\n')
            val col = when {
                l.startsWith("+++") || l.startsWith("---") || l.startsWith("===") -> 0xFF8AB4FF.toInt()
                l.startsWith("@@") -> 0xFFB39DDB.toInt()
                l.startsWith("+") -> 0xFF7EE787.toInt()
                l.startsWith("-") -> 0xFFFF8A80.toInt()
                else -> 0xB3FFFFFF.toInt()
            }
            sp.setSpan(android.text.style.ForegroundColorSpan(col), st, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val tv = TextView(this).apply {
            text = sp; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        }
        val hs = android.widget.HorizontalScrollView(this).apply { addView(tv) }
        val vs = ScrollView(this).apply { addView(hs) }
        android.app.AlertDialog.Builder(this, Glass.DIALOG)
            .setTitle("बदलाव (${a.staged.size} फ़ाइल)").setView(vs).setPositiveButton("ठीक है", null).show()
    }

    private fun newSession() {
        if (CoderSession.busy) { toast("पहले चल रहा काम रोकें"); return }
        CoderSession.agent?.reset(); CoderSession.savePr(this); CoderSession.agent = null
        CoderTeam.suggestions.clear()
        CoderSession.lines.clear(); shown = 0; list.removeAllViews()
        CoderSession.add("d", welcome())
    }

    private fun chooseRepo() {
        val gh = GitHub.fromPrefs(this) ?: run { toast("पहले सेटिंग्स में GitHub token डालें"); return }
        toast("Repos ला रहा हूँ…")
        CoderSession.scope.launch {
            try {
                val repos = withContext(Dispatchers.IO) { gh.repos() }
                if (isFinishing || isDestroyed) return@launch
                if (repos.isEmpty()) { toast("कोई repo नहीं मिला"); return@launch }
                val names = repos.map { it.fullName + if (it.private) "  (private)" else "" }.toTypedArray()
                android.app.AlertDialog.Builder(this@CodingActivity, Glass.DIALOG)
                    .setTitle("Repo चुनें")
                    .setItems(names) { _, i ->
                        val r = repos[i]
                        if (CoderSession.agent?.staged?.isNotEmpty() == true) toast("पुराने repo के बिना push बदलाव हटा दिए")
                        Prefs.edit(this@CodingActivity) { putString("gh_repo", r.fullName); putString("gh_branch", r.branch) }
                        newSession()
                    }
                    .setNegativeButton("बंद करें", null).show()
            } catch (e: Exception) { toast((e.message ?: "गलती").take(120)) }
        }
    }

    override fun onResume() {
        super.onResume()
        CoderSession.listener = { runOnUiThread { render() } }
        // app दोबारा खुली हो तो पिछला खुला PR (Merge card) वापस लाओ
        if (CoderSession.agent == null && problems().isEmpty()) CoderSession.agentFor(this)
        render(); keepTyping()
    }

    override fun onPause() { CoderSession.listener = null; super.onPause() }

    private fun render() {
        val a = CoderSession.agent
        val ai = CoderAi.fromPrefs(this)
        sub.text = "${Prefs.ghRepo(this).ifBlank { "repo नहीं चुना" }} • ${a?.branch ?: Prefs.ghBranch(this)} • ${ai?.label ?: "AI नहीं"}" +
            if (CoderSession.busy) " • काम चल रहा है…" else ""
        send.setImageResource(if (CoderSession.busy) android.R.drawable.ic_media_pause else R.drawable.ic_send)
        send.contentDescription = if (CoderSession.busy) "रोकें" else "भेजें"
        val lines = CoderSession.lines
        if (lines.size < shown) { list.removeAllViews(); shown = 0 }
        while (shown < lines.size) { bubble(lines[shown]); shown++ }
        renderSuggestions()
        // badlaav card
        changes.removeAllViews()
        if (a != null && a.staged.isNotEmpty() && !CoderSession.busy) {
            changes.visibility = View.VISIBLE
            changes.addView(TextView(this).apply {
                text = "बदलाव (अभी push नहीं हुए) — ${a.staged.size} फ़ाइल"; textSize = 14f
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            changes.addView(TextView(this).apply {
                text = a.changeList(); textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); typeface = Typeface.MONOSPACE
                setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt()); maxLines = 8
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallBtn("Push करें", accent = true) {
                android.app.AlertDialog.Builder(this, Glass.DIALOG)
                    .setTitle("GitHub पर push करें?")
                    .setMessage("${a.repo} • " + (if (a.safePr && a.pr == null) "नई branch (feature/dk-…) → PR → ${a.baseBranch}" else a.branch) +
                        "\n\n${a.changeList()}\n\nCommit: ${CoderSession.lastCommit.ifBlank { "DK: code changes" }}" +
                        SafePr.protectedIn(a.staged.keys).let { if (it.isEmpty()) "" else "\n\n⚠ संवेदनशील फ़ाइलें: " + it.joinToString() })
                    .setPositiveButton("Push करें") { _, _ -> CoderSession.launch(this) { push(applicationContext) }; render() }
                    .setNegativeButton("रुको", null).show()
            }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(smallBtn("फ़र्क देखें") { showDiff(a) }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(smallBtn("हटाओ") {
                a.discard(); CoderSession.add("s", "सारे बदलाव हटा दिए (GitHub पर कुछ नहीं बदला)")
            }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
            changes.addView(row)
        } else if (a != null && a.pr != null && !CoderSession.busy) {
            // PR तैयार: review करें, फिर Merge
            val p = a.pr!!
            changes.visibility = View.VISIBLE
            changes.addView(TextView(this).apply {
                text = "🔀 PR #${p.number} review के लिए तैयार"; textSize = 14f
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            changes.addView(TextView(this).apply {
                text = "${a.branch} → ${a.baseBranch}\nGitHub पर बदलाव देखें; tests + build पास हों तो Merge करें (या DK से बोलें: \"PR merge कर दो\")। Merge होते ही नई APK release बनेगी। और बदलाव चाहिए तो बस लिखिए — उसी PR में जुड़ेंगे।"
                textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallBtn("PR खोलें") { Setup.openUrl(this, p.url) },
                LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(smallBtn("Merge करें", accent = true) { confirmMerge(a) }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
            changes.addView(row)
        } else if (a != null && a.canRetryPr && !CoderSession.busy) {
            // Push हो गया पर PR नहीं बना (अक्सर token की इजाज़त) - वजह + दोबारा कोशिश, ताकि Merge तक पहुँचें
            changes.visibility = View.VISIBLE
            changes.addView(TextView(this).apply {
                text = "⚠ PR नहीं बना — इसलिए Merge का बटन नहीं है"; textSize = 14f
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            changes.addView(TextView(this).apply {
                text = "${a.branch} → ${a.baseBranch}\n${a.prError}"
                textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallBtn("PR फिर बनाएँ", accent = true) {
                CoderSession.launch(this) {
                    CoderSession.add("s", "PR दोबारा बना रहा हूँ…")
                    try { val r = a.retryPr(); CoderSession.add(if (a.pr != null) "d" else "e", r) }
                    catch (e: Exception) { CoderSession.add("e", "PR नहीं बना: " + (e.message ?: e.javaClass.simpleName).take(300)) }
                }
                render()
            }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(smallBtn("GitHub पर बनाएँ") { Setup.openUrl(this, "https://github.com/${a.repo}/compare/${a.baseBranch}...${a.branch}?expand=1") },
                LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
            changes.addView(row)
        } else changes.visibility = View.GONE
    }

    private fun bubble(l: CoderSession.Line) {
        val mine = l.role == "u"
        val tv = TextView(this).apply {
            text = l.text; setTextIsSelectable(true)
            when (l.role) {
                "s" -> { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding((6 * dp).toInt(), (2 * dp).toInt(), 0, 0) }
                else -> {
                    textSize = 15f; setTextColor(if (l.role == "e") 0xFFFFB4B4.toInt() else 0xFFFFFFFF.toInt())
                    setBackgroundResource(if (mine) R.drawable.glass_bubble_me else R.drawable.glass_bubble_dk)
                    val ph = (14 * dp).toInt(); val pv = (10 * dp).toInt(); setPadding(ph, pv, ph, pv)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
                }
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = ((if (l.role == "s") 2 else 8) * dp).toInt()
        }
        list.addView(tv, lp)
        scroll.post { scroll.smoothScrollTo(0, list.height) }    // fullScroll() focus chheen leta hai - nahi
    }

    private fun smallBtn(t: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(if (accent) R.drawable.btn_accent else R.drawable.glass_pill)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (12 * dp).toInt(); setPadding(ph, (6 * dp).toInt(), ph, (6 * dp).toInt())
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
