package com.dk.assistant.voice.st3

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random

/**
 * 19 नवंबर 2026: Supertonic 3 की असली आवाज़-मशीन (ONNX Runtime) - आधिकारिक `Helper.java` (TextToSpeech._infer) का
 * Kotlin रूप. चार मॉडल एक के बाद एक:
 *
 *   text -> [St3Text] अंक -> duration_predictor (कितनी लंबी आवाज़) -> text_encoder (text की समझ)
 *        -> vector_estimator [steps] बार (शोर से आवाज़ का ढाँचा, कदम-दर-कदम) -> vocoder (44.1 kHz लहर)
 *
 * - Android import नहीं: यही class कंप्यूटर (JVM) पर भी असली मॉडल से चलकर जाँची गई.
 * - भारी: मॉडल एक बार खुलते हैं ([open], ~400 MB RAM) और [close] तक खुले रहते हैं.
 * - [stop] हर कदम पर देखा जाता है - बीच में रोको तो तुरंत null लौटता है.
 * - thread-safe नहीं - एक समय में एक [synth] (ऊपर वाला Mutex रखता है).
 */
class St3Synth private constructor(
    private val env: OrtEnvironment,
    private val dp: OrtSession,
    private val enc: OrtSession,
    private val vec: OrtSession,
    private val voc: OrtSession,
    private val indexer: LongArray,
    val sampleRate: Int,
    private val baseChunk: Int,
    private val compress: Int,
    private val latentDim: Int,
) : AutoCloseable {

    /** एक आवाज़ (F1...) के style अंक */
    class Style internal constructor(val id: String, internal val ttl: OnnxTensor, internal val dp: OnnxTensor) : AutoCloseable {
        override fun close() { try { ttl.close() } catch (ignored: Exception) {}; try { dp.close() } catch (ignored: Exception) {} }
    }

    data class Files(val dp: File, val enc: File, val vec: File, val voc: File, val cfg: File, val indexer: File)

    /** टुकड़े का नतीजा: आवाज़ (-1..1) और मॉडल का बताया समय (सेकंड). [stageMs] = लंबाई, समझ, सारे कदम, लहर (ms) - कौन सा हिस्सा धीमा */
    class Out(val wav: FloatArray, val seconds: Float, val stageMs: LongArray = LongArray(4))

    private val rng = Random()

    fun loadStyle(id: String, json: String): Style {
        val o = JSONObject(json)
        fun tensor(key: String): OnnxTensor {
            val s = o.getJSONObject(key)
            val dims = s.getJSONArray("dims").let { d -> LongArray(d.length()) { d.getLong(it) } }
            val n = dims.fold(1L) { a, b -> a * b }.toInt()
            val flat = FloatArray(n); var i = 0
            fun walk(x: Any?) {
                when (x) {
                    is JSONArray -> for (k in 0 until x.length()) walk(x.get(k))
                    is Number -> {
                        if (i < n) flat[i] = x.toFloat()
                        i++
                    }
                }
            }
            walk(s.get("data"))
            require(i == n) { "आवाज़ $id की फ़ाइल अधूरी ($key: $i/$n)" }
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), dims)
        }
        return Style(id, tensor("style_ttl"), tensor("style_dp"))
    }

    /**
     * एक टुकड़ा (करीब 300 अक्षर तक) बोलो. null = [stop] ने रोका या text में बोलने लायक कुछ नहीं.
     * [steps] ज़्यादा = साफ़ पर धीमा. [speed] 1.05 = सामान्य.
     */
    fun synth(text: String, style: Style, steps: Int, speed: Float, stop: () -> Boolean = { false }): Out? {
        val ids = St3Text.ids(St3Text.preprocess(text), indexer)
        if (ids.size <= 9) return null                  // सिर्फ़ <hi></hi> बचा - कुछ बोलने को नहीं
        val len = ids.size
        val tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, len.toLong()))
        val tMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(len) { 1f }), longArrayOf(1, 1, len.toLong()))
        val opened = ArrayList<AutoCloseable>()
        opened.add(tIds); opened.add(tMask)
        val stage = LongArray(4)
        var t0 = System.nanoTime()
        fun lap(i: Int) { val t = System.nanoTime(); stage[i] = (t - t0) / 1_000_000; t0 = t }
        try {
            if (stop()) return null
            val dur = dp.run(mapOf("text_ids" to tIds, "style_dp" to style.dp, "text_mask" to tMask)).use { r ->
                (r.get(0) as OnnxTensor).floatBuffer.get(0)
            } / speed
            lap(0)
            if (stop()) return null
            val encR = enc.run(mapOf("text_ids" to tIds, "style_ttl" to style.ttl, "text_mask" to tMask))
            opened.add(encR)
            val textEmb = encR.get(0) as OnnxTensor
            lap(1)

            // शोर (noise) से शुरू
            val wavLen = (dur * sampleRate).toLong()
            val chunk = baseChunk * compress
            val latLen = ((wavLen + chunk - 1) / chunk).toInt().coerceAtLeast(1)
            val dim = latentDim * compress
            val validLat = latentLen(wavLen)
            val latMaskArr = FloatArray(latLen) { if (it < validLat) 1f else 0f }
            var xt = FloatArray(dim * latLen)
            for (d in 0 until dim) for (t in 0 until latLen) xt[d * latLen + t] = (rng.nextGaussian() * latMaskArr[t]).toFloat()
            val latShape = longArrayOf(1, dim.toLong(), latLen.toLong())
            val tLatMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(latMaskArr), longArrayOf(1, 1, latLen.toLong()))
            val tTotal = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(steps.toFloat())), longArrayOf(1))
            opened.add(tLatMask); opened.add(tTotal)

            for (step in 0 until steps) {
                if (stop()) return null
                OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), latShape).use { tX ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1)).use { tStep ->
                        vec.run(mapOf("noisy_latent" to tX, "text_emb" to textEmb, "style_ttl" to style.ttl, "latent_mask" to tLatMask,
                            "text_mask" to tMask, "current_step" to tStep, "total_step" to tTotal)).use { r ->
                            val fb = (r.get(0) as OnnxTensor).floatBuffer
                            val nx = FloatArray(fb.remaining()); fb.get(nx); xt = nx
                        }
                    }
                }
            }
            lap(2)
            if (stop()) return null
            val wav = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), latShape).use { tX ->
                voc.run(mapOf("latent" to tX)).use { r ->
                    val fb = (r.get(0) as OnnxTensor).floatBuffer
                    val all = FloatArray(fb.remaining()); fb.get(all); all
                }
            }
            // मॉडल के बताए समय तक ही (पीछे की खाली जगह काटो) - आधिकारिक call() जैसा
            lap(3)
            val keep = (sampleRate * dur).toInt().coerceIn(0, wav.size)
            return Out(if (keep == wav.size) wav else wav.copyOf(keep), dur, stage)
        } finally {
            for (x in opened.asReversed()) try { x.close() } catch (ignored: Exception) {}
        }
    }

    private fun latentLen(wavLen: Long): Int {
        val size = baseChunk.toLong() * compress
        return ((wavLen + size - 1) / size).toInt()
    }

    override fun close() {
        for (s in listOf(dp, enc, vec, voc)) try { s.close() } catch (ignored: Exception) {}
    }

    companion object {
        /** मॉडल खोलो (भारी - पीछे के धागे पर बुलाओ). [threads] = कितने CPU core */
        fun open(f: Files, threads: Int, xnn: Boolean = false): St3Synth {
            val env = OrtEnvironment.getEnvironment()
            val cfg = JSONObject(f.cfg.readText())
            val ae = cfg.getJSONObject("ae"); val ttl = cfg.getJSONObject("ttl")
            val idx = parseIndexer(f.indexer.readText())
            // 19 नवंबर 2026 (फ़ोन का नाप: 9 अक्षर भी 2.3 s, कंप्यूटर पर 0.35 s): [xnn] = ARM के लिए तेज़ गणित (XNNPACK),
            // थ्रेड वही गिनती; ORT के अपने धागे 1 (दो thread-pool आपस में न लड़ें) और इंतज़ार में CPU न घेरें (spinning
            // बंद - उसी समय Live / माइक / VAD भी चल रहे होते हैं). सादा तरीका ([xnn] = false) बिल्कुल पहले जैसा.
            fun opts() = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setInterOpNumThreads(1)
                if (xnn) {
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to threads.coerceIn(1, 8).toString()))
                } else setIntraOpNumThreads(threads.coerceIn(1, 8))
            }
            val sessions = ArrayList<OrtSession>()
            try {
                for (m in listOf(f.dp, f.enc, f.vec, f.voc)) sessions.add(env.createSession(m.path, opts()))
            } catch (e: Throwable) {
                sessions.forEach { try { it.close() } catch (ignored: Exception) {} }
                throw e
            }
            return St3Synth(env, sessions[0], sessions[1], sessions[2], sessions[3], idx,
                ae.getInt("sample_rate"), ae.getInt("base_chunk_size"), ttl.getInt("chunk_compress_factor"), ttl.getInt("latent_dim"))
        }

        fun parseIndexer(json: String): LongArray { val a = JSONArray(json); return LongArray(a.length()) { a.getLong(it) } }

        /** pure: float (-1..1) -> PCM 16-bit little-endian */
        fun pcm16(wav: FloatArray, from: Int = 0, to: Int = wav.size): ByteArray {
            val n = (to - from).coerceAtLeast(0)
            val b = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = (wav[from + i].coerceIn(-1f, 1f) * 32767f).toInt()
                b[i * 2] = (v and 0xff).toByte(); b[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            return b
        }


        /** बातचीत की कतार 24 kHz पर चलती है - टुकड़ों के बीच 0.2 सेकंड की चुप्पी (24 kHz, 16-bit) */
        const val SILENCE_24K_BYTES = 24_000 / 5 * 2

        /**
         * low-pass के गुणांक (windowed-sinc @44.1 kHz) - नीचे लाने पर खड़खड़ाहट (aliasing) न आए.
         * 19 नवंबर 2026 (मालिक: "रोबोटिक गूंज"): 31 गुणांक / 11 kHz पर 12-13 kHz की आवाज़ भी आधी निकल जाती थी और 24 kHz में
         * उलटकर (10-12 kHz) सरसराहट बनती थी. अब 63 गुणांक, 10.5 kHz - सारा कटाव 12 kHz से नीचे.
         */
        private val LP: FloatArray by lazy {
            val taps = 63; val fc = 10_500.0 / 44_100.0; val m = (taps - 1) / 2.0
            val h = DoubleArray(taps) { i ->
                val x = i - m
                val sinc = if (x == 0.0) 2 * fc else Math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1)))      // Hamming
            }
            val sum = h.sum(); FloatArray(taps) { (h[it] / sum).toFloat() }
        }

        /** pure: 44.1 kHz -> 24 kHz (पहले low-pass, फिर linear). [from] = स्रोत की rate */
        fun to24k(wav: FloatArray, from: Int = St3Config.SAMPLE_RATE): FloatArray {
            if (wav.isEmpty() || from == 24_000) return wav
            val lp = LP; val half = lp.size / 2
            val f = FloatArray(wav.size)
            for (i in wav.indices) {
                var acc = 0f
                for (k in lp.indices) { val j = i + k - half; if (j in wav.indices) acc += wav[j] * lp[k] }
                f[i] = acc
            }
            val outN = (wav.size.toLong() * 24_000 / from).toInt()
            return FloatArray(outN) { o ->
                val pos = o.toDouble() * from / 24_000
                val a = pos.toInt().coerceAtMost(f.size - 1); val b = (a + 1).coerceAtMost(f.size - 1); val t = (pos - a).toFloat()
                f[a] + (f[b] - f[a]) * t
            }
        }

        /** pure: बीच में [ms] की चुप्पी (टुकड़ों के बीच) */
        fun silence(ms: Int, rate: Int = St3Config.SAMPLE_RATE): ByteArray = ByteArray((rate.toLong() * ms / 1000).toInt() * 2)
    }
}
