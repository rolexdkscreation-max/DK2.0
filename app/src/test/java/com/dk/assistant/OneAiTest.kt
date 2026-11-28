package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 28 नवंबर 2026 (मालिक): One AI (oneaiapikey.com) - ऐप वाला हिस्सा */
class OneAiTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    @After fun reset() { AiRouter.readyOverride = null; AiRouter.freeOverride = null; AiRouter.oneAiOverride = null }

    @Test fun freeOnlyByIdSuffix() {
        assertTrue(OneAi.isFree("z-ai-glm-5.3-flash:free"))
        assertTrue(OneAi.isFree("grok-4.7-fast:FREE"))
        assertFalse(OneAi.isFree("z-ai-glm-5.3-flash"))
        assertFalse(OneAi.isFree("free-model"))
        assertTrue(OneAi.label("grok-4.7-fast:free").endsWith("• मुफ़्त"))
        assertEquals("gpt-x", OneAi.label("gpt-x"))
    }

    @Test fun bannedAndOrder() {
        assertTrue(OneAi.banned("some-uncensored-7b:free"))
        assertTrue(OneAi.banned("x-roleplay:free"))
        assertTrue(OneAi.banned("stepfun-stepaudio-2:free"))
        val live = OneAi.FAST + OneAi.BIG + listOf("paid-model", "a-uncensored:free")
        val chat = OneAi.order("chat", live, null)
        assertEquals(OneAi.FAST.take(OneAi.MAX_TRIES), chat)
        assertEquals(OneAi.BIG.take(OneAi.MAX_TRIES), OneAi.order(AiRouter.Task.CODER, live, null))
        assertEquals(OneAi.BIG.take(OneAi.MAX_TRIES), OneAi.order(AiRouter.Task.RESEARCH, live, null))
        // चुना मॉडल पहले - पर सिर्फ़ :free, असली सूची में, बिना मना वाले शब्द
        assertEquals("z-ai-glm-5.3:free", OneAi.order("chat", live, "z-ai-glm-5.3:free").first())
        assertFalse(OneAi.order("chat", live, "paid-model").contains("paid-model"))
        assertFalse(OneAi.order("chat", live, "a-uncensored:free").contains("a-uncensored:free"))
        // असली सूची में नहीं = नहीं
        assertFalse(OneAi.order("chat", listOf("grok-4.7-fast:free"), null).contains(OneAi.FAST.first()))
        // ठंडा (हाल में फेल) मॉडल पीछे
        assertEquals(OneAi.FAST[1], OneAi.order("chat", live, null) { it == OneAi.FAST[0] }.first())
        assertTrue(OneAi.order("chat", live, null).all { it.endsWith(":free") })
    }

    @Test fun keyOrderOwnThenOthersThenAllRounder() {
        val prefs = mapOf("coder_key_oneai" to "sk-coder-1234", "key_oneai" to "sk-chat-1234", OneAi.ALL_KEY to "sk-all-12345", "pc_key_oneai" to "sk-chat-1234")
        val k = OneAi.keyOrder(ApiScopes.CODER) { prefs[it].orEmpty() }
        assertEquals(listOf("sk-coder-1234", "sk-chat-1234", "sk-all-12345"), k)
        assertEquals("sk-chat-1234", OneAi.keyOrder(ApiScopes.CHAT) { prefs[it].orEmpty() }.first())
        assertTrue(OneAi.keyOrder(ApiScopes.LIVE) { "" }.isEmpty())
        assertEquals(ApiScopes.PC, OneAi.scopeOf(AiRouter.Task.COMPUTER))
        assertEquals(ApiScopes.LIVE, OneAi.scopeOf(AiRouter.Task.LIVE))
    }

    @Test fun keysAreSecret() {
        for (n in listOf("key_oneai", "live_key_oneai", "coder_key_oneai", "pc_key_oneai", OneAi.ALL_KEY))
            assertTrue(n, SecretNames.isSecret(n))
        assertFalse(SecretNames.isSecret(OneAi.ONLY_PREF))
    }

    @Test fun testMessages() {
        assertTrue(OneAi.testMsg(200, listOf("a:free", "b")).startsWith("✓"))
        assertTrue(OneAi.testMsg(200, listOf("a:free", "b")).contains("1 मुफ़्त"))
        assertTrue(OneAi.testMsg(401, emptyList()).startsWith("✗"))
        assertTrue(OneAi.testMsg(403, emptyList()).startsWith("✗"))
        assertFalse(OneAi.testMsg(0, emptyList()).startsWith("✓"))
        assertEquals(listOf("m1:free", "m2"), OneAi.parseModels("""{"data":[{"id":"m1:free"},{"id":"m2"},{"id":""}]}"""))
        assertTrue(OneAi.parseModels("not json").isEmpty())
    }

    @Test fun sseAndLimits() {
        assertEquals("नमस्ते", OneAi.delta("""{"choices":[{"delta":{"content":"नमस्ते"}}]}"""))
        assertEquals(null, OneAi.delta("[DONE]"))
        assertEquals(null, OneAi.delta("""{"choices":[{"delta":{}}]}"""))
        assertTrue(runCatching { OneAi.delta("""{"error":{"code":429,"message":"x"}}""") }.exceptionOrNull() is AiException)
        assertEquals(4_000L, OneAi.FIRST_TOKEN_MS)
        val now = 86_400_000L * 100 + 3_600_000L   // 01:00 UTC
        assertEquals(23 * 3_600_000L, OneAi.msToUtcMidnight(now))
        assertFalse(OneAi.next(kotlinx.coroutines.CancellationException("x")))
        assertTrue(OneAi.next(AiException(429, "x")))
    }

    @Test fun chipGroupHasSeparateFreeBadge() {
        val g = OneAi.groupOf(listOf("b-paid", "a:free", "x-roleplay:free"))!!
        assertEquals(listOf("a:free", "b-paid"), g.entries.map { it.model })
        assertEquals(OneAi.BADGE, g.entries[0].sub)
        assertFalse(g.entries[0].paid)
        assertTrue(g.entries[1].paid)
        assertEquals(null, OneAi.groupOf(emptyList()))
    }

    @Test fun routerOneAiBeforeServerQuietly() {
        // ऐप में One AI key = सर्वर न हो / मुफ़्त वाला हो तब भी अगली कड़ी (One AI) आज़माई जाती है
        AiRouter.readyOverride = false
        AiRouter.oneAiOverride = true
        assertTrue(AiRouter.fallback(AiException(429, "limit")))
        assertTrue(AiRouter.fallback(null))
        assertFalse(AiRouter.fallback(kotlinx.coroutines.CancellationException("x")))
        AiRouter.oneAiOverride = false
        assertFalse(AiRouter.fallback(AiException(429, "limit")))
        val r = src("AiRouter.kt")
        assertTrue(r.contains("val one = OneAi.client(c, task) ?: return server"))
        assertTrue(r.contains("OneAiThenServer(one) { server.takeIf { backendReady() && serverAiOk() } }"))
    }

    @Test fun wired() {
        val o = src("OneAi.kt")
        assertTrue(o.contains("X-Fallback-From"))                 // पैसे वाला बदला जवाब नहीं
        assertTrue(o.contains("lock.tryAcquire(QUEUE_WAIT_MS"))    // एक key = एक समय में एक
        assertTrue(o.contains("https://oneaiapikey.com/console/sign-up"))
        assertTrue(src("dkai/DkaiSheets.kt").contains("if (com.dk.assistant.OneAi.onlyOn(c)) listOfNotNull(com.dk.assistant.OneAi.group(c))"))
        val s = src("SettingsBrain.kt")
        assertTrue(s.contains("oneAiAllCard(box)   // 28 नवंबर 2026 (मालिक): Gemini कार्ड के नीचे वैसा ही One AI कार्ड"))
        for (sc in listOf("ApiScopes.LIVE", "ApiScopes.CHAT", "scope")) assertTrue(sc, s.contains("oneAiScopeCard($sc)"))
        assertTrue(s.contains("OneAi.onlyOn(this)"))
        assertTrue(src("CoderAi.kt").contains("if (provider.id == OneAi.ID) return AiRouter.oneAiChat("))
        assertTrue(src("dkai/SelectedModel.kt").contains("ch.provider == com.dk.assistant.OneAi.ID"))
        // DK Live की आवाज़ (Gemini Live) नहीं छुई
        assertFalse(src("GeminiLive.kt").contains("OneAi"))
        // One AI पुरानी सूचियों में नहीं (रेडियो बटन / key क्रम वही)
        assertFalse(Providers.all.any { it.id == OneAi.ID })
        assertFalse(ApiScopes.PROVIDERS.contains(OneAi.ID))
    }
}
