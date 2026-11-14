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

/**
 * DK होम: State 1 (होम + चैट, Perplexity जैसा नीचे "Ask anything" डिब्बा), State 2 (पूरी आवाज़), State 3 (छोटी तैरती पट्टी).
 * MainActivity के ऊपर addContentView से - पुराना होम नीचे वैसा ही (क्लासिक होम पर वापस जा सकते हैं).
 */
class DkaiScreen(val activity: ComponentActivity, val host: DkaiHost) {
    private val c = activity
    private val ui = Handler(Looper.getMainLooper())
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
    private lateinit var readyDot: PulseDot
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
    private lateinit var attachRow: LinearLayout
    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var heroBox: LinearLayout
    private lateinit var hero: Logo3D
    lateinit var voice: DkaiVoice; private set

    /** + से चुनी फोटो / फ़ाइल (भेजने तक यहीं, ✕ से हटाओ) */
    private val attached = ArrayList<android.net.Uri>()
    /** 🎤 लिखवाना चालू (null = नहीं) */
    private var dictation: InlineDictation? = null
    private var dictBase = ""

    private var shownMsgs = -1
    private var shownLive = ""
    private var insetBottom = 0

    fun attach(): DkaiScreen {
        DkaiTheme.load(c)                                       // Luminous Light / Obsidian Dark
        root = FrameLayout(c).apply { setBackgroundColor(DkaiTheme.BG); isClickable = true; id = R.id.dkai_root; tag = this@DkaiScreen }
        content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        buildHeader(); buildHome(); buildInput()
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
            voice.applyInsets(bars.top, bars.bottom)
            placeMini()
            ins
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeMini() }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        DkaiLive.attach(this)
        renumberChats()
        panelHost.addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
        render()
        hero.post { hero.spin() }                               // ऐप खुलते ही लोगो 3D में एक चक्कर
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
        } catch (_: Exception) {}
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
    private fun headIcon(kind: DkaiIcon.Kind, desc: String, tint: Int = DkaiTheme.GRAY, fill: Int = 0, go: (View) -> Unit): ImageView =
        DkaiUi.iconBtn(c, kind, desc, tint, 38f, fill) {}.apply { setOnClickListener { v -> go(v) } }

    private fun buildHeader() {
        // ☰  [DK लोगो] DKAI          ▶  ⚙
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(4f), d(6f), d(6f), 0) }
        head.addView(ImageView(c).apply {
            setImageDrawable(DkaiIcon(DkaiIcon.Kind.MENU, DkaiTheme.GRAY)); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = d(11f); setPadding(p, p, p, p); contentDescription = "मेन्यू"
            background = DkaiTheme.press(DkaiTheme.oval(0)); isClickable = true
            setOnClickListener { v -> host.moreMenu(v) }
        }, LinearLayout.LayoutParams(d(42f), d(42f)))
        head.addView(ImageView(c).apply { setImageResource(R.drawable.ic_dk); contentDescription = "DK लोगो"
            background = DkaiTheme.round(c, DkaiTheme.LOGO_TILE, 10f, DkaiTheme.LOGO_TILE_EDGE); val p = d(4f); setPadding(p, p, p, p) },
            LinearLayout.LayoutParams(d(32f), d(32f)).apply { leftMargin = d(2f) })
        head.addView(DkaiTheme.text(c, "DKAI", 21f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.04f; setPadding(d(8f), 0, 0, 0); maxLines = 1 },
            LinearLayout.LayoutParams(0, -2, 1f))
        // 14 नवंबर 2026 (मालिक): आवाज़ वाला बटन अब नीचे "Ask anything" डिब्बे में (Perplexity जैसा) - ऊपर सिर्फ़ ⚙
        // 14 नवंबर 2026 (मालिक): ⚙ दबाते ही सीधे Settings खुले. लंबा दबाओ = पुरानी छोटी सूची (प्रोफ़ाइल / थीम / बाकी रास्ते)
        head.addView(headIcon(DkaiIcon.Kind.GEAR, "Settings - सब कुछ एक जगह") { openSettings() }.apply {
            setOnLongClickListener { settingsList(); true }
        })
        content.addView(head, LinearLayout.LayoutParams(-1, d(52f)))

        // Chat #1  • READY            🔍  ⧉  ↗  + New
        val sub = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(10f), d(2f), d(10f), d(6f)) }
        chatLabel = DkaiTheme.text(c, DkaiCore.chatLabel(Dkai.chatNo(c)), 13f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply {
            // 13 नवंबर 2026 (मालिक): टैप = पुरानी सारी चैट की सूची (Claude के Recents जैसी)
            setCompoundDrawablesRelative(null, null, DkaiTheme.icon(c, DkaiIcon.Kind.DOWN, DkaiTheme.GRAY, 14f), null); compoundDrawablePadding = d(3f)
            gravity = Gravity.CENTER_VERTICAL; minHeight = d(36f); setPadding(d(6f), 0, d(6f), 0)
            background = DkaiTheme.press(DkaiTheme.round(c, 0, 10f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 10f))
            isClickable = true; isFocusable = true; contentDescription = "पुरानी सारी चैट देखें"
            setOnClickListener { DkaiSheets.history(this@DkaiScreen) }
        }
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

    /** ⚙ = सीधे DK Settings (न खुले तो पुरानी सूची) */
    private fun openSettings() {
        try { c.startActivity(Intent(c, SettingsActivity::class.java)) } catch (_: Exception) { settingsList() }
    }

    /** ⚙ लंबा दबाना: पूरी DK सेटिंग्स + बाकी रास्ते (फ़ोन कंट्रोल, माइक, Story Writer, टीचर, Forge…) + प्रोफ़ाइल + थीम */
    private fun settingsList() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        try { for ((t, sub, go) in host.settingsItems()) items.add("$t — $sub" to go) } catch (_: Exception) {}
        if (items.isEmpty()) items.add("पूरी DK सेटिंग्स" to { c.startActivity(Intent(c, SettingsActivity::class.java)) })
        items.add("प्रोफ़ाइल (आपका नाम)" to { DkaiSheets.profile(this) })
        items.add((if (DkaiTheme.light) "डार्क थीम पर जाएँ" else "लाइट थीम पर जाएँ") to { DkaiTheme.setLight(c, !DkaiTheme.light); activity.recreate() })
        android.app.AlertDialog.Builder(c).setTitle("Settings")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items.getOrNull(i)?.second?.invoke() }.show()
    }

    // ================================================================== State 1: बीच (3D लोगो + चैट)
    private fun buildHome() {
        homeCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, d(8f), 0, d(16f)) }
        // 14 नवंबर 2026 (मालिक: "Perplexity जैसा"): चैट खाली = बीच में घूमता 3D DK लोगो (टैप = फिर घूमे). quick कार्ड / Broadcast हटे
        heroBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        hero = Logo3D(c, R.drawable.ic_dk).apply { setOnClickListener { spin() } }
        heroBox.addView(hero, LinearLayout.LayoutParams(d(120f), d(120f)))
        homeCol.addView(heroBox, LinearLayout.LayoutParams(-1, 0, 1f))
        chatList = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(14f), d(14f), 0) }
        UiLang.keep(chatList)
        homeCol.addView(chatList)
        homeScroll = ScrollView(c).apply { isFillViewport = true; clipToPadding = false; addView(homeCol) }
    }

    // ================================================================== State 1: नीचे "Ask anything" डिब्बा (Perplexity जैसा)
    // [फोटो चिप ✕ ✕ ✕]
    // Ask anything…
    // [+] [🔍|🖥] [Model ▾]                [🎤] [〰 / ↑]
    private fun buildInput() {
        inputBox = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.CARD, 24f, DkaiTheme.BORDER)
            setPadding(d(12f), d(6f), d(8f), d(8f))
        }
        attachRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, d(4f), 0, d(2f)) }
        attachScroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(attachRow) }
        inputBox.addView(attachScroll, LinearLayout.LayoutParams(-1, -2))
        input = EditText(c).apply {
            hint = "Ask anything…"; textSize = 15.5f
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
            background = DkaiTheme.press(DkaiTheme.oval(DkaiTheme.PILL, DkaiTheme.BORDER, 1)); isClickable = true
            setOnClickListener { v -> DkaiSheets.attach(this@DkaiScreen, v) }
            setOnLongClickListener { pickImages(); true }
        }, LinearLayout.LayoutParams(d(38f), d(38f)))
        // 🔍 | 🖥  - खोज / चैट या DK Computer
        val seg = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(3f), d(3f), d(3f), d(3f))
            background = DkaiTheme.round(c, DkaiTheme.PILL, 19f, DkaiTheme.BORDER)
        }
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
            background = DkaiTheme.press(DkaiTheme.oval(DkaiTheme.BLUE), DkaiTheme.oval(0xFFFFFFFF.toInt()))
            elevation = d(3f).toFloat(); isClickable = true; isFocusable = true
            setOnClickListener { if (input.text.isNotBlank() || attached.isNotEmpty()) send() else micTap() }
        }
        micIcon = ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.WAVE, DkaiTheme.ON)) }      // नीले बटन पर हमेशा सफ़ेद
        micBtn.addView(micIcon, FrameLayout.LayoutParams(d(22f), d(22f), Gravity.CENTER))
        row.addView(micBtn, LinearLayout.LayoutParams(d(44f), d(44f)))
        inputBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        content.addView(inputBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(12f), d(6f), d(12f), d(10f)) })
        micOrSend()
    }

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
        searchSeg.background = if (on) null else DkaiTheme.round(c, DkaiTheme.BLUE, 16f)
        compSeg.background = if (on) DkaiTheme.round(c, DkaiTheme.BLUE, 16f) else null
        compEyes.color = if (on) DkaiTheme.ON else DkaiTheme.GRAY
        input.hint = if (on) "DK Computer को काम दीजिए…" else "Ask anything…"
    }

    // ================================================================== 🎤 लिखवाना
    private fun dictTap() {
        val d0 = dictation
        if (d0 != null) { finishDictation(send = true); return }
        if (!Setup.hasMic(c)) { host.ready(); return }
        dictBase = input.text.toString().trimEnd()
        val dd = InlineDictation(c,
            onText = { t -> input.setText(DkaiHomeCore.joinDictation(dictBase, t)); input.setSelection(input.text.length) },
            onLevel = { lv -> dictBtn.scaleX = 1f + lv * 0.25f; dictBtn.scaleY = dictBtn.scaleX },
            onEnd = { msg -> if (msg != null) toast(msg); finishDictation(send = false) })
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
            DkaiHomeCore.SendRoute.CHAT -> { if (!sendChat(t)) return }
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

    /** 14 नवंबर 2026 (मालिक): चैट में जुड़ी फोटो / फ़ाइल चैट में ही - AI देखकर यहीं जवाब दे */
    private fun sendChatFiles(pics: List<android.net.Uri>, t: String): Boolean {
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
                try { l.unregister() } catch (_: Exception) {}
                addAttached(us)
            }
        } else {
            l = activity.activityResultRegistry.register("dkai_img_${pickSeq++}", androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { u ->
                try { l.unregister() } catch (_: Exception) {}
                if (u != null) addAttached(listOf(u))
            }
        }
        try { l.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { try { l.unregister() } catch (_: Exception) {}; toast("गैलरी नहीं खुली") }
    }

    /** PDF / text / फोटो फ़ाइल */
    fun pickFiles() {
        lateinit var l: androidx.activity.result.ActivityResultLauncher<Array<String>>
        l = activity.activityResultRegistry.register("dkai_files_${pickSeq++}", androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { us ->
            try { l.unregister() } catch (_: Exception) {}
            addAttached(us)
        }
        try { l.launch(ComputerActivity.PICK_TYPES) } catch (_: Exception) { try { l.unregister() } catch (_: Exception) {}; toast("फ़ाइल चुनने वाला नहीं खुला") }
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
                try { setImageURI(u) } catch (_: Exception) {}
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

    /** चैट वाला भेजना (ChatActivity जैसा): Accessibility चालू = DK का पूरा दिमाग़ (फ़ोन के काम भी), नहीं = सिर्फ़ बातचीत */
    private fun sendChat(t: String): Boolean {
        if (!Prefs.hasAnyKey(c)) { toast("पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"); host.ready(); return false }
        DKState.userNow(t)
        // 14 नवंबर 2026 (मालिक): "डीप रिसर्च" - Exa + Tavily + Linkup एक साथ, Firecrawl से पूरे पेज, Gemini की हिंदी रिपोर्ट
        if (com.dk.assistant.research.DeepResearch.wanted(t, Prefs.deepAuto(c))) { deepResearch(t); return true }
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

    private fun deepResearch(t: String) {
        localJob?.cancel()
        localJob = activity.lifecycleScope.launch {
            try {
                DKState.hint("डीप रिसर्च शुरू…")
                val r = com.dk.assistant.research.DeepResearchRun(c.applicationContext) { step -> activity.runOnUiThread { DKState.hint(step) } }.run(t)
                DKState.reply(r.text, r.sources)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { DKState.replyFailed("डीप रिसर्च नहीं हो पाई — " + (e.message?.take(120) ?: "गड़बड़ी"))
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
        if (DKState.messages.isEmpty()) { input.requestFocus(); return }      // खाली चैट पर दोबारा नई नहीं
        localJob?.cancel()
        DkaiChats.save(DkaiChats.dir(c), Dkai.chatNo(c), DKState.messages.toList())
        DKState.clearChat(); Dkai.nextChat(c)
        chatLabel.text = DkaiCore.chatLabel(Dkai.chatNo(c)); shownMsgs = -1; render()
        input.setText(""); input.requestFocus(); hero.spin()
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
        chatLabel.text = DkaiCore.chatLabel(n); shownMsgs = -1; render()
        input.setText("")
        homeScroll.post { homeScroll.scrollTo(0, homeCol.height) }
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
        if (dictation != null) finishDictation(send = false)       // 🎤 लिखवाना और 〰 बातचीत एक साथ नहीं
        hideKeyboard()
        voice.newSession()
        setState(DkaiCore.next(voiceSessionState, VoiceEvent.MIC_TAP))
        startLive()
    }

    /** 14 नवंबर 2026 (मालिक): मोड टैब हटे - DK लाइव हमेशा अपने पुराने रास्ते से (null) */
    fun liveMode(): DkaiMode? = null

    fun scrollHomeEnd() { homeScroll.post { homeScroll.smoothScrollTo(0, homeCol.height) } }

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

    private fun padForMini() {
        val extra = if (voiceSessionState == VoiceSessionState.ACTIVE_MINIMIZED) d(84f) else 0
        homeScroll.setPadding(0, 0, 0, extra)          // चैट का आख़िरी संदेश पट्टी के नीचे न छिपे (scroll करके दिखे)
    }

    /** छोटी पट्टी: लिखने के डिब्बे (होम) / टैब-पट्टी के ठीक ऊपर */
    private fun placeMini() {
        if (!::root.isInitialized || !::voice.isInitialized) return
        val h = root.height; if (h <= 0) return
        val anchor: View = inputBox
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

    /** DK Computer काम कर रहा हो तो 🖥 की आँखें घूमें */
    private val eyesTick = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (::compEyes.isInitialized) compEyes.working = ComputerSession.busy
            ui.postDelayed(this, 1000)
        }
    }

    fun onResume() {
        resumed = true
        ui.removeCallbacks(eyesTick); ui.post(eyesTick)
        DKState.listen(stateL); com.dk.assistant.LiveVision.listen(visionL)
        renderModel(); render()
    }

    fun onPause() {
        resumed = false
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
        for ((t, go) in a) alertRow.addView(DkaiTheme.pill(c, t, DkaiTheme.ALERT_BG, DkaiTheme.ALERT_TEXT, 12f).apply { setOnClickListener { go() } },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
    }

    private fun renderChat() {
        val msgs = DKState.messages.toList().takeLast(60)
        val liveR = DKState.liveReply
        val thinking = DKState.mode == Mode.THINKING && msgs.lastOrNull()?.role == "u" && liveR.isBlank()
        // 13 नवंबर 2026 (मालिक): लाइव सर्च कार्ड - असली खोज चल रही हो तब; जवाब आते ही हटे (नीचे स्रोत-चिप दिखते हैं)
        var searchDone = false
        val sStart = DkaiSearchLive.startedAt()
        if (sStart > 0L && (liveR.isNotBlank() || msgs.any { it.role != "u" && it.time > sStart } || !DkaiSearchLive.active())) {
            DkaiSearchLive.cancel(); searchDone = true
        }
        val searchOn = DkaiSearchLive.active()
        val sig = "${msgs.size}|${msgs.lastOrNull()?.time}|${msgs.lastOrNull()?.text?.length}|${msgs.lastOrNull()?.src?.size}|${msgs.lastOrNull()?.g?.chunks?.size}|$thinking|${DKState.hintText}|$searchOn".hashCode()
        if (sig == shownMsgs && liveR == shownLive) return
        shownMsgs = sig; shownLive = liveR
        chatList.removeAllViews()
        for (m in msgs) {
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
        if (liveR.isNotBlank()) chatList.addView(bubble(false, liveR))
        else if (searchOn) chatList.addView(DkaiSearchCard(c).apply {
            layoutParams = LinearLayout.LayoutParams(minOf((c.resources.displayMetrics.widthPixels * 0.86f).toInt(), d(520f)), -2).apply { gravity = Gravity.START; bottomMargin = d(10f) }
        })
        else if (thinking) chatList.addView(bubble(false, DKState.hintText.ifBlank { "सोच रहा हूँ…" }, faint = true))
        DkaiTheme.gone(heroBox, msgs.isNotEmpty() || liveR.isNotBlank())
        if (msgs.isNotEmpty()) homeScroll.post { homeScroll.smoothScrollTo(0, homeCol.height) }
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
                val cur = input.text.toString().trim()
                input.setText(if (cur.isEmpty()) t else "$cur\n$t"); input.setSelection(input.text.length); input.requestFocus()
            }.onFailure { e -> toast("ऑडियो नहीं पढ़ पाया: ${e.message?.take(120)}") }
        }
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
                dictation != null -> finishDictation(send = false)
                attached.isNotEmpty() -> clearAttached()
                else -> { isEnabled = false; activity.onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        }
    }
}
