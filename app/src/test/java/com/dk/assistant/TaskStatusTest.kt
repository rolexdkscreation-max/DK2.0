package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** असली live status: सिर्फ़ दर्ज सच - नकली % नहीं, खत्म होने से पहले "पूरा" नहीं */
class TaskStatusTest {
    private var now = 1_000_000L
    @Before fun setUp() { TaskStatus.clear(); TaskStatus.clock = { now } }

    @Test fun nothingRunning_saysSoHonestly() {
        assertTrue(TaskStatus.summary().contains("No background task"))
    }

    @Test fun realStepsCounted_noPercent() {
        TaskStatus.start(TaskStatus.PC, "DK कंप्यूटर", "DK Computer", "PDF बनाओ")
        now += 5_000; TaskStatus.step(TaskStatus.PC, "वेब पर खोज")
        now += 5_000; TaskStatus.step(TaskStatus.PC, "PDF लिख रहा हूँ")
        val s = TaskStatus.summary()
        assertTrue(s.contains("RUNNING")); assertTrue(s.contains("2 real steps")); assertTrue(s.contains("PDF लिख रहा हूँ"))
        assertFalse(s.contains("%"))
        assertFalse(s.lines().first().contains("FINISHED"))
        assertTrue(TaskStatus.line(TaskStatus.get(TaskStatus.PC)!!).contains("2 कदम"))
    }

    @Test fun endedTask_endNoteTellsRealResult_listenerFiresOnce() {
        val ends = mutableListOf<TaskStatus.Job>()
        val l = TaskStatus.Listener { j, ended -> if (ended) ends.add(j) }
        TaskStatus.listen(l)
        try {
            TaskStatus.start(TaskStatus.CODER, "कोडिंग टीम", "the coding team", "बटन जोड़ो")
            TaskStatus.end(TaskStatus.CODER, TaskStatus.State.FAILED, "चेक फ़ेल")
            TaskStatus.end(TaskStatus.CODER, TaskStatus.State.DONE, "दोबारा नहीं")      // खत्म हो चुका - अनदेखा
            TaskStatus.step(TaskStatus.CODER, "खत्म के बाद कदम")                       // अनदेखा
            assertEquals(1, ends.size)
            val note = TaskStatus.endNote(ends[0])
            assertTrue(note.contains("FAILED")); assertTrue(note.contains("Do NOT say the work is complete"))
            assertEquals(0, TaskStatus.get(TaskStatus.CODER)!!.steps)
        } finally { TaskStatus.unlisten(l) }
    }

    @Test fun coderState_doneOnlyOnRealPass() {
        assertEquals(TaskStatus.State.DONE, TaskStatus.coderState("d", "📋 रिपोर्ट\n✅ काम पूरा हुआ", false))
        assertEquals(TaskStatus.State.NOT_DONE, TaskStatus.coderState("e", "📋 रिपोर्ट\n⏳ काम अभी पूरा नहीं हुआ", false))
        assertEquals(TaskStatus.State.FAILED, TaskStatus.coderState("e", "❌ फ़ेल", false))
        assertEquals(TaskStatus.State.CANCELLED, TaskStatus.coderState("d", "x", true))
        assertEquals(TaskStatus.State.NOT_DONE, TaskStatus.coderState(null, null, false))
    }

    @Test fun waitingForPermission_isNotDone() {
        TaskStatus.start(TaskStatus.PC, "DK कंप्यूटर", "DK Computer", "फ़ाइल हटाओ")
        TaskStatus.waiting(TaskStatus.PC, "इजाज़त चाहिए: फ़ाइल हटाऊँ?")
        assertTrue(TaskStatus.summary().contains("WAITING FOR THE USER"))
    }
}
