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
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-guard-face").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var shots = 0
    @Volatile private var lastShot = 0L
    @Volatile private var ownerHits = 0
    private var startedAt = 0L
    private var blinkOn = false
    private lateinit var root: LinearLayout

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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFFB00020.toInt()) }
        fun tv(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            text = UiLang.s(t); textSize = size; setTextColor(color); gravity = Gravity.CENTER
            val p = (20 * d).toInt(); setPadding(p, p / 3, p, p / 3)
        }
        root.addView(tv("⚠ चेतावनी", 40f))
        root.addView(tv("यह फ़ोन आपका नहीं है", 30f))
        root.addView(tv("इसे तुरंत वापस रखिए। आपकी फ़ोटो ली जा रही है और मालिक को खबर दी जा रही है।", 17f, 0xEEFFFFFF.toInt()))
        val preview = PreviewView(this)
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { setStroke((3 * d).toInt(), Color.WHITE); cornerRadius = 16 * d }
            val p = (3 * d).toInt(); setPadding(p, p, p, p)
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(frame, LinearLayout.LayoutParams((150 * d).toInt(), (190 * d).toInt()).apply { topMargin = (12 * d).toInt(); bottomMargin = (12 * d).toInt() })
        root.addView(Button(this).apply {
            text = UiLang.s("मैं मालिक हूँ — फ़ोन खोलें")
            setOnClickListener { askUnlock() }
        })
        root.addView(tv("मालिक: फ़ोन खोलें, चेहरा कैमरे के सामने करें, या गुप्त शब्द बोलें", 13f, 0xCCFFFFFF.toInt()))
        setContentView(root)
        blink()
        startCam(preview)
    }

    private fun blink() {
        if (isFinishing) return
        blinkOn = !blinkOn
        root.setBackgroundColor(if (blinkOn) 0xFFB00020.toInt() else 0xFF000000.toInt())
        root.postDelayed({ blink() }, 500)
    }

    private fun startCam(preview: PreviewView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val photo = Guard.photoOn(this)
        val face = Guard.faceOn(this) && FaceAI.enrolled(this) && FaceAI.ready(this)
        if (!photo && !face) return
        cam = FaceCam(this, preview) { b -> onFrame(b, photo, face) }.also { it.start { DKLog.d("गार्ड: कैमरा नहीं खुला ($it)") } }
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
                if (photo && shots < 3 && now - lastShot > 2_500 && (det != null || (shots == 0 && now - startedAt > 1_500))) {
                    if (Guard.savePhoto(this, copy) != null) { shots++; lastShot = now }
                }
                if (face && det != null) {
                    val e = FaceAI.embed(this, copy, det)
                    val s = e?.let { FaceAI.score(this, it) }
                    if (s != null && FaceMath.decide(s, Prefs.faceStrict(this)) == MatchResult.ACCESS_GRANTED) {
                        ownerHits++
                        if (ownerHits >= 2) { Guard.disarm(this, "मालिक का चेहरा पहचाना"); runOnUiThread { finish() } }
                    } else ownerHits = 0
                }
            } catch (_: Throwable) {
            } finally { copy.recycle(); busy = false }
        }
    }

    /** फ़ोन का अपना PIN / फ़िंगरप्रिंट - खुला = मालिक (USER_PRESENT से [Guard] बंद) */
    private fun askUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { if (Guard.secure(this@GuardAlarmActivity)) Guard.disarm(this@GuardAlarmActivity, "फ़ोन मालिक ने खोला") }
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
        try { cam?.stop() } catch (_: Exception) {}
        work.shutdown()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
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
            } catch (_: Exception) {}
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
