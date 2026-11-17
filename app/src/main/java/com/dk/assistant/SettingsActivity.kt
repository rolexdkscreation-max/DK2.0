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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_settings)
        Glass.install(this)
        PlainLook.install(this)   // 14 नवंबर 2026 (मालिक): सेटिंग पैनल होम जैसा साधारण (काँच/ग्लासी नहीं)
        container = findViewById(R.id.container)
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
            "cat_dk" -> { title.text = "मेरा DK"; menu("cat_dk") }
            "cat_brain" -> { title.text = "दिमाग और आवाज़"; menu("cat_brain") }
            "cat_auto" -> { title.text = "ऑटोमेशन"; menu("cat_auto") }
            "cat_security" -> { title.text = "सुरक्षा"; menu("cat_security") }
            "cat_social" -> { title.text = "सोशल और कहानी"; menu("cat_social") }
            "cat_data" -> { title.text = "डेटा और ऐप"; menu("cat_data") }
            "profile" -> { title.text = "प्रोफ़ाइल"; secProfile() }
            "lang" -> { title.text = "भाषा / Language"; secLang() }
            "brain" -> { title.text = "AI दिमाग"; secBrainMenu() }
            "brain_live" -> { title.text = "AI दिमाग • DK लाइव"; secBrainLive() }
            "brain_chat" -> { title.text = "AI दिमाग • DK चैट"; secBrain() }
            "brain_coder" -> { title.text = "AI दिमाग • कोडिंग एजेंट"; secBrainScope(ApiScopes.CODER) }
            "brain_pc" -> { title.text = "AI दिमाग • DK कंप्यूटर"; secBrainScope(ApiScopes.PC) }
            "voice" -> { title.text = "आवाज़"; secVoice(); voiceGuardianCard() }
            "orb" -> { title.text = "ओर्ब और चेहरा"; secOrb(); secIsland() }
            "ui" -> { title.text = "दिखावट"; secUi() }
            "access" -> { title.text = "Accessibility"; secAccess(); secPayGuard(); secDriving(); secEvents() }
            "phonelock" -> { title.text = "फ़ोन लॉक / अनलॉक"; secPhoneLock(); secVoiceCmds() }
            "guard" -> { title.text = "टच मोड"; secGuard(); secPrivacy(); secSos() }
            "face" -> { title.text = "चेहरा पहचान"; secFace() }
            "perms" -> { title.text = "परमिशन"; secPerms() }
            "update" -> { title.text = "अपडेट"; secUpdate(); secBetaCheck() }
            "license" -> { title.text = "लाइसेंस की"; secLicense() }
            "debug" -> { title.text = "डिबग लॉग"; secBetaCheck(); secDebug() }
            "coder" -> { title.text = "कोडिंग एजेंट"; secCoder(); secTermux() }
            "computer" -> { title.text = "DK कंप्यूटर"; secComputer(); secImageGen() }
            "social" -> { title.text = "सोशल रिव्यू"; secSocial(); secYouTube() }
            "reels" -> { title.text = "रील गिनती और लिमिट"; secReels() }
            "autoreply" -> { title.text = "ऑटो रिप्लाई"; secAutoReply() }
            "about" -> { title.text = "ऐप के बारे में"; secAbout() }
        }
        // 15 नवंबर 2026: नई सुविधाएँ अपने पुराने section के नीचे (पुरानी लाइनें वैसी ही)
        when (section) { "ui" -> secHomeTabs(); "computer" -> secEmail(); "autoreply" -> secGroupReport() }
        space(container, 8)
        FutureViews.goldShader(title)          // नवंबर 2026: शीर्षक धातु जैसा सुनहरा (Control Center रूप)
    }


    // ------------------------------------------------------------ mukhya menu
    /** नवंबर 2026 (Control Center): cat = null -> 6 श्रेणी कार्ड; cat = "cat_…" -> उस श्रेणी की वही पुरानी पंक्तियाँ */
    private fun menu(cat: String? = null) {
        val name = Prefs.assistantName(this)
        val items = listOf(
            Triple("profile", "प्रोफ़ाइल", "आपका नाम • असिस्टेंट का नाम ($name) • बात करने का तरीका"),
            Triple("lang", "भाषा / Language", "ऐप: ${if (UiLang.en()) "English" else "हिंदी"} • DK के जवाब: ${Lang.short(Prefs.replyLang(this))}"),
            Triple("ui", "दिखावट (UI कस्टमाइज़ेशन)", "थीम • मुख्य रंग • काँच की पारदर्शिता • चमक • एनिमेशन"),
            Triple("brain", "AI दिमाग", "DK लाइव • DK चैट • कोडिंग एजेंट • DK कंप्यूटर • एक key से सब"),
            Triple("voice", "आवाज़", "बोलने वाला इंजन (Live • Gemini TTS) • सुनना"),
            Triple("orb", "ओर्ब और चेहरा", "पृथ्वी, गैलेक्सी, एटम, छल्ला, पुरुष/महिला HD चेहरा"),
            Triple("access", "Accessibility", (Setup.a11yMark(this) + when { Setup.accessibilityOn() -> "चालू"; Setup.enabledInSettings(this) -> "रुका है"; else -> "बंद" }) + " • फ़ोन कंट्रोल • हमेशा सुनना • कॉल • बैटरी • ड्राइविंग मोड • इवेंट ट्रिगर"),
            Triple("phonelock", "फ़ोन लॉक / अनलॉक", (if (PhoneLockStore.lockOn(this) || PhoneLockStore.unlockOn(this)) "✓ चालू" else "✗ बंद") + " • \"हे DK फोन लॉक करो\" • अपना नाम और वाक्य • PIN / पैटर्न"),
            Triple("guard", "टच मोड", (if (Guard.active()) "✓ चालू" else "✗ बंद") + " • कोई फ़ोन छुए तो सायरन • फ़ोटो • SMS • इमरजेंसी SOS"),
            Triple("face", "चेहरा पहचान", (if (FaceAI.enrolled(this)) "✓ Boss का चेहरा सेट" else "✗ सेट नहीं") + " • जान-पहचान वाले (${FacePeople.all(this).size}) • कौन है? • DK से पूछकर पहचान"),
            Triple("perms", "परमिशन", "माइक • स्क्रीन देखना • नोटिफ़िकेशन • कॉल • ऐप इंस्टॉल"),
            Triple("computer", "DK कंप्यूटर", (if (Prefs.computerOn(this)) "✓ चालू" else "✗ बंद") + " • कोई भी काम: खोज, रिसर्च, फ़ाइल/PDF, हिसाब, फ़ोन, रिमाइंडर, मुफ़्त इमेज"),
            Triple("coder", "कोडिंग एजेंट", (if (Prefs.coderOn(this)) "✓ चालू" else "✗ बंद") + " • GitHub से जुड़ें • कोड लिखें/बदलें/हटाएँ • push • अलग AI key • Termux"),
            Triple("auto", "ऑटोनोमस मोड", (if (com.dk.assistant.auto.AutoPrefs.on(this)) "✓ चालू" else "✗ बंद") + " • DK खुद तय करे और खुद करे • API keys • आपातकालीन STOP"),
            Triple("templates", "टेम्पलेट कमांड", "बिना AI के तय साँचे • WhatsApp • अलार्म • मैप • कॉल"),
            Triple("autoreply", "ऑटो रिप्लाई", (if (com.dk.assistant.autoreply.AutoReply.on(this)) "✓ चालू" else "✗ बंद") + " • WhatsApp • WhatsApp Business • Telegram • Messenger — हर ऐप का अपना स्विच"),
            Triple("social", "सोशल रिव्यू", "इंस्टाग्राम • फेसबुक • यूट्यूब स्टूडियो — हर रील के व्यूज़, रीच क्यों गिरी, ट्रेंड • YouTube चैनल जाँच"),
            Triple("reels", "रील गिनती और लिमिट", "इंस्टाग्राम • YouTube शॉर्ट्स • फेसबुक — ऊपर गिनती की पट्टी • रोज़ की लिमिट • फ़ोन लॉक / ऐप बंद"),
            Triple("backup", "बैकअप", "DK की याददाश्त Google Drive में • अपना ईमेल डालें • बैकअप / रिस्टोर (ऐप दोबारा डालने पर सब वापस)"),
            Triple("connectors", "कनेक्टर", "Gmail • Drive • Calendar • GitHub • Facebook Page • Instagram • MCP — DK लाइव से बोलकर काम"),
            Triple("story", "स्टोरी राइटर एजेंट", "लंबी हिंदी कहानियाँ (10–30 मिनट) • भाग-दर-भाग • कॉपी • फ़ोन में सेव"),
            Triple("update", "अपडेट", "अभी: ${Updater.displayVersion(this)} • नया version चेक करें"),
            // 17 नवंबर 2026 (DK बैकएंड): लाइसेंस की - मुख्य पन्ने पर अपडेट के ठीक ऊपर (किसी श्रेणी में नहीं)
            Triple("license", "लाइसेंस की", "की डालें • स्तर और आज का बचा समय • लाइसेंस की पाएँ (WhatsApp)"),
            Triple("debug", "डिबग लॉग", "माइक, कनेक्शन, देरी और गलतियों का पूरा हाल"),
            Triple("about", "ऐप के बारे में", "version • बनाने वाले • निजता (privacy)")
        )
        if (cat == null) {
            // 15 नवंबर 2026 (मालिक: "अपडेट का सेक्शन सबसे नीचे दिखना चाहिए"): मुख्य पन्ने पर एक ही जगह, सबसे नीचे
            // (किसी श्रेणी के अंदर नहीं). नया अपडेट आया हो तो होम पर चमकती पट्टी + popup ([UpdatePrompt])
            SettingsSearch.CATEGORIES.forEach { (c, _) -> menuRow(c.first, c.second, c.third) }
            // 15 नवंबर 2026 (मालिक): "DK AI Plus" का अलग पन्ना हटाया - उसकी असली सुविधाएँ अपनी पुरानी जगह ([secSos] वगैरह, SettingsExtra.kt)
            items.firstOrNull { it.first == "license" }?.let { (id, t, sub) -> menuRow(id, t, sub) }
            items.firstOrNull { it.first == "update" }?.let { (id, t, sub) -> menuRow(id, t, sub) }
            return
        }
        val ids = SettingsSearch.CATEGORIES.firstOrNull { it.first.first == cat }?.second.orEmpty()
        items.filter { it.first in ids }.sortedBy { ids.indexOf(it.first) }.forEach { (id, t, sub) -> menuRow(id, t, sub) }
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
                    else if (id == "backup") startActivity(android.content.Intent(this@SettingsActivity, com.dk.assistant.backup.BackupActivity::class.java))
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
            // 15 नवंबर 2026 (मालिक: "light में भी dark वाला ही design"): pastel रंगीन डिब्बा और रंगीन नाम हटे -
            // दोनों मोड में वही कार्ड; light में PlainLook बस कार्ड सफ़ेद और लिखावट गहरी करता है
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt()) })
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
            // 14 नवंबर 2026 (मालिक): काँच (ग्लासी) हर जगह से हटा - इसलिए "दिखावट" पर काँच वाला slider भी नहीं
            // 14 नवंबर 2026 (मालिक): डार्क / लाइट मोड सेटिंग खोलते ही दिखे (अंदर जाए बिना)
            if (id == "ui" && searchHit == null) txt.addView(SwitchCompat(this).apply {
                text = "लाइट मोड (बंद = डार्क)"; textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
                isChecked = com.dk.assistant.dkai.DkaiTheme.light
                setOnCheckedChangeListener { _, on -> com.dk.assistant.dkai.DkaiTheme.setLight(this@SettingsActivity, on); save(false); recreate() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()))
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
            val edText = field(box, "API key", "", "Gemini / Groq / OpenRouter — कोई भी", secret = true, card = false)
            val res1 = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            val cur = Prefs.activeProviderRaw(this).let { id -> if (Prefs.keyRaw(this, id).isNotBlank()) "अभी: ${KeySetup.name(id)} ✓ key लगी है" else "अभी कोई key नहीं" }
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

    // 16 नवंबर 2026: पुराना "Hey DK सिखाओ" (enrollHeyDk) हटाया - नया वेक वर्ड सिखाना नहीं माँगता (wake/SettingsWake.kt)

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
        if (section?.startsWith("cat_") == true && container.childCount > 0) { container.removeAllViews(); menu(section); space(container, 8) }
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
    internal lateinit var swCoderLite: SwitchCompat
    internal lateinit var edGhToken: EditText
    internal lateinit var edGhRepo: EditText
    internal lateinit var edGhBranch: EditText

    // ------------------------------------------------------------ save
    internal fun save(showToast: Boolean) {
        if (section == null || section?.startsWith("cat_") == true || section == "orb" || section == "ui" || section == "face") return
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
                if (::swCoderLite.isInitialized) putBoolean(Prefs.CODER_LITE_SIDE, swCoderLite.isChecked)
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

    @Deprecated("Contacts picker (SOS)")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        sosPicked(requestCode, resultCode, data)   // 15 नवंबर 2026 (हिस्सा 4)
        licenseGoogleResult(requestCode, data)       // 17 नवंबर 2026: लाइसेंस की → Google से लॉगिन
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
                        SettingsSearch.ACT_CONNECTORS -> "connectors"; SettingsSearch.ACT_STORY -> "story"; SettingsSearch.ACT_BACKUP -> "backup"; else -> e.section.substringBefore('_') }
                    if (e.card.isBlank()) menuRow(e.section, SettingsSearch.SECTIONS[e.section].orEmpty(), e.words.split(' ').take(5).joinToString(" • "), icon, e)
                    else menuRow(e.section, e.card, SettingsSearch.place(e) + if (e.sub.isNotBlank()) " • " + e.sub.take(70) else "", icon, e)
                }
                space(container, 8)
            }
        })
    }

}
