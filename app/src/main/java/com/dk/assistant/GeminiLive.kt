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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *  Mic (16 kHz, mono, PCM 16-bit little-endian) --40ms ke tukde lagatar--> Gemini
 *  Gemini khud sunta hai ki aapne bolna kab khatam kiya (server VAD, ~600ms chup)
 *  Gemini ki awaaz (24 kHz PCM) tukdon me aati hai --> turant AudioTrack par bajti hai
 *  Beech me bolo -> Gemini "interrupted" bhejta hai -> DK turant chup (barge-in)
 *
 * Purane tareeke (poora record -> Whisper -> AI -> alag TTS) me 4 alag kadam the; yahan sab ek hi stream me.
 * Connection toote to apne-aap dobara judta hai (session resumption = baatcheet yaad rehti hai).
 */
class GeminiLive(private val svc: DKAccessibilityService, private val agent: AgentRunner) {

    companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        private const val CHUNK_MS = 40
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

    /** Live nahi chal sakta (key galat / limit / model nahi) - purane tareeke par jao */
    class Fatal(msg: String) : Exception(msg)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)       // stream: read timeout nahi
        .pingInterval(15, TimeUnit.SECONDS)          // connection zinda hai? (NAT/mobile data par tootne se bachao)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var stopped = true
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
    @Volatile private var shownReply = ""
    @Volatile private var eosAt = 0L
    @Volatile private var firstAudioAt = 0L

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
            " • दोबारा जुड़ा: $reconnects" + (if (lastLatency >= 0) " • पिछली देरी: $lastLatency ms" else "")
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
        caption = LiveCaption(key, onText = { t, fin -> onCaption(t, fin) }, onLate = { t -> onLateCaption(t) }, isNewSpeech = { barging }).also { it.start() }
        coroutineScope {
            val mic = launch(Dispatchers.IO) { micLoop() }
            // DK ka text awaaz ke saath-saath (har 80ms)
            val reveal = launch(Dispatchers.IO) { while (isActive && !stopped) { revealTick(); delay(80) } }
            try {
                while (!stopped && isActive) {
                    if (svc.callBusy()) return@coroutineScope
                    model = models[mi]
                    val closed = CompletableDeferred<Pair<Int, String>>()
                    val setup = CompletableDeferred<Unit>()
                    val t0 = System.currentTimeMillis()
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
                            handle != null && (r.contains("handle") || r.contains("resum") || r.contains("session")) -> { handle = null; continue }
                            r.contains("model") || r.contains("not found") || r.contains("not supported") || code == 1008 || code == 404 || code == 1007 ->
                                if (mi + 1 < models.size) { mi++; handle = null; continue } else throw Fatal("Live वाला कोई मॉडल नहीं चला ($reason)")
                        }
                        fails++
                        if (fails >= 4) throw Fatal("Gemini Live से जुड़ नहीं पाया: ${reason.take(80)}")
                        delay(minOf(8000L, 500L shl fails))       // 1s, 2s, 4s, 8s
                        continue
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
                        if (System.currentTimeMillis() % 10_000 < 200) { updateStats(); log(stats) }
                        delay(200)
                    }
                    ready = false
                    if (stopped) break
                    val (code, reason) = if (closed.isCompleted) closed.await() else 1000 to "goAway"
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
                    if (quickDrops >= 6) throw Fatal("Gemini Live बार-बार टूट रहा है - नेटवर्क देखें")
                    val wait = if (quickDrops == 0) 250L else minOf(15_000L, 500L shl quickDrops)   // 1s,2s,4s,8s,15s
                    if (quickDrops > 0) log("जल्दी टूटा (${lasted / 1000}s) - $wait ms रुककर दोबारा (#$quickDrops)")
                    delay(wait)
                }
            } finally {
                stopped = true
                ready = false
                closeWs(ws, "bye")
                ws = null
                caption?.stop(); caption = null
                reveal.cancel()
                mic.cancelAndJoin()
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
                .put("description", "Give a programming task to the user's coding team (Builder + Reviewer + Coordinator) that works on their GitHub repo - e.g. build/change/fix a feature in the app. Also use it when the user approves or rejects a coding suggestion (\"सुझाव जोड़ दो\" / \"सुझाव नहीं\"). It starts in the background; tell the user it has started.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("task", JSONObject().put("type", "STRING").put("description", "The full coding task (or the suggestion decision) in the user's words")))
                    .put("required", JSONArray().put("task"))))
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
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.geminiVoice(svc))))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", brain.liveSystemPrompt()))))
            .put("tools", tools)
            .put("realtimeInputConfig", JSONObject()
                .put("automaticActivityDetection", JSONObject()
                    .put("disabled", false)
                    .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    // HIGH = chup hote hi jaldi "bolna khatam" (tez jawab); 500ms = docs ki salaah (500-800ms) ka sabse tez
                    .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    // 20ms = pehla shabd (chhota haan bhi) turant pakdo
                    .put("prefixPaddingMs", 20)
                    .put("silenceDurationMs", 500))
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
    private fun onServer(msg: String, setup: CompletableDeferred<Unit>) {
        val o = try { JSONObject(msg) } catch (e: Exception) { log("JSON नहीं समझा: ${msg.take(80)}"); return }
        if (o.has("setupComplete")) {
            ready = true; setup.complete(Unit); flushPending()
            firstText?.let { t ->
                firstText = null
                try {
                    // "आप:" line me wahi baat (wake word ke baad jo bola) - jawab ke turn me judegi
                    startTurn(); turnUser = t; userDone = false
                    DKState.user("$t…")
                    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
                    log("पहली बात भेजी: $t")
                } catch (_: Exception) {}
            }
            return
        }
        lastAct = System.currentTimeMillis()
        o.optJSONObject("sessionResumptionUpdate")?.let {
            if (it.optBoolean("resumable") && it.optString("newHandle").isNotBlank()) handle = it.optString("newHandle")
        }
        o.optJSONObject("goAway")?.let { log("goAway: ${it.optString("timeLeft")} बाकी"); goAway = true }
        o.optJSONObject("voiceActivity")?.optString("type")?.let { t ->
            if (t == "ACTIVITY_START") {
                // jawab ban chuka (turnComplete) par awaaz abhi baj rahi hai aur user bolne laga -> server "interrupted" nahi bhejta, khud chup ho
                if (isPlaying() && !generating) stopForUser("ACTIVITY_START")
                else if (!isPlaying()) DKState.set(Mode.LISTENING)
            }
            if (t == "ACTIVITY_END") { eosAt = System.currentTimeMillis(); firstAudioAt = 0; if (!isPlaying()) DKState.set(Mode.THINKING) }
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { runTools(it) }
        o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a ->
            for (i in 0 until a.length()) toolJobs.remove(a.optString(i))?.cancel()
        }
        val sc = o.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            if (!turnActive) startTurn()
            Ducker.duck(svc, "Live: user बोल रहा")
            val u = synchronized(turnLock) { userText.append(t); userText.toString().trim() }
            eosAt = System.currentTimeMillis()
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
                if (firstAudioAt == 0L) {
                    firstAudioAt = System.currentTimeMillis()
                    if (eosAt > 0) { lastLatency = firstAudioAt - eosAt; log("देरी: बोलना खत्म → पहली आवाज़ = $lastLatency ms") }
                }
                turnRecvBytes += pcm.size
                playQ.offer(pcm)
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
            if (!turnActive) startTurn()
            generating = true
            // screen par abhi nahi - revealTick() awaaz ke saath-saath dikhata hai
            synchronized(turnLock) { replyText.append(t) }
        }
        if (sc.optBoolean("interrupted")) {
            log("user ने बीच में टोका - आवाज़ रोकी")
            flushPlayback()
            // close=false: user abhi bol raha hai (toka hai) - uski nayi baat band mat karo
            if (turnActive) finishTurn(close = false)
            commitPending(false)          // jitna bola utna hi likha rahe
            DKState.set(Mode.LISTENING)
        }
        if (sc.optBoolean("turnComplete")) {
            finishTurn()
            // jawab poora bana; jab DK ki awaaz bajna band ho uske ~1.2s baad peeche ki awaaz dheere-dheere wapas
            Ducker.releaseWhenQuiet({ !isPlaying() && !generating })
        }
    }

    private fun startTurn() {
        turnActive = true
        synchronized(turnLock) { userText.clear(); replyText.clear() }
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
        if (r.isNotBlank()) synchronized(turnLock) { pendingReply = r }
        firstAudioAt = 0; replyStarted = false
        if (!isPlaying()) DKState.set(Mode.LISTENING)
    }

    /** Caption is key par chal raha hai -> "आप:" sirf usi se (Live model ka der se aaya text screen par nahi, warna text aage-peeche hota) */
    private fun captionOn() = caption?.let { it.ready && it.working } == true

    /** Caption se turant shabd - "आप:" line */
    private fun onCaption(t: String, final: Boolean) {
        lastAct = System.currentTimeMillis()
        if (t.isNotBlank()) Ducker.duck(svc, "Live: user बोल रहा")   // user ke shabd aaye -> peeche ka gaana/video dheema
        // DK bol raha hai aur user ke asli shabd aaye (mic DK bolte samay sirf tez awaaz bhejta hai) -> DK chup
        if (isPlaying() && !generating) stopForUser("caption")
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
        DKState.set(Mode.LISTENING)
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
        val text = synchronized(turnLock) { pendingReply.also { pendingReply = null } } ?: return
        val out = if (full || shownReply.isBlank()) text else "$shownReply…"
        shownReply = ""
        DKState.reply(out)
    }

    // ================================================================ phone ke kaam (function calling)
    private fun runTools(calls: JSONArray) {
        for (i in 0 until calls.length()) {
            val fc = calls.optJSONObject(i) ?: continue
            val id = fc.optString("id"); val name = fc.optString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
            log("tool: $name $args")
            val tStart = System.currentTimeMillis()
            val j = svc.scope.launch {
                val result = when (name) {
                    "phone_task" -> {
                        DKState.set(Mode.WORKING)
                        val before = DKState.lastReply
                        val job = agent.run(args.optString("request"), speak = false)
                        withTimeoutOrNull(60_000) { job.join() } ?: run { job.cancel(); null }
                        DKState.lastReply.takeIf { it != before && it.isNotBlank() } ?: "done"
                    }
                    "remember_fact" -> { Memory.addFact(svc, args.optString("fact")); "saved" }
                    "coding_task" -> {
                        val t = args.optString("task")
                        CoderTeam.voiceDecision(svc, t) ?: when {
                            !Prefs.coderOn(svc) -> "Coding agent is OFF in settings - tell the user to turn it on (सेटिंग्स → कोडिंग एजेंट)."
                            !Prefs.coderTeam(svc) -> "Coding team is OFF in settings (सेटिंग्स → कोडिंग एजेंट → टीम)."
                            else -> CoderTeam.start(svc, t, fromVoice = true)?.let { "Could not start: $it" }
                                ?: "Started. Builder writes, Reviewer checks, Coordinator pushes and will notify when done."
                        }
                    }
                    "deep_think" -> {
                        DKState.set(Mode.THINKING)
                        try {
                            withTimeoutOrNull(45_000) { BrainManager(svc).think(args.optString("question")).text }
                                ?.takeIf { it.isNotBlank() } ?: "Deep thinking timed out - answer it yourself carefully."
                        } catch (e: Exception) {
                            log("deep_think fail: ${e.message}")
                            "Deep thinking unavailable - answer it yourself carefully."
                        }
                    }
                    else -> "unknown function"
                }
                val resp = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
                    JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result.take(if (name == "deep_think") 4000 else 1500))))))
                log("tool $name ${(System.currentTimeMillis() - tStart)} ms: ${result.take(80)}")
                // ab se deri ginti: tool ka jawab bheja -> pehli awaaz (tool ka samay alag dikhta hai)
                eosAt = System.currentTimeMillis(); firstAudioAt = 0
                if (ws?.send(resp.toString()) != true) log("tool जवाब नहीं भेज पाया")
                toolJobs.remove(id)
            }
            toolJobs[id] = j
        }
    }

    // ================================================================ mic -> stream
    private val pending = ArrayDeque<ByteArray>()       // judne/dobara judne ke beech ki baat (1 sec tak)

    private fun flushPending() {
        val list = synchronized(pending) { pending.toList().also { pending.clear() } }
        list.forEach { sendAudio(it) }
        if (list.isNotEmpty()) log("जुड़ते समय की ${list.size * CHUNK_MS} ms आवाज़ भेजी")
    }

    private fun sendAudio(pcm: ByteArray) {
        val w = ws
        if (w == null || !ready) {
            synchronized(pending) { pending.addLast(pcm); while (pending.size > 2000 / CHUNK_MS) { pending.removeFirst(); dropped++ } }
            return
        }
        // network dheema: 256KB se zyada atka = purani awaaz chhodo (warna der badhti jaati)
        if (w.queueSize() > 256 * 1024) { dropped++; return }
        val msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=$IN_RATE\",\"data\":\"" +
            Base64.encodeToString(pcm, Base64.NO_WRAP) + "\"}}}"
        if (w.send(msg)) { sent++; sentBytes += pcm.size } else dropped++
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
                log("माइक: ${r.sampleRate} Hz, mono, PCM16, source=$src, AEC=$aec NS=$ns AGC=$agc, buffer=${min}B")
                return r
            } catch (e: Exception) { log("माइक source $src: ${e.message}") }
        }
        return null
    }

    /** Dheere bolne par bhi saaf awaaz Gemini tak (v3.7) */
    private val boost = MicBoost()

    private suspend fun micLoop() = coroutineScope {
        var r = openMic() ?: throw Fatal("माइक नहीं खुला (कोई दूसरा ऐप माइक इस्तेमाल कर रहा है?)")
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Fatal("माइक रिकॉर्डिंग शुरू नहीं हुई")
            val buf = ShortArray(CHUNK_SAMPLES)
            val bytes = ByteArray(CHUNK_SAMPLES * 2)
            val zeros = ByteArray(CHUNK_SAMPLES * 2)
            var last = 0L; var zeroRun = 0; var echo = 0.0; var loud = 0; var barge = false; var errRun = 0
            var playSince = 0L
            while (isActive && !stopped) {
                // poora 40ms tukda padho (beech ka khaali hissa = gap)
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
                        r = openMic() ?: throw Fatal("माइक दोबारा नहीं खुला")
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
                // dheemi awaaz tez (v3.7): DK chup ho tab hi - DK ki apni goonj ko tez nahi karna
                if (!isPlaying()) boost.apply(buf, CHUNK_SAMPLES)
                for (i in 0 until CHUNK_SAMPLES) {
                    val s = buf[i].toInt()
                    bytes[i * 2] = (s and 0xff).toByte(); bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()   // little-endian
                }
                if (sum == 0.0) { if (++zeroRun == 75) log("माइक से 3 सेकंड से बिल्कुल आवाज़ नहीं (कोई दूसरा ऐप माइक ले रहा है?)") } else zeroRun = 0
                // DK bol raha ho: apni goonj Gemini ko na bhejo (warna woh khud ko tok deta), par user tez bole to bhejo (barge-in)
                val out = if (isPlaying()) {
                    if (playSince == 0L) playSince = now
                    if (!barge) {
                        // DK ki awaaz shuru hote hi goonj dheere-dheere badhti hai: pehle 700ms sirf goonj seekho (galat "toka" nahi)
                        if (now - playSince < 700) { loud = 0; echo = maxOf(echo * 0.8, rms) }
                        else if (rms > maxOf(echo * 2.2, 900.0)) loud++
                        else { loud = 0; echo = if (echo == 0.0) rms else echo * 0.9 + rms * 0.1 }
                        if (loud >= 4) { barge = true; barging = true; log("बीच में बोलना पकड़ा (rms=${rms.toInt()}, goonj=${echo.toInt()})") }
                    }
                    if (barge) bytes else zeros
                } else {
                    barge = false; loud = 0; barging = false; playSince = 0L; echo = 0.0
                    if (DKState.mode == Mode.LISTENING) DKState.level = (rms / 3500.0).toFloat().coerceIn(0f, 1f)
                    bytes
                }
                val chunk = out.copyOf()
                sendAudio(chunk)
                caption?.send(chunk)
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
                        if (!turnActive) DKState.set(Mode.LISTENING)
                    }
                    continue
                }
                val g = gen
                if (!speakingSet) { speakingSet = true; svc.speaker.ensureAudible(); DKState.set(Mode.SPEAKING) }
                val now = System.currentTimeMillis()
                playEnd = maxOf(playEnd, now) + b.size * 1000L / (OUT_RATE * 2)
                var off = 0
                while (off < b.size && g == gen) {
                    val n = try { tr.write(b, off, b.size - off) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    off += n
                    if (g == gen) turnPlayedBytes += n
                }
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
