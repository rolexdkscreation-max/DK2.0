package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.Seven.Member
import com.dk.assistant.SevenTeamTest.FakeAi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v5.2 (मालिक): 11 की जगह 7 काम करने वाले एजेंट + 1 समझाने वाला. जुड़े एजेंट एक सदस्य, काम का तरीका वही.
 * मैनेजर सबसे पहले; सवाल → समझाने वाला; काम → प्लानर (पहले जड़/कारण) → कोडर → रिव्यूअर → टेस्टर → डिबगर → गिट/रिलीज़;
 * काम ख़त्म → समझाने वाला नतीजा बताए. कोई "max 3" सीमा नहीं - रुकना सिर्फ़ हूबहू वही गलती लगातार 10 बार.
 */
class CoderSevenAgentsTest {

    @Test fun team_is7Workers_plusExplainer_withMerges() {
        assertEquals(8, Member.values().size)
        assertEquals(7, Member.WORKERS.size)
        assertFalse(Member.EXPLAINER in Member.WORKERS)
        // जोड़े हुए
        assertEquals(Member.MANAGER, Agent.MANAGER.member); assertEquals(Member.MANAGER, Agent.PROMPT.member)
        assertEquals(Member.GIT_RELEASE, Agent.GIT.member); assertEquals(Member.GIT_RELEASE, Agent.DEPLOY.member)
        assertEquals(Member.REVIEWER, Agent.REVIEWER.member); assertEquals(Member.REVIEWER, Agent.PERF.member)
        assertEquals(Member.TESTER, Agent.TESTER.member); assertEquals(Member.TESTER, Agent.QA.member)
        // वैसे ही
        assertEquals(Member.PLANNER, Agent.PLANNER.member)
        assertEquals(Member.CODER, Agent.CODER.member)
        assertEquals(Member.DEBUGGER, Agent.DEBUGGER.member)
        assertEquals(Member.EXPLAINER, Agent.EXPLAINER.member)
        // हर सदस्य के पास कम से कम एक ज़िम्मेदारी; स्क्रीन पर बस 8 नाम
        assertEquals(Member.values().toSet(), Agent.values().map { it.member }.toSet())
        assertEquals("🌿 गिट/रिलीज़", Member.GIT_RELEASE.label)
    }

    @Test fun oneModelPerMember() {
        for (a in Agent.values()) for (b in Agent.values())
            if (a.member == b.member) assertEquals("$a/$b", TeamModels.fast(a), TeamModels.fast(b))
        assertTrue(TeamModels.fast(Agent.PERF))           // रिव्यूअर का हिस्सा = रिव्यूअर का मज़बूत मॉडल
        assertFalse(TeamModels.fast(Agent.QA))            // टेस्टर का हिस्सा = टेस्टर वाला मॉडल
        assertFalse(TeamModels.fast(Agent.PROMPT))        // मैनेजर का हिस्सा
    }

    @Test fun loop_noCountLimit_stopsOnlyAfter10SameErrorsInARow() {
        assertEquals(10, FixLoop.TEAM_SAME_LIMIT)
        // अलग-अलग गलतियाँ = आगे बढ़ रहे हैं: कितनी भी बार, रुकना नहीं (पहले 8 पर रुक जाता था)
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.nextTeam((1..50).map { "e$it" }))
        // वही गलती 3 बार (पहले यहीं रुकता था) और 9 बार = अभी भी कोशिश
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.nextTeam(List(3) { "same" }))
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.nextTeam(List(9) { "same" }))
        // बीच में एक अलग गलती = गिनती फिर से
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.nextTeam(List(9) { "same" } + "other" + List(9) { "same" }))
        // हूबहू वही गलती लगातार 10 बार = रुककर पूछो
        assertEquals(FixLoop.Next.STOP_STUCK, FixLoop.nextTeam(listOf("a", "b") + List(10) { "same" }))
        // खाली signature पर कभी "अटका" नहीं
        assertEquals(FixLoop.Next.FIX_AGAIN, FixLoop.nextTeam(List(12) { "" }))
        // पुराना नियम (पुरानी टीम के लिए) जैसा का तैसा
        assertEquals(FixLoop.Next.STOP_STUCK, FixLoop.next(List(FixLoop.SAME_LIMIT) { "x" }))
    }

    @Test fun routing_questionAndTask_explainerFirst() {
        // सवाल + काम एक साथ = पहले समझाने वाला, फिर प्लानर (काम रुकता नहीं)
        assertTrue(Seven.wantsExplain("login क्यों टूटा? ठीक करो"))
        assertFalse(Seven.explainOnly("login क्यों टूटा? ठीक करो"))
        assertTrue(Seven.wantsExplain("यह कैसे हुआ, और fix करो"))
        // सिर्फ़ काम = सीधे प्लानर
        assertFalse(Seven.wantsExplain("sub() जोड़ो"))
        // सिर्फ़ समझाना = समझाने वाला, कोड नहीं
        assertTrue(Seven.explainOnly("login कैसे काम करता है समझाओ"))
        assertTrue(Seven.asksQuestion("क्या हुआ?"))
    }

    @Test fun resultExplain_fromRealOutcome_simpleHindi() {
        val ok = Seven.resultExplain(true, listOf("src/Calc.kt" to "sub() जोड़ा"), emptyList(), "", "", emptyList(), "https://pr/1", true)
        assertTrue(ok, ok.startsWith("🎓 समझाने वाला"))
        for (h in listOf("• क्या हुआ:", "• क्या नहीं हुआ:", "• वजह:", "• कैसे ठीक होगा:")) assertTrue(ok, ok.contains(h))
        assertTrue(ok, ok.contains("src/Calc.kt") && ok.contains("https://pr/1"))
        assertTrue(ok, ok.contains("आपकी मंज़ूरी"))                // merge/release सिर्फ़ मंज़ूरी से
        val bad = Seven.resultExplain(false, emptyList(), listOf(Seven.Bug("crash", "src/Div.kt:2", "b == 0 पर भाग", "guard")),
            "वही गलती 10 बार", "", listOf("जड़ मानी: x"), "", true)
        assertTrue(bad, bad.contains("काम पूरा नहीं माना गया") && bad.contains("वही गलती 10 बार"))
        assertFalse(bad, bad.contains("✅ काम पूरा हुआ"))
        // नॉर्मल मोड: merge की बात ही नहीं
        val normal = Seven.resultExplain(true, listOf("a.kt" to "x"), emptyList(), "", "", emptyList(), "", false)
        assertFalse(normal, normal.contains("merge"))
    }

    private val calc = "fun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private val understood = """{"understood":"sub जोड़ना","spec":"add sub(a,b)","acceptance":["sub(5,3)=2"],"not_touch":["add()"],"area":["src/Calc.kt"],"questions":[]}"""
    private val pass = """{"pass":true,"problems":[]}"""

    @Test fun feature_plannerWritesReasonBeforeCoder_andExplainerTellsResult() = runBlocking {
        val plan = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub","anchor":"fun add","why":"Calc.kt में sub() नहीं है, वहीं जोड़ना है"}]}"""
        val edit = "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\nFIND>>>\n<<<REPLACE\nfun sub(a: Int, b: Int): Int = a - b\n\nfun add(a: Int, b: Int): Int {\nREPLACE>>>"
        val ai = FakeAi(Agent.PLANNER to plan, Agent.CODER to edit, Agent.REVIEWER to pass, Agent.PERF to """{"pass":true,"must":[],"should":[]}""",
            Agent.TESTER to """{"pass":true}""", Agent.QA to """{"pass":true,"checks":["sub ✓"],"missing":[]}""")
        val f = MemoryFiles(mapOf("src/Calc.kt" to calc))
        val events = mutableListOf<Pair<Agent, String>>()
        val pre = SevenPlus.Understanding.parse(Seven.json(understood), Seven.Kind.FEATURE, "", emptyList())
        val out = SevenTeam(ai, f, null, full = true) { a, t -> events.add(a to t) }.run("Calc में sub जोड़ो", pre)
        assertEquals(true, out.testOk)
        val reason = events.indexOfFirst { it.first == Agent.PLANNER && it.second.contains("कारण/ज़रूरत लिखी") && it.second.contains("sub() नहीं है") }
        val firstCoder = events.indexOfFirst { it.first == Agent.CODER || it.first == Agent.REVIEWER }
        assertTrue(events.toString(), reason in 0 until firstCoder.let { if (it < 0) Int.MAX_VALUE else it })
        // काम ख़त्म → मैनेजर समझाने वाले को बुलाता है, वही नतीजा बताता है (कोड नहीं छूता)
        val last = events.last()
        assertEquals(Agent.EXPLAINER, last.first)
        assertTrue(last.second, last.second.contains("• क्या हुआ:") && last.second.contains("src/Calc.kt"))
        assertTrue(events[events.size - 2].first == Agent.MANAGER)
        assertFalse(ai.calls.contains(Agent.EXPLAINER))     // नतीजा असली data से, AI नहीं
        assertTrue(out.report.contains("🎓 समझाने वाला — नतीजा"))
    }

    @Test fun bug_rootCauseIsFoundByPlanner_beforeCoder() = runBlocking {
        val buggy = "fun div(a: Int, b: Int): Int {\n    return a / b\n}\n"
        val root = """{"root_cause":"src/Div.kt:2 - a / b crashes when b is 0","bug":"crash","where":"src/Div.kt:2","fix":"guard b == 0","files":["src/Div.kt"]}"""
        val ai = FakeAi(Agent.DEBUGGER to """{"read":["src/Div.kt"]}""", Agent.DEBUGGER to root,
            Agent.PLANNER to """{"steps":[{"file":"src/Div.kt","action":"edit","change":"guard b == 0","anchor":"return a / b"}]}""",
            Agent.CODER to "FILE: src/Div.kt\n<<<FIND\n    return a / b\nFIND>>>\n<<<REPLACE\n    return if (b == 0) 0 else a / b\nREPLACE>>>",
            Agent.REVIEWER to pass, Agent.PERF to """{"pass":true,"must":[],"should":[]}""",
            Agent.TESTER to """{"pass":true}""", Agent.QA to """{"pass":true,"checks":["✓"],"missing":[]}""")
        val f = MemoryFiles(mapOf("src/Div.kt" to buggy))
        val shown = mutableListOf<Pair<Member, String>>()
        lateinit var team: SevenTeam
        team = SevenTeam(ai, f, null, full = true) { a, t -> shown.add(team.shownAs(a) to t) }
        val bugU = """{"understood":"0 से भाग पर crash","spec":"div must not crash when b == 0","acceptance":["div(1,0) crash नहीं"],"not_touch":[],"area":["src/Div.kt"],"questions":[]}"""
        val out = team.run("div 0 पर crash हो रहा है", SevenPlus.Understanding.parse(Seven.json(bugU), Seven.Kind.BUG, "", emptyList()))
        assertEquals(true, out.testOk)
        // जड़ ढूँढने का पूरा हिस्सा स्क्रीन पर "प्लानर" के नाम से, कोडर उसके बाद ही
        val rootLine = shown.indexOfFirst { it.first == Member.PLANNER && it.second.contains("जड़ (कारण) लिखी") && it.second.contains("src/Div.kt:2") }
        val firstCoder = shown.indexOfFirst { it.first == Member.CODER }
        assertTrue(shown.toString(), rootLine >= 0 && firstCoder > rootLine)
        assertTrue(shown.toString(), shown.take(rootLine).none { it.first == Member.DEBUGGER })
        // जड़-खोज के बाद डिबगर का नाम फिर अपना
        assertEquals(Member.DEBUGGER, team.shownAs(Agent.DEBUGGER))
    }

    @Test fun texts_sayTeamOf7_notOf11() {
        val files = listOf("CodingActivity.kt", "SettingsActivity.kt", "SevenRunner.kt", "RepoDoctorRunner.kt", "RepoDoctor.kt", "AgentRunner.kt")
        for (n in files) {
            val s = File("src/main/java/com/dk/assistant/$n").readText()
            assertFalse(n, s.contains("\"11 एजेंट") || s.contains("11-एजेंट टीम") || s.contains("11 एजेंट वाली"))
        }
        val live = File("src/main/java/com/dk/assistant/GeminiLive.kt").readText()
        assertFalse(live.contains("11-agent team"))
        assertTrue(File("src/main/assets/ui_en.tsv").readText().contains("7 एजेंट वाली टीम तैयार है"))
    }
}
