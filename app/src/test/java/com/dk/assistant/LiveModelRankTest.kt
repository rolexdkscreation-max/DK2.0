package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.41: DK लाइव सबसे नया नहीं, सबसे तेज़ मॉडल पहले; limit खत्म वाले पीछे */
class LiveModelRankTest {
    private val found = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-3.5-transcribe-live", "gemini-2.5-flash-live-translate")

    @Test fun neverPicksTranscribeOrTranslate() {
        val o = LiveModelRank.order(found, emptyList(), emptyMap())
        assertFalse(o.any { it.contains("transcri") || it.contains("translat") })
        assertEquals(3, o.size)
    }

    @Test fun withoutMeasurements_flashBeforeBigNewest() {
        val o = LiveModelRank.order(found, emptyList(), emptyMap())
        // पहले "3.8" सबसे ऊपर था; अब flash (तेज़) ऊपर
        assertEquals("gemini-3.1-flash-live-preview", o.first())
    }

    @Test fun measuredFastestWins() {
        val lat = mapOf("gemini-3.8-live" to 600L, "gemini-3.1-flash-live-preview" to 1400L,
            "gemini-2.5-flash-native-audio-preview-12-2025" to 900L)
        val o = LiveModelRank.order(found, emptyList(), lat)
        assertEquals(listOf("gemini-3.8-live", "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-3.1-flash-live-preview"), o)
    }

    @Test fun blockedGoLast_soNextFastestIsUsed() {
        val lat = mapOf("gemini-3.8-live" to 600L, "gemini-2.5-flash-native-audio-preview-12-2025" to 900L)
        val o = LiveModelRank.order(found, emptyList(), lat) { it == "gemini-3.8-live" }
        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", o.first())
        assertEquals("gemini-3.8-live", o.last())
    }

    @Test fun modelsOnThisKeyBeforeDocsFallback() {
        val o = LiveModelRank.order(listOf("gemini-3.8-live"), listOf("gemini-3.1-flash-live-preview"), emptyMap())
        assertEquals(listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"), o)
        // list नहीं आई = docs वाले ही, सबसे तेज़ पहले
        val f = LiveModelRank.order(emptyList(), listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"), emptyMap())
        assertEquals("gemini-3.1-flash-live-preview", f.first())
    }

    @Test fun blendIgnoresCrazySamples() {
        assertEquals(800L, LiveModelRank.blend(null, 800))
        assertNull(LiveModelRank.blend(800, 20_000))
        assertNull(LiveModelRank.blend(800, 0))
        val v = LiveModelRank.blend(1000, 500)!!
        assertTrue(v in 800..900)
    }

    @Test fun encodeDecodeRoundTrip() {
        val m = mapOf("gemini-3.8-live" to 600L, "gemini-2.5-flash-native-audio-preview-12-2025" to 912L)
        assertEquals(m, LiveModelRank.decode(LiveModelRank.encode(m)))
        assertTrue(LiveModelRank.decode("").isEmpty())
        assertTrue(LiveModelRank.decode("garbage;x=;=5").isEmpty())
    }
}
