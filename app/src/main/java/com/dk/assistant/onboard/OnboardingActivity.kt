package com.dk.assistant.onboard

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.dk.assistant.AssistantBrand
import com.dk.assistant.Glass
import com.dk.assistant.Prefs
import com.dk.assistant.R
import com.dk.assistant.Setup

/**
 * v3.58 (Part 11): पहली बार ऐप खोलने पर 4 पेज का परिचय - सिर्फ़ नए install पर।
 * 1) DK कौन  2) क्या-क्या बोल सकते हो  3) ज़रूरी इजाज़त (माइक, फ़ोन कंट्रोल)  4) आपका नाम।
 * "छोड़ें" हर पेज पर - कोई चीज़ ज़बरदस्ती नहीं।
 */
class OnboardingActivity : AppCompatActivity() {
    private val dp by lazy { resources.displayMetrics.density }
    private var page = 0
    private lateinit var body: LinearLayout
    private lateinit var btnNext: Button
    private lateinit var dots: TextView
    private var nameField: EditText? = null
    private var permStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sky)
            val p = (20 * dp).toInt(); setPadding(p, (36 * dp).toInt(), p, p)
        }
        val skip = TextView(this).apply {
            text = "छोड़ें"; textSize = 15f; setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.END
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); minHeight = (48 * dp).toInt()
            setOnClickListener { finishOnboarding() }
        }
        root.addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dots = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xCCFFFFFF.toInt()) }
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        btnNext = Button(this).apply {
            isAllCaps = false; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.btn_accent)
            setOnClickListener { next() }
        }
        root.addView(btnNext, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (54 * dp).toInt()).apply { topMargin = (12 * dp).toInt() })
        setContentView(root)
        Glass.install(this)
        show()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("page", page) }

    override fun onResume() { super.onResume(); refreshPerms() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page > 0) { page--; show() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun next() {
        if (page == 3) saveName()
        val n = OnboardingRules.next(page)
        if (n == null) finishOnboarding() else { page = n; show() }
    }

    private fun show() {
        body.removeAllViews(); nameField = null; permStatus = null
        val name = Prefs.assistantName(this)
        when (page) {
            0 -> {
                title("नमस्ते, मैं $name")
                text(AssistantBrand.TAGLINE)
                text("मुझसे बोलकर या लिखकर बात करें — सवाल पूछें, फ़ोन के काम कराएँ, कहानी लिखवाएँ या वेबसाइट बनवाएँ।")
            }
            1 -> {
                title("ऐसे बोलकर देखें")
                listOf("\"माँ को कॉल करो\"", "\"WhatsApp पर राहुल को मैसेज भेजो\"", "\"कल सुबह 7 बजे का अलार्म लगाओ\"",
                    "\"मेरी दुकान की वेबसाइट बनाओ\"").forEach { card(it) }
                text("Instagram / Facebook / WhatsApp पर पोस्ट या मैसेज भेजने से पहले मैं आपसे \"हाँ\" पूछूँगा।")
            }
            2 -> {
                title("ज़रूरी इजाज़त")
                text("सुनने के लिए माइक और आपके कहने पर फ़ोन चलाने के लिए Accessibility चाहिए। बाकी इजाज़तें बाद में सेटिंग्स → परमिशन में।")
                permStatus = text("")
                action("माइक की इजाज़त दें") {
                    val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                    if (android.os.Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                    ActivityCompat.requestPermissions(this, perms.toTypedArray(), 31)
                }
                action("फ़ोन कंट्रोल (Accessibility) चालू करें") { Setup.openAccessibility(this) }
                refreshPerms()
            }
            else -> {
                title("आपको क्या बुलाऊँ?")
                // 18 नवंबर 2026 (मालिक): Google लॉगिन चाहें तो - नाम अपने-आप आ जाता है (बदल सकते हैं). न करें तो भी ऐप वैसे ही चलता है
                if (com.dk.assistant.backend.BackendConfig.ready()) {
                    if (com.dk.assistant.backend.BackendSession.google(this)) text("✓ Google से जुड़े: " + com.dk.assistant.backend.BackendSession.email(this))
                    else action("Google से लॉगिन (चाहें तो)") { googleLogin() }
                }
                nameField = EditText(this).apply {
                    setText(Prefs.userName(this@OnboardingActivity).ifBlank { com.dk.assistant.backend.BackendSession.displayName(this@OnboardingActivity) }); hint = "आपका नाम"
                    setBackgroundResource(R.drawable.glass_input)
                    setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 17f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; isSingleLine = true
                    val p = (14 * dp).toInt(); setPadding(p, p, p, p)
                }
                body.addView(nameField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * dp).toInt() })
                // 15 नवंबर 2026 (मालिक): "बॉस / सर" एक टैप में - power बटन / Hey DK पर DK यही नाम लेकर बोलेगा ("जी बॉस")
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("बॉस", "सर", "मैडम").forEach { n ->
                    chips.addView(android.widget.Button(this).apply {
                        text = n; isAllCaps = false; setOnClickListener { nameField?.setText(n); nameField?.setSelection(n.length) }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                body.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
                text("Power बटन / \"हेलो DK\" पर DK इसी नाम से जवाब देगा - जैसे नाम \"बॉस\" = \"जी बॉस\", अभिवादन में \"हेलो बॉस\"।")
                text("नाम सिर्फ़ इस फ़ोन में रहता है। बाद में सेटिंग्स → प्रोफ़ाइल में बदल सकते हैं।")
            }
        }
        btnNext.text = if (page == OnboardingRules.PAGES - 1) "चलो शुरू करें" else "आगे →"
        dots.text = (0 until OnboardingRules.PAGES).joinToString("  ") { if (it == page) "●" else "○" }
        dots.contentDescription = "पेज नंबर ${page + 1} / ${OnboardingRules.PAGES}"
    }

    private fun refreshPerms() {
        val st = permStatus ?: return
        st.text = (if (Setup.hasMic(this)) "✓ " else "✗ ") + "माइक" + "     " +
            Setup.a11yMark(this) + "फ़ोन कंट्रोल"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
    }

    private fun saveName() {
        val n = nameField?.text?.toString()?.trim().orEmpty()
        if (n.isNotEmpty()) Prefs.edit(this) { putString("user_name", n) }
    }

    private fun finishOnboarding() {
        if (page == 3) saveName()
        markDone(this)
        // 18 नवंबर 2026 (मालिक): नाम के बाद एक ही key का option - ऑल-राउंडर Gemini key (मालिक को ज़रूरी नहीं, सर्वर की keys)
        if (OnboardingRules.askKey(hasKey(), com.dk.assistant.backend.Plan.level(this) == com.dk.assistant.backend.Plan.Level.OWNER)) try {
            startActivity(Intent(this, com.dk.assistant.SettingsActivity::class.java).putExtra("section", "brain")
                .putExtra(com.dk.assistant.SettingsActivity.EXTRA_FOCUS_KEY, true))
            android.widget.Toast.makeText(this, KEY_ASK, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) { android.util.Log.w("DK", "onboard", e) }
        finish()
    }

    private fun hasKey() = Prefs.oneKey(this).isNotBlank() || Prefs.liveGeminiKeys(this).isNotEmpty()

    // ---- Google लॉगिन (चाहें तो) - Settings → लाइसेंस की वाला ही तरीका
    private fun bg(work: () -> Unit) = Thread { try { work() } catch (e: Exception) { ui(com.dk.assistant.connect.GoogleAuth.explain(e)) } }.start()
    private fun ui(msg: String, then: () -> Unit = {}) = runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show(); then()
    }

    /** 18 नवंबर 2026: पहले फ़ोन के सारे Google खाते (popup) - जिससे चाहें */
    private fun googleLogin() {
        @Suppress("DEPRECATION")
        try { startActivityForResult(com.dk.assistant.connect.GoogleAuth.chooseAccountIntent(), RC_PICK) } catch (e: Exception) { googleLoginAs(null) }
    }

    private fun googleLoginAs(account: String?) = bg {
        when (val r = com.dk.assistant.connect.GoogleAuth.tokenBlocking(this, com.dk.assistant.backend.BackendSession.GOOGLE_SCOPES, account)) {
            is com.dk.assistant.connect.GoogleAuth.Res.Token -> finishGoogle(r.value)
            is com.dk.assistant.connect.GoogleAuth.Res.NeedsUi -> runOnUiThread {
                val pi = r.intent ?: return@runOnUiThread ui("Google की स्क्रीन नहीं खुली")
                @Suppress("DEPRECATION")
                try { startIntentSenderForResult(pi.intentSender, RC_GOOGLE, null, 0, 0, 0) } catch (e: Exception) { ui(com.dk.assistant.connect.GoogleAuth.explain(e)) }
            }
            is com.dk.assistant.connect.GoogleAuth.Res.Fail -> ui(r.message)
        }
    }

    private fun finishGoogle(token: String) = bg {
        com.dk.assistant.backend.BackendSession.signInGoogle(this, token)
        try { com.dk.assistant.backend.BackendApi.status(this) } catch (e: Exception) { android.util.Log.w("DK", "onboard", e) }
        val n = com.dk.assistant.backend.BackendSession.displayName(this)
        val boss = com.dk.assistant.backend.BackendSession.owner(this)
        ui("✓ Google से लॉगिन हुआ") {
            if (boss) com.dk.assistant.backend.WelcomeBoss.show(this)
            if (page == 3) {
                val typed = nameField?.text?.toString()?.trim().orEmpty()
                show()
                if (typed.isNotBlank()) nameField?.setText(typed)
                else if (n.isNotBlank()) nameField?.let { it.setText(n); it.setSelection(n.length) }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PICK) {
            com.dk.assistant.connect.GoogleAuth.pickedAccount(data)?.let { googleLoginAs(it) } ?: ui("कोई खाता नहीं चुना")
            return
        }
        if (requestCode != RC_GOOGLE) return
        try {
            val t = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).accessToken
            if (!t.isNullOrBlank()) finishGoogle(t) else ui("Google ने इजाज़त नहीं दी")
        } catch (e: Exception) { ui(com.dk.assistant.connect.GoogleAuth.explain(e)) }
    }

    private fun title(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 28f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setPadding(0, (40 * dp).toInt(), 0, (12 * dp).toInt())
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    private fun text(t: String): TextView {
        val v = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0); setLineSpacing(0f, 1.15f)
        }
        body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return v
    }

    private fun card(t: String) = body.addView(TextView(this).apply {
        text = t; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_card)
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() })

    private fun action(t: String, onClick: (View) -> Unit) = body.addView(Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_pill); setOnClickListener(onClick)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply { topMargin = (12 * dp).toInt() })

    companion object {
        private const val KEY = "onboarded"
        private const val RC_GOOGLE = 42
        private const val RC_PICK = 44
        const val KEY_ASK = "अब अपनी Gemini API key डालें — DK के सारे काम इसी एक key से चलेंगे"

        fun markDone(c: Context) = Prefs.edit(c) { putBoolean(KEY, true) }

        /** MainActivity से: नया install हो तो परिचय खोलो; पुराने उपयोगकर्ता पर हमेशा के लिए "हो गया" */
        fun maybeShow(a: android.app.Activity) {
            try {
                val onboarded = Prefs.onboarded(a)
                if (onboarded) return
                val info = a.packageManager.getPackageInfo(a.packageName, 0)
                val fresh = info.firstInstallTime == info.lastUpdateTime
                val hasName = Prefs.userName(a).isNotBlank()
                if (OnboardingRules.shouldShow(onboarded, fresh, hasName)) a.startActivity(Intent(a, OnboardingActivity::class.java))
                else markDone(a)
            } catch (e: Exception) { android.util.Log.w("DK", "onboard", e) }
        }
    }
}
