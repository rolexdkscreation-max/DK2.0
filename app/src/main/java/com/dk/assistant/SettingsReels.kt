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
        hint(box, (if (Setup.accessibilityOn()) "✓ Accessibility चालू है — गिनती चल रही है। " else "✗ Accessibility बंद है — इसके बिना न गिनती होगी, न लिमिट। ") +
            "इंस्टाग्राम रील, YouTube शॉर्ट्स और फेसबुक रील / वीडियो — हर नई रील पर +1, पीछे जाकर वही रील फिर देखी तो भी +1। " +
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
