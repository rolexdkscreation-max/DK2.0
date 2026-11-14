package com.dk.assistant

import kotlin.math.abs
import kotlin.math.hypot

/**
 * नवंबर 2026 (मालिक की शिकायत): "गार्ड मोड का सायरन मेरी फ़ोटो दिखाने से भी रुक जाता है".
 *
 * जड़: पुराना [LivenessTracker] सिर्फ़ यह देखता था कि 5 फ़्रेम में चेहरे के 5 बिंदु ज़रा-से (variance ≥ 0.00001) हिले या नहीं.
 * हाथ में पकड़ी फ़ोटो / फ़ोन की स्क्रीन पर फ़ोटो हमेशा थोड़ा हिलती है और पहचान के बिंदु ख़ुद भी काँपते हैं - तो फ़ोटो भी "असली" मान ली जाती थी.
 *
 * नया तरीका (3D जाँच, सिर घुमाना):
 * - नाक की जगह को आँखों और होंठ के बीच के हिसाब से नापते हैं ([yaw]). यह नाप affine-invariant है: फ़ोटो को
 *   हिलाओ, घुमाओ, पास-दूर करो, तिरछा करो - सपाट (2D) फ़ोटो में यह लगभग वही रहता है.
 * - असली चेहरे में नाक आगे उभरी होती है, सिर दाएँ-बाएँ घुमाने पर यह नाप साफ़ बदलता है (करीब 18-20° पर ±0.12).
 * - सायरन तभी बंद होगा जब एक ही लगातार चेहरा: सामने (मालिक पहचाना) + एक तरफ़ घूमा + दूसरी तरफ़ घूमा, [WINDOW_MS] के अंदर,
 *   और फिर सामने आकर [GuardRules.OWNER_HITS] बार मालिक पहचाना जाए.
 * - बीच में चेहरा गायब ([GAP_MS]) या एकदम कूदा (फ़ोटो बदली) = सब फिर से शुरू.
 * Pure - JUnit से जाँच.
 */
class FaceLiveness(
    private val turn: Float = TURN,
    private val center: Float = CENTER,
    private val windowMs: Long = WINDOW_MS,
    private val gapMs: Long = GAP_MS,
) {
    enum class Step { NEED_FACE, LOOK_CENTER, TURN_ONE, TURN_OTHER, LIVE }

    private var lastAt = 0L
    private var lastCx = 0f; private var lastCy = 0f; private var lastW = 0f
    private var startAt = 0L
    private var centerSeen = false
    private var sawPos = false
    private var sawNeg = false
    private var flatFrames = 0
    @Volatile var step: Step = Step.NEED_FACE
        private set
    /** पिछला नाप (डिबग / स्क्रीन) */
    @Volatile var lastYaw: Float = 0f
        private set

    fun reset() {
        lastAt = 0L; startAt = 0L; centerSeen = false; sawPos = false; sawNeg = false; flatFrames = 0
        step = Step.NEED_FACE
    }

    /** इस बार कितने फ़्रेम सामने वाले नाप में रहे (फ़ोटो लगभग हमेशा सामने रहती है) */
    fun flatCount() = flatFrames

    /**
     * एक फ़्रेम. [det] = null (चेहरा नहीं). [now] = समय (ms). लौटाता है: अभी असली इंसान पक्का हुआ या नहीं.
     */
    fun update(det: FaceMath.Det?, now: Long): Boolean {
        if (det == null || det.kps.size < 10 || det.w <= 0f || det.h <= 0f) {
            if (lastAt > 0L && now - lastAt > gapMs) reset()
            return step == Step.LIVE
        }
        val cx = det.x + det.w / 2f; val cy = det.y + det.h / 2f
        // चेहरा बहुत देर गायब रहा, या एक फ़्रेम में एकदम दूसरी जगह/दूसरे आकार का (फ़ोटो बदली) = नया चेहरा
        if (lastAt > 0L) {
            val jump = hypot(cx - lastCx, cy - lastCy) > JUMP * lastW
            val resize = lastW > 0f && (det.w / lastW > RESIZE || lastW / det.w > RESIZE)
            if (now - lastAt > gapMs || jump || resize) reset()
        }
        lastAt = now; lastCx = cx; lastCy = cy; lastW = det.w
        if (startAt == 0L) startAt = now
        // LIVE भी हमेशा के लिए नहीं - समय-सीमा के बाद फिर से जाँच
        if (now - startAt > windowMs) { reset(); startAt = now; lastAt = now; lastCx = cx; lastCy = cy; lastW = det.w }

        val y = yaw(det.kps) ?: return step == Step.LIVE
        lastYaw = y
        if (abs(y) <= center) { centerSeen = true; flatFrames++ }
        if (y >= turn) sawPos = true
        if (y <= -turn) sawNeg = true
        step = when {
            centerSeen && sawPos && sawNeg && abs(y) <= center -> Step.LIVE
            step == Step.LIVE -> Step.LIVE
            sawPos && sawNeg -> Step.LOOK_CENTER
            sawPos || sawNeg -> Step.TURN_OTHER
            else -> Step.TURN_ONE
        }
        return step == Step.LIVE
    }

    /**
     * 14 नवंबर 2026 (मालिक: "पहले से सेट हो कि कैसे घुमाना है, फिर आई कॉन्टैक्ट"): तय क्रम ① एक तरफ़ ② दूसरी तरफ़ ③ सीधे कैमरे में आँख -
     * स्क्रीन पर पहले से दिखता है ([GUIDE]), और हर कदम पर अगला कदम ([hint]). बाएँ / दाएँ किसी से भी शुरू करें, चलेगा.
     */
    fun hint(): String = when (step) {
        Step.NEED_FACE -> "चेहरा कैमरे के सामने करें"
        Step.TURN_ONE -> "① सिर धीरे से एक तरफ़ (बाएँ) घुमाइए"
        Step.TURN_OTHER -> "② अब दूसरी तरफ़ (दाएँ) घुमाइए"
        Step.LOOK_CENTER -> "③ अब सीधे कैमरे में आँख मिलाइए"
        Step.LIVE -> "✓ असली इंसान"
    }

    /** कितने कदम पूरे (0-3) - स्क्रीन पर ●●○ */
    fun done(): Int = when (step) { Step.NEED_FACE, Step.TURN_ONE -> 0; Step.TURN_OTHER -> 1; Step.LOOK_CENTER -> 2; Step.LIVE -> 3 }

    /**
     * 14 नवंबर 2026 (मालिक: "चेहरा पहचानने में बहुत टाइम लगता है"): जड़ - हर फ़्रेम पर चेहरा पहचान (embed) भी चलती थी, तो एक ही
     * धागे पर फ़्रेम धीमे आते और सिर घुमाना पकड़ में देर से आता. अब पहचान सिर्फ़ शुरू के सीधे चेहरे पर (किसी और का चेहरा = फिर से)
     * और आख़िरी कदम (सीधे देखना) पर; घुमाते समय सिर्फ़ तेज़ detect - वही लगातार चेहरा ([JUMP] / [RESIZE] / [GAP_MS]) होने की जाँच चलती रहती है.
     */
    fun idNow(frontNow: Boolean): Boolean = step == Step.LOOK_CENTER || step == Step.LIVE || (frontNow && (step == Step.NEED_FACE || step == Step.TURN_ONE))

    companion object {
        /**
         * सिर घूमा माना जाए. 14 नवंबर 2026: 0.12 (करीब 20°) से 0.09 (करीब 10-12°) - मालिक को ज़ोर से घुमाना पड़ता था.
         * सपाट फ़ोटो का नाप हिलाने / झुकाने पर भी 0.02 के अंदर रहता है, तो फ़ोटो अब भी पास नहीं होगी.
         */
        const val TURN = 0.09f
        /** स्क्रीन पर पहले से दिखने वाला तय क्रम */
        const val GUIDE = "जाँच का क्रम: ① सिर बाएँ ② सिर दाएँ ③ सीधे कैमरे में आँख मिलाइए"
        /** सीधा माना जाए */
        const val CENTER = 0.06f
        /** पूरी जाँच इतने में (वरना फिर से) */
        const val WINDOW_MS = 12_000L
        /** इतनी देर चेहरा न दिखे = नया चेहरा */
        const val GAP_MS = 900L
        /** एक फ़्रेम में चेहरे की चौड़ाई के इतने गुना से ज़्यादा कूदा = फ़ोटो बदली */
        const val JUMP = 0.6f
        /** एक फ़्रेम में आकार इतने गुना बदला = फ़ोटो बदली */
        const val RESIZE = 1.5f

        /**
         * नाक का आँखों की रेखा के साथ (बाएँ-दाएँ) नाप, आँखों और होंठ के बीच वाले affine आधार में:
         * nose - eyeMid = a·(eye2 - eye1) + b·(mouthMid - eyeMid) -> a.
         * सपाट फ़ोटो पर कोई भी affine बदलाव (हिलाना/घुमाना/पास-दूर/तिरछा) a को नहीं बदलता. सामने = 0 के पास.
         * YuNet बिंदु: [आँख1 x,y, आँख2 x,y, नाक x,y, होंठ-कोना1 x,y, होंठ-कोना2 x,y]. null = बिंदु बेतुके.
         */
        fun yaw(k: FloatArray): Float? {
            if (k.size < 10) return null
            val emx = (k[0] + k[2]) / 2f; val emy = (k[1] + k[3]) / 2f
            val e1x = k[2] - k[0]; val e1y = k[3] - k[1]
            val mmx = (k[6] + k[8]) / 2f; val mmy = (k[7] + k[9]) / 2f
            val e2x = mmx - emx; val e2y = mmy - emy
            val px = k[4] - emx; val py = k[5] - emy
            val det = e1x * e2y - e1y * e2x
            if (abs(det) < 1e-3f || hypot(e1x, e1y) < 1f) return null
            return (px * e2y - py * e2x) / det
        }
    }
}
