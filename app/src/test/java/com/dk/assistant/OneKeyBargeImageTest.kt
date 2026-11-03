package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.152 (मालिक): "एक key से सब" मोड में - बीच में बोलूँ तो DK नहीं रुकता, चुप होने के बाद देर से जवाब।
 * + image_task (ChatGPT / Gemini में इमेज) की wiring।
 */
class OneKeyBargeImageTest {
    @Test fun silentCaption_userAboveEcho_confirmsBarge() {
        val g = BargeGate(); g.suspect(1_000)
        assertFalse(g.expired(1_500, captionOn = true, loudMs = 500, overEchoMs = 500))
        assertFalse(g.expired(2_050, captionOn = true, loudMs = 1_050, overEchoMs = 1_050))
        assertEquals(BargeGate.State.CONFIRMED, g.state)   // पहले: 1.5s पर "शोर" - DK बोलता रहता
    }

    @Test fun captionAlive_echoOnly_staysFalseAlarm() {
        val g = BargeGate(); g.suspect(1_000)
        assertFalse(g.onText("आ", "आज मौसम साफ़ है"))
        assertTrue(g.capSeen)
        assertFalse(g.expired(2_100, captionOn = true, loudMs = 0, overEchoMs = 1_100))
        assertTrue(g.expired(2_600, captionOn = true, loudMs = 0, overEchoMs = 0))
        assertEquals(BargeGate.State.IDLE, g.state)
    }

    @Test fun quietNoise_notConfirmed() {
        val g = BargeGate(); g.suspect(0)
        assertFalse(g.expired(1_000, captionOn = true, loudMs = 900, overEchoMs = 300))
        assertTrue(g.expired(1_600, captionOn = true, loudMs = 0, overEchoMs = 0))
    }

    @Test fun slowCaption_learned_thenLoudnessConfirms() {
        val g = BargeGate()
        repeat(2) { i ->
            val t = 10_000L * (i + 1)
            g.suspect(t); g.sawCaption("रु", t + 1_300); g.reset()
        }
        assertTrue(g.captionSlow)
        g.suspect(50_000)
        assertFalse(g.expired(50_750, captionOn = true, loudMs = 750))
        assertEquals(BargeGate.State.CONFIRMED, g.state)
    }

    @Test fun fastCaption_notSlow() {
        val g = BargeGate()
        repeat(3) { i -> val t = 10_000L * (i + 1); g.suspect(t); g.sawCaption("रुको", t + 300); g.reset() }
        assertFalse(g.captionSlow)
    }

    @Test fun staleCaption_doesNotHoldTurnEnd() {
        assertTrue(TurnEnd.captionFresh(textAt = 10_000, lastVoiceAt = 10_200))
        assertFalse(TurnEnd.captionFresh(textAt = 9_000, lastVoiceAt = 10_200))
        assertFalse(TurnEnd.captionFresh(textAt = 0, lastVoiceAt = 10_200))
        assertTrue(TurnEnd.captionFresh(textAt = 5_000, lastVoiceAt = 0))
    }

    private fun src(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main/java/com/dk/assistant/$name"), File(dir, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("$name नहीं मिला")
    }

    @Test fun wiring() {
        assertTrue(src("GeminiLiveSetup.kt").contains("\"image_task\""))
        assertTrue(src("GeminiLiveTools.kt").contains("ImageGenAgent.run(svc, args)"))
        assertTrue(src("LiveConnect.kt").contains("image_task"))
        assertTrue(LeakedCall.TOOLS.contains("image_task"))
        assertTrue(src("GeminiLiveTurnEnd.kt").contains("TurnEnd.captionFresh("))
        assertTrue(src("GeminiLiveMic.kt").contains("overEchoMs"))
        assertTrue(src("GeminiLiveCaption.kt").contains("bargeGate.sawCaption("))
    }
}
