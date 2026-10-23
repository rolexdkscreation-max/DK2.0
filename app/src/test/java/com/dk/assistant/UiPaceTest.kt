package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPaceTest {
    @Test fun firstUpdateIsImmediate() { assertEquals(0L, UiPace.emitWait(0L, 10_000L)) }

    @Test fun burstIsCappedToAbout30PerSecond() {
        assertEquals(23L, UiPace.emitWait(1000L, 1010L))
        assertEquals(0L, UiPace.emitWait(1000L, 1040L))
        assertTrue(UiPace.emitWait(1000L, 1000L) <= UiPace.EMIT_GAP_MS)
    }

    @Test fun clockGoingBackNeverWaitsTooLong() { assertEquals(UiPace.EMIT_GAP_MS, UiPace.emitWait(5000L, 100L)) }

    @Test fun auroraSlowsDownWhileScrolling() {
        assertEquals(UiPace.AURORA_BUSY_MS, UiPace.auroraDelay(1000L, 1200L))
        assertEquals(UiPace.AURORA_MS, UiPace.auroraDelay(1300L, 1200L))
        assertTrue("background ~15fps se tez nahi", UiPace.AURORA_MS >= 60L)
    }
}
