package com.dk.assistant

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक की Inspector रिपोर्ट, 02 Nov 09:00-09:12):
 * DKI-406DD9 / F2A775 / 7A418A / E31268 / CCB3F8 / 063250 / 4144DC.
 * जड़ 1: Live बार-बार Google की तरफ़ खराब 2.5 native-audio मॉडलों पर लौटता था (1011).
 * जड़ 2: Inspector "Live हाल" की कुल गिनती को हर जाँच में नई घटना मानता था - हटाया बग झूठा "फिर आया", दोहरी रिपोर्ट.
 */
class LiveInspectorFix1102Test {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    private val m12 = "gemini-2.5-flash-native-audio-preview-12-2025"
    private val m09 = "gemini-2.5-flash-native-audio-preview-09-2025"
    private val mLatest = "gemini-2.5-flash-native-audio-latest"

    // ---------------------------------------------------------------- Live: 1011 वाले मॉडल

    @Test fun native25DemotedBehind38Live_butNeverRemoved() {
        val o = LiveStay.demoteKnown1011(listOf(m12, mLatest, "gemini-3.8-live", m09, "gemini-3.1-flash-live-preview"))
        assertEquals(listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", m12, mLatest, m09), o)
        // सिर्फ़ 2.5 बचे = वही (DK बंद नहीं होता)
        assertEquals(listOf(m12, m09), LiveStay.demoteKnown1011(listOf(m12, m09)))
        assertTrue(LiveStay.known1011(m09) && LiveStay.known1011(mLatest))
        assertFalse(LiveStay.known1011("gemini-3.8-live"))
    }

    @Test fun broken1011StaysBehindFor6Hours_rememberedAcrossRestart() {
        val b = LiveStay.Broken1011()
        b.mark(m12, now = 0)
        assertTrue(b.isBroken(m12, 2 * 60_000L + 1))                 // पहले 2 मिनट बाद लौट आता था
        assertTrue(b.isBroken(m12, LiveStay.BROKEN_1011_MS - 1))
        assertFalse(b.isBroken(m12, LiveStay.BROKEN_1011_MS))
        val back = LiveStay.Broken1011.decode(b.encode(now = 1))
        assertTrue(back.isBroken(m12, 1000)); assertFalse(back.isBroken(m09, 1000))
        assertFalse(LiveStay.Broken1011.decode("garbage;=;x=").isBroken("x", 0))
        val o = LiveStay.safeOrder(listOf(m12, "gemini-3.8-live", m09)) { back.isBroken(it, 1000) }
        assertEquals(listOf("gemini-3.8-live", m09, m12), o)
    }

    @Test fun poisonedResumeStartsFreshConversation() {
        assertTrue(LiveStay.resumePoisoned(1011, 3_000, resumed = true))
        assertFalse(LiveStay.resumePoisoned(1011, 3_000, resumed = false))
        assertFalse(LiveStay.resumePoisoned(1011, 60_000, resumed = true))
        assertFalse(LiveStay.resumePoisoned(1006, 3_000, resumed = true))
    }

    @Test fun micGapLogsContext() {
        assertTrue(LiveStay.gapContext(false, 0, 10_000, "LISTENING", false).contains("जुड़ा नहीं"))
        val c = LiveStay.gapContext(true, 4_000, 10_000, "SPEAKING", true)
        assertTrue(c, c.contains("6s से") && c.contains("SPEAKING") && c.contains("बोल रहा"))
    }

    @Test fun wiredInGeminiLive() {
        val s = src("GeminiLive.kt")
        assertTrue(s.contains("LiveStay.demoteKnown1011(LiveModelRank.order(found, FALLBACK_MODELS, lat))"))
        assertTrue(s.contains("val safe = LiveStay.safeOrder(models) { broken1011.isBroken(it, now) }"))
        assertTrue(s.contains("code == 1011 && LiveStay.known1011(model)"))
        assertTrue(s.contains("code == 1011 && LiveStay.known1011(a.c.model)"))
        assertTrue(s.contains("markBroken1011(a.c.model)"))
        val d = s.substring(s.indexOf("private fun dropModel1011"), s.indexOf("private fun isQuota"))
        assertTrue(d.contains("markBroken1011(m)"))
        assertTrue(s.contains("LiveStay.resumePoisoned(code, lastedAtDrop, resumedConn)"))
        assertTrue(s.contains("LiveStay.gapContext(ready, upSince, now, DKState.mode.name, isPlaying())"))
    }

    // ---------------------------------------------------------------- Inspector: झूठा "फिर आया" / दोहरी गिनती

    private fun stall(n: Int) = I.liveFindings(I.parseStats("बात पर server से कुछ नहीं: $n"), null, null)

    @Test fun sameLiveCounterDoesNotReopenDismissedBug() {
        val first = I.merge(emptyList(), stall(7), 0, "v1").incidents
        assertEquals(7, first.single().lastRaw)
        val gone = I.dismiss(first, first.single().id, 1_000)
        // 20 मिनट बाद वही "7" = कुछ नया नहीं
        val m = I.merge(gone, stall(7), 20 * 60_000L, "v1")
        assertEquals(I.Status.DISMISSED, m.incidents.single().status)
        assertTrue(m.reopened.isEmpty())
        // सच में बढ़ा (7 -> 9) = फिर आया, गिनती 7 + 2
        val up = I.merge(gone, stall(9), 40 * 60_000L, "v1")
        assertEquals(I.Status.REOPENED, up.incidents.single().status)
        assertEquals(9, up.incidents.single().count); assertEquals(9, up.incidents.single().lastRaw)
        // नई बातचीत (गिनती 0 से, 9 -> 2) = नई घटनाएँ
        val reset = I.merge(up.incidents, stall(2), 60 * 60_000L, "v1").incidents.single()
        assertEquals(11, reset.count)
        // सबूत में एक ही लाइन बार-बार नहीं
        assertEquals(reset.evidence.distinct(), reset.evidence)
    }

    @Test fun oldStoredDismissedWithoutCounterIsNotReopened() {
        val i = I.merge(emptyList(), stall(7), 0, "v1").incidents.single().copy(status = I.Status.DISMISSED, lastRaw = -1)
        val m = I.merge(listOf(i), stall(7), 1_000, "v1")
        assertEquals(I.Status.DISMISSED, m.incidents.single().status)
        assertEquals(7, m.incidents.single().lastRaw)
    }

    @Test fun unchangedCounterGoesQuietAfter24h() {
        var xs = I.merge(emptyList(), stall(7), 0, "v1").incidents
        xs = I.merge(xs, stall(7), I.QUIET_MS, "v1").incidents
        assertEquals(I.Status.QUIET, xs.single().status)
    }

    private val realLog = """
09:02:05.492 Live: server ने बंद किया [$m09 • key #1] code=1011 reason=Internal error occurred.
09:02:05.553 Live: connection टूटा (code=1011, Internal error occurred., 44s चला) - दोबारा जुड़ रहा हूँ #3 (बातचीत वहीं से)
09:02:45.312 Live: server ने बंद किया [$m09 • key #1] code=1011 reason=Internal error occurred.
09:02:45.405 Live: connection टूटा (code=1011, Internal error occurred., 37s चला) - दोबारा जुड़ रहा हूँ #4 (नई बातचीत)
09:05:57.011 Live: 1011 "Internal error" 2 बार ($mLatest, बातचीत के बीच) - Google की तरफ़ इस मॉडल की खराबी; 2 मिनट हटाया, अब gemini-3.8-live (नई बातचीत)
09:05:58.000 Live: 1011 "Internal error" ($m12, बातचीत के बीच) - Google की तरफ़ इस मॉडल की खराबी; 6 घंटे सबसे पीछे, अब gemini-3.8-live (नई बातचीत)
""".trim()

    @Test fun oneDropCountedOnce_andHandledMessageIsNotABug() {
        val f = I.analyze(I.parseLog(realLog))
        val drop = f.single { it.rule.id == "live_conn_drop" }
        assertEquals(2, drop.count)                        // 4 लाइनें = 2 बार टूटा (पहले 4 गिनता था)
        assertEquals(4, drop.evidence.size)
        assertTrue(f.none { it.rule.id == "generic_error" })   // DKI-063250 अब नहीं
        assertEquals(1, f.size)
        assertEquals(9_725_492L, I.clockMs("02:42:05.492"))
        assertEquals(-1L, I.clockMs("--:--:--.---"))
    }

    @Test fun statsDuplicateOfLogBugIsDropped() {
        val logF = I.analyze(I.parseLog(realLog))
        val statF = I.liveFindings(I.parseStats("दोबारा जुड़ा: 6 • बात पर server से कुछ नहीं: 7"), null, null)
        assertTrue(statF.any { it.fingerprint.startsWith("stats:live_conn_drop") })
        val (uniq, _) = I.dropDupStats(logF + statF, emptyList())
        assertTrue(uniq.none { it.fingerprint.startsWith("stats:live_conn_drop") })
        assertTrue(uniq.any { it.fingerprint.startsWith("stats:live_stall") })   // अलग गड़बड़ी रहती है
        // store में पड़ी दोहरी (DKI-F2A775) भी हटे; लॉग वाली (DKI-406DD9) रहे
        val stored = I.merge(emptyList(), logF + statF, 0, "v1").incidents
        val (_, old) = I.dropDupStats(emptyList(), stored)
        assertTrue(old.none { it.fingerprint.startsWith("stats:live_conn_drop") })
        assertTrue(old.any { it.fingerprint == "live_conn_drop" })
        // Coding Agent के पास वाली नहीं छूती
        val fixing = stored.map { if (it.fingerprint.startsWith("stats:live_conn_drop")) it.copy(status = I.Status.FIXING) else it }
        assertTrue(I.dropDupStats(emptyList(), fixing).second.any { it.fingerprint.startsWith("stats:live_conn_drop") })
        assertTrue(src("inspector/Inspector.kt").contains("InspectorCore.dropDupStats(found, incidents(ctx))"))
    }
}
