package com.dk.assistant.agent

import com.dk.assistant.agent.core.ActionResult
import com.dk.assistant.agent.core.Blocker
import com.dk.assistant.agent.core.BlockerDetector
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ConfirmGate
import com.dk.assistant.agent.core.ExecPhase
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.TargetResolver
import com.dk.assistant.agent.core.TargetSpec
import com.dk.assistant.agent.core.TaskStateMachine
import com.dk.assistant.agent.core.UiActionExecutor
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.social.ChatText
import com.dk.assistant.agent.social.MediaPolicy
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.Selectors
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SocialRequestParser
import com.dk.assistant.agent.social.SocialTaskRunner
import com.dk.assistant.agent.social.TaskMode
import com.dk.assistant.agent.social.TaskReport
import com.dk.assistant.agent.social.TaskStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DK AI Part 12: screen agent + Instagram/Facebook/WhatsApp (FakeScreenDriver पर, असली फ़ोन नहीं) */
class SocialAgentTest {
    private var nextTop = 0
    private fun el(id: Int, text: String = "", desc: String = "", vid: String = "", click: Boolean = true, edit: Boolean = false, enabled: Boolean = true): UiElement {
        val top = (nextTop++ % 20) * 110
        return UiElement(id, text, desc, vid, "View", click, edit, false, enabled, Box(0, top, 1000, top + 100))
    }
    private fun obs(vararg e: UiElement, pkg: String = "x") = ScreenObservation(1, pkg, e.toList())

    // ---------------- core
    @Test fun resolver_viewIdBeatsText_exactAvoidsLookalike_disabledIgnored() {
        val o = obs(el(0, "Share to Facebook"), el(1, "Share", vid = "com.instagram.android:id/share_footer_button"), el(2, "share", enabled = false))
        assertEquals(1, TargetResolver.resolve(o, Selectors.IG_SHARE)?.id)
        assertNull(TargetResolver.resolve(obs(el(0, "Share to Facebook")), Selectors.IG_SHARE))
        assertNull(TargetResolver.resolve(obs(el(0, "share", enabled = false)), Selectors.IG_SHARE))
        // editable चाहिए तो सिर्फ़ editable
        val c = obs(el(0, "Write a caption…", click = true), el(1, "Write a caption…", edit = true))
        assertEquals(1, TargetResolver.resolve(c, Selectors.IG_CAPTION)?.id)
    }

    @Test fun blockers() {
        assertEquals(Blocker.LOGIN, BlockerDetector.detect(obs(el(0, "Username"), el(1, "Password", edit = true), el(2, "Log in"))))
        assertNull(BlockerDetector.detect(obs(el(0, "Log in to see more"), el(1, "Home"))))   // सिर्फ़ शब्द = लॉग-इन नहीं
        assertEquals(Blocker.CAPTCHA, BlockerDetector.detect(obs(el(0, "I'm not a robot"))))
        assertEquals(Blocker.TWO_FACTOR, BlockerDetector.detect(obs(el(0, "Enter the 6-digit security code"))))
        assertEquals(Blocker.SECURITY_CHECK, BlockerDetector.detect(obs(el(0, "We noticed unusual activity"))))
        assertEquals(Blocker.PERMISSION_PROMPT, BlockerDetector.detect(obs(el(0, "Allow"), pkg = "com.google.android.permissioncontroller")))
    }

    @Test fun confirmGate_needsRealYesAfterQuestion() {
        for (y in listOf("हाँ", "haan kar do", "yes", "ok post kar do", "हाँ भेज दो", "ji", "theek hai")) assertTrue(y, ConfirmGate.isYes(y))
        for (n in listOf("nahi", "मत करो", "no", "ruko", "cancel karo", "haan nahi", "kya?", "")) assertFalse(n, ConfirmGate.isYes(n))
        assertTrue(ConfirmGate.accept(askedAt = 100, userAt = 200, userText = "हाँ"))
        assertFalse(ConfirmGate.accept(askedAt = 100, userAt = 50, userText = "हाँ"))   // पूछने से पहले वाला हाँ नहीं चलेगा
        assertFalse(ConfirmGate.accept(askedAt = 0, userAt = 200, userText = "हाँ"))
        assertFalse(ConfirmGate.accept(askedAt = 100, userAt = 200, userText = "नहीं"))
    }

    @Test fun stateMachine_onlyLegalMoves() {
        val m = TaskStateMachine()
        assertFalse(m.go(ExecPhase.EXECUTING_ACTION))
        assertTrue(m.go(ExecPhase.OPENING_APP)); assertTrue(m.go(ExecPhase.WAITING_FOR_UI)); assertTrue(m.go(ExecPhase.OBSERVING))
        assertTrue(m.go(ExecPhase.RESOLVING_TARGET)); assertTrue(m.go(ExecPhase.VALIDATING_TARGET))
        assertTrue(m.go(ExecPhase.WAITING_FOR_USER)); assertTrue(m.go(ExecPhase.OBSERVING))
        assertTrue(m.go(ExecPhase.CANCELLED))
        assertFalse(m.go(ExecPhase.OBSERVING))          // खत्म के बाद कुछ नहीं
        assertEquals(ExecPhase.CANCELLED, m.phase)
    }

    @Test fun executor_clickThenGestureFallback_andNotFound() = runBlocking {
        val d = FakeScreenDriver("p", mapOf("a" to listOf(el(0, "Next")), "b" to listOf(el(0, "Done"))), "a")
        d.onClick["a" to "next"] = "b"
        val ex = UiActionExecutor(d, 0)
        assertTrue(ex.tap(TargetSpec(listOf("next"), exact = true)) is ActionResult.Success)
        assertEquals("b", d.current); assertEquals(0, d.gestureTaps)
        d.current = "a"; d.clickWorks = false
        assertTrue(ex.tap(TargetSpec(listOf("next"), exact = true)) is ActionResult.Success)
        assertEquals(1, d.gestureTaps)
        val r = ex.tap(TargetSpec(listOf("nothing here"), exact = true))
        assertTrue(r is ActionResult.Failed)
    }

    // ---------------- request / media / numbers
    @Test fun parser_rules() {
        fun p(j: String, f: Platform? = null) = SocialRequestParser.parse(JSONObject(j), f)
        assertTrue(p("""{"platform":"instagram","action":"post_photo","caption":"hi"}""") is SocialRequestParser.Parsed.Ok)
        assertTrue(p("""{"platform":"instagram","action":"post_text","caption":"hi"}""") is SocialRequestParser.Parsed.Err)
        assertTrue(p("""{"platform":"facebook","action":"post_text"}""") is SocialRequestParser.Parsed.Err)
        assertTrue(p("""{"action":"send_message","contact":"Ravi"}""", Platform.WHATSAPP) is SocialRequestParser.Parsed.Err)
        assertTrue(p("""{"action":"send_message","contact":"Ravi","message":"hi"}""", Platform.WHATSAPP) is SocialRequestParser.Parsed.Ok)
        assertTrue(p("""{"platform":"whatsapp","action":"post_photo"}""") is SocialRequestParser.Parsed.Err)
        assertTrue(p("""{"platform":"tiktok","action":"post_photo"}""") is SocialRequestParser.Parsed.Err)
        val d = p("""{"platform":"insta","action":"story","mode":"draft"}""") as SocialRequestParser.Parsed.Ok
        assertEquals(SocialAction.POST_STORY, d.r.action); assertEquals(TaskMode.DRAFT, d.r.mode)
    }

    @Test fun media_onlyWhatUserShared() {
        val img = SharedItem("content://com.dk.assistant.files/shared_media/a.jpg", "image/jpeg", 10)
        val vid = SharedItem("content://com.dk.assistant.files/shared_media/b.mp4", "video/mp4", 20)
        val all = listOf(img, vid)
        assertEquals(vid, MediaPolicy.resolve("latest_shared", all, null))
        assertEquals(img, MediaPolicy.resolve("", all, false))
        assertEquals(vid, MediaPolicy.resolve("latest", all, true))
        assertEquals(img, MediaPolicy.resolve(img.uri, all, null))
        assertNull(MediaPolicy.resolve("content://media/external/images/media/5", all, null))
        assertNull(MediaPolicy.resolve("/sdcard/DCIM/x.jpg", all, false))
        assertNull(MediaPolicy.resolve("latest_shared", emptyList(), null))
    }

    @Test fun whatsappNumbers() {
        assertEquals("919876543210", SocialPlanner.waDigits("98765 43210"))
        assertEquals("919876543210", SocialPlanner.waDigits("+91 98765-43210"))
        assertEquals("919876543210", SocialPlanner.waDigits("09876543210"))
        assertEquals("447700900123", SocialPlanner.waDigits("0044 7700 900123"))
        assertNull(SocialPlanner.waDigits("12345"))
    }

    @Test fun chatText_dropsChrome() {
        assertEquals(listOf("Kal milte hain", "ok bhai"), ChatText.visible(listOf("Message", "Kal milte hain", "10:42 pm", "ok bhai", "Send", "Today"), 10))
    }

    // ---------------- पूरा काम (runner)
    private val share = SharedItem("content://dk/shared_media/a.jpg", "image/jpeg", 1)
    private fun igReq(mode: String = "publish") = (SocialRequestParser.parse(JSONObject("""{"platform":"instagram","action":"post_photo","caption":"Nashik ka cafe","mode":"$mode"}""")) as SocialRequestParser.Parsed.Ok).r

    private fun instagram(after: List<UiElement>): FakeScreenDriver {
        val ig = Platform.INSTAGRAM.pkg
        val d = FakeScreenDriver(ig, mapOf(
            "crop" to listOf(el(0, desc = "Next", vid = "$ig:id/next_button_imageview"), el(1, "Crop")),
            "filter" to listOf(el(0, "Next", vid = "$ig:id/next_button_textview"), el(1, "Filter")),
            "caption" to listOf(el(0, "Write a caption…", vid = "$ig:id/caption_input_text_view", edit = true), el(1, "Share to Facebook"), el(2, "Share", vid = "$ig:id/share_footer_button")),
            "after" to after,
        ), "crop")
        d.onClick["crop" to "next"] = "filter"
        d.onClick["filter" to "next"] = "caption"
        d.onClick["caption" to "share"] = "after"
        return d
    }

    private fun run(d: FakeScreenDriver, task: com.dk.assistant.agent.social.SocialTask, confirmWait: Long = 180_000, onRep: (SocialTaskRunner, TaskReport) -> Unit): Pair<TaskReport, List<TaskStatus>> = runBlocking {
        val seen = mutableListOf<TaskStatus>()
        lateinit var r: SocialTaskRunner
        r = SocialTaskRunner(d, { true }, { rep -> seen += rep.status; onRep(r, rep) }, confirmWaitMs = confirmWait, settleMs = 0)
        r.run(task) to seen
    }

    @Test fun instagram_publish_asksYes_thenPosts_andVerifies() {
        val d = instagram(listOf(el(0, "Sharing…"), el(1, "Home")))
        val task = SocialPlanner.plan("t1", igReq(), share, null)
        assertTrue(task.open is OpenSpec.ShareMedia)
        val (rep, seen) = run(d, task) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CONFIRM) }
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertTrue(TaskStatus.WAITING_CONFIRMATION in seen)
        assertEquals(listOf("Nashik ka cafe"), d.typed)
        assertTrue(d.clicks.contains("share"))
        assertFalse(d.clicks.contains("share to facebook"))
    }

    @Test fun instagram_noYes_meansNothingPosted() {
        val d = instagram(listOf(el(0, "Sharing…")))
        val (rep, _) = run(d, SocialPlanner.plan("t2", igReq(), share, null), confirmWait = 2_000) { _, _ -> }
        assertEquals(TaskStatus.CANCELLED, rep.status)
        assertFalse(d.clicks.contains("share"))
    }

    @Test fun instagram_cancelAtQuestion() {
        val d = instagram(listOf(el(0, "Sharing…")))
        val (rep, _) = run(d, SocialPlanner.plan("t3", igReq(), share, null)) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CANCEL) }
        assertEquals(TaskStatus.CANCELLED, rep.status)
        assertFalse(d.clicks.contains("share"))
    }

    @Test fun instagram_draft_neverPosts() {
        val d = instagram(listOf(el(0, "Sharing…")))
        val (rep, seen) = run(d, SocialPlanner.plan("t4", igReq("draft"), share, null)) { r, _ -> r.control(SocialTaskRunner.Control.CONFIRM) }
        assertEquals(TaskStatus.DRAFT_READY, rep.status)
        assertFalse(TaskStatus.WAITING_CONFIRMATION in seen)
        assertFalse(d.clicks.contains("share"))
        assertEquals(listOf("Nashik ka cafe"), d.typed)
    }

    @Test fun instagram_unclearResult_isSubmittedUnverified() {
        val d = instagram(listOf(el(0, "Home"), el(1, "Reels")))
        val (rep, _) = run(d, SocialPlanner.plan("t5", igReq(), share, null)) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CONFIRM) }
        assertEquals(TaskStatus.SUBMITTED_UNVERIFIED, rep.status)
    }

    @Test fun login_waitsForUser_thenContinues() {
        val d = instagram(listOf(el(0, "Sharing…")))
        d.screens["login"] = listOf(el(0, "Phone number, username or email", edit = true), el(1, "Password", edit = true), el(2, "Log in"))
        d.current = "login"
        d.onCapture = { f -> if (f.current == "login" && f.captureCount >= 5) f.current = "crop" }
        val (rep, seen) = run(d, SocialPlanner.plan("t6", igReq(), share, null)) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CONFIRM) }
        assertTrue(TaskStatus.WAITING_USER in seen)
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertTrue(d.typed.none { it.contains("@") })      // DK ने लॉग-इन में कुछ नहीं भरा
        assertEquals(listOf("Nashik ka cafe"), d.typed)
    }

    @Test fun whatsapp_send_confirmed_andVerified() {
        val wa = Platform.WHATSAPP.pkg
        val req = (SocialRequestParser.parse(JSONObject("""{"action":"send_message","contact":"Ravi","message":"Hello bhai"}"""), Platform.WHATSAPP) as SocialRequestParser.Parsed.Ok).r
        val task = SocialPlanner.plan("w1", req, null, "919876543210", "Ravi Kumar")
        assertTrue((task.open as OpenSpec.Url).url.startsWith("https://wa.me/919876543210?text=Hello%20bhai"))
        assertTrue(task.confirmText.contains("Ravi Kumar"))
        val d = FakeScreenDriver(wa, mapOf(
            "chat" to listOf(el(0, "Ravi Kumar"), el(1, "Hello bhai", vid = "$wa:id/entry", edit = true), el(2, desc = "Send", vid = "$wa:id/send")),
            "sent" to listOf(el(0, "Ravi Kumar"), el(1, "Hello bhai"), el(2, "", vid = "$wa:id/entry", edit = true), el(3, desc = "Voice message", vid = "$wa:id/voice_note_btn")),
        ), "chat")
        d.onClick["chat" to "send"] = "sent"
        val (rep, _) = run(d, task) { r, rep -> if (rep.status == TaskStatus.WAITING_CONFIRMATION) r.control(SocialTaskRunner.Control.CONFIRM) }
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertEquals(listOf("send"), d.clicks)
    }

    @Test fun whatsapp_readChat_returnsMessages() {
        val wa = Platform.WHATSAPP.pkg
        val req = (SocialRequestParser.parse(JSONObject("""{"action":"read_chat","contact":"Ravi"}"""), Platform.WHATSAPP) as SocialRequestParser.Parsed.Ok).r
        val d = FakeScreenDriver(wa, mapOf("chat" to listOf(el(0, "Ravi Kumar"), el(1, "Kal aa raha hu"), el(2, "10:42 pm"), el(3, "Message", edit = true))), "chat")
        val (rep, _) = run(d, SocialPlanner.plan("w2", req, null, "919876543210")) { _, _ -> }
        assertEquals(TaskStatus.COMPLETED, rep.status)
        assertTrue(rep.readText.contains("Kal aa raha hu"))
        assertTrue(d.clicks.isEmpty())
        assertNotNull(rep.toJson().optString("content"))
    }
}
