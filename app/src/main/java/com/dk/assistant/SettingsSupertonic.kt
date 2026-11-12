package com.dk.assistant

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.dk.assistant.voice.engine.SupertonicDownload
import com.dk.assistant.voice.engine.SupertonicStore
import com.dk.assistant.voice.engine.SupertonicTtsEngine
import com.dk.assistant.voice.engine.SpeechPipeline
import com.dk.assistant.voice.engine.VoiceEngines

// नवंबर 2026 (मालिक: "Supertonic 3 लगाओ, वही सेटअप जो Piper का था; Piper पूरी तरह हटाओ"): Settings > आवाज़ >
// "Supertonic 3 आवाज़" card. SettingsVoice.kt में सिर्फ़ एक card(...) { supertonicCard(it) }.
//
//  डाउनलोड से पहले: सिर्फ़ "डाउनलोड करें" (स्विच नहीं) • चलते समय: प्रतिशत + पट्टी + रोकें • टूटा: संदेश + "दोबारा कोशिश करें"
//  डाउनलोड के बाद: ON/OFF स्विच (डिफ़ॉल्ट OFF) + "मॉडल हटाएँ" • ON: आवाज़, स्पीड, पिच, रिवर्ब, बास, ठहराव, टेस्ट

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
                if (s.kind == SupertonicDownload.Kind.DONE) {
                    val app = applicationContext
                    SupertonicStore.setVoice(app, s.voice)       // नई आवाज़ - ON हो तो अगला वाक्य इसी से (मॉडल पीछे बदलता है)
                    Thread({ SupertonicTtsEngine.warm(app) }, "dk-supertonic-voice").apply { isDaemon = true; start() }
                    toast("Supertonic 3 आवाज़ डाउनलोड हो गई — अब स्विच से चालू करें")
                }
                draw()
            }
        }
    }
    body.tag = l                 // view ही listener को पकड़े (card बंद = अपने-आप छूटे)
    SupertonicDownload.listen(l)
    draw()
    // नवंबर 2026: ऊपर DK लाइव / Gemini TTS चालू किया = Supertonic अपने-आप बंद - यह card भी तुरंत वही दिखाए
    var shownOn = SupertonicStore.on(applicationContext)
    val sync = object : Runnable {
        override fun run() {
            if (!body.isAttachedToWindow || isFinishing || isDestroyed) return
            val now = SupertonicStore.on(applicationContext)
            if (now != shownOn && !SupertonicDownload.running) { shownOn = now; draw() }
            body.postDelayed(this, 1_000)
        }
    }
    body.post { body.postDelayed(sync, 1_000) }
}

private fun SettingsActivity.stBody(body: LinearLayout, redraw: () -> Unit, keep: (ProgressBar?, TextView?) -> Unit) {
    val app = applicationContext
    val st = SupertonicDownload.state
    SupertonicStore.error(app)?.let { stSmall(body, "ध्यान दें: $it", 0xFFFFB74D.toInt()) }

    // ---- डाउनलोड चल रहा
    if (SupertonicDownload.running || st.kind == SupertonicDownload.Kind.RUNNING) {
        label(body, "डाउनलोड हो रहा है — Supertonic 3 (चारों महिला आवाज़ें एक साथ)")
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = st.pct }
        body.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tv = stSmall(body, "${st.pct}% • ${st.line}", 0xB3FFFFFF.toInt())
        keep(pb, tv)
        row(body, button("रोकें") { SupertonicDownload.stop() })
        hint(body, "ऐप बंद न करें। नेटवर्क टूटा तो अपने-आप फिर कोशिश होगी; रोका तो अगली बार जहाँ रुका वहीं से।")
        return
    }

    val installed = SupertonicStore.installed(app)
    if (!installed) {
        // ---- डाउनलोड से पहले: सिर्फ़ बटन (स्विच नहीं)
        if (st.kind == SupertonicDownload.Kind.FAILED && st.msg != null) {
            stSmall(body, st.msg, 0xFFFF8A80.toInt())
            row(body, button("दोबारा कोशिश करें") { SupertonicDownload.start(app, st.voice.ifBlank { SupertonicStore.voice(app) }); redraw() })
        } else {
            row(body, button("डाउनलोड करें") { SupertonicDownload.start(app, SupertonicStore.voice(app)); redraw() })
        }
        hint(body, "एक बार करीब ${(SupertonicStore.MODEL_SIZE + SupertonicStore.LIBS.first().zipEnd) shr 20} MB डाउनलोड (Wi-Fi बेहतर), इंटरनेट से — ऐप के अंदर नहीं, ऐप का साइज़ नहीं बढ़ता। उसके बाद बिना इंटरनेट चलेगा। " +
            "एक ही मॉडल में ${SupertonicStore.VOICES.size} नेचुरल महिला आवाज़ें: " + SupertonicStore.VOICES.joinToString(", ") { it.title.substringBefore(" —") } + " (44 kHz, हाई क्वालिटी)।")
        return
    }

    // ---- डाउनलोड हो चुका: ON/OFF स्विच (डिफ़ॉल्ट OFF)
    val on = SupertonicStore.on(app) && SupertonicStore.installed(app)
    val sw = switch(body, "Supertonic 3 आवाज़ चालू (चालू = सिर्फ़ Supertonic बोलेगा, DK लाइव / Gemini TTS अपने-आप बंद)", on)
    sw.setOnCheckedChangeListener { _, v ->
        if (!v && SupertonicStore.on(app)) {
            // नवंबर 2026 (मालिक): बंद करने से अपने-आप कोई दूसरा इंजन चालू नहीं - एक आवाज़ हमेशा चालू, दूसरी खुद चुनो
            toast("Supertonic बंद नहीं हुआ — एक आवाज़ हमेशा चालू रहती है। ऊपर \"बोलने वाला इंजन\" में DK लाइव या Gemini TTS चालू करें, Supertonic अपने-आप बंद हो जाएगा")
        } else stToggle(v)
        redraw()
    }
    if (st.kind == SupertonicDownload.Kind.FAILED && st.msg != null) {
        stSmall(body, st.msg, 0xFFFF8A80.toInt())
        row(body, button("दोबारा कोशिश करें") { SupertonicDownload.start(app, st.voice); redraw() })
    }
    if (on) {
        hint(body, "चालू है — Gemini सिर्फ़ जवाब लिखता है, बोलता हमेशा Supertonic है (अपने-आप Gemini TTS पर नहीं जाता)। Supertonic में कभी दिक़्क़त हो तो उसी पल Gemini Live की सीधी आवाज़, और थोड़ी देर में फिर Supertonic।")
        stTune(body, redraw)
    }
    else hint(body, "बंद है — DK ऊपर चुने इंजन (DK लाइव / Gemini TTS) से बोल रहा है। चालू करने पर सिर्फ़ Supertonic बोलेगा, बाकी दोनों अपने-आप बंद।")

    row(body, button("मॉडल हटाएँ (${SupertonicDownload.mb(SupertonicStore.diskBytes(app))} MB खाली करें)") {
        AlertDialog.Builder(this).setTitle("Supertonic मॉडल हटाएँ?")
            .setMessage("फ़ोन से Supertonic की सारी फ़ाइलें हट जाएँगी और DK Gemini Live की आवाज़ में बोलेगा। दोबारा चाहिए तो फिर डाउनलोड करना होगा।")
            .setPositiveButton("हटाएँ") { _, _ ->
                stToggle(false)            // मॉडल ही नहीं रहा - तब ऊपर चुना इंजन (DK लाइव / Gemini TTS)
                Thread({
                    SupertonicDownload.stop(); SupertonicTtsEngine.release()
                    SupertonicStore.delete(app)
                    runOnUiThread { SupertonicDownload.reset(); toast("Supertonic मॉडल हटा दिया"); redraw() }
                }, "dk-supertonic-delete").apply { isDaemon = true; start() }
            }.setNegativeButton("रहने दें", null).show()
    })
    hint(body, "आवाज़: Supertonic 3 (Supertone) — लाइसेंस OpenRAIL-M। इंजन: sherpa-onnx (Apache-2.0)।")
}

/** स्विच: ON = स्विच सेव + मॉडल मेमोरी में; OFF = चल रही/कतार की आवाज़ उसी पल बंद. दोनों में पुरानी सेटिंग वैसी ही */
private fun SettingsActivity.stToggle(on: Boolean) {
    val app = applicationContext
    if (on == SupertonicStore.on(app)) return
    SupertonicStore.setOn(app, on)
    if (on) {
        SupertonicStore.setError(app, null)
        VoiceEngines.clearFailed(SupertonicTtsEngine.ID)
        toast("Supertonic 3 आवाज़ चालू — अब सिर्फ़ Supertonic बोलेगा (DK लाइव / Gemini TTS बंद), DK चुप होते ही अगला जवाब Supertonic में")
        Thread({ SupertonicTtsEngine.warm(app) }, "dk-supertonic-warm").apply { isDaemon = true; start() }
    } else {
        SupertonicTtsEngine.stop()
        SpeechPipeline.stopAll()
        toast("Supertonic बंद — DK अब ऊपर चुने इंजन से बोलेगा")
        Thread({ SupertonicTtsEngine.release() }, "dk-supertonic-release").apply { isDaemon = true; start() }
    }
    VoiceEngines.changed()
}

/** स्विच ON होने पर ही: आवाज़ + इफ़ेक्ट + टेस्ट */
private fun SettingsActivity.stTune(body: LinearLayout, redraw: () -> Unit) {
    val app = applicationContext
    label(body, "आवाज़")
    val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    val cur = SupertonicStore.voice(app)
    SupertonicStore.VOICES.forEachIndexed { i, v ->
        g.addView(RadioButton(this).apply {
            id = 3300 + i; text = v.title
            textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((6 * dp).toInt(), (5 * dp).toInt(), 0, (5 * dp).toInt())
        })
        if (v.id == cur) g.check(3300 + i)
    }
    g.setOnCheckedChangeListener { _, id ->
        val v = SupertonicStore.VOICES.getOrNull(id - 3300) ?: return@setOnCheckedChangeListener
        SupertonicTtsEngine.setVoice(app, v.id)        // एक ही मॉडल - अगला वाक्य इसी आवाज़ में (न डाउनलोड, न दोबारा लोड)
    }
    body.addView(g)
    hint(body, "चारों नेचुरल महिला आवाज़ें (Supertonic 3, 44 kHz, हाई क्वालिटी) एक ही मॉडल में हैं — बदलने पर न डाउनलोड, न इंतज़ार। ▶ टेस्ट से चुनी आवाज़ सुनें।")

    pSlider(body, "स्पीड", 50, 200, SupertonicStore.speed(app), { if (it == 100) "1.00x (सामान्य)" else String.format(java.util.Locale.US, "%.2fx", it / 100f) }) { SupertonicStore.setInt(app, "speed", it) }
    pSlider(body, "पिच", 70, 140, SupertonicStore.pitch(app), { if (it == 100) "सामान्य" else if (it > 100) "+${it - 100}% ऊँची" else "${it - 100}% नीची" }) { SupertonicStore.setInt(app, "pitch", it) }

    val rv = switch(body, "रिवर्ब (हल्की गूँज — भरी-भरी, सुरीली आवाज़)", SupertonicStore.reverbOn(app))
    val rvBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(rvBox)
    fun rvDraw() {
        rvBox.removeAllViews()
        if (SupertonicStore.reverbOn(app)) pSlider(rvBox, "रिवर्ब की मात्रा", 5, 100, SupertonicStore.reverb(app).coerceAtLeast(5), { "$it%" }) { SupertonicStore.setInt(app, "reverb", it) }
    }
    rvDraw()
    rv.setOnCheckedChangeListener { _, v -> SupertonicStore.setReverbOn(app, v); rvDraw() }

    pSlider(body, "बास (गहराई)", 0, 12, SupertonicStore.bass(app), { if (it == 0) "बंद" else "+$it dB" }) { SupertonicStore.setInt(app, "bass", it) }
    pSlider(body, "ठहराव (वाक्यों के बीच)", 0, 300, SupertonicStore.pause(app), {
        val ms = it * 140 / 100
        if (it == 100) "सामान्य (पूर्ण विराम पर 140 ms)" else "$it% (पूर्ण विराम पर $ms ms)"
    }) { SupertonicStore.setInt(app, "pause", it) }

    val res = stSmall(body, stLatency(), 0xB3FFFFFF.toInt())
    row(body,
        button("▶ टेस्ट — नमूना सुनें") {
            res.text = if (SupertonicTtsEngine.isLoaded) "बज रहा है…" else "मॉडल मेमोरी में आ रहा है, फिर बजेगा…"
            Thread({
                val wasLoaded = SupertonicTtsEngine.isLoaded
                SupertonicTtsEngine.warm(app)
                val load = if (!wasLoaded) SupertonicTtsEngine.lastLoadMs else -1L
                val ms = try { SupertonicTtsEngine.preview(app, SupertonicTtsEngine.SAMPLE, SupertonicStore.voice(app)) } catch (e: Exception) { -2L }
                if (ms >= 0) VoiceEngines.recordPreview(app, SupertonicTtsEngine.ID, ms)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    res.text = when {
                        ms == -2L -> "नमूना नहीं बजा — ${SupertonicStore.error(app) ?: "दोबारा दबाएँ"}"
                        else -> stLatency() + (if (load >= 0) " • मॉडल लोड (एक बार): ${VoiceEngines.secs(load)}" else "")
                    }
                    if (ms == -2L && !SupertonicStore.on(app)) redraw()
                }
            }, "dk-supertonic-test").apply { isDaemon = true; start() }
        },
        button("सब डिफ़ॉल्ट पर") { SupertonicStore.resetTune(app); toast("Supertonic की आवाज़ डिफ़ॉल्ट पर (कोई इफ़ेक्ट नहीं)"); redraw() },
    )
    hint(body, "बदलाव अगले वाक्य से लगते हैं और फ़ोन में सेव रहते हैं। इफ़ेक्ट आवाज़ बनने के बाद लगते हैं।")
}

private fun SettingsActivity.stLatency(): String {
    val p = VoiceEngines.lastPreview(this, SupertonicTtsEngine.ID); val l = VoiceEngines.lastLatency(this, SupertonicTtsEngine.ID)
    val a = if (p >= 0) "टेस्ट: दबाने से पहली आवाज़ ${VoiceEngines.secs(p)}" else "टेस्ट दबाकर पहली आवाज़ का समय देखें"
    val b = if (l >= 0) " • बातचीत में (आपकी बात ख़त्म → पहली आवाज़): ${VoiceEngines.secs(l)}" else ""
    return a + b + "\n" + com.dk.assistant.voice.engine.SupertonicTiming.line()
}

private fun SettingsActivity.stSmall(box: LinearLayout, t: String, color: Int): TextView {
    val v = TextView(this).apply { text = t; textSize = 12f; setTextColor(color); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
    box.addView(v); return v
}

/** VoiceEngineCard जैसा ही स्लाइडर (वही रूप) - छूते ही सेव */
private fun SettingsActivity.pSlider(box: LinearLayout, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
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
