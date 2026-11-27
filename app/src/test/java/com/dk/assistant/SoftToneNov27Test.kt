package com.dk.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dk.assistant.voice.st3.St3Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 27 नवंबर 2026 (मालिक का "कोमल, प्यार भरी लड़की" वाला प्रॉम्प्ट): सिर्फ़ जो पहले नहीं था, वह Live (पूरे + छोटे),
 * Gemini TTS और ऑफ़लाइन आवाज़ (बिना-Live दिमाग + Supertonic) में.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoftToneNov27Test {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun onlyMissingPartsAdded() {
        val s = DkAiStyle.SOFT
        for (k in listOf("सबसे अपना इंसान", "\"सुनो ना...\"", "\"सुनिए ना...\"", "never on every line", "stars (*)", "emojis", "spoken aloud, not written"))
            assertTrue("नहीं मिला: $k", s.contains(k))
        assertTrue(DkAiStyle.BLOCK.endsWith(DkAiStyle.SOFT))
        assertTrue(DkAiStyle.SAY_RULE.contains("stars (*)"))
        // पहले से मौजूद बातें दोबारा नहीं जोड़ीं
        assertTrue(SpeakingStyle.OWNER_STYLE.contains("उदास हो तो धीमी और दिलासा देने वाली"))
        assertTrue(SpeakingStyle.OWNER_STYLE.contains("\"हम्म...\", \"अच्छा...\""))
        assertFalse(s.contains("हम्म...\", \"अच्छा...\""))
    }

    @Test fun inLiveFullAndLite() {
        assertTrue(BrainManager(c).liveSystemPrompt(withMemory = false).contains(DkAiStyle.SOFT))
        Prefs.setLivePromptLite(c, true)
        val lite = BrainManager(c).liveSystemPrompt(withMemory = false)
        assertTrue(lite.contains(DkAiStyle.SOFT))
        assertTrue("Live निर्देश ${lite.length}", lite.length + 120 < 29_000)
        Prefs.setLivePromptLite(c, false)
        // JARVIS में नहीं
        Prefs.edit(c) { putBoolean(JarvisMode.PREF, true) }
        assertEquals("", DkAiStyle.block(c))
        Prefs.edit(c) { putBoolean(JarvisMode.PREF, false) }
    }

    @Test fun geminiTtsChirpAndSupport() {
        assertTrue(SpeakingStyle.TTS_STYLE.contains("light chirp when happy"))
        assertTrue(SpeakingStyle.TTS_STYLE.contains("slow, soft, supportive when sad"))
        val full = SpeakingStyle.tts(CalmPersona.ttsStyle(DkPersona.ttsStyle(c, "")))
        assertTrue(full.length <= SpeakingStyle.TTS_MAX)
        assertTrue(full.contains(DkAiStyle.TTS) && full.contains(SpeakingStyle.TTS_STYLE) && full.contains(CalmPersona.VOICE_STYLE))
    }

    @Test fun offlineVoiceNeverReadsStars() {
        val t = St3Text.preprocess("**सुनो ना**... • अच्छा")
        assertFalse(t.contains("*") || t.contains("•"))
        assertTrue(t.contains("सुनो ना"))
    }
}
