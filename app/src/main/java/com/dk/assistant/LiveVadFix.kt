package com.dk.assistant

/**
 * 16 नवंबर 2026 (मालिक: "Live बातचीत और VAD का पूरा ऑडिट - सभी 8 सुधार"): ऑडिट के सुधारों के नियम एक जगह.
 * Android का कोई import नहीं -> JVM unit test में सीधे चलते हैं. माइक loop ([micLoop]) सिर्फ़ इन्हें बुलाता है.
 */
object LiveVadFix {
    /** S4: फ़ोन का अपना AGC चालू हो तो MicBoost इससे ज़्यादा गुना नहीं (आवाज़ दो बार तेज़ = शोर भी दो बार) */
    const val BOOST_WITH_AGC = 6.0

    /** S4: MicBoost की ऊपरी सीमा - AGC बंद = पहले जैसा [LiveHear.BOOST_MAX] */
    fun boostLimit(agcOn: Boolean): Double = if (agcOn) minOf(BOOST_WITH_AGC, LiveHear.BOOST_MAX) else LiveHear.BOOST_MAX

    /**
     * S5: Silero VAD चलना था पर नहीं चला (model नहीं खुला / बार-बार गलती) = सिर्फ़ RMS. तब "बोलना शुरू" का दरवाज़ा
     * इतना गुना ऊँचा (1.7x शोर -> ~2.1x, 150 -> ~190 RMS) - दरवाज़ा Silero के भरोसे नीचा किया गया था.
     */
    const val RMS_ONLY_SCALE = 1.25

    fun rmsOnlyScale(smartVadWanted: Boolean): Double = if (smartVadWanted) RMS_ONLY_SCALE else 1.0

    /**
     * S6: 0.36s वाला जल्दी "बात खत्म" - caption कम से कम इतनी देर से वैसा ही हो (पहले 200ms)
     */
    const val EARLY_SETTLE_MS = 300L

    /**
     * S6: आपकी आदत के ठहराव (typicalPauseMs) 0.36s से लंबे हों = आप वाक्यों के बीच ज़्यादा रुकते हैं. तब जल्दी "खत्म"
     * के लिए भी उतनी चुप्पी (+60ms), पर [GeminiLive.CLIENT_END_MS] से ज़्यादा नहीं (आम बात से धीमा कभी नहीं).
     */
    fun earlySilenceMs(typicalPauseMs: Int, clientEndMs: Int): Int =
        if (typicalPauseMs <= 0) TurnEnd.EARLY_END_MS
        else maxOf(TurnEnd.EARLY_END_MS, minOf(typicalPauseMs + 60, clientEndMs))

    fun earlySafe(silentMs: Int, typicalPauseMs: Int, clientEndMs: Int): Boolean =
        silentMs >= earlySilenceMs(typicalPauseMs, clientEndMs)

    /** S7: हर बात की एक लाइन वाली VAD रिपोर्ट (log) - शुरू, खत्म, किस नियम से, कितनी देर */
    fun summary(rule: String, speechMs: Int, silentMs: Int, needMs: Int, vadOn: Boolean, heardSrc: String,
                noise: Double, fromWake: Boolean): String =
        "VAD सारांश: बोलना ${speechMs} ms • खत्म-नियम=$rule • चुप्पी ${silentMs}/${needMs} ms • " +
            "VAD=${if (vadOn) "Silero" else "सिर्फ़ RMS"} • लिखा=$heardSrc • शोर=${noise.toInt()}" +
            if (fromWake) " • वेक वर्ड के बाद की बात" else ""
}

/**
 * S2 (ऑडिट G1): DK speaker पर बोल रहा है (कॉल-mode नहीं) और आप बीच में टोकते हैं - पहले caption के शब्दों का इंतज़ार
 * (0.7-1.5s). अब: Silero पक्का इंसानी आवाज़ (0.85+) और आवाज़ DK की गूँज से बहुत तेज़ (2.2x+) लगातार [CONFIRM_MS] = तुरंत रुको.
 * DK की अपनी गूँज भी "इंसानी आवाज़" है, इसलिए दरवाज़ा [DuplexBarge] से सख़्त.
 * बचाव: रोका पर [WORDS_WAIT_MS] में कोई शब्द नहीं आया = गलत रोका.
 * 18 नवंबर 2026 (मालिक: "बीच में बोलूँ तो रुकता नहीं"): पहले 2 गलत रोक = पूरे session में बंद (फिर टोकना 1-1.5s वाला caption
 * तरीका ही). अब बंद नहीं - हर गलत रोक पर सीमा [STRICT_STEP] सख़्त ([STRICT_MAX] तक), सही रोक पर धीरे-धीरे वापस.
 * [scale] = [MicStyle] (धीरे / दूर से बोलने वाले के लिए नीची सीमा).
 */
class SpeakerBarge {
    companion object {
        const val SPEECH_P = 0.85f
        const val ECHO_X = 2.2
        const val AMBIENT_X = 3.0
        const val MIN_RMS = 1200.0
        const val CONFIRM_MS = 320L
        const val WORDS_WAIT_MS = 2_500L
        const val STRICT_STEP = 0.25
        const val STRICT_MAX = 1.75
        const val PREF = "vad_fast_barge"
    }

    var speechMs = 0L; private set
    var confirms = 0; private set
    var misses = 0; private set
    /** गलत रोक के बाद की सख़्ती (1.0 = आम). कभी बंद नहीं होता */
    var strict = 1.0; private set
    /** [MicStyle] का गुणक (Settings → "आप कैसे बोलते हैं") */
    var scale = 1.0
    private var pendingAt = 0L

    fun reset() { speechMs = 0L }
    fun resetSession() { reset(); confirms = 0; misses = 0; strict = 1.0; pendingAt = 0L }

    /** आवाज़ DK की गूँज और पीछे के शोर से इतनी ऊपर = पास से कोई बोल रहा */
    fun loudOver(rms: Double, echo: Double, ambient: Double): Boolean =
        rms > maxOf(echo * ECHO_X, ambient * AMBIENT_X, MIN_RMS) * strict * scale

    /** DK बोलते समय (शक वाली हालत) हर chunk: true = अभी रोको */
    fun feed(prob: Float, rms: Double, echo: Double, ambient: Double, chunkMs: Long): Boolean {
        if (prob < 0f) { speechMs = 0L; return false }
        speechMs = if (prob >= SPEECH_P && loudOver(rms, echo, ambient)) speechMs + chunkMs else 0L
        return speechMs >= CONFIRM_MS
    }

    fun onConfirm(now: Long) { confirms++; pendingAt = now; speechMs = 0L }

    /** रोकने के बाद शब्द आए = सही रोका -> सख़्ती धीरे-धीरे वापस */
    fun onWords() {
        if (pendingAt != 0L) strict = maxOf(1.0, strict - STRICT_STEP / 2)
        pendingAt = 0L
    }

    /** हर chunk. true = अभी-अभी गलत रोक मानी, सीमा सख़्त हुई (log के लिए) */
    fun tick(now: Long): Boolean {
        if (pendingAt == 0L || now - pendingAt < WORDS_WAIT_MS) return false
        pendingAt = 0L; misses++
        strict = minOf(STRICT_MAX, strict + STRICT_STEP)
        return true
    }
}

/**
 * S3 (ऑडिट G2): caption (दूसरा connection) जुड़ा दिखता है पर लिखना बंद कर चुका (limit / धीमा) - तब भी हर बात पर
 * उसका इंतज़ार होता था (0.7s तक "बात खत्म", टोकने में 1s). अब: [NEED_MISSES] लंबी बातें लगातार बिना caption के एक भी
 * शब्द = caption "चुप" माना जाए -> Live का अपना लिखा + बिना caption वाले नियम. caption फिर लिखे तो अपने-आप वापस.
 */
class CaptionHealth(
    private val needMisses: Int = NEED_MISSES,
    private val minSpeechMs: Int = MIN_SPEECH_MS,
    private val waitMs: Long = WAIT_MS,
) {
    companion object {
        const val NEED_MISSES = 2
        /** इससे छोटी बात (खटखट / "हम्म") पर caption का चुप रहना आम है - गिनो मत */
        const val MIN_SPEECH_MS = 700
        /** बात खत्म होने के बाद caption को इतना समय (वह आवाज़ से ~0.5s पीछे चलता है) */
        const val WAIT_MS = 2_500L
    }

    private val lock = Any()
    private var pendStart = 0L
    private var pendEnd = 0L
    var misses = 0; private set
    @Volatile var staleAt = 0L; private set

    fun reset() = synchronized(lock) { pendStart = 0L; pendEnd = 0L; misses = 0; staleAt = 0L }

    /** असली बात खत्म (Gemini/caption को आवाज़ गई थी) */
    fun onSpeechEnd(startAt: Long, speechMs: Int, now: Long) = synchronized(lock) {
        if (speechMs >= minSpeechMs && startAt > 0L) { pendStart = startAt; pendEnd = now }
    }

    /** हर chunk: पिछली बात पर caption ने लिखा? true = अभी-अभी "चुप" माना (log के लिए) */
    fun tick(capTextAt: Long, now: Long): Boolean = synchronized(lock) {
        if (pendEnd == 0L) return false
        if (capTextAt >= pendStart) { misses = 0; pendEnd = 0L; return false }
        if (now - pendEnd < waitMs) return false
        pendEnd = 0L; misses++
        if (misses >= needMisses && staleAt == 0L) { staleAt = now; return true }
        false
    }

    /** caption अभी "चुप" है? (चुप माने जाने के बाद नया text = फिर से चालू) */
    fun stale(capTextAt: Long): Boolean = synchronized(lock) {
        if (staleAt > 0L && capTextAt > staleAt) { staleAt = 0L; misses = 0 }
        staleAt > 0L
    }
}
