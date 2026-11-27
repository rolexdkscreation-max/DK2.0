package com.dk.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * नवंबर 2026: [Guard] का अलार्म - लॉक स्क्रीन के ऊपर लाल चेतावनी ("यह फ़ोन आपका नहीं है").
 * - छूने / back / volume से बंद नहीं होता (सिर्फ़ मालिक: फ़ोन खोलना, चेहरा, गुप्त शब्द).
 * - आगे वाला कैमरा: छूने वाले की फ़ोटो सेव ([Guard.savePhoto]); DK ने मालिक का चेहरा सीखा हो और मिल जाए = बंद.
 * - "मैं मालिक हूँ" = फ़ोन का अपना PIN / फ़िंगरप्रिंट वाला खाना (फ़ोन खुला = [Guard] बंद).
 */
class GuardAlarmActivity : AppCompatActivity() {
    /** 23 नवंबर 2026 (मालिक): टैबलेट पर पूरा UI स्क्रीन के अनुपात में बड़ा; फ़ोन पर कुछ नहीं बदलता ([UiFit.tablet]) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(com.dk.assistant.UiFit.tablet(newBase)) }
    private var cam: FaceCam? = null
    /**
     * 22 नवंबर 2026 (मालिक: "टच मोड से सिर घुमाना हटा दो, सिर्फ़ रंगीन लाइव जाँच रखो"): सिर घुमाना नहीं.
     * ऐप लॉक वाला ही तेज़ गेट ([QuickFaceGate], allowTurn = false): एक ही चेहरा + सख़्त मिलान + AI anti-spoof (ज़रूरी),
     * फिर आख़िरी ताला स्क्रीन की random रंगीन रोशनी ([FlashLive]). वीडियो / फ़ोटो इसका जवाब नहीं दे सकते.
     */
    @Volatile private var gate = QuickFaceGate(allowTurn = false)
    private val flashScr by lazy { FlashScreen(this) }
    @Volatile private var pauseUntil = 0L
    @Volatile private var lastStep = 0
    @Volatile private var lastHint = ""
    /** futuristic स्कैनर ([HoloScanView]) */
    private var holo: HoloScanView? = null
    @Volatile private var declinedUntil = 0L
    @Volatile private var authorized = false
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-guard-face").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var shots = 0
    @Volatile private var lastShot = 0L
    @Volatile private var snapping = false
    @Volatile private var snapFailed = false
    @Volatile private var dueSince = 0L
    private var startedAt = 0L
    private var blinkOn = false
    private lateinit var root: LinearLayout
    private var faceTv: TextView? = null
    @Volatile private var lastFaceUi = 0L
    private var stealth = false
    @Volatile private var mode = GuardModes.Mode.NORMAL

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Guard.alarming) { finish(); return }
        current = WeakReference(this)
        startedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val d = resources.displayMetrics.density
        // 15 नवंबर 2026: STEALTH = काली, चुप स्क्रीन (कोई लिखावट नहीं) - कैमरा चुपके से फ़ोटो लेता है, चेहरा मिले तो बंद
        stealth = !GuardModes.loud(GuardModes.mode(this))
        mode = GuardModes.mode(this)
        if (stealth) {
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
            root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()) }
            val pv = PreviewView(this)
            root.addView(pv, LinearLayout.LayoutParams((2 * d).toInt().coerceAtLeast(2), (2 * d).toInt().coerceAtLeast(2)))
            setContentView(root)
            startCam(pv)
            return
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFFB00020.toInt()) }
        fun tv(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = UiLang.s(t); textSize = size; setTextColor(color); gravity = Gravity.CENTER
            val p = (20 * d).toInt(); setPadding(p, p / 3, p, p / 3)
        }
        root.addView(tv("⚠ चेतावनी", 40f))
        root.addView(tv("यह फ़ोन आपका नहीं है", 30f))
        root.addView(tv("इसे तुरंत वापस रखिए। आपकी फ़ोटो ली जा रही है और मालिक को खबर दी जा रही है।", 17f, 0xEEFFFFFF.toInt()))
        val preview = PreviewView(this)
        // 15 नवंबर 2026 (मालिक: "फ़्यूचरिस्टिक तरीके से स्कैन"): बड़ा कैमरा + ऊपर hologram स्कैनर
        val hv = HoloScanView(this).also { holo = it; it.show(HoloScanView.Mode.SEARCH, emptyList()) }
        val frame = FrameLayout(this).apply {
            background = com.dk.assistant.ui.Clay.inset(this@GuardAlarmActivity, 16f)   // 23 नवंबर 2026: कैमरा Clay के धँसे फ़्रेम में
            val p = (6 * d).toInt(); setPadding(p, p, p, p)
            clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(hv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val fw = (resources.displayMetrics.widthPixels * 0.72f).toInt().coerceIn((150 * d).toInt(), (320 * d).toInt())
        val fh = (fw * 1.27f).toInt().coerceAtMost((resources.displayMetrics.heightPixels * 0.45f).toInt())
        root.addView(frame, LinearLayout.LayoutParams(fw, fh).apply { topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt() })
        root.addView(Button(this).apply {
            text = UiLang.s("मैं मालिक हूँ — फ़ोन खोलें")
            setOnClickListener { askUnlock() }
        })
        // 21 नवंबर 2026 (मालिक): "फेस वेरिफिकेशन के साथ पिन डालने का भी ऑप्शन - अंदर डाला सीक्रेट PIN, डॉट डॉट में दिखे"
        if (AppLockStore.hasPin(this)) root.addView(Button(this).apply {
            text = UiLang.s("DK सीक्रेट PIN डालें")
            setOnClickListener { askPin() }
        })
        root.addView(tv("मालिक: फ़ोन खोलें, या चेहरा सीधे कैमरे के सामने करें (स्क्रीन के रंग बदलेंगे), या गुप्त शब्द बोलें", 13f, 0xCCFFFFFF.toInt()))
        faceTv = tv("", 13f, 0xEEFFFFFF.toInt()).also { root.addView(it) }
        setContentView(root)
        blink()
        startCam(preview)
    }

    private fun blink() {
        if (isFinishing || stealth) return
        blinkOn = !blinkOn
        root.setBackgroundColor(if (blinkOn) 0xFFB00020.toInt() else 0xFF000000.toInt())
        root.postDelayed({ blink() }, 500)
    }

    private fun faceUi(t: String) { runOnUiThread { faceTv?.text = UiLang.s(t) } }

    /**
     * नवंबर 2026 (मालिक की शिकायत): "मालिक ने फ़ोन उठाया, कैमरे ने देखा, फिर भी सायरन बंद नहीं हुआ".
     * जड़: चेहरा सिखाने वाला हिस्सा 29 अक्टूबर को Settings से हट गया था, तो [FaceAI.enrolled] हमेशा false -
     * यहाँ face = false और कैमरा सिर्फ़ फ़ोटो लेता था, पहचानता नहीं था (और कहीं बताया भी नहीं).
     * अब चेहरा Settings → टच मोड में सेट होता है; न हो तो यहाँ और इतिहास में साफ़ कारण.
     */
    private fun startCam(preview: PreviewView) {
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val photo = Guard.photoOn(this) && camOk
        val faceOn = Guard.faceOn(this); val enrolled = FaceAI.enrolled(this); val ready = FaceAI.ready(this)
        val face = GuardRules.faceCheck(faceOn, enrolled, ready, camOk)
        val why = GuardRules.faceWhyNot(faceOn, enrolled, ready, camOk)
        if (why != null && faceOn) { Guard.note(this, "चेहरे से सायरन बंद नहीं होगा: $why"); faceUi("चेहरा पहचान बंद: $why") }
        // 15 नवंबर 2026: हर बार नया क्रम (पहले बाएँ / पहले दाएँ) - रिकॉर्ड किया वीडियो नहीं जानता
        else if (face) faceUi("मालिक का चेहरा देख रहा हूँ… सीधे कैमरे में देखिए (सिर घुमाने की ज़रूरत नहीं)")
        // 22 नवंबर 2026: AI anti-spoof अब ज़रूरी (Settings में बंद हो तब भी) - मॉडल न हो तो चेहरे से बंद नहीं, पहले डाउनलोड
        if (face && !FaceAI.hasSpoof(this)) Guard.note(this, "AI anti-spoof मॉडल अभी फ़ोन में नहीं - डाउनलोड होने तक चेहरे से सायरन बंद नहीं (PIN / फ़ोन का लॉक चलेगा)")
        if (face && !FaceAI.hasSpoof(this)) Thread { FaceAI.downloadSpoof(applicationContext) }.apply { isDaemon = true }.start()
        if (!photo && !face) return
        cam = FaceCam(this, preview, capture = photo, gapMs = if (face) FlashLive.CAM_GAP_MS else 350L) { b -> flashScr.sample(b); onFrame(b, photo, face) }.also { it.start { w -> DKLog.d("टच मोड: कैमरा नहीं खुला ($w)"); faceUi("कैमरा नहीं खुला: $w") } }
        flashScr.cam = cam
    }

    /** बीच का [SHARP_BOX]² हिस्सा (तेज़ी नापने को) */
    private fun centerPx(b: Bitmap): IntArray {
        val n = minOf(SHARP_BOX, b.width, b.height); val px = IntArray(SHARP_BOX * SHARP_BOX)
        if (n < SHARP_BOX) return px
        b.getPixels(px, 0, SHARP_BOX, (b.width - n) / 2, (b.height - n) / 2, n, n); return px
    }

    private fun onFrame(b: Bitmap, photo: Boolean, face: Boolean) {
        if (busy || !Guard.alarming) return
        busy = true
        val copy = try { b.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { busy = false; return }
        work.execute {
            try {
                val now = System.currentTimeMillis()
                val dets = if (face) try { FaceAI.detectAll(this, copy, minScore = 0.6f) } catch (_: Throwable) { emptyList() } else emptyList()
                val det = if (face) dets.maxByOrNull { it.w * it.h } else if (photo) try { FaceAI.detect(this, copy) } catch (_: Throwable) { null } else null
                // फ़ोटो: चेहरा दिखे तो तुरंत, वरना 1.5 s बाद एक; हर अलार्म में ज़्यादा से ज़्यादा 3
                if (photo && !snapping && shots < GuardModes.photoMax(mode) && now - lastShot > GuardModes.photoGapMs(mode) && (det != null || (shots == 0 && now - startedAt > 1_500))) {
                    // 15 नवंबर 2026: अच्छी फ़ोटो - हिलता/धुँधला फ़्रेम छोड़ो (2 s तक), फिर असली कैमरा फ़ोटो (पूरा resolution)
                    if (dueSince == 0L) dueSince = now
                    val sharp = GuardPhotos.sharpness(centerPx(copy), SHARP_BOX, SHARP_BOX)
                    if (sharp >= GuardPhotos.SHARP_MIN || now - dueSince > GuardPhotos.WAIT_SHARP_MS) {
                        val c = cam
                        if (c != null && c.canSnap && !snapFailed) {
                            snapping = true; lastShot = now
                            c.snap { full ->
                                work.execute {
                                    try {
                                        if (full != null && Guard.savePhoto(this, full) != null) { shots++; dueSince = 0L }
                                        else { snapFailed = true; lastShot = 0L }   // अगली बार पहचान वाला फ़्रेम
                                    } finally { full?.recycle(); snapping = false }
                                }
                            }
                        } else if (Guard.savePhoto(this, copy) != null) { shots++; lastShot = now; dueSince = 0L }
                    }
                }
                if (face && !authorized && now >= pauseUntil) faceStep(copy, dets, det, now)
            } catch (_: Throwable) {
            } finally { copy.recycle(); busy = false }
        }
    }

    /** एक फ़्रेम की चेहरा-जाँच ([QuickFaceGate]) - सही = सायरन बंद */
    private fun faceStep(b: Bitmap, dets: List<FaceMath.Det>, det: FaceMath.Det?, now: Long) {
        val g = gate
        val one = det != null && dets.size == 1
        flashScr.box = det?.let { floatArrayOf(it.x, it.y, it.w, it.h) }
        // AI anti-spoof ज़रूरी - मॉडल न हो तो चेहरे से बंद नहीं (PIN / फ़ोन लॉक)
        val spoofOn = FaceAI.ready(this) && FaceAI.hasSpoof(this)
        if (!spoofOn) {
            if (det != null && now - lastFaceUi > UI_MS) { lastFaceUi = now; faceUi("AI anti-spoof मॉडल अभी नहीं आया (डाउनलोड हो रहा है) — तब तक PIN / फ़ोन का लॉक") }
            return
        }
        val yaw = det?.let { FaceLiveness.yaw(it.kps) }
        val nearFront = yaw != null && kotlin.math.abs(yaw) <= FaceLiveness.TURN
        // रंगीन रोशनी के बीच AI / पहचान नहीं (रंग से गड़बड़ाते हैं) - सिर्फ़ वही लगातार चेहरा
        val flashing = g.flashing
        val real = if (one && det != null && !flashing) FaceAI.realness(this, b, det, force = true) else null
        val e = if (one && det != null && nearFront && !flashing) FaceAI.embed(this, b, det) else null
        val s = e?.let { FaceAI.score(this, it) }
        val other = e?.let { x -> FacePeople.all(this).maxOfOrNull { FaceMatch.cosineSimilarity(it.print, x) } }
        val v = g.feed(QuickFaceGate.Frame(now, dets.size, det?.let { it.x + it.w / 2f } ?: 0f, det?.let { it.y + it.h / 2f } ?: 0f,
            det?.w ?: 0f, yaw, s, other, real, true, lux = flashScr.lux()))
        lastStep = v.step; lastHint = v.why
        if (v.flash) runOnUiThread { g.flash?.let { flashScr.begin(it) } }
        g.lastFlash?.let { if (v.state != QuickFaceGate.State.WAIT) DKLog.d("टच मोड ${it.log()}") }
        when (v.state) {
            QuickFaceGate.State.PASS -> {
                authorized = true
                val pct = ((s ?: g.lastScore) * 100).toInt()
                if (det != null) holo?.let { h -> h.place(det, b.width, b.height, true, tone = HoloScanView.OK)?.let { f ->
                    h.show(HoloScanView.Mode.AUTHORIZED, listOf(f), 3, bars(s), HoloScanView.AUTHORIZED, "स्वागत है, ${Prefs.userName(this).trim().ifBlank { "Boss" }}") } }
                faceUi("✓ मालिक पहचाने गए — असली इंसान (लाइव)")
                Guard.disarm(this, "मालिक का चेहरा पहचाना ($pct%, AI anti-spoof + ${if (g.lastFlash?.live == false) "धूप वाली random सिर-घुमाना जाँच" else "स्क्रीन की रंगीन रोशनी"} से असली इंसान जाँचा)")
                runOnUiThread { root.postDelayed({ finish() }, AUTH_SHOW_MS) }
            }
            QuickFaceGate.State.FAIL -> {
                flashScr.cancel()
                Guard.note(this, "टच मोड: चेहरे से बंद नहीं - ${v.why}")
                if (det != null) declined(b, det, v.why) else faceUi("✗ ${v.why}")
                pauseUntil = now + RETRY_MS
                gate = QuickFaceGate(allowTurn = false)
            }
            QuickFaceGate.State.WAIT -> {
                if (det == null) { if (now > declinedUntil) holo?.show(HoloScanView.Mode.SEARCH, emptyList()) } else scan(b, det, s)
                if (now - lastFaceUi > UI_MS || v.flash) { lastFaceUi = now; faceUi(v.why) }
            }
        }
    }

    private fun bars(s: Float?): List<Pair<String, Float>> = buildList {
        if (s != null) add("MATCH" to s)
        add("LIVE" to lastStep / 3f)
        if (gate.count() > 0) add("AI ANTI-SPOOF" to gate.mean())
    }

    /** स्कैन चल रहा है (नीला) */
    private fun scan(b: Bitmap, det: FaceMath.Det, s: Float?) {
        if (authorized || System.currentTimeMillis() < declinedUntil) return
        val h = holo ?: return
        val f = h.place(det, b.width, b.height, true, "SCANNING", lastHint) ?: return
        h.show(HoloScanView.Mode.SCAN, listOf(f), lastStep, bars(s))
    }

    /** बॉस नहीं / नकली = लाल DECLINED (कुछ देर) */
    private fun declined(b: Bitmap, det: FaceMath.Det, why: String) {
        if (authorized) return
        declinedUntil = System.currentTimeMillis() + DECLINED_SHOW_MS
        faceUi("✗ $why")
        val h = holo ?: return
        val f = h.place(det, b.width, b.height, true, tone = HoloScanView.BAD)
        h.show(HoloScanView.Mode.DECLINED, listOfNotNull(f), lastStep, bars(null), HoloScanView.DECLINED, why)
    }

    /** 21 नवंबर 2026: DK सीक्रेट PIN ([AppLockStore]) - ● बिंदु, अंक कभी नहीं दिखते. सही = टच मोड बंद ([Guard.pinDisarm]) */
    private fun askPin() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(com.dk.assistant.ui.ClayTheme.t.surface); val p = (16 * d).toInt(); setPadding(p, p, p, p)
        }
        // 22 नवंबर 2026: सही PIN पूरा होते ही अपने-आप (OK नहीं) - लंबाई पता हो तो ([AppLockStore.pinLen])
        val auto = AppLockStore.pinLen(this)
        val msg = TextView(this).apply { text = UiLang.s(if (auto > 0) AppLockActivity.PIN_AUTO_HINT else "DK सीक्रेट PIN डालें, फिर OK"); setTextColor(com.dk.assistant.ui.ClayTheme.t.sub); textSize = 15f; gravity = Gravity.CENTER }
        box.addView(msg)
        val pad = NeonPinPad(this, autoLen = auto)
        box.addView(pad, LinearLayout.LayoutParams((280 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this).setView(box).setNegativeButton(UiLang.s("रहने दें"), null).create()
        pad.onDone = { pin ->
            val wait = AppLockStore.waitLeft(this)
            when {
                wait > 0 -> msg.text = UiLang.s("बहुत बार गलत — ${AppLockRules.waitLabel(wait)} बाद")
                Guard.pinDisarm(this, pin) -> { dlg.dismiss(); finish() }
                else -> { msg.text = UiLang.s("✗ गलत PIN"); pad.reject() }
            }
        }
        dlg.show()
    }

    /** फ़ोन का अपना PIN / फ़िंगरप्रिंट - खुला = मालिक (USER_PRESENT से [Guard] बंद) */
    private fun askUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { if (Guard.secure(this@GuardAlarmActivity)) Guard.pinUnlocked(this@GuardAlarmActivity) }
        })
    }

    // back / volume से बंद नहीं (volume घटाकर आवाज़ कम न हो)
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {}

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_MUTE) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() { super.onResume(); shown = true }
    override fun onPause() { shown = false; super.onPause() }

    override fun onDestroy() {
        shown = false
        try { cam?.stop() } catch (e: Exception) { Quiet.note(e) }
        flashScr.cancel(); flashScr.stopLux(); flashScr.cam = null
        work.shutdown()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val SHARP_BOX = 256
        /** गलत / नकली के बाद इतनी देर बाद नई जाँच */
        const val RETRY_MS = 1_000L
        /** 14 नवंबर 2026: स्क्रीन पर अगला कदम जल्दी दिखे (पहले 1 s) */
        const val UI_MS = 400L
        /** हरा "BOSS AUTHORIZED" इतनी देर दिखे, फिर स्क्रीन बंद */
        const val AUTH_SHOW_MS = 1_100L
        const val DECLINED_SHOW_MS = 1_800L
        @Volatile private var current: WeakReference<GuardAlarmActivity>? = null
        @Volatile private var shown = false

        fun show(c: Context) {
            if (!Guard.alarming) return
            // स्क्रीन बंद की गई हो (power बटन) - फिर जलाओ. 22 नवंबर 2026: पर सायरन में खुला फ़ोन DK ने अभी फिर लॉक किया हो
            // तो नहीं - स्क्रीन अँधेरी और फ़ोन लॉक रहे, जब तक कोई खुद स्क्रीन न जलाए (पहले यहीं से लॉक स्क्रीन ढक जाती थी)
            val pm = c.getSystemService(android.os.PowerManager::class.java)
            if (!GuardRules.wakeForAlarm(Guard.relockDark, pm?.isInteractive != false)) return
            try {
                if (pm != null && !pm.isInteractive) {
                    @Suppress("DEPRECATION")
                    pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "dk:guard-screen").acquire(3_000)
                }
            } catch (e: Exception) { Quiet.note(e) }
            if (shown && current?.get()?.let { !it.isFinishing && !it.isDestroyed } == true) return
            try {
                c.startActivity(Intent(c, GuardAlarmActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            } catch (e: Exception) { DKLog.d("गार्ड: चेतावनी स्क्रीन नहीं खुली (${e.message})") }
        }

        fun closeAll() {
            val a = current?.get() ?: return
            a.runOnUiThread { if (!a.isFinishing) a.finish() }
        }
    }
}
