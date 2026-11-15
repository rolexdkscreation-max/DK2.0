package com.dk.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.text.InputType
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.plus.DrivingMode
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.ImageGen
import com.dk.assistant.plus.Sos
import com.dk.assistant.plus.Termux
import com.dk.assistant.plus.YtChannel
import kotlinx.coroutines.launch

/**
 * 15 नवंबर 2026 (मालिक: "DK AI Plus का नाम हटाओ - जो सेटिंग पहले से बनी है उसी के अंदर डालो, दोहराव नहीं,
 * झूठे / बेकार स्विच नहीं"): पुराने "DK AI Plus" पन्ने की सिर्फ़ असली काम करने वाली सुविधाएँ, अपनी सही पुरानी जगह पर:
 *  - इमरजेंसी SOS        → सुरक्षा → टच मोड
 *  - ड्राइविंग मोड, ऑटो इवेंट ट्रिगर → ऑटोमेशन → Accessibility (कॉल / बैटरी वाला हिस्सा)
 *  - Termux ऑटोमेशन      → दिमाग और आवाज़ → कोडिंग एजेंट
 *  - इमेज बनाना (मुफ़्त API) → दिमाग और आवाज़ → DK कंप्यूटर
 *  - YouTube चैनल जाँच    → सोशल और कहानी → सोशल रिव्यू
 *  - इनकॉग्निटो           → आवाज़ → Memory (याददाश्त) कॉलम ([secVoice])
 * हर स्विच वही pref ([FeatureFlags]) जो सुविधा का code पढ़ता है - शुरू में सब बंद. कोई key / paywall नहीं.
 */

internal fun SettingsActivity.flagSwitch(box: LinearLayout, t: String, key: String, onChange: (Boolean) -> Unit = {}) =
    switch(box, t, FeatureFlags.on(this, key)).apply {
        setOnCheckedChangeListener { _, on -> FeatureFlags.set(this@flagSwitch, key, on); onChange(on) }
    }

/** जो इजाज़त अभी नहीं मिली, सिर्फ़ वही माँगो */
internal fun SettingsActivity.askMissing(code: Int, vararg perms: String) {
    val miss = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), code)
}

internal fun SettingsActivity.status(box: LinearLayout): TextView = TextView(this).apply {
    textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
}.also { box.addView(it) }

internal fun SettingsActivity.openLink(url: String) {
    if (url.isBlank()) return
    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast(url) }
}

// ------------------------------------------------------------------ सुरक्षा → टच मोड
internal fun SettingsActivity.secSos() {
    card("इमरजेंसी SOS", "\"DK, activate SOS\" या \"SOS अभी शुरू करें\" - ${Sos.Rules.COUNTDOWN_SEC} सेकंड की गिनती, फिर 5 लोगों को लोकेशन WhatsApp + SMS और कॉल") { box ->
        val sosPerms = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS)
        flagSwitch(box, "इमरजेंसी SOS चालू", FeatureFlags.SOS_ON) { on ->
            if (on) askMissing(61, *sosPerms)
        }
        // 15 नवंबर 2026 (मालिक, हिस्सा 4): 5 नंबरों की सूची - हर नंबर पर बदलें / हटाएँ, Contacts से चुनें
        label(box, "इमरजेंसी नंबर (${Sos.MAX_CONTACTS} तक) — सिर्फ़ इसी फ़ोन में")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        val ed = field(box, "SOS नंबर (कॉमा से अलग, ज़्यादा से ज़्यादा ${Sos.MAX_CONTACTS})", FeatureFlags.str(this, Sos.CONTACTS), "98xxxxxx01, 98xxxxxx02")
        ed.inputType = InputType.TYPE_CLASS_PHONE
        val st = status(box)
        lateinit var show: () -> Unit
        fun save(l: List<String>) { FeatureFlags.putStr(this, Sos.CONTACTS, l.joinToString(", ")); ed.setText(l.joinToString(", ")); show() }
        fun ask(title: String, cur: String, done: (String) -> Unit) {
            val e = android.widget.EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; setText(cur) }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s(title)).setView(e)
                .setPositiveButton(UiLang.s("सेव")) { _, _ -> done(e.text.toString()) }.setNegativeButton(UiLang.s("रद्द"), null).show()
        }
        show = {
            list.removeAllViews()
            val cur = Sos.contacts(this)
            cur.forEachIndexed { i, n ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                r.addView(TextView(this).apply { text = "${i + 1}. $n"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(button("बदलें") { ask("नंबर बदलें", n) { v -> save(Sos.Rules.replace(Sos.contacts(this), i, v)) } })
                r.addView(button("हटाएँ") { save(Sos.Rules.remove(Sos.contacts(this), i)); toast("हटा दिया") })
                list.addView(r)
            }
            st.text = UiLang.s(if (cur.isEmpty()) "अभी कोई नंबर नहीं — पहले emergency नंबर जोड़ो" else "सेव नंबर: ${cur.size}/${Sos.MAX_CONTACTS}")
        }
        show()
        row(box, button("Contacts से चुनें") {
            if (Sos.contacts(this).size >= Sos.MAX_CONTACTS) { toast("5 नंबर पूरे — पहले कोई हटाएँ"); return@button }
            sosPick = { n -> val l = Sos.Rules.add(Sos.contacts(this), n); save(l); toast("जुड़ गया: $n") }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI), SOS_PICK)
            } catch (_: Exception) { toast("Contacts नहीं खुले — नंबर टाइप करें") }
        }, button("नंबर टाइप करें") {
            if (Sos.contacts(this).size >= Sos.MAX_CONTACTS) { toast("5 नंबर पूरे — पहले कोई हटाएँ"); return@button }
            ask("नया नंबर", "") { v -> val before = Sos.contacts(this).size; val l = Sos.Rules.add(Sos.contacts(this), v); save(l); toast(if (l.size > before) "जुड़ गया" else "नंबर सही नहीं (कम से कम 10 अंक) या पहले से है") }
        })
        row(box, button("नंबर सेव करें") {
            val l = Sos.Rules.parse(ed.text.toString())
            save(l)
            toast("${l.size} नंबर सेव")
        }, button("SOS रोकें") { toast(Sos.stop()) })
        // "चालू करते ही कुछ नहीं होता" - अब सीधे यहाँ से भी शुरू, और गिनती में बड़ा रद्द बटन
        row(box, button("SOS अभी शुरू करें") {
            if (!FeatureFlags.on(this, FeatureFlags.SOS_ON)) { toast("पहले ऊपर \"इमरजेंसी SOS चालू\" करें"); return@button }
            askMissing(61, *sosPerms)
            toast(Sos.start(this))
        }, button("रद्द करें (गिनती में)") { toast(Sos.stop()) })
        flagSwitch(box, "सबको पूरी 5 बार कॉल (बंद = कोई उठा ले तो वहीं रुको)", FeatureFlags.SOS_CALL_ALL)
        hint(box, "क्रम: ${Sos.Rules.COUNTDOWN_SEC} सेकंड की गिनती (\"SOS रोको\" बोलें या \"रद्द करें\" दबाएँ तो कुछ नहीं जाता) → ताज़ा GPS लोकेशन (10 सेकंड तक) → सबको SMS + WhatsApp → फिर हर नंबर पर क्रम से 5-5 बार कॉल. " +
            "SMS, कॉल, लोकेशन, फ़ोन-स्थिति, call log और Contacts की इजाज़त चाहिए - चालू करते ही माँगी जाती है. WhatsApp अपने-आप भेजने के लिए Accessibility में DK चालू हो. " +
            "WhatsApp पर Google Maps का लिंक जाता है (असली \"लाइव लोकेशन\" शेयर नहीं).")
    }
}

/** 15 नवंबर 2026 (हिस्सा 4): Contacts picker का नतीजा ([SettingsActivity.onActivityResult]) */
internal const val SOS_PICK = 6107
internal var sosPick: ((String) -> Unit)? = null

internal fun SettingsActivity.sosPicked(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
    if (requestCode != SOS_PICK) return
    val cb = sosPick; sosPick = null
    if (resultCode != android.app.Activity.RESULT_OK || cb == null) return
    val uri = data?.data ?: return
    try {
        contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { cb(it) }
        }
    } catch (_: Exception) { toast("नंबर नहीं पढ़ पाई — टाइप करें") }
}

// ------------------------------------------------------------------ ऑटोमेशन → Accessibility
internal fun SettingsActivity.secDriving() {
    card("ड्राइविंग मोड", "गाड़ी चलाते समय आने वाली कॉल अपने-आप कटे और उसी नंबर पर आपका SMS जवाब जाए") { box ->
        flagSwitch(box, "ड्राइविंग मोड चालू", FeatureFlags.DRIVING_MODE) { on ->
            if (on) askMissing(62, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
        }
        val ed = field(box, "जवाब वाला SMS ({user_name} = आपका नाम)", FeatureFlags.str(this, DrivingMode.MSG, DrivingMode.DEFAULT_MSG), DrivingMode.DEFAULT_MSG, multiLine = true)
        row(box, button("संदेश सेव करें") {
            FeatureFlags.putStr(this, DrivingMode.MSG, ed.text.toString().trim().ifBlank { DrivingMode.DEFAULT_MSG }); toast("सेव हो गया")
        })
        hint(box, "बोलकर: \"ड्राइविंग मोड चालू करो\" / \"ड्राइविंग मोड बंद करो\"। एक ही नंबर को 10 मिनट में दोबारा SMS नहीं जाता।")
    }
}

internal fun SettingsActivity.secEvents() {
    card("ऑटो इवेंट ट्रिगर", "चार्जर, Wi-Fi या बैटरी सेवर बदले तो DK बोलकर बताए") { box ->
        flagSwitch(box, "ऑटो इवेंट ट्रिगर (मुख्य स्विच)", FeatureFlags.EVENTS_ON)
        for ((k, t) in listOf(
            FeatureFlags.EV_CHARGER_ON to "चार्जर लगा", FeatureFlags.EV_CHARGER_OFF to "चार्जर हटा",
            FeatureFlags.EV_WIFI_ON to "Wi-Fi जुड़ा", FeatureFlags.EV_WIFI_OFF to "Wi-Fi टूटा",
            FeatureFlags.EV_BATTERY_SAVER to "बैटरी सेवर बदला",
        )) flagSwitch(box, t, k)
        flagSwitch(box, "फ़ोन चालू होते ही ये ट्रिगर फिर से शुरू", FeatureFlags.START_ON_BOOT)
        hint(box, "मुख्य स्विच बंद = कोई receiver नहीं चलता (बैटरी पर असर नहीं)।")
    }
}

// ------------------------------------------------------------------ दिमाग और आवाज़ → कोडिंग एजेंट
internal fun SettingsActivity.secTermux() {
    card("Termux ऑटोमेशन", "DK, Termux (F-Droid) में कमांड / लोकल सर्वर चला सके - RUN_COMMAND इजाज़त से") { box ->
        flagSwitch(box, "Termux ऑटोमेशन चालू", FeatureFlags.TERMUX) { on -> if (on) askMissing(63, Termux.PERMISSION) }
        val st = status(box)
        fun show() {
            st.text = "Termux: " + (if (Termux.installed(this)) "इंस्टॉल ✓" else "इंस्टॉल नहीं") + " • इजाज़त: " + (if (Termux.permitted(this)) "मिली ✓" else "नहीं")
        }
        show()
        row(box, button("इजाज़त माँगें") { askMissing(63, Termux.PERMISSION); show() }, button("जाँच कमांड चलाएँ") {
            if (!FeatureFlags.on(this, FeatureFlags.TERMUX)) { toast("पहले \"Termux ऑटोमेशन चालू\" करें"); return@button }
            toast(Termux.run(this, "echo DK AI > ~/dk_ai_test.txt", background = true) ?: "भेज दिया - Termux में ~/dk_ai_test.txt देखें"); show()
        })
        hint(box, "Termux में एक बार चलाएँ:\nmkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties")
    }
}

// ------------------------------------------------------------------ दिमाग और आवाज़ → DK कंप्यूटर
internal fun SettingsActivity.secImageGen() {
    card("इमेज बनाना (मुफ़्त API)", "\"एक शेर की तस्वीर बनाओ\" - DK खुद मुफ़्त API से बनाकर गैलरी में सेव करे; key वैकल्पिक") { box ->
        flagSwitch(box, "बोलकर तस्वीर बनाना चालू", FeatureFlags.IMAGE_GEN)
        flagSwitch(box, "बनी तस्वीरें गैलरी में नहीं, DK के प्राइवेट हिस्से में रखें", FeatureFlags.FACE_VAULT)
        label(box, "पहले कौन सा रास्ता आज़माएँ (न चले तो बाकी, जिनकी key भरी है):")
        val opts = listOf(ImageGen.AUTO to "अपने-आप (जिसकी key है)") + ImageGen.Provider.values().filter { it != ImageGen.Provider.CUSTOM }.map { it.id to it.title }
        val cur = Prefs.str(this, ImageGen.PROVIDER, ImageGen.AUTO)
        val rg = RadioGroup(this)
        opts.forEachIndexed { i, (id, t) ->
            rg.addView(RadioButton(this).apply { this.id = 5100 + i; text = t; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); isChecked = id == cur })
        }
        rg.setOnCheckedChangeListener { _, idc -> opts.getOrNull(idc - 5100)?.let { (id, _) -> Prefs.edit(this) { putString(ImageGen.PROVIDER, id) } } }
        box.addView(rg)
        val keys = LinkedHashMap<ImageGen.Provider, android.widget.EditText>()
        for (p in ImageGen.Provider.values().filter { it != ImageGen.Provider.CUSTOM }) {
            keys[p] = field(box, "${p.title} key" + if (p.keyNeeded) " (मुफ़्त)" else " (वैकल्पिक - बिना key भी चलता है)", Prefs.str(this, p.keyPref, ""), "key पेस्ट करें", secret = true)
            if (p.keyUrl.isNotBlank()) row(box, button("${p.title}: मुफ़्त key लें") { openLink(p.keyUrl) })
        }
        val hf = field(box, "Hugging Face model (खाली = ${ImageGen.HF_DEFAULT_MODEL})", Prefs.str(this, ImageGen.HF_MODEL, ""), ImageGen.HF_DEFAULT_MODEL)
        fun saveImg() = Prefs.edit(this) {
            keys.forEach { (p, e) -> putString(p.keyPref, e.text.toString().trim()) }
            putString(ImageGen.HF_MODEL, hf.text.toString().trim())
        }
        val out = status(box)
        row(box, button("सेव करें") { saveImg(); toast("सेव हो गया") }, button("जाँच: एक इमेज बनाओ") {
            saveImg()
            out.text = "बन रही है… (AI Horde पर 1-2 मिनट लग सकते हैं)"
            lifecycleScope.launch {
                val r = ImageGen.generate(this@secImageGen, "a cute robot reading a book, digital art")
                out.text = r.error ?: "बन गई (${r.via}) और सेव हो गई ✓"
            }
        })
        hint(box, "बिना key भी AI Horde से बनती है (थोड़ी धीमी)। Keys फ़ोन के ताले (Android Keystore) में रहती हैं, कहीं भेजी नहीं जातीं।")
    }
}

// ------------------------------------------------------------------ सोशल और कहानी → सोशल रिव्यू
internal fun SettingsActivity.secYouTube() {
    card("YouTube चैनल जाँच", "\"मेरे चैनल का हाल बताओ\" - subscribers, views, नए वीडियो, comments (सिर्फ़ पढ़ना)") { box ->
        flagSwitch(box, "बोलकर चैनल जाँच चालू", FeatureFlags.YT_CHANNEL)
        val h = field(box, "आपका चैनल (@handle, channel ID या लिंक)", Prefs.str(this, YtChannel.HANDLE, ""), "@mychannel")
        val k = field(box, "YouTube Data API key (मुफ़्त, वैकल्पिक)", Prefs.str(this, YtChannel.KEY, ""), "पक्के आँकड़े और comments के लिए", secret = true)
        fun saveYt() = Prefs.edit(this) { putString(YtChannel.HANDLE, h.text.toString().trim()); putString(YtChannel.KEY, k.text.toString().trim()) }
        val out = status(box)
        row(box, button("सेव करें") { saveYt(); toast("सेव हो गया") }, button("अभी जाँचें") {
            saveYt(); out.text = "देख रही हूँ…"
            lifecycleScope.launch {
                val r = YtChannel.check(this@secYouTube)
                out.text = r.error ?: r.report?.let { YtChannel.text(it) } ?: "कुछ नहीं मिला"
            }
        })
        row(box, button("मुफ़्त key लें (Google Cloud)") { openLink(YtChannel.KEY_URL) })
        hint(box, "बिना key: नाम, subscriber (जितना पेज पर दिखे) और नए 5 वीडियो के views। key से: पक्के आँकड़े + नए वीडियो के comments।")
    }
}
