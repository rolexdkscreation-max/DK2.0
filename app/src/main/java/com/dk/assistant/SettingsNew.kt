package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.GroupReport
import com.dk.assistant.plus.Mail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 15 नवंबर 2026 (मालिक की सूची, Market / Marketing Suite छोड़कर): नई सुविधाओं के Settings कार्ड - अपनी सही पुरानी जगह पर.
 *  - ईमेल (IMAP/SMTP)       → DK कंप्यूटर
 *  - WhatsApp ग्रुप रिपोर्ट  → ऑटो रिप्लाई
 * हर स्विच शुरू में बंद; DK लाइव इन्हें dk_settings से (दो बार पक्का करके) चालू-बंद कर सकता है और tools से चला सकता है.
 */
internal fun SettingsActivity.secEmail() {
    card("ईमेल (IMAP/SMTP)", "DK आपके ईमेल पढ़े, जवाब भेजे, स्पैम हटाए — आपके अपने App Password से (मुफ़्त)") { box ->
        flagSwitch(box, "बोलकर ईमेल पढ़ना / भेजना चालू", FeatureFlags.EMAIL_ON)
        val addr = field(box, "ईमेल पता", Prefs.str(this, Mail.ADDR, ""), "you@gmail.com")
        val pass = field(box, "App Password (सामान्य पासवर्ड नहीं)", Prefs.str(this, Mail.PASS, ""), "16 अक्षर", secret = true)
        val sign = field(box, "हस्ताक्षर (Signature)", Prefs.str(this, Mail.SIGN, ""), "जैसे: — धीरज", multiLine = true)
        val imap = field(box, "IMAP सर्वर (खाली = पते से अपने-आप: Gmail, Yahoo, Outlook, Zoho)", Prefs.str(this, Mail.IMAP_HOST, ""), "imap.example.com:993")
        val smtp = field(box, "SMTP सर्वर (खाली = अपने-आप)", Prefs.str(this, Mail.SMTP_HOST, ""), "smtp.example.com:465")
        fun saveMail() = Prefs.edit(this) {
            putString(Mail.ADDR, addr.text.toString().trim())
            putString(Mail.PASS, pass.text.toString().trim())
            putString(Mail.SIGN, sign.text.toString().trim())
            putString(Mail.IMAP_HOST, imap.text.toString().trim())
            putString(Mail.SMTP_HOST, smtp.text.toString().trim())
        }
        val out = status(box)
        row(box, button("सेव करें") { saveMail(); toast("सेव हो गया") }, button("जाँच: लॉगिन") {
            saveMail(); out.text = "जुड़ रही है…"
            lifecycleScope.launch {
                out.text = withContext(Dispatchers.IO) { try { "✓ " + Mail.test(this@secEmail) } catch (e: Exception) { "✗ " + (e.message ?: "नहीं जुड़ा") } }
            }
        })
        row(box, button("Gmail App Password बनाएँ") { openLink("https://myaccount.google.com/apppasswords") })
        hint(box, "Gmail: पहले 2-Step Verification चालू करें, फिर App Password बनाकर यहाँ डालें। Password फ़ोन के ताले (encrypted) में रहता है। " +
            "बोलें: \"मेरे ईमेल पढ़ो\", \"राम को मेल भेजो\" - भेजने / हटाने से पहले DK सुनाकर पूछती है।")
    }
}

internal fun SettingsActivity.secGroupReport() {
    card("WhatsApp ग्रुप रिपोर्ट", "रोज़ की रिपोर्ट का टेम्पलेट - बोलकर भरो, सुनकर \"हाँ\" पर ग्रुप में") { box ->
        flagSwitch(box, "बोलकर ग्रुप रिपोर्ट चालू", FeatureFlags.WA_GROUP_REPORT)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun refresh() {
            list.removeAllViews()
            val ts = GroupReport.all(this)
            if (ts.isEmpty()) list.addView(TextView(this).apply { text = UiLang.s("अभी कोई टेम्पलेट नहीं"); textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
            ts.forEach { t ->
                list.addView(TextView(this).apply {
                    text = "${t.name} → ${t.group}\n" + UiLang.s("खाने: ") + GroupReport.Rules.fields(t.format).joinToString().ifEmpty { "-" }
                    textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
                })
                row(list, button("✕ ${t.name} मिटाएँ") {
                    AlertDialog.Builder(this).setTitle(UiLang.s("${t.name} मिटाएँ?"))
                        .setPositiveButton(UiLang.s("मिटाएँ")) { _, _ -> GroupReport.remove(this, t.name); refresh() }
                        .setNegativeButton(UiLang.s("रहने दें"), null).show()
                })
            }
        }
        refresh()
        val name = field(box, "रिपोर्ट का नाम", "", "जैसे: बिक्री रिपोर्ट")
        val group = field(box, "WhatsApp ग्रुप का नाम (ठीक वैसा जैसा WhatsApp में)", "", "जैसे: दुकान टीम")
        val fmt = field(box, "रिपोर्ट का रूप - खाने {ब्रैकेट} में", "", "आज ({तारीख}) की बिक्री\nकुल: {कुल}\nनकद: {नकद}", multiLine = true)
        row(box, button("टेम्पलेट सेव करें") {
            val n = name.text.toString().trim(); val g = group.text.toString().trim(); val f = fmt.text.toString()
            when {
                n.isEmpty() || g.isEmpty() -> toast("नाम और ग्रुप दोनों भरें")
                GroupReport.Rules.fields(f).isEmpty() -> toast("रूप में कम से कम एक {खाना} डालें")
                else -> { GroupReport.save(this, GroupReport.Template(n, g, f)); name.setText(""); group.setText(""); fmt.setText(""); refresh(); toast("सेव हो गया") }
            }
        })
        hint(box, "बोलें: \"DK, बिक्री रिपोर्ट भरो - कुल 5000, नकद 2000\"। {तारीख} अपने-आप आज की। कागज़ / स्क्रीन पर लिखे आँकड़े DK लाइव कैमरे से पढ़कर भी भर सकती है। " +
            "भेजने से पहले DK पूरी रिपोर्ट सुनाकर पूछती है; भेजना Accessibility से होता है।")
    }
}

/** 15 नवंबर 2026 (मालिक: "अलग 5-टैब वाली नीचे की पट्टी भी बनाओ") - होम • स्कैन • माइक • चैट • मेमोरी */
internal fun SettingsActivity.secHomeTabs() {
    card("नीचे की 5-टैब पट्टी", com.dk.assistant.ui.HomeTabs.order().joinToString(" • ")) { box ->
        flagSwitch(box, "पट्टी छुपाएँ (बंद = पट्टी दिखे)", FeatureFlags.HIDE_HOME_TABS)
        hint(box, "होम = ऊपर लौटें, स्कैन = DK लेंस (कैमरा), माइक = DK लाइव, चैट = पूरी चैट, मेमोरी = DK की यादें। " +
            "कीबोर्ड खुलने पर पट्टी अपने-आप छुपती है। बोलें: \"DK, नीचे की पट्टी बंद करो\" (दो बार पक्का पूछेगा)।")
    }
}
