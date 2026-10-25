package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.66: दूर की आवाज़ (परत 4) + पूरे वाक्य पर जल्दी "बात खत्म" (तेज़ी) */
class OwnerFastTest {
    private fun run(d: TurnDetector, rms: Double, ms: Int, t0: Long): Pair<List<TurnDetector.Event>, Long> {
        val ev = ArrayList<TurnDetector.Event>(); var t = t0
        repeat(ms / 40) { val e = d.feed(rms, t); if (e != TurnDetector.Event.NONE) ev.add(e); t += 40 }
        return ev to t
    }

    @Test fun farVoiceDoesNotStartTurn() {
        val d = TurnDetector(); d.startFloor = 1500.0
        var (_, t) = run(d, 200.0, 800, 0)
        val (ev, t2) = run(d, 900.0, 1500, t)              // दूर की आवाज़: शोर से तेज़ पर मालिक से बहुत धीमी
        assertTrue(ev.isEmpty()); assertFalse(d.inSpeech); assertEquals(1, d.farBlocked)
        assertTrue("दूर की आवाज़ को शोर नहीं सीखा", d.noise < 400)
        val (ev2, _) = run(d, 2500.0, 200, t2)             // पास की (मालिक) आवाज़
        assertEquals(listOf(TurnDetector.Event.SPEECH_START), ev2)
    }

    @Test fun floorOffMeansOldBehaviour() {
        val d = TurnDetector()
        val (_, t) = run(d, 200.0, 800, 0)
        val (ev, _) = run(d, 900.0, 200, t)
        assertEquals(listOf(TurnDetector.Event.SPEECH_START), ev)
    }

    @Test fun quietEndOfWordsKeptOnceSpeaking() {
        val d = TurnDetector(); d.startFloor = 1500.0
        var (_, t) = run(d, 200.0, 800, 0)
        t = run(d, 2500.0, 400, t).second
        val (ev, _) = run(d, 700.0, 400, t)               // बोलते-बोलते आवाज़ धीमी - बात नहीं कटी
        assertTrue(ev.isEmpty()); assertTrue(d.inSpeech)
    }

    @Test fun forceEndOnlyWhileRealSpeech() {
        val d = TurnDetector()
        assertFalse(d.forceEnd())
        var (_, t) = run(d, 200.0, 800, 0)
        t = run(d, 2500.0, 600, t).second
        t = run(d, 200.0, 360, t).second
        assertEquals(360, d.silentMs)
        assertTrue(d.forceEnd()); assertFalse(d.inSpeech); assertTrue(d.lastSpeechMs >= 400)
    }

    @Test fun completeSentenceDetection() {
        assertTrue(TurnEnd.complete("व्हाट्सऐप खोलो"))
        assertTrue(TurnEnd.complete("आज मौसम कैसा है"))
        assertTrue(TurnEnd.complete("अलार्म लगा देना?"))
        assertTrue(TurnEnd.complete("open youtube now"))
        assertFalse(TurnEnd.complete("खोलो"))                // एक शब्द
        assertFalse(TurnEnd.complete("राहुल को"))
        assertFalse(TurnEnd.complete("मैसेज भेजो और"))
        assertFalse(TurnEnd.complete(""))
        assertTrue(TurnEnd.EARLY_END_MS < 550)  // GeminiLive.CLIENT_END_MS
        assertTrue(TurnEnd.complete("कल का अलार्म लगा देना"))
    }
}
