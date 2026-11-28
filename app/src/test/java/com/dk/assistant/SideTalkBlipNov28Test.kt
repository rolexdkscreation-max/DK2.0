package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 28 नवंबर 2026 (मालिक का log): असली बात (2.5 s) के ठीक बाद 200-320 ms की खटखट -> मिलान 0.03 / -0.05 -> "कोई और" ->
 * पिछली असली बात का पूरा जवाब (510-549 टुकड़े) नहीं बजा, DK चुप/अटका.
 */
class SideTalkBlipNov28Test {

    @Test fun shortSpeechNeverOther() {
        val th = 0.33f
        assertEquals(SideTalk.Who.UNKNOWN, SideTalk.classify(0.03f, th, 320))
        assertEquals(SideTalk.Who.UNKNOWN, SideTalk.classify(-0.05f, th, SideTalk.MIN_OTHER_MS - 1))
        assertEquals(SideTalk.Who.OTHER, SideTalk.classify(0.03f, th, SideTalk.MIN_OTHER_MS))
        assertEquals(SideTalk.Who.BOSS, SideTalk.classify(0.6f, th, 400))
    }

    @Test fun blipRestoresBossVerdict_replyNotMuted() {
        val s = SideTalk.State()
        // बॉस की असली बात
        val q = s.begin(1_000); s.voice(SideTalk.Who.BOSS, 3_500, q)
        // 300 ms की खटखट - नई "बात" शुरू हुई, फिर छोटी निकली
        val b = s.begin(5_000); s.blip(b)
        assertEquals(SideTalk.Who.BOSS, s.current(5_600))
        // पिछली बात का जवाब आया (DK 60 s से नहीं बोली) - रुकना नहीं चाहिए
        assertNull(s.muteReason(6_000, "हाँ तो क्या तुमको समझ नहीं आ रहा", dkSpokeAgoMs = 60_000))
    }

    @Test fun staleBlipIgnored() {
        val s = SideTalk.State()
        val q = s.begin(1_000)
        s.begin(2_000)
        s.blip(q)   // पुरानी बात का देर से आया नतीजा - कुछ न बदले
        assertEquals(SideTalk.Who.UNKNOWN, s.current(2_100))
    }

    @Test fun noWordsNeverMuted() {
        assertFalse(SideTalk.hasWords("<noise>"))
        assertFalse(SideTalk.hasWords("  <noise> . "))
        assertTrue(SideTalk.hasWords("भैया चाय पियोगे"))
        val s = SideTalk.State()
        val q = s.begin(1_000); s.voice(SideTalk.Who.BOSS, 1_500, q)
        val o = s.begin(2_000); s.voice(SideTalk.Who.OTHER, 3_500, o)
        assertNull(s.muteReason(3_600, "<noise>"))
        // असली शब्द हों तो पुराना नियम वैसा ही
        assertEquals("कोई और आपसे बात कर रहा है", s.muteReason(3_600, "भैया चाय पियोगे"))
    }

    @Test fun blipNeverBecomesOther() {
        val s = SideTalk.State()
        val q = s.begin(1_000); s.voice(SideTalk.Who.BOSS, 1_500, q)
        val o = s.begin(2_000); s.voice(SideTalk.Who.OTHER, 3_500, o)
        // बॉस का छोटा "हाँ" (खटखट जितना छोटा) - "कोई और" वापस नहीं आना चाहिए
        val b = s.begin(4_000); s.blip(b)
        assertEquals(SideTalk.Who.UNKNOWN, s.current(4_500))
    }

    @Test fun ttsGiveUpStreak() {
        var st = 0
        st = SplitRescue.nextStreak(st, true); st = SplitRescue.nextStreak(st, true)
        assertFalse(SplitRescue.giveUp(st))
        st = SplitRescue.nextStreak(st, true)
        assertTrue(SplitRescue.giveUp(st))
        assertEquals(0, SplitRescue.nextStreak(st, false))
        assertTrue(SplitRescue.limitHit("Gemini TTS: Gemini TTS की लिमिट खत्म (सब keys / मॉडल) - थोड़ी देर बाद"))
        assertTrue(SplitRescue.limitHit("HTTP 429 RESOURCE_EXHAUSTED"))
        assertFalse(SplitRescue.limitHit("Gemini TTS जवाब नहीं दे रहा (समय सीमा खत्म)"))
    }

    @Test fun wiredInLive() {
        val dir = java.io.File("src/main/java/com/dk/assistant").let { if (it.exists()) it else java.io.File("app/src/main/java/com/dk/assistant") }
        val side = java.io.File(dir, "GeminiLiveSideTalk.kt").readText()
        assertTrue(side.contains("speechMs: Int = turnDet.lastSpeechMs"))
        assertTrue(side.contains("SideTalk.global.blip(seq)"))
        val rescue = java.io.File(dir, "GeminiLiveRescue.kt").readText()
        assertTrue(rescue.contains("SplitRescue.giveUp(rescueStreak)"))
        assertTrue(java.io.File(dir, "GeminiLive.kt").readText().contains("if (SplitRescue.limitHit(why)) splitGiveUp("))
    }

    /** 28 नवंबर 2026: भावना वाला "अच्छा हो गया" काम का दावा नहीं; असली दावा अब भी पकड़ा जाए */
    @Test fun moodIsNotDoneClaim() {
        assertFalse(HonestGuard.claimsDone("मेरा दिन तो बहुत अच्छा बीता, boss, आपसे बात करके तो और भी अच्छा हो गया"))
        assertFalse(HonestGuard.claimsDone("मूड ठीक हो गया बॉस"))
        assertTrue(HonestGuard.claimsDone("हो गया बॉस"))
        assertTrue(HonestGuard.claimsDone("मूड अच्छा हो गया, और मैसेज भी भेज दिया।"))
    }
}
