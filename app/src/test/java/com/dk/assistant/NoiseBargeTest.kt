package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.9: DK bolte samay peeche ke shor par jawab chalta rahe; sirf user ke pakke shabdon par ruke */
class NoiseBargeTest {

    @Test fun shortKnockDoesNotEvenSuspect() {
        val b = BargeDetector(); b.ambient = 300.0
        var t = 0L; var hit = false
        repeat(10) { hit = hit or b.feed(200.0, t); t += 40 }        // goonj seekho
        repeat(4) { hit = hit or b.feed(6000.0, t); t += 40 }        // 160ms khatak
        repeat(10) { hit = hit or b.feed(200.0, t); t += 40 }
        assertFalse(hit)
    }

    @Test fun roomNoiseLevelDoesNotSuspect() {
        // kamre me pehle se 900 ka shor (TV) -> 2500 ka shor user nahi (3.5x = 3150 chahiye)
        val b = BargeDetector(); b.ambient = 900.0
        var t = 0L; var hit = false
        repeat(40) { hit = hit or b.feed(2500.0, t); t += 40 }
        assertFalse(hit)
    }

    @Test fun noiseWithoutWordsIsFalseAlarmAndReplyContinues() {
        val g = BargeGate()
        g.suspect(1000)
        assertFalse(g.onText("", "आज मौसम साफ़ है"))
        assertFalse(g.onText("आ", "आज मौसम साफ़ है"))                  // ek akshar / aawaaz
        assertFalse(g.expired(2000, captionOn = true, loudMs = 900))
        assertTrue(g.expired(2600, captionOn = true, loudMs = 0))    // 1.5s me shabd nahi -> shor
        assertEquals(BargeGate.State.IDLE, g.state)
    }

    @Test fun realWordsConfirm() {
        val g = BargeGate(); g.suspect(0)
        assertTrue(g.onText("रुको", "आज मौसम साफ़ है और तापमान 30 डिग्री"))
        assertEquals(BargeGate.State.CONFIRMED, g.state)
        val h = BargeGate(); h.suspect(0)
        assertTrue(h.onText("हाँ ठीक है", ""))
    }

    @Test fun dkOwnVoiceInCaptionIsNotUser() {
        val reply = "आज मुंबई में हल्की बारिश होगी और तापमान तीस डिग्री रहेगा"
        val g = BargeGate(); g.suspect(0)
        assertFalse(g.onText("मुंबई में हल्की बारिश", reply))
        assertEquals(BargeGate.State.TENTATIVE, g.state)
        assertTrue(BargeGate.isEcho("मुंबई में हल्की बारिश", reply))
        assertFalse(BargeGate.isEcho("रुको दूसरा सवाल", reply))
    }

    @Test fun withoutCaptionLongSpeechConfirms() {
        val g = BargeGate(); g.suspect(0)
        assertFalse(g.expired(400, captionOn = false, loudMs = 400))
        g.expired(800, captionOn = false, loudMs = 720)
        assertTrue(g.confirmedByLength())
    }

    @Test fun rearmIgnoresSameNoiseNextTime() {
        val b = BargeDetector(); b.ambient = 200.0
        var t = 1000L
        repeat(12) { b.feed(150.0, t); t += 40 }                     // 400ms goonj seekho
        var hit = false
        repeat(8) { hit = hit or b.feed(2000.0, t); t += 40 }
        assertTrue(hit)
        b.rearm(2000.0)                                              // shor tha
        hit = false
        repeat(20) { hit = hit or b.feed(2000.0, t); t += 40 }       // wahi shor dobara
        assertFalse(hit)
    }

    @Test fun softSpeechOpensGateInQuietRoom() {
        val d = TurnDetector(); var t = 0L
        repeat(25) { d.feed(100.0, t); t += 40 }                     // shant kamra
        var started = false
        repeat(5) { started = started or (d.feed(380.0, t) == TurnDetector.Event.SPEECH_START); t += 40 }
        assertTrue(started)
    }
}
