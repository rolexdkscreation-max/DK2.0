package com.dk.assistant.dkai

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.*
import com.dk.assistant.*

/**
 * 13 नवंबर 2026 (मालिक - दूसरी spec, M1 + M8): होम के popup / sheet.
 * Mode popup (5 मोड), AI Model sheet, + (Add to Prompt) sheet, Profile, सत्र की खोज.
 * सब असली काम करते हैं - सिर्फ़ दिखावा नहीं.
 */
object DkaiSheets {

    // ================================================================== AI Model sheet
    fun models(s: DkaiScreen) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        // 13 नवंबर 2026 (मालिक): सिर्फ़ फ्री मॉडल, और सिर्फ़ उन्हीं कंपनियों के जिनकी key AI दिमाग में डली है. Paid = सिर्फ़ paid key बताई हो तब
        val (card, body) = DkaiUi.sheet(c, "Select AI Model", "सिर्फ़ फ्री मॉडल • जिनकी key AI दिमाग में डली है") { DkaiUi.close(s.root, layer) }
        val rows = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rows, LinearLayout.LayoutParams(-1, -2))
        fun fill() {
            rows.removeAllViews()
            val list = DkaiKit.homeModels(hasKey = { hasKey(c, it) }, geminiPaid = Prefs.bool(c, DkaiKit.PREF_GEMINI_PAID, false),
                cached = { Prefs.models(c, it) }, fallback = { Providers.fallbackModels(it) }, name = { ModelNames.fromId(it) })
            val pid = Prefs.activeProvider(c); val curModel = Prefs.model(c, pid)
            if (list.isEmpty()) rows.addView(DkaiTheme.text(c, "अभी कोई फ्री key नहीं डली - सेटिंग्स → AI दिमाग में Gemini / Groq / OpenRouter / Z.ai / NVIDIA की फ्री key डालें।",
                13f, DkaiTheme.SUB).apply { setPadding(DkaiUi.d(c, 6f), DkaiUi.d(c, 6f), DkaiUi.d(c, 6f), DkaiUi.d(c, 10f)) })
            for (m in list) {
                val on = m.provider == pid && m.model.equals(curModel.removePrefix("models/"), true)
                rows.addView(DkaiUi.row(c, DkaiIcon.Kind.ZAP, m.title, m.sub, on, if (m.paid) DkaiTheme.VIOLET else DkaiTheme.CYAN) {
                    DkaiUi.close(s.root, layer)
                    // 14 नवंबर 2026 (मालिक): चुना मॉडल ही काम करे ([DkaiPick]) - "मॉडल: …" वाला संदेश नहीं, चुपचाप
                    DkaiPick.choose(c, m.provider, m.model)
                    s.renderModel()
                })
            }
        }
        fill()
        // 14 नवंबर 2026: नया फ्री मॉडल अपने-आप - पीछे से कंपनियों की ताज़ा सूची ([DkaiModelList]); बदली तो सूची फिर से
        s.activity.lifecycleScope.launch {
            if (DkaiModelList.refreshStale(c) && rows.isAttachedToWindow) fill()
        }
        body.addView(DkaiUi.button(c, "और AI / key (सेटिंग्स → AI दिमाग)…", DkaiTheme.FIELD, DkaiTheme.SUB) {
            DkaiUi.close(s.root, layer); c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain"))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = DkaiUi.d(c, 4f) })
        layer = DkaiUi.open(s.root, card)
    }

    /** इस कंपनी की key डली है? (Gemini: एक-key / चैट key / DK लाइव की key - पहले जैसा) */
    fun hasKey(c: android.content.Context, id: String): Boolean =
        if (id == "gemini") Prefs.oneKeyOn(c) || Prefs.key(c, "gemini").isNotBlank() || Prefs.liveGeminiKeys(c).isNotEmpty()
        else !Prefs.oneKeyOn(c) && Prefs.key(c, id).isNotBlank()

    // ================================================================== + (Add to Prompt)
    /** 14 नवंबर 2026 (मालिक): + = एक बार में 5 तक फोटो (Perplexity जैसा), या फ़ाइल / ऑडियो. zip प्रोजेक्ट (IDE) हटा */
    fun attach(s: DkaiScreen, anchor: View) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "Add to Prompt", "फोटो (${DkaiHomeCore.MAX_ATTACH} तक), फ़ाइल, आवाज़") { DkaiUi.close(s.root, layer) }
        val rows = listOf(
            Triple(DkaiIcon.Kind.IMAGE, "फोटो (${DkaiHomeCore.MAX_ATTACH} तक)" to "गैलरी से एक साथ कई फोटो - DK Computer देखेगा", DkaiTheme.CYAN) to { s.pickImages() },
            Triple(DkaiIcon.Kind.DOC, "फ़ाइल" to "PDF, text या फोटो फ़ाइल", 0xFF60A5FA.toInt()) to { s.pickFiles() },
            Triple(DkaiIcon.Kind.MUSIC, "ऑडियो से लिखो" to "आवाज़ की फ़ाइल -> लिखा हुआ text", DkaiTheme.PINK) to { s.pickAudio() },
        )
        for ((r, go) in rows) {
            body.addView(DkaiUi.row(c, r.first, r.second.first, r.second.second, false, r.third) { DkaiUi.close(s.root, layer); go() })
        }
        layer = DkaiUi.open(s.root, card)
        anchor.isPressed = false
    }

    // ================================================================== Profile (Avatar)
    fun profile(s: DkaiScreen) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.modal(c, "Profile", "DK आपको इसी नाम से बुलाता है") { DkaiUi.close(s.root, layer) }
        val name = DkaiUi.field(c, "आपका नाम", Prefs.userName(c))
        body.addView(name, LinearLayout.LayoutParams(-1, -2))
        val facts = Memory.facts(c).size
        body.addView(DkaiTheme.text(c, "AI Memory: $facts बातें याद • ${if (Memory.personalOn(c)) "ACTIVE" else "OFF"}", 12.5f, DkaiTheme.GRAY).apply {
            setPadding(DkaiUi.d(c, 2f), DkaiUi.d(c, 12f), 0, 0)
        })
        body.addView(DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Manage Memory", DkaiTheme.FIELD, DkaiTheme.SUB) { DkaiUi.close(s.root, layer); c.startActivity(android.content.Intent(c, MemoryActivity::class.java)) },
            DkaiUi.button(c, "Save") {
                Prefs.edit(c) { putString("user_name", name.text.toString().trim().take(40)) }
                DkaiUi.close(s.root, layer); s.toast("नाम सेव हो गया")
            }))
        layer = DkaiUi.open(s.root, card, DkaiUi.Place.CENTER)
    }

    // ================================================================== सत्र: चैट में खोज
    fun search(s: DkaiScreen) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "Search this chat", DkaiCore.chatLabel(Dkai.chatNo(c))) { DkaiUi.close(s.root, layer) }
        val q = DkaiUi.field(c, "शब्द लिखें…")
        val res = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, DkaiUi.d(c, 10f), 0, 0) }
        fun run() {
            res.removeAllViews()
            val msgs = DKState.messages.toList()
            val hits = DkaiKit.searchHits(msgs.map { it.text }, q.text.toString())
            if (q.text.isNotBlank() && hits.isEmpty()) res.addView(DkaiTheme.text(c, "कुछ नहीं मिला", 13f, DkaiTheme.GRAY))
            for (i in hits.take(40)) {
                val m = msgs[i]
                res.addView(DkaiUi.row(c, if (m.role == "u") DkaiIcon.Kind.USER else DkaiIcon.Kind.SPARKLE, if (m.role == "u") "You" else "DKAI",
                    m.text.replace('\n', ' ').take(140)) { DkaiUi.copy(c, "DKAI", m.text) })
            }
        }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(x: android.text.Editable?) { run() }
        })
        body.addView(q, LinearLayout.LayoutParams(-1, -2)); body.addView(res)
        body.addView(DkaiTheme.text(c, "टैप = संदेश कॉपी", 11f, DkaiTheme.HINT).apply { gravity = Gravity.CENTER; setPadding(0, DkaiUi.d(c, 6f), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))
        layer = DkaiUi.open(s.root, card)
        q.requestFocus()
    }

    // ================================================================== पुरानी चैट की सूची (Recents)
    /** 13 नवंबर 2026 (मालिक): "Chat #11" टैप = सारी चैट, सबसे नई ऊपर; टैप = वह चैट खुले. बंद = back / बाहर टैप / ✕ */
    fun history(s: DkaiScreen) {
        val c = s.activity
        val cur = Dkai.chatNo(c)
        val list = DkaiChats.entries(DkaiChats.dir(c), cur, DKState.messages.toList())
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "सभी चैट", "${list.size} चैट • सबसे नई ऊपर") { DkaiUi.close(s.root, layer) }
        body.addView(DkaiUi.button(c, "+ नई चैट", DkaiTheme.PILL, DkaiTheme.WHITE, DkaiIcon.Kind.PLUS) {
            DkaiUi.close(s.root, layer); s.startNewChat()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = DkaiUi.d(c, 12f) })
        for (e in list) body.addView(historyRow(c, e, e.no == cur) {
            DkaiUi.close(s.root, layer); s.openChat(e.no)
        }.apply {
            // 14 नवंबर 2026 (मालिक): लंबा दबाओ = यह चैट मिटाओ (पूछकर)
            setOnLongClickListener {
                android.app.AlertDialog.Builder(c).setTitle("${DkaiCore.chatLabel(e.no)} मिटाएँ?")
                    .setMessage(e.title.ifBlank { "यह चैट" } + "\n\nयह चैट हमेशा के लिए मिट जाएगी।")
                    .setPositiveButton("मिटाएँ") { _, _ -> DkaiUi.close(s.root, layer); s.deleteChat(e.no); s.toast("चैट मिटा दी"); history(s) }
                    .setNegativeButton("रद्द", null).show()
                true
            }
        })
        if (list.size > 1) body.addView(DkaiUi.button(c, "सारी पुरानी चैट मिटाएँ", DkaiTheme.FIELD, DkaiTheme.RED) {
            android.app.AlertDialog.Builder(c).setTitle("सारी पुरानी चैट मिटाएँ?").setMessage("अभी वाली चैट बचेगी, बाकी सब हमेशा के लिए मिट जाएँगी। नंबर फिर 1 से।")
                .setPositiveButton("मिटाएँ") { _, _ -> DkaiUi.close(s.root, layer); s.deleteOldChats(); s.toast("पुरानी चैट मिटा दीं") }
                .setNegativeButton("रद्द", null).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = DkaiUi.d(c, 6f) })
        body.addView(DkaiTheme.text(c, "किसी चैट को लंबा दबाएँ = मिटाएँ", 11f, DkaiTheme.HINT).apply { gravity = Gravity.CENTER; setPadding(0, DkaiUi.d(c, 8f), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))
        layer = DkaiUi.open(s.root, card, maxWidth = DkaiUi.d(c, 560f))
    }

    private fun historyRow(c: android.content.Context, e: DkaiChats.Entry, current: Boolean, onTap: () -> Unit): LinearLayout = LinearLayout(c).apply {
        val d = { v: Float -> DkaiUi.d(c, v) }
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(60f)
        setPadding(d(12f), d(10f), d(12f), d(10f))
        background = DkaiTheme.press(DkaiTheme.round(c, if (current) DkaiTheme.SEL else DkaiTheme.FIELD, 14f,
            if (current) DkaiTheme.BLUE else DkaiTheme.BORDER, if (current) 1.5f else 1f))
        isClickable = true; isFocusable = true
        val name = DkaiCore.chatLabel(e.no)
        val title = e.title.ifBlank { if (current) "नई चैट (अभी खाली)" else name }
        val whenTxt = DkaiChats.whenText(e.time)
        contentDescription = "$name, $title" + (if (whenTxt.isNotBlank()) ", $whenTxt" else "") + (if (current) ", अभी खुली" else "")
        val ic = FrameLayout(c).apply { background = DkaiTheme.round(c, DkaiTheme.ICON_CYAN and 0x26FFFFFF, 12f) }
        ic.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHAT, DkaiTheme.ICON_CYAN)) }, FrameLayout.LayoutParams(d(20f), d(20f), Gravity.CENTER))
        addView(ic, LinearLayout.LayoutParams(d(38f), d(38f)).apply { rightMargin = d(12f) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(DkaiTheme.text(c, title, 14.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f); UiLang.keep(this)
        })
        val meta = listOf(name, whenTxt, if (e.count > 0) "${e.count} संदेश" else "").filter { it.isNotBlank() }.joinToString(" • ")
        col.addView(DkaiTheme.text(c, meta, 12f, DkaiTheme.GRAY).apply { setPadding(0, d(4f), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (current) addView(DkaiTheme.text(c, "अभी खुली", 11f, DkaiTheme.ON, DkaiTheme.SANS_BOLD).apply {
            setPadding(d(8f), d(3f), d(8f), d(3f)); background = DkaiTheme.round(c, DkaiTheme.BLUE, 99f)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8f) }
    }

    /** पूरी चैट का साफ़ text (कॉपी / शेयर) */
    fun chatText(c: android.content.Context): String =
        DkaiKit.chatText(DKState.messages.toList().map { it.role to it.text }, DkaiCore.chatLabel(Dkai.chatNo(c)))
}
