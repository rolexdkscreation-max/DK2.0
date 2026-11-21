package com.dk.assistant.stt

import android.content.Context
import android.os.Build
import com.dk.assistant.DKLog
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 21 नवंबर 2026 (मालिक: "Whisper हटाकर Sherpa-ONNX - फ़ोन पर, बिना इंटरनेट / key, बोलते-बोलते लिखे"):
 * k2-fsa Sherpa-ONNX का streaming (बोलते-बोलते) पहचान-इंजन, हिंदी + English (Hinglish) zipformer2 CTC मॉडल.
 *
 *  - मॉडल [ModelCatalog.STT_SHERPA] (दो फ़ाइलें, Wi-Fi पर डाउनलोड, `filesDir/models/`). न हो = [ready] false.
 *  - एक ही recognizer सबके लिए (अलग तरीका, माइक बटन, Live caption) - मॉडल एक बार उतरता है, एक बार खुलता है.
 *    जो इस्तेमाल करे वह [acquire] / [release]; कोई न बचे तो 60 s बाद मेमोरी खाली.
 *  - native लाइब्रेरी सिर्फ़ arm64 फ़ोन में - बाकी पर (जैसे x86 emulator) [supported] false = Android की पहचान.
 */
object SherpaStt {
    const val RATE = 16_000
    /** बात के बाद इतनी चुप्पी जोड़ते हैं ताकि आख़िरी शब्द भी निकल आए (मॉडल 320 ms के टुकड़ों में देखता है) */
    const val TAIL_MS = 800
    /** इतनी देर कोई इस्तेमाल न करे तो recognizer बंद (मेमोरी खाली) */
    const val IDLE_FREE_MS = 60_000L
    const val NAME = "Sherpa-ONNX"

    sealed class Res {
        data class Text(val text: String, val ms: Long) : Res()
        data class Slow(val ms: Long) : Res()
        data class Fail(val why: String) : Res()
    }

    private val lock = Any()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rec: OnlineRecognizer? = null
    private var users = 0
    private var idleSince = 0L
    @Volatile private var libBroken = false
    @Volatile var problem = ""; private set
    /** decode एक समय में एक ही (CPU पर दो भारी काम साथ न चलें) */
    internal val decodeLock = Any()

    fun assets(): List<ModelCatalog.Asset> = ModelCatalog.STT_SHERPA
    fun hasModel(c: Context): Boolean = assets().all { ModelManager.ready(c, it) }
    /** यह फ़ोन चला सकता है (arm64 + लाइब्रेरी पहले नाकाम नहीं हुई) */
    fun supported(): Boolean = !libBroken && abiOk(Build.SUPPORTED_ABIS.toList())
    fun ready(c: Context): Boolean = hasModel(c) && supported()

    /** क्यों नहीं चल रहा (Settings / स्क्रीन पर) - "" = सब ठीक */
    fun whyNot(c: Context): String = when {
        !supported() -> problem.ifBlank { "$NAME इस फ़ोन पर नहीं चल सकता (सिर्फ़ 64-bit ARM फ़ोन)" }
        !hasModel(c) -> "$NAME मॉडल अभी डाउनलोड नहीं हुआ (Settings → आवाज़ → बातचीत का तरीका)"
        else -> ""
    }

    /** recognizer लो (पहली बार ~1 s में खुलता है). null = नहीं चला - बुलाने वाला Android पर जाए */
    fun acquire(c: Context): OnlineRecognizer? = synchronized(lock) {
        if (!ready(c)) return null
        val r = rec ?: open(c) ?: return null
        rec = r; users++
        r
    }

    /** [acquire] का जोड़ा. कोई न बचे = 60 s बाद बंद (बार-बार खोलने की देर नहीं) */
    fun release() {
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return
            idleSince = System.currentTimeMillis()
        }
        idle.schedule({ freeIfIdle() }, IDLE_FREE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private val idle = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dk-stt-idle").apply { isDaemon = true } }

    private fun freeIfIdle() {
        val r = synchronized(lock) {
            if (users > 0 || rec == null || System.currentTimeMillis() - idleSince < IDLE_FREE_MS - 50) return
            rec.also { rec = null }
        }
        synchronized(decodeLock) { try { r?.release() } catch (e: Throwable) { DKLog.d("$NAME release: ${e.message}") } }
        DKLog.d("$NAME: मॉडल बंद (मेमोरी खाली)")
    }

    private fun open(c: Context): OnlineRecognizer? {
        val enc = ModelManager.file(c, ModelCatalog.STT_ENCODER).path
        val tok = ModelManager.file(c, ModelCatalog.STT_TOKENS).path
        val t0 = System.currentTimeMillis()
        return try {
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = enc),
                    tokens = tok, numThreads = threads(Runtime.getRuntime().availableProcessors()), debug = false, provider = "cpu",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, ENDPOINT_SILENCE_S, 0f),
                    rule3 = EndpointRule(false, 0f, 20f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            OnlineRecognizer(null, cfg).also { DKLog.d("$NAME: मॉडल खुला (${System.currentTimeMillis() - t0} ms)") }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError / खराब फ़ाइल - इस बार के लिए बंद, Android पर
            libBroken = libError(e)
            problem = if (libBroken) "$NAME इस फ़ोन पर नहीं चला" else "$NAME मॉडल नहीं खुला - Settings से हटाकर फिर डाउनलोड करें"
            DKLog.d("$NAME open: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            null
        }
    }

    /** बातचीत / Live शुरू होते ही पहले से खोल लो (पहली बात पर देर न हो). false = नहीं खुला */
    suspend fun warm(c: Context): Boolean = withContext(Dispatchers.Default) {
        val r = acquire(c)
        if (r != null) release()
        r != null
    }

    /** पूरी बात एक साथ (अलग तरीका / Ear): PCM16 (little-endian, 16 kHz mono) -> text */
    suspend fun transcribe(c: Context, pcm: ByteArray, timeoutMs: Long = 8_000L): Res = withContext(Dispatchers.Default) {
        if (pcm.size < RATE / 5) return@withContext Res.Fail("आवाज़ बहुत छोटी")
        val t0 = System.currentTimeMillis()
        val job = bg.async { decodeAll(c, pcm) }
        val text = withTimeoutOrNull(timeoutMs) { job.await() }
        val ms = System.currentTimeMillis() - t0
        when {
            text == null && job.isActive -> Res.Slow(ms)
            text == null -> Res.Fail(whyNot(c).ifBlank { problem.ifBlank { "$NAME नहीं चला" } })
            else -> Res.Text(text, ms).also { DKLog.d("$NAME: ${pcm.size / 32} ms आवाज़ -> $ms ms: \"${text.take(60)}\"") }
        }
    }

    private fun decodeAll(c: Context, pcm: ByteArray): String? {
        val s = Stream.open(c) ?: return null
        try {
            s.feed(toFloat(pcm, 0, pcm.size))
            s.feed(FloatArray(RATE * TAIL_MS / 1000))
            return s.text()
        } finally { s.close() }
    }

    /**
     * एक बोलती धारा (माइक बटन / Live caption / batch). [feed] = आवाज़ दो और तुरंत जितना पहचाना वह लो.
     * एक धारा एक ही धागे से चलाओ.
     */
    class Stream private constructor(private val rec: OnlineRecognizer, private val s: OnlineStream) {
        @Volatile private var closed = false

        /** आवाज़ (-1..1) दो, decode करो, अब तक का text */
        fun feed(samples: FloatArray): String {
            if (closed) return ""
            s.acceptWaveform(samples, RATE)
            synchronized(decodeLock) { while (rec.isReady(s)) rec.decode(s) }
            return text()
        }

        fun text(): String = if (closed) "" else clean(rec.getResult(s).text)
        /** बोलने वाला रुका (चुप्पी) = यह हिस्सा पक्का */
        fun endpoint(): Boolean = !closed && rec.isEndpoint(s)
        /** पक्का हिस्सा हो गया - अगला हिस्सा खाली से */
        fun reset() { if (!closed) rec.reset(s) }

        fun close() {
            if (closed) return
            closed = true
            try { s.release() } catch (e: Throwable) { DKLog.d("$NAME stream: ${e.message}") }
            SherpaStt.release()
        }

        companion object {
            fun open(c: Context): Stream? {
                val r = acquire(c) ?: return null
                return try { Stream(r, r.createStream("")) } catch (e: Throwable) {
                    DKLog.d("$NAME stream: ${e.message}"); SherpaStt.release(); null
                }
            }
        }
    }

    // ---------------------------------------------------------------- pure (JUnit)

    /** बोलने के बाद इतनी चुप्पी = एक हिस्सा पक्का (माइक बटन / caption में अगली पंक्ति) */
    const val ENDPOINT_SILENCE_S = 0.8f

    /** UnsatisfiedLinkError / ExceptionInInitializerError / NoClassDefFoundError (सब LinkageError) = .so ही नहीं चला */
    fun libError(e: Throwable): Boolean = e is LinkageError

    fun abiOk(abis: List<String>): Boolean = "arm64-v8a" in abis

    /** बड़े core (आम तौर पर 2) - सारे core लेने से Live की आवाज़ / UI पर असर */
    fun threads(cores: Int): Int = if (cores >= 6) 2 else 1

    /** PCM16 little-endian -> -1..1 */
    fun toFloat(pcm: ByteArray, off: Int, len: Int): FloatArray {
        val n = len / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val p = off + i * 2
            val v = (pcm[p].toInt() and 0xff) or (pcm[p + 1].toInt() shl 8)
            out[i] = v.toShort() / 32768f
        }
        return out
    }

    private val SPACES = Regex("\\s+")
    private val DEVA = Regex("[\\u0900-\\u097F]")
    /** देवनागरी शब्द के आख़िर में रोमन मात्रा ("खोलo", "कo") - मॉडल की जानी-पहचानी गड़बड़ */
    private val TAIL_VOWEL = Regex("([\\u0915-\\u0939\\u0958-\\u095F])([aeiou])$")
    private val MATRA = mapOf('a' to "ा", 'e' to "े", 'i' to "ी", 'o' to "ो", 'u' to "ू")

    /** खाली जगह ठीक, आधे-देवनागरी शब्द की रोमन मात्रा देवनागरी में */
    fun clean(raw: String): String {
        val t = raw.replace(SPACES, " ").trim()
        if (t.isEmpty()) return ""
        return t.split(' ').joinToString(" ") { w ->
            if (!DEVA.containsMatchIn(w)) w
            else TAIL_VOWEL.replace(w) { m -> m.groupValues[1] + MATRA.getValue(m.groupValues[2][0]) }
        }
    }
}
