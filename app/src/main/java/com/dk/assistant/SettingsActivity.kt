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
 *  Profile (aapka naam, assistant ka naam, baat karne ka tareeka, wake words)
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
    private lateinit var swWake: SwitchCompat
    private lateinit var swShot: SwitchCompat
    private lateinit var swCalls: SwitchCompat
    private lateinit var swWeb: SwitchCompat
    private val keyFields = mutableMapOf<String, EditText>()
    private val modelFields = mutableMapOf<String, EditText>()
    private val baseFields = mutableMapOf<String, EditText>()
    private lateinit var rgListen: RadioGroup
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
            "ui" -> { title.text = "दिखावट"; bar.visibility = View.GONE; secUi() }
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
            Triple("ui", "दिखावट (UI कस्टमाइज़ेशन)", "थीम • मुख्य रंग • काँच की पारदर्शिता • चमक • एनिमेशन"),
            Triple("brain", "AI दिमाग", "DK लाइव • DK चैट • कोडिंग एजेंट • DK कंप्यूटर — हर एक की अपनी API key"),
            Triple("voice", "आवाज़", "बोलने वाला इंजन (Gemini TTS • Edge • Live) • सुनना • सिर्फ़ मेरी आवाज़"),
            Triple("orb", "ओर्ब और चेहरा", "पृथ्वी, गैलेक्सी, एटम, छल्ला, पुरुष/महिला HD चेहरा"),
            Triple("access", "Accessibility", (if (Setup.accessibilityOn(this)) "✓ चालू" else "✗ बंद") + " • फ़ोन कंट्रोल • हमेशा सुनना • कॉल • बैटरी"),
            Triple("perms", "परमिशन", "माइक • स्क्रीन देखना • नोटिफ़िकेशन • कॉल • ऐप इंस्टॉल"),
            Triple("computer", "DK कंप्यूटर", (if (Prefs.computerOn(this)) "✓ चालू" else "✗ बंद") + " • कोई भी काम: खोज, रिसर्च, फ़ाइल/PDF, हिसाब, फ़ोन, रिमाइंडर"),
            Triple("coder", "कोडिंग एजेंट", (if (Prefs.coderOn(this)) "✓ चालू" else "✗ बंद") + " • GitHub से जुड़ें • कोड लिखें/बदलें/हटाएँ • push • अलग AI key"),
            Triple("connectors", "कनेक्टर", "Gmail • Drive • Calendar • GitHub • Facebook Page • Instagram • MCP — DK लाइव से बोलकर काम"),
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
                    else if (id == "connectors") startActivity(android.content.Intent(this@SettingsActivity, com.dk.assistant.connect.ConnectorsActivity::class.java))
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
        "ui" -> R.drawable.ic_s_ui
        "access" -> R.drawable.ic_s_access
        "perms" -> R.drawable.ic_s_perms
        "coder" -> R.drawable.ic_s_coder
        "computer" -> R.drawable.ic_s_computer
        "story" -> R.drawable.ic_s_story
        "connectors" -> R.drawable.ic_s_connect
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
        // v3.71: बोलने की देरी - हर बारी के तीन समय
        card("देरी का हिसाब", "आपका बोलना ख़त्म → पहला टुकड़ा आया → पहले वाक्य की आवाज़ बनी → आवाज़ बजी") { box ->
            val tv = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(0xE6FFFFFF.toInt()); setTextIsSelectable(true) }
            box.addView(tv)
            fun upd() { tv.text = LatencyBoard.text() }
            upd()
            row(box, button("ताज़ा करें") { upd() }, button("साफ़ करें") { LatencyBoard.clear(); upd() })
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
            edPersona = field(it, "बात करने का तरीका (अपनी तरफ़ से)", Prefs.personality(this),
                "जैसे: जवाब छोटे रखो, मुझे सर कहकर बुलाओ", multiLine = true)
            edWake = field(it, "जगाने वाले शब्द (कॉमा से अलग)", Prefs.wakeWordsRaw(this), "डीके, dk")
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

    // ================================================================ v3.66: सिर्फ़ मेरी आवाज़
    private var ownerJob: kotlinx.coroutines.Job? = null

    private fun ownerVoiceCard(box: LinearLayout) {
        val OV = com.dk.assistant.voice.OwnerVoice
        val status = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(status)
        val swOn = switch(box, "सिर्फ़ मेरी आवाज़ पर जवाब दो", Prefs.ownerVoiceOn(this))
        val swStrict = switch(box, "सख़्त पहचान (दूसरे लगभग कभी पास नहीं, पर कभी-कभी आपको दोबारा बोलना पड़ सकता है)", Prefs.ownerVoiceStrict(this))
        val swFar = switch(box, "दूर की / बहुत धीमी आवाज़ अनसुनी करो (दूसरे कमरे की बात, धीमा TV)", Prefs.ownerFarOn(this))
        fun refresh() {
            val enrolled = OV.enrolled(this)
            status.text = UiLang.s(when {
                !OV.supported -> "यह फ़ोन 64-bit ARM नहीं है — आवाज़-पहचान यहाँ नहीं चलेगी, DK पहले की तरह सबकी आवाज़ सुनेगा"
                OV.broken != null -> "पहचान वाला मॉडल इस फ़ोन पर नहीं चला — DK पहले की तरह सबकी आवाज़ सुनेगा"
                !enrolled -> "अभी आपकी आवाज़ नहीं सीखी — नीचे \"मेरी आवाज़ सिखाओ\" दबाएँ (करीब 30 सेकंड)"
                Prefs.ownerVoiceOn(this) -> "चालू — DK सिर्फ़ आपकी आवाज़ पर जवाब देगा"
                else -> "आपकी आवाज़ सीखी हुई है — ऊपर वाला स्विच चालू करें"
            })
            swOn.isEnabled = enrolled
        }
        swOn.setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("owner_voice_on", on && OV.enrolled(this@SettingsActivity)) }; refresh() }
        swStrict.setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("owner_voice_strict", on) } }
        swFar.setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("owner_far_on", on) } }
        refresh()
        row(box,
            button("मेरी आवाज़ सिखाओ") { enrollOwner { swOn.isChecked = Prefs.ownerVoiceOn(this); refresh() } },
            button("जाँचो") { testOwner() })
        row(box, button("पहचान मिटाओ") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("आवाज़ की पहचान मिटाएँ?"))
                .setMessage(UiLang.s("DK फिर से सबकी आवाज़ सुनेगा। दोबारा चालू करने के लिए आवाज़ फिर से सिखानी होगी।"))
                .setPositiveButton(UiLang.s("मिटाओ")) { _, _ -> OV.forget(this); swOn.isChecked = false; refresh(); toast("आवाज़ की पहचान मिटा दी") }
                .setNegativeButton(UiLang.s("रहने दो"), null).show()
        })
        hint(box, "कैसे काम करता है: आप 5 छोटे वाक्य बोलते हैं, फ़ोन उनसे आपकी आवाज़ की पहचान बनाता है। यह पहचान सिर्फ़ इसी फ़ोन में रहती है — इंटरनेट या GitHub पर कहीं नहीं जाती, और आवाज़ सेव नहीं होती। पहली बार पहचान वाला मॉडल (~28 MB) एक बार डाउनलोड होता है।")
        hint(box, "रफ़्तार: आपकी आवाज़ Gemini को उसी पल जाती रहती है, साथ-साथ पहली 1 सेकंड मिलाई जाती है — आपके जवाब में कोई देरी नहीं। किसी और की आवाज़ निकली तो DK न जवाब बोलेगा, न फ़ोन पर कोई काम करेगा।")
        hint(box, "गला ख़राब हो, बहुत शोर हो या फ़ोन बहुत दूर हो तो कभी-कभी आपकी आवाज़ भी छूट सकती है — तब पास आकर फिर बोलें, या \"सख़्त\" बंद रखें। Bluetooth headset पर दूर-पास वाली जाँच अपने-आप बंद रहती है।")
    }

    /** DK का माइक थोड़ी देर बंद (Live/सुनना माइक छोड़े), फिर जैसा था */
    private suspend fun <T> withDkMicPaused(block: suspend () -> T): T {
        val was = DKState.micMuted
        if (!was) { DKState.setMicMuted(true); kotlinx.coroutines.delay(700) }
        return try { block() } finally { if (!was) DKState.setMicMuted(false) }
    }

    private fun ownerDialog(title: String): Pair<androidx.appcompat.app.AlertDialog, TextView> {
        val tv = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); val p = (20 * dp).toInt(); setPadding(p, p, p, p) }
        val d = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s(title)).setView(tv)
            .setNegativeButton(UiLang.s("बंद करें")) { _, _ -> ownerJob?.cancel() }
            .setOnCancelListener { ownerJob?.cancel() }.show()
        return d to tv
    }

    private fun enrollOwner(done: () -> Unit) {
        val OV = com.dk.assistant.voice.OwnerVoice
        if (!OV.supported) { toast("यह फ़ोन 64-bit ARM नहीं है — आवाज़-पहचान यहाँ नहीं चलेगी"); return }
        if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
        val VP = com.dk.assistant.voice.VoicePrint
        val (dlg, tv) = ownerDialog("मेरी आवाज़ सिखाओ")
        ownerJob?.cancel()
        ownerJob = lifecycleScope.launch {
            try {
                if (!OV.hasModel(this@SettingsActivity)) {
                    tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…")
                    val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        OV.download(this@SettingsActivity) { pct -> runOnUiThread { tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…") + " $pct%" } }
                    }
                    if (err != null) { tv.text = UiLang.s(err); return@launch }
                }
                val prints = ArrayList<FloatArray>(); val levels = ArrayList<Double>()
                withDkMicPaused {
                    val phrases = com.dk.assistant.voice.VoiceEnroll.PHRASES
                    var i = 0; var tries = 0
                    while (i < phrases.size) {
                        tv.text = UiLang.s("वाक्य") + " ${i + 1}/${phrases.size} — " + UiLang.s("फ़ोन को आम तौर पर जैसे पकड़ते हैं, वैसे पकड़कर आराम से बोलिए:") + "\n\n\"${phrases[i]}\""
                        val take = com.dk.assistant.voice.VoiceEnroll.record()
                        if (take == null || take.speechMs < VP.MIN_ENROLL_SPEECH_MS) {
                            if (++tries >= 3) { tv.text = UiLang.s("आवाज़ ठीक से नहीं आई — शांत जगह पर फिर कोशिश करें"); return@withDkMicPaused }
                            tv.text = UiLang.s("आवाज़ कम आई — पूरा वाक्य थोड़ा ज़ोर से बोलिए"); kotlinx.coroutines.delay(1200); continue
                        }
                        tv.text = UiLang.s("सीख रहा हूँ…")
                        val e = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { OV.embed(this@SettingsActivity, take.pcm) }
                        if (e == null) { tv.text = UiLang.s("पहचान वाला मॉडल इस फ़ोन पर नहीं चला — DK पहले की तरह सबकी आवाज़ सुनेगा"); return@withDkMicPaused }
                        prints.add(e); if (take.level > 0) levels.add(take.level)
                        i++; tries = 0
                        kotlinx.coroutines.delay(400)
                    }
                }
                if (prints.size < com.dk.assistant.voice.VoicePrint.ENROLL_PHRASES) return@launch
                val cons = VP.consistency(prints)
                if (cons < VP.ENROLL_MIN_CONSISTENCY) {
                    tv.text = UiLang.s("वाक्य आपस में ठीक से नहीं मिले (शायद शोर था या कोई और बोला) — शांत जगह पर फिर से सिखाएँ")
                    return@launch
                }
                val lv = levels.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                OV.saveEnrollment(this@SettingsActivity, VP.mean(prints)!!, lv)
                Prefs.edit(this@SettingsActivity) { putBoolean("owner_voice_on", true) }
                tv.text = UiLang.s("हो गया — DK ने आपकी आवाज़ सीख ली। अब DK सिर्फ़ आपकी आवाज़ पर जवाब देगा।") +
                    "\n\n" + UiLang.s("मिलान की मज़बूती:") + " ${(cons * 100).toInt()}%"
                done()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { tv.text = UiLang.s("गड़बड़ हुई") + " (${e.javaClass.simpleName})" }
        }
        dlg.setOnDismissListener { ownerJob?.cancel() }
    }

    private fun testOwner() {
        val OV = com.dk.assistant.voice.OwnerVoice
        if (!OV.enrolled(this) || !OV.hasModel(this)) { toast("पहले \"मेरी आवाज़ सिखाओ\" दबाएँ"); return }
        if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
        val (dlg, tv) = ownerDialog("आवाज़ की जाँच")
        ownerJob?.cancel()
        ownerJob = lifecycleScope.launch {
            withDkMicPaused {
                tv.text = UiLang.s("कुछ भी बोलिए — या किसी और से बुलवाइए")
                val take = com.dk.assistant.voice.VoiceEnroll.record()
                if (take == null) { tv.text = UiLang.s("आवाज़ नहीं आई"); return@withDkMicPaused }
                val cut = take.pcm.copyOf(minOf(take.pcm.size, 16000 * 2 * 3))
                val sc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { OV.score(this@SettingsActivity, cut) }
                if (sc == null) { tv.text = UiLang.s("पहचान वाला मॉडल इस फ़ोन पर नहीं चला — DK पहले की तरह सबकी आवाज़ सुनेगा"); return@withDkMicPaused }
                val th = com.dk.assistant.voice.VoicePrint.threshold(Prefs.ownerVoiceStrict(this@SettingsActivity))
                tv.text = UiLang.s(if (sc >= th) "यह आपकी आवाज़ है — DK जवाब देगा" else "यह किसी और की आवाज़ लगी — DK अनसुनी करेगा") +
                    "\n\n" + UiLang.s("मिलान:") + " ${(sc * 100).toInt()}% (" + UiLang.s("सीमा") + " ${(th * 100).toInt()}%)"
            }
        }
        dlg.setOnDismissListener { ownerJob?.cancel() }
    }

    private fun secVoice() {
        // v3.69: पुराना "आवाज़" हिस्सा (महिला/पुरुष, आवाज़ का प्रकार, सूची, अंदाज़, स्पीड, पिच, फ़ोन TTS) हटाया -
        // बैकअप: backup/v3.69-settings/. अब सिर्फ़ DK लाइव का बोलने वाला इंजन.
        card("बोलने वाला इंजन (DK Live)", "Gemini TTS • Microsoft Edge TTS • Gemini Live सीधी आवाज़ — एक समय में एक") { box ->
            com.dk.assistant.voice.engine.VoiceEngineCard.build(this, box, dp)
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

        // ---- v3.66: सिर्फ़ मेरी आवाज़ (परत 3) + दूर की आवाज़ अनसुनी (परत 4)
        card("सिर्फ़ मेरी आवाज़ सुनो", "DK सिर्फ़ आपकी आवाज़ पर जवाब दे — पास वालों की बातें, TV या दूर की आवाज़ अनसुनी") { box -> ownerVoiceCard(box) }

        // ---- v3.67: WhatsApp तुरंत भेजो (मालिक की मर्ज़ी, default बंद)
        card("WhatsApp संदेश", "\"राहुल को WhatsApp पर लिखो …\" कहने पर DK खुद भेजता है और भेजने के बाद ही \"भेज दिया\" बोलता है") { box ->
            switch(box, "बिना पूछे तुरंत भेजो (बंद = भेजने से पहले \"भेज दूँ?\" पूछो)", Prefs.waAutoSend(this))
                .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("wa_auto_send", on) } }
            hint(box, "चालू करने पर भी नाम साफ़ न मिले (दो मिलते-जुलते नाम) तो DK पूछेगा। भेजने के बाद DK जाँचता है कि संदेश सच में चैट में गया।")
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

    // ------------------------------------------------------------ v4: दिखावट (UI कस्टमाइज़ेशन) - सिर्फ़ रंग-रूप
    /**
     * थीम, मुख्य रंग, काँच की पारदर्शिता, चमक, एनिमेशन. बदलते ही पूरा ऐप नए रूप में (पीछे खुली स्क्रीनें लौटते ही).
     * यह AI, आवाज़, prompt, API key या बातचीत/यादों को बिल्कुल नहीं छूता - सिर्फ़ ui_* पसंद.
     */
    private fun secUi() {
        val T = com.dk.assistant.ui.NeonTheme
        fun applyLook(l: com.dk.assistant.ui.NeonTheme.Look) {
            T.save(this, l)
            com.dk.assistant.ui.NeonRefresh.bars(this)
            com.dk.assistant.ui.NeonRefresh.invalidateAll(window.decorView)
        }
        card("झलक", "बदलाव यहीं तुरंत दिखता है", startOpen = true) { box ->
            box.addView(OrbView(this).apply { previewStyle = Shapes.NEON; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (170 * dp).toInt()))
            row(box,
                Button(this).apply {
                    text = "मुख्य बटन"; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.btn_accent); isClickable = false
                },
                button("काँच का बटन") {}.apply { isClickable = false })
        }
        card("थीम", "पृष्ठभूमि का रंग", startOpen = true) { box ->
            val g = radios(box, T.THEMES, T.look.theme, 6100)
            g.setOnCheckedChangeListener { _, id ->
                val v = T.THEMES.getOrNull(id - 6100)?.first ?: return@setOnCheckedChangeListener
                applyLook(T.look.copy(theme = v))
            }
            hint(box, "हल्की (Light) थीम अभी नहीं है: ऐप की ज़्यादातर स्क्रीनों पर सफ़ेद अक्षर हैं, हल्की पृष्ठभूमि पर वे पढ़ने में नहीं आते। " +
                "\"फ़ोन जैसी (System)\" भी इसी वजह से नहीं — ऐप हमेशा गहरी थीम में रहता है।")
        }
        card("मुख्य रंग (Accent)", "किनारे, चमक, मुख्य बटन और ओर्ब का रंग", startOpen = true) { box ->
            val items = com.dk.assistant.ui.NeonTheme.Accent.values().map { it.key to it.label }
            val g = radios(box, items, T.look.accent.key, 6200)
            com.dk.assistant.ui.NeonTheme.Accent.values().forEachIndexed { i, ac ->
                (g.getChildAt(i) as? RadioButton)?.let { rb ->
                    val sp = android.text.SpannableString("●  " + UiLang.s(ac.label))
                    sp.setSpan(android.text.style.ForegroundColorSpan(ac.main), 0, 1, 0)
                    rb.text = sp; rb.minHeight = (48 * dp).toInt()
                }
            }
            g.setOnCheckedChangeListener { _, id ->
                val ac = com.dk.assistant.ui.NeonTheme.Accent.values().getOrNull(id - 6200) ?: return@setOnCheckedChangeListener
                applyLook(T.look.copy(accent = ac))
            }
        }
        fun slider(box: LinearLayout, title: String, value: Int, set: (Int) -> com.dk.assistant.ui.NeonTheme.Look) {
            val lab = TextView(this).apply {
                textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
            }
            fun show(v: Int) { lab.text = UiLang.s(title) + ": $v%" }
            show(value); box.addView(lab)
            val sb = SeekBar(this).apply {
                max = 100; progress = value; minimumHeight = (48 * dp).toInt()
                contentDescription = UiLang.s(title)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { show(p) }
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) { applyLook(set(progress)) }
                })
            }
            box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        }
        card("काँच और चमक", "पैनल कितने पारदर्शी हों, किनारों की नियॉन चमक कितनी हो", startOpen = true) { box ->
            slider(box, "काँच की पारदर्शिता", T.look.glass) { T.look.copy(glass = it) }
            slider(box, "नियॉन चमक", T.look.glow) { T.look.copy(glow = it) }
            hint(box, "ज़्यादा पारदर्शिता पर भी अक्षर पढ़ने लायक रहें, इसलिए काँच कभी पूरा गायब नहीं होता। " +
                "पीछे का असली धुंधलापन (blur) Android 12+ पर सिर्फ़ dialog के पीछे; बाकी जगह हल्का काँच-जैसा रंग।")
        }
        card("एनिमेशन", "ओर्ब के छल्ले, पृष्ठभूमि की रोशनी और मेनू का खुलना", startOpen = true) { box ->
            val g = radios(box, T.MOTIONS, T.look.motion, 6300)
            g.setOnCheckedChangeListener { _, id ->
                val v = T.MOTIONS.getOrNull(id - 6300)?.first ?: return@setOnCheckedChangeListener
                applyLook(T.look.copy(motion = v))
            }
            if (T.systemNoAnim) hint(box, "फ़ोन की सेटिंग में एनिमेशन बंद हैं — इसलिए DK अभी अपने-आप कम एनिमेशन में है।")
        }
        card("दिखावट पहले जैसी करें", "सिर्फ़ रंग-रूप शुरुआती जैसा — बातचीत, यादें, key और आवाज़ की सेटिंग नहीं मिटतीं", startOpen = true) { box ->
            row(box, button("दिखावट रीसेट करें") {
                UiDialog(this, Glass.DIALOG)
                    .setTitle("दिखावट पहले जैसी करें?")
                    .setMessage("थीम, मुख्य रंग, काँच, चमक और एनिमेशन शुरुआती हो जाएँगे। आपका कोई डेटा नहीं मिटेगा।")
                    .setPositiveButton("हाँ, रीसेट करें") { _, _ ->
                        T.resetAppearance(this)
                        toast("दिखावट शुरुआती जैसी हो गई")
                        recreate()
                    }
                    .setNegativeButton("नहीं", null)
                    .show()
            })
        }
    }

    // ------------------------------------------------------------ orb + chehra
    private fun secOrb() {
        val styles = Shapes.STYLES
        lateinit var preview: OrbView
        var chosen = Prefs.orbStyle(this)
        // v3.71: ORB या लड़की अवतार
        card("अवतार चुनो", "होम स्क्रीन और आवाज़ स्क्रीन पर ORB की जगह क्या दिखे") { box ->
            val g = radios(box, listOf(
                "orb" to "ORB (डिफ़ॉल्ट)",
                "girl" to "लड़की अवतार — बालों में हवा, पलकें, बोलते समय होंठ, भाव के साथ चेहरा"),
                Prefs.avatarKind(this), 2600)
            g.setOnCheckedChangeListener { _, id ->
                val v = if (id - 2600 == 1) "girl" else "orb"
                Prefs.edit(this) { putString("avatar_kind", v) }
                if (v == "girl" && !com.dk.assistant.avatar.AvatarAssets.available(this))
                    toast("अवतार का चित्र (avatar_body) ऐप में नहीं है — तब तक ORB ही दिखेगा")
            }
            val miss = com.dk.assistant.avatar.AvatarAssets.missingFiles(this)
            hint(box, if (miss.isEmpty()) "अवतार के सारे 11 चित्र मौजूद हैं।"
                else "ऐप में ये चित्र नहीं हैं (res/drawable-nodpi में 1024x1536 PNG डालें): " + miss.joinToString(", ") +
                    (if (miss.contains(com.dk.assistant.avatar.AvatarAssets.BODY)) " — avatar_body के बिना ORB ही दिखेगा।" else " — जो रूप नहीं है उसकी जगह पास वाला रूप दिखेगा।"))
        }
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
                Prefs.edit(this) { putBoolean("screenshot", on); putBoolean(ScreenGuard.USER_OK_KEY, on) }
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
            if (p.isGemini && scope == ApiScopes.CODER) {
                // कोडिंग एजेंट: 3 Gemini keys - एक के सारे मॉडल की limit खत्म = अपने-आप अगली
                keyFields[ApiScopes.pref(scope, "key", "gemini2")] = field(box, "Gemini API Key 2 (बैकअप, ज़रूरी नहीं)",
                    Prefs.scopeKey(this, scope, "gemini2"), "दूसरी Gemini key", secret = true)
                keyFields[ApiScopes.pref(scope, "key", "gemini3")] = field(box, "Gemini API Key 3 (बैकअप, ज़रूरी नहीं)",
                    Prefs.scopeKey(this, scope, "gemini3"), "तीसरी Gemini key", secret = true)
                box.addView(TextView(this).apply {
                    text = "Key 1 के सारे मॉडल (3.8 Flash → 3.6 Flash → 3 Flash → 3.5 Flash-Lite → 3.1 Flash-Lite) की limit खत्म → अपने-आप Key 2 के सारे मॉडल → फिर Key 3। " +
                        "सबकी limit खत्म हो तो काम रुकता नहीं — limit खुलने तक रुककर फिर चलता है। ध्यान दें: Google limit project पर लगाता है, इसलिए हर key अलग Google project की हो।"
                    textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
                })
            }
            val model = field(box, "मॉडल", Prefs.scopeModel(this, scope, p.id), (if (p.isGemini && scope == ApiScopes.CODER) GeminiModels.CODER else p.defaultModel).ifBlank { "model-name" })
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
        if (section == null || section == "orb" || section == "ui") return
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
            if (::swShot.isInitialized) { putBoolean("screenshot", swShot.isChecked); putBoolean(ScreenGuard.USER_OK_KEY, swShot.isChecked) }
            if (::swAutoUpd.isInitialized) putBoolean("auto_update", swAutoUpd.isChecked)
            // v3.69: आवाज़ (महिला/पुरुष, प्रकार, अंदाज़, स्पीड, पिच) की सेटिंग हटी - पहले सेव मान वैसे ही रहते हैं
            if (::rgListen.isInitialized) putString("listen_engine", radioValue(rgListen, listOf("auto", "classic", "google"), 2200))
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

    // ------------------------------------------------------------ small UI helpers
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { txtSaved.text = "बदलाव सेव नहीं हुए — सेव दबाएँ" }
    }

    /** v3.69: हर सेटिंग हिस्सा खुलने-बंद होने वाला कॉलम (साझा: ui/CollapsibleSection) - शुरू में बंद */
    private fun card(title: String, sub: String?, startOpen: Boolean = false, body: (LinearLayout) -> Unit) {
        com.dk.assistant.ui.CollapsibleSection.add(this, container, title, sub, startOpen = startOpen, body = body)
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
