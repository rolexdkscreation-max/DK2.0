package com.dk.assistant.dkai

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

    private fun iconOf(m: DkaiMode) = when (m) {
        DkaiMode.CHAT -> DkaiIcon.Kind.CHAT; DkaiMode.BUILDER -> DkaiIcon.Kind.BUILDER; DkaiMode.AGENT -> DkaiIcon.Kind.AGENT
        DkaiMode.PIPELINE -> DkaiIcon.Kind.PIPELINE; DkaiMode.BROADCAST -> DkaiIcon.Kind.BROADCAST
    }

    private fun tintOf(m: DkaiMode) = when (m) {
        DkaiMode.CHAT -> DkaiTheme.CYAN; DkaiMode.BUILDER -> DkaiTheme.VIOLET; DkaiMode.AGENT -> 0xFF60A5FA.toInt()
        DkaiMode.PIPELINE -> 0xFF34D399.toInt(); DkaiMode.BROADCAST -> DkaiTheme.PINK
    }

    // ================================================================== Mode popup (Chat v)
    fun modes(s: DkaiScreen, anchor: View) {
        val c = s.activity
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(DkaiUi.d(c, 10f), DkaiUi.d(c, 10f), DkaiUi.d(c, 10f), DkaiUi.d(c, 4f))
            background = DkaiTheme.round(c, DkaiTheme.CARD, 18f, DkaiTheme.BORDER); elevation = DkaiUi.d(c, 12f).toFloat()
        }
        box.addView(DkaiUi.label(c, "Select Mode").apply { setPadding(DkaiUi.d(c, 6f), DkaiUi.d(c, 2f), 0, DkaiUi.d(c, 8f)) })
        lateinit var layer: DkaiUi.Layer
        for (m in DkaiKit.HOME_MODES) {
            val r = DkaiKit.modeRow(m)
            box.addView(DkaiUi.row(c, iconOf(m), r.title, r.sub, m == s.mode, tintOf(m)) {
                DkaiUi.close(s.root, layer)
                if (m == DkaiMode.BROADCAST) DkaiBroadcast(s).open() else s.pickMode(m)
            }.apply { minimumWidth = (c.resources.displayMetrics.widthPixels * 0.78f).toInt() })
        }
        val loc = IntArray(2); val rl = IntArray(2)
        anchor.getLocationInWindow(loc); s.root.getLocationInWindow(rl)
        val bottom = (s.root.height - (loc[1] - rl[1]) + DkaiUi.d(c, 8f)).coerceAtLeast(DkaiUi.d(c, 80f))
        layer = DkaiUi.open(s.root, box, DkaiUi.Place.ANCHOR, anchorBottom = bottom, anchorEnd = DkaiUi.d(c, 12f))
    }

    // ================================================================== AI Model sheet
    fun models(s: DkaiScreen) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "Select AI Model", "Gemini मॉडल चुनें - तुरंत लागू") { DkaiUi.close(s.root, layer) }
        val pid = Prefs.activeProvider(c)
        val cur = DkaiKit.modelCardOf(pid, Prefs.model(c, pid))
        DkaiKit.MODEL_CARDS.forEachIndexed { i, m ->
            body.addView(DkaiUi.row(c, if (m.model == null) DkaiIcon.Kind.LOCK else DkaiIcon.Kind.ZAP, m.title, m.sub, i == cur,
                if (i == 1) DkaiTheme.VIOLET else DkaiTheme.CYAN) {
                DkaiUi.close(s.root, layer)
                if (m.model == null) {
                    c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain"))
                    s.toast("Pro (API): सेटिंग्स → AI दिमाग में अपनी key / endpoint डालें")
                } else {
                    if (Prefs.oneKeyOn(c) || Prefs.key(c, "gemini").isNotBlank() || Prefs.liveGeminiKeys(c).isNotEmpty()) {
                        Prefs.edit(c) { putString("active_provider", "gemini"); putString("model_gemini", m.model) }
                        s.renderModel(); s.toast("मॉडल: ${m.title}")
                    } else {
                        s.toast("पहले Gemini की API key डालें"); c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain"))
                    }
                }
            })
        }
        body.addView(DkaiUi.button(c, "और AI / मॉडल (सेटिंग्स)…", DkaiTheme.FIELD, DkaiTheme.SUB) {
            DkaiUi.close(s.root, layer); c.startActivity(Intent(c, SettingsActivity::class.java).putExtra("section", "brain"))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = DkaiUi.d(c, 4f) })
        layer = DkaiUi.open(s.root, card)
    }

    // ================================================================== + (Add to Prompt)
    fun attach(s: DkaiScreen, anchor: View) {
        val c = s.activity
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "Add to Prompt", "फोटो, आवाज़, zip प्रोजेक्ट, दस्तावेज़") { DkaiUi.close(s.root, layer) }
        val kinds = listOf(DkaiIcon.Kind.IMAGE, DkaiIcon.Kind.MUSIC, DkaiIcon.Kind.ARCHIVE, DkaiIcon.Kind.DOC, DkaiIcon.Kind.SPARKLE)
        val tints = listOf(DkaiTheme.CYAN, DkaiTheme.PINK, DkaiTheme.AMBER, 0xFF60A5FA.toInt(), DkaiTheme.VIOLET)
        DkaiKit.ATTACH.forEachIndexed { i, r ->
            body.addView(DkaiUi.row(c, kinds[i], r.title, r.sub, false, tints[i]) {
                DkaiUi.close(s.root, layer)
                when (i) {
                    0 -> s.host.pickPhoto?.invoke() ?: s.host.attachMenu(anchor)
                    1 -> s.pickAudio()
                    2 -> s.pickZip()
                    3 -> s.host.pickFile?.invoke() ?: s.host.attachMenu(anchor)
                    else -> {
                        val t = s.input.text.toString().trim()
                        c.startActivity(Intent(c, ComputerActivity::class.java).putExtra("task",
                            if (t.isNotEmpty()) "Create a well-formatted deliverable (report / document): $t" else "Create a well-formatted report on: "))
                        if (t.isNotEmpty()) s.input.setText("")
                    }
                }
            })
        }
        layer = DkaiUi.open(s.root, card)
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
            DkaiUi.button(c, "Manage Memory", DkaiTheme.FIELD, DkaiTheme.SUB) { DkaiUi.close(s.root, layer); DkaiSettings(s).memory() },
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

    /** पूरी चैट का साफ़ text (कॉपी / शेयर) */
    fun chatText(c: android.content.Context): String =
        DkaiKit.chatText(DKState.messages.toList().map { it.role to it.text }, DkaiCore.chatLabel(Dkai.chatNo(c)))
}
