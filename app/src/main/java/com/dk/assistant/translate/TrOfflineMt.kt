package com.dk.assistant.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.JsonReader
import com.dk.assistant.DKLog
import com.dk.assistant.Quiet
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import java.io.File
import java.io.InputStreamReader
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 25 नवंबर 2026 (मालिक: "ऑफ़लाइन भी काम करना चाहिए... मॉडल डाल दो, इंटरनेट से डाउनलोड होने वाला"):
 * फ़ोन पर ही अनुवाद - AI4Bharat IndicTrans2 (indic-indic, distilled 320M, int8 ONNX, MIT).
 * मॉडल APK में नहीं (AGENTS.md §8) - [ModelCatalog.MT_INDIC] Wi-Fi पर `filesDir/models/` में (~370 MB, एक बार).
 * चलाता है ऐप का अपना onnxruntime (अलग .so नहीं). Greedy decoding, KV-cache के साथ (sandbox: एक वाक्य ~0.2 s CPU).
 */
object TrOfflineMt {
    private const val MAX_NEW = 96
    @Volatile private var enc: OrtSession? = null
    @Volatile private var dec: OrtSession? = null
    @Volatile private var decPast: OrtSession? = null
    @Volatile private var bpe: TrBpe? = null
    @Volatile var failed: String? = null
        private set
    private val lock = Any()

    fun downloaded(c: Context): Boolean = ModelCatalog.MT_INDIC.all { ModelManager.ready(c, it) }

    /** Wi-Fi पर डाउनलोड शुरू (पहले से हो तो कुछ नहीं) */
    fun download(c: Context) { ModelCatalog.MT_INDIC.forEach { ModelManager.enqueue(c, it) } }

    fun loaded(): Boolean = enc != null && bpe != null

    /** भारी काम (कुछ सेकंड) - background thread से. true = तैयार */
    fun load(c: Context): Boolean = synchronized(lock) {
        if (loaded()) return true
        if (!downloaded(c)) return false
        try {
            val env = OrtEnvironment.getEnvironment()
            val o = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            fun f(a: ModelCatalog.Asset) = ModelManager.file(c, a).absolutePath
            // .onnx.data (वज़न) उसी फ़ोल्डर में उसी नाम से - onnxruntime खुद ढूँढता है
            enc = env.createSession(f(ModelCatalog.MT_ENC), o)
            dec = env.createSession(f(ModelCatalog.MT_DEC), o)
            decPast = env.createSession(f(ModelCatalog.MT_DEC_PAST), o)
            bpe = readTokenizer(ModelManager.file(c, ModelCatalog.MT_TOK_SRC), ModelManager.file(c, ModelCatalog.MT_TOK_TGT))
            failed = null
            DKLog.d("DK ट्रांसलेटर: ऑफ़लाइन अनुवाद मॉडल तैयार")
            true
        } catch (e: Throwable) {
            failed = e.message ?: e.javaClass.simpleName
            DKLog.d("DK ट्रांसलेटर: ऑफ़लाइन मॉडल नहीं खुला: $failed")
            close(); false
        }
    }

    fun close() {
        for (s in listOf(enc, dec, decPast)) try { s?.close() } catch (e: Exception) { Quiet.note(e) }
        enc = null; dec = null; decPast = null; bpe = null
    }

    /** HF tokenizer.json पढ़ो (बड़ी फ़ाइल - streaming, पूरा JSON memory में नहीं) */
    private fun readTokenizer(src: File, tgt: File): TrBpe {
        val vocab = HashMap<String, Int>(140_000); val merges = HashMap<String, Int>(260_000); val tags = HashMap<String, Int>()
        readJson(src, onAdded = { s, id -> tags[s] = id }, onVocab = { s, id -> vocab[s] = id }, onMerge = { s, r -> merges[s] = r })
        val tv = HashMap<String, Int>(140_000); val special = HashSet<Int>()
        readJson(tgt, onAdded = { _, id -> special.add(id) }, onVocab = { s, id -> tv[s] = id }, onMerge = null)
        val arr = arrayOfNulls<String>((tv.values.maxOrNull() ?: 0) + 1)
        for ((s, id) in tv) arr[id] = s
        return TrBpe(vocab, merges, tags, arr, 122_706, special)
    }

    private fun readJson(f: File, onAdded: (String, Int) -> Unit, onVocab: (String, Int) -> Unit, onMerge: ((String, Int) -> Unit)?) {
        JsonReader(InputStreamReader(f.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "added_tokens" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var s = ""; var id = -1
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) { "content" -> s = r.nextString(); "id" -> id = r.nextInt(); else -> r.skipValue() }
                        r.endObject()
                        if (id >= 0) onAdded(s, id)
                    }
                    r.endArray()
                }
                "model" -> {
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "vocab" -> { r.beginObject(); while (r.hasNext()) { val k = r.nextName(); onVocab(k, r.nextInt()) }; r.endObject() }
                        "merges" -> if (onMerge == null) r.skipValue() else {
                            r.beginArray(); var rank = 0
                            while (r.hasNext()) {
                                // नया रूप ["a","b"], पुराना "a b"
                                val m = if (r.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                                    r.beginArray(); val a = r.nextString(); val b = r.nextString(); while (r.hasNext()) r.skipValue(); r.endArray(); "$a $b"
                                } else r.nextString()
                                onMerge(m, rank++)
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
            r.endObject()
        }
    }

    /**
     * text (from भाषा) -> to भाषा. null = नहीं हो सका (मॉडल नहीं / भाषा नहीं).
     * देवनागरी के अलावा लिपि: पहले देवनागरी में ([TrLang.toDeva]), अनुवाद वापस उस लिपि में ([TrLang.fromDeva]).
     */
    fun translate(text: String, from: String, to: String): String? {
        val b = bpe ?: return null; val e = enc ?: return null; val d = dec ?: return null; val dp = decPast ?: return null
        val fl = TrLang.of(from) ?: return null; val tl = TrLang.of(to) ?: return null
        if (!TrLang.offlineFrom(from) || !TrLang.offlineTo(to) || text.isBlank()) return null
        val env = OrtEnvironment.getEnvironment()
        val ids = b.encode(if (fl.base != 0) TrLang.toDeva(text) else text, fl.it2, tl.it2)
        val n = ids.size.toLong()
        val mask = LongArray(ids.size) { 1L }
        val out = ArrayList<Int>()
        synchronized(lock) {
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n)).use { inp ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, n)).use { am ->
                    e.run(mapOf("input_ids" to inp, "attention_mask" to am)).use { er ->
                        val hidden = er.get(0) as OnnxTensor
                        var prev: OrtSession.Result? = null
                        var next = TrBpe.EOS.toLong()
                        try {
                            for (step in 0 until MAX_NEW) {
                                val cur = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(next)), longArrayOf(1, 1))
                                val res = try {
                                    if (step == 0) d.run(mapOf("input_ids" to cur, "encoder_hidden_states" to hidden, "encoder_attention_mask" to am))
                                    else {
                                        val feed = HashMap<String, OnnxTensor>()
                                        feed["input_ids"] = cur; feed["encoder_attention_mask"] = am
                                        prev?.forEach { (k, v) -> if (k.startsWith("present.")) feed["past_key_values." + k.removePrefix("present.")] = v as OnnxTensor }
                                        dp.run(feed)
                                    }
                                } finally { cur.close() }
                                prev?.close(); prev = res
                                val id = argmaxLast(res.get(0))
                                if (id == TrBpe.EOS) break
                                out.add(id); next = id.toLong()
                            }
                        } finally { prev?.close() }
                    }
                }
            }
        }
        val raw = b.decode(out)
        return TrLang.fromDeva(raw, to).ifBlank { null }
    }

    private fun argmaxLast(v: OnnxValue): Int {
        val t = v as OnnxTensor
        val shape = t.info.shape                        // [1, steps, vocab]
        val vsz = shape[2].toInt(); val steps = shape[1].toInt()
        val fb: FloatBuffer = t.floatBuffer
        val off = (steps - 1) * vsz
        var best = 0; var bv = Float.NEGATIVE_INFINITY
        for (i in 0 until vsz) { val x = fb.get(off + i); if (x > bv) { bv = x; best = i } }
        return best
    }
}
