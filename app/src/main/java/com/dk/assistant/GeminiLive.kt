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
        const val SERVER_SILENCE_MS = 800   // v3.46: 550 -> 800. छोटी बात पर फ़ोन खुद 550ms पर "खत्म" भेजता है; यह सिर्फ़ तब जब वाक्य अधूरा लगे ("और…", "तो…")
        /** Phone ka VAD: itni der chup = turant audioStreamEnd (server ka intezaar nahi) */
        const val CLIENT_END_MS = 550
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
        /** v3.41: phone_task ka lamba kaam (AgentRunner) - itni der baad Gemini ko "chal raha hai" */
        const val AGENT_WAIT_MS = 7_000L
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
            // tool का जवाब बाकी हो तो भी नहीं (Gemini tool के जवाब का इंतज़ार कर रहा हो तब text = 1007 का ख़तरा)
            withTimeoutOrNull(15_000) { while (isPlaying() || generating || toolJobs.isNotEmpty()) delay(150) }
            if (stopped || !ready) return@launch
            lastEvidenceAt = System.currentTimeMillis()
            try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", TaskStatus.endNote(job))).toString()) } catch (_: Exception) {}
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
    /** v3.41: aakhri tool call kab aaya - tool wali baari ka samay model ki raftaar me mat gino */
    @Volatile private var lastToolAt = 0L
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
    private val turnDet = TurnDetector(CHUNK_MS, endSilenceMs = CLIENT_END_MS, loudNoiseFactor = 2.0,
        longSpeechMs = TurnEnd.LONG_SPEECH_MS, longEndSilenceMs = TurnEnd.LONG_END_SILENCE_MS, steadyResetMs = 6_000)
    /** वही पहचानी बात router में बार-बार न जाए (हर छोटी चुप्पी पर पूरा caption दोबारा जाँचा जाता था) */
    private val routeDedupe = RouteDedupe()
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
    /** v3.39: टूटने से पहले भेजे आख़िरी संदेश (किस्म + आकार) - 1007 की असली वजह लॉग में */
    private val wire = SentTrace()
    private val drop1007 = Drop1007()
    /** "बात खत्म" (audioStreamEnd) एक बात पर एक ही बार */
    private val endGate = StreamEndGate()
    /** आख़िरी बार यूज़र बोला / DK बोला - पीछे की आवाज़ कब वापस (DuckWatch) */
    @Volatile private var quietSince = 0L
    @Volatile private var duckFreedAt = 0L
    /** Bluetooth जुड़ा = कॉल वाला तरीका (BtAudio); हटा = speaker */
    @Volatile private var btOn = false
    @Volatile private var routeChanged = false
    /** DK की अपनी आवाज़ कितनी तेज़ बज रही है (आख़िरी ~400ms) - गूँज पहचानने के लिए */
    private val outLevels = ArrayDeque<Pair<Long, Double>>()
    private fun outLevel(now: Long): Double = synchronized(outLevels) {
        while (outLevels.isNotEmpty() && now - outLevels.first().first > 400) outLevels.removeFirst()
        outLevels.maxOfOrNull { it.second } ?: 0.0
    }
    /** Live socket पर भेजो + दर्ज करो (टूटने पर लॉग) */
    private fun WebSocket.tx(m: String): Boolean { wire.sent(m); return send(m) }
    /** Server ka aakhri sandesh (koi bhi) - atka stream pakadne ke liye */
    @Volatile private var lastServerAt = 0L
    @Volatile private var genStalls = 0
    @Volatile private var handlerErrors = 0
    /** Connection tootne se pehle ki baat jiska jawab shuru nahi hua tha - judte hi ek baar text me */
    @Volatile private var resendText: String? = null
    @Volatile private var resentN = 0
    /** v3.9.1: bola par Gemini ne suna hi nahi -> caption ka text seedha bhejo */
    // v3.41: suna par jawab shuru nahi = 4.5s ki jagah 3.5s me bachao (atka sawaal jaldi dobara)
    // v3.46: 2.5s -> 1.8s (सर्वर ने सुना हो तो ~1s में text भेज देता है); 0.4s -> 0.25s (छोटे आदेश "हाँ", "रुको" भी बचें - caption के शब्द ज़रूरी)
    private val replyWatch = ReplyWatchdog(timeoutMs = 1800, minSpeechMs = 250, answerTimeoutMs = 3500)
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
    // v3.53: फ़ोन से निकली आवाज़ (वीडियो/गाना) - PhoneAudioGuard. ये सब mic thread के; guardOk caption thread लिखता है
    @Volatile private var guardHeld = false          // अभी की आवाज़ रोकी हुई - caption में DK का नाम आने तक Gemini को नहीं
    @Volatile private var guardOk = false            // रोकी हुई बात में नाम मिल गया = यूज़र
    private var guardPass = false                    // नाम वाली बात अभी चल रही - इसे दोबारा मत रोको
    private var guardEndAt = 0L                      // रोकी हुई बात खत्म कब हुई (देर से आते शब्दों का इंतज़ार)
    private val guardBuf = ArrayDeque<ByteArray>()
    @Volatile private var guardOn = false            // अभी गार्ड चालू (फ़ोन पर दूसरी आवाज़ बज रही)
    private var mediaAt = 0L; private var mediaOn = false
    @Volatile var phoneAudioIgnored = 0              // कितनी बार फ़ोन की अपनी आवाज़ छोड़ी (debug)
        private set
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
    /** v3.51: आखिरी असली काम (tool नतीजा / काम पूरा होने का SYSTEM NOTE) कब - इसके बिना "कर दिया" = झूठ */
    @Volatile private var lastEvidenceAt = 0L
    @Volatile private var lastGuardAt = 0L
    /** v3.51: आखिरी बात (user / DK) - "मैं चेक करता हूँ" पर Live खुला रहे */
    @Volatile private var lastUserLine = ""
    @Volatile private var lastDkLine = ""
    @Volatile private var modelCache: Triple<Int, Long, List<String>>? = null

    fun stop(why: String = "user stop") {
        stopped = true
        closeWs(ws, why)
        parked?.let { parked = null; closeWs(it.ws, why) }
    }

    // ================================================================ v3.40: फ़ोन कॉल + स्क्रीन/कैमरा
    /** Live जुड़ा है और अभी बोल-सुन सकता है */
    fun canTalk() = !stopped && ready && ws != null
    @Volatile private var micMuted = false
    /** फ़ोन की अपनी आवाज़ (speakQuick) से कुछ बोलते समय वह आवाज़ Gemini को न जाए */
    fun muteMic(on: Boolean) { micMuted = on }
    /** call का आख़िरी काम (दो बार न हो: फ़ोन ने सुनकर उठाया, फिर Gemini ने भी phone_call माँगा) */
    @Volatile private var callDone: Pair<String, Long>? = null
    private val callLock = Any()
    /** v3.41: caption ka aakhri PAKKA text - scheduleFast doosri baar intezaar na kare */
    @Volatile private var capFinal = ""

    /** घंटी: Gemini खुद बताए किसका कॉल है और पूछे. true = DK बोलने लगा */
    suspend fun announceCall(who: String): Boolean {
        if (!canTalk()) return false
        // tool का जवाब बाकी हो तब text = 1007 का ख़तरा -> थोड़ा रुको
        // v3.41: 1.5s -> 0.6s; tool chal raha ho to phone ki apni awaaz turant poochhti hai (call ka samay keemti)
        withTimeoutOrNull(600) { while (toolJobs.isNotEmpty()) delay(100) }
        if (!canTalk() || toolJobs.isNotEmpty()) return false
        callDone = null
        if (isPlaying()) { flushPlayback(); commitPending(false) }
        val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", CallReply.liveNote(who))).toString()) == true } catch (_: Exception) { false }
        log("कॉल आया (${who.ifBlank { "नंबर पता नहीं" }}) - DK लाइव से पूछ रहा हूँ" + if (ok) "" else " (भेज नहीं पाया)")
        if (!ok) return false
        lastAct = System.currentTimeMillis()
        // v3.41: 5s -> 3s; na bole to phone ki awaaz (ghanti ke 25-30s me har second keemti)
        withTimeoutOrNull(3_000) { while (!isPlaying()) delay(100) }
        return isPlaying()
    }

    /** v3.51: कॉल के समय Live की आवाज़ accessibility पर (media घंटी/कॉल में mute) */
    @Volatile private var callAudioOn = false
    fun callAudio(on: Boolean) {
        if (callAudioOn == on) return
        callAudioOn = on
        if (stopped || btOn || track == null) return
        try { startPlayer(); log("आवाज़: " + if (on) "कॉल के समय accessibility (घंटी में भी सुनाई दे)" else "वापस speaker (media)") } catch (e: Exception) { log("आवाज़ बदल नहीं पाया: ${e.message}") }
    }

    /** v3.51: फ़ोन ने खुद पूछ लिया "उठाऊँ या काट दूँ?" - Live को बताओ ताकि जवाब सुने (दोबारा न पूछे) */
    suspend fun noteCallAsked(who: String) {
        if (!canTalk()) return
        // tool का जवाब बाकी हो तब text = 1007 का ख़तरा -> थोड़ा रुको (announceCall जैसा)
        withTimeoutOrNull(600) { while (toolJobs.isNotEmpty()) delay(100) }
        callDone = null
        if (isPlaying()) { flushPlayback(); commitPending(false) }
        val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", CallReply.liveNoteAsked(who))).toString()) == true } catch (_: Exception) { false }
        lastAct = System.currentTimeMillis(); lastEvidenceAt = lastAct
        log("कॉल आया (${who.ifBlank { "नंबर पता नहीं" }}) - फ़ोन ने आवाज़ में पूछा, Live जवाब सुनेगा" + if (ok) "" else " (Live को बता नहीं पाया)")
    }

    private suspend fun callTool(action: String, from: String): String {
        val a = action.trim().lowercase()
        if (a !in CallReply.ACTIONS) return "Unknown action '$action' - use one of: ${CallReply.ACTIONS.joinToString()}"
        val now = System.currentTimeMillis()
        val pick = listOf(CallReply.ANSWER, CallReply.ANSWER_SPEAKER, CallReply.REJECT)
        // v3.41: phone (caption) aur Gemini ek saath -> pehla jeete, doosra "ho gaya" (dono saath uthayein/kaatein nahi)
        synchronized(callLock) {
            callDone?.let { (d, at) ->
                if (now - at < 15_000 && d in pick && a in pick) return "Already done ($d) a moment ago - do not repeat it."
            }
            if (a in pick) callDone = a to now
        }
        val r = svc.liveCallAction(a)
        if (a in pick && (r.startsWith("ERROR") || r == "NO_CALL")) synchronized(callLock) { if (callDone?.second == now) callDone = null }
        if (a != CallReply.WHO && a !in pick && !r.startsWith("ERROR") && r != "NO_CALL") callDone = a to now
        log("कॉल ($from): $a -> $r")
        return if (a == CallReply.WHO) (if (r.startsWith("CALLER:")) r else "Caller name is not known.") else CallReply.resultNote(a, r)
    }

    @Volatile private var lastFrameAt = 0L
    @Volatile private var frames = 0
    private val vision1007 = ArrayDeque<Long>()
    /** यह Live मॉडल तस्वीर नहीं लेता (तस्वीर भेजते ही बार-बार 1007) */
    @Volatile var visionBroken = false
        private set

    /** स्क्रीन / कैमरा की JPEG तस्वीर (~1 हर सेकंड). network भरा हो तो तस्वीर छोड़ो - आवाज़ पहले */
    fun sendFrame(jpeg: ByteArray): Boolean {
        if (visionBroken || stopped || !ready) return false
        val w = ws ?: return false
        if (w.queueSize() > 384 * 1024) return false
        val msg = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"" +
            Base64.encodeToString(jpeg, Base64.NO_WRAP) + "\"}}}"
        val ok = w.tx(msg)
        if (ok) { lastFrameAt = System.currentTimeMillis(); frames++ }
        return ok
    }

    /** स्क्रीन/कैमरा चालू-बंद हुआ -> Gemini को बताओ (tool का जवाब बाकी हो तो उसके बाद) */
    fun visionNote(t: String, evidence: Boolean = true) {
        svc.scope.launch {
            withTimeoutOrNull(8_000) { while (toolJobs.isNotEmpty() || generating) delay(150) }
            if (!canTalk()) return@launch
            if (evidence) lastEvidenceAt = System.currentTimeMillis()
            try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) } catch (_: Exception) {}
            log("Gemini को बताया: ${t.take(70)}")
        }
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
            (if (noReplyTurns > 0) " • बात पर server से कुछ नहीं: $noReplyTurns" else "") + (if (noiseTurns > 0) " • शोर/खटखट (सही से छोड़े): $noiseTurns" else "") +
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
        stopped = false; goAway = false; idleExit = false; lastAct = System.currentTimeMillis(); callAudioOn = false; lastUserLine = ""; lastDkLine = ""
        sent = 0; sentBytes = 0; dropped = 0; gaps = 0; readErr = 0; recvAudio = 0; reconnects = 0
        if (!Setup.hasMic(svc)) throw Fatal("माइक की परमिशन नहीं है")
        val key = key(svc).ifBlank { throw Fatal("Gemini की key नहीं है") }
        val models = discoverModels(key)
        log("models: ${models.joinToString()}")
        if (stopped) return@withContext "जुड़ने से पहले बंद"
        var mi = 0; var fails = 0
        var quickDrops = 0                               // judne ke turant baad baar-baar tootna
        // v3.39: Bluetooth headset जुड़ा = कॉल वाला तरीका (माइक+आवाज़ headset पर), नहीं तो speaker (पहले जैसा)
        drop1007.reset(); endGate.reset(); quietSince = 0L; routeChanged = false
        visionBroken = false; synchronized(vision1007) { vision1007.clear() }; micMuted = false
        btOn = BtAudio.acquire(svc, this@GeminiLive) { bt ->
            btOn = bt; routeChanged = true
            // DK चुप हो तब आवाज़ का रास्ता बदलो (बोलते-बोलते आवाज़ न कटे)
            svc.scope.launch(Dispatchers.IO) {
                withTimeoutOrNull(6_000) { while (isPlaying()) delay(100) }
                if (!stopped) { startPlayer(); log("आवाज़: " + if (bt) "Bluetooth headset (कॉल वाला तरीका)" else "फ़ोन का speaker") }
            }
        }
        log("आवाज़ का रास्ता: " + if (btOn) "Bluetooth headset (कॉल वाला तरीका)" else "फ़ोन का माइक + speaker")
        startPlayer()
        // v3.41: "aap:" likhne wala apni alag key se (Settings -> AI dimaag -> DK live) - bolne wale ki free limit bachi rahe
        val capKey = Prefs.captionKey(svc)
        log("लिखने वाला: " + if (Prefs.captionOwnKey(svc).isNotBlank()) "अपनी अलग key से" else "DK लाइव वाली key से (अलग key नहीं डाली)")
        caption = LiveCaption(capKey, onText = { t, fin -> onCaption(t, fin) }, onLate = { t -> onLateCaption(t) },
            isNewSpeech = { barging || System.currentTimeMillis() < bargeUntil }).also { it.start() }
        turnDet.reset(); bargeDet.reset(); bargeGate.reset(); synchronized(bargeBuf) { bargeBuf.clear() }
        guardHeld = false; guardOk = false; guardPass = false; guardEndAt = 0L; guardBuf.clear(); guardOn = false
        trace.reset(); dropOld = false; suspectAt = 0L; replyWatch.reset()
        timeline.reset(); liveGate.reset(); capGate.reset(); resendText = null; lastServerAt = 0L
        coroutineScope {
            val mic = launch(Dispatchers.IO) { micLoop() }
            // DK ka text awaaz ke saath-saath (har 80ms)
            val reveal = launch(Dispatchers.IO) { while (isActive && !stopped) { revealTick(); delay(80) } }
            try {
                while (!stopped && isActive) {
                    if (svc.callTakesMic()) return@coroutineScope      // v3.40: घंटी पर नहीं, call उठने पर ही
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
                        if (firstText != null) openerText = null; sendFirstText(); sendResend(); sendOpener()
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
                        val idleMs = LiveHold.idleMs(idleExitMs, lastUserLine, lastDkLine)
                        if (idleMs > 0 && !turnActive && !isPlaying() && toolJobs.isEmpty() &&
                            System.currentTimeMillis() - lastAct > idleMs) {
                            log("${idleMs / 1000}s से कोई बात नहीं - Live बंद, wake word पर वापस")
                            idleExit = true; stopped = true; break
                        }
                        if (goAway && !turnActive && !isPlaying()) { log("goAway - नया connection"); goAwayDrop = true; break }
                        if (svc.callTakesMic()) { closeWs(ws, "call उठी - माइक call का"); return@coroutineScope }
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
                    log("टूटने से पहले भेजे: ${wire.summary()}")
                    if (code == 1007 && lastFrameAt > 0 && System.currentTimeMillis() - lastFrameAt < 6_000) {
                        val n = synchronized(vision1007) {
                            val t = System.currentTimeMillis(); vision1007.addLast(t)
                            while (vision1007.isNotEmpty() && t - vision1007.first() > 120_000) vision1007.removeFirst()
                            vision1007.size
                        }
                        log("1007 तस्वीर भेजने के तुरंत बाद (#$n)")
                        if (n >= 2) {
                            visionBroken = true
                            log("यह Live मॉडल ($model) तस्वीर नहीं ले रहा - स्क्रीन/कैमरा बंद")
                            LiveVision.onVisionBroken(svc)
                        }
                    }
                    if (code == 1007) {
                        // सर्वर को कोई संदेश गलत लगा: उसी (टूटी) बातचीत से दोबारा जुड़ने पर फिर टूटता - नई बातचीत
                        val a = drop1007.hit(System.currentTimeMillis(), useSearch)
                        if (handle != null) { handle = null; log("1007: टूटी बातचीत से दोबारा नहीं - नई बातचीत (#${a.count})") }
                        if (a.disableSearch) { useSearch = false; log("1007 दो मिनट में ${a.count} बार - इस Live में Google Search बंद (जाना-माना 1007 कारण)") }
                    }
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
                BtAudio.release(this@GeminiLive); btOn = false
                LiveVision.onLiveEnded(svc)          // Live बंद = स्क्रीन शेयर / कैमरा भी बंद (कोई तस्वीर कहीं नहीं जाती)
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
        try { w.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}     // atki awaaz flush
        val p = Parked(w, c, model, System.currentTimeMillis())
        parked = p
        log("connection बंद नहीं किया - ${PARK_MS / 60_000} मिनट standby (अगली बार तुरंत जुड़ेगा)")
        svc.scope.launch {
            delay(PARK_MS)
            if (parked === p) { parked = null; closeWs(p.ws, "standby khatam") }
        }
    }

    /** Wake word ke saath boli baat (text) - judte hi Gemini ko */
    /** v3.45: ऐप खुला - DK खुद बात शुरू करे (SYSTEM NOTE, "आप:" line नहीं). जुड़ते ही / अभी जुड़ा हो तो तुरंत */
    @Volatile var openerText: String? = null
        set(v) { field = v; openerAt = System.currentTimeMillis() }
    @Volatile private var openerAt = 0L

    /** अभी बोल सकता है तो तुरंत, नहीं तो जुड़ते ही. true = अभी भेज दिया */
    fun greetNow(note: String): Boolean {
        openerText = note
        if (!canTalk() || isPlaying() || turnDet.inSpeech || toolJobs.isNotEmpty()) return false
        sendOpener()
        return openerText == null
    }

    private fun sendOpener() {
        val t = openerText ?: return
        // 90s से पुराना (Live नहीं जुड़ा था) = बासी; अगली बार ऐप खुलने पर नया
        if (System.currentTimeMillis() - openerAt > 90_000) { openerText = null; return }
        if (toolJobs.isNotEmpty() || turnDet.inSpeech) return
        openerText = null
        try {
            val now = System.currentTimeMillis()
            eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
            beginTimeline(now, now, "DK ने खुद बात शुरू की")
            setMode(Mode.THINKING)
            ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
            lastAct = now
            log("DK ने खुद बात शुरू की (ऐप खुला)")
        } catch (_: Exception) {}
    }

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
            ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
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
        val ok = try { w.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) } catch (_: Exception) { false }
        if (ok) { resentN++; if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING) }
        log("टूटने से पहले की बात (${t.length} अक्षर) नए connection पर text में भेजी" + if (ok) "" else " (भेज नहीं पाया)")
    }

    /**
     * Nayi baat ki samay-rekha shuru. Pichhli baat par server se kuch bhi nahi aaya tha (na event, na awaaz) to
     * woh mamla log me - "bola par jawab nahi" agli baar bhi dikhe (pakad me aaye).
     */
    @Volatile private var tlWords = false
    @Volatile private var noiseTurns = 0
    private fun beginTimeline(speechEnd: Long, endpoint: Long, why: String) {
        if (timeline.active && timeline.eventAt == 0L && timeline.audioAt == 0L && toolJobs.isEmpty()) {
            // v3.46: खटखट/शोर (caption में कोई शब्द नहीं) को "जवाब नहीं आया" में मत गिनो - वहाँ चुप रहना ही सही है
            if (tlWords) { noReplyTurns++; log("पिछली बात पर Gemini से कुछ नहीं आया (#$noReplyTurns) • ${timeline.summary()}") }
            else noiseTurns++
        }
        tlWords = BargeGate.realWords(caption?.text().orEmpty())
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

    /** Network se Live models ki list (aur cache memory + phone dono me) */
    private fun fetchModels(key: String): List<String> {
        val found = mutableListOf<String>()
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
        if (found.isNotEmpty()) {
            val now = System.currentTimeMillis()
            modelCache = Triple(key.hashCode(), now, found.toList())
            Prefs.saveLiveModelCache(svc, LiveModelCache.encode(key.hashCode(), now, found))
        }
        return found
    }

    /** Is key par kaun se Live models hain (bidiGenerateContent) - sabse naya/tez pehle */
    private fun discoverModels(key: String): List<String> {
        val found = mutableListOf<String>()
        // wake word par har baar list mat mango (deri). Cache ab phone me bhi save - app restart ke baad bhi
        // connect se pehle 3-4s ki list wali deri nahi (log 07:51:50); 1 ghante se purani ho to nayi peeche se.
        val kh = key.hashCode()
        val cache = modelCache ?: LiveModelCache.decode(Prefs.liveModelCache(svc))?.also { modelCache = it }
        when (LiveModelCache.plan(cache, kh, System.currentTimeMillis())) {
            "use" -> found.addAll(cache!!.third)
            "refresh" -> {
                found.addAll(cache!!.third)
                svc.scope.launch(Dispatchers.IO) {
                    try { fetchModels(key) } catch (e: Exception) { log("models list (peeche se) nahi aayi: ${e.message}") }
                }
            }
            else -> try { found.addAll(fetchModels(key)) } catch (e: Fatal) { throw e } catch (e: Exception) { log("models list nahi aayi: ${e.message}") }
        }
        // v3.41: sabse NAYA nahi, sabse TEZ pehle - phone par naapa jawab-samay (LiveModelRank)
        val lat = Prefs.liveLatency(svc)
        val all = LiveModelRank.order(found, FALLBACK_MODELS, lat)
        // limit khatam wale model (aaj) chhodo - har baar 2 bekaar connection = ~1.5s deri + mic ke tukde gire
        val (skip, ok) = all.partition { Cooldown.blocked("live:$it") }
        if (skip.isNotEmpty()) log("limit खत्म, छोड़े: ${skip.joinToString()}")
        log("सबसे तेज़ क्रम: " + ok.take(3).joinToString { m -> m + (lat[m]?.let { " ${it}ms" } ?: "") })
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
            .put(JSONObject().put("name", "build_website")
                .put("description", "DK Website Forge: build a complete animated 3D website from the user's description (e.g. 'DK, ek cafe ki website banao'). A live 3D build screen opens on the phone and the finished website is shown there. Use it ONLY when the user asks to build/create/make a website, web page or landing page - not for coding tasks on GitHub. It returns forge_started immediately; tell the user it has started and that they can watch it being built.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("prompt", JSONObject().put("type", "STRING").put("description", "What website to build, with every detail the user gave (business, name, sections, colours, language)")))
                    .put("required", JSONArray().put("prompt"))))
            .put(JSONObject().put("name", "task_status")
                .put("description", "Get the REAL current status of background work given to DK Computer or the coding team (state, real steps done, latest step, result). Call it whenever the user asks how far a task has got / is it done (काम कहाँ तक पहुँचा, हुआ या नहीं). Tell ONLY what it returns - never guess progress or percentages.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
            .put(JSONObject().put("name", "phone_call")
                .put("description", "Handle the phone call that is ringing now (or is in progress) - ONLY after the user told you what to do. action: answer = pick up; answer_speaker = pick up and put it on loudspeaker; reject = cut / decline / hang up; speaker_on / speaker_off during a call; who = who is calling. Never answer or reject a call on your own.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("action", JSONObject().put("type", "STRING")
                        .put("description", "One of: answer, answer_speaker, reject, speaker_on, speaker_off, who")))
                    .put("required", JSONArray().put("action"))))
            .put(JSONObject().put("name", "screen_share")
                .put("description", "Turn sharing of the user's phone screen with you ON or OFF (it is OFF by default). Only while it is ON you receive an image of the screen about once a second. If the user asks you to look at / read / check their screen and sharing is OFF, first ask 'स्क्रीन शेयर चालू करूँ?'; when they agree, or when they directly ask you to share or see the screen, call with on=true. Android then shows its own permission popup (DK tries to tap Start itself). Never describe the screen before you get the SYSTEM NOTE that sharing really started. Call with on=false when the user says to stop.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN")))
                    .put("required", JSONArray().put("on"))))
            .put(JSONObject().put("name", "camera")
                .put("description", "Turn the phone camera ON or OFF so you can see what the user shows you (OFF by default). Only while it is ON you receive a camera image about once a second. Use when the user says कैमरा चालू करो / कैमरा से देखो / ये देखो क्या है (and the camera is off), or to turn it off. facing: back (default) or front (selfie).")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN"))
                        .put("facing", JSONObject().put("type", "STRING").put("description", "back or front")))
                    .put("required", JSONArray().put("on"))))
            .put(JSONObject().put("name", "notifications")
                .put("description", "Read the phone's recent notifications (messages, WhatsApp, calls missed, apps). Call it when the user asks क्या notification आया / किसका मैसेज आया / क्या लिखा है / कोई मैसेज आया क्या. Optional app = app or sender name to filter (e.g. WhatsApp, Rahul). Tell the user briefly who sent what - only what it returns.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("app", JSONObject().put("type", "STRING").put("description", "Optional: app or sender name to filter")))))
            .put(JSONObject().put("name", "notification_reply")
                .put("description", "Send a reply to a notification directly from the notification (WhatsApp, SMS, Telegram, etc.), when the user asks to reply (इसको रिप्लाई कर दो / Rahul को जवाब दो कि ...). to = sender or app name from the notification, or empty for the latest one. message = exactly what the user wants to send, in their words. After it returns, tell the user in one short line what was sent and to whom.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("to", JSONObject().put("type", "STRING").put("description", "Sender/app name, or empty for the newest notification"))
                        .put("message", JSONObject().put("type", "STRING").put("description", "The reply text to send")))
                    .put("required", JSONArray().put("message"))))
            .put(JSONObject().put("name", "forget_memory")
                .put("description", "When the user says to forget something (यह भूल जाओ / वो बात भूल जाओ / इसे मिटा दो), call this with what to forget - resolve 'यह/वो' from the conversation into the actual topic in a few words (e.g. 'इंटरव्यू', 'प्रिया का नंबर'). Deletes it from saved facts and conversation memory. Then confirm in one short line.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("about", JSONObject().put("type", "STRING").put("description", "The topic to forget, a few words")))
                    .put("required", JSONArray().put("about"))))
            .put(JSONObject().put("name", "local_info")
                .put("description", "Get the user's CURRENT location (fresh GPS/network, city/area name) and live weather right now. Call it when the user asks about weather, their area, nearby events/news/traffic/pandals/melas, or anything 'here/आसपास/मेरे शहर'. For local news or events, then use Google Search with the city name.")
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
                wire.reset()
                if (!webSocket.tx(setupJson(m))) closed.complete(0 to "setup नहीं भेज पाया")
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
            if (firstText != null) openerText = null; sendFirstText(); sendResend(); sendOpener()
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
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { lastServerAt = System.currentTimeMillis(); timeline.event(lastServerAt); replyWatch.onServerAnswered(); lastToolAt = lastServerAt; runTools(it) }
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
            Lang.noteUser(u)                   // DK AI: "Auto" भाषा - यूज़र की भाषा याद (तय लाइनें उसी में)
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
                    if (from > 0) {
                        lastLatency = firstAudioAt - from; log("देरी: बोलना खत्म → पहला ऑडियो चंक = $lastLatency ms")
                        // v3.41: is model ki asli raftaar yaad - agli baar sabse tez model pehle (tool wali baari nahi)
                        if (lastToolAt < from && toolJobs.isEmpty() && model.isNotBlank()) Prefs.recordLiveLatency(svc, model, lastLatency)
                    }
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
        turnActive = true; routeDedupe.reset()
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
        if (u.isNotBlank()) lastUserLine = u
        if (r.isNotBlank()) { lastDkLine = r; checkHonest(r) }
        // jawab: awaaz abhi baj rahi hai to revealTick() saath-saath poora karega
        synchronized(turnLock) {
            if (r.isNotBlank()) { pendingReply = r; pendingSources = Grounding.merge(turnSources, toolSources); toolSources = emptyList() }
            turnSources = emptyList()
        }
        firstAudioAt = 0; replyStarted = false
        if (!isPlaying()) setMode(Mode.LISTENING)
    }

    /**
     * v3.51: DK ने "कर दिया / भेज दिया" बोला पर कोई tool चला ही नहीं (90s में) = झूठ.
     * तुरंत Gemini को सुधार का SYSTEM NOTE - वह सच बोले और ज़रूरत हो तो सच में tool चलाए.
     */
    private fun checkHonest(reply: String) {
        val now = System.currentTimeMillis()
        if (toolJobs.isNotEmpty() || now - lastGuardAt < 30_000) return
        if (!HonestGuard.unbacked(reply, lastEvidenceAt, now)) return
        lastGuardAt = now
        log("सच-जाँच: DK ने बिना किसी काम के \"हो गया\" बोला - सुधार भेजा: ${reply.take(60)}")
        visionNote(HonestGuard.correctionNote(reply), evidence = false)
    }

    /** Caption is key par chal raha hai -> "आप:" sirf usi se (Live model ka der se aaya text screen par nahi, warna text aage-peeche hota) */
    private fun captionOn() = caption?.let { it.ready && it.working } == true

    /** Caption se turant shabd - "आप:" line */
    private fun onCaption(t: String, final: Boolean) {
        // v3.53: फ़ोन पर वीडियो/गाना बज रहा है और DK चुप - बिना DK के नाम वाले शब्द फ़ोन की अपनी आवाज़ (न दिखाओ, न धीमा करो)
        if (guardOn && !isPlaying() && !guardOk) {
            if (!(guardHeld && PhoneAudioGuard.hasName(t, Prefs.wakeWords(svc)))) return
            guardOk = true
            log("वीडियो/गाने के बीच DK का नाम सुना (\"${t.take(40)}\") - यूज़र की बात, Gemini को भेज रहा हूँ")
        }
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
        if (final) capFinal = t.trim()
        // v3.41: ghanti baj rahi hai aur pakka text aaya ("उठा लो" / "काट दो") -> bolna-khatam ka intezaar bhi nahi, turant
        if (final && svc.callRinging() && System.currentTimeMillis() - playEnd > 600 && !micMuted) {
            CallReply.parse(t)?.takeIf { it != CallReply.WHO }?.let { act -> svc.scope.launch { callTool(act, "फ़ोन ने सुना (तुरंत): \"${t.take(30)}\"") } }
        }
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
    /** v3.45: ताज़ा लोकेशन + मौसम (हर बार नई; कहीं सेव नहीं) */
    private suspend fun localInfoTool(): String {
        if (!LocalInfo.hasPermission(svc)) return "Location permission is OFF. Ask the user to allow location for DK (open the DK app; it asks), then try again."
        val r = LocalInfo.now(svc) ?: return "Could not get the current location right now (GPS/location may be off). Tell the user honestly."
        return "Current place: ${r.first.name.ifBlank { "unknown area (%.3f, %.3f)".format(java.util.Locale.US, r.first.lat, r.first.lon) }}. Weather now: ${r.second.substringAfter(" — ", r.second)}"
    }

    /** v3.41: फ़ोन के notification (DKNotifListener -> NotifStore) */
    private fun notifTool(app: String): String {
        if (!DKNotifListener.enabled(svc)) {
            DKNotifListener.openSettings(svc)
            return "Notification access is OFF. I opened the setting - tell the user to turn ON 'DK' in Notification access, then ask again."
        }
        // v3.51: पहले सिर्फ़ memory देखी जाती थी - Android ने पढ़ने वाला (listener) अलग कर दिया हो / ऐप दोबारा शुरू हुआ हो
        // तो memory खाली = "कोई notification नहीं" (झूठ). अब फ़ोन पर अभी पड़े notification ताज़ा पढ़ो; न पढ़ सको तो सच बताओ.
        val alive = DKNotifListener.refresh(svc)
        val q = app.trim()
        val list = NotifStore.recent(app = q)
        val r = NotifPush.toolAnswer(alive, NotifStore.describe(list, System.currentTimeMillis()), NotifStore.all().size, q,
            NotifStore.describe(list, System.currentTimeMillis()))
        log("notifications (${if (alive) "ताज़ा पढ़ा" else "पढ़ने वाला जुड़ा नहीं"}): ${list.size}")
        return r
    }

    /** v3.51: नया notification आया - Live को तुरंत बताओ (कुछ सेकंड के सब एक साथ) */
    private val notifQ = ArrayList<Triple<String, String, String>>()
    @Volatile private var notifFlushing = false
    fun pushNotif(app: String, title: String, text: String) {
        if (!canTalk() || svc.callBusy()) return
        synchronized(notifQ) { notifQ.add(Triple(app, title, text)); if (notifFlushing) return; notifFlushing = true }
        svc.scope.launch {
            try {
                delay(NotifPush.BATCH_MS)
                // DK बोल रहा / user बोल रहा / tool चल रहा = बीच में नहीं (60s तक रुको)
                withTimeoutOrNull(60_000) { while (isPlaying() || generating || turnActive || turnDet.inSpeech || toolJobs.isNotEmpty()) delay(200) }
                val items = synchronized(notifQ) { notifQ.toList().also { notifQ.clear() } }
                if (items.isEmpty() || !canTalk() || svc.callBusy()) return@launch
                val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", NotifPush.note(items))).toString()) == true } catch (_: Exception) { false }
                lastAct = System.currentTimeMillis()
                log("🔔 नया notification Live को बताया (${items.size})" + if (ok) "" else " (भेज नहीं पाया)")
            } finally { synchronized(notifQ) { notifFlushing = false } }
        }
    }

    private fun notifReply(to: String, message: String): String {
        val msg = message.trim()
        if (msg.isBlank()) return "No message given - ask the user what to reply."
        if (!DKNotifListener.enabled(svc)) {
            DKNotifListener.openSettings(svc)
            return "Notification access is OFF. I opened the setting - tell the user to turn ON 'DK' in Notification access."
        }
        val n = NotifStore.findForReply(to)
            ?: return "No notification found that can be replied to" + (if (to.isNotBlank()) " for '$to'" else "") + ". Tell the user; offer to send it via phone_task instead."
        val err = DKNotifListener.reply(svc, n.key, msg)
        log("notification reply -> [${n.app}] ${n.title.take(20)}: " + (err ?: "भेज दिया"))
        return if (err == null) "Sent to ${n.title.ifBlank { n.app }} on ${n.app}: \"$msg\""
        else "Not sent: $err. Offer to send it via phone_task (open ${n.app})."
    }

    /** DK AI: वही tool call id दोबारा आए तो काम दोबारा नहीं (दो बार call/मैसेज न जाए) */
    private val toolDedup = ToolCallDeduplicator()

    private fun runTools(calls: JSONArray) {
        for (i in 0 until calls.length()) {
            val fc = calls.optJSONObject(i) ?: continue
            val id = fc.optString("id"); val name = fc.optString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
            if (!toolDedup.firstTime(id)) { log("tool: $name - यही call (id) पहले आ चुका, दोबारा नहीं"); continue }
            log("tool: $name ${args.toString().take(80)}")
            val tStart = System.currentTimeMillis()
            // v3.39: जिस जुड़ाव पर सवाल आया, जवाब सिर्फ़ उसी पर. बीच में दोबारा जुड़ा = पुराना id नए सत्र को अनजान -> 1007
            val sock = ws
            // LAZY: pehle map me darj, phir shuru - warna tez tool (remember_fact) map me aane se pehle hi remove() kar deta tha
            val j = svc.scope.launch(start = CoroutineStart.LAZY) {
              try {
                val result = try { when (name) {
                    "phone_task" -> phoneTask(args.optString("request"))
                    "task_status" -> TaskStatus.summary()
                    "phone_call" -> callTool(args.optString("action"), "Gemini")
                    "screen_share" -> LiveVision.setScreen(svc, args.optBoolean("on", true))
                    "camera" -> LiveVision.setCamera(svc, args.optBoolean("on", true), args.optString("facing"))
                    "remember_fact" -> if (Memory.addFact(svc, args.optString("fact"))) "saved" else "NOT saved: private info (passwords, PIN, OTP, bank/card/Aadhaar numbers) is never stored. If the user asked you to remember it, tell them gently you do not save such private details." 
                    "forget_memory" -> { val n = Memory.forget(svc, args.optString("about")); if (n > 0) "Forgotten ($n place(s) deleted)." else "Nothing saved about that - tell the user it is not in memory." }
                    "local_info" -> localInfoTool()
                    "notifications" -> notifTool(args.optString("app"))
                    "notification_reply" -> notifReply(args.optString("to"), args.optString("message"))
                    "build_website" -> com.dk.assistant.forge.ForgeLauncher.fromVoice(svc, args.optString("prompt"))
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
                if (name != "remember_fact") lastEvidenceAt = System.currentTimeMillis()
                // ab se deri ginti: tool ka jawab bheja -> pehli awaaz (tool ka samay alag dikhta hai)
                eosAt = System.currentTimeMillis(); firstAudioAt = 0
                val cur = ws
                if (cur != null && cur === sock) { if (!cur.tx(resp.toString())) log("tool जवाब नहीं भेज पाया") }
                else {
                    log("tool $name का जवाब पुराने जुड़ाव का - नए जुड़ाव पर tool जवाब नहीं भेजा (1007 से बचाव)")
                    if (cur != null && ready && name != "task_status" && name != "remember_fact") lastEvidenceAt = System.currentTimeMillis()
                    if (cur != null && ready && name != "task_status" && name != "remember_fact") try {
                        cur.tx(JSONObject().put("realtimeInput", JSONObject().put("text",
                            "SYSTEM NOTE: the connection was renewed while the user's earlier request ($name) was running. Its result: \"" +
                                result.take(600) + "\". Tell the user briefly only if it is still relevant. Do not invent details.")).toString())
                    } catch (_: Exception) {}
                }
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
        val failBefore = DKState.failedAt
        val job = agent.run(req, speak = false, note = note)
        // v3.41: 20s -> 7s. Gemini tool ke jawab tak CHUP rehta hai - 20s chuppi = "sun hi nahi raha". Ab 7s me
        // "kar raha hoon" bolta hai; kaam peeche chalta rehta hai aur poora hote hi lateNote se batata hai.
        val finished = withTimeoutOrNull(AGENT_WAIT_MS) { job.join() } != null
        val reply = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
        if (!finished) {
            log("phone_task ${AGENT_WAIT_MS / 1000}s में पूरा नहीं - Gemini को सच बताया (चल रहा है), पूरा होने पर बताऊँगा")
            svc.scope.launch {
                val done = withTimeoutOrNull(120_000) { job.join() } != null
                if (!done) job.cancel()
                val rep = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
                    ?: if (!done) "It could not be finished in time and was stopped." else null
                // DK बोल रहा हो तो बीच में नहीं - चुप होने तक (10s तक) रुको
                withTimeoutOrNull(15_000) { while (isPlaying() || generating || toolJobs.isNotEmpty()) delay(150) }
                if (stopped || !ready) return@launch
                val bad = !done || DKState.failedAt != failBefore
                lastEvidenceAt = System.currentTimeMillis()
                try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", LiveFast.lateNote(req, rep, bad))).toString()) } catch (_: Exception) {}
                log("phone_task बाद में पूरा (${if (done) "हुआ" else "रोका"}) - Gemini को बताया")
            }
        }
        return LiveFast.agentResult(finished, reply, failed = finished && DKState.failedAt != failBefore)
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
    private fun sendAudio(pcm: ByteArray, silent: Boolean = false): Unit = synchronized(sendLock) {
        if ((micMuted || DKState.micMuted) && !silent) { sendAudio(ByteArray(pcm.size), true); return@synchronized }
        var end = false
        if (silent) {
            when (liveGate.onSilence()) {
                SilenceStreamGate.Action.SKIP -> { silenceSkipped++; return@synchronized }
                SilenceStreamGate.Action.SEND_THEN_END -> end = true
                SilenceStreamGate.Action.SEND -> {}
            }
        } else { liveGate.onReal(); endGate.onReal() }
        val w = ws
        if (w == null || !ready) {
            synchronized(pending) { pending.addLast(pcm); while (pending.size > 3000 / CHUNK_MS) { pending.removeFirst(); dropped++ } }
            return@synchronized
        }
        // network dheema: line me atka data. Pehle khaamoshi chhodo (32KB), asli awaaz sirf bahut atakne par (256KB) -
        // OkHttp ki line se purana nahi hata sakte, isliye pehle asli baat ke naye tukde hi gir jaate the.
        if (w.queueSize() > (if (silent) 32 * 1024 else 256 * 1024)) { dropped++; return@synchronized }
        val msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=$IN_RATE\",\"data\":\"" +
            Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}"
        if (w.tx(msg)) { sent++; sentBytes += pcm.size } else dropped++
        // 1s khaamoshi bheji -> stream band (docs: audioStreamEnd), ab asli awaaz aane tak upload nahi
        if (end && endGate.onSilenceEnd()) try { w.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
    }

    /** Caption socket: asli awaaz hamesha; khaamoshi 1s tak, phir audioStreamEnd aur band */
    private fun capSend(pcm: ByteArray, silent: Boolean = false) {
        val c = caption ?: return
        if (!silent) { capGate.onReal(); c.send(pcm); return }
        when (capGate.onSilence()) {
            SilenceStreamGate.Action.SEND -> c.send(pcm, real = false)
            SilenceStreamGate.Action.SEND_THEN_END -> { c.send(pcm, real = false); c.endOfSpeech() }
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
                // Bluetooth जुड़ा = headset का माइक (कॉल वाला तरीका)
                if (btOn) BtAudio.btInput?.let { d -> try { r.setPreferredDevice(d) } catch (_: Exception) {} }
                val sid = r.audioSessionId
                val aec = try { AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                val ns = try { NoiseSuppressor.isAvailable() && NoiseSuppressor.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                val agc = try { AutomaticGainControl.isAvailable() && AutomaticGainControl.create(sid)?.apply { enabled = true } != null } catch (_: Exception) { false }
                aecOn = aec
                log("माइक: ${if (btOn) "Bluetooth headset" else "फ़ोन"} • ${r.sampleRate} Hz, mono, PCM16, source=$src, AEC=$aec NS=$ns AGC=$agc, buffer=${min}B" +
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
        // v3.39: एक बात पर एक ही "बात खत्म"; बहुत छोटी आवाज़ (खटखट) पर बिना caption शब्दों के नहीं - सर्वर का VAD संभाले
        val capNow = caption?.text().orEmpty()
        val words = BargeGate.realWords(capNow)
        // v3.46: वाक्य "और / तो / कि / ये बताओ…" पर रुका = आप आगे बोलेंगे. तब "बात खत्म" मत भेजो (पहले हर ठहराव पर
        // Gemini अधूरी बात का जवाब बनाने लगता, फिर आप आगे बोलते तो रद्द - कई वाक्यों पर जवाब बहुत देर से आता था)
        val holdEnd = TurnEnd.continues(capNow)
        val endSent = !holdEnd && ready && w != null && endGate.onSpeechEnd(turnDet.lastSpeechMs, words) &&
            try { w.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) { false }
        caption?.endOfSpeech()
        // DK abhi jawab bana/bol raha hai = server sun chuka; warna 2.5s me kuch na aaye to "nahi suna".
        // dropOld = user ne purana jawab toka tha: woh "generating" PURANE jawab ka hai, is nayi baat ka nahi -
        // pehle yahan watchdog band ho jaata tha aur toka-ke-boli baat ko server na sune to DK chup reh jaata.
        if ((generating && !dropOld) || isPlaying()) replyWatch.onServerAnswered() else replyWatch.onEndOfSpeech(now, turnDet.lastSpeechMs)
        scheduleFast()
        log("फ़ोन: बोलना खत्म (${now - trace.speechEndAt} ms चुप) → " + (if (endSent) "audioStreamEnd भेजा" else if (holdEnd) "audioStreamEnd रोका (वाक्य अधूरा लगा - सर्वर ${SERVER_SILENCE_MS}ms चुप्पी पर तय करेगा)" else "audioStreamEnd नहीं (पहले भेजा जा चुका / बहुत छोटी आवाज़)") +
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
        if (!ready) return
        val now0 = System.currentTimeMillis()
        // v3.46: पहले यहाँ सीधा return = बचाव का इकलौता मौका खत्म और बात हमेशा के लिए खो जाती थी
        // (उसी पल फ़ोन का काम चल रहा हो / आप फिर बोलने लगे / DK बोल रहा हो). अब थोड़ी देर बाद फिर देखो.
        if ((generating && !dropOld) || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech) {
            if (!turnDet.inSpeech) replyWatch.defer(now0)
            return
        }
        val t = caption?.text().orEmpty().trim()
        // caption के शब्द थोड़ी देर से आते हैं (उसका जुड़ाव हर 50s बदलता है) - एक-दो बार और रुको, फिर "शोर" मानो
        if (!BargeGate.realWords(t) && rescueWaits < 2 && replyWatch.defer(now0, 600)) { rescueWaits++; return }
        rescueWaits = 0
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
        val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) == true } catch (_: Exception) { false }
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
        if (!ready) return
        if ((generating && !dropOld) || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech) {
            if (!turnDet.inSpeech && !isPlaying() && !(generating && !dropOld)) replyWatch.deferAnswer(System.currentTimeMillis())
            return
        }
        val t = caption?.text().orEmpty().trim().ifBlank { synchronized(turnLock) { userText.toString().trim() } }
        if (!BargeGate.realWords(t)) return
        val now = System.currentTimeMillis()
        eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
        log("अटकी बात की समय-रेखा: ${timeline.summary()}")
        timeline.begin(now, now, "अटका - text में दोबारा"); waitingLogged = false
        val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) == true } catch (_: Exception) { false }
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
            // v3.41: 300+350 ms -> ghanti me 150 ms; baaki 200 ms (+250 sirf jab caption ne text pakka nahi kiya)
            val ringing = svc.callRinging()
            delay(if (ringing) 150 else 200); val a = cur()
            val b = if (ringing || (a.isNotBlank() && a == capFinal)) a else { delay(250); cur() }
            if (b.isBlank() || a != b || turnDet.inSpeech || isPlaying() || stopped) return@launch
            // v3.40: घंटी बज रही है -> "हाँ / उठाओ / स्पीकर पर / काट दो" फ़ोन तुरंत करे (Gemini के tool का इंतज़ार नहीं)
            if (svc.callRinging()) {
                val act = CallReply.parse(b)
                if (act != null) { callTool(act, "फ़ोन ने सुना: \"${b.take(30)}\""); return@launch }
            }
            // v3.46: वही बात दोबारा router में नहीं (हर ठहराव/शोर पर वही caption दोबारा जाता था)
            if (!routeDedupe.fresh(b, System.currentTimeMillis())) return@launch
            val tc = System.nanoTime()
            val q = LiveFast.pick(b, Prefs.wakeWords(svc), svc.currentPackage(), fast = true)
            val classMs = (System.nanoTime() - tc) / 1_000_000
            // "स्क्रीन शेयर करो / बंद करो" Live में = असली स्क्रीन शेयर (Gemini देखे), पुराना screenshot switch नहीं
            if (q != null && q.action.optString("type") == "screen_view") {
                val r = LiveVision.setScreen(svc, q.action.optBoolean("on", true))
                log("तुरंत: स्क्रीन शेयर ${if (q.action.optBoolean("on", true)) "चालू" else "बंद"} -> ${r.take(60)}"); return@launch
            }
            if (q == null) { log("रूटर: \"${b.take(30)}\" फ़ोन पर सीधा नहीं - Gemini संभालेगा (वर्गीकरण $classMs ms)"); return@launch }
            if (!fastMemo.begin(q, System.currentTimeMillis())) return@launch
            val t0 = System.currentTimeMillis()
            val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
            fastMemo.finish(q, r); fastN++
            log("तुरंत काम (Gemini से पहले) \"${b.take(40)}\" -> ${q.action.optString("type")} = ${r.take(60)} (वर्गीकरण $classMs ms, काम+जाँच ${System.currentTimeMillis() - t0} ms)")
        }
    }

    @Volatile private var loudMs = 0L
    @Volatile private var rescueWaits = 0

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
                // इनपुट बार में बोलकर लिखना: माइक छोड़ो (Google पहचान को आवाज़ मिले), खत्म होते ही वापस
                if (DKMic.dictating) {
                    try { r.stop() } catch (_: Exception) {}
                    try { r.release() } catch (_: Exception) {}
                    log("माइक छोड़ा: आप इनपुट बार में बोलकर लिख रहे हैं")
                    while (isActive && !stopped && DKMic.dictating) delay(200)
                    r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला (बोलकर लिखने के बाद)", recoverable = true)
                    r.startRecording(); last = 0L; errRun = 0
                    log("माइक वापस: बोलकर लिखना खत्म")
                    continue
                }
                // v3.39: Bluetooth जुड़ा/हटा -> माइक नए रास्ते पर दोबारा खोलो
                if (routeChanged) {
                    routeChanged = false
                    try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {}
                    r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला (Bluetooth बदला)", recoverable = true)
                    r.startRecording()
                    log("माइक नए रास्ते पर: " + if (btOn) "Bluetooth headset" else "फ़ोन का माइक")
                }
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
                    quietSince = now
                    turnDet.reset()
                    if (guardHeld) { guardHeld = false; guardEndAt = 0L; guardBuf.clear() }   // v3.53: DK बोलने लगा - रोकी आवाज़ छोड़ो
                    guardPass = false
                    if (bargeDet.ambient == 0.0) bargeDet.ambient = turnDet.noise
                    when (bargeGate.state) {
                        BargeGate.State.IDLE -> {
                            if (bargeDet.feed(rms, now, outLevel(now))) {
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
                    if (turnDet.inSpeech || generating || (turnActive && now - eosAt < 8_000)) quietSince = now
                    // v3.53: फ़ोन पर दूसरे ऐप की आवाज़ (वीडियो/गाना) - हर 300ms देखो (DK की अपनी आवाज़ थमने के बाद ही भरोसा)
                    if (now - mediaAt > 300) { mediaAt = now; mediaOn = Ducker.musicActive(svc) }
                    guardOn = !svc.callRinging() && !callAudioOn && PhoneAudioGuard.active(mediaOn, now - playEnd)
                    if (guardPass && (ev == TurnDetector.Event.SPEECH_END || !turnDet.inSpeech)) guardPass = false
                    if (guardHeld || (guardOn && !guardPass && (ev == TurnDetector.Event.SPEECH_START || turnDet.inSpeech))) {
                        if (!guardHeld) {
                            // नई आवाज़ आई, पर फ़ोन पर वीडियो चल रहा है: पहले सिर्फ़ लिखने वाले को, Gemini को नहीं
                            guardHeld = true; guardOk = false; guardEndAt = 0L; guardBuf.clear()
                            caption?.discard()
                            if (caption?.usable != true && phoneAudioIgnored == 0)
                                log("वीडियो/गाना चल रहा है और लिखने वाला (caption) बंद - DK का नाम पहचान नहीं सकता, फ़ोन की आवाज़ अनसुनी रहेगी (बात के लिए वीडियो रोकें)")
                            preroll.forEach { guardBuf.addLast(it); capSend(it) }; preroll.clear()
                        }
                        guardBuf.addLast(chunk); capSend(chunk)
                        while (guardBuf.size > PhoneAudioGuard.MAX_HOLD_CHUNKS) guardBuf.removeFirst()
                        if (ev == TurnDetector.Event.SPEECH_END) { guardEndAt = now; caption?.endOfSpeech() }
                        if (guardOk) {
                            // नाम मिला = यूज़र. रुकी आवाज़ Gemini को, फिर सामान्य रास्ता (बात चल रही या खत्म)
                            guardHeld = false; guardPass = turnDet.inSpeech && guardEndAt == 0L
                            synchronized(sendLock) { guardBuf.forEach { sendAudio(it) } }; guardBuf.clear()
                            lastAct = now; trace.reset(); replyWatch.onSpeechStart(); rescueWaits = 0
                            if (Ducker.musicActive(svc)) Ducker.duck(svc, "Live: वीडियो के बीच DK का नाम")
                            if (DKState.mode != Mode.WORKING) setMode(Mode.LISTENING)
                            if (guardEndAt > 0L || !turnDet.inSpeech) { guardEndAt = 0L; onLocalEndOfSpeech(now) }
                            continue
                        }
                        val tooLong = guardBuf.size >= PhoneAudioGuard.MAX_HOLD_CHUNKS && turnDet.inSpeech
                        if (PhoneAudioGuard.expired(guardEndAt, now) || tooLong || (!guardOn && !turnDet.inSpeech && guardEndAt == 0L)) {
                            // नाम नहीं आया = फ़ोन से निकली आवाज़ (वीडियो की बात). छोड़ो - न जवाब, न धीमा, न स्क्रीन पर
                            phoneAudioIgnored++
                            if (phoneAudioIgnored % 10 == 1) log("फ़ोन से निकली आवाज़ (वीडियो/गाना) - DK का नाम नहीं, अनसुनी (#$phoneAudioIgnored)")
                            guardHeld = false; guardEndAt = 0L; guardBuf.clear(); caption?.discard()
                            if (tooLong) caption?.endOfSpeech()
                        }
                        sendAudio(zeros, silent = true); gatedChunks++
                        continue
                    }
                    // v3.39: पीछे की आवाज़ - यूज़र चुप, DK चुप, जवाब नहीं बन रहा, 3s शांत = वापस तेज़ (हर रास्ते पर)
                    if (DuckWatch.shouldRelease(Ducker.active, turnDet.inSpeech, false, generating, quietSince, now) && now - duckFreedAt > 2_000) {
                        duckFreedAt = now; Ducker.releaseNow()
                        log("पीछे की आवाज़ वापस (3s से सब शांत)")
                    }
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
                            lastAct = now; trace.reset(); replyWatch.onSpeechStart(); rescueWaits = 0
                            // v3.46: गाना/वीडियो चल रहा हो तो आप बोलना शुरू करते ही धीमा (पहले शब्द पहचाने जाने के बाद ही -
                            // पर तेज़ गाने में शब्द पहचाने ही नहीं जाते थे)
                            if (Ducker.musicActive(svc)) Ducker.duck(svc, "Live: फ़ोन ने आवाज़ पकड़ी")
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
            try { if (ready) ws?.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {}
            DKState.level = 0f
        }
    }

    // ================================================================ Gemini ki awaaz bajana
    private fun startPlayer() {
        stopPlayer()
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            // Bluetooth = कॉल वाली आवाज़ (headset में, गूँज हटाना ठीक); नहीं तो पहले जैसा मीडिया (speaker)
            // v3.51: कॉल (घंटी/बात) के समय media mute होता है -> accessibility (CallVoice)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(CallVoice.usage(callAudioOn || svc.callRinging(), btOn))
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, OUT_RATE * 2 / 5))      // ~200ms buffer: jaldi shuru, atke bina
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        log((if (btOn) "आवाज़: Bluetooth headset" else "स्पीकर") + ": $OUT_RATE Hz PCM16 mono stream")
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
                // DK की अपनी आवाज़ कितनी तेज़ (गूँज पहचानने के लिए - BargeDetector)
                noteOutLevel(b, now)
                var off = 0
                while (off < b.size && g == gen) {
                    val n = try { tr.write(b, off, b.size - off) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    // v3.46: आवाज़ पहले हिस्से के लिखते ही शुरू हो जाती है - पहले पूरा टुकड़ा लिखने (buffer भरा हो तो
                    // ~800ms रुकना) के बाद गिनते थे = "playback 818ms" असल में नाप की गलती थी
                    if (off == 0) { val pt = System.currentTimeMillis(); timeline.play(pt); if (trace.firstChunkAt > 0 && trace.firstPlayAt == 0L) firstWriteAt = pt }
                    off += n
                    if (g == gen) turnPlayedBytes += n
                }
                if (trace.firstChunkAt > 0 && trace.firstPlayAt == 0L && off > 0) {
                    trace.firstPlayAt = firstWriteAt.takeIf { it > 0 } ?: System.currentTimeMillis(); firstWriteAt = 0L
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

    @Volatile private var firstWriteAt = 0L

    /** Beech me toka: jo awaaz kataar me hai sab hatao, turant chup */
    private fun flushPlayback() {
        gen++
        playQ.clear()
        playEnd = 0L
        track?.let { try { it.pause(); it.flush(); it.play() } catch (_: Exception) {} }
    }

    /** बजने वाले टुकड़े की तेज़ी (हर चौथा sample - सस्ता) */
    private fun noteOutLevel(b: ByteArray, now: Long) {
        var sq = 0.0; var k = 0; var i = 0
        while (i + 1 < b.size) { val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); sq += (v * v).toDouble(); k++; i += 8 }
        if (k > 0) synchronized(outLevels) { outLevels.addLast(now to sqrt(sq / k)); while (outLevels.size > 40) outLevels.removeFirst() }
    }

    private fun stopPlayer() {
        playThread?.interrupt(); playThread = null
        playQ.clear(); playEnd = 0L
        track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        track = null
    }
}
