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
 * डार्क / लाइट, orb, एनिमेशन (थीम के रंग अब Settings → Theme में). बदलते ही पूरा ऐप नए रूप में (पीछे खुली स्क्रीनें लौटते ही).
 * यह AI, आवाज़, prompt, API key या बातचीत/यादों को बिल्कुल नहीं छूता - सिर्फ़ ui_* पसंद.
 */
internal fun SettingsActivity.secUi() {
    val T = com.dk.assistant.ui.NeonTheme
    fun applyLook(l: com.dk.assistant.ui.NeonTheme.Look) {
        T.save(this, l)
        com.dk.assistant.ui.NeonRefresh.bars(this)
        com.dk.assistant.ui.NeonRefresh.invalidateAll(window.decorView)
    }
    // 14 नवंबर 2026 (मालिक: "सेटिंग में डार्क / लाइट थीम का मोड नहीं दिख रहा"): जड़ - यह बदलाव सिर्फ़ ⚙ लंबा दबाने वाली
    // सूची में छिपा था. अब यहाँ सबसे ऊपर - DKAI होम, मेनू, सेटिंग और DK Computer चुने रंग में
    card("डार्क / लाइट मोड", "DKAI होम, मेनू, सेटिंग और DK Computer का रंग", startOpen = true) { box ->
        val g = radios(box, SettingsThemeMode.ITEMS, SettingsThemeMode.current(com.dk.assistant.dkai.DkaiTheme.light), 6050)
        g.setOnCheckedChangeListener { _, id ->
            val light = SettingsThemeMode.isLight(SettingsThemeMode.ITEMS.getOrNull(id - 6050)?.first ?: return@setOnCheckedChangeListener)
            if (light == com.dk.assistant.dkai.DkaiTheme.light) return@setOnCheckedChangeListener
            com.dk.assistant.dkai.DkaiTheme.setLight(this, light)
            toast(if (light) "लाइट मोड चालू" else "डार्क मोड चालू")
            save(false); recreate()
        }
        hint(box, "बदलते ही यह स्क्रीन नए रंग में खुलती है; DKAI होम लौटते ही नए रंग में दिखेगा।")
    }
    card("DKAI का orb", "नए DKAI होम में आवाज़ वाला orb — 7 नए या पुराना DK orb", startOpen = true) { box ->
        val orbs = com.dk.assistant.dkai.DkaiOrbs.ORBS
        val g = radios(box, orbs, com.dk.assistant.dkai.DkaiOrbs.get(this), 6600)
        g.setOnCheckedChangeListener { _, id -> orbs.getOrNull(id - 6600)?.let { com.dk.assistant.dkai.DkaiOrbs.set(this, it.first) } }
        hint(box, "पुराना DK orb चुना = उसका आकार \"ओर्ब और चेहरा\" वाले हिस्से से। orb सिर्फ़ यहीं से बदलता है — DKAI में orb पर टैप करने से कुछ नहीं खुलता।")
    }
    // 23 नवंबर 2026 (मालिक): "होम कैरेक्टर" - कोलाज के सारे आर्टवर्क छोटे प्रीव्यू के साथ (+ ऊपर वाला ORB). चुनाव सेव रहता है
    // (ऐप बंद होने के बाद भी). पहले वाला अवतार हटा; नए आर्टवर्क ऐप के अंदर हैं (डाउनलोड नहीं).
    card("होम कैरेक्टर", "होम के बीच में कौन दिखे — टैप करके चुनें", startOpen = true) { box ->
        homeCharacterGrid(box)
        hint(box, "डिफ़ॉल्ट: DK वेव ऑर्ब। \"ORB\" = ऊपर \"DKAI का orb\" में चुना हुआ चलता orb।")
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
    // 23 नवंबर 2026 (मालिक): पुराना थीम-चुनाव, थीम गैलरी और मुख्य रंग वाले कार्ड पूरी तरह हटे - रंग अब Settings → Theme से
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
    // 14 नवंबर 2026 (मालिक: "ग्लासी इफेक्ट वाला सब हटा दो, सभी जगह से"): काँच और चमक वाला कार्ड हटाया - हर स्क्रीन अब होम जैसी सपाट (PlainLook)
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
        val g = radios(box, TypingStyles.ALL, cur, 6400)
        g.setOnCheckedChangeListener { _, id ->
            val v = TypingStyles.ALL.getOrNull(id - 6400)?.first ?: return@setOnCheckedChangeListener
            Prefs.edit(this) { putString("typing_style", v) }
            prev.typingPreview = v
        }
        hint(box, "यही एनीमेशन \"सोच रहा हूँ…\" वाले बुलबुले और ऊपर के THINKING पर भी चलता है — जवाब आना शुरू होते ही रुक जाता है। \"बंद\" चुनें या ऊपर \"कम एनिमेशन\" करें तो वहाँ भी कुछ नहीं हिलता।")
        // 14 नवंबर 2026 (मालिक): DK कंप्यूटर + कोडिंग एजेंट का अलग चुनाव (डिफ़ॉल्ट छोटा कंप्यूटर)
        hint(box, "DK कंप्यूटर और कोडिंग एजेंट में (\"काम Running…\" वाला बुलबुला):")
        val curA = Prefs.typingStyleAgent(this)
        val prevA = DKWorkingIndicator(this).apply { bare = true; agent = true; state = WorkState.WORKING; typingPreview = curA }
        box.addView(prevA, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val ga = radios(box, TypingStyles.ALL, curA, 6500)
        ga.setOnCheckedChangeListener { _, id ->
            val v = TypingStyles.ALL.getOrNull(id - 6500)?.first ?: return@setOnCheckedChangeListener
            Prefs.edit(this) { putString("typing_style_agent", v) }
            prevA.typingPreview = v
        }
        hint(box, "हर जगह एक जैसा चाहिए तो ऊपर वाली सूची में भी \"छोटा कंप्यूटर\" चुन लें — तब चैट में भी वही दिखेगा।")
    }
    card("दिखावट पहले जैसी करें", "सिर्फ़ रंग-रूप शुरुआती जैसा — बातचीत, यादें, key और आवाज़ की सेटिंग नहीं मिटतीं", startOpen = true) { box ->
        row(box, button("दिखावट रीसेट करें") {
            UiDialog(this, Glass.DIALOG)
                .setTitle("दिखावट पहले जैसी करें?")
                .setMessage("एनिमेशन और बाक़ी दिखावट शुरुआती हो जाएगी। आपका कोई डेटा नहीं मिटेगा।")
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
    card("ओर्ब का आकार", "सब छोटे-छोटे 3D डॉट से बने हैं — जो पसंद हो चुनें। \"हालत के साथ\" वाले ORB सुनते, सोचते और बोलते समय अलग आकार और रंग लेते हैं (झलक में चारों बारी-बारी दिखते हैं)") { box ->
        val g = radios(box, styles, chosen, 2400)
        g.setOnCheckedChangeListener { _, id ->
            chosen = styles.getOrNull(id - 2400)?.first ?: "earth"
            Prefs.edit(this) { putString("orb_style", chosen) }
            preview.previewStyle = chosen
        }
    }
    card("किनारों की चमक", "DK सुन या बोल रहा हो तो स्क्रीन के किनारे चमकें") { box ->
        switch(box, "रंग-बिरंगी चमक — रंग एक के बाद एक, स्क्रीन के चारों किनारों और कोनों पर घूमते हैं", Prefs.edgeGlow(this))
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


// 15 नवंबर 2026 (मालिक: "Wallpaper section पूरी तरह हटाओ"): वॉलपेपर का कार्ड, चित्र, गैलरी-picker और download - सब हटा.
// पीछे सिर्फ़ थीम का सादा रंग. पुराना सेव "ui_wallpaper" मान चुपचाप अनदेखा (NeonTheme.LEGACY_WALL).

/** 14 नवंबर 2026: सेटिंग का डार्क / लाइट मोड (pure, JUnit) */
object SettingsThemeMode {
    const val TITLE = "डार्क / लाइट मोड"
    val ITEMS = listOf("dark" to "डार्क (गहरा)", "light" to "लाइट (उजला)")
    fun current(light: Boolean) = if (light) "light" else "dark"
    fun isLight(key: String) = key == "light"
}

/**
 * 23 नवंबर 2026 (मालिक): "होम कैरेक्टर" की जाली - हर टाइल पर आर्टवर्क का छोटा प्रीव्यू (असली पिक्सल, फ़िल्टर चालू) + नाम.
 * चुना टाइल accent से भरा (साझा कार्ड, selectable). पहली टाइल = ORB. टैप = तुरंत सेव, होम लौटते ही वही.
 */
internal fun SettingsActivity.homeCharacterGrid(box: LinearLayout) {
    val H = com.dk.assistant.dkai.HomeAvatars
    val items = listOf<com.dk.assistant.dkai.HomeAvatars.Avatar?>(null) + H.list(this)
    val cols = if (com.dk.assistant.UiFitRules.isTablet(resources.configuration.smallestScreenWidthDp)) 4 else 3
    val tiles = ArrayList<Pair<String, LinearLayout>>()
    fun paint(cur: String) { for ((k, v) in tiles) v.isSelected = k == cur }
    var row: LinearLayout? = null
    items.forEachIndexed { i, a ->
        if (i % cols == 0) {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
        }
        val key = if (a == null) H.ORB else H.prefFor(a.id)
        val name = a?.name ?: "ORB"
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = com.dk.assistant.ui.Clay.card(this@homeCharacterGrid, selectable = true)
            val p = (6 * dp).toInt(); setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            contentDescription = UiLang.s("होम कैरेक्टर: ") + name
        }
        val pic = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val bmp = a?.let { H.thumb(this@homeCharacterGrid, it) }?.let { raw -> a.badge?.let { badgeThumb(raw, it, a.subject) } ?: raw }
            if (bmp != null) setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = true })
            else setImageResource(R.drawable.ic_dk)
            if (bmp == null) { val ip = (14 * dp).toInt(); setPadding(ip, ip, ip, ip) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 14 * dp) }
            }
        }
        tile.addView(pic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()).apply { val m = (2 * dp).toInt(); setMargins(m, m, m, 0) })
        tile.addView(TextView(this).apply {
            text = UiLang.s(name); textSize = 11f; gravity = Gravity.CENTER; maxLines = 2
            setTextColor(com.dk.assistant.ui.ClayTheme.t.text); setPadding(0, (4 * dp).toInt(), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tile.setOnClickListener {
            if (a == null) H.chooseOrb(this) else H.chooseAvatar(this, a.id)
            paint(key)
            if (a != null && a.res == null && !H.downloaded(this, a)) {
                toast("अवतार डाउनलोड हो रहा है…")
                H.fetch(this, a.id) { ok -> if (!isFinishing) toast(if (ok) "अवतार तैयार - होम पर दिखेगा" else "अवतार नहीं आ पाया - इंटरनेट चालू होने पर फिर आएगा, तब तक ORB") }
            } else toast(UiLang.s("होम कैरेक्टर: ") + name)
        }
        tiles.add(key to tile)
        row?.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            val m = (4 * dp).toInt(); leftMargin = m; rightMargin = m
        })
    }
    // आख़िरी पंक्ति अधूरी हो तो खाली जगह (टाइलें बराबर चौड़ी रहें)
    val left = (cols - items.size % cols) % cols
    repeat(left) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { val m = (4 * dp).toInt(); leftMargin = m; rightMargin = m }) }
    paint(H.choice(this))
}

/** प्रीव्यू पर भी ऑर्ब के बीच वही "DK" (होम जैसा) - छोटी तस्वीर को फ़िल्टर के साथ 4x करके उस पर लिखा */
private fun badgeThumb(raw: android.graphics.Bitmap, txt: String, subject: List<Float>): android.graphics.Bitmap = try {
    val b = android.graphics.Bitmap.createScaledBitmap(raw, raw.width * 4, raw.height * 4, true).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF0FFFFFF.toInt(); textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = minOf(b.width, b.height) * 0.16f
        setShadowLayer(textSize * 0.45f, 0f, 0f, 0xFF4FA3FF.toInt())
    }
    val cx = (subject.getOrElse(0) { 0f } + subject.getOrElse(2) { 1f }) / 2f
    android.graphics.Canvas(b).drawText(txt, b.width * cx, b.height * com.dk.assistant.dkai.HomeAvatars.BADGE_Y + p.textSize * 0.35f, p)
    b
} catch (e: Throwable) { com.dk.assistant.Quiet.note(e); raw }
