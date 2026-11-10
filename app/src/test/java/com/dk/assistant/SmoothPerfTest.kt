package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "ऐप अटक-अटक के न चले, स्क्रीन डूबकर फिर चालू न हो, कोडिंग एजेंट जल्दी समझे/समझाए") */
class SmoothPerfTest {
    private fun src(f: String) = File("src/main/java/com/dk/assistant/$f").readText()

    @Test fun chatTrimKeepsNewest() {
        assertEquals(0, UiPace.trimCount(10))
        assertEquals(0, UiPace.trimCount(UiPace.CHAT_MAX_VIEWS))
        assertEquals(5, UiPace.trimCount(UiPace.CHAT_MAX_VIEWS + 5))
    }

    @Test fun droppedLinesDoNotFreezeChat() {
        // 300 पर एक लाइन आगे से हटी: पहले shown == size रहकर नई लाइन कभी नहीं दिखती थी
        assertEquals(299, UiPace.shownAfterDrop(300, 0, 1))
        assertEquals(300, UiPace.shownAfterDrop(300, 4, 4))
        assertEquals(0, UiPace.shownAfterDrop(3, 0, 10))
        assertTrue(src("CodingActivity.kt").contains("UiPace.shownAfterDrop(shown, seenDrop, drop)"))
        assertTrue(src("ComputerActivity.kt").contains("UiPace.shownAfterDrop(shown, seenDrop, drop)"))
    }

    @Test fun coderLinesOnlyOnMainThreadAndRenderCoalesced() {
        val s = src("CodingActivity.kt")
        val add = s.substringAfter("fun add(role: String, t: String) {").substringBefore("\n    }\n")
        assertTrue(add.contains("Looper.myLooper() != main"))
        assertTrue(s.contains("CoderSession.listener = { runOnUiThread { scheduleRender() } }"))
        assertTrue(src("ComputerActivity.kt").contains("ComputerSession.listener = { runOnUiThread { scheduleRender() } }"))
    }

    @Test fun glassAndLogDoLessWork() {
        val g = src("Glass.kt")
        assertTrue(g.contains("postDelayed(pass, DECORATE_GAP_MS)"))
        assertFalse(g.contains("addOnGlobalLayoutListener { apply() }"))
        val l = src("DKLog.kt")
        assertTrue(l.contains("DKBug.logFile?.append"))
        assertFalse(l.substringAfter("fun d(msg: String)").substringBefore("fun clear").contains("joinToString"))
    }

    @Test fun checkpointReadFromMemory() {
        val h = src("CoderHistory.kt")
        assertTrue(h.contains("if (ckPath != null) return ckMem"))
    }

    @Test fun crashHistoryKeepsLastFive() {
        var all = ""
        for (i in 1..7) all = CrashLog.push(all, "e$i")
        val e = CrashLog.entries(all)
        assertEquals(CrashLog.KEEP, e.size)
        assertEquals("e7", e.first())
        assertEquals("e3", e.last())
        assertEquals(listOf("x"), CrashLog.entries(CrashLog.push("", "x")))
        val app = src("DKApp.kt")
        assertTrue(app.contains("last_crash.txt") && app.contains("crash_history.txt"))
        assertTrue(app.contains("v.requestLayout(); v.invalidate()"))
    }

    @Test fun talkAgentsThinkLessCodersUnchanged() {
        for (a in listOf(Seven.Agent.MANAGER, Seven.Agent.PROMPT, Seven.Agent.EXPLAINER, Seven.Agent.GIT))
            assertEquals(a.name, ThinkLevel.LOW, TeamModels.thinkFor(a, ThinkLevel.AUTO))
        for (a in listOf(Seven.Agent.CODER, Seven.Agent.PLANNER, Seven.Agent.REVIEWER, Seven.Agent.DEBUGGER, Seven.Agent.TESTER, Seven.Agent.QA))
            assertEquals(a.name, ThinkLevel.AUTO, TeamModels.thinkFor(a, ThinkLevel.AUTO))
        // user ने खुद स्तर चुना = वही, किसी पर नहीं बदलता
        assertEquals(ThinkLevel.HIGH, TeamModels.thinkFor(Seven.Agent.MANAGER, ThinkLevel.HIGH))
        assertTrue(SevenTeam.EXPLAIN_ROUNDS in 3..8)
        assertTrue(src("SevenRunner.kt").contains("TeamModels.thinkFor(agent, base.think)"))
    }
}
