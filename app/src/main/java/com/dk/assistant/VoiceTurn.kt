package com.dk.assistant

import kotlin.math.max

/**
 * Phone par hi (client-side) aawaaz ki pehchaan - v3.8.
 *
 * Gemini Live ka server VAD chalu rehta hai (bolna SHURU pakadne ke liye sabse bharosemand).
 * Yeh class sirf do kaam karti hai, har 40ms ke mic tukde (RMS) par:
 *  1. SPEECH_START - user bolne laga  -> orb turant "Listening"
 *  2. SPEECH_END   - user ~500ms se chup -> turant `audioStreamEnd` bhejo (server apna chup-intezaar chhod kar
 *                    turant jawab banata hai = "hybrid VAD", Google docs) + orb "Thinking"
 *
 * Android ka koi import nahi -> JVM unit test me seedha chalti hai.
 */
class TurnDetector(
    private val chunkMs: Int = 40,
    /** Itni der chup = baat khatam. Google: 500ms se kam = beech ke chhote ruke par baat kat-ti hai */
    private val endSilenceMs: Int = 500,
    /** Itna bolna zaroori (khaansi/thak-thak ko baat mat samjho) */
    private val minSpeechMs: Int = 200,
    /** Itni der lagatar awaaz = bolna shuru */
    private val startMs: Int = 80,
    /** Shor se kitna tez = bolna (v3.9: thoda neecha - dheemi awaaz bhi; shor seekha jaata hai) */
    private val startFactor: Double = 2.5,
    private val startMin: Double = 300.0,
) {
    enum class Event { NONE, SPEECH_START, SPEECH_END }

    /** Peeche ka shor (pankha/TV) - dheere-dheere seekhta hai */
    var noise = 250.0
        private set
    var inSpeech = false
        private set
    private var startRun = 0
    private var speechMs = 0
    private var silenceMs = 0
    /** Aakhri baar asli awaaz kab thi (ms, feed() ke `now` ke hisaab se) - deri naapne ke liye */
    var lastVoiceAt = 0L
        private set

    fun reset() { inSpeech = false; startRun = 0; speechMs = 0; silenceMs = 0 }

    /** DK ko toka (pakka) - user pehle se bol raha hai: seedha "bol raha" haalat (gate khula rahe) */
    fun forceSpeech(now: Long) { inSpeech = true; startRun = 0; speechMs = minSpeechMs; silenceMs = 0; lastVoiceAt = now }

    fun feed(rms: Double, now: Long): Event {
        val startThr = max(noise * startFactor, startMin)
        // bolte samay dheemi awaaz (shabd ke aakhri hisse) ko chup mat samjho: neecha darwaza (hysteresis)
        val keepThr = max(noise * 1.6, 220.0)
        if (!inSpeech) {
            if (rms > startThr) {
                startRun++
                if (startRun * chunkMs >= startMs) {
                    inSpeech = true; speechMs = startRun * chunkMs; silenceMs = 0; startRun = 0; lastVoiceAt = now
                    return Event.SPEECH_START
                }
            } else {
                startRun = 0
                noise = (noise * 0.95 + rms * 0.05).coerceIn(50.0, 3000.0)
            }
            return Event.NONE
        }
        if (rms > keepThr) { silenceMs = 0; speechMs += chunkMs; lastVoiceAt = now; return Event.NONE }
        silenceMs += chunkMs
        if (silenceMs < endSilenceMs) return Event.NONE
        val real = speechMs >= minSpeechMs
        reset()
        return if (real) Event.SPEECH_END else Event.NONE
    }
}

/**
 * DK bol raha hai aur user beech me bola? (barge-in)
 * DK ki apni goonj (echo) seekhta hai; usse kaafi tez awaaz lagatar 3 tukde (120ms) = user bol raha hai.
 */
class BargeDetector(
    /** DK ki awaaz shuru hote hi goonj badhti hai - itni der sirf seekho */
    private val learnMs: Long = 400,
    /** v3.9: 6 x 40ms = 240ms lagatar tez awaaz (thak/khatak/door ki awaaz nahi) */
    private val needChunks: Int = 6,
    private val minRms: Double = 1200.0,
) {
    /** DK bolne se pehle kamre ka shor (TurnDetector.noise) - isse kaafi tez hi user maana jaye */
    var ambient = 0.0
    private var echo = 0.0
    private var loud = 0
    private var since = 0L
    var triggered = false
        private set

    fun reset() { echo = 0.0; loud = 0; since = 0L; triggered = false }

    /** Galat alarm tha (shor): dobara suno, par ab is shor ko "goonj" maan kar (wahi shor dobara na roke) */
    fun rearm(noiseRms: Double) { loud = 0; triggered = false; echo = max(echo, noiseRms * 0.6) }

    /** true = abhi-abhi user ki awaaz pakdi (sirf ek baar, jab tak reset na ho) */
    fun feed(rms: Double, now: Long): Boolean {
        if (triggered) return false
        if (since == 0L) since = now
        if (now - since < learnMs) { loud = 0; echo = max(echo * 0.8, rms); return false }
        if (rms > maxOf(echo * 2.5, ambient * 3.5, minRms)) loud++
        else { loud = 0; echo = if (echo == 0.0) rms else echo * 0.9 + rms * 0.1 }
        if (loud >= needChunks) { triggered = true; return true }
        return false
    }

    fun echoLevel() = echo
}

/**
 * Ek baari (turn) ki deri ke hisse - kahan der ho rahi hai yeh alag-alag dikhe:
 *  STT/turn  = user chup -> humne "baat khatam" bheja (client VAD) ya server ne pakda
 *  network   = bheja hua audio abhi phone me atka (upload backlog) + RTT
 *  generation= baat khatam -> Gemini ka pehla audio tukda phone tak
 *  playback  = pehla tukda aaya -> speaker me likha gaya
 */
class LatencyTrace {
    @Volatile var speechEndAt = 0L      // user ki aakhri asli awaaz
    @Volatile var endpointAt = 0L       // audioStreamEnd bheja / server ACTIVITY_END
    @Volatile var backlogBytes = 0L     // endpoint ke waqt WebSocket me atka data
    @Volatile var firstChunkAt = 0L
    @Volatile var firstPlayAt = 0L
    @Volatile var rttMs = -1L

    fun reset() { speechEndAt = 0; endpointAt = 0; backlogBytes = 0; firstChunkAt = 0; firstPlayAt = 0 }

    fun summary(): String? {
        if (speechEndAt == 0L || firstPlayAt == 0L) return null
        val ep = if (endpointAt > 0) endpointAt - speechEndAt else -1
        val gen = if (firstChunkAt > 0) firstChunkAt - (if (endpointAt > 0) endpointAt else speechEndAt) else -1
        val play = if (firstChunkAt > 0) firstPlayAt - firstChunkAt else -1
        val total = firstPlayAt - speechEndAt
        val net = (if (rttMs >= 0) "RTT~${rttMs}ms" else "RTT ?") + (if (backlogBytes > 0) ", अटका ${backlogBytes / 1024}KB" else "")
        return "कुल ${total}ms = बोलना-खत्म-पहचान ${ep}ms + generation(+network) ${gen}ms + playback ${play}ms [$net]"
    }

    /** Sabse bada hissa - "der kahan hai" */
    fun bottleneck(): String? {
        if (firstPlayAt == 0L || speechEndAt == 0L) return null
        val ep = if (endpointAt > 0) endpointAt - speechEndAt else 0
        val gen = if (firstChunkAt > 0) firstChunkAt - (if (endpointAt > 0) endpointAt else speechEndAt) else 0
        val play = if (firstChunkAt > 0) firstPlayAt - firstChunkAt else 0
        return listOf("STT/turn" to ep, "generation/network" to gen, "playback" to play).maxBy { it.second }.first
    }
}

/**
 * v3.9: DK bolte samay "user sach me bol raha hai?" - pakki jaanch.
 * Tez awaaz (BargeDetector) = sirf SHAK: DK ki awaaz halki karo, caption model ko suno.
 * Caption me asli shabd aaye (DK ke apne jawab ki goonj nahi) = PAKKA -> DK chup, Gemini ko user ki awaaz.
 * Shabd nahi aaye (TV/log/khatak) = galat alarm -> DK ki awaaz wapas, jawab chalta rahe.
 */
class BargeGate(
    /** Itni der me shabd na aaye to shor tha */
    private val confirmMs: Long = 1500,
    /** Caption band ho to: itni der lagatar tez awaaz = pakka */
    private val noCaptionMs: Long = 700,
) {
    enum class State { IDLE, TENTATIVE, CONFIRMED }
    var state = State.IDLE
        private set
    var since = 0L
        private set

    fun reset() { state = State.IDLE; since = 0L }

    /** BargeDetector ne tez awaaz pakdi */
    fun suspect(now: Long) { if (state == State.IDLE) { state = State.TENTATIVE; since = now } }

    /** Caption ka text aaya - asli user shabd? true = abhi pakka hua */
    fun onText(text: String, dkReply: String): Boolean {
        if (state != State.TENTATIVE) return false
        if (!realWords(text) || isEcho(text, dkReply)) return false
        state = State.CONFIRMED; return true
    }

    /** Har 40ms: true = galat alarm, ab chhodo. loudNow = abhi bhi tez awaaz hai */
    fun expired(now: Long, captionOn: Boolean, loudMs: Long): Boolean {
        if (state != State.TENTATIVE) return false
        if (!captionOn && loudMs >= noCaptionMs) { state = State.CONFIRMED; return false }
        if (now - since >= confirmMs) { state = State.IDLE; return true }
        return false
    }

    fun confirmedByLength(): Boolean = state == State.CONFIRMED

    companion object {
        private val LETTER = Regex("[\\p{L}\\p{M}]")
        private val WS = Regex("[\\s,.!?।]+")
        /** Kam se kam 2 akshar wala ek shabd ("हाँ", "ruko") - khaali/ek akshar/aawaaz-chinh nahi */
        fun realWords(t: String): Boolean = t.split(WS).any { w -> LETTER.findAll(w).count() >= 2 }
        /** Caption ne DK ki apni awaaz likh di? (adhiktar shabd DK ke jawab me) */
        fun isEcho(t: String, reply: String): Boolean {
            if (reply.isBlank()) return false
            val words = t.lowercase().split(WS).filter { LETTER.findAll(it).count() >= 2 }
            if (words.isEmpty()) return true
            val r = reply.lowercase()
            val hit = words.count { r.contains(it) }
            return words.size >= 2 && hit * 10 >= words.size * 8
        }
    }
}
