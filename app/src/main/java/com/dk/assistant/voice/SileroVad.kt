package com.dk.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.dk.assistant.DKLog
import com.dk.assistant.VadFramer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * नवंबर 2026 (Live जाँच #3): फ़ोन पर चलने वाला Silero VAD v5 (MIT, assets/vad/silero_vad.onnx, ~2.3 MB).
 * हर 32ms की खिड़की पर "इंसानी आवाज़ है?" की संभावना 0..1 - पंखा / TV की धुन / खटखट को बात नहीं मानता.
 * बिना इंटरनेट, एक खिड़की ~1ms. model न खुले = [feed] -1 लौटाता है (DK पुराने RMS पर चलता रहे).
 *
 * एक instance = एक माइक loop (state अंदर). thread-safe नहीं - सिर्फ़ माइक वाले thread से.
 */
class SileroVad private constructor(private val session: OrtSession) {
    companion object {
        const val ASSET = "vad/silero_vad.onnx"
        private const val RATE = 16_000L
        @Volatile private var bytes: ByteArray? = null
        @Volatile private var failed = false

        /** null = model नहीं खुला (log में वजह) */
        fun open(c: Context): SileroVad? {
            if (failed) return null
            return try {
                val b = bytes ?: c.assets.open(ASSET).use { it.readBytes() }.also { bytes = it }
                val o = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1); setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                SileroVad(OrtEnvironment.getEnvironment().createSession(b, o))
            } catch (e: Throwable) {
                failed = true
                DKLog.d("स्मार्ट VAD (Silero) नहीं खुला - पुराना RMS तरीका: ${e.message}")
                null
            }
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val framer = VadFramer()
    private var state = FloatArray(2 * 1 * 128)
    @Volatile var last = -1f; private set
    private var errors = 0

    fun reset() { framer.reset(); state.fill(0f); last = -1f }

    /** 40ms का टुकड़ा. लौटाता: इस टुकड़े की सबसे ऊँची संभावना (कोई पूरी खिड़की नहीं बनी = पिछली), -1 = VAD बंद */
    fun feed(pcm: ShortArray, count: Int): Float {
        if (errors >= 5) return -1f
        var best = -1f
        framer.push(pcm, count) { x -> run(x)?.let { best = maxOf(best, it) } }
        if (best >= 0f) last = best
        return if (errors >= 5) -1f else last
    }

    private fun run(x: FloatArray): Float? = try {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { inp ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(RATE)), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to inp, "state" to st, "sr" to sr)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val p = (out.get(0).value as Array<FloatArray>)[0][0]
                        @Suppress("UNCHECKED_CAST")
                        val s = out.get(1).value as Array<Array<FloatArray>>
                        var k = 0
                        for (a in s) for (b in a) for (v in b) state[k++] = v
                        p
                    }
                }
            }
        }
    } catch (e: Throwable) {
        if (++errors == 5) DKLog.d("स्मार्ट VAD बार-बार गलती - इस session में बंद: ${e.message}")
        null
    }

    fun close() { try { session.close() } catch (_: Exception) {} }
}
