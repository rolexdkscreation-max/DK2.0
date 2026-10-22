package com.dk.assistant

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Gemini Live API: AAWAZ SE AAWAZ, real-time (WebSocket).
 *
 *  Mic (16 kHz, mono, PCM 16-bit little-endian) --40ms ke tukde lagatar (koi jama nahi)--> Gemini
 *  Bolna khatam: phone khud pakadta hai (500ms chup) -> turant audioStreamEnd (hybrid VAD, v3.8);
 *  na pakde to server VAD (500ms) fallback
 *  Gemini ki awaaz (24 kHz PCM) tukdon me aati hai --> pehla tukda aate hi AudioTrack par bajti hai
 *  Beech me bolo -> phone turant awaaz rokta hai + purane jawab ke bache tukde phenk deta hai;
 *  mic DK bolte samay bhi Gemini tak jaata hai (echo-cancel ho to) -> server generation rok deta hai (interrupted)
 *  Wake word ke baad 45s chup: connection band nahi, 5 min "garam" (standby) -> agli baar 0 ms me judna
 *
 * Purane tareeke (poora record -> Whisper -> AI -> alag TTS) me 4 alag kadam the; yahan sab ek hi stream me.
 * Connection toote to apne-aap dobara judta hai (session resumption = baatcheet yaad rehti hai).
 */
class GeminiLive(private val svc: DKAccessibilityService, private val agent: AgentRunner) {

    companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        private const val CHUNK_MS = 40
        /** Server VAD: itni der chup = baat khatam (Google: 500-800ms; kam = baat beech me kat-ti hai) */
        const val SERVER_SILENCE_MS = 500
        /** Phone ka VAD: itni der chup = turant audioStreamEnd (server ka intezaar nahi) */
        const val CLIENT_END_MS = 500
        /** Bolna shuru pakadne se pehle itni awaaz - chhota = pehla shabd turant (bahut chhota = DK ki goonj se galat "toka") */
        const val PREFIX_PADDING_MS = 100
        /** Wake word wala Live chup hone par connection itni der garam rakho */
        private const val PARK_MS = 5 * 60_000L
        /** DK bolte samay (gate mode) user ki awaaz pakdi -> usse pehle ka itna hissa bhi bhejo (pehla shabd na kate) */
        private const val PREROLL_CHUNKS = 8          // 8 x 40ms = 320ms
        /** Itni der "Thinking" aur kuch na aaye -> wapas "Listening" (purani haalat atki na rahe) */
        private const val THINK_STUCK_MS = 12_000L
        /**
         * Jawab "ban raha" (generating) tha par itni der server se ek bhi sandesh nahi aur awaaz bhi nahi baj rahi =
         * turnComplete kho gaya / stream atak gaya. Tab turn band (stream ya connection nahi kaata - aage kuch aaye to
         * naya turn shuru hota hai). Audio stream me tukde lagatar aate hain, tool ka samay alag (toolJobs) - 15s kaafi hai.
         */
        private const val GEN_STALL_MS = 15_000L
        /** AudioTrack me likhi par abhi na baji awaaz (~150ms) */
        private const val LEAD_BYTES = OUT_RATE * 2 * 150 / 1000
        private val WS = Regex("\\s+")
        private const val CHUNK_SAMPLES = IN_RATE * CHUNK_MS / 1000      // 640 samples = 1280 bytes
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** Docs (Oct 2026): gemini-3.8-live = default; 3.1 flash live = purana preview */
        val FALLBACK_MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-2.5-flash-native-audio-preview-09-2025")

        fun key(c: android.content.Context) = Prefs.ttsGeminiKey(c)

        /** "माइक बंद करो" = haan; "माइक बंद मत करो" / "माइक बंद नहीं" = nahi */
        fun micOffCommand(t: String): Boolean {
            val low = t.lowercase()
            val i = listOf("माइक बंद", "सुनना बंद", "mic off", "mic band").map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return false
            val after = low.substring(i).take(30)
            return listOf("मत", "नहीं", "नही", "ना करो", "न करो", "don't", "mat ").none { after.contains(it) }
        }
        fun available(c: android.content.Context) = key(c).isNotBlank()

        /** Debug screen ke liye taaza aankde */
        @Volatile var stats: String = "Gemini Live अभी नहीं चला"
            private set
    }

    /**
     * Live nahi chal sakta (key galat / limit / model nahi) - purane tareeke par jao.
     * recoverable = network / mic thodi der busy jaisi dikkat: thodi der baad Live dobara (10 min ka lamba ruk nahi)
     */
    class Fatal(msg: String, val recoverable: Boolean = false) : Exception(msg)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)       // stream: read timeout nahi
        .pingInterval(15, TimeUnit.SECONDS)          // connection zinda hai? (NAT/mobile data par tootne se bachao)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var stopped = true

    /** v3.31: DK कंप्यूटर / कोडिंग टीम का काम सच में खत्म हो -> DK लाइव को असली नतीजा (वही बोले). नकली progress नहीं */
    private val statusListener = TaskStatus.Listener { job, ended ->
        if (!ended || stopped) return@Listener
        svc.scope.launch {
            // DK बोल रहा हो तो बीच में नहीं - चुप होने तक (10s तक) रुको
            withTimeoutOrNull(10_000) { while (isPlaying() || generating) delay(150) }
            if (stopped || !ready) return@launch
            try { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", TaskStatus.endNote(job))).toString()) } catch (_: Exception) {}
            log("${job.whoEn} का काम खत्म (${job.state.en}) - Gemini को असली नतीजा बताया")
        }
    }
    init { TaskStatus.listen(statusListener) }
    @Volatile private var goAway = false
    @Volatile private var goAwayDrop = false          // server ne khud naya connection maanga (galti nahi)
    private var handle: String? = null
    private var useSearch = true

    // ---- aankde (debug)
    @Volatile private var sent = 0L
    @Volatile private var sentBytes = 0L
    @Volatile private var dropped = 0L
    @Volatile private var gaps = 0L
    @Volatile private var readErr = 0L
    @Volatile private var recvAudio = 0L
    @Volatile private var reconnects = 0
    @Volatile private var lastLatency = -1L
    @Volatile private var model = ""

    // ---- baari (turn)
    private val userText = StringBuilder()
    private val replyText = StringBuilder()
    @Volatile private var turnActive = false
    private val turnLock = Any()

    // ---- turant text (v3.0)
    /** Alag tez caption stream - "आप:" me bolte-bolte shabd */
    @Volatile private var caption: LiveCaption? = null
    /** DK ne jawab shuru kiya us waqt tak user ne jo bola (caption se) */
    @Volatile private var turnUser = ""
    /** Is baari ki user line chat me pakki ho chuki (der se aaya text ab us line ko theek karega) */
    @Volatile private var userDone = false
    /** Is baari ka jawab (awaaz) shuru ho chuka - ginti dobara shuru nahi karni */
    @Volatile private var replyStarted = false
    /** Gemini abhi jawab bana raha hai (turnComplete nahi aaya) */
    @Volatile private var generating = false
    /** DK bol raha hai aur user sach me bol raha hai (mic asli awaaz bhej raha hai) */
    @Volatile private var barging = false
    /** DK ka text uski awaaz ke saath: kitni awaaz aayi / kitni baj chuki */
    @Volatile private var turnRecvBytes = 0L
    @Volatile private var turnPlayedBytes = 0L
    /** Jawab poora aa gaya par awaaz abhi baj rahi hai */
    @Volatile private var pendingReply: String? = null
    /** v3.9: is baari Google Search hua to uske sources (groundingMetadata) - sirf screen par, bole nahi jaate */
    private var turnSources: List<Pair<String, String>> = emptyList()
    private var pendingSources: List<Pair<String, String>> = emptyList()
    /** deep_think tool ne search kiya ho to uske sources (agle jawab ke saath) */
    @Volatile private var toolSources: List<Pair<String, String>> = emptyList()
    @Volatile private var shownReply = ""
    @Volatile private var eosAt = 0L
    @Volatile private var firstAudioAt = 0L

    // ---- v3.8: turant haalat (listening/thinking/speaking) + barge-in + deri ke hisse
    private val turnDet = TurnDetector(CHUNK_MS, endSilenceMs = CLIENT_END_MS)
    private val bargeDet = BargeDetector()
    private val trace = LatencyTrace()
    /** Har baari ki poori samay-rekha (end of speech -> upload -> pehla event -> audio -> awaaz -> turn khatam) */
    private val timeline = TurnTimeline()
    @Volatile private var waitingLogged = false
    @Volatile private var noReplyTurns = 0
    /** Khaamoshi me upload band (Live socket / caption socket alag-alag) */
    private val liveGate = SilenceStreamGate(CHUNK_MS)
    private val capGate = SilenceStreamGate(CHUNK_MS)
    @Volatile private var silenceSkipped = 0L
    /**
     * Audio bhejne ka ek hi kram: mic thread, caption thread (barge pakka) aur OkHttp thread (judte samay ki
     * awaaz) teeno bhejte hain - bina lock ke purane tukde naye ke BAAD pahunch sakte the (ulti-pulti awaaz).
     */
    private val sendLock = Any()
    /** Server ka aakhri sandesh (koi bhi) - atka stream pakadne ke liye */
    @Volatile private var lastServerAt = 0L
    @Volatile private var genStalls = 0
    @Volatile private var handlerErrors = 0
    /** Connection tootne se pehle ki baat jiska jawab shuru nahi hua tha - judte hi ek baar text me */
    @Volatile private var resendText: String? = null
    @Volatile private var resentN = 0
    /** v3.9.1: bola par Gemini ne suna hi nahi -> caption ka text seedha bhejo */
    private val replyWatch = ReplyWatchdog()
    @Volatile private var unheard = 0
    @Volatile private var rescued = 0
    /** v3.29: suna par jawab shuru nahi kiya -> wahi baat text me dobara (kitni baar) */
    @Volatile private var stalledN = 0
    /** v3.29: baat khatam hote hi seedha kaam (Gemini se pehle) + Gemini ka phone_task dohraav na kare */
    private val fastMemo = LiveFast.Memo()
    @Volatile private var fastJob: Job? = null
    @Volatile private var fastN = 0
    /** User ne toka: purane jawab ke jo tukde ab bhi aa rahe hain unhe mat bajao (interrupted/turnComplete tak) */
    @Volatile private var dropOld = false
    @Volatile private var droppedOld = 0L
    /** Echo-cancel chalu hai -> DK bolte samay bhi mic ki asli awaaz Gemini ko (sach me full-duplex) */
    @Volatile private var aecOn = false
    /**
     * v3.9: band. Log (15:37) me dikha: lagatar asli mic = peeche ka shor bhi Gemini tak -> server sochta raha user bol raha hai
     * -> jawab 8-16s der se / aaya hi nahi. Ab DK bolte samay Gemini ko sirf PAKKE user shabd (BargeGate) jaate hain.
     */
    @Volatile private var fullDuplex = false
    /** DK bolte samay "user bola?" - shak -> caption se shabd -> pakka (shor par jawab chalta rahe) */
    private val bargeGate = BargeGate()
    /** Shak ke dauran ki awaaz (pakka hone par Gemini ko yahi pehle jaati hai) */
    private val bargeBuf = ArrayDeque<ByteArray>()
    @Volatile private var falseAlarms = 0
    @Volatile private var realBarges = 0
    @Volatile private var gatedChunks = 0L
    /** 2.5 model ka "sochna" band (pehle awaaz tez) - setup mana kare to wapas */
    @Volatile private var thinkOff = true
    @Volatile private var falseInterrupts = 0
    @Volatile private var suspectAt = 0L
    @Volatile private var localBargeAt = 0L
    @Volatile private var bargeUntil = 0L
    @Volatile private var thinkingSince = 0L
    @Volatile private var lastBreakdown = ""
    @Volatile private var warmReuses = 0
    @Volatile private var setupSentAt = 0L

    /** Garam (standby) connection: wake word ke baad chup hone par band nahi kiya */
    private class Parked(val ws: WebSocket, val closed: CompletableDeferred<Pair<Int, String>>, val model: String, val at: Long)
    @Volatile private var parked: Parked? = null
    @Volatile private var curClosed: CompletableDeferred<Pair<Int, String>>? = null

    // ---- bajana (playback)
    private val playQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var playEnd = 0L                // kab tak awaaz baj rahi hogi (andaza)
    @Volatile private var gen = 0
    private var playThread: Thread? = null
    fun isPlaying() = System.currentTimeMillis() < playEnd + 120 || playQ.isNotEmpty()

    private val toolJobs = ConcurrentHashMap<String, Job>()

    /** Wake word se Live shuru hua: pehli baat (text) jo DK ko turant samajhni hai */
    @Volatile var firstText: String? = null
    /** >0 = itni der koi baat na ho to Live khud band (wake mode me wapas) */
    @Volatile var idleExitMs = 0L
    @Volatile private var lastAct = 0L
    @Volatile private var idleExit = false
    @Volatile private var modelCache: Triple<Int, Long, List<String>>? = null

    fun stop(why: String = "user stop") {
        stopped = true
        closeWs(ws, why)
        parked?.let { parked = null; closeWs(it.ws, why) }
    }

    /** Haalat badlo (Thinking kab se hai yaad rakho - atakne par wapas) */
    private fun setMode(m: Mode) {
        thinkingSince = if (m == Mode.THINKING) (if (DKState.mode == Mode.THINKING && thinkingSince > 0) thinkingSince else System.currentTimeMillis()) else 0L
        if (DKState.mode != m) DKState.set(m)
    }

    /** Kaun se connection HUMNE band kiye (aur kyun) - log me "server ने बंद किया" galat na likha jaye */
    private val ourClose = ConcurrentHashMap<WebSocket, String>()
    private fun closeWs(w: WebSocket?, why: String) {
        if (w == null) return
        ourClose[w] = why
        try { w.close(1000, "bye") } catch (_: Exception) {}      // reason ASCII hi (123 byte se zyada = close hi nahi hota)
    }

    /** Quota khatam wala model: aaj dobara mat maango (pehli baar 30 min, dobara = Pacific aadhi raat tak) */
    private fun blockModel(m: String, why: String) {
        val n = Cooldown.strike("live:$m")
        val ms = if (n <= 1) 30 * 60_000L else Cooldown.msToQuotaReset()
        Cooldown.block("live:$m", ms)
        log("$m की limit खत्म ($why) - ${ms / 60_000} मिनट तक यह मॉडल नहीं माँगूँगा")
    }
    private fun isQuota(code: Int, r: String) = r.contains("quota") || r.contains("exhausted") || r.contains("rate limit") || code == 429

    private fun log(m: String) = DKLog.d("Live: $m")

    private fun updateStats() {
        stats = "मॉडल: ${model.ifBlank { "-" }} • जुड़ा: ${if (ready) "हाँ" else "नहीं"} • भेजे टुकड़े: $sent (${sentBytes / 1024} KB)" +
            " • गिरे: $dropped • मिक गैप: $gaps • पढ़ने की गलती: $readErr • आई आवाज़: ${recvAudio / 1024} KB" +
            " • दोबारा जुड़ा: $reconnects • गर्म connection दोबारा: $warmReuses" +
            " • बीच में टोकना: सिर्फ़ पक्के शब्द (असली $realBarges, शोर अनदेखा $falseAlarms)" +
            " • शोर-गेट: ${gatedChunks * CHUNK_MS / 1000}s शोर नहीं भेजा" +
            (if (unheard > 0) " • Gemini ने नहीं सुना: $unheard (caption से भेजा $rescued)" else "") +
            (if (stalledN > 0) " • सुना पर जवाब अटका: $stalledN (दोबारा भेजा)" else "") +
            (if (fastN > 0) " • तुरंत किए काम: $fastN" else "") +
            " • चुप्पी में upload बंद: ${silenceSkipped * CHUNK_MS / 1000}s" +
            (if (noReplyTurns > 0) " • बात पर server से कुछ नहीं: $noReplyTurns" else "") +
            (if (genStalls > 0) " • अटका जवाब (turnComplete नहीं): $genStalls" else "") +
            (if (resentN > 0) " • टूटने के बाद दोबारा भेजी बात: $resentN" else "") +
            (if (handlerErrors > 0) " • server संदेश की गलती: $handlerErrors" else "") +
            (if (droppedOld > 0) " • पुराने जवाब के फेंके टुकड़े: $droppedOld" else "") +
            (if (lastLatency >= 0) " • पिछली देरी: $lastLatency ms" else "") +
            (if (lastBreakdown.isNotBlank()) " • $lastBreakdown" else "")
    }

    // ================================================================ session
    /**
     * Live baatcheet chalao jab tak stop() na ho. "call" = phone call aaya (mic chhodo).
     * Fatal = Live chal hi nahi sakta.
     */
    suspend fun run(): String = withContext(Dispatchers.IO) {
        stopped = false; goAway = false; idleExit = false; lastAct = System.currentTimeMillis()
        sent = 0; sentBytes = 0; dropped = 0; gaps = 0; readErr = 0; recvAudio = 0; reconnects = 0
        if (!Setup.hasMic(svc)) throw Fatal("माइक की परमिशन नहीं है")
        val key = key(svc).ifBlank { throw Fatal("Gemini की key नहीं है") }
        val models = discoverModels(key)
        log("models: ${models.joinToString()}")
        var mi = 0; var fails = 0
        var quickDrops = 0                               // judne ke turant baad baar-baar tootna
        startPlayer()
        caption = LiveCaption(key, onText = { t, fin -> onCaption(t, fin) }, onLate = { t -> onLateCaption(t) },
            isNewSpeech = { barging || System.currentTimeMillis() < bargeUntil }).also { it.start() }
        turnDet.reset(); bargeDet.reset(); bargeGate.reset(); synchronized(bargeBuf) { bargeBuf.clear() }
        trace.reset(); dropOld = false; suspectAt = 0L; replyWatch.reset()
        timeline.reset(); liveGate.reset(); capGate.reset(); resendText = null; lastServerAt = 0L
        coroutineScope {
            val mic = launch(Dispatchers.IO) { micLoop() }
            // DK ka text awaaz ke saath-saath (har 80ms)
            val reveal = launch(Dispatchers.IO) { while (isActive && !stopped) { revealTick(); delay(80) } }
            try {
                while (!stopped && isActive) {
                    if (svc.callBusy()) return@coroutineScope
                    model = models[mi]
                    // v3.8: wake word ke baad garam rakha connection abhi zinda hai -> naya mat banao (0 ms)
                    val pk = parked; parked = null
                    val reuse = pk != null && !pk.closed.isCompleted && pk.model == model &&
                        System.currentTimeMillis() - pk.at < PARK_MS
                    if (pk != null && !reuse) closeWs(pk.ws, "standby purana")
                    val closed = if (reuse) pk!!.closed else CompletableDeferred()
                    curClosed = closed
                    val t0 = System.currentTimeMillis()
                    if (reuse) {
                        ws = pk!!.ws; warmReuses++
                        synchronized(sendLock) { ready = true; flushPending() }
                        log("गर्म connection फिर से [$model] - जुड़ने में 0 ms (${(t0 - pk.at) / 1000}s से standby में था)")
                        sendFirstText(); sendResend()
                    } else {
                    val setup = CompletableDeferred<Unit>()
                    connect(key, model, closed, setup)
                    val ok = withTimeoutOrNull(12_000) {
                        while (!setup.isCompleted && !closed.isCompleted) delay(25)
                        setup.isCompleted
                    } ?: false
                    if (!ok) {
                        val (code, reason) = if (closed.isCompleted) closed.await() else 0 to "setup का जवाब 12 सेकंड में नहीं आया"
                        ws?.let { ourClose[it] = "setup fail" }
                        try { ws?.cancel() } catch (_: Exception) {}
                        ready = false
                        log("setup fail [$model] code=$code reason=$reason")
                        val r = reason.lowercase()
                        // dobara judte waqt purani baatcheet (resume handle) na chale to turant nayi baatcheet - fail mat gino
                        if (!stopped && handle != null && code != 401 && code != 403 && !r.contains("api key") && !r.contains("quota")) {
                            log("पुरानी बातचीत जारी नहीं हो पाई - नई बातचीत से जुड़ रहा हूँ")
                            handle = null; continue
                        }
                        when {
                            stopped -> break
                            r.contains("api key") || r.contains("api_key") || code == 401 || code == 403 ->
                                throw Fatal("Gemini key गलत है या Live की इजाज़त नहीं")
                            isQuota(code, r) || r.contains("rate") -> {
                                blockModel(model, "setup")
                                if (mi + 1 < models.size) { mi++; handle = null; continue } else throw Fatal("Gemini Live की फ्री लिमिट अभी खत्म है")
                            }
                            useSearch && (r.contains("search") || r.contains("tool")) -> { useSearch = false; continue }
                            thinkOff && r.contains("think") -> { thinkOff = false; log("यह मॉडल thinkingBudget नहीं लेता - उसके बिना"); continue }
                            handle != null && (r.contains("handle") || r.contains("resum") || r.contains("session")) -> { handle = null; continue }
                            r.contains("model") || r.contains("not found") || r.contains("not supported") || code == 1008 || code == 404 || code == 1007 ->
                                if (mi + 1 < models.size) { mi++; handle = null; continue } else throw Fatal("Live वाला कोई मॉडल नहीं चला ($reason)")
                        }
                        fails++
                        if (fails >= 4) throw Fatal("Gemini Live से जुड़ नहीं पाया: ${reason.take(80)}", recoverable = true)
                        delay(minOf(8000L, 500L shl fails))       // 1s, 2s, 4s, 8s
                        continue
                    }
                    }
                    fails = 0
                    val upAt = System.currentTimeMillis()
                    log("जुड़ गया [$model] ${System.currentTimeMillis() - t0} ms" + if (handle != null) " (पुरानी बातचीत जारी)" else "")
                    Prefs.edit(svc) { putString("live_model", model) }
                    Cooldown.clear("live:$model")
                    if (!turnActive && !isPlaying()) DKState.set(Mode.LISTENING)
                    // ab chalne do jab tak band/toote/goAway/call
                    while (!stopped && isActive) {
                        if (closed.isCompleted) break
                        if (idleExitMs > 0 && !turnActive && !isPlaying() && toolJobs.isEmpty() &&
                            System.currentTimeMillis() - lastAct > idleExitMs) {
                            log("${idleExitMs / 1000}s से कोई बात नहीं - Live बंद, wake word पर वापस")
                            idleExit = true; stopped = true; break
                        }
                        if (goAway && !turnActive && !isPlaying()) { log("goAway - नया connection"); goAwayDrop = true; break }
                        if (svc.callBusy()) { closeWs(ws, "call आया"); return@coroutineScope }
                        if (System.currentTimeMillis() % 10_000 < 100) { updateStats(); log(stats) }
                        val now = System.currentTimeMillis()
                        // "Thinking" me atka (kuch nahi aaya) -> wapas Listening, purani haalat na rahe
                        if (DKState.mode == Mode.THINKING && thinkingSince > 0 && now - thinkingSince > THINK_STUCK_MS &&
                            !generating && toolJobs.isEmpty() && !isPlaying()) {
                            log("${THINK_STUCK_MS / 1000}s से जवाब नहीं आया - Listening पर वापस"); setMode(Mode.LISTENING)
                        }
                        // toka tha par server ne na "interrupted" bheja na turnComplete (8s) -> purana rokna hatao
                        if (dropOld && now - localBargeAt > 8000) { dropOld = false; log("पुराने जवाब को रोकना हटाया (8s)") }
                        // server ne roka par user bola hi nahi (na phone ne awaaz pakdi, na text aaya) = DK ki apni goonj
                        if (suspectAt > 0 && now - suspectAt > 2000) {
                            suspectAt = 0L
                            if (++falseInterrupts >= 2 && fullDuplex) {
                                fullDuplex = false
                                log("DK अपनी ही आवाज़ से 2 बार रुका - अब DK के बोलते समय सिर्फ़ पकड़ी गई user की आवाज़ भेजूँगा (gate)")
                            }
                        }
                        if (replyWatch.due(now)) onNoReply()
                        if (replyWatch.stalled(now)) onStalled()
                        // samay-rekha: bheja hua sab input audio phone se nikal gaya?
                        if (timeline.active && timeline.uploadAt == 0L && (ws?.queueSize() ?: 1L) == 0L) timeline.upload(now)
                        // jawab "ban raha" tha par server 15s se bilkul chup (turnComplete kho gaya) -> turn band, warna
                        // generating=true hamesha atka rehta aur agli har baat par watchdog band rehta (jawab nahi = chup DK)
                        if (generating && !isPlaying() && toolJobs.isEmpty() && lastServerAt > 0 && now - lastServerAt > GEN_STALL_MS) {
                            genStalls++
                            log("जवाब बन रहा था पर server ${(now - lastServerAt) / 1000}s से चुप (turnComplete नहीं आया) - turn बंद, फिर सुन रहा हूँ • ${timeline.summary()}")
                            dropOld = false; timeline.done(now)
                            finishTurn(); setMode(Mode.LISTENING)
                        }
                        delay(100)
                    }
                    ready = false
                    if (stopped) break
                    val (code, reason) = if (closed.isCompleted) closed.await() else 1000 to "goAway"
                    // user ki baat khatam ho chuki thi par jawab shuru nahi hua aur connection toot gaya -> woh baat
                    // chupchaap kho jaati thi. Dobara judte hi ek baar text me bhejo.
                    val dropAt = System.currentTimeMillis()
                    if (resendText == null && replyWatch.unanswered(dropAt)) {
                        val t = caption?.text().orEmpty().trim()
                            .ifBlank { if (!userDone) turnUser.trim() else "" }
                            .ifBlank { synchronized(turnLock) { userText.toString().trim() } }
                        if (BargeGate.realWords(t)) { resendText = t; replyWatch.onResent() }
                        log("connection टूटा जब बात का जवाब बाकी था • ${timeline.summary()}" + if (resendText != null) " - जुड़ते ही दोबारा भेजूँगा" else "")
                    }
                    closeWs(ws, "reconnect")
                    goAway = false
                    reconnects++
                    log("connection टूटा (code=$code, ${reason.ifBlank { "-" }}, ${(System.currentTimeMillis() - upAt) / 1000}s चला) - दोबारा जुड़ रहा हूँ #$reconnects" +
                        if (handle != null) " (बातचीत वहीं से)" else " (नई बातचीत)")
                    if (reason.lowercase().contains("api key")) throw Fatal("Gemini key बंद हो गई")
                    if (isQuota(code, reason.lowercase())) {
                        blockModel(model, "बातचीत के बीच")
                        if (turnActive) finishTurn()
                        if (mi + 1 < models.size) { mi++; handle = null; continue } else throw Fatal("Gemini Live की फ्री लिमिट खत्म")
                    }
                    if (turnActive) finishTurn()
                    // 20 sec se pehle toota = network/server dikkat -> har baar zyada ruko (battery bachao)
                    val lasted = System.currentTimeMillis() - upAt
                    if (goAwayDrop || lasted >= 20_000) quickDrops = 0 else quickDrops++
                    goAwayDrop = false
                    if (quickDrops >= 6) throw Fatal("Gemini Live बार-बार टूट रहा है - नेटवर्क देखें", recoverable = true)
                    val wait = if (quickDrops == 0) 250L else minOf(15_000L, 500L shl quickDrops)   // 1s,2s,4s,8s,15s
                    if (quickDrops > 0) log("जल्दी टूटा (${lasted / 1000}s) - $wait ms रुककर दोबारा (#$quickDrops)")
                    delay(wait)
                }
            } finally {
                stopped = true
                reveal.cancel()
                // pehle mic band (woh aakhri audioStreamEnd bhejta hai) - phir socket. NonCancellable: beech me na ruke
                withContext(NonCancellable) { mic.cancelAndJoin() }
                val w = ws; val c = curClosed
                // wake word wala Live chup hone se ruka: connection band mat karo, 5 min garam rakho
                if (idleExit && w != null && c != null && !c.isCompleted && !goAway) park(w, c)
                else closeWs(w, "bye")
                ready = false
                ws = null; curClosed = null
                caption?.stop(); caption = null
                fastJob?.cancel(); fastJob = null; fastMemo.reset()
                toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
                stopPlayer()
                if (turnActive) finishTurn()
                commitPending(true)
                Ducker.releaseNow()
                updateStats(); log("बंद • $stats")
            }
        }
        if (svc.callBusy()) "call" else if (idleExit) "idle" else "stopped"
    }

    /** Wake word wala Live chup hone par: connection band nahi, 5 min standby (agli baar judne ki deri = 0) */
    private fun park(w: WebSocket, c: CompletableDeferred<Pair<Int, String>>) {
        try { w.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}     // atki awaaz flush
        val p = Parked(w, c, model, System.currentTimeMillis())
        parked = p
        log("connection बंद नहीं किया - ${PARK_MS / 60_000} मिनट standby (अगली बार तुरंत जुड़ेगा)")
        svc.scope.launch {
            delay(PARK_MS)
            if (parked === p) { parked = null; closeWs(p.ws, "standby khatam") }
        }
    }

    /** Wake word ke saath boli baat (text) - judte hi Gemini ko */
    private fun sendFirstText() {
        val t = firstText ?: return
        firstText = null
        try {
            // "आप:" line me wahi baat (wake word ke baad jo bola) - jawab ke turn me judegi
            startTurn(); turnUser = t; userDone = false
            DKState.user("$t…")
            setMode(Mode.THINKING)
            eosAt = System.currentTimeMillis(); trace.reset(); trace.speechEndAt = eosAt; trace.endpointAt = eosAt
            beginTimeline(eosAt, eosAt, "wake word की बात (text)")
            ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
            log("पहली बात भेजी: $t")
        } catch (_: Exception) {}
    }

    /** Tootne se pehle ki unanswered baat - naye connection par ek baar text me (UI line dobara nahi) */
    private fun sendResend() {
        val t = resendText ?: return
        resendText = null
        val w = ws ?: return
        val now = System.currentTimeMillis()
        eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
        beginTimeline(now, now, "टूटने के बाद दोबारा")
        val ok = try { w.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) } catch (_: Exception) { false }
        if (ok) { resentN++; if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING) }
        log("टूटने से पहले की बात (${t.length} अक्षर) नए connection पर text में भेजी" + if (ok) "" else " (भेज नहीं पाया)")
    }

    /**
     * Nayi baat ki samay-rekha shuru. Pichhli baat par server se kuch bhi nahi aaya tha (na event, na awaaz) to
     * woh mamla log me - "bola par jawab nahi" agli baar bhi dikhe (pakad me aaye).
     */
    private fun beginTimeline(speechEnd: Long, endpoint: Long, why: String) {
        if (timeline.active && timeline.eventAt == 0L && timeline.audioAt == 0L && toolJobs.isEmpty()) {
            noReplyTurns++
            log("पिछली बात पर Gemini से कुछ नहीं आया (#$noReplyTurns) • ${timeline.summary()}")
        }
        timeline.begin(speechEnd, endpoint, why); waitingLogged = false
    }

    /**
     * User DK ke bolte samay bola (phone ne pakda / server ne / caption ne):
     * 1) awaaz TURANT band (AudioTrack flush - sirf dheemi nahi)  2) purane jawab ke aane wale tukde phenko
     * 3) haalat turant Listening. Generation server rokta hai (mic ki awaaz -> server VAD -> "interrupted").
     */
    private fun bargeIn(why: String) {
        localBargeAt = System.currentTimeMillis(); bargeUntil = localBargeAt + 1500; suspectAt = 0L
        if (generating) dropOld = true
        stopForUser(why)
    }

    /** Is key par kaun se Live models hain (bidiGenerateContent) - sabse naya/tez pehle */
    private fun discoverModels(key: String): List<String> {
        val found = mutableListOf<String>()
        // wake word par har baar list mat mango (deri) - 1 ghante tak yaad rakho
        val cache = modelCache
        if (cache != null && cache.first == key.hashCode() && System.currentTimeMillis() - cache.second < 3600_000L) found.addAll(cache.third)
        else try {
            val c = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                .header("x-goog-api-key", key).build()
            c.newCall(req).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (r.code == 400 || r.code == 401 || r.code == 403) throw Fatal("Gemini key गलत है")
                val arr = JSONObject(s).optJSONArray("models")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val m = arr!!.optJSONObject(i) ?: continue
                    if (m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("bidiGenerateContent") == true)
                        found.add(m.optString("name").removePrefix("models/"))
                }
            }
            if (found.isNotEmpty()) modelCache = Triple(key.hashCode(), System.currentTimeMillis(), found.toList())
        } catch (e: Fatal) { throw e } catch (e: Exception) { log("models list nahi aayi: ${e.message}") }
        fun ver(m: String) = Regex("(\\d+(?:\\.\\d+)?)").find(m)?.value?.toDoubleOrNull() ?: 0.0
        // translate/transcribe = sirf anuvaad/likhne wale model, baatcheet nahi karte -> inhe mat chuno
        // robotics = robot wala model, baatcheet ke liye nahi
        val good = found.filter { !it.contains("translat") && !it.contains("transcri") && !it.contains("robotic") }
            .sortedWith(compareBy<String> { if (it.contains("thinking")) 1 else 0 }
                .thenBy { if (it.contains("live") || it.contains("native-audio")) 0 else 1 }
                .thenByDescending { ver(it) })
        // pichhla chala model tabhi pehle jab woh sabse naye jitna naya ho (warna purana 2.5 hamesha chipka rehta)
        val last = Prefs.liveModel(svc)
        val best = good.firstOrNull()?.let { ver(it) } ?: 0.0
        val keepLast = last.isNotBlank() && !last.contains("transcri") && !last.contains("translat") && !last.contains("robotic") &&
            (found.isEmpty() || last in found) && ver(last) >= best
        val all = (listOfNotNull(last.takeIf { keepLast }) + good + FALLBACK_MODELS).distinct()
        // limit khatam wale model (aaj) chhodo - har baar 2 bekaar connection = ~1.5s deri + mic ke tukde gire
        val (skip, ok) = all.partition { Cooldown.blocked("live:$it") }
        if (skip.isNotEmpty()) log("limit खत्म, छोड़े: ${skip.joinToString()}")
        return ok.ifEmpty { all }
    }

    private fun setupJson(m: String): String {
        val brain = BrainManager(svc)
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().put("name", "phone_task")
                .put("description", "Do something on the user's Android phone: open apps, call, send messages, WhatsApp, YouTube, music, settings (wifi, bluetooth, torch, volume, brightness), maps/directions, shopping, read or tap the screen.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("request", JSONObject().put("type", "STRING").put("description", "The user's request in their own words")))
                    .put("required", JSONArray().put("request"))))
            .put(JSONObject().put("name", "deep_think")
                .put("description", "Solve a genuinely HARD question with a much stronger reasoning model (multi-step maths, logic puzzles, code, detailed comparisons, planning, exam-level or deep why/how questions). Returns a carefully reasoned answer in Hindi to speak.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("question", JSONObject().put("type", "STRING").put("description", "The complete question with all details the user gave")))
                    .put("required", JSONArray().put("question"))))
            .put(JSONObject().put("name", "coding_task")
                .put("description", "Give a programming task to the user's coding team (Builder + Reviewer + Coordinator) that works on their GitHub repo - e.g. build/change/fix a feature in the app. Also use it when the user approves or rejects a coding suggestion (\"सुझाव जोड़ दो\" / \"सुझाव नहीं\"), and when the user asks to merge the ready pull request (e.g. PR merge कर दो - pass task \"PR merge कर दो\"; it checks tests/build first, merges, then GitHub Actions builds the new APK release). Also use it when the user asks about the repository itself - what is going on, which PRs are open, whether a PR/build is stuck or failed, 'रिपो चेक करो', 'PR 42 ठीक करो', 'PR 42 merge करो' - pass the user's words; it reads the real GitHub state and answers. Also use it when the coding team showed its understanding and the user says yes / corrects it / answers its question - pass exactly what the user said (e.g. \"हाँ\"). It starts in the background; tell the user it has started.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("task", JSONObject().put("type", "STRING").put("description", "The full coding task (or the suggestion decision) in the user's words")))
                    .put("required", JSONArray().put("task"))))
            .put(JSONObject().put("name", "computer_task")
                .put("description", "Give a multi-step task to DK Computer, the user's autonomous agent: web research, reading websites, calculations, making files/PDF/tables/reports, reminders or tasks at a later time (survive phone restart), and chains of phone actions. Use it when the user says DK कंप्यूटर / कंप्यूटर से, or asks to research and make a PDF/file/report/table, or to remind/do something later. Also use it to pass the user's हाँ/नहीं when DK Computer asked for permission (task = the user's words). It runs in the background, shows each step on the DK कंप्यूटर screen and notifies when done; tell the user it has started.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("task", JSONObject().put("type", "STRING").put("description", "The full task in the user's words, with all details (or just हाँ / नहीं for a permission)")))
                    .put("required", JSONArray().put("task"))))
            .put(JSONObject().put("name", "task_status")
                .put("description", "Get the REAL current status of background work given to DK Computer or the coding team (state, real steps done, latest step, result). Call it whenever the user asks how far a task has got / is it done (काम कहाँ तक पहुँचा, हुआ या नहीं). Tell ONLY what it returns - never guess progress or percentages.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
            .put(JSONObject().put("name", "remember_fact")
                .put("description", "Save a personal fact or preference the user told about themselves.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("fact", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("fact"))))))
        if (useSearch) tools.put(JSONObject().put("googleSearch", JSONObject()))
        val setup = JSONObject()
            .put("model", "models/$m")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .apply {
                    // v3.9: 2.5 native-audio pehle "sochta" hai (der). Baatcheet me band - mushkil sawaal deep_think tool se.
                    // (3.1 ka default "minimal" hai; 3.8-live me thinking setting dena mana hai)
                    if (thinkOff && m.startsWith("gemini-2.5")) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.geminiVoice(svc))))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(brain.liveSystemPrompt())))))
            .put("tools", tools)
            .put("realtimeInputConfig", JSONObject()
                .put("automaticActivityDetection", JSONObject()
                    .put("disabled", false)
                    // v3.9.1: wapas HIGH. Shor-gate ke baad server ko sirf (a) phone ki pakdi user-awaaz ya (b) khaamoshi (0) jaati hai -
                    // shor pehle hi ruk chuka. LOW ne user ki asli (dheemi/chhoti) baat bhi "bolna" nahi maani: log me
                    // ~15 baar audioStreamEnd, jawab sirf 3. HIGH = jo phone bheje woh suna jaye.
                    .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    // HIGH = chup hote hi jaldi "bolna khatam" (tez jawab); 500ms = docs ki salaah (500-800ms) ka sabse tez.
                    // (server ka default ~800ms tha.) Phone khud bhi 500ms chup par audioStreamEnd bhejta hai (hybrid VAD).
                    .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    // 100ms = pehla shabd na kate (phone 320ms pehle ki awaaz bhi bhejta hai), khatak/thak par "bolna shuru" nahi
                    .put("prefixPaddingMs", PREFIX_PADDING_MS)
                    .put("silenceDurationMs", SERVER_SILENCE_MS))
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS"))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", if (handle != null) JSONObject().put("handle", handle) else JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        return JSONObject().put("setup", setup).toString()
    }

    private fun connect(key: String, m: String, closed: CompletableDeferred<Pair<Int, String>>, setup: CompletableDeferred<Unit>) {
        ready = false
        val req = Request.Builder().url("$URL?key=$key").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                setupSentAt = System.currentTimeMillis()
                if (!webSocket.send(setupJson(m))) closed.complete(0 to "setup नहीं भेज पाया")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) onServer(text, setup) }
            // Gemini JSON ko binary frame me bhi bhejta hai
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (webSocket === ws) onServer(bytes.utf8(), setup) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val ours = ourClose.remove(webSocket)
                if (ours != null) log("हमने बंद किया ($ours) code=$code")
                else log("server ने बंद किया code=$code reason=${reason.ifBlank { "-" }}")
                closed.complete(code to reason)
                try { webSocket.close(1000, null) } catch (_: Exception) {}
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ourClose.remove(webSocket); closed.complete(code to reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = try { response?.body?.string()?.take(200) } catch (_: Exception) { null }
                val why = (t.javaClass.simpleName + ": " + (t.message ?: "")) + (body?.let { " | $it" } ?: "")
                ourClose.remove(webSocket)
                log("connection गलती: http=${response?.code} $why")
                closed.complete((response?.code ?: 0) to why)
            }
        })
    }

    // ================================================================ server ke sandesh
    /**
     * OkHttp ke listener se koi exception bahar gaya to OkHttp poora WebSocket fail kar deta hai (onFailure) =
     * chalta jawab beech me kho jata aur reconnect. Ek sandesh ki gadbad se session mat todo - log karo, aage chalo.
     */
    private fun onServer(msg: String, setup: CompletableDeferred<Unit>) {
        try { handleServer(msg, setup) } catch (e: Exception) {
            handlerErrors++
            log("server संदेश संभालने में गलती #$handlerErrors (${e.javaClass.simpleName}: ${e.message?.take(80)}) - connection चालू रखा")
        }
    }

    private fun handleServer(msg: String, setup: CompletableDeferred<Unit>) {
        val o = try { JSONObject(msg) } catch (e: Exception) { log("JSON नहीं समझा (${msg.length} bytes)"); return }
        if (o.has("setupComplete")) {
            if (setupSentAt > 0) { trace.rttMs = System.currentTimeMillis() - setupSentAt; log("setup का जवाब ${trace.rttMs} ms (network RTT + server)") }
            // ready + purani (judte samay ki) awaaz ek saath, lock me: mic ka naya tukda purane se pehle na nikle
            synchronized(sendLock) { ready = true; flushPending() }
            setup.complete(Unit)
            sendFirstText(); sendResend()
            return
        }
        lastAct = System.currentTimeMillis()
        o.optJSONObject("sessionResumptionUpdate")?.let {
            if (it.optBoolean("resumable") && it.optString("newHandle").isNotBlank()) handle = it.optString("newHandle")
        }
        o.optJSONObject("goAway")?.let { log("goAway: ${it.optString("timeLeft")} बाकी"); goAway = true }
        o.optJSONObject("voiceActivity")?.optString("type")?.let { t ->
            if (t == "ACTIVITY_START" || t == "ACTIVITY_END") replyWatch.onServerHeard()
            if (t == "ACTIVITY_START") {
                // server ne user ki awaaz pakdi: DK bol raha ho to turant chup (bache tukde bhi nahi), warna Listening
                if (isPlaying()) bargeIn("server ACTIVITY_START") else setMode(Mode.LISTENING)
            }
            if (t == "ACTIVITY_END") {
                val now = System.currentTimeMillis()
                // phone ne is baari "khatam" nahi pakda (dheemi awaaz) -> server ka pakadna hi endpoint
                if (trace.endpointAt == 0L || now - trace.endpointAt > 5000) {
                    trace.reset(); trace.endpointAt = now; trace.speechEndAt = now
                    beginTimeline(now, now, "server VAD")
                }
                if (eosAt == 0L || now - eosAt > 3000) eosAt = now
                firstAudioAt = 0; if (!isPlaying()) setMode(Mode.THINKING)
            }
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { lastServerAt = System.currentTimeMillis(); timeline.event(lastServerAt); replyWatch.onServerAnswered(); runTools(it) }
        o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a ->
            for (i in 0 until a.length()) toolJobs.remove(a.optString(i))?.cancel()
        }
        val sc = o.optJSONObject("serverContent") ?: return
        lastServerAt = System.currentTimeMillis()
        // dropOld = user ne toka, yeh PURANE jawab ke bache tukde hain (phenke jaate hain) - inhe nayi baat ka
        // "jawab aaya" mat gino, warna nayi baat ka watchdog band ho jaata aur server na sune to DK chup rehta
        val oldReply = dropOld
        if (!oldReply || sc.has("inputTranscription")) timeline.event(lastServerAt)
        if (sc.has("inputTranscription")) replyWatch.onServerHeard()
        // docs: model jawab nahi bana raha kyunki use lagta hai user aage bolega (diagnostics - sirf log)
        if (sc.optBoolean("waitingForInput") && !waitingLogged) { waitingLogged = true; log("server: waitingForInput (मॉडल मान रहा है user आगे बोलेगा) • ${timeline.summary()}") }
        if (sc.optBoolean("generationComplete")) timeline.genDone(System.currentTimeMillis())
        if (!oldReply && (sc.has("modelTurn") || sc.has("outputTranscription"))) replyWatch.onServerAnswered()
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            if (!turnActive) startTurn()
            Ducker.duck(svc, "Live: user बोल रहा")
            suspectAt = 0L                     // user ki baat ka text aaya = rokna sahi tha (goonj nahi)
            val u = synchronized(turnLock) { userText.append(t); userText.toString().trim() }
            // phone ne "bolna khatam" pakda ho to deri wahin se ginti (der se aaye text se nahi)
            if (trace.speechEndAt == 0L || System.currentTimeMillis() - trace.speechEndAt > 5000) eosAt = System.currentTimeMillis()
            // caption chal raha hai to woh pehle hi turant likh chuka - yeh der se aaya text screen par mat daalo
            if (!captionOn()) DKState.user("$u…")
            checkMicOff(u)
        }
        // kuch Live model khud bhi bolte-bolte (interim) text dete hain - caption na ho to wahi dikhao
        sc.optJSONObject("interimInputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            Ducker.duck(svc, "Live: user बोल रहा")
            if (!captionOn()) {
                val u = synchronized(turnLock) { userText.toString().trim() }
                DKState.user((if (turnActive) "$u $t" else t).trim() + "…")
            }
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            // user ne toka hai: purane jawab ke bache tukde - mat bajao (server "interrupted" bhejega)
            if (dropOld) { droppedOld++; return@let }
            if (!turnActive) startTurn()
            generating = true
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val inline = p.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                if (!mime.startsWith("audio/pcm")) { log("अनजान output: $mime"); continue }
                val pcm = try { Base64.decode(inline.optString("data"), Base64.DEFAULT) } catch (e: Exception) { log("audio decode गलती"); continue }
                if (pcm.isEmpty()) continue
                recvAudio += pcm.size
                if (!replyStarted) {
                    replyStarted = true
                    // naya jawab: pichhla (agar abhi bhi baj raha tha) poora likho, ginti shuru se
                    commitPending(true)
                    turnRecvBytes = 0; turnPlayedBytes = 0; shownReply = ""
                    // user ki baat yahin tak - aage jo bole woh nayi baat
                    caption?.closeUtterance()?.takeIf { it.isNotBlank() }?.let { turnUser = it; userDone = false }
                }
                timeline.audio(System.currentTimeMillis())
                if (firstAudioAt == 0L) {
                    firstAudioAt = System.currentTimeMillis()
                    if (trace.firstChunkAt == 0L) trace.firstChunkAt = firstAudioAt
                    val from = if (trace.speechEndAt > 0 && firstAudioAt - trace.speechEndAt < 30_000) trace.speechEndAt else eosAt
                    if (from > 0) { lastLatency = firstAudioAt - from; log("देरी: बोलना खत्म → पहला ऑडियो चंक = $lastLatency ms") }
                }
                turnRecvBytes += pcm.size
                playQ.offer(pcm)
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() && !dropOld }?.let { t ->
            if (!turnActive) startTurn()
            generating = true
            // screen par abhi nahi - revealTick() awaaz ke saath-saath dikhata hai
            synchronized(turnLock) { replyText.append(t) }
        }
        // v3.9: Gemini ne sach me Google Search kiya -> sources (title, url) is baari ke jawab ke saath
        (sc.optJSONObject("groundingMetadata") ?: o.optJSONObject("groundingMetadata"))?.let { gm ->
            val add = Grounding.sources(gm)
            if (add.isNotEmpty()) {
                synchronized(turnLock) { turnSources = Grounding.merge(turnSources, add) }
                log("Google Search हुआ - ${add.size} स्रोत: ${add.joinToString { Grounding.host(it.second).ifBlank { it.first } }}")
            }
        }
        if (sc.optBoolean("interrupted")) {
            val now = System.currentTimeMillis()
            replyWatch.onServerHeard()    // server ne user ki awaaz pakdi tabhi roka - "nahi suna" rescue mat bhejo
            log("server: generation रुकी (interrupted) - आवाज़ रोकी" + if (localBargeAt > 0 && now - localBargeAt < 2000) " (फ़ोन ने ${now - localBargeAt} ms पहले ही रोक दी थी)" else "")
            // phone ne user ki awaaz nahi pakdi -> shayad DK ki apni goonj; 2 sec me user ka text na aaye to gino
            if (now - localBargeAt > 1500 && fullDuplex && aecOn) suspectAt = now
            dropOld = false               // purana generation khatam - ab aane wala audio NAYE jawab ka hai
            // sirf toka gaya (purana) turn band karo - toka-ke-boli nayi baat ki samay-rekha (barge ke baad shuru) chalti rahe
            val newAfterBarge = localBargeAt > 0 && now - localBargeAt < 10_000 && timeline.speechEndAt > localBargeAt
            if (timeline.active && !newAfterBarge) { timeline.done(now); log("turn (interrupted): ${timeline.summary()}") }
            if (trace.firstPlayAt == 0L) trace.firstChunkAt = 0L
            flushPlayback()
            // close=false: user abhi bol raha hai (toka hai) - uski nayi baat band mat karo
            if (turnActive) finishTurn(close = false)
            commitPending(false)          // jitna bola utna hi likha rahe
            setMode(Mode.LISTENING)
        }
        if (sc.optBoolean("turnComplete")) {
            val wasDropping = dropOld
            dropOld = false
            if (timeline.active) { timeline.done(System.currentTimeMillis()); log("turn पूरा: ${timeline.summary()}") }
            finishTurn()
            if (wasDropping) commitPending(false)   // toka hua jawab: sirf jitna bola utna hi chat me
            // jawab poora bana; jab DK ki awaaz bajna band ho uske ~1.2s baad peeche ki awaaz dheere-dheere wapas
            Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        }
    }

    private fun startTurn() {
        turnActive = true
        synchronized(turnLock) { userText.clear(); replyText.clear(); turnSources = emptyList() }
    }

    private fun finishTurn(close: Boolean = true) {
        turnActive = false; generating = false
        val (mu, r) = synchronized(turnLock) {
            (userText.toString().trim() to replyText.toString().trim()).also { userText.clear(); replyText.clear() }
        }
        // caption ka text (jo user ne sach me bola, turant likha) pehle; na ho to Live model ka
        // awaaz wala jawab: baat pehli awaaz par band ho chuki. Bina awaaz (sirf tool) wala: abhi band karo
        val snap = if (close && !replyStarted) caption?.closeUtterance().orEmpty() else ""
        val cur = if (!userDone) turnUser else ""               // pichhli baari ka text dobara na lo
        val u = cur.ifBlank { snap }.ifBlank { mu }
        turnUser = u; userDone = true                           // der se aaya pakka text isi line ko theek karega
        if (u.isNotBlank()) DKState.user(u)
        // jawab: awaaz abhi baj rahi hai to revealTick() saath-saath poora karega
        synchronized(turnLock) {
            if (r.isNotBlank()) { pendingReply = r; pendingSources = Grounding.merge(turnSources, toolSources); toolSources = emptyList() }
            turnSources = emptyList()
        }
        firstAudioAt = 0; replyStarted = false
        if (!isPlaying()) setMode(Mode.LISTENING)
    }

    /** Caption is key par chal raha hai -> "आप:" sirf usi se (Live model ka der se aaya text screen par nahi, warna text aage-peeche hota) */
    private fun captionOn() = caption?.let { it.ready && it.working } == true

    /** Caption se turant shabd - "आप:" line */
    private fun onCaption(t: String, final: Boolean) {
        lastAct = System.currentTimeMillis()
        if (t.isNotBlank()) Ducker.duck(svc, "Live: user बोल रहा")   // user ke shabd aaye -> peeche ka gaana/video dheema
        // DK bol raha hai aur user ke asli shabd aaye (mic DK bolte samay sirf tez awaaz bhejta hai) -> DK chup
        // (caption ko DK bolte samay sirf phone ki pakdi user-awaaz jaati hai). Jawab abhi ban raha ho to
        // yeh pichhli baat ka der se aaya text ho sakta hai - tab phone/server ka barge-in hi rokega
        suspectAt = 0L
        if (bargeGate.state == BargeGate.State.TENTATIVE) {
            val dkNow = synchronized(turnLock) { replyText.toString() + " " + (pendingReply ?: "") }
            // sendLock: CONFIRMED hote hi mic thread naya tukda bhejta hai - pehle shak ke samay ki (purani) awaaz jaye
            val ok = synchronized(sendLock) {
                // DK ki apni awaaz ya pichhli baat ka der se aaya text = user ka naya bolna nahi
                if (BargeGate.isEcho(t, turnUser) || !bargeGate.onText(t, dkNow)) false
                else { confirmBarge("caption: ${t.take(30)}"); true }
            }
            if (!ok) { log("caption: \"${t.take(40)}\" - पक्का नहीं, जवाब जारी"); return }
        } else if (isPlaying()) return        // DK bol raha hai aur shak nahi = shor/goonj ka text, dikhao bhi mat
        DKState.user("$t…")
        if (final) checkMicOff(t)        // andaze (interim) par mic band nahi - "माइक बंद मत करो" bhi ho sakta hai
    }

    /** Pichhli baat ka pakka text der se aaya - wahi line theek karo, nayi line nahi */
    private fun onLateCaption(t: String) {
        if (!userDone) { turnUser = t; DKState.user("$t…") } else DKState.fixLastUser(t)
        checkMicOff(t)
    }

    /** DK ki bachi awaaz rokkar user ko suno; jitna bola utna hi text */
    private fun stopForUser(why: String) {
        log("user बोलने लगा ($why) - बची आवाज़ रोकी")
        flushPlayback()
        commitPending(false)
        setMode(Mode.LISTENING)
    }

    @Volatile private var micOffSent = false
    private fun checkMicOff(t: String) {
        if (micOffSent || !micOffCommand(t)) return
        // DK khud bol raha tha / abhi bola ("ठीक है, माइक बंद") - apni hi awaaz ko hukm mat samjho
        val now = System.currentTimeMillis()
        if (isPlaying() || now - playEnd < 1500 || svc.speaker.busy || now - svc.speaker.lastSpokeAt < 2500) {
            log("'माइक बंद' सुना पर DK खुद बोल रहा था - अनदेखा: $t"); return
        }
        micOffSent = true
        android.os.Handler(android.os.Looper.getMainLooper()).post { svc.stopLive("user ने कहा: ${t.takeLast(60)}"); micOffSent = false }
    }


    /**
     * DK ka text uski awaaz ke saath: jitni awaaz baj chuki, utne shabd.
     * (Gemini text pehle bhej deta hai aur awaaz baad me bajti hai - isliye seedha dikhane par text aage bhaagta tha.)
     */
    private fun revealTick() {
        val (pend, live) = synchronized(turnLock) { pendingReply to replyText.toString().trim() }
        val full = pend ?: live
        if (full.isBlank()) return
        if (pend != null && !isPlaying()) { commitPending(true); return }
        val recv = turnRecvBytes
        if (recv <= 0L) return                                  // awaaz abhi shuru nahi hui
        val played = (turnPlayedBytes - LEAD_BYTES).coerceAtLeast(0L)
        if (played <= 0L) return
        val words = full.split(WS).filter { it.isNotEmpty() }
        val frac = (played.toDouble() / recv).coerceIn(0.0, 1.0)
        val n = kotlin.math.ceil(frac * words.size).toInt().coerceIn(1, words.size)
        val shown = words.take(n).joinToString(" ")
        if (shown != shownReply) { shownReply = shown; DKState.replyLive(shown) }
    }

    /** Ruka hua jawab chat me pakka karo. full=false: sirf jitna bola gaya (beech me toka) */
    private fun commitPending(full: Boolean) {
        val (text, src) = synchronized(turnLock) {
            (pendingReply to pendingSources).also { pendingReply = null; pendingSources = emptyList() }
        }
        if (text == null) return
        val out = if (full || shownReply.isBlank()) text else "$shownReply…"
        shownReply = ""
        // search hua = chat/caption me click hone wale sources; nahi hua = khali (koi "Sources" nahi)
        DKState.reply(out, src)
    }

    // ================================================================ phone ke kaam (function calling)
    private fun runTools(calls: JSONArray) {
        for (i in 0 until calls.length()) {
            val fc = calls.optJSONObject(i) ?: continue
            val id = fc.optString("id"); val name = fc.optString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
            log("tool: $name ${args.toString().take(80)}")
            val tStart = System.currentTimeMillis()
            // LAZY: pehle map me darj, phir shuru - warna tez tool (remember_fact) map me aane se pehle hi remove() kar deta tha
            val j = svc.scope.launch(start = CoroutineStart.LAZY) {
              try {
                val result = try { when (name) {
                    "phone_task" -> phoneTask(args.optString("request"))
                    "task_status" -> TaskStatus.summary()
                    "remember_fact" -> { Memory.addFact(svc, args.optString("fact")); "saved" }
                    "computer_task" -> {
                        val t = args.optString("task").trim()
                        val yn = if (ComputerSession.ask != null) ComputerVoice.yesNo(t) else null
                        when {
                            yn != null -> { ComputerSession.answer(yn); if (yn) "Permission given - DK Computer continues." else "Permission refused - DK Computer will not do it." }
                            !Prefs.computerOn(svc) -> "DK Computer is OFF in settings - tell the user to turn it on (सेटिंग्स → DK कंप्यूटर)."
                            t.length < 3 -> "Ask the user what DK Computer should do."
                            else -> {
                                val wasBusy = ComputerSession.busy
                                ComputerSession.start(svc, t, voice = true)?.let { "Could not start: $it" }
                                    ?: if (wasBusy) "Queued - DK Computer will start it after the current task."
                                    else "Started. DK Computer shows each step on its screen and will notify when done (result also in the notification)."
                            }
                        }
                    }
                    "coding_task" -> {
                        val t = args.optString("task")
                        // pehle setting jaancho - band ho to merge/sujhav ka faisla bhi nahi (AgentRunner jaisa)
                        when {
                            !Prefs.coderOn(svc) -> "Coding agent is OFF in settings - tell the user to turn it on (सेटिंग्स → कोडिंग एजेंट)."
                            else -> CoderTeam.voiceDecision(svc, t) ?: when {
                                // 7-एजेंट चालू = पुराना टीम switch ज़रूरी नहीं
                                !Prefs.coderSeven(svc) && !Prefs.coderTeam(svc) -> "Coding team is OFF in settings (सेटिंग्स → कोडिंग एजेंट → टीम)."
                                else -> CoderTeam.start(svc, t, fromVoice = true)?.let { "Could not start: $it" }
                                    ?: if (Prefs.coderSeven(svc)) "Started (11-agent team). FIRST its prompt engineer shows the user what it understood and WAITS: when the user confirms (हाँ / yes), call this tool again with task='हाँ'; if the user corrects or answers its question, call this tool again with exactly that answer; 'रद्द' cancels. It is NOT done yet - real progress: call task_status. You will get a SYSTEM NOTE when it really finishes; never say it is done before that."
                                    else "Started. Builder writes, Reviewer checks, Coordinator pushes and will notify when done. It is NOT done yet - real progress: call task_status."
                            }
                        }
                    }
                    "deep_think" -> {
                        DKState.set(Mode.THINKING)
                        try {
                            withTimeoutOrNull(45_000) { BrainManager(svc).think(args.optString("question")) }
                                ?.takeIf { it.text.isNotBlank() }
                                ?.also { if (it.sources.isNotEmpty()) toolSources = Grounding.merge(toolSources, it.sources) }
                                ?.text ?: "Deep thinking timed out - answer it yourself carefully."
                        } catch (e: Exception) {
                            log("deep_think fail: ${e.message}")
                            "Deep thinking unavailable - answer it yourself carefully."
                        }
                    }
                    else -> "unknown function"
                } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // tool toota - phir bhi Gemini ko jawab bhejo, warna model hamesha intezaar karta
                    log("tool $name fail: ${e.message}")
                    "Tool failed (${e.javaClass.simpleName}) - tell the user it could not be done."
                }
                val resp = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                    JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result.take(if (name == "deep_think") 4000 else 1500))))))
                log("tool $name ${(System.currentTimeMillis() - tStart)} ms: ${result.take(80)}")
                // ab se deri ginti: tool ka jawab bheja -> pehli awaaz (tool ka samay alag dikhta hai)
                eosAt = System.currentTimeMillis(); firstAudioAt = 0
                if (ws?.send(resp.toString()) != true) log("tool जवाब नहीं भेज पाया")
              } finally {
                // hamesha hatao (fail/cancel par bhi) - warna idle-exit aur watchdog band rehte
                coroutineContext[Job]?.let { me -> toolJobs.remove(id, me) }
              }
            }
            toolJobs[id] = j
            j.start()
        }
    }

    /**
     * v3.29: phone_task तेज़ + सच्चा.
     * Gemini Live का tool call रुकने वाला है (मॉडल जवाब आने तक चुप) - इसलिए यहाँ देर = DK की चुप्पी.
     * 1) बात खत्म होते ही वही काम हो चुका (scheduleFast) -> तुरंत "हो चुका"
     * 2) सीधा काम (QuickCommands) -> खुद, ~0.1-1s
     * 3) बाकी AgentRunner; 20s में पूरा न हो तो Gemini को सच ("अभी चल रहा") और पूरा होने पर उसे बताओ
     * पहले: 60s तक इंतज़ार और नतीजा न मिले तो "done" (झूठा "हो गया").
     */
    private suspend fun phoneTask(req: String): String {
        val wake = Prefs.wakeWords(svc)
        val fg = svc.currentPackage()
        val tc = System.nanoTime()
        val q = LiveFast.pick(req, wake, fg)
        log("रूटर: phone_task \"${req.take(30)}\" -> ${q?.action?.optString("type") ?: "फ़ोन पर पूरा नहीं"} (${(System.nanoTime() - tc) / 1_000_000} ms)")
        var same = fastMemo.same(q, System.currentTimeMillis())
        if (same != LiveFast.Memo.NOT_SAME) {
            val until = System.currentTimeMillis() + 4000
            while (same == null && System.currentTimeMillis() < until) { delay(80); same = fastMemo.same(q, System.currentTimeMillis()) }
            log("phone_task = अभी-अभी किया काम, दोबारा नहीं: $req")
            val r = same
            if (r != null && r != LiveFast.Memo.NOT_SAME) {
                if (LiveFast.ok(q!!.action.optString("type"), r) || LocalRouter.final(r)) return LiveFast.quickResult(q, r, already = true)
                // तुरंत वाला नहीं चला (ऐप नहीं मिला / वीडियो सीधे नहीं चला) -> AgentRunner संभाले
            } else if (r == null) return "STARTED just now and still opening. Tell the user in 2-5 words."
        } else if (q != null && fastMemo.begin(q, System.currentTimeMillis())) {
            DKState.set(Mode.WORKING)
            val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
            fastMemo.finish(q, r)
            log("phone_task सीधे (बिना AI): ${q.action} = ${r.take(60)}")
            // v3.33: ऐप है ही नहीं / कॉन्टैक्ट नहीं / user से पूछना है = AI से कुछ नहीं सुधरेगा -> सीधे सच
            if (LiveFast.ok(q.action.optString("type"), r) || LocalRouter.final(r)) return LiveFast.quickResult(q, r, already = false)
        }
        DKState.set(Mode.WORKING)
        // v3.33: आधा काम फ़ोन पर ("Flipkart खोलो, X सर्च करो") + बाकी ("पहला प्रोडक्ट कार्ट में डालो") AgentRunner को
        var note = ""
        if (q == null) {
            val sp = LocalRouter.split(req, wake, fg)
            val pre = sp.local
            if (pre != null && sp.rest != null && fastMemo.begin(pre, System.currentTimeMillis())) {
                val r = try { svc.perform(pre.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
                fastMemo.finish(pre, r)
                log("phone_task: पहला हिस्सा फ़ोन पर (${pre.action.optString("type")}) = ${r.take(60)}, बाकी AgentRunner")
                if (!LiveFast.ok(pre.action.optString("type"), r) && LocalRouter.final(r)) return LiveFast.quickResult(pre, r, already = false)
                note = LocalRouter.DONE_NOTE + " " + LiveFast.describe(pre.action) + " -> " + r.take(160) +
                    "\nContinue the rest of the user's goal from the CURRENT screen."
            }
        }
        val before = DKState.lastReply
        val job = agent.run(req, speak = false, note = note)
        val finished = withTimeoutOrNull(20_000) { job.join() } != null
        val reply = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
        if (!finished) {
            log("phone_task 20s में पूरा नहीं - Gemini को सच बताया (चल रहा है), पूरा होने पर बताऊँगा")
            svc.scope.launch {
                val done = withTimeoutOrNull(120_000) { job.join() } != null
                if (!done) job.cancel()
                val rep = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
                    ?: if (!done) "It could not be finished in time and was stopped." else null
                // DK बोल रहा हो तो बीच में नहीं - चुप होने तक (10s तक) रुको
                withTimeoutOrNull(10_000) { while (isPlaying() || generating) delay(150) }
                if (stopped || !ready) return@launch
                try { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", LiveFast.lateNote(req, rep))).toString()) } catch (_: Exception) {}
                log("phone_task बाद में पूरा (${if (done) "हुआ" else "रोका"}) - Gemini को बताया")
            }
        }
        return LiveFast.agentResult(finished, reply)
    }

    // ================================================================ mic -> stream
    private val pending = ArrayDeque<ByteArray>()       // judne/dobara judne ke beech ki baat (1 sec tak)

    /** sendLock ke andar hi bulao (setupComplete / garam connection) */
    private fun flushPending() = synchronized(sendLock) {
        val list = synchronized(pending) { pending.toList().also { pending.clear() } }
        list.forEach { sendAudio(it) }
        if (list.isNotEmpty()) log("जुड़ते समय की ${list.size * CHUNK_MS} ms आवाज़ भेजी")
    }

    /**
     * silent=true: khaamoshi (shoonya) ka tukda - SilenceStreamGate tay karta hai bhejna hai ya nahi.
     * sendLock: teen thread yahan bhejte hain - ek hi kram me jaye.
     */
    private fun sendAudio(pcm: ByteArray, silent: Boolean = false) = synchronized(sendLock) {
        var end = false
        if (silent) {
            when (liveGate.onSilence()) {
                SilenceStreamGate.Action.SKIP -> { silenceSkipped++; return@synchronized }
                SilenceStreamGate.Action.SEND_THEN_END -> end = true
                SilenceStreamGate.Action.SEND -> {}
            }
        } else liveGate.onReal()
        val w = ws
        if (w == null || !ready) {
            synchronized(pending) { pending.addLast(pcm); while (pending.size > 2000 / CHUNK_MS) { pending.removeFirst(); dropped++ } }
            return@synchronized
        }
        // network dheema: line me atka data. Pehle khaamoshi chhodo (32KB), asli awaaz sirf bahut atakne par (256KB) -
        // OkHttp ki line se purana nahi hata sakte, isliye pehle asli baat ke naye tukde hi gir jaate the.
        if (w.queueSize() > (if (silent) 32 * 1024 else 256 * 1024)) { dropped++; return@synchronized }
        val msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=$IN_RATE\",\"data\":\"" +
            Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}"
        if (w.send(msg)) { sent++; sentBytes += pcm.size } else dropped++
        // 1s khaamoshi bheji -> stream band (docs: audioStreamEnd), ab asli awaaz aane tak upload nahi
        if (end) try { w.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
    }

    /** Caption socket: asli awaaz hamesha; khaamoshi 1s tak, phir audioStreamEnd aur band */
    private fun capSend(pcm: ByteArray, silent: Boolean = false) {
        val c = caption ?: return
        if (!silent) { capGate.onReal(); c.send(pcm); return }
        when (capGate.onSilence()) {
            SilenceStreamGate.Action.SEND -> c.send(pcm)
            SilenceStreamGate.Action.SEND_THEN_END -> { c.send(pcm); c.endOfSpeech() }
            SilenceStreamGate.Action.SKIP -> {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { log("यह फ़ोन 16kHz PCM16 mono नहीं देता (min=$min)"); return null }
        // VOICE_COMMUNICATION = phone ka echo-cancel (DK ki apni awaaz mic me kam), na chale to baaki
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, CHUNK_SAMPLES * 2 * 6))
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                val sid = r.audioSessionId
                val aec = try { AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                val ns = try { NoiseSuppressor.isAvailable() && NoiseSuppressor.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                val agc = try { AutomaticGainControl.isAvailable() && AutomaticGainControl.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                aecOn = aec
                log("माइक: ${r.sampleRate} Hz, mono, PCM16, source=$src, AEC=$aec NS=$ns AGC=$agc, buffer=${min}B" +
                    " • शोर-गेट चालू: Gemini को सिर्फ़ यूज़र की आवाज़; DK बोलते समय सिर्फ़ पक्के शब्दों पर रुकेगा")
                return r
            } catch (e: Exception) { log("माइक source $src: ${e.message}") }
        }
        return null
    }

    /**
     * Phone ne pakda: user ~500ms se chup (hybrid VAD). Turant audioStreamEnd -> server apna chup-intezaar chhod kar
     * jawab banana shuru karta hai. Na chale to server ka apna VAD (500ms) fallback hai.
     */
    private fun onLocalEndOfSpeech(now: Long) {
        val w = ws
        trace.speechEndAt = turnDet.lastVoiceAt.takeIf { it > 0 } ?: now
        trace.endpointAt = now
        trace.firstChunkAt = 0; trace.firstPlayAt = 0
        eosAt = trace.speechEndAt; firstAudioAt = 0
        trace.backlogBytes = w?.queueSize() ?: 0
        beginTimeline(trace.speechEndAt, now, "फ़ोन VAD")
        if (trace.backlogBytes == 0L) timeline.upload(now)
        if (ready && w != null) { try { w.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {} }
        caption?.endOfSpeech()
        // DK abhi jawab bana/bol raha hai = server sun chuka; warna 2.5s me kuch na aaye to "nahi suna".
        // dropOld = user ne purana jawab toka tha: woh "generating" PURANE jawab ka hai, is nayi baat ka nahi -
        // pehle yahan watchdog band ho jaata tha aur toka-ke-boli baat ko server na sune to DK chup reh jaata.
        if ((generating && !dropOld) || isPlaying()) replyWatch.onServerAnswered() else replyWatch.onEndOfSpeech(now, turnDet.lastSpeechMs)
        scheduleFast()
        log("फ़ोन: बोलना खत्म (${now - trace.speechEndAt} ms चुप) → audioStreamEnd भेजा" +
            if (trace.backlogBytes > 16 * 1024) " • नेटवर्क में ${trace.backlogBytes / 1024}KB अटका (upload धीमा)" else "")
        // sochne ka halka pulse - sannata na lage
        if (!isPlaying() && DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
    }

    /**
     * v3.9.1: user ne asli baat boli, phone ne audioStreamEnd bheja, par 2.5s me Gemini se koi nishaani nahi
     * (na ACTIVITY_START, na text, na awaaz) = server ne awaaz suni hi nahi. Pehle DK yahin chup ho jaata tha.
     * Ab: caption ne shabd likhe hain to wahi text Gemini ko seedha bhejo (jawab pakka aaye).
     * Shabd nahi = shor tha -> Listening + peeche ki awaaz wapas (pehle 40-80s dheemi atki rehti thi).
     */
    private fun onNoReply() {
        if (!ready || (generating && !dropOld) || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech) return
        val t = caption?.text().orEmpty().trim()
        unheard++
        if (!BargeGate.realWords(t)) {
            log("Gemini से 2.5s में कुछ नहीं आया और caption में शब्द नहीं - शोर था, सुन रहा हूँ (#$unheard)")
            if (DKState.mode == Mode.THINKING) setMode(Mode.LISTENING)
            Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
            return
        }
        val now = System.currentTimeMillis()
        eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
        log("आवाज़ वाली बात की समय-रेखा: ${timeline.summary()}")
        timeline.begin(now, now, "caption text (नहीं सुना था)"); waitingLogged = false
        replyWatch.onRescued()               // ek baat ke liye ek hi baar (atka-nudge bhi nahi)
        val ok = try { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) == true } catch (_: Exception) { false }
        if (ok) rescued++
        log("Gemini ने आवाज़ नहीं सुनी (2.5s में कुछ नहीं) - caption का text सीधे भेजा: \"${t.take(60)}\"" + if (ok) "" else " (भेज नहीं पाया)")
        if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
    }

    /**
     * v3.29: Gemini ne baat SUNI (text aaya) par 4.5s tak jawab shuru nahi kiya (na awaaz, na tool).
     * Pehle: kuch nahi - 12s baad sirf "Listening", user ko jawab hi nahi milta tha ("सुनता है पर जवाब नहीं देता").
     * Ab: wahi baat (caption ka text) ek baar text me dobara - jawab pakka shuru ho.
     */
    private fun onStalled() {
        if (!ready || (generating && !dropOld) || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech) return
        val t = caption?.text().orEmpty().trim().ifBlank { synchronized(turnLock) { userText.toString().trim() } }
        if (!BargeGate.realWords(t)) return
        val now = System.currentTimeMillis()
        eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
        log("अटकी बात की समय-रेखा: ${timeline.summary()}")
        timeline.begin(now, now, "अटका - text में दोबारा"); waitingLogged = false
        val ok = try { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) == true } catch (_: Exception) { false }
        if (ok) stalledN++
        log("Gemini ने सुना पर 4.5s में जवाब शुरू नहीं किया - वही बात text में दोबारा भेजी: \"${t.take(60)}\"" + if (ok) "" else " (भेज नहीं पाया)")
        if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
    }

    /**
     * v3.29: बात खत्म -> caption का text 650ms में स्थिर हो और सीधा काम हो (ऐप खोलो, टॉर्च, आवाज़, YouTube पर चलाओ…)
     * तो Gemini के सोचने/tool call का इंतज़ार किए बिना तुरंत करो. Gemini बाद में वही phone_task माँगे तो दोबारा नहीं (Memo).
     * (अधूरे text पर नहीं: "टॉर्च" सुनकर जला दे और पूरी बात "टॉर्च बंद करो" हो - इसलिए दो बार देखकर ही)
     */
    private fun scheduleFast() {
        if (ComputerSession.ask != null) return
        fastJob?.cancel()
        fastJob = svc.scope.launch {
            fun cur() = caption?.text().orEmpty().trim().ifBlank { if (!userDone) turnUser.trim() else "" }
            delay(300); val a = cur()
            delay(350); val b = cur()
            if (b.isBlank() || a != b || turnDet.inSpeech || isPlaying() || stopped) return@launch
            val tc = System.nanoTime()
            val q = LiveFast.pick(b, Prefs.wakeWords(svc), svc.currentPackage(), fast = true)
            val classMs = (System.nanoTime() - tc) / 1_000_000
            if (q == null) { log("रूटर: \"${b.take(30)}\" फ़ोन पर सीधा नहीं - Gemini संभालेगा (वर्गीकरण $classMs ms)"); return@launch }
            if (!fastMemo.begin(q, System.currentTimeMillis())) return@launch
            val t0 = System.currentTimeMillis()
            val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
            fastMemo.finish(q, r); fastN++
            log("तुरंत काम (Gemini से पहले) \"${b.take(40)}\" -> ${q.action.optString("type")} = ${r.take(60)} (वर्गीकरण $classMs ms, काम+जाँच ${System.currentTimeMillis() - t0} ms)")
        }
    }

    @Volatile private var loudMs = 0L

    /**
     * PAKKA: user sach me bol raha hai (caption me asli shabd / caption band ho to lagatar awaaz).
     * Ab DK chup + purane tukde phenko + user ki awaaz (shak ke samay se) Gemini ko -> server generation rokta hai.
     */
    private fun confirmBarge(why: String) {
        realBarges++
        log("यूज़र सच में बोल रहा ($why) - DK चुप, सुन रहा हूँ")
        // sendLock: shak ke samay ki (purani) awaaz pehle, mic ka naya tukda uske baad (ulti-pulti awaaz = server na samjhe)
        synchronized(sendLock) {
            val buf = synchronized(bargeBuf) { bargeBuf.toList().also { bargeBuf.clear() } }
            bargeIn(why)
            track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
            turnDet.forceSpeech(System.currentTimeMillis())     // gate khula: user ki baaki baat bhi jaye
            replyWatch.onSpeechStart()                         // nayi baat: iska apna "suna / jawab" hisaab
            buf.forEach { sendAudio(it) }
        }
    }

    /** Dheere bolne par bhi saaf awaaz Gemini tak (v3.7) */
    private val boost = MicBoost()

    private suspend fun micLoop() = coroutineScope {
        var r = openMic() ?: throw Fatal("माइक नहीं खुला (कोई दूसरा ऐप माइक इस्तेमाल कर रहा है?)", recoverable = true)
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Fatal("माइक रिकॉर्डिंग शुरू नहीं हुई", recoverable = true)
            val buf = ShortArray(CHUNK_SAMPLES)
            val bytes = ByteArray(CHUNK_SAMPLES * 2)
            val zeros = ByteArray(CHUNK_SAMPLES * 2)
            // DK bolte samay (gate mode) ki pichhli 320ms asli awaaz - user ki pakdi awaaz ka shuru ka hissa na kate
            val preroll = ArrayDeque<ByteArray>()
            var last = 0L; var zeroRun = 0; var errRun = 0
            loudMs = 0L
            while (isActive && !stopped) {
                // poora 40ms tukda padho (beech ka khaali hissa = gap). Koi jama nahi: har 40ms turant bhejo
                var got = 0
                while (got < CHUNK_SAMPLES && isActive) {
                    val n = r.read(buf, got, CHUNK_SAMPLES - got)
                    if (n < 0) { readErr++; errRun++; break }
                    got += n
                }
                if (got < CHUNK_SAMPLES) {
                    log("माइक पढ़ने में गलती ($errRun)")
                    if (errRun >= 5) {           // mic atak gaya -> dobara kholo
                        try { r.stop() } catch (_: Exception) {}; r.release()
                        delay(300)
                        r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला", recoverable = true)
                        r.startRecording(); errRun = 0
                    }
                    continue
                }
                errRun = 0
                val now = System.currentTimeMillis()
                if (last > 0 && now - last > CHUNK_MS * 3) { gaps++; if (gaps % 20 == 1L) log("माइक में ${now - last} ms का गैप (#$gaps)") }
                last = now
                var sum = 0.0
                for (i in 0 until CHUNK_SAMPLES) { val s = buf[i].toInt(); sum += (s * s).toDouble() }
                val rms = sqrt(sum / CHUNK_SAMPLES)
                val playing = isPlaying()
                // dheemi awaaz tez (v3.7): DK chup ho tab hi - DK ki apni goonj ko tez nahi karna
                if (!playing) boost.apply(buf, CHUNK_SAMPLES)
                for (i in 0 until CHUNK_SAMPLES) {
                    val s = buf[i].toInt()
                    bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()   // little-endian
                }
                if (sum == 0.0) { if (++zeroRun == 75) log("माइक से 3 सेकंड से बिल्कुल आवाज़ नहीं (कोई दूसरा ऐप माइक ले रहा है?)") } else zeroRun = 0
                val chunk = bytes.copyOf()
                if (playing) {
                    // ---- DK bol raha hai: mic band NAHI, har 40ms suno. Par Gemini ko sirf PAKKE user shabd.
                    turnDet.reset()
                    if (bargeDet.ambient == 0.0) bargeDet.ambient = turnDet.noise
                    when (bargeGate.state) {
                        BargeGate.State.IDLE -> {
                            if (bargeDet.feed(rms, now)) {
                                // SHAK: tez awaaz. DK ki awaaz halki (rokna nahi), caption model ko suno - shabd hain?
                                bargeGate.suspect(now); barging = true; loudMs = 0L
                                track?.let { try { it.setVolume(0.3f) } catch (_: Exception) {} }
                                log("तेज़ आवाज़ (rms=${rms.toInt()}, गूँज=${bargeDet.echoLevel().toInt()}, शोर=${bargeDet.ambient.toInt()}) - यूज़र है? शब्द सुन रहा हूँ, DK धीमा")
                                synchronized(bargeBuf) { bargeBuf.clear(); bargeBuf.addAll(preroll); bargeBuf.addLast(chunk) }
                                preroll.forEach { capSend(it) }; preroll.clear()
                                capSend(chunk)
                            } else {
                                preroll.addLast(chunk); while (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
                                capSend(zeros, silent = true)
                            }
                            sendAudio(zeros, silent = true); gatedChunks++
                        }
                        BargeGate.State.TENTATIVE -> {
                            loudMs = if (rms > maxOf(bargeDet.ambient * 2.5, 800.0)) loudMs + CHUNK_MS else 0L
                            synchronized(bargeBuf) { bargeBuf.addLast(chunk); while (bargeBuf.size > 50) bargeBuf.removeFirst() }
                            capSend(chunk)
                            sendAudio(zeros, silent = true); gatedChunks++
                            if (bargeGate.expired(now, captionOn(), loudMs)) {
                                // shabd nahi aaye = shor/TV/log. DK ka jawab poori awaaz me chalta rahe
                                falseAlarms++
                                log("शब्द नहीं मिले - पीछे का शोर था, जवाब जारी (#$falseAlarms)")
                                track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
                                bargeDet.rearm(rms); barging = false
                                synchronized(bargeBuf) { bargeBuf.clear() }
                            } else if (bargeGate.state == BargeGate.State.CONFIRMED) confirmBarge("लगातार आवाज़ (caption बंद)")
                        }
                        BargeGate.State.CONFIRMED -> { sendAudio(chunk); capSend(chunk) }
                    }
                } else {
                    // ---- DK chup: phone par hi "bolna shuru / khatam" + SHOR-GATE:
                    // Gemini/caption ko sirf user ki awaaz (320ms pehle se); baaki samay khaamoshi.
                    // (Peeche ka lagatar shor bhejne par server "user abhi bol raha" maan kar jawab rokta tha.)
                    if (barging) bargeUntil = now + 1500
                    barging = false; bargeDet.reset(); bargeDet.ambient = 0.0
                    if (bargeGate.state != BargeGate.State.IDLE) { bargeGate.reset(); track?.let { try { it.setVolume(1f) } catch (_: Exception) {} } }
                    if (DKState.mode == Mode.LISTENING) DKState.level = (rms / 3500.0).toFloat().coerceIn(0f, 1f)
                    val ev = turnDet.feed(rms, now)
                    if (ev == TurnDetector.Event.SPEECH_START) { preroll.forEach { sendAudio(it); capSend(it) }; preroll.clear() }
                    // bolte samay + baat khatam hone tak (aakhri 500ms chup bhi) asli awaaz; warna khaamoshi
                    // (khaamoshi 1s tak jaati hai, phir audioStreamEnd aur upload band - SilenceStreamGate)
                    if (turnDet.inSpeech || ev == TurnDetector.Event.SPEECH_END) { sendAudio(chunk); capSend(chunk) }
                    else {
                        preroll.addLast(chunk); while (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
                        sendAudio(zeros, silent = true); capSend(zeros, silent = true); gatedChunks++
                    }
                    when (ev) {
                        TurnDetector.Event.SPEECH_START -> {
                            lastAct = now; trace.reset(); replyWatch.onSpeechStart()
                            // user bolne laga -> turant Listening (Thinking/Idle se bhi; phone ka kaam chal raha ho to nahi)
                            if (DKState.mode != Mode.WORKING) setMode(Mode.LISTENING)
                        }
                        TurnDetector.Event.SPEECH_END -> onLocalEndOfSpeech(now)
                        else -> {}
                    }
                }
            }
        } finally {
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
            try { if (ready) ws?.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
            DKState.level = 0f
        }
    }

    // ================================================================ Gemini ki awaaz bajana
    private fun startPlayer() {
        stopPlayer()
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, OUT_RATE * 2 / 5))      // ~200ms buffer: jaldi shuru, atke bina
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        log("स्पीकर: $OUT_RATE Hz PCM16 mono stream")
        playThread = Thread {
            var speakingSet = false
            while (!Thread.currentThread().isInterrupted) {
                val b = try { playQ.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                val tr = track ?: break
                if (b == null) {
                    if (speakingSet && !isPlaying()) {
                        speakingSet = false
                        if (!turnActive && DKState.mode == Mode.SPEAKING) setMode(Mode.LISTENING)
                    }
                    continue
                }
                val g = gen
                // pehla tukda: haalat PEHLE "Speaking" (UI turant), awaaz likhne ke baad baaki kaam
                val first = !speakingSet || DKState.mode != Mode.SPEAKING
                if (first) { speakingSet = true; setMode(Mode.SPEAKING) }
                val now = System.currentTimeMillis()
                playEnd = maxOf(playEnd, now) + b.size * 1000L / (OUT_RATE * 2)
                var off = 0
                while (off < b.size && g == gen) {
                    val n = try { tr.write(b, off, b.size - off) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    off += n
                    if (g == gen) turnPlayedBytes += n
                }
                if (off > 0) timeline.play(System.currentTimeMillis())
                if (trace.firstChunkAt > 0 && trace.firstPlayAt == 0L && off > 0) {
                    trace.firstPlayAt = System.currentTimeMillis()
                    trace.summary()?.let { sm ->
                        lastLatency = trace.firstPlayAt - trace.speechEndAt
                        lastBreakdown = "$sm • सबसे बड़ा हिस्सा: ${trace.bottleneck()}"
                        log("देरी के हिस्से: $lastBreakdown")
                    }
                    trace.reset()                // agli baari nayi ginti (purane aankde na chipkein)
                }
                if (first) svc.speaker.ensureAudible()      // awaaz mute to nahi (system calls - awaaz shuru hone ke baad)
            }
        }.apply { name = "dk-live-play"; isDaemon = true; start() }
    }

    /** Beech me toka: jo awaaz kataar me hai sab hatao, turant chup */
    private fun flushPlayback() {
        gen++
        playQ.clear()
        playEnd = 0L
        track?.let { try { it.pause(); it.flush(); it.play() } catch (_: Exception) {} }
    }

    private fun stopPlayer() {
        playThread?.interrupt(); playThread = null
        playQ.clear(); playEnd = 0L
        track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        track = null
    }
}
