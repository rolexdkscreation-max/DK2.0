package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** DK कंप्यूटर: AI जवाब पढ़ना, इजाज़त वाले काम, search क्रम/जवाब, फ़ाइल नाम, समय, JS नतीजा */
class ComputerToolsTest {

    // ---------------------------------------------------------------- AI जवाब -> टूल
    @Test fun parseFencedJson() {
        val c = ComputerAgent.parse("```json\n{\"thought\":\"खोजता हूँ\",\"tool\":\"web_search\",\"args\":{\"query\":\"nashik weather\"}}\n```")
        assertNotNull(c)
        assertEquals("web_search", c!!.tool)
        assertEquals("nashik weather", c.args.optString("query"))
        assertEquals("खोजता हूँ", c.thought)
    }

    @Test fun parseTextAroundAndTopLevelArgs() {
        val c = ComputerAgent.parse("ठीक है: {\"tool\":\"read_url\",\"url\":\"https://example.com\"} बस")
        assertEquals("read_url", c!!.tool)
        assertEquals("https://example.com", c.args.optString("url"))
    }

    @Test fun parseAnswerWithoutTool() {
        val c = ComputerAgent.parse("{\"answer\":\"हो गया\"}")
        assertEquals("done", c!!.tool)
        assertEquals("हो गया", c.args.optString("answer"))
    }

    @Test fun parseGarbage() {
        assertNull(ComputerAgent.parse("नमस्ते, मैं मदद करूँगा"))
        assertNull(ComputerAgent.parse("{\"thought\":\"सिर्फ़ सोच\"}"))
        assertNull(ComputerAgent.parse("{ टूटा json"))
    }

    // ---------------------------------------------------------------- इजाज़त
    @Test fun sensitiveActions() {
        assertTrue(ComputerAgent.sensitive("WhatsApp पर राहुल को मैसेज भेजो"))
        assertTrue(ComputerAgent.sensitive("Call mom"))
        assertTrue(ComputerAgent.sensitive("PhonePe से 500 रुपये पेमेंट करो"))
        assertTrue(ComputerAgent.sensitive("गैलरी की पुरानी फोटो डिलीट करो"))
        assertFalse(ComputerAgent.sensitive("YouTube खोलकर lofi songs चलाओ"))
        assertFalse(ComputerAgent.sensitive("ब्राइटनेस बढ़ाओ"))
    }

    // ---------------------------------------------------------------- search
    @Test fun searchOrderPrefersChoiceThenOthers() {
        assertEquals(listOf("tavily", "brave", "gemini"), ComputerTools.searchOrder("auto", true, true, true))
        assertEquals(listOf("brave", "tavily"), ComputerTools.searchOrder("brave", true, true, false))
        assertEquals(listOf("gemini"), ComputerTools.searchOrder("tavily", false, false, true))
        assertTrue(ComputerTools.searchOrder("auto", false, false, false).isEmpty())
    }

    @Test fun parseTavilyResults() {
        val raw = """{"query":"q","results":[
            {"title":"IMD","url":"https://imd.gov.in","content":"बारिश","score":0.9},
            {"title":"no url","url":"","content":"x"}]}"""
        val h = ComputerTools.parseTavily(raw)
        assertEquals(1, h.size)
        assertEquals("IMD", h[0].title); assertEquals("https://imd.gov.in", h[0].url); assertEquals("बारिश", h[0].snippet)
    }

    @Test fun parseBraveResultsStripsTags() {
        val raw = """{"type":"search","web":{"results":[
            {"title":"<strong>Nashik</strong> news","url":"https://a.in/n","description":"आज <strong>नासिक</strong> में"}]}}"""
        val h = ComputerTools.parseBrave(raw)
        assertEquals(1, h.size)
        assertEquals("Nashik news", h[0].title); assertEquals("आज नासिक में", h[0].snippet)
        assertTrue(ComputerTools.parseBrave("{\"type\":\"search\"}").isEmpty())
    }

    // ---------------------------------------------------------------- वेब पेज
    @Test fun htmlToTextKeepsTextAndLinks() {
        val html = "<html><head><title>T &amp; Co</title><style>.a{}</style></head><body><script>evil()</script>" +
            "<h1>हेडिंग</h1><p>Hello&nbsp;world</p><a href=\"https://a.com/x\">लिंक</a><ul><li>एक</li></ul></body></html>"
        val t = ComputerTools.htmlToText(html)
        assertTrue(t, t.startsWith("TITLE: T & Co"))
        assertTrue(t, t.contains("## हेडिंग"))
        assertTrue(t, t.contains("Hello world"))
        assertTrue(t, t.contains("लिंक (https://a.com/x)"))
        assertTrue(t, t.contains("• एक"))
        assertFalse(t, t.contains("evil"))
    }

    // ---------------------------------------------------------------- फ़ाइलें
    @Test fun safeNameBlocksFolders() {
        assertEquals("passwd.md", ComputerTools.safeName("../../etc/passwd"))
        assertEquals("my report_.pdf", ComputerTools.safeName("my report?.pdf"))
        assertEquals("file.md", ComputerTools.safeName("   "))
        assertEquals("स्कूल.csv", ComputerTools.safeName("स्कूल.csv"))
        assertEquals("a.b", ComputerTools.safeName("a..b"))
    }

    @Test fun cleanMdForPdf() {
        assertEquals("• bold t (https://x.y) c", ComputerTools.cleanMd("- **bold** [t](https://x.y) `c`"))
    }

    // ---------------------------------------------------------------- समय
    @Test fun whenMsMinutesAndClock() {
        val now = Calendar.getInstance().apply { set(2026, 9, 22, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(now + 30 * 60_000L, ComputerTools.whenMs(now, 30, null))
        val at = ComputerTools.whenMs(now, null, "18:30")!!
        assertEquals(8 * 3600_000L + 30 * 60_000L, at - now)
        // निकल चुका समय = कल
        val tomorrow = ComputerTools.whenMs(now, null, "09:00")!!
        assertEquals(23 * 3600_000L, tomorrow - now)
        assertNull(ComputerTools.whenMs(now, null, "25:00"))
        assertNull(ComputerTools.whenMs(now, null, "शाम"))
        assertNull(ComputerTools.whenMs(now, 0, null))
    }

    // ---------------------------------------------------------------- JavaScript
    @Test fun jsResultReadsWebViewJson() {
        // evaluateJavascript string लौटाए तो JSON-quoted आता है
        val raw = "\"{\\\"r\\\":4,\\\"l\\\":[\\\"hi\\\"]}\""
        assertEquals("LOG:\nhi\nRESULT: 4", ComputerTools.jsResult(raw))
        assertEquals("ERROR: ReferenceError: x", ComputerTools.jsResult("\"{\\\"e\\\":\\\"ReferenceError: x\\\",\\\"l\\\":[]}\""))
        assertEquals("(कोई नतीजा नहीं)", ComputerTools.jsResult(null))
        assertTrue(ComputerTools.wrapJs("1+1").contains("eval(\"1+1\")"))
    }
}
