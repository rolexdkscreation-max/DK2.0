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
 */
class FlashLive(val seq: IntArray = randomSeq(), private val slotMs: Long = SLOT_MS) {
    data class Result(val live: Boolean, val diff: Float, val face: Float, val lag: Long, val inOrder: Boolean, val samples: Int) {
        fun log() = "flash: live=$live diff=${"%.3f".format(diff)} face=${"%.3f".format(face)} lag=$lag क्रम=$inOrder नमूने=$samples"
    }

    private data class S(val t: Long, val face: Float, val ring: Float)
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
    fun sample(t: Long, face: Float, ring: Float) {
        if (startAt == 0L || !face.isFinite() || !ring.isFinite()) return
        synchronized(samples) { samples.add(S(t, face, ring)) }
    }

    /** वही लगातार चेहरा? null = नहीं दिखा. false = टूटा (फ़ेल) */
    fun track(now: Long, faces: Int, cx: Float, cy: Float, w: Float): Boolean {
        if (broken != null) return false
        if (!started) return true
        if (faces > 1) { broken = "एक से ज़्यादा चेहरे"; return false }
        if (faces == 0) { if (now - lastSeen > GAP_MS) broken = "चेहरा हट गया"; return broken == null }
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
        var lag = 0L
        while (lag <= LAG_MAX) {
            val r = at(all, lag)
            if (r != null && (best == null || score(r) > score(best))) best = r
            lag += LAG_STEP
        }
        return best ?: Result(false, 0f, 0f, 0L, false, all.size)
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
        val live = inOrder && diff >= MIN_DIFF && face >= MIN_FACE
        return Result(live, diff, face, lag, inOrder, red.size + blue.size)
    }

    companion object {
        const val SLOTS = 6
        const val SLOT_MS = 240L
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

        /** (R - B) / (R + G + B) - लाल रोशनी पर ऊपर, नीली पर नीचे. 0 = नाप नहीं */
        fun chroma(r: Long, g: Long, b: Long): Float { val s = r + g + b; return if (s <= 0L) 0f else (r - b).toFloat() / s }

        /**
         * ARGB pixels में से: चेहरे के बीच का हिस्सा (डिब्बे का बीच [INNER]) और आस-पास का घेरा ([RING_IN]..[RING_OUT] गुना डिब्बा).
         * @return [face, ring] chroma, या null (बहुत कम pixel)
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
            return floatArrayOf(chroma(fr, fg, fb), chroma(rr, rg, rb))
        }

        const val INNER = 0.5f
        const val RING_IN = 1.3f
        const val RING_OUT = 1.9f
        const val MIN_PX = 12
    }
}
