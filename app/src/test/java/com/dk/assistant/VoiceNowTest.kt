package com.dk.assistant

import com.dk.assistant.voice.engine.VoiceNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): स्क्रीन पर दिखे कि अभी आवाज़ किस TTS / किस आवाज़ से आ रही है */
class VoiceNowTest {
    @Test fun line_format() {
        assertEquals("आवाज़: Piper (हिंदी, ऑफ़लाइन) • Priyamvada HQ", VoiceNow.line("Piper (हिंदी, ऑफ़लाइन)", "Priyamvada HQ"))
        assertEquals("आवाज़: Gemini Live (सीधी आवाज़) • Kore — Piper तैयार नहीं", VoiceNow.line(VoiceNow.LIVE_TITLE, "Kore", "Piper तैयार नहीं"))
        assertEquals("आवाज़: —", VoiceNow.line(""))
    }

    @Test fun played_updatesOnlyOnChange_andExpires() {
        VoiceNow.reset()
        var n = 0
        VoiceNow.onChange = { n++ }
        VoiceNow.played("piper_hi", "Piper", "A", now = 1_000)
        VoiceNow.played("piper_hi", "Piper", "A", now = 1_040)
        assertEquals(1, n)
        assertEquals("piper_hi", VoiceNow.engineId)
        VoiceNow.played("live_native", VoiceNow.LIVE_TITLE, "Kore", "Piper अभी नहीं चला", now = 2_000)
        assertEquals(2, n)
        assertTrue(VoiceNow.shown(3_000).contains("Piper अभी नहीं चला"))
        assertEquals("", VoiceNow.shown(2_000 + 11 * 60_000L))
        VoiceNow.onChange = {}; VoiceNow.reset()
    }

    @Test fun wiring() {
        fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("VoiceNow.played(com.dk.assistant.voice.engine.VoiceEngines.LIVE_NATIVE"))
        assertTrue(live.contains("Piper चालू है, पर उसकी आवाज़ अभी डाउनलोड नहीं हुई"))
        assertTrue(src("voice/engine/SpeechPipeline.kt").contains("showVoice(s)"))
        assertTrue(src("VoiceActivity.kt").contains("VoiceNow.shown()"))
        assertTrue(src("Speaker.kt").contains("Gemini TTS (बैकअप मोड)"))
    }
}
