package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * v3.40: DK लाइव का कैमरा - आवाज़ स्क्रीन पर preview, और हर ~1 सेकंड एक छोटी JPEG Gemini Live को.
 * Activity के lifecycle से बँधा (screen छोड़ी = कैमरा अपने-आप बंद, Android भी पीछे कैमरा नहीं देता).
 */
class CameraFeed(private val act: AppCompatActivity, private val view: PreviewView) {
    private var provider: ProcessCameraProvider? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val gate = FrameGate(minGapMs = 1000, idleGapMs = 2000)
    @Volatile private var lastTry = 0L
    @Volatile private var sent = 0
    var front = false
        private set
    var running = false
        private set

    fun start(front: Boolean, onOk: () -> Unit, onFail: (String) -> Unit) {
        val f = ProcessCameraProvider.getInstance(act)
        f.addListener({
            try {
                val p = f.get(); provider = p
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!p.hasCamera(sel)) { onFail(if (front) "आगे वाला कैमरा नहीं मिला" else "पीछे वाला कैमरा नहीं मिला"); return@addListener }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                @Suppress("DEPRECATION")
                val an = ImageAnalysis.Builder()
                    .setTargetResolution(Size(720, 960))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                an.setAnalyzer(exec) { img -> analyze(img) }
                p.unbindAll()
                p.bindToLifecycle(act, sel, preview, an)
                this.front = front; running = true
                onOk()
            } catch (e: Exception) {
                running = false
                DKLog.d("कैमरा नहीं खुला: ${e.message}")
                onFail(e.message ?: "कैमरा नहीं खुला")
            }
        }, ContextCompat.getMainExecutor(act))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastTry < 900) return          // बाकी frame बिना बदले छोड़ो (CPU/बैटरी)
            lastTry = now
            val raw = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            val (fw, fh) = FrameGate.fit(raw.width, raw.height, 1024)
            var b = if (fw != raw.width || fh != raw.height) Bitmap.createScaledBitmap(raw, fw, fh, true) else raw
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            }
            val sig = ScreenShareService.signature(b)
            if (!gate.shouldSend(now, sig)) return
            val out = ByteArrayOutputStream(64 * 1024)
            b.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (LiveVision.frame(out.toByteArray())) {
                gate.sent(now, sig); sent++
                if (sent == 1 || sent % 60 == 0) DKLog.d("कैमरा फ़्रेम #$sent भेजा (${out.size() / 1024}KB)")
            }
        } catch (e: Exception) {
            DKLog.d("कैमरा फ़्रेम गड़बड़: ${e.message}")
        } finally { img.close() }
    }

    fun stop() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        running = false
    }

    fun release() { stop(); exec.shutdown() }
}
