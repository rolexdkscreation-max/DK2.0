package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.voice.Awareness
import com.dk.assistant.voice.VgRules
import com.dk.assistant.voice.VgStyle
import com.dk.assistant.voice.VoiceEnroll
import com.dk.assistant.voice.VoiceFamily
import com.dk.assistant.voice.VoiceGuardian
import com.dk.assistant.voice.VoicePrint
import kotlinx.coroutines.launch

/**
 * 21 नवंबर 2026 (मालिक): "परिवार की आवाज़ें और अवेयरनेस मोड" card (आवाज़ section, वॉइस गार्डियन के ठीक नीचे).
 * अवेयरनेस स्विच (शुरू में बंद) → परिवार के सदस्य जोड़ो (मम्मी / पापा / भैया / कोई भी नाम, 6 सैंपल) → "कौन बोल रहा है" जाँच.
 * मॉडल वही जो वॉइस गार्डियन में चुना है; सैंपल सिर्फ़ इसी फ़ोन में, ताले में बंद.
 */
private fun SettingsActivity.famEnroll(name: String, say: TextView, done: () -> Unit) {
    if (!Setup.hasMic(this)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return }
    if (!VoiceGuardian.hasModel(this)) { toast("पहले ऊपर वॉइस गार्डियन में मॉडल डाउनलोड करें"); return }
    val clean = VoiceFamily.cleanName(name) ?: run { toast("नाम 1-24 अक्षर का लिखें (\"बॉस\" सिर्फ़ आपके लिए है)"); return }
    val old = VoiceGuardian.family(this)
    if (old.size >= VoiceFamily.MAX_MEMBERS && old.none { it.name.equals(clean, ignoreCase = true) }) { toast("${VoiceFamily.MAX_MEMBERS} लोग पूरे — पहले किसी को हटाएँ"); return }
    lifecycleScope.launch {
        say.text = UiLang.s("अब $clean बोलें — फ़ोन उनके मुँह से एक हाथ दूर रखें")
        val takes = withDkMicPaused { styledTakes(VgStyle.plan(VoiceFamily.PHRASES), say) }
        val need = VgStyle.need(VoiceFamily.PHRASES.size)
        if (takes.size < need) { say.text = UiLang.s("✗ बहुत से सैंपल साफ़ नहीं आए (${takes.size}/${VoiceFamily.PHRASES.size}) — शांत जगह पर फिर से"); return@launch }
        val good = consistentTakes(takes)
        if (good.size < need) { say.text = UiLang.s("✗ सैंपल आपस में कम मिले — सिर्फ़ $clean बोलें, फिर से"); return@launch }
        val prints = good.map { it.emb }
        val mean = VoicePrint.mean(prints) ?: return@launch
        // ग़लती से बॉस की ही (या पहले से सीखे किसी और की) आवाज़ तो नहीं सिखा दी?
        VoiceGuardian.print(this@famEnroll)?.let { boss ->
            if (VoicePrint.cosine(boss, mean) >= VoiceGuardian.threshold(this@famEnroll) + 0.10f) {
                say.text = UiLang.s("✗ यह आवाज़ आपकी (बॉस) जैसी लगी — $clean खुद बोलें, फिर से"); return@launch
            }
        }
        val clash = old.filter { !it.name.equals(clean, ignoreCase = true) }
            .firstOrNull { m -> m.centroid?.let { VoicePrint.cosine(it, mean) >= maxOf(m.th, 0.55f) + 0.10f } == true }
        if (clash != null) { say.text = UiLang.s("✗ यह आवाज़ \"${clash.name}\" से बहुत मिलती है — सही आदमी बोले, या पहले \"${clash.name}\" हटाएँ"); return@launch }
        val id = old.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.id ?: VoiceFamily.newId(System.currentTimeMillis(), old.map { it.id })
        val m = VoiceFamily.Member(id, clean, prints, VoiceFamily.memberTh(prints))
        VoiceGuardian.saveFamily(this@famEnroll, VoiceFamily.upsert(old, m))
        say.text = UiLang.s("✓ $clean की आवाज़ सीख ली (${prints.size} सैंपल, आपस में मिलान ${VgRules.pct(VoicePrint.consistency(prints))}%" +
            ", सीमा ${VgRules.pct(m.th)}%). सिर्फ़ इसी फ़ोन में")
        if (!FeatureFlags.on(this@famEnroll, FeatureFlags.VG_AWARE)) toast("ऊपर \"अवेयरनेस मोड चालू\" करें")
        done()
    }
}

internal fun SettingsActivity.voiceFamilyCard() {
    card("परिवार की आवाज़ें और अवेयरनेस मोड", "DK पहचाने कौन बोल रहा है — आप (बॉस), मम्मी, पापा, भैया या कोई अनजान — और उसी हिसाब से बात करे. शुरू में बंद") { box ->
        if (!VoiceGuardian.supported) {
            hint(box, "यह फ़ोन (64-bit ARM नहीं) आवाज़-पहचान मॉडल नहीं चला सकता.")
            return@card
        }
        val st = status(box)
        fun refresh() {
            val n = VoiceGuardian.family(this).size
            st.text = UiLang.s(buildString {
                append(if (VoiceGuardian.hasModel(this@voiceFamilyCard)) "✓ मॉडल: ${VoiceGuardian.asset(this@voiceFamilyCard).title}" else "✗ पहले ऊपर वॉइस गार्डियन में मॉडल डाउनलोड करें")
                append(" • ")
                append(if (VoiceGuardian.enrolled(this@voiceFamilyCard)) "✓ बॉस की आवाज़ सीखी" else "✗ बॉस की आवाज़ अभी नहीं सीखी")
                append(" • परिवार: $n")
                append(" • ")
                append(if (VoiceGuardian.awareOn(this@voiceFamilyCard)) "अवेयरनेस चालू" else "अवेयरनेस बंद")
            })
        }

        flagSwitch(box, "अवेयरनेस मोड चालू", FeatureFlags.VG_AWARE) { refresh(); com.dk.assistant.voice.engine.VoiceEngines.liveChanged() }
        hint(box, "चालू होने पर DK लाइव: हर बात के बाद आवाज़ से पहचानता है कौन बोला (बॉस / परिवार / अनजान) • बोलने वाला बदले तो चुपचाप समझ लेता है और नाम/रिश्ते से इज़्ज़त से बात करता है (मम्मी जी, पापा जी, भैया) • बड़ों से धीरे-साफ़, बच्चों से आसान • मूड (थका, परेशान, जल्दी में) समझकर बात • याद रखता है किसने क्या कहा — पूछें \"आसपास कौन है\" / \"मम्मी ने क्या कहा\" • मैसेज, कॉल, पैसे, डिलीट, सेटिंग और आपकी निजी चीज़ें सिर्फ़ आपकी (बॉस) आवाज़ पर. \"सिर्फ़ Boss की आवाज़\" चालू हो तो दूसरी आवाज़ें वैसे ही अनसुनी रहती हैं — परिवार से बात के लिए उसे बंद रखें.")

        label(box, "परिवार की आवाज़ सिखाएँ — जिसकी आवाज़ है, वही बोले (6 सैंपल)")
        val say = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        box.addView(say)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var show: () -> Unit
        fun ask(cur: String, done: (String) -> Unit) {
            val e = android.widget.EditText(this).apply { setText(cur); hint = UiLang.s("जैसे: चाचा जी, राहुल, दीदी") }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("नाम या रिश्ता लिखें")).setView(e)
                .setPositiveButton(UiLang.s("सिखाओ")) { _, _ -> done(e.text.toString()) }.setNegativeButton(UiLang.s("रद्द"), null).show()
        }
        VoiceFamily.RELATIONS.take(6).chunked(3).forEach { ch ->
            row(box, *ch.map { r -> button(r) { famEnroll(r, say) { show(); refresh() } } }.toTypedArray())
        }
        row(box, button("दूसरा नाम लिखें") { ask("") { n -> famEnroll(n, say) { show(); refresh() } } },
            button("सब मिटाओ") { VoiceGuardian.forgetFamily(this); show(); refresh(); toast("परिवार की सारी सीखी आवाज़ें मिटा दीं") })
        box.addView(list)
        show = {
            list.removeAllViews()
            val fam = VoiceGuardian.family(this)
            if (fam.isEmpty()) list.addView(TextView(this).apply { text = UiLang.s("अभी कोई नहीं जोड़ा"); textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) })
            fam.forEach { m ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                r.addView(TextView(this).apply { text = UiLang.s("${m.name} — ${m.templates.size} सैंपल, सीमा ${VgRules.pct(m.th)}%"); textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(button("फिर सिखाओ") { famEnroll(m.name, say) { show(); refresh() } })
                r.addView(button("हटाएँ") { VoiceGuardian.saveFamily(this, VoiceFamily.remove(VoiceGuardian.family(this), m.id)); show(); refresh(); toast("${m.name} हटाए") })
                list.addView(r)
            }
        }
        show()

        val test = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()) }
        row(box, button("कौन बोल रहा है? (3 सेकंड बोलें)") {
            if (!Setup.hasMic(this)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return@button }
            if (!VoiceGuardian.hasModel(this)) { toast("पहले मॉडल डाउनलोड करें"); return@button }
            if (!VoiceGuardian.enrolled(this) && VoiceGuardian.family(this).isEmpty()) { toast("पहले अपनी या परिवार की आवाज़ सिखाएँ"); return@button }
            lifecycleScope.launch {
                test.text = UiLang.s("बोलिए…")
                val p = withDkMicPaused {
                    val t = VoiceEnroll.record()
                    if (t == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        VoiceGuardian.identify(this@voiceFamilyCard, VgRules.trimSilence(t.pcm))?.second
                    }
                }
                test.text = UiLang.s(VoiceFamily.testLine(p))
            }
        }, button("आसपास कौन था") { test.text = UiLang.s(Awareness.aroundLine(Awareness.global.recent(System.currentTimeMillis()))) })
        box.addView(test)
        refresh()
        hint(box, "सबसे अच्छी पहचान: ऊपर \"सबसे सटीक\" मॉडल (ReDimNet2-B6) + अपनी आवाज़ 15 सैंपल से. मॉडल बदलें तो आपकी और परिवार की आवाज़ फिर से सिखानी होगी. पक्का न हो तो DK नाम नहीं लेता (ग़लत नाम से अच्छा). सीमाएँ: यह पक्का ताला नहीं — बहुत मिलती-जुलती आवाज़ (जैसे भाई-भाई), रिकॉर्डिंग, तेज़ शोर या एक साथ दो लोग बोलें तो ग़लती हो सकती है. आवाज़ और बातें कहीं सेव / इंटरनेट पर नहीं जातीं — सिर्फ़ पहचान के अंक, इसी फ़ोन में.")
    }
}
