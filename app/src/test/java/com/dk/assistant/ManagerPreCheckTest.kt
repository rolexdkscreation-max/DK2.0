package com.dk.assistant

import com.dk.assistant.Seven.Agent
import com.dk.assistant.SevenTeamTest.FakeAi
import com.dk.assistant.SevenTeamTest.FakeGit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** मैनेजर पहले: सवाल = मैनेजर खुद जवाब देकर रुके; काम = "हाँ" से पहले रिपो जाँच (खुले PR + टकराव) */
class ManagerPreCheckTest {

    private val files = MemoryFiles(mapOf("src/Calc.kt" to "package app\n\nfun add(a: Int, b: Int) = a + b\n"))

    @Test fun question_managerAnswers_noPromptEngineer_noGit() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"question","answer":"add() दो संख्याएँ जोड़ता है"}""")
        val git = FakeGit(ArrayDeque())
        val u = SevenTeam(ai, files, git, full = true) { _, _ -> }.understand("add() क्या करता है?")
        assertEquals("add() दो संख्याएँ जोड़ता है", u.answer)
        assertEquals(listOf(Agent.MANAGER), ai.calls)
        assertTrue(git.log.isEmpty())
    }

    @Test fun question_blankAnswer_managerAsksItselfAgain_stillNoWork() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"question","answer":""}""",
            Agent.MANAGER to """{"kind":"question","answer":"यह फ़ाइल src/Calc.kt में है"}""")
        val u = SevenTeam(ai, files, FakeGit(ArrayDeque()), full = true) { _, _ -> }.understand("add() कहाँ है?")
        assertEquals("यह फ़ाइल src/Calc.kt में है", u.answer)
        assertEquals(listOf(Agent.MANAGER, Agent.MANAGER), ai.calls)   // प्रॉम्प्ट इंजीनियर / काम नहीं
    }

    @Test fun question_noAnswerAtAll_managerStopsWithMessage() = runBlocking {
        val ai = FakeAi(Agent.MANAGER to """{"kind":"question"}""", Agent.MANAGER to "{}")
        val u = SevenTeam(ai, files, FakeGit(ArrayDeque()), full = true) { _, _ -> }.understand("यह क्या है?")
        assertEquals(Seven.NO_ANSWER_HI, u.answer)
        assertFalse(ai.calls.contains(Agent.PROMPT))
    }

    @Test fun managerPrompt_saysQuestionIsAnsweredByManager() {
        assertTrue(Seven.Prompts.MANAGER.contains("YOU answer it yourself"))
        assertTrue(Seven.Prompts.MANAGER.contains("never call the work \"done\" before"))   // पुराना नियम बचा
    }

    private fun pr(n: Int, t: String) = RepoDoctor.Pr(n, t, "https://github.com/u/r/pull/$n", "feature/x$n", "main", false, "sha$n", "u")

    @Test fun areaPaths_onlyRealPaths() {
        assertEquals(listOf("app/src/main/java/com/dk/assistant/TeacherActivity.kt", "src/Calc.kt"),
            RepoPreCheck.areaPaths(listOf("app/src/main/java/com/dk/assistant/TeacherActivity.kt - toolbar यहीं", "src/Calc.kt: add()", "Teacher screen", "")))
    }

    @Test fun overlap_byPathOrFileName() {
        val area = listOf("app/src/main/java/com/dk/assistant/TeacherActivity.kt")
        assertEquals(listOf("app/src/main/java/com/dk/assistant/TeacherActivity.kt"),
            RepoPreCheck.overlap(area, listOf("README.md", "app/src/main/java/com/dk/assistant/TeacherActivity.kt")))
        assertTrue(RepoPreCheck.overlap(area, listOf("README.md")).isEmpty())
    }

    @Test fun text_noOpenPr() {
        val t = RepoPreCheck.text(emptyList(), emptyMap())
        assertTrue(t.contains("कोई PR खुला नहीं"))
    }

    @Test fun text_openPrClash_warnsBeforeYes() {
        val t = RepoPreCheck.text(listOf(pr(120, "Teacher toolbar"), pr(121, "Docs")), mapOf(120 to listOf("app/x/TeacherActivity.kt"), 121 to emptyList()))
        assertTrue(t.contains("2 PR खुले"))
        assertTrue(t.contains("#120"))
        assertTrue(t.contains("TeacherActivity.kt"))
        assertTrue(t.contains("⚠ PR #120 इसी जगह"))
        assertFalse(t.contains("#121 इसी जगह"))
    }

    @Test fun text_ownPr_isNotAClash() {
        val t = RepoPreCheck.text(listOf(pr(7, "DK काम")), mapOf(7 to listOf("src/Calc.kt")), ownPr = 7)
        assertTrue(t.contains("टीम का अपना"))
        assertTrue(t.contains("टकराव नहीं"))
    }
}
