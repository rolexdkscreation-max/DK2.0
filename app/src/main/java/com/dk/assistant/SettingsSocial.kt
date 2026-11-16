package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.dk.assistant.insights.InsightsAgent
import com.dk.assistant.insights.Platform

/**
 * नवंबर 2026 (मालिक): Settings → "सोशल रिव्यू" - इंस्टाग्राम / फेसबुक / यूट्यूब का पूरा रिव्यू.
 * हर प्लेटफ़ॉर्म पर आँकड़े कहाँ से लें: दोनों (API + ऐप की स्क्रीन) / सिर्फ़ API / सिर्फ़ स्क्रीन. [InsightsAgent]
 */
internal fun SettingsActivity.secSocial() {
    socialSpecialistCard()
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

private var socialJob: kotlinx.coroutines.Job? = null

/**
 * 16 नवंबर 2026 (मालिक): सोशल पोस्ट स्पेशलिस्ट - Instagram / Facebook / YouTube Shorts.
 * गैलरी पहचान मॉडल (MobileCLIP2-S0, ~102 MB) APK में नहीं - यहीं से इंटरनेट से डाउनलोड. कैप्शन Gemini से. शुरू में बंद.
 */
internal fun SettingsActivity.socialSpecialistCard() {
    card("सोशल पोस्ट स्पेशलिस्ट", "बोलकर पोस्ट: गैलरी से खुद चुने, देखकर कैप्शन/हैशटैग, कहने पर गाना/टैग, YouTube Shorts — आख़िर में \"हाँ\" पूछकर") { box ->
        val gc = com.dk.assistant.agent.android.GalleryClip
        val assets = com.dk.assistant.plus.models.ModelCatalog.SOCIAL_CLIP
        val st = status(box)
        fun refresh() {
            st.text = UiLang.s(buildString {
                append(if (gc.modelsReady(this@socialSpecialistCard)) "✓ गैलरी पहचान मॉडल तैयार" else "✗ गैलरी पहचान मॉडल डाउनलोड नहीं")
                append(" • ")
                append(if (gc.mediaPermission(this@socialSpecialistCard) && gc.videoPermission(this@socialSpecialistCard)) "✓ गैलरी की इजाज़त" else "✗ गैलरी की इजाज़त नहीं")
                append(" • ")
                append(if (com.dk.assistant.plus.FeatureFlags.on(this@socialSpecialistCard, com.dk.assistant.plus.FeatureFlags.SOCIAL_SPECIALIST)) "चालू" else "बंद")
            })
        }
        refresh()
        flagSwitch(box, "सोशल पोस्ट स्पेशलिस्ट चालू", com.dk.assistant.plus.FeatureFlags.SOCIAL_SPECIALIST) { refresh() }
        if (!gc.supported) hint(box, "यह फ़ोन (64-bit ARM नहीं) गैलरी पहचान मॉडल नहीं चला सकता — फिर भी शेयर की गई / सबसे नई फोटो-वीडियो पर कैप्शन, हैशटैग, गाना, टैग और YouTube Shorts चलेंगे।")

        val total = assets.sumOf { it.bytes }
        label(box, "गैलरी पहचान मॉडल (MobileCLIP2-S0, " + String.format(java.util.Locale.US, "%.0f MB", total / 1_048_576.0) + ") — APK में नहीं, इंटरनेट से")
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        box.addView(bar)
        val dl = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        box.addView(dl)
        row(box, button("मॉडल डाउनलोड करो") {
            if (gc.modelsReady(this)) { toast("मॉडल पहले से तैयार है"); return@button }
            socialJob?.cancel()
            socialJob = lifecycleScope.launch {
                var doneBytes = 0L
                for ((i, a) in assets.withIndex()) {
                    if (com.dk.assistant.plus.models.ModelManager.ready(this@socialSpecialistCard, a)) { doneBytes += a.bytes; continue }
                    com.dk.assistant.plus.models.ModelManager.enqueue(this@socialSpecialistCard, a)
                    val end = com.dk.assistant.plus.models.ModelManager.state(a.id).first { s ->
                        if (s is com.dk.assistant.plus.models.ModelManager.St.Running) {
                            val pct = ((doneBytes + a.bytes * s.pct / 100) * 100 / total).toInt()
                            bar.visibility = android.view.View.VISIBLE; bar.progress = pct
                            dl.text = UiLang.s("डाउनलोड: $pct% (फ़ाइल ${i + 1}/${assets.size})")
                        }
                        s is com.dk.assistant.plus.models.ModelManager.St.Ready || s is com.dk.assistant.plus.models.ModelManager.St.Failed
                    }
                    if (end !is com.dk.assistant.plus.models.ModelManager.St.Ready) {
                        bar.visibility = android.view.View.GONE
                        dl.text = UiLang.s("✗ डाउनलोड नहीं हुआ — इंटरनेट देखकर फिर दबाएँ (जहाँ रुका वहीं से चलेगा)")
                        return@launch
                    }
                    doneBytes += a.bytes
                }
                bar.visibility = android.view.View.GONE
                dl.text = UiLang.s("✓ डाउनलोड पूरा, जाँच (SHA-256) सही")
                gc.close(); refresh()
            }
        })

        row(box, button("गैलरी की इजाज़त दो") {
            if (android.os.Build.VERSION.SDK_INT >= 33) askMissing(41, android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            else askMissing(41, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            refresh()
        })

        val wl = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        box.addView(wl)
        row(box, button("गैलरी पहचान तैयार करो") {
            if (!gc.supported) { toast("यह फ़ोन गैलरी पहचान नहीं चला सकता"); return@button }
            if (!gc.modelsReady(this)) { toast("पहले मॉडल डाउनलोड करें"); return@button }
            if (!gc.mediaPermission(this)) { toast("पहले गैलरी की इजाज़त दें"); return@button }
            socialJob?.cancel()
            socialJob = lifecycleScope.launch {
                wl.text = UiLang.s("गैलरी देख रहा हूँ…")
                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    gc.warm(this@socialSpecialistCard) { d, t -> runOnUiThread { wl.text = UiLang.s("गैलरी पहचान: $d / $t") } }
                }
                wl.text = UiLang.s(if (n < 0) "✗ मॉडल नहीं चल पाया — फिर से डाउनलोड करके देखें" else "✓ $n फोटो/वीडियो पहचान के लिए तैयार")
            }
        })

        hint(box, "बोलें: \"DK, कल वाली समुद्र की फोटो इंस्टाग्राम पर डालो, कैप्शन और हैशटैग खुद लगाओ\", " +
            "\"गोवा वाला वीडियो रील में डालो, केसरिया गाना लगाओ, रवि को टैग करो\", \"सबसे नया वीडियो YouTube Shorts पर डालो\", " +
            "\"यह फोटो फेसबुक पर डालो, गाना कोई भी अच्छा सा\".\n\n" +
            "• गाना सिर्फ़ तब लगता है जब आप कहें। टैग सिर्फ़ उन्हीं को जिनका नाम आप बोलें।\n" +
            "• पोस्ट से पहले DK सब सुनाता है — कौन सी फोटो, कैप्शन, हैशटैग, गाना, किसे टैग किया — और \"हाँ\" कहने पर ही पब्लिक करता है। \"ना\" = कुछ पोस्ट नहीं।\n" +
            "• जो नहीं हो पाया (गाना न मिला, टैग न हुआ) वह भी साफ़ बताता है।\n" +
            "• कैप्शन के लिए Gemini key चाहिए (वही जो DK में है)। पहली बार गैलरी पहचान में कुछ मिनट लगते हैं।\n" +
            "• Instagram / Facebook / YouTube ऐप के नए डिज़ाइन से कोई बटन न मिले तो DK रुककर बता देगा।\n" +
            "• मॉडल: Apple MobileCLIP2 (सिर्फ़ निजी इस्तेमाल का लाइसेंस)।")
    }
}
