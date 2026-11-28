package com.dk.assistant

import com.dk.assistant.voice.engine.VoiceEngines
import com.dk.assistant.voice.st3.St3Load
import com.dk.assistant.voice.st3.St3Stats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 28 नवंबर 2026 (मालिक का लॉग 18:48-18:50): "थ्रेड घटाए (2), अगले जवाब से" के बाद भी हर टुकड़ा 4 थ्रेड.
 * वजह: Gemini TTS चुना था, limit पर ऑफ़लाइन आवाज़ पीछे से बोली - नया जवाब सिर्फ़ ऑफ़लाइन आवाज़ चुने होने पर गिना जाता,
 * टुकड़े जमा रहते, [St3Load.freshAnswer] कभी true नहीं और मॉडल कम थ्रेड से दोबारा नहीं खुलता.
 */
class St3ThreadCutNov28Test {
    @Before fun clean() { St3Load.reset() }
    @After fun after() { St3Load.reset() }

    private fun row(rtfPct: Int) = St3Stats.Row(30, 2_000L * rtfPct / 100, 2_000, 5, 4)

    @Test fun fallbackEngineAlsoStartsNewAnswer() {
        val st3 = VoiceEngines.ST3_OFFLINE
        assertTrue(St3Load.newAnswerFor(st3, emptyList()))
        assertTrue("Gemini TTS चुना, ऑफ़लाइन पीछे", St3Load.newAnswerFor(VoiceEngines.GEMINI_TTS, listOf(st3)))
        assertFalse(St3Load.newAnswerFor(VoiceEngines.GEMINI_TTS, emptyList()))
    }

    @Test fun cutThreadsApplyOnNextAnswerWhenOfflineIsFallback() {
        St3Load.onRow(row(108)); St3Load.onRow(row(108))
        assertTrue(St3Load.sessionThreadsCut)
        assertFalse(St3Load.freshAnswer)
        // अगला जवाब Gemini TTS से शुरू (ऑफ़लाइन पीछे) - फिर भी नया जवाब गिना जाए
        if (St3Load.newAnswerFor(VoiceEngines.GEMINI_TTS, VoiceEngines.FALLBACK_ORDER.filter { it != VoiceEngines.GEMINI_TTS })) St3Load.newAnswer(500)
        assertTrue("मॉडल अब कम थ्रेड से खुल सकता है", St3Load.freshAnswer)
        assertEquals(St3Load.HOT_THREADS, St3Load.threadsFor(4, 0, St3Load.sessionThreadsCut))
    }

    @Test fun wiredInPipelineAndSpeaker() {
        val dir = java.io.File("src/main/java/com/dk/assistant").let { if (it.exists()) it else java.io.File("app/src/main/java/com/dk/assistant") }
        val pipe = java.io.File(dir, "voice/engine/SpeechPipeline.kt").readText()
        assertTrue(pipe.contains("St3Load.newAnswerFor(e.id, VoiceEngines.fallbacks(e.id)"))
        val speaker = java.io.File(dir, "Speaker.kt").readText()
        assertTrue(speaker.contains("St3Load.newAnswer(com.dk.assistant.voice.st3.St3Stats.lastFirstMs)"))
        val eng = java.io.File(dir, "voice/st3/St3Engine.kt").readText()
        assertTrue(eng.contains("openThreads != want && St3Load.freshAnswer"))
    }
}
