package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemini Live latency / "jawab nahi aaya" fix ke pure-logic hisse (Android ke bina). */
class LiveReliabilityTest {

    // ---------- ReplyWatchdog: barge-in ke baad nayi baat ----------

    @Test fun bargeUtteranceGetsRescueAfterReset() {
        val w = ReplyWatchdog(timeoutMs = 2500, minSpeechMs = 400)
        // pichhli baat ka jawab aa chuka tha
        w.onSpeechStart(); w.onEndOfSpeech(0, 800); w.onServerAnswered()
        // bina reset ke nayi (barge-in) baat par watchdog band rehta tha
        w.onEndOfSpeech(10_000, 800)
        assertFalse(w.due(20_000))
        // confirmBarge ab onSpeechStart() bulata hai -> rescue phir se armed
        w.onSpeechStart(); w.onEndOfSpeech(30_000, 800)
        assertFalse(w.due(32_000))
        assertTrue(w.due(32_500))
        assertFalse(w.due(33_000)) // sirf ek baar
    }

    @Test fun serverHeardCancelsRescue() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onEndOfSpeech(0, 800)
        w.onServerHeard() // e.g. interrupted / inputTranscription
        assertFalse(w.due(10_000))
    }

    @Test fun unansweredTracksPendingUtterance() {
        val w = ReplyWatchdog(minSpeechMs = 400)
        w.onSpeechStart(); w.onEndOfSpeech(1_000, 800)
        assertTrue(w.unanswered(5_000))
        assertFalse(w.unanswered(1_000 + 20_001)) // bahut purani baat dobara nahi
        w.onResent()
        assertFalse(w.unanswered(5_000))
    }

    @Test fun answeredOrShortSpeechIsNotUnanswered() {
        val w = ReplyWatchdog(minSpeechMs = 400)
        w.onSpeechStart(); w.onEndOfSpeech(1_000, 200) // khaansi
        assertFalse(w.unanswered(2_000))
        w.onSpeechStart(); w.onEndOfSpeech(3_000, 900); w.onServerAnswered()
        assertFalse(w.unanswered(4_000))
    }

    // ---------- SilenceStreamGate ----------

    @Test fun silenceSentForKeepMsThenEndedOnce() {
        val g = SilenceStreamGate(chunkMs = 40, keepMs = 1000)
        g.onReal()
        val acts = (1..30).map { g.onSilence() }
        assertEquals(24, acts.count { it == SilenceStreamGate.Action.SEND })
        assertEquals(1, acts.count { it == SilenceStreamGate.Action.SEND_THEN_END })
        assertEquals(SilenceStreamGate.Action.SEND_THEN_END, acts[24])
        assertTrue(acts.drop(25).all { it == SilenceStreamGate.Action.SKIP })
        assertTrue(g.paused)
    }

    @Test fun realAudioReopensStream() {
        val g = SilenceStreamGate(chunkMs = 40, keepMs = 120)
        repeat(5) { g.onSilence() }
        assertTrue(g.paused)
        g.onReal()
        assertFalse(g.paused)
        assertEquals(SilenceStreamGate.Action.SEND, g.onSilence())
    }

    // ---------- TurnTimeline ----------

    @Test fun timelineRecordsFirstOfEachStage() {
        val t = TurnTimeline()
        assertFalse(t.active)
        assertEquals("-", t.summary())
        t.play(50) // bina begin ke kuch nahi
        t.begin(1_000, 1_500, "local VAD")
        assertTrue(t.active)
        t.upload(1_520); t.upload(1_600)
        t.event(1_900); t.event(2_000)
        t.audio(2_100); t.play(2_180); t.play(2_300)
        t.genDone(3_000); t.done(5_200)
        assertFalse(t.active)
        assertEquals(1_520, t.uploadAt)
        assertEquals(1_900, t.eventAt)
        assertEquals(2_180, t.playAt)
        val s = t.summary()
        assertTrue(s.contains("+500ms"))
        assertTrue(s.contains("+1180ms"))
        assertTrue(s.contains("+4200ms"))
        assertTrue(s.endsWith("[local VAD]"))
    }

    @Test fun timelineIgnoresEventsAfterDone() {
        val t = TurnTimeline()
        t.begin(0, 10, "x"); t.done(100)
        t.event(200); t.audio(300)
        assertEquals(0, t.eventAt)
        assertEquals(0, t.audioAt)
    }

    // ---------- LiveRetry ----------

    @Test fun hardErrorsPauseTenMinutes() {
        assertEquals(10 * 60_000L, LiveRetry.offMs(false, 0))
        assertEquals(10 * 60_000L, LiveRetry.offMs(false, 9))
    }

    @Test fun softErrorsBackOffAndCap() {
        val seq = (0..8).map { LiveRetry.offMs(true, it) }
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L, 300_000L), seq)
        assertEquals(15_000L, LiveRetry.offMs(true, -3))
    }
}
