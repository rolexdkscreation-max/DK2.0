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
import kotlinx.coroutines.withContext
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
    /** v3.57: सुनते/बोलते समय किनारों की चमक (छूने पर कुछ नहीं, नीचे का ऐप चलता रहे) */
    private var edgeGlow: com.dk.assistant.fx.EdgeGlowView? = null
    lateinit var calls: CallManager
        private set
    private var wakeLoopRunning = false

    // ---------------------------------------------------------------- lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // v3.39: Bluetooth माइक अब BtAudio से (सिर्फ़ headset जुड़ा हो तब, सुनते/बात करते समय). पहले (PR #48) यहाँ
        // पूरा फ़ोन हमेशा के लिए "कॉल" मोड में चला जाता था - Bluetooth न हो तब भी, और कभी वापस नहीं.

        speaker = Speaker(this)
        // v3.51: घंटी/कॉल के समय Android media आवाज़ mute करता है -> DK तब accessibility आवाज़ में बोले
        speaker.callNow = { try { calls.ringing || calls.inCall || callPending } catch (_: Exception) { false } }
        listener = VoiceListener(this)
        // aap bolna shuru karo aur ghanti baj rahi ho -> ringtone turant dheemi (taaki DK saaf sune)
        ear = Ear(this, { speaker.busy }) { if (::calls.isInitialized && calls.ringing) calls.quietRinger() }
        agent = AgentRunner(this, scope, speaker)
        calls = CallManager(this) { who -> scope.launch { onIncomingCall(who) } }
        calls.start()
        showEdgeGlow()
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
        removeEdgeGlow()
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
        // DK AI Part 13: access key चालू नहीं = लाइव शुरू नहीं (लाइसेंस सेट नहीं = पहले जैसा चलता है)
        com.dk.assistant.license.AccessKeyManager.refusalOrNull(this)?.let { msg ->
            DKLog.d("Live: access key चालू नहीं - शुरू नहीं किया")
            DKState.reply(msg); return
        }
        calls.start()     // v3.40: Phone की इजाज़त बाद में दी हो तो भी अब call पता चले (पहले सिर्फ़ service शुरू होते समय)
        if (DKState.live) {
            // wake word se thodi der ka Live chal raha tha -> ab pakka Live (khud band nahi hoga)
            if (wakeLive) { wakeLive = false; gemLive.idleExitMs = 0; Prefs.edit(this) { putBoolean("live_mode", true) } }
            return
        }
        wakeLive = false; gemLive.idleExitMs = 0
        Prefs.edit(this) { putBoolean("mic_user_off", false) }    // v3.59: यूज़र ने खुद चालू किया
        Setup.startKeepAlive(this)
        // wake word wala loop hear() me atka ho (baat ka intezaar / STT) to Live tab tak shuru nahi hota tha -> use roko
        val restart = LiveStart.restartLoop(loopJob?.isActive == true, wasLive = false)
        DKState.setLive(true)
        Prefs.edit(this) { putBoolean("live_mode", true) }
        if (restart) {
            DKLog.d("Live: wake word वाला सुनना रोका - Gemini Live तुरंत")
            loopJob?.cancel(); loopJob = null
            ear.release(); listener.release(); earCaptionOff()
        }
        ensureLoop()
    }

    /**
     * v3.45: ऐप खुला - DK पहले बोले (सच्चा दोस्त). 20 मिनट में दोबारा नहीं. Live बंद हो तो चालू करता है.
     * ताज़ा लोकेशन + मौसम पहले (9s तक), फिर Gemini को SYSTEM NOTE.
     */
    @Volatile private var greeting = false
    fun friendOpen() {
        if (greeting || !Prefs.friendOpener(this) || !Prefs.friendMode(this)) return
        // v3.59: आपने माइक बंद किया था - ऐप खुलने पर DK खुद माइक चालू नहीं करेगा
        if (!DKState.live && Prefs.micUserOff(this)) return
        if (GeminiLive.key(this).isBlank() || !Setup.hasMic(this)) return
        if (calls.ringing || calls.inCall) return
        if (!FriendTalk.shouldGreet(this)) return
        // अभी-अभी बात चल रही थी (Live खुला रहा, सारांश अभी बना नहीं) - बीच में "नमस्ते" नहीं
        val lastMsg = DKState.messages.lastOrNull()?.time ?: 0L
        if (System.currentTimeMillis() - lastMsg < 20 * 60_000L) return
        greeting = true
        FriendTalk.markGreeted(this)
        scope.launch {
            try {
                val note = FriendTalk.openerNote(this@DKAccessibilityService)
                if (!DKState.live) startLive()
                else if (wakeLive) { wakeLive = false; gemLive.idleExitMs = 0 }
                val sent = gemLive.greetNow(note)
                DKLog.d("दोस्त: ऐप खुला - DK खुद बोलेगा" + if (sent) "" else " (जुड़ते ही)")
            } catch (e: Exception) { DKLog.d("दोस्त: शुरुआत नहीं हो पाई: ${e.message}") }
            finally { greeting = false }
        }
    }

    fun stopLive(why: String = "?") {
        DKLog.d("Live बंद किया: $why")
        wakeLive = false; gemLive.idleExitMs = 0
        DKState.setLive(false)
        // v3.59: बंद = बंद। जड़: बंद करने के 0.6s बाद "हमेशा सुनो" वाला wake-word loop माइक फिर खोल देता था,
        // और ऐप खोलते ही "दोस्त" वाला अभिवादन Live खुद चालू कर देता था। अब यूज़र के चालू करने तक माइक बंद।
        Prefs.edit(this) { putBoolean("live_mode", false); putBoolean("mic_user_off", true) }
        gemLive.stop(why)
        agent.stop()
        loopJob?.cancel(); loopJob = null
        listener.release()
        ear.release()
        DKState.set(Mode.IDLE)
        scope.launch {
            speaker.speak("ठीक है, माइक बंद।")
            // v3.59: wake word वाला सुनना भी नहीं - माइक तभी खुलेगा जब आप बटन दबाएँ या लाइव चालू करें
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
    /** Lagatar kitni baar Live network/mic ki thodi-der wali dikkat se ruka (backoff ke liye) */
    @Volatile private var liveSoftFails = 0
    fun callBusy() = calls.ringing || callPending || callAnnouncing || calls.inCall
    /**
     * v3.40: call उठ चुकी (चल रही) = माइक call का -> तभी DK लाइव रुकता है. घंटी के समय Live खुला रहता है:
     * वही पूछता है "उठाऊँ या काट दूँ?" और आपके कहने पर उठाता / स्पीकर / काटता है (पहले घंटी बजते ही Live बंद हो जाता था).
     */
    fun callTakesMic() = calls.inCall && !calls.ringing
    fun callRinging() = calls.ringing || callPending
    fun callerName() = calls.caller.ifBlank { callerFromScreen() }

    /** DK लाइव से call का काम (CallReply.ACTIONS). कोई call न हो तो कुछ नहीं दबाता ("Decline" जैसा कोई और बटन न दब जाए) */
    suspend fun liveCallAction(action: String): String {
        if (!calls.ringing && !calls.inCall && !callPending) return "NO_CALL"
        val r = when (action) {
            CallReply.ANSWER -> answerCall(false)
            CallReply.ANSWER_SPEAKER -> answerCall(true)
            CallReply.REJECT -> endCall()
            CallReply.SPEAKER_ON -> if (calls.inCall) setSpeaker(true) else answerCall(true)
            CallReply.SPEAKER_OFF -> setSpeaker(false)
            CallReply.WHO -> whoCalls()
            else -> "ERROR: unknown action $action"
        }
        if (action != CallReply.WHO && !r.startsWith("ERROR")) callPending = false
        DKLog.d("Live: call $action -> $r")
        return r
    }

    /** v3.74: पहले DK Android का "Start" खुद दबा देता था (बिना इजाज़त स्क्रीन). अब कभी नहीं - user खुद दबाए. */
    fun autoTapShareStart() {
        if (!ScreenGuard.AUTO_TAP_SHARE_START) { DKLog.d("स्क्रीन शेयर: Start बटन user खुद दबाएगा"); return }
    }

    private fun exactClickable(n: AccessibilityNodeInfo?, words: Set<String>, d: Int): AccessibilityNodeInfo? {
        if (n == null || d > 25) return null
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
        if (t != null && t in words) {
            var c: AccessibilityNodeInfo? = n; var up = 0
            while (c != null && !c.isClickable && up < 3) { c = c.parent; up++ }
            if (c != null && c.isClickable && c.isEnabled) return c
        }
        for (i in 0 until n.childCount) exactClickable(n.getChild(i), words, d + 1)?.let { return it }
        return null
    }
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

    /**
     * v3.7: purane (bina beep) tareeke me bhi bolte-bolte shabd screen par.
     * Gemini key ho to mic ki awaaz (sirf jab aap bol rahe ho) tez caption model ko bhi jaati hai.
     */
    private var earCaption: LiveCaption? = null
    @Volatile private var earShowing = false
    private fun earCaptionOn() {
        val key = Prefs.captionKey(this)   // v3.41: likhne ki apni key (ho to)
        if (key.isBlank()) { ear.onFrame = null; return }
        val c = earCaption ?: LiveCaption(key, onText = { t, _ -> if (earShowing && t.isNotBlank()) DKState.user("$t…") }, onLate = { })
            .also { it.start(); earCaption = it }
        ear.onFrame = { b, n ->
            val out = ByteArray(n * 2)
            for (i in 0 until n) { val v = b[i].toInt(); out[i * 2] = (v and 0xff).toByte(); out[i * 2 + 1] = (v shr 8 and 0xff).toByte() }
            c.send(out)
        }
    }
    private fun earCaptionOff() { ear.onFrame = null; earCaption?.stop(); earCaption = null }

    @Volatile private var ownerSeenAt = 0L
    private var othersIgnored = 0

    /** v3.66: आवाज़ मालिक की है? (पहचान बंद / मॉडल गड़बड़ = हाँ, आवाज़ मत खोओ) */
    private suspend fun ownerHeard(pcm: ByteArray): Boolean {
        if (!com.dk.assistant.voice.OwnerVoice.active(this)) return true
        val G = com.dk.assistant.voice.OwnerGate.Companion
        val cut = pcm.copyOf(minOf(pcm.size, G.RATE * 2 * G.MAX_MS / 1000))
        val ms = cut.size * 1000 / (G.RATE * 2)
        if (ms < G.MIN_CHECK_MS) return System.currentTimeMillis() - ownerSeenAt < G.RECENT_OWNER_MS
        val t0 = System.currentTimeMillis()
        val sc = withContext(Dispatchers.Default) { try { com.dk.assistant.voice.OwnerVoice.score(this@DKAccessibilityService, cut) } catch (_: Throwable) { null } }
            ?: return true
        val now = System.currentTimeMillis()
        var th = com.dk.assistant.voice.VoicePrint.threshold(Prefs.ownerVoiceStrict(this))
        if (ms < G.SHORT_MS) th -= G.SHORT_SLACK
        if (now - ownerSeenAt < G.RECENT_OWNER_MS) th -= G.RECENT_SLACK
        val ok = sc >= th
        val txt = String.format(java.util.Locale.US, "%.2f", sc)
        if (ok) { ownerSeenAt = now; DKLog.d("आवाज़ मिलान $txt - मालिक (${now - t0} ms)") }
        else { othersIgnored++; DKLog.d("किसी और की आवाज़ (मिलान $txt) - अनसुनी (#$othersIgnored)") }
        return ok
    }

    /** Ek baat suno -> text. Bina beep (apna mic + Groq/Gemini) ya Google mic. Har dikkat DK bolkar batata hai. */
    /** @param wakeGate wake word वाला सुनना (Live बंद): caption में DK का नाम न हो तो Groq को मत भेजो */
    suspend fun hear(wakeGate: Boolean = false): String? {
        if (!checkMic()) { delay(2000); return null }
        if (DKState.micMuted) { delay(800); return null }   // होम का म्यूट बटन: कुछ मत सुनो
        if (!silentEar()) return hearGoogle()
        // DK ki apni awaaz ki goonj na sune
        val since = System.currentTimeMillis() - speaker.lastSpokeAt
        if (since < 350) delay(350 - since)
        DKState.set(Mode.LISTENING)
        earCaptionOn()
        earCaption?.closeUtterance()          // pichhli (beech me toki) baat ka text saaf
        earShowing = true
        val pcm = try { ear.nextUtterance() } catch (e: Exception) { earShowing = false; throw e }
        if (pcm == null) {
            earShowing = false
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
        // v3.66: सिर्फ़ मालिक की आवाज़ - किसी और की बात STT/जवाब तक नहीं (फ़ोन पर ही, ~50 ms)
        if (!ownerHeard(pcm)) {
            earShowing = false
            earCaption?.closeUtterance()
            DKState.user("")
            DKState.set(Mode.IDLE)
            return null
        }
        DKState.set(Mode.THINKING)
        // caption ne bolte-bolte jo likha woh screen par rehne do (pehle "…" se mit jaata tha)
        val capNow = earCaption?.text().orEmpty()
        // wake mode: caption ne saaf baat suni aur usme DK ka naam nahi -> Groq par bhejna bekaar (deri + limit)
        if (wakeGate && SttTiming.skipWakeStt(capNow, Prefs.wakeWords(this))) {
            earShowing = false
            earCaption?.closeUtterance()
            if (capNow.isNotBlank()) DKState.user("")      // background ki baat screen par na rahe
            DKState.set(Mode.IDLE)
            DKLog.d("Wake: caption में DK का नाम नहीं - STT नहीं भेजा (${pcm.size / 32} ms awaaz)")
            return null
        }
        if (capNow.isBlank()) DKState.user("…")
        val sttT0 = System.currentTimeMillis()
        // STT alag chalao: 3s me na aaye aur caption ke paas saaf shabd hon -> caption ka text (pehle 16-28s intezaar)
        val stt = scope.async(Dispatchers.IO) { Transcriber.transcribe(this@DKAccessibilityService, pcm) }
        var fromCaption: String? = null
        val text0 = try {
            val quick = withTimeoutOrNull(SttTiming.CAPTION_FALLBACK_MS) { stt.await() }
            if (quick != null || stt.isCompleted) quick
            else {
                val cap = earCaption?.text().orEmpty().trim()
                if (SttTiming.captionUsable(cap)) { fromCaption = cap; null } else stt.await()
            }
        } finally { earShowing = false }
        val capText = earCaption?.closeUtterance().orEmpty()
        if (fromCaption != null) DKLog.d("STT ${SttTiming.CAPTION_FALLBACK_MS} ms में नहीं आया - caption का text लिया")
        // Whisper ne kuch na diya par caption ne suna -> wahi lo (baat na khoye)
        val text = text0 ?: fromCaption ?: capText.trim().takeIf { it.length >= 2 }
        DKLog.d("Deri: bolna khatam -> STT ${System.currentTimeMillis() - sttT0} ms (${pcm.size / 32} ms awaaz)")
        if (text == null && fromCaption == null && Transcriber.lastError != null) {
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

    /**
     * इनपुट बार में बोलकर लिखना (DKMic): DK का अपना सुनना माइक छोड़ दे, वरना दोनों में से एक को आवाज़ नहीं मिलती.
     * Live चालू हो तो GeminiLive का micLoop खुद माइक छोड़ता/वापस लेता है (session बना रहता है).
     */
    fun onDictation(on: Boolean) {
        if (on) {
            if (!DKState.live) {
                loopJob?.cancel(); loopJob = null
                ear.release(); listener.release(); earCaptionOff()
            }
        } else ensureLoop()
    }

    /** Ek hi sunne wala loop: live mode me har baat command hai, wake mode me sirf wake word ke baad. */
    private fun ensureLoop() {
        val wanted = DKState.live || (Prefs.wakeMode(this) && !Prefs.micUserOff(this))
        if (::calls.isInitialized) calls.start()      // इजाज़त बाद में मिली हो तो अब (पहले से चालू हो तो कुछ नहीं)
        if (!wanted || loopJob?.isActive == true) return
        loopJob = scope.launch {
            DKLog.d("Sunna shuru (live=${DKState.live}, wake=${Prefs.wakeMode(this@DKAccessibilityService)}, Gemini Live=${if (liveReady()) "तैयार" else "नहीं: " + liveWhyNot()})")
            listener.muteBeep = true
            var misses = 0
            while (isActive && (DKState.live || (Prefs.wakeMode(this@DKAccessibilityService) && !Prefs.micUserOff(this@DKAccessibilityService)))) {
                // v3.59: म्यूट (wake word वाले सुनने में) = माइक छोड़ दो
                if (DKState.micMuted && !DKState.live) { ear.release(); listener.release(); earCaptionOff(); delay(400); continue }
                if (agent.isBusy || speaker.busy || DKState.mode == Mode.SPEAKING || callAnnouncing) { delay(250); continue }
                // आप इनपुट बार में बोलकर लिख रहे हैं - माइक आपका (DK यह बात command नहीं मानता)
                if (DKMic.dictating && !useGeminiLive()) { ear.release(); listener.release(); delay(300); continue }
                // call chal rahi hai: mic call ka hai - DK ka mic chhod do (warna saamne wale ko awaaz nahi jaati)
                if (calls.inCall && !calls.ringing) { ear.release(); listener.release(); DKState.set(Mode.IDLE); delay(1000); continue }
                // Gemini Live: mic ki awaaz lagatar stream, jawab ki awaaz turant stream (sabse kam deri)
                if (useGeminiLive() && !callBusy()) {
                    if (!checkMic()) { delay(2000); continue }
                    ear.release(); listener.release(); earCaptionOff()
                    lastWhyNot = ""
                    var failed = false
                    val liveStart = System.currentTimeMillis()
                    try {
                        val r = gemLive.run()
                        liveSoftFails = 0
                        DKLog.d("Gemini Live ruka: $r")
                    } catch (e: GeminiLive.Fatal) {
                        failed = true
                        // 5 min+ theek chala tha = purani dikkatein ginti se hatao (backoff shuru se)
                        if (System.currentTimeMillis() - liveStart > 5 * 60_000L) liveSoftFails = 0
                        // key/limit/model = asli rukawat (10 min). Network/mic busy = thodi der: 15s, 30s, 60s ... 5 min tak
                        // (pehle har baar 10 min ke liye Live band = "kabhi chalta hai kabhi nahi", beech me purana dheema tareeka)
                        val offMs = LiveRetry.offMs(e.recoverable, liveSoftFails)
                        if (e.recoverable) liveSoftFails++
                        liveOffUntil = System.currentTimeMillis() + offMs
                        DKLog.d("Gemini Live ruka (${if (e.recoverable) "थोड़ी देर की दिक्कत #$liveSoftFails" else "असली रुकावट"}) - ${offMs / 1000}s बाद फिर कोशिश: ${e.message}")
                        DKState.set(Mode.IDLE)
                        warn("live", "Gemini Live नहीं चला: ${e.message}। अभी पुराने तरीके से सुन रहा हूँ।")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        failed = true
                        liveOffUntil = System.currentTimeMillis() + 2 * 60_000L
                        DKLog.d("Gemini Live गलती: ${e.javaClass.simpleName} ${e.message}")
                    }
                    // v3.45: बातचीत खत्म -> कब, कितनी देर, सारांश, अधूरी बातें (फ़ोन पर)
                    try { FriendTalk.sessionEnd(this@DKAccessibilityService, liveStart, scope) } catch (_: Exception) {}
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
                val liveBefore = DKState.live
                val heard = hear(wakeGate = !liveBefore)
                if (DKState.mode == Mode.LISTENING || DKState.mode == Mode.THINKING) DKState.set(Mode.IDLE)
                // sunte-sunte Live chalu hua: yeh baat Live se pehle ki (background) - command nahi, Gemini Live khud sunega
                if (heard != null && LiveStart.staleHeard(liveBefore, DKState.live) && !wakeLive) {
                    DKLog.d("Live से पहले की बात छोड़ी (command नहीं): \"${heard.take(40)}\"")
                    continue
                }
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
                agent.heardAt = System.currentTimeMillis()
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
            earCaptionOff()
            DKLog.d("Sunna band")
        }
    }

    // ---------------------------------------------------------------- screen reading (aankh)
    fun currentPackage(): String = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }

    /** v3.33: काम हुआ या नहीं की जाँच - [maxMs] तक सामने वाला ऐप देखो (main thread नहीं रुकता, सिर्फ़ यह coroutine) */
    suspend fun waitForeground(maxMs: Long, ok: (String) -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < maxMs) {
            if (ok(currentPackage())) return true
            delay(150)
        }
        return false
    }

    /** DK खुद बोल रहा है? (तब "गाना बज रहा" की जाँच भरोसे लायक नहीं) */
    private fun selfAudio(): Boolean = try { speaker.busy || gemLive.isPlaying() } catch (_: Exception) { false }

    /**
     * v3.33: YouTube वीडियो सच में चला? - YouTube सामने हो और फ़ोन पर media आवाज़ चल रही हो (DK की अपनी आवाज़ नहीं).
     * "खोल दिया" को "चल रहा है" नहीं कहते.
     */
    private suspend fun verifyYoutube(r: String): String {
        if (!r.startsWith("PLAYING")) return r
        val t0 = System.currentTimeMillis()
        val am = getSystemService(android.media.AudioManager::class.java)
        var front = false
        while (System.currentTimeMillis() - t0 < 5000) {
            if (currentPackage() == LocalRouter.YT_PKG) front = true
            if (front && am?.isMusicActive == true && !selfAudio()) {
                DKLog.d("जाँच: YouTube वीडियो चल रहा है (${System.currentTimeMillis() - t0} ms)")
                return "$r (verified: playing)"
            }
            delay(200)
        }
        DKLog.d("जाँच: YouTube ${if (front) "सामने खुला, पर आवाज़ नहीं मिली" else "सामने नहीं दिखा"} (${System.currentTimeMillis() - t0} ms)")
        return if (front) "VIDEO_OPENED: YouTube पर वीडियो खोल दिया, पर चलना शुरू हुआ या नहीं - पक्का नहीं दिखा।"
        else "VIDEO_OPENED: वीडियो खोलने को कह दिया, पर YouTube सामने खुला नहीं दिखा।"
    }

    /** v3.33: ऐप सच में सामने आया? (नतीजे पर निशान; न दिखे तो भी झूठा "खुल गया" नहीं) */
    private suspend fun verifyOpen(r: String): String {
        if (!r.startsWith("opened")) return r
        val pkg = AppLauncher.lastPkg
        if (pkg.isBlank()) return r
        val t0 = System.currentTimeMillis()
        val ok = waitForeground(2500) { it == pkg }
        DKLog.d("जाँच: ऐप ${if (ok) "सामने आ गया" else "सामने नहीं दिखा"} (${System.currentTimeMillis() - t0} ms)")
        return if (ok) "$r (verified)" else "$r (not confirmed on screen)"
    }

    /**
     * v3.33: कई कदम एक-एक करके; हर कदम के बाद जाँच, कोई कदम न हो / पूछना पड़े तो वहीं रुको (आगे झूठा "हो गया" नहीं).
     * सिर्फ़ तय action types (nested "steps" नहीं), ज़्यादा से ज़्यादा 6.
     */
    private suspend fun performSteps(arr: org.json.JSONArray?): String {
        if (arr == null || arr.length() == 0) return "ERROR: steps khali"
        if (arr.length() > 6) return "ERROR: bahut saare steps"
        val done = mutableListOf<String>()
        var last = ""
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: return "ERROR: step ${i + 1} galat"
            val type = st.optString("type")
            if (type == "steps") return "ERROR: nested steps nahi"
            val prev = currentPackage()
            val r = perform(st)
            DKLog.d("कदम ${i + 1}/${arr.length()} $type -> ${r.take(80)}")
            if (r.startsWith("ASK:") || r.startsWith("ERROR")) {
                val why = LocalRouter.userText(r) ?: r.take(100)
                return if (r.startsWith("ASK:")) r
                else "STEP_FAILED: ${if (done.isEmpty()) "" else done.joinToString(", ") + " हो गया, पर "}कदम ${i + 1} ($type) नहीं हुआ: $why"
            }
            done.add(LiveFast.describe(st)); last = r
            if (i < arr.length() - 1 && type in listOf("open_app", "shop_search", "youtube_search", "open_url", "web_search")) waitSettle(prev, true, 3500)
        }
        return "STEPS_DONE: " + done.joinToString(", ") + "। " + (LocalRouter.userText(last) ?: "")
    }

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
                "screen_view" -> {
                    // v3.74: चालू सिर्फ़ user की अपनी बात/बटन से - AI अपने-आप स्क्रीन नहीं खोल सकता
                    val on = a.optBoolean("on", true)
                    if (!ScreenGuard.allowSet(on, a.optBoolean(ScreenGuard.BY_USER, false))) {
                        DKLog.d("स्क्रीन देखना: AI ने खुद चालू करना चाहा - रोका (user की इजाज़त नहीं)")
                        "ERROR: screen viewing can only be turned on by the user (say \"स्क्रीन देखना चालू करो\" or Settings)"
                    } else { Prefs.edit(this) { putBoolean("screenshot", on); putBoolean(ScreenGuard.USER_OK_KEY, on) }; "ok" }
                }
                "open_app" -> verifyOpen(AppLauncher.open(this, a.optString("name")))
                "youtube_search" -> AppLauncher.youtubeSearch(this, a.optString("query"))
                "youtube_play" -> {
                    val q = a.optString("query")
                    val id = kotlinx.coroutines.withContext(Dispatchers.IO) { AppLauncher.youtubeFirstVideo(q) }
                    verifyYoutube(AppLauncher.youtubePlay(this, q, id))
                }
                // v3.33: फ़ोन के सीधे काम (बिना AI)
                "call_contact" -> PhoneActions.callContact(this, a.optString("name"))
                "call_log" -> PhoneActions.callLog(this)
                "open_dialer" -> PhoneActions.openDialer(this)
                "gallery_latest" -> PhoneActions.latestPhoto(this)
                "delete_media" -> MediaDelete.latest(this, a.optString("kind", "photo"), a.optInt("count", 1))
                "steps" -> performSteps(a.optJSONArray("steps"))
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
        delay(200)   // v3.41: 500 -> 200 ms (call screen aane bhar ka samay; naam phone ki list se pehle hi)
        var who = who0
        if (who.isBlank()) who = callerFromScreen()
        if (!calls.ringing && calls.known) { callPending = false; return }
        val msg = if (who.isNotBlank()) "$who का कॉल आ रहा है। उठाऊँ या काट दूँ?" else "किसी का कॉल आ रहा है। उठाऊँ या काट दूँ?"
        DKState.reply(msg)
        // v3.40: DK लाइव (Gemini) जुड़ा है = वही पूछे और जवाब सुने (Live बंद नहीं होता, पुराने सुनने पर निर्भर नहीं)
        if (DKState.live && gemLive.canTalk()) {
            // v3.51: पहले Gemini (Live) खुद पूछता था - पर उसकी आवाज़ media पर है और घंटी के समय Android उसे mute कर देता है:
            // "बोल दिया" दिखता (isPlaying) पर सुनाई कुछ नहीं, फ़ोन वाली आवाज़ भी नहीं चलती थी = सिर्फ़ लिखा दिखता.
            // अब फ़ोन खुद (accessibility आवाज़, घंटी में भी सुनाई दे) पूछता है; Live को बस बताया जाता है ताकि जवाब वही सुने.
            gemLive.noteCallAsked(who)
            gemLive.muteMic(true)
            callAnnouncing = true
            try { speaker.speakQuick(msg) } finally { callAnnouncing = false; gemLive.muteMic(false) }
            // पूछने के बाद Live की आवाज़ भी घंटी में सुनाई दे ("उठा रहा हूँ" / "किसका है" का जवाब)
            gemLive.callAudio(true)
            DKLog.d("कॉल: फ़ोन की आवाज़ से पूछा (Live जवाब सुन रहा है)")
            scope.launch { while (calls.ringing) delay(500); callPending = false; if (!calls.inCall) gemLive.callAudio(false) }
            return
        }
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
        // v3.41: tay deri (700/700/800/900/1500 ms) ki jagah "uthi ya nahi" har 100 ms dekho - uthte hi aage
        suspend fun answered(ms: Long) = withTimeoutOrNull(ms) { while (!(calls.known && calls.inCall)) delay(100) } != null
        // 1) Android ka seedha tareeka (permission chahiye)
        var ok = calls.answer()
        if (answered(1000)) ok = true
        // 2) Call popup / call screen ka "Answer" button
        if (!(calls.known && calls.inCall)) {
            ok = clickByText("Answer", "Accept", "ANSWER", "उत्तर दें", "उत्तर", "स्वीकार करें", "स्वीकार", "जवाब दें", "रिसीव", "उठाएं") ||
                clickById("answer", "accept", "incoming_call_accept") || ok
            answered(900)
        }
        // 3) "Swipe up to answer" wali screen
        if (!(calls.known && calls.inCall) && isCallScreen()) {
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.85f, dm.widthPixels / 2f, dm.heightPixels * 0.35f, 300)
            answered(900)
            ok = ok || (calls.known && calls.inCall)
        }
        // 4) ek baar aur seedha tareeka (kuch phone pehli baar me nahi uthate)
        if (calls.known && !calls.inCall && calls.ringing) { calls.answer(); answered(900) }
        calls.restoreRinger()
        if (calls.known) ok = calls.inCall
        if (!ok) return if (!calls.canAnswer()) "ERROR_CALL_PERM" else "ERROR: call nahi uthi"
        if (speakerOn) {
            // call screen ka Speaker button aane do (pehle 1.5s fixed), phir ek baar aur pakka (kuch phone shuru me speaker hata dete)
            delay(400); setSpeaker(true)
            scope.launch { delay(1500); if (calls.inCall && calls.speakerIsOn() != true) setSpeaker(true) }
        }
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
        // Button TOGGLE hai - bina haalat dekhe dabane se "speaker off" par speaker ON ho jaata tha
        val btn = speakerButton()
        if (btn == null) { calls.speakerApi(on); return "speaker ${if (on) "on" else "off"}" }
        val now: Boolean? = if (btn.isCheckable) btn.isChecked else calls.speakerIsOn()
        if (now == on) return "speaker pehle se ${if (on) "on" else "off"} hai"
        if (now == null || !btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) calls.speakerApi(on)
        return "speaker ${if (on) "on" else "off"}"
    }

    /** Call screen ka "Speaker" button (clickable node) - na mile to null */
    private fun speakerButton(): AccessibilityNodeInfo? {
        for (root in allRoots()) for (w in listOf("Speaker", "स्पीकर", "Speakerphone", "लाउडस्पीकर")) {
            for (n in root.findAccessibilityNodeInfosByText(w)) {
                var c: AccessibilityNodeInfo? = n; var depth = 0
                while (c != null && !c.isClickable && depth < 4) { c = c.parent; depth++ }
                if (c != null && c.isClickable) return c
            }
        }
        return null
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

    internal suspend fun tap(x: Float, y: Float): Boolean = swipe(x, y, x, y, 60)

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

    private fun showEdgeGlow() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val v = com.dk.assistant.fx.EdgeGlowView(this)
            wm.addView(v, params)
            edgeGlow = v
        } catch (e: Exception) { DKLog.d("Edge glow नहीं लगा: ${e.javaClass.simpleName}") }
    }

    private fun removeEdgeGlow() {
        edgeGlow?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        edgeGlow = null
    }

    private fun removeBubble() {
        bubble?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        bubble = null
    }
}
