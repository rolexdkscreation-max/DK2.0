package com.dk.assistant.agent.social

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 16 नवंबर 2026 (मालिक: "गैलरी से कौन सी फोटो/वीडियो पिक करनी है, वो खुद करे"): MobileCLIP2-S0 का pure हिस्सा (JVM test).
 *  - [ClipTokenizer]: open_clip का SimpleTokenizer (CLIP BPE) - 77 अंक, sot 49406, eot 49407, बाकी 0
 *  - [Int4Table]: शब्द-तालिका की एक पंक्ति (288 bytes) -> 512 float
 *  - [ClipImage]: 256x256 तस्वीर -> CHW float (0..1, बिना mean/std - sandbox में जाँचा, normalisation से नतीजा गलत आता है)
 *  - [GalleryRank]: वर्णन बनाम हर फोटो (cosine) -> सबसे सही, या साफ़ "नहीं मिली"
 * Android / ONNX वाला हिस्सा: agent/android/GalleryClip.kt
 */
class ClipTokenizer(merges: List<Pair<String, String>>) {
    private val enc = HashMap<String, Int>(merges.size * 2 + 1024)
    private val ranks = HashMap<Pair<String, String>, Int>(merges.size * 2)
    private val b2u: Array<String> = byteToUnicode()
    private val cache = HashMap<String, List<String>>()

    init {
        var i = 0
        for (b in BYTE_ORDER) enc[b2u[b]] = i++
        for (b in BYTE_ORDER) enc[b2u[b] + "</w>"] = i++
        merges.forEachIndexed { r, m -> ranks[m] = r; enc[m.first + m.second] = i++ }
        enc["<|startoftext|>"] = i++
        enc["<|endoftext|>"] = i
    }

    /** 77 अंक: [sot] + टुकड़े + [eot], लंबा हो तो काटकर आख़िरी eot */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(80)
        ids.add(SOT)
        val clean = text.replace(Regex("\\s+"), " ").trim().lowercase()
        for (m in PAT.findAll(clean)) {
            val w = m.value.toByteArray(Charsets.UTF_8).joinToString("") { b2u[it.toInt() and 0xff] }
            for (t in bpe(w)) ids.add(enc[t] ?: continue)
        }
        val out = IntArray(CTX)
        val body = ids.take(CTX - 1)
        body.forEachIndexed { k, v -> out[k] = v }
        out[body.size] = EOT
        return out
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        val cps = token.codePoints().toArray().map { String(Character.toChars(it)) }
        var word: List<String> = if (cps.isEmpty()) emptyList() else cps.dropLast(1) + (cps.last() + "</w>")
        while (word.size > 1) {
            var best: Pair<String, String>? = null; var bestRank = Int.MAX_VALUE
            for (k in 0 until word.size - 1) {
                val p = word[k] to word[k + 1]
                val r = ranks[p] ?: continue
                if (r < bestRank) { bestRank = r; best = p }
            }
            val (a, b) = best ?: break
            val nw = ArrayList<String>(word.size)
            var k = 0
            while (k < word.size) {
                if (k < word.size - 1 && word[k] == a && word[k + 1] == b) { nw.add(a + b); k += 2 } else { nw.add(word[k]); k++ }
            }
            word = nw
        }
        if (cache.size < 4096) cache[token] = word
        return word
    }

    companion object {
        const val SOT = 49406
        const val EOT = 49407
        const val CTX = 77
        const val VOCAB = 49408
        private val PAT = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+", RegexOption.IGNORE_CASE)

        /** GPT-2 / CLIP का byte -> unicode (पहले छपने वाले अक्षर, फिर बाकी 256+n) */
        private val BYTE_ORDER: IntArray by lazy {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val set = bs.toHashSet()
            (0..255).filter { it !in set }.forEach { bs += it }
            bs.toIntArray()
        }

        private fun byteToUnicode(): Array<String> {
            val out = Array(256) { "" }
            val printable = ArrayList<Int>().apply { addAll('!'.code..'~'.code); addAll('¡'.code..'¬'.code); addAll('®'.code..'ÿ'.code) }.toHashSet()
            var n = 0
            for (b in 0..255) out[b] = if (b in printable) String(Character.toChars(b)) else String(Character.toChars(256 + n++))
            return out
        }

        /** bpe_simple_vocab_16e6.txt.gz: पहली पंक्ति शीर्षक, फिर 48894 merges (open_clip की तरह) */
        fun load(gz: InputStream): ClipTokenizer {
            val lines = GZIPInputStream(gz).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            val merges = lines.subList(1, minOf(lines.size, 49152 - 256 - 2 + 1)).mapNotNull { l ->
                val p = l.split(' '); if (p.size == 2) p[0] to p[1] else null
            }
            return ClipTokenizer(merges)
        }
    }
}

object Int4Table {
    const val DIM = 512
    const val ROW = 288

    /** एक पंक्ति: 256 bytes (दो-दो nibble, सम अंक नीचे वाले में, q + 8) + 16 float16 scale (little-endian) */
    fun unpack(row: ByteArray, off: Int, out: FloatArray, outOff: Int) {
        for (g in 0 until 16) {
            val lo = row[off + 256 + g * 2].toInt() and 0xff
            val hi = row[off + 256 + g * 2 + 1].toInt() and 0xff
            val sc = half((hi shl 8) or lo)
            for (j in 0 until 16) {
                val b = row[off + g * 16 + j].toInt() and 0xff
                out[outOff + g * 32 + j * 2] = ((b and 15) - 8) * sc
                out[outOff + g * 32 + j * 2 + 1] = ((b shr 4) - 8) * sc
            }
        }
    }

    /** IEEE float16 -> float (JDK 17 में Float.float16ToFloat नहीं) */
    fun half(h: Int): Float {
        val s = (h shr 15) and 1; val e = (h shr 10) and 0x1f; val m = h and 0x3ff
        val v = when (e) {
            0 -> m / 1024f * Math.pow(2.0, -14.0).toFloat()
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1 + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
        }
        return if (s == 1) -v else v
    }

    /** eot की जगह = सबसे बड़े id की जगह (open_clip: argmax) */
    fun eotIndex(ids: IntArray): Int { var b = 0; for (i in ids.indices) if (ids[i] > ids[b]) b = i; return b }
}

object ClipImage {
    const val SIZE = 256

    /** छोटी भुजा 256, फिर बीच से 256x256: (scaledW, scaledH, cropX, cropY) */
    fun plan(w: Int, h: Int): IntArray {
        val s = SIZE.toDouble() / minOf(w, h)
        val sw = maxOf(SIZE, Math.round(w * s).toInt()); val sh = maxOf(SIZE, Math.round(h * s).toInt())
        return intArrayOf(sw, sh, (sw - SIZE) / 2, (sh - SIZE) / 2)
    }

    /** ARGB pixels (256x256) -> [3,256,256] float 0..1 */
    fun chw(argb: IntArray): FloatArray {
        val n = SIZE * SIZE
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = argb[i]
            out[i] = ((p shr 16) and 0xff) / 255f
            out[n + i] = ((p shr 8) and 0xff) / 255f
            out[2 * n + i] = (p and 0xff) / 255f
        }
        return out
    }
}

object GalleryRank {
    /** इससे कम = वर्णन वाली फोटो पक्की नहीं (sandbox: सही जोड़ी ~0.24, गलत ~0.08) */
    const val MIN_SCORE = 0.16f
    /** इतना करीब = लगभग एक जैसी (एक ही जगह की कई फोटो) - तब नई वाली */
    const val TIE = 0.004f

    data class Item(val uri: String, val mime: String, val at: Long, val name: String = "")
    data class Hit(val item: Item, val score: Float)

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0; for (x in v) s += x * x
        val n = Math.sqrt(s).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }

    fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }

    /** सबसे मिलती पहले; बराबरी जैसी हो तो नई पहले */
    fun rank(query: FloatArray, items: List<Pair<Item, FloatArray>>): List<Hit> =
        items.map { Hit(it.first, dot(query, it.second)) }
            .sortedWith { x, y -> if (Math.abs(x.score - y.score) <= TIE) y.item.at.compareTo(x.item.at) else y.score.compareTo(x.score) }

    /** null = कोई फोटो वर्णन से पक्की नहीं मिली (झूठा चुनाव नहीं) */
    fun best(query: FloatArray, items: List<Pair<Item, FloatArray>>): Hit? = rank(query, items).firstOrNull()?.takeIf { it.score >= MIN_SCORE }

    /** CLIP अंग्रेज़ी समझता है - "a photo of ..." */
    fun prompt(desc: String): String {
        val d = desc.trim().removePrefix("a photo of").removePrefix("photo of").trim()
        return "a photo of " + d.ifEmpty { "something" }
    }

    /** embedding cache: हर पंक्ति = key (uri|at) + 512 float, एक फ़ाइल में */
    fun encodeCache(rows: Map<String, FloatArray>): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bo).use { d ->
            d.writeInt(MAGIC); d.writeInt(rows.size)
            for ((k, v) in rows) { d.writeUTF(k); d.writeShort(v.size); v.forEach { d.writeFloat(it) } }
        }
        return bo.toByteArray()
    }

    fun decodeCache(b: ByteArray): Map<String, FloatArray> = try {
        java.io.DataInputStream(b.inputStream()).use { d ->
            if (d.readInt() != MAGIC) return emptyMap()
            val n = d.readInt(); val out = LinkedHashMap<String, FloatArray>(n * 2)
            repeat(n) { val k = d.readUTF(); val len = d.readShort().toInt(); out[k] = FloatArray(len) { d.readFloat() } }
            out
        }
    } catch (_: Exception) { emptyMap() }

    fun key(uri: String, at: Long) = "$uri|$at"
    private const val MAGIC = 0x444b4331   // "DKC1"
}
