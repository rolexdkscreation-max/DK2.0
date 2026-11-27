package com.dk.assistant

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
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

    card("आवाज़ से फ़ोन लॉक / अनलॉक", "बोलते ही — बिना AI, बिना इंटरनेट, सीधे फ़ोन पर") { box ->
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
        // 22 नवंबर 2026 (मालिक): "लॉक-अनलॉक में एक ऑप्शन - सिर्फ़ मालिक की आवाज़ पर; बंद हो तो कोई भी बोले".
        // आवाज़ की पहचान सिर्फ़ वॉइस गार्डियन से (एक बार सिखाई) - यहाँ अलग से रिकॉर्ड करके सिखाने वाला हिस्सा हटा
        val swOwner = switch(box, OWNER_ONLY_LABEL, PhoneLockStore.ownerOnly(this))
        swOwner.setOnCheckedChangeListener { _, on -> PhoneLockStore.setOwnerOnly(this, on) }
        hint(box, "चालू = सिर्फ़ आपकी आवाज़ (Settings → वॉइस गार्डियन में एक बार सिखाई हुई) पर फ़ोन लॉक / अनलॉक होगा। " +
            "बंद = पास में कोई भी \"डीके फोन अनलॉक करो\" बोले तो खुल जाएगा। बोलते ही स्क्रीन जगती है, इंटरनेट हो या न हो।")
        hint(box, "ध्यान दें: PIN फ़ोन से बाहर कहीं नहीं जाता " +
            "(न इंटरनेट, न AI)। फ़ोन का PIN बदलें तो यहाँ भी नया सेव करें। लगातार ${PhoneLockStore.MAX_FAILS} बार न खुले तो DK अपने-आप रुक जाता है।")
        // 16 नवंबर 2026 (ऑडिट G1): आवाज़ से अनलॉक की सुरक्षा साफ़-साफ़
        if (!com.dk.assistant.voice.VoiceGuardian.ready(this)) hint(box, "⚠ \"$OWNER_ONLY_LABEL\" चालू है तो पहले Settings → वॉइस गार्डियन में अपनी आवाज़ सिखाइए — उसके बिना DK PIN नहीं डालेगा।")
        hint(box, UNLOCK_SAFETY_NOTE)
        // 21 नवंबर 2026 (मालिक): हर फ़ोन में PIN वाला हिस्सा अलग दूरी पर आता है - DK कितना ऊपर स्लाइड करे
        label(box, "अनलॉक: लॉक स्क्रीन पर कितना ऊपर स्लाइड करे")
        val upTxt = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        val upBar = SeekBar(this).apply {
            max = (UnlockSwipe.MAX - UnlockSwipe.MIN) / UnlockSwipe.STEP
            progress = (UnlockSwipe.pct(this@secPhoneLock) - UnlockSwipe.MIN) / UnlockSwipe.STEP
        }
        box.addView(upBar); box.addView(upTxt)
        upTxt.text = UiLang.s(UnlockSwipe.label(UnlockSwipe.pct(this)))
        upBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pct = UnlockSwipe.MIN + p * UnlockSwipe.STEP
                UnlockSwipe.setPct(this@secPhoneLock, pct); upTxt.text = UiLang.s(UnlockSwipe.label(pct))
            }
            override fun onStartTrackingTouch(b: SeekBar?) { // ज़रूरत नहीं
            }
            override fun onStopTrackingTouch(b: SeekBar?) { // ज़रूरत नहीं
            }
        })
        switch(box, "PIN न आए तो अगली बार थोड़ा और ऊपर स्लाइड करो", UnlockSwipe.more(this))
            .setOnCheckedChangeListener { _, on -> UnlockSwipe.setMore(this, on) }
        hint(box, "जिस फ़ोन में थोड़ा-सा ऊपर करने पर PIN आ जाता है वहाँ कम रखें; जहाँ पूरा खींचना पड़ता है वहाँ ज़्यादा. " +
            "\"और ऊपर\" चालू = पहली बार PIN न आए तो हर अगली कोशिश ${UnlockSwipe.STEP_UP}% ज़्यादा (${PhoneUnlocker.SWIPE_TRIES} कोशिश तक).")
        swUnlock.setOnCheckedChangeListener { _, on ->
            txtSaved?.text = "बदलाव सेव नहीं हुए — सेव दबाएँ"
            if (on && PhoneLockStore.hasSecret(this) && !PhoneLockStore.unlockOn(this)) {
                AlertDialog.Builder(this).setTitle(UiLang.s("आवाज़ से अनलॉक — ध्यान दें")).setMessage(UiLang.s(UNLOCK_SAFETY_NOTE))
                    .setPositiveButton(UiLang.s("समझ गया, चालू करो"), null)
                    .setNegativeButton(UiLang.s("बंद रखो")) { _, _ -> swUnlock.isChecked = false }
                    .setCancelable(false).show()
            }
            if (on && !PhoneLockStore.hasSecret(this)) askPhoneSecret { ok -> refresh(); if (!ok) swUnlock.isChecked = false else plSave?.invoke() }
        }
    }

    // v4.6 मालिक: "लॉक-अनलॉक में टेक्स्ट हटाकर वॉइस रिकॉर्डिंग वाला डालना" - नाम/वाक्य लिखने वाला खाना हटाया.
    // 22 नवंबर 2026 (मालिक): अलग-अलग 30 सेकंड रिकॉर्ड करके सिखाने वाला हिस्सा भी यहाँ से हटा - आवाज़ सिर्फ़ वॉइस गार्डियन से,
    // वाक्य आम सुनने से ("डीके फोन अनलॉक करो"). पहले लिखे नाम/वाक्य मिटते नहीं - वही (और DK के नाम) पीछे से चलते रहते हैं.

    plSave = {
        PhoneLockStore.saveSettings(this, swLock.isChecked, swUnlock.isChecked,
            PhoneLockStore.names(this), PhoneLockStore.lockWords(this), PhoneLockStore.unlockWords(this))
        if (swUnlock.isChecked && !PhoneLockStore.hasSecret(this)) swUnlock.isChecked = false
    }
}

/** 22 नवंबर 2026: लॉक / अनलॉक सिर्फ़ मालिक की आवाज़ पर (वॉइस गार्डियन) - switch का नाम */
internal const val OWNER_ONLY_LABEL = "सिर्फ़ मालिक की आवाज़ से लॉक / अनलॉक (वॉइस गार्डियन)"

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

/** 16 नवंबर 2026: आवाज़ से अनलॉक की सच्ची सुरक्षा (Settings में और चालू करते समय) */
internal const val UNLOCK_SAFETY_NOTE = "सुरक्षा: DK PIN तभी डालेगा जब आपने खुद \"अनलॉक\" कहा हो और उसी बात की आवाज़ कड़ी जाँच में आपकी निकले " +
    "(वॉइस गार्डियन)। AI या स्क्रीन / नोटिफ़िकेशन की लिखावट से अनलॉक नहीं होगा। पुरानी रिकॉर्डिंग हूबहू बजाने पर रोक, और 3 बार गलत आवाज़ = 30 मिनट रोक। " +
    "फिर भी आवाज़ PIN जितनी पक्की नहीं — कोई आपकी आवाज़ की अच्छी नकल या रिकॉर्डिंग कर सकता है। ज़रूरत न हो तो यह बंद रखें।"
