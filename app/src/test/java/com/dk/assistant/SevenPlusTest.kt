package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.SevenTeamTest.FakeAi
import com.dk.assistant.SevenTeamTest.FakeGit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 11-एजेंट: प्रॉम्प्ट इंजीनियर (समझ + हाँ), नक्शा + खोज, जगह-जाँच, परफॉरमेंस, QA, डिप्लॉयमेंट */
class SevenPlusTest {

    private val calc = "package app\n\nclass Calc {\n    fun add(a: Int, b: Int): Int {\n        return a + b\n    }\n}\n"
    private val screen = "package app\n\nclass MainScreen {\n    private val title = \"नमस्ते\"\n    fun render() {\n        show(title)\n    }\n}\n"

    // ------------------------------------------------------------------ नक्शा + खोज
    @Test fun repoMap_listsClassesAndFunctions() {
        val m = SevenPlus.RepoMap.build(mapOf("src/Calc.kt" to calc, "src/MainScreen.kt" to screen, "README.md" to "# hi",
            "res/layout/main.xml" to "<Button android:id=\"@+id/btnSend\"/>"))
        assertTrue(m, m.contains("src/Calc.kt: class Calc, fun add"))
        assertTrue(m, m.contains("src/MainScreen.kt: class MainScreen") && m.contains("fun render"))
        assertTrue(m, m.contains("res/layout/main.xml: id/btnSend"))
        assertFalse(m.contains("README.md"))
    }

    @Test fun repoMap_isCappedAndSaysSo() {
        val ws = (1..400).associate { "src/F$it.kt" to "class F$it {\n    fun work$it() {}\n}\n" }
        val m = SevenPlus.RepoMap.build(ws, maxChars = 2_000)
        assertTrue(m.length < 2_200)
        assertTrue(m.contains("search"))
    }

    @Test fun repoSearch_findsExactPlace_andSaysNotFound() {
        val ws = mapOf("src/Calc.kt" to calc, "src/MainScreen.kt" to screen)
        val r = SevenPlus.RepoSearch.search(ws, listOf("नमस्ते", "doesNotExist"))
        assertTrue(r, r.contains("src/MainScreen.kt:4:"))
        assertTrue(r, r.contains("NOT FOUND"))
        assertEquals(listOf("render", "title"), SevenPlus.RepoSearch.terms("""{"search":["render","title"]}"""))
        assertNull(SevenPlus.RepoSearch.terms("""{"read":["a"]}"""))
    }

    // ------------------------------------------------------------------ जगह-जाँच
    @Test fun where_wrongAnchor_missingFile_existingCreate_areCaught() {
        val files = mapOf("src/Calc.kt" to calc)
        val plan = Seven.Plan(listOf(
            Seven.Step("src/Calc.kt", "edit", "add sub", "fun add(a: Int, b: Int)"),        // सही
            Seven.Step("src/Calc.kt", "edit", "x", "fun multiply("),                       // गलत anchor
            Seven.Step("src/Nope.kt", "edit", "x", "fun a"),                                // फ़ाइल नहीं
            Seven.Step("src/Calc.kt", "create", "x"),                                       // पहले से है
            Seven.Step("src/New.kt", "create", "new file"),                                 // ठीक
            Seven.Step("src/New.kt", "edit", "x", "")                                       // इसी प्लान में बनी - ठीक
        ), emptyList())
        val p = SevenPlus.Where.problems(plan) { files[it] }
        assertEquals(p.toString(), 3, p.size)
        assertTrue(p[0].startsWith("कदम 2") && p[0].contains("नहीं मिला"))
        assertTrue(p[1].startsWith("कदम 3"))
        assertTrue(p[2].startsWith("कदम 4") && p[2].contains("edit"))
    }

    @Test fun where_anchorIgnoresWhitespaceDifferences() {
        val plan = Seven.Plan(listOf(Seven.Step("src/Calc.kt", "edit", "x", "fun add(a: Int,   b: Int):\n Int")), emptyList())
        assertTrue(SevenPlus.Where.problems(plan) { calc }.isEmpty())
    }

    // ------------------------------------------------------------------ परफॉरमेंस + QA (पक्की जाँच)
    @Test fun perf_onlyNewLines_areChecked() {
        val old = "fun a() {\n    Thread.sleep(10)\n}\n"
        assertTrue(SevenPlus.Perf.scan("src/A.kt", old, old + "fun b() = 1\n").isEmpty())          // पुराना code नहीं छेड़ते
        val bad = old + "fun c() { GlobalScope.launch { } }\nfun d() { runBlocking { } }\n"
        val p = SevenPlus.Perf.scan("src/A.kt", old, bad)
        assertEquals(p.toString(), 2, p.size)                                   // GlobalScope + runBlocking; पुराना Thread.sleep नहीं गिना
        assertTrue(p.none { it.contains("Thread.sleep") })
    }

    @Test fun perf_findsSleepLoopAndLeak() {
        val p = SevenPlus.Perf.scan("src/A.kt", null, "fun a() {\n    while (true) {\n        x++\n    }\n    h.postDelayed(r, 100)\n    Thread.sleep(100)\n}\n")
        assertTrue(p.toString(), p.any { it.contains("while(true)") })
        assertTrue(p.toString(), p.any { it.contains("removeCallbacks") })
        assertTrue(p.toString(), p.any { it.contains("Thread.sleep") })
        assertTrue(SevenPlus.Perf.scan("src/test/ATest.kt", null, "Thread.sleep(1)").isEmpty())
    }

    @Test fun qa_catchesTodoAndFakeProgress() {
        val fake = "fun go() {\n    while (progress < 100) {\n        delay(2000L)\n        progress += 25\n    }\n    TODO(\"later\")\n}\n"
        val q = SevenPlus.Qa.scan("src/A.kt", null, fake)
        assertTrue(q.toString(), q.any { it.contains("नकली progress") })
        assertTrue(q.toString(), q.any { it.contains("TODO") })
        assertTrue(SevenPlus.Qa.scan("src/A.kt", null, calc).isEmpty())
    }

    // ------------------------------------------------------------------ हाँ / नहीं / सुधार
    @Test fun reply_yesNoOrCorrection() {
        for (y in listOf("हाँ", "हां", "Haan", "yes", "ok", "हाँ, सही समझा", "हाँ शुरू करो", "ठीक है", "🎤 हाँ", "jee haan".replace("jee", "ji")))
            assertEquals(y, SevenPlus.Reply.YES, SevenPlus.reply(y))
        for (n in listOf("नहीं", "रद्द", "cancel", "नहीं करो", "मत करो", "no"))
            assertEquals(n, SevenPlus.Reply.NO, SevenPlus.reply(n))
        for (o in listOf("हाँ लेकिन बटन लाल रखना", "नहीं, title नीला चाहिए", "बटन ऊपर लगाओ", ""))
            assertEquals(o, SevenPlus.Reply.OTHER, SevenPlus.reply(o))
    }

    private fun u(questions: List<String> = emptyList()) = SevenPlus.Understanding(Seven.Kind.FEATURE, "b", "समझ", "spec", listOf("a"), emptyList(), questions, emptyList())

    @Test fun resolve_yesStartsApprovedWork_correctionReUnderstands_noCancels() {
        assertEquals(Triple("नया काम", null, false), SevenPlus.resolve("नया काम", null, null, false, false))
        val ok = SevenPlus.resolve("हाँ", "बटन जोड़ो", u(), true, false)!!
        assertEquals("बटन जोड़ो", ok.first); assertNotNull(ok.second); assertTrue(ok.third)
        val fix = SevenPlus.resolve("बटन लाल हो", "बटन जोड़ो", u(), false, false)!!
        assertNull(fix.second); assertTrue(fix.first.contains("बटन जोड़ो") && fix.first.contains("CORRECTION") && fix.first.contains("बटन लाल हो"))
        // सवाल बाकी थे: "हाँ" भी जवाब माना जाता है - दोबारा समझो, सीधे काम नहीं
        val q = SevenPlus.resolve("हाँ", "बटन जोड़ो", u(listOf("कौन-सी स्क्रीन?")), false, false)!!
        assertNull(q.second); assertTrue(q.first.contains("ANSWER"))
        assertNull(SevenPlus.resolve("रद्द", "बटन जोड़ो", u(), false, false))
    }

    @Test fun understanding_hindiMessage_asksForYes_orShowsQuestions() {
        val m = u().hindi()
        assertTrue(m, m.contains("मैंने आपकी बात ऐसे समझी") && m.contains("\"हाँ\""))
        val q = u(listOf("कौन-सी स्क्रीन?")).hindi()
        assertTrue(q, q.contains("1. कौन-सी स्क्रीन?") && !q.contains("\"हाँ\" लिखें/बोलें तो काम शुरू"))
    }

    // ------------------------------------------------------------------ डिप्लॉयमेंट
    private val buildYml = "name: Build DK APK\non:\n  push:\n    branches: [ main ]\n  workflow_dispatch:\njobs:\n  b:\n    steps:\n      - run: gh release create v1 DK.apk\n"
    private val checkYml = "name: Check\non:\n  pull_request:\n  workflow_dispatch:\njobs:\n  t:\n    steps:\n      - run: gradle test\n"

    @Test fun deploy_picksTheReleaseWorkflow() {
        assertEquals("build.yml", SevenPlus.Deploy.releaseWorkflow(listOf("check.yml" to checkYml, "build.yml" to buildYml)))
        assertNull(SevenPlus.Deploy.releaseWorkflow(listOf("check.yml" to checkYml)))
    }

    @Test fun deploy_verify_releaseMustBeNewAndHaveApk() {
        val merged = 1_000_000_000L
        val good = SevenPlus.Deploy.Rel("v74", "DK 3.34", "main", listOf("DK.apk"), "u", merged + 120_000)
        assertEquals("", SevenPlus.Deploy.verify(good, "c23e813f556f", merged))
        assertTrue(SevenPlus.Deploy.verify(good.copy(publishedMs = merged - 600_000), "c23e813", merged).contains("पहले की"))
        assertTrue(SevenPlus.Deploy.verify(good.copy(assets = listOf("notes.txt")), "c23e813", merged).contains("APK"))
        assertTrue(SevenPlus.Deploy.verify(good.copy(target = "abcdef1234"), "c23e813", merged).contains("नहीं है"))
        assertTrue(SevenPlus.Deploy.verify(null, "c23e813", merged).isNotBlank())
    }

    // ------------------------------------------------------------------ पूरा 11-एजेंट क्रम (नकली AI)
    private val calcFile = "package app\n\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private val manager = """{"kind":"feature","brief":"add sub()","acceptance":["sub(5,3)=2"]}"""
    private val understood = """{"understood":"Calc में घटाने का sub() जोड़ना है","spec":"Add fun sub(a,b)=a-b in src/Calc.kt next to add()","acceptance":["sub(5,3)=2","add() वैसा ही"],"not_touch":["add()"],"area":["src/Calc.kt - add() यहीं है"],"questions":[]}"""
    private val planOne = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub function","anchor":"fun add","why":"add() इसी फ़ाइल में है"}]}"""
    private val goodEdit = "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\nFIND>>>\n<<<REPLACE\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n\nfun sub(a: Int, b: Int): Int {\n    return a - b\n}\nREPLACE>>>"
    private val pass = """{"pass":true,"problems":[]}"""
    private val perfOk = """{"pass":true,"must":[],"should":[]}"""
    private val qaOk = """{"pass":true,"checks":["sub(5,3)=2 → ✓ src/Calc.kt:7"],"missing":[]}"""

    @Test fun understand_promptEngineerSearches_thenGivesSpec_noChangesYet() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to manager, Agent.PROMPT to """{"search":["fun add"]}""", Agent.PROMPT to understood)
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcFile))
        val git = FakeGit(ArrayDeque())
        val u = SevenTeam(ai, f, git, full = true) { _, _ -> }.understand("Calc में sub जोड़ो")
        assertEquals(listOf(Agent.MANAGER, Agent.PROMPT, Agent.PROMPT), ai.calls)
        assertTrue(ai.prompts[Agent.PROMPT]!!.startsWith("You are the PROMPT ENGINEER"))
        assertTrue(ai.lastUser[Agent.PROMPT]!!.contains("src/Calc.kt:3:"))       // खोज का असली नतीजा मिला
        assertEquals("Calc में घटाने का sub() जोड़ना है", u.understood)
        assertEquals(listOf("add()"), u.notTouch)
        assertFalse(u.needsAnswer)
        assertTrue(f.staged().isEmpty())                                          // समझते समय कुछ नहीं बदला
        assertTrue(git.log.isEmpty())                                             // न backup, न branch
    }

    @Test fun eleven_fullOrder_withApprovedUnderstanding() = runBlocking {
        val ai = FakeAi(Agent.PLANNER to """{"read":["src/Calc.kt"]}""", Agent.PLANNER to planOne, Agent.CODER to goodEdit, Agent.REVIEWER to pass,
            Agent.PERF to perfOk, Agent.QA to qaOk, Agent.GIT to """{"commit":"Add sub()","title":"sub() जोड़ा"}""")
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcFile))
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.FEATURE, "add sub()", emptyList())
        val out = SevenTeam(ai, f, git, full = true) { _, _ -> }.run("Calc में sub जोड़ो", pre)
        // मंज़ूर समझ = मैनेजर दोबारा नहीं; परफॉरमेंस कोडिंग के बाद, QA build पास होने के बाद और PR से पहले
        assertEquals(listOf(Agent.PLANNER, Agent.PLANNER, Agent.CODER, Agent.REVIEWER, Agent.PERF, Agent.GIT, Agent.QA), ai.calls)
        assertEquals(listOf("backup", "branch", "push:Add sub()", "ready"), git.log)
        assertTrue(ai.lastUser[Agent.QA]!!.contains("sub(5,3)=2"))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("fun sub(") && f.staged()["src/Calc.kt"]!!.contains("fun add("))
        assertEquals(true, out.testOk)
        assertTrue(out.report, out.report.contains("✅ QA की जाँच") && out.report.contains("🚀 डिप्लॉयमेंट"))
        assertTrue(out.report.contains("आपकी हाँ के बिना"))
    }

    @Test fun planner_wrongAnchor_isSentBack_beforeAnyCode() = runBlocking {
        val wrong = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub","anchor":"fun multiply("}]}"""
        val ai = FakeAi(Agent.PLANNER to wrong, Agent.PLANNER to """{"search":["fun add"]}""", Agent.PLANNER to planOne,
            Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.PERF to perfOk, Agent.TESTER to """{"pass":true}""", Agent.QA to qaOk)
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcFile))
        val events = mutableListOf<String>()
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.FEATURE, "", emptyList())
        val out = SevenTeam(ai, f, null, full = true) { a, t -> events.add("${a.name}:$t") }.run("Calc में sub जोड़ो", pre)
        assertEquals(3, ai.calls.count { it == Agent.PLANNER })
        assertEquals(1, ai.calls.count { it == Agent.CODER })                      // गलत जगह पर कोडर कभी नहीं बुलाया
        assertTrue(events.any { it.startsWith("PLANNER:") && it.contains("जगह गलत") })
        assertEquals(true, out.testOk)
    }

    @Test fun qa_rejects_thenFixIsPlanned_andRetested() = runBlocking {
        val qaNo = """{"pass":false,"checks":["sub(5,3)=2 → ✗"],"missing":["sub() कहीं से बुलाया नहीं गया"],"fix":"call sub from main"}"""
        val fixPlan = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"document sub","anchor":"fun sub"}]}"""
        val fixEdit = "FILE: src/Calc.kt\n<<<FIND\nfun sub(a: Int, b: Int): Int {\nFIND>>>\n<<<REPLACE\n/** a - b */\nfun sub(a: Int, b: Int): Int {\nREPLACE>>>"
        val ai = FakeAi(Agent.PLANNER to planOne, Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.PERF to perfOk,
            Agent.TESTER to """{"pass":true}""", Agent.QA to qaNo,
            Agent.PLANNER to fixPlan, Agent.CODER to fixEdit, Agent.REVIEWER to pass,
            Agent.TESTER to """{"pass":true}""", Agent.QA to qaOk)
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcFile))
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.FEATURE, "", emptyList())
        val out = SevenTeam(ai, f, null, full = true) { _, _ -> }.run("Calc में sub जोड़ो", pre)
        assertEquals(2, ai.calls.count { it == Agent.QA })
        assertTrue(ai.lastUser[Agent.PLANNER]!!.contains("QA FOUND THE TASK INCOMPLETE"))
        assertTrue(ai.lastUser[Agent.PLANNER]!!.contains("sub() कहीं से बुलाया नहीं गया"))
        assertTrue(f.staged()["src/Calc.kt"]!!.contains("/** a - b */"))
        assertEquals(true, out.testOk)
    }

    @Test fun qa_keepsRejecting_stopsAndReports_neverClaimsDone() = runBlocking {
        // v5.2 (मालिक: "max 3 की सीमा हटाओ"): QA कमी बताता रहे तो टीम रुकती नहीं; रुकती सिर्फ़ तब जब हूबहू वही कमी
        // लगातार FixLoop.TEAM_SAME_LIMIT बार लौटे - और तब भी कभी "काम पूरा" नहीं कहती
        val qaNo = """{"pass":false,"missing":["अधूरा"]}"""
        val first = "FILE: src/Calc.kt\n<<<FIND\nfun sub(a: Int, b: Int): Int {\nFIND>>>\n<<<REPLACE\n// v1\nfun sub(a: Int, b: Int): Int {\nREPLACE>>>"
        fun edit(k: Int) = "FILE: src/Calc.kt\n<<<FIND\n// v$k\nFIND>>>\n<<<REPLACE\n// v${k + 1}\nREPLACE>>>"
        fun plan(anchor: String) = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"x","anchor":"$anchor"}]}"""
        val script = mutableListOf(Agent.PLANNER to planOne, Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.PERF to perfOk,
            Agent.TESTER to """{"pass":true}""", Agent.QA to qaNo)
        for (r in 1 until FixLoop.TEAM_SAME_LIMIT) {
            script += listOf(Agent.PLANNER to plan(if (r == 1) "fun sub" else "// v${r - 1}"), Agent.CODER to (if (r == 1) first else edit(r - 1)),
                Agent.REVIEWER to pass, Agent.TESTER to """{"pass":true}""", Agent.QA to qaNo)
        }
        val ai = FakeAi(*script.toTypedArray())
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcFile))
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.FEATURE, "", emptyList())
        val out = SevenTeam(ai, f, null, full = true) { _, _ -> }.run("Calc में sub जोड़ो", pre)
        assertEquals(FixLoop.TEAM_SAME_LIMIT, ai.calls.count { it == Agent.QA })
        assertFalse(out.testOk == true)
        assertTrue(out.report, out.report.contains("QA"))
        assertTrue(out.report, out.report.contains("क्या-क्या आज़माया"))
    }

    @Test fun perf_mustProblem_getsOneFixRound() = runBlocking {
        val slowEdit = "FILE: src/Calc.kt\n<<<FIND\n    return a + b\nFIND>>>\n<<<REPLACE\n    Thread.sleep(50)\n    return a + b\nREPLACE>>>"
        val plan = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"wait","anchor":"return a + b"}]}"""
        val fixPlan = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"remove sleep","anchor":"Thread.sleep(50)"}]}"""
        val fixEdit = "FILE: src/Calc.kt\n<<<FIND\n    Thread.sleep(50)\n    return a + b\nFIND>>>\n<<<REPLACE\n    return a + b\nREPLACE>>>"
        val ai = FakeAi(Agent.PLANNER to plan, Agent.CODER to slowEdit, Agent.REVIEWER to pass, Agent.PERF to perfOk,
            Agent.PLANNER to fixPlan, Agent.CODER to fixEdit, Agent.REVIEWER to pass,
            Agent.TESTER to """{"pass":true}""", Agent.QA to qaOk)
        val f = MemoryFiles(mapOf("src/Calc.kt" to calcFile))
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.FEATURE, "", emptyList())
        val events = mutableListOf<String>()
        SevenTeam(ai, f, null, full = true) { a, t -> events.add("${a.name}:$t") }.run("धीरे करो", pre)
        assertTrue(events.any { it.startsWith("PERF:") && it.contains("Thread.sleep") })
        assertFalse(f.staged()["src/Calc.kt"]!!.contains("Thread.sleep"))
    }

    @Test fun oldSevenMode_unchanged_noNewAgentsCalled() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to manager, Agent.PLANNER to planOne, Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.TESTER to """{"pass":true}""")
        SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calcFile)), null) { _, _ -> }.run("sub जोड़ो")
        assertTrue(ai.calls.none { it == Agent.PROMPT || it == Agent.PERF || it == Agent.QA || it == Agent.DEPLOY })
    }
}
