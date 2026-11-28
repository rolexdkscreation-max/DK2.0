package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 28 नवंबर 2026 (मालिक का लॉग: context 19,214 token > सीमा 18,417): एक बार थोड़ा ऊपर = ठीक (server अगली बारी से पहले
 * छाँटता है). पर लगातार [HistoryOnDemand.SMALL_OVER_TURNS] बार सीमा से ऊपर = छँटाई नहीं हो रही -> तरीका B.
 * पहले सिर्फ़ 2.5k से ज़्यादा ऊपर ही पकड़ा जाता था.
 */
class ContextLimitNov28Test {
    private val trigger = 18_417L

    @Test fun oneSmallOverIsFine() {
        assertFalse(HistoryOnDemand.ineffective(listOf(16_900L, 19_214L), trigger))
        assertFalse(HistoryOnDemand.ineffective(listOf(19_214L, 16_800L, 17_200L), trigger))
    }

    @Test fun twoSmallOverStillWaits() {
        assertFalse(HistoryOnDemand.ineffective(listOf(16_000L, 19_214L, 19_400L), trigger))
    }

    @Test fun threeSmallOverInARowIsStuck() {
        val stuck = listOf(19_214L, 19_300L, 19_500L)
        assertTrue(HistoryOnDemand.stuckOver(stuck, trigger))
        assertTrue(HistoryOnDemand.ineffective(stuck, trigger))
        assertFalse("सीमा नहीं = कभी नहीं", HistoryOnDemand.stuckOver(stuck, 0L))
    }

    @Test fun oldBigOverRuleUnchanged() {
        assertTrue(HistoryOnDemand.ineffective(listOf(21_000L, 21_500L), trigger))
    }

    @Test fun overNoteInLog() {
        assertEquals(" - सीमा से 797 token ऊपर (server अगली बारी से पहले छाँटेगा)", HistoryOnDemand.overNote(19_214L, trigger))
        assertEquals("", HistoryOnDemand.overNote(18_000L, trigger))
        assertEquals("", HistoryOnDemand.overNote(19_214L, 0L))
    }

    @Test fun wiredInLive() {
        val dir = java.io.File("src/main/java/com/dk/assistant").let { if (it.exists()) it else java.io.File("app/src/main/java/com/dk/assistant") }
        val h = java.io.File(dir, "GeminiLiveHistory.kt").readText()
        assertTrue(h.contains("HistoryOnDemand.overNote(t, histTrigger)"))
        assertTrue(h.contains("HistoryOnDemand.stuckOver(recent, histTrigger)"))
    }
}
