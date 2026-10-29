package com.dk.assistant.voice.engine

import android.app.Activity
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.dk.assistant.R
import java.util.concurrent.Executors

/**
 * v3.68: सेटिंग्स -> "आवाज़" के अंदर "बोलने वाला इंजन (DK लाइव)".
 * तीन स्विच (एक समय में एक), हर इंजन की आवाज़ों की सूची + हर आवाज़ के आगे "▶ सुनो",
 * हर इंजन का अपना भावना/स्टाइल डिब्बा (v3.69), v3.80: ऊपर "अभी कौन बोल रहा" (इंजन/आवाज़/मॉडल + मॉडल बदलो)
 * और सब इंजनों के लिए एक ही गति/पिच ([VoiceTune])
 * और हर इंजन के आगे पिछली बार नापा समय. ▶ सुनो सहेजा नमूना-वाक्य बोलता है. ये बदलाव तुरंत सेव होते हैं (सेव बटन की ज़रूरत नहीं).
 */
class VoiceEngineCard private constructor(private val a: Activity, private val dp: Float) : VoiceUi {
    // Owner voice removed completely

    companion object {
        /** SettingsActivity की "आवाज़" वाली card के box में जोड़ो */
        fun build(a: Activity, box: LinearLayout, dp: Float) { VoiceEngineCard(a, dp).render(box) }
        private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-voice-preview").apply { isDaemon = true } }
    }

    private val switches = LinkedHashMap<String, SwitchCompat>()
    private val latency = HashMap<String, TextView>()
    private val radios = HashMap<String, MutableList<Pair<String, RadioButton>>>()
    private val status = HashMap<String, TextView>()
    private var muteSwitch = false

    private fun render(box: LinearLayout) {
        val engines = VoiceEngines.all
        // v3.69: ऊपर का शीर्षक अब card का नाम है; नमूना-वाक्य, "तीनों बारी-बारी सुनो" और "रोको" हटाए (बैकअप backup/v3.69-settings)
        hint(box, "जवाब Gemini Live लिखता है; बोलता यह इंजन है. एक समय में एक ही चालू. मेरे बोलना ख़त्म करने से पहली आवाज़ तक का " +
            "समय हर इंजन के नीचे दिखता है (पिछली बार नापा). डिफ़ॉल्ट: DK लाइव (सबसे तेज़). Gemini TTS / Edge में दिक़्क़त आए तो ऐप अपने-आप Gemini TTS पर लौटता है. ये बदलाव तुरंत सेव होते हैं.")
        nowBlock(box)            // v3.80: अभी कौन बोल रहा (इंजन / आवाज़ / मॉडल) + मॉडल बदलो + गति-पिच
        divider(box)
        val activeId = VoiceEngines.activeId(a)
        engines.forEachIndexed { i, e -> if (i > 0) divider(box); engineBlock(box, e, e.id == activeId) }
        if (engines.isEmpty()) hint(box, "कोई आवाज़ इंजन नहीं मिला - DK लाइव अपनी आवाज़ में बोलेगा")
    }

    private fun engineBlock(box: LinearLayout, e: VoiceEngine, on: Boolean) {
        val sw = SwitchCompat(a).apply {
            text = e.title; isChecked = on; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (14 * dp).toInt(), 0, (2 * dp).toInt())
            setOnCheckedChangeListener { _, checked -> if (!muteSwitch) onSwitch(e.id, checked) }
        }
        box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        switches[e.id] = sw
        small(box, e.note, 0x99FFFFFF.toInt())
        latency[e.id] = small(box, VoiceEngines.latencyLine(a, e.id), 0xFF8FD3FF.toInt())
        status[e.id] = small(box, "", 0xCCFFFFFF.toInt()).apply { visibility = View.GONE }
        e.notReady(a)?.let { small(box, "अभी नहीं चलेगा: $it", 0xFFFFC46B.toInt()) }
        // आवाज़ें: हर एक के आगे ▶ सुनो
        val list = mutableListOf<Pair<String, RadioButton>>()
        val cur = e.voice(a)
        for ((name, label) in e.voices()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val rb = RadioButton(a).apply {
                text = label; isChecked = name == cur; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { e.setVoice(a, name); radios[e.id]?.forEach { (n, b) -> b.isChecked = n == name }; refreshNow() }
            }
            row.addView(rb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(pill("▶ सुनो") { preview(e, name) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * dp).toInt()))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
            list.add(name to rb)
        }
        radios[e.id] = list
        e.settings(a, box, this)
    }

    private fun onSwitch(id: String, on: Boolean) {
        val cur = VoiceEngines.activeId(a)
        val next = VoiceEngines.toggle(cur, id, on, available = VoiceEngines.all.map { it.id }.toSet())
        if (next != cur) VoiceEngines.setActive(a, next)
        muteSwitch = true
        switches.forEach { (k, s) -> s.isChecked = k == next }
        muteSwitch = false
        val t = VoiceEngines.byId(next)?.title ?: next
        say(id, if (next == cur && !on) "कम से कम एक इंजन चालू रहता है - अभी: $t" else if (on) "अब बोलेगा: $t (चलती बातचीत में भी - अभी का जवाब पूरा होते ही)" else "")
        if (next != id) say(next, "अब बोलेगा: $t (चलती बातचीत में भी - अभी का जवाब पूरा होते ही)")
        refreshNow()
    }

    // ------------------------------------------------------------------ v3.80: अभी कौन बोल रहा + मॉडल + गति/पिच
    private var nowText: TextView? = null

    /** "बोल रहा: Gemini TTS • आवाज़ Achernar • मॉडल ..." - हर बदलाव पर और हर 2 सेकंड (Live चलते हुए मॉडल बदल सकता है) */
    private fun nowLine(): String {
        val e = VoiceEngines.active(a)
        val live = com.dk.assistant.Prefs.liveModel(a).ifBlank { "अभी नहीं जुड़ा" }
        val livePick = com.dk.assistant.LiveModelPick.get(a)
        return buildString {
            append("बोलने वाला: ").append(e?.title ?: "Gemini Live").append(" • आवाज़: ").append(e?.voice(a) ?: com.dk.assistant.Prefs.geminiVoice(a))
            if (e != null && e.split) append("\nजवाब लिखने वाला (DK लाइव): ").append(live)
            else append("\nDK लाइव मॉडल (यही बोलता है): ").append(live)
            if (livePick.isNotBlank() && livePick != live) append(" → चुना: ").append(livePick).append(" (DK चुप होते ही)")
            if (e?.id == VoiceEngines.GEMINI_TTS) {
                val used = GeminiTtsEngine.lastModel(a).ifBlank { "अभी नहीं बोला" }
                append("\nआवाज़ बनाने वाला मॉडल (Gemini TTS): ").append(used)
                GeminiTtsEngine.model(a).takeIf { it.isNotBlank() && it != used }?.let { append(" → चुना: ").append(it) }
            }
            append("\n").append(VoiceTune.label())
        }
    }

    private fun refreshNow() { nowText?.text = nowLine() }

    private fun nowBlock(box: LinearLayout) {
        VoiceTune.load(a)
        box.addView(TextView(a).apply {
            text = "अभी कौन बोल रहा है"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (2 * dp).toInt())
        })
        val tv = small(box, nowLine(), 0xFF8FD3FF.toInt()).apply { textSize = 12.5f }
        nowText = tv
        // Live चलते हुए मॉडल/आवाज़ बदल सकती है - स्क्रीन दिखे तब तक हर 2 सेकंड ताज़ा
        val tick = object : Runnable {
            override fun run() { if (!tv.isAttachedToWindow) return; refreshNow(); tv.postDelayed(this, 2_000) }
        }
        tv.post { tv.postDelayed(tick, 2_000) }

        // DK लाइव का मॉडल
        label(box, "DK लाइव का मॉडल (जवाब देने वाला)")
        radioList(box, listOf("" to "अपने-आप (सबसे तेज़ जो चले)") + com.dk.assistant.LiveModelPick.choices(a).map { it to it },
            com.dk.assistant.LiveModelPick.get(a)) { m -> com.dk.assistant.LiveModelPick.set(a, m); refreshNow() }
        hint(box, "बदलते ही लगता है - चलती बातचीत में DK का जवाब पूरा होते ही नया मॉडल जुड़ जाता है।")

        // Gemini TTS का मॉडल
        label(box, "Gemini TTS का मॉडल (आवाज़ बनाने वाला)")
        radioList(box, listOf("" to "अपने-आप (Worker जो चले)") + GeminiTtsEngine.MODELS.map { it to it }, GeminiTtsEngine.model(a)) { m ->
            GeminiTtsEngine.setModel(a, m); refreshNow()
        }
        hint(box, "Gemini TTS चालू हो तो अगले वाक्य से ही इसी मॉडल की आवाज़।")

        // गति / पिच - जो भी इंजन चालू हो, उसी पर; बातचीत के बीच भी तुरंत
        label(box, "गति और पिच (जो आवाज़ चालू है, उसी पर - तुरंत)")
        slider(box, "गति", VoiceTune.MIN_SPEED, VoiceTune.MAX_SPEED, VoiceTune.speedPct, { v -> "$v%" }) { v -> VoiceTune.setSpeed(a, v); refreshNow() }
        slider(box, "पिच (आवाज़ का ऊँचा-नीचापन)", VoiceTune.MIN_PITCH, VoiceTune.MAX_PITCH, VoiceTune.pitchPct, { v -> "$v%" }) { v -> VoiceTune.setPitch(a, v); refreshNow() }
        hint(box, "100% = सामान्य। कोमल आवाज़ के लिए गति 90-95% और पिच 100-104% अच्छी लगती है। DK लाइव, Gemini TTS और Edge - तीनों पर एक ही।")
    }

    private fun radioList(box: LinearLayout, items: List<Pair<String, String>>, cur: String, onPick: (String) -> Unit) {
        val group = mutableListOf<Pair<String, RadioButton>>()
        val selected = if (items.any { it.first == cur }) cur else ""
        for ((value, text) in items) {
            val rb = RadioButton(a).apply {
                this.text = text; isChecked = value == selected; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { group.forEach { (v, b) -> b.isChecked = v == value }; onPick(value) }
            }
            box.addView(rb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            group.add(value to rb)
        }
    }

    // ------------------------------------------------------------------ सुनना / तुलना
    private fun preview(e: VoiceEngine, voice: String) {
        stopAll()
        val text = VoiceEngines.sample(a)
        say(e.id, "बज रहा है: $voice …")
        bg.execute {
            val r = runPreview(e, voice, text)
            a.runOnUiThread { say(e.id, r); refreshLatency() }
        }
    }

    private fun runPreview(e: VoiceEngine, voice: String, text: String): String {
        e.notReady(a)?.let { return "${e.title}: $it" }
        return try {
            val ms = e.preview(a, text, voice)
            if (ms < 0) "${e.title}: कोई आवाज़ नहीं आई"
            else { VoiceEngines.recordPreview(a, e.id, ms); VoiceEngines.clearFailed(e.id); "${e.title} ($voice): पहली आवाज़ ${VoiceEngines.secs(ms)} में" }
        } catch (ex: Exception) {
            "${e.title}: दिक़्क़त - ${(ex.message ?: ex.javaClass.simpleName).take(100)} (बातचीत में ऐप Gemini TTS पर लौटेगा)"
        }
    }

    private fun stopAll() {
        PreviewPlayer.stop()
        VoiceEngines.all.forEach { try { it.stop() } catch (_: Exception) {} }
    }

    private fun refreshLatency() { latency.forEach { (id, tv) -> tv.text = VoiceEngines.latencyLine(a, id) } }
    private fun say(id: String, t: String) { status[id]?.apply { text = t; visibility = if (t.isBlank()) View.GONE else View.VISIBLE } }

    // ------------------------------------------------------------------ VoiceUi (इंजन अपने controls इसी से)
    override fun label(box: LinearLayout, t: String) {
        box.addView(TextView(a).apply { text = t; textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt()) })
    }

    override fun hint(box: LinearLayout, t: String) {
        box.addView(TextView(a).apply { text = t; textSize = 12f; setTextColor(0x80FFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0) })
    }

    override fun field(box: LinearLayout, title: String, value: String, hint: String, secret: Boolean, multiLine: Boolean, onSave: (String) -> Unit) {
        label(box, title)
        val e = EditText(a).apply {
            setText(value); this.hint = hint
            setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 14f
            val pd = (10 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (multiLine) { minLines = 2; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { onSave(s?.toString().orEmpty()) }
            })
        }
        box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun slider(box: LinearLayout, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val lbl = TextView(a).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0) }
        fun show(v: Int) { lbl.text = "$title: ${fmt(v)}" }
        show(value)
        box.addView(lbl)
        val sb = SeekBar(a).apply {
            this.max = max - min; progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { show(p + min); if (fromUser) onChange(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ छोटे हिस्से
    private fun small(box: LinearLayout, t: String, color: Int): TextView {
        val v = TextView(a).apply { text = t; textSize = 11.5f; setTextColor(color); setPadding((4 * dp).toInt(), (2 * dp).toInt(), 0, 0) }
        box.addView(v); return v
    }

    private fun pill(t: String, onClick: (View) -> Unit) = Button(a).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_pill); minHeight = 0; minimumHeight = 0
        val ph = (12 * dp).toInt(); setPadding(ph, (4 * dp).toInt(), ph, (4 * dp).toInt())
        setOnClickListener(onClick)
    }

    private fun divider(box: LinearLayout) {
        box.addView(View(a).apply { setBackgroundColor(0x22FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp.toInt())).apply { topMargin = (14 * dp).toInt(); bottomMargin = (4 * dp).toInt() })
    }
}
