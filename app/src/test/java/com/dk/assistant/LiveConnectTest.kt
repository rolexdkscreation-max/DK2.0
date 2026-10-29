package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveConnectTest {

    @Test fun features_listAllAgentsAndTools() {
        val f = LiveConnect.features(autoOn = false, autoKinds = emptyList(), oneKey = true)
        listOf("phone_task", "whatsapp_task", "phone_call", "social_media_task", "coding_task", "computer_task",
            "build_website", "story_task", "teacher_mode", "camera", "screen_share", "web_search", "task_status").forEach {
            assertTrue("missing $it", f.contains(it))
        }
        listOf("Manager", "Planner", "Coder", "Reviewer", "Tester", "Debugger", "Git", "QA", "Deployment").forEach { assertTrue(f.contains(it)) }
        assertTrue(f.contains("Autonomous Mode: OFF"))
        assertTrue(f.contains("one all-rounder key"))
    }

    @Test fun features_autonomousOnShowsKinds() {
        val f = LiveConnect.features(autoOn = true, autoKinds = listOf("WhatsApp मैसेज", "YouTube पोस्ट"), oneKey = false)
        assertTrue(f.contains("Autonomous Mode: ON for: WhatsApp मैसेज, YouTube पोस्ट"))
        assertFalse(f.contains("one all-rounder key"))
    }

    @Test fun storyMinutes_snapsToScreenLengths() {
        assertEquals(10, LiveConnect.storyMinutes(0))
        assertEquals(10, LiveConnect.storyMinutes(5))
        assertEquals(20, LiveConnect.storyMinutes(20))
        assertEquals(25, LiveConnect.storyMinutes(24))
        assertEquals(30, LiveConnect.storyMinutes(60))
    }

    @Test fun storyDoneNote_onlyReadyWhenAllParts() {
        assertTrue(LiveConnect.storyDoneNote("माँ का त्याग", 4, 4, null).contains("really finished"))
        val half = LiveConnect.storyDoneNote("", 2, 4, "भाग 3 नहीं लिख पाया")
        assertTrue(half.contains("NOT complete") && half.contains("2 of 4") && half.contains("Do NOT say the story is ready"))
    }

    @Test fun webDoneNote_truthful() {
        assertTrue(LiveConnect.webDoneNote(true, "cafe", null).contains("really finished"))
        assertTrue(LiveConnect.webDoneNote(false, "cafe", "no internet").contains("Do NOT say it is ready"))
    }

    @Test fun wantFront_meIsFront_thingIsBack() {
        assertEquals(true, LiveConnect.wantFront("", "DK मुझे देखो"))
        assertEquals(true, LiveConnect.wantFront("", "मेरा चेहरा देखो कैसा लग रहा है"))
        assertEquals(true, LiveConnect.wantFront("", "mujhe dekho"))
        assertEquals(false, LiveConnect.wantFront("", "बैक कैमरा करके ये देखो"))
        assertEquals(false, LiveConnect.wantFront("", "ये देखो क्या है"))
        assertNull(LiveConnect.wantFront("", "कैमरा चालू करो"))
        // model का facing पहले
        assertEquals(false, LiveConnect.wantFront("back", "मुझे देखो"))
        assertEquals(true, LiveConnect.wantFront("front", "ये देखो"))
    }

    private fun src(p: String): String {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/$p").exists() && !File(d, p).exists()) d = d.parentFile
        return (File(d, "app/$p").takeIf { it.exists() } ?: File(d, p)).readText()
    }

    @Test fun liveDeclaresNewTools_andHomeHasButtons() {
        val g = src("src/main/java/com/dk/assistant/GeminiLive.kt")
        listOf("dk_features", "story_task", "teacher_mode").forEach {
            assertTrue(g.contains("put(\"name\", \"$it\")") && g.contains("\"$it\" ->"))
        }
        val xml = src("src/main/res/layout/activity_main.xml")
        assertTrue(xml.contains("@+id/btnHomeCam") && xml.contains("@+id/btnHomeShare"))
        val v = src("src/main/java/com/dk/assistant/VoiceActivity.kt")
        assertFalse(v.contains("top.visibility = android.view.View.GONE"))
    }
}
