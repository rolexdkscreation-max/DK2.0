package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.insights.InsightsAgent
import com.dk.assistant.insights.Platform

/**
 * नवंबर 2026 (मालिक): Settings → "सोशल रिव्यू" - इंस्टाग्राम / फेसबुक / यूट्यूब का पूरा रिव्यू.
 * हर प्लेटफ़ॉर्म पर आँकड़े कहाँ से लें: दोनों (API + ऐप की स्क्रीन) / सिर्फ़ API / सिर्फ़ स्क्रीन. [InsightsAgent]
 */
internal fun SettingsActivity.secSocial() {
    card("सोशल रिव्यू कैसे करें", "एक बार बोलें — DK खुद ऐप खोलकर हर रील / वीडियो के आँकड़े देखकर पूरी रिपोर्ट बताएगा", startOpen = true) { box ->
        hint(box, "बोलें: \"हे DK, इंस्टाग्राम का पूरा रिव्यू करो\", \"यूट्यूब स्टूडियो चेक करो - कौन सा वीडियो चला\", " +
            "\"फेसबुक पर रीच क्यों गिर रही है\", \"तीनों सोशल मीडिया का एनालिसिस करो\". DK लाइव में भी यही बोलें।\n\n" +
            "DK सिर्फ़ पढ़ता है — कुछ पोस्ट, लाइक, कमेंट, डिलीट या सेटिंग नहीं बदलता। काम के बीच फ़ोन न छुएँ (कुछ मिनट)। " +
            "पूरी रिपोर्ट DK चैट में और फ़ोन के Download/DK Reports फ़ोल्डर में सेव होती है।")
    }

    card("आँकड़े कहाँ से लें", "हर प्लेटफ़ॉर्म के लिए: दोनों (सबसे पक्का) • सिर्फ़ API • सिर्फ़ ऐप की स्क्रीन", startOpen = true) { box ->
        val modes = InsightsAgent.Mode.values().toList()
        Platform.values().forEachIndexed { i, p ->
            val api = InsightsAgent.apiConnected(this, p)
            label(box, p.hi + if (api) "  •  ✓ कनेक्टर जुड़ा है" else "  •  ✗ कनेक्टर नहीं जुड़ा (सिर्फ़ स्क्रीन से)")
            val g = radios(box, modes.map { it.id to it.hi }, InsightsAgent.mode(this, p).id, 7100 + i * 10)
            g.setOnCheckedChangeListener { _, _ -> InsightsAgent.setMode(this, p, InsightsAgent.Mode.of(radioValue(g, modes.map { it.id }, 7100 + i * 10))) }
        }
        hint(box, "दोनों = पहले आधिकारिक API (कनेक्टर) से पक्के आँकड़े, फिर ऐप / YouTube Studio की स्क्रीन पढ़कर जो API में नहीं (जैसे रील की स्किप रेट) वह भी। " +
            "कनेक्टर न जुड़ा हो तो DK अपने-आप सिर्फ़ स्क्रीन से करता है।")
    }

    card("ट्रेंड और आपका विषय", "रिपोर्ट में अभी के ट्रेंडिंग टॉपिक और आपके विषय के हिसाब से सलाह") { box ->
        val sw = switch(box, "ट्रेंडिंग टॉपिक भी खोजें (इंटरनेट)", InsightsAgent.trendsOn(this))
        sw.setOnCheckedChangeListener { _, on -> InsightsAgent.setTrends(this, on) }
        val ed = field(box, "आपका विषय (niche)", InsightsAgent.niche(this), "जैसे: नर्सरी / पौधे, कॉमेडी, खाना, टेक")
        val res = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box, button("विषय सेव करें") { InsightsAgent.setNiche(this, ed.text.toString()); res.text = "✓ सेव हो गया" })
        box.addView(res)
    }

    card("ज़रूरी तैयारी", "कनेक्टर ज़रूरी नहीं — बस ये चीज़ें") { box ->
        hint(box, "कनेक्टर ज़रूरी नहीं — DK आपके सामने ऐप और उसका डैशबोर्ड खोलकर खुद सब देखता है। ऊपर एक पट्टी में हर कदम दिखता है, " +
            "और काम चलने तक स्क्रीन बंद नहीं होती।\n\n" +
            "1. Settings → Accessibility में DK चालू रखें (ऐप खोलकर स्क्रीन पढ़ने के लिए)।\n" +
            "2. यूट्यूब के लिए YouTube Studio ऐप इंस्टॉल करें (न हो तो DK YouTube ऐप से देखेगा)।\n" +
            "3. Instagram / Facebook / YouTube Studio ऐप में अपने खाते से लॉग-इन रहें।\n" +
            "4. चाहें तो (ज़रूरी नहीं): कनेक्टर → YouTube में \"खाता जोड़ो\", और Facebook Page / Instagram token में read_insights, " +
            "instagram_manage_insights — इससे स्क्रीन के साथ आधिकारिक आँकड़े भी मिलेंगे।\n" +
            "5. DK लाइव / चैट के लिए Gemini key हो — उसी से रिपोर्ट लिखी जाती है।")
        row(box, button("कनेक्टर खोलें") { startActivity(android.content.Intent(this, com.dk.assistant.connect.ConnectorsActivity::class.java)) })
    }

    card("पिछली रिपोर्ट", "आख़िरी सोशल रिव्यू की पूरी रिपोर्ट") { box ->
        val last = InsightsAgent.lastReport(this)
        box.addView(TextView(this).apply {
            text = last.ifBlank { "अभी कोई रिपोर्ट नहीं — \"इंस्टाग्राम का पूरा रिव्यू करो\" बोलकर देखें।" }
            textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true)
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
