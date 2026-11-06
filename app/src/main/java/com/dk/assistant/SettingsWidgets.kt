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

internal fun SettingsActivity.menuIcon(id: String) = when (id) {
    "profile" -> R.drawable.ic_s_profile
    "lang" -> R.drawable.ic_s_lang
    "brain" -> R.drawable.ic_s_brain
    "voice" -> R.drawable.ic_s_voice
    "orb" -> R.drawable.ic_s_orb
    "ui" -> R.drawable.ic_s_ui
    "access" -> R.drawable.ic_s_access
    "perms" -> R.drawable.ic_s_perms
    "coder" -> R.drawable.ic_s_coder
    "computer" -> R.drawable.ic_s_computer
    "story" -> R.drawable.ic_s_story
    "connectors" -> R.drawable.ic_s_connect
    "social" -> R.drawable.ic_s_story
    "reels" -> R.drawable.ic_s_ui
    "auto" -> R.drawable.ic_s_brain
    "templates" -> R.drawable.ic_s_voice
    "update" -> R.drawable.ic_s_update
    "about" -> R.drawable.ic_s_profile
    else -> R.drawable.ic_s_debug
}

internal fun SettingsActivity.radios(box: LinearLayout, items: List<Pair<String, String>>, current: String, base: Int): RadioGroup {
    val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    items.forEachIndexed { i, (v, t) ->
        g.addView(RadioButton(this).apply {
            id = base + i; text = t; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
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
        else -> android.content.Intent(this, SettingsActivity::class.java).putExtra("section", e.section).also { if (e.card.isNotBlank()) it.putExtra("focus", e.card) }
    }
    startActivity(i)
}

internal fun SettingsActivity.label(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
    text = t; textSize = 13f; setTextColor(0xB3FFFFFF.toInt())
    setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
})

internal fun SettingsActivity.hint(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
    text = t; textSize = 12f; setTextColor(0x80FFFFFF.toInt())
    setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0)
})

internal fun SettingsActivity.field(box: LinearLayout, title: String, value: String, hint: String,
                  secret: Boolean = false, multiLine: Boolean = false): EditText {
    label(box, title)
    val e = EditText(this).apply {
        setText(value)
        this.hint = hint
        setBackgroundResource(R.drawable.glass_input)
        setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt())
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
    return e
}

internal fun SettingsActivity.switch(box: LinearLayout, t: String, on: Boolean): SwitchCompat {
    val s = SwitchCompat(this).apply {
        text = t; isChecked = on; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
        setOnCheckedChangeListener { _, _ -> txtSaved.text = "बदलाव सेव नहीं हुए — सेव दबाएँ" }
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
    text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
    setBackgroundResource(R.drawable.glass_pill)
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
