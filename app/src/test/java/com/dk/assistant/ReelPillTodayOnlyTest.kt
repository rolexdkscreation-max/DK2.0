package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): रील की ऊपर वाली पट्टी पर सिर्फ़ आज - कल का हिसाब सिर्फ़ DK सेटिंग के अंदर */
class ReelPillTodayOnlyTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun pillExtra_onlyToday_neverYesterday() {
        assertEquals("", ReelRules.todayExtra(total = 12, limit = 0))
        assertEquals(" • आज सब ऐप 12 / 100", ReelRules.todayExtra(total = 12, limit = 100))
        assertFalse(ReelRules.todayExtra(5, 50).contains("कल"))
        assertTrue(ReelRules.pill(ReelRules.App.INSTAGRAM, 3, 0).contains("आज 3"))
        assertFalse(ReelRules.ytPill(true, 2, 4, 0).contains("कल"))
    }

    @Test fun wiring_pillHasNoYesterday_settingsKeepsHistory() {
        val w = src("ReelWatch.kt")
        assertTrue(w.contains("ReelRules.todayExtra(all.total, all.limit)"))
        assertFalse(w.contains("\" • कल \${all.yesterday}\""))
        val s = src("SettingsReels.kt")
        assertTrue(s.contains("card(\"7 दिन का रील हिसाब\"") && s.contains("ReelDaily.table(this)"))
        assertTrue(s.contains("ऊपर की पट्टी पर सिर्फ़ आज की गिनती"))
    }
}
