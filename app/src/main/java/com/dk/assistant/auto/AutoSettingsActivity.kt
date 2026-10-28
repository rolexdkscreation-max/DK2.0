package com.dk.assistant.auto

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.AuroraView
import com.dk.assistant.R
import com.dk.assistant.Setup
import com.dk.assistant.UiDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → ऑटोनोमस मोड: मुख्य switch (शुरू में बंद), हर काम का switch, अपने काम, पुष्टि, keys, इमरजेंसी स्टॉप, लॉग.
 */
class AutoSettingsActivity : AppCompatActivity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var col: LinearLayout
    private lateinit var customList: LinearLayout
    private lateinit var logView: TextView
    private lateinit var master: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        header()

        // ---- मुख्य switch
        val main = card("ऑटोनोमस मोड", "चालू करने पर DK खुद तय करता है कि काम कोडिंग का है या फ़ोन के ऐप का, और खुद कदम-दर-कदम करता है। बंद = DK ठीक पहले जैसा।")
        master = switch(main, "ऑटोनोमस मोड चालू", AutoPrefs.on(this)) { on -> if (on) askEnable() else { AutoPrefs.setOn(this, false); AutoMode.codingUntil = 0L } }
        hint(main, "पुराने सारे कमांड वैसे ही चलते रहेंगे — ऑटोनोमस सिर्फ़ उन कामों पर चलता है जो नीचे चालू हैं।")

        // ---- हर काम का switch
        val kinds = card("कौन-कौन से काम", "जो बंद है, उस पर DK पहले जैसा चलेगा")
        for (k in AutoPlan.Kind.values().filter { it != AutoPlan.Kind.CUSTOM }) {
            switch(kinds, k.title, AutoPrefs.kindOn(this, k)) { AutoPrefs.setKindOn(this, k, it) }
        }

        // ---- अपने काम
        val cust = card("अपने काम", "नाम + निर्देश. बोलते समय नाम आए तो DK उन्हीं निर्देशों से काम करेगा")
        customList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cust.addView(customList)
        cust.addView(button("+ नया काम जोड़ो") { editTask(null) })
        refreshCustom()

        // ---- सुरक्षा
        val safe = card("सुरक्षा", "")
        switch(safe, "भेजना / पोस्ट / डिलीट से पहले मुझसे पूछो", AutoPrefs.confirm(this)) { AutoPrefs.setConfirm(this, it) }
        hint(safe, "पासवर्ड, OTP, PIN, बैंक या भुगतान वाली स्क्रीन पर DK कभी कुछ नहीं करता — वहीं रुककर बताता है। " +
            "एक ही कदम बार-बार fail हो तो भी रुककर बताता है।")
        safe.addView(button("■ इमरजेंसी स्टॉप (सारा ऑटोनोमस काम अभी रोको)", accent = true) {
            AutoMode.emergencyStop(this); toast("सारा ऑटोनोमस काम रोक दिया"); refreshLog()
        })
        hint(safe, "बोलकर भी: \"इमरजेंसी स्टॉप\"")

        // ---- keys
        val keys = card("API keys (ऑटोनोमस के लिए)", "खाली छोड़ें तो DK की मुख्य key इस्तेमाल होगी")
        keyField(keys, "ऑटोनोमस मोड की key", AutoPrefs.KEY_AUTO, "खाली = DK की मुख्य key")
        keyField(keys, "कोडिंग एजेंट की key (ऑटोनोमस कोडिंग के लिए)", AutoPrefs.KEY_CODER, "खाली = कोडिंग एजेंट की अपनी key, वो भी नहीं तो DK की मुख्य key")
        hint(keys, "Keys फ़ोन के सुरक्षित हिस्से (Android Keystore) से बंद करके रखी जाती हैं। ये कभी लॉग, GitHub या किसी server पर नहीं जातीं।")

        // ---- लॉग
        val lg = card("लॉग (हर कदम)", "ऑटोनोमस मोड ने क्या-क्या किया — सिर्फ़ इसी फ़ोन में")
        logView = TextView(this).apply { textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setTextIsSelectable(true) }
        lg.addView(logView)
        lg.addView(button("ताज़ा करो") { refreshLog() })
        lg.addView(button("लॉग मिटाओ") { UiDialog(this).setMessage("सारा लॉग मिटा दें?").setPositiveButton("हाँ") { _, _ -> AutoLog.clear(this); refreshLog() }.setNegativeButton("नहीं", null).show() })
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        // इजाज़त देकर लौटे: अब चालू
        if (pendingEnable && Setup.accessibilityOn(this)) { pendingEnable = false; AutoPrefs.setOn(this, true); master.isChecked = true; toast("ऑटोनोमस मोड चालू") }
    }

    private var pendingEnable = false

    /** चालू करते समय ही Accessibility की इजाज़त - और क्यों चाहिए, साफ़ बताकर */
    private fun askEnable() {
        if (Setup.accessibilityOn(this)) { AutoPrefs.setOn(this, true); return }
        master.isChecked = false
        UiDialog(this).setTitle("एक इजाज़त चाहिए")
            .setMessage("ऑटोनोमस मोड फ़ोन के ऐप (WhatsApp, Instagram, YouTube, Facebook) में आपकी जगह स्क्रीन पढ़ता है, बटन दबाता है, लिखता है और scroll करता है। " +
                "इसके लिए Android की \"Accessibility\" इजाज़त चाहिए।\n\nपासवर्ड, OTP और बैंक वाली स्क्रीन पर DK कुछ नहीं करेगा। आप कभी भी इसे बंद कर सकते हैं।")
            .setPositiveButton("इजाज़त दें") { _, _ -> pendingEnable = true; Setup.openAccessibility(this) }
            .setNegativeButton("अभी नहीं", null).show()
    }

    private fun keyField(box: LinearLayout, title: String, name: String, hintText: String) {
        box.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt()) })
        val ed = EditText(this).apply {
            hint = hintText; setText(AutoPrefs.secret(this@AutoSettingsActivity, name)); textSize = 14f
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundResource(R.drawable.glass_input); val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        }
        box.addView(ed)
        val res = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var shown = false
        row.addView(small("दिखाओ") { v ->
            shown = !shown
            ed.inputType = InputType.TYPE_CLASS_TEXT or if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            (v as TextView).text = if (shown) "छिपाओ" else "दिखाओ"; ed.setSelection(ed.text.length)
        })
        row.addView(small("सेव") { AutoPrefs.setSecret(this, name, ed.text.toString()); res.text = if (ed.text.isBlank()) "खाली — DK की मुख्य key इस्तेमाल होगी" else "✓ सेव (बंद करके रखी)" })
        row.addView(small("कुंजी जाँचो") {
            val k = ed.text.toString().trim(); res.text = "जाँच रही हूँ…"
            lifecycleScope.launch { res.text = withContext(Dispatchers.IO) { AutoMode.checkKey(this@AutoSettingsActivity, k) } }
        })
        box.addView(row); box.addView(res)
    }

    private fun refreshCustom() {
        customList.removeAllViews()
        val list = AutoPrefs.customTasks(this)
        if (list.isEmpty()) hint(customList, "अभी कोई अपना काम नहीं जोड़ा।")
        for (t in list) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(SwitchCompat(this).apply {
                text = t.name; isChecked = t.on; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                setOnCheckedChangeListener { _, on -> AutoPrefs.saveCustom(this@AutoSettingsActivity, AutoPrefs.customTasks(this@AutoSettingsActivity).map { if (it.id == t.id) it.copy(on = on) else it }) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(small("बदलो") { editTask(t) })
            r.addView(small("हटाओ") {
                UiDialog(this).setMessage("\"${t.name}\" हटा दें?").setPositiveButton("हाँ") { _, _ ->
                    AutoPrefs.saveCustom(this, AutoPrefs.customTasks(this).filter { it.id != t.id }); refreshCustom()
                }.setNegativeButton("नहीं", null).show()
            })
            customList.addView(r)
        }
    }

    private fun editTask(t: AutoPlan.CustomTask?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        val name = EditText(this).apply { hint = "नाम (जैसे: सुबह की रिपोर्ट)"; setText(t?.name.orEmpty()) }
        val ins = EditText(this).apply { hint = "निर्देश (DK को क्या-क्या करना है)"; setText(t?.instructions.orEmpty()); minLines = 3 }
        box.addView(name); box.addView(ins)
        UiDialog(this).setTitle(if (t == null) "नया काम" else "काम बदलो").setView(box)
            .setPositiveButton("सेव") { _, _ ->
                val n = name.text.toString().trim(); val i = ins.text.toString().trim()
                if (n.length < 2 || i.length < 3) { toast("नाम और निर्देश दोनों लिखिए"); return@setPositiveButton }
                val all = AutoPrefs.customTasks(this)
                AutoPrefs.saveCustom(this, if (t == null) all + AutoPlan.CustomTask("c${System.currentTimeMillis()}", n, i)
                    else all.map { if (it.id == t.id) it.copy(name = n, instructions = i) else it })
                refreshCustom()
            }.setNegativeButton("रद्द", null).show()
    }

    private fun refreshLog() {
        val t = AutoLog.read(this).trim()
        logView.text = if (t.isBlank()) "अभी कोई कदम नहीं।" else t.lines().takeLast(150).reversed().joinToString("\n")
    }

    // ---------------------------------------------------------------- छोटे UI हिस्से
    private fun header() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_card); val hp = (10 * dp).toInt(); setPadding(hp, hp, (14 * dp).toInt(), (12 * dp).toInt())
        }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        top.addView(TextView(this).apply {
            text = "ऑटोनोमस मोड"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); setPadding((12 * dp).toInt(), 0, 0, 0)
        })
        col.addView(top)
    }

    private fun card(title: String, sub: String): LinearLayout =
        com.dk.assistant.ui.CollapsibleSection.add(this, col, title, sub.ifBlank { null }, startOpen = true) {}.body

    private fun switch(box: LinearLayout, t: String, on: Boolean, change: (Boolean) -> Unit): SwitchCompat =
        SwitchCompat(this).apply {
            text = t; isChecked = on; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { b, v -> if (b.isPressed) change(v) }
        }.also { box.addView(it) }

    private fun hint(box: LinearLayout, t: String) {
        box.addView(TextView(this).apply { text = t; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt()) })
    }

    private fun button(t: String, accent: Boolean = false, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(if (accent) R.drawable.btn_accent else R.drawable.glass_pill)
        val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun small(t: String, on: (android.view.View) -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill)
        val p = (9 * dp).toInt(); setPadding(p + p / 2, p, p + p / 2, p)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() }
        setOnClickListener { on(it) }
    }

    private fun toast(t: String) = com.dk.assistant.UiLang.toast(this, t, android.widget.Toast.LENGTH_SHORT).show()
}
