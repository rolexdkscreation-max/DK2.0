package com.dk.assistant.forge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.59: Forge का "वेबसाइट नहीं बनती" - पुराना 2.x मॉडल पहले चुना जाता था */
class ForgeModelRankTest {
    private fun m(name: String, vararg methods: String) =
        JSONObject().put("name", "models/$name").put("supportedGenerationMethods", JSONArray(methods.toList()))

    @Test fun newFlashBeatsLegacyFlashEvenIfListedFirst() {
        // Google की सूची में पुराने मॉडल पहले आते हैं - यही असली गड़बड़ थी
        val list = JSONArray()
            .put(m("gemini-2.0-flash", "generateContent"))
            .put(m("gemini-2.5-flash", "generateContent"))
            .put(m("gemini-2.5-flash-preview-tts", "generateContent"))
            .put(m("gemini-3.5-flash-lite", "generateContent"))
            .put(m("gemini-3.8-flash", "generateContent"))
            .put(m("gemini-3.8-pro", "generateContent"))
            .put(m("gemini-3.8-flash-live", "bidiGenerateContent"))
        val r = WebsiteForgeClient.rankModels(list)
        assertEquals("gemini-3.8-flash", r.first())
        assertEquals("gemini-3.8-flash", WebsiteForgeClient.chooseModel(list))
        assertTrue(r.indexOf("gemini-3.5-flash-lite") < r.indexOf("gemini-2.5-flash"))
        assertTrue(r.indexOf("gemini-3.8-pro") < r.indexOf("gemini-2.5-flash"))
        assertFalse(r.any { it.contains("tts") || it.contains("live") })
    }

    @Test fun fallbacksAreNewModels() {
        assertTrue(WebsiteForgeClient.FALLBACKS.isNotEmpty())
        assertTrue(WebsiteForgeClient.FALLBACKS.none { com.dk.assistant.GeminiModels.isLegacy(it) })
    }

    @Test fun goneModelMeansTryNext() {
        assertTrue(WebsiteForgeClient.tryNextModel(404, "This model is no longer available to new users"))
        assertTrue(WebsiteForgeClient.tryNextModel(429, "quota"))
        assertTrue(WebsiteForgeClient.tryNextModel(503, "overloaded"))
        assertFalse(WebsiteForgeClient.tryNextModel(403, "API key not valid"))
        assertFalse(WebsiteForgeClient.tryNextModel(400, "API key not valid. Please pass a valid API key."))
    }
}
