package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 25 नवंबर 2026 (मालिक: "Hinglish भी आना चाहिए"): हिंदी -> रोमन अक्षर */
class TranslitTest {
    private fun dev(s: String) = s.any { it in '\u0900'..'\u097F' }

    @Test fun words() {
        assertEquals("asli", Translit.word("असली"))
        assertEquals("naam", Translit.word("नाम"))
        assertEquals("karna", Translit.word("करना"))
    }

    @Test fun loanWordsBecomeEnglish() {
        val h = HinglishText()
        val out = h.tr("ऐप की भाषा")
        assertTrue(out, out.startsWith("app "))
        assertFalse(out, dev(out))
        assertEquals("Hello DK", h.tr("Hello DK"))
        assertFalse(dev(h.tr("फ़ोन कंट्रोल • ऑटोमेशन")))
    }
}
