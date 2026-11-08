package com.dk.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026 (मालिक): हर AI दिमाग हिस्से में Z.ai और NVIDIA का विकल्प; Extra API हटी */
class ZaiNvidiaTest {
    @Test fun providersPresent_customGone() {
        val ids = Providers.all.map { it.id }
        assertTrue("zai" in ids && "nvidia" in ids)
        assertFalse("custom" in ids)
        assertEquals("glm-4.7-flash", Providers.byId("zai")!!.defaultModel)
        assertEquals("https://integrate.api.nvidia.com/v1", Providers.byId("nvidia")!!.baseUrl)
        // चैट / कोडिंग / कंप्यूटर तीनों में
        for (sc in listOf(ApiScopes.CHAT, ApiScopes.CODER, ApiScopes.PC)) {
            assertTrue("zai" in ApiScopes.providers(sc) && "nvidia" in ApiScopes.providers(sc))
        }
        // DK लाइव: आवाज़ Gemini, सोच Z.ai / NVIDIA
        assertEquals(listOf("zai", "nvidia"), ApiScopes.LIVE_TEXT)
    }

    @Test fun detectKeys() {
        assertEquals("nvidia", ApiScopes.detect("nvapi-abcdefghijklmnopqrstuvwxyz"))
        assertEquals("zai", ApiScopes.detect("0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMnOp"))
        assertEquals("gemini", ApiScopes.detect("AIzaSyAbcdefghijklmnopqrstuvwxyz"))
        assertNull(ApiScopes.detect("sk-abcdefghijklmnopqrstuvwxyz0123"))
    }

    @Test fun zaiThinkingOffForSpeed() {
        val b = JSONObject()
        assertTrue(ThinkLevel.applyOpenAi(b, "https://api.z.ai/api/paas/v4", "glm-4.7-flash", ThinkLevel.AUTO, "low"))
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
        val h = JSONObject()
        ThinkLevel.applyOpenAi(h, "https://api.z.ai/api/paas/v4", "glm-4.7-flash", ThinkLevel.HIGH, null)
        assertEquals("enabled", h.getJSONObject("thinking").getString("type"))
        ThinkLevel.strip(h); assertFalse(h.has("thinking"))
        // दूसरी कंपनियों पर कुछ नहीं बदला
        val g = JSONObject()
        assertFalse(ThinkLevel.applyOpenAi(g, "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", ThinkLevel.AUTO, "low"))
    }

    @Test fun fallbackModels_andChain() {
        assertEquals("glm-4.7-flash", Providers.fallbackModels("zai").first())
        assertTrue(Providers.fallbackModels("gemini").isEmpty())
        assertTrue(ApiScopes.backupModels("zai", "glm-4.7-flash", emptyList(), emptyList()).none { it == "glm-4.7-flash" })
        assertEquals(listOf("gemini", "openrouter", "zai", "nvidia", "groq"), CoderAi.PC_CHAIN)
    }

    @Test fun liveTextPick() {
        assertNull(LiveTextBrain.pick { "" })
        assertEquals("nvidia", LiveTextBrain.pick { if (it == "nvidia") "nvapi-x" else "" })
        assertEquals("zai", LiveTextBrain.pick { "k" })
    }
}
