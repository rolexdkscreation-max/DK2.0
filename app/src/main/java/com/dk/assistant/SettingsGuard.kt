package com.dk.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.text.InputType
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * नवंबर 2026: Settings → स्क्रीन गार्ड (चोरी से बचाव)।
 * चालू = फ़ोन लॉक, फिर कोई उठाए / हिलाए / PIN डालने लगे / पावर मेनू खोले / चार्जर निकाले / SIM निकाले
 * तो तेज़ सायरन + "यह फ़ोन आपका नहीं है" + फ़ोटो + भरोसेमंद नंबर पर SMS (जगह के साथ)।
 * यहाँ हर बदलाव तुरंत सेव होता है (सेव बटन नहीं)।
 */
internal fun SettingsActivity.secGuard() {
    lateinit var status: TextView
    lateinit var swOn: SwitchCompat
    var quiet = false

    fun refresh() {
        status.text = Guard.status(this)
        quiet = true; swOn.isChecked = Guard.active(); quiet = false
    }

    card("स्क्रीन गार्ड", "कोई और फ़ोन छुए / उठाए तो सायरन + चेतावनी", startOpen = true) { box ->
        if (!Setup.accessibilityOn(this)) hint(box, "⚠ पहले Settings → Accessibility में DK चालू करें — उसके बिना स्क्रीन गार्ड नहीं चलेगा।")
        swOn = switch(box, "स्क्रीन गार्ड चालू", Guard.active())
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
        }
        box.addView(status)
        refresh()
        swOn.setOnCheckedChangeListener { _, on ->
            if (quiet) return@setOnCheckedChangeListener
            if (on) {
                toast(Guard.arm(this, 0, "सेटिंग्स"))
                if (Guard.faceOn(this) && !FaceAI.enrolled(this)) toast("ध्यान दें: मालिक का चेहरा सेट नहीं — नीचे \"मालिक का चेहरा सेट करें\" दबाएँ, तभी चेहरे से सायरन बंद होगा")
            } else Guard.disarm(this, "सेटिंग्स से बंद")
            status.postDelayed({ refresh() }, 300)
        }
        hint(box, "बोलकर: \"DK स्क्रीन गार्ड चालू करो\" / \"DK 1 घंटे के लिए स्क्रीन गार्ड चालू करो\"। चालू होने के 5 सेकंड बाद फ़ोन लॉक होगा — फ़ोन नीचे रख दें।")
        hint(box, "बंद करने के तरीके: फ़ोन को अपने PIN / फ़िंगरप्रिंट से खोलें • चेतावनी स्क्रीन पर कैमरे के सामने अपना चेहरा (DK ने चेहरा सीखा हो) • नीचे लिखा गुप्त शब्द बोलें।")
        row(box,
            button("अभी आज़माएँ (10 सेकंड)") { Guard.test(this); toast("10 सेकंड सायरन — अपने-आप बंद होगा") },
            button("सायरन रोकें") { Guard.disarm(this, "सेटिंग्स से रोका"); refresh() })
    }

    // नवंबर 2026 (मालिक): पहले चेहरा सेट करने की जगह ही नहीं थी (29 अक्टूबर को "चेहरा पहचान" Settings से हटी) -
    // इसलिए चेतावनी स्क्रीन मालिक को पहचान नहीं पाती थी और सायरन बंद नहीं होता था. अब यहीं, सिर्फ़ स्क्रीन गार्ड के लिए.
    card("मालिक का चेहरा", "पहली बार एक बार सेट करें — फिर अलार्म बजे और कैमरा आपको देखे तो सायरन अपने-आप बंद", startOpen = true) { box ->
        val st = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(st)
        fun refreshFace() { st.text = UiLang.s(guardFaceStatus()) }
        refreshFace()
        row(box,
            button(if (FaceAI.enrolled(this)) "चेहरा फिर से सेट करें" else "मालिक का चेहरा सेट करें") { guardFaceDialog(enroll = true) { refreshFace() } },
            button("जाँचें") {
                if (!FaceAI.enrolled(this)) { toast("पहले \"मालिक का चेहरा सेट करें\" दबाएँ"); return@button }
                guardFaceDialog(enroll = false) { refreshFace() }
            })
        row(box, button("चेहरा मिटाएँ") {
            AlertDialog.Builder(this).setTitle(UiLang.s("मालिक का चेहरा मिटाएँ?"))
                .setMessage(UiLang.s("फिर चेहरे से सायरन बंद नहीं होगा, जब तक दोबारा सेट न करें।"))
                .setPositiveButton(UiLang.s("मिटाएँ")) { _, _ -> FaceAI.forget(this); refreshFace(); toast("चेहरा मिटा दिया") }
                .setNegativeButton(UiLang.s("रहने दें"), null).show()
        })
        hint(box, "कैसे: आगे वाले कैमरे से ~8 फ़्रेम लेकर आपके चेहरे की पहचान (128 अंक) बनती है — सिर्फ़ इसी फ़ोन में, encrypted; तस्वीर सेव नहीं होती। पहली बार मॉडल (~10 MB) एक बार डाउनलोड होता है।")
        hint(box, "अलार्म बजे तो चेतावनी स्क्रीन के कैमरे में सीधे देखें — 2 बार लगातार मिलान होते ही सायरन और स्क्रीन गार्ड बंद। अंधेरे में न पहचाने तो फ़ोन PIN से खोलें या गुप्त शब्द बोलें। फ़ोटो दिखाकर धोखा यह पूरी तरह नहीं पकड़ता।")
    }

    card("कितना संवेदनशील", "उठाने / छूने पर कब बजे") { box ->
        val all = GuardRules.Sense.values().toList()
        val g = radios(box, all.map { it.name to it.hi }, Guard.sense(this).name, 7300)
        g.setOnCheckedChangeListener { _, _ ->
            Prefs.edit(this) { putString(Guard.P_SENSE, radioValue(g, all.map { it.name }, 7300)) }
            toast("सेव हो गया (अगली बार चालू करने पर लागू)")
        }
    }

    card("गुप्त शब्द और भरोसेमंद नंबर", "बोलकर सायरन बंद • चोरी पर SMS") { box ->
        val code = field(box, "गुप्त शब्द (कम से कम ${GuardRules.MIN_CODE} अक्षर)", Guard.code(this), "जैसे: नीला आसमान", secret = true)
        hint(box, "अलार्म बजे तो यह शब्द बोलें — DK सायरन बंद कर देगा। किसी को न बताएँ। (फ़ोन में PIN न हो तो गुप्त शब्द ज़रूरी है)")
        val num = field(box, "भरोसेमंद मोबाइल नंबर (घर वाले / दोस्त)", Guard.smsNumber(this), "+91XXXXXXXXXX")
        num.inputType = InputType.TYPE_CLASS_PHONE
        val swSms = switch(box, "अलार्म बजे तो इस नंबर पर SMS भेजें (सटीक GPS जगह के लिंक के साथ, फिर हर कुछ मिनट नई जगह — जब तक आप फ़ोन न खोलें)", Guard.smsOn(this))
        swSms.setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean(Guard.P_SMS_ON, on) } }
        row(box, button("सेव करें") {
            val c = code.text.toString().trim()
            val n = num.text.toString().trim()
            when {
                c.isNotEmpty() && c.replace(" ", "").length < GuardRules.MIN_CODE -> toast("गुप्त शब्द कम से कम ${GuardRules.MIN_CODE} अक्षर का रखें")
                n.isNotEmpty() && !GuardRules.validNumber(n) -> toast("मोबाइल नंबर सही नहीं लग रहा")
                else -> {
                    Prefs.edit(this) { putString(Guard.P_CODE, c); putString(Guard.P_SMS, n) }
                    toast("सेव हो गया")
                }
            }
        })
    }

    card("और सुरक्षा", "फ़ोटो • चेहरा • पावर मेनू • चार्जर • SIM") { box ->
        fun opt(t: String, key: String, on: Boolean) =
            switch(box, t, on).setOnCheckedChangeListener { _, v -> Prefs.edit(this) { putBoolean(key, v) } }
        opt("छूने वाले की फ़ोटो लें (आगे वाला कैमरा)", Guard.P_PHOTO, Guard.photoOn(this))
        opt("मालिक का चेहरा दिखे तो सायरन बंद (ऊपर \"मालिक का चेहरा\" में सेट हो)", Guard.P_FACE, Guard.faceOn(this))
        opt("लॉक में पावर मेनू / ऊपर का पैनल खुले तो बंद करो + सायरन", Guard.P_BLOCK, Guard.blockMenus(this))
        opt("चार्जर निकाले तो सायरन", Guard.P_CHARGER, Guard.chargerOn(this))
        opt("SIM निकाले तो सायरन", Guard.P_SIM, Guard.simOn(this))
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            hint(box, "⚠ फ़ोटो, SMS और सटीक जगह (GPS) के लिए परमिशन चाहिए — लोकेशन में \"सटीक\" चुनें")
            row(box, button("परमिशन दें") { androidx.core.app.ActivityCompat.requestPermissions(this, need.toTypedArray(), 15) })
        }
        hint(box, "ध्यान दें: कुछ फ़ोन में लॉक पर भी पावर बटन लंबा दबाकर फ़ोन बंद हो सकता है — DK हर फ़ोन में इसे पूरा नहीं रोक सकता। स्क्रीन गार्ड में बैटरी थोड़ी ज़्यादा लगती है।")
    }

    card("घुसपैठिए का रिकॉर्ड", "फ़ोटो और क्या-क्या हुआ") { box ->
        val info = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0) }
        box.addView(info)
        fun show() {
            val p = Guard.photos(this).size
            val l = Guard.log(this).take(15)
            info.text = "फ़ोटो: $p\n" + (if (l.isEmpty()) "अभी कुछ नहीं हुआ" else l.joinToString("\n"))
        }
        show()
        row(box,
            button("आख़िरी फ़ोटो देखें") {
                val f = Guard.photos(this).firstOrNull()
                if (f == null) toast("अभी कोई फ़ोटो नहीं")
                else {
                    val iv = ImageView(this).apply { adjustViewBounds = true; setImageBitmap(BitmapFactory.decodeFile(f.absolutePath)) }
                    AlertDialog.Builder(this).setTitle(f.name).setView(iv).setPositiveButton("ठीक है", null).show()
                }
            },
            button("सब मिटाएँ") {
                AlertDialog.Builder(this).setMessage("सारी फ़ोटो और रिकॉर्ड मिटा दें?")
                    .setPositiveButton("मिटाएँ") { _, _ -> Guard.clearRecords(this); show(); toast("मिटा दिया") }
                    .setNegativeButton("रहने दें", null).show()
            })
    }
}

private fun SettingsActivity.guardFaceStatus(): String = when {
    !FaceAI.supported -> "✗ यह फ़ोन 64-bit ARM नहीं है — चेहरा पहचान यहाँ नहीं चलेगी (PIN / गुप्त शब्द से बंद करें)"
    FaceAI.enrolled(this) && FaceAI.ready(this) -> "✓ मालिक का चेहरा सेट है — अलार्म में कैमरा आपको पहचानकर सायरन बंद करेगा"
    FaceAI.enrolled(this) -> "⚠ चेहरा सेट है पर पहचान मॉडल नहीं मिला — \"चेहरा फिर से सेट करें\" दबाएँ"
    else -> "✗ अभी मालिक का चेहरा सेट नहीं — इसके बिना कैमरा आपको पहचान नहीं पाएगा"
}

private var guardFaceCam: FaceCam? = null

/** मालिक का चेहरा सेट (enroll) या जाँचने का कैमरा वाला डायलॉग - पहले वाले \"चेहरा पहचान\" (v3.91) जैसा, सिर्फ़ स्क्रीन गार्ड में */
private fun SettingsActivity.guardFaceDialog(enroll: Boolean, done: () -> Unit) {
    if (!FaceAI.supported) { toast("यह फ़ोन 64-bit ARM नहीं है — चेहरा पहचान यहाँ नहीं चलेगी"); return }
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 16); toast("कैमरा की परमिशन दें, फिर दोबारा दबाएँ"); return
    }
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
    val pv = androidx.camera.view.PreviewView(this).apply {
        implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
    }
    wrap.addView(pv, LinearLayout.LayoutParams(-1, (300 * dp).toInt()))
    val tv = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, (12 * dp).toInt(), 0, 0) }
    wrap.addView(tv)
    val dlg = AlertDialog.Builder(this).setTitle(UiLang.s(if (enroll) "मालिक का चेहरा सेट करें" else "चेहरा जाँचें")).setView(wrap)
        .setNegativeButton(UiLang.s("बंद करें"), null).show()
    val ui = { t: String -> runOnUiThread { tv.text = UiLang.s(t) } }
    val prints = ArrayList<FloatArray>()
    var saved = false
    fun startCam() {
        ui(if (enroll) "फ़ोन आँखों के सामने रखें, रोशनी में सीधे कैमरे में देखें…" else "कैमरे में देखें…")
        val cam = FaceCam(this, pv) { b ->
            if (saved) return@FaceCam
            val d = FaceAI.detect(this, b, minScore = 0.6f)
            if (enroll) {
                FaceMath.enrollProblem(d, b.width)?.let { ui(it + "  (${prints.size}/${FaceMath.ENROLL_FRAMES})"); return@FaceCam }
                val e = FaceAI.embed(this, b, d!!) ?: run { ui(FaceAI.status(this)); return@FaceCam }
                val mean = FaceMatch.aggregateEmbeddings(prints)
                if (mean != null && FaceMatch.cosineSimilarity(mean, e) < FaceMath.ENROLL_SAME) {
                    prints.clear(); ui("बीच में चेहरा बदल गया — फिर से शुरू, अकेले कैमरे में देखें"); return@FaceCam
                }
                prints.add(e)
                if (prints.size < FaceMath.ENROLL_FRAMES) { ui("सीख रहा हूँ… ${prints.size}/${FaceMath.ENROLL_FRAMES} — सिर थोड़ा-सा दाएँ-बाएँ घुमाएँ"); return@FaceCam }
                saved = true
                FaceAI.save(this, FaceMatch.aggregateEmbeddings(prints)!!)
                Guard.note(this, "मालिक का चेहरा सेट हुआ")
                ui("हो गया — मालिक का चेहरा सेट। अब अलार्म में कैमरा आपको देखते ही सायरन बंद करेगा। \"जाँचें\" दबाकर देख सकते हैं।")
                runOnUiThread { guardFaceCam?.stop(); done() }
            } else {
                if (d == null) { ui("चेहरा नहीं दिखा — सामने आएँ"); return@FaceCam }
                val e = FaceAI.embed(this, b, d) ?: run { ui(FaceAI.status(this)); return@FaceCam }
                val sc = FaceAI.score(this, e) ?: return@FaceCam
                val ok = FaceMath.decide(sc, Prefs.faceStrict(this)) == MatchResult.ACCESS_GRANTED
                ui((if (ok) "✓ आप हैं — मालिक पहचाने गए (सायरन बंद होगा)" else "✗ यह चेहरा मालिक से नहीं मिला") +
                    "\nमिलान: ${(sc * 100).toInt()}% (पास: ${((if (Prefs.faceStrict(this)) FaceMath.STRICT else FaceMath.MATCH) * 100).toInt()}% से ऊपर)")
            }
        }
        guardFaceCam?.stop(); guardFaceCam = cam
        cam.start { why -> ui("कैमरा नहीं खुला: $why") }
    }
    val job = lifecycleScope.launch {
        if (!FaceAI.hasModels(this@guardFaceDialog)) {
            tv.text = UiLang.s("चेहरा पहचान मॉडल डाउनलोड हो रहा है (~10 MB, एक बार)…")
            val err = withContext(Dispatchers.IO) {
                FaceAI.download(this@guardFaceDialog) { pct -> runOnUiThread { tv.text = UiLang.s("चेहरा पहचान मॉडल डाउनलोड हो रहा है (~10 MB, एक बार)…") + " $pct%" } }
            }
            if (err != null) { tv.text = UiLang.s(err); return@launch }
        }
        if (!FaceAI.ready(this@guardFaceDialog)) { tv.text = UiLang.s(FaceAI.status(this@guardFaceDialog)); return@launch }
        startCam()
    }
    dlg.setOnDismissListener { job.cancel(); guardFaceCam?.stop(); guardFaceCam = null; done() }
}
