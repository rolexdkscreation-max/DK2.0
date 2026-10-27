package com.dk.assistant.voice

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v3.81: "Hey DK" / "Hello DK" - फ़ोन के अंदर, इंटरनेट के बिना, सिर्फ़ मालिक के बोले नमूनों से.
 *
 * तरीका (पुराना, भरोसेमंद "template matching"):
 *  1) सीखना: मालिक 5 बार "Hey DK" (या "Hello DK") बोलता है -> हर बार की आवाज़ से MFCC (13 अंक / 10 ms).
 *  2) सुनना: कोई भी छोटी बात (0.3-2.5 s) -> वही MFCC -> हर नमूने से DTW दूरी (बोलने की रफ़्तार अलग हो तो भी मिलती है).
 *  3) फ़ैसला: सबसे पास वाला नमूना सीमा के अंदर + दूसरा भी पास (एक नमूने से धोखा न हो) = "Hey DK".
 *     सीमा मालिक के अपने नमूनों की आपसी दूरी से बनती है (हर आवाज़ के लिए अपनी) - [Model.build].
 *  4) फिर (अलग, [WakeListener] में) आवाज़-पहचान (CAM++ voiceprint): मालिक की आवाज़ हो तभी जागो.
 *
 * शुद्ध Kotlin + org.json -> JVM test.
 */
object WakeMatch {
    const val RATE = 16000
    const val DIMS = 13
    /** "Hey DK" इससे छोटा/लंबा नहीं होता */
    const val MIN_MS = 300
    const val MAX_MS = 2500
    const val TAKES = 5

    /** संवेदनशीलता: कम = कम ग़लत जागना (कभी दोबारा बोलना पड़े), ज़्यादा = आसानी से जागे */
    enum class Sense(val factor: Float, val label: String) {
        LOW(1.10f, "कम (शोर वाली जगह)"), NORMAL(1.25f, "सामान्य"), HIGH(1.42f, "ज़्यादा (आसानी से जागे)")
    }

    // ------------------------------------------------------------------ आवाज़ -> MFCC

    private val dct: Array<FloatArray> = Array(DIMS) { k ->
        val kk = k + 1   // c0 (कुल ज़ोर) छोड़ो - दूर/पास से बदलता है
        FloatArray(SpeakerFbank.BINS) { n -> cos(PI * kk * (n + 0.5) / SpeakerFbank.BINS).toFloat() }
    }

    /** हर 10 ms फ़्रेम का ज़ोर (log) - चुप्पी काटने के लिए */
    private fun frameEnergy(x: FloatArray): FloatArray {
        val nf = SpeakerFbank.numFrames(x.size)
        return FloatArray(nf) { f ->
            val s = (f * 160 + 80 - 200).coerceAtLeast(0); val e = min(x.size, s + 400)
            var sum = 0.0
            for (i in s until e) sum += x[i] * x[i]
            ln(max(sum / max(1, e - s), 1e-10)).toFloat()
        }
    }

    /** शुरू/आख़िर की चुप्पी हटाकर (frame index) बोलने वाला हिस्सा */
    fun speechRange(energy: FloatArray): IntRange {
        if (energy.isEmpty()) return IntRange.EMPTY
        val sorted = energy.sorted()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.size - 1)]
        val peak = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)]
        if (peak - floor < 1.5f) return energy.indices     // पूरा एक-सा: सब रखो
        val thr = floor + (peak - floor) * 0.25f
        var a = energy.indexOfFirst { it > thr }; var b = energy.indexOfLast { it > thr }
        if (a < 0 || b < a) return energy.indices
        a = (a - 3).coerceAtLeast(0); b = (b + 3).coerceAtMost(energy.size - 1)
        return a..b
    }

    /** PCM (-1..1, 16 kHz) -> [frames][13] MFCC, चुप्पी कटी, हर अंक का औसत 0 / फैलाव 1 (माइक/दूरी का असर कम) */
    fun features(x: FloatArray, n: Int = x.size): Array<FloatArray> {
        val xs = if (n == x.size) x else x.copyOf(n)
        val nf = SpeakerFbank.numFrames(xs.size)
        if (nf < 5) return emptyArray()
        val fb = SpeakerFbank.compute(xs)
        val r = speechRange(frameEnergy(xs))
        val frames = r.map { f ->
            FloatArray(DIMS) { k ->
                var s = 0f; val row = f * SpeakerFbank.BINS; val d = dct[k]
                for (b in 0 until SpeakerFbank.BINS) s += fb[row + b] * d[b]
                s
            }
        }.toTypedArray()
        return cmvn(frames)
    }

    fun cmvn(f: Array<FloatArray>): Array<FloatArray> {
        if (f.isEmpty()) return f
        val d = f[0].size
        val mean = FloatArray(d); val sd = FloatArray(d)
        for (v in f) for (i in 0 until d) mean[i] += v[i]
        for (i in 0 until d) mean[i] /= f.size
        for (v in f) for (i in 0 until d) { val t = v[i] - mean[i]; sd[i] += t * t }
        for (i in 0 until d) sd[i] = sqrt(sd[i] / f.size).coerceAtLeast(1e-3f)
        return Array(f.size) { j -> FloatArray(d) { i -> (f[j][i] - mean[i]) / sd[i] } }
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val t = a[i] - b[i]; s += t * t }
        return sqrt(s)
    }

    /**
     * DTW दूरी (औसत प्रति कदम). band = रफ़्तार का कितना फ़र्क चलेगा (0.5 = आधा/दोगुना तक).
     * बहुत अलग लंबाई (2x से ज़्यादा) = मिलेगा ही नहीं -> बड़ी दूरी.
     */
    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>, band: Float = 0.5f): Float {
        val n = a.size; val m = b.size
        if (n == 0 || m == 0) return Float.MAX_VALUE
        if (max(n, m) > 2 * min(n, m) + 4) return Float.MAX_VALUE
        val w = max((max(n, m) * band).toInt(), kotlin.math.abs(n - m) + 2)
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(m + 1) { inf }; var cur = FloatArray(m + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            cur.fill(inf)
            val center = (i.toLong() * m / n).toInt()
            val lo = max(1, center - w); val hi = min(m, center + w)
            for (j in lo..hi) {
                val c = dist(a[i - 1], b[j - 1])
                val best = min(prev[j - 1], min(prev[j], cur[j - 1]))
                if (best < inf) cur[j] = c + best
            }
            val t = prev; prev = cur; cur = t
        }
        val total = prev[m]
        return if (total >= inf) Float.MAX_VALUE else total / (n + m)
    }

    /**
     * खुला-अंत DTW: पूरा नमूना [t] बनाम [s] का शुरू का कोई भी हिस्सा (0.5x-2x लंबा).
     * "Hey DK, WhatsApp खोलो" एक साँस में बोला -> शुरू का "Hey DK" मिल जाए, बाकी बात इंतज़ार न करे.
     */
    fun dtwOpen(t: Array<FloatArray>, s: Array<FloatArray>): Float {
        val n = t.size
        if (n == 0 || s.isEmpty()) return Float.MAX_VALUE
        val m = min(s.size, 2 * n + 4)
        if (m < n / 2) return Float.MAX_VALUE
        val w = max((n * 0.6f).toInt(), 4)
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(m + 1) { inf }; var cur = FloatArray(m + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            cur.fill(inf)
            val lo = max(1, i - w); val hi = min(m, i + w)
            for (j in lo..hi) {
                val c = dist(t[i - 1], s[j - 1])
                val best = min(prev[j - 1], min(prev[j], cur[j - 1]))
                if (best < inf) cur[j] = c + best
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        var bestScore = Float.MAX_VALUE
        for (j in max(1, n / 2)..m) if (prev[j] < inf) bestScore = min(bestScore, prev[j] / (n + j))
        return bestScore
    }

    // ------------------------------------------------------------------ सीखा हुआ मॉडल

    class Model(val templates: List<Array<FloatArray>>, val base: Float) {
        /** इस संवेदनशीलता पर सीमा */
        fun threshold(s: Sense) = base * s.factor

        /** (सबसे पास, दूसरा सबसे पास) */
        fun distances(f: Array<FloatArray>): Pair<Float, Float> {
            val d = templates.map { dtwOpen(it, f) }.sorted()
            return d.first() to (d.getOrNull(1) ?: d.first())
        }

        fun matches(f: Array<FloatArray>, s: Sense): Boolean {
            if (f.isEmpty()) return false
            val (a, b) = distances(f)
            val th = threshold(s)
            return a <= th && b <= th * 1.15f
        }

        /**
         * बात चलती रहे ("Hey DK, WhatsApp खोलो") तो शुरू के इतने samples (16 kHz) पर जाँचो - बोलना शुरू होने से गिनकर.
         * एक लंबे टुकड़े पर जाँचने से बाद की बात "Hey DK" का औसत बिगाड़ देती है -> कई छोटे कट.
         */
        fun prefixCuts(): List<Int> = listOf(0.9, 1.05, 1.2, 1.35).map { (maxFrames * 160 * it).toInt() }

        /** सबसे छोटे/बड़े नमूने के फ़्रेम (सुनते समय कितनी आवाज़ पर जाँचें) */
        val minFrames: Int get() = templates.minOf { it.size }
        val maxFrames: Int get() = templates.maxOf { it.size }

        fun toJson(): String = JSONObject().put("v", 1).put("base", base.toDouble())
            .put("t", JSONArray().apply {
                templates.forEach { t -> put(JSONArray().apply { t.forEach { fr -> put(fr.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it) }) } }) }
            }).toString()

        companion object {
            fun fromJson(s: String?): Model? = try {
                if (s.isNullOrBlank()) null else {
                    val o = JSONObject(s)
                    val arr = o.getJSONArray("t")
                    val ts = (0 until arr.length()).map { i ->
                        val ta = arr.getJSONArray(i)
                        Array(ta.length()) { j -> ta.getString(j).split(',').map { it.toFloat() }.toFloatArray() }
                    }.filter { t -> t.isNotEmpty() && t.all { it.size == DIMS } }
                    val base = o.getDouble("base").toFloat()
                    if (ts.size < 2 || !base.isFinite() || base <= 0f) null else Model(ts, base)
                }
            } catch (_: Exception) { null }

            /**
             * नमूनों से मॉडल. हर नमूने की बाकी से सबसे कम दूरी (leave-one-out) -> उनमें सबसे बड़ी = base.
             * (मालिक का अपना "Hey DK" आपस में कितना अलग होता है - उतनी छूट, बस.)
             */
            fun build(templates: List<Array<FloatArray>>): Model? {
                val ts = templates.filter { it.isNotEmpty() }
                if (ts.size < 2) return null
                val loo = ts.indices.map { i -> ts.indices.filter { it != i }.minOf { dtwOpen(ts[i], ts[it]) } }
                if (loo.any { !it.isFinite() || it == Float.MAX_VALUE }) return null
                return Model(ts, loo.max())
            }
        }
    }

    /**
     * सीखते समय: कौन-सा नमूना बाकियों से बहुत अलग है (ग़लत शब्द / शोर / कोई और बोला) - उसे दोबारा बुलवाओ.
     * @return उस नमूने का index, या -1 (सब ठीक)
     */
    fun outlier(templates: List<Array<FloatArray>>): Int {
        if (templates.size < 3) return -1
        val loo = templates.indices.map { i -> templates.indices.filter { it != i }.minOf { dtwOpen(templates[i], templates[it]) } }
        val sorted = loo.sorted(); val median = sorted[sorted.size / 2]
        val worst = loo.indices.maxBy { loo[it] }
        return if (loo[worst] > median * 1.6f || loo[worst] == Float.MAX_VALUE) worst else -1
    }

    /** नमूने की लंबाई ठीक? (ms) */
    fun goodLength(speechMs: Int) = speechMs in MIN_MS..MAX_MS

    // ------------------------------------------------------------------ जागने पर क्या बोले

    val DEFAULT_ACKS = listOf("हाँ सर", "बोलिए सर", "हाँ बॉस", "जी, बोलिए")

    /** "हाँ सर, बोलिए सर" -> सूची (कॉमा/नई लाइन से) */
    fun parseAcks(raw: String?): List<String> {
        val l = raw.orEmpty().split(',', '\n', '|').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(8)
        return l.ifEmpty { DEFAULT_ACKS }
    }

    /** पिछली बार वाला नहीं - बदल-बदलकर */
    fun nextAck(list: List<String>, last: String, rnd: kotlin.random.Random = kotlin.random.Random): String {
        if (list.size <= 1) return list.firstOrNull() ?: DEFAULT_ACKS[0]
        val options = list.filter { it != last }
        return options[rnd.nextInt(options.size)]
    }

    /** बोली बात में से शुरू का "Hey DK" हटाओ */
    fun stripWake(text: String, wakeWords: List<String>): String {
        var t = text.trim()
        val low = t.lowercase()
        val words = (wakeWords + listOf("डीके", "dk", "d k", "डी के", "दीके")).map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct()
        val hit = words.mapNotNull { w -> low.indexOf(w).takeIf { it in 0..25 }?.let { it + w.length } }.minOrNull()
        if (hit != null) t = t.substring(hit.coerceAtMost(t.length))
        return t.trim(' ', ',', '.', '!', '।', '?')
    }
}
