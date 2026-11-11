package com.dk.assistant.voice.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dk.assistant.DKLog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
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
 * - गड़बड़ (मॉडल न खुले / बार-बार न बोले): Piper अपने-आप OFF ([fail]), साफ़ हिंदी संदेश, DK Gemini Live की आवाज़ पर.
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

    /** स्विच ON + डाउनलोड पूरा = Piper; नहीं तो null (= पहले वाला इंजन, कुछ नहीं बदला) */
    fun override(c: Context): VoiceEngine? =
        try { if (gate(PiperStore.on(c), PiperStore.installed(c))) this else null } catch (_: Throwable) { null }

    /** वाक्यों के बीच ठहराव - सिर्फ़ Piper के टुकड़ों पर Settings का "ठहराव" (बाकी इंजन जस के तस) */
    fun gap(c: Context, engineId: String, base: Int): Int =
        if (engineId == ID) try { PiperFx.gap(base, PiperStore.pause(c)) } catch (_: Throwable) { base } else base

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

    private fun ensure(c: Context): OfflineTts = synchronized(lock) {
        val v = PiperStore.voice(c).let { if (PiperStore.voiceOk(c, it)) it else PiperStore.installedVoices(c).firstOrNull()?.id ?: it }
        tts?.let { if (loaded == v) return it; try { it.release() } catch (_: Throwable) {}; tts = null }
        val t0 = System.currentTimeMillis()
        try {
            loadLib(c)
            val vo = PiperStore.voiceOf(v)
            val dir = PiperStore.voiceDir(c, v)
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = java.io.File(dir, vo.onnxName).absolutePath,
                        tokens = java.io.File(dir, "tokens.txt").absolutePath,
                        dataDir = PiperStore.espeakDir(c).absolutePath,
                    ),
                    numThreads = threads(), debug = false, provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            val t = OfflineTts(null, cfg)
            rate = t.sampleRate().takeIf { it > 0 } ?: 22050
            tts = t; loaded = v
            lastLoadMs = System.currentTimeMillis() - t0
            DKLog.d("Piper: मॉडल मेमोरी में ($v, ${rate} Hz, ${threads()} धागे) $lastLoadMs ms")
            t
        } catch (e: Throwable) {
            fail(c, "Piper का मॉडल नहीं खुला (${e.javaClass.simpleName})")
            throw IOException("Piper मॉडल लोड नहीं हुआ: ${e.message}", e)
        }
    }

    private fun threads(): Int = if (Runtime.getRuntime().availableProcessors() >= 4) 2 else 1

    override fun speak(c: Context, text: String, voice: String, out: PcmOut) {
        if (!PiperStore.on(c) && !previewing) throw IOException("Piper बंद है")
        val g = gen.get()
        val t = ensure(c)
        val say = speakable(text)
        if (say.isBlank()) { out.write(ByteArray(480)); return }      // बोलने लायक कुछ नहीं (जैसे सिर्फ़ emoji) - चुप्पी, ग़लती नहीं
        val tune = PiperStore.tune(c)
        val fx = PiperFx(rate, tune.bassDb, tune.reverb)
        val src = PiperFx.srcRate(rate, tune.pitch)
        try {
            synchronized(lock) {                   // एक मॉडल - एक समय में एक वाक्य (दूसरा धागा कतार में)
                if (gen.get() != g) return
                // अलग object (lambda नहीं): native कोड ठीक invoke([F)Ljava/lang/Integer; ढूँढता है - Kotlin 2 के indy-lambda में वह नहीं बनता
                t.generateWithCallback(say, 0, PiperFx.genSpeed(tune.speed, tune.pitch), object : (FloatArray) -> Int {
                    override fun invoke(samples: FloatArray): Int {
                        if (gen.get() != g) return 0                        // रोका गया - बाकी मत बनाओ
                        out.write(Resample.to24k(PiperFx.pcm16(fx.process(samples)), src))
                        return 1
                    }
                })
            }
            if (gen.get() == g) fx.tail().takeIf { it.isNotEmpty() }?.let { out.write(Resample.to24k(PiperFx.pcm16(it), src)) }
            fails.set(0)
        } catch (e: Throwable) {
            if (gen.get() != g) return
            if (fails.incrementAndGet() >= 2) fail(c, "Piper आवाज़ नहीं बना पाया (${e.javaClass.simpleName})")
            throw IOException("Piper: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

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
     * Piper अपने-आप OFF: स्विच बंद, वजह सेव (Settings में दिखे), चलता Live चुप होते ही अपनी सीधी आवाज़ पर
     * ([VoiceEngines.changed]), और यूज़र को छोटा संदेश.
     */
    fun fail(c: Context, why: String) {
        val app = c.applicationContext
        DKLog.d("Piper: अपने-आप बंद - $why")
        try {
            PiperStore.setOn(app, false)
            PiperStore.setError(app, "$why — Piper अपने-आप बंद किया, DK पहले की तरह Gemini Live की आवाज़ में बोलेगा।")
        } catch (_: Throwable) {}
        stop()
        VoiceEngines.changed()
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(app, "Piper आवाज़ में दिक़्क़त — DK अब पहले की तरह Gemini Live की आवाज़ में बोलेगा", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
        }
        Thread({ release() }, "dk-piper-release").apply { isDaemon = true; start() }
    }
}
