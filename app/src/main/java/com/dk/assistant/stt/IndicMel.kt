package com.dk.assistant.stt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 21 नवंबर 2026 (मालिक: "Sherpa-ONNX हटाओ, हिंदी + English एकदम सही लिखे, ऐप का MB घटे"):
 * IndicConformer (NeMo) मॉडल की आवाज़ -> 80 mel की तस्वीर, बिना किसी native लाइब्रेरी के (pure Kotlin, JUnit से जाँचा).
 * NeMo AudioToMelSpectrogramPreprocessor जैसा ही: pre-emphasis 0.97, 512 FFT, 400 का hann (periodic=false),
 * 160 का hop, center (दोनों ओर 256 शून्य), power, slaney mel, log(x + 2^-24), हर mel की अलग normalize (per_feature).
 * Python (numpy) वाली नकल से sherpa-onnx जितना ही नतीजा मिला था (FLEURS हिंदी पर WER एक जैसा).
 */
object IndicMel {
    const val RATE = 16_000
    const val NFFT = 512
    const val WIN = 400
    const val HOP = 160
    const val MELS = 80
    private const val PREEMPH = 0.97
    private const val GUARD = 5.9604644775390625E-8      // 2^-24
    private const val BINS = NFFT / 2 + 1

    /** [MELS] x [BINS] (slaney, librosa htk=false जैसा) */
    val bank: Array<DoubleArray> by lazy { melBank() }
    private val window: DoubleArray by lazy {
        val w = DoubleArray(NFFT); val off = (NFFT - WIN) / 2
        for (i in 0 until WIN) w[off + i] = 0.5 - 0.5 * cos(2.0 * PI * i / (WIN - 1))
        w
    }

    /** कितने frame बनेंगे (NeMo: len / hop + 1) */
    fun frames(samples: Int): Int = samples / HOP + 1

    /** आवाज़ (-1..1) -> [MELS * T] (mel-major: index = m * T + t, मॉडल का [1, 80, T]) और T */
    fun features(a: FloatArray): Pair<FloatArray, Int> {
        val n = a.size
        val t = frames(n)
        // pre-emphasis + center padding (शून्य)
        val pad = NFFT / 2
        val x = DoubleArray(n + 2 * pad)
        for (i in 0 until n) x[pad + i] = if (i == 0) a[0].toDouble() else a[i] - PREEMPH * a[i - 1]
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT); val pw = DoubleArray(BINS)
        val out = DoubleArray(MELS * t)
        val b = bank; val w = window
        for (f in 0 until t) {
            val s = f * HOP
            for (k in 0 until NFFT) { re[k] = x[s + k] * w[k]; im[k] = 0.0 }
            fft(re, im)
            for (k in 0 until BINS) pw[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until MELS) {
                val row = b[m]; var acc = 0.0
                for (k in 0 until BINS) { val c = row[k]; if (c != 0.0) acc += c * pw[k] }
                out[m * t + f] = ln(acc + GUARD)
            }
        }
        // per_feature: हर mel की औसत 0, फैलाव 1 (std unbiased + 1e-5, NeMo जैसा)
        val res = FloatArray(MELS * t)
        for (m in 0 until MELS) {
            var mu = 0.0
            for (f in 0 until t) mu += out[m * t + f]
            mu /= t
            var v = 0.0
            for (f in 0 until t) { val d = out[m * t + f] - mu; v += d * d }
            val sd = (if (t > 1) sqrt(v / (t - 1)) else 0.0) + 1e-5
            for (f in 0 until t) res[m * t + f] = ((out[m * t + f] - mu) / sd).toFloat()
        }
        return res to t
    }

    private fun hzToMel(f: Double): Double = if (f >= 1000.0) 15.0 + ln(f / 1000.0) / (ln(6.4) / 27.0) else f / (200.0 / 3.0)
    private fun melToHz(m: Double): Double = if (m >= 15.0) 1000.0 * exp((m - 15.0) * (ln(6.4) / 27.0)) else m * (200.0 / 3.0)

    private fun melBank(): Array<DoubleArray> {
        val fft = DoubleArray(BINS) { it * (RATE / 2.0) / (BINS - 1) }
        val lo = hzToMel(0.0); val hi = hzToMel(RATE / 2.0)
        val pts = DoubleArray(MELS + 2) { melToHz(lo + (hi - lo) * it / (MELS + 1)) }
        return Array(MELS) { i ->
            val d0 = pts[i + 1] - pts[i]; val d1 = pts[i + 2] - pts[i + 1]
            val norm = 2.0 / (pts[i + 2] - pts[i])
            DoubleArray(BINS) { k ->
                val down = (fft[k] - pts[i]) / d0
                val up = (pts[i + 2] - fft[k]) / d1
                max(0.0, min(down, up)) * norm
            }
        }
    }

    /** in-place radix-2 FFT (n = 2 की घात) */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** pure (JUnit): CTC greedy - हर frame का सबसे ऊँचा token, दोहराव + blank हटाओ, "▁" = खाली जगह */
object CtcText {
    fun decode(logits: FloatArray, frames: Int, vocab: Int, tokens: List<String>, blank: Int = vocab - 1): String {
        val sb = StringBuilder()
        var prev = -1
        for (f in 0 until frames) {
            val o = f * vocab
            var best = 0; var bv = logits[o]
            for (v in 1 until vocab) { val x = logits[o + v]; if (x > bv) { bv = x; best = v } }
            if (best != prev && best != blank) {
                val t = tokens.getOrNull(best)
                if (t != null && t != "<unk>" && !(t.startsWith("<") && t.endsWith(">"))) sb.append(t)
            }
            prev = best
        }
        return sb.toString().replace('\u2581', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /** tokens.txt ("टुकड़ा id" हर पंक्ति) -> id क्रम की सूची */
    fun parseTokens(lines: List<String>): List<String> {
        val m = HashMap<Int, String>()
        for (l in lines) {
            if (l.isBlank()) continue
            val sp = l.lastIndexOf(' ')
            if (sp <= 0) continue
            val id = l.substring(sp + 1).trim().toIntOrNull() ?: continue
            m[id] = l.substring(0, sp)
        }
        val n = (m.keys.maxOrNull() ?: -1) + 1
        return List(n) { m[it] ?: "" }
    }
}

/**
 * pure (JUnit): बोलती धारा को हिस्सों में बाँटना (मॉडल streaming नहीं, इसलिए हम खुद तय करते हैं कब पढ़ें).
 *  - बोली शुरू = कम से कम [MIN_SPEECH_MS] आवाज़ वाले टुकड़े
 *  - बोली के बाद [END_SILENCE_MS] चुप्पी, या हिस्सा [MAX_SEG_MS] से लंबा = हिस्सा पक्का (endpoint)
 *  - बोलते-बोलते बीच का text: नई आवाज़ [PARTIAL_MS] (या पिछली पढ़ाई के समय का दोगुना) होते ही दोबारा पढ़ो
 */
class SpeechSegmenter {
    companion object {
        const val MIN_SPEECH_MS = 120
        const val END_SILENCE_MS = 800
        const val MAX_SEG_MS = 15_000
        const val PARTIAL_MS = 700
        /** बोली नहीं आई तो इतनी पुरानी आवाज़ ही रखो (बोली की शुरुआत न कटे) */
        const val PREROLL_MS = 300
        const val IDLE_TRIM_MS = 1_500
    }

    var segMs = 0; private set
    var speechMs = 0; private set
    var silenceMs = 0; private set
    private var sinceDecodeMs = 0
    @Volatile var lastDecodeTookMs = 0L

    val hasSpeech: Boolean get() = speechMs >= MIN_SPEECH_MS
    val endpoint: Boolean get() = hasSpeech && (silenceMs >= END_SILENCE_MS || segMs >= MAX_SEG_MS)

    fun push(ms: Int, voiced: Boolean) {
        segMs += ms; sinceDecodeMs += ms
        if (voiced) { speechMs += ms; silenceMs = 0 } else if (speechMs > 0) silenceMs += ms
    }

    /** true = पुरानी चुप्पी काटो, सिर्फ़ आख़िरी [PREROLL_MS] रखो */
    fun trimIdle(): Boolean {
        if (hasSpeech || segMs < IDLE_TRIM_MS) return false
        segMs = PREROLL_MS; speechMs = 0; silenceMs = 0; sinceDecodeMs = 0
        return true
    }

    fun wantPartial(): Boolean = hasSpeech && !endpoint && sinceDecodeMs >= max(PARTIAL_MS.toLong(), lastDecodeTookMs * 2)
    fun decoded(tookMs: Long) { sinceDecodeMs = 0; lastDecodeTookMs = tookMs }
    fun reset() { segMs = 0; speechMs = 0; silenceMs = 0; sinceDecodeMs = 0 }
}
