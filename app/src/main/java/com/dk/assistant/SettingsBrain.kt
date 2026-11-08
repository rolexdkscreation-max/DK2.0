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
import android.content.Context.INPUT_METHOD_SERVICE
import com.dk.assistant.SettingsActivity.Companion.EXTRA_FOCUS_KEY

// SettingsActivity का हिस्सा: AI दिमाग: provider, keys, मॉडल, Live/कोडर/कंप्यूटर की अलग keys, API link.
// यह code पहले SettingsActivity.kt में था; सिर्फ़ जगह बदली है, व्यवहार वही (extension functions, वही state SettingsActivity में)।

// ------------------------------------------------------------ AI दिमाग: 4 अलग हिस्से, हर एक की अपनी API
internal fun SettingsActivity.scopeStatus(scope: String): String {
    if (scope == ApiScopes.LIVE) {
        val g = Prefs.scopeKeyRaw(this, scope, "gemini").isNotBlank(); val q = Prefs.scopeKeyRaw(this, scope, "groq").isNotBlank()
        return (if (g) "✓ Gemini key" else "✗ Gemini key नहीं") + (if (q) " • ✓ सुनने के लिए Groq" else "") +
            (LiveTextBrain.pick { Prefs.scopeKeyRaw(this, scope, it) }?.let { " • ✓ गहरी सोच: " + KeySetup.name(it) } ?: "") + " • आवाज़ सिर्फ़ Gemini"
    }
    val id = Prefs.scopeActiveRaw(this, scope)
    val ok = Prefs.scopeKeyRaw(this, scope, id).isNotBlank()
    return (if (ok) "✓ " else "✗ ") + KeySetup.name(id) + (if (ok) " • ${Prefs.scopeModel(this, scope, id)}" else " की key नहीं") + " • अपनी अलग key"
}

internal fun SettingsActivity.secBrainMenu() {
    // v3.91: "एक key से सब" - नया install = चालू (एक Gemini key से सारे काम); अलग-अलग keys वाले हिस्से बंद (मिटते नहीं)
    val one = Prefs.oneKeyOn(this)
    card("एक key से सब (ऑल-राउंडर)", "सिर्फ़ एक Gemini key — DK लाइव में बात, चैट, सुनना-लिखना, Google खोज और फ़ोन के काम। कोडिंग एजेंट, स्टोरी मोड, DK कंप्यूटर, वेबसाइट के लिए इसे बंद करके अलग-अलग keys डालें", startOpen = one) { box ->
        val sw = switch(box, "एक key से सब चलाओ", one)
        sw.setOnCheckedChangeListener { _, on ->
            save(false)
            Prefs.edit(this) { putBoolean(ApiScopes.ONE_KEY_ON, on) }
            container.removeAllViews(); secBrainMenu(); space(container, 8)
            toast(if (on) "✓ अब सारे काम एक key से — DK लाइव दोबारा शुरू करें" else "✓ अब हर हिस्सा अपनी अलग key से")
        }
        if (!one) {
            hint(box, "अभी बंद है — हर हिस्सा नीचे अपनी अलग key से चल रहा है। चालू करें तो सारे काम ऊपर की एक key से होंगे (अलग keys मिटेंगी नहीं, बंद करते ही फिर चलेंगी)।")
            return@card
        }
        val ed = field(box, "Gemini API Key (बात + चैट इसी से)", Prefs.oneKey(this), "AIza… यहाँ पेस्ट करें", secret = true)
        keyFields[ApiScopes.ONE_KEY] = ed
        // v3.6: पहली बार माइक दबाया और key नहीं = सीधे यहीं, keyboard खुला
        if (intent.getBooleanExtra(EXTRA_FOCUS_KEY, false) && Prefs.oneKey(this).isBlank()) ed.post {
            ed.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(ed, 0)
        }
        val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box, showButton(ed), button("जाँचें") {
            val k = ed.text.toString().trim()
            if (k.isBlank()) { res.text = "पहले Gemini key पेस्ट करें (AIza… से शुरू)"; return@button }
            if (ApiScopes.detect(k).let { it != null && it != "gemini" }) { res.text = "यह Gemini की key नहीं लगती — यहाँ सिर्फ़ Gemini key (AIza…) चलेगी"; return@button }
            res.text = "जवाब और Google खोज से जाँच रहा हूँ…"
            lifecycleScope.launch {
                res.text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { checkSearchKey(k) }.replace("Google खोज चली", "जवाब और Google खोज चले")
                save(false)
            }
        }, button("Key लें") { Setup.openUrl(this, Providers.byId("gemini")!!.keyUrl) })
        box.addView(res)
        // नवंबर 2026 (मालिक): एक-key मोड में भी मॉडल (नाम + विवरण) और सोचने का स्तर यहीं
        val gem = Providers.byId("gemini")!!
        val chatModel = ApiScopes.pref(ApiScopes.CHAT, "model", "gemini")
        val mLine = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (2 * dp).toInt()) }
        fun showModel() { val id = Prefs.scopeModel(this, ApiScopes.CHAT, "gemini"); mLine.text = "मॉडल: " + modelLabel(gem, id) + " — " + ModelNames.desc(id) }
        showModel(); box.addView(mLine)
        row(box, button("मॉडल चुनें") {
            val k = Prefs.oneKey(this)
            val pick: (List<String>) -> Unit = { list ->
                pickModelThen(gem, Prefs.scopeModel(this, ApiScopes.CHAT, "gemini"), list, ThinkLevel.ONE) { id ->
                    Prefs.edit(this) { putString(chatModel, id) }; showModel()
                }
            }
            val cached = Prefs.models(this, "gemini")
            if (cached.isNotEmpty()) pick(cached)
            else if (k.length < 20) toast("पहले Gemini key डालें")
            else lifecycleScope.launch {
                try {
                    val list = ModelLister.fetch(gem, k, "")
                    Prefs.edit(this@secBrainMenu) { putString("models_gemini", list.joinToString("\n")) }
                    saveModelNames(gem, list); pick(list)
                } catch (e: Exception) { toast("मॉडल नहीं आए: " + BrainManager(this@secBrainMenu).explain(e).take(120)) }
            }
        })
        thinkRow(box, ThinkLevel.ONE)
        hint(box, "अभी: " + (if (Prefs.oneKey(this).isNotBlank()) "✓ बात, चैट, खोज और फ़ोन के काम इसी एक key से" else "✗ key नहीं डाली") +
            " • सुनना भी Gemini से (Groq की ज़रूरत नहीं)। एक free key की limit सब कामों में बँटती है — limit जल्दी खत्म हो तो बटन बंद करके अलग-अलग keys डालें।")
    }
    // v3.167 (मालिक): वेबसाइट बिल्डर की अपनी अलग key - एक-key मोड में भी दिखे और चले
    menuRow("brain_web", "वेबसाइट बिल्डर (Website Forge)", forgeStatus() + "\nवेबसाइट बनाने के लिए अलग Gemini key — सिर्फ़ यही इस्तेमाल होगी", "computer")
    if (one) {
        card("अलग-अलग key वाले हिस्से — बंद", "ऊपर \"एक key से सब\" चालू है, इसलिए DK लाइव / चैट / कोडिंग / कंप्यूटर की अलग keys अभी इस्तेमाल नहीं हो रहीं") { box ->
            hint(box, "अलग-अलग key चाहिए तो ऊपर वाला बटन बंद करें — पहले डाली हुई अलग keys वैसी ही सुरक्षित हैं।")
        }
        return
    }
    card("हर हिस्से की अपनी API", "हर हिस्सा सिर्फ़ अपनी डाली हुई key से जवाब लेता है — किसी दूसरे की key इस्तेमाल नहीं करता") { box ->
        hint(box, "किसी मॉडल का फ्री कोटा खत्म (limit) हो तो उसी key के दूसरे फ्री मॉडल पर अपने-आप स्विच होता है। दूसरी कंपनी पर नहीं जाता।")
    }
    menuRow("brain_live", "DK लाइव", scopeStatus(ApiScopes.LIVE) + "\nआवाज़ से बातचीत (Gemini Live), बोलना, सुनना", "voice")
    menuRow("brain_chat", "DK चैट", scopeStatus(ApiScopes.CHAT) + "\nलिखकर चैट, जवाब, Google search, गहरी सोच, कहानी", "brain")
    menuRow("brain_coder", "कोडिंग एजेंट", scopeStatus(ApiScopes.CODER) + "\nकोडिंग टीम: बिल्डर, रिव्यूअर, कोऑर्डिनेटर", "coder")
    menuRow("brain_pc", "DK कंप्यूटर", scopeStatus(ApiScopes.PC) + "\nDK कंप्यूटर के काम और उसकी web search", "computer")
}

internal fun SettingsActivity.forgeStatus(): String {
    if (Prefs.forgeKey(this).isBlank()) return "✗ key नहीं डाली — वेबसाइट नहीं बनेगी"
    val m = Prefs.forgeModel(this).ifBlank { Prefs.forgeAutoModel(this).ifBlank { "अपने-आप" } }
    return "✓ अपनी key • मॉडल: $m"
}

/** v3.167: वेबसाइट बिल्डर (Website Forge) - अपनी अलग Gemini key + अपने-आप सबसे अच्छा मॉडल */
internal fun SettingsActivity.secBrainWeb() {
    card("वेबसाइट बिल्डर — अपनी Gemini key", "DK से जब भी वेबसाइट बनवाएँगे, सिर्फ़ यही key लगेगी — DK लाइव, चैट, कोडिंग या कोई दूसरी key नहीं") { box ->
        val ed = field(box, "Gemini API Key (वेबसाइट बिल्डर की अपनी)", Prefs.forgeRaw(this, Prefs.FORGE_KEY), "AIza… यहाँ पेस्ट करें", secret = true)
        keyFields[Prefs.FORGE_KEY] = ed
        keyFields[Prefs.FORGE_KEY2] = field(box, "Gemini API Key 2 (बैकअप, ज़रूरी नहीं)",
            Prefs.forgeRaw(this, Prefs.FORGE_KEY2), "दूसरी Gemini key (दूसरे Google account की)", secret = true)
        val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        val modelTv = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0) }
        fun showModel() {
            val auto = Prefs.forgeAutoModel(this)
            val own = Prefs.forgeModel(this)
            modelTv.text = if (own.isNotBlank()) "मॉडल: $own (आपका चुना)" + (if (auto.isNotBlank() && auto != own) "\nसबसे अच्छा (अपने-आप): $auto" else "")
                else if (auto.isNotBlank()) "मॉडल: $auto ✓ (वेबसाइट के लिए सबसे अच्छा — अपने-आप चुना)"
                else "मॉडल: key डालकर \"जाँचें\" दबाएँ — सबसे अच्छा मॉडल अपने-आप आ जाएगा"
        }
        var models: List<String> = Prefs.forgeRaw(this, Prefs.FORGE_MODELS).split("\n").filter { it.isNotBlank() }
        fun check(silent: Boolean) {
            val k = ed.text.toString().trim()
            if (k.isBlank()) { if (!silent) res.text = "✗ पहले वेबसाइट बिल्डर की Gemini key डालें"; return }
            if (ApiScopes.detect(k).let { it != null && it != "gemini" }) { res.text = "✗ यह Gemini की key नहीं लगती (AIza… से शुरू होती है)"; return }
            save(false); res.text = "⏳ key जाँच रहा हूँ — Pro, Flash, Lite एक साथ आज़मा रहा हूँ…"
            lifecycleScope.launch {
                try {
                    val r = com.dk.assistant.forge.WebsiteForgeClient.pickBest(k)
                    val best = r.best; val all = r.all
                    models = all
                    Prefs.edit(this@secBrainWeb) {
                        putString(Prefs.FORGE_MODELS, all.joinToString("\n"))
                        if (best != null) putString(Prefs.FORGE_AUTO_MODEL, best)
                    }
                    // v3.169: हर जाँचे मॉडल का साफ़ नतीजा - मालिक को दिखे key चल रही है या नहीं, और क्यों
                    val lines = r.tried.joinToString("\n") { (m, c) -> "• $m: " + com.dk.assistant.forge.WebsiteForgeClient.probeLabel(c) }
                    res.text = (if (best != null) "✓ key चल रही है • सबसे अच्छा मॉडल: $best (${all.size} मॉडल मिले)"
                        else "✗ key सही है, पर अभी किसी मॉडल की free limit नहीं बची — थोड़ी देर बाद या Key 2 डालकर फिर जाँचें") + "\n" + lines
                    showModel()
                } catch (e: Exception) {
                    val m = e.message.orEmpty()
                    res.text = "✗ " + (if ((e as? com.dk.assistant.forge.WebsiteForgeClient.ForgeHttpException)?.code.let { it == 400 || it == 401 || it == 403 }) "यह key गलत/बंद है — Google AI Studio से नई key लें"
                        else m.ifBlank { "key नहीं चली (नेट जाँचें)" }).take(160)
                }
            }
        }
        // v3.169: key पेस्ट करते ही अपने-आप जाँच (जाँचें दबाना भूलें तो भी नतीजा दिखे)
        var lastChecked = ed.text.toString().trim()
        val autoCheck = Runnable { val k = ed.text.toString().trim(); if (k != lastChecked && k.startsWith("AIza") && k.length >= 35) { lastChecked = k; check(true) } }
        ed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ed.removeCallbacks(autoCheck); ed.postDelayed(autoCheck, 1200) }
        })
        row(box, showButton(ed), button("जाँचें") { check(false) }, button("Key लें") { Setup.openUrl(this, Providers.byId("gemini")!!.keyUrl) })
        box.addView(res)
        // v3.169: खोलते ही दिखे key सेव है या नहीं
        res.text = if (ed.text.isNotBlank()) "✓ key सेव है" + (if (Prefs.forgeAutoModel(this).isNotBlank()) " — चल रही है या नहीं देखने को \"जाँचें\" दबाएँ" else " — जाँच रहा हूँ…")
            else "✗ वेबसाइट बिल्डर की key नहीं डली — यहाँ Gemini key डालें (कोडिंग/चैट वाली key यहाँ नहीं चलती)"
        val other = listOf(Prefs.scopeKey(this, ApiScopes.CODER, "gemini"), Prefs.scopeKey(this, ApiScopes.CHAT, "gemini"), Prefs.ttsGeminiKey(this))
            .firstOrNull { it.isNotBlank() }
        if (ed.text.isBlank() && other != null) row(box, button("कोडिंग/चैट वाली Gemini key यहाँ लगाएँ") { ed.setText(other); check(false) })
        box.addView(modelTv); showModel()
        row(box, button("मॉडल बदलें") {
            if (models.isEmpty()) { toast("पहले \"जाँचें\" दबाएँ — मॉडल की सूची आ जाएगी"); return@button }
            val items = (listOf("अपने-आप (सबसे अच्छा)") + models).toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(UiLang.s("वेबसाइट का मॉडल"))
                .setItems(items) { _, i ->
                    Prefs.edit(this) { putString(Prefs.FORGE_MODEL, if (i == 0) "" else models[i - 1]) }
                    showModel()
                }.show()
        }, button("वेबसाइट बिल्डर खोलें") { com.dk.assistant.forge.ForgeLauncher.open(this) })
        hint(box, "सबसे अच्छा मॉडल अपने-आप: नया Pro → नया Flash → Lite (जो इस key पर सच में चले)। वेबसाइट बनाते समय उस मॉडल की limit खत्म हो तो " +
            "इसी key के अगले मॉडल पर, फिर भी न चले तो Key 2 पर — DK की दूसरी कोई key कभी नहीं।" +
            if (Prefs.oneKeyOn(this)) "\n\"एक key से सब\" चालू होने पर भी, यहाँ key डली हो तो वेबसाइट बनेगी।" else "")
        if (ed.text.isNotBlank() && Prefs.forgeAutoModel(this).isBlank()) check(true)
    }
}

/** DK लाइव: सिर्फ़ Google Gemini (Live + बोलना + सुनना) और सुनने के लिए चाहें तो Groq */
internal fun SettingsActivity.secBrainLive() {
    card("DK लाइव — Google Gemini", "Gemini Live बातचीत, Gemini नेचुरल आवाज़ और सुनना — सिर्फ़ इसी key से") { box ->
        val ed = field(box, "Gemini API Key (DK लाइव की अपनी)", Prefs.scopeKeyRaw(this, ApiScopes.LIVE, "gemini"), "AIza… यहाँ पेस्ट करें", secret = true)
        keyFields[ApiScopes.pref(ApiScopes.LIVE, "key", "gemini")] = ed
        // v3.94: बैकअप keys - मुख्य key बंद/गलत हो या उसकी limit खत्म हो तो DK लाइव चुपचाप अगली key से चलता रहे
        keyFields[ApiScopes.pref(ApiScopes.LIVE, "key", "gemini2")] = field(box, "Gemini API Key 2 (बैकअप, ज़रूरी नहीं)",
            Prefs.scopeKeyRaw(this, ApiScopes.LIVE, "gemini2"), "दूसरी Gemini key", secret = true)
        keyFields[ApiScopes.pref(ApiScopes.LIVE, "key", "gemini3")] = field(box, "Gemini API Key 3 (बैकअप, ज़रूरी नहीं)",
            Prefs.scopeKeyRaw(this, ApiScopes.LIVE, "gemini3"), "तीसरी Gemini key", secret = true)
        val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box, showButton(ed), button("टेस्ट करें") {
            save(false); res.text = "टेस्ट हो रहा है…"
            lifecycleScope.launch { res.text = BrainManager(this@secBrainLive).test(Providers.byId("gemini")!!, ApiScopes.LIVE) }
        }, button("Key लें") { Setup.openUrl(this, Providers.byId("gemini")!!.keyUrl) })
        box.addView(res)
        hint(box, "Limit खत्म हो तो इसी key के दूसरे Gemini Live / आवाज़ मॉडल पर अपने-आप। फिर भी न चले (key बंद/गलत या सारे मॉडल की limit खत्म) → " +
            "बातचीत के बीच ही चुपचाप Key 2, फिर Key 3। हर key अलग Google account/project की हो, तभी limit अलग मिलती है। DK चैट या किसी और हिस्से की key यहाँ इस्तेमाल नहीं होती।")
    }
    card("लिखना (\"आप:\" वाली लाइन) — अपनी अलग Gemini key", "आप जो बोलते हैं उसे लिखने वाला (gemini-3.5-transcribe-live) इसी key से चलेगा, ताकि बोलने वाले Live की free limit इसमें खर्च न हो") { box ->
        val ed = field(box, "Gemini API Key (सिर्फ़ लिखने के लिए)", Prefs.captionOwnKeyRaw(this), "AIza… (ज़रूरी नहीं — दूसरे Google account की key बेहतर)", secret = true)
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
        hint(box, "अभी: " + (if (Prefs.captionOwnKeyRaw(this).isNotBlank()) "लिखना अपनी अलग key से" else "लिखना DK लाइव वाली key से (अलग key नहीं डाली)") +
            ". एक ही Google account की दो keys की limit अक्सर साझा होती है — limit सच में बचानी हो तो दूसरे account की key डालें।")
        val lat = Prefs.liveLatency(this)
        if (lat.isNotEmpty()) hint(box, "बोलने वाले मॉडल — नापी गई रफ़्तार (सबसे तेज़ अपने-आप पहले):\n" +
            LiveModelRank.describe(LiveModelRank.order(lat.keys.toList(), emptyList(), lat), lat))
    }
    // v3.93: खोज का बैकअप क्रम - Tavily (मुख्य) -> Exa (दूसरा) -> Gemini + Google Search (आख़िरी). कोई भी key खाली रह सकती है.
    card("खोज — Tavily, Exa, Gemini (बैकअप क्रम)", "DK लाइव और चैट की इंटरनेट खोज पहले Tavily से; न चले तो अपने-आप Exa; वह भी न चले तो Gemini + Google Search") { box ->
        fun searchKey(title: String, pref: String, value: String, ph: String, url: String, check: (String) -> String) {
            val ed = field(box, title, value, ph, secret = true)
            keyFields[pref] = ed
            val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
            row(box, showButton(ed), button("जाँचें") {
                val k = ed.text.toString().trim()
                if (k.isBlank()) { res.text = "खाली है — खोज इसे छोड़कर अगली सेवा से होगी"; return@button }
                res.text = "जाँच रहा हूँ…"
                lifecycleScope.launch {
                    res.text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { check(k) }
                    save(false)
                }
            }, button("Key लें") { Setup.openUrl(this, url) })
            box.addView(res)
        }
        searchKey("Tavily API Key (मुख्य)", Prefs.TAVILY_SEARCH_KEY, Prefs.tavilySearchKey(this), "tvly-… (ज़रूरी नहीं)",
            WebSearch.TAVILY_KEY_URL) { WebSearchNet.checkTavily(it) }
        searchKey("Exa API Key (दूसरा)", Prefs.EXA_SEARCH_KEY, Prefs.exaSearchKey(this), "Exa key (ज़रूरी नहीं)",
            WebSearch.EXA_KEY_URL) { WebSearchNet.checkExa(it) }
        searchKey("Gemini API Key (सिर्फ़ खोज के लिए)", Prefs.SEARCH_KEY, Prefs.searchOwnKeyRaw(this), "AIza… (आख़िरी — खाली = DK लाइव / चैट वाली key)",
            Providers.byId("gemini")!!.keyUrl) { k ->
            if (ApiScopes.detect(k).let { it != null && it != "gemini" }) "✗ Key गलत है — यह Gemini की key नहीं लगती (AIza… से शुरू होती है)"
            else WebSearchNet.checkGemini(k)
        }
        val order = listOfNotNull(
            "Tavily".takeIf { Prefs.tavilySearchKey(this).isNotBlank() },
            "Exa".takeIf { Prefs.exaSearchKey(this).isNotBlank() },
            "Gemini".takeIf { BrainManager(this).searchKeys().isNotEmpty() })
        hint(box, "अभी का क्रम: " + (if (order.isEmpty()) "कोई key नहीं — इंटरनेट खोज बंद" else order.joinToString(" → ")) +
            ". जो सेवा न चले (key गलत / limit / गड़बड़ी) उसे 1 घंटे छोड़कर अगली से खोज — आपसे कुछ नहीं पूछा जाता। " +
            "खोज सिर्फ़ ताज़ा जानकारी (आज, ख़बर, भाव, मौसम, स्कोर) पर, एक सवाल पर एक बार, नतीजे 10 मिनट याद। Gemini खाली हो तो DK लाइव / चैट वाली key से।")
    }
    card("सुनना (आवाज़ → text) — Groq, चाहें तो", "Groq Whisper सबसे तेज़ सुनता है। खाली छोड़ें = ऊपर वाली Gemini key से सुनेगा") { box ->
        val ed = field(box, "Groq API Key (सिर्फ़ सुनने के लिए)", Prefs.scopeKeyRaw(this, ApiScopes.LIVE, "groq"), "gsk_… (ज़रूरी नहीं)", secret = true)
        keyFields[ApiScopes.pref(ApiScopes.LIVE, "key", "groq")] = ed
        val res = TextView(this).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 13f; setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0) }
        row(box, showButton(ed), button("जाँचें") {
            val k = ed.text.toString().trim()
            if (k.isBlank()) { res.text = "खाली है — सुनना Gemini key से होगा"; return@button }
            if (ApiScopes.detect(k).let { it != null && it != "groq" }) { res.text = "यह Groq की key नहीं लगती — Gemini key ऊपर वाले खाने में डालें"; return@button }
            res.text = "जाँच रहा हूँ…"
            lifecycleScope.launch {
                val r = KeySetup.setupVoice(this@secBrainLive, k)
                res.text = r.msg
                if (r.ok) DKAccessibilityService.instance?.startWakeLoopIfEnabled()
            }
        }, button("Groq key लें") { Setup.openUrl(this, "https://console.groq.com/keys") })
        box.addView(res)
        hint(box, "अभी: " + when {
            Prefs.scopeKeyRaw(this, ApiScopes.LIVE, "groq").isNotBlank() -> "Groq Whisper से सुन रहा है"
            Prefs.scopeKeyRaw(this, ApiScopes.LIVE, "gemini").isNotBlank() -> "Gemini से सुन रहा है"
            else -> "Google माइक से सुन रहा है (फ्री, बीप आ सकती है)"
        } + " • बिना-Live आवाज़ मोड में जवाब का text DK चैट की API से बनता है।")
    }
    // नवंबर 2026 (मालिक): DK लाइव में भी Z.ai और NVIDIA - "गहरी सोच" वाले जवाब के लिए (आवाज़ Gemini Live ही)
    card("गहरी सोच — Z.ai / NVIDIA, चाहें तो", "कठिन सवाल (गणित, तुलना, योजना) पर DK लाइव इन्हीं से सोचकर बोलता है — तेज़, " +
        "और Gemini Live की limit बचती है। खाली छोड़ें = पहले जैसा Gemini से।") { box ->
        hint(box, "बातचीत की आवाज़ (बोलना-सुनना) हमेशा Gemini Live से — यहाँ की key सिर्फ़ सोचने वाले text जवाब के लिए।")
    }
    ApiScopes.LIVE_TEXT.mapNotNull { Providers.byId(it) }.forEach { p -> providerCard(p, ApiScopes.LIVE) }
}

/**
 * v3.93: Gemini (खोज) key की जाँच - असली वजह + code. पहले: models-list फेल होने पर (जैसे इंटरनेट नहीं) भी "✓ key सही है",
 * और 429 के अलावा हर गड़बड़ी छिप जाती थी. अब [WebSearch.checkMessage]: 400/401/403 = Key गलत, 429 = limit, इंटरनेट = इंटरनेट की दिक्कत.
 */
internal fun SettingsActivity.checkSearchKey(k: String): String = WebSearchNet.checkGemini(k)

/** v3.41: लिखने वाली key सच में चलती है? (models list + transcribe मॉडल) */
internal fun SettingsActivity.checkCaptionKey(k: String): String = try {
    val c = com.dk.assistant.NetFix.builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
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

internal fun SettingsActivity.showButton(key: EditText) = button("दिखाओ") { v ->
    val show = key.inputType != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
    key.inputType = if (show) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    key.setSelection(key.text.length)
    (v as Button).text = if (show) "छुपाओ" else "दिखाओ"
}

internal fun SettingsActivity.scopeRadioBase(scope: String) = if (scope == ApiScopes.CODER) 6000 else 6100

internal fun SettingsActivity.secBrainScope(scope: String) {
    card("${ApiScopes.title(scope)} की AI", "${ApiScopes.title(scope)} सिर्फ़ यहाँ डाली key से चलेगा — DK चैट या किसी और की key नहीं") { box ->
        if (scope == ApiScopes.CODER) hint(box, "वेबसाइट बनाने की key अलग है: AI दिमाग → वेबसाइट बिल्डर (यहाँ की key से वेबसाइट नहीं बनती)")
        val cur = Prefs.scopeActiveRaw(this, scope)
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        Providers.all.forEachIndexed { i, p ->
            rg.addView(RadioButton(this).apply {
                id = scopeRadioBase(scope) + i
                text = p.name + (if (p.free) "   • फ्री" else "   • पेड") + if (Prefs.scopeKeyRaw(this@secBrainScope, scope, p.id).isBlank()) "   • key नहीं" else "   • ✓ key"
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

internal fun SettingsActivity.secBrain() {
    quickSetupCard()
    card("DK चैट की AI", "चैट, आम जवाब, बिना-Live आवाज़ मोड का जवाब, search, गहरी सोच और कहानी — सिर्फ़ इसी की key से") { box ->
        rgProvider = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        Providers.all.forEachIndexed { i, p ->
            rgProvider.addView(RadioButton(this).apply {
                id = 1000 + i
                text = p.name + if (p.free) "   • फ्री" else "   • पेड"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                isChecked = Prefs.activeProviderRaw(this@secBrain) == p.id
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

/** एक कंपनी का key कार्ड - [scope] = किस हिस्से की key (हर हिस्से की अलग setting) */
internal fun SettingsActivity.providerCard(p: Provider, scope: String = ApiScopes.CHAT) {
    val kName = ApiScopes.pref(scope, "key", p.id); val mName = ApiScopes.pref(scope, "model", p.id); val bName = ApiScopes.pref(scope, "base", p.id)
    card(p.name + if (scope == ApiScopes.CHAT) "" else " — ${ApiScopes.title(scope)}", p.note) { box ->
        // v5 (मालिक): Base URL वाला खाना नहीं - key से कंपनी अपने-आप (पता छुपे खाने में सेव), फिर मॉडल चुनें
        val key = field(box, "API Key", Prefs.scopeKeyRaw(this, scope, p.id), "यहाँ key पेस्ट करें", secret = true)
        keyFields[kName] = key
        if (p.isGemini && scope == ApiScopes.CODER) {
            // कोडिंग एजेंट: 5 Gemini keys - एक के सारे मॉडल की limit खत्म = अपने-आप अगली
            keyFields[ApiScopes.pref(scope, "key", "gemini2")] = field(box, "Gemini API Key 2 (बैकअप, ज़रूरी नहीं)",
                Prefs.scopeKeyRaw(this, scope, "gemini2"), "दूसरी Gemini key", secret = true)
            keyFields[ApiScopes.pref(scope, "key", "gemini3")] = field(box, "Gemini API Key 3 (बैकअप, ज़रूरी नहीं)",
                Prefs.scopeKeyRaw(this, scope, "gemini3"), "तीसरी Gemini key", secret = true)
            keyFields[ApiScopes.pref(scope, "key", "gemini4")] = field(box, "Gemini API Key 4 (बैकअप, ज़रूरी नहीं)",
                Prefs.scopeKeyRaw(this, scope, "gemini4"), "चौथी Gemini key", secret = true)
            keyFields[ApiScopes.pref(scope, "key", "gemini5")] = field(box, "Gemini API Key 5 (बैकअप, ज़रूरी नहीं)",
                Prefs.scopeKeyRaw(this, scope, "gemini5"), "पाँचवीं Gemini key", secret = true)
            box.addView(TextView(this).apply {
                text = "Key 1 के सारे मॉडल (3.8 Flash → 3.6 Flash → 3 Flash → 3.5 Flash-Lite → 3.1 Flash-Lite) की limit खत्म → अपने-आप Key 2 के सारे मॉडल → फिर Key 3 → Key 4 → Key 5। " +
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
            val cached = Prefs.models(this@providerCard, p.id)
            text = if (cached.isNotEmpty()) "${cached.size} मॉडल उपलब्ध — \"मॉडल चुनें\" दबाएँ" else "Key डालते ही इस कंपनी के सारे मॉडल यहाँ आ जाएँगे"
        }
        box.addView(mInfo)
        fun loadModels(openPicker: Boolean) {
            val k = key.text.toString().trim()
            if (k.length < 6) { mInfo.text = "पहले सही API key डालें"; return }
            val base = baseFields[bName]?.text?.toString()?.trim()?.ifBlank { null } ?: Prefs.scopeBase(this, scope, p.id)
            if (base.isBlank() && !p.isGemini) { mInfo.text = "पहले Base URL डालें"; return }
            mInfo.text = "मॉडल लोड हो रहे हैं…"
            lifecycleScope.launch {
                try {
                    val list = ModelLister.fetch(p, k, base)
                    fetchedFor[kName] = k
                    Prefs.edit(this@providerCard) { putString("models_${p.id}", list.joinToString("\n")) }
                    saveModelNames(p, list)
                    mInfo.text = "✓ ${list.size} मॉडल मिले — \"मॉडल चुनें\" दबाएँ"
                    if (openPicker && list.isNotEmpty()) pickModel(p, model, list, scope)
                } catch (e: Exception) {
                    mInfo.text = "मॉडल नहीं आए: " + BrainManager(this@providerCard).explain(e).take(160)
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
                if (cached.isNotEmpty() && fetchedFor[kName] == key.text.toString().trim()) pickModel(p, model, cached, scope)
                else loadModels(true)
            },
            button("लिस्ट रीफ़्रेश") { loadModels(false) })
        // नवंबर 2026 (मालिक): सोचने का स्तर (Thinking level) - Low / Medium / High, इसी हिस्से का अपना
        if (scope != ApiScopes.LIVE) thinkRow(box, scope, withHint = p.isGemini)
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
            lifecycleScope.launch { result.text = BrainManager(this@providerCard).test(p, scope) }
        })
        if (p.keyUrl.isNotBlank()) buttons.add(button("Key लें") { Setup.openUrl(this, p.keyUrl) })
        row(box, *buttons.toTypedArray())
        box.addView(result)
    }
}

/** मॉडल चुनने का खाना: [target] में चुना id + सेव ([scope] = किस हिस्से का सोचने का स्तर साथ में दिखे) */
internal fun SettingsActivity.pickModel(p: Provider, target: EditText, list: List<String>, scope: String = ApiScopes.CHAT) {
    pickModelThen(p, target.text.toString().trim(), list, if (Prefs.oneKeyOn(this)) ThinkLevel.ONE else scope) { id ->
        target.setText(id); save(false)
    }
}

/** नवंबर 2026: API से आए साफ़ नाम (Gemini displayName / OpenRouter name) फ़ोन में - अगली बार बिना इंटरनेट भी दिखें */
internal fun SettingsActivity.saveModelNames(p: Provider, list: List<String>) {
    val old = ModelNames.decode(Prefs.str(this, "model_names_${p.id}", ""))
    val fresh = list.mapNotNull { id -> ModelLister.names[id]?.let { id to it } }.toMap()
    if (fresh.isNotEmpty()) Prefs.edit(this) { putString("model_names_${p.id}", ModelNames.encode(old + fresh)) }
}

internal fun SettingsActivity.modelLabel(p: Provider, id: String): String =
    ModelNames.label(id, ModelLister.names[id] ?: ModelNames.decode(Prefs.str(this, "model_names_${p.id}", ""))[id])

/** "सोचने का स्तर: Medium — संतुलित गहराई" + बदलें बटन */
internal fun SettingsActivity.thinkRow(box: LinearLayout, scope: String, withHint: Boolean = true) {
    val t = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (2 * dp).toInt()) }
    fun show() { val l = ThinkLevel.norm(Prefs.str(this, ThinkLevel.pref(scope), ThinkLevel.AUTO)); t.text = "सोचने का स्तर (Thinking level): " + ThinkLevel.title(l) + " — " + ThinkLevel.desc(l) }
    show(); box.addView(t)
    row(box, button("सोचने का स्तर बदलें") {
        val cur = ThinkLevel.norm(Prefs.str(this, ThinkLevel.pref(scope), ThinkLevel.AUTO))
        val items = ThinkLevel.ALL.map { ThinkLevel.title(it) + "\n" + ThinkLevel.desc(it) }.toTypedArray()
        UiDialog(this, Glass.DIALOG)
            .setTitle("सोचने का स्तर (Thinking level)")
            .setSingleChoiceItems(items, ThinkLevel.ALL.indexOf(cur)) { d, which ->
                ThinkLevel.set(this, scope, ThinkLevel.ALL[which]); show(); d.dismiss()
                toast("सोचने का स्तर: " + ThinkLevel.title(ThinkLevel.ALL[which]))
            }
            .setNegativeButton("बंद करें", null)
            .show()
    })
    if (withHint) hint(box, "Low = जल्दी जवाब, Medium = संतुलित, High = मुश्किल सवाल/कोडिंग में ज़्यादा सोच (थोड़ा धीमा, limit जल्दी खर्च)। Auto = पहले जैसा। जो मॉडल सोचने का स्तर न समझे, वह अपने-आप बिना इसके चलता है।")
}

/**
 * नवंबर 2026 (मालिक, Gemini ऐप जैसा): ऊपर मॉडलों की list - हर एक का साफ़ नाम + छोटा विवरण (✓ = चुना हुआ),
 * नीचे "सोचने का स्तर" - Auto / Low / Medium / High. [thinkScope] = किस हिस्से का स्तर. [onPick] = मॉडल चुना.
 */
internal fun SettingsActivity.pickModelThen(p: Provider, current: String, list: List<String>, thinkScope: String, onPick: (String) -> Unit) {
    val saved = ModelNames.decode(Prefs.str(this, "model_names_${p.id}", ""))
    var chosen = current
    val pad = (12 * dp).toInt()
    val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, (4 * dp).toInt(), pad, 0) }
    val search = EditText(this).apply {
        hint = "मॉडल खोजें…"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x99FFFFFF.toInt()); isSingleLine = true
    }
    if (list.size > 10) outer.addView(search)
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = android.widget.ScrollView(this).apply { addView(inner) }
    outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6).toInt()))

    fun item(name: String, desc: String, on: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()); isClickable = true; isFocusable = true
        addView(TextView(this@pickModelThen).apply {
            text = if (on) "✓" else ""; textSize = 16f; setTextColor(0xFFD6EEFF.toInt()); width = (28 * dp).toInt()
        })
        addView(LinearLayout(this@pickModelThen).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@pickModelThen).apply { text = name; textSize = 17f; setTextColor(0xFFFFFFFF.toInt()); if (on) setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@pickModelThen).apply { text = desc; textSize = 13f; setTextColor(0xA6FFFFFF.toInt()) })
        })
        setOnClickListener { click() }
    }

    lateinit var render: () -> Unit
    render = {
        inner.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val shown = list.filter { id -> q.isBlank() || id.lowercase().contains(q) || ModelNames.label(id, ModelLister.names[id] ?: saved[id]).lowercase().contains(q) }
        shown.forEach { id ->
            val name = ModelNames.label(id, ModelLister.names[id] ?: saved[id])
            inner.addView(item(name, ModelNames.desc(id) + "\n" + id, id == chosen) {
                chosen = id; onPick(id); toast("मॉडल: $name"); render()
            })
        }
        if (shown.isEmpty()) inner.addView(TextView(this).apply { text = "कोई मॉडल नहीं मिला"; setTextColor(0xA6FFFFFF.toInt()); setPadding(0, pad, 0, pad) })
        inner.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)).apply { topMargin = pad; bottomMargin = (4 * dp).toInt() })
        inner.addView(TextView(this).apply {
            text = "सोचने का स्तर (Thinking level)"; textSize = 17f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        })
        val cur = ThinkLevel.norm(Prefs.str(this, ThinkLevel.pref(thinkScope), ThinkLevel.AUTO))
        ThinkLevel.ALL.forEach { l ->
            inner.addView(item(ThinkLevel.title(l), ThinkLevel.desc(l), l == cur) {
                ThinkLevel.set(this, thinkScope, l); toast("सोचने का स्तर: " + ThinkLevel.title(l)); render()
            })
        }
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { render() }
    })
    render()
    UiDialog(this, Glass.DIALOG)
        .setTitle("${p.name} — मॉडल चुनें (${list.size})")
        .setView(outer)
        .setPositiveButton("हो गया", null)
        .show()
}

/** कोडिंग / कंप्यूटर स्क्रीन पर: उनकी API अब AI दिमाग में उनके अपने विकल्प में */
internal fun SettingsActivity.apiLinkCard(scope: String, section: String) {
    card("${ApiScopes.title(scope)} की AI", "API key अब AI दिमाग → ${ApiScopes.title(scope)} में — सिर्फ़ इसी की अपनी") { box ->
        hint(box, "अभी: " + if (Prefs.oneKeyOn(this)) "✓ \"एक key से सब\" चालू — यह हिस्सा भी उसी एक Gemini key से" else scopeStatus(scope))
        row(box, button("${ApiScopes.title(scope)} की API खोलें") {
            save(false)
            // v3.91: एक-key मोड में अलग key वाला पन्ना नहीं - AI दिमाग (एक key वाला खाना) खोलो
            startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra("section", if (Prefs.oneKeyOn(this)) "brain" else section))
        })
    }
}
