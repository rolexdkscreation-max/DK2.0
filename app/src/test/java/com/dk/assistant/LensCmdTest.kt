package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 21 नवंबर 2026 (मालिक): "DK स्कैन खोलो" = DK लेंस; बिना बोले पीछे वाला, "सामने करके" = आगे वाला */
class LensCmdTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun scanOpensLensBackByDefault() {
        assertEquals(LensCmd.Cmd(on = true, front = false), LensCmd.parse("डीके स्कैन खोलो"))
        assertEquals(LensCmd.Cmd(on = true, front = false), LensCmd.parse("DK scan kholo"))
        assertEquals(LensCmd.Cmd(on = true, front = false), LensCmd.parse("स्कैन चालू करो"))
        assertEquals(LensCmd.Cmd(on = true, front = false), LensCmd.parse("DK लेंस खोलो"))
        assertEquals(LensCmd.Cmd(on = true, front = false), LensCmd.parse("डीके स्कैन बैक करके खोलो"))
    }

    @Test fun samneMeansFront() {
        assertEquals(LensCmd.Cmd(on = true, front = true), LensCmd.parse("डीके स्कैन सामने की तरफ करके खोलो"))
        assertEquals(LensCmd.Cmd(on = true, front = true), LensCmd.parse("स्कैन फ्रंट कैमरा से खोलो"))
        assertEquals(LensCmd.Cmd(on = true, front = true), LensCmd.parse("dk scan front open karo"))
        assertEquals(LensCmd.Cmd(on = true, front = false), LensCmd.parse("सामने वाला नहीं, स्कैन बैक करके खोलो"))
    }

    @Test fun offAndOthersUntouched() {
        assertEquals(false, LensCmd.parse("डीके स्कैन बंद करो")!!.on)
        assertNull(LensCmd.parse("QR स्कैन करो"))
        assertNull(LensCmd.parse("गूगल लेंस खोलो"))
        assertNull(LensCmd.parse("फोनपे से स्कैन करके पेमेंट करो"))
        assertNull(LensCmd.parse("फेस स्कैन करो"))
        assertNull(LensCmd.parse("स्कैन खोलो और YouTube चलाओ"))
        assertNull(LensCmd.parse("स्कैन करके बताओ ये क्या है"))
        assertNull(LensCmd.parse("यूट्यूब खोलो"))
    }

    @Test fun samneIsFrontForLiveToo() {
        assertEquals(true, LiveConnect.wantFront("", "डीके स्कैन सामने की तरफ करके खोलो"))
        assertEquals(false, LiveConnect.wantFront("", "स्कैन बैक करके खोलो"))
    }

    @Test fun wiredEverywhere() {
        assertTrue(src("LocalRouter.kt").contains("LensCmd.parse(raw)"))
        assertTrue(src("GeminiLiveQueue.kt").contains("LensCmd.parse(req)"))
        assertTrue(src("DKAccessibilityService.kt").contains("LensCmd.TYPE -> openLens("))
        assertTrue(src("GeminiLiveSetup.kt").contains("DK Scan (DK स्कैन / डीके स्कैन"))
        // बिना बोले = पीछे वाला (पहले पिछली बार वाला)
        assertTrue(src("LiveVision.kt").contains("?: (if (cameraOn || cameraWanted) cameraFront else false)"))
    }
}
