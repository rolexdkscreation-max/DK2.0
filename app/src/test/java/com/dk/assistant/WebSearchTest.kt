package com.dk.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import com.dk.assistant.WebSearch.Provider as P

/** v3.93: Tavily -> Exa -> Gemini बैकअप क्रम, 1 घंटे की रोक, 10 मिनट cache, Check के संदेश */
class WebSearchTest {
    @Before fun clean() = WebSearch.resetAll()
    @After fun clean2() = WebSearch.resetAll()

    @Test fun orderSkipsBlankKeys() {
        assertEquals(listOf(P.TAVILY, P.EXA, P.GEMINI), WebSearch.order(true, true, true, 0))
        assertEquals(listOf(P.EXA, P.GEMINI), WebSearch.order(false, true, true, 0))
        assertEquals(listOf(P.GEMINI), WebSearch.order(false, false, true, 0))
        assertEquals(emptyList<WebSearch.Provider>(), WebSearch.order(false, false, false, 0))
    }

    @Test fun failedServiceIsOffForOneHourThenRetried() {
        val now = 1_000_000L
        WebSearch.markDown(P.TAVILY, SearchGuard.Fail.QUOTA, now)
        assertEquals(listOf(P.EXA, P.GEMINI), WebSearch.order(true, true, true, now + 59 * 60_000))
        assertEquals(SearchGuard.Fail.QUOTA, WebSearch.downReason(now + 1))
        assertEquals(listOf(P.TAVILY, P.EXA, P.GEMINI), WebSearch.order(true, true, true, now + 60 * 60_000 + 1))
        WebSearch.markDown(P.EXA, now = now); WebSearch.clearDown(P.EXA)
        assertFalse(WebSearch.isDown(P.EXA, now))
    }

    @Test fun phoneInternetProblemDoesNotSwitchServiceOff() {
        assertFalse(WebSearch.coolsDown(P.TAVILY, SearchGuard.Fail.NETWORK))
        assertFalse(WebSearch.coolsDown(P.EXA, SearchGuard.Fail.TIMEOUT))
        assertTrue(WebSearch.coolsDown(P.TAVILY, SearchGuard.Fail.OTHER))
        assertTrue(WebSearch.coolsDown(P.EXA, SearchGuard.Fail.QUOTA))
        assertTrue(WebSearch.coolsDown(P.GEMINI, SearchGuard.Fail.QUOTA))
        assertFalse(WebSearch.coolsDown(P.GEMINI, SearchGuard.Fail.OTHER))
    }

    @Test fun cacheTenMinutes() {
        val r = SearchResult("आज नासिक में धूप", listOf("IMD" to "https://mausam.imd.gov.in"))
        WebSearch.put("आज नासिक का मौसम?", r, 0)
        assertEquals(r, WebSearch.cached("आज  नासिक का मौसम", 9 * 60_000))
        assertNull(WebSearch.cached("आज नासिक का मौसम", 10 * 60_000 + 1))
        WebSearch.put("x", SearchResult("", emptyList()), 0)
        assertNull(WebSearch.cached("x", 1))
    }

    @Test fun sameQuestionAtSameTimeSearchesOnce() = runBlocking {
        val n = AtomicInteger()
        val block: suspend () -> SearchResult = { n.incrementAndGet(); delay(50); SearchResult("ok", emptyList()) }
        val a = async { WebSearch.once("आज की ख़बर", block) }
        val b = async { WebSearch.once("आज की ख़बर?", block) }
        assertEquals("ok", a.await().text); assertEquals("ok", b.await().text)
        assertEquals(1, n.get())
    }

    @Test fun tavilyRequestIsSmall() {
        val b = WebSearch.tavilyBody("आज का सोने का भाव")
        assertTrue(b.getInt("max_results") in 5..8)
        assertEquals("basic", b.getString("search_depth"))
        assertFalse(b.getBoolean("include_raw_content"))
        assertFalse(b.toString().contains("tvly-"))
    }

    @Test fun exaAsksOnlySummaryNotFullPage() {
        val b = WebSearch.exaBody("latest ISRO news")
        assertTrue(b.getInt("numResults") in 5..8)
        val c = b.getJSONObject("contents")
        assertTrue(c.has("summary"))
        assertFalse(c.has("text")); assertFalse(c.has("highlights"))
    }

    @Test fun parseTavilyAndExa() {
        val t = WebSearch.parseTavily("""{"answer":"Gold is 7000","results":[{"title":"Gold rate","url":"https://a.com/x","content":"Gold 7000 today","published_date":null},{"title":"","url":""}]}""")
        assertEquals("Gold is 7000", t.answer); assertEquals(1, t.hits.size); assertEquals("", t.hits[0].date)
        assertTrue(WebSearch.report(t).contains("SHORT ANSWER: Gold is 7000"))
        assertEquals(listOf("Gold rate" to "https://a.com/x"), WebSearch.sources(t))
        val e = WebSearch.parseExa("""{"results":[{"title":"ISRO","url":"https://isro.gov.in/n","summary":"Launch done","publishedDate":"2026-10-27T10:00:00Z","text":"FULL"}]}""")
        assertEquals("Launch done", e.hits[0].text); assertEquals("2026-10-27", e.hits[0].date)
        assertEquals("Launch done", WebSearch.plainAnswer(e))
    }

    @Test fun viaLabelOnlyForTaggedSources() {
        val s = listOf("ISRO" to "https://isro.gov.in/n")
        assertNull(WebSearch.viaOf(s))
        WebSearch.tag(s, P.EXA)
        assertEquals("Exa", WebSearch.viaOf(s))
        assertNull(WebSearch.viaOf(listOf("Wikipedia" to "https://hi.wikipedia.org/x")))
    }

    @Test fun checkShowsRealReasonAndCode() {
        assertTrue(WebSearch.checkMessage(P.TAVILY, 200).startsWith("✓ चालू है"))
        WebSearch.checkMessage(P.TAVILY, 401).let { assertTrue(it, it.contains("Key गलत है") && it.contains("401")) }
        WebSearch.checkMessage(P.EXA, 403).let { assertTrue(it, it.contains("Key गलत है") && it.contains("403")) }
        WebSearch.checkMessage(P.TAVILY, 432).let { assertTrue(it, it.contains("limit खत्म है") && it.contains("432")) }
        WebSearch.checkMessage(P.EXA, 402).let { assertTrue(it, it.contains("limit खत्म है")) }
        WebSearch.checkMessage(P.GEMINI, 429).let { assertTrue(it, it.contains("limit खत्म है") && it.contains("429")) }
        WebSearch.checkMessage(P.GEMINI, 400, "API key not valid. Please pass a valid API key.").let { assertTrue(it, it.contains("Key गलत है")) }
        WebSearch.checkMessage(P.GEMINI, 0, "Unable to resolve host").let { assertTrue(it, it.contains("इंटरनेट की दिक्कत है")) }
        WebSearch.checkMessage(P.TAVILY, 0, "timeout").let { assertTrue(it, it.contains("इंटरनेट की दिक्कत है")) }
        // हर गड़बड़ी "limit" नहीं
        for (code in listOf(400, 401, 403, 404, 500, 503, 0)) {
            assertFalse("$code", WebSearch.checkMessage(P.GEMINI, code, "server error").contains("limit"))
        }
        WebSearch.checkMessage(P.EXA, 500, "Internal").let { assertTrue(it, it.contains("गड़बड़ी") && it.contains("500")) }
    }

    @Test fun tavilyUsageFullMeansLimit() {
        assertTrue(WebSearch.tavilyUsageFull("""{"key":{"usage":1000,"limit":1000},"account":{"plan_usage":10,"plan_limit":1000}}"""))
        assertTrue(WebSearch.tavilyUsageFull("""{"key":{"usage":5,"limit":null},"account":{"plan_usage":1000,"plan_limit":1000,"paygo_usage":0,"paygo_limit":0}}"""))
        assertFalse(WebSearch.tavilyUsageFull("""{"key":{"usage":5,"limit":null},"account":{"plan_usage":10,"plan_limit":1000}}"""))
        assertFalse(WebSearch.tavilyUsageFull("not json"))
    }

    @Test fun keysNeverInErrorText() {
        val t = WebSearch.errorText("""{"detail":{"error":"Unauthorized: missing or invalid API key tvly-abcdefgh12345"}}""")
        assertFalse(t, t.contains("tvly-abcdefgh12345")); assertTrue(t.contains("Unauthorized"))
        assertEquals("API key not valid.", WebSearch.errorText("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}"""))
        assertEquals("x", WebSearch.errorText("""{"requestId":"1","error":"x"}"""))
        assertFalse(WebSearch.redact("key AIzaSyABCDEFGHIJKLMNOP").contains("AIzaSyABC"))
    }

    @Test fun searchOnlyForFreshQuestions() {
        listOf("python में loop कैसे लिखें", "कहानी सुनाओ", "12*8", "प्रकाश संश्लेषण समझाओ").forEach { assertTrue(it, WebSearch.skip(it)) }
        listOf("आज का मौसम समझाओ", "आज की ख़बर", "सोने का भाव", "भारत की राजधानी क्या है").forEach { assertFalse(it, WebSearch.skip(it)) }
    }

    @Test fun allFailedTellsUserAndAnswersFromOwnKnowledge() {
        val r = SearchGuard.failResult("आज की ख़बर", SearchGuard.Fail.QUOTA)
        assertTrue(r.contains(WebSearch.ALL_FAILED_LINE)); assertTrue(r.contains("OWN knowledge"))
        assertTrue(LivePromise.searchFailNote("x").contains(WebSearch.ALL_FAILED_LINE))
    }
}
