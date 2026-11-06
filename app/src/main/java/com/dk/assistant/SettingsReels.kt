package com.dk.assistant

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * नवंबर 2026 (मालिक): Settings → "रील गिनती और लिमिट". ऊपर की छोटी पट्टी चालू/बंद, इंस्टाग्राम / YouTube / फेसबुक की
 * रोज़ की लिमिट, लिमिट पूरी होने पर फ़ोन लॉक या ऐप बंद, और बंद ऐप यहीं से खोलना. [ReelWatch] [ReelRules]
 */
internal fun SettingsActivity.secReels() {
    // नवंबर 2026 (मालिक): 7 दिन का हिसाब, सब ऐप की कुल लिमिट + DK की आवाज़, Accessibility के बिना भी
    card("7 दिन का रील हिसाब", "आज, कल, हर दिन — कितनी रील, कितने मिनट, कम हुई या ज़्यादा", startOpen = true) { box ->
        val t = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt()); setLineSpacing(0f, 1.2f) }
        box.addView(t)
        fun refresh() {
            t.text = "देख रहा हूँ…"
            Thread {
                val s = ReelDaily.table(this)
                runOnUiThread { t.text = s }
            }.start()
        }
        refresh()
        row(box, button("हिसाब ताज़ा करें") { refresh() })
        hint(box, "7 दिन पूरे होते ही हिसाब अपने-आप साफ़ होकर नया हफ़्ता शुरू होता है। DK लाइव से पूछ सकते हैं: \"आज कितनी रील देखीं?\" / \"इस हफ़्ते का हिसाब बताओ\"।")
    }

    card("सब ऐप की कुल रोज़ लिमिट और आवाज़", "जैसे 100 — पार हुई तो DK बोलकर बताएगा", startOpen = true) { box ->
        label(box, "इंस्टाग्राम + YouTube + फेसबुक मिलाकर रोज़ कितनी रील (0 = कोई लिमिट नहीं)")
        val ed = EditText(this).apply {
            setText(ReelDaily.totalLimit(this@secReels).toString())
            inputType = InputType.TYPE_CLASS_NUMBER; isSingleLine = true
            setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            box.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val res = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box, button("कुल लिमिट सेव करें") { ReelDaily.setTotalLimit(this, ed.text.toString().trim().toIntOrNull() ?: 0); res.text = "✓ सेव हो गया" })
        box.addView(res)
        val v = switch(box, "DK बोलकर बताए (लिमिट पार / कल से ज़्यादा / रोज़ कल का हिसाब)", ReelDaily.voiceOn(this))
        v.setOnCheckedChangeListener { _, on -> ReelDaily.setVoice(this, on) }
        hint(box, "लिमिट हो: 80% पर एक बार, पार होते ही, फिर हर 10 रील पर DK बोलेगा — \"सर, लिमिट पार हो गई, अब फ़ोन रख दीजिए, आँखों और सेहत के लिए अच्छा नहीं\"। " +
            "लिमिट न हो: आज कल से ज़्यादा होते ही एक बार बताएगा। हर दिन पहली रील पर कल और परसों का मुक़ाबला बताएगा। " +
            "यह कुल लिमिट सिर्फ़ बोलती है, ऐप बंद नहीं करती — ऐप बंद / फ़ोन लॉक नीचे \"हर ऐप की रील लिमिट\" से।")
    }

    card("रील गिनती (ऊपर छोटी पट्टी)", "रील / शॉर्ट्स देखते समय ऊपर दिखे: आज कितनी रील देखीं", startOpen = true) { box ->
        val sw = switch(box, "ऊपर गिनती वाली छोटी पट्टी दिखाओ", ReelStore.popupOn(this))
        sw.setOnCheckedChangeListener { _, on -> ReelStore.setPopup(this, on) }
        val today = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(today)
        fun refresh() {
            today.text = "आज की गिनती:\n" + ReelRules.App.values().joinToString("\n") { a ->
                "• ${a.hi}: ${ReelStore.count(this, a)}" + ReelStore.limit(this, a).let { if (it > 0) " / $it" else "" } + if (ReelStore.blocked(this, a)) "  (लिमिट से बंद)" else ""
            }
        }
        refresh()
        row(box, button("गिनती फिर देखें") { refresh() })
        hint(box, (if (Setup.accessibilityOn()) "✓ Accessibility चालू है — गिनती चल रही है। " else "✗ Accessibility बंद है — इसे चालू करें, इसके बिना गिनती, ऊपर पट्टी, आवाज़ और लिमिट नहीं चलेंगी। ") +
            "इंस्टाग्राम रील, YouTube शॉर्ट्स और आम वीडियो, फेसबुक रील / वीडियो — हर नई रील / वीडियो पर +1, पीछे जाकर वही रील फिर देखी तो भी +1। " +
            "गिनती हर रात 12 बजे 0 से। यह नोटिफ़िकेशन नहीं — ऊपर एक छोटी पट्टी है जो छूने पर कुछ नहीं करती। " +
            "जब DK खुद फ़ोन चला रहा हो (जैसे सोशल रिव्यू) तब न गिनती, न रोक।")
    }

    card("हर ऐप की रील लिमिट", "रोज़ कितनी रील — पूरी होते ही फ़ोन लॉक या ऐप बंद", startOpen = true) { box ->
        val eds = ReelRules.App.values().associateWith { a ->
            label(box, "${a.hi} — रोज़ कितनी रील (0 = कोई लिमिट नहीं)")
            EditText(this).apply {
                setText(ReelStore.limit(this@secReels, a).toString())
                inputType = InputType.TYPE_CLASS_NUMBER; isSingleLine = true
                setBackgroundResource(R.drawable.glass_input)
                setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
                box.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        label(box, "लिमिट पूरी होने पर")
        val acts = ReelRules.Action.values().toList()
        val g = radios(box, acts.map { it.id to it.hi }, ReelStore.action(this).id, 7300)
        g.setOnCheckedChangeListener { _, _ -> ReelStore.setAction(this, ReelRules.Action.of(radioValue(g, acts.map { it.id }, 7300))) }
        val res = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box, button("लिमिट सेव करें") {
            eds.forEach { (a, e) -> ReelStore.setLimit(this, a, e.text.toString().trim().toIntOrNull() ?: 0) }
            res.text = "✓ सेव हो गया"
        })
        box.addView(res)
        hint(box, "लिमिट 30 = आज 30 रील पूरी देख सकते हैं; 31वीं पर रोक। रोक के बाद वह ऐप (जैसे इंस्टाग्राम) खोलेंगे तो DK तुरंत होम पर ले जाएगा — " +
            "जब तक नीचे \"खोलें\" न दबाएँ। फ़ोन लॉक Android 9+ पर ही; पुराने फ़ोन पर ऐप बंद होगा।")
    }

    card("लिमिट से बंद ऐप खोलें", "यहीं से खोलें — तब तक वह ऐप नहीं खुलेगा") { box ->
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun fill() {
            list.removeAllViews()
            val blocked = ReelRules.App.values().filter { ReelStore.blocked(this, it) }
            if (blocked.isEmpty()) { hint(list, "अभी कोई ऐप बंद नहीं है।"); return }
            blocked.forEach { a -> row(list, button("${a.hi} खोलें (गिनती 0 से)") { ReelStore.unblock(this, a); fill() }) }
        }
        fill()
    }
}
