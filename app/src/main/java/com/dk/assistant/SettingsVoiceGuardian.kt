package com.dk.assistant

import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.voice.VgRules
import com.dk.assistant.voice.VgStyle
import com.dk.assistant.voice.VoiceEnroll
import com.dk.assistant.voice.VoiceGuardian
import com.dk.assistant.voice.VoicePrint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 2): "वॉइस गार्डियन" card (आवाज़ section, बंद खुलने वाला).
 * मॉडल चुनो → डाउनलोड (progress) → 6 वाक्य सिखाओ → सीमा (slider) → लाइव जाँच → स्विच. सब शुरू में बंद.
 */
private var vgJob: Job? = null
private var vgSlider: SeekBar? = null

/**
 * 16 नवंबर 2026: कई वाक्य रिकॉर्ड -> चुप्पी काटो -> पहचान. आपस में कम मिलने वाले सैंपल (शोर / कोई और बोला) छोड़ दो.
 * keepOld = पुराने सैंपल के साथ जोड़ो. आखिर में सीमा आपकी आवाज़ के हिसाब से ([VgRules.calibrate]).
 */
private fun SettingsActivity.vgDeepEnroll(phrases: List<String>, keepOld: Boolean, say: TextView, done: () -> Unit) {
    if (!Setup.hasMic(this)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return }
    if (!VoiceGuardian.hasModel(this)) { toast("पहले मॉडल डाउनलोड करें"); return }
    lifecycleScope.launch {
        // 16 नवंबर 2026 (मालिक): हर सैंपल अलग अंदाज़ में (धीरे / ज़ोर से / उत्साह से ...), वाक्य सिर्फ़ सुझाव - कुछ भी बोल सकते हैं
        val takes = withDkMicPaused { styledTakes(VgStyle.plan(phrases, start = if (keepOld) VoiceGuardian.templates(this@vgDeepEnroll).size else 0), say) }
        val got = takes.map { it.emb }
        val need = (phrases.size * 2 + 2) / 3
        if (got.size < need) { say.text = UiLang.s("✗ बहुत से वाक्य साफ़ नहीं आए (${got.size}/${phrases.size}) — शांत जगह पर फिर से"); return@launch }
        val old = if (keepOld) VoiceGuardian.templates(this@vgDeepEnroll) else emptyList()
        val ref = VoicePrint.mean(old + got) ?: return@launch
        val good = takes.filter { VgStyle.keep(it.prompt, VoicePrint.cosine(it.emb, ref)) }.map { it.emb }
        if (good.size < need) { say.text = UiLang.s("✗ सैंपल आपस में कम मिले — शांत जगह पर, सिर्फ़ आप बोलें, फिर से"); return@launch }
        val all = VgRules.addTemplates(old, good)
        VoiceGuardian.saveTemplates(this@vgDeepEnroll, all)
        val th = VgRules.calibrate(all)
        if (th != null) { VoiceGuardian.setThreshold(this@vgDeepEnroll, th); vgSlider?.progress = VgRules.sliderFromTh(th) }
        val skipped = got.size - good.size
        say.text = UiLang.s(buildString {
            append("✓ ${good.size} सैंपल सीख लिए (कुल ${all.size})")
            if (skipped > 0) append(", $skipped कम मिलने वाले छोड़े")
            if (th != null) append(". सीमा आपकी आवाज़ के हिसाब से: ${VgRules.pct(th)}%")
            append(". यह सिर्फ़ इसी फ़ोन में रहता है")
        })
        if (!FeatureFlags.on(this@vgDeepEnroll, FeatureFlags.VG_DEEP)) toast("ऊपर \"15 सैंपल वाली पक्की पहचान चालू\" करें")
        done()
    }
}

internal fun SettingsActivity.voiceGuardianCard() {
    card("वॉइस गार्डियन", "फ़ोन पर ही आपकी आवाज़ की पहचान — अनजान आवाज़ अनसुनी, Away मोड में चेतावनी फिर लॉक. मॉडल APK में नहीं, यहीं से डाउनलोड") { box ->
        if (!VoiceGuardian.supported) {
            hint(box, "यह फ़ोन (64-bit ARM नहीं) आवाज़-पहचान मॉडल नहीं चला सकता — DK पहले की तरह सबकी आवाज़ सुनेगा.")
            return@card
        }
        val st = status(box)
        fun refresh() {
            val a = VoiceGuardian.asset(this)
            st.text = UiLang.s(buildString {
                append(if (VoiceGuardian.hasModel(this@voiceGuardianCard)) "✓ मॉडल तैयार (${a.mb})" else "✗ मॉडल डाउनलोड नहीं")
                append(" • ")
                append(if (VoiceGuardian.enrolled(this@voiceGuardianCard)) "✓ आवाज़ सीखी हुई" else "✗ आवाज़ अभी नहीं सीखी")
                append(" • ")
                append(if (VoiceGuardian.gateOn(this@voiceGuardianCard)) "पहचान चालू" else "पहचान बंद")
            })
        }

        flagSwitch(box, "वॉइस गार्डियन चालू", FeatureFlags.VOICE_GUARDIAN) { refresh(); com.dk.assistant.voice.engine.VoiceEngines.liveChanged() }

        label(box, "मॉडल चुनें")
        val opts = VgRules.MODELS.map { it.id to VgRules.label(it) }
        radios(box, opts, VoiceGuardian.modelId(this), 2900).setOnCheckedChangeListener { _, id ->
            opts.getOrNull(id - 2900)?.let { VoiceGuardian.setModel(this, it.first); refresh(); toast("मॉडल बदला — इस मॉडल से आवाज़ फिर से सिखानी होगी (अगर पहले नहीं सिखाई)") }
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        box.addView(bar)
        val dl = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        box.addView(dl)
        row(box, button("मॉडल डाउनलोड करो") {
            val a = VoiceGuardian.asset(this)
            if (ModelManager.ready(this, a)) { toast("मॉडल पहले से तैयार है"); return@button }
            ModelManager.enqueue(this, a)
            vgJob?.cancel()
            vgJob = lifecycleScope.launch {
                ModelManager.state(a.id).collectLatest { s ->
                    when (s) {
                        is ModelManager.St.Running -> { bar.visibility = android.view.View.VISIBLE; bar.progress = s.pct; dl.text = UiLang.s("डाउनलोड: ${s.pct}% (${a.mb})") }
                        is ModelManager.St.Ready -> { bar.visibility = android.view.View.GONE; dl.text = UiLang.s("✓ डाउनलोड पूरा, जाँच (SHA-256) सही"); VoiceGuardian.close(); refresh() }
                        is ModelManager.St.None -> {}
                        else -> { bar.visibility = android.view.View.GONE; dl.text = UiLang.s("✗ डाउनलोड नहीं हुआ — इंटरनेट देखकर फिर दबाएँ (जहाँ रुका वहीं से चलेगा). पुराना मॉडल (अगर था) वैसा ही है") }
                    }
                }
            }
        })

        label(box, "आवाज़ सिखाएँ — 6 सैंपल, हर एक अलग अंदाज़ में (कुछ भी बोल सकते हैं)")
        val say = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        box.addView(say)
        row(box, button("आवाज़ सिखाओ (6 वाक्य)") {
            if (!Setup.hasMic(this)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return@button }
            if (!VoiceGuardian.hasModel(this)) { toast("पहले मॉडल डाउनलोड करें"); return@button }
            lifecycleScope.launch {
                // 16 नवंबर 2026 (मालिक): अंदाज़ बदल-बदलकर (धीरे / ज़ोर से / उत्साह से ...), लिखा वाक्य सिर्फ़ सुझाव
                val takes = withDkMicPaused { styledTakes(VgStyle.plan(VgRules.PHRASES), say) }
                val need = VgStyle.need(VgRules.PHRASES.size)
                if (takes.size < need) { say.text = UiLang.s("✗ बहुत से सैंपल साफ़ नहीं आए (${takes.size}/${VgRules.PHRASES.size}) — शांत जगह पर फिर से सिखाएँ"); return@launch }
                val good = consistentTakes(takes)
                if (good.size < need) { say.text = UiLang.s("✗ सैंपल आपस में कम मिले — सिर्फ़ आप बोलें, फिर से"); return@launch }
                val prints = good.map { it.emb }
                VoiceGuardian.saveTemplates(this@voiceGuardianCard, prints)      // औसत पहचान भी इसी में सेव
                val th = VgRules.calibrate(prints)
                if (th != null) { VoiceGuardian.setThreshold(this@voiceGuardianCard, th); vgSlider?.progress = VgRules.sliderFromTh(th) }
                val c = VoicePrint.consistency(prints)
                say.text = UiLang.s("✓ आवाज़ सीख ली (${prints.size} सैंपल, आपस में मिलान ${VgRules.pct(c)}%" +
                    (if (takes.size > good.size) ", ${takes.size - good.size} कम मिलने वाले छोड़े" else "") +
                    (if (th != null) ", सीमा ${VgRules.pct(th)}%" else "") + "). यह सिर्फ़ इसी फ़ोन में रहती है")
                refresh()
            }
        }, button("सीखी आवाज़ मिटाओ") { VoiceGuardian.forget(this); refresh(); toast("सीखी आवाज़ मिटा दी") })

        // 16 नवंबर 2026 (मालिक): 15 सैंपल वाली पक्की पहचान - हर सैंपल अलग याद, सीमा आपकी आवाज़ के हिसाब से
        label(box, "15 सैंपल वाली पक्की पहचान")
        flagSwitch(box, "15 सैंपल वाली पक्की पहचान चालू", FeatureFlags.VG_DEEP) { refresh() }
        val deep = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        box.addView(deep)
        fun deepInfo() { val n = VoiceGuardian.templates(this).size; if (n > 0) deep.text = UiLang.s("सीखे सैंपल: $n (ज़्यादा से ज़्यादा ${VgRules.MAX_TEMPLATES})") }
        deepInfo()
        row(box, button("15 वाक्य सिखाओ") {
            vgDeepEnroll(VgRules.PHRASES_15, keepOld = false, deep) { deepInfo(); refresh() }
        }, button("${VgRules.ADD_MORE} और सैंपल जोड़ो") {
            if (VoiceGuardian.templates(this).isEmpty()) { toast("पहले 15 वाक्य सिखाएँ"); return@button }
            vgDeepEnroll(VgRules.PHRASES_EXTRA.shuffled().take(VgRules.ADD_MORE), keepOld = true, deep) { deepInfo(); refresh() }
        })
        hint(box, "हर सैंपल पर स्क्रीन बताएगी किस अंदाज़ में बोलना है (आम / धीरे / ज़ोर से / उत्साह से / जल्दी / धीमी / दूर से / फुसफुसाकर). लिखा वाक्य सिर्फ़ सुझाव है — आप कुछ भी बोल सकते हैं, पहचान आवाज़ की होती है, शब्दों की नहीं. \"और सैंपल जोड़ो\" किसी दूसरी जगह (बाहर / शोर / गाड़ी में) करें तो वहाँ भी पहचान अच्छी होगी. सीखने के बाद सीमा अपने-आप आपकी आवाज़ के हिसाब से लगती है (नीचे slider से बदल सकते हैं). सबसे अच्छा: \"सटीक\" मॉडल (RedimNet2-B3) + 15 सैंपल. मॉडल बदलें तो सैंपल फिर से सिखाने होंगे.")

        label(box, "कितना सख़्त मिलान (सीमा)")
        val thTxt = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        val sb = SeekBar(this).also { vgSlider = it }.apply { max = 100; progress = VgRules.sliderFromTh(VoiceGuardian.threshold(this@voiceGuardianCard)) }
        box.addView(sb); box.addView(thTxt)
        fun showTh() { thTxt.text = UiLang.s("सीमा: ${VgRules.pct(VoiceGuardian.threshold(this))}% (ज़्यादा = सख़्त, कम = ढीला; डिफ़ॉल्ट ${VgRules.pct(VgRules.TH_DEFAULT)}%)") }
        showTh()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { VoiceGuardian.setThreshold(this@voiceGuardianCard, VgRules.thFromSlider(p)); showTh() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val test = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()) }
        row(box, button("लाइव जाँच (3 सेकंड बोलें)") {
            if (!Setup.hasMic(this)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return@button }
            if (!VoiceGuardian.ready(this)) { toast("पहले मॉडल डाउनलोड करें और आवाज़ सिखाएँ"); return@button }
            lifecycleScope.launch {
                test.text = UiLang.s("बोलिए…")
                val sc = withDkMicPaused {
                    val t = VoiceEnroll.record()
                    if (t == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { VoiceGuardian.score(this@voiceGuardianCard, t.pcm) }
                }
                test.text = UiLang.s(VgRules.testLine(sc, VoiceGuardian.threshold(this@voiceGuardianCard)))
            }
        })
        box.addView(test)

        flagSwitch(box, "सिर्फ़ Boss की आवाज़ (अनजान आवाज़ अनसुनी)", FeatureFlags.VG_OWNER_ONLY) { refresh(); com.dk.assistant.voice.engine.VoiceEngines.liveChanged() }
        flagSwitch(box, "पक्की रोक: पहचान होने तक आवाज़ DK तक नहीं (अनजान आवाज़ यहीं कट)", FeatureFlags.VG_STRICT) { refresh(); com.dk.assistant.voice.engine.VoiceEngines.liveChanged() }
        hint(box, "पक्की रोक बंद हो तो किसी और की पहली ~1 सेकंड आवाज़ DK तक पहुँच जाती है (सिर्फ़ जवाब रुकता है). चालू हो तो हर बात रुककर जाँची जाती है (ज़्यादातर 0.6 सेकंड, पक्का न हो तो 1.5 सेकंड): Boss = रुकी आवाज़ तुरंत DK को, कोई और = यहीं मिटा दी. \"सिर्फ़ Boss की आवाज़\" भी चालू होना चाहिए.")
        flagSwitch(box, "Away मोड (अनजान आवाज़: पहली बार चेतावनी, दूसरी बार फ़ोन लॉक)", FeatureFlags.VG_AWAY) { refresh(); com.dk.assistant.voice.engine.VoiceEngines.liveChanged() }
        refresh()
        hint(box, "मिलान सिर्फ़ तब चलता है जब कोई बोल रहा हो. मॉडल फेल / आवाज़ बहुत छोटी = DK आवाज़ नहीं खोती (पहले जैसा सुनती है). फ़ोन लॉक के लिए Accessibility में DK चालू होना चाहिए. सीमाएँ: यह पक्का ताला नहीं — रिकॉर्डिंग या मिलती-जुलती आवाज़ से धोखा हो सकता है; शोर में पहचान कमज़ोर. बैटरी सेवर में \"वॉइस गार्डियन रोको\" चालू हो तो पहचान बंद.")
    }
}
