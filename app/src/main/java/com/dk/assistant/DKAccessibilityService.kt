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

// फ़ाइलें (नवंबर 2026 में बँटवारा, व्यवहार वही): यहाँ सारी state + lifecycle + बाहर से बुलाए जाने वाले functions।
// बाकी हिस्से इसी class के extension functions हैं:
//  - DKAccessibilityCalls.kt  : आने वाली कॉल, उठाना/काटना, म्यूट, स्पीकर, मना करने का संदेश
//  - DKAccessibilityScreen.kt : स्क्रीन पढ़ना, खोज, टाइप, टैप/स्वाइप, अनलॉक, ऐप बंद करना
//  - DKAccessibilityHear.kt   : माइक जाँच, Google से सुनना, गूँज, बीच में टोकना, backup मोड
// नया हिस्सा बने तो test की AccSrc.PARTS में भी जोड़ें (source-check tests सारे हिस्से पढ़ते हैं)।
/**
 * DK ka "haath aur aankh":
 *  - screen ke saare buttons/text padhta hai (aankh)
 *  - click, long press, type, scroll, swipe, back/home karta hai (haath)
 *  - screenshot leta hai (Android 11+)
 *  - ऐप से बाहर नीचे बीच में एक तैरता ORB (FloatOrb.kt): tap = bolo, dobara tap = roko; ✕ नहीं (छिपाना Settings से)
 */
class DKAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: DKAccessibilityService? = null
            private set
        /** v3.156: AI को एक बार में इतनी चीज़ें (पहले 140, और सिर्फ़ शुरू की) */
        const val SCREEN_CAP = 180
    }

    data class Element(val id: Int, val node: AccessibilityNodeInfo, val bounds: Rect)
    data class Screen(val text: String, val packageName: String, val width: Int, val height: Int, val count: Int)

    // Kisi bhi kaam me galti ho to sirf log karo - service crash na ho (crash = Android Accessibility band kar deta hai)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> DKLog.d("Galti (sambhal li): ${e.javaClass.simpleName} ${e.message}") })
    internal var elements: List<Element> = emptyList()

    lateinit var speaker: Speaker
        private set
    lateinit var listener: VoiceListener
        private set
    lateinit var agent: AgentRunner
        private set
    lateinit var ear: Ear
        private set

    /** नवंबर 2026: सिर्फ़ एक तैरता ORB (नीचे बीच में) - पुराना किनारे वाला bubble हटाया */
    private var floatOrb: FloatOrb? = null
    /** नवंबर 2026: DK काम करे तो हर ऐप के ऊपर "DK क्या कर रहा है" पट्टी + स्क्रीन जगी ([WorkAwake]) */
    private var workAwake: WorkAwake? = null
    /** नवंबर 2026: रील गिनती + ऊपर छोटी पट्टी + रोज़ की लिमिट (ReelRules) */
    private var reelWatch: ReelWatch? = null
    /** v3.57: सुनते/बोलते समय किनारों की चमक (छूने पर कुछ नहीं, नीचे का ऐप चलता रहे) */
    internal var edgeGlow: com.dk.assistant.fx.EdgeGlowView? = null
    lateinit var calls: CallManager
        private set
    private var wakeLoopRunning = false

    // ---------------------------------------------------------------- lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try { PayGuard.onServiceBack(this) } catch (_: Exception) {}
        try { PhoneLockStore.reload(this) } catch (_: Exception) {}
        try { Guard.resume(this) } catch (e: Exception) { DKLog.d("गार्ड: ${e.message}") }   // नवंबर 2026: गार्ड मोड चालू था तो फिर से पहरा
        // power बटन वाला "Yes Boss" पहले से बना लो (Live चालू हो तो नहीं - तब Live बंद होने पर)
        com.dk.assistant.voice.LiveAckMaker.ensureAsync(this, "DK शुरू", 15_000L)   // आवाज़ से फ़ोन लॉक / अनलॉक के नाम-वाक्य
        // v3.39: Bluetooth माइक अब BtAudio से (सिर्फ़ headset जुड़ा हो तब, सुनते/बात करते समय). पहले (PR #48) यहाँ
        // पूरा फ़ोन हमेशा के लिए "कॉल" मोड में चला जाता था - Bluetooth न हो तब भी, और कभी वापस नहीं.

        speaker = Speaker(this)
        // v3.51: घंटी/कॉल के समय Android media आवाज़ mute करता है -> DK तब accessibility आवाज़ में बोले
        speaker.callNow = { try { calls.ringing || calls.inCall || callPending } catch (_: Exception) { false } }
        listener = VoiceListener(this)
        // aap bolna shuru karo aur ghanti baj rahi ho -> ringtone turant dheemi (taaki DK saaf sune)
        ear = Ear(this, { speaker.busy }) {
            if (::calls.isInitialized && calls.ringing) calls.quietRinger()
            // v4.6 मालिक: कॉल का एलान चल रहा हो और आप बोल पड़ें ("डीके फोन उठा लो") - DK तुरंत चुप, आपकी बात सुने
            callBargeAt = if (callBargeEar()) System.currentTimeMillis() else 0L
            if (callBargeAt > 0) try { speaker.stop() } catch (_: Exception) {}
        }
        ear.bargeWhen = { callBargeEar() }
        agent = AgentRunner(this, scope, speaker)
        OfflineQueue.drain(this)        // v3.88: पिछली बार नेटवर्क न होने से रुका काम
        calls = CallManager(this) { who -> scope.launch { onIncomingCall(who) } }
        CallCmd.liveCheck = { calls.ringing || calls.inCall }   // v4.1: घंटी/कॉल में कॉल-कमांड बिना DK
        calls.start()
        scope.launch { speaker.warmCallVoice() }   // v4.2: "सर, कॉल आ रहा है" पहले से तैयार - limit / नेट न हो तब भी कॉल पर चुप नहीं
        showEdgeGlow()
        // एक ही छोटा ORB - सिर्फ़ ऐप से बाहर (ऐप खुला = नहीं, OrbRules). ऐप खुलने/बंद होने पर sync
        // ✕ अब नहीं: पुराने ✕ से छिपा ORB सिर्फ़ एक बार वापस. उसके बाद Settings का switch ही मालिक - ऐप खुलने पर ज़बरदस्ती चालू नहीं
        if (!Prefs.orbNoXMigrated(this)) { Prefs.setFloatOrbOn(this, true); Prefs.setOrbNoXMigrated(this) }
        floatOrb = FloatOrb(this) { onOrbTap() }.also { it.sync() }
        // 12 नवंबर 2026 (मालिक): DK Island (ऊपर), DK Privacy, Live की notification में कॉल काटें / म्यूट
        try { DKIsland.start(this) } catch (e: Exception) { DKLog.d("island: ${e.message}") }
        try { PrivacyScreen.start(this) } catch (e: Exception) { DKLog.d("प्राइवेसी: ${e.message}") }
        try { LiveNotify.install(this) } catch (e: Exception) { DKLog.d("लाइव notification: ${e.message}") }
        workAwake = WorkAwake(this).also { it.start() }
        reelWatch = ReelWatch(this).also { it.start() }
        AppVisible.onChange = { _ -> refreshOrb() }
        DKLog.d("DK accessibility service chalu ho gaya")
        A11yWatch.cancel(this)          // नवंबर 2026: "फ़ोन कंट्रोल रुक गया" वाली notification हटाओ
        val user = Prefs.userName(this)
        liveOffUntil = 0L; liveSoftFails = 0; liveOffSoft = false
        scope.launch {
            val hello = "${Prefs.assistantName(this@DKAccessibilityService)} तैयार है" + (if (user.isNotBlank()) ", $user" else "") + "।"
            DKState.reply(hello)
            speaker.speak(hello)
        }
        // v3.81: "Hey DK" चालू = फ़ोन restart / ऐप बंद के बाद भी सुनने वाली notification-service फिर से
        if (com.dk.assistant.voice.HeyDk.on(this) && !Prefs.micUserOff(this)) Setup.startKeepAlive(this)
        if (Prefs.liveMode(this)) startLive() else ensureLoop()
        // v4.6: नेट लौटते ही टूटा Live फिर जोड़ो (इंतज़ार नहीं)
        try { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(netBack) } catch (_: Exception) {}
        // v5.1: स्क्रीन चालू होते ही Gemini Live के host का DNS+TLS पहले से (Live सत्र नहीं, माइक नहीं; हर स्क्रीन-चालू में एक बार)
        LiveNet.register(this)
        // नवंबर 2026: DK Inspector - service चालू रहे तब तक पक्का हर 20 मिनट पूरे ऐप की जाँच (alarm Android टाल सकता है)
        com.dk.assistant.inspector.Inspector.startTimer(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // बाकी सब on-demand पढ़ते हैं. नवंबर 2026: पेमेंट ऐप खुला = फ़ोन कंट्रोल अपने-आप बंद (PayGuard)
        if (event != null) try { PayGuard.onEvent(this, event) } catch (e: Exception) { DKLog.d("PayGuard: ${e.message}") }
        if (event != null) try { Guard.onEvent(this, event) } catch (e: Exception) { DKLog.d("गार्ड: ${e.message}") }   // नवंबर 2026: लॉक में पावर मेनू / PIN खाना
        if (event != null) try { PrivacyScreen.onEvent(this, event) } catch (e: Exception) { DKLog.d("प्राइवेसी: ${e.message}") }   // सामने वाला ऐप बदला
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        DKLog.d("Accessibility service unbind hua")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() { agent.stop() }

    override fun onDestroy() {
        try { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(netBack) } catch (_: Exception) {}
        LiveNet.unregister(this)
        com.dk.assistant.inspector.Inspector.stopTimer()
        exitBackup("सेवा बंद")
        instance = null
        AppVisible.onChange = null
        floatOrb?.release(); floatOrb = null
        try { DKIsland.stop(); PrivacyScreen.stop(); LiveNotify.release(this) } catch (_: Exception) {}
        workAwake?.release(); workAwake = null
        reelWatch?.release(); reelWatch = null
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

    /** नवंबर 2026: power बटन वाली एक बार की बातचीत चल रही है (चुप = Live बंद, वापस "Hey DK") */
    @Volatile internal var sessionLive = false

    fun startLive() {
        DKLog.d("Live चालू (live=${DKState.live}, wake=$wakeLive)")
        // खुद Live चालू किया (बटन/ओर्ब) = हमेशा वाला, एक बार वाली बातचीत नहीं
        if (sessionLive) { sessionLive = false; gemLive.idleExitMs = 0; Prefs.edit(this) { putBoolean("live_mode", true) } }
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
        liveNow("Live चालू")                                       // v4.6: टैप = अभी जोड़ो (पिछली गलती का इंतज़ार नहीं)
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
     * v5.1: power बटन (default assistant) / DK स्क्रीन खुली = Live अभी, UI के साथ-साथ (UI का इंतज़ार नहीं).
     * माइक की इजाज़त न हो = कुछ नहीं (स्क्रीन इजाज़त का बटन दिखाती है). Accessibility यहाँ कभी चालू नहीं की जाती.
     */
    /**
     * नवंबर 2026 (मालिक): power बटन = Google Assistant जैसी एक बार की बातचीत. चुप होने के कुछ देर बाद Live खुद बंद और
     * माइक वापस सिर्फ़ "Hey DK" सुनता है (पहले power बटन से Live हमेशा के लिए चालू रह जाता था = कुछ भी बोलो, DK जवाब देता).
     * Live का बटन खुद दबाकर चालू किया = वह पहले जैसा हमेशा वाला.
     */
    private fun beginSession() {
        if (!InvokeSession.useSession(true, Prefs.invokeSession(this), Prefs.wakeMode(this)) || !DKState.live) return
        if (wakeLive) { gemLive.idleExitMs = maxOf(gemLive.idleExitMs, InvokeSession.IDLE_MS); return }
        sessionLive = true; gemLive.idleExitMs = InvokeSession.IDLE_MS
        Prefs.edit(this) { putBoolean("live_mode", false) }   // फ़ोन/ऐप फिर शुरू हो तो हमेशा वाला Live न लौटे
        DKLog.d("power बटन: एक बार की बातचीत (${InvokeSession.IDLE_MS / 1000}s चुप = वापस Hey DK)")
    }

    fun invokedNow(why: String, session: Boolean = false) {
        if (!Setup.hasMic(this) || callBusy()) return
        DKLog.d("DK बुलाया ($why) - Live तुरंत, पीछे से")
        // नवंबर 2026 (मालिक, Siri जैसा): नेट का रास्ता (DNS + TLS) अभी गरम करो, Live उसी पल जुड़ना शुरू,
        // और साथ-साथ फ़ोन में बना छोटा "Yes Boss" / टिंग तुरंत (InvokeAck) - जुड़ने का इंतज़ार कभी चुप्पी में नहीं
        try { LiveNet.warm(this, "DK बुलाया") } catch (_: Exception) {}
        val wasConnected = DKState.live && gemLive.ready
        InvokeAck.expect()
        LiveChime.arm()
        if (DKState.live) appOpened() else startLive()
        if (session) beginSession()    // power बटन = एक बार की बातचीत (InvokeSession)
        val inCall = calls.inCall || calls.ringing
        scope.launch(Dispatchers.IO) {
            val how = try { InvokeAck.play(this@DKAccessibilityService, inCall) } catch (_: Exception) { "" }
            if (how.isNotEmpty()) DKLog.d("DK बुलाया: तुरंत जवाब ($how)")
            // पहले से जुड़ा था = "Yes Boss" के बाद अभी "जुड़ गया" वाली आवाज़
            if (wasConnected && gemLive.ready) LiveChime.connected(this@DKAccessibilityService, inCall)
        }
    }

    /**
     * 14 नवंबर 2026 (मालिक: "लाइव जल्दी डिस्कनेक्ट हो जाता है") - जड़: Live पहले "Hey DK" / power बटन से चालू हुआ हो
     * (उसमें 30-45 सेकंड चुप = Live बंद) और फिर DKAI का माइक दबाया जाए, तो [openedInApp] "पहले से जुड़ा" देखकर लौट
     * जाता था - वह छोटा टाइमर ([GeminiLive.idleExitMs]) वैसा ही रहता और थोड़ी चुप्पी पर Live कट जाता.
     * अब माइक दबाना = खुद चालू किया हुआ हमेशा वाला Live (✕ दबाने तक): टाइमर साफ़, और जुड़ते ही (या पहले से जुड़ा हो तो अभी) आवाज़.
     */
    fun micPressed(why: String) {
        if (!Setup.hasMic(this) || callBusy()) return
        if (InvokeSession.keepOnMic(wakeLive, sessionLive, gemLive.idleExitMs)) {
            DKLog.d("$why: एक बार वाली बातचीत (${gemLive.idleExitMs / 1000}s चुप = बंद) अब हमेशा वाला Live")
            wakeLive = false; sessionLive = false; gemLive.idleExitMs = 0
            Prefs.edit(this) { putBoolean("live_mode", true) }
        }
        LiveChime.arm()
        if (DKState.live && gemLive.ready) LiveChime.connected(this, calls.inCall || calls.ringing)
        openedInApp(why)
    }

    /** ऐप के अंदर "जुड़ गया" की हल्की आवाज़ बज चुकी (एक बार ही) */
    @Volatile private var inAppChimed = false

    /**
     * नवंबर 2026 (मालिक): ऐप के अंदर ओर्ब / काली आवाज़ स्क्रीन पर टैप = पहले से जुड़ा Live वैसे ही चलता रहे.
     * "जी बॉस" और जुड़ने की आवाज़ सिर्फ़ power बटन (assistant) पर. ऐप के अंदर पहली बार जुड़ने पर हल्की आवाज़ एक ही बार.
     */
    fun openedInApp(why: String) {
        if (!Setup.hasMic(this) || callBusy()) return
        if (InvokeEntry.keepAsIs(DKState.live, gemLive.ready, gemLive.running)) {
            DKLog.d("$why - Live पहले से जुड़ा, कुछ नहीं दोहराया"); return
        }
        try { LiveNet.warm(this, why) } catch (_: Exception) {}
        if (InvokeEntry.chimeInApp(inAppChimed)) { inAppChimed = true; LiveChime.arm() }
        if (DKState.live) appOpened() else startLive()
    }

    // ================================================================ बैकअप मोड (मालिक, नवंबर 2026)
    // "हल्का मोड" पूरी तरह हटा. अब: Gemini Live सच में न जुड़े (GeminiLive.Fatal) = बैकअप मोड -
    // फ़ोन की बोली-पहचान (SpeechRecognizer) सुनती है -> agent.run() = DK Chat का text दिमाग़ (Gemini key हो तो Gemini)
    // -> Gemini TTS की आवाज़ में; वह न चले = तुरंत फ़ोन की अपनी Google / Android आवाज़ (speaker.localOnly; नवंबर 2026 Kokoro हटाया).
    // स्क्रीन पर "बैकअप मोड • <कारण>"; कारण के हिसाब से 1-10 मिनट बाद Live फिर आज़माया जाता है (LiveFailWhy).
    @Volatile var backupMode = false
        internal set
    @Volatile var backupKind: LiveFailWhy.Kind? = null
        internal set
    /** नवंबर 2026: बैकअप कब शुरू हुआ (cooldown यहीं से) + लगातार कितनी बार लिमिट (60 -> 120 -> 240s ...) - LiveHealth */
    @Volatile internal var backupAt = 0L
    @Volatile internal var quotaStrikes = 0
    /** बैकअप में पीछे वाली चुप सेहत-जाँच (Live ठीक = अगली बात से फिर Live) */
    internal var healthJob: kotlinx.coroutines.Job? = null

    /** GeminiLive: setup पूरा = DK लाइव जुड़ गया */
    fun liveConnected() {
        if (backupMode) { exitBackup("DK लाइव जुड़ गया"); liveSoftFails = 0 }
        // नवंबर 2026 (मालिक): बुलाने के बाद Live जुड़ा = छोटी, हल्की "जुड़ गया" आवाज़ (Yes Boss के बाद; बीच बातचीत के चुपचाप re-connect पर नहीं)
        LiveChime.connected(this, calls.inCall || calls.ringing)
        ModeTrack.switchTo(ModeTrack.Ch.LIVE, "Live जुड़ा")
    }

    /** Mic error 12/13 का संदेश एक बार (हर बार बोलकर परेशान नहीं) */
    private var langWarnedAt = 0L

    /**
     * बैकअप मोड में सुनना: फ़ोन का अपना SpeechRecognizer (online पहले - धीमे नेट वाला offline-पहले हटा).
     * Error 12/13 पर भाषा वाली अगली कोशिश VoiceListener खुद (तुरंत) करता है - यहाँ सिर्फ़ सब नाकाम होने पर संदेश.
     */
    private suspend fun hearBackup(): String? {
        if (!checkMic()) { delay(2000); return null }
        if (DKState.micMuted) { delay(800); return null }
        listener.preferOffline = false
        val text = hearGoogle()
        if (text == null && listener.langMissing) {
            val now = System.currentTimeMillis()
            if (now - langWarnedAt > MicLang.BAD_FOR_MS) {
                langWarnedAt = now
                val msg = DictationRules.message(MicLang.ERR_LANG_UNAVAILABLE)
                DKState.reply(msg); speaker.speak(msg)
            }
            delay(1500)
        }
        return text
    }

    /**
     * v4.6 (मालिक: "ऐप चालू करते ही लाइव से कनेक्शन हो जाना चाहिए"): ऐप खुला -
     * Live बंद हो (और आपने खुद माइक बंद नहीं किया) = तुरंत चालू; चालू हो पर टूटा पड़ा = तुरंत फिर जोड़ो.
     */
    fun appOpened() {
        if (callBusy()) return
        if (DKState.live) {
            if (wakeLive || gemLive.running || liveOffUntil <= System.currentTimeMillis()) return
            liveNow("ऐप खुला")
            // पुराने तरीके के सुनने (hear) में अटका हो तो रोको - Live तुरंत
            if (!agent.isBusy && !speaker.busy && DKState.mode != Mode.SPEAKING && loopJob?.isActive == true) {
                loopJob?.cancel(); loopJob = null
                ear.release(); listener.release(); earCaptionOff()
            }
            ensureLoop()
            return
        }
        if (Prefs.micUserOff(this) || GeminiLive.key(this).isBlank() || !Setup.hasMic(this)) return
        if (com.dk.assistant.license.AccessKeyManager.refusalOrNull(this) != null) return
        DKLog.d("ऐप खुला - DK लाइव तुरंत चालू")
        startLive()
    }

    /** v4.6: नेट वापस आया - Live टूटा पड़ा हो तो इंतज़ार नहीं, अभी जोड़ो */
    private val netBack = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            try { gemLive.netBack() } catch (_: Exception) {}     // v5.1: नेट की वजह से रुकी जोड़ियाँ फिर आज़माओ
            if (DKState.live && !wakeLive && liveOffSoft && liveOffUntil > System.currentTimeMillis()) liveNow("नेट वापस आया")
        }
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
        // अभी-अभी बात चल रही थी (Live खुला रहा, सारांश अभी बना नहीं) - बीच में "नमस्ते" नहीं
        val lastMsg = DKState.messages.lastOrNull()?.time ?: 0L
        // v4.3 (मालिक): पिछली बातचीत हो चुकी हो तो कुछ मिनट बाद भी "इतनी देर कहाँ थे?" (पहली बार = पुराना 20 मिनट नियम)
        if (!FriendTalk.shouldGreetNow(this, lastMsg)) return
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
        wakeLive = false; sessionLive = false; gemLive.idleExitMs = 0
        DKState.setLive(false)
        exitBackup("Live बंद"); listener.preferOffline = false; DKState.setLink("")
        // v3.59: बंद = बंद। जड़: बंद करने के 0.6s बाद "हमेशा सुनो" वाला wake-word loop माइक फिर खोल देता था,
        // और ऐप खोलते ही "दोस्त" वाला अभिवादन Live खुद चालू कर देता था। अब यूज़र के चालू करने तक माइक बंद।
        // v3.81: "Hey DK" चालू = Live बंद होने पर भी फ़ोन के अंदर सिर्फ़ "Hey DK" का इंतज़ार (कुछ बाहर नहीं जाता).
        // पूरा माइक बंद = होम का म्यूट बटन या सेटिंग में "Hey DK" बंद.
        val heyDk = com.dk.assistant.voice.HeyDk.on(this)
        Prefs.edit(this) { putBoolean("live_mode", false); putBoolean("mic_user_off", !heyDk) }
        gemLive.stop(why)
        // मालिक (नवंबर 2026): power बटन वाला "Yes Boss" DK की Live आवाज़ में - Live बंद हुआ = अब एक छोटा अलग connection ठीक
        com.dk.assistant.voice.LiveAckMaker.ensureAsync(this, "Live बंद", 3_000L)
        agent.stop()
        loopJob?.cancel(); loopJob = null
        listener.release()
        ear.release()
        DKState.set(Mode.IDLE)
        scope.launch {
            if (heyDk) {
                speaker.speak("ठीक है। ज़रूरत हो तो Hey DK बोलिए।")
                delay(800); ensureLoop()
                return@launch
            }
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

    internal var loopJob: kotlinx.coroutines.Job? = null

    /** Silent kaan (apna mic) ya uski key kuch der ke liye kharab -> tab tak Google mic se suno */
    @Volatile private var googleUntil = 0L
    private var micFails = 0
    internal val warnedAt = HashMap<String, Long>()

    /** Gemini Live (aawaz se aawaz, real-time) - sabse tez tareeka */
    val gemLive by lazy { GeminiLive(this, agent) }
    @Volatile internal var liveOffUntil = 0L
    /** Lagatar kitni baar Live network/mic ki thodi-der wali dikkat se ruka (backoff ke liye) */
    @Volatile internal var liveSoftFails = 0
    /** v4.6: Live थोड़ी देर की दिक्कत (नेट / टूटना / setup) से रुका - पुराने तरीके पर नहीं, बस सेकंडों में फिर जोड़ो */
    @Volatile internal var liveOffSoft = false
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

    fun useGeminiLive(): Boolean = DKState.live && liveReady()
    /** Gemini Live chal sakta hai? (sunne ka tareeka "auto" + Gemini key + abhi ruka nahi) */
    fun liveReady(): Boolean = Prefs.listenEngine(this) == "auto" &&
        GeminiLive.available(this) && System.currentTimeMillis() > liveOffUntil
    /** Wake word sunke thodi der ke liye Live chalu kiya (chup rahne par wapas wake mode) */
    @Volatile private var wakeLive = false
    private var lastWhyNot = ""

    /** Bina beep wala tareeka chalega? (Groq ya Gemini key chahiye) */
    fun silentEar(): Boolean = Prefs.listenEngine(this) != "google" && Transcriber.available(this) &&
        System.currentTimeMillis() > googleUntil

    /** Ek hi dikkat baar-baar na bole (5 min me ek baar) - screen par likho + bolkar batao */
    /** v5: DK लाइव न जुड़ पाया - स्क्रीन पर बताया / बोलकर बताया (फिर जुड़ने पर GeminiLive दोनों रीसेट करता है) */
    @Volatile var liveTroubleShown = false
    @Volatile var liveTroubleSpoken = false

    /**
     * v3.7: purane (bina beep) tareeke me bhi bolte-bolte shabd screen par.
     * Gemini key ho to mic ki awaaz (sirf jab aap bol rahe ho) tez caption model ko bhi jaati hai.
     */
    internal var earCaption: LiveCaption? = null
    @Volatile internal var earShowing = false

    @Volatile private var ownerSeenAt = 0L
    private var othersIgnored = 0

    /** v3.66: आवाज़ मालिक की है? (पहचान बंद / मॉडल गड़बड़ = हाँ, आवाज़ मत खोओ) */
    internal suspend fun ownerHeard(pcm: ByteArray): Boolean {
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
    /** v4.8: Live बंद, पर DK का wake वाला सुनना चालू (user ने माइक बंद नहीं किया) */
    internal fun offlineWakeOn(): Boolean = !DKState.live && Prefs.wakeMode(this) && !Prefs.micUserOff(this)

    /** v4.8: DK सुन रहा है (Live या wake) - ऑफलाइन माइक तब तक नहीं छूटता */
    internal fun listeningWanted(): Boolean = DKState.live || offlineWakeOn()

    suspend fun hear(wakeGate: Boolean = false): String? {
        // नवंबर 2026: बैकअप + नेट नहीं = फ़ोन का SpeechRecognizer नहीं (हर बार बीप) - अपना माइक, सिर्फ़ आवाज़-कमांड
        // v4.8: Live बंद पर wake वाला सुनना (DK ऑन) भी - बिना नेट हमेशा अपना माइक, लगातार (बीप / नेट वाला STT नहीं)
        if (OfflineListen.cmdsOnly(backupMode, DKState.live, NetProbe.online(this), offlineWakeOn())) return hearOfflineCmds()
        if (backupMode && DKState.live) return hearBackup()   // Live नहीं जुड़ा - बैकअप मोड (फ़ोन की बोली-पहचान)
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
        // नवंबर 2026: बिना इंटरनेट वाली आवाज़-कमांड पहले (STT से पहले; "किधर हो" किसी की भी आवाज़ पर - इसलिए मालिक-जाँच से पहले)
        if (com.dk.assistant.voice.VoiceCmds.anyOn(this)) {
            val vc = withContext(Dispatchers.Default) { com.dk.assistant.voice.VoiceCmds.match(this@DKAccessibilityService, pcm) }
            if (vc != null && runVoiceCmd(vc, "सुनना")) {
                earShowing = false; earCaption?.closeUtterance(); DKState.user(""); DKState.set(Mode.IDLE)
                return null
            }
        }
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
        // (फ़ोन लॉक / अनलॉक के अपने नाम - "हे मायरा फोन अनलॉक करो" - भी DK के नाम जैसे गिनो)
        if (wakeGate && SttTiming.skipWakeStt(capNow, Prefs.wakeWords(this) + PhoneLockVoice.cfg.let { if (it.on) it.names else emptyList() })) {
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
    @Volatile internal var agentHearing = false
    @Volatile internal var bargeListen: kotlinx.coroutines.Deferred<String?>? = null

    suspend fun hearForAgent(): String? {
        agentHearing = true
        try {
            bargeListen?.cancelAndJoin()
            return hear()
        } finally { agentHearing = false }
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
            // नवंबर 2026: नेट रहते ही फ़ोन की बिना-नेट हिंदी पहचान (on-device) डाउनलोड करवा लो - वरना ऑफलाइन में कुछ नहीं सुनता
            if (NetProbe.online(this@DKAccessibilityService)) OfflineStt.prepare(this@DKAccessibilityService)
            // v3.81: "हाँ सर" वगैरह पहले से बना लो (एक बार) - जागते ही बिना इंटरनेट-इंतज़ार के बोले
            if (Prefs.wakeMode(this@DKAccessibilityService)) launch(Dispatchers.IO) {
                try { com.dk.assistant.voice.HeyDk.prepareAcks(this@DKAccessibilityService) } catch (_: Exception) {}
            }
            var misses = 0
            while (isActive && (DKState.live || (Prefs.wakeMode(this@DKAccessibilityService) && !Prefs.micUserOff(this@DKAccessibilityService)))) {
                // v3.59: म्यूट (wake word वाले सुनने में) = माइक छोड़ दो
                if (DKState.micMuted && !DKState.live) { ear.release(); listener.release(); earCaptionOff(); delay(400); continue }
                if ((agent.isBusy || speaker.busy || DKState.mode == Mode.SPEAKING || callAnnouncing) && !callBargeEar()) { delay(250); continue }
                // आप इनपुट बार में बोलकर लिख रहे हैं - माइक आपका (DK यह बात command नहीं मानता)
                if (DKMic.dictating && !useGeminiLive()) { ear.release(); listener.release(); delay(300); continue }
                // call chal rahi hai: mic call ka hai - DK ka mic chhod do (warna saamne wale ko awaaz nahi jaati)
                if (calls.inCall && !calls.ringing) { ear.release(); listener.release(); DKState.set(Mode.IDLE); delay(1000); continue }
                // v4.6: Live थोड़ी देर के लिए टूटा (नेट/connection) - पुराने तरीके (दूसरा STT + chat) पर नहीं जाना:
                // वहाँ बोला हुआ लिखा जाता था (कभी ग़लत) पर जवाब अक्सर नहीं आता था. बस इंतज़ार और तुरंत फिर Live
                if (DKState.live && !wakeLive && liveOffSoft && System.currentTimeMillis() <= liveOffUntil && GeminiLive.available(this@DKAccessibilityService)) {
                    ear.release(); listener.release(); earCaptionOff()
                    delay(250); continue
                }
                // Gemini Live: mic ki awaaz lagatar stream, jawab ki awaaz turant stream (sabse kam deri)
                // नवंबर 2026: नेट नहीं (बैकअप) = Live जोड़ने के लिए माइक बंद-चालू नहीं; नेट लौटते ही फिर Live
                if (useGeminiLive() && !callBusy() && !OfflineListen.cmdsOnly(backupMode, DKState.live, NetProbe.online(this@DKAccessibilityService))) {
                    if (!checkMic()) { delay(2000); continue }
                    ear.release(); listener.release(); earCaptionOff()
                    lastWhyNot = ""
                    var failed = false
                    val liveStart = System.currentTimeMillis()
                    ModeTrack.call(ModeTrack.Ch.LIVE, if (backupMode) "बैकअप से वापस Live (सेहत-जाँच ठीक)" else "")
                    try {
                        val r = gemLive.run()
                        DKState.setLink("")          // v5.1: Live रुका (सामान्य) - पुराना "Live • …" न दिखे
                        liveSoftFails = 0
                        ModeTrack.ok(ModeTrack.Ch.LIVE)
                        DKLog.d("Gemini Live ruka: $r")
                        // नवंबर 2026 (मालिक): power बटन वाली बातचीत - चुप रहे = Live बंद, माइक वापस सिर्फ़ "Hey DK" सुनता है
                        if (sessionLive && !wakeLive && gemLive.idleExited) {
                            sessionLive = false; gemLive.idleExitMs = 0
                            DKState.setLive(false); DKState.set(Mode.IDLE); DKState.setLink("")
                            Prefs.edit(this@DKAccessibilityService) { putBoolean("live_mode", false) }
                            DKLog.d("power बटन वाली बातचीत खत्म - अब सिर्फ़ Hey DK पर जागेगा")
                            Ducker.releaseWhenQuiet({ !speaker.busy })
                        }
                    } catch (e: GeminiLive.Fatal) {
                        failed = true
                        ModeTrack.error(ModeTrack.Ch.LIVE, (e.kind?.short ?: if (e.recoverable) "टूटा" else "रुकावट") + ": " + (e.message ?: "-"))
                        // 5 min+ theek chala tha = purani dikkatein ginti se hatao (backoff shuru se)
                        if (System.currentTimeMillis() - liveStart > 5 * 60_000L) { liveSoftFails = 0; quotaStrikes = 0 }
                        // key/limit/model = asli rukawat. Network/mic busy = thodi der.
                        val userLive = DKState.live && !wakeLive
                        val rawMs = if (userLive) LiveRetry.fast(e.recoverable, LiveRetry.liveOnMs(e.recoverable, liveSoftFails)) else LiveRetry.offMs(e.recoverable, liveSoftFails)
                        val offMs = rawMs.coerceAtMost(60_000L)
                        if (e.recoverable) liveSoftFails++
                        liveOffSoft = userLive && e.recoverable
                        liveOffUntil = System.currentTimeMillis() + offMs
                        DKLog.d("Gemini Live ruka (${if (e.recoverable) "थोड़ी देर की दिक्कत #$liveSoftFails" else "असली रुकावट"}) - ${offMs / 1000}s बाद फिर कोशिश: ${e.message}")
                        DKState.set(Mode.IDLE)
                        // v5 (मालिक: "न जुड़ पाए तो यूज़र को बोल दे"): आपने Live चालू रखा है = हर रुकावट में एक बार साफ़ शब्दों में बोलो
                        // (तकनीकी गलती नहीं); फिर जुड़ते ही GeminiLive "जुड़ गई, बोलिए" दिखाता है
                        // मालिक (नवंबर 2026): Live नहीं जुड़ा = साफ़ हिंदी कारण + बैकअप मोड (बोली-पहचान -> text दिमाग़ -> Edge TTS).
                        // जुड़ने के बाद बीच में टूटा (kind नहीं, recoverable) = पहले जैसा तुरंत फिर जोड़ो
                        if (userLive && (e.kind != null || !e.recoverable)) {
                            val kind = e.kind ?: LiveFailWhy.classify(0, e.message, NetProbe.online(this@DKAccessibilityService))
                            enterBackup(kind, e.message ?: "-")
                        }
                        // नवंबर 2026 (मालिक): जुड़कर बार-बार टूटा (socket drop) = यूज़र रुका न रहे -> चुपचाप text मोड + Gemini TTS (न चले तो फ़ोन की आवाज़);
                        // पीछे सेहत-जाँच ठीक होते ही अगली बात से फिर Live (LiveHealth)
                        else if (userLive && LiveHealth.dropToBackup(liveSoftFails)) {
                            enterBackup(LiveHealth.softKind(LiveFailWhy.classify(0, e.message, NetProbe.online(this@DKAccessibilityService))), e.message ?: "-")
                        }
                        else if (userLive) { DKState.setLink("Live नहीं जुड़ा - फिर जोड़ रहा हूँ"); liveTroubleSay(LiveConnectNotice.forStop(e.message, e.recoverable)) }
                        else if (liveOffSoft) warn("live", "DK लाइव का connection टूटा (${e.message}) - ${offMs / 1000}s में फिर जोड़ रहा हूँ।")
                        else warn("live", "Gemini Live नहीं चला: ${e.message}। अभी पुराने तरीके से सुन रहा हूँ।")
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        failed = true
                        // v4.6: आपने Live चालू रखा है = 3s में फिर (पहले 2 मिनट पुराना तरीका)
                        val userLive = DKState.live && !wakeLive
                        liveOffSoft = userLive
                        // v5: 3s -> 1s (मालिक: "टूटे तो 1 सेकंड में फिर जुड़े"), max 60s
                        val rawMs = if (userLive) LiveRetry.FAST_MAX_MS else 2 * 60_000L
                        val offMs = rawMs.coerceAtMost(60_000L)
                        liveOffUntil = System.currentTimeMillis() + offMs
                        DKLog.d("Gemini Live गलती: ${e.javaClass.simpleName} ${e.message}")
                        ModeTrack.error(ModeTrack.Ch.LIVE, "${e.javaClass.simpleName}: ${e.message ?: "-"}")
                        if (userLive) liveSoftFails++
                        // बार-बार अनजानी गलती = क्रैश/रुकना नहीं, चुपचाप text मोड (सेहत-जाँच से वापस)
                        if (userLive && LiveHealth.dropToBackup(liveSoftFails))
                            enterBackup(LiveHealth.softKind(LiveFailWhy.classify(0, e.message, NetProbe.online(this@DKAccessibilityService))), e.message ?: "-")
                        else if (userLive) liveTroubleSay(LiveConnectNotice.TROUBLE)
                    }
                    // v3.45: बातचीत खत्म -> कब, कितनी देर, सारांश, अधूरी बातें (फ़ोन पर)
                    try { FriendTalk.sessionEnd(this@DKAccessibilityService, liveStart, scope) } catch (_: Exception) {}
                    try { LocalMemory.maintain(this@DKAccessibilityService, "बातचीत खत्म") } catch (_: Exception) {}   // v3.88: आज का सार
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
                // v3.81: "Hey DK" फ़ोन के अंदर (cloud STT नहीं) - सिर्फ़ मालिक की आवाज़ में "Hey DK" पर जागो
                // v4.6: घंटी बजते समय "Hey DK" loop रुका रहता था (stop = ringing) और कोई नहीं सुनता था - "डीके फोन उठा लो"
                // अनसुना. घंटी के समय सीधे नीचे वाला hear() (कॉल का जवाब, आवाज़-कमांड, बिना wake word)
                // v4.8: बिना नेट (फ़ोन की अपनी पहचान हो तो) "Hey DK" नहीं - सीधे "डीके ..." वाला लगातार सुनना (नीचे hear())
                val offlineEar = !NetProbe.online(this@DKAccessibilityService) && OfflineStt.available(this@DKAccessibilityService)
                if (!DKState.live && !callRinging() && !offlineEar && com.dk.assistant.voice.HeyDk.on(this@DKAccessibilityService)) {
                    if (!checkMic()) { delay(2000); continue }
                    ear.release(); listener.release(); earCaptionOff()
                    if (DKState.mode == Mode.LISTENING) DKState.set(Mode.IDLE)
                    val me = this@DKAccessibilityService
                    val hit = com.dk.assistant.voice.HeyDk.listen(me) {
                        DKState.live || DKState.micMuted || agent.isBusy || speaker.busy || callAnnouncing || calls.inCall ||
                            calls.ringing || DKMic.dictating || Prefs.micUserOff(me) || !Prefs.heyDkOn(me) || !Prefs.wakeMode(me)
                    }
                    if (hit == null) { delay(if (calls.ringing || calls.inCall) 800 else 200); continue }
                    // नवंबर 2026: लॉक / अनलॉक / किधर हो - फ़ोन पर ही पहचाना (इंटरनेट / STT / Live नहीं)
                    if (hit.cmd != null) { runVoiceCmd(hit.cmd, "Hey DK"); continue }
                    agent.heardAt = System.currentTimeMillis()
                    Ducker.duck(me, "Hey DK")
                    var cmd = ""
                    if (hit.rest != null) {
                        DKState.set(Mode.THINKING)
                        // नवंबर 2026: बिना नेट cloud STT नहीं चलता - फ़ोन की on-device पहचान (Android 13+)
                        val said = if (!NetProbe.online(me) && OfflineStt.available(me)) OfflineStt.transcribe(me, hit.rest) else Transcriber.transcribe(me, hit.rest)
                        cmd = com.dk.assistant.voice.WakeMatch.stripWake(said ?: "", Prefs.wakeWords(me))
                    }
                    if (cmd.isBlank()) {
                        val ack = com.dk.assistant.voice.HeyDk.nextAck(me)
                        DKState.reply(ack)
                        val played = try { withContext(Dispatchers.IO) { com.dk.assistant.voice.HeyDk.playAck(me, ack) } } catch (_: Exception) { false }
                        if (!played) {
                            speaker.speak(ack)
                            scope.launch(Dispatchers.IO) { try { com.dk.assistant.voice.HeyDk.prepareAcks(me) } catch (_: Exception) {} }
                        }
                    }
                    // नवंबर 2026 (मालिक: ऑफलाइन में "डीके फोन लॉक / अनलॉक करो" नहीं होता): बिना नेट यह बात Gemini Live को जाती थी,
                    // जो जुड़ ही नहीं सकता = कुछ नहीं. अब बिना नेट लॉक / अनलॉक सीधे फ़ोन पर, बाकी बात फ़ोन वाले रास्ते (agent) से.
                    val offlineHey = cmd.isNotBlank() && !NetProbe.online(me)
                    if (offlineHey) {
                        val act = PhoneLockVoice.instant(cmd, Prefs.wakeWords(me), needName = false)
                        if (act != null) {
                            DKState.user(cmd); DKLog.d("ऑफलाइन Hey DK: आवाज़ से ${act.type}")
                            agent.runQuick(PhoneLockVoice.quick(act)); Ducker.releaseWhenQuiet({ !speaker.busy }); continue
                        }
                    }
                    if (!offlineHey && liveReady() && !callBusy() && checkMic()) {
                        DKLog.d("Hey DK -> Gemini Live" + if (cmd.isNotBlank()) " (बात: $cmd)" else "")
                        wakeLive = true; LiveChime.arm()
                        gemLive.idleExitMs = 30_000
                        gemLive.firstText = cmd.ifBlank { null }
                        if (cmd.isBlank()) DKState.set(Mode.LISTENING)
                        DKState.setLive(true)
                        continue
                    }
                    if (cmd.isBlank()) {
                        DKState.set(Mode.LISTENING)
                        cmd = hear() ?: ""
                        DKState.set(Mode.IDLE)
                    }
                    if (cmd.isNotBlank()) agent.run(cmd).join()
                    Ducker.releaseWhenQuiet({ !speaker.busy })
                    continue
                }
                val liveBefore = DKState.live
                val heard = hear(wakeGate = !liveBefore && !callRinging())
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
                // v4.1: कॉल अभी-अभी ख़त्म / चल रही - कुछ सेकंड तक कॉल वाली कमांड बिना DK
                if (CallCmd.windowOpen(System.currentTimeMillis())) {
                    val cc = CallCmd.route(heard, System.currentTimeMillis())
                    if (cc != null) { DKState.user(heard); doCallCmd(cc); continue }
                }

                // नवंबर 2026: आवाज़ से फ़ोन लॉक / अनलॉक - Settings में डाला कोई भी नाम + वाक्य ("हे मायरा फोन अनलॉक करो"),
                // AI / इंटरनेट के बिना, सीधे फ़ोन पर
                PhoneLockStore.reload(this@DKAccessibilityService)
                // v3.151: Settings में अनलॉक बंद / PIN सेव नहीं हो तब भी "DK फोन अनलॉक करो" पहचाने - स्क्रीन जगाए और सही कारण बोले
                // (पहले यह बात Live/AI में चली जाती, जो "खुद खोलिए" या "हो गया" बोल देता)। लॉक वाला switch पहले जैसा।
                // नवंबर 2026 (मालिक: "ऑफलाइन में डीके फोन लॉक कर दो नहीं करता"): जड़ - ऑफलाइन सुनना (keepText) LOCK को आगे भेजता था,
                // पर यहाँ Settings का लॉक switch बंद (default) हो तो LOCK गिरा दिया जाता और बात AI (नेट चाहिए) को चली जाती = कुछ नहीं.
                // बिना नेट = आम वाक्य से LOCK भी (नाम ज़रूरी, वही पक्की जाँच).
                val offlineNow = !NetProbe.online(this@DKAccessibilityService)
                val lockAct = PhoneLockVoice.match(heard, needName = true)
                    ?: PhoneLockVoice.instant(heard, Prefs.wakeWords(this@DKAccessibilityService), needName = true)
                        ?.takeIf { it == PhoneLockVoice.Act.UNLOCK || offlineNow }
                if (lockAct != null) {
                    DKState.user(heard); DKLog.d("फ़ोन लॉक: आवाज़ से ${lockAct.type}")
                    agent.runQuick(PhoneLockVoice.quick(lockAct)); continue
                }
                // नवंबर 2026: "DK किधर हो" (लिखी बात से - आवाज़ वाला नमूना न सिखाया हो तब) / बजते समय "मिल गया"
                if (FindPhone.active && com.dk.assistant.voice.VoiceCmdMatch.foundText(heard)) {
                    DKState.user(heard); FindPhone.stop("बोला: ${heard.take(30)}"); continue
                }
                // नवंबर 2026: गार्ड मोड - अलार्म / पहरे में गुप्त शब्द (या अलार्म में "सायरन बंद" + गुप्त शब्द) = बंद
                if (Guard.active() && Guard.heardStop(this@DKAccessibilityService, heard)) { DKState.user(heard); continue }
                val findRest = OfflineListen.findRest(heard, Prefs.wakeWords(this@DKAccessibilityService))   // नवंबर 2026: "डीके कहाँ हो" (बिना "हे") भी
                if (findRest != null && com.dk.assistant.voice.VoiceCmdMatch.findText(findRest)) {
                    DKState.user(heard); DKState.reply(FindPhone.SAY); FindPhone.start(this@DKAccessibilityService, "बोला: ${heard.take(30)}"); continue
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
                    // v4.1: "सिर्फ़ DK बोलने पर सुनो" - शुरुआत में DK वाले वाक्य ही, बाकी पूरी तरह अनदेखे
                    // नवंबर 2026: सुनने वाला मोड ("मैं किसी से बात कर रहा हूँ") = वही नियम; नाम लेकर बोले = मोड खत्म
                    SideTalk.global.touch(System.currentTimeMillis())
                    val sideListen = SideTalk.global.listening(System.currentTimeMillis())
                    if (Prefs.dkOnly(this@DKAccessibilityService) || sideListen) {
                        cmdText = DkOnly.filter(heard) ?: continue
                        if (sideListen) SideTalk.apply(false)
                        if (cmdText.isBlank()) continue
                    } else if (othersPlaying) {
                        val w = Prefs.wakeWords(this@DKAccessibilityService).firstOrNull { low.contains(it) } ?: continue
                        cmdText = heard.substring((low.indexOf(w) + w.length).coerceAtMost(heard.length)).trim(' ', ',', '.', '!')
                        if (cmdText.isBlank()) continue
                    }
                    Ducker.duck(this@DKAccessibilityService, "बात शुरू")
                    runWithBarge(agent.run(cmdText))
                    Ducker.releaseWhenQuiet({ !speaker.busy })
                } else {
                    // wake word mode
                    // नवंबर 2026 (मालिक): "कुछ भी बोलूँ तो DK जाग जाता है" - अब सिर्फ़ पूरा "Hey DK / OK DK / Hello DK" (शुरुआत में).
                    // बाकी हर बात = अनसुनी, कुछ नहीं होता. Settings में "सख़्त जागना" बंद = पुराना तरीका (बात में कहीं भी नाम)
                    var cmd: String
                    // v4.8 मालिक: बिना नेट "पहला शब्द डीके" - "डीके फोन अनलॉक करो" (बिना "हे") भी; बाकी हर बात अनसुनी
                    if (offlineNow) {
                        cmd = OfflineListen.findRest(heard, Prefs.wakeWords(this@DKAccessibilityService)) ?: run {
                            DKLog.d("ऑफलाइन: DK नाम से शुरू नहीं - अनसुना: \"${heard.take(40)}\""); null
                        } ?: continue
                        if (cmd.isBlank()) continue      // अकेला "डीके" - hearOfflineCmds ने "जी" बोलकर अगली बात खोली
                        DKState.user(heard)
                        agent.run(cmd).join()
                        Ducker.releaseWhenQuiet({ !speaker.busy })
                        continue
                    }
                    if (Prefs.wakeStrict(this@DKAccessibilityService)) {
                        cmd = com.dk.assistant.voice.WakeMatch.strictWake(heard, Prefs.wakeWords(this@DKAccessibilityService)) ?: run {
                            DKLog.d("Hey DK नहीं बोला - अनसुना: \"${heard.take(40)}\""); null
                        } ?: continue
                    } else {
                        val wake = Prefs.wakeWords(this@DKAccessibilityService).firstOrNull { low.contains(it) } ?: continue
                        cmd = heard.substring((low.indexOf(wake) + wake.length).coerceAtMost(heard.length))
                            .trim(' ', ',', '.', '!')
                        // v4.1: "सिर्फ़ DK बोलने पर सुनो" - नाम बात की शुरुआत में हो तभी (बीच में आया नाम = अनदेखा)
                        if (Prefs.dkOnly(this@DKAccessibilityService) || SideTalk.global.listening(System.currentTimeMillis())) cmd = DkOnly.filter(heard) ?: continue
                    }
                    // naam suna = user DK se baat kar raha hai -> peeche ka gaana/video dheema (Live ho to jawab ke baad wahi wapas badhayega)
                    Ducker.duck(this@DKAccessibilityService, "wake word")
                    // naam suna -> Gemini Live (asli awaaz, turant jawab); 45s chup = wapas wake word
                    // v3.81: "हे डीके / हेलो डीके" (बिना सिखाए वाला तरीका) पर भी पहले "हाँ सर" - फिर Live
                    if (cmd.isBlank()) {
                        val me = this@DKAccessibilityService
                        val ack = com.dk.assistant.voice.HeyDk.nextAck(me)
                        DKState.reply(ack)
                        val played = try { withContext(Dispatchers.IO) { com.dk.assistant.voice.HeyDk.playAck(me, ack) } } catch (_: Exception) { false }
                        if (!played) {
                            speaker.speak(ack)
                            scope.launch(Dispatchers.IO) { try { com.dk.assistant.voice.HeyDk.prepareAcks(me) } catch (_: Exception) {} }
                        }
                    }
                    if (liveReady() && !callBusy() && checkMic()) {
                        DKLog.d("Wake word suna -> Gemini Live" + if (cmd.isNotBlank()) " (बात: $cmd)" else "")
                        wakeLive = true; LiveChime.arm()
                        gemLive.idleExitMs = 45_000
                        gemLive.firstText = cmd.ifBlank { null }
                        if (cmd.isBlank()) DKState.set(Mode.LISTENING)
                        DKState.setLive(true)
                        continue
                    }
                    if (cmd.isBlank()) {
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

    /** ऑटोनोमस मोड की सुरक्षा: पिछली पढ़ी स्क्रीन पर पासवर्ड वाली जगह है? */
    fun hasPasswordField(): Boolean = try { elements.any { it.node.isPassword } } catch (_: Exception) { false }

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

        val raw = mutableListOf<Element>()
        for (root in roots) walk(root, raw, sb, 0)
        // v3.156: ज़्यादा चीज़ें हों तो ज़रूरी पहले (लिखने का खाना, बटन, नीचे का हिस्सा) - क्रम वही
        val h = resources.displayMetrics.heightPixels
        val cands = raw.map { e ->
            com.dk.assistant.agent.core.NodePick.Cand(e.node.isEditable, e.node.isClickable || e.node.isLongClickable,
                !e.node.text.isNullOrBlank() || !e.node.contentDescription.isNullOrBlank(), e.bounds.top, e.bounds.bottom)
        }
        com.dk.assistant.agent.core.NodePick.keep(cands, SCREEN_CAP, h).forEach { i -> list.add(raw[i].copy(id = list.size)) }
        list.forEach { screenLine(it, sb) }
        elements = list

        val dm = resources.displayMetrics
        val pkg = rootInActiveWindow?.packageName?.toString() ?: "unknown"
        return Screen(sb.toString(), pkg, dm.widthPixels, dm.heightPixels, list.size)
    }

    /** Screenshot (Android 11+), chhota JPEG base64 me - AI ko "dikhane" ke liye. */
    suspend fun screenshotBase64(width: Int = 540, quality: Int = 60): String? {
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
                                val w = width.coerceIn(240, bmp.width.coerceAtLeast(240))
                                val h = (bmp.height * (w.toFloat() / bmp.width)).toInt()
                                val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                                val bos = ByteArrayOutputStream()
                                small.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), bos)
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
                DkPersona.TYPE -> {
                    DkPersona.set(this, DkPersona.of(a.optString("persona")))
                    "ok"
                }
                SideTalk.TYPE -> {
                    SideTalk.apply(a.optBoolean("on", true))
                    "ok"
                }
                JarvisMode.TYPE -> {
                    // नवंबर 2026: JARVIS ⇄ DK मोड (काम/tools वही; DK लाइव चुप होते ही नई आवाज़ से जुड़ता है)
                    JarvisMode.set(this, a.optBoolean("jarvis", true))
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
                "wa_call" -> WaCall.call(this, a.optString("name"), a.optBoolean("video", false))
                "call_log" -> PhoneActions.callLog(this)
                "open_dialer" -> PhoneActions.openDialer(this)
                "gallery_latest" -> PhoneActions.latestPhoto(this)
                "delete_media" -> MediaDelete.run(this, MediaPick.fromJson(a))
                "steps" -> performSteps(a.optJSONArray("steps"))
                "web_search" -> AppLauncher.webSearch(this, a.optString("query"))
                "open_url" -> AppLauncher.openUrl(this, a.optString("url"))
                // v3.75: Chrome/ब्राउज़र में सीधे वेबसाइट या Google खोज (पहले सिर्फ़ Chrome खुलता था)
                "browser_search" -> verifySearch(AppLauncher.browserSearch(this, a.optString("query"), a.optString("browser", "chrome")))
                // v3.75: फ़ोन में इंस्टॉल किसी भी ऐप के अंदर खोज
                "app_search" -> appSearch(a.optString("app"), a.optString("query"))
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
                "alarm_off" -> AlarmOff.run(this, AlarmOff.fromJson(a))
                "distance" -> distance(a.optString("to"), a.optString("from"), a.optString("mode", "driving"))
                "media" -> DeviceActions.media(this, a.optString("key", "play_pause"))
                "find_phone" -> FindPhone.start(this, "कमांड")
                "find_phone_stop" -> { FindPhone.stop("कमांड"); "ठीक है" }
                GuardRules.TYPE -> Guard.run(this, a)   // नवंबर 2026: गार्ड मोड चालू / बंद
                "lock" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN) else NativeActionEngine.lockOld()
                "screenshot_save" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT) else "ERROR"
                "power_menu" -> global(GLOBAL_ACTION_POWER_DIALOG)
                "unlock", "wake" -> unlock()
                "answer_call" -> answerCall(a.optBoolean("speaker", false))
                "end_call" -> endCall()
                "speaker" -> setSpeaker(a.optBoolean("on", true))
                "who_calls" -> whoCalls()
                "close_app" -> closeApp()
                "close_all_apps" -> closeAllApps()
                in NativeActionEngine.TYPES -> NativeActionEngine.run(this, a)   // नवंबर 2026: ब्राइटनेस / ऑटो रोटेट / DND
                in OfflineExec.TYPES -> OfflineExec.run(this, a)   // v4.1: अलार्म / टाइमर / कैमरा / समय / टैप / SMS (बिना API)
                "wait" -> { delay(a.optLong("ms", 1000).coerceIn(100, 8000)); "waited" }
                else -> "ERROR: unknown action ${a.optString("type")}"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    internal suspend fun click(id: Int): String {
        val e = element(id) ?: return "ERROR: id $id screen par nahi hai"
        var n: AccessibilityNodeInfo? = e.node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "clicked $id"
        return if (tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) "tapped $id" else "ERROR click $id"
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

    // ---------------------------------------------------------------- calls
    /** Call ka elaan chal raha hai (tab sunne wala loop ruka rahe, DK apni hi awaaz na sune) */
    @Volatile internal var callAnnouncing = false
    /** Call aayi aur abhi jawab (उठाओ / काटो) nahi mila */
    @Volatile internal var callPending = false
    /** v4.6: आख़िरी बात कॉल के एलान के बीच शुरू हुई (0 = नहीं) - तब DK का नाम ज़रूरी ([CallReply.bargeOk]) */
    @Volatile internal var callBargeAt = 0L
    /**
     * v4.6 मालिक: "जवाब देते में अगर यूज़र बोलेगा डीके फोन उठा लो, तो फटाक से पहले फोन उठाएगा"।
     * घंटी बज रही + DK कॉल का एलान कर रहा = एलान के ख़त्म होने का इंतज़ार नहीं, अपने माइक से साथ-साथ सुनो.
     * (Live में यह काम Live का माइक करता है; Google माइक DK की अपनी आवाज़ भी लिख देता, इसलिए सिर्फ़ अपना माइक)
     */
    /** v4.8: बिना नेट भी (फ़ोन की अपनी पहचान से) - एलान के बीच "डीके फोन उठा लो" */
    fun callBargeEar(): Boolean = try {
        callAnnouncing && callRinging() && !useGeminiLive() &&
            (silentEar() || !NetProbe.online(this) && OfflineStt.available(this))
    } catch (_: Exception) { false }

    /**
     * Call aate hi:
     *  1. ringtone dheemi (taaki DK ki awaaz aur aapki baat dono saaf)
     *  2. "<naam> का कॉल आ रहा है। उठाऊँ या काट दूँ?"
     *  3. aapka jawab suno (wake word ki zarurat nahi) -> uthao / kaato / speaker / ringtone band
     */
    /** v4: WhatsApp / Instagram / Facebook / Messenger… की कॉल बजी (DKNotifListener) - SIM कॉल जैसा ही पूछो */
    fun onAppCall(who: String, ask: String) {
        if (!::calls.isInitialized || calls.simRinging || calls.inCall) return
        DKLog.d("ऐप कॉल: $ask")
        AppCall.onGone = { calls.restoreRinger(); if (!AppCall.ongoing()) CallCmd.markEnded(System.currentTimeMillis()) }
        scope.launch { onIncomingCall(who, ask) }
    }

    /** काटी गई कॉल किसकी थी - मैसेज इसी को (नाम कभी नहीं पूछते) */
    internal data class CallTarget(val name: String, val number: String, val pkg: String, val app: String, val open: android.app.PendingIntent?)
    @Volatile internal var rejectTarget: CallTarget? = null

    /** ऊपर की कमांड चलाओ; लौटता है = बोलने वाला छोटा वाक्य */
    suspend fun runCallCmd(c: CallCmd.Cmd): String {
        val now = System.currentTimeMillis()
        DKLog.d("कॉल कमांड: ${c.action}${if (c.text.isNotBlank()) " (मैसेज ${c.text.length} अक्षर)" else ""}")
        return when (c.action) {
            CallCmd.REJECT_MSG, CallCmd.REJECT_BUSY -> {
                val live = calls.ringing || calls.inCall
                val t = if (live) currentTarget() else rejectTarget ?: currentTarget()
                if (live) { endCall(); callPending = false }
                if (t != null) rejectTarget = t
                val target = rejectTarget ?: return "सर, कॉल काट दी। किसकी कॉल थी पता नहीं चला, इसलिए मैसेज नहीं भेजा।"
                val text = if (c.action == CallCmd.REJECT_BUSY) Prefs.callBusyMsg(this) else c.text
                if (!CallCmd.usableText(text)) {
                    CallCmd.awaitMessage(now)
                    "सर, कॉल काट दी। क्या मैसेज भेजूँ?"
                } else { CallCmd.doneMessage(); sendRejectMessage(target, text) }
            }
            CallCmd.MUTE, CallCmd.UNMUTE -> if (c.action == CallCmd.MUTE && calls.ringing && !calls.inCall) {
                calls.quietRinger(); "सर, घंटी धीमी कर दी।"   // कॉल ख़त्म होते ही पुरानी आवाज़ वापस (RingGuard)
            } else muteCall(c.action == CallCmd.MUTE)
            CallCmd.CANCEL -> "ठीक है सर, मैसेज नहीं भेजा।"
            CallCmd.END -> {
                if (!calls.ringing && !calls.inCall && !callPending) return "सर, अभी कोई कॉल नहीं चल रही।"
                val r = endCall(); callPending = false
                if (r == "ERROR_CALL_PERM") "सर, कॉल काटने की परमिशन नहीं है। Settings में DK को फ़ोन की परमिशन दीजिए।"
                else if (r.startsWith("ERROR")) "सर, कॉल नहीं कटी। स्क्रीन पर बटन दबाइए।" else "सर, कॉल काट दी।"
            }
            else -> ""
        }
    }

    /** कमांड चलाओ और नतीजा बोलो (फ़ोन की अपनी आवाज़ - घंटी/कॉल में भी सुनाई दे) */
    suspend fun doCallCmd(c: CallCmd.Cmd) {
        val m = try { runCallCmd(c) } catch (e: Exception) { DKLog.d("call cmd: ${e.message}"); "सर, यह काम नहीं हो पाया।" }
        if (m.isBlank()) return
        DKState.reply(m)
        callAnnouncing = true
        try { speaker.speakQuick(m) } finally { callAnnouncing = false }
    }

    /** v4.1: स्क्रीन पर लिखे (या बटन के विवरण) पर टैप - [OfflineExec] के लिए */
    fun clickVisibleText(vararg words: String): Boolean = clickByText(*words)

    // ---------------------------------------------------------------- तैरता ORB (FloatOrb.kt)
    /** Settings switch / slider, notification बटन, ऐप खुलना - सब यहीं से ORB ठीक करते हैं */
    fun refreshOrb() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { floatOrb?.sync(); DKIsland.poke(); LiveNotify.sync(this) }
    }

    /** ORB टैप = पहले वाले किनारे के ORB जैसा */
    private fun onOrbTap() {
        // call chal rahi ho to orb tap = call kaato (call ke dauraan Android mic nahi deta)
        if (calls.inCall || calls.ringing) scope.launch { perform(JSONObject().put("type", "end_call")) }
        else toggleLive()
    }

}
