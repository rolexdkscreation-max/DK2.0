package com.dk.assistant

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * v3.91: चेहरा सिखाने/जाँचने का छोटा कैमरा (सेटिंग्स में) - आगे वाला कैमरा, preview + हर ~0.35 सेकंड एक सीधा (घुमाया) फ़्रेम.
 * फ़्रेम सिर्फ़ फ़ोन के अंदर पहचान बनाने में - न सेव, न कहीं भेजा.
 */
class FaceCam(private val act: AppCompatActivity, private val view: PreviewView, private val capture: Boolean = false, private val onFrame: (Bitmap) -> Unit) {
    /**
     * 15 नवंबर 2026 (मालिक: "टच मोड की फ़ोटो अच्छे से क्लिक करके अच्छी फ़ोटो रहे"): [capture] = असली कैमरा फ़ोटो (ImageCapture -
     * पूरा resolution, फ़ोकस/रोशनी कैमरा ख़ुद ठीक करता है). पहले सिर्फ़ 720x960 का पहचान वाला फ़्रेम सेव होता था.
     * किसी फ़ोन पर तीनों साथ न चलें तो पहले जैसा (preview + पहचान) - [canSnap] = false.
     */
    private var shot: ImageCapture? = null
    val canSnap: Boolean get() = shot != null && !stopped
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
                val ic = if (capture) ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() else null
                if (ic != null) try {
                    p.bindToLifecycle(act, sel, preview, an, ic); useCases = arrayOf(preview, an, ic); shot = ic
                    return@addListener
                } catch (e: Exception) { DKLog.d("कैमरा: पूरी फ़ोटो वाला हिस्सा इस फ़ोन पर साथ नहीं चला (${e.message}) - पहचान वाले फ़्रेम से फ़ोटो"); p.unbindAll() }
                useCases = arrayOf(preview, an)
                p.bindToLifecycle(act, sel, preview, an)
            } catch (e: Exception) { onFail(e.message ?: "कैमरा नहीं खुला") }
        }, ContextCompat.getMainExecutor(act))
    }

    /** असली फ़ोटो (सीधी घुमाई हुई). null = नहीं खिंची. [done] कैमरे के अपने thread पर */
    fun snap(done: (Bitmap?) -> Unit) {
        val ic = shot ?: return done(null)
        try {
            ic.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(img: ImageProxy) {
                    val b = try {
                        var x = img.toBitmap()
                        val rot = img.imageInfo.rotationDegrees
                        if (rot != 0) x = Bitmap.createBitmap(x, 0, 0, x.width, x.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                        x
                    } catch (_: Throwable) { null } finally { img.close() }
                    done(b)
                }
                override fun onError(e: ImageCaptureException) { done(null) }
            })
        } catch (_: Throwable) { done(null) }
    }

    fun stop() {
        stopped = true
        try { provider?.unbind(*useCases) } catch (e: Exception) { Quiet.note(e) }
        exec.shutdown()
    }
}
