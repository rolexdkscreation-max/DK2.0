package com.dk.assistant

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dk.assistant.plus.FeatureFlags

/**
 * 15 नवंबर 2026 (मालिक): "सेटिंग में जो चेहरा पहचानने वाला है - फ़ेस रजिस्ट्रेशन - वह चाहिए, एग्ज़ैक्ट. पहचानना चाहिए."
 * (v3.99 में यह section मालिक ने हटवाया था - अब मालिक ने ही वापस माँगा. पुराना हाल: branch backup/dk-face-settings-1115.)
 *
 * सुरक्षा → चेहरा पहचान:
 *  - Boss (मालिक) का चेहरा: सेट / जाँचें / मिटाएँ - वही [FaceAI] पहचान जो टच मोड की चेतावनी स्क्रीन इस्तेमाल करती है
 *  - जान-पहचान वाले: नाम डालकर उनका चेहरा सीखना ([FacePeople]), सूची, मिटाना
 *  - "कौन है?" - कैमरा देखकर बताए: आप / सेव किया व्यक्ति / अनजान
 *  - DK लाइव से: "DK, देखो कौन है" (face_who tool - नीचे वाला स्विच चालू हो तब)
 * मॉडल (~10 MB) पहली बार इंटरनेट से (SHA-256 जाँच के साथ) - APK में नहीं. तस्वीर कभी सेव नहीं होती, सिर्फ़ अंक (encrypted).
 */
internal fun SettingsActivity.secFace() {
    card("चेहरा पहचान", "Boss का चेहरा सेट करें — DK और टच मोड आपको पहचानें", startOpen = true) { box ->
        val st = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(st)
        fun refreshFace() { st.text = UiLang.s(GuardFaceSetup.status(this).replace("मालिक", "Boss")) }
        refreshFace()
        row(box,
            button(if (GuardFaceSetup.enrolled(this)) "Boss का चेहरा फिर से सेट करें" else "Boss का चेहरा सेट करें") { GuardFaceSetup.dialog(this, enroll = true) { refreshFace() } },
            button("जाँचें") {
                if (!GuardFaceSetup.enrolled(this)) { toast("पहले \"Boss का चेहरा सेट करें\" दबाएँ"); return@button }
                GuardFaceSetup.dialog(this, enroll = false) { refreshFace() }
            })
        row(box,
            button("कौन है?") { GuardFaceSetup.dialog(this, enroll = false, who = true) { refreshFace() } },
            button("Boss का चेहरा मिटाएँ") {
                AlertDialog.Builder(this).setTitle(UiLang.s("Boss का चेहरा मिटाएँ?"))
                    .setMessage(UiLang.s("फिर चेहरे से टच मोड का सायरन बंद नहीं होगा और DK आपको चेहरे से नहीं पहचानेगी, जब तक दोबारा सेट न करें।"))
                    .setPositiveButton(UiLang.s("मिटाएँ")) { _, _ -> GuardFaceSetup.forget(this); refreshFace(); toast("चेहरा मिटा दिया") }
                    .setNegativeButton(UiLang.s("रहने दें"), null).show()
            })
        switch(box, "सख़्त मिलान (ज़्यादा पक्का, कभी-कभी दोबारा देखना पड़े)", Prefs.faceStrict(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("face_strict", on) } }
        // 15 नवंबर 2026 (मालिक: "फ़ोटो या वीडियो दिखाए फिर भी न खुले")
        switch(box, "AI anti-spoof — फ़ोटो / वीडियो / स्क्रीन वाला चेहरा न माने", Prefs.faceSpoofOn(this))
            .setOnCheckedChangeListener { _, on ->
                Prefs.edit(this) { putBoolean("face_spoof_on", on) }
                if (on && FaceAI.supported && !FaceAI.hasSpoof(this)) {
                    toast("anti-spoof मॉडल (~3.5 MB) डाउनलोड हो रहा है…")
                    val app = applicationContext
                    Thread { val err = FaceAI.downloadSpoof(app); runOnUiThread { toast(err?.let { "anti-spoof मॉडल नहीं आया: $it" } ?: "✓ anti-spoof मॉडल तैयार") } }.apply { isDaemon = true }.start()
                }
            }
        switch(box, "DK लेंस में futuristic चेहरा स्कैन (Boss / दोस्त / परिवार पहचाने)", Prefs.faceHudOn(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("face_hud_on", on) } }
        flagSwitch(box, "DK लाइव से पूछकर पहचानना (\"DK, देखो कौन है\")", FeatureFlags.FACE_ON) { on ->
            if (on) askMissing(66, android.Manifest.permission.CAMERA)
        }
        hint(box, "कैसे: आगे वाले कैमरे से ~8 फ़्रेम लेकर चेहरे की पहचान (128 अंक) बनती है — सिर्फ़ इसी फ़ोन में, encrypted; तस्वीर सेव नहीं होती। " +
            "पहली बार पहचान मॉडल (~10 MB) इंटरनेट से एक बार डाउनलोड होता है (SHA-256 जाँच के साथ)।")
        hint(box, "फ़ोटो / वीडियो से न खुले: टच मोड में हर बार नया क्रम (पहले बाएँ या पहले दाएँ सिर घुमाना) + AI anti-spoof मॉडल " +
            "(MiniFASNet V2 + V1SE, Silent-Face, ~3.5 MB) जो असली त्वचा और काग़ज़ की फ़ोटो / फ़ोन-स्क्रीन में फ़र्क करता है। " +
            "दोनों पास हों तभी हरा \"BOSS AUTHORIZED\", वरना लाल \"DECLINED\"। \"जाँचें\" दबाकर अपनी फ़ोटो दिखाकर खुद आज़माएँ।")
        if (FaceAI.supported && Prefs.faceSpoofOn(this) && FaceAI.hasModels(this) && !FaceAI.hasSpoof(this)) {
            val app = applicationContext
            Thread { FaceAI.downloadSpoof(app) }.apply { isDaemon = true }.start()
        }
        hint(box, "अच्छी पहचान के लिए: रोशनी में, चश्मा/मास्क हटाकर, फ़ोन आँखों के सामने। मिलान ${(FaceMath.MATCH * 100).toInt()}% (सख़्त: ${(FaceMath.STRICT * 100).toInt()}%) से ऊपर = पहचाना। दो लोग लगभग बराबर मिलें तो DK \"पक्का नहीं\" कहती है — ग़लत नाम नहीं बोलती।")
    }

    card("जान-पहचान वाले चेहरे", "परिवार / दोस्त — नाम के साथ चेहरा सेव करें (ज़्यादा से ज़्यादा ${FacePeople.MAX})") { box ->
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun refreshList() {
            list.removeAllViews()
            val ps = FacePeople.all(this)
            if (ps.isEmpty()) list.addView(TextView(this).apply { text = UiLang.s("अभी कोई सेव नहीं"); textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
            ps.forEach { p ->
                val r = p.relation().let { if (it.isBlank()) "" else " — $it" }
                row(list, button("${p.name}$r") {
                    // 15 नवंबर 2026: छूने पर रिश्ता बदलें / मिटाएँ
                    AlertDialog.Builder(this).setTitle(UiLang.s("${p.name}$r"))
                        .setItems(arrayOf(UiLang.s("रिश्ता बदलें"), UiLang.s("चेहरा फिर से सिखाएँ"), UiLang.s("मिटाएँ"))) { _, which ->
                            when (which) {
                                0 -> pickRelation(p.name) { k, rl -> FacePeople.setRelation(this, p.name, k, rl); refreshList() }
                                1 -> GuardFaceSetup.dialog(this, enroll = true, person = p.name, kind = p.kind, rel = p.rel) { refreshList() }
                                else -> AlertDialog.Builder(this).setTitle(UiLang.s("${p.name} का चेहरा मिटाएँ?"))
                                    .setPositiveButton(UiLang.s("मिटाएँ")) { _, _ -> FacePeople.remove(this, p.name); refreshList() }
                                    .setNegativeButton(UiLang.s("रहने दें"), null).show()
                            }
                        }.setNegativeButton(UiLang.s("बंद करें"), null).show()
                })
            }
        }
        refreshList()
        val ed = EditText(this).apply { hint = UiLang.s("नाम (जैसे: मम्मी, राहुल)"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()) }
        box.addView(ed)
        row(box, button("इस नाम से चेहरा सीखें") {
            val n = FacePeople.cleanName(ed.text.toString()) ?: run { toast("पहले नाम लिखें"); return@button }
            // 15 नवंबर 2026 (मालिक: "फ़्रेंड है या फ़ैमिली मेंबर, फ़ैमिली को क्या बोलते हैं"): पहले रिश्ता, फिर चेहरा
            pickRelation(n) { k, rl -> GuardFaceSetup.dialog(this, enroll = true, person = n, kind = k, rel = rl) { ed.setText(""); refreshList() } }
        })
        hint(box, "वह व्यक्ति कैमरे के सामने ~8 फ़्रेम तक देखे। पहले पूछा जाएगा: दोस्त / परिवार (मम्मी, पापा, भाई, बहन…) / और कोई। " +
            "उसी नाम से दोबारा सिखाने पर पुराना बदल जाता है। सूची में नाम छूकर रिश्ता बदलें या मिटाएँ।")
        hint(box, "DK लेंस (DK लाइव का कैमरा) में एक साथ ${FaceMath.MAX_FACES} तक चेहरे स्कैन होते हैं — हर चेहरे पर नाम + रिश्ता, " +
            "आप पर हरा \"BOSS\"; DK बोल भी सकती है \"ये राहुल हैं — आपके दोस्त\"। \"कौन है?\" भी सारे चेहरे बताता है।")
    }
    watchmanCard()
}

/**
 * 15 नवंबर 2026 (मालिक): "DK वॉचमैन - फ़ोन सामने रख दूँ, कौन आया-गया DK देखता रहे, बाद में पूछूँ तो बताए - सिर्फ़ Boss को".
 * secFace() के आख़िर से बुलाया जाता है.
 */
internal fun SettingsActivity.watchmanCard() {
    card("DK वॉचमैन", "कौन आया, कितनी बार, क्या किया — रिपोर्ट सिर्फ़ Boss के चेहरे से") { box ->
        flagSwitch(box, "DK वॉचमैन (\"वॉचमैन चालू करो\" / \"कौन आया था?\")", FeatureFlags.FACE_WATCHMAN) { on ->
            if (on) askMissing(67, android.Manifest.permission.CAMERA)
        }
        row(box,
            button("वॉचमैन शुरू करें") {
                if (!FeatureFlags.on(this, FeatureFlags.FACE_WATCHMAN)) { toast("पहले ऊपर वाला स्विच चालू करें"); return@button }
                if (!GuardFaceSetup.enrolled(this)) { toast("पहले Boss का चेहरा सेट करें — रिपोर्ट उसी से खुलती है"); return@button }
                val r = WatchmanActivity.start(this); if (r.startsWith("ERROR")) toast(r)
            },
            button("रिपोर्ट देखें (Boss चेहरा)") {
                if (!GuardFaceSetup.enrolled(this)) { toast("पहले Boss का चेहरा सेट करें"); return@button }
                BossCheckActivity.open(this, BossCheckActivity.MODE_REPORT)
            })
        row(box, button("रिकॉर्ड मिटाएँ (Boss चेहरा)") {
            if (!GuardFaceSetup.enrolled(this)) { toast("पहले Boss का चेहरा सेट करें"); return@button }
            BossCheckActivity.open(this, BossCheckActivity.MODE_CLEAR)
        })
        hint(box, "कैसे: वॉचमैन चालू करके फ़ोन ऐसी जगह रखें जहाँ से आने-जाने वालों के चेहरे दिखें (चार्जर लगा दें)। स्क्रीन जागी रहती है, " +
            "30 सेकंड बाद लगभग काली। DK हर ~1 सेकंड देखती है — Boss / सेव किए दोस्त-परिवार / अनजान (अनजान 1, 2… — वही दोबारा आए तो वही नंबर)। " +
            "हर आने पर: कब आया, कितनी देर रुका, फ़ोन के पास आया, फ़ोन छुआ, साथ में कौन — और एक फ़ोटो। स्क्रीन पर किसी का नाम नहीं दिखता।")
        hint(box, "रिपोर्ट / बंद / मिटाना = हर बार सख़्त Boss जाँच: अकेला चेहरा, ${(FaceMath.STRICT * 100).toInt()}% मिलान, हर बार नए क्रम में सिर घुमाना, " +
            "और AI anti-spoof (Settings में बंद हो तब भी) — फ़ोटो / वीडियो से नहीं खुलता। Boss न निकले तो DK कुछ नहीं बताती, और यह कोशिश भी रिपोर्ट में लिखी जाती है। " +
            "सब कुछ सिर्फ़ इसी फ़ोन में, ${Watchman.KEEP_DAYS} दिन तक।")
    }
}

/** 15 नवंबर 2026: दोस्त / परिवार (कौन-सा रिश्ता) / और कोई - [done] (kind, rel) */
internal fun SettingsActivity.pickRelation(name: String, done: (String, String) -> Unit) {
    val opts = arrayOf("दोस्त", "परिवार (मम्मी, पापा, भाई, बहन…)", "और कोई (ख़ुद लिखें)")
    AlertDialog.Builder(this).setTitle(UiLang.s("$name आपके क्या लगते हैं?"))
        .setItems(opts.map { UiLang.s(it) }.toTypedArray()) { _, which ->
            when (which) {
                0 -> done(FacePeople.FRIEND, "")
                1 -> {
                    val rels = FacePeople.FAMILY_RELS + "और (लिखें)"
                    AlertDialog.Builder(this).setTitle(UiLang.s("$name — परिवार में कौन?"))
                        .setItems(rels.map { UiLang.s(it) }.toTypedArray()) { _, i ->
                            if (i < FacePeople.FAMILY_RELS.size) done(FacePeople.FAMILY, FacePeople.FAMILY_RELS[i])
                            else askRelText(name, FacePeople.FAMILY, done)
                        }.show()
                }
                else -> askRelText(name, FacePeople.OTHER, done)
            }
        }.setNegativeButton(UiLang.s("रहने दें"), null).show()
}

private fun SettingsActivity.askRelText(name: String, kind: String, done: (String, String) -> Unit) {
    val ed = EditText(this).apply { hint = UiLang.s(if (kind == FacePeople.FAMILY) "रिश्ता (जैसे: ताऊ, सास)" else "रिश्ता (जैसे: ऑफ़िस वाले, पड़ोसी)"); inputType = InputType.TYPE_CLASS_TEXT }
    AlertDialog.Builder(this).setTitle(UiLang.s("$name — रिश्ता")).setView(ed)
        .setPositiveButton(UiLang.s("ठीक")) { _, _ -> done(kind, ed.text.toString().trim().take(30)) }
        .setNegativeButton(UiLang.s("रहने दें"), null).show()
}
