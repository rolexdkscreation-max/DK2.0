package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoderFallbackTest {

    @Test fun orderChain_stickyFirst_restInOrder() {
        val l = listOf("Gemini", "Groq", "OpenRouter")
        assertEquals(l, CoderAi.orderChain(l, null))
        assertEquals(listOf("Groq", "Gemini", "OpenRouter"), CoderAi.orderChain(l, "Groq"))
        // purana sticky jo ab chain me nahi
        assertEquals(l, CoderAi.orderChain(l, "Custom"))
    }

    @Test fun quotaWait_onlyWhenAllAre429() {
        assertEquals(0, CoderAi.quotaWaitSec(emptyList()))
        assertEquals(0, CoderAi.quotaWaitSec(listOf(429 to 10, 401 to 0)))
        assertEquals(0, CoderAi.quotaWaitSec(listOf(429 to 0, -1 to 0)))      // network
        assertEquals(60, CoderAi.quotaWaitSec(listOf(429 to 0, 429 to 0)))
        assertEquals(20, CoderAi.quotaWaitSec(listOf(429 to 5)))              // kam se kam 20
        assertEquals(37, CoderAi.quotaWaitSec(listOf(429 to 37, 429 to 50)))
        assertEquals(90, CoderAi.quotaWaitSec(listOf(429 to 3600)))           // zyada se zyada 90
    }

    @Test fun reason_isShortHindi() {
        assertTrue(CoderAi.reason(AiException(429, "quota")).contains("429"))
        assertTrue(CoderAi.reason(java.io.IOException("timeout")).contains("नेटवर्क"))
    }

    @Test fun report_noProblems() {
        assertEquals("📋 रिपोर्ट\nकाम में कोई दिक्कत या एरर नहीं आया।", CoderReport.format(emptyList(), null))
    }

    @Test fun report_listsProblemAndFix_andDedupes() {
        CoderReport.begin()
        CoderReport.add("Gemini: limit (429)", "Groq पर स्विच")
        CoderReport.add("Gemini: limit (429)", "Groq पर स्विच")
        assertEquals(1, CoderReport.items().size)
        val t = CoderReport.text()
        assertTrue(t.contains("दिक्कत: Gemini: limit (429)"))
        assertTrue(t.contains("हल: Groq पर स्विच"))
        CoderReport.begin()
        assertTrue(CoderReport.items().isEmpty())
    }

    @Test fun report_unfixedError_givesAdvice() {
        val t = CoderReport.format(emptyList(), "Gemini की free limit (quota) अभी खत्म है (429)")
        assertTrue(t.contains("ठीक नहीं हो पाया"))
        assertTrue(t.contains("Groq"))
        assertFalse(t.contains("कोई दिक्कत या एरर नहीं"))
    }
}
