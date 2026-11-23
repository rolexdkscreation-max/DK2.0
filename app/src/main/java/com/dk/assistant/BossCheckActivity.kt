package com.dk.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * 15 नवंबर 2026 (मालिक): वॉचमैन की जानकारी से पहले DK ख़ुद चेहरा स्कैन खोले - Boss हो तो ही बताए.
 * सख़्त नियम [BossGate] में. [MODE_REPORT] = पास होने पर यहीं रिपोर्ट + फ़ोटो, [MODE_STOP] = वॉचमैन बंद,
 * [MODE_CLEAR] = रिकॉर्ड मिटाना, [MODE_VERIFY] = DK लाइव के लिए सिर्फ़ हाँ/ना.
 */
class BossCheckActivity : AppCompatActivity() {
    private var cam: FaceCam? = null
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-boss-check").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var done = false
    private val gate = BossGate()
    private val liveness = FaceLiveness().apply { challenge(FaceLiveness.randomSide()) }
    private val spoof = FaceSpoof.Vote(BossGate.SPOOF_WINDOW)
    private lateinit var tv: TextView
    private lateinit var root: LinearLayout
    private var holo: HoloScanView? = null
    private var mode = MODE_VERIFY
    private var lastUi = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(com.dk.assistant.ui.ClayTheme.t.bg) }
        root.addView(TextView(this).apply {
            text = UiLang.s("DK सुरक्षा जाँच — सिर्फ़ Boss"); textSize = 18f; setTextColor(com.dk.assistant.ui.ClayTheme.t.accent); gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * d).toInt())
        })
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        holo = HoloScanView(this).also { it.show(HoloScanView.Mode.SEARCH, emptyList()) }
        root.addView(FrameLayout(this).apply {
            // 23 नवंबर 2026 (मालिक): कैमरा Clay के धँसे फ़्रेम में (रंग थीम से)
            background = com.dk.assistant.ui.Clay.inset(this@BossCheckActivity, 18f); val cp = (6 * d).toInt(); setPadding(cp, cp, cp, cp); clipToOutline = true
            addView(pv, FrameLayout.LayoutParams(-1, -1)); addView(holo, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams((260 * d).toInt(), (320 * d).toInt()))
        tv = TextView(this).apply { textSize = 16f; setTextColor(com.dk.assistant.ui.ClayTheme.t.text); gravity = Gravity.CENTER; setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), 0); text = UiLang.s(liveness.guide()) }
        root.addView(tv)
        root.addView(Button(this).apply { text = UiLang.s("रद्द करें"); isAllCaps = false; setOnClickListener { finishWith(false, "CANCELLED") } })
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { finishWith(false, "ERROR: camera permission not given"); return }
        if (!FaceAI.ready(this) || !FaceAI.enrolled(this)) { finishWith(false, "ERROR: Boss face is not set (Settings → चेहरा पहचान)"); return }
        val start = {
            cam = FaceCam(this, pv) { b -> onFrame(b) }.also { it.start { w -> finishWith(false, "ERROR: camera did not open ($w)") } }
            tv.postDelayed({ if (!done) fail("समय ख़त्म — Boss पक्का नहीं हुआ") }, BossGate.TIMEOUT_MS)
        }
        if (FaceAI.hasSpoof(this)) start()
        else {
            tv.text = UiLang.s("AI anti-spoof मॉडल (~3.5 MB) डाउनलोड हो रहा है — सख़्त जाँच के लिए ज़रूरी…")
            Thread { val err = FaceAI.downloadSpoof(applicationContext); runOnUiThread { if (err == null) start() else fail("anti-spoof मॉडल नहीं आया ($err) — बिना पूरी जाँच के जानकारी नहीं दूँगी") } }
                .apply { isDaemon = true }.start()
        }
    }

    private fun ui(t: String) = runOnUiThread { tv.text = UiLang.s(t) }

    private fun onFrame(b: Bitmap) {
        if (busy || done) return
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
                            else -> h.show(HoloScanView.Mode.SCAN, listOf(f), bars = bars)
                        }
                    }
                }
                when (v.state) {
                    BossGate.State.PASS -> runOnUiThread { pass() }
                    BossGate.State.FAIL -> fail(v.why)
                    else -> if (now - lastUi > 400) { lastUi = now; ui(v.why.ifBlank { liveness.hint() }) }
                }
            } catch (_: Throwable) {
            } finally { copy.recycle(); busy = false }
        }
    }

    private fun fail(why: String) {
        if (done) return
        ui("✗ DECLINED — $why")
        Watchman.book(this).note(System.currentTimeMillis(), "रिपोर्ट/कंट्रोल माँगा गया — Boss नहीं निकला ($why)"); Watchman.save(this)
        runOnUiThread { tv.postDelayed({ finishWith(false, "DENIED: $why") }, 1_500) }
    }

    private fun pass() {
        if (done) return
        passedAt = System.currentTimeMillis()
        try { cam?.stop() } catch (e: Exception) { Quiet.note(e) }
        when (mode) {
            MODE_REPORT -> showReport()
            MODE_STOP -> { WatchmanActivity.stopByBoss(); finishWith(true, "OK") }
            MODE_CLEAR -> AlertDialog.Builder(this).setTitle(UiLang.s("वॉचमैन का सारा रिकॉर्ड और फ़ोटो मिटा दें?"))
                .setPositiveButton(UiLang.s("मिटाएँ")) { _, _ -> Watchman.clearAll(this); UiLang.toast(this, "मिटा दिया", android.widget.Toast.LENGTH_SHORT).show(); finishWith(true, "OK") }
                .setNegativeButton(UiLang.s("रहने दें")) { _, _ -> finishWith(true, "OK") }.setCancelable(false).show()
            else -> { ui("✓ BOSS AUTHORIZED"); tv.postDelayed({ finishWith(true, "OK") }, 700) }
        }
    }

    private fun showReport() {
        val d = resources.displayMetrics.density
        root.removeAllViews(); root.gravity = Gravity.TOP
        root.setPadding((14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
        root.addView(TextView(this).apply { text = UiLang.s("✓ BOSS AUTHORIZED — DK वॉचमैन रिपोर्ट"); textSize = 17f; setTextColor(0xFF34D399.toInt()) })
        val photos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(photos)
        GuardPhotos.strip(this, photos, Watchman.photos(this)) {}
        root.addView(ScrollView(this).apply {
            addView(TextView(this@BossCheckActivity).apply { text = UiLang.s(Watchman.reportFor(this@BossCheckActivity)); textSize = 15f; setTextColor(com.dk.assistant.ui.ClayTheme.t.text); setTextIsSelectable(true) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = UiLang.s("बंद करें"); isAllCaps = false; setOnClickListener { finishWith(true, "OK") } })
    }

    override fun onStop() {
        super.onStop()
        // रिपोर्ट खुली छोड़कर फ़ोन किसी और के हाथ - बाहर जाते ही बंद
        if (!isChangingConfigurations) finishWith(done && result == true, if (done) "OK" else "CANCELLED")
    }

    private var result: Boolean? = null
    private fun finishWith(ok: Boolean, why: String) {
        if (result == null) { result = ok; pending?.complete(ok to why) }
        done = true
        runOnUiThread { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        try { cam?.stop() } catch (e: Exception) { Quiet.note(e) }
        work.shutdown()
        if (result == null) { result = false; pending?.complete(false to "CANCELLED") }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_VERIFY = "verify"
        const val MODE_REPORT = "report"
        const val MODE_STOP = "stop"
        const val MODE_CLEAR = "clear"
        @Volatile private var pending: CompletableDeferred<Pair<Boolean, String>>? = null
        @Volatile private var passedAt = 0L

        fun open(c: Context, mode: String) {
            c.startActivity(Intent(c, BossCheckActivity::class.java).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }

        /** DK लाइव से: सख़्त Boss जाँच (हाल में पास हुआ हो तो दोबारा नहीं). (हाँ/ना, कारण) */
        suspend fun verify(c: Context): Pair<Boolean, String> {
            if (System.currentTimeMillis() - passedAt in 0..BossGate.GRACE_MS) return true to "OK (recent)"
            val p = CompletableDeferred<Pair<Boolean, String>>()
            pending?.complete(false to "CANCELLED"); pending = p
            try { open(c, MODE_VERIFY) } catch (e: Exception) { return false to "ERROR: could not open camera screen (${e.message})" }
            return withTimeoutOrNull(BossGate.TIMEOUT_MS + 10_000) { p.await() } ?: (false to "ERROR: timed out")
        }
    }
}
