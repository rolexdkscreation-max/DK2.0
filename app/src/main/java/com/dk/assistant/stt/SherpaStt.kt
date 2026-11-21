package com.dk.assistant.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import com.dk.assistant.DKLog
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.voice.SileroVad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors

/**
 * 21 नवंबर 2026 (मालिक: "Sherpa-ONNX वाला सही काम नहीं कर रहा, उसकी जगह दूसरा open-source मॉडल - हिंदी + English
 * एकदम सही समझे, जो बोला वही लिखे; ऐप का MB घटे, मॉडल इंटरनेट से उतरे"):
 * AI4Bharat का IndicConformer हिंदी (CTC, int8) - फ़ोन पर, बिना इंटरनेट / key.
 *
 *  - पुराना k2-fsa AAR (9.5 MB, अपना अलग onnxruntime) हटा. अब ऐप में पहले से मौजूद Microsoft onnxruntime-android ही चलाता है;
 *    mel की तस्वीर [IndicMel] (pure Kotlin), शब्द [CtcText].
 *  - मॉडल [ModelCatalog.STT_SHERPA] (Wi-Fi पर डाउनलोड, `filesDir/models/`). न हो = [ready] false -> Android की पहचान.
 *  - जाँच (FLEURS हिंदी 40 वाक्य): पुराना betterflow 28.8% शब्द-ग़लती, यह 4.4%. Hinglish आदेश ("WhatsApp पर Rahul को
 *    message भेजो") लगभग पूरे सही - English शब्द देवनागरी में (व्हाट्सएप, मैसेज) लिखता है. चुप्पी पर कुछ नहीं लिखता.
 *  - मॉडल streaming नहीं: [Stream] आवाज़ जमा करता है, VAD (Silero, न चले तो RMS) से हिस्सा तय, बोलते-बोलते हर ~0.7 s
 *    पीछे के धागे पर दोबारा पढ़ता है (माइक कभी नहीं रुकता), चुप्पी पर आख़िरी पक्का text.
 *  - एक ही session सबके लिए (अलग तरीका, माइक बटन, Live caption). कोई न बचे तो 60 s बाद मेमोरी खाली.
 *  - नाम `SherpaStt` / pref "sherpa" पुराने ही रखे (बुलाने वाली जगहें / सहेजी सेटिंग न बदलें).
 */
object SherpaStt {
    const val RATE = 16_000
    /** बात के बाद इतनी चुप्पी जोड़ते हैं ताकि हिस्सा पक्का हो जाए */
    const val TAIL_MS = 800
    /** इतनी देर कोई इस्तेमाल न करे तो session बंद (मेमोरी खाली) */
    const val IDLE_FREE_MS = 60_000L
    const val NAME = "IndicConformer"
    /** एक बार में ज़्यादा से ज़्यादा इतनी आवाज़ मॉडल को (लंबी हो तो टुकड़ों में) */
    const val MAX_DECODE_S = 20

    sealed class Res {
        data class Text(val text: String, val ms: Long) : Res()
        data class Slow(val ms: Long) : Res()
        data class Fail(val why: String) : Res()
    }

    /** खुला मॉडल: session + शब्द-टुकड़े */
    class Model internal constructor(internal val session: OrtSession, internal val tokens: List<String>)

    private val lock = Any()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rec: Model? = null
    private var users = 0
    private var idleSince = 0L
    @Volatile private var libBroken = false
    @Volatile var problem = ""; private set
    /** decode एक समय में एक ही (CPU पर दो भारी काम साथ न चलें) */
    internal val decodeLock = Any()
    /** बोलते-बोलते वाला text पीछे के इस धागे पर (माइक / caption का धागा न रुके) */
    internal val partials = Executors.newSingleThreadExecutor { r -> Thread(r, "dk-stt-partial").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

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

    /** मॉडल लो (पहली बार कुछ सेकंड में खुलता है). null = नहीं चला - बुलाने वाला Android पर जाए */
    fun acquire(c: Context): Model? = synchronized(lock) {
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

    private val idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dk-stt-idle").apply { isDaemon = true } }

    private fun freeIfIdle() {
        val r = synchronized(lock) {
            if (users > 0 || rec == null || System.currentTimeMillis() - idleSince < IDLE_FREE_MS - 50) return
            rec.also { rec = null }
        }
        synchronized(decodeLock) { try { r?.session?.close() } catch (e: Throwable) { DKLog.d("$NAME release: ${e.message}") } }
        DKLog.d("$NAME: मॉडल बंद (मेमोरी खाली)")
    }

    private fun open(c: Context): Model? {
        val enc = ModelManager.file(c, ModelCatalog.STT_ENCODER).path
        val tok = ModelManager.file(c, ModelCatalog.STT_TOKENS)
        val t0 = System.currentTimeMillis()
        return try {
            val tokens = CtcText.parseTokens(tok.readLines(Charsets.UTF_8))
            val o = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads(Runtime.getRuntime().availableProcessors()))
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            Model(OrtEnvironment.getEnvironment().createSession(enc, o), tokens)
                .also { DKLog.d("$NAME: मॉडल खुला (${System.currentTimeMillis() - t0} ms, ${tokens.size} टुकड़े)") }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError / OOM / खराब फ़ाइल - इस बार के लिए बंद, Android पर
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
        val m = acquire(c) ?: return null
        try {
            val a = toFloat(pcm, 0, pcm.size)
            val step = RATE * MAX_DECODE_S
            val parts = ArrayList<String>()
            var i = 0
            while (i < a.size) {
                val end = minOf(a.size, i + step)
                if (end - i >= RATE / 10) parts += decode(m, a.copyOfRange(i, end))
                i = end
            }
            return clean(parts.joinToString(" "))
        } finally { release() }
    }

    /** एक हिस्सा (-1..1) -> text. मॉडल [1, 80, T] mel लेता है, [1, T/4, 5633] log-prob देता है */
    internal fun decode(m: Model, a: FloatArray): String {
        if (a.size < IndicMel.HOP * 8) return ""
        val (feat, t) = IndicMel.features(a)
        val env = OrtEnvironment.getEnvironment()
        val (arr, frames, vocab) = synchronized(decodeLock) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), longArrayOf(1, IndicMel.MELS.toLong(), t.toLong())).use { x ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t.toLong())), longArrayOf(1)).use { len ->
                    m.session.run(mapOf("audio_signal" to x, "length" to len)).use { out ->
                        val lp = out.get(0) as OnnxTensor
                        val shape = lp.info.shape
                        val buf = lp.floatBuffer
                        val a = FloatArray(buf.remaining()); buf.get(a)
                        Triple(a, shape[1].toInt(), shape[2].toInt())
                    }
                }
            }
        }
        return CtcText.decode(arr, frames, vocab, m.tokens)
    }

    /**
     * एक बोलती धारा (माइक बटन / Live caption). [feed] = आवाज़ दो और अब तक का text लो (तुरंत लौटता है;
     * बीच का text पीछे बनता है, हिस्सा पक्का होते ही [feed] खुद आख़िरी बार पढ़ता है).
     * एक धारा एक ही धागे से चलाओ.
     */
    class Stream private constructor(private val m: Model, private val vad: SileroVad?) {
        @Volatile private var closed = false
        private val seg = SpeechSegmenter()
        private var buf = FloatArray(RATE * 4)
        private var n = 0
        @Volatile private var cur = ""
        /** [reset] के बाद पुराना (पीछे बन रहा) text न आए */
        @Volatile private var gen = 0
        @Volatile private var busy = false
        private var finalDone = false

        /** आवाज़ (-1..1) दो, अब तक का text */
        fun feed(samples: FloatArray): String {
            if (closed || samples.isEmpty()) return text()
            append(samples)
            val v = voiced(samples)
            if (v) finalDone = false
            seg.push(samples.size * 1000 / RATE, v)
            if (seg.trimIdle()) keepLast(RATE * SpeechSegmenter.PREROLL_MS / 1000)
            if (seg.endpoint) {
                if (!finalDone) finalDecode()
            } else if (seg.wantPartial() && !busy) partial()
            return text()
        }

        fun text(): String = if (closed) "" else cur
        /** बोलने वाला रुका (चुप्पी) = यह हिस्सा पक्का */
        fun endpoint(): Boolean {
            if (closed || !seg.endpoint) return false
            if (!finalDone) finalDecode()
            return true
        }
        /** पक्का हिस्सा हो गया - अगला हिस्सा खाली से */
        fun reset() {
            if (closed) return
            gen++; n = 0; cur = ""; finalDone = false; seg.reset()
        }

        fun close() {
            if (closed) return
            closed = true; gen++
            try { vad?.close() } catch (e: Throwable) { DKLog.d("$NAME vad: ${e.message}") }
            SherpaStt.release()
        }

        private fun finalDecode() {
            val t0 = System.currentTimeMillis()
            val g = ++gen
            val txt = clean(decode(m, buf.copyOf(n)))
            if (g == gen) cur = txt
            seg.decoded(System.currentTimeMillis() - t0); finalDone = true
        }

        private fun partial() {
            val snap = buf.copyOf(n); val g = gen
            busy = true
            seg.decoded(seg.lastDecodeTookMs)
            try {
                partials.execute {
                    try {
                        val t0 = System.currentTimeMillis()
                        val txt = if (closed || g != gen) null else clean(decode(m, snap))
                        if (txt != null && g == gen && !closed) { cur = txt; seg.lastDecodeTookMs = System.currentTimeMillis() - t0 }
                    } catch (e: Throwable) { DKLog.d("$NAME partial: ${e.javaClass.simpleName} ${e.message?.take(60)}") }
                    finally { busy = false }
                }
            } catch (e: Throwable) { busy = false }
        }

        private fun voiced(f: FloatArray): Boolean {
            val v = vad
            if (v != null) {
                val s = ShortArray(f.size) { (f[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                val p = v.feed(s, s.size)
                if (p >= 0f) return p >= VAD_ON
            }
            return rmsVoiced(f)
        }

        private fun append(f: FloatArray) {
            if (n + f.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + f.size))
            System.arraycopy(f, 0, buf, n, f.size); n += f.size
        }

        private fun keepLast(k: Int) {
            if (n <= k) return
            System.arraycopy(buf, n - k, buf, 0, k); n = k
        }

        companion object {
            fun open(c: Context): Stream? {
                val r = acquire(c) ?: return null
                return try { Stream(r, SileroVad.open(c)) } catch (e: Throwable) {
                    DKLog.d("$NAME stream: ${e.message}"); SherpaStt.release(); null
                }
            }
        }
    }

    // ---------------------------------------------------------------- pure (JUnit)

    /** बोलने के बाद इतनी चुप्पी = एक हिस्सा पक्का (माइक बटन / caption में अगली पंक्ति) */
    const val ENDPOINT_SILENCE_S = SpeechSegmenter.END_SILENCE_MS / 1000f
    /** Silero की संभावना इससे ऊपर = बोली */
    const val VAD_ON = 0.4f
    /** Silero न चले तो: इतनी तेज़ आवाज़ (RMS, -1..1) = बोली */
    const val RMS_ON = 0.012f

    fun rmsVoiced(f: FloatArray): Boolean {
        if (f.isEmpty()) return false
        var s = 0.0
        for (x in f) s += x * x
        return kotlin.math.sqrt(s / f.size) >= RMS_ON
    }

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
