package com.dk.assistant

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// SettingsActivity का हिस्सा: सेटिंग पेज बनाने के छोटे हिस्से (card, label, field, switch, button ...).
// यह code पहले SettingsActivity.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state SettingsActivity में)।

/**
 * 19 नवंबर 2026 (मालिक: "Settings की हर चीज़ पर उसी के हिसाब से इमेज"): हर section / श्रेणी का अपना आइकन
 * (पहले फ़ोन लॉक, टच मोड वगैरह पर डिबग वाला कीड़ा, और कई पर दूसरे section का आइकन था).
 * "brain_live" जैसे अंदर वाले पन्ने: अपना न हो तो पहले हिस्से ("brain") वाला.
 */
internal fun SettingsActivity.menuIcon(id: String): Int = settingsIcon(id)

internal fun settingsIcon(id: String): Int = when (id) {
    "cat_dk" -> R.drawable.ic_s_cat_dk
    "cat_brain" -> R.drawable.ic_s_cat_brain
    "cat_live" -> R.drawable.ic_s_cat_live
    "cat_voice" -> R.drawable.ic_s_cat_voice
    "persona" -> R.drawable.ic_s_persona
    "memory" -> R.drawable.ic_s_memory
    "cat_auto" -> R.drawable.ic_s_bolt
    "cat_security" -> R.drawable.ic_s_perms
    "cat_social" -> R.drawable.ic_s_groups
    "cat_data" -> R.drawable.ic_s_storage
    "profile" -> R.drawable.ic_s_profile
    "lang" -> R.drawable.ic_s_lang
    "brain" -> R.drawable.ic_s_brain
    "brain_live" -> R.drawable.ic_s_mic
    "brain_chat" -> R.drawable.ic_s_chat
    "brain_coder" -> R.drawable.ic_s_coder
    "brain_pc" -> R.drawable.ic_s_computer
    "voice" -> R.drawable.ic_s_voice
    "st3" -> R.drawable.ic_s_st3
    "orb" -> R.drawable.ic_s_orb
    "ui" -> R.drawable.ic_s_ui
    "theme" -> R.drawable.ic_s_theme
    "access" -> R.drawable.ic_s_access
    "phonelock" -> R.drawable.ic_s_lock
    "guard" -> R.drawable.ic_s_touch
    "applock" -> R.drawable.ic_s_applock
    "face" -> R.drawable.ic_s_face
    "perms" -> R.drawable.ic_s_shield_ok
    "coder" -> R.drawable.ic_s_coder
    "computer" -> R.drawable.ic_s_computer
    "story" -> R.drawable.ic_s_story
    "connectors" -> R.drawable.ic_s_connect
    "backup" -> R.drawable.ic_s_backup
    "license" -> R.drawable.ic_s_key
    "social" -> R.drawable.ic_s_trend
    "reels" -> R.drawable.ic_s_movie
    "auto" -> R.drawable.ic_s_robot
    "templates" -> R.drawable.ic_s_template
    "autoreply" -> R.drawable.ic_s_reply
    "update" -> R.drawable.ic_s_update
    "debug" -> R.drawable.ic_s_debug
    "about" -> R.drawable.ic_s_about
    "livewall" -> R.drawable.ic_s_wallpaper
    else -> if ('_' in id) settingsIcon(id.substringBefore('_')) else R.drawable.ic_s_debug
}

/** नवंबर 2026: squircle आइकन का निऑन रंग (हर section अलग, ताकि मेनू में पहचान आसान) */
internal fun SettingsActivity.menuTint(id: String): Int = settingsTint(id)

internal fun settingsTint(id: String): Int = when (id) {
    "cat_dk" -> 0xFF22D3EE.toInt()
    "cat_brain" -> 0xFF6366F1.toInt()
    "cat_live" -> 0xFFEC4899.toInt()
    "cat_voice" -> 0xFFF472B6.toInt()
    "persona" -> 0xFFEC4899.toInt()
    "memory" -> 0xFF22C55E.toInt()
    "cat_auto" -> 0xFFA855F7.toInt()
    "cat_security" -> 0xFF34D399.toInt()
    "cat_social" -> 0xFFFBBF24.toInt()
    "cat_data" -> 0xFF38BDF8.toInt()
    "profile", "about" -> 0xFF22D3EE.toInt()
    "voice", "templates", "brain_live", "st3" -> 0xFFA855F7.toInt()
    "autoreply" -> 0xFF22C55E.toInt()
    "brain", "auto", "coder", "brain_chat", "brain_coder" -> 0xFF6366F1.toInt()
    "ui", "orb", "reels", "theme" -> 0xFFEC4899.toInt()
    "access", "perms", "phonelock", "guard", "applock", "face", "backup" -> 0xFF34D399.toInt()
    "computer", "connectors", "lang", "brain_pc" -> 0xFF38BDF8.toInt()
    "update", "story", "social", "license" -> 0xFFFBBF24.toInt()
    "debug" -> 0xFFF87171.toInt()
    "livewall" -> 0xFFF59E0B.toInt()
    else -> if ('_' in id && !id.startsWith("cat_")) settingsTint(id.substringBefore('_')) else 0xFF94A3B8.toInt()
}

internal fun SettingsActivity.radios(box: LinearLayout, items: List<Pair<String, String>>, current: String, base: Int): RadioGroup {
    val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    items.forEachIndexed { i, (v, t) ->
        g.addView(RadioButton(this).apply {
            id = base + i; text = t; textSize = 14f; setTextColor(com.dk.assistant.ui.ClayTheme.t.text)
            setPadding((6 * dp).toInt(), (5 * dp).toInt(), 0, (5 * dp).toInt())
        })
        if (v == current) g.check(base + i)
    }
    if (g.checkedRadioButtonId == -1) g.check(base)
    box.addView(g)
    return g
}

internal fun SettingsActivity.radioValue(g: RadioGroup, values: List<String>, base: Int) =
    values.getOrElse(g.checkedRadioButtonId - base) { values.first() }

internal fun SettingsActivity.sp(): Speaker = speaker ?: Speaker(this).also { speaker = it }

/** v3.69: हर सेटिंग हिस्सा खुलने-बंद होने वाला कॉलम (साझा: ui/CollapsibleSection) - शुरू में बंद */
internal fun SettingsActivity.card(title: String, sub: String?, startOpen: Boolean = false, body: (LinearLayout) -> Unit) {
    val focus = intent.getStringExtra("focus")
    val s = com.dk.assistant.ui.CollapsibleSection.add(this, container, title, sub, startOpen = startOpen || focus == title, body = body)
    // सर्च से आए: वही कॉलम खुला मिले और स्क्रीन उसी तक जाए
    if (focus == title) s.view.post { findViewById<android.widget.ScrollView>(R.id.scroll)?.smoothScrollTo(0, (s.view.top - (8 * dp).toInt()).coerceAtLeast(0)) }
}

internal fun SettingsActivity.openHit(e: SettingsSearch.E) {
    val i = when (e.section) {
        SettingsSearch.ACT_AUTO -> android.content.Intent(this, com.dk.assistant.auto.AutoSettingsActivity::class.java)
        SettingsSearch.ACT_TEMPLATES -> android.content.Intent(this, com.dk.assistant.template.TemplateSettingsActivity::class.java)
        SettingsSearch.ACT_CONNECTORS -> android.content.Intent(this, com.dk.assistant.connect.ConnectorsActivity::class.java)
        SettingsSearch.ACT_STORY -> android.content.Intent(this, StoryWriterActivity::class.java)
        SettingsSearch.ACT_BACKUP -> android.content.Intent(this, com.dk.assistant.backup.BackupActivity::class.java)
        else -> android.content.Intent(this, SettingsActivity::class.java).putExtra("section", e.section).also { if (e.card.isNotBlank()) it.putExtra("focus", e.card) }
    }
    startActivity(i)
}

internal fun SettingsActivity.label(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
    text = t; textSize = 13f; setTextColor(com.dk.assistant.ui.ClayTheme.t.sub)
    setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
})

internal fun SettingsActivity.hint(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
    text = t; textSize = 12f; setTextColor(com.dk.assistant.ui.ClayTheme.t.gray)
    setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0)
})

internal fun SettingsActivity.field(box: LinearLayout, title: String, value: String, hint: String,
                  secret: Boolean = false, multiLine: Boolean = false,
                  // 16 नवंबर 2026 (मालिक, तस्वीर जैसा): हर API key / token = अपना कार्ड (ताला, सेव + टेस्ट, हरा ✓)
                  card: Boolean = secret && KeyTick.isKeyTitle(title), keyUrl: String? = null, tip: String? = null,
                  onSave: ((String) -> Unit)? = null, check: (suspend (String) -> String)? = null): EditText {
    if (card) return keyCard(box, title, value, hint, keyUrl, tip, onSave, check)
    label(box, title)
    val e = EditText(this).apply {
        setText(value)
        this.hint = hint
        background = com.dk.assistant.ui.Clay.inset(this@field, 18f, 3f)   // धँसा खाना
        setTextColor(com.dk.assistant.ui.ClayTheme.t.text); setHintTextColor(com.dk.assistant.ui.ClayTheme.t.hint)
        textSize = 15f
        val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiLine) { minLines = 2; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
        addTextChangedListener(watcher)
    }
    box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (secret) keyTick(box, e)          // 14 नवंबर 2026: सही key = हरा ✓ (आवाज़ में भी), फिर गायब
    return e
}

/**
 * नवंबर 2026 (मालिक: "जो बैक एंड में रहना चाहिए वो फ्रंटेंड में मत दिखाओ, बैक एंड में रख दो"):
 * तकनीकी / इंजन वाले विकल्प इसी छिपे हिस्से में बनते हैं - पसंद सेव और लागू वैसे ही, बस स्क्रीन पर नहीं दिखते.
 */
internal fun SettingsActivity.backend(box: LinearLayout, body: (LinearLayout) -> Unit): LinearLayout {
    val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = BACKEND_TAG }
    box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    body(b)
    return b
}

internal const val BACKEND_TAG = "dk_backend"

internal fun SettingsActivity.switch(box: LinearLayout, t: String, on: Boolean): SwitchCompat {
    val s = SwitchCompat(this).apply {
        text = t; isChecked = on; textSize = 14f; setTextColor(com.dk.assistant.ui.ClayTheme.t.text)
        setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
        setOnCheckedChangeListener { _, _ -> txtSaved?.text = "बदलाव सेव नहीं हुए — सेव दबाएँ" }
    }
    box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return s
}

/** 0.5x .. 2.0x */
internal fun SettingsActivity.seek(box: LinearLayout, value: Float): SeekBar {
    val s = SeekBar(this).apply { max = 150; progress = ((value - 0.5f) * 100).toInt().coerceIn(0, 150) }
    box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return s
}

internal fun SettingsActivity.seekValue(s: SeekBar) = 0.5f + s.progress / 100f

internal fun SettingsActivity.button(t: String, onClick: (View) -> Unit) = Button(this).apply {
    text = t; isAllCaps = false; textSize = 13f; setTextColor(com.dk.assistant.ui.ClayTheme.t.text)
    background = com.dk.assistant.ui.Clay.chip(this@button, 20f, 4f)   // उभरा, दबाने पर धँसे
    stateListAnimator = null
    minHeight = 0; minimumHeight = 0
    val ph = (14 * dp).toInt(); setPadding(ph, (8 * dp).toInt(), ph, (8 * dp).toInt())
    setOnClickListener(onClick)
}

internal fun SettingsActivity.row(box: LinearLayout, vararg views: View) {
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * dp).toInt(), 0, 0) }
    views.forEach { v ->
        val lp = LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f)
        lp.marginEnd = (8 * dp).toInt()
        r.addView(v, lp)
    }
    box.addView(r)
}

internal fun SettingsActivity.space(box: LinearLayout, h: Int) = box.addView(View(this), LinearLayout.LayoutParams(1, (h * dp).toInt()))

internal fun SettingsActivity.toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
