package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI दिमाग के 4 हिस्से: हर एक की अपनी API key, अपनी ही key के मॉडलों में fallback */
class ApiScopesTest {

    @Test fun fourScopes_liveIsGeminiOnly() {
        assertEquals(listOf("live", "chat", "coder", "pc"), ApiScopes.ALL)
        // DK लाइव: Gemini (Live/बोलना/सुनना) + Groq सिर्फ़ सुनने के लिए; OpenRouter/Custom नहीं
        assertEquals(listOf("gemini", "groq"), ApiScopes.providers(ApiScopes.LIVE))
        assertFalse("openrouter" in ApiScopes.providers(ApiScopes.LIVE))
        ApiScopes.ALL.filter { it != ApiScopes.LIVE }.forEach {
            assertEquals(listOf("gemini", "groq", "openrouter", "custom"), ApiScopes.providers(it))
        }
    }

    @Test fun prefNames_separatePerScope_chatKeepsOldNames() {
        assertEquals("key_gemini", ApiScopes.pref(ApiScopes.CHAT, "key", "gemini"))
        assertEquals("model_groq", ApiScopes.pref(ApiScopes.CHAT, "model", "groq"))
        assertEquals("live_key_gemini", ApiScopes.pref(ApiScopes.LIVE, "key", "gemini"))
        assertEquals("coder_key_groq", ApiScopes.pref(ApiScopes.CODER, "key", "groq"))
        assertEquals("pc_base_custom", ApiScopes.pref(ApiScopes.PC, "base", "custom"))
        // चारों के नाम अलग = एक की key दूसरे में नहीं जाती
        val names = ApiScopes.ALL.map { ApiScopes.pref(it, "key", "gemini") }
        assertEquals(4, names.distinct().size)
        assertEquals("active_provider", ApiScopes.activePref(ApiScopes.CHAT))
        assertEquals("coder_active", ApiScopes.activePref(ApiScopes.CODER))
        assertEquals("pc_active", ApiScopes.activePref(ApiScopes.PC))
    }

    private fun mig(vararg kv: Pair<String, String>) = ApiScopes.migrate { mapOf(*kv)[it] }

    @Test fun migrate_copiesOldSharedKeysToEveryScope() {
        val m = mig("key_gemini" to "AIzaCHAT_gemini_key_123", "model_gemini" to "gemini-3.8-flash",
            "key_groq" to "gsk_chat_groq_key_12345", "active_provider" to "groq")
        assertEquals("AIzaCHAT_gemini_key_123", m["live_key_gemini"])
        assertEquals("gsk_chat_groq_key_12345", m["live_key_groq"])
        assertEquals("AIzaCHAT_gemini_key_123", m["coder_key_gemini"])
        assertEquals("gemini-3.8-flash", m["pc_model_gemini"])
        assertEquals("groq", m["coder_active"])
        assertEquals("groq", m["pc_active"])
        assertEquals("1", m[ApiScopes.MIGRATED])
        // DK चैट की keys नहीं छूतीं (पुराने नाम ही)
        assertNull(m["key_gemini"])
    }

    @Test fun migrate_keepsOldSeparateVoiceAndCoderKeys() {
        val m = mig("key_gemini" to "AIzaCHAT_gemini_key_123", "voice_key" to "AIzaVOICE_key_456789", "voice_provider" to "gemini",
            "coder_provider" to "openrouter", "coder_key" to "sk-or-coder-key-123456", "coder_model" to "qwen/qwen3-coder:free",
            "pc_provider" to "same", "pc_model" to "gemini-3.1-flash-lite")
        assertEquals("AIzaVOICE_key_456789", m["live_key_gemini"])           // पहले की "आवाज़ के लिए अलग key"
        assertEquals("openrouter", m["coder_active"])
        assertEquals("sk-or-coder-key-123456", m["coder_key_openrouter"])
        assertEquals("qwen/qwen3-coder:free", m["coder_model_openrouter"])
        assertEquals("gemini", m["pc_active"])                               // "same" = तब चैट वाली
        assertEquals("gemini-3.1-flash-lite", m["pc_model_gemini"])
    }

    @Test fun migrate_groqVoiceKey_goesToLiveGroq() {
        val m = mig("voice_key" to "gsk_voice_only_key_1234")
        assertEquals("gsk_voice_only_key_1234", m["live_key_groq"])
        assertNull(m["live_key_gemini"])
    }

    @Test fun migrate_onlyOnce() {
        assertTrue(ApiScopes.migrate { if (it == ApiScopes.MIGRATED) "1" else "x" }.isEmpty())
        // नया फ़ोन (कोई key नहीं): बस निशान
        assertEquals(setOf(ApiScopes.MIGRATED, "coder_active", "pc_active"), mig().keys)
    }

    @Test fun backups_sameKeyOtherModels_only() {
        val or = ApiScopes.backupModels("openrouter", "deepseek/deepseek-r1:free",
            fast = emptyList(), cached = listOf("openai/gpt-4o", "deepseek/deepseek-r1:free", "qwen/qwen3-coder:free", "google/gemma-3-12b-it:free"))
        assertFalse("openai/gpt-4o" in or)                          // पेड मॉडल नहीं
        assertFalse("deepseek/deepseek-r1:free" in or)              // मुख्य दोबारा नहीं
        assertTrue("qwen/qwen3-coder:free" in or)
        assertTrue(or.size <= 4)

        val gq = ApiScopes.backupModels("groq", "llama-3.3-70b-versatile",
            fast = listOf("llama-3.1-8b-instant"), cached = listOf("whisper-large-v3", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-guard-4-12b"))
        assertEquals("llama-3.1-8b-instant", gq.first())             // तेज़ पहले
        assertFalse(gq.any { it.contains("whisper") || it.contains("guard") })
        assertTrue("openai/gpt-oss-20b" in gq)
        assertTrue(ApiScopes.backupModels("custom", "m", emptyList(), listOf("a", "b")).isEmpty())
    }

    @Test fun detect() {
        assertEquals("gemini", ApiScopes.detect("AIzaSyA1234567890123"))
        assertEquals("groq", ApiScopes.detect("gsk_1234567890123456"))
        assertEquals("openrouter", ApiScopes.detect("sk-or-v1-1234567890"))
        assertNull(ApiScopes.detect("short"))
    }
}
