package com.dk.assistant

import org.junit.Assert.*
import org.junit.Test

class CoderSpeedTest {
    private val small = listOf("val a = 1\nval b = 2" to "val a = 1\nval b = 3")

    @Test fun smallCleanReplaceSkipsAiReview() {
        assertTrue(CoderSpeed.quickPass(small, fullFile = false, fileExists = true, unsureNames = 0, flips = 0, droppedGuards = 0, attempt = 1))
    }

    @Test fun riskyEditsStillGetAiReview() {
        assertFalse(CoderSpeed.quickPass(small, true, true, 0, 0, 0, 1))      // पूरी फ़ाइल
        assertFalse(CoderSpeed.quickPass(small, false, false, 0, 0, 0, 1))    // नई फ़ाइल
        assertFalse(CoderSpeed.quickPass(small, false, true, 1, 0, 0, 1))     // नाम पक्का नहीं
        assertFalse(CoderSpeed.quickPass(small, false, true, 0, 1, 0, 1))     // शर्त उल्टी
        assertFalse(CoderSpeed.quickPass(small, false, true, 0, 0, 1, 1))     // सुरक्षा हटी
        assertFalse(CoderSpeed.quickPass(small, false, true, 0, 0, 0, 2))     // दूसरी कोशिश
        assertFalse(CoderSpeed.quickPass(emptyList(), false, true, 0, 0, 0, 1))
        assertFalse(CoderSpeed.quickPass(List(3) { small[0] }, false, true, 0, 0, 0, 1))
        val big = (1..20).joinToString("\n") { "l$it" } to (1..20).joinToString("\n") { "m$it" }
        assertFalse(CoderSpeed.quickPass(listOf(big), false, true, 0, 0, 0, 1))
        assertFalse(CoderSpeed.quickPass(listOf("x" to "x"), false, true, 0, 0, 0, 1)) // कुछ बदला ही नहीं
    }

    @Test fun changedCountsOnlyRealLines() {
        assertEquals(2, CoderSpeed.changed("a\nb\nc", "a\nB\nc"))
        assertEquals(1, CoderSpeed.changed("a", "a\nnew"))
        assertEquals(0, CoderSpeed.changed("  a\n", "a"))
    }

    @Test fun briefAiAtMostEveryTenMinutes() {
        assertTrue(CoderSpeed.briefByAi(true, 1000, 2000))
        assertTrue(CoderSpeed.briefByAi(false, 0, 2000))
        assertFalse(CoderSpeed.briefByAi(false, 1_000, 1_000 + 9 * 60_000L))
        assertTrue(CoderSpeed.briefByAi(false, 1_000, 1_000 + CoderSpeed.BRIEF_AI_EVERY_MS))
    }

    @Test fun clockReportsWhereTimeWent() {
        var t = 0L
        val c = CoderSpeed.Clock { t }
        val a = c.start(); t += 90_000; assertEquals(90_000, c.done(a))
        val b = c.start(); t += 30_000; c.done(b)
        c.reviewSkipped(); c.briefSkipped(); t += 60_000
        assertEquals(2, c.calls)
        val r = c.report()
        assertTrue(r, r.contains("कुल 3 मिनट 0s"))
        assertTrue(r, r.contains("AI के जवाब 2 मिनट 0s (2 कॉल, सबसे लंबी 90s)"))
        assertTrue(r, r.contains("इंतज़ार 1 मिनट 0s"))
        assertTrue(r, r.contains("बचाई कॉल: 2"))
    }

    @Test fun slowNoteOnlyForSlowCalls() {
        assertNull(CoderSpeed.slowNote("कोडर", 5_000))
        assertTrue(CoderSpeed.slowNote("कोडर", 75_000)!!.contains("75s"))
    }
}
