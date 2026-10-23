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
}
