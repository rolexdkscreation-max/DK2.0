package com.dk.assistant

import android.net.ConnectivityManager
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.voice.st3.St3Config
import com.dk.assistant.voice.st3.St3Engine
import com.dk.assistant.voice.st3.St3Stats
import com.dk.assistant.voice.st3.St3Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 19 नवंबर 2026 (मालिक, चरण 1): "आवाज़ें" स्क्रीन - फ़ोन पर बनने वाली नई आवाज़ (Supertonic 3).
 * - मॉडल APK में नहीं: यहीं से डाउनलोड (WorkManager + OkHttp, रुका तो वहीं से आगे, SHA-256 जाँच, ऐप की अपनी स्टोरेज).
 * - सिर्फ़ महिला आवाज़ें F1-F5, default F1. हर आवाज़: साइज़, "डाउनलोड", progress, फिर "चुनें".
 * - मोबाइल डेटा पर बड़ी फ़ाइल से पहले पूछो. नेट न हो / फ़ेल = साफ़ हिंदी संदेश + "दोबारा कोशिश".
 * - रफ़्तार (default 1.05) और कदम (default 8) बदल सकते हैं. "टेस्ट वाक्य सुनाओ" = तय वाक्य बोलकर जाँच.
 * DK की बातचीत अभी पहले जैसी - यह आवाज़ अभी सिर्फ़ यहाँ के टेस्ट में.
 */
private val st3Jobs = ArrayList<Job>()

/** "आवाज़" वाले हिस्से में छोटा कार्ड - बटन दबाओ तो "आवाज़ें" स्क्रीन (यहाँ पुराने इंजन की सूची नहीं बदलती) */
internal fun SettingsActivity.st3Entry() {
    card("आवाज़ें (फ़ोन पर बनने वाली नई आवाज़)", "महिला आवाज़ें 1-5 • डाउनलोड • टेस्ट वाक्य • नाप") { box ->
        hint(box, "नई आवाज़ इंटरनेट के बिना फ़ोन पर ही बनती है. मॉडल ऐप में नहीं - वहाँ से डाउनलोड करें. बातचीत में इसे चलाने के लिए ऊपर इंजन की सूची में \"DK ऑफ़लाइन आवाज़\" चालू करें.")
        row(box, button("आवाज़ें खोलें") {
            startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", "st3"))
        })
    }
}

private fun mbs(b: Long): String = if (b >= 1_048_576) String.format(java.util.Locale.US, "%.0f MB", b / 1_048_576.0)
    else String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)

private fun SettingsActivity.metered(): Boolean = try {
    (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
} catch (_: Exception) { false }

/** बचे bytes (आधी फ़ाइल हो तो उसे घटाकर) */
private fun SettingsActivity.left(list: List<ModelCatalog.Asset>): Long = list.filterNot { ModelManager.ready(this, it) }
    .sumOf { a -> (a.bytes - java.io.File(ModelManager.dir(this), a.fileName + ".part").length()).coerceAtLeast(0) }

/** डाउनलोड शुरू करो - जगह, इंटरनेट, मोबाइल डेटा की जाँच के साथ. [done] = सच में शुरू हुआ */
private fun SettingsActivity.startDl(list: List<ModelCatalog.Asset>, what: String, onMsg: (String) -> Unit) {
    val need = left(list)
    if (need <= 0) { list.forEach { ModelManager.enqueue(this, it) }; return }
    val free = try { ModelManager.dir(this).usableSpace } catch (_: Exception) { Long.MAX_VALUE }
    if (free < need + 50L * 1024 * 1024) { onMsg("✗ फ़ोन में जगह कम है - $what के लिए करीब ${mbs(need + 50L * 1024 * 1024)} खाली जगह चाहिए"); return }
    if (!NetProbe.online(this)) { onMsg("✗ अभी इंटरनेट नहीं है. Wi-Fi या मोबाइल डेटा चालू करके \"दोबारा कोशिश\" दबाएँ."); return }
    val go = { try { list.forEach { ModelManager.enqueue(this, it) } } catch (e: Exception) { onMsg("✗ डाउनलोड शुरू नहीं हुआ: ${e.message ?: "अनजानी गलती"}") } }
    if (St3Config.askBeforeDownload(metered(), need)) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(UiLang.s("मोबाइल डेटा पर डाउनलोड?"))
            .setMessage(UiLang.s("आप अभी मोबाइल डेटा पर हैं. $what करीब ${mbs(need)} का है. क्या मोबाइल डेटा से ही डाउनलोड करें? (Wi-Fi पर करना बेहतर)"))
            .setPositiveButton(UiLang.s("हाँ, डाउनलोड करो")) { _, _ -> go() }
            .setNegativeButton(UiLang.s("नहीं, Wi-Fi पर करूँगा"), null)
            .show()
    } else go()
}

internal fun SettingsActivity.secSt3() {
    st3Jobs.forEach { it.cancel() }; st3Jobs.clear()
    try { ModelManager.refresh(this) } catch (e: Exception) { Quiet.note(e) }
    val voiceViews = ArrayList<() -> Unit>()

    // ------------------------------------------------------------ 1. मुख्य मॉडल
    card("1. मुख्य आवाज़-मॉडल (एक बार)", "करीब ${mbs(St3Config.coreBytes())} • सभी आवाज़ों के लिए एक ही", startOpen = true) { box ->
        hint(box, "स्रोत: Hugging Face - ${St3Config.REPO} (तय संस्करण ${St3Config.COMMIT.take(7)}) • License ${St3Config.LICENSE}. फ़ोन की ऐप-स्टोरेज में रहता है, कभी भी हटा सकते हैं. रुका / नेट गया = जहाँ रुका वहीं से आगे; पूरा होने पर SHA-256 से जाँच.")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        box.addView(bar)
        val st = status(box)
        val retry = button("दोबारा कोशिश") { }.apply { visibility = View.GONE }
        fun show() {
            val list = St3Config.CORE
            val states = list.map { ModelManager.state(it.id).value }
            val total = list.sumOf { it.bytes }
            val done = list.sumOf { a -> if (ModelManager.ready(this, a)) a.bytes else when (val s = ModelManager.state(a.id).value) {
                is ModelManager.St.Running -> s.done; is ModelManager.St.Paused -> s.done; else -> 0L } }
            val pct = (done * 100 / total).toInt()
            val failed = states.filterIsInstance<ModelManager.St.Failed>().firstOrNull()
            retry.visibility = if (failed != null) View.VISIBLE else View.GONE
            when {
                St3Store.coreReady(this) -> { bar.visibility = View.GONE; st.text = UiLang.s("✓ मुख्य मॉडल तैयार (जाँच सही)") }
                failed != null -> { bar.visibility = View.VISIBLE; bar.progress = pct
                    st.text = UiLang.s("✗ डाउनलोड नहीं हुआ (${failed.why}). इंटरनेट देखकर \"दोबारा कोशिश\" दबाएँ - जहाँ रुका वहीं से चलेगा.") }
                states.any { it is ModelManager.St.Running } -> { bar.visibility = View.VISIBLE; bar.progress = pct
                    st.text = UiLang.s("डाउनलोड हो रहा है: $pct% (${mbs(done)} / ${mbs(total)})") }
                done > 0 -> { bar.visibility = View.VISIBLE; bar.progress = pct; st.text = UiLang.s("रुका हुआ: $pct% - \"डाउनलोड\" दबाएँ, वहीं से आगे चलेगा") }
                else -> { bar.visibility = View.GONE; st.text = UiLang.s("अभी फ़ोन में नहीं • ${mbs(total)}") }
            }
            voiceViews.forEach { it() }
        }
        val dl = { startDl(St3Config.CORE, "मुख्य आवाज़-मॉडल") { m -> st.text = UiLang.s(m); retry.visibility = View.VISIBLE } }
        retry.setOnClickListener { dl() }
        row(box, button("डाउनलोड") { dl() }, button("रोकें") {
            St3Config.CORE.forEach { ModelManager.pause(this, it) }; toast("डाउनलोड रोका - आधी फ़ाइल सुरक्षित")
        }, button("हटाएँ") {
            lifecycleScope.launch { try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { St3Engine.release() } } catch (e: Exception) { Quiet.note(e) }
                St3Config.CORE.forEach { ModelManager.delete(this@secSt3, it) }; toast("मुख्य मॉडल हटाया (जगह खाली)") }
        })
        row(box, retry)
        show()
        for (a in St3Config.CORE) st3Jobs.add(lifecycleScope.launch {
            try { ModelManager.state(a.id).collectLatest { show() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Quiet.note(e) }
        })
    }

    // ------------------------------------------------------------ 2. आवाज़ें F1-F5
    card("2. आवाज़ें (महिला)", "हर आवाज़ छोटी (~300 KB) • डाउनलोड करके \"चुनें\"", startOpen = true) { box ->
        for (v in St3Config.VOICES) {
            val a = v.asset
            label(box, "${v.name} (${v.id}) • ${mbs(a.bytes)}")
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
            box.addView(bar)
            val st = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
            box.addView(st)
            val act = button("डाउनलोड") { }
            fun show() {
                val s = ModelManager.state(a.id).value
                val chosen = St3Store.voice(this) == v.id
                bar.visibility = if (s is ModelManager.St.Running) View.VISIBLE else View.GONE
                if (s is ModelManager.St.Running) bar.progress = s.pct
                when {
                    ModelManager.ready(this, a) -> { act.text = UiLang.s(if (chosen) "✓ चुनी गई" else "चुनें"); act.isEnabled = !chosen
                        st.text = UiLang.s(if (chosen) "यही आवाज़ चलेगी" else "डाउनलोड हो चुकी") }
                    s is ModelManager.St.Running -> { act.text = UiLang.s("डाउनलोड हो रहा है"); act.isEnabled = false; st.text = UiLang.s("${s.pct}%") }
                    s is ModelManager.St.Failed -> { act.text = UiLang.s("दोबारा कोशिश"); act.isEnabled = true
                        st.text = UiLang.s("✗ डाउनलोड नहीं हुआ (${s.why})") }
                    else -> { act.text = UiLang.s("डाउनलोड"); act.isEnabled = true; st.text = UiLang.s(if (chosen) "चुनी हुई (पहले डाउनलोड करें)" else "अभी फ़ोन में नहीं") }
                }
            }
            act.setOnClickListener {
                if (ModelManager.ready(this, a)) { St3Store.setVoice(this, v.id); toast("${v.name} चुनी गई"); voiceViews.forEach { it() } }
                else startDl(listOf(a), v.name) { m -> st.text = UiLang.s(m); act.text = UiLang.s("दोबारा कोशिश"); act.isEnabled = true }
            }
            row(box, act)
            voiceViews.add { show() }
            show()
            st3Jobs.add(lifecycleScope.launch {
                try { ModelManager.state(a.id).collectLatest { show() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Quiet.note(e) }
            })
        }
    }

    // ------------------------------------------------------------ 3. रफ़्तार / कदम / टेस्ट
    card("3. रफ़्तार, साफ़ी और टेस्ट", "रफ़्तार ${St3Config.SPEED_DEF} • कदम ${St3Config.STEPS_DEF} (डिफ़ॉल्ट)", startOpen = true) { box ->
        label(box, "बोलने की रफ़्तार")
        val spTxt = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        val spMax = Math.round((St3Config.SPEED_MAX - St3Config.SPEED_MIN) * 100)
        val sp = SeekBar(this).apply { max = spMax; progress = ((St3Store.speed(this@secSt3) - St3Config.SPEED_MIN) * 100 + 0.5f).toInt() }
        box.addView(sp); box.addView(spTxt)
        fun showSp() { spTxt.text = UiLang.s(String.format(java.util.Locale.US, "%.2f (कम = धीरे, ज़्यादा = तेज़; डिफ़ॉल्ट %.2f)", St3Store.speed(this), St3Config.SPEED_DEF)) }
        showSp()
        sp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { St3Store.setSpeed(this@secSt3, St3Config.SPEED_MIN + p / 100f); showSp() } }
            override fun onStartTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
            override fun onStopTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
        })

        label(box, "कदम (total steps)")
        val stTxt = TextView(this).apply { textSize = 13f; setTextColor(0xB3FFFFFF.toInt()) }
        val stb = SeekBar(this).apply { max = St3Config.STEPS_MAX - St3Config.STEPS_MIN; progress = St3Store.steps(this@secSt3) - St3Config.STEPS_MIN }
        box.addView(stb); box.addView(stTxt)
        fun showSt() { stTxt.text = UiLang.s("${St3Store.steps(this)} (कम = जल्दी पर थोड़ी कम साफ़; ज़्यादा = साफ़ पर धीमी; डिफ़ॉल्ट ${St3Config.STEPS_DEF})") }
        showSt()
        stb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { St3Store.setSteps(this@secSt3, St3Config.STEPS_MIN + p); showSt() } }
            override fun onStartTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
            override fun onStopTrackingTouch(s: SeekBar?) { // ज़रूरत नहीं
            }
        })

        label(box, "टेस्ट वाक्य")
        hint(box, St3Config.TEST_LINE)
        val st = status(box)
        st.text = UiLang.s("मॉडल और एक आवाज़ डाउनलोड होने के बाद दबाएँ")
        val stats = status(box)
        var showStats: () -> Unit = {}
        row(box, button("टेस्ट वाक्य सुनाओ") {
            if (!St3Store.coreReady(this)) { st.text = UiLang.s("✗ पहले \"मुख्य आवाज़-मॉडल\" डाउनलोड करें"); return@button }
            if (St3Store.usableVoice(this) == null) { st.text = UiLang.s("✗ पहले कोई एक आवाज़ (जैसे आवाज़ 1) डाउनलोड करें"); return@button }
            st.text = UiLang.s("बोल रहा हूँ... (पहली बार मॉडल खुलने में कुछ सेकंड)")
            lifecycleScope.launch {
                val t0 = System.currentTimeMillis()
                val err = try { withDkMicPaused { St3Engine.say(this@secSt3, St3Config.TEST_LINE) } } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Throwable) { Quiet.note(e); e.message ?: "अनजानी गलती" }
                st.text = UiLang.s(if (err == null) "✓ बोल दिया (${(System.currentTimeMillis() - t0) / 100 / 10.0} सेकंड) • आवाज़ ${St3Store.usableVoice(this@secSt3)}" +
                    " • पहली आवाज़ ${St3Stats.lastTestFirstMs} ms में"
                    else "✗ $err")
                showStats()
            }
        }, button("रोकें") { St3Engine.stop() })

        // 19 नवंबर 2026 (मालिक: "हर वाक्य का समय नापो, बनना आवाज़ से धीमा हो तो बताओ")
        label(box, "नाप (हर वाक्य: बनने का समय / आवाज़ की लंबाई)")
        box.removeView(stats); box.addView(stats)
        showStats = {
            val first = St3Stats.lastFirstMs
            stats.text = UiLang.s(St3Stats.summary(St3Stats.recent()) +
                (if (first > 0) "\nपिछली बातचीत: जवाब का text आने से पहली आवाज़ $first ms में" else "") +
                (if (St3Engine.benching) "\nथ्रेड जाँच चल रही है..." else ""))
        }
        showStats()
        row(box, button("नाप ताज़ा करें") { showStats() }, button("थ्रेड फिर से जाँचें") {
            if (!St3Store.ready(this)) { stats.text = UiLang.s("✗ पहले मॉडल और एक आवाज़ डाउनलोड करें"); return@button }
            stats.text = UiLang.s("थ्रेड जाँच चल रही है (4-6 थ्रेड, 10-30 सेकंड)...")
            lifecycleScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    St3Engine.rebench(this@secSt3)
                    var waited = 0
                    while ((St3Engine.benching || St3Store.threads(this@secSt3) == 0) && waited < 120) { kotlinx.coroutines.delay(500); waited++ }
                }
                val n = St3Store.threads(this@secSt3)
                stats.text = UiLang.s(if (n > 0) "✓ इस फ़ोन पर $n थ्रेड सबसे तेज़ - वही चलेंगे" else "✗ थ्रेड जाँच पूरी नहीं हुई - फिर से दबाएँ")
            }
        })
    }
}
