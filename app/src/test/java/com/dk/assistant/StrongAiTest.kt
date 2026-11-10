package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: कोडर/डिबगर/प्लानर का मज़बूत (paid) मॉडल */
class StrongAiTest {
    private fun agentOf(m: Seven.Member) = Seven.Agent.values().first { it.member == m }

    @Test fun onlyCoderDebuggerPlanner_getStrong_restUnchanged() {
        for (a in Seven.Agent.values()) {
            val got = TeamModels.pick(a, "fast", "light", "strong")
            val want = if (a.member in setOf(Seven.Member.CODER, Seven.Member.DEBUGGER, Seven.Member.PLANNER)) "strong"
                else if (TeamModels.fast(a)) "fast" else "light"
            assertEquals(a.name, want, got)
        }
        // मज़बूत न हो = बिल्कुल पहले जैसा
        for (a in Seven.Agent.values()) assertEquals(a.name, if (TeamModels.fast(a)) "fast" else "light", TeamModels.pick<String>(a, "fast", "light", null))
        assertEquals("fast", TeamModels.pick(agentOf(Seven.Member.REVIEWER), "fast", "light", "strong"))
    }

    @Test fun emptyKey_meansOff() {
        assertNull(StrongAi.setup("anthropic", "", "claude-opus-5-5"))
        assertNull(StrongAi.setup("openai", "   ", null))
        assertNull(StrongAi.setup(null, null, null))
    }

    @Test fun setup_defaultsAndDetect() {
        val a = StrongAi.setup("anthropic", "sk-ant-api03-xxxxxxxx", "")!!
        assertEquals("anthropic", a.provider.id); assertEquals("claude-opus-5-5", a.model)
        assertEquals("https://api.anthropic.com/v1", a.base)
        // चुना Claude, पर key OpenRouter की = OpenRouter + उसका अपना मॉडल
        val o = StrongAi.setup("anthropic", "sk-or-v1-abcdefgh", "claude-opus-5-5")!!
        assertEquals("openrouter", o.provider.id); assertEquals("anthropic/claude-sonnet-5.5", o.model)
        // DeepSeek की key sk- = user की चुनी कंपनी, user का मॉडल
        val d = StrongAi.setup("deepseek", "sk-1234567890abcdef", " deepseek-v4-pro ")!!
        assertEquals("deepseek", d.provider.id); assertEquals("deepseek-v4-pro", d.model)
        val g = StrongAi.setup("gemini", "AIzaSyXXXXXXXXXXXX", "models/gemini-4-argon")!!
        assertTrue(g.provider.isGemini); assertEquals("gemini-4-argon", g.model)
        assertEquals("anthropic", StrongAi.choice("nahi-hai").id)
    }

    @Test fun newModels_noTemperature_rightTokenField_bigBudget() {
        val oa = StrongAi.choice("openai").provider
        assertEquals("max_completion_tokens", oa.tokenField); assertFalse(oa.sendTemp)
        assertFalse(StrongAi.choice("anthropic").provider.sendTemp)
        // पुरानी फ्री APIs पहले जैसी
        for (p in Providers.all) { assertEquals(p.id, "max_tokens", p.tokenField); assertTrue(p.id, p.sendTemp) }
        assertEquals(listOf("gemini", "groq", "openrouter", "zai", "nvidia"), Providers.all.map { it.id })
        for (m in listOf("claude-opus-5-5", "gpt-6.1-sol", "deepseek-v4-pro")) assertTrue(m, CoderContext.budgetFor(false, m) >= 500_000)
    }

    @Test fun strongFallsBackToOldChain() {
        val p = Providers.byId("groq")!!
        val backup = CoderAi(p, "gsk_xxxxxxxxxxxxxxxxxxxx", "openai/gpt-oss-120b", p.baseUrl,
            listOf(CoderAi(p, "gsk_xxxxxxxxxxxxxxxxxxxx", "llama-3.3-70b-versatile", p.baseUrl)))
        val st = StrongAi.setup("anthropic", "sk-ant-api03-xxxxxxxx", "")!!
        val s = CoderAi.strong(st, backup) { _, _ -> }
        assertEquals("claude-opus-5-5", s.model)
        val chain = s.fallbackList()
        assertEquals(listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), chain.map { it.model })
        assertSame(p, chain.first().provider)
        assertTrue(CoderAi.strong(st, null) { _, _ -> }.fallbackList().isEmpty())
    }

    @Test fun wiredInRunnerAndSettings() {
        val runner = File("src/main/java/com/dk/assistant/SevenRunner.kt").readText()
        assertEquals(3, Regex("""Ai\(ai, Prefs\.coderLightModel\(c\), CoderAi\.forStrong\(c, ai\)\)""").findAll(runner).count())
        val set = File("src/main/java/com/dk/assistant/SettingsCoder.kt").readText()
        assertTrue(set.contains("strongModelBox(box)"))
        val save = File("src/main/java/com/dk/assistant/SettingsActivity.kt").readText()
        assertTrue(save.contains("putString(StrongAi.PREF_KEY"))
    }
}
