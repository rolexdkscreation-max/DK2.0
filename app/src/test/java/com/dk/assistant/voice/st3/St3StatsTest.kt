package com.dk.assistant.voice.st3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19 नवंबर 2026 (मालिक): हर वाक्य का नाप + धीमा हो तो साफ़ बताओ + 4-6 थ्रेड में से सबसे तेज़ */
class St3StatsTest {
    @Test fun rowSaysWhenSynthIsSlowerThanAudio() {
        val ok = St3Stats.Row(30, 600, 2_400, 5, 4)
        val bad = St3Stats.Row(30, 3_000, 2_400, 8, 4)
        assertEquals(0.25, ok.rtf, 1e-9); assertFalse(ok.slow)
        assertTrue(bad.slow)
        assertTrue(St3Stats.line(bad).contains("धीमा"))
        assertFalse(St3Stats.line(ok).contains("धीमा"))
        assertTrue(St3Stats.line(ok).contains("बनी 600 ms") && St3Stats.line(ok).contains("आवाज़ 2400 ms"))
    }

    @Test fun summaryTellsWhatToDo() {
        assertTrue(St3Stats.summary(emptyList()).contains("अभी कोई नाप नहीं"))
        assertTrue(St3Stats.summary(listOf(St3Stats.Row(30, 600, 2_400, 5, 4))).contains("✓"))
        assertTrue(St3Stats.summary(listOf(St3Stats.Row(30, 3_000, 2_400, 8, 4))).contains("कदम\" कम करें"))
    }

    @Test fun keepsOnlyRecentRows() {
        St3Stats.clear()
        repeat(40) { St3Stats.add(St3Stats.Row(it, 1, 2, 5, 4)) }
        assertEquals(8, St3Stats.recent().size)
        assertEquals(39, St3Stats.recent().last().chars)
        St3Stats.clear()
    }

    @Test fun threadsFourToSix_pickFastest() {
        assertEquals(listOf(4, 5, 6), St3Stats.threadChoices(8))
        assertEquals(listOf(4, 5), St3Stats.threadChoices(5))
        assertEquals(listOf(2), St3Stats.threadChoices(2))
        assertEquals(6, St3Stats.fastest(mapOf(4 to 1_000L, 5 to 900L, 6 to 700L)))
        assertEquals(4, St3Stats.fastest(mapOf(4 to 1_000L, 5 to 980L, 6 to 1_200L)))   // 5% के अंदर = कम थ्रेड
        assertEquals(null, St3Stats.fastest(emptyMap()))
    }
}
