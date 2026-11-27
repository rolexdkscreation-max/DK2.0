package com.dk.assistant

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 22 नवंबर 2026 (मालिक: "मैंने वीडियो रिकॉर्ड किया - गर्दन इधर-उधर, सामने देखना - और वही वीडियो दिखाया तो टच मोड और ऐप लॉक
 * दोनों ने ऑथराइज़ कर दिया. पहचान हो कि आगे सच में असली इंसान है या वीडियो").
 *
 * जड़: सिर घुमाना ([FaceLiveness]) वीडियो में भी होता है - "पहले बाएँ या पहले दाएँ" वाला random क्रम उस वीडियो को नहीं रोकता जिसमें
 * सिर बार-बार दोनों तरफ़ घूमता है. और AI anti-spoof ([FaceSpoof], 80x80 का छोटा मॉडल) आजकल की साफ़ OLED स्क्रीन पर धोखा खा जाता है.
 *
 * नया ताला - स्क्रीन की रंगीन रोशनी (flash challenge), फ़िज़िक्स पर, किसी मॉडल पर नहीं:
 *  - ताले की स्क्रीन पूरी चमक पर [SLOTS] बार random क्रम में लाल / नीली होती है (हर बार नया क्रम, [SLOT_MS] ms हर रंग).
 *  - असली चेहरा कैमरे के पास है - उस पर स्क्रीन का रंग पड़ता है (लाल पर चेहरा लाल-सा, नीले पर नीला-सा), पीछे की दीवार दूर है तो उस पर कम.
 *  - वीडियो / फ़ोटो दूसरी स्क्रीन या काग़ज़ पर है: उसमें चेहरा पहले से रिकॉर्ड है, इस बार का random रंग-क्रम उसमें हो ही नहीं सकता.
 *    शीशे पर पड़ी चमक चेहरे और उसके आस-पास (उसी स्क्रीन / काग़ज़) पर बराबर पड़ती है - तो "चेहरा - आस-पास" ([sample]) में कुछ नहीं बदलता.
 *  - पास तभी: हर लाल वाला हिस्सा हर नीले वाले से ऊपर (क्रम पूरा मिला) और फ़र्क़ [MIN_DIFF] / [MIN_FACE] से ज़्यादा.
 *    कैमरे की देर (~0-[LAG_MAX] ms) के लिए हर देर पर जाँच, सबसे अच्छी चुनी.
 *  - बीच में चेहरा गायब / कूदा / आकार बदला ([track]) = फ़ेल.
 * बहुत तेज़ धूप में स्क्रीन की रोशनी चेहरे पर नहीं दिखती - तब पास नहीं (PIN / फ़ोन लॉक से खोलें). Pure - JVM test.
 *
 * 27 नवंबर 2026 (मालिक: "आउटडोर में धूप रहते रंग वाली जाँच नहीं होती - एक्यूरेट करो, सिर्फ़ असली इंसान से खुले, फ़ोटो / वीडियो से नहीं"):
 *  - जड़: धूप में चेहरे पर रंग का असर बहुत छोटा (~0.5-1%) और कैमरे के शोर में एक-दो हिस्से ऊपर-नीचे हो जाते हैं - तो
 *    "हर लाल हर नीले से ऊपर" वाली सख़्त शर्त असली चेहरे पर भी टूटती थी. ऊपर से कैमरा ख़ुद रंग / चमक बराबर कर देता था (AWB / AE).
 *  - अब: [SLOTS] 6 → 10 हिस्से (ज़्यादा सबूत) और दूसरा रास्ता - आँकड़ों वाली जाँच ([tStat], Welch / pooled t): लाल और नीले
 *    हिस्सों का फ़र्क़ शोर से [T_MIN] गुना बड़ा हो, चेहरे पर भी और "चेहरा - आस-पास" पर भी, और कम से कम [WEAK_DIFF] / [WEAK_FACE].
 *    वीडियो / फ़ोटो में "चेहरा - आस-पास" का असली फ़र्क़ शून्य - शोर से 6 गुना होना लगभग नामुमकिन (simulation: 1500 में 0).
 *  - रंग के दौरान कैमरे का AWB / AE ताला ([FaceCam.lockLight]) - रंग का असर कैमरा मिटा न दे.
 *  - फिर भी नतीजा न निकले और सच में धूप हो ([QuickFaceGate]) = धूप वाली random सिर-घुमाना जाँच ([SunTurns]), फ़ेल नहीं.
 */
class FlashLive(val seq: IntArray = randomSeq(), private val slotMs: Long = SLOT_MS) {
    /**
     * [tDiff] / [tFace] = लाल-नीले फ़र्क़ शोर से कितने गुना (आँकड़ों वाली जाँच). [luma] = रंग के दौरान चेहरे की औसत चमक (0-1, NaN = पता नहीं).
     * उल्टा क्रम (पिछली बार का रिकॉर्ड) = [tDiff] बहुत नीचे (ऋण).
     */
    data class Result(val live: Boolean, val diff: Float, val face: Float, val lag: Long, val inOrder: Boolean, val samples: Int,
                      val tDiff: Float = 0f, val tFace: Float = 0f, val luma: Float = Float.NaN, val worstT: Float = 0f) {
        /**
         * रंग का साफ़ जवाब है पर इस बार के क्रम से उल्टा (किसी देर पर t ≤ -[T_MIN], और किसी पर भी सही-दिशा में पक्का नहीं) =
         * वीडियो में पिछली बार की चमक. धूप में तो रंग दिखता ही नहीं - तब धूप वाली दूसरी जाँच भी नहीं.
         */
        val against get() = worstT <= -T_MIN && maxOf(tDiff, tFace) < T_MIN
        fun log() = "flash: live=$live diff=${"%.3f".format(diff)} face=${"%.3f".format(face)} t=${"%.1f".format(tDiff)}/${"%.1f".format(tFace)} " +
            "lag=$lag क्रम=$inOrder नमूने=$samples चमक=${if (luma.isNaN()) "-" else "%.2f".format(luma)}"
    }

    private data class S(val t: Long, val face: Float, val ring: Float, val luma: Float = Float.NaN)
    private val samples = ArrayList<S>()
    @Volatile var startAt = 0L
        private set
    @Volatile var broken: String? = null
        private set
    private var lastCx = 0f; private var lastCy = 0f; private var lastW = 0f; private var lastSeen = 0L

    fun start(now: Long) { synchronized(samples) { samples.clear() }; startAt = now; broken = null; lastW = 0f; lastSeen = now }
    val started get() = startAt > 0L

    /** अभी स्क्रीन का रंग: +1 लाल, -1 नीला, 0 = (अब) नहीं */
    fun color(now: Long): Int {
        if (startAt == 0L || now < startAt) return 0
        val i = ((now - startAt) / slotMs).toInt()
        return if (i in seq.indices) seq[i] else 0
    }

    /** रंग वाला हिस्सा खत्म + कैमरे की देर का समय भी बीत गया */
    fun done(now: Long) = startAt > 0L && now >= startAt + seq.size * slotMs + LAG_MAX + SETTLE_MS

    /** रंग चल रहा है (स्क्रीन रंगीन दिखाओ) */
    fun showing(now: Long) = color(now) != 0

    /**
     * हर कैमरा फ़्रेम: चेहरे के बीच का और उसके आस-पास के घेरे का रंग-नाप ([chroma]).
     */
    fun sample(t: Long, face: Float, ring: Float, luma: Float = Float.NaN) {
        if (startAt == 0L || !face.isFinite() || !ring.isFinite()) return
        synchronized(samples) { samples.add(S(t, face, ring, luma)) }
    }

    /** वही लगातार चेहरा? null = नहीं दिखा. false = टूटा (फ़ेल) */
    fun track(now: Long, faces: Int, cx: Float, cy: Float, w: Float): Boolean {
        if (broken != null) return false
        if (!started) return true
        if (faces > 1) { broken = "एक से ज़्यादा चेहरे"; return false }
        if (faces == 0) {
            if (now - lastSeen > GAP_MS) broken = "चेहरा हट गया"
            return broken == null
        }
        if (lastW > 0f) {
            val jump = hypot(cx - lastCx, cy - lastCy) > FaceLiveness.JUMP * lastW
            val resize = w / lastW > FaceLiveness.RESIZE || lastW / w > FaceLiveness.RESIZE
            if (jump || resize) { broken = "चेहरा बदला / कूदा"; return false }
        }
        lastCx = cx; lastCy = cy; lastW = w; lastSeen = now
        return true
    }

    fun result(): Result {
        val all = synchronized(samples) { samples.toList() }
        var best: Result? = null
        var worst = 0f
        var lag = 0L
        while (lag <= LAG_MAX) {
            val r = at(all, lag)
            if (r != null && (best == null || score(r) > score(best))) best = r
            if (r != null) worst = minOf(worst, r.tDiff, r.tFace)
            lag += LAG_STEP
        }
        return best?.copy(worstT = worst) ?: Result(false, 0f, 0f, 0L, false, all.size)
    }

    private fun score(r: Result) = (if (r.live) 10f else 0f) + (if (r.inOrder) 1f else 0f) + r.diff

    private fun at(all: List<S>, lag: Long): Result? {
        val diffSlot = FloatArray(seq.size) { Float.NaN }; val faceSlot = FloatArray(seq.size) { Float.NaN }
        for (i in seq.indices) {
            val a = startAt + i * slotMs + lag + SETTLE_MS; val b = startAt + (i + 1) * slotMs + lag
            val inSlot = all.filter { it.t in a until b }
            if (inSlot.isEmpty()) continue
            diffSlot[i] = inSlot.map { it.face - it.ring }.average().toFloat()
            faceSlot[i] = inSlot.map { it.face }.average().toFloat()
        }
        val red = seq.indices.filter { seq[it] > 0 && !diffSlot[it].isNaN() }
        val blue = seq.indices.filter { seq[it] < 0 && !diffSlot[it].isNaN() }
        if (red.size < MIN_PER_COLOR || blue.size < MIN_PER_COLOR || red.size + blue.size < seq.size - 1) return null
        val diff = red.map { diffSlot[it] }.average().toFloat() - blue.map { diffSlot[it] }.average().toFloat()
        val face = red.map { faceSlot[it] }.average().toFloat() - blue.map { faceSlot[it] }.average().toFloat()
        val inOrder = red.minOf { diffSlot[it] } > blue.maxOf { diffSlot[it] } && red.minOf { faceSlot[it] } > blue.maxOf { faceSlot[it] }
        val tD = tStat(red.map { diffSlot[it] }, blue.map { diffSlot[it] })
        val tF = tStat(red.map { faceSlot[it] }, blue.map { faceSlot[it] })
        // पुराना सख़्त रास्ता (घर के अंदर - बड़ा, साफ़ असर) या आँकड़ों वाला (धूप / कम असर पर भी पक्का, शोर से बहुत बड़ा)
        val strict = inOrder && diff >= MIN_DIFF && face >= MIN_FACE
        val stat = tD >= T_MIN && tF >= T_MIN && diff >= WEAK_DIFF && face >= WEAK_FACE
        val lumas = all.mapNotNull { s -> s.luma.takeIf { it.isFinite() } }
        return Result(strict || stat, diff, face, lag, inOrder, red.size + blue.size, tD, tF, if (lumas.isEmpty()) Float.NaN else lumas.average().toFloat())
    }

    companion object {
        /** 27 नवंबर 2026: 6 → 10 (धूप में छोटे असर के लिए ज़्यादा सबूत), हर रंग 240 → 200 ms - कुल ~2 s */
        const val SLOTS = 10
        const val SLOT_MS = 200L
        /** रंग बदलने के बाद इतना छोड़ो (स्क्रीन + कैमरा के बीच का धुंधला पल) */
        const val SETTLE_MS = 60L
        const val LAG_MAX = 330L
        const val LAG_STEP = 30L
        const val GAP_MS = 700L
        const val MIN_PER_COLOR = 2
        /** चेहरा - आस-पास: लाल और नीले में कम से कम इतना फ़र्क़ */
        const val MIN_DIFF = 0.015f
        /** चेहरे पर ख़ुद कम से कम इतना फ़र्क़ (स्क्रीन की रोशनी सच में पड़ी) */
        const val MIN_FACE = 0.02f
        /** आँकड़ों वाला रास्ता: लाल-नीला फ़र्क़ शोर से कम से कम इतने गुना (t). शोर अकेले से ~1/5000 */
        const val T_MIN = 6f
        /** आँकड़ों वाला रास्ता: फिर भी कम से कम इतना असली फ़र्क़ ("चेहरा - आस-पास" और चेहरे पर) */
        const val WEAK_DIFF = 0.005f
        const val WEAK_FACE = 0.006f
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        /** रंग इतनी तेज़ी से (कैमरे के फ़्रेम) */
        const val CAM_GAP_MS = 50L

        fun screen(c: Int) = if (c > 0) RED else BLUE

        /** हर बार नया: बराबर लाल-नीले, उलट-पुलट (पहले से बना वीडियो नहीं जानता) */
        fun randomSeq(rnd: kotlin.random.Random = kotlin.random.Random.Default): IntArray {
            while (true) {
                val s = (IntArray(SLOTS / 2) { 1 } + IntArray(SLOTS / 2) { -1 }).also { it.shuffle(rnd) }
                // सीधा बारी-बारी (लाल-नीला-लाल...) बहुत आसान - दोबारा
                if (s.toList().zipWithNext().any { (a, b) -> a == b }) return s
            }
        }

        /**
         * लाल वाले और नीले वाले हिस्सों का फ़र्क़ शोर से कितने गुना (pooled two-sample t). कम हिस्से = 0.
         * शोर का नीचे का हद [NOISE_FLOOR] (बिल्कुल सपाट नाप पर अनंत न हो).
         */
        fun tStat(red: List<Float>, blue: List<Float>): Float {
            if (red.size < 2 || blue.size < 2) return 0f
            val mr = red.average(); val mb = blue.average()
            val ss = red.sumOf { (it - mr) * (it - mr) } + blue.sumOf { (it - mb) * (it - mb) }
            val sp = kotlin.math.sqrt(ss / (red.size + blue.size - 2)).coerceAtLeast(NOISE_FLOOR.toDouble())
            return ((mr - mb) / (sp * kotlin.math.sqrt(1.0 / red.size + 1.0 / blue.size))).toFloat()
        }
        const val NOISE_FLOOR = 0.0005f

        /** (R - B) / (R + G + B) - लाल रोशनी पर ऊपर, नीली पर नीचे. 0 = नाप नहीं */
        fun chroma(r: Long, g: Long, b: Long): Float { val s = r + g + b; return if (s <= 0L) 0f else (r - b).toFloat() / s }

        /**
         * ARGB pixels में से: चेहरे के बीच का हिस्सा (डिब्बे का बीच [INNER]) और आस-पास का घेरा ([RING_IN]..[RING_OUT] गुना डिब्बा).
         * @return [face, ring] chroma + [2] चेहरे की चमक (0-1), या null (बहुत कम pixel)
         */
        fun measure(px: IntArray, w: Int, h: Int, x: Float, y: Float, bw: Float, bh: Float, step: Int = 4): FloatArray? {
            if (w <= 0 || h <= 0 || bw <= 0f || bh <= 0f) return null
            val cx = x + bw / 2f; val cy = y + bh / 2f
            var fr = 0L; var fg = 0L; var fb = 0L; var fn = 0
            var rr = 0L; var rg = 0L; var rb = 0L; var rn = 0
            val ox = bw * RING_OUT / 2f; val oy = bh * RING_OUT / 2f
            val x0 = (cx - ox).toInt().coerceAtLeast(0); val x1 = (cx + ox).toInt().coerceAtMost(w - 1)
            val y0 = (cy - oy).toInt().coerceAtLeast(0); val y1 = (cy + oy).toInt().coerceAtMost(h - 1)
            var yy = y0
            while (yy <= y1) {
                var xx = x0
                val dy = abs(yy - cy) / bh
                while (xx <= x1) {
                    val dx = abs(xx - cx) / bw
                    val p = px[yy * w + xx]
                    val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                    if (dx <= INNER / 2f && dy <= INNER / 2f) { fr += r; fg += g; fb += b; fn++ }
                    else if (dx > RING_IN / 2f || dy > RING_IN / 2f) { rr += r; rg += g; rb += b; rn++ }
                    xx += step
                }
                yy += step
            }
            if (fn < MIN_PX || rn < MIN_PX) return null
            return floatArrayOf(chroma(fr, fg, fb), chroma(rr, rg, rb), (fr + fg + fb).toFloat() / (fn * 3f * 255f))
        }

        const val INNER = 0.5f
        const val RING_IN = 1.3f
        const val RING_OUT = 1.9f
        const val MIN_PX = 12
    }
}
