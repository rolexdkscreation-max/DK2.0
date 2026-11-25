package com.dk.assistant

import com.dk.assistant.Seven.Agent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 25 नवंबर 2026 (मालिक): कोडिंग एजेंट कदम-दर-कदम - पहले समझे और असली कोड देखकर बताए, फिर काम, फिर खुद जाँच,
 * खुद रिव्यू + डिबग, और आख़िर में बदली फ़ाइलें / कैसे इस्तेमाल करें / screenshot.
 */
class CoderStepsReviewTest {

    private val calc = "package app\n\nfun add(a: Int, b: Int): Int {\n    return a + b\n}\n"
    private val managerFeature = """{"kind":"feature","brief":"add sub()","acceptance":["sub(5,3)=2"]}"""

    @Test fun phases_stepByStep_withReviewAndScreenshot() {
        val g = Solo.phases(github = true)
        assertTrue(g.first().contains("खोजकर पढ़ना"))
        assertTrue(g.any { it.contains("खुद रिव्यू") })
        assertTrue(g.any { it.contains("unit tests") })
        assertTrue(g.last().contains("screenshot") && g.last().contains("कौन-सी फ़ाइल बदली"))
        assertFalse(Solo.phases(github = false).any { it.contains("unit tests") })
        assertTrue(Solo.phasesText(true).contains("1. ") && Solo.phasesText(true).contains("🪜"))
    }

    @Test fun miniDiff_showsOnlyChangedPart() {
        val d = Solo.miniDiff("a.kt", "l1\nl2\nl3\nl4", "l1\nX\nl3\nl4")
        assertTrue(d, d.contains("- l2") && d.contains("+ X"))
        assertFalse(d.contains("l1\n") || d.contains("- l3"))
        assertEquals(2, Solo.changedLines(d))
        assertTrue(Solo.miniDiff("n.kt", null, "a\nb").contains("(NEW FILE)"))
        assertTrue(Solo.miniDiff("o.kt", "x", null).contains("DELETED"))
    }

    @Test fun ownReview_onlyForBigChanges() {
        assertFalse(Solo.needsOwnReview(1, 5))
        assertTrue(Solo.needsOwnReview(2, 3))
        assertTrue(Solo.needsOwnReview(1, 40))
        val o = Solo.reviewOrder("=== a.kt ===\n+ x", listOf("sub(5,3)=2"))
        assertTrue(o.startsWith("SELF-REVIEW") && o.contains("sub(5,3)=2") && o.contains("'- ' line"))
    }

    @Test fun systemPrompt_reviewReportAndScreens() {
        assertTrue(Solo.SYSTEM.contains("YOUR OWN DIFF"))
        assertTrue(Solo.SYSTEM.contains("बदली फ़ाइलें") && Solo.SYSTEM.contains("कैसे इस्तेमाल करें"))
        assertTrue(Solo.SYSTEM.contains(Shots.EXTRA))
        assertTrue(Solo.SYSTEM.contains("\${tag}_20_name"))
    }

    @Test fun screenshotRequest_vsNewFeature() {
        assertTrue(Shots.wantsShots("स्क्रीनशॉट दो"))
        assertTrue(Shots.wantsShots("कैसा दिखता है screenshot भेजो"))
        assertFalse(Shots.wantsShots("ऐप में screenshot लेने का फ़ीचर जोड़ो"))
        assertFalse(Shots.wantsShots("सेटिंग में नया बटन बनाओ"))
        assertTrue(Shots.uiChanged(listOf("app/src/main/java/com/dk/assistant/SettingsVoice.kt")))
        assertTrue(Shots.uiChanged(listOf("app/src/main/res/layout/x.xml")))
        assertFalse(Shots.uiChanged(listOf("app/src/main/java/com/dk/assistant/GitHub.kt")))
        assertTrue(Shots.reportNote(listOf("app/src/main/res/layout/x.xml"), true).contains("स्क्रीनशॉट दो"))
        assertEquals("", Shots.reportNote(listOf("a.kt"), false))
    }

    @Test fun askWhatWillYouDo_readsCodeFirst() {
        assertTrue(Seven.wantsExplain("यह काम कैसे करोगे?"))
        assertTrue(Seven.wantsExplain("पहले बताओ क्या करोगे"))
    }

    @Test fun finalBrief_hasAgentReportAndScreenshotLine() {
        val t = MergeBrief.text("✅ काम हो गया", "sub जोड़ो", listOf("Calc.kt" to "sub जोड़ा"), "", "पक्का", true, "https://p", true,
            coder = "बदली फ़ाइलें: Calc.kt\nकैसे इस्तेमाल करें: sub(5,3)", shots = "📸 स्क्रीनशॉट: x")
        assertTrue(t.contains("🧠 एजेंट की रिपोर्ट") && t.contains("कैसे इस्तेमाल करें: sub(5,3)"))
        assertTrue(t.contains("📸 स्क्रीनशॉट: x"))
        assertTrue(t.indexOf("🔧") < t.indexOf("🧠") && t.endsWith(MergeBrief.ASK))
    }

    @Test fun checkClean_showsOwnDiff_inSameReply() = runBlocking {
        val ai = SoloCoderTest.FakeAi(Agent.MANAGER to managerFeature,
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\n    return a + b\n}\nFIND>>>\n<<<REPLACE\n    return a + b\n}\n\nfun sub(a: Int, b: Int) = a - b\nREPLACE>>>\nCHECK",
            Agent.CODER to "DONE: Add sub\nबदली फ़ाइलें: Calc.kt")
        val git = SoloCoderTest.FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1", stage = ""))))
        val out = SevenTeam(ai, MemoryFiles(mapOf("src/Calc.kt" to calc)), git) { _, _ -> }.apply { solo = true }.run("sub जोड़ो")
        assertEquals(true, out.testOk)
        val afterCheck = ai.history[2].last().second
        assertTrue(afterCheck, afterCheck.contains("YOUR DIFF") && afterCheck.contains("+ fun sub(a: Int, b: Int) = a - b"))
        assertTrue(out.brief, out.brief.contains("बदली फ़ाइलें: Calc.kt"))
    }

    @Test fun bigChange_withoutCheck_getsOwnReviewRound_beforeDone() = runBlocking {
        val ai = SoloCoderTest.FakeAi(Agent.MANAGER to managerFeature,
            Agent.CODER to "FILE: src/Calc.kt\n<<<FIND\n    return a + b\n}\nFIND>>>\n<<<REPLACE\n    return a + b\n}\n\nfun sub(a: Int, b: Int) = a - b\nREPLACE>>>\n" +
                "FILE: src/Sub.kt\n<<<FILE\npackage app\n\nconst val TWO = 2\nFILE>>>\nDONE: Add sub\nजोड़ा",
            Agent.CODER to "DONE: Add sub\nरिव्यू किया, सब ठीक")
        val git = SoloCoderTest.FakeGit(ArrayDeque(listOf(TeamTest(true, "", "https://ci/1", stage = ""))))
        val f = MemoryFiles(mapOf("src/Calc.kt" to calc))
        val out = SevenTeam(ai, f, git) { _, _ -> }.apply { solo = true }.run("sub जोड़ो")
        assertEquals(true, out.testOk)
        assertEquals(listOf(Agent.MANAGER, Agent.CODER, Agent.CODER), ai.calls)
        val review = ai.history[2].last().second
        assertTrue(review, review.contains("SELF-REVIEW") && review.contains("src/Sub.kt (NEW FILE)") && review.contains("sub(5,3)=2"))
        assertEquals(1, git.log.count { it.startsWith("push:") })
    }
}
