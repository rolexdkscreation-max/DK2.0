package com.dk.assistant

import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: Codespaces फ़्री घंटे (120 core-hours = 2-core पर 60 घंटे) - कितना इस्तेमाल / बाकी */
class CodespaceMeterTest {
    @Test fun parsesBillingSummary() {
        val j = """{"usageItems":[
            {"product":"codespaces","sku":"Codespaces compute 2-core","unitType":"Hours","grossQuantity":15.0},
            {"product":"codespaces","sku":"Codespaces storage","unitType":"GigabyteHours","grossQuantity":730.0},
            {"product":"actions","sku":"linux","unitType":"Minutes","grossQuantity":500}]}"""
        val u = CodespaceMeter.parse(j)!!
        assertEquals(30.0, u.coreHours, 0.001)
        assertEquals(15.0, u.hoursUsed, 0.001)
        assertEquals(25, u.usedPct)
        assertEquals(75, u.leftPct)
        assertEquals(1.0, u.storageGbMonth!!, 0.01)
        assertEquals(CodespaceMeter.Src.API, u.src)
        assertEquals("☁ Codespace: 15/60 घंटे • 25% इस्तेमाल • 75% बाकी", CodespaceMeter.line(u))
    }

    @Test fun noCodespacesItems() {
        assertNull(CodespaceMeter.parse("""{"usageItems":[{"product":"actions","sku":"linux","unitType":"Minutes","grossQuantity":5}]}"""))
        assertNull(CodespaceMeter.parse("not json"))
    }

    @Test fun cores() {
        assertEquals(4, CodespaceMeter.coresOf("Codespaces compute 4-core"))
        assertEquals(2, CodespaceMeter.coresOf("basicLinux32gb"))
        assertEquals(8, CodespaceMeter.coresOf("compute_8_core"))
    }

    @Test fun localEstimateAndMinutes() {
        val u = CodespaceMeter.local(60)   // 60 core-minutes = 1 core-hour = 30 मिनट (2-core)
        assertTrue(CodespaceMeter.line(u).contains("30 मिनट / 60 घंटे"))
        assertTrue(CodespaceMeter.line(u).contains("DK का अनुमान"))
        assertEquals(CodespaceMeter.Src.LOCAL, u.src)
    }

    @Test fun sessionCappedAndCounted() {
        assertEquals(20L, CodespaceMeter.session(1_000L, 1_000L + 10 * 60_000L, 2))
        assertEquals(CodespaceMeter.MAX_SESSION_MIN * 2, CodespaceMeter.session(1L, 1L + 24 * 3_600_000L, 2))
        assertEquals(0L, CodespaceMeter.session(0L, 5L, 2))
    }

    @Test fun skipSandboxWhenAlmostEmpty() {
        assertTrue(CodespaceMeter.allow(null))
        assertTrue(CodespaceMeter.allow(CodespaceMeter.Usage(100.0, null, src = CodespaceMeter.Src.API)))
        assertFalse(CodespaceMeter.allow(CodespaceMeter.Usage(110.0, null, src = CodespaceMeter.Src.API)))
        assertEquals(0, CodespaceMeter.Usage(200.0, null, src = CodespaceMeter.Src.API).leftPct)
    }

    @Test fun detailExplainsSaving() {
        val d = CodespaceMeter.detail(CodespaceMeter.local(120), "token में \"Plan: read\" इजाज़त नहीं")
        assertTrue(d.contains("बाकी"))
        assertTrue(d.contains("Plan: read"))
        assertTrue(d.contains("घंटे कैसे बचते हैं"))
    }
}
