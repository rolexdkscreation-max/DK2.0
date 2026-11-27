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
 *  - 27 नवंबर 2026 (मालिक: "आउटडोर में धूप रहते नहीं होता… रियल इंसान से ही"): रंग वाली जाँच में अब आँकड़ों वाला रास्ता
 *    ([FlashLive.tStat]) और कैमरे का रंग-ताला. फिर भी नतीजा न निकले और **सच में धूप** हो ([Frame.lux] ≥ [SUN_LUX], या
 *    light sensor न हो तो चेहरे की चमक ≥ [SUN_LUMA]) और रंग का जवाब उल्टा न हो ([FlashLive.Result.against]) = फ़ेल नहीं,
 *    धूप वाली random सिर-घुमाना जाँच ([SunTurns]) + AI anti-spoof की सख़्त शर्त (नए सिरे से) + आख़िर में सीधे चेहरे पर सख़्त
 *    मालिक-मिलान. घर के अंदर / छाँव में (धूप नहीं) रंग न दिखे = पहले जैसा FAIL (वहाँ वीडियो / फ़ोटो ही वजह).
 */
class QuickFaceGate(private val side: Int = FaceLiveness.randomSide(), private val allowTurn: Boolean = true) {
    data class Frame(
        val at: Long, val faces: Int, val cx: Float = 0f, val cy: Float = 0f, val w: Float = 0f,
        val yaw: Float? = null, val score: Float? = null, val otherBest: Float? = null, val real: Float? = null,
        val spoofReady: Boolean = true,
        /** 27 नवंबर 2026: फ़ोन के light sensor से आस-पास की रोशनी (lux). null = sensor नहीं */
        val lux: Float? = null,
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
    /** धूप वाली सिर-घुमाना जाँच चल रही हो तो */
    @Volatile var sun: SunTurns? = null
        private set
    /** अब तक की सबसे तेज़ रोशनी (lux) - धूप है या नहीं */
    private var maxLux = 0f
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
        votes.clear(); hits = 0; notBoss = 0; lastAt = 0L; lastW = 0f; matchAt = 0L; turnAsked = false; turned = false; flash = null; sun = null
    }

    fun mean(): Float = if (votes.isEmpty()) 0f else votes.sum() / votes.size
    fun count() = votes.size
    fun hits() = hits

    private fun fastReal() = votes.size >= FAST_FRAMES && mean() >= FAST_MEAN && votes.min() >= FAST_FLOOR
    private fun sureReal() = votes.size >= FaceSpoof.SURE_FRAMES && mean() >= FaceSpoof.SURE_MEAN && votes.min() >= FaceSpoof.SURE_FLOOR
    private fun fake() = votes.size >= FaceSpoof.MIN_FRAMES && mean() <= FaceSpoof.FAKE_MAX

    fun feed(f: Frame): Verdict {
        if (!f.spoofReady) return Verdict(State.FAIL, "AI anti-spoof मॉडल तैयार नहीं — PIN से खोलें")
        f.lux?.let { if (it.isFinite() && it > maxLux) maxLux = it }
        flash?.let { fl -> return flashStep(fl, f) }
        sun?.let { st -> return sunStep(st, f) }
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
        val r = fl.result(); lastFlash = r
        if (r.live) { reset(); return passed() }
        if (sunny(r)) return startSun(f)
        reset()
        return Verdict(State.FAIL, FLASH_FAIL)
    }

    /** सच में धूप (रंग इसीलिए नहीं दिखा) और रंग का जवाब उल्टा नहीं (पुराना क्रम वाला वीडियो नहीं) */
    private fun sunny(r: FlashLive.Result): Boolean {
        if (r.against) return false
        return if (maxLux > 0f) maxLux >= SUN_LUX else r.luma.isFinite() && r.luma >= SUN_LUMA
    }

    private fun startSun(f: Frame): Verdict {
        flash = null
        // AI की गिनती नए सिरे से (धूप वाले फ़्रेम), मिलान भी नया - आख़िर में सीधे चेहरे पर फिर से सख़्त
        votes.clear(); hits = 0; notBoss = 0; lastAt = f.at; lastW = 0f
        val st = SunTurns(); sun = st; sunAt = f.at; sunMatched = false
        return Verdict(State.WAIT, st.hint(), 3, true)
    }

    private var sunAt = 0L
    private var sunMatched = false

    private fun sunStep(st: SunTurns, f: Frame): Verdict {
        val broke = when {
            f.faces > 1 -> "एक से ज़्यादा चेहरे"
            f.faces == 0 -> if (f.at - lastAt > GAP_MS) "चेहरा हट गया" else null
            lastW > 0f && (hypot(f.cx - lastCx, f.cy - lastCy) > FaceLiveness.JUMP * lastW ||
                f.w / lastW > FaceLiveness.RESIZE || lastW / f.w > FaceLiveness.RESIZE) -> "चेहरा बदला / कूदा"
            f.at - sunAt > SUN_MAX_MS -> "समय ख़त्म"
            else -> null
        }
        if (broke != null) { reset(); return Verdict(State.FAIL, "धूप वाली लाइव जाँच टूटी ($broke) — फिर से") }
        if (f.faces == 0) return Verdict(State.WAIT, st.hint(), 3, true)
        lastAt = f.at; lastCx = f.cx; lastCy = f.cy; lastW = f.w
        f.real?.let { votes.addLast(it.coerceIn(0f, 1f)); while (votes.size > WINDOW) votes.removeFirst() }
        if (fake()) { reset(); return Verdict(State.FAIL, "फ़ोटो / वीडियो / स्क्रीन पकड़ी गई — असली इंसान नहीं") }
        f.score?.let { s ->
            if (s < FaceMath.MATCH * 0.8f) { reset(); return Verdict(State.FAIL, "यह चेहरा मालिक का नहीं (${(s * 100).toInt()}%)") }
            val strong = s >= FaceMath.STRICT && (f.otherBest == null || s - f.otherBest >= FacePeople.MARGIN)
            if (strong) {
                lastScore = s
                if (st.index >= st.steps.size) sunMatched = true
            }
        }
        when (st.update(f.at, f.yaw)) {
            SunTurns.State.FAIL -> { val w = st.why; reset(); return Verdict(State.FAIL, "धूप वाली लाइव जाँच फ़ेल ($w) — फ़ोटो / वीडियो? फिर से या PIN") }
            SunTurns.State.WAIT -> return Verdict(State.WAIT, st.hint(), 3, true)
            SunTurns.State.DONE -> Unit
        }
        // सब कदम सही - अब सीधे चेहरे पर सख़्त मिलान + AI पक्का
        if (sunMatched && sureReal()) { reset(); return passed() }
        return Verdict(State.WAIT, "✓ सिर घुमाना सही — सीधे देखिए, AI लाइव जाँच (${votes.size}/${FaceSpoof.SURE_FRAMES})…", 3)
    }

    /** PASS सिर्फ़ यहीं से - रंगीन रोशनी ([flashStep]) या धूप वाली जाँच ([sunStep]) के बाद */
    private fun passed() = Verdict(State.PASS, "✓ LIVE — असली इंसान, मालिक पक्का", 3)

    private fun turnHint() = if (turned) "✓ ठीक — अब सीधे कैमरे में देखिए" else "सिर हल्का-सा ${FaceLiveness.side(side)} घुमाइए"

    companion object {
        const val FLASH_FAIL = "स्क्रीन की रोशनी चेहरे पर नहीं दिखी — फ़ोटो / वीडियो / स्क्रीन? (बहुत तेज़ धूप हो तो छाँव में आइए या PIN से खोलें)"
        const val FLASH_START_MS = 2_000L
        /** इससे ज़्यादा रोशनी = धूप (बादल वाला बाहर ~10 हज़ार, सीधी धूप 30 हज़ार - 1 लाख lux; घर के अंदर 50-1000) */
        const val SUN_LUX = 8_000f
        /** light sensor न हो: रंग के दौरान चेहरे की औसत चमक इतनी (0-1) = बहुत तेज़ रोशनी */
        const val SUN_LUMA = 0.62f
        /** धूप वाली पूरी जाँच की हद */
        const val SUN_MAX_MS = 15_000L
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
