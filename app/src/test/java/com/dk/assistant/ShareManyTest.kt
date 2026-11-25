package com.dk.assistant

import com.dk.assistant.agent.social.MediaPolicy
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.PlanStep
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialBatch
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SocialRequest
import com.dk.assistant.agent.social.SocialRequestParser
import com.dk.assistant.agent.social.TaskStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026: गैलरी से कई फोटो/वीडियो DK को -> सब एक पोस्ट / एक-एक करके स्टोरी / WhatsApp स्टेटस */
class ShareManyTest {
    private fun req(j: String): SocialRequest = (SocialRequestParser.parse(JSONObject(j)) as SocialRequestParser.Parsed.Ok).r
    private fun reqWa(j: String): SocialRequest = (SocialRequestParser.parse(JSONObject(j), Platform.WHATSAPP) as SocialRequestParser.Parsed.Ok).r

    // पुराना एक वाला (batch 1) + नया 3 का batch (2 फोटो + 1 वीडियो)
    private val old = SharedItem("u0", "image/jpeg", 100, batch = 100)
    private val a = SharedItem("u1", "image/jpeg", 200, batch = 200)
    private val b = SharedItem("u2", "video/mp4", 201, batch = 200)
    private val c = SharedItem("u3", "image/png", 202, batch = 200)
    private val all = listOf(c, old, b, a)

    @Test fun allSharedPicksLastBatchInShareOrder() {
        assertTrue(MediaPolicy.isMany("all_shared")); assertTrue(MediaPolicy.isMany("last_3")); assertFalse(MediaPolicy.isMany("latest_shared"))
        assertEquals(listOf("u1", "u2", "u3"), MediaPolicy.resolveMany("all_shared", all, null).map { it.uri })
        assertEquals(listOf("u1", "u3"), MediaPolicy.resolveMany("all_shared", all, false).map { it.uri })
        assertEquals(listOf("u2"), MediaPolicy.resolveMany("all_shared", all, true).map { it.uri })
        assertEquals(listOf("u0", "u1", "u2", "u3"), MediaPolicy.resolveMany("last_4", all, null).map { it.uri })
        // पुराना बर्ताव वही
        assertEquals(listOf("u3"), MediaPolicy.resolveMany("latest_shared", all, null).map { it.uri })
        assertEquals("u3", MediaPolicy.resolve("latest_shared", all, null)?.uri)
        assertEquals("2 फोटो और 1 वीडियो", MediaPolicy.countLabel(listOf(a, b, c)))
        // पुरानी सेव (batch नहीं) = बस आख़िरी एक
        assertEquals(1, MediaPolicy.resolveMany("all_shared", listOf(SharedItem("x", "image/jpeg", 5), SharedItem("y", "image/jpeg", 4)), null).size)
    }

    @Test fun instagramPostIsOneCarousel() {
        val r = req("""{"platform":"instagram","action":"post_photo","media":"all_shared","caption":"Goa trip"}""")
        val items = listOf(a, b, c)
        val p = SocialBatch.split(r, items)
        assertEquals(3, p.onePost.size); assertTrue(p.queue.isEmpty())
        val t = SocialPlanner.plan("t", r, a, null, many = p.onePost)
        val o = t.open as OpenSpec.ShareMedia
        assertEquals(listOf("u1", "u2", "u3"), o.uris); assertEquals("*/*", o.mime)
        assertTrue(t.confirmText.contains("2 फोटो और 1 वीडियो")); assertTrue(t.confirmText.contains("Goa trip"))
        assertTrue(t.steps.last() is PlanStep.Final)
        // Instagram 10 तक
        val twelve = (1..12).map { SharedItem("p$it", "image/jpeg", it.toLong(), batch = 1) }
        val p2 = SocialBatch.split(r, twelve)
        assertEquals(10, p2.onePost.size); assertEquals(2, p2.dropped)
        assertEquals("image/jpeg", (SocialPlanner.plan("t", r, twelve[0], null, many = p2.onePost).open as OpenSpec.ShareMedia).mime)
    }

    @Test fun singleStaysExactlyAsBefore() {
        val r = req("""{"platform":"instagram","action":"post_photo"}""")
        val t = SocialPlanner.plan("t", r, a, null)
        val o = t.open as OpenSpec.ShareMedia
        assertTrue(o.uris.isEmpty()); assertEquals("image/jpeg", o.mime)
        assertEquals("Instagram पर यह फोटो पोस्ट कर दूँ?", t.confirmText)
    }

    @Test fun storiesGoOneByOneWithOneQuestion() {
        val r = req("""{"platform":"instagram","action":"post_story","media":"all_shared"}""")
        val p = SocialBatch.split(r, listOf(a, b, c))
        assertTrue(p.onePost.isEmpty()); assertEquals(3, p.queue.size)
        val first = SocialPlanner.plan("t", r, a, null, many = p.queue, seq = 1 to 3)
        assertTrue(first.confirmText, first.confirmText.contains("3") && first.confirmText.contains("एक-एक करके"))
        val second = SocialPlanner.plan("t2", r, b, null, seq = 2 to 3)
        assertTrue(second.confirmText.startsWith("(2/3)"))
        assertTrue(SocialBatch.ok(TaskStatus.COMPLETED)); assertFalse(SocialBatch.ok(TaskStatus.FAILED))
        assertTrue(SocialBatch.summary("Instagram", 2, 3, TaskStatus.FAILED).contains("3 में से 2"))
        assertTrue(SocialBatch.summary("Instagram", 3, 3, TaskStatus.COMPLETED).contains("सभी 3"))
    }

    @Test fun whatsappStatus() {
        val r = reqWa("""{"action":"post_status","caption":"Sunday vibes"}""")
        assertEquals(SocialAction.POST_STORY, r.action); assertEquals("Sunday vibes", r.caption)
        assertEquals(SocialAction.POST_STORY, req("""{"platform":"whatsapp","action":"post_story"}""").action)
        val t = SocialPlanner.plan("w", r, a, null)
        val o = t.open as OpenSpec.ShareMedia
        assertEquals(SocialPlanner.STATUS_JID, o.jid)
        assertTrue(t.confirmText.contains("स्टेटस"))
        val fin = t.steps.last() as PlanStep.Final
        assertEquals("Send", fin.spec.name)
        assertTrue(t.steps.any { it is PlanStep.Type && it.text == "Sunday vibes" })
        // बाक़ी WhatsApp फ़ोटो-पोस्ट अब भी मना
        assertTrue(SocialRequestParser.parse(JSONObject("""{"platform":"whatsapp","action":"post_photo"}""")) is SocialRequestParser.Parsed.Err)
    }

    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun wiring() {
        val act = src("agent/android/ShareToDKActivity.kt")
        assertTrue(act.contains("ACTION_SEND_MULTIPLE")); assertTrue(act.contains("SharedMedia.addMany"))
        val man = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(man.contains("android.intent.action.SEND_MULTIPLE"))
        val ag = src("agent/android/SocialAgent.kt")
        assertTrue(ag.contains("ACTION_SEND_MULTIPLE")); assertTrue(ag.contains("FLAG_GRANT_READ_URI_PERMISSION")); assertTrue(ag.contains("runQueue"))
        // बाक़ी आइटम सिर्फ़ पहले वाली असली हाँ के बाद (पहला COMPLETED होने पर ही आगे)
        assertTrue(ag.contains("if (done == 1)"))
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("all_shared")); assertTrue(setup.contains("post_status"))
    }
}
