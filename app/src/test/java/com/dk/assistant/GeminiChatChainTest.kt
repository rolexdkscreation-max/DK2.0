package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 21 नवंबर 2026 (मालिक): DK चैट / DK लाइव की text बातचीत - पक्का fallback क्रम
 * 3.5 Flash-Lite → 3.1 Flash-Lite → Gemma 4 31B, हर key पर (Key 1 → 2 → 3), आख़िरी 10 बातें, 1024 tokens.
 */
class GeminiChatChainTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun chainOrder_exact() {
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemma-4-31b-it"), GeminiChatChain.MODELS)
        assertEquals(GeminiChatChain.MODELS, GeminiChatChain.models())
        assertEquals(GeminiChatChain.MODELS, GeminiChatChain.models("gemini-3.8-flash"))           // keepFirst नहीं = चुना मॉडल नहीं
    }

    @Test fun noLegacyNoLowRpd_inChain() {
        for (m in GeminiChatChain.MODELS) {
            assertFalse(m, GeminiModels.isLegacy(m))
            assertFalse(m, m in GeminiChatChain.LOW_RPD)
            assertFalse(m, m.contains("1.5") || m.contains("2.0") || m.contains("2.5"))
            assertFalse(m, m.startsWith("gemini-3.8-flash") || m.startsWith("gemini-3.6-flash"))
        }
        // पुराना (1.x / 2.x) चुना हो तब भी सबसे पहले नहीं
        assertEquals(GeminiChatChain.MODELS, GeminiChatChain.models("gemini-2.5-flash", keepFirst = true))
        assertEquals("gemini-3-flash-preview", GeminiChatChain.models("models/gemini-3-flash-preview", keepFirst = true).first())
    }

    @Test fun window_last10_startsWithUser() {
        val h = (1..30).map { Turn(if (it % 2 == 1) "user" else "model", "t$it") }
        val w = GeminiChatChain.window(h)
        assertEquals(10, w.size)
        assertEquals("user", w.first().role)
        assertEquals("t30", w.last().text)
        val w2 = GeminiChatChain.window((1..31).map { Turn(if (it % 2 == 1) "user" else "model", "t$it") })
        assertEquals(9, w2.size); assertEquals("user", w2.first().role)        // पहली बात model की = हटी
        assertTrue(GeminiChatChain.window(listOf(Turn("model", "x"), Turn("user", " "))).isEmpty())
        assertEquals(10, GeminiChatChain.HISTORY_MAX)
        assertEquals(1024, GeminiChatChain.MAX_OUTPUT)
    }

    @Test fun fallbackOn429_503_only() {
        assertTrue(GeminiChatChain.shouldFallback(429))
        assertTrue(GeminiChatChain.shouldFallback(503))
        assertTrue(GeminiChatChain.shouldFallback(0, "RESOURCE_EXHAUSTED: quota"))
        assertTrue(GeminiChatChain.shouldFallback(0, "The model is overloaded"))
        assertFalse(GeminiChatChain.shouldFallback(400, "API key not valid"))
        assertTrue(GeminiChatChain.keyBroken(400, "API key not valid. Please pass a valid API key."))
        assertTrue(GeminiChatChain.keyBroken(401, ""))
        assertFalse(GeminiChatChain.keyBroken(429, "RESOURCE_EXHAUSTED"))
    }

    @Test fun plan_keyFirst_thenNextKey() {
        val p = GeminiChatChain.plan(listOf("K1", " K2 ", "", "K1", "K3"))
        assertEquals(9, p.size)
        assertEquals("K1" to "gemini-3.5-flash-lite", p[0])
        assertEquals("K1" to "gemma-4-31b-it", p[2])
        assertEquals("K2" to "gemini-3.5-flash-lite", p[3])
        assertEquals("K3" to "gemma-4-31b-it", p[8])
        assertEquals(3, GeminiChatChain.plan(listOf("ONE")).size)          // एक-key मोड = एक key, तीन मॉडल
    }

    @Test fun logs_andBusyMessage() {
        assertEquals("[AI Service] Served by: gemini-3.5-flash-lite", GeminiChatChain.servedLog("gemini-3.5-flash-lite"))
        assertEquals("[AI Service] 429 hit on 3.5-flash-lite, switching to 3.1-flash-lite...",
            GeminiChatChain.switchLog(429, "gemini-3.5-flash-lite", "gemini-3.1-flash-lite"))
        assertTrue(GeminiChatChain.servedLog("gemma-4-31b-it", 2, 3).endsWith("(key 2/3)"))
        assertTrue(GeminiChatChain.allBusy(listOf(429, 503, 429)))
        assertFalse(GeminiChatChain.allBusy(listOf(429, 400)))
        assertFalse(GeminiChatChain.allBusy(emptyList()))
        assertEquals("Server busy, please try again in a minute.", GeminiChatChain.BUSY_EN)
        assertFalse(GeminiChatChain.searchToolOk("gemma-4-31b-it"))
        assertTrue(GeminiChatChain.searchToolOk("gemini-3.5-flash-lite"))
    }

    @Test fun wired_chatAndLive_keys_settings() {
        val a = src("AiClients.kt")
        assertTrue(a.contains("if (chatChain && !only) return completeChain(system, history, prompt, imageB64)"))
        assertTrue(a.contains("if (chatChain && !only) return streamChain(system, history, prompt, onDelta)"))
        assertTrue(a.contains("finally { key = homeKey; maxOut = 700 }"))
        assertTrue(a.contains("if (!GeminiChatChain.isGemma(m)) gen.put(\"responseMimeType\", \"application/json\")"))
        assertTrue(a.contains("x-goog-api-key"))
        assertFalse(a.contains("AIza"))                                       // key कभी code में नहीं
        val b = src("BrainManager.kt")
        assertTrue(b.contains("c.chatChain = true"))
        assertTrue(b.contains("c.chainKeys = Prefs.textChatKeys(ctx, fromLive)"))
        assertTrue(b.contains("client.chatChain = true; client.chainKeys = Prefs.textChatKeys(ctx, fromLive)"))
        assertTrue(b.contains("return Lang.pick(GeminiChatChain.BUSY_HI, GeminiChatChain.BUSY_EN)"))
        val p = src("Prefs.kt")
        assertTrue(p.contains("fun chatGeminiKeys(c: Context)"))
        assertTrue(p.contains("GeminiModels.distinctKeys(liveGeminiKeys(c) + chatGeminiKeys(c))"))
        val s = src("SettingsBrain.kt")
        assertTrue(s.contains("keyFields[ApiScopes.pref(scope, \"key\", \"gemini2\")]"))
        assertTrue(s.contains("keyFields[ApiScopes.pref(scope, \"key\", \"gemini3\")]"))
        assertTrue(SecretNames.isSecret(ApiScopes.pref(ApiScopes.CHAT, "key", "gemini2")))
    }
}
