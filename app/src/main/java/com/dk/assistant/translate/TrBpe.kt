package com.dk.assistant.translate

import java.text.Normalizer

/**
 * 25 नवंबर 2026: ऑफ़लाइन अनुवाद मॉडल (IndicTrans2) के शब्द-टुकड़े (BPE) - Hugging Face `tokenizer_src.json` जैसा ही.
 *
 * तरीका (sandbox में Python `tokenizers` से 100% मेल जाँचा): NFKC -> हर शब्द के आगे "▁" -> merges के क्रम (rank) से
 * जोड़ते जाओ -> vocab id. आगे दो भाषा-टैग ("mar_Deva hin_Deva"), आख़िर में </s> (2). न मिला टुकड़ा = <unk> (3, लगातार वाले एक).
 * Android import नहीं -> JVM test.
 */
class TrBpe(
    /** टुकड़ा -> id (src vocab) */
    private val vocab: Map<String, Int>,
    /** merge जोड़ी "a b" -> rank (छोटा = पहले) */
    private val merges: Map<String, Int>,
    /** भाषा-टैग -> id ("mar_Deva" -> 2xx) */
    private val tags: Map<String, Int>,
    /** id -> टुकड़ा (tgt vocab, अनुवाद पढ़ने के लिए) */
    private val tgt: Array<String?>,
    private val srcSize: Int = Int.MAX_VALUE,
    /** tgt के खास टुकड़े (<s>, भाषा-टैग) - अनुवाद में नहीं दिखते */
    private val special: Set<Int> = emptySet(),
) {
    companion object {
        const val EOS = 2
        const val UNK = 3
        const val PAD = 1
        const val SPACE = "\u2581"

        fun norm(t: String): String = Normalizer.normalize(t, Normalizer.Form.NFKC)

        /** अनुवाद के टुकड़े -> वाक्य (▁ = जगह) + विराम-चिह्न से पहले की फ़ालतू जगह हटाओ */
        fun join(pieces: List<String>): String {
            val s = pieces.joinToString("").replace(SPACE, " ").trim()
            return s.replace(Regex("\\s+([,.!?।॥:;])"), "$1").replace(Regex("\\s+"), " ").trim()
        }
    }

    private fun bpe(word: String): List<String> {
        val syms = ArrayList<String>(word.length)
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i); val n = Character.charCount(cp)
            syms.add(word.substring(i, i + n)); i += n
        }
        while (syms.size > 1) {
            var best = Int.MAX_VALUE; var at = -1
            for (k in 0 until syms.size - 1) {
                val r = merges[syms[k] + " " + syms[k + 1]] ?: continue
                if (r < best) { best = r; at = k }
            }
            if (at < 0) break
            syms[at] = syms[at] + syms[at + 1]
            syms.removeAt(at + 1)
        }
        return syms
    }

    /** text -> ids ([srcTag] [tgtTag] ... </s>) */
    fun encode(text: String, srcTag: String, tgtTag: String): LongArray {
        val out = ArrayList<Long>()
        tags[srcTag]?.let { out.add(it.toLong()) }
        tags[tgtTag]?.let { out.add(it.toLong()) }
        for (w in norm(text).split(' ')) {
            if (w.isEmpty()) continue
            var lastUnk = false
            for (p in bpe(SPACE + w)) {
                val id = vocab[p]?.takeIf { it < srcSize }
                if (id == null) { if (!lastUnk) out.add(UNK.toLong()); lastUnk = true }
                else { out.add(id.toLong()); lastUnk = false }
            }
        }
        out.add(EOS.toLong())
        return out.toLongArray()
    }

    /** ids -> वाक्य (खास टुकड़े <s> </s> <pad> <unk> छोड़कर) */
    fun decode(ids: List<Int>): String = join(ids.filter { it > UNK && it !in special }.mapNotNull { tgt.getOrNull(it) })
}
