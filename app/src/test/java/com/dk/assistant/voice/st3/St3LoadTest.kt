package com.dk.assistant.voice.st3

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक का लॉग: 14:24 के बाद "बनी" आवाज़ का 100-141%, बीच में 1.4-3 s चुप्पी; पहली आवाज़ 8.5 s):
 * लगातार धीमा = हल्का तरीका, फिर भी धीमा = Gemini की आवाज़; फ़ोन की गर्मी लॉग में; बोलते समय भारी काम टलें;
 * पहला टुकड़ा 1.5 s तैयार आवाज़ के बाद; ऐप खुलते + देर से खाली होने पर मॉडल फिर गरम.
 */
class St3LoadTest {
    @Before fun clean() { St3Load.reset() }
    @After fun after() { St3Load.reset() }

    private fun slow() = St3Stats.Row(40, 3_000, 2_400, 5, 4)      // 125%
    private fun fast() = St3Stats.Row(40, 1_200, 2_400, 5, 4)      // 50%

    @Test fun twoSlowPiecesGoLightThenGemini() {
        assertNull(St3Load.onRow(slow()))
        assertEquals(St3Load.Mode.NORMAL, St3Load.mode)
        val l = St3Load.onRow(slow())
        assertNotNull(l)
        assertTrue(l!!.contains("हल्का"))
        assertEquals(St3Load.Mode.LIGHT, St3Load.mode)
        assertEquals(St3Load.LIGHT_STEPS, St3Load.stepsFor(St3Load.mode, 8))
        assertEquals(3, St3Load.stepsFor(St3Load.mode, 3))
        St3Load.onRow(slow())
        St3Load.onRow(slow())
        assertTrue(St3Load.toGemini)
        // नया जवाब: फिर से हल्के से आज़माओ + पिछले का सार
        val sum = St3Load.newAnswer(900)
        assertNotNull(sum)
        assertTrue(sum!!.contains("पहली आवाज़ 900 ms") && sum.contains("बनी/आवाज़ औसत 125%"))
        assertEquals(St3Load.Mode.LIGHT, St3Load.mode)
    }

    @Test fun oneSlowPieceAloneDoesNothingAndFastRecovers() {
        St3Load.onRow(slow()); St3Load.onRow(fast()); St3Load.onRow(slow())
        assertEquals(St3Load.Mode.NORMAL, St3Load.mode)
        St3Load.onRow(slow())
        assertEquals(St3Load.Mode.LIGHT, St3Load.mode)
        repeat(St3Load.GOOD_RUN) { St3Load.onRow(fast()) }
        assertEquals(St3Load.Mode.NORMAL, St3Load.mode)
        assertEquals(8, St3Load.stepsFor(St3Load.mode, 8))
    }

    @Test fun tinyPiecesAreNotTrusted() {
        repeat(5) { St3Load.onRow(St3Stats.Row(3, 600, 200, 5, 4)) }
        assertEquals(St3Load.Mode.NORMAL, St3Load.mode)
    }

    @Test fun hotPhoneGoesLightAndIsLogged() {
        assertEquals(St3Load.Mode.LIGHT, St3Load.next(St3Load.Mode.NORMAL, 0, 0, St3Load.HOT_STATUS))
        assertEquals(St3Load.Mode.LIGHT, St3Load.next(St3Load.Mode.LIGHT, 0, 9, St3Load.HOT_STATUS))
        val l = St3Load.onHeat(2, 0.9f)
        assertTrue(l!!.contains("गर्म") && l.contains("thermal 2") && l.contains("headroom 0.90"))
        assertNull(St3Load.onHeat(2, 0.9f))            // न बदला = दोबारा लॉग नहीं
        assertEquals("पता नहीं", St3Load.heatName(-1))
        assertEquals("सामान्य", St3Load.heatName(0))
    }

    @Test fun lightModeSplitsSmaller() {
        assertEquals(3000L, St3Load.leadFor(St3Load.Mode.NORMAL, 3000))
        assertTrue(St3Load.leadFor(St3Load.Mode.LIGHT, 3000) < 3000L)
    }

    @Test fun heavyWorkWaitsWhileSpeakingButNotForever() {
        val now = 1_000_000L
        assertFalse(St3Load.shouldDefer(now, 0, 30_000))
        St3Load.mark(now + 2_000)
        assertTrue(St3Load.shouldDefer(now, 0, 30_000))
        assertFalse(St3Load.shouldDefer(now, 30_000, 30_000))
        assertFalse(St3Load.shouldDefer(now + 2_500, 0, 30_000))
    }

    private fun src(p: String): String {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        return java.io.File(root, p).readText()
    }

    @Test fun wired() {
        val pipe = src("com/dk/assistant/voice/engine/SpeechPipeline.kt")
        assertTrue(pipe.contains("\"dk-st3-synth\""))
        assertTrue(pipe.contains("if (e.id == St3VoiceEngine.ID) st3Synth else synth"))
        assertTrue(pipe.contains("readyMs(s) >= PREROLL_READY_MS"))
        assertTrue(pipe.contains("St3Load.toGemini"))
        assertTrue(com.dk.assistant.voice.engine.SpeechPipeline.PREROLL_READY_MS in 1_500L..2_000L)
        val eng = src("com/dk/assistant/voice/st3/St3Engine.kt")
        assertTrue(eng.contains("St3Load.onRow(row)"))
        assertTrue(eng.contains("currentThermalStatus"))
        assertTrue(eng.contains("(!warmedUp || idle)"))
        assertTrue(src("com/dk/assistant/DKApp.kt").contains("St3VoiceEngine.warmIfChosen(this@DKApp)"))
        // standby connection (बिना रुकावट बदलना) नहीं छेड़ा - मालिक का नियम; LiveSpareTest वही रहे
        assertFalse(src("com/dk/assistant/GeminiLive.kt").contains("St3Load.shouldDefer"))
        assertTrue(src("com/dk/assistant/MemoryDigest.kt").contains("St3Load.MEMORY_DEFER_MS"))
    }
}
