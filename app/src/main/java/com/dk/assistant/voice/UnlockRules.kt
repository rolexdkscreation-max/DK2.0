package com.dk.assistant.voice

/**
 * 16 नवंबर 2026 (मालिक: "आवाज़ से फ़ोन अनलॉक वाली गड़बड़ी सही करो - पर हर बार अलग random शब्द नहीं, आवाज़ मिलान रखो").
 *
 * जड़ (ऑडिट G1): PIN डालने से पहले यह पक्का नहीं होता था कि बोलने वाला मालिक ही है -
 *  - लिखी बात वाले रास्ते ("DK फोन अनलॉक करो" - Live / आम सुनना / बिना इंटरनेट) में आवाज़-पहचान थी ही नहीं:
 *    कोई भी यह वाक्य बोले = PIN डल जाता.
 *  - AI का कोई भी "unlock" काम (स्क्रीन / नोटिफ़िकेशन की बाहर की लिखावट से बहका AI भी) सीधे PIN डालता था.
 *  - आवाज़-कमांड वाले रास्ते में पहचान थी, पर पुराने CAM++ इंजन से और पुरानी रिकॉर्डिंग दोबारा बजाने (replay) की कोई जाँच नहीं.
 *
 * अब PIN सिर्फ़ तब डलता है जब ये सब सही हों (Pure, JVM test: UnlockRulesTest):
 *  1. मालिक ने खुद अभी-अभी अनलॉक कहा ([INTENT_MS]) - फ़ोन के अपने सुनने वाले रास्ते ने यह वाक्य पहचाना (AI का अपना फ़ैसला नहीं).
 *  2. उसी बात की आवाज़ वॉइस गार्डियन (RedimNet) से कड़ी सीमा पर मालिक की ([threshold]) - आम सीमा से [TH_EXTRA] ऊपर.
 *  3. आवाज़ किसी पहले की (सीखते समय की या पिछले अनलॉक की) रिकॉर्डिंग जैसी हूबहू नहीं ([REPLAY_COS]) - वही रिकॉर्डिंग दोबारा बजाना पकड़ में.
 *  4. लगातार [MAX_REJECTS] बार आवाज़ न मिली = आवाज़ से अनलॉक [PAUSE_MS] के लिए रुका (कोई रिकॉर्डिंग आज़माता रहे तो).
 */
object UnlockRules {
    /** आम ("सिर्फ़ मेरी आवाज़") सीमा से इतना ऊपर */
    const val TH_EXTRA = 0.10f
    /** कम से कम इतना (Settings में सीमा ढीली हो तब भी) */
    const val TH_FLOOR = 0.60f
    const val TH_CAP = 0.85f

    /** इतना हूबहू मिलना इंसान की दोबारा बोली बात में नहीं होता - यह वही रिकॉर्डिंग है */
    const val REPLAY_COS = 0.97f
    /** पिछले इतने सफल अनलॉक की पहचान याद (सिर्फ़ इसी फ़ोन में, ताले में) */
    const val SEEN_MAX = 12

    /** "अनलॉक करो" कहे इतनी देर के अंदर ही PIN */
    const val INTENT_MS = 20_000L
    /** मिलाई जाने वाली बात अनलॉक कहने से इतना पहले तक की हो */
    const val UTTER_BEFORE_MS = 10_000L
    /** बात अभी चल रही हो (caption ने बीच में पकड़ा) तो खत्म होने का इतना इंतज़ार */
    const val UTTER_WAIT_MS = 3_000L
    /** इससे छोटी आवाज़ पर पहचान भरोसे की नहीं */
    const val MIN_MS = 600

    const val MAX_REJECTS = 3
    const val REJECT_WINDOW_MS = 10 * 60_000L
    const val PAUSE_MS = 30 * 60_000L

    fun threshold(base: Float): Float {
        val b = if (base.isFinite()) base else TH_FLOOR
        return (b + TH_EXTRA).coerceAtLeast(TH_FLOOR).coerceAtMost(TH_CAP)
    }

    fun isReplay(e: FloatArray, seen: List<FloatArray>): Boolean =
        seen.any { it.size == e.size && VoicePrint.cosine(it, e) >= REPLAY_COS }

    enum class Verdict(val ok: Boolean, val hi: String) {
        OK(true, "आवाज़ आपकी"),
        NO_INTENT(false, "आपने खुद अनलॉक नहीं कहा"),
        NO_VOICE(false, "अनलॉक कहने वाली आवाज़ नहीं मिली"),
        SHORT(false, "आवाज़ बहुत छोटी"),
        NOT_READY(false, "वॉइस गार्डियन में आवाज़ सिखाई नहीं / मॉडल नहीं"),
        LOW(false, "आवाज़ आपकी नहीं लगी"),
        REPLAY(false, "पहले की रिकॉर्डिंग जैसी हूबहू आवाज़ - दोबारा बजाई लगती है"),
        PAUSED(false, "कई बार आवाज़ न मिलने से आवाज़ से अनलॉक कुछ देर रुका"),
    }

    /** एक बात का फ़ैसला. score = null: पहचान नहीं हो पाई (मॉडल / सीखी आवाज़ नहीं) */
    fun judge(score: Float?, th: Float, replay: Boolean, ms: Int): Verdict = when {
        ms < MIN_MS -> Verdict.SHORT
        score == null || !score.isFinite() -> Verdict.NOT_READY
        replay -> Verdict.REPLAY
        score < th -> Verdict.LOW
        else -> Verdict.OK
    }

    /** मिलाई जाने वाली बात समय में ठीक है? (अनलॉक कहने से [UTTER_BEFORE_MS] पहले से, बाद तक) */
    fun utterFits(utterAt: Long, askedAt: Long): Boolean = utterAt >= askedAt - UTTER_BEFORE_MS

    /** लगातार गलत आवाज़ की गिनती (Pure) */
    class Strikes {
        private val fails = ArrayDeque<Long>()
        var pausedUntil = 0L
            private set

        @Synchronized fun paused(now: Long) = now < pausedUntil
        @Synchronized fun fail(now: Long) {
            while (fails.isNotEmpty() && now - fails.first() > REJECT_WINDOW_MS) fails.removeFirst()
            fails.addLast(now)
            if (fails.size >= MAX_REJECTS) { pausedUntil = now + PAUSE_MS; fails.clear() }
        }
        @Synchronized fun ok() { fails.clear() }
        @Synchronized fun reset() { fails.clear(); pausedUntil = 0L }
    }

    /** मालिक को बोला जाने वाला कारण (PIN नहीं डाला) */
    fun reason(v: Verdict): String =
        "${com.dk.assistant.PhoneLockVoice.UNLOCK_FAIL} सुरक्षा: ${v.hi}, इसलिए PIN नहीं डाला। फ़ोन खुद खोलिए।"
}
