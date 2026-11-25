package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक: "DK लेंस कभी-कभी नहीं खुलता") */
class LensRetryTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun delaysGrow() {
        assertEquals(500L, LensRetry.delayMs(0))
        assertTrue(LensRetry.delayMs(1) > LensRetry.delayMs(0))
        assertEquals(LensRetry.delayMs(2), LensRetry.delayMs(9))
    }

    @Test fun reasonsInHindi() {
        assertTrue(LensRetry.reason("CAMERA_IN_USE").contains("दूसरा ऐप"))
        assertTrue(LensRetry.reason("Camera disabled by policy").contains("बंद कर रखा"))
        assertTrue(LensRetry.reason("SecurityException: permission").contains("इजाज़त"))
        assertTrue(LensRetry.reason(null).contains("स्कैन फिर दबाएँ"))
        assertEquals("x", LensRetry.reason("x"))
    }

    @Test fun cameraRetriesAndPanelWaitsForLive() {
        val c = src("CameraFeed.kt")
        assertTrue(c.contains("RETRIES"))
        assertTrue(c.contains("LensRetry.reason("))
        val p = src("dkai/ScanPanel.kt")
        assertTrue(p.contains("pendingFront"))                 // Live जुड़ते ही लेंस खुले
        val d = src("dkai/DkaiScreen.kt")
        assertTrue(d.contains("scanRetry"))                    // panel बनाने में गड़बड़ = एक बार फिर
    }
}
