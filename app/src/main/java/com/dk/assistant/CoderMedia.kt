package com.dk.assistant

/**
 * कोडिंग एजेंट के साथ भेजी गई फ़ोटो / वीडियो (Android-free - JUnit से जाँचा जाता है)।
 *
 * - फ़ोटो: एक JPEG (base64) frame
 * - वीडियो: कुछ बराबर दूरी के frames (हर मॉडल देख सके) + छोटा हो तो पूरा वीडियो (सिर्फ़ Gemini देख सकता है)
 */
data class CoderMedia(
    val kind: String,
    val label: String,
    val frames: List<String>,
    val video: String? = null,
    val videoMime: String = "video/mp4",
    val durationMs: Long = 0,
) {
    val isVideo get() = kind == VIDEO

    /** AI को भेजने लायक हिस्से: (mime, base64) */
    fun parts(gemini: Boolean): List<Pair<String, String>> =
        if (gemini && video != null) listOf(videoMime to video) else frames.map { "image/jpeg" to it }

    /** लिखा हुआ एक-लाइन परिचय (हर एजेंट को, भले मॉडल फ़ोटो न देख सके) */
    fun line(): String =
        if (isVideo) "VIDEO \"$label\" (${secs(durationMs)}s, ${frames.size} frames" + (if (video != null) " + full video" else "") + ")"
        else "PHOTO \"$label\""

    companion object {
        const val IMAGE = "image"
        const val VIDEO = "video"
        const val MAX_ITEMS = 6
        const val FRAMES = 8
        /** इससे बड़ा वीडियो पूरा नहीं - सिर्फ़ frames (फ़ोन की memory बचाने के लिए) */
        const val VIDEO_INLINE_MAX = 8 * 1024 * 1024
        /** एक request में फ़ोटो/वीडियो का कुल base64 (Gemini inline सीमा ~20MB से नीचे) */
        const val TOTAL_B64_MAX = 18 * 1024 * 1024

        private fun secs(ms: Long) = ((ms + 500) / 1000).coerceAtLeast(0)

        /** वीडियो में बराबर दूरी के समय (ms) - शुरू/आख़िर के बिल्कुल किनारे छोड़कर */
        fun frameTimes(durationMs: Long, n: Int = FRAMES): List<Long> {
            if (n <= 0) return emptyList()
            if (durationMs <= 0) return listOf(0L)
            return (0 until n).map { i -> durationMs * (2 * i + 1) / (2L * n) }
        }

        /** सबके हिस्से - कुल सीमा के अंदर (पहले आए पहले) */
        fun allParts(list: List<CoderMedia>, gemini: Boolean): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            var total = 0L
            for (m in list.take(MAX_ITEMS)) {
                var ps = m.parts(gemini)
                // पूरा वीडियो न समाए तो उसके frames
                if (ps.sumOf { it.second.length.toLong() } + total > TOTAL_B64_MAX && m.video != null) ps = m.frames.map { "image/jpeg" to it }
                for (p in ps) {
                    if (total + p.second.length > TOTAL_B64_MAX) return out
                    out += p; total += p.second.length
                }
            }
            return out
        }

        /** सबका लिखा परिचय - AI के text में */
        fun describe(list: List<CoderMedia>): String =
            if (list.isEmpty()) "" else "ATTACHED BY THE USER: " + list.take(MAX_ITEMS).joinToString("; ") { it.line() } +
                ". Video frames are in time order."

        /** चैट में दिखाने के लिए */
        fun chip(list: List<CoderMedia>): String {
            val p = list.count { !it.isVideo }; val v = list.count { it.isVideo }
            return listOfNotNull(if (p > 0) "$p फ़ोटो" else null, if (v > 0) "$v वीडियो" else null).joinToString(", ").let { if (it.isEmpty()) "" else "📎 $it" }
        }
    }
}
