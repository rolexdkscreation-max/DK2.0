package com.dk.assistant.studio

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.dk.assistant.ComputerTools
import com.dk.assistant.DKComposer
import com.dk.assistant.Glass
import com.dk.assistant.R
import com.dk.assistant.UiDialog
import com.dk.assistant.UiLang
import com.dk.assistant.forge.LocalSite
import java.io.File

/**
 * ऐप स्टूडियो की स्क्रीन: ऊपर बनने के कदम, बीच में चलता हुआ preview (WebView), नीचे "क्या बदलना है" (लिखकर / 🎤).
 * हर फ़ाइल लिखते ही preview ताज़ा. preview में गलती = नीचे पट्टी + "ठीक करो" (बनने के बाद एक बार अपने-आप).
 * Chrome में खोलना / उसी Wi-Fi के दूसरे फ़ोन-लैपटॉप के लिए लिंक / ZIP / code देखना - ऊपर के बटन.
 */
class StudioActivity : AppCompatActivity() {
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var titleTv: TextView
    private lateinit var sub: TextView
    private lateinit var stepsBox: LinearLayout
    private lateinit var stepsText: TextView
    private lateinit var stopBtn: Button
    private lateinit var errBar: LinearLayout
    private lateinit var errText: TextView
    private lateinit var composer: DKComposer
    private var shownVersion = -1
    private var shownSlug = ""
    private var wasBusy = false
    private val errors = mutableListOf<String>()
    /** नवंबर 2026 (मालिक): साइड में लाइव कोड - बनते समय हर फ़ाइल पूरी टाइप होती दिखे + project की बाकी फ़ाइलें; रंग बदलने का विकल्प */
    private lateinit var codePanel: com.dk.assistant.CodeLivePanel
    private lateinit var codeBtn: Button

    private val loader by lazy {
        WebViewAssetLoader.Builder().setDomain(HOST)
            .addPathHandler("/studio/", WebViewAssetLoader.InternalStoragePathHandler(this, StudioProject.root(this))).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (StudioSession.project == null) StudioSession.project = StudioProject.all(this).firstOrNull()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg)) }
        val pad = (10 * dp).toInt()

        // ---- ऊपर: वापस + नाम + बटन
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad, pad, (6 * dp).toInt()) }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((10 * dp).toInt(), 0, 0, 0) }
        titleTv = TextView(this).apply { textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        sub = TextView(this).apply { textSize = 11.5f; setTextColor(0x99FFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        titles.addView(titleTv); titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, 0, pad, (6 * dp).toInt()) }
        fun addBtn(t: String, f: () -> Unit) = btns.addView(pill(t, f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
        addBtn("↻ ताज़ा") { reload(true) }
        addBtn(com.dk.assistant.CodeLivePanel.label(0)) { codePanel.toggle() }
        codeBtn = btns.getChildAt(btns.childCount - 1) as Button
        addBtn("🌐 Chrome") { openChrome() }
        addBtn("📶 Wi-Fi लिंक") { shareLan() }
        addBtn("📦 ZIP") { shareZip() }
        addBtn("🗂 प्रोजेक्ट") { pickProject() }
        addBtn("＋ नया ऐप") { newApp() }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(btns) })

        // ---- बनने के कदम
        stepsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.glass_card)
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt()); visibility = View.GONE
        }
        stepsText = TextView(this).apply { textSize = 12.5f; setTextColor(0xDDFFFFFF.toInt()); setLineSpacing(0f, 1.12f) }
        stepsBox.addView(stepsText, LinearLayout.LayoutParams(0, -2, 1f))
        stopBtn = pill("रोकें") { StudioSession.stop() }
        stepsBox.addView(stopBtn)
        root.addView(stepsBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, 0, pad, (6 * dp).toInt()) })

        // ---- preview
        web = makeWeb()
        root.addView(FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); addView(web, FrameLayout.LayoutParams(-1, -1)) },
            LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- गलतियाँ
        errBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xCC5A1E1E.toInt())
            setPadding(pad, (6 * dp).toInt(), pad, (6 * dp).toInt()); visibility = View.GONE
        }
        errText = TextView(this).apply { textSize = 12f; setTextColor(0xFFFFE0E0.toInt()); maxLines = 2 }
        errBar.addView(errText, LinearLayout.LayoutParams(0, -2, 1f))
        errBar.addView(pill("🩺 ठीक करो") { StudioSession.fix(this, errors.toList())?.let { toast(it) }; render() })
        root.addView(errBar, LinearLayout.LayoutParams(-1, -2))

        // ---- बदलाव (लिखकर / बोलकर)
        composer = DKComposer(this, "क्या बदलना / जोड़ना है… (जैसे: बटन नीला करो, login पेज जोड़ो)", maxLines = 4)
        composer.onSend = { send() }
        composer.send.setOnClickListener { if (StudioSession.busy) StudioSession.stop() else send() }
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(pad, (6 * dp).toInt(), pad, pad) })

        val frame = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        codePanel = com.dk.assistant.CodeLivePanel(this, { who -> StudioSession.project?.let { who == com.dk.assistant.CodeLive.studio(it.slug) } == true }, extra = { diskFiles() }) { n ->
            codeBtn.text = UiLang.s(com.dk.assistant.CodeLivePanel.label(n)) }
        codePanel.attachTo(frame)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (codePanel.isOpen) codePanel.close() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() } }
        })
        setContentView(frame)
        Glass.install(this)
        com.dk.assistant.PlainLook.install(this)   // 14 नवंबर 2026 (मालिक): होम (DKAI) जैसा सपाट रूप, काँच/ग्लासी नहीं
        intent.getStringExtra("slug")?.let { s -> StudioProject.open(this, s)?.let { StudioSession.project = it } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true                     // बने ऐप का localStorage
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE           // हर बदलाव तुरंत दिखे
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) return null
                // preview में service worker नहीं (पुरानी फ़ाइलें cache न करे - हर बदलाव तुरंत दिखे). Chrome / Wi-Fi लिंक पर असली PWA
                val path = request.url.path.orEmpty()
                if (path.endsWith("/sw.js") || path.endsWith("service-worker.js")) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
                return loader.shouldInterceptRequest(request.url)
            }
            override fun onPageFinished(view: WebView, url: String) { checkErrorsLater() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) addError("पेज नहीं खुला: ${error.description}")
                else if (request.url.host == HOST) addError("फ़ाइल नहीं मिली: ${request.url.path?.substringAfter("/studio/")?.substringAfter('/')}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
                val p = request.url.path.orEmpty()
                if (request.url.host == HOST && r.statusCode == 404 && !p.endsWith("sw.js") && !p.endsWith("favicon.ico") && !p.endsWith("service-worker.js"))
                    addError("फ़ाइल नहीं मिली: ${p.substringAfter("/studio/").substringAfter('/')}")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) addError(StudioCore.errorLine(m.message(), m.sourceId(), m.lineNumber()))
                return true
            }
        }
    }

    private fun addError(e: String) = ui.post {
        if (e.isBlank() || e in errors || errors.size >= 20) return@post
        errors.add(e); renderErrors()
    }

    private fun renderErrors() {
        if (errors.isEmpty()) { errBar.visibility = View.GONE; return }
        errBar.visibility = View.VISIBLE
        errText.text = "⚠ preview में ${errors.size} गलती: " + errors.first().take(140)
    }

    /** बनना पूरा + पेज खुला + गलती = एक बार अपने-आप ठीक */
    private fun checkErrorsLater() = ui.postDelayed({
        if (!isFinishing && errors.isNotEmpty() && !StudioSession.busy && StudioSession.autoFixLeft > 0) {
            StudioSession.fix(this, errors.toList(), auto = true)
            render()
        }
    }, 2500)

    private fun url(p: StudioProject) = "https://$HOST/studio/${p.slug}/" + p.entry() + "?v=" + StudioSession.version

    private fun reload(force: Boolean) {
        val p = StudioSession.project
        if (p == null || !p.hasPreview()) {
            shownSlug = p?.slug.orEmpty(); shownVersion = StudioSession.version
            val msg = if (p == null) "अभी कोई ऐप नहीं बना।<br><br>नीचे लिखिए / बोलिए क्या बनाना है — जैसे<br><b>\"एक todo ऐप बनाओ\"</b><br><b>\"Android नेटिव का expense tracker बनाओ\"</b>"
                else if (StudioSession.busy) "⏳ बन रहा है… पहली फ़ाइल आते ही यहीं दिखेगा।" else "इस project में अभी preview वाली फ़ाइल नहीं है।"
            web.loadDataWithBaseURL(null, "<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body style='font-family:sans-serif;background:#0b1020;color:#e8ecff;" +
                "display:flex;align-items:center;justify-content:center;height:90vh;text-align:center;padding:24px;line-height:1.6'><div>$msg</div></body></html>", "text/html", "utf-8", null)
            return
        }
        if (!force && p.slug == shownSlug && StudioSession.version == shownVersion) return
        shownSlug = p.slug; shownVersion = StudioSession.version
        errors.clear(); renderErrors()
        web.loadUrl(url(p))
    }

    private var lastReloadAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 700)
        }
    }

    private fun render() {
        val p = StudioSession.project
        val busy = StudioSession.busy
        titleTv.text = p?.title ?: "ऐप स्टूडियो"
        sub.text = if (p == null) "कोई भी ऐप बनाइए — preview यहीं" else "${p.kind.hi} • ${p.files().size} फ़ाइलें • " +
            (if (p.who == StudioSession.WHO_CODER) "कोडिंग एजेंट" else "DK कंप्यूटर") + if (busy) " • बन रहा है…" else ""
        val steps = StudioSession.stepsNow()
        if (busy || (wasBusy && steps.isNotEmpty())) {
            stepsBox.visibility = View.VISIBLE
            stepsText.text = steps.takeLast(4).joinToString("\n")
            stopBtn.visibility = if (busy) View.VISIBLE else View.GONE
        } else if (!busy && stepsBox.visibility == View.VISIBLE && System.currentTimeMillis() - doneAt > 6000) stepsBox.visibility = View.GONE
        if (wasBusy && !busy) doneAt = System.currentTimeMillis()
        wasBusy = busy
        composer.send.setImageResource(if (busy) android.R.drawable.ic_media_pause else R.drawable.ic_send)
        // बनते समय भी: नई फ़ाइल = preview ताज़ा (बहुत जल्दी-जल्दी नहीं)
        val now = System.currentTimeMillis()
        if ((p?.slug.orEmpty() != shownSlug || StudioSession.version != shownVersion) && now - lastReloadAt > 900) { lastReloadAt = now; reload(false) }
    }
    private var doneAt = 0L

    private fun send() {
        val t = composer.edt.text.toString().trim()
        if (t.isEmpty()) return
        composer.edt.setText("")
        val p = StudioSession.project
        val err = if (p == null) StudioSession.start(this, t, StudioSession.WHO_COMPUTER, openScreen = false) else StudioSession.change(this, t)
        err?.let { toast(it) }
        render()
    }

    private fun newApp() {
        val e = android.widget.EditText(this).apply { hint = "क्या बनाना है? (जैसे: Android नेटिव का notes ऐप)"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x88FFFFFF.toInt()) }
        UiDialog(this, Glass.DIALOG).setTitle("नया ऐप").setView(e)
            .setPositiveButton("बनाओ") { _, _ ->
                val t = e.text.toString().trim()
                if (t.isNotEmpty()) StudioSession.start(this, t, StudioSession.project?.who ?: StudioSession.WHO_COMPUTER, openScreen = false)?.let { toast(it) }
                render()
            }.setNegativeButton("रहने दें", null).show()
    }

    private fun pickProject() {
        val all = StudioProject.all(this)
        if (all.isEmpty()) { toast("अभी कोई project नहीं"); return }
        UiDialog(this, Glass.DIALOG).setTitle("ऐप स्टूडियो के project")
            .setItems(all.map { "${it.title.take(40)}  •  ${it.kind.hi.substringBefore(' ')}" }.toTypedArray()) { _, i ->
                if (StudioSession.busy) { toast("अभी काम चल रहा है"); return@setItems }
                StudioSession.project = all[i]; reload(true); render()
            }.setNegativeButton("बंद करें", null).show()
    }

    /** लाइव कोड पैनल के लिए: इस project की सारी (text) फ़ाइलें, डिस्क से */
    private fun diskFiles(): List<Pair<String, String>> {
        val p = StudioSession.project ?: return emptyList()
        return p.files().filter { !com.dk.assistant.BinaryGuard.isBinary(it) }.take(80)
            .mapNotNull { f -> try { p.read(f)?.let { f to it.take(com.dk.assistant.CodeLive.MAX_CHARS) } } catch (_: Exception) { null } }
    }

    private fun openChrome() {
        val p = StudioSession.project?.takeIf { it.hasPreview() } ?: run { toast("अभी preview नहीं"); return }
        if (LocalSite.openStudio(this, p.slug, p.entry()) == null) toast("ब्राउज़र नहीं खुला")
    }

    private fun shareLan() {
        val p = StudioSession.project?.takeIf { it.hasPreview() } ?: run { toast("अभी preview नहीं"); return }
        val port = LocalSite.ensure(this)
        val link = LocalSite.lanStudioUrl(p.slug, p.entry(), port)
        if (port == 0 || link == null) { toast("Wi-Fi / हॉटस्पॉट नहीं मिला — उसी Wi-Fi पर हों तभी दूसरा फ़ोन / लैपटॉप खोल सकता है"); return }
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "${p.title}\n$link\n(उसी Wi-Fi पर, DK ऐप खुला रहने तक)")
        startActivity(android.content.Intent.createChooser(i, "लिंक भेजें"))
    }

    private fun shareZip() {
        val p = StudioSession.project ?: run { toast("अभी कोई project नहीं"); return }
        try {
            val z = p.zip(File(File(cacheDir, "studio_zip"), "${p.slug}.zip"))
            ComputerTools.share(this, z)
        } catch (e: Exception) { com.dk.assistant.DKLog.d("Studio: ZIP गलती ${e.javaClass.simpleName}: ${e.message?.take(160)}"); toast("ZIP नहीं बन पाया, फिर से कोशिश करें") }
    }

    private fun pill(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12.5f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(R.drawable.glass_pill)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (11 * dp).toInt(); setPadding(ph, (6 * dp).toInt(), ph, (6 * dp).toInt())
        isFocusable = false
        setOnClickListener { f() }
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        StudioSession.listener = { ui.post { render() } }
        reload(true); render()
        ui.removeCallbacks(tick); ui.postDelayed(tick, 700)
    }

    override fun onPause() { StudioSession.listener = null; ui.removeCallbacks(tick); super.onPause() }

    override fun onDestroy() { try { web.destroy() } catch (_: Exception) {}; super.onDestroy() }

    companion object { const val HOST = "appassets.androidplatform.net" }
}
