package com.dk.assistant.dkai

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक - M3): DKAI Broadcast Studio - सिर्फ़ आवाज़ (वीडियो बिल्कुल नहीं).
 * विषय लिखो -> AI स्क्रिप्ट ([Host] / [Narrator] …) -> हर बोलने वाले की अपनी महिला/पुरुष आवाज़ (Gemini TTS)
 * -> एक WAV -> चलाओ / रफ़्तार / WhatsApp-Telegram पर भेजो / Downloads में सेव.
 *
 * 13 नवंबर 2026 (मालिक - अपग्रेड): पहले विषय पर रिसर्च (इंटरनेट खोज), फिर स्क्रिप्ट; All Voices = दो किरदारों की असली बातचीत
 * (मोनोलॉग आए तो दोबारा); भाषा Auto / शुद्ध हिंदी / English / मराठी / ગુજરાતી / বাংলা; रफ़्तार 0.5x-2.0x (default 1.3x) -
 * चलाने में भी और शेयर/सेव फ़ाइल में भी; माइक से विषय बोलना; Seekbar; WhatsApp/Telegram पर M4A (वहाँ WAV नहीं चलता).
 */
class DkaiBroadcast(private val s: DkaiScreen) {
    private val c = s.activity
    private val ui = Handler(Looper.getMainLooper())
    private fun d(v: Float) = DkaiUi.d(c, v)

    private lateinit var layer: DkaiUi.Layer
    private lateinit var title: TextView
    private lateinit var state: TextView
    private lateinit var script: TextView
    private lateinit var playBtn: ImageView
    private lateinit var wave: Wave
    private lateinit var time: TextView
    private lateinit var speedPill: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var micBtn: ImageView
    private var rec: SpeechRecognizer? = null
    private var listening = false
    private lateinit var voicePill: TextView
    private lateinit var langPill: TextView
    private lateinit var prompt: EditText
    private lateinit var goBtn: FrameLayout
    private var player: MediaPlayer? = null
    private var wav: File? = null
    private var format = DkaiKit.FORMATS[0]
    private var voicePick = Prefs.str(c, PREF_VOICE, DkaiKit.ALL_VOICES)
    private var lang = DkaiKit.normLang(Prefs.str(c, PREF_LANG, DkaiKit.LANGS[0]))
    private var speed = DkaiKit.clampSpeed(Prefs.str(c, PREF_SPEED, DkaiKit.DEFAULT_SPEED.toString()).toIntOrNull() ?: DkaiKit.DEFAULT_SPEED)
    private var busy = false

    companion object {
        const val DIR = "dkai_broadcast"
        const val PREF_NO = "dkai_broadcast_no"
        const val PREF_VOICE = "dkai_bc_voice"
        const val PREF_LANG = "dkai_bc_lang"
        /** v2: नया default 1.3x (पुरानी सेव 1.0x सिर्फ़ पुराना default थी) */
        const val PREF_SPEED = "dkai_bc_speed_v2"
        fun no(c: Context) = Prefs.str(c, PREF_NO, "1").toIntOrNull() ?: 1
        fun dir(c: Context) = File(c.filesDir, DIR).apply { mkdirs() }
    }

    /** [topic] दिया = होम पर Broadcast मोड में लिखा विषय: नया Broadcast बनाकर तुरंत बनाना शुरू */
    fun open(topic: String? = null) {
        val (page, body, right) = DkaiUi.page(c, "Broadcast Studio") { close() }
        state = DkaiUi.status(c, "READY", DkaiKit.EMERALD)
        right.addView(state)
        right.addView(DkaiTheme.pill(c, "+ New", DkaiTheme.PILL, DkaiTheme.WHITE, 12.5f).apply {
            minHeight = d(36f); contentDescription = "नया Broadcast"; setOnClickListener { newOne() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })

        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16f), d(10f), d(16f), d(10f)) }
        title = DkaiTheme.text(c, "Broadcast #${no(c)}", 15f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD)
        col.addView(title)
        col.addView(DkaiTheme.text(c, "सिर्फ़ आवाज़ • कई आवाज़ें अपने-आप (महिला / पुरुष)", 11.5f, DkaiTheme.GRAY).apply { setPadding(0, d(3f), 0, d(10f)) })

        // ---- स्क्रिप्ट
        script = DkaiTheme.text(c, "", 14f, DkaiTheme.SUB).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true); UiLang.keep(this) }
        val sc = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(12f), d(14f), d(12f)); background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER) }
        val sh = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sh.addView(DkaiUi.label(c, "Script").apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        sh.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.EDIT, "स्क्रिप्ट खुद लिखें / बदलें", DkaiTheme.CYAN, 38f) { editScript() })
        sh.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.COPY, "स्क्रिप्ट कॉपी", DkaiTheme.GRAY, 38f) { DkaiUi.copy(c, "Script", script.text.toString()) })
        sh.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.TRASH, "स्क्रिप्ट और आवाज़ मिटाएँ", DkaiTheme.RED, 38f) { deleteCurrent() })
        sc.addView(sh)
        sc.addView(MaxScroll(c, d(220f)).apply { addView(script) }, LinearLayout.LayoutParams(-1, -2))
        col.addView(sc, LinearLayout.LayoutParams(-1, -2))

        // ---- DKAI STUDIO VOICE (player)
        val pc = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14f), d(12f), d(14f), d(12f))
            background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER) }
        pc.addView(DkaiTheme.text(c, "DKAI STUDIO VOICE", 11f, DkaiTheme.CYAN, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.14f })
        val pr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10f), 0, d(6f)) }
        playBtn = DkaiUi.iconBtn(c, DkaiIcon.Kind.PLAY_FILL, "चलाएँ", DkaiTheme.ON, 48f, DkaiTheme.BLUE) { toggle() }
        pr.addView(playBtn)
        wave = Wave(c).apply { setOnTouchListener { v, e -> seek(e.x / v.width.coerceAtLeast(1)); true } }
        pr.addView(wave, LinearLayout.LayoutParams(0, d(44f), 1f).apply { leftMargin = d(10f) })
        pc.addView(pr)
        seekBar = SeekBar(c).apply {
            max = 1000; contentDescription = "आवाज़ में आगे-पीछे"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, v: Int, user: Boolean) { if (user) seek(v / 1000f) }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        pc.addView(seekBar, LinearLayout.LayoutParams(-1, -2))
        val tr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        time = DkaiTheme.text(c, "00:00 / 00:00", 12f, DkaiTheme.GRAY, DkaiTheme.MONO)
        tr.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        tr.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.SHARE, "शेयर (WhatsApp / Telegram)", DkaiTheme.CYAN, 42f) { share() })
        tr.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.DOWNLOAD, "Download Audio", DkaiTheme.GREEN, 42f) { download() })
        tr.addView(DkaiUi.iconBtn(c, DkaiIcon.Kind.TRASH, "आवाज़ मिटाएँ", DkaiTheme.RED, 42f) { deleteCurrent() })
        pc.addView(tr)
        // रफ़्तार: 0.5x - 2.0x का slider (default 1.3x) - चलाने और शेयर/सेव फ़ाइल दोनों में
        val sr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6f), 0, 0) }
        sr.addView(DkaiTheme.text(c, "Speed", 12f, DkaiTheme.GRAY, DkaiTheme.SANS_BOLD))
        val speedBar = SeekBar(c).apply {
            max = DkaiKit.STUDIO_SPEEDS.size - 1; progress = DkaiKit.STUDIO_SPEEDS.indexOf(speed).coerceAtLeast(0); contentDescription = "रफ़्तार"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, v: Int, user: Boolean) { setSpeed(DkaiKit.STUDIO_SPEEDS[v.coerceIn(0, DkaiKit.STUDIO_SPEEDS.size - 1)]) }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        sr.addView(speedBar, LinearLayout.LayoutParams(0, -2, 1f))
        speedPill = DkaiTheme.pill(c, DkaiCore.speedLabel(speed), DkaiTheme.PILL, DkaiTheme.WHITE, 12f).apply { minHeight = d(34f); contentDescription = "रफ़्तार चुनें"; setOnClickListener { pickSpeed { speedBar.progress = DkaiKit.STUDIO_SPEEDS.indexOf(speed).coerceAtLeast(0) } } }
        sr.addView(speedPill)
        pc.addView(sr)
        col.addView(pc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12f) })

        // ---- format
        col.addView(DkaiUi.label(c, "Format"))
        // 14 नवंबर 2026: विषय के साथ खुला = format विषय से (कहानी -> Story, खबर -> News …)
        topic?.trim()?.takeIf { it.isNotEmpty() }?.let { format = DkaiKit.formatFor(it) }
        col.addView(DkaiUi.chips(c, DkaiKit.FORMATS, DkaiKit.FORMATS.indexOf(format).coerceAtLeast(0)) { format = DkaiKit.FORMATS[it] })
        body.addView(ScrollView(c).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- नीचे के controls + prompt
        val bot = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), d(8f), d(12f), d(10f)); setBackgroundColor(DkaiTheme.NAV) }
        val cr = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        voicePill = DkaiTheme.pill(c, voicePick + "  ▾", DkaiTheme.PILL, DkaiTheme.WHITE, 12f).apply { minHeight = d(36f); setOnClickListener { pickVoice() } }
        langPill = DkaiTheme.pill(c, lang + "  ▾", DkaiTheme.PILL, DkaiTheme.WHITE, 12f).apply { minHeight = d(36f); setOnClickListener { pickLang() } }
        cr.addView(voicePill); cr.addView(langPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) })
        bot.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(cr) })
        val ir = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8f), 0, 0) }
        prompt = DkaiUi.field(c, "Direct news bulletins, podcasts, speeches, stories, or lectures...", multi = true).apply { minLines = 1; maxLines = 4 }
        ir.addView(prompt, LinearLayout.LayoutParams(0, -2, 1f))
        micBtn = DkaiUi.iconBtn(c, DkaiIcon.Kind.MIC, "माइक से विषय बोलें", DkaiTheme.WHITE, 44f) { mic() }
        ir.addView(micBtn, LinearLayout.LayoutParams(d(44f), d(44f)).apply { leftMargin = d(6f) })
        goBtn = FrameLayout(c).apply {
            background = DkaiTheme.press(DkaiTheme.oval(DkaiTheme.RED), DkaiTheme.oval(0xFFFFFFFF.toInt())); elevation = d(4f).toFloat()
            isClickable = true; isFocusable = true; contentDescription = "आवाज़ बनाएँ"; setOnClickListener { generate() }
            addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.RECORD, DkaiTheme.ON)) }, FrameLayout.LayoutParams(d(22f), d(22f), Gravity.CENTER))
        }
        ir.addView(goBtn, LinearLayout.LayoutParams(d(52f), d(52f)).apply { leftMargin = d(10f) })
        bot.addView(ir)
        body.addView(bot, LinearLayout.LayoutParams(-1, -2))

        layer = DkaiUi.open(s.root, page, DkaiUi.Place.FULL, onClose = { releaseAll() })
        loadLatest()
        val t = topic?.trim().orEmpty()
        if (t.isNotEmpty()) { newOne(); prompt.setText(t); prompt.setSelection(t.length); generate() }
    }

    private fun close() = DkaiUi.close(s.root, layer)

    private fun release() { ui.removeCallbacks(tick); try { player?.release() } catch (_: Exception) {}; player = null }

    private fun releaseAll() { release(); stopMic() }

    // ================================================================== चुनाव
    private fun pickVoice() {
        val vs = DkaiKit.VOICE_MENU.mapNotNull { n -> DkaiKit.STUDIO_VOICES.firstOrNull { it.name == n } }
        val items = listOf(DkaiKit.ALL_VOICES) + vs.map { it.name }
        val labels = listOf("All Voices (दो किरदारों की बातचीत - महिला + पुरुष)") + vs.map { "${it.name} - ${if (it.female) "Female" else "Male"} (सिंगल वॉइस)" }
        android.app.AlertDialog.Builder(c).setTitle("आवाज़").setItems(labels.toTypedArray()) { _, i ->
            voicePick = items[i]; Prefs.edit(c) { putString(PREF_VOICE, voicePick) }; voicePill.text = voicePick + "  ▾"
        }.show()
    }

    private fun pickLang() {
        android.app.AlertDialog.Builder(c).setTitle("Language").setItems(DkaiKit.LANGS.toTypedArray()) { _, i ->
            lang = DkaiKit.LANGS[i]; Prefs.edit(c) { putString(PREF_LANG, lang) }; langPill.text = lang + "  ▾"
        }.show()
    }

    private fun pickSpeed(after: () -> Unit) {
        val sp = DkaiKit.STUDIO_SPEEDS
        android.app.AlertDialog.Builder(c).setTitle("Speed").setItems(sp.map { (if (it == DkaiKit.DEFAULT_SPEED) "★ " else "") + DkaiCore.speedLabel(it) }.toTypedArray()) { _, i ->
            setSpeed(sp[i]); after()
        }.show()
    }

    private fun setSpeed(v: Int) {
        val n = DkaiKit.clampSpeed(v)
        if (n == speed && speedPill.text == DkaiCore.speedLabel(n)) return
        speed = n; Prefs.edit(c) { putString(PREF_SPEED, speed.toString()) }; speedPill.text = DkaiCore.speedLabel(speed)
        if (player?.isPlaying == true) applySpeed()
    }

    // ================================================================== माइक से विषय
    private fun mic() {
        if (listening) { stopMic(); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(c, arrayOf(android.Manifest.permission.RECORD_AUDIO), 4711)
            s.toast("माइक की अनुमति दें, फिर माइक दोबारा दबाएँ"); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) { s.toast("इस फ़ोन पर बोलकर लिखना उपलब्ध नहीं - विषय लिखें"); return }
        val before = prompt.text.toString().trim()
        try {
            rec?.destroy()
            rec = SpeechRecognizer.createSpeechRecognizer(c).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: android.os.Bundle?) { setState("LISTENING", DkaiTheme.PINK) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(t: Int, p: android.os.Bundle?) {}
                    override fun onPartialResults(r: android.os.Bundle?) {
                        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (t.isNotBlank()) prompt.setText(listOf(before, t).filter { it.isNotBlank() }.joinToString(" "))
                    }
                    override fun onResults(r: android.os.Bundle?) {
                        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                        stopMic()
                        if (t.isBlank()) { setState("READY", DkaiKit.EMERALD); s.toast("कुछ सुनाई नहीं दिया - फिर बोलें"); return }
                        prompt.setText(listOf(before, t).filter { it.isNotBlank() }.joinToString(" ")); prompt.setSelection(prompt.text.length)
                        generate()                                   // बोला विषय = सीधे रिसर्च + स्क्रिप्ट + आवाज़
                    }
                    override fun onError(e: Int) {
                        stopMic(); setState("READY", DkaiKit.EMERALD)
                        s.toast(if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "कुछ सुनाई नहीं दिया - फिर बोलें" else "माइक नहीं चला ($e) - विषय लिख दें")
                    }
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, DkaiKit.micLocale(lang))
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
            }
            listening = true; micBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.PINK)); s.hideKeyboard()
        } catch (e: Exception) { stopMic(); s.toast("माइक नहीं चला: ${e.message?.take(60)}") }
    }

    private fun stopMic() {
        listening = false
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
        if (::micBtn.isInitialized) micBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.WHITE))
    }

    private fun editScript() {
        val e = DkaiUi.field(c, "Host (female): नमस्ते…\nGuest (male): …", script.text.toString(), multi = true).apply { minLines = 6; maxLines = 14 }
        val box = FrameLayout(c).apply { setPadding(d(16f), d(8f), d(16f), 0); addView(e) }
        android.app.AlertDialog.Builder(c).setTitle("स्क्रिप्ट (Name: बात)").setView(box)
            .setPositiveButton("इसी से आवाज़ बनाएँ") { _, _ -> val t = e.text.toString(); if (t.isNotBlank()) { showScript(DkaiKit.parseScript(t)); generate(t) } }
            .setNegativeButton("रद्द", null).show()
    }

    // ================================================================== बनाना
    private fun setState(label: String, color: Int) { state.text = "● $label"; state.setTextColor(color)
        state.background = DkaiTheme.round(c, color and 0x1FFFFFFF, 999f, color and 0x55FFFFFF) }

    private fun showScript(lines: List<DkaiKit.Line>) {
        val cast = DkaiKit.studioCast(lines, voicePick)
        val sb = SpannableStringBuilder()
        for (l in lines) {
            val v = cast[l.speaker]
            val col = if (v?.female ?: l.female) DkaiTheme.PINK else DkaiTheme.CYAN
            val a = sb.length
            sb.append("[${l.speaker}]")
            sb.setSpan(ForegroundColorSpan(col), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (v != null) { val b = sb.length; sb.append(" · ${v.name}"); sb.setSpan(ForegroundColorSpan(DkaiTheme.HINT), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            // 14 नवंबर 2026 (मालिक): किस अंदाज़ में बोला जाएगा, वह भी लिखा दिखे
            if (l.tone.isNotBlank()) { val b = sb.length; sb.append(" · अंदाज़: ${l.tone}"); sb.setSpan(ForegroundColorSpan(DkaiTheme.AMBER), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            sb.append("\n").append(l.text).append("\n\n")
        }
        script.text = if (sb.isEmpty()) "विषय लिखें या माइक से बोलें, फिर लाल बटन - DK पहले रिसर्च करेगा, फिर स्क्रिप्ट लिखकर हर किरदार की अलग आवाज़ बनाएगा।" else sb.trimEnd()
    }

    /** [own] = खुद लिखी स्क्रिप्ट (AI से नहीं लिखवानी) */
    private fun generate(own: String? = null) {
        if (busy) { s.toast("अभी बन रहा है…"); return }
        val topic = own ?: prompt.text.toString().trim()
        if (topic.isEmpty()) { s.toast("पहले विषय लिखें (जैसे: आज की टेक खबरें)"); return }
        val keys = Prefs.liveGeminiKeys(c).ifEmpty { listOf(Prefs.ttsGeminiKey(c)) }.filter { it.isNotBlank() }
        if (keys.isEmpty()) { s.toast("आवाज़ के लिए Gemini की key चाहिए (सेटिंग्स → DK लाइव)"); return }
        busy = true; s.hideKeyboard(); release(); wave.peaks = null; wave.progress = 0f; wave.invalidate()
        val multi = voicePick == DkaiKit.ALL_VOICES
        c.lifecycleScope.launch {
            try {
                // 1) रिसर्च (इंटरनेट खोज - न हो सके तो AI की अपनी जानकारी) -> 2) स्क्रिप्ट
                var lines = if (own != null) DkaiKit.parseScript(own) else DkaiKit.ownScript(topic)
                var sources: List<Pair<String, String>> = emptyList()
                if (lines.isEmpty()) {
                    setState("RESEARCHING", DkaiTheme.AMBER)
                    val found = withContext(Dispatchers.IO) { research(topic) }
                    val facts = found?.text.orEmpty(); sources = found?.sources.orEmpty()
                    setState("WRITING SCRIPT", DkaiTheme.VIOLET)
                    lines = DkaiKit.parseScript(writeScript(DkaiKit.scriptSystem(format, lang, multi, facts), topic))
                    // नियम टूटा (All Voices में मोनोलॉग / शुद्ध हिंदी में अंग्रेज़ी) = एक बार दोबारा, कड़े निर्देश से
                    val why = when {
                        lines.isEmpty() -> ""
                        multi && !DkaiKit.isDialogue(lines) -> "Your previous script was a monologue. Write it again as a real back-and-forth conversation between the two characters, alternating every turn."
                        DkaiKit.needsPureHindi(lang, lines) -> "Your previous script used English words or Latin letters. Write it again using ONLY Devanagari Hindi words in the spoken text."
                        else -> ""
                    }
                    if (why.isNotEmpty()) {
                        setState("REWRITING", DkaiTheme.VIOLET)
                        val again = try { DkaiKit.parseScript(writeScript(DkaiKit.scriptSystem(format, lang, multi, facts, why), topic)) } catch (e: Exception) { emptyList() }
                        if (again.isNotEmpty() && (!multi || DkaiKit.isDialogue(again) || !DkaiKit.isDialogue(lines)) &&
                            DkaiKit.latinShare(again) <= DkaiKit.latinShare(lines) + 0.05f) lines = again
                    }
                }
                if (lines.isEmpty()) throw IllegalStateException("स्क्रिप्ट खाली आई")
                showScript(lines)
                // 2) आवाज़ें
                val cast = DkaiKit.studioCast(lines, voicePick)
                val segs = DkaiKit.segments(lines)
                val style = DkaiKit.studioStyle(format, lang)
                val pcm = ByteArrayOutputStream(); var rate = 24000
                for ((i, seg) in segs.withIndex()) {
                    setState("VOICE ${i + 1}/${segs.size}", DkaiTheme.CYAN)
                    val part = withContext(Dispatchers.IO) { segmentPcm(keys, seg, cast, style) }
                    rate = part.second
                    if (pcm.size() > 0) pcm.write(ByteArray(rate / 4 * 2))          // बीच में 0.25 सेकंड रुकना
                    pcm.write(part.first)
                }
                // 3) सेव
                val n = no(c)
                val out = withContext(Dispatchers.IO) {
                    val f = File(dir(c), "broadcast_$n.wav")
                    f.writeBytes(GeminiTts.pcmToWav(pcm.toByteArray(), rate))
                    File(dir(c), "broadcast_$n.json").writeText(JSONObject().put("topic", topic).put("format", format).put("lang", lang)
                        .put("voice", voicePick).put("speed", speed)
                        .put("sources", org.json.JSONArray().apply { sources.forEach { (t, u) -> put(JSONObject().put("t", t).put("u", u)) } }).put("script", DkaiCast.scriptText(lines)).toString())
                    f
                }
                load(out, pcm.toByteArray())
                setState("READY", DkaiKit.EMERALD)
                if (own == null) prompt.setText("")
                toggle()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                setState("ERROR", DkaiTheme.RED)
                s.toast("आवाज़ नहीं बनी: ${e.message?.take(140)}")
            } finally { busy = false }
        }
    }

    /** विषय पर रिसर्च: इंटरनेट खोज (Tavily / Exa / Gemini + Google) - न हो सके तो null (तब AI अपनी पक्की जानकारी से) */
    private suspend fun research(topic: String): SearchResult? = try {
        val b = BrainManager(c)
        if (!b.anySearch()) null
        else b.search(DkaiKit.researchQuery(topic, format), deep = true).let { r -> r.copy(text = WebCite.strip(r.text)) }.takeIf { it.text.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("Broadcast research: ${e.message?.take(80)}"); null }

    /** लंबी स्क्रिप्ट: कहानी वाली AI (8k token) - न मिले तो छोटा तेज़ रास्ता */
    private suspend fun writeScript(sys: String, topic: String): String = withContext(Dispatchers.IO) {
        try { StoryAi.fromPrefs(c)?.write(sys, listOf("user" to topic))?.text?.takeIf { it.isNotBlank() } } catch (e: Exception) { DKLog.d("Broadcast script: ${e.message?.take(80)}"); null }
    } ?: BrainManager(c).quickText(sys, topic) ?: throw IllegalStateException("AI ने स्क्रिप्ट नहीं लिखी (AI की key / limit देखें)")

    /** एक टुकड़ा (<= 2 बोलने वाले) - 14 नवंबर 2026: साझा इंजन ([DkaiCastTts]) - हर लाइन का अंदाज़ भी */
    private fun segmentPcm(keys: List<String>, seg: List<DkaiKit.Line>, cast: Map<String, DkaiKit.StudioVoice>, style: String): Pair<ByteArray, Int> =
        DkaiCastTts.segmentPcm(keys, seg, cast, style)

    // ================================================================== चलाना
    private fun load(f: File, pcm: ByteArray?) {
        wav = f
        val raw = pcm ?: try { DkaiKit.wavPcm(f.readBytes()).first } catch (_: Exception) { ByteArray(0) }
        wave.peaks = DkaiKit.peaks(raw, 64); wave.progress = 0f; wave.invalidate()
        release()
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare()
                setOnCompletionListener { playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLAY_FILL, DkaiTheme.ON)); wave.playing = false; wave.progress = 1f; wave.invalidate(); showTime() }
            }
        } catch (e: Exception) { s.toast("चल नहीं पाया: ${e.message?.take(80)}") }
        showTime()
    }

    private fun toggle() {
        val p = player ?: run { s.toast("पहले आवाज़ बनाएँ (लाल बटन)"); return }
        if (p.isPlaying) { p.pause(); playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PLAY_FILL, DkaiTheme.ON)); ui.removeCallbacks(tick) }
        else { applySpeed(); p.start(); playBtn.setImageDrawable(DkaiIcon(DkaiIcon.Kind.PAUSE, DkaiTheme.ON)); ui.post(tick) }
    }

    private fun applySpeed() {
        val p = player ?: return
        try { val was = p.isPlaying; p.playbackParams = p.playbackParams.setSpeed(speed / 100f); if (!was) p.pause() } catch (_: Exception) {}
    }

    private fun seek(f: Float) {
        val p = player ?: return
        try { p.seekTo((p.duration * f.coerceIn(0f, 1f)).toInt()); wave.progress = f.coerceIn(0f, 1f); wave.invalidate(); showTime() } catch (_: Exception) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            try { if (p.duration > 0) wave.progress = p.currentPosition / p.duration.toFloat() } catch (_: Exception) {}
            wave.playing = try { p.isPlaying } catch (_: Exception) { false }
            wave.invalidate(); showTime()
            if (wave.playing) ui.postDelayed(this, 120)
        }
    }

    private fun showTime() {
        val p = player
        val cur = try { p?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        val dur = try { p?.duration ?: 0 } catch (_: Exception) { 0 }
        time.text = DkaiKit.clock(cur.toLong()) + " / " + DkaiKit.clock(dur.toLong())
        if (::seekBar.isInitialized && !seekBar.isPressed) seekBar.progress = if (dur > 0) (cur * 1000L / dur).toInt() else 0
    }

    private fun loadLatest() {
        val n = no(c)
        val f = File(dir(c), "broadcast_$n.wav")
        val j = File(dir(c), "broadcast_$n.json")
        if (j.isFile) try { showScript(DkaiKit.parseScript(JSONObject(j.readText()).optString("script"))) } catch (_: Exception) { showScript(emptyList()) }
        else showScript(emptyList())
        if (f.isFile) load(f, null) else showTime()
    }

    private fun newOne() {
        if (busy) return
        release()
        if (File(dir(c), "broadcast_${no(c)}.wav").isFile) Prefs.edit(c) { putString(PREF_NO, (no(c) + 1).toString()) }
        title.text = "Broadcast #${no(c)}"; wav = null; wave.peaks = null; wave.progress = 0f; wave.invalidate()
        showScript(emptyList()); showTime(); prompt.setText(""); prompt.requestFocus(); setState("READY", DkaiKit.EMERALD)
    }

    /** 14 नवंबर 2026 (मालिक): बनी आवाज़ / स्क्रिप्ट मिटाने का विकल्प (पूछकर) */
    private fun deleteCurrent() {
        if (busy) { s.toast("अभी बन रहा है…"); return }
        val n = no(c)
        val files = DkaiCast.files(dir(c), n)
        if (files.none { it.isFile }) { s.toast("मिटाने को कुछ नहीं"); return }
        android.app.AlertDialog.Builder(c).setTitle("Broadcast #$n मिटाएँ?").setMessage("इसकी स्क्रिप्ट और आवाज़ हमेशा के लिए मिट जाएगी।")
            .setPositiveButton("मिटाएँ") { _, _ ->
                release(); DkaiCast.delete(dir(c), n)
                wav = null; wave.peaks = null; wave.progress = 0f; wave.invalidate()
                showScript(emptyList()); showTime(); setState("READY", DkaiKit.EMERALD); s.toast("मिटा दिया")
            }.setNegativeButton("रद्द", null).show()
    }

    // ================================================================== भेजना / सेव
    private fun uri(f: File) = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)

    /**
     * शेयर/सेव वाली फ़ाइल चुनी रफ़्तार पर (1.0x = वही WAV). [m4a] = WhatsApp/Telegram के लिए AAC (.m4a) -
     * WhatsApp WAV को ऑडियो की तरह नहीं लेता. M4A न बने तो WAV. (IO thread)
     */
    private fun exported(f: File, m4a: Boolean): File {
        val tag = "x$speed"
        val out = File(File(dir(c), "share").apply { mkdirs() }, f.nameWithoutExtension + "_" + tag + if (m4a) ".m4a" else ".wav")
        if (out.isFile && out.lastModified() >= f.lastModified() && out.length() > 0) return out
        if (!m4a && speed == 100) return f
        val (pcm, rate) = DkaiKit.wavPcm(f.readBytes())
        val fast = if (speed == 100) pcm else DkaiKit.toBytes(DkaiKit.stretch(DkaiKit.toShorts(pcm), rate, speed / 100f))
        if (m4a) {
            try { BroadcastM4a.encode(fast, rate, out); if (out.length() > 0) return out } catch (e: Exception) { DKLog.d("Broadcast m4a: ${e.message?.take(80)}"); out.delete() }
            val w = File(out.parentFile, f.nameWithoutExtension + "_" + tag + ".wav"); w.writeBytes(GeminiTts.pcmToWav(fast, rate)); return w
        }
        out.writeBytes(GeminiTts.pcmToWav(fast, rate)); return out
    }

    private fun share() {
        val f = wav?.takeIf { it.isFile } ?: run { s.toast("पहले आवाज़ बनाएँ"); return }
        val apps = listOf("WhatsApp" to "com.whatsapp", "WhatsApp Business" to "com.whatsapp.w4b", "Telegram" to "org.telegram.messenger", "और ऐप…" to "")
        android.app.AlertDialog.Builder(c).setTitle("Share Audio (${DkaiCore.speedLabel(speed)})").setItems(apps.map { it.first }.toTypedArray()) { _, i ->
            s.toast("ऑडियो तैयार हो रहा है…")
            c.lifecycleScope.launch {
                val out = try { withContext(Dispatchers.IO) { exported(f, m4a = true) } } catch (e: Exception) { s.toast("शेयर फ़ाइल नहीं बनी: ${e.message?.take(80)}"); return@launch }
                val u = uri(out)
                val send = Intent(Intent.ACTION_SEND).setType(if (out.extension == "m4a") "audio/mp4" else "audio/wav").putExtra(Intent.EXTRA_STREAM, u)
                    .putExtra(Intent.EXTRA_TEXT, title.text.toString() + " - DKAI Broadcast Studio").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri(out.name, u)
                val pkg = apps[i].second
                val chooser = Intent.createChooser(send, "Share Audio").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    if (pkg.isNotEmpty()) c.startActivity(Intent(send).setPackage(pkg)) else c.startActivity(chooser)
                } catch (_: Exception) {
                    s.toast("${apps[i].first} नहीं मिला - दूसरा ऐप चुनें"); try { c.startActivity(chooser) } catch (_: Exception) {}
                }
            }
        }.show()
    }

    private fun download() {
        val f = wav?.takeIf { it.isFile } ?: run { s.toast("पहले आवाज़ बनाएँ"); return }
        val base = "DKAI_${title.text.toString().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')}_${DkaiCore.speedLabel(speed).replace('.', '_')}_${System.currentTimeMillis() / 1000}"
        Thread {
            val where = try {
                val src = exported(f, m4a = false)
                val name = "$base.wav"
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name); put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/DKAI")
                    }
                    val u = c.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("जगह नहीं मिली")
                    c.contentResolver.openOutputStream(u)?.use { o -> src.inputStream().use { it.copyTo(o) } }
                    "Downloads/DKAI/$name"
                } else {
                    val dd = File(c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "DKAI").apply { mkdirs() }
                    src.copyTo(File(dd, name), overwrite = true).absolutePath
                }
            } catch (e: Exception) { null.also { ui.post { s.toast("सेव नहीं हुआ: ${e.message?.take(80)}") } } }
            if (where != null) ui.post { s.toast("सेव हुआ: $where") }
        }.start()
    }

    /** चलती लहर: पूरी आवाज़ की चोटियाँ, बजा हुआ हिस्सा cyan→violet */
    class Wave(c: Context) : View(c) {
        var peaks: FloatArray? = null
        var progress = 0f
        var playing = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private var t = 0f
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f) return
            val pk = peaks ?: FloatArray(48) { 0.12f }
            val n = pk.size; val gap = w / n
            p.strokeWidth = (gap * 0.55f).coerceAtLeast(2f)
            p.shader = LinearGradient(0f, 0f, w, 0f, 0xFF00E5FF.toInt(), 0xFFA855F7.toInt(), Shader.TileMode.CLAMP)
            if (playing) t += 0.25f
            for (i in 0 until n) {
                val x = gap * i + gap / 2
                val bounce = if (playing && i / n.toFloat() <= progress + 0.02f && i / n.toFloat() >= progress - 0.08f) (0.15f * kotlin.math.sin(t + i)) else 0f
                val a = (pk[i] + bounce).coerceIn(0.08f, 1f) * h * 0.46f
                p.alpha = if (i / n.toFloat() <= progress) 255 else 80
                canvas.drawLine(x, h / 2 - a, x, h / 2 + a, p)
            }
        }
    }
}

/** PCM (16-bit mono) -> AAC .m4a (MediaCodec + MediaMuxer) - WhatsApp / Telegram पर आवाज़ की तरह चले */
object BroadcastM4a {
    fun encode(pcm: ByteArray, rate: Int, out: File) {
        val mime = android.media.MediaFormat.MIMETYPE_AUDIO_AAC
        val fmt = android.media.MediaFormat.createAudioFormat(mime, rate, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 64_000); setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = android.media.MediaCodec.createEncoderByType(mime)
        val mux = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        try {
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            var pos = 0; var inDone = false; var done = false; var guard = 0
            fun pts(bytes: Int) = bytes / 2L * 1_000_000L / rate
            while (!done && guard++ < 2_000_000) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!; buf.clear()
                        val n = minOf(buf.remaining(), pcm.size - pos) and 1.inv()
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts(pos), android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { buf.put(pcm, pos, n); codec.queueInputBuffer(i, 0, n, pts(pos), 0); pos += n }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            if (!done) throw IllegalStateException("AAC पूरा नहीं बना")
        } finally {
            try { codec.stop() } catch (_: Exception) {}; try { codec.release() } catch (_: Exception) {}
            try { if (started) mux.stop() } catch (_: Exception) {}; try { mux.release() } catch (_: Exception) {}
        }
    }
}
