package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkAwakeTest {
    @Test fun onlyTalking_screenMayTurnOff() {
        assertFalse(WorkAwakeRules.working(false, Mode.LISTENING, 0))
        assertFalse(WorkAwakeRules.working(false, Mode.SPEAKING, 0))
        assertFalse(WorkAwakeRules.working(false, Mode.IDLE, 0))
        // छोटा सवाल-जवाब (AgentRunner busy पर 4 सेकंड से कम, WORKING नहीं) = बात
        assertFalse(WorkAwakeRules.working(true, Mode.THINKING, 0, busyMs = 1_500))
    }

    @Test fun anyPhoneWork_keepsScreenOn() {
        assertTrue(WorkAwakeRules.working(true, Mode.WORKING, 0, busyMs = 100))
        assertTrue(WorkAwakeRules.working(true, Mode.THINKING, 0, busyMs = 5_000))
        assertTrue(WorkAwakeRules.working(false, Mode.LISTENING, 1))      // सोशल रिव्यू चल रहा, Live सुन रहा
        assertTrue(WorkAwakeRules.working(false, Mode.WORKING, 0, modeOnlyMs = 10_000))
    }

    @Test fun stuckWorkingMode_doesNotKeepScreenForever() {
        assertFalse(WorkAwakeRules.working(false, Mode.WORKING, 0, modeOnlyMs = WorkAwakeRules.MODE_ONLY_MAX_MS + 1))
    }

    @Test fun remoteJobs_doNotCount() {
        assertTrue(TaskStatus.PC in WorkAwakeRules.REMOTE_KEYS && TaskStatus.CODER in WorkAwakeRules.REMOTE_KEYS)
        assertFalse("insights" in WorkAwakeRules.REMOTE_KEYS)
    }

    @Test fun label_showsRealStep() {
        assertEquals("DK: इंस्टाग्राम: रील 2 खोल रही हूँ", WorkAwakeRules.label("इंस्टाग्राम: रील 2 खोल रही हूँ", "x"))
        assertEquals("DK: ऐप खोल रहा है", WorkAwakeRules.label("", "ऐप खोल रहा है"))
        assertEquals("DK काम कर रहा है…", WorkAwakeRules.label(" ", ""))
    }

    @Test fun banner_notOverOwnApp() {
        assertTrue(WorkAwakeRules.showBanner(true, "com.instagram.android", "com.dk.assistant"))
        assertFalse(WorkAwakeRules.showBanner(true, "com.dk.assistant", "com.dk.assistant"))
        assertFalse(WorkAwakeRules.showBanner(false, "com.instagram.android", "com.dk.assistant"))
    }

    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    @Test fun overlay_keepsScreenOn_notTouchable_wired() {
        val w = src("WorkAwake.kt")
        assertTrue(w.contains("FLAG_KEEP_SCREEN_ON"))
        assertTrue(w.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(w.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue(w.contains("IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertFalse("wake lock नहीं", w.contains("newWakeLock"))
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("workAwake = WorkAwake(this).also { it.start() }"))
        assertTrue(s.contains("workAwake?.release()"))
    }

    @Test fun socialReview_onScreen_noConnectorNeeded() {
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("STARTED ON SCREEN"))
        assertTrue(a.contains("No connector is needed"))
        assertFalse(a.contains("STARTED (background"))
        assertTrue(a.contains("wakeForScreen(svc)"))
        assertFalse("बिना कनेक्टर = गलती नहीं", a.contains("का API कनेक्टर जुड़ा नहीं (सेटिंग्स"))
        assertTrue(src("GeminiLiveSetup.kt").contains("NO connector is needed"))
    }
}
