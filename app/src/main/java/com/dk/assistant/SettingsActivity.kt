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
private const val GEM_ID = 3000   // Gemini awaazon ke radio id (doosre radio groups se alag)

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
        Glass.install(this)
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
            "lang" -> { title.text = "भाषा / Language"; secLang() }
            "brain" -> { title.text = "AI दिमाग"; bar.visibility = View.GONE; secBrainMenu() }
            "brain_live" -> { title.text = "AI दिमाग • DK लाइव"; secBrainLive() }
            "brain_chat" -> { title.text = "AI दिमाग • DK चैट"; secBrain() }
            "brain_coder" -> { title.text = "AI दिमाग • कोडिंग एजेंट"; secBrainScope(ApiScopes.CODER) }
            "brain_pc" -> { title.text = "AI दिमाग • DK कंप्यूटर"; secBrainScope(ApiScopes.PC) }
            "voice" -> { title.text = "आवाज़"; secVoice() }
            "orb" -> { title.text = "ओर्ब और चेहरा"; bar.visibility = View.GONE; secOrb() }
            "access" -> { title.text = "Accessibility"; secAccess() }
            "perms" -> { title.text = "परमिशन"; secPerms() }
            "update" -> { title.text = "अपडेट"; secUpdate() }
            "debug" -> { title.text = "डिबग लॉग"; bar.visibility = View.GONE; secDebug() }
            "coder" -> { title.text = "कोडिंग एजेंट"; secCoder() }
            "computer" -> { title.text = "DK कंप्यूटर"; secComputer() }
            "about" -> { title.text = "ऐप के बारे में"; bar.visibility = View.GONE; secAbout() }
        }
        space(container, 8)
    }


    // ------------------------------------------------------------ mukhya menu
    private fun menu() {
        val name = Prefs.assistantName(this)
        val items = listOf(
            Triple("profile", "प्रोफ़ाइल", "आपका नाम • असिस्टेंट का नाम ($name) • बात करने का तरीका"),
            Triple("lang", "भाषा / Language", "ऐप: ${if (UiLang.en()) "English" else "हिंदी"} • DK के जवाब: ${Lang.short(Prefs.replyLang(this))}"),
            Triple("brain", "AI दिमाग", "DK लाइव • DK चैट • कोडिंग एजेंट • DK कंप्यूटर — हर एक की अपनी API key"),
            Triple("voice", "आवाज़", "महिला / पुरुष • आवाज़ चुनें • स्पीड • सुनने का तरीका"),
            Triple("orb", "ओर्ब और चेहरा", "पृथ्वी, गैलेक्सी, एटम, DNA, छल्ला, पुरुष/महिला चेहरा"),
            Triple("access", "Accessibility", (if (Setup.accessibilityOn(this)) "✓ चालू" else "✗ बंद") + " • फ़ोन कंट्रोल • हमेशा सुनना • कॉल • बैटरी"),
            Triple("perms", "परमिशन", "माइक • स्क्रीन देखना • नोटिफ़िकेशन • कॉल • ऐप इंस्टॉल"),
            Triple("computer", "DK कंप्यूटर", (if (Prefs.computerOn(this)) "✓ चालू" else "✗ बंद") + " • कोई भी काम: खोज, रिसर्च, फ़ाइल/PDF, हिसाब, फ़ोन, रिमाइंडर"),
            Triple("coder", "कोडिंग एजेंट", (if (Prefs.coderOn(this)) "✓ चालू" else "✗ बंद") + " • GitHub से जुड़ें • कोड लिखें/बदलें/हटाएँ • push • अलग AI key"),
            Triple("story", "स्टोरी राइटर एजेंट", "लंबी हिंदी कहानियाँ (10–30 मिनट) • भाग-दर-भाग • कॉपी • फ़ोन में सेव"),
            Triple("update", "अपडेट", "अभी: ${Updater.displayVersion(this)} • नया version चेक करें"),
            Triple("debug", "डिबग लॉग", "माइक, कनेक्शन, देरी और गलतियों का पूरा हाल"),
            Triple("about", "ऐप के बारे में", "version • बनाने वाले • निजता (privacy)")
        )
        items.forEach { (id, t, sub) -> menuRow(id, t, sub) }
    }

    /** मेनू की एक पंक्ति (icon + नाम + विवरण + ›) - दबाने पर वह section */
    private fun menuRow(id: String, t: String, sub: String, icon: String = id) {
        run {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_card)
                val pd = (18 * dp).toInt(); setPadding(pd, pd, pd, pd)
                isClickable = true
                setOnClickListener {
                    // Story Writer: seedha apni screen (iski koi settings nahi)
                    if (id == "story") startActivity(android.content.Intent(this@SettingsActivity, StoryWriterActivity::class.java))
                    else startActivity(android.content.Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra("section", id))
                }
            }
            // har section ka apna rangeen icon (rang neele background se alag) glass gole me
            box.addView(android.widget.ImageView(this).apply {
                setImageResource(menuIcon(icon)); setBackgroundResource(R.drawable.glass_circle)
                val ip = (10 * dp).toInt(); setPadding(ip, ip, ip, ip); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
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

    /**
     * भाषा: (1) ऐप की स्क्रीनों की भाषा - English (शुरुआती) / हिंदी, पूरे ऐप के बटन, Settings, संदेश, सूचनाएँ;
     * (2) DK के जवाब की भाषा (v3.30) - Hinglish / हिंदी / English, पहले जैसी.
     */
    private fun secLang() {
        card("ऐप की भाषा (स्क्रीन)", "पूरे ऐप के बटन, Settings, संदेश — English या हिंदी") { box ->
            val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val ui = listOf(UiLang.EN to "English (default)", UiLang.HI to "हिंदी (Hindi)")
            ui.forEachIndexed { i, (code, label) ->
                rg.addView(RadioButton(this).apply {
                    id = 3100 + i; text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                    isChecked = code == UiLang.code
                    setPadding((6 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                    UiLang.keep(this)     // भाषा के नाम हर भाषा में वैसे ही
                })
            }
            rg.setOnCheckedChangeListener { _, idc ->
                val code = ui.getOrNull(idc - 3100)?.first ?: return@setOnCheckedChangeListener
                if (code == UiLang.code) return@setOnCheckedChangeListener
                UiLang.set(this, code)
                toast(if (code == UiLang.EN) "App language: English" else "ऐप की भाषा: हिंदी")
                // यह screen अभी नई बने; पीछे खुली screens लौटते ही अपने-आप नई भाषा में
                recreate()
            }
            box.addView(rg)
            hint(box, "चुनते ही पूरे ऐप की स्क्रीनें इसी भाषा में। DK के जवाब की भाषा नीचे अलग से चुनें। बातचीत, कोड और कहानियाँ जैसी लिखी हैं वैसी ही दिखती हैं।")
        }
        card("DK किस भाषा में जवाब दे", "DK लाइव, DK चैट, बिना-Live आवाज़, DK कंप्यूटर और कोडिंग एजेंट — सब पर एक साथ") { box ->
            val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val cur = Prefs.replyLang(this)
            Lang.CHOICES.forEachIndexed { i, (code, label) ->
                rg.addView(RadioButton(this).apply {
                    id = 3000 + i
                    text = label
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    isChecked = code == cur
                    setPadding((6 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                })
            }
            rg.setOnCheckedChangeListener { _, idc ->
                val code = Lang.CHOICES.getOrNull(idc - 3000)?.first ?: return@setOnCheckedChangeListener
                Prefs.edit(this) { putString(Lang.KEY, code) }
                Prefs.loadLang(this)
                toast("भाषा: ${Lang.short(code)} — अगले जवाब से लागू")
            }
            box.addView(rg)
            hint(box, "DK लाइव चल रहा हो तो नई भाषा अगली बार जुड़ने पर (लाइव बंद करके फिर चालू करें)। " +
                "किसी एक जवाब के लिए आप बोलकर दूसरी भाषा माँग सकते हैं।")
        }
    }

    private fun menuIcon(id: String) = when (id) {
        "profile" -> R.drawable.ic_s_profile
        "lang" -> R.drawable.ic_s_lang
        "brain" -> R.drawable.ic_s_brain
        "voice" -> R.drawable.ic_s_voice
        "orb" -> R.drawable.ic_s_orb
        "access" -> R.drawable.ic_s_access
        "perms" -> R.drawable.ic_s_perms
        "coder" -> R.drawable.ic_s_coder
        "computer" -> R.drawable.ic_s_computer
        "story" -> R.drawable.ic_s_story
        "update" -> R.drawable.ic_s_update
        "about" -> R.drawable.ic_s_profile
        else -> R.drawable.ic_s_debug
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
                textSize = 13f; setTextColor(Glass.ACCENT); visibility = View.GONE
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
                    UiLang.setText(status, "✓ आपके पास सबसे नया version है")
                    info.visibility = View.GONE; notes.visibility = View.GONE; bar.visibility = View.GONE; btnAct.visibility = View.GONE
                    return
                }
                UiLang.setText(status, "${rel.typeLabel} आया है")
                UiLang.setText(info, "नया version: DK ${rel.version}  •  साइज़: ${rel.sizeMb}")
                info.visibility = View.VISIBLE
                UiLang.setText(notes, "क्या नया है:\n" + rel.notes.take(1200).ifBlank { if (rel.isSecurity) "गड़बड़ियाँ ठीक की गईं" else "नए फ़ीचर और सुधार" })
                notes.visibility = View.VISIBLE
                btnAct.visibility = View.VISIBLE
                when {
                    Updater.downloaded(this, rel) != null -> {
                        bar.visibility = View.GONE
                        UiLang.setText(btnAct, "इंस्टॉल करें"); btnAct.isEnabled = true
                        UiLang.setText(status, "✓ ${rel.typeLabel} डाउनलोड हो चुका है")
                    }
                    Updater.isDownloading(rel) -> {
                        bar.visibility = View.VISIBLE; bar.progress = Updater.progress
                        UiLang.setText(btnAct, "डाउनलोड हो रहा है… ${Updater.progress}%"); btnAct.isEnabled = false
                    }
                    else -> {
                        bar.visibility = View.GONE
                        UiLang.setText(btnAct, "डाउनलोड करें (${rel.sizeMb})"); btnAct.isEnabled = true
                        Updater.lastError?.let { UiLang.setText(status, "डाउनलोड रुक गया: $it — फिर दबाएँ, जहाँ रुका वहीं से आगे होगा") }
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
                        // v3.64: असली वजह (403 = GitHub की घंटे वाली सीमा) - पहले हर बार "इंटरनेट देखें"
                        status.text = "चेक नहीं हो पाया: ${e.message ?: ReleaseFeed.friendly(0)}"
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
            edPersona = field(it, "बात करने का तरीका (अपनी तरफ़ से, चुनी पर्सनैलिटी के साथ जुड़ता है)", Prefs.personality(this),
                "जैसे: जवाब छोटे रखो, मुझे सर कहकर बुलाओ", multiLine = true)
            edWake = field(it, "जगाने वाले शब्द (कॉमा से अलग)", Prefs.wakeWordsRaw(this), "डीके, dk")
        }
        // DK AI: 10 पर्सनैलिटी - चुनते ही सेव, अगले जवाब से लागू (DK लाइव: अगली बार जुड़ने पर)
        card("पर्सनैलिटी", "DK किस अंदाज़ में बात करे - 10 में से चुनें") { box ->
            val items = Personality.entries.map { it.name to "${it.icon}  ${it.displayName} — ${it.description}" }
            val g = radios(box, items, Prefs.personalityPreset(this).name, 4100)
            g.setOnCheckedChangeListener { _, idc ->
                val p = Personality.entries.getOrNull(idc - 4100) ?: return@setOnCheckedChangeListener
                Prefs.edit(this) { putString(Personality.PREF_KEY, p.name) }
                toast("पर्सनैलिटी: ${p.displayName} — अगले जवाब से लागू")
            }
            hint(box, "DK लाइव चल रहा हो तो नई पर्सनैलिटी अगली बार जुड़ने पर (लाइव बंद करके फिर चालू करें)। " +
                "Companion और GF Mode में भी DK खुद को इंसान नहीं बताता और कोई अश्लील बात नहीं करता।")
        }
        secAccessKey()

    }

    /** DK AI Part 13: access key (एडमिन ${AssistantBrand.OWNER_NAME}) */
    private fun secAccessKey() {
        val km = com.dk.assistant.license.AccessKeyManager
        card("Access key", "DK चलाने की key • एडमिन: ${AssistantBrand.OWNER_NAME}") { box ->
            val status = TextView(this).apply {
                textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt())
                text = km.refreshInfo(this@SettingsActivity).statusText
            }
            box.addView(status)
            if (!km.configured(this)) {
                hint(box, "लाइसेंस सर्वर इस ऐप में सेट नहीं है, इसलिए key की ज़रूरत नहीं।")
                return@card
            }
            val ed = field(box, "आपकी access key", "", "जैसे: DK-ABCD-1234", secret = true)
            ed.removeTextChangedListener(watcher)
            var busy = false
            val btnOn = button("key चालू करें") {
                if (busy) return@button
                busy = true; status.text = UiLang.s("जाँच हो रही है…")
                lifecycleScope.launch {
                    val msg = km.activate(this@SettingsActivity, ed.text.toString())
                    busy = false; status.text = msg; ed.setText("")
                }
            }
            val btnCheck = button("अभी जाँचें") {
                lifecycleScope.launch { status.text = km.liveCheck(this@SettingsActivity, force = true).statusText }
            }
            val btnForget = button("key हटाएँ") {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("इस फ़ोन से key हटाएँ?"))
                    .setMessage(UiLang.s("हटाने के बाद DK तब तक काम नहीं करेगा जब तक नई key न डालें।"))
                    .setPositiveButton(UiLang.s("हटाएँ")) { _, _ -> km.forget(this); status.text = km.refreshInfo(this).statusText }
                    .setNegativeButton(UiLang.s("रहने दो"), null).show()
            }
            row(box, btnOn, btnCheck)
            row(box, btnForget)
            hint(box, "key हर 3 घंटे में एक बार सर्वर से जाँची जाती है। इंटरनेट न हो तो पिछली हालत चलती रहती है। key रोकने/चालू करने का अधिकार सिर्फ़ एडमिन ${AssistantBrand.OWNER_NAME} के पास है।")
        }
    }

    // ------------------------------------------------------------ AI दिमाग: 4 अलग हिस्से, हर एक की अपनी API
    private fun scopeStatus(scope: String): String {
        if (scope == ApiScopes.LIVE) {
            val g = Prefs.scopeKey(this, scope, "gemini").isNotBlank(); val q = Prefs.scopeKey(this, scope, "groq").isNotBlank()
            return (if (g) "✓ Gemini key" else "✗ Gemini key नहीं") + (if (q) " • ✓ सुनने के लिए Groq" else "") + " • सिर्फ़ Gemini"
        }
        val id = Prefs.scopeActive(this, scope)
        val ok = Prefs.scopeKey(this, scope, id).isNotBlank()
        return (if (ok) "✓ " else "✗ ") + KeySetup.name(id) + (if (ok) " • ${Prefs.scopeModel(this, scope, id)}" else " की key नहीं") + " • अपनी अलग key"
    }

    private fun secBrainMenu() {
        card("हर हिस्से की अपनी API", "हर हिस्सा सिर्फ़ अपनी डाली हुई key से जवाब लेता है — किसी दूसरे की key इस्तेमाल नहीं करता") { box ->
            hint(box, "किसी मॉडल का फ्री कोटा खत्म (limit) हो तो उसी key के दूसरे फ्री मॉडल पर अपने-आप स्विच होता है। दूसरी कंपनी पर नहीं जाता।")
        }
        menuRow("brain_live", "DK लाइव", scopeStatus(ApiScopes.LIVE) + "\nआवाज़ से बातचीत (Gemini Live), बोलना, सुनना", "voice")
        menuRow("brain_chat", "DK चैट", scopeStatus(ApiScopes.CHAT) + "\nलिखकर चैट, जवाब, Google search, गहरी सोच, कहानी", "brain")
        menuRow("brain_coder", "कोडिंग एजेंट", scopeStatus(ApiScopes.CODER) + "\nकोडिंग टीम: बिल्डर, रिव्यूअर, कोऑर्डिनेटर", "coder")
        menuRow("brain_pc", "DK कंप्यूटर", scopeStatus(ApiScopes.PC) + "\nDK कंप्यूटर के काम और उसकी web search", "computer")
    }

    /** DK लाइव: सिर्फ़ Google Gemini (Live + बोलना + सुनना) और सुनने के लिए चाहें तो Groq */
    private fun secBrainLive() {
        card("DK लाइव — Google Gemini", "Gemini Live बातचीत, Gemini नेचुरल आवाज़ और सुनना — सिर्फ़ इसी key से") { box ->
            val ed = field(box, "Gemini API Key (DK लाइव की अपनी)", Prefs.scopeKey(this, ApiScopes.LIVE, "gemini"), "AIza… यहाँ पेस्ट करें", secret = true)
            keyFields[ApiScopes.pref(ApiScopes.LIVE, "key", "gemini")] = ed
            val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            row(box, showButton(ed), button("टेस्ट करें") {
                save(false); res.text = "टेस्ट हो रहा है…"
                lifecycleScope.launch { res.text = BrainManager(this@SettingsActivity).test(Providers.byId("gemini")!!, ApiScopes.LIVE) }
            }, button("Key लें") { Setup.openUrl(this, Providers.byId("gemini")!!.keyUrl) })
            box.addView(res)
            hint(box, "Limit खत्म हो तो इसी key के दूसरे Gemini Live / आवाज़ मॉडल पर अपने-आप। DK चैट या किसी और हिस्से की key यहाँ इस्तेमाल नहीं होती।")
        }
        card("लिखना (\"आप:\" वाली लाइन) — अपनी अलग Gemini key", "आप जो बोलते हैं उसे लिखने वाला (gemini-3.5-transcribe-live) इसी key से चलेगा, ताकि बोलने वाले Live की free limit इसमें खर्च न हो") { box ->
            val ed = field(box, "Gemini API Key (सिर्फ़ लिखने के लिए)", Prefs.captionOwnKey(this), "AIza… (ज़रूरी नहीं — दूसरे Google account की key बेहतर)", secret = true)
            keyFields[Prefs.CAPTION_KEY] = ed
            val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            row(box, showButton(ed), button("जाँचें") {
                val k = ed.text.toString().trim()
                if (k.isBlank()) { res.text = "खाली है — लिखना ऊपर वाली DK लाइव key से होगा"; return@button }
                if (ApiScopes.detect(k).let { it != null && it != "gemini" }) { res.text = "यह Gemini की key नहीं लगती (AIza… से शुरू होती है)"; return@button }
                res.text = "जाँच रहा हूँ…"
                lifecycleScope.launch {
                    res.text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { checkCaptionKey(k) }
                    save(false)
                }
            }, button("Key लें") { Setup.openUrl(this, Providers.byId("gemini")!!.keyUrl) })
            box.addView(res)
            hint(box, "अभी: " + (if (Prefs.captionOwnKey(this).isNotBlank()) "लिखना अपनी अलग key से" else "लिखना DK लाइव वाली key से (अलग key नहीं डाली)") +
                ". एक ही Google account की दो keys की limit अक्सर साझा होती है — limit सच में बचानी हो तो दूसरे account की key डालें।")
            val lat = Prefs.liveLatency(this)
            if (lat.isNotEmpty()) hint(box, "बोलने वाले मॉडल — नापी गई रफ़्तार (सबसे तेज़ अपने-आप पहले):\n" +
                LiveModelRank.describe(LiveModelRank.order(lat.keys.toList(), emptyList(), lat), lat))
        }
        card("सुनना (आवाज़ → text) — Groq, चाहें तो", "Groq Whisper सबसे तेज़ सुनता है। खाली छोड़ें = ऊपर वाली Gemini key से सुनेगा") { box ->
            val ed = field(box, "Groq API Key (सिर्फ़ सुनने के लिए)", Prefs.scopeKey(this, ApiScopes.LIVE, "groq"), "gsk_… (ज़रूरी नहीं)", secret = true)
            keyFields[ApiScopes.pref(ApiScopes.LIVE, "key", "groq")] = ed
            val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            row(box, showButton(ed), button("जाँचें") {
                val k = ed.text.toString().trim()
                if (k.isBlank()) { res.text = "खाली है — सुनना Gemini key से होगा"; return@button }
                if (ApiScopes.detect(k).let { it != null && it != "groq" }) { res.text = "यह Groq की key नहीं लगती — Gemini key ऊपर वाले खाने में डालें"; return@button }
                res.text = "जाँच रहा हूँ…"
                lifecycleScope.launch {
                    val r = KeySetup.setupVoice(this@SettingsActivity, k)
                    res.text = r.msg
                    if (r.ok) DKAccessibilityService.instance?.startWakeLoopIfEnabled()
                }
            }, button("Groq key लें") { Setup.openUrl(this, "https://console.groq.com/keys") })
            box.addView(res)
            hint(box, "अभी: " + when {
                Prefs.scopeKey(this, ApiScopes.LIVE, "groq").isNotBlank() -> "Groq Whisper से सुन रहा है"
                Prefs.scopeKey(this, ApiScopes.LIVE, "gemini").isNotBlank() -> "Gemini से सुन रहा है"
                else -> "Google माइक से सुन रहा है (फ्री, बीप आ सकती है)"
            } + " • बिना-Live आवाज़ मोड में जवाब का text DK चैट की API से बनता है।")
        }
    }

    /** v3.41: लिखने वाली key सच में चलती है? (models list + transcribe मॉडल) */
    private fun checkCaptionKey(k: String): String = try {
        val c = okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000").header("x-goog-api-key", k).build()
        c.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            when {
                r.code == 400 || r.code == 401 || r.code == 403 -> "✗ key गलत है या बंद है"
                r.code == 429 -> "✗ इस key की limit अभी खत्म है"
                !r.isSuccessful -> "✗ Google से जवाब नहीं (${r.code})"
                body.contains("transcribe") -> "✓ key सही है — लिखने वाला मॉडल मिला। DK लाइव दोबारा शुरू करें"
                else -> "✓ key सही है (लिखने वाला मॉडल list में नहीं दिखा, फिर भी आज़माएगा)"
            }
        }
    } catch (e: Exception) { "✗ जाँच नहीं हो पाई: ${e.message}" }

    private fun showButton(key: EditText) = button("दिखाओ") { v ->
        val show = key.inputType != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        key.inputType = if (show) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        key.setSelection(key.text.length)
        (v as Button).text = if (show) "छुपाओ" else "दिखाओ"
    }

    /** कोडिंग एजेंट / DK कंप्यूटर की अपनी API: मुख्य कंपनी चुनें + हर कंपनी का अपना key कार्ड */
    private val scopeRadios = mutableMapOf<String, RadioGroup>()
    private fun scopeRadioBase(scope: String) = if (scope == ApiScopes.CODER) 6000 else 6100

    private fun secBrainScope(scope: String) {
        card("${ApiScopes.title(scope)} की AI", "${ApiScopes.title(scope)} सिर्फ़ यहाँ डाली key से चलेगा — DK चैट या किसी और की key नहीं") { box ->
            val cur = Prefs.scopeActive(this, scope)
            val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            Providers.all.forEachIndexed { i, p ->
                rg.addView(RadioButton(this).apply {
                    id = scopeRadioBase(scope) + i
                    text = p.name + (if (p.free) "   • फ्री" else "   • पेड") + if (Prefs.scopeKey(this@SettingsActivity, scope, p.id).isBlank()) "   • key नहीं" else "   • ✓ key"
                    setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                    isChecked = cur == p.id
                    setPadding((6 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
                })
            }
            box.addView(rg); scopeRadios[scope] = rg
            hint(box, "जो कंपनी चुनेंगे, सिर्फ़ वही कॉल होगी। उसके किसी मॉडल की limit खत्म हो तो उसी key के दूसरे फ्री मॉडल पर अपने-आप।")
        }
        Providers.all.forEach { p -> providerCard(p, scope) }
    }

    private fun secBrain() {
        quickSetupCard()
        card("DK चैट की AI", "चैट, आम जवाब, बिना-Live आवाज़ मोड का जवाब, search, गहरी सोच और कहानी — सिर्फ़ इसी की key से") { box ->
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
            hint(box, "सिर्फ़ चुनी हुई कंपनी कॉल होगी। उसके किसी मॉडल की limit खत्म हो तो उसी key के दूसरे फ्री मॉडल पर अपने-आप — दूसरी कंपनी पर नहीं।")
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
            val edText = field(box, "API key", "", "Gemini / Groq / OpenRouter — कोई भी", secret = true)
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
                        keyFields[ApiScopes.pref(ApiScopes.CHAT, "key", r.provider)]?.setText(Prefs.key(this@SettingsActivity, r.provider))
                        modelFields[ApiScopes.pref(ApiScopes.CHAT, "model", r.provider)]?.setText(r.model)
                        fetchedFor[ApiScopes.pref(ApiScopes.CHAT, "key", r.provider)] = Prefs.key(this@SettingsActivity, r.provider)
                        Providers.all.indexOfFirst { it.id == r.provider }.takeIf { it >= 0 }?.let { rgProvider.check(1000 + it) }
                        save(false)
                        startTalking()
                    }
                }
            }, button("Groq key लें") { Setup.openUrl(this, "https://console.groq.com/keys") })
            box.addView(res1)

            hint(box, "यह key DK चैट में लगती है, और DK लाइव / कोडिंग एजेंट / DK कंप्यूटर में भी — पर सिर्फ़ वहाँ जहाँ अभी उस कंपनी की key खाली है। " +
                "अलग key डालनी हो तो AI दिमाग → उस हिस्से में डालें। सिर्फ़ फ्री मॉडल चुने जाते हैं।")
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
                "auto" to "सबसे तेज़ — Gemini Live, आवाज़ से आवाज़ (DK लाइव की Gemini key से); न चले तो बिना बीप",
                "classic" to "बिना बीप — DK खुद सुनेगा (DK लाइव की Groq/Gemini key) + जवाब DK चैट की API से",
                "google" to "Google माइक — बिना key, पर हर बार बीप आ सकती है"),
                Prefs.listenEngine(this), 2200)
            hint(box, "बिना बीप वाले मोड में सबसे तेज़ और सही हिंदी पहचान के लिए Groq की फ्री key डालें (Whisper)। Groq key न हो तो Gemini key से काम चलेगा।")
            switch(box, "बोलते समय पीछे का गाना/वीडियो धीमा करो (करीब 20%), जवाब के बाद धीरे-धीरे वापस", Prefs.duckMedia(this))
                .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("duck_media", on) }; if (!on) Ducker.releaseNow() }
            hint(box, "आवाज़ धीरे से (fade) कम-ज़्यादा होती है। फ़ोन की volume नहीं बदलती। कुछ ऐप (जैसे podcast) धीमे होने की जगह थोड़ी देर रुक जाते हैं।")
            hint(box, "आवाज़ की keys (Gemini Live, बोलना, सुनना): सेटिंग्स → AI दिमाग → DK लाइव। अभी: " + scopeStatus(ApiScopes.LIVE))
        }

        // ---- v3.45: सच्चा दोस्त
        card("सच्चा दोस्त", "DK करीबी दोस्त की तरह अपनेपन से बात करे, पिछली बातें याद रखे") { box ->
            switch(box, "दोस्त वाला अंदाज़ + पिछली बातचीत की याद (आखिरी मुलाकात, सारांश, अधूरी बातें)", Prefs.friendMode(this))
                .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("friend_mode", on) } }
            switch(box, "ऐप खोलते ही DK खुद बात शुरू करे (अभिवादन, हालचाल, त्योहार, मौसम)", Prefs.friendOpener(this))
                .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("friend_opener", on) } }
            hint(box, "ऐप खुलने पर DK लाइव अपने-आप चालू होकर पहले बोलेगा (20 मिनट में दोबारा नहीं)। मौसम और इलाके की बात के लिए हर बार ताज़ा लोकेशन लेता है — लोकेशन कहीं सेव नहीं होती। बातचीत की याद: सेटिंग्स → मेमोरी।")
        }

    }

    // ------------------------------------------------------------ v3.58 (Part 11): About + निजता
    private fun secAbout() {
        card(AssistantBrand.FULL_NAME, AssistantBrand.TAGLINE) { box ->
            hint(box, "Version: " + Updater.displayVersion(this))
            box.addView(TextView(this).apply {
                text = AssistantBrand.CREATED_BY; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
                setPadding((4 * dp).toInt(), (14 * dp).toInt(), 0, (4 * dp).toInt())
            })
            hint(box, "असिस्टेंट का नाम: " + Prefs.assistantName(this) + " (बदलने के लिए सेटिंग्स → प्रोफ़ाइल)")
        }
        card("निजता (Privacy)", "आपका डेटा कहाँ जाता है") { box ->
            hint(box, "• आपकी API keys और सेटिंग्स सिर्फ़ इस फ़ोन में सेव रहती हैं (फ़ोन के backup में भी नहीं जातीं)। हर key सिर्फ़ अपनी सेवा को भेजी जाती है, और आपका सवाल उसी AI सेवा को जाता है जिसकी key आपने डाली है।")
            hint(box, "• स्क्रीन देखना चालू हो तभी स्क्रीनशॉट AI को जाता है (सेटिंग्स → परमिशन)।")
            hint(box, "• Notification सिर्फ़ फ़ोन की memory में रहते हैं।")
            hint(box, "• मौसम/इलाके के लिए ली गई लोकेशन कहीं सेव नहीं होती।")
            hint(box, "• कैमरा permission सिर्फ़ टॉर्च के लिए है; DK फ़ोटो नहीं लेता।")
            hint(box, "• Instagram / Facebook / WhatsApp पर पोस्ट या मैसेज भेजने से पहले DK आपसे \"हाँ\" पूछता है।")
        }
        card("और विकल्प", null) { box ->
            row(box,
                button("परिचय दोबारा देखें") { startActivity(android.content.Intent(this, com.dk.assistant.onboard.OnboardingActivity::class.java)) },
                button("परमिशन") { startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", "perms")) })
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
            hint(box, "3D रोशनी का गोला: Android 13 या नए फ़ोन पर असली रोशनी और चमक वाला गोला (फ़ोन झुकाओ तो रोशनी घूमे)। पुराने फ़ोन पर पृथ्वी वाला ओर्ब दिखेगा।")
        }
        card("किनारों की चमक", "DK सुन या बोल रहा हो तो स्क्रीन के किनारे चमकें") { box ->
            switch(box, "सुनते समय बैंगनी, बोलते समय गुलाबी चमक", Prefs.edgeGlow(this))
                .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("edge_glow", on) } }
            hint(box, "चमक छूने पर कुछ नहीं करती — नीचे का ऐप वैसे ही चलता है। फ़ोन में animation बंद हों तो चमक स्थिर रहती है।")
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
                text = cmd; textSize = 12f; setTextColor(Glass.ACCENT); setTextIsSelectable(true)
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
            row(box, button("सीधी कॉल + फ़ोटो") {
                androidx.core.app.ActivityCompat.requestPermissions(this, PhoneActions.EXTRA_PERMS, 14)
            })
            row(box, button("ऐप इंस्टॉल (अपडेट के लिए)") { Updater.askInstallPermission(this) },
                button("App info") { Setup.openAppInfo(this) })
            row(box, button("टॉर्च की permission दें") {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 13)
            })
            hint(box, "कैमरा permission यहाँ सिर्फ़ टॉर्च चालू/बंद करने के लिए है; DK फ़ोटो नहीं लेता।")
            row(box, button("Notification पढ़ने की इजाज़त") { DKNotifListener.openSettings(this) })
            hint(box, "इससे DK लाइव चलते समय आए notification बता सकता है (\"क्या notification आया?\") और आपके कहने पर उनका reply भेज सकता है। notification सिर्फ़ फ़ोन की memory में रहते हैं।")
        }
        refreshPerms()
    }

    private fun refreshPerms() {
        fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notif = android.os.Build.VERSION.SDK_INT < 33 || ok(android.Manifest.permission.POST_NOTIFICATIONS)
        val calls = CallManager.PERMS.all { ok(it) }
        val camera = ok(android.Manifest.permission.CAMERA)
        permStatus?.text = (if (Setup.hasMic(this)) "✓" else "✗") + " माइक\n" +
            (if (notif) "✓" else "✗") + " नोटिफ़िकेशन\n" +
            (if (calls) "✓" else "✗") + " कॉल / कॉन्टैक्ट\n" +
            (if (camera) "✓" else "✗") + " टॉर्च (कैमरा permission)\n" +
            (if (DKNotifListener.enabled(this)) "✓" else "✗") + " notification पढ़ना / reply\n" +
            (if (ok(PhoneActions.CALL_PERM)) "✓" else "✗") + " सीधी कॉल (न हो तो डायलर खुलता है)\n" +
            (if (ok(PhoneActions.PHOTO_PERM)) "✓" else "✗") + " फ़ोटो (सबसे नई फ़ोटो खोलना)\n" +
            (if (Updater.canInstall(this)) "✓" else "✗") + " ऐप इंस्टॉल (अपडेट)"
    }

    override fun onResume() {
        super.onResume()
        refreshAccess(); refreshPerms()
        if (section == null && container.childCount > 0) { container.removeAllViews(); menu(); space(container, 8) }
        if (section == "brain" && container.childCount > 0) { container.removeAllViews(); secBrainMenu(); space(container, 8) }
    }

    /** एक कंपनी का key कार्ड - [scope] = किस हिस्से की key (हर हिस्से की अलग setting) */
    private fun providerCard(p: Provider, scope: String = ApiScopes.CHAT) {
        val kName = ApiScopes.pref(scope, "key", p.id); val mName = ApiScopes.pref(scope, "model", p.id); val bName = ApiScopes.pref(scope, "base", p.id)
        card(p.name + if (scope == ApiScopes.CHAT) "" else " — ${ApiScopes.title(scope)}", p.note) { box ->
            if (p.isCustom) {
                baseFields[bName] = field(box, "Base URL", Prefs.scopeBase(this, scope, p.id), "https://.../v1")
            }
            val key = field(box, "API Key", Prefs.scopeKey(this, scope, p.id), "यहाँ key पेस्ट करें", secret = true)
            keyFields[kName] = key
            val model = field(box, "मॉडल", Prefs.scopeModel(this, scope, p.id), p.defaultModel.ifBlank { "model-name" })
            modelFields[mName] = model
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
                val base = baseFields[bName]?.text?.toString()?.trim()?.ifBlank { null } ?: Prefs.scopeBase(this, scope, p.id)
                if (base.isBlank() && !p.isGemini) { mInfo.text = "पहले Base URL डालें"; return }
                mInfo.text = "मॉडल लोड हो रहे हैं…"
                lifecycleScope.launch {
                    try {
                        val list = ModelLister.fetch(p, k, base)
                        fetchedFor[kName] = k
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
                    if (k.length >= 20 && fetchedFor[kName] != k) {
                        pendingFetch = Runnable { save(false); loadModels(true) }.also { h.postDelayed(it, 1200) }
                    }
                }
            })
            row(box,
                button("मॉडल चुनें") {
                    val cached = Prefs.models(this, p.id)
                    if (cached.isNotEmpty() && fetchedFor[kName] == key.text.toString().trim()) pickModel(p, model, cached)
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
                lifecycleScope.launch { result.text = BrainManager(this@SettingsActivity).test(p, scope) }
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
        UiDialog(this, Glass.DIALOG)
            .setTitle("${p.name} — मॉडल चुनें (${items.size})")
            .setSingleChoiceItems(items, sel) { d, which ->
                target.setText(items[which]); save(false)
                toast("मॉडल: ${items[which]}"); d.dismiss()
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    // ------------------------------------------------------------ DK कंप्यूटर
    private lateinit var swPc: SwitchCompat
    private lateinit var swPcConfirm: SwitchCompat
    private lateinit var rgPcSearch: RadioGroup
    private lateinit var edTavily: EditText
    private lateinit var edBrave: EditText
    private lateinit var edPcSteps: EditText
    private val pcTools = mutableMapOf<String, SwitchCompat>()
    private val pcSearchIds = listOf("auto", "tavily", "brave", "gemini")

    private fun secComputer() {
        card("DK कंप्यूटर", "कोई भी काम कदम-दर-कदम: इंटरनेट पर खोज, वेबसाइट पढ़ना, हिसाब (कोड), फ़ाइल/PDF, फ़ोन के काम, कोडिंग, रिमाइंडर") { box ->
            swPc = switch(box, "DK कंप्यूटर चालू करें", Prefs.computerOn(this))
            swPcConfirm = switch(box, "मैसेज, कॉल, पेमेंट, डिलीट जैसे फ़ोन के काम से पहले मुझसे पूछो", Prefs.computerConfirm(this))
            edPcSteps = field(box, "एक काम में ज़्यादा से ज़्यादा कदम (5–60)", Prefs.computerMaxSteps(this).toString(), "40")
            edPcSteps.inputType = InputType.TYPE_CLASS_NUMBER
            row(box, button("कंप्यूटर खोलें") {
                save(false)
                if (!Prefs.computerOn(this)) { toast("पहले \"DK कंप्यूटर चालू करें\" ON करें"); return@button }
                startActivity(android.content.Intent(this, ComputerActivity::class.java))
            })
        }
        apiLinkCard(ApiScopes.PC, "brain_pc")
        card("वेब search", "Gemini key से Google search फ्री; Tavily / Brave की key डालें तो उनसे (Groq/OpenRouter वालों के लिए भी)") { box ->
            val cur = Prefs.computerSearch(this)
            val names = mapOf("auto" to "अपने-आप (Tavily → Brave → Gemini, जो key हो)", "tavily" to "Tavily", "brave" to "Brave Search", "gemini" to "Google (Gemini key से)")
            rgPcSearch = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            pcSearchIds.forEachIndexed { i, id ->
                rgPcSearch.addView(RadioButton(this).apply {
                    this.id = 5100 + i; text = names[id]; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                    isChecked = cur == id; setPadding((6 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
                })
            }
            box.addView(rgPcSearch)
            edTavily = field(box, "Tavily API Key", Prefs.tavilyKey(this), "tvly-…", secret = true)
            row(box, button("Tavily key बनाएँ") { Setup.openUrl(this, "https://app.tavily.com/home") })
            edBrave = field(box, "Brave Search API Key", Prefs.braveKey(this), "BSA…", secret = true)
            row(box, button("Brave key बनाएँ") { Setup.openUrl(this, "https://api-dashboard.search.brave.com/app/keys") })
            hint(box, "Keys सिर्फ़ इसी फ़ोन में रहती हैं। एक search fail हो तो DK अपने-आप अगला आज़माता है।")
        }
        card("कंप्यूटर के टूल", "जो बंद करेंगे, वह DK कंप्यूटर इस्तेमाल नहीं करेगा") { box ->
            val names = mapOf("web_search" to "इंटरनेट पर खोज", "read_url" to "वेबसाइट पढ़ना", "run_js" to "कोड चलाना (हिसाब/डेटा)",
                "write_file" to "फ़ाइल बनाना", "make_pdf" to "PDF बनाना", "read_file" to "अपनी फ़ाइलें पढ़ना", "list_files" to "फ़ाइलों की लिस्ट",
                "phone_task" to "फ़ोन कंट्रोल (ऐप, मैसेज, कॉल)", "coding_task" to "कोडिंग टीम को काम देना", "remember" to "बातें याद रखना",
                "schedule" to "बाद में काम / रिमाइंडर")
            pcTools.clear()
            ComputerAgent.TOOL_IDS.forEach { id -> pcTools[id] = switch(box, names[id] ?: id, Prefs.computerTool(this, id)) }
        }
        card("तय किए काम", "रिमाइंडर और बाद में चलने वाले काम — फ़ोन restart होने पर भी याद रहते हैं") { box ->
            val jobs = ComputerJobs.all(this)
            val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("hi", "IN"))
            hint(box, if (jobs.isEmpty()) "अभी कोई काम तय नहीं है। बोलिए: \"डीके कंप्यूटर, 30 मिनट बाद मुझे पानी पीने की याद दिलाना\""
                else jobs.take(10).joinToString("\n") { "⏰ " + fmt.format(java.util.Date(it.at)) + " — " + it.task.take(70) } +
                    if (jobs.size > 10) "\n…और ${jobs.size - 10}" else "")
            if (jobs.isNotEmpty()) row(box, button("सारे तय काम रद्द करें") {
                val n = ComputerJobs.cancelAll(this); toast("$n काम रद्द किए"); recreate()
            })
            hint(box, "कुछ फ़ोन (Xiaomi, Oppo, Vivo, Realme) में Settings → Apps → DK → Autostart चालू करना पड़ता है, तभी restart के बाद काम चलते हैं।")
        }
    }

    // ------------------------------------------------------------ coding agent + GitHub
    private lateinit var swCoder: SwitchCompat
    private lateinit var swAutoPush: SwitchCompat
    private lateinit var edCoderSteps: EditText
    private lateinit var swBuildFix: SwitchCompat
    private lateinit var swTeam: SwitchCompat
    private lateinit var swSeven: SwitchCompat
    private lateinit var edGhToken: EditText
    private lateinit var edGhRepo: EditText
    private lateinit var edGhBranch: EditText

    private fun secCoder() {
        card("कोडिंग एजेंट", "आपके GitHub repo में कोड लिखता, बदलता, हटाता और push करता है") { box ->
            swCoder = switch(box, "कोडिंग एजेंट चालू करें", Prefs.coderOn(this))
            hint(box, "बंद रहने पर यह कुछ नहीं करता। चालू करके नीचे GitHub और AI भरें, फिर \"एजेंट खोलें\" दबाएँ।")
            swTeam = switch(box, "मल्टी-एजेंट टीम: बिल्डर → रिव्यूअर → कोऑर्डिनेटर (एक बार बोलो, बाकी सब अपने-आप)", Prefs.coderTeam(this))
            hint(box, "चालू: बिल्डर कोड लिखता है → रिव्यूअर खुद जाँचता है (बग/गलती/लॉजिक, ब्रैकेट/XML) → गड़बड़ी हो तो बिल्डर को वापस → सही होने पर अपने-आप push → GitHub build देखकर fail हो तो फिर ठीक → पूरा होने पर बताता है। " +
                "कोई अतिरिक्त चीज़ ज़रूरी लगे तो टीम खुद नहीं जोड़ती — आपसे पूछती है (हाँ/नहीं)। आवाज़ से: \"कोडिंग एजेंट, … बनाओ\"। बंद: पुराना तरीका (एक एजेंट, आप push दबाएँ)।")
            swSeven = switch(box, "11 एजेंट वाला नया सिस्टम: मैनेजर → प्रॉम्प्ट इंजीनियर (समझ + आपकी हाँ) → गिट → प्लानर → कोडर → रिव्यूअर → परफॉरमेंस → टेस्टर → डिबगर → QA → डिप्लॉयमेंट", Prefs.coderSeven(this))
            hint(box, "चालू: GitHub token + चुना रिपो = GitHub मोड (backup, नई branch, PR, मंज़ूरी से merge); token/रिपो नहीं = नॉर्मल मोड (कोड फ़ाइल-नाम के साथ स्क्रीन पर)। " +
                "हर बदलाव FIND/REPLACE में, लागू होने से पहले रिव्यूअर जाँचता है। बंद: पुराना एजेंट/टीम जैसी की तैसी।")
            row(box, button("एजेंट खोलें") {
                save(false)
                if (!Prefs.coderOn(this)) { toast("पहले \"कोडिंग एजेंट चालू करें\" ON करें"); return@button }
                startActivity(android.content.Intent(this, CodingActivity::class.java))
            })
        }
        card("GitHub कनेक्टर", "Personal Access Token से जुड़ता है (फ़ोन में ही रहता है)") { box ->
            edGhToken = field(box, "GitHub Token", Prefs.ghToken(this), "github_pat_… या ghp_…", secret = true)
            edGhRepo = field(box, "Repo (owner/naam)", Prefs.ghRepo(this), "जैसे: username/my-repo")
            edGhBranch = field(box, "Branch", Prefs.ghBranch(this), "main")
            val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            row(box,
                button("जाँचें") {
                    save(false)
                    val gh = GitHub.fromPrefs(this) ?: run { res.text = "पहले token डालें"; return@button }
                    res.text = "जाँच रहा हूँ…"
                    lifecycleScope.launch {
                        res.text = try {
                            val (who, br) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val w = gh.me()
                                val repo = Prefs.ghRepo(this@SettingsActivity)
                                w to (if (repo.contains('/')) gh.defaultBranch(repo) else "")
                            }
                            "✓ जुड़ गया: $who" + if (br.isNotBlank()) " • repo मिला (मुख्य branch: $br)" else " • अब repo चुनें"
                        } catch (e: Exception) { "✗ " + (e.message ?: "गलती").take(160) }
                    }
                },
                button("रिपो चुनें") {
                    save(false)
                    val gh = GitHub.fromPrefs(this) ?: run { res.text = "पहले token डालें"; return@button }
                    res.text = "Repos ला रहा हूँ…"
                    lifecycleScope.launch {
                        try {
                            val repos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gh.repos() }
                            res.text = "${repos.size} repo मिले"
                            if (isFinishing || isDestroyed || repos.isEmpty()) return@launch
                            UiDialog(this@SettingsActivity, Glass.DIALOG)
                                .setTitle("Repo चुनें")
                                .setItems(repos.map { it.fullName + if (it.private) "  (private)" else "" }.toTypedArray()) { _, i ->
                                    edGhRepo.setText(repos[i].fullName); edGhBranch.setText(repos[i].branch); save(false)
                                }
                                .setNegativeButton("बंद करें", null).show()
                        } catch (e: Exception) { res.text = "✗ " + (e.message ?: "गलती").take(160) }
                    }
                },
                button("Token बनाएँ") { Setup.openUrl(this, GitHub.TOKEN_URL) })
            box.addView(res)
            hint(box, "Token बनाते समय: Repository access → अपना repo चुनें • Permissions → Contents: Read and write, Pull requests: Read and write (सुरक्षित PR मोड), और Actions: Read (build/tests जाँचने के लिए)।")
            swAutoPush = switch(box, "बिना पूछे push करो (बंद = पहले बदलाव दिखाओ, फिर \"Push करें\" दबाने पर)", Prefs.coderAutoPush(this))
            edCoderSteps = field(box, "एक काम में ज़्यादा से ज़्यादा कदम (3–30)", Prefs.coderMaxSteps(this).toString(),
                "5 (सुझाया) — कम कदम = कम API खर्च, limit/413 कम").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            swBuildFix = switch(box, "Push के बाद tests + build जाँचो, fail हो तो पहले जड़ ढूँढकर खुद ठीक करो (पास होने तक)", Prefs.coderBuildFix(this))
            hint(box, "🔒 सुरक्षित PR मोड हमेशा चालू: हर काम पहले main से नई feature/dk-… branch में, फिर Pull Request; सारे पुराने + नए unit tests और build पास होने के बाद " +
                "आपकी मंज़ूरी (\"Merge करें\" / \"PR merge कर दो\") से ही main में जाता है। पुराना फ़ीचर बदलने/हटाने से पहले backup/dk-… branch बनती है। " +
                "Token में Pull requests: Read and write दें; Actions: Read and write देंगे तो GitHub की नेटवर्क दिक्कत पर build अपने-आप दोबारा चलेगा।")
        }
        apiLinkCard(ApiScopes.CODER, "brain_coder")
    }

    /** कोडिंग / कंप्यूटर स्क्रीन पर: उनकी API अब AI दिमाग में उनके अपने विकल्प में */
    private fun apiLinkCard(scope: String, section: String) {
        card("${ApiScopes.title(scope)} की AI", "API key अब AI दिमाग → ${ApiScopes.title(scope)} में — सिर्फ़ इसी की अपनी") { box ->
            hint(box, "अभी: " + scopeStatus(scope))
            row(box, button("${ApiScopes.title(scope)} की API खोलें") {
                save(false)
                startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", section))
            })
        }
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
                putBoolean("web_answers", swWeb.isChecked)
            }
            if (::swWake.isInitialized) {
                putBoolean("wake_mode", swWake.isChecked)
                // v3.59: "हमेशा सुनो" अभी-अभी चालू किया = आपने खुद माइक चालू करना चुना
                if (swWake.isChecked && !Prefs.wakeMode(this@SettingsActivity)) putBoolean("mic_user_off", false)
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
            if (::swCoder.isInitialized) {
                putBoolean("coder_on", swCoder.isChecked)
                putBoolean("coder_autopush", swAutoPush.isChecked)
                putString("coder_max_steps", (edCoderSteps.text.toString().trim().toIntOrNull() ?: CodingAgent.DEFAULT_STEPS).coerceIn(3, 30).toString())
                putBoolean("coder_buildfix", swBuildFix.isChecked)
                putBoolean("coder_team", swTeam.isChecked)
                putBoolean("coder_seven", swSeven.isChecked)
                putString("gh_token", edGhToken.text.toString().trim())
                putString("gh_repo", edGhRepo.text.toString().trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/'))
                putString("gh_branch", edGhBranch.text.toString().trim())
            }
            if (::swPc.isInitialized) {
                putBoolean("pc_on", swPc.isChecked)
                putBoolean("pc_confirm", swPcConfirm.isChecked)
                putInt("pc_max_steps", (edPcSteps.text.toString().trim().toIntOrNull() ?: 25).coerceIn(5, 60))
                putString("pc_search", pcSearchIds.getOrNull(rgPcSearch.checkedRadioButtonId - 5100) ?: "auto")
                putString("key_tavily", edTavily.text.toString().trim())
                putString("key_brave", edBrave.text.toString().trim())
                pcTools.forEach { (id, sw) -> putBoolean("pc_tool_$id", sw.isChecked) }
            }
            // हर हिस्से की अपनी setting (नाम ApiScopes.pref से: key_gemini, coder_key_groq, live_key_gemini …)
            keyFields.forEach { (name, e) -> putString(name, e.text.toString().trim()) }
            modelFields.forEach { (name, e) -> putString(name, e.text.toString().trim()) }
            baseFields.forEach { (name, e) -> putString(name, e.text.toString().trim()) }
            scopeRadios.forEach { (scope, rg) ->
                Providers.all.getOrNull(rg.checkedRadioButtonId - scopeRadioBase(scope))?.let { putString(ApiScopes.activePref(scope), it.id) }
            }
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
    /** Ek samay me ek hi awaaz sunao - jaldi-jaldi tap karo to pichhli rok do (warna purani baad me bajti thi) */
    private var previewJob: kotlinx.coroutines.Job? = null

    private fun preview(name: String) {
        previewJob?.cancel()
        sp().stop()
        val sample = "नमस्ते, मैं ${Prefs.assistantName(this)} हूँ। क्या मैं आपकी मदद करूँ?"
        previewJob = lifecycleScope.launch { sp().previewGemini(name, sample)?.let { toast(it) } }
    }

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
                id = GEM_ID + i
                text = "$name — ${VoiceCatalog.GEMINI_INFO[name].orEmpty()}"
                textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(Glass.ACCENT)
            })
        }
        gemList.check(GEM_ID + list.indexOf(cur).coerceAtLeast(0))
        gemList.setOnCheckedChangeListener { _, id ->
            val name = list.getOrNull(id - GEM_ID) ?: return@setOnCheckedChangeListener
            Prefs.edit(this) { putString("gemini_voice", name) }
            txtVoice.text = geminiVoiceLine()
            toast(if (DKState.live) "चुनी: $name — चालू बातचीत में अगली बार माइक चालू करने पर" else "सुनाई दे रही है: $name …")
            preview(name)
        }
    }

    private fun geminiVoiceLine(): String {
        val g = if (Prefs.voiceGender(this) == "male") "पुरुष" else "महिला"
        return "चुनी हुई आवाज़: ${Prefs.geminiVoice(this)}  •  $g की ${VoiceCatalog.geminiFor(Prefs.voiceGender(this)).size} आवाज़ें — नीचे टैप करके सुनें और चुनें" +
            if (Prefs.ttsGeminiKey(this).isBlank()) "\n(AI दिमाग → DK लाइव में Gemini key नहीं डाली — तब तक Google आवाज़)" else ""
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
            UiDialog(this, Glass.DIALOG)
                .setTitle((if (gender == "male") "पुरुष" else "महिला") + " Gemini आवाज़ चुनें (टैप = सुनें)")
                .setItems(all.map { it.second }.toTypedArray()) { _, i ->
                    val name = all[i].first
                    val g = if (name in VoiceCatalog.GEMINI_MALE) "male" else "female"
                    Prefs.edit(this) { putString("gemini_voice", name); putString("voice_gender", g) }
                    rgGender.check(if (g == "male") 2001 else 2000)
                    Prefs.edit(this) { putString("gemini_voice", name) }
                    updateVoiceText()
                    toast("सुनाई दे रही है: $name …")
                    preview(name)
                }
                .setNegativeButton("बंद करें", null).show()
        } else {
            lifecycleScope.launch {
                val voices = sp().hindiVoices()
                if (voices.isEmpty()) { toast("हिंदी आवाज़ नहीं मिली — TTS सेटिंग से हिंदी डाउनलोड करें"); Setup.openTts(this@SettingsActivity); return@launch }
                val labels = voices.mapIndexed { i, v -> VoiceCatalog.label(v, i) }.toTypedArray()
                UiDialog(this@SettingsActivity, Glass.DIALOG)
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

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}
