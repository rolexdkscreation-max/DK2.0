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

    // ---------------------------------------------------------------- नवंबर 2026: थामो और बदलो (मालिक: बात न रुके, पता न चले)
    @Test fun freezeStartsOnlyAtAQuietMomentWithFreshHandle() {
        val r = LiveSpare.FREEZE_RETRY_MS
        // 8 मिनट हुए, शांत, ताज़ा handle, 400ms शांत = थामो
        assertTrue(LiveSpare.startFreeze(true, true, false, false, true, true, true, LiveSpare.SETTLE_MS, r))
        assertFalse(LiveSpare.startFreeze(false, true, false, false, true, true, true, 5_000, r))   // अभी 8 मिनट नहीं, goAway नहीं
        assertFalse(LiveSpare.startFreeze(true, false, false, false, true, true, true, 5_000, r))   // कोई बोल रहा / काम चल रहा
        assertFalse(LiveSpare.startFreeze(true, true, true, false, true, true, true, 5_000, r))    // पहले से थामा
        assertFalse(LiveSpare.startFreeze(true, true, false, true, true, true, true, 5_000, r))    // ताज़ा standby तैयार = सीधा स्विच
        assertFalse(LiveSpare.startFreeze(true, true, false, false, false, true, true, 5_000, r))  // handle नहीं = नई बातचीत बनती
        assertFalse(LiveSpare.startFreeze(true, true, false, false, true, false, true, 5_000, r))  // keys नहीं
        assertFalse(LiveSpare.startFreeze(true, true, false, false, true, true, true, LiveSpare.SETTLE_MS - 1, r))  // अभी-अभी बोले
        assertFalse(LiveSpare.startFreeze(true, true, false, false, true, true, true, 5_000, r - 1))   // अभी नाकाम हुआ = चक्कर नहीं
        // जवाब के बाद handle नहीं आया = थोड़ा ज़्यादा रुको, फिर जो है वही
        assertFalse(LiveSpare.startFreeze(true, true, false, false, true, true, false, LiveSpare.SETTLE_MS, r))
        assertTrue(LiveSpare.startFreeze(true, true, false, false, true, true, false, LiveSpare.SETTLE_NO_HANDLE_MS, r))
    }

    @Test fun freezeSwitchesOrFallsBackWithoutLosingSpeech() {

        assertEquals(LiveSpare.Freeze.SWITCH, LiveSpare.freezeStep(spareReady = true, handleSame = true, preparing = false, frozenMs = 900, goAway = false, msLeft = 0))
        assertEquals(LiveSpare.Freeze.WAIT, LiveSpare.freezeStep(false, true, true, 900, false, 0))
        assertEquals(LiveSpare.Freeze.RESUME_OLD, LiveSpare.freezeStep(false, true, true, LiveSpare.FREEZE_MAX_MS, false, 0))   // समय पर नहीं बना
        assertEquals(LiveSpare.Freeze.RESUME_OLD, LiveSpare.freezeStep(false, true, false, 300, false, 0))                      // दौड़ नाकाम
        assertEquals(LiveSpare.Freeze.RESUME_OLD, LiveSpare.freezeStep(true, false, false, 300, false, 0))                      // बीच में handle बदला
        // goAway: पुराना जा रहा = पुराने पर लौटना नहीं, सामान्य नया connection (जमा आवाज़ उस पर)
        assertEquals(LiveSpare.Freeze.WAIT, LiveSpare.freezeStep(false, true, true, 300, true, 5_000))
        assertEquals(LiveSpare.Freeze.BREAK, LiveSpare.freezeStep(false, true, true, 300, true, LiveSpare.GOAWAY_MARGIN_MS))
        assertEquals(LiveSpare.Freeze.BREAK, LiveSpare.freezeStep(false, true, false, 300, true, 5_000))
        assertEquals(LiveSpare.Freeze.SWITCH, LiveSpare.freezeStep(true, true, true, 300, true, 100))
        // जमा आवाज़ थामने के पूरे समय से ज़्यादा (कोई शब्द न गिरे)
        assertTrue(LiveSpare.FREEZE_PENDING_MS > LiveSpare.FREEZE_MAX_MS)
        // 8 मिनट तैयारी (बिना थामे), 9 मिनट से थामना - 10 मिनट से पहले कई मौके
        assertTrue(LiveSpare.PREPARE_AT_MS < LiveSpare.FREEZE_AT_MS)
        assertTrue(LiveSpare.FREEZE_AT_MS + LiveSpare.FREEZE_RETRY_MS * 2 < 10 * 60_000L - LiveSpare.FREEZE_MAX_MS)
        assertFalse(LiveSpare.freezeDue(goAway = false, ageMs = LiveSpare.PREPARE_AT_MS))
        assertTrue(LiveSpare.freezeDue(goAway = false, ageMs = LiveSpare.FREEZE_AT_MS))
        assertTrue(LiveSpare.freezeDue(goAway = true, ageMs = 1_000))
    }

    @Test fun goAwayWaitsForQuietHandleAndFreshness() {
        assertTrue(LiveSpare.goAwaySettling(true, LiveSpare.FREEZE_MAX_MS + LiveSpare.GOAWAY_MARGIN_MS + 1))
        assertFalse(LiveSpare.goAwaySettling(true, LiveSpare.FREEZE_MAX_MS))
        assertFalse(LiveSpare.goAwaySettling(false, 60_000))
        assertTrue(LiveSpare.handleFresh(handleAt = 0, lastReplyDoneAt = 0))
        assertTrue(LiveSpare.handleFresh(handleAt = 1_200, lastReplyDoneAt = 1_000))
        assertFalse(LiveSpare.handleFresh(handleAt = 900, lastReplyDoneAt = 1_000))
    }

    @Test fun freezeWiredIntoLiveLoop() {
        val g = LiveSrc.all()
        assertTrue(g.contains("if (freezeTick(nowL)) { goAwayDrop = true; break }"))
        assertTrue(g.contains("beginFreeze(if (goAway) \"goAway\" else"))
        assertTrue(g.contains("LiveSpare.startFreeze(LiveSpare.freezeDue(goAway, nowL - upAt)"))
        assertTrue(g.contains("synchronized(sendLock) { ready = false }"))                     // थामे = पुराने पर कुछ नहीं
        assertTrue(g.contains("val capMs = if (rotFreezeAt > 0L) LiveSpare.FREEZE_PENDING_MS else 3000L"))
        assertTrue(g.contains("synchronized(sendLock) { ready = true; flushPending() }"))      // पुराने पर लौटे = जमा आवाज़ वहीं
        assertTrue(g.contains("sendFrozenEnd()          // नवंबर 2026 (थामो और बदलो)"))          // flushPending के अंत में (नए/पुराने दोनों पर)
        assertTrue(g.contains("it.optString(\"newHandle\").isNotBlank()) handleAt = System.currentTimeMillis()"))
        assertTrue(g.contains("if (sc.optBoolean(\"turnComplete\")) lastReplyDoneAt = System.currentTimeMillis()"))
        assertTrue(g.contains("prepareSpare(\"\$why (थामा)\", idle = true, force = true)"))
    }
}

