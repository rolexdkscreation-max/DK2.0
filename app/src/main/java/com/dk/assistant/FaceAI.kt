package com.dk.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * v3.91: DK की अपनी असली चेहरा पहचान - पूरी फ़ोन के अंदर (कैमरे की तस्वीर कहीं नहीं जाती).
 * - चेहरा ढूँढना: YuNet 2023mar (OpenCV Zoo, MIT, ~0.2 MB)
 * - पहचान: SFace 2021dec int8 (MobileFaceNet, OpenCV Zoo, Apache-2.0, ~9.9 MB, LFW पर 99.3%)
 * दोनों "चेहरा सिखाओ" पर एक बार डाउनलोड (SHA-256 जाँच), फिर filesDir/face में.
 * सीमा: यह फ़ोटो/वीडियो वाले धोखे (liveness) नहीं पकड़ता - फ़ोन के अपने लॉक जितना पक्का नहीं.
 */
object FaceAI {
    class Model(val file: String, val urls: List<String>, val sha256: String, val bytes: Long)

    val DETECTOR = Model("yunet_2023mar.onnx", listOf(
        "https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx",
        "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"),
        "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4", 232_589L)
    val RECOGNIZER = Model("sface_2021dec_int8.onnx", listOf(
        "https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec_int8.onnx",
        "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec_int8.onnx"),
        "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a", 9_896_933L)
    /** पुराने (रंग/पिक्सेल वाले) तरीके से बनी पहचान इससे नहीं मिलती - तो निशान रखो */
    const val TAG = "sface1"

    @Volatile private var det: OrtSession? = null
    @Volatile private var rec: OrtSession? = null
    @Volatile var broken: String? = null
        private set
    private val lock = Any()

    /** onnxruntime सिर्फ़ 64-bit ARM फ़ोनों के लिए APK में है (आवाज़-पहचान जैसा) */
    val supported: Boolean get() = com.dk.assistant.voice.OwnerVoice.supported

    private fun file(c: Context, m: Model) = File(File(c.filesDir, "face"), m.file)
    fun hasModels(c: Context) = listOf(DETECTOR, RECOGNIZER).all { file(c, it).let { f -> f.exists() && f.length() == it.bytes } }
    fun enrolled(c: Context) = Prefs.hasOwnerFace(c) && Prefs.ownerFaceModel(c) == TAG
    fun ready(c: Context) = supported && hasModels(c) && broken == null

    fun status(c: Context): String = when {
        !supported -> "यह फ़ोन 64-bit ARM नहीं है — चेहरा पहचान यहाँ नहीं चलेगी"
        broken != null -> "पहचान वाला मॉडल इस फ़ोन पर नहीं चला ($broken)"
        !hasModels(c) -> "मॉडल अभी नहीं है — \"मेरा चेहरा सिखाओ\" दबाते ही एक बार (~10 MB) डाउनलोड होगा"
        !enrolled(c) -> "अभी आपका चेहरा नहीं सीखा — \"मेरा चेहरा सिखाओ\" दबाएँ (करीब 10 सेकंड)"
        else -> "✓ आपका चेहरा सीखा हुआ है — DK लाइव का कैमरा खुलने पर पहचान दिखेगी"
    }

    private fun open(c: Context): Boolean {
        if (det != null && rec != null) return true
        synchronized(lock) {
            if (det != null && rec != null) return true
            if (!ready(c)) return false
            return try {
                val env = OrtEnvironment.getEnvironment()
                val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
                det = env.createSession(file(c, DETECTOR).absolutePath, o)
                rec = env.createSession(file(c, RECOGNIZER).absolutePath, o)
                DKLog.d("चेहरा पहचान मॉडल तैयार")
                true
            } catch (e: Throwable) {
                broken = e.javaClass.simpleName; det = null; rec = null
                DKLog.d("चेहरा पहचान मॉडल नहीं खुला (${e.javaClass.simpleName})")
                false
            }
        }
    }

    private fun floats(r: OrtSession.Result, name: String): FloatArray {
        val b = (r.get(name).get() as OnnxTensor).floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /** फ़्रेम में सबसे भरोसेमंद चेहरा (फ़्रेम के हिसाब से). null = नहीं मिला / मॉडल नहीं */
    fun detect(c: Context, bmp: Bitmap, minScore: Float = FaceMath.MIN_SCORE): FaceMath.Det? {
        if (!open(c)) return null
        val s = det ?: return null
        val sc = FaceMath.detScale(bmp.width, bmp.height)
        val box = Bitmap.createBitmap(FaceMath.DET, FaceMath.DET, Bitmap.Config.ARGB_8888)
        Canvas(box).apply { drawColor(Color.BLACK); drawBitmap(bmp, Matrix().apply { setScale(sc, sc) }, Paint(Paint.FILTER_BITMAP_FLAG)) }
        val px = IntArray(FaceMath.DET * FaceMath.DET); box.getPixels(px, 0, FaceMath.DET, 0, 0, FaceMath.DET, FaceMath.DET); box.recycle()
        return try {
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FaceMath.yunetInput(px)), longArrayOf(1, 3, FaceMath.DET.toLong(), FaceMath.DET.toLong())).use { t ->
                s.run(mapOf("input" to t)).use { r ->
                    val out = HashMap<Int, Array<FloatArray>>()
                    for (st in FaceMath.STRIDES) out[st] = arrayOf(floats(r, "cls_$st"), floats(r, "obj_$st"), floats(r, "bbox_$st"), floats(r, "kps_$st"))
                    FaceMath.decode(out, sc, minScore)
                }
            }
        } catch (e: Throwable) { broken = e.javaClass.simpleName; DKLog.d("चेहरा ढूँढने में गड़बड़ (${e.javaClass.simpleName})"); null }
    }

    /** चेहरे की 128 अंकों की पहचान (normalize) */
    fun embed(c: Context, bmp: Bitmap, d: FaceMath.Det): FloatArray? {
        if (!open(c)) return null
        val s = rec ?: return null
        val face = Bitmap.createBitmap(FaceMath.SIZE, FaceMath.SIZE, Bitmap.Config.ARGB_8888)
        Canvas(face).apply { drawColor(Color.BLACK); drawBitmap(bmp, Matrix().apply { setValues(FaceMath.similarity(d.kps)) }, Paint(Paint.FILTER_BITMAP_FLAG)) }
        val px = IntArray(FaceMath.SIZE * FaceMath.SIZE); face.getPixels(px, 0, FaceMath.SIZE, 0, 0, FaceMath.SIZE, FaceMath.SIZE); face.recycle()
        return try {
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FaceMath.sfaceInput(px)), longArrayOf(1, 3, FaceMath.SIZE.toLong(), FaceMath.SIZE.toLong())).use { t ->
                s.run(mapOf("data" to t)).use { r ->
                    val e = floats(r, "fc1")
                    if (e.size != FaceMath.DIM || e.any { !it.isFinite() }) null else FaceMatch.normalize(e)
                }
            }
        } catch (e: Throwable) { broken = e.javaClass.simpleName; DKLog.d("चेहरा पहचान में गड़बड़ (${e.javaClass.simpleName})"); null }
    }

    /** मालिक से कितना मिलता है (-1..1). null = सीखा नहीं / मॉडल नहीं */
    fun score(c: Context, e: FloatArray): Float? {
        if (!enrolled(c)) return null
        val p = Prefs.ownerFacePrint(c) ?: return null
        return FaceMatch.cosineSimilarity(p, e)
    }

    fun save(c: Context, print: FloatArray) { Prefs.setOwnerFacePrint(c, print); Prefs.edit(c) { putString("owner_face_model", TAG) } }
    fun forget(c: Context) { Prefs.setOwnerFacePrint(c, null); Prefs.edit(c) { remove("owner_face_model") } }

    /** दोनों मॉडल डाउनलोड (IO thread). @return null = ठीक, वरना हिंदी कारण */
    fun download(c: Context, progress: (Int) -> Unit): String? {
        if (hasModels(c)) return null
        val total = DETECTOR.bytes + RECOGNIZER.bytes
        var done = 0L
        for (m in listOf(DETECTOR, RECOGNIZER)) {
            val f = file(c, m)
            if (f.exists() && f.length() == m.bytes) { done += m.bytes; continue }
            var err: String? = "डाउनलोड नहीं हुआ"
            for (u in m.urls) {
                err = fetch(u, m, f) { got -> progress(((done + got) * 100 / total).toInt()) }
                if (err == null) break
            }
            if (err != null) return err
            done += m.bytes
        }
        broken = null
        return null
    }

    private fun fetch(url: String, m: Model, dst: File, prog: (Long) -> Unit): String? {
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, dst.name + ".part")
        return try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return "डाउनलोड नहीं हुआ (HTTP ${resp.code})"
                val body = resp.body ?: return "डाउनलोड खाली आया"
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                body.byteStream().use { inp ->
                    part.outputStream().use { out ->
                        val b = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(b); if (n < 0) break
                            out.write(b, 0, n); md.update(b, 0, n); got += n
                            if (got > m.bytes + 1024) { part.delete(); return "फ़ाइल उम्मीद से बड़ी - रोक दिया" }
                            prog(got)
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (got != m.bytes || hex != m.sha256) { part.delete(); return "फ़ाइल जाँच में ग़लत निकली - फिर से कोशिश करें" }
                if (!part.renameTo(dst)) { part.delete(); return "फ़ाइल सेव नहीं हुई" }
                null
            }
        } catch (e: Exception) { part.delete(); "इंटरनेट से नहीं आया (${e.javaClass.simpleName})" }
    }
}
