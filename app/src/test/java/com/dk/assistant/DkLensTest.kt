package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): कैमरा = "DK लेंस", DKAI होम पर लेंस बटन, खुलते ही जो दिखे तुरंत बोले */
class DkLensTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun lensWordsPickSide() {
        assertEquals(false, LiveConnect.wantFront("", "DK लेंस खोलो और बैक करो"))
        assertEquals(false, LiveConnect.wantFront("", "बैक लेंस से मुझे देखो"))
        assertEquals(true, LiveConnect.wantFront("", "DK लेंस चालू करके मुझे देखो"))
        assertEquals(true, LiveConnect.wantFront("", "फ्रंट लेंस खोलो"))
    }

    @Test fun describesRightAway() {
        assertTrue(LiveVision.LENS_SEE_NOW.contains("RIGHT NOW, without waiting for the user"))
        val v = src("LiveVision.kt")
        assertTrue(v.contains("if (ok && describeFirst && cameraOn) { describeFirst = false; note(LENS_SEE_NOW) }"))
        assertTrue(src("GeminiLiveSetup.kt").contains("DK Lens (DK लेंस / डीके लेंस"))
    }

    @Test fun homeHasLensButton() {
        val d = src("dkai/DkaiScreen.kt")
        // 23 नवंबर 2026 (मालिक): ऊपर का कैमरा हटा - लेंस नीचे के स्कैन टैब से उसी जगह खुलता है
        assertFalse(d.contains("headIcon(DkaiIcon.Kind.CAMERA, \"DK लेंस"))
        assertTrue(d.contains("ScanPanel(it) { startLive() }"))
        val sp = src("dkai/ScanPanel.kt")
        assertTrue(sp.contains("LiveVision.setCamera(act, true, if (front) \"front\" else \"back\")"))
        // 23 नवंबर 2026 (मालिक): स्कैन टैब चुना = पुरानी आवाज़ वाली विंडो कभी नहीं (पल भर रुकी स्क्रीन पर भी)
        assertTrue(src("LiveVision.kt").contains("!com.dk.assistant.dkai.ScanPanel.onScreen()"))
        assertFalse(d.contains("VoiceActivity.WANT_CAMERA"))
        assertTrue(src("DKAccessibilityYt.kt").contains(".putExtra(VoiceActivity.EXTRA_FRONT, front)"))
        assertTrue(src("VoiceActivity.kt").contains("if (i.getBooleanExtra(EXTRA_FRONT, false)) \"front\" else \"back\""))
    }
}
