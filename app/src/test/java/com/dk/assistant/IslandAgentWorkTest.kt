package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक, स्क्रीनशॉट): "island के नीचे वाला अलग बुलबुला (DK: 2/4 • instagram खोलो…) हटाओ -
 * जो काम चल रहा है वो island के अंदर दिखे. कोई भी काम रनिंग हो तो वहाँ दिखना चाहिए."
 */
class IslandAgentWorkTest {
    private val now = 1_000_000L

    @Test fun directPhoneWork_showsInsideIsland_withRealStep() {
        val t = IslandCore.agentTask("instagram खोलो", "Instagram खोल रहा हूँ", now - 5_000)
        val s = IslandCore.Snap(tasks = listOf(t))
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(s, now))
        assertEquals("instagram खोलो • Instagram खोल रहा हूँ", IslandCore.compactText(s, now))
        assertTrue(IslandCore.marquee(s, now))               // लंबी लाइन बहती है
        assertEquals("0:05", IslandCore.compactRight(s, now))
    }

    @Test fun directPhoneWork_beatsLiveBubble_likeQueue() {
        val t = IslandCore.agentTask("बैटरी बताओ", "", now)
        val s = IslandCore.Snap(live = IslandCore.Live(Mode.WORKING, false), tasks = listOf(t))
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(s, now))
        assertEquals("बैटरी बताओ", IslandCore.compactText(s, now))
    }

    @Test fun queue_stillFirst() {
        val q = IslandCore.Task("queue:1", IslandCore.QUEUE_KEY, "DK के 4 काम", "", "चल रहा है", "2/4 • instagram खोलो", 0, now, 0, true)
        val a = IslandCore.agentTask("x", "y", now)
        assertEquals(IslandCore.QUEUE_KEY, IslandCore.shownTask(IslandCore.Snap(tasks = listOf(a, q)), now)?.key)
    }

    @Test fun agentLine_neverEmpty_noDuplicates() {
        assertEquals(IslandCore.modeHi(Mode.WORKING), IslandCore.agentLine(" ", ""))
        assertEquals("instagram खोलो", IslandCore.agentLine("instagram  खोलो…", "instagram खोलो"))
        assertTrue(IslandCore.flowing(IslandCore.agentTask("a", "b", now)))
    }

    @Test fun banner_onlyWhenIslandCannotShow() {
        assertFalse(WorkAwakeRules.bannerNeeded(true, "com.instagram.android", "com.dk.assistant", islandShows = true))
        assertTrue(WorkAwakeRules.bannerNeeded(true, "com.instagram.android", "com.dk.assistant", islandShows = false))
        assertFalse(WorkAwakeRules.bannerNeeded(true, "com.dk.assistant", "com.dk.assistant", islandShows = false))
        assertFalse(WorkAwakeRules.bannerNeeded(false, "com.instagram.android", "com.dk.assistant", islandShows = false))
    }

    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    @Test fun wired() {
        val w = src("WorkAwake.kt")
        assertTrue(w.contains("WorkAwakeRules.bannerNeeded(true, fg, svc.packageName, islandShows)"))
        assertTrue(w.contains("DKIsland.canShow(svc)"))
        assertTrue(w.contains("FLAG_KEEP_SCREEN_ON"))         // स्क्रीन जगी रखना वही
        val i = src("DKIsland.kt")
        assertTrue(i.contains("WorkAwake.agentNow()"))
        assertTrue(i.contains("IslandCore.agentTask(agent.goal, agent.step, agent.since)"))
    }
}
