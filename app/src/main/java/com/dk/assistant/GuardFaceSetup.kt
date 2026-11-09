package com.dk.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * नवंबर 2026 (मालिक): स्क्रीन गार्ड के लिए "मालिक का चेहरा" - पहली बार एक बार सेट, फिर अलार्म की चेतावनी स्क्रीन
 * ([GuardAlarmActivity]) का कैमरा मालिक को पहचानकर सायरन बंद करता है.
 *
 * जड़ (क्यों सायरन चेहरे से बंद नहीं होता था): 29 अक्टूबर (3659e77) को अलग "चेहरा पहचान" Settings हटी, तो चेहरा सेट
 * करने की कोई जगह नहीं बची - [FaceAI.enrolled] हमेशा false. वह अलग section वापस नहीं (मालिक ने हटवाया था);
 * यह सिर्फ़ Settings → स्क्रीन गार्ड के अंदर, और इसीलिए Settings फ़ाइलों से अलग फ़ाइल में.
 */
object GuardFaceSetup {
    fun enrolled(c: android.content.Context) = FaceAI.enrolled(c)
    fun forget(c: android.content.Context) = FaceAI.forget(c)

    fun status(c: android.content.Context): String = when {
        !FaceAI.supported -> "✗ यह फ़ोन 64-bit ARM नहीं है — चेहरा पहचान यहाँ नहीं चलेगी (PIN / गुप्त शब्द से बंद करें)"
        FaceAI.enrolled(c) && FaceAI.ready(c) -> "✓ मालिक का चेहरा सेट है — अलार्म में कैमरा आपको पहचानकर सायरन बंद करेगा"
        FaceAI.enrolled(c) -> "⚠ चेहरा सेट है पर पहचान मॉडल नहीं मिला — \"चेहरा फिर से सेट करें\" दबाएँ"
        else -> "✗ अभी मालिक का चेहरा सेट नहीं — इसके बिना कैमरा आपको पहचान नहीं पाएगा"
    }

    private var cam: FaceCam? = null

    private fun toast(a: AppCompatActivity, t: String) = UiLang.toast(a, t, Toast.LENGTH_SHORT).show()

    /** मालिक का चेहरा सेट (enroll) या जाँचने का कैमरा वाला डायलॉग - पहले वाले "चेहरा पहचान" (v3.91) जैसा */
    fun dialog(a: AppCompatActivity, enroll: Boolean, done: () -> Unit) {
        if (!FaceAI.supported) { toast(a, "यह फ़ोन 64-bit ARM नहीं है — चेहरा पहचान यहाँ नहीं चलेगी"); return }
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 16); toast(a, "कैमरा की परमिशन दें, फिर दोबारा दबाएँ"); return
        }
        val dp = a.resources.displayMetrics.density
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        val pv = androidx.camera.view.PreviewView(a).apply {
            implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        }
        wrap.addView(pv, LinearLayout.LayoutParams(-1, (300 * dp).toInt()))
        val tv = TextView(a).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, (12 * dp).toInt(), 0, 0) }
        wrap.addView(tv)
        val dlg = AlertDialog.Builder(a).setTitle(UiLang.s(if (enroll) "मालिक का चेहरा सेट करें" else "चेहरा जाँचें")).setView(wrap)
            .setNegativeButton(UiLang.s("बंद करें"), null).show()
        val ui = { t: String -> a.runOnUiThread { tv.text = UiLang.s(t) } }
        val prints = ArrayList<FloatArray>()
        var saved = false
        fun startCam() {
            ui(if (enroll) "फ़ोन आँखों के सामने रखें, रोशनी में सीधे कैमरे में देखें…" else "कैमरे में देखें…")
            val fc = FaceCam(a, pv) { b ->
                if (saved) return@FaceCam
                val d = FaceAI.detect(a, b, minScore = 0.6f)
                if (enroll) {
                    FaceMath.enrollProblem(d, b.width)?.let { ui(it + "  (${prints.size}/${FaceMath.ENROLL_FRAMES})"); return@FaceCam }
                    val e = FaceAI.embed(a, b, d!!) ?: run { ui(FaceAI.status(a)); return@FaceCam }
                    val mean = FaceMatch.aggregateEmbeddings(prints)
                    if (mean != null && FaceMatch.cosineSimilarity(mean, e) < FaceMath.ENROLL_SAME) {
                        prints.clear(); ui("बीच में चेहरा बदल गया — फिर से शुरू, अकेले कैमरे में देखें"); return@FaceCam
                    }
                    prints.add(e)
                    if (prints.size < FaceMath.ENROLL_FRAMES) { ui("सीख रहा हूँ… ${prints.size}/${FaceMath.ENROLL_FRAMES} — सिर थोड़ा-सा दाएँ-बाएँ घुमाएँ"); return@FaceCam }
                    saved = true
                    FaceAI.save(a, FaceMatch.aggregateEmbeddings(prints)!!)
                    Guard.note(a, "मालिक का चेहरा सेट हुआ")
                    ui("हो गया — मालिक का चेहरा सेट। अब अलार्म में कैमरा आपको देखते ही सायरन बंद करेगा। \"जाँचें\" दबाकर देख सकते हैं।")
                    a.runOnUiThread { cam?.stop(); done() }
                } else {
                    if (d == null) { ui("चेहरा नहीं दिखा — सामने आएँ"); return@FaceCam }
                    val e = FaceAI.embed(a, b, d) ?: run { ui(FaceAI.status(a)); return@FaceCam }
                    val sc = FaceAI.score(a, e) ?: return@FaceCam
                    val ok = FaceMath.decide(sc, Prefs.faceStrict(a)) == MatchResult.ACCESS_GRANTED
                    ui((if (ok) "✓ आप हैं — मालिक पहचाने गए (सायरन बंद होगा)" else "✗ यह चेहरा मालिक से नहीं मिला") +
                        "\nमिलान: ${(sc * 100).toInt()}% (पास: ${((if (Prefs.faceStrict(a)) FaceMath.STRICT else FaceMath.MATCH) * 100).toInt()}% से ऊपर)")
                }
            }
            cam?.stop(); cam = fc
            fc.start { why -> ui("कैमरा नहीं खुला: $why") }
        }
        val job = a.lifecycleScope.launch {
            if (!FaceAI.hasModels(a)) {
                tv.text = UiLang.s("चेहरा पहचान मॉडल डाउनलोड हो रहा है (~10 MB, एक बार)…")
                val err = withContext(Dispatchers.IO) {
                    FaceAI.download(a) { pct -> a.runOnUiThread { tv.text = UiLang.s("चेहरा पहचान मॉडल डाउनलोड हो रहा है (~10 MB, एक बार)…") + " $pct%" } }
                }
                if (err != null) { tv.text = UiLang.s(err); return@launch }
            }
            if (!FaceAI.ready(a)) { tv.text = UiLang.s(FaceAI.status(a)); return@launch }
            startCam()
        }
        dlg.setOnDismissListener { job.cancel(); cam?.stop(); cam = null; done() }
    }
}
