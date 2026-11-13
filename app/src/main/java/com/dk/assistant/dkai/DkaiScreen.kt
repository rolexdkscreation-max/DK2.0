package com.dk.assistant.dkai

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.dk.assistant.*
import com.dk.assistant.dkai.DkaiTheme.dp
import java.io.File

/** MainActivity से DKAI को क्या चाहिए */
class DkaiHost(
    /** key / माइक / Accessibility तैयार? नहीं = सही जगह भेजता है */
    val ready: () -> Boolean,
    /** + बटन: फोटो / फ़ाइल जोड़ने का मेन्यू */
    val attachMenu: (View) -> Unit,
    /** ऊपर का ☰: सेटिंग्स, मेमोरी, फ़ोन कंट्रोल, क्लासिक होम… */
    val moreMenu: (View) -> Unit,
    /** अपडेट / key वाली सूचना (text, टैप) */
    val alerts: () -> List<Pair<String, () -> Unit>> = { emptyList() },
    /** 13 नवंबर 2026: + sheet - सीधे गैलरी / फ़ाइल चुनने वाला (null = attachMenu) */
    val pickPhoto: (() -> Unit)? = null,
    val pickFile: (() -> Unit)? = null,
)

object Dkai {
    const val PREF = "dkai_home"
    /** DKAI होम चालू (डिफ़ॉल्ट हाँ). बंद = पुराना (क्लासिक) होम */
    fun on(c: android.content.Context) = Prefs.bool(c, PREF, true)
    fun set(c: android.content.Context, on: Boolean) = Prefs.edit(c) { putBoolean(PREF, on) }
    /** चैट नंबर ("Chat #3") - "+ New" दबाने पर +1 */
    fun chatNo(c: android.content.Context) = Prefs.str(c, "dkai_chat_no", "1").toIntOrNull() ?: 1
    fun nextChat(c: android.content.Context) = Prefs.edit(c) { putString("dkai_chat_no", (chatNo(c) + 1).toString()) }
}

/**
 * DKAI स्क्रीन: State 1 (होम + चैट + IDE टैब), State 2 (पूरी आवाज़), State 3 (छोटी तैरती पट्टी).
 * MainActivity के ऊपर addContentView से - पुराना होम नीचे वैसा ही (क्लासिक होम पर वापस जा सकते हैं).
 */
class DkaiScreen(val activity: ComponentActivity, val host: DkaiHost) : DkaiNav {
    override val act get() = activity
    private val c = activity
    private val ui = Handler(Looper.getMainLooper())
    private fun d(v: Float) = dp(c, v)

    // ------------------------------------------------------------------ हालत
    var voiceSessionState = VoiceSessionState.INACTIVE; private set
    var tab = DkaiTab.HOME; private set
    var mode = DkaiMode.CHAT; private set

    // ------------------------------------------------------------------ views
    lateinit var root: FrameLayout; private set
    private lateinit var content: LinearLayout
    private lateinit var chatLabel: TextView
    private lateinit var readyDot: PulseDot
    private lateinit var readyTxt: TextView
    private lateinit var alertRow: LinearLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var homeCol: LinearLayout
    private lateinit var chatList: LinearLayout
    private lateinit var cardRow: LinearLayout
    lateinit var input: EditText; private set
    private lateinit var inputBox: LinearLayout
    private lateinit var modelPill: TextView
    private lateinit var modePill: TextView
    private lateinit var sendBtn: ImageView
    private lateinit var quickGrid: LinearLayout
    private lateinit var micBtn: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var nav: LinearLayout
    private val navItems = HashMap<DkaiTab, Pair<ImageView, TextView>>()
    lateinit var voice: DkaiVoice; private set

    val files by lazy { FilesPanel(this) }
    val editor by lazy { EditorPanel(this) }
    val terminal by lazy { TerminalPanel(this) }
    val preview by lazy { PreviewPanel(this) }
    val github by lazy { GithubPanel(this) }
    /** 13 नवंबर 2026 (मालिक - M6): GitHub टैब = GitHub | Root Project (DKAI Copilot) */
    val rootProject by lazy { RootProjectPanel(this) }
    val gitTab by lazy { GitTab(this, github, rootProject) }

    private var shownMsgs = -1
    private var shownLive = ""
    private var insetBottom = 0

    fun attach(): DkaiScreen {
        DkaiTheme.load(c)                                       // Luminous Light / Obsidian Dark
        root = FrameLayout(c).apply { setBackgroundColor(DkaiTheme.BG); isClickable = true; id = R.id.dkai_root; tag = this@DkaiScreen }
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        buildHeader(); buildHome(); buildInput(); buildNav()
        voice = DkaiVoice(this)
        pickMode(mode)                                          // कार्ड शुरू से अपने डिब्बे (#161E31 + पतली लकीर) में
        root.addView(voice.mini, voice.miniParams())
        root.addView(voice.sheet, FrameLayout.LayoutParams(-1, -1))
        c.addContentView(root, ViewGroup.LayoutParams(-1, -1))
        // पुराना होम छुपा (वहीं रहता है - क्लासिक पर लौटना एक टैप)
        (root.parent as? ViewGroup)?.let { p -> for (i in 0 until p.childCount) p.getChildAt(i).takeIf { it !== root }?.visibility = View.GONE }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            insetBottom = maxOf(bars.bottom, ime.bottom)
            DkaiUi.insetTop = bars.top; DkaiUi.insetBottom = insetBottom
            content.setPadding(bars.left, bars.top, bars.right, insetBottom)
            voice.applyInsets(bars.top, bars.bottom)
            placeMini()
            ins
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeMini() }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        showTab(DkaiTab.HOME, animate = false)
        render()
        return this
    }

    // ================================================================== State 1: ऊपर
    private fun headIcon(kind: DkaiIcon.Kind, desc: String, tint: Int = DkaiTheme.GRAY, fill: Int = 0, go: (View) -> Unit): ImageView =
        DkaiUi.iconBtn(c, kind, desc, tint, 38f, fill) {}.apply { setOnClickListener { v -> go(v) } }

    private fun buildHeader() {
        // ☰  [DK लोगो] DKAI          ▶  🔧  ⚙  👤  ↗
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4f), d(6f), d(6f), 0) }
        head.addView(ImageView(c).apply {
            setImageDrawable(DkaiIcon(DkaiIcon.Kind.MENU, DkaiTheme.GRAY)); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = d(11f); setPadding(p, p, p, p); contentDescription = "मेन्यू"
            background = DkaiTheme.press(DkaiTheme.oval(0)); isClickable = true
            setOnClickListener { v -> host.moreMenu(v) }
        }, LinearLayout.LayoutParams(d(42f), d(42f)))
        head.addView(ImageView(c).apply { setImageResource(R.drawable.ic_dk); contentDescription = "DK लोगो"
            background = DkaiTheme.round(c, DkaiTheme.CARD, 10f, DkaiTheme.BORDER); val p = d(4f); setPadding(p, p, p, p) },
            LinearLayout.LayoutParams(d(32f), d(32f)).apply { leftMargin = d(2f) })
        head.addView(DkaiTheme.text(c, "DKAI", 21f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.04f; setPadding(d(8f), 0, 0, 0); maxLines = 1 },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(headIcon(DkaiIcon.Kind.PLAY_FILL, "आवाज़ से बात शुरू करें", DkaiTheme.ON, DkaiTheme.BLUE) { micTap() },
            LinearLayout.LayoutParams(d(36f), d(36f)).apply { rightMargin = d(4f) })
        head.addView(headIcon(DkaiIcon.Kind.TOOL, "Dev Tools") { DkaiDevTools(this).open() })
        head.addView(headIcon(DkaiIcon.Kind.GEAR, "IDE Settings") { DkaiSettings(this).open() })
        head.addView(headIcon(DkaiIcon.Kind.USER, "प्रोफ़ाइल") { DkaiSheets.profile(this) })
        head.addView(headIcon(DkaiIcon.Kind.SHARE, "DKAI शेयर करें") {
            DkaiUi.shareText(c, "${DkaiKit.BRAND} - AI coding, voice & broadcast studio\nhttps://github.com/rolexdkscreation-max/DK2.0", "DKAI शेयर करें")
        })
        content.addView(head, LinearLayout.LayoutParams(-1, d(52f)))

        // Chat #1  • READY            🔍  ⧉  ↗  + New
        val sub = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(16f), d(2f), d(10f), d(6f)) }
        chatLabel = DkaiTheme.text(c, DkaiCore.chatLabel(Dkai.chatNo(c)), 13f, DkaiTheme.GRAY, DkaiTheme.SANS_MED)
        sub.addView(chatLabel)
        val mid = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(10f), 0, 0, 0) }
        readyDot = PulseDot(c)
        mid.addView(readyDot, LinearLayout.LayoutParams(d(14f), d(14f)))
        readyTxt = DkaiTheme.text(c, "READY", 12f, DkaiKit.EMERALD, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.12f; setPadding(d(6f), 0, 0, 0) }
        mid.addView(readyTxt)
        mid.setOnClickListener { if (!Prefs.hasAnyKey(c)) host.ready() }
        sub.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        sub.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SEARCH, "चैट में खोजें", DkaiTheme.GRAY, 36f) { DkaiSheets.search(this) })
        sub.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.COPY, "चैट कॉपी करें", DkaiTheme.GRAY, 36f) {
            val t = DkaiSheets.chatText(c); if (t.isBlank()) toast("अभी चैट खाली है") else DkaiUi.copy(c, "chat", t)
        })
        sub.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SHARE, "चैट शेयर करें", DkaiTheme.GRAY, 36f) {
            val t = DkaiSheets.chatText(c); if (t.isBlank()) toast("अभी चैट खाली है") else DkaiUi.shareText(c, t, "चैट शेयर करें")
        })
        sub.addView(DkaiTheme.pill(c, "+ New", DkaiTheme.PILL, DkaiTheme.WHITE, 13f).apply {
            minHeight = d(36f); contentDescription = "नई चैट"; setOnClickListener { newChat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(4f) })
        content.addView(sub, LinearLayout.LayoutParams(-1, -2))
        content.addView(View(c).apply { setBackgroundColor(DkaiTheme.BORDER) }, LinearLayout.LayoutParams(-1, 1))

        alertRow = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(d(12f), d(8f), d(12f), 0) }
        content.addView(alertRow)
        panelHost = FrameLayout(c)
        content.addView(panelHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ================================================================== State 1: बीच (hero + कार्ड + चैट)
    private fun buildHome() {
        homeCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(18f), 0, d(16f)) }
        homeCol.addView(AiBadgeView(c), LinearLayout.LayoutParams(d(92f), d(92f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        homeCol.addView(DkaiTheme.text(c, "What would you like to build or explore?", 21f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); setPadding(d(28f), d(14f), d(28f), d(18f))
        }, LinearLayout.LayoutParams(-1, -2))
        // 2x2 quick कार्ड (Deep Web Research / Landing Page / Expense Tracker / Voice Assistant)
        quickGrid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16f), 0, d(16f), d(10f)) }
        for (r in 0 until 2) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 2) row.addView(quick(DkaiKit.QUICK[r * 2 + k]), LinearLayout.LayoutParams(0, -2, 1f).apply { if (k == 0) rightMargin = d(10f) })
            quickGrid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (r == 0) bottomMargin = d(10f) })
        }
        homeCol.addView(quickGrid)
        cardRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(16f), d(2f), d(16f), d(6f)) }
        for (m in DkaiMode.CARDS) cardRow.addView(card(m))
        homeCol.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(cardRow) })
        chatList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(14f), d(14f), 0) }
        UiLang.keep(chatList)
        homeCol.addView(chatList)
        homeScroll = ScrollView(c).apply { isFillViewport = true; clipToPadding = false; addView(homeCol) }
    }

    private fun quick(q: DkaiKit.Quick): View {
        val kind = when (q.icon) { "search" -> DkaiIcon.Kind.SEARCH; "globe" -> DkaiIcon.Kind.GLOBE; "wallet" -> DkaiIcon.Kind.WALLET; else -> DkaiIcon.Kind.MIC }
        val tint = when (q.icon) { "search" -> DkaiTheme.CYAN; "globe" -> DkaiTheme.VIOLET; "wallet" -> DkaiKit.EMERALD; else -> DkaiTheme.PINK }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = d(92f)
            setPadding(d(10f), d(14f), d(10f), d(12f))
            background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 18f, DkaiTheme.BORDER))
            isClickable = true; isFocusable = true; contentDescription = q.title + ", " + q.sub
            addView(ImageView(c).apply { setImageDrawable(DkaiIcon(kind, tint)) }, LinearLayout.LayoutParams(d(24f), d(24f)))
            addView(DkaiTheme.text(c, q.title, 13.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply { setPadding(0, d(8f), 0, 0); gravity = Gravity.CENTER; maxLines = 1 })
            addView(DkaiTheme.text(c, q.sub, 11f, DkaiTheme.GRAY).apply { setPadding(0, d(2f), 0, 0); gravity = Gravity.CENTER; maxLines = 2 })
            setOnClickListener { useQuick(q) }
        }
    }

    /** quick कार्ड = सच में काम: सही मोड + prompt (अधूरा हो तो आप पूरा करें, पूरा हो तो भेजो) */
    private fun useQuick(q: DkaiKit.Quick) {
        val m = q.mode ?: run { micTap(); return }
        pickMode(m)
        input.setText(q.prompt); input.setSelection(input.text.length)
        if (DkaiKit.needsMore(q)) {
            input.requestFocus()
            try { (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) } catch (_: Exception) {}
        } else send()
    }

    private fun card(m: DkaiMode): View {
        val (kind, tint) = when (m) {
            DkaiMode.CHAT -> DkaiIcon.Kind.CHAT to DkaiTheme.CYAN
            DkaiMode.BUILDER -> DkaiIcon.Kind.BUILDER to DkaiTheme.VIOLET
            DkaiMode.AGENT -> DkaiIcon.Kind.AGENT to 0xFF60A5FA.toInt()
            else -> DkaiIcon.Kind.PIPELINE to 0xFF34D399.toInt()
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(d(10f), d(14f), d(10f), d(12f)); tag = m
            isClickable = true; isFocusable = true; contentDescription = m.label
            val glow = FrameLayout(c).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(tint and 0x33FFFFFF, 0x00000000)).apply { shape = GradientDrawable.OVAL } }
            glow.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(kind, tint)) }, FrameLayout.LayoutParams(d(26f), d(26f), Gravity.CENTER))
            addView(glow, LinearLayout.LayoutParams(d(48f), d(48f)))
            addView(DkaiTheme.text(c, m.label, 13f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply { setPadding(0, d(8f), 0, 0); gravity = Gravity.CENTER })
            setOnClickListener { pickMode(m) }
            // चारों कार्ड एक लाइन में पूरे दिखें (छोटी स्क्रीन पर कम-से-कम 78dp - तब sideways scroll)
            val cw = ((c.resources.displayMetrics.widthPixels - d(32f) - 3 * d(10f)) / 4).coerceAtLeast(d(78f))
            layoutParams = LinearLayout.LayoutParams(cw, -2).apply { if (m != DkaiMode.CARDS.last()) rightMargin = d(10f) }
        }
    }

    fun pickMode(m: DkaiMode) {
        mode = m
        for (i in 0 until cardRow.childCount) {
            val v = cardRow.getChildAt(i); val on = v.tag == m
            v.background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.CARD, 18f, if (on) DkaiTheme.BLUE else DkaiTheme.BORDER, if (on) 1.5f else 1f))
        }
        input.hint = when (m) {
            DkaiMode.BUILDER -> "Describe the app or website to build..."
            DkaiMode.AGENT -> "Give DK Computer a multi-step task..."
            DkaiMode.PIPELINE -> "Describe a code change for your GitHub repo..."
            else -> "Build apps, create websites, or write code..."
        }
        if (::modePill.isInitialized) modePill.text = m.label + " ▾"
        if (tab != DkaiTab.HOME) showTab(DkaiTab.HOME)
    }

    // ================================================================== State 1: नीचे लिखने का डिब्बा
    private fun buildInput() {
        inputBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.CARD, 22f, DkaiTheme.BORDER)
            setPadding(d(14f), d(6f), d(8f), d(8f))
        }
        input = EditText(c).apply {
            hint = "Build apps, create websites, or write code..."; textSize = 15f
            setTextColor(DkaiTheme.WHITE); setHintTextColor(0xFF64748B.toInt()); background = null
            setPadding(d(2f), d(10f), d(2f), d(6f)); maxLines = 5; minHeight = d(44f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun afterTextChanged(s: Editable?) { micOrSend() }
            })
        }
        UiLang.keep(input)
        inputBox.addView(input, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(c).apply {
            setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLUS, DkaiTheme.WHITE)); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = d(10f); setPadding(p, p, p, p); contentDescription = "फोटो / फ़ाइल जोड़ें"
            background = DkaiTheme.press(DkaiTheme.oval(DkaiTheme.PILL, DkaiTheme.BORDER, 1)); isClickable = true
            setOnClickListener { v -> DkaiSheets.attach(this@DkaiScreen, v) }
            setOnLongClickListener { v -> host.attachMenu(v); true }
        }, LinearLayout.LayoutParams(d(40f), d(40f)))
        modelPill = DkaiTheme.pill(c, "Gemini Flash ▾", DkaiTheme.PILL, DkaiTheme.SUB, 12f).apply {
            minHeight = d(34f); contentDescription = "AI मॉडल चुनें"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = d(130f)
            setOnClickListener { DkaiSheets.models(this@DkaiScreen) }
            setOnLongClickListener { pickModel(it); true }
        }
        row.addView(modelPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        modePill = DkaiTheme.pill(c, mode.label + " ▾", DkaiTheme.PILL, DkaiTheme.SUB, 12f).apply {
            minHeight = d(34f); contentDescription = "मोड चुनें"; setOnClickListener { v -> DkaiSheets.modes(this@DkaiScreen, v) }
        }
        row.addView(modePill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(6f) })
        row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        sendBtn = DkaiUi.iconBtn(c, DkaiIcon.Kind.SEND, "भेजें", DkaiTheme.CYAN, 42f) { send() }.apply { alpha = 0.4f }
        row.addView(sendBtn, LinearLayout.LayoutParams(d(42f), d(42f)).apply { rightMargin = d(4f) })
        micBtn = FrameLayout(c).apply {
            background = DkaiTheme.press(DkaiTheme.oval(DkaiTheme.BLUE), DkaiTheme.oval(0xFFFFFFFF.toInt()))
            elevation = d(4f).toFloat(); isClickable = true; isFocusable = true; contentDescription = "माइक - आवाज़ से बात"
            setOnClickListener { micTap() }
        }
        micIcon = ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.WHITE)) }
        micBtn.addView(micIcon, FrameLayout.LayoutParams(d(22f), d(22f), Gravity.CENTER))
        row.addView(micBtn, LinearLayout.LayoutParams(d(48f), d(48f)))
        inputBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        content.addView(inputBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(6f), d(12f), d(8f)) })
    }

    /** 13 नवंबर 2026 (मालिक - M1): माइक और भेजें अलग - माइक हमेशा आवाज़, भेजें लिखा होने पर चमके */
    private fun micOrSend() {
        val typing = input.text.isNotBlank()
        if (::sendBtn.isInitialized) { sendBtn.alpha = if (typing) 1f else 0.4f; sendBtn.background = DkaiTheme.press(DkaiTheme.oval(if (typing) DkaiTheme.SEL else 0)) }
        micBtn.contentDescription = "माइक - आवाज़ से बात"
    }

    // ================================================================== State 1: 6 टैब
    private fun buildNav() {
        content.addView(View(c).apply { setBackgroundColor(DkaiTheme.BORDER) }, LinearLayout.LayoutParams(-1, 1))
        nav = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF0D1324.toInt()); setPadding(d(4f), d(6f), d(4f), d(6f)) }
        for (t in DkaiTab.values()) {
            val kind = when (t) {
                DkaiTab.HOME -> DkaiIcon.Kind.HOME; DkaiTab.FILES -> DkaiIcon.Kind.FILES; DkaiTab.EDITOR -> DkaiIcon.Kind.EDITOR
                DkaiTab.TERMINAL -> DkaiIcon.Kind.TERMINAL; DkaiTab.PREVIEW -> DkaiIcon.Kind.PREVIEW; DkaiTab.GITHUB -> DkaiIcon.Kind.GITHUB
            }
            val col = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = d(48f)
                background = DkaiTheme.press(DkaiTheme.round(c, 0, 12f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 12f))
                isClickable = true; isFocusable = true; contentDescription = t.label
                setOnClickListener { showTab(t) }
            }
            val ic = ImageView(c).apply { setImageDrawable(DkaiIcon(kind, DkaiTheme.GRAY)) }
            col.addView(ic, LinearLayout.LayoutParams(d(21f), d(21f)))
            val lb = DkaiTheme.text(c, t.label, 10.5f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply { setPadding(0, d(4f), 0, 0); maxLines = 1; gravity = Gravity.CENTER }
            col.addView(lb, LinearLayout.LayoutParams(-2, -2))
            navItems[t] = ic to lb
            nav.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(nav, LinearLayout.LayoutParams(-1, -2))
    }

    fun showTab(t: DkaiTab, animate: Boolean = true) {
        if (t != DkaiTab.EDITOR && tab == DkaiTab.EDITOR && editor.dirty) { editor.confirmLeave { showTab(t, animate) }; return }
        tab = t
        val v: View = when (t) {
            DkaiTab.HOME -> homeScroll
            DkaiTab.FILES -> files.root
            DkaiTab.EDITOR -> editor.root
            DkaiTab.TERMINAL -> terminal.root
            DkaiTab.PREVIEW -> preview.root
            DkaiTab.GITHUB -> gitTab.root
        }
        if (v.parent !== panelHost) { (v.parent as? ViewGroup)?.removeView(v); panelHost.addView(v, FrameLayout.LayoutParams(-1, -1)) }
        for (i in 0 until panelHost.childCount) panelHost.getChildAt(i).let { it.visibility = if (it === v) View.VISIBLE else View.GONE }
        if (animate) { v.alpha = 0f; v.animate().alpha(1f).setDuration(160).start() }
        DkaiTheme.gone(inputBox, t != DkaiTab.HOME)
        if (t != DkaiTab.HOME) hideKeyboard()
        for ((k, p) in navItems) {
            val on = k == t
            (p.first.drawable as? DkaiIcon)?.color = if (on) DkaiTheme.BLUE else DkaiTheme.GRAY
            p.second.setTextColor(if (on) DkaiTheme.BLUE else DkaiTheme.GRAY)
            p.first.parent?.let { (it as View).isSelected = on }
        }
        when (t) {
            DkaiTab.FILES -> files.onShow(); DkaiTab.EDITOR -> editor.onShow(); DkaiTab.TERMINAL -> terminal.onShow()
            DkaiTab.PREVIEW -> preview.onShow(); DkaiTab.GITHUB -> gitTab.onShow(); DkaiTab.HOME -> {}
        }
        root.post { placeMini() }
    }

    // ================================================================== DkaiNav
    override fun openInEditor(f: File) {
        val go = { if (editor.open(f)) showTab(DkaiTab.EDITOR) }
        if (editor.dirty && editor.file != f) editor.confirmLeave(go) else go()
    }
    override fun openPreview(f: File) { preview.open(f); showTab(DkaiTab.PREVIEW) }
    override fun openFiles(dir: File) { files.open(dir); showTab(DkaiTab.FILES) }
    override fun runInTerminal(cmd: String, cwd: File) { showTab(DkaiTab.TERMINAL); terminal.run(cmd, cwd) }
    override fun toast(s: String) { UiLang.toast(c, s, Toast.LENGTH_SHORT).show() }

    // ================================================================== भेजना (मोड के हिसाब से असली काम)
    fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        when (mode) {
            DkaiMode.CHAT, DkaiMode.BROADCAST -> { if (!sendChat(t)) return }
            DkaiMode.BUILDER -> {
                val err = com.dk.assistant.studio.StudioSession.start(c, t, com.dk.assistant.studio.StudioSession.WHO_COMPUTER)
                if (err != null) { toast(err); return }
                toast("Builder: ऐप स्टूडियो में बन रहा है…")
            }
            DkaiMode.AGENT -> {
                c.startActivity(Intent(c, ComputerActivity::class.java).putExtra("task", t))
            }
            DkaiMode.PIPELINE -> {
                CoderSession.queue.addLast(t)          // कोडिंग स्क्रीन खुलते ही उसी के भेजने वाले रास्ते से (सारे नियम वही)
                c.startActivity(Intent(c, CodingActivity::class.java))
            }
        }
        input.setText("")
    }

    private var localJob: kotlinx.coroutines.Job? = null

    /** 13 नवंबर 2026: वाक्य पर जगह न मिली (मिलाया जवाब / DK लाइव) = सारे चिप एक कतार में; + Google का searchEntryPoint (उसकी शर्त) */
    private fun groundExtra(m: DKState.Msg): View? {
        val g = m.g ?: return null
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (com.dk.assistant.GroundCite.points(m.text, g).isEmpty())
            com.dk.assistant.GroundView.chipRow(c, g, DkaiTheme.WHITE)?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(4f) }) }
        com.dk.assistant.GroundView.entry(c, g.entryHtml)?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        if (col.childCount == 0) return null
        return col.apply { layoutParams = LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), -2).apply { gravity = Gravity.START; bottomMargin = d(10f) } }
    }

    /** चैट वाला भेजना (ChatActivity जैसा): Accessibility चालू = DK का पूरा दिमाग़ (फ़ोन के काम भी), नहीं = सिर्फ़ बातचीत */
    private fun sendChat(t: String): Boolean {
        if (!Prefs.hasAnyKey(c)) { toast("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"); host.ready(); return false }
        DKState.userNow(t)
        val svc = DKAccessibilityService.instance
        if (svc != null) { svc.runCommand(t, speak = false); return true }
        val b = BrainManager(c)
        localJob?.cancel()
        localJob = activity.lifecycleScope.launch {
            try {
                LocalBrain.instant(c, t)?.let { DKState.reply(it); return@launch }
                val sb = StringBuilder()
                fun clean(x: String) = x.replace(Regex("\\[(SCREEN|SEARCH|THINK)\\]"), "").substringBefore("[YAAD").replace(Regex("[*#`]"), "").trim()
                val full = b.streamChat("USER SAYS: $t", "USER SAYS: $t") { dl ->
                    sb.append(dl); val cl = clean(sb.toString())
                    if (cl.isNotBlank() && !cl.startsWith("{")) DKState.replyLive(cl)
                }
                val cl = clean(full)
                DKState.reply(when {
                    full.contains("[SCREEN]") || full.contains("need_screen") -> "फ़ोन पर काम करने के लिए पहले Accessibility में DK चालू करें।"
                    cl.startsWith("{") -> try { org.json.JSONObject(cl.substring(0, cl.lastIndexOf('}') + 1)).optString("say") } catch (_: Exception) { cl }.ifBlank { "…" }
                    else -> cl.ifBlank { "…" }
                }, emptyList(), b.lastGround)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { DKState.replyFailed(b.explainAll(e))
            } finally { DKState.set(Mode.IDLE) }
        }
        return true
    }

    private fun newChat() {
        val go = {
            localJob?.cancel(); DKState.clearChat(); Dkai.nextChat(c)
            chatLabel.text = DkaiCore.chatLabel(Dkai.chatNo(c)); shownMsgs = -1; render()
            showTab(DkaiTab.HOME); input.setText(""); input.requestFocus()
        }
        if (DKState.messages.isEmpty()) go()
        else android.app.AlertDialog.Builder(c).setTitle("नई चैट शुरू करें?").setMessage("स्क्रीन की बातचीत साफ़ होगी (DK की यादें वैसी ही रहेंगी)।")
            .setPositiveButton("नई चैट") { _, _ -> go() }.setNegativeButton("रद्द", null).show()
    }

    private fun pickModel(anchor: View) {
        val id = Prefs.activeProvider(c)
        val cur = Prefs.model(c, id)
        val list = (listOf(cur) + Prefs.models(c, id)).filter { it.isNotBlank() }.distinct().take(40)
        val items = list.map { m -> (if (m == cur) "✓ " else "    ") + ModelNames.fromId(m) }.toMutableList()
        items.add("और AI / मॉडल (सेटिंग्स)…")
        android.app.AlertDialog.Builder(c).setTitle((Providers.byId(id)?.name ?: id) + " मॉडल")
            .setItems(items.toTypedArray()) { _, i ->
                if (i >= list.size) c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain"))
                else { Prefs.edit(c) { putString("model_$id", list[i]) }; renderModel(); toast("मॉडल: ${ModelNames.fromId(list[i])}") }
            }.show()
        anchor.isPressed = false
    }

    fun renderModel() {
        val id = Prefs.activeProvider(c)
        modelPill.text = DkaiCore.modelPill(Providers.byId(id)?.name ?: id, Prefs.model(c, id)) + " ▾"
    }

    // ================================================================== आवाज़ (State 2/3)
    fun micTap() {
        if (!host.ready()) return
        hideKeyboard()
        voice.newSession()
        setState(DkaiCore.next(voiceSessionState, VoiceEvent.MIC_TAP))
        startLive()
    }

    fun startLive() {
        val svc = DKAccessibilityService.instance
        when {
            svc == null -> { if (!Setup.selfEnableAccessibility(c)) toast("सेटिंग्स → Accessibility में ${Prefs.assistantName(c)} ON करें") }
            else -> svc.openedInApp("DKAI माइक")
        }
    }

    fun event(e: VoiceEvent) {
        if (e == VoiceEvent.CLOSE) { DKAccessibilityService.instance?.stopLive("DKAI ✕"); if (DKState.micMuted) DKState.setMicMuted(false) }
        setState(DkaiCore.next(voiceSessionState, e))
    }

    /** हालत बदलो + animation (slide-up / slide-down / fade) */
    fun setState(s: VoiceSessionState, animate: Boolean = true) {
        val old = voiceSessionState
        if (s == old) return
        voiceSessionState = s
        voice.transition(old, s, animate)
        back.isEnabled = true
        padForMini()
        render()
    }

    private fun padForMini() {
        val extra = if (voiceSessionState == VoiceSessionState.ACTIVE_MINIMIZED) d(84f) else 0
        homeScroll.setPadding(0, 0, 0, extra)          // चैट का आख़िरी संदेश पट्टी के नीचे न छिपे (scroll करके दिखे)
    }

    /** छोटी पट्टी: लिखने के डिब्बे (होम) / टैब-पट्टी के ठीक ऊपर */
    private fun placeMini() {
        if (!::root.isInitialized || !::voice.isInitialized) return
        val h = root.height; if (h <= 0) return
        val anchor: View = if (inputBox.visibility == View.VISIBLE) inputBox else nav
        val loc = IntArray(2); val rl = IntArray(2)
        anchor.getLocationInWindow(loc); root.getLocationInWindow(rl)
        val top = loc[1] - rl[1]
        val want = (h - top + d(8f)).coerceAtLeast(d(8f))
        val lp = voice.mini.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != want) { lp.bottomMargin = want; voice.mini.layoutParams = lp }
    }

    // ================================================================== हर बदलाव पर स्क्रीन ताज़ा
    private val stateL: () -> Unit = { render() }
    private val visionL: () -> Unit = { voice.render() }
    private var resumed = false

    fun onResume() {
        resumed = true
        DKState.listen(stateL); com.dk.assistant.LiveVision.listen(visionL)
        renderModel(); render()
        if (tab == DkaiTab.FILES) files.refresh()
    }

    fun onPause() {
        resumed = false
        DKState.unlisten(stateL); com.dk.assistant.LiveVision.unlisten(visionL)
        if (editor.dirty) editor.save()          // ऐप से बाहर = अपने-आप सेव (बदलाव खोते नहीं)
    }

    private var liveSeen = false
    private val endCheck = Runnable {
        if (!DKState.live && voiceSessionState.active && liveSeen) { liveSeen = false; setState(DkaiCore.next(voiceSessionState, VoiceEvent.LIVE_ENDED)) }
    }

    fun render() {
        if (!::root.isInitialized) return
        // Live की असली हालत से State 3 / 1
        if (DKState.live) {
            ui.removeCallbacks(endCheck)
            if (voiceSessionState.active) liveSeen = true
            else if (resumed && !voice.closedRecently()) { liveSeen = true; voice.newSession(); setState(DkaiCore.next(voiceSessionState, VoiceEvent.LIVE_STARTED)) }
        } else if (voiceSessionState.active && liveSeen) {
            ui.removeCallbacks(endCheck); ui.postDelayed(endCheck, DkaiCore.LIVE_END_GRACE_MS)
        }
        // होम का बैज
        val st = DkaiCore.homeStatus(Prefs.hasAnyKey(c), DKState.shownMode(), DKState.live, DKState.working())
        val col = when (st) { DkaiCore.Status.READY -> DkaiKit.EMERALD; DkaiCore.Status.SETUP -> DkaiTheme.AMBER; DkaiCore.Status.SPEAKING -> DkaiTheme.CYAN; else -> DkaiTheme.VIOLET }
        readyTxt.text = st.label; readyTxt.setTextColor(col); readyDot.color = col; readyDot.invalidate()
        renderAlerts()
        renderChat()
        voice.render()
    }

    private fun renderAlerts() {
        val a = try { host.alerts() } catch (_: Exception) { emptyList() }
        alertRow.removeAllViews()
        alertRow.visibility = if (a.isEmpty()) View.GONE else View.VISIBLE
        for ((t, go) in a) alertRow.addView(DkaiTheme.pill(c, t, 0xFF2A1F0A.toInt(), DkaiTheme.AMBER, 12f).apply { setOnClickListener { go() } },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
    }

    private fun renderChat() {
        val msgs = DKState.messages.toList().takeLast(60)
        val liveR = DKState.liveReply
        val thinking = DKState.mode == Mode.THINKING && msgs.lastOrNull()?.role == "u" && liveR.isBlank()
        val sig = "${msgs.size}|${msgs.lastOrNull()?.time}|${msgs.lastOrNull()?.text?.length}|${msgs.lastOrNull()?.src?.size}|${msgs.lastOrNull()?.g?.chunks?.size}|$thinking|${DKState.hintText}".hashCode()
        if (sig == shownMsgs && liveR == shownLive) return
        shownMsgs = sig; shownLive = liveR
        chatList.removeAllViews()
        for (m in msgs) {
            // 13 नवंबर 2026 (मालिक): जिस वाक्य को स्रोत मिला उसके ठीक अंत में छोटे चिप (github, wikipedia…), टैप = पूरा link
            chatList.addView(bubble(m.role == "u", m.text, ground = if (m.role != "u") m.g else null))
            if (m.role != "u" && m.src.isNotEmpty()) chatList.addView(sources(m.src))     // 13 नवंबर 2026: कौन-सी वेबसाइट से
            if (m.role != "u") groundExtra(m)?.let { chatList.addView(it) }              // चिप-कतार (जगह न मिली) + Google खोज सुझाव
        }
        if (liveR.isNotBlank()) chatList.addView(bubble(false, liveR))
        else if (thinking) chatList.addView(bubble(false, DKState.hintText.ifBlank { "सोच रहा हूँ…" }, faint = true))
        if (tab == DkaiTab.HOME && msgs.isNotEmpty()) homeScroll.post { homeScroll.smoothScrollTo(0, homeCol.height) }
    }

    /**
     * 13 नवंबर 2026 (मालिक): "जानकारी कहाँ से ली, सारे सोर्सेस दिखें - कौन-कौन सी वेबसाइट पर जाकर खोजा".
     * हर स्रोत: नंबर + पेज का नाम + वेबसाइट; टैप = वह पेज खुले.
     */
    private fun sources(src: List<Pair<String, String>>): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12f), d(10f), d(12f), d(8f))
            background = DkaiTheme.round(c, 0xFF0B1324.toInt(), 14f, DkaiTheme.BORDER)
        }
        val sites = com.dk.assistant.Grounding.sites(src)
        val via = com.dk.assistant.WebSearch.viaOf(src)
        box.addView(DkaiTheme.text(c, "🔎 स्रोत • ${sites.size} वेबसाइट" + (via?.let { " • $it" } ?: ""), 12f, DkaiTheme.CYAN, DkaiTheme.SANS_BOLD))
        src.forEachIndexed { i, (title, url) ->
            val site = com.dk.assistant.Grounding.site(title, url)
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(40f)
                isClickable = true; isFocusable = true; contentDescription = "स्रोत ${i + 1}: ${title.ifBlank { site }} ($site), खोलें"
                background = DkaiTheme.press(DkaiTheme.round(c, 0, 10f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 10f))
                setOnClickListener { com.dk.assistant.SourcesView.open(c, url) }
            }
            row.addView(DkaiTheme.pill(c, "${i + 1}", DkaiTheme.PILL, DkaiTheme.CYAN, 11f).apply { minWidth = d(26f); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8f) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(DkaiTheme.text(c, com.dk.assistant.Grounding.label(title.ifBlank { site }, 60), 13f, DkaiTheme.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) })
            if (site.isNotBlank()) col.addView(DkaiTheme.text(c, site, 11f, 0xFF60A5FA.toInt()).apply { maxLines = 1; UiLang.keep(this) })
            // पूरा link (टैप करने लायक)
            col.addView(DkaiTheme.text(c, url, 10.5f, DkaiTheme.GRAY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG; UiLang.keep(this) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return box.apply {
            layoutParams = LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
        }
    }

    private fun bubble(user: Boolean, text: String, faint: Boolean = false, ground: com.dk.assistant.GroundCite.G? = null): View = TextView(c).apply {
        this.text = if (ground == null) text else com.dk.assistant.GroundView.decorate(c, text, ground, DkaiTheme.WHITE)
        textSize = 15f; setLineSpacing(0f, 1.2f); setTextIsSelectable(true)
        com.dk.assistant.GroundView.links(this)
        setTextColor(if (faint) DkaiTheme.GRAY else DkaiTheme.WHITE)
        setPadding(d(14f), d(10f), d(14f), d(10f))
        background = if (user) DkaiTheme.round(c, 0xFF1D4ED8.toInt(), 18f) else DkaiTheme.round(c, DkaiTheme.CARD, 18f, DkaiTheme.BORDER)
        maxWidth = (c.resources.displayMetrics.widthPixels * 0.82f).toInt()
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = if (user) Gravity.END else Gravity.START; bottomMargin = d(10f) }
    }

    // ================================================================== + sheet: Attach Audio / Import Zip Project
    private var pickSeq = 0

    /** एक बार का फ़ाइल-चुनाव (MainActivity के बाहर से भी - registry से, काम के बाद हटता है) */
    private fun pickOne(types: Array<String>, got: (android.net.Uri) -> Unit) {
        lateinit var l: androidx.activity.result.ActivityResultLauncher<Array<String>>
        l = activity.activityResultRegistry.register("dkai_pick_${pickSeq++}", androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
            try { l.unregister() } catch (_: Exception) {}
            if (u != null) got(u)
        }
        try { l.launch(types) } catch (e: Exception) { try { l.unregister() } catch (_: Exception) {}; toast("फ़ाइल चुनने वाला नहीं खुला") }
    }

    /** Attach Audio: आवाज़ की फ़ाइल -> Gemini से लिखा हुआ text -> लिखने के डिब्बे में */
    fun pickAudio() = pickOne(arrayOf("audio/*")) { u ->
        toast("ऑडियो सुन रहा हूँ (लिख रहा हूँ)…")
        activity.lifecycleScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { Result.success(DkaiAudio.transcribe(c, u)) } catch (e: Throwable) { Result.failure(e) } }
            r.onSuccess { t ->
                if (t.isBlank()) { toast("ऑडियो में कुछ समझ नहीं आया"); return@onSuccess }
                showTab(DkaiTab.HOME)
                val cur = input.text.toString().trim()
                input.setText(if (cur.isEmpty()) t else "$cur\n$t"); input.setSelection(input.text.length); input.requestFocus()
            }.onFailure { e -> toast("ऑडियो नहीं पढ़ पाया: ${e.message?.take(120)}") }
        }
    }

    /** Import Zip Project: zip -> /DKAIProjects/<नाम> (zip-slip सुरक्षित) -> Files में खुले */
    fun pickZip() = pickOne(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) { u ->
        toast("Zip खोल रहा हूँ…")
        Thread {
            val r = try {
                val name = DkaiAudio.nameOf(c, u)?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9._-]+"), "-")?.trim('-')?.ifBlank { null } ?: "project"
                var dest = File(DkaiWorkspace.projectsRoot(c), name); var i = 2
                while (dest.exists()) dest = File(DkaiWorkspace.projectsRoot(c), "$name-${i++}")
                val tmp = File(c.cacheDir, "import_${System.currentTimeMillis()}.zip")
                try {
                    c.contentResolver.openInputStream(u)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: throw IllegalStateException("फ़ाइल नहीं खुली")
                    val n = DkaiZip.unzip(tmp, dest)
                    if (n == 0) { dest.deleteRecursively(); throw IllegalStateException("zip खाली है") }
                    DkaiWorkspace.setProject(c, dest)
                    Result.success(dest to n)
                } finally { tmp.delete() }
            } catch (e: Throwable) { Result.failure(e) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                r.onSuccess { (d, n) -> toast("✓ ${d.name}: $n फ़ाइलें"); openFiles(d) }.onFailure { e -> toast("Zip नहीं खुला: ${e.message?.take(120)}") }
            }
        }.start()
    }

    fun hideKeyboard() {
        try { (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0) } catch (_: Exception) {}
    }

    // ================================================================== back
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                DkaiUi.closeTop(root) -> {}                       // पहले खुला sheet / modal / page बंद
                voice.closeMenu() -> {}
                DkaiCore.handlesBack(voiceSessionState) -> setState(DkaiCore.next(voiceSessionState, VoiceEvent.BACK))
                tab != DkaiTab.HOME -> {
                    val handled = when (tab) { DkaiTab.FILES -> files.onBack(); DkaiTab.PREVIEW -> preview.onBack(); else -> false }
                    if (!handled) showTab(DkaiTab.HOME)
                }
                else -> { isEnabled = false; activity.onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        }
    }
}
