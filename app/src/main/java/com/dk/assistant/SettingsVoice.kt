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

// SettingsActivity का हिस्सा: आवाज़: Hey DK, आवाज़ सिखाना, बोलने वाला इंजन, बात शुरू करना.
// यह code पहले SettingsActivity.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state SettingsActivity में)।

/** Key jud gayi -> seedha baatcheet shuru */
internal fun SettingsActivity.startTalking() {
    val svc = DKAccessibilityService.instance
    if (svc == null) { toast("✓ Key तैयार — अब Accessibility में DK चालू करें, फिर ओर्ब दबाकर बोलिए"); return }
    if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
    svc.startLive()
    toast("✓ तैयार — बोलिए, DK सुन रहा है")
    try { startActivity(android.content.Intent(this, VoiceActivity::class.java)) } catch (_: Exception) {}
}

internal fun SettingsActivity.heyDkCard(box: LinearLayout) {
    val HD = com.dk.assistant.voice.HeyDk
    val status = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt()) }
    box.addView(status)
    val swOn = switch(box, "\"Hey DK\" पर जागो", Prefs.heyDkOn(this))
    fun refresh() {
        status.text = UiLang.s(HD.status(this)) + if (!Setup.batteryExempt(this)) "\n" + UiLang.s("बैटरी की रोक लगी है — नीचे \"बैटरी की रोक हटाओ\" दबाइए, वरना फ़ोन कुछ देर बाद DK को सुला सकता है") else ""
        swOn.isEnabled = HD.trained(this)
    }
    swOn.setOnCheckedChangeListener { _, on ->
        val ok = on && HD.trained(this)
        Prefs.edit(this) {
            putBoolean("hey_dk_on", ok)
            if (ok) { putBoolean("wake_mode", true); putBoolean("mic_user_off", false) }
        }
        if (ok) {
            if (swWakeReady) swWake.isChecked = true
            Setup.startKeepAlive(this)
            DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        }
        refresh()
    }
    label(box, "पहचान कितनी आसानी से हो")
    val senses = com.dk.assistant.voice.WakeMatch.Sense.values().toList()
    val rgSense = radios(box, senses.map { it.name to it.label }, Prefs.heyDkSense(this).name, 2750)
    rgSense.setOnCheckedChangeListener { _, _ ->
        Prefs.edit(this) { putString("hey_dk_sense", radioValue(rgSense, senses.map { it.name }, 2750)) }
    }
    val edAcks = field(box, "जागते ही क्या बोले (कॉमा से अलग, बदल-बदलकर बोलेगा)", Prefs.heyDkAcks(this),
        com.dk.assistant.voice.WakeMatch.DEFAULT_ACKS.joinToString(", "))
    row(box,
        button("Hey DK सिखाओ") { enrollHeyDk { swOn.isChecked = Prefs.heyDkOn(this); refresh() } },
        button("जवाब सेव करो") {
            Prefs.edit(this) { putString("hey_dk_acks", edAcks.text.toString().trim()) }
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { HD.prepareAcks(this@heyDkCard) } catch (_: Exception) {} }
            toast("सेव हो गया")
        })
    // v3.81: सिखाना न चले तो - "हे डीके / हेलो डीके" बोलकर (पुराना इंटरनेट वाला सुनना + "हाँ सर")
    row(box, button("बिना सिखाए चलाओ") {
        Prefs.edit(this) { putBoolean("wake_mode", true); putBoolean("mic_user_off", false); putBoolean("hey_dk_on", false) }
        if (swWakeReady) swWake.isChecked = true
        swOn.isChecked = false
        Setup.startKeepAlive(this)
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
        refresh()
        toast("अब \"हे डीके\" या \"हेलो डीके\" बोलिए")
    })
    row(box,
        button("बैटरी की रोक हटाओ") { Setup.requestBatteryExempt(this) },
        button("मिटाओ") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("Hey DK मिटाएँ?"))
                .setMessage(UiLang.s("सीखे हुए नमूने मिट जाएँगे। दोबारा चालू करने के लिए फिर से सिखाना होगा।"))
                .setPositiveButton(UiLang.s("मिटाओ")) { _, _ -> HD.forget(this); swOn.isChecked = false; refresh(); toast("Hey DK मिटा दिया") }
                .setNegativeButton(UiLang.s("रहने दो"), null).show()
        })
    refresh()
    hint(box, "कैसे काम करता है: आप 5 बार \"Hey DK\" बोलते हैं (या \"Hello DK\" — जो भी चुनें, पाँचों बार वही)। फ़ोन उससे आपके बोलने का तरीका और आपकी आवाज़ की पहचान बनाता है। सुनते समय हर छोटी बात फ़ोन के अंदर ही मिलाई जाती है — न मिले तो भूल जाता है, कहीं भेजता नहीं।")
    hint(box, "\"Hey DK, WhatsApp खोलो\" एक साँस में भी बोल सकते हैं — तब DK सीधे वही करेगा। सिर्फ़ \"Hey DK\" कहा तो \"हाँ सर\" बोलकर सुनेगा, और करीब 30 सेकंड चुप रहने पर फिर सो जाएगा।")
    hint(box, "सिखाना न हो पाए तो \"बिना सिखाए चलाओ\" दबाइए: तब \"हे डीके, मौसम बताओ\" या सिर्फ़ \"हेलो डीके\" बोलने पर DK \"हाँ सर\" बोलकर सुनेगा। पर यह तरीका आपकी बात इंटरनेट (Groq/Gemini) से पहचानता है, थोड़ा धीमा है और किसी की भी आवाज़ पर जाग सकता है।")
    hint(box, "सीमाएँ: ऊपर DK की notification हमेशा दिखेगी (Android का नियम)। बैटरी थोड़ी ज़्यादा लगती है। Force stop करने या फ़ोन बंद होने पर नहीं सुनेगा। कॉल के दौरान माइक कॉल का होता है। यह फ़ोन का ताला नहीं है — आपकी आवाज़ की रिकॉर्डिंग से धोखा हो सकता है।")
}

/** DK का माइक थोड़ी देर बंद (Live/सुनना माइक छोड़े), फिर जैसा था */
internal suspend fun <T> SettingsActivity.withDkMicPaused(block: suspend () -> T): T {
    val was = DKState.micMuted
    if (!was) { DKState.setMicMuted(true); kotlinx.coroutines.delay(700) }
    return try { block() } finally { if (!was) DKState.setMicMuted(false) }
}

// v3.99: चेहरा पहचान (फ़ेस वेरिफ़िकेशन) ऐप से हटाई - मालिक की माँग. FaceAI वगैरह की फ़ाइलें/tests अंदर हैं, कहीं से चलती नहीं.

internal fun SettingsActivity.secVoice() {
    // v3.69: पुराना "आवाज़" हिस्सा (महिला/पुरुष, आवाज़ का प्रकार, सूची, अंदाज़, स्पीड, पिच, फ़ोन TTS) हटाया -
    // बैकअप: branch backup/dk-before-backup-folder-cleanup-1102, फ़ोल्डर backup/v3.69-settings/. अब सिर्फ़ DK लाइव का बोलने वाला इंजन.
    card("बोलने वाला इंजन (DK Live)", "DK लाइव (Direct Gemini Live Voice, डिफ़ॉल्ट) • Gemini TTS (बैकअप) — एक समय में एक") { box ->
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
        // v4.7: दूर की आवाज़ अनसुनी (बिना आवाज़ सिखाए) + जवाब के बीच बोलें तो DK रुके
        switch(box, "दूर की आवाज़ अनसुनी करो (फ़ोन के पास धीरे या ज़ोर से - दोनों सुनेगा)", Prefs.ownerFarOn(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("owner_far_on", on) } }
        hint(box, "DK आपकी पहली कुछ बातों से आपकी पास वाली आवाज़ अपने आप सीखता है। उससे बहुत धीमी (दूसरे कमरे / दूर की) आवाज़ पर जवाब नहीं देगा। Bluetooth headset पर यह रोक बंद रहती है।")
        switch(box, "DK के जवाब के बीच बोलूँ तो DK रुककर मेरी बात सुने", !Prefs.bargeHold(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("barge_hold", !on) } }
        // नवंबर 2026: बीच बातचीत text (1011 की जड़) - डिफ़ॉल्ट बंद
        switch(box, "Gemini न सुने तो लिखा हुआ text भेजकर बचाओ (पुराना तरीका)", LiveStay.textRescueOn(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean(LiveStay.PREF_TEXT_RESCUE, on) } }
        hint(box, "डिफ़ॉल्ट बंद। चालू करने पर बातचीत के बीच text जाता है, जिससे Live का connection (1011) कट सकता है। अगली Live बातचीत से लागू।")
        hint(box, "आवाज़ की keys (Gemini Live, बोलना, सुनना): सेटिंग्स → AI दिमाग → DK लाइव। अभी: " + scopeStatus(ApiScopes.LIVE))
    }

    // ---- v3.81: "Hey DK" - फ़ोन के अंदर, सिर्फ़ मालिक की आवाज़ पर जागना
    card("Hey DK (फ़ोन के अंदर)", "ऐप बंद हो तब भी \"Hey DK\" बोलिए — DK \"हाँ सर\" बोलकर सुनने लगेगा. इंटरनेट पर कुछ नहीं जाता") { box -> heyDkCard(box) }

    // ---- v3.67: WhatsApp तुरंत भेजो (मालिक की मर्ज़ी, default बंद)
    card("WhatsApp संदेश", "\"राहुल को WhatsApp पर लिखो …\" कहने पर DK खुद भेजता है और भेजने के बाद ही \"भेज दिया\" बोलता है") { box ->
        switch(box, "बिना पूछे तुरंत भेजो (बंद = भेजने से पहले \"भेज दूँ?\" पूछो)", Prefs.waAutoSend(this))
            .setOnCheckedChangeListener { _, on -> Prefs.edit(this) { putBoolean("wa_auto_send", on) } }
        hint(box, "चालू करने पर भी नाम साफ़ न मिले (दो मिलते-जुलते नाम) तो DK पूछेगा। भेजने के बाद DK जाँचता है कि संदेश सच में चैट में गया।")
    }

    // ---- टेम्पलेट कमांड (साँचे): बिना API key फ़ोन कंट्रोल
    card("टेम्पलेट कमांड", "\"व्हाट्सएप ओपन करो ___ को ___ मैसेज भेज दो\" जैसे साँचे — बिना API key, बिना AI") { box ->
        row(box, button("टेम्पलेट कमांड खोलें") { startActivity(android.content.Intent(this, com.dk.assistant.template.TemplateSettingsActivity::class.java)) })
        hint(box, "हर साँचे को चालू/बंद करें, अपने नए साँचे जोड़ें, बदलें या हटाएँ।")
    }

    // ---- ऑटोनोमस मोड (अलग स्क्रीन; शुरू में बंद)
    card("ऑटोनोमस मोड", "DK खुद तय करे कि काम कोडिंग का है या फ़ोन के ऐप का, और खुद करे — शुरू में बंद") { box ->
        row(box, button("ऑटोनोमस मोड खोलें") { startActivity(android.content.Intent(this, com.dk.assistant.auto.AutoSettingsActivity::class.java)) })
        hint(box, "यहाँ से चालू/बंद, कौन-से काम, अपने काम, पुष्टि, अलग API keys, इमरजेंसी स्टॉप और हर कदम का लॉग।")
    }

    // ---- v3.88: Memory (फ़ोन की लोकल याददाश्त)
    card("Memory (याददाश्त)", "क्या-क्या सेव है देखें, कोई एक चीज़ या सारी मेमोरी मिटाएँ — सब सिर्फ़ इसी फ़ोन में") { box ->
        row(box, button("मेमोरी खोलें") { startActivity(android.content.Intent(this, MemoryActivity::class.java)) })
        hint(box, "हर बातचीत 30 दिन फ़ोन में रहती है, फिर अपने-आप मिटती है (उससे पहले ज़रूरी बातों का सार बनता है जो हमेशा रहता है)। बोलकर भी कह सकते हैं: \"ये भूल जाओ\"। पासवर्ड और बैंक की बातें सेव नहीं होतीं।")
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

internal fun SettingsActivity.playFace(v: OrbView, back: String) {
    val face = if (Prefs.voiceGender(this) == "male") "face_male" else "face_female"
    if (v.previewStyle == face && back != face) { v.previewStyle = back; return }
    v.previewStyle = face
    v.postDelayed({ if (v.previewStyle == face && back != face) v.previewStyle = back }, 9000)
}
