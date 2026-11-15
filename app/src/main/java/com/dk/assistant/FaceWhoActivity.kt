package com.dk.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * 15 नवंबर 2026 (मालिक: "DK को सब पता हो, बोलकर चला सके"): DK लाइव का face_who - "DK, देखो कौन है".
 * आगे वाला कैमरा ~10 सेकंड में चेहरा देखकर [FacePeople.who] (Boss / सेव किए लोग / अनजान), नतीजा Live को, फिर बंद.
 * कोई तस्वीर सेव नहीं होती.
 */
class FaceWhoActivity : AppCompatActivity() {
    private var cam: FaceCam? = null
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-face-who").apply { isDaemon = true } }
    @Volatile private var busy = false
    @Volatile private var done = false
    private val votes = HashMap<String, Int>()
    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFF0B1020.toInt()) }
        val pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(pv, LinearLayout.LayoutParams((260 * d).toInt(), (320 * d).toInt()))
        tv = TextView(this).apply { textSize = 17f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, 0); text = UiLang.s("कैमरे में देखें… DK पहचान रही है") }
        root.addView(tv)
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWith("ERROR: camera permission not given - ask the user to allow Camera for DK (Settings → चेहरा पहचान)"); return
        }
        if (!FaceAI.ready(this)) { finishWith("ERROR: " + FaceAI.status(this) + " - the face model downloads on first use from Settings → चेहरा पहचान"); return }
        cam = FaceCam(this, pv) { b -> onFrame(b) }.also { it.start { w -> finishWith("ERROR: camera did not open ($w)") } }
        tv.postDelayed({ if (!done) finishWith(verdict() ?: "NO_FACE: no face was seen in 10 seconds - tell the user to look into the front camera in good light") }, 10_000)
    }

    private fun onFrame(b: Bitmap) {
        if (busy || done) return
        busy = true
        val copy = try { b.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { busy = false; return }
        work.execute {
            try {
                val det = FaceAI.detect(this, copy, minScore = 0.6f) ?: return@execute
                val e = FaceAI.embed(this, copy, det) ?: return@execute
                val w = FacePeople.who(this, e)
                last = w
                val key = when { w.boss -> FacePeople.BOSS; w.name != null && w.sure -> w.name; else -> "?" }
                val n = (votes[key] ?: 0) + 1
                votes[key] = n
                runOnUiThread { tv.text = UiLang.s(w.say(Prefs.userName(this))) }
                // 2 बार एक जैसा पक्का नतीजा = बस
                if (n >= 2) finishWith(result(w))
            } catch (_: Throwable) {
            } finally { copy.recycle(); busy = false }
        }
    }

    @Volatile private var last: FacePeople.Who? = null
    private fun result(w: FacePeople.Who): String = "RESULT: " + w.say(Prefs.userName(this)) + ". Tell the user exactly this in one short line."
    private fun verdict(): String? = last?.let { result(it) }

    private fun finishWith(r: String) {
        if (done) return
        done = true
        pending?.complete(r)
        runOnUiThread { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        try { cam?.stop() } catch (_: Exception) {}
        work.shutdown()
        if (!done) { done = true; pending?.complete("CANCELLED: the camera screen was closed") }
        super.onDestroy()
    }

    companion object {
        @Volatile private var pending: CompletableDeferred<String>? = null

        /** DK लाइव से: कैमरा खोलकर पहचान (ज़्यादा से ज़्यादा ~15 s) */
        suspend fun ask(c: Context): String {
            val p = CompletableDeferred<String>()
            pending?.complete("CANCELLED")
            pending = p
            try {
                c.startActivity(Intent(c, FaceWhoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (e: Exception) { return "ERROR: could not open camera screen (${e.message})" }
            return withTimeoutOrNull(15_000) { p.await() } ?: "ERROR: timed out"
        }
    }
}
