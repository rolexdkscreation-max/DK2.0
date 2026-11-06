package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: रील / वीडियो का 7 दिन का हिसाब, कुल लिमिट पर DK की आवाज़, Accessibility के बिना भी (ReelWeek / ReelDaily / ReelTimeService) */
class ReelWeekTest {
    private fun src(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }.readText()

    private fun withReels(w: ReelWeek.Week, date: String, app: String, n: Int): ReelWeek.Week {
        var x = w; repeat(n) { x = ReelWeek.addReel(x, date, app, "creator $it") }; return x
    }

    @Test fun weekRolls_sevenDays_thenAutoReset_keepsLastDayForCompare() {
        var w = ReelWeek.roll(null, "2026-11-01")
        assertEquals("2026-11-01", w.start)
        w = withReels(w, "2026-11-01", "ig", 5)
        for (d in 2..7) w = ReelWeek.roll(w, "2026-11-0$d")
        assertEquals(7, w.days.size); assertEquals("2026-11-01", w.start)
        w = withReels(w, "2026-11-07", "yt", 9)
        val nw = ReelWeek.roll(w, "2026-11-08")              // 7 दिन पूरे = नया हफ़्ता
        assertEquals("2026-11-08", nw.start); assertEquals(1, nw.days.size)
        assertEquals(9, ReelWeek.total(ReelWeek.day(nw, "2026-11-07"), 20))   // कल (पिछले हफ़्ते का आख़िरी दिन) तुलना के लिए
        assertNull(ReelWeek.day(nw, "2026-11-01"))
        assertEquals("2026-11-15", ReelWeek.resetDate(nw))
        // कई दिन बाद खुला = नया हफ़्ता, पुराना "कल" नहीं
        assertNull(ReelWeek.roll(w, "2026-11-20").prev)
    }

    @Test fun counts_exact_plusEstimateOnlyForBlindTime() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 10)
        w = ReelWeek.addSeen(w, "2026-11-01", "ig", 300_000, 250_000)    // DK ने 5 मिनट देखा, 10 रील = 25 सेकंड / रील
        assertEquals(25, ReelWeek.reelSecs(w))
        w = ReelWeek.setSecs(w, "2026-11-01", "ig", 300_000)             // Usage access: ऐप कुल 5 मिनट = सब DK ने देखा
        val d = ReelWeek.day(w, "2026-11-01")!!
        assertEquals(10 to 0, ReelWeek.counts(d.app("ig"), 25))
        assertFalse(ReelWeek.estimated(d, 25))
        w = ReelWeek.setSecs(w, "2026-11-01", "ig", 550_000)             // 250 सेकंड Accessibility बंद में = ~10 अंदाज़ा
        val d2 = ReelWeek.day(w, "2026-11-01")!!
        assertEquals(10 to 10, ReelWeek.counts(d2.app("ig"), 25))
        assertTrue(ReelWeek.estimated(d2, 25)); assertEquals(20, ReelWeek.total(d2, 25))
        assertEquals(20, ReelWeek.reelSecs(ReelWeek.roll(null, "2026-11-01")))   // कुछ गिना नहीं = 20 सेकंड
    }

    @Test fun usageForegroundTime_fromAndroidEvents_noAccessibility() {
        val ig = "com.instagram.android"; val wa = "com.whatsapp"
        val ev = listOf(
            Triple(ig, true, 500L),        // आधी रात से पहले खुला
            Triple(ig, false, 1_500L),
            Triple(wa, true, 2_000L), Triple(wa, false, 3_000L),
            Triple(ig, true, 4_000L),
            Triple(wa, true, 6_000L),      // बिना pause के दूसरा ऐप आया
            Triple(ig, true, 9_000L),
        )
        val m = ReelWeek.foregroundMs(ev, 1_000L, 10_000L, setOf(ig))
        assertEquals(500L + 2_000L + 1_000L, m[ig])      // 1000-1500, 4000-6000, 9000-10000 (अभी भी खुला)
        assertNull(m[wa])
    }

    @Test fun dailyCompare_moreLessAndLimit() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 50)
        w = ReelWeek.roll(w, "2026-11-02"); w = withReels(w, "2026-11-02", "yt", 100)
        w = ReelWeek.roll(w, "2026-11-03")
        val more = ReelWeek.dailyLine(w, "2026-11-03", 0)!!
        assertTrue(more, more.contains("कल आपने 100 रील") && more.contains("परसों से 50 ज़्यादा") && more.contains("क्यों"))
        val lim = ReelWeek.dailyLine(w, "2026-11-03", 80)!!
        assertTrue(lim.contains("लिमिट 80 से 20 ज़्यादा"))
        w = withReels(w, "2026-11-03", "fb", 10)
        w = ReelWeek.roll(w, "2026-11-04")
        assertTrue(ReelWeek.dailyLine(w, "2026-11-04", 0)!!.contains("परसों से 90 कम"))
        assertNull(ReelWeek.dailyLine(ReelWeek.roll(null, "2026-11-04"), "2026-11-04", 0))   // कल का डेटा नहीं = कुछ नहीं बोलो
    }

    @Test fun alerts_80percent_cross_every10_andNoLimitMoreThanYesterday() {
        assertEquals(0, ReelWeek.alertStep(79, 100, 0))
        assertEquals(1, ReelWeek.alertStep(80, 100, 0))
        assertEquals(1, ReelWeek.alertStep(100, 100, 0))
        assertEquals(2, ReelWeek.alertStep(101, 100, 0))
        assertEquals(2, ReelWeek.alertStep(110, 100, 0))
        assertEquals(3, ReelWeek.alertStep(111, 100, 0))
        assertEquals(0, ReelWeek.alertStep(40, 0, 50)); assertEquals(1, ReelWeek.alertStep(51, 0, 50))
        assertEquals(0, ReelWeek.alertStep(500, 0, 0))     // न लिमिट, न कल = चुप
        val over = ReelWeek.alertLine(105, 100, 0)
        assertTrue(over.contains("लिमिट से 5 ज़्यादा") && over.contains("फ़ोन रख दीजिए") && over.contains("आँखों") && over.contains("सेहत"))
        assertTrue(ReelWeek.alertLine(85, 100, 0).contains("बस 15 बाकी"))
        assertTrue(ReelWeek.alertLine(60, 0, 50).contains("कल पूरे दिन में 50"))
    }

    @Test fun tableAndLive_showAllDays_markEstimate() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 30)
        w = ReelWeek.roll(w, "2026-11-02"); w = withReels(w, "2026-11-02", "yt", 12)
        w = ReelWeek.setSecs(w, "2026-11-02", "fb", 200_000)     // Accessibility बंद में 200 सेकंड फेसबुक = ~10
        val t = ReelWeek.table(w, "2026-11-02", 25)
        assertTrue(t, t.contains("आज (11-02): ~22 रील") && t.contains("पिछले दिन से 8 कम"))
        assertTrue(t.contains("कल (11-01): 30 रील") && t.contains("लिमिट 25 पार"))
        assertTrue(t.contains("11-08 को अपने-आप नया हफ़्ता") && t.contains("~ = Accessibility बंद"))
        val live = ReelWeek.liveText(w, "2026-11-02", 25, accOn = false, usageOn = true)
        assertTrue(live.contains("Accessibility (now OFF)") && live.contains("Daily limit for all apps together: 25") && live.contains("yt: creator"))
    }

    @Test fun saveAndLoad() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 3); w = ReelWeek.addSeen(w, "2026-11-01", "ig", 9_000, 6_000)
        val w7 = ReelWeek.roll(w, "2026-11-07")
        assertEquals(w7, ReelWeek.decode(ReelWeek.encode(w7)))
        w = ReelWeek.roll(w7, "2026-11-08")
        assertEquals(w, ReelWeek.decode(ReelWeek.encode(w)))
        assertNull(ReelWeek.decode("कचरा"))
    }

    @Test fun youtubeNormalVideo_countedByTitle_notByRunningTime() {
        val n = listOf(
            ReelRules.Node("com.google.android.youtube:id/watch_player", "", "", 0.5f, 0.15f),
            ReelRules.Node("", "3:45 / 10:20", "", 0.2f, 0.28f),
            ReelRules.Node("", "Kheti ka naya tarika", "", 0.4f, 0.36f),
            ReelRules.Node("", "Kisan Channel", "", 0.3f, 0.42f),
            ReelRules.Node("", "Subscribe", "", 0.8f, 0.42f),
        )
        assertTrue(ReelRules.watchMode(ReelRules.App.YOUTUBE, n))
        assertFalse(ReelRules.watchMode(ReelRules.App.INSTAGRAM, n))
        assertEquals(setOf("kheti ka naya tarika", "kisan channel"), ReelRules.watchSignature(n))
        val later = n.map { if (it.text.startsWith("3:45")) it.copy(text = "3:46 / 10:20") else it }
        assertEquals(ReelRules.watchSignature(n), ReelRules.watchSignature(later))    // चलता समय = वही वीडियो
    }

    @Test fun wiring_worksWithoutAccessibility_voice_live_settings() {
        val m = src("AndroidManifest.xml")
        assertTrue(m.contains("android:name=\".ReelTimeService\"") && m.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(m.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse(m.contains("SYSTEM_ALERT_WINDOW"))
        val svc = src("java/com/dk/assistant/ReelTimeService.kt")
        assertTrue(svc.contains("ReelDaily.foregroundNow(this)") && svc.contains("if (app != null && !accOn) ReelDaily.coach(this)"))
        assertFalse(svc.contains("rootInActiveWindow"))
        val d = src("java/com/dk/assistant/ReelDaily.kt")
        assertTrue(d.contains("PhoneVoice.say(c.applicationContext, text)") && d.contains("um.queryEvents("))
        assertTrue(src("java/com/dk/assistant/DKApp.kt").contains("ReelTimeService.start(this)"))
        assertTrue(src("java/com/dk/assistant/ComputerJobs.kt").contains("ReelTimeService.start(c.applicationContext)"))
        val w = src("java/com/dk/assistant/ReelWatch.kt")
        assertTrue(w.contains("ReelRules.watchMode(app, nodes)") && w.contains("ReelDaily.addReel(c, app,") && w.contains("ReelDaily.coach(c)"))
        assertTrue(src("java/com/dk/assistant/GeminiLiveSetup.kt").contains("\"name\", \"reel_stats\""))
        assertTrue(src("java/com/dk/assistant/GeminiLiveTools.kt").contains("\"reel_stats\" -> withContext(Dispatchers.IO) { ReelDaily.liveTool(svc) }"))
        assertTrue(src("java/com/dk/assistant/LeakedCall.kt").contains("\"reel_stats\""))
        assertTrue(src("java/com/dk/assistant/LiveConnect.kt").contains("-> reel_stats"))
        val s = src("java/com/dk/assistant/SettingsReels.kt")
        assertTrue(s.contains("ReelDaily.setTotalLimit(") && s.contains("ReelDaily.table(this)") && s.contains("PayGuard.openUsageAccess(this)"))
        assertNotNull(SettingsSearch.search("कुल लिमिट").firstOrNull { it.section == "reels" })
    }
}
