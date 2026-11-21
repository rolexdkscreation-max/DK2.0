package com.dk.assistant

/**
 * 21 नवंबर 2026 (मालिक): "DK का लाइव चल रहा है तो दिखना चाहिए कि लाइव चालू होने से कितना मिनट हुआ.
 * और लाइव ख़त्म करेगा (वॉइस बातचीत) तो कितना मिनट चला, वह भी दिखना चाहिए."
 * pure (Android import नहीं) -> JVM test. [DKState.setLive] इसे बताता है; आवाज़ वाली स्क्रीनें / नोटिफ़िकेशन यहीं से पढ़ते हैं.
 */
object LiveClock {
    /** लाइव कब चालू हुई (0 = अभी नहीं चल रही) */
    @Volatile var startedAt = 0L
        private set
    /** पिछली लाइव कितनी देर चली (ms) और कब ख़त्म हुई */
    @Volatile var lastMs = 0L
        private set
    @Volatile var endedAt = 0L
        private set

    /** लाइव की हालत बदली: चालू = घड़ी शुरू; बंद = चली देर लौटाओ (नहीं चल रही थी = null) */
    @Synchronized fun onLive(on: Boolean, now: Long): Long? {
        if (on) {
            if (startedAt == 0L) startedAt = now
            return null
        }
        if (startedAt == 0L) return null
        val ms = (now - startedAt).coerceAtLeast(0L)
        lastMs = ms; endedAt = now; startedAt = 0L
        return ms
    }

    fun running() = startedAt > 0L
    fun elapsed(now: Long): Long = if (startedAt > 0L) (now - startedAt).coerceAtLeast(0L) else 0L

    /** "0:05", "12:03", "1:02:03" */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }

    /** "45 सेकंड" / "5 मिनट 12 सेकंड" / "1 घंटा 3 मिनट" */
    fun spoken(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when {
            h > 0 -> "$h घंटा" + (if (m > 0) " $m मिनट" else "")
            m > 0 -> "$m मिनट" + (if (sec > 0) " $sec सेकंड" else "")
            else -> "$sec सेकंड"
        }
    }

    /** चलते समय स्क्रीन पर: "लाइव • 03:12" */
    fun runningLine(now: Long) = "लाइव • " + clock(elapsed(now))

    /** ख़त्म होने पर: "DK लाइव ख़त्म • 5 मिनट 12 सेकंड चली (5:12)" */
    fun endLine(ms: Long) = "DK लाइव ख़त्म • ${spoken(ms)} चली (${clock(ms)})"

    /** ख़त्म होने के बाद थोड़ी देर (आवाज़ वाली स्क्रीन पर) पिछली लाइव की लाइन - [showMs] के बाद "" */
    fun lastLine(now: Long, showMs: Long = 60_000L) =
        if (!running() && endedAt > 0L && now - endedAt in 0..showMs) endLine(lastMs) else ""
}
