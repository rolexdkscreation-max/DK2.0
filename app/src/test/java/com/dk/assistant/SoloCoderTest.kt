package com.dk.assistant

import com.dk.assistant.Seven.Agent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक: "एक ही एजेंट सब काम कर लेगा... पूरा फिनिश करके ही दे"): एक-दिमाग वाला कोडर ([Solo]).
 * एक ही एजेंट पूरी बातचीत याद रखकर खोजता/पढ़ता/बदलता/जाँचता है; CI की गलती उसी बातचीत में लौटती है.
 */
class SoloCoderTest {

    /** हर एजेंट के तय जवाब; हर कॉल का पूरा संदेश-इतिहास दर्ज (एक ही बातचीत चलती है या नहीं, यह जाँचने को) */
    class FakeAi(vararg script: Pair<Agent, String>) : TeamAi {
        private val q = script.groupBy({ it.first }, { it.second }).mapValues { ArrayDeque(it.value) }.toMutableMap()
        val calls = mutableListOf<Agent>()
        val history = mutableListOf<List<Pair<String, String>>>()
        val systems = mutableListOf<String>()
        override suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String {
            calls.add(agent); history.add(msgs.toList()); systems.add(system)
            return q[agent]?.removeFirstOrNull() ?: error("$agent के लिए जवाब नहीं बचा (calls: $calls)")
        }
    }

    class FakeGit(private val tests: ArrayDeque<TeamTest>) : TeamGit {
        val log = mutableListOf<String>()
        val branchTasks = mutableListOf<String>()
        override suspend fun backup(task: String) = "backup/dk-1112-1500".also { log.add("backup") }
        override suspend fun branch(task: String) = "feature/dk-x".also { log.add("branch"); branchTasks.add(task) }
        override suspend fun pushAndTest(commit: String, title: String, summary: String, rootCause: String, onStep: (String) -> Unit): TeamTest {
            log.add("push:$commit"); return tests.removeFirst()
        }
        override suspend fun ready() = "https://github.com/u/r/pull/9".also { log.add("ready") }
        override val prUrl = "https://github.com/u/r/pull/9"
    }

    private val calc = "package app\n\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private fun files() = MemoryFiles(mapOf("src/Calc.kt" to calc))
    private val managerFeature = """{"kind":"feature","brief":"add sub()","acceptance":["sub(5,3)=2"]}"""

    private val addSub = """FILE: src/Calc.kt
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
REPLACE>>>
CHECK"""

    private fun team(ai: TeamAi, f: TeamFiles, git: TeamGit?, ev: (Agent, String) -> Unit = { _, _ -> }) =
        SevenTeam(ai, f, git, ev = ev).apply { solo = true }

    // ------------------------------------------------------------------ जवाब पढ़ना

    @Test fun parse_manyCommandsInOneReply() {
        val acts = Solo.parse("READ: src/A.kt, src/B.kt:10-40\nGREP: fun sub\\( IN: src\nLIST: app/src\n" + addSub +
            "\nDONE: Add sub function\nsub() जोड़ा।\nअब घटाना भी होगा।")
        assertEquals(Solo.Act.Read("src/A.kt"), acts[0])
        assertEquals(Solo.Act.Read("src/B.kt", 10, 40), acts[1])
        assertEquals(Solo.Act.Grep("fun sub\\(", "src"), acts[2])
        assertEquals(Solo.Act.ListFiles("app/src"), acts[3])
        val e = acts[4] as Solo.Act.Edit
        assertEquals("src/Calc.kt", e.path)
        assertEquals(1, e.pairs.size)
        assertTrue(e.pairs[0].second.contains("fun sub("))
        assertEquals(Solo.Act.Check, acts[5])
        val d = acts[6] as Solo.Act.Done
        assertEquals("Add sub function", d.title)
        assertEquals("sub() जोड़ा।\nअब घटाना भी होगा।", d.summary)
        assertEquals(7, acts.size)
    }

    @Test fun parse_newFileAndDelete_keepCodeLinesThatLookLikeCommands() {
        val acts = Solo.parse("FILE: res/drawable/bg.xml\n<<<FILE\n<shape>\nDONE: not a command inside a block\n</shape>\nFILE>>>\nDELETE: src/Old.kt")
        val w = acts[0] as Solo.Act.Write
        assertEquals("res/drawable/bg.xml", w.path)
        assertTrue(w.content.contains("DONE: not a command inside a block"))
        assertEquals(Solo.Act.Delete("src/Old.kt"), acts[1])
        assertEquals(2, acts.size)
    }

    @Test fun parse_brokenBlocksAreReported_notSilentlyDropped() {
        assertTrue(Solo.parse("FILE: a.kt\n<<<FIND\nx\nFIND>>>").first() is Solo.Act.Bad)            // REPLACE नहीं
        assertTrue(Solo.parse("FILE: a.kt\n<<<FIND\nx").any { it is Solo.Act.Bad })                    // घेरा बंद नहीं (जवाब कटा)
        assertTrue(Solo.parse("<<<FIND\nx\nFIND>>>\n<<<REPLACE\ny\nREPLACE>>>").first() is Solo.Act.Bad) // FILE: नहीं
        assertTrue(Solo.parse("मैं अभी सोच रहा हूँ").isEmpty())
    }

    // ------------------------------------------------------------------ बदलाव लागू करना

    @Test fun apply_exact_loose_ambiguous_missing() {
        val src = "fun a() {\n    x()\n}\nfun b() {\n    x()\n}\n"
        assertEquals("fun a() {\n    y()\n}\nfun b() {\n    x()\n}\n", Solo.apply(src, listOf("fun a() {\n    x()" to "fun a() {\n    y()")).content)
        // AI ने indentation बदल दी - लाइनें वही हों तो भी लगे (ठीक एक जगह)
        val loose = Solo.apply(src, listOf("fun b() {\n  x()\n}" to "fun b() {\n    z()\n}")).content
        assertTrue(loose!!.contains("z()") && loose.contains("fun a() {\n    x()"))
        // दो जगह मिला = मना (गलत जगह बदलने से बेहतर)
        val two = Solo.apply(src, listOf("    x()" to "    q()"))
        assertNull(two.content); assertTrue(two.errors.first().contains("2 जगह"))
        // न मिला = कुछ भी लागू नहीं (आधा बदलाव कभी नहीं)
        val miss = Solo.apply(src, listOf("fun a() {" to "fun a2() {", "nothing()" to "x"))
        assertNull(miss.content); assertTrue(miss.errors.single().contains("नहीं मिला"))
    }

    @Test fun grep_and_numbered() {
        val ws = mapOf("app/src/A.kt" to "val a = 1\nfun go() {}\n", "app/src/B.kt" to "fun go2() {}\n", "docs/x.md" to "go")
        assertEquals(listOf("app/src/A.kt:2: fun go() {}", "app/src/B.kt:1: fun go2() {}"), Solo.grep(ws, "fun go", "app/"))
        assertEquals(1, Solo.grep(ws, "go2(", "").size)        // गलत regex = सीधा शब्द
        val n = Solo.numbered("a.kt", "l1\nl2\nl3", 2, 3)
        assertTrue(n.contains("2| l2") && n.contains("3| l3") && !n.contains("1| l1"))
        assertTrue(Solo.numbered("a.kt", (1..500).joinToString("\n") { "line $it" }, maxChars = 300).contains("READ: a.kt:"))
    }

    // ------------------------------------------------------------------ पूरा काम, एक ही एजेंट

    @Test fun oneAgent_doesWholeTask_thenCiAndPr() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature,
            Agent.CODER to "READ: src/Calc.kt\nGREP: fun add",
            Agent.CODER to addSub,
            Agent.CODER to "DONE: Add sub function\nCalc में sub() जोड़ा।")
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1", stage = ""))))
        val f = files()
        val out = team(ai, f, git).run("Calc में sub() फ़ंक्शन जोड़ो")
        // कोई प्लानर/रिव्यूअर/डिबगर/गिट-AI नहीं - सिर्फ़ समझ + एक ही कोडर
        assertEquals(listOf(Agent.MANAGER, Agent.CODER, Agent.CODER, Agent.CODER), ai.calls)
        assertEquals(listOf("backup", "branch", "push:DK: Add sub function", "ready"), git.log)
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun sub("))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun add("))
        assertEquals(true, out.testOk)
        // दूसरी कॉल ने पहली कॉल की पढ़ी फ़ाइल (पंक्ति-नंबर के साथ) देखी - वही बातचीत
        val second = ai.history[2].last().second
        assertTrue(second, second.contains("1| package app") && second.contains("=== SEARCH fun add"))
        assertTrue(ai.systems[1].contains("You do the WHOLE coding task ALONE"))
    }

    @Test fun ciFailure_goesBackToSameAgent_sameConversation() = runBlocking {
        val bad = addSub.replace("return a - b", "return a + b")      // नाम सही, पर तर्क गलत - सिर्फ़ CI का test पकड़ता है
        val ai = FakeAi(Agent.MANAGER to managerFeature,
            Agent.CODER to "READ: src/Calc.kt",
            Agent.CODER to bad + "\nDONE: Add sub\nजोड़ा।",
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\nfun sub(a: Int, b: Int): Int {\n    return a + b\nFIND>>>\n<<<REPLACE\nfun sub(a: Int, b: Int): Int {\n    return a - b\nREPLACE>>>\nDONE: Add sub\nठीक किया।")
        val git = FakeGit(ArrayDeque(listOf(
            TeamTest(false, "CalcTest > sub FAILED\n    java.lang.AssertionError: expected:<2> but was:<8>", "https://ci/1"),
            TeamTest(true, "", "https://ci/2"))))
        val f = files()
        val out = team(ai, f, git).run("Calc में sub() फ़ंक्शन जोड़ो")
        assertEquals(true, out.testOk)
        assertEquals(listOf(Agent.MANAGER, Agent.CODER, Agent.CODER, Agent.CODER), ai.calls)     // डिबगर/प्लानर नहीं
        val fixCall = ai.history[3]
        assertTrue(fixCall.last().second.contains("BUILD / TESTS FAILED") && fixCall.last().second.contains("expected:<2> but was:<8>"))
        // वही बातचीत: पहले का पढ़ना और पहला बदलाव भी उसी इतिहास में
        assertTrue(fixCall.any { it.first == "model" && it.second.contains("return a + b") && it.second.contains("fun sub(") })
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("return a - b"))
        assertEquals(2, git.log.count { it.startsWith("push:") })
    }

    @Test fun doneWithoutChange_isNotAccepted() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature,
            Agent.CODER to "DONE: nothing\nहो गया।",
            Agent.CODER to addSub + "\nDONE: Add sub\nजोड़ा।")
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val out = team(ai, files(), git).run("Calc में sub() जोड़ो")
        assertEquals(true, out.testOk)
        assertTrue(ai.history[2].last().second.contains("NOTHING CHANGED"))
    }

    @Test fun binaryImage_refused_withVectorHint() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature,
            Agent.CODER to "FILE: res/drawable/wp.png\n<<<FILE\nnot an image\nFILE>>>",
            Agent.CODER to "FILE: res/drawable/wp.xml\n<<<FILE\n<vector android:width=\"24dp\"/>\nFILE>>>\nDONE: Add wallpaper\nवॉलपेपर जोड़ा।")
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val f = files()
        team(ai, f, git).run("नया वॉलपेपर जोड़ो")
        assertTrue(ai.history[2].last().second.contains("REFUSED res/drawable/wp.png"))
        assertTrue(ai.history[2].last().second.contains("vector drawable"))
        assertFalse("res/drawable/wp.png" in f.staged())
        assertTrue("res/drawable/wp.xml" in f.staged())
    }

    @Test fun ask_stopsWithQuestion_noPush() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.CODER to "ASK: किस API key से जोड़ूँ?")
        val git = FakeGit(ArrayDeque())
        val out = team(ai, files(), git).run("पेमेंट जोड़ो")
        assertEquals(false, out.testOk)
        assertTrue(out.report, out.report.contains("किस API key से जोड़ूँ?"))
        assertTrue(git.log.none { it.startsWith("push:") })
    }

    @Test fun oldSevenTeam_unchanged_whenSoloOff() {
        assertFalse(SevenTeam(FakeAi(), files(), null) { _, _ -> }.solo)
    }

    // ------------------------------------------------------------------ तुरंत शुरू + "आगे करो" की branch

    @Test fun startsAtOnce_onlyWhenSoloAndNoQuestion() {
        assertTrue(SoloStart.now(solo = true, needsAnswer = false))
        assertFalse(SoloStart.now(solo = true, needsAnswer = true))
        assertFalse(SoloStart.now(solo = false, needsAnswer = false))
    }

    @Test fun branchName_neverFromResumeNote() {
        val u = SevenPlus.Understanding(Seven.Kind.FEATURE, "वॉलपेपर", "वॉलपेपर जोड़ना", "spec",
            listOf("वॉलपेपर दिखे"), emptyList(), emptyList(), emptyList(), "", emptyList(), emptyList(), "")
        val k = CoderHistory.Checkpoint("t1", "वॉलपेपर जोड़ो", u, "pause", false, "u/r", "main", branch = "")
        val work = "वॉलपेपर जोड़ो" + CoderHistory.Logic.resumeNote(k)
        assertTrue(work.contains("RESUME"))
        assertEquals("वॉलपेपर जोड़ो", CoderHistory.Logic.withoutResumeNote(work))
        assertEquals("add dark mode", CoderHistory.Logic.withoutResumeNote("add dark mode"))
    }
}
