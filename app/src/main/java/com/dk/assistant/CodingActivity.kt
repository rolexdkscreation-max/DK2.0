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
import android.widget.ProgressBar
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
    /** 7-एजेंट: टोकन/रिपो की दिक्कत पर रुका काम - "नॉर्मल मोड में करो" का विकल्प */
    @Volatile var offerNormal: String? = null
    /** v5.1: अभी कौन सा एजेंट काम कर रहा है (स्क्रीन पर नाम + animation) */
    @Volatile var activeAgent: Seven.Member? = null
    @Volatile var activeNote: String = ""
    /** 7-एजेंट नॉर्मल मोड: पिछले जवाबों की फ़ाइलें (अगले संदेश में आगे काम के लिए) */
    val normalFiles = LinkedHashMap<String, String>()
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile var listener: (() -> Unit)? = null
    /** नवंबर 2026 (स्मूथ): 300 से ऊपर होने पर आगे से कितनी लाइनें हटीं - स्क्रीन इसी से नई लाइनें पहचानती है */
    @Volatile var dropped = 0L
        private set
    fun add(role: String, t: String) {
        // नवंबर 2026 (crash): lines सिर्फ़ main thread पर बदले (स्क्रीन उसी समय पढ़ती है - दो thread = crash/स्क्रीन डूबना)
        val main = try { android.os.Looper.getMainLooper() } catch (_: Throwable) { null }
        if (main != null && android.os.Looper.myLooper() != main) { android.os.Handler(main).post { add(role, t) }; return }
        // एक ही संदेश दो बार नहीं (हर कदम पर वही चेतावनी/गलती)
        if (lines.takeLast(3).any { it.role == role && it.text == t }) return
        lines.add(Line(role, t)); if (lines.size > 300) { lines.removeAt(0); dropped++ }; listener?.invoke()
        if (role == "s") TaskStatus.step(TaskStatus.CODER, t)      // असली कदम (live status)
        CoderHistory.onLine(role, t)                                // इतिहास: फ़ोन में (ऐप बंद होकर खुले तो वही बातचीत)
    }

    init {
        // सारी limit खत्म होने पर इंतज़ार की खबर कोडिंग स्क्रीन पर (काम रुका नहीं, रुककर चल रहा है)
        CoderAi.onLimitWait = { t -> scope.launch { add("s", t) } }
    }
    val busy get() = job?.isActive == true
    /** अभी चल रहा काम (जाँचकर्ता को बताने के लिए) */
    @Volatile var task: String? = null
    /** अगले संदेश के साथ जाने वाली फ़ोटो/वीडियो (typing bar के ऊपर दिखती हैं) + छोटी तस्वीर */
    val media = mutableListOf<Pair<CoderMedia, android.graphics.Bitmap?>>()
    fun takeMedia(): List<CoderMedia> = media.map { it.first }.also { media.clear() }
    @Volatile private var appCtx: Context? = null

    // ---- नवंबर 2026 (मालिक): काम चलते समय नई बात - उसी काम की = साथ में, अलग = यह पूरा होने के बाद
    /** उसी काम में जोड़ी बातें (टीम / एजेंट अगले कदम पर उठाते हैं) */
    val inbox = FollowUp.Inbox()
    /** अलग काम - अभी वाला पूरा होते ही क्रम से */
    val queue = ArrayDeque<String>()
    /** बीच में भेजी आख़िरी बात कहाँ गई (बटन से पलटने के लिए) */
    @Volatile var routed: FollowUp.Routed? = null
    /** अभी चल रहे कोडिंग काम की बात (किसी भी रास्ते से शुरू हुआ) */
    @Volatile var current: String? = null
    /** स्क्रीन खुली हो तो कतार का काम उसी के भेजने वाले रास्ते से (सारे नियम वही) */
    @Volatile var starter: ((String) -> Unit)? = null

    /** काम चलते समय user की नई बात. [canJoin] = यह काम बीच में नई बात ले सकता है (पुरानी 3-एजेंट टीम नहीं) */
    fun followUp(c: Context, text: String, shown: String = text, canJoin: Boolean = true) {
        add("u", shown)
        if (canJoin && FollowUp.route(current, text) == FollowUp.Route.NOW) {
            routed = FollowUp.Routed(inbox.add(text), text, FollowUp.Route.NOW)
            add("s", FollowUp.ADDED_NOW)
        } else {
            queue.addLast(text)
            routed = FollowUp.Routed(0, text, FollowUp.Route.NEXT).takeIf { canJoin }
            add("s", FollowUp.ADDED_NEXT + " (कतार में ${queue.size})")
        }
        listener?.invoke()
    }

    /** बीच में भेजी बात का रास्ता पलटो: साथ में ⇄ बाद में */
    fun reroute() {
        val r = routed ?: return
        if (r.route == FollowUp.Route.NOW) {
            val t = inbox.take(r.id)
            if (t == null) { routed = null; add("s", "यह बात काम में पहले ही जुड़ चुकी है — अब अलग नहीं हो सकती।"); return }
            queue.addLast(t); routed = r.copy(route = FollowUp.Route.NEXT)
            add("s", "⏭ ठीक है — इसे अलग काम माना, अभी वाला पूरा होते ही होगा।")
        } else {
            if (!queue.remove(r.text)) { routed = null; listener?.invoke(); return }
            routed = FollowUp.Routed(inbox.add(r.text), r.text, FollowUp.Route.NOW)
            add("s", "🔗 ठीक है — इसे चल रहे काम में जोड़ दिया।")
        }
    }

    /** कतार का अगला काम (कोई काम न चल रहा हो और टीम \"हाँ\" का इंतज़ार न कर रही हो) */
    fun runNext(c: Context) {
        if (busy || SevenRunner.pending != null) return
        val t = queue.removeFirstOrNull() ?: return
        add("s", "▶ कतार का अगला काम शुरू: ${t.lineSequence().first().take(120)}")
        val s = starter
        if (s != null) s(t)
        else if (Prefs.coderSeven(c)) SevenRunner.start(c, t)?.let { add("e", it) }
        else queue.addFirst(t)          // पुराना एजेंट: स्क्रीन खुलते ही चलेगा
    }

    /**
     * Har kaam isi se chalao. Kaam KHATAM hone ke baad (busy = false) screen dobara banti hai —
     * pehle listener kaam ke andar hi chalta tha (tab busy = true), isliye "Merge करें" card chhupa reh jaata tha.
     */
    fun launch(c: Context, task: String? = null, block: suspend CoroutineScope.() -> Unit): Job {
        appCtx = c.applicationContext
        // task = कोडिंग का असली काम (live status में दिखे). merge/push जैसे छोटे काम: null
        if (task != null) TaskStatus.start(TaskStatus.CODER, "कोडिंग टीम", "the coding team", task)
        this.task = task
        if (task != null) current = task
        val from = lines.size
        val j = scope.launch(block = block)
        job = j
        if (task != null) CoderHistory.setStatus(c.applicationContext, "run")
        j.invokeOnCompletion { cause ->
            if (task != null) appCtx?.let { ctx ->
                val last = lines.drop(from.coerceAtMost(lines.size)).lastOrNull { it.role == "d" || it.role == "e" }
                CoderHistory.setStatus(ctx, CoderHistory.Logic.statusAfter(last?.role, cause is kotlinx.coroutines.CancellationException, SevenRunner.pending != null))
            }
            if (task != null) {
                val last = lines.drop(from.coerceAtMost(lines.size)).lastOrNull { it.role == "d" || it.role == "e" }
                TaskStatus.end(TaskStatus.CODER, TaskStatus.coderState(last?.role, last?.text, cause is kotlinx.coroutines.CancellationException),
                    last?.text.orEmpty().lines().filter { it.isNotBlank() }.take(4).joinToString(" • "))
            }
            scope.launch {
                // बीच में जोड़ी बात उठी नहीं (काम खत्म हो गया) = छूटे नहीं, अगला काम बनकर कतार में सबसे आगे
                val doing = current
                inbox.drain().asReversed().forEach { queue.addFirst(FollowUp.leftover(doing, it)) }
                routed = null; current = null
                savePr(appCtx); listener?.invoke()
                if (cause is kotlinx.coroutines.CancellationException) {
                    if (queue.isNotEmpty()) add("s", "कतार के ${queue.size} काम भी हटा दिए")
                    queue.clear()
                } else appCtx?.let { runNext(it) }
            }
        }
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
        if (a != null && a.repo == repo && a.baseBranch == br && a.aiNote == ai.autoTag) return a.also { if (it.pr == null) it.safePr = Prefs.coderSafePr(c) }
        return CodingAgent(gh, repo, br, ai).also { it.safePr = Prefs.coderSafePr(c); restorePr(c, it); agent = it }
    }

    /** Settings वाले कदम हर काम से पहले (agent पहले से बना हो तो भी) */
    private fun applySteps(c: Context, a: CodingAgent?): CodingAgent? = a?.also { it.maxSteps = Prefs.coderMaxSteps(c) }
}

class CodingActivity : AppCompatActivity() {

    /** नवंबर 2026 (मालिक): साइड में लाइव कोड - AI जो फ़ाइल लिख रहा है, पूरी दिखती है (रंग बदलने का विकल्प) */
    private lateinit var codePanel: CodeLivePanel
    private lateinit var codeBtn: android.widget.Button
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var edt: EditText
    private lateinit var send: ImageButton
    private lateinit var changes: LinearLayout
    private lateinit var sugBox: LinearLayout
    /** नवंबर 2026: "आगे क्या करें" के बटन (डिब्बे के ऊपर) */
    private lateinit var choiceBar: ChoiceBar
    // नवंबर 2026 (मालिक): काम चलते समय ऊपर की हेडर पट्टी में ही "मौजूदा काम" (अलग कार्ड नहीं)
    private lateinit var headTitle: TextView
    private lateinit var headTask: LinearLayout
    private lateinit var busyTask: TextView
    private lateinit var agentWork: AgentWorkView
    private val busyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var busyStep = 0
    private var busyOn = false
    private val busyTick = object : Runnable {
        override fun run() {
            if (!CoderSession.busy) { showBusy(false); return }
            busyStep++
            if (inline.active) inline.show(busyLabel(), WorkState.WORKING)
            fillTaskCard()
            showAgent()
            busyHandler.postDelayed(this, BusyLabel.TICK_MS)
        }
    }
    // DK का काम = सूची के आख़िर में जवाब वाले बुलबुले के अंदर तरंग (नीचे/ऊपर अलग animation नहीं)
    private lateinit var inline: DKInlineReply
    private lateinit var composer: DKComposer
    private var busySince = 0L
    private var shown = 0
    /** नवंबर 2026 (स्मूथ): CoderSession.dropped का आख़िरी देखा मान */
    private var seenDrop = 0L
    /** एक frame में एक ही render (हर लाइन पर पूरा render = अटकना) */
    private var renderQueued = false
    private val renderNow = Runnable { renderQueued = false; if (!isDestroyed) render() }
    private fun scheduleRender() { if (renderQueued) return; renderQueued = true; list.postOnAnimation(renderNow) }
    private val dp get() = resources.displayMetrics.density
    // 📎 फ़ोटो/वीडियो: typing bar के ऊपर छोटी पट्टी (भेजने तक)
    private lateinit var mediaScroll: android.widget.HorizontalScrollView
    private lateinit var mediaRow: LinearLayout
    private val pickMedia = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(CoderMedia.MAX_ITEMS)) { uris ->
        addMedia(uris)
    }

    /** नवंबर 2026: फ़ोन और टैब पर एक जैसे अक्षर (बहुत बड़े system font से लेआउट नहीं टूटता) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(UiFit.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // likhne ki jagah hamesha khuli: keyboard turant aur bhejne ke baad bhi
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        // नवंबर 2026 (मालिक): पूरी स्क्रीन भरी - किनारे की जगह डिवाइस के हिसाब से (UiFit), notch / नीचे की पट्टी से बचाव
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val e = UiFit.edgePx(this@CodingActivity); val p = (14 * dp).toInt(); setPadding(e, p, e, (10 * dp).toInt()) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        UiFit.safeArea(root)

        // नवंबर 2026 (मालिक): फ़ोन पर बटन नाम को दबा देते थे - अब दो लाइन (UiFit.header), बटन कभी कटते नहीं
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        // नवंबर 2026 (मालिक): Futuristic रूप - चमकदार "DK CODING AGENT" (मालिक: ऊपर सिर्फ़ काँच की पट्टी + नाम, status टैग नहीं)
        // नवंबर 2026 (मालिक): हेडर का आकार वही - खाली = "DK CODING AGENT", काम चालू = उसी जगह मौजूदा काम + घूमता निशान
        headTitle = FutureViews.title(this, Prefs.assistantName(this).uppercase() + " CODING AGENT").apply { gravity = Gravity.CENTER }
        busyTask = TextView(this).apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this); gravity = Gravity.CENTER_VERTICAL
        }
        headTask = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.INVISIBLE; alpha = 0f
            addView(ProgressBar(this@CodingActivity).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(FutureViews.CYAN)
            }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            addView(busyTask, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
        }
        // दोनों एक ही जगह (FrameLayout) - नाम हटे तो भी जगह/ऊँचाई वैसी ही रहती है
        val headSlot = FrameLayout(this).apply {
            addView(headTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addView(headTask, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        titles.addView(headSlot, LinearLayout.LayoutParams(-1, -2))
        val top = UiFit.header(this, back, titles, listOf(
            smallBtn("रिपो") { if (Prefs.coderSeven(this)) sevenPicker() else chooseRepo() },
            smallBtn("इतिहास") { showHistory() },
            smallBtn("नया") { newSession() },
            smallBtn(CodeLivePanel.label(0)) { codePanel.toggle() }.also { codeBtn = it },
            smallBtn("स्टूडियो") { com.dk.assistant.studio.StudioSession.open(this, null) }))
        FutureViews.glassTopBar(top)      // संदर्भ चित्र: ऊपर काँच की पट्टी (सिर्फ़ नाम)
        col.addView(top)
        // नवंबर 2026 (मालिक): Codespaces के महीने के फ़्री घंटे - छोटी पट्टी (कितना इस्तेमाल / कितना बाकी). छूने पर पूरा ब्योरा + ताज़ा
        csMeter = TextView(this).apply {
            textSize = 11f; setTextColor(FutureViews.CYAN); gravity = Gravity.CENTER
            val p = (4 * dp).toInt(); setPadding(p, p, p, p)
            text = CodespaceMeter.line(null)
            setOnClickListener { codespaceDetail() }
        }
        col.addView(csMeter, LinearLayout.LayoutParams(-1, -2))

        // नवंबर 2026 (मालिक): बड़ा "मौजूदा काम" कार्ड हटाया - वह अब ऊपर हेडर में; उसकी जगह (बटनों के नीचे) एजेंट स्टेटस पट्टी
        // v5.1: कौन सा एजेंट काम कर रहा है - सारे एजेंट मैनेजर के नीचे, चलता हुआ चमकता है
        agentWork = AgentWorkView(this).apply { visibility = View.GONE; setBackgroundResource(R.drawable.glass_card); val p = (8 * dp).toInt(); setPadding(p, p, p, p) }
        col.addView(agentWork, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })

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
        choiceBar = ChoiceBar(this).apply {
            onPick = { ch -> if (ch.act == FollowUp.Act.REROUTE) CoderSession.reroute() else { edt.setText(ch.text); doSend() }; render() }
            onEdit = { t -> edt.setText(t); edt.setSelection(edt.text.length); keepTyping() }
        }
        col.addView(choiceBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })

        // साझा इनपुट बॉक्स: 📎 फ़ोटो/वीडियो (चुनने के बाद cursor वापस), लिखना, 🎤 बार के अंदर बोलकर लिखना, भेजें/रोकें
        composer = DKComposer(this, "क्या बनाना या ठीक करना है?", maxLines = 5, attach = { openPicker() })
        FutureViews.glassCommandBar(composer)   // नीचे काँच का कमांड बार (नियॉन किनारा)
        edt = composer.edt
        send = composer.send
        mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mediaScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; visibility = View.GONE
            addView(mediaRow, ViewGroup.LayoutParams(-2, -2))
        }
        col.addView(mediaScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        col.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        inline = DKInlineReply(list, scroll, 0.82f)

        // बटन: काम चलते समय = रोकें; keyboard/माइक का भेजें = हमेशा भेजना (doSend खुद तय करता है)
        // नवंबर 2026: काम चलते समय डिब्बा खाली = रोकें; कुछ लिखा = भेजें (उसी काम का = साथ में, अलग = बाद में)
        send.setOnClickListener { if (ChoiceBar.stops(edt, CoderSession.busy)) stopJob() else doSend() }
        ChoiceBar.wireSend(edt, send) { CoderSession.busy }
        CoderSession.starter = { t -> runOnUiThread { edt.setText(t); doSend() } }
        composer.onSend = { doSend() }
        // कोडिंग एजेंट (और 7 एजेंट की टीम) की फ़ाइलें + इससे बने स्टूडियो ऐप की फ़ाइलें
        codePanel = CodeLivePanel(this, { it == CodeLive.WHO_CODER || CodeLive.isStudio(it) }) { n -> codeBtn.text = UiLang.s(CodeLivePanel.label(n)) }
        codePanel.attachTo(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (codePanel.isOpen) codePanel.close() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() } }
        })
        setContentView(root)
        Glass.install(this)
        CoderHistory.init(this)                                     // पिछली बातचीत वापस (ऐप बंद होकर खुली हो तो)
        if (CoderSession.lines.isEmpty()) CoderSession.add("d", welcome())
        renderMedia()
        keepTyping()
    }

    private fun openPicker() {
        if (CoderSession.media.size >= CoderMedia.MAX_ITEMS) { Toast.makeText(this, "एक बार में ${CoderMedia.MAX_ITEMS} तक फ़ोटो/वीडियो", Toast.LENGTH_SHORT).show(); return }
        try {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        } catch (e: Exception) { DKLog.d("Coder: गैलरी गलती ${e.javaClass.simpleName}: ${e.message?.take(160)}"); Toast.makeText(this, "गैलरी नहीं खुली", Toast.LENGTH_SHORT).show() }
    }

    /** चुनी फ़ाइलें background में तैयार (फ़ोटो छोटी, वीडियो के frames) → पट्टी में; cursor लिखने की जगह पर */
    private fun addMedia(uris: List<android.net.Uri>) {
        keepTyping()
        val free = CoderMedia.MAX_ITEMS - CoderSession.media.size
        if (uris.isEmpty() || free <= 0) return
        val app = applicationContext
        Toast.makeText(this, "फ़ोटो/वीडियो तैयार कर रहा हूँ…", Toast.LENGTH_SHORT).show()
        CoderSession.scope.launch {
            var fail = 0
            for (u in uris.take(free)) {
                val n = CoderSession.media.size + 1
                val p = withContext(Dispatchers.IO) { try { MediaPrep.prepare(app, u, n) } catch (_: Throwable) { null } }
                if (p == null) fail++ else CoderSession.media.add(p.media to p.thumb)
            }
            if (isDestroyed) return@launch
            if (fail > 0) Toast.makeText(this@CodingActivity, "$fail फ़ाइल नहीं पढ़ पाया", Toast.LENGTH_SHORT).show()
            renderMedia(); keepTyping()
        }
    }

    private fun renderMedia() {
        mediaRow.removeAllViews()
        mediaScroll.visibility = if (CoderSession.media.isEmpty()) View.GONE else View.VISIBLE
        for ((i, pair) in CoderSession.media.withIndex()) {
            val (m, thumb) = pair
            val box = FrameLayout(this).apply { setBackgroundResource(R.drawable.glass_card) }
            box.addView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                if (thumb != null) setImageBitmap(thumb)
                contentDescription = m.label
            }, FrameLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()))
            if (m.isVideo) box.addView(TextView(this).apply {
                text = "🎬 " + (m.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) }
                textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x99000000.toInt()); setPadding((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            box.addView(TextView(this).apply {
                text = "✕"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xAA000000.toInt())
                contentDescription = "हटाएँ"
                setOnClickListener { if (i < CoderSession.media.size) CoderSession.media.removeAt(i); renderMedia(); keepTyping() }
            }, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.TOP or Gravity.END))
            mediaRow.addView(box, LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        }
    }

    private fun welcome(): String {
        // नवंबर 2026 (मालिक): खुलते ही लंबी सूची नहीं - सिर्फ़ एक लाइन; "क्या-क्या कर सकते हो" पूछने पर पूरी (AgentIntro.coderCapabilities)
        val problems = problems()
        if (Prefs.coderSeven(this)) return AgentIntro.coderHello(sevenModeLine().substringBefore(" • ").substringBefore(" — "))
        return if (problems.isEmpty()) AgentIntro.coderHello("${Prefs.ghRepo(this)} (${Prefs.ghBranch(this)})")
        else "पहले ये ठीक करें (सेटिंग्स → कोडिंग एजेंट):\n• " + problems.joinToString("\n• ")
    }

    /** "तुम क्या-क्या कर सकते हो" का पूरा जवाब (अभी का मोड देखकर) */
    private fun capabilities(): String {
        val seven = Prefs.coderSeven(this)
        val github = Prefs.ghToken(this).isNotBlank() && Prefs.ghRepo(this).contains('/') && !(seven && Prefs.coderNormal(this))
        // 13 नवंबर 2026 (मालिक): 7 एजेंट वाली टीम हटी - सिर्फ़ सिंगल एजेंट (टीम की लाइन नहीं)
        return AgentIntro.coderCapabilities(false, github) + "\n" + SOLO_LINE + (if (seven) "\n" + sevenModeLine() else "") + safeNote()
    }

    private companion object {
        const val SOLO_LINE = "🧠 सिंगल एजेंट: एक ही एजेंट पूरा काम करता है। 📎 से फ़ोटो / वीडियो भेजकर भी पूछ सकते हैं, और \"यह क्यों / कैसे हो रहा है\" पूछें तो असली कोड पढ़कर समझाता है।"
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
        val typed = edt.text.toString().trim()
        val hasMedia = CoderSession.media.isNotEmpty()
        // नवंबर 2026 (मालिक): ऐप स्टूडियो से जुड़ा - "स्टूडियो में क्या बन रहा है / उसमें X बदलो / रोको / खोलो" (कोडिंग टीम का काम चलते समय भी)
        if (!hasMedia && typed.isNotEmpty()) com.dk.assistant.studio.StudioLink.handle(this, typed, com.dk.assistant.studio.StudioSession.WHO_CODER)?.let { (msg, ok) ->
            edt.setText(""); CoderSession.add("u", typed); CoderSession.add(if (ok) "a" else "e", msg); render(); return
        }
        // नवंबर 2026 (मालिक): "तुम क्या-क्या कर सकते हो" = तुरंत पूरी सूची (AI कॉल नहीं)
        if (!hasMedia && AgentIntro.isCapabilityQuestion(typed)) {
            edt.setText(""); CoderSession.add("u", typed); CoderSession.add("a", capabilities()); render(); return
        }
        // काम चलते समय भी "क्या चल रहा है / रिपो चेक करो" पूछा जा सकता है (सिर्फ़ पढ़ना)
        if (typed.isEmpty() && !hasMedia) return
        // नवंबर 2026 (मालिक): काम चलते समय नई बात - उसी काम की = साथ में जुड़ेगी, अलग = यह काम पूरा होते ही
        if (CoderSession.busy && !RepoDoctor.isStatusAsk(typed)) {
            if (typed.isEmpty()) return
            edt.setText("")
            CoderSession.followUp(this, typed, canJoin = Prefs.coderSeven(this) || !Prefs.coderTeam(this))
            render(); return
        }
        // सिर्फ़ फ़ोटो/वीडियो भेजी: टीम उन्हें देखकर समझे
        val t = typed.ifEmpty { "इस फ़ोटो/वीडियो को ध्यान से देखकर समझो कि क्या ठीक करना / बदलना है" }
        OneKeyGate.check(this, OneKeyGate.Work.CODING)?.let { CoderSession.add("u", t); CoderSession.add("e", it); render(); return }
        edt.setText("")
        keepTyping()
        // नवंबर 2026 (ऐप स्टूडियो): GitHub नहीं जुड़ा / नॉर्मल मोड + "ऐप / वेबसाइट / गेम बनाओ" = फ़ोन पर ही बनाओ, वहीं preview
        if (!hasMedia && com.dk.assistant.studio.StudioRoute.noGithub(this)) com.dk.assistant.studio.StudioCore.extract(t)?.let { want ->
            CoderSession.add("u", t)
            val err = com.dk.assistant.studio.StudioSession.startFrom(this, want, com.dk.assistant.studio.StudioSession.WHO_CODER)
            CoderSession.add(if (err == null) "s" else "e", err ?: com.dk.assistant.studio.StudioRoute.STARTED_CODER)
            render(); return
        }
        // रिपो डॉक्टर: रिपो की हालत / किसी PR को ठीक या merge करना / उसकी योजना की हाँ (फ़ोटो के साथ = नया काम)
        if (!hasMedia) RepoDoctorRunner.intercept(this, t, false)?.let { r -> r.err?.let { CoderSession.add("e", it) }; render(); return }
        // 7 एजेंट वाला नया सिस्टम (चालू हो तो): GitHub मोड / नॉर्मल मोड खुद तय करता है
        if (Prefs.coderSeven(this)) {
            val taken = CoderSession.media.toList()
            CoderSession.media.clear(); renderMedia()
            SevenRunner.start(this, t, media = taken.map { it.first })?.let {
                CoderSession.add("u", t); CoderSession.add("e", it)
                CoderSession.media.addAll(taken); renderMedia()      // काम शुरू नहीं हुआ - फ़ोटो वापस पट्टी में
            }
            render(); return
        }
        if (hasMedia) {
            CoderSession.media.clear(); renderMedia()
            CoderSession.add("s", "📎 फ़ोटो/वीडियो सिर्फ़ 7 एजेंट वाली टीम देखती है (Settings → कोडिंग एजेंट में चालू करें)। अभी सिर्फ़ आपका लिखा भेजा।")
            if (typed.isEmpty()) { render(); return }
        }
        val p = problems()
        if (p.isNotEmpty()) { CoderSession.add("u", t); CoderSession.add("e", "पहले ठीक करें:\n• " + p.joinToString("\n• ")); return }
        // टीम मोड: बिल्डर -> रिव्यूअर -> कोऑर्डिनेटर, सब अपने-आप
        if (Prefs.coderTeam(this)) { CoderTeam.start(this, t)?.let { CoderSession.add("e", it) }; render(); return }
        val a = agent() ?: return
        CoderSession.add("u", t)
        a.inbox = CoderSession.inbox
        CoderSession.current = t
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
                    CoderSession.add("e", ShortReport.text(null, out.summary) + "\n(बदलाव push नहीं किए)")
                } else {
                    val partial = out.commit == "DK: partial changes"
                    CoderSession.add("d", when {
                        out.question -> out.summary
                        partial -> ShortReport.text(null, out.summary)
                        else -> ShortReport.text(true, github = false).lines().first() + "\n\n" + out.summary
                    })
                    if (!out.question && a.staged.isNotEmpty() && Prefs.coderAutoPush(applicationContext)) push(applicationContext)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                CoderSession.add("e", "रोक दिया।")
            } catch (e: Exception) {
                val m = (e.message ?: e.javaClass.simpleName).take(300)
                CoderSession.add("e", ShortReport.text(false, m))
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

    /** बुलबुले में तरंग के बगल का संदेश: असली रुकावट (दोबारा कोशिश / मॉडल बदला / सीमा) ही; कदम ऊपर सूची में दिखते हैं */
    private fun busyLabel(): String = DKStatus.fresh(busySince).orEmpty()

    /** नवंबर 2026: काम चलते समय = बीच में भेजी बात पलटने का बटन; रुका हो = अभी जिसका इंतज़ार है उसके बटन */
    private fun renderChoices() {
        val r = CoderSession.routed
        choiceBar.show(if (CoderSession.busy) when (r?.route) {
            FollowUp.Route.NOW -> listOf(FollowUp.TO_NEXT); FollowUp.Route.NEXT -> listOf(FollowUp.TO_NOW); null -> emptyList()
        } else {
            val last = CoderSession.lines.lastOrNull { it.role != "s" }?.role
            val ck = if (last == "e") CoderHistory.checkpoint(this) else null
            FollowUp.coder(SevenRunner.pending?.u, CoderSession.agent?.pr != null, last, ck != null && ck.stage != "pending")
        })
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
        UiDialog(this, Glass.DIALOG)
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
        if (b.ok) { CoderSession.add("d", "Build सफल ✓\n${b.url}"); CoderSound.notify(c, CoderSound.DONE, "Build सफल ✓", a.repo); return }
        CoderSound.notify(c, CoderSound.BUG, "Build fail — जड़ ढूँढ रहा हूँ", a.repo)
        CoderSession.add("e", "Build fail हुआ ✗\n${b.url}\n\n" + b.log.lines().filter { it.startsWith("e: ") || it.contains("error", true) }.take(6).joinToString("\n").take(700))
        fixSigs.add(FixLoop.signature(b.log))
        when (FixLoop.next(fixSigs)) {
            FixLoop.Next.STOP_CAP -> { CoderSound.notify(c, CoderSound.WAIT, "कोडिंग एजेंट रुका", "बार-बार fail — आपकी मदद चाहिए"); CoderSession.add("e", "${fixSigs.size} बार जड़ ढूँढकर ठीक करने के बाद भी build/tests fail हैं। ऊपर की गलती देखकर बताइए क्या करना है।"); return }
            FixLoop.Next.STOP_STUCK -> { CoderSound.notify(c, CoderSound.WAIT, "कोडिंग एजेंट रुका", "वही गलती बार-बार"); CoderSession.add("e", "वही गलती ${FixLoop.SAME_LIMIT} बार लगातार लौटी — एजेंट अटक गया, इसलिए रोका। इस गलती के बारे में और बताइए।"); return }
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
        // v3.89: अलग feature branch (PR वाला सुरक्षित तरीका) पर सुधार अपने-आप push → build फिर से चलता है, पास होने तक
        // (FixLoop की सीमा तक). main/base branch पर कभी बिना पूछे नहीं - वहाँ पहले जैसा "Push करें".
        if (Prefs.coderAutoPush(c) || (a.safePr && a.branch.isNotBlank() && a.branch != a.baseBranch)) push(c, round + 1)
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
        UiDialog(this, Glass.DIALOG)
            .setTitle("बदलाव (${a.staged.size} फ़ाइल)").setView(vs).setPositiveButton("ठीक है", null).show()
    }

    private fun newSession() {
        if (CoderSession.busy) { toast("पहले चल रहा काम रोकें"); return }
        CoderSession.agent?.reset(); CoderSession.savePr(this); CoderSession.agent = null
        CoderTeam.suggestions.clear(); SevenRunner.pending = null; RepoDoctorRunner.pending = null
        CoderHistory.newThread(this)                                // पुरानी बातचीत इतिहास में रहती है
        CoderSession.lines.clear(); shown = 0; seenDrop = CoderSession.dropped; list.removeAllViews()
        CoderSession.add("d", welcome())
    }

    // ---------------------------------------------------------------- इतिहास: कब क्या काम हुआ, उसी में आगे लिखना
    private fun showHistory() {
        CoderHistory.flush(this)
        val all = CoderHistory.list(this)
        if (all.isEmpty()) { if (LocalNotes.all(filesDir).isNotEmpty()) showNotes() else toast("अभी कोई इतिहास नहीं — पहला काम लिखें"); return }
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale("hi", "IN"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (6 * dp).toInt(); setPadding(p, p, p, p) }
        var dlg: android.app.Dialog? = null
        all.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
                val p = (12 * dp).toInt(); setPadding(p, (10 * dp).toInt(), p, (10 * dp).toInt())
                isClickable = true; isFocusable = true
            }
            row.addView(TextView(this).apply {
                text = (if (t.id == CoderHistory.current) "▶ " else "") + t.title; textSize = 15f; maxLines = 2
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            row.addView(TextView(this).apply {
                text = "${CoderHistory.Logic.statusHi(t.status)} • शुरू ${fmt.format(java.util.Date(t.created))} • आख़िरी ${fmt.format(java.util.Date(t.updated))}" +
                    (if (t.repo.isNotBlank()) "\n${t.repo}" else "") + (if (t.prNum > 0) " • PR #${t.prNum}" else "") + " • ${t.lines} संदेश"
                textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0)
            })
            row.setOnClickListener { dlg?.dismiss(); openThread(t) }
            row.setOnLongClickListener {
                UiDialog(this, Glass.DIALOG).setTitle("यह बातचीत इतिहास से हटाएँ?").setMessage(t.title + "\n\n(GitHub पर branch/PR जैसे हैं वैसे रहेंगे)")
                    .setPositiveButton("हटाओ") { _, _ -> CoderHistory.delete(this, t.id); dlg?.dismiss(); showHistory() }
                    .setNegativeButton("रुको", null).show(); true
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() })
        }
        val sc = android.widget.ScrollView(this).apply { addView(box) }
        dlg = UiDialog(this, Glass.DIALOG).setTitle("इतिहास — कब क्या काम हुआ")
            .setMessage("किसी पर टैप = वही बातचीत खुलेगी, उसी में आगे लिखें। लंबा दबाएँ = हटाएँ।")
            .setView(sc).setNegativeButton("बंद करें", null)
            .setNeutralButton("📒 काम-कॉपी") { _, _ -> showNotes() }.show()
    }

    /**
     * v3.89: फ़ोन में रखी काम-कॉपियाँ (हर रिपो/branch की) - तुरंत खुलती हैं, न इंटरनेट न GitHub API.
     * चल रहे काम की कॉपी सबसे ऊपर.
     */
    private fun showNotes() {
        val a = CoderSession.agent
        val cur = a?.let { LocalNotes.fileName(it.repo, it.branch) }
        val files = LocalNotes.all(filesDir).sortedByDescending { it.name == cur }
        if (files.isEmpty()) { toast("अभी फ़ोन में कोई काम-कॉपी नहीं — कोडिंग टीम काम करेगी तो अपने-आप बनेगी"); return }
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale("hi", "IN"))
        fun open(f: java.io.File) {
            val md = try { f.readText() } catch (_: Exception) { "" }
            // छुपा data (<!-- ... -->) पढ़ने वाले के काम का नहीं
            val txt = md.replace(Regex("<!--[\\s\\S]*?-->"), "").replace("**", "").trim()
            val tv = TextView(this).apply {
                text = txt.ifBlank { "(खाली)" }; textSize = 14f; setTextIsSelectable(true); setTextColor(0xFFFFFFFF.toInt())
                val p = (16 * dp).toInt(); setPadding(p, p, p, p)
            }
            UiDialog(this, Glass.DIALOG).setTitle("📒 " + f.name.removeSuffix(".md").replace("__", " • "))
                .setView(android.widget.ScrollView(this).apply { addView(tv) }).setNegativeButton("बंद करें", null).show()
        }
        if (files.size == 1) { open(files[0]); return }
        val names = files.map { f -> (if (f.name == cur) "▶ " else "") + f.name.removeSuffix(".md").replace("__", " • ") + "\n" + fmt.format(java.util.Date(f.lastModified())) }
        UiDialog(this, Glass.DIALOG).setTitle("📒 काम-कॉपी (फ़ोन में)")
            .setItems(names.toTypedArray()) { _, i -> open(files[i]) }.setNegativeButton("बंद करें", null).show()
    }

    /** इतिहास की बातचीत खोलो: उसके संदेश + उसका खुला PR (हो तो) - अगला काम उसी में जुड़ेगा */
    private fun openThread(t: CoderHistory.Thread) {
        if (CoderSession.busy) { toast("पहले चल रहा काम पूरा होने दें या रोकें"); return }
        if (t.id == CoderHistory.current) return
        val l = CoderHistory.open(this, t.id)
        CoderTeam.suggestions.clear(); SevenRunner.pending = null; RepoDoctorRunner.pending = null
        CoderSession.lines.clear(); CoderSession.lines.addAll(l); shown = 0; seenDrop = CoderSession.dropped; inline.drop(); list.removeAllViews()
        // उस बातचीत का PR अभी खुला हो (merge नहीं) तो वही वापस - वरना साफ़ शुरुआत
        CoderSession.agent?.let { a ->
            a.reset()
            if (t.prNum > 0 && t.prHead.isNotBlank() && t.repo == a.repo) a.restorePr(GitHub.Pull(t.prNum, t.prUrl, t.prHead, t.prBase.ifBlank { a.baseBranch }))
            CoderSession.savePr(this)
        }
        SevenRunner.reopen(this)
        CoderSession.add("s", "↩ इतिहास से खुली: ${t.title} — इसी में आगे लिखें")
    }

    // ---------------------------------------------------------------- 7-एजेंट: मोड + रिपो/branch चुनना
    private fun sevenModeLine(): String {
        val repo = Prefs.ghRepo(this)
        return when {
            Prefs.ghToken(this).isBlank() -> "मोड: नॉर्मल (GitHub नहीं जुड़ा — Settings → कोडिंग एजेंट में token डालें तो GitHub मोड) • ऐप / वेबसाइट बनाने को कहें = ऐप स्टूडियो (फ़ोन पर, preview के साथ)"
            Prefs.coderNormal(this) -> "मोड: नॉर्मल (आपने चुना) — ऊपर \"रिपो\" से रिपो चुनें तो GitHub मोड"
            !repo.contains('/') -> "मोड: नॉर्मल (कोई रिपो नहीं चुना) — ऊपर \"रिपो\" से चुनें"
            else -> "मोड: GitHub — $repo (${Prefs.ghBranch(this)}) • बदलने के लिए ऊपर \"रिपो\""
        }
    }

    private var pickerShown = false

    /** token से खुलने वाले सारे रिपो (खोज के साथ) + नॉर्मल मोड; फिर branch */
    private fun sevenPicker() {
        val gh = GitHub.fromPrefs(this)
        if (gh == null) {
            UiDialog(this, Glass.DIALOG).setTitle("GitHub नहीं जुड़ा")
                .setMessage("नॉर्मल मोड में काम होगा (गिट एजेंट बंद)। GitHub मोड के लिए Settings → कोडिंग एजेंट में token डालें।")
                .setPositiveButton("Settings खोलें") { _, _ -> startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", "coder")) }
                .setNegativeButton("ठीक है", null).show()
            return
        }
        toast("रिपो ला रहा हूँ…")
        CoderSession.scope.launch {
            val repos = try { withContext(Dispatchers.IO) { gh.reposAll() } } catch (e: GitHub.Err) {
                if (isFinishing || isDestroyed) return@launch
                val msg = if (e.code == 401) "GitHub टोकन गलत है या expire हो गया (401)।" else "रिपो नहीं ला पाया: ${e.message?.take(120)}"
                UiDialog(this@CodingActivity, Glass.DIALOG).setTitle("GitHub की दिक्कत").setMessage("$msg\n\nनॉर्मल मोड में काम करें?")
                    .setPositiveButton("नॉर्मल मोड") { _, _ -> setNormal(true) }
                    .setNegativeButton("बंद करें", null).show()
                return@launch
            } catch (e: Exception) { toast((e.message ?: "नेटवर्क गलती").take(120)); return@launch }
            if (isFinishing || isDestroyed) return@launch
            val box = LinearLayout(this@CodingActivity).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, 0) }
            val search = EditText(this@CodingActivity).apply {
                hint = "रिपो खोजें (${repos.size})"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x88FFFFFF.toInt()); isSingleLine = true
            }
            box.addView(search)
            val lv = android.widget.ListView(this@CodingActivity)
            box.addView(lv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5).toInt()))
            val normalLabel = "📝 नॉर्मल मोड (बिना GitHub / बिना रिपो)"
            fun items(q: String) = listOf(normalLabel) + repos.filter { q.isBlank() || it.fullName.contains(q.trim(), true) }
                .map { (if (it.fullName == Prefs.ghRepo(this@CodingActivity) && !Prefs.coderNormal(this@CodingActivity)) "✓ " else "") + it.fullName + if (it.private) "  🔒" else "" }
            val ad = android.widget.ArrayAdapter(this@CodingActivity, android.R.layout.simple_list_item_1, items("").toMutableList())
            lv.adapter = ad
            val dlg = UiDialog(this@CodingActivity, Glass.DIALOG).setTitle("रिपो चुनें").setView(box)
                .setNegativeButton("बंद करें", null).show()
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { ad.clear(); ad.addAll(items(e?.toString().orEmpty())); ad.notifyDataSetChanged() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
            lv.setOnItemClickListener { _, _, pos, _ ->
                val label = ad.getItem(pos) ?: return@setOnItemClickListener
                dlg.dismiss()
                if (label == normalLabel) { setNormal(true); return@setOnItemClickListener }
                val name = label.removePrefix("✓ ").substringBefore("  ").trim()
                val r = repos.firstOrNull { it.fullName == name } ?: return@setOnItemClickListener
                pickBranch(gh, r)
            }
        }
    }

    private fun pickBranch(gh: GitHub, r: GitHub.Repo) {
        CoderSession.scope.launch {
            val brs = try { withContext(Dispatchers.IO) { gh.branches(r.fullName) } } catch (e: Exception) { listOf(r.branch) }
            if (isFinishing || isDestroyed) return@launch
            val start = if ("main" in brs) "main" else r.branch
            val ordered = (listOf(start) + brs).distinct().filter { it.isNotBlank() }
            UiDialog(this@CodingActivity, Glass.DIALOG).setTitle("${r.fullName} — branch चुनें (शुरुआत: $start)")
                .setItems(ordered.map { if (it == start) "$it  (शुरुआती)" else it }.toTypedArray()) { _, i ->
                    if (CoderSession.agent?.staged?.isNotEmpty() == true) toast("पुराने रिपो के बिना push बदलाव हटा दिए")
                    Prefs.edit(this@CodingActivity) { putString("gh_repo", r.fullName); putString("gh_branch", ordered[i]); putBoolean("coder_normal", false) }
                    newSession()
                    CoderSession.offerNormal?.let { t -> CoderSession.offerNormal = null; CoderSession.add("s", "अब GitHub मोड: ${r.fullName} (${ordered[i]}) — काम दोबारा भेजें: $t".take(300)) }
                }.setNegativeButton("बंद करें", null).show()
        }
    }

    private fun setNormal(on: Boolean) {
        Prefs.edit(this) { putBoolean("coder_normal", on) }
        CoderSession.add("s", if (on) "📝 नॉर्मल मोड चुना — गिट एजेंट बंद, कोड फ़ाइल-नाम के साथ मिलेगा" else "🌿 GitHub मोड")
        CoderSession.offerNormal?.let { t -> CoderSession.offerNormal = null; if (on) SevenRunner.start(this, t, forceNormal = true)?.let { CoderSession.add("e", it) } }
        render()
    }

    /** टोकन/रिपो की दिक्कत पर रुका काम: नॉर्मल मोड का विकल्प */
    private var offerShownFor: String? = null
    private fun maybeOfferNormal() {
        val t = CoderSession.offerNormal ?: return
        if (CoderSession.busy || offerShownFor == t) return
        offerShownFor = t
        UiDialog(this, Glass.DIALOG).setTitle("GitHub मोड में दिक्कत")
            .setMessage((CoderSession.lines.lastOrNull { it.role == "e" }?.text ?: "") + "\n\nक्या करें?")
            .setPositiveButton("नॉर्मल मोड में करो") { _, _ -> CoderSession.offerNormal = null; SevenRunner.start(this, t, forceNormal = true)?.let { CoderSession.add("e", it) }; render() }
            .setNeutralButton("रिपो बदलो") { _, _ -> sevenPicker() }
            .setNegativeButton("रुको", null).show()
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
                UiDialog(this@CodingActivity, Glass.DIALOG)
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

    private var csMeter: TextView? = null
    private fun refreshMeter(force: Boolean = false, then: (() -> Unit)? = null) {
        CoderSession.scope.launch {
            val u = withContext(Dispatchers.IO) { try { CodespaceUsage.init(this@CodingActivity).refresh(this@CodingActivity, force) } catch (_: Exception) { null } }
            csMeter?.text = CodespaceMeter.line(u)
            then?.invoke()
        }
    }
    private fun codespaceDetail() {
        refreshMeter(force = true) {
            val u = try { CodespaceUsage.current(this) } catch (_: Exception) { null }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("☁ Codespaces फ़्री घंटे")
                .setMessage(CodespaceMeter.detail(u, CodespaceUsage.apiWhy(this)))
                .setPositiveButton("ठीक है", null).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMeter()
        CoderSession.listener = { runOnUiThread { scheduleRender() } }
        // app दोबारा खुली हो तो पिछला खुला PR (Merge card) वापस लाओ
        if (CoderSession.agent == null && problems().isEmpty()) CoderSession.agentFor(this)
        // ऐप बंद होने से रुका काम: अपने-आप वहीं से आगे (या "वहीं से आगे" बटन)
        if (Prefs.coderSeven(this)) SevenRunner.reopen(this)
        CoderSession.starter = { t -> runOnUiThread { edt.setText(t); doSend() } }
        CoderSession.runNext(this)
        render(); keepTyping()
        // 7-एजेंट: बीच में GitHub जोड़ा (token है, रिपो नहीं, नॉर्मल खुद नहीं चुना) → रिपो चुनने का विकल्प
        if (Prefs.coderSeven(this) && !pickerShown && Prefs.ghToken(this).isNotBlank() && !Prefs.ghRepo(this).contains('/') && !Prefs.coderNormal(this)) {
            pickerShown = true; sevenPicker()
        }
    }

    override fun onPause() { CoderSession.listener = null; showBusy(false); super.onPause() }

    override fun onDestroy() { CoderSession.starter = null; super.onDestroy() }

    /** चलती पट्टी दिखाओ/छिपाओ. एक ही tick चलता है (दोबारा start नहीं होता). */
    private fun showBusy(on: Boolean) {
        if (on == busyOn) return
        busyOn = on
        busyHandler.removeCallbacks(busyTick)
        if (on) {
            busyStep = 0
            busySince = System.currentTimeMillis()
            // नवंबर 2026 (मालिक): हेडर में "DK CODING AGENT" की जगह मौजूदा काम (मुलायम बदलाव)
            fillTaskCard(); headMode(true)
            showAgent()
            busyHandler.postDelayed(busyTick, BusyLabel.TICK_MS)
        } else { headMode(false); agentWork.visibility = View.GONE; CoderSession.activeAgent = null; CoderSession.activeNote = "" }
    }

    /** हेडर: true = मौजूदा काम + घूमता निशान, false = "DK CODING AGENT". आकार कभी नहीं बदलता (सिर्फ़ alpha) */
    private fun headMode(working: Boolean) {
        val show = if (working) headTask else headTitle
        val hide = if (working) headTitle else headTask
        hide.animate().cancel(); show.animate().cancel()
        show.visibility = View.VISIBLE
        show.animate().alpha(1f).setDuration(220).start()
        hide.animate().alpha(0f).setDuration(180).withEndAction { if (hide.alpha == 0f) hide.visibility = View.INVISIBLE }.start()
    }

    private fun fillTaskCard() {
        val t = CoderSession.task?.trim().orEmpty()
        val v = if (t.isBlank()) busyLabel().ifBlank { "काम चल रहा है" } else CoderHistory.Logic.title(t)
        if (busyTask.text.toString() != v) busyTask.text = v
    }

    /** 7 एजेंट वाली टीम चल रही हो तो: कौन सा एजेंट काम कर रहा है (नाम + animation) */
    private fun showAgent() {
        // 13 नवंबर 2026 (मालिक: "7 एजेंट वाला हटा दो"): अब एक ही एजेंट - 7 एजेंट की पंक्ति नहीं दिखती
        if (!Prefs.coderSeven(this) || Prefs.coderSolo(this)) { agentWork.visibility = View.GONE; return }
        if (agentWork.visibility != View.VISIBLE) { agentWork.alpha = 0f; agentWork.visibility = View.VISIBLE; agentWork.animate().alpha(1f).setDuration(220).start() }
        agentWork.active = CoderSession.activeAgent
        agentWork.note = CoderSession.activeNote
    }

    private fun render() {
        val a = CoderSession.agent
        maybeOfferNormal()
        ChoiceBar.icon(edt, send, CoderSession.busy)
        showBusy(CoderSession.busy)
        renderChoices()
        val lines = CoderSession.lines
        // नवंबर 2026 (बग): 300 लाइन के बाद आगे से हटी लाइनें गिनो - पहले यहीं अटककर नए संदेश दिखना बंद हो जाते थे
        val drop = CoderSession.dropped
        shown = UiPace.shownAfterDrop(shown, seenDrop, drop); seenDrop = drop
        if (lines.size < shown) { inline.drop(); list.removeAllViews(); shown = 0 }
        while (shown < lines.size) {
            val l = lines[shown]; shown++
            // एजेंट का जवाब/गलती = उसी बुलबुले में जहाँ तरंग चल रही थी
            if (l.role == "d" || l.role == "e") {
                val b = inline.answer(l.text, if (l.role == "e") 0xFFFFB4B4.toInt() else 0xFFFFFFFF.toInt())
                if (b != null) { FutureViews.addCodeParts(b, b.body, l.text, ::copyCode); continue }   // ``` कोड = IDE पैनल
            }
            // नवंबर 2026 (मालिक): काम के कदम चैट के बीच में नहीं (ऊपर एजेंट पट्टी में दिखते हैं)
            if (!ChatClean.showInChat(l.role, CoderSession.busy, shown == lines.size)) continue
            bubble(l); inline.keepLast()
        }
        // नवंबर 2026 (स्मूथ): बहुत लंबी बातचीत में सबसे पुराने बुलबुले स्क्रीन से हटें (इतिहास में पूरी बची रहती है)
        UiPace.trimCount(list.childCount).let { if (it > 0) list.removeViews(0, it) }
        if (CoderSession.busy) inline.show(busyLabel(), WorkState.WORKING)
        else if (inline.bubble?.isWorking == true) inline.hide()
        renderSuggestions()
        // badlaav card
        changes.removeAllViews()
        val ck = if (!CoderSession.busy && SevenRunner.pending == null) CoderHistory.checkpoint(this)?.takeIf { it.stage == "pause" || it.stage == "run" } else null
        if (ck != null) {
            // बीच में रुका काम (ऐप बंद / limit / समय / आपने रोका) - वहीं से आगे
            changes.visibility = View.VISIBLE
            changes.addView(TextView(this).apply {
                text = "⏸ पिछला काम बीच में रुका"; textSize = 14f
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            changes.addView(TextView(this).apply {
                text = CoderHistory.Logic.title(ck.task) + (if (ck.branch.isNotBlank()) "\nbranch: ${ck.branch}" else "") +
                    (if (ck.prNum > 0) " • PR #${ck.prNum}" else "") + (if (ck.staged.isNotEmpty()) " • ${ck.staged.size} फ़ाइल के बदलाव बचे" else "") +
                    "\nनए सिरे से नहीं — जहाँ रुका वहीं से आगे बढ़ेगा।"
                textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallBtn("वहीं से आगे", accent = true) {
                SevenRunner.resume(this, auto = false)?.let { toast(it) }; render()
            }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(smallBtn("छोड़ दें") {
                UiDialog(this, Glass.DIALOG).setTitle("रुका काम छोड़ दें?")
                    .setMessage("GitHub पर branch/PR जैसे हैं वैसे रहेंगे; बस यह काम आगे नहीं बढ़ेगा।")
                    .setPositiveButton("छोड़ दो") { _, _ -> SevenRunner.dropResume(this); CoderSession.add("s", "रुका काम छोड़ दिया"); render() }
                    .setNegativeButton("रुको", null).show()
            }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
            changes.addView(row)
        } else if (a != null && a.staged.isNotEmpty() && !CoderSession.busy) {
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
                UiDialog(this, Glass.DIALOG)
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
                setTypeface(typeface, Typeface.BOLD); setTextColor(FutureViews.GREEN)   // Patch तैयार = हरा निऑन
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

    /** नॉर्मल मोड की फ़ाइल: नाम + IDE जैसा कोड पैनल (लाइन नंबर, रंग) + कॉपी बटन (पहली लाइन = फ़ाइल का नाम) */
    private fun fileCard(l: CoderSession.Line) {
        val path = l.text.substringBefore('\n'); val code = l.text.substringAfter('\n', "")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
        }
        card.addView(TextView(this).apply {
            text = "📄 $path"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); UiLang.keep(this)
        })
        card.addView(FutureViews.codePanel(this, code, path.substringAfterLast('.', "").ifBlank { "code" }) { c ->
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(path, c)); toast("$path कॉपी हो गया")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        scroll.post { scroll.smoothScrollTo(0, list.height) }
    }

    /** Emulator screenshot की पट्टी ("i" लाइन): शीर्षक + तस्वीरें, टैप = बड़ी */
    private fun shotsCard(l: CoderSession.Line) {
        val (caption, paths) = Shots.parse(l.text)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
        }
        card.addView(TextView(this).apply { text = caption; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()) })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var any = false
        paths.forEach { path ->
            val f = java.io.File(path)
            val bmp = if (f.exists()) decodeSmall(f, (150 * dp).toInt()) else null
            if (bmp == null) return@forEach
            any = true
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp); adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = "Emulator screenshot ${Shots.label(f.name)}"
                setOnClickListener { showBig(f) }
            }, LinearLayout.LayoutParams((110 * dp).toInt(), (200 * dp).toInt()))
            col.addView(TextView(this).apply { text = Shots.label(f.name); textSize = 11f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER })
            strip.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * dp).toInt() })
        }
        if (any) card.addView(android.widget.HorizontalScrollView(this).apply { addView(strip); setPadding(0, (6 * dp).toInt(), 0, 0) })
        else card.addView(TextView(this).apply { text = "(पुरानी तस्वीरें फ़ोन से हट चुकी हैं)"; textSize = 12f; setTextColor(0x99FFFFFF.toInt()) })
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        scroll.post { scroll.smoothScrollTo(0, list.height) }
    }

    private fun decodeSmall(f: java.io.File, maxH: Int): android.graphics.Bitmap? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (o.outHeight / (s * 2) >= maxH) s *= 2
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Throwable) { null }

    private fun showBig(f: java.io.File) {
        val bmp = decodeSmall(f, resources.displayMetrics.heightPixels) ?: return
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(android.widget.ImageView(this).apply {
            setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF000000.toInt())
            contentDescription = "Emulator screenshot ${Shots.label(f.name)} — टैप करके बंद करें"
            setOnClickListener { d.dismiss() }
        })
        d.show()
    }

    private fun bubble(l: CoderSession.Line) {
        if (l.role == "f") { fileCard(l); return }
        if (l.role == "i") { shotsCard(l); return }
        if (l.role == "d") FutureViews.dkCodeBubble(this, l.text, (resources.displayMetrics.widthPixels * 0.82).toInt(), 0xFFFFFFFF.toInt(), ::copyCode)?.let {
            list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
            scroll.post { scroll.smoothScrollTo(0, list.height) }; return
        }
        val mine = l.role == "u"
        val tv = TextView(this).apply {
            text = l.text; setTextIsSelectable(true)
            when (l.role) {
                "s" -> { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding((6 * dp).toInt(), (2 * dp).toInt(), 0, 0) }
                else -> {
                    textSize = 15f; setTextColor(if (l.role == "e") 0xFFFFB4B4.toInt() else 0xFFFFFFFF.toInt())
                    // नवंबर 2026: आपका बुलबुला = गहरा नीला → बैंगनी (16dp), DK का = काँच
                    if (mine) background = FutureViews.userBubble(this@CodingActivity) else setBackgroundResource(R.drawable.glass_bubble_dk)
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

    private fun copyCode(code: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code)); toast("कोड कॉपी हो गया")
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}
