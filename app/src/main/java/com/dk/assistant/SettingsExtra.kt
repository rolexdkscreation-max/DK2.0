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

private fun SettingsActivity.flagSwitch(box: LinearLayout, t: String, key: String, onChange: (Boolean) -> Unit = {}) =
    switch(box, t, FeatureFlags.on(this, key)).apply {
        setOnCheckedChangeListener { _, on -> FeatureFlags.set(this@flagSwitch, key, on); onChange(on) }
    }

/** जो इजाज़त अभी नहीं मिली, सिर्फ़ वही माँगो */
private fun SettingsActivity.askMissing(code: Int, vararg perms: String) {
    val miss = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), code)
}

private fun SettingsActivity.status(box: LinearLayout): TextView = TextView(this).apply {
    textSize = 13f; setTextColor(0xB3FFFFFF.toInt()); setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, 0)
}.also { box.addView(it) }

private fun SettingsActivity.openLink(url: String) {
    if (url.isBlank()) return
    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) { toast(url) }
}

// ------------------------------------------------------------------ सुरक्षा → टच मोड
internal fun SettingsActivity.secSos() {
    card("इमरजेंसी SOS", "\"DK, activate SOS\" - 10 सेकंड की गिनती, फिर 5 लोगों को लोकेशन SMS + WhatsApp और कॉल") { box ->
        flagSwitch(box, "इमरजेंसी SOS चालू", FeatureFlags.SOS_ON) { on ->
            if (on) askMissing(61, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CALL_LOG)
        }
        val ed = field(box, "SOS नंबर (कॉमा से अलग, ज़्यादा से ज़्यादा ${Sos.MAX_CONTACTS})", FeatureFlags.str(this, Sos.CONTACTS), "98xxxxxx01, 98xxxxxx02")
        ed.inputType = InputType.TYPE_CLASS_PHONE
        val st = status(box)
        fun show() { st.text = Sos.contacts(this).let { if (it.isEmpty()) "अभी कोई नंबर नहीं" else "सेव नंबर: ${it.size}" } }
        show()
        row(box, button("नंबर सेव करें") {
            val list = Sos.Rules.parse(ed.text.toString())
            FeatureFlags.putStr(this, Sos.CONTACTS, list.joinToString(", ")); ed.setText(list.joinToString(", ")); show()
            toast("${list.size} नंबर सेव")
        }, button("SOS रोकें") { toast(Sos.stop()) })
        hint(box, "बोलकर: \"DK, activate SOS\" / \"SOS बंद करो\"। गिनती के 10 सेकंड में \"SOS बंद करो\" बोलें तो कुछ नहीं जाता। " +
            "SMS, कॉल और लोकेशन की इजाज़त चाहिए - चालू करते ही माँगी जाती है।")
    }
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
        val opts = listOf(ImageGen.AUTO to "अपने-आप (जिसकी key है)") + ImageGen.Provider.values().map { it.id to it.title }
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
        label(box, "अपनी OpenAI-compatible images API (वैकल्पिक):")
        val b = field(box, "Base URL", Prefs.str(this, ImageGen.BASE, ""), "https://…/v1")
        val k = field(box, "API key", Prefs.str(this, ImageGen.KEY, ""), "key", secret = true)
        val m = field(box, "Model", Prefs.str(this, ImageGen.MODEL, ""), "model का नाम")
        fun saveImg() = Prefs.edit(this) {
            keys.forEach { (p, e) -> putString(p.keyPref, e.text.toString().trim()) }
            putString(ImageGen.HF_MODEL, hf.text.toString().trim())
            putString(ImageGen.BASE, b.text.toString().trim().trimEnd('/')); putString(ImageGen.KEY, k.text.toString().trim()); putString(ImageGen.MODEL, m.text.toString().trim())
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
