package com.dk.assistant.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * v3.66: आवाज़-पहचान मॉडल के लिए 80 mel-fbank (Kaldi जैसा, sherpa-onnx की default सेटिंग से मिलाया):
 * 16 kHz, 25 ms खिड़की, 10 ms कदम, povey window, preemph 0.97, DC हटाओ, dither नहीं, snip_edges = false,
 * mel 20..7600 Hz, power spectrum, log. फिर हर कॉलम से औसत घटाओ (global-mean)।
 * शुद्ध Kotlin - Android के बिना JVM टेस्ट में चलता है।
 */
object SpeakerFbank {
    const val RATE = 16000
    const val BINS = 80
    private const val LEN = 400
    private const val SHIFT = 160
    private const val NFFT = 512
    private const val LOW = 20.0
    private const val HIGH = 7600.0

    private val window = DoubleArray(LEN) { (0.5 - 0.5 * cos(2 * PI * it / (LEN - 1))).pow(0.85) }

    /** mel फ़िल्टर: हर bin के लिए (पहला fft-bin, वज़न) */
    private val melStart = IntArray(BINS)
    private val melW: Array<DoubleArray>

    private fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

    init {
        val half = NFFT / 2
        val fftBinW = RATE.toDouble() / NFFT
        val lo = mel(LOW); val hi = mel(HIGH); val d = (hi - lo) / (BINS + 1)
        melW = Array(BINS) { b ->
            val l = lo + b * d; val c = lo + (b + 1) * d; val r = lo + (b + 2) * d
            var first = -1; var last = -1
            val w = DoubleArray(half)
            for (k in 0 until half) {
                val m = mel(fftBinW * k)
                if (m > l && m < r) {
                    w[k] = if (m <= c) (m - l) / (c - l) else (r - m) / (r - c)
                    if (first < 0) first = k
                    last = k
                }
            }
            if (first < 0) { first = 0; last = -1 }
            melStart[b] = first
            DoubleArray(last - first + 1) { w[first + it] }
        }
    }

    fun numFrames(n: Int): Int = (n + SHIFT / 2) / SHIFT

    /** @param x नमूने -1..1 (normalize_samples = 1) | @return [frames*80], global-mean घटाकर */
    fun compute(x: FloatArray, n: Int = x.size): FloatArray {
        val nf = numFrames(n)
        val out = FloatArray(nf * BINS)
        if (nf == 0) return out
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT); val fr = DoubleArray(LEN)
        for (f in 0 until nf) {
            val start = f * SHIFT + SHIFT / 2 - LEN / 2
            var mean = 0.0
            for (i in 0 until LEN) {
                var s = start + i
                if (s < 0) s = -s - 1
                if (s >= n) s = 2 * n - 1 - s
                s = s.coerceIn(0, n - 1)
                fr[i] = x[s].toDouble(); mean += fr[i]
            }
            mean /= LEN
            for (i in 0 until LEN) fr[i] -= mean
            for (i in LEN - 1 downTo 1) fr[i] -= 0.97 * fr[i - 1]
            fr[0] -= 0.97 * fr[0]
            for (i in 0 until NFFT) { re[i] = if (i < LEN) fr[i] * window[i] else 0.0; im[i] = 0.0 }
            fft(re, im)
            for (b in 0 until BINS) {
                val w = melW[b]; val s0 = melStart[b]
                var e = 0.0
                for (j in w.indices) { val k = s0 + j; e += w[j] * (re[k] * re[k] + im[k] * im[k]) }
                out[f * BINS + b] = ln(max(e, 1.1920929e-7)).toFloat()
            }
        }
        for (b in 0 until BINS) {
            var m = 0.0
            for (f in 0 until nf) m += out[f * BINS + b]
            val mf = (m / nf).toFloat()
            for (f in 0 until nf) out[f * BINS + b] -= mf
        }
        return out
    }

    /** in-place radix-2 FFT (NFFT = 512) */
    private fun fft(re: DoubleArray, im: DoubleArray) {
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
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** PCM16 little-endian bytes -> -1..1 */
    fun pcm16(b: ByteArray, len: Int = b.size): FloatArray {
        val n = len / 2
        return FloatArray(n) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() / 32768f }
    }
}
