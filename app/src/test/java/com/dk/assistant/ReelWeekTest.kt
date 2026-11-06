package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: रील / वीडियो का 7 दिन का हिसाब, कुल लिमिट पर DK की आवाज़ (ReelWeek / ReelDaily). मालिक: "जो नहीं हो सकता वह हटाओ" - अंदाज़ा / बिना Accessibility वाला हिस्सा नहीं */
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
        assertEquals(9, ReelWeek.total(ReelWeek.day(nw, "2026-11-07")))   // कल (पिछले हफ़्ते का आख़िरी दिन) तुलना के लिए
        assertNull(ReelWeek.day(nw, "2026-11-01"))
        assertEquals("2026-11-15", ReelWeek.resetDate(nw))
        // कई दिन बाद खुला = नया हफ़्ता, पुराना "कल" नहीं
        assertNull(ReelWeek.roll(w, "2026-11-20").prev)
    }

    @Test fun counts_onlyExact_noEstimate() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 10)
        w = ReelWeek.addSeen(w, "2026-11-01", "ig", 300_000)
        val d = ReelWeek.day(w, "2026-11-01")!!
        assertEquals(10, ReelWeek.total(d)); assertEquals(5, ReelWeek.minutes(d))
        assertEquals(10, d.app("ig").n); assertEquals(0, d.app("yt").n)
        // पुराने (अंदाज़े वाले) सेव डेटा के फ़ालतू खाने = अनदेखे, अंदाज़ा कभी नहीं जुड़ता
        val old = """{"start":"2026-11-01","days":[{"date":"2026-11-01","titles":[],"apps":{"fb":{"n":2,"seen":1000,"reelS":500,"secs":900000}}}]}"""
        assertEquals(2, ReelWeek.total(ReelWeek.decode(old)!!.days[0]))
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

    @Test fun tableAndLive_showAllDays_exactOnly() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 30)
        w = ReelWeek.roll(w, "2026-11-02"); w = withReels(w, "2026-11-02", "yt", 12)
        w = ReelWeek.addSeen(w, "2026-11-02", "yt", 180_000)
        val t = ReelWeek.table(w, "2026-11-02", 25)
        assertTrue(t, t.contains("आज (11-02): 12 रील • 3 मिनट • इंस्टा 0, YouTube 12, फेसबुक 0") && t.contains("पिछले दिन से 18 कम"))
        assertTrue(t.contains("कल (11-01): 30 रील") && t.contains("लिमिट 25 पार"))
        assertTrue(t.contains("11-08 को अपने-आप नया हफ़्ता"))
        assertFalse(t.contains("~") || t.contains("अंदाज़ा"))
        val live = ReelWeek.liveText(w, "2026-11-02", 25, accOn = false)
        assertTrue(live.contains("only while DK's Accessibility is ON") && live.contains("nothing is being counted right now") && live.contains("Daily limit for all apps together: 25") && live.contains("yt: creator"))
        assertFalse(live.contains("estimate") || live.contains("Usage access"))
    }

    @Test fun saveAndLoad() {
        var w = ReelWeek.roll(null, "2026-11-01")
        w = withReels(w, "2026-11-01", "ig", 3); w = ReelWeek.addSeen(w, "2026-11-01", "ig", 9_000)
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

    @Test fun wiring_exactOnly_voice_live_settings_noFakeParts() {
        val m = src("AndroidManifest.xml")
        assertFalse(m.contains("ReelTimeService") || m.contains("FOREGROUND_SERVICE_SPECIAL_USE"))   // बिना Accessibility वाला अंदाज़ा हटाया
        assertFalse(m.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(File("src/main/java/com/dk/assistant/ReelTimeService.kt").exists() || File("app/src/main/java/com/dk/assistant/ReelTimeService.kt").exists())
        val d = src("java/com/dk/assistant/ReelDaily.kt")
        assertTrue(d.contains("PhoneVoice.say(c.applicationContext, text)"))
        assertFalse(d.contains("queryEvents") || d.contains("refreshUsage") || d.contains("estimated"))
        assertFalse(src("java/com/dk/assistant/DKApp.kt").contains("ReelTimeService"))
        assertFalse(src("java/com/dk/assistant/ComputerJobs.kt").contains("ReelTimeService"))
        val w = src("java/com/dk/assistant/ReelWatch.kt")
        assertTrue(w.contains("ReelRules.watchMode(app, nodes)") && w.contains("ReelDaily.addReel(c, app,") && w.contains("ReelDaily.coach(c)"))
        assertTrue(w.contains("ReelRules.Counter(stableMs = 600)") && w.contains("return if (inReel) 450L else 650L"))
        assertTrue(src("java/com/dk/assistant/GeminiLiveSetup.kt").contains("\"name\", \"reel_stats\""))
        assertTrue(src("java/com/dk/assistant/GeminiLiveTools.kt").contains("\"reel_stats\" -> withContext(Dispatchers.IO) { ReelDaily.liveTool(svc) }"))
        assertTrue(src("java/com/dk/assistant/LeakedCall.kt").contains("\"reel_stats\""))
        assertTrue(src("java/com/dk/assistant/LiveConnect.kt").contains("-> reel_stats"))
        val s = src("java/com/dk/assistant/SettingsReels.kt")
        assertTrue(s.contains("ReelDaily.setTotalLimit(") && s.contains("ReelDaily.table(this)"))
        assertFalse(s.contains("Accessibility के बिना भी") || s.contains("Usage access") || s.contains("ReelTimeService") || s.contains("अंदाज़ा"))
        assertNotNull(SettingsSearch.search("कुल लिमिट").firstOrNull { it.section == "reels" })
        assertTrue(SettingsSearch.search("Accessibility के बिना").none { it.section == "reels" })
    }
}
