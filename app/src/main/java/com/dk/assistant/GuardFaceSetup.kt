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
 * नवंबर 2026 (मालिक): टच मोड के लिए "मालिक का चेहरा" - पहली बार एक बार सेट, फिर अलार्म की चेतावनी स्क्रीन
 * ([GuardAlarmActivity]) का कैमरा मालिक को पहचानकर सायरन बंद करता है.
 *
 * जड़ (क्यों सायरन चेहरे से बंद नहीं होता था): 29 अक्टूबर (3659e77) को अलग "चेहरा पहचान" Settings हटी, तो चेहरा सेट
 * करने की कोई जगह नहीं बची - [FaceAI.enrolled] हमेशा false. वह अलग section वापस नहीं (मालिक ने हटवाया था);
 * यह सिर्फ़ Settings → टच मोड के अंदर, और इसीलिए Settings फ़ाइलों से अलग फ़ाइल में.
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
    /**
     * 15 नवंबर 2026 (मालिक: Settings में "चेहरा पहचान" वापस): [person] = उस नाम से जान-पहचान वाले का चेहरा सीखना
     * ([FacePeople]); [who] = "कौन है?" - Boss / सेव किए लोग / अनजान. दोनों खाली = पहले जैसा (मालिक).
     */
    fun dialog(a: AppCompatActivity, enroll: Boolean, person: String? = null, who: Boolean = false, kind: String = "", rel: String = "", done: () -> Unit) {
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
        // 15 नवंबर 2026: कैमरे के ऊपर futuristic स्कैनर ([HoloScanView])
        val holo = HoloScanView(a)
        val box = android.widget.FrameLayout(a).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(pv, android.widget.FrameLayout.LayoutParams(-1, -1)); addView(holo, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        holo.show(HoloScanView.Mode.SEARCH, emptyList())
        wrap.addView(box, LinearLayout.LayoutParams(-1, (320 * dp).toInt()))
        val spoof = FaceSpoof.Vote()
        val tv = TextView(a).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, (12 * dp).toInt(), 0, 0) }
        wrap.addView(tv)
        val dlg = AlertDialog.Builder(a).setTitle(UiLang.s(when { enroll && person != null -> "$person का चेहरा सीखें"; enroll -> "मालिक का चेहरा सेट करें"; who -> "कौन है?"; else -> "चेहरा जाँचें" })).setView(wrap)
            .setNegativeButton(UiLang.s("बंद करें"), null).show()
        val ui = { t: String -> a.runOnUiThread { tv.text = UiLang.s(t) } }
        val prints = ArrayList<FloatArray>()
        var saved = false
        fun startCam() {
            ui(if (enroll) "फ़ोन आँखों के सामने रखें, रोशनी में सीधे कैमरे में देखें…" else "कैमरे में देखें…")
            val fc = FaceCam(a, pv) { b ->
                if (saved) return@FaceCam
                // "कौन है?" = एक साथ कई चेहरे (Boss / दोस्त / परिवार / अनजान)
                if (who && !enroll) {
                    val ds = FaceAI.detectAll(a, b, minScore = 0.6f)
                    if (ds.isEmpty()) { holo.show(HoloScanView.Mode.SEARCH, emptyList()); ui("चेहरा नहीं दिखा — सामने आएँ"); return@FaceCam }
                    val ws = FacePeople.whoAll(a, ds.map { FaceAI.embed(a, b, it) })
                    val seen = ds.indices.map { i -> LensFaces.Rules.Seen(ws[i], fake = false, real = true) }
                    holo.show(HoloScanView.Mode.SCAN, ds.indices.mapNotNull { i ->
                        holo.place(ds[i], b.width, b.height, true, LensFaces.Rules.title(seen[i], Prefs.userName(a)), LensFaces.Rules.sub(seen[i]), LensFaces.Rules.tone(seen[i])) })
                    ui(ws.joinToString("\n") { it.say(Prefs.userName(a)) })
                    return@FaceCam
                }
                val d = FaceAI.detect(a, b, minScore = 0.6f)
                if (d == null) holo.show(HoloScanView.Mode.SEARCH, emptyList())
                if (enroll) {
                    d?.let { holo.place(it, b.width, b.height, true, "LEARNING", "${prints.size}/${FaceMath.ENROLL_FRAMES}")?.let { f ->
                        holo.show(HoloScanView.Mode.SCAN, listOf(f), bars = listOf("PROGRESS" to prints.size / FaceMath.ENROLL_FRAMES.toFloat())) } }
                    FaceMath.enrollProblem(d, b.width)?.let { ui(it + "  (${prints.size}/${FaceMath.ENROLL_FRAMES})"); return@FaceCam }
                    val e = FaceAI.embed(a, b, d!!) ?: run { ui(FaceAI.status(a)); return@FaceCam }
                    val mean = FaceMatch.aggregateEmbeddings(prints)
                    if (mean != null && FaceMatch.cosineSimilarity(mean, e) < FaceMath.ENROLL_SAME) {
                        prints.clear(); ui("बीच में चेहरा बदल गया — फिर से शुरू, अकेले कैमरे में देखें"); return@FaceCam
                    }
                    prints.add(e)
                    if (prints.size < FaceMath.ENROLL_FRAMES) { ui("सीख रहा हूँ… ${prints.size}/${FaceMath.ENROLL_FRAMES} — सिर थोड़ा-सा दाएँ-बाएँ घुमाएँ"); return@FaceCam }
                    saved = true
                    if (person != null) {
                        FacePeople.add(a, person, FaceMatch.aggregateEmbeddings(prints)!!, kind, rel)
                        val r = FacePeople.relationOf(kind, rel).let { if (it.isBlank()) "" else " ($it)" }
                        ui("हो गया — $person$r का चेहरा सेव। अब \"कौन है?\" दबाकर या DK से पूछकर पहचान सकते हैं।")
                        a.runOnUiThread { cam?.stop(); done() }
                        return@FaceCam
                    }
                    FaceAI.save(a, FaceMatch.aggregateEmbeddings(prints)!!)
                    Guard.note(a, "मालिक का चेहरा सेट हुआ")
                    ui("हो गया — मालिक का चेहरा सेट। अब अलार्म में कैमरा आपको देखते ही सायरन बंद करेगा। \"जाँचें\" दबाकर देख सकते हैं।")
                    a.runOnUiThread { cam?.stop(); done() }
                } else {
                    if (d == null) { ui("चेहरा नहीं दिखा — सामने आएँ"); return@FaceCam }
                    val e = FaceAI.embed(a, b, d) ?: run { ui(FaceAI.status(a)); return@FaceCam }
                    if (who) { ui(FacePeople.who(a, e).say(Prefs.userName(a))); return@FaceCam }
                    val sc = FaceAI.score(a, e) ?: return@FaceCam
                    val ok = FaceMath.decide(sc, Prefs.faceStrict(a)) == MatchResult.ACCESS_GRANTED
                    // 15 नवंबर 2026: जाँच में AI anti-spoof भी दिखे (फ़ोटो दिखाकर खुद आज़मा सकते हैं)
                    val spoofOn = FaceAI.spoofReady(a)
                    if (spoofOn) FaceAI.realness(a, b, d)?.let { spoof.add(it) }
                    val fake = spoofOn && spoof.fake()
                    val bars = buildList { add("MATCH" to sc); if (spoofOn && spoof.count() > 0) add("AI ANTI-SPOOF" to spoof.mean()) }
                    val f = holo.place(d, b.width, b.height, true, tone = if (fake || !ok) HoloScanView.BAD else HoloScanView.OK)
                    when {
                        fake -> holo.show(HoloScanView.Mode.DECLINED, listOfNotNull(f), bars = bars, banner = HoloScanView.DECLINED, reason = "फ़ोटो / वीडियो / स्क्रीन — असली नहीं")
                        !ok -> holo.show(HoloScanView.Mode.DECLINED, listOfNotNull(f), bars = bars, banner = HoloScanView.DECLINED, reason = "यह चेहरा Boss का नहीं")
                        !spoofOn || spoof.real() -> holo.show(HoloScanView.Mode.AUTHORIZED, listOfNotNull(f), bars = bars, banner = HoloScanView.AUTHORIZED)
                        else -> holo.show(HoloScanView.Mode.SCAN, listOfNotNull(f), bars = bars)
                    }
                    if (fake) { ui("✗ फ़ोटो / वीडियो / स्क्रीन पकड़ी गई — असली इंसान नहीं (AI ${(spoof.mean() * 100).toInt()}%)"); return@FaceCam }
                    ui((if (ok) "✓ आप हैं — मालिक पहचाने गए (सायरन बंद होगा)" else "✗ यह चेहरा मालिक से नहीं मिला") +
                        "\nमिलान: ${(sc * 100).toInt()}% (पास: ${((if (Prefs.faceStrict(a)) FaceMath.STRICT else FaceMath.MATCH) * 100).toInt()}% से ऊपर)")
                }
            }
            cam?.stop(); cam = fc
            fc.start { why -> ui("कैमरा नहीं खुला: $why") }
        }
        val job = a.lifecycleScope.launch {
            // 15 नवंबर 2026: पहचान के दो + फ़ोटो/वीडियो पकड़ने वाले (anti-spoof) दो मॉडल
            if (!FaceAI.hasModels(a) || (Prefs.faceSpoofOn(a) && !FaceAI.hasSpoof(a))) {
                tv.text = UiLang.s("चेहरा पहचान + anti-spoof मॉडल डाउनलोड हो रहा है (~13 MB, एक बार)…")
                val err = withContext(Dispatchers.IO) {
                    FaceAI.download(a) { pct -> a.runOnUiThread { tv.text = UiLang.s("चेहरा पहचान + anti-spoof मॉडल डाउनलोड हो रहा है (~13 MB, एक बार)…") + " " + DlText.progress(pct, FaceAI.ALL.sumOf { it.bytes }) } }
                }
                if (err != null) { tv.text = UiLang.s(err); return@launch }
            }
            if (!FaceAI.ready(a)) { tv.text = UiLang.s(FaceAI.status(a)); return@launch }
            startCam()
        }
        dlg.setOnDismissListener { job.cancel(); cam?.stop(); cam = null; done() }
    }
}
