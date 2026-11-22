package com.dk.assistant

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 22 नवंबर 2026 (मालिक: "फ़ेस वेरिफ़िकेशन में बहुत इधर-उधर घुमाना पड़ता है, बहुत टाइम लगता है. फ़ोटो / वीडियो से न खुले,
 * जो लाइव दिख रहा है उसी से खुले, और बहुत फ़ास्ट").
 *
 * जड़ (ऐप लॉक में [BossGate] + [FaceLiveness]): हर बार ① एक तरफ़ ② दूसरी तरफ़ ③ फिर सीधे - तीन कदम, ऊपर से
 * AI anti-spoof सिर्फ़ सीधे चेहरे पर चलता था और 8 फ़्रेम चाहिए थे, फिर 3 मिलान, फिर 600ms रुकना. यानी कई सेकंड और सिर घुमाना.
 *
 * नया तरीका (ऐप लॉक के लिए, pure - JVM test):
 *  - **बिना घुमाए (तेज़ रास्ता)**: एक ही लगातार चेहरा, सख़्त मिलान ([FaceMath.STRICT], दूसरे सेव लोगों से [FacePeople.MARGIN] आगे)
 *    लगातार [HITS] फ़्रेम + AI anti-spoof हर फ़्रेम पर: कम से कम [FAST_FRAMES] फ़्रेम, औसत [FAST_MEAN] से ऊपर और कोई फ़्रेम
 *    [FAST_FLOOR] से नीचे नहीं. असली चेहरा अच्छी रोशनी में = करीब आधा-एक सेकंड.
 *  - **AI पक्का न हो** ([UNSURE_MS] बाद भी): तभी एक ही छोटा कदम - इस बार की random तरफ़ ([side]) सिर हल्का-सा
 *    ([FaceLiveness.TURN]) घुमाना. पहले से बना वीडियो नहीं जानता कौन-सी तरफ़ माँगी जाएगी; सपाट फ़ोटो का नाप घूमता ही नहीं.
 *    साथ में पुरानी सख़्त AI शर्त ([FaceSpoof.SURE_MEAN] / [FaceSpoof.SURE_FLOOR]) फिर भी ज़रूरी.
 *  - फ़ोटो / स्क्रीन (AI औसत [FaceSpoof.FAKE_MAX] से नीचे) = तुरंत FAIL. दो चेहरे / चेहरा कूदा / बदला = फिर से शुरू.
 *  - मॉडल न हो = FAIL (बिना जाँच कभी नहीं खुलता).
 *  - 22 नवंबर 2026 (मालिक: "रिकॉर्ड किया वीडियो दिखाया तो भी खुल गया"): ऊपर का सब पास होने के बाद भी आख़िरी ताला -
 *    स्क्रीन की random रंगीन रोशनी ([FlashLive], ~1.5 s). उसका जवाब सिर्फ़ सामने खड़ा असली चेहरा देता है, वीडियो / फ़ोटो नहीं.
 *    बीच में चेहरा हटा / बदला / दूसरा आया = FAIL.
 */
class QuickFaceGate(private val side: Int = FaceLiveness.randomSide(), private val allowTurn: Boolean = true) {
    data class Frame(
        val at: Long, val faces: Int, val cx: Float = 0f, val cy: Float = 0f, val w: Float = 0f,
        val yaw: Float? = null, val score: Float? = null, val otherBest: Float? = null, val real: Float? = null,
        val spoofReady: Boolean = true,
    )
    enum class State { WAIT, PASS, FAIL }
    /** [step] 0-3 स्क्रीन के ●●● के लिए, [turn] = अभी सिर घुमाने को कहा */
    data class Verdict(val state: State, val why: String, val step: Int = 0, val turn: Boolean = false, val flash: Boolean = false)

    /** रंगीन रोशनी वाली जाँच (चल रही हो तो) - [FlashScreen] इसी से रंग दिखाता है */
    @Volatile var flash: FlashLive? = null
        private set
    /** पिछली रंगीन जाँच का नतीजा (लॉग) */
    @Volatile var lastFlash: FlashLive.Result? = null
        private set
    val flashing get() = flash != null
    /** आख़िरी सख़्त मिलान (0-1) */
    @Volatile var lastScore = 0f
        private set

    private val votes = ArrayDeque<Float>()
    private var hits = 0
    private var notBoss = 0
    private var lastAt = 0L
    private var lastCx = 0f; private var lastCy = 0f; private var lastW = 0f
    private var matchAt = 0L
    private var turnAsked = false
    private var turned = false

    fun reset() {
        votes.clear(); hits = 0; notBoss = 0; lastAt = 0L; lastW = 0f; matchAt = 0L; turnAsked = false; turned = false; flash = null
    }

    fun mean(): Float = if (votes.isEmpty()) 0f else votes.sum() / votes.size
    fun count() = votes.size
    fun hits() = hits

    private fun fastReal() = votes.size >= FAST_FRAMES && mean() >= FAST_MEAN && votes.min() >= FAST_FLOOR
    private fun sureReal() = votes.size >= FaceSpoof.SURE_FRAMES && mean() >= FaceSpoof.SURE_MEAN && votes.min() >= FaceSpoof.SURE_FLOOR
    private fun fake() = votes.size >= FaceSpoof.MIN_FRAMES && mean() <= FaceSpoof.FAKE_MAX

    fun feed(f: Frame): Verdict {
        if (!f.spoofReady) return Verdict(State.FAIL, "AI anti-spoof मॉडल तैयार नहीं — PIN से खोलें")
        flash?.let { fl -> return flashStep(fl, f) }
        if (f.faces == 0) {
            if (lastAt > 0L && f.at - lastAt > GAP_MS) reset()
            hits = 0
            return Verdict(State.WAIT, "चेहरा कैमरे के सामने करें")
        }
        if (f.faces > 1) { reset(); return Verdict(State.WAIT, "सिर्फ़ आप अकेले कैमरे के सामने रहें — ${f.faces} चेहरे दिख रहे हैं") }
        // वही लगातार चेहरा? (फ़ोटो बदली / बहुत देर गायब = नए सिरे से)
        if (lastAt > 0L) {
            val jump = lastW > 0f && hypot(f.cx - lastCx, f.cy - lastCy) > FaceLiveness.JUMP * lastW
            val resize = lastW > 0f && f.w > 0f && (f.w / lastW > FaceLiveness.RESIZE || lastW / f.w > FaceLiveness.RESIZE)
            if (f.at - lastAt > GAP_MS || jump || resize) reset()
        }
        lastAt = f.at; lastCx = f.cx; lastCy = f.cy; lastW = f.w

        f.real?.let { votes.addLast(it.coerceIn(0f, 1f)); while (votes.size > WINDOW) votes.removeFirst() }
        if (fake()) { reset(); return Verdict(State.FAIL, "फ़ोटो / वीडियो / स्क्रीन पकड़ी गई — असली इंसान नहीं") }
        if (turnAsked && f.yaw != null && f.yaw * side >= FaceLiveness.TURN) turned = true

        val s = f.score ?: return when {
            turnAsked -> Verdict(State.WAIT, turnHint(), 2, true)
            f.yaw != null && abs(f.yaw) > FaceLiveness.TURN -> Verdict(State.WAIT, "सीधे कैमरे में देखिए", 1)
            else -> Verdict(State.WAIT, "चेहरा पढ़ रहा हूँ…", 1)
        }
        if (s < FaceMath.MATCH * 0.8f) {
            hits = 0; notBoss++
            if (notBoss >= NOT_BOSS_FRAMES) { reset(); return Verdict(State.FAIL, "यह चेहरा मालिक का नहीं (${(s * 100).toInt()}%)") }
            return Verdict(State.WAIT, "यह चेहरा मेल नहीं खा रहा", 1)
        }
        notBoss = 0
        if (s < FaceMath.STRICT) { hits = 0; return Verdict(State.WAIT, "मिलान ${(s * 100).toInt()}% — रोशनी में सीधे देखिए", 1, turnAsked) }
        if (f.otherBest != null && s - f.otherBest < FacePeople.MARGIN) { hits = 0; return Verdict(State.WAIT, "चेहरा किसी और सेव व्यक्ति से भी मिल रहा है — पक्का नहीं", 1) }
        hits++
        if (matchAt == 0L) matchAt = f.at
        val pct = (s * 100).toInt()
        lastScore = s
        if (hits >= HITS && fastReal()) return startFlash(pct)
        // 22 नवंबर 2026 (टच मोड, मालिक: "सिर घुमाना हटा दो"): AI पुरानी सख़्त शर्त पर पक्का = सीधे रंगीन जाँच, घुमाना कभी नहीं
        if (!allowTurn) return if (hits >= HITS && sureReal()) startFlash(pct) else Verdict(State.WAIT, "✓ चेहरा मिला — AI लाइव जाँच (${votes.size}/${FaceSpoof.SURE_FRAMES})… सीधे देखते रहिए", 2)
        if (!turnAsked && f.at - matchAt >= UNSURE_MS) turnAsked = true
        if (turnAsked) {
            if (turned && hits >= HITS && sureReal()) return startFlash(pct)
            return Verdict(State.WAIT, turnHint(), 2, true)
        }
        return Verdict(State.WAIT, "✓ चेहरा मिला — AI लाइव जाँच (${votes.size}/$FAST_FRAMES)…", 2)
    }

    private var flashAskedAt = 0L

    private fun startFlash(pct: Int): Verdict {
        flash = FlashLive(); flashAskedAt = lastAt
        return Verdict(State.WAIT, "✓ चेहरा मिला ($pct%) — आख़िरी लाइव जाँच: रंग बदलेंगे, सीधे देखते रहिए", 3, flash = true)
    }

    private fun flashStep(fl: FlashLive, f: Frame): Verdict {
        if (!fl.started && f.at - flashAskedAt > FLASH_START_MS) { reset(); return Verdict(State.WAIT, "चेहरा कैमरे के सामने करें") }
        if (!fl.track(f.at, f.faces, f.cx, f.cy, f.w)) {
            val why = fl.broken; reset()
            return Verdict(State.FAIL, "लाइव जाँच बीच में टूटी ($why) — फिर से")
        }
        if (!fl.done(f.at)) return Verdict(State.WAIT, "लाइव जाँच — रंग बदल रहे हैं, सीधे देखते रहिए", 3)
        val r = fl.result(); lastFlash = r; reset()
        return if (r.live) Verdict(State.PASS, "✓ LIVE — असली इंसान, मालिक पक्का", 3)
        else Verdict(State.FAIL, FLASH_FAIL)
    }

    private fun turnHint() = if (turned) "✓ ठीक — अब सीधे कैमरे में देखिए" else "सिर हल्का-सा ${FaceLiveness.side(side)} घुमाइए"

    companion object {
        const val FLASH_FAIL = "स्क्रीन की रोशनी चेहरे पर नहीं दिखी — फ़ोटो / वीडियो / स्क्रीन? (बहुत तेज़ धूप हो तो छाँव में आइए या PIN से खोलें)"
        const val FLASH_START_MS = 2_000L
        const val HITS = 2
        const val WINDOW = 6
        const val FAST_FRAMES = 4
        const val FAST_MEAN = 0.80f
        const val FAST_FLOOR = 0.50f
        const val UNSURE_MS = 1_500L
        const val GAP_MS = 900L
        const val NOT_BOSS_FRAMES = 4
    }
}
