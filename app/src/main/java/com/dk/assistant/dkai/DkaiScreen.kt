package com.dk.assistant.dkai

import android.content.Intent
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
    /** 14 नवंबर 2026: ⚙ Settings में "पूरा DK" वाले रास्ते (title, विवरण, टैप) - फ़ोन कंट्रोल, माइक, चैट, टीचर, Forge… */
    val settingsItems: () -> List<Triple<String, String, () -> Unit>> = { emptyList() },
    /** 15 नवंबर 2026 (मालिक): नीचे की 5-टैब पट्टी - चैट / मेमोरी जैसी अलग स्क्रीनें MainActivity खोलती है (true = संभाल लिया) */
    val tab: (com.dk.assistant.ui.HomeTabs.Tab) -> Boolean = { false },
    /**
     * 23 नवंबर 2026 (मालिक: "इनपुट बॉक्स होम से Chat में"): Chat स्क्रीन (ChatActivity) अपना वही हेडर देती है
     * (← , "DK Chat", Online • Phone control on, "बोलकर भी"). null = होम.
     */
    val chatHeader: ((DkaiScreen) -> View)? = null,
    /** Chat स्क्रीन की वही बातचीत की सूची (ChatActivity का list) - इनपुट बॉक्स के ऊपर */
    val chatBody: View? = null,
    /** Chat स्क्रीन पर लिखा संदेश भेजना (ChatActivity.sendText - "बोलकर भी", रुकना / "फिर से भेजें" वही). true = भेजा */
    val chatSend: ((String) -> Boolean)? = null,
)

object Dkai {
    /**
     * 14 नवंबर 2026 (मालिक: "नया UI पूरा हटाओ, पुराना रखो - पर होम Perplexity जैसा"): अब एक ही होम.
     * नया/क्लासिक बदलने वाला स्विच हटा - होम हमेशा यही (चैट + चैट-सूची + आवाज़), IDE टैब / मोड / Terminal नहीं.
     */
    @Suppress("UNUSED_PARAMETER")
    fun on(c: android.content.Context) = true
    /** चैट नंबर ("Chat #3") - "+ New" दबाने पर +1 */
    fun chatNo(c: android.content.Context) = Prefs.str(c, "dkai_chat_no", "1").toIntOrNull() ?: 1
    fun nextChat(c: android.content.Context) = setChat(c, DkaiChats.nextNo(DkaiChats.dir(c), chatNo(c)))
    /** 13 नवंबर 2026: पुरानी चैट खोली = वही नंबर अब चालू */
    fun setChat(c: android.content.Context, n: Int) = Prefs.edit(c) { putString("dkai_chat_no", n.coerceAtLeast(1).toString()) }
}

/** अभिवादन को बीच से कितना ऊपर (dp) - ऊपर की पट्टी छोटी हुई तो थोड़ा ऊपर */
private const val GREET_LIFT_DP = 56f

/**
 * DK होम: State 1 (होम + चैट, Perplexity जैसा नीचे "Ask anything" डिब्बा), State 2 (पूरी आवाज़), State 3 (छोटी तैरती पट्टी).
 * MainActivity के ऊपर addContentView से - पुराना होम नीचे वैसा ही (क्लासिक होम पर वापस जा सकते हैं).
 */
class DkaiScreen(val activity: ComponentActivity, val host: DkaiHost) {
    private val c = activity
    private val ui = Handler(Looper.getMainLooper())
    /** 23 नवंबर 2026: true = Chat स्क्रीन (इनपुट बॉक्स + बातचीत), false = होम (बीच में ORB / अवतार + लाइव टेक्स्ट) */
    val chatPage: Boolean get() = host.chatHeader != null
    private fun d(v: Float) = dp(c, v)

    // ------------------------------------------------------------------ हालत
    var voiceSessionState = VoiceSessionState.INACTIVE; private set
    /** आवाज़ वाले हिस्से के लिए - मोड हटे, हमेशा Chat */
    val mode = DkaiMode.CHAT
    /** 🔍 (खोज / चैट) या 🖥 (DK Computer) - नीचे वाला दो-हिस्सों का बटन */
    var computerMode = false; private set

    // ------------------------------------------------------------------ views
    lateinit var root: FrameLayout; private set
    private lateinit var content: LinearLayout
    private lateinit var chatLabel: TextView
    /** Chat टैब का "बोलकर भी" (सिर्फ़ Chat टैब पर दिखता है) */
    private var speakSw: androidx.appcompat.widget.SwitchCompat? = null
    private lateinit var readyDot: PulseDot
    /** 15 नवंबर 2026 (मालिक): header के THINKING पर भी वही टाइपिंग एनीमेशन (Settings › दिखावट वाला) */
    private lateinit var readyAnim: com.dk.assistant.DKWorkingIndicator
    private lateinit var readyTxt: TextView
    private lateinit var alertRow: LinearLayout
    private lateinit var panelHost: FrameLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var homeCol: LinearLayout
    private lateinit var chatList: LinearLayout
    lateinit var input: EditText; private set
    private lateinit var inputBox: LinearLayout
    private lateinit var modelPill: TextView
    private lateinit var micBtn: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var dictBtn: ImageView
    private lateinit var searchSeg: ImageView
    private lateinit var compSeg: FrameLayout
    private lateinit var compEyes: ComputerEyes
    private var segBar: LinearLayout? = null
    private lateinit var attachRow: LinearLayout
    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var heroBox: LinearLayout
    private var cardDrop = DkaiHomeCore.CARD_DROP_MIN
    /** होम के बीच: ORB (चुना हुआ) या 3D अवतार - Settings › दिखावट › "DKAI का orb" */
    private lateinit var centerOrb: FluidOrbView
    private lateinit var centerClassic: com.dk.assistant.OrbView
    private lateinit var centerAvatar: HomeAvatarView
    /** पूरी स्क्रीन वाला कैरेक्टर (होम टैब के पीछे) */
    private lateinit var bgAvatar: HomeAvatarView
    private lateinit var centerLogo: Logo3D
    private var centerSig = ""
    /** होम का लाइव टेक्स्ट बॉक्स (यूज़र की बात -> DK की बात) */
    private lateinit var liveBox: LinearLayout
    private lateinit var liveDot: PulseDot
    private lateinit var liveState: TextView
    private lateinit var liveWho: TextView
    private lateinit var liveScroll: ScrollView
    private lateinit var liveTxt: TextView
    /** इस होम सत्र की शुरुआत (इससे पहले की बात होम के बॉक्स में नहीं) */
    private var liveSince = System.currentTimeMillis()
    private lateinit var greetTxt: TextView
    /** ऊपर की पट्टी में READY के बराबर वाली जगह - अभिवादन यहीं ([buildHeader]) */
    private var greetSlot: LinearLayout? = null
    private lateinit var nameTxt: TextView
    lateinit var voice: DkaiVoice; private set

    /** + से चुनी फोटो / फ़ाइल (भेजने तक यहीं, ✕ से हटाओ) */
    private val attached = ArrayList<android.net.Uri>()
    /** 🎤 लिखवाना चालू (null = नहीं) */
    private var dictation: com.dk.assistant.Dictation? = null
    private var dictBase = ""

    private var shownMsgs = -1
    private var shownLive = ""
    private var insetBottom = 0

    fun attach(): DkaiScreen {
        DkaiTheme.load(c)                                       // Luminous Light / Obsidian Dark
        builtLight = DkaiTheme.light
        root = FrameLayout(c).apply { setBackgroundColor(DkaiTheme.BG); isClickable = true; id = R.id.dkai_root; tag = this@DkaiScreen }
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        // 23 नवंबर 2026 (मालिक): चुना कैरेक्टर पूरी स्क्रीन के पीछे (होम टैब पर), ऊपर काँच जैसा लाइव कार्ड
        if (!chatPage) {
            bgAvatar = HomeAvatarView(c).apply { level = homeLevel; active = homeActive; call = homeCall; cover = true; fade = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE }
            root.addView(bgAvatar, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        // 23 नवंबर 2026 (मालिक): होम = ऊपर का हेडर + बीच में ORB / अवतार + लाइव टेक्स्ट; Chat = वही हेडर + बातचीत + होम वाला इनपुट बॉक्स
        if (chatPage) buildChatTop() else buildHeader()
        buildHome()
        if (chatPage) { buildInput(); buildTabs() } else buildTabs()
        voice = DkaiVoice(this)
        setComputer(false)
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
            // कीबोर्ड खुला = 5-टैब पट्टी छुपी (लिखने की जगह कम न हो)
            tabsBar?.visibility = if (ime.bottom > bars.bottom || !com.dk.assistant.ui.HomeTabs.visible(c)) View.GONE else View.VISIBLE
            voice.applyInsets(bars.top, bars.bottom)
            placeMini()
            ins
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeMini() }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) { scanPanel?.release(); scanPanel = null }
        })
        DkaiLive.attach(this)
        if (!chatPage) renumberChats()          // नंबर होम सँभालता है (Chat स्क्रीन खुलने से नंबर नहीं बदलते)
        panelHost.addView(if (chatPage) host.chatBody ?: homeScroll else homeScroll, FrameLayout.LayoutParams(-1, -1))
        render()
        return this
    }

    /** 14 नवंबर 2026 (मालिक): चैट के नंबर 1 से, बीच में खाली जगह नहीं (अभी वाली भी उसी हिसाब से) */
    fun renumberChats() {
        try {
            val dir = DkaiChats.dir(c); val cur = Dkai.chatNo(c)
            if (DKState.messages.isNotEmpty()) DkaiChats.save(dir, cur, DKState.messages.toList())
            val n = DkaiChats.renumber(dir, cur)
            if (n != cur) Dkai.setChat(c, n)
            if (::chatLabel.isInitialized) chatLabel.text = DkaiCore.chatLabel(n)
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }

    /** 14 नवंबर 2026 (मालिक): पुरानी चैट मिटाओ (अभी वाली हो तो स्क्रीन खाली) - फिर नंबर 1 से */
    fun deleteChat(no: Int) {
        val dir = DkaiChats.dir(c)
        if (no == Dkai.chatNo(c)) { localJob?.cancel(); DKState.clearChat() }
        DkaiChats.delete(dir, no)
        renumberChats(); shownMsgs = -1; render()
    }

    /** सारी पुरानी चैट मिटाओ - सिर्फ़ अभी वाली बचे (वह Chat 1 बने) */
    fun deleteOldChats() {
        val dir = DkaiChats.dir(c); val cur = Dkai.chatNo(c)
        for (n in DkaiChats.saved(dir)) if (n != cur) DkaiChats.delete(dir, n)
        renumberChats(); shownMsgs = -1; render()
    }

    /** संदेश लंबा दबाया: कॉपी / मिटाएँ */
    private fun msgMenu(m: DKState.Msg) {
        android.app.AlertDialog.Builder(c).setTitle(if (m.role == "u") "आपका संदेश" else "DK का जवाब")
            .setItems(arrayOf("कॉपी करें", "यह संदेश मिटाएँ")) { _, w ->
                if (w == 0) {
                    (c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("DKAI", m.text)); toast("कॉपी हो गया")
                } else android.app.AlertDialog.Builder(c).setMessage("यह संदेश मिटा दें?")
                    .setPositiveButton("मिटाएँ") { _, _ -> if (DKState.removeMsg(m.time, m.text)) { shownMsgs = -1; render(); toast("संदेश मिटा दिया") } }
                    .setNegativeButton("रद्द", null).show()
            }.show()
    }

    // ================================================================== State 1: ऊपर
    private fun headIcon(kind: DkaiIcon.Kind, desc: String, tint: Int = DkaiTheme.WHITE, fill: Int = 0, go: (View) -> Unit): ImageView =
        DkaiUi.iconBtn(c, kind, desc, tint, 46f, fill) {}.apply {
            setOnClickListener { v -> go(v) }
            layoutParams = LinearLayout.LayoutParams(d(46f), d(46f)).apply { leftMargin = d(8f) }
        }

    private fun buildHeader() {
        // ☰ [DK लोगो] DKAI                     ⚙
        // ● READY  (वहीं जहाँ पहले "Chat #" था)
        // 23 नवंबर 2026 (मालिक): ऊपर से Chat # ड्रॉपडाउन / खोज / कॉपी / शेयर / + New / कैमरा हटे - ये सब अब चैट टैब के
        // अपने ऊपर वाले हिस्से में ([buildChatPane]); कैमरा नीचे स्कैन टैब में. बाईं ओर ☰ + लोगो + नाम, दाईं ओर ⚙.
        val headF = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14f), d(8f), d(14f), 0); clipChildren = false; clipToPadding = false }
        headF.addView(ImageView(c).apply {
            setImageDrawable(DkaiIcon(DkaiIcon.Kind.MENU, DkaiTheme.WHITE)); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = d(12f); setPadding(p, p, p, p); contentDescription = "मेन्यू"
            background = com.dk.assistant.ui.Clay.circle(c, 4f); isClickable = true
            setOnClickListener { v -> host.moreMenu(v) }
        }, LinearLayout.LayoutParams(d(46f), d(46f)))
        val title = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), 0, 0, 0) }
        // DKAI लोगो: रंग हर थीम में वही (अपना तय पीछा, थीम / PlainLook इसे नहीं बदलते)
        title.addView(ImageView(c).apply { setImageResource(R.drawable.ic_dk); contentDescription = "DK लोगो"
            background = DkaiTheme.round(c, DkaiTheme.LOGO_TILE, 10f, DkaiTheme.LOGO_TILE_EDGE); val p = d(4f); setPadding(p, p, p, p)
            imageTintList = null; setTag(com.dk.assistant.PlainLook.SKIP, true) },
            LinearLayout.LayoutParams(d(28f), d(28f)))
        title.addView(DkaiTheme.text(c, "DKAI", 20f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.06f; setPadding(d(8f), 0, 0, 0); maxLines = 1 })
        headF.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        // 23 नवंबर 2026 (मालिक): Chat टैब पर ऊपर वही "बोलकर भी" toggle (बाय डिफ़ॉल्ट बंद) - चालू = लिखा जवाब बोलकर भी
        speakSw = androidx.appcompat.widget.SwitchCompat(c).apply {
            text = ChatSpeak.LABEL; textSize = 12f; setTextColor(DkaiTheme.GRAY); switchPadding = d(6f)
            isChecked = ChatSpeak.on(c); contentDescription = "जवाब बोलकर भी सुनाएँ"
            setOnCheckedChangeListener { _, on -> ChatSpeak.set(c, on) }
            visibility = View.GONE
        }
        headF.addView(speakSw, LinearLayout.LayoutParams(-2, -2))
        // 14 नवंबर 2026 (मालिक): ⚙ दबाते ही सीधे Settings खुले. लंबा दबाओ = पुरानी छोटी सूची (प्रोफ़ाइल / थीम / बाकी रास्ते)
        headF.addView(headIcon(DkaiIcon.Kind.GEAR, "Settings - सब कुछ एक जगह") { openSettings() }.apply {
            setOnLongClickListener { settingsList(); true }
        })
        content.addView(headF, LinearLayout.LayoutParams(-1, d(62f)))

        // ● READY - वहीं जहाँ पहले "Chat #" था
        val sub = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), d(8f), d(12f), d(6f)) }
        val mid = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = com.dk.assistant.ui.Clay.chip(c); setPadding(d(14f), 0, d(14f), 0); minimumHeight = d(36f)
            contentDescription = "DK की हालत" }
        readyDot = PulseDot(c)
        mid.addView(readyDot, LinearLayout.LayoutParams(d(14f), d(14f)))
        readyAnim = com.dk.assistant.DKWorkingIndicator(c).apply { bare = true; state = com.dk.assistant.WorkState.IDLE; visibility = View.GONE }
        mid.addView(readyAnim, LinearLayout.LayoutParams(d(20f), d(16f)))
        readyTxt = DkaiTheme.text(c, "READY", 12f, DkaiKit.EMERALD, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.12f; setPadding(d(6f), 0, 0, 0) }
        mid.addView(readyTxt)
        mid.setOnClickListener { if (!Prefs.hasAnyKey(c)) host.ready() }
        sub.addView(mid, LinearLayout.LayoutParams(-2, d(36f)))
        // 23 नवंबर 2026 (मालिक): "Good night, boss" कैरेक्टर / ऑर्ब के ऊपर नहीं - यहीं, READY के ठीक बराबर में (ऊपर की पट्टी में)
        greetSlot = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), 0, 0, 0) }
        sub.addView(greetSlot, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(sub, LinearLayout.LayoutParams(-1, -2))

        alertRow = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(d(12f), d(8f), d(12f), 0) }
        content.addView(alertRow)
        panelHost = FrameLayout(c)
        content.addView(panelHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ================================================================== 23 नवंबर 2026: टैब उसी जगह (कोई नई विंडो नहीं)
    /** अभी होम की बीच वाली जगह में कौन-सा टैब (होम / स्कैन / चैट / मेमोरी) */
    var tabNow = com.dk.assistant.ui.HomeTabs.Tab.HOME; private set
    private var chatPane: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var memPanel: com.dk.assistant.MemoryPanel? = null
    private var scanPanel: ScanPanel? = null

    /** चैट टैब: ऊपर Chat # (पुरानी चैट) / खोज / कॉपी / शेयर / + New, बीच में बातचीत, नीचे वही इनपुट बॉक्स */
    private fun buildChatPane(): LinearLayout {
        chatPane?.let { return it }
        val pane = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(12f), d(2f), d(12f), d(4f)) }
        chatLabel = DkaiTheme.text(c, DkaiCore.chatLabel(Dkai.chatNo(c)), 13f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply {
            // 13 नवंबर 2026 (मालिक): टैप = पुरानी सारी चैट की सूची
            setCompoundDrawablesRelative(null, null, DkaiTheme.icon(c, DkaiIcon.Kind.DOWN, DkaiTheme.GRAY, 14f), null); compoundDrawablePadding = d(3f)
            gravity = Gravity.CENTER_VERTICAL; minHeight = d(36f)
            background = com.dk.assistant.ui.Clay.chip(c); setPadding(d(14f), 0, d(12f), 0)
            isClickable = true; isFocusable = true; contentDescription = "पुरानी सारी चैट देखें"
            setOnClickListener { DkaiSheets.history(this@DkaiScreen) }
        }
        bar.addView(chatLabel)
        bar.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SEARCH, "चैट में खोजें", DkaiTheme.GRAY, 36f) { DkaiSheets.search(this) })
        bar.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.COPY, "चैट कॉपी करें", DkaiTheme.GRAY, 36f) {
            val t = DkaiSheets.chatText(c); if (t.isBlank()) toast("अभी चैट खाली है") else DkaiUi.copy(c, "chat", t)
        })
        bar.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SHARE, "चैट शेयर करें", DkaiTheme.GRAY, 36f) {
            val t = DkaiSheets.chatText(c); if (t.isBlank()) toast("अभी चैट खाली है") else DkaiUi.shareText(c, t, "चैट शेयर करें")
        })
        bar.addView(DkaiTheme.pill(c, "+ New", DkaiTheme.PILL, DkaiTheme.WHITE, 13f).apply {
            minHeight = d(36f); contentDescription = "नई चैट"; setOnClickListener { newChat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(4f) })
        pane.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val sc = ScrollView(c).apply { isFillViewport = true; clipToPadding = false; addView(chatList) }
        chatScroll = sc
        pane.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        buildInput(pane)
        chatPane = pane
        shownMsgs = -1
        return pane
    }

    /**
     * नीचे की पट्टी का टैब: सामग्री उसी बीच वाली जगह में बदलती है (होम = कैरेक्टर / ORB, स्कैन = DK लेंस,
     * चैट = बातचीत + इनपुट, मेमोरी = यादें). पट्टी और बीच का माइक हमेशा दिखते रहते हैं.
     */
    /** 23 नवंबर 2026: "DK सामने वाला लेंस खोलो" (आवाज़) - अगली बार स्कैन टैब खुले तो सामने वाला कैमरा */
    var scanFront = false

    fun showTab(t: com.dk.assistant.ui.HomeTabs.Tab): Boolean {
        if (chatPage || t == com.dk.assistant.ui.HomeTabs.Tab.MIC) return false
        val a = activity as? androidx.appcompat.app.AppCompatActivity
        val v: View = try {
            when (t) {
                com.dk.assistant.ui.HomeTabs.Tab.CHAT -> buildChatPane()
                com.dk.assistant.ui.HomeTabs.Tab.MEMORY -> (memPanel ?: a?.let { com.dk.assistant.MemoryPanel(it) }?.also { memPanel = it })?.view ?: return false
                com.dk.assistant.ui.HomeTabs.Tab.SCAN -> (scanPanel ?: a?.let { ScanPanel(it) { startLive() } }?.also { scanPanel = it })?.view?.let { sv ->
                    FrameLayout(c).apply { addView(sv, FrameLayout.LayoutParams(-1, -1).apply { setMargins(d(14f), d(6f), d(14f), d(10f)) }) }
                } ?: return false
                else -> homeScroll
            }
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e); return false }
        if (tabNow == com.dk.assistant.ui.HomeTabs.Tab.SCAN && t != tabNow) scanPanel?.leaveTab()
        DkaiUi.closeTop(root)
        if (t != com.dk.assistant.ui.HomeTabs.Tab.CHAT) hideKeyboard()
        val changed = t != tabNow
        tabNow = t
        // स्कैन टैब के लिए पैनल को एक फ्रेम में लपेटा है - वही रखो, बार-बार नया नहीं
        val show = if (t == com.dk.assistant.ui.HomeTabs.Tab.SCAN) (scanFrame ?: v.also { scanFrame = it }) else v
        if (show.parent !== panelHost) {
            (show.parent as? ViewGroup)?.removeView(show)
            panelHost.removeAllViews()
            panelHost.addView(show, FrameLayout.LayoutParams(-1, -1))
            if (changed && !com.dk.assistant.ui.NeonTheme.reducedMotion()) { show.alpha = 0f; show.animate().alpha(1f).setDuration(160).start() }
        }
        when (t) {
            com.dk.assistant.ui.HomeTabs.Tab.MEMORY -> memPanel?.refresh()
            com.dk.assistant.ui.HomeTabs.Tab.SCAN -> { val f = scanFront; scanFront = false; scanPanel?.show(front = f, userTap = true) }
            com.dk.assistant.ui.HomeTabs.Tab.CHAT -> { shownMsgs = -1; render(); chatScroll?.post { chatScroll?.scrollTo(0, chatList.height) } }
            else -> homeScroll.post { homeScroll.smoothScrollTo(0, 0) }
        }
        com.dk.assistant.ui.HomeTabs.select(tabsBar, t)
        if (::voice.isInitialized) { voice.syncMini(); padForMini() }      // छोटी पट्टी: होम पर नहीं, Chat टैब पर हाँ
        speakSw?.let { sw -> sw.isChecked = ChatSpeak.on(c); DkaiTheme.gone(sw, t != com.dk.assistant.ui.HomeTabs.Tab.CHAT) }
        renderBack()
        return true
    }
    private var scanFrame: View? = null

    /** पूरी स्क्रीन वाला कैरेक्टर सिर्फ़ होम टैब पर (बाकी टैब पर सादा पीछा, ताकि लिखावट साफ़ पढ़ी जाए) */
    private fun renderBack() {
        if (chatPage || !::bgAvatar.isInitialized) return
        val on = tabNow == com.dk.assistant.ui.HomeTabs.Tab.HOME && bgAvatar.bitmap != null
        bgAvatar.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Chat स्क्रीन: ChatActivity वाला वही हेडर, उसके नीचे बातचीत (panelHost) */
    private fun buildChatTop() {
        val top = host.chatHeader?.invoke(this) ?: View(c)
        content.addView(top, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(14f), d(14f), d(14f), 0) })
        alertRow = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        panelHost = FrameLayout(c)
        content.addView(panelHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d(6f) })
    }

    /** ⚙ = सीधे DK Settings (न खुले तो पुरानी सूची) */
    private fun openSettings() {
        try { c.startActivity(Intent(c, SettingsActivity::class.java)) } catch (_: Exception) { settingsList() }
    }

    /** ⚙ लंबा दबाना: पूरी DK सेटिंग्स + बाकी रास्ते (फ़ोन कंट्रोल, माइक, Story Writer, टीचर, Forge…) + प्रोफ़ाइल + थीम */
    private fun settingsList() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        try { for ((t, sub, go) in host.settingsItems()) items.add("$t — $sub" to go) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        if (items.isEmpty()) items.add("पूरी DK सेटिंग्स" to { c.startActivity(Intent(c, SettingsActivity::class.java)) })
        items.add("प्रोफ़ाइल (आपका नाम)" to { DkaiSheets.profile(this) })
        items.add((if (DkaiTheme.light) "डार्क थीम पर जाएँ" else "लाइट थीम पर जाएँ") to { DkaiTheme.setLight(c, !DkaiTheme.light); activity.recreate() })
        android.app.AlertDialog.Builder(c).setTitle("Settings")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items.getOrNull(i)?.second?.invoke() }.show()
    }

    /** Good morning / afternoon / evening + नाम (प्रोफ़ाइल बदले तो लौटते ही नया) */
    private fun renderGreeting() {
        if (!::greetTxt.isInitialized) return
        // 23 नवंबर 2026 (मालिक): फ़ोन के समय से (सुबह / दोपहर / शाम / रात) + Profile का नाम (खाली = "boss")
        val g = DkaiHomeCore.greeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
        if (greetTxt.text.toString() != g) greetTxt.text = g
        val n = DkaiHomeCore.greetName(try { Prefs.userName(c) } catch (_: Exception) { "" })
        if (nameTxt.text.toString() != n) nameTxt.text = n
        DkaiTheme.gone(nameTxt, false)
    }

    // ================================================================== State 1: बीच (3D लोगो + चैट)
    private fun buildHome() {
        homeCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(8f), 0, d(16f)) }
        // 14 नवंबर 2026 (मालिक: "Perplexity जैसा"): चैट खाली = बीच में घूमता 3D DK लोगो (टैप = फिर घूमे). quick कार्ड / Broadcast हटे
        // 23 नवंबर 2026 (मालिक): अभिवादन बीच में पर पहले से थोड़ा ऊपर (ऊपर की चार चीज़ें हटीं) - नीचे ज़्यादा जगह
        // 23 नवंबर 2026 (मालिक): READY कार्ड थोड़ा नीचे ([DkaiHomeCore.cardDropDp]) - कार्ड के ऊपर उतनी जगह, नीचे की जगह उतनी कम
        // (अभिवादन वहीं रहता है, सिर्फ़ कार्ड नीचे खिसकता है)
        cardDrop = DkaiHomeCore.cardDropDp(c.resources.configuration.screenHeightDp)
        heroBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, d(maxOf(0f, GREET_LIFT_DP - cardDrop))) }
        // 23 नवंबर 2026 (Soft Clay): समय के हिसाब से नमस्ते + नीचे आपका नाम बड़े मोटे अक्षरों में (नाम = Settings › प्रोफ़ाइल वाला)
        greetTxt = DkaiTheme.text(c, DkaiHomeCore.greeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)), 16f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply { gravity = Gravity.CENTER }
        nameTxt = DkaiTheme.text(c, "", 30f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(d(16f), d(4f), d(16f), 0) }
        UiLang.keep(greetTxt); UiLang.keep(nameTxt)
        val slot = greetSlot
        if (slot != null) {
            // ऊपर की पट्टी: दो छोटी पंक्तियाँ, बाईं ओर READY से सटी (बीच की पूरी जगह कैरेक्टर / ऑर्ब के लिए)
            greetTxt.textSize = 12f; greetTxt.gravity = Gravity.START
            nameTxt.textSize = 17f; nameTxt.gravity = Gravity.START; nameTxt.setPadding(0, 0, 0, 0)
            slot.addView(greetTxt, LinearLayout.LayoutParams(-1, -2))
            slot.addView(nameTxt, LinearLayout.LayoutParams(-1, -2))
        } else {
            heroBox.addView(greetTxt, LinearLayout.LayoutParams(-1, -2))
            heroBox.addView(nameTxt, LinearLayout.LayoutParams(-1, -2))
        }
        renderGreeting()
        // 23 नवंबर 2026 (मालिक): बीच में ORB या 3D अवतार (Settings से) - वही जगह, वही आकार, पीछे वही हल्की accent चमक
        val glow = FrameLayout(c).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(com.dk.assistant.ui.ClayTheme.t.glow, com.dk.assistant.ui.NeonTheme.alpha(com.dk.assistant.ui.ClayTheme.t.accent, 0x00))
                gradientRadius = d(130f).toFloat()
            }
            isClickable = true; isFocusable = true
            contentDescription = "DK - टैप: आवाज़ से बात, लंबा दबाएँ: पूरी आवाज़ स्क्रीन"
            setOnClickListener {
                if (::centerLogo.isInitialized && centerLogo.visibility == View.VISIBLE) { centerLogo.spin() }
                centerTap()
            }
            setOnLongClickListener { if (DKState.live) setState(VoiceSessionState.ACTIVE_FULL) else micTap(); true }
        }
        centerOrb = FluidOrbView(c).apply { level = homeLevel; active = homeActive; style = { DkaiOrbs.get(c) }; call = homeCall
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        centerClassic = com.dk.assistant.OrbView(c).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE }
        centerAvatar = HomeAvatarView(c).apply { level = homeLevel; active = homeActive; call = homeCall; fade = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.GONE }
        glow.addView(centerOrb, FrameLayout.LayoutParams(d(200f), d(200f), Gravity.CENTER))
        glow.addView(centerClassic, FrameLayout.LayoutParams(d(200f), d(200f), Gravity.CENTER))
        glow.addView(centerAvatar, FrameLayout.LayoutParams(-1, -1))
        // पहले वाला DK लोगो (3D) ORB के बीच में ही रहता है - अवतार चुनने पर छिपता है
        centerLogo = Logo3D(c, R.drawable.ic_dk).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
        glow.addView(centerLogo, FrameLayout.LayoutParams(d(92f), d(92f), Gravity.CENTER))
        heroBox.addView(glow, avatarArea(false))
        centerGlow = glow
        // लाइव टेक्स्ट बॉक्स (वही उभरा Clay कार्ड): हालत + पहले यूज़र की पूरी लाइव बात, फिर उसी डिब्बे में DK की
        liveBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = com.dk.assistant.ui.Clay.card(c)
            setPadding(d(18f), d(14f), d(18f), d(16f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val lhead = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        liveDot = PulseDot(c)
        lhead.addView(liveDot, LinearLayout.LayoutParams(d(12f), d(12f)))
        liveState = DkaiTheme.text(c, "READY", 12f, DkaiKit.EMERALD, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.12f; setPadding(d(8f), 0, 0, 0) }
        lhead.addView(liveState, LinearLayout.LayoutParams(0, -2, 1f))
        liveWho = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY, DkaiTheme.SANS_MED)
        lhead.addView(liveWho)
        liveBox.addView(lhead, LinearLayout.LayoutParams(-1, -2))
        liveTxt = DkaiTheme.text(c, "", 16f, DkaiTheme.WHITE).apply { setLineSpacing(0f, 1.2f); setPadding(0, d(8f), 0, 0) }
        liveScroll = object : ScrollView(c) {
            // बॉक्स बहुत लंबा न हो: ज़्यादा बात = अंदर स्क्रॉल, सबसे नई लाइन दिखे
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(d(170f), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(liveTxt) }
        liveBox.addView(liveScroll, LinearLayout.LayoutParams(-1, -2))
        // चौड़ाई = उपलब्ध जगह (दोनों तरफ़ 14dp) - फ़ोन पर पहले जितनी ही; टैबलेट पर स्क्रीन के अनुपात में ([com.dk.assistant.UiFit.tablet])
        heroBox.addView(liveBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(14f + cardDrop); leftMargin = d(14f); rightMargin = d(14f) })
        if (!chatPage) homeCol.addView(heroBox, LinearLayout.LayoutParams(-1, 0, 1f))
        chatList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(14f), d(14f), 0) }
        UiLang.keep(chatList)
        // बातचीत की सूची अब Chat स्क्रीन पर (ChatActivity की वही सूची) - होम पर सिर्फ़ ORB / अवतार + लाइव टेक्स्ट
        homeScroll = ScrollView(c).apply { isFillViewport = true; clipToPadding = false; addView(homeCol) }
    }

    // ================================================================== State 1: नीचे "Ask anything" डिब्बा (Perplexity जैसा)
    // [फोटो चिप ✕ ✕ ✕]
    // Ask anything…
    // [+] [🔍|🖥] [Model ▾]                [🎤] [〰 / ↑]
    private fun buildInput(parent: LinearLayout = content) {
        inputBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            // 23 नवंबर 2026 (Soft Clay): लंबी गोल उभरी पट्टी (कोने 28dp)
            background = com.dk.assistant.ui.Clay.card(c)
            setPadding(d(18f), d(10f), d(14f), d(14f))
        }
        attachRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, d(4f), 0, d(2f)) }
        attachScroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(attachRow) }
        inputBox.addView(attachScroll, LinearLayout.LayoutParams(-1, -2))
        input = EditText(c).apply {
            hint = DkaiHomeCore.HINT; textSize = 15.5f      // 23 नवंबर 2026 (मालिक): "DK AI से कुछ भी पूछो"
            setTextColor(DkaiTheme.WHITE); setHintTextColor(DkaiTheme.HINT); background = null
            setPadding(d(4f), d(10f), d(4f), d(6f)); maxLines = 6; minHeight = d(44f)
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
        // + = एक बार में 5 तक फोटो (या फ़ाइल)
        row.addView(ImageView(c).apply {
            setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLUS, DkaiTheme.WHITE)); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = d(9f); setPadding(p, p, p, p); contentDescription = "फोटो / फ़ाइल जोड़ें (5 तक)"
            background = com.dk.assistant.ui.Clay.circle(c, 3f); isClickable = true
            setOnClickListener { v -> DkaiSheets.attach(this@DkaiScreen, v) }
            setOnLongClickListener { pickImages(); true }
        }, LinearLayout.LayoutParams(d(38f), d(38f)))
        // 🔍 | 🖥  - खोज / चैट या DK Computer
        val seg = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(3f), d(3f), d(3f), d(3f))
        }
        // Segmented पट्टी: धँसी पट्टी + चुना (🔍 या 🖥) उभरा accent बटन जो 200ms में खिसकता है
        seg.background = com.dk.assistant.ui.ClaySegmentDrawable(seg) { if (computerMode) 1 else 0 }
        segBar = seg
        searchSeg = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE; val p = d(7f); setPadding(p, p, p, p)
            contentDescription = "खोज / चैट"; isClickable = true; setOnClickListener { setComputer(false) }
        }
        seg.addView(searchSeg, LinearLayout.LayoutParams(d(34f), d(32f)))
        compSeg = FrameLayout(c).apply {
            contentDescription = "DK Computer (टैप = चालू)"; isClickable = true; isFocusable = true
            setOnClickListener { setComputer(!computerMode) }
            setOnLongClickListener { c.startActivity(Intent(c, ComputerActivity::class.java)); true }
        }
        compEyes = ComputerEyes(c)
        compSeg.addView(compEyes, FrameLayout.LayoutParams(d(22f), d(22f), Gravity.CENTER))
        seg.addView(compSeg, LinearLayout.LayoutParams(d(34f), d(32f)).apply { leftMargin = d(2f) })
        row.addView(seg, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        modelPill = DkaiTheme.pill(c, "Model ▾", DkaiTheme.PILL, DkaiTheme.SUB, 12f).apply {
            minHeight = d(34f); contentDescription = "AI मॉडल चुनें"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = d(120f)
            setOnClickListener { DkaiSheets.models(this@DkaiScreen) }
            setOnLongClickListener { pickModel(it); true }
        }
        // मॉडल गोली बची जगह में (लंबा नाम "…") - दाईं ओर के 🎤 / 〰 कभी बाहर न धकेले जाएँ
        val modelBox = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        modelBox.addView(modelPill, LinearLayout.LayoutParams(-2, -2))
        row.addView(modelBox, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(8f) })
        // 🎤 = लिखवाना (जितना चाहें बोलें, शब्द डिब्बे में; ✓ = पूरा, भेजो)
        dictBtn = DkaiUi.iconBtn(c, DkaiIcon.Kind.MIC, "बोलकर लिखें", DkaiTheme.GRAY, 40f) { dictTap() }
        row.addView(dictBtn, LinearLayout.LayoutParams(d(40f), d(40f)).apply { rightMargin = d(6f) })
        // 〰 = सीधी आवाज़ में बातचीत (DK लाइव). कुछ लिखा हो तो यही ↑ भेजें
        micBtn = FrameLayout(c).apply {
            // accent से भरा उभरा गोल (दबाने पर धँसे) + हल्की accent चमक
            background = com.dk.assistant.ui.Clay.circle(c, 3f, accent = true)
            elevation = d(6f).toFloat(); isClickable = true; isFocusable = true
            if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = DkaiTheme.BLUE; outlineAmbientShadowColor = DkaiTheme.BLUE }
            setOnClickListener { if (input.text.isNotBlank() || attached.isNotEmpty()) send() else micTap() }
        }
        micIcon = ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.WAVE, DkaiTheme.ON)) }      // नीले बटन पर हमेशा सफ़ेद
        micBtn.addView(micIcon, FrameLayout.LayoutParams(d(22f), d(22f), Gravity.CENTER))
        row.addView(micBtn, LinearLayout.LayoutParams(d(44f), d(44f)))
        inputBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        parent.addView(inputBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(6f), d(12f), d(12f)) })
        micOrSend()
    }

    // ================================================================== 15 नवंबर 2026: नीचे की 5-टैब पट्टी
    /** होम • स्कैन • माइक • चैट • मेमोरी (मालिक: "अलग 5-टैब वाली नीचे की पट्टी भी बनाओ") - सब पहले से बने असली रास्ते */
    var tabsBar: LinearLayout? = null; private set

    private fun buildTabs() {
        // Chat स्क्रीन पर भी वही पट्टी, बस Chat टैब सक्रिय
        val sel = if (chatPage) com.dk.assistant.ui.HomeTabs.Tab.CHAT else com.dk.assistant.ui.HomeTabs.Tab.HOME
        val bar = com.dk.assistant.ui.HomeTabs.bar(c, DkaiTheme.GRAY, DkaiTheme.BLUE, DkaiTheme.CARD, sel) { t -> onTab(t) }
        tabsBar = bar
        com.dk.assistant.ui.HomeTabs.sync(c, bar)
        content.clipChildren = false; content.clipToPadding = false
        content.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(4f), d(12f), d(10f)) })
    }

    /**
     * पट्टी का टैब. होम पर (23 नवंबर 2026, मालिक: "कोई नई स्क्रीन या विंडो न खुले"): होम / स्कैन / चैट / मेमोरी
     * उसी बीच वाली जगह में ([showTab]); माइक = DK लाइव. Chat स्क्रीन (दूसरे रास्तों से खुली) पर पहले जैसा.
     */
    fun onTab(t: com.dk.assistant.ui.HomeTabs.Tab) {
        // 23 नवंबर 2026 (मालिक: "माइक वाला बटन ही कॉल कट बटन"): DK लाइव चालू = बीच का बटन लाल कॉल-कट, टैप = सीधे लाइव बंद
        if (t == com.dk.assistant.ui.HomeTabs.Tab.MIC && DKState.live) { voice.endCall(); com.dk.assistant.ui.HomeTabs.live(tabsBar, false); return }
        if (!chatPage) {
            when (t) {
                com.dk.assistant.ui.HomeTabs.Tab.MIC -> micTap()
                // 23 नवंबर 2026 (मालिक): स्कैन कभी पुरानी आवाज़ वाली विंडो में नहीं - सिर्फ़ इसी जगह (DK जुड़ा हो या नहीं)
                else -> if (!showTab(t) && !host.tab(t)) toast(t.label + " अभी नहीं खुला")
            }
            return
        }
        when (t) {
            com.dk.assistant.ui.HomeTabs.Tab.HOME -> { DkaiUi.closeTop(root); hideKeyboard(); activity.finish() }
            com.dk.assistant.ui.HomeTabs.Tab.CHAT -> { DkaiUi.closeTop(root); input.requestFocus() }
            // Chat स्क्रीन से स्कैन = होम का वही नया स्कैन टैब (ChatActivity होम पर लौटकर खोलता है), पुरानी विंडो नहीं
            com.dk.assistant.ui.HomeTabs.Tab.SCAN -> { DkaiUi.closeTop(root); hideKeyboard(); if (!host.tab(t)) toast(t.label + " अभी नहीं खुला") }
            com.dk.assistant.ui.HomeTabs.Tab.MIC -> micTap()
            else -> if (!host.tab(t)) toast(t.label + " अभी नहीं खुला")
        }
    }

    /** Settings से लौटे - "पट्टी छुपाएँ" बदला हो तो */
    fun syncTabs() = com.dk.assistant.ui.HomeTabs.sync(c, tabsBar)

    /** 〰 बटन: कुछ लिखा / फोटो जुड़ी = ↑ भेजें, खाली = आवाज़ से बात */
    private fun micOrSend() {
        if (!::micBtn.isInitialized) return
        val k = DkaiHomeCore.inputAction(input.text.isNotBlank(), attached.isNotEmpty(), dictation != null)
        val want = if (k == DkaiHomeCore.InputAction.SEND) DkaiIcon.Kind.SEND else DkaiIcon.Kind.WAVE
        if ((micIcon.drawable as? DkaiIcon)?.kind != want) micIcon.setImageDrawable(DkaiIcon(want, DkaiTheme.ON))
        micBtn.contentDescription = if (k == DkaiHomeCore.InputAction.SEND) "भेजें" else "आवाज़ से बात (DK लाइव)"
    }

    /** 🔍 / 🖥 चुनो: रंग + hint */
    fun setComputer(on: Boolean) {
        computerMode = on
        if (!::searchSeg.isInitialized) return
        searchSeg.setImageDrawable(DkaiIcon(DkaiIcon.Kind.SEARCH, if (on) DkaiTheme.GRAY else DkaiTheme.ON))
        searchSeg.background = null; compSeg.background = null
        segBar?.invalidate()   // उभरा accent बटन खिसके (200ms)
        compEyes.color = if (on) DkaiTheme.ON else DkaiTheme.GRAY
        input.hint = if (on) "DK Computer को काम दीजिए…" else DkaiHomeCore.HINT
    }

    // ================================================================== 🎤 लिखवाना
    private fun dictTap() {
        val d0 = dictation
        if (d0 != null) { finishDictation(send = true); return }
        if (!Setup.hasMic(c)) { host.ready(); return }
        dictBase = input.text.toString().trimEnd()
        // 21 नवंबर 2026: पहले फ़ोन पर Sherpa-ONNX (बोलते-बोलते, बिना नेट / key), न चले तो Android/Google (InlineDictation, पहले जैसा)
        val dd = com.dk.assistant.SherpaDictation.pick(c,
            onText = { t -> input.setText(DkaiHomeCore.joinDictation(dictBase, t)); input.setSelection(input.text.length) },
            onLevel = { lv -> dictBtn.scaleX = 1f + lv * 0.25f; dictBtn.scaleY = dictBtn.scaleX },
            onEnd = { msg -> if (msg != null) toast(msg); finishDictation(send = false) },
            phone = { t, l, e -> InlineDictation(c, onText = t, onLevel = l, onEnd = e) })
        dictation = dd
        dictBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHECK, DkaiTheme.ON))
        dictBtn.background = DkaiTheme.oval(DkaiKit.EMERALD)
        dictBtn.contentDescription = "✓ पूरा - भेजें"
        hideKeyboard()
        dd.start()
        micOrSend()
    }

    /** ✓ दबाया = रुककर भेजो; अपने-आप रुका = लिखा हुआ डिब्बे में ही रहे */
    private fun finishDictation(send: Boolean) {
        val dd = dictation ?: return
        dictation = null
        val t = try { dd.stop() } catch (_: Exception) { "" }
        if (t.isNotBlank()) { input.setText(DkaiHomeCore.joinDictation(dictBase, t)); input.setSelection(input.text.length) }
        dictBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.GRAY)); dictBtn.background = null
        dictBtn.scaleX = 1f; dictBtn.scaleY = 1f; dictBtn.contentDescription = "बोलकर लिखें"
        micOrSend()
        if (send && input.text.isNotBlank()) send()
    }

    fun toast(s: String) { UiLang.toast(c, s, Toast.LENGTH_SHORT).show() }

    // ================================================================== भेजना
    fun send() {
        if (dictation != null) { finishDictation(send = false) }
        val t = input.text.toString().trim()
        val pics = ArrayList(attached)
        if (t.isEmpty() && pics.isEmpty()) return
        when (DkaiHomeCore.sendRoute(computerMode, pics.size)) {
            DkaiHomeCore.SendRoute.CHAT -> { if (!chatRoute(t)) return }
            DkaiHomeCore.SendRoute.COMPUTER -> c.startActivity(Intent(c, ComputerActivity::class.java).putExtra("task", t))
            DkaiHomeCore.SendRoute.COMPUTER_FILES -> sendFiles(pics, t)
            DkaiHomeCore.SendRoute.CHAT_FILES -> { if (!sendChatFiles(pics, t)) return }
        }
        input.setText(""); clearAttached()
    }

    /** फोटो / फ़ाइल -> DK Computer के फ़ोल्डर में कॉपी -> वहीं काम (लिखा हो तो वही सवाल) */
    private fun sendFiles(pics: List<android.net.Uri>, t: String) {
        val app = c.applicationContext
        Thread {
            val names = pics.take(DkaiHomeCore.MAX_ATTACH).mapNotNull { u -> try { ComputerActivity.copyIn(app, u) } catch (_: Throwable) { null } }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (names.isEmpty()) { toast("फ़ाइल नहीं जुड़ पाई (30MB तक की फोटो / PDF / text)"); return@runOnUiThread }
                val i = Intent(c, ComputerActivity::class.java)
                if (t.isNotBlank()) i.putExtra("task", ComputerActivity.attachNote(names) + t)
                else i.putStringArrayListExtra(ComputerActivity.EXTRA_ATTACHED, ArrayList(names))
                c.startActivity(i)
            }
        }.start()
    }

    /**
     * 23 नवंबर 2026 (मालिक: "लाइव के दौरान फोटो अटैच = सीधे DK को, DK देखकर जवाब दे; text / फ़ाइल = उसका लिखा DK पढ़े"):
     * DK लाइव जुड़ी हो तो फ़ाइलें उसी लाइव बातचीत में - फोटो तस्वीर की तरह ([com.dk.assistant.GeminiLive.sendFrame]) और
     * look_photo के लिए याद ([DkaiChatFiles.remember]), text फ़ाइल का लिखा SYSTEM NOTE में ([DkaiChatFiles.liveNote]).
     * सत्र नहीं टूटता (वही connection). false = लाइव नहीं - पहले वाला चैट रास्ता.
     */
    private fun liveFiles(pics: List<android.net.Uri>, t: String): Boolean {
        val g = DKAccessibilityService.instance?.gemLive
        val can = try { g?.canTalk() == true } catch (_: Exception) { false }
        if (g == null || !DkaiHomeCore.attachToLive(DKState.live, can, pics.size)) return false
        val n = pics.size.coerceAtMost(DkaiHomeCore.MAX_ATTACH)
        DKState.userNow("📎 $n फ़ाइल" + if (t.isNotBlank()) " • $t" else "")
        val app = c.applicationContext
        activity.lifecycleScope.launch {
            try {
                DKState.hint("फ़ाइल DK लाइव को दे रहा हूँ…")
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DkaiChatFiles.prepare(app, pics) }
                if (r.imageB64 == null && r.texts.isEmpty()) { DKState.hint(""); toast("यह फ़ाइल नहीं खुल पाई — फोटो (JPG/PNG), PDF या text फ़ाइल जोड़िए।"); return@launch }
                DkaiChatFiles.remember(r.imageB64)
                r.imageB64?.let { b64 -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try { g.sendFrame(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
                } }
                g.pushNote(DkaiChatFiles.liveNote(t, r.names, r.texts, r.images))
                DKState.hint("")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { com.dk.assistant.Quiet.note(e); DKState.hint("") }
        }
        return true
    }

    /** 14 नवंबर 2026 (मालिक): चैट में जुड़ी फोटो / फ़ाइल चैट में ही - AI देखकर यहीं जवाब दे */
    private fun sendChatFiles(pics: List<android.net.Uri>, t: String): Boolean {
        if (liveFiles(pics, t)) return true   // लाइव चालू: फ़ाइल/फ़ोटो सीधे DK लाइव को, सत्र नहीं टूटता
        if (!Prefs.hasAnyKey(c)) { toast("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"); host.ready(); return false }
        val n = pics.size.coerceAtMost(DkaiHomeCore.MAX_ATTACH)
        DKState.userNow("📎 $n फ़ाइल" + if (t.isNotBlank()) " • $t" else "")
        val app = c.applicationContext
        val b = BrainManager(c)
        localJob?.cancel()
        localJob = activity.lifecycleScope.launch {
            try {
                DKState.hint("फ़ाइल पढ़ रहा हूँ…")
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DkaiChatFiles.prepare(app, pics) }
                if (r.imageB64 == null && r.texts.isEmpty()) { DKState.replyFailed("यह फ़ाइल नहीं खुल पाई — फोटो (JPG/PNG), PDF या text फ़ाइल जोड़िए।"); return@launch }
                DkaiChatFiles.remember(r.imageB64)      // DK Live से "इस फोटो में क्या है" भी पूछ सकें
                DKState.hint("देखकर जवाब लिख रहा हूँ…")
                val raw = b.teach(DkaiChatFiles.SYSTEM, DkaiChatFiles.prompt(t, r.names, r.texts, r.images), r.imageB64)
                DKState.reply(DkaiChatFiles.parseSay(raw).ifBlank { "…" })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { DKState.replyFailed(b.explainAll(e))
            } finally { DKState.set(Mode.IDLE) }
        }
        return true
    }

    // ================================================================== + : फोटो चिप
    private var pickSeq = 0

    /** एक बार में 5 तक फोटो (गैलरी) */
    fun pickImages() {
        val left = DkaiHomeCore.MAX_ATTACH - attached.size
        if (left <= 0) { toast("एक बार में ${DkaiHomeCore.MAX_ATTACH} तक"); return }
        lateinit var l: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>
        val contract = if (left >= 2) androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(left) else null
        if (contract != null) {
            l = activity.activityResultRegistry.register("dkai_imgs_${pickSeq++}", contract) { us ->
                try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
                addAttached(us)
            }
        } else {
            l = activity.activityResultRegistry.register("dkai_img_${pickSeq++}", androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u ->
                try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
                if (u != null) addAttached(listOf(u))
            }
        }
        try { l.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }; toast("गैलरी नहीं खुली") }
    }

    /** PDF / text / फोटो फ़ाइल */
    fun pickFiles() {
        lateinit var l: androidx.activity.result.ActivityResultLauncher<Array<String>>
        l = activity.activityResultRegistry.register("dkai_files_${pickSeq++}", androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { us ->
            try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            addAttached(us)
        }
        try { l.launch(ComputerActivity.PICK_TYPES) } catch (_: Exception) { try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }; toast("फ़ाइल चुनने वाला नहीं खुला") }
    }

    private fun addAttached(us: List<android.net.Uri>) {
        val (keep, over) = DkaiHomeCore.addUpTo(attached, us, DkaiHomeCore.MAX_ATTACH)
        attached.clear(); attached.addAll(keep)
        if (over > 0) toast("एक बार में ${DkaiHomeCore.MAX_ATTACH} तक - $over छोड़ दीं")
        renderAttached()
    }

    private fun clearAttached() { attached.clear(); renderAttached() }

    private fun renderAttached() {
        if (!::attachRow.isInitialized) return
        attachRow.removeAllViews()
        for (u in attached.toList()) {
            val box = FrameLayout(c)
            box.addView(ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; background = DkaiTheme.round(c, DkaiTheme.FIELD, 12f, DkaiTheme.BORDER)
                clipToOutline = true; contentDescription = "जुड़ी फ़ाइल"
                try { setImageURI(u) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
                if (drawable == null) { setImageDrawable(DkaiIcon(DkaiIcon.Kind.DOC, DkaiTheme.GRAY)); scaleType = ImageView.ScaleType.CENTER_INSIDE; val p = d(16f); setPadding(p, p, p, p) }
            }, FrameLayout.LayoutParams(d(58f), d(58f)).apply { topMargin = d(5f); rightMargin = d(5f) })
            box.addView(ImageView(c).apply {
                setImageDrawable(DkaiIcon(DkaiIcon.Kind.X, DkaiTheme.ON)); val p = d(3f); setPadding(p, p, p, p)
                background = DkaiTheme.oval(0xCC111827.toInt()); contentDescription = "हटाएँ"; isClickable = true
                setOnClickListener { attached.remove(u); renderAttached() }
            }, FrameLayout.LayoutParams(d(20f), d(20f), Gravity.TOP or Gravity.END))
            attachRow.addView(box, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(6f) })
        }
        attachScroll.visibility = if (attached.isEmpty()) View.GONE else View.VISIBLE
        micOrSend()
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

    /** साझा साँचे वाले जवाब के नीचे: [com.dk.assistant.GroundView.footer] (वही चिप, वही "स्रोत (N)" सूची) DKAI के रंग में */
    private fun citeBox(m: DKState.Msg): View? {
        val foot = com.dk.assistant.GroundView.footer(c, m.text, m.src, m.g, DkaiTheme.WHITE, DkaiTheme.GRAY, DkaiTheme.CYAN) ?: return null
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12f), d(6f), d(12f), d(6f))
            background = DkaiTheme.round(c, DkaiTheme.FIELD, 14f, DkaiTheme.BORDER)
            addView(foot, LinearLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
        }
    }

    /**
     * 23 नवंबर 2026: Chat स्क्रीन पर लिखा = ChatActivity का वही भेजना ("बोलकर भी", रुकना, "फिर से भेजें");
     * इमेज बनाना / डीप रिसर्च पहले की तरह यहीं से. होम पर (अब इनपुट नहीं) पुराना रास्ता.
     */
    private fun chatRoute(t: String): Boolean {
        val send = host.chatSend
        if (send == null || com.dk.assistant.FluxImage.wanted(t) || com.dk.assistant.research.DeepResearch.wanted(t, Prefs.deepAuto(c))) return sendChat(t, speak = ChatSpeak.on(c))
        return send(t)
    }

    /** चैट वाला भेजना (ChatActivity जैसा): Accessibility चालू = DK का पूरा दिमाग़ (फ़ोन के काम भी), नहीं = सिर्फ़ बातचीत */
    private fun sendChat(t: String, speak: Boolean = false): Boolean {
        // 20 नवंबर 2026 (मालिक): "यूज़र इमेज माँगे तो इमेज सीधे चैट में दिखे" - सिर्फ़ DK सर्वर का Cloudflare FLUX (key की ज़रूरत नहीं)
        if (com.dk.assistant.FluxImage.wanted(t)) {
            DKState.userNow(t)
            if (!com.dk.assistant.FluxImage.on(c)) { DKState.reply(com.dk.assistant.FluxImage.OFF_MSG); return true }
            localJob?.cancel()
            localJob = activity.lifecycleScope.launch { com.dk.assistant.FluxImage.chat(c, t) }
            return true
        }
        if (!Prefs.hasAnyKey(c)) { toast("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"); host.ready(); return false }
        DKState.userNow(t)
        // 14 नवंबर 2026 (मालिक): "डीप रिसर्च" - Exa + Tavily + Linkup एक साथ, Firecrawl से पूरे पेज, Gemini की हिंदी रिपोर्ट
        if (com.dk.assistant.research.DeepResearch.wanted(t, Prefs.deepAuto(c))) { deepResearch(t); return true }
        val svc = DKAccessibilityService.instance
        // Chat टैब: "बोलकर भी" चालू = जवाब बोलकर भी (पहले वाले Chat जैसा); बंद / DK लाइव से आई बात = सिर्फ़ लिखकर
        if (svc != null) { svc.runCommand(t, speak = speak, typed = speak); return true }
        fun answer(x: String) {
            DKState.reply(x)
            if (speak) { ChatSpeak.sayIfOn(c, x) }
        }
        val b = BrainManager(c)
        localJob?.cancel()
        localJob = activity.lifecycleScope.launch {
            try {
                LocalBrain.instant(c, t)?.let { answer(it); return@launch }
                // 15 नवंबर 2026 (मालिक): बिना नेट = DK का अपना दिमाग तुरंत; पहले पूछा पक्का सवाल = अपना जवाब (request बचे)
                if (!com.dk.assistant.NetProbe.online(c)) { answer(com.dk.assistant.OwnMind.offline(c, t)); return@launch }
                com.dk.assistant.OwnMind.saved(c, t)?.let { answer(it); return@launch }
                val sb = StringBuilder()
                fun clean(x: String) = x.replace(Regex("\\[(SCREEN|SEARCH|THINK)\\]"), "").substringBefore("[YAAD").replace(Regex("[*#`]"), "").trim()
                val full = b.streamChat("USER SAYS: $t", "USER SAYS: $t") { dl ->
                    sb.append(dl); val cl = clean(sb.toString())
                    if (cl.isNotBlank() && !cl.startsWith("{")) DKState.replyLive(cl)
                }
                val cl = clean(full)
                val out = when {
                    full.contains("[SCREEN]") || full.contains("need_screen") -> "फ़ोन पर काम करने के लिए पहले Accessibility में DK चालू करें।"
                    cl.startsWith("{") -> try { org.json.JSONObject(cl.substring(0, cl.lastIndexOf('}') + 1)).optString("say") } catch (_: Exception) { cl }.ifBlank { "…" }
                    else -> cl.ifBlank { "…" }
                }
                DKState.reply(out, emptyList(), b.lastGround)
                if (speak) ChatSpeak.sayIfOn(c, out)
                // 15 नवंबर 2026: हर जवाब से DK का अपना दिमाग सीखे
                if (!cl.startsWith("{")) { val g = b.lastGround != null; launch(kotlinx.coroutines.Dispatchers.IO) { com.dk.assistant.OwnMind.learn(c, t, out, g) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { com.dk.assistant.OwnMind.answer(c, t)?.let { DKState.reply(it) } ?: DKState.replyFailed(b.explainAll(e))
            } finally { DKState.set(Mode.IDLE) }
        }
        return true
    }

    private fun deepResearch(t: String) {
        // 18 नवंबर 2026 (मालिक): डीप रिसर्च = लाइसेंस (बेसिक से). मुफ़्त = आम चैट चलती रहे, बस यह नहीं
        com.dk.assistant.backend.Plan.check(c, com.dk.assistant.backend.Plan.Work.RESEARCH)?.let { DKState.replyFailed(it); return }
        localJob?.cancel()
        localJob = activity.lifecycleScope.launch {
            try {
                DKState.hint("डीप रिसर्च शुरू…")
                // 14 नवंबर 2026 (मालिक: "लाइव दिखे कौन-कौन सी वेबसाइट पर जा रहा है"): हर कदम + असली वेबसाइटें लाइव सर्च कार्ड में
                DkaiSearchLive.start(com.dk.assistant.research.DeepResearch.topic(t), maxMs = DkaiSearchLive.DEEP_MS)
                DkaiSearchLive.stage("डीप रिसर्च शुरू…")
                val r = com.dk.assistant.research.DeepResearchRun(c.applicationContext, { urls -> DkaiSearchLive.add(urls) }) { step ->
                    DkaiSearchLive.stage(step); activity.runOnUiThread { DKState.hint(step) } }.run(t)
                DKState.reply(r.text, r.sources)
            } catch (e: kotlinx.coroutines.CancellationException) { DkaiSearchLive.cancel(); throw e
            } catch (e: Exception) { DkaiSearchLive.cancel(); DKState.replyFailed("डीप रिसर्च नहीं हो पाई — " + com.dk.assistant.ChatFlow.plain(com.dk.assistant.BrainManager(c).explainAll(e)))   // 15 नवंबर 2026: अंग्रेज़ी तकनीकी गलती नहीं
            } finally { DKState.set(Mode.IDLE) }
        }
    }

    private fun newChat() {
        if (DKState.messages.isEmpty()) startNewChat()
        else android.app.AlertDialog.Builder(c).setTitle("नई चैट शुरू करें?").setMessage("यह चैट \"सभी चैट\" सूची में सेव रहेगी (ऊपर Chat नंबर पर टैप करके फिर खोल सकते हैं)।")
            .setPositiveButton("नई चैट") { _, _ -> startNewChat() }.setNegativeButton("रद्द", null).show()
    }

    /** नई चैट: अभी वाली सूची में सेव, फिर अगला नंबर (सबसे बड़े + 1) */
    fun startNewChat() {
        if (DKState.messages.isEmpty()) {
            if (::input.isInitialized) { input.requestFocus() }
            return
        }      // खाली चैट पर दोबारा नई नहीं
        localJob?.cancel()
        DkaiChats.save(DkaiChats.dir(c), Dkai.chatNo(c), DKState.messages.toList())
        DKState.clearChat(); Dkai.nextChat(c)
        ChatFresh.changed(c, Dkai.chatNo(c), reopened = false)    // 19 नवंबर 2026: नई चैट = नई Live बातचीत, जुड़ते ही सिर्फ़ नमस्ते
        if (::chatLabel.isInitialized) chatLabel.text = DkaiCore.chatLabel(Dkai.chatNo(c))
        shownMsgs = -1; liveSince = System.currentTimeMillis(); render()
        if (::input.isInitialized) { input.setText(""); input.requestFocus() }
    }

    /** पुरानी चैट खोलो: अभी वाली सेव, वह चैट स्क्रीन पर (पूरी बातचीत), आगे उसी में बात */
    fun openChat(n: Int) {
        val cur = Dkai.chatNo(c)
        if (n == cur) return
        if (DKState.mode == Mode.THINKING) { toast("DK अभी जवाब लिख रहा है - पूरा होने दें, फिर चैट बदलें"); return }
        localJob?.cancel()
        val dir = DkaiChats.dir(c)
        DkaiChats.save(dir, cur, DKState.messages.toList())
        DKState.replaceChat(DkaiChats.load(dir, n))
        Dkai.setChat(c, n)
        ChatFresh.changed(c, n, reopened = true)                   // 19 नवंबर 2026: पुरानी चैट = Live उसी के हिसाब से बात करे
        if (::chatLabel.isInitialized) chatLabel.text = DkaiCore.chatLabel(n)
        shownMsgs = -1; liveSince = System.currentTimeMillis(); render()
        if (::input.isInitialized) input.setText("")
        // होम से पुरानी चैट चुनी = पूरी बातचीत Chat स्क्रीन पर
        if (chatPage) homeScroll.post { homeScroll.scrollTo(0, homeCol.height) } else if (!showTab(com.dk.assistant.ui.HomeTabs.Tab.CHAT)) host.tab(com.dk.assistant.ui.HomeTabs.Tab.CHAT)
    }

    private fun pickModel(anchor: View) {
        val id = Prefs.activeProvider(c)
        val cur = Prefs.model(c, id)
        // 13 नवंबर 2026 (मालिक): यहाँ भी सिर्फ़ फ्री मॉडल (paid key बताई हो तो Gemini के सब)
        val list = DkaiKit.pickable(id, (listOf(cur) + Prefs.models(c, id) + Providers.fallbackModels(id)).filter { it.isNotBlank() }.distinct(),
            Prefs.bool(c, DkaiKit.PREF_GEMINI_PAID, false)).take(40)
        val items = list.map { m -> (if (m == cur) "✓ " else "    ") + ModelNames.fromId(m) }.toMutableList()
        items.add("और AI / मॉडल (सेटिंग्स)…")
        android.app.AlertDialog.Builder(c).setTitle((Providers.byId(id)?.name ?: id) + " मॉडल")
            .setItems(items.toTypedArray()) { _, i ->
                if (i >= list.size) c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain"))
                else { DkaiPick.choose(c, id, list[i]); renderModel(); toast("मॉडल: ${ModelNames.fromId(list[i])}") }
            }.show()
        anchor.isPressed = false
    }

    fun renderModel() {
        // 20 नवंबर 2026: चिप = केंद्रीय selectedModel ([SelectedModel]); कुछ न चुना = "Auto" (पहले जैसी routing)
        val ch = SelectedModel.get(c)
        if (::modelPill.isInitialized) modelPill.text = (if (ch == null) "Auto" else DkaiCore.modelPill(DkaiKit.providerName(ch.provider), ch.model)) + " ▾"
    }

    // ================================================================== आवाज़ (State 2/3)
    fun micTap() {
        if (!host.ready()) return
        if (dictation != null) finishDictation(send = false)       // 🎤 लिखवाना और 〰 बातचीत एक साथ नहीं
        hideKeyboard()
        voice.newSession()
        liveSince = System.currentTimeMillis()
        // 23 नवंबर 2026 (मालिक): होम पर बात = बीच का ORB / अवतार और लाइव टेक्स्ट बॉक्स ही दिखें (नीचे छोटी पट्टी में
        // वही mute / ✕ / बड़ी स्क्रीन वाले बटन). Chat स्क्रीन पर पहले जैसी पूरी आवाज़ स्क्रीन
        if (chatPage) setState(DkaiCore.next(voiceSessionState, VoiceEvent.MIC_TAP))
        else if (!voiceSessionState.active) setState(VoiceSessionState.ACTIVE_MINIMIZED)
        startLive()
    }

    /** 14 नवंबर 2026 (मालिक): मोड टैब हटे - DK लाइव हमेशा अपने पुराने रास्ते से (null) */
    fun liveMode(): DkaiMode? = null

    fun scrollHomeEnd() {
        val sc = chatScroll
        if (!chatPage && sc != null) sc.post { sc.smoothScrollTo(0, chatList.height) } else homeScroll.post { homeScroll.smoothScrollTo(0, homeCol.height) }
    }

    /** आवाज़ की बड़ी स्क्रीन छोटी पट्टी बने - ताकि पीछे बनता काम दिखे */
    private fun peek() { if (voiceSessionState == VoiceSessionState.ACTIVE_FULL) setState(DkaiCore.next(voiceSessionState, VoiceEvent.MINIMIZE)) }

    /** DK लाइव (Chat टैब): कहानी चैट में लिखी जाए */
    fun chatFromLive(t: String): Boolean { peek(); return sendChat(t) }

    fun startLive() {
        val svc = DKAccessibilityService.instance
        when {
            svc == null -> { if (!Setup.selfEnableAccessibility(c)) toast("सेटिंग्स → Accessibility में ${Prefs.assistantName(c)} ON करें") }
            else -> svc.micPressed("DKAI माइक")      // 14 नवंबर 2026: एक बार वाला छोटा टाइमर साफ़ (Live जल्दी न कटे)
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

    /** छोटी तैरती पट्टी यहाँ दिख सकती है? (होम पर नहीं - सिर्फ़ Chat स्क्रीन / Chat टैब) */
    fun miniAllowed(): Boolean = DkaiHomeCore.miniShown(chatPage, tabNow == com.dk.assistant.ui.HomeTabs.Tab.CHAT)

    private fun padForMini() {
        val extra = if (voiceSessionState == VoiceSessionState.ACTIVE_MINIMIZED && miniAllowed()) d(84f) else 0
        homeScroll.setPadding(0, 0, 0, extra)          // चैट का आख़िरी संदेश पट्टी के नीचे न छिपे (scroll करके दिखे)
    }

    /** छोटी पट्टी: लिखने के डिब्बे (होम) / टैब-पट्टी के ठीक ऊपर */
    private fun placeMini() {
        if (!::root.isInitialized || !::voice.isInitialized) return
        val h = root.height; if (h <= 0) return
        val anchor: View = if (::inputBox.isInitialized && inputBox.isShown) inputBox else tabsBar?.takeIf { it.visibility == View.VISIBLE } ?: return
        val loc = IntArray(2); val rl = IntArray(2)
        anchor.getLocationInWindow(loc); root.getLocationInWindow(rl)
        val top = loc[1] - rl[1]
        val want = (h - top + d(8f)).coerceAtLeast(d(8f))
        val lp = voice.mini.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != want) { lp.bottomMargin = want; voice.mini.layoutParams = lp }
    }

    // ================================================================== 23 नवंबर 2026: होम का बीच (ORB / अवतार) + लाइव टेक्स्ट
    private val homeLevel: () -> Float = {
        when {
            DKState.mode == Mode.SPEAKING -> com.dk.assistant.voice.engine.VoiceLevel.mouth() ?: 0.25f
            DKState.live && !DKState.micMuted -> DKState.level
            else -> 0f
        }
    }
    private val homeActive: () -> Boolean = { DKState.live && (DKState.mode == Mode.SPEAKING || !DKState.micMuted) }
    private var homeCallNow = DkaiCore.Status.READY
    /** ORB / अवतार की हालत: सुन रहा / सोच रहा / बोल रहा (glow का रंग इसी से) */
    private val homeCall: () -> Int = {
        when (DkaiCore.callState(homeCallNow)) {
            DkaiCore.Call.SPEAKING -> FluidOrbView.SPEAK
            DkaiCore.Call.PROCESSING -> FluidOrbView.THINK
            else -> if (DKState.live) FluidOrbView.LISTEN else -1
        }
    }

    /** अभी की हालत - Live जुड़ा है या नहीं (Gemini Live / अलग तरीका / बैकअप) भी देखकर */
    private fun homeStatusNow(): DkaiCore.Status {
        val svc = DKAccessibilityService.instance
        val connected = DKState.live && ((try { svc?.gemLive?.canTalk() == true } catch (_: Exception) { false }) ||
            (try { svc?.voiceUp() == true } catch (_: Exception) { false }))
        return DkaiCore.homeLive(Prefs.hasAnyKey(c), DKState.shownMode(), DKState.live, DKState.micMuted, connected, DkaiSearchLive.active())
    }

    private fun statusColor(st: DkaiCore.Status): Int = when (st) {
        DkaiCore.Status.READY -> DkaiKit.EMERALD
        DkaiCore.Status.SETUP, DkaiCore.Status.CONNECTING -> DkaiTheme.AMBER
        DkaiCore.Status.SPEAKING -> DkaiTheme.CYAN
        DkaiCore.Status.LISTENING -> DkaiTheme.GREEN
        DkaiCore.Status.MUTED -> DkaiTheme.RED
        DkaiCore.Status.RESEARCHING -> DkaiTheme.BLUE
        else -> DkaiTheme.VIOLET
    }

    /** बीच में टैप: बात शुरू; बात चल रही हो तो पूरी आवाज़ स्क्रीन (mute / ✕ / लेंस वाले सारे बटन) */
    private fun centerTap() { if (DKState.live) setState(VoiceSessionState.ACTIVE_FULL) else micTap() }

    private var avatarTried: String? = null
    private var centerGlow: View? = null

    /**
     * कैरेक्टर के ऊपर वाला लाइव कार्ड: हल्का पारदर्शी काँच (पीछे कैरेक्टर दिखता रहे) - थीम के surface से,
     * ऊपर-बाएँ हल्की चमक वाली किनारी. रंग सिर्फ़ [com.dk.assistant.ui.ClayTheme.t] से.
     */
    private fun glassCard(): android.graphics.drawable.Drawable {
        val k = com.dk.assistant.ui.ClayTheme.t
        return android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(com.dk.assistant.ui.NeonTheme.alpha(com.dk.assistant.ui.NeonTheme.mix(k.surface, k.rim, 0.12f), 0xC4), com.dk.assistant.ui.NeonTheme.alpha(k.surface, 0xA8))).apply {
            cornerRadius = d(com.dk.assistant.ui.ClayCard.RADIUS_DP).toFloat()   // गोलाई साझा कार्ड वाली ([com.dk.assistant.ui.ClayCard])
            setStroke(maxOf(1, d(1f)), com.dk.assistant.ui.NeonTheme.alpha(k.rim, 0x88))
        }
    }

    /** ORB = 260dp का गोला (पहले जैसा); अवतार = अभिवादन और READY कार्ड के बीच की पूरी जगह */
    private fun avatarArea(avatar: Boolean): LinearLayout.LayoutParams =
        if (avatar) LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d(4f) }
        else LinearLayout.LayoutParams(d(260f), d(260f)).apply { topMargin = d(4f) }

    /** Settings में ORB चुना = ORB; 3D अवतार चुना और तस्वीर फ़ोन में = अवतार; तस्वीर नहीं = ORB (और पीछे से डाउनलोड) */
    private fun renderCenter() {
        if (chatPage || !::centerOrb.isInitialized) return
        val av = HomeAvatars.chosenId(c)?.let { HomeAvatars.byId(c, it) }
        // पूरी स्क्रीन के लिए बड़ी तस्वीर (स्क्रीन की लंबाई तक)
        val bmp = av?.let { HomeAvatars.bitmap(c, it, maxOf(c.resources.displayMetrics.heightPixels, d(320f))) }
        if (av != null && bmp == null && avatarTried != av.id) {
            avatarTried = av.id
            HomeAvatars.fetch(c, av.id) { ok -> if (ok && !activity.isDestroyed) { centerSig = ""; renderCenter() } }
        }
        val classic = DkaiOrbs.get(c) == DkaiOrbs.CLASSIC
        val sig = "${av?.id}|${bmp != null}|$classic|${DkaiOrbs.get(c)}"
        if (sig == centerSig) return
        centerSig = sig
        val showAv = bmp != null
        // 23 नवंबर 2026 (मालिक का कोलाज): ऐप के अंदर वाला आर्टवर्क = बीच में, कभी न कटे, पीछे रोशनी + बहती वेव
        centerAvatar.art = showAv && av?.res != null
        centerAvatar.orb = av?.orb == true
        centerAvatar.badge = av?.badge
        centerAvatar.subject = av?.subject ?: HomeAvatars.FULL_SUBJECT
        centerAvatar.tablet = com.dk.assistant.UiFitRules.isTablet(c.resources.configuration.smallestScreenWidthDp)
        // आर्टवर्क READY पट्टी से नीचे की बार के ठीक ऊपर तक (नीचे की खाली पट्टी सिर्फ़ ORB के लिए)
        heroBox.setPadding(0, 0, 0, if (centerAvatar.art) 0 else d(maxOf(0f, GREET_LIFT_DP - cardDrop)))
        // 23 नवंबर 2026 (मालिक: "अवतार पूरी स्क्रीन नहीं"): अवतार सिर्फ़ अभिवादन के नीचे से READY कार्ड के ऊपर तक (बीच वाली जगह),
        // अनुपात वही (Fit), किनारे ऐप के पीछे में घुलते हुए ([HomeAvatarView.fade]). कार्ड / टॉप बार / नीचे की पट्टी पर नहीं चढ़ता.
        bgAvatar.bitmap = null
        centerAvatar.bitmap = bmp
        centerAvatar.visibility = if (showAv) View.VISIBLE else View.GONE
        centerGlow?.layoutParams = avatarArea(showAv)
        centerGlow?.background?.alpha = if (showAv) 0 else 255
        liveBox.background = if (showAv) glassCard() else com.dk.assistant.ui.Clay.card(c)
        liveBox.setTag(com.dk.assistant.PlainLook.SKIP, if (showAv) true else null)   // काँच वाला कार्ड ठोस Clay में न बदले
        // कैरेक्टर के ऊपर अभिवादन साफ़ पढ़ा जाए: हल्की परछाईं (थीम का पर्दा-रंग). ऊपर की पट्टी में हो तो परछाईं नहीं
        val overArt = showAv && greetSlot == null
        val sh = com.dk.assistant.ui.ClayTheme.scrim(if (overArt) 0.7f else 0f)
        for (tv in listOf(greetTxt, nameTxt)) if (overArt) tv.setShadowLayer(d(6f).toFloat(), 0f, d(1f).toFloat(), sh) else tv.setShadowLayer(0f, 0f, 0f, 0)
        renderBack()
        centerOrb.visibility = if (!showAv && !classic) View.VISIBLE else View.GONE
        centerClassic.visibility = if (!showAv && classic) View.VISIBLE else View.GONE
        centerLogo.visibility = if (showAv) View.GONE else View.VISIBLE
    }

    /** लाइव टेक्स्ट बॉक्स: हालत + यूज़र की पूरी लाइव बात, फिर उसी डिब्बे में DK की लाइव बात */
    private fun renderLive(st: DkaiCore.Status) {
        if (chatPage || !::liveBox.isInitialized) return
        // 23 नवंबर 2026 (मालिक): कार्ड सिर्फ़ DK लाइव चलते समय - बंद होते ही छिपे, कोई डिफ़ॉल्ट लाइन नहीं
        val show = DkaiHomeCore.liveCardShown(DKState.live)
        DkaiTheme.gone(liveBox, !show)
        if (!show) {
            if (liveTxt.text.isNotEmpty()) { liveTxt.text = "" }
            return
        }
        val col = statusColor(st)
        if (liveState.text.toString() != st.label) liveState.text = st.label
        liveState.setTextColor(col); liveDot.color = col
        liveDot.pulsing = st != DkaiCore.Status.READY && st != DkaiCore.Status.SETUP; liveDot.invalidate()
        val lastDk = DKState.messages.lastOrNull { it.role != "u" }
        val t = DkaiCore.homeText(st, DKState.lastUser, DKState.lastUserAt, DKState.liveReply, lastDk?.text.orEmpty(), lastDk?.time ?: 0L, liveSince)
        val body = t?.first ?: when (st) {
            DkaiCore.Status.SETUP -> "पहले Settings में AI की key डालें — फिर बात कीजिए"
            DkaiCore.Status.CONNECTING -> "जुड़ रहा है…"
            DkaiCore.Status.LISTENING -> "सुन रहा हूँ — बोलिए…"
            DkaiCore.Status.MUTED -> DkaiCore.MUTED_LINE
            DkaiCore.Status.THINKING -> DKState.hintText.ifBlank { "सोच रहा हूँ…" }
            DkaiCore.Status.RESEARCHING -> DKState.hintText.ifBlank { "रिसर्च कर रहा हूँ…" }
            DkaiCore.Status.PROCESSING -> DKState.hintText.ifBlank { "काम कर रहा हूँ…" }
            else -> ""
        }
        val who = when (t?.second) { true -> "आप"; false -> Prefs.assistantName(c); null -> "" }
        if (liveWho.text.toString() != who) liveWho.text = who
        liveTxt.setTextColor(if (t == null) DkaiTheme.GRAY else DkaiTheme.WHITE)
        if (liveTxt.text.toString() != body) { liveTxt.text = body; liveScroll.post { liveScroll.fullScroll(View.FOCUS_DOWN) } }
        liveBox.contentDescription = st.label + ". " + (if (who.isNotBlank()) "$who: " else "") + body
    }

    // ================================================================== हर बदलाव पर स्क्रीन ताज़ा
    private val stateL: () -> Unit = { render() }
    private val visionL: () -> Unit = { voice.render() }
    private var resumed = false

    /** DK Computer काम कर रहा हो तो 🖥 की आँखें घूमें */
    private val eyesTick = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (::compEyes.isInitialized) compEyes.working = ComputerSession.busy
            if (!chatPage) render()        // होम की हालत / लाइव टेक्स्ट (हर सेकंड)
            ui.postDelayed(this, 1000)
        }
    }

    /** किस रंग (लाइट/डार्क) में बना था - सेटिंग में बदला हो तो लौटते ही नए रंग में (14 नवंबर 2026) */
    private var builtLight = false
    private var builtTheme = com.dk.assistant.ui.ClayTheme.version

    fun onResume() {
        DkaiLive.attach(this)       // दो स्क्रीन (होम + Chat) हों तो जो सामने है वही DK लाइव से जुड़ा
        centerSig = ""; avatarTried = null
        DkaiTheme.load(c)
        if (DkaiTheme.light != builtLight) { activity.recreate(); return }
        if (com.dk.assistant.ui.ClayTheme.version != builtTheme) { activity.recreate(); return }   // Settings › Theme में थीम बदली
        renderGreeting()
        resumed = true
        if (tabNow == com.dk.assistant.ui.HomeTabs.Tab.SCAN) scanPanel?.show()        // इजाज़त / ऐप लौटा = लेंस फिर
        if (tabNow == com.dk.assistant.ui.HomeTabs.Tab.MEMORY) memPanel?.refresh()
        ui.removeCallbacks(eyesTick); ui.post(eyesTick)
        DKState.listen(stateL); com.dk.assistant.LiveVision.listen(visionL)
        renderModel(); render()
    }

    fun onPause() {
        resumed = false
        scanPanel?.hide()           // ऐप पीछे = कैमरा बंद (टैब वही रहता है, लौटते ही फिर)
        DKState.unlisten(stateL); com.dk.assistant.LiveVision.unlisten(visionL)
        if (dictation != null) finishDictation(send = false)      // ऐप से बाहर = माइक छोड़ो, लिखा हुआ डिब्बे में रहे
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
        // होम का बैज: 23 नवंबर 2026 (मालिक) - CONNECTING -> LISTENING, THINKING, SPEAKING, RESEARCHING / PROCESSING
        val st = homeStatusNow()
        if (::readyTxt.isInitialized) {
            val col = statusColor(st)
            readyTxt.text = st.label; readyTxt.setTextColor(col); readyDot.color = col; readyDot.invalidate()
            // THINKING = बिंदु की जगह चुना हुआ टाइपिंग एनीमेशन (बंद / कम एनिमेशन = पहले जैसा बिंदु)
            val thinkAnim = st == DkaiCore.Status.THINKING && thinkAnimOn()
            DkaiTheme.gone(readyAnim, !thinkAnim); DkaiTheme.gone(readyDot, thinkAnim)
            readyAnim.state = if (thinkAnim) com.dk.assistant.WorkState.THINKING else com.dk.assistant.WorkState.IDLE
        }
        homeCallNow = st
        renderGreeting()
        renderCenter()
        renderLive(st)
        renderAlerts()
        renderChat()
        voice.render()
        com.dk.assistant.ui.HomeTabs.live(tabsBar, DKState.live)       // बीच का बटन: माइक / लाल कॉल-कट
    }

    private var alertSig = ""
    private fun renderAlerts() {
        if (chatPage) return
        val a = try { host.alerts() } catch (_: Exception) { emptyList() }
        // 15 नवंबर 2026: वही सूचना = दोबारा न बनाओ (वरना अपडेट वाली पट्टी की चमक हर बार टूटती)
        val sig = a.joinToString("|") { it.first } + DkaiTheme.light
        if (sig == alertSig && alertRow.childCount == a.size) return
        alertSig = sig
        alertRow.removeAllViews()
        alertRow.visibility = if (a.isEmpty()) View.GONE else View.VISIBLE
        for ((t, go) in a) {
            // 15 नवंबर 2026 (मालिक): नया अपडेट = होम पर चमकती (हाईलाइट) पट्टी, टैप = अपडेट स्क्रीन
            val upd = com.dk.assistant.UpdatePrompt.isUpdateAlert(t)
            val pill = if (upd) DkaiTheme.pill(c, t, DkaiTheme.UPDATE_BG, DkaiTheme.UPDATE_TEXT, 13f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
                else DkaiTheme.pill(c, t, DkaiTheme.ALERT_BG, DkaiTheme.ALERT_TEXT, 12f)
            pill.setOnClickListener { go() }
            // सूचना = छोटा चमकता गोल बिंदु (लाल / नारंगी)
            pill.setCompoundDrawablesRelative(com.dk.assistant.ui.ClayDot(d(10f), if (upd) DkaiTheme.RED else DkaiTheme.AMBER), null, null, null)
            pill.compoundDrawablePadding = d(8f)
            alertRow.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
            if (upd) com.dk.assistant.UpdatePrompt.pulse(pill)
        }
    }

    private fun renderChat() {
        // 23 नवंबर 2026: होम पर अब बातचीत की सूची नहीं (ORB / अवतार + लाइव टेक्स्ट); Chat स्क्रीन की सूची ChatActivity बनाती है
        val msgs = DKState.messages.toList().takeLast(60)
        val liveR = DKState.liveReply
        // खोज की हालत होम / Chat दोनों पर साफ़ हो (सूची हो या न हो)
        val sStart0 = DkaiSearchLive.startedAt()
        if (sStart0 > 0L && (liveR.isNotBlank() || msgs.any { it.role != "u" && it.time > sStart0 } || !DkaiSearchLive.active())) DkaiSearchLive.cancel()
        if (!::chatList.isInitialized || chatList.parent == null) return
        val thinking = DKState.mode == Mode.THINKING && msgs.lastOrNull()?.role == "u" && liveR.isBlank()
        // 13 नवंबर 2026 (मालिक): लाइव सर्च कार्ड - असली खोज चल रही हो तब; जवाब आते ही हटे (नीचे स्रोत-चिप दिखते हैं)
        var searchDone = false
        val sStart = DkaiSearchLive.startedAt()
        if (sStart > 0L && (liveR.isNotBlank() || msgs.any { it.role != "u" && it.time > sStart } || !DkaiSearchLive.active())) {
            DkaiSearchLive.cancel(); searchDone = true
        }
        val searchOn = DkaiSearchLive.active()
        val sig = "${msgs.size}|${msgs.lastOrNull()?.time}|${msgs.lastOrNull()?.text?.length}|${msgs.lastOrNull()?.src?.size}|${msgs.lastOrNull()?.g?.chunks?.size}|$thinking|${DKState.hintText}|$searchOn|${if (thinking) thinkStyle() else ""}".hashCode()
        if (sig == shownMsgs && liveR == shownLive) return
        // 23 नवंबर 2026 (मालिक: "लंबा जवाब / आवाज़ वाली बात लिखते समय चैट blink करती है"): सिर्फ़ बनता जवाब बदला =
        // वही एक बुलबुला अपडेट (पूरी सूची दोबारा नहीं बनती, इमेज फिर से नहीं खुलतीं, scroll का animation बार-बार नहीं)
        val tail = liveTail
        if (sig == shownMsgs && liveR.isNotBlank() && tail != null && tail.parent === chatList) {
            shownLive = liveR
            tail.text = com.dk.assistant.GroundView.decorate(c, liveR, null, DkaiTheme.WHITE)
            keepChatEnd()
            return
        }
        shownMsgs = sig; shownLive = liveR; liveTail = null
        chatList.removeAllViews()
        val lastImg = msgs.indexOfLast { it.role != "u" && com.dk.assistant.FluxImage.parse(it.text) != null }
        for ((i, m) in msgs.withIndex()) {
            // 20 नवंबर 2026 (मालिक): बनी इमेज चैट में ही दिखे (इतिहास में भी), डाउनलोड बटन के साथ
            val img = if (m.role != "u") com.dk.assistant.FluxImage.parse(m.text) else null
            if (img != null) { chatList.addView(imageCard(m, img.first, img.second, i == lastImg)); continue }
            // 13 नवंबर 2026 (मालिक): जिस वाक्य को स्रोत मिला उसके ठीक अंत में छोटे चिप (github, wikipedia…), टैप = पूरा link
            chatList.addView(bubble(m.role == "u", m.text, ground = if (m.role != "u") m.g else null).apply {
                setOnLongClickListener { msgMenu(m); true }        // 14 नवंबर 2026: लंबा दबाओ = कॉपी / मिटाएँ
            })
            // 13 नवंबर 2026: खोज वाला जवाब (Tavily / Exa / Google एक जैसे) = "स्रोत (N) · प्रदाता" सिकुड़ने वाली सूची + Google खोज सुझाव
            val card = if (m.role != "u") com.dk.assistant.WebCite.card(m.text, m.g) else null
            if (card != null) citeBox(m)?.let { chatList.addView(it) }
            else {
                if (m.role != "u" && m.src.isNotEmpty()) chatList.addView(sources(m.src))     // 13 नवंबर 2026: कौन-सी वेबसाइट से
                if (m.role != "u") groundExtra(m)?.let { chatList.addView(it) }              // चिप-कतार (जगह न मिली) + Google खोज सुझाव
            }
        }
        if (searchDone) shrinkIn(chatList.childCount - 1)
        if (liveR.isNotBlank()) chatList.addView((bubble(false, liveR) as TextView).also { liveTail = it })
        else if (searchOn) chatList.addView(DkaiSearchCard(c).apply {
            layoutParams = LinearLayout.LayoutParams(minOf((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), d(520f)), -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
        })
        else if (thinking) chatList.addView(thinkingBubble(DKState.hintText.ifBlank { "सोच रहा हूँ…" }))
        if (msgs.isNotEmpty()) scrollHomeEnd()
    }

    /** बनता जवाब (लाइव) वाला बुलबुला - [renderChat] इसी का पाठ बदलता है */
    private var liveTail: TextView? = null

    /** नीचे टिके रहो बिना animation के (बनते जवाब पर हर टुकड़े के साथ smooth-scroll = हिलना / blink) */
    private fun keepChatEnd() {
        val sc = chatScroll ?: return
        sc.post { val bottom = chatList.height - sc.height + sc.paddingBottom; if (bottom > sc.scrollY) { sc.scrollTo(0, bottom) } }
    }

    /** खोज वाला कार्ड हटा: आख़िरी स्रोत-डिब्बा सिकुड़ा-सा आकर अपनी जगह बैठे (कार्ड से चिप बनने जैसा) */
    private fun shrinkIn(i: Int) {
        val v = chatList.getChildAt(i) ?: return
        v.pivotX = 0f; v.pivotY = 0f; v.scaleX = 1.06f; v.scaleY = 1.25f; v.alpha = 0.4f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /**
     * 13 नवंबर 2026 (मालिक): "जानकारी कहाँ से ली, सारे सोर्सेस दिखें - कौन-कौन सी वेबसाइट पर जाकर खोजा".
     * हर स्रोत: नंबर + पेज का नाम + वेबसाइट; टैप = वह पेज खुले.
     */
    private fun sources(src: List<Pair<String, String>>): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12f), d(10f), d(12f), d(8f))
            background = DkaiTheme.round(c, DkaiTheme.FIELD, 14f, DkaiTheme.BORDER)
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
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return box.apply {
            layoutParams = LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
        }
    }

    /** 15 नवंबर 2026: अभी का टाइपिंग एनीमेशन (Settings › दिखावट से, हर render पर ताज़ा) + "कम एनिमेशन" */
    private fun thinkStyle() = com.dk.assistant.Prefs.typingStyle(c) + "|" + com.dk.assistant.ui.NeonTheme.reducedMotion()
    private fun thinkAnimOn() = com.dk.assistant.TypingStyles.animates(com.dk.assistant.Prefs.typingStyle(c), com.dk.assistant.ui.NeonTheme.reducedMotion())

    /**
     * 15 नवंबर 2026 (मालिक: "सोच रहा हूँ… वाला bubble static है"): अब बुलबुले में वही टाइपिंग एनीमेशन जो Settings › दिखावट
     * में चुना है - जवाब का पहला अक्षर आते ही यह बुलबुला हटकर असली जवाब (renderChat). बंद / कम एनिमेशन = सिर्फ़ लिखा, कुछ नहीं हिलता.
     */
    private fun thinkingBubble(text: String): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(d(14f), d(10f), d(14f), d(10f))
        background = DkaiTheme.round(c, DkaiTheme.CARD, 18f, DkaiTheme.BORDER)
        contentDescription = text
        if (thinkAnimOn()) addView(com.dk.assistant.DKWorkingIndicator(c).apply { bare = true; state = com.dk.assistant.WorkState.THINKING },
            LinearLayout.LayoutParams(d(26f), d(20f)).apply { marginEnd = d(8f) })
        addView(DkaiTheme.text(c, text, 15f, DkaiTheme.GRAY).apply {
            maxWidth = (c.resources.displayMetrics.widthPixels * 0.66f).toInt(); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
    }

    /** 20 नवंबर 2026: चैट की इमेज - टैप = पूरी खुले, डाउनलोड = गैलरी (Pictures/DK AI), लंबा दबाओ = कॉपी / मिटाएँ; सबसे नई के नीचे आज की बची गिनती */
    fun imageCard(m: DKState.Msg, name: String, caption: String, newest: Boolean): View {
        val f = com.dk.assistant.FluxImage.file(c, name)
        val w = minOf((c.resources.displayMetrics.widthPixels * 0.82f).toInt(), d(520f))
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(8f), d(8f), d(8f), d(8f))
            background = DkaiTheme.round(c, DkaiTheme.CARD, 18f, DkaiTheme.BORDER)
            layoutParams = LinearLayout.LayoutParams(w, -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
            setOnLongClickListener { msgMenu(m); true }
        }
        val bmp = if (f.exists()) try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e); null } else null
        if (bmp != null) box.addView(android.widget.ImageView(c).apply {
            setImageBitmap(bmp); adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "बनी इमेज: $caption, खोलें"
            setOnClickListener { openImage(f) }
            setOnLongClickListener { msgMenu(m); true }
        }, LinearLayout.LayoutParams(-1, -2))
        else box.addView(DkaiTheme.text(c, "इमेज फ़ाइल नहीं मिली (मिट गई होगी)", 13f, DkaiTheme.GRAY))
        if (caption.isNotBlank()) box.addView(DkaiTheme.text(c, caption, 12f, DkaiTheme.GRAY).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(d(4f), d(6f), d(4f), 0) })
        if (bmp != null) box.addView(DkaiUi.button(c, "डाउनलोड") {
            activity.lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.dk.assistant.FluxImage.toGallery(c, f) }
                toast(if (ok) "गैलरी में सेव हो गई (Pictures/DK AI)" else "गैलरी में सेव नहीं हो पाई")
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(6f) })
        if (newest) box.addView(DkaiTheme.text(c, com.dk.assistant.FluxImage.leftText(com.dk.assistant.FluxImage.leftNow()), 11f, DkaiTheme.GRAY).apply { setPadding(d(4f), d(6f), d(4f), 0) })
        return box
    }

    private fun openImage(f: java.io.File) {
        try {
            val u = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
            val mime = if (f.extension.equals("png", true)) "image/png" else "image/jpeg"
            c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(u, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e); toast("इमेज खोलने वाला ऐप नहीं मिला") }
    }

    private fun bubble(user: Boolean, text: String, faint: Boolean = false, ground: com.dk.assistant.GroundCite.G? = null): View = TextView(c).apply {
        this.text = if (user) text else com.dk.assistant.GroundView.decorate(c, text, ground, DkaiTheme.WHITE)
        textSize = 15f; setLineSpacing(0f, 1.2f); setTextIsSelectable(true)
        com.dk.assistant.GroundView.links(this)
        setTextColor(if (faint) DkaiTheme.GRAY else if (user) DkaiTheme.ON else DkaiTheme.WHITE)      // नीले बुलबुले पर हमेशा सफ़ेद
        setPadding(d(14f), d(10f), d(14f), d(10f))
        background = if (user) DkaiTheme.round(c, 0xFF1D4ED8.toInt(), 18f) else DkaiTheme.round(c, DkaiTheme.CARD, 18f, DkaiTheme.BORDER)
        maxWidth = (c.resources.displayMetrics.widthPixels * 0.82f).toInt()
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = if (user) Gravity.END else Gravity.START; bottomMargin = d(10f) }
    }

    // ================================================================== + sheet: Attach Audio

    /** एक बार का फ़ाइल-चुनाव (MainActivity के बाहर से भी - registry से, काम के बाद हटता है) */
    private fun pickOne(types: Array<String>, got: (android.net.Uri) -> Unit) {
        lateinit var l: androidx.activity.result.ActivityResultLauncher<Array<String>>
        l = activity.activityResultRegistry.register("dkai_pick_${pickSeq++}", androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
            try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            if (u != null) got(u)
        }
        try { l.launch(types) } catch (e: Exception) { try { l.unregister() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }; toast("फ़ाइल चुनने वाला नहीं खुला") }
    }

    /** Attach Audio: आवाज़ की फ़ाइल -> Gemini से लिखा हुआ text -> लिखने के डिब्बे में */
    fun pickAudio() = pickOne(arrayOf("audio/*")) { u ->
        toast("ऑडियो सुन रहा हूँ (लिख रहा हूँ)…")
        activity.lifecycleScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { Result.success(DkaiAudio.transcribe(c, u)) } catch (e: Throwable) { Result.failure(e) } }
            r.onSuccess { t ->
                if (t.isBlank()) { toast("ऑडियो में कुछ समझ नहीं आया"); return@onSuccess }
                val cur = input.text.toString().trim()
                input.setText(if (cur.isEmpty()) t else "$cur\n$t"); input.setSelection(input.text.length); input.requestFocus()
            }.onFailure { e -> toast("ऑडियो नहीं पढ़ पाया: ${e.message?.take(120)}") }
        }
    }

    fun hideKeyboard() {
        try { (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }

    // ================================================================== back
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                DkaiUi.closeTop(root) -> {}                       // पहले खुला sheet / modal / page बंद
                voice.closeMenu() -> {}
                DkaiCore.handlesBack(voiceSessionState) -> setState(DkaiCore.next(voiceSessionState, VoiceEvent.BACK))
                dictation != null -> finishDictation(send = false)
                attached.isNotEmpty() -> clearAttached()
                !chatPage && tabNow != com.dk.assistant.ui.HomeTabs.Tab.HOME -> showTab(com.dk.assistant.ui.HomeTabs.Tab.HOME)
                else -> { isEnabled = false; activity.onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        }
    }
}
