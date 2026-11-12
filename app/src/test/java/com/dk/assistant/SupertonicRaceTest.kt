package com.dk.assistant

import com.dk.assistant.voice.engine.SupertonicTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "फ़ोन वाला TTS इस्तेमाल नहीं हो पाता, देर करके सीधे Gemini पर"; नया Supertonic 3) - Supertonic की दौड़ अलग, असली नाप */
class SupertonicRaceTest {
    @Before fun clean() = SupertonicTiming.reset()
    @After fun clean2() = SupertonicTiming.reset()

    private fun dir(): File = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }

    @Test fun supertonicGetsMoreTimeThanGeminiTts() {
        assertTrue(SupertonicTiming.firstBudget(-1) > SplitRescue.FIRST_BUDGET_MS)
        assertEquals(SupertonicTiming.FIRST_BUDGET_MS, SupertonicTiming.firstBudget(-1))
        // Gemini TTS की 900 ms वाली दौड़ में फ़ोन वाली आवाज़ हारती थी - अब 1.5 s पर भी नहीं
        assertTrue(SplitRescue.firstTooSlow(now = 2_500, liveSince = 1_000, ttsStarted = false, open = true))
        assertFalse(SupertonicTiming.firstTooSlow(now = 2_500, liveSince = 1_000, started = false, open = true, lastFirstMs = -1))
        assertTrue(SupertonicTiming.firstTooSlow(now = 4_000, liveSince = 1_000, started = false, open = true, lastFirstMs = -1))
        assertFalse(SupertonicTiming.firstTooSlow(now = 9_000, liveSince = 1_000, started = true, open = true, lastFirstMs = -1))
        assertFalse(SupertonicTiming.firstTooSlow(now = 9_000, liveSince = 1_000, started = false, open = false, lastFirstMs = -1))
        assertFalse(SupertonicTiming.firstTooSlow(now = 9_000, liveSince = 0, started = false, open = true, lastFirstMs = -1))
    }

    @Test fun budgetFollowsMeasuredSpeedWithinLimits() {
        assertEquals(SupertonicTiming.MIN_BUDGET_MS, SupertonicTiming.firstBudget(200))
        assertEquals(1_500 + SupertonicTiming.SLACK_MS, SupertonicTiming.firstBudget(1_500))
        assertEquals(SupertonicTiming.MAX_BUDGET_MS, SupertonicTiming.firstBudget(10_000))
        assertTrue(SupertonicTiming.stuck(SupertonicTiming.FIRST_STUCK_MS, true))
        assertFalse(SupertonicTiming.stuck(SplitRescue.FIRST_STUCK_MS, true))     // Gemini TTS की 1.6 s सीमा पर Supertonic नहीं रुकता
        assertFalse(SupertonicTiming.stuck(SplitRescue.NEXT_STUCK_MS, false))
    }

    @Test fun measuresEverySentence() {
        assertTrue(SupertonicTiming.line().contains("अभी कोई वाक्य नहीं"))
        assertEquals(-1, SupertonicTiming.typicalFirstMs())
        SupertonicTiming.spoke(firstMs = 2_400, totalMs = 2_500, audioMs = 2_000, chars = 30, loadMs = 1_800)   // मॉडल लोड वाला - औसत में नहीं
        assertEquals(-1, SupertonicTiming.typicalFirstMs())
        SupertonicTiming.spoke(800, 900, 2_000, 30); SupertonicTiming.spoke(1_200, 1_300, 2_000, 30)
        assertEquals(1_000, SupertonicTiming.typicalFirstMs())
        SupertonicTiming.cut(950)
        val l = SupertonicTiming.line()
        assertTrue(l, l.contains("पहली आवाज़ 1.20 s") && l.contains("मॉडल लोड 1.80 s") && l.contains("बोले 3 वाक्य"))
        assertTrue(l, l.contains("Live ने बीच में लिया 1 बार") && l.contains("0.95 s"))
        val w = SupertonicTiming.rescueWhy(2_300, false)
        assertTrue(w, w.startsWith("Supertonic 2.30 s में नहीं बोला") && w.contains("सीमा 2.20 s"))
        assertFalse(w.contains("Gemini TTS"))
        assertEquals("Supertonic ने आवाज़ नहीं बनाई", SupertonicTiming.rescueWhy(0, true))
    }

    @Test fun wired() {
        val r = File(dir(), "GeminiLiveRescue.kt").readText()
        assertTrue(r.contains("if (splitEngine?.id == com.dk.assistant.voice.engine.SupertonicTtsEngine.ID)"))
        assertTrue(r.contains("P.firstTooSlow(now, since, started, open, P.typicalFirstMs())"))
        assertTrue(r.contains("splitLiveWhy = why"))
        val p = File(dir(), "voice/engine/SpeechPipeline.kt").readText()
        assertTrue(p.contains("if (engine?.id == SupertonicTtsEngine.ID) SupertonicTiming.stuck(waited, first)"))
        assertTrue(p.contains("SupertonicTiming.rescueWhy(waited, why == SplitRescueWhy.FAILED)"))
        val e = File(dir(), "voice/engine/SupertonicTtsEngine.kt").readText()
        assertTrue(e.contains("if (!previewing) note(g, t0, firstAt, nSamp, say.length, if (wasLoaded) -1L else lastLoadMs)"))
        assertTrue(e.contains("SupertonicTiming.cut(now - t0)"))
        assertTrue(File(dir(), "SettingsSupertonic.kt").readText().contains("SupertonicTiming.line()"))
    }
}

/** नवंबर 2026 (मालिक का फ़ोन: HQ 75 अक्षर 33.87 s में, आवाज़ 8.52 s = बोलने से 4 गुना धीमा) */
class SupertonicSpeedTest {
    @Before fun clean() = SupertonicTiming.reset()
    @After fun clean2() = SupertonicTiming.reset()
    private fun dir(): File = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private val S = com.dk.assistant.voice.engine.SupertonicSpeed

    @Test fun stepsAdaptToPhoneSpeed() {
        assertEquals(4, S.START_STEPS)
        assertEquals(2, S.nextSteps(4, genMs = 33_870, audioMs = 8_520))      // मालिक का फ़ोन (~4 गुना धीमा) = दो चरण कम
        assertEquals(3, S.nextSteps(4, 6_000, 4_000))                          // थोड़ा धीमा = एक चरण कम
        assertEquals(2, S.nextSteps(8, 0, 0).let { S.nextSteps(4, 9_000, 4_000) })
        assertEquals(2, S.nextSteps(3, 20_000, 8_000))
        assertEquals(2, S.nextSteps(2, 20_000, 8_000))                         // सबसे कम पर रुकता है
        assertEquals(5, S.nextSteps(4, 500, 4_000))                            // बहुत तेज़ = बेहतर आवाज़
        assertEquals(8, S.nextSteps(5, 500, 4_000))
        assertEquals(8, S.nextSteps(8, 500, 4_000))
        assertEquals(4, S.nextSteps(4, 2_000, 4_000))                          // ठीक-ठाक = वही
        assertEquals(4, S.nextSteps(4, 5_000, 800))                            // छोटी आवाज़ पर नाप भरोसे का नहीं
        assertEquals(5, S.norm(6)); assertEquals(8, S.norm(32)); assertEquals(2, S.norm(1))
        assertTrue(S.tooSlow(2, 1.4)); assertFalse(S.tooSlow(4, 1.4)); assertFalse(S.tooSlow(2, 0.7))
    }

    @Test fun threadsOnlyOnBigCores() {
        // 2 बड़े + 6 छोटे (cpu_capacity): पहले HQ में 4 धागे (2 छोटे कोर मिलकर हर चरण धीमा) - अब 2
        assertEquals(2, S.threads(listOf(400L, 400, 400, 400, 400, 400, 1024, 1024), 8))
        // 1 + 3 + 4: बड़े 4
        assertEquals(4, S.threads(listOf(250L, 250, 250, 250, 800, 800, 800, 1024), 8))
        // पता न चले = पुराना हिसाब
        assertEquals(com.dk.assistant.voice.engine.SupertonicTtsEngine.threadsFor(8), S.threads(emptyList(), 8))
    }

    @Test fun speedShownWithAdvice() {
        assertEquals("", SupertonicTiming.speedLine())
        SupertonicTiming.speed(2, 2, 1.6)
        val l = SupertonicTiming.line()
        assertTrue(l, l.contains("1 s आवाज़ 1.60 s में बनी") && l.contains(" 2 चरण") && !l.contains("HQ") && l.contains("2 धागे") && l.contains(S.ADVICE))
        SupertonicTiming.speed(4, 4, 0.4)
        assertTrue(SupertonicTiming.speedLine().contains("बोलने से तेज़") && !SupertonicTiming.speedLine().contains(S.ADVICE))
    }

    @Test fun wired() {
        val e = File(dir(), "voice/engine/SupertonicTtsEngine.kt").readText()
        assertTrue(e.contains("genConfig(sid, SupertonicAudio.genSpeed(speed, pitch), steps)"))
        assertTrue(e.contains("val steps = SupertonicSpeed.steps(c)"))
        assertTrue(e.contains("adapt(c, steps, firstAt - t0"))
        assertTrue(e.contains("SupertonicSpeed.threadsHere()"))
        assertTrue(e.contains("THREAD_PRIORITY_AUDIO"))
        assertEquals(3, com.dk.assistant.voice.engine.SupertonicTtsEngine.genConfig(0, 1f, 3).numSteps)
    }
}
