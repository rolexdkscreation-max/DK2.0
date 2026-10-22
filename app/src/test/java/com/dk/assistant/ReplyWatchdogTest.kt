package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.9.1: bola par Gemini ne suna hi nahi (log 15:59-16:01) -> pakdo, ek hi baar */
class ReplyWatchdogTest {

    @Test fun serverSilentAfterRealSpeechIsDueOnce() {
        val w = ReplyWatchdog(timeoutMs = 2500, minSpeechMs = 400)
        w.onSpeechStart()
        w.onEndOfSpeech(now = 10_000, speechMs = 1200)
        assertFalse(w.due(11_000))
        assertFalse(w.due(12_499))
        assertTrue(w.due(12_500))
        assertFalse("sirf ek baar", w.due(13_000))
    }

    @Test fun serverHeardMeansNoRescue() {
        val w = ReplyWatchdog()
        w.onSpeechStart()
        w.onEndOfSpeech(0, 1200)
        w.onServerHeard()                 // ACTIVITY_START / text / awaaz aayi
        assertFalse(w.due(5_000))
    }

    @Test fun heardBeforeEndStaysHeard() {
        val w = ReplyWatchdog()
        w.onSpeechStart()
        w.onServerHeard()                 // server ne bolte-bolte hi text bheja
        w.onEndOfSpeech(0, 1200)
        assertFalse(w.due(5_000))
    }

    @Test fun shortClickIsIgnored() {
        val w = ReplyWatchdog(minSpeechMs = 400)
        w.onSpeechStart()
        w.onEndOfSpeech(0, 240)           // khatak / khaansi
        assertFalse(w.due(5_000))
    }

    @Test fun newSpeechResetsHeard() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onServerHeard(); w.onEndOfSpeech(0, 1000)
        assertFalse(w.due(3_000))
        w.onSpeechStart()                 // nayi baat - server ne ise nahi suna
        w.onEndOfSpeech(4_000, 1000)
        assertTrue(w.due(6_500))
    }

    @Test fun turnDetectorReportsSpeechLength() {
        val d = TurnDetector()
        var t = 0L
        fun feed(ms: Int, rms: Double) { repeat(ms / 40) { d.feed(rms, t); t += 40 } }
        feed(1000, 180.0); feed(1200, 2500.0); feed(800, 180.0)
        assertTrue("len=${d.lastSpeechMs}", d.lastSpeechMs in 1100..1300)
        assertEquals(false, d.inSpeech)
    }

    // v3.29: सुना (text आया) पर जवाब शुरू नहीं हुआ -> एक बार "अटका"
    @Test fun heardButNoAnswerIsStalledOnce() {
        val w = ReplyWatchdog(answerTimeoutMs = 4500)
        w.onSpeechStart(); w.onServerHeard()
        w.onEndOfSpeech(10_000, 1200)
        assertFalse(w.due(13_000))
        assertFalse(w.stalled(14_000))
        assertTrue(w.stalled(14_500))
        assertFalse("सिर्फ़ एक बार", w.stalled(20_000))
    }

    @Test fun answerStartedIsNotStalled() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onServerHeard(); w.onEndOfSpeech(0, 1200)
        w.onServerAnswered()
        assertFalse(w.stalled(10_000))
    }

    @Test fun rescuedTextIsNotNudgedAgain() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onEndOfSpeech(0, 1200)
        assertTrue(w.due(2_500))
        w.onRescued()
        assertFalse(w.stalled(10_000))
    }

    @Test fun notHeardIsNotStalledButDue() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onEndOfSpeech(0, 1200)
        assertFalse("नहीं सुना = stalled नहीं (due संभालता है)", w.stalled(10_000))
    }
}
