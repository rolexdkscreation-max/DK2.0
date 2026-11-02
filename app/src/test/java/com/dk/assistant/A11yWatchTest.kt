package com.dk.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026 (मालिक): Active apps से Stop के बाद "Service is not available" - पकड़ो, ठीक करो या एक टैप में बताओ */
class A11yWatchTest {
    private fun src(rel: String) = listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.exists() }.readText()

    @Test fun stateRules() {
        assertEquals(A11yWatch.State.RUNNING, A11yWatch.state(running = true, enabledInSettings = true, canWriteSecure = false))
        assertEquals(A11yWatch.State.OFF, A11yWatch.state(false, enabledInSettings = false, canWriteSecure = true))   // मालिक ने खुद बंद = छेड़ो नहीं
        assertEquals(A11yWatch.State.HEAL, A11yWatch.state(false, true, canWriteSecure = true))
        assertEquals(A11yWatch.State.ASK_USER, A11yWatch.state(false, true, canWriteSecure = false))
    }

    @Test fun notifyNotTooOften_andSaysWhy() {
        assertTrue(A11yWatch.shouldNotify(now = 1_000, lastNotified = 0))
        assertFalse(A11yWatch.shouldNotify(now = 1_000 + A11yWatch.NOTIFY_EVERY_MS - 1, lastNotified = 1_000))
        assertTrue(A11yWatch.shouldNotify(now = 1_000 + A11yWatch.NOTIFY_EVERY_MS, lastNotified = 1_000))
        assertTrue(A11yWatch.message(true).contains("Active apps"))
        assertTrue(A11yWatch.message(false).contains("OFF") && A11yWatch.message(false).contains("ON"))
        assertTrue(A11yWatch.userStopReason(10) && A11yWatch.userStopReason(11))
        assertFalse(A11yWatch.userStopReason(4))   // crash ≠ यूज़र का Stop
    }

    @Test fun wiredIn() {
        val m = src("AndroidManifest.xml")
        assertTrue(m.contains("android:name=\".A11yWatchJob\"") && m.contains("android:name=\".MicOffReceiver\""))
        val app = src("java/com/dk/assistant/DKApp.kt")
        assertTrue(app.contains("A11yWatchJob.schedule(this)") && app.contains("A11yWatch.check(this@DKApp"))
        assertTrue(src("java/com/dk/assistant/DKAccessibilityService.kt").contains("A11yWatch.cancel(this)"))
        val w = src("java/com/dk/assistant/A11yWatch.kt")
        assertTrue(w.contains("Setup.healAccessibility(app)"))            // सिर्फ़ पहले से ON हो तब
        assertTrue(w.contains(".setPersisted(true)"))
        // DK की notification में सिर्फ़ माइक बंद करने का बटन (पूरा ऐप नहीं मरता)
        assertTrue(src("java/com/dk/assistant/KeepAliveService.kt").contains("MicOffReceiver::class.java"))
        // स्क्रीन पर सच्ची हालत: Settings में ON पर रुकी = ⚠ (पहले ✓ दिखता था)
        assertTrue(src("java/com/dk/assistant/MainActivity.kt").contains("Setup.a11yMark(this) + \"फ़ोन कंट्रोल\""))
    }

    @Test fun noPcGuide() {
        assertTrue(A11yNoPc.STEPS.contains("Wireless debugging") && A11yNoPc.STEPS.contains("Shizuku") && A11yNoPc.STEPS.contains("aShell"))
        assertTrue(A11yNoPc.SHIZUKU_URL.startsWith("https://play.google.com/") && A11yNoPc.ASHELL_URL.startsWith("https://play.google.com/"))
        val s = src("java/com/dk/assistant/SettingsSystem.kt")
        assertTrue(s.contains("\"pm grant \$packageName android.permission.WRITE_SECURE_SETTINGS\""))
    }
}
