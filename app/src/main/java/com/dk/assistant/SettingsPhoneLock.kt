package com.dk.assistant

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/**
 * नवंबर 2026: Settings → फ़ोन लॉक / अनलॉक।
 * मालिक खुद तय करे: किस नाम से बुलाने पर (DK, मायरा, जारवी, श्री, Google …) और कौन-सा वाक्य बोलने पर फ़ोन लॉक / अनलॉक हो।
 * अनलॉक चालू करते ही फ़ोन का PIN / पैटर्न / पासवर्ड एक बार डालने का खाना खुलता है - फिर DK खुद डालता है।
 */
internal fun SettingsActivity.secPhoneLock() {
    lateinit var swLock: SwitchCompat
    lateinit var swUnlock: SwitchCompat
    lateinit var status: TextView

    fun refresh() {
        val k = PhoneLockStore.kind(this)
        status.text = when {
            !PhoneLockStore.hasSecret(this) -> "फ़ोन का PIN / पैटर्न अभी सेव नहीं है"
            PhoneLockStore.fails(this) >= PhoneLockStore.MAX_FAILS ->
                "⚠ ${k?.hi} से फ़ोन ${PhoneLockStore.MAX_FAILS} बार नहीं खुला — DK ने डालना रोका है। नया ${k?.hi} सेव करें"
            else -> "✓ फ़ोन का ${k?.hi} सेव है (फ़ोन के अंदर ताले में)"
        }
    }

    card("आवाज़ से फ़ोन लॉक / अनलॉक", "अपनी आवाज़ रिकॉर्ड करके — बिना AI, बिना इंटरनेट, सीधे फ़ोन पर", startOpen = true) { box ->
        if (!Setup.accessibilityOn(this)) hint(box, "⚠ पहले Settings → Accessibility में DK चालू करें — उसके बिना फ़ोन लॉक / अनलॉक नहीं होगा।")
        swLock = switch(box, "आवाज़ से फ़ोन लॉक करो", PhoneLockStore.lockOn(this))
        swUnlock = switch(box, "आवाज़ से फ़ोन अनलॉक करो (PIN / पैटर्न DK डालेगा)", PhoneLockStore.unlockOn(this))
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
        }
        box.addView(status)
        refresh()
        row(box,
            button("PIN / पैटर्न डालें") { askPhoneSecret { ok -> refresh(); if (ok) { swUnlock.isChecked = true; plSave?.invoke() } } },
            button("हटाएँ") {
                PhoneLockStore.clearSecret(this); swUnlock.isChecked = false; refresh()
                toast("फ़ोन का PIN / पैटर्न DK से हटा दिया")
            })
        hint(box, "लॉक और अनलॉक का वाक्य नीचे \"आवाज़ रिकॉर्ड करके सिखाएँ\" में अलग-अलग 30 सेकंड रिकॉर्ड करें — उसी आवाज़ से फ़ोन लॉक / अनलॉक होगा, इंटरनेट हो या न हो।")
        hint(box, "ध्यान दें: PIN फ़ोन से बाहर कहीं नहीं जाता " +
            "(न इंटरनेट, न AI)। फ़ोन का PIN बदलें तो यहाँ भी नया सेव करें। लगातार ${PhoneLockStore.MAX_FAILS} बार न खुले तो DK अपने-आप रुक जाता है।")
        swUnlock.setOnCheckedChangeListener { _, on ->
            txtSaved.text = "बदलाव सेव नहीं हुए — सेव दबाएँ"
            if (on && !PhoneLockStore.hasSecret(this)) askPhoneSecret { ok -> refresh(); if (!ok) swUnlock.isChecked = false else plSave?.invoke() }
        }
    }

    // v4.6 मालिक: "लॉक-अनलॉक में टेक्स्ट हटाकर वॉइस रिकॉर्डिंग वाला डालना" - नाम/वाक्य लिखने वाला खाना हटाया.
    // अब नीचे "आवाज़ रिकॉर्ड करके सिखाएँ" में लॉक और अनलॉक की अलग-अलग 30 सेकंड की रिकॉर्डिंग (वाक्य 5 बार).
    // पहले लिखे नाम/वाक्य मिटते नहीं - रिकॉर्डिंग न हो तब तक वही (और DK के नाम) पीछे से चलते रहते हैं.

    plSave = {
        PhoneLockStore.saveSettings(this, swLock.isChecked, swUnlock.isChecked,
            PhoneLockStore.names(this), PhoneLockStore.lockWords(this), PhoneLockStore.unlockWords(this))
        if (swUnlock.isChecked && !PhoneLockStore.hasSecret(this)) swUnlock.isChecked = false
    }
}

/**
 * फ़ोन का PIN / पैटर्न / पासवर्ड एक बार डालने का खाना (दो बार - पक्का करने के लिए)।
 * done(true) = ताले में सेव हुआ।
 */
internal fun SettingsActivity.askPhoneSecret(done: (Boolean) -> Unit) {
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
    val kinds = PhoneLockVoice.Kind.values()
    val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
    kinds.forEachIndexed { i, k -> rg.addView(RadioButton(this).apply { id = 7100 + i; text = k.hi }) }
    rg.check(7100 + kinds.indexOf(PhoneLockStore.kind(this) ?: PhoneLockVoice.Kind.PIN))
    root.addView(rg)
    val info = TextView(this).apply { textSize = 13f; setPadding(0, pad / 2, 0, pad / 2) }
    root.addView(info)
    val e1 = EditText(this).apply { isSingleLine = true }
    val e2 = EditText(this).apply { isSingleLine = true }
    root.addView(e1); root.addView(e2)
    val pp = PatternPadView(this).apply { setBackgroundColor(0xFF1B2030.toInt()) }
    root.addView(pp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
    var first: String? = null

    fun kind() = kinds.getOrElse(rg.checkedRadioButtonId - 7100) { PhoneLockVoice.Kind.PIN }
    fun show() {
        val k = kind(); first = null; pp.clear(); e1.setText(""); e2.setText("")
        val pin = k == PhoneLockVoice.Kind.PIN
        val text = k == PhoneLockVoice.Kind.PIN || k == PhoneLockVoice.Kind.PASSWORD
        e1.visibility = if (text) View.VISIBLE else View.GONE; e2.visibility = e1.visibility
        pp.visibility = if (text) View.GONE else View.VISIBLE
        val type = if (pin) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e1.inputType = type; e2.inputType = type
        e1.hint = "फ़ोन का ${k.hi}"; e2.hint = "वही ${k.hi} दोबारा"
        info.text = when (k) {
            PhoneLockVoice.Kind.PIN -> "वही PIN डालिए जो फ़ोन की लॉक स्क्रीन पर डालते हैं (4–16 अंक)"
            PhoneLockVoice.Kind.PASSWORD -> "वही पासवर्ड डालिए जो लॉक स्क्रीन पर डालते हैं"
            PhoneLockVoice.Kind.PATTERN -> "लॉक स्क्रीन वाला पैटर्न यहाँ बनाइए (कम से कम 4 बिंदु)"
        }
    }
    rg.setOnCheckedChangeListener { _, _ -> show() }
    show()

    val d = AlertDialog.Builder(this).setTitle(UiLang.s("फ़ोन का PIN / पैटर्न / पासवर्ड")).setView(root)
        .setPositiveButton(UiLang.s("सेव"), null).setNegativeButton(UiLang.s("रद्द"), null).create()
    var saved = false
    fun save(k: PhoneLockVoice.Kind, s: String) {
        if (!PhoneLockStore.saveSecret(this, k, s)) {
            info.text = "⚠ इस फ़ोन में सुरक्षित ताला (Keystore) नहीं चला — ${k.hi} सेव नहीं किया"; return
        }
        saved = true; toast("फ़ोन का ${k.hi} ताले में सेव हो गया"); d.dismiss()
    }
    pp.onDone = { s ->
        when {
            s.length < 4 -> { info.text = "कम से कम 4 बिंदु जोड़िए"; pp.clear() }
            first == null -> { first = s; info.text = "अब वही पैटर्न दोबारा बनाइए"; pp.clear() }
            first != s -> { first = null; info.text = "दोनों पैटर्न अलग थे — फिर से बनाइए"; pp.clear() }
            else -> info.text = "✓ पैटर्न पक्का — सेव दबाइए"
        }
    }
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val k = kind()
            if (k == PhoneLockVoice.Kind.PATTERN) {
                val s = pp.pattern
                if (first == null || first != s || !PhoneLockVoice.validPattern(s)) { info.text = "पैटर्न दो बार एक जैसा बनाइए"; return@setOnClickListener }
                save(k, s)
            } else {
                val a = e1.text.toString(); val b = e2.text.toString()
                when {
                    !PhoneLockVoice.valid(k, a) -> info.text = if (k == PhoneLockVoice.Kind.PIN) "PIN में 4–16 अंक होने चाहिए" else "पासवर्ड कम से कम 4 अक्षर"
                    a != b -> info.text = "दोनों खानों में एक जैसा ${k.hi} डालिए"
                    else -> save(k, a)
                }
            }
        }
    }
    d.setOnDismissListener { done(saved) }
    d.show()
}
