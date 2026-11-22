package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 22 नवंबर 2026 (मालिक): कई काम चल रहे हों तो island में अभी वाला काम, island की चौड़ाई में पानी की तरह बहता हुआ */
class IslandQueueMarqueeTest {
    private val now = 1_000_000_000L

    private fun queueTask(last: String, active: Boolean = true) = IslandCore.fromJob(
        TaskStatus.Job(TaskQueue.STATUS_KEY, "DK के 5 काम", "DK task list", "1) बैटरी  2) Instagram", now - 20_000, steps = 2, last = last,
            state = if (active) TaskStatus.State.RUNNING else TaskStatus.State.DONE, ended = if (active) 0L else now - 1_000))

    private val live = IslandCore.Live(Mode.LISTENING, false)

    @Test fun sameKeyAsQueue() = assertEquals(TaskQueue.STATUS_KEY, IslandCore.QUEUE_KEY)

    @Test fun runningQueueLeadsEvenWhenLiveIsOn() {
        val line = TaskQueue.stepLine(2, 5, TaskQueue.Item("Instagram खोलो और followers चेक करो", "instagram"))
        val s = IslandCore.Snap(live = live, tasks = listOf(queueTask(line)))
        assertEquals(IslandCore.Kind.TASK, IslandCore.lead(s, now))
        assertEquals(IslandCore.Kind.TASK, IslandCore.items(s, now).first().kind)
        assertEquals(IslandCore.Kind.LIVE, IslandCore.bubble(s, now)?.kind)      // लाइव बुलबुले में दिखता रहे
        assertEquals("2/5 • Instagram खोलो और followers चेक करो", IslandCore.compactText(s, now))
        assertTrue(IslandCore.compactRight(s, now).startsWith("2/5"))
        assertTrue(IslandCore.marquee(s, now))
    }

    @Test fun otherTasksKeepOldBehaviour() {
        val pc = IslandCore.fromJob(TaskStatus.Job(TaskStatus.PC, "DK कंप्यूटर", "DK Computer", "x", now - 5_000, steps = 3, last = "कदम"))
        val s = IslandCore.Snap(live = live, tasks = listOf(pc))
        assertEquals(IslandCore.Kind.LIVE, IslandCore.lead(s, now))
        assertFalse(IslandCore.marquee(s, now))
        val noLive = IslandCore.Snap(tasks = listOf(pc))
        assertEquals("DK कंप्यूटर", IslandCore.compactText(noLive, now))
        assertEquals("3 कदम", IslandCore.compactRight(noLive, now))
        assertFalse(IslandCore.marquee(noLive, now))
    }

    @Test fun finishedQueueStopsFlowing() {
        val s = IslandCore.Snap(live = live, tasks = listOf(queueTask("5/5 • गाना चलाओ", active = false)))
        assertEquals(IslandCore.Kind.LIVE, IslandCore.lead(s, now))
        assertFalse(IslandCore.marquee(s, now))
    }

    @Test fun queueShownBeforeOtherRunningTask() {
        val pc = IslandCore.fromJob(TaskStatus.Job(TaskStatus.PC, "DK कंप्यूटर", "DK Computer", "x", now - 5_000))
        val s = IslandCore.Snap(tasks = listOf(pc, queueTask("1/3 • बैटरी बताओ")))
        assertEquals(IslandCore.QUEUE_KEY, IslandCore.shownTask(s, now)?.key)
        assertEquals("1/3 • बैटरी बताओ", IslandCore.compactText(s, now))
    }

    @Test fun queueCountOnlyFromRealPrefix() {
        assertEquals("3/10", IslandCore.queueCount("3/10 • टॉर्च जलाओ"))
        assertEquals("", IslandCore.queueCount("टॉर्च जलाओ"))
        assertEquals("", IslandCore.queueCount("ab • c"))
    }

    @Test fun marqueeMathLoopsSmoothly() {
        assertFalse(IslandCore.marqueeNeeded(100f, 190f))
        assertTrue(IslandCore.marqueeNeeded(300f, 190f))
        assertFalse(IslandCore.marqueeNeeded(300f, 0f))
        assertEquals(3.4f, IslandCore.marqueeStep(0f, 1000L, 34f, 400f), 0.001f)   // island अटका = बड़ी छलांग नहीं (100ms तक)
        assertEquals(3.4f, IslandCore.marqueeStep(0f, 100L, 34f, 400f), 0.001f)
        assertEquals(1f, IslandCore.marqueeStep(399f, 100L, 20f, 400f), 0.001f)       // घूमकर फिर शुरू
        assertEquals(0f, IslandCore.marqueeStep(10f, 16L, 34f, 0f), 0.0f)
    }

    @Test fun islandUsesOwnMarqueeView() {
        val src = listOf(File("src/main/java/com/dk/assistant/DKIsland.kt"), File("app/src/main/java/com/dk/assistant/DKIsland.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("private class MarqueeText"))
        assertTrue(src.contains("postInvalidateOnAnimation()"))
        assertTrue(src.contains("if (IslandCore.marquee(sn, now))"))
        assertTrue(src.contains("t.key == IslandCore.QUEUE_KEY"))
    }
}
