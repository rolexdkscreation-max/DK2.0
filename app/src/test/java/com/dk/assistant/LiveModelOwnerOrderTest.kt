package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक): 3.1 Flash Live सबसे ऊपर (1.2-2 s), 2.5 native-audio 09-2025 आख़िर में (4-6 s) */
class LiveModelOwnerOrderTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    private val m31 = "gemini-3.1-flash-live-preview"
    private val m09 = "gemini-2.5-flash-native-audio-preview-09-2025"
    private val m12 = "gemini-2.5-flash-native-audio-preview-12-2025"

    @Test fun pin31First_09Last() {
        assertEquals(listOf(m31, "gemini-3.8-live", m12, m09), LiveModelRank.ownerPin(listOf(m09, "gemini-3.8-live", m31, m12)))
        assertEquals(listOf("gemini-3.8-live", m12), LiveModelRank.ownerPin(listOf("gemini-3.8-live", m12)))   // सूची में नहीं = कुछ नहीं जुड़ता
        assertEquals(listOf(m31), LiveModelRank.ownerPin(listOf(m31)))
    }

    @Test fun fallbackListOrder() {
        assertEquals(m31, GeminiLive.FALLBACK_MODELS.first())
        assertEquals(m09, GeminiLive.FALLBACK_MODELS.last())
    }

    @Test fun ownerPickStillFirst() {
        // मालिक ने खुद मॉडल चुना = वही पहले (ownerPin के बाद LiveModelPick.order)
        val o = LiveModelPick.order("gemini-3.8-live", LiveModelRank.ownerPin(listOf("gemini-3.8-live", m31, m09)))
        assertEquals("gemini-3.8-live", o.first())
    }

    @Test fun wired() {
        assertTrue(src("GeminiLiveSetup.kt").contains("val all = LiveModelRank.ownerPin(LiveStay.demoteKnown1011(LiveModelRank.order(found, FALLBACK_MODELS, lat)))"))
        assertTrue(src("GeminiLiveRace.kt").contains("if (live && resumeId == null && models.firstOrNull() == LiveModelRank.OWNER_FIRST)"))
    }
}
