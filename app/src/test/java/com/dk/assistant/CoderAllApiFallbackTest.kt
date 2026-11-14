package com.dk.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): सिंगल कोडिंग एजेंट - "जितनी भी API दी गई है सब पर fallback (Gemini की 5वीं key भी न चले तो OpenRouter, Z.ai, NVIDIA…)"
 * + नया UI बदलना ☰ मेनू में नहीं, Settings में और DK लाइव से बोलकर.
 */
class CoderAllApiFallbackTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun spareKeys_everyProvidedKey_inOrder_noDuplicates() {
        val saved = mapOf(
            (ApiScopes.CODER to "gemini") to "AIza1", (ApiScopes.CODER to "gemini5") to "AIza5",
            (ApiScopes.PC to "gemini") to "AIza1",                      // वही key दो जगह = एक बार
            (ApiScopes.PC to "nvidia") to "nvapi-x", (ApiScopes.CHAT to "openrouter") to "sk-or-1",
            (ApiScopes.CHAT to "zai") to "abc.def", (ApiScopes.LIVE to "groq") to "gsk_1",
            (ApiScopes.LIVE to "nvidia") to "nvapi-live",               // DK लाइव में NVIDIA नहीं होता - नहीं लेना
        )
        val r = ApiScopes.spareKeys({ sc, slot -> saved[sc to slot].orEmpty() }, emptySet())
        assertEquals(listOf("gemini", "gemini", "openrouter", "zai", "nvidia", "groq"), r.map { it.second })
        assertEquals(listOf("AIza1", "AIza5"), r.filter { it.second == "gemini" }.map { it.third })
        assertFalse(r.any { it.third == "nvapi-live" })
        assertEquals(ApiScopes.PC, r.first { it.second == "nvidia" }.first)
        // जो chain में पहले से है वह दोबारा नहीं
        val r2 = ApiScopes.spareKeys({ sc, slot -> saved[sc to slot].orEmpty() }, setOf("gemini" to "AIza1", "nvidia" to "nvapi-x"))
        assertEquals(listOf("AIza5", "sk-or-1", "abc.def", "gsk_1"), r2.map { it.third })
        assertTrue(ApiScopes.spareKeys({ _, _ -> "" }, emptySet()).isEmpty())
    }

    @Test fun coder_appendsAllOtherKeys() {
        val s = src("CoderAi.kt")
        assertTrue(s.contains("fun forCoder(c: Context, sink: (String, String) -> Unit): CoderAi? = withSpares(c, coderOwn(c, sink), sink)"))
        assertTrue(s.contains("ApiScopes.spareKeys("))
        assertTrue(s.contains("Prefs.scopeKeyRaw(c, sc, slot)"))
        // पुरानी chain वैसी ही (पहले कोडिंग एजेंट की अपनी keys)
        assertTrue(s.contains("ApiScopes.crossOrder(Prefs.scopeActive(c, ApiScopes.CODER))"))
    }

    /** 14 नवंबर 2026 (मालिक: "नया UI पूरा हटाओ, पुराना रखो"): नया/क्लासिक बदलने वाला स्विच हटा - एक ही होम */
    @Test fun oneHome_switchRemoved() {
        val look = src("SettingsLook.kt")
        assertFalse(look.contains("card(\"होम का रूप (नया UI)\""))
        assertFalse(look.contains("Dkai.set("))
        assertTrue(DkSettings.ITEMS.none { it.id == "dkai_home" })
        assertTrue(SettingsSearch.search("नया UI").none { it.card == "होम का रूप (नया UI)" })
        assertTrue(src("dkai/DkaiScreen.kt").contains("fun on(c: android.content.Context) = true"))
        val main = src("MainActivity.kt")
        assertFalse(main.contains("Dkai.set("))
    }
}
