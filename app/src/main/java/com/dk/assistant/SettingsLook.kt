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

// SettingsActivity का हिस्सा: रूप: थीम, रंग, ORB.
// यह code पहले SettingsActivity.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state SettingsActivity में)।

// ------------------------------------------------------------ v4: दिखावट (UI कस्टमाइज़ेशन) - सिर्फ़ रंग-रूप
/**
 * थीम, मुख्य रंग, काँच की पारदर्शिता, चमक, एनिमेशन. बदलते ही पूरा ऐप नए रूप में (पीछे खुली स्क्रीनें लौटते ही).
 * यह AI, आवाज़, prompt, API key या बातचीत/यादों को बिल्कुल नहीं छूता - सिर्फ़ ui_* पसंद.
 */
internal fun SettingsActivity.secUi() {
    val T = com.dk.assistant.ui.NeonTheme
    fun applyLook(l: com.dk.assistant.ui.NeonTheme.Look) {
        T.save(this, l)
        com.dk.assistant.ui.NeonRefresh.bars(this)
        com.dk.assistant.ui.NeonRefresh.invalidateAll(window.decorView)
    }
    card("झलक", "बदलाव यहीं तुरंत दिखता है", startOpen = true) { box ->
        box.addView(OrbView(this).apply { previewStyle = Shapes.NEON; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (170 * dp).toInt()))
        row(box,
            Button(this).apply {
                text = "मुख्य बटन"; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.btn_accent); isClickable = false
            },
            button("काँच का बटन") {}.apply { isClickable = false })
    }
    themeGallery()
    card("थीम", "पृष्ठभूमि का रंग", startOpen = true) { box ->
        val g = radios(box, T.THEMES, T.look.theme, 6100)
        g.setOnCheckedChangeListener { _, id ->
            val v = T.THEMES.getOrNull(id - 6100)?.first ?: return@setOnCheckedChangeListener
            applyLook(T.look.copy(theme = v))
        }
        hint(box, "हल्की (Light) थीम अभी नहीं है: ऐप की ज़्यादातर स्क्रीनों पर सफ़ेद अक्षर हैं, हल्की पृष्ठभूमि पर वे पढ़ने में नहीं आते। " +
            "\"फ़ोन जैसी (System)\" भी इसी वजह से नहीं — ऐप हमेशा गहरी थीम में रहता है।")
    }
    card("मुख्य रंग (Accent)", "किनारे, चमक, मुख्य बटन और ओर्ब का रंग", startOpen = true) { box ->
        val items = com.dk.assistant.ui.NeonTheme.Accent.values().map { it.key to it.label }
        val g = radios(box, items, T.look.accent.key, 6200)
        com.dk.assistant.ui.NeonTheme.Accent.values().forEachIndexed { i, ac ->
            (g.getChildAt(i) as? RadioButton)?.let { rb ->
                val sp = android.text.SpannableString("●  " + UiLang.s(ac.label))
                sp.setSpan(android.text.style.ForegroundColorSpan(ac.main), 0, 1, 0)
                rb.text = sp; rb.minHeight = (48 * dp).toInt()
            }
        }
        g.setOnCheckedChangeListener { _, id ->
            val ac = com.dk.assistant.ui.NeonTheme.Accent.values().getOrNull(id - 6200) ?: return@setOnCheckedChangeListener
            applyLook(T.look.copy(accent = ac))
        }
    }
    fun slider(box: LinearLayout, title: String, value: Int, set: (Int) -> com.dk.assistant.ui.NeonTheme.Look) {
        val lab = TextView(this).apply {
            textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun show(v: Int) { lab.text = UiLang.s(title) + ": $v%" }
        show(value); box.addView(lab)
        val sb = SeekBar(this).apply {
            max = 100; progress = value; minimumHeight = (48 * dp).toInt()
            contentDescription = UiLang.s(title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { show(p) }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) { applyLook(set(progress)) }
            })
        }
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
    }
    card("काँच और चमक", "पैनल कितने पारदर्शी हों, किनारों की नियॉन चमक कितनी हो", startOpen = true) { box ->
        slider(box, "काँच की पारदर्शिता", T.look.glass) { T.look.copy(glass = it) }
        slider(box, "नियॉन चमक", T.look.glow) { T.look.copy(glow = it) }
        hint(box, "ज़्यादा पारदर्शिता पर भी अक्षर पढ़ने लायक रहें, इसलिए काँच कभी पूरा गायब नहीं होता। " +
            "पीछे का असली धुंधलापन (blur) Android 12+ पर सिर्फ़ dialog के पीछे; बाकी जगह हल्का काँच-जैसा रंग।")
    }
    card("एनिमेशन", "ओर्ब के छल्ले, पृष्ठभूमि की रोशनी और मेनू का खुलना", startOpen = true) { box ->
        val g = radios(box, T.MOTIONS, T.look.motion, 6300)
        g.setOnCheckedChangeListener { _, id ->
            val v = T.MOTIONS.getOrNull(id - 6300)?.first ?: return@setOnCheckedChangeListener
            applyLook(T.look.copy(motion = v))
        }
        if (T.systemNoAnim) hint(box, "फ़ोन की सेटिंग में एनिमेशन बंद हैं — इसलिए DK अभी अपने-आप कम एनिमेशन में है।")
    }
    // नवंबर 2026 (मालिक): एक ही तैरता ORB (नीचे बीच में) - दिखे/छिपे + आकार. ORB पर दो उंगलियों से pinch भी
    card("तैरता ORB", "DK सुने या बोले तभी दिखे, चुप = छिप जाए — उंगली से कहीं भी खिसकाएँ", startOpen = true) { box ->
        val on = SwitchCompat(this).apply {
            text = UiLang.s("ORB दिखाओ"); isChecked = Prefs.floatOrbOn(this@secUi); textSize = 14f
            setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, v ->
                Prefs.setFloatOrbOn(this@secUi, v)
                if (v) OrbNotify.cancel(this@secUi)
                DKAccessibilityService.instance?.refreshOrb()
            }
        }
        box.addView(on, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lab = TextView(this).apply {
            textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun showSize(v: Int) { lab.text = UiLang.s("ORB का आकार") + ": $v dp" + (if (v == FloatOrbSize.DEFAULT_DP) " (" + UiLang.s("शुरुआती") + ")" else "") }
        val cur = Prefs.floatOrbDp(this)
        showSize(cur); box.addView(lab)
        val sb = SeekBar(this).apply {
            max = FloatOrbSize.MAX_DP - FloatOrbSize.MIN_DP; progress = cur - FloatOrbSize.MIN_DP
            minimumHeight = (48 * dp).toInt(); contentDescription = UiLang.s("ORB का आकार")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = FloatOrbSize.MIN_DP + p
                    showSize(v)
                    if (fromUser) { Prefs.setFloatOrbDp(this@secUi, v); DKAccessibilityService.instance?.refreshOrb() }
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        hint(box, "DK ऐप खुला हो तब छोटा ORB नहीं दिखता। ऐप से बाहर जाते ही ORB छोटा होकर नीचे बीच में आता है और वहीं रहता है। " +
            "उस पर ✕ नहीं है — छिपाना हो तो यहाँ का स्विच बंद करें (DK का माइक, \"हे डीके\" और सेवा चलते रहते हैं)। " +
            "दो उंगलियों से फैलाएँ/सिकोड़ें तो आकार बदलता है।" +
            (if (DKAccessibilityService.instance == null) " (अभी DK की Accessibility सेवा बंद है — ORB तभी दिखेगा जब वह चालू हो।)" else ""))
    }
    // v4.x: जवाब आने से पहले बुलबुले में चलने वाला "लिख रही है" एनीमेशन
    card("टाइपिंग एनीमेशन", "जवाब आने से पहले चैट के बुलबुले में क्या चले — जो पसंद हो चुनें", startOpen = true) { box ->
        val cur = Prefs.typingStyle(this)
        val prev = DKWorkingIndicator(this).apply { bare = true; state = WorkState.THINKING; typingPreview = cur }
        box.addView(prev, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val g = radios(box, TypingStyles.STYLES, cur, 6400)
        g.setOnCheckedChangeListener { _, id ->
            val v = TypingStyles.STYLES.getOrNull(id - 6400)?.first ?: return@setOnCheckedChangeListener
            Prefs.edit(this) { putString("typing_style", v) }
            prev.typingPreview = v
        }
    }
    card("दिखावट पहले जैसी करें", "सिर्फ़ रंग-रूप शुरुआती जैसा — बातचीत, यादें, key और आवाज़ की सेटिंग नहीं मिटतीं", startOpen = true) { box ->
        row(box, button("दिखावट रीसेट करें") {
            UiDialog(this, Glass.DIALOG)
                .setTitle("दिखावट पहले जैसी करें?")
                .setMessage("थीम, मुख्य रंग, काँच, चमक और एनिमेशन शुरुआती हो जाएँगे। आपका कोई डेटा नहीं मिटेगा।")
                .setPositiveButton("हाँ, रीसेट करें") { _, _ ->
                    T.resetAppearance(this)
                    toast("दिखावट शुरुआती जैसी हो गई")
                    recreate()
                }
                .setNegativeButton("नहीं", null)
                .show()
        })
    }
}

// ------------------------------------------------------------ orb + chehra
internal fun SettingsActivity.secOrb() {
    val styles = Shapes.STYLES
    lateinit var preview: OrbView
    var chosen = Prefs.orbStyle(this)
    card("झलक", "ओर्ब पर टैप = चेहरा बनने वाला एनीमेशन") { box ->
        preview = OrbView(this).apply {
            previewStyle = chosen
            setOnClickListener { playFace(this, chosen) }
        }
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (260 * dp).toInt()))
    }
    card("ओर्ब का आकार", "सब छोटे-छोटे 3D डॉट से बने हैं — जो पसंद हो चुनें") { box ->
        val g = radios(box, styles, chosen, 2400)
        g.setOnCheckedChangeListener { _, id ->
            chosen = styles.getOrNull(id - 2400)?.first ?: "earth"
            Prefs.edit(this) { putString("orb_style", chosen) }
            preview.previewStyle = chosen
        }
    }
    card("किनारों की चमक", "DK सुन या बोल रहा हो तो स्क्रीन के किनारे चमकें") { box ->
        switch(box, "सुनते समय बैंगनी, बोलते समय गुलाबी चमक", Prefs.edgeGlow(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("edge_glow", on) } }
        hint(box, "चमक छूने पर कुछ नहीं करती — नीचे का ऐप वैसे ही चलता है। फ़ोन में animation बंद हों तो चमक स्थिर रहती है।")
    }
    card("DK का चेहरा", "\"अपना चेहरा दिखाओ\" बोलने पर डॉट निकलकर चेहरा बनता है — आवाज़ महिला हो तो महिला चेहरा, पुरुष हो तो पुरुष") { box ->
        row(box,
            button("चेहरा दिखाओ") { playFace(preview, chosen) },
            button("बड़ी स्क्रीन पर") {
                startActivity(android.content.Intent(this, VoiceActivity::class.java).putExtra("face", true))
            })
        hint(box, "अभी की आवाज़: " + (if (Prefs.voiceGender(this) == "male") "पुरुष" else "महिला") + " — बदलने के लिए सेटिंग्स → आवाज़")
    }
}


// ------------------------------------------------------------ नवंबर 2026: थीम गैलरी (पूरी थीम = पृष्ठभूमि चित्र + काँच + रंग + ओर्ब)
/**
 * "DK Futuristic" ऐप में ही है (कोई डाउनलोड नहीं). बाकी थीमें GitHub के themes/ से एक बार डाउनलोड होती हैं
 * (theme.json + bg.webp, फ़ोन की अपनी जगह filesDir/themes में). लागू करते ही पूरा ऐप उसी थीम में.
 */
internal fun SettingsActivity.themeGallery() {
    val P = com.dk.assistant.ui.ThemePacks
    card("थीम गैलरी", "पूरी थीम: पृष्ठभूमि चित्र, काँच, रंग और ओर्ब", startOpen = true) { box ->
        val cur = P.selectedId(this)
        val all = listOf(P.Entry(P.DEFAULT_ID, P.DEFAULT.name, P.DEFAULT.desc, P.DEFAULT.accent)) + P.CATALOG
        for (e in all) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }
            line.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(e.accent); setStroke((2 * dp).toInt(), 0x66FFFFFF)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = e.name; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
            txt.addView(TextView(this).apply {
                text = UiLang.s(e.desc)
                textSize = 12f; setTextColor(0x99FFFFFF.toInt())
            })
            line.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val has = P.installed(this, e.id)
            val act = button(when { e.id == cur -> "लागू है"; has -> "लागू करें"; else -> "डाउनलोड" }) { v ->
                val b = v as Button
                if (P.installed(this, e.id)) {
                    P.select(this, e.id)
                    com.dk.assistant.ui.NeonRefresh.bars(this)
                    toast("थीम लागू हो गई")
                    recreate()
                } else {
                    b.isEnabled = false; b.text = UiLang.s("डाउनलोड हो रहा है…")
                    val app = applicationContext
                    Thread {
                        val err = P.download(app, e.id)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            b.isEnabled = true
                            if (err == null) { b.text = UiLang.s("लागू करें"); toast("डाउनलोड हो गई") }
                            else { b.text = UiLang.s("डाउनलोड"); toast(err) }
                        }
                    }.start()
                }
            }.apply { isEnabled = e.id != cur; minHeight = (44 * dp).toInt() }
            line.addView(act)
            if (has && e.id != P.DEFAULT_ID) line.addView(button("हटाएँ") {
                P.remove(this, e.id); com.dk.assistant.ui.NeonRefresh.bars(this); recreate()
            }.apply { minHeight = (44 * dp).toInt() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (6 * dp).toInt() })
            box.addView(line)
        }
        hint(box, "रंग बदलना अलग है (नीचे 'मुख्य रंग'). थीम बदलने पर पूरा रूप बदलता है. डाउनलोड वाली थीम एक बार इंटरनेट से आती है, फिर बिना इंटरनेट चलती है.")
    }
}
