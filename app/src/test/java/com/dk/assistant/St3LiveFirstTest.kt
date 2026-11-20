package com.dk.assistant

import com.dk.assistant.voice.st3.St3Load
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक का 15:52 लॉग): ऑफ़लाइन आवाज़ हल्के तरीके में भी धीमी = पहले Gemini TTS पर जाते थे, वह और धीमा
 * (चुप्पी 1.4-4.3 s, टुकड़े 15-36 s में बने). अब बचा जवाब पहले Live की अपनी आवाज़ में; वह न हो तो पुराना रास्ता.
 */
class St3LiveFirstTest {
    @Before fun clean() { St3Load.reset() }
    @After fun after() { St3Load.reset() }

    private fun src(p: String): String {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        return java.io.File(root, p).readText()
    }

    @Test fun liveVoiceBeforeGeminiTts() {
        val pipe = src("com/dk/assistant/voice/engine/SpeechPipeline.kt")
        val play = pipe.substring(pipe.indexOf("private fun play(s: Seg)"))
        val first = play.indexOf("St3Load.toGemini && liveFirst(s)) return")
        assertTrue(first > 0)
        assertTrue(first < play.indexOf("if (s.phone || waitPhone(s))"))      // बनने का इंतज़ार करने से पहले
        assertTrue(pipe.contains("hooks.takeOver(s.at, LIVE_FIRST_WHY)"))
        // Live की आवाज़ न मिले तो पुराना बैकअप रास्ता वैसा ही (Gemini TTS, फिर ऑफ़लाइन)
        assertTrue(pipe.contains("all.sortedBy { if (it.id == VoiceEngines.GEMINI_TTS) 0 else 1 }"))
        assertTrue(src("com/dk/assistant/GeminiLive.kt").contains("override fun takeOver(at: Int, why: String): Boolean = rescueWithLive(at, why)"))
    }
}
