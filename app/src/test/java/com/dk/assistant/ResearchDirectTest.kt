package com.dk.assistant

import com.dk.assistant.backend.BackendWire
import com.dk.assistant.research.DeepResearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक): "डीप रिसर्च में Google नहीं, सीधे Exa / Tavily (पहले ऐप की key, फिर Worker की) -
 * न हों तो सामान्य बात. हेलो / कैसे हो = सामान्य चैट, DK खुद समझे."
 */
class ResearchDirectTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n"))
        .first { it.exists() }.readText()

    @Test fun deepResearchNeverUsesGoogleSearch() {
        assertFalse(WebSearch.googleOk(research = true, hasGeminiKey = true))
        assertTrue(WebSearch.googleOk(research = false, hasGeminiKey = true))
        assertFalse(WebSearch.googleOk(research = false, hasGeminiKey = false))
        val b = src("BrainManager.kt")
        assertTrue(b.contains("WebSearch.googleOk(research, searchKeys().isNotEmpty())"))
        assertTrue(b.contains("AiRouter.search(ctx, q, deep = research)"))
        val run = src("research/DeepResearchRun.kt")
        assertFalse("डीप रिसर्च में Gemini की Google खोज नहीं", run.contains(".search(\n"))
        assertFalse(run.contains("Gemini की Google खोज से जवाब"))
    }

    @Test fun ownKeyFirstThenServer() {
        val run = src("research/DeepResearchRun.kt")
        assertTrue(run.contains("server = searchers.isEmpty() && com.dk.assistant.AiRouter.backendReady() && com.dk.assistant.AiRouter.serverAiOk()"))
        assertTrue(run.contains("BackendApi.search(c, DeepResearch.phrase(t, kind, q), deep = true, only = DeepResearch.serverId(t))"))
        assertTrue(run.contains("BackendApi.read(c, part.map { it.url })"))
        assertEquals(listOf(DeepResearch.Tool.TAVILY, DeepResearch.Tool.EXA, DeepResearch.Tool.LINKUP), DeepResearch.SERVER_TOOLS)
        assertEquals("tavily", DeepResearch.serverId(DeepResearch.Tool.TAVILY))
        assertEquals("exa", DeepResearch.serverId(DeepResearch.Tool.EXA))
    }

    @Test fun parsesServerResults() {
        val hits = DeepResearch.parseServer("""{"provider":"exa","results":[{"title":"DK","url":"https://dk.example/a","snippet":"पाठ"},{"title":"x","url":"javascript:1"}]}""",
            DeepResearch.Tool.EXA)
        assertEquals(1, hits.size)
        assertEquals("https://dk.example/a", hits[0].url)
        assertEquals(DeepResearch.Tool.EXA, hits[0].tool)
        val pages = BackendWire.readPages("""{"ok":true,"pages":[{"url":"https://a.example","text":"पूरा पाठ"},{"url":"https://b.example","text":" "}]}""")
        assertEquals(mapOf("https://a.example" to "पूरा पाठ"), pages)
    }

    @Test fun noSearchToolMeansNormalAnswer() {
        assertTrue(DeepResearch.SHALLOW_NOTE.contains("अपनी जानकारी से"))
        assertFalse(DeepResearch.SHALLOW_NOTE.contains("Google"))
    }

    @Test fun smallTalkIsNormalChat() {
        listOf("हेलो, क्या कर रहे हो, कैसे हो, और सब बताओ", "हेलो डीके", "कैसे हो भाई", "और बताओ अभी क्या चल रहा है?", "hello dk how are you", "नमस्ते जी")
            .forEach {
                assertTrue(it, SearchPolicy.smallTalk(it))
                assertFalse(it, SearchPolicy.needsSearch(it))
                assertTrue(it, WebSearch.skip(it))
            }
        listOf("हेलो, आज सोने का भाव क्या है", "आज की ताज़ा खबर क्या है", "भारत की राजधानी क्या है", "मोदी जी के बारे में डीप रिसर्च करो")
            .forEach { assertFalse(it, SearchPolicy.smallTalk(it)) }
        assertTrue(SearchPolicy.needsSearch("हेलो, आज सोने का भाव क्या है"))
        assertTrue(SearchPolicy.rules("[SEARCH]").contains("Greetings and small talk"))
    }
}
