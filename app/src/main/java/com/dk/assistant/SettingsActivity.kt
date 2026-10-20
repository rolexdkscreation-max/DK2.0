package com.dk.assistant

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Settings panel (liquid glass cards):
 *  Profile (aapka naam, assistant ka naam, personality, wake words)
 *  AI Brain (kaunsi company, har company ki API key + model + test)
 *  Awaaz (speed, pitch, test), Options, Permissions
 *  Neeche hamesha dikhne wala "सेव करें" button + auto-save.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var txtSaved: TextView
    private var speaker: Speaker? = null

    // form fields
    private lateinit var edUser: EditText
    private lateinit var edName: EditText
    private lateinit var edPersona: EditText
    private lateinit var edWake: EditText
    private lateinit var rgProvider: RadioGroup
    private lateinit var swFallback: SwitchCompat
    private lateinit var swWake: SwitchCompat
    private lateinit var swShot: SwitchCompat
    private lateinit var swCalls: SwitchCompat
    private lateinit var swWeb: SwitchCompat
    private lateinit var sbRate: SeekBar
    private lateinit var sbPitch: SeekBar
    private val keyFields = mutableMapOf<String, EditText>()
    private val modelFields = mutableMapOf<String, EditText>()
    private val baseFields = mutableMapOf<String, EditText>()
    private lateinit var rgGender: RadioGroup
    private lateinit var rgEngine: RadioGroup
    private lateinit var rgListen: RadioGroup
    private lateinit var rgStyle: RadioGroup
    private lateinit var txtVoice: TextView
    private val fetchedFor = mutableMapOf<String, String>()   // provider -> key jiske models la chuke

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_settings)
        container = findViewById(R.id.container)
        txtSaved = findViewById(R.id.txtSaved)
        findViewById<OrbView>(R.id.miniOrb).mini = true
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { save(false); finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(true) }
        build()
    }

    // ------------------------------------------------------------ UI build
    private lateinit var swAutoUpd: SwitchCompat
    private var pendingRelease: Updater.Release? = null

    private fun build() {
        // ---- Update
        card("अपडेट", "अभी का version: ${Updater.currentName(this)} (build ${Updater.currentCode(this)})") { box ->
            val status = TextView(this).apply {
                text = "अपडेट चेक करने के लिए बटन दबाएँ"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt())
            }
            box.addView(status)
            val notes = TextView(this).apply {
                textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); visibility = View.GONE
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            box.addView(notes)
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; visibility = View.GONE
            }
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * dp).toInt()))
            lateinit var btnInstall: Button
            val btnCheck = button("अपडेट चेक करें") {
                status.text = "चेक हो रहा है…"
                lifecycleScope.launch {
                    try {
                        val rel = Updater.check(this@SettingsActivity)
                        pendingRelease = rel
                        if (rel == null) {
                            status.text = "✓ आपके पास सबसे नया version है"
                            notes.visibility = View.GONE; btnInstall.visibility = View.GONE
                        } else {
                            status.text = "नया अपडेट आया है: ${rel.name}  (${rel.size / 1024 / 1024} MB)"
                            notes.text = rel.notes.take(600).ifBlank { "सुधार और नए फ़ीचर" }
                            notes.visibility = View.VISIBLE; btnInstall.visibility = View.VISIBLE
                        }
                    } catch (e: Exception) {
                        status.text = "चेक नहीं हो पाया: ${e.message}. इंटरनेट देखें।"
                    }
                }
            }
            btnInstall = button("डाउनलोड करके अपडेट करें") {
                val rel = pendingRelease ?: return@button
                if (!Updater.canInstall(this)) {
                    toast("DK को \"ऐप इंस्टॉल करने\" की इजाज़त दें, फिर वापस आकर दोबारा दबाएँ")
                    Updater.askInstallPermission(this); return@button
                }
                bar.visibility = View.VISIBLE; bar.progress = 0
                status.text = "डाउनलोड हो रहा है…"
                lifecycleScope.launch {
                    try {
                        Updater.downloadAndInstall(this@SettingsActivity, rel) { p ->
                            bar.progress = p; status.text = "डाउनलोड: $p%"
                        }
                        status.text = "इंस्टॉल स्क्रीन पर \"Update / Install\" दबाएँ"
                    } catch (e: Exception) {
                        status.text = "डाउनलोड नहीं हुआ: ${e.message}"
                        bar.visibility = View.GONE
                    }
                }
            }.apply { visibility = View.GONE }
            row(box, btnCheck)
            row(box, btnInstall)
            swAutoUpd = switch(box, "ऐप खुलते ही अपडेट चेक करो", Prefs.autoUpdate(this))
            // Settings khulte hi ek baar chupchap check
            btnCheck.performClick()
        }

        // ---- Profile
        card("प्रोफ़ाइल", "आपका और असिस्टेंट का नाम") {
            edUser = field(it, "आपका नाम", Prefs.userName(this), "जैसे: दीपक")
            edName = field(it, "असिस्टेंट का नाम", Prefs.assistantName(this), "DK")
            edPersona = field(it, "पर्सनैलिटी / खास निर्देश", Prefs.personality(this),
                "जैसे: दोस्त की तरह मज़ेदार बात करो, जवाब छोटे रखो", multiLine = true)
            edWake = field(it, "जगाने वाले शब्द (कॉमा से अलग)", Prefs.wakeWordsRaw(this), "डीके, dk")
        }

        // ---- AI brain
        card("AI दिमाग", "कौन सी AI कंपनी इस्तेमाल करनी है") { box ->
            rgProvider = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            Providers.all.forEachIndexed { i, p ->
                rgProvider.addView(RadioButton(this).apply {
                    id = 1000 + i
                    text = p.name + if (p.free) "   • फ्री" else "   • पेड"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    isChecked = Prefs.activeProvider(this@SettingsActivity) == p.id
                    setPadding((6 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
                })
            }
            box.addView(rgProvider)
            swFallback = switch(box, "मुख्य AI फेल हो तो दूसरी AI अपने-आप इस्तेमाल करो", Prefs.fallback(this))
        }

        Providers.all.forEach { p -> providerCard(p) }

        // ---- Voice
        card("आवाज़", "महिला या पुरुष — दोनों फ्री") { box ->
            label(box, "किसकी आवाज़ में बोले?")
            rgGender = radios(box, listOf("female" to "महिला (Female)", "male" to "पुरुष (Male)"), Prefs.voiceGender(this), 2000)
            label(box, "आवाज़ का प्रकार")
            rgEngine = radios(box, listOf(
                "google" to "फ़ोन की Google आवाज़ — फ्री, कोई लिमिट नहीं, तेज़",
                "gemini" to "Gemini नेचुरल आवाज़ — 30 इंसानी आवाज़ें (Gemini key से, रोज़ की लिमिट, थोड़ी धीमी)"),
                Prefs.voiceEngine(this), 2100)
            txtVoice = TextView(this).apply {
                textSize = 13f; setTextColor(0xB3FFFFFF.toInt())
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
            }
            box.addView(txtVoice)
            updateVoiceText()
            rgGender.setOnCheckedChangeListener { _, _ ->
                // gender badla to purani chuni hui awaaz hatao (naye gender ki sabse achhi awaaz)
                Prefs.edit(this) { putString("google_voice", ""); putString("gemini_voice", "") }
                save(false); updateVoiceText()
            }
            rgEngine.setOnCheckedChangeListener { _, _ -> save(false); updateVoiceText() }
            label(box, "बोलने का अंदाज़ (Gemini आवाज़)")
            rgStyle = radios(box, VoiceCatalog.STYLES, Prefs.voiceStyle(this), 2300)
            rgStyle.setOnCheckedChangeListener { _, _ -> save(false) }
            row(box,
                button("आवाज़ चुनें") { chooseVoice() },
                button("आवाज़ सुनें") { testVoice() })
            label(box, "बोलने की स्पीड")
            sbRate = seek(box, Prefs.ttsRate(this))
            label(box, "आवाज़ की पिच (ऊँची/नीची) — सिर्फ़ Google आवाज़")
            sbPitch = seek(box, Prefs.ttsPitch(this))
            hint(box, "Google आवाज़ ज़्यादा नेचुरल चाहिए तो: Play Store से \"Speech Services by Google\" अपडेट करें, फिर \"आवाज़ चुनें\" में \"ऑनलाइन\" वाली आवाज़ लें। Gemini आवाज़ की लिमिट खत्म हो तो DK अपने-आप Google आवाज़ में बोलेगा।")
            row(box, button("फ़ोन की TTS सेटिंग") { if (!Setup.openTts(this)) toast("TTS सेटिंग नहीं खुली") })
        }

        // ---- Listening
        card("सुनना (माइक)", "लगातार सुनना — बिना बीप की आवाज़") { box ->
            rgListen = radios(box, listOf(
                "auto" to "बिना बीप — DK खुद सुनेगा (Groq या Gemini key चाहिए)",
                "google" to "Google माइक — बिना key, पर हर बार बीप आ सकती है"),
                Prefs.listenEngine(this), 2200)
            hint(box, "बिना बीप वाले मोड में सबसे तेज़ और सही हिंदी पहचान के लिए Groq की फ्री key डालें (Whisper)। Groq key न हो तो Gemini key से काम चलेगा।")
        }

        // ---- Options
        card("विकल्प", null) { box ->
            swWake = switch(box, "हमेशा सुनो (जगाने वाला शब्द बोलकर कमांड)", Prefs.wakeMode(this))
            swShot = switch(box, "स्क्रीनशॉट भी AI को भेजो (ज़्यादा समझदार, ज़्यादा डेटा)", Prefs.screenshot(this))
            swCalls = switch(box, "कॉल आने पर बताओ किसका कॉल है (\"उठा लो\" / \"काट दो\" बोलें)", Prefs.announceCalls(this))
            swWeb = switch(box, "ज़रूरी सवालों का जवाब इंटरनेट (Google Search) से पता करके दो — Gemini key से", Prefs.webAnswers(this))
            row(box, button("कॉल/कॉन्टैक्ट परमिशन दें") {
                androidx.core.app.ActivityCompat.requestPermissions(this, CallManager.PERMS, 11)
            })
            hint(box, "कॉल के दौरान Android दूसरे ऐप्स को माइक नहीं देता — कॉल काटने के लिए स्क्रीन पर तैरता DK बुलबुला एक बार दबाएँ।")
        }

        // ---- Crash report
        val crash = DKApp.lastCrash(this)
        if (crash.isNotBlank()) card("गड़बड़ी रिपोर्ट", "पिछली बार DK किस वजह से बंद हुआ") { box ->
            box.addView(TextView(this).apply {
                text = crash.take(1200); textSize = 11f; setTextColor(0xB3FFFFFF.toInt()); setTextIsSelectable(true)
            })
            row(box,
                button("कॉपी करें") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("DK crash", crash)); toast("कॉपी हो गया — मुझे भेज दें")
                },
                button("हटाएँ") { DKApp.clearCrash(this); toast("हटा दिया") })
        }

        // ---- Permissions
        card("परमिशन", "फ़ोन कंट्रोल के लिए ज़रूरी") { box ->
            row(box,
                button("Accessibility ON") { Setup.openAccessibility(this) },
                button("App info") { Setup.openAppInfo(this) })
            hint(box, "Android 13+: अगर Accessibility में DK धुंधला दिखे तो App info → ऊपर ⋮ → \"Allow restricted settings\" करें। Xiaomi/Vivo/Oppo पर Battery → No restrictions और Autostart ON करें।")
        }
        space(container, 8)
    }

    private fun providerCard(p: Provider) {
        card(p.name, p.note) { box ->
            if (p.isCustom) {
                baseFields[p.id] = field(box, "Base URL", Prefs.baseUrl(this, p.id), "https://.../v1")
            }
            val key = field(box, "API Key", Prefs.key(this, p.id), "यहाँ key पेस्ट करें", secret = true)
            keyFields[p.id] = key
            val model = field(box, "मॉडल", Prefs.model(this, p.id), p.defaultModel.ifBlank { "model-name" })
            modelFields[p.id] = model
            val result = TextView(this).apply {
                setTextColor(0xB3FFFFFF.toInt()); textSize = 13f
                visibility = View.GONE
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            val mInfo = TextView(this).apply {
                setTextColor(0xB3FFFFFF.toInt()); textSize = 13f
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
                val cached = Prefs.models(this@SettingsActivity, p.id)
                text = if (cached.isNotEmpty()) "${cached.size} मॉडल उपलब्ध — \"मॉडल चुनें\" दबाएँ" else "Key डालते ही इस कंपनी के सारे मॉडल यहाँ आ जाएँगे"
            }
            box.addView(mInfo)
            fun loadModels(openPicker: Boolean) {
                val k = key.text.toString().trim()
                if (k.length < 12) { mInfo.text = "पहले सही API key डालें"; return }
                val base = baseFields[p.id]?.text?.toString()?.trim()?.ifBlank { null } ?: Prefs.baseUrl(this, p.id)
                if (base.isBlank() && !p.isGemini) { mInfo.text = "पहले Base URL डालें"; return }
                mInfo.text = "मॉडल लोड हो रहे हैं…"
                lifecycleScope.launch {
                    try {
                        val list = ModelLister.fetch(p, k, base)
                        fetchedFor[p.id] = k
                        Prefs.edit(this@SettingsActivity) { putString("models_${p.id}", list.joinToString("\n")) }
                        mInfo.text = "✓ ${list.size} मॉडल मिले — \"मॉडल चुनें\" दबाएँ"
                        if (openPicker && list.isNotEmpty()) pickModel(p, model, list)
                    } catch (e: Exception) {
                        mInfo.text = "मॉडल नहीं आए: " + BrainManager(this@SettingsActivity).explain(e).take(160)
                    }
                }
            }
            // key paste/likhte hi (1.2 sec ruk kar) models apne-aap
            val h = android.os.Handler(mainLooper)
            var pendingFetch: Runnable? = null
            key.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val k = s?.toString()?.trim() ?: ""
                    pendingFetch?.let { h.removeCallbacks(it) }
                    if (k.length >= 20 && fetchedFor[p.id] != k) {
                        pendingFetch = Runnable { save(false); loadModels(true) }.also { h.postDelayed(it, 1200) }
                    }
                }
            })
            row(box,
                button("मॉडल चुनें") {
                    val cached = Prefs.models(this, p.id)
                    if (cached.isNotEmpty() && fetchedFor[p.id] == key.text.toString().trim()) pickModel(p, model, cached)
                    else loadModels(true)
                },
                button("लिस्ट रीफ़्रेश") { loadModels(false) })
            val buttons = mutableListOf<View>()
            buttons.add(button("दिखाओ") { v ->
                val show = key.inputType != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                key.inputType = if (show) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                key.setSelection(key.text.length)
                (v as Button).text = if (show) "छुपाओ" else "दिखाओ"
            })
            buttons.add(button("टेस्ट करें") {
                save(false)
                result.visibility = View.VISIBLE
                result.text = "टेस्ट हो रहा है…"
                lifecycleScope.launch { result.text = BrainManager(this@SettingsActivity).test(p) }
            })
            if (p.keyUrl.isNotBlank()) buttons.add(button("Key लें") { Setup.openUrl(this, p.keyUrl) })
            row(box, *buttons.toTypedArray())
            box.addView(result)
        }
    }

    private fun pickModel(p: Provider, target: EditText, list: List<String>) {
        val current = target.text.toString().trim()
        val items = list.toTypedArray()
        val sel = items.indexOf(current)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${p.name} — मॉडल चुनें (${items.size})")
            .setSingleChoiceItems(items, sel) { d, which ->
                target.setText(items[which]); save(false)
                toast("मॉडल: ${items[which]}"); d.dismiss()
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    // ------------------------------------------------------------ save
    private fun save(showToast: Boolean) {
        if (!::rgProvider.isInitialized) return
        val checked = rgProvider.checkedRadioButtonId
        val active = Providers.all.getOrNull(checked - 1000)?.id ?: Prefs.activeProvider(this)
        Prefs.edit(this) {
            putString("user_name", edUser.text.toString().trim())
            putString("assistant_name", edName.text.toString().trim().ifBlank { "DK" })
            putString("personality", edPersona.text.toString().trim())
            putString("wake_words", edWake.text.toString().trim())
            putString("active_provider", active)
            putBoolean("fallback", swFallback.isChecked)
            putBoolean("wake_mode", swWake.isChecked)
            putBoolean("screenshot", swShot.isChecked)
            putBoolean("announce_calls", swCalls.isChecked)
            putBoolean("web_answers", swWeb.isChecked)
            putBoolean("auto_update", swAutoUpd.isChecked)
            putFloat("tts_rate", seekValue(sbRate))
            putString("voice_gender", radioValue(rgGender, listOf("female", "male"), 2000))
            putString("voice_engine", radioValue(rgEngine, listOf("google", "gemini"), 2100))
            putString("voice_style", radioValue(rgStyle, VoiceCatalog.STYLES.map { it.first }, 2300))
            putString("listen_engine", radioValue(rgListen, listOf("auto", "google"), 2200))
            putFloat("tts_pitch", seekValue(sbPitch))
            keyFields.forEach { (id, e) -> putString("key_$id", e.text.toString().trim()) }
            modelFields.forEach { (id, e) -> putString("model_$id", e.text.toString().trim()) }
            baseFields.forEach { (id, e) -> putString("base_$id", e.text.toString().trim()) }
        }
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        txtSaved.text = "✓ सेव हो गया"
        if (showToast) {
            val active2 = Providers.byId(active)
            val warn = if (Prefs.key(this, active).isBlank()) " (पर ${active2?.name} की key खाली है)" else ""
            toast("सेटिंग्स सेव हो गईं$warn")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DKAccessibilityService.instance?.calls?.start()
        if (requestCode == 11) toast(if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) "✓ कॉल परमिशन मिल गई"
            else "कुछ परमिशन नहीं मिलीं — App info → Permissions से दें (Android 13+: पहले ⋮ → Allow restricted settings)")
    }

    override fun onPause() { save(false); super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    private fun radios(box: LinearLayout, items: List<Pair<String, String>>, current: String, base: Int): RadioGroup {
        val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        items.forEachIndexed { i, (v, t) ->
            g.addView(RadioButton(this).apply {
                id = base + i; text = t; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                setPadding((6 * dp).toInt(), (5 * dp).toInt(), 0, (5 * dp).toInt())
            })
            if (v == current) g.check(base + i)
        }
        if (g.checkedRadioButtonId == -1) g.check(base)
        box.addView(g)
        return g
    }

    private fun radioValue(g: RadioGroup, values: List<String>, base: Int) =
        values.getOrElse(g.checkedRadioButtonId - base) { values.first() }

    private fun sp(): Speaker = speaker ?: Speaker(this).also { speaker = it }

    private fun updateVoiceText() {
        if (!::txtVoice.isInitialized) return
        val engine = radioValue(rgEngine, listOf("google", "gemini"), 2100)
        if (engine == "gemini") {
            txtVoice.text = "चुनी हुई आवाज़: ${Prefs.geminiVoice(this)}" +
                if (Prefs.key(this, "gemini").isBlank()) "  (Gemini key नहीं डाली — तब तक Google आवाज़)" else ""
        } else {
            lifecycleScope.launch {
                val voices = sp().hindiVoices()
                val name = Prefs.googleVoice(this@SettingsActivity)
                val v = voices.firstOrNull { it.name == name } ?: VoiceCatalog.pickGoogle(voices, Prefs.voiceGender(this@SettingsActivity))
                txtVoice.text = if (v == null) "फ़ोन में हिंदी आवाज़ नहीं मिली — \"फ़ोन की TTS सेटिंग\" से हिंदी डाउनलोड करें"
                else "चुनी हुई आवाज़: " + VoiceCatalog.label(v, voices.indexOf(v)) + "  •  कुल ${voices.size} हिंदी आवाज़ें"
            }
        }
    }

    /** Saari awaazon ki list: tap = suno + chuno. */
    private fun chooseVoice() {
        save(false)
        val gender = Prefs.voiceGender(this)
        val sample = "नमस्ते, मैं ${Prefs.assistantName(this)} हूँ। क्या मैं आपकी मदद करूँ?"
        if (Prefs.voiceEngine(this) == "gemini") {
            val f = VoiceCatalog.GEMINI_FEMALE.map { it to "महिला • $it — ${VoiceCatalog.GEMINI_INFO[it]}" }
            val m = VoiceCatalog.GEMINI_MALE.map { it to "पुरुष • $it — ${VoiceCatalog.GEMINI_INFO[it]}" }
            val all = if (gender == "male") m + f else f + m
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Gemini आवाज़ चुनें (टैप = सुनें)")
                .setItems(all.map { it.second }.toTypedArray()) { _, i ->
                    val name = all[i].first
                    val g = if (name in VoiceCatalog.GEMINI_MALE) "male" else "female"
                    Prefs.edit(this) { putString("gemini_voice", name); putString("voice_gender", g) }
                    rgGender.check(if (g == "male") 2001 else 2000)
                    Prefs.edit(this) { putString("gemini_voice", name) }
                    updateVoiceText()
                    toast("सुनाई दे रही है: $name …")
                    lifecycleScope.launch { sp().previewGemini(name, sample)?.let { toast(it) } }
                }
                .setNegativeButton("बंद करें", null).show()
        } else {
            lifecycleScope.launch {
                val voices = sp().hindiVoices()
                if (voices.isEmpty()) { toast("हिंदी आवाज़ नहीं मिली — TTS सेटिंग से हिंदी डाउनलोड करें"); Setup.openTts(this@SettingsActivity); return@launch }
                val labels = voices.mapIndexed { i, v -> VoiceCatalog.label(v, i) }.toTypedArray()
                android.app.AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Google आवाज़ चुनें (टैप = सुनें)")
                    .setItems(labels) { _, i ->
                        val v = voices[i]
                        val g = VoiceCatalog.googleGender(v)
                        Prefs.edit(this@SettingsActivity) {
                            putString("google_voice", v.name)
                            if (g != null) putString("voice_gender", g)
                        }
                        if (g != null) { rgGender.setOnCheckedChangeListener(null); rgGender.check(if (g == "male") 2001 else 2000)
                            rgGender.setOnCheckedChangeListener { _, _ -> Prefs.edit(this@SettingsActivity) { putString("google_voice", ""); putString("gemini_voice", "") }; save(false); updateVoiceText() } }
                        updateVoiceText()
                        lifecycleScope.launch { sp().speakGoogle(sample, v.name) }
                    }
                    .setNegativeButton("बंद करें", null).show()
            }
        }
    }

    private fun testVoice() {
        save(false)
        val sp = sp()
        val user = Prefs.userName(this)
        lifecycleScope.launch {
            sp.speak("नमस्ते${if (user.isNotBlank()) " $user" else ""}, मैं ${Prefs.assistantName(this@SettingsActivity)} हूँ। मेरी आवाज़ कैसी लगी?")
        }
    }

    // ------------------------------------------------------------ small UI helpers
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { txtSaved.text = "बदलाव सेव नहीं हुए — सेव दबाएँ" }
    }

    private fun card(title: String, sub: String?, body: (LinearLayout) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_card)
            val pd = (18 * dp).toInt(); setPadding(pd, pd, pd, pd)
        }
        box.addView(TextView(this).apply {
            text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        if (sub != null) box.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(0x99FFFFFF.toInt())
            setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
        })
        body(box)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (12 * dp).toInt()
        container.addView(box, lp)
    }

    private fun label(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 13f; setTextColor(0xB3FFFFFF.toInt())
        setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
    })

    private fun hint(box: LinearLayout, t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0x80FFFFFF.toInt())
        setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0)
    })

    private fun field(box: LinearLayout, title: String, value: String, hint: String,
                      secret: Boolean = false, multiLine: Boolean = false): EditText {
        label(box, title)
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            setBackgroundResource(R.drawable.glass_input)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt())
            textSize = 15f
            val pd = (12 * dp).toInt(); setPadding(pd + 4, pd, pd, pd)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiLine) { minLines = 2; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
            addTextChangedListener(watcher)
        }
        box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun switch(box: LinearLayout, t: String, on: Boolean): SwitchCompat {
        val s = SwitchCompat(this).apply {
            text = t; isChecked = on; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _, _ -> txtSaved.text = "बदलाव सेव नहीं हुए — सेव दबाएँ" }
        }
        box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return s
    }

    /** 0.5x .. 2.0x */
    private fun seek(box: LinearLayout, value: Float): SeekBar {
        val s = SeekBar(this).apply { max = 150; progress = ((value - 0.5f) * 100).toInt().coerceIn(0, 150) }
        box.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return s
    }
    private fun seekValue(s: SeekBar) = 0.5f + s.progress / 100f

    private fun button(t: String, onClick: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_pill)
        minHeight = 0; minimumHeight = 0
        val ph = (14 * dp).toInt(); setPadding(ph, (8 * dp).toInt(), ph, (8 * dp).toInt())
        setOnClickListener(onClick)
    }

    private fun row(box: LinearLayout, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * dp).toInt(), 0, 0) }
        views.forEach { v ->
            val lp = LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f)
            lp.marginEnd = (8 * dp).toInt()
            r.addView(v, lp)
        }
        box.addView(r)
    }

    private fun space(box: LinearLayout, h: Int) = box.addView(View(this), LinearLayout.LayoutParams(1, (h * dp).toInt()))

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
