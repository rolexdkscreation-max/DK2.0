package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoderQueueTest {
    @Test fun limitErrors_pauseQueue() {
        assertTrue(CoderTeam.isLimit("Google Gemini (gemini-3.1-flash-lite): free limit (quota) अभी खत्म है (429)"))
        assertTrue(CoderTeam.isLimit("कोटा खत्म"))
        assertFalse(CoderTeam.isLimit("कोडिंग का काम तय समय (10 मिनट) में पूरा नहीं हो पाया।"))
        assertFalse(CoderTeam.isLimit("GitHub token गलत (401)"))
    }
}
