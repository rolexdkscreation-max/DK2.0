package com.dk.assistant

import com.dk.assistant.Seven.Agent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7-एजेंट सिस्टम: पूरा क्रम नकली (scripted) AI के साथ - हर एजेंट को उसका अपना prompt, सही क्रम, गलत बदलाव कभी लागू नहीं */
class SevenTeamTest {

    /** हर एजेंट के लिए तय जवाबों की कतार; कौन-सा एजेंट किस prompt से कब बुलाया गया, सब दर्ज */
    class FakeAi(vararg script: Pair<Agent, String>) : TeamAi {
        private val q = script.groupBy({ it.first }, { it.second }).mapValues { ArrayDeque(it.value) }.toMutableMap()
        val calls = mutableListOf<Agent>()
        val prompts = mutableMapOf<Agent, String>()
        val lastUser = mutableMapOf<Agent, String>()
        override suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String {
            calls.add(agent); prompts[agent] = system; lastUser[agent] = msgs.last().second
            return q[agent]?.removeFirstOrNull() ?: error("$agent के लिए जवाब नहीं बचा (calls: $calls)")
        }
    }

    class FakeGit(private val tests: ArrayDeque<TeamTest>) : TeamGit {
        val log = mutableListOf<String>()
        override suspend fun backup(task: String) = "backup/dk-1022-1430".also { log.add("backup") }
        override suspend fun branch(task: String) = "feature/dk-x".also { log.add("branch") }
        override suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest {
            log.add("push:$commit"); return tests.removeFirst()
        }
        override suspend fun ready() = "https://github.com/u/r/pull/7".also { log.add("ready") }
        override val prUrl = "https://github.com/u/r/pull/7"
    }

    private val calc = """package app

fun add(a: Int, b: Int): Int {
    return a + b
}
"""
    private fun files() = MemoryFiles(mapOf("src/Calc.kt" to calc))

    private val managerFeature = """{"kind":"feature","brief":"add sub()","acceptance":["sub(5,3)=2"]}"""
    private val planRead = """{"read":["src/Calc.kt"]}"""
    private val planOne = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub function","anchor":"fun add"}],"risks":["add() नहीं बदलना"]}"""
    private val goodEdit = """FILE: src/Calc.kt
<<<FIND
fun add(a: Int, b: Int): Int {
    return a + b
}
FIND>>>
<<<REPLACE
fun add(a: Int, b: Int): Int {
    return a + b
}

fun sub(a: Int, b: Int): Int {
    return a - b
}
REPLACE>>>"""
    private val pass = """{"pass":true,"problems":[]}"""
    private val gitMsg = """{"commit":"Add sub()","title":"sub() जोड़ा"}"""

    @Test fun feature_githubMode_fullOrder() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planRead, Agent.PLANNER to planOne,
            Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val f = files()
        val events = mutableListOf<Agent>()
        val out = SevenTeam(ai, f, git) { a, _ -> events.add(a) }.run("Calc में sub() फ़ंक्शन जोड़ो")
        // क्रम: मैनेजर → गिट (backup, branch) → प्लानर → कोडर → रिव्यूअर → टेस्टर/गिट push → गिट PR
        assertEquals(listOf(Agent.MANAGER, Agent.PLANNER, Agent.PLANNER, Agent.CODER, Agent.REVIEWER, Agent.GIT), ai.calls)
        assertEquals(listOf("backup", "branch", "push:Add sub()", "ready"), git.log)
        assertTrue(events.indexOf(Agent.GIT) < events.indexOf(Agent.PLANNER))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun sub("))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun add("))          // पुराना फ़ीचर बरकरार
        assertEquals(true, out.testOk)
        assertTrue(out.report, out.report.contains("क्या मैं PR को merge कर दूँ?"))
        assertTrue(out.report.contains("backup/dk-1022-1430"))
        assertTrue(out.report.contains("src/Calc.kt"))
        // हर एजेंट का अपना prompt
        assertTrue(ai.prompts[Agent.CODER]!!.startsWith("You are the CODER"))
        assertTrue(ai.prompts[Agent.REVIEWER]!!.startsWith("You are the REVIEWER"))
        assertTrue(ai.prompts[Agent.PLANNER]!!.startsWith("You are the PLANNER"))
    }

    @Test fun replaceWithoutFind_neverApplied_sentBackToCoder() = runBlocking {
        val onlyReplace = "FILE: src/Calc.kt\n<<<REPLACE\nfun sub(a: Int, b: Int) = a - b\nREPLACE>>>"
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planOne,
            Agent.CODER to onlyReplace, Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.TESTER to """{"pass":true,"cases":["sub(5,3)=2"]}""")
        val f = files()
        val out = SevenTeam(ai, f, null) { _, _ -> }.run("Calc में sub() जोड़ो")
        assertEquals(2, ai.calls.count { it == Agent.CODER })
        assertTrue(ai.lastUser[Agent.CODER]!!.contains("REVIEWER REJECTED"))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun sub("))
        assertEquals(true, out.testOk)
    }

    @Test fun findMissTwice_managerSwitchesToFullRewrite() = runBlocking {
        val wrongFind = "FILE: src/Calc.kt\n<<<FIND\nfun add(x: Int) {\nFIND>>>\n<<<REPLACE\nfun add(x: Int) { }\nREPLACE>>>"
        val rewrite = "FILE: src/Calc.kt\n<<<FILE\n" + calc.trimEnd() + "\n\nfun sub(a: Int, b: Int): Int {\n    return a - b\n}\nFILE>>>"
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planOne,
            Agent.CODER to wrongFind, Agent.CODER to wrongFind, Agent.CODER to rewrite, Agent.REVIEWER to pass,
            Agent.TESTER to """{"pass":true}""")
        val events = mutableListOf<String>()
        val f = files()
        SevenTeam(ai, f, null) { a, t -> events.add("${a.name}:$t") }.run("Calc में sub() जोड़ो")
        assertTrue(events.any { it.startsWith("MANAGER:") && it.contains("तरीका बदला") })
        assertTrue(ai.prompts[Agent.CODER]!!.contains("MANAGER CHANGED STRATEGY"))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun sub("))
    }

    @Test fun bug_debuggerFirst_thenMinimalPlan() = runBlocking {
        val buggy = "fun div(a: Int, b: Int): Int {\n    return a / b\n}\n"
        val ai = FakeAi(Agent.MANAGER to """{"kind":"bug","brief":"div crashes on 0"}""",
            Agent.DEBUGGER to """{"read":["src/Div.kt"]}""",
            Agent.DEBUGGER to """{"root_cause":"src/Div.kt:2 - b=0 पर a / b ArithmeticException देता है","bug":"0 से भाग पर crash","where":"src/Div.kt:2","fix":"return 0 when b == 0","files":["src/Div.kt"]}""",
            Agent.PLANNER to """{"steps":[{"file":"src/Div.kt","action":"edit","change":"guard b == 0","anchor":"return a / b"}]}""",
            Agent.CODER to "FILE: src/Div.kt\n<<<FIND\n    return a / b\nFIND>>>\n<<<REPLACE\n    return if (b == 0) 0 else a / b\nREPLACE>>>",
            Agent.REVIEWER to pass, Agent.TESTER to """{"pass":true}""")
        val f = MemoryFiles(mapOf("src/Div.kt" to buggy))
        val out = SevenTeam(ai, f, null) { _, _ -> }.run("div 0 पर crash हो रहा है, ठीक करो")
        // बग: डिबगर प्लानर से पहले
        assertTrue(ai.calls.indexOf(Agent.DEBUGGER) < ai.calls.indexOf(Agent.PLANNER))
        assertTrue(out.report.contains("जड़ कहाँ थी: src/Div.kt:2"))
        assertTrue(out.report.contains("नॉर्मल मोड"))
        assertFalse(out.report.contains("merge"))                       // नॉर्मल मोड: PR/merge नहीं
        assertTrue(f.staged()["src/Div.kt"]!!.contains("if (b == 0)"))
    }

    @Test fun failedTest_debuggerLoop_thenPass() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planOne, Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.GIT to gitMsg,
            Agent.DEBUGGER to """{"root_cause":"src/Calc.kt:7 - sub returns a + b","where":"src/Calc.kt:7","fix":"use minus","files":["src/Calc.kt"]}""",
            Agent.PLANNER to """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"fix sub","anchor":"fun sub"}]}""",
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\n    return a - b\nFIND>>>\n<<<REPLACE\n    return a - b // checked\nREPLACE>>>",
            Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(false, "CalcTest > sub FAILED\njava.lang.AssertionError: expected:<2> but was:<8>"), TeamTest(true, ""))))
        val out = SevenTeam(ai, files(), git) { _, _ -> }.run("sub() जोड़ो")
        assertEquals(true, out.testOk)
        assertEquals(2, git.log.count { it.startsWith("push") })
        assertTrue(ai.calls.contains(Agent.DEBUGGER))
        assertTrue(out.report.contains("क्या मैं PR को merge कर दूँ?"))
    }

    @Test fun ciNeverPasses_noReadyPr_noMergeQuestion() = runBlocking {
        val dbg = """{"root_cause":"src/Calc.kt:7 - same failure each time","where":"src/Calc.kt:7","fix":"x","files":["src/Calc.kt"]}"""
        val fixPlan = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"fix","anchor":"fun sub"}]}"""
        fun fixEdit(n: Int) = "FILE: src/Calc.kt\n<<<FIND\n    return a - b${if (n == 0) "" else " // $n"}\nFIND>>>\n<<<REPLACE\n    return a - b // ${n + 1}\nREPLACE>>>"
        val script = mutableListOf(Agent.MANAGER to managerFeature, Agent.PLANNER to planOne, Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        for (n in 0 until 3) script += listOf(Agent.DEBUGGER to dbg, Agent.PLANNER to fixPlan, Agent.CODER to fixEdit(n), Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        val ai = FakeAi(*script.toTypedArray())
        val same = TeamTest(false, "e: file:///src/Calc.kt:7:5 Unresolved reference: foo")
        val git = FakeGit(ArrayDeque(List(5) { same }))
        val out = SevenTeam(ai, files(), git) { _, _ -> }.run("sub() जोड़ो")
        assertEquals(false, out.testOk)
        assertFalse(git.log.contains("ready"))                          // PR review के लिए तैयार नहीं
        assertFalse(out.report.contains("क्या मैं PR को merge कर दूँ?"))
        assertTrue(out.report.contains("main अछूता"))
    }

    @Test fun question_answeredWithoutCodeChange() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"question","answer":"add() दो संख्याएँ जोड़ता है"}""")
        val out = SevenTeam(ai, files(), null) { _, _ -> }.run("add() क्या करता है?")
        assertTrue(out.answerOnly)
        assertEquals("add() दो संख्याएँ जोड़ता है", out.report)
    }

    @Test fun normalMode_newWebsite_filesWithNames() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"feature","brief":"hello page"}""",
            Agent.PLANNER to """{"steps":[{"file":"index.html","action":"create","change":"hello page"},{"file":"style.css","action":"create","change":"styles"}]}""",
            Agent.CODER to "FILE: index.html\n<<<FILE\n<!doctype html>\n<h1>नमस्ते</h1>\nFILE>>>", Agent.REVIEWER to pass,
            Agent.CODER to "FILE: style.css\n<<<FILE\nh1 { color: red; }\nFILE>>>", Agent.REVIEWER to pass,
            Agent.TESTER to """{"pass":true,"cases":["पेज खुलता है"]}""")
        val out = SevenTeam(ai, MemoryFiles(emptyMap()), null) { _, _ -> }.run("एक hello वेबसाइट बनाओ")
        assertEquals(Seven.Mode.NORMAL, out.mode)
        val text = Seven.formatFiles(out.files)
        assertTrue(text.contains("📄 index.html"))
        assertTrue(text.contains("📄 style.css"))
        assertTrue(text.contains("h1 { color: red; }"))
    }

    @Test fun badSyntax_rejectedBeforeApply() = runBlocking {
        val broken = "FILE: src/Calc.kt\n<<<FIND\n    return a + b\n}\nFIND>>>\n<<<REPLACE\n    return a + b\nREPLACE>>>"
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planOne, Agent.CODER to broken, Agent.CODER to goodEdit,
            Agent.REVIEWER to pass, Agent.TESTER to """{"pass":true}""")
        val f = files()
        SevenTeam(ai, f, null) { _, _ -> }.run("sub जोड़ो")
        assertTrue(Syntax.check("src/Calc.kt", f.staged()["src/Calc.kt"]!!) == null)
        assertTrue(ai.lastUser[Agent.CODER]!!.contains("syntax"))
    }

    @Test fun deletingOldTests_failsTester() = runBlocking {
        val t = "package app\nclass CalcTest {\n    @Test fun a() {}\n    @Test fun b() {}\n}\n"
        val ai = FakeAi(Agent.MANAGER to managerFeature,
            Agent.PLANNER to """{"steps":[{"file":"app/src/test/CalcTest.kt","action":"edit","change":"remove test b"}]}""",
            Agent.CODER to "FILE: app/src/test/CalcTest.kt\n<<<FIND\n    @Test fun b() {}\nFIND>>>\n<<<REPLACE\nREPLACE>>>", Agent.REVIEWER to pass,
            Agent.DEBUGGER to """{"root_cause":"app/src/test/CalcTest.kt - test removed instead of fixing code","where":"app/src/test/CalcTest.kt","fix":"restore","files":["app/src/test/CalcTest.kt"]}""",
            Agent.PLANNER to """{"steps":[{"file":"app/src/test/CalcTest.kt","action":"edit","change":"restore test b"}]}""",
            Agent.CODER to "FILE: app/src/test/CalcTest.kt\n<<<FIND\n    @Test fun a() {}\nFIND>>>\n<<<REPLACE\n    @Test fun a() {}\n    @Test fun b() {}\nREPLACE>>>", Agent.REVIEWER to pass,
            Agent.TESTER to """{"pass":true}""")
        val out = SevenTeam(ai, MemoryFiles(mapOf("app/src/test/CalcTest.kt" to t)), null) { _, _ -> }.run("CalcTest साफ़-सुथरा करो")
        // पहला टेस्ट (पक्की जाँच) test हटने पर फ़ेल → डिबगर → वापस
        assertTrue(ai.calls.contains(Agent.DEBUGGER))
        assertNull(out.files["app/src/test/CalcTest.kt"]?.let { if (Regex("@Test").findAll(it).count() < 2) "kam" else null })
    }
}
