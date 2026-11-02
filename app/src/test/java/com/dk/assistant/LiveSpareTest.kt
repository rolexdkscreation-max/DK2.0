package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026 (मालिक): Gemini Live कटने से पहले ही दूसरा connection तैयार - यूज़र को पता न चले */
class LiveSpareTest {
    @Test fun spareIsPreparedBeforeServerCuts() {
        assertFalse(LiveSpare.due(goAway = false, ageMs = 60_000))
        assertTrue(LiveSpare.due(goAway = true, ageMs = 1_000))                       // goAway = तुरंत
        assertTrue(LiveSpare.due(goAway = false, ageMs = LiveSpare.PREPARE_AT_MS))    // goAway से पहले ही
        assertTrue(LiveSpare.PREPARE_AT_MS < 10 * 60_000L)                             // Gemini का connection ~10 मिनट
    }

    @Test fun prepareOnlyWhenItCanResumeSameConversation() {
        val ok = LiveSpare.SPARE_RACE_MS
        assertTrue(LiveSpare.shouldPrepare(false, true, true, false, false, idle = false, sinceLastTryMs = ok))
        assertFalse(LiveSpare.shouldPrepare(true, true, true, false, false, true, ok))      // ✕ से बंद
        assertFalse(LiveSpare.shouldPrepare(false, false, true, false, false, true, ok))    // handle नहीं = नई बातचीत बनती
        assertFalse(LiveSpare.shouldPrepare(false, true, false, false, false, true, ok))    // keys नहीं
        assertFalse(LiveSpare.shouldPrepare(false, true, true, true, false, true, ok))      // ताज़ा standby पहले से
        assertFalse(LiveSpare.shouldPrepare(false, true, true, false, false, true, ok - 1)) // पिछली तैयारी चल रही
        // पुराने handle वाला standby: DK बोल रहा हो तो नहीं बदलो, चुप होते ही ताज़ा
        assertFalse(LiveSpare.shouldPrepare(false, true, true, false, true, idle = false, sinceLastTryMs = ok))
        assertTrue(LiveSpare.shouldPrepare(false, true, true, false, true, idle = true, sinceLastTryMs = ok))
    }

    @Test fun goAwayWaitsForSpareInsteadOfGap() {
        assertTrue(LiveSpare.waitForSpare(spareReady = false, sinceTryMs = 500, msLeft = 5_000))
        assertFalse(LiveSpare.waitForSpare(spareReady = true, sinceTryMs = 500, msLeft = 5_000))     // तैयार = अभी स्विच
        assertFalse(LiveSpare.waitForSpare(false, 500, LiveSpare.GOAWAY_MARGIN_MS))                   // समय खत्म = सामान्य तरीका
        assertFalse(LiveSpare.waitForSpare(false, LiveSpare.SPARE_RACE_MS, 5_000))                    // तैयारी नाकाम
        assertEquals(10_000L, LiveSpare.goAwayLeft(10_000))
        assertEquals(LiveSpare.GOAWAY_DEFAULT_LEFT_MS, LiveSpare.goAwayLeft(-1))
    }

    @Test fun rotateOnlyWhenIdleAndSpareReady() {
        val old = LiveSpare.PREPARE_AT_MS
        assertTrue(LiveSpare.rotateNow(old, spareReady = true, idle = true))
        assertFalse(LiveSpare.rotateNow(old, spareReady = true, idle = false))      // DK बोल रहा = बीच में नहीं
        assertFalse(LiveSpare.rotateNow(old, spareReady = false, idle = true))
        assertFalse(LiveSpare.rotateNow(old - 1, spareReady = true, idle = true))
        assertEquals(0L, LiveSpare.waitAfterSwitch(seamless = true, backoff = 250))
        assertEquals(250L, LiveSpare.waitAfterSwitch(seamless = false, backoff = 250))
    }

    @Test fun wiredIntoLiveLoop() {
        val g = LiveSrc.all()
        assertTrue(g.contains("prepareSpare(\"goAway\", idle = "))
        assertTrue(g.contains("if (LiveSpare.due(goAway, nowL - upAt)) prepareSpare("))
        assertTrue(g.contains("if (LiveSpare.waitForSpare(spareReady(), nowL - spareTriedAt, goAwayDeadline - nowL)) { delay(100); continue }"))
        assertTrue(g.contains("if (!goAway && LiveSpare.rotateNow(nowL - upAt, spareReady(), idleNow))"))
        assertTrue(g.contains("LiveSpare.waitAfterSwitch(seamless, wait)"))
        // standby वही बातचीत हो (resume), नई बातचीत वाला standby नहीं रखते
        assertTrue(g.contains("if (spareWanted && !a.resume)"))
        // पुराना नियम: सिर्फ़ DK बोलते समय वाला return हटा
        assertFalse(g.contains("if (!busy || stopped || parked != null || handle == null || curKeys.isEmpty()) return"))
    }
}
