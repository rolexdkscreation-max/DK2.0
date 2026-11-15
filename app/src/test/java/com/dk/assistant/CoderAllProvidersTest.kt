package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (मालिक): Gemini की 5 keys खत्म → Groq → OpenRouter → Z.ai → NVIDIA, हर एक के कोडिंग वाले सबसे अच्छे मॉडल; सबकी limit पर भी काम न रुके */
class CoderAllProvidersTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun order_groqRightAfterGemini() {
        assertEquals(listOf("gemini", "groq", "openrouter", "zai", "nvidia"), CoderModels.order(ApiScopes.crossOrder("gemini")))
        assertEquals(listOf("nvidia", "gemini", "groq", "openrouter", "zai"), CoderModels.order(ApiScopes.crossOrder("nvidia")))
        assertTrue(src("CoderAi.kt").contains("CoderModels.order(ApiScopes.crossOrder(Prefs.scopeActive(c, ApiScopes.CODER)))"))
    }

    @Test fun nvidia_codingModelsFirst_smallLast() {
        val live = listOf("meta/llama-3.1-8b-instruct", "nvidia/nv-embedqa-e5-v5", "bigcode/starcoder2-15b", "moonshotai/kimi-k2.6",
            "qwen/qwen3-coder-480b-a35b-instruct", "nvidia/llama-3.1-nemoguard-8b-content-safety", "openai/gpt-oss-20b")
        val r = CoderModels.chain("nvidia", "meta/llama-3.3-70b-instruct", false, live)
        assertEquals("qwen/qwen3-coder-480b-a35b-instruct", r.first())
        assertEquals("moonshotai/kimi-k2.6", r[1])
        assertFalse(r.any { it.contains("embed") || it.contains("nemoguard") })
        assertEquals("meta/llama-3.1-8b-instruct", r.last())
    }

    @Test fun openrouter_onlyFree_routerLast() {
        val r = CoderModels.chain("openrouter", "openrouter/free", false,
            listOf("openrouter/free", "anthropic/claude-x", "google/gemma-4-31b-it:free", "cohere/north-mini-code:free"))
        assertEquals("cohere/north-mini-code:free", r.first())
        assertEquals("openrouter/free", r.last())
        assertFalse(r.contains("anthropic/claude-x"))
    }

    @Test fun zai_onlyFlash_userChoiceFirst_knownWhenNoList() {
        assertEquals(listOf("glm-4.7-flash", "glm-4.5-flash"), CoderModels.chain("zai", "glm-4.7-flash", false, listOf("glm-5", "glm-4.5-flash", "glm-4.7-flash")))
        assertEquals("my/model", CoderModels.chain("groq", "my/model", true, emptyList()).first())
        assertTrue(CoderModels.chain("groq", "openai/gpt-oss-120b", false, emptyList()).take(2).contains("openai/gpt-oss-120b"))
        assertTrue(CoderModels.chain("nvidia", "x", false, emptyList()).size >= 2)
    }

    @Test fun mixedErrors_stillWaitsForLimit() {
        assertEquals(60, CoderAi.limitWaitSec(listOf(429 to 0, 404 to 0, 401 to 0)))
        assertEquals(30, CoderAi.limitWaitSec(listOf(404 to 0, 429 to 30)))
        assertEquals(0, CoderAi.limitWaitSec(listOf(404 to 0, 401 to 0)))
        assertEquals(0, CoderAi.limitWaitSec(emptyList()))
        assertTrue(src("CoderAi.kt").contains("val wait = limitWaitSec(errs.map {"))
    }
}
