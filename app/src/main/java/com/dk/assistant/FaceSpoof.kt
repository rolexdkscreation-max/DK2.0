package com.dk.assistant

import kotlin.math.exp

/**
 * 15 नवंबर 2026 (मालिक): "कोई भी फ़ोटो या वीडियो दिखाए, फिर भी उससे न खुले - पता चलना चाहिए कि आगे असली इंसान है".
 *
 * पहले सिर्फ़ [FaceLiveness] (सिर घुमाकर 3D जाँच) थी - सपाट फ़ोटो रुकती थी, पर मालिक का सिर घुमाता हुआ *वीडियो* (दूसरे फ़ोन की
 * स्क्रीन पर) पास हो सकता था. अब दो और ताले:
 *  1. AI anti-spoof मॉडल - MiniFASNetV2 (2.7x) + MiniFASNetV1SE (4.0x), Silent-Face-Anti-Spoofing (minivision-ai, Apache-2.0),
 *     ONNX: github.com/QingHeYang/Silent-Face-Anti-Spoofing-onnx. हर एक ~1.7 MB, पहली बार इंटरनेट से (SHA-256 जाँच).
 *     चेहरे के आस-पास का बड़ा हिस्सा देखकर बताता है: असली त्वचा / काग़ज़ की फ़ोटो / स्क्रीन (moiré, चमक, किनारे).
 *     दोनों मॉडल का औसत (ensemble), और कई फ़्रेम का औसत ([Vote]) - एक फ़्रेम की गलती से फ़ैसला नहीं.
 *  2. हर बार नया (random) क्रम - पहले बाएँ या पहले दाएँ ([FaceLiveness.challenge]) - पहले से रिकॉर्ड वीडियो क्रम नहीं जानता.
 *
 * यहाँ सिर्फ़ pure गणित (JVM test). मॉडल चलाना [FaceAI.realness] में.
 * जाँचा (Python, onnxruntime): इनपुट BGR 0-255 (÷255 नहीं), [1,3,80,80]; आउटपुट 3 वर्ग - 0 काग़ज़, 1 असली, 2 स्क्रीन.
 */
object FaceSpoof {
    const val INPUT = 80
    /** आउटपुट में असली चेहरे का खाना */
    const val REAL = 1

    /** इतना औसत (0-1) = असली इंसान */
    const val REAL_MIN = 0.55f
    /** इतने से नीचे औसत = फ़ोटो / स्क्रीन (बीच वाला = अभी पक्का नहीं, और देखो) */
    const val FAKE_MAX = 0.30f
    /** फ़ैसले से पहले कम से कम इतने फ़्रेम */
    const val MIN_FRAMES = 3
    const val WINDOW = 6
    const val SURE_FRAMES = 5
    const val SURE_MEAN = 0.65f
    const val SURE_FLOOR = 0.30f

    /**
     * Silent-Face का बड़ा डिब्बा: चेहरे के बीच से [scale] गुना, फ़्रेम के अंदर खिसकाकर. @return [left, top, right, bottom] (शामिल)
     */
    fun cropBox(srcW: Int, srcH: Int, x: Float, y: Float, w: Float, h: Float, scale: Float): IntArray {
        if (w <= 0f || h <= 0f) return intArrayOf(0, 0, maxOf(srcW - 1, 0), maxOf(srcH - 1, 0))
        val sc = minOf((srcH - 1) / h, minOf((srcW - 1) / w, scale))
        val nw = w * sc; val nh = h * sc
        val cx = w / 2f + x; val cy = h / 2f + y
        var l = cx - nw / 2f; var t = cy - nh / 2f; var r = cx + nw / 2f; var b = cy + nh / 2f
        if (l < 0) { r -= l; l = 0f }
        if (t < 0) { b -= t; t = 0f }
        if (r > srcW - 1) { l -= r - srcW + 1; r = (srcW - 1).toFloat() }
        if (b > srcH - 1) { t -= b - srcH + 1; b = (srcH - 1).toFloat() }
        return intArrayOf(l.toInt().coerceAtLeast(0), t.toInt().coerceAtLeast(0), r.toInt(), b.toInt())
    }

    /** ARGB pixels (80x80) -> [1,3,80,80] BGR 0-255 */
    fun input(px: IntArray): FloatArray {
        val n = INPUT * INPUT; val f = FloatArray(3 * n)
        for (i in 0 until n) { val p = px[i]; f[i] = (p and 255).toFloat(); f[n + i] = ((p shr 8) and 255).toFloat(); f[2 * n + i] = ((p shr 16) and 255).toFloat() }
        return f
    }

    fun softmax(o: FloatArray): FloatArray {
        if (o.isEmpty()) return o
        val m = o.max(); val e = FloatArray(o.size) { exp((o[it] - m).toDouble()).toFloat() }
        val s = e.sum(); return FloatArray(o.size) { e[it] / s }
    }

    /** कई मॉडल के आउटपुट (logits) -> असली होने का भरोसा 0-1 (softmax का औसत) */
    fun realScore(outs: List<FloatArray>): Float? {
        val ok = outs.filter { it.size == 3 && it.all { v -> v.isFinite() } }
        if (ok.isEmpty()) return null
        return ok.map { softmax(it)[REAL] }.average().toFloat()
    }

    /** कई फ़्रेम का औसत - एक फ़्रेम की गलती से न खुले, न अटके */
    class Vote(private val window: Int = WINDOW) {
        private val q = ArrayDeque<Float>()
        fun add(p: Float) { q.addLast(p.coerceIn(0f, 1f)); while (q.size > window) q.removeFirst() }
        fun reset() = q.clear()
        fun count() = q.size
        fun mean(): Float = if (q.isEmpty()) 0f else q.sum() / q.size
        fun real(): Boolean = q.size >= MIN_FRAMES && mean() >= REAL_MIN
        fun fake(): Boolean = q.size >= MIN_FRAMES && mean() <= FAKE_MAX
        /**
         * 15 नवंबर 2026 (मालिक: "Boss का चेहरा आए तो पूरा पक्का करे - असली है या फ़ोटो / वीडियो - फिर authorize"):
         * खोलने से पहले सख़्त: कम से कम [SURE_FRAMES] फ़्रेम, औसत [SURE_MEAN] से ऊपर, और कोई भी फ़्रेम [SURE_FLOOR] से नीचे नहीं
         * (स्क्रीन पर चलता वीडियो बीच-बीच में "नकली" दिखता है - एक भी ऐसा फ़्रेम = अभी नहीं).
         */
        fun sure(): Boolean = q.size >= SURE_FRAMES && mean() >= SURE_MEAN && q.min() >= SURE_FLOOR
    }
}
