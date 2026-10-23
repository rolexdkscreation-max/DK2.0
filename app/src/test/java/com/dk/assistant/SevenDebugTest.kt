package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.SevenTeamTest.FakeAi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 11-एजेंट डिबगर: खोज, लाइन नंबर, बड़ी फ़ाइल के हिस्से, रिपो का नक्शा / diff, और जड़ की पक्की जाँच */
class SevenDebugTest {

    // ------------------------------------------------------------------ पढ़ना
    @Test fun parseRead_pathAndRanges() {
        assertEquals(SevenDebug.ReadReq("src/A.kt"), SevenDebug.parseRead("src/A.kt"))
        assertEquals(SevenDebug.ReadReq("src/A.kt", 120, 260), SevenDebug.parseRead("src/A.kt:120-260"))
        assertEquals(SevenDebug.ReadReq("src/A.kt", 5, 9), SevenDebug.parseRead("./src/A.kt#L5-L9"))
        assertEquals(SevenDebug.ReadReq("src/A.kt", 3, 8), SevenDebug.parseRead("src/A.kt:8-3"))
    }

    @Test fun numbered_showsRealLineNumbers() {
        val n = SevenDebug.numbered("a\nb\nc", 2, 3)
        assertEquals("2| b\n3| c", n)
        assertEquals("", SevenDebug.numbered("a", 5, 9))
    }

    @Test fun block_smallFile_wholeAndNumbered() {
        val b = SevenDebug.block(SevenDebug.ReadReq("x.kt"), "fun a() {}\nfun b() {}", 10_000, 40_000)
        assertTrue(b.text, b.text.contains("1| fun a() {}") && b.text.contains("2| fun b() {}"))
        assertEquals(1, b.from); assertEquals(2, b.to)
    }

    @Test fun block_bigFile_isCut_withOutline_andRangeReadsTheRest() {
        val src = (1..3000).joinToString("\n") { if (it == 2500) "    fun deepBug() {" else "    val v$it = $it" }
        val cut = SevenDebug.block(SevenDebug.ReadReq("Big.kt"), src, 150_000, 20_000)
        assertTrue(cut.text.contains("TRUNCATED"))
        assertTrue(cut.to < 2500)
        assertTrue(cut.text, cut.text.contains("2500: fun deepBug"))                // रूपरेखा बताती है कि बग वाला fun कहाँ है
        val part = SevenDebug.block(SevenDebug.parseRead("Big.kt:2490-2510"), src, 150_000, 20_000)
        assertTrue(part.text, part.text.contains("2500|     fun deepBug() {"))
        assertEquals(2490, part.from); assertEquals(2510, part.to)
    }

    @Test fun block_missingFile_saysSearch() {
        val b = SevenDebug.block(SevenDebug.ReadReq("nope.kt"), null, 1000, 1000)
        assertTrue(b.text.contains("FILE NOT FOUND") && b.text.contains("search"))
        assertEquals(0, b.to)
    }

    // ------------------------------------------------------------------ जड़ की जाँच
    private val sizes = mapOf("src/Div.kt" to 3)
    private fun j(s: String) = Seven.json(s)!!

    @Test fun verify_rejectsUnreadFile_missingFile_wrongLine_andUnseenLine() {
        val seen = SevenDebug.Seen()
        val ok = j("""{"root_cause":"src/Div.kt:2 - a / b crashes","where":"src/Div.kt:2","fix":"guard","files":["src/Div.kt"]}""")
        assertTrue(SevenDebug.verify(ok, seen) { sizes[it] }.first().contains("did not READ"))
        val ghost = j("""{"root_cause":"src/Ghost.kt:2 - x","where":"src/Ghost.kt:2","fix":"y"}""")
        assertTrue(SevenDebug.verify(ghost, seen) { sizes[it] }.first().contains("does not exist"))
        seen.add("src/Div.kt", 1, 1)
        assertTrue(SevenDebug.verify(ok, seen) { sizes[it] }.first().contains("not in the part you read"))
        val far = j("""{"root_cause":"src/Div.kt:99 - x","where":"src/Div.kt:99","fix":"y"}""")
        assertTrue(SevenDebug.verify(far, seen) { sizes[it] }.first().contains("only 3 lines"))
        seen.add("src/Div.kt", 1, 3)
        assertTrue(SevenDebug.verify(ok, seen) { sizes[it] }.isEmpty())
    }

    @Test fun verify_badFilesList_unlessNewFile() {
        val seen = SevenDebug.Seen().apply { add("src/Div.kt", 1, 3) }
        val bad = j("""{"root_cause":"src/Div.kt:2 - x","where":"src/Div.kt:2","fix":"guard","files":["src/Nope.kt"]}""")
        assertTrue(SevenDebug.verify(bad, seen) { sizes[it] }.any { it.contains("do not exist") })
        val create = j("""{"root_cause":"src/Div.kt:2 - x","where":"src/Div.kt:2","fix":"create new file src/Nope.kt","files":["src/Nope.kt"]}""")
        assertTrue(SevenDebug.verify(create, seen) { sizes[it] }.isEmpty())
    }

    @Test fun location_parsesWhere() {
        assertEquals("app/src/A.kt" to 12, SevenDebug.location("app/src/A.kt:12 - wrong"))
        assertEquals("A.kt" to null, SevenDebug.location("A.kt"))
        assertNull(SevenDebug.location("कोई फ़ाइल नहीं"))
    }

    // ------------------------------------------------------------------ पूरा क्रम (11-एजेंट, बग)
    private val buggy = "fun div(a: Int, b: Int): Int {\n    return a / b\n}\n"
    private val understood = """{"understood":"0 से भाग पर crash ठीक","spec":"div must not crash when b == 0","acceptance":["div(1,0) crash नहीं"],"not_touch":["add()"],"area":["src/Div.kt - div()"],"questions":[]}"""

    @Test fun fullBug_guessedRootIsSentBack_thenSearchRead_thenFix() = runBlocking {
        val guess = """{"root_cause":"src/Div.kt:2 - a / b crashes when b is 0","bug":"crash","where":"src/Div.kt:2","fix":"guard b == 0","files":["src/Div.kt"]}"""
        val ai = FakeAi(
            Agent.DEBUGGER to guess,                                         // बिना पढ़े अंदाज़ा -> वापस
            Agent.DEBUGGER to """{"search":["fun div"]}""",
            Agent.DEBUGGER to """{"read":["src/Div.kt"]}""",
            Agent.DEBUGGER to guess,                                         // अब पढ़कर -> पास
            Agent.PLANNER to """{"steps":[{"file":"src/Div.kt","action":"edit","change":"guard b == 0","anchor":"return a / b"}]}""",
            Agent.CODER to "FILE: src/Div.kt\n<<<FIND\n    return a / b\nFIND>>>\n<<<REPLACE\n    return if (b == 0) 0 else a / b\nREPLACE>>>",
            Agent.REVIEWER to """{"pass":true,"problems":[]}""", Agent.PERF to """{"pass":true,"must":[],"should":[]}""",
            Agent.TESTER to """{"pass":true}""", Agent.QA to """{"pass":true,"checks":["div(1,0) → ✓"],"missing":[]}""")
        val f = MemoryFiles(mapOf("src/Div.kt" to buggy, "src/Calc.kt" to "fun add(a: Int, b: Int) = a + b\n"))
        val events = mutableListOf<String>()
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.BUG, "", emptyList())
        val out = SevenTeam(ai, f, null, full = true) { a, t -> events.add("${a.name}:$t") }.run("div 0 पर crash हो रहा है", pre)

        assertEquals(4, ai.calls.count { it == Agent.DEBUGGER })
        assertTrue(events.any { it.startsWith("DEBUGGER:") && it.contains("पक्की नहीं") })
        // डिबगर के prompt में खोज + लाइन नंबर + हिस्से पढ़ने के नियम; पुराना "EXACT error lines" नियम भी
        val sys = ai.prompts[Agent.DEBUGGER]!!
        assertTrue(sys.contains("\"search\"") && sys.contains("LINE NUMBERS") && sys.contains("START-END") && sys.contains("EXACT error lines"))
        assertTrue(ai.calls.indexOf(Agent.DEBUGGER) < ai.calls.indexOf(Agent.PLANNER))
        assertTrue(f.staged()["src/Div.kt"]!!.contains("if (b == 0)"))
        assertTrue(out.report, out.report.contains("जड़ कहाँ थी: src/Div.kt:2"))
        assertEquals(true, out.testOk)
    }

    @Test fun fullBug_firstMessage_hasRepoMap_area_andNumberedRead() = runBlocking {
        val seenMsgs = mutableListOf<String>()
        val script = ArrayDeque(listOf(
            """{"read":["src/Div.kt"]}""",
            """{"root_cause":"src/Div.kt:2 - a / b crashes when b is 0","where":"src/Div.kt:2","fix":"guard","files":["src/Div.kt"]}"""))
        val ai = object : TeamAi {
            override suspend fun ask(agent: Agent, system: String, msgs: List<Pair<String, String>>): String = when (agent) {
                Agent.DEBUGGER -> { seenMsgs.add(msgs.last().second); script.removeFirst() }
                Agent.PLANNER -> """{"steps":[{"file":"src/Div.kt","action":"edit","change":"guard","anchor":"return a / b"}]}"""
                Agent.CODER -> "FILE: src/Div.kt\n<<<FIND\n    return a / b\nFIND>>>\n<<<REPLACE\n    return if (b == 0) 0 else a / b\nREPLACE>>>"
                Agent.REVIEWER -> """{"pass":true,"problems":[]}"""
                Agent.PERF -> """{"pass":true,"must":[]}"""
                Agent.QA -> """{"pass":true,"checks":[],"missing":[]}"""
                else -> """{"pass":true}"""
            }
        }
        val f = MemoryFiles(mapOf("src/Div.kt" to buggy))
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.BUG, "", emptyList())
        SevenTeam(ai, f, null, full = true) { _, _ -> }.run("div 0 पर crash", pre)
        assertEquals(2, seenMsgs.size)
        val first = seenMsgs[0]
        assertTrue(first, first.contains("REPO MAP") && first.contains("fun div"))
        assertTrue(first, first.contains("LIKELY AREA") && first.contains("src/Div.kt - div()"))
        assertTrue(seenMsgs[1], seenMsgs[1].contains("2|     return a / b"))      // पढ़ी फ़ाइल लाइन नंबर के साथ
    }

    @Test fun oldSevenMode_debuggerUnchanged_noSearchRules() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"bug","brief":"div crashes on 0"}""",
            Agent.DEBUGGER to """{"root_cause":"src/Div.kt:2 - b=0 पर a / b","where":"src/Div.kt:2","fix":"guard","files":["src/Div.kt"]}""",
            Agent.PLANNER to """{"steps":[{"file":"src/Div.kt","action":"edit","change":"guard","anchor":"return a / b"}]}""",
            Agent.CODER to "FILE: src/Div.kt\n<<<FIND\n    return a / b\nFIND>>>\n<<<REPLACE\n    return if (b == 0) 0 else a / b\nREPLACE>>>",
            Agent.REVIEWER to """{"pass":true,"problems":[]}""", Agent.TESTER to """{"pass":true}""")
        SevenTeam(ai, MemoryFiles(mapOf("src/Div.kt" to buggy)), null) { _, _ -> }.run("div 0 पर crash, ठीक करो")
        assertEquals(1, ai.calls.count { it == Agent.DEBUGGER })               // पुराना क्रम: बिना पढ़े भी मान्य (जैसा पहले)
        assertFalse(ai.prompts[Agent.DEBUGGER]!!.contains("START-END"))
    }

    // ------------------------------------------------------------------ प्लानर: बड़ी फ़ाइल के हिस्से
    private val big = (1..3000).joinToString("\n") { if (it == 2500) "    fun deepBug() {}" else "    val v$it = $it" } + "\n"

    @Test fun block_withoutNumbers_forPlanner_isExactText() {
        val part = SevenDebug.block(SevenDebug.parseRead("Big.kt:2499-2501"), big, 150_000, 40_000, numbers = false)
        assertTrue(part.text, part.text.contains("\n    val v2499 = 2499\n    fun deepBug() {}\n    val v2501 = 2501\n"))
        assertFalse(part.text.contains("2500|"))
        val cut = SevenDebug.block(SevenDebug.ReadReq("Big.kt"), big, 150_000, 40_000, numbers = false)
        assertTrue(cut.text.contains("TRUNCATED") && cut.text.contains("2500: fun deepBug"))
    }

    @Test fun anchorLine_multiLineAndSpaces() {
        assertEquals(2500, SevenDebug.anchorLine(big, "fun deepBug() {}\n    val v2501 = 2501"))
        assertEquals(2500, SevenDebug.anchorLine(big, "fun   deepBug()   {}"))
        assertNull(SevenDebug.anchorLine(big, "fun nothing()"))
    }

    @Test fun window_multiLineAnchor_showsTheRightPart() {
        val w = Seven.window(big, "fun deepBug() {}\n    val v2501 = 2501")
        assertTrue(w.contains("fun deepBug() {"))                              // पहले: फ़ाइल की शुरुआत दिखती थी
        assertFalse(w.contains("val v5 = 5\n"))
    }

    @Test fun anchorsSeen_bigFileNeedsReadPart_smallFileNot() {
        val plan = Seven.Plan(listOf(Seven.Step("Big.kt", "edit", "x", "fun deepBug() {")), emptyList())
        val seen = SevenDebug.Seen().apply { add("Big.kt", 1, 1800) }
        val probs = SevenDebug.anchorsSeen(plan, { if (it == "Big.kt") big else null }, seen, 40_000)
        assertTrue(probs.toString(), probs.single().contains("line 2500") && probs.single().contains("Big.kt:2440-2560"))
        seen.add("Big.kt", 2440, 2560)
        assertTrue(SevenDebug.anchorsSeen(plan, { big }, seen, 40_000).isEmpty())
        val small = Seven.Plan(listOf(Seven.Step("S.kt", "edit", "x", "fun a")), emptyList())
        assertTrue(SevenDebug.anchorsSeen(small, { "fun a() {}" }, SevenDebug.Seen(), 40_000).isEmpty())
    }

    @Test fun fullPlanner_bigFile_unreadAnchorSentBack_thenReadsPart_coderGetsRightPlace() = runBlocking {
        val plan = """{"steps":[{"file":"src/Big.kt","action":"edit","change":"add extra after deepBug","anchor":"fun deepBug() {"}]}"""
        val edit = "FILE: src/Big.kt\n<<<FIND\n    fun deepBug() {}\n    val v2501 = 2501\nFIND>>>\n<<<REPLACE\n    fun deepBug() {}\n    val extra = 1\n    val v2501 = 2501\nREPLACE>>>"
        val ai = FakeAi(Agent.PLANNER to """{"read":["src/Big.kt"]}""", Agent.PLANNER to plan,
            Agent.PLANNER to """{"read":["src/Big.kt:2440-2560"]}""", Agent.PLANNER to plan,
            Agent.CODER to edit, Agent.REVIEWER to """{"pass":true,"problems":[]}""", Agent.PERF to """{"pass":true,"must":[]}""",
            Agent.TESTER to """{"pass":true}""", Agent.QA to """{"pass":true,"checks":[],"missing":[]}""")
        val f = MemoryFiles(mapOf("src/Big.kt" to big))
        val events = mutableListOf<String>()
        val u = """{"understood":"x","spec":"add extra","acceptance":["extra है"],"not_touch":[],"area":[],"questions":[]}"""
        val pre = SevenPlus.Understanding.parse(Seven.json(u), Seven.Kind.FEATURE, "", emptyList())
        val out = SevenTeam(ai, f, null, full = true) { a, t -> events.add("${a.name}:$t") }.run("deepBug में extra जोड़ो", pre)
        assertEquals(4, ai.calls.count { it == Agent.PLANNER })
        assertTrue(events.any { it.startsWith("PLANNER:") && it.contains("जगह गलत") })
        assertTrue(ai.prompts[Agent.PLANNER]!!.startsWith("You are the PLANNER") && ai.prompts[Agent.PLANNER]!!.contains("START-END"))
        assertTrue(ai.lastUser[Agent.CODER]!!.contains("fun deepBug() {"))
        assertTrue(f.staged()["src/Big.kt"]!!.contains("    val extra = 1"))
        assertEquals(true, out.testOk)
    }

    // ------------------------------------------------------------------ गायब कोड (जैसे Bluetooth माइक) + अंतहीन खोज + डिबगर अटके तो दूसरा रास्ता
    private val ear = "package app\n\nclass Ear {\n    fun start() {\n        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION)\n    }\n}\n"
    private val earFiles = mapOf("app/src/main/java/com/dk/assistant/Ear.kt" to ear, "app/src/main/java/com/dk/assistant/Speaker.kt" to "class Speaker {\n    fun speak() {}\n}\n")
    private val earPath = "app/src/main/java/com/dk/assistant/Ear.kt"
    private val btUnderstood = """{"understood":"Bluetooth जुड़ा हो तो उसी के माइक से सुनना","spec":"use the Bluetooth headset microphone when connected","acceptance":["Bluetooth जुड़ा हो तो उसी माइक से सुनता है"],"not_touch":[],"area":[],"questions":[]}"""
    private val btPlan = """{"steps":[{"file":"$earPath","action":"edit","change":"route mic to bluetooth SCO before recording","anchor":"fun start() {"}]}"""
    private val btEdit = "FILE: $earPath\n<<<FIND\n    fun start() {\nFIND>>>\n<<<REPLACE\n    fun start() {\n        val useBt = true\nREPLACE>>>"
    private fun rest() = arrayOf(Agent.CODER to btEdit, Agent.REVIEWER to """{"pass":true,"problems":[]}""", Agent.PERF to """{"pass":true,"must":[]}""",
        Agent.TESTER to """{"pass":true}""", Agent.QA to """{"pass":true,"checks":[],"missing":[]}""")

    @Test fun resolve_shortNameToRealPath() {
        val keys = listOf("app/src/main/java/com/dk/assistant/Ear.kt", "app/src/test/java/com/dk/assistant/Ear.kt", "a/X.kt", "b/X.kt")
        assertEquals("app/src/main/java/com/dk/assistant/Ear.kt", SevenDebug.resolve("Ear.kt", keys))       // test वाली नहीं, असली
        assertEquals("app/src/main/java/com/dk/assistant/Ear.kt", SevenDebug.resolve("assistant/Ear.kt", keys))
        assertNull(SevenDebug.resolve("X.kt", keys))                                                         // दो फ़ाइलें = पक्का नहीं
        assertNull(SevenDebug.resolve("Nope.kt", keys))
    }

    @Test fun compact_shortensOldBigResults_keepsTaskAndRecent() {
        val big = "----- BEGIN src/A.kt (900 lines) -----\n" + "x\n".repeat(5000) + "----- END src/A.kt -----"
        val msgs = mutableListOf("user" to "TASK " + "t".repeat(5000), "model" to "{}", "user" to big, "model" to "{}", "user" to big, "model" to "{}", "user" to big)
        SevenDebug.compact(msgs, keepLast = 2)
        assertTrue(msgs[0].second.startsWith("TASK") && msgs[0].second.length > 5000)    // काम कभी छोटा नहीं
        assertTrue(msgs[2].second, msgs[2].second.startsWith("(EARLIER RESULT") && msgs[2].second.contains("src/A.kt (900 lines)"))
        assertEquals(big, msgs[6].second)                                                  // आख़िरी नतीजा पूरा
    }

    @Test fun missingFeature_endlessSearchIsStopped_shortPathResolved_plannerAddsMissingCode() = runBlocking {
        val script = mutableListOf<Pair<Agent, String>>()
        repeat(3) { script += Agent.DEBUGGER to """{"search":["bluetooth"]}""" }
        script += Agent.DEBUGGER to """{"read":["Ear.kt"]}"""                                   // छोटा नाम -> असली path
        repeat(7) { script += Agent.DEBUGGER to """{"search":["sco"]}""" }                     // आख़िरी खोज रोकी जाती है
        script += Agent.DEBUGGER to """{"kind":"missing","root_cause":"Ear.kt:5 - AudioRecord starts without routing to the Bluetooth SCO mic; no Bluetooth code exists anywhere","bug":"Bluetooth माइक कभी चालू नहीं होता","where":"Ear.kt:5","fix":"start Bluetooth SCO before AudioRecord in Ear.start","files":["Ear.kt"]}"""
        script += Agent.PLANNER to btPlan
        script += rest()
        val ai = FakeAi(*script.toTypedArray())
        val events = mutableListOf<String>()
        val pre = SevenPlus.Understanding.parse(Seven.json(btUnderstood), Seven.Kind.BUG, "", emptyList())
        val f = MemoryFiles(earFiles)
        val out = SevenTeam(ai, f, null, full = true) { a, t -> events.add("${a.name}:$t") }.run("Bluetooth जुड़ा हो तब भी फ़ोन के माइक से सुनता है, ठीक करो", pre)
        assertEquals(12, ai.calls.count { it == Agent.DEBUGGER })
        assertTrue(events.any { it.startsWith("MANAGER:") && it.contains("बहुत देर से खोज") })
        assertTrue(events.any { it.startsWith("DEBUGGER:") && it.contains("लिखा ही नहीं") })
        assertTrue(ai.lastUser[Agent.PLANNER]!!.contains("ADD THE MISSING CODE"))
        assertTrue(ai.lastUser[Agent.PLANNER]!!.contains(earPath))                       // डिबगर की फ़ाइल असली path में
        assertTrue(out.report, out.report.contains("जड़ कहाँ थी: $earPath:5"))
        assertTrue(f.staged()[earPath]!!.contains("useBt"))
        assertEquals(true, out.testOk)
    }

    @Test fun debuggerNeverAnswers_managerSwitchesToPlanner_noDeadEnd() = runBlocking {
        val script = mutableListOf<Pair<Agent, String>>()
        script += Agent.DEBUGGER to """{"read":["$earPath"]}"""
        repeat(4) { script += Agent.DEBUGGER to "I think the bluetooth part is not there." }   // JSON नहीं
        script += Agent.PLANNER to btPlan
        script += rest()
        val ai = FakeAi(*script.toTypedArray())
        val events = mutableListOf<String>()
        val pre = SevenPlus.Understanding.parse(Seven.json(btUnderstood), Seven.Kind.BUG, "", emptyList())
        val f = MemoryFiles(earFiles)
        val out = SevenTeam(ai, f, null, full = true) { a, t -> events.add("${a.name}:$t") }.run("Bluetooth माइक नहीं चलता", pre)
        assertTrue(events.any { it.startsWith("MANAGER:") && it.contains("रणनीति बदल रहा हूँ: प्लानर") })
        val pu = ai.lastUser[Agent.PLANNER]!!
        assertTrue(pu, pu.contains("could not pin") && pu.contains("DEBUGGER SAYS THESE FILES: $earPath"))
        assertFalse(out.report.contains("थोड़ी और जानकारी दें"))
        assertTrue(f.staged()[earPath]!!.contains("useBt"))
        assertEquals(true, out.testOk)
    }
}
