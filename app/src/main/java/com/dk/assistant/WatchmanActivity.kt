package com.dk.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
 * 15 नवंबर 2026 (मालिक): "DK वॉचमैन - ऑन करके फ़ोन सामने रख दूँ, कौन आ रहा है कौन जा रहा है DK देखता रहे".
 *
 * स्क्रीन जागी रहती है (थोड़ी देर बाद लगभग काली - बैटरी), आगे वाला कैमरा हर ~1 s देखता है ([Watchman.Book]).
 * स्क्रीन पर किसी का नाम / गिनती **नहीं** दिखती (सामने वाला न देख ले) - सिर्फ़ "DK वॉचमैन देख रहा है".
 * कोई स्क्रीन छुए = लिखा जाता है ("फ़ोन छुआ") + फ़ोटो. बंद करना = सिर्फ़ Boss का चेहरा ([BossCheckActivity.MODE_STOP]).
 * कोई Home दबाकर बीच में छोड़े = वह भी रिपोर्ट में ("वॉचमैन X मिनट रुका रहा").
 */
class WatchmanActivity : AppCompatActivity() {
    private var cam: FaceCam? = null
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-watchman").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var lastFrame = 0L
    @Volatile private var lastSave = 0L
    @Volatile private var wantPhoto = false
    private var pausedAt = 0L
    private var bossStop = false
    private lateinit var status: TextView
    private lateinit var shade: FrameLayout
    private var lastTouch = 0L
    private var dimAt = 0L
    private var pv: PreviewView? = null
    private var checking = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        shade = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; alpha = 0.35f }
        this.pv = pv
        shade.addView(pv, FrameLayout.LayoutParams((90 * d).toInt(), (120 * d).toInt(), Gravity.TOP or Gravity.END).apply { topMargin = (24 * d).toInt(); rightMargin = (16 * d).toInt() })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(TextView(this).apply { text = UiLang.s("DK वॉचमैन"); textSize = 26f; setTextColor(0xFF00E5FF.toInt()); gravity = Gravity.CENTER })
        status = TextView(this).apply { textSize = 15f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER; setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (20 * d).toInt()) }
        col.addView(status)
        col.addView(Button(this).apply { text = UiLang.s("बंद करें (सिर्फ़ Boss का चेहरा)"); isAllCaps = false; setOnClickListener { askStop() } })
        shade.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(shade)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { status.text = UiLang.s("कैमरे की इजाज़त नहीं — Settings → चेहरा पहचान"); return }
        if (!FaceAI.ready(this)) { status.text = UiLang.s(FaceAI.status(this)); return }
        current = WeakReference(this)
        Watchman.running = true
        Watchman.book(this).start(System.currentTimeMillis()); Watchman.save(this)
        status.text = UiLang.s("देख रहा है… फ़ोन ऐसी जगह रखें जहाँ से आने-जाने वालों के चेहरे दिखें (चार्जर लगा दें)")
        dimAt = System.currentTimeMillis() + DIM_AFTER_MS
        openCam()
        tick()
    }

    private fun openCam() {
        val v = pv ?: return
        cam = FaceCam(this, v, capture = false) { b -> onFrame(b) }.also { it.start { w -> runOnUiThread { status.text = UiLang.s("कैमरा नहीं खुला: $w") } } }
    }

    private fun tick() {
        if (isFinishing) return
        val now = System.currentTimeMillis()
        // थोड़ी देर बाद स्क्रीन लगभग काली (बैटरी) - कैमरा चलता रहता है
        window.attributes = window.attributes.apply { screenBrightness = if (now >= dimAt) 0.01f else -1f }
        status.postDelayed({ tick() }, 5_000)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            dimAt = now + DIM_AFTER_MS
            window.attributes = window.attributes.apply { screenBrightness = -1f }
            if (now - lastTouch > 10_000L && Watchman.running) {
                lastTouch = now
                work.execute { Watchman.book(this).touch(now); wantPhoto = true; Watchman.save(this) }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onFrame(b: Bitmap) {
        val now = System.currentTimeMillis()
        if (busy || now - lastFrame < FRAME_MS || !Watchman.running) return
        busy = true; lastFrame = now
        val copy = try { b.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { busy = false; return }
        work.execute {
            try {
                val book = Watchman.book(this)
                val dets = FaceAI.detectAll(this, copy, minScore = 0.65f)
                var newVisit = false
                if (dets.isNotEmpty()) {
                    val prints = dets.map { FaceAI.embed(this, copy, it) }
                    val ws = FacePeople.whoAll(this, prints)
                    val ids = ws.mapIndexed { i, w ->
                        when {
                            w.boss && w.sure -> Watchman.BOSS_ID
                            w.name != null && w.sure -> "p:" + w.name
                            else -> prints[i]?.let { book.unknownId(it) } ?: ""
                        }
                    }
                    ws.forEachIndexed { i, w ->
                        val id = ids[i]; if (id.isEmpty()) return@forEachIndexed
                        val kind = when { id == Watchman.BOSS_ID -> "boss"; id.startsWith("p:") -> w.kind; else -> "unknown" }
                        val (v, isNew) = book.see(id, w.name ?: "", kind, w.rel, now, dets[i].w / copy.width, ids.filter { it.isNotEmpty() })
                        // हर आने पर एक फ़ोटो (Boss की नहीं)
                        if (id != Watchman.BOSS_ID && v.photo.isEmpty() && v.frames >= Watchman.MIN_FRAMES) {
                            Watchman.savePhoto(this, copy)?.let { v.photo = it }
                        }
                        if (isNew) newVisit = true
                    }
                }
                if (wantPhoto) { wantPhoto = false; Watchman.savePhoto(this, copy) }
                if (newVisit || now - lastSave > SAVE_MS) { lastSave = now; book.tick(now); Watchman.save(this) }
            } catch (_: Throwable) {
            } finally { copy.recycle(); busy = false }
        }
    }

    /** Boss जाँच का कैमरा खुल सके - वॉचमैन का कैमरा थोड़ी देर बंद, लौटने पर फिर ([onStart]) */
    private fun askStop() {
        try { cam?.stop() } catch (_: Exception) {}
        cam = null; checking = System.currentTimeMillis()
        BossCheckActivity.open(this, BossCheckActivity.MODE_STOP)
    }

    @Deprecated("Back = Boss जाँच")
    override fun onBackPressed() { askStop() }

    override fun onStop() {
        super.onStop()
        if (!bossStop && Watchman.running) pausedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        val p = pausedAt; pausedAt = 0L
        val now = System.currentTimeMillis()
        val wasCheck = checking > 0 && now - checking < BossGate.TIMEOUT_MS + 10_000L
        checking = 0L
        if (Watchman.running && !bossStop && cam == null && pv != null) openCam()
        if (p > 0 && now - p > PAUSE_NOTE_MS && Watchman.running && !wasCheck) {
            Watchman.book(this).note(now, "वॉचमैन ${Watchman.dur(now - p)} रुका रहा (स्क्रीन छोड़ी गई / बंद हुई) — उस समय कुछ नहीं दिखा")
            Watchman.save(this)
        }
    }

    override fun onDestroy() {
        try { cam?.stop() } catch (_: Exception) {}
        if (Watchman.running && !bossStop) {
            val now = System.currentTimeMillis()
            Watchman.book(this).stop(now, "वॉचमैन की स्क्रीन बंद हो गई — Boss ने बंद नहीं किया")
            Watchman.save(this)
            Watchman.running = false
        }
        work.shutdown()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val FRAME_MS = 900L
        const val SAVE_MS = 15_000L
        const val DIM_AFTER_MS = 30_000L
        const val PAUSE_NOTE_MS = 5_000L
        @Volatile private var current: WeakReference<WatchmanActivity>? = null

        fun start(c: Context): String {
            if (!FaceAI.ready(c)) return "ERROR: " + FaceAI.status(c) + " - face model downloads from Settings → चेहरा पहचान"
            if (c.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return "ERROR: camera permission not given"
            return try {
                c.startActivity(Intent(c, WatchmanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                "STARTED: DK वॉचमैन चालू - फ़ोन सामने रखें. बंद सिर्फ़ Boss के चेहरे से."
            } catch (e: Exception) { "ERROR: ${e.message}" }
        }

        /** सिर्फ़ [BossCheckActivity] (Boss पास) से */
        fun stopByBoss() {
            val a = current?.get()
            val now = System.currentTimeMillis()
            if (a != null) {
                a.bossStop = true
                Watchman.book(a).stop(now, "Boss ने वॉचमैन बंद किया"); Watchman.save(a)
                Watchman.running = false
                a.runOnUiThread { a.finish() }
            } else Watchman.running = false
        }
    }
}
