package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** 18 नवंबर 2026: याद की खोज मतलब से - एक-मतलब वाले शब्द + देवनागरी/Hinglish एक ही नाम (सब फ़ोन पर) */
class MemoryMeaningTest {
    private val z = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(z).toInstant().toEpochMilli()
    private val now = at(18, 20)

    @Test fun sameNameBothScripts() {
        assertEquals(MemoryMeaning.skeleton("राहुल"), MemoryMeaning.skeleton("rahul"))
        assertEquals(MemoryMeaning.skeleton("नाशिक"), MemoryMeaning.skeleton("nashik"))
        assertEquals(MemoryMeaning.skeleton("पंकज"), MemoryMeaning.skeleton("pankaj"))
        assertEquals(MemoryMeaning.skeleton("संजय"), MemoryMeaning.skeleton("sanjay"))
        assertNull("बहुत छोटा ढाँचा = भरोसा नहीं", MemoryMeaning.skeleton("पुणे"))
        assertTrue(MemoryMeaning.related("राहुल", MemoryMeaning.tokens("rahul ka phone aaya tha")))
        assertFalse("एक ही लिपि में ढाँचे से मिलान नहीं", MemoryMeaning.related("रमेश", MemoryMeaning.tokens("रामेश्वर मंदिर")))
    }

    @Test fun sameMeaningWords() {
        assertTrue(MemoryMeaning.synonyms("गाड़ी").contains(LocalRecall.norm("बाइक")))
        assertTrue(MemoryMeaning.related("गाड़ी", MemoryMeaning.tokens("मेरी बाइक का नंबर MH15 है")))
        assertTrue(MemoryMeaning.related("wife", MemoryMeaning.tokens("मेरी पत्नी का जन्मदिन 5 मार्च को है")))
        assertFalse(MemoryMeaning.related("गोवा", MemoryMeaning.tokens("मुझे बिरयानी पसंद है")))
        assertTrue(MemoryMeaning.expand(listOf("गाडी")).size > 1)
    }

    @Test fun recallFindsByMeaning_exactStaysOnTop() {
        val turns = listOf(
            LocalRecall.Turn(1, "u", "मेरी बाइक की सर्विस शनिवार को है", at(16, 10)),
            LocalRecall.Turn(2, "u", "rahul ko kal call karna hai", at(17, 11)),
            LocalRecall.Turn(3, "u", "गाड़ी का बीमा अगले महीने खत्म", at(15, 9)),
            LocalRecall.Turn(4, "u", "मुझे बिरयानी पसंद है", at(17, 21)),
        )
        val q = LocalRecall.parse("मैंने गाड़ी के बारे में क्या बताया था", now, z)
        val h = LocalRecall.search(q, turns, emptyList(), max = 1)
        assertEquals("वही शब्द (गाड़ी) पहले", listOf(3L), h.turns.map { it.id })
        val all = LocalRecall.search(q, turns, emptyList())
        assertEquals("बाइक भी मिली, बिरयानी नहीं", listOf(3L, 1L), all.turns.map { it.id })
        val r = LocalRecall.search(LocalRecall.parse("मैंने राहुल के बारे में क्या बताया था", now, z), turns, emptyList())
        assertEquals(listOf(2L), r.turns.map { it.id })
    }

    @Test fun factsSearchedOnPhone() {
        val facts = listOf("मेरी बाइक का नंबर MH15 AB 1234 है", "मुझे चाय पसंद है", "मेरी पत्नी का जन्मदिन 5 मार्च")
        assertEquals(listOf(facts[0]), LocalRecall.matchFacts("मेरी गाड़ी का नंबर क्या है", facts, max = 1))
        assertTrue(LocalRecall.matchFacts("wife birthday kab hai", facts).contains(facts[2]))
        assertTrue(LocalRecall.matchFacts("गोवा", facts).isEmpty())
        assertTrue(LocalRecall.toolReply(LocalRecall.NOT_FOUND, emptyList()).startsWith("NOT FOUND"))
        val r = LocalRecall.toolReply(LocalRecall.NOT_FOUND, listOf(facts[0]))
        assertTrue(r.startsWith("FOUND") && r.contains("MH15") && !r.contains(LocalRecall.NOT_FOUND))
    }

    @Test fun wiredIntoTheApp() {
        assertTrue(src("LocalMemory.kt").contains("MemoryMeaning.expand(q.words.take(6))"))
        val t = src("GeminiLiveTools.kt")
        assertTrue(t.contains("LocalRecall.matchFacts(q, Memory.facts(svc))") && t.contains("LocalRecall.toolReply(a, f)"))
    }

    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
}
