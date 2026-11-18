package com.dk.assistant.stt

import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 18 नवंबर 2026: फ़ोन पर ऑफलाइन Whisper (whisper.cpp + ggml-small-q5_1, भाषा हिंदी पर तय).
 *
 *  - मॉडल [ModelCatalog.WHISPER_SMALL] (डाउनलोड वाले हिस्से से, `filesDir/models/`). न हो = [Res.Fail].
 *  - मॉडल एक बार खुलता है (~1-2 s) और बातचीत चलने तक खुला रहता है; बातचीत बंद = [release] (मेमोरी खाली).
 *  - हर काम पीछे के धागे पर (Dispatchers.Default) - स्क्रीन कभी नहीं अटकती.
 *  - [timeoutMs] से ज़्यादा = native को "रुको" (abort callback), [Res.Slow] - ऊपर वाला Android की पहचान पर जाता है.
 */
object WhisperStt {
    const val LANG = "hi"
    /** देवनागरी में लिखने का संकेत (Whisper कभी-कभी हिंदी को उर्दू/रोमन लिपि में लिख देता है) */
    const val PROMPT = "नमस्ते डीके। आज मौसम कैसा है? व्हाट्सऐप खोलो और मम्मी को कॉल करो।"
    const val RATE = 16_000

    sealed class Res {
        data class Text(val text: String, val ms: Long) : Res()
        data class Slow(val ms: Long) : Res()
        data class Fail(val why: String) : Res()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var ctx = 0L
    @Volatile private var ctxPath = ""

    fun asset(): ModelCatalog.Asset = ModelCatalog.WHISPER_SMALL
    fun hasModel(c: Context): Boolean = ModelManager.ready(c, asset())
    /** लाइब्रेरी चल सकती है (CPU ठीक + .so मौजूद) */
    fun supported(): Boolean = WhisperNative.load()
    fun ready(c: Context): Boolean = hasModel(c) && supported()

    /** क्यों नहीं चल रहा (Settings / स्क्रीन पर) - "" = सब ठीक */
    fun whyNot(c: Context): String = when {
        !supported() -> WhisperNative.problem.ifBlank { "Whisper इस फ़ोन पर नहीं चल सकता" }
        !hasModel(c) -> "Whisper मॉडल अभी डाउनलोड नहीं हुआ (Settings → आवाज़ → बातचीत का तरीका)"
        else -> ""
    }

    /** मॉडल पहले से खोल लो (बातचीत शुरू होते ही, पहली बात पर देर न हो). false = नहीं खुला */
    suspend fun warm(c: Context): Boolean = lock.withLock { open(c) != 0L }

    private suspend fun open(c: Context): Long {
        val f = ModelManager.file(c, asset())
        if (ctx != 0L && ctxPath == f.path) return ctx
        if (!ready(c)) return 0L
        val t0 = System.currentTimeMillis()
        val p = withContext(Dispatchers.Default) { try { WhisperNative.initContext(f.path) } catch (e: Throwable) { DKLog.d("Whisper init: ${e.message}"); 0L } }
        if (p != 0L) { ctx = p; ctxPath = f.path; DKLog.d("Whisper: मॉडल खुला (${System.currentTimeMillis() - t0} ms)") }
        else DKLog.d("Whisper: मॉडल नहीं खुला - फ़ाइल खराब? (Settings से हटाकर फिर डाउनलोड करें)")
        return p
    }

    /** PCM16 (little-endian, 16 kHz mono) -> text */
    suspend fun transcribe(c: Context, pcm: ByteArray, timeoutMs: Long, fullAccuracy: Boolean): Res = lock.withLock {
        if (pcm.size < RATE / 5) return@withLock Res.Fail("आवाज़ बहुत छोटी")
        val p = open(c)
        if (p == 0L) return@withLock Res.Fail(whyNot(c).ifBlank { "Whisper मॉडल नहीं खुला" })
        val samples = toFloat(pcm)
        val audioMs = pcm.size * 1000L / (RATE * 2)
        val actx = if (fullAccuracy) 0 else audioCtx(audioMs)
        val threads = threads(Runtime.getRuntime().availableProcessors())
        val t0 = System.currentTimeMillis()
        val job = scope.async {
            try { WhisperNative.transcribe(p, samples, LANG, threads, PROMPT, actx) } catch (e: Throwable) { DKLog.d("Whisper: ${e.message}"); null }
        }
        val bytes = withTimeoutOrNull(timeoutMs) { job.await() }
        val ms = System.currentTimeMillis() - t0
        if (bytes == null && job.isActive) {
            // समय पूरा: native को रोको, उसके लौटने तक रुको (वही context अगली बार चाहिए)
            withContext(NonCancellable) {
                try { WhisperNative.setAbort(true) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
                job.await()
                try { WhisperNative.setAbort(false) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) }
            }
            DKLog.d("Whisper: ${audioMs} ms की आवाज़ पर $ms ms - बहुत धीमा, रोका")
            return@withLock Res.Slow(ms)
        }
        if (bytes == null) return@withLock Res.Fail("Whisper ने कुछ नहीं लौटाया")
        val text = clean(String(bytes, Charsets.UTF_8))
        DKLog.d("Whisper: ${audioMs} ms आवाज़ -> $ms ms (ctx=${if (actx == 0) 1500 else actx}, $threads धागे): \"${text.take(60)}\"")
        Res.Text(text, ms)
    }

    /** बातचीत खत्म - मॉडल की मेमोरी (~300 MB) खाली */
    suspend fun release() = lock.withLock {
        val p = ctx
        ctx = 0L; ctxPath = ""
        if (p != 0L) withContext(Dispatchers.Default) { try { WhisperNative.freeContext(p) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e) } }
    }

    // ---------------------------------------------------------------- pure (JUnit)

    fun toFloat(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val v = (pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)
            out[i] = v.toShort() / 32768f
        }
        return out
    }

    /**
     * encoder की खिड़की: Whisper हमेशा 30 s (1500) देखता है - छोटी बात पर बेकार देर. 50 फ्रेम = 1 s.
     * बात + 2 s गुंजाइश, 64 के गुणक में, कम से कम 512 (इससे कम पर लिखावट बिगड़ती है), ज़्यादा से ज़्यादा 1500.
     */
    fun audioCtx(audioMs: Long): Int {
        val frames = ((audioMs + 2_000L) * 50 / 1000).toInt()
        val up = (frames + 63) / 64 * 64
        return up.coerceIn(512, 1500)
    }

    /** बड़े core (आम तौर पर 4) - सारे core लेने से फ़ोन गरम और UI धीमी */
    fun threads(cores: Int): Int = when { cores >= 8 -> 4; cores >= 4 -> cores - 1; else -> cores.coerceAtLeast(1) }

    private val BRACKETS = Regex("[\\[(（][^\\])）]{0,40}[\\])）]")
    private val SPACES = Regex("\\s+")
    /**
     * शोर पर Whisper के जाने-माने भ्रम (पूरा text सिर्फ़ यही हो तो खाली). "धन्यवाद" / "thank you" जानबूझकर नहीं -
     * मालिक सच में ऐसा बोल सकता है (VAD पहले ही पक्का कर चुका कि इंसानी आवाज़ थी).
     */
    private val HALLUCINATIONS = setOf(
        "सब्सक्राइब करें", "चैनल को सब्सक्राइब करें", "वीडियो देखने के लिए धन्यवाद", "thanks for watching", "thanks for watching.",
        "you", ".", "।", "...", "म्यूजिक", "संगीत",
    )

    /** [संगीत], (हँसी) जैसे निशान हटाओ, खाली जगह ठीक करो, भ्रम वाला text = "" */
    fun clean(raw: String): String {
        val t = raw.replace(BRACKETS, " ").replace("*", " ").replace(SPACES, " ").trim()
        if (t.isBlank()) return ""
        val low = t.lowercase().trim().trimEnd('!', '?')
        if (low in HALLUCINATIONS) return ""
        if (t.length * 2 >= PROMPT.length && PROMPT.contains(t)) return ""      // संकेत ही दोहरा दिया (चुप्पी पर)
        return t
    }
}
