package com.dk.assistant

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * v3.91: असली चेहरा पहचान का गणित (pure - Android नहीं, unit test होता है).
 * चेहरा ढूँढना = YuNet (OpenCV Zoo, MIT), पहचान = SFace (MobileFaceNet, OpenCV Zoo, Apache-2.0) - दोनों फ़ोन के अंदर ONNX से.
 * इनकी तैयारी (letterbox, 5 बिंदुओं से 112x112 सीधा चेहरा, RGB 0-255) OpenCV के FaceDetectorYN/FaceRecognizerSF जैसी ही.
 */
object FaceMath {
    /** YuNet का इनपुट (640x640, BGR, 0-255) */
    const val DET = 640
    /** SFace का इनपुट (112x112, RGB, 0-255) -> 128 अंक */
    const val SIZE = 112
    const val DIM = 128
    val STRIDES = intArrayOf(8, 16, 32)
    /** 112x112 में आँखें/नाक/होंठ के कोने कहाँ होने चाहिए (ArcFace/SFace वाला ढाँचा) */
    val REF = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    /** SFace cosine: OpenCV की सलाह 0.363 = वही आदमी. हम थोड़ा सख़्त: 0.45 से ऊपर = मालिक */
    const val MATCH = 0.45f
    /** सख़्त मोड */
    const val STRICT = 0.55f
    /** सिखाते समय: चेहरा अच्छा दिखे (YuNet score) और काफ़ी बड़ा हो */
    const val MIN_SCORE = 0.80f
    const val ENROLL_SCORE = 0.88f
    const val ENROLL_MIN_FACE = 0.18f
    const val ENROLL_FRAMES = 8
    /** सिखाते समय हर नया फ़्रेम पिछलों से इतना मिले - बीच में कोई और न आ जाए */
    const val ENROLL_SAME = 0.50f

    class Det(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val kps: FloatArray)

    /** फ़्रेम को 640 में फिट करने का पैमाना (ऊपर-बाएँ रखो, बाकी काला) */
    fun detScale(w: Int, h: Int): Float = DET.toFloat() / maxOf(w, h, 1)

    /**
     * YuNet के 12 आउटपुट (हर stride के cls/obj/bbox/kps) से सबसे भरोसेमंद एक चेहरा, असली फ़्रेम के हिसाब से.
     * out[stride] = (cls, obj, bbox, kps)
     */
    fun decode(out: Map<Int, Array<FloatArray>>, scale: Float, minScore: Float = MIN_SCORE): Det? {
        var best: Det? = null
        for (s in STRIDES) {
            val o = out[s] ?: continue
            val cls = o[0]; val obj = o[1]; val bb = o[2]; val kp = o[3]
            val cols = DET / s
            for (i in cls.indices) {
                val sc = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                if (sc < minScore || (best != null && sc <= best.score)) continue
                val r = i / cols; val c = i % cols
                val cx = (c + bb[i * 4]) * s; val cy = (r + bb[i * 4 + 1]) * s
                val w = exp(bb[i * 4 + 2]) * s; val h = exp(bb[i * 4 + 3]) * s
                val k = FloatArray(10) { j -> ((kp[i * 10 + j] + if (j % 2 == 0) c else r) * s) / scale }
                best = Det(sc, (cx - w / 2) / scale, (cy - h / 2) / scale, w / scale, h / scale, k)
            }
        }
        return best
    }

    /**
     * 5 बिंदुओं (src) को REF पर लाने वाला similarity transform (घुमाव+पैमाना+खिसकाव, उल्टा नहीं), least squares.
     * @return android Matrix.setValues वाले 9 अंक [a,-b,tx, b,a,ty, 0,0,1]
     */
    fun similarity(src: FloatArray, dst: FloatArray = REF): FloatArray {
        val n = src.size / 2
        var mx = 0.0; var my = 0.0; var ux = 0.0; var uy = 0.0
        for (i in 0 until n) { mx += src[2 * i]; my += src[2 * i + 1]; ux += dst[2 * i]; uy += dst[2 * i + 1] }
        mx /= n; my /= n; ux /= n; uy /= n
        var sxx = 0.0; var pa = 0.0; var pb = 0.0
        for (i in 0 until n) {
            val x = src[2 * i] - mx; val y = src[2 * i + 1] - my
            val u = dst[2 * i] - ux; val v = dst[2 * i + 1] - uy
            sxx += x * x + y * y; pa += x * u + y * v; pb += x * v - y * u
        }
        if (sxx < 1e-9) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val a = pa / sxx; val b = pb / sxx
        val tx = ux - (a * mx - b * my); val ty = uy - (b * mx + a * my)
        return floatArrayOf(a.toFloat(), (-b).toFloat(), tx.toFloat(), b.toFloat(), a.toFloat(), ty.toFloat(), 0f, 0f, 1f)
    }

    /** ARGB pixels (112x112) -> SFace इनपुट [1,3,112,112] RGB 0-255 */
    fun sfaceInput(px: IntArray): FloatArray {
        val n = SIZE * SIZE; val f = FloatArray(3 * n)
        for (i in 0 until n) { val p = px[i]; f[i] = ((p shr 16) and 255).toFloat(); f[n + i] = ((p shr 8) and 255).toFloat(); f[2 * n + i] = (p and 255).toFloat() }
        return f
    }

    /** ARGB pixels (640x640) -> YuNet इनपुट [1,3,640,640] BGR 0-255 */
    fun yunetInput(px: IntArray): FloatArray {
        val n = DET * DET; val f = FloatArray(3 * n)
        for (i in 0 until n) { val p = px[i]; f[i] = (p and 255).toFloat(); f[n + i] = ((p shr 8) and 255).toFloat(); f[2 * n + i] = ((p shr 16) and 255).toFloat() }
        return f
    }

    /** सिखाने के लिए यह फ़्रेम अच्छा है? null = हाँ, वरना हिंदी में क्या ठीक करें */
    fun enrollProblem(d: Det?, frameW: Int): String? = when {
        d == null -> "चेहरा नहीं दिखा — फ़ोन सामने रखें, रोशनी में आएँ"
        d.score < ENROLL_SCORE -> "चेहरा साफ़ नहीं — सीधे कैमरे में देखें"
        d.w < frameW * ENROLL_MIN_FACE -> "थोड़ा पास आएँ"
        else -> null
    }

    /**
     * फ़्रेम (कैमरा bitmap) का चेहरा-डिब्बा -> स्क्रीन पर (PreviewView FILL_CENTER: भरकर बीच से कटा; आगे वाला कैमरा = शीशे जैसा उल्टा)
     * @return [left, top, right, bottom]
     */
    fun toView(x: Float, y: Float, w: Float, h: Float, fw: Int, fh: Int, vw: Int, vh: Int, mirror: Boolean): FloatArray {
        val sc = maxOf(vw.toFloat() / fw, vh.toFloat() / fh)
        val ox = (vw - fw * sc) / 2f; val oy = (vh - fh * sc) / 2f
        var l = ox + x * sc; var r = ox + (x + w) * sc
        if (mirror) { val nl = vw - r; r = vw - l; l = nl }
        return floatArrayOf(l, oy + y * sc, r, oy + (y + h) * sc)
    }

    /** मिलान का फ़ैसला */
    fun decide(score: Float, strict: Boolean = false): MatchResult =
        if (score >= (if (strict) STRICT else MATCH)) MatchResult.ACCESS_GRANTED else MatchResult.ACCESS_DENIED
}
