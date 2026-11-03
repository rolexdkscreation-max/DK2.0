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

// SettingsActivity का हिस्सा: भाषा, डिबग, अपडेट, प्रोफ़ाइल, access key, जानकारी, Accessibility और अनुमतियाँ.
// यह code पहले SettingsActivity.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state SettingsActivity में)।

/**
 * भाषा: (1) ऐप की स्क्रीनों की भाषा - English (शुरुआती) / हिंदी, पूरे ऐप के बटन, Settings, संदेश, सूचनाएँ;
 * (2) DK के जवाब की भाषा (v3.30) - Hinglish / हिंदी / English, पहले जैसी.
 */
internal fun SettingsActivity.secLang() {
    card("ऐप की भाषा (स्क्रीन)", "पूरे ऐप के बटन, Settings, संदेश — English या हिंदी") { box ->
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ui = listOf(UiLang.EN to "English (default)", UiLang.HI to "हिंदी (Hindi)")
        ui.forEachIndexed { i, (code, label) ->
            rg.addView(RadioButton(this).apply {
                id = 3100 + i; text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                isChecked = code == UiLang.code
                setPadding((6 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                UiLang.keep(this)     // भाषा के नाम हर भाषा में वैसे ही
            })
        }
        rg.setOnCheckedChangeListener { _, idc ->
            val code = ui.getOrNull(idc - 3100)?.first ?: return@setOnCheckedChangeListener
            if (code == UiLang.code) return@setOnCheckedChangeListener
            UiLang.set(this, code)
            toast(if (code == UiLang.EN) "App language: English" else "ऐप की भाषा: हिंदी")
            // यह screen अभी नई बने; पीछे खुली screens लौटते ही अपने-आप नई भाषा में
            recreate()
        }
        box.addView(rg)
        hint(box, "चुनते ही पूरे ऐप की स्क्रीनें इसी भाषा में। DK के जवाब की भाषा नीचे अलग से चुनें। बातचीत, कोड और कहानियाँ जैसी लिखी हैं वैसी ही दिखती हैं।")
    }
    card("DK किस भाषा में जवाब दे", "DK लाइव, DK चैट, बिना-Live आवाज़, DK कंप्यूटर और कोडिंग एजेंट — सब पर एक साथ") { box ->
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cur = Prefs.replyLang(this)
        Lang.CHOICES.forEachIndexed { i, (code, label) ->
            rg.addView(RadioButton(this).apply {
                id = 3000 + i
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                isChecked = code == cur
                setPadding((6 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
        }
        rg.setOnCheckedChangeListener { _, idc ->
            val code = Lang.CHOICES.getOrNull(idc - 3000)?.first ?: return@setOnCheckedChangeListener
            Prefs.edit(this) { putString(Lang.KEY, code) }
            Prefs.loadLang(this)
            toast("भाषा: ${Lang.short(code)} — अगले जवाब से लागू")
        }
        box.addView(rg)
        hint(box, "DK लाइव चल रहा हो तो नई भाषा अगली बार जुड़ने पर (लाइव बंद करके फिर चालू करें)। " +
            "किसी एक जवाब के लिए आप बोलकर दूसरी भाषा माँग सकते हैं।")
    }
}

/** App ka "console": har galti/chetavni, mic ke aankde, Live connection, deri (ms) */
internal fun SettingsActivity.secDebug() {
    // v5.1: DK Inspector - Debug के तीनों हिस्से पढ़कर गड़बड़ी, वजह, असर, हल और bug रिपोर्ट (कोड कभी नहीं बदलता)
    card("DK Inspector", "Run Deep Inspection • Ask DK Inspector • Bug Reports • हर 20 मिनट अपने-आप जाँच") { box ->
        try { com.dk.assistant.inspector.InspectorCard.build(this, box, dp, { t, f -> button(t, f) }, { b, vs -> row(b, *vs) }) }
        catch (_: Throwable) { hint(box, "DK Inspector अभी नहीं खुल पाया - बाकी Debug हिस्से नीचे वैसे ही हैं") }
    }
    card("Gemini Live हाल", "माइक से भेजे टुकड़े, गिरे टुकड़े, दोबारा जुड़ना, देरी") { box ->
        val st = TextView(this).apply { textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        box.addView(st)
        fun upd() { st.text = GeminiLive.stats + "\nमाइक परमिशन: " + (if (Setup.hasMic(this)) "मिली है" else "नहीं मिली") +
            "\nसुनने का तरीका: " + Prefs.listenEngine(this) + " • Gemini key: " + (if (GeminiLive.available(this)) "है" else "नहीं") }
        upd()
        row(box, button("ताज़ा करें") { upd() })
    }
    // v3.71: बोलने की देरी - हर बारी के तीन समय
    card("देरी का हिसाब", "आपका बोलना ख़त्म → पहला टुकड़ा आया → पहले वाक्य की आवाज़ बनी → आवाज़ बजी") { box ->
        val tv = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true) }
        box.addView(tv)
        fun upd() { tv.text = LatencyBoard.text() + "\n\n" + NativeCommands.text() }
        upd()
        row(box, button("ताज़ा करें") { upd() }, button("साफ़ करें") { LatencyBoard.clear(); NativeCommands.clear(); upd() })
    }
    // नवंबर 2026 (मालिक): Live और text मोड की कॉल / गलतियाँ अलग-अलग
    card("Live / Text हिसाब", "Gemini Live, text मोड (बैकअप) और पीछे की सेहत-जाँच - हर एक की कॉल, ठीक, गलती अलग") { box ->
        val tv = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true) }
        box.addView(tv)
        fun upd() { tv.text = ModeTrack.text() }
        upd()
        row(box, button("ताज़ा करें") { upd() }, button("साफ़ करें") { ModeTrack.clear(); upd() })
    }
    card("लॉग", "सबसे नई लाइन नीचे • 300 लाइन तक") { box ->
        val tv = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true)
            text = DKLog.all().ifBlank { "अभी कोई लॉग नहीं" }
        }
        box.addView(tv)
        DKLog.listener = { all -> tv.text = all }
        row(box,
            button("कॉपी करें") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("DK log", GeminiLive.stats + "\n\n" + DKLog.all()))
                toast("लॉग कॉपी हो गया")
            },
            button("साफ़ करें") { DKLog.clear() })
    }
}

internal fun SettingsActivity.secUpdate() {
    card("अपडेट", "अभी का version: ${Updater.displayVersion(this)}") { box ->
        val status = TextView(this).apply {
            text = "अपडेट चेक करने के लिए बटन दबाएँ"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (2 * dp).toInt())
        }
        box.addView(status)
        val info = TextView(this).apply {
            textSize = 13f; setTextColor(Glass.ACCENT); visibility = View.GONE
            setPadding((4 * dp).toInt(), 0, 0, (4 * dp).toInt())
        }
        box.addView(info)
        val notes = TextView(this).apply {
            textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); visibility = View.GONE
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        box.addView(notes)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * dp).toInt()))
        lateinit var btnAct: Button
        var watch: kotlinx.coroutines.Job? = null

        /** Button aur status: डाउनलोड करें -> डाउनलोड हो रहा है x% -> इंस्टॉल करें */
        fun show(rel: Updater.Release?) {
            if (rel == null) {
                UiLang.setText(status, "✓ आपके पास सबसे नया version है")
                info.visibility = View.GONE; notes.visibility = View.GONE; bar.visibility = View.GONE; btnAct.visibility = View.GONE
                return
            }
            UiLang.setText(status, "${rel.typeLabel} आया है")
            UiLang.setText(info, "नया version: DK ${rel.version}  •  साइज़: ${rel.sizeMb}")
            info.visibility = View.VISIBLE
            UiLang.setText(notes, "क्या नया है:\n" + rel.notes.take(1200).ifBlank { if (rel.isSecurity) "गड़बड़ियाँ ठीक की गईं" else "नए फ़ीचर और सुधार" })
            notes.visibility = View.VISIBLE
            btnAct.visibility = View.VISIBLE
            when {
                Updater.downloaded(this, rel) != null -> {
                    bar.visibility = View.GONE
                    UiLang.setText(btnAct, "इंस्टॉल करें"); btnAct.isEnabled = true
                    UiLang.setText(status, "✓ ${rel.typeLabel} डाउनलोड हो चुका है")
                }
                Updater.isDownloading(rel) -> {
                    bar.visibility = View.VISIBLE; bar.progress = Updater.progress
                    UiLang.setText(btnAct, "डाउनलोड हो रहा है… ${Updater.progress}%"); btnAct.isEnabled = false
                }
                else -> {
                    bar.visibility = View.GONE
                    UiLang.setText(btnAct, "डाउनलोड करें (${rel.sizeMb})"); btnAct.isEnabled = true
                    Updater.lastError?.let { UiLang.setText(status, "डाउनलोड रुक गया: $it — फिर दबाएँ, जहाँ रुका वहीं से आगे होगा") }
                }
            }
        }

        /** Download chalte samay har 300ms screen taaza (download khud background me chalta hai) */
        fun follow(rel: Updater.Release) {
            watch?.cancel()
            watch = lifecycleScope.launch {
                while (true) {
                    show(rel)
                    if (!Updater.isDownloading(rel)) break
                    kotlinx.coroutines.delay(300)
                }
                show(rel)
            }
        }

        val btnCheck = button("अपडेट चेक करें") {
            status.text = "चेक हो रहा है…"
            lifecycleScope.launch {
                try {
                    val rel = Updater.check(this@secUpdate)
                    pendingRelease = rel
                    show(rel)
                    if (rel != null && Updater.isDownloading(rel)) follow(rel)
                } catch (e: Exception) {
                    // v3.64: असली वजह (403 = GitHub की घंटे वाली सीमा) - पहले हर बार "इंटरनेट देखें"
                    status.text = "चेक नहीं हो पाया: ${e.message ?: ReleaseFeed.friendly(0)}"
                }
            }
        }
        btnAct = button("डाउनलोड करें") {
            val rel = pendingRelease ?: return@button
            if (Updater.downloaded(this, rel) != null) {
                if (!Updater.canInstall(this)) {
                    toast("DK को \"ऐप इंस्टॉल करने\" की इजाज़त दें, फिर वापस आकर दोबारा दबाएँ")
                    Updater.askInstallPermission(this); return@button
                }
                status.text = "इंस्टॉल स्क्रीन पर \"Update / Install\" दबाएँ"
                lifecycleScope.launch {
                    try { Updater.install(this@secUpdate, rel) }
                    catch (e: Exception) { status.text = "इंस्टॉल नहीं हुआ: ${e.message}" }
                }
            } else {
                Updater.startDownload(this, rel)
                toast("डाउनलोड बैकग्राउंड में चल रहा है — ऐप बंद कर सकते हैं, पूरा होने पर सूचना आएगी")
                follow(rel)
            }
        }.apply { visibility = View.GONE }
        row(box, btnCheck)
        row(box, btnAct)
        swAutoUpd = switch(box, "ऐप खुलते ही अपडेट चेक करो और बैकग्राउंड में डाउनलोड करो", Prefs.autoUpdate(this))
        hint(box, "सॉफ़्टवेयर अपडेट = नया डिज़ाइन / नए फ़ीचर  •  सिक्योरिटी पैच = गड़बड़ियाँ और सुरक्षा ठीक")
        // Settings khulte hi ek baar chupchap check
        btnCheck.performClick()
    }

}

internal fun SettingsActivity.secProfile() {
    card("प्रोफ़ाइल", "आपका नाम, असिस्टेंट का नाम और बात करने का तरीका") {
        edUser = field(it, "आपका नाम", Prefs.userName(this), "जैसे: दीपक")
        edName = field(it, "असिस्टेंट का नाम", Prefs.assistantName(this), "DK")
        edPersona = field(it, "बात करने का तरीका (अपनी तरफ़ से)", Prefs.personality(this),
            "जैसे: जवाब छोटे रखो, मुझे सर कहकर बुलाओ", multiLine = true)
        edWake = field(it, "जगाने वाले शब्द (कॉमा से अलग)", Prefs.wakeWordsRaw(this), "डीके, dk")
    }
    secAccessKey()

}

/** DK AI Part 13: access key (एडमिन ${AssistantBrand.OWNER_NAME}) */
internal fun SettingsActivity.secAccessKey() {
    val km = com.dk.assistant.license.AccessKeyManager
    card("Access key", "DK चलाने की key • एडमिन: ${AssistantBrand.OWNER_NAME}") { box ->
        val status = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt())
            text = km.refreshInfo(this@secAccessKey).statusText
        }
        box.addView(status)
        if (!km.configured(this)) {
            hint(box, "लाइसेंस सर्वर इस ऐप में सेट नहीं है, इसलिए key की ज़रूरत नहीं।")
            return@card
        }
        val ed = field(box, "आपकी access key", "", "जैसे: DK-ABCD-1234", secret = true)
        ed.removeTextChangedListener(watcher)
        var busy = false
        val btnOn = button("key चालू करें") {
            if (busy) return@button
            busy = true; status.text = UiLang.s("जाँच हो रही है…")
            lifecycleScope.launch {
                val msg = km.activate(this@secAccessKey, ed.text.toString())
                busy = false; status.text = msg; ed.setText("")
            }
        }
        val btnCheck = button("अभी जाँचें") {
            lifecycleScope.launch { status.text = km.liveCheck(this@secAccessKey, force = true).statusText }
        }
        val btnForget = button("key हटाएँ") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("इस फ़ोन से key हटाएँ?"))
                .setMessage(UiLang.s("हटाने के बाद DK तब तक काम नहीं करेगा जब तक नई key न डालें।"))
                .setPositiveButton(UiLang.s("हटाएँ")) { _, _ -> km.forget(this); status.text = km.refreshInfo(this).statusText }
                .setNegativeButton(UiLang.s("रहने दो"), null).show()
        }
        row(box, btnOn, btnCheck)
        row(box, btnForget)
        hint(box, "key हर 3 घंटे में एक बार सर्वर से जाँची जाती है। इंटरनेट न हो तो पिछली हालत चलती रहती है। key रोकने/चालू करने का अधिकार सिर्फ़ एडमिन ${AssistantBrand.OWNER_NAME} के पास है।")
    }
}

// ------------------------------------------------------------ v3.58 (Part 11): About + निजता
internal fun SettingsActivity.secAbout() {
    card(AssistantBrand.FULL_NAME, AssistantBrand.TAGLINE) { box ->
        hint(box, "Version: " + Updater.displayVersion(this))
        box.addView(TextView(this).apply {
            text = AssistantBrand.CREATED_BY; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (14 * dp).toInt(), 0, (4 * dp).toInt())
        })
        hint(box, "असिस्टेंट का नाम: " + Prefs.assistantName(this) + " (बदलने के लिए सेटिंग्स → प्रोफ़ाइल)")
    }
    card("निजता (Privacy)", "आपका डेटा कहाँ जाता है") { box ->
        hint(box, "• आपकी API keys और सेटिंग्स सिर्फ़ इस फ़ोन में सेव रहती हैं (फ़ोन के backup में भी नहीं जातीं)। हर key सिर्फ़ अपनी सेवा को भेजी जाती है, और आपका सवाल उसी AI सेवा को जाता है जिसकी key आपने डाली है।")
        hint(box, "• स्क्रीन देखना चालू हो तभी स्क्रीनशॉट AI को जाता है (सेटिंग्स → परमिशन)।")
        hint(box, "• Notification सिर्फ़ फ़ोन की memory में रहते हैं।")
        hint(box, "• मौसम/इलाके के लिए ली गई लोकेशन कहीं सेव नहीं होती।")
        hint(box, "• कैमरा permission सिर्फ़ टॉर्च के लिए है; DK फ़ोटो नहीं लेता।")
        hint(box, "• Instagram / Facebook / WhatsApp पर पोस्ट या मैसेज भेजने से पहले DK आपसे \"हाँ\" पूछता है।")
    }
    card("और विकल्प", null) { box ->
        row(box,
            button("परिचय दोबारा देखें") { startActivity(android.content.Intent(this, com.dk.assistant.onboard.OnboardingActivity::class.java)) },
            button("परमिशन") { startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", "perms")) })
    }
}

internal fun SettingsActivity.secAccess() {
    card("फ़ोन कंट्रोल (Accessibility)", "एक बार ON करें — फिर ON ही रहेगा") { box ->
        accStatus = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        box.addView(accStatus)
        val roleManager = getSystemService(android.app.role.RoleManager::class.java)
        val isAssistantRoleHeld = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        val roleStatusView = TextView(this).apply {
            textSize = 14f
            setTextColor(if (isAssistantRoleHeld) 0xFF00FF00.toInt() else 0xFFFF5555.toInt())
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
            text = if (isAssistantRoleHeld) "✓ Assistant Role: सक्रिय है" else "✗ Assistant Role: सक्रिय नहीं है (डिफ़ॉल्ट असिस्टेंट सेट करें)"
        }
        box.addView(roleStatusView)
        row(box,
            button("Accessibility ON करें") { Setup.openAccessibility(this) },
            button("App info") { Setup.openAppInfo(this) })
        hint(box, "Android 13+: अगर Accessibility में DK धुंधला दिखे तो App info → ऊपर ⋮ → \"Allow restricted settings\" करें, फिर ON करें।")
    }
    card("बार-बार बंद न हो", "Android बैटरी बचाने के लिए DK को बंद कर देता है — यह रोकें") { box ->
        batStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        box.addView(batStatus)
        row(box, button("बैटरी रोक हटाएँ") { Setup.requestBatteryExempt(this) })
        hint(box, "Xiaomi/Redmi/Poco: App info → Autostart ON, Battery saver → No restrictions। Vivo/Oppo/Realme: Battery → Allow background activity। Recent apps में DK पर लॉक लगाएँ।")
        label(box, "हमेशा ON (सबसे पक्का तरीका — एक बार इजाज़त, PC हो या न हो)")
        secStatus = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), 0, 0, 0) }
        box.addView(secStatus)
        val cmd = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        box.addView(TextView(this).apply {
            text = cmd; textSize = 12f; setTextColor(Glass.ACCENT); setTextIsSelectable(true)
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
        })
        row(box, button("कमांड कॉपी करें") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd)); toast("कॉपी हो गया")
        })
        hint(box, "USB debugging ON करके कंप्यूटर से यह कमांड एक बार चलाएँ। उसके बाद अगर Android कभी DK की Accessibility बंद भी कर दे, तो DK खुद उसे वापस ON कर लेगा।")
        // नवंबर 2026 (मालिक: "मेरे पास PC नहीं"): Android 11+ में फ़ोन खुद ही adb चला सकता है (Wireless debugging)
        label(box, "बिना PC के (Android 11+, सिर्फ़ फ़ोन से)")
        val shellCmd = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        hint(box, A11yNoPc.STEPS)
        row(box, button("बिना PC वाली कमांड कॉपी करें") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("shell", shellCmd)); toast("कॉपी हो गया — aShell में चिपकाएँ")
        }, button("Shizuku खोलें") { Setup.openUrl(this, A11yNoPc.SHIZUKU_URL) })
        row(box, button("aShell खोलें") { Setup.openUrl(this, A11yNoPc.ASHELL_URL) },
            button("Developer options") { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) { toast("पहले Build number 7 बार दबाएँ") } })
    }
    card("सुनना और कॉल", null) { box ->
        swWake = switch(box, "हमेशा सुनो (जगाने वाला शब्द बोलकर कमांड)", Prefs.wakeMode(this))
        // v4.1: सिर्फ़ "DK ..." से शुरू होने वाली बात सुनो (शुरू में बंद)
        swDkOnly = switch(box, "सिर्फ़ DK बोलने पर सुनो", Prefs.dkOnly(this))
        hint(box, "चालू करने पर DK तभी सुनेगा और काम करेगा जब बात की शुरुआत में DK बोला जाए। बाकी बातें अनदेखी कर दी जाएँगी।")
        swCalls = switch(box, "आने वाली कॉल बोलकर बताओ", Prefs.announceCalls(this))
        hint(box, "कॉल आने पर DK बताएगा किसका कॉल है (फ़ोन, WhatsApp, Telegram, Messenger, Instagram)। घंटी के समय बिना DK बोले कहें: \"उठाओ\", \"काट दो\", \"उठाकर स्पीकर पर डालो\", \"काटो और मैसेज भेजो ...\", \"काटो और बोलो बाद में कॉल करूँगा\"।")
        edBusyMsg = field(box, "कॉल काटकर भेजा जाने वाला तैयार मैसेज", Prefs.callBusyMsg(this), Prefs.CALL_BUSY_DEFAULT)
        row(box, button("कॉल/कॉन्टैक्ट परमिशन दें") {
            androidx.core.app.ActivityCompat.requestPermissions(this, CallManager.PERMS, 11)
        })
        hint(box, "कॉल के दौरान Android दूसरे ऐप्स को माइक नहीं देता — कॉल काटने के लिए स्क्रीन पर तैरता DK बुलबुला एक बार दबाएँ।")
    }
    val crash = DKApp.lastCrash(this)
    if (crash.isNotBlank()) card("गड़बड़ी रिपोर्ट", "पिछली बार DK किस वजह से बंद हुआ") { box ->
        box.addView(TextView(this).apply {
            text = crash.take(1200); textSize = 11f; setTextColor(0xB3FFFFFF.toInt()); setTextIsSelectable(true)
        })
        row(box,
            button("कॉपी करें") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DK crash", crash)); toast("कॉपी हो गया — मुझे भेज दें")
            },
            button("हटाएँ") { DKApp.clearCrash(this); toast("हटा दिया") })
    }

    refreshAccess()
}

internal fun SettingsActivity.refreshAccess() {
    accStatus?.text = when {
        Setup.accessibilityOn() -> "✓ चालू है — DK फ़ोन चला सकता है"
        // नवंबर 2026: ON दिखता है पर चल नहीं रही (ज़्यादातर ऊपर "Active apps" से Stop दबाने पर) - साफ़ बताओ
        Setup.enabledInSettings(this) -> if (Setup.canWriteSecure(this)) "⟳ रुकी थी — DK खुद फिर से चालू कर रहा है…"
            else "⚠ Settings में ON है पर Android ने सेवा रोक दी (\"Service is not available\")। ठीक करें: नीचे बटन → DK → OFF → फिर ON"
        else -> "✗ बंद है — नीचे बटन दबाकर Installed/Downloaded apps में ${Prefs.assistantName(this)} ON करें"
    }
    batStatus?.text = if (Setup.batteryExempt(this)) "✓ बैटरी रोक हटी हुई है" else "✗ Android DK को बैटरी बचाने के लिए बंद कर सकता है"
    secStatus?.text = if (Setup.canWriteSecure(this)) "✓ चालू — DK अपनी Accessibility खुद ON रखेगा" else "अभी नहीं दिया गया (ज़रूरी नहीं, पर सबसे पक्का)"
}

internal fun SettingsActivity.secPerms() {
    card("स्क्रीन देखना", "DK आपकी स्क्रीन देखकर काम करे (स्क्रीनशॉट AI को जाता है)") { box ->
        swShot = switch(box, "स्क्रीन देखना / शेयर करना", Prefs.screenshot(this))
        swShot.setOnCheckedChangeListener { _, on ->
            Prefs.edit(this) { putBoolean("screenshot", on); putBoolean(ScreenGuard.USER_OK_KEY, on) }
            toast(if (on) "अब DK स्क्रीन देख सकता है" else "स्क्रीन देखना बंद")
        }
        hint(box, "बोलकर भी कर सकते हैं: \"डीके, स्क्रीन देखना चालू करो\" / \"स्क्रीन देखना बंद करो\"। बंद रहने पर DK सिर्फ़ स्क्रीन का लिखा हुआ पढ़ता है।")
        // v3.156 मालिक: "कोई भी ऐप में टैप करने की परमिशन होनी चाहिए" - सिर्फ़ आपके कहे काम (ChatGPT/Gemini) में बटन ढूँढने को
        val swTask = switch(box, "आपके कहे काम में बटन देखकर दबाए (ChatGPT/Gemini में \"भेजें\")", Prefs.taskLook(this))
        swTask.setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean(Prefs.TASK_LOOK_KEY, on) } }
        hint(box, "ऊपर वाला बंद हो तब भी: जब आप खुद \"ChatGPT में यह लिखकर भेजो\" कहें, और बटन नाम से न मिले, तभी उसी पल की एक तस्वीर आपकी Gemini key से देखी जाती है।")
    }
    card("ज़रूरी परमिशन", null) { box ->
        permStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        box.addView(permStatus)
        row(box, button("माइक + नोटिफ़िकेशन") {
            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            androidx.core.app.ActivityCompat.requestPermissions(this, perms.toTypedArray(), 12)
        }, button("कॉल/कॉन्टैक्ट") {
            androidx.core.app.ActivityCompat.requestPermissions(this, CallManager.PERMS, 11)
        })
        row(box, button("सीधी कॉल + फ़ोटो") {
            androidx.core.app.ActivityCompat.requestPermissions(this, PhoneActions.EXTRA_PERMS, 14)
        })
        row(box, button("ऐप इंस्टॉल (अपडेट के लिए)") { Updater.askInstallPermission(this) },
            button("App info") { Setup.openAppInfo(this) })
        row(box, button("टॉर्च की permission दें") {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 13)
        })
        hint(box, "कैमरा permission यहाँ सिर्फ़ टॉर्च चालू/बंद करने के लिए है; DK फ़ोटो नहीं लेता।")
        row(box, button("Notification पढ़ने की इजाज़त") { DKNotifListener.openSettings(this) })
        hint(box, "इससे DK लाइव चलते समय आए notification बता सकता है (\"क्या notification आया?\") और आपके कहने पर उनका reply भेज सकता है। notification सिर्फ़ फ़ोन की memory में रहते हैं।")
    }
    refreshPerms()
}

internal fun SettingsActivity.refreshPerms() {
    fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val notif = android.os.Build.VERSION.SDK_INT < 33 || ok(android.Manifest.permission.POST_NOTIFICATIONS)
    val calls = CallManager.PERMS.all { ok(it) }
    val camera = ok(android.Manifest.permission.CAMERA)
    permStatus?.text = (if (Setup.hasMic(this)) "✓" else "✗") + " माइक\n" +
        (if (notif) "✓" else "✗") + " नोटिफ़िकेशन\n" +
        (if (calls) "✓" else "✗") + " कॉल / कॉन्टैक्ट\n" +
        (if (camera) "✓" else "✗") + " टॉर्च (कैमरा permission)\n" +
        (if (DKNotifListener.enabled(this)) "✓" else "✗") + " notification पढ़ना / reply\n" +
        (if (ok(PhoneActions.CALL_PERM)) "✓" else "✗") + " सीधी कॉल (न हो तो डायलर खुलता है)\n" +
        (if (ok(PhoneActions.PHOTO_PERM)) "✓" else "✗") + " फ़ोटो (सबसे नई फ़ोटो खोलना)\n" +
        (if (Updater.canInstall(this)) "✓" else "✗") + " ऐप इंस्टॉल (अपडेट)"
}
