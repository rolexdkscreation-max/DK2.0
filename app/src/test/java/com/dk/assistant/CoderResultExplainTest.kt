package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.SevenTeamTest.FakeAi
import com.dk.assistant.SevenTeamTest.FakeGit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक): "समझाने वाला सिर्फ़ बताता है कौन सी फ़ाइल बदली - वैसे समझाए जैसे क्या, कैसे हुआ, बग कहाँ था, कैसे जाँचें.
 * कोड नहीं लिखे." + पुराने PR (#148, #159) की गलतियाँ टीम खुद पकड़े: हटाया बचाव, बना पर कभी इस्तेमाल न हुआ code.
 */
class CoderResultExplainTest {

    private val calc = "package app\n\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private val managerFeature = """{"kind":"feature","brief":"add sub()","acceptance":["sub(5,3)=2"]}"""
    private val planRead = """{"read":["src/Calc.kt"]}"""
    private val planOne = """{"steps":[{"file":"src/Calc.kt","action":"edit","change":"add sub function","anchor":"fun add"}]}"""
    private val goodEdit = "FILE: src/Calc.kt\n<<<FIND\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\nFIND>>>\n<<<REPLACE\n" +
        "fun add(a: Int, b: Int): Int {\n    return a + b\n}\n\nfun sub(a: Int, b: Int): Int {\n    return a - b\n}\nREPLACE>>>"
    private val pass = """{"pass":true,"problems":[]}"""
    private val gitMsg = """{"commit":"Add sub()","title":"sub() जोड़ा"}"""
    private val deep = "1) सीधी बात: आपने घटाने का sub() माँगा था, वह जुड़ गया और build पास है.\n" +
        "2) क्या बदला और क्यों: src/Calc.kt: sub() - पहले सिर्फ़ जोड़ना था, अब घटाना भी.\n" +
        "3) अब यह कैसे चलेगा: sub(5,3) बुलाने पर 2 मिलेगा.\n4) बग कहाँ था: कोई बग नहीं था, यह नया काम था.\n" +
        "5) आप खुद कैसे जाँचें: sub(5,3) चलाकर 2 देखें.\n6) ध्यान रखें: कोई ख़तरा नहीं दिखा."

    @Test fun finishedTask_explainerReadsRealDiffAndExplains() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planRead, Agent.PLANNER to planOne,
            Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.GIT to gitMsg, Agent.EXPLAINER to deep)
        val git = FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1"))))
        val said = mutableListOf<Pair<Agent, String>>()
        val out = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calc)), git, aiResult = true) { a, t -> said.add(a to t) }
            .run("Calc में sub() फ़ंक्शन जोड़ो")
        assertEquals(Agent.EXPLAINER, ai.calls.last())
        // समझाने वाले को असली diff, नतीजा और माँग मिली - सिर्फ़ फ़ाइल का नाम नहीं
        val got = ai.lastUser[Agent.EXPLAINER]!!
        assertTrue(got, got.contains("+ fun sub(a: Int, b: Int): Int {") && got.contains("RESULT: build/tests PASSED") && got.contains("sub(5,3)=2"))
        assertTrue(ai.prompts[Agent.EXPLAINER]!!.contains("You NEVER write code"))
        assertTrue(ai.prompts[Agent.EXPLAINER]!!.contains("अब यह कैसे चलेगा") && ai.prompts[Agent.EXPLAINER]!!.contains("आप खुद कैसे जाँचें"))
        // मालिक को गहरी समझ + पक्का ढाँचा (असली नतीजा) दोनों
        assertTrue(out.report, out.report.contains("अब यह कैसे चलेगा: sub(5,3)") && out.report.contains("• क्या हुआ:"))
        assertTrue(said.any { (a, t) -> a == Agent.EXPLAINER && t.contains("कैसे हुआ, आसान भाषा में") })
        assertEquals(true, out.testOk)
    }

    @Test fun explainerFails_orWritesCode_fallsBackToRealTemplate() = runBlocking {
        for (bad in listOf("```kotlin\nfun sub() = 1\n```  यह रहा code, पूरा समझा दिया गया है ताकि आप देख सकें", """{"read":["x"]}""")) {
            val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planRead, Agent.PLANNER to planOne,
                Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.GIT to gitMsg, Agent.EXPLAINER to bad)
            val out = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calc)), FakeGit(ArrayDeque(listOf(TeamTest(true, "", "u")))), aiResult = true) { _, _ -> }
                .run("Calc में sub() फ़ंक्शन जोड़ो")
            assertFalse(out.report, out.report.contains("```kotlin"))
            assertTrue(out.report.contains("नतीजा आसान भाषा में") && out.report.contains("• क्या हुआ:"))
        }
    }

    @Test fun oldConstructor_noExtraAiCall() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to managerFeature, Agent.PLANNER to planRead, Agent.PLANNER to planOne,
            Agent.CODER to goodEdit, Agent.REVIEWER to pass, Agent.GIT to gitMsg)
        SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calc)), FakeGit(ArrayDeque(listOf(TeamTest(true, "", "u"))))) { _, _ -> }
            .run("Calc में sub() फ़ंक्शन जोड़ो")
        assertFalse(Agent.EXPLAINER in ai.calls)
        // असली ऐप में यह चालू है
        val runner = listOf(java.io.File("src/main/java/com/dk/assistant/SevenRunner.kt"), java.io.File("app/src/main/java/com/dk/assistant/SevenRunner.kt")).first { it.exists() }.readText()
        assertEquals(3, Regex("aiResult = true").findAll(runner).count())
    }

    @Test fun findBugWords_explainerFirst_butFixFlowStays() {
        assertTrue(Seven.wantsExplain("login का बग ढूँढो"))
        assertTrue(Seven.wantsExplain("mic क्यों बंद होता है पता करो"))
        assertFalse(Seven.explainOnly("login का बग ढूँढो"))        // पहले जैसा: टीम ढूँढकर ठीक भी करती है, बस पहले समझ
        assertTrue(Seven.explainOnly("PR का code review करो"))
        assertTrue(Seven.Prompts.EXPLAINER.contains("बग कहाँ है (path:line)") && Seven.Prompts.EXPLAINER.contains("You NEVER write code"))
    }

    // ---- PR #159: बना पर कभी इस्तेमाल न हुआ code
    @Test fun qa_catchesNewCodeThatIsNeverUsed() {
        val before = "class Stt {\n    fun listen(): String? {\n        return hear()\n    }\n}\n"
        val after = "class Stt {\n    private const val SETUP_MS = 15_000L\n    fun listen(): String? {\n" +
            "        val lang = \"hi-IN\"\n        val recognizerIntent = Intent(lang)\n        return hear()\n    }\n    private fun networkMsg() = \"नेट नहीं\"\n}\n"
        val q = SevenPlus.Qa.scan("src/Stt.kt", before, after)
        assertTrue(q.toString(), q.any { it.contains("'recognizerIntent'") })
        assertTrue(q.toString(), q.any { it.contains("'SETUP_MS'") })
        assertTrue(q.toString(), q.any { it.contains("'networkMsg'") })
        assertFalse(q.toString(), q.any { it.contains("'lang'") })                      // इस्तेमाल हुआ
        // सार्वजनिक (बाहर से बुलाया जा सकता) / class का सदस्य / override = नहीं पकड़ते (गलत चेतावनी नहीं)
        val pub = "class A {\n    val size = 3\n    fun open() = 1\n    override fun toString() = \"a\"\n}\nfun top() {\n    val used = 1\n    println(used)\n}\n"
        assertTrue(SevenPlus.Qa.scan("src/A.kt", null, pub).toString(), SevenPlus.Qa.scan("src/A.kt", null, pub).isEmpty())
        assertTrue(SevenPlus.Qa.scan("src/test/ATest.kt", null, after).isEmpty())
    }

    // ---- PR #148: retry / fallback चुपचाप हटा
    @Test fun droppedGuard_shownToReviewerAndQa() {
        val before = "fun go() {\n    try { call(thinking) } catch (e: AiException) {\n        if (e.code == 400 && thinking) { thinking = false; continue }\n        throw e\n    }\n}\n"
        val after = "fun go() {\n    try { call(thinking) } catch (e: AiException) {\n        throw e\n    }\n}\n"
        val d = CodeGuard.droppedGuards(before, after)
        assertEquals(listOf("if (e.code == 400 && thinking) { thinking = false; continue }"), d)
        assertTrue(CodeGuard.droppedGuards(before, before).isEmpty())
        assertTrue(CodeGuard.droppedGuards(null, after).isEmpty())
        val team = listOf(java.io.File("src/main/java/com/dk/assistant/SevenTeam.kt"), java.io.File("app/src/main/java/com/dk/assistant/SevenTeam.kt")).first { it.exists() }.readText()
        assertTrue(team.contains("SAFETY / CONDITION REMOVED by this change") && team.contains("!! SAFETY / CONDITION REMOVED"))
    }

    @Test fun geminiThinkingRejected_retriesWithoutThinking() {
        assertTrue(GeminiModels.thinkingRejected(400, "Thinking level is not supported for this model", true))
        assertFalse(GeminiModels.thinkingRejected(400, "Thinking level is not supported", false))   // पहले ही बिना thinking
        assertFalse(GeminiModels.thinkingRejected(400, "API key not valid (thinking)", true))
        assertFalse(GeminiModels.thinkingRejected(429, "thinking quota", true))
        val src = listOf(java.io.File("src/main/java/com/dk/assistant/AiClients.kt"), java.io.File("app/src/main/java/com/dk/assistant/AiClients.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("GeminiModels.thinkingRejected(e.code, e.message.orEmpty(), thinking)") && src.contains("streamOnce(m, system, history, prompt, false)"))
    }

    // ---- मालिक: "मैनेजर काम समझेगा, समझकर समझाने वाले को बताएगा; समझाने वाला कदम-दर-कदम पूरा समझाए"
    @Test fun managerUnderstandsQuestion_thenHandsEveryPointToExplainer() = runBlocking {
        val calc2 = "package app\n\nfun add(a: Int, b: Int) = a + b\n\nfun twice(x: Int) = add(x, x)\n"
        val mgr = """{"kind":"question","answer":"छोटा","handoff":{"wants":"twice() अंदर से कैसे चलता है","points":["twice() किसे बुलाता है","add() क्या लौटाता है"],"look":["src/Calc.kt: twice"]}}"""
        val deep = "1) सीधा जवाब: twice(x) = add(x, x).\n2) यह कैसे काम करता है: कदम 1: twice() -> कदम 2: add()"
        val ai = FakeAi(Agent.MANAGER to mgr, Agent.EXPLAINER to deep)
        val events = mutableListOf<String>()
        val u = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calc2)), FakeGit(ArrayDeque()), full = true) { a, t -> events.add("${a.name}:$t") }
            .understand("twice() कैसे काम करता है? समझाओ")
        assertEquals(deep, u.answer)
        assertEquals(listOf(Agent.MANAGER, Agent.EXPLAINER), ai.calls)                 // कोई अतिरिक्त AI कॉल नहीं
        val got = ai.lastUser[Agent.EXPLAINER]!!
        assertTrue(got, got.contains("MANAGER'S HANDOFF") && got.contains("1. twice() किसे बुलाता है") && got.contains("2. add() क्या लौटाता है") &&
            got.contains("कहाँ से पढ़ना शुरू करें: src/Calc.kt: twice"))
        assertTrue(events.any { it.startsWith("MANAGER:") && it.contains("मैंने सवाल ऐसे समझा") })   // मालिक को भी दिखे
        assertTrue(ai.prompts[Agent.MANAGER]!!.contains("\"handoff\""))
        assertTrue(ai.prompts[Agent.EXPLAINER]!!.contains("कदम-दर-कदम") && ai.prompts[Agent.EXPLAINER]!!.contains("explain EVERY one of its points"))
        assertEquals("", Seven.handoff(null))
        assertEquals("", Seven.handoff(org.json.JSONObject("""{"handoff":{"look":["x"]}}""")))
    }
}
