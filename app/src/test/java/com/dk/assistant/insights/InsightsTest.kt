package com.dk.assistant.insights

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * नवंबर 2026 मालिक: "Facebook / Instagram / YouTube का पूरा रिव्यू - किस रील पर कितने व्यूज़, रीच क्यों गिर रही है,
 * एक बार बोलूँ तो पूरा काम करके बताए" - पहचान, नंबर पढ़ना, हिसाब, स्क्रीन वाला तरीका, API वाला तरीका.
 */
class InsightsTest {

    // ------------------------------------------------------------ पहचान
    @Test fun voiceFindsReviewRequests() {
        assertEquals(listOf(Platform.INSTAGRAM), InsightsVoice.parse("इंस्टाग्राम खोलो और मेरे जो भी काम हैं चेक करके पूरा रिव्यू करके बताओ")?.platforms)
        assertEquals(listOf(Platform.FACEBOOK), InsightsVoice.parse("मेरा फेसबुक देखकर बताओ रील क्यों नहीं वायरल जा रहा, रीच क्यों गिर रहा है")?.platforms)
        assertEquals(listOf(Platform.YOUTUBE), InsightsVoice.parse("यूट्यूब स्टूडियो खोलो और वहाँ पे जाके व्यूज़ चेक करो")?.platforms)
        assertEquals(listOf(Platform.YOUTUBE), InsightsVoice.parse("open YouTube Studio and check my views")?.platforms)
        assertEquals(Platform.values().toList(), InsightsVoice.parse("मेरे सोशल मीडिया का पूरा रिव्यू करो")?.platforms)
        assertEquals(listOf(Platform.INSTAGRAM, Platform.YOUTUBE), InsightsVoice.parse("insta aur youtube dono ka analysis karo")?.platforms)
    }

    @Test fun voiceIgnoresNonReview() {
        assertNull(InsightsVoice.parse("इंस्टाग्राम खोलो"))
        assertNull(InsightsVoice.parse("यूट्यूब पर गाना चलाओ"))
        assertNull(InsightsVoice.parse("फेसबुक पर यह फोटो पोस्ट करो"))
        assertNull(InsightsVoice.parse("instagram par rahul ko message bhejo"))
        assertNull(InsightsVoice.parse("मौसम कैसा है"))
    }

    // ------------------------------------------------------------ नंबर
    @Test fun numbersFromScreens() {
        assertEquals(1200L, Num.parse("1.2K"))
        assertEquals(12345L, Num.parse("12,345 views"))
        assertEquals(340000L, Num.parse("3.4 लाख"))
        assertEquals(20000000L, Num.parse("2 करोड़"))
        assertEquals(123L, Num.parse("१२३"))
        assertEquals(1500000L, Num.parse("1.5M"))
        assertEquals(4500L, Num.parse("4.5 हज़ार"))
        assertEquals(1234567L, Num.parse("1,234,567"))
        assertNull(Num.parse("-"))
        assertEquals("1.2K", Num.short(1234))
        assertEquals("15K", Num.short(15000))
        assertEquals("2.5M", Num.short(2_500_000))
    }

    @Test fun percentAndDurations() {
        assertEquals(4.5, Pct.parse("4.5%")!!, 0.001)
        assertEquals(7.0, Dur.parse("0:07")!!, 0.001)
        assertEquals(3723.0, Dur.parse("1:02:03")!!, 0.001)
        assertEquals(7.5, Dur.parse("7.5 सेकंड")!!, 0.001)
        assertEquals(130.0, Dur.parse("2m 10s")!!, 0.001)
        assertEquals(432000.0, Dur.parse("120 hours")!!, 0.001)
        assertEquals(3.4e3 * 3600, Dur.parse("3.4K hours")!!, 1.0)
        assertEquals(3.0, Ago.days("3d")!!, 0.001)
        assertEquals(2.0, Ago.days("2 दिन पहले")!!, 0.001)
        assertEquals(7.0, Ago.days("1w")!!, 0.001)
        assertEquals(0.0, Ago.days("आज")!!, 0.001)
    }

    // ------------------------------------------------------------ हिसाब
    private fun reel(t: String, d: String, v: Long, avg: Double? = null, sh: Long? = null, lk: Long? = null) =
        Post(Platform.INSTAGRAM, t, d, "reel", views = v, avgWatchSec = avg, shares = sh, likes = lk, comments = 2, saves = 1)

    @Test fun analysisFindsDropAndReasons() {
        val posts = listOf(
            reel("नई रील 1", "1d", 800, 2.1, 1, 10), reel("नई रील 2", "3d", 1000, 2.5, 0, 12), reel("नई रील 3", "5d", 900), reel("नई रील 4", "6d", 700),
            reel("पुरानी हिट", "15d", 25000, 9.0, 600), reel("पुरानी 2", "18d", 4000), reel("पुरानी 3", "20d", 5000),
        )
        val a = Insights.analyze(PlatformData(Platform.INSTAGRAM, posts, Account(status = "Some of your content can't be recommended")))
        assertEquals(Analysis.Trend.DOWN, a.trend)
        assertTrue(a.changePct!! < -50)
        assertEquals("पुरानी हिट", a.best?.title)
        val all = a.findings.joinToString("\n")
        assertTrue(all, all.contains("कम"))
        assertTrue(all, all.contains("शुरुआत"))
        assertTrue(all, all.contains("चेतावनी"))
        assertTrue(a.tips.isNotEmpty())
    }

    @Test fun youtubeLowCtrAndRetention() {
        val posts = listOf(
            Post(Platform.YOUTUBE, "वीडियो A", "2 दिन पहले", "video", views = 300, ctrPct = 1.8, retentionPct = 22.0),
            Post(Platform.YOUTUBE, "वीडियो B", "9 दिन पहले", "video", views = 5000, ctrPct = 9.1, retentionPct = 48.0),
        )
        val f = Insights.analyze(PlatformData(Platform.YOUTUBE, posts)).findings.joinToString("\n")
        assertTrue(f, f.contains("CTR 3% से कम"))
        assertTrue(f, f.contains("8% या उससे ज़्यादा"))
        assertTrue(f, f.contains("औसतन 22"))
    }

    @Test fun reportFallbackHasTableAndSpoken() {
        val d = PlatformData(Platform.INSTAGRAM, listOf(reel("A", "1d", 100), reel("B", "2d", 900), reel("C", "3d", 300)), Account(views = 5000))
        val an = listOf(Insights.analyze(d))
        val r = InsightsReport.fallback(listOf(d), an, "")
        assertTrue(r, r.contains("| रील |"))
        assertTrue(r, r.contains("सबसे अच्छी"))
        val s = InsightsReport.fallbackSpoken(listOf(d), an)
        assertFalse(s.contains("|"))
        val (body, spoken) = InsightsReport.split("## सार\nठीक\n${InsightsReport.SPOKEN_MARK} **सब** ठीक है")
        assertEquals("## सार\nठीक", body)
        assertEquals("सब ठीक है", spoken)
        val p = InsightsReport.prompt(listOf(d), an, "trend x", "रिव्यू करो")
        assertTrue(p.contains("Never invent a number"))
        assertTrue(p.contains("\"views\":900"))
    }

    @Test fun mergeKeepsOneItemForApiAndScreen() {
        val api = PlatformData(Platform.INSTAGRAM, listOf(Post(Platform.INSTAGRAM, "Diwali special dance with family and friends #reels", views = 1200, source = "api")))
        val scr = PlatformData(Platform.INSTAGRAM, listOf(Post(Platform.INSTAGRAM, "Diwali special dance with", avgWatchSec = 4.0)), statusChecked = true)
        val m = api.merge(scr)
        assertEquals(1, m.posts.size)
        assertEquals(1200L, m.posts[0].views)
        assertEquals(4.0, m.posts[0].avgWatchSec!!, 0.01)
        assertTrue(m.statusChecked)
    }

    // ------------------------------------------------------------ स्क्रीन वाला तरीका
    @Test fun parseReplyActions() {
        val r = InsightsPlan.parseReply("```json\n{\"posts\":[{\"title\":\"x\",\"views\":\"1.2K\"}],\"account\":{\"views\":\"\"},\"actions\":[{\"do\":\"click\",\"id\":4},{\"do\":\"scroll\",\"dir\":\"up\"},{\"do\":\"fly\"}],\"done\":false}\n```")!!
        assertEquals(1, r.posts.size)
        assertNull(r.account)
        assertEquals(listOf("click", "scroll"), r.actions.map { it.kind })
        assertEquals(4, r.actions[0].id)
        assertEquals("up", r.actions[1].dir)
        assertNull(InsightsPlan.parseReply("नहीं पता"))
    }

    private class FakeDriver(val screens: List<ScreenObservation>) : ScreenDriver {
        var i = 0
        val clicks = mutableListOf<Int>()
        override suspend fun capture() = screens[i.coerceAtMost(screens.size - 1)]
        override suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult { clicks += e.id; i++; return DriverResult.OK }
        override suspend fun tapAt(x: Int, y: Int) = DriverResult.OK
        override suspend fun setText(obs: ScreenObservation, e: UiElement, text: String) = DriverResult.UNSUPPORTED
        override suspend fun scroll(down: Boolean): DriverResult { i++; return DriverResult.OK }
        override suspend fun back(): DriverResult = DriverResult.OK
        override suspend fun pause(ms: Long) {}
    }

    private fun screen(n: Long, vararg t: String) = ScreenObservation(n, "com.instagram.android",
        t.mapIndexed { k, s -> UiElement(k + 1, text = s, clickable = true, bounds = Box(0, 100 * k, 1080, 100 * k + 90)) })

    @Test fun collectorReadsScreensAndStops() = runBlocking {
        val d = FakeDriver(listOf(
            screen(1, "Profile", "Professional dashboard", "Views 12.3K", "Accounts reached 4,100"),
            screen(2, "Reels", "Reel one 3d 1.2K", "Reel two 5d 900"),
        ))
        val replies = ArrayDeque(listOf(
            """{"account":{"period":"Last 30 days","views":"12.3K","reach":"4,100"},"posts":[],"actions":[{"do":"click","id":2}],"done":false}""",
            """{"posts":[{"title":"Reel one","date":"3d","views":"1.2K"},{"title":"Reel two","date":"5d","views":"900"}],"status_checked":true,"actions":[],"done":true}""",
        ))
        val prompts = mutableListOf<String>()
        val c = ScreenCollector(d, openApp = { true }, installed = { it == "com.instagram.android" }, brain = { p -> prompts += p; replies.removeFirstOrNull() })
        val r = c.collect(Platform.INSTAGRAM)
        assertEquals(12300L, r.account.views)
        assertEquals(4100L, r.account.reach)
        assertEquals(2, r.posts.size)
        assertEquals(1200L, r.posts[0].views)
        assertTrue(r.statusChecked)
        assertEquals(listOf(2), d.clicks)
        assertTrue(prompts[0].contains("[2] Professional dashboard"))
        assertTrue(prompts[0].contains("never post, delete"))
        assertTrue(prompts[1].contains("overview collected: YES"))
    }

    @Test fun collectorStopsAtLoginAndMissingApp() = runBlocking {
        val login = ScreenObservation(1, "com.instagram.android", listOf(UiElement(1, text = "Log in"), UiElement(2, text = "Password", editable = true)))
        val r = ScreenCollector(FakeDriver(listOf(login)), { true }, { true }, { "{}" }).collect(Platform.INSTAGRAM)
        assertTrue(r.problems.joinToString(), r.problems.any { it.contains("लॉग-इन") })
        val none = ScreenCollector(FakeDriver(listOf(login)), { true }, { false }, { "{}" }).collect(Platform.YOUTUBE)
        assertTrue(none.problems.joinToString(), none.problems.any { it.contains("YouTube Studio") })
    }

    // ------------------------------------------------------------ API वाला तरीका
    private val today = LocalDate.parse("2026-11-05")

    @Test fun youtubeApiParsing() {
        val vids = JSONObject("""{"items":[
            {"id":"v1","snippet":{"title":"Short one","publishedAt":"2026-11-03T10:00:00Z"},"statistics":{"viewCount":"1500","likeCount":"80","commentCount":"5"},"contentDetails":{"duration":"PT45S"}},
            {"id":"v2","snippet":{"title":"Long video","publishedAt":"2026-10-20T10:00:00Z"},"statistics":{"viewCount":"9000"},"contentDetails":{"duration":"PT12M5S"}}]}""")
        val posts = InsightsApi.ytVideos(vids, today)
        assertEquals("short", posts[0].type)
        assertEquals(725.0, posts[1].durationSec!!, 0.1)
        assertTrue(posts[0].date.startsWith("2 दिन पहले"))
        val by = InsightsApi.ytAnalyticsByVideo(JSONObject("""{"columnHeaders":[{"name":"video"},{"name":"views"},{"name":"averageViewDuration"},{"name":"averageViewPercentage"},{"name":"subscribersGained"},{"name":"subscribersLost"}],
            "rows":[["v1",1600,21,46.5,12,2]]}"""))
        val merged = InsightsApi.ytMerge(posts, by)
        assertEquals(1600L, merged[0].views)
        assertEquals(46.5, merged[0].retentionPct!!, 0.01)
        assertEquals(10L, merged[0].follows)
        assertEquals("", InsightsApi.cleanNotes(merged[0]).notes)
        val tot = InsightsApi.ytTotals(JSONObject("""{"columnHeaders":[{"name":"views"},{"name":"estimatedMinutesWatched"}],"rows":[[800,100]]}"""),
            JSONObject("""{"columnHeaders":[{"name":"views"}],"rows":[[1000]]}"""), 5000)
        assertEquals(-20.0, tot.viewsChangePct!!, 0.01)
        assertEquals(6000.0, tot.watchTimeSec!!, 0.01)
        assertEquals("Shorts फ़ीड 75%, YouTube सर्च 25%", InsightsApi.ytTraffic(JSONObject("""{"rows":[["YT_SEARCH",25],["YT_SHORTS",75]]}""")))
        assertEquals(65.0, InsightsApi.isoDuration("PT1M5S")!!, 0.01)
    }

    @Test fun metaApiParsing() {
        val media = InsightsApi.igMedia(JSONObject("""{"data":[{"id":"m1","caption":"Hello reel","media_product_type":"REELS","timestamp":"2026-11-04T08:00:00+0000","like_count":40,"comments_count":3}]}"""), today)
        assertEquals("reel", media[0].type)
        assertEquals(40L, media[0].likes)
        val m = InsightsApi.metaInsights(JSONObject("""{"data":[{"name":"views","values":[{"value":2300}]},{"name":"reach","values":[{"value":1800}]},
            {"name":"ig_reels_avg_watch_time","values":[{"value":4200}]},{"name":"shares","total_value":{"value":7}}]}"""))
        val p = InsightsApi.igApply(media[0], m)
        assertEquals(2300L, p.views)
        assertEquals(1800L, p.reach)
        assertEquals(4.2, p.avgWatchSec!!, 0.01)
        assertEquals(7L, p.shares)
        val fb = InsightsApi.fbPosts(JSONObject("""{"data":[{"id":"p1","message":"Page post","created_time":"2026-11-01T08:00:00+0000","shares":{"count":4},
            "reactions":{"summary":{"total_count":55}},"comments":{"summary":{"total_count":6}}}]}"""), today)
        assertEquals(55L, fb[0].likes)
        assertEquals(4L, fb[0].shares)
        assertEquals(900L, InsightsApi.fbApply(fb[0], mapOf("post_impressions_unique" to 900.0)).reach)
        assertNotNull(InsightsApi.firstWorking(listOf("a", "b")) { if (it == "b") 1 else null })
    }
}
