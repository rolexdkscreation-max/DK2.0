package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.7 (मालिक): टूटे तो 1-2s में जुड़े • limit = तुरंत दूसरी key • बात खत्म होते ही जवाब •
 * जवाब के बीच बोलूँ तो DK रुककर सुने • दूर की आवाज़ अनसुनी, पास धीरे/ज़ोर से = सुने.
 */
class LiveFastBargeNearTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private fun det(tail: Double) = TurnDetector(40, endSilenceMs = 550, startFactor = LiveHear.START_FACTOR, startMin = LiveHear.START_MIN,
        loudNoiseFactor = LiveHear.LOUD_FACTOR, steadyResetMs = 6_000, keepFactor = LiveHear.KEEP_FACTOR, keepMin = LiveHear.KEEP_MIN, tailFactor = tail)

    /** बोलना, फिर बात खत्म होने के बाद माइक का शोर (AGC) - कितने ms में "बात खत्म" */
    private fun endAfter(d: TurnDetector, talk: Double, after: Double): Long? {
        var t = 0L; var ended: Long? = null
        repeat(10) { d.feed(250.0, t); t += 40 }                      // कमरा
        repeat(30) { d.feed(if (it % 4 == 0) talk * 0.6 else talk, t); t += 40 }   // ~1.2s बोलना
        val stop = t
        repeat(100) { if (ended == null && d.feed(after, t) == TurnDetector.Event.SPEECH_END) ended = t - stop; t += 40 }
        return ended
    }

    @Test fun endsRightAfterSpeechEvenWithMicNoise() {
        val now = endAfter(det(LiveHear.TAIL_FACTOR), 4000.0, 420.0)
        assertTrue("बात खत्म $now ms", now != null && now <= 700)
        // पहले: शोर "अभी भी बोल रहा" की सीमा से ऊपर = सेकंडों तक बात खत्म नहीं
        val old = endAfter(det(0.0), 4000.0, 420.0)
        assertTrue(old == null || old > 3_000)
        // असली चुप्पी पहले जैसी
        assertTrue(endAfter(det(LiveHear.TAIL_FACTOR), 4000.0, 100.0)!! <= 700)
    }

    @Test fun softSpeechNotCutMidway() {
        val d = det(LiveHear.TAIL_FACTOR); var t = 0L
        repeat(10) { d.feed(250.0, t); t += 40 }
        repeat(25) { d.feed(700.0, t); t += 40 }                      // पास धीरे बोलना
        var cut = false
        repeat(25) { if (d.feed(380.0, t) == TurnDetector.Event.SPEECH_END) cut = true; t += 40 }   // और धीमा आख़िरी शब्द
        assertFalse(cut); assertTrue(d.inSpeech)
    }

    @Test fun nearVoiceLearnsAndBlocksFar() {
        val n = NearVoice()
        assertEquals(0.0, n.floor(), 0.0)                              // अभी नहीं पता = सब सुनो
        listOf(3000.0, 2600.0, 3400.0).forEach { n.learn(it) }
        assertEquals(3000.0, n.level, 0.0); assertEquals(600.0, n.floor(), 1e-6)
        n.learn(50_000.0); n.learn(-1.0); n.learn(Double.NaN)
        assertTrue(n.floor() <= NearVoice.MAX_FLOOR)
        val m = NearVoice(); m.load(n.save()); assertEquals(n.level, m.level, 1.0)
        assertEquals(0.0, NearVoice.utterLevel(listOf(900.0, 900.0), 250.0), 0.0)   // बहुत कम टुकड़े
        assertEquals(1000.0, NearVoice.utterLevel(List(10) { 1000.0 } + List(10) { 100.0 }, 250.0), 0.0)

        fun starts(rms: Double): Boolean {
            val d = det(LiveHear.TAIL_FACTOR); d.startFloor = n.floor(); var t = 0L; var s = false
            repeat(10) { d.feed(250.0, t); t += 40 }
            repeat(15) { if (d.feed(rms, t) == TurnDetector.Event.SPEECH_START) s = true; t += 40 }
            return s
        }
        assertFalse(starts(500.0))           // दूर की आवाज़ (आपकी आम आवाज़ से ~−15 dB)
        assertTrue(starts(950.0))            // पास धीरे बोलना (~−10 dB)
        assertTrue(starts(6000.0))           // ज़ोर से
    }

    @Test fun reconnectWithinTwoSeconds() {
        assertTrue((0..9).all { LiveRetry.fast(true, LiveRetry.liveOnMs(true, it)) <= 2_000L })
        assertEquals(1_000L, LiveRetry.fast(true, LiveRetry.liveOnMs(true, 0)))
        assertEquals(60_000L, LiveRetry.fast(false, LiveRetry.liveOnMs(false, 0)))     // सारी keys की limit खत्म
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("minOf(LiveRetry.FAST_MAX_MS, 500L shl quickDrops)"))
        assertTrue(g.contains("delay(minOf(LiveRetry.FAST_MAX_MS, 500L shl fails))"))
        assertTrue(g.contains("withTimeoutOrNull(6_000) {\n                        while (!setup.isCompleted"))
        assertFalse(g.contains("minOf(15_000L, 500L shl quickDrops)"))
        assertTrue(src("DKAccessibilityService.kt").contains("LiveRetry.fast(e.recoverable, LiveRetry.liveOnMs(e.recoverable, liveSoftFails))"))
    }

    @Test fun limitSwitchesKeyFirst() {
        val g = src("GeminiLive.kt")
        val a = g.indexOf("if (switchKey(\"इस key की limit खत्म\")) continue")
        assertTrue(a > 0 && a < g.indexOf("else if (mi + 1 < models.size)", a))
        val b = g.indexOf("if (switchKey(\"बातचीत के बीच इस key की limit खत्म\")) continue")
        assertTrue(b > 0 && b < g.indexOf("else if (mi + 1 < models.size)", b))
    }

    @Test fun bargeStopsAndListens() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("captionOn() && !LiveStop.isStop(t) && Prefs.bargeHold(svc)"))
        assertTrue(g.contains("!LiveStop.isStop(t) && Prefs.bargeHold(svc)) { DKState.hint(HOLD_HINT); return }"))
        assertTrue(g.contains("BargeDetector(needChunks = 5, minRms = 900.0)"))
        assertTrue(src("Prefs.kt").contains("getBoolean(\"barge_hold\", false)"))
        // असली आवाज़: 1000 RMS (पहले 1200 से नीचे = नहीं पकड़ता था)
        val b = BargeDetector(needChunks = 5, minRms = 900.0); b.ambient = 200.0
        var t = 0L; var hit = false
        repeat(12) { b.feed(150.0, t, 0.0); t += 40 }
        repeat(8) { if (b.feed(1100.0, t, 0.0)) hit = true; t += 40 }
        assertTrue(hit)
    }

    @Test fun nearWiredIn() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("tailFactor = LiveHear.TAIL_FACTOR"))
        assertTrue(g.contains("if (!ownerOn && !btOn && Prefs.ownerFarOn(svc)) turnDet.startFloor = near.floor()"))
        assertTrue(g.contains("learnNear()"))
        val s = src("SettingsActivity.kt")
        assertTrue(s.contains("\"दूर की आवाज़ अनसुनी करो") && s.contains("putBoolean(\"barge_hold\", !on)"))
    }
}
