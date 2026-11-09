package com.dk.assistant.studio

import com.dk.assistant.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): ऐप स्टूडियो कोडिंग एजेंट, DK कंप्यूटर और DK लाइव से जुड़ा */
class StudioLinkTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()
    private fun act(t: String) = StudioLink.parse(t)?.act

    @Test fun understandsStudioTalk() {
        assertEquals(StudioLink.Act.STATUS, act("स्टूडियो में क्या बन रहा है"))
        assertEquals(StudioLink.Act.STATUS, act("ऐप स्टूडियो में काम कहाँ तक पहुँचा?"))
        assertEquals(StudioLink.Act.STATUS, act("what is app studio building"))
        assertEquals(StudioLink.Act.STOP, act("स्टूडियो का काम रोको"))
        assertEquals(StudioLink.Act.OPEN, act("ऐप स्टूडियो खोलो"))
        assertEquals(StudioLink.Act.CHANGE, act("स्टूडियो वाले ऐप में बटन नीला कर दो"))
        assertEquals(StudioLink.Act.CHANGE, act("स्टूडियो वाले ऐप में login page बनाओ"))
        assertEquals(StudioLink.Act.BUILD, act("स्टूडियो में एक todo ऐप बनाओ"))
    }

    @Test fun otherStudiosUntouched() {
        assertNull(StudioLink.parse("Android Studio में project खोलो"))
        assertNull(StudioLink.parse("YouTube Studio खोलो और views बताओ"))
        assertNull(StudioLink.parse("यूट्यूब स्टूडियो में क्या चल रहा है"))
        assertNull(StudioLink.parse("एक todo ऐप बनाओ"))
        assertNull(StudioLink.parse("रिपो चेक करो"))
        assertNull(StudioLink.parse("studios near me"))
    }

    @Test fun snapshotOnlyFacts() {
        val none = StudioLink.text(null, null, null, false, emptyList(), emptyList(), false, "", emptyList(), null)
        assertTrue(none.contains("कोई project नहीं"))
        val s = StudioLink.text("todo ऐप", "वेबसाइट / वेब ऐप", "DK कंप्यूटर", true, listOf("📐 समझा", "🧠 code लिख रहा हूँ…", "📄 index.html (3 KB)"),
            listOf("index.html", "app.js"), true, "", listOf("todo ऐप", "notes"), "चल रहा है • 3 कदम • 12 सेकंड")
        assertTrue(s.contains("\"todo ऐप\"")); assertTrue(s.contains("DK कंप्यूटर")); assertTrue(s.contains("अभी काम चल रहा है"))
        assertTrue(s.contains("अभी का कदम: 📄 index.html")); assertTrue(s.contains("फ़ाइलें (2)")); assertTrue(s.contains("दूसरे project: notes"))
    }

    @Test fun liveNoteIsHonest() {
        val done = TaskStatus.Job(StudioLink.KEY, StudioLink.WHO, StudioLink.WHO_EN, "नया ऐप: todo", 0L, state = TaskStatus.State.DONE, result = "✅ तैयार")
        assertTrue(StudioLink.liveNote(done)!!.contains("FINISHED"))
        val bad = done.copy(state = TaskStatus.State.FAILED, result = "AI key नहीं")
        assertTrue(StudioLink.liveNote(bad)!!.contains("Do NOT say it is ready"))
        assertNull(StudioLink.liveNote(done.copy(key = TaskStatus.PC)))
    }

    @Test fun wiredEverywhere() {
        val session = src("studio/StudioSession.kt")
        assertTrue(session.contains("TaskStatus.start(StudioLink.KEY"))
        assertTrue(session.contains("TaskStatus.step(StudioLink.KEY"))
        assertTrue(session.contains("TaskStatus.end(StudioLink.KEY"))
        assertTrue(src("StatusNotify.kt").contains("StudioLink.KEY -> com.dk.assistant.studio.StudioActivity"))
        assertTrue(src("GeminiLive.kt").contains("StudioLink.liveNote(job)"))
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("\"name\", \"app_studio\"")); assertTrue(setup.contains("\"name\", \"look_screen\""))
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.contains("\"app_studio\" -> com.dk.assistant.studio.StudioLink.liveTool"))
        assertTrue(tools.contains("\"look_screen\" -> ScreenLook.liveTool"))
        assertTrue(tools.contains("StudioLink.fromLive(svc, t, com.dk.assistant.studio.StudioSession.WHO_COMPUTER"))
        assertTrue(tools.contains("StudioLink.fromLive(svc, t, com.dk.assistant.studio.StudioSession.WHO_CODER"))
        assertTrue(src("CodingActivity.kt").contains("StudioLink.handle(this, typed"))
        assertTrue(src("ComputerActivity.kt").contains("StudioLink.handle(this, t"))
        assertTrue(src("AgentRunner.kt").contains("StudioLink.parse(goal)"))
        val feats = com.dk.assistant.LiveConnect.features(false, emptyList(), false)
        assertTrue(feats.contains("app_studio")); assertTrue(feats.contains("look_screen"))
        assertTrue(com.dk.assistant.LeakedCall.TOOLS.containsAll(listOf("app_studio", "look_screen")))
        assertTrue("look_screen" in com.dk.assistant.LiveToolSlim.CORE); assertFalse("app_studio" in com.dk.assistant.LiveToolSlim.CORE)
        assertNotNull(TaskStatus.summary().takeIf { it.contains("App Studio") || it.contains("STATE") })
    }
}
