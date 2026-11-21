package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 21 नवंबर 2026 (मालिक): लाइव चालू होने से कितना समय हुआ + ख़त्म होने पर कितनी देर चली */
class LiveClockTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun clockFormat() {
        assertEquals("0:05", LiveClock.clock(5_400))
        assertEquals("12:03", LiveClock.clock((12 * 60 + 3) * 1000L))
        assertEquals("1:02:03", LiveClock.clock((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("45 सेकंड", LiveClock.spoken(45_000))
        assertEquals("5 मिनट 12 सेकंड", LiveClock.spoken((5 * 60 + 12) * 1000L))
        assertEquals("1 घंटा 3 मिनट", LiveClock.spoken((3600 + 3 * 60 + 9) * 1000L))
    }

    @Test fun startAndEnd() {
        LiveClock.onLive(false, 0L)
        assertNull(LiveClock.onLive(false, 1_000L))          // चल ही नहीं रही थी
        assertNull(LiveClock.onLive(true, 10_000L))
        assertTrue(LiveClock.running())
        assertNull(LiveClock.onLive(true, 20_000L))          // दोबारा चालू = घड़ी वही
        assertEquals("लाइव • 3:12", LiveClock.runningLine(10_000L + 192_000L))
        assertEquals(312_000L, LiveClock.onLive(false, 322_000L))
        assertFalse(LiveClock.running())
        assertEquals("DK लाइव ख़त्म • 5 मिनट 12 सेकंड चली (5:12)", LiveClock.lastLine(330_000L))
        assertEquals("", LiveClock.lastLine(322_000L + 61_000L))
    }

    @Test fun shownOnScreens() {
        assertTrue(src("DKState.kt").contains("LiveClock.onLive(on, System.currentTimeMillis())"))
        assertTrue(src("VoiceActivity.kt").contains("LiveClock.runningLine(now)"))
        assertTrue(src("dkai/DkaiVoice.kt").contains("LiveClock.runningLine(now)"))
        assertTrue(src("LiveNotify.kt").contains("setUsesChronometer(LiveClock.running())"))
    }
}
