package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): ऐप के अंदर ओर्ब टैप = न "जी बॉस", न दोबारा जुड़ना. power बटन = पहले जैसा. */
class InvokeEntryTest {
    private fun src(p: String): String =
        listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun inAppConnectedLiveIsLeftAlone() {
        assertTrue(InvokeEntry.keepAsIs(liveOn = true, ready = true, running = true))
        assertTrue(InvokeEntry.keepAsIs(liveOn = true, ready = false, running = true))   // जुड़ रहा = दोबारा नहीं
        assertFalse(InvokeEntry.keepAsIs(liveOn = true, ready = false, running = false))
        assertFalse(InvokeEntry.keepAsIs(liveOn = false, ready = false, running = false))
    }

    @Test fun inAppChimeOnlyOnce() {
        assertTrue(InvokeEntry.chimeInApp(alreadyChimed = false))
        assertFalse(InvokeEntry.chimeInApp(alreadyChimed = true))
    }

    @Test fun wiring() {
        val s = src("DKAccessibilityService.kt")
        val app = s.substring(s.indexOf("fun openedInApp("), s.indexOf("fun openedInApp(") + 700)
        assertFalse(app.contains("InvokeAck.play(") || app.contains("InvokeAck.expect("))   // ऐप के अंदर "जी बॉस" कभी नहीं
        assertTrue(app.contains("InvokeEntry.keepAsIs(") && app.contains("InvokeEntry.chimeInApp("))
        // ऐप की आवाज़ स्क्रीन (ओर्ब टैप) = openedInApp; power बटन / assistant = invokedNow (जी बॉस वहीं)
        val v = src("VoiceActivity.kt")
        assertTrue(v.contains("instance?.openedInApp(") && !v.contains("invokedNow("))
        assertTrue(src("DKAssistActivity.kt").contains("invokedNow(\"assist\", session = true)"))
        assertTrue(src("DKVoiceInteractionSessionService.kt").contains("session = true"))
        val inv = s.substring(s.indexOf("fun invokedNow("), s.indexOf("fun invokedNow(") + 900)
        assertTrue(inv.contains("InvokeAck.play("))
    }
}
