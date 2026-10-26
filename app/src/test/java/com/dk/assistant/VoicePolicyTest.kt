package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v3.74: फ़ोन की आवाज़ (Android TTS) कभी नहीं - सिर्फ़ Gemini; ऑफ़लाइन चुप */
class VoicePolicyTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun phoneTtsNeverAllowed() { assertFalse(VoicePolicy.PHONE_TTS_ALLOWED) }

    @Test fun offlineMeansSilent() {
        assertEquals(VoicePolicy.Why.OFFLINE, VoicePolicy.gemini(hasKey = true, online = false, now = 10, cooldownUntil = 0))
        assertTrue(VoicePolicy.message(VoicePolicy.Why.OFFLINE).contains("इंटरनेट"))
    }

    @Test fun noKeyAndLimit() {
        assertEquals(VoicePolicy.Why.NO_KEY, VoicePolicy.gemini(false, true, 10, 0))
        assertEquals(VoicePolicy.Why.LIMIT, VoicePolicy.gemini(true, true, 10, 50))
        assertTrue(VoicePolicy.message(VoicePolicy.Why.NO_KEY).contains("नई key"))
    }

    @Test fun onlineWithKeySpeaks() {
        assertEquals(VoicePolicy.Why.OK, VoicePolicy.gemini(true, true, 100, 50))
        assertEquals("", VoicePolicy.message(VoicePolicy.Why.OK))
    }

    @Test fun waitsLongerInsteadOfPhoneFallback() {
        assertTrue(VoicePolicy.waitMs(quick = false) >= 15_000)
        assertTrue(VoicePolicy.waitMs(quick = true) >= 3_000)
    }

    @Test fun speakerHasNoPhoneTts() {
        val s = src("Speaker.kt")
        assertFalse(s.contains("TextToSpeech("))
        assertFalse(s.contains("googleSay"))
    }

    @Test fun liveAndPipelineDoNotUsePhoneVoice() {
        assertFalse(src("GeminiLive.kt").contains("speaker.speakGoogle("))
        assertFalse(src("voice/engine/SpeechPipeline.kt").contains("hooks.phoneSay("))
    }
}
