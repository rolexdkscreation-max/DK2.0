package com.dk.assistant.voice.engine

/**
 * v3.68: बहते (streaming) जवाब को बोलने लायक टुकड़ों में काटो.
 *
 * - पहला टुकड़ा: जल्दी से जल्दी - पूरा वाक्य (।.!?) या अल्पविराम तक, क़रीब 5-8 शब्द
 *   ([FIRST_SOFT] शब्द होते ही अल्पविराम पर, [FIRST_MAX] शब्दों पर ज़बरदस्ती).
 * - बाद के टुकड़े: पहला बजते-बजते बनते हैं, इसलिए थोड़े बड़े (कम request, आवाज़ ज़्यादा एक-सी):
 *   वाक्य ख़त्म + कम से कम [NEXT_MIN] शब्द, या [NEXT_SOFT] शब्द के बाद अल्पविराम, या [NEXT_MAX] पर.
 * - "3.5" जैसे दशमलव पर नहीं कटता.
 * बिना Android के (JVM test में चलता है).
 */
class TextChunker(
    private val firstSoft: Int = FIRST_SOFT,
    private val firstMax: Int = FIRST_MAX,
    private val nextMin: Int = NEXT_MIN,
    private val nextSoft: Int = NEXT_SOFT,
    private val nextMax: Int = NEXT_MAX,
) {
    private val buf = StringBuilder()
    /** अब तक कितने टुकड़े निकले (0 = पहला अभी बाकी) */
    var emitted = 0
        private set

    fun reset() { buf.setLength(0); emitted = 0 }

    /** नया text जोड़ो, जो टुकड़े पूरे हो गए वे लौटाओ */
    fun feed(t: String): List<String> {
        if (t.isEmpty()) return emptyList()
        buf.append(clean(t))
        val out = ArrayList<String>(2)
        while (true) {
            val cut = findCut(final = false)
            if (cut <= 0) break
            take(cut)?.let { out.add(it) }
        }
        return out
    }

    /** जवाब ख़त्म: जो बचा वह आख़िरी टुकड़ा */
    fun flush(): String? {
        val r = buf.toString().trim()
        buf.setLength(0)
        if (!hasWords(r)) return null
        emitted++
        return r
    }

    /** पहला टुकड़ा अभी बना नहीं और text रुका पड़ा है - कम से कम [min] शब्द हों तो अभी दे दो */
    fun flushFirstIfStalled(min: Int = 3): String? {
        if (emitted > 0) return null
        if (words(buf.toString()) < min) return null
        return flush()
    }

    fun pendingWords(): Int = words(buf.toString())

    private fun take(cut: Int): String? {
        val piece = buf.substring(0, cut).trim()
        buf.delete(0, cut)
        // अगले टुकड़े की शुरुआत की खाली जगह हटाओ
        while (buf.isNotEmpty() && buf[0].isWhitespace()) buf.deleteCharAt(0)
        if (!hasWords(piece)) return null
        emitted++
        return piece
    }

    /** कहाँ काटें (index, उस char के बाद). 0 = अभी नहीं */
    private fun findCut(final: Boolean): Int {
        val s = buf
        val first = emitted == 0
        var wordsSoFar = 0
        var inWord = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch.isWhitespace()) {
                if (inWord) { wordsSoFar++; inWord = false }
                // ज़बरदस्ती: इतने शब्द हो गए और अगला शब्द शुरू होने वाला है
                val max = if (first) firstMax else nextMax
                if (wordsSoFar >= max) return i
            } else {
                inWord = true
                val w = wordsSoFar + 1                       // यह char जिस शब्द में है, उसे गिनकर
                if (ch in HARD) {
                    // "3.5" / "v3.68": बिंदु के बाद अंक = दशमलव, कटाव नहीं. आख़िरी char हो तो अगले का इंतज़ार
                    if (ch == '.') {
                        if (i + 1 >= s.length) return if (final) s.length else 0
                        if (s[i + 1].isDigit() || s[i + 1] == '.') { i++; continue }
                    }
                    val need = if (first) 1 else nextMin
                    if (w >= need) return endAfterMarks(i)
                } else if (ch in SOFT) {
                    val need = if (first) firstSoft else nextSoft
                    if (w >= need) return endAfterMarks(i)
                }
            }
            i++
        }
        return 0
    }

    /** "।।", "?!", "...", ")" जैसे लगातार निशान साथ ले जाओ */
    private fun endAfterMarks(i: Int): Int {
        var j = i + 1
        while (j < buf.length && (buf[j] in HARD || buf[j] in SOFT || buf[j] in CLOSERS)) j++
        return j
    }

    companion object {
        const val FIRST_SOFT = 5
        const val FIRST_MAX = 8
        const val NEXT_MIN = 6
        const val NEXT_SOFT = 12
        const val NEXT_MAX = 28
        private val HARD = charArrayOf('।', '॥', '.', '!', '?', '…', '\n').toSet()
        private val SOFT = charArrayOf(',', ';', ':', '—', '–', '،').toSet()
        private val CLOSERS = charArrayOf(')', '"', '\'', '”', '’', ']').toSet()
        private val MARKDOWN = Regex("[*#`_~>|]+")

        fun words(s: String): Int = s.trim().split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }
        private fun hasWords(s: String) = s.any { it.isLetterOrDigit() }

        /** बोलने में न आने वाले निशान (markdown) हटाओ */
        fun clean(t: String): String = MARKDOWN.replace(t, " ")
    }
}
