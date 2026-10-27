package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalRecallTest {
    private val z = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(z).toInstant().toEpochMilli()
    private val now = at(27, 19, 0)

    private val turns = listOf(
        LocalRecall.Turn(1, "u", "मेरे भाई का नाम राहुल है और वो पुणे में रहता है", at(26, 18, 10)),
        LocalRecall.Turn(2, "d", "अच्छा, राहुल पुणे में रहते हैं।", at(26, 18, 10) + 2000),
        LocalRecall.Turn(3, "u", "मुझे बिरयानी बहुत पसंद है", at(26, 21, 0)),
        LocalRecall.Turn(4, "u", "अलार्म लगाओ सुबह 6 बजे का", at(27, 9, 0)),
        LocalRecall.Turn(5, "u", "मेरे प्रोजेक्ट DK 2.0 की डेडलाइन 5 नवंबर है", at(27, 18, 50)),
        LocalRecall.Turn(6, "d", "ठीक है, 5 नवंबर याद रहेगा।", at(27, 18, 50) + 1500),
        LocalRecall.Turn(7, "u", "मैंने अभी क्या बोला था", at(27, 18, 58)),
        LocalRecall.Turn(8, "d", "बॉस, अभी आपने कहा था…", at(27, 18, 58) + 900),
    )

    @Test fun recallQuestionsDetected() {
        for (q in listOf("मैंने क्या बोला था?", "अभी थोड़ी देर पहले मैंने क्या कहा था", "कल क्या बात हुई थी", "मैंने उस बारे में क्या बताया था",
            "मैंने राहुल के बारे में क्या बताया था", "what did I say about the project", "maine kya bola tha", "तुमने क्या कहा था"))
            assertTrue(q, LocalRecall.isRecall(q))
        for (q in listOf("अलार्म लगाओ", "आज का मौसम कैसा है", "राहुल को व्हाट्सऐप करो", "मैंने खाना खा लिया"))
            assertFalse(q, LocalRecall.isRecall(q))
    }

    @Test fun justNowFindsLastUserLineNotTheQuestion() {
        val q = LocalRecall.parse("मैंने अभी क्या बोला था", now, z)
        assertTrue(q.recent)
        val h = LocalRecall.search(q, turns, emptyList())
        assertEquals(listOf(5L), h.turns.map { it.id })
        assertTrue(LocalRecall.answer(q, h, now, z).contains("डेडलाइन 5 नवंबर"))
    }

    @Test fun yesterdayWindowAndKeywords() {
        val q = LocalRecall.parse("कल मैंने राहुल के बारे में क्या बताया था", now, z)
        assertEquals(at(26, 0), q.from)
        assertTrue(q.words.contains("राहुल"))
        val h = LocalRecall.search(q, turns, emptyList())
        assertEquals(listOf(1L), h.turns.map { it.id })
        assertTrue(LocalRecall.answer(q, h, now, z).startsWith("बॉस, कल शाम 6:10 बजे आपने कहा था"))
    }

    @Test fun eveningOfYesterday() {
        val q = LocalRecall.parse("कल रात क्या बात हुई थी", now, z)
        val h = LocalRecall.search(q, turns, emptyList())
        assertEquals(listOf(3L), h.turns.map { it.id })
    }

    @Test fun notFoundIsHonest() {
        val q = LocalRecall.parse("मैंने गोवा के बारे में क्या बताया था", now, z)
        assertEquals(LocalRecall.NOT_FOUND, LocalRecall.answer(q, LocalRecall.search(q, turns, emptyList()), now, z))
    }

    @Test fun summaryPointsFoundWhenTurnsGone() {
        val pts = listOf(LocalRecall.Point(1, "2026-09-10", "लोग: मेरी बहन का नाम प्रिया है", at(10, 20)))
        val q = LocalRecall.parse("मैंने प्रिया के बारे में क्या बताया था", now, z)
        val a = LocalRecall.answer(q, LocalRecall.search(q, turns, pts), now, z)
        assertTrue(a, a.contains("प्रिया"))
    }

    @Test fun dkSideQuestion() {
        val q = LocalRecall.parse("तुमने नवंबर के बारे में क्या कहा था", now, z)
        assertTrue(q.dk)
        assertEquals(listOf(6L), LocalRecall.search(q, turns, emptyList()).turns.map { it.id })
    }

    @Test fun summaryKeepsOnlyImportant() {
        val s = LocalRecall.summarize(turns + LocalRecall.Turn(9, "u", "मेरा पासवर्ड abc123 है", at(27, 10)) +
            LocalRecall.Turn(10, "u", "हाँ", at(27, 10)))
        assertTrue(s.toString(), s.any { it.contains("राहुल") })
        assertTrue(s.toString(), s.any { it.contains("बिरयानी") })
        assertTrue(s.toString(), s.any { it.contains("डेडलाइन") })
        assertFalse(s.toString(), s.any { it.contains("अलार्म") || it.contains("पासवर्ड") || it == "हाँ" })
    }

    @Test fun privateThingsNotStored() {
        assertNull(LocalRecall.storable("मेरा पासवर्ड abc123 है"))
        assertNull(LocalRecall.storable("कार्ड नंबर 4111 1111 1111 1111"))
        assertEquals("मुझे चाय पसंद है", LocalRecall.storable("मुझे चाय पसंद है"))
    }

    @Test fun forgetThisMeansLast() {
        assertTrue(LocalRecall.isForget("ये भूल जाओ"))
        assertTrue(LocalRecall.forgetsLast("ये"))
        assertFalse(LocalRecall.forgetsLast("राहुल का पता"))
        assertEquals(5L, LocalRecall.lastRealUser(turns, at(27, 19, 0))?.id)
    }

    @Test fun daysBeforeDelete() {
        assertEquals(listOf("2026-09-01", "2026-10-26"),
            LocalRecall.daysToSummarize(listOf("2026-10-26", "2026-09-01", "2026-10-27", "2026-09-01"), setOf("2026-09-02"), "2026-10-27"))
    }
}
