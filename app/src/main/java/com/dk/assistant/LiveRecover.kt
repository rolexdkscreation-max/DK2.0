package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "बाद में इसकी दिक्कत न हो" - DKI-406DD9 / DKI-70D747 / DKI-D24C3A):
 * Live का connection टूटे, session अंदर से अटक जाए (server बिल्कुल चुप) या कोई मॉडल setup पर अटके -
 * तीनों में यूज़र को रुकावट न दिखे. Android import नहीं = पूरा unit test.
 *
 *  1. [PING_S]: पहले हर 5s ping और 5s में pong नहीं = connection खुद तोड़ते थे (326 बार "ping/pong timeout").
 *     Google के अपने SDK (Python websockets) 20s, browser वाले ऐप ping भेजते ही नहीं. अब 15s (caption जैसा).
 *  2. [zombie]: बात भेजी, पर उस पल से server से एक भी संदेश नहीं (न लिखावट, न VAD, न जवाब) = session अंदर से अटका
 *     (Google forum पर 3.1 / 3.8 Live की जानी-मानी खराबी). पहले यह बचाव सिर्फ़ "बचाव-text" चालू होने पर था (डिफ़ॉल्ट बंद).
 *     अब हमेशा: नया connection और आपकी वही आवाज़ ([Utterance]) दोबारा - text नहीं (text = 1011).
 *  3. [Demote]: एक मॉडल पर socket खुला पर setup का जवाब नहीं (लगातार 2 बार) = 30 मिनट उसे दौड़ में सबसे पीछे.
 *     एक key वाले यूज़र पर दौड़ में एक समय एक ही जोड़ी चलती है - अटका मॉडल हर बार पहले = हर बार 10s+ इंतज़ार.
 */
object LiveRecover {
    /** WebSocket ping का अंतर (और pong का इंतज़ार - OkHttp में दोनों बराबर) */
    const val PING_S = 15L
    /** बात खत्म होने के इतनी देर बाद तक server से एक भी संदेश नहीं = अटका session (आम पहला संदेश < 1.5s) */
    const val ZOMBIE_MS = 4_000L
    /** caption में शब्द नहीं तो कम से कम इतनी असली आवाज़ (खटखट पर connection नहीं बदलते) */
    const val MIN_SPEECH_NO_WORDS_MS = 1_500
    /** caption में शब्द हैं तो इतनी आवाज़ काफ़ी */
    const val MIN_SPEECH_MS = 300
    /** इतनी देर में ज़्यादा से ज़्यादा [MAX_SWAPS] बार connection बदलो (चक्कर न बने) */
    const val SWAP_WINDOW_MS = 60_000L
    const val MAX_SWAPS = 2
    /** टूटने/बदलने के बाद इतनी देर में फिर जुड़ा = यूज़र के लिए रुकावट नहीं (Inspector भी इसे गलती नहीं गिनता) */
    const val RECOVER_MS = 12_000L
    /** इससे लंबा टूटा रहा = यूज़र को सच में रुकावट (Inspector में गिना जाए) */
    const val OUTAGE_LOG_MS = 15_000L
    /** Inspector का निशान: इसके बाद आई "टूटा/अटका" लाइनें DK ने खुद संभाल लीं */
    const val OK_MARK = "Live संभल गया"

    /**
     * true = यह session अटका है, नया connection चाहिए.
     * @param endpointAt बात खत्म होने का पल (0 = कोई बात इंतज़ार में नहीं)
     * @param eventAt उस बात पर server का पहला event (0 = कुछ नहीं)
     * @param lastAnyAt server का आखिरी कोई भी संदेश (VAD / लिखावट / resumption भी)
     * @param busy DK बोल रहा / जवाब बन रहा / फ़ोन का काम / यूज़र अभी बोल रहा / आवाज़ रोकी हुई (मालिक-जाँच)
     */
    fun zombie(now: Long, endpointAt: Long, eventAt: Long, lastAnyAt: Long, busy: Boolean, speechMs: Int, words: Boolean): Boolean {
        if (busy || endpointAt <= 0L || eventAt != 0L) return false
        if (lastAnyAt >= endpointAt) return false
        if (now - endpointAt < ZOMBIE_MS) return false
        return if (words) speechMs >= MIN_SPEECH_MS else speechMs >= MIN_SPEECH_NO_WORDS_MS
    }

    /** [MAX_SWAPS] / [SWAP_WINDOW_MS] की सीमा - true = अभी बदल सकते हैं (और गिनती में जुड़ गया) */
    class SwapLimit(private val max: Int = MAX_SWAPS, private val windowMs: Long = SWAP_WINDOW_MS) {
        private val at = ArrayDeque<Long>()
        @Synchronized fun tryTake(now: Long): Boolean {
            while (at.isNotEmpty() && now - at.first() > windowMs) at.removeFirst()
            if (at.size >= max) return false
            at.addLast(now); return true
        }
        /** इस खिड़की में पहले भी बदला था = अटका सत्र फिर अटका, अगली बार नई बातचीत */
        @Synchronized fun recent(now: Long): Int = at.count { now - it <= windowMs }
        @Synchronized fun reset() = at.clear()
    }

    /**
     * यूज़र की अभी की बात की आवाज़ (जो सच में server को गई) - session अटके तो नए connection पर यही दोबारा.
     * असली आवाज़ से शुरू; बीच की छोटी चुप्पी साथ; [gapMs] से लंबी चुप्पी के बाद नई आवाज़ = नई बात (पुरानी हटी).
     */
    class Utterance(private val chunkMs: Int = 40, private val maxMs: Int = 15_000, private val gapMs: Long = 2_500) {
        private val chunks = ArrayDeque<ByteArray>()
        private var realChunks = 0
        private var lastRealAt = 0L
        private var startAt = 0L

        @Synchronized fun add(pcm: ByteArray, real: Boolean, now: Long) {
            if (real) {
                if (realChunks > 0 && now - lastRealAt > gapMs) clearLocked()
                if (chunks.isEmpty()) startAt = now
                lastRealAt = now; realChunks++
            } else if (chunks.isEmpty()) return          // बात से पहले की चुप्पी नहीं
            chunks.addLast(pcm)
            while (chunks.size * chunkMs > maxMs) chunks.removeFirst()
        }

        /** असली आवाज़ कितनी (ms) */
        @Synchronized fun speechMs(): Int = realChunks * chunkMs
        @Synchronized fun lastRealAt(): Long = lastRealAt

        /** दोबारा भेजने के लिए (बहुत पुरानी बात = कुछ नहीं) */
        @Synchronized fun snapshot(now: Long, maxAgeMs: Long = 20_000): List<ByteArray> =
            if (chunks.isEmpty() || now - lastRealAt > maxAgeMs) emptyList() else chunks.toList()

        /** server ने इस बात पर कुछ भेजा (सुना) = अब दोबारा भेजने की ज़रूरत नहीं */
        @Synchronized fun clear() = clearLocked()
        private fun clearLocked() { chunks.clear(); realChunks = 0; lastRealAt = 0L; startAt = 0L }
    }

    /** दोबारा भेजने के बाद इतनी चुप्पी - server का VAD बात को खत्म माने */
    const val REPLAY_TAIL_MS = 800

    /** मॉडल setup पर अटके (socket खुला, setupComplete नहीं) तो सबसे पीछे */
    object Demote {
        const val STREAK = 2
        const val DEMOTE_MS = 30 * 60_000L
        private val streak = HashMap<String, Int>()
        private val until = HashMap<String, Long>()

        /** true = अभी पीछे किया गया (log के लिए) */
        @Synchronized fun setupHung(model: String, now: Long): Boolean {
            val n = (streak[model] ?: 0) + 1
            streak[model] = n
            if (n >= STREAK && (until[model] ?: 0L) <= now) { until[model] = now + DEMOTE_MS; return true }
            return false
        }
        @Synchronized fun ok(model: String) { streak.remove(model); until.remove(model) }
        @Synchronized fun demoted(model: String, now: Long): Boolean = (until[model] ?: 0L) > now
        /** पीछे किए मॉडल सबसे आख़िर में (हटाए नहीं - वही अकेला बचा हो तो भी चले) */
        fun <T> order(items: List<T>, now: Long, modelOf: (T) -> String): List<T> {
            val (bad, good) = items.partition { demoted(modelOf(it), now) }
            return good + bad
        }
        @Synchronized fun reset() { streak.clear(); until.clear() }
    }
}
