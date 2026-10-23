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
    /** Pichhli poori baat kitni lambi thi (SPEECH_END par) - chhoti khatak par "jawab kahan?" mat poochho */
    var lastSpeechMs = 0
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
        lastSpeechMs = speechMs
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

    /** v3.39: माइक में गूँज ÷ DK की अपनी आवाज़ (जितना DK बजाता है, उसका कितना हिस्सा माइक में लौटता है) */
    private var ratio = 0.0

    fun reset() { echo = 0.0; loud = 0; since = 0L; triggered = false; ratio = 0.0 }

    /** Galat alarm tha (shor): dobara suno, par ab is shor ko "goonj" maan kar (wahi shor dobara na roke) */
    fun rearm(noiseRms: Double) { loud = 0; triggered = false; echo = max(echo, noiseRms * 0.6) }

    /** true = abhi-abhi user ki awaaz pakdi (sirf ek baar, jab tak reset na ho) */
    /**
     * @param out DK की अपनी आवाज़ अभी कितनी तेज़ बज रही है (आख़िरी ~400ms का सबसे ऊँचा; 0 = पता नहीं -> पुराना तरीका).
     * DK ज़ोर से बोले तो गूँज भी ज़ोर से आती है - इसलिए "यूज़र" तभी जब माइक की आवाज़ उस पल की अनुमानित गूँज से 3 गुना हो.
     */
    fun feed(rms: Double, now: Long, out: Double = 0.0): Boolean {
        if (triggered) return false
        if (since == 0L) since = now
        if (now - since < learnMs) { loud = 0; echo = max(echo * 0.8, rms); learn(rms, out); return false }
        val expected = if (out > 0.0 && ratio > 0.0) ratio * out * 3.0 else 0.0
        if (rms > maxOf(echo * 2.5, ambient * 3.5, minRms, expected)) loud++
        else { loud = 0; echo = if (echo == 0.0) rms else echo * 0.9 + rms * 0.1; learn(rms, out) }
        if (loud >= needChunks) { triggered = true; return true }
        return false
    }

    fun echoLevel() = echo

    private fun learn(rms: Double, out: Double) {
        if (out <= 300.0) return
        val r = minOf(rms / out, 4.0)
        ratio = if (ratio == 0.0) r else max(ratio * 0.97, r)
    }
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

/**
 * v3.9.1: "bola par jawab nahi aaya" pakadna.
 * Log (15:59-16:01) me: phone ne ~15 baar "बोलना खत्म → audioStreamEnd" bheja, Gemini ne sirf 3 baar jawab diya -
 * server ne baaki baaton ko suna hi nahi (na ACTIVITY_START, na text, na awaaz). Tab DK chup baitha rehta tha.
 * Yeh class sirf hisaab rakhti hai: asli baat khatam -> [timeoutMs] tak server se koi nishaani nahi = "nahi suna".
 * (GeminiLive phir caption ka text Gemini ko seedha bhejta hai.) Android import nahi -> unit test me chalti hai.
 */
class ReplyWatchdog(
    /** Itni der me server se kuch na aaye (audioStreamEnd ke baad) = nahi suna */
    private val timeoutMs: Long = 2500,
    /** Itni chhoti awaaz (khaansi/khatak) par nahi */
    private val minSpeechMs: Int = 400,
    /**
     * v3.29: server ne SUNA (text aaya) par itni der tak jawab shuru nahi kiya (na awaaz, na tool) = atka.
     * Pehle yahan kuch nahi hota tha: 12s baad bas "Listening" - user ko jawab hi nahi milta tha.
     */
    private val answerTimeoutMs: Long = 4500,
) {
    @Volatile private var heard = false
    @Volatile private var deadline = 0L
    @Volatile private var answered = false
    @Volatile private var answerDeadline = 0L
    @Volatile private var nudged = false
    /** Asli baat kab khatam hui jiska jawab abhi shuru nahi hua (0 = koi baat jawab ka intezaar nahi kar rahi) */
    @Volatile private var pendingSince = 0L

    /** User bolne laga - nayi baat, ab tak server ne kuch nahi suna */
    fun onSpeechStart() { heard = false; deadline = 0L; answered = false; answerDeadline = 0L; nudged = false; pendingSince = 0L }

    /** Server ne is baat ka kuch bheja (ACTIVITY_START/END, text, tool, awaaz) - woh sun raha hai */
    fun onServerHeard() { heard = true; deadline = 0L }

    /** Server ne jawab shuru kiya (awaaz / jawab ka text / tool call) */
    fun onServerAnswered() { heard = true; deadline = 0L; answered = true; answerDeadline = 0L; pendingSince = 0L }

    /** Humne khud text bhej diya (nahi suna tha) - is baat par dobara "atka" nudge nahi */
    fun onRescued() { heard = true; deadline = 0L; nudged = true; answerDeadline = 0L }

    /** Phone ne pakda: baat khatam */
    fun onEndOfSpeech(now: Long, speechMs: Int) {
        deadline = if (!heard && speechMs >= minSpeechMs) now + timeoutMs else 0L
        answerDeadline = if (!answered && !nudged && speechMs >= minSpeechMs) now + answerTimeoutMs else 0L
        if (!answered && speechMs >= minSpeechMs) pendingSince = now
    }

    /**
     * Connection toota: kya user ki koi asli baat (pichhle [maxAgeMs] me) abhi bhi jawab ka intezaar kar rahi thi?
     * (Tab dobara judne ke baad wahi baat ek baar text me bhejni hai - warna woh baat chupchaap kho jaati thi.)
     */
    fun unanswered(now: Long, maxAgeMs: Long = 20_000): Boolean =
        pendingSince > 0 && !answered && now - pendingSince <= maxAgeMs

    /** Dobara bhej di - is baat ke liye phir se nahi */
    fun onResent() { pendingSince = 0L; heard = true; deadline = 0L; nudged = true; answerDeadline = 0L }

    /** true = (sirf ek baar) server ne suna par jawab shuru nahi kiya */
    fun stalled(now: Long): Boolean {
        if (answerDeadline == 0L || answered || nudged || !heard || now < answerDeadline) return false
        answerDeadline = 0L; nudged = true
        return true
    }

    /** true = (sirf ek baar) server ne is baat ko nahi suna */
    fun due(now: Long): Boolean {
        if (deadline == 0L || heard || now < deadline) return false
        deadline = 0L
        return true
    }

    fun reset() { heard = false; deadline = 0L; answered = false; answerDeadline = 0L; nudged = false; pendingSince = 0L }
}

/**
 * Khaamoshi ke samay upload band (Live API docs: stream 1s se zyada ruke to `audioStreamEnd` bhejo, client kabhi bhi
 * dobara audio bhej sakta hai).
 * Pehle chup rehne par bhi har 40ms shoonya (0) ka tukda dono socket (Live + caption) par jaata tha = ~85 KB/s
 * (~700 kbps) lagatar upload. Kamzor mobile data par user ki asli baat isi khaamoshi ke peeche line me atakti thi.
 * Ab: asli awaaz ke baad [keepMs] tak khaamoshi (server VAD ko "chup" dikhane ke liye, 500ms se zyada), phir ek
 * `audioStreamEnd` aur upload band - jab tak asli awaaz phir na aaye. Android import nahi -> unit test.
 */
class SilenceStreamGate(private val chunkMs: Int = 40, private val keepMs: Int = 1000) {
    enum class Action { SEND, SEND_THEN_END, SKIP }
    private var silentMs = 0
    /** true = abhi upload ruka hai (khaamoshi) */
    var paused = false
        private set

    /** Asli awaaz ja rahi hai - stream khula */
    fun onReal() { silentMs = 0; paused = false }

    /** Khaamoshi ka tukda: bhejo / bhejo + audioStreamEnd (phir band) / mat bhejo */
    fun onSilence(): Action {
        if (paused) return Action.SKIP
        silentMs += chunkMs
        if (silentMs >= keepMs) { paused = true; return Action.SEND_THEN_END }
        return Action.SEND
    }

    fun reset() { silentMs = 0; paused = false }
}

/**
 * Ek baari ki poori samay-rekha (diagnostics) - "jawab der se / aaya hi nahi" ka agla mamla log me saaf dikhe.
 * Sab samay [speechEndAt] (user ki aakhri asli awaaz) se ginte hain:
 *  endpoint  = audioStreamEnd bheja / server ACTIVITY_END
 *  upload    = WebSocket ki bheji line khaali (sab input audio phone se nikal gaya)
 *  event     = server ka pehla sandesh (is baat ke baad)
 *  audio     = pehla jawab-audio tukda aaya
 *  play      = pehla tukda speaker me likha
 *  genDone   = server: generationComplete
 *  done      = server: turnComplete / interrupted
 * Android import nahi -> unit test.
 */
class TurnTimeline {
    @Volatile var speechEndAt = 0L; private set
    @Volatile var endpointAt = 0L; private set
    @Volatile var uploadAt = 0L; private set
    @Volatile var eventAt = 0L; private set
    @Volatile var audioAt = 0L; private set
    @Volatile var playAt = 0L; private set
    @Volatile var genDoneAt = 0L; private set
    @Volatile var doneAt = 0L; private set
    @Volatile var why = ""; private set

    /** Abhi koi baat jawab ka intezaar / jawab chal raha hai */
    val active get() = speechEndAt > 0 && doneAt == 0L

    fun begin(speechEnd: Long, endpoint: Long, reason: String) {
        speechEndAt = speechEnd; endpointAt = endpoint; why = reason
        uploadAt = 0; eventAt = 0; audioAt = 0; playAt = 0; genDoneAt = 0; doneAt = 0
    }
    fun upload(now: Long) { if (active && uploadAt == 0L) uploadAt = now }
    fun event(now: Long) { if (active && eventAt == 0L) eventAt = now }
    fun audio(now: Long) { if (active && audioAt == 0L) audioAt = now }
    fun play(now: Long) { if (speechEndAt > 0 && audioAt > 0 && playAt == 0L) playAt = now }
    fun genDone(now: Long) { if (active && genDoneAt == 0L) genDoneAt = now }
    fun done(now: Long) { if (active) doneAt = now }
    fun reset() { speechEndAt = 0; endpointAt = 0; uploadAt = 0; eventAt = 0; audioAt = 0; playAt = 0; genDoneAt = 0; doneAt = 0; why = "" }

    /** "endpoint +520 • upload +540 • event +900 • audio +1100 • play +1180 • genDone +3000 • done +5200" */
    fun summary(): String {
        if (speechEndAt == 0L) return "-"
        fun f(n: String, t: Long) = if (t > 0) "$n +${t - speechEndAt}ms" else "$n -"
        return listOf(f("endpoint", endpointAt), f("upload खाली", uploadAt), f("पहला server event", eventAt),
            f("पहला ऑडियो", audioAt), f("पहली आवाज़", playAt), f("generationComplete", genDoneAt), f("turn खत्म", doneAt))
            .joinToString(" • ") + " [$why]"
    }
}

/**
 * Live ruka to kitni der baad dobara (DKAccessibilityService). Pehle har Fatal = 10 min band.
 * Asli rukawat (key galat / limit / model nahi) = 10 min. Thodi-der wali (network / mic busy) = 15s, 30s, 60s,
 * 120s, 240s, phir 5 min par ruk jaata hai - anant tez retry nahi. Android import nahi -> unit test.
 */
object LiveRetry {
    const val HARD_MS = 10 * 60_000L
    const val SOFT_MAX_MS = 5 * 60_000L
    fun offMs(recoverable: Boolean, softFails: Int): Long =
        if (!recoverable) HARD_MS else minOf(SOFT_MAX_MS, 15_000L shl softFails.coerceIn(0, 5))
}
