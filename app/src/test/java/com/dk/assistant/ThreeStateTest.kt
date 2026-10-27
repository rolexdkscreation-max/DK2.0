package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreeStateTest {
    @Test fun onlyThree() {
        val allowed = setOf(ThreeState.LISTENING, ThreeState.THINKING, ThreeState.SPEAKING, null)
        for (m in Mode.values()) for (live in listOf(true, false)) for (muted in listOf(true, false))
            assert(ThreeState.label(m, live, muted) in allowed)
    }

    @Test fun mapping() {
        assertEquals(ThreeState.THINKING, ThreeState.label(Mode.WORKING, true, false))
        assertEquals(ThreeState.THINKING, ThreeState.label(Mode.THINKING, false, false))
        assertEquals(ThreeState.SPEAKING, ThreeState.label(Mode.SPEAKING, true, false))
        assertEquals(ThreeState.LISTENING, ThreeState.label(Mode.LISTENING, false, false))
        assertEquals(ThreeState.LISTENING, ThreeState.label(Mode.IDLE, true, false))
        assertNull(ThreeState.label(Mode.IDLE, false, false))
        assertNull(ThreeState.label(Mode.IDLE, true, true))
        assertEquals(Mode.THINKING, ThreeState.look(Mode.WORKING))
    }
}
