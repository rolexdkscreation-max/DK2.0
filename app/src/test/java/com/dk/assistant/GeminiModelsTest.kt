package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelsTest {

    @Test fun legacyModels() {
        assertTrue(GeminiModels.isLegacy("gemini-2.5-flash"))
        assertTrue(GeminiModels.isLegacy("models/gemini-2.5-flash-lite"))
        assertTrue(GeminiModels.isLegacy("gemini-1.5-pro"))
        assertFalse(GeminiModels.isLegacy("gemini-3.8-flash"))
        assertFalse(GeminiModels.isLegacy("gemini-3.5-flash-lite"))
        assertFalse(GeminiModels.isLegacy("gemini-4-argon"))
    }

    @Test fun order_dropsLegacyBackups_keepsChosen() {
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-flash-lite"),
            GeminiModels.order("gemini-3.5-flash-lite", listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash")))
        // पुराना user जिसने खुद 2.5 चुना - वही पहले, backup नए
        assertEquals(listOf("gemini-2.5-flash", "gemini-3.8-flash"), GeminiModels.order("gemini-2.5-flash", listOf("gemini-3.8-flash", "gemini-2.5-flash-lite")))
    }

    @Test fun backupLists_haveNoLegacy() {
        assertTrue(GeminiClient.BACKUP_MODELS.none { GeminiModels.isLegacy(it) })
        assertTrue(GeminiClient.DEEP_MODELS.none { GeminiModels.isLegacy(it) })
    }

    @Test fun retryDelay() {
        assertEquals(37, GeminiModels.retryDelaySec("""{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay": "37s"}]}}"""))
        assertEquals(13, GeminiModels.retryDelaySec("Quota exceeded. Please retry in 12.5s."))
        assertEquals(0, GeminiModels.retryDelaySec("bad request"))
        assertEquals(20, GeminiModels.waitBeforeRetry(429, 20))
        assertEquals(0, GeminiModels.waitBeforeRetry(429, 0))
        assertEquals(0, GeminiModels.waitBeforeRetry(429, 300))
        assertEquals(3, GeminiModels.waitBeforeRetry(503, 0))
    }

    @Test fun best_hidesGoneModelError() {
        val errs = listOf("gemini-3.5-flash-lite" to (429 to "Quota exceeded"),
            "gemini-2.5-flash" to (404 to "This model models/gemini-2.5-flash is no longer available to new users."))
        assertEquals("gemini-3.5-flash-lite", GeminiModels.best(errs, { it.first }, { it.second })?.first)
        val onlyGone = listOf("gemini-x" to (404 to "not found"))
        assertEquals("gemini-x", GeminiModels.best(onlyGone, { it.first }, { it.second })?.first)
        assertTrue(GeminiModels.hindi(429, "").contains("429"))
        assertTrue(GeminiModels.hindi(404, "no longer available to new users").contains(GeminiModels.CODER))
    }
}
