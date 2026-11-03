package com.dk.assistant

/**
 * नवंबर 2026 (Smart turn - बासी जवाब न बजे):
 *
 * जड़: आप छोटे ठहराव ("मुझे कल… [0.8s] …सुबह उठा देना") के बाद आगे बोले. server पहला टुकड़ा "बात खत्म" मान
 * चुका था और उसका जवाब बन रहा था. उसका पहला ऑडियो server के "interrupted" से पहले आ गया = playQ में = isPlaying()
 * सच = mic "DK बोल रहा" रास्ते पर (turnDet.reset + Gemini को सिर्फ़ खामोशी, caption से पक्का होने तक) - आपकी बाक़ी
 * बात कट जाती थी और DK पुराना अधूरा जवाब बोलने लगता था.
 *
 * इलाज: आप बोल रहे हैं (DK चुप) और जवाब अभी बजना शुरू नहीं हुआ = आने वाला जवाब कतार में नहीं, यहाँ रुकता है.
 *  - server "interrupted" भेजे = [clear] (पुराना जवाब कभी नहीं बजता)
 *  - आप चुप + [graceMs] तक कोई "interrupted" नहीं:
 *      - पुराना जवाब पूरा हो चुका था और आपने नए शब्द बोले = [Decision.DROP] (Gemini आपकी नई बात का जवाब देगा)
 *      - वरना (खाँसी / शोर / बिना शब्द) = [Decision.RELEASE] (वही जवाब, बस थोड़ा रुककर - कुछ खोया नहीं)
 *  - आप बहुत देर बोलते रहे ([maxHoldMs]) = पुराना जवाब बासी = DROP
 * सिर्फ़ जवाब की शुरुआत पर - जो बज चुका उसके बीच में टोकना BargeGate का काम (वैसा ही).
 */
class ReplyStartHold(
    private val graceMs: Long = 600L,
    private val maxHoldMs: Long = 15_000L,
    /** इससे ज़्यादा रुका ऑडियो नहीं (24 kHz 16-bit ≈ 40 सेकंड) - लंबे जवाब में memory न भरे */
    private val maxBytes: Int = 2_000_000,
) {
    enum class Decision { WAIT, RELEASE, DROP }

    private val buf = ArrayDeque<ByteArray>()
    private var bytes = 0
    @Volatile var active = false
        private set
    private var since = 0L
    private var userEndAt = 0L
    private var userWords = false
    /** रुका जवाब server पर पूरा हो चुका (turnComplete आया) */
    @Volatile var heldComplete = false
        private set
    /** गिनती (debug / Inspector) */
    var holds = 0
        private set
    var releases = 0
        private set
    var drops = 0
        private set

    /** आप बोलने लगे (DK चुप, जवाब आने वाला / बन रहा) */
    @Synchronized fun begin(now: Long) {
        if (!active) { active = true; since = now; heldComplete = false; userWords = false; holds++ }
        userEndAt = 0L
    }

    /** आपकी बात खत्म (फ़ोन का VAD) - words = caption में असली शब्द */
    @Synchronized fun userEnded(now: Long, words: Boolean) {
        if (!active) return
        userEndAt = now; userWords = userWords || words
    }

    /** जवाब का ऑडियो: true = यहाँ रुका (कतार में मत डालो) */
    @Synchronized fun offer(pcm: ByteArray): Boolean {
        if (!active) return false
        if (bytes + pcm.size <= maxBytes) { buf.addLast(pcm); bytes += pcm.size }
        return true
    }

    @Synchronized fun turnComplete() { if (active) heldComplete = true }

    @Synchronized fun check(now: Long): Decision {
        if (!active) return Decision.WAIT
        if (userEndAt == 0L) return if (now - since > maxHoldMs) Decision.DROP else Decision.WAIT
        if (now - userEndAt < graceMs) return Decision.WAIT
        return if (heldComplete && userWords) Decision.DROP else Decision.RELEASE
    }

    /** रुका ऑडियो (क्रम में) - रोक खत्म */
    @Synchronized fun release(): List<ByteArray> {
        val out = buf.toList(); reset(); releases++; return out
    }

    /** बासी जवाब फेंको - रोक खत्म. @return कितने bytes फेंके */
    @Synchronized fun drop(): Int { val b = bytes; reset(); drops++; return b }

    /** server ने पुराना जवाब रोका (interrupted) / नया connection */
    @Synchronized fun clear() { reset() }

    @Synchronized fun heldBytes(): Int = bytes

    private fun reset() { buf.clear(); bytes = 0; active = false; userEndAt = 0L; userWords = false; heldComplete = false }
}
