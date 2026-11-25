package com.dk.assistant.translate

import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.dk.assistant.DKAccessibilityService
import com.dk.assistant.DKLog
import com.dk.assistant.DKState
import com.dk.assistant.GeminiLive
import com.dk.assistant.NetProbe
import com.dk.assistant.Prefs
import com.dk.assistant.Quiet
import com.dk.assistant.Setup
import com.dk.assistant.voice.SileroVad
import kotlin.math.sqrt

/**
 * 25 नवंबर 2026 (मालिक: "DK ट्रांसलेटर (Live Interpreter = सीधा दुभाषिया)"): सब कुछ जोड़ने वाला हिस्सा.
 *
 * ऑनलाइन: दो Gemini Live session ([TrLive]) - A = सामने वाला -> हिंदी (ईयरफोन), B = मेरी हिंदी -> चुनी भाषा (फ़ोन स्पीकर).
 * - माइक एक बार चालू = लगातार सुनना; सिर्फ़ असली आवाज़ (VAD) भेजी जाती है, चुप्पी नहीं.
 * - Language filter: A की लिखावट से भाषा ([TrLang.verdict]) - मेरी हिंदी = चुपचाप छोड़ो; अनुवाद पक्का होने तक आवाज़ रुकी रहती है.
 * - बोलने का बटन (push-to-talk) दबा = A रुका, आवाज़ B को; छोड़ते ही B जवाब; B की आवाज़ खत्म + 0.3 s = सुनना अपने-आप वापस.
 * - सिर्फ़ तीन स्थितियाँ: सुन रहा है / अनुवाद हो रहा है / बोल रहा है.
 * ऑफ़लाइन (key / नेट नहीं): फ़ोन की बोली-पहचान + IndicTrans2 ([TrOfflineMt]) + फ़ोन की आवाज़ ([TrTts]) - [TrOffline].
 *
 * DK Live: बाय डिफ़ॉल्ट बंद; "DK ट्रांसलेटर चालू करो" = DK Live का माइक बंद, ट्रांसलेटर का चालू (एक बार में एक ही),
 * बंद करने पर DK Live पहले जैसा.
 */
object TrEngine {
    enum class St(val hi: String) { LISTENING("सुन रहा है"), TRANSLATING("अनुवाद हो रहा है"), SPEAKING("बोल रहा है") }

    /** स्क्रीन की एक पंक्ति: orig = जो बोला गया, tr = अनुवाद, read = अनुवाद हिंदी लिपि में पढ़ने लायक (तमिल वगैरह) */
    class Line(val mine: Boolean, val lang: String) {
        @Volatile var orig = ""
        @Volatile var tr = ""
        @Volatile var read = ""
        @Volatile var note = ""
    }

    interface Ui {
        fun onStatus(st: St)
        fun onLines()
        fun onRoute(s: String)
        fun onNote(s: String)
        fun onClosed()
    }

    const val VOICE = "Zephyr"
    private const val FRAME = 640 // 40 ms @16 kHz
    private const val VERDICT_MS = 800L
    private const val MAX_LINES = 40

    @Volatile var ui: Ui? = null
    @Volatile var running = false
        private set
    @Volatile var chosen = TrLang.ALL.first().code
        private set
    @Volatile var status = St.LISTENING
        private set
    @Volatile var micOn = true
        private set
    @Volatile var offline = false
        private set
    @Volatile var route = ""
        private set
    @Volatile var lastNote = ""
        private set
    /** Auto में आख़िरी पहचानी भाषा - मेरी बात उसी भाषा में जाती है */
    @Volatile var detected = ""
        private set

    val top = java.util.concurrent.CopyOnWriteArrayList<Line>()
    val bottom = java.util.concurrent.CopyOnWriteArrayList<Line>()

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var app: Context? = null
    private var audio: TrAudio? = null
    private var a: TrLive? = null
    private var b: TrLive? = null
    private var bTarget = ""
    private var micTh: Thread? = null
    private var off: TrOffline? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var ptt = false
    @Volatile private var bBusyUntil = 0L
    @Volatile private var pauseUntil = 0L

    /** Live की आवाज़ न आए तो बोलने वाला (Gemini TTS -> फ़ोन की आवाज़) - [TrVoice] */
    private var speak: TrSpeak? = null

    // A का चल रहा turn (lock में)
    private var aGotAudio = false
    private var aSpoken = false
    private var aLine: Line? = null
    private var aVerdict: TrLang.Verdict? = null
    private val aBuf = ArrayList<ByteArray>()
    private var aFirst = 0L
    private val aIn = StringBuilder()
    private val aOut = StringBuilder()
    // B का चल रहा turn
    private var bLine: Line? = null
    private var bGotAudio = false
    private var bSpoken = false
    private val bIn = StringBuilder()
    private val bOut = StringBuilder()

    // DK Live के साथ "एक बार में एक ही माइक"
    private var prevMuted = false
    private var restoreLive = false

    // ---------------------------------------------------------------- शुरू / बंद

    fun start(c0: Context, lang: String) {
        val c = c0.applicationContext
        if (running) { setLang(lang); return }
        running = true; app = c; chosen = lang; micOn = true; ptt = false; detected = ""
        top.clear(); bottom.clear(); lastNote = ""
        // DK Live का माइक बंद (एक बार में एक ही)
        prevMuted = DKState.micMuted
        if (DKState.live) { restoreLive = true; try { DKAccessibilityService.instance?.stopLive("DK ट्रांसलेटर", quiet = true) } catch (e: Exception) { Quiet.note(e) } }
        DKState.setMicMuted(true)
        Setup.startKeepAlive(c)
        val au = TrAudio(c) { r -> route = r; post { ui?.onRoute(r) } }
        audio = au; au.start()
        speak = TrSpeak(c) { note(it) }
        watchNet(c)
        val key = GeminiLive.key(c)
        if (key.isBlank() || !NetProbe.online(c)) goOffline(if (key.isBlank()) TrPrompt.NO_KEY else "नेट नहीं - ऑफ़लाइन अनुवाद")
        else goOnline()
        setStatus(St.LISTENING)
        DKLog.d("Translator: चालू (भाषा $lang, ${if (offline) "ऑफ़लाइन" else "ऑनलाइन"})")
    }

    fun stop() {
        if (!running) return
        running = false
        stopOnline(); off?.stop(); off = null
        main.removeCallbacks(aWatch); main.removeCallbacks(bWatch)
        speak?.close(); speak = null
        audio?.stop(); audio = null
        app?.let { c -> netCb?.let { cb -> try { (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Exception) { Quiet.note(e) } } }
        netCb = null
        DKState.setMicMuted(prevMuted)
        if (restoreLive) { restoreLive = false; main.postDelayed({ try { DKAccessibilityService.instance?.startLive() } catch (e: Exception) { Quiet.note(e) } }, 500) }
        DKLog.d("Translator: बंद")
        post { ui?.onClosed() }
    }

    /** DK Live से: "DK ट्रांसलेटर चालू / बंद करो" */
    fun voice(svc: DKAccessibilityService, on: Boolean): String {
        if (!on) {
            if (!running) return "SAID: DK ट्रांसलेटर पहले से बंद है।"
            main.post { stop() }
            return "SAID: DK ट्रांसलेटर बंद कर दिया।"
        }
        if (running) return "SAID: DK ट्रांसलेटर पहले से चालू है।"
        val wasLive = DKState.live
        // DK Live अपनी \"ठीक है\" बोल ले, फिर उसका माइक बंद और ट्रांसलेटर का चालू
        main.postDelayed({
            if (wasLive || DKState.live) {
                restoreLive = true
                try { svc.stopLive("DK ट्रांसलेटर", quiet = true) } catch (e: Exception) { Quiet.note(e) }
            }
            TranslatorActivity.open(svc)
        }, 900)
        return "SAID: DK ट्रांसलेटर चालू कर रहा हूँ - अब मेरा माइक बंद, ट्रांसलेटर सुनेगा।"
    }

    fun setLang(code: String) {
        if (code == chosen) return
        chosen = code; detected = ""
        if (!running) return
        if (offline) off?.setLang(code) else { rebuildA(); rebuildB(force = true) }
    }

    fun setMic(on: Boolean) {
        micOn = on
        if (offline) off?.setMic(on)
        if (!on) a?.streamEnd()
    }

    // ---------------------------------------------------------------- बोलने का बटन

    fun pttDown() {
        if (!running) return
        ptt = true
        audio?.hindi?.flush()
        if (offline) { off?.pttDown(); return }
        rebuildB(force = false)
        a?.streamEnd()
        synchronized(lock) { newBLine() }
        setStatus(St.LISTENING)
    }

    fun pttUp() {
        if (!running || !ptt) return
        ptt = false
        if (offline) { off?.pttUp(); return }
        b?.streamEnd()
        bBusyUntil = System.currentTimeMillis() + 12_000
        setStatus(St.TRANSLATING)
    }

    /** लिखी हिंदी -> चुनी भाषा */
    fun typed(t: String) {
        val s = t.trim(); if (!running || s.isEmpty()) return
        if (TrCommands.parse(s)?.action?.optBoolean("on", true) == false) { main.post { stop() }; return }
        if (offline) { off?.typed(s); return }
        rebuildB(force = false)
        synchronized(lock) { newBLine().orig = s; bIn.append(s) }
        lines()
        bBusyUntil = System.currentTimeMillis() + 12_000
        b?.sendText(s)
        setStatus(St.TRANSLATING)
    }

    // ---------------------------------------------------------------- ऑनलाइन

    private fun models(c: Context) = (listOf(Prefs.liveModel(c)) + GeminiLive.FALLBACK_MODELS).filter { it.isNotBlank() }.distinct()

    private fun target(): String = when {
        chosen != TrLang.AUTO -> chosen
        detected.isNotEmpty() -> detected
        else -> "en"
    }

    private fun goOnline() {
        val c = app ?: return
        off?.stop(); off = null
        offline = false
        rebuildA(); rebuildB(force = true)
        startMic(c)
    }

    private fun stopOnline() {
        micTh?.interrupt(); micTh = null
        a?.stop(); a = null; b?.stop(); b = null; bTarget = ""
    }

    private fun rebuildA() {
        val c = app ?: return
        a?.stop()
        a = TrLive("A", GeminiLive.key(c), models(c), TrPrompt.toHindi(chosen), VOICE, aCb).also { it.start() }
        synchronized(lock) { resetA() }
    }

    private fun rebuildB(force: Boolean) {
        val c = app ?: return
        val t = target()
        if (!force && b != null && t == bTarget) return
        b?.stop()
        bTarget = t
        if (chosen == TrLang.AUTO && detected.isEmpty()) note("Auto: सामने वाले की भाषा अभी पता नहीं - आपकी बात English में जाएगी")
        b = TrLive("B", GeminiLive.key(c), models(c), TrPrompt.fromHindi(t), VOICE, bCb).also { it.start() }
    }

    private fun goOffline(why: String) {
        val c = app ?: return
        stopOnline()
        offline = true
        note(why)
        val au = audio ?: return
        off = TrOffline(c, au, chosen, offCb).also { it.start() }
    }

    private fun watchNet(c: Context) {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                post {
                    if (!running || offline) return@post
                    // थोड़ा रुको - अक्सर नेट तुरंत लौट आता है (TrLive खुद चुपचाप दोबारा जुड़ता है)
                    main.postDelayed({ if (running && !offline && !NetProbe.online(c) && TrOfflineMt.downloaded(c)) goOffline("नेट गया - ऑफ़लाइन अनुवाद") }, 4000)
                }
            }
            override fun onAvailable(network: Network) {
                post { if (running && offline && GeminiLive.key(c).isNotBlank()) main.postDelayed({ if (running && offline && NetProbe.online(c)) { note("नेट वापस - ऑनलाइन अनुवाद"); goOnline() } }, 1500) }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (e: Exception) { Quiet.note(e) }
    }

    // ---------------------------------------------------------------- माइक (ऑनलाइन)

    private fun startMic(c: Context) {
        micTh?.interrupt()
        micTh = Thread({ micLoop(c) }, "dk-tr-mic").apply { isDaemon = true; start() }
    }

    /** A को रोके रखना है? (बटन दबा / सामने वाले को आवाज़ जा रही / हिंदी स्पीकर पर बज रही) */
    private fun aHeld(): Boolean {
        val au = audio ?: return true
        val now = System.currentTimeMillis()
        val busy = ptt || !micOn || now < bBusyUntil || au.partner.busy() || (au.headset == null && (au.hindi.busy() || speak?.pending() == true))
        if (busy) pauseUntil = now + 300
        return busy || now < pauseUntil
    }

    private fun micLoop(c: Context) {
        val rec: AudioRecord = audio?.openMic() ?: run { note("माइक नहीं खुला - माइक की अनुमति दें"); return }
        val vad = try { SileroVad.open(c) } catch (e: Exception) { Quiet.note(e); null }
        val buf = ShortArray(FRAME)
        val pre = ArrayDeque<ByteArray>()
        var inSpeech = false; var silent = 0; var spoke = 0
        try {
            rec.startRecording()
            while (running && !offline && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, FRAME)
                if (n <= 0) continue
                val bytes = toBytes(buf, n)
                if (ptt) { b?.send(bytes); continue }
                if (aHeld()) {
                    if (inSpeech) { a?.streamEnd(); inSpeech = false }
                    pre.clear(); vad?.reset(); continue
                }
                val p = vad?.feed(buf, n) ?: -1f
                val loud = rms(buf, n)
                val speech = if (p >= 0f) p > (if (inSpeech) 0.35f else 0.5f) else loud > (if (inSpeech) 450.0 else 700.0)
                if (!inSpeech) {
                    pre.addLast(bytes); while (pre.size > 8) pre.removeFirst()
                    if (speech) {
                        inSpeech = true; silent = 0; spoke = 0
                        pre.forEach { a?.send(it) }; pre.clear()
                        if (status != St.SPEAKING) setStatus(St.LISTENING)
                    }
                } else {
                    a?.send(bytes); spoke += 40
                    silent = if (speech) 0 else silent + 40
                    // ठहराव पर / लंबी बात में छोटे ठहराव पर / 8 सेकंड पर - अनुवाद शुरू (streaming: टुकड़ों में)
                    // 25 नवंबर 2026 (मालिक: "जवाब तुरंत बने"): ठहराव 480 -> 320 ms, लंबी बात 4.5 -> 3.5 s
                    if (TrVoice.turnEnds(spoke, silent)) {
                        a?.streamEnd(); inSpeech = false
                        if (status == St.LISTENING) setStatus(St.TRANSLATING)
                    }
                }
                checkVerdictTimeout()
            }
        } catch (e: Exception) { Quiet.note(e) } finally {
            try { rec.stop() } catch (e: Exception) { Quiet.note(e) }
            try { rec.release() } catch (e: Exception) { Quiet.note(e) }
            vad?.close()
        }
    }

    // ---------------------------------------------------------------- A: सामने वाला -> हिंदी

    private fun resetA() { aLine = null; aVerdict = null; aBuf.clear(); aFirst = 0; aIn.setLength(0); aOut.setLength(0); aGotAudio = false; aSpoken = false }

    /** Live ने अनुवाद लिखा पर आवाज़ नहीं दी = दूसरे रास्ते से बोलो (चुप नहीं) */
    private fun fallbackA() {
        val c = app ?: return
        val au = audio ?: return
        val text = synchronized(lock) {
            val play = aVerdict == TrLang.Verdict.PLAY
            if (!TrVoice.needFallback(aGotAudio, aOut.toString(), aSpoken, !play)) return
            aSpoken = true; aOut.toString().trim()
        }
        DKLog.d("Translator A: Live की आवाज़ नहीं आई - Gemini TTS / फ़ोन की आवाज़ से")
        setStatus(St.SPEAKING)
        speak?.speak(text, TrLang.HINDI, au.hindi, Prefs.liveGeminiKeys(c), NetProbe.online(c))
        idleSoon()
    }

    private fun fallbackB() {
        val c = app ?: return
        val au = audio ?: return
        val (text, t) = synchronized(lock) {
            if (!TrVoice.needFallback(bGotAudio, bOut.toString(), bSpoken, false)) return
            bSpoken = true; bOut.toString().trim() to (bLine?.lang ?: bTarget)
        }
        DKLog.d("Translator B: Live की आवाज़ नहीं आई - Gemini TTS / फ़ोन की आवाज़ से")
        bBusyUntil = System.currentTimeMillis() + 4000
        setStatus(St.SPEAKING)
        speak?.speak(text, t, au.partner, Prefs.liveGeminiKeys(c), NetProbe.online(c))
        idleSoon()
    }

    /** लिखा आ गया, आवाज़ नहीं, और कुछ देर से कुछ नया नहीं (turn अटका / socket टूटा) = आगे बढ़ो */
    private val aWatch = Runnable { if (running && !offline) { decide(true); fallbackA() } }
    private val bWatch = Runnable { if (running && !offline) fallbackB() }

    private fun aLineOrNew(): Line = aLine ?: Line(false, chosen).also { aLine = it; add(top, it) }

    private fun checkVerdictTimeout() {
        val go = synchronized(lock) { aVerdict == null && aFirst > 0 && System.currentTimeMillis() - aFirst > VERDICT_MS }
        if (go) decide(true)
    }

    /** भाषा तय करो: PLAY = रुकी हुई हिंदी आवाज़ बजाओ; DROP = मेरी हिंदी - सब फेंको; UNSURE = "समझ नहीं आई" */
    private fun decide(final: Boolean) {
        val au = audio ?: return
        var msg = ""
        synchronized(lock) {
            if (aVerdict != null) return
            val heard = aIn.toString()
            val v = if (heard.isBlank()) (if (final) TrLang.Verdict.PLAY else TrLang.Verdict.WAIT) else TrLang.verdict(chosen, heard, final)
            if (v == TrLang.Verdict.WAIT) return
            aVerdict = v
            when (v) {
                TrLang.Verdict.PLAY -> {
                    aBuf.forEach { au.hindi.play(it) }; aBuf.clear()
                    val g = TrLang.guess(heard)
                    if (chosen == TrLang.AUTO && g.isNotEmpty() && g != TrLang.UNKNOWN && g != TrLang.HINDI && g != detected) { detected = g; main.post { if (running && !offline) rebuildB(force = false) } }
                }
                TrLang.Verdict.DROP -> { aBuf.clear(); au.hindi.flush(); aLine?.let { top.remove(it) } }
                else -> { aBuf.clear(); au.hindi.flush(); aLine?.let { it.note = TrPrompt.NOT_UNDERSTOOD }; msg = TrPrompt.NOT_UNDERSTOOD }
            }
            Unit
        }
        if (msg.isNotEmpty()) note(msg)
        lines()
    }

    private val aCb = object : TrLive.Cb {
        override fun onInText(t: String) {
            synchronized(lock) { aIn.append(t); aLineOrNew().orig = aIn.toString().trim() }
            decide(false); lines()
        }
        override fun onOutText(t: String) {
            var drop = false
            synchronized(lock) {
                aOut.append(t)
                val l = aLineOrNew(); l.tr = aOut.toString().trim()
                if (aVerdict != TrLang.Verdict.DROP && TrLang.sameAsHeard(aIn.toString(), aOut.toString()) && TrLang.guess(aIn.toString()) == "" &&
                    chosen != TrLang.ALL.first().code) drop = true
            }
            if (drop) synchronized(lock) { aVerdict = TrLang.Verdict.DROP; aBuf.clear(); audio?.hindi?.flush(); aLine?.let { top.remove(it) } }
            if (!synchronized(lock) { aGotAudio }) { main.removeCallbacks(aWatch); main.postDelayed(aWatch, TrVoice.NO_AUDIO_MS) }
            lines()
        }
        override fun onAudio(pcm: ByteArray) {
            val au = audio ?: return
            var play = false
            synchronized(lock) {
                // दूसरे रास्ते से बोल चुके = देर से आई Live आवाज़ दोबारा नहीं
                if (aSpoken) return
                aGotAudio = true
                when (aVerdict) {
                    null -> { aBuf.add(pcm); if (aFirst == 0L) aFirst = System.currentTimeMillis() }
                    TrLang.Verdict.PLAY -> play = true
                    else -> Unit
                }
            }
            main.removeCallbacks(aWatch)
            if (play) { au.hindi.play(pcm); setStatus(St.SPEAKING) }
            checkVerdictTimeout()
        }
        override fun onTurnDone() {
            decide(true)
            main.removeCallbacks(aWatch)
            fallbackA()
            synchronized(lock) { resetA() }
            lines(); idleSoon()
        }
        override fun onState(ready: Boolean) { if (!ready) DKLog.d("Translator A: दोबारा जुड़ रहा") }
        override fun onDead(why: String) { post { if (running && !offline) goOffline("ऑनलाइन अनुवाद नहीं चला ($why) - ऑफ़लाइन तरीका") } }
    }

    // ---------------------------------------------------------------- B: मेरी हिंदी -> चुनी भाषा

    private fun newBLine(): Line { bIn.setLength(0); bOut.setLength(0); bGotAudio = false; bSpoken = false; return Line(true, target()).also { bLine = it; add(bottom, it) } }

    private val bCb = object : TrLive.Cb {
        override fun onInText(t: String) {
            val s = synchronized(lock) { bIn.append(t); (bLine ?: newBLine()).orig = bIn.toString().trim(); bIn.toString() }
            // बोलकर बंद: "ट्रांसलेटर बंद करो"
            if (TrCommands.parse(s)?.action?.optBoolean("on", true) == false) { audio?.partner?.flush(); main.post { stop() }; return }
            lines()
        }
        override fun onOutText(t: String) {
            val got = synchronized(lock) { bOut.append(t); val l = bLine ?: newBLine(); l.tr = bOut.toString().trim(); l.read = TrLang.readInHindi(l.tr); bGotAudio }
            if (!got) { main.removeCallbacks(bWatch); main.postDelayed(bWatch, TrVoice.NO_AUDIO_MS) }
            lines()
        }
        override fun onAudio(pcm: ByteArray) {
            val late = synchronized(lock) {
                if (!bSpoken) {
                    bGotAudio = true
                }
                bSpoken
            }
            if (late) return
            main.removeCallbacks(bWatch)
            audio?.partner?.play(pcm)
            bBusyUntil = System.currentTimeMillis() + 1500
            setStatus(St.SPEAKING)
        }
        override fun onTurnDone() {
            bBusyUntil = 0
            main.removeCallbacks(bWatch)
            fallbackB()
            synchronized(lock) { bLine = null; bIn.setLength(0); bOut.setLength(0); bGotAudio = false; bSpoken = false }
            lines(); idleSoon()
        }
        override fun onState(ready: Boolean) { if (!ready) DKLog.d("Translator B: दोबारा जुड़ रहा") }
        override fun onDead(why: String) { post { if (running && !offline) goOffline("ऑनलाइन अनुवाद नहीं चला ($why) - ऑफ़लाइन तरीका") } }
    }

    /** आवाज़ खत्म होते ही "सुन रहा है" */
    private fun idleSoon() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                val au = audio ?: return
                if (au.hindi.busy() || au.partner.busy()) { main.postDelayed(this, 200); return }
                if (status != St.LISTENING && !ptt) setStatus(St.LISTENING)
            }
        }, 200)
    }

    // ---------------------------------------------------------------- ऑफ़लाइन रास्ते से

    private val offCb = object : TrOffline.Cb {
        override fun status(st: St) = setStatus(st)
        override fun line(mine: Boolean, lang: String): Line = Line(mine, lang).also { add(if (mine) bottom else top, it) }
        override fun remove(l: Line) { top.remove(l); bottom.remove(l) }
        override fun changed() = lines()
        override fun note(s: String) = this@TrEngine.note(s)
        override fun detected(code: String) { detected = code }
        override fun closeAsked() { main.post { stop() } }
    }

    // ---------------------------------------------------------------- UI

    private fun add(list: MutableList<Line>, l: Line) { list.add(l); while (list.size > MAX_LINES) list.removeAt(0) }

    fun setStatus(st: St) {
        if (status == st) return
        status = st; post { ui?.onStatus(st) }
    }

    private fun note(s: String) { lastNote = s; DKLog.d("Translator: $s"); post { ui?.onNote(s) } }

    private fun lines() = post { ui?.onLines() }

    private fun post(r: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) r() else main.post(r) }

    private fun toBytes(s: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) { val v = s[i].toInt(); out[2 * i] = (v and 0xFF).toByte(); out[2 * i + 1] = ((v shr 8) and 0xFF).toByte() }
        return out
    }

    private fun rms(s: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = s[i].toDouble(); sum += v * v }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    /** Settings / अन्य जगह से खोलने का Intent */
    fun intent(c: Context): Intent = Intent(c, TranslatorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
