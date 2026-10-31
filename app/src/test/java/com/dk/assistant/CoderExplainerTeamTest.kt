package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.SevenTeamTest.FakeAi
import com.dk.assistant.SevenTeamTest.FakeGit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v5.1: समझाने वाला एजेंट, मैनेजर का काम सौंपना, "कौन एजेंट काम कर रहा" animation, डिबगर quote जाँच, कोई समय-सीमा नहीं */
class CoderExplainerTeamTest {

    private val calc = "package app\n\nfun add(a: Int, b: Int) = a + b\n\nfun twice(x: Int) = add(x, x)\n"
    private val files = MemoryFiles(mapOf("src/Calc.kt" to calc))

    @Test fun explainerReadsRealCodeAndAnswersDeeply() = runBlocking {
        val deep = "1) सीधा जवाब: add() दो संख्याएँ जोड़ता है।\n2) यह कैसे काम करता है: src/Calc.kt: twice() → add(x, x)"
        val ai = FakeAi(Agent.MANAGER to """{"kind":"question","answer":"छोटा जवाब"}""",
            Agent.EXPLAINER to """{"read":["src/Calc.kt"]}""", Agent.EXPLAINER to deep)
        val events = mutableListOf<String>()
        val u = SevenTeam(ai, files, FakeGit(ArrayDeque()), full = true) { a, t -> events.add("${a.name}:$t") }.understand("twice() कैसे काम करता है? समझाओ")
        assertEquals(deep, u.answer)
        assertEquals(listOf(Agent.MANAGER, Agent.EXPLAINER, Agent.EXPLAINER), ai.calls)
        assertTrue("असली फ़ाइल पढ़कर दी गई", ai.lastUser[Agent.EXPLAINER]!!.contains("fun twice(x: Int) = add(x, x)"))
        assertTrue(ai.prompts[Agent.EXPLAINER]!!.contains("ONLY job"))
        assertTrue("मैनेजर ने सौंपा", events.any { it.startsWith("MANAGER:") && it.contains("समझाने वाला को सौंपा") })
        assertFalse(ai.calls.contains(Agent.PROMPT))
    }

    @Test fun checkOnlyRequestBecomesQuestionNoCodeChange() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"feature","brief":"app check","answer":"छोटा"}""",
            Agent.EXPLAINER to "✓ ठीक: src/Calc.kt:3 add() सही है। आगे क्या करें: कुछ नहीं।")
        val u = SevenTeam(ai, files, FakeGit(ArrayDeque()), full = true) { _, _ -> }.understand("पूरे app का कोड जांच करो और समझाओ")
        assertEquals(Seven.Kind.QUESTION, u.kind)
        assertTrue(u.answer.contains("✓ ठीक"))
        assertFalse(ai.calls.contains(Agent.PROMPT))
    }

    @Test fun explainWords() {
        assertTrue(Seven.explainOnly("जांच करो"))
        assertTrue(Seven.explainOnly("login कैसे काम करता है समझाओ"))
        assertFalse(Seven.explainOnly("login का बग ठीक करो और समझाओ"))
        assertTrue(Seven.wantsExplain("login का बग ठीक करो और समझाओ"))
        assertFalse(Seven.wantsExplain("sub() जोड़ो"))
    }

    @Test fun explainerIsStrongModelAndInTeam() {
        assertTrue(TeamModels.fast(Agent.EXPLAINER))
        assertEquals("समझाने वाला", Agent.EXPLAINER.hi)
        assertTrue(Seven.Prompts.of(Agent.EXPLAINER).contains("You NEVER change code"))
        assertTrue(Seven.Prompts.RULES.contains("PROFESSIONAL STANDARD"))
        assertTrue(Seven.Prompts.RULES.contains("must be finished completely"))
    }

    @Test fun agentWorkLabelAndManagerFirst() {
        assertEquals(Agent.MANAGER, AgentWork.ORDER.first())
        assertEquals(Agent.values().size, AgentWork.ORDER.size)
        assertEquals(Agent.DEBUGGER, AgentWork.parse("🐞 डिबगर: जड़ ढूँढ रहा हूँ"))
        assertNull(AgentWork.parse("कुछ और"))
        assertEquals("🎓 समझाने वाला काम कर रहा है..", AgentWork.label(Agent.EXPLAINER, 2))
        assertEquals("👔 मैनेजर → 🐞 डिबगर", AgentWork.underManager(Agent.DEBUGGER))
        val src = File("src/main/java/com/dk/assistant/SevenRunner.kt").readText()
        assertTrue(src.contains("CoderSession.activeAgent = ag"))
        assertTrue(File("src/main/java/com/dk/assistant/CodingActivity.kt").readText().contains("AgentWorkView(this)"))
    }

    @Test fun noTimeLimitOnWholeTask() {
        val r = File("src/main/java/com/dk/assistant/SevenRunner.kt").readText()
        val t = File("src/main/java/com/dk/assistant/CoderTeam.kt").readText()
        assertFalse(r.contains("withTimeout(CoderTeam.TASK_TIMEOUT_MIN"))
        assertFalse(t.contains("withTimeout(TASK_TIMEOUT_MIN"))
        assertTrue(r.contains("run work@{"))
    }

    @Test fun debuggerQuoteMustMatchRealLine() {
        val text = { p: String -> if (p == "src/Calc.kt") calc else null }
        val ok = JSONObject().put("where", "src/Calc.kt:5").put("quote", "fun twice(x: Int) = add(x, x)")
        assertNull(QuoteCheck.problem(ok, { it }, text))
        val near = JSONObject().put("where", "src/Calc.kt:3").put("quote", "fun twice(x: Int) = add(x, x)")
        assertNull("±3 लाइन में = ठीक", QuoteCheck.problem(near, { it }, text))
        val fake = JSONObject().put("where", "src/Calc.kt:3").put("quote", "return a * b // गलत")
        val p = QuoteCheck.problem(fake, { it }, text)
        assertNotNull(p); assertTrue(p!!.startsWith("QUOTE NOT FOUND")); assertTrue(p.contains("3| fun add"))
        assertNull("quote नहीं = पुराना व्यवहार", QuoteCheck.problem(JSONObject().put("where", "src/Calc.kt:3"), { it }, text))
        assertTrue(SevenDebug.EXTRA.contains("\"quote\""))
        assertEquals(3, SevenDebug.VERIFY_ROUNDS)
        assertEquals(20, SevenTeam.DEBUG_ROUNDS)
        assertEquals(4, SevenTeam.QA_ROUNDS)
    }
}
