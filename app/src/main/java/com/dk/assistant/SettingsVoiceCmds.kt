package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.voice.VoiceCmdMatch
import com.dk.assistant.voice.VoiceCmdMatch.Cmd
import com.dk.assistant.voice.VoiceCmds
import com.dk.assistant.voice.VoiceTape
import com.dk.assistant.voice.VoiceTapeRec

/**
 * नवंबर 2026 (मालिक): Settings → फ़ोन लॉक / अनलॉक → "बिना इंटरनेट वाली आवाज़-कमांड"।
 * मालिक हर वाक्य 5 बार बोलकर सिखाए - फिर वो वाक्य फ़ोन पर ही पहचाना जाता है (न AI, न API, न इंटरनेट)।
 * लॉक / अनलॉक सिर्फ़ मालिक की आवाज़ पर (अनलॉक कड़ी जाँच से), "किधर हो" किसी की भी आवाज़ पर - फ़ोन 60 सेकंड बजे।
 *
 * v4.6 मालिक: "टेक्स्ट हटाकर 30 सेकंड का वॉइस रिकॉर्डिंग - उसमें 5 बार बोला जाएगा; लॉक का अलग, अनलॉक का अलग;
 * 'डीके कहाँ हो, फोन बजाओ' में भी; एक बार रिकॉर्ड करके रख दिया तो सेव; ऑफलाइन हो या ऑनलाइन"।
 * अब हर कमांड की एक लगातार 30 सेकंड की रिकॉर्डिंग ([VoiceTapeRec]) - DK खुद हर बार अलग काटता है.
 * साथ में "डीके फोन उठा लो" / "उठा के स्पीकर पर डाल दो" (सिर्फ़ घंटी के समय).
 */
internal fun SettingsActivity.secVoiceCmds() {
    val rows = HashMap<Cmd, TextView>()
    fun refresh() { for ((k, tv) in rows) tv.text = UiLang.s(VoiceCmds.status(this, k)) }

    card("आवाज़ रिकॉर्ड करके सिखाएँ", "लॉक, अनलॉक, \"DK कहाँ हो\", कॉल उठाना — अपनी आवाज़ में, इंटरनेट हो या न हो", startOpen = true) { box ->
        if (!Setup.accessibilityOn(this)) hint(box, "⚠ पहले Settings → Accessibility में DK चालू करें — उसके बिना ये कमांड नहीं चलेंगी।")
        hint(box, "हर कमांड की 30 सेकंड की एक रिकॉर्डिंग: जो मन करे वो बोलिए, जैसे मन करे वैसे — बस वही बात ${VoiceTape.TIMES} बार, हर बार के बाद 2 सेकंड रुककर। " +
            "DK वही याद रखेगा। रिकॉर्डिंग सेव रहती है और \"सुनें\" से दोबारा सुन सकते हैं। दूर से या धीरे बोलेंगे तब भी पहचान लेगा। " +
            "लॉक / अनलॉक सिर्फ़ आपकी आवाज़ पर होगा (अनलॉक सबसे कड़ी जाँच से)। \"DK कहाँ हो\" कोई भी पूछे तो फ़ोन 60 सेकंड पूरी आवाज़ में बजेगा। " +
            "कॉल उठाने वाली कमांड सिर्फ़ तब चलती है जब कॉल की घंटी बज रही हो।")
        for (k in Cmd.values()) {
            label(box, k.hi)
            val st = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), 0, 0, (4 * dp).toInt()) }
            box.addView(st); rows[k] = st
            val sw = switch(box, "चालू", Prefs.voiceCmdOn(this, k.id))
            sw.setOnCheckedChangeListener { _, on ->
                VoiceCmds.setOn(this, k, on)
                if (on) DKAccessibilityService.instance?.startWakeLoopIfEnabled()
                refresh()
            }
            row(box,
                button("30 सेकंड रिकॉर्ड करें") { stopVoiceCmdPlay(); enrollVoiceCmd(k) { refresh() } },
                button("सुनें") { playVoiceCmd(k) },
                button("हटाओ") { stopVoiceCmdPlay(); VoiceCmds.forget(this, k); refresh(); toast("\"${k.hi}\" वाली आवाज़ हटा दी") })
        }
        refresh()
        row(box, button("अभी बजाकर देखें") { toast(FindPhone.start(this, "Settings test")) })
        hint(box, "ज़रूरी: आवाज़ → \"Hey DK\" से बुलाना (wake word) चालू रहे — DK तभी पीछे सुनता है। Live चालू हो तो भी पहचान पहले फ़ोन पर ही होती है।")
    }
}

/**
 * रिकॉर्डिंग के समय की स्क्रीन: बचा समय, कितनी बार, माइक-मीटर.
 * v4.7 मालिक: "क्या बोलना है वो नहीं दिखेगा - जो मन करेगा, जैसा मन करेगा, वो बोलेगा" - कोई तय वाक्य नहीं दिखाते.
 */
internal fun voiceTapeText(cmd: Cmd, t: VoiceTapeRec.Tick?): String {
    val head = UiLang.s("${cmd.hi} — 30 सेकंड") + "\n\n" +
        UiLang.s("जो मन करे वो बोलिए — बस वही बात ${VoiceTape.TIMES} बार, हर बार के बाद 2 सेकंड रुकिए।") + "\n" +
        UiLang.s("फ़ोन को मुँह से एक हाथ दूर रखें, आम आवाज़ में बोलें।")
    if (t == null) return head + "\n\n" + UiLang.s("तैयार हो जाइए… (कमरे का शोर सुन रहा हूँ)")
    val bars = (t.level * 12).toInt().coerceIn(0, 12)
    return head + "\n\n" + UiLang.s(VoiceTape.progress(t.leftMs, t.got)) + "\n" + UiLang.s("माइक:") + " " + "▮".repeat(bars) + "▯".repeat(12 - bars) +
        (if (t.note.isNotBlank()) "\n" + UiLang.s(t.note) else "")
}

private var voiceCmdPlayer: android.media.MediaPlayer? = null

internal fun stopVoiceCmdPlay() {
    try { voiceCmdPlayer?.stop() } catch (_: Exception) {}
    try { voiceCmdPlayer?.release() } catch (_: Exception) {}
    voiceCmdPlayer = null
}

/** v4.7 मालिक: "जो रिकॉर्ड करें वह ऑडियो दोबारा सुनने का ऑप्शन हो" - दोबारा दबाओ = रुक जाए */
internal fun SettingsActivity.playVoiceCmd(cmd: Cmd) {
    if (voiceCmdPlayer != null) { stopVoiceCmdPlay(); return }
    val f = VoiceCmds.audioFile(this, cmd)
    if (!VoiceCmds.hasAudio(this, cmd)) {
        toast(if (VoiceCmds.trained(this, cmd)) "यह आवाज़ पुराने तरीके से सिखाई थी — सुनने के लिए एक बार फिर 30 सेकंड रिकॉर्ड करें" else "अभी कुछ रिकॉर्ड नहीं किया")
        return
    }
    try {
        val mp = android.media.MediaPlayer()
        mp.setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        mp.setDataSource(f.absolutePath)
        mp.setOnCompletionListener { stopVoiceCmdPlay() }
        mp.prepare(); mp.start()
        voiceCmdPlayer = mp
        toast("\"${cmd.hi}\" की रिकॉर्डिंग चल रही है (रोकने को फिर \"सुनें\")")
    } catch (e: Exception) { stopVoiceCmdPlay(); DKLog.d("आवाज़-कमांड बजाना: ${e.javaClass.simpleName} ${e.message?.take(160)}"); toast("चला नहीं पाया") }
}

internal fun SettingsActivity.enrollVoiceCmd(cmd: Cmd, done: () -> Unit) {
    val HD = com.dk.assistant.voice.HeyDk
    val OV = com.dk.assistant.voice.OwnerVoice
    if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
    val (dlg, tv) = ownerDialog("आवाज़ रिकॉर्ड करें")
    ownerRun {
        try {
            if (cmd.ownerOnly && OV.supported && !OV.hasModel(this@enrollVoiceCmd)) {
                tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…")
                val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OV.download(this@enrollVoiceCmd) { pct -> runOnUiThread { tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…") + " $pct%" } }
                }
                if (err != null) { tv.text = UiLang.s(err); kotlinx.coroutines.delay(2500) }
            }
            val src = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
            var takes: List<ByteArray> = emptyList()
            var noise = ByteArray(0); var quiet = false
            var why: String? = null
            withDkMicPaused {
                tv.text = voiceTapeText(cmd, null)
                kotlinx.coroutines.delay(1200)
                var lastUi = 0L
                val r = VoiceTapeRec.record(src, ok = { pcm -> HD.spokenFrames(pcm) in VoiceCmdMatch.MIN_FRAMES..VoiceCmdMatch.MAX_FRAMES }) { t ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 120 || t.got >= VoiceTape.TIMES) { lastUi = now; runOnUiThread { tv.text = voiceTapeText(cmd, t) } }
                }
                if (!r.micOk) { why = "माइक नहीं खुला — कोई दूसरा ऐप माइक इस्तेमाल कर रहा है?"; return@withDkMicPaused }
                why = VoiceTape.verdict(r.takes.size, r.tooShort, r.tooLong)
                takes = r.takes; noise = r.noise; quiet = r.quiet
            }
            if (why != null) { tv.text = UiLang.s(why!!) + "\n\n" + UiLang.s("\"30 सेकंड रिकॉर्ड करें\" फिर दबाइए।"); return@ownerRun }
            tv.text = UiLang.s("सीख रहा हूँ…") + " (${takes.size} " + UiLang.s("बार") + ")"
            // एक बार बाकियों से बहुत अलग (खाँसी / बीच में कोई और बोला) = उसे छोड़ दो, बाकी से सीखो
            val keep = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val bad = if (takes.size > VoiceTape.MIN_TIMES) VoiceCmdMatch.outlier(takes.map { HD.featuresOf(it) }) else -1
                if (bad >= 0) takes.filterIndexed { i, _ -> i != bad } else takes
            }
            val (err, warn) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { VoiceCmds.save(this@enrollVoiceCmd, cmd, keep, noise) }
            if (err != null) { tv.text = UiLang.s(err); return@ownerRun }
            VoiceCmds.setOn(this@enrollVoiceCmd, cmd, true)
            Setup.startKeepAlive(this@enrollVoiceCmd)
            DKAccessibilityService.instance?.startWakeLoopIfEnabled()
            tv.text = UiLang.s("हो गया — आवाज़ सेव हो गई (${keep.size} बार से सीखा)। \"सुनें\" से सुनकर देखिए, फिर बोलकर आज़माइए।") + "\n\n" + UiLang.s(VoiceCmds.status(this@enrollVoiceCmd, cmd)) +
                (if (quiet) "\n\n⚠ " + UiLang.s("आवाज़ काफ़ी धीमी आई थी — पहचान कमज़ोर हो तो फ़ोन थोड़ा पास रखकर दोबारा रिकॉर्ड करें") else "") +
                (if (warn != null) "\n\n⚠ " + UiLang.s(warn) else "")
            done()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { HD.prepareAcks(this@enrollVoiceCmd) } catch (_: Exception) {}
                try { com.dk.assistant.voice.LiveAckMaker.ensureAsync(this@enrollVoiceCmd, "voice cmd") } catch (_: Exception) {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { DKLog.d("आवाज़-कमांड सिखाना: ${e.javaClass.simpleName} ${e.message?.take(160)}"); tv.text = UiLang.s("गड़बड़ हुई, फिर से कोशिश करें") }
    }
    dlg.setOnDismissListener { ownerCancel() }
}

