package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): हर हिस्से (DK चैट, DK लाइव, DK कंप्यूटर + खोज, कोडिंग एजेंट) में अपने-आप fallback -
 * एक मॉडल की limit खत्म = उसी API का अगला फ्री मॉडल; उस API के सारे खत्म = अगली API.
 */
class ApiFallbackAllTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun everyApi_hasOwnFreeBackupModels() {
        for (id in listOf("groq", "openrouter", "zai", "nvidia")) {
            assertTrue("$id के फ्री मॉडल", Providers.fallbackModels(id).size >= 2)
        }
        // Gemini के बैकअप GeminiClient/GeminiModels खुद संभालते हैं (पुराना नियम वही)
        assertTrue(Providers.fallbackModels("gemini").isEmpty())
        assertTrue(Providers.fallbackModels("openrouter").all { it == "openrouter/free" || it.endsWith(":free") })   // पैसे न कटें
    }

    @Test fun backups_workEvenWithoutSettingsList() {
        // Settings में मॉडल सूची कभी न खोली (cache खाली) तब भी Groq/OpenRouter के बैकअप मॉडल
        val gq = ApiScopes.backupModels("groq", "openai/gpt-oss-120b", emptyList(), emptyList(), ApiScopes.BACKUP_MAX)
        assertTrue(gq.isNotEmpty()); assertFalse("openai/gpt-oss-120b" in gq)
        val or = ApiScopes.backupModels("openrouter", "x/y:free", emptyList(), emptyList(), ApiScopes.BACKUP_MAX)
        assertEquals("openrouter/free", or.first())
        assertTrue(or.size <= ApiScopes.BACKUP_MAX)
        val nv = ApiScopes.backupModels("nvidia", "meta/llama-3.3-70b-instruct", emptyList(), emptyList(), ApiScopes.BACKUP_MAX)
        assertTrue(nv.size >= 4); assertFalse("meta/llama-3.3-70b-instruct" in nv)
    }

    @Test fun crossOrder_chosenFirst_thenAllOthers() {
        assertEquals(listOf("nvidia", "gemini", "openrouter", "zai", "groq"), ApiScopes.crossOrder("nvidia"))
        assertEquals(ApiScopes.CROSS_ORDER, ApiScopes.crossOrder("gemini"))
        assertEquals(ApiScopes.PROVIDERS.toSet(), ApiScopes.crossOrder("zai").toSet())
        assertEquals(CoderAi.PC_CHAIN, ApiScopes.CROSS_ORDER)
    }

    @Test fun coder_usesCrossApiChain() {
        val s = src("CoderAi.kt")
        assertTrue(s.contains("fun forCoder("))
        assertTrue(s.contains("?: forCoder(c)"))
        assertTrue(s.contains("ApiScopes.crossOrder(Prefs.scopeActive(c, ApiScopes.CODER))"))
        assertTrue(s.contains("fun forScopeChain("))
        assertTrue(s.contains("forScopeChain(c, ApiScopes.PC, sink, PC_CHAIN)"))
        // एक-key मोड: पहले जैसा सिर्फ़ Gemini
        assertTrue(s.contains("if (Prefs.oneKeyOn(c)) return forScope(c, ApiScopes.CODER, sink)"))
        // 404 वाला मॉडल आख़िर में
        assertTrue(s.contains("Cooldown.block(goneKey(ai), GONE_MS)"))
        assertTrue(src("AiClients.kt").contains("if (e.code == 404) Cooldown.block(mKey(m)"))
    }

    @Test fun liveDeepThink_triesEveryApi() {
        assertEquals(listOf("zai", "nvidia", "groq"), LiveTextBrain.order { "k" })
        assertEquals(listOf("nvidia"), LiveTextBrain.order { if (it == "nvidia") "nvapi" else "" })
        assertTrue(LiveTextBrain.order { "" }.isEmpty())
        // पुराना pick() वही
        assertEquals("zai", LiveTextBrain.pick { "k" })
    }

    @Test fun computerSearch_tavilyFallsBackToSearchKey() {
        val s = src("ComputerTools.kt")
        assertTrue(s.contains("Prefs.tavilyKey(c).ifBlank { Prefs.tavilySearchKey(c) }"))
        assertEquals(listOf("tavily", "gemini"), ComputerTools.searchChain("tavily", true, true, false, false).take(2))
        assertEquals(listOf("gemini", "exa"), ComputerTools.searchChain("openrouter", true, false, false, true))
    }

    @Test fun chat_stillUsesEveryApiWithKey() {
        val s = src("BrainManager.kt")
        assertTrue(s.contains("val withKey = Providers.all.filter { clientFor(it) != null }"))
        assertTrue(src("Prefs.kt").contains("ApiScopes.BACKUP_MAX"))
    }
}
