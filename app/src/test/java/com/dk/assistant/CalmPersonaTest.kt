package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v3.76: मालिक का तय शांत स्वभाव - हर वॉइस मोड + हर TTS में */
class CalmPersonaTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun blockHasOwnerRules() {
        val b = CalmPersona.block()
        assertTrue(b.contains(CalmPersona.OWNER_TEXT))
        assertTrue(b.contains("exactly ONE natural, curious, open question"))
        assertTrue(b.contains("Do NOT talk about tasks"))
        assertTrue(b.contains("medium, unhurried pace"))
        assertTrue(b.contains("hidden behind the words"))
    }

    @Test fun ttsStyleAddsPaceOnce() {
        val s = CalmPersona.ttsStyle("warm Hindi")
        assertTrue(s.startsWith("warm Hindi; "))
        assertEquals(s, CalmPersona.ttsStyle(s))
        assertEquals(CalmPersona.VOICE_STYLE, CalmPersona.ttsStyle(""))
        assertTrue(GeminiTts.styleText("cheerful").contains("pauses"))
        assertTrue(GeminiTts.styleText("natural").contains("medium unhurried pace"))
    }

    @Test fun edgeRateSlowerButInRange() {
        assertEquals(-8, CalmPersona.edgeRate(0))
        assertEquals(-50, CalmPersona.edgeRate(-50))
        assertEquals(42, CalmPersona.edgeRate(50))
    }

    @Test fun appliedInAllVoicePromptsAndEngines() {
        assertEquals(3, Regex("append\\(CalmPersona.block\\(\\)\\)").findAll(src("BrainManager.kt")).count())
        assertTrue(src("voice/engine/GeminiTtsEngine.kt").contains("CalmPersona.ttsStyle("))
        assertTrue(src("voice/engine/EdgeTtsEngine.kt").contains("CalmPersona.edgeRate("))
        assertTrue(src("voice/engine/LiveNativeEngine.kt").contains("मध्यम गति"))
    }
}
