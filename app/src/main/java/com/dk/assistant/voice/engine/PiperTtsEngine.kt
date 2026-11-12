package com.dk.assistant.voice.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dk.assistant.DKLog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * नवंबर 2026 (मालिक): वैकल्पिक Piper TTS - हिंदी, फ़ोन में ही (बिना इंटरनेट). डिफ़ॉल्ट बंद.
 *
 * - यह [VoiceEngines.all] की सूची (CLASSES) में नहीं - इसलिए "बोलने वाला इंजन" card और उसकी पसंद जस की तस.
 *   सिर्फ़ जब Settings > "Piper आवाज़" का स्विच ON हो (और फ़ाइलें फ़ोन में हों), [override] इसे चालू इंजन बनाता है.
 *   स्विच OFF = [override] null = ठीक वही इंजन जो पहले था (DK लाइव की सीधी आवाज़) - पुरानी पसंद कभी लिखी ही नहीं गई.
 * - ON: Gemini Live सिर्फ़ जवाब लिखता है (उसकी अपनी आवाज़ नहीं बजती - मौजूदा "बँटवारा"), हर वाक्य Piper बोलता है.
 * - तेज़ी: [SpeechPipeline] वाक्य (। ? !) बनते ही काटता है, पहला बनते ही बजता है, बाकी पीछे कतार में बनते हैं.
 *   मॉडल ऐप खुलते ही / स्विच ON होते ही मेमोरी में ([warm]). सारा काम पीछे के धागों पर.
 * - गड़बड़ (मॉडल न खुले / बार-बार न बोले): स्विच ON ही ([fail]) - थोड़ी देर Gemini Live की सीधी आवाज़ (Gemini TTS नहीं), फिर Piper.
 *   एक वाक्य न बने = उस जवाब का बाकी हिस्सा Live की अपनी आवाज़ (SpeechPipeline का मौजूदा बचाव) - चुप नहीं.
 */
object PiperTtsEngine : VoiceEngine {
    const val ID = "piper_hi"
    override val id = ID
    override val title = "Piper आवाज़ (हिंदी, ऑफ़लाइन)"
    override val note = "फ़ोन में ही बनती है — बिना इंटरनेट"
    override val split = true
    override val defaultOn = false

    /**
     * Piper (VITS) पूरा वाक्य एक साथ बनाता है - छोटा पहला वाक्य = जल्दी पहली आवाज़. पूरे वाक्य (। ? !) पर कटता है;
     * पहला: अल्पविराम पर 2 शब्द से ("जी बिल्कुल,"), ज़बरदस्ती 7 पर (बीच से नहीं - [TextChunker.Profile.smartCut]);
     * बाद वाले बड़े (पहला बजते-बजते बनें). sandbox नाप: 2 शब्द ≈ 0.2 s, 5 शब्द ≈ 0.42 s में पहली आवाज़.
     */
    val CHUNKS = TextChunker.Profile(
        firstSoft = 2, firstMax = 7,
        secondMin = 4, secondSoft = 8, secondMax = 14,
        nextMin = 6, nextSoft = 12, nextMax = 24,
        stallMs = 200L, stallMin = 2,
        smartCut = true,
    )

    const val SAMPLE = "नमस्ते, मैं DK हूँ। आज मैं आपकी क्या मदद करूँ?"

    /** pure (JVM test): Piper बोलेगा? = स्विच ON + फ़ाइलें फ़ोन में */
    fun gate(on: Boolean, installed: Boolean): Boolean = on && installed

    /**
     * pure (JVM test) - नवंबर 2026 (मालिक: "Piper चुना तो हमेशा Piper; खराबी हो तो Gemini TTS नहीं, सीधे Gemini Live की आवाज़"):
     * स्विच OFF = null (पहले वाला इंजन). ON + तैयार = Piper. ON पर अभी न चले (डाउनलोड बाकी / अभी-अभी टूटा) = Live की सीधी आवाज़ -
     * स्विच ON ही रहता है, ठीक होते ही फिर Piper. कभी अपने-आप Gemini TTS नहीं.
     */
    enum class Pick { NONE, PIPER, LIVE }
    fun pick(on: Boolean, installed: Boolean, failed: Boolean): Pick = when {
        !on -> Pick.NONE
        gate(on, installed) && !failed -> Pick.PIPER
        else -> Pick.LIVE
    }

    /** स्विच ON + डाउनलोड पूरा = Piper; ON पर अभी न चले = Live की सीधी आवाज़; OFF = null (= पहले वाला इंजन) */
    fun override(c: Context): VoiceEngine? = try {
        when (pick(PiperStore.on(c), PiperStore.installed(c), VoiceEngines.isFailed(ID))) {
            Pick.NONE -> null
            Pick.PIPER -> this
            Pick.LIVE -> VoiceEngines.byId(VoiceEngines.LIVE_NATIVE)
        }
    } catch (_: Throwable) { null }

    /** वाक्यों के बीच ठहराव - सिर्फ़ Piper के टुकड़ों पर Settings का "ठहराव" (बाकी इंजन जस के तस) */
    fun gap(c: Context, engineId: String, base: Int): Int =
        if (engineId == ID) try {
            PiperFx.gap(base, PiperStore.pause(c)).let { if (humanOn(c)) HumanVoice.gap(it, com.dk.assistant.avatar.MoodState.current()) else it }
        } catch (_: Throwable) { base } else base

    /** नवंबर 2026: साँस / ठहराव / भाव (Settings, शुरू में चालू) */
    fun humanOn(c: Context): Boolean = try { com.dk.assistant.Prefs.bool(c, HumanVoice.PREF, true) } catch (_: Throwable) { false }

    private val breather = HumanVoice.Breather()

    /** जवाब का भाव (अवतार वाला); न हो तो इसी वाक्य के शब्दों से */
    private fun humanMood(text: String): com.dk.assistant.avatar.Mood = try {
        com.dk.assistant.avatar.MoodState.current().takeIf { it != com.dk.assistant.avatar.Mood.NORMAL }
            ?: com.dk.assistant.avatar.EmotionGuess.of(text)
    } catch (_: Throwable) { com.dk.assistant.avatar.Mood.NORMAL }

    // ------------------------------------------------------------------ VoiceEngine
    override fun voices(): List<Pair<String, String>> = PiperStore.VOICES.map { it.id to it.title }
    override fun voice(c: Context): String = PiperStore.voice(c)
    override fun setVoice(c: Context, v: String) {
        if (v == PiperStore.voice(c)) return
        PiperStore.setVoice(c, v)
        release()                 // अगला वाक्य नई आवाज़ से (पीछे के धागे से बुलाओ)
    }

    override fun notReady(c: Context): String? = when {
        !PiperStore.installed(c) -> "Piper की आवाज़ अभी डाउनलोड नहीं हुई"
        else -> null
    }

    /** मॉडल पहले से मेमोरी में (ऐप खुलते ही / स्विच ON) - पहली बार बोलने में देरी नहीं. पीछे के धागे से बुलाओ */
    override fun warm(c: Context) {
        if (!PiperStore.on(c) || notReady(c) != null) return
        try { ensure(c) } catch (_: Throwable) {}
    }

    /** एक ही ताला: मॉडल खोलना, वाक्य बनाना, मेमोरी खाली करना - कभी एक साथ नहीं (बनते समय मॉडल हटे तो crash) */
    private val lock = Any()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loaded = ""
    @Volatile private var rate = 22050
    @Volatile private var hq = false
    @Volatile private var libLoaded = false
    @Volatile var lastLoadMs = -1L
        private set
    private val gen = AtomicInteger(0)
    private val fails = AtomicInteger(0)
    @Volatile private var previewing = false

    val isLoaded: Boolean get() = tts != null

    /** डाउनलोड हुई .so खोलो (एक बार) */
    fun loadLib(c: Context) {
        if (libLoaded) return
        synchronized(lock) {
            if (libLoaded) return
            System.load(PiperStore.libFile(c).absolutePath)
            libLoaded = true
        }
    }

    /** मॉडल की पहचान: HQ की सारी आवाज़ें एक ही मॉडल (आवाज़ बदलने पर दोबारा लोड नहीं) */
    fun modelKey(id: String): String = PiperStore.voiceOf(id).let { if (it.hq) PiperStore.ST_DIR else it.id }

    private fun ensure(c: Context): OfflineTts = synchronized(lock) {
        val v = PiperStore.voice(c).let { if (PiperStore.voiceOk(c, it)) it else PiperStore.installedVoices(c).firstOrNull()?.id ?: it }
        val key = modelKey(v)
        tts?.let { if (loaded == key) return it; try { it.release() } catch (_: Throwable) {}; tts = null }
        val t0 = System.currentTimeMillis()
        try {
            loadLib(c)
            val vo = PiperStore.voiceOf(v)
            val dir = PiperStore.voiceDir(c, v)
            fun f(n: String) = java.io.File(dir, n).absolutePath
            val cfg = if (vo.hq) OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = f("duration_predictor.int8.onnx"), textEncoder = f("text_encoder.int8.onnx"),
                        vectorEstimator = f("vector_estimator.int8.onnx"), vocoder = f("vocoder.int8.onnx"),
                        ttsJson = f("tts.json"), unicodeIndexer = f("unicode_indexer.bin"), voiceStyle = f("voice.bin"),
                    ),
                    numThreads = threads(true), debug = false, provider = "cpu",
                ),
                maxNumSentences = 1,
            ) else OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = java.io.File(dir, vo.onnxName).absolutePath,
                        tokens = java.io.File(dir, "tokens.txt").absolutePath,
                        dataDir = PiperStore.espeakDir(c).absolutePath,
                    ),
                    numThreads = threads(false), debug = false, provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            val t = OfflineTts(null, cfg)
            rate = t.sampleRate().takeIf { it > 0 } ?: (if (vo.hq) 44100 else 22050)
            hq = vo.hq
            tts = t; loaded = key
            lastLoadMs = System.currentTimeMillis() - t0
            DKLog.d("Piper: मॉडल मेमोरी में ($v, ${rate} Hz, ${threads(vo.hq)} धागे) $lastLoadMs ms")
            t
        } catch (e: Throwable) {
            fail(c, "Piper का मॉडल नहीं खुला (${e.javaClass.simpleName})")
            throw IOException("Piper मॉडल लोड नहीं हुआ: ${e.message}", e)
        }
    }

    /** HQ मॉडल भारी है - 8 कोर वाले फ़ोन पर 4 धागे (पहली आवाज़ जल्दी), बाकी पहले जैसा */
    private fun threads(hq: Boolean): Int = threadsFor(Runtime.getRuntime().availableProcessors(), hq)
    fun threadsFor(cores: Int, hq: Boolean): Int = when {
        hq && cores >= 8 -> 4
        hq && cores >= 4 -> 3
        cores >= 4 -> 2
        else -> 1
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        if (!PiperStore.on(c) && !previewing) throw IOException("Piper बंद है")
        val g = gen.get()
        val t0 = System.currentTimeMillis()
        val wasLoaded = tts != null
        val t = ensure(c)
        val say = speakable(text)
        if (say.isBlank()) { out.write(ByteArray(480)); return }      // बोलने लायक कुछ नहीं (जैसे सिर्फ़ emoji) - चुप्पी, ग़लती नहीं
        val tune0 = PiperStore.tune(c)
        // नवंबर 2026: इंसान जैसी बोली - भाव से रफ़्तार/सुर, हर वाक्य में हल्का फ़र्क, लंबी बात में धीमी साँस
        val human = humanOn(c)
        val mood = if (human) humanMood(say) else com.dk.assistant.avatar.Mood.NORMAL
        val ht = HumanVoice.tune(mood)
        val tune = if (!human) tune0 else tune0.copy(
            speed = tune0.speed * ht.speedMul * HumanVoice.jitter(say),
            pitch = tune0.pitch * ht.pitchMul * HumanVoice.questionLift(say))
        if (human && !previewing && breather.next(say, System.currentTimeMillis())) out.write(HumanVoice.breath(HumanVoice.breathMs(say), say.length))
        val fx = PiperFx(rate, tune.bassDb, tune.reverb)
        val src = PiperFx.srcRate(rate, tune.pitch)
        // HQ = 44.1 kHz: 24 kHz पर लाने से पहले ऊँची आवृत्ति हटाओ (नहीं तो खरखराहट / aliasing) - आवाज़ साफ़ और मुलायम
        val lp = if (rate > VoiceEngine.RATE) SoftLowpass.forRates(src, VoiceEngine.RATE) else null
        fun pcm(x: FloatArray): ByteArray = Resample.to24k(PiperFx.pcm16(lp?.process(x) ?: x), src)
        // नवंबर 2026 (मालिक: "Piper कितनी देर में बोलता है, पता नहीं चलता"): हर वाक्य का असली नाप ([PiperTiming])
        var firstAt = 0L; var nSamp = 0L
        try {
            synchronized(lock) {                   // एक मॉडल - एक समय में एक वाक्य (दूसरा धागा कतार में)
                if (gen.get() != g) return
                // अलग object (lambda नहीं): native कोड ठीक invoke([F)Ljava/lang/Integer; ढूँढता है - Kotlin 2 के indy-lambda में वह नहीं बनता
                val cb = object : (FloatArray) -> Int {
                    override fun invoke(samples: FloatArray): Int {
                        if (gen.get() != g) return 0                        // रोका गया - बाकी मत बनाओ
                        if (firstAt == 0L) firstAt = System.currentTimeMillis()
                        nSamp += samples.size
                        out.write(pcm(fx.process(samples)))
                        return 1
                    }
                }
                if (hq) t.generateWithConfigAndCallback(say, hqConfig(PiperStore.voiceOf(PiperStore.voice(c)).sid, PiperFx.genSpeed(tune.speed, tune.pitch)), cb)
                else t.generateWithCallback(say, 0, PiperFx.genSpeed(tune.speed, tune.pitch), cb)
            }
            if (gen.get() == g) fx.tail().takeIf { it.isNotEmpty() }?.let { out.write(pcm(it)) }
            fails.set(0)
            if (!previewing) note(g, t0, firstAt, nSamp, say.length, if (wasLoaded) -1L else lastLoadMs)
        } catch (e: Throwable) {
            if (gen.get() != g) return
            if (fails.incrementAndGet() >= 2) { fails.set(0); fail(c, "Piper आवाज़ नहीं बना पाया (${e.javaClass.simpleName})") }
            throw IOException("Piper: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** एक वाक्य का नाप: पूरा बना = [PiperTiming.spoke]; पहली आवाज़ से पहले रोका गया (Live ने लिया) = [PiperTiming.cut] */
    private fun note(g: Int, t0: Long, firstAt: Long, nSamp: Long, chars: Int, loadMs: Long) {
        val now = System.currentTimeMillis()
        if (gen.get() != g) { if (firstAt == 0L) PiperTiming.cut(now - t0); return }
        if (firstAt == 0L) return
        val audio = if (rate > 0) nSamp * 1000L / rate else -1L
        PiperTiming.spoke(firstAt - t0, now - t0, audio, chars, loadMs)
        DKLog.d("Piper: वाक्य ($chars अक्षर) - पहली आवाज़ ${firstAt - t0} ms, पूरा ${now - t0} ms, आवाज़ $audio ms" +
            (if (loadMs >= 0) ", मॉडल लोड $loadMs ms" else "") + (if (hq) " (HQ)" else ""))
    }

    /** HQ (Supertonic-3): कौन-सी आवाज़ (sid), हिंदी, हाई क्वालिटी चरण. pure (JVM test) */
    fun hqConfig(sid: Int, speed: Float): GenerationConfig =
        GenerationConfig(sid = sid.coerceAtLeast(0), speed = speed, numSteps = PiperStore.ST_STEPS, extra = mapOf("lang" to "hi"))

    /** ▶ टेस्ट: स्विच कुछ भी हो, नमूना बोले */
    override fun preview(c: Context, text: String, voice: String): Long {
        previewing = true
        return try { PreviewPlayer.play(c) { out -> speak(c, text, voice, out) } } finally { previewing = false }
    }

    override fun stop() { gen.incrementAndGet() }

    /** मेमोरी खाली (स्विच OFF / मॉडल हटाया / आवाज़ बदली) */
    fun release() {
        stop()
        synchronized(lock) { tts?.let { try { it.release() } catch (_: Throwable) {} }; tts = null; loaded = "" }
    }

    /** Hinglish (Roman) -> देवनागरी, और बोलने में न आने वाले निशान हटाओ */
    fun speakable(text: String): String {
        val t = try { HinglishText.toSpeakable(text) } catch (_: Throwable) { text }
        return t.replace(Regex("https?://\\S+"), " ").replace(Regex("[*#`_~>|\\[\\]{}<>]+"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{FE0F}\\x{200D}]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * नवंबर 2026 (मालिक: "Piper चुना है तो हमेशा Piper; कोई ख़राबी हो तो तुरंत Gemini की सीधी आवाज़ - Gemini TTS नहीं"):
     * पहले यहाँ स्विच अपने-आप OFF होता था (और फिर पुरानी पसंद - कभी Gemini TTS - चल पड़ती थी). अब स्विच ON ही रहता है:
     * [VoiceEngines.FAIL_MS] (2 मिनट) तक [override] = Gemini Live की सीधी आवाज़, फिर अपने-आप दोबारा Piper.
     */
    fun fail(c: Context, why: String) {
        val app = c.applicationContext
        DKLog.d("Piper: अभी नहीं चला - $why (स्विच ON ही, थोड़ी देर Gemini Live की सीधी आवाज़)")
        try { PiperStore.setError(app, "$why — Piper चालू ही है; तब तक DK Gemini Live की सीधी आवाज़ में बोला, थोड़ी देर में फिर Piper से।") } catch (_: Throwable) {}
        VoiceEngines.markFailed(ID, why)
        stop()
        VoiceEngines.changed()
        val main = Handler(Looper.getMainLooper())
        main.post {
            try { Toast.makeText(app, "Piper आवाज़ में अभी दिक़्क़त — इस बीच DK Gemini Live की सीधी आवाज़ में बोलेगा (Piper चालू ही है)", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
        // 2 मिनट बाद: चलता Live चुप होते ही फिर Piper पर (नया connection)
        main.postDelayed({ VoiceEngines.changed() }, VoiceEngines.FAIL_MS + 1_000L)
        Thread({ release() }, "dk-piper-release").apply { isDaemon = true; start() }
    }
}
