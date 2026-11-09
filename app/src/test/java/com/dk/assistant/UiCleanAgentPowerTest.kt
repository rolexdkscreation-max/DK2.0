package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): फ़ोन/टैब पर एक जैसी स्क्रीन, चैट में कदम-लाइनें नहीं, छोटा स्वागत,
 * "क्या-क्या कर सकते हो" पर पूरी सूची, और DK कंप्यूटर: पहले योजना + एक जवाब में कई टूल.
 */
class UiCleanAgentPowerTest {

    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun fontScaleCapped_sameOnPhoneAndTab() {
        assertEquals(1.0f, UiFitRules.fontScale(1.3f))
        assertEquals(1.0f, UiFitRules.fontScale(2.0f))
        assertEquals(0.85f, UiFitRules.fontScale(0.85f))
        assertEquals(1.0f, UiFitRules.fontScale(Float.NaN))
        assertEquals(1.0f, UiFitRules.fontScale(0f))
    }

    @Test fun bothScreensUseTwoLineHeaderAndFontCap() {
        for (f in listOf("ComputerActivity.kt", "CodingActivity.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("UiFit.header(this, back, titles"))
            assertTrue(f, s.contains("UiFit.wrap(newBase)"))
            assertTrue(f, s.contains("ChatClean.showInChat(l.role"))
            assertTrue(f, s.contains("AgentIntro.isCapabilityQuestion("))
        }
    }

    @Test fun stepLinesHiddenFromChat() {
        // काम चलते समय कदम कभी नहीं
        assertFalse(ChatClean.showInChat("s", busy = true, isLast = true))
        assertFalse(ChatClean.showInChat("s", busy = false, isLast = false))
        // काम के बाहर की आखिरी सूचना (जैसे "नॉर्मल मोड चुना") दिखती है
        assertTrue(ChatClean.showInChat("s", busy = false, isLast = true))
        // योजना कार्ड चैट में नहीं (ऊपर पट्टी में)
        assertFalse(ChatClean.showInChat("p", busy = false, isLast = true))
        for (r in listOf("u", "d", "a", "e", "f", "i")) assertTrue(r, ChatClean.showInChat(r, busy = true, isLast = false))
        assertEquals("काम चल रहा है…", ChatClean.busyLabel(null, null))
        assertEquals("काम चल रहा है… (कदम 3/5)", ChatClean.busyLabel(2, 5))
        assertEquals("काम चल रहा है… (कदम 5/5)", ChatClean.busyLabel(5, 5))
    }

    @Test fun shortHello_fullListOnlyWhenAsked() {
        val h = AgentIntro.computerHello("DK")
        assertTrue(h.length < 80)
        assertFalse(h.contains("मैं कर सकता हूँ"))
        for (q in listOf("तुम क्या-क्या कर सकते हो?", "tum kya kya kar sakte ho", "What can you do?", "आप क्या कर सकते हैं"))
            assertTrue(q, AgentIntro.isCapabilityQuestion(q))
        for (q in listOf("Nashik के 5 स्कूलों की फ़ीस की PDF बनाओ", "", "नमस्ते"))
            assertFalse(q, AgentIntro.isCapabilityQuestion(q))
    }

    @Test fun capabilitiesFollowEnabledTools() {
        val all = AgentIntro.computerCapabilities("DK", ComputerAgent.TOOL_IDS)
        assertTrue(all.contains("असली ब्राउज़र") && all.contains("Excel") && all.contains("PDF रिपोर्ट"))
        assertFalse(all.contains("बंद हैं"))
        val some = AgentIntro.computerCapabilities("DK", listOf("web_search"))
        assertTrue(some.contains("इंटरनेट पर खोज"))
        assertFalse(some.contains("असली ब्राउज़र"))
        assertTrue(some.contains("बंद हैं"))
        val coder = AgentIntro.coderCapabilities(seven = true, github = true)
        assertTrue(coder.contains("PR") && coder.contains("मैनेजर"))
        assertTrue(AgentIntro.coderCapabilities(seven = false, github = false).contains("नॉर्मल मोड"))
    }

    @Test fun planFirstOnlyForRealTasks() {
        assertTrue(ComputerPlan.planFirst("Nashik के 5 अच्छे स्कूलों की फ़ीस की तुलना करके PDF रिपोर्ट बनाओ"))
        assertTrue(ComputerPlan.planFirst("दो काम:\nपहला यह"))
        assertFalse(ComputerPlan.planFirst("नमस्ते"))
        assertFalse(ComputerPlan.planFirst("thanks!"))
        assertFalse(ComputerPlan.planFirst("आज मौसम कैसा है"))
        assertTrue(ComputerPlan.PLAN_WAIT_MS in 5_000L..30_000L)
    }

    @Test fun manyToolsInOneReply() {
        val c = ComputerAgent.parse("{\"thought\":\"दोनों खोज\",\"step\":1,\"calls\":[{\"tool\":\"web_search\",\"args\":{\"query\":\"a\"}},{\"tool\":\"read_url\",\"args\":{\"url\":\"https://x.com\"}}]}")!!
        assertEquals("parallel", c.tool)
        assertEquals(2, c.args.getJSONArray("calls").length())
        assertEquals(1, c.step)
        // एक ही call = सीधे वही टूल
        val one = ComputerAgent.parse("{\"calls\":[{\"tool\":\"web_search\",\"args\":{\"query\":\"a\"}}]}")!!
        assertEquals("web_search", one.tool)
        assertEquals("a", one.args.getString("query"))
        assertTrue(ComputerAgent.PARALLEL_MAX >= 6)
        assertTrue(ComputerAgent.CONTROL_TOOLS.containsAll(listOf("done", "ask_user", "update_plan")))
    }

    @Test fun twoJsonObjects_firstIsUsed() {
        val c = ComputerAgent.parse("{\"tool\":\"web_search\",\"args\":{\"query\":\"x}\"}}\n{\"tool\":\"done\",\"args\":{\"answer\":\"y\"}}")
        assertNotNull(c)
        assertEquals("web_search", c!!.tool)
        assertEquals("x}", c.args.getString("query"))
        assertEquals("{\"a\":\"}\"}", ComputerAgent.firstObject("बस {\"a\":\"}\"} और {\"b\":1}"))
        assertNull(ComputerAgent.firstObject("{ अधूरा"))
    }

    @Test fun coderDefaultStepsRaised() {
        assertEquals(15, CodingAgent.DEFAULT_STEPS)
    }
}
