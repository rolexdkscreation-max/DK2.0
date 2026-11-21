package com.dk.assistant

import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.stt.WhisperStt
import com.dk.assistant.talk.TalkPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 18 नवंबर 2026 (मालिक: "सुनना अलग, सोचना-बोलना अलग; Whisper का मॉडल ऐप के साथ मत बाँधो - डाउनलोड वाले हिस्से से,
 * progress, रोकें / फिर शुरू, पूरी जाँच, हटा सकें; STT चुन सकूँ; चुप्पी का समय बदल सकूँ"):
 * Settings → आवाज़ → "बातचीत का तरीका" कार्ड. पुरानी कोई setting नहीं बदलती (default = Gemini Live, पहले जैसा).
 */
private var talkJob: Job? = null
private const val TALK_MODE_RADIO = 3900
private const val TALK_STT_RADIO = 3950

internal fun SettingsActivity.secTalk() {
    card("बातचीत का तरीका (सुनना अलग, सोचना-बोलना अलग)", "Gemini Live या नया अलग-अलग तरीका • Whisper (फ़ोन पर) / Android पहचान • चुप्पी का समय • Whisper मॉडल डाउनलोड") { box ->
        val st = status(box)
        fun refresh() {
            val a = WhisperStt.asset()
            st.text = UiLang.s(buildString {
                append(if (TalkPrefs.split(this@secTalk)) "तरीका: अलग-अलग" else "तरीका: Gemini Live")
                append(" • पहचान: ").append(if (TalkPrefs.stt(this@secTalk) == TalkPrefs.STT_WHISPER) "Whisper" else "Android")
                append(" • ")
                append(when {
                    !WhisperStt.supported() -> "✗ " + WhisperStt.whyNot(this@secTalk)
                    ModelManager.ready(this@secTalk, a) -> "✓ Whisper मॉडल तैयार (${a.mb})"
                    else -> "✗ Whisper मॉडल अभी डाउनलोड नहीं - तब तक Android की पहचान"
                })
            })
        }
        refresh()

        label(box, "बातचीत कैसे चले")
        val modes = listOf(TalkPrefs.MODE_LIVE to "Gemini Live (पहले जैसा - आवाज़ सीधे Gemini को)",
            TalkPrefs.MODE_SPLIT to "अलग-अलग: पहले आपकी बात लिखी जाए, फिर Gemini सोचे, फिर DK बोले")
        radios(box, modes, if (TalkPrefs.split(this)) TalkPrefs.MODE_SPLIT else TalkPrefs.MODE_LIVE, TALK_MODE_RADIO).setOnCheckedChangeListener { _, id ->
            val split = modes.getOrNull(id - TALK_MODE_RADIO)?.first == TalkPrefs.MODE_SPLIT
            TalkPrefs.setMode(this, split); refresh()
            toast(if (split) "अलग-अलग तरीका चालू - अगली बातचीत से (DK लाइव चालू हो तो अभी)" else "Gemini Live वाला तरीका चालू")
        }
        hint(box, "अलग-अलग तरीके में: आपकी आवाज़ पहले स्क्रीन पर लिखी जाती है (आपका संदेश), वही text Gemini को जाता है, जवाब text में आता है और DK की आवाज़ (Gemini की आवाज़, न चले तो फ़ोन की आवाज़) में बोला जाता है. DK बोल रहा हो और आप बीच में बोलें तो DK तुरंत रुकता है और आपकी नई बात सुनता है. स्क्रीन पर दिखता है: सुन रहा है / सोच रहा है / बोल रहा है.")

        label(box, "आपकी बोली कौन लिखे")
        val stts = listOf(TalkPrefs.STT_WHISPER to "Whisper - फ़ोन पर ही, हिंदी (इंटरनेट नहीं, सबसे सटीक)",
            TalkPrefs.STT_ANDROID to "Android की पहचान (Google - Android 13+)")
        radios(box, stts, TalkPrefs.stt(this), TALK_STT_RADIO).setOnCheckedChangeListener { _, id ->
            stts.getOrNull(id - TALK_STT_RADIO)?.let { TalkPrefs.setStt(this, it.first); refresh() }
        }
        hint(box, "Whisper चुना हो पर मॉडल अभी डाउनलोड नहीं हुआ, या Whisper इस फ़ोन पर न चले / अटके / बहुत धीमा हो - तो DK अपने-आप Android की पहचान से सुनता है और स्क्रीन पर बताता है.")

        label(box, "रुकने के बाद कितनी चुप्पी = बात पूरी")
        val silTxt = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        val steps = ((TalkPrefs.SILENCE_MAX - TalkPrefs.SILENCE_MIN) / 50).toInt()
        val sb = SeekBar(this).apply { max = steps; progress = ((TalkPrefs.silenceMs(this@secTalk) - TalkPrefs.SILENCE_MIN) / 50).toInt() }
        box.addView(sb); box.addView(silTxt)
        fun showSil() { silTxt.text = UiLang.s("${TalkPrefs.silenceMs(this)} ms (कम = जल्दी जवाब पर बीच में रुकने पर बात कट सकती है; ज़्यादा = आराम से बोलें; डिफ़ॉल्ट ${TalkPrefs.SILENCE_DEF} ms)") }
        showSil()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { TalkPrefs.setSilence(this@secTalk, TalkPrefs.SILENCE_MIN + p * 50L); showSil() } }
            override fun onStartTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
            override fun onStopTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
        })

        switch(box, "DK बोलते समय मैं बोलूँ तो DK तुरंत रुके", TalkPrefs.barge(this)).setOnCheckedChangeListener { _, on -> TalkPrefs.setBarge(this, on) }
        switch(box, "Whisper पूरी सटीकता (थोड़ा धीमा)", TalkPrefs.fullAccuracy(this)).setOnCheckedChangeListener { _, on -> TalkPrefs.setFull(this, on) }
        hint(box, "बंद (सुझाया): छोटी बात पर Whisper सिर्फ़ उतना हिस्सा देखता है जितना आप बोले - 2-3 गुना जल्दी. चालू: हर बार पूरी 30 सेकंड की खिड़की.")

        // 21 नवंबर 2026 (मालिक): DK Live की आवाज़ 100 / 50 / 30% - डिफ़ॉल्ट बंद
        label(box, "DK Live की आवाज़ (कितनी तेज़)")
        val lvTxt = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        val lvBar = SeekBar(this).apply {
            max = (LiveVoiceLevel.MAX - LiveVoiceLevel.MIN) / LiveVoiceLevel.STEP
            progress = (LiveVoiceLevel.pct(this@secTalk) - LiveVoiceLevel.MIN) / LiveVoiceLevel.STEP
            isEnabled = LiveVoiceLevel.on(this@secTalk)
        }
        fun showLv() { lvTxt.text = UiLang.s(LiveVoiceLevel.label(LiveVoiceLevel.on(this), LiveVoiceLevel.pct(this))) }
        switch(box, "DK Live की आवाज़ कम-ज़्यादा करूँ", LiveVoiceLevel.on(this))
            .setOnCheckedChangeListener { _, on -> LiveVoiceLevel.setOn(this, on); lvBar.isEnabled = on; showLv() }
        box.addView(lvBar); box.addView(lvTxt)
        showLv()
        lvBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { LiveVoiceLevel.setPct(this@secTalk, LiveVoiceLevel.MIN + p * LiveVoiceLevel.STEP); showLv() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
            override fun onStopTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
        })
        hint(box, "बंद (डिफ़ॉल्ट) = पहले जैसा. चालू करके 100% (पूरी), 50% (आधी) या 30% (धीमी) चुनें - अगले टुकड़े से लागू. फ़ोन का volume नहीं बदलता.")

        // ---------------------------------------------------------------- Whisper मॉडल डाउनलोड
        val a = WhisperStt.asset()
        label(box, "Whisper मॉडल (डाउनलोड)")
        hint(box, "${a.title} • ${a.mb} • स्रोत: Hugging Face पर whisper.cpp का आधिकारिक मॉडल (ggerganov/whisper.cpp) • License ${a.license}. मॉडल ऐप (APK) में नहीं - फ़ोन की ऐप-स्टोरेज में रहता है, कभी भी हटा सकते हैं. Wi-Fi पर डाउनलोड करें. बीच में रुका / नेट गया = जहाँ रुका वहीं से आगे; पूरा होने पर SHA-256 से जाँच.")
        if (!WhisperStt.supported()) hint(box, "⚠ " + WhisperStt.whyNot(this) + " - DK Android की पहचान से सुनेगा.")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        box.addView(bar)
        val dl = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        box.addView(dl)
        fun show(s: ModelManager.St) {
            when (s) {
                is ModelManager.St.Running -> { bar.visibility = android.view.View.VISIBLE; bar.progress = s.pct; dl.text = UiLang.s("डाउनलोड हो रहा है: ${s.pct}% (${mb(s.done)} / ${a.mb})") }
                is ModelManager.St.Paused -> { bar.visibility = android.view.View.VISIBLE; bar.progress = s.pct; dl.text = UiLang.s("रुका हुआ: ${s.pct}% (${mb(s.done)} / ${a.mb}) - \"फिर शुरू\" दबाएँ, वहीं से आगे चलेगा") }
                is ModelManager.St.Ready -> { bar.visibility = android.view.View.GONE; dl.text = UiLang.s("✓ मॉडल पूरा डाउनलोड हुआ, जाँच (SHA-256) सही") }
                is ModelManager.St.Failed -> { bar.visibility = android.view.View.GONE; dl.text = UiLang.s("✗ डाउनलोड नहीं हुआ (${s.why}) - इंटरनेट देखकर \"डाउनलोड / फिर शुरू\" दबाएँ (जहाँ रुका वहीं से चलेगा)") }
                else -> { bar.visibility = android.view.View.GONE; dl.text = UiLang.s("मॉडल अभी फ़ोन में नहीं") }
            }
            refresh()
        }
        try { ModelManager.refresh(this) } catch (e: Exception) { Quiet.note(e) }
        talkJob?.cancel()
        talkJob = lifecycleScope.launch {
            try { ModelManager.state(a.id).collectLatest { show(it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { Quiet.note(e) }
        }
        row(box, button("डाउनलोड / फिर शुरू") {
            try {
                if (ModelManager.ready(this, a)) { toast("मॉडल पहले से तैयार है"); return@button }
                val free = try { ModelManager.dir(this).usableSpace } catch (_: Exception) { Long.MAX_VALUE }
                if (free < a.bytes + 50L * 1024 * 1024) { toast("फ़ोन में जगह कम है - Whisper के लिए करीब ${a.mb} + 50 MB खाली जगह चाहिए"); return@button }
                if (!NetProbe.online(this)) toast("अभी इंटरनेट नहीं - नेट आते ही डाउनलोड अपने-आप शुरू होगा")
                ModelManager.enqueue(this, a)
            } catch (e: Exception) { toast("डाउनलोड शुरू नहीं हुआ: ${e.message ?: "अनजानी गलती"}") }
        }, button("रोकें") {
            try { ModelManager.pause(this, a); toast("डाउनलोड रोका - आधी फ़ाइल सुरक्षित है") } catch (e: Exception) { toast("रोक नहीं पाया: ${e.message}") }
        }, button("हटाएँ") {
            try {
                lifecycleScope.launch { try { WhisperStt.release() } catch (e: Exception) { Quiet.note(e) } }
                ModelManager.delete(this, a); toast("Whisper मॉडल हटा दिया (जगह खाली). अब Android की पहचान चलेगी")
            } catch (e: Exception) { toast("हटा नहीं पाया: ${e.message}") }
        })
    }
}

private fun mb(b: Long): String = String.format(java.util.Locale.US, "%.1f MB", b / 1_048_576.0)
