package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक: DK SMART VOICE ACTIVITY & END-OF-TURN DETECTION)।
 * छोटे ठहराव पर बात न कटे, पूरा वाक्य तुरंत जाए, Hinglish समझे, शोर में बात खत्म पहचाने,
 * और आपके आगे बोलते समय पिछले टुकड़े का बासी जवाब न बजे।
 */
class SmartTurnTest {
    // ------------------------------------------------------------------ Hinglish / English अधूरा-पूरा
    @Test fun continues_romanHinglishAndEnglish() {
        assertTrue(TurnEnd.continues("kal meeting hai to"))
        assertTrue(TurnEnd.continues("mujhe ye batao ki"))
        assertTrue(TurnEnd.continues("Google Maps pe search karo aur"))
        assertTrue(TurnEnd.continues("Set an alarm for"))
        assertTrue(TurnEnd.continues("Tell me something about"))
        assertTrue(TurnEnd.continues("I want to"))
        assertTrue(TurnEnd.continues("matlab"))
        assertTrue(TurnEnd.continues("umm"))
        assertTrue(TurnEnd.continues("मेरे फ़ोन की"))
        assertTrue(TurnEnd.continues("दिल्ली से"))
        assertTrue(TurnEnd.continues("अच्छा तो अम्म"))
        // पुराने जैसे
        assertTrue(TurnEnd.continues("मैं कल बाज़ार गया और"))
        assertFalse(TurnEnd.continues("होम स्क्रीन पे आओ"))
        assertFalse(TurnEnd.continues("आज मौसम कैसा है"))
        assertFalse(TurnEnd.continues("Torch on karo"))
        assertFalse(TurnEnd.continues("What is the weather today?"))
        assertFalse(TurnEnd.continues(""))
    }

    @Test fun complete_romanHinglish() {
        assertTrue(TurnEnd.complete("DK mera WhatsApp open karo"))
        assertTrue(TurnEnd.complete("phir se batao"))
        assertTrue(TurnEnd.complete("reminder set karna hai"))
        assertTrue(TurnEnd.complete("ye kaise hoga"))
        assertTrue(TurnEnd.earlyOk("torch on karo"))
        assertFalse(TurnEnd.complete("kal meeting hai to"))
        assertFalse(TurnEnd.complete("rahul ko"))
        // "Can you…" - दो शब्द का सवाल-शुरू पूरा नहीं (पहले "you" पर 0.36s में कटता)
        assertFalse(TurnEnd.complete("Can you"))
        assertFalse(TurnEnd.complete("Tell me"))
        assertTrue(TurnEnd.complete("Can you translate this?"))
        // लंबा Roman हुक्म जल्दी नहीं (देवनागरी जैसा)
        assertFalse(TurnEnd.earlyOk("Google Maps pe Sagar Nursery search karo"))
        assertTrue(TurnEnd.complete("Google Maps pe Sagar Nursery search karo"))
    }

    // ------------------------------------------------------------------ adaptive चुप्पी
    @Test fun adaptiveEnd_completeFast_continuesWaits_staleCaptionWaits() {
        val v = 10_000L
        // पूरा वाक्य, caption आवाज़ के बाद आया = 0.36s
        assertEquals(TurnEnd.EARLY_END_MS, TurnEnd.adaptiveEndMs("आज मौसम कैसा है", v + 300, v, 1_500, 0, true, 500, v + 400))
        // "और / to" = सोचने का समय
        assertEquals(TurnEnd.CONT_END_MS, TurnEnd.adaptiveEndMs("kal meeting hai to", v + 300, v, 1_500, 0, true, 500, v + 400))
        // caption अभी आख़िरी आवाज़ तक नहीं पहुँचा = रुको
        assertEquals(TurnEnd.CAPTION_WAIT_MS, TurnEnd.adaptiveEndMs("मैं कल बाज़ार गया", v - 200, v, 1_500, 0, true, 500, v + 300))
        // caption पहुँचा, स्थिर, साधारण = 0.5s (पहले जैसा तेज़)
        assertEquals(500, TurnEnd.adaptiveEndMs("open youtube and play old songs", v + 100, v, 1_500, 0, true, 500, v + 400))
        // caption बंद = पुराना तरीका
        assertEquals(500, TurnEnd.adaptiveEndMs("", 0, v, 1_500, 0, false, 500, v + 300))
        assertEquals(TurnEnd.LONG_END_SILENCE_MS, TurnEnd.adaptiveEndMs("", 0, v, 3_000, 0, false, 500, v + 300))
        // लंबी बात, पर साफ़ पूरी = 0.8 नहीं, 0.5
        assertEquals(500, TurnEnd.adaptiveEndMs("मैप में सागर नर्सरी सर्च करके वहाँ का रास्ता बताओ", v + 300, v, 3_000, 0, true, 500, v + 400))
    }

    @Test fun adaptiveEnd_learnsSlowSpeakerPauses_capped() {
        val v = 10_000L
        // आप बीच में ~520ms रुकते हैं = साधारण "खत्म" 0.5 नहीं, ~0.71s
        assertEquals((520 * 1.25 + 60).toInt(), TurnEnd.adaptiveEndMs("open youtube and play old songs", v + 100, v, 1_500, 520, true, 500, v + 400))
        // पर server के बचाव से ज़्यादा कभी नहीं
        assertEquals(TurnEnd.MAX_NEUTRAL_END_MS, TurnEnd.adaptiveEndMs("open youtube and play old songs", v + 100, v, 1_500, 1_400, true, 500, v + 400))
        assertTrue(TurnEnd.CONT_END_MS <= GeminiLive.SERVER_SILENCE_MS)
    }

    @Test fun noiseTail_endsOnlyWhenNoTalkAndNoNewWords() {
        val talk = 10_000L
        assertTrue(TurnEnd.noiseTail(talk + 1_100, talk, talk + 300, 500, true))
        assertFalse(TurnEnd.noiseTail(talk + 900, talk, talk + 300, 500, true))      // अभी 1s नहीं
        assertFalse(TurnEnd.noiseTail(talk + 1_100, talk, talk + 800, 500, true))    // caption में अभी नए शब्द आए (धीमे बोल रहे)
        assertFalse(TurnEnd.noiseTail(talk + 1_100, talk, talk + 300, 1_000, true))  // अधूरा वाक्य - उसका इंतज़ार बड़ा
        assertFalse(TurnEnd.noiseTail(talk + 1_100, talk, 0, 500, true))             // caption नहीं = पुराना तरीका
        assertFalse(TurnEnd.noiseTail(talk + 1_100, talk, talk + 300, 500, false))
    }

    // ------------------------------------------------------------------ TurnDetector
    private fun det() = TurnDetector(40, endSilenceMs = 500, startFactor = 1.7, startMin = 150.0, loudNoiseFactor = 1.5,
        longSpeechMs = TurnEnd.LONG_SPEECH_MS, longEndSilenceMs = TurnEnd.LONG_END_SILENCE_MS, steadyResetMs = 6_000,
        keepFactor = 1.4, keepMin = 140.0, tailFactor = 0.12)

    @Test fun detector_endNeedOverride_holdsLongerThanFixed500() {
        val d = det(); var t = 0L
        repeat(10) { d.feed(80.0, t); t += 40 }
        repeat(20) { d.feed(2_500.0, t); t += 40 }
        assertTrue(d.inSpeech)
        d.endNeedMs = 800
        val stop = t; var ended = -1L
        repeat(30) { if (d.feed(80.0, t) == TurnDetector.Event.SPEECH_END && ended < 0) ended = t; t += 40 }
        assertTrue("800ms से पहले खत्म नहीं: ${ended - stop}", ended >= 0 && ended - stop in 740..840)
        // 0 = पुराना तरीका (500)
        val d2 = det(); var t2 = 0L; var e2 = -1L
        repeat(10) { d2.feed(80.0, t2); t2 += 40 }
        repeat(20) { d2.feed(2_500.0, t2); t2 += 40 }
        val speechStop = t2
        repeat(30) { if (d2.feed(80.0, t2) == TurnDetector.Event.SPEECH_END && e2 < 0) e2 = t2; t2 += 40 }
        assertTrue(e2 - speechStop in 440..520)
    }

    @Test fun detector_learnsResumedPauses_andTalkLevel() {
        val d = det(); var t = 0L
        repeat(10) { d.feed(80.0, t); t += 40 }
        repeat(20) { d.feed(2_500.0, t); t += 40 }
        repeat(10) { d.feed(80.0, t); t += 40 }       // 400ms ठहराव
        repeat(20) { d.feed(2_500.0, t); t += 40 }    // फिर बोले
        assertEquals(400, d.typicalPauseMs)
        val talk = d.lastTalkAt
        // पंखे का झोंका (बोलने के स्तर का ~20%) = "आवाज़" पर "बोलना" नहीं
        d.feed(500.0, t)
        assertEquals(talk, d.lastTalkAt)
        assertEquals(t, d.lastVoiceAt)
    }

    // ------------------------------------------------------------------ बासी जवाब न बजे
    @Test fun startHold_interruptedClears_neverPlays() {
        val h = ReplyStartHold()
        assertFalse(h.offer(ByteArray(10)))            // रोक नहीं = सीधे कतार
        h.begin(1_000)
        assertTrue(h.offer(ByteArray(4_800)))
        assertEquals(ReplyStartHold.Decision.WAIT, h.check(1_500))   // आप अभी बोल रहे
        h.clear()                                      // server: interrupted
        assertFalse(h.active)
        assertEquals(0, h.heldBytes())
        assertFalse(h.offer(ByteArray(10)))
    }

    @Test fun startHold_completeAndNewWords_drops_noiseReleases() {
        val h = ReplyStartHold(graceMs = 600)
        h.begin(1_000); h.offer(ByteArray(4_800)); h.turnComplete()
        h.userEnded(2_000, words = true)
        assertEquals(ReplyStartHold.Decision.WAIT, h.check(2_300))
        assertEquals(ReplyStartHold.Decision.DROP, h.check(2_700))
        assertEquals(4_800, h.drop()); assertFalse(h.active)

        val n = ReplyStartHold(graceMs = 600)
        n.begin(1_000); n.offer(byteArrayOf(1)); n.offer(byteArrayOf(2)); n.turnComplete()
        n.userEnded(1_300, words = false)              // खाँसी/शोर - शब्द नहीं
        assertEquals(ReplyStartHold.Decision.RELEASE, n.check(1_950))
        val out = n.release()
        assertEquals(listOf<Byte>(1, 2), out.map { it[0] })   // क्रम वही
        assertFalse(n.active)
    }

    @Test fun startHold_notComplete_releasesAfterGrace_longTalkDrops_memoryCapped() {
        val h = ReplyStartHold(graceMs = 600)
        h.begin(0); h.offer(ByteArray(100)); h.userEnded(500, words = true)
        assertEquals(ReplyStartHold.Decision.RELEASE, h.check(1_200))   // server ने नहीं रोका, जवाब अधूरा = वही बजे
        val l = ReplyStartHold(maxHoldMs = 15_000)
        l.begin(0); assertEquals(ReplyStartHold.Decision.WAIT, l.check(14_000))
        assertEquals(ReplyStartHold.Decision.DROP, l.check(15_100))
        val m = ReplyStartHold(maxBytes = 1_000)
        m.begin(0); repeat(5) { assertTrue(m.offer(ByteArray(400))) }
        assertTrue(m.heldBytes() <= 1_000)
    }

    // ------------------------------------------------------------------ देरी: median / p95
    @Test fun latencyBoard_percentilesAndStages() {
        LatencyBoard.clear()
        assertEquals(-1, LatencyBoard.pct(emptyList(), 0.5))
        val xs = (1..100).map { it.toLong() }
        assertEquals(50, LatencyBoard.pct(xs, 0.5))
        assertEquals(95, LatencyBoard.pct(xs, 0.95))
        val t = TurnTimeline(); t.begin(1_000, 1_400, "test"); t.event(1_900); t.audio(2_100); t.play(2_200); t.done(4_000)
        LatencyBoard.timeline(t)
        LatencyBoard.count("x")
        val s = LatencyBoard.stageText()
        assertTrue(s, s.contains("turn भेजा (endpoint): median 400 ms"))
        assertTrue(s, s.contains("पहली आवाज़ बजी: median 1200 ms"))
        assertTrue(s, s.contains("turn भेजा → पहली आवाज़ बजी: median 800 ms"))
        assertTrue(s, s.contains("x: 1"))
        LatencyBoard.clear()
        assertEquals("", LatencyBoard.stageText())
    }

    // ------------------------------------------------------------------ wiring
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun wiring_hybridVad_adaptiveAndStartHold() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("turnDet.endNeedMs = if (turnDet.inSpeech && !guardHeld)"))
        assertTrue(mic.contains("TurnEnd.adaptiveEndMs("))
        assertTrue(mic.contains("TurnEnd.noiseTail("))
        assertTrue(mic.contains("capHeard && TurnEnd.earlyOk(cap) && turnDet.forceEnd()"))
        assertTrue(mic.contains("startHold.begin(now)") && mic.contains("startHold.userEnded(") && mic.contains("pollStartHold(now)"))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("if (startHold.offer(pcm)) return"))
        assertTrue(live.contains("|| startHold.active) return"))
        assertTrue(src("GeminiLivePlayer.kt").contains("startHold.clear()"))
        val server = src("GeminiLiveServer.kt")
        assertTrue(server.contains("startHold.turnComplete()"))
        assertTrue(server.contains("LatencyBoard.timeline(timeline)"))
        // server VAD वही (Hybrid): चालू, START_OF_ACTIVITY_INTERRUPTS, कोई दूसरा client-side turn system नहीं
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("START_OF_ACTIVITY_INTERRUPTS"))
        assertFalse(setup.contains("\"disabled\", true"))
        assertFalse(LiveSrc.all().contains("activityStart"))
    }
}
