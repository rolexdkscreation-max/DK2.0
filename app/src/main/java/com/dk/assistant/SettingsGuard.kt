package com.dk.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.text.InputType
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * नवंबर 2026: Settings → गार्ड मोड (चोरी से बचाव)।
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

    card("गार्ड मोड", "कोई और फ़ोन छुए / उठाए तो सायरन + चेतावनी", startOpen = true) { box ->
        if (!Setup.accessibilityOn(this)) hint(box, "⚠ पहले Settings → Accessibility में DK चालू करें — उसके बिना गार्ड मोड नहीं चलेगा।")
        swOn = switch(box, "गार्ड मोड चालू", Guard.active())
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
        }
        box.addView(status)
        refresh()
        swOn.setOnCheckedChangeListener { _, on ->
            if (quiet) return@setOnCheckedChangeListener
            if (on) toast(Guard.arm(this, 0, "सेटिंग्स")) else Guard.disarm(this, "सेटिंग्स से बंद")
            status.postDelayed({ refresh() }, 300)
        }
        hint(box, "बोलकर: \"DK गार्ड मोड चालू करो\" / \"DK 1 घंटे के लिए गार्ड मोड चालू करो\"। चालू होने के 5 सेकंड बाद फ़ोन लॉक होगा — फ़ोन नीचे रख दें।")
        hint(box, "बंद करने के तरीके: फ़ोन को अपने PIN / फ़िंगरप्रिंट से खोलें • चेतावनी स्क्रीन पर कैमरे के सामने अपना चेहरा (DK ने चेहरा सीखा हो) • नीचे लिखा गुप्त शब्द बोलें।")
        row(box,
            button("अभी आज़माएँ (10 सेकंड)") { Guard.test(this); toast("10 सेकंड सायरन — अपने-आप बंद होगा") },
            button("सायरन रोकें") { Guard.disarm(this, "सेटिंग्स से रोका"); refresh() })
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
        val swSms = switch(box, "अलार्म बजे तो इस नंबर पर SMS भेजें (जगह के लिंक के साथ)", Guard.smsOn(this))
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
        opt("मालिक का चेहरा दिखे तो सायरन बंद (चेहरा DK में सीखा हो)", Guard.P_FACE, Guard.faceOn(this))
        opt("लॉक में पावर मेनू / ऊपर का पैनल खुले तो बंद करो + सायरन", Guard.P_BLOCK, Guard.blockMenus(this))
        opt("चार्जर निकाले तो सायरन", Guard.P_CHARGER, Guard.chargerOn(this))
        opt("SIM निकाले तो सायरन", Guard.P_SIM, Guard.simOn(this))
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            hint(box, "⚠ फ़ोटो, SMS और जगह के लिए परमिशन चाहिए")
            row(box, button("परमिशन दें") { androidx.core.app.ActivityCompat.requestPermissions(this, need.toTypedArray(), 15) })
        }
        hint(box, "ध्यान दें: कुछ फ़ोन में लॉक पर भी पावर बटन लंबा दबाकर फ़ोन बंद हो सकता है — DK हर फ़ोन में इसे पूरा नहीं रोक सकता। गार्ड मोड में बैटरी थोड़ी ज़्यादा लगती है।")
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
