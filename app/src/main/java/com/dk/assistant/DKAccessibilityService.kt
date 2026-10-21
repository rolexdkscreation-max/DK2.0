package com.dk.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * DK ka "haath aur aankh":
 *  - screen ke saare buttons/text padhta hai (aankh)
 *  - click, long press, type, scroll, swipe, back/home karta hai (haath)
 *  - screenshot leta hai (Android 11+)
 *  - floating "DK" bubble dikhata hai: tap = bolo, dobara tap = roko
 */
class DKAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: DKAccessibilityService? = null
            private set
    }

    data class Element(val id: Int, val node: AccessibilityNodeInfo, val bounds: Rect)
    data class Screen(val text: String, val packageName: String, val width: Int, val height: Int, val count: Int)

    // Kisi bhi kaam me galti ho to sirf log karo - service crash na ho (crash = Android Accessibility band kar deta hai)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> DKLog.d("Galti (sambhal li): ${e.javaClass.simpleName} ${e.message}") })
    private var elements: List<Element> = emptyList()

    lateinit var speaker: Speaker
        private set
    lateinit var listener: VoiceListener
        private set
    lateinit var agent: AgentRunner
        private set
    lateinit var ear: Ear
        private set

    private var bubble: OrbView? = null
    lateinit var calls: CallManager
        private set
    private var wakeLoopRunning = false

    // ---------------------------------------------------------------- lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        speaker = Speaker(this)
        listener = VoiceListener(this)
        // aap bolna shuru karo aur ghanti baj rahi ho -> ringtone turant dheemi (taaki DK saaf sune)
        ear = Ear(this, { speaker.busy }) { if (::calls.isInitialized && calls.ringing) calls.quietRinger() }
        agent = AgentRunner(this, scope, speaker)
        calls = CallManager(this) { who -> scope.launch { onIncomingCall(who) } }
        calls.start()
        showBubble()
        AppVisible.onChange = { inApp -> bubble?.visibility = if (inApp) android.view.View.GONE else android.view.View.VISIBLE }
        bubble?.visibility = if (AppVisible.visible) android.view.View.GONE else android.view.View.VISIBLE
        DKLog.d("DK accessibility service chalu ho gaya")
        val user = Prefs.userName(this)
        scope.launch {
            val hello = "${Prefs.assistantName(this@DKAccessibilityService)} तैयार है" + (if (user.isNotBlank()) ", $user" else "") + "।"
            DKState.reply(hello)
            speaker.speak(hello)
        }
        if (Prefs.liveMode(this)) startLive() else ensureLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* zarurat nahi, hum on-demand padhte hain */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        DKLog.d("Accessibility service unbind hua")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() { agent.stop() }

    override fun onDestroy() {
        instance = null
        AppVisible.onChange = null
        removeBubble()
        DKState.setLive(false)
        listener.release()
        ear.release()
        calls.stop()
        speaker.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- voice entry points
    /**
     * Orb / mic tap = LIVE MODE ON/OFF.
     * ON hone par DK lagatar sunta rehta hai: aap bolo -> kaam/jawab -> phir se sunna... jab tak aap band na karo.
     */
    fun toggleLive() {
        if (DKState.live) stopLive("ओर्ब/माइक टैप") else startLive()
    }

    fun startLive() {
        DKLog.d("Live चालू (live=${DKState.live}, wake=$wakeLive)")
        if (DKState.live) {
            // wake word se thodi der ka Live chal raha tha -> ab pakka Live (khud band nahi hoga)
            if (wakeLive) { wakeLive = false; gemLive.idleExitMs = 0; Prefs.edit(this) { putBoolean("live_mode", true) } }
            return
        }
        wakeLive = false; gemLive.idleExitMs = 0
        Setup.startKeepAlive(this)
        DKState.setLive(true)
        Prefs.edit(this) { putBoolean("live_mode", true) }
        ensureLoop()
    }

    fun stopLive(why: String = "?") {
        DKLog.d("Live बंद किया: $why")
        wakeLive = false; gemLive.idleExitMs = 0
        DKState.setLive(false)
        Prefs.edit(this) { putBoolean("live_mode", false) }
        gemLive.stop(why)
        agent.stop()
        loopJob?.cancel(); loopJob = null
        listener.release()
        ear.release()
        DKState.set(Mode.IDLE)
        scope.launch {
            speaker.speak("ठीक है, माइक बंद।")
            // "hamesha suno" chalu hai to wake word wala sunna wapas shuru
            delay(600); ensureLoop()
        }
    }

    /** Purana naam - ab live mode toggle karta hai. */
    fun listenAndRun() = toggleLive()

    /** Text command (app ki likhne wali bar se). */
    fun runCommand(text: String, speak: Boolean = true) { agent.run(text, speak) }

    /** Settings save hone par: "hamesha suno (wake word)" mode chalu/band. */
    fun startWakeLoopIfEnabled() { ensureLoop() }

    private var loopJob: kotlinx.coroutines.Job? = null

    /** Silent kaan (apna mic) ya uski key kuch der ke liye kharab -> tab tak Google mic se suno */
    @Volatile private var googleUntil = 0L
    private var micFails = 0
    private val warnedAt = HashMap<String, Long>()

    /** Gemini Live (aawaz se aawaz, real-time) - sabse tez tareeka */
    val gemLive by lazy { GeminiLive(this, agent) }
    @Volatile private var liveOffUntil = 0L
    fun callBusy() = calls.ringing || callPending || callAnnouncing || calls.inCall
    fun useGeminiLive(): Boolean = DKState.live && liveReady()
    /** Gemini Live chal sakta hai? (sunne ka tareeka "auto" + Gemini key + abhi ruka nahi) */
    fun liveReady(): Boolean = Prefs.listenEngine(this) == "auto" &&
        GeminiLive.available(this) && System.currentTimeMillis() > liveOffUntil
    /** Live kyun nahi chal raha - log me saaf likho */
    private fun liveWhyNot(): String = when {
        Prefs.listenEngine(this) != "auto" -> "सेटिंग में सुनने का तरीका '${Prefs.listenEngine(this)}' है (Gemini Live के लिए 'अपने आप' चुनें)"
        !GeminiLive.available(this) -> "Gemini key नहीं है"
        System.currentTimeMillis() <= liveOffUntil -> "पिछली गलती के बाद ${(liveOffUntil - System.currentTimeMillis()) / 1000}s रुका है"
        else -> "-"
    }
    /** Wake word sunke thodi der ke liye Live chalu kiya (chup rahne par wapas wake mode) */
    @Volatile private var wakeLive = false
    private var lastWhyNot = ""

    /** Bina beep wala tareeka chalega? (Groq ya Gemini key chahiye) */
    fun silentEar(): Boolean = Prefs.listenEngine(this) != "google" && Transcriber.available(this) &&
        System.currentTimeMillis() > googleUntil

    /** Ek hi dikkat baar-baar na bole (5 min me ek baar) - screen par likho + bolkar batao */
    private suspend fun warn(id: String, msg: String) {
        val now = System.currentTimeMillis()
        if (now - (warnedAt[id] ?: 0L) < 5 * 60_000L) return
        warnedAt[id] = now
        DKLog.d("Chetavni: $msg")
        DKState.reply(msg)
        speaker.speak(msg)
    }

    /** Mic ki permission hai? Nahi to batao aur app khol kar permission maango. */
    private suspend fun checkMic(): Boolean {
        if (Setup.hasMic(this)) return true
        DKState.set(Mode.IDLE)
        warn("perm", "माइक की परमिशन नहीं है, इसलिए मैं सुन नहीं पा रहा। ऐप खुल रहा है — वहाँ माइक की इजाज़त दीजिए।")
        try {
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ask_mic", true))
        } catch (e: Exception) { DKLog.d("app nahi khula: ${e.message}") }
        return false
    }

    /** Google mic se ek baat (silent kaan na chale tab) */
    private suspend fun hearGoogle(): String? {
        ear.release()
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            warn("norec", "फ़ोन में Google का सुनने वाला ऐप नहीं मिला। Groq की फ्री key डालिए, तो मैं खुद सुनूँगा।")
            delay(3000); return null
        }
        listener.lastSpokeAt = speaker.lastSpokeAt
        DKState.set(Mode.LISTENING)
        return listener.listenOnce()
    }

    /** Ek baat suno -> text. Bina beep (apna mic + Groq/Gemini) ya Google mic. Har dikkat DK bolkar batata hai. */
    suspend fun hear(): String? {
        if (!checkMic()) { delay(2000); return null }
        if (!silentEar()) return hearGoogle()
        // DK ki apni awaaz ki goonj na sune
        val since = System.currentTimeMillis() - speaker.lastSpokeAt
        if (since < 350) delay(350 - since)
        DKState.set(Mode.LISTENING)
        val pcm = ear.nextUtterance()
        if (pcm == null) {
            val prob = ear.problem
            ear.release()
            if (prob == "perm") { checkMic(); return null }
            micFails++
            if (micFails >= 3) {
                micFails = 0
                googleUntil = System.currentTimeMillis() + 2 * 60_000L
                warn("mic_" + prob, if (prob == "silent") "मेरे माइक में आवाज़ नहीं आ रही — शायद कोई दूसरा ऐप माइक इस्तेमाल कर रहा है। अभी Google माइक से सुन रहा हूँ।"
                    else "मेरा माइक नहीं खुल रहा, इसलिए अभी Google माइक से सुन रहा हूँ।")
                return hearGoogle()
            }
            delay(400); return null
        }
        micFails = 0
        DKState.set(Mode.THINKING)
        DKState.user("…")
        val sttT0 = System.currentTimeMillis()
        val text = Transcriber.transcribe(this, pcm)
        DKLog.d("Deri: bolna khatam -> STT ${System.currentTimeMillis() - sttT0} ms (${pcm.size / 32} ms awaaz)")
        if (text == null && Transcriber.lastError != null) {
            // sunne wali key nahi chali: aapki baat kho gayi - batao aur Google mic par chalo (2 min)
            googleUntil = System.currentTimeMillis() + 2 * 60_000L
            DKState.user("")
            warn("stt", "सुनने वाली key अभी नहीं चली (${Transcriber.lastError}). अभी Google माइक से सुन रहा हूँ, एक बार फिर बोलिए।")
            return null
        }
        return text
    }

    /** Agent ne "ask" se sawaal poocha hai aur jawab sun raha hai (tab beech-me-tokne wala kaan chup) */
    @Volatile private var agentHearing = false
    @Volatile private var bargeListen: kotlinx.coroutines.Deferred<String?>? = null

    suspend fun hearForAgent(): String? {
        agentHearing = true
        try {
            bargeListen?.cancelAndJoin()
            return hear()
        } finally { agentHearing = false }
    }

    /** Suni hui baat DK ki apni hi awaaz (goonj) to nahi? */
    private fun isEcho(heard: String): Boolean {
        val dk = (speaker.currentChunk + " " + speaker.spokenSoFar().takeLast(300)).let { LocalBrain.norm(it) }
        if (dk.isBlank()) return false
        val dkWords = dk.split(' ').filter { it.length > 1 }.toSet()
        val hw = LocalBrain.norm(heard).split(' ').filter { it.length > 1 }
        if (hw.isEmpty()) return true
        val same = hw.count { it in dkWords }
        return if (hw.size <= 2) same == hw.size else same >= hw.size * 0.6
    }

    /** DK bol/soch raha hai - us dauraan bhi suno. User bole to DK chup, baat suno, phir dono milakar jawab. */
    private suspend fun listenBarge(): String? {
        if (silentEar()) {
            val pcm = ear.nextUtterance(barge = true, onStart = { if (speaker.busy) speaker.pause() }, onDiscard = { speaker.resume() })
                ?: run { speaker.resume(); delay(300); return null }
            val text = Transcriber.transcribe(this, pcm)
            if (text.isNullOrBlank() || text.trim(' ', '.', '।', ',').length < 2 || isEcho(text)) {
                DKLog.d("Beech ki awaaz goonj/shor thi: $text"); speaker.resume(); return null
            }
            return text
        }
        // Google mic: pehle apne kaan se pata karo ki user bolne laga, phir Google se suno
        ear.nextUtterance(barge = true, vadOnly = true, onStart = { if (speaker.busy) speaker.pause() }) ?: run { delay(300); return null }
        ear.release()
        listener.lastSpokeAt = speaker.lastSpokeAt
        val text = listener.listenOnce()
        if (text.isNullOrBlank() || isEcho(text)) { speaker.resume(); return null }
        return text
    }

    /** Kaam/jawab chalne do, saath me suno - beech me toka to naya (milaya hua) jawab. */
    private suspend fun runWithBarge(first: kotlinx.coroutines.Job) {
        var job = first
        while (job.isActive) {
            if (!DKState.live || agentHearing || callAnnouncing || calls.ringing || calls.inCall) {
                withTimeoutOrNull(300) { job.join() }; continue
            }
            val heard: String? = coroutineScope {
                val l = async { listenBarge() }
                bargeListen = l
                try {
                    select<String?> {
                        l.onAwait { it }
                        // kaam khatam - par user beech me bol raha ho to uski baat poori suno
                        job.onJoin { if (ear.capturing) l.await() else { l.cancel(); null } }
                    }
                } finally { bargeListen = null }
            }
            if (heard == null) continue
            val low = heard.lowercase()
            if (GeminiLive.micOffCommand(low)) { stopLive("बोला: ${heard.take(60)}"); return }
            job = agent.interrupt(heard)
        }
    }

    /** Ek hi sunne wala loop: live mode me har baat command hai, wake mode me sirf wake word ke baad. */
    private fun ensureLoop() {
        val wanted = DKState.live || Prefs.wakeMode(this)
        if (!wanted || loopJob?.isActive == true) return
        loopJob = scope.launch {
            DKLog.d("Sunna shuru (live=${DKState.live}, wake=${Prefs.wakeMode(this@DKAccessibilityService)}, Gemini Live=${if (liveReady()) "तैयार" else "नहीं: " + liveWhyNot()})")
            listener.muteBeep = true
            var misses = 0
            while (isActive && (DKState.live || Prefs.wakeMode(this@DKAccessibilityService))) {
                if (agent.isBusy || speaker.busy || DKState.mode == Mode.SPEAKING || callAnnouncing) { delay(250); continue }
                // call chal rahi hai: mic call ka hai - DK ka mic chhod do (warna saamne wale ko awaaz nahi jaati)
                if (calls.inCall && !calls.ringing) { ear.release(); listener.release(); DKState.set(Mode.IDLE); delay(1000); continue }
                // Gemini Live: mic ki awaaz lagatar stream, jawab ki awaaz turant stream (sabse kam deri)
                if (useGeminiLive() && !callBusy()) {
                    if (!checkMic()) { delay(2000); continue }
                    ear.release(); listener.release()
                    lastWhyNot = ""
                    var failed = false
                    try {
                        val r = gemLive.run()
                        DKLog.d("Gemini Live ruka: $r")
                    } catch (e: GeminiLive.Fatal) {
                        failed = true
                        liveOffUntil = System.currentTimeMillis() + 10 * 60_000L
                        DKState.set(Mode.IDLE)
                        warn("live", "Gemini Live नहीं चला: ${e.message}। अभी पुराने तरीके से सुन रहा हूँ।")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        failed = true
                        liveOffUntil = System.currentTimeMillis() + 2 * 60_000L
                        DKLog.d("Gemini Live गलती: ${e.javaClass.simpleName} ${e.message}")
                    }
                    if (wakeLive) {
                        // wake word wala chhota Live khatam -> wapas wake word sunna
                        wakeLive = false; gemLive.idleExitMs = 0
                        DKState.setLive(false); DKState.set(Mode.IDLE)
                        val left = gemLive.firstText; gemLive.firstText = null
                        if (failed && !left.isNullOrBlank()) agent.run(left).join()   // Live nahi chala to purane tareeke se jawab
                    }
                    continue
                }
                if (DKState.live) {
                    val why = liveWhyNot()
                    if (why != lastWhyNot) { lastWhyNot = why; DKLog.d("Gemini Live इस्तेमाल नहीं हो रहा: $why") }
                }
                val heard = hear()
                if (DKState.mode == Mode.LISTENING || DKState.mode == Mode.THINKING) DKState.set(Mode.IDLE)
                if (listener.lastError == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    // Google mic ko permission nahi (Google app ki) - apne kaan par wapas
                    googleUntil = 0L
                    if (!silentEar()) warn("gperm", "Google माइक को परमिशन नहीं मिली। Google ऐप को माइक की इजाज़त दीजिए, या Groq की फ्री key डालिए।")
                    delay(1500)
                }
                if (heard == null) {
                    misses++
                    delay(if (misses > 20) 600 else 150)   // chup hai to thoda aaram
                    continue
                }
                misses = 0
                val low = heard.lowercase().trim()

                // ghanti baj rahi hai -> jo bhi bolo woh call ka jawab (wake word ki zarurat nahi)
                if (calls.ringing || callPending) {
                    if (handleCallReply(heard)) continue
                    if (calls.ringing) continue      // call ke dauraan baaki baatein nahi
                }

                if (DKState.live) {
                    if (low.trim(' ', '.', '।').length < 2) continue
                    if (GeminiLive.micOffCommand(low) || low.contains("stop listening") || low == "बंद करो") {
                        stopLive("बोला: ${heard.take(60)}"); break
                    }
                    // gaana/video chal raha ho to sirf naam ("DK ...") ke saath boli baat command maano
                    val am = getSystemService(android.media.AudioManager::class.java)
                    val othersPlaying = am?.isMusicActive == true && System.currentTimeMillis() - speaker.lastSpokeAt > 2500
                    var cmdText = heard
                    if (othersPlaying) {
                        val w = Prefs.wakeWords(this@DKAccessibilityService).firstOrNull { low.contains(it) } ?: continue
                        cmdText = heard.substring((low.indexOf(w) + w.length).coerceAtMost(heard.length)).trim(' ', ',', '.', '!')
                        if (cmdText.isBlank()) continue
                    }
                    Ducker.duck(this@DKAccessibilityService, "बात शुरू")
                    runWithBarge(agent.run(cmdText))
                    Ducker.releaseWhenQuiet({ !speaker.busy })
                } else {
                    // wake word mode
                    val wake = Prefs.wakeWords(this@DKAccessibilityService).firstOrNull { low.contains(it) } ?: continue
                    var cmd = heard.substring((low.indexOf(wake) + wake.length).coerceAtMost(heard.length))
                        .trim(' ', ',', '.', '!')
                    // naam suna = user DK se baat kar raha hai -> peeche ka gaana/video dheema (Live ho to jawab ke baad wahi wapas badhayega)
                    Ducker.duck(this@DKAccessibilityService, "wake word")
                    // naam suna -> Gemini Live (asli awaaz, turant jawab); 45s chup = wapas wake word
                    if (liveReady() && !callBusy() && checkMic()) {
                        DKLog.d("Wake word suna -> Gemini Live" + if (cmd.isNotBlank()) " (बात: $cmd)" else "")
                        wakeLive = true
                        gemLive.idleExitMs = 45_000
                        gemLive.firstText = cmd.ifBlank { null }
                        if (cmd.isBlank()) DKState.set(Mode.LISTENING)
                        DKState.setLive(true)
                        continue
                    }
                    if (cmd.isBlank()) {
                        speaker.speak("हाँ, बोलिए।")
                        DKState.set(Mode.LISTENING)
                        cmd = hear() ?: ""
                        DKState.set(Mode.IDLE)
                    }
                    if (cmd.isNotBlank()) agent.run(cmd).join()
                    Ducker.releaseWhenQuiet({ !speaker.busy })
                }
            }
            listener.muteBeep = false
            DKLog.d("Sunna band")
        }
    }

    // ---------------------------------------------------------------- screen reading (aankh)
    fun currentPackage(): String = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }

    /**
     * Action ke baad screen ke thamne ka intezaar (pehle seedha 650ms tha - YouTube jaisa app tab tak khulta bhi nahi tha,
     * AI ko purani screen dikhti thi aur woh app dobara kholta tha = 10-20 sec bekaar).
     * newApp=true: jab tak naya app saamne na aaye; phir jab tak screen ke elements badalna band na ho.
     */
    suspend fun waitSettle(prevPkg: String, newApp: Boolean, maxMs: Long = 4000L) {
        val t0 = System.currentTimeMillis()
        delay(if (newApp) 450 else 300)
        var last = Int.MIN_VALUE
        while (System.currentTimeMillis() - t0 < maxMs) {
            val pkg = currentPackage()
            if (newApp && (pkg.isBlank() || pkg == prevPkg || pkg == packageName)) { delay(200); continue }
            val sig = try { readScreen().text.hashCode() } catch (_: Exception) { 0 }
            if (sig == last) break
            last = sig
            delay(250)
        }
        DKLog.d("Screen thami ${System.currentTimeMillis() - t0} ms (${currentPackage()})")
    }

    fun readScreen(): Screen {
        val list = mutableListOf<Element>()
        val sb = StringBuilder()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) { }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

        for (root in roots) walk(root, list, sb, 0)
        elements = list

        val dm = resources.displayMetrics
        val pkg = rootInActiveWindow?.packageName?.toString() ?: "unknown"
        return Screen(sb.toString(), pkg, dm.widthPixels, dm.heightPixels, list.size)
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<Element>, sb: StringBuilder, depth: Int) {
        if (out.size >= 140 || depth > 40) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val useful = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable ||
                node.isEditable || node.isScrollable || node.isLongClickable
            if (useful) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    val id = out.size
                    out.add(Element(id, node, r))
                    val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    val flags = buildList {
                        if (node.isClickable) add("click")
                        if (node.isLongClickable) add("long")
                        if (node.isEditable) add("edit")
                        if (node.isScrollable) add("scroll")
                        if (node.isChecked) add("checked")
                        if (node.isSelected) add("selected")
                        if (node.isFocused) add("focused")
                    }.joinToString(",")
                    sb.append("[").append(id).append("] ").append(cls)
                    if (text.isNotEmpty()) sb.append(" \"").append(text.take(50)).append("\"")
                    if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(50)).append(")")
                    if (flags.isNotEmpty()) sb.append(" {").append(flags).append("}")
                    sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, sb, depth + 1)
        }
    }

    /** Screenshot (Android 11+), chhota JPEG base64 me - AI ko "dikhane" ke liye. */
    suspend fun screenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val out = try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (bmp == null) null else {
                                val w = 540
                                val h = (bmp.height * (w.toFloat() / bmp.width)).toInt()
                                val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                                val bos = ByteArrayOutputStream()
                                small.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            }
                        } catch (e: Exception) { null }
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onFailure(errorCode: Int) {
                        DKLog.d("Screenshot fail: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ---------------------------------------------------------------- actions (haath)
    suspend fun perform(a: JSONObject): String {
        return try {
            when (a.optString("type")) {
                "show_face" -> {
                    DKState.showFace()
                    try {
                        startActivity(android.content.Intent(this, VoiceActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("face", true))
                    } catch (_: Exception) {}
                    "ok"
                }
                "screen_view" -> { Prefs.edit(this) { putBoolean("screenshot", a.optBoolean("on", true)) }; "ok" }
                "open_app" -> AppLauncher.open(this, a.optString("name"))
                "youtube_search" -> AppLauncher.youtubeSearch(this, a.optString("query"))
                "youtube_play" -> {
                    val q = a.optString("query")
                    val id = kotlinx.coroutines.withContext(Dispatchers.IO) { AppLauncher.youtubeFirstVideo(q) }
                    AppLauncher.youtubePlay(this, q, id)
                }
                "web_search" -> AppLauncher.webSearch(this, a.optString("query"))
                "open_url" -> AppLauncher.openUrl(this, a.optString("url"))
                "dial" -> AppLauncher.dial(this, a.optString("number"))
                "click" -> click(a.optInt("id", -1))
                "long_click" -> longClick(a.optInt("id", -1))
                "type" -> typeText(if (a.has("id")) a.optInt("id", -1) else -1, a.optString("text"))
                "enter" -> pressEnter()
                "scroll" -> scroll(a.optString("direction", "down"))
                "tap" -> if (tap(a.optInt("x").toFloat(), a.optInt("y").toFloat())) "ok" else "ERROR tap"
                "swipe" -> if (swipe(
                        a.optInt("x1").toFloat(), a.optInt("y1").toFloat(),
                        a.optInt("x2").toFloat(), a.optInt("y2").toFloat(), 350
                    )) "ok" else "ERROR swipe"
                "back" -> global(GLOBAL_ACTION_BACK)
                "home" -> global(GLOBAL_ACTION_HOME)
                "recents" -> global(GLOBAL_ACTION_RECENTS)
                "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> global(GLOBAL_ACTION_QUICK_SETTINGS)
                "shop_search" -> AppLauncher.shopSearch(this, a.optString("app"), a.optString("query"))
                "torch" -> DeviceActions.torch(this, if (a.has("on")) a.optBoolean("on") else null)
                "open_settings" -> DeviceActions.openSettings(this, a.optString("page"))
                "panel" -> DeviceActions.panel(this, a.optString("name", "internet"))
                "volume" -> DeviceActions.volume(this, a.optString("direction", "up"), a.optInt("level", -1),
                    a.optString("stream").ifBlank { if (calls.ringing) "ring" else if (calls.inCall) "call" else "music" })
                "directions" -> Maps.directions(this, a.optString("to"), a.optString("from"), a.optString("mode", "driving"))
                "navigate" -> Maps.navigate(this, a.optString("to"), a.optString("mode", "driving"))
                "map_search" -> Maps.search(this, a.optString("query"))
                "distance" -> distance(a.optString("to"), a.optString("from"), a.optString("mode", "driving"))
                "media" -> DeviceActions.media(this, a.optString("key", "play_pause"))
                "lock" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN) else "ERROR: Android 9+ chahiye"
                "screenshot_save" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT) else "ERROR"
                "power_menu" -> global(GLOBAL_ACTION_POWER_DIALOG)
                "unlock", "wake" -> unlock()
                "answer_call" -> answerCall(a.optBoolean("speaker", false))
                "end_call" -> endCall()
                "speaker" -> setSpeaker(a.optBoolean("on", true))
                "who_calls" -> whoCalls()
                "close_app" -> closeApp()
                "close_all_apps" -> closeAllApps()
                "wait" -> { delay(a.optLong("ms", 1000).coerceIn(100, 8000)); "waited" }
                else -> "ERROR: unknown action ${a.optString("type")}"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun element(id: Int): Element? = elements.getOrNull(id)

    private suspend fun click(id: Int): String {
        val e = element(id) ?: return "ERROR: id $id screen par nahi hai"
        var n: AccessibilityNodeInfo? = e.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "clicked $id"
        return if (tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) "tapped $id" else "ERROR click $id"
    }

    private suspend fun longClick(id: Int): String {
        val e = element(id) ?: return "ERROR: id $id screen par nahi hai"
        var n: AccessibilityNodeInfo? = e.node
        while (n != null && !n.isLongClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "long clicked $id"
        val x = e.bounds.exactCenterX(); val y = e.bounds.exactCenterY()
        return if (swipe(x, y, x, y, 900)) "long pressed $id" else "ERROR long click $id"
    }

    private suspend fun typeText(id: Int, text: String): String {
        var target: AccessibilityNodeInfo? = element(id)?.node
        if (target != null && !target.isEditable) {
            click(id); delay(500)
            target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: target
        }
        if (target == null) target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) return "ERROR: koi text box nahi mila"
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed" else "ERROR type"
    }

    private fun pressEnter(): String {
        val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "ERROR: focused box nahi"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ok = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return "enter pressed"
        }
        return "ERROR: enter support nahi, search button click karo"
    }

    private suspend fun scroll(dir: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val ok = when (dir) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f, 400)
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f, 400)
            "left" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2, 300)
            "right" -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2, 300)
            else -> false
        }
        return if (ok) "scrolled $dir" else "ERROR scroll"
    }

    /** Screen jagao; swipe-lock ho to khol do. PIN/pattern/fingerprint user khud dalega (suraksha). */
    @Suppress("DEPRECATION")
    private suspend fun unlock(): String {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            try {
                val wl = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "dk:wake")
                wl.acquire(4000)
            } catch (e: Exception) { DKLog.d("wake: ${e.message}") }
            delay(700)
        }
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) return "screen on hai, phone pehle se unlocked hai"
        val dm = resources.displayMetrics
        swipe(dm.widthPixels / 2f, dm.heightPixels * 0.88f, dm.widthPixels / 2f, dm.heightPixels * 0.3f, 300)
        delay(700)
        return when {
            !km.isKeyguardLocked -> "phone unlock ho gaya"
            km.isKeyguardSecure -> "screen on, lock screen PIN/pattern/fingerprint maang rahi hai - user ko bolo ki khud khole (DK PIN nahi daalta)"
            else -> "lock screen khuli"
        }
    }

    // ---------------------------------------------------------------- calls
    /** Call ka elaan chal raha hai (tab sunne wala loop ruka rahe, DK apni hi awaaz na sune) */
    @Volatile private var callAnnouncing = false
    /** Call aayi aur abhi jawab (उठाओ / काटो) nahi mila */
    @Volatile private var callPending = false

    /**
     * Call aate hi:
     *  1. ringtone dheemi (taaki DK ki awaaz aur aapki baat dono saaf)
     *  2. "<naam> का कॉल आ रहा है। उठाऊँ या काट दूँ?"
     *  3. aapka jawab suno (wake word ki zarurat nahi) -> uthao / kaato / speaker / ringtone band
     */
    private suspend fun onIncomingCall(who0: String) {
        if (!Prefs.announceCalls(this)) return
        callPending = true
        agent.stop()
        calls.quietRinger()
        delay(500)
        var who = who0
        if (who.isBlank()) who = callerFromScreen()
        if (!calls.ringing && calls.known) { callPending = false; return }
        val msg = if (who.isNotBlank()) "$who का कॉल आ रहा है। उठाऊँ या काट दूँ?" else "किसी का कॉल आ रहा है। उठाऊँ या काट दूँ?"
        DKState.reply(msg)
        callAnnouncing = true
        try { speaker.speakQuick(msg) } finally { callAnnouncing = false }   // phone ki apni awaaz = bina der

        // Sunne wala loop chalu hai to wahi jawab sambhalega. Nahi to yahin suno (jab tak ghanti baje, max ~30 sec)
        if (loopJob?.isActive == true) {
            scope.launch { while (calls.ringing) delay(500); callPending = false }
            return
        }
        val until = System.currentTimeMillis() + 30_000
        var asked = 1
        try {
            while (calls.ringing && callPending && System.currentTimeMillis() < until) {
                DKState.set(Mode.LISTENING)
                val ans = hear()
                DKState.set(Mode.IDLE)
                if (!calls.ringing) break
                if (ans == null) {
                    // chup hain -> ek baar aur poochho
                    if (asked < 2 && System.currentTimeMillis() > until - 18_000) {
                        asked++; callAnnouncing = true
                        try { speaker.speakQuick("कॉल उठाऊँ?") } finally { callAnnouncing = false }
                    }
                    continue
                }
                if (handleCallReply(ans)) break
            }
        } finally { callPending = false; DKState.set(Mode.IDLE) }
    }

    /** Ghanti ke dauraan boli baat: true = samajh kar kaam kar diya. */
    private suspend fun handleCallReply(heard: String): Boolean {
        val l = " " + heard.lowercase().trim().trim('.', '।', '!', '?') + " "
        fun has(vararg w: String) = w.any { l.contains(it) }
        DKState.user(heard)
        val speakerOn = has("स्पीकर", "speaker", "लाउड")
        val pick = has("उठा", "रिसीव", "receive", "answer", "पिक", "pick", "accept", "स्वीकार", "बात करा", "बात करनी", "जोड़ो", "जोड़ दो") ||
            Regex("(^| )(हाँ|हां|हा|हम्म|yes|yeah|ok|ओके|ठीक है|जी|जी हाँ|जी हां|बिलकुल|बिल्कुल|उठाओ)( |$)").containsMatchIn(l.trim())
        val cut = has("काट", "कट ", "कट", "reject", "decline", "मना", "रख दो", "बाद में", "अभी नहीं", "नहीं उठा", "मत उठा", "बिज़ी", "busy") ||
            Regex("(^| )(नहीं|नही|ना|no|nahi)( |$)").containsMatchIn(l.trim())
        val quiet = has("चुप", "साइलेंट", "silent", "ringtone बंद", "रिंगटोन बंद", "घंटी बंद", "आवाज़ बंद", "आवाज बंद", "आवाज़ कम", "आवाज कम", "धीरे")
        val who = has("कौन", "किसका", "who")
        return when {
            // "मत उठाओ" / "नहीं उठाना" = kaato (pehle check)
            has("मत उठा", "नहीं उठा", "ना उठा") -> { agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "end_call"), "कॉल काट दिया")).join(); callPending = false; true }
            pick && !has("काट", "reject") -> {
                agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "answer_call").put("speaker", speakerOn),
                    if (speakerOn) "कॉल उठा कर स्पीकर पर डाल रहा हूँ" else "कॉल उठा रहा हूँ")).join()
                callPending = false; true
            }
            cut -> { agent.runQuick(QuickCommands.Quick(JSONObject().put("type", "end_call"), "कॉल काट दिया")).join(); callPending = false; true }
            quiet -> {
                DeviceActions.volume(this, "set", 0, "ring")
                DKState.reply("रिंगटोन धीमी कर दी।"); speaker.speakQuick("रिंगटोन धीमी कर दी।"); false
            }
            who -> {
                val name = calls.caller.ifBlank { callerFromScreen() }
                val m = if (name.isNotBlank()) "$name का कॉल है। उठाऊँ?" else "नंबर पता नहीं चला। उठाऊँ?"
                DKState.reply(m); speaker.speakQuick(m); false
            }
            else -> {
                // "आवाज़ बढ़ाओ" jaisi seedhi command chalne do
                val q = QuickCommands.parse(heard, Prefs.wakeWords(this))
                if (q != null) { agent.runQuick(q).join(); q.action.optString("type") in listOf("answer_call", "end_call") } else false
            }
        }
    }

    /** Doori naapo: Maps me rasta kholo, phir screen se km + samay padho. */
    private suspend fun distance(to: String, from: String, mode: String): String {
        val r = Maps.directions(this, to, from, mode)
        if (r.startsWith("ERROR")) return r
        val until = System.currentTimeMillis() + 12_000
        delay(2500)
        while (System.currentTimeMillis() < until) {
            val sc = readScreen()
            if (sc.packageName.contains("maps")) Maps.readDistance(sc.text)?.let { return "DISTANCE: $it" }
            delay(800)
        }
        return "maps route opened, distance screen par dikh rahi hai par padh nahi paya"
    }

    /** Call wali screen se naam padho (contacts permission na ho tab). */
    private fun callerFromScreen(): String {
        return try {
            val root = rootInActiveWindow ?: return ""
            val texts = mutableListOf<String>()
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 25) return
                n.text?.toString()?.trim()?.let { if (it.length in 2..40) texts.add(it) }
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(root, 0)
            val skip = listOf("incoming", "call", "कॉल", "answer", "decline", "उत्तर", "अस्वीकार", "message", "मैसेज", "reply", "remind", "swipe", "स्वाइप", "mobile", "मोबाइल", "sim", "hd")
            texts.firstOrNull { t -> skip.none { t.lowercase().contains(it) } && !t.contains(":") } ?: ""
        } catch (e: Exception) { "" }
    }

    /** Saari windows (notification popup, call screen) me button dhoondo aur dabao. */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { out.add(it) } } } catch (_: Exception) {}
        rootInActiveWindow?.let { if (out.none { r -> r == it }) out.add(0, it) }
        return out
    }

    private fun clickByText(vararg words: String): Boolean {
        for (root in allRoots()) for (w in words) {
            for (n in root.findAccessibilityNodeInfosByText(w)) {
                var c: AccessibilityNodeInfo? = n; var depth = 0
                while (c != null && !c.isClickable && depth < 4) { c = c.parent; depth++ }
                if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    /** View-id se (jaise "answer_button") button dabao. */
    private fun clickById(vararg parts: String): Boolean {
        fun walk(n: AccessibilityNodeInfo?, d: Int): Boolean {
            if (n == null || d > 30) return false
            val id = n.viewIdResourceName?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            if (parts.any { id.contains(it) || desc.contains(it) }) {
                var c: AccessibilityNodeInfo? = n; var k = 0
                while (c != null && !c.isClickable && k < 3) { c = c.parent; k++ }
                if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            for (i in 0 until n.childCount) if (walk(n.getChild(i), d + 1)) return true
            return false
        }
        return allRoots().any { walk(it, 0) }
    }

    private suspend fun answerCall(speakerOn: Boolean): String {
        if (calls.inCall && !calls.ringing) {
            if (speakerOn) setSpeaker(true)
            return "call pehle se chal rahi hai" + if (speakerOn) ", speaker on" else ""
        }
        // 1) Android ka seedha tareeka (permission chahiye)
        var ok = calls.answer()
        delay(700)
        if (calls.known && calls.inCall) ok = true
        // 2) Call popup / call screen ka "Answer" button
        if (!(calls.known && calls.inCall)) {
            ok = clickByText("Answer", "Accept", "ANSWER", "उत्तर दें", "उत्तर", "स्वीकार करें", "स्वीकार", "जवाब दें", "रिसीव", "उठाएं") ||
                clickById("answer", "accept", "incoming_call_accept") || ok
            delay(700)
        }
        // 3) "Swipe up to answer" wali screen
        if (!(calls.known && calls.inCall) && isCallScreen()) {
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.85f, dm.widthPixels / 2f, dm.heightPixels * 0.35f, 300)
            delay(800)
            ok = ok || (calls.known && calls.inCall)
        }
        // 4) ek baar aur seedha tareeka (kuch phone pehli baar me nahi uthate)
        if (calls.known && !calls.inCall && calls.ringing) { calls.answer(); delay(900) }
        calls.restoreRinger()
        if (calls.known) ok = calls.inCall
        if (!ok) return if (!calls.canAnswer()) "ERROR_CALL_PERM" else "ERROR: call nahi uthi"
        if (speakerOn) { delay(1500); setSpeaker(true) }
        return "call utha li" + (if (speakerOn) " aur speaker on" else "")
    }

    private fun isCallScreen(): Boolean {
        val p = rootInActiveWindow?.packageName?.toString()?.lowercase() ?: return false
        return listOf("dialer", "incall", "telecom", "phone", "contacts").any { p.contains(it) }
    }

    private suspend fun endCall(): String {
        calls.restoreRinger()
        if (calls.end()) return "call kaat di"
        if (clickByText("End call", "Decline", "Reject", "DECLINE", "कॉल समाप्त करें", "कॉल समाप्त", "समाप्त", "अस्वीकार करें", "अस्वीकार", "काटें", "कट करें", "Hang up") ||
            clickById("decline", "reject", "end_call", "endcall", "hangup")) return "call kaat di"
        return if (!calls.canAnswer()) "ERROR_CALL_PERM" else "ERROR: call nahi kati"
    }

    private suspend fun setSpeaker(on: Boolean): String {
        // Pehle call screen ka "Speaker" button (sabse pakka), phir system tareeka
        val clicked = clickByText("Speaker", "स्पीकर", "Speakerphone", "लाउडस्पीकर")
        if (!clicked) calls.speakerApi(on)
        return "speaker ${if (on) "on" else "off"}"
    }

    private fun whoCalls(): String {
        if (!calls.ringing && !calls.inCall) return "abhi koi call nahi hai"
        val who = calls.caller.ifBlank { callerFromScreen() }
        return if (who.isBlank()) "caller ka naam pata nahi chala" else "CALLER: $who"
    }

    /** Abhi wala app band karo: recents me card upar fenko. */
    private suspend fun closeApp(): String {
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return "ERROR recents"
        delay(900)
        val dm = resources.displayMetrics
        swipe(dm.widthPixels / 2f, dm.heightPixels * 0.5f, dm.widthPixels / 2f, dm.heightPixels * 0.05f, 220)
        delay(500)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "closed recent app ${pkg ?: ""}"
    }

    /** Sab apps band: recents me "Clear all / सभी बंद करें" dabao. */
    private suspend fun closeAllApps(): String {
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return "ERROR recents"
        delay(1000)
        val root = rootInActiveWindow ?: return "ERROR recents screen"
        val words = listOf("clear all", "close all", "सभी साफ़", "सब हटाएँ", "सभी बंद", "सभी हटाएं", "सभी साफ करें", "x")
        for (w in words) {
            val n = root.findAccessibilityNodeInfosByText(w).firstOrNull { it.isClickable || it.parent?.isClickable == true }
            if (n != null) {
                (if (n.isClickable) n else n.parent).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return "cleared all recent apps"
            }
        }
        return "recents khula hai, 'Clear all' button nahi mila - screen dekh kar dabao"
    }

    private fun global(action: Int): String = if (performGlobalAction(action)) "ok" else "ERROR"

    private suspend fun tap(x: Float, y: Float): Boolean = swipe(x, y, x, y, 60)

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    // ---------------------------------------------------------------- floating bubble
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (76 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 20
            y = resources.displayMetrics.heightPixels / 3
        }
        val v = OrbView(this).apply { mini = true }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    // call chal rahi ho to bubble tap = call kaato (call ke dauraan Android mic nahi deta)
                    if (calls.inCall || calls.ringing) scope.launch { perform(JSONObject().put("type", "end_call")) }
                    else toggleLive()
                }
            }
            true
        }
        wm.addView(v, params)
        bubble = v
    }

    private fun removeBubble() {
        bubble?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        bubble = null
    }
}
