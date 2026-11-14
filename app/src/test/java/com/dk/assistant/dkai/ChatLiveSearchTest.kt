package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026: टाइप वाली चैट में भी असली खोज + लाइव कार्ड (कौन-कौन सी वेबसाइट) */
class ChatLiveSearchTest {
    private fun src(p: String): String =
        listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun sitesAddUpLiveWithStage() {
        DkaiSearchLive.cancel()
        DkaiSearchLive.stage("x"); assertFalse(DkaiSearchLive.snap(5).on)          // बिना खोज कुछ नहीं
        DkaiSearchLive.start("ipl news", 1_000)
        DkaiSearchLive.stage("Tavily पर खोज रहा है")
        assertEquals("Tavily पर खोज रहा है", DkaiSearchLive.snap(1_100).stage)
        DkaiSearchLive.add(listOf("https://www.ndtv.com/a"), 2_000)
        DkaiSearchLive.add(listOf("ndtv.com", "https://espn.in/x"), 2_100)
        val s = DkaiSearchLive.snap(2_100 + DkaiSearchLive.STEP_MS * 5)
        assertEquals(listOf("ndtv.com", "espn.in"), s.sites); assertTrue(s.found); assertEquals(2, s.visited)
        DkaiSearchLive.cancel()
        // डीप रिसर्च: कार्ड लंबे समय तक
        DkaiSearchLive.start("deep", 1, DkaiSearchLive.DEEP_MS)
        assertTrue(DkaiSearchLive.snap(DkaiSearchLive.MAX_MS + 10).on)
        assertFalse(DkaiSearchLive.snap(DkaiSearchLive.DEEP_MS + 10).on)
        DkaiSearchLive.cancel()
    }

    @Test fun typedChatWired() {
        val a = src("AgentRunner.kt")
        assertTrue(a.contains("com.dk.assistant.dkai.DkaiSearchLive.start(goal)"))
        assertTrue(a.contains("brain.search(goal, deep, thorough, asked = asked)"))
        val b = src("BrainManager.kt")
        assertTrue(b.contains("explicit = deep || thorough || asked"))
        assertTrue(b.contains("com.dk.assistant.dkai.DkaiSearchLive.add(found)"))
        val sc = src("dkai/DkaiScreen.kt")
        assertTrue(sc.contains("DkaiSearchLive.start(com.dk.assistant.research.DeepResearch.topic(t), maxMs = DkaiSearchLive.DEEP_MS)"))
        assertTrue(src("research/DeepResearchRun.kt").contains("sites(hits.map { it.url })"))
        assertTrue(src("dkai/DkaiSearchCard.kt").contains("if (s.stage.isNotBlank()) s.stage"))
    }
}
