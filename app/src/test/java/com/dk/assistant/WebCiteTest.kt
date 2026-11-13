package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): वेब खोज के जवाब और स्रोत - Tavily / Exa / Google एक ही साँचे में,
 * [n] निशान -> वाक्य के अंत में चिप, नीचे "स्रोत (N) · प्रदाता (X फ़ेल)" सूची, Google का बाइट वाला endIndex.
 */
class WebCiteTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun tavilyNormalized() {
        val raw = """{"results":[
            {"title":"Storm hits coast","url":"https://www.apnews.com/article/storm-1","content":"A  storm hit the coast today.","published_date":"Thu, 12 Nov 2026 10:00:00 GMT"},
            {"title":"dup","url":"https://www.apnews.com/article/storm-1","content":"x"},
            {"title":"","url":"ftp://bad"},
            {"title":"CNN live","url":"https://edition.cnn.com/live/x","content":"Live updates","published_date":null}]}"""
        val s = WebCite.normalizeResults(WebCite.TAVILY, raw)
        assertEquals(2, s.size)
        assertEquals(listOf(1, 2), s.map { it.id })
        assertEquals("apnews", s[0].site); assertEquals("cnn", s[1].site)
        assertEquals("2026-11-12", s[0].date); assertEquals("", s[1].date)
        assertEquals("A storm hit the coast today.", s[0].snippet)
        assertEquals("Tavily", s[0].provider)
    }

    @Test fun exaHighlightsThenTextThenSummary() {
        val raw = """{"results":[
            {"title":"ISRO launch","url":"https://www.isro.gov.in/n/1","publishedDate":"2026-11-13T05:31:56Z","highlights":["Launch done.","All good."],"text":"FULL"},
            {"title":"B","url":"https://b.com/x","text":"Only text"},
            {"title":"C","url":"https://c.com/y","summary":"Only summary"}]}"""
        val s = WebCite.normalizeResults(WebCite.EXA, raw)
        assertEquals("Launch done. … All good.", s[0].snippet)
        assertEquals("2026-11-13", s[0].date)
        assertEquals("Only text", s[1].snippet); assertEquals("Only summary", s[2].snippet)
        assertEquals("b", s[1].site)
    }

    @Test fun googleGroundingAndCustomSearch() {
        val meta = JSONObject().put("groundingChunks", JSONArray()
            .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/a").put("title", "ndtv.com")))
            .put(JSONObject().put("web", JSONObject().put("uri", "not-a-link")))
            .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/c").put("title", "bbc.co.uk"))))
        // पूरा Gemini जवाब (candidates[0].groundingMetadata) भी चले
        val full = JSONObject().put("candidates", JSONArray().put(JSONObject().put("groundingMetadata", meta)))
        val g = WebCite.normalizeResults(WebCite.GOOGLE, full)
        assertEquals(listOf(1, 3), g.map { it.id })            // id = chunk का क्रम + 1 (groundingChunkIndices वही)
        assertEquals(listOf("ndtv", "bbc"), g.map { it.site })
        val cse = WebCite.normalizeResults(WebCite.GOOGLE, """{"items":[{"title":"Reuters","link":"https://www.reuters.com/world/x","snippet":"World news"}]}""")
        assertEquals("reuters", cse.single().site); assertEquals("World news", cse.single().snippet)
        assertTrue(WebCite.normalizeResults(WebCite.TAVILY, "not json").isEmpty())
        assertTrue(WebCite.normalizeResults(WebCite.EXA, JSONObject()).isEmpty())
    }

    @Test fun maxSixResults() {
        val arr = JSONArray(); (1..9).forEach { arr.put(JSONObject().put("title", "t$it").put("url", "https://s$it.com/a")) }
        assertEquals(6, WebCite.normalizeResults(WebCite.TAVILY, JSONObject().put("results", arr)).size)
    }

    @Test fun homepageOnlyMeansNextProvider() {
        assertTrue(WebCite.homepage("https://www.cnn.com/")); assertTrue(WebCite.homepage("https://apnews.com"))
        assertTrue(WebCite.homepage("https://apnews.com/?ref=x"))
        assertFalse(WebCite.homepage("https://apnews.com/article/x"))
        val home = WebCite.normalizeResults(WebCite.TAVILY, """{"results":[{"title":"CNN","url":"https://cnn.com/"},{"title":"AP","url":"https://apnews.com"}]}""")
        assertTrue(WebCite.useless(home)); assertTrue(WebCite.useless(emptyList()))
        val mixed = home + WebCite.Src(3, "x", "https://cnn.com/2026/11/13/x", "cnn", "", "", "Tavily")
        assertFalse(WebCite.useless(mixed))
    }

    @Test fun queryRewriteAndNewsFilters() {
        assertTrue(WebCite.isNews("आज की ताज़ा खबर बताओ")); assertTrue(WebCite.isNews("latest news on ISRO"))
        assertFalse(WebCite.isNews("सोने का भाव"))
        val d = "Friday, 13 November 2026"
        assertEquals("India vs Australia score 13 November 2026", WebCite.cleanQuery("\"India vs Australia score 13 November 2026\"\nextra", "q", d))
        assertEquals("gold price India $d", WebCite.cleanQuery("Query: gold price India", "q", d))
        assertEquals("सोने का भाव ($d)", WebCite.cleanQuery(null, "सोने का भाव", d))
        assertTrue(WebCite.rewriteSystem(d, true).contains(d))
        val t = WebSearch.tavilyBody("x", news = true)
        assertEquals("news", t.getString("topic")); assertEquals("day", t.getString("time_range"))
        assertFalse(WebSearch.tavilyBody("x").has("topic"))
        val since = WebCite.sinceIso(1_763_000_000_000L)
        assertTrue(since.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
        val e = WebSearch.exaBody("x", since = since)
        assertEquals("news", e.getString("category")); assertEquals(since, e.getString("startPublishedDate"))
        assertFalse(WebSearch.exaBody("x").has("category"))
        assertTrue(WebSearch.exaBody("x", 6).getInt("numResults") in 5..6)
    }

    @Test fun answerPromptHasFourRulesAndNumberedSources() {
        val sys = WebCite.answerSystem("NOW", "DK", "")
        WebCite.RULES.forEach { assertTrue(it, sys.contains(it)) }
        assertTrue(sys.contains("सिर्फ़ इन्हीं स्रोतों से जवाब दो।"))
        assertTrue(sys.contains("ठोस खबर नहीं मिली"))
        val srcs = listOf(WebCite.Src(1, "Storm", "https://apnews.com/a", "apnews", "2026-11-13", "Storm hit.", "Tavily"),
            WebCite.Src(2, "CNN", "https://cnn.com/", "cnn", "", "", "Tavily"))
        val inp = WebCite.answerInput("तूफ़ान?", srcs, "storm news 13 November 2026")
        assertTrue(inp.contains("[1] Storm (apnews, 2026-11-13)")); assertTrue(inp.contains("[2] CNN (cnn) [HOMEPAGE ONLY]"))
        assertFalse(inp.contains("https://"))
        // AI न चले = स्रोत का ही वाक्य + उसका [n]; होमपेज वाला नहीं
        assertEquals("• Storm hit the coast today. [1]",
            WebCite.extractive(srcs.map { if (it.id == 1) it.copy(snippet = "Storm hit the coast today. More text follows here.") else it }))
    }

    @Test fun markersLayoutAndStreaming() {
        val t = "दिल्ली में बारिश हुई [1]। तापमान गिरा [2][3]. नया [2, 4]"
        assertEquals(listOf(1, 2, 3, 4), WebCite.ids(t))
        val l = WebCite.layout(t)
        assertEquals("दिल्ली में बारिश हुई। तापमान गिरा. नया", l.text)
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(2, 4)), l.at.map { it.second })
        assertEquals("दिल्ली में बारिश हुई।".length, l.at[0].first)        // चिप वाक्य-चिह्न के बाद
        assertEquals(l.text.length, l.at[2].first)
        assertEquals("दिल्ली में बारिश हुई। तापमान गिरा. नया", WebCite.strip(t))
        // स्ट्रीमिंग: अधूरा निशान नहीं दिखता
        assertEquals("बारिश हुई", WebCite.streamSafe("बारिश हुई [1"))
        assertEquals("बारिश हुई", WebCite.streamSafe("बारिश हुई ["))
        assertEquals("बारिश हुई। और", WebCite.streamSafe("बारिश हुई [1]। और [2, "))
        // बिना स्रोत वाली संख्या = नकली लिंक नहीं
        assertEquals("a [1]। b।", WebCite.sanitize("a [1][9]। b [7]।", setOf(1, 2)))
    }

    @Test fun googleEndIndexIsUtf8Bytes() {
        val s1 = "दिल्ली में आज बारिश होगी।"
        val s2 = " Hugging Face पर नया मॉडल आया।"
        val raw = s1 + s2
        val b1 = s1.toByteArray(Charsets.UTF_8).size
        assertNotEquals(s1.length, b1)                                   // हिंदी: बाइट != अक्षर
        val out = WebCite.insertMarkers(raw, listOf(b1 to listOf(0, 1), raw.toByteArray(Charsets.UTF_8).size to listOf(2)))
        assertEquals("$s1[1][2]$s2[3]", out)
        // बीच में कटा अक्षर = उस अक्षर के अंत पर, टूटा अक्षर नहीं
        assertEquals("द[1]िल्ली", WebCite.insertMarkers("दिल्ली", listOf(2 to listOf(0))))
        // groundingSupports पढ़ना + GroundCite से वही (endByte सहेजा)
        val meta = JSONObject()
            .put("groundingChunks", JSONArray()
                .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/r/a").put("title", "imd.gov.in")))
                .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/r/b").put("title", "huggingface.co"))))
            .put("groundingSupports", JSONArray()
                .put(JSONObject().put("segment", JSONObject().put("startIndex", 0).put("endIndex", b1)).put("groundingChunkIndices", JSONArray().put(0)))
                .put(JSONObject().put("segment", JSONObject().put("startIndex", b1 + 1).put("endIndex", raw.toByteArray(Charsets.UTF_8).size))
                    .put("groundingChunkIndices", JSONArray().put(1))))
            .put("searchEntryPoint", JSONObject().put("renderedContent", "<div>chip</div>"))
        assertEquals(2, WebCite.supportsOf(meta).size)
        val g = GroundCite.parse(meta, raw)!!
        assertEquals(b1, g.cites[0].endByte)
        assertEquals("$s1[1]$s2[2]", WebCite.markGoogle(raw, g))
        // trim किया जवाब भी (कच्चे जवाब का हिस्सा) बाइट से
        assertEquals("$s1[1]$s2[2]".trim(), WebCite.markGoogle(raw.trim(), g))
        // कार्ड: Google ग्राउंडिंग भी बाकी प्रदाताओं जैसा - चिप imd / huggingface, entry point साथ
        val card = WebCite.card(raw, g)!!
        assertEquals("Google", card.provider); assertTrue(card.marked); assertEquals("<div>chip</div>", card.entryHtml)
        assertEquals(listOf("imd", "huggingface"), WebCite.used(card).map { it.site })
        val back = GroundCite.fromJson(JSONObject(GroundCite.toJson(g).toString()))!!
        assertEquals(g, back)
    }

    @Test fun cardOnlyUsedSourcesAndProviderHeader() {
        val srcs = (1..4).map { WebCite.Src(it, "T$it", "https://s$it.com/a", "s$it", "", "", "Exa") }
        val g = WebCite.toG(srcs, WebCite.EXA, listOf("Tavily"))
        val card = WebCite.card("पहली बात [1]। तीसरी [3]।", g)!!
        assertEquals(listOf(1, 3), WebCite.used(card).map { it.id })       // सिर्फ़ इस्तेमाल हुए
        assertEquals("स्रोत (2)", WebCite.title(WebCite.used(card).size))
        assertEquals("Exa (Tavily फ़ेल)", WebCite.providerLine(card.provider, card.failed))
        assertEquals("Tavily", WebCite.providerLine("Tavily", emptyList()))
        // कोई निशान नहीं (DK लाइव) = दिए गए सारे स्रोत
        assertEquals(4, WebCite.used(WebCite.card("बोला गया जवाब", g)!!).size)
        // सहेजना / वापस पढ़ना
        val back = GroundCite.fromJson(JSONObject(GroundCite.toJson(g).toString()))!!
        assertEquals(g, back)
        assertEquals("Exa", back.provider); assertEquals(listOf("Tavily"), back.failed); assertEquals(srcs, back.srcs)
    }

    @Test fun noSearchNoChipsNoFakeLinks() {
        assertNull(WebCite.card("कोई जवाब [1]", null))
        assertNull(WebCite.card("x", WebCite.toG(emptyList(), "Tavily")))
        assertTrue(WebCite.normalizeResults("Other", "{}").isEmpty())
        assertEquals("Google", WebCite.nameOf(WebSearch.Provider.GEMINI))
        assertEquals("Tavily", WebCite.nameOf(WebSearch.Provider.TAVILY))
    }

    @Test fun liveMergeKeepsBothSourcesRenumbered() {
        val a = WebCite.toG(listOf(WebCite.Src(1, "A", "https://a.com/x", "a", "", "", "Tavily")), "Tavily", live = true)
        val b = WebCite.toG(listOf(WebCite.Src(1, "B", "https://b.com/y", "b", "", "", "Tavily")), "Tavily", live = true)
        val m = GroundCite.merge(a, b)!!
        assertEquals(listOf(1, 2), m.srcs.map { it.id }); assertEquals("Tavily", m.provider)
    }

    @Test fun wiredEverywhere() {
        val brain = src("BrainManager.kt")
        assertTrue(brain.contains("WebCite.normalizeResults(provider, raw)"))
        assertTrue(brain.contains("WebCite.useless(srcs)"))
        assertTrue(brain.contains("rewriteQuery(question, news)"))
        assertTrue(brain.contains("WebCite.answerSystem("))
        assertTrue(brain.contains("WebCite.markGoogle("))
        assertTrue(src("WebSearchNet.kt").contains("fun tavilyRaw(") && src("WebSearchNet.kt").contains("fun exaRaw("))
        assertTrue(src("GroundView.kt").contains("WebCite.layout("))
        assertTrue(src("GroundView.kt").contains("fun sourcesList("))
        assertTrue(src("DKState.kt").contains("WebCite.streamSafe(t0)"))
        assertTrue(src("AgentRunner.kt").contains("speaker.speak(WebCite.strip(r.text))"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("GroundView.footer("))
        assertTrue(src("VoiceActivity.kt").contains("WebCite.strip(DKState.lastReply)"))
        // पूरा url सूची में दोबारा नहीं छपता
        assertFalse(src("GroundView.kt").contains("text = url;"))
        // key ऐप की स्क्रीन के कोड में नहीं
        for (f in listOf("GroundView.kt", "SourcesView.kt", "WebCite.kt", "ChatActivity.kt", "dkai/DkaiScreen.kt"))
            assertFalse(f, Regex("tvly-|x-api-key|Bearer ").containsMatchIn(src(f)))
    }
}
