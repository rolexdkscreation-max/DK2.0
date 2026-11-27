package com.dk.assistant

import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 21 नवंबर 2026 (मालिक): Settings → सुरक्षा → ऐप लॉक. DK खोलते समय और चुने हुए किसी भी ऐप पर ताला.
 * PIN / पैटर्न सिर्फ़ ● बिंदु में डलते हैं, सेव होने के बाद कभी नहीं दिखते ("सेट है ●●●●").
 * बंद करना / PIN बदलना = पहले पुराना PIN (कोई और Settings खोलकर ताला न हटा दे).
 */
internal fun SettingsActivity.secAppLock() {
    lateinit var state: TextView

    fun refresh() {
        state.text = UiLang.s(buildString {
            append(if (AppLockStore.on(this@secAppLock)) "✓ ऐप लॉक चालू" else "✗ ऐप लॉक बंद")
            append(" • PIN: ").append(if (AppLockStore.hasPin(this@secAppLock)) "सेट है ●●●●" else "सेट नहीं")
            append(" • पैटर्न: ").append(if (AppLockStore.hasPattern(this@secAppLock)) "सेट है" else "नहीं")
            append(" • ${AppLockStore.apps(this@secAppLock).size} ऐप")
            if (AppLockStore.dkSelf(this@secAppLock)) append(" + DK खुद")
        })
    }

    card("ऐप लॉक", "DK और कोई भी ऐप — PIN • पैटर्न • फ़िंगरप्रिंट / फ़ेस • DK फ़ेस स्कैन") { box ->
        if (!Setup.accessibilityOn(this)) hint(box, "⚠ पहले Settings → Accessibility में DK चालू करें — उसके बिना ऐप लॉक नहीं चलेगा (ऐप खुलना DK वहीं से देखता है)।")
        state = TextView(this).apply { textSize = 13f; setTextColor(com.dk.assistant.ui.ClayTheme.t.sub); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0) }
        box.addView(state)
        val sw = switch(box, "ऐप लॉक चालू", AppLockStore.on(this))
        var quiet = false
        sw.setOnCheckedChangeListener { _, on ->
            if (quiet) return@setOnCheckedChangeListener
            when {
                on && !AppLockStore.hasPin(this) -> {
                    quiet = true; sw.isChecked = false; quiet = false
                    setPinFlow { AppLockStore.setOn(this, true); quiet = true; sw.isChecked = true; quiet = false; refresh(); toast("ऐप लॉक चालू") }
                }
                on -> { AppLockStore.setOn(this, true); refresh(); toast("ऐप लॉक चालू") }
                else -> {
                    quiet = true; sw.isChecked = true; quiet = false
                    askOldPin("ऐप लॉक बंद करने के लिए DK सीक्रेट PIN") {
                        AppLockStore.setOn(this, false); quiet = true; sw.isChecked = false; quiet = false; refresh(); toast("ऐप लॉक बंद")
                    }
                }
            }
        }
        refresh()
        hint(box, "ताले वाला ऐप खुलते ही ऊपर DK का futuristic ताला आता है — सही PIN / पैटर्न / फ़िंगरप्रिंट / चेहरा = ऐप खुले, back = होम पर। ${AppLockRules.FREE_TRIES} बार गलत = कुछ देर रुकना पड़ेगा।")
        hint(box, "टिप: फ़ोन की Settings ऐप पर भी ताला लगा दें — कोई DK की Accessibility बंद करके ताला न हटा सके।")
        row(box, button("ताला आज़माएँ") {
            if (!AppLockStore.hasPin(this)) toast("पहले DK सीक्रेट PIN सेट करें")
            else startActivity(Intent(this, AppLockActivity::class.java).putExtra(AppLockActivity.EXTRA_PKG, packageName))
        })
    }

    card("DK सीक्रेट PIN और पैटर्न", "ताला खोलने का अपना PIN — टच मोड का सायरन भी इसी से बंद") { box ->
        hint(box, "PIN ${AppLockRules.PIN_MIN}–${AppLockRules.PIN_MAX} अंक का (1111 / 1234 जैसा आसान नहीं)। सेव होने के बाद कहीं नहीं दिखता — सिर्फ़ \"सेट है ●●●●\"।")
        row(box,
            button(if (AppLockStore.hasPin(this)) "PIN बदलें" else "PIN सेट करें") { setPinFlow { refresh(); toast("DK सीक्रेट PIN सेव हो गया") } },
            button(if (AppLockStore.hasPattern(this)) "पैटर्न बदलें" else "पैटर्न सेट करें") {
                if (!AppLockStore.hasPin(this)) toast("पहले PIN सेट करें") else askOldPin("पैटर्न के लिए पहले DK सीक्रेट PIN") { setPatternFlow { refresh() } }
            })
        if (AppLockStore.hasPattern(this)) row(box, button("पैटर्न हटाएँ") {
            askOldPin("पैटर्न हटाने के लिए DK सीक्रेट PIN") { AppLockStore.clearPattern(this); refresh(); toast("पैटर्न हटाया") }
        })
    }

    card("ताला खोलने का तरीका", "पहले कौन सा दिखे — बाकी तरीके ताले की स्क्रीन पर नीचे") { box ->
        val ms = AppLockRules.Method.values().toList()
        radios(box, ms.map { it.id to it.hi }, AppLockStore.method(this).id, 7600)
            .setOnCheckedChangeListener { _, id ->
                val m = ms.getOrNull(id - 7600) ?: return@setOnCheckedChangeListener
                AppLockStore.setMethod(this, m)
                when {
                    m == AppLockRules.Method.PATTERN && !AppLockStore.hasPattern(this) -> toast("पैटर्न अभी सेट नहीं — ऊपर \"पैटर्न सेट करें\"")
                    m == AppLockRules.Method.FACE && !(FaceAI.enrolled(this) && FaceAI.ready(this)) -> toast("DK फ़ेस के लिए पहले सुरक्षा → चेहरा पहचान में अपना चेहरा सेट करें")
                    m == AppLockRules.Method.PHONE && !Guard.secure(this) -> toast("फ़ोन में स्क्रीन लॉक (PIN / फ़िंगरप्रिंट) सेट नहीं")
                    else -> toast("पहले दिखेगा: ${m.hi}")
                }
            }
        hint(box, "फ़ोन का फ़िंगरप्रिंट / फ़ेस = Android का अपना सुरक्षित पैनल (फ़ोन में जो हो — फ़िंगरप्रिंट, नहीं तो फ़ेस, नहीं तो फ़ोन का PIN)। DK फ़ेस स्कैन = होलोग्राम स्कैनर, सिर घुमाकर + AI anti-spoof — फ़ोटो / वीडियो से नहीं खुलता।")
    }

    card("किन ऐप पर ताला", "DK खुद • WhatsApp • गैलरी • कोई भी ऐप") { box ->
        val swDk = switch(box, "DK खोलते समय ताला (DK का मुख्य पन्ना, चैट, Settings…)", AppLockStore.dkSelf(this))
        swDk.setOnCheckedChangeListener { _, v -> AppLockStore.setDkSelf(this, v); refresh() }
        appPicker(box) { refresh() }
    }

    card("फिर से ताला कब", "ऐप छोड़ने के बाद लौटें तो") { box ->
        val opts = AppLockRules.RELOCK
        radios(box, opts.map { it.first.toString() to it.second }, AppLockStore.grace(this).toString(), 7700)
            .setOnCheckedChangeListener { _, id ->
                opts.getOrNull(id - 7700)?.let { (ms, t) -> AppLockStore.setGrace(this, ms); AppLock.session.graceMs = ms; toast("फिर से ताला: $t") }
            }
        hint(box, "स्क्रीन बंद होते ही सारे ऐप फिर से ताले में।")
    }
}

/** लॉन्चर वाले सारे ऐप - खोजें, टिक करें (तुरंत सेव) */
private fun SettingsActivity.appPicker(box: LinearLayout, changed: () -> Unit) {
    val search = EditText(this).apply {
        hint = UiLang.s("ऐप खोजें…"); setBackgroundResource(R.drawable.glass_input)
        setTextColor(com.dk.assistant.ui.ClayTheme.t.text); setHintTextColor(com.dk.assistant.ui.ClayTheme.t.hint); textSize = 14f; isSingleLine = true
        val p = (10 * dp).toInt(); setPadding(p + 4, p, p, p)
    }
    box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(list)
    val wait = TextView(this).apply { text = UiLang.s("ऐप की सूची बन रही है…"); setTextColor(com.dk.assistant.ui.ClayTheme.t.gray); textSize = 13f }
    list.addView(wait)
    data class App(val pkg: String, val name: String, val icon: Drawable?)
    Thread {
        val pm = packageManager
        val apps = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != packageName }
                .map { p -> App(p, try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p },
                    try { pm.getApplicationIcon(p) } catch (_: Exception) { null }) }
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) { Quiet.note(e); emptyList() }
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            list.removeView(wait)
            val rows = apps.map { a ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
                r.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
                val cb = CheckBox(this).apply {
                    text = a.name; textSize = 14f; setTextColor(com.dk.assistant.ui.ClayTheme.t.text); isChecked = a.pkg in AppLockStore.apps(this@appPicker)
                    setOnCheckedChangeListener { _, v ->
                        val s = AppLockStore.apps(this@appPicker).toMutableSet()
                        if (v) s.add(a.pkg) else s.remove(a.pkg)
                        AppLockStore.setApps(this@appPicker, s); changed()
                    }
                }
                r.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(r)
                a to r
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim()?.lowercase().orEmpty()
                    for ((a, r) in rows) r.visibility = if (q.isEmpty() || a.name.lowercase().contains(q) || a.pkg.contains(q)) android.view.View.VISIBLE else android.view.View.GONE
                }
            })
        }
    }.apply { isDaemon = true }.start()
}

/** PIN पैड वाला dialog (● बिंदु) */
private fun SettingsActivity.pinDialog(title: String, autoLen: Int = 0, onPin: (String, AlertDialog, TextView, NeonPinPad) -> Unit) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(com.dk.assistant.ui.ClayTheme.t.surface); val p = (16 * dp).toInt(); setPadding(p, p, p, p)
    }
    val msg = TextView(this).apply { text = UiLang.s(title); setTextColor(com.dk.assistant.ui.ClayTheme.t.sub); textSize = 15f; gravity = Gravity.CENTER }
    box.addView(msg)
    val pad = NeonPinPad(this, autoLen = autoLen)
    box.addView(pad, LinearLayout.LayoutParams((280 * dp).toInt(), -2))
    val dlg = AlertDialog.Builder(this).setView(box).setNegativeButton(UiLang.s("रद्द करें"), null).create()
    pad.onDone = { p -> onPin(p, dlg, msg, pad) }
    dlg.show()
}

/** पुराना PIN पक्का करो (गलत कोशिशों की सीमा वही) */
private fun SettingsActivity.askOldPin(title: String, ok: () -> Unit) {
    if (!AppLockStore.hasPin(this)) { ok(); return }
    pinDialog(title, autoLen = AppLockStore.pinLen(this)) { p, dlg, msg, pad ->
        val w = AppLockStore.waitLeft(this)
        when {
            w > 0 -> msg.text = UiLang.s("बहुत बार गलत — ${AppLockRules.waitLabel(w)} बाद")
            AppLockStore.checkPin(this, p) -> { dlg.dismiss(); ok() }
            else -> { msg.text = UiLang.s("✗ गलत PIN"); pad.reject() }
        }
    }
}

/** नया PIN: (पुराना PIN) -> नया -> दोबारा */
private fun SettingsActivity.setPinFlow(done: () -> Unit) {
    askOldPin("पहले अभी वाला DK सीक्रेट PIN") {
        var first = ""
        pinDialog("नया DK सीक्रेट PIN डालें (${AppLockRules.PIN_MIN}–${AppLockRules.PIN_MAX} अंक), फिर OK") { p, dlg, msg, _ ->
            when {
                first.isEmpty() && !AppLockRules.pinOk(p) -> msg.text = UiLang.s("${AppLockRules.PIN_MIN}–${AppLockRules.PIN_MAX} अंक का PIN डालें")
                first.isEmpty() && AppLockRules.weakPin(p) -> msg.text = UiLang.s("बहुत आसान PIN (1111 / 1234 जैसा) — दूसरा डालें")
                first.isEmpty() -> { first = p; msg.text = UiLang.s("वही PIN दोबारा डालें") }
                p != first -> { first = ""; msg.text = UiLang.s("✗ दोनों PIN अलग — फिर से नया PIN डालें") }
                else -> { AppLockStore.setPin(this, p); AppLockStore.success(this); dlg.dismiss(); done() }
            }
        }
    }
}

/** नया पैटर्न: बनाएँ -> दोबारा */
private fun SettingsActivity.setPatternFlow(done: () -> Unit) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(com.dk.assistant.ui.ClayTheme.t.surface); val p = (16 * dp).toInt(); setPadding(p, p, p, p)
    }
    val msg = TextView(this).apply { text = UiLang.s("नया पैटर्न बनाइए (कम से कम ${AppLockRules.PATTERN_MIN} बिंदु)"); setTextColor(com.dk.assistant.ui.ClayTheme.t.sub); textSize = 15f; gravity = Gravity.CENTER }
    box.addView(msg)
    val pv = NeonPattern(this)
    box.addView(pv, LinearLayout.LayoutParams((260 * dp).toInt(), (260 * dp).toInt()))
    val dlg = AlertDialog.Builder(this).setView(box).setNegativeButton(UiLang.s("रद्द करें"), null).create()
    var first: List<Int> = emptyList()
    pv.onDone = { dots ->
        when {
            first.isEmpty() && !AppLockRules.patternOk(dots) -> { msg.text = UiLang.s("कम से कम ${AppLockRules.PATTERN_MIN} बिंदु जोड़िए"); pv.postDelayed({ pv.clear() }, 500) }
            first.isEmpty() -> { first = dots; msg.text = UiLang.s("वही पैटर्न दोबारा बनाइए"); pv.postDelayed({ pv.clear() }, 400) }
            dots != first -> { first = emptyList(); msg.text = UiLang.s("✗ दोनों पैटर्न अलग — फिर से बनाइए"); pv.postDelayed({ pv.clear() }, 500) }
            else -> { AppLockStore.setPattern(this, dots); dlg.dismiss(); toast("DK पैटर्न सेव हो गया"); done() }
        }
    }
    dlg.show()
}

/**
 * 21 नवंबर 2026 (मालिक): टच मोड में - चेतावनी स्क्रीन पर DK सीक्रेट PIN से बंद, गलत PIN = तुरंत फिर लॉक,
 * सायरन बजते फ़ोन खुला (सायरन बंद नहीं किया) = 1.5 सेकंड में फिर लॉक. [secGuard] में.
 */
internal fun SettingsActivity.guardPinCard() {
    card("सीक्रेट PIN और फिर से लॉक", "चेतावनी स्क्रीन पर DK सीक्रेट PIN • गलत PIN = तुरंत लॉक • सायरन में फ़ोन खुला = फिर लॉक") { box ->
        val st = TextView(this).apply { textSize = 13f; setTextColor(com.dk.assistant.ui.ClayTheme.t.sub); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0) }
        fun show() { st.text = UiLang.s("DK सीक्रेट PIN: " + if (AppLockStore.hasPin(this)) "सेट है ●●●●" else "सेट नहीं") }
        box.addView(st); show()
        row(box, button(if (AppLockStore.hasPin(this)) "PIN बदलें" else "PIN सेट करें") { setPinFlow { show(); toast("DK सीक्रेट PIN सेव हो गया") } })
        hint(box, "सायरन बजे तो चेतावनी स्क्रीन पर \"DK सीक्रेट PIN डालें\" दबाएँ — PIN ● बिंदु में दिखेगा, सही = टच मोड बंद। यही PIN ऐप लॉक में भी चलता है।")
        val sw = switch(box, "सायरन बजते समय कोई फ़ोन खोल ले (और सायरन बंद न करे) = 1.5 सेकंड में फिर से लॉक", Guard.relockOn(this))
        sw.setOnCheckedChangeListener { _, v -> Prefs.edit(this) { putBoolean(Guard.P_RELOCK, v) } }
        hint(box, "यह तभी चलता है जब बंद करने का दूसरा रास्ता हो — मालिक का चेहरा, गुप्त शब्द या DK सीक्रेट PIN (वरना आप फँस न जाएँ)।")
        hint(box, "टच मोड में लॉक स्क्रीन पर कोई गलत PIN / पैटर्न डाले = फ़ोन तुरंत फिर लॉक, फिर से स्क्रीन जलाकर खोलना पड़ेगा (और सायरन)।")
    }
}
