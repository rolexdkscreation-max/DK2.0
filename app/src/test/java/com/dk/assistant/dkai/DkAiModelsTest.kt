package com.dk.assistant.dkai

import com.dk.assistant.AiRouter
import com.dk.assistant.backend.BackendWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक): One AI की key डली = चिप में उसके मॉडल; ऑल-राउंडर key = DK सर्वर के सारे मॉडल भी,
 * "DK AI ..." नाम से (कंपनी का नाम नहीं), सबसे ताकतवर ऊपर, चुना = सर्वर पर वही पहले.
 */
class DkAiModelsTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    private val server = listOf(
        "gemini" to "gemini-3.8-flash", "gemini-lite" to "gemini-3.5-flash-lite", "mistral" to "mistral-small-latest",
        "groq" to "openai/gpt-oss-120b", "cloudflare" to "@cf/openai/gpt-oss-120b", "openrouter" to "openrouter/free",
        "nvidia" to "meta/llama-3.3-70b-instruct", "anthropic" to "claude-sonnet-5-5",
        "oneai" to "moonshotai-kimi-k3.1-pro:free", "oneai" to "nvidia-nemotron-3-ultra-550b-a55b:free", "oneai" to "grok-4.7-fast:free",
    )

    @Test fun namesAreDkAiWithoutCompany() {
        assertEquals("DK AI 3.8 Flash", DkAiNames.label("gemini", "gemini-3.8-flash"))
        assertEquals("DK AI 3.5 Flash Lite", DkAiNames.label("gemini-lite", "gemini-3.5-flash-lite"))
        assertEquals("DK AI OSS 120B", DkAiNames.label("groq", "openai/gpt-oss-120b"))
        assertEquals("DK AI 3.3 70B", DkAiNames.label("nvidia", "meta/llama-3.3-70b-instruct"))
        assertEquals("DK AI K3.1 Pro", DkAiNames.label("oneai", "moonshotai-kimi-k3.1-pro:free"))
        assertEquals("DK AI 3 Ultra 550B", DkAiNames.label("oneai", "nvidia-nemotron-3-ultra-550b-a55b:free"))
        assertEquals("DK AI M3.1 Flash", DkAiNames.label("oneai", "minimaxai-minimax-m3-1-flash:free"))
        assertEquals("DK AI Sonnet 5.5", DkAiNames.label("anthropic", "claude-sonnet-5-5"))
        assertEquals("DK AI Nova", DkAiNames.label("openrouter", "openrouter/free"))
        val brands = listOf("gemini", "gpt", "llama", "claude", "kimi", "nemotron", "grok", "mistral", "openai", "google", "nvidia", "free")
        for (r in DkAiNames.rank(server)) {
            assertTrue(r.label, r.label.startsWith("DK AI "))
            for (b in brands) assertFalse("${r.label} में $b", r.label.lowercase().contains(b))
        }
    }

    @Test fun strongestFirstAndUniqueNames() {
        val rows = DkAiNames.rank(server)
        assertEquals(server.size, rows.size)
        assertEquals(rows.size, rows.map { it.label.lowercase() }.toSet().size)
        val powers = rows.map { DkAiNames.power(it.provider, it.model) }
        assertEquals(powers.sortedDescending(), powers)
        assertEquals("सबसे ताकतवर", rows.first().tier)
        assertTrue(rows.indexOfFirst { it.provider == "gemini" } < rows.indexOfFirst { it.provider == "gemini-lite" })
        assertTrue(DkAiNames.power("oneai", "nvidia-nemotron-3-ultra-550b-a55b:free") > DkAiNames.power("oneai", "grok-4.7-fast:free"))
        // "gemini" के अंदर "mini" = छोटा मॉडल नहीं माना जाता
        assertTrue(DkAiNames.power("gemini", "gemini-3.8-flash") > DkAiNames.power("gemini-lite", "gemini-3.5-flash-lite"))
        // एक जैसा मॉडल दो कड़ियों पर = दूसरे के आगे छोटा टैग
        assertTrue(rows.any { it.label == "DK AI OSS 120B" } && rows.any { it.label == "DK AI OSS 120B Edge" })
    }

    @Test fun realIdStaysInside() {
        val g = DkAiModels.groupOf(server)!!
        assertEquals(AiRouter.PROVIDER_ID, g.provider)
        assertTrue(g.entries.all { it.provider == DkAiModels.ID && !it.paid && !it.supportsVoice })
        val e = g.entries.first { it.title == "DK AI 3.8 Flash" }
        assertEquals("gemini" to "gemini-3.8-flash", DkAiNames.decode(e.model))
        assertEquals("oneai" to "z-ai-glm-5.3:free", DkAiNames.decode(DkAiNames.encode("oneai", "z-ai-glm-5.3:free")))
        assertNull(DkAiNames.decode("bina-kadi"))
        assertNull(DkAiModels.groupOf(emptyList()))
    }

    @Test fun serverListParsing() {
        val ok = BackendWire.serverModels("""{"ok":true,"server_ai":true,"models":[{"provider":"gemini","model":"gemini-3.8-flash"},
            {"provider":"oneai","model":"z-ai-glm-5.3:free"},{"provider":"","model":"x"},{"provider":"gemini","model":"gemini-3.8-flash"}]}""")
        assertEquals(listOf("gemini" to "gemini-3.8-flash", "oneai" to "z-ai-glm-5.3:free"), ok)
        assertTrue(BackendWire.serverModels("""{"ok":true,"server_ai":false,"models":[{"provider":"gemini","model":"m"}]}""").isEmpty())
        assertTrue(BackendWire.serverModels("गड़बड़").isEmpty())
    }

    @Test fun wiring() {
        val sheet = src("dkai/DkaiSheets.kt")
        assertTrue(sheet.contains("listOfNotNull(DkAiModels.group(c)) + DkaiModelList.groups(c)"))
        assertTrue(sheet.contains("listOfNotNull(com.dk.assistant.OneAi.group(c))"))
        assertTrue(sheet.contains("if (com.dk.assistant.OneAi.hasKey(c) &&"))
        assertTrue(sheet.contains("DkAiModels.refresh(c)"))
        assertTrue(src("dkai/DkAiModels.kt").contains("Prefs.oneKeyOn(c) && AiRouter.backendReady() && AiRouter.serverAiOk()"))
        val bm = src("BrainManager.kt")
        assertTrue(bm.contains("com.dk.assistant.dkai.DkAiModels.picked(ctx)"))
        assertTrue(bm.contains("ch.p.id == AiRouter.PROVIDER_ID) return"))
        val r = src("AiRouter.kt")
        assertTrue(r.contains("put(\"first\", first)"))
        assertTrue(src("backend/BackendApi.kt").contains("getJson(c, \"/ai/models\")"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("DkAiModels.labelOf(c, ch.model)"))
    }
}
