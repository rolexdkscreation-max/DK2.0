package com.dk.assistant

import com.dk.assistant.agent.social.FileMatch
import com.dk.assistant.agent.social.OpenSpec
import com.dk.assistant.agent.social.PlanStep
import com.dk.assistant.agent.social.Platform
import com.dk.assistant.agent.social.SharedItem
import com.dk.assistant.agent.social.SocialAction
import com.dk.assistant.agent.social.SocialPlanner
import com.dk.assistant.agent.social.SocialRequestParser
import com.dk.assistant.agent.social.WaRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** मालिक (नवंबर 2026): "कोई फ़ाइल भेजनी हो तो DK फ़ाइल ढूँढे, निकाले और जिसे बोलें उसे WhatsApp पर भेजे" */
class WaSendFileTest {
    private fun src(path: String) = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }.readText()

    private val files = listOf(
        FileMatch.Cand("Aadhar_Card_2024.pdf", "application/pdf", "u1", 1000),
        FileMatch.Cand("IMG_20261101_101010.jpg", "image/jpeg", "u2", 5000),
        FileMatch.Cand("IMG_20261030_090000.jpg", "image/jpeg", "u3", 3000),
        FileMatch.Cand("Rahul_Resume_final.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "u4", 2000),
        FileMatch.Cand("website_snake_game.html", "text/html", "u5", 4000),
        FileMatch.Cand("trip.mp4", "video/mp4", "u6", 4500),
    )

    @Test fun findsFileFromSpokenWords() {
        assertEquals("u1", FileMatch.best("आधार कार्ड वाली PDF", files).single().uri)
        assertEquals("u1", FileMatch.best("aadhar card", files).single().uri)
        assertEquals("u4", FileMatch.best("मेरा resume", files).single().uri)
        assertEquals("u5", FileMatch.best("snake गेम", files).single().uri)
    }

    @Test fun latestOfAKind() {
        assertEquals("u2", FileMatch.best("सबसे नई फोटो", files).single().uri)
        assertEquals("u2", FileMatch.best("latest_photo", files).single().uri)
        assertEquals("u6", FileMatch.best("वीडियो", files).single().uri)
    }

    @Test fun unknownFileIsNotGuessed() {
        assertTrue(FileMatch.best("पासपोर्ट", files).isEmpty())
        assertTrue(FileMatch.best("", files).isEmpty())
    }

    @Test fun parserAndPlanner() {
        val ok = SocialRequestParser.parse(JSONObject().put("action", "send_file").put("contact", "राहुल").put("file", "आधार"), Platform.WHATSAPP)
        val r = (ok as SocialRequestParser.Parsed.Ok).r
        assertEquals(SocialAction.SEND_FILE, r.action)
        assertEquals("आधार", r.file)
        val bad = SocialRequestParser.parse(JSONObject().put("action", "send_file").put("contact", "राहुल"), Platform.WHATSAPP)
        assertTrue(bad is SocialRequestParser.Parsed.Err)
        val t = SocialPlanner.plan("t1", r, SharedItem("content://x", "application/pdf", 1, "Aadhar_Card_2024.pdf"), "919999999999", "Rahul")
        val open = t.open as OpenSpec.ShareMedia
        assertEquals("919999999999@s.whatsapp.net", open.jid)
        assertEquals("com.whatsapp", open.pkg)
        assertTrue(t.steps.single() is PlanStep.Final)        // भेजने से पहले "हाँ" (वापस नहीं होता)
        assertTrue(t.confirmText.contains("Rahul") && t.confirmText.contains("Aadhar_Card_2024.pdf"))
    }

    @Test fun phoneTaskRedirectsFilesToSendFile() {
        assertTrue(WaRoute.isFileSend("राहुल को WhatsApp पर आधार वाली PDF भेजो"))
        assertTrue(WaRoute.redirect("राहुल को WhatsApp पर आधार वाली PDF भेजो").contains("action=send_file"))
        assertTrue(WaRoute.redirect("राहुल को WhatsApp पर hi भेजो").contains("action=send_message"))
    }

    @Test fun wiring() {
        val agent = src("java/com/dk/assistant/agent/android/SocialAgent.kt")
        assertTrue(agent.contains("FileFinder.find(svc, req.file)"))
        assertTrue(agent.contains("putExtra(\"jid\", o.jid)"))
        assertTrue(src("java/com/dk/assistant/GeminiLiveSetup.kt").contains(".put(\"send_file\")"))
        assertTrue(src("res/xml/computer_files.xml").contains("<external-path"))
        assertTrue(src("AndroidManifest.xml").contains("MANAGE_EXTERNAL_STORAGE"))
    }
}
