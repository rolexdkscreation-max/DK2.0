package com.dk.assistant

/** v3.67: YouTube results पेज से पहला वीडियो (Android import नहीं -> JVM test) */
object YtPage {
    private val VIDEO_ID = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    /**
     * v3.67: पूरा पेज (~1.7 MB) नहीं - पहला वीडियो मिलते ही पढ़ना बंद (आधे से कम पढ़ना, धीमे नेट पर ~1 सेकंड बचे)।
     * pure (okio source) - JVM test में चलता है।
     */
    fun firstVideoId(src: okio.BufferedSource, maxBytes: Long = 4_000_000): String? {
        val sb = StringBuilder()
        var read = 0L
        while (read < maxBytes && src.request(1)) {
            val n = minOf(src.buffer.size, 32_768L)
            val from = maxOf(0, sb.length - 80)        // दो टुकड़ों के बीच कटा id भी मिले
            sb.append(src.buffer.readString(n, Charsets.ISO_8859_1))   // id ASCII है - बाइट = अक्षर, कभी टूटे नहीं
            read += n
            VIDEO_ID.find(sb, from)?.let { return it.groupValues[1] }
            if (sb.length > 200_000) sb.delete(0, sb.length - 200)  // याद में पूरा पेज नहीं
        }
        return null
    }
}
