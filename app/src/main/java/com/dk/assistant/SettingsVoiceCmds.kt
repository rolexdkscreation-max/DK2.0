package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.voice.VoiceCmdMatch
import com.dk.assistant.voice.VoiceCmdMatch.Cmd
import com.dk.assistant.voice.VoiceCmds
import kotlinx.coroutines.launch

/**
 * नवंबर 2026 (मालिक): Settings → फ़ोन लॉक / अनलॉक → "बिना इंटरनेट वाली आवाज़-कमांड"।
 * मालिक हर वाक्य 5 बार बोलकर सिखाए - फिर वो वाक्य फ़ोन पर ही पहचाना जाता है (न AI, न API, न इंटरनेट)।
 * लॉक / अनलॉक सिर्फ़ मालिक की आवाज़ पर (अनलॉक कड़ी जाँच से), "किधर हो" किसी की भी आवाज़ पर - फ़ोन 60 सेकंड बजे।
 */
internal fun SettingsActivity.secVoiceCmds() {
    val rows = HashMap<Cmd, TextView>()
    fun refresh() { for ((k, tv) in rows) tv.text = UiLang.s(VoiceCmds.status(this, k)) }

    card("बिना इंटरनेट वाली आवाज़-कमांड", "अपनी आवाज़ में सिखाएँ — फ़ोन दूर पड़ा हो तब भी, बिना AI / API", startOpen = true) { box ->
        if (!Setup.accessibilityOn(this)) hint(box, "⚠ पहले Settings → Accessibility में DK चालू करें — उसके बिना ये कमांड नहीं चलेंगी।")
        hint(box, "हर वाक्य 5 बार आम आवाज़ में बोलें। लॉक / अनलॉक सिर्फ़ आपकी आवाज़ पर होगा (अनलॉक सबसे कड़ी जाँच से)। " +
            "\"DK किधर हो\" कोई भी पूछे तो फ़ोन 60 सेकंड पूरी आवाज़ में बजेगा, फ्लैशलाइट टिमटिमाएगी — छूने, कोई बटन दबाने या \"हे DK मिल गया\" बोलने पर बंद।")
        for (k in Cmd.values()) {
            label(box, k.hi + " — \"" + k.say + "\"")
            val st = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), 0, 0, (4 * dp).toInt()) }
            box.addView(st); rows[k] = st
            val sw = switch(box, "चालू", Prefs.voiceCmdOn(this, k.id))
            sw.setOnCheckedChangeListener { _, on ->
                VoiceCmds.setOn(this, k, on)
                if (on) DKAccessibilityService.instance?.startWakeLoopIfEnabled()
                refresh()
            }
            row(box,
                button("सिखाओ") { enrollVoiceCmd(k) { refresh() } },
                button("हटाओ") { VoiceCmds.forget(this, k); refresh(); toast("\"${k.hi}\" वाली आवाज़ हटा दी") })
        }
        refresh()
        row(box, button("अभी बजाकर देखें") { toast(FindPhone.start(this, "Settings test")) })
        hint(box, "ज़रूरी: आवाज़ → \"Hey DK\" से बुलाना (wake word) चालू रहे — DK तभी पीछे सुनता है। Live चालू हो तो भी पहचान पहले फ़ोन पर ही होती है।")
    }
}

internal fun SettingsActivity.enrollVoiceCmd(cmd: Cmd, done: () -> Unit) {
    val HD = com.dk.assistant.voice.HeyDk
    val OV = com.dk.assistant.voice.OwnerVoice
    if (!Setup.hasMic(this)) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 12); return }
    val (dlg, tv) = ownerDialog("आवाज़-कमांड सिखाओ")
    ownerJob?.cancel()
    ownerJob = lifecycleScope.launch {
        try {
            if (cmd.ownerOnly && OV.supported && !OV.hasModel(this@enrollVoiceCmd)) {
                tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…")
                val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OV.download(this@enrollVoiceCmd) { pct -> runOnUiThread { tv.text = UiLang.s("पहचान वाला मॉडल डाउनलोड हो रहा है…") + " $pct%" } }
                }
                if (err != null) { tv.text = UiLang.s(err); kotlinx.coroutines.delay(2500) }
            }
            val takes = ArrayList<ByteArray>()
            val src = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
            withDkMicPaused {
                var tries = 0; var redo = -1; var outlierDone = false
                while (takes.size < VoiceCmdMatch.TAKES || redo >= 0) {
                    val n = if (redo >= 0) redo + 1 else takes.size + 1
                    val ask = UiLang.s("नमूना") + " $n/${VoiceCmdMatch.TAKES} — " + UiLang.s("आम आवाज़ में, एक ही तरह बोलिए:") + "\n\n\"" + cmd.say + "\""
                    tv.text = ask
                    val take = HD.recordTake(src, maxMs = 6_000, endMs = 900) { lv ->
                        val bars = (lv * 12).toInt().coerceIn(0, 12)
                        runOnUiThread { tv.text = ask + "\n\n" + UiLang.s("माइक:") + " " + "▮".repeat(bars) + "▯".repeat(12 - bars) }
                    }
                    val frames = if (take == null) 0 else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { HD.spokenFrames(take.pcm) }
                    if (take == null || frames !in VoiceCmdMatch.MIN_FRAMES..VoiceCmdMatch.MAX_FRAMES) {
                        tries++
                        if (tries >= 4) { tv.text = UiLang.s("आवाज़ ठीक से नहीं आई — शांत जगह पर फिर कोशिश करें"); return@withDkMicPaused }
                        tv.text = UiLang.s(if (frames > VoiceCmdMatch.MAX_FRAMES) "बहुत लंबा हो गया — सिर्फ़ ऊपर वाला वाक्य बोलिए" else "आवाज़ नहीं पकड़ी या बहुत छोटा — पूरा वाक्य साफ़ बोलिए")
                        kotlinx.coroutines.delay(1500); continue
                    }
                    tries = 0
                    if (redo >= 0) { takes[redo] = take.pcm; redo = -1 } else takes.add(take.pcm)
                    if (takes.size >= VoiceCmdMatch.TAKES && !outlierDone) {
                        outlierDone = true
                        tv.text = UiLang.s("सीख रहा हूँ…")
                        val bad = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { VoiceCmdMatch.outlier(takes.map { HD.featuresOf(it) }) }
                        if (bad >= 0) {
                            tv.text = UiLang.s("एक नमूना बाकियों से अलग लगा — उसे एक बार फिर बोलिए"); kotlinx.coroutines.delay(1200)
                            redo = bad; continue
                        }
                    }
                    tv.text = UiLang.s("ठीक है") + " ✓"
                    kotlinx.coroutines.delay(600)
                }
            }
            if (takes.size < VoiceCmdMatch.TAKES) return@launch
            tv.text = UiLang.s("सीख रहा हूँ…")
            val (err, warn) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { VoiceCmds.save(this@enrollVoiceCmd, cmd, takes) }
            if (err != null) { tv.text = UiLang.s(err); return@launch }
            VoiceCmds.setOn(this@enrollVoiceCmd, cmd, true)
            Setup.startKeepAlive(this@enrollVoiceCmd)
            DKAccessibilityService.instance?.startWakeLoopIfEnabled()
            tv.text = UiLang.s("हो गया — अब बोलकर देखिए।") + "\n\n" + UiLang.s(VoiceCmds.status(this@enrollVoiceCmd, cmd)) +
                (if (warn != null) "\n\n⚠ " + UiLang.s(warn) else "")
            done()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { HD.prepareAcks(this@enrollVoiceCmd) } catch (_: Exception) {}
                try { com.dk.assistant.voice.LiveAckMaker.ensureAsync(this@enrollVoiceCmd, "voice cmd") } catch (_: Exception) {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { tv.text = UiLang.s("गड़बड़ हुई") + " (${e.javaClass.simpleName})" }
    }
    dlg.setOnDismissListener { ownerJob?.cancel() }
}
