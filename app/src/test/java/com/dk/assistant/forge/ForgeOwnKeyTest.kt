package com.dk.assistant.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): वेबसाइट बिल्डर की अपनी अलग API key (Settings → AI दिमाग → वेबसाइट बिल्डर) -
 * वेबसाइट सिर्फ़ उसी key से, दूसरी कोई key नहीं; वेबसाइट के लिए सबसे अच्छा मॉडल अपने-आप दिखे.
 */
class ForgeOwnKeyTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    @Test fun forgeUsesOnlyItsOwnKey() {
        val c = src("forge/ForgeController.kt")
        assertTrue(c.contains("fun apiKey(c: Context): String = Prefs.forgeKey(c)"))
        // अब चैट / कोडिंग / लाइव की key उधार नहीं
        assertFalse(c.contains("Prefs.scopeKey(c, ApiScopes.CHAT, \"gemini\")"))
        assertFalse(c.contains("Prefs.ttsGeminiKey(c)"))
        assertTrue(c.contains("WebsiteForgeClient.streamKeys(Prefs.forgeKeys(c)"))
        assertTrue(c.contains("Prefs.forgeModelToUse(c)"))
        val p = src("Prefs.kt")
        assertTrue(p.contains("const val FORGE_KEY = \"forge_key_gemini\""))
        assertTrue(p.contains("const val FORGE_KEY2 = \"forge_key_gemini2\""))
        assertTrue(p.contains("fun forgeModelToUse(c: Context): String = forgeModel(c).ifBlank { forgeAutoModel(c) }"))
    }

    @Test fun settingsHasWebsiteBuilderSection() {
        val b = src("SettingsBrain.kt")
        assertTrue(b.contains("menuRow(\"brain_web\""))
        assertTrue(b.contains("keyFields[Prefs.FORGE_KEY] = ed"))
        assertTrue(b.contains("keyFields[Prefs.FORGE_KEY2]"))
        assertTrue(b.contains("WebsiteForgeClient.pickBest(k)"))
        // एक-key मोड में भी दिखे: "if (one)" वाले return से पहले
        assertTrue(b.indexOf("menuRow(\"brain_web\"") < b.indexOf("    if (one) {\n        card(\"अलग-अलग key वाले हिस्से"))
        assertTrue(src("SettingsActivity.kt").contains("\"brain_web\" -> { title.text = \"AI दिमाग • वेबसाइट बिल्डर\"; secBrainWeb() }"))
        assertTrue(src("SettingsSearch.kt").contains("\"brain_web\" to \"AI दिमाग • वेबसाइट बिल्डर\""))
        assertTrue(src("forge/ForgeLauncher.kt").contains("putExtra(\"section\", \"brain_web\")"))
    }

    @Test fun ownKeyRunsEvenInOneKeyMode() {
        assertTrue(src("forge/ForgeController.kt").contains("if (apiKey(c).isBlank()) OneKeyGate.check(c, OneKeyGate.Work.WEBSITE)"))
        assertTrue(src("GeminiLiveTools.kt").contains("!(name == \"build_website\" && Prefs.forgeKey(svc).isNotBlank())"))
    }

    @Test fun bestWebsiteModelOrder() {
        val ranked = WebsiteForgeClient.rankForWebsite(listOf(
            "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.5-pro", "gemini-3-pro", "gemini-flash-latest"))
        assertEquals("gemini-3.5-pro", ranked[0])
        assertEquals("gemini-3-pro", ranked[1])
        assertEquals("gemini-3.5-flash", ranked[2])
        assertTrue(ranked.indexOf("gemini-3.5-flash-lite") > ranked.indexOf("gemini-3.5-flash"))
        assertEquals("gemini-2.5-flash", ranked.last())
        assertEquals(6, ranked.size)
    }

    @Test fun preferredModelFirst() {
        val l = listOf("a", "b", "c")
        assertEquals(l, WebsiteForgeClient.withPreferred(l, ""))
        assertEquals(listOf("c", "a", "b"), WebsiteForgeClient.withPreferred(l, "c"))
        assertEquals(listOf("x", "a", "b", "c"), WebsiteForgeClient.withPreferred(l, "models/x"))
    }

    @Test fun backupKeyOnlyOnKeyOrLimitErrors() {
        assertTrue(WebsiteForgeClient.nextKey(401, "API_KEY_INVALID"))
        assertTrue(WebsiteForgeClient.nextKey(403, null))
        assertTrue(WebsiteForgeClient.nextKey(429, "RESOURCE_EXHAUSTED"))
        assertFalse(WebsiteForgeClient.nextKey(200, null))
    }
}
