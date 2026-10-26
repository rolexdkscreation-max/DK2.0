package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.SevenTeamTest.FakeAi
import com.dk.assistant.SevenTeamTest.FakeGit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "आगे करो": टीम ठीक वहीं से आगे बढ़े जहाँ रुकी थी - पूरे हुए हिस्से (branch, कदम, टेस्ट) दोबारा नहीं */
class TeamProgressTest {

    private val calcSub = """package app

fun add(a: Int, b: Int): Int {
    return a + b
}

fun sub(a: Int, b: Int): Int {
    return a + b
}
"""
    private val pass = """{"pass":true,"problems":[]}"""
    private val gitMsg = """{"commit":"Add sub()","title":"sub() जोड़ा"}"""
    private val pre = SevenPlus.Understanding(Seven.Kind.FEATURE, "add sub()", "Calc में sub() जोड़ना", "add fun sub(a,b)=a-b to src/Calc.kt",
        listOf("sub(5,3)=2"), emptyList(), emptyList(), emptyList())

    @Test fun json_roundTrip() {
        val p = TeamProgress(TeamProgress.CODED, "src/Calc.kt:7 - plus instead of minus", mapOf("src/Calc.kt" to "add sub"),
            listOf(Seven.Step("src/Calc.kt", "edit", "fix sub", "fun sub", listOf("add"), "why")), 1,
            "CalcTest FAILED", "https://ci/3", "Add sub()", "sub() जोड़ा", "backup/dk-1", qaOk = true)
        assertEquals(p, TeamProgress.parse(p.json()))
        assertNull(TeamProgress.parse(""))
        assertNull(TeamProgress.parse("{broken"))
    }

    @Test fun checkpoint_keepsTeamProgress() {
        val k = CoderHistory.Checkpoint("t1", "task", pre, "pause", team = TeamProgress(TeamProgress.PASSED).json())
        val back = CoderHistory.Logic.ck(CoderHistory.Logic.ckJson(k))!!
        assertEquals(TeamProgress.PASSED, TeamProgress.parse(back.team)!!.phase)
    }

    @Test fun progress_savedAtEveryMilestone() = runBlocking {
        val ai = FakeAi(Agent.PLANNER to """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add mul","anchor":"fun add"}]}""",
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\nFIND>>>\n<<<REPLACE\nfun mul(a: Int, b: Int): Int = a * b\n\nfun add(a: Int, b: Int): Int {\nREPLACE>>>",
            Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val seen = mutableListOf<TeamProgress>()
        val team = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calcSub)), git) { _, _ -> }
        team.onProgress = { seen.add(it) }
        val out = team.run("Calc में mul() जोड़ो", pre)
        assertEquals(true, out.testOk)
        assertTrue(seen.any { it.phase == TeamProgress.BRANCH && it.backup == "backup/dk-1022-1430" })
        assertTrue(seen.any { it.steps.size == 1 && it.stepsDone == 1 })
        assertTrue(seen.any { it.phase == TeamProgress.CODED })
        assertEquals(TeamProgress.PASSED, seen.last().phase)
        assertEquals("Add sub()", seen.last().commit)
    }

    @Test fun resume_afterPass_noTestsNoCodingAgain() = runBlocking {
        val ai = FakeAi()                                          // कोई AI कॉल नहीं होनी चाहिए
        val git = FakeGit(ArrayDeque())                           // कोई टेस्ट/push नहीं होना चाहिए
        val team = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calcSub)), git) { _, _ -> }
        team.resumeFrom = TeamProgress(TeamProgress.PASSED, changes = mapOf("src/Calc.kt" to "add sub"), commit = "Add sub()", backup = "backup/dk-old")
        val out = team.run("Calc में sub() जोड़ो", pre)
        assertEquals(true, out.testOk)
        assertTrue(ai.calls.isEmpty())
        assertEquals(listOf("branch", "ready"), git.log)          // backup दोबारा नहीं, push/टेस्ट दोबारा नहीं
        assertTrue(out.short, out.short.startsWith("✅ काम हो गया"))
    }

    @Test fun resume_afterFailedTest_goesStraightToDebugger() = runBlocking {
        val ai = FakeAi(
            Agent.DEBUGGER to """{"root_cause":"src/Calc.kt:8 - sub returns a + b","where":"src/Calc.kt:8","fix":"use minus","files":["src/Calc.kt"]}""",
            Agent.PLANNER to """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"fix sub","anchor":"fun sub"}]}""",
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\nfun sub(a: Int, b: Int): Int {\n    return a + b\n}\nFIND>>>\n<<<REPLACE\nfun sub(a: Int, b: Int): Int {\n    return a - b\n}\nREPLACE>>>",
            Agent.REVIEWER to pass)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/2"))))
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcSub))
        val team = SevenTeam(ai, f, git) { _, _ -> }
        team.resumeFrom = TeamProgress(TeamProgress.CODED, changes = mapOf("src/Calc.kt" to "add sub"),
            failLog = "CalcTest > sub FAILED\njava.lang.AssertionError: expected:<2> but was:<8>", failUrl = "https://ci/1",
            commit = "Add sub()", title = "sub() जोड़ा", backup = "backup/dk-old")
        val out = team.run("Calc में sub() जोड़ो", pre)
        assertEquals(Agent.DEBUGGER, ai.calls.first())             // पहले टेस्ट दोबारा नहीं चला - सीधे डिबगर
        assertEquals(listOf("branch", "push:Add sub()", "ready"), git.log)
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("return a - b"))
        assertEquals(true, out.testOk)
    }

    @Test fun resume_midPlan_onlyRemainingSteps() = runBlocking {
        val steps = listOf(Seven.Step("src/Calc.kt", "edit", "add sub", "fun add"), Seven.Step("src/Calc.kt", "edit", "add mul", "fun add"))
        val ai = FakeAi(
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\nFIND>>>\n<<<REPLACE\nfun mul(a: Int, b: Int): Int = a * b\n\nfun add(a: Int, b: Int): Int {\nREPLACE>>>",
            Agent.REVIEWER to pass)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcSub))
        val events = mutableListOf<String>()
        val team = SevenTeam(ai, f, git) { _, t -> events.add(t) }
        team.resumeFrom = TeamProgress(TeamProgress.BRANCH, steps = steps, stepsDone = 1, commit = "Add sub()", backup = "backup/dk-old")
        val out = team.run("Calc में sub() और mul() जोड़ो", pre)
        assertFalse(ai.calls.contains(Agent.PLANNER))              // प्लान दोबारा नहीं बना
        assertEquals(1, ai.calls.count { it == Agent.CODER })       // सिर्फ़ बचा हुआ कदम 2
        assertTrue(events.any { it.contains("कदम 2/2") })
        assertFalse(events.any { it.contains("कदम 1/2") })
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun mul("))
        assertEquals(true, out.testOk)
    }

    @Test fun noResult_shortReport_reasonAndFixOnly() = runBlocking {
        val ai = FakeAi(Agent.PLANNER to """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add mul","anchor":"fun add"}]}""",
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\nFIND>>>\n<<<REPLACE\nfun mul(a: Int, b: Int): Int = a * b\n\nfun add(a: Int, b: Int): Int {\nREPLACE>>>",
            Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(null, "", "", "PR के चेक का नतीजा नहीं दिखा — GitHub token में Actions: Read की इजाज़त दें"))))
        val out = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calcSub)), git) { _, _ -> }.run("Calc में mul() जोड़ो", pre)
        assertNull(out.testOk)
        assertTrue(out.short, out.short.startsWith("⏳ काम पूरा नहीं हुआ"))
        assertTrue(out.short, out.short.contains("वजह: GitHub token") && out.short.contains("हल:") && out.short.contains("API: ✅"))
        assertTrue(out.short, out.short.lines().size <= 5)
    }
}
