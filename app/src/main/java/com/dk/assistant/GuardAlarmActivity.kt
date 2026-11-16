package com.dk.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private var cam: FaceCam? = null
    /** नवंबर 2026: फ़ोटो से सायरन न रुके - सिर घुमाकर 3D जाँच ([FaceLiveness]) */
    private val liveness = FaceLiveness()
    /** 15 नवंबर 2026: AI anti-spoof ([FaceSpoof]) - कई फ़्रेम का औसत (फ़ोटो / वीडियो / स्क्रीन वाला चेहरा) */
    private val spoof = FaceSpoof.Vote()
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
    @Volatile private var ownerHits = 0
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
            background = GradientDrawable().apply { setStroke((2 * d).toInt(), 0xFF00E5FF.toInt()); cornerRadius = 16 * d; setColor(Color.BLACK) }
            val p = (3 * d).toInt(); setPadding(p, p, p, p)
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
        root.addView(tv("मालिक: फ़ोन खोलें, चेहरा कैमरे के सामने करके सिर धीरे से दाएँ-बाएँ घुमाएँ, या गुप्त शब्द बोलें", 13f, 0xCCFFFFFF.toInt()))
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
        else if (face) { liveness.challenge(FaceLiveness.randomSide()); faceUi("मालिक का चेहरा देख रहा हूँ…\n" + (if (liveness.first == 0) FaceLiveness.GUIDE else liveness.guide())) }
        if (face && !FaceAI.hasSpoof(this) && Prefs.faceSpoofOn(this)) Guard.note(this, "AI anti-spoof मॉडल अभी फ़ोन में नहीं - इस बार सिर्फ़ सिर घुमाने वाली जाँच; डाउनलोड हो रहा है")
        if (face && !FaceAI.hasSpoof(this) && Prefs.faceSpoofOn(this)) Thread { FaceAI.downloadSpoof(applicationContext) }.apply { isDaemon = true }.start()
        if (!photo && !face) return
        cam = FaceCam(this, preview, capture = photo) { b -> onFrame(b, photo, face) }.also { it.start { w -> DKLog.d("टच मोड: कैमरा नहीं खुला ($w)"); faceUi("कैमरा नहीं खुला: $w") } }
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
                val det = if (face || photo) try { FaceAI.detect(this, copy) } catch (_: Throwable) { null } else null
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
                if (face) {
                    // असली इंसान? (फ़ोटो / फ़ोन की स्क्रीन वाली फ़ोटो सिर घुमाने पर नहीं बदलती) - चेहरा न हो तब भी गिनती चले
                    val live = liveness.update(det, now)
                    if (det == null) { ownerHits = 0; spoof.reset(); if (now > declinedUntil && !authorized) holo?.show(HoloScanView.Mode.SEARCH, emptyList()); return@execute }
                    // 15 नवंबर 2026: AI से असली त्वचा या फ़ोटो/स्क्रीन (हर फ़्रेम, ~10 ms)
                    val spoofOn = FaceAI.spoofReady(this)
                    // घूमे चेहरे पर मॉडल कम भरोसेमंद - सिर्फ़ लगभग सीधे चेहरे के फ़्रेम गिनो
                    val nearFront = FaceLiveness.yaw(det.kps)?.let { kotlin.math.abs(it) <= FaceLiveness.TURN } == true
                    if (spoofOn && nearFront) FaceAI.realness(this, copy, det)?.let { spoof.add(it) }
                    if (spoofOn && spoof.fake()) {
                        ownerHits = 0; liveness.reset(); spoof.reset()
                        declined(copy, det, "फ़ोटो / वीडियो / स्क्रीन पकड़ी गई — असली इंसान नहीं")
                        Guard.note(this, "टच मोड: किसी ने फ़ोटो/वीडियो/स्क्रीन दिखाकर खोलने की कोशिश की (AI anti-spoof ने रोका)")
                        return@execute
                    }
                    val front = FaceLiveness.yaw(det.kps)?.let { kotlin.math.abs(it) <= FaceLiveness.CENTER } == true
                    // 14 नवंबर 2026: सिर घुमाते समय पहचान (धीमी) नहीं - सिर्फ़ शुरू के सीधे चेहरे पर और आख़िरी कदम पर (तेज़)
                    if (!liveness.idNow(front)) {
                        ownerHits = 0
                        scan(copy, det, null)
                        if (now - lastFaceUi > UI_MS) { lastFaceUi = now; faceUi("${"●".repeat(liveness.done())}${"○".repeat(3 - liveness.done())}  ${liveness.hint()}") }
                        return@execute
                    }
                    val e = FaceAI.embed(this, copy, det)
                    val s = e?.let { FaceAI.score(this, it) }
                    val owner = s != null && FaceMath.decide(s, Prefs.faceStrict(this)) == MatchResult.ACCESS_GRANTED
                    // किसी और का चेहरा (मालिक से बिल्कुल नहीं मिलता) = जाँच फिर से - किसी और का सिर घुमाना + फिर मालिक की फ़ोटो नहीं चलेगा
                    if (s != null && s < TURNED_MIN) liveness.reset()
                    if (owner && live && front) {
                        // 15 नवंबर 2026: AI anti-spoof मॉडल हो तो वह भी "असली" बोले (सिर घुमाता वीडियो भी न चले)
                        // 15 नवंबर 2026: सख़्त - कई फ़्रेम, औसत ऊँचा, एक भी "नकली" फ़्रेम नहीं ([FaceSpoof.Vote.sure])
                        if (spoofOn && !(spoof.real() && spoof.sure())) {
                            ownerHits = 0; scan(copy, det, s)
                            faceUi("मालिक का चेहरा मिला — AI पूरी जाँच कर रहा है: असली इंसान है या फ़ोटो / वीडियो / स्क्रीन (${spoof.count()}/${FaceSpoof.SURE_FRAMES})… सीधे देखते रहिए")
                            return@execute
                        }
                        ownerHits++
                        faceUi("✓ मालिक पहचाने गए (${(s!! * 100).toInt()}%) — असली इंसान")
                        scan(copy, det, s)
                        if (ownerHits >= GuardRules.OWNER_HITS) {
                            authorized = true
                            holo?.let { h -> h.place(det, copy.width, copy.height, true, tone = HoloScanView.OK)?.let { f ->
                                h.show(HoloScanView.Mode.AUTHORIZED, listOf(f), 3, bars(s), HoloScanView.AUTHORIZED, "स्वागत है, ${Prefs.userName(this).trim().ifBlank { "Boss" }}") } }
                            Guard.disarm(this, "मालिक का चेहरा पहचाना (${(s * 100).toInt()}%, सिर घुमाकर${if (spoofOn) " + AI anti-spoof से" else ""} असली इंसान जाँचा)")
                            runOnUiThread { root.postDelayed({ finish() }, AUTH_SHOW_MS) }
                        }
                    } else {
                        ownerHits = 0
                        if (s != null && !owner && s < FaceMath.MATCH * 0.8f && liveness.step == FaceLiveness.Step.TURN_ONE) declined(copy, det, "यह चेहरा Boss का नहीं (${(s * 100).toInt()}%)")
                        else scan(copy, det, s)
                        if (s != null && now - lastFaceUi > UI_MS) {
                            lastFaceUi = now
                            faceUi(when {
                                !owner && liveness.step == FaceLiveness.Step.TURN_ONE -> "✗ यह चेहरा मालिक का नहीं (${(s * 100).toInt()}%)"
                                // बहुत देर सिर्फ़ सीधा, कोई घुमाव नहीं = शायद फ़ोटो/स्क्रीन
                                liveness.step == FaceLiveness.Step.TURN_ONE && liveness.flatCount() >= FLAT_WARN ->
                                    "✗ असली चेहरा नहीं (2D फ़ोटो?) — फ़ोटो से सायरन बंद नहीं होगा। असली इंसान सिर दाएँ-बाएँ घुमाए"
                                else -> "चेहरा मिला (${(s * 100).toInt()}%) — ${liveness.hint()}"
                            })
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally { copy.recycle(); busy = false }
        }
    }

    private fun bars(s: Float?): List<Pair<String, Float>> = buildList {
        if (s != null) add("MATCH" to s)
        add("LIVENESS" to liveness.done() / 3f)
        if (FaceAI.spoofReady(this@GuardAlarmActivity) && spoof.count() > 0) add("AI ANTI-SPOOF" to spoof.mean())
    }

    /** स्कैन चल रहा है (नीला) */
    private fun scan(b: Bitmap, det: FaceMath.Det, s: Float?) {
        if (authorized || System.currentTimeMillis() < declinedUntil) return
        val h = holo ?: return
        val f = h.place(det, b.width, b.height, true, "SCANNING", liveness.hint()) ?: return
        h.show(HoloScanView.Mode.SCAN, listOf(f), liveness.done(), bars(s))
    }

    /** बॉस नहीं / नकली = लाल DECLINED (कुछ देर) */
    private fun declined(b: Bitmap, det: FaceMath.Det, why: String) {
        if (authorized) return
        declinedUntil = System.currentTimeMillis() + DECLINED_SHOW_MS
        faceUi("✗ $why")
        val h = holo ?: return
        val f = h.place(det, b.width, b.height, true, tone = HoloScanView.BAD)
        h.show(HoloScanView.Mode.DECLINED, listOfNotNull(f), liveness.done(), bars(null), HoloScanView.DECLINED, why)
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
        work.shutdown()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val SHARP_BOX = 256
        /** घूमे चेहरे का मालिक से कम-से-कम इतना मिलना (वरना कोई और) */
        const val TURNED_MIN = 0.20f
        /** इतने फ़्रेम सिर्फ़ सीधा चेहरा (कोई घुमाव नहीं) = फ़ोटो की चेतावनी */
        const val FLAT_WARN = 12
        /** 14 नवंबर 2026: स्क्रीन पर अगला कदम जल्दी दिखे (पहले 1 s) */
        const val UI_MS = 400L
        /** हरा "BOSS AUTHORIZED" इतनी देर दिखे, फिर स्क्रीन बंद */
        const val AUTH_SHOW_MS = 1_100L
        const val DECLINED_SHOW_MS = 1_800L
        @Volatile private var current: WeakReference<GuardAlarmActivity>? = null
        @Volatile private var shown = false

        fun show(c: Context) {
            if (!Guard.alarming) return
            // स्क्रीन बंद की गई हो (power बटन) - फिर जलाओ
            try {
                val pm = c.getSystemService(android.os.PowerManager::class.java)
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
