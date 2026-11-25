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
    // 25 नवंबर 2026 (मालिक): तीन भाषाएँ - हिंदी, English, Hinglish - और नीचे "Custom" (खोजकर दुनिया की कोई भी भाषा).
    // English चुनी हो तो पूरा ऐप English में (शब्दकोश में न हो वह फ़ोन पर ही अनुवाद - [UiMt])
    card("ऐप की भाषा (स्क्रीन)", "पूरे ऐप के बटन, Settings, संदेश — हिंदी, English, Hinglish या कोई भी भाषा") { box ->
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val ui = listOf(UiLang.HI to "हिंदी (Hindi)", UiLang.EN to "English (default)", UiLang.HINGLISH to "Hinglish (Hindi + English)")
        ui.forEachIndexed { i, (code, label) ->
            rg.addView(RadioButton(this).apply {
                id = 3100 + i; text = label; setTextColor(com.dk.assistant.ui.ClayTheme.t.text); textSize = 15f
                isChecked = code == UiLang.code
                setPadding((6 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                UiLang.keep(this)     // भाषा के नाम हर भाषा में वैसे ही
            })
        }
        if (UiLang.custom()) rg.addView(RadioButton(this).apply {
            id = 3199; text = "Custom: " + (WorldLangs.find(UiLang.code)?.label ?: UiLang.code)
            setTextColor(com.dk.assistant.ui.ClayTheme.t.text); textSize = 15f; isChecked = true
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            UiLang.keep(this)
        })
        rg.setOnCheckedChangeListener { _, idc ->
            val code = ui.getOrNull(idc - 3100)?.first ?: return@setOnCheckedChangeListener
            if (code == UiLang.code) return@setOnCheckedChangeListener
            pickUiLang(code)
        }
        box.addView(rg)
        row(box, button("Custom — दुनिया की कोई भी भाषा खोजें") { customLangDialog() })
        if (UiMt.status.isNotBlank()) hint(box, UiMt.status)
        hint(box, "चुनते ही पूरे ऐप की स्क्रीनें इसी भाषा में। जिन लाइनों का अनुवाद पहले से नहीं, वे फ़ोन पर ही बदलती हैं — " +
            "पहली बार भाषा-पैक (करीब 30 MB) इंटरनेट से आता है, फिर बिना इंटरनेट। DK के जवाब की भाषा नीचे अलग से चुनें। " +
            "बातचीत, कोड और कहानियाँ जैसी लिखी हैं वैसी ही दिखती हैं।")
    }
    card("DK किस भाषा में जवाब दे", "DK लाइव, DK चैट, बिना-Live आवाज़, DK कंप्यूटर और कोडिंग एजेंट — सब पर एक साथ") { box ->
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cur = Prefs.replyLang(this)
        (Lang.CHOICES + Lang.MORE).forEachIndexed { i, (code, label) ->
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
            val code = (Lang.CHOICES + Lang.MORE).getOrNull(idc - 3000)?.first ?: return@setOnCheckedChangeListener
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
            button("साफ़ करें") { DKLog.clear(); Quiet.clear() })
        // 16 नवंबर 2026 (ऑडिट Q1): जो गलतियाँ पहले चुपचाप निगली जाती थीं - अब गिनती + जगह
        row(box, button("दबी गलतियाँ") {
            val t = Quiet.summary()
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("DK quiet", t))
            android.app.AlertDialog.Builder(this).setTitle("दबी गलतियाँ").setMessage(t.take(3000)).setPositiveButton("ठीक", null).show()
        })
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
                    Updater.retryNote?.let { UiLang.setText(status, it) }      // नवंबर 2026: 504 पर "दोबारा कोशिश 2/6"
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
                    // नवंबर 2026: पिछली बार मिला नया version याद है = उसी का डाउनलोड बटन (GitHub का 504 डाउनलोड न रोके)
                    Updater.cached(this@secUpdate)?.let { old ->
                        pendingRelease = old; show(old)
                        if (Updater.isDownloading(old)) follow(old)
                    }
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
        // 16 नवंबर 2026 (मालिक: "बीटा और स्टेबल अपडेट"): शुरू से स्टेबल; बीटा = चालू करने वाले को ही, अलग से
        val swBeta = switch(box, "बीटा अपडेट भी लो (नए फ़ीचर स्टेबल से पहले — कभी-कभी कोई कमी रह सकती है)", Prefs.betaUpdates(this))
        swBeta.setOnCheckedChangeListener { _, on -> Prefs.setBetaUpdates(this, on); btnCheck.performClick() }
        hint(box, "स्टेबल = सबके लिए सामान्य अपडेट  •  बीटा = आज़माइशी build, सिर्फ़ यह चालू करने वालों को  •  हर डाउनलोड का SHA-256 मिलाया जाता है")
        // Settings khulte hi ek baar chupchap check
        btnCheck.performClick()
    }

}

internal fun SettingsActivity.secProfile() {
    realNameCard()   // 25 नवंबर 2026 (मालिक): असली नाम + verified टिक सबसे ऊपर
    card("प्रोफ़ाइल", "आपका नाम, असिस्टेंट का नाम और बात करने का तरीका") {
        edUser = field(it, "आपका नाम", Prefs.userName(this), "जैसे: दीपक")
        hint(it, "DK इसी नाम से बुलाएगा: power बटन / Hey DK पर \"जी <नाम>\" (नाम बॉस = \"जी बॉस\"), \"हेलो DK\" पर \"हेलो <नाम>\"।")
        edName = field(it, "असिस्टेंट का नाम", Prefs.assistantName(this), "DK")
        edPersona = field(it, "बात करने का तरीका (अपनी तरफ़ से)", Prefs.personality(this),
            "जैसे: जवाब छोटे रखो, मुझे सर कहकर बुलाओ", multiLine = true)
        edWake = field(it, "जगाने वाले शब्द (कॉमा से अलग)", Prefs.wakeWordsRaw(this), "डीके, dk")
    }
    // 15 नवंबर 2026 (मालिक): access key / license हटाया - सब कुछ बिना key के खुला 
    secProfileMore()

}


/** 20 नवंबर 2026 (मालिक): "नंबर, अच्छे गाने (लिंक या नाम), Instagram / Facebook का हैंडल, और कुछ भी - DK को सब पता हो, कहूँ तो खोले" */
internal fun SettingsActivity.secProfileMore() {
    card("आपकी और जानकारी", "जो भी भरेंगे, DK को पता रहेगा — पूछें तो बताएगा, कहें तो सीधा खोलेगा। सब सिर्फ़ इसी फ़ोन में") { box ->
        profileFields[OwnerProfile.PHONE] = field(box, "आपका मोबाइल नंबर", Prefs.str(this, OwnerProfile.PHONE, ""), "जैसे: 98XXXXXXXX")
            .apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        profileFields[OwnerProfile.SONGS] = field(box, "पसंद के गाने (हर लाइन में एक — YouTube लिंक या गाने का नाम)", Prefs.str(this, OwnerProfile.SONGS, ""),
            "जैसे: तुम ही हो - अरिजीत सिंह\nhttps://youtu.be/...", multiLine = true)
        profileFields[OwnerProfile.INSTAGRAM] = field(box, "Instagram id / हैंडल (या प्रोफ़ाइल लिंक कॉपी करके)", Prefs.str(this, OwnerProfile.INSTAGRAM, ""), "जैसे: @aapka_naam")
        profileFields[OwnerProfile.FACEBOOK] = field(box, "Facebook id / प्रोफ़ाइल लिंक", Prefs.str(this, OwnerProfile.FACEBOOK, ""), "जैसे: facebook.com/aapka.naam")
        profileFields[OwnerProfile.YOUTUBE] = field(box, "YouTube चैनल (हैंडल या लिंक)", Prefs.str(this, OwnerProfile.YOUTUBE, ""), "जैसे: @aapka_channel")
        profileFields[OwnerProfile.EXTRA] = field(box, "अपने बारे में और कुछ (शहर, जन्मदिन, काम, पसंद …)", Prefs.str(this, OwnerProfile.EXTRA, ""),
            "जैसे: नासिक में रहता हूँ, जन्मदिन 5 मार्च", multiLine = true)
        fun open(u: String) { if (u.isBlank()) toast("पहले ऊपर भरें") else Setup.openUrl(this, u) }
        row(box,
            button("Instagram खोलें") { open(OwnerProfile.instagramUrl(profileFields[OwnerProfile.INSTAGRAM]?.text?.toString().orEmpty())) },
            button("Facebook खोलें") { open(OwnerProfile.facebookUrl(profileFields[OwnerProfile.FACEBOOK]?.text?.toString().orEmpty())) })
        row(box,
            button("YouTube खोलें") { open(OwnerProfile.youtubeUrl(profileFields[OwnerProfile.YOUTUBE]?.text?.toString().orEmpty())) },
            button("पहला गाना चलाएँ") { open(OwnerProfile.songsOf(profileFields[OwnerProfile.SONGS]?.text?.toString().orEmpty()).firstOrNull()?.let { OwnerProfile.songUrl(it) }.orEmpty()) })
        hint(box, "पीछे जाते ही सेव। फिर DK से बोलें: \"मेरा नंबर क्या है\" • \"मेरा Instagram खोलो\" • \"मेरा पसंदीदा गाना चलाओ\" • \"मेरी Facebook प्रोफ़ाइल चेक करो\"। DK आपका नंबर या ये बातें किसी और को खुद से नहीं भेजती — सिर्फ़ आपके कहने पर। अगली बार DK लाइव जुड़ने पर लागू।")
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
    // 16 नवंबर 2026 (मालिक: "असली डेटा कहाँ है, privacy पेज में लिखो") - पूरा: docs/PRIVACY.md
    card("निजता (Privacy)", "आपका डेटा असल में कहाँ जाता है") { box ->
        PrivacyText.LINES.forEach { hint(box, it) }
        row(box, button("पूरा निजता पेज") {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PrivacyText.URL))) } catch (_: Exception) { toast(PrivacyText.URL) }
        })
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
        // 16 नवंबर 2026 (Android Lint, NewApi): RoleManager Android 10+ में ही - पहले Android 8/9 पर यह स्क्रीन खुलते ही बंद होती
        val isAssistantRoleHeld = android.os.Build.VERSION.SDK_INT >= 29 &&
            getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
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
    // 15 नवंबर 2026 (मालिक): "DK कॉल उठाए, जैसे बोलूँ तो. और कॉलिंग की अपनी सेटिंग - कौन-सी रिंग, कितनी तेज़"
    card("DK कॉलिंग", "बोलकर कॉल उठाना • रिंग कौन-सी और कितनी तेज़ • DK कितनी देर सुने") { box ->
        fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val checks = CallTune.checks(
            announce = Prefs.announceCalls(this),
            phoneState = ok(android.Manifest.permission.READ_PHONE_STATE),
            answer = android.os.Build.VERSION.SDK_INT < 26 || ok(android.Manifest.permission.ANSWER_PHONE_CALLS),
            a11y = DKAccessibilityService.instance != null)
        hint(box, checks)
        row(box, button("कॉल परमिशन दें") { androidx.core.app.ActivityCompat.requestPermissions(this, CallManager.PERMS, 11) },
            button("Accessibility खोलें") { try { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { toast("फ़ोन की Settings → Accessibility में DK चालू करें") } })
        label(box, "घंटी कितनी तेज़ बजे")
        val modes = listOf(CallTune.FULL to "पूरी आवाज़ (कम हो तो फुल — पहले जैसा)", CallTune.KEEP to "जितनी फ़ोन में रखी है, उतनी ही", CallTune.LEVEL to "मेरी तय आवाज़ (नीचे वाली पट्टी)")
        radios(box, modes, CallTune.ringMode(this), 7100)
            .setOnCheckedChangeListener { _, id -> modes.getOrNull(id - 7100)?.let { Prefs.edit(this) { putString(CallTune.K_MODE, it.first) }; toast("घंटी: ${it.second.substringBefore(" (")}") } }
        callPct(box, "तय आवाज़", CallTune.ringPct(this)) { CallTune.setNum(this, CallTune.K_PCT, it) }
        switch(box, "DK जब तक जवाब सुन रही है, घंटी धीमी करो (ताकि \"DK उठा लो\" सुनाई दे)", CallTune.listenQuiet(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean(CallTune.K_QUIET, on) } }
        callPct(box, "सुनते समय घंटी", CallTune.listenPct(this)) { CallTune.setNum(this, CallTune.K_QPCT, it) }
        label(box, "घंटी के समय DK कितनी देर सुने")
        val secs = CallTune.SECONDS.map { it.toString() to "$it सेकंड" }
        radios(box, secs, (CallTune.listenMs(this) / 1000).toString(), 7150)
            .setOnCheckedChangeListener { _, id -> CallTune.SECONDS.getOrNull(id - 7150)?.let { CallTune.setNum(this, CallTune.K_SEC, it); toast("DK $it सेकंड सुनेगी") } }
        row(box, button("रिंग चुनें") { startActivity(android.content.Intent(this, CallRingPickActivity::class.java)) },
            button("डिफ़ॉल्ट") {
                Prefs.edit(this) { putString(CallTune.K_MODE, CallTune.FULL); putBoolean(CallTune.K_QUIET, true) }
                CallTune.setNum(this, CallTune.K_PCT, CallTune.DEF_PCT); CallTune.setNum(this, CallTune.K_QPCT, CallTune.DEF_QPCT); CallTune.setNum(this, CallTune.K_SEC, CallTune.DEF_SEC)
                toast("डिफ़ॉल्ट लगा दिया — सेटिंग दोबारा खोलें")
            })
        hint(box, "घंटी के समय बोलें: \"DK उठा लो\" / \"कॉल उठाओ\" / \"उठाकर स्पीकर पर डालो\" / \"काट दो\" / \"कौन है\" / \"घंटी धीमी करो\". कॉल ख़त्म होते ही घंटी की पुरानी आवाज़ वापस. फ़ोन साइलेंट/वाइब्रेट पर हो तो DK घंटी नहीं छेड़ती. रिंग बदलने के लिए एक बार \"सिस्टम सेटिंग बदलें\" की इजाज़त माँगी जाएगी.")
    }
    val crash = DKApp.lastCrash(this)
    if (crash.isNotBlank()) card("गड़बड़ी रिपोर्ट", "पिछली बार DK किस वजह से बंद हुआ") { box ->
        box.addView(TextView(this).apply {
            text = crash.take(1200); textSize = 11f; setTextColor(0xB3FFFFFF.toInt()); setTextIsSelectable(true)
        })
        row(box,
            button("कॉपी करें") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                // नवंबर 2026: पिछली कई गलतियाँ साथ (एक से जड़ नहीं मिलती तो बाकी से)
                val all = DKApp.crashHistory(this).ifBlank { crash }
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DK crash", all)); toast("कॉपी हो गया — मुझे भेज दें")
            },
            button("हटाएँ") { DKApp.clearCrash(this); toast("हटा दिया") })
        // 16 नवंबर 2026 (मालिक: "क्रैश रिपोर्ट मेरी मर्ज़ी से"): भेजना सिर्फ़ आपके दबाने पर, छिपाकर
        row(box,
            button("रिपोर्ट भेजें") { CrashUi.openIssue(this, DKApp.crashHistory(this).ifBlank { crash }) },
            button("शेयर करें") { CrashUi.share(this, DKApp.crashHistory(this).ifBlank { crash }) })
        hint(box, "DK खुद कुछ नहीं भेजता। \"रिपोर्ट भेजें\" = GitHub पर नया issue खुलेगा (key / token / नंबर / OTP छिपाकर) — पढ़कर आप खुद Submit करें।")
    }
    card("क्रैश रिपोर्ट", "आपकी मर्ज़ी से — शुरू से बंद") { box ->
        val sw = switch(box, "DK बंद हो जाए तो अगली बार खुलते समय पूछो कि रिपोर्ट भेजूँ", Prefs.crashAsk(this))
        sw.setOnCheckedChangeListener { _, on -> Prefs.setCrashAsk(this, on) }
        hint(box, "बंद = crash सिर्फ़ इसी फ़ोन में रहता है (यहीं Settings में दिखता है)। चालू = सिर्फ़ पूछेगा; बिना आपके \"हाँ\" कुछ नहीं जाता।")
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
        // नवंबर 2026: "आज के स्क्रीनशॉट डिलीट करो" - यह इजाज़त हो तो Android हर बार Allow नहीं पूछता
        if (android.os.Build.VERSION.SDK_INT >= 31) row(box, button("फ़ोटो बिना पूछे मिटाना (मीडिया मैनेजमेंट)") {
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { UiLang.toast(this, "सेटिंग्स → ऐप्स → खास ऐक्सेस → मीडिया मैनेजमेंट → DK चालू करें", android.widget.Toast.LENGTH_LONG).show() }
        })
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
        (if (MediaDelete.canManage(this)) "✓" else "✗") + " मीडिया मैनेजमेंट (फ़ोटो / स्क्रीनशॉट बिना Allow पूछे मिटाना)\n" +
        (if (Updater.canInstall(this)) "✓" else "✗") + " ऐप इंस्टॉल (अपडेट)"
}

/** 15 नवंबर 2026: DK कॉलिंग की % पट्टी (छोड़ते ही सेव) */
internal fun SettingsActivity.callPct(box: LinearLayout, title: String, value: Int, save: (Int) -> Unit) {
    val lab = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt()) }
    fun show(v: Int) { lab.text = UiLang.s(title) + ": $v%" }
    show(value); box.addView(lab)
    val sb = SeekBar(this).apply {
        max = 100; progress = value.coerceIn(0, 100); contentDescription = UiLang.s(title)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { show(p) }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) { save(progress) }
        })
    }
    box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
}


/** भाषा बदलो - यह screen अभी नई बने; पीछे खुली screens लौटते ही अपने-आप नई भाषा में */
private fun SettingsActivity.pickUiLang(code: String) {
    UiLang.set(this, code)
    toast(when (code) { UiLang.EN -> "App language: English"; UiLang.HI -> "ऐप की भाषा: हिंदी"; else -> "App language: " + UiLang.label(code) })
    recreate()
}

/** 25 नवंबर 2026 (मालिक): Custom - खोजने वाला खाना + दुनिया की सारी भाषाएँ, टैप = पूरा ऐप उसी भाषा में */
private fun SettingsActivity.customLangDialog() {
    val k = com.dk.assistant.ui.ClayTheme.t
    val wrap = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val p = (16 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, 0)
    }
    val q = android.widget.EditText(this).apply {
        hint = "भाषा खोजें (जैसे: Tamil, मराठी, Français)"; setSingleLine(); setTextColor(k.text); setHintTextColor(k.gray)
    }
    wrap.addView(q)
    var shown = WorldLangs.ALL
    val ad = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.label + if (UiMt.offline(it.code)) "" else "  • AI" }.toMutableList())
    val list = android.widget.ListView(this).apply { adapter = ad; UiLang.keep(this) }
    wrap.addView(list, android.widget.LinearLayout.LayoutParams(-1, (420 * dp).toInt()))
    wrap.addView(android.widget.TextView(this).apply {
        text = "• AI = यह भाषा फ़ोन वाले अनुवाद में नहीं, DK का AI (आपकी key) अनुवाद करेगा"
        textSize = 12f; setTextColor(k.gray); setPadding(0, (6 * dp).toInt(), 0, 0)
    })
    val dlg = UiDialog(this).setTitle("Custom भाषा").setView(wrap).setNegativeButton("बंद", null).create()
    q.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) { /* सिर्फ़ afterTextChanged चाहिए */ }
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) { /* सिर्फ़ afterTextChanged चाहिए */ }
        override fun afterTextChanged(s: android.text.Editable?) {
            shown = WorldLangs.search(s?.toString().orEmpty())
            ad.clear(); ad.addAll(shown.map { it.label + if (UiMt.offline(it.code)) "" else "  • AI" }); ad.notifyDataSetChanged()
        }
    })
    list.setOnItemClickListener { _, _, pos, _ ->
        val l = shown.getOrNull(pos) ?: return@setOnItemClickListener
        dlg.dismiss()
        pickUiLang(l.code)
    }
    dlg.show()
}
