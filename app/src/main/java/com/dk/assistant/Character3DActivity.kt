package com.dk.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.dk.assistant.plus.Character3D
import com.dk.assistant.plus.FeatureFlags
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 15 नवंबर 2026: DK का 3D कैरेक्टर (मालिक: "3D कैरेक्टर… इंटरनेट से डाउनलोड").
 * पहली बार: कैरेक्टर + GLTFLoader डाउनलोड (MB प्रगति), फिर WebView + three.js में चलता-फिरता कैरेक्टर.
 * DK बोले तो कैरेक्टर हल्का हिलता है; छूकर घुमाएँ, टैप = अगला एनिमेशन. कोई network नहीं (सिर्फ़ ऐप के अंदर की फ़ाइलें).
 */
class Character3DActivity : AppCompatActivity() {
    private var web: WebView? = null
    private lateinit var status: TextView
    private lateinit var asset: ModelCatalog.Asset
    private var dl: Job? = null
    private val stateL: () -> Unit = { runOnUiThread { pushTalk() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        asset = intent.getStringExtra(EXTRA_ID)?.let { ModelCatalog.byId(it) }?.takeIf { it.kind == ModelCatalog.Kind.CHARACTER } ?: Character3D.selected(this)
        Character3D.select(this, asset)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF070A16.toInt()) }
        status = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0) }
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, (28 * d).toInt()) }
        fun b(t: String, f: () -> Unit) = Button(this).apply {
            text = UiLang.s(t); isAllCaps = false; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(0x33FFFFFF) }
            setOnClickListener { f() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(-2, (46 * d).toInt()).apply { marginStart = (6 * d).toInt(); marginEnd = (6 * d).toInt() }) }
        b("अगला एनिमेशन") { js("Char.next()") }
        b("दूसरा कैरेक्टर") { switchTo(ModelCatalog.CHARACTERS[(ModelCatalog.CHARACTERS.indexOf(asset) + 1) % ModelCatalog.CHARACTERS.size]) }
        b("बंद") { finish() }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        if (!FeatureFlags.on(this, FeatureFlags.CHARACTER_3D)) {
            status.text = UiLang.s("3D कैरेक्टर बंद है - Settings → दिखावट → 3D कैरेक्टर में चालू करें")
            return
        }
        if (FeatureFlags.suspendedByBattery(this, FeatureFlags.BS_3D)) {
            status.text = UiLang.s("बैटरी सेवर में 3D कैरेक्टर रुका है - बैटरी सेवर बंद करें")
            return
        }
        start()
        DKState.listen(stateL)
    }

    private fun switchTo(a: ModelCatalog.Asset) {
        if (!FeatureFlags.on(this, FeatureFlags.CHARACTER_3D)) return
        asset = a; Character3D.select(this, a)
        if (Character3D.ready(this, a) && web != null) { status.text = ""; js("Char.load(${JSONObject.quote(a.fileName)})") } else start()
    }

    private fun start() {
        if (Character3D.ready(this, asset)) { show(); return }
        dl?.cancel()
        val need = (ModelCatalog.VIEWER_LIBS + asset).filterNot { ModelManager.ready(this, it) }
        val total = need.sumOf { it.bytes }
        status.text = UiLang.s("${asset.title} डाउनलोड हो रहा है… ") + DlText.progress(0, total)
        dl = lifecycleScope.launch {
            val tick = launch {
                while (isActive) {
                    val got = need.sumOf { x ->
                        when (val st = ModelManager.state(x.id).value) { is ModelManager.St.Running -> st.done; ModelManager.St.Ready -> x.bytes; else -> 0L }
                    }
                    val pct = if (total > 0) ((got.coerceAtMost(total)) * 100 / total).toInt() else 100
                    status.text = UiLang.s("${asset.title} डाउनलोड हो रहा है… ") + DlText.progress(pct, total)
                    delay(400)
                }
            }
            val err = Character3D.ensure(this@Character3DActivity, asset)
            tick.cancel()
            if (err != null) status.text = UiLang.s("डाउनलोड नहीं हुआ: ") + err + UiLang.s("\nइंटरनेट जाँचकर फिर खोलें") else show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun show() {
        status.text = ""
        web?.let { js("Char.load(${JSONObject.quote(asset.fileName)})"); return }
        val loader = WebViewAssetLoader.Builder().setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/models/", Character3D.pathHandler(this)).build()
        val w = WebView(this).apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != HOST) return WebResourceResponse("text/plain", "utf-8", null)   // बाहर कोई network नहीं
                    return loader.shouldInterceptRequest(request.url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) { tryLoad(0) }
            }
            addJavascriptInterface(Bridge(), "DKChar")
        }
        web = w
        (status.parent as FrameLayout).addView(w, 0, FrameLayout.LayoutParams(-1, -1))
        w.loadUrl("https://$HOST/assets/character/character.html")
    }

    private fun tryLoad(attempt: Int) {
        web?.evaluateJavascript("(function(){if(!window.Char)return 'wait';Char.load(${JSONObject.quote(asset.fileName)});return 'ok';})()") { r ->
            if (r?.contains("wait") == true) { if (attempt < 50) web?.postDelayed({ tryLoad(attempt + 1) }, 100) else status.text = UiLang.s("3D चालू नहीं हुआ (WebView पुराना है?)") }
        }
    }

    private fun js(s: String) { web?.evaluateJavascript("try{$s}catch(e){}", null) }
    private fun pushTalk() { js("Char.talk(${DKState.mode == Mode.SPEAKING})") }

    private inner class Bridge {
        @JavascriptInterface fun onReady(names: String) { runOnUiThread { status.text = ""; pushTalk() } }
        @JavascriptInterface fun onError(m: String) { runOnUiThread { status.text = UiLang.s("कैरेक्टर नहीं खुला: ") + m } }
        @JavascriptInterface fun onTap() { runOnUiThread { js("Char.next()") } }
    }

    override fun onDestroy() {
        DKState.unlisten(stateL)
        dl?.cancel()
        web?.let { (it.parent as? FrameLayout)?.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "char_id"
        private const val HOST = "appassets.androidplatform.net"
        fun open(c: Context, id: String? = null) = c.startActivity(Intent(c, Character3DActivity::class.java)
            .apply { if (id != null) putExtra(EXTRA_ID, id) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
