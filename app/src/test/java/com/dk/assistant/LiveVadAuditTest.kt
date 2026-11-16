package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 16 नवंबर 2026 (मालिक: "Live बातचीत और VAD का पूरा ऑडिट - सभी 8 सुधार एक PR में"): S1-S8 के नियम + जुड़ाव.
 */
class LiveVadAuditTest {
    private fun src(p: String): String {
        val f = listOf("app/src/main/java/com/dk/assistant/$p", "src/main/java/com/dk/assistant/$p").map { File(it) }.first { it.exists() }
        return f.readText()
    }

    // ---------------------------------------------------------------- S1 वेक वर्ड के बाद की आवाज़ फ़ोन VAD से
    @Test fun s1_wakeAudio_goesThroughPhoneVad() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("val hev = turnDet.feed(sqrt(hs / h.size), t, hp)"))
        assertTrue(mic.contains("speechFromWake = true"))
        assertTrue(mic.contains("if (speechFromWake) cmdBuf.write(wakePcm.toByteArray())"))
        // Live तैयार होते ही फ़ोन का "बात खत्म" - पर जवाब पहले से बन रहा हो तो दोबारा नहीं, और 3 s के बाद नहीं
        assertTrue(mic.contains("now <= wakeEndBy && !generating && !turnActive"))
        // वेक वर्ड से *पहले* की आवाज़ नहीं - सिर्फ़ WakeHandoff (बाद की) ही
        assertTrue(mic.indexOf("WakeHandoff.releaseMic()") < mic.indexOf("WakeHandoff.next()"))
    }

    // ---------------------------------------------------------------- S2 speaker पर जल्दी टोकना
    @Test fun s2_speakerBarge_confirmsOnStrongHumanVoice() {
        val b = SpeakerBarge()
        var hit = false
        for (i in 1..8) hit = b.feed(0.9f, 3000.0, 1000.0, 200.0, 40)
        assertTrue("0.32s पक्की आवाज़ = रुको", hit)
        b.reset()
        for (i in 1..20) assertFalse("गूँज से 2.2x कम = नहीं", b.feed(0.95f, 2000.0, 1000.0, 200.0, 40))
        for (i in 1..20) assertFalse("VAD 0.85 से कम = नहीं", b.feed(0.7f, 5000.0, 1000.0, 200.0, 40))
        for (i in 1..20) assertFalse("VAD बंद = नहीं", b.feed(-1f, 5000.0, 1000.0, 200.0, 40))
        // बीच में एक भी कमज़ोर टुकड़ा = फिर से गिनती
        for (i in 1..7) b.feed(0.9f, 3000.0, 1000.0, 200.0, 40)
        assertFalse(b.feed(0.3f, 3000.0, 1000.0, 200.0, 40))
        assertEquals(0L, b.speechMs)
    }

    @Test fun s2_speakerBarge_turnsOffAfterTwoWrongStops() {
        val b = SpeakerBarge()
        b.onConfirm(0L); assertFalse(b.tick(1_000L)); assertFalse(b.tick(2_600L)); assertEquals(1, b.misses)
        b.onConfirm(3_000L); b.onWords(); assertFalse(b.tick(6_000L)); assertEquals(1, b.misses)   // शब्द आए = सही रोका
        b.onConfirm(7_000L); assertTrue(b.tick(9_600L)); assertTrue(b.disabled)
        for (i in 1..20) assertFalse(b.feed(0.99f, 9000.0, 100.0, 100.0, 40))
        b.resetSession(); assertFalse(b.disabled)
    }

    @Test fun s2_wiring() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("speakerBarge.feed(prob, rms, bargeDet.echoLevel(), bargeDet.ambient, CHUNK_MS.toLong())"))
        assertTrue(mic.contains("!duplexOn && !CallSpeaker.active && !Prefs.bargeHold(svc)"))
        assertTrue(mic.contains("bargeGate.confirm(); confirmBarge(\"इंसानी आवाज़ - VAD"))
        assertTrue(src("GeminiLiveCaption.kt").contains("speakerBarge.onWords()"))
        assertTrue(src("GeminiLiveServer.kt").contains("speakerBarge.onWords()"))
        // पुराना कॉल-mode वाला रास्ता वैसा ही
        assertTrue(mic.contains("duplex.feed(prob, overEchoMs >= 120, CHUNK_MS.toLong())"))
    }

    // ---------------------------------------------------------------- S3 चुप caption
    @Test fun s3_captionHealth_staleAfterTwoSilentLongTurns() {
        val h = CaptionHealth()
        h.onSpeechEnd(1_000L, 800, 2_000L)
        assertFalse("caption को अभी समय", h.tick(0L, 3_000L))
        assertFalse(h.tick(0L, 4_600L)); assertEquals(1, h.misses)
        h.onSpeechEnd(5_000L, 900, 6_000L)
        assertTrue(h.tick(0L, 8_600L))
        assertTrue(h.stale(0L))
        assertFalse("नया text = फिर चालू", h.stale(9_000L))
        assertEquals(0, h.misses)
    }

    @Test fun s3_captionHealth_ignoresShortSoundsAndCountsWords() {
        val h = CaptionHealth()
        h.onSpeechEnd(1_000L, 300, 1_400L)          // खटखट - गिनो मत
        assertFalse(h.tick(0L, 10_000L)); assertEquals(0, h.misses)
        h.onSpeechEnd(20_000L, 1_200, 21_500L)
        assertFalse(h.tick(21_800L, 22_000L))        // caption ने इसी बात पर लिखा
        assertEquals(0, h.misses)
        assertFalse(h.stale(0L))
    }

    @Test fun s3_wiring() {
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("it.ready && it.working && !capHealth.stale(it.textAt)"))
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("capHealth.tick(caption?.textAt ?: 0L, now)"))
        assertTrue(mic.contains("if (!other) capHealth.onSpeechEnd(speechStartAt, turnDet.lastSpeechMs, now)"))
    }

    // ---------------------------------------------------------------- S4 AGC + MicBoost
    @Test fun s4_boostLimit() {
        assertEquals(LiveVadFix.BOOST_WITH_AGC, LiveVadFix.boostLimit(true), 0.0)
        assertEquals(LiveHear.BOOST_MAX, LiveVadFix.boostLimit(false), 0.0)
    }

    private fun softGain(limit: Double?): Double {
        val b = MicBoost(maxGain = LiveHear.BOOST_MAX, noiseCap = LiveHear.BOOST_NOISE_CAP)
        if (limit != null) b.gainLimit = limit
        val n = 640
        repeat(40) { b.apply(ShortArray(n) { i -> (if (i % 2 == 0) 30 else -30).toShort() }, n) }          // पंखे जितना शोर
        repeat(300) { b.apply(ShortArray(n) { i -> (100 * Math.sin(i * 0.2)).toInt().toShort() }, n) }   // धीमी बात
        return b.gain
    }

    @Test fun s4_micBoost_respectsLimit() {
        val free = softGain(null)
        val capped = softGain(LiveVadFix.BOOST_WITH_AGC)
        assertTrue("पहले जैसा 6 से ऊपर तक: $free", free > LiveVadFix.BOOST_WITH_AGC)
        assertTrue("AGC के साथ 6 तक: $capped", capped <= LiveVadFix.BOOST_WITH_AGC + 1e-9)
        assertTrue(src("GeminiLiveMic.kt").contains("boost.gainLimit = LiveVadFix.boostLimit(agc)"))
    }

    // ---------------------------------------------------------------- S5 Silero न चले
    @Test fun s5_rmsOnly_needsLouderVoice() {
        val old = TurnDetector()
        var started = false
        for (i in 1..5) if (old.feed(700.0, i * 40L) == TurnDetector.Event.SPEECH_START) started = true
        assertTrue("पुराना: 700 RMS = बोलना", started)

        val strict = TurnDetector().apply { rmsOnlyScale = LiveVadFix.RMS_ONLY_SCALE }
        started = false
        for (i in 1..5) if (strict.feed(700.0, i * 40L) == TurnDetector.Event.SPEECH_START) started = true
        assertFalse("Silero नहीं: 700 RMS (ऊँचे दरवाज़े से नीचे) = नहीं", started)

        // Silero चल रहा (prob >= 0) = scale का असर नहीं
        val vad = TurnDetector().apply { rmsOnlyScale = LiveVadFix.RMS_ONLY_SCALE }
        started = false
        for (i in 1..5) if (vad.feed(700.0, i * 40L, 0.9f) == TurnDetector.Event.SPEECH_START) started = true
        assertTrue(started)
        assertEquals(1.0, LiveVadFix.rmsOnlyScale(false), 0.0)
        assertTrue(src("GeminiLiveMic.kt").contains("turnDet.rmsOnlyScale = LiveVadFix.rmsOnlyScale("))
    }

    // ---------------------------------------------------------------- S6 जल्दी "खत्म" सावधान
    @Test fun s6_earlySilence_followsHabit() {
        assertEquals(TurnEnd.EARLY_END_MS, LiveVadFix.earlySilenceMs(0, 500))
        assertEquals(TurnEnd.EARLY_END_MS, LiveVadFix.earlySilenceMs(250, 500))
        assertEquals(440, LiveVadFix.earlySilenceMs(380, 500))
        assertEquals(500, LiveVadFix.earlySilenceMs(1_200, 500))     // आम बात से धीमा कभी नहीं
        assertFalse(LiveVadFix.earlySafe(400, 380, 500))
        assertTrue(LiveVadFix.earlySafe(440, 380, 500))
        assertTrue(LiveVadFix.EARLY_SETTLE_MS > 200)
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("now - capSeenAt >= LiveVadFix.EARLY_SETTLE_MS && LiveVadFix.earlySafe(turnDet.silentMs, turnDet.typicalPauseMs, CLIENT_END_MS)"))
    }

    // ---------------------------------------------------------------- S7 एक लाइन की VAD रिपोर्ट
    @Test fun s7_summaryLine() {
        val s = LiveVadFix.summary("पूरा वाक्य (जल्दी)", 1_240, 380, 360, true, "caption", 212.7, false)
        assertTrue(s.startsWith("VAD सारांश:"))
        assertTrue(s.contains("1240 ms") && s.contains("पूरा वाक्य") && s.contains("380/360") && s.contains("Silero") && s.contains("शोर=212"))
        assertTrue(LiveVadFix.summary("चुप्पी", 900, 500, 500, false, "none", 100.0, true).contains("सिर्फ़ RMS"))
        assertTrue(LiveVadFix.summary("चुप्पी", 900, 500, 500, false, "none", 100.0, true).contains("वेक वर्ड"))
        assertTrue(src("GeminiLiveMic.kt").contains("log(LiveVadFix.summary(endRule.ifBlank { \"चुप्पी\" }"))
    }

    // ---------------------------------------------------------------- S8 टिप्पणियाँ असली मानों से
    @Test fun s8_commentsMatchValues() {
        assertEquals(800, GeminiLive.SERVER_SILENCE_MS)
        assertTrue(src("GeminiLive.kt").contains("ऑडिट S8, सिर्फ़ टिप्पणी"))
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("ऑडिट S8, सिर्फ़ टिप्पणी"))
        assertTrue(setup.contains(".put(\"endOfSpeechSensitivity\", \"END_SENSITIVITY_LOW\")"))
    }

    // ---------------------------------------------------------------- पाबंदियाँ
    @Test fun noKeyScreen_noEagle() {
        val f = src("LiveVadFix.kt")
        assertFalse(f.contains("Eagle") || f.contains("picovoice"))
        assertFalse(f.contains("apiKey") || f.contains("EditText"))
    }
}
