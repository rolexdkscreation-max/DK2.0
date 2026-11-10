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

// फ़ाइलें (नवंबर 2026 में बँटवारा, व्यवहार वही): यहाँ सारी state + onCreate/save + menu + खोज।
// बाकी हिस्से इसी class के extension functions हैं:
//  - SettingsBrain.kt   : AI दिमाग (provider, keys, मॉडल, Live/कोडर/कंप्यूटर की अलग keys)
//  - SettingsVoice.kt   : आवाज़ (Hey DK, बोलने वाला इंजन)
//  - SettingsSystem.kt  : भाषा, डिबग, अपडेट, प्रोफ़ाइल, access key, Accessibility, अनुमतियाँ
//  - SettingsLook.kt    : रूप (थीम, रंग, ORB)
//  - SettingsCoder.kt   : कोडिंग एजेंट और DK कंप्यूटर
//  - SettingsWidgets.kt : card / label / field / switch / button जैसे छोटे हिस्से
// नया हिस्सा बने तो test की SetSrc.PARTS में भी जोड़ें (source-check tests सारे हिस्से पढ़ते हैं)।
/**
 * Settings panel (liquid glass cards):
 *  Profile (aapka naam, assistant ka naam, baat karne ka tareeka, wake words)
 *  AI Brain (kaunsi company, har company ki API key + model + test)
 *  Awaaz (speed, pitch, test), Options, Permissions
 *  Neeche hamesha dikhne wala "सेव करें" button + auto-save.
 */

class SettingsActivity : AppCompatActivity() {

    internal lateinit var container: LinearLayout
    internal var txtSaved: TextView? = null
    internal var speaker: Speaker? = null

    // form fields
    internal lateinit var edUser: EditText
    internal lateinit var edName: EditText
    internal lateinit var edPersona: EditText
    internal lateinit var edWake: EditText
    internal lateinit var rgProvider: RadioGroup
    internal lateinit var swWake: SwitchCompat
    /** lateinit की जाँच class के बाहर (SettingsVoice.kt) से नहीं होती - वही जाँच यहाँ से */
    internal val swWakeReady: Boolean get() = ::swWake.isInitialized
    internal lateinit var swShot: SwitchCompat
    internal lateinit var swCalls: SwitchCompat
    internal lateinit var swDkOnly: SwitchCompat
    internal lateinit var edBusyMsg: EditText
    internal lateinit var swWeb: SwitchCompat
    internal val keyFields = mutableMapOf<String, EditText>()
    internal val modelFields = mutableMapOf<String, EditText>()
    internal val baseFields = mutableMapOf<String, EditText>()
    internal lateinit var rgListen: RadioGroup
    internal val fetchedFor = mutableMapOf<String, String>()   // provider -> key jiske models la chuke

    internal val dp get() = resources.displayMetrics.density

    /** वॉलपेपर: गैलरी से अपना चित्र (SettingsLook.wallpaperGallery) - फ़ोन में ही सेव, फिर पूरे ऐप में लागू */
    internal val pickWall = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        toast("वॉलपेपर लग रहा है…")
        val app = applicationContext
        Thread {
            val err = com.dk.assistant.ui.Wallpapers.saveCustom(app, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err != null) { toast(err); return@runOnUiThread }
                com.dk.assistant.ui.Wallpapers.select(this, com.dk.assistant.ui.Wallpapers.CUSTOM)
                com.dk.assistant.ui.NeonRefresh.bars(this)
                toast("वॉलपेपर लागू हो गया")
                recreate()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_settings)
        Glass.install(this)
        container = findViewById(R.id.container)
        txtSaved = findViewById(R.id.txtSaved)
        findViewById<OrbView>(R.id.miniOrb).mini = true
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { save(false); finish() }
        build()
    }

    // ------------------------------------------------------------ UI build
    internal lateinit var swAutoUpd: SwitchCompat
    internal var pendingRelease: Updater.Release? = null
    /** फ़ोन लॉक / अनलॉक वाला हिस्सा खुला हो तो उसका सेव */
    internal var plSave: (() -> Unit)? = null

    companion object { const val EXTRA_FOCUS_KEY = "focus_key" }

    private val section: String? get() = intent.getStringExtra("section")

    private fun build() {
        val title = findViewById<TextView>(R.id.txtTitle)
        when (section) {
            null -> { title.text = "CONTROL CENTER"; title.letterSpacing = 0.05f; title.maxLines = 1
                // छोटे फ़ोन पर भी एक ही लाइन: जगह के हिसाब से 22sp से 14sp तक छोटा (एक बार, layout के बाद)
                title.post {
                    val room = title.width - title.paddingLeft - title.paddingRight
                    var sz = 22f
                    while (room > 0 && sz > 14f && title.paint.measureText(title.text.toString()) > room) { sz -= 1f; title.textSize = sz }
                    FutureViews.goldShader(title)
                }
                setupSearch(); menu() }
            "profile" -> { title.text = "प्रोफ़ाइल"; secProfile() }
            "lang" -> { title.text = "भाषा / Language"; secLang() }
            "brain" -> { title.text = "AI दिमाग"; secBrainMenu() }
            "brain_live" -> { title.text = "AI दिमाग • DK लाइव"; secBrainLive() }
            "brain_chat" -> { title.text = "AI दिमाग • DK चैट"; secBrain() }
            "brain_coder" -> { title.text = "AI दिमाग • कोडिंग एजेंट"; secBrainScope(ApiScopes.CODER) }
            "brain_pc" -> { title.text = "AI दिमाग • DK कंप्यूटर"; secBrainScope(ApiScopes.PC) }
            "brain_web" -> { title.text = "AI दिमाग • वेबसाइट बिल्डर"; secBrainWeb() }
            "voice" -> { title.text = "आवाज़"; secVoice() }
            "orb" -> { title.text = "ओर्ब और चेहरा"; secOrb() }
            "ui" -> { title.text = "दिखावट"; secUi() }
            "access" -> { title.text = "Accessibility"; secAccess(); secPayGuard() }
            "phonelock" -> { title.text = "फ़ोन लॉक / अनलॉक"; secPhoneLock(); secVoiceCmds() }
            "guard" -> { title.text = "स्क्रीन गार्ड"; secGuard() }
            "perms" -> { title.text = "परमिशन"; secPerms() }
            "update" -> { title.text = "अपडेट"; secUpdate() }
            "debug" -> { title.text = "डिबग लॉग"; secDebug() }
            "coder" -> { title.text = "कोडिंग एजेंट"; secCoder() }
            "computer" -> { title.text = "DK कंप्यूटर"; secComputer() }
            "social" -> { title.text = "सोशल रिव्यू"; secSocial() }
            "reels" -> { title.text = "रील गिनती और लिमिट"; secReels() }
            "about" -> { title.text = "ऐप के बारे में"; secAbout() }
        }
        space(container, 8)
        FutureViews.goldShader(title)          // नवंबर 2026: शीर्षक धातु जैसा सुनहरा (Control Center रूप)
    }


    // ------------------------------------------------------------ mukhya menu
    private fun menu() {
        val name = Prefs.assistantName(this)
        val items = listOf(
            Triple("profile", "प्रोफ़ाइल", "आपका नाम • असिस्टेंट का नाम ($name) • बात करने का तरीका"),
            Triple("lang", "भाषा / Language", "ऐप: ${if (UiLang.en()) "English" else "हिंदी"} • DK के जवाब: ${Lang.short(Prefs.replyLang(this))}"),
            Triple("ui", "दिखावट (UI कस्टमाइज़ेशन)", "थीम • मुख्य रंग • काँच की पारदर्शिता • चमक • एनिमेशन"),
            Triple("brain", "AI दिमाग", "DK लाइव • DK चैट • कोडिंग एजेंट • DK कंप्यूटर — हर एक की अपनी API key"),
            Triple("voice", "आवाज़", "बोलने वाला इंजन (Live • Gemini TTS) • सुनना"),
            Triple("orb", "ओर्ब और चेहरा", "पृथ्वी, गैलेक्सी, एटम, छल्ला, पुरुष/महिला HD चेहरा"),
            Triple("access", "Accessibility", (Setup.a11yMark(this) + when { Setup.accessibilityOn() -> "चालू"; Setup.enabledInSettings(this) -> "रुका है"; else -> "बंद" }) + " • फ़ोन कंट्रोल • हमेशा सुनना • कॉल • बैटरी"),
            Triple("phonelock", "फ़ोन लॉक / अनलॉक", (if (PhoneLockStore.lockOn(this) || PhoneLockStore.unlockOn(this)) "✓ चालू" else "✗ बंद") + " • \"हे DK फोन लॉक करो\" • अपना नाम और वाक्य • PIN / पैटर्न"),
            Triple("guard", "स्क्रीन गार्ड", (if (Guard.active()) "✓ चालू" else "✗ बंद") + " • कोई फ़ोन छुए तो सायरन • फ़ोटो • SMS • \"DK स्क्रीन गार्ड चालू करो\""),
            Triple("perms", "परमिशन", "माइक • स्क्रीन देखना • नोटिफ़िकेशन • कॉल • ऐप इंस्टॉल"),
            Triple("computer", "DK कंप्यूटर", (if (Prefs.computerOn(this)) "✓ चालू" else "✗ बंद") + " • कोई भी काम: खोज, रिसर्च, फ़ाइल/PDF, हिसाब, फ़ोन, रिमाइंडर"),
            Triple("coder", "कोडिंग एजेंट", (if (Prefs.coderOn(this)) "✓ चालू" else "✗ बंद") + " • GitHub से जुड़ें • कोड लिखें/बदलें/हटाएँ • push • अलग AI key"),
            Triple("auto", "ऑटोनोमस मोड", (if (com.dk.assistant.auto.AutoPrefs.on(this)) "✓ चालू" else "✗ बंद") + " • DK खुद तय करे और खुद करे • API keys • आपातकालीन STOP"),
            Triple("templates", "टेम्पलेट कमांड", "बिना AI के तय साँचे • WhatsApp • अलार्म • मैप • कॉल"),
            Triple("social", "सोशल रिव्यू", "इंस्टाग्राम • फेसबुक • यूट्यूब स्टूडियो — हर रील के व्यूज़, रीच क्यों गिरी, ट्रेंड, ज़्यादा व्यूज़ कैसे"),
            Triple("reels", "रील गिनती और लिमिट", "इंस्टाग्राम • YouTube शॉर्ट्स • फेसबुक — ऊपर गिनती की पट्टी • रोज़ की लिमिट • फ़ोन लॉक / ऐप बंद"),
            Triple("connectors", "कनेक्टर", "Gmail • Drive • Calendar • GitHub • Facebook Page • Instagram • MCP — DK लाइव से बोलकर काम"),
            Triple("story", "स्टोरी राइटर एजेंट", "लंबी हिंदी कहानियाँ (10–30 मिनट) • भाग-दर-भाग • कॉपी • फ़ोन में सेव"),
            Triple("update", "अपडेट", "अभी: ${Updater.displayVersion(this)} • नया version चेक करें"),
            Triple("debug", "डिबग लॉग", "माइक, कनेक्शन, देरी और गलतियों का पूरा हाल"),
            Triple("about", "ऐप के बारे में", "version • बनाने वाले • निजता (privacy)")
        )
        items.forEach { (id, t, sub) -> menuRow(id, t, sub) }
    }

    /** मेनू की एक पंक्ति (icon + नाम + विवरण + ›) - दबाने पर वह section */
    internal fun menuRow(id: String, t: String, sub: String, icon: String = id, searchHit: SettingsSearch.E? = null) {
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
                    else if (id == "auto") startActivity(android.content.Intent(this@SettingsActivity, com.dk.assistant.auto.AutoSettingsActivity::class.java))
                    else if (id == "templates") startActivity(android.content.Intent(this@SettingsActivity, com.dk.assistant.template.TemplateSettingsActivity::class.java))
                    else if (searchHit != null) openHit(searchHit)
                    else startActivity(android.content.Intent(this@SettingsActivity, SettingsActivity::class.java).putExtra("section", id))
                }
            }
            // har section ka apna rangeen icon (rang neele background se alag) glass gole me
            box.addView(android.widget.ImageView(this).apply {
                // नवंबर 2026: गोल की जगह रंगीन squircle (हर section का अपना निऑन रंग)
                setImageResource(menuIcon(icon)); background = FutureViews.squircle(this@SettingsActivity, menuTint(icon))
                val ip = (10 * dp).toInt(); setPadding(ip, ip, ip, ip); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
            val subTv = TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0) }
            txt.addView(subTv)
            box.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // नवंबर 2026 (मालिक, संदर्भ चित्र): कार्ड पर ही असली स्विच / slider - बाकी पर ›
            val sw = if (searchHit == null) MenuQuick.switchFor(id, this) else null
            if (sw != null) {
                box.addView(SwitchCompat(this).apply {
                    isChecked = sw.first; contentDescription = UiLang.s(t)
                    setOnCheckedChangeListener { _, on ->
                        sw.second(on)
                        subTv.text = (if (on) "✓ चालू" else "✗ बंद") + sub.substringAfter(" • ", "").let { if (it.isEmpty()) "" else " • $it" }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()).apply { marginStart = (6 * dp).toInt() })
            } else box.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(0x99FFFFFF.toInt()) })
            if (id == "ui" && searchHit == null) txt.addView(MenuQuick.glassSlider(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (12 * dp).toInt()
            container.addView(box, lp)
        }
    }

    /** कोडिंग एजेंट / DK कंप्यूटर की अपनी API: मुख्य कंपनी चुनें + हर कंपनी का अपना key कार्ड */
    internal val scopeRadios = mutableMapOf<String, RadioGroup>()

    /**
     * Sabse aasaan: koi bhi FREE key paste karo -> DK khud company pehchaane, free models laaye,
     * sabse tez chune, save kare aur baat shuru. Jawab (text) aur awaaz (sunna) ki key ALAG = takraav nahi.
     */
    internal fun quickSetupCard() {
        card("तुरंत सेटअप — कोई भी फ्री key डालें", "DK खुद पहचानेगा कि key किस कंपनी की है, उसके फ्री मॉडल जाँचकर सबसे तेज़ चुनेगा और बात शुरू कर देगा") { box ->
            val edText = field(box, "API key", "", "Gemini / Groq / OpenRouter — कोई भी", secret = true)
            val res1 = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            val cur = Prefs.activeProviderRaw(this).let { id -> if (Prefs.keyRaw(this, id).isNotBlank()) "अभी: ${KeySetup.name(id)} • ${Prefs.model(this, id)}" else "अभी कोई key नहीं" }
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
                        keyFields[ApiScopes.pref(ApiScopes.CHAT, "key", r.provider)]?.setText(Prefs.keyRaw(this@SettingsActivity, r.provider))
                        modelFields[ApiScopes.pref(ApiScopes.CHAT, "model", r.provider)]?.setText(r.model)
                        fetchedFor[ApiScopes.pref(ApiScopes.CHAT, "key", r.provider)] = Prefs.keyRaw(this@SettingsActivity, r.provider)
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

    // v4.5: "सिर्फ़ मेरी आवाज़ सुनो" option ऐप से हटाया - मालिक की माँग. ownerJob/ownerDialog "Hey DK सिखाओ" में चलते हैं.
    private var ownerJob: kotlinx.coroutines.Job? = null
    /** नवंबर 2026: आवाज़-कमांड सिखाना (SettingsVoiceCmds) भी इसी एक job/dialog से - दो रिकॉर्डिंग साथ न चलें */
    internal fun ownerRun(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        ownerJob?.cancel(); ownerJob = lifecycleScope.launch(block = block)
    }
    internal fun ownerCancel() { ownerJob?.cancel() }

    internal fun enrollHeyDk(done: () -> Unit) {
        val HD = com.dk.assistant.voice.HeyDk
        val WM = com.dk.assistant.voice.WakeMatch
        val OV = com.dk.assistant.voice.OwnerVoice
        if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
        val (dlg, tv) = ownerDialog("Hey DK सिखाओ")
        ownerJob?.cancel()
        ownerJob = lifecycleScope.launch {
            try {
                // आवाज़-पहचान (सिर्फ़ मालिक) के लिए मॉडल - न चल सके तो सिर्फ़ शब्द-मिलान
                if (OV.supported && !OV.hasModel(this@SettingsActivity)) {
                    tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…")
                    val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        OV.download(this@SettingsActivity) { pct -> runOnUiThread { tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…") + " $pct%" } }
                    }
                    if (err != null) { tv.text = UiLang.s(err); kotlinx.coroutines.delay(2500) }
                }
                // नवंबर 2026 (मालिक): "Hey DK" भी 30 सेकंड की एक रिकॉर्डिंग में 5 बार (एक-एक नमूने वाला तरीका हटाया).
                // चुप्पी वाले हिस्से से कमरे का शोर - उसी से "दूर से बोले" जैसे नमूने भी बनते हैं (HeyDk.save)
                val VT = com.dk.assistant.voice.VoiceTape
                val sources = listOf(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                var srcIdx = 0
                var takes: List<ByteArray> = emptyList()
                var noise = ByteArray(0); var quiet = false
                var why: String? = null
                withDkMicPaused {
                    while (true) {
                        tv.text = heyDkTapeText(null)
                        kotlinx.coroutines.delay(1200)
                        var lastUi = 0L
                        val r = com.dk.assistant.voice.VoiceTapeRec.record(sources[srcIdx], ok = { pcm -> HD.spokenFrames(pcm) in 20..300 }) { t ->
                            val now = System.currentTimeMillis()
                            if (now - lastUi >= 120 || t.got >= VT.TIMES) { lastUi = now; runOnUiThread { tv.text = heyDkTapeText(t) } }
                        }
                        // इस माइक-रास्ते पर कुछ नहीं आया (कई फ़ोन पर पहला बहुत धीमा) -> Live वाला रास्ता, एक बार फिर
                        if (srcIdx == 0 && (!r.micOk || r.takes.isEmpty())) {
                            srcIdx = 1; tv.text = UiLang.s("माइक दूसरे तरीके से खोल रहा हूँ — फिर से बोलिए"); kotlinx.coroutines.delay(1200); continue
                        }
                        if (!r.micOk) { why = "माइक नहीं खुला — कोई दूसरा ऐप माइक इस्तेमाल कर रहा है?"; break }
                        why = VT.verdict(r.takes.size, r.tooShort, r.tooLong)
                        takes = r.takes; noise = r.noise; quiet = r.quiet
                        break
                    }
                }
                if (why != null) { tv.text = UiLang.s(why!!) + "\n\n" + UiLang.s("\"Hey DK सिखाओ\" फिर दबाइए।"); return@launch }
                tv.text = UiLang.s("सीख रहा हूँ…") + " (${takes.size} " + UiLang.s("बार") + ")"
                // एक बार बाकियों से बहुत अलग (खाँसी / कोई और बोला) = उसे छोड़कर बाकी से सीखो
                val keep = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val bad = if (takes.size > VT.MIN_TIMES) WM.outlier(takes.map { HD.featuresOf(it) }) else -1
                    if (bad >= 0) takes.filterIndexed { i, _ -> i != bad } else takes
                }
                val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { HD.save(this@SettingsActivity, keep, sources[srcIdx], noise) }
                if (err != null) { tv.text = UiLang.s(err); return@launch }
                if (quiet) DKLog.d("Hey DK: सिखाते समय आवाज़ धीमी थी")
                if (::swWake.isInitialized) swWake.isChecked = true
                Setup.startKeepAlive(this@SettingsActivity)
                DKAccessibilityService.instance?.startWakeLoopIfEnabled()
                tv.text = UiLang.s("हो गया — अब \"Hey DK\" बोलकर देखिए।") + "\n\n" + UiLang.s(HD.status(this@SettingsActivity))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { HD.prepareAcks(this@SettingsActivity) } catch (_: Exception) {} }
                done()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { tv.text = UiLang.s("गड़बड़ हुई") + " (${e.javaClass.simpleName})" }
        }
        dlg.setOnDismissListener { ownerJob?.cancel() }
    }

    /** "Hey DK सिखाओ" - 30 सेकंड की रिकॉर्डिंग का हाल */
    private fun heyDkTapeText(t: com.dk.assistant.voice.VoiceTapeRec.Tick?): String {
        val VT = com.dk.assistant.voice.VoiceTape
        val head = UiLang.s("Hey DK — 30 सेकंड") + "\n\n" +
            UiLang.s("\"हे डीके\" (Hey DK) ${VT.TIMES} बार बोलिए, हर बार के बाद 2 सेकंड रुकिए।") + "\n" +
            UiLang.s("आम आवाज़ में, जैसे रोज़ बुलाएँगे। फ़ोन एक हाथ दूर रखें।")
        if (t == null) return head + "\n\n" + UiLang.s("तैयार हो जाइए… (कमरे का शोर सुन रहा हूँ)")
        val bars = (t.level * 12).toInt().coerceIn(0, 12)
        return head + "\n\n" + UiLang.s(VT.progress(t.leftMs, t.got)) + "\n" + UiLang.s("माइक:") + " " + "▮".repeat(bars) + "▯".repeat(12 - bars) +
            (if (t.note.isNotBlank()) "\n" + UiLang.s(t.note) else "")
    }

    internal fun ownerDialog(title: String): Pair<androidx.appcompat.app.AlertDialog, TextView> {
        val tv = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); val p = (20 * dp).toInt(); setPadding(p, p, p, p) }
        val d = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s(title)).setView(tv)
            .setNegativeButton(UiLang.s("बंद करें")) { _, _ -> ownerJob?.cancel() }
            .setOnCancelListener { ownerJob?.cancel() }.show()
        return d to tv
    }

    // ------------------------------------------------------------ accessibility
    internal var accStatus: TextView? = null
    internal var batStatus: TextView? = null
    internal var secStatus: TextView? = null

    // ------------------------------------------------------------ permissions
    internal var permStatus: TextView? = null

    override fun onResume() {
        super.onResume()
        refreshAccess(); refreshPerms()
        if (section == null && container.childCount > 0) { container.removeAllViews(); menu(); space(container, 8) }
        if (section == "brain" && container.childCount > 0) { container.removeAllViews(); secBrainMenu(); space(container, 8) }
    }

    // ------------------------------------------------------------ DK कंप्यूटर
    internal lateinit var swPc: SwitchCompat
    internal lateinit var swPcConfirm: SwitchCompat
    internal lateinit var rgPcSearch: RadioGroup
    internal lateinit var edTavily: EditText
    internal lateinit var edExa: EditText
    internal lateinit var edPcSteps: EditText
    internal val pcTools = mutableMapOf<String, SwitchCompat>()
    internal val pcSearchIds = listOf("auto", "tavily", "exa", "gemini", "openrouter")

    // ------------------------------------------------------------ coding agent + GitHub
    internal lateinit var swCoder: SwitchCompat
    internal lateinit var swAutoPush: SwitchCompat
    internal lateinit var edCoderSteps: EditText
    internal lateinit var edCoderLight: EditText
    internal lateinit var rgStrong: android.widget.RadioGroup
    internal lateinit var rgStrongScope: android.widget.RadioGroup
    internal lateinit var edStrongKey: EditText
    internal lateinit var edStrongModel: EditText
    internal lateinit var swBuildFix: SwitchCompat
    internal lateinit var swTeam: SwitchCompat
    internal lateinit var swSeven: SwitchCompat
    internal lateinit var edGhToken: EditText
    internal lateinit var edGhRepo: EditText
    internal lateinit var edGhBranch: EditText

    // ------------------------------------------------------------ save
    internal fun save(showToast: Boolean) {
        if (section == null || section == "orb" || section == "ui" || section == "face") return
        val active = if (::rgProvider.isInitialized)
            Providers.all.getOrNull(rgProvider.checkedRadioButtonId - 1000)?.id ?: Prefs.activeProviderRaw(this)
        else Prefs.activeProviderRaw(this)
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
                if (::swDkOnly.isInitialized) putBoolean("dk_only_listen", swDkOnly.isChecked)
                if (::edBusyMsg.isInitialized) putString("call_busy_msg", edBusyMsg.text.toString().trim())
            }
            if (::swShot.isInitialized) { putBoolean("screenshot", swShot.isChecked); putBoolean(ScreenGuard.USER_OK_KEY, swShot.isChecked) }
            if (::swAutoUpd.isInitialized) putBoolean("auto_update", swAutoUpd.isChecked)
            // v3.69: आवाज़ (महिला/पुरुष, प्रकार, अंदाज़, स्पीड, पिच) की सेटिंग हटी - पहले सेव मान वैसे ही रहते हैं
            if (::rgListen.isInitialized) putString("listen_engine", radioValue(rgListen, listOf("auto", "classic", "google"), 2200))
            if (::swCoder.isInitialized) {
                putBoolean("coder_on", swCoder.isChecked)
                putBoolean("coder_autopush", swAutoPush.isChecked)
                putString("coder_max_steps", (edCoderSteps.text.toString().trim().toIntOrNull() ?: CodingAgent.DEFAULT_STEPS).coerceIn(3, 30).toString())
                if (::edCoderLight.isInitialized) putString("coder_light_model", edCoderLight.text.toString().trim())
                if (::edStrongKey.isInitialized) {
                    putString(StrongAi.PREF_PROVIDER, radioValue(rgStrong, StrongAi.CHOICES.map { it.id }, STRONG_RADIO))
                    putString(StrongAi.PREF_KEY, edStrongKey.text.toString().trim())
                    putString(StrongAi.PREF_MODEL, edStrongModel.text.toString().trim())
                    if (::rgStrongScope.isInitialized) putString(StrongAi.PREF_SCOPE, radioValue(rgStrongScope, StrongAi.SCOPES.map { it.first }, SCOPE_RADIO))
                }
                putBoolean("coder_buildfix", swBuildFix.isChecked)
                putBoolean("coder_team", swTeam.isChecked)
                putBoolean("coder_seven", swSeven.isChecked)
                putString("gh_token", edGhToken.text.toString().trim())
                putString("gh_repo", Prefs.cleanRepo(edGhRepo.text.toString()))
                putString("gh_branch", edGhBranch.text.toString().trim())
            }
            if (::swPc.isInitialized) {
                putBoolean("pc_on", swPc.isChecked)
                putBoolean("pc_confirm", swPcConfirm.isChecked)
                putInt("pc_max_steps", (edPcSteps.text.toString().trim().toIntOrNull() ?: 25).coerceIn(5, 60))
                putString("pc_search", pcSearchIds.getOrNull(rgPcSearch.checkedRadioButtonId - 5100) ?: "auto")
                putString("key_tavily", edTavily.text.toString().trim())
                putString(Prefs.EXA_SEARCH_KEY, edExa.text.toString().trim())
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
        plSave?.invoke()
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        txtSaved?.text = "✓ सेव हो गया"
        if (showToast) {
            val active2 = Providers.byId(active)
            val warn = if (::rgProvider.isInitialized && Prefs.keyRaw(this, active).isBlank()) " (पर ${active2?.name} की key खाली है)" else ""
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

    override fun onPause() { save(false); stopVoiceCmdPlay(); super.onPause() }
    override fun onDestroy() { if (section == "debug") DKLog.listener = null; speaker?.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------ small UI helpers
    internal val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { txtSaved?.text = "बदलाव सेव नहीं हुए — सेव दबाएँ" }
    }

    // ------------------------------------------------------------ सेटिंग्स में खोज (टॉगल होने वाला लॉजिक)
    private fun setupSearch() {
        val box = findViewById<EditText>(R.id.settingsSearch)
        box.visibility = View.GONE
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        btnSearch.visibility = View.VISIBLE
        btnSearch.setOnClickListener {
            if (box.visibility == View.VISIBLE) {
                box.visibility = View.GONE
                box.setText("")
            } else {
                box.visibility = View.VISIBLE
                box.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(box, 0)
            }
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                container.removeAllViews()
                if (q.isBlank()) { menu(); space(container, 8); return }
                val hits = SettingsSearch.search(q)
                if (hits.isEmpty()) {
                    container.addView(TextView(this@SettingsActivity).apply {
                        text = UiLang.s("\"$q\" से कुछ नहीं मिला — कोई दूसरा शब्द लिखें"); textSize = 14f; setTextColor(0x99FFFFFF.toInt())
                        val pd = (16 * dp).toInt(); setPadding(pd, pd, pd, pd)
                    })
                    return
                }
                hits.forEach { e ->
                    val icon = when (e.section) { SettingsSearch.ACT_AUTO -> "auto"; SettingsSearch.ACT_TEMPLATES -> "templates"
                        SettingsSearch.ACT_CONNECTORS -> "connectors"; SettingsSearch.ACT_STORY -> "story"; else -> e.section.substringBefore('_') }
                    if (e.card.isBlank()) menuRow(e.section, SettingsSearch.SECTIONS[e.section].orEmpty(), e.words.split(' ').take(5).joinToString(" • "), icon, e)
                    else menuRow(e.section, e.card, SettingsSearch.place(e) + if (e.sub.isNotBlank()) " • " + e.sub.take(70) else "", icon, e)
                }
                space(container, 8)
            }
        })
    }

}
