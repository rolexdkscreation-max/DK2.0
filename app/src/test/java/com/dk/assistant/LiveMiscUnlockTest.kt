package com.dk.assistant

import com.dk.assistant.voice.VgRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 21 नवंबर 2026 (मालिक): DK Live #4 / #6, आवाज़ का slider, अनलॉक में कितना ऊपर स्लाइड */
class LiveMiscUnlockTest {
    private fun src(p: String): String {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        return java.io.File(root, "com/dk/assistant/$p").readText()
    }

    // ---------------------------------------------------------------- अनलॉक स्लाइड
    @Test fun unlockSwipeDefaultIsOldDistance() {
        assertEquals(60, UnlockSwipe.DEF)
        val (y0, y1) = UnlockSwipe.ys(1000, UnlockSwipe.DEF)
        assertEquals(850f, y0, 0.5f)
        assertEquals(250f, y1, 0.5f)                         // पहले जैसा 0.85 -> 0.25
        assertEquals(60, UnlockSwipe.pctFor(60, false, 2))    // "और ऊपर" बंद = हर बार वही
    }

    @Test fun unlockSwipeShortLongAndMore() {
        assertEquals(550f, UnlockSwipe.ys(1000, 30).second, 0.5f)
        assertEquals(50f, UnlockSwipe.ys(1000, 80).second, 0.5f)   // ऊपर 5% पर रुकता
        assertEquals(UnlockSwipe.MIN, UnlockSwipe.clamp(3))
        assertEquals(UnlockSwipe.MAX, UnlockSwipe.clamp(500))
        assertEquals(45, UnlockSwipe.clamp(44))
        assertEquals(30, UnlockSwipe.pctFor(30, true, 0))
        assertEquals(40, UnlockSwipe.pctFor(30, true, 1))
        assertEquals(UnlockSwipe.MAX, UnlockSwipe.pctFor(75, true, 2))
    }

    @Test fun unlockSwipeWired() {
        val u = src("PhoneUnlocker.kt")
        assertTrue(u.contains("UnlockSwipe.pctFor(upPct, upMore, tries)"))
        assertFalse(u.contains("dm.heightPixels * 0.25f"))
        assertTrue(src("SettingsPhoneLock.kt").contains("UnlockSwipe.setPct("))
    }

    // ---------------------------------------------------------------- DK Live आवाज़
    @Test fun liveVoiceLevelOffByDefaultAndScales() {
        assertEquals(100, LiveVoiceLevel.active(false, 30))
        assertEquals(30, LiveVoiceLevel.active(true, 30))
        val b = byteArrayOf(0x10, 0x27, 0xF0.toByte(), 0xD8.toByte())      // 10000, -10000
        assertSame(b, LiveVoiceLevel.scale(b, 100))
        val h = LiveVoiceLevel.scale(b, 50)
        assertEquals(5000, ((h[0].toInt() and 0xff) or (h[1].toInt() shl 8)).toShort().toInt())
        assertEquals(-5000, ((h[2].toInt() and 0xff) or (h[3].toInt() shl 8)).toShort().toInt())
        assertTrue(src("GeminiLivePlayer.kt").contains("LiveVoiceLevel.apply(svc, bFx)"))
    }

    // ---------------------------------------------------------------- #4 छोटी आवाज़
    @Test fun noStrangerWarningUnderOneSecond() {
        assertFalse(VgRules.warnOk(340))
        assertTrue(VgRules.warnOk(1_000))
        assertTrue(src("GeminiLiveOwner.kt").contains("VgRules.warnOk(ms)"))
    }

    // ---------------------------------------------------------------- #6 network
    @Test fun uplinkHoldsAndDropsOldest() {
        val h = LiveUplink.Hold(3)
        assertNull(h.add(byteArrayOf(1))); assertNull(h.add(byteArrayOf(2))); assertNull(h.add(byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(1), h.add(byteArrayOf(4)))   // सबसे पुराना गिरा
        assertEquals(1, h.dropped)
        assertEquals(listOf(2, 3, 4), h.drain().map { it[0].toInt() })
        assertEquals(0, h.size)
        assertTrue(LiveUplink.mustHold(false, 31 * 1024L))
        assertFalse(LiveUplink.mustHold(true, 31 * 1024L))      // खामोशी पहले जैसी
        assertFalse(LiveUplink.mustHold(false, 20 * 1024L))
    }

    @Test fun slowNetworkHalvesRateThenRecovers() {
        val s = LiveUplink.Slow()
        assertFalse(s.update(10_000, 0))
        assertTrue(s.update(40_000, 100)); assertTrue(s.on)
        assertFalse(s.update(1_000, 200))
        assertFalse(s.update(1_000, 200 + LiveUplink.CLEAR_MS - 1))
        assertTrue(s.update(1_000, 200 + LiveUplink.CLEAR_MS)); assertFalse(s.on)
        val pcm = byteArrayOf(0x64, 0, 0xC8.toByte(), 0, 0, 0, 0, 0)          // 100, 200, 0, 0
        val half = LiveUplink.half(pcm)
        assertEquals(4, half.size)
        assertEquals(150, (half[0].toInt() and 0xff) or (half[1].toInt() shl 8))
    }

    @Test fun streamEndAfterHeldAudio() {
        val t = src("GeminiLiveTurnEnd.kt")
        assertTrue(t.contains("flushHeldAudio(w) && try { w.tx("))
        val c = src("GeminiLiveChecks.kt")
        assertTrue(c.contains("LiveUplink.mustHold(silent, q)"))
        assertTrue(c.contains("rate=\${if (slow) LiveUplink.SLOW_RATE else IN_RATE}"))
    }
}
