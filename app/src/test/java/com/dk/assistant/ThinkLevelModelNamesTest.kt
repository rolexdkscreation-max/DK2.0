package com.dk.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: AI दिमाग → मॉडल के साफ़ नाम + विवरण, और सोचने का स्तर (Auto/Low/Medium/High) */
class ThinkLevelModelNamesTest {

    @Test fun modelNamesLikeGeminiApp() {
        assertEquals("3.5 Flash-Lite", ModelNames.fromId("gemini-3.5-flash-lite"))
        assertEquals("3.8 Flash", ModelNames.fromId("gemini-3.8-flash"))
        assertEquals("3.1 Pro (Preview)", ModelNames.fromId("gemini-3.1-pro-preview"))
        assertEquals("4 Argon", ModelNames.fromId("gemini-4-argon"))
        assertEquals("GPT-OSS 120B", ModelNames.fromId("openai/gpt-oss-120b"))
        assertEquals("Llama 3.3 70B Instruct • फ्री", ModelNames.fromId("meta-llama/llama-3.3-70b-instruct:free"))
        // API का नाम हो तो वही (Gemini शब्द हटाकर, "कंपनी: " हटाकर)
        assertEquals("3.8 Flash", ModelNames.label("gemini-3.8-flash", "Gemini 3.8 Flash"))
        assertEquals("Llama 3.3 70B", ModelNames.label("x/y", "Meta: Llama 3.3 70B"))
        assertEquals("3.8 Flash", ModelNames.label("gemini-3.8-flash", null))
    }

    @Test fun shortDescriptions() {
        assertTrue(ModelNames.desc("gemini-3.5-flash-lite").contains("Fastest"))
        assertTrue(ModelNames.desc("gemini-3.8-flash").contains("All-around"))
        assertTrue(ModelNames.desc("gemini-3.1-pro-preview").contains("reasoning"))
        assertTrue(ModelNames.desc("gemini-4-argon").contains("Complex"))
        assertTrue(ModelNames.desc("openai/gpt-oss-120b").isNotBlank())
    }

    @Test fun namesEncodeDecode() {
        val m = mapOf("gemini-3.8-flash" to "Gemini 3.8 Flash", "a" to "B")
        assertEquals(m, ModelNames.decode(ModelNames.encode(m)))
    }

    @Test fun levels() {
        assertEquals(listOf("auto", "low", "medium", "high"), ThinkLevel.ALL)
        assertEquals(ThinkLevel.AUTO, ThinkLevel.norm(null))
        assertEquals(ThinkLevel.AUTO, ThinkLevel.norm("कुछ भी"))
        assertEquals(ThinkLevel.HIGH, ThinkLevel.norm(" High "))
        assertEquals("Medium", ThinkLevel.title("medium"))
        assertEquals("think_level_coder", ThinkLevel.pref(ApiScopes.CODER))
    }

    @Test fun geminiConfig_autoKeepsOldBehaviour() {
        assertNull(ThinkLevel.geminiConfig("gemini-3.8-flash", ThinkLevel.AUTO))
        assertEquals("high", ThinkLevel.geminiConfig("gemini-3.8-flash", "high")!!.getString("thinkingLevel"))
        assertEquals("low", ThinkLevel.geminiConfig("gemini-4-argon", "low")!!.getString("thinkingLevel"))
        assertEquals(8192, ThinkLevel.geminiConfig("gemini-2.5-flash", "medium")!!.getInt("thinkingBudget"))
        assertNull(ThinkLevel.geminiConfig("gemma-3-27b-it", "high"))
        assertEquals(0, ThinkLevel.extraTokens(ThinkLevel.AUTO))
        assertTrue(ThinkLevel.extraTokens(ThinkLevel.HIGH) > ThinkLevel.extraTokens(ThinkLevel.LOW))
    }

    @Test fun openAiEffort() {
        // AUTO = पुराना नियम: सिर्फ़ gpt-oss
        val a = JSONObject(); assertTrue(ThinkLevel.applyOpenAi(a, "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "auto", "low"))
        assertEquals("low", a.getString("reasoning_effort"))
        val b = JSONObject(); assertFalse(ThinkLevel.applyOpenAi(b, "https://api.groq.com/openai/v1", "llama-3.3-70b", "auto", "low"))
        assertFalse(b.has("reasoning_effort"))
        // चुना स्तर
        val c = JSONObject(); ThinkLevel.applyOpenAi(c, "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "high", "low")
        assertEquals("high", c.getString("reasoning_effort"))
        val d = JSONObject(); ThinkLevel.applyOpenAi(d, "https://openrouter.ai/api/v1", "any/model:free", "medium", null)
        assertEquals("medium", d.getJSONObject("reasoning").getString("effort"))
        // जो मॉडल नहीं समझता, उसे नहीं भेजते (400 से बचाव)
        val e = JSONObject(); assertFalse(ThinkLevel.applyOpenAi(e, "https://api.groq.com/openai/v1", "llama-3.3-70b", "high", null))
        assertTrue(ThinkLevel.rejected(400, "Unsupported parameter: reasoning_effort"))
        assertFalse(ThinkLevel.rejected(400, "API key not valid"))
        ThinkLevel.strip(c); assertFalse(c.has("reasoning_effort"))
    }

    @Test fun wiredIntoClientsAndSettings() {
        val all = SetSrc.all()
        assertTrue(all.contains("सोचने का स्तर (Thinking level)"))
        assertTrue(all.contains("pickModelThen("))
        assertTrue(all.contains("thinkRow(box, ThinkLevel.ONE)"))
        val dir = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        assertTrue(File(dir, "BrainManager.kt").readText().contains("ThinkLevel.get(ctx, scope)"))
        assertTrue(File(dir, "CoderAi.kt").readText().contains("applyThink(ThinkLevel.get(c, scope))"))
        assertTrue(File(dir, "AiClients.kt").readText().contains("ThinkLevel.geminiConfig(m, think)"))
    }
}
