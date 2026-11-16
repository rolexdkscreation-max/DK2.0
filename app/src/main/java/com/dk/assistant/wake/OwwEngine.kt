package com.dk.assistant.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * 16 नवंबर 2026: openWakeWord के ONNX मॉडल फ़ोन पर (onnxruntime-android, जो ऐप में पहले से है - नई library नहीं, APK नहीं बढ़ता).
 * एक thread, कम CPU: हर 80 ms में melspectrogram (~1 MB मॉडल) + embedding (~1.3 MB) + दो छोटे classifier.
 */
class OwwEngine private constructor(
    private val env: OrtEnvironment,
    private val melS: OrtSession,
    private val embS: OrtSession,
    private val headS: Map<String, OrtSession>,
) : AutoCloseable {

    val words: Set<String> get() = headS.keys
    private val melIn = melS.inputNames.first()
    private val embIn = embS.inputNames.first()
    private val headIn = headS.mapValues { it.value.inputNames.first() }

    val stream = OwwStream(::runMel, ::runEmb, headS.keys.associateWith { id -> { w: Array<FloatArray> -> runHead(id, w) } })

    private fun runMel(x: FloatArray): Array<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { t ->
            melS.run(mapOf(melIn to t)).use { r ->
                // output: [time=1, 1, frames, 32]
                @Suppress("UNCHECKED_CAST")
                val v = r[0].value as Array<Array<Array<FloatArray>>>
                v[0][0]
            }
        }

    private fun runEmb(win: Array<FloatArray>): FloatArray {
        val buf = FloatBuffer.allocate(WakeRules.EMB_WIN * WakeRules.MEL_BINS)
        for (row in win) buf.put(row)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, WakeRules.EMB_WIN.toLong(), WakeRules.MEL_BINS.toLong(), 1)).use { t ->
            embS.run(mapOf(embIn to t)).use { r ->
                // output: [1, 1, 1, 96]
                @Suppress("UNCHECKED_CAST")
                val v = r[0].value as Array<Array<Array<FloatArray>>>
                v[0][0][0].copyOf()
            }
        }
    }

    private fun runHead(id: String, feats: Array<FloatArray>): Float {
        val s = headS[id] ?: return 0f
        val buf = FloatBuffer.allocate(WakeRules.FEAT_FRAMES * WakeRules.EMB_DIM)
        for (row in feats) buf.put(row)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, WakeRules.FEAT_FRAMES.toLong(), WakeRules.EMB_DIM.toLong())).use { t ->
            s.run(mapOf(headIn.getValue(id) to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val v = r[0].value as Array<FloatArray>
                v[0][0]
            }
        }
    }

    override fun close() {
        (headS.values + melS + embS).forEach { try { it.close() } catch (_: Exception) {} }
    }

    companion object {
        /** null = कोई फ़ाइल नहीं / नहीं खुली (गलती [lastError] में) */
        @Volatile var lastError: String? = null
            private set

        fun open(mel: File, emb: File, heads: Map<String, File>): OwwEngine? {
            if (!mel.isFile || !emb.isFile || heads.isEmpty() || heads.values.any { !it.isFile }) { lastError = "मॉडल फ़ाइल नहीं मिली"; return null }
            val env = OrtEnvironment.getEnvironment()
            val opened = ArrayList<OrtSession>()
            return try {
                // 1 thread = कम बैटरी (हर 80 ms में थोड़ा सा काम)
                fun sess(f: File) = env.createSession(f.absolutePath, OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1); setInterOpNumThreads(1); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }).also { opened.add(it) }
                val m = sess(mel); val e = sess(emb)
                val h = heads.mapValues { sess(it.value) }
                lastError = null
                OwwEngine(env, m, e, h)
            } catch (t: Throwable) {
                opened.forEach { try { it.close() } catch (_: Exception) {} }
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                null
            }
        }
    }
}
