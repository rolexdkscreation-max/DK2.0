package com.dk.assistant

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक: "बाद में इसकी दिक्कत न हो", DKI-406DD9 / DKI-70D747 / DKI-D24C3A):
 * Live टूटे / अटके / setup पर अटके - यूज़र की बात न खोए, एक key और कई key दोनों पर.
 */
class LiveRecoverTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Before fun clean() { LiveRecover.Demote.reset() }

    // ---------------------------------------------------------------- ping

    @Test fun pingIsNotSelfInflicted_15sLikeCaption() {
        assertTrue(LiveRecover.PING_S >= 15)
        val net = src("LiveNet.kt")
        assertTrue(net.contains(".pingInterval(LiveRecover.PING_S, TimeUnit.SECONDS)"))
        assertFalse(net.contains(".pingInterval(5, TimeUnit.SECONDS)"))
        assertTrue(net.contains(".connectTimeout(4, TimeUnit.SECONDS)"))      // पुराना नियम वही
    }

    // ---------------------------------------------------------------- अटका session

    @Test fun zombie_onlyWhenServerTotallySilentAfterRealSpeech() {
        val end = 10_000L
        // 4s तक server से कुछ नहीं, शब्द थे = अटका
        assertTrue(LiveRecover.zombie(end + 4_000, end, 0L, end - 500, busy = false, speechMs = 800, words = true))
        // अभी 4s नहीं हुए
        assertFalse(LiveRecover.zombie(end + 3_900, end, 0L, end - 500, busy = false, speechMs = 800, words = true))
        // server ने बात के बाद कुछ भी भेजा (VAD / लिखावट) = ज़िंदा
        assertFalse(LiveRecover.zombie(end + 9_000, end, 0L, end + 300, busy = false, speechMs = 800, words = true))
        // उस बात पर event आ चुका
        assertFalse(LiveRecover.zombie(end + 9_000, end, end + 700, end - 500, busy = false, speechMs = 800, words = true))
        // DK बोल रहा / काम चल रहा / यूज़र बोल रहा
        assertFalse(LiveRecover.zombie(end + 9_000, end, 0L, end - 500, busy = true, speechMs = 800, words = true))
        // कोई बात नहीं
        assertFalse(LiveRecover.zombie(end + 9_000, 0L, 0L, 0L, busy = false, speechMs = 800, words = true))
    }

    @Test fun zombie_noWordsNeedsLongerSpeech_soKnocksDoNotSwap() {
        val end = 10_000L
        assertFalse(LiveRecover.zombie(end + 5_000, end, 0L, 0L, false, speechMs = 400, words = false))
        assertTrue(LiveRecover.zombie(end + 5_000, end, 0L, 0L, false, speechMs = LiveRecover.MIN_SPEECH_NO_WORDS_MS, words = false))
        assertFalse(LiveRecover.zombie(end + 5_000, end, 0L, 0L, false, speechMs = 200, words = true))
    }

    @Test fun swapLimit_twoPerMinute_noLoop() {
        val l = LiveRecover.SwapLimit()
        assertTrue(l.tryTake(0)); assertTrue(l.tryTake(1_000))
        assertFalse(l.tryTake(2_000))
        assertEquals(2, l.recent(2_000))
        assertTrue(l.tryTake(61_500))
    }

    // ---------------------------------------------------------------- आवाज़ दोबारा

    private fun pcm(v: Byte) = ByteArray(1280) { v }

    @Test fun utterance_keepsSpeechWithShortPauses_dropsLeadingSilence() {
        val u = LiveRecover.Utterance(40)
        u.add(pcm(0), real = false, now = 0)               // बात से पहले की चुप्पी नहीं
        u.add(pcm(1), real = true, now = 40)
        u.add(pcm(0), real = false, now = 80)              // बीच की चुप्पी साथ
        u.add(pcm(2), real = true, now = 1_000)
        assertEquals(80, u.speechMs())
        assertEquals(listOf<Byte>(1, 0, 2), u.snapshot(1_100).map { it[0] })
    }

    @Test fun utterance_newSpeechAfterLongGap_isNewTurn_andOldSnapshotExpires() {
        val u = LiveRecover.Utterance(40)
        u.add(pcm(1), true, 0)
        u.add(pcm(2), true, 4_000)                         // 2.5s से लंबी चुप्पी = नई बात
        assertEquals(listOf<Byte>(2), u.snapshot(4_100).map { it[0] })
        assertTrue(u.snapshot(4_000 + 20_001).isEmpty())   // बहुत पुरानी बात दोबारा नहीं
        u.clear(); assertEquals(0, u.speechMs()); assertTrue(u.snapshot(4_100).isEmpty())
    }

    @Test fun utterance_cappedAt15s() {
        val u = LiveRecover.Utterance(40, maxMs = 15_000)
        for (i in 0 until 1_000) u.add(pcm(1), true, i * 40L)
        assertEquals(15_000 / 40, u.snapshot(40_000).size)
    }

    // ---------------------------------------------------------------- setup पर अटका मॉडल

    @Test fun demote_afterTwoHangs_toBack_neverRemoved_resetOnSuccess() {
        val d = LiveRecover.Demote
        assertFalse(d.setupHung("gemini-3.8-live", 0))
        assertFalse(d.demoted("gemini-3.8-live", 1))
        assertTrue(d.setupHung("gemini-3.8-live", 10))
        assertTrue(d.demoted("gemini-3.8-live", 11))
        val o = d.order(listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"), 12) { it }
        assertEquals(listOf("gemini-3.1-flash-live-preview", "gemini-3.8-live"), o)
        // अकेला मॉडल = वही रहे (एक key + एक मॉडल वाला यूज़र बंद न हो)
        assertEquals(listOf("gemini-3.8-live"), d.order(listOf("gemini-3.8-live"), 12) { it })
        assertFalse(d.demoted("gemini-3.8-live", 10 + LiveRecover.Demote.DEMOTE_MS))
        d.ok("gemini-3.8-live"); assertFalse(d.demoted("gemini-3.8-live", 13))
    }

    // ---------------------------------------------------------------- जुड़ाव (source)

    @Test fun wiring_zombieCheckDoesNotNeedTextRescue_andReplaysAudioNotText() {
        val g = LiveSrc.all()
        val line = g.lines().first { it.contains("LiveRecover.zombie(") }
        assertFalse(line.contains("textRescue"))
        assertTrue(g.contains("replayAudio = utt.snapshot(now)"))
        assertTrue(g.contains("sendContext(ws); ready = true; flushPending(); sendReplay()"))
        assertEquals(2, Regex("flushPending\\(\\); sendReplay\\(\\)").findAll(g).count())
        val r = src("GeminiLiveRecover.kt")
        assertTrue(r.contains("audioStreamEnd"))
        assertFalse(r.contains("\"text\""))                 // बीच बातचीत का text नहीं (1011)
        assertTrue(g.contains("utt.add(pcm, !silent"))
    }

    @Test fun wiring_demoteInRace_andNetworkSwitch() {
        val race = src("GeminiLiveRace.kt")
        assertTrue(race.contains("LiveRecover.Demote.order(queue, now)"))
        assertTrue(race.contains("LiveRecover.Demote.setupHung(a.c.model, t)"))
        assertTrue(race.contains("LiveRecover.Demote.ok(a.c.model)"))
        assertTrue(src("NetFix.kt").contains("DkDns.switchedAt = System.currentTimeMillis()"))
        assertTrue(LiveSrc.all().contains("DkDns.switchedAt > upAt"))
    }

    // ---------------------------------------------------------------- Inspector: खुद संभला = गलती नहीं

    private val m = "gemini-3.8-live"

    @Test fun inspector_dropFixedWithinSeconds_isNotABug() {
        val log = """
10:00:01.000 Live: connection गलती [$m • key #1]: http=null SocketTimeoutException: sent ping but didn't receive pong within 5000ms
10:00:01.050 Live: connection टूटा (code=0, ping, 44s चला) - दोबारा जुड़ रहा हूँ #3 (बातचीत वहीं से)
10:00:01.900 Live: जुड़ गया [$m • key #1] 812 ms (पुरानी बातचीत जारी)
""".trim()
        assertTrue(I.analyze(I.parseLog(log)).none { it.rule.id == "live_conn_drop" })
    }

    @Test fun inspector_dropNotFixed_stillReported() {
        val log = """
10:00:01.050 Live: connection टूटा (code=1011, Deadline expired, 44s चला) - दोबारा जुड़ रहा हूँ #3 (बातचीत वहीं से)
10:00:30.000 Live: जुड़ गया [$m • key #1] 812 ms
""".trim()
        assertEquals(1, I.analyze(I.parseLog(log)).single { it.rule.id == "live_conn_drop" }.count)
    }

    @Test fun inspector_longOutageAlwaysReported_evenIfReconnected() {
        val log = """
10:00:31.000 Live: connection टूटा रहा 30s - यूज़र को रुकावट दिखी (#1)
10:00:31.010 Live: जुड़ गया [$m • key #1] 812 ms
""".trim()
        assertTrue(I.analyze(I.parseLog(log)).any { it.rule.id == "live_conn_drop" })
    }

    @Test fun inspector_stuckSessionRecovered_isNotABug_butUnrecoveredIs() {
        val ok = """
10:00:05.000 Live: बात के बाद 4.0s तक Gemini से कुछ नहीं आया (न लिखावट, न VAD) - session अटका; नया connection (बातचीत वहीं से) और आपकी वही आवाज़ दोबारा (#1)
10:00:06.500 Live: आपकी बात की आवाज़ (2.4s) नए connection पर दोबारा भेजी - text नहीं (#1)
10:00:07.300 Live: ${LiveRecover.OK_MARK} - session अटका के बाद 2.3s में server फिर जवाब दे रहा है
""".trim()
        assertTrue(I.analyze(I.parseLog(ok)).none { it.rule.id == "live_stall" })
        val bad = ok.lines().first()
        assertTrue(I.analyze(I.parseLog(bad)).any { it.rule.id == "live_stall" })
    }

    @Test fun inspector_markerMatchesRealLogLines() {
        val g = LiveSrc.all()
        assertTrue(g.contains("log(\"जुड़ गया [\$model"))
        assertTrue(g.contains("log(\"गर्म connection फिर से [\$model"))
        assertTrue(src("GeminiLiveRecover.kt").contains("LiveRecover.OK_MARK"))
        assertEquals(LiveRecover.RECOVER_MS, I.RECOVER_MS)
    }
}
