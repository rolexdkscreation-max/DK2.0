package com.dk.assistant.research

import com.dk.assistant.research.DeepResearch.Hit
import com.dk.assistant.research.DeepResearch.Tool
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): डीप रिसर्च - Exa + Tavily + Linkup एक साथ, Firecrawl से पूरा पेज, 15 पेज / 3 चक्कर तक */
class DeepResearchTest {
    @Test fun runsOnlyWhenAskedOrClearlyMultiSite() {
        assertTrue(DeepResearch.wanted("डीप रिसर्च करो: भारत में EV बाज़ार", auto = false))
        assertTrue(DeepResearch.wanted("deep research on solar panels in India", auto = false))
        assertTrue(DeepResearch.wanted("गहरी खोज करके बताओ मानसून 2026", auto = false))
        // साधारण सवाल = नहीं (क्रेडिट बचाओ)
        assertFalse(DeepResearch.wanted("नमस्ते", auto = true))
        assertFalse(DeepResearch.wanted("समय क्या है", auto = true))
        assertFalse(DeepResearch.wanted("iPhone 17 vs Pixel 11 कौन सा बेहतर, तुलना करो", auto = false))
        // अपने-आप वाला चालू हो तभी लंबी तुलना वाला सवाल
        assertTrue(DeepResearch.wanted("iPhone 17 vs Pixel 11 कौन सा बेहतर, तुलना करो", auto = true))
        assertFalse(DeepResearch.wanted("", auto = true))
    }

    @Test fun topicWithoutTriggerWords() {
        assertEquals("भारत में EV बाज़ार", DeepResearch.topic("डीप रिसर्च करो: भारत में EV बाज़ार"))
        assertEquals("solar panels in India", DeepResearch.topic("deep research on solar panels in India"))
    }

    @Test fun subQuestionsCoverFourAngles() {
        val s = DeepResearch.fallbackSubs("EV")
        assertEquals(listOf(DeepResearch.Kind.MAIN, DeepResearch.Kind.NEWS, DeepResearch.Kind.OPINION, DeepResearch.Kind.STATS), s.map { it.first })
        val p = DeepResearch.parsePlan("""```json
            {"subs":[{"kind":"main","q":"a"},{"kind":"news","q":"b"},{"kind":"opinion","q":"c"},{"kind":"stats","q":"d"},{"kind":"main","q":"e"},{"kind":"main","q":"f"}]}```""", "EV")
        assertEquals(5, p.size)   // ज़्यादा से ज़्यादा 5
        assertEquals(DeepResearch.Kind.NEWS, p[1].first)
        // AI गड़बड़ = अपने-आप वाले
        assertEquals(4, DeepResearch.parsePlan("कुछ नहीं", "EV").size)
    }

    @Test fun everyToolGetsDifferentWords() {
        val k = DeepResearch.Kind.NEWS
        val w = listOf(Tool.EXA, Tool.TAVILY, Tool.LINKUP).map { DeepResearch.phrase(it, k, "monsoon") }
        assertEquals(3, w.distinct().size)
        assertTrue(w.all { it.contains("monsoon") })
    }

    @Test fun duplicatesRemovedAndAgreementCounted() {
        assertEquals(DeepResearch.normalize("https://example.com/a"), DeepResearch.normalize("http://www.example.com/a/?utm_source=x#top"))
        val hits = listOf(
            Hit("A", "https://www.example.com/a?utm_source=x", "short", "", Tool.EXA),
            Hit("", "https://example.com/a/", "a much longer snippet text", "2026-11-01", Tool.TAVILY),
            Hit("B", "https://b.org/x", "b", "", Tool.LINKUP))
        val m = DeepResearch.merge(hits)
        assertEquals(2, m.size)
        assertEquals(setOf(Tool.EXA, Tool.TAVILY), m[0].tools)
        assertEquals("a much longer snippet text", m[0].snippet)
        assertEquals("2026-11-01", m[0].date)
    }

    @Test fun pickMaxFifteenTwoPerSite() {
        val links = (1..30).map { DeepResearch.Link("t$it", "https://site${it % 10}.com/p$it", "s", "2026", setOf(Tool.EXA)) }
        val p = DeepResearch.pick(links, emptySet(), budget = 99, nowYear = 2026)
        assertEquals(DeepResearch.MAX_PAGES, p.size)
        assertTrue(p.groupBy { DeepResearch.domain(it.url) }.values.all { it.size <= DeepResearch.PER_DOMAIN })
        assertEquals(3, DeepResearch.pick(links, emptySet(), budget = 3, nowYear = 2026).size)
        // पहले पढ़ा = दोबारा नहीं
        val seen = setOf(DeepResearch.normalize(links[0].url))
        assertFalse(DeepResearch.pick(links.take(1), seen, 10, 2026).isNotEmpty())
        // दो खोजियों ने दिया + ताज़ा = पहले
        val two = DeepResearch.Link("x", "https://z.com/1", "s", "2026", setOf(Tool.EXA, Tool.LINKUP))
        assertEquals(two, DeepResearch.pick(links.take(5) + two, emptySet(), 10, 2026).first())
        assertEquals(15, DeepResearch.MAX_PAGES); assertEquals(3, DeepResearch.MAX_ROUNDS); assertEquals(8, DeepResearch.MIN_PICK)
    }

    @Test fun parsesEveryTool() {
        assertEquals("u1", DeepResearch.parseExa("""{"results":[{"title":"T","url":"u1","text":"x","publishedDate":"2026-11-01"}]}""")[0].url)
        assertEquals("2026-11-02", DeepResearch.parseTavily("""{"results":[{"title":"T","url":"u2","content":"c","published_date":"2026-11-02"}]}""")[0].date)
        val l = DeepResearch.parseLinkup("""{"results":[{"type":"text","name":"N","url":"https://l.so/a","content":"c"}]}""")[0]
        assertEquals("N", l.title); assertEquals(Tool.LINKUP, l.tool)
        val f = DeepResearch.parseFirecrawl("""{"success":true,"data":{"markdown":"# Hi","metadata":{"title":"Page"}}}""")!!
        assertEquals("Page", f.first); assertEquals("# Hi", f.second)
        assertNull(DeepResearch.parseFirecrawl("""{"success":false}"""))
        assertEquals("full", DeepResearch.parseTavilyExtract("""{"results":[{"url":"https://a.com/x","raw_content":"full"}]}""")[DeepResearch.normalize("https://a.com/x")])
        assertEquals("t", DeepResearch.parseExaContents("""{"results":[{"url":"https://a.com/x","text":"t"}]}""")[DeepResearch.normalize("https://a.com/x")])
        assertTrue(DeepResearch.parseExa("not json").isEmpty())
    }

    @Test fun toolFailureNotesNeverShowKey() {
        assertTrue(DeepResearch.failNote(Tool.FIRECRAWL, 402).contains("क्रेडिट खत्म"))
        assertTrue(DeepResearch.failNote(Tool.TAVILY, 429).contains("429"))
        assertTrue(DeepResearch.checkMessage(Tool.LINKUP, 200).startsWith("✓"))
        assertTrue(DeepResearch.checkMessage(Tool.LINKUP, 401).startsWith("✗"))
    }

    @Test fun gapsAndReportFormat() {
        assertEquals(listOf("a", "b"), DeepResearch.parseGaps("""{"queries":["a","b","a"]}"""))
        assertTrue(DeepResearch.parseGaps("""{"queries":[]}""").isEmpty())
        val pages = listOf(DeepResearch.Page("P1", "https://a.com/1", "text one", "2026-11-01", true),
            DeepResearch.Page("", "https://b.com/2", "snip", "", false))
        val w = DeepResearch.writerPrompt("सवाल", pages, "14 नवंबर 2026", listOf("Linkup छोड़ा — क्रेडिट खत्म"))
        for (s in listOf("सीधा जवाब", "मुख्य बातें", "अलग-अलग राय", "स्रोत", "भरोसा: पक्का / शायद / अधूरा", "पक्का नहीं", "कम से कम 2 अलग वेबसाइट", "[1] P1", "[2] b.com", "सिर्फ़ छोटा अंश", "Linkup छोड़ा"))
            assertTrue(s, w.contains(s))
        val done = DeepResearch.finish("## जवाब\n**हाँ** [1]", pages)
        assertFalse(done.contains("##")); assertFalse(done.contains("**"))
        assertTrue(done.contains("[1] P1 (2026-11-01) — https://a.com/1"))
        assertTrue(DeepResearch.SHALLOW_NOTE.contains("गहरी खोज") && DeepResearch.SHALLOW_NOTE.contains("नहीं हो पाई"))
    }

    @Test fun wiredIntoChatAndSettings() {
        val sc = File("src/main/java/com/dk/assistant/dkai/DkaiScreen.kt").readText()
        assertTrue(sc.contains("DeepResearch.wanted(t, Prefs.deepAuto(c))"))
        assertTrue(sc.contains("DeepResearchRun("))
        val st = File("src/main/java/com/dk/assistant/SettingsBrain.kt").readText()
        assertTrue(st.contains("Prefs.LINKUP_KEY") && st.contains("Prefs.FIRECRAWL_KEY") && st.contains("Prefs.DEEP_AUTO"))
        assertTrue(com.dk.assistant.SecretNames.isSecret("search_key_linkup"))
        assertTrue(com.dk.assistant.SecretNames.isSecret("search_key_firecrawl"))
        // order: Firecrawl -> Tavily extract -> Exa contents
        val run = File("src/main/java/com/dk/assistant/research/DeepResearchRun.kt").readText()
        assertTrue(run.indexOf("DeepResearchNet.firecrawl(") < run.indexOf("DeepResearchNet.tavilyExtract(") &&
            run.indexOf("DeepResearchNet.tavilyExtract(") < run.indexOf("DeepResearchNet.exaContents("))
    }
}
