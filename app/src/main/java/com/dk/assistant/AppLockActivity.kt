package com.dk.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 21 नवंबर 2026 (मालिक): ऐप लॉक की futuristic स्क्रीन - काला-नीला निऑन, ऐप का आइकन, और ताला खोलने के तरीके:
 * DK सीक्रेट PIN (● बिंदु), DK पैटर्न, फ़ोन का अपना फ़िंगरप्रिंट / फ़ेस / स्क्रीन लॉक (Android BiometricPrompt),
 * और DK फ़ेस स्कैन (होलोग्राम + हर फ़्रेम AI anti-spoof + सख़्त मिलान, [QuickFaceGate] - लाइव चेहरा ही, फ़ोटो / वीडियो नहीं).
 * back = होम पर (ताले वाला ऐप नहीं खुलता). [AppLock] खोलता है.
 */
class AppLockActivity : AppCompatActivity() {
    /** 23 नवंबर 2026 (मालिक): टैबलेट पर पूरा UI स्क्रीन के अनुपात में बड़ा; फ़ोन पर कुछ नहीं बदलता ([UiFit.tablet]) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(com.dk.assistant.UiFit.tablet(newBase)) }
    private var pkg = ""
    private var label = ""
    private var done = false
    private var sysAuth = false
    private lateinit var status: TextView
    private lateinit var body: FrameLayout
    private lateinit var chips: LinearLayout
    private var method = AppLockRules.Method.PIN
    private var cancel: CancellationSignal? = null
    // DK फ़ेस
    private var cam: FaceCam? = null
    private var holo: HoloScanView? = null
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-applock-face").apply { isDaemon = true } }
    @Volatile private var busy = false
    // 22 नवंबर 2026: तेज़ लाइव जाँच - बिना सिर घुमाए; AI पक्का न हो तभी एक छोटा random घुमाव ([QuickFaceGate])
    @Volatile private var gate = QuickFaceGate()
    /** 22 नवंबर 2026: आख़िरी लाइव ताला - स्क्रीन की random रंगीन रोशनी ([FlashLive]) - वीडियो / फ़ोटो इसका जवाब नहीं दे सकते */
    private val flashScr by lazy { FlashScreen(this) }
    @Volatile private var pauseUntil = 0L
    private var lastUi = 0L
    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        if (pkg.isEmpty() || !AppLockStore.hasPin(this)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // ताले की स्क्रीन का screenshot / रिकॉर्डिंग नहीं
        val pm = packageManager
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        label = ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(com.dk.assistant.ui.ClayTheme.t.bg)   // 23 नवंबर 2026 (मालिक): Clay - रंग थीम से
            val p = (18 * d).toInt(); setPadding(p, (36 * d).toInt(), p, p)
        }
        // आइकन निऑन घेरे में
        val ring = FrameLayout(this).apply {
            background = com.dk.assistant.ui.Clay.circle(this@AppLockActivity)
            val p = (14 * d).toInt(); setPadding(p, p, p, p)
            addView(ImageView(this@AppLockActivity).apply {
                try { setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { setImageResource(R.drawable.ic_s_applock) }
            }, FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
        }
        root.addView(ring, LinearLayout.LayoutParams(-2, -2))
        root.addView(text("DK SECURE LOCK", 12f, com.dk.assistant.ui.ClayTheme.t.accent, mono = true).apply { letterSpacing = 0.3f; setPadding(0, (12 * d).toInt(), 0, 0) })
        root.addView(text("$label लॉक है", 22f, com.dk.assistant.ui.ClayTheme.t.text, bold = true))
        status = text("", 14f, com.dk.assistant.ui.ClayTheme.t.sub).apply { minLines = 2; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
        root.addView(status)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-2, -2))
        setContentView(root)
        // back = होम पर (ताले वाला ऐप नहीं खुलता)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goHome() }
        })
        method = AppLockStore.method(this).takeIf { available(it) } ?: AppLockRules.Method.PIN
        buildChips()
        use(method)
    }

    /** singleTask: ताला खुला रहते किसी दूसरे ताले वाले ऐप की बारी - उसी का ताला नए सिरे से */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        val p = i.getStringExtra(EXTRA_PKG).orEmpty()
        if (p.isNotEmpty() && p != pkg && !done) { setIntent(i); recreate() }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = UiLang.s(t); textSize = size; setTextColor(color); gravity = Gravity.CENTER
        typeface = if (mono) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun say(t: String, tone: Int = com.dk.assistant.ui.ClayTheme.t.sub) = runOnUiThread { status.text = UiLang.s(t); status.setTextColor(tone) }

    /** इस फ़ोन पर यह तरीका चलेगा? */
    private fun available(m: AppLockRules.Method): Boolean = when (m) {
        AppLockRules.Method.PIN -> AppLockStore.hasPin(this)
        AppLockRules.Method.PATTERN -> AppLockStore.hasPattern(this)
        AppLockRules.Method.PHONE -> Guard.secure(this)
        AppLockRules.Method.FACE -> FaceAI.ready(this) && FaceAI.enrolled(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun bioLabel(): String {
        val fp = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val face = packageManager.hasSystemFeature("android.hardware.biometrics.face")
        return when {
            fp && face -> "फ़िंगरप्रिंट / फ़ेस"
            fp -> "फ़िंगरप्रिंट"
            face -> "फ़ोन का फ़ेस"
            else -> "फ़ोन का स्क्रीन लॉक"
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        for (m in AppLockRules.Method.values()) {
            if (!available(m)) continue
            val name = when (m) {
                AppLockRules.Method.PIN -> "PIN"
                AppLockRules.Method.PATTERN -> "पैटर्न"
                AppLockRules.Method.PHONE -> bioLabel()
                AppLockRules.Method.FACE -> "DK फ़ेस स्कैन"
            }
            val k = com.dk.assistant.ui.ClayTheme.t
            chips.addView(text(name, 13f, if (m == method) k.onAccent else k.accent, bold = true).apply {
                val p = (12 * d).toInt(); setPadding(p, (8 * d).toInt(), p, (8 * d).toInt())
                background = if (m == method) com.dk.assistant.ui.Clay.accent(this@AppLockActivity) else com.dk.assistant.ui.Clay.chip(this@AppLockActivity)
                setOnClickListener { use(m) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt(); topMargin = (8 * d).toInt() })
        }
    }

    private fun use(m: AppLockRules.Method) {
        stopFace()
        cancel?.cancel(); cancel = null
        method = m
        buildChips()
        body.removeAllViews()
        val wait = AppLockStore.waitLeft(this)
        when (m) {
            AppLockRules.Method.PIN -> {
                // 22 नवंबर 2026 (मालिक): सही PIN पूरा होते ही तुरंत खुले - OK नहीं. गलत = वहीं लाल, गिनती बढ़े ([AppLockStore.P_PIN_LEN])
                val auto = AppLockStore.pinLen(this)
                val pad = NeonPinPad(this, autoLen = auto).apply { onDone = { p -> tryPin(p, this) } }
                body.addView(pad, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                say(if (wait > 0) waitText(wait) else if (auto > 0) PIN_AUTO_HINT else "DK सीक्रेट PIN डालें, फिर OK")
            }
            AppLockRules.Method.PATTERN -> {
                val pv = NeonPattern(this).apply { onDone = { dots -> tryPattern(dots, this) } }
                body.addView(pv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                say(if (wait > 0) waitText(wait) else "DK पैटर्न बनाइए")
            }
            AppLockRules.Method.PHONE -> {
                body.addView(text("◉\n${bioLabel()} से खोलें\n(यहाँ छुएँ)", 18f, com.dk.assistant.ui.ClayTheme.t.accent).apply { setOnClickListener { phoneAuth() } },
                    FrameLayout.LayoutParams(-1, -1))
                phoneAuth()
            }
            AppLockRules.Method.FACE -> startFace()
        }
    }

    private fun waitText(ms: Long) = "बहुत बार गलत — ${AppLockRules.waitLabel(ms)} बाद फिर कोशिश करें (या फ़ोन का लॉक / DK फ़ेस से खोलें)"

    private fun tryPin(p: String, pad: NeonPinPad) {
        val wait = AppLockStore.waitLeft(this)
        if (wait > 0) { say(waitText(wait), HoloScanView.WARN); return }
        if (AppLockStore.checkPin(this, p)) { pad.grant(); ok("PIN"); return }
        pad.reject()
        wrong()
    }

    private fun tryPattern(dots: List<Int>, pv: NeonPattern) {
        val wait = AppLockStore.waitLeft(this)
        if (wait > 0) { say(waitText(wait), HoloScanView.WARN); pv.clear(); return }
        if (dots.size < AppLockRules.PATTERN_MIN) { say("कम से कम ${AppLockRules.PATTERN_MIN} बिंदु जोड़िए", HoloScanView.WARN); pv.clear(); return }
        if (AppLockStore.checkPattern(this, dots)) { ok("पैटर्न"); return }
        pv.tone = HoloScanView.BAD; pv.postDelayed({ pv.tone = com.dk.assistant.ui.ClayTheme.t.accent; pv.clear() }, 600)
        wrong()
    }

    private fun wrong() {
        val f = AppLockStore.fails(this)
        val w = AppLockStore.waitLeft(this)
        say(if (w > 0) waitText(w) else "✗ गलत — ${(AppLockRules.FREE_TRIES - f).coerceAtLeast(0)} कोशिश बची", HoloScanView.BAD)
        Guard.note(this, "ऐप लॉक: $label पर गलत PIN / पैटर्न ($f बार)")
    }

    private fun ok(how: String) {
        if (done) return
        done = true
        AppLock.unlocked(this, pkg)
        DKLog.d("ऐप लॉक: $label खुला ($how)")
        say("✓ ACCESS GRANTED", HoloScanView.OK)
        stopFace()
        status.postDelayed({ finish() }, 250)
    }

    // ------------------------------------------------------------------ फ़ोन का फ़िंगरप्रिंट / फ़ेस / स्क्रीन लॉक
    private fun phoneAuth() {
        if (done) return
        if (!Guard.secure(this)) { say("फ़ोन में स्क्रीन लॉक (PIN / फ़िंगरप्रिंट) सेट नहीं — DK PIN से खोलें", HoloScanView.WARN); return }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val b = BiometricPrompt.Builder(this).setTitle(UiLang.s("$label खोलें")).setSubtitle(UiLang.s("DK ऐप लॉक — ${bioLabel()}"))
                when {
                    Build.VERSION.SDK_INT >= 30 ->
                        b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    Build.VERSION.SDK_INT >= 29 -> { @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true) }
                    else -> b.setNegativeButton(UiLang.s("DK PIN से खोलें"), mainExecutor) { _, _ -> use(AppLockRules.Method.PIN) }
                }
                val c = CancellationSignal().also { cancel = it }
                b.build().authenticate(c, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) { ok(bioLabel()) }
                    override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                        if (code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) say("${msg ?: "रद्द"} — फिर से छुएँ या दूसरा तरीका चुनें", HoloScanView.WARN)
                    }
                    override fun onAuthenticationFailed() { say("✗ मेल नहीं खाया — फिर कोशिश करें", HoloScanView.BAD) }
                })
                say("${bioLabel()} लगाइए")
            } else {
                val km = getSystemService(KeyguardManager::class.java)
                @Suppress("DEPRECATION")
                val i = km?.createConfirmDeviceCredentialIntent(UiLang.s("$label खोलें"), UiLang.s("DK ऐप लॉक"))
                if (i == null) { say("फ़ोन का लॉक नहीं मिला — DK PIN से खोलें", HoloScanView.WARN); return }
                sysAuth = true
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_PHONE)
            }
        } catch (e: Exception) { say("फ़ोन का लॉक नहीं खुला (${e.message}) — DK PIN से खोलें", HoloScanView.WARN) }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHONE) return
        sysAuth = false
        if (resultCode == RESULT_OK) ok("फ़ोन का लॉक") else say("रद्द — दूसरा तरीका चुनें", HoloScanView.WARN)
    }

    // ------------------------------------------------------------------ DK फ़ेस स्कैन (होलोग्राम)
    private fun startFace() {
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        val hv = HoloScanView(this).also { holo = it; it.show(HoloScanView.Mode.SEARCH, emptyList()) }
        val w = (resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtMost((300 * d).toInt())
        body.addView(FrameLayout(this).apply {
            background = com.dk.assistant.ui.Clay.inset(this@AppLockActivity, 18f)   // कैमरा Clay के धँसे फ़्रेम में
            val p = (6 * d).toInt(); setPadding(p, p, p, p); clipToOutline = true
            addView(pv, FrameLayout.LayoutParams(-1, -1)); addView(hv, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(w, (w * 1.25f).toInt(), Gravity.CENTER))
        gate = QuickFaceGate()
        val go = {
            say("सीधे कैमरे में देखिए — लाइव चेहरा जाँच रहा हूँ")
            cam = FaceCam(this, pv, gapMs = FlashLive.CAM_GAP_MS) { b -> flashScr.sample(b); onFrame(b) }.also { it.start { why -> say("कैमरा नहीं खुला ($why) — दूसरा तरीका चुनें", HoloScanView.WARN) } }
        }
        if (FaceAI.hasSpoof(this)) go()
        else {
            say("AI anti-spoof मॉडल (~3.5 MB) आ रहा है — पक्की जाँच के लिए ज़रूरी…")
            Thread {
                val err = FaceAI.downloadSpoof(applicationContext)
                runOnUiThread {
                    if (isFinishing || method != AppLockRules.Method.FACE) return@runOnUiThread
                    if (err == null) go() else say("anti-spoof मॉडल नहीं आया ($err) — PIN से खोलें", HoloScanView.WARN)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun stopFace() {
        flashScr.cancel()
        try { cam?.stop() } catch (e: Exception) { Quiet.note(e) }
        cam = null; holo = null
    }

    /**
     * 22 नवंबर 2026 (मालिक: "बहुत टाइम लगाता है, इधर-उधर घुमाना पड़ता है"): हर फ़्रेम पर एक साथ detect + AI anti-spoof + पहचान
     * (पहले anti-spoof सिर्फ़ सीधे चेहरे पर और पहचान सिर्फ़ कुछ कदमों पर), फ़ैसला [QuickFaceGate], PASS पर तुरंत खुलना (600ms रुकना हटा).
     */
    private fun onFrame(b: Bitmap) {
        if (busy || done || System.currentTimeMillis() < pauseUntil) return
        busy = true
        val copy = try { b.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { busy = false; return }
        work.execute {
            try {
                val now = System.currentTimeMillis()
                val dets = FaceAI.detectAll(this, copy, minScore = 0.6f)
                val det = dets.maxByOrNull { it.w * it.h }
                val one = det != null && dets.size == 1
                flashScr.box = det?.let { floatArrayOf(it.x, it.y, it.w, it.h) }
                val yaw = det?.let { FaceLiveness.yaw(it.kps) }
                val nearFront = yaw != null && kotlin.math.abs(yaw) <= FaceLiveness.TURN
                // रंगीन रोशनी के बीच AI / पहचान नहीं (रंग से गड़बड़ाते हैं) - सिर्फ़ वही लगातार चेहरा
                val flashing = gate.flashing
                val real = if (one && det != null && !flashing) FaceAI.realness(this, copy, det, force = true) else null
                val e = if (one && det != null && nearFront && !flashing) FaceAI.embed(this, copy, det) else null
                val s = e?.let { FaceAI.score(this, it) }
                val other = e?.let { x -> FacePeople.all(this).maxOfOrNull { FaceMatch.cosineSimilarity(it.print, x) } }
                val v = gate.feed(QuickFaceGate.Frame(now, dets.size, det?.let { it.x + it.w / 2f } ?: 0f, det?.let { it.y + it.h / 2f } ?: 0f,
                    det?.w ?: 0f, yaw, s, other, real, FaceAI.hasSpoof(this)))
                if (det == null) holo?.show(HoloScanView.Mode.SEARCH, emptyList())
                else holo?.let { h ->
                    val tone = when (v.state) { QuickFaceGate.State.PASS -> HoloScanView.OK; QuickFaceGate.State.FAIL -> HoloScanView.BAD; else -> HoloScanView.SCAN }
                    h.place(det, copy.width, copy.height, true, tone = tone)?.let { f ->
                        val bars = buildList { s?.let { add("MATCH" to it) }; add("LIVE" to v.step / 3f); add("AI ANTI-SPOOF" to gate.mean()) }
                        when (v.state) {
                            QuickFaceGate.State.PASS -> h.show(HoloScanView.Mode.AUTHORIZED, listOf(f), bars = bars, banner = HoloScanView.AUTHORIZED)
                            QuickFaceGate.State.FAIL -> h.show(HoloScanView.Mode.DECLINED, listOf(f), bars = bars, banner = HoloScanView.DECLINED)
                            else -> h.show(HoloScanView.Mode.SCAN, listOf(f), v.step, bars)
                        }
                    }
                }
                if (v.flash) { val g = gate; runOnUiThread { g.flash?.let { flashScr.begin(it) } } }
                gate.lastFlash?.let { if (v.state != QuickFaceGate.State.WAIT) DKLog.d("ऐप लॉक ${it.log()}") }
                when (v.state) {
                    QuickFaceGate.State.PASS -> runOnUiThread { ok("DK फ़ेस स्कैन") }
                    QuickFaceGate.State.FAIL -> {
                        flashScr.cancel()
                        say("✗ DECLINED — ${v.why}", HoloScanView.BAD)
                        Guard.note(this, "ऐप लॉक: $label पर चेहरा नहीं मिला (${v.why})")
                        // कुछ देर रुककर फिर से नई जाँच (नई random तरफ़)
                        pauseUntil = now + RETRY_MS
                        gate = QuickFaceGate()
                    }
                    else -> if (now - lastUi > UI_MS || v.turn) { lastUi = now; say(v.why) }
                }
            } catch (t: Throwable) {
                Quiet.note(t)
            } finally { copy.recycle(); busy = false }
        }
    }

    // ------------------------------------------------------------------ बाहर निकलना
    private fun goHome() {
        AppLock.dismissed(pkg)
        val svc = DKAccessibilityService.instance
        val home = try { svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true } catch (_: Exception) { false }
        if (!home) try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Quiet.note(e) }
        finish()
    }


    override fun onStop() {
        super.onStop()
        // ताले की स्क्रीन छोड़कर कहीं और गए (recent / होम) = यह स्क्रीन बंद; ताले वाला ऐप फिर खोलेंगे तो फिर ताला
        if (!done && !sysAuth && !isChangingConfigurations) { AppLock.dismissed(pkg); finish() }
    }

    override fun onDestroy() {
        cancel?.cancel()
        stopFace()
        work.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val REQ_PHONE = 7311
        private const val RETRY_MS = 1_000L
        private const val UI_MS = 250L
        const val PIN_AUTO_HINT = "DK सीक्रेट PIN डालें — सही होते ही अपने-आप खुलेगा"
    }
}
