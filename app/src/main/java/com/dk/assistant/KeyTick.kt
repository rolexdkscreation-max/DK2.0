package com.dk.assistant

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 14 नवंबर 2026 (मालिक): "API key डालेगा तो डॉट-डॉट के रूप में दिखे. key सही हो तो वहीं हरे रंग का टिक आए और फिर हट जाए -
 * पता चले कि हाँ, key सही है और चल रही है. सिर्फ़ लिखा नहीं, आवाज़ में भी."
 * key डालते / पेस्ट करते ही (थोड़ा रुककर) अपने-आप जाँच: सही = हरा ✓ + "API key सही है, चल रही है" बोलकर, 3 सेकंड बाद टिक गायब
 * और key फिर डॉट-डॉट. गलत = लाल ✗ थोड़ी देर. पहचानी न जाने वाली key (जैसे खोज की) पर कुछ नहीं.
 */
object KeyTick {
    const val WAIT_MS = 1_200L
    const val SHOW_MS = 3_000L
    const val OK_TEXT = "✓ API key सही है — चल रही है"
    const val OK_SAY = "API key सही है, चल रही है"
    const val GREEN = 0xFF22C55E.toInt()
    const val RED = 0xFFF87171.toInt()

    /** जाँचने लायक key = उसकी कंपनी (Gemini / Groq / OpenRouter / NVIDIA / Z.ai), नहीं तो null. Pure */
    fun provider(text: String): String? = text.trim().takeIf { it.length >= 20 && !it.contains(' ') }?.let { ApiScopes.detect(it) }

    /** key डॉट-डॉट (छुपी) */
    fun masked(): Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    fun visible(): Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    // ---------------------------------------------------------------- 16 नवंबर 2026: हर key का अपना कार्ड
    const val AMBER = 0xFFFBBF24.toInt()
    const val SAVE_TEXT = "सेव"
    const val TEST_TEXT = "टेस्ट करें"
    const val TESTING = "टेस्ट हो रहा है…"

    /** जाँच का नतीजा: OK = हरा ✓, BAD = लाल ✗, WARN = पीला (limit / इंटरनेट / यहाँ जाँच नहीं होती) */
    enum class Verdict { OK, BAD, WARN }

    fun verdict(msg: String): Verdict = when {
        msg.trimStart().startsWith("✓") -> Verdict.OK
        msg.trimStart().startsWith("✗") -> Verdict.BAD
        else -> Verdict.WARN
    }

    fun color(v: Verdict): Int = when (v) { Verdict.OK -> GREEN; Verdict.BAD -> RED; Verdict.WARN -> AMBER }

    /**
     * खाने का नाम key / token वाला? तो उसे कार्ड बनाओ. गुप्त शब्द (गार्ड) और ईमेल का App Password = कार्ड नहीं (वे API key नहीं).
     * Pure.
     */
    fun isKeyTitle(title: String): Boolean {
        val t = title.lowercase()
        if (t.contains("password") || t.contains("पासवर्ड") || t.contains("गुप्त")) return false
        return t.contains("key") || t.contains("token")
    }

    /** सेव key कैसी दिखे: "••••••••abcd" (पूरी कभी नहीं). खाली = "" . Pure */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.isEmpty()) return ""
        if (k.length <= 8) return "•".repeat(k.length)
        return "•".repeat(8) + k.takeLast(4)
    }

    /** खाने के नाम से कंपनी: "Groq API Key" = groq. AI कंपनी न हो तो github / youtube / tavily / exa / null. Pure */
    fun service(title: String, key: String = ""): String? {
        val k = key.trim()
        if (k.startsWith("ghp_") || k.startsWith("github_pat_") || k.startsWith("gho_")) return "github"
        provider(k)?.let { return it }
        val t = title.lowercase()
        return when {
            t.contains("github") -> "github"
            t.contains("youtube") -> "youtube"
            t.contains("hugging") -> "hf"
            t.contains("horde") -> "horde"
            t.contains("tavily") -> "tavily"
            t.contains("exa ") || t.startsWith("exa") -> "exa"
            t.contains("gemini") -> "gemini"
            t.contains("groq") -> "groq"
            t.contains("openrouter") -> "openrouter"
            t.contains("nvidia") -> "nvidia"
            t.contains("z.ai") || t.contains("glm") -> "zai"
            else -> null
        }
    }

    /** key कहाँ से लें (कार्ड का "Key लें →" लिंक). Pure */
    fun keyUrl(service: String?): String = when (service) {
        null -> ""
        "github" -> "https://github.com/settings/personal-access-tokens"
        "youtube" -> "https://console.cloud.google.com/apis/credentials"
        "tavily" -> "https://app.tavily.com/home"
        "exa" -> "https://dashboard.exa.ai/api-keys"
        else -> Providers.byId(service)?.keyUrl ?: ""
    }

    /** कार्ड की सलाह (छोटा डिब्बा). Pure */
    fun tip(service: String?): String = when (service) {
        "gemini" -> "Google AI Studio से मुफ़्त Gemini key लें - कार्ड नहीं चाहिए. हर key अलग Google project की हो तो limit अलग मिलती है."
        "groq" -> "Groq Console से मुफ़्त key लें (gsk_… से शुरू होती है)."
        "openrouter" -> "OpenRouter से मुफ़्त key लें (sk-or-… से शुरू). सिर्फ़ :free मॉडल चलेंगे, पैसे नहीं कटेंगे."
        "nvidia" -> "build.nvidia.com से मुफ़्त key लें (nvapi-… से शुरू)."
        "zai" -> "Z.ai से मुफ़्त key लें - GLM Flash मॉडल फ्री हैं."
        "github" -> "GitHub → Settings → Developer settings से token बनाएँ (Contents + Pull requests की इजाज़त दें)."
        "youtube" -> "Google Cloud Console में YouTube Data API v3 चालू करके मुफ़्त API key बनाएँ."
        "tavily" -> "Tavily से मुफ़्त key लें (हर महीने 1000 खोज मुफ़्त)."
        "exa" -> "Exa dashboard से मुफ़्त key लें."
        else -> ""
    }

    /** HTTP code से हिंदी नतीजा. Pure */
    fun fromCode(code: Int, okText: String = OK_TEXT): String = when (code) {
        in 200..299 -> okText
        400, 401, 403 -> "✗ key गलत है या बंद है (code $code) - दोबारा देखकर डालें"
        404 -> "✗ यह key इस सेवा की नहीं लगती (code 404)"
        429 -> "⚠ key सही है, पर अभी limit खत्म (429) - थोड़ी देर बाद अपने-आप चलेगी"
        0 -> "⚠ इंटरनेट नहीं मिला - नेट चालू करके फिर टेस्ट करें"
        else -> "⚠ सेवा ने जवाब नहीं दिया (code $code) - थोड़ी देर बाद टेस्ट करें"
    }

    /** पिछली जाँच की याद - key नहीं, सिर्फ़ उसका छोटा hash (log/prefs में key कभी नहीं) */
    fun hash(key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.trim().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun sp(c: android.content.Context) = c.applicationContext.getSharedPreferences("dk_key_checks", android.content.Context.MODE_PRIVATE)
    fun remember(c: android.content.Context, key: String, msg: String) {
        if (key.isBlank()) return
        sp(c).edit().putString(hash(key), msg.take(140)).apply()
    }
    fun last(c: android.content.Context, key: String): String? = if (key.isBlank()) null else sp(c).getString(hash(key), null)

    /** अपने-आप जाँच (कंपनी पहचानकर). [title] = खाने का नाम. नतीजा ✓ / ✗ / ⚠ से शुरू */
    suspend fun autoCheck(title: String, key0: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val key = key0.trim()
        if (key.isBlank()) return@withContext "⚠ पहले key डालें"
        when (val s = service(title, key)) {
            "github" -> http("https://api.github.com/user", mapOf("Authorization" to "Bearer $key", "Accept" to "application/vnd.github+json"),
                "✓ GitHub token सही है — चल रहा है")
            "youtube" -> http("https://www.googleapis.com/youtube/v3/i18nLanguages?part=snippet&hl=hi&key=" + java.net.URLEncoder.encode(key, "UTF-8"),
                emptyMap(), "✓ YouTube key सही है — चल रही है")
            "hf" -> http("https://huggingface.co/api/whoami-v2", mapOf("Authorization" to "Bearer $key"), "✓ Hugging Face token सही है — चल रहा है")
            "horde" -> http("https://aihorde.net/api/v2/find_user", mapOf("apikey" to key, "Client-Agent" to "DK:2.0:dk"), "✓ AI Horde key सही है — चल रही है")
            "tavily" -> WebSearchNet.checkTavily(key)
            "exa" -> WebSearchNet.checkExa(key)
            null -> "⚠ इस तरह की key की जाँच ऐप में नहीं होती - सेव करके इस्तेमाल करें"
            else -> {
                val p = Providers.byId(s) ?: return@withContext "⚠ इस key की जाँच ऐप में नहीं होती"
                val detected = provider(key)
                if (detected != null && detected != s && title.lowercase().contains(p.name.substringBefore(' ').lowercase()))
                    return@withContext "✗ यह ${p.name} की key नहीं लगती - ${Providers.byId(detected)?.name ?: detected} की है"
                try {
                    if (ModelLister.fetch(p, key, p.baseUrl).isNotEmpty()) OK_TEXT else "✗ ${p.name} ने कोई मॉडल नहीं दिया - key देखें"
                } catch (e: AiException) { fromCode(e.code) } catch (e: java.io.IOException) { fromCode(0) } catch (e: Exception) { fromCode(-1) }
            }
        }
    }

    private fun http(url: String, headers: Map<String, String>, okText: String): String = try {
        val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        con.connectTimeout = 10_000; con.readTimeout = 10_000
        headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        val code = con.responseCode
        con.disconnect()
        fromCode(code, okText)
    } catch (e: java.io.IOException) { fromCode(0) }
}

internal fun SettingsActivity.keyTick(box: LinearLayout, e: EditText) {
    val tick = TextView(this).apply {
        textSize = 13f; visibility = View.GONE
        setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
    }
    box.addView(tick)
    val ui = Handler(Looper.getMainLooper())
    var checked = e.text.toString().trim()        // पहले से सेव key = दोबारा नहीं (सिर्फ़ नई डाली)
    val hide = Runnable { tick.visibility = View.GONE }
    val run = Runnable {
        val k = e.text.toString().trim()
        val id = KeyTick.provider(k) ?: return@Runnable
        if (k == checked) return@Runnable
        checked = k
        val p = Providers.byId(id) ?: return@Runnable
        lifecycleScope.launch {
            val ok = try { withContext(Dispatchers.IO) { ModelLister.fetch(p, k, p.baseUrl) }.isNotEmpty() } catch (_: Exception) { false }
            if (e.text.toString().trim() != k) return@launch          // बीच में बदल दी
            ui.removeCallbacks(hide)
            tick.text = if (ok) KeyTick.OK_TEXT else "✗ यह ${p.name} key नहीं चली — दोबारा देखकर डालें"
            tick.setTextColor(if (ok) KeyTick.GREEN else KeyTick.RED)
            tick.visibility = View.VISIBLE
            if (ok) {
                // फिर से डॉट-डॉट ("दिखाओ" दबाया था तब भी)
                if (e.inputType != KeyTick.masked()) { e.inputType = KeyTick.masked(); e.setSelection(e.text.length) }
                launch { PhoneVoice.say(this@keyTick, KeyTick.OK_SAY) }
            }
            ui.postDelayed(hide, if (ok) KeyTick.SHOW_MS else KeyTick.SHOW_MS + 2_000L)
        }
    }
    e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(run); ui.postDelayed(run, KeyTick.WAIT_MS) }
    })
}

/**
 * 16 नवंबर 2026 (मालिक, तस्वीर जैसा): हर API key / token का अपना छोटा काँच वाला कार्ड -
 * ताला + नाम + छोटी लाइन, सलाह वाला डिब्बा, "Key लें →", डॉट-डॉट वाला खाना (दाईं तरफ़ ताला = दिखाओ / छुपाओ),
 * "सेव" (भरा बटन) + "टेस्ट करें" (किनारे वाला बटन), और नीचे हमेशा दिखने वाली लाइन: हरा "✓ सही है — चल रही है" / लाल ✗.
 * सेव key का हाल भी दिखता है ("सेव है: ••••abcd") ताकि डॉट-डॉट में भी पता रहे कि key डली है.
 * बैकअप keys (2, 3, 4, 5) - हर एक का अपना कार्ड और अपना टेस्ट.
 * [check] = अपनी जाँच (नतीजा ✓ / ✗ / ⚠ से शुरू); null = [KeyTick.autoCheck]. [onSave] = अपना सेव; null = पूरी Settings सेव.
 */
internal fun SettingsActivity.keyCard(box: LinearLayout, title: String, value: String, hint: String,
                                      keyUrl: String? = null, tip: String? = null,
                                      onSave: ((String) -> Unit)? = null,
                                      check: (suspend (String) -> String)? = null): EditText {
    val d = dp
    fun px(v: Int) = (v * d).toInt()
    val service = KeyTick.service(title, value)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.key_card_bg)
        setPadding(px(14), px(12), px(14), px(12))
    }
    box.addView(card, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })

    // ऊपर: ताला + नाम (सीधा कार्ड में - स्क्रीन पर नाम के ठीक नीचे वही खाना मिले), फिर छोटी लाइन
    val lockBadge = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_lock)?.mutate()?.apply {
        setTint(0xFF93C5FD.toInt()); setBounds(0, 0, px(20), px(20))
    }
    card.addView(TextView(this).apply {
        text = title; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setCompoundDrawablesRelative(lockBadge, null, null, null); compoundDrawablePadding = px(10)
        gravity = android.view.Gravity.CENTER_VERTICAL
    })
    val sub = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding(px(30), px(2), 0, 0) }
    card.addView(sub)

    // सलाह वाला डिब्बा
    // बैकअप key (2, 3, 4, 5) पर वही सलाह दोबारा नहीं - सिर्फ़ मुख्य key पर
    val tipText = tip ?: if (title.contains("बैकअप")) "" else KeyTick.tip(service)
    if (tipText.isNotBlank()) {
        val tb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundResource(R.drawable.key_tip_box)
            setPadding(px(10), px(8), px(10), px(8)); gravity = android.view.Gravity.CENTER_VERTICAL
        }
        tb.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_bulb) }, LinearLayout.LayoutParams(px(18), px(18)))
        tb.addView(TextView(this).apply { text = tipText; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(px(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(tb, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
    }
    // "Key लें →"
    val url = keyUrl ?: KeyTick.keyUrl(service)
    if (url.isNotBlank()) card.addView(TextView(this).apply {
        text = "Key लें →"; textSize = 13f; setTextColor(0xFF60A5FA.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(px(2), px(10), px(2), px(2))
        setOnClickListener { Setup.openUrl(this@keyCard, url) }
    })

    // डॉट-डॉट खाना + दाईं तरफ़ ताला (ताले पर दबाओ = दिखाओ / छुपाओ)
    fun lockIcon(open: Boolean) = androidx.core.content.ContextCompat.getDrawable(this, if (open) R.drawable.ic_lock_open else R.drawable.ic_lock)
        ?.mutate()?.apply { setTint(0x99FFFFFF.toInt()); setBounds(0, 0, px(20), px(20)) }
    val e = EditText(this).apply {
        setText(value); this.hint = hint
        setBackgroundResource(R.drawable.glass_input)
        setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 15f
        setPadding(px(14), px(12), px(14), px(12))
        inputType = KeyTick.masked(); isSingleLine = true
        transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()   // पक्का डॉट-डॉट
        setCompoundDrawablesRelative(null, null, lockIcon(false), null); compoundDrawablePadding = px(8)
        contentDescription = "API key"
        addTextChangedListener(watcher)
    }
    fun toggleShow() {
        val show = e.inputType != KeyTick.visible()
        e.inputType = if (show) KeyTick.visible() else KeyTick.masked()
        e.transformationMethod = if (show) null else android.text.method.PasswordTransformationMethod.getInstance()
        e.setSelection(e.text.length)
        e.setCompoundDrawablesRelative(null, null, lockIcon(show), null)
    }
    @Suppress("ClickableViewAccessibility")
    e.setOnTouchListener { v, ev ->
        val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val zone = px(44)
        val onLock = if (rtl) ev.x <= zone else ev.x >= v.width - zone
        if (onLock && ev.action == android.view.MotionEvent.ACTION_UP) { toggleShow(); true } else onLock && ev.action == android.view.MotionEvent.ACTION_DOWN
    }
    card.addView(e, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })

    // सेव (भरा) + टेस्ट करें (किनारे वाला)
    val status = TextView(this).apply { textSize = 13f; setPadding(px(2), px(8), 0, 0); visibility = View.GONE }
    fun show(msg: String) {
        status.text = msg; status.setTextColor(KeyTick.color(KeyTick.verdict(msg))); status.visibility = View.VISIBLE
    }
    fun refreshSub() {
        val k = e.text.toString().trim()
        sub.text = if (k.isBlank()) "अभी कोई key नहीं डली" else "सेव है: " + KeyTick.mask(k)
    }
    var testing = false
    fun doSave(silent: Boolean) {
        val k = e.text.toString().trim()
        if (onSave != null) onSave(k) else save(false)
        refreshSub()
        if (!silent) toast(if (k.isBlank()) "key हटा दी" else "key सेव हो गई")
    }
    val btnSave = Button(this).apply {
        text = KeyTick.SAVE_TEXT; isAllCaps = false; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.btn_accent); minHeight = 0; minimumHeight = 0
        setOnClickListener { doSave(false) }
    }
    val btnTest = Button(this).apply {
        text = KeyTick.TEST_TEXT; isAllCaps = false; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.key_outline_pill); minHeight = 0; minimumHeight = 0
    }
    btnTest.setOnClickListener {
        val k = e.text.toString().trim()
        if (k.isBlank()) { show("⚠ पहले key डालें"); return@setOnClickListener }
        if (testing) return@setOnClickListener
        testing = true; show(KeyTick.TESTING); status.setTextColor(0xB3FFFFFF.toInt())
        lifecycleScope.launch {
            val msg = try { check?.invoke(k) ?: KeyTick.autoCheck(title, k) } catch (ex: Exception) { KeyTick.fromCode(0) }
            testing = false
            if (e.text.toString().trim() != k) return@launch
            show(msg); KeyTick.remember(this@keyCard, k, msg)
            if (KeyTick.verdict(msg) == KeyTick.Verdict.OK) {
                doSave(true)                                   // सही key = अपने-आप सेव भी
                launch { PhoneVoice.say(this@keyCard, KeyTick.OK_SAY) }
                if (e.inputType != KeyTick.masked()) toggleShow()
            }
        }
    }
    val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(10), 0, 0) }
    br.addView(btnSave, LinearLayout.LayoutParams(0, px(42), 1f).apply { marginEnd = px(8) })
    br.addView(btnTest, LinearLayout.LayoutParams(0, px(42), 1f))
    card.addView(br)
    card.addView(status)

    // पहले से सेव key = पिछली जाँच का नतीजा तुरंत (नेट के बिना)
    refreshSub()
    KeyTick.last(this, value)?.let { show(it) }
    // key डालते / पेस्ट करते ही (थोड़ा रुककर) अपने-आप टेस्ट - नतीजा अब गायब नहीं होता
    val ui = Handler(Looper.getMainLooper())
    var autoFor = value.trim()
    val auto = Runnable {
        val k = e.text.toString().trim()
        if (k.isBlank() || k == autoFor || KeyTick.service(title, k) == null || k.length < 20) return@Runnable
        autoFor = k; btnTest.performClick()
    }
    e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            status.visibility = View.GONE          // key बदली = पुराना नतीजा अब इस key का नहीं
            ui.removeCallbacks(auto); ui.postDelayed(auto, KeyTick.WAIT_MS)
        }
    })
    return e
}
