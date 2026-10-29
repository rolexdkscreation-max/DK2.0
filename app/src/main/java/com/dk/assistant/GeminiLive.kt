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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
        /** v3.5+: HOLD - caption इतनी देर न बदले + आवाज़ शांत = यूज़र का अगला सवाल पूरा */
        const val HOLD_END_MS = 1100L
        const val HOLD_QUIET_MS = 700L
        /** caption ने "पक्का" (final) text दे दिया हो तो इतना ही */
        const val HOLD_FINAL_MS = 500L
        /** आवाज़ गूँज से तेज़ लगती रहे तब भी caption इतनी देर न बदले = पूरा */
        const val HOLD_STALE_MS = 2200L
        const val HOLD_MAX_MS = 15_000L
        /** कतार का सवाल इतनी देर में न भेजा जा सका = पुराना, छोड़ो */
        const val ASK_KEEP_MS = 90_000L
        /** एजेंट का tool इतनी देर में बुलाया जा चुका = उसी बात का, फ़ोन दोबारा नहीं चलाता */
        const val AGENT_TOOL_RECENT_MS = 60_000L
        const val HOLD_HINT = "सुन रही हूँ — यह जवाब पूरा होते ही आपके सवाल का जवाब दूँगी (रोकना हो तो \"रुको\" बोलें)"
        const val ASK_HINT = "आपका अगला सवाल याद है — यह जवाब पूरा होते ही बताऊँगी"
        /** v3.88: बोलते-बोलते तैयार किया डेटा कितनी देर ताज़ा माना जाए */
        const val PREP_WEATHER_MS = 5 * 60_000L
        const val PREP_SEARCH_MS = 50_000L
        /** Bolna shuru pakadne se pehle itni awaaz - chhota = pehla shabd turant (bahut chhota = DK ki goonj se galat "toka") */
        const val PREFIX_PADDING_MS = 100
        /** Wake word wala Live chup hone par connection itni der garam rakho */
        private const val PARK_MS = 5 * 60_000L
        /** DK bolte samay (gate mode) user ki awaaz pakdi -> usse pehle ka itna hissa bhi bhejo (pehla shabd na kate) */
        private const val PREROLL_CHUNKS = 8          // 8 x 40ms = 320ms
        /** Itni der "Thinking" aur kuch na aaye -> wapas "Listening" (purani haalat atki na rahe) */
        private const val THINK_STUCK_MS = 12_000L
        /** v3.80: tool का नतीजा भेजने के बाद इतनी देर चुप = याद दिलाओ */
        private const val TOOL_ANSWER_MS = 8_000L
        /** v4.1: जवाब में लिखी हुई (असली नहीं) tool call का नकली id - इसका नतीजा toolResponse में नहीं जाता */
        const val LEAK_ID = "dk-leak-"
        const val LEAK_DEDUP_MS = 20_000L
        /** v3.80: इनका नतीजा = यूज़र को जवाब बोलना ज़रूरी */
        private val ANSWER_TOOLS = setOf("web_search", "deep_think", "current_time", "local_info", "connector_task", "task_status", "dk_features")
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
        /** v3.68: बँटवारे में Live का लिखा text सीधे बोला जाता है - बोलने लायक लिखो */
        private const val SPLIT_NOTE = "\n\nOUTPUT NOTE: Your reply text is spoken aloud by a separate voice engine, sentence by sentence, " +
            "as soon as you write it. Write exactly what should be heard: natural spoken Hindi (Devanagari), short sentences, " +
            "commas where a speaker would pause. No markdown, lists, emojis, headings or stage directions."

        /** v3.71: पहली आवाज़ जल्दी - पहला वाक्य बहुत छोटा (वही सबसे पहले बनकर बजता है), बाकी भी छोटे */
        private const val FAST_START = "\n\nSPEED NOTE: Always begin your reply with one very short sentence of 4-6 words " +
            "(for example \"अच्छा, सुनो।\" or \"हाँ, यह आसान है।\"), then continue. Keep the rest in short sentences too."
        /** v3.71: अवतार का भाव - सिर्फ़ TEXT बँटवारे में (टैग आवाज़ में नहीं जाता; ऐप पढ़कर हटा देता है) */
        const val MOOD_TAG_NOTE = "\n\nMOOD TAG: हर जवाब की शुरुआत में एक भाव-टैग लिखो: [खुश], [उदास], [गुस्सा] या [भावुक]; " +
            "सामान्य बात हो तो कोई टैग नहीं। टैग सिर्फ़ एक बार, सबसे पहले, फिर जवाब।"

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
    init {
        TaskStatus.listen(statusListener)
        // v3.90: "Thinking" तभी जब कोई tool (खोज, गहरी सोच, फ़ोन का काम…) या फ़ोन की अपनी खोज चल रही हो
        DKState.workNow = { toolJobs.isNotEmpty() || promiseJob?.isActive == true || DKState.mode == Mode.WORKING }
    }
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
    // v4.1: Live ने tool call की जगह "phone_task{…}<ctrl43>" लिख/बोल दिया -> यह फ़िल्टर उसे छिपाता है और फ़ोन तुरंत असली काम करता है
    private var leakFilter = newLeakFilter()
    /** इस बारी में लिखी हुई call दिखी = Live की अपनी (गड़बड़) आवाज़ अब नहीं बजती */
    @Volatile private var leakMute = false
    @Volatile private var lastLeakKey = ""
    @Volatile private var lastLeakAt = 0L
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
    // v3.6: धीमी आवाज़ भी "बोलना" (owner: "ज़ोर से बोलना पड़ता है") - दरवाज़ा 2.5x -> 2.0x शोर, 300 -> 220 RMS
    private val turnDet = TurnDetector(CHUNK_MS, endSilenceMs = CLIENT_END_MS, startFactor = LiveHear.START_FACTOR,
        startMin = LiveHear.START_MIN, loudNoiseFactor = LiveHear.LOUD_FACTOR,
        longSpeechMs = TurnEnd.LONG_SPEECH_MS, longEndSilenceMs = TurnEnd.LONG_END_SILENCE_MS, steadyResetMs = 6_000,
        keepFactor = LiveHear.KEEP_FACTOR, keepMin = LiveHear.KEEP_MIN)
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
    /**
     * v3.5+: DK बोल रहा है और यूज़र ने "रुको" के अलावा कुछ पूछा = जवाब नहीं काटा, सवाल यहाँ याद.
     * जवाब पूरा (आवाज़ बंद, कुछ बन नहीं रहा) होते ही वही सवाल Gemini को - उसका भी जवाब.
     */
    private val askQueue = AskQueue()
    @Volatile private var askAt = 0L
    /** HOLD (यूज़र का अगला सवाल सुन रहे) का हिसाब: caption का आख़िरी text, कब बदला, आख़िरी तेज़ आवाज़ */
    @Volatile private var holdCap = ""
    @Volatile private var holdCapAt = 0L
    @Volatile private var holdLoudAt = 0L
    @Volatile private var holdFinal = false
    /** आख़िरी बार DK की आवाज़ बज रही थी (कतार वाला सवाल थोड़ी चुप्पी के बाद ही) */
    @Volatile private var lastPlayingAt = 0L
    /** v3.5+: एजेंट (कहानी/कोडिंग/कंप्यूटर/वेबसाइट) का tool आख़िरी बार कब बुलाया - "हाँ कर दिया" पर tool न बुलाया तो फ़ोन खुद */
    @Volatile private var agentToolAt = 0L
    @Volatile private var agentFixAt = 0L
    @Volatile private var falseAlarms = 0
    @Volatile private var realBarges = 0
    @Volatile private var gatedChunks = 0L

    // ---- v3.66: सिर्फ़ मालिक की आवाज़ (परत 3) + दूर की आवाज़ अनसुनी (परत 4)
    /** इस बात का फ़ैसला (mic thread पर ही) */
    private val owner = com.dk.assistant.voice.OwnerGate(CHUNK_MS)
    @Volatile private var ownerOn = false
    @Volatile private var ownerTh = com.dk.assistant.voice.VoicePrint.TH_NORMAL
    private var ownerCfgAt = 0L
    /** हर नई बात पर +1 - पुरानी बात का देर से आया मिलान नई पर न लगे */
    @Volatile private var ownerGen = 0
    private class OwnerResult(val gen: Int, val score: Float?, val ms: Int, val tookMs: Long)
    @Volatile private var ownerResult: OwnerResult? = null
    /** DK सोच/काम कर रहा था तब नई आवाज़: पहचान होने तक Gemini को नहीं (किसी और की बात DK का जवाब न काटे) */
    private var ownerHolding = false
    private val ownerHold = ArrayDeque<ByteArray>()
    /** इस जवाब को न बजाओ, न कोई काम करो - वह किसी और की आवाज़ का है */
    @Volatile private var muteTurn = false
    @Volatile private var muteAt = 0L
    // v3.67 (DoneGate): काम वाली बात पर Gemini की आवाज़ असली काम दिखने तक रोकी जाती है - झूठा "हो गया" बजता ही नहीं
    @Volatile private var actHold = false
    @Volatile private var actEvidence = false
    private val actHeld = ArrayList<ByteArray>()
    @Volatile private var actHeldBytes = 0
    /** v3.7: रोकना कब शुरू हुआ (घड़ी) - आम बात 0.9 s से ज़्यादा नहीं रुकती */
    @Volatile private var actHoldAt = 0L
    @Volatile private var actTextFrom = 0
    @Volatile private var lastFastOkAt = 0L
    @Volatile private var actDropped = 0
    @Volatile private var actReleased = 0
    /** tool ने कहा "अभी हुआ नहीं" (शुरू हुआ / गड़बड़) - उसके बाद की बात फिर जाँचो */
    @Volatile private var actPending = false
    @Volatile private var actAfterTool = false
    /** v3.90: यूज़र ने साफ़ हुक्म दिया ("खोलो/भेजो/कर दो") = काम से पहले की बातें नहीं (पहले काम, फिर बात) */
    @Volatile private var actCommand = false
    @Volatile private var othersIgnored = 0
    @Volatile private var mutedParts = 0L
    @Volatile private var earlyEnds = 0
    private var capSeen = ""; private var capSeenAt = 0L
    /** 2.5 model ka "sochna" band (pehle awaaz tez) - setup mana kare to wapas */
    @Volatile private var thinkOff = true
    /** v3.59: इस Live की आवाज़/भाषा/affective (setup पर एक बार तय - बीच में अपने-आप नहीं बदलती) */
    @Volatile private var extras: LiveVoice.Extras? = null
    @Volatile private var extrasModel = ""
    @Volatile private var extrasChosen = ""
    private fun extrasFor(m: String): LiveVoice.Extras {
        val chosen = Prefs.geminiVoice(svc)
        val cur = extras
        if (cur != null && extrasModel == m && extrasChosen == chosen) return cur
        return LiveVoice.start(m, chosen, Prefs.liveExtrasOff(svc)).also { extras = it; extrasModel = m; extrasChosen = chosen }
    }
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
    @Volatile private var dkOnlyNow = false          // v4.1: Settings → "सिर्फ़ DK बोलने पर सुनो" (हर 3s ताज़ा)
    private var dkOnlyAt = 0L
    @Volatile private var guardOn = false            // अभी गार्ड चालू (फ़ोन पर दूसरी आवाज़ बज रही)
    private var mediaAt = 0L; private var mediaOn = false
    @Volatile var phoneAudioIgnored = 0              // कितनी बार फ़ोन की अपनी आवाज़ छोड़ी (debug)
        private set
    @Volatile private var thinkingSince = 0L
    @Volatile private var lastBreakdown = ""
    @Volatile private var warmReuses = 0
    @Volatile private var setupSentAt = 0L

    // ---- v3.68: बँटवारा - Gemini Live सिर्फ़ जवाब लिखे, बोलना अलग "आवाज़ इंजन" (voice/engine/*) करे
    /** इस connection में Live की अपनी आवाज़ नहीं बजती - उसका text SpeechPipeline बोलती है */
    @Volatile private var connSplit = false
    /** setup में responseModalities = TEXT (false + connSplit = AUDIO + transcript वाला तरीका) */
    @Volatile private var connText = false
    @Volatile private var connEngVer = -1
    /** v3.80: इस connection में Live की आवाज़ / अंदाज़ / मॉडल का version (बदले = चुप होते ही नया connection) */
    @Volatile private var connLiveVer = -1
    @Volatile private var splitEngine: com.dk.assistant.voice.engine.VoiceEngine? = null
    /** replyText का कितना हिस्सा pipeline को भेजा जा चुका (DoneGate रोके तो आगे नहीं) */
    @Volatile private var splitFedTo = 0
    /** v3.71: जवाब की शुरुआत का भाव-टैग पढ़कर हटाने वाला (सिर्फ़ TEXT बँटवारे में) */
    @Volatile private var tagFilter: com.dk.assistant.avatar.EmotionTag.Filter? = null
    /** इस बारी में pipeline से कुछ बोला गया */
    @Volatile private var splitSpoke = false
    private val speechHooks = object : com.dk.assistant.voice.engine.SpeechPipeline.Hooks {
        override fun usage() = CallVoice.usage(callAudioOn, btOn)
        override fun log(m: String) = this@GeminiLive.log(m)
        override fun onPlay(pcm: ByteArray, now: Long) {
            noteOutLevel(pcm, now)
            playEnd = maxOf(playEnd, now) + pcm.size * 1000L / (OUT_RATE * 2)
            turnPlayedBytes += pcm.size
        }
        override fun onFirstAudio(at: Long, engineId: String) {
            splitSpoke = true
            timeline.play(at)
            if (trace.speechEndAt > 0 && trace.firstPlayAt == 0L) {
                trace.firstPlayAt = at
                val ms = at - trace.speechEndAt
                lastLatency = ms
                if (ms in 0..60_000 && engineId.isNotBlank() && engineId != "phone") com.dk.assistant.voice.engine.VoiceEngines.recordLatency(svc, engineId, ms)
                val name = com.dk.assistant.voice.engine.VoiceEngines.byId(engineId)?.title ?: "फ़ोन की आवाज़"
                trace.summary()?.let { lastBreakdown = "$it • बोलने वाला: $name" }
                log("बोलना ख़त्म → पहली आवाज़ ($name): $ms ms" + (trace.summary()?.let { " • $it" } ?: ""))
                // v3.71: तीनों समय अलग (डिबग → देरी का हिसाब)
                LatencyBoard.record(name, trace.speechEndAt, trace.firstChunkAt, pipeline.firstSegPcmAt, at)?.let { log("देरी: " + LatencyBoard.line(it)) }
                trace.reset()
            }
            svc.speaker.ensureAudible()
        }
        override fun onSpeaking(on: Boolean) {
            if (stopped) return
            if (on) setMode(Mode.SPEAKING)
            else if (!generating && toolJobs.isEmpty() && !turnActive) setMode(Mode.LISTENING)
            else if (!generating && toolJobs.isNotEmpty() && DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
        }
        override fun onSpoken(soFar: String) { if (connSplit && soFar.isNotBlank()) { shownReply = soFar; DKState.replyLive(soFar) } }
        /** v3.74: फ़ोन की अपनी आवाज़ बंद - Live में सिर्फ़ Gemini की आवाज़; न बने तो चुप (text दिखता रहता है) */
        override suspend fun phoneSay(text: String) { DKLog.d("Live: आवाज़ नहीं बनी - फ़ोन TTS नहीं चलाया, सिर्फ़ लिखा") }
        override fun phoneStop() { try { svc.speaker.stop() } catch (_: Exception) {} }
        // v3.7: "बोला तो दिखा, जवाब भी दिखा, पर आवाज़ नहीं" - बँटवारे वाला इंजन (Gemini TTS) चुप रहा. अब: Gemini Live की अपनी आवाज़ पर
        override fun onSilent(text: String) {
            if (!connSplit) return
            splitSilentAt = System.currentTimeMillis()
            splitOffUntil = splitSilentAt + SilentVoice.OFF_MS
            if (silentResay.isBlank()) silentResay = text.take(300) else if (silentResay.length < 600) silentResay += " " + text.take(300)
        }
    }
    /** v3.7: बँटवारे वाला इंजन चुप रहा (कब) / कब तक उसे छोड़कर Live की अपनी आवाज़ / जो बोला नहीं जा सका */
    @Volatile private var splitSilentAt = 0L
    @Volatile private var splitOffUntil = 0L
    @Volatile private var silentResay = ""
    private val pipelineLazy: Lazy<com.dk.assistant.voice.engine.SpeechPipeline> = lazy { com.dk.assistant.voice.engine.SpeechPipeline(svc, speechHooks) }
    private val pipeline: com.dk.assistant.voice.engine.SpeechPipeline by pipelineLazy
    private fun pipeBusy() = pipelineLazy.isInitialized() && pipeline.busy
    private fun pipeStop() { if (pipelineLazy.isInitialized()) pipeline.stop() }

    /** नए connection से पहले: कौन बोलेगा (बँटवारा या Live की सीधी आवाज़) */
    private fun planSplit(m: String) {
        val engines = com.dk.assistant.voice.engine.VoiceEngines
        connEngVer = engines.version
        connLiveVer = engines.liveVersion
        val chosen = engines.active(svc)
        if (chosen != null && chosen.split && System.currentTimeMillis() < splitOffUntil) {
            connSplit = false; connText = false; splitEngine = null
            log("आवाज़ इंजन ${chosen.title} अभी चुप रहा था - ${SilentVoice.OFF_MS / 60_000} मिनट तक Gemini Live की अपनी आवाज़ (ताकि DK की आवाज़ आती रहे)")
            return
        }
        if (chosen == null || !chosen.split) {
            connSplit = false; connText = false; splitEngine = null
            log("बोलने वाला: ${chosen?.title ?: "Gemini Live"} (सीधी आवाज़, बँटवारा नहीं)")
            return
        }
        val usable = listOfNotNull(chosen, engines.fallback()).firstOrNull { it.notReady(svc) == null }
        if (usable == null) {
            connSplit = false; connText = false; splitEngine = null
            log("आवाज़ इंजन ${chosen.title} अभी तैयार नहीं (${chosen.notReady(svc)}) - तब तक Gemini Live की अपनी आवाज़")
            return
        }
        connSplit = true; splitEngine = usable   // v3.80: चुना तैयार न हो तो जो सच में बोलेगा वही
        connText = !engines.sp(svc).getBoolean("text_off|$m", false)
        log("बँटवारा: Live लिखेगा (${if (connText) "TEXT" else "AUDIO की लिखावट"}), बोलेगा ${usable.title} • ${usable.voice(svc)}")
        pipeline.warm(usable)
    }

    /** बँटवारे में: replyText का नया हिस्सा pipeline को (DoneGate रोके हो तो नहीं) */
    private fun pumpSplit() {
        if (!connSplit || actHold || muteTurn || dropOld) return
        val add = synchronized(turnLock) {
            if (splitFedTo > replyText.length) splitFedTo = replyText.length
            val t = replyText.substring(splitFedTo); splitFedTo = replyText.length; t
        }
        if (add.isNotEmpty()) pipeline.feed(add)
    }

    /** Live की अपनी आवाज़ का टुकड़ा: बँटवारे में बजता नहीं (सिर्फ़ गिनती) */
    private fun playOut(pcm: ByteArray) {
        if (connSplit) return
        playQ.offer(pcm)
    }

    /** Garam (standby) connection: wake word ke baad chup hone par band nahi kiya */
    private class Parked(val ws: WebSocket, val closed: CompletableDeferred<Pair<Int, String>>, val model: String, val at: Long,
                         val split: Boolean = false, val text: Boolean = false, val liveVer: Int = -1)
    @Volatile private var parked: Parked? = null
    @Volatile private var curClosed: CompletableDeferred<Pair<Int, String>>? = null

    // ---- bajana (playback)
    private val playQ = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var playEnd = 0L                // kab tak awaaz baj rahi hogi (andaza)
    @Volatile private var gen = 0
    private var playThread: Thread? = null
    fun isPlaying() = System.currentTimeMillis() < playEnd + 120 || playQ.isNotEmpty() || pipeBusy()

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

    /** v3.75: web_search tool - फ़ोन खुद Google Search (Gemini REST) करके छोटा जवाब + स्रोत लाता है */
    private suspend fun webSearchTool(query: String): String {
        val q = query.trim().ifBlank { turnUser.trim() }
        if (q.length < 2) return "Ask the user what to search."
        DKState.hint(LivePromise.hintFor("web_search", q))
        setMode(Mode.THINKING)
        // v3.87: Live की खोज = Live की अपनी Gemini key पहले; fail = ख़ुद एक बार दोबारा (छोटी, तेज़ खोज)
        // v3.93: सामान्य ज्ञान / गणित / कहानी (ताज़ा शब्द नहीं) = खोज नहीं, अपनी जानकारी से
        if (WebSearch.skip(q)) {
            log("web_search: \"${q.take(40)}\" - सामान्य सवाल, खोज नहीं")
            return "NO_SEARCH_NEEDED: this is general knowledge (not live or changing). Answer it now from your own knowledge, briefly. Do not call web_search for it again."
        }
        val brain = BrainManager(svc).also { it.fromLive = true }
        // v3.88: यूज़र के बोलते ही शुरू हुई खोज उसी बात की है = वही नतीजा (इंतज़ार कम)
        prepFail = null
        var r: SearchResult? = preparedSearch(q)?.also { log("web_search: पहले से तैयार नतीजा इस्तेमाल किया") }
        var why = SearchGuard.Fail.OTHER
        // v3.90: अभी-अभी (यूज़र के बोलते ही) वही पूरी Google खोज fail हुई = वही दोबारा भेजना बेकार (सीमा और भरती, समय जाता),
        // सीधे बचाव वाली खोज (बिना-key वेबसाइटें + ज़रूरत हो तो एक छोटी Gemini खोज)
        val pf = prepFail
        if (r == null && pf != null) {
            why = pf; log("web_search: पहले वाली खोज fail (${SearchGuard.hindi(pf)}) - सीधे बचाव वाली खोज")
            val fb = fallbackSearch(q, why, brain); r = fb.first; why = fb.second
            if (r == null) why = pf
        }
        if (r == null && pf == null) {
            r = try { withTimeoutOrNull(LiveSearch.totalTimeoutMs(Prefs.searchOwnKey(svc).isNotBlank()) + chainExtraMs()) { brain.search(q, deep = false) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                why = brain.lastSearchFail
                log("web_search fail (कोशिश 1): ${SearchGuard.hindi(why)} - ${e.javaClass.simpleName} ${e.message?.take(120)}")
                null
            }
            if (r == null && why == SearchGuard.Fail.OTHER) why = SearchGuard.Fail.TIMEOUT
            if (r != null && r.text.isBlank()) { r = null; why = SearchGuard.Fail.EMPTY }
            // v3.90: Google खोज न चली तो हार नहीं - बिना key वाली दूसरी वेबसाइटें (Google News, Wikipedia) + एक छोटी दोबारा खोज, साथ-साथ
            if (r == null) { val fb = fallbackSearch(q, why, brain); r = fb.first; why = fb.second }
        }
        val res = r
        if (res == null) {
            log("web_search: \"${q.take(40)}\" -> दो कोशिश के बाद भी नहीं (${SearchGuard.hindi(why)})")
            DKState.hint(when (why) {
                SearchGuard.Fail.NO_KEY -> "${WebSearch.ALL_FAILED_LINE} - सेटिंग → खोज में Tavily, Exa या Gemini key डालें"
                SearchGuard.Fail.BAD_KEY -> "${WebSearch.ALL_FAILED_LINE} - खोज की key ग़लत है, सेटिंग → खोज में जाँचें"
                SearchGuard.Fail.QUOTA -> "${WebSearch.ALL_FAILED_LINE} - खोज की limit खत्म है"
                SearchGuard.Fail.NETWORK -> "${WebSearch.ALL_FAILED_LINE} - इंटरनेट की दिक्कत है"
                else -> WebSearch.ALL_FAILED_LINE
            })
            return SearchGuard.failResult(q, why)
        }
        // स्रोत सिर्फ़ तब, जब सच में खोज से जवाब आया
        if (res.sources.isNotEmpty()) toolSources = Grounding.merge(toolSources, res.sources)
        log("web_search: \"${q.take(40)}\" -> ${res.text.length} अक्षर, ${res.sources.size} स्रोत")
        return "SEARCH RESULT (verified from several websites): " + res.text.take(3200) + "\n" + LiveSearch.sourcesLine(res.sources) +
            "\nSpeak the answer from this result now, briefly and naturally. Do not read URLs."
    }

    /**
     * v3.90: Gemini की खोज fail होने पर बचाव - (1) बिना-key खोज: Google News + Wikipedia, (2) साथ में छोटी Gemini खोज
     * (सिर्फ़ जब दोबारा कोशिश का फ़ायदा हो: धीमा नेट / अनजानी गड़बड़ - सीमा भरी/key ग़लत पर नहीं, वरना वही गलती फिर).
     * Gemini वाला मिले तो वही (ज़्यादा सटीक), वरना बिना-key वाला. कुछ न मिले = (null, असली वजह).
     */
    private suspend fun fallbackSearch(q: String, why0: SearchGuard.Fail, brain: BrainManager): Pair<SearchResult?, SearchGuard.Fail> = coroutineScope {
        var why = why0
        val open = async(Dispatchers.IO) { try { OpenSearchNet.search(q) } catch (e: Exception) { log("बिना-key खोज fail: ${e.message}"); null } }
        // v3.93: Tavily/Exa वाला क्रम पहले ही सब सेवाएँ आज़मा चुका = Gemini से दोबारा खोज नहीं (एक सवाल पर एक खोज)
        val retry = why in listOf(SearchGuard.Fail.TIMEOUT, SearchGuard.Fail.OTHER, SearchGuard.Fail.EMPTY) && chainExtraMs() == 0L
        val quick = if (!retry) null else try { withTimeoutOrNull(16_000) { brain.quickSearch(q) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (brain.lastSearchFail != SearchGuard.Fail.OTHER) why = brain.lastSearchFail
                log("web_search fail (कोशिश 2): ${SearchGuard.hindi(why)} - ${e.message?.take(100)}"); null
            }
        if (quick != null && quick.text.isNotBlank()) { open.cancel(); return@coroutineScope quick to why }
        val o = withTimeoutOrNull(12_000) { open.await() }
        open.cancel()
        if (o != null) {
            log("web_search: Google खोज नहीं चली (${SearchGuard.hindi(why)}) - बिना-key वेबसाइटों से जवाब")
            return@coroutineScope SearchResult(OPEN_NOTE + o.text, o.sources) to why
        }
        null to why
    }

    /** v3.93: Tavily/Exa पहले आज़माए जाते हैं - उनके लिए कुछ सेकंड और (हर एक ज़्यादा से ज़्यादा ~10 सेकंड) */
    private fun chainExtraMs(): Long =
        (if (Prefs.tavilySearchKey(svc).isNotBlank()) 10_000L else 0L) + (if (Prefs.exaSearchKey(svc).isNotBlank()) 10_000L else 0L)

    private val OPEN_NOTE = "(The normal Google search did not work right now, so these come from Google News headlines and Wikipedia. " +
        "Answer from them. If they do not answer the exact question, say briefly what you found and that the full detail could not be confirmed.)\n"

    @Volatile private var promiseJob: Job? = null
    @Volatile private var promiseFixAt = 0L

    /**
     * v3.75: बारी खत्म - Gemini ने सिर्फ़ "सर्च करके बताता हूँ / एक मिनट / कर रहा हूँ" कहा और कोई tool नहीं बुलाया?
     * तो चुप न रहो: सवाल था -> फ़ोन खुद सर्च करे और नतीजा Gemini को दे (वह बोले); काम था -> Gemini से phone_task करवाओ.
     */
    private fun promiseCheck() {
        val now = System.currentTimeMillis()
        val (said, heard) = synchronized(turnLock) { replyText.toString().trim() to userText.toString().trim() }
        val user = (if (!userDone) turnUser.trim() else "").ifBlank { heard }.ifBlank { caption?.text().orEmpty().trim() }
        val kind = LivePromise.kind(said, user)
        if (kind == LivePromise.Kind.NONE || user.length < 2) return
        val sources = synchronized(turnLock) { turnSources }
        if (actEvidence || toolJobs.isNotEmpty() || sources.isNotEmpty() || fastJob?.isActive == true ||
            now - lastFastOkAt < DoneGate.FAST_EVIDENCE_MS || now - promiseFixAt < 15_000 || promiseJob?.isActive == true) return
        promiseFixAt = now
        log("वादा-जाँच: Gemini ने \"${said.take(50)}\" कहा पर कोई tool नहीं - ${if (kind == LivePromise.Kind.SEARCH) "फ़ोन खुद सर्च कर रहा है" else "phone_task करवा रहा हूँ"}")
        if (kind == LivePromise.Kind.ACTION) {
            // v3.6 (owner: "सुना, लिखा भी दिखा, पर काम नहीं किया"): पहले सिर्फ़ Gemini को याद दिलाते थे - वह फिर भी
            // अक्सर tool नहीं बुलाता था. अब फ़ोन खुद वही काम करता है, फिर Gemini को असली नतीजा (वह सच बोले).
            promiseJob = svc.scope.launch {
                DKState.hint(LivePromise.hintFor("phone_task", user))
                setMode(Mode.WORKING)
                val r = try { phoneTask(user) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
                DKState.hint("")
                log("वादा-जाँच: फ़ोन ने खुद काम किया - ${r.take(60)}")
                visionNote(LivePromise.actionDoneNote(user, r), evidence = LivePromise.actionOk(r))
            }
            return
        }
        promiseJob = svc.scope.launch {
            DKState.hint(LivePromise.hintFor("web_search", user))
            setMode(Mode.THINKING)
            val brain = BrainManager(svc).also { it.fromLive = true }
            var why = SearchGuard.Fail.TIMEOUT
            var r = try { withTimeoutOrNull(LiveSearch.totalTimeoutMs(Prefs.searchOwnKey(svc).isNotBlank()) + 3_000 + chainExtraMs()) { brain.search(user, deep = false) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { why = brain.lastSearchFail; log("वादा-जाँच सर्च fail: ${e.message}"); null }
            // v3.90: यहाँ भी बचाव वाली खोज, और fail हो तो असली वजह यूज़र को
            if (r == null || r.text.isBlank()) { val fb = fallbackSearch(user, why, brain); r = fb.first; why = fb.second }
            DKState.hint("")
            if (r == null || r.text.isBlank()) { visionNote(LivePromise.searchFailNote(user, why), evidence = false); return@launch }
            if (r.sources.isNotEmpty()) toolSources = Grounding.merge(toolSources, r.sources)
            visionNote(LivePromise.searchNote(user, r.text), evidence = true)
        }
    }

    /**
     * v3.5+: बारी ख़त्म - यूज़र ने किसी एजेंट (कहानी / कोडिंग / DK कंप्यूटर / वेबसाइट) को काम कहा, DK ने "हाँ, कर रही हूँ /
     * हो गया" कहा, पर उसका tool नहीं बुलाया? तो फ़ोन खुद वही tool चलाए - एजेंट की स्क्रीन खुले और काम वहाँ दिखे,
     * फिर Gemini को असली नतीजा (वह सच बोले). true = यहीं संभाल लिया (promiseCheck नहीं).
     */
    private fun agentCheck(): Boolean {
        val now = System.currentTimeMillis()
        val (said, heard) = synchronized(turnLock) { replyText.toString().trim() to userText.toString().trim() }
        val user = (if (!userDone) turnUser.trim() else "").ifBlank { heard }.ifBlank { caption?.text().orEmpty().trim() }
        val recent = now - agentToolAt < AGENT_TOOL_RECENT_MS || toolJobs.isNotEmpty()
        val agent = LiveAgentRoute.missed(user, said, recent) ?: return false
        if (now - agentFixAt < 15_000) return false
        agentFixAt = now; agentToolAt = now
        log("एजेंट-जाँच: Gemini ने \"${said.take(50)}\" कहा पर ${agent.tool} नहीं बुलाया - फ़ोन खुद ${agent.title} को काम दे रहा है")
        DKState.hint("${agent.title} को काम दे रही हूँ…")
        svc.scope.launch {
            val a = JSONObject(); LiveAgentRoute.args(agent, user).forEach { (k, v) -> a.put(k, v) }
            val r = try { agentTool(agent.tool, a) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("एजेंट-जाँच: ${agent.tool} fail: ${e.message}"); "Tool failed (${e.javaClass.simpleName}) - tell the user it could not be done."
            }
            log("एजेंट-जाँच: ${agent.tool} -> ${r.take(80)}")
            DKState.hint("")
            if (DoneGate.notDoneYet(r)) actPending = true
            visionNote(LiveAgentRoute.note(agent, r), evidence = true)
        }
        return true
    }

    private fun newLeakFilter() = LeakedCall.Filter(onOpen = { onLeakOpen() }, onCall = { runLeaked(it) })

    /** v4.1: जवाब में लिखी हुई tool call शुरू होती दिखी - Live की आवाज़ तुरंत बंद (वह "phone task…" बोलने वाला है) */
    private fun onLeakOpen() {
        if (leakMute) return
        leakMute = true
        flushPlayback()
        log("Live ने tool call बुलाने की जगह लिख दी - छिपाई, आवाज़ रोकी, फ़ोन खुद काम करेगा")
    }

    /** v4.1: लिखी हुई call = असली tool की तरह उसी पल चलाओ (वही रास्ता जो सच्चे toolCall का है) */
    private fun runLeaked(c: LeakedCall.Call) {
        val now = System.currentTimeMillis()
        val key = c.name + "|" + c.args.toString()
        if (key == lastLeakKey && now - lastLeakAt < LEAK_DEDUP_MS) { log("लिखी हुई ${c.name} - अभी-अभी यही चलाई, दोबारा नहीं"); return }
        lastLeakKey = key; lastLeakAt = now
        val args = JSONObject(); c.args.forEach { (k, v) -> args.put(k, v) }
        log("लिखी हुई call पकड़ी: ${c.name} - सीधे चला रहे हैं")
        lastToolAt = now
        runTools(JSONArray().put(JSONObject().put("id", LEAK_ID + now).put("name", c.name).put("args", args)))
    }

    private fun leakNote(name: String, result: String): String =
        "SYSTEM NOTE: you wrote the function call for $name as text instead of calling it, so the phone ran it itself. " +
            "Real result: \"" + result.take(800) + "\". Tell the user this real result in ONE short natural sentence in their language " +
            "(e.g. \"हो गया सर\") - never read out function names, braces, arguments or <ctrl> tokens. Do NOT call $name again for this request."

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
    /** v3.94: अभी कौन सी key चल रही है (0 = मुख्य) - मॉडल की limit हर key की अलग */
    @Volatile private var keyIdx = 0
    private fun blockModel(m: String, why: String) {
        val n = Cooldown.strike(LiveKeys.cooldownName(keyIdx, m))
        val ms = if (n <= 1) 30 * 60_000L else Cooldown.msToQuotaReset()
        Cooldown.block(LiveKeys.cooldownName(keyIdx, m), ms)
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
        NowClock.syncAsync()   // v3.80: सही समय (फ़ोन की घड़ी ग़लत हो तो इंटरनेट से)
        stopped = false; goAway = false; idleExit = false; lastAct = System.currentTimeMillis(); callAudioOn = false; lastUserLine = ""; lastDkLine = ""
        sent = 0; sentBytes = 0; dropped = 0; gaps = 0; readErr = 0; recvAudio = 0; reconnects = 0
        if (!Setup.hasMic(svc)) throw Fatal("माइक की परमिशन नहीं है")
        // v3.94: मुख्य key + बैकअप keys - एक बंद/गलत/limit खत्म = चुपचाप अगली (user को पता न चले)
        val keys = Prefs.liveGeminiKeys(svc).ifEmpty { throw Fatal("Gemini की key नहीं है") }
        var ki = 0; keyIdx = 0; var mi = 0
        var key = keys[0]
        var models: List<String> = emptyList()
        while (true) {
            try { models = LiveModelPick.order(LiveModelPick.get(svc), discoverModels(key)); break }   // v3.80: चुना हुआ Live मॉडल पहले
            catch (e: Fatal) {
                val nk = LiveKeys.next(ki, keys.size); if (nk < 0) throw e
                ki = nk; keyIdx = ki; key = keys[ki]; log("key ${ki} नहीं चली (${e.message}) - बैकअप key ${ki + 1} से")
            }
        }
        /** अगली बैकअप key पर चुपचाप - true = बदल गई (मॉडल फिर से पहले से) */
        fun switchKey(why: String): Boolean {
            val nk = LiveKeys.next(ki, keys.size); if (nk < 0) return false
            ki = nk; keyIdx = ki; key = keys[ki]
            models = try { LiveModelPick.order(LiveModelPick.get(svc), discoverModels(key)) } catch (_: Exception) { FALLBACK_MODELS }
            mi = 0; handle = null
            log("$why - बैकअप key ${ki + 1} से जुड़ रहा हूँ (models: ${models.joinToString()})")
            return true
        }
        // v4.2: मुख्य key के सारे मॉडल की limit पहले से खत्म (पिछली बार पता चला) = बेकार connection नहीं, सीधे बैकअप key
        while (LiveKeys.allBlocked(models) { Cooldown.blocked(LiveKeys.cooldownName(keyIdx, it)) }) {
            if (!switchKey("key ${ki + 1} के सारे Live मॉडल की limit आज खत्म")) break
        }
        log("models: ${models.joinToString()}")
        if (stopped) return@withContext "जुड़ने से पहले बंद"
        mi = 0; var fails = 0
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
        caption = LiveCaption(LiveKeys.order(listOf(capKey) + Prefs.liveGeminiKeys(svc)), onText = { t, fin -> onCaption(t, fin) }, onLate = { t -> onLateCaption(t) },
            isNewSpeech = { barging || System.currentTimeMillis() < bargeUntil }).also { it.start() }
        turnDet.reset(); bargeDet.reset(); bargeGate.reset(); synchronized(bargeBuf) { bargeBuf.clear() }
        askQueue.clear(); holdCap = ""; holdCapAt = 0L; holdLoudAt = 0L; holdFinal = false
        guardHeld = false; guardOk = false; guardPass = false; guardEndAt = 0L; guardBuf.clear(); guardOn = false
        trace.reset(); dropOld = false; suspectAt = 0L; replyWatch.reset()
        timeline.reset(); liveGate.reset(); capGate.reset(); resendText = null; lastServerAt = 0L
        owner.reset(); ownerGen++; ownerResult = null; ownerHolding = false; ownerHold.clear(); muteTurn = false; ownerCfgAt = 0L
        refreshOwnerCfg(System.currentTimeMillis())
        if (ownerOn) { com.dk.assistant.voice.OwnerVoice.warm(svc); log("सिर्फ़ मालिक की आवाज़: चालू (" + (if (Prefs.ownerVoiceStrict(svc)) "सख़्त" else "सामान्य") + ")") }
        if (turnDet.startFloor > 0) log("दूर की आवाज़ अनसुनी: चालू (${turnDet.startFloor.toInt()} RMS से धीमी नहीं)")
        coroutineScope {
            val mic = launch(Dispatchers.IO) { micLoop() }
            // DK ka text awaaz ke saath-saath (har 80ms)
            val reveal = launch(Dispatchers.IO) { while (isActive && !stopped) { revealTick(); delay(80) } }
            try {
                while (!stopped && isActive) {
                    if (svc.callTakesMic()) return@coroutineScope      // v3.40: घंटी पर नहीं, call उठने पर ही
                    model = models[mi]
                    planSplit(model)                 // v3.68: इस connection में कौन बोलेगा (बँटवारा / सीधी आवाज़)
                    // v3.8: wake word ke baad garam rakha connection abhi zinda hai -> naya mat banao (0 ms)
                    val pk = parked; parked = null
                    val reuse = pk != null && !pk.closed.isCompleted && pk.model == model &&
                        System.currentTimeMillis() - pk.at < PARK_MS && pk.split == connSplit && pk.text == connText &&
                        pk.liveVer == connLiveVer   // v3.80: बीच में आवाज़/मॉडल बदला = पुराना गर्म connection नहीं
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
                                if (switchKey("key गलत/बंद (code=$code)")) continue else throw Fatal("Gemini key गलत है या Live की इजाज़त नहीं")
                            isQuota(code, r) || r.contains("rate") -> {
                                blockModel(model, "setup")
                                if (mi + 1 < models.size) { mi++; handle = null; continue }
                                else if (switchKey("इस key के सारे Live मॉडल की limit खत्म")) continue
                                else throw Fatal("Gemini Live की फ्री लिमिट अभी खत्म है")
                            }
                            // v3.68: यह मॉडल TEXT जवाब नहीं देता (native-audio मॉडल सिर्फ़ AUDIO) -> AUDIO + लिखावट से बँटवारा, याद रखो
                            connText && (r.contains("modalit") || r.contains("text") || r.contains("not supported") ||
                                r.contains("invalid") || code == 1007) -> {
                                com.dk.assistant.voice.engine.VoiceEngines.sp(svc).edit().putBoolean("text_off|$model", true).apply()
                                log("यह मॉडल ($model) TEXT जवाब नहीं देता - AUDIO की लिखावट से बँटवारा (Live की आवाज़ नहीं बजेगी)")
                                handle = null; continue
                            }
                            useSearch && (r.contains("search") || r.contains("tool")) -> { useSearch = false; continue }
                            // v3.59: आवाज़ (Sulafat) / hi-IN / affective यह मॉडल न ले -> चुपचाप उसके बिना (Aoede), याद रखो
                            LiveVoice.degrade(model, extrasFor(model), reason, code) != null -> {
                                val st = LiveVoice.degrade(model, extrasFor(model), reason, code)!!
                                extras = st.next; extrasModel = model
                                Prefs.addLiveExtrasOff(svc, st.offEntry)
                                log("यह मॉडल ${st.what} नहीं लेता - उसके बिना" + if (st.offEntry.contains("|voice:")) " (आवाज़ ${LiveVoice.FALLBACK})" else "")
                                handle = null; continue
                            }
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
                    Cooldown.clear(LiveKeys.cooldownName(keyIdx, model))
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
                        // v3.68: सेटिंग्स में आवाज़ इंजन बदला और connection का तरीका (TEXT/AUDIO) बदलना ज़रूरी -> चुप होने पर नया connection
                        if (connEngVer != com.dk.assistant.voice.engine.VoiceEngines.version) {
                            val ch = com.dk.assistant.voice.engine.VoiceEngines.active(svc)
                            val wantSplit = ch != null && ch.split
                            if (wantSplit == connSplit) { connEngVer = com.dk.assistant.voice.engine.VoiceEngines.version; splitEngine = if (wantSplit) ch else null
                                log("बोलने वाला अब: ${ch?.title}"); if (wantSplit) pipeline.warm(ch) }
                            else if (!turnActive && !isPlaying()) { log("आवाज़ इंजन बदला (${ch?.title}) - नया connection"); goAway = true; goAwayDrop = true }
                        }
                        // v3.80: Live की अपनी आवाज़ / अंदाज़ / Live मॉडल बदला -> DK चुप होते ही नया connection (उसी बातचीत में).
                        // बँटवारे वाले इंजन (Gemini TTS / Edge) की आवाज़ हर टुकड़े पर नई पढ़ी जाती है - वहाँ connection नहीं तोड़ना
                        if (connLiveVer != com.dk.assistant.voice.engine.VoiceEngines.liveVersion && !turnActive && !isPlaying() && toolJobs.isEmpty()) {
                            connLiveVer = com.dk.assistant.voice.engine.VoiceEngines.liveVersion
                            val pick = LiveModelPick.get(svc)
                            val reordered = LiveModelPick.order(pick, models)
                            if (pick.isNotBlank() && reordered.first() != model) {
                                models = reordered; mi = 0; handle = null
                                log("Live मॉडल बदला -> $pick - नया connection (नई बातचीत)"); goAway = true; goAwayDrop = true
                            } else if (!connSplit) {
                                // पुराने सत्र (handle) पर server पुरानी आवाज़ रख सकता है -> नई आवाज़ पक्की लगे, इसलिए नया सत्र (पिछली बातें याद/prompt में)
                                handle = null
                                log("Live की आवाज़ बदली (${Prefs.geminiVoice(svc)}) - नया connection, अब नई आवाज़"); goAway = true; goAwayDrop = true
                            }
                        }
                        // v3.7: बँटवारे वाला इंजन चुप रहा = DK चुप होते ही नया connection, Live की अपनी आवाज़ में (जो छूटा वह दोबारा बोलेगा)
                        if (connSplit && splitSilentAt > 0 && !turnActive && !isPlaying() && toolJobs.isEmpty()) {
                            splitSilentAt = 0L
                            log("आवाज़ नहीं बनी (सिर्फ़ लिखा गया) - अब Gemini Live की अपनी आवाज़, नया connection")
                            goAway = true; goAwayDrop = true
                        }
                        if (connSplit && !turnActive && !isPlaying()) pipeline.warm(splitEngine)
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
                    if (reason.lowercase().contains("api key")) {
                        if (turnActive) finishTurn()
                        if (switchKey("key बंद हो गई")) continue else throw Fatal("Gemini key बंद हो गई")
                    }
                    if (isQuota(code, reason.lowercase())) {
                        blockModel(model, "बातचीत के बीच")
                        if (turnActive) finishTurn()
                        if (mi + 1 < models.size) { mi++; handle = null; continue }
                        else if (switchKey("बातचीत के बीच इस key की limit खत्म")) continue
                        else throw Fatal("Gemini Live की फ्री लिमिट खत्म")
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
        val p = Parked(w, c, model, System.currentTimeMillis(), connSplit, connText, connLiveVer)
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
        val (skip, ok) = all.partition { Cooldown.blocked(LiveKeys.cooldownName(keyIdx, it)) }
        if (skip.isNotEmpty()) log("limit खत्म, छोड़े: ${skip.joinToString()}")
        log("सबसे तेज़ क्रम: " + ok.take(3).joinToString { m -> m + (lat[m]?.let { " ${it}ms" } ?: "") })
        return ok.ifEmpty { all }
    }

    private fun setupJson(m: String): String {
        NetProbe.refresh(svc)      // v3.88: धीमा नेटवर्क = छोटे जवाब (अपने-आप, कोई सेटिंग नहीं)
        val brain = BrainManager(svc)
        val ex = extrasFor(m)
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().put("name", "phone_task")
                .put("description", "Do something on the user's Android phone: open apps (including opening WhatsApp), call, SMS, YouTube, music, settings (wifi, bluetooth, torch, volume, brightness), maps/directions, shopping, read or tap the screen. NOT for a single WhatsApp message - use whatsapp_task (action=send_message) for that. BUT if the user gives SEVERAL tasks in one go (e.g. alarm, then WhatsApp, then delete photo, then YouTube, then maps), call phone_task ONCE with his full request - the phone runs them one by one in order. While tasks run: a new task = phone_task with only that task (added at the end); 'रुको'/'बंद करो' = phone_task with request 'रुको' (stops at once).")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("request", JSONObject().put("type", "STRING").put("description", "The user's request in their own words")))
                    .put("required", JSONArray().put("request"))))
            .put(JSONObject().put("name", "web_search")
                .put("description", "Live search RIGHT NOW: searches several websites at once (official government websites first for government questions, plus reputed news and reference sites), cross-checks them and returns one verified, up-to-date answer with the website names (SOURCES). Use it for anything live or changing: news, prices, weather, scores, government schemes/rules/forms/dates, results, recruitments, people in office, products, releases - and anything you are not sure about. Whenever you say you will search / check / look it up, you MUST call this in the SAME turn - never only promise. (Opening a website or searching inside Chrome/an app on the phone = phone_task.)")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("query", JSONObject().put("type", "STRING").put("description", "What to search, with all details the user gave")))
                    .put("required", JSONArray().put("query"))))
            .put(JSONObject().put("name", "current_time")
                .put("description", "Get the exact current date, day and time (internet-checked clock, user's time zone). Call it whenever the user asks the time, date or day, or before saying how late/early it is - never guess.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
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
            .put(JSONObject().put("name", "dk_features")
                .put("description", "Get the REAL list of everything DK can do - all agents and modes (phone control, WhatsApp, calls, social media, Coding Agent team, DK Computer, Website Forge, Story Mode, Teacher Mode, Autonomous Mode, camera, screen share, search, memory) and which tool does what. Call it when the user asks what you can do / which agents DK has / what is inside an agent (तुम क्या-क्या कर सकती हो, कौन-कौन से एजेंट हैं), or when you are unsure which tool fits a task.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
            .put(JSONObject().put("name", "story_task")
                .put("description", "Story Mode: the Story Writer Agent writes a long emotional Hindi story (10, 15, 20, 25 or 30 minutes) part by part on DK's Story screen. Use it when the user asks to write/make a story or kahani (कहानी लिखो, 20 मिनट की कहानी बनाओ, स्टोरी मोड). It returns story_started immediately; you get a SYSTEM NOTE when it is really finished.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("topic", JSONObject().put("type", "STRING").put("description", "The story topic / instructions in the user's words (empty = the agent picks one)"))
                        .put("minutes", JSONObject().put("type", "INTEGER").put("description", "Length in minutes if the user said it (10-30), else 0")))))
            .put(JSONObject().put("name", "teacher_mode")
                .put("description", "Open (on=true) or close (on=false) DK Teacher Mode - a whiteboard page where everything you explain is written for studying. Use it when the user says टीचर मोड खोलो / पढ़ाई शुरू करो / टीचर मोड बंद करो.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN")))
                    .put("required", JSONArray().put("on"))))
            .put(JSONObject().put("name", "social_media_task")
                .put("description", "Post on Instagram or Facebook by driving the real app on the phone. Instagram: post_photo, post_video, post_reel, post_story. Facebook: post_text, post_photo, post_video, post_story. Media can ONLY be a photo/video the user shared to DK (Gallery -> Share -> DK); use media='latest_shared'. mode='draft' fills everything but never posts. Posting is irreversible: you will get a SYSTEM NOTE with status waiting_confirmation - ask the user that exact question and only after a clear yes call social_media_task_control(command=confirm). DK never fills logins, passwords, CAPTCHAs or 2-step codes - it waits for the user. Report the final status truthfully (submitted_unverified = not confirmed).")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("platform", JSONObject().put("type", "STRING").put("enum", JSONArray().put("instagram").put("facebook")))
                        .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("post_photo").put("post_video").put("post_reel").put("post_story").put("post_text")))
                        .put("caption", JSONObject().put("type", "STRING").put("description", "Caption / post text exactly as the user wants it"))
                        .put("media", JSONObject().put("type", "STRING").put("description", "latest_shared (default)"))
                        .put("mode", JSONObject().put("type", "STRING").put("enum", JSONArray().put("publish").put("draft"))))
                    .put("required", JSONArray().put("platform").put("action"))))
            .put(JSONObject().put("name", "whatsapp_task")
                .put("description", "WhatsApp on the phone - ALWAYS use this (never phone_task) to send a WhatsApp message or make a WhatsApp call: read_unread (unread messages from notifications), voice_call / video_call (contact - WhatsApp audio or video call; when the user says WhatsApp पर कॉल / वीडियो कॉल; a plain X को कॉल करो without WhatsApp is a normal phone call, not this), send_message (contact + message; irreversible - wait for waiting_confirmation SYSTEM NOTE, ask the user, confirm only after a clear yes), read_chat (contact), search_chat (query), mute / unmute / mark_read (contact). If the contact is ambiguous you get contact_ambiguous - ask the user which one.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("read_unread").put("voice_call").put("video_call").put("send_message").put("read_chat").put("search_chat").put("mute").put("unmute").put("mark_read")))
                        .put("contact", JSONObject().put("type", "STRING").put("description", "Contact name as saved, or phone number"))
                        .put("message", JSONObject().put("type", "STRING"))
                        .put("query", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("action"))))
            .put(JSONObject().put("name", "social_media_task_control")
                .put("description", "Control the running Instagram/Facebook/WhatsApp task: status, confirm (ONLY after the user clearly said yes to the confirmation question - DK checks this), cancel, resume (after the user finished a login/CAPTCHA/permission screen themselves).")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "STRING").put("enum", JSONArray().put("status").put("confirm").put("cancel").put("resume")))
                        .put("task_id", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("command"))))
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
                .put("description", "Turn sharing of the user's phone screen with you ON or OFF (it is OFF by default). Only while it is ON you receive an image of the screen about once a second. If the user asks you to look at / read / check their screen and sharing is OFF, first ask 'स्क्रीन शेयर चालू करूँ?'; when they agree, or when they directly ask you to share or see the screen, call with on=true. Never turn it on by yourself without the user's clear request or yes. Android then shows its own permission popup and the USER must tap Start (DK never taps it). Never describe the screen before you get the SYSTEM NOTE that sharing really started. Call with on=false when the user says to stop.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN")))
                    .put("required", JSONArray().put("on"))))
            .put(JSONObject().put("name", "camera")
                .put("description", "Turn the phone camera ON or OFF so you can see what the user shows you (OFF by default). Only while it is ON you receive a camera image about once a second. Use when the user says कैमरा चालू करो / कैमरा से देखो / ये देखो क्या है (and the camera is off), or to turn it off. This is DK's own camera inside the app (not the phone's camera app). facing: front when the user wants you to look at THEM (मुझे देखो / मेरा चेहरा देखो / मैं कैसा लग रहा हूँ / आगे वाला कैमरा); back when they want to show you something (ये देखो / पीछे वाला कैमरा / बैक कैमरा). If the camera is already on and the user wants the other side, call again with on=true and the new facing.")
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
            .put(JSONObject().put("name", "recall_conversation")
                .put("description", "The user asks what HE said earlier or what you two talked about (मैंने क्या बोला था / अभी मैंने क्या कहा था / कल क्या बात हुई थी / मैंने उस बारे में क्या बताया था / तुमने क्या कहा था). Searches the conversation saved ONLY on this phone (last 30 days + permanent short notes) - no internet. question = the user's question in his own words (include the time like अभी/कल/आज and the topic). Then say the result to the user briefly in your own voice; if it says it was not found, say exactly 'बॉस, मुझे ये याद नहीं मिल रहा।' - never invent.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("question", JSONObject().put("type", "STRING").put("description", "The user's question, his words")))
                    .put("required", JSONArray().put("question"))))
            .put(JSONObject().put("name", "local_info")
                .put("description", "Get the user's CURRENT location (fresh GPS/network, city/area name) and live weather right now. Call it when the user asks about weather, their area, nearby events/news/traffic/pandals/melas, or anything 'here/आसपास/मेरे शहर'. For local news or events, then use Google Search with the city name.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
            .put(JSONObject().put("name", "remember_fact")
                .put("description", "Save a personal fact or preference the user told about themselves.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("fact", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("fact"))))))
        // v3.72: जुड़े कनेक्टर (Gmail, Drive, GitHub, FB Page, MCP...) - कोई नहीं जुड़ा = कुछ नहीं जुड़ता
        com.dk.assistant.connect.ConnectorTool.declaration(svc)?.let { d ->
            tools.optJSONObject(0)?.optJSONArray("functionDeclarations")?.put(d); log("कनेक्टर: connector_task जोड़ा")
        }
        // v3.93: Tavily/Exa की key हो तो Live की अपनी Google Search नहीं - हर खोज web_search से (Tavily -> Exa -> Gemini क्रम)
        val ownChain = Prefs.tavilySearchKey(svc).isNotBlank() || Prefs.exaSearchKey(svc).isNotBlank()
        if (useSearch && !ownChain) tools.put(JSONObject().put("googleSearch", JSONObject()))
        val setup = JSONObject()
            .put("model", "models/$m")
            .put("generationConfig", JSONObject()
                // v3.68: बँटवारा - Live सिर्फ़ जवाब लिखे (TEXT), बोलना अलग आवाज़ इंजन (voice/engine). मॉडल TEXT न ले तो
                // AUDIO + transcript (Live की आवाज़ बजती नहीं, उसका लिखा text इंजन बोलता है)
                .put("responseModalities", JSONArray().put(if (connText) "TEXT" else "AUDIO"))
                .apply {
                    // v3.9: 2.5 native-audio pehle "sochta" hai (der). Baatcheet me band - mushkil sawaal deep_think tool se.
                    // (3.1 ka default "minimal" hai; 3.8-live me thinking setting dena mana hai)
                    if (thinkOff && m.startsWith("gemini-2.5")) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
                .apply { if (ex.affective && !connText) put("enableAffectiveDialog", true) }
                .apply { if (!connText) put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", ex.voice)))
                    .apply { if (ex.lang) put("languageCode", LiveVoice.LANGUAGE) }) })
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lang.sys(brain.liveSystemPrompt()) + FAST_START + NetSpeed.promptNote() + (if (connSplit) SPLIT_NOTE + (if (connText) MOOD_TAG_NOTE else "") else
                com.dk.assistant.voice.engine.LiveNativeEngine.styleNote(com.dk.assistant.voice.engine.LiveNativeEngine.style(svc).ifBlank { CalmPersona.VOICE_STYLE }))))))
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
            .apply { if (!connText) put("outputAudioTranscription", JSONObject()) }
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
            if (setupSentAt > 0) { trace.rttMs = System.currentTimeMillis() - setupSentAt; log("setup का जवाब ${trace.rttMs} ms (network RTT + server)"); NetSpeed.onSetup(trace.rttMs) }
            // ready + purani (judte samay ki) awaaz ek saath, lock me: mic ka naya tukda purane se pehle na nikle
            synchronized(sendLock) { ready = true; flushPending() }
            setup.complete(Unit)
            if (firstText != null) openerText = null; sendFirstText(); sendResend(); sendOpener()
            // v3.7: पिछला जवाब सिर्फ़ लिखा गया था (आवाज़ नहीं बनी) - अब Live की अपनी आवाज़ में दोबारा
            if (!connSplit && silentResay.isNotBlank()) { val t = silentResay; silentResay = ""; visionNote(SilentVoice.resayNote(t), evidence = false) }
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
                firstAudioAt = 0; if (!isPlaying() && !muteTurn) setMode(Mode.THINKING)
            }
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let {
            lastServerAt = System.currentTimeMillis()
            // v3.66: किसी और की आवाज़ पर बना काम - कुछ मत करो (फ़ोन पर कोई काम नहीं), Gemini को बता दो
            if (muteTurn) { ignoreTools(it); return@let }
            // v3.67: tool से पहले बोला "कर दिया" = झूठ (काम अभी शुरू हुआ) - वह हिस्सा नहीं बजता; "ठीक है बॉस" बजता है
            // v3.90: काम से पहले की बातें ("ठीक है, खोल रहा हूँ…" / इधर-उधर की बात) नहीं - पहले काम, फिर नतीजे वाली बात
            if (actHold) {
                val cmd = !actAfterTool && actCommand
                // v3.7 (मालिक): हुक्म = काम से पहले कुछ नहीं (ठीक है भी नहीं) - काम के बाद "हो गया सर"
                val v = if (cmd) TaskFirst.beforeToolCommand(heldActText())
                        else DoneGate.beforeTool(heldActText())
                // v3.90: हुक्म = छोटा "ठीक है, कर रहा हूँ" बजे, उससे ज़्यादा बात हटे
                if (v == DoneGate.Verdict.DROP) dropAct(if (cmd) "काम से पहले की लंबी बात - पहले काम" else "tool से पहले") else releaseAct(if (cmd) "छोटा ठीक है" else "tool आया")
            }
            actEvidence = true
            timeline.event(lastServerAt); replyWatch.onServerAnswered(); lastToolAt = lastServerAt; runTools(it)
        }
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
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() && !muteTurn }?.let { t ->
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
            // v3.66: किसी और की आवाज़ का जवाब - बजाओ मत
            if (muteTurn) { mutedParts++; generating = true; return@let }
            if (!turnActive) startTurn()
            generating = true
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                // v3.68: TEXT जवाब (बँटवारा) - जैसे-जैसे आए, आवाज़ इंजन को (पहला वाक्यांश बनते ही बोलना शुरू)
                val tx = if (p.optBoolean("thought")) "" else p.optString("text", "")
                if (tx.isNotEmpty() && connSplit) {
                    replyPartArrived("पहला text")
                    val noLeak = leakFilter.push(tx)               // v4.1: "phone_task{…}" न दिखे, न बोला जाए - काम सीधे
                    val clean = if (noLeak.isEmpty()) "" else tagFilter?.push(noLeak) ?: noLeak   // v3.71: [खुश] जैसा टैग न बोला जाए, न दिखे
                    if (clean.isNotEmpty()) synchronized(turnLock) { replyText.append(clean) }
                    splitHoldCheck(); pumpSplit()
                    continue
                }
                val inline = p.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                if (!mime.startsWith("audio/pcm")) { log("अनजान output: $mime"); continue }
                val pcm = try { Base64.decode(inline.optString("data"), Base64.DEFAULT) } catch (e: Exception) { log("audio decode गलती"); continue }
                if (pcm.isEmpty()) continue
                recvAudio += pcm.size
                replyPartArrived("पहला ऑडियो चंक")
                timeline.audio(System.currentTimeMillis())
                if (connSplit) continue              // v3.68: transcript वाला बँटवारा - Live की अपनी आवाज़ नहीं बजती
                if (leakMute) continue               // v4.1: "phone task request…" जैसी आवाज़ नहीं - फ़ोन चुपचाप काम कर रहा है
                if (actHold) { holdAct(pcm); continue }
                turnRecvBytes += pcm.size
                playOut(pcm)
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() && !dropOld && !muteTurn }?.let { t ->
            if (!turnActive) startTurn()
            generating = true
            // screen par abhi nahi - revealTick() awaaz ke saath-saath dikhata hai
            val tc = leakFilter.push(t)              // v4.1: लिखी हुई tool call हटाओ (और उसी पल असली काम)
            val soFar = synchronized(turnLock) { replyText.append(tc); if (replyText.length < 240) replyText.toString() else null }
            // v3.71: सीधी आवाज़ में टैग नहीं (बोल दिया जाता) - शुरू के शब्दों से अवतार का भाव
            soFar?.let { com.dk.assistant.avatar.EmotionGuess.of(it) }?.takeIf { it != com.dk.assistant.avatar.Mood.NORMAL }
                ?.let { com.dk.assistant.avatar.MoodState.set(it) }
            // v3.68: AUDIO + लिखावट वाला बँटवारा - यही text आवाज़ इंजन बोलता है
            if (connSplit) { replyPartArrived("पहली लिखावट"); splitHoldCheck(); pumpSplit() }
        }
        // v3.9: Gemini ne sach me Google Search kiya -> sources (title, url) is baari ke jawab ke saath
        (sc.optJSONObject("groundingMetadata") ?: o.optJSONObject("groundingMetadata"))?.let { gm ->
            val add = Grounding.sources(gm).also { WebSearch.tag(it, WebSearch.Provider.GEMINI) }
            if (add.isNotEmpty()) {
                synchronized(turnLock) { turnSources = Grounding.merge(turnSources, add) }
                if (toolJobs.isEmpty()) DKState.hint("")
                log("Google Search हुआ - ${add.size} स्रोत: ${add.joinToString { Grounding.host(it.second).ifBlank { it.first } }}")
            }
        }
        if (sc.optBoolean("interrupted") && muteTurn) {
            // किसी और की बात का (न बजा) जवाब रुका - बस छूट खत्म, बाकी कुछ नहीं बदला
            muteTurn = false; generating = false
            log("दूसरे की आवाज़ का (न बजाया) जवाब रुका")
        } else if (sc.optBoolean("interrupted")) {
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
            tagFilter = null; com.dk.assistant.avatar.MoodState.replyDone()
            leakFilter.finish(); leakFilter = newLeakFilter(); leakMute = false
            clearAct()
            flushPlayback()
            // close=false: user abhi bol raha hai (toka hai) - uski nayi baat band mat karo
            if (turnActive) finishTurn(close = false)
            commitPending(false)          // jitna bola utna hi likha rahe
            setMode(Mode.LISTENING)
        }
        if (sc.optBoolean("turnComplete") && muteTurn) {
            muteTurn = false; generating = false
            log("दूसरे की आवाज़ का जवाब नहीं बजाया ($mutedParts टुकड़े छोड़े)")
            Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        } else if (sc.optBoolean("turnComplete")) {
            val wasDropping = dropOld
            dropOld = false
            if (actHold) endActHold()
            // v4.1: रुका हुआ बचा हिस्सा (अधूरी लिखी call भी हो तो अभी चलेगी)
            leakFilter.finish().takeIf { it.isNotEmpty() }?.let { r -> (tagFilter?.push(r) ?: r).takeIf { it.isNotEmpty() }?.let { c -> synchronized(turnLock) { replyText.append(c) } } }
            leakFilter = newLeakFilter(); leakMute = false
            tagFilter?.finish()?.takeIf { it.isNotEmpty() }?.let { r -> synchronized(turnLock) { replyText.append(r) } }
            tagFilter = null
            com.dk.assistant.avatar.MoodState.replyDone()         // v3.71: बोलना ख़त्म होने के कुछ सेकंड बाद चेहरा सामान्य
            if (connSplit && !wasDropping) { pumpSplit(); if (pipelineLazy.isInitialized()) pipeline.endTurn() }   // v3.68: बचा text आख़िरी टुकड़ा
            if (timeline.active) { timeline.done(System.currentTimeMillis()); log("turn पूरा: ${timeline.summary()}") }
            if (!wasDropping && !agentCheck()) promiseCheck()
            finishTurn()
            if (wasDropping) commitPending(false)   // toka hua jawab: sirf jitna bola utna hi chat me
            // jawab poora bana; jab DK ki awaaz bajna band ho uske ~1.2s baad peeche ki awaaz dheere-dheere wapas
            Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        }
    }

    /** जवाब का हिस्सा (आवाज़ या text) आया: पहली बार पर बारी की गिनती + देरी (पहले inline था; v3.68 में text के लिए भी) */
    private fun replyPartArrived(what: String) {
        if (!replyStarted) {
            replyStarted = true
            // naya jawab: pichhla (agar abhi bhi baj raha tha) poora likho, ginti shuru se
            commitPending(true)
            turnRecvBytes = 0; turnPlayedBytes = 0; shownReply = ""
            // user ki baat yahin tak - aage jo bole woh nayi baat
            caption?.closeUtterance()?.takeIf { it.isNotBlank() }?.let { turnUser = it; userDone = false }
            beginActHold()
            // v3.71: नया जवाब = भाव शुरू से (TEXT बँटवारे में टैग से, बाकी में लिखावट के शब्दों से)
            com.dk.assistant.avatar.MoodState.set(com.dk.assistant.avatar.Mood.NORMAL)
            tagFilter = if (connSplit && connText) com.dk.assistant.avatar.EmotionTag.Filter { com.dk.assistant.avatar.MoodState.set(it) } else null
            if (connSplit) {
                splitSpoke = false
                synchronized(turnLock) { splitFedTo = minOf(splitFedTo, replyText.length) }
                val e = splitEngine ?: com.dk.assistant.voice.engine.VoiceEngines.fallback()
                if (e != null) pipeline.begin(e)
            }
        }
        if (firstAudioAt == 0L) {
            firstAudioAt = System.currentTimeMillis()
            if (trace.firstChunkAt == 0L) trace.firstChunkAt = firstAudioAt
            val from = if (trace.speechEndAt > 0 && firstAudioAt - trace.speechEndAt < 30_000) trace.speechEndAt else eosAt
            if (from > 0) {
                lastLatency = firstAudioAt - from; log("देरी: बोलना खत्म → $what = $lastLatency ms")
                // v3.41: is model ki asli raftaar yaad - agli baar sabse tez model pehle (tool wali baari nahi)
                if (lastToolAt < from && toolJobs.isEmpty() && model.isNotBlank()) Prefs.recordLiveLatency(svc, model, lastLatency)
            }
        }
        if (actPending && !actHold) {
            actPending = false; actAfterTool = true
            actTextFrom = synchronized(turnLock) { replyText.length }; actHold = true; actHoldAt = System.currentTimeMillis()
        }
    }

    /** बँटवारे में आवाज़ नहीं रुकती, text रुकता है: इतना लिखा और कोई "हो गया" दावा नहीं = आम बात, बोलो */
    private fun splitHoldCheck() {
        if (!connSplit || !actHold) return
        val t = heldActText()
        if (t.length >= DoneGate.holdLimitMs(actAfterTool, actCommand) * 14 / 1000 && !HonestGuard.claimsDone(t)) releaseAct("आम बात")
        else if (TaskFirst.release(actCommand && !actAfterTool, System.currentTimeMillis() - actHoldAt, t)) releaseAct("आम बात / ज़रूरी सवाल")
    }

    private fun startTurn() {
        turnActive = true; routeDedupe.reset(); clearAct(); actEvidence = false
        leakFilter = newLeakFilter(); leakMute = false
        synchronized(turnLock) { userText.clear(); replyText.clear(); turnSources = emptyList(); splitFedTo = 0 }
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
        if (!isPlaying()) idleMode()
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

    private fun now0() = System.currentTimeMillis()

    /** v4.1: रोकी हुई आवाज़ मालिक की है? (मिलान फ़ेल / आवाज़ बहुत छोटी = हाँ, पहले जैसा) */
    private fun ownerSaysOwner(held: Collection<ByteArray>): Boolean {
        return try {
            val out = java.io.ByteArrayOutputStream(); held.forEach { out.write(it) }
            val audio = out.toByteArray()
            if (audio.size < IN_RATE * 2 / 2) return true
            val sc = com.dk.assistant.voice.OwnerVoice.score(svc, audio) ?: return true
            sc >= ownerTh
        } catch (_: Throwable) { true }
    }

    /** Caption is key par chal raha hai -> "आप:" sirf usi se (Live model ka der se aaya text screen par nahi, warna text aage-peeche hota) */
    private fun captionOn() = caption?.let { it.ready && it.working } == true

    /** Caption se turant shabd - "आप:" line */
    private fun onCaption(t: String, final: Boolean) {
        // v3.53: फ़ोन पर वीडियो/गाना बज रहा है और DK चुप - बिना DK के नाम वाले शब्द फ़ोन की अपनी आवाज़ (न दिखाओ, न धीमा करो)
        if (guardOn && !isPlaying() && !guardOk) {
            val named = if (dkOnlyNow) DkOnly.filter(t) != null else PhoneAudioGuard.hasName(t, Prefs.wakeWords(svc))
            if (!(guardHeld && named)) return
            guardOk = true
            log("वीडियो/गाने के बीच DK का नाम सुना (\"${t.take(40)}\") - यूज़र की बात, Gemini को भेज रहा हूँ")
        }
        lastAct = System.currentTimeMillis()
        if (t.isNotBlank()) Ducker.duck(svc, "Live: user बोल रहा")   // user ke shabd aaye -> peeche ka gaana/video dheema
        // DK bol raha hai aur user ke asli shabd aaye (mic DK bolte samay sirf tez awaaz bhejta hai) -> DK chup
        // (caption ko DK bolte samay sirf phone ki pakdi user-awaaz jaati hai). Jawab abhi ban raha ho to
        // yeh pichhli baat ka der se aaya text ho sakta hai - tab phone/server ka barge-in hi rokega
        suspectAt = 0L
        if (bargeGate.state == BargeGate.State.HOLD) {
            // v3.5+: यूज़र का अगला सवाल सुन रहे (जवाब चल रहा). "रुको" आया = अभी रोको; बाकी = सुनते रहो, जवाब के बाद
            val now = System.currentTimeMillis()
            if (t != holdCap) { holdCap = t; holdCapAt = now }
            if (final) holdFinal = true
            if (!LiveStop.isStop(t)) { DKState.hint(HOLD_HINT); return }
            askQueue.clear()
            synchronized(sendLock) { bargeGate.confirm(); confirmBarge("रुको: ${t.take(30)}") }
        } else if (bargeGate.state == BargeGate.State.TENTATIVE) {
            val dkNow = dkHeardNow()
            // DK ki apni awaaz ya pichhli baat ka der se aaya text = user ka naya bolna nahi
            // (v3.5+: पूरे शब्द मिलाकर, आम शब्द छोड़कर - पहले "है/क्या" से असली सवाल भी गूँज मान लिया जाता था)
            val real = BargeGate.realWords(t) && !BargeGate.isEcho(t, dkNow) && !BargeGate.isRepeat(t, turnUser)
            if (!real) { log("caption: \"${t.take(40)}\" - पक्का नहीं, जवाब जारी"); return }
            if (dkOnlyNow && !CallCmd.windowOpen(now0()) && !svc.callRinging() && DkOnly.filter(t) == null && !LiveStop.isStop(t)) return   // v4.1: DK नहीं बोला = अनदेखा
            if (captionOn() && !LiveStop.isStop(t)) {
                // अगला सवाल: जवाब मत काटो (Gemini को आवाज़ नहीं - वरना सर्वर पुराना जवाब रोक देता), caption सुनता रहे
                val now = System.currentTimeMillis()
                synchronized(sendLock) { bargeGate.hold(now) }
                holdCap = t; holdCapAt = now; holdLoudAt = now; holdFinal = final
                track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
                log("यूज़र ने बीच में कुछ पूछा (\"${t.take(30)}\") - जवाब नहीं रोका, सवाल सुन रहा हूँ")
                DKState.hint(HOLD_HINT)
                return
            }
            if (LiveStop.isStop(t)) askQueue.clear()
            // sendLock: CONFIRMED hote hi mic thread naya tukda bhejta hai - pehle shak ke samay ki (purani) awaaz jaye
            synchronized(sendLock) { bargeGate.confirm(); confirmBarge("caption: ${t.take(30)}") }
        } else if (isPlaying()) return        // DK bol raha hai aur shak nahi = shor/goonj ka text, dikhao bhi mat
        DKState.user("$t…")
        prefetch(t, final)        // v3.88: यूज़र बोल ही रहा है - ज़रूरी डेटा पहले से तैयार (गलत अंदाज़ा = चुपचाप बेकार)
        if (final) checkMicOff(t)        // andaze (interim) par mic band nahi - "माइक बंद मत करो" bhi ho sakta hai
        if (final) capFinal = t.trim()
        // v3.41: ghanti baj rahi hai aur pakka text aaya ("उठा लो" / "काट दो") -> bolna-khatam ka intezaar bhi nahi, turant
        // v4.1: काटकर मैसेज / तैयार मैसेज / म्यूट / कॉल काटो (घंटी, कॉल, या कॉल के ठीक बाद) - फ़ोन खुद, Gemini का जवाब/tool नहीं
        if (final && System.currentTimeMillis() - playEnd > 600 && !micMuted && CallCmd.windowOpen(System.currentTimeMillis())) {
            val cc = CallCmd.route(t, System.currentTimeMillis())
            if (cc != null) {
                muteTurn = true; muteAt = System.currentTimeMillis()
                log("कॉल कमांड फ़ोन ने सुनी: ${cc.action}")
                svc.scope.launch { svc.doCallCmd(cc) }
                return
            }
        }
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
        if (connSplit) return                                   // v3.68: बँटवारे में screen का text onSpoken से (जितना बोला)
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
    // ================================================================ v3.88: बोलते-बोलते तैयारी (speculative prefetch)
    private class Prep(val kind: SpecIntent.Kind, val text: String, val at: Long, val job: Deferred<Any?>)
    @Volatile private var prepWeather: Prep? = null
    @Volatile private var prepSearch: Prep? = null

    /**
     * "हेलो डीके, आज का मौसम बताओ" - "मौसम" सुनते ही लोकेशन + मौसम लाना शुरू. Gemini जब local_info माँगे तो तैयार मिले.
     * ताज़ा खबर/स्कोर = पक्का (final) text आते ही खोज शुरू; Gemini की web_search उसी बात की हो तो वही नतीजा.
     * अंदाज़ा गलत = कोई इस्तेमाल नहीं करता, कुछ देर में अपने-आप पुराना (यूज़र को कुछ नहीं दिखता/सुनाई देता).
     */
    private fun prefetch(t: String, final: Boolean) {
        val k = SpecIntent.detect(t) ?: return
        val now = System.currentTimeMillis()
        try {
            when (k) {
                SpecIntent.Kind.WEATHER -> {
                    if (prepWeather?.let { now - it.at < PREP_WEATHER_MS } == true) return
                    if (!LocalInfo.hasPermission(svc)) return
                    prepWeather = Prep(k, t, now, svc.scope.async(Dispatchers.IO) {
                        try { LocalInfo.now(svc) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    })
                    log("पहले से तैयारी: मौसम (\"${t.take(30)}\")")
                }
                SpecIntent.Kind.SEARCH -> {
                    if (!final) return
                    val p = prepSearch
                    if (p != null && now - p.at < PREP_SEARCH_MS && p.text == t.trim()) return
                    val q = t.trim()
                    prepSearch = Prep(k, q, now, svc.scope.async(Dispatchers.IO) {
                        val b = BrainManager(svc).also { it.fromLive = true }
                        // v3.90: fail हुई तो वजह भी (सीमा भरी/key ग़लत = web_search फिर वही Google खोज न दोहराए)
                        try { withTimeoutOrNull(LiveSearch.totalTimeoutMs(Prefs.searchOwnKey(svc).isNotBlank())) { b.search(q, deep = false) } ?: SearchGuard.Fail.TIMEOUT }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { b.lastSearchFail }
                    })
                    log("पहले से तैयारी: खोज (\"${q.take(30)}\")")
                }
            }
        } catch (e: Exception) { log("पहले से तैयारी नहीं हुई: ${e.javaClass.simpleName}") }
    }

    /** तैयार मौसम (ताज़ा हो तो); नहीं = null (तब पहले जैसा अभी लाओ) */
    @Suppress("UNCHECKED_CAST")
    private suspend fun preparedWeather(): Pair<LocalInfo.Place, String>? {
        val p = prepWeather ?: return null
        if (System.currentTimeMillis() - p.at > PREP_WEATHER_MS) { prepWeather = null; return null }
        return (try { p.job.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }) as? Pair<LocalInfo.Place, String>
    }

    /** तैयार खोज - उसी बात की हो और ताज़ा हो तो; नहीं = null (तब पहले जैसी खोज) */
    private suspend fun preparedSearch(query: String): SearchResult? {
        val p = prepSearch ?: return null
        if (System.currentTimeMillis() - p.at > PREP_SEARCH_MS || !SpecIntent.sameTopic(query, p.text)) return null
        prepSearch = null       // एक बार ही इस्तेमाल
        val r = try { p.job.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        prepFail = r as? SearchGuard.Fail
        return (r as? SearchResult)?.takeIf { it.text.isNotBlank() }
    }
    /** v3.90: पहले से शुरू हुई खोज किस वजह से fail हुई (null = fail नहीं / थी ही नहीं) */
    @Volatile private var prepFail: SearchGuard.Fail? = null

    private suspend fun localInfoTool(): String {
        if (!LocalInfo.hasPermission(svc)) return "Location permission is OFF. Ask the user to allow location for DK (open the DK app; it asks), then try again."
        val r = preparedWeather()?.also { log("local_info: पहले से तैयार मौसम इस्तेमाल किया") } ?: LocalInfo.now(svc) ?: return "Could not get the current location right now (GPS/location may be off). Tell the user honestly."
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

    /**
     * v3.5+: एजेंट वाले tool (कहानी / वेबसाइट / DK कंप्यूटर / कोडिंग) एक जगह - Gemini के tool call से भी और
     * [agentCheck] से भी (Gemini ने "हाँ कर दिया" कहा पर tool नहीं बुलाया). काम सच में सौंपा गया = उस एजेंट की स्क्रीन
     * खुलती है और वहाँ "🎤 DK लाइव से मिला काम" दिखता है.
     */
    private fun agentTool(name: String, args: JSONObject): String {
        val agent = LiveAgentRoute.byTool(name) ?: return "unknown function"
        // v3.6: एक key = सिर्फ़ बातचीत. एजेंट नहीं चलेगा - DK साफ़ बताए + Settings खुले (झूठा "हो गया" नहीं)
        OneKeyGate.LIVE_TOOLS[name]?.takeIf { OneKeyGate.blocked(svc, it) }?.let { w ->
            log("एक key मोड: ${w.hi} नहीं चलेगा - Settings खोली"); OneKeyGate.openSettings(svc)
            return OneKeyGate.liveNote(w)
        }
        var handoff = true          // false = सिर्फ़ हाँ/नहीं/इजाज़त का जवाब (नया काम नहीं)
        val task = when (name) { "story_task" -> args.optString("topic"); "build_website" -> args.optString("prompt"); else -> args.optString("task") }.trim()
        val result = when (name) {
            "story_task" -> LiveReport.startStory(svc, args.optString("topic"), args.optInt("minutes", 0))
            "build_website" -> com.dk.assistant.forge.ForgeLauncher.fromVoice(svc, args.optString("prompt"))
            "computer_task" -> {
                val t = args.optString("task").trim()
                val yn = if (ComputerSession.ask != null) ComputerVoice.yesNo(t) else null
                when {
                    yn != null -> { handoff = false; ComputerSession.answer(yn); if (yn) "Permission given - DK Computer continues." else "Permission refused - DK Computer will not do it." }
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
                    else -> CoderTeam.voiceDecision(svc, t)?.also { handoff = false } ?: when {
                        // 7-एजेंट चालू = पुराना टीम switch ज़रूरी नहीं
                        !Prefs.coderSeven(svc) && !Prefs.coderTeam(svc) -> "Coding team is OFF in settings (सेटिंग्स → कोडिंग एजेंट → टीम)."
                        else -> CoderTeam.start(svc, t, fromVoice = true)?.let { "Could not start: $it" }
                            ?: if (Prefs.coderSeven(svc)) "Started (11-agent team). FIRST its prompt engineer shows the user what it understood and WAITS: when the user confirms (हाँ / yes), call this tool again with task='हाँ'; if the user corrects or answers its question, call this tool again with exactly that answer; 'रद्द' cancels. It is NOT done yet - real progress: call task_status. You will get a SYSTEM NOTE when it really finishes; never say it is done before that."
                            else "Started. Builder writes, Reviewer checks, Coordinator pushes and will notify when done. It is NOT done yet - real progress: call task_status."
                    }
                }
            }
            else -> "unknown function"
        }
        if (handoff && LiveAgentRoute.started(result)) {
            AgentHandoff.mark(agent, task, result)
            if (agent == LiveAgentRoute.Agent.CODING || agent == LiveAgentRoute.Agent.COMPUTER) LiveReport.openAgent(svc, agent)
            log("एजेंट को काम दिया: ${agent.title} - स्क्रीन पर दिख रहा है")
        }
        return result
    }

    private fun runTools(calls: JSONArray) {
        for (i in 0 until calls.length()) {
            val fc = calls.optJSONObject(i) ?: continue
            val id = fc.optString("id"); val name = fc.optString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
            if (!toolDedup.firstTime(id)) { log("tool: $name - यही call (id) पहले आ चुका, दोबारा नहीं"); continue }
            // DK AI Part 12: caption / संदेश / कॉन्टैक्ट log में नहीं
            log("tool: $name " + if (name == "social_media_task" || name == "whatsapp_task") args.optString("action") else if (name == "connector_task") args.optString("connector") + "." + args.optString("action") else args.toString().take(80))
            val tStart = System.currentTimeMillis()
            if (name in LiveAgentRoute.TOOLS) agentToolAt = tStart      // v3.5+: इस बात पर एजेंट का tool सच में बुलाया
            // v3.39: जिस जुड़ाव पर सवाल आया, जवाब सिर्फ़ उसी पर. बीच में दोबारा जुड़ा = पुराना id नए सत्र को अनजान -> 1007
            val sock = ws
            // LAZY: pehle map me darj, phir shuru - warna tez tool (remember_fact) map me aane se pehle hi remove() kar deta tha
            val j = svc.scope.launch(start = CoroutineStart.LAZY) {
              // v3.75: screen पर दिखे कि DK अभी क्या कर रहा है (सर्च / सोच / फ़ोन का काम)
              LivePromise.hintFor(name, args.optString("query").ifBlank { args.optString("request") }).takeIf { it.isNotEmpty() }?.let { DKState.hint(it) }
              try {
                val result = try { when (name) {
                    "phone_task" -> phoneTask(args.optString("request"))
                    "task_status" -> TaskStatus.summary()
                    "phone_call" -> callTool(args.optString("action"), "Gemini")
                    "screen_share" -> LiveVision.setScreen(svc, args.optBoolean("on", true))
                    "camera" -> LiveVision.setCamera(svc, args.optBoolean("on", true), args.optString("facing"))
                    "dk_features" -> LiveConnect.features(com.dk.assistant.auto.AutoPrefs.on(svc),
                        com.dk.assistant.auto.AutoPrefs.enabledKinds(svc).map { it.title }, Prefs.oneKeyOn(svc) && Prefs.oneKey(svc).isNotBlank())
                    "story_task", "build_website", "computer_task", "coding_task" -> agentTool(name, args)
                    "teacher_mode" -> LiveReport.teacher(svc, args.optBoolean("on", true))
                    "remember_fact" -> if (Memory.addFact(svc, args.optString("fact"))) "saved" else "NOT saved: private info (passwords, PIN, OTP, bank/card/Aadhaar numbers) is never stored. If the user asked you to remember it, tell them gently you do not save such private details." 
                    "recall_conversation" -> {
                        val a = LocalMemory.answer(svc, args.optString("question").ifBlank { DKState.lastUser.trimEnd('…') })
                        if (a == LocalRecall.NOT_FOUND) "NOT FOUND in the phone's saved conversation. Say exactly: बॉस, मुझे ये याद नहीं मिल रहा।"
                        else "FOUND on the phone (saved conversation). Tell the user this briefly, in your voice, keep the quoted words exact:\n$a"
                    }
                    "forget_memory" -> { val n = Memory.forget(svc, args.optString("about")); if (n > 0) "Forgotten ($n place(s) deleted)." else "Nothing saved about that - tell the user it is not in memory." }
                    "local_info" -> localInfoTool()
                    "connector_task" -> withContext(Dispatchers.IO) { com.dk.assistant.connect.ConnectorTool.run(svc, args) }
                    "notifications" -> notifTool(args.optString("app"))
                    "notification_reply" -> notifReply(args.optString("to"), args.optString("message"))
                    "social_media_task" -> com.dk.assistant.agent.android.SocialAgent.start(svc, args)
                    "whatsapp_task" -> when (val act = args.optString("action").trim().lowercase()) {
                        in setOf("read_unread", "unread", "read") -> notifTool("WhatsApp")
                        // v4: WhatsApp आवाज़ / वीडियो कॉल - सीधे (स्क्रीन पर बटन ढूँढे बिना)
                        in setOf("voice_call", "video_call", "call", "audio_call") -> {
                            val cmd = WaCall.Cmd(args.optString("contact").trim(), act == "video_call")
                            DKState.set(Mode.WORKING)
                            val r = WaCall.call(svc, cmd.name, cmd.video)
                            log("WhatsApp ${if (cmd.video) "वीडियो " else ""}कॉल \"${cmd.name}\" -> ${r.substringBefore(':')}")
                            LiveFast.quickResult(QuickCommands.Quick(WaCall.toJson(cmd), ""), r, already = false)
                        }
                        else -> com.dk.assistant.agent.android.SocialAgent.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP)
                    }
                    "social_media_task_control" -> com.dk.assistant.agent.android.SocialAgent.control(args)
                    "web_search" -> webSearchTool(args.optString("query"))
                    "current_time" -> { NowClock.syncAsync(); NowClock.toolAnswer() }
                    "deep_think" -> {
                        DKState.set(Mode.THINKING)
                        try {
                            withTimeoutOrNull(45_000) { BrainManager(svc).also { it.fromLive = true }.think(args.optString("question")) }
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
                    JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result.take(if (name == "deep_think" || name == "connector_task" || name == "web_search") 4000 else 1500))))))
                log("tool $name ${(System.currentTimeMillis() - tStart)} ms: ${result.take(if (name == "connector_task") 24 else 80)}")
                if (name != "remember_fact") lastEvidenceAt = System.currentTimeMillis()
                // v3.67: नतीजा "अभी हुआ नहीं" -> इसके बाद Gemini "हो गया" बोले तो वह नहीं बजेगा
                if (name != "remember_fact" && name != "task_status" && DoneGate.notDoneYet(result)) actPending = true
                // ab se deri ginti: tool ka jawab bheja -> pehli awaaz (tool ka samay alag dikhta hai)
                eosAt = System.currentTimeMillis(); firstAudioAt = 0
                val cur = ws
                if (id.startsWith(LEAK_ID)) {
                    // v4.1: यह call Live ने लिखी थी (असली toolCall नहीं) - server को इसका id पता नहीं, इसलिए नतीजा SYSTEM NOTE में
                    visionNote(leakNote(name, result), evidence = !DoneGate.notDoneYet(result))
                } else if (cur != null && cur === sock) {
                    if (!cur.tx(resp.toString())) log("tool जवाब नहीं भेज पाया")
                    else if (name in ANSWER_TOOLS) watchToolAnswer(name, result, cur)
                }
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
                if (toolJobs.isEmpty() && promiseJob?.isActive != true) { DKState.hint(""); DKState.refresh() }
              }
            }
            toolJobs[id] = j
            DKState.refresh()       // v3.90: अब सच में काम चल रहा है = "Thinking" दिखे
            j.start()
        }
    }

    /**
     * v3.80: नतीजा (खोज / सोच / समय) भेज दिया पर Gemini चुप रह गया = यूज़र "सुनकर रह गया" वाली हालत.
     * [TOOL_ANSWER_MS] तक कुछ न आए (आवाज़/लिखावट/यूज़र की बात) -> एक बार याद दिलाओ कि अब जवाब बोलो.
     */
    private fun watchToolAnswer(name: String, result: String, sock: WebSocket) {
        val sentAt = System.currentTimeMillis()
        svc.scope.launch {
            delay(TOOL_ANSWER_MS)
            if (stopped || !ready || ws !== sock) return@launch
            if (lastServerAt > sentAt || lastAct > sentAt || generating || isPlaying() || toolJobs.isNotEmpty()) return@launch
            log("tool $name का नतीजा ${TOOL_ANSWER_MS / 1000}s से बोला नहीं गया - Gemini को याद दिलाया")
            DKState.hint("जवाब तैयार कर रहा है…")
            if (DKState.mode != Mode.WORKING) setMode(Mode.THINKING)
            try {
                sock.tx(JSONObject().put("realtimeInput", JSONObject().put("text",
                    "SYSTEM NOTE: you already received the result of $name for the user's last question but have not answered yet. " +
                        "Answer the user NOW, briefly and naturally in Hindi, from this result: \"" + result.take(900) + "\"")).toString())
            } catch (_: Exception) {}
            delay(10_000)
            if (toolJobs.isEmpty() && promiseJob?.isActive != true && !generating) DKState.hint("")
        }
    }

    /** v3.80: tool चलते समय आवाज़ खत्म = "सोच/खोज रहा है" दिखे, सुन रहा नहीं (वरना लगता बात छूट गई) */
    private fun idleMode() {
        if (DKState.mode == Mode.WORKING) return
        setMode(if (toolJobs.isNotEmpty()) Mode.THINKING else Mode.LISTENING)
    }

    /**
     * v3.29: phone_task तेज़ + सच्चा.
     * Gemini Live का tool call रुकने वाला है (मॉडल जवाब आने तक चुप) - इसलिए यहाँ देर = DK की चुप्पी.
     * 1) बात खत्म होते ही वही काम हो चुका (scheduleFast) -> तुरंत "हो चुका"
     * 2) सीधा काम (QuickCommands) -> खुद, ~0.1-1s
     * 3) बाकी AgentRunner; 20s में पूरा न हो तो Gemini को सच ("अभी चल रहा") और पूरा होने पर उसे बताओ
     * पहले: 60s तक इंतज़ार और नतीजा न मिले तो "done" (झूठा "हो गया").
     */
    // ---------------- v3.87: एक साथ कई काम (कतार) ----------------
    @Volatile private var taskQueue: TaskRunQueue? = null
    @Volatile private var queueAnswer: CompletableDeferred<String>? = null

    private suspend fun phoneTask(req: String): String {
        val running = taskQueue?.takeIf { it.running }
        if (running != null) {
            if (TaskQueue.isStop(req)) { stopQueue("phone_task: \"${req.take(20)}\""); return TaskQueue.stoppedNote() }
            // कतार ने सवाल पूछा था और Gemini ने जवाब phone_task में भेज दिया - वही जवाब
            if (running.asking) { queueAnswer?.complete(req); return "QUEUE_OK: the phone took this as the answer to its question. Say only \"ठीक है\" - no tool call." }
            // काम के बीच नया काम = कतार के आख़िर में
            val n = running.add(TaskQueue.split(req).ifEmpty { listOf(TaskQueue.Item(req, "other")) })
            log("कतार: नया काम आख़िर में जोड़ा (\"${req.take(30)}\", अब $n बाक़ी)")
            return TaskQueue.addedNote(n)
        }
        val items = TaskQueue.split(req)
        if (items.size >= 2) return startQueue(items)
        return phoneTaskOne(req)
    }

    private fun startQueue(items: List<TaskQueue.Item>): String {
        val startedAt = System.currentTimeMillis()
        val q = TaskRunQueue(
            runOne = { runQueued(it) },
            prepare = { prev, next, prevOk -> queuePrepare(prev, next, prevOk) },
            ask = { queueAsk(it) },
            log = { log(it) }
        )
        q.add(items)
        taskQueue = q
        TeacherBoard.exitBlocked = { taskQueue?.running == true }
        log("कतार शुरू: ${items.size} काम - " + items.joinToString(" | ") { "${it.domain}:${it.text.take(25)}" })
        svc.scope.launch {
            // "रुको / बंद करो" - Gemini के भरोसे नहीं, फ़ोन ख़ुद सुनता है
            val watcher = launch {
                var seen = DKState.lastUserAt
                while (isActive) {
                    delay(200)
                    val at = DKState.lastUserAt
                    if (at != seen && at > startedAt) {
                        seen = at
                        if (TaskQueue.isStop(DKState.lastUser.trimEnd('…'))) { stopQueue("यूज़र ने कहा \"${DKState.lastUser.take(20)}\""); break }
                    }
                }
            }
            val summary = try { q.runAll() } catch (e: CancellationException) { TaskQueue.summary(q.results, true, q.left()) }
                catch (e: Exception) { log("कतार गड़बड़: ${e.message}"); TaskQueue.summary(q.results, q.stopped, q.left()) }
                finally { watcher.cancel() }
            if (taskQueue === q) taskQueue = null
            log("कतार ख़त्म: $summary")
            withTimeoutOrNull(15_000) { while (isPlaying() || generating || toolJobs.isNotEmpty()) delay(150) }
            if (stopped || !ready) return@launch
            visionNote(TaskQueue.summaryNote(summary), evidence = true)
        }
        return TaskQueue.startedNote(items)
    }

    private fun stopQueue(why: String) {
        val q = taskQueue ?: return
        if (q.stopped) return
        q.stop()
        log("कतार तुरंत रोकी: $why")
        queueAnswer?.complete("")
        try { if (agent.isBusy) agent.stop() } catch (_: Exception) {}
        try { if (com.dk.assistant.agent.android.SocialAgent.busy()) com.dk.assistant.agent.android.SocialAgent.control(JSONObject().put("command", "cancel")) } catch (_: Exception) {}
    }

    /** अगले काम से पहले फ़ोन ठीक हालत में: अगला काम स्क्रीन पर चलेगा (AI एजेंट) या पिछला अटका = होम */
    private suspend fun queuePrepare(prev: TaskQueue.Item, next: TaskQueue.Item, prevOk: Boolean) {
        val direct = next.kind == TaskQueue.Kind.WHATSAPP_SEND || LocalRouter.deleteMedia(next.text) != null ||
            LiveFast.pick(next.text, Prefs.wakeWords(svc), svc.currentPackage()) != null
        if (!prevOk || !direct) {
            try { svc.perform(JSONObject().put("type", "home")) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            log("कतार: \"${prev.text.take(20)}\" के बाद होम (${if (!prevOk) "पिछला अटका" else "अगला स्क्रीन पर"})")
            delay(800)
        } else delay(350)
    }

    /** कतार का एक काम - पूरा होने तक रुको (Gemini को 7 सेकंड वाला जवाब नहीं, यह पीछे चलता है) */
    private suspend fun runQueued(item: TaskQueue.Item): String {
        if (item.kind == TaskQueue.Kind.WHATSAPP_SEND) return queueWhatsApp(item)
        // कतार में WhatsApp मैसेज phone_task वाले redirect में न फँसे - सीधे भेजो
        if (com.dk.assistant.agent.social.WaRoute.isWhatsAppSend(item.text)) {
            val (c, m) = TaskQueue.parseWa(item.text)
            if (c.isNotBlank() && m.isNotBlank()) return queueWhatsApp(item.copy(kind = TaskQueue.Kind.WHATSAPP_SEND, contact = c, message = m))
            return "NEEDS USER CHOICE (nothing was done yet): किसे और क्या मैसेज भेजूँ, सर?. Ask the user"
        }
        return phoneTaskOne(item.text, queued = true)
    }

    private suspend fun queueWhatsApp(item: TaskQueue.Item): String {
        val sa = com.dk.assistant.agent.android.SocialAgent
        val args = JSONObject().put("action", "send_message").put("contact", item.contact).put("message", item.message)
        val r = try { sa.start(svc, args, com.dk.assistant.agent.social.Platform.WHATSAPP) } catch (e: CancellationException) { throw e } catch (e: Exception) { return "FAILED: ${e.message}" }
        val o = try { JSONObject(r) } catch (_: Exception) { null }
        if (o == null || o.optString("status") == "error") {
            val code = o?.optString("error").orEmpty(); val msg = o?.optString("message").orEmpty().ifBlank { r.take(120) }
            log("कतार WhatsApp: $code $msg")
            return if (code.contains("contact") || code.contains("ambiguous") || code.contains("not_found") || code.contains("many"))
                "NEEDS USER CHOICE (nothing was done yet): $msg. Ask the user" else "FAILED: $msg"
        }
        val id = o.optString("task_id")
        // भेजने से पहले "हाँ" वाला सवाल SocialAgent के SYSTEM NOTE से Gemini पूछता है - तब तक रुको
        val rep = withTimeoutOrNull(150_000) {
            while (true) {
                val l = sa.lastReport
                if (l != null && (id.isBlank() || l.taskId == id) && l.status.finished) return@withTimeoutOrNull l
                if (taskQueue?.stopped == true) return@withTimeoutOrNull null
                delay(400)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return when {
            rep == null -> "FAILED: timeout"
            rep.status == com.dk.assistant.agent.social.TaskStatus.COMPLETED || rep.status == com.dk.assistant.agent.social.TaskStatus.SUBMITTED_UNVERIFIED -> "DONE: ${rep.message}"
            rep.status == com.dk.assistant.agent.social.TaskStatus.CANCELLED -> "FAILED: cancel - ${rep.message}"
            else -> "FAILED: ${rep.message}"
        }
    }

    /** कतार का एक छोटा सवाल - Gemini पूछे, जवाब फ़ोन ख़ुद ले (अगली बात या phone_task) */
    private suspend fun queueAsk(question: String): String? {
        val since = System.currentTimeMillis()
        val d = CompletableDeferred<String>()
        queueAnswer = d
        visionNote(TaskQueue.askNote(question), evidence = false)
        log("कतार सवाल: $question")
        val ans = withTimeoutOrNull(40_000) {
            while (true) {
                if (d.isCompleted) return@withTimeoutOrNull d.await()
                val at = DKState.lastUserAt
                val u = DKState.lastUser
                // पूरी बात ("…" वाली अधूरी नहीं), सवाल पूछे जाने के बाद
                if (at > since + 1500 && u.isNotBlank() && !u.endsWith("…") && System.currentTimeMillis() - at > 600) return@withTimeoutOrNull u
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        queueAnswer = null
        return ans?.trim()?.takeIf { it.isNotBlank() && !TaskQueue.isStop(it) }
    }

    private suspend fun phoneTaskOne(req: String, queued: Boolean = false): String {
        // v3.59: WhatsApp मैसेज = whatsapp_task (पक्का भेजना + जाँच), phone_task नहीं
        if (com.dk.assistant.agent.social.WaRoute.isWhatsAppSend(req)) {
            log("रूटर: WhatsApp मैसेज -> whatsapp_task")
            return com.dk.assistant.agent.social.WaRoute.redirect(req)
        }
        // v3.67: "आखिरी फोटो/स्क्रीनशॉट डिलीट करो" = सीधे फ़ोन पर; Android खुद Allow पूछता है, फिर जाँच कि सच में ट्रैश में गई
        LocalRouter.deleteMedia(req)?.let { q ->
            DKState.set(Mode.WORKING)
            val t0 = System.currentTimeMillis()
            val r = try { svc.perform(q.action) } catch (e: CancellationException) { throw e } catch (e: Exception) { "ERROR ${e.message}" }
            log("phone_task मिटाना (बिना AI एजेंट) ${System.currentTimeMillis() - t0} ms: ${r.substringBefore(':')}")
            return LiveFast.quickResult(q, r, already = false)
        }
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
        // v3.87: कतार में = पूरा होने तक (90s) रुको, फिर अगला काम; बाद वाला संदेश नहीं
        val finished = withTimeoutOrNull(if (queued) 90_000 else AGENT_WAIT_MS) { job.join() } != null
        if (queued && !finished) job.cancel()
        val reply = DKState.lastReply.takeIf { it != before && it.isNotBlank() }
        if (!finished && !queued) {
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
        if (DKState.micMuted && !silent) return     // v3.59: म्यूट में लिखने वाले को भी आवाज़ नहीं
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

    // ================================================================ v3.66: सिर्फ़ मालिक की आवाज़
    private fun refreshOwnerCfg(now: Long) {
        ownerCfgAt = now
        ownerOn = com.dk.assistant.voice.OwnerVoice.active(svc)
        ownerTh = com.dk.assistant.voice.VoicePrint.threshold(Prefs.ownerVoiceStrict(svc))
        // v3.6: "दूर की आवाज़ अनसुनी" सिर्फ़ जब "सिर्फ़ मेरी आवाज़" चालू हो - वरना धीमी बोली हुई बात भी फेंक देता था
        turnDet.startFloor = LiveHear.farFloor(ownerOn, com.dk.assistant.voice.OwnerVoice.farFloor(svc, btOn))
        val fb = turnDet.farBlocked
        if (fb > 0 && fb % 10 == 1 && fb != farLogged) { farLogged = fb; log("दूर की / धीमी आवाज़ अनसुनी (#$fb)") }
    }
    private var farLogged = 0

    /** पहली ~1 सेकंड आवाज़ का मिलान अलग thread पर - mic और Gemini को भेजना रुकता नहीं */
    private fun checkOwnerAsync() {
        val gen = ownerGen
        val audio = owner.takeAudio()
        val ms = audio.size * 1000 / (IN_RATE * 2)
        try {
            com.dk.assistant.voice.OwnerVoice.exec.execute {
                val t0 = System.currentTimeMillis()
                val sc = try { com.dk.assistant.voice.OwnerVoice.score(svc, audio) } catch (_: Throwable) { null }
                ownerResult = OwnerResult(gen, sc, ms, System.currentTimeMillis() - t0)
            }
        } catch (_: Exception) { owner.onFailed(System.currentTimeMillis()) }
    }

    /** मिलान का नतीजा आया हो तो लागू (mic thread पर) */
    private fun applyOwnerResult(now: Long) {
        val r = ownerResult ?: return
        ownerResult = null
        if (r.gen != ownerGen || !owner.active) return
        val before = owner.verdict
        if (r.score == null) owner.onFailed(now) else owner.onScore(r.score, ownerTh, now, r.ms, atEnd = false)
        if (owner.verdict != before) onOwnerVerdict(now, r.score, r.ms, r.tookMs)
    }

    private fun onOwnerVerdict(now: Long, score: Float?, ms: Int, tookMs: Long) {
        val sc = score?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-"
        when (owner.verdict) {
            com.dk.assistant.voice.OwnerGate.Verdict.OWNER -> {
                if (owner.checks <= 1 && owner.lastScore.isFinite()) DKLog.d("Live: आवाज़ मिलान $sc - मालिक (${ms} ms आवाज़, $tookMs ms)")
                if (muteTurn && !generating) muteTurn = false
                if (ownerHolding) {
                    ownerHolding = false
                    synchronized(sendLock) { ownerHold.forEach { sendAudio(it) } }
                    ownerHold.forEach { capSend(it) }; ownerHold.clear()
                }
            }
            com.dk.assistant.voice.OwnerGate.Verdict.OTHER -> {
                othersIgnored++
                log("किसी और की आवाज़ (मिलान $sc, ${ms} ms) - अनसुनी, जवाब नहीं (#$othersIgnored)")
                val held = ownerHolding
                ownerHolding = false; ownerHold.clear()
                caption?.discard()
                DKState.user("")
                replyWatch.reset()
                // पहले से Gemini को गई (रोकी नहीं गई) आवाज़ का जवाब आएगा - उसे न बजाओ, न काम
                if (!held) { muteTurn = true; muteAt = now }
                if (DKState.mode == Mode.LISTENING && !generating) setMode(Mode.IDLE)
                Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
            }
            else -> {}
        }
    }

    /** बोलना खत्म: फ़ैसला बाकी हो तो अभी (इसी thread पर, ~15-60 ms - जवाब आने से पहले) */
    private fun ownerEnd(now: Long) {
        applyOwnerResult(now)
        if (owner.verdict == com.dk.assistant.voice.OwnerGate.Verdict.PENDING) {
            val before = owner.verdict
            if (owner.needFinalCheck(now)) {
                val audio = owner.takeAudio(); val ms = audio.size * 1000 / (IN_RATE * 2)
                val t0 = System.currentTimeMillis()
                val sc = try { com.dk.assistant.voice.OwnerVoice.score(svc, audio) } catch (_: Throwable) { null }
                if (sc == null) owner.onFailed(now) else owner.onScore(sc, ownerTh, now, ms, atEnd = true)
                ownerGen++; ownerResult = null           // बीच वाला (अगर चल रहा था) अब बेकार
                if (owner.verdict != before) onOwnerVerdict(now, sc, ms, System.currentTimeMillis() - t0)
            } else if (owner.verdict != before) onOwnerVerdict(now, null, owner.audioMs(), 0)
        }
        val isOwner = owner.verdict == com.dk.assistant.voice.OwnerGate.Verdict.OWNER
        if (isOwner) {
            val lv = com.dk.assistant.voice.VoicePrint.speechLevel(owner.rms(), turnDet.noise)
            if (lv > 0) try { com.dk.assistant.voice.OwnerVoice.learnLevel(svc, lv) } catch (_: Exception) {}
        }
        owner.finish()
        if (isOwner) onLocalEndOfSpeech(now)
    }

    /** किसी और की आवाज़ पर Gemini ने फ़ोन का काम माँगा - कुछ मत करो, सिर्फ़ जवाब दो कि अनदेखा करे */
    private fun ignoreTools(calls: JSONArray) {
        val arr = JSONArray()
        for (i in 0 until calls.length()) {
            val fc = calls.optJSONObject(i) ?: continue
            arr.put(JSONObject().put("id", fc.optString("id")).put("name", fc.optString("name"))
                .put("response", JSONObject().put("result", "IGNORED: that voice was not the owner's. Do nothing, say nothing, wait for the owner.")))
        }
        log("किसी और की आवाज़ पर काम माँगा (${calls.length()}) - कुछ नहीं किया")
        try { ws?.tx(JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr)).toString()) } catch (_: Exception) {}
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
        if (!ready || muteTurn) return
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

    // ================================================================ v3.67: DoneGate (सच्चा "हो गया")
    /** जवाब की पहली आवाज़: यूज़र ने काम कहा और अभी तक कोई असली काम नहीं दिखा -> आवाज़ रोको */
    private fun beginActHold() {
        clearAct()
        // यूज़र की बात नहीं (SYSTEM NOTE से बना जवाब, जैसे "संदेश भेज दिया" वाला) = रोकना नहीं
        val u = turnUser.ifBlank { synchronized(turnLock) { userText.toString() } }
        actAfterTool = false
        val now = System.currentTimeMillis()
        val ev = actEvidence || toolJobs.isNotEmpty() || now - lastFastOkAt < DoneGate.FAST_EVIDENCE_MS
        if (ev || !DoneGate.isAction(u)) return
        actCommand = DoneGate.isCommand(u)
        actTextFrom = synchronized(turnLock) { replyText.length }
        actHold = true; actHoldAt = now
    }

    private fun heldActText(): String = synchronized(turnLock) { if (replyText.length > actTextFrom) replyText.substring(actTextFrom) else "" }

    private fun holdAct(pcm: ByteArray) {
        synchronized(actHeld) { actHeld.add(pcm) }
        actHeldBytes += pcm.size
        // इतनी देर कोई दावा नहीं = आम बात (लंबा जवाब/सवाल) - रोकना बंद, बजाओ
        if (actHeldBytes >= OUT_RATE * 2 * DoneGate.holdLimitMs(actAfterTool, actCommand) / 1000 && !HonestGuard.claimsDone(heldActText())) releaseAct("आम बात")
        // v3.7: आम बात 0.9 s से ज़्यादा नहीं रुकती (पहले 2.5-8 s = देरी); हुक्म में ज़रूरी सवाल तुरंत
        else if (TaskFirst.release(actCommand && !actAfterTool, System.currentTimeMillis() - actHoldAt, heldActText())) releaseAct("आम बात / ज़रूरी सवाल")
    }

    private fun releaseAct(why: String) {
        val l = synchronized(actHeld) { actHeld.toList().also { actHeld.clear() } }
        actHold = false; actHeldBytes = 0
        if (connSplit) { actReleased++; pumpSplit(); log("काम की बात: रुका text बोलने भेजा ($why)"); return }
        if (l.isEmpty()) return
        actReleased++
        for (b in l) { turnRecvBytes += b.size; playOut(b) }
        log("काम की बात: रोकी आवाज़ बजाई ($why, ${l.sumOf { it.size } * 1000 / (OUT_RATE * 2)} ms)")
    }

    private fun dropAct(why: String): String {
        val t = heldActText()
        val n = synchronized(actHeld) { actHeld.sumOf { it.size }.also { actHeld.clear() } }
        actHold = false; actHeldBytes = 0
        synchronized(turnLock) { if (replyText.length > actTextFrom) replyText.setLength(actTextFrom) }
        actDropped++
        log("सच-जाँच: काम होने से पहले का \"हो गया\" नहीं बजाया ($why, ${n * 1000 / (OUT_RATE * 2)} ms छोड़े)")
        return t
    }

    private fun clearAct() { synchronized(actHeld) { actHeld.clear() }; actHold = false; actHeldBytes = 0; actPending = false; actAfterTool = false; actCommand = false }

    /** बारी खत्म और आवाज़ अब भी रुकी है: tool नहीं आया */
    private fun endActHold() {
        val fj = fastJob
        if (fj?.isActive == true) {
            // फ़ोन वही काम अभी कर रहा है (Gemini से पहले वाला रास्ता) - उसका नतीजा आने तक रुको, फिर तय करो
            val keep = synchronized(actHeld) { actHeld.toList().also { actHeld.clear() } }
            val said = heldActText()
            actHold = false; actHeldBytes = 0
            if (connSplit) synchronized(turnLock) { splitFedTo = replyText.length }    // यह text फ़ैसले तक रुका रहे
            svc.scope.launch {
                withTimeoutOrNull(8_000) { fj.join() }
                val ok = System.currentTimeMillis() - lastFastOkAt < DoneGate.FAST_EVIDENCE_MS
                if (ok || !HonestGuard.claimsDone(said)) {
                    actReleased++; for (b in keep) { turnRecvBytes += b.size; playOut(b) }
                    if (connSplit && said.isNotBlank()) { pipeline.feed(said); pipeline.endTurn() }
                    log("काम की बात: फ़ोन ने काम कर दिया - रोकी आवाज़ बजाई")
                } else {
                    actDropped++; log("सच-जाँच: फ़ोन का काम नहीं हुआ - \"हो गया\" नहीं बजाया")
                    visionNote(DoneGate.dropNote(said), evidence = false)
                }
            }
            return
        }
        val said = heldActText()
        val after = actAfterTool
        if (DoneGate.decide(said, actEvidence && !after) == DoneGate.Verdict.DROP) {
            dropAct(if (after) "काम अभी पूरा नहीं, फिर भी \"हो गया\"" else "बारी खत्म, कोई काम नहीं चला")
            lastGuardAt = System.currentTimeMillis()          // HonestGuard दोबारा वही सुधार न भेजे
            visionNote(if (after) DoneGate.pendingNote(said) else DoneGate.dropNote(said), evidence = false)
        } else releaseAct("बारी खत्म, कोई दावा नहीं")
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
            if (LiveFast.ok(q.action.optString("type"), r) && !r.startsWith("VIDEO_OPENED")) lastFastOkAt = System.currentTimeMillis()
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

    /** DK ने अभी तक जो बोला/बोल रहा है (गूँज की जाँच के लिए) - बारी ख़त्म हो चुकी हो तब भी पिछला जवाब */
    private fun dkHeardNow(): String = synchronized(turnLock) { replyText.toString() + " " + (pendingReply ?: "") } + " " + lastDkLine

    /**
     * v3.5+: HOLD ख़त्म - यूज़र ने अपना अगला सवाल पूरा बोल दिया. गूँज/शोर नहीं तो कतार में; जवाब पूरा होते ही [flushAsks].
     * Gemini को अभी कुछ नहीं गया था, इसलिए DK का चलता जवाब पूरा बजता है.
     */
    private fun finishHold(now: Long) {
        val t = (caption?.text().orEmpty().ifBlank { holdCap }).trim().trimEnd('…').trim()
        caption?.discard()
        synchronized(bargeBuf) { bargeBuf.clear() }
        synchronized(sendLock) { bargeGate.reset() }
        barging = false; bargeDet.reset(); holdCap = ""; holdCapAt = 0L; holdLoudAt = 0L; holdFinal = false
        track?.let { try { it.setVolume(1f) } catch (_: Exception) {} }
        when {
            !BargeGate.realWords(t) || BargeGate.isEcho(t, dkHeardNow()) || BargeGate.isRepeat(t, turnUser) -> {
                log("बीच की आवाज़ (\"${t.take(30)}\") DK की गूँज / पुरानी बात थी - छोड़ी"); DKState.hint("")
            }
            LiveStop.isStop(t) -> { askQueue.clear(); DKState.hint(""); bargeIn("रुको: ${t.take(30)}") }
            askQueue.add(t) -> {
                askAt = now
                log("बीच में पूछा सवाल याद रखा (\"${t.take(40)}\") - जवाब पूरा होते ही बताऊँगा (कतार: ${askQueue.size()})")
                DKState.hint(ASK_HINT)
            }
        }
    }

    /** जवाब पूरा हुआ - बीच में पूछा सवाल अब Gemini को (यूज़र की नई बारी की तरह, "आप:" लाइन भी) */
    private fun flushAsks(now: Long) {
        val raw = askQueue.drain() ?: return
        // v4.1: "सिर्फ़ DK बोलने पर सुनो" - बीच में पूछी बात में DK से शुरू होने वाले वाक्य ही (DK हटाकर)
        val t = if (!dkOnlyNow) raw else DkOnly.filter(raw)?.takeIf { it.isNotBlank() } ?: run {
            log("बीच की बात DK से शुरू नहीं हुई - छोड़ी (\"${raw.take(40)}\")"); return
        }
        try {
            startTurn(); turnUser = t; userDone = false
            caption?.discard()
            DKState.hint(""); DKState.user("$t…")
            setMode(Mode.THINKING)
            eosAt = now; firstAudioAt = 0; trace.reset(); trace.speechEndAt = now; trace.endpointAt = now
            beginTimeline(now, now, "बीच में पूछा सवाल (जवाब के बाद)")
            ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
            lastAct = now
            log("बीच में पूछा सवाल अब भेजा: ${t.take(60)}")
        } catch (_: Exception) {}
    }

    /** Dheere bolne par bhi saaf awaaz Gemini tak (v3.7) */
    private val boost = MicBoost(maxGain = LiveHear.BOOST_MAX, noiseCap = LiveHear.BOOST_NOISE_CAP)   // v4.2: और धीमी आवाज़ भी

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
                // v3.59: म्यूट बटन = माइक सच में बंद (Android का हरा माइक-निशान भी हटे)।
                // जड़: पहले माइक खुला रहता था और सिर्फ़ Gemini को खाली आवाज़ जाती थी - पर caption वाले को असली
                // आवाज़ जाती थी, बीच में टोकना (barge) भी चलता था, और फ़ोन "माइक चालू" ही दिखाता था।
                if (DKState.micMuted) {
                    try { r.stop() } catch (_: Exception) {}
                    try { r.release() } catch (_: Exception) {}
                    log("माइक म्यूट: माइक छोड़ दिया - अनम्यूट तक कुछ नहीं सुनूँगा")
                    synchronized(sendLock) { try { ws?.tx("{\"realtimeInput\":{\"audioStreamEnd\":true}}") } catch (_: Exception) {} }
                    try { caption?.endOfSpeech() } catch (_: Exception) {}
                    synchronized(bargeBuf) { bargeBuf.clear() }
                    preroll.clear(); turnDet.reset(); DKState.level = 0f
                    while (isActive && !stopped && DKState.micMuted) delay(150)
                    if (!isActive || stopped) break
                    r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला (अनम्यूट के बाद)", recoverable = true)
                    r.startRecording(); last = 0L; errRun = 0
                    quietSince = System.currentTimeMillis()
                    log("माइक अनम्यूट: फिर सुन रहा हूँ")
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
                if (playing) lastPlayingAt = now
                // v3.5+: HOLD (यूज़र का अगला सवाल सुन रहे) - DK का जवाब ख़त्म हो जाए तब भी सवाल पूरा होने तक इसी रास्ते
                if (playing || bargeGate.state == BargeGate.State.HOLD) {
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
                                track?.let { try { it.setVolume(SpeakerBoost.SUSPECT_VOLUME) } catch (_: Exception) {} }
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
                        BargeGate.State.HOLD -> {
                            // सिर्फ़ caption सुने (Gemini को खामोशी - जवाब चलता रहे). "रुको" सुनते ही onCaption रोकेगा
                            synchronized(bargeBuf) { bargeBuf.addLast(chunk); while (bargeBuf.size > 50) bargeBuf.removeFirst() }
                            capSend(chunk)
                            sendAudio(zeros, silent = true); gatedChunks++
                            if (rms > maxOf(bargeDet.ambient * 2.5, bargeDet.echoLevel() * 1.5, 800.0)) holdLoudAt = now
                            val capQuiet = now - holdCapAt; val loudQuiet = now - holdLoudAt
                            val done = (holdFinal && capQuiet >= HOLD_FINAL_MS && loudQuiet >= HOLD_FINAL_MS) ||
                                (capQuiet >= HOLD_END_MS && loudQuiet >= HOLD_QUIET_MS) || capQuiet >= HOLD_STALE_MS ||
                                now - bargeGate.since >= HOLD_MAX_MS
                            if (done) finishHold(now)
                        }
                    }
                } else {
                    // ---- DK chup: phone par hi "bolna shuru / khatam" + SHOR-GATE:
                    // Gemini/caption ko sirf user ki awaaz (320ms pehle se); baaki samay khaamoshi.
                    // (Peeche ka lagatar shor bhejne par server "user abhi bol raha" maan kar jawab rokta tha.)
                    if (barging) bargeUntil = now + 1500
                    barging = false; bargeDet.reset(); bargeDet.ambient = 0.0
                    if (bargeGate.state != BargeGate.State.IDLE) { bargeGate.reset(); track?.let { try { it.setVolume(1f) } catch (_: Exception) {} } }
                    // v3.5+: जवाब पूरा, DK चुप -> बीच में पूछा सवाल अब (उसका भी जवाब)
                    if (!askQueue.isEmpty()) {
                        if (now - askAt > ASK_KEEP_MS) { askQueue.clear(); log("बीच में पूछा सवाल बहुत पुराना हो गया - छोड़ा") }
                        else if (ready && ws != null && !turnActive && !muteTurn && AskQueue.readyToSend(isPlaying(), generating,
                                toolJobs.isNotEmpty(), turnDet.inSpeech, false, now - lastPlayingAt)) flushAsks(now)
                    }
                    if (DKState.mode == Mode.LISTENING) DKState.level = (rms / 3500.0).toFloat().coerceIn(0f, 1f)
                    if (now - ownerCfgAt > 3_000) refreshOwnerCfg(now)
                    var ev = turnDet.feed(rms, now)
                    // v3.66 (तेज़ी): वाक्य साफ़ पूरा + 0.36s चुप + caption 0.2s से नहीं बदला = अभी "बात खत्म" (0.55s इंतज़ार नहीं)
                    if (ev == TurnDetector.Event.NONE && turnDet.inSpeech && turnDet.silentMs > 0 && !guardHeld && captionOn()) {
                        val cap = caption?.text().orEmpty()
                        if (cap != capSeen) { capSeen = cap; capSeenAt = now }
                        if (turnDet.silentMs >= TurnEnd.EARLY_END_MS && turnDet.speechMsNow < TurnEnd.LONG_SPEECH_MS &&
                            now - capSeenAt >= 200 && TurnEnd.complete(cap) && turnDet.forceEnd()) {
                            ev = TurnDetector.Event.SPEECH_END; earlyEnds++
                            if (earlyEnds <= 3 || earlyEnds % 20 == 0) log("वाक्य पूरा लगा - ${TurnEnd.EARLY_END_MS} ms चुप्पी पर ही बात खत्म (#$earlyEnds)")
                        }
                    }
                    if (turnDet.inSpeech || generating || (turnActive && now - eosAt < 8_000)) quietSince = now
                    // v3.53: फ़ोन पर दूसरे ऐप की आवाज़ (वीडियो/गाना) - हर 300ms देखो (DK की अपनी आवाज़ थमने के बाद ही भरोसा)
                    if (now - mediaAt > 300) { mediaAt = now; mediaOn = Ducker.musicActive(svc) }
                    // v4.1: "सिर्फ़ DK बोलने पर सुनो" चालू = हर बात रोको, caption में शुरुआत में DK हो तभी Gemini को
                    // (घंटी / कॉल के ठीक बाद के कुछ सेकंड में नहीं - तब कॉल की कमांड बिना DK)
                    if (now - dkOnlyAt > 3_000) { dkOnlyAt = now; dkOnlyNow = Prefs.dkOnly(svc) }
                    val dkGate = dkOnlyNow && caption?.usable == true && !CallCmd.windowOpen(now)
                    guardOn = !svc.callRinging() && !callAudioOn && (dkGate || PhoneAudioGuard.active(mediaOn, now - playEnd))
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
                        if (guardOk && dkOnlyNow && ownerOn && !ownerSaysOwner(guardBuf)) {
                            // v4.1: पहले "सिर्फ़ मेरी आवाज़" - DK बोला पर आवाज़ मालिक की नहीं = अनसुनी
                            othersIgnored++
                            log("DK सुना पर आवाज़ मालिक की नहीं - अनसुनी (#$othersIgnored)")
                            guardHeld = false; guardOk = false; guardEndAt = 0L; guardBuf.clear(); caption?.discard(); DKState.user("")
                            sendAudio(zeros, silent = true); gatedChunks++
                            continue
                        }
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
                    if (ev == TurnDetector.Event.SPEECH_START) {
                        // v3.66: नई बात - मालिक की पहचान शुरू. DK सोच/काम कर रहा हो तो पहचान तक आवाज़ रोको
                        ownerGen++; ownerResult = null; ownerHold.clear()
                        if (ownerOn) owner.start(preroll) else owner.finish()
                        ownerHolding = ownerOn && (generating || toolJobs.isNotEmpty() || (turnActive && now - eosAt < 8_000))
                        if (ownerHolding) { preroll.forEach { ownerHold.addLast(it) }; preroll.clear() }
                        else { preroll.forEach { sendAudio(it); capSend(it) }; preroll.clear() }
                    }
                    applyOwnerResult(now)
                    val other = owner.active && owner.verdict == com.dk.assistant.voice.OwnerGate.Verdict.OTHER
                    // bolte samay + baat khatam hone tak (aakhri 500ms chup bhi) asli awaaz; warna khaamoshi
                    // (khaamoshi 1s tak jaati hai, phir audioStreamEnd aur upload band - SilenceStreamGate)
                    if ((turnDet.inSpeech || ev == TurnDetector.Event.SPEECH_END) && !other) {
                        if (ownerHolding) {
                            ownerHold.addLast(chunk); while (ownerHold.size > 75) ownerHold.removeFirst()
                            sendAudio(zeros, silent = true); capSend(zeros, silent = true)
                        } else { sendAudio(chunk); capSend(chunk) }
                        if (owner.active && turnDet.inSpeech && owner.add(chunk, rms)) checkOwnerAsync()
                    } else if (other && (turnDet.inSpeech || ev == TurnDetector.Event.SPEECH_END)) {
                        // किसी और की आवाज़: बाकी बात Gemini/लिखने वाले को नहीं
                        sendAudio(zeros, silent = true); capSend(zeros, silent = true); gatedChunks++
                    } else {
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
                        TurnDetector.Event.SPEECH_END -> {
                            if (owner.active) ownerEnd(now)
                            else onLocalEndOfSpeech(now)
                        }
                        else -> {}
                    }
                    if (muteTurn && now - muteAt > 20_000 && !generating) muteTurn = false
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
        com.dk.assistant.voice.engine.VoiceTune.load(svc)
        var tuneVer = com.dk.assistant.voice.engine.VoiceTune.apply(t, -1)   // v3.80: गति / पिच (सेटिंग्स -> आवाज़)
        // v3.6: speaker पर DK की आवाज़ फ़ोन की बाकी आवाज़ से धीमी थी - थोड़ा boost (Bluetooth पर नहीं)
        spkBoost = SpeakerBoost.attach(t.audioSessionId, btOn)
        t.play()
        track = t
        log((if (btOn) "आवाज़: Bluetooth headset" else "स्पीकर" + (if (spkBoost != null) " (+${SpeakerBoost.GAIN_MB / 100} dB)" else "")) + ": $OUT_RATE Hz PCM16 mono stream • " + com.dk.assistant.voice.engine.VoiceTune.label())
        playThread = Thread {
            var speakingSet = false
            while (!Thread.currentThread().isInterrupted) {
                val b = try { playQ.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                val tr = track ?: break
                if (b == null) {
                    if (speakingSet && !isPlaying()) {
                        speakingSet = false
                        if (!turnActive && DKState.mode == Mode.SPEAKING) idleMode()
                    }
                    continue
                }
                val g = gen
                // pehla tukda: haalat PEHLE "Speaking" (UI turant), awaaz likhne ke baad baaki kaam
                val first = !speakingSet || DKState.mode != Mode.SPEAKING
                if (first) { speakingSet = true; setMode(Mode.SPEAKING) }
                val now = System.currentTimeMillis()
                // v3.80: बातचीत के बीच गति / पिच बदली -> अगले टुकड़े से ही
                tuneVer = com.dk.assistant.voice.engine.VoiceTune.apply(tr, tuneVer)
                com.dk.assistant.voice.engine.VoiceLevel.schedule(b, maxOf(playEnd, now))     // v3.68: असली ज़ोर से लिप-सिंक
                playEnd = maxOf(playEnd, now) + com.dk.assistant.voice.engine.VoiceTune.playMs(b.size, OUT_RATE)
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
                        if (!connSplit && lastLatency in 0..60_000) com.dk.assistant.voice.engine.VoiceEngines.recordLatency(svc,
                            com.dk.assistant.voice.engine.VoiceEngines.LIVE_NATIVE, lastLatency)
                        lastBreakdown = "$sm • सबसे बड़ा हिस्सा: ${trace.bottleneck()}"
                        log("देरी के हिस्से: $lastBreakdown")
                    }
                    if (!connSplit) LatencyBoard.record("Gemini Live (सीधी आवाज़)", trace.speechEndAt, trace.firstChunkAt, trace.firstChunkAt, trace.firstPlayAt)
                        ?.let { log("देरी: " + LatencyBoard.line(it)) }
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
        pipeStop()                                   // v3.68: आवाज़ इंजन की कतार + चलती आवाज़ भी तुरंत
        com.dk.assistant.voice.engine.VoiceLevel.clear()
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
        pipeStop(); com.dk.assistant.voice.engine.VoiceLevel.clear()
        track?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        track = null
        SpeakerBoost.release(spkBoost); spkBoost = null
    }
    private var spkBoost: android.media.audiofx.LoudnessEnhancer? = null
}
