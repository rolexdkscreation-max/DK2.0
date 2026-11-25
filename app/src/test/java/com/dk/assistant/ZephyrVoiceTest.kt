package com.dk.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 25 नवंबर 2026 (मालिक): "Zephyr आवाज़ Gemini Live और Gemini TTS दोनों में दिखे" */
class ZephyrVoiceTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun zephyrInBothPickers() {
        assertTrue(src("voice/engine/GeminiTtsEngine.kt").contains("\"Zephyr\" to \"Zephyr — "))
        assertTrue(Regex("""listOf\([^)]*"Zephyr"""").containsMatchIn(src("voice/engine/LiveNativeEngine.kt")))
        assertTrue("Zephyr" in VoiceCatalog.GEMINI_FEMALE)
        assertTrue("Zephyr" in com.dk.assistant.voice.engine.GeminiTtsDirect.VOICES)
        assertTrue(LiveVoice.pick("Zephyr", "female") == "Zephyr")
    }
}
