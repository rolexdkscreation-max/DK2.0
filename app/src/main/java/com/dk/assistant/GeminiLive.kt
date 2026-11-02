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
 *
 * फ़ाइलें (नवंबर 2026 में बँटवारा, व्यवहार वही): यहाँ सारी state + run() + बाहर से बुलाए जाने वाले functions।
 * बाकी हिस्से इसी class के extension functions हैं, इन फ़ाइलों में:
 *  - GeminiLiveSetup.kt  : setup message, मॉडल की सूची
 *  - GeminiLiveRace.kt   : (मॉडल × key) की दौड़, WebSocket connect
 *  - GeminiLiveServer.kt : server के message (आवाज़, caption, tool call, interrupted)
 *  - GeminiLiveTools.kt  : tools (खोज, मौसम/जगह, notification, एजेंट, call)
 *  - GeminiLiveQueue.kt  : फ़ोन के काम + कामों की कतार
 *  - GeminiLiveMic.kt    : माइक खोलना + सुनने का loop
 *  - GeminiLivePlayer.kt : DK की आवाज़ बजाना
 *  - GeminiLiveRecover.kt: टूटने/अटकने पर वापस आना
 *  - GeminiLiveTurn.kt   : बारी शुरू/खत्म, पहला वाक्य, दोबारा भेजना, जवाब के टुकड़े
 *  - GeminiLiveTurnEnd.kt: बोलना खत्म होने पर (जवाब न आए / अटके / तेज़ जवाब / टोकना)
 *  - GeminiLiveCaption.kt: आपकी बात का लिखा रूप (caption)
 *  - GeminiLiveOwner.kt  : मालिक की / पास की आवाज़ की जाँच
 *  - GeminiLiveActHold.kt: फ़ोन-काम के समय DK की आवाज़ रोकना/छोड़ना
 *  - GeminiLiveChecks.kt : वादा/एजेंट जाँच, text में आया tool call, आवाज़ भेजना, idle
 * नया हिस्सा बने तो test की LiveSrc.PARTS में भी जोड़ें (source-check tests सारे हिस्से पढ़ते हैं)।
 */
class GeminiLive(internal val svc: DKAccessibilityService, internal val agent: AgentRunner) {

    companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        internal const val CHUNK_MS = 40
        /** Server VAD: itni der chup = baat khatam (Google: 500-800ms; kam = baat beech me kat-ti hai) */
        const val SERVER_SILENCE_MS = 500   // v5.1 (मालिक: "बोलना बंद होते ही जवाब"): 800 -> 500 (Google की सलाह 500-800 का सबसे तेज़). पहले v3.46: 550 -> 800. छोटी बात पर फ़ोन खुद 550ms पर "खत्म" भेजता है; यह सिर्फ़ तब जब वाक्य अधूरा लगे ("और…", "तो…")
        /** Phone ka VAD: itni der chup = turant audioStreamEnd (server ka intezaar nahi) */
        const val CLIENT_END_MS = 500     // v5.1: 550 -> 500 (Google: फ़ोन का VAD कम से कम 500ms; पूरे वाक्य पर TurnEnd.EARLY_END_MS पहले जैसा)
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
        internal const val PARK_MS = 5 * 60_000L
        /** DK bolte samay (gate mode) user ki awaaz pakdi -> usse pehle ka itna hissa bhi bhejo (pehla shabd na kate) */
        internal const val PREROLL_CHUNKS = 8          // 8 x 40ms = 320ms
        /** Itni der "Thinking" aur kuch na aaye -> wapas "Listening" (purani haalat atki na rahe) */
        private const val THINK_STUCK_MS = 12_000L
        /** v3.80: tool का नतीजा भेजने के बाद इतनी देर चुप = याद दिलाओ */
        internal const val TOOL_ANSWER_MS = 8_000L
        /** v4.1: जवाब में लिखी हुई (असली नहीं) tool call का नकली id - इसका नतीजा toolResponse में नहीं जाता */
        const val LEAK_ID = "dk-leak-"
        const val LEAK_DEDUP_MS = 20_000L
        /** v3.80: इनका नतीजा = यूज़र को जवाब बोलना ज़रूरी */
        internal val ANSWER_TOOLS = setOf("web_search", "deep_think", "current_time", "local_info", "connector_task", "task_status", "dk_features")
        /**
         * Jawab "ban raha" (generating) tha par itni der server se ek bhi sandesh nahi aur awaaz bhi nahi baj rahi =
         * turnComplete kho gaya / stream atak gaya. Tab turn band (stream ya connection nahi kaata - aage kuch aaye to
         * naya turn shuru hota hai). Audio stream me tukde lagatar aate hain, tool ka samay alag (toolJobs) - 15s kaafi hai.
         */
        private const val GEN_STALL_MS = 15_000L
        /** AudioTrack me likhi par abhi na baji awaaz (~150ms) */
        internal const val LEAD_BYTES = OUT_RATE * 2 * 150 / 1000
        internal val WS = Regex("\\s+")
        internal const val CHUNK_SAMPLES = IN_RATE * CHUNK_MS / 1000      // 640 samples = 1280 bytes
        internal const val LIVE_HOST = "generativelanguage.googleapis.com"
        internal const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val CONST_SETUP_TIMEOUT_MS = 15_000L
        /** Docs (Oct 2026): gemini-3.8-live = default; 3.1 flash live = purana preview */
        /** v3.41: phone_task ka lamba kaam (AgentRunner) - itni der baad Gemini ko "chal raha hai" */
        const val AGENT_WAIT_MS = 7_000L
        val FALLBACK_MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-2.5-flash-native-audio-preview-09-2025")

        fun key(c: android.content.Context) = Prefs.ttsGeminiKey(c)
        /** v3.68: बँटवारे में Live का लिखा text सीधे बोला जाता है - बोलने लायक लिखो */
        internal const val SPLIT_NOTE = "\n\nOUTPUT NOTE: Your reply text is spoken aloud by a separate voice engine, sentence by sentence, " +
            "as soon as you write it. Write exactly what should be heard: natural spoken Hindi (Devanagari), short sentences, " +
            "commas where a speaker would pause. No markdown, lists, emojis, headings or stage directions."

        /** v3.71: पहली आवाज़ जल्दी - पहला वाक्य बहुत छोटा (वही सबसे पहले बनकर बजता है), बाकी भी छोटे */
        internal const val FAST_START = "\n\nSPEED NOTE: Always begin your reply with one very short sentence of 4-6 words " +
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
    /** Live नहीं चल सकता. [kind] = साफ़ हिंदी कारण (स्क्रीन/बैकअप मोड में) - LiveFailWhy */
    class Fatal(msg: String, val recoverable: Boolean = false, val kind: LiveFailWhy.Kind? = null) : Exception(msg)

    /** v5.1: एक ही साझा client (connection pool) - स्क्रीन चालू होते ही LiveNet इसका host पहले से गरम रखता है */
    internal val http: OkHttpClient = LiveNet.http

    private fun reconnect() {
        svc.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (stopped) return@launch
                log("धीमे नेटवर्क पर ऑटो-रीकनेक्ट और रिट्राई शुरू हो रहा है...")
                ws?.cancel()
                ws = null
                kotlinx.coroutines.delay(1000)
                if (!stopped) {
                    reconnects++
                    val currentKey = key(svc)
                    val currentModel = if (model.isNotBlank()) model else FALLBACK_MODELS.first()
                    val closed = kotlinx.coroutines.CompletableDeferred<Pair<Int, String>>()
                    val setup = kotlinx.coroutines.CompletableDeferred<Unit>()
                    connect(currentKey, currentModel, closed, setup)
                }
            } catch (e: Exception) {
                log("रीकनेक्शन और रिट्राई विफल: ${e.message}")
            }
        }
    }

    @Volatile internal var ws: WebSocket? = null
    @Volatile internal var ready = false
    /** नवंबर 2026: इस connection के जुड़ने का समय (0 = नहीं जुड़ा) - Inspector सिर्फ़ टिके connection में बोले */
    @Volatile internal var upSince = 0L
    fun stableFor(ms: Long): Boolean = ready && upSince > 0 && System.currentTimeMillis() - upSince >= ms
    @Volatile internal var stopped = true
    /** v4.6: Live अभी सच में चल रहा है (run() के अंदर) - ऐप खुलने / नेट लौटने पर "तुरंत जोड़ो" के लिए */
    val running: Boolean get() = !stopped
    /** v4.6: अटके सत्र कितनी बार पकड़े (बात भेजी पर server चुप) */
    @Volatile private var deadSessions = 0

    /** v3.31: DK कंप्यूटर / कोडिंग टीम का काम सच में खत्म हो -> DK लाइव को असली नतीजा (वही बोले). नकली progress नहीं */
    private val statusListener = TaskStatus.Listener { job, ended ->
        if (!ended || stopped) return@Listener
        svc.scope.launch {
            // DK बोल रहा हो तो बीच में नहीं - चुप होने तक (10s तक) रुको
            // tool का जवाब बाकी हो तो भी नहीं (Gemini tool के जवाब का इंतज़ार कर रहा हो तब text = 1007 का ख़तरा)
            if (!waitQuiet(45_000)) { log("Gemini चुप नहीं हुआ (45s) - बीच बातचीत text नहीं भेजा"); return@launch }
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
    @Volatile internal var goAway = false
    @Volatile private var goAwayDrop = false          // server ne khud naya connection maanga (galti nahi)
    internal var handle: String? = null
    /** नवंबर 2026: बीच बातचीत का बचाव-text (caption/अटका/टूटने से पहले की बात/बीच का सवाल) - डिफ़ॉल्ट बंद (1011 की जड़) */
    @Volatile internal var textRescue = false
    /** goAway पर पहले से तैयार connection किस handle से बना (useParked) */
    @Volatile internal var parkHandle: String? = null
    @Volatile private var weakNetTold = false
    @Volatile private var prevAlive = false
    /** run() की keys/models - goAway पर पीछे से नया connection तैयार करने के लिए */
    @Volatile internal var curKeys: List<String> = emptyList()
    @Volatile internal var curModels: List<String> = emptyList()
    internal var useSearch = true

    // ---- aankde (debug)
    @Volatile internal var sent = 0L
    @Volatile internal var sentBytes = 0L
    @Volatile internal var dropped = 0L
    @Volatile internal var gaps = 0L
    @Volatile internal var readErr = 0L
    @Volatile internal var recvAudio = 0L
    @Volatile private var reconnects = 0
    @Volatile internal var lastLatency = -1L
    /** v3.41: aakhri tool call kab aaya - tool wali baari ka samay model ki raftaar me mat gino */
    @Volatile internal var lastToolAt = 0L
    @Volatile internal var model = ""

    // ---- baari (turn)
    internal val userText = StringBuilder()
    internal val replyText = StringBuilder()
    @Volatile internal var turnActive = false
    // v4.1: Live ने tool call की जगह "phone_task{…}<ctrl43>" लिख/बोल दिया -> यह फ़िल्टर उसे छिपाता है और फ़ोन तुरंत असली काम करता है
    internal var leakFilter = newLeakFilter()
    /** इस बारी में लिखी हुई call दिखी = Live की अपनी (गड़बड़) आवाज़ अब नहीं बजती */
    @Volatile internal var leakMute = false
    @Volatile internal var lastLeakKey = ""
    @Volatile internal var lastLeakAt = 0L
    internal val turnLock = Any()

    // ---- turant text (v3.0)
    /** Alag tez caption stream - "आप:" me bolte-bolte shabd */
    @Volatile internal var caption: LiveCaption? = null
    /** DK ne jawab shuru kiya us waqt tak user ne jo bola (caption se) */
    @Volatile internal var turnUser = ""
    /** Is baari ki user line chat me pakki ho chuki (der se aaya text ab us line ko theek karega) */
    @Volatile internal var userDone = false
    /** Is baari ka jawab (awaaz) shuru ho chuka - ginti dobara shuru nahi karni */
    @Volatile internal var replyStarted = false
    /** Gemini abhi jawab bana raha hai (turnComplete nahi aaya) */
    @Volatile internal var generating = false
    /** DK bol raha hai aur user sach me bol raha hai (mic asli awaaz bhej raha hai) */
    @Volatile internal var barging = false
    /** DK ka text uski awaaz ke saath: kitni awaaz aayi / kitni baj chuki */
    @Volatile internal var turnRecvBytes = 0L
    @Volatile internal var turnPlayedBytes = 0L
    /** Jawab poora aa gaya par awaaz abhi baj rahi hai */
    @Volatile internal var pendingReply: String? = null
    /** v3.9: is baari Google Search hua to uske sources (groundingMetadata) - sirf screen par, bole nahi jaate */
    internal var turnSources: List<Pair<String, String>> = emptyList()
    internal var pendingSources: List<Pair<String, String>> = emptyList()
    /** deep_think tool ne search kiya ho to uske sources (agle jawab ke saath) */
    @Volatile internal var toolSources: List<Pair<String, String>> = emptyList()
    @Volatile internal var shownReply = ""
    @Volatile internal var eosAt = 0L
    @Volatile internal var firstAudioAt = 0L

    // ---- v3.8: turant haalat (listening/thinking/speaking) + barge-in + deri ke hisse
    // v3.6: धीमी आवाज़ भी "बोलना" (owner: "ज़ोर से बोलना पड़ता है") - दरवाज़ा 2.5x -> 2.0x शोर, 300 -> 220 RMS
    internal val turnDet = TurnDetector(CHUNK_MS, endSilenceMs = CLIENT_END_MS, startFactor = LiveHear.START_FACTOR,
        startMin = LiveHear.START_MIN, loudNoiseFactor = LiveHear.LOUD_FACTOR,
        longSpeechMs = TurnEnd.LONG_SPEECH_MS, longEndSilenceMs = TurnEnd.LONG_END_SILENCE_MS, steadyResetMs = 6_000,
        keepFactor = LiveHear.KEEP_FACTOR, keepMin = LiveHear.KEEP_MIN, tailFactor = LiveHear.TAIL_FACTOR)
    /** v4.7: आपकी पास वाली आवाज़ (अपने आप सीखी) - दूर की आवाज़ अनसुनी */
    internal val near = NearVoice().also { try { it.load(Prefs.nearLevels(svc)) } catch (_: Exception) {} }
    internal val nearRms = ArrayList<Double>()
    /** वही पहचानी बात router में बार-बार न जाए (हर छोटी चुप्पी पर पूरा caption दोबारा जाँचा जाता था) */
    internal val routeDedupe = RouteDedupe()
    // v4.7 (मालिक: "जवाब देते समय बोलूँ तो रुककर सुने"): 200ms / 900 RMS (पहले 240ms / 1200) - पक्का caption के शब्दों से
    internal val bargeDet = BargeDetector(needChunks = 5, minRms = 900.0)
    internal val trace = LatencyTrace()
    /** Har baari ki poori samay-rekha (end of speech -> upload -> pehla event -> audio -> awaaz -> turn khatam) */
    internal val timeline = TurnTimeline()
    @Volatile internal var waitingLogged = false
    @Volatile internal var noReplyTurns = 0
    /** Khaamoshi me upload band (Live socket / caption socket alag-alag) */
    internal val liveGate = SilenceStreamGate(CHUNK_MS)
    internal val capGate = SilenceStreamGate(CHUNK_MS)
    @Volatile internal var silenceSkipped = 0L
    /**
     * Audio bhejne ka ek hi kram: mic thread, caption thread (barge pakka) aur OkHttp thread (judte samay ki
     * awaaz) teeno bhejte hain - bina lock ke purane tukde naye ke BAAD pahunch sakte the (ulti-pulti awaaz).
     */
    internal val sendLock = Any()
    /** v3.39: टूटने से पहले भेजे आख़िरी संदेश (किस्म + आकार) - 1007 की असली वजह लॉग में */
    internal val wire = SentTrace()
    private val drop1007 = Drop1007()
    /** "बात खत्म" (audioStreamEnd) एक बात पर एक ही बार */
    internal val endGate = StreamEndGate()
    /** आख़िरी बार यूज़र बोला / DK बोला - पीछे की आवाज़ कब वापस (DuckWatch) */
    @Volatile internal var quietSince = 0L
    @Volatile internal var duckFreedAt = 0L
    /** Bluetooth जुड़ा = कॉल वाला तरीका (BtAudio); हटा = speaker */
    @Volatile internal var btOn = false
    @Volatile internal var routeChanged = false
    /** DK की अपनी आवाज़ कितनी तेज़ बज रही है (आख़िरी ~400ms) - गूँज पहचानने के लिए */
    internal val outLevels = ArrayDeque<Pair<Long, Double>>()
    internal fun outLevel(now: Long): Double = synchronized(outLevels) {
        while (outLevels.isNotEmpty() && now - outLevels.first().first > 400) outLevels.removeFirst()
        outLevels.maxOfOrNull { it.second } ?: 0.0
    }
    /** Live socket पर भेजो + दर्ज करो (टूटने पर लॉग) */
    internal fun WebSocket.tx(m: String): Boolean { wire.sent(m); return send(m) }
    /** Server ka aakhri sandesh (koi bhi) - atka stream pakadne ke liye */
    @Volatile internal var lastServerAt = 0L
    /** नवंबर 2026: server का आखिरी कोई भी संदेश (VAD / resumption भी) - अटका session पकड़ने के लिए */
    @Volatile internal var lastAnyServerAt = 0L
    /** नवंबर 2026: अभी की बात की आवाज़ (session अटके तो नए connection पर यही दोबारा - text नहीं) */
    internal val utt = LiveRecover.Utterance(CHUNK_MS)
    /** नए connection पर दोबारा भेजनी है (null = कुछ नहीं) */
    @Volatile internal var replayAudio: List<ByteArray>? = null
    @Volatile internal var replayAt = 0L
    internal val zombieSwaps = LiveRecover.SwapLimit()
    @Volatile internal var zombieN = 0
    @Volatile internal var replayedN = 0
    /** गड़बड़ी (टूटा / अटका) कब और क्या - अगला जवाब आने पर "संभल गया" */
    @Volatile internal var recoverWhy: String? = null
    @Volatile internal var recoverAt = 0L
    /** यूज़र को सच में रुकावट दिखी (12s से ज़्यादा टूटा) */
    @Volatile internal var slowReconnects = 0
    /** इस बात (endpoint) पर अटका-जाँच हो चुकी */
    @Volatile internal var zombieFor = 0L
    @Volatile private var genStalls = 0
    @Volatile internal var handlerErrors = 0
    /** Connection tootne se pehle ki baat jiska jawab shuru nahi hua tha - judte hi ek baar text me */
    @Volatile internal var resendText: String? = null
    @Volatile internal var resentN = 0
    /** v3.9.1: bola par Gemini ne suna hi nahi -> caption ka text seedha bhejo */
    // v3.41: suna par jawab shuru nahi = 4.5s ki jagah 3.5s me bachao (atka sawaal jaldi dobara)
    // v3.46: 2.5s -> 1.8s (सर्वर ने सुना हो तो ~1s में text भेज देता है); 0.4s -> 0.25s (छोटे आदेश "हाँ", "रुको" भी बचें - caption के शब्द ज़रूरी)
    internal val replyWatch = ReplyWatchdog(timeoutMs = LiveStay.NO_REPLY_MS, minSpeechMs = 250, answerTimeoutMs = 3500)   // नवंबर 2026: 1.8s -> 6s (पहला जवाब अक्सर 2-4s)
    @Volatile internal var unheard = 0
    @Volatile internal var rescued = 0
    /** v3.29: suna par jawab shuru nahi kiya -> wahi baat text me dobara (kitni baar) */
    @Volatile internal var stalledN = 0
    /** v3.29: baat khatam hote hi seedha kaam (Gemini se pehle) + Gemini ka phone_task dohraav na kare */
    internal val fastMemo = LiveFast.Memo()
    @Volatile internal var fastJob: Job? = null
    @Volatile internal var fastN = 0
    /** User ne toka: purane jawab ke jo tukde ab bhi aa rahe hain unhe mat bajao (interrupted/turnComplete tak) */
    @Volatile internal var dropOld = false
    @Volatile internal var droppedOld = 0L
    /** Echo-cancel chalu hai -> DK bolte samay bhi mic ki asli awaaz Gemini ko (sach me full-duplex) */
    @Volatile internal var aecOn = false
    /**
     * v3.9: band. Log (15:37) me dikha: lagatar asli mic = peeche ka shor bhi Gemini tak -> server sochta raha user bol raha hai
     * -> jawab 8-16s der se / aaya hi nahi. Ab DK bolte samay Gemini ko sirf PAKKE user shabd (BargeGate) jaate hain.
     */
    @Volatile internal var fullDuplex = false
    /** DK bolte samay "user bola?" - shak -> caption se shabd -> pakka (shor par jawab chalta rahe) */
    internal val bargeGate = BargeGate()
    /** Shak ke dauran ki awaaz (pakka hone par Gemini ko yahi pehle jaati hai) */
    internal val bargeBuf = ArrayDeque<ByteArray>()
    /**
     * v3.5+: DK बोल रहा है और यूज़र ने "रुको" के अलावा कुछ पूछा = जवाब नहीं काटा, सवाल यहाँ याद.
     * जवाब पूरा (आवाज़ बंद, कुछ बन नहीं रहा) होते ही वही सवाल Gemini को - उसका भी जवाब.
     */
    internal val askQueue = AskQueue()
    @Volatile internal var askAt = 0L
    /** HOLD (यूज़र का अगला सवाल सुन रहे) का हिसाब: caption का आख़िरी text, कब बदला, आख़िरी तेज़ आवाज़ */
    @Volatile internal var holdCap = ""
    @Volatile internal var holdCapAt = 0L
    @Volatile internal var holdLoudAt = 0L
    @Volatile internal var holdFinal = false
    /** आख़िरी बार DK की आवाज़ बज रही थी (कतार वाला सवाल थोड़ी चुप्पी के बाद ही) */
    @Volatile internal var lastPlayingAt = 0L
    /** v3.5+: एजेंट (कहानी/कोडिंग/कंप्यूटर/वेबसाइट) का tool आख़िरी बार कब बुलाया - "हाँ कर दिया" पर tool न बुलाया तो फ़ोन खुद */
    @Volatile internal var agentToolAt = 0L
    @Volatile internal var agentFixAt = 0L
    @Volatile internal var falseAlarms = 0
    @Volatile internal var realBarges = 0
    @Volatile internal var gatedChunks = 0L

    // ---- v3.66: सिर्फ़ मालिक की आवाज़ (परत 3) + दूर की आवाज़ अनसुनी (परत 4)
    /** इस बात का फ़ैसला (mic thread पर ही) */
    internal val owner = com.dk.assistant.voice.OwnerGate(CHUNK_MS)
    @Volatile internal var ownerOn = false
    @Volatile internal var ownerTh = com.dk.assistant.voice.VoicePrint.TH_NORMAL
    internal var ownerCfgAt = 0L
    /** हर नई बात पर +1 - पुरानी बात का देर से आया मिलान नई पर न लगे */
    @Volatile internal var ownerGen = 0
    internal class OwnerResult(val gen: Int, val score: Float?, val ms: Int, val tookMs: Long)
    @Volatile internal var ownerResult: OwnerResult? = null
    /** DK सोच/काम कर रहा था तब नई आवाज़: पहचान होने तक Gemini को नहीं (किसी और की बात DK का जवाब न काटे) */
    internal var ownerHolding = false
    internal val ownerHold = ArrayDeque<ByteArray>()
    /** इस जवाब को न बजाओ, न कोई काम करो - वह किसी और की आवाज़ का है */
    @Volatile internal var muteTurn = false
    @Volatile internal var muteAt = 0L
    // v3.67 (DoneGate): काम वाली बात पर Gemini की आवाज़ असली काम दिखने तक रोकी जाती है - झूठा "हो गया" बजता ही नहीं
    @Volatile internal var actHold = false
    @Volatile internal var actEvidence = false
    internal val actHeld = ArrayList<ByteArray>()
    @Volatile internal var actHeldBytes = 0
    /** v3.7: रोकना कब शुरू हुआ (घड़ी) - आम बात 0.9 s से ज़्यादा नहीं रुकती */
    @Volatile internal var actHoldAt = 0L
    @Volatile internal var actTextFrom = 0
    @Volatile internal var lastFastOkAt = 0L
    @Volatile internal var actDropped = 0
    @Volatile internal var actReleased = 0
    /** tool ने कहा "अभी हुआ नहीं" (शुरू हुआ / गड़बड़) - उसके बाद की बात फिर जाँचो */
    @Volatile internal var actPending = false
    @Volatile internal var actAfterTool = false
    /** v3.90: यूज़र ने साफ़ हुक्म दिया ("खोलो/भेजो/कर दो") = काम से पहले की बातें नहीं (पहले काम, फिर बात) */
    @Volatile internal var actCommand = false
    @Volatile internal var othersIgnored = 0
    @Volatile internal var mutedParts = 0L
    @Volatile internal var earlyEnds = 0
    internal var capSeen = ""; internal var capSeenAt = 0L
    /** 2.5 model ka "sochna" band (pehle awaaz tez) - setup mana kare to wapas */
    @Volatile internal var thinkOff = true
    /** v3.59: इस Live की आवाज़/भाषा/affective (setup पर एक बार तय - बीच में अपने-आप नहीं बदलती) */
    @Volatile private var extras: LiveVoice.Extras? = null
    @Volatile private var extrasModel = ""
    @Volatile private var extrasChosen = ""
    internal fun extrasFor(m: String): LiveVoice.Extras {
        val chosen = Prefs.geminiVoice(svc)
        val cur = extras
        if (cur != null && extrasModel == m && extrasChosen == chosen) return cur
        return LiveVoice.start(m, chosen, Prefs.liveExtrasOff(svc)).also { extras = it; extrasModel = m; extrasChosen = chosen }
    }
    @Volatile private var falseInterrupts = 0
    @Volatile internal var suspectAt = 0L
    @Volatile internal var localBargeAt = 0L
    @Volatile internal var bargeUntil = 0L
    // v3.53: फ़ोन से निकली आवाज़ (वीडियो/गाना) - PhoneAudioGuard. ये सब mic thread के; guardOk caption thread लिखता है
    @Volatile internal var guardHeld = false          // अभी की आवाज़ रोकी हुई - caption में DK का नाम आने तक Gemini को नहीं
    @Volatile internal var guardOk = false            // रोकी हुई बात में नाम मिल गया = यूज़र
    internal var guardPass = false                    // नाम वाली बात अभी चल रही - इसे दोबारा मत रोको
    internal var guardEndAt = 0L                      // रोकी हुई बात खत्म कब हुई (देर से आते शब्दों का इंतज़ार)
    internal val guardBuf = ArrayDeque<ByteArray>()
    @Volatile internal var dkOnlyNow = false          // v4.1: Settings → "सिर्फ़ DK बोलने पर सुनो" (हर 3s ताज़ा)
    internal var dkOnlyAt = 0L
    @Volatile internal var guardOn = false            // अभी गार्ड चालू (फ़ोन पर दूसरी आवाज़ बज रही)
    internal var mediaAt = 0L; internal var mediaOn = false
    @Volatile var phoneAudioIgnored = 0              // कितनी बार फ़ोन की अपनी आवाज़ छोड़ी (debug)
        internal set
    @Volatile private var thinkingSince = 0L
    @Volatile internal var lastBreakdown = ""
    @Volatile private var warmReuses = 0
    @Volatile internal var setupSentAt = 0L

    // ---- v3.68: बँटवारा - Gemini Live सिर्फ़ जवाब लिखे, बोलना अलग "आवाज़ इंजन" (voice/engine/*) करे
    /** इस connection में Live की अपनी आवाज़ नहीं बजती - उसका text SpeechPipeline बोलती है */
    @Volatile internal var connSplit = false
    /** setup में responseModalities = TEXT (false + connSplit = AUDIO + transcript वाला तरीका) */
    @Volatile internal var connText = false
    @Volatile private var connEngVer = -1
    /** v3.80: इस connection में Live की आवाज़ / अंदाज़ / मॉडल का version (बदले = चुप होते ही नया connection) */
    @Volatile internal var connLiveVer = -1
    @Volatile internal var splitEngine: com.dk.assistant.voice.engine.VoiceEngine? = null
    /** replyText का कितना हिस्सा pipeline को भेजा जा चुका (DoneGate रोके तो आगे नहीं) */
    @Volatile internal var splitFedTo = 0
    /** v3.71: जवाब की शुरुआत का भाव-टैग पढ़कर हटाने वाला (सिर्फ़ TEXT बँटवारे में) */
    @Volatile internal var tagFilter: com.dk.assistant.avatar.EmotionTag.Filter? = null
    /** इस बारी में pipeline से कुछ बोला गया */
    @Volatile internal var splitSpoke = false
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
                if (toolCallAt < trace.speechEndAt) LatencyBoard.record(name, trace.speechEndAt, trace.firstChunkAt, pipeline.firstSegPcmAt, at)?.let { log("देरी: " + LatencyBoard.line(it)) }
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
        override fun onEngineError(title: String, why: String) {
            val now = System.currentTimeMillis()
            if (now - engineErrShownAt < 5 * 60_000L && why == engineErrLast) return
            engineErrShownAt = now; engineErrLast = why
            DKState.reply("सर, $title नहीं बोला: ${why.removePrefix("Gemini TTS: ")}। अभी दूसरी आवाज़ से बोल रही हूँ।")
        }
        override fun onSilent(text: String) {
            if (!connSplit) return
            splitSilentAt = System.currentTimeMillis()
            splitOffUntil = splitSilentAt + SilentVoice.OFF_MS
            if (silentResay.isBlank()) silentResay = text.take(300) else if (silentResay.length < 600) silentResay += " " + text.take(300)
        }
    }
    /** v3.7: बँटवारे वाला इंजन चुप रहा (कब) / कब तक उसे छोड़कर Live की अपनी आवाज़ / जो बोला नहीं जा सका */
    @Volatile private var engineErrShownAt = 0L
    @Volatile private var engineErrLast = ""
    @Volatile private var splitSilentAt = 0L
    @Volatile private var splitOffUntil = 0L
    @Volatile internal var silentResay = ""
    internal val pipelineLazy: Lazy<com.dk.assistant.voice.engine.SpeechPipeline> = lazy { com.dk.assistant.voice.engine.SpeechPipeline(svc, speechHooks) }
    internal val pipeline: com.dk.assistant.voice.engine.SpeechPipeline by pipelineLazy
    private fun pipeBusy() = pipelineLazy.isInitialized() && pipeline.busy
    internal fun pipeStop() { if (pipelineLazy.isInitialized()) pipeline.stop() }

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
        connText = LiveSetupRules.askText(m, split = true)   // हमेशा false: native-audio Live सिर्फ़ AUDIO (TEXT = setup मना)
        log("बँटवारा: Live लिखेगा (${if (connText) "TEXT" else "AUDIO की लिखावट"}), बोलेगा ${usable.title} • ${usable.voice(svc)}")
        pipeline.warm(usable)
    }

    /** बँटवारे में: replyText का नया हिस्सा pipeline को (DoneGate रोके हो तो नहीं) */
    internal fun pumpSplit() {
        if (!connSplit || actHold || muteTurn || dropOld) return
        val add = synchronized(turnLock) {
            if (splitFedTo > replyText.length) splitFedTo = replyText.length
            val t = replyText.substring(splitFedTo); splitFedTo = replyText.length; t
        }
        if (add.isNotEmpty()) pipeline.feed(add)
    }

    /** Live की अपनी आवाज़ का टुकड़ा: बँटवारे में बजता नहीं (सिर्फ़ गिनती) */
    internal fun playOut(pcm: ByteArray) {
        if (connSplit) return
        playQ.offer(pcm)
    }

    /** Garam (standby) connection: wake word ke baad chup hone par band nahi kiya */
    internal class Parked(val ws: WebSocket, val closed: CompletableDeferred<Pair<Int, String>>, val model: String, val at: Long,
                         val split: Boolean = false, val text: Boolean = false, val liveVer: Int = -1, val keyIdx: Int = -1,
                         val resumedFrom: String? = null)
    @Volatile internal var parked: Parked? = null
    @Volatile private var curClosed: CompletableDeferred<Pair<Int, String>>? = null

    // ---- bajana (playback)
    internal val playQ = LinkedBlockingQueue<ByteArray>()
    @Volatile internal var track: AudioTrack? = null
    @Volatile internal var playEnd = 0L                // kab tak awaaz baj rahi hogi (andaza)
    @Volatile internal var gen = 0
    internal var playThread: Thread? = null
    fun isPlaying() = System.currentTimeMillis() < playEnd + 120 || playQ.isNotEmpty() || pipeBusy()

    internal val toolJobs = ConcurrentHashMap<String, Job>()

    /** Wake word se Live shuru hua: pehli baat (text) jo DK ko turant samajhni hai */
    @Volatile var firstText: String? = null
    /** >0 = itni der koi baat na ho to Live khud band (wake mode me wapas) */
    @Volatile var idleExitMs = 0L
    @Volatile internal var lastAct = 0L
    @Volatile private var idleExit = false
    /** v3.51: आखिरी असली काम (tool नतीजा / काम पूरा होने का SYSTEM NOTE) कब - इसके बिना "कर दिया" = झूठ */
    @Volatile internal var lastEvidenceAt = 0L
    @Volatile internal var lastGuardAt = 0L
    /** v3.51: आखिरी बात (user / DK) - "मैं चेक करता हूँ" पर Live खुला रहे */
    @Volatile internal var lastUserLine = ""
    @Volatile internal var lastDkLine = ""
    @Volatile internal var modelCache: Triple<Int, Long, List<String>>? = null

    fun stop(why: String = "user stop") {
        stopped = true
        curRace?.let { cancelRace(it, why) }      // v5.1: चलती दौड़ (पीछे वाली भी) बंद
        closeWs(ws, why)
        parked?.let { parked = null; closeWs(it.ws, why) }
    }

    // ================================================================ v3.40: फ़ोन कॉल + स्क्रीन/कैमरा
    /** Live जुड़ा है और अभी बोल-सुन सकता है */
    fun canTalk() = !stopped && ready && ws != null
    @Volatile internal var micMuted = false
    /** फ़ोन की अपनी आवाज़ (speakQuick) से कुछ बोलते समय वह आवाज़ Gemini को न जाए */
    fun muteMic(on: Boolean) { micMuted = on }
    /** call का आख़िरी काम (दो बार न हो: फ़ोन ने सुनकर उठाया, फिर Gemini ने भी phone_call माँगा) */
    @Volatile internal var callDone: Pair<String, Long>? = null
    internal val callLock = Any()
    /** v3.41: caption ka aakhri PAKKA text - scheduleFast doosri baar intezaar na kare */
    @Volatile internal var capFinal = ""

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
    @Volatile internal var callAudioOn = false
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

    internal val OPEN_NOTE = "(The normal Google search did not work right now, so these come from Google News headlines and Wikipedia. " +
        "Answer from them. If they do not answer the exact question, say briefly what you found and that the full detail could not be confirmed.)\n"

    @Volatile internal var promiseJob: Job? = null
    @Volatile internal var promiseFixAt = 0L

    /**
     * नवंबर 2026: बीच बातचीत text सिर्फ़ तब जब Gemini चुप हो और कोई turn न चल रहा हो (1011 की जड़: बोलते/सोचते समय text).
     * true = अभी भेज सकते हैं (ज़्यादा से ज़्यादा [maxMs] इंतज़ार)
     */
    internal suspend fun waitQuiet(maxMs: Long): Boolean {
        fun quiet() = !isPlaying() && !generating && !turnActive && !turnDet.inSpeech && toolJobs.isEmpty()
        withTimeoutOrNull(maxMs) { while (!stopped && !quiet()) delay(150) }
        if (!quiet()) return false
        delay(400)                        // ठीक अभी turn खत्म हुआ - server को साँस लेने दो
        return !stopped && quiet()
    }

    /** स्क्रीन/कैमरा चालू-बंद हुआ -> Gemini को बताओ (tool का जवाब बाकी हो तो उसके बाद) */
    fun visionNote(t: String, evidence: Boolean = true) {
        svc.scope.launch {
            if (!waitQuiet(20_000)) { log("Gemini चुप नहीं हुआ (20s) - SYSTEM NOTE नहीं भेजा: ${t.take(50)}"); return@launch }
            if (!canTalk()) return@launch
            if (evidence) lastEvidenceAt = System.currentTimeMillis()
            try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) } catch (_: Exception) {}
            log("Gemini को बताया: ${t.take(70)}")
        }
    }

    /** Haalat badlo (Thinking kab se hai yaad rakho - atakne par wapas) */
    internal fun setMode(m: Mode) {
        thinkingSince = if (m == Mode.THINKING) (if (DKState.mode == Mode.THINKING && thinkingSince > 0) thinkingSince else System.currentTimeMillis()) else 0L
        if (DKState.mode != m) DKState.set(m)
    }

    /** Kaun se connection HUMNE band kiye (aur kyun) - log me "server ने बंद किया" galat na likha jaye */
    internal val ourClose = ConcurrentHashMap<WebSocket, String>()
    internal fun closeWs(w: WebSocket?, why: String) {
        if (w == null) return
        ourClose[w] = why
        try { w.close(1000, "bye") } catch (_: Exception) {}      // reason ASCII hi (123 byte se zyada = close hi nahi hota)
    }

    /** Quota khatam wala model: aaj dobara mat maango (pehli baar 30 min, dobara = Pacific aadhi raat tak) */
    /** v3.94: अभी कौन सी key चल रही है (0 = मुख्य) - मॉडल की limit हर key की अलग */
    @Volatile internal var keyIdx = 0
    private fun blockModel(m: String, why: String, reason: String = "") {
        val n = Cooldown.strike(LiveKeys.cooldownName(keyIdx, m))
        // नवंबर 2026: दूसरी बार भी पूरे दिन की रोक सिर्फ़ असली रोज़ की limit पर (पहले हर दूसरी बार = Pacific आधी रात तक)
        val ms = if (LiveRace.dailyBlock(n, reason)) Cooldown.msToQuotaReset() else 30 * 60_000L
        Cooldown.block(LiveKeys.cooldownName(keyIdx, m), ms)
        log("$m की limit खत्म ($why) - ${ms / 60_000} मिनट तक यह मॉडल नहीं माँगूँगा")
    }
    /** नवंबर 2026: एक मॉडल पर बार-बार 1011 = Google की तरफ़ उस मॉडल की खराबी - 30 मिनट हटाओ (अगर दूसरा मॉडल बचा हो). नया मॉडल लौटाता है */
    internal val model1011 = LiveStay.Model1011()
    /** नवंबर 2026: 1011 से टूटे मॉडल 6 घंटे सबसे पीछे (ऐप बंद-चालू पर भी याद) */
    internal val broken1011: LiveStay.Broken1011 by lazy { LiveStay.Broken1011.decode(try { raceSp.getString("b1011", "") } catch (_: Exception) { "" }) }
    internal fun markBroken1011(m: String) {
        broken1011.mark(m)
        try { raceSp.edit().putString("b1011", broken1011.encode()).apply() } catch (_: Exception) {}
    }
    private fun dropModel1011(keys: List<String>, models: List<String>, where: String): String? {
        val m = model
        val free = { x: String -> keys.indices.any { k -> !Cooldown.blocked(LiveKeys.cooldownName(k, x)) } }
        val nowF = System.currentTimeMillis()
        val next = models.firstOrNull { it != m && free(it) && keys.indices.any { k -> !health.isComboFailed(LiveRace.Combo(it, k), nowF) } }
        if (next == null) { log("1011 बार-बार ($m, $where) - दूसरा मॉडल नहीं बचा, इसी पर कोशिश"); return null }
        // save होने वाली Cooldown नहीं (वह ऐप बंद-चालू पर भी रहती) - सिर्फ़ दौड़ की 2 मिनट वाली रोक, अपने-आप हटती है
        val now = System.currentTimeMillis()
        for (k in keys.indices) health.comboFail(LiveRace.Combo(m, k), now)
        markBroken1011(m)
        log("1011 \"Internal error\" ($m, $where) - Google की तरफ़ इस मॉडल की खराबी; ${LiveStay.BROKEN_1011_MS / 3600_000} घंटे सबसे पीछे, अब $next (नई बातचीत)")
        return next
    }
    private fun isQuota(code: Int, r: String) = r.contains("quota") || r.contains("exhausted") || r.contains("rate limit") || code == 429

    internal fun log(m: String) = DKLog.d("Live: $m")

    private fun updateStats() {
        stats = "मॉडल: ${model.ifBlank { "-" }} • जुड़ा: ${if (ready) "हाँ" else "नहीं"} • भेजे टुकड़े: $sent (${sentBytes / 1024} KB)" +
            " • गिरे: $dropped • मिक गैप: $gaps • पढ़ने की गलती: $readErr • आई आवाज़: ${recvAudio / 1024} KB" +
            " • दोबारा जुड़ा: $slowReconnects • खुद संभला (बिना रुकावट): ${(reconnects - slowReconnects).coerceAtLeast(0)}" +
            (if (zombieN > 0) " • अटका session बदला: $zombieN" else "") + (if (replayedN > 0) " • बात की आवाज़ दोबारा भेजी: $replayedN" else "") +
            " • गर्म connection दोबारा: $warmReuses" +
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
        sent = 0; sentBytes = 0; dropped = 0; gaps = 0; readErr = 0; recvAudio = 0; reconnects = 0; slowReconnects = 0; zombieN = 0; replayedN = 0
        textRescue = LiveStay.textRescueOn(svc); weakNetTold = false
        log("बीच बातचीत का बचाव-text: " + if (textRescue) "चालू (सेटिंग)" else "बंद (डिफ़ॉल्ट - 1011 से बचाव)")
        if (!Setup.hasMic(svc)) throw Fatal("माइक की परमिशन नहीं है", kind = LiveFailWhy.Kind.MIC)
        // v3.94: मुख्य key + बैकअप keys - एक बंद/गलत/limit खत्म = चुपचाप अगली (user को पता न चले)
        val keys = Prefs.liveGeminiKeys(svc).ifEmpty { throw Fatal("Gemini की key नहीं है", kind = LiveFailWhy.Kind.NO_KEY) }
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
        curKeys = keys; curModels = models
        if (stopped) return@withContext "जुड़ने से पहले बंद"
        mi = 0; var fails = 0
        var lostAt = 0L                                   // नवंबर 2026: connection कब टूटा (फिर जुड़ने तक कितनी रुकावट)
        zombieSwaps.reset(); zombieFor = 0L; replayAudio = null; recoverWhy = null; utt.clear(); lastAnyServerAt = 0L
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
                    // v5.1: standby वाला गर्म connection (wake word) किसी भी मॉडल/key का हो - वही लो
                    val pk = parked; parked = null
                    val pkOk = pk != null && !pk.closed.isCompleted && System.currentTimeMillis() - pk.at < PARK_MS
                    model = if (pkOk) pk!!.model else models[mi]
                    if (pkOk && pk!!.keyIdx in keys.indices) { ki = pk.keyIdx; keyIdx = ki; key = keys[ki] }
                    planSplit(model)                 // v3.68: इस connection में कौन बोलेगा (बँटवारा / सीधी आवाज़)
                    // v3.8: wake word ke baad garam rakha connection abhi zinda hai -> naya mat banao (0 ms)
                    val reuse = pkOk && pk!!.model == model && pk.split == connSplit && pk.text == connText &&
                        pk.liveVer == connLiveVer &&  // v3.80: बीच में आवाज़/मॉडल बदला = पुराना गर्म connection नहीं
                        LiveStay.useParked(pk.resumedFrom, handle, oldAlive = prevAlive)
                    if (pk != null && !reuse) closeWs(pk.ws, "standby purana")
                    val closed: CompletableDeferred<Pair<Int, String>>
                    val t0 = System.currentTimeMillis()
                    if (reuse) {
                        closed = pk!!.closed; curClosed = closed
                        ws = pk.ws; warmReuses++; connResumed = pk.resumedFrom != null    // goAway वाला = वही बातचीत (याद दोबारा नहीं)
                        synchronized(sendLock) { sendContext(ws); ready = true; flushPending(); sendReplay() }
                        log("गर्म connection फिर से [$model • key #${keyIdx + 1}] - जुड़ने में 0 ms (${(t0 - pk.at) / 1000}s से standby में था)")
                        DKState.setLink("Live • $model • key #${keyIdx + 1} • 0 ms")
                        svc.liveConnected()          // बैकअप मोड था तो खत्म
                        if (firstText != null) openerText = null; sendFirstText(); sendResend(); sendOpener()
                    } else {
                    // v5.1 (मालिक): तय 6s/12s का इंतज़ार नहीं - (मॉडल × key) की दौड़. #1 अभी, 1.5s में setup न हो तो #2 साथ में,
                    // फिर #3 (3 तक). हर जोड़ी का अपना इंतज़ार नेट के हिसाब से (4s - 22s); जो पहले जुड़े वही, बाकी तुरंत बंद.
                    // मालिक (नवंबर 2026): हल्का मोड हटा. 20s तक दौड़ (सारी जोड़ियाँ नाकाम = तुरंत नीचे की जाँच, बनावट की गलती ठीक करके फिर);
                    // फिर भी नहीं = साफ़ कारण के साथ बैकअप मोड (सेवा में)
                    ready = false
                    val rc = startRace(keys, models, live = true, resumeId = if (handle != null) LiveRace.Combo(model, ki).id else null)
                    withTimeoutOrNull(LiveRace.CONNECT_WAIT_MS) {
                        while (!rc.winner.isCompleted && !rc.over) delay(25)
                    }
                    val att = synchronized(raceLock) { if (rc.winner.isCompleted) rc.won else { rc.handOff = false; null } }
                    val ok = att != null
                    val stillTrying = !rc.over
                    closed = att?.closed ?: CompletableDeferred()
                    curClosed = closed
                    if (att != null) {
                        ki = att.c.keyIdx; key = keys[ki]; mi = models.indexOf(model).coerceAtLeast(0)
                        if (!att.resume) handle = null      // दूसरी जोड़ी जीती = नई बातचीत (उसके setup में handle नहीं गया था)
                    } else {
                        // नीचे वाली पुरानी जाँच उसी जोड़ी पर जिसकी गलती है (बनावट की गलती पहले)
                        (rc.configCombo ?: rc.lastFailCombo)?.let { c -> model = c.model; if (c.keyIdx in keys.indices) { ki = c.keyIdx; keyIdx = ki; key = keys[ki] } }
                    }
                    if (!ok) {
                        val (code, reason) = rc.config ?: rc.lastFail ?: (0 to "setup का जवाब ${LiveRace.CONNECT_WAIT_MS / 1000} सेकंड में नहीं आया")
                        ready = false
                        log("setup fail [$model • key #${keyIdx + 1}] code=$code reason=$reason" + if (stillTrying) " - दौड़ पीछे से जारी" else "")
                        val r = reason.lowercase()
                        // नवंबर 2026 (log 11:09): 18 जोड़ियाँ, एक का भी WebSocket नहीं खुला, जबकि Groq/OpenRouter चल रहे थे =
                        // चाबी/मॉडल/limit की गलती नहीं - फ़ोन से Google के server तक रास्ता बंद/अटका. साफ़ यही कारण, तुरंत बैकअप
                        val unreached = rc.config == null && rc.atts.isNotEmpty() && rc.opened.get() == 0 && NetProbe.online(svc)
                        if (!stopped && unreached) {
                            fails++
                            if (stillTrying) cancelRace(rc, "Google तक नहीं पहुँचा")
                            throw Fatal("Google के server तक connection खुला ही नहीं (${rc.atts.size} जोड़ियाँ, ${LiveFailWhy.detail(code, reason)})",
                                recoverable = true, kind = LiveFailWhy.Kind.UNREACHABLE)
                        }
                        // 20s में कोई जोड़ी नहीं जुड़ी (और गलती भी नहीं आई) = बहुत धीमा नेट -> दौड़ बंद, साफ़ कारण
                        if (!stopped && stillTrying && rc.config == null) {
                            fails++
                            cancelRace(rc, "20s में नहीं जुड़ा")
                            val k = if (NetProbe.online(svc)) LiveFailWhy.Kind.SLOW else LiveFailWhy.Kind.NET
                            throw Fatal("धीमा नेट - ${LiveRace.CONNECT_WAIT_MS / 1000}s में Live नहीं जुड़ा (${rc.lastFail?.let { LiveFailWhy.detail(it.first, it.second) } ?: "server से कोई जवाब नहीं"})", recoverable = true, kind = k)
                        }
                        // dobara judte waqt purani baatcheet (resume handle) na chale to turant nayi baatcheet - fail mat gino
                        if (!stopped && handle != null && code != 401 && code != 403 && !r.contains("api key") && !r.contains("quota")) {
                            log("पुरानी बातचीत जारी नहीं हो पाई - नई बातचीत से जुड़ रहा हूँ")
                            handle = null; continue
                        }
                        when {
                            stopped -> break
                            r.contains("api key") || r.contains("api_key") || code == 401 || code == 403 ->
                                if (switchKey("key गलत/बंद (code=$code)")) continue else throw Fatal("Gemini key गलत है या Live की इजाज़त नहीं (${LiveFailWhy.detail(code, reason)})", kind = LiveFailWhy.Kind.KEY)
                            LiveRace.shouldBlockModel(code, r, tried = rc.lastFailCombo != null) -> {
                                blockModel(model, "setup", r)
                                // v4.7 (मालिक: "limit खत्म = तुरंत दूसरी key"): पहले उसी key के बाकी मॉडल एक-एक कर (हर एक पर
                                // नया connection = सेकंडों की देर), फिर दूसरी key. अब सीधे दूसरी key; वह न हो तो बाकी मॉडल
                                if (switchKey("इस key की limit खत्म")) continue
                                else if (mi + 1 < models.size) { mi++; handle = null; continue }
                                else throw Fatal("Gemini Live की फ्री लिमिट अभी खत्म है", kind = LiveFailWhy.Kind.QUOTA)
                            }
                            // v3.68: यह मॉडल TEXT जवाब नहीं देता (native-audio मॉडल सिर्फ़ AUDIO) -> AUDIO + लिखावट से बँटवारा, याद रखो
                            // (अब TEXT कभी माँगा ही नहीं जाता - LiveSetupRules; यह पुराने standby connection के लिए बचा है)
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
                                if (mi + 1 < models.size) { mi++; handle = null; continue }
                                else throw Fatal("Live वाला कोई मॉडल नहीं चला (${LiveFailWhy.detail(code, reason)})",
                                    kind = LiveFailWhy.classify(code, reason).let { if (it == LiveFailWhy.Kind.CONFIG) it else LiveFailWhy.Kind.MODEL })
                        }
                        fails++
                        // मालिक: "न जुड़ पाए तो यूज़र को बोल दे" - 2 नाकाम कोशिशें (≈2s) = स्क्रीन पर बताओ (बोलकर: सेवा में, Live रुकने पर)
                        if (LiveConnectNotice.shouldTell(fails, svc.liveTroubleShown)) { svc.liveTroubleShown = true; DKState.reply(LiveConnectNotice.TROUBLE) }
                        // v5.1: दौड़ में सारी जोड़ियाँ पहले ही आज़माई गईं (हर एक सिर्फ़ एक बार) - बनावट की गलती ठीक न हो पाई तो
                        // वह जोड़ी भी 2 मिनट नहीं; दूसरी नाकाम दौड़ = साफ़ कारण के साथ बैकअप मोड (बार-बार उसी पर नहीं)
                        rc.configCombo?.let { health.comboFail(it, System.currentTimeMillis()); saveHealth() }
                        if (fails >= 2 || stillTrying) throw Fatal("Gemini Live से जुड़ नहीं पाया (${LiveFailWhy.detail(code, reason)})", recoverable = true,
                            kind = LiveFailWhy.classify(code, reason, NetProbe.online(svc)))
                        delay(minOf(LiveRetry.FAST_MAX_MS, 500L shl fails))       // v4.7: 1s, 2s, 2s (पहले 8s तक)
                        continue
                    }
                    }
                    fails = 0
                    // पहले "जुड़ नहीं पा रही" बताया था = अब "फिर जुड़ गई, बोलिए" (स्क्रीन पर; माइक चालू है, इसलिए फ़ोन की आवाज़ नहीं)
                    if (svc.liveTroubleShown || svc.liveTroubleSpoken) { svc.liveTroubleShown = false; svc.liveTroubleSpoken = false; DKState.reply(LiveConnectNotice.BACK) }
                    val upAt = System.currentTimeMillis()
                    val resumedConn = handle != null
                    // नवंबर 2026: कितनी देर टूटा रहा - 12s तक = यूज़र को पता नहीं चला (अपने-आप संभला); ज़्यादा = सच में रुकावट
                    if (lostAt > 0L) {
                        val gap = upAt - lostAt; lostAt = 0L
                        if (gap > LiveRecover.RECOVER_MS) { slowReconnects++; log("Live: connection टूटा रहा ${gap / 1000}s - यूज़र को रुकावट दिखी (#$slowReconnects)") }
                    }
                    upSince = upAt
                    log("जुड़ गया [$model • key #${keyIdx + 1}] ${System.currentTimeMillis() - t0} ms" + if (handle != null) " (पुरानी बातचीत जारी)" else "")
                    DKState.setLink("Live • $model • key #${keyIdx + 1} • ${System.currentTimeMillis() - t0} ms")
                    svc.liveConnected()              // बैकअप मोड था तो खत्म, वापस DK लाइव
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
                        // बँटवारे वाले इंजन (Kokoro / Gemini TTS) की आवाज़ हर टुकड़े पर नई पढ़ी जाती है - वहाँ connection नहीं तोड़ना
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
                        // v4.6 (मालिक: "बोला हुआ लिखा जाता है, पर जवाब नहीं आता"): बचाव का text भी भेजा, फिर भी server
                        // बिल्कुल चुप = connection खुला पर सत्र अटका. पहले यहाँ कुछ नहीं होता था (DK हमेशा चुप).
                        // अब: तुरंत नया connection (नई बातचीत, अटका सत्र नहीं) + वही बात दोबारा (नीचे resendText).
                        if (textRescue && !generating && !isPlaying() && toolJobs.isEmpty() && !turnDet.inSpeech && replyWatch.dead(now)) {
                            deadSessions++
                            log("बात भेजी, बचाव भी भेजा, पर ${ReplyWatchdog.DEAD_MS / 1000}s से जवाब नहीं - सत्र अटका, तुरंत नया connection और वही बात दोबारा (#$deadSessions) • ${timeline.summary()}")
                            handle = null; goAway = true; goAwayDrop = true
                            break
                        }
                        // नवंबर 2026 (DKI-70D747, मालिक: "बीच-बीच में लाइव जवाब नहीं देता"): बात भेजी पर उस पल से server से
                        // एक भी संदेश नहीं (न लिखावट, न VAD) = session अंदर से अटका (Google की जानी-मानी खराबी, 3.1/3.8).
                        // पहले यह बचाव सिर्फ़ बचाव-text चालू होने पर था (डिफ़ॉल्ट बंद) = DK हर बार चुप.
                        // अब हमेशा: 4s में नया connection + वही आवाज़ दोबारा (text नहीं). मिनट में 2 बार तक.
                        val zBusy = generating || isPlaying() || toolJobs.isNotEmpty() || turnDet.inSpeech || muteTurn || ownerHolding || guardHeld
                        val zEnd = if (timeline.active) timeline.endpointAt else 0L
                        if (zEnd > 0L && zEnd != zombieFor &&
                            LiveRecover.zombie(now, zEnd, timeline.eventAt, lastAnyServerAt, zBusy, utt.speechMs(), BargeGate.realWords(caption?.text().orEmpty()))) {
                            zombieFor = zEnd
                            if (zombieSwaps.tryTake(now)) {
                                zombieN++
                                val again = zombieSwaps.recent(now) > 1
                                replayAudio = utt.snapshot(now); replayAt = now
                                recoverWhy = "session अटका"; recoverAt = now
                                if (again) handle = null          // अटका सत्र फिर अटका = नई बातचीत (याद prompt में)
                                log("Live: बात के बाद ${(now - zEnd) / 100 / 10.0}s तक Gemini से कुछ नहीं आया (न लिखावट, न VAD) - session अटका; " +
                                    "नया connection${if (again) " (नई बातचीत)" else " (बातचीत वहीं से)"} और आपकी वही आवाज़ दोबारा (#$zombieN) • ${timeline.summary()}")
                                goAway = true; goAwayDrop = true
                                break
                            } else log("Live: session फिर अटका पर 1 मिनट में ${LiveRecover.MAX_SWAPS} बार बदल चुका - इस बार नहीं (चक्कर नहीं) • ${timeline.summary()}")
                        }
                        // नवंबर 2026: फ़ोन का नेटवर्क बदला (Wi-Fi <-> mobile) = पुराना socket पुराने रास्ते पर, ping से पता चलने में 30s.
                        // DK चुप होते ही तुरंत नया connection (बातचीत वहीं से); पुराना standby भी पुराने रास्ते का - हटाओ
                        if (DkDns.switchedAt > upAt && !isPlaying() && toolJobs.isEmpty() && !turnDet.inSpeech && !generating) {
                            parked?.let { p -> parked = null; closeWs(p.ws, "नेटवर्क बदला") }
                            recoverWhy = "नेटवर्क बदला"; recoverAt = now
                            log("नेटवर्क बदला - पुराने रास्ते का connection छोड़कर तुरंत नया (बातचीत वहीं से)")
                            goAway = true; goAwayDrop = true
                            break
                        }
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
                    prevAlive = !closed.isCompleted          // goAway/बदलाव पर हमने बंद किया (पुराना ज़िंदा था) या खुद कटा
                    val (code, reason) = if (closed.isCompleted) closed.await() else 1000 to "goAway"
                    // user ki baat khatam ho chuki thi par jawab shuru nahi hua aur connection toot gaya -> woh baat
                    // chupchaap kho jaati thi. Dobara judte hi ek baar text me bhejo.
                    val dropAt = System.currentTimeMillis()
                    val textAgo = wire.lastTextAgo()
                    val lastedAtDrop = dropAt - upAt
                    if (textAgo >= 0) log("कटने से ${"%.1f".format(textAgo / 1000.0)}s पहले text भेजा था (${lastedAtDrop / 1000}s चला)")
                    if (LiveStay.skipResend(lastedAtDrop, textAgo)) {
                        resendText = null; replyWatch.onResent()
                        log("2s से कम चला और ठीक पहले text गया था - वही text दोबारा नहीं (टूटने का चक्कर नहीं)")
                    } else if (textRescue && resendText == null && replyWatch.unanswered(dropAt)) {
                        val t = caption?.text().orEmpty().trim()
                            .ifBlank { if (!userDone) turnUser.trim() else "" }
                            .ifBlank { synchronized(turnLock) { userText.toString().trim() } }
                        if (BargeGate.realWords(t)) { resendText = t; replyWatch.onResent() }
                        log("connection टूटा जब बात का जवाब बाकी था • ${timeline.summary()}" + if (resendText != null) " - जुड़ते ही दोबारा भेजूँगा" else "")
                    } else if (!textRescue && replayAudio == null && replyWatch.unanswered(dropAt)) {
                        // नवंबर 2026: बचाव-text बंद (डिफ़ॉल्ट) में पहले यह बात चुपचाप खो जाती थी - अब उसकी आवाज़ दोबारा (text नहीं)
                        val a = utt.snapshot(dropAt)
                        if (a.isNotEmpty()) { replayAudio = a; replayAt = dropAt; replyWatch.onResent() }
                        log("connection टूटा जब बात का जवाब बाकी था • ${timeline.summary()}" + if (a.isNotEmpty()) " - जुड़ते ही वही आवाज़ दोबारा" else "")
                    }
                    if (recoverWhy == null) { recoverWhy = "connection टूटा/बदला"; recoverAt = dropAt }
                    lostAt = dropAt
                    closeWs(ws, "reconnect")
                    upSince = 0L
                    LiveStay.modeAfterDrop(DKState.mode, isPlaying()).let { if (it != DKState.mode) setMode(it) }
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
                    // नवंबर 2026: पुरानी बातचीत से जुड़ते ही 1011 = वह बातचीत टूटी हुई - उससे फिर नहीं
                    if (LiveStay.resumePoisoned(code, lastedAtDrop, resumedConn) && handle != null) { handle = null; log("पुरानी बातचीत से जुड़ते ही 1011 - वह बातचीत टूटी हुई, अब नई बातचीत") }
                    // 2.5 native-audio = पहले 1011 पर ही (Google की जानी-मानी खराबी), बाकी = 3 मिनट में 2 बार
                    val drop1011 = code == 1011 && model1011.hit(model) || code == 1011 && LiveStay.known1011(model)
                    if (drop1011) dropModel1011(keys, models, "बातचीत के बीच")?.let { handle = null }
                    log("connection टूटा (code=$code, ${reason.ifBlank { "-" }}, ${(System.currentTimeMillis() - upAt) / 1000}s चला) - दोबारा जुड़ रहा हूँ #$reconnects" +
                        if (handle != null) " (बातचीत वहीं से)" else " (नई बातचीत)")
                    if (reason.lowercase().contains("api key")) {
                        if (turnActive) finishTurn()
                        if (switchKey("key बंद हो गई")) continue else throw Fatal("Gemini key बंद हो गई")
                    }
                    if (isQuota(code, reason.lowercase())) {
                        blockModel(model, "बातचीत के बीच", reason)
                        if (turnActive) finishTurn()
                        if (switchKey("बातचीत के बीच इस key की limit खत्म")) continue     // v4.7: सीधे दूसरी key
                        else if (mi + 1 < models.size) { mi++; handle = null; continue }
                        else throw Fatal("Gemini Live की फ्री लिमिट खत्म")
                    }
                    if (turnActive) finishTurn()
                    // 20 sec se pehle toota = network/server dikkat -> har baar zyada ruko (battery bachao)
                    val lasted = System.currentTimeMillis() - upAt
                    if (goAwayDrop || lasted >= 20_000) quickDrops = 0 else quickDrops++
                    goAwayDrop = false
                    // नवंबर 2026 (मालिक): 7 बार के बाद हार नहीं - ✕ दबाने तक कोशिश. इंतज़ार 1, 2, 4, 8, 15s (+ random)
                    if (quickDrops >= LiveStay.WEAK_NET_TELL_AT && !weakNetTold) {
                        weakNetTold = true
                        DKState.setLink("नेटवर्क कमज़ोर है - पीछे से जोड़ रहा हूँ")
                        log("बार-बार टूट रहा है ($quickDrops) - यूज़र को एक बार बताया: नेटवर्क कमज़ोर है")
                    }
                    val wait = LiveStay.backoffMs(quickDrops)
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
                ready = false; upSince = 0L
                ws = null; curClosed = null
                caption?.stop(); caption = null
                fastJob?.cancel(); fastJob = null; fastMemo.reset()
                toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
                stopPlayer()
                LiveStay.modeAfterEnd(DKState.mode).let { if (it != DKState.mode) { DKState.set(it); thinkingSince = 0L } }
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
        val p = Parked(w, c, model, System.currentTimeMillis(), connSplit, connText, connLiveVer, keyIdx)
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
    @Volatile internal var openerAt = 0L

    /** अभी बोल सकता है तो तुरंत, नहीं तो जुड़ते ही. true = अभी भेज दिया */
    fun greetNow(note: String): Boolean {
        openerText = note
        if (!canTalk() || isPlaying() || turnDet.inSpeech || toolJobs.isNotEmpty()) return false
        sendOpener()
        return openerText == null
    }

    /**
     * Nayi baat ki samay-rekha shuru. Pichhli baat par server se kuch bhi nahi aaya tha (na event, na awaaz) to
     * woh mamla log me - "bola par jawab nahi" agli baar bhi dikhe (pakad me aaye).
     */
    @Volatile internal var tlWords = false
    @Volatile internal var noiseTurns = 0
    @Volatile internal var contTurns = 0
    /** आपने आखिरी बार बोलना कब शुरू किया (फ़ोन VAD) */
    @Volatile internal var speechStartAt = 0L
    /** इस बारी में Gemini ने फ़ोन का काम (tool) माँगा - देरी के औसत में नहीं गिनते */
    @Volatile internal var toolCallAt = 0L

    // ================================================================ v5.1: (मॉडल × key) की दौड़
    // मालिक: "DK लाइव 1-2 सेकंड में जुड़े; धीमे नेट / गलत key / मॉडल बंद पर भी". नियम LiveRace में (JVM test).
    private val raceSp by lazy { svc.getSharedPreferences("dk_live_race", android.content.Context.MODE_PRIVATE) }
    internal val health: LiveRace.Health by lazy { LiveRace.Health.decode(try { raceSp.getString("h", "") } catch (_: Exception) { "" }) }
    internal fun saveHealth() { try { raceSp.edit().putString("h", health.encode()).apply() } catch (_: Exception) {} }
    internal val raceLock = Any()

    /** दौड़ की एक जोड़ी (अपना WebSocket, अपना closed/setup) - key सिर्फ़ URL में, log में कभी नहीं */
    internal inner class Attempt(val c: LiveRace.Combo, val key: String, val text: Boolean, val resume: Boolean) {
        val closed = CompletableDeferred<Pair<Int, String>>()
        val setup = CompletableDeferred<Unit>()
        val t0 = System.currentTimeMillis()
        @Volatile var w: WebSocket? = null
        @Volatile var sentAt = 0L
        @Volatile var won = false
        @Volatile var dead = false
    }

    internal inner class Race(val live: Boolean, val keyCount: Int) {
        val winner = CompletableDeferred<Attempt>()
        /** जीतने वाली जोड़ी (winner के साथ ही, raceLock में) */
        @Volatile var won: Attempt? = null
        /** true = जीतने वाला सीधे Live बने; false (run() ने इंतज़ार छोड़ा / पीछे की दौड़) = standby में रखो */
        @Volatile var handOff = live
        @Volatile var over = false
        @Volatile var cancelled = false
        @Volatile var config: Pair<Int, String>? = null
        @Volatile var configCombo: LiveRace.Combo? = null
        @Volatile var lastFail: Pair<Int, String>? = null
        @Volatile var lastFailCombo: LiveRace.Combo? = null
        val atts = java.util.concurrent.CopyOnWriteArrayList<Attempt>()
        /** कितनी जोड़ियों का WebSocket सच में खुला (0 = Google के server तक पहुँचे ही नहीं) */
        val opened = java.util.concurrent.atomic.AtomicInteger()
    }
    @Volatile internal var curRace: Race? = null
    /** पिछली नाकाम दौड़ का असली कारण - अगली दौड़ में सारी जोड़ियाँ रुकी हों तो वही कारण (पहले "limit" शब्द से गलत "लिमिट खत्म" दिखता था) */
    @Volatile internal var lastRaceFail: Pair<Int, String>? = null
    @Volatile internal var lastRaceFailAt = 0L
    /** सब रुका होने पर आख़िरी जाँच-कोशिश कब (2 मिनट में एक से ज़्यादा नहीं - लगातार चक्कर नहीं) */
    @Volatile internal var lastProbeAt = 0L
    /** इस connection पर पुरानी बातचीत (handle) चली = याद दोबारा नहीं भेजनी */
    @Volatile internal var connResumed = false
    internal val ctxSent = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebSocket, Boolean>())

    /** v5.1: पीछे की दौड़ से जीता (या wake word वाला) connection standby में तैयार */
    fun warmReady(): Boolean = parked?.let { !it.closed.isCompleted && System.currentTimeMillis() - it.at < PARK_MS } == true
    fun raceBusy(): Boolean = curRace?.let { !it.over } == true
    /** नेट वापस आया: नेट की वजह से रुकी जोड़ियाँ फिर (गलत key वाली नहीं) + पीछे की दौड़ तुरंत */
    fun netBack() { health.clearComboFails(); saveHealth() }

    // ================================================================ server ke sandesh

    internal fun now0() = System.currentTimeMillis()

    /** Caption is key par chal raha hai -> "आप:" sirf usi se (Live model ka der se aaya text screen par nahi, warna text aage-peeche hota) */
    internal fun captionOn() = caption?.let { it.ready && it.working } == true

    @Volatile internal var micOffSent = false


    // ================================================================ phone ke kaam (function calling)
    /** v3.45: ताज़ा लोकेशन + मौसम (हर बार नई; कहीं सेव नहीं) */
    // ================================================================ v3.88: बोलते-बोलते तैयारी (speculative prefetch)
    internal class Prep(val kind: SpecIntent.Kind, val text: String, val at: Long, val job: Deferred<Any?>)
    @Volatile internal var prepWeather: Prep? = null
    @Volatile internal var prepSearch: Prep? = null

    /** v3.90: पहले से शुरू हुई खोज किस वजह से fail हुई (null = fail नहीं / थी ही नहीं) */
    @Volatile internal var prepFail: SearchGuard.Fail? = null

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
                if (!waitQuiet(60_000)) { log("सूचना: Gemini चुप नहीं हुआ (60s) - text नहीं भेजा, अगली सूचना के साथ"); return@launch }
                val items = synchronized(notifQ) { notifQ.toList().also { notifQ.clear() } }
                if (items.isEmpty() || !canTalk() || svc.callBusy()) return@launch
                val ok = try { ws?.tx(JSONObject().put("realtimeInput", JSONObject().put("text", NotifPush.note(items))).toString()) == true } catch (_: Exception) { false }
                lastAct = System.currentTimeMillis()
                log("🔔 नया notification Live को बताया (${items.size})" + if (ok) "" else " (भेज नहीं पाया)")
            } finally { synchronized(notifQ) { notifFlushing = false } }
        }
    }

    /** DK AI: वही tool call id दोबारा आए तो काम दोबारा नहीं (दो बार call/मैसेज न जाए) */
    internal val toolDedup = ToolCallDeduplicator()

    /**
     * v3.29: phone_task तेज़ + सच्चा.
     * Gemini Live का tool call रुकने वाला है (मॉडल जवाब आने तक चुप) - इसलिए यहाँ देर = DK की चुप्पी.
     * 1) बात खत्म होते ही वही काम हो चुका (scheduleFast) -> तुरंत "हो चुका"
     * 2) सीधा काम (QuickCommands) -> खुद, ~0.1-1s
     * 3) बाकी AgentRunner; 20s में पूरा न हो तो Gemini को सच ("अभी चल रहा") और पूरा होने पर उसे बताओ
     * पहले: 60s तक इंतज़ार और नतीजा न मिले तो "done" (झूठा "हो गया").
     */
    // ---------------- v3.87: एक साथ कई काम (कतार) ----------------
    @Volatile internal var taskQueue: TaskRunQueue? = null
    @Volatile internal var queueAnswer: CompletableDeferred<String>? = null

    // ================================================================ mic -> stream
    internal val pending = ArrayDeque<ByteArray>()       // judne/dobara judne ke beech ki baat (1 sec tak)

    internal var farLogged = 0

    internal var nearLearned = 0

    @Volatile internal var loudMs = 0L
    @Volatile internal var rescueWaits = 0

    /** Dheere bolne par bhi saaf awaaz Gemini tak (v3.7) */
    internal val boost = MicBoost(maxGain = LiveHear.BOOST_MAX, noiseCap = LiveHear.BOOST_NOISE_CAP)   // v4.2: और धीमी आवाज़ भी

    // ================================================================ Gemini ki awaaz bajana

    @Volatile internal var firstWriteAt = 0L
    /** v5.1: जवाब के बीच कितनी बार jitter buffer ने रुककर आवाज़ जमा की */
    @Volatile internal var jitterWaits = 0

    internal var spkBoost: android.media.audiofx.LoudnessEnhancer? = null
}
