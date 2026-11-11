package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): 3.8 Flash पहले सारी Gemini keys पर, सब पर खत्म हो तब कम ताक़त वाला मॉडल - वह भी सारी keys पर */
class GeminiModelFirstTest {
    private val chain = GeminiModels.order(GeminiModels.CODER, GeminiModels.CODER_CHAIN)
    private val keys = listOf("k1", "k2", "k3")

    @Test fun bestModelOnEveryKeyBeforeLowerModel() {
        val s = GeminiModels.modelFirst(keys, { chain }) { _, _ -> false }
        assertEquals(listOf("k1", "k2", "k3").map { it to GeminiModels.CODER }, s.take(3))
        assertEquals(listOf("k1", "k2", "k3").map { it to "gemini-3.6-flash" }, s.subList(3, 6))
        assertEquals(chain.size * keys.size, s.size)
        // हर मॉडल-स्तर पूरा हो तब ही अगला स्तर
        assertEquals(chain, s.map { it.second }.distinct())
        assertEquals("gemini-3.1-flash-lite", s.last().second)
    }

    @Test fun limitHitPairsSkipped_restKeepOrder() {
        // k1, k2 पर 3.8 की limit खत्म -> पहले k3 पर 3.8, फिर ही 3.6 (k1 से)
        val s = GeminiModels.modelFirst(keys, { chain }) { k, m -> m == GeminiModels.CODER && k != "k3" }
        assertEquals("k3" to GeminiModels.CODER, s.first())
        assertEquals("k1" to "gemini-3.6-flash", s[1])
    }

    @Test fun allOnLimit_stillTriesEverything() {
        val s = GeminiModels.modelFirst(keys, { chain }) { _, _ -> true }
        assertEquals(chain.size * keys.size, s.size)
        assertEquals("k1" to GeminiModels.CODER, s.first())
    }

    @Test fun keyWithGoneModel_onlyItsOwnModels() {
        val s = GeminiModels.modelFirst(keys, { k -> if (k == "k2") chain - "gemini-3.6-flash" else chain }) { _, _ -> false }
        assertTrue(("k2" to "gemini-3.6-flash") !in s)
        assertEquals(listOf("k1", "k3"), s.filter { it.second == "gemini-3.6-flash" }.map { it.first })
    }

    @Test fun singleKey_sameAsOldOrder() {
        assertEquals(chain.map { "k1" to it }, GeminiModels.modelFirst(listOf("k1"), { chain }) { _, _ -> false })
    }

    @Test fun coderChainUsesModelFirstForGeminiKeys() {
        val f = listOf("app/src/main/java/com/dk/assistant/CoderAi.kt", "src/main/java/com/dk/assistant/CoderAi.kt").map(::File).first { it.exists() }.readText()
        assertTrue(f.contains("GeminiModels.modelFirst(g, { it.geminiOrder() })"))
        assertTrue(f.contains("ai.chatOne(system, msgs, media, only)"))
        assertTrue(f.contains("if (only != null) listOf(only) else"))
    }
}
