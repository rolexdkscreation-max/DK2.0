package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.71: बोलने की देरी के तीन समय */
class LatencyBoardTest {
    @Test fun threeTimes() {
        LatencyBoard.clear()
        val r = LatencyBoard.record("Edge TTS", 1000, 1600, 1900, 2050)!!
        assertEquals(600, r.firstPiece); assertEquals(900, r.firstVoice); assertEquals(1050, r.play)
        assertTrue(LatencyBoard.line(r).contains("पहला टुकड़ा 600 ms"))
        assertTrue(LatencyBoard.line(r).contains("बजना शुरू 1050 ms"))
    }

    @Test fun badTimesIgnored() {
        assertNull(LatencyBoard.record("x", 0, 1, 2, 3))
        assertNull(LatencyBoard.record("x", 5000, 1, 2, 3000))
        assertNull(LatencyBoard.record("x", 1000, 0, 0, 90_000))
        // बीच का समय पता नहीं = ?
        val r = LatencyBoard.record("x", 1000, 0, 0, 1500)!!
        assertTrue(LatencyBoard.line(r).contains("पहला टुकड़ा ?"))
    }

    @Test fun keepsLastAndAverages() {
        LatencyBoard.clear()
        repeat(20) { LatencyBoard.record("Gemini TTS", 1000, 1200, 1500, 1800) }
        LatencyBoard.record("Edge TTS", 1000, 1200, 1300, 1400)
        assertEquals(12, LatencyBoard.all().size)
        assertEquals(800L, LatencyBoard.averages()["Gemini TTS"])
        assertEquals(400L, LatencyBoard.averages()["Edge TTS"])
        assertTrue(LatencyBoard.text().contains("औसत"))
        LatencyBoard.clear()
        assertTrue(LatencyBoard.text().contains("अभी कोई बारी नहीं"))
    }

    @Test fun warmGap() {
        assertTrue(VoiceWarm.shouldWarm(100, 0))
        assertTrue(!VoiceWarm.shouldWarm(10_000, 1))
        assertTrue(VoiceWarm.shouldWarm(30_000, 1))
    }
}
