package com.dk.assistant.dkai

import kotlin.math.max
import kotlin.math.min

/**
 * 27 नवंबर 2026 (मालिक: "Settings में जो कैरेक्टर चुनूँ वह पूरी Home स्क्रीन पर फुल-स्क्रीन फोटो की तरह दिखे,
 * उसकी अपनी बैकग्राउंड ही पूरी स्क्रीन की बैकग्राउंड - और चेहरा कभी न कटे"):
 *
 * pure (JVM test): तस्वीर को स्क्रीन पर कहाँ और कितना बड़ा रखें.
 *  - शुरुआत "cover" से (पूरी स्क्रीन भरे, कोई खाली पट्टी नहीं).
 *  - [focus] (x, y; 0..1) स्क्रीन की ऊँचाई के ~36% पर ([FOCUS_Y], मालिक: 30-40%), बीच में.
 *  - [face] (बायाँ, ऊपर, दायाँ, नीचे; 0..1) हमेशा ऊपर की पट्टी (`top`) और नीचे की बार (`bottom`) के बीच, स्क्रीन के अंदर.
 *    cover में न बैठे = पहले थोड़ा बड़ा करके देखो; फिर भी न बैठे = scale घटाओ (चेहरा कभी न कटे) - बची जगह तस्वीर
 *    के अपने किनारे के रंग से भरती है ([FullBleedCharacterView], CLAMP), कोई खाली / अलग रंग की पट्टी नहीं.
 *  - टैबलेट: चेहरा स्क्रीन की ऊँचाई का कम से कम [minFaceK] (छोटा न दिखे), पर सुरक्षित जगह से बड़ा नहीं.
 */
object CharacterFrame {
    /** focus स्क्रीन की ऊँचाई के इतने हिस्से पर (मालिक: 30-40%) */
    const val FOCUS_Y = 0.36f
    /** टैबलेट पर चेहरा कम से कम स्क्रीन की ऊँचाई का इतना */
    const val TABLET_FACE_K = 0.26f
    /** focus / face न दिया हो (नया कैरेक्टर बिना जानकारी) - बीच में, ऊपर की ओर (portrait फोटो का आम चेहरा) */
    val DEFAULT_FOCUS: List<Float> = listOf(0.5f, 0.36f)
    val DEFAULT_FACE: List<Float> = listOf(0.3f, 0.16f, 0.7f, 0.58f)

    /** [scale] = तस्वीर का गुणक, [dx]/[dy] = स्क्रीन पर तस्वीर का बायाँ-ऊपरी कोना; [covers] = पूरी स्क्रीन बिना किनारा-भराई के भरी */
    data class Place(val scale: Float, val dx: Float, val dy: Float, val covers: Boolean, val faceFits: Boolean) {
        /** स्क्रीन पर चेहरे का आयत (l, t, r, b) */
        fun faceOn(iw: Float, ih: Float, face: List<Float>): FloatArray = floatArrayOf(
            dx + face[0] * iw * scale, dy + face[1] * ih * scale, dx + face[2] * iw * scale, dy + face[3] * ih * scale)
        fun focusOn(iw: Float, ih: Float, focus: List<Float>): FloatArray = floatArrayOf(dx + focus[0] * iw * scale, dy + focus[1] * ih * scale)
    }

    /** index.json वाला focus सही? (2 मान, 0..1) */
    fun okFocus(l: List<Float>?): List<Float>? = l?.takeIf { it.size == 2 && it.all { v -> v in 0f..1f } }

    /** index.json वाला face सही? (4 मान, 0..1, बायाँ < दायाँ, ऊपर < नीचे) */
    fun okFace(l: List<Float>?): List<Float>? = l?.takeIf { it.size == 4 && it.all { v -> v in 0f..1f } && it[0] < it[2] && it[1] < it[3] }

    /** focus न हो = face के बीच (x) और ऊपर से 40% (आँखें); दोनों न हों = [DEFAULT_FOCUS] */
    fun focusOf(focus: List<Float>?, face: List<Float>?): List<Float> = okFocus(focus)
        ?: okFace(face)?.let { listOf((it[0] + it[2]) / 2f, it[1] + (it[3] - it[1]) * 0.4f) } ?: DEFAULT_FOCUS

    /** face न हो = focus के चारों ओर आम चेहरे जितना; दोनों न हों = [DEFAULT_FACE] */
    fun faceOf(focus: List<Float>?, face: List<Float>?): List<Float> = okFace(face)
        ?: okFocus(focus)?.let { f -> listOf((f[0] - 0.2f).coerceAtLeast(0f), (f[1] - 0.2f).coerceAtLeast(0f), (f[0] + 0.2f).coerceAtMost(1f), (f[1] + 0.22f).coerceAtMost(1f)) }
        ?: DEFAULT_FACE

    /**
     * [w]x[h] स्क्रीन, [iw]x[ih] तस्वीर. [top] = ऊपर की पट्टी (status bar + हेडर + READY) की ऊँचाई, [bottom] = नीचे की बार
     * (+ nav bar) की ऊँचाई. चेहरा इन दोनों के बीच.
     */
    fun place(w: Float, h: Float, iw: Float, ih: Float, focus: List<Float>, face: List<Float>,
              top: Float, bottom: Float, minFaceK: Float = 0f): Place {
        if (w <= 0f || h <= 0f || iw <= 0f || ih <= 0f) return Place(1f, 0f, 0f, false, false)
        val fc = focusOf(focus, face); val fr = faceOf(focus, face)
        val cover = max(w / iw, h / ih)
        val fw = max(1f, (fr[2] - fr[0]) * iw); val fh = max(1f, (fr[3] - fr[1]) * ih)
        val room = max(1f, h - top - bottom)
        val fit = min(room / fh, w / fw)                   // इससे बड़ा = चेहरा ऊपर/नीचे की पट्टी या किनारे से कटे
        var s = cover
        if (minFaceK > 0f && fh * s < minFaceK * h) s = max(s, min(fit, minFaceK * h / fh))
        if (s > fit) s = fit                               // cover में भी चेहरा बड़ा = छोटा करो (चेहरा पहले)
        // cover वाली तस्वीर में चेहरा पट्टियों के बीच न आए = थोड़ा-थोड़ा बड़ा करके देखो (ज़्यादा से ज़्यादा fit तक).
        // चेहरा बैठ गया पर focus 30-40% से दूर (जैसे phone पर चौकोर तस्वीर - ऊपर-नीचे खिसकने की जगह ही नहीं) =
        // [ZOOM_MAX] गुना तक थोड़ा और बड़ा करके focus को उसकी जगह के पास लाओ; न आए = पहली सही जगह
        var z = s
        var best: Place? = null
        val zFocus = s * ZOOM_MAX
        while (true) {
            val p = at(w, h, iw, ih, fc, fr, top, bottom, z, clamp = true)
            if (p.covers && p.faceFits) {
                if (best == null) best = p
                if (focusOk(p.dy + fc[1] * ih * p.scale, h)) return p
            }
            if (best != null && z >= zFocus) return best
            if (z >= fit - 1e-4f) break
            z = min(fit, z * 1.03f)
        }
        // कोई cover वाली जगह नहीं जहाँ पूरा चेहरा दिखे = चेहरा पहले (बची जगह किनारे के रंग से)
        return best ?: at(w, h, iw, ih, fc, fr, top, bottom, s, clamp = false)
    }

    /** focus स्क्रीन की 30-40% ऊँचाई के पास? (थोड़ी छूट) */
    fun focusOk(y: Float, h: Float): Boolean = y >= h * (FOCUS_Y - 0.08f) && y <= h * (FOCUS_Y + 0.08f)

    /** focus पास लाने के लिए cover से ज़्यादा से ज़्यादा इतना बड़ा */
    const val ZOOM_MAX = 1.3f

    private fun at(w: Float, h: Float, iw: Float, ih: Float, fc: List<Float>, fr: List<Float>,
                   top: Float, bottom: Float, s: Float, clamp: Boolean): Place {
        val bw = iw * s; val bh = ih * s
        var dx = w / 2f - fc[0] * bw
        var dy = h * FOCUS_Y - fc[1] * bh
        // चेहरा ऊपर-नीचे की पट्टियों के बीच (ऊपर वाला किनारा पहले - सिर / माथा कभी न ढके)
        val lo = top; val hi = h - bottom
        if (dy + fr[3] * bh > hi) dy -= dy + fr[3] * bh - hi
        if (dy + fr[1] * bh < lo) dy += lo - (dy + fr[1] * bh)
        if (dx + fr[2] * bw > w) dx -= dx + fr[2] * bw - w
        if (dx + fr[0] * bw < 0f) dx -= dx + fr[0] * bw
        if (clamp) {
            if (bw >= w) dx = dx.coerceIn(w - bw, 0f)
            if (bh >= h) dy = dy.coerceIn(h - bh, 0f)
        }
        val e = 0.5f
        val covers = dx <= e && dy <= e && dx + bw >= w - e && dy + bh >= h - e
        val fl = dx + fr[0] * bw; val ft = dy + fr[1] * bh; val frr = dx + fr[2] * bw; val fb = dy + fr[3] * bh
        val fits = fl >= -e && frr <= w + e && ft >= lo - e && fb <= hi + e
        return Place(s, dx, dy, covers, fits)
    }
}
