package com.dk.assistant

import com.dk.assistant.voice.engine.TextChunker
import com.dk.assistant.voice.st3.St3Load
import com.dk.assistant.voice.st3.St3Stats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 21 नवंबर 2026 (मालिक, DK Live #2 ऑफ़लाइन आवाज़ धीमी + #3 बीच में गलत टोकना) */
class LiveVoiceBargeFixTest {
    @Before fun clean() { St3Load.reset() }
    @After fun after() { St3Load.reset() }

    private fun pieces(text: String): List<String> {
        val c = TextChunker().apply { reset(TextChunker.ST3) }
        val out = ArrayList<String>()
        for (w in text.split(" ")) out += c.feed("$w ")
        c.flush()?.let { out += it }
        return out
    }

    @Test fun st3PiecesAreShort_first15to20_next25to30() {
        val p = pieces("आज का मौसम बहुत अच्छा रहने वाला है और दोपहर में हल्की धूप भी निकलेगी लेकिन शाम को थोड़ी ठंडी हवा चल सकती है इसलिए जैकेट साथ रखना")
        assertTrue(p.toString(), p.size >= 4)
        assertTrue(p.toString(), p.first().length in 12..26)
        p.drop(1).dropLast(1).forEach { assertTrue(p.toString(), it.length <= 40) }
        assertEquals(TextChunker.ST3_FIRST_MAX, 20)
        assertEquals(TextChunker.ST3_NEXT_MAX, 30)
    }

    @Test fun timeAndNumbersNeverSplit() {
        val all = pieces("ठीक है बॉस, मैंने आपके लिए शाम के 6:30 बजे का अलार्म लगा दिया है और सुबह 10 बजे की मीटिंग भी याद दिला दूँगी")
        assertTrue(all.toString(), all.any { it.contains("6:30 बजे") })
        assertTrue(all.toString(), all.any { it.contains("10 बजे") })
        all.forEach { assertFalse(all.toString(), it.trimEnd().endsWith(":")) }
        // streaming: ":" आख़िर में आया तो अगला अक्षर देखे बिना नहीं कटता
        val c = TextChunker().apply { reset(TextChunker.ST3) }
        assertTrue(c.feed("आज शाम के लगभग 6:").isEmpty())
    }

    @Test fun refillDoesNotCutHalfTime() {
        val c = TextChunker().apply { reset(TextChunker.ST3) }
        c.feed("नमस्ते जी, आज हम बात करते हैं "); c.flush()
        c.feed("कल सुबह ठीक 6:")
        val got = c.flushIfAtLeast(3)
        assertEquals("कल सुबह ठीक", got)
    }

    @Test fun oldEnginesKeepTheirChunks() {
        assertEquals(0, TextChunker.STANDARD.firstMaxChars)
        assertEquals(0, TextChunker.FAST_NET.nextMaxChars)
        assertFalse(TextChunker.STANDARD.numberSafe)
    }

    private fun row(rtfPct: Int) = St3Stats.Row(30, 2_000L * rtfPct / 100, 2_000, 5, 4)

    @Test fun slowOver90CutsThreadsThenGeminiForWholeSession() {
        assertEquals(4, St3Load.threadsFor(4, 0, false))
        St3Load.onRow(row(95)); St3Load.onRow(row(95))
        assertTrue(St3Load.sessionThreadsCut)
        assertEquals(St3Load.HOT_THREADS, St3Load.threadsFor(4, 0, St3Load.sessionThreadsCut))
        assertFalse(St3Load.sessionGemini)
        St3Load.newAnswer(500)                        // कम थ्रेड लागू
        St3Load.onRow(row(95)); St3Load.onRow(row(95))
        assertTrue(St3Load.sessionGemini)
        St3Load.newAnswer(500); St3Load.newAnswer(500)
        assertTrue("हर जवाब पर दोबारा नहीं", St3Load.toGemini)
        St3Load.newSession()
        assertFalse(St3Load.toGemini)
        assertFalse(St3Load.sessionThreadsCut)
    }

    @Test fun hotPhoneCutsThreadsAtOnce() {
        assertEquals(true to false, St3Load.sessionStep(0, St3Load.HOT_STATUS, false, false))
        assertEquals(false to false, St3Load.sessionStep(1, 0, false, false))
        assertEquals(true to true, St3Load.sessionStep(2, 0, true, true))
        assertEquals(true to false, St3Load.sessionStep(2, 0, true, false))
    }

    @Test fun shortLoudSoundOnlyDucks_stopNeedsOneSecondAndOwner() {
        val b = SoftBarge()
        assertTrue(b.trigger("speaker", 320, 0))
        assertFalse(b.trigger("speaker", 320, 40))
        var t = 0L; var act = SoftBarge.Act.NONE
        // 200 ms आवाज़ फिर चुप = रोका नहीं, आवाज़ वापस
        while (t < 2_000 && act == SoftBarge.Act.NONE) { t += 40; act = b.feed(false, true, 40, t) }
        assertEquals(SoftBarge.Act.UNDUCK, act)
        // 1s+ लगातार पर मालिक नहीं = नहीं रुका
        b.trigger("speaker", 320, 0); t = 0; act = SoftBarge.Act.NONE
        repeat(30) { t += 40; val a = b.feed(true, false, 40, t); if (a != SoftBarge.Act.NONE) act = a }
        assertTrue(act != SoftBarge.Act.STOP)
        // मालिक + 1s
        b.reset(); b.trigger("कॉल-mode", 200, 0); t = 0; act = SoftBarge.Act.NONE
        while (t < 2_000 && act == SoftBarge.Act.NONE) { t += 40; act = b.feed(true, true, 40, t) }
        assertEquals(SoftBarge.Act.STOP, act)
        assertTrue(t >= SoftBarge.STOP_MS - 200)
        assertEquals(0.0, SoftBarge.residual(2_000.0, 2_109.0), 1e-9)   // गूँज जितनी आवाज़ = कुछ नहीं
        assertEquals(1_500.0, SoftBarge.residual(3_609.0, 2_109.0), 1e-9)
    }

    @Test fun fastPathsNoLongerStopDirectly() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val mic = java.io.File(root, "com/dk/assistant/GeminiLiveMic.kt").readText()
        assertTrue(mic.contains("duckBarge(\"कॉल-mode\", duplex.speechMs, now, prob)"))
        assertTrue(mic.contains("duckBarge(\"speaker\", speakerBarge.speechMs, now, prob)"))
        assertTrue(mic.contains("AcousticEchoCanceler.create(sid)?.apply { enabled = true }"))
    }
}
