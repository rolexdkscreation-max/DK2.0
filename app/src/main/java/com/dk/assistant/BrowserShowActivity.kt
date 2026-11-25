package com.dk.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * 25 नवंबर 2026 (मालिक: "लास्ट वाला जो पेज है अगर मैं देखना चाहूँ तो वो मुझे वो पेज खोल के दिखाए, चाहे वो कोई भी काम कर रहा हो").
 * DK कंप्यूटर का छिपा ब्राउज़र ([ComputerBrowser]) यहीं पूरी स्क्रीन पर - वही WebView, इसलिए login / cart / बुकिंग / भुगतान
 * वाला पेज वैसे का वैसा. मालिक खुद देख / स्क्रॉल / भुगतान कर सकता है; DK का चलता काम पीछे नहीं रुकता.
 * बंद करने पर पेज फिर से छिपा (DK वहीं से आगे).
 */
class BrowserShowActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(UiFit.tablet(newBase)) }

    private var web: WebView? = null
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = ComputerBrowser.showIn(this)
        if (w == null) {
            Toast.makeText(this, UiLang.s("अभी DK का कोई खुला पेज नहीं है"), Toast.LENGTH_LONG).show()
            finish(); return
        }
        web = w
        val dp = resources.displayMetrics.density
        fun btn(t: String, onTap: () -> Unit) = TextView(this).apply {
            text = UiLang.s(t); textSize = 15f; setTextColor(0xFF9FD3FF.toInt()); gravity = Gravity.CENTER
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            setOnClickListener { onTap() }
        }
        title = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF121418.toInt())
            addView(btn("←") { back() })
            addView(title)
            addView(btn("ब्राउज़र में") { outside() })
            addView(btn("✕") { finish() })
        }
        val hint = TextView(this).apply {
            text = UiLang.s("यह DK का काम वाला पेज है — DK पीछे काम करता रहेगा। भुगतान / पासवर्ड / OTP आप खुद भरें।")
            textSize = 12f; setTextColor(0xFFB8C0CC.toInt()); setBackgroundColor(0xFF1B1E24.toInt())
            val p = (10 * dp).toInt(); setPadding(p, p / 2, p, p / 2)
        }
        val box = FrameLayout(this).apply {
            addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        refreshTitle()
    }

    private fun refreshTitle() {
        val w = web ?: return
        title.text = w.title?.takeIf { it.isNotBlank() } ?: w.url ?: ComputerBrowser.lastUrl
        title.postDelayed({ if (!isFinishing) refreshTitle() }, 1500)
    }

    private fun back() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else finish()
    }

    /** फ़ोन के अपने ब्राउज़र (Chrome) में वही पता - session वहाँ नहीं जाता, इसलिए यहीं देखना पहली पसंद */
    private fun outside() {
        val u = web?.url ?: ComputerBrowser.lastUrl
        if (!u.startsWith("http")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Quiet.note(e) }
    }

    override fun onDestroy() {
        web?.let { ComputerBrowser.hide(it) }
        web = null
        super.onDestroy()
    }
}
