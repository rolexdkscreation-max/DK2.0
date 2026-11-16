package com.dk.assistant.agent

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.PlanStep
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SocialRequest
import com.dk.assistant.agent.social.SocialRequestParser
import com.dk.assistant.agent.social.SocialSmart
import com.dk.assistant.agent.social.SocialTaskRunner
import com.dk.assistant.agent.social.TaskReport
import com.dk.assistant.agent.social.TaskStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16 नवंबर 2026: सोशल पोस्ट स्पेशलिस्ट - कैप्शन / हैशटैग / YouTube Shorts (pure + FakeScreenDriver) */
class SocialSmartTest {
    private var nextTop = 0
    private fun el(id: Int, text: String = "", desc: String = "", vid: String = "", edit: Boolean = false): UiElement {
        val top = (nextTop++ % 20) * 110
        return UiElement(id, text, desc, vid, "View", true, edit, false, true, Box(0, top, 1000, top + 100))
    }
    private fun req(j: String) = (SocialRequestParser.parse(JSONObject(j)) as SocialRequestParser.Parsed.Ok).r

    @Test fun parser_newFields_andYoutubeRules() {
        val r = req("""{"platform":"instagram","action":"post_reel","media_query":"sunset at the beach","media_days":7,"auto_caption":true,
            "caption_hint":"दोस्तों के साथ","song":"Kesariya","tags":"Ravi, Amit और @sonu_01"}""")
        assertEquals("sunset at the beach", r.mediaQuery); assertEquals(7, r.mediaDays); assertTrue(r.autoCaption)
        assertEquals("Kesariya", r.song); assertEquals(listOf("Ravi", "Amit", "sonu_01"), r.tags); assertTrue(r.smart)
        val plain = req("""{"platform":"instagram","action":"post_photo","caption":"hi"}""")
        assertFalse(plain.smart); assertEquals("", plain.song); assertTrue(plain.tags.isEmpty())     // पुराना तरीका वैसा ही
        assertEquals(Platform.YOUTUBE, Platform.parse("यूट्यूब")); assertEquals(Platform.YOUTUBE, Platform.parse("shorts"))
        assertTrue(req("""{"platform":"youtube","action":"post_reel"}""").smart)
        assertTrue(SocialRequestParser.parse(JSONObject("""{"platform":"youtube","action":"post_photo"}""")) is SocialRequestParser.Parsed.Err)
        assertTrue(SocialRequestParser.parse(JSONObject("""{"platform":"youtube","action":"post_story"}""")) is SocialRequestParser.Parsed.Err)
        assertTrue(SocialRequestParser.parse(JSONObject("""{"action":"send_message","contact":"Ravi","message":"hi","tags":"Amit"}"""), Platform.WHATSAPP) is SocialRequestParser.Parsed.Err)
        // गाना सिर्फ़ बोलने पर
        assertEquals("", SocialSmart.normSong("")); assertEquals("", SocialSmart.normSong("none"))
        assertEquals(SocialSmart.SONG_AUTO, SocialSmart.normSong("auto")); assertEquals(SocialSmart.SONG_AUTO, SocialSmart.normSong("कोई भी"))
    }

    @Test fun prompt_isHonest_andAsksSongOnlyWhenWanted() {
        val ask = SocialSmart.Ask(autoCaption = true, hint = "birthday party")
        val p = SocialSmart.prompt(Platform.INSTAGRAM, SocialAction.POST_REEL, true, 4, ask)
        assertTrue(p.contains("4 frames")); assertTrue(p.contains("Never invent")); assertTrue(p.contains("birthday party"))
        assertFalse(p.contains("song (\""))
        assertTrue(SocialSmart.prompt(Platform.INSTAGRAM, SocialAction.POST_REEL, true, 4, ask.copy(song = "auto")).contains("ONE real, well-known song"))
        assertTrue(SocialSmart.prompt(Platform.YOUTUBE, SocialAction.POST_REEL, true, 4, ask).contains("YouTube Short"))
        assertTrue(SocialSmart.prompt(Platform.INSTAGRAM, SocialAction.POST_PHOTO, false, 1, ask.copy(captionGiven = "मेरा कैप्शन")).contains("do NOT change it"))
    }

    @Test fun parse_cleansHashtags_keepsUserCaption() {
        val reply = "```json\n{\"about\":\"समुद्र किनारे सूर्यास्त\",\"caption\":\"Golden hour vibes\",\"hashtags\":[\"#sunset\",\"beach life\",\"#Sunset\",\"#123\",\"#a\",\"#समुद्र\"],\"song\":\"Kesariya - Arijit Singh\"}\n```"
        val o = SocialSmart.parse(reply, Platform.INSTAGRAM, SocialSmart.Ask(true, song = "auto"))!!
        assertEquals("Golden hour vibes", o.caption)
        assertEquals(listOf("#sunset", "#beachlife", "#समुद्र"), o.hashtags)       // दोहराव / सिर्फ़ अंक / 1 अक्षर हटे
        assertEquals("Kesariya - Arijit Singh", o.song)
        val mine = SocialSmart.parse(reply, Platform.INSTAGRAM, SocialSmart.Ask(true, captionGiven = "मेरा अपना कैप्शन"))!!
        assertEquals("मेरा अपना कैप्शन", mine.caption); assertEquals("", mine.song)   // गाना नहीं माँगा = कोई गाना नहीं
        assertEquals(SocialSmart.maxTags(Platform.FACEBOOK), SocialSmart.cleanTags((1..20).map { "tag$it" }, SocialSmart.maxTags(Platform.FACEBOOK)).size)
        assertNull(SocialSmart.parse("sorry, I can't", Platform.INSTAGRAM, SocialSmart.Ask(true)))
        assertNull(SocialSmart.parse(null, Platform.INSTAGRAM, SocialSmart.Ask(true)))
    }

    @Test fun compose_captionAndTags_youtubeTitleMax100() {
        assertEquals("Hi\n\n#a1 #b2", SocialSmart.compose(Platform.INSTAGRAM, "Hi", listOf("#a1", "#b2")))
        assertEquals("Hi #a1\n\n#b2", SocialSmart.compose(Platform.INSTAGRAM, "Hi #a1", listOf("#a1", "#b2")))
        assertEquals("#a1", SocialSmart.compose(Platform.FACEBOOK, "", listOf("#a1")))
        val t = SocialSmart.compose(Platform.YOUTUBE, "x".repeat(95), listOf("#travel", "#goa"))
        assertTrue(t.length <= 100)
        assertEquals("Goa trip #shorts #travel #goa", SocialSmart.compose(Platform.YOUTUBE, "Goa trip", listOf("#travel", "#goa")))
        val s = SocialSmart.summary("आपकी कल की फोटो", "समुद्र किनारा.", "Golden", listOf("#sunset"), "Kesariya", listOf("Ravi"))
        assertEquals("आपकी कल की फोटो (समुद्र किनारा), कैप्शन: \"Golden\", हैशटैग: #sunset, गाना: Kesariya, टैग: Ravi", s)
    }

    private val vid = SharedItem("content://media/external/video/media/9", "video/mp4", 1)

    @Test fun youtubePlan_andConfirmText() {
        val r = req("""{"platform":"youtube","action":"post_reel","caption":"Goa trip #shorts"}""")
        val t = SocialPlanner.plan("y1", r, vid, null)
        assertTrue(t.open is OpenSpec.ShareMedia); assertEquals(Platform.YOUTUBE.pkg, (t.open as OpenSpec.ShareMedia).pkg)
        assertTrue(t.steps.last() is PlanStep.Final)
        assertTrue(t.confirmText.contains("YouTube Shorts"))
        val x = SocialPlanner.plan("y2", r.copy(confirmExtra = "गोवा वाला वीडियो, कैप्शन: \"Goa\""), vid, null)
        assertTrue(x.confirmText.startsWith("YouTube पर सब तैयार है - गोवा वाला वीडियो")); assertTrue(x.confirmText.endsWith("Short अपलोड (पब्लिक) कर दूँ?"))
        val ig = SocialPlanner.plan("i1", req("""{"platform":"instagram","action":"post_photo"}""").copy(confirmExtra = "फोटो"), vid, null)
        assertTrue(ig.confirmText.endsWith("पब्लिक (शेयर) कर दूँ?"))
        // पुराना सवाल वैसा ही
        assertEquals("Instagram पर यह फोटो (कैप्शन: \"hi\") पोस्ट कर दूँ?",
            SocialPlanner.plan("i2", req("""{"platform":"instagram","action":"post_photo","caption":"hi"}"""), SharedItem("u", "image/jpeg", 1), null).confirmText)
    }

    private fun youtube(after: List<UiElement>): FakeScreenDriver {
        val yt = Platform.YOUTUBE.pkg
        val d = FakeScreenDriver(yt, mapOf(
            "edit" to listOf(el(0, "Add sound"), el(1, "Next")),
            "details" to listOf(el(0, "Caption your Short", edit = true), el(1, "Visibility Public"), el(2, "Upload Short")),
            "after" to after,
        ), "edit")
        d.onClick["edit" to "next"] = "details"
        d.onClick["details" to "upload short"] = "after"
        return d
    }

    private fun run(d: FakeScreenDriver, task: com.dk.assistant.agent.social.SocialTask, onRep: (SocialTaskRunner, TaskReport) -> Unit): Pair<TaskReport, List<TaskReport>> = runBlocking {
        val seen = mutableListOf<TaskReport>()
        lateinit var r: SocialTaskRunner
        r = SocialTaskRunner(d, { true }, { rep -> seen += rep; onRep(r, rep) }, confirmWaitMs = 5_000, settleMs = 0)
        r.run(task) to seen
    }

    @Test fun youtube_asksYes_thenUploads() {
        val d = youtube(listOf(el(0, "Uploading…")))
        val t = SocialPlanner.plan("y3", req("""{"platform":"youtube","action":"post_reel","caption":"Goa trip #shorts"}"""), vid, null)
        val (rep, seen) = run(d, t) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CONFIRM) }
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertTrue(seen.any { it.status == TaskStatus.WAITING_CONFIRMATION })
        assertEquals(listOf("Goa trip #shorts"), d.typed)
        assertTrue(d.clicks.contains("upload short"))
        assertFalse(d.clicks.contains("add sound"))           // गाना नहीं माँगा = गाना नहीं
    }

    @Test fun youtube_noYes_nothingUploaded() {
        val d = youtube(listOf(el(0, "Uploading…")))
        val t = SocialPlanner.plan("y4", req("""{"platform":"youtube","action":"post_reel","caption":"x"}"""), vid, null)
        val (rep, _) = run(d, t) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CANCEL) }
        assertEquals(TaskStatus.CANCELLED, rep.status)
        assertFalse(d.clicks.contains("upload short"))
    }

    @Test fun requestDefaults_keepOldConstructorWorking() {
        val r = SocialRequest(Platform.INSTAGRAM, SocialAction.POST_PHOTO, com.dk.assistant.agent.social.TaskMode.PUBLISH, "", "", "", "", "")
        assertFalse(r.smart); assertEquals("", r.confirmExtra)
    }
}
