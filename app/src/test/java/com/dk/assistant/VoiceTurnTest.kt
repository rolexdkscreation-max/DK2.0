package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.8: phone par chalne wala VAD (bolna shuru/khatam) + barge-in + deri ke hisse.
 * Har tukda = 40ms mic audio ka RMS (jaisa GeminiLive.micLoop deta hai).
 */
class VoiceTurnTest {
    private val chunk = 40L

    /** rms list ko 40ms tukdon me khilao; har event ka samay (ms) lautao */
    private fun run(d: TurnDetector, rms: List<Double>, t0: Long = 0L): List<Pair<TurnDetector.Event, Long>> {
        val out = mutableListOf<Pair<TurnDetector.Event, Long>>()
        rms.forEachIndexed { i, r ->
            val now = t0 + i * chunk
            val e = d.feed(r, now)
            if (e != TurnDetector.Event.NONE) out.add(e to now)
        }
        return out
    }

    private fun seg(ms: Int, rms: Double) = List(ms / 40) { rms }

    @Test fun endOfSpeechFiresAbout500msAfterLastWord() {
        val d = TurnDetector()
        val noise = seg(1000, 180.0)
        val speech = seg(1200, 2500.0)
        val silence = seg(1500, 180.0)
        val ev = run(d, noise + speech + silence)
        assertEquals(TurnDetector.Event.SPEECH_START, ev[0].first)
        assertEquals(TurnDetector.Event.SPEECH_END, ev[1].first)
        val lastWordAt = (noise.size + speech.size - 1) * chunk
        val gap = ev[1].second - lastWordAt
        // 500ms ka chup (40ms ke tukdon me) - na pehle, na 40ms se zyada der
        assertTrue("gap=$gap", gap in 500..540)
        assertEquals(lastWordAt, d.lastVoiceAt)
    }

    @Test fun shortPauseInsideSentenceDoesNotEndTurn() {
        val d = TurnDetector()
        val ev = run(d, seg(800, 180.0) + seg(600, 2500.0) + seg(320, 180.0) + seg(600, 2500.0) + seg(1000, 180.0))
        assertEquals(listOf(TurnDetector.Event.SPEECH_START, TurnDetector.Event.SPEECH_END), ev.map { it.first })
    }

    @Test fun quietTailKeepsSpeechAlive() {
        val d = TurnDetector()
        // shabd ka aakhri hissa dheema (400) - shor 180 se kaafi upar -> abhi bhi bol raha hai
        val ev = run(d, seg(800, 180.0) + seg(400, 2500.0) + seg(400, 400.0) + seg(1000, 180.0))
        val end = ev.first { it.first == TurnDetector.Event.SPEECH_END }.second
        val quietEnd = (20 + 10 + 10 - 1) * chunk
        assertTrue("end=$end quietEnd=$quietEnd", end - quietEnd in 500..540)
    }

    @Test fun coughOrClickIsNotATurn() {
        val d = TurnDetector()
        val ev = run(d, seg(800, 180.0) + seg(120, 3000.0) + seg(1000, 180.0))
        assertFalse(ev.any { it.first == TurnDetector.Event.SPEECH_END })
    }

    @Test fun steadyFanNoiseIsLearnedNotSpeech() {
        val d = TurnDetector()
        val ev = run(d, seg(4000, 600.0))       // pankha: lagatar 600
        // shuru me 600 > 420 ho sakta hai; par shor seekhne ke baad koi pakka "baat" nahi banni chahiye baar-baar
        assertTrue(ev.count { it.first == TurnDetector.Event.SPEECH_END } <= 1)
        val d2 = TurnDetector()
        run(d2, seg(2000, 150.0))
        assertTrue(d2.noise < 200)
    }

    @Test fun bargeInDetectedWithin120msOfUserSpeech() {
        val b = BargeDetector()
        var t = 0L
        // DK bol raha hai: goonj ~1100 (seekhna)
        repeat(25) { assertFalse(b.feed(1100.0 + (it % 3) * 60, t)); t += chunk }
        val userStart = t
        var hitAt = -1L
        repeat(10) { if (b.feed(4200.0, t) && hitAt < 0) hitAt = t; t += chunk }
        assertTrue(b.triggered)
        assertTrue("hit=${hitAt - userStart}", hitAt - userStart in 0..120)
    }

    @Test fun echoAloneNeverTriggersBarge() {
        val b = BargeDetector()
        var t = 0L
        // DK ki goonj dheere-dheere tez hoti hai (shuru ke 400ms seekhne me)
        repeat(100) { i -> assertFalse("i=$i", b.feed(600.0 + minOf(i, 10) * 80 + (i % 4) * 90, t)); t += chunk }
        assertFalse(b.triggered)
    }

    @Test fun bargeOnlyOncePerPlayback() {
        val b = BargeDetector()
        var t = 0L
        repeat(12) { b.feed(800.0, t); t += chunk }
        var hits = 0
        repeat(20) { if (b.feed(5000.0, t)) hits++; t += chunk }
        assertEquals(1, hits)
        b.reset()
        assertFalse(b.triggered)
    }

    @Test fun latencyTraceSplitsStages() {
        val tr = LatencyTrace()
        tr.speechEndAt = 1000; tr.endpointAt = 1500; tr.firstChunkAt = 2100; tr.firstPlayAt = 2140; tr.rttMs = 90
        val s = tr.summary()
        assertNotNull(s)
        assertTrue(s!!, s.contains("कुल 1140ms"))
        assertTrue(s, s.contains("500ms") && s.contains("600ms") && s.contains("40ms"))
        assertEquals("generation/network", tr.bottleneck())
    }
}
