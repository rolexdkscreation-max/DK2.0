package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawSkipTest {
    @Test fun noChangeDraws() {
        val s = DrawSkip()
        repeat(5) { assertTrue(s.draw()) }
    }

    @Test fun changedFrameIsSkippedOnce() {
        val s = DrawSkip()
        s.changed()
        assertFalse("हिंदी नाप वाला frame नहीं दिखना चाहिए", s.draw())
        assertTrue("अगला (English नाप) frame दिखे", s.draw())
    }

    @Test fun neverFreezes() {
        val s = DrawSkip()
        var drawn = 0
        repeat(10) { s.changed(); if (s.draw()) drawn++ }
        assertTrue("हर बार text बदले तब भी screen draw हो", drawn >= 3)
    }

    @Test fun counterResetsAfterDraw() {
        val s = DrawSkip()
        s.changed(); assertFalse(s.draw())
        assertTrue(s.draw())
        s.changed(); assertFalse(s.draw())
        assertEquals(2, DrawSkip.MAX)
    }
}
