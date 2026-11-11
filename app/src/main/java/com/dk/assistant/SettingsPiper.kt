package com.dk.assistant

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.dk.assistant.voice.engine.PiperDownload
import com.dk.assistant.voice.engine.PiperStore
import com.dk.assistant.voice.engine.PiperTtsEngine
import com.dk.assistant.voice.engine.SpeechPipeline
import com.dk.assistant.voice.engine.VoiceEngines

// नवंबर 2026 (मालिक): Settings > आवाज़ > "Piper आवाज़" card - नई फ़ाइल. बाकी Settings में कुछ नहीं बदला,
// SettingsVoice.kt में सिर्फ़ एक card(...) { piperCard(it) } जुड़ा.
//
//  डाउनलोड से पहले: सिर्फ़ "डाउनलोड करें" (स्विच नहीं) • चलते समय: प्रतिशत + पट्टी + रोकें • टूटा: संदेश + "दोबारा कोशिश करें"
//  डाउनलोड के बाद: ON/OFF स्विच (डिफ़ॉल्ट OFF) + "मॉडल हटाएँ" • ON: आवाज़, स्पीड, पिच, रिवर्ब, बास, ठहराव, टेस्ट

internal fun SettingsActivity.piperCard(box: LinearLayout) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    var bar: ProgressBar? = null
    var line: TextView? = null
    var drawn: PiperDownload.Kind? = null
    lateinit var draw: () -> Unit
    draw = {
        drawn = PiperDownload.state.kind
        bar = null; line = null
        body.removeAllViews()
        piperBody(body, { draw() }) { b, l -> bar = b; line = l }
    }
    val l: (PiperDownload.State) -> Unit = { s ->
        if (!isFinishing && !isDestroyed) {
            if (s.kind == PiperDownload.Kind.RUNNING && drawn == PiperDownload.Kind.RUNNING && bar != null) {
                bar?.progress = s.pct; line?.text = "${s.pct}% • ${s.line}"
            } else {
                if (s.kind == PiperDownload.Kind.DONE) {
                    val app = applicationContext
                    PiperStore.setVoice(app, s.voice)       // नई आवाज़ - ON हो तो अगला वाक्य इसी से (मॉडल पीछे बदलता है)
                    Thread({ PiperTtsEngine.warm(app) }, "dk-piper-voice").apply { isDaemon = true; start() }
                    toast("Piper आवाज़ डाउनलोड हो गई — अब स्विच से चालू करें")
                }
                draw()
            }
        }
    }
    body.tag = l                 // view ही listener को पकड़े (card बंद = अपने-आप छूटे)
    PiperDownload.listen(l)
    draw()
}

private fun SettingsActivity.piperBody(body: LinearLayout, redraw: () -> Unit, keep: (ProgressBar?, TextView?) -> Unit) {
    val app = applicationContext
    val st = PiperDownload.state
    PiperStore.error(app)?.let { piperSmall(body, "ध्यान दें: $it", 0xFFFFB74D.toInt()) }

    // ---- डाउनलोड चल रहा
    if (PiperDownload.running || st.kind == PiperDownload.Kind.RUNNING) {
        label(body, "डाउनलोड हो रहा है — ${PiperStore.voiceOf(st.voice.ifBlank { PiperStore.voice(app) }).title}")
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = st.pct }
        body.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tv = piperSmall(body, "${st.pct}% • ${st.line}", 0xB3FFFFFF.toInt())
        keep(pb, tv)
        row(body, button("रोकें") { PiperDownload.stop() })
        hint(body, "ऐप बंद न करें। नेटवर्क टूटा तो अपने-आप फिर कोशिश होगी; रोका तो अगली बार जहाँ रुका वहीं से।")
        return
    }

    val installed = PiperStore.anyInstalled(app)
    if (!installed) {
        // ---- डाउनलोड से पहले: सिर्फ़ बटन (स्विच नहीं)
        if (st.kind == PiperDownload.Kind.FAILED && st.msg != null) {
            piperSmall(body, st.msg, 0xFFFF8A80.toInt())
            row(body, button("दोबारा कोशिश करें") { PiperDownload.start(app, st.voice.ifBlank { PiperStore.voice(app) }); redraw() })
        } else {
            row(body, button("डाउनलोड करें") { PiperDownload.start(app, PiperStore.voice(app)); redraw() })
        }
        hint(body, "एक बार करीब 73 MB डाउनलोड (Wi-Fi बेहतर), फ़ोन में करीब 90 MB जगह। उसके बाद बिना इंटरनेट चलेगा। " +
            "हिंदी आवाज़: ${PiperStore.voiceOf(PiperStore.DEFAULT_VOICE).title}, medium क्वालिटी। ऐप का साइज़ नहीं बढ़ता।")
        return
    }

    // ---- डाउनलोड हो चुका: ON/OFF स्विच (डिफ़ॉल्ट OFF)
    val on = PiperStore.on(app) && PiperStore.installed(app)
    val sw = switch(body, "Piper आवाज़ चालू (बंद = Gemini Live की सीधी आवाज़, पहले जैसी)", on)
    sw.setOnCheckedChangeListener { _, v -> piperToggle(v); redraw() }
    if (st.kind == PiperDownload.Kind.FAILED && st.msg != null) {
        piperSmall(body, st.msg, 0xFFFF8A80.toInt())
        row(body, button("दोबारा कोशिश करें") { PiperDownload.start(app, st.voice); redraw() })
    }
    if (on) piperTune(body, redraw)
    else hint(body, "बंद है — DK पहले की तरह Gemini Live की अपनी आवाज़ में बोल रहा है। चालू करने पर Gemini सिर्फ़ जवाब लिखेगा और Piper फ़ोन में ही बोलेगा।")

    row(body, button("मॉडल हटाएँ (${PiperDownload.mb(PiperStore.diskBytes(app))} MB खाली करें)") {
        AlertDialog.Builder(this).setTitle("Piper मॉडल हटाएँ?")
            .setMessage("फ़ोन से Piper की सारी फ़ाइलें हट जाएँगी और DK Gemini Live की आवाज़ में बोलेगा। दोबारा चाहिए तो फिर डाउनलोड करना होगा।")
            .setPositiveButton("हटाएँ") { _, _ ->
                piperToggle(false)
                Thread({
                    PiperDownload.stop(); PiperTtsEngine.release()
                    PiperStore.delete(app)
                    runOnUiThread { PiperDownload.reset(); toast("Piper मॉडल हटा दिया"); redraw() }
                }, "dk-piper-delete").apply { isDaemon = true; start() }
            }.setNegativeButton("रहने दें", null).show()
    })
    hint(body, "आवाज़ें: Piper (rhasspy) हिंदी मॉडल — लाइसेंस CC BY-NC-SA 4.0 (सिर्फ़ ग़ैर-व्यावसायिक इस्तेमाल)। इंजन: sherpa-onnx (Apache-2.0)।")
}

/** स्विच: ON = स्विच सेव + मॉडल मेमोरी में; OFF = चल रही/कतार की आवाज़ उसी पल बंद. दोनों में पुरानी सेटिंग वैसी ही */
private fun SettingsActivity.piperToggle(on: Boolean) {
    val app = applicationContext
    if (on == PiperStore.on(app)) return
    PiperStore.setOn(app, on)
    if (on) {
        PiperStore.setError(app, null)
        toast("Piper आवाज़ चालू — DK चुप होते ही अगला जवाब Piper में")
        Thread({ PiperTtsEngine.warm(app) }, "dk-piper-warm").apply { isDaemon = true; start() }
    } else {
        PiperTtsEngine.stop()
        SpeechPipeline.stopAll()
        toast("Piper बंद — DK फिर Gemini Live की सीधी आवाज़ में")
        Thread({ PiperTtsEngine.release() }, "dk-piper-release").apply { isDaemon = true; start() }
    }
    VoiceEngines.changed()
}

/** स्विच ON होने पर ही: आवाज़ + इफ़ेक्ट + टेस्ट */
private fun SettingsActivity.piperTune(body: LinearLayout, redraw: () -> Unit) {
    val app = applicationContext
    label(body, "आवाज़")
    val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
    val cur = PiperStore.voice(app)
    PiperStore.VOICES.forEachIndexed { i, v ->
        val have = PiperStore.voiceOk(app, v.id)
        g.addView(RadioButton(this).apply {
            id = 3300 + i; text = if (have) v.title else "${v.title} — डाउनलोड करें (~${v.onnxSize shr 20} MB)"
            textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((6 * dp).toInt(), (5 * dp).toInt(), 0, (5 * dp).toInt())
        })
        if (v.id == cur) g.check(3300 + i)
    }
    g.setOnCheckedChangeListener { _, id ->
        val v = PiperStore.VOICES.getOrNull(id - 3300) ?: return@setOnCheckedChangeListener
        if (PiperStore.voiceOk(app, v.id)) Thread({ PiperTtsEngine.setVoice(app, v.id); PiperTtsEngine.warm(app) }, "dk-piper-voice").apply { isDaemon = true; start() }
        else { PiperDownload.start(app, v.id); redraw() }
    }
    body.addView(g)
    hint(body, "सारी आवाज़ें medium क्वालिटी की हैं (हिंदी के लिए Piper में low / high मॉडल उपलब्ध नहीं)।")

    pSlider(body, "स्पीड", 50, 200, PiperStore.speed(app), { if (it == 100) "1.00x (सामान्य)" else String.format(java.util.Locale.US, "%.2fx", it / 100f) }) { PiperStore.setInt(app, "speed", it) }
    pSlider(body, "पिच", 70, 140, PiperStore.pitch(app), { if (it == 100) "सामान्य" else if (it > 100) "+${it - 100}% ऊँची" else "${it - 100}% नीची" }) { PiperStore.setInt(app, "pitch", it) }

    val rv = switch(body, "रिवर्ब (हल्की गूँज — भरी-भरी, सुरीली आवाज़)", PiperStore.reverbOn(app))
    val rvBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(rvBox)
    fun rvDraw() {
        rvBox.removeAllViews()
        if (PiperStore.reverbOn(app)) pSlider(rvBox, "रिवर्ब की मात्रा", 5, 100, PiperStore.reverb(app).coerceAtLeast(5), { "$it%" }) { PiperStore.setInt(app, "reverb", it) }
    }
    rvDraw()
    rv.setOnCheckedChangeListener { _, v -> PiperStore.setReverbOn(app, v); rvDraw() }

    pSlider(body, "बास (गहराई)", 0, 12, PiperStore.bass(app), { if (it == 0) "बंद" else "+$it dB" }) { PiperStore.setInt(app, "bass", it) }
    pSlider(body, "ठहराव (वाक्यों के बीच)", 0, 300, PiperStore.pause(app), {
        val ms = it * 140 / 100
        if (it == 100) "सामान्य (पूर्ण विराम पर 140 ms)" else "$it% (पूर्ण विराम पर $ms ms)"
    }) { PiperStore.setInt(app, "pause", it) }

    val res = piperSmall(body, piperLatency(), 0xB3FFFFFF.toInt())
    row(body,
        button("▶ टेस्ट — नमूना सुनें") {
            res.text = if (PiperTtsEngine.isLoaded) "बज रहा है…" else "मॉडल मेमोरी में आ रहा है, फिर बजेगा…"
            Thread({
                val wasLoaded = PiperTtsEngine.isLoaded
                PiperTtsEngine.warm(app)
                val load = if (!wasLoaded) PiperTtsEngine.lastLoadMs else -1L
                val ms = try { PiperTtsEngine.preview(app, PiperTtsEngine.SAMPLE, PiperStore.voice(app)) } catch (e: Exception) { -2L }
                if (ms >= 0) VoiceEngines.recordPreview(app, PiperTtsEngine.ID, ms)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    res.text = when {
                        ms == -2L -> "नमूना नहीं बजा — ${PiperStore.error(app) ?: "दोबारा दबाएँ"}"
                        else -> piperLatency() + (if (load >= 0) " • मॉडल लोड (एक बार): ${VoiceEngines.secs(load)}" else "")
                    }
                    if (ms == -2L && !PiperStore.on(app)) redraw()
                }
            }, "dk-piper-test").apply { isDaemon = true; start() }
        },
        button("सब डिफ़ॉल्ट पर") { PiperStore.resetTune(app); toast("Piper की आवाज़ डिफ़ॉल्ट पर (कोई इफ़ेक्ट नहीं)"); redraw() },
    )
    hint(body, "बदलाव अगले वाक्य से लगते हैं और फ़ोन में सेव रहते हैं। इफ़ेक्ट आवाज़ बनने के बाद लगते हैं।")
}

private fun SettingsActivity.piperLatency(): String {
    val p = VoiceEngines.lastPreview(this, PiperTtsEngine.ID); val l = VoiceEngines.lastLatency(this, PiperTtsEngine.ID)
    val a = if (p >= 0) "टेस्ट: दबाने से पहली आवाज़ ${VoiceEngines.secs(p)}" else "टेस्ट दबाकर पहली आवाज़ का समय देखें"
    val b = if (l >= 0) " • बातचीत में (आपकी बात ख़त्म → पहली आवाज़): ${VoiceEngines.secs(l)}" else ""
    return a + b
}

private fun SettingsActivity.piperSmall(box: LinearLayout, t: String, color: Int): TextView {
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
