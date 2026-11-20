package com.dk.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक): कंपनी ने बंद किए मॉडल (410 Gone) हटाओ, उनकी जगह चालू फ्री मॉडल */
class RetiredModelsTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun deadModels_goneFromLists() {
        val dead = listOf("meta/llama-3.3-70b-instruct", "nvidia/llama-3.3-nemotron-super-49b-v1.5", "openai/gpt-oss-120b")
        assertTrue(Providers.fallbackModels("nvidia").none { it in dead })
        assertEquals("nvidia/nemotron-3.5-lightning-30b-a3b", Providers.byId("nvidia")!!.defaultModel)
        assertEquals("nvidia/nemotron-3.5-lightning-30b-a3b", Providers.fallbackModels("nvidia").first())
        val g = Providers.fallbackModels("groq")
        assertFalse("llama-3.3-70b-versatile" in g || "llama-3.1-8b-instant" in g)
        assertEquals("openai/gpt-oss-120b", g.first())
    }

    @Test fun savedDeadModel_movesToLiveOne() {
        assertEquals("nvidia/nemotron-3.5-lightning-30b-a3b", Providers.current("nvidia", "meta/llama-3.3-70b-instruct"))
        assertEquals("openai/gpt-oss-120b", Providers.current("groq", "llama-3.3-70b-versatile"))
        // Groq पर gpt-oss-120b चालू है - सिर्फ़ NVIDIA वाला बदले
        assertEquals("openai/gpt-oss-120b", Providers.current("groq", "openai/gpt-oss-120b"))
        assertEquals("glm-4.7-flash", Providers.current("zai", "glm-4.7-flash"))
        // नया (बदला) मॉडल खुद बंद सूची में नहीं
        for ((id, m) in Providers.RETIRED) for (to in m.values) assertFalse("$id $to", to in m.keys)
        assertTrue(src("Prefs.kt").contains("return Providers.current(id, "))
    }

    @Test fun gone410_triesNextModel() {
        val s = src("AiClients.kt")
        assertTrue(s.contains("e.code == 404 || e.code == 410 || e.code == 429"))
        assertTrue(s.contains("if (e.code == 410) Cooldown.block(mKey(m), 24 * 60 * 60 * 1000L)"))
    }

    @Test fun nemotron_thinkingOffForSpeed() {
        val b = JSONObject()
        assertTrue(ThinkLevel.applyOpenAi(b, "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3.5-lightning-30b-a3b", ThinkLevel.AUTO, "low"))
        assertFalse(b.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        ThinkLevel.strip(b); assertFalse(b.has("chat_template_kwargs"))
        // HIGH चुना हो तो सोचने दो
        val h = JSONObject()
        ThinkLevel.applyOpenAi(h, "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3.5-lightning-30b-a3b", ThinkLevel.HIGH, null)
        assertFalse(h.has("chat_template_kwargs"))
    }
}
