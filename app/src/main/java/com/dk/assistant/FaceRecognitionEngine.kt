package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DK लाइव कैमरे पर चेहरा पहचान का निशान.
 * v3.91: पुराना तरीका (त्वचा जैसा रंग + 128 धूसर पिक्सेल) असल में चेहरा नहीं पहचानता था, और सिखाने वाला हिस्सा कभी चलता ही नहीं था.
 * अब FaceAI (YuNet + SFace, फ़ोन के अंदर). चेहरा सिखाना: सेटिंग्स → चेहरा पहचान.
 */
class FaceRecognitionEngine(private val context: Context) {

    data class AnalysisResult(
        val result: MatchResult,
        val similarity: Float,
        /** फ़्रेम (bitmap) के हिसाब से - स्क्रीन पर लाने को FaceMath.toView */
        val faceBounds: RectF?,
        val embedding: FloatArray?,
        val scanProgress: Float,
        val frameW: Int = 0,
        val frameH: Int = 0
    )

    private val isProcessing = AtomicBoolean(false)
    @Volatile private var last = 0L
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-face").apply { isDaemon = true } }

    /** हर ~0.6 सेकंड एक फ़्रेम (CPU/बैटरी). मॉडल न हो / बंद हो = NO_FACE (निशान नहीं दिखता) */
    fun processFrame(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - last < 600 || !Prefs.faceHudOn(context) || !FaceAI.ready(context)) return
        if (!isProcessing.compareAndSet(false, true)) return
        last = now
        val b = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { isProcessing.set(false); return }
        exec.execute {
            try {
                val d = FaceAI.detect(context, b)
                if (d == null) { onResult(AnalysisResult(MatchResult.NO_FACE, 0f, null, null, 0f, b.width, b.height)); return@execute }
                val rect = RectF(d.x, d.y, d.x + d.w, d.y + d.h)
                if (!FaceAI.enrolled(context)) { onResult(AnalysisResult(MatchResult.SCANNING, 0f, rect, null, 0f, b.width, b.height)); return@execute }
                val e = FaceAI.embed(context, b, d)
                val s = e?.let { FaceAI.score(context, it) }
                val res = if (s == null) MatchResult.SCANNING else FaceMath.decide(s, Prefs.faceStrict(context))
                onResult(AnalysisResult(res, s ?: 0f, rect, null, 1f, b.width, b.height))
            } catch (_: Throwable) {
            } finally { b.recycle(); isProcessing.set(false) }
        }
    }

    /** पुराना API - अब सिखाना सेटिंग्स → चेहरा पहचान से होता है */
    fun completeEnrollment(): Boolean = FaceAI.enrolled(context)

    fun reset() { isProcessing.set(false) }
}
