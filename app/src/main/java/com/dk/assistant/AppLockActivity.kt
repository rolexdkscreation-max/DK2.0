package com.dk.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 21 नवंबर 2026 (मालिक): ऐप लॉक की futuristic स्क्रीन - काला-नीला निऑन, ऐप का आइकन, और ताला खोलने के तरीके:
 * DK सीक्रेट PIN (● बिंदु), DK पैटर्न, फ़ोन का अपना फ़िंगरप्रिंट / फ़ेस / स्क्रीन लॉक (Android BiometricPrompt),
 * और DK फ़ेस स्कैन (होलोग्राम + सिर घुमाना + AI anti-spoof, [BossGate] जितना सख़्त).
 * back = होम पर (ताले वाला ऐप नहीं खुलता). [AppLock] खोलता है.
 */
class AppLockActivity : AppCompatActivity() {
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
    @Volatile private var gate = BossGate()
    private val liveness = FaceLiveness().apply { challenge(FaceLiveness.randomSide()) }
    private val spoof = FaceSpoof.Vote(BossGate.SPOOF_WINDOW)
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF02040C.toInt(), 0xFF071530.toInt(), 0xFF02040C.toInt()))
            val p = (18 * d).toInt(); setPadding(p, (36 * d).toInt(), p, p)
        }
        // आइकन निऑन घेरे में
        val ring = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke((2 * d).toInt(), HoloScanView.SCAN); setColor(0x2200E5FF) }
            val p = (14 * d).toInt(); setPadding(p, p, p, p)
            addView(ImageView(this@AppLockActivity).apply {
                try { setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { setImageResource(R.drawable.ic_s_applock) }
            }, FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
        }
        root.addView(ring, LinearLayout.LayoutParams(-2, -2))
        root.addView(text("DK SECURE LOCK", 12f, HoloScanView.SCAN, mono = true).apply { letterSpacing = 0.3f; setPadding(0, (12 * d).toInt(), 0, 0) })
        root.addView(text("$label लॉक है", 22f, Color.WHITE, bold = true))
        status = text("", 14f, 0xFFB8E9FF.toInt()).apply { minLines = 2; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt()) }
        root.addView(status)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-2, -2))
        setContentView(root)
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

    private fun say(t: String, tone: Int = 0xFFB8E9FF.toInt()) = runOnUiThread { status.text = UiLang.s(t); status.setTextColor(tone) }

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
            chips.addView(text(name, 13f, if (m == method) Color.BLACK else HoloScanView.SCAN, bold = true).apply {
                val p = (12 * d).toInt(); setPadding(p, (8 * d).toInt(), p, (8 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 20 * d; setStroke((1.5f * d).toInt(), HoloScanView.SCAN)
                    setColor(if (m == method) HoloScanView.SCAN else 0x1100E5FF)
                }
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
                val pad = NeonPinPad(this).apply { onDone = { p -> tryPin(p, this) } }
                body.addView(pad, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                say(if (wait > 0) waitText(wait) else "DK सीक्रेट PIN डालें, फिर OK")
            }
            AppLockRules.Method.PATTERN -> {
                val pv = NeonPattern(this).apply { onDone = { dots -> tryPattern(dots, this) } }
                body.addView(pv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                say(if (wait > 0) waitText(wait) else "DK पैटर्न बनाइए")
            }
            AppLockRules.Method.PHONE -> {
                body.addView(text("◉\n${bioLabel()} से खोलें\n(यहाँ छुएँ)", 18f, HoloScanView.SCAN).apply { setOnClickListener { phoneAuth() } },
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
        if (AppLockStore.checkPin(this, p)) { ok("PIN"); return }
        pad.tone = HoloScanView.BAD; pad.postDelayed({ pad.tone = HoloScanView.SCAN }, 600)
        wrong()
    }

    private fun tryPattern(dots: List<Int>, pv: NeonPattern) {
        val wait = AppLockStore.waitLeft(this)
        if (wait > 0) { say(waitText(wait), HoloScanView.WARN); pv.clear(); return }
        if (dots.size < AppLockRules.PATTERN_MIN) { say("कम से कम ${AppLockRules.PATTERN_MIN} बिंदु जोड़िए", HoloScanView.WARN); pv.clear(); return }
        if (AppLockStore.checkPattern(this, dots)) { ok("पैटर्न"); return }
        pv.tone = HoloScanView.BAD; pv.postDelayed({ pv.tone = HoloScanView.SCAN; pv.clear() }, 600)
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
            background = GradientDrawable().apply { setStroke((2 * d).toInt(), HoloScanView.SCAN); cornerRadius = 18 * d; setColor(Color.BLACK) }
            val p = (3 * d).toInt(); setPadding(p, p, p, p); clipToOutline = true
            addView(pv, FrameLayout.LayoutParams(-1, -1)); addView(hv, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(w, (w * 1.25f).toInt(), Gravity.CENTER))
        gate = BossGate(); liveness.challenge(FaceLiveness.randomSide()); spoof.reset()
        val go = {
            say(liveness.guide())
            cam = FaceCam(this, pv) { b -> onFrame(b) }.also { it.start { why -> say("कैमरा नहीं खुला ($why) — दूसरा तरीका चुनें", HoloScanView.WARN) } }
        }
        if (FaceAI.hasSpoof(this)) go()
        else {
            say("AI anti-spoof मॉडल (~3.5 MB) आ रहा है — पक्की जाँच के लिए ज़रूरी…")
            Thread {
                val err = FaceAI.downloadSpoof(applicationContext)
                runOnUiThread { if (isFinishing || method != AppLockRules.Method.FACE) return@runOnUiThread; if (err == null) go() else say("anti-spoof मॉडल नहीं आया ($err) — PIN से खोलें", HoloScanView.WARN) }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun stopFace() {
        try { cam?.stop() } catch (e: Exception) { Quiet.note(e) }
        cam = null; holo = null
    }

    private fun onFrame(b: Bitmap) {
        if (busy || done || System.currentTimeMillis() < pauseUntil) return
        busy = true
        val copy = try { b.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { busy = false; return }
        work.execute {
            try {
                val now = System.currentTimeMillis()
                val dets = FaceAI.detectAll(this, copy, minScore = 0.6f)
                val det = dets.maxByOrNull { it.w * it.h }
                val live = liveness.update(if (dets.size == 1) det else null, now)
                if (det == null) { spoof.reset(); holo?.show(HoloScanView.Mode.SEARCH, emptyList()) }
                val front = det != null && FaceLiveness.yaw(det.kps)?.let { kotlin.math.abs(it) <= FaceLiveness.CENTER } == true
                val nearFront = det != null && FaceLiveness.yaw(det.kps)?.let { kotlin.math.abs(it) <= FaceLiveness.TURN } == true
                if (det != null && dets.size == 1 && nearFront) FaceAI.realness(this, copy, det, force = true)?.let { spoof.add(it) }
                val e = if (det != null && dets.size == 1 && liveness.idNow(front)) FaceAI.embed(this, copy, det) else null
                val s = e?.let { FaceAI.score(this, it) }
                val other = e?.let { x -> FacePeople.all(this).maxOfOrNull { FaceMatch.cosineSimilarity(it.print, x) } }
                val v = gate.feed(BossGate.Frame(dets.size, s, other, live, front, FaceAI.hasSpoof(this), spoof.count(), spoof.sure(), spoof.fake()))
                if (det != null) holo?.let { h ->
                    val tone = when (v.state) { BossGate.State.PASS -> HoloScanView.OK; BossGate.State.FAIL -> HoloScanView.BAD; else -> HoloScanView.SCAN }
                    h.place(det, copy.width, copy.height, true, tone = tone)?.let { f ->
                        val bars = buildList { if (s != null) add("MATCH" to s); add("LIVENESS" to liveness.done() / 3f); add("AI ANTI-SPOOF" to spoof.mean()) }
                        when (v.state) {
                            BossGate.State.PASS -> h.show(HoloScanView.Mode.AUTHORIZED, listOf(f), bars = bars, banner = HoloScanView.AUTHORIZED)
                            BossGate.State.FAIL -> h.show(HoloScanView.Mode.DECLINED, listOf(f), bars = bars, banner = HoloScanView.DECLINED)
                            else -> h.show(HoloScanView.Mode.SCAN, listOf(f), liveness.done(), bars)
                        }
                    }
                }
                when (v.state) {
                    BossGate.State.PASS -> runOnUiThread { status.postDelayed({ ok("DK फ़ेस स्कैन") }, 600) }
                    BossGate.State.FAIL -> {
                        say("✗ DECLINED — ${v.why}", HoloScanView.BAD)
                        Guard.note(this, "ऐप लॉक: $label पर चेहरा नहीं मिला (${v.why})")
                        // कुछ देर रुककर फिर से नई जाँच (नया क्रम)
                        pauseUntil = now + RETRY_MS
                        gate = BossGate(); liveness.challenge(FaceLiveness.randomSide()); spoof.reset()
                    }
                    else -> if (now - lastUi > 400) { lastUi = now; say(v.why.ifBlank { liveness.hint() }) }
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

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { goHome() }

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
        private const val RETRY_MS = 1_800L
    }
}
