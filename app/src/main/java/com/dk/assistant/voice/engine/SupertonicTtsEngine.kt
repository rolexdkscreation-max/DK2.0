package com.dk.assistant.voice.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dk.assistant.DKLog
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * नवंबर 2026 (मालिक: "पहला TTS पूरी तरह डिलीट, फिर वही TTS नए सिरे से - महिला आवाज़ें, सिर्फ़ स्पीड और पिच, मॉडल APK में नहीं"):
 * Supertonic 3 - हिंदी, फ़ोन में ही बनती है (बिना इंटरनेट). डिफ़ॉल्ट बंद.
 *
 * - नई पहचान: [ID] "supertonic3_hi", अपनी prefs/फ़ोल्डर ([SupertonicStore]) - पुराने हटाए TTS की कोई चीज़ नहीं छूता.
 * - चालू/बंद = [VoiceEngines] की एक ही "active" पसंद (अलग स्विच-state नहीं): Settings > "Supertonic 3 आवाज़" का स्विच
 *   [VoiceModeController.switch] से इसे चुनता है - DK लाइव / Gemini TTS अपने-आप बंद. उनमें से कोई चुना = यह बंद.
 * - चालू: Gemini Live सिर्फ़ जवाब लिखता है, हर वाक्य Supertonic बोलता है ([SpeechPipeline] वाक्य बनते ही काटता है).
 * - गड़बड़ (मॉडल न खुले / बार-बार न बने / अभी डाउनलोड नहीं): [liveOnTrouble] - उस समय Gemini Live की सीधी आवाज़,
 *   कभी अपने-आप Gemini TTS नहीं. 2 मिनट बाद फिर Supertonic. पसंद Supertonic ही रहती है.
 * - स्पीड / पिच इंजन के अंदर ही ([SupertonicAudio]); ऊपर वाली आम स्पीड/पिच (VoiceTune) इस पर दोबारा नहीं लगती.
 */
object SupertonicTtsEngine : VoiceEngine {
    const val ID = "supertonic3_hi"
    override val id = ID
    override val title = "Supertonic 3 आवाज़ (हिंदी, ऑफ़लाइन)"
    override val note = "फ़ोन में ही बनती है — बिना इंटरनेट"
    override val split = true
    override val defaultOn = false
    override val liveOnTrouble = true
    override val ownCard = true
    override val ownTune = true

    /**
     * Supertonic पूरा वाक्य एक साथ बनाता है - छोटा पहला वाक्य = जल्दी पहली आवाज़. पूरे वाक्य (। ? !) पर कटता है;
     * पहला: अल्पविराम पर 2 शब्द से, ज़बरदस्ती 7 पर; बाद वाले बड़े (पहला बजते-बजते बनें).
     */
    val CHUNKS = TextChunker.Profile(
        firstSoft = 2, firstMax = 7,
        secondMin = 4, secondSoft = 8, secondMax = 14,
        nextMin = 6, nextSoft = 12, nextMax = 24,
        stallMs = 200L, stallMin = 2,
        smartCut = true,
    )

    const val SAMPLE = "नमस्ते, मैं DK हूँ। आज मैं आपकी क्या मदद करूँ?"

    // ------------------------------------------------------------------ VoiceEngine
    override fun voices(): List<Pair<String, String>> = SupertonicStore.VOICES.map { it.id to it.title }
    override fun voice(c: Context): String = SupertonicStore.voice(c)
    /** एक ही मॉडल - आवाज़ बदलना = बस अगले वाक्य की sid (मॉडल दोबारा लोड नहीं) */
    override fun setVoice(c: Context, v: String) { SupertonicStore.setVoice(c, v) }

    override fun notReady(c: Context): String? =
        if (!SupertonicStore.installed(c)) "Supertonic की आवाज़ अभी डाउनलोड नहीं हुई" else null

    /** मॉडल पहले से मेमोरी में - पहली बार बोलने में देरी नहीं. पीछे के धागे से बुलाओ */
    override fun warm(c: Context) {
        if (notReady(c) != null || VoiceEngines.isFailed(ID)) return
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
            val f = SupertonicStore.libFile(c)
            try { f.setReadOnly() } catch (_: Throwable) {}        // Android 14+: System.load सिर्फ़ read-only फ़ाइल
            System.load(f.absolutePath)
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
            DKLog.d("Supertonic3: मॉडल मेमोरी में (${rate} Hz, ${threads()} धागे) $lastLoadMs ms")
            t
        } catch (e: Throwable) {
            fail(c, "Supertonic का मॉडल नहीं खुला (${e.javaClass.simpleName})")
            throw IOException("Supertonic मॉडल लोड नहीं हुआ: ${e.message}", e)
        }
    }

    /** सिर्फ़ तेज़ (बड़े) कोर जितने धागे ([SupertonicSpeed.threads]) - धीमे कोर मिलाने से हर चरण धीमा */
    private fun threads(): Int = try { SupertonicSpeed.threadsHere() } catch (_: Throwable) { threadsFor(Runtime.getRuntime().availableProcessors()) }

    private fun audioPriority() { try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {} }
    /** कोर की ताक़त पता न चले तो: 8 कोर = 4 धागे, 4+ = 3, बाकी 1 */
    fun threadsFor(cores: Int): Int = when {
        cores >= 8 -> 4
        cores >= 4 -> 3
        else -> 1
    }

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        if (!previewing && VoiceEngines.activeId(c) != ID) throw IOException("Supertonic चुना नहीं है")
        val g = gen.get()
        audioPriority()
        val t0 = System.currentTimeMillis()
        val wasLoaded = tts != null
        val t = ensure(c)
        val say = speakable(text)
        if (say.isBlank()) { out.write(ByteArray(480)); return }      // बोलने लायक कुछ नहीं (जैसे सिर्फ़ emoji) - चुप्पी, ग़लती नहीं
        val speed = SupertonicStore.speed(c) / 100f
        val pitch = SupertonicStore.pitch(c) / 100f
        val src = SupertonicAudio.srcRate(rate, pitch)
        // 44.1 kHz: 24 kHz पर लाने से पहले ऊँची आवृत्ति हटाओ (नहीं तो खरखराहट) - आवाज़ साफ़ और मुलायम
        val lp = if (src > VoiceEngine.RATE) SoftLowpass.forRates(src, VoiceEngine.RATE) else null
        fun pcm(x: FloatArray): ByteArray = Resample.to24k(SupertonicAudio.pcm16(lp?.process(x) ?: x), src)
        var firstAt = 0L; var nSamp = 0L
        val steps = SupertonicSpeed.steps(c)                // चरण फ़ोन की रफ़्तार से अपने-आप
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
                        out.write(pcm(samples))
                        return 1
                    }
                }
                t.generateWithConfigAndCallback(say, genConfig(sid, SupertonicAudio.genSpeed(speed, pitch), steps), cb)
            }
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
        DKLog.d("Supertonic3: वाक्य ($chars अक्षर) - पहली आवाज़ ${firstAt - t0} ms, पूरा ${now - t0} ms, आवाज़ $audio ms" +
            (if (loadMs >= 0) ", मॉडल लोड $loadMs ms" else ""))
    }

    /** इस वाक्य की रफ़्तार देखकर अगले चरण ([SupertonicSpeed.nextSteps]) */
    private fun adapt(c: Context, steps: Int, genMs: Long, audioMs: Long) {
        val r = SupertonicSpeed.rtf(genMs, audioMs)
        SupertonicTiming.speed(steps, threads(), r)
        val next = SupertonicSpeed.nextSteps(steps, genMs, audioMs)
        if (next != steps) SupertonicSpeed.setSteps(c, next)
    }

    /** Supertonic 3: कौन-सी आवाज़ (sid), हिंदी, कितने चरण. pure (JVM test) */
    fun genConfig(sid: Int, speed: Float, steps: Int = SupertonicSpeed.START_STEPS): GenerationConfig =
        GenerationConfig(sid = sid.coerceAtLeast(0), speed = speed, numSteps = steps, extra = mapOf("lang" to "hi"))

    /** ▶ सुनो: चुना हो या नहीं, नमूना बोले ([voice] = जो आवाज़ सुननी है). अपनी स्पीड/पिच - ऊपर वाली आम नहीं */
    override fun preview(c: Context, text: String, voice: String): Long {
        previewing = true
        return try { PreviewPlayer.play(c, neutral = true) { out -> speak(c, text, voice, out) } } finally { previewing = false }
    }

    override fun stop() { gen.incrementAndGet() }

    /** मेमोरी खाली (मॉडल हटाया / गड़बड़) */
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
     * गड़बड़: पसंद Supertonic ही रहती है; [VoiceEngines.FAIL_MS] (2 मिनट) तक Gemini Live की सीधी आवाज़
     * ([VoiceEngines.active] - [liveOnTrouble]), फिर अपने-आप दोबारा Supertonic. कभी Gemini TTS नहीं.
     */
    fun fail(c: Context, why: String) {
        val app = c.applicationContext
        DKLog.d("Supertonic3: अभी नहीं चला - $why (थोड़ी देर Gemini Live की सीधी आवाज़)")
        try { SupertonicStore.setError(app, "$why — Supertonic चुना ही है; तब तक DK Gemini Live की सीधी आवाज़ में बोला, थोड़ी देर में फिर Supertonic से।") } catch (_: Throwable) {}
        VoiceEngines.markFailed(ID, why)
        stop()
        VoiceEngines.changed()
        val main = Handler(Looper.getMainLooper())
        main.post {
            try { Toast.makeText(app, "Supertonic आवाज़ में अभी दिक़्क़त — इस बीच DK Gemini Live की सीधी आवाज़ में बोलेगा", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
        main.postDelayed({ VoiceEngines.changed() }, VoiceEngines.FAIL_MS + 1_000L)
        Thread({ release() }, "dk-supertonic3-release").apply { isDaemon = true; start() }
    }
}
