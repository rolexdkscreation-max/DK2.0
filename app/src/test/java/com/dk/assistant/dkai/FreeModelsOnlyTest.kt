package com.dk.assistant.dkai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक: "जो सिर्फ़ फ्री है, वही मॉडल वहाँ शो होना चाहिए"): मॉडल चिप में paid मॉडल नहीं */
class FreeModelsOnlyTest {

    private fun build(tier: ModelCatalog.Tier, lists: Map<String, List<String>>) =
        ModelCatalog.build(lists.keys.toList(), { tier }, { lists[it].orEmpty().map { m -> ModelCatalog.Meta(m) } }, { emptyList() },
            { it.id }, nowSec = 2_000_000_000L)

    private val lists = linkedMapOf(
        "gemini" to listOf("gemini-3.6-flash", "gemini-3.5-pro", "gemini-3.1-flash-live-preview", "gemini-3.5-flash-lite"),
        "openrouter" to listOf("meta-llama/llama-4-maverick:free", "openai/gpt-5.5", "anthropic/claude-opus-5"),
        "anthropic" to listOf("claude-opus-5-5", "claude-sonnet-5-5"),
        "openai" to listOf("gpt-6.1"),
        "groq" to listOf("llama-4-scout"),
    )

    @Test fun unknownTier_paidModelsHidden() {
        val all = build(ModelCatalog.Tier.UNKNOWN, lists)
        assertTrue(all.flatMap { it.entries }.any { it.model == "gemini-3.5-pro" })        // पहले यही दिखता था
        val g = ModelCatalog.onlyFree(all, geminiPaid = false)
        val models = g.flatMap { it.entries }.map { it.model }
        assertTrue(g.flatMap { it.entries }.none { it.paid })
        assertFalse(models.contains("gemini-3.5-pro"))
        assertFalse(models.contains("openai/gpt-5.5"))
        assertTrue(models.containsAll(listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-live-preview",
            "meta-llama/llama-4-maverick:free", "llama-4-scout")))
        // Claude / OpenAI में कोई फ्री नहीं = पूरा group ही नहीं
        assertEquals(listOf("gemini", "groq", "openrouter"), g.map { it.provider })
    }

    @Test fun paidTier_stillOnlyFree() {
        val g = ModelCatalog.onlyFree(build(ModelCatalog.Tier.PAID, lists), geminiPaid = false)
        assertTrue(g.flatMap { it.entries }.none { it.paid })
    }

    @Test fun geminiPaidSwitch_onlyGeminiPaidAllowed() {
        val models = ModelCatalog.onlyFree(build(ModelCatalog.Tier.PAID, lists), geminiPaid = true).flatMap { it.entries }.map { it.model }
        assertTrue(models.contains("gemini-3.5-pro"))
        assertFalse(models.contains("openai/gpt-5.5"))
        assertFalse(models.contains("claude-opus-5-5"))
    }

    @Test fun customApi_notTouched() {
        val custom = listOf(ModelCatalog.Group("my_api", "Mine", listOf(ModelCatalog.Entry("my_api", "x", "x", "", paid = true, supportsVoice = false))))
        assertEquals(custom, ModelCatalog.onlyFree(custom, false))
    }

    @Test fun chipAndSettingsUseFilter() {
        val src = { p: String -> File("src/main/java/com/dk/assistant/$p").readText() }
        assertTrue(src("dkai/DkaiModelList.kt").contains("ModelCatalog.onlyFree(allGroups(c)"))
        assertTrue(src("SettingsBrain.kt").contains("freeList(gem, list)"))
        assertTrue(src("SettingsBrain.kt").contains("freeList(p, list)"))
    }
}
