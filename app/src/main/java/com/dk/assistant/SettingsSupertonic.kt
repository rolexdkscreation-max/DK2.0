package com.dk.assistant

import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import com.dk.assistant.voice.engine.SpeechPipeline
import com.dk.assistant.voice.engine.SupertonicDownload
import com.dk.assistant.voice.engine.SupertonicStore
import com.dk.assistant.voice.engine.SupertonicTiming
import com.dk.assistant.voice.engine.SupertonicTtsEngine
import com.dk.assistant.voice.engine.VoiceEngines
import com.dk.assistant.voice.engine.VoiceModeController

// नवंबर 2026 (मालिक: "पहला TTS पूरी तरह हटाओ, फिर वही TTS नए सिरे से - 5-6 महिला आवाज़ें, सिर्फ़ स्पीड और पिच, बाकी कोई
// option नहीं, मॉडल APK में नहीं - इंटरनेट से"): Settings > आवाज़ > "Supertonic 3 आवाज़" card.
//
//  डाउनलोड से पहले: सिर्फ़ "डाउनलोड करें" • चलते समय: प्रतिशत + पट्टी + रोकें • टूटा: संदेश + "दोबारा कोशिश करें"
//  डाउनलोड के बाद: स्विच (चालू = सिर्फ़ Supertonic; एक ही "active" पसंद - DK लाइव / Gemini TTS अपने-आप बंद) •
//  आवाज़ें (हर एक के आगे ▶ सुनो) • स्पीड • पिच • मॉडल हटाएँ. रिवर्ब / बास / ठहराव / साँस - कुछ नहीं.

internal fun SettingsActivity.supertonicCard(box: LinearLayout) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    var bar: ProgressBar? = null
    var line: TextView? = null
    var drawn: SupertonicDownload.Kind? = null
    lateinit var draw: () -> Unit
    draw = {
        drawn = SupertonicDownload.state.kind
        bar = null; line = null
        body.removeAllViews()
        stBody(body, { draw() }) { b, l -> bar = b; line = l }
    }
    val l: (SupertonicDownload.State) -> Unit = { s ->
        if (!isFinishing && !isDestroyed) {
            if (s.kind == SupertonicDownload.Kind.RUNNING && drawn == SupertonicDownload.Kind.RUNNING && bar != null) {
                bar?.progress = s.pct; line?.text = "${s.pct}% • ${s.line}"
            } else {
                if (s.kind == SupertonicDownload.Kind.DONE) toast("Supertonic 3 डाउनलोड हो गया — अब स्विच से चालू करें")
                draw()
            }
        }
    }
    body.tag = l                 // view ही listener को पकड़े (card बंद = अपने-आप छूटे)
    SupertonicDownload.listen(l)
    draw()
    // ऊपर DK लाइव / Gemini TTS चालू किया = Supertonic अपने-आप बंद - यह card भी तुरंत वही दिखाए
    var shownOn = stOn()
    val sync = object : Runnable {
        override fun run() {
            if (!body.isAttachedToWindow || isFinishing || isDestroyed) return
            val now = stOn()
            if (now != shownOn && !SupertonicDownload.running) { shownOn = now; draw() }
            body.postDelayed(this, 1_000)
        }
    }
    body.post { body.postDelayed(sync, 1_000) }
}

/** Supertonic चुना है? (एक ही पसंद - [VoiceEngines] "active") */
private fun SettingsActivity.stOn(): Boolean = VoiceModeController.currentId(applicationContext) == SupertonicTtsEngine.ID

private fun SettingsActivity.stBody(body: LinearLayout, redraw: () -> Unit, keep: (ProgressBar?, TextView?) -> Unit) {
    val app = applicationContext
    val st = SupertonicDownload.state

    // ---- डाउनलोड चल रहा
    if (SupertonicDownload.running || st.kind == SupertonicDownload.Kind.RUNNING) {
        label(body, "डाउनलोड हो रहा है — Supertonic 3 (पाँचों महिला आवाज़ें एक साथ)")
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = st.pct }
        body.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tv = stSmall(body, "${st.pct}% • ${st.line}", 0xB3FFFFFF.toInt())
        keep(pb, tv)
        row(body, button("रोकें") { SupertonicDownload.stop() })
        hint(body, "ऐप बंद न करें। नेटवर्क टूटा तो अपने-आप फिर कोशिश होगी; रोका तो अगली बार जहाँ रुका वहीं से।")
        return
    }

    if (!SupertonicStore.installed(app)) {
        // ---- डाउनलोड से पहले: सिर्फ़ बटन (स्विच नहीं)
        if (st.kind == SupertonicDownload.Kind.FAILED && st.msg != null) {
            stSmall(body, st.msg, 0xFFFF8A80.toInt())
            row(body, button("दोबारा कोशिश करें") { SupertonicDownload.start(app); redraw() })
        } else {
            row(body, button("डाउनलोड करें") { SupertonicDownload.start(app); redraw() })
        }
        hint(body, "एक बार करीब ${(SupertonicStore.MODEL_SIZE + SupertonicStore.LIBS.first().zipEnd) shr 20} MB डाउनलोड (Wi-Fi बेहतर), इंटरनेट से — ऐप के अंदर नहीं, ऐप का साइज़ नहीं बढ़ता। उसके बाद बिना इंटरनेट चलेगा। " +
            "एक ही मॉडल में ${SupertonicStore.VOICES.size} नेचुरल महिला आवाज़ें: " + SupertonicStore.VOICES.joinToString(", ") { it.title.substringBefore(" —") } + "।")
        return
    }

    // ---- डाउनलोड हो चुका: स्विच (डिफ़ॉल्ट बंद)
    val on = stOn()
    val sw = switch(body, "Supertonic 3 आवाज़ चालू (चालू = सिर्फ़ Supertonic बोलेगा, DK लाइव / Gemini TTS अपने-आप बंद)", on)
    sw.setOnCheckedChangeListener { _, v ->
        if (v) {
            VoiceEngines.clearFailed(SupertonicTtsEngine.ID)
            SupertonicStore.setError(app, null)
            VoiceModeController.switch(app, SupertonicTtsEngine.ID, true)
            SpeechPipeline.stopAll()
            toast("Supertonic 3 चालू — अब सिर्फ़ Supertonic बोलेगा, DK चुप होते ही अगला जवाब Supertonic में")
            Thread({ SupertonicTtsEngine.warm(app) }, "dk-supertonic3-warm").apply { isDaemon = true; start() }
        } else if (stOn()) {
            // मालिक: बंद करने से अपने-आप कोई दूसरा इंजन चालू नहीं - एक आवाज़ हमेशा चालू, दूसरी खुद चुनो
            toast("Supertonic बंद नहीं हुआ — एक आवाज़ हमेशा चालू रहती है। ऊपर \"बोलने वाला इंजन\" में DK लाइव या Gemini TTS चालू करें, Supertonic अपने-आप बंद हो जाएगा")
        }
        redraw()
    }
    if (st.kind == SupertonicDownload.Kind.FAILED && st.msg != null) {
        stSmall(body, st.msg, 0xFFFF8A80.toInt())
        row(body, button("दोबारा कोशिश करें") { SupertonicDownload.start(app); redraw() })
    }
    if (on) {
        SupertonicStore.error(app)?.takeIf { VoiceEngines.isFailed(SupertonicTtsEngine.ID) }?.let { stSmall(body, "ध्यान दें: $it", 0xFFFFB74D.toInt()) }
        hint(body, "चालू है — Gemini सिर्फ़ जवाब लिखता है, बोलता Supertonic है (अपने-आप Gemini TTS पर नहीं जाता)। कभी दिक़्क़त हो तो उसी पल Gemini Live की सीधी आवाज़, थोड़ी देर में फिर Supertonic।")
    } else hint(body, "बंद है — DK ऊपर चुने इंजन (DK लाइव / Gemini TTS) से बोल रहा है। चालू करने पर सिर्फ़ Supertonic बोलेगा, बाकी दोनों अपने-आप बंद।")

    stVoices(body)
    stSlider(body, "स्पीड", SupertonicStore.SPEED_MIN, SupertonicStore.SPEED_MAX, SupertonicStore.speed(app),
        { if (it == 100) "1.00x (सामान्य)" else String.format(java.util.Locale.US, "%.2fx", it / 100f) }) { SupertonicStore.setSpeed(app, it) }
    stSlider(body, "पिच", SupertonicStore.PITCH_MIN, SupertonicStore.PITCH_MAX, SupertonicStore.pitch(app),
        { if (it == 100) "सामान्य" else if (it > 100) "+${it - 100}% ऊँची" else "${it - 100}% नीची" }) { SupertonicStore.setPitch(app, it) }
    hint(body, "स्पीड और पिच अगले वाक्य से लगते हैं और फ़ोन में सेव रहते हैं।")
    stSmall(body, SupertonicTiming.line(), 0xB3FFFFFF.toInt())

    row(body, button("मॉडल हटाएँ (${SupertonicDownload.mb(SupertonicStore.diskBytes(app))} MB खाली करें)") {
        AlertDialog.Builder(this).setTitle("Supertonic मॉडल हटाएँ?")
            .setMessage("फ़ोन से Supertonic की सारी फ़ाइलें और इसकी सेटिंग हट जाएँगी, DK Gemini Live की सीधी आवाज़ में बोलेगा। दोबारा चाहिए तो फिर डाउनलोड करना होगा।")
            .setPositiveButton("हटाएँ") { _, _ ->
                if (stOn()) VoiceEngines.setActive(app, VoiceEngines.LIVE_NATIVE)
                SpeechPipeline.stopAll()
                Thread({
                    SupertonicDownload.stop(); SupertonicTtsEngine.release()
                    SupertonicStore.delete(app)
                    runOnUiThread { SupertonicDownload.reset(); toast("Supertonic मॉडल हटा दिया"); redraw() }
                }, "dk-supertonic3-delete").apply { isDaemon = true; start() }
            }.setNegativeButton("रहने दें", null).show()
    })
    hint(body, "आवाज़: Supertonic 3 (Supertone) — लाइसेंस OpenRAIL-M। इंजन: sherpa-onnx (Apache-2.0)।")
}

/** पाँचों आवाज़ें - हर एक के आगे ▶ सुनो (चुनो = अगला वाक्य इसी में; एक ही मॉडल, न डाउनलोड न इंतज़ार) */
private fun SettingsActivity.stVoices(body: LinearLayout) {
    val app = applicationContext
    label(body, "आवाज़ (${SupertonicStore.VOICES.size} महिला आवाज़ें)")
    val cur = SupertonicStore.voice(app)
    val radios = mutableListOf<Pair<String, RadioButton>>()
    val res = stSmall(body, "", 0xB3FFFFFF.toInt())
    for (v in SupertonicStore.VOICES) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rb = RadioButton(this).apply {
            text = v.title; isChecked = v.id == cur; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { SupertonicStore.setVoice(app, v.id); radios.forEach { (n, b) -> b.isChecked = n == v.id } }
        }
        r.addView(rb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(button("▶ सुनो") { stPreview(v.id, res) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        radios.add(v.id to rb)
    }
    body.removeView(res); body.addView(res)
}

private fun SettingsActivity.stPreview(voice: String, res: TextView) {
    val app = applicationContext
    res.text = if (SupertonicTtsEngine.isLoaded) "बज रहा है…" else "मॉडल मेमोरी में आ रहा है, फिर बजेगा…"
    Thread({
        val ms = try { SupertonicTtsEngine.preview(app, SupertonicTtsEngine.SAMPLE, voice) } catch (e: Exception) { -2L }
        if (ms >= 0) VoiceEngines.recordPreview(app, SupertonicTtsEngine.ID, ms)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            res.text = if (ms == -2L) "नमूना नहीं बजा — ${SupertonicStore.error(app) ?: "दोबारा दबाएँ"}"
            else "▶ दबाने से पहली आवाज़: ${VoiceEngines.secs(ms.coerceAtLeast(0))}"
        }
    }, "dk-supertonic3-test").apply { isDaemon = true; start() }
}

private fun SettingsActivity.stSmall(box: LinearLayout, t: String, color: Int): TextView {
    val v = TextView(this).apply { text = t; textSize = 12f; setTextColor(color); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
    box.addView(v); return v
}

/** VoiceEngineCard जैसा ही स्लाइडर - छूते ही सेव */
private fun SettingsActivity.stSlider(box: LinearLayout, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
    val lbl = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0) }
    fun show(v: Int) { lbl.text = "$title: ${fmt(v)}" }
    show(value)
    box.addView(lbl)
    val sb = SeekBar(this).apply {
        this.max = max - min; progress = (value - min).coerceIn(0, max - min)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { show(p + min); if (fromUser) onChange(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}
