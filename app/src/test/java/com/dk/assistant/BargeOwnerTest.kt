package com.dk.assistant

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (लॉग: VAD 1.00 पर rms=5263 < सीमा 14015, गूँज=5378): मालिक की आवाज़ मिली = टोकने की सीमा गूँज के हिसाब से नीची */
class BargeOwnerTest {
    private fun src(p: String) = listOf("src/main/java/com/dk/assistant/$p", "app/src/main/java/com/dk/assistant/$p").map(::File).first { it.exists() }.readText()

    /** लॉग जैसी हालत: गूँज ~5378, DK की अपनी आवाज़ तेज़ (सीमा 14000 के आसपास) */
    private fun logLike(): BargeDetector {
        val d = BargeDetector(needChunks = 5, minRms = 900.0)
        d.ambient = 300.0
        var t = 0L
        repeat(20) { d.feed(5378.0, t, out = 8000.0, prob = 0.3f); t += 40 }   // सीखना: गूँज ~5378
        return d
    }

    @Test fun logCase_ownerLowersThreshold() {
        val d = logLike()
        val before = d.threshold(8000.0, 1.0f)
        assertTrue("पहले सीमा आपकी आवाज़ से ऊपर ($before)", before > 5263.0)
        d.ownerNear = true
        val after = d.threshold(8000.0, 1.0f)
        assertTrue("मालिक मिलने पर सीमा नीची ($after)", after < 5263.0 && after < before)
        // पर ढीली नहीं: गूँज के 0.9 गुना से कम नहीं
        assertTrue(after >= d.echoLevel() * BargeOwner.ECHO_X - 1)
    }

    @Test fun lowThresholdOnlyWithSureVad() {
        val d = logLike()
        d.ownerNear = true
        val unsure = d.threshold(8000.0, 0.5f)
        d.ownerNear = false
        assertTrue(unsure == d.threshold(8000.0, 0.5f))          // VAD पक्का नहीं = पहले वाली सीमा
    }

    @Test fun ownerAudioTriggersSuspect_dkEchoAloneDoesNot() {
        val d = logLike()
        var t = 1_000L
        // DK की अपनी गूँज (मालिक नहीं मिला) - VAD ऊँचा हो तब भी शक नहीं
        var hit = false
        repeat(10) { if (d.feed(5300.0, t, 8000.0, 1.0f)) hit = true; t += 40 }
        assertFalse(hit)
        d.ownerNear = true
        repeat(10) { if (d.feed(5300.0, t, 8000.0, 1.0f)) hit = true; t += 40 }
        assertTrue("मालिक की आवाज़ पर शक (फिर शब्दों से पक्का)", hit)
    }

    @Test fun resetClearsOwner() {
        val d = logLike(); d.ownerNear = true; d.reset(); assertFalse(d.ownerNear)
    }

    @Test fun whenToCheck() {
        assertTrue(BargeOwner.wantCheck(true, 0.95f, 600, false, 5_000))
        assertFalse(BargeOwner.wantCheck(false, 0.95f, 600, false, 5_000))   // आवाज़ पहचान बंद
        assertFalse(BargeOwner.wantCheck(true, 0.8f, 600, false, 5_000))     // VAD पक्का नहीं
        assertFalse(BargeOwner.wantCheck(true, 0.95f, 400, false, 5_000))    // आवाज़ छोटी
        assertFalse(BargeOwner.wantCheck(true, 0.95f, 600, true, 5_000))     // पिछला चल रहा
        assertFalse(BargeOwner.wantCheck(true, 0.95f, 600, false, 500))      // अभी-अभी किया
        assertTrue(BargeOwner.matched(0.7f, 0.6f)); assertFalse(BargeOwner.matched(0.5f, 0.6f)); assertFalse(BargeOwner.matched(null, 0.6f))
        assertTrue(BargeOwner.fresh(1_000, 2_000)); assertFalse(BargeOwner.fresh(1_000, 1_000 + BargeOwner.FRESH_MS + 1)); assertFalse(BargeOwner.fresh(0, 10))
    }

    @Test fun wiredInMicLoop_speakerMatchUnchanged() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("bargeDet.ownerNear = BargeOwner.fresh(bargeOwnerAt, now)"))
        assertTrue(mic.contains("BargeOwner.wantCheck(vgOn, prob, bargeNearMs, bargeOwnerBusy, now - bargeOwnerTriedAt)) checkBargeOwner(now)"))
        val own = src("GeminiLiveOwner.kt")
        assertTrue(own.contains("val sc = try { ownerScore(audio) } catch (_: Throwable) { null }"))
        assertTrue(own.contains("if (vgOn) com.dk.assistant.voice.VoiceGuardian.score(svc, audio) else null"))
    }
}
