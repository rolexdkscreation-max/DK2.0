package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 18 नवंबर 2026: DK लाइव को याद कम भेजो - बाकी फ़ोन पर (recall_conversation), दोहराव नहीं */
class LiveMemoryBudgetTest {
    private val now = 10_000_000_000L

    @Test fun factsNewestKept_restStayOnPhone() {
        val facts = (1..60).map { "पक्की बात नंबर $it - " + "क".repeat(60) }
        val p = LiveMemoryBudget.pickFacts(facts)
        assertTrue(p.kept.isNotEmpty() && p.kept.size < 60)
        assertEquals(60, p.kept.size + p.left)
        assertEquals("सबसे नई बात ज़रूर", facts.last(), p.kept.last())
        assertEquals("prompt में पुराना क्रम", facts.takeLast(p.kept.size), p.kept)
        val b = LiveMemoryBudget.factsBlock(facts)
        assertTrue(b.length < LiveMemoryBudget.FACTS_CHARS + 400)
        assertTrue(b.contains("more saved facts") && b.contains("recall_conversation"))
    }

    @Test fun fewFacts_allSent_noNote() {
        val b = LiveMemoryBudget.factsBlock(listOf("मुझे चाय पसंद है", "मेरा भाई राहुल"))
        assertTrue(b.contains("चाय") && b.contains("राहुल") && !b.contains("more saved facts"))
        assertEquals("", LiveMemoryBudget.factsBlock(emptyList()))
    }

    @Test fun carriedTurnsNotSentTwice() {
        val chat = listOf(LiveCarry.Turn("u", "बहुत पुरानी", now - 50 * 60_000L), LiveCarry.Turn("u", "कल नासिक जाना है", now - 5 * 60_000L),
            LiveCarry.Turn("d", "ठीक है", now - 4 * 60_000L))
        val from = LiveMemoryBudget.carriedFrom(chat, now)
        assertEquals(now - 5 * 60_000L, from)
        assertEquals(0L, LiveMemoryBudget.carriedFrom(listOf(LiveCarry.Turn("u", "पुरानी", now - 50 * 60_000L)), now))
        val saved = listOf(LocalRecall.Turn(1, "u", "सुबह की बात", now - 3 * 3_600_000L), LocalRecall.Turn(2, "u", "कल नासिक जाना है", now - 5 * 60_000L))
        assertEquals(listOf(1L), LiveMemoryBudget.recentFor(saved, from).map { it.id })
        assertEquals(2, LiveMemoryBudget.recentFor(saved, 0L).size)
    }

    @Test fun wiredIntoLive() {
        val b = src("BrainManager.kt")
        assertTrue(b.contains("append(Memory.liveFactsBlock(ctx))"))
        assertFalse("Live की याद में सारी पक्की बातें अब नहीं", b.substringAfter("fun liveMemoryContext(carriedFrom: Long)").substringBefore("}.trim()").contains("Memory.factsBlock(ctx)"))
        assertTrue(src("GeminiLiveRace.kt").contains("liveMemoryContext(carriedFrom)"))
        assertTrue(src("GeminiLiveSetup.kt").contains("carriedFrom = LiveMemoryBudget.carriedFrom(turns, now)"))
        assertTrue(src("LocalMemory.kt").contains("maxChars = LiveMemoryBudget.RECENT_CHARS"))
    }

    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
}
