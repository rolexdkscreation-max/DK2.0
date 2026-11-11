package com.dk.assistant

import com.dk.assistant.Seven.Agent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026 (तेज़): fast=true पर छोटे साफ़ बदलाव का अलग AI रिव्यू नहीं - बाकी क्रम, push, tests वैसे ही */
class CoderSpeedTeamTest {
    private val calc = "package app\n\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private val manager = """{"kind":"feature","brief":"add sub()","acceptance":["sub(5,3)=2"]}"""
    private val planRead = """{"read":["src/Calc.kt"]}"""
    private val planOne = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub function","anchor":"fun add"}],"risks":["add() नहीं बदलना"]}"""
    private val edit = "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\nFIND>>>\n<<<REPLACE\n" +
        "fun add(a: Int, b: Int): Int {\n    return a + b\n}\n\nfun sub(a: Int, b: Int): Int {\n    return a - b\n}\nREPLACE>>>"
    private val gitMsg = """{"commit":"Add sub()","title":"sub() जोड़ा"}"""

    @Test fun fastSkipsAiReviewForSmallCleanEdit() = runBlocking {
        val ai = SevenTeamTest.FakeAi(Agent.MANAGER to manager, Agent.PLANNER to planRead, Agent.PLANNER to planOne,
            Agent.CODER to edit, Agent.GIT to gitMsg)
        val git = SevenTeamTest.FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val f = MemoryFiles(mapOf("src/Calc.kt" to calc))
        val lines = mutableListOf<String>()
        val team = SevenTeam(ai, f, git, fast = true) { _, t -> lines.add(t) }
        val out = team.run("Calc में sub() फ़ंक्शन जोड़ो")
        assertEquals(listOf(Agent.MANAGER, Agent.PLANNER, Agent.PLANNER, Agent.CODER, Agent.GIT), ai.calls)
        assertEquals(true, out.testOk)
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun sub("))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun add("))
        assertEquals(1, team.clock.skippedReviews)
        assertTrue(lines.any { it.startsWith("⏱ कुल") })
    }
}
