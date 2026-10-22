package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** कोडिंग एजेंट: छोटा context, 413/429 संभालना, बार-बार की चेतावनी पर रुकना, token की साफ़ सलाह */
class CoderContextTest {

    private fun history(steps: Int, big: Int = 5000): List<Pair<String, String>> = buildList {
        add("user" to "Repository files (3):\na.kt\nb.kt\nc.kt")
        add("model" to "{\"tool\":\"list\",\"path\":\"\"}")
        add("user" to "TASK FROM USER: बटन का रंग लाल करो")
        repeat(steps) { i ->
            add("model" to "{\"tool\":\"read\",\"path\":\"f$i.kt\"}")
            add("user" to "RESULT (read f$i.kt):\n" + "x".repeat(big))
        }
    }

    @Test fun compact_keepsTaskAlternationAndBudget() {
        val h = history(30)
        val out = CoderContext.compact(h, 20_000, 6, "STAGED (not pushed yet): a.kt")
        assertTrue(CoderContext.total(out) <= 20_000)
        assertTrue(out.size < h.size)
        // पहला संदेश (repo list) वही, user/model बारी-बारी
        assertTrue(out[0].second.startsWith("Repository files"))
        for (i in 1 until out.size) assertTrue("roles at $i", out[i].first != out[i - 1].first)
        assertEquals("user", out.last().first)
        val all = out.joinToString("\n") { it.second }
        assertTrue(all.contains("CURRENT TASK FROM USER: बटन का रंग लाल करो"))
        assertTrue(all.contains("STAGED (not pushed yet): a.kt"))
        assertTrue(all.contains("EARLIER STEPS"))
    }

    @Test fun compact_smallHistoryUnchanged() {
        val h = history(1, 100)
        assertEquals(h, CoderContext.compact(h, 60_000, 8))
    }

    @Test fun chunk_numbersLinesAndPointsToNext() {
        val text = (1..2000).joinToString("\n") { "line $it with some code" }
        val c = CoderContext.chunk(text, 1, 3000, "a/Big.kt")
        assertTrue(c.length < 3300)
        assertTrue(c.contains("1| line 1 with"))
        assertTrue(c.contains("\"path\":\"a/Big.kt\",\"start\":"))
        val last = CoderContext.chunk(text, 1990, 3000, "a/Big.kt")
        assertTrue(last.contains("end of file"))
        assertTrue(last.contains("2000| line 2000"))
    }

    @Test fun tooLarge_and_tokenLimit() {
        assertTrue(CoderContext.tooLarge(413, ""))
        assertTrue(CoderContext.tooLarge(400, "This model's maximum context length is 8192 tokens"))
        assertFalse(CoderContext.tooLarge(400, "invalid json"))
        assertFalse(CoderContext.tooLarge(429, "too large"))
        assertEquals(6000 to 21034, CoderContext.tokenLimit("Request too large for model on tokens per minute (TPM): Limit 6000, Requested 21034"))
        assertNull(CoderContext.tokenLimit("no numbers"))
    }

    @Test fun shrink_alwaysSmaller() {
        val (c, t) = CoderContext.shrink(80_000, 16_000, "Limit 6000, Requested 21034")
        assertTrue(c < 80_000)
        assertTrue(t <= 1500)
        val (c2, t2) = CoderContext.shrink(80_000, 16_000, "Payload Too Large")
        assertEquals(40_000, c2); assertEquals(8000, t2)
        val (c3, _) = CoderContext.shrink(CoderContext.MIN_BUDGET, 1024, "413")
        assertTrue(c3 < CoderContext.MIN_BUDGET)
    }

    @Test fun backoff_grows_and_caps() {
        assertEquals(0L, CoderContext.backoffMs(0))
        assertEquals(2000L, CoderContext.backoffMs(1))
        assertEquals(4000L, CoderContext.backoffMs(2))
        assertEquals(8000L, CoderContext.backoffMs(3))
        assertEquals(8000L, CoderContext.backoffMs(9))
    }

    @Test fun warnGuard_stopsOnSecondSameWarning() {
        val g = WarnGuard(2)
        assertFalse(g.hit("ERROR: FIND text not found in a.kt (line 12)"))
        assertTrue(g.hit("ERROR: FIND text not found in a.kt (line 40)"))
        val g2 = WarnGuard(2)
        assertFalse(g2.hit("ERROR: one"))
        assertFalse(g2.hit("NOT FOUND: b.kt"))
        g2.ok()
        assertFalse(g2.hit("NOT FOUND: b.kt"))
    }

    @Test fun permissionAdvice_isClearHindi() {
        val fine = GitHub.permissionAdvice(403, "contents=write", null, "Resource not accessible by personal access token", "o/r")
        assertTrue(fine.contains("Fine-grained tokens"))
        assertTrue(fine.contains("Contents: Read and write"))
        val missing = GitHub.permissionAdvice(404, null, null, "", "o/r")
        assertTrue(missing.contains("Repository access"))
        assertTrue(missing.contains("o/r"))
        val classic = GitHub.permissionAdvice(403, null, "read:user", "", "o/r")
        assertTrue(classic.contains("classic"))
        assertTrue(classic.contains("\"repo\""))
    }

    @Test fun allLimits_tellsUserAndStops() {
        val m = CoderAi.allLimitsMessage(120, "GEMINI: 429, GROQ: 429", noFallback = false)
        assertTrue(m.contains("लगातार कोशिश नहीं"))
        assertTrue(m.contains("2 मिनट"))
        assertTrue(CoderTeam.isLimit(m))
        assertTrue(CoderAi.allLimitsMessage(30, "", true).contains("Groq"))
    }

    @Test fun finalReport_threeSectionsNoDuplicate() {
        val err = "सारी API keys की limit खत्म (429)\nआगे: 2 मिनट बाद फिर भेजें"
        val r = CoderReport.final(false, err, emptyList())
        assertTrue(r.contains("✔ क्या हुआ"))
        assertTrue(r.contains("✘ क्या नहीं हुआ"))
        assertTrue(r.contains("➜ आगे आपको क्या करना है:\n• 2 मिनट बाद फिर भेजें"))
        assertEquals(1, Regex("limit खत्म").findAll(r).count())
        val ok = CoderReport.final(true, "बटन लाल किया", listOf(CoderReport.Item("Gemini: 429", "Groq पर स्विच")))
        assertTrue(ok.contains("Groq पर स्विच"))
        assertTrue(ok.contains("कुछ बाकी नहीं"))
    }

    @Test fun importantPaths_codeFirstNoImages() {
        val p = CodingAgent.importantPaths(listOf("logo.png", "README.md", "app/A.kt", "docs/x.pdf"), 3)
        assertEquals(listOf("README.md", "app/A.kt", "docs/x.pdf"), p)
    }
}
