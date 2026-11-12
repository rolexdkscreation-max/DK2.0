package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 12 नवंबर 2026: DK Island के नियम (13 नवंबर: नाम सिर्फ़ "DK Island") */
class IslandCoreTest {
    private val now = 1_000_000_000L
    private fun task(id: String, active: Boolean, started: Long, ended: Long = 0L, ok: Boolean = false) =
        IslandCore.Task(id, id.substringBefore(':'), "PC", "काम $id", if (active) "चल रहा है" else "पूरा हुआ", "", 0, started, ended, active, ok)

    @Test fun nothing_noIsland() {
        assertNull(IslandCore.lead(IslandCore.Snap(), now))
        assertFalse(IslandCore.wanted(true, false, IslandCore.Snap(), now))
    }

    @Test fun insideDk_orOff_noIsland() {
        val s = IslandCore.Snap(tasks = listOf(task("pc:1", true, now - 5000)))
        assertTrue(IslandCore.wanted(true, false, s, now))
        assertFalse(IslandCore.wanted(true, true, s, now))     // DK ऐप सामने = island नहीं
        assertFalse(IslandCore.wanted(false, false, s, now))   // Settings में बंद
    }

    @Test fun priority_call_live_task_media() {
        val media = IslandCore.Media("गाना", "गायक", "YouTube", "com.google.android.youtube", true)
        val t = task("pc:1", true, now - 5000)
        val all = IslandCore.Snap(IslandCore.Call("माँ", true, now), IslandCore.Live(Mode.LISTENING, false), listOf(t), media)
        assertEquals(IslandCore.Kind.CALL, IslandCore.lead(all, now))
        assertEquals(IslandCore.Kind.LIVE, IslandCore.lead(all.copy(call = null), now))
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(all.copy(call = null, live = null), now))
        assertEquals(IslandCore.Kind.MEDIA, IslandCore.lead(IslandCore.Snap(media = media), now))
        assertEquals(3, IslandCore.others(all, now))
        assertTrue(IslandCore.compactRight(all, now).endsWith("+3"))
        assertEquals("कॉल: माँ", IslandCore.compactText(all, now))
    }

    @Test fun pausedMedia_alone_noIsland_butEndedTask_shortly() {
        val paused = IslandCore.Media("गाना", "", "Spotify", "com.spotify.music", false)
        assertNull(IslandCore.lead(IslandCore.Snap(media = paused), now))
        val done = task("pc:1", false, now - 60_000, ended = now - 2_000, ok = true)
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(IslandCore.Snap(tasks = listOf(done)), now))
        assertTrue(IslandCore.compactText(IslandCore.Snap(tasks = listOf(done)), now).startsWith("✓"))
        assertNull(IslandCore.lead(IslandCore.Snap(tasks = listOf(done)), now + IslandCore.DONE_SHOW_MS + 5_000))
    }

    @Test fun recent_max5_activeFirst_noDuplicates() {
        val active = listOf(task("coder:9", true, now - 100))
        val hist = (1..8).map { task("pc:$it", false, now - 100_000L * it, ended = now - 90_000L * it) } + task("coder:9", false, now - 100)
        val r = IslandCore.recent(active, hist)
        assertEquals(IslandCore.MAX_TASKS, r.size)
        assertEquals("coder:9", r.first().id)
        assertTrue(r.first().active)
        assertEquals(r.size, r.map { it.id }.toSet().size)
        assertEquals("pc:1", r[1].id)
    }

    @Test fun timer_and_ago() {
        assertEquals("1:05", IslandCore.timer(65_000))
        assertEquals("1:00:01", IslandCore.timer(3_601_000))
        assertEquals("अभी", IslandCore.ago(10_000))
        assertEquals("2 मिनट पहले", IslandCore.ago(125_000))
    }

    @Test fun fromJob_realState() {
        val j = TaskStatus.Job("pc", "PC", "PC", "फ़ाइल बनाओ", now - 1000, steps = 3, state = TaskStatus.State.DONE, result = "बन गई", ended = now)
        val t = IslandCore.fromJob(j)
        assertFalse(t.active); assertTrue(t.ok); assertEquals("बन गई", t.last); assertEquals("pc:${now - 1000}", t.id)
    }

    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    @Test fun wiredInService_andSettings() {
        val a = src("src/main/java/com/dk/assistant/DKAccessibilityService.kt")
        assertTrue(a.contains("DKIsland.start(this)") && a.contains("DKIsland.stop()") && a.contains("DKIsland.poke()"))
        val set = SetSrc.all()
        assertTrue(set.contains("secOrb(); secIsland()"))
        assertTrue(SettingsSearch.ENTRIES.any { it.section == "orb" && it.card == "DK Island" })
    }
}
