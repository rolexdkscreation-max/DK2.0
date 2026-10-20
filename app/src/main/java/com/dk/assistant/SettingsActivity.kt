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

    private val section: String? get() = intent.getStringExtra("section")

    private fun build() {
        val title = findViewById<TextView>(R.id.txtTitle)
        val bar = findViewById<View>(R.id.saveBar)
        when (section) {
            null -> { title.text = "सेटिंग्स"; bar.visibility = View.GONE; menu() }
            "profile" -> { title.text = "प्रोफ़ाइल"; secProfile() }
            "brain" -> { title.text = "AI दिमाग"; secBrain() }
            "voice" -> { title.text = "आवाज़"; secVoice() }
            "orb" -> { title.text = "ओर्ब और चेहरा"; bar.visibility = View.GONE; secOrb() }
            "access" -> { title.text = "Accessibility"; secAccess() }
            "perms" -> { title.text = "परमिशन"; secPerms() }
            "update" -> { title.text = "अपडेट"; secUpdate() }
            "debug" -> { title.text = "डिबग लॉग"; bar.visibility = View.GONE; secDebug() }
        }
        space(container, 8)
    }

    // ------------------------------------------------------------ mukhya menu
    private fun menu() {
        val name = Prefs.assistantName(this)
        val items = listOf(
            Triple("profile", "प्रोफ़ाइल", "आपका नाम • असिस्टेंट का नाम ($name) • बात करने का तरीका"),
            Triple("brain", "AI दिमाग", "AI कंपनी चुनें • API keys • मॉडल • इंटरनेट से जवाब"),
            Triple("voice", "आवाज़", "महिला / पुरुष • आवाज़ चुनें • स्पीड • सुनने का तरीका"),
            Triple("orb", "ओर्ब और चेहरा", "पृथ्वी, गैलेक्सी, एटम, DNA, छल्ला, पुरुष/महिला चेहरा"),
            Triple("access", "Accessibility", (if (Setup.accessibilityOn(this)) "✓ चालू" else "✗ बंद") + " • फ़ोन कंट्रोल • हमेशा सुनना • कॉल • बैटरी"),
            Triple("perms", "परमिशन", "माइक • स्क्रीन देखना • नोटिफ़िकेशन • कॉल • ऐप इंस्टॉल"),
            Triple("update", "अपडेट", "अभी: ${Updater.displayVersion(this)} • नया version चेक करें"),
            Triple("debug", "डिबग लॉग", "माइक, कनेक्शन, देरी और गलतियों का पूरा हाल")
        )
        items.forEach { (id, t, sub) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_card)
                val pd = (18 * dp).toInt(); setPadding(pd, pd, pd, pd)
                isClickable = true
                setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra("section", id)) }
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
            txt.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0) })
            box.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(0x99FFFFFF.toInt()) })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (12 * dp).toInt()
            container.addView(box, lp)
        }
    }

    /** App ka "console": har galti/chetavni, mic ke aankde, Live connection, deri (ms) */
    private fun secDebug() {
        card("Gemini Live हाल", "माइक से भेजे टुकड़े, गिरे टुकड़े, दोबारा जुड़ना, देरी") { box ->
            val st = TextView(this).apply { textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            box.addView(st)
            fun upd() { st.text = GeminiLive.stats + "\nमाइक परमिशन: " + (if (Setup.hasMic(this)) "मिली है" else "नहीं मिली") +
                "\nसुनने का तरीका: " + Prefs.listenEngine(this) + " • Gemini key: " + (if (GeminiLive.available(this)) "है" else "नहीं") }
            upd()
            row(box, button("ताज़ा करें") { upd() })
        }
        card("लॉग", "सबसे नई लाइन नीचे • 300 लाइन तक") { box ->
            val tv = TextView(this).apply {
                textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true)
                text = DKLog.all().ifBlank { "अभी कोई लॉग नहीं" }
            }
            box.addView(tv)
            DKLog.listener = { all -> tv.text = all }
            row(box,
                button("कॉपी करें") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("DK log", GeminiLive.stats + "\n\n" + DKLog.all()))
                    toast("लॉग कॉपी हो गया")
                },
                button("साफ़ करें") { DKLog.clear() })
        }
    }

    private fun secUpdate() {
        card("अपडेट", "अभी का version: ${Updater.displayVersion(this)}") { box ->
            val status = TextView(this).apply {
                text = "अपडेट चेक करने के लिए बटन दबाएँ"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (2 * dp).toInt())
            }
            box.addView(status)
            val info = TextView(this).apply {
                textSize = 13f; setTextColor(0xFF7FDBFF.toInt()); visibility = View.GONE
                setPadding((4 * dp).toInt(), 0, 0, (4 * dp).toInt())
            }
            box.addView(info)
            val notes = TextView(this).apply {
                textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); visibility = View.GONE
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            box.addView(notes)
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; visibility = View.GONE
            }
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * dp).toInt()))
            lateinit var btnAct: Button
            var watch: kotlinx.coroutines.Job? = null

            /** Button aur status: डाउनलोड करें -> डाउनलोड हो रहा है x% -> इंस्टॉल करें */
            fun show(rel: Updater.Release?) {
                if (rel == null) {
                    status.text = "✓ आपके पास सबसे नया version है"
                    info.visibility = View.GONE; notes.visibility = View.GONE; bar.visibility = View.GONE; btnAct.visibility = View.GONE
                    return
                }
                status.text = "${rel.typeLabel} आया है"
                info.text = "नया version: DK ${rel.version}  •  साइज़: ${rel.sizeMb}"
                info.visibility = View.VISIBLE
                notes.text = "क्या नया है:\n" + rel.notes.take(1200).ifBlank { if (rel.isSecurity) "गड़बड़ियाँ ठीक की गईं" else "नए फ़ीचर और सुधार" }
                notes.visibility = View.VISIBLE
                btnAct.visibility = View.VISIBLE
                when {
                    Updater.downloaded(this, rel) != null -> {
                        bar.visibility = View.GONE
                        btnAct.text = "इंस्टॉल करें"; btnAct.isEnabled = true
                        status.text = "✓ ${rel.typeLabel} डाउनलोड हो चुका है"
                    }
                    Updater.isDownloading(rel) -> {
                        bar.visibility = View.VISIBLE; bar.progress = Updater.progress
                        btnAct.text = "डाउनलोड हो रहा है… ${Updater.progress}%"; btnAct.isEnabled = false
                    }
                    else -> {
                        bar.visibility = View.GONE
                        btnAct.text = "डाउनलोड करें (${rel.sizeMb})"; btnAct.isEnabled = true
                        Updater.lastError?.let { status.text = "डाउनलोड रुक गया: $it — फिर दबाएँ, जहाँ रुका वहीं से आगे होगा" }
                    }
                }
            }

            /** Download chalte samay har 300ms screen taaza (download khud background me chalta hai) */
            fun follow(rel: Updater.Release) {
                watch?.cancel()
                watch = lifecycleScope.launch {
                    while (true) {
                        show(rel)
                        if (!Updater.isDownloading(rel)) break
                        kotlinx.coroutines.delay(300)
                    }
                    show(rel)
                }
            }

            val btnCheck = button("अपडेट चेक करें") {
                status.text = "चेक हो रहा है…"
                lifecycleScope.launch {
                    try {
                        val rel = Updater.check(this@SettingsActivity)
                        pendingRelease = rel
                        show(rel)
                        if (rel != null && Updater.isDownloading(rel)) follow(rel)
                    } catch (e: Exception) {
                        status.text = "चेक नहीं हो पाया: ${e.message}. इंटरनेट देखें।"
                    }
                }
            }
            btnAct = button("डाउनलोड करें") {
                val rel = pendingRelease ?: return@button
                if (Updater.downloaded(this, rel) != null) {
                    if (!Updater.canInstall(this)) {
                        toast("DK को \"ऐप इंस्टॉल करने\" की इजाज़त दें, फिर वापस आकर दोबारा दबाएँ")
                        Updater.askInstallPermission(this); return@button
                    }
                    status.text = "इंस्टॉल स्क्रीन पर \"Update / Install\" दबाएँ"
                    lifecycleScope.launch {
                        try { Updater.install(this@SettingsActivity, rel) }
                        catch (e: Exception) { status.text = "इंस्टॉल नहीं हुआ: ${e.message}" }
                    }
                } else {
                    Updater.startDownload(this, rel)
                    toast("डाउनलोड बैकग्राउंड में चल रहा है — ऐप बंद कर सकते हैं, पूरा होने पर सूचना आएगी")
                    follow(rel)
                }
            }.apply { visibility = View.GONE }
            row(box, btnCheck)
            row(box, btnAct)
            swAutoUpd = switch(box, "ऐप खुलते ही अपडेट चेक करो और बैकग्राउंड में डाउनलोड करो", Prefs.autoUpdate(this))
            hint(box, "सॉफ़्टवेयर अपडेट = नया डिज़ाइन / नए फ़ीचर  •  सिक्योरिटी पैच = गड़बड़ियाँ और सुरक्षा ठीक")
            // Settings khulte hi ek baar chupchap check
            btnCheck.performClick()
        }

    }

    private fun secProfile() {
        card("प्रोफ़ाइल", "आपका नाम, असिस्टेंट का नाम और बात करने का तरीका") {
            edUser = field(it, "आपका नाम", Prefs.userName(this), "जैसे: दीपक")
            edName = field(it, "असिस्टेंट का नाम", Prefs.assistantName(this), "DK")
            edPersona = field(it, "बात करने का तरीका (पर्सनैलिटी)", Prefs.personality(this),
                "जैसे: दोस्त की तरह मज़ेदार बात करो, जवाब छोटे रखो", multiLine = true)
            edWake = field(it, "जगाने वाले शब्द (कॉमा से अलग)", Prefs.wakeWordsRaw(this), "डीके, dk")
        }

    }

    private fun secBrain() {
        quickSetupCard()
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
            swWeb = switch(box, "ज़रूरी सवालों का जवाब इंटरनेट (Google Search) से पता करके दो — Gemini key से", Prefs.webAnswers(this))
            hint(box, "नीचे जिस कंपनी को इस्तेमाल करना है उसकी API key डालें — key डालते ही मॉडल अपने-आप आ जाएँगे।")
        }

        Providers.all.forEach { p -> providerCard(p) }

    }

    /**
     * Sabse aasaan: koi bhi FREE key paste karo -> DK khud company pehchaane, free models laaye,
     * sabse tez chune, save kare aur baat shuru. Jawab (text) aur awaaz (sunna) ki key ALAG = takraav nahi.
     */
    private fun quickSetupCard() {
        card("तुरंत सेटअप — कोई भी फ्री key डालें", "DK खुद पहचानेगा कि key किस कंपनी की है, उसके फ्री मॉडल जाँचकर सबसे तेज़ चुनेगा और बात शुरू कर देगा") { box ->
            val edText = field(box, "1. जवाब (टेक्स्ट) के लिए key", "", "Groq / Gemini / OpenRouter / Mistral / Z.ai — कोई भी", secret = true)
            val res1 = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            val cur = Prefs.activeProvider(this).let { id -> if (Prefs.key(this, id).isNotBlank()) "अभी: ${KeySetup.name(id)} • ${Prefs.model(this, id)}" else "अभी कोई key नहीं" }
            res1.text = cur
            row(box, button("जोड़ें और सबसे तेज़ चुनें") {
                val k = edText.text.toString().trim()
                if (k.isBlank()) { res1.text = "पहले key पेस्ट करें"; return@button }
                res1.text = "key पहचान रहा हूँ और फ्री मॉडल जाँच रहा हूँ…"
                lifecycleScope.launch {
                    val r = KeySetup.setupText(this@SettingsActivity, k)
                    res1.text = r.msg
                    if (r.ok) {
                        edText.setText("")
                        // neeche wale company card bhi naye hisaab se (warna save() purani key likh deta)
                        keyFields[r.provider]?.setText(Prefs.key(this@SettingsActivity, r.provider))
                        modelFields[r.provider]?.setText(r.model)
                        fetchedFor[r.provider] = Prefs.key(this@SettingsActivity, r.provider)
                        Providers.all.indexOfFirst { it.id == r.provider }.takeIf { it >= 0 }?.let { rgProvider.check(1000 + it) }
                        if (::swFallback.isInitialized) swFallback.isChecked = true
                        save(false)
                        startTalking()
                    }
                }
            }, button("Groq key लें") { Setup.openUrl(this, "https://console.groq.com/keys") })
            box.addView(res1)

            space(box, 10)
            val edVoice = field(box, "2. आवाज़ (सुनने) के लिए अलग key — Groq या Gemini", "", "खाली छोड़ें = जवाब वाली key से ही सुनेगा", secret = true)
            val res2 = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            res2.text = voiceStatus()
            row(box, button("आवाज़ key जोड़ें") {
                val k = edVoice.text.toString().trim()
                if (k.isBlank()) { res2.text = "पहले Groq या Gemini की key पेस्ट करें"; return@button }
                res2.text = "आवाज़ वाली key जाँच रहा हूँ…"
                lifecycleScope.launch {
                    val r = KeySetup.setupVoice(this@SettingsActivity, k)
                    res2.text = r.msg
                    if (r.ok) { edVoice.setText(""); DKAccessibilityService.instance?.startWakeLoopIfEnabled() }
                }
            }, button("हटाएँ") {
                lifecycleScope.launch { res2.text = KeySetup.setupVoice(this@SettingsActivity, "").msg }
            })
            box.addView(res2)
            hint(box, "सलाह: जवाब के लिए एक key (जैसे Gemini) और आवाज़ के लिए दूसरी (जैसे Groq) डालें — दोनों फ्री हैं, और एक की लिमिट दूसरी को नहीं रोकेगी। सिर्फ़ फ्री मॉडल चुने जाते हैं।")
        }
    }

    private fun voiceStatus(): String {
        val vk = Prefs.voiceKey(this)
        val stt = Prefs.sttKeys(this)
        return when {
            vk.isNotBlank() -> "अभी: आवाज़ के लिए अलग ${KeySetup.name(Prefs.voiceProvider(this).ifBlank { KeySetup.detect(vk) ?: "" })} key लगी है"
            stt.isNotEmpty() -> "अभी: जवाब वाली ${KeySetup.name(stt.first().first)} key से सुन रहा है (अलग key डालें तो तेज़ और बिना टकराव)"
            else -> "अभी: Google माइक से सुन रहा है (बीप आ सकती है) — Groq की फ्री key डालें"
        }
    }

    /** Key jud gayi -> seedha baatcheet shuru */
    private fun startTalking() {
        val svc = DKAccessibilityService.instance
        if (svc == null) { toast("✓ Key तैयार — अब Accessibility में DK चालू करें, फिर ओर्ब दबाकर बोलिए"); return }
        if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
        svc.startLive()
        toast("✓ तैयार — बोलिए, DK सुन रहा है")
        try { startActivity(android.content.Intent(this, VoiceActivity::class.java)) } catch (_: Exception) {}
    }

    private fun secVoice() {
        card("आवाज़", "महिला या पुरुष — दोनों फ्री • DK का चेहरा भी इसी आवाज़ के हिसाब से बनेगा") { box ->
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
            // Gemini chuna ho to yahin saari awaazein (mahila chuna = sirf mahila, purush = sirf purush). Tap = chuno + suno
            gemList = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, 0) }
            box.addView(gemList)
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
                "auto" to "सबसे तेज़ — Gemini Live, आवाज़ से आवाज़ (Gemini key से); न चले तो बिना बीप",
                "classic" to "बिना बीप — DK खुद सुनेगा: Whisper + AI + आवाज़ (Groq या Gemini key)",
                "google" to "Google माइक — बिना key, पर हर बार बीप आ सकती है"),
                Prefs.listenEngine(this), 2200)
            hint(box, "बिना बीप वाले मोड में सबसे तेज़ और सही हिंदी पहचान के लिए Groq की फ्री key डालें (Whisper)। Groq key न हो तो Gemini key से काम चलेगा।")
            hint(box, voiceStatus() + "\nआवाज़ के लिए अलग key: सेटिंग्स → AI दिमाग → \"तुरंत सेटअप\" में दूसरा खाना।")
        }

    }

    // ------------------------------------------------------------ orb + chehra
    private fun secOrb() {
        val styles = Shapes.STYLES
        lateinit var preview: OrbView
        var chosen = Prefs.orbStyle(this)
        card("झलक", "ओर्ब पर टैप = चेहरा बनने वाला एनीमेशन") { box ->
            preview = OrbView(this).apply {
                previewStyle = chosen
                setOnClickListener { playFace(this, chosen) }
            }
            box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (260 * dp).toInt()))
        }
        card("ओर्ब का आकार", "सब छोटे-छोटे 3D डॉट से बने हैं — जो पसंद हो चुनें") { box ->
            val g = radios(box, styles, chosen, 2400)
            g.setOnCheckedChangeListener { _, id ->
                chosen = styles.getOrNull(id - 2400)?.first ?: "earth"
                Prefs.edit(this) { putString("orb_style", chosen) }
                preview.previewStyle = chosen
            }
        }
        card("DK का चेहरा", "\"अपना चेहरा दिखाओ\" बोलने पर डॉट निकलकर चेहरा बनता है — आवाज़ महिला हो तो महिला चेहरा, पुरुष हो तो पुरुष") { box ->
            row(box,
                button("चेहरा दिखाओ") { playFace(preview, chosen) },
                button("बड़ी स्क्रीन पर") {
                    startActivity(android.content.Intent(this, VoiceActivity::class.java).putExtra("face", true))
                })
            hint(box, "अभी की आवाज़: " + (if (Prefs.voiceGender(this) == "male") "पुरुष" else "महिला") + " — बदलने के लिए सेटिंग्स → आवाज़")
        }
    }

    private fun playFace(v: OrbView, back: String) {
        val face = if (Prefs.voiceGender(this) == "male") "face_male" else "face_female"
        if (v.previewStyle == face && back != face) { v.previewStyle = back; return }
        v.previewStyle = face
        v.postDelayed({ if (v.previewStyle == face && back != face) v.previewStyle = back }, 9000)
    }

    // ------------------------------------------------------------ accessibility
    private var accStatus: TextView? = null
    private var batStatus: TextView? = null
    private var secStatus: TextView? = null

    private fun secAccess() {
        card("फ़ोन कंट्रोल (Accessibility)", "एक बार ON करें — फिर ON ही रहेगा") { box ->
            accStatus = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            box.addView(accStatus)
            row(box,
                button("Accessibility ON करें") { Setup.openAccessibility(this) },
                button("App info") { Setup.openAppInfo(this) })
            hint(box, "Android 13+: अगर Accessibility में DK धुंधला दिखे तो App info → ऊपर ⋮ → \"Allow restricted settings\" करें, फिर ON करें।")
        }
        card("बार-बार बंद न हो", "Android बैटरी बचाने के लिए DK को बंद कर देता है — यह रोकें") { box ->
            batStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            box.addView(batStatus)
            row(box, button("बैटरी रोक हटाएँ") { Setup.requestBatteryExempt(this) })
            hint(box, "Xiaomi/Redmi/Poco: App info → Autostart ON, Battery saver → No restrictions। Vivo/Oppo/Realme: Battery → Allow background activity। Recent apps में DK पर लॉक लगाएँ।")
            label(box, "हमेशा ON (सबसे पक्का तरीका — एक बार कंप्यूटर से)")
            secStatus = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), 0, 0, 0) }
            box.addView(secStatus)
            val cmd = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
            box.addView(TextView(this).apply {
                text = cmd; textSize = 12f; setTextColor(0xFF00E5FF.toInt()); setTextIsSelectable(true)
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
            })
            row(box, button("कमांड कॉपी करें") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd)); toast("कॉपी हो गया")
            })
            hint(box, "USB debugging ON करके कंप्यूटर से यह कमांड एक बार चलाएँ। उसके बाद अगर Android कभी DK की Accessibility बंद भी कर दे, तो DK खुद उसे वापस ON कर लेगा।")
        }
        card("सुनना और कॉल", null) { box ->
            swWake = switch(box, "हमेशा सुनो (जगाने वाला शब्द बोलकर कमांड)", Prefs.wakeMode(this))
            swCalls = switch(box, "कॉल आने पर पहले बताओ किसका कॉल है (\"उठा लो\" / \"काट दो\" बोलें)", Prefs.announceCalls(this))
            row(box, button("कॉल/कॉन्टैक्ट परमिशन दें") {
                androidx.core.app.ActivityCompat.requestPermissions(this, CallManager.PERMS, 11)
            })
            hint(box, "कॉल के दौरान Android दूसरे ऐप्स को माइक नहीं देता — कॉल काटने के लिए स्क्रीन पर तैरता DK बुलबुला एक बार दबाएँ।")
        }
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

        refreshAccess()
    }

    private fun refreshAccess() {
        accStatus?.text = when {
            Setup.accessibilityOn() -> "✓ चालू है — DK फ़ोन चला सकता है"
            Setup.enabledInSettings(this) -> "⟳ ON है, सर्विस दोबारा शुरू हो रही है… (न हो तो एक बार OFF-ON करें)"
            else -> "✗ बंद है — नीचे बटन दबाकर Installed/Downloaded apps में ${Prefs.assistantName(this)} ON करें"
        }
        batStatus?.text = if (Setup.batteryExempt(this)) "✓ बैटरी रोक हटी हुई है" else "✗ Android DK को बैटरी बचाने के लिए बंद कर सकता है"
        secStatus?.text = if (Setup.canWriteSecure(this)) "✓ चालू — DK अपनी Accessibility खुद ON रखेगा" else "अभी नहीं दिया गया (ज़रूरी नहीं, पर सबसे पक्का)"
    }

    // ------------------------------------------------------------ permissions
    private var permStatus: TextView? = null

    private fun secPerms() {
        card("स्क्रीन देखना", "DK आपकी स्क्रीन देखकर काम करे (स्क्रीनशॉट AI को जाता है)") { box ->
            swShot = switch(box, "स्क्रीन देखना / शेयर करना", Prefs.screenshot(this))
            swShot.setOnCheckedChangeListener { _, on ->
                Prefs.edit(this) { putBoolean("screenshot", on) }
                toast(if (on) "अब DK स्क्रीन देख सकता है" else "स्क्रीन देखना बंद")
            }
            hint(box, "बोलकर भी कर सकते हैं: \"डीके, स्क्रीन देखना चालू करो\" / \"स्क्रीन देखना बंद करो\"। बंद रहने पर DK सिर्फ़ स्क्रीन का लिखा हुआ पढ़ता है।")
        }
        card("ज़रूरी परमिशन", null) { box ->
            permStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            box.addView(permStatus)
            row(box, button("माइक + नोटिफ़िकेशन") {
                val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                androidx.core.app.ActivityCompat.requestPermissions(this, perms.toTypedArray(), 12)
            }, button("कॉल/कॉन्टैक्ट") {
                androidx.core.app.ActivityCompat.requestPermissions(this, CallManager.PERMS, 11)
            })
            row(box, button("ऐप इंस्टॉल (अपडेट के लिए)") { Updater.askInstallPermission(this) },
                button("App info") { Setup.openAppInfo(this) })
        }
        refreshPerms()
    }

    private fun refreshPerms() {
        fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notif = android.os.Build.VERSION.SDK_INT < 33 || ok(android.Manifest.permission.POST_NOTIFICATIONS)
        val calls = CallManager.PERMS.all { ok(it) }
        permStatus?.text = (if (Setup.hasMic(this)) "✓" else "✗") + " माइक\n" +
            (if (notif) "✓" else "✗") + " नोटिफ़िकेशन\n" +
            (if (calls) "✓" else "✗") + " कॉल / कॉन्टैक्ट\n" +
            (if (Updater.canInstall(this)) "✓" else "✗") + " ऐप इंस्टॉल (अपडेट)"
    }

    override fun onResume() {
        super.onResume()
        refreshAccess(); refreshPerms()
        if (section == null && container.childCount > 0) { container.removeAllViews(); menu(); space(container, 8) }
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
        if (section == null || section == "orb") return
        val active = if (::rgProvider.isInitialized)
            Providers.all.getOrNull(rgProvider.checkedRadioButtonId - 1000)?.id ?: Prefs.activeProvider(this)
        else Prefs.activeProvider(this)
        Prefs.edit(this) {
            if (::edUser.isInitialized) {
                putString("user_name", edUser.text.toString().trim())
                putString("assistant_name", edName.text.toString().trim().ifBlank { "DK" })
                putString("personality", edPersona.text.toString().trim())
                putString("wake_words", edWake.text.toString().trim())
            }
            if (::rgProvider.isInitialized) {
                putString("active_provider", active)
                putBoolean("fallback", swFallback.isChecked)
                putBoolean("web_answers", swWeb.isChecked)
            }
            if (::swWake.isInitialized) {
                putBoolean("wake_mode", swWake.isChecked)
                putBoolean("announce_calls", swCalls.isChecked)
            }
            if (::swShot.isInitialized) putBoolean("screenshot", swShot.isChecked)
            if (::swAutoUpd.isInitialized) putBoolean("auto_update", swAutoUpd.isChecked)
            if (::rgGender.isInitialized) {
                putFloat("tts_rate", seekValue(sbRate))
                putString("voice_gender", radioValue(rgGender, listOf("female", "male"), 2000))
                putString("voice_engine", radioValue(rgEngine, listOf("google", "gemini"), 2100))
                putString("voice_style", radioValue(rgStyle, VoiceCatalog.STYLES.map { it.first }, 2300))
                putString("listen_engine", radioValue(rgListen, listOf("auto", "classic", "google"), 2200))
                putFloat("tts_pitch", seekValue(sbPitch))
            }
            keyFields.forEach { (id, e) -> putString("key_$id", e.text.toString().trim()) }
            modelFields.forEach { (id, e) -> putString("model_$id", e.text.toString().trim()) }
            baseFields.forEach { (id, e) -> putString("base_$id", e.text.toString().trim()) }
        }
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        txtSaved.text = "✓ सेव हो गया"
        if (showToast) {
            val active2 = Providers.byId(active)
            val warn = if (::rgProvider.isInitialized && Prefs.key(this, active).isBlank()) " (पर ${active2?.name} की key खाली है)" else ""
            toast("सेटिंग्स सेव हो गईं$warn")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DKAccessibilityService.instance?.calls?.start()
        refreshPerms()
        if (requestCode == 12) Setup.startKeepAlive(this)
        if (requestCode == 11) toast(if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) "✓ कॉल परमिशन मिल गई"
            else "कुछ परमिशन नहीं मिलीं — App info → Permissions से दें (Android 13+: पहले ⋮ → Allow restricted settings)")
    }

    override fun onPause() { save(false); super.onPause() }
    override fun onDestroy() { if (section == "debug") DKLog.listener = null; speaker?.shutdown(); super.onDestroy() }

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

    private lateinit var gemList: RadioGroup

    /** Gemini ki awaazein - sirf chune hue gender ki (mahila/purush), har ek ka vivaran; tap = chuno + suno */
    private fun fillGeminiList() {
        if (!::gemList.isInitialized) return
        gemList.setOnCheckedChangeListener(null)
        gemList.removeAllViews()
        val gender = Prefs.voiceGender(this)
        val list = VoiceCatalog.geminiFor(gender)
        val cur = Prefs.geminiVoice(this)
        list.forEachIndexed { i, name ->
            gemList.addView(RadioButton(this).apply {
                id = 2500 + i
                text = "$name — ${VoiceCatalog.GEMINI_INFO[name].orEmpty()}"
                textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF7FDBFF.toInt())
            })
        }
        gemList.check(2500 + list.indexOf(cur).coerceAtLeast(0))
        gemList.setOnCheckedChangeListener { _, id ->
            val name = list.getOrNull(id - 2500) ?: return@setOnCheckedChangeListener
            Prefs.edit(this) { putString("gemini_voice", name) }
            txtVoice.text = geminiVoiceLine()
            toast("सुनाई दे रही है: $name …")
            val sample = "नमस्ते, मैं ${Prefs.assistantName(this)} हूँ। क्या मैं आपकी मदद करूँ?"
            lifecycleScope.launch { sp().previewGemini(name, sample)?.let { toast(it) } }
        }
    }

    private fun geminiVoiceLine(): String {
        val g = if (Prefs.voiceGender(this) == "male") "पुरुष" else "महिला"
        return "चुनी हुई आवाज़: ${Prefs.geminiVoice(this)}  •  $g की ${VoiceCatalog.geminiFor(Prefs.voiceGender(this)).size} आवाज़ें — नीचे टैप करके सुनें और चुनें" +
            if (Prefs.key(this, "gemini").isBlank()) "\n(Gemini key नहीं डाली — तब तक Google आवाज़)" else ""
    }

    private fun updateVoiceText() {
        if (!::txtVoice.isInitialized) return
        val engine = radioValue(rgEngine, listOf("google", "gemini"), 2100)
        if (::gemList.isInitialized) gemList.visibility = if (engine == "gemini") View.VISIBLE else View.GONE
        if (engine == "gemini") {
            txtVoice.text = geminiVoiceLine()
            fillGeminiList()
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
            val all = if (gender == "male") m else f        // jo gender chuna, usi ki awaazein
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle((if (gender == "male") "पुरुष" else "महिला") + " Gemini आवाज़ चुनें (टैप = सुनें)")
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
