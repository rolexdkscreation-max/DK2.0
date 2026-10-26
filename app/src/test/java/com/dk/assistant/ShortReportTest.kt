package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** यूज़र की छोटी रिपोर्ट: सिर्फ़ हुआ/नहीं, API चली/नहीं, वजह, हल - API/मॉडल बदलने की बातें कभी नहीं */
class ShortReportTest {

    private val internals = listOf("स्विच", "groq", "gemini", "openrouter", "llama", "दूसरे मॉडल", "आज़माए")

    private fun noInternals(t: String) = internals.forEach { assertFalse("'$it' नहीं दिखना चाहिए:\n$t", t.lowercase().contains(it)) }

    @Test fun limit_saysApiFailed_newKey_thenContinue() {
        val t = ShortReport.text(false, "Groq llama-3.3-70b: 429 (आज़माए: gemini-2.5-flash: 429, openrouter qwen: 429)")
        assertTrue(t, t.startsWith("❌ काम नहीं हुआ"))
        assertTrue(t, t.contains("API: ❌ नहीं चली"))
        assertTrue(t, t.contains("वजह: API key की limit खत्म हो गई"))
        assertTrue(t, t.contains("नई API key") && t.contains("\"आगे करो\""))
        noInternals(t)
        assertEquals(4, t.lines().size)                       // बस 4 लाइन
    }

    @Test fun wrongKey_401() {
        val w = ShortReport.why("Gemini: 401 API key not valid")
        assertEquals(ShortReport.Cause.KEY, w.cause)
        assertTrue(w.apiFault)
        assertTrue(w.fix.contains("सही API key"))
    }

    @Test fun githubToken_isNotApiKey() {
        val w = ShortReport.why("GitHub 403: Resource not accessible by integration (token में इजाज़त नहीं)")
        assertEquals(ShortReport.Cause.TOKEN, w.cause)
        assertFalse(w.apiFault)
        assertTrue(w.fix.contains("GitHub token"))
    }

    @Test fun network_isApiSide() {
        val w = ShortReport.why("java.net.SocketTimeoutException: timeout")
        assertEquals(ShortReport.Cause.NETWORK, w.cause)
        assertTrue(w.apiFault)
    }

    @Test fun codeError_showsFileAndLine_apiWorked() {
        val t = ShortReport.text(false, "बिल्ड फ़ेल\ne: file:///home/runner/work/app/src/main/java/com/dk/assistant/Foo.kt:42:7 Unresolved reference: bar")
        assertTrue(t, t.contains("API: ✅ ठीक चली"))
        assertTrue(t, t.contains("Foo.kt:42"))
        assertTrue(t, t.contains("Unresolved reference: bar"))
        assertTrue(t, t.contains("\"आगे करो\""))
    }

    @Test fun stoppedAndTimeout_tellToContinue() {
        assertEquals(ShortReport.Cause.STOPPED, ShortReport.why("काम रोक दिया").cause)
        assertEquals(ShortReport.Cause.TIMEOUT, ShortReport.why("तय समय (30 मिनट) में पूरा नहीं हुआ").cause)
        val t = ShortReport.text(null, "काम रोक दिया")
        assertTrue(t, t.startsWith("⏳ काम पूरा नहीं हुआ") && t.contains("वहीं से"))
    }

    @Test fun done_githubMode_prAndMergeHint() {
        val t = ShortReport.text(true, "", "https://github.com/u/r/pull/9")
        assertTrue(t, t.startsWith("✅ काम हो गया"))
        assertTrue(t, t.contains("API: ✅ ठीक चली"))
        assertTrue(t, t.contains("PR: https://github.com/u/r/pull/9"))
        assertTrue(t, t.contains("merge"))
        assertFalse(t, t.contains("वजह"))
    }

    @Test fun done_normalMode_codeBelow() {
        val t = ShortReport.text(true, github = false)
        assertTrue(t, t.contains("कोड नीचे") && !t.contains("PR"))
    }

    @Test fun firstLine_dropsModelNames() {
        val s = ShortReport.firstLine("openrouter/qwen-coder: कुछ अजीब हुआ (आज़माए: groq: 500)\nदूसरी लाइन")
        assertTrue(s, s.contains("कुछ अजीब हुआ"))
        assertFalse(s, s.contains("qwen") || s.contains("groq") || s.contains("दूसरी"))
    }
}
