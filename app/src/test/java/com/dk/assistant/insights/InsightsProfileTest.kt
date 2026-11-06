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
import java.io.File

/**
 * नवंबर 2026 (मालिक): प्रोफ़ाइल (नाम, फ़ॉलोअर, फ़ॉलोइंग), आख़िरी रील कब और उसके कमेंट, मोनेटाइज़ेशन / रिकमेंडेशन की हालत और वजह,
 * पॉलिसी + एल्गोरिदम; "रिव्यू रोक दो" पर रुके; बीच में दूसरा काम = रिव्यू रुककर फिर अपने-आप; झूठ कभी नहीं.
 */
class InsightsProfileTest {
    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    @Test fun profileFieldsAndComments_areRead() {
        val a = Account.fromJson(JSONObject("""{"name":"Dhiraj","handle":"dk_creator","followers":"12.4K","following":"310","posts_count":"88",
            "last_post":"2 Nov","monetization":"Not eligible: unoriginal content","recommendation":"Some of your content can't be recommended"}"""))
        assertEquals("Dhiraj", a.name); assertEquals("dk_creator", a.handle)
        assertEquals(12_400L, a.followers); assertEquals(310L, a.following); assertEquals(88L, a.postsCount)
        assertEquals("2 Nov", a.lastPost)
        assertTrue(a.monetization.contains("unoriginal")); assertTrue(a.recommendation.contains("can't be recommended"))
        assertEquals(310L, Account(followers = 5).merge(a).following)
        val p = Post.fromJson(Platform.INSTAGRAM, JSONObject("""{"title":"Diwali reel","views":"900","comments":"2",
            "comment_list":["ram: बढ़िया", {"user":"sita","text":"next part?"}]}"""))!!
        assertEquals(listOf("ram: बढ़िया", "sita: next part?"), p.commentList)
        assertTrue(p.toJson().toString().contains("comment_list"))
        assertEquals(2, p.merge(p.copy(commentList = listOf("ram: बढ़िया"))).commentList.size)
    }

    @Test fun reasons_onlyFromWhatWasRead_neverGuessed() {
        assertTrue(InsightsPolicy.reasons("").isEmpty())
        assertTrue(InsightsPolicy.reasons("monetization page not found").isEmpty())
        assertTrue(InsightsPolicy.reasons("You're able to monetize").isEmpty())
        val r = InsightsPolicy.reasons("Your Page shared content that was unoriginal, or repurposed content from other sources with limited added value")
        assertTrue(r.first().why.contains("unoriginal"))
        assertTrue(InsightsPolicy.reasons("1 Community Standards strike").any { it.why.contains("कम्युनिटी") })
        assertEquals("पता नहीं चला (वह पेज स्क्रीन पर नहीं मिला)", InsightsPolicy.verdict(""))
        assertEquals("ठीक है", InsightsPolicy.verdict("You're able to monetize"))
        assertEquals("दिक्कत है", InsightsPolicy.verdict("Not yet eligible"))
    }

    @Test fun report_tellsProfileMoneyAndPolicy_honestly() {
        val d = PlatformData(Platform.FACEBOOK, listOf(Post(Platform.FACEBOOK, "Comedy reel", views = 500, comments = 3, commentList = listOf("amit: 😂"))),
            Account(views = 900, followers = 2_000, following = 50, name = "DK Page", lastPost = "3 Nov",
                recommendation = "Your Page is not recommendable: limited originality"))
        val an = listOf(Insights.analyze(d))
        val f = InsightsReport.fallback(listOf(d), an, "")
        assertTrue(f, f.contains("फ़ॉलोअर: 2K") || f.contains("फ़ॉलोअर: 2.0K"))
        assertTrue(f, f.contains("फ़ॉलोइंग: 50"))
        assertTrue(f, f.contains("3 Nov"))
        assertTrue(f, f.contains("amit: 😂"))
        // मोनेटाइज़ेशन पढ़ा ही नहीं गया = "पता नहीं चला", कोई बनी-बनाई वजह नहीं
        assertTrue(f, f.contains("मोनेटाइज़ेशन: पता नहीं चला"))
        assertTrue(f, f.contains("रिकमेंडेशन: दिक्कत है"))
        assertTrue(f, f.contains("unoriginal"))
        assertTrue(f, f.contains("https://business.facebook.com/business/help/3382366608650437"))
        val p = InsightsReport.prompt(listOf(d), an, "", "फेसबुक रिव्यू")
        assertTrue(p.contains("NEVER LIE"))
        assertTrue(p.contains("### मोनेटाइज़ेशन और रिकमेंडेशन"))
        assertTrue(p.contains("## पॉलिसी और एल्गोरिदम"))
        assertTrue(p.contains("POLICY (official help pages"))
        assertTrue(p.contains("Never invent a number"))
    }

    @Test fun policyGuide_hasOfficialRules() {
        val yt = InsightsPolicy.guide(Platform.YOUTUBE)
        assertTrue(yt.contains("4,000")); assertTrue(yt.contains("1 फ़रवरी 2027")); assertTrue(yt.contains("support.google.com/youtube"))
        assertTrue(InsightsPolicy.guide(Platform.INSTAGRAM).contains("Monetization status"))
        assertTrue(InsightsPolicy.guide(Platform.INSTAGRAM).contains("sends"))
        assertTrue(InsightsPolicy.guide(Platform.FACEBOOK).contains("limited originality"))
    }

    @Test fun screenGuide_asksForProfileCommentsAndMoney() {
        Platform.values().forEach { p ->
            val g = InsightsPlan.guide(p, studio = true)
            assertTrue(p.name, g.contains("PROFILE"))
            assertTrue(p.name, g.contains("comment_list"))
            assertTrue(p.name, g.contains("account.monetization"))
        }
        assertTrue(InsightsPlan.guide(Platform.FACEBOOK, false).contains("account.recommendation"))
    }

    @Test fun stopWords_stopTheReview_butNotNewRequests() {
        listOf("रुको", "ठीक है, रिव्यू रोक दो", "रिव्यू बंद करो", "review stop karo", "इंस्टाग्राम का रिव्यू रोक दो", "stop", "बस करो", "ریویو روک دو", "روک دو")
            .forEach { assertTrue(it, InsightsVoice.isStop(it)) }
        listOf("इंस्टाग्राम का रिव्यू करो", "रिव्यू मत रोको", "फेसबुक में रिकमेंडेशन बंद हो गया है, रिव्यू करो", "रुको, एक काम करो WhatsApp खोलो", "")
            .forEach { assertFalse(it, InsightsVoice.isStop(it)) }
        assertNull(InsightsVoice.parse("इंस्टाग्राम का रिव्यू रोक दो"))
        assertNotNull(InsightsVoice.parse("फेसबुक का मोनेटाइज़ेशन क्यों हटा, चेक करो"))
        assertNotNull(InsightsVoice.parse("फेसबुक में रिकमेंडेशन बंद हो गया है, रिव्यू करो"))
    }

    @Test fun stopIsWired_everywhere() {
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("watchStop(svc)"))
        assertTrue(a.contains("liveCall?.cancel()"))
        assertTrue(a.contains("stop() || justStopped()"))
        assertTrue(src("AgentRunner.kt").contains("InsightsVoice.isStop(goal)"))
    }

    private class Drv : ScreenDriver {
        var clicks = 0
        override suspend fun capture() = ScreenObservation(1, "com.instagram.android",
            listOf(UiElement(1, text = "Professional dashboard", clickable = true, bounds = Box(0, 100, 1080, 200))))
        override suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult { clicks++; return DriverResult.OK }
        override suspend fun tapAt(x: Int, y: Int) = DriverResult.OK
        override suspend fun setText(obs: ScreenObservation, e: UiElement, text: String) = DriverResult.UNSUPPORTED
        override suspend fun scroll(down: Boolean) = DriverResult.OK
        override suspend fun back(): DriverResult = DriverResult.OK
        override suspend fun pause(ms: Long) {}
    }

    @Test fun otherTask_pausesReview_thenItResumesByItself() = runBlocking {
        val d = Drv()
        var opened = 0
        var checks = 0
        var clicksWhilePaused = 0
        val replies = ArrayDeque(listOf(
            """{"posts":[],"actions":[{"do":"click","id":1}],"done":false}""",
            """{"posts":[],"actions":[{"do":"click","id":1}],"done":false}""",
            """{"posts":[],"actions":[],"done":true}""",
        ))
        // पहले जवाब के बाद दूसरा काम आया (कुछ जाँच तक रुका), फिर खत्म
        var paused = false
        val c = ScreenCollector(d, { opened++; true }, { true }, { r -> replies.removeFirstOrNull().also { if (replies.size == 2) paused = true } },
            paused = { checks++; if (paused && checks > 12) paused = false; if (paused) clicksWhilePaused = d.clicks; paused })
        c.collect(Platform.INSTAGRAM)
        // जवाब के बाद दूसरा काम आ गया = पुराने कदम (click) नहीं चले; रुकने के दौरान कोई दबाना नहीं
        assertEquals(1, d.clicks)
        assertEquals(0, clicksWhilePaused)
        // दूसरा काम खत्म = ऐप फिर खोला और रिव्यू आगे चला
        assertEquals(2, opened)
        assertTrue(replies.isEmpty())
    }

    @Test fun otherTaskWiring_inLiveAndAgent() {
        val t = src("GeminiLiveTools.kt")
        assertTrue(t.contains("val held = com.dk.assistant.insights.InsightsAgent.holdFor(name, args)"))
        assertTrue(t.contains("if (held) com.dk.assistant.insights.InsightsAgent.releaseHold()"))
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("paused = { othersOnScreen(svc) }"))
        assertTrue(a.contains("\"phone_task\""))
    }
}
