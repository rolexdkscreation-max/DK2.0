package com.dk.assistant.voice

/**
 * v3.66: एक बात (बोलना शुरू -> खत्म) के लिए फ़ैसला - आवाज़ मालिक की है या किसी और की।
 *
 * तेज़ रहने का तरीका: आवाज़ Gemini को उसी पल जाती रहती है (कोई रुकावट नहीं)। साथ-साथ पहली ~1 सेकंड आवाज़
 * मिलाई जाती है (~15-60 ms)। मालिक = कुछ नहीं बदलता। कोई और = बाकी आवाज़ बंद + उस बात का जवाब न बजे, न कोई काम हो।
 * छोटी बात (1 सेकंड से कम) = बोलना खत्म होते ही जितनी आवाज़ है उसी से (जवाब आने से पहले)।
 *
 * शुद्ध Kotlin: मिलान (score) बाहर से आता है, यहाँ सिर्फ़ फ़ैसले के नियम।
 */
class OwnerGate(private val chunkMs: Int = 40) {
    enum class Verdict { PENDING, OWNER, OTHER }

    companion object {
        const val RATE = 16000
        /** पहला मिलान इतनी आवाज़ पर */
        const val FIRST_CHECK_MS = 1000
        /** पक्का न हो (बीच का score) तो दूसरा मिलान इतनी आवाज़ पर */
        const val SECOND_CHECK_MS = 2000
        /** इससे छोटी आवाज़ पर मिलान नहीं (खटखट) */
        const val MIN_CHECK_MS = 300
        /** इससे छोटी आवाज़ पर score अपने-आप कम आता है - सीमा थोड़ी ढीली */
        const val SHORT_MS = 800
        const val SHORT_SLACK = 0.08f
        /** score सीमा से इतना नीचे = पहली बार में ही "कोई और" */
        const val CLEAR_OTHER = 0.15f
        /** मालिक अभी-अभी (इतनी देर में) पहचाना गया = बातचीत चल रही, बीच वाले score को मालिक मानो */
        const val RECENT_OWNER_MS = 20_000L
        const val RECENT_SLACK = 0.10f
        /** 3 सेकंड से ज़्यादा जमा नहीं */
        const val MAX_MS = 3000
    }

    var verdict = Verdict.PENDING
        private set
    var active = false
        private set
    var lastScore = Float.NaN
        private set
    var checks = 0
        private set
    @Volatile var checking = false
        private set
    var lastOwnerAt = 0L
        private set
    /** इस बात की आवाज़ (मिलान के लिए) */
    private val maxBytes = RATE * 2 * MAX_MS / 1000
    private val buf = ByteArray(maxBytes)
    private var len = 0
    var speechMs = 0
        private set
    private val rmsList = ArrayList<Double>(80)

    /** बोलना शुरू: पहले के (preroll) टुकड़े भी मिलान में */
    fun start(preroll: Collection<ByteArray> = emptyList()) {
        active = true; verdict = Verdict.PENDING; checks = 0; checking = false; lastScore = Float.NaN
        len = 0; speechMs = 0; rmsList.clear()
        for (p in preroll) append(p)
    }

    private fun append(b: ByteArray) {
        val n = minOf(b.size, maxBytes - len)
        if (n > 0) { System.arraycopy(b, 0, buf, len, n); len += n }
    }

    /** @return true = अभी मिलान करो (takeAudio() से आवाज़ लो) */
    fun add(chunk: ByteArray, rms: Double): Boolean {
        if (!active) return false
        append(chunk); speechMs += chunkMs
        if (rmsList.size < 80) rmsList.add(rms)
        return wantCheck()
    }

    fun wantCheck(): Boolean = active && verdict == Verdict.PENDING && !checking &&
        ((checks == 0 && speechMs >= FIRST_CHECK_MS) || (checks == 1 && speechMs >= SECOND_CHECK_MS))

    /** मिलान के लिए आवाज़ की कॉपी (checking = true, onScore तक दोबारा नहीं) */
    fun takeAudio(): ByteArray { checking = true; return buf.copyOf(len) }

    fun audioMs(): Int = len * 1000 / (RATE * 2)

    fun rms(): List<Double> = rmsList.toList()

    /**
     * मिलान का नतीजा। @param atEnd बोलना खत्म हो चुका (अब और आवाज़ नहीं आएगी - फ़ैसला पक्का)
     * @param ms कितनी आवाज़ पर score बना
     */
    fun onScore(score: Float, threshold: Float, now: Long, ms: Int, atEnd: Boolean): Verdict {
        checking = false
        if (!active || verdict != Verdict.PENDING) return verdict
        checks++
        lastScore = score
        val th = if (ms < SHORT_MS) threshold - SHORT_SLACK else threshold
        val recent = lastOwnerAt > 0 && now - lastOwnerAt < RECENT_OWNER_MS
        verdict = when {
            !score.isFinite() -> Verdict.OWNER          // मिलान ही नहीं बना = पहले जैसा (आवाज़ मत खोओ)
            score >= th -> Verdict.OWNER
            recent && score >= th - RECENT_SLACK -> Verdict.OWNER
            atEnd || checks >= 2 || score < th - CLEAR_OTHER -> Verdict.OTHER
            else -> Verdict.PENDING
        }
        if (verdict == Verdict.OWNER) lastOwnerAt = now
        return verdict
    }

    /** मिलान न हो पाया (मॉडल गड़बड़) = मालिक मानो, आवाज़ न खोए */
    fun onFailed(now: Long) { checking = false; if (active && verdict == Verdict.PENDING) { verdict = Verdict.OWNER } }

    /**
     * बोलना खत्म। @return true = अभी आखिरी मिलान ज़रूरी (फ़ैसला बाकी है और आवाज़ काफ़ी है)
     * बहुत छोटी आवाज़: बातचीत चल रही हो तो मालिक, वरना कोई और (खटखट/दूर की "हाँ")
     */
    fun needFinalCheck(now: Long): Boolean {
        if (!active || verdict != Verdict.PENDING) return false
        if (audioMs() >= MIN_CHECK_MS) return true
        verdict = if (lastOwnerAt > 0 && now - lastOwnerAt < RECENT_OWNER_MS) Verdict.OWNER else Verdict.OTHER
        return false
    }

    /** बात पूरी तरह खत्म - अगली बात नए सिरे से */
    fun finish() { active = false; checking = false; len = 0 }

    /** सब भूलो (सत्र बंद / आवाज़ फिर से सीखी) */
    fun reset() { finish(); verdict = Verdict.PENDING; lastOwnerAt = 0L; lastScore = Float.NaN }
}
