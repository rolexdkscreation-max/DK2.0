package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक: "Gemini की आवाज़ अच्छी है, पर एकदम स्लो सुनाई देती है"): जड़ - Gemini TTS / Live की आवाज़ के
 * निर्देश में पाँच जगह "धीमे / ठहराव" (धीरे-धीरे, unhurried + pauses, slightly slower, small pauses, a little slower).
 * अब रफ़्तार आम बातचीत जैसी, कोमलता वही.
 */
class VoicePaceTest {
    @Test fun noExtraSlowWords() {
        val tts = com.dk.assistant.voice.engine.GeminiTtsEngine.DEFAULT_STYLE
        assertFalse(tts.contains("धीरे-धीरे"))
        assertTrue(tts.contains("कोमल") && tts.contains("धीमी या खिंची हुई नहीं"))
        assertTrue(CalmPersona.VOICE_STYLE.contains("never dragging"))
        assertFalse(CalmPersona.VOICE_STYLE.contains("pauses between sentences"))
        assertTrue(SpeakingStyle.TTS_STYLE.startsWith("normal talking pace, never dragging"))
        val caring = DkPersona.ttsFor(DkPersona.P.CARING, "")
        assertFalse(caring.contains("slower") || caring.contains("small pauses"))
        assertTrue(caring.contains("normal talking speed"))
    }

    @Test fun fullStyleStillFits() {
        val s = GeminiTts.styleText("natural")
        assertTrue(s.length <= SpeakingStyle.TTS_MAX)
        assertTrue(s.contains("warm, natural, friendly conversational Hindi"))     // आधार कटता नहीं
        assertTrue(s.contains("never dragging"))
    }
}
