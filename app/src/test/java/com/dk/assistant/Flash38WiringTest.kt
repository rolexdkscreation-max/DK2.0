package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "3.8 Flash सिर्फ़ कोडिंग एजेंट और DK कंप्यूटर के लिए; सारे एजेंट एक के बाद एक वही मॉडल; limit पर
 * Gemini keys 1-5, फिर OpenRouter / Z.ai / NVIDIA / Groq - वह fallback वैसा ही रहे". साथ में मैनेजर की याददाश्त हर एजेंट तक.
 */
class Flash38WiringTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun coderAndComputerDefaultTo38Flash() {
        assertEquals("gemini-3.8-flash", GeminiModels.CODER)
        assertEquals(GeminiModels.CODER, GeminiModels.CODER_CHAIN.first())
        val p = src("Prefs.kt")
        assertTrue(p.contains("(scope == ApiScopes.CODER || scope == ApiScopes.PC) && id == \"gemini\""))
        assertTrue(p.contains("pc_model_v380") && p.contains("coder_model_v370"))
        assertTrue(p.contains("coder_light_v380") && p.contains("cur.ifBlank { GeminiModels.CODER }"))
        // पुराना default flash-lite = एक बार 3.8 पर; user का अपना चुना (जैसे 3.6) नहीं बदलता
        assertTrue(GeminiModels.oldCoderDefault("gemini-3.5-flash-lite"))
        assertTrue(!GeminiModels.oldCoderDefault("gemini-3.6-flash"))
    }

    @Test fun lightAgentsUseCoderChainWhen38() {
        val r = src("SevenRunner.kt")
        assertTrue(r.contains("if (lightModel == GeminiModels.CODER) GeminiModels.CODER_CHAIN else GeminiModels.LIGHT_CHAIN"))
        // fallback क्रम वैसा ही
        assertEquals(listOf("gemini", "openrouter", "zai", "nvidia", "groq"), CoderAi.PC_CHAIN)
        assertTrue(src("CoderAi.kt").contains("forScopeChain(c, ApiScopes.PC, sink, PC_CHAIN)"))
        assertTrue(src("Prefs.kt").contains("scopeKey(c, ApiScopes.CODER, \"gemini5\")"))
    }

    @Test fun everyAgentGetsManagerBriefAndCanRead() {
        val t = src("SevenTeam.kt")
        assertTrue(t.contains("TeamMemory.HEADER + tb"))
        assertTrue(t.contains("memory.event(a.name, t)"))
        assertTrue(t.contains("SevenSymbols.apiOf("))
        assertTrue(t.contains("REVIEW_READS"))
        assertTrue(SevenTeam.CODER_LOOKUPS >= 8)
        assertTrue(SevenTeam.READS_PER_LOOKUP >= 8)
    }
}
