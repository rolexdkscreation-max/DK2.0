package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.69: हर इंजन का अपना भावना/स्टाइल - Edge में शब्द -> गति/पिच, Live में system instruction */
class EngineStyleTest {
    @Test fun edgeSlowSoftLowersRateAndPitch() {
        val (r, p) = EdgeStyle.adjust("धीमी, कोमल, भावुक आवाज़ में, प्यार से बोलो:")
        assertTrue("rate=$r", r < 0)
        assertTrue("pitch=$p", p < 0)
    }

    @Test fun edgeFastExcitedRaisesRate() {
        val (r, _) = EdgeStyle.adjust("तेज़ और जोश में बोलो")
        assertTrue("rate=$r", r > 0)
    }

    @Test fun edgeEmptyOrUnknownChangesNothing() {
        assertEquals(0 to 0, EdgeStyle.adjust(""))
        assertEquals(0 to 0, EdgeStyle.adjust("नमस्ते"))
        assertEquals(10 to -5, EdgeStyle.apply(10, -5, ""))
    }

    @Test fun edgeStaysInsideLimits() {
        val (r, p) = EdgeStyle.apply(50, -30, "तेज़ जल्दी जोश गहरी भारी")
        assertTrue(r in -50..50); assertTrue(p in -30..30)
        assertEquals(50, r); assertEquals(-30, p)
    }

    @Test fun liveStyleGoesIntoSystemInstruction() {
        assertEquals("", LiveNativeEngine.styleNote("  "))
        val n = LiveNativeEngine.styleNote(" धीमी, कोमल आवाज़ में ")
        assertTrue(n.contains("VOICE STYLE"))
        assertTrue(n.endsWith("धीमी, कोमल आवाज़ में"))
    }
}
