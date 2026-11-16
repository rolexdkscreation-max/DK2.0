package com.dk.assistant.wake

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.Prefs
import com.dk.assistant.SettingsActivity
import com.dk.assistant.Setup
import com.dk.assistant.UiLang
import com.dk.assistant.askMissing
import com.dk.assistant.button
import com.dk.assistant.hint
import com.dk.assistant.label
import com.dk.assistant.radioValue
import com.dk.assistant.radios
import com.dk.assistant.row
import com.dk.assistant.switch
import com.dk.assistant.toast
import com.dk.assistant.voice.VgRules
import com.dk.assistant.voice.VoiceEnroll
import com.dk.assistant.voice.VoiceGuardian
import com.dk.assistant.voice.VoicePrint
import com.dk.assistant.withDkMicPaused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 16 नवंबर 2026 (मालिक): "हे डीके / हेलो डीके" वेक वर्ड की सेटिंग (आवाज़ section).
 * कोई key / API का खाना नहीं. हर बदलाव तुरंत सेव, और सुनने वाला loop हर ~1 सेकंड में नई सेटिंग पढ़ लेता है.
 */
internal fun SettingsActivity.wakeCard(box: LinearLayout) {
    val c = this
    val dp = resources.displayMetrics.density
    fun tv(size: Float = 14f, alpha: Int = 0xFF) = TextView(this).apply {
        textSize = size; setTextColor((alpha shl 24) or 0xFFFFFF); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
    }
    fun kick() {
        if (WakeWord.on(c) && !Prefs.micUserOff(c)) Setup.startKeepAlive(c)
        DKAccessibilityService.instance?.startWakeLoopIfEnabled()
    }

    val status = tv(); box.addView(status)

    // ---------------------------------------------------------------- मॉडल डाउनलोड (APK में नहीं)
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
    box.addView(bar)
    val dl = tv(13f, 0xB3); box.addView(dl)
    val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    box.addView(btnRow)
    var busy = false

    fun refresh() {
        val ready = WakeModels.ready(c)
        val parts = ArrayList<String>()
        parts += if (ready) "✓ मॉडल फ़ोन में (v${WakeModels.version(c)}) - अब बिना इंटरनेट चलता है"
                 else "✗ मॉडल अभी फ़ोन में नहीं (कुल ${"%.1f".format(WakeModels.totalBytes(c) / 1_048_576.0)} MB, एक बार डाउनलोड)"
        parts += if (!WakeWord.enabled(c)) "वेक वर्ड बंद" else when {
            WakeWord.listenMode(c) == WakeRules.Listen.BUTTON -> "सिर्फ़ बटन से (वेक वर्ड नहीं सुन रहा)"
            WakeWord.words(c).isEmpty() -> "कोई शब्द चालू नहीं"
            !WakeWord.allowedNow(c) -> "अभी नहीं सुन रहा (${WakeWord.listenMode(c).hi})"
            !ready -> "मॉडल आने का इंतज़ार"
            else -> "सुन रहा है: " + WakeWord.words(c).joinToString(" / ") { "\"${it.hi}\"" } + (if (WakeWord.ownerOnly(c)) " • सिर्फ़ मालिक" else " • सबकी आवाज़")
        }
        if (Prefs.bool(c, WakeWord.P_FGS_BLOCK, false)) parts += WakeRules.FGS_BLOCKED
        if (!Setup.batteryExempt(c)) parts += "⚠ बैटरी की रोक लगी है - नीचे \"बैटरी की रोक हटाओ\" दबाइए, वरना फ़ोन कुछ देर बाद सुनना रोक सकता है"
        status.text = UiLang.s(parts.joinToString("\n"))
    }

    fun download(list: List<com.dk.assistant.plus.models.ModelCatalog.Asset>?, upd: WakeModels.Manifest?) {
        if (busy) return
        busy = true; bar.visibility = View.VISIBLE; bar.progress = 0
        dl.text = UiLang.s("डाउनलोड शुरू…")
        lifecycleScope.launch {
            val prog: (Long, Long) -> Unit = { d, t ->
                runOnUiThread {
                    bar.progress = if (t > 0) (d * 1000 / t).toInt() else 0
                    dl.text = UiLang.s("डाउनलोड: ${if (t > 0) d * 100 / t else 0}% (${"%.1f".format(d / 1_048_576.0)} / ${"%.1f".format(t / 1_048_576.0)} MB)")
                }
            }
            val err = if (upd != null) WakeModels.applyUpdate(c, upd, prog) else WakeModels.downloadAll(c, list ?: WakeModels.assets(c), prog)
            busy = false; bar.visibility = View.GONE
            btnRow.removeAllViews()
            if (err == null) {
                dl.text = UiLang.s("✓ डाउनलोड पूरा, जाँच (SHA-256) सही। अब बिना इंटरनेट चलेगा।")
                kick()
            } else {
                dl.text = UiLang.s("✗ $err")
                btnRow.addView(button(UiLang.s("फिर कोशिश करो")) { btnRow.removeAllViews(); download(list, upd) })
            }
            refresh()
        }
    }
    row(box,
        button(UiLang.s("मॉडल डाउनलोड करो")) {
            if (WakeModels.ready(c)) { toast("मॉडल पहले से तैयार है"); return@button }
            download(null, null)
        },
        button(UiLang.s("नया संस्करण देखो")) {
            dl.text = UiLang.s("देख रहा हूँ…")
            lifecycleScope.launch {
                val m = WakeModels.checkUpdate(c)
                btnRow.removeAllViews()
                if (m == null) dl.text = UiLang.s("✓ आपके पास सबसे नया मॉडल है (v${WakeModels.version(c)})")
                else {
                    dl.text = UiLang.s("नया मॉडल v${m.version} आया है।")
                    btnRow.addView(button(UiLang.s("अपडेट करो")) { btnRow.removeAllViews(); download(null, m) })
                }
            }
        })

    // ---------------------------------------------------------------- मुख्य स्विच + शब्द
    val swOn = switch(box, "वेक वर्ड चालू/बंद", WakeWord.enabled(c))
    swOn.setOnCheckedChangeListener { _, on ->
        if (on && !Setup.hasMic(c)) { askMissing(41, android.Manifest.permission.RECORD_AUDIO); swOn.isChecked = false; return@setOnCheckedChangeListener }
        Prefs.edit(c) { putBoolean(WakeWord.P_ON, on); if (on) putBoolean("mic_user_off", false) }
        if (on) {
            if (Build.VERSION.SDK_INT >= 33) askMissing(42, android.Manifest.permission.POST_NOTIFICATIONS)
            if (!WakeModels.ready(c)) download(null, null)
            kick()
        }
        refresh()
    }
    for (w in WakeRules.Word.values()) {
        switch(box, "\"${w.hi}\" पर जागो", WakeWord.wordOn(c, w)).setOnCheckedChangeListener { _, on ->
            Prefs.edit(c) { putBoolean(w.pref, on) }; refresh()
        }
    }

    // ---------------------------------------------------------------- सुनने का मोड
    label(box, "कब सुने")
    val modes = WakeRules.Listen.values().toList()
    val rgMode = radios(box, modes.map { it.id to it.hi }, WakeWord.listenMode(c).id, 4100)
    rgMode.setOnCheckedChangeListener { _, _ ->
        Prefs.edit(c) { putString(WakeWord.P_LISTEN, radioValue(rgMode, modes.map { it.id }, 4100)) }
        kick(); refresh()
    }
    hint(box, "बैटरी: (ख) और (ग) सबसे कम खाते हैं। (क) में भी मॉडल सिर्फ़ तब चलता है जब कमरे में कोई आवाज़ हो (बाकी समय सिर्फ़ हल्की आवाज़-जाँच)।")

    // ---------------------------------------------------------------- संवेदनशीलता (3 कदम)
    label(box, "संवेदनशीलता")
    val senses = WakeRules.Sense.values().toList()
    val senseWarn = tv(13f, 0xB3)
    val rgSense = radios(box, senses.map { it.id to (it.hi + if (it == WakeRules.Sense.HIGH) "  ⚠" else "") }, WakeWord.sense(c).id, 4200)
    box.addView(senseWarn)
    fun showSense() { senseWarn.text = UiLang.s(WakeWord.sense(c).warn) }
    showSense()
    rgSense.setOnCheckedChangeListener { _, _ ->
        Prefs.edit(c) { putString(WakeWord.P_SENSE, radioValue(rgSense, senses.map { it.id }, 4200)) }; showSense()
    }

    // ---------------------------------------------------------------- सिर्फ़ मालिक की आवाज़
    label(box, "किसकी आवाज़ पर जागे")
    val ownerInfo = tv(13f, 0xB3)
    val swOwner = switch(box, "सिर्फ़ मालिक की आवाज़ पर जागो", WakeWord.ownerOnly(c))
    box.addView(ownerInfo)
    fun ownerRefresh() {
        val ok = WakeWord.ownerReady(c)
        swOwner.isEnabled = ok
        if (!ok && swOwner.isChecked) swOwner.isChecked = false
        ownerInfo.text = UiLang.s(when {
            !VoiceGuardian.supported -> "यह फ़ोन आवाज़-पहचान मॉडल नहीं चला सकता - सिर्फ़ \"सबकी आवाज़\" मोड।"
            !VoiceGuardian.hasModel(c) -> "पहले वॉइस गार्डियन का आवाज़-पहचान मॉडल डाउनलोड करें (आवाज़ → वॉइस गार्डियन), फिर नीचे आवाज़ दर्ज करें।"
            !VoiceGuardian.enrolled(c) -> "आपकी आवाज़ अभी दर्ज नहीं - नीचे \"मालिक की आवाज़ दोबारा दर्ज करो\" दबाइए। तब तक यह विकल्प बंद रहेगा।"
            else -> "✓ आपकी आवाज़ दर्ज है (वॉइस गार्डियन वाली, फ़ोन में ही, ताले में बंद)। बंद = सबकी आवाज़ पर जागेगा।"
        })
    }
    swOwner.setOnCheckedChangeListener { _, on ->
        if (on && !WakeWord.ownerReady(c)) { swOwner.isChecked = false; toast("पहले मालिक की आवाज़ दर्ज करें"); return@setOnCheckedChangeListener }
        Prefs.edit(c) { putBoolean(WakeWord.P_OWNER, on) }; refresh()
    }
    label(box, "मालिक की आवाज़ का मिलान कितना सख़्त")
    val thTxt = tv(13f, 0xB3)
    val sb = SeekBar(this).apply { max = 60; progress = WakeRules.ownerToSlider(WakeWord.ownerTh(c)) }
    box.addView(sb); box.addView(thTxt)
    fun showTh() { thTxt.text = UiLang.s("सीमा: ${(WakeWord.ownerTh(c) * 100).toInt()}% (ज़्यादा = सख़्त: दूसरों पर कम जागेगा, पर कभी आप भी छूटेंगे; डिफ़ॉल्ट ${(WakeRules.OWNER_TH_DEFAULT * 100).toInt()}%)") }
    showTh()
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) { Prefs.edit(c) { putLong(WakeWord.P_OWNER_TH, (WakeRules.ownerFromSlider(p) * 100).toLong()) }; showTh() }
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    val say = tv(15f); box.addView(say)
    row(box,
        button(UiLang.s("मालिक की आवाज़ दोबारा दर्ज करो")) {
            if (!Setup.hasMic(c)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return@button }
            if (!VoiceGuardian.supported || !VoiceGuardian.hasModel(c)) { toast("पहले वॉइस गार्डियन का मॉडल डाउनलोड करें"); return@button }
            lifecycleScope.launch {
                val prints = ArrayList<FloatArray>()
                withDkMicPaused {
                    for ((i, p) in WakeRules.ENROLL_PHRASES.withIndex()) {
                        for (attempt in 0 until 2) {
                            say.text = UiLang.s("(${i + 1}/${WakeRules.ENROLL_PHRASES.size}) बोलिए: \"$p\"")
                            val t = VoiceEnroll.record(source = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: continue
                            if (t.speechMs < VgRules.MIN_SPEECH_MS) { say.text = UiLang.s("थोड़ा लंबा बोलिए - फिर से"); continue }
                            val e = withContext(Dispatchers.Default) { VoiceGuardian.embed(c, VgRules.trimSilence(t.pcm)) }
                            if (e != null) { prints += e; break }
                        }
                    }
                }
                if (prints.size < 3) { say.text = UiLang.s("✗ बहुत से वाक्य साफ़ नहीं आए (${prints.size}/${WakeRules.ENROLL_PHRASES.size}) - शांत कमरे में फिर से"); return@launch }
                val cons = VoicePrint.consistency(prints)
                if (cons < VgRules.MIN_CONSISTENCY) { say.text = UiLang.s("✗ वाक्य आपस में कम मिले (${VgRules.pct(cons)}%) - सिर्फ़ आप बोलें, फिर से"); return@launch }
                val m = VoicePrint.mean(prints) ?: return@launch
                VoiceGuardian.savePrint(c, m)
                say.text = UiLang.s("✓ आवाज़ दर्ज हो गई (${prints.size} वाक्य, आपस में मिलान ${VgRules.pct(cons)}%). सिर्फ़ इसी फ़ोन में, ताले में बंद।")
                ownerRefresh(); refresh()
            }
        },
        button(UiLang.s("वॉइसप्रिंट मिटाओ")) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("वॉइसप्रिंट मिटाएँ?"))
                .setMessage(UiLang.s("आपकी दर्ज आवाज़ (वॉइस गार्डियन वाली भी) मिट जाएगी, और \"सिर्फ़ मालिक\" मोड बंद हो जाएगा।"))
                .setPositiveButton(UiLang.s("मिटाओ")) { _, _ ->
                    VoiceGuardian.forget(c); Prefs.edit(c) { putBoolean(WakeWord.P_OWNER, false) }
                    ownerRefresh(); refresh(); toast("वॉइसप्रिंट मिटा दिया")
                }
                .setNegativeButton(UiLang.s("रहने दो"), null).show()
        })
    ownerRefresh()
    if (Prefs.str(c, "hey_dk_print", "").isNotBlank() && !VoiceGuardian.enrolled(c))
        hint(box, "ध्यान दें: पुराने \"Hey DK\" की आवाज़-पहचान (CAM++) नए मॉडल से मेल नहीं खाती, इसलिए दोबारा इस्तेमाल नहीं हो सकती - ऊपर एक बार नई आवाज़ दर्ज कीजिए।")

    // ---------------------------------------------------------------- चुप्पी के बाद बंद
    label(box, "चुप रहने पर Live कितनी देर बाद बंद हो")
    val sil = WakeRules.SILENCE_CHOICES
    val rgSil = radios(box, sil.map { it.toString() to "${it / 1000} सेकंड" + if (it == WakeRules.SILENCE_DEFAULT_MS) " (डिफ़ॉल्ट)" else "" },
        WakeWord.silenceMs(c).let { cur -> sil.minByOrNull { kotlin.math.abs(it - cur) } ?: WakeRules.SILENCE_DEFAULT_MS }.toString(), 4300)
    rgSil.setOnCheckedChangeListener { _, _ ->
        Prefs.edit(c) { putLong(WakeWord.P_SILENCE, radioValue(rgSil, sil.map { it.toString() }, 4300).toLongOrNull() ?: WakeRules.SILENCE_DEFAULT_MS.toLong()) }
    }

    // ---------------------------------------------------------------- जागने की जाँच
    val test = tv(); box.addView(test)
    row(box, button(UiLang.s("जागने की जाँच")) {
        if (!Setup.hasMic(c)) { askMissing(31, android.Manifest.permission.RECORD_AUDIO); return@button }
        if (!WakeModels.ready(c)) { toast("पहले मॉडल डाउनलोड करें"); return@button }
        if (WakeWord.words(c).isEmpty()) { toast("कम से कम एक शब्द चालू करें"); return@button }
        lifecycleScope.launch {
            test.text = UiLang.s("10 सेकंड में \"हे डीके\" या \"हेलो डीके\" बोलिए…")
            val ignoredBefore = WakeWord.falseIgnored
            WakeWord.testing = true
            val t0 = System.currentTimeMillis()
            val hit = try {
                withDkMicPaused { WakeWord.listen(c, handoff = false) { System.currentTimeMillis() - t0 > 10_000 } }
            } finally { WakeWord.testing = false }
            test.text = UiLang.s(when {
                hit != null -> "✓ \"${hit.word.hi}\" पहचाना (score ${"%.2f".format(hit.score)})\n" + WakeRules.latencyText(hit.latencyMs, hit.ownerMs)
                WakeWord.falseIgnored > ignoredBefore -> "शब्द सुना, पर आवाज़ मालिक की नहीं लगी - \"सिर्फ़ मालिक\" की सीमा थोड़ी कम करें या आवाज़ दोबारा दर्ज करें"
                else -> "✗ 10 सेकंड में नहीं पहचाना - थोड़ा पास से, साफ़ बोलिए, या संवेदनशीलता \"बीच का\" / \"ज़्यादा\" करें"
            })
            DKLog.d("जागने की जाँच: " + (hit?.let { "${it.word.hi} ${it.latencyMs} ms" } ?: "नहीं"))
        }
    })

    // ---------------------------------------------------------------- बैटरी की रोक (Oppo / Motorola के अलग रास्ते)
    row(box,
        button(UiLang.s("बैटरी की रोक हटाओ")) { Setup.requestBatteryExempt(c) },
        button(UiLang.s("फ़ोन कंपनी की सेटिंग")) { if (!openMakerBattery()) toast("इस फ़ोन में अलग पेज नहीं मिला - Settings → Apps → DK → Battery → \"Unrestricted\" चुनें") })
    hint(box, "Oppo / Realme (ColorOS): Settings → Apps → DK → Battery → \"Allow background activity\" चालू, और \"Auto launch\" चालू।\n" +
        "Motorola: Settings → Apps → DK → App battery usage → \"Unrestricted\" चुनें।")

    hint(box, WakeRules.hotwordText(WakeWord.hotwordState(c)))
    hint(box, WakeRules.A14_NOTE)
    hint(box, WakeRules.LIMITS)
    refresh()
}

/** Oppo / Realme / OnePlus (ColorOS) और Motorola के बैटरी / auto-start पेज - जो खुले वही. कोई न खुले = false */
private fun SettingsActivity.openMakerBattery(): Boolean {
    val pkg = packageName
    val list = listOf(
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Intent().setClassName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        Intent().setClassName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        Intent().setClassName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        Intent().setClassName("com.motorola.batterymanager", "com.motorola.batterymanager.BatteryManagerActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
    )
    for (i in list) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
    }
    return false
}
