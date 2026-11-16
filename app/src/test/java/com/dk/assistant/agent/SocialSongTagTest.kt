package com.dk.assistant.agent

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.social.PlanStep
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.ResultPick
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SocialRequestParser
import com.dk.assistant.agent.social.SocialTask
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

/** 16 नवंबर 2026: कहने पर गाना / टैग - न हो पाए तो पोस्ट नहीं रुकती, पर "हाँ" से पहले साफ़ बताया जाता है */
class SocialSongTagTest {
    private var nextTop = 0
    private fun el(id: Int, text: String = "", vid: String = "", edit: Boolean = false, box: Box? = null): UiElement {
        val top = (nextTop++ % 20) * 110
        return UiElement(id, text, "", vid, "View", true, edit, false, true, box ?: Box(0, top, 1000, top + 100))
    }
    private fun req(j: String) = (SocialRequestParser.parse(JSONObject(j)) as SocialRequestParser.Parsed.Ok).r
    private val vid = SharedItem("content://media/external/video/media/9", "video/mp4", 1)
    private val pic = SharedItem("content://media/external/images/media/5", "image/jpeg", 1)
    private val CAP = "com.instagram.android:id/caption_input_text_view"

    private fun run(d: FakeScreenDriver, task: SocialTask, yes: Boolean = true): Pair<TaskReport, List<TaskReport>> = runBlocking {
        val seen = mutableListOf<TaskReport>()
        lateinit var r: SocialTaskRunner
        r = SocialTaskRunner(d, { true }, { rep ->
            seen += rep
            if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(if (yes) SocialTaskRunner.Control.CONFIRM else SocialTaskRunner.Control.CANCEL)
        }, confirmWaitMs = 5_000, settleMs = 0)
        r.run(task) to seen
    }

    private fun reelPhone(music: List<UiElement>): FakeScreenDriver {
        val d = FakeScreenDriver(Platform.INSTAGRAM.pkg, mapOf(
            "editor" to listOf(el(0, "Audio"), el(1, "Next")),
            "music" to music,
            "caption" to listOf(el(0, "Write a caption", vid = CAP, edit = true), el(1, "Tag people"), el(2, "Share")),
            "tag" to listOf(el(0, "Search for a person", edit = true), el(1, "ravi_kumar Ravi Kumar"), el(2, "ravi.k22"), el(3, "Done")),
            "after" to listOf(el(0, "Sharing…")),
        ), "editor")
        d.onClick["editor" to "audio"] = "music"
        d.onClick["music" to "kesariya"] = "editor"
        d.onClick["editor" to "next"] = "caption"
        d.onClick["caption" to "tag people"] = "tag"
        d.onClick["tag" to "done"] = "caption"
        d.onClick["caption" to "share"] = "after"
        d.onBack["music"] = "editor"
        d.onBack["tag"] = "caption"
        return d
    }

    @Test fun reel_songAndTag_areDone_andToldBeforeYes() {
        val d = reelPhone(listOf(el(0, "Search music", edit = true), el(1, "Kesariya"), el(2, "Kesariya (Remix)")))
        val r = req("""{"platform":"instagram","action":"post_reel","caption":"Goa","song":"Kesariya - Arijit Singh","tags":"Ravi"}""")
        val (rep, seen) = run(d, SocialPlanner.plan("s1", r, vid, null))
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertEquals(listOf("Kesariya Arijit Singh", "Goa", "Ravi"), d.typed)
        assertTrue(d.clicks.contains("kesariya")); assertFalse(d.clicks.contains("kesariya (remix)"))
        assertTrue(d.clicks.contains("ravi_kumar ravi kumar"))
        val q = seen.first { it.status == TaskStatus.WAITING_CONFIRMATION }.message
        assertTrue(q, q.contains("गाना: Kesariya")); assertTrue(q, q.contains("टैग: ravi_kumar Ravi Kumar"))
        assertFalse(rep.message, rep.message.contains(" - पर "))
    }

    @Test fun songNotFound_warns_butPostStillAsks() {
        val d = reelPhone(listOf(el(0, "Search music", edit = true), el(1, "No results")))
        val r = req("""{"platform":"instagram","action":"post_reel","caption":"Goa","song":"Tum Hi Ho"}""")
        val (rep, seen) = run(d, SocialPlanner.plan("s2", r, vid, null))
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertTrue(d.backs >= 1)                                    // वापस editor पर
        val q = seen.first { it.status == TaskStatus.WAITING_CONFIRMATION }.message
        assertTrue(q, q.contains("गाना \"Tum Hi Ho\" नहीं लग पाया"))
        assertTrue(rep.message, rep.message.contains("गाना \"Tum Hi Ho\" नहीं लग पाया"))
    }

    @Test fun songNotFound_andNo_meansNothingPosted() {
        val d = reelPhone(listOf(el(0, "Search music", edit = true)))
        val (rep, _) = run(d, SocialPlanner.plan("s3", req("""{"platform":"instagram","action":"post_reel","song":"X Y"}"""), vid, null), yes = false)
        assertEquals(TaskStatus.CANCELLED, rep.status); assertFalse(d.clicks.contains("share"))
    }

    @Test fun photoTag_tapsOnPhotoFirst() {
        val photo = el(0, "Photo", box = Box(0, 0, 1080, 2400))
        val d = FakeScreenDriver(Platform.INSTAGRAM.pkg, mapOf(
            "caption" to listOf(el(0, "Write a caption", vid = CAP, edit = true), el(1, "Tag people"), el(2, "Share")),
            "tagphoto" to listOf(photo),
            "tag" to listOf(el(0, "Search for a person", edit = true), el(1, "amit_99"), el(2, "Done")),
            "after" to listOf(el(0, "Sharing…")),
        ), "caption")
        d.onClick["caption" to "tag people"] = "tagphoto"
        d.onClick["tagphoto" to "photo"] = "tag"
        d.onClick["tag" to "done"] = "caption"
        d.onClick["caption" to "share"] = "after"
        val t = SocialPlanner.plan("s4", req("""{"platform":"instagram","action":"post_photo","caption":"hi","tags":["Amit"]}"""), pic, null)
        assertTrue(t.steps.any { it is PlanStep.Soft && it.steps.any { s -> s is PlanStep.TapAt } })
        val (rep, _) = run(d, t)
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertTrue(d.gestureTaps >= 1); assertTrue(d.clicks.contains("amit_99"))
    }

    @Test fun plan_noSongNoTags_isUnchanged() {
        val t = SocialPlanner.plan("s5", req("""{"platform":"instagram","action":"post_photo","caption":"hi"}"""), pic, null)
        assertFalse(t.steps.any { it is PlanStep.Soft })
        assertEquals(3, t.steps.size)
        val fb = SocialPlanner.plan("s6", req("""{"platform":"facebook","action":"post_photo","caption":"hi","tags":"Ravi, Amit","song":"Kesariya"}"""), pic, null)
        assertEquals(3, fb.steps.count { it is PlanStep.Soft })                // 2 टैग + 1 गाना
        val yt = SocialPlanner.plan("s7", req("""{"platform":"youtube","action":"post_reel","song":"Kesariya"}"""), vid, null)
        assertTrue(yt.steps.first() is PlanStep.Soft)
        assertNull(SocialPlanner.songStep(req("""{"platform":"instagram","action":"post_reel","song":"auto"}"""), com.dk.assistant.agent.social.Selectors.IG_REEL_AUDIO, com.dk.assistant.agent.social.Selectors.IG_NEXT))
    }

    @Test fun unsupported_isHonest() {
        assertTrue(SocialPlanner.unsupported(req("""{"platform":"youtube","action":"post_reel","tags":"Ravi"}""")).any { it.contains("YouTube") })
        assertTrue(SocialPlanner.unsupported(req("""{"platform":"instagram","action":"post_story","song":"Kesariya"}""")).any { it.contains("स्टोरी") })
        assertTrue(SocialPlanner.unsupported(req("""{"platform":"instagram","action":"post_reel","song":"Kesariya","tags":"Ravi"}""")).isEmpty())
    }

    @Test fun resultPick_skipsSearchBoxAndChrome() {
        val o = ScreenObservation(1, "x", listOf(
            el(0, "Search for 'kesariya'"), el(1, "kesariya", edit = true), el(2, "Cancel"), el(3, "Kesariya Arijit Singh"), el(4, "Kesariya"),
        ))
        assertEquals(3, ResultPick.choose(o, "Kesariya")?.id)
        assertNull(ResultPick.choose(o, "Tum Hi Ho"))
        assertNull(ResultPick.choose(o, "k"))
    }
}
