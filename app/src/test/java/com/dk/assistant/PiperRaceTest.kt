package com.dk.assistant

import com.dk.assistant.voice.engine.PiperTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "Piper इस्तेमाल नहीं हो पाता, देर करके सीधे Gemini पर") - Piper की दौड़ अलग, असली नाप */
class PiperRaceTest {
    @Before fun clean() = PiperTiming.reset()
    @After fun clean2() = PiperTiming.reset()

    private fun dir(): File = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }

    @Test fun piperGetsMoreTimeThanGeminiTts() {
        assertTrue(PiperTiming.firstBudget(-1) > SplitRescue.FIRST_BUDGET_MS)
        assertEquals(PiperTiming.FIRST_BUDGET_MS, PiperTiming.firstBudget(-1))
        // Gemini TTS की 900 ms वाली दौड़ में Piper हारता था - अब 1.5 s पर भी नहीं
        assertTrue(SplitRescue.firstTooSlow(now = 2_500, liveSince = 1_000, ttsStarted = false, open = true))
        assertFalse(PiperTiming.firstTooSlow(now = 2_500, liveSince = 1_000, started = false, open = true, lastFirstMs = -1))
        assertTrue(PiperTiming.firstTooSlow(now = 4_000, liveSince = 1_000, started = false, open = true, lastFirstMs = -1))
        assertFalse(PiperTiming.firstTooSlow(now = 9_000, liveSince = 1_000, started = true, open = true, lastFirstMs = -1))
        assertFalse(PiperTiming.firstTooSlow(now = 9_000, liveSince = 1_000, started = false, open = false, lastFirstMs = -1))
        assertFalse(PiperTiming.firstTooSlow(now = 9_000, liveSince = 0, started = false, open = true, lastFirstMs = -1))
    }

    @Test fun budgetFollowsMeasuredSpeedWithinLimits() {
        assertEquals(PiperTiming.MIN_BUDGET_MS, PiperTiming.firstBudget(200))
        assertEquals(1_500 + PiperTiming.SLACK_MS, PiperTiming.firstBudget(1_500))
        assertEquals(PiperTiming.MAX_BUDGET_MS, PiperTiming.firstBudget(10_000))
        assertTrue(PiperTiming.stuck(PiperTiming.FIRST_STUCK_MS, true))
        assertFalse(PiperTiming.stuck(SplitRescue.FIRST_STUCK_MS, true))     // Gemini TTS की 1.6 s सीमा पर Piper नहीं रुकता
        assertFalse(PiperTiming.stuck(SplitRescue.NEXT_STUCK_MS, false))
    }

    @Test fun measuresEverySentence() {
        assertTrue(PiperTiming.line().contains("अभी कोई वाक्य नहीं"))
        assertEquals(-1, PiperTiming.typicalFirstMs())
        PiperTiming.spoke(firstMs = 2_400, totalMs = 2_500, audioMs = 2_000, chars = 30, loadMs = 1_800)   // मॉडल लोड वाला - औसत में नहीं
        assertEquals(-1, PiperTiming.typicalFirstMs())
        PiperTiming.spoke(800, 900, 2_000, 30); PiperTiming.spoke(1_200, 1_300, 2_000, 30)
        assertEquals(1_000, PiperTiming.typicalFirstMs())
        PiperTiming.cut(950)
        val l = PiperTiming.line()
        assertTrue(l, l.contains("पहली आवाज़ 1.20 s") && l.contains("मॉडल लोड 1.80 s") && l.contains("बोले 3 वाक्य"))
        assertTrue(l, l.contains("Live ने बीच में लिया 1 बार") && l.contains("0.95 s"))
        val w = PiperTiming.rescueWhy(2_300, false)
        assertTrue(w, w.startsWith("Piper 2.30 s में नहीं बोला") && w.contains("सीमा 2.20 s"))
        assertFalse(w.contains("Gemini TTS"))
        assertEquals("Piper ने आवाज़ नहीं बनाई", PiperTiming.rescueWhy(0, true))
    }

    @Test fun wired() {
        val r = File(dir(), "GeminiLiveRescue.kt").readText()
        assertTrue(r.contains("if (splitEngine?.id == com.dk.assistant.voice.engine.PiperTtsEngine.ID)"))
        assertTrue(r.contains("P.firstTooSlow(now, since, started, open, P.typicalFirstMs())"))
        assertTrue(r.contains("splitLiveWhy = why"))
        val p = File(dir(), "voice/engine/SpeechPipeline.kt").readText()
        assertTrue(p.contains("if (engine?.id == PiperTtsEngine.ID) PiperTiming.stuck(waited, first)"))
        assertTrue(p.contains("PiperTiming.rescueWhy(waited, why == SplitRescueWhy.FAILED)"))
        val e = File(dir(), "voice/engine/PiperTtsEngine.kt").readText()
        assertTrue(e.contains("if (!previewing) note(g, t0, firstAt, nSamp, say.length, if (wasLoaded) -1L else lastLoadMs)"))
        assertTrue(e.contains("PiperTiming.cut(now - t0)"))
        assertTrue(File(dir(), "SettingsPiper.kt").readText().contains("PiperTiming.line()"))
    }
}
