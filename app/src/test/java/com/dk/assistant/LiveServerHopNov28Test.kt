package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 28 नवंबर 2026 (मालिक): अपनी key की limit बातचीत के बीच खत्म = रुकना नहीं, तुरंत DK सर्वर, limit लौटने तक */
class LiveServerHopNov28Test {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n"))
        .first { it.exists() }.readText()

    @Test fun serverBackupMeansNoSilentWait() {
        assertTrue(LiveDkServer.waitOnOwnKey(soft = true, spareOk = false))   // सर्वर नहीं = पहले जैसा छोटा इंतज़ार
        assertFalse(LiveDkServer.waitOnOwnKey(soft = true, spareOk = true))   // सर्वर है = तुरंत सर्वर
        assertFalse(LiveDkServer.waitOnOwnKey(soft = false, spareOk = false))
    }

    @Test fun dailyLimitStaysOnServerTillReset() {
        val now = 1_795_000_000_000L
        assertEquals(LiveDkServer.SPARE_MS, LiveDkServer.spareMs(false, now))
        val d = LiveDkServer.spareMs(true, now)
        assertTrue(d >= LiveDkServer.SPARE_MS && d <= 25 * 3_600_000L)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
        cal.timeInMillis = now + d
        assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test fun midTalkQuotaHopsToServer() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("val spareOk = { livePin == null && !dkServer && LiveDkServer.canSpare() }"))
        assertTrue(g.contains("if (spareOk()) carryToServer()"))
        assertTrue(g.contains("throw Fatal(\"Gemini Live की फ्री लिमिट खत्म\", kind = LiveFailWhy.Kind.QUOTA)"))
        assertTrue(g.contains("LiveDkServer.waitOnOwnKey(LiveOneKey.soft(keys.size, reason, softQuota), spareOk())"))
        assertTrue(src("GeminiLiveTurn.kt").contains("internal fun GeminiLive.carryToServer()"))
    }
}
