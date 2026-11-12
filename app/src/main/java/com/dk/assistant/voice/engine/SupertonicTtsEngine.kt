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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * नवंबर 2026 (मालिक: "Supertonic 3 लगाओ, वैसे ही जैसे Piper लगा था - वही सेटअप; Piper पूरी तरह हटाओ; मॉडल ऐप में नहीं,
 * इंटरनेट से; नेचुरल महिला आवाज़ें"): वैकल्पिक Supertonic 3 TTS - हिंदी, फ़ोन में ही बनती है. डिफ़ॉल्ट बंद.
 * Piper (VITS) वाला रास्ता पूरा हटाया (बैकअप: branch backup/dk-piper-tts-1112).
 *
 * - यह [VoiceEngines.all] की सूची (CLASSES) में नहीं - इसलिए "बोलने वाला इंजन" card और उसकी पसंद जस की तस.
 *   सिर्फ़ जब Settings > "Supertonic 3 आवाज़" का स्विच ON हो (और फ़ाइलें फ़ोन में हों), [override] इसे चालू इंजन बनाता है.
 *   स्विच OFF = [override] null = ठीक वही इंजन जो पहले था (DK लाइव की सीधी आवाज़) - पुरानी पसंद कभी लिखी ही नहीं गई.
 * - ON: Gemini Live सिर्फ़ जवाब लिखता है (उसकी अपनी आवाज़ नहीं बजती - मौजूदा "बँटवारा"), हर वाक्य Supertonic बोलता है.
 * - तेज़ी: [SpeechPipeline] वाक्य (। ? !) बनते ही काटता है, पहला बनते ही बजता है, बाकी पीछे कतार में बनते हैं.
 *   मॉडल ऐप खुलते ही / स्विच ON होते ही मेमोरी में ([warm]). सारा काम पीछे के धागों पर.
 * - गड़बड़ (मॉडल न खुले / बार-बार न बोले): स्विच ON ही ([fail]) - थोड़ी देर Gemini Live की सीधी आवाज़ (Gemini TTS नहीं), फिर Supertonic.
 *   एक वाक्य न बने = उस जवाब का बाकी हिस्सा Live की अपनी आवाज़ (SpeechPipeline का मौजूदा बचाव) - चुप नहीं.
 */
object SupertonicTtsEngine : VoiceEngine {
    const val ID = "supertonic_hi"
    override val id = ID
    override val title = "Supertonic 3 आवाज़ (हिंदी, ऑफ़लाइन)"
    override val note = "फ़ोन में ही बनती है — बिना इंटरनेट"
    override val split = true
    override val defaultOn = false

    /**
     * Supertonic पूरा वाक्य एक साथ बनाता है - छोटा पहला वाक्य = जल्दी पहली आवाज़. पूरे वाक्य (। ? !) पर कटता है;
     * पहला: अल्पविराम पर 2 शब्द से ("जी बिल्कुल,"), ज़बरदस्ती 7 पर (बीच से नहीं - [TextChunker.Profile.smartCut]);
     * बाद वाले बड़े (पहला बजते-बजते बनें).
     */
    val CHUNKS = TextChunker.Profile(
        firstSoft = 2, firstMax = 7,
        secondMin = 4, secondSoft = 8, secondMax = 14,
        nextMin = 6, nextSoft = 12, nextMax = 24,
        stallMs = 200L, stallMin = 2,
        smartCut = true,
    )

    const val SAMPLE = "नमस्ते, मैं DK हूँ। आज मैं आपकी क्या मदद करूँ?"

    /** pure (JVM test): Supertonic बोलेगा? = स्विच ON + फ़ाइलें फ़ोन में */
    fun gate(on: Boolean, installed: Boolean): Boolean = on && installed

    /**
     * pure (JVM test) - (मालिक: "जो चुना वही हमेशा; खराबी हो तो Gemini TTS नहीं, सीधे Gemini Live की आवाज़"):
     * स्विच OFF = null (पहले वाला इंजन). ON + तैयार = Supertonic. ON पर अभी न चले (डाउनलोड बाकी / अभी-अभी टूटा) = Live की
     * सीधी आवाज़ - स्विच ON ही रहता है, ठीक होते ही फिर Supertonic. कभी अपने-आप Gemini TTS नहीं.
     */
    enum class Pick { NONE, SUPERTONIC, LIVE }
    fun pick(on: Boolean, installed: Boolean, failed: Boolean): Pick = when {
        !on -> Pick.NONE
        gate(on, installed) && !failed -> Pick.SUPERTONIC
        else -> Pick.LIVE
    }

    /** स्विच ON + डाउनलोड पूरा = Supertonic; ON पर अभी न चले = Live की सीधी आवाज़; OFF = null (= पहले वाला इंजन) */
    fun override(c: Context): VoiceEngine? = try {
        when (pick(SupertonicStore.on(c), SupertonicStore.installed(c), VoiceEngines.isFailed(ID))) {
            Pick.NONE -> null
            Pick.SUPERTONIC -> this
            Pick.LIVE -> VoiceEngines.byId(VoiceEngines.LIVE_NATIVE)
        }
    } catch (_: Throwable) { null }

    /** वाक्यों के बीच ठहराव - सिर्फ़ Supertonic के टुकड़ों पर Settings का "ठहराव" (बाकी इंजन जस के तस) */
    fun gap(c: Context, engineId: String, base: Int): Int =
        if (engineId == ID) try {
            SupertonicFx.gap(base, SupertonicStore.pause(c)).let { if (humanOn(c)) HumanVoice.gap(it, com.dk.assistant.avatar.MoodState.current()) else it }
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
    override fun voices(): List<Pair<String, String>> = SupertonicStore.VOICES.map { it.id to it.title }
    override fun voice(c: Context): String = SupertonicStore.voice(c)
    /** एक ही मॉडल - आवाज़ बदलना = बस अगले वाक्य की sid (मॉडल दोबारा लोड नहीं) */
    override fun setVoice(c: Context, v: String) { SupertonicStore.setVoice(c, v) }

    override fun notReady(c: Context): String? = when {
        !SupertonicStore.installed(c) -> "Supertonic की आवाज़ अभी डाउनलोड नहीं हुई"
        else -> null
    }

    /** मॉडल पहले से मेमोरी में (ऐप खुलते ही / स्विच ON) - पहली बार बोलने में देरी नहीं. पीछे के धागे से बुलाओ */
    override fun warm(c: Context) {
        if (!SupertonicStore.on(c) || notReady(c) != null) return
        try { ensure(c) } catch (_: Throwable) {}
    }

    /** एक ही ताला: मॉडल खोलना, वाक्य बनाना, मेमोरी खाली करना - कभी एक साथ नहीं (बनते समय मॉडल हटे तो crash) */
    private val lock = Any()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var rate = 44100
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
            System.load(SupertonicStore.libFile(c).absolutePath)
            libLoaded = true
        }
    }

    private fun ensure(c: Context): OfflineTts = synchronized(lock) {
        tts?.let { return it }
        val t0 = System.currentTimeMillis()
        audioPriority()
        try {
            loadLib(c)
            val dir = SupertonicStore.modelDir(c)
            fun f(n: String) = java.io.File(dir, n).absolutePath
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = f("duration_predictor.int8.onnx"), textEncoder = f("text_encoder.int8.onnx"),
                        vectorEstimator = f("vector_estimator.int8.onnx"), vocoder = f("vocoder.int8.onnx"),
                        ttsJson = f("tts.json"), unicodeIndexer = f("unicode_indexer.bin"), voiceStyle = f("voice.bin"),
                    ),
                    numThreads = threads(), debug = false, provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            val t = OfflineTts(null, cfg)
            rate = t.sampleRate().takeIf { it > 0 } ?: 44100
            tts = t
            lastLoadMs = System.currentTimeMillis() - t0
            DKLog.d("Supertonic: मॉडल मेमोरी में (${rate} Hz, ${threads()} धागे) $lastLoadMs ms")
            t
        } catch (e: Throwable) {
            fail(c, "Supertonic का मॉडल नहीं खुला (${e.javaClass.simpleName})")
            throw IOException("Supertonic मॉडल लोड नहीं हुआ: ${e.message}", e)
        }
    }

    /** नवंबर 2026: सिर्फ़ तेज़ (बड़े) कोर जितने धागे ([SupertonicSpeed.threads]) - धीमे कोर मिलाने से हर चरण धीमा */
    private fun threads(): Int = try { SupertonicSpeed.threadsHere() } catch (_: Throwable) { threadsFor(Runtime.getRuntime().availableProcessors()) }

    /** बनाने वाला धागा आवाज़ वाली प्राथमिकता पर (ONNX के अपने धागे मॉडल खुलते समय यही प्राथमिकता लेते हैं) */
    private fun audioPriority() { try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {} }
    /** कोर की ताक़त पता न चले तो: मॉडल भारी है - 8 कोर वाले फ़ोन पर 4 धागे, 4+ पर 3, बाकी 1 */
    fun threadsFor(cores: Int): Int = when {
        cores >= 8 -> 4
        cores >= 4 -> 3
        else -> 1
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        if (!SupertonicStore.on(c) && !previewing) throw IOException("Supertonic बंद है")
        val g = gen.get()
        audioPriority()
        val t0 = System.currentTimeMillis()
        val wasLoaded = tts != null
        val t = ensure(c)
        val say = speakable(text)
        if (say.isBlank()) { out.write(ByteArray(480)); return }      // बोलने लायक कुछ नहीं (जैसे सिर्फ़ emoji) - चुप्पी, ग़लती नहीं
        val tune0 = SupertonicStore.tune(c)
        // नवंबर 2026: इंसान जैसी बोली - भाव से रफ़्तार/सुर, हर वाक्य में हल्का फ़र्क, लंबी बात में धीमी साँस
        val human = humanOn(c)
        val mood = if (human) humanMood(say) else com.dk.assistant.avatar.Mood.NORMAL
        val ht = HumanVoice.tune(mood)
        val tune = if (!human) tune0 else tune0.copy(
            speed = tune0.speed * ht.speedMul * HumanVoice.jitter(say),
            pitch = tune0.pitch * ht.pitchMul * HumanVoice.questionLift(say))
        if (human && !previewing && breather.next(say, System.currentTimeMillis())) out.write(HumanVoice.breath(HumanVoice.breathMs(say), say.length))
        val fx = SupertonicFx(rate, tune.bassDb, tune.reverb)
        val src = SupertonicFx.srcRate(rate, tune.pitch)
        // 44.1 kHz: 24 kHz पर लाने से पहले ऊँची आवृत्ति हटाओ (नहीं तो खरखराहट / aliasing) - आवाज़ साफ़ और मुलायम
        val lp = if (rate > VoiceEngine.RATE) SoftLowpass.forRates(src, VoiceEngine.RATE) else null
        fun pcm(x: FloatArray): ByteArray = Resample.to24k(SupertonicFx.pcm16(lp?.process(x) ?: x), src)
        // नवंबर 2026 (मालिक: "कितनी देर में बोलता है, पता नहीं चलता"): हर वाक्य का असली नाप ([SupertonicTiming])
        var firstAt = 0L; var nSamp = 0L
        val steps = SupertonicSpeed.steps(c)                // नवंबर 2026: चरण फ़ोन की रफ़्तार से अपने-आप
        val sid = SupertonicStore.voiceOf(voice.takeIf { v -> SupertonicStore.VOICES.any { it.id == v } } ?: SupertonicStore.voice(c)).sid
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
                t.generateWithConfigAndCallback(say, genConfig(sid, SupertonicFx.genSpeed(tune.speed, tune.pitch), steps), cb)
            }
            if (gen.get() == g) fx.tail().takeIf { it.isNotEmpty() }?.let { out.write(pcm(it)) }
            fails.set(0)
            if (!previewing) note(g, t0, firstAt, nSamp, say.length, if (wasLoaded) -1L else lastLoadMs)
            if (gen.get() == g && firstAt > 0 && wasLoaded) adapt(c, steps, firstAt - t0, if (rate > 0) nSamp * 1000L / rate else -1L)
        } catch (e: Throwable) {
            if (gen.get() != g) return
            if (fails.incrementAndGet() >= 2) { fails.set(0); fail(c, "Supertonic आवाज़ नहीं बना पाया (${e.javaClass.simpleName})") }
            throw IOException("Supertonic: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** एक वाक्य का नाप: पूरा बना = [SupertonicTiming.spoke]; पहली आवाज़ से पहले रोका गया (Live ने लिया) = [SupertonicTiming.cut] */
    private fun note(g: Int, t0: Long, firstAt: Long, nSamp: Long, chars: Int, loadMs: Long) {
        val now = System.currentTimeMillis()
        if (gen.get() != g) { if (firstAt == 0L) SupertonicTiming.cut(now - t0); return }
        if (firstAt == 0L) return
        val audio = if (rate > 0) nSamp * 1000L / rate else -1L
        SupertonicTiming.spoke(firstAt - t0, now - t0, audio, chars, loadMs)
        DKLog.d("Supertonic: वाक्य ($chars अक्षर) - पहली आवाज़ ${firstAt - t0} ms, पूरा ${now - t0} ms, आवाज़ $audio ms" +
            (if (loadMs >= 0) ", मॉडल लोड $loadMs ms" else ""))
    }

    /** इस वाक्य की रफ़्तार देखकर अगले चरण ([SupertonicSpeed.nextSteps]); स्क्रीन के लिए [SupertonicTiming] में */
    private fun adapt(c: Context, steps: Int, genMs: Long, audioMs: Long) {
        val r = SupertonicSpeed.rtf(genMs, audioMs)
        SupertonicTiming.speed(steps, threads(), r)
        val next = SupertonicSpeed.nextSteps(steps, genMs, audioMs)
        if (next != steps) {
            SupertonicSpeed.setSteps(c, next)
            DKLog.d("Supertonic: रफ़्तार ${String.format(java.util.Locale.US, "%.2f", r)} (बनना/आवाज़) - चरण $steps → $next")
        }
    }

    /** Supertonic 3: कौन-सी आवाज़ (sid), हिंदी, कितने चरण ([steps], फ़ोन की रफ़्तार से [SupertonicSpeed]). pure (JVM test) */
    fun genConfig(sid: Int, speed: Float, steps: Int = SupertonicStore.STEPS): GenerationConfig =
        GenerationConfig(sid = sid.coerceAtLeast(0), speed = speed, numSteps = steps, extra = mapOf("lang" to "hi"))

    /** ▶ टेस्ट: स्विच कुछ भी हो, नमूना बोले ([voice] = जो आवाज़ सुननी है) */
    override fun preview(c: Context, text: String, voice: String): Long {
        previewing = true
        return try { PreviewPlayer.play(c) { out -> speak(c, text, voice, out) } } finally { previewing = false }
    }

    override fun stop() { gen.incrementAndGet() }

    /** मेमोरी खाली (स्विच OFF / मॉडल हटाया) */
    fun release() {
        stop()
        synchronized(lock) { tts?.let { try { it.release() } catch (_: Throwable) {} }; tts = null }
    }

    /** Hinglish (Roman) -> देवनागरी, और बोलने में न आने वाले निशान हटाओ */
    fun speakable(text: String): String {
        val t = try { HinglishText.toSpeakable(text) } catch (_: Throwable) { text }
        return t.replace(Regex("https?://\\S+"), " ").replace(Regex("[*#`_~>|\\[\\]{}<>]+"), " ")
            .replace(Regex("[\\p{So}\\p{Cn}\\x{FE0F}\\x{200D}]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * नवंबर 2026 (मालिक: "जो चुना वही हमेशा; कोई ख़राबी हो तो तुरंत Gemini की सीधी आवाज़ - Gemini TTS नहीं"):
     * स्विच ON ही रहता है: [VoiceEngines.FAIL_MS] (2 मिनट) तक [override] = Gemini Live की सीधी आवाज़, फिर अपने-आप दोबारा Supertonic.
     */
    fun fail(c: Context, why: String) {
        val app = c.applicationContext
        DKLog.d("Supertonic: अभी नहीं चला - $why (स्विच ON ही, थोड़ी देर Gemini Live की सीधी आवाज़)")
        try { SupertonicStore.setError(app, "$why — Supertonic चालू ही है; तब तक DK Gemini Live की सीधी आवाज़ में बोला, थोड़ी देर में फिर Supertonic से।") } catch (_: Throwable) {}
        VoiceEngines.markFailed(ID, why)
        stop()
        VoiceEngines.changed()
        val main = Handler(Looper.getMainLooper())
        main.post {
            try { Toast.makeText(app, "Supertonic आवाज़ में अभी दिक़्क़त — इस बीच DK Gemini Live की सीधी आवाज़ में बोलेगा (Supertonic चालू ही है)", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
        // 2 मिनट बाद: चलता Live चुप होते ही फिर Supertonic पर (नया connection)
        main.postDelayed({ VoiceEngines.changed() }, VoiceEngines.FAIL_MS + 1_000L)
        Thread({ release() }, "dk-supertonic-release").apply { isDaemon = true; start() }
    }
}
