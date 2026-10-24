package com.dk.assistant.forge

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import com.dk.assistant.Lang
import com.dk.assistant.UiLang
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DK AI ब्रीफ़ Part 14: Website Forge स्क्रीन.
 * बनते समय: पूरी स्क्रीन 3D "Forge Reactor" (assets/forge, WebGL) + दो तैरते HUD (ऊपर prompt chip, नीचे status card).
 * पूरा होने पर: बनी वेबसाइट अलग sandboxed WebView में पूरी स्क्रीन, अपने-आप छिपने वाला toolbar + नीचे-दाएँ हमेशा दिखने वाला handle.
 * scene WebView को किसी animation/graphicsLayer में नहीं रखा (काला दिखता है).
 */
class ForgeActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROMPT = "dk_forge_prompt"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val SCENE_URL = "https://$ASSET_HOST/assets/forge/forge.html"
    }

    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var site: WebView
    private var scene: WebView? = null
    private var fallback: ForgeFallbackView? = null
    private lateinit var chip: TextView
    private lateinit var card: LinearLayout
    private lateinit var stageTxt: TextView
    private lateinit var percentTxt: TextView
    private lateinit var trackBox: LinearLayout
    private lateinit var statsTxt: TextView
    private lateinit var errorTxt: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var retryBtn: TextView
    private lateinit var toolbar: HorizontalScrollView
    private lateinit var handle: TextView

    private val lowRam by lazy {
        val am = getSystemService(ActivityManager::class.java)
        am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 160
    }
    private val reducedMotion by lazy {
        try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }
    }

    private var sceneReady = false
    private var sceneFailed = false
    private var lastPushed = ""
    private var lastStage = -1
    /** किस buildId का आख़िरी HTML site में लगा / reveal हुआ */
    private var loadedBuild = -1
    private var revealedBuild = -1
    /** इस स्क्रीन ने यह build बनते देखा? (हाँ = reveal animation, नहीं = सीधे वेबसाइट) */
    private var watchedBuild = -1
    private var partialLen = 0
    private var partialAt = 0L
    private var pageFinished = false
    /** scene किस build के लिए तैयार है (नया build = scene फिर से शुरू, पुराना reveal/fail हटे) */
    private var sceneBuild = -1

    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val f = ForgeController.state.value.file ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
            toast("वेबसाइट सेव हो गई")
        } catch (_: Exception) { toast("सेव नहीं हो पाई") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = ForgeTheme.BG; window.navigationBarColor = ForgeTheme.BG
        root = FrameLayout(this).apply { setBackgroundColor(ForgeTheme.BG) }
        site = buildSiteView()
        root.addView(site, FrameLayout.LayoutParams(-1, -1))
        if (lowRam) addFallback() else addScene()
        buildHud()
        buildToolbar()
        setContentView(root)
        ForgeController.pendingOpen = false
        handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ForgeController.state.collect { render(it) } }
        }
        ui.post(pushLoop)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val prompt = i?.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
        i?.removeExtra(EXTRA_PROMPT)
        if (prompt.isNotBlank()) {
            val r = ForgeController.start(this, prompt)
            if (r !is ForgeController.StartResult.Started) toast(r.message)
        } else if (ForgeController.state.value.phase == ForgePhase.IDLE) {
            askPrompt()
        }
    }

    override fun onResume() {
        super.onResume()
        ForgeController.screenVisible = true
        scene?.onResume(); site.onResume()
    }

    override fun onPause() {
        ForgeController.screenVisible = false
        scene?.onPause(); site.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        scene?.let { w -> try { w.evaluateJavascript("window.Forge&&Forge.dispose()", null) } catch (_: Exception) {}; w.destroy() }
        scene = null
        site.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- views

    private fun glass(radius: Float, fill: Int = 0xB817143A.toInt()) = GradientDrawable().apply {
        cornerRadius = radius; setColor(fill); setStroke((1 * dp).toInt().coerceAtLeast(1), 0x33FFFFFF)
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFF1A1206.toInt() else ForgeTheme.TEXT)
        setTypeface(typeface, Typeface.BOLD)
        background = if (primary) GradientDrawable().apply { cornerRadius = 999f; setColor(ForgeTheme.ACCENT) } else glass(999f)
        minHeight = (48 * dp).toInt(); minWidth = (48 * dp).toInt()
        val px = (16 * dp).toInt(); setPadding(px, 0, px, 0)
        isClickable = true; isFocusable = true
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addScene() {
        val loader = WebViewAssetLoader.Builder().setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val w = WebView(this).apply {
            setBackgroundColor(ForgeTheme.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != ASSET_HOST) return WebResourceResponse("text/plain", "utf-8", null)  // scene से कोई network नहीं
                    return loader.shouldInterceptRequest(request.url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) { initScene(0) }
            }
            addJavascriptInterface(Bridge(), "ForgeBridge")
        }
        scene = w
        root.addView(w, FrameLayout.LayoutParams(-1, -1))
        w.loadUrl(SCENE_URL)
    }

    private fun initScene(attempt: Int) {
        val w = scene ?: return
        val opts = JSONObject().put("lowRam", lowRam).put("reducedMotion", reducedMotion)
            .put("labels", JSONArray(ForgeStage.entries.map { if (UiLang.en()) it.label else it.labelHi }))
        val js = "(function(){if(!window.Forge)return 'wait';Forge.init(${JSONObject.quote(ForgeTheme.json())},${JSONObject.quote(opts.toString())});return 'ok';})()"
        w.evaluateJavascript(js) { r ->
            if (r?.contains("wait") == true && attempt < 40) ui.postDelayed({ initScene(attempt + 1) }, 100)
            else if (r?.contains("wait") == true) onSceneFailed()
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun onReady() = ui.post { sceneReady = true; lastPushed = ""; render(ForgeController.state.value) }.let { }
        @JavascriptInterface fun onRevealFinished() = ui.post { finishReveal() }.let { }
        @JavascriptInterface fun onWebGLFailed() = ui.post { onSceneFailed() }.let { }
    }

    private fun onSceneFailed() {
        if (sceneFailed) return
        sceneFailed = true
        scene?.let { root.removeView(it); it.destroy() }
        scene = null; sceneReady = false
        addFallback()
        // HUD/toolbar फिर से सबसे ऊपर
        listOf(chip, card, toolbar, handle).forEach { it.bringToFront() }
        render(ForgeController.state.value)
    }

    private fun addFallback() {
        val f = ForgeFallbackView(this, reducedMotion)
        fallback = f
        root.addView(f, 1.coerceAtMost(root.childCount), FrameLayout.LayoutParams(-1, -1))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildSiteView() = WebView(this).apply {
        setBackgroundColor(ForgeTheme.BG)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true; settings.loadWithOverviewMode = true
        contentDescription = UiLang.s("बनी हुई वेबसाइट")
        // कोई JavascriptInterface नहीं - बनी वेबसाइट ऐप से बात नहीं कर सकती
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "localhost") return false               // पेज के अंदर के #anchor
                try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
                return true
            }
            override fun onPageFinished(view: WebView, url: String) { pageFinished = true }
        }
    }

    private fun buildHud() {
        val m = (16 * dp).toInt()
        chip = TextView(this).apply {
            textSize = 13f; setTextColor(ForgeTheme.TEXT); maxLines = 2
            background = glass(999f); val px = (16 * dp).toInt(); setPadding(px, (10 * dp).toInt(), px, (10 * dp).toInt())
        }
        root.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (40 * dp).toInt(); leftMargin = m; rightMargin = m })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glass(28 * dp)
            val p = (18 * dp).toInt(); setPadding(p, p, p, p)
        }
        stageTxt = TextView(this).apply {
            textSize = 15f; setTextColor(ForgeTheme.ACCENT); setTypeface(typeface, Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        percentTxt = TextView(this).apply { textSize = 44f; setTextColor(ForgeTheme.TEXT); typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
        trackBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(6) {
            trackBox.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = 99f; setColor(0x33FFFFFF) } },
                LinearLayout.LayoutParams(0, (6 * dp).toInt(), 1f).apply { marginEnd = if (it < 5) (4 * dp).toInt() else 0 })
        }
        statsTxt = TextView(this).apply { textSize = 12f; setTextColor(ForgeTheme.TEXT2) }
        errorTxt = TextView(this).apply { textSize = 14f; setTextColor(ForgeTheme.ERROR); visibility = View.GONE }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        retryBtn = pill("दोबारा बनाएँ", primary = true) { val r = ForgeController.retry(this); if (r !is ForgeController.StartResult.Started) toast(r.message) }
        cancelBtn = pill("रद्द करें") {
            if (ForgeController.state.value.phase == ForgePhase.BUILDING) ForgeController.cancel() else finish()
        }
        row.addView(retryBtn, LinearLayout.LayoutParams(-2, (48 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
        row.addView(cancelBtn, LinearLayout.LayoutParams(-2, (48 * dp).toInt()))
        card.addView(stageTxt); card.addView(percentTxt)
        card.addView(trackBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt(); bottomMargin = (10 * dp).toInt() })
        card.addView(statsTxt); card.addView(errorTxt)
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = m; rightMargin = m; bottomMargin = (28 * dp).toInt() })
    }

    private fun buildToolbar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = glass(999f, 0xD917143A.toInt())
            val p = (6 * dp).toInt(); setPadding(p, p, p, p)
        }
        fun add(label: String, onClick: () -> Unit) =
            bar.addView(pill(label) { onClick(); scheduleHide() }, LinearLayout.LayoutParams(-2, (48 * dp).toInt()).apply { marginEnd = (4 * dp).toInt() })
        add("← वापस") { finish() }
        add("शेयर") { share() }
        add("सेव") { ForgeController.state.value.file?.let { saveDoc.launch(it.name) } }
        add("ब्राउज़र") { openInBrowser() }
        add("बदलें") { askEdit() }
        add("दोबारा बनाएँ") { val r = ForgeController.retry(this); if (r !is ForgeController.StartResult.Started) toast(r.message) }
        add("मेरी वेबसाइटें") { showList() }
        add("नई") { askPrompt() }
        add("हटाएँ") { confirmDelete() }
        toolbar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar); visibility = View.GONE }
        root.addView(toolbar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (36 * dp).toInt(); leftMargin = (10 * dp).toInt(); rightMargin = (10 * dp).toInt() })
        handle = TextView(this).apply {
            text = "☰"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(ForgeTheme.TEXT); background = glass(999f, 0xD917143A.toInt())
            contentDescription = UiLang.s("वेबसाइट का मेन्यू"); visibility = View.GONE
            setOnClickListener { showToolbar() }
        }
        root.addView(handle, FrameLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = (16 * dp).toInt(); bottomMargin = (24 * dp).toInt() })
    }

    private val hideRun = Runnable { toolbar.animate().alpha(0f).setDuration(200).withEndAction { toolbar.visibility = View.GONE; toolbar.alpha = 1f }.start() }
    private fun scheduleHide() { ui.removeCallbacks(hideRun); ui.postDelayed(hideRun, 4000) }
    private fun showToolbar() { toolbar.visibility = View.VISIBLE; toolbar.alpha = 1f; scheduleHide() }

    // ---------------------------------------------------------------- state

    private val pushLoop = object : Runnable {
        override fun run() {
            pushScene(ForgeController.state.value)
            refreshPartial(ForgeController.state.value)
            ui.postDelayed(this, 60)
        }
    }

    private fun pushScene(s: ForgeUiState) {
        val w = scene ?: return
        if (!sceneReady) return
        val j = JSONObject().put("phase", s.phase.name).put("stage", s.stage.ordinal).put("progress", s.progress)
            .put("chars", s.metrics.chars).put("sections", s.sections.size).put("logSeq", s.logSeq).put("log", JSONArray(s.log.takeLast(4)))
            .toString()
        if (j == lastPushed) return
        lastPushed = j
        w.evaluateJavascript("window.Forge&&Forge.setState($j)", null)
    }

    /** बनते समय site WebView में अधूरा पेज (हर 1.5 s, कम से कम 4 KB बढ़ने पर; कम RAM पर नहीं) */
    private fun refreshPartial(s: ForgeUiState) {
        if (lowRam || s.phase != ForgePhase.BUILDING) return
        val now = System.currentTimeMillis()
        if (now - partialAt < 1500) return
        val html = ForgeController.partialHtml()
        if (html.length - partialLen < 4096) return
        partialAt = now; partialLen = html.length
        site.loadDataWithBaseURL("https://localhost/", ForgeHtml.repairTruncated(ForgeHtml.stripFences(html)), "text/html", "utf-8", null)
    }

    private fun immersive(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) { c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(WindowInsetsCompat.Type.systemBars()) }
        else c.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun render(s: ForgeUiState) {
        val building = s.phase == ForgePhase.BUILDING
        if (building) {
            watchedBuild = s.buildId
            if (revealedBuild == s.buildId) revealedBuild = -1
            if (sceneBuild != s.buildId) {
                if (sceneBuild != -1) {
                    lastStage = -1; partialLen = 0
                    scene?.let { w -> sceneReady = false; lastPushed = ""; w.evaluateJavascript("window.Forge&&Forge.dispose()") { initScene(0) } }
                }
                sceneBuild = s.buildId
            }
        }
        val showStage = building || s.phase == ForgePhase.FAILED || s.phase == ForgePhase.CANCELLED ||
            (s.phase == ForgePhase.DONE && revealedBuild != s.buildId && watchedBuild == s.buildId)
        immersive(building)
        scene?.visibility = if (showStage) View.VISIBLE else View.GONE
        fallback?.visibility = if (showStage) View.VISIBLE else View.GONE
        chip.visibility = if (showStage) View.VISIBLE else View.GONE
        card.visibility = if (showStage && s.phase != ForgePhase.DONE) View.VISIBLE else View.GONE
        chip.text = (if (s.isEdit) "✎ " else "✦ ") + s.prompt.take(90)

        fallback?.let { f -> f.progress = s.progress / 100f; f.stage = s.stage.ordinal; f.failed = s.phase == ForgePhase.FAILED; f.sections = s.sections.size }

        val label = if (UiLang.en()) s.stage.label else s.stage.labelHi
        if (s.stage.ordinal != lastStage) {
            if (lastStage >= 0 && building) card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastStage = s.stage.ordinal
            stageTxt.text = label
            if (!reducedMotion) { stageTxt.translationY = 18 * dp; stageTxt.alpha = 0f; stageTxt.animate().translationY(0f).alpha(1f).setDuration(300).start() }
        }
        percentTxt.text = "${s.progress}%"
        for (i in 0 until trackBox.childCount) {
            val on = i < s.stage.ordinal || (i == s.stage.ordinal && building) || s.phase == ForgePhase.DONE
            (trackBox.getChildAt(i).background as GradientDrawable).setColor(
                when { !on -> 0x33FFFFFF; i == s.stage.ordinal && building -> ForgeTheme.ACCENT; else -> ForgeTheme.LAGOON })
        }
        val m = s.metrics
        statsTxt.text = "${m.chars / 1024} KB  •  ${m.sections} " + UiLang.s("हिस्से") + "  •  ${m.tokensPerSec} tok/s  •  ${m.elapsedSec} s"
        when (s.phase) {
            ForgePhase.FAILED -> {
                errorTxt.visibility = View.VISIBLE; errorTxt.text = s.error ?: UiLang.s("वेबसाइट नहीं बन पाई।")
                retryBtn.visibility = View.VISIBLE; cancelBtn.text = UiLang.s("बंद करें")
                if (sceneReady) scene?.evaluateJavascript("window.Forge&&Forge.fail()", null)
            }
            ForgePhase.CANCELLED -> {
                errorTxt.visibility = View.VISIBLE; errorTxt.text = UiLang.s("रद्द कर दिया")
                retryBtn.visibility = View.VISIBLE; cancelBtn.text = UiLang.s("बंद करें")
            }
            else -> { errorTxt.visibility = View.GONE; retryBtn.visibility = View.GONE; cancelBtn.text = UiLang.s("रद्द करें") }
        }
        if (s.phase == ForgePhase.DONE) onDone(s)
        else { toolbar.visibility = View.GONE; handle.visibility = View.GONE }
    }

    private fun onDone(s: ForgeUiState) {
        val f = s.file ?: return
        if (loadedBuild != s.buildId) {
            loadedBuild = s.buildId; pageFinished = false
            val html = try { f.readText() } catch (_: Exception) { toast("फ़ाइल नहीं खुली"); return }
            site.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
            if (watchedBuild == s.buildId && revealedBuild != s.buildId) {
                // पेज बनने (paint) तक रुको, ज़्यादा से ज़्यादा 2.5 s, फिर reveal
                val start = System.currentTimeMillis()
                val wait = object : Runnable {
                    override fun run() {
                        if (pageFinished || System.currentTimeMillis() - start > 2500) startReveal(s.buildId) else ui.postDelayed(this, 100)
                    }
                }
                ui.postDelayed(wait, 150)
            } else revealedBuild = s.buildId
        }
        if (revealedBuild == s.buildId) {
            scene?.visibility = View.GONE; fallback?.visibility = View.GONE; chip.visibility = View.GONE; card.visibility = View.GONE
            handle.visibility = View.VISIBLE
        }
    }

    private fun startReveal(build: Int) {
        if (revealedBuild == build) return
        val w = scene
        if (w != null && sceneReady && !reducedMotion) w.evaluateJavascript("window.Forge&&Forge.reveal()", null)
        else finishReveal()
    }

    private fun finishReveal() {
        val s = ForgeController.state.value
        if (s.phase != ForgePhase.DONE) return
        revealedBuild = s.buildId
        render(s)
        showToolbar()
        toast(Lang.pick("आपकी वेबसाइट तैयार है", "Your website is ready"))
    }

    // ---------------------------------------------------------------- actions

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun share() {
        val f = ForgeController.state.value.file ?: return
        val i = Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_STREAM, uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, UiLang.s("वेबसाइट शेयर करें")))
    }

    private fun openInBrowser() {
        val f = ForgeController.state.value.file ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), "text/html").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, UiLang.s("ब्राउज़र में खोलें"))) } catch (_: ActivityNotFoundException) { toast("कोई ब्राउज़र नहीं मिला") }
    }

    private fun input(title: String, hint: String, onOk: (String) -> Unit) {
        val e = EditText(this).apply {
            this.hint = UiLang.s(hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; val p = (20 * dp).toInt(); setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this).setTitle(UiLang.s(title)).setView(e)
            .setPositiveButton(UiLang.s("बनाओ")) { _, _ -> e.text.toString().trim().takeIf { it.isNotBlank() }?.let(onOk) }
            .setNegativeButton(UiLang.s("रहने दो")) { _, _ -> if (ForgeController.state.value.phase == ForgePhase.IDLE) finish() }
            .setOnCancelListener { if (ForgeController.state.value.phase == ForgePhase.IDLE) finish() }
            .show()
    }

    private fun askPrompt() = input("कैसी वेबसाइट बनाऊँ?", "जैसे: नासिक में एक कैफ़े की वेबसाइट, मेन्यू और संपर्क के साथ") { p ->
        val r = ForgeController.start(this, p)
        if (r !is ForgeController.StartResult.Started) toast(r.message)
    }

    private fun askEdit() = input("वेबसाइट में क्या बदलूँ?", "जैसे: रंग हरा कर दो और एक गैलरी जोड़ो") { c ->
        val r = ForgeController.edit(this, c)
        if (r !is ForgeController.StartResult.Started) toast(r.message)
    }

    private fun showList() {
        val files = ForgeController.savedSites(this)
        if (files.isEmpty()) { toast("अभी कोई वेबसाइट सेव नहीं है"); return }
        val names = files.map { f -> java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified())) + "  •  " + (f.length() / 1024) + " KB" }
        AlertDialog.Builder(this).setTitle(UiLang.s("मेरी वेबसाइटें"))
            .setItems(names.toTypedArray()) { _, i -> ForgeController.openSaved(files[i]) }
            .setNegativeButton(UiLang.s("बंद करें"), null).show()
    }

    private fun confirmDelete() {
        val f = ForgeController.state.value.file ?: return
        AlertDialog.Builder(this).setTitle(UiLang.s("यह वेबसाइट हटाएँ?"))
            .setMessage(UiLang.s("फ़ोन से यह फ़ाइल हमेशा के लिए हट जाएगी।"))
            .setPositiveButton(UiLang.s("हटाएँ")) { _, _ ->
                f.delete(); ForgeController.reset(); toast("वेबसाइट हटा दी"); finish()
            }
            .setNegativeButton(UiLang.s("रहने दो"), null).show()
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}
