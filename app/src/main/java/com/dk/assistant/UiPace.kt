package com.dk.assistant

/**
 * v3.41: UI kitni baar dobara bane (Android import nahi -> JVM test).
 *  • screen update: kai badlaav ek saath, max ~30 baar/sec ([EMIT_GAP_MS])
 *  • background (AuroraView): ~15 frame/sec, scroll/touch ke beech 400ms ruk kar
 */
object UiPace {
    const val EMIT_GAP_MS = 33L
    const val AURORA_MS = 66L
    const val AURORA_BUSY_MS = 400L

    /** pichhle update ke baad kitna rukein (0 = abhi) */
    fun emitWait(lastEmit: Long, now: Long, gap: Long = EMIT_GAP_MS): Long = (lastEmit + gap - now).coerceIn(0L, gap)

    fun auroraDelay(now: Long, busyUntil: Long): Long = if (now < busyUntil) AURORA_BUSY_MS else AURORA_MS

    /** नवंबर 2026 (स्मूथ): चैट स्क्रीन पर इतने से ज़्यादा बुलबुले/कार्ड नहीं (पुरानी बातचीत इतिहास में बची रहती है) */
    const val CHAT_MAX_VIEWS = 160
    /** सबसे ऊपर से कितने हटाएँ (0 = कुछ नहीं) */
    fun trimCount(childCount: Int, max: Int = CHAT_MAX_VIEWS): Int = if (childCount > max) childCount - max else 0

    /** नवंबर 2026 (बग): सूची आगे से कटी (dropped बढ़ा) तो पहले से दिखी लाइनों की गिनती उतनी कम */
    fun shownAfterDrop(shown: Int, seenDrop: Long, drop: Long): Int =
        if (drop > seenDrop) (shown - (drop - seenDrop)).coerceAtLeast(0L).toInt() else shown

    /** एजेंट पट्टी / चलती बत्ती का एक frame (~20/sec काफ़ी, पहले 50ms था - वही) */
    const val STRIP_MS = 50L
}
