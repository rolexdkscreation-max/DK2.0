package com.dk.assistant

import com.dk.assistant.voice.engine.VoiceTune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 26 नवंबर 2026 (मालिक का लिखा DK Live प्रॉम्प्ट + temperature 0.8-1.0) */
class OwnerVoiceCallNov26Test {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun temperatureInOwnerRange() {
        assertEquals(90, VoiceTune.DEFAULT_TEMP)
        assertTrue(VoiceTune.DEFAULT_TEMP / 100.0 in 0.8..1.0)
        assertTrue(VoiceTune.fmtTemp(90).contains("सही"))
        assertTrue(src("voice/engine/VoiceTune.kt").contains("tune_temp_090_v1"))   // पुराना 0.75 एक बार नए पर
    }

    @Test fun fullPromptHasOwnerRules() {
        val p = OwnerVoiceCall.prompt("Dheeraj")
        for (w in listOf("Dheeraj", "close friend late at night", "never fast", "never rattling", "slow, soft and gentle", "धीरे-धीरे", "never so slow that a single word drags", "हम्म...", "उम्म...", "अच्छा...", "chuckle",
            "customer care", "news anchor", "Hinglish", "सहायता", "अवश्य", "कृपया", "प्रदान", "1-3 short sentences", "tired = extra gentle",
            "tease him lightly", "emojis"))
            assertTrue("नहीं मिला: $w", p.contains(w))
        assertFalse(OwnerVoiceCall.prompt("").contains("Dheeraj"))
        assertEquals(3, src("BrainManager.kt").split("OwnerVoiceCall.prompt(Prefs.userName(ctx))").size - 1)
    }

    @Test fun ttsPaceCalmNotFast() {
        val t = SpeakingStyle.TTS_STYLE
        assertTrue(t.contains("never fast") && t.contains("slow and gentle on loving lines") && t.contains("never dragging"))
        assertTrue(GeminiTts.styleText("natural").length <= SpeakingStyle.TTS_MAX)
    }

    @Test fun liteInEveryLivePrompt() {
        val l = OwnerVoiceCall.LITE
        for (w in listOf("close friend late at night", "never fast", "loving words slow and soft", "never dragging", "सहायता/अवश्य/कृपया/प्रदान", "customer care", "news anchor")) assertTrue(w, l.contains(w))
        assertTrue(SpeakingStyle.block("धीरज").contains(l))
        assertTrue("LITE ${l.length}", l.length < 320)
    }
}
