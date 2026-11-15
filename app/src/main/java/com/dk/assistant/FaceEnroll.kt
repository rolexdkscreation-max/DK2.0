package com.dk.assistant

import kotlin.math.abs

/**
 * 15 नवंबर 2026 (मालिक): "चेहरा सेट करते समय कुछ सेकंड ही लेता है - कम से कम 30 सेकंड तक इधर-उधर घुमाने को बोले
 * और अच्छे से पूरा चेहरा capture करे".
 *
 * पहले: सीधे चेहरे के 8 फ़्रेम (~3 सेकंड) - घूमे / झुके चेहरे की पहचान कमज़ोर रहती थी.
 * अब तय क्रम, हर कदम कम से कम [POSE_MS] (कुल ≥ 30 सेकंड):
 *   ① सीधे  ② सिर बाएँ  ③ सिर दाएँ  ④ ठोड़ी ऊपर  ⑤ ठोड़ी नीचे  ⑥ फिर सीधे, आँख मिलाइए
 * हर कदम पर वह मुद्रा (pose) पकड़ में आए तभी फ़्रेम लिए जाते हैं ([FRAMES_PER_POSE] तक, [GAP_MS] के अंतर पर).
 * मुद्रा का नाप 5 बिंदुओं से (affine-invariant): बाएँ-दाएँ = [FaceLiveness.yaw], ऊपर-नीचे = [pitch] (शुरू के सीधे चेहरे से फ़र्क).
 * Pure (JVM test) - कैमरा/मॉडल [GuardFaceSetup] में.
 */
class FaceEnroll(private val poseMs: Long = POSE_MS) {
    enum class Pose(val hint: String) {
        CENTER("① सीधे कैमरे में देखिए"),
        LEFT("② अब सिर धीरे से बाएँ घुमाइए"),
        RIGHT("③ अब सिर धीरे से दाएँ घुमाइए"),
        UP("④ अब ठोड़ी थोड़ा ऊपर उठाइए"),
        DOWN("⑤ अब ठोड़ी थोड़ा नीचे झुकाइए"),
        FINAL("⑥ फिर सीधे — कैमरे में आँख मिलाइए"),
    }

    enum class Act { WAIT, TAKE, NEXT, DONE }

    private var idx = 0
    private var startAt = 0L
    private var poseAt = 0L
    private var lastTake = 0L
    private var taken = 0
    private var base = 0f; private var baseN = 0
    @Volatile var skipped = 0
        private set

    val pose: Pose get() = Pose.values()[idx.coerceAtMost(Pose.values().size - 1)]
    fun done() = idx >= Pose.values().size
    /** कितने कदम पूरे (0-6) */
    fun step() = idx
    fun total() = Pose.values().size
    fun takenNow() = taken

    fun reset() { idx = 0; startAt = 0L; poseAt = 0L; lastTake = 0L; taken = 0; base = 0f; baseN = 0; skipped = 0 }

    /** अभी तक कितने सेकंड (UI पर) */
    fun elapsed(now: Long) = if (startAt == 0L) 0L else now - startAt
    /** इस कदम में अभी कितना और रुकना है (ms) */
    fun leftMs(now: Long) = if (poseAt == 0L) poseMs else (poseMs - (now - poseAt)).coerceAtLeast(0)

    /** यह फ़्रेम इस कदम की मुद्रा में है? */
    fun inPose(k: FloatArray): Boolean {
        val y = FaceLiveness.yaw(k) ?: return false
        val p = pitch(k) ?: return false
        val dp = if (baseN > 0) p - base / baseN else 0f
        return when (pose) {
            Pose.CENTER, Pose.FINAL -> abs(y) <= CENTER_YAW && (baseN == 0 || abs(dp) <= CENTER_PITCH)
            Pose.LEFT -> y >= TURN_YAW && y <= MAX_YAW
            Pose.RIGHT -> y <= -TURN_YAW && y >= -MAX_YAW
            Pose.UP -> dp <= -TILT && abs(y) <= SIDE_LIMIT
            Pose.DOWN -> dp >= TILT && abs(y) <= SIDE_LIMIT
        }
    }

    /**
     * एक अच्छा (साफ़, पास, उसी इंसान का) चेहरा. लौटाता है: [Act.TAKE] = यह फ़्रेम सीखो, [Act.NEXT] = अगला कदम शुरू,
     * [Act.DONE] = सब पूरा, [Act.WAIT] = अभी नहीं (मुद्रा नहीं / जल्दी है).
     */
    fun feed(k: FloatArray, now: Long): Act {
        if (done()) return Act.DONE
        if (startAt == 0L) startAt = now
        if (poseAt == 0L) poseAt = now
        val ok = inPose(k)
        if (ok && pose == Pose.CENTER) pitch(k)?.let { base += it; baseN++ }
        val long = now - poseAt >= poseMs
        // ऊपर/नीचे का नाप हर चेहरे पर एक-सा नहीं - बहुत देर न हो तो कुछ फ़्रेम लेकर आगे (बाएँ/दाएँ/सीधे कभी नहीं छोड़ते)
        val giveUp = (pose == Pose.UP || pose == Pose.DOWN) && now - poseAt >= GIVE_UP_MS
        if (taken >= FRAMES_PER_POSE && long || giveUp && taken > 0) {
            if (taken < FRAMES_PER_POSE) skipped++
            idx++; taken = 0; poseAt = now; lastTake = 0L
            return if (done()) Act.DONE else Act.NEXT
        }
        if ((ok || giveUp) && taken < MAX_PER_POSE && now - lastTake >= GAP_MS) { taken++; lastTake = now; return Act.TAKE }
        return Act.WAIT
    }

    /** स्क्रीन के लिए: "② सिर बाएँ … (4 s)" */
    fun hint(now: Long, inPoseNow: Boolean): String {
        if (done()) return "✓ पूरा चेहरा सीख लिया"
        val s = (leftMs(now) + 999) / 1000
        val hold = when {
            !inPoseNow && (pose == Pose.LEFT || pose == Pose.RIGHT) -> " — थोड़ा और घुमाइए"
            !inPoseNow && (pose == Pose.UP || pose == Pose.DOWN) -> " — थोड़ा और"
            !inPoseNow -> ""
            taken >= FRAMES_PER_POSE -> " — ऐसे ही रहिए ${s}s"
            else -> " — ऐसे ही रहिए"
        }
        return pose.hint + hold
    }

    /** पूरी प्रगति 0..1 (समय + कदम) */
    fun progress(now: Long): Float {
        val per = 1f / total()
        val inStep = if (poseAt == 0L) 0f else ((now - poseAt).toFloat() / poseMs).coerceIn(0f, 1f)
        return (idx * per + inStep * per).coerceIn(0f, 1f)
    }

    companion object {
        /** हर कदम कम से कम (6 कदम x 5 s = 30 s) */
        const val POSE_MS = 5_000L
        const val MIN_TOTAL_MS = 30_000L
        const val FRAMES_PER_POSE = 4
        const val MAX_PER_POSE = 6
        const val GAP_MS = 600L
        const val GIVE_UP_MS = 12_000L
        const val CENTER_YAW = 0.06f
        const val CENTER_PITCH = 0.06f
        const val TURN_YAW = 0.10f
        /** इससे ज़्यादा घूमा = आधा चेहरा ही दिखता है, पहचान बिगड़ती है */
        const val MAX_YAW = 0.45f
        /** ऊपर/नीचे: शुरू के सीधे चेहरे से इतना फ़र्क (3D ढाँचे पर करीब 7°) */
        const val TILT = 0.07f
        const val SIDE_LIMIT = 0.12f
        /** घूमे चेहरे का बाकी सीखे चेहरे से कम-से-कम मिलान (सीधे वाला [FaceMath.ENROLL_SAME]) */
        const val SAME_TURNED = 0.35f
        /** घूमे/झुके चेहरे पर YuNet का भरोसा थोड़ा कम आता है */
        const val TURNED_SCORE = 0.75f

        /**
         * ऊपर-नीचे का नाप: नाक आँखों से होंठ की तरफ़ कितनी दूर (affine आधार में b):
         * nose - eyeMid = a·(eye2 - eye1) + b·(mouthMid - eyeMid). सीधे ≈ 0.44 (3D ढाँचे पर); ठोड़ी नीचे = बढ़ता, ऊपर = घटता.
         */
        fun pitch(k: FloatArray): Float? {
            if (k.size < 10) return null
            val emx = (k[0] + k[2]) / 2f; val emy = (k[1] + k[3]) / 2f
            val e1x = k[2] - k[0]; val e1y = k[3] - k[1]
            val e2x = (k[6] + k[8]) / 2f - emx; val e2y = (k[7] + k[9]) / 2f - emy
            val px = k[4] - emx; val py = k[5] - emy
            val det = e1x * e2y - e1y * e2x
            if (abs(det) < 1e-3f) return null
            return (e1x * py - e1y * px) / det
        }

        /** सिखाने लायक फ़्रेम? null = हाँ ([FaceMath.enrollProblem] जैसा, घूमे चेहरे पर थोड़ी ढील) */
        fun problem(d: FaceMath.Det?, frameW: Int, pose: Pose): String? = when {
            pose == Pose.CENTER || pose == Pose.FINAL -> FaceMath.enrollProblem(d, frameW)
            d == null -> "चेहरा नहीं दिखा — फ़्रेम में रहिए"
            d.score < TURNED_SCORE -> "चेहरा साफ़ नहीं — रोशनी में आइए, धीरे घुमाइए"
            d.w < frameW * FaceMath.ENROLL_MIN_FACE -> "थोड़ा पास आएँ"
            else -> null
        }
    }
}
