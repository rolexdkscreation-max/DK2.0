package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.2 (मालिक): धीरे बोलूँ तब भी सुने, ग़लत सुना शब्द हो तो सही मतलब निकाले, तुरंत काम करे;
 * DK लाइव की 3 keys का fallback आवाज़ में भी; कॉल आए तो पूरी आवाज़ में बोले (limit खत्म हो तब भी).
 */
class SoftHearCallVoiceTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    // ------------------------------------------------------------------ ग़लत सुना शब्द -> सही मतलब
    @Test fun misheardOpenFixed() {
        assertEquals("youtube खोलो और गाना चलाओ", HearFix.fix("youtube फॉलो और गाना चलाओ"))
        assertEquals("व्हाट्सएप खोलो", HearFix.fix("व्हाट्सएप फोलो"))
        assertEquals("यूट्यूब खोलो", HearFix.fix("यूट्यूब खोलू"))
    }

    @Test fun realFollowUntouched() {
        assertEquals("instagram पर राहुल को फॉलो करो", HearFix.fix("instagram पर राहुल को फॉलो करो"))
        assertEquals("youtube फॉलो करो", HearFix.fix("youtube फॉलो करो"))
        assertEquals("मुझे फॉलो करो", HearFix.fix("मुझे फॉलो करो"))
    }

    @Test fun singerNamesFixed() {
        assertEquals("पवन सिंह सॉन्ग हकीकत", HearFix.fix("पबा सिंगा सॉन्ग हकीकत"))
        assertEquals("pawan singh ka song", HearFix.fix("pawan singh ka song"))   // सही English नाम नहीं बदलता
        assertEquals("खेसारी लाल यादव", HearFix.fix("केसरी लाल यादव"))
        assertEquals("पवनपुत्र हनुमान", HearFix.fix("पवनपुत्र हनुमान"))
    }

    @Test fun ownerSentenceRunsOnPhoneInstantly() {
        // मालिक की असली बात (caption ने जैसा लिखा) - अब Gemini का इंतज़ार नहीं, फ़ोन पर ही YouTube पर गाना
        val q = LocalRouter.route("DK YouTube फॉलो और पबा सिंगा सॉन्ग हकीकत सर्च करो और प्ले कर दो।", listOf("dk", "डीके"))
        assertTrue("फ़ोन पर ही होना चाहिए", q != null)
        assertEquals("youtube_play", q!!.action.optString("type"))
        assertTrue(q.action.optString("query"), q.action.optString("query").contains("पवन सिंह"))
    }

    // ------------------------------------------------------------------ धीमी आवाज़
    @Test fun softSpeechStartsAndIsNotCut() {
        fun detector() = TurnDetector(startFactor = LiveHear.START_FACTOR, startMin = LiveHear.START_MIN,
            loudNoiseFactor = LiveHear.LOUD_FACTOR, keepFactor = LiveHear.KEEP_FACTOR, keepMin = LiveHear.KEEP_MIN)
        // शांत कमरा (शोर ~100), मालिक धीरे बोले (~200 RMS) - पहले 220 से नीचे = Gemini को सिर्फ़ खामोशी
        val d = detector(); var t = 0L
        repeat(50) { d.feed(100.0, t); t += 40 }
        var started = false
        repeat(10) { if (d.feed(200.0, t) == TurnDetector.Event.SPEECH_START) started = true; t += 40 }
        assertTrue(started)
        // बोलते-बोलते आवाज़ और धीमी (160) - बात कटनी नहीं चाहिए
        var ended = false
        repeat(30) { if (d.feed(160.0, t) == TurnDetector.Event.SPEECH_END) ended = true; t += 40 }
        assertFalse(ended)
        // पुराना दरवाज़ा: यही धीमी आवाज़ अनसुनी
        val old = TurnDetector(startFactor = 2.0, startMin = 220.0, loudNoiseFactor = 1.7); var t2 = 0L
        repeat(50) { old.feed(100.0, t2); t2 += 40 }
        var oldStarted = false
        repeat(10) { if (old.feed(200.0, t2) == TurnDetector.Event.SPEECH_START) oldStarted = true; t2 += 40 }
        assertFalse(oldStarted)
        assertTrue(LiveHear.START_MIN < 220.0 && LiveHear.START_FACTOR < 2.0)
    }

    @Test fun softSpeechBoostedMore() {
        fun level(b: MicBoost): Double {
            val buf = ShortArray(640)
            var out = 0.0
            repeat(80) { i ->
                for (k in buf.indices) buf[k] = (if (i < 40) 80.0 else 200.0).let { a -> (a * kotlin.math.sin(k * 0.3) * 1.414).toInt().toShort() }
                b.apply(buf, buf.size)
                if (i >= 40) out = kotlin.math.sqrt(buf.sumOf { it.toDouble() * it } / buf.size)
            }
            return out
        }
        val old = level(MicBoost())
        val now = level(MicBoost(maxGain = LiveHear.BOOST_MAX, noiseCap = LiveHear.BOOST_NOISE_CAP))
        assertTrue("नया boost ($now) पुराने ($old) से तेज़", now > old * 1.3)
    }

    @Test fun liveUsesSofterGateAndBoost() {
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("keepMin = LiveHear.KEEP_MIN"))
        assertTrue(live.contains("MicBoost(maxGain = LiveHear.BOOST_MAX, noiseCap = LiveHear.BOOST_NOISE_CAP)"))
        assertTrue(src("BrainManager.kt").contains("HEARING:"))
    }

    // ------------------------------------------------------------------ 3 keys
    @Test fun liveSkipsKeyWithAllModelsBlocked() {
        assertTrue(LiveKeys.allBlocked(listOf("a", "b")) { true })
        assertFalse(LiveKeys.allBlocked(listOf("a", "b")) { it == "a" })
        assertFalse(LiveKeys.allBlocked(emptyList()) { true })
        assertTrue(src("GeminiLive.kt").contains("LiveKeys.allBlocked(models)"))
    }

    @Test fun ttsLimitIsPerKey() {
        assertTrue(GeminiTts.tag("key-one") != GeminiTts.tag("key-two"))
        assertEquals(8, GeminiTts.tag("x").length)
        assertEquals(0L, GeminiTts.allBlockedUntil(emptyList()))
    }

    @Test fun voiceAndCaptionUseAllThreeKeys() {
        val sp = src("Speaker.kt")
        assertTrue(sp.contains("Prefs.liveGeminiKeys(appCtx)"))
        assertTrue(sp.contains("GeminiTts.synthesizeAny(keys"))
        assertFalse(sp.contains("Prefs.ttsGeminiKey(appCtx)"))
        assertTrue(src("GeminiLive.kt").contains("LiveCaption(LiveKeys.order(listOf(capKey) + Prefs.liveGeminiKeys(svc))"))
        assertTrue(src("LiveCaption.kt").contains("बैकअप key"))
    }

    // ------------------------------------------------------------------ कॉल
    @Test fun callAlwaysSpokenLoud() {
        val acc = src("DKAccessibilityService.kt")
        assertTrue(acc.contains("speaker.speakCall(msg"))
        assertTrue(acc.contains("gemLive.announceCall(whoNote)"))          // TTS न बने = Live की अपनी आवाज़
        assertTrue(acc.contains("speaker.warmCallVoice()"))
        assertTrue(acc.contains("speaker.restoreCallVolume()"))
        val sp = src("Speaker.kt")
        assertTrue(sp.contains("fun fullCallVolume()") && sp.contains("audio.setStreamVolume(a, am, 0)"))
        assertTrue(CallVoice.GENERIC_ASK.contains("कॉल") && CallVoice.GENERIC_ASK.length <= 60)   // सहेजी जा सके
        assertFalse(VoicePolicy.PHONE_TTS_ALLOWED)                          // फ़ोन की आवाज़ अब भी नहीं
    }
}
