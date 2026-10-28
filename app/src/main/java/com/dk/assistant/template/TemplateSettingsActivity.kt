package com.dk.assistant.template

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.dk.assistant.AuroraView
import com.dk.assistant.R
import com.dk.assistant.UiDialog

/**
 * Settings → टेम्पलेट कमांड: अंदर वाले साँचे (हर एक का चालू/बंद) + अपने साँचे (जोड़ो / बदलो / हटाओ / चालू-बंद).
 */
class TemplateSettingsActivity : AppCompatActivity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var col: LinearLayout
    private lateinit var userBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        scroll.addView(col); root.addView(scroll, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
        header()

        val mine = card("मेरे साँचे", "अपने बनाए साँचे सबसे पहले मिलाए जाते हैं. खाली जगह के लिए ___ लिखें")
        userBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mine.addView(userBox)
        mine.addView(button("+ नया साँचा जोड़ो") { edit(null) })
        refreshUser()

        val inb = card("अंदर वाले साँचे", "ये बिना API key चलते हैं. जो बंद करेंगे, वह बात पहले जैसे DK / AI को जाएगी")
        for (t in TemplateCommands.BUILTIN) {
            inb.addView(SwitchCompat(this).apply {
                text = "${t.name}\n\"${t.example}\""; isChecked = TemplateStore.on(this@TemplateSettingsActivity, t.id); textSize = 13.5f
                setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnCheckedChangeListener { b, v -> if (b.isPressed) TemplateStore.setOn(this@TemplateSettingsActivity, t.id, v) }
            })
        }
        hint(inb, "सुरक्षा: पासवर्ड / OTP / बैंक / भुगतान वाली स्क्रीन पर DK कुछ नहीं भरता। शॉपिंग में DK कार्ट तक ही जाता है — ऑर्डर, भुगतान या पता नहीं बदलता। " +
            "कई कॉन्टैक्ट मिलें तो पूछता है। ऑटोनोमस मोड का \"भेजने से पहले पूछो\" switch चालू हो तो मैसेज भेजने से पहले एक बार पूछता है।")
    }

    private fun refreshUser() {
        userBox.removeAllViews()
        val list = TemplateStore.user(this)
        if (list.isEmpty()) hint(userBox, "अभी कोई अपना साँचा नहीं।")
        for (t in list) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(SwitchCompat(this).apply {
                text = "${t.name}\n\"${t.example}\""; isChecked = t.on; textSize = 13.5f; setTextColor(0xFFFFFFFF.toInt())
                setOnCheckedChangeListener { b, on -> if (b.isPressed) TemplateStore.saveUser(this@TemplateSettingsActivity,
                    TemplateStore.user(this@TemplateSettingsActivity).map { if (it.id == t.id) it.copy(on = on) else it }) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(small("बदलो") { edit(t) })
            r.addView(small("हटाओ") {
                UiDialog(this).setMessage("\"${t.name}\" हटा दें?").setPositiveButton("हाँ") { _, _ ->
                    TemplateStore.saveUser(this, TemplateStore.user(this).filter { it.id != t.id }); refreshUser()
                }.setNegativeButton("नहीं", null).show()
            })
            userBox.addView(r)
        }
    }

    /** नया / बदलो: नाम, साँचा (___ के साथ), खाली जगहों के नाम, क्या करना है, कौन-सा ऐप */
    private fun edit(t: TemplateCommands.Template?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        val name = EditText(this).apply { hint = "नाम (जैसे: Spotify गाना)"; setText(t?.name.orEmpty()) }
        val pat = EditText(this).apply { hint = "साँचा, जैसे: स्पॉटिफाई पर ___ बजाओ"; setText(t?.example.orEmpty()) }
        val slots = EditText(this).apply { hint = "खाली जगहों के नाम (कॉमा से), जैसे: गाना"; setText(t?.slots?.joinToString(", ").orEmpty()) }
        val keys = TemplateStore.USER_ACTIONS.keys.toList()
        var action = t?.action?.takeIf { it in keys } ?: keys[0]
        var app = t?.app.orEmpty()
        val actBtn = small("काम: ${TemplateStore.USER_ACTIONS[action]}") {}
        val appBtn = small("ऐप: ${app.ifBlank { "चुनें" }}") {}
        actBtn.setOnClickListener {
            UiDialog(this).setTitle("क्या करना है").setItems(keys.map { TemplateStore.USER_ACTIONS[it] }.toTypedArray()) { _, i ->
                action = keys[i]; actBtn.text = "काम: ${TemplateStore.USER_ACTIONS[action]}"
            }.show()
        }
        appBtn.setOnClickListener {
            val apps = launcherApps()
            UiDialog(this).setTitle("ऐप चुनें").setItems(apps.toTypedArray()) { _, i -> app = apps[i]; appBtn.text = "ऐप: $app" }.show()
        }
        listOf(name, pat, slots).forEach { box.addView(it) }
        box.addView(actBtn); box.addView(appBtn)
        UiDialog(this).setTitle(if (t == null) "नया साँचा" else "साँचा बदलो").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("सेव") { _, _ ->
                val n = name.text.toString().trim(); val p = pat.text.toString().trim()
                val sl = slots.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val blanks = Regex("_{2,}").findAll(p).count()
                when {
                    n.length < 2 || p.length < 3 -> { toast("नाम और साँचा दोनों लिखिए"); return@setPositiveButton }
                    action != "open_app" && blanks == 0 -> { toast("इस काम के लिए साँचे में कम से कम एक ___ चाहिए"); return@setPositiveButton }
                    action in setOf("open_app", "app_search") && app.isBlank() -> { toast("ऐप चुनिए"); return@setPositiveButton }
                }
                val names = if (sl.size >= blanks) sl.take(blanks) else sl + (sl.size until blanks).map { "जगह ${it + 1}" }
                val all = TemplateStore.user(this)
                val nt = TemplateCommands.Template(t?.id ?: "u${System.currentTimeMillis()}", n, p, listOf(TemplateCommands.userPattern(p)),
                    names, action, app, builtin = false, on = t?.on ?: true)
                TemplateStore.saveUser(this, if (t == null) all + nt else all.map { if (it.id == t.id) nt else it })
                refreshUser()
            }.setNegativeButton("रद्द", null).show()
    }

    private fun launcherApps(): List<String> = try {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(packageManager).toString() }.distinct().sorted()
    } catch (_: Exception) { emptyList() }

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
            text = "टेम्पलेट कमांड"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); setPadding((12 * dp).toInt(), 0, 0, 0)
        })
        col.addView(top)
    }

    private fun card(title: String, sub: String): LinearLayout =
        com.dk.assistant.ui.CollapsibleSection.add(this, col, title, sub, startOpen = true) {}.body

    private fun hint(box: LinearLayout, t: String) {
        box.addView(TextView(this).apply { text = t; textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt()) })
    }

    private fun button(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill); val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun small(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill); val p = (9 * dp).toInt(); setPadding(p + p / 2, p, p + p / 2, p)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun toast(t: String) = com.dk.assistant.UiLang.toast(this, t, android.widget.Toast.LENGTH_SHORT).show()
}
