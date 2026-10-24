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
                nameField = EditText(this).apply {
                    setText(Prefs.userName(this@OnboardingActivity)); hint = "आपका नाम"
                    setBackgroundResource(R.drawable.glass_input)
                    setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF.toInt()); textSize = 17f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; isSingleLine = true
                    val p = (14 * dp).toInt(); setPadding(p, p, p, p)
                }
                body.addView(nameField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * dp).toInt() })
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
            (if (Setup.accessibilityOn(this)) "✓ " else "✗ ") + "फ़ोन कंट्रोल"
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
        finish()
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
            } catch (_: Exception) { }
        }
    }
}
