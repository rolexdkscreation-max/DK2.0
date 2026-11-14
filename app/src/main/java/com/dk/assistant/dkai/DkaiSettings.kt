package com.dk.assistant.dkai

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.*
import com.dk.assistant.*
import com.dk.assistant.voice.engine.VoiceEngines

/**
 * 13 नवंबर 2026 (मालिक - दूसरी spec, M4): IDE Settings (थीम, सोचने की गहराई, AI Memory, आवाज़)
 * + AI Long-Term Memory स्क्रीन (बातें जोड़ो / बदलो / हटाओ / खाने से छाँटो).
 * Save Changes = सच में लागू (थीम बदली = स्क्रीन फिर से बनती है).
 */
class DkaiSettings(private val s: DkaiScreen) {
    private val c = s.activity
    private fun d(v: Float) = DkaiUi.d(c, v)

    fun open() {
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "IDE Settings", "${DkaiKit.BRAND} • दिखावट, सोच, याद, आवाज़") { DkaiUi.close(s.root, layer) }
        var light = DkaiTheme.light
        var depth = DkaiKit.depthOf(ThinkLevel.get(c, ApiScopes.CHAT))
        var voice = DkaiCore.voiceFor(Prefs.str(c, "gemini_voice", LiveVoice.DEFAULT), Prefs.voiceGender(c))

        // ---- 14 नवंबर 2026 (मालिक: "जो चीज़ इधर-उधर बिखरा हुआ है, वो सारे चीज़ें सेटिंग के अंदर दिखना चाहिए")
        body.addView(DkaiUi.label(c, "सब कुछ एक जगह"))
        fun row(kind: DkaiIcon.Kind, tint: Int, title: String, sub: String, go: () -> Unit) {
            body.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(14f), d(11f), d(10f), d(11f))
                background = DkaiTheme.press(DkaiTheme.round(c, DkaiTheme.FIELD, 14f, DkaiTheme.BORDER)); isClickable = true; isFocusable = true
                contentDescription = title
                addView(ImageView(c).apply { setImageDrawable(DkaiIcon(kind, tint)) }, LinearLayout.LayoutParams(d(20f), d(20f)))
                val tc = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(6f), 0) }
                tc.addView(DkaiTheme.text(c, title, 13.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED))
                if (sub.isNotBlank()) tc.addView(DkaiTheme.text(c, sub, 11f, DkaiTheme.GRAY).apply { setPadding(0, d(2f), 0, 0) })
                addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHEVRON_RIGHT, DkaiTheme.GRAY)) }, LinearLayout.LayoutParams(d(16f), d(16f)))
                setOnClickListener { DkaiUi.close(s.root, layer); go() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
        }
        row(DkaiIcon.Kind.USER, DkaiTheme.CYAN, "प्रोफ़ाइल", "नाम, खाता") { DkaiSheets.profile(s) }
        row(DkaiIcon.Kind.DATABASE, DkaiTheme.VIOLET, "AI Memory", "${Memory.facts(c).size} बातें याद - जोड़ें / बदलें / हटाएँ") { memory() }
        row(DkaiIcon.Kind.TOOL, DkaiTheme.AMBER, "Dev Tools", "Creator & Community, ZIP, Clone, Terminal") { s.devTools() }
        row(DkaiIcon.Kind.GITHUB, DkaiTheme.WHITE, "GitHub", "Token, repo, Clone (URL से)") { s.showTab(DkaiTab.GITHUB) }
        row(DkaiIcon.Kind.BROADCAST, DkaiTheme.PINK, "Broadcast Studio", "आवाज़, भाषा, रफ़्तार, पुराने Broadcast") { DkaiBroadcast(s).open() }
        row(DkaiIcon.Kind.SHARE, DkaiTheme.GREEN, "DKAI शेयर करें", "दोस्तों को भेजें") {
            DkaiUi.shareText(c, "${DkaiKit.BRAND} - AI coding, voice & broadcast studio\nhttps://github.com/rolexdkscreation-max/DK2.0", "DKAI शेयर करें")
        }
        for ((t, sub, go) in try { s.host.settingsItems() } catch (_: Exception) { emptyList() }) row(DkaiIcon.Kind.GEAR, DkaiTheme.GRAY, t, sub, go)

        // ---- थीम
        body.addView(DkaiUi.label(c, "Theme"))
        val themes = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun themeCard(isLight: Boolean): LinearLayout = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(d(10f), d(14f), d(10f), d(12f)); tag = isLight
            isClickable = true; isFocusable = true; contentDescription = if (isLight) "Luminous Light" else "Obsidian Dark"
            val prev = FrameLayout(c).apply { background = DkaiTheme.round(c, if (isLight) 0xFFF4F7FB.toInt() else 0xFF0B101D.toInt(), 12f, if (isLight) 0xFFE2E8F0.toInt() else 0xFF26324D.toInt()) }
            prev.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(if (isLight) DkaiIcon.Kind.SUN else DkaiIcon.Kind.MOON, if (isLight) DkaiTheme.AMBER else 0xFF00E5FF.toInt())) },
                FrameLayout.LayoutParams(d(24f), d(24f), Gravity.CENTER))
            addView(prev, LinearLayout.LayoutParams(-1, d(56f)))
            addView(DkaiTheme.text(c, if (isLight) "Luminous Light" else "Obsidian Dark", 13f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply { setPadding(0, d(8f), 0, 0) })
        }
        val dark = themeCard(false); val lite = themeCard(true)
        fun paintTheme() { for (v in listOf(dark, lite)) { val on = v.tag == light
            v.background = DkaiTheme.press(DkaiTheme.round(c, if (on) DkaiTheme.SEL else DkaiTheme.FIELD, 16f, if (on) DkaiTheme.BLUE else DkaiTheme.BORDER, if (on) 1.5f else 1f)) } }
        dark.setOnClickListener { light = false; paintTheme() }; lite.setOnClickListener { light = true; paintTheme() }
        themes.addView(dark, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d(10f) }); themes.addView(lite, LinearLayout.LayoutParams(0, -2, 1f))
        paintTheme(); body.addView(themes)

        // ---- सोचने की गहराई
        body.addView(DkaiUi.label(c, "Reasoning Depth"))
        body.addView(DkaiUi.segmented(c, DkaiKit.DEPTHS, DkaiKit.DEPTHS.indexOf(depth).coerceAtLeast(0)) { depth = DkaiKit.DEPTHS[it] })
        body.addView(DkaiTheme.text(c, "Off = हर हिस्सा अपना पुराना तय तरीका (Auto)", 11f, DkaiTheme.HINT).apply { setPadding(d(2f), d(6f), 0, 0) })

        // ---- AI Memory
        body.addView(DkaiUi.label(c, "AI Memory"))
        val on = Memory.personalOn(c)
        val mem = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(14f), d(12f), d(10f), d(12f))
            background = DkaiTheme.round(c, DkaiTheme.FIELD, 14f, DkaiTheme.BORDER)
        }
        mem.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.DATABASE, DkaiTheme.VIOLET)) }, LinearLayout.LayoutParams(d(22f), d(22f)))
        val mc = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0) }
        mc.addView(DkaiTheme.text(c, "AI Memory Status: " + if (on) "ACTIVE" else "OFF", 13.5f, if (on) DkaiKit.EMERALD else DkaiTheme.GRAY, DkaiTheme.SANS_BOLD))
        mc.addView(DkaiTheme.text(c, "${Memory.facts(c).size} बातें याद", 11.5f, DkaiTheme.GRAY).apply { setPadding(0, d(3f), 0, 0) })
        mem.addView(mc, LinearLayout.LayoutParams(0, -2, 1f))
        mem.addView(DkaiTheme.pill(c, "Manage Memory →", DkaiTheme.PILL, DkaiTheme.CYAN, 12f).apply { minHeight = d(38f); setOnClickListener { DkaiUi.close(s.root, layer); memory() } })
        body.addView(mem)

        // ---- आवाज़
        body.addView(DkaiUi.label(c, "Voice Persona"))
        val vrow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun paintVoice() { for (i in 0 until vrow.childCount) { val v = vrow.getChildAt(i); val sel = v.tag == voice.name
            v.background = DkaiTheme.press(DkaiTheme.round(c, if (sel) DkaiTheme.SEL else DkaiTheme.FIELD, 14f, if (sel) DkaiTheme.BLUE else DkaiTheme.BORDER, if (sel) 1.5f else 1f)) } }
        for (v in DkaiCore.VOICES) {
            vrow.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(d(14f), d(10f), d(14f), d(10f)); tag = v.name
                isClickable = true; isFocusable = true; contentDescription = "${v.name}, ${DkaiKit.voiceTag(v.name, v.female)}"
                addView(ImageView(c).apply { setImageDrawable(DkaiIcon(if (v.female) DkaiIcon.Kind.FEMALE else DkaiIcon.Kind.MALE, if (v.female) DkaiTheme.PINK else DkaiTheme.CYAN)) },
                    LinearLayout.LayoutParams(d(22f), d(22f)))
                addView(DkaiTheme.text(c, v.name, 13.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply { setPadding(0, d(6f), 0, d(2f)) })
                addView(DkaiTheme.text(c, DkaiKit.voiceTag(v.name, v.female), 10.5f, DkaiTheme.GRAY))
                setOnClickListener { voice = v; paintVoice() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(8f) })
        }
        paintVoice()
        body.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(vrow) })

        body.addView(DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Done", DkaiTheme.FIELD, DkaiTheme.SUB) { DkaiUi.close(s.root, layer) },
            DkaiUi.button(c, "Save Changes") {
                val lvl = DkaiKit.levelOf(depth)
                for (sc in listOf(ApiScopes.CHAT, ApiScopes.CODER, ApiScopes.PC, ThinkLevel.ONE)) ThinkLevel.set(c, sc, lvl)
                val old = DkaiCore.voiceFor(Prefs.str(c, "gemini_voice", LiveVoice.DEFAULT), Prefs.voiceGender(c))
                if (old != voice) { Prefs.edit(c) { putString("gemini_voice", voice.api); putString("voice_gender", voice.gender) }; VoiceEngines.liveChanged() }
                DkaiUi.close(s.root, layer)
                s.toast("सेव हो गया")
                if (light != DkaiTheme.light) { DkaiTheme.setLight(c, light); s.activity.recreate() }
                else s.voice.render()
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8f) })
        layer = DkaiUi.open(s.root, card)
    }

    // ================================================================== AI Long-Term Memory
    private var filter: DkaiKit.MemCat? = null
    private var addCat = DkaiKit.MemCat.PREFERENCE
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    fun memory() {
        lateinit var layer: DkaiUi.Layer
        val (page, body, _) = DkaiUi.page(c, "AI Long-Term Memory") { DkaiUi.close(s.root, layer) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16f), d(12f), d(16f), d(12f)) }
        col.addView(DkaiUi.switch(c, "Personalized Memory Active", "बंद = यादें AI के जवाबों में नहीं जुड़तीं (मिटती नहीं)", Memory.personalOn(c)) {
            Memory.setPersonal(c, it); s.toast(if (it) "Memory चालू" else "Memory बंद")
        })

        col.addView(DkaiUi.label(c, "Add Fact"))
        val inp = DkaiUi.field(c, "Add a fact, preference, or tech stack...", multi = true).apply { minLines = 2 }
        col.addView(inp, LinearLayout.LayoutParams(-1, -2))
        val addRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10f), 0, 0) }
        val catPill = DkaiTheme.pill(c, addCat.label + "  ▾", DkaiTheme.PILL, DkaiTheme.CYAN, 12f).apply { minHeight = d(42f); contentDescription = "खाना चुनें" }
        catPill.setOnClickListener {
            val cats = DkaiKit.MemCat.values()
            AlertDialog.Builder(c).setTitle("Category").setItems(cats.map { it.label }.toTypedArray()) { _, i -> addCat = cats[i]; catPill.text = addCat.label + "  ▾" }.show()
        }
        addRow.addView(catPill)
        addRow.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
        addRow.addView(DkaiUi.button(c, "+ Save Fact") {
            val t = inp.text.toString().trim()
            if (t.length < 3) { s.toast("थोड़ा और लिखें"); return@button }
            if (Memory.addFact(c, DkaiKit.memTag(addCat, t))) { inp.setText(""); s.hideKeyboard(); refresh(); s.toast("याद रख लिया") }
            else s.toast("यह याद नहीं रखा (निजी जानकारी - पासवर्ड / PIN / नंबर)")
        })
        col.addView(addRow)

        val fr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(16f), 0, d(8f)) }
        val names = listOf("ALL") + DkaiKit.MemCat.values().map { it.label }
        fr.addView(DkaiUi.chips(c, names, 0) { i -> filter = if (i == 0) null else DkaiKit.MemCat.values()[i - 1]; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(fr)
        count = DkaiTheme.text(c, "", 11.5f, DkaiTheme.GRAY).apply { setPadding(d(2f), 0, 0, d(8f)) }
        col.addView(count)
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        body.addView(ScrollView(c).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val foot = DkaiUi.twoButtons(c,
            DkaiUi.button(c, "Clear All", DkaiTheme.RED_BG, DkaiTheme.RED, DkaiIcon.Kind.TRASH) {
                AlertDialog.Builder(c).setTitle("सारी बातें मिटाएँ?").setMessage("AI की याद की हुई सारी बातें हमेशा के लिए हट जाएँगी।")
                    .setPositiveButton("मिटाएँ") { _, _ -> Memory.clearFacts(c); refresh() }.setNegativeButton("रद्द", null).show()
            },
            DkaiUi.button(c, "Done") { DkaiUi.close(s.root, layer) }).apply { setPadding(d(16f), d(8f), d(16f), d(10f)) }
        body.addView(foot, LinearLayout.LayoutParams(-1, -2))
        layer = DkaiUi.open(s.root, page, DkaiUi.Place.FULL)
        refresh()
    }

    private fun catColor(k: DkaiKit.MemCat?) = when (k) {
        DkaiKit.MemCat.PREFERENCE -> DkaiTheme.VIOLET; DkaiKit.MemCat.PROFILE -> DkaiTheme.CYAN; DkaiKit.MemCat.PROJECT -> DkaiTheme.AMBER; null -> DkaiTheme.GRAY
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val all = Memory.facts(c)
        val shown = DkaiKit.memFilter(all, filter).reversed()
        count.text = "${shown.size} / ${all.size} बातें"
        if (shown.isEmpty()) { list.addView(DkaiTheme.text(c, "अभी कोई बात नहीं - ऊपर लिखकर + Save Fact दबाएँ", 13f, DkaiTheme.GRAY).apply { gravity = Gravity.CENTER; setPadding(0, d(24f), 0, 0) },
            LinearLayout.LayoutParams(-1, -2)); return }
        for (f in shown) {
            val k = DkaiKit.memCat(f)
            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(12f), d(8f), d(8f))
                background = DkaiTheme.round(c, DkaiTheme.CARD, 14f, DkaiTheme.BORDER)
            }
            card.addView(DkaiUi.tag(c, k?.label ?: "GENERAL", catColor(k)), LinearLayout.LayoutParams(-2, -2))
            card.addView(DkaiTheme.text(c, DkaiKit.memBody(f), 14f, DkaiTheme.WHITE).apply { setPadding(0, d(8f), d(6f), d(4f)); setLineSpacing(0f, 1.15f); UiLang.keep(this) })
            val acts = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            acts.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.EDIT, "बदलें", DkaiTheme.CYAN, 40f) { edit(f) })
            acts.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.TRASH, "हटाएँ", DkaiTheme.RED, 40f) { Memory.removeFact(c, f); refresh() })
            card.addView(acts, LinearLayout.LayoutParams(-1, -2))
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10f) })
        }
    }

    private fun edit(f: String) {
        val k = DkaiKit.memCat(f)
        val e = DkaiUi.field(c, "बात", DkaiKit.memBody(f), multi = true)
        val box = FrameLayout(c).apply { setPadding(d(20f), d(8f), d(20f), 0); addView(e) }
        AlertDialog.Builder(c).setTitle("बात बदलें").setView(box)
            .setPositiveButton("सेव") { _, _ -> if (!Memory.editFact(c, f, DkaiKit.memTag(k, e.text.toString()))) s.toast("नहीं बदली"); refresh() }
            .setNegativeButton("रद्द", null).show()
    }
}
