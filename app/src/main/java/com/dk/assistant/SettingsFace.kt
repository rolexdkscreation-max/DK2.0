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
        flagSwitch(box, "DK लाइव से पूछकर पहचानना (\"DK, देखो कौन है\")", FeatureFlags.FACE_ON) { on ->
            if (on) askMissing(66, android.Manifest.permission.CAMERA)
        }
        hint(box, "कैसे: आगे वाले कैमरे से ~8 फ़्रेम लेकर चेहरे की पहचान (128 अंक) बनती है — सिर्फ़ इसी फ़ोन में, encrypted; तस्वीर सेव नहीं होती। " +
            "पहली बार पहचान मॉडल (~10 MB) इंटरनेट से एक बार डाउनलोड होता है (SHA-256 जाँच के साथ)।")
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
                row(list, button("✕ ${p.name}") {
                    AlertDialog.Builder(this).setTitle(UiLang.s("${p.name} का चेहरा मिटाएँ?"))
                        .setPositiveButton(UiLang.s("मिटाएँ")) { _, _ -> FacePeople.remove(this, p.name); refreshList() }
                        .setNegativeButton(UiLang.s("रहने दें"), null).show()
                })
            }
        }
        refreshList()
        val ed = EditText(this).apply { hint = UiLang.s("नाम (जैसे: मम्मी, राहुल)"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()) }
        box.addView(ed)
        row(box, button("इस नाम से चेहरा सीखें") {
            val n = FacePeople.cleanName(ed.text.toString()) ?: run { toast("पहले नाम लिखें"); return@button }
            GuardFaceSetup.dialog(this, enroll = true, person = n) { ed.setText(""); refreshList() }
        })
        hint(box, "वह व्यक्ति कैमरे के सामने ~8 फ़्रेम तक देखे। उसी नाम से दोबारा सिखाने पर पुराना बदल जाता है। \"कौन है?\" या DK लाइव (\"देखो कौन है\") नाम बताएगी।")
    }
}
