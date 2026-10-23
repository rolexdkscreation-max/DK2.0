package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** v3.46: debug log वाली समस्याओं की असली वजहें - हर एक की जाँच */
class LiveRootCauseTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private fun type(t: String) = LocalRouter.route(t, wake, "")?.action?.optString("type")

    // ---------------------------------------------------------------- 3) router: भराव वाले शब्द
    @Test fun router_fillerClauseNoLongerSendsToGemini() {
        assertEquals("home", type("चलो ठीक है, होम स्क्रीन पे आओ"))
        assertEquals("home", type("चलो ठीक है होम स्क्रीन पे आओ"))
        assertEquals("home", type("अच्छा डीके, होम स्क्रीन पर जाओ"))
        assertEquals("open_app", type("ठीक है यूट्यूब खोलो"))
        assertEquals("open_app", type("यूट्यूब खोलो, ठीक है"))
    }

    @Test fun router_incompleteQuestionStillGoesToGemini() {
        assertNull(type("अच्छा डीके ये बताओ"))
        assertNull(type("चलो ठीक है"))
    }

    @Test fun filler_onlyWholeFillerClauses() {
        assertTrue(Filler.isFiller("चलो ठीक है"))
        assertTrue(Filler.isFiller("अच्छा डीके"))
        assertFalse(Filler.isFiller("होम स्क्रीन पे आओ"))
        assertFalse(Filler.isFiller("बस करो"))                      // "बस" = रुको, भराव नहीं
        assertEquals("होम स्क्रीन पे आओ", Filler.stripLead("चलो ठीक है, होम स्क्रीन पे आओ"))
        assertEquals("ठीक है", Filler.stripLead("ठीक है"))          // सब भराव = जैसा था
    }

    @Test fun routeDedupe_sameUtteranceOnlyOnce() {
        val d = RouteDedupe(15_000)
        assertTrue(d.fresh("चलो ठीक है, होम स्क्रीन पे आओ", 0))
        assertFalse(d.fresh("चलो ठीक है होम स्क्रीन पे आओ।", 2_000))   // वही बात, बस विराम अलग
        assertTrue(d.fresh("यूट्यूब खोलो", 3_000))
        assertTrue(d.fresh("यूट्यूब खोलो", 20_000))                  // बहुत देर बाद = नई बार
        d.reset(); assertTrue(d.fresh("यूट्यूब खोलो", 20_500))        // जवाब शुरू होने पर नई बात
    }

    // ---------------------------------------------------------------- 1) बचाव का मौका खोता नहीं
    @Test fun rescue_deferredWhenBusy_notLost() {
        val w = ReplyWatchdog(timeoutMs = 1800, minSpeechMs = 250)
        w.onSpeechStart(); w.onEndOfSpeech(0, 900)
        assertTrue(w.due(1_800))
        // उस पल फ़ोन का काम चल रहा था -> पहले यहीं खत्म; अब फिर मौका
        assertTrue(w.defer(1_800, 700))
        assertFalse(w.due(2_000))
        assertTrue(w.due(2_500))
        // सर्वर ने सुन लिया = फिर नहीं
        w.defer(2_500, 700); w.onServerHeard(); assertFalse(w.due(10_000))
    }

    @Test fun rescue_unheardSpeechSurvivesFollowingClick() {
        val w = ReplyWatchdog(timeoutMs = 1800, minSpeechMs = 250)
        w.onSpeechStart(); w.onEndOfSpeech(0, 1200)              // असली बात, सर्वर ने नहीं सुनी
        w.onSpeechStart(); w.onEndOfSpeech(1_000, 100)           // तुरंत एक खटखट
        assertTrue("पहली बात खटखट से खोनी नहीं चाहिए", w.due(2_800))
    }

    @Test fun rescue_shortCommandAlsoCovered() {
        val w = ReplyWatchdog(timeoutMs = 1800, minSpeechMs = 250)
        w.onSpeechStart(); w.onEndOfSpeech(0, 320)               // "रुको" / "हाँ"
        assertTrue(w.due(1_800))
    }

    @Test fun rescue_deferHasLimit() {
        val w = ReplyWatchdog(timeoutMs = 1800, minSpeechMs = 250)
        w.onSpeechStart(); w.onEndOfSpeech(0, 900)
        var n = 0
        while (w.defer(n.toLong(), 10)) n++
        assertEquals(30, n)                                      // हमेशा के लिए नहीं अटकता
    }

    // ---------------------------------------------------------------- 6) शोर / लंबी बात
    @Test fun turnDetector_loudBackgroundLowersFactor() {
        val d = TurnDetector(loudNoiseFactor = 2.0)
        assertEquals(2.5, d.factorFor(300.0), 1e-9)
        assertEquals(2.0, d.factorFor(2000.0), 1e-9)
        assertTrue(d.factorFor(1000.0) < 2.5 && d.factorFor(1000.0) > 2.0)
        assertEquals(2.5, TurnDetector().factorFor(2000.0), 1e-9)   // पुराना default जैसा था
    }

    @Test fun turnDetector_speechOverMusicIsHeard() {
        // गाना ~1600 RMS पर; आप ~3600 पर बोलते हैं (पहले दरवाज़ा 4000 = आवाज़ कभी नहीं पहुँचती थी)
        // गाना धीरे-धीरे तेज़ हुआ (शोर सीखा गया) और ~1600 पर रुका
        val ramp = (0 until 1400).map { 200.0 + it } + List(200) { 1600.0 }
        val d = TurnDetector(loudNoiseFactor = 2.0); var t = 0L
        ramp.forEach { d.feed(it, t); t += 40 }
        assertFalse(d.inSpeech)
        var started = false
        repeat(10) { if (d.feed(3600.0, t) == TurnDetector.Event.SPEECH_START) started = true; t += 40 }
        assertTrue(started)
        val old = TurnDetector(); var t2 = 0L
        ramp.forEach { old.feed(it, t2); t2 += 40 }
        var oldStarted = false
        repeat(10) { if (old.feed(3600.0, t2) == TurnDetector.Event.SPEECH_START) oldStarted = true; t2 += 40 }
        assertFalse("पुराना तरीका यही आवाज़ नहीं पकड़ता था", oldStarted)
    }

    @Test fun turnDetector_musicAlreadyPlayingIsLearnedNotEndlessSpeech() {
        val d = TurnDetector(steadyResetMs = 6_000); var t = 0L
        repeat(10) { d.feed(150.0, t); t += 40 }
        repeat(200) { d.feed(1800.0 + (it % 4) * 40, t); t += 40 }   // 8s एक-सा गाना
        assertFalse("लगातार गाना हमेशा 'बोलना' नहीं रहना चाहिए", d.inSpeech)
        assertTrue(d.steadyResets >= 1)
        assertTrue(d.noise > 1000)
        // असली बोलना (शब्दों के बीच उतार-चढ़ाव) 8s तक भी नहीं कटता
        val s = TurnDetector(steadyResetMs = 6_000); var u = 0L
        repeat(10) { s.feed(150.0, u); u += 40 }
        repeat(200) { s.feed(if (it % 6 == 5) 500.0 else 3000.0, u); u += 40 }
        assertTrue(s.inSpeech)
        assertEquals(0, s.steadyResets)
    }

    @Test fun turnDetector_longSpeechToleratesLongerPause() {
        val d = TurnDetector(endSilenceMs = 550, longSpeechMs = 2_500, longEndSilenceMs = 800); var t = 0L
        repeat(10) { d.feed(100.0, t); t += 40 }
        repeat(80) { d.feed(3000.0, t); t += 40 }                // 3.2s बोलना
        var ended = false
        repeat(16) { if (d.feed(100.0, t) == TurnDetector.Event.SPEECH_END) ended = true; t += 40 }   // 640ms ठहराव
        assertFalse("लंबी बात के बीच 0.64s ठहराव पर बात नहीं कटनी चाहिए", ended)
        repeat(6) { if (d.feed(100.0, t) == TurnDetector.Event.SPEECH_END) ended = true; t += 40 }
        assertTrue(ended)
        // छोटी बात पर पहले जैसा 550ms
        val s = TurnDetector(endSilenceMs = 550, longSpeechMs = 2_500, longEndSilenceMs = 800); var u = 0L
        repeat(10) { s.feed(100.0, u); u += 40 }
        repeat(20) { s.feed(3000.0, u); u += 40 }
        var e2 = false
        repeat(15) { if (s.feed(100.0, u) == TurnDetector.Event.SPEECH_END) e2 = true; u += 40 }
        assertTrue(e2)
    }

    @Test fun turnEnd_continuationWordsHoldEnd() {
        assertTrue(TurnEnd.continues("अच्छा डीके ये बताओ"))
        assertTrue(TurnEnd.continues("मैं कल बाज़ार गया और"))
        assertTrue(TurnEnd.continues("मैंने उसे बोला कि"))
        assertFalse(TurnEnd.continues("होम स्क्रीन पे आओ"))
        assertFalse(TurnEnd.continues("आज मौसम कैसा है"))
        assertFalse(TurnEnd.continues(""))
    }

    // ---------------------------------------------------------------- 4) काम की पक्की जाँच
    @Test fun agentResult_failureIsNeverReportedAsFinished() {
        val r = LiveFast.agentResult(true, "फ्री लिमिट खत्म", failed = true)
        assertTrue(r.startsWith("FAILED"))
        assertFalse(r.startsWith("FINISHED"))
        assertTrue(LiveFast.lateNote("मैसेज डिलीट करो", "पुष्टि नहीं दिखी", failed = true).contains("FAILED"))
        assertTrue(LiveFast.agentResult(true, "WhatsApp खोल दिया").startsWith("FINISHED"))
    }

    @Test fun deleteCheck_needsScreenProof() {
        assertTrue(ActionCheck.isDeleteMessage("धीरज कुमार को भेजा आखिरी मैसेज डिलीट करो"))
        assertTrue(ActionCheck.isDeleteMessage("delete the last WhatsApp message"))
        assertFalse(ActionCheck.isDeleteMessage("यूट्यूब खोलो"))
        assertFalse(ActionCheck.isDeleteMessage("फोटो डिलीट करो"))
        assertTrue(ActionCheck.deleteConfirmed("Dheeraj Kumar\nYou deleted this message\n10:42"))
        assertTrue(ActionCheck.deleteConfirmed("आपने यह मैसेज डिलीट किया"))
        assertFalse(ActionCheck.deleteConfirmed("Dheeraj Kumar\nHi\n10:42 ✓✓"))
    }

    // ---------------------------------------------------------------- 5) OpenRouter 429
    @Test fun rateLimit_dailyDetected() {
        assertTrue(RateLimit.daily(AiException(429, "Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day")))
        assertTrue(RateLimit.accountWide(AiException(429, "Rate limit exceeded: free-models-per-day")))
        assertFalse(RateLimit.daily(AiException(429, "meta-llama/x:free is temporarily rate-limited upstream")))
        assertFalse(RateLimit.daily(AiException(500, "per-day")))
    }

    @Test fun rateLimit_resetAtUtcMidnight_andBackoffGrows() {
        val now = 1_792_700_000_000L
        val r = RateLimit.nextUtcMidnight(now)
        assertTrue(r > now && r - now <= 24 * 3600_000L)
        assertEquals(0L, r % (24 * 3600_000L))
        assertEquals(60_000L, RateLimit.backoffMs(1))
        assertEquals(120_000L, RateLimit.backoffMs(2))
        assertEquals(15 * 60_000L, RateLimit.backoffMs(9))
        assertEquals(90_000L, RateLimit.backoffMs(1, retryAfterSec = 90))
        assertEquals("सुबह 5:30 बजे", RateLimit.clock(0L, TimeZone.getTimeZone("Asia/Kolkata")))
    }

    @Test fun providerHealth_dailyLimitBlocksUntilTomorrow() {
        val id = "test_or_${System.nanoTime()}"
        assertTrue(ProviderHealth.usable(id))
        ProviderHealth.fail(id, AiException(429, "Rate limit exceeded: free-models-per-day"))
        assertFalse(ProviderHealth.usable(id))
        assertTrue(ProviderHealth.dailyOut(id))
        assertFalse(ProviderHealth.retryable(id))                 // बार-बार नहीं मारेंगे
        assertTrue(ProviderHealth.reason(id).contains("आज की फ्री लिमिट"))
        val id2 = "test_m_${System.nanoTime()}"
        ProviderHealth.fail(id2, AiException(429, "temporarily rate-limited upstream"))
        assertFalse(ProviderHealth.usable(id2))
        assertTrue(ProviderHealth.retryable(id2))                 // थोड़ी देर वाली - सबसे आख़िर में
    }
}
