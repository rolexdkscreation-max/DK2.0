package com.dk.assistant.dkai

import com.dk.assistant.AiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 20 नवंबर 2026 (मालिक): मॉडल चिप हर दिमाग के काम में - केंद्रीय selectedModel, आवाज़ का नियम (supportsVoice),
 * कंपनी के हिसाब से अपने-आप बनने वाली लिस्ट, और फेल होने पर साफ़ संदेश.
 */
class SelectedModelCatalogTest {

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    // ------------------------------------------------------------ केंद्रीय selectedModel
    @Test fun autoOrEmptyMeansOldRouting() {
        assertNull(SelectedModel.decode(null, null, false))
        assertNull(SelectedModel.decode("", "gemini-3.6-flash", false))
        assertNull(SelectedModel.decode(SelectedModel.AUTO, SelectedModel.AUTO, false))
        val ch = SelectedModel.decode("groq", "models/llama-4-scout", false)
        assertEquals("llama-4-scout", ch?.model)
        assertFalse(ch!!.supportsVoice)
        // आवाज़ वाला मॉडल: flag नाम से भी पक्का
        assertTrue(SelectedModel.decode("gemini", "gemini-3.1-flash-live-preview", false)!!.supportsVoice)
    }

    @Test fun chosenGeminiModelGoesFirstWithoutDuplicates() {
        assertEquals(listOf("a", "b", "c"), SelectedModel.firstOf(null, listOf("a", "b", "c")))
        assertEquals(listOf("c", "a", "b"), SelectedModel.firstOf("models/c", listOf("a", "b", "C")))
        assertTrue(SelectedModel.same("models/Gemini-3.6-Flash", "gemini-3.6-flash"))
    }

    // ------------------------------------------------------------ गलती का संदेश
    @Test fun failureReasonsAreShortAndClear() {
        assertTrue(SelectedModel.reason(AiException(429, "Quota exceeded ... limit: 0")).contains("paid"))
        assertEquals("limit खत्म", SelectedModel.reason(AiException(429, "rate")))
        assertEquals("key गलत", SelectedModel.reason(AiException(401, "bad")))
        assertEquals("मॉडल अब उपलब्ध नहीं", SelectedModel.reason(AiException(404, "nf")))
        assertTrue(SelectedModel.reason(AiException(503, "x")).contains("सर्वर"))
        assertEquals("नेट/समय की दिक्कत", SelectedModel.reason(java.net.SocketTimeoutException("t")))
        val t = SelectedModel.failText("llama-4-scout", "limit खत्म", "Gemini Flash")
        assertTrue(t.contains("नहीं चला")); assertTrue(t.contains("limit खत्म")); assertTrue(t.contains("Gemini Flash"))
        assertTrue(SelectedModel.failText("x", "गलती", null).contains("fallback"))
    }

    // ------------------------------------------------------------ आवाज़ का नियम
    @Test fun supportsVoiceFlag() {
        assertTrue(ModelCaps.supportsVoice("gemini", "gemini-2.5-flash-native-audio-preview-12-2025"))
        assertTrue(ModelCaps.supportsVoice("gemini", "models/gemini-live-2.5-flash"))
        assertFalse(ModelCaps.supportsVoice("gemini", "gemini-3.6-flash"))
        assertFalse(ModelCaps.supportsVoice("groq", "some-live-model"))
        assertFalse(ModelCaps.supportsVoice("gemini", "gemini-live-transcribe"))
    }

    // ------------------------------------------------------------ लिस्ट
    private fun build(tier: ModelCatalog.Tier, lists: Map<String, List<ModelCatalog.Meta>>, providers: List<String> = lists.keys.toList()) =
        ModelCatalog.build(providers, { tier }, { lists[it].orEmpty() }, { emptyList() }, { it.id }, nowSec = 2_000_000_000L)

    @Test fun onlyKeyedProvidersGroupedNewestFirst() {
        val g = build(ModelCatalog.Tier.PAID, mapOf(
            "groq" to listOf(ModelCatalog.Meta("llama-4-scout", created = 1_960_000_000), ModelCatalog.Meta("qwen-3-32b", created = 1_990_000_000)),
            "gemini" to listOf(ModelCatalog.Meta("gemini-3.6-flash"), ModelCatalog.Meta("gemini-3.8-flash"), ModelCatalog.Meta("gemini-3.1-pro")),
        ))
        assertEquals(listOf("gemini", "groq"), g.map { it.provider })   // तय क्रम
        assertEquals("gemini-3.8-flash", g[0].entries.first().model)    // सबसे नया ऊपर
        assertEquals("qwen-3-32b", g[1].entries.first().model)
        // जिसकी key नहीं - उसकी कंपनी नहीं
        assertTrue(build(ModelCatalog.Tier.PAID, mapOf("groq" to listOf(ModelCatalog.Meta("x-1"))), providers = emptyList()).isEmpty())
    }

    @Test fun hidesOldDeprecatedEmbeddingImage() {
        val g = build(ModelCatalog.Tier.PAID, mapOf("gemini" to listOf(
            ModelCatalog.Meta("gemini-3.6-flash"),
            ModelCatalog.Meta("gemini-embedding-001"), ModelCatalog.Meta("imagen-4.0-generate"),
            ModelCatalog.Meta("gemini-1.5-flash"), ModelCatalog.Meta("gemini-3.2-flash", deprecated = true),
            ModelCatalog.Meta("gemini-2.5-flash-preview-tts"),
            ModelCatalog.Meta("gemini-2.5-flash-native-audio-preview-12-2025", voice = true),
        )))
        val ids = g.single().entries.map { it.model }
        assertTrue("gemini-3.6-flash" in ids)
        assertFalse(ids.any { it.contains("embedding") || it.contains("imagen") || it.contains("1.5") || it.contains("3.2") || it.contains("tts") })
        val voice = g.single().entries.single { it.model.contains("native-audio") }
        assertTrue(voice.supportsVoice)
        // बहुत पुराना (कंपनी की तारीख 3 साल से पहले)
        val old = build(ModelCatalog.Tier.PAID, mapOf("groq" to listOf(ModelCatalog.Meta("old-9", created = 1_000), ModelCatalog.Meta("new-9", created = 1_990_000_000))))
        assertEquals(listOf("new-9"), old.single().entries.map { it.model })
    }

    @Test fun freeKeyShowsOnlyFreeModels() {
        val list = mapOf("openrouter" to listOf(ModelCatalog.Meta("openai/gpt-6"), ModelCatalog.Meta("x/y:free")))
        assertEquals(listOf("x/y:free"), build(ModelCatalog.Tier.FREE, list).single().entries.map { it.model })
        assertEquals(2, build(ModelCatalog.Tier.PAID, list).single().entries.size)
        // पता नहीं = सब दिखाओ (गलती आए तो साफ़ संदेश)
        assertEquals(2, build(ModelCatalog.Tier.UNKNOWN, list).single().entries.size)
    }

    @Test fun cacheAndTierHelpers() {
        val l = listOf(ModelCatalog.Meta("a", 5, voice = true, deprecated = false, name = "A\tB"), ModelCatalog.Meta("b"))
        val d = ModelCatalog.decode(ModelCatalog.encode(l))
        assertEquals(listOf("a", "b"), d.map { it.id }); assertTrue(d[0].voice); assertEquals(5L, d[0].created)
        assertEquals(ModelCatalog.Tier.PAID, ModelCatalog.geminiProbe(200, ""))
        assertEquals(ModelCatalog.Tier.FREE, ModelCatalog.geminiProbe(429, "quota ... limit: 0"))
        assertEquals(ModelCatalog.Tier.UNKNOWN, ModelCatalog.geminiProbe(500, ""))
        assertEquals(ModelCatalog.Tier.FREE, ModelCatalog.openRouterTier("""{"data":{"is_free_tier":true}}"""))
        assertEquals(ModelCatalog.Tier.PAID, ModelCatalog.openRouterTier("""{"data":{"is_free_tier":false}}"""))
        assertEquals(ModelCatalog.Tier.UNKNOWN, ModelCatalog.openRouterTier("oops"))
        assertEquals(ModelCatalog.Tier.FREE, ModelCatalog.tierOf(ModelCatalog.tierName(ModelCatalog.Tier.FREE)))
        assertTrue(ModelCatalog.version("gemini-3.8-flash") > ModelCatalog.version("gemini-3.6-flash"))
        assertEquals(24L * 60 * 60 * 1000, DkaiModelList.MAX_AGE_MS)
    }

    // ------------------------------------------------------------ wiring (हर दिमाग के काम में)
    @Test fun chipWiredEverywhere() {
        assertTrue(src("dkai/DkaiPick.kt").contains("SelectedModel.set(c, SelectedModel.Choice(provider, model, voice))"))
        assertTrue(src("dkai/DkaiSheets.kt").contains("SelectedModel.set(c, null)"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("if (ch == null) \"Auto\""))
        val bm = src("BrainManager.kt")
        assertTrue(bm.contains("val chip = chipPick()")); assertTrue(bm.contains("chipText(system, prompt, null)?.let"))
        assertTrue(src("CoderAi.kt").contains("withChip(c, ApiScopes.CODER"))
        assertTrue(src("CoderAi.kt").contains("withChip(c, ApiScopes.PC"))
        assertTrue(src("StoryWriter.kt").contains("SelectedModel.resolve(c) ?: return auto"))
        assertTrue(src("LiveModelPick.kt").contains("SelectedModel.voiceModel(c)"))
        assertTrue(src("talk/TalkPrefs.kt").contains("split(c) || com.dk.assistant.dkai.SelectedModel.textVoice(c)"))
        assertTrue(src("DKAccessibilityService.kt").contains("TalkPrefs.wanted(this)"))
        for (f in listOf("ScreenLook.kt", "tools/ToolAgent.kt", "agent/android/SocialCaption.kt", "research/DeepResearchRun.kt", "forge/ForgeController.kt"))
            assertTrue(f, src(f).contains("SelectedModel.geminiOrder("))
        assertTrue(src("insights/InsightsAgent.kt").contains("SelectedModel.geminiText(svc)"))
        assertTrue(src("SettingsBrain.kt").contains("refreshCatalog(this@geminiPaidSwitch, force = true)"))
    }
}
