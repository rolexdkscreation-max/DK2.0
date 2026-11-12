package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.autoreply.AutoReply

/**
 * नवंबर 2026 (मालिक): Settings → "ऑटो रिप्लाई" (ऑटोमेशन के अंदर, अपना अलग पेज).
 * फ़ोन में जो मैसेज वाले ऐप हैं (WhatsApp, WhatsApp Business, Telegram, Messenger...) वे अपने-आप यहाँ आते हैं, हर एक का
 * अपना स्विच (शुरू में बंद). चालू = उस ऐप का नया मैसेज आते ही DK आपकी ओर से, आपके ही ढंग में, सिर्फ़ उसी बात का जवाब देता है.
 */
internal fun SettingsActivity.secAutoReply() {
    card("ऑटो रिप्लाई कैसे काम करता है", "मैसेज आए तो DK आपकी ओर से, आपके ही ढंग में, सिर्फ़ उसी बात का जवाब", startOpen = true) { box ->
        val access = DKNotifListener.enabled(this)
        label(box, if (access) "✓ DK को नोटिफ़िकेशन पढ़ने की इजाज़त है" else "✗ नोटिफ़िकेशन की इजाज़त नहीं — इसके बिना ऑटो रिप्लाई नहीं चलेगा")
        if (!access) row(box, button("इजाज़त दें") { DKNotifListener.openSettings(this) })
        hint(box, "जिस ऐप का स्विच चालू है, उसका नया मैसेज आते ही DK पूरी बातचीत पढ़कर जवाब लिखता है और नोटिफ़िकेशन के " +
            "\"जवाब दें\" से भेज देता है — स्क्रीन नहीं छीनता, कोई ऐप नहीं खोलता, आपका चल रहा काम नहीं रुकता।\n\n" +
            "• सिर्फ़ वही जवाब जो सामने वाले ने पूछा/कहा — DK अपनी तरफ़ से कोई नई बात, वादा, पैसा, समय या पता नहीं बोलता। पता न हो तो " +
            "\"थोड़ी देर में बताता हूँ\" जैसा छोटा जवाब।\n" +
            "• आपके भेजे पुराने मैसेज देखकर आपका लिखने का ढंग सीखता है (भाषा, लंबाई, शब्द, इमोजी) — उसी तरह लिखता है।\n" +
            "• ग्रुप में जवाब नहीं। पैसे / OTP / बैंक / उधार वाली बात पर खुद जवाब नहीं — आपको बताता है।\n" +
            "• WhatsApp पर \"अपने-आप बात\" या चैटिंग मोड चालू हो तो WhatsApp वही सँभालता है (दो जवाब कभी नहीं)।")
    }

    card("ऑटो रिप्लाई चालू / बंद", "एक मुख्य स्विच — बंद = किसी भी ऐप में अपने-आप जवाब नहीं", startOpen = true) { box ->
        val sw = switch(box, "ऑटो रिप्लाई", AutoReply.on(this))
        sw.setOnCheckedChangeListener { _, on -> AutoReply.setOn(this, on) }
        hint(box, "मुख्य स्विच चालू करने के बाद नीचे जिस ऐप पर चाहिए, सिर्फ़ उसी का स्विच चालू करें।")
    }

    card("किन ऐप पर ऑटो रिप्लाई", "फ़ोन में मिले मैसेज वाले ऐप — हर एक का अपना स्विच (शुरू में बंद)", startOpen = true) { box ->
        val apps = AutoReply.apps(this)
        if (apps.isEmpty()) hint(box, "फ़ोन में कोई मैसेज वाला ऐप नहीं मिला (WhatsApp, Telegram, Messenger...). ऐप डालने के बाद यह पेज फिर खोलें।")
        apps.forEach { a ->
            val sw = switch(box, a.name, AutoReply.appOn(this, a.pkg))
            sw.setOnCheckedChangeListener { _, on -> AutoReply.setApp(this, a.pkg, on) }
        }
        hint(box, "सूची अपने-आप बनती है: जो ऐप फ़ोन में हैं वही दिखते हैं। कोई और चैट ऐप (जिसके नोटिफ़िकेशन में \"जवाब दें\" हो) " +
            "का मैसेज आने पर वह भी यहाँ जुड़ जाता है।")
    }

    card("आपका लिखने का ढंग", "DK ने आपके भेजे मैसेज से कितना सीखा • मिटाएँ") { box ->
        val n = AutoReply.style(this).size
        val res = TextView(this).apply {
            text = if (n == 0) "अभी कोई नमूना नहीं — आप जैसे-जैसे मैसेज भेजेंगे (और उनका नोटिफ़िकेशन आएगा), DK आपका ढंग सीखेगा। तब तक Hinglish में छोटा जवाब।"
            else "$n मैसेज से आपका ढंग सीखा है।"
            textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
        }
        box.addView(res, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val ed = field(box, "अपने जैसे कुछ मैसेज लिखें (हर लाइन एक मैसेज)", "", "जैसे: haan bhai aa raha hu 10 min me", multiLine = true)
        row(box, button("ढंग में जोड़ें") {
            val k = AutoReply.addSamples(this, ed.text.toString()); ed.setText(""); res.text = "✓ जुड़ गया — अब $k नमूने"
        }, button("सीखा हुआ ढंग मिटाएँ") { AutoReply.clearStyle(this); res.text = "✓ मिटा दिया — DK फिर से सीखेगा" })
    }
}
