package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerGateTest {
    private val chunk = ByteArray(1280)
    private val th = VoicePrint.TH_NORMAL

    private fun speak(g: OwnerGate, ms: Int): Boolean { var want = false; repeat(ms / 40) { want = want or g.add(chunk, 2000.0) }; return want }

    @Test fun firstCheckAtOneSecondNotBefore() {
        val g = OwnerGate(); g.start()
        assertFalse(speak(g, 960))
        assertTrue(g.add(chunk, 2000.0))
        g.takeAudio()
        assertFalse(g.wantCheck())        // जाँच चल रही - दोबारा नहीं
    }

    @Test fun ownerPassesOtherBlocked() {
        val g = OwnerGate(); g.start(); speak(g, 1000)
        assertEquals(OwnerGate.Verdict.OWNER, g.onScore(0.6f, th, 1000, 1000, false))
        g.finish(); g.start(); speak(g, 1000)
        // मालिक 99 सेकंड पहले = ढील नहीं; 0.1 बहुत नीचे = तुरंत कोई और
        assertEquals(OwnerGate.Verdict.OTHER, g.onScore(0.1f, th, 100_000, 1000, false))
        val h = OwnerGate(); h.start(); speak(h, 1000)
        assertEquals(OwnerGate.Verdict.OTHER, h.onScore(0.05f, th, 1000, 1000, false))
    }

    @Test fun borderlineWaitsForSecondCheck() {
        val g = OwnerGate(); g.start(); speak(g, 1000)
        assertEquals(OwnerGate.Verdict.PENDING, g.onScore(th - 0.05f, th, 1000, 1000, false))
        speak(g, 960)
        assertTrue(g.add(chunk, 2000.0))
        assertEquals(OwnerGate.Verdict.OTHER, g.onScore(th - 0.05f, th, 2000, 2000, false))
    }

    @Test fun recentOwnerGetsSlack() {
        val g = OwnerGate(); g.start(); speak(g, 1000)
        g.onScore(0.7f, th, 1000, 1000, false); g.finish()
        g.start(); speak(g, 1000)
        assertEquals(OwnerGate.Verdict.OWNER, g.onScore(th - 0.07f, th, 5000, 1000, false))
    }

    @Test fun shortUtteranceDecidedAtEnd() {
        val g = OwnerGate(); g.start(); speak(g, 600)
        assertTrue(g.needFinalCheck(1000))
        assertEquals(OwnerGate.Verdict.OWNER, g.onScore(th - 0.05f, th, 1000, 600, true))   // छोटी = ढील
        val h = OwnerGate(); h.start(); speak(h, 600)
        h.needFinalCheck(1000)
        assertEquals(OwnerGate.Verdict.OTHER, h.onScore(th - 0.12f, th, 1000, 600, true))
    }

    @Test fun tinyKnockIsOtherUnlessTalking() {
        val g = OwnerGate(); g.start(); speak(g, 200)
        assertFalse(g.needFinalCheck(1000)); assertEquals(OwnerGate.Verdict.OTHER, g.verdict)
        val h = OwnerGate(); h.start(); speak(h, 1000); h.onScore(0.8f, th, 1000, 1000, false); h.finish()
        h.start(); speak(h, 200)
        assertFalse(h.needFinalCheck(3000)); assertEquals(OwnerGate.Verdict.OWNER, h.verdict)
    }

    @Test fun modelFailureNeverLosesOwnerVoice() {
        val g = OwnerGate(); g.start(); speak(g, 1000); g.takeAudio()
        g.onFailed(1000); assertEquals(OwnerGate.Verdict.OWNER, g.verdict)
        val h = OwnerGate(); h.start(); speak(h, 1000)
        assertEquals(OwnerGate.Verdict.OWNER, h.onScore(Float.NaN, th, 1000, 1000, false))
    }

    @Test fun bufferCappedAtThreeSeconds() {
        val g = OwnerGate(); g.start(); speak(g, 6000)
        assertEquals(OwnerGate.MAX_MS, g.audioMs())
    }
}
