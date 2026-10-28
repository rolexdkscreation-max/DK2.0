package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * v3.91: चेहरा सिखाने/जाँचने का छोटा कैमरा (सेटिंग्स में) - आगे वाला कैमरा, preview + हर ~0.35 सेकंड एक सीधा (घुमाया) फ़्रेम.
 * फ़्रेम सिर्फ़ फ़ोन के अंदर पहचान बनाने में - न सेव, न कहीं भेजा.
 */
class FaceCam(private val act: AppCompatActivity, private val view: PreviewView, private val onFrame: (Bitmap) -> Unit) {
    private var provider: ProcessCameraProvider? = null
    private var useCases: Array<androidx.camera.core.UseCase> = emptyArray()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-face-cam").apply { isDaemon = true } }
    @Volatile private var last = 0L
    @Volatile var stopped = false
        private set

    fun start(onFail: (String) -> Unit) {
        val f = ProcessCameraProvider.getInstance(act)
        f.addListener({
            try {
                if (stopped) return@addListener
                val p = f.get(); provider = p
                val sel = CameraSelector.DEFAULT_FRONT_CAMERA
                if (!p.hasCamera(sel)) { onFail("आगे वाला कैमरा नहीं मिला"); return@addListener }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                @Suppress("DEPRECATION")
                val an = ImageAnalysis.Builder().setTargetResolution(Size(720, 960))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                an.setAnalyzer(exec) { img ->
                    try {
                        val now = System.currentTimeMillis()
                        if (stopped || now - last < 350) return@setAnalyzer
                        last = now
                        var b = img.toBitmap()
                        val rot = img.imageInfo.rotationDegrees
                        if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                        onFrame(b)
                    } catch (_: Throwable) {
                    } finally { img.close() }
                }
                useCases = arrayOf(preview, an)
                p.bindToLifecycle(act, sel, preview, an)
            } catch (e: Exception) { onFail(e.message ?: "कैमरा नहीं खुला") }
        }, ContextCompat.getMainExecutor(act))
    }

    fun stop() {
        stopped = true
        try { provider?.unbind(*useCases) } catch (_: Exception) {}
        exec.shutdown()
    }
}
