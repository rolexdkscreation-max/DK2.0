package com.dk.assistant

/**
 * 15 नवंबर 2026 (मालिक: "वॉचमैन की जानकारी सिर्फ़ Boss को. फ़ेस वेरिफ़िकेशन और भी सख़्त करो - पूछने पर DK ख़ुद स्कैन खोले,
 * Boss हो तो ही बताए, नहीं तो नहीं").
 *
 * टच मोड वाली जाँच से भी सख़्त - सब एक साथ पास हों तभी PASS:
 *  1. कैमरे में **एक ही** चेहरा (कोई Boss के साथ खड़ा होकर न सुन ले / Boss की फ़ोटो पकड़कर पीछे से न देखे)
 *  2. मिलान हमेशा [FaceMath.STRICT] (Settings में "सख़्त" बंद हो तब भी) और किसी सेव किए दूसरे व्यक्ति से [MARGIN] आगे
 *  3. हर बार नए क्रम में सिर घुमाना ([FaceLiveness.challenge]) - पहले से बना वीडियो नहीं चलेगा
 *  4. AI anti-spoof ज़रूरी (Settings में बंद हो तब भी): कम से कम [SPOOF_FRAMES] फ़्रेम, [FaceSpoof.Vote.sure]
 *  5. लगातार [HITS] फ़्रेम ऊपर का सब - बीच में एक भी चूका तो गिनती फिर शून्य
 * मॉडल न हो = FAIL (बिना जाँच कभी नहीं बताता). [NOT_BOSS_FRAMES] बार साफ़ कोई और = FAIL.
 */
class BossGate {
    data class Frame(
        val faces: Int, val score: Float?, val otherBest: Float?, val live: Boolean, val front: Boolean,
        val spoofReady: Boolean, val spoofCount: Int, val spoofSure: Boolean, val spoofFake: Boolean,
    )
    enum class State { WAIT, PASS, FAIL }
    data class Verdict(val state: State, val why: String)

    private var hits = 0
    private var notBoss = 0
    fun hits() = hits

    fun feed(f: Frame): Verdict {
        fun wait(w: String): Verdict { hits = 0; return Verdict(State.WAIT, w) }
        if (!f.spoofReady) return Verdict(State.FAIL, "AI anti-spoof मॉडल तैयार नहीं — बिना पूरी जाँच के रिपोर्ट नहीं")
        if (f.faces == 0) return wait("कैमरे में सीधे देखिए")
        if (f.faces > 1) return wait("सिर्फ़ Boss अकेले कैमरे के सामने रहें — ${f.faces} चेहरे दिख रहे हैं")
        if (f.spoofFake) return Verdict(State.FAIL, "फ़ोटो / वीडियो / स्क्रीन पकड़ी गई — असली इंसान नहीं")
        val s = f.score ?: return wait("चेहरा पढ़ रही हूँ…")
        if (s < FaceMath.MATCH * 0.8f) {
            notBoss++
            if (notBoss >= NOT_BOSS_FRAMES) return Verdict(State.FAIL, "यह चेहरा Boss का नहीं (${(s * 100).toInt()}%)")
            return wait("यह चेहरा Boss से नहीं मिल रहा")
        }
        notBoss = 0
        if (s < FaceMath.STRICT) return wait("मिलान ${(s * 100).toInt()}% — सख़्त जाँच में ${(FaceMath.STRICT * 100).toInt()}% चाहिए, रोशनी में सीधे देखिए")
        if (f.otherBest != null && s - f.otherBest < FacePeople.MARGIN) return wait("चेहरा किसी और सेव किए व्यक्ति से भी मिल रहा है — पक्का नहीं")
        if (!f.live) return wait("")
        if (!f.front) return wait("अब सीधे कैमरे में आँख मिलाइए")
        if (f.spoofCount < SPOOF_FRAMES || !f.spoofSure) return wait("AI जाँच रहा है: असली इंसान या फ़ोटो / वीडियो (${f.spoofCount}/$SPOOF_FRAMES)…")
        hits++
        return if (hits >= HITS) Verdict(State.PASS, "BOSS पक्का (${(s * 100).toInt()}%)") else Verdict(State.WAIT, "✓ Boss — पक्का कर रही हूँ ($hits/$HITS)")
    }

    companion object {
        const val HITS = 3
        const val SPOOF_FRAMES = 8
        const val SPOOF_WINDOW = 12
        const val NOT_BOSS_FRAMES = 4
        const val TIMEOUT_MS = 35_000L
        /** एक बार पास = इतनी देर दोबारा स्कैन नहीं ("और बताओ") */
        const val GRACE_MS = 60_000L
    }
}
