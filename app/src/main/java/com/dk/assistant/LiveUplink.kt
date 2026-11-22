package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक का log #6: "60-78 KB upload अटका, 11 टुकड़े गिरे"):
 * - network की line (OkHttp queue) [HOLD_AT] से ज़्यादा भरी = नई असली आवाज़ फ़ोन में ही थोड़ी देर रोको ([Hold]);
 *   रुकी आवाज़ [MAX_HOLD_CHUNKS] से ज़्यादा = सबसे पुराना टुकड़ा गिराओ (नया रखो - ताज़ी बात ज़रूरी).
 *   OkHttp की line में जा चुका data वापस नहीं निकलता, इसलिए "पुराना गिराना" फ़ोन वाली रोक में होता है.
 * - audioStreamEnd तभी जब फ़ोन में रुकी आवाज़ पूरी चली जाए (line खुद क्रम से भेजती है - end आवाज़ के बाद ही पहुँचता है).
 * - network धीमा ([Slow]) = सिर्फ़ पता रखना (log). 22 नवंबर 2026: पहले 8 kHz पर बदल देते थे, पर Gemini Live एक
 *   connection में पहली आवाज़ के बाद rate बदलने पर code=1007 ("Sample rate changed ... not supported") से बंद करता है -
 *   यही बार-बार टूटने की वजह थी. अब rate हमेशा 16 kHz ([wireRate]).
 * pure (JVM test).
 */
object LiveUplink {
    const val HOLD_AT = 30 * 1024
    /** 40 ms के टुकड़े: 25 = 1 s तक फ़ोन में रुके */
    const val MAX_HOLD_CHUNKS = 25
    const val SLOW_RATE = 8_000
    /** line इतनी खाली इतनी देर = फिर पूरी quality */
    const val CLEAR_BYTES = 4 * 1024
    const val CLEAR_MS = 5_000L

    class Hold(private val max: Int = MAX_HOLD_CHUNKS) {
        private val q = ArrayDeque<ByteArray>()
        var dropped = 0; private set
        val size: Int get() = synchronized(q) { q.size }
        /** रोको; भरा हो तो सबसे पुराना गिरा - गिरा टुकड़ा लौटाओ (नहीं तो null) */
        fun add(pcm: ByteArray): ByteArray? = synchronized(q) {
            q.addLast(pcm)
            if (q.size > max) { dropped++; q.removeFirst() } else null
        }
        fun drain(): List<ByteArray> = synchronized(q) { val l = q.toList(); q.clear(); l }
        fun clear() = synchronized(q) { q.clear() }
        fun resetCount() { dropped = 0 }
    }

    /** line पर जाने वाला rate - धीमे network पर भी वही (session के बीच बदलना = 1007). pure */
    @Suppress("UNUSED_PARAMETER")
    fun wireRate(slow: Boolean): Int = GeminiLive.IN_RATE

    /** network धीमा है? queue भरी = हाँ; [CLEAR_MS] तक लगभग खाली = नहीं */
    class Slow {
        var on = false; private set
        private var clearSince = 0L
        /** लौटाता है: बदला (log के लिए) */
        fun update(queue: Long, now: Long): Boolean {
            if (queue > HOLD_AT) {
                clearSince = 0L
                val changed = !on
                on = true
                return changed
            }
            if (!on) return false
            if (queue > CLEAR_BYTES) { clearSince = 0L; return false }
            if (clearSince == 0L) clearSince = now
            if (now - clearSince >= CLEAR_MS) { on = false; clearSince = 0L; return true }
            return false
        }
        fun reset() { on = false; clearSince = 0L }
    }

    /** रोकना है? (सिर्फ़ असली आवाज़; खामोशी पहले जैसी 32 KB पर गिरती है) */
    fun mustHold(silent: Boolean, queue: Long): Boolean = !silent && queue > HOLD_AT

    /** PCM16 16 kHz -> 8 kHz (दो-दो sample का औसत). pure */
    fun half(pcm: ByteArray): ByteArray {
        val n = pcm.size / 4
        val out = ByteArray(n * 2)
        for (k in 0 until n) {
            val i = k * 4
            val a = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val b = ((pcm[i + 2].toInt() and 0xff) or (pcm[i + 3].toInt() shl 8)).toShort().toInt()
            val m = (a + b) / 2
            out[k * 2] = (m and 0xff).toByte(); out[k * 2 + 1] = ((m shr 8) and 0xff).toByte()
        }
        return out
    }
}
