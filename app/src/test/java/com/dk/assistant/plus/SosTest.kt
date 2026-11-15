package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SosTest {
    @Test fun parse_cleans_dedupes_limits5() {
        assertEquals(listOf("+919876543210", "9876543211"), Sos.Rules.parse("+91 98765-43210, 98765 43211; 123"))
        assertEquals(5, Sos.Rules.parse((1..8).joinToString(",") { "98765432$it$it" }).size)
        assertEquals(listOf("9876543210"), Sos.Rules.parse("9876543210\n9876543210"))
    }

    @Test fun waNumber_addsIndiaCode() {
        assertEquals("919876543210", Sos.Rules.waNumber("98765 43210"))
        assertEquals("919876543210", Sos.Rules.waNumber("09876543210"))
        assertEquals("919876543210", Sos.Rules.waNumber("+91 9876543210"))
    }

    @Test fun message_hasLocationLink() {
        val m = Sos.Rules.message("दीपक", 19.2608, 76.7748)
        assertTrue(m.contains("दीपक")); assertTrue(m.contains("https://maps.google.com/?q=19.260800,76.774800"))
        assertTrue(Sos.Rules.message("", null, null).contains("लोकेशन नहीं"))
    }

    @Test fun callPlan_5triesEach_inOrder() {
        val p = Sos.Rules.callPlan(listOf("1", "2"))
        assertEquals(10, p.size); assertEquals("1" to 1, p.first()); assertEquals("2" to 5, p.last()); assertEquals("2" to 1, p[5])
    }

    // 15 नवंबर 2026 (ऑडिट): SMS/कॉल से पहले रद्द करने का समय
    @Test fun countdown_beforeSending() {
        assertTrue(Sos.Rules.COUNTDOWN_SEC in 5..15)
        val s = Sos.Rules.countdownSpeech()
        assertTrue(s, s.contains("${Sos.Rules.COUNTDOWN_SEC} सेकंड") && s.contains("SOS रोको"))
    }
}
