package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {
    private fun sig(v: Int) = IntArray(512) { v }

    @Test fun firstFrameGoes() { assertTrue(FrameGate().shouldSend(1000, sig(10))) }

    @Test fun notFasterThanOnePerSecond() {
        val g = FrameGate(); g.sent(1000, sig(10))
        assertFalse(g.shouldSend(1500, sig(200)))
        assertTrue(g.shouldSend(2000, sig(200)))
    }

    @Test fun sameScreenOnlyEveryFewSeconds() {
        val g = FrameGate(); g.sent(1000, sig(10))
        assertFalse(g.shouldSend(2500, sig(10)))
        assertTrue(g.shouldSend(5000, sig(10)))
    }

    @Test fun smallNoiseIsNotAChange() {
        val g = FrameGate(); g.sent(1000, sig(100))
        assertFalse(g.shouldSend(2100, sig(105)))
    }

    @Test fun fitKeepsAspectAndEven() {
        assertEquals(460 to 1024, FrameGate.fit(1080, 2400, 1024))
        assertEquals(640 to 480, FrameGate.fit(640, 480, 1024))
        assertEquals(2 to 2, FrameGate.fit(0, 0, 1024))
    }
}
