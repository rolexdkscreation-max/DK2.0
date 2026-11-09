package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (09 नवंबर 2026): "DK / DK लाइव जुड़ा रहे - मीडिया वॉल्यूम न बढ़े न घटे; कॉल / रिंगटोन कम हो तो फुल,
 * कभी कम नहीं; बोलने पर ही बदले."
 */
class VolumeKeepTest {
    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }
        return f.readText()
    }

    @Test fun ringLowGoesFull() { assertEquals(7, VolumeKeep.upTarget(2, 7)) }
    @Test fun ringFullStays() { assertEquals(7, VolumeKeep.upTarget(7, 7)) }
    @Test fun neverLower() {
        for (max in 1..15) for (cur in 0..max) assertTrue(VolumeKeep.upTarget(cur, max) >= cur)
        assertEquals(9, VolumeKeep.upTarget(9, 7))     // फ़ोन ने max से ऊपर बताया तब भी कम नहीं
    }
    @Test fun silentModeUntouched() { assertEquals(0, VolumeKeep.upTarget(0, 7, allowed = false)) }

    @Test fun mediaChangedByDkGoesBack() { assertEquals(8, VolumeKeep.mediaRestore(8, 15, userAsked = false)) }
    @Test fun mediaLoweredByDkGoesBack() { assertEquals(8, VolumeKeep.mediaRestore(8, 3, userAsked = false)) }
    @Test fun mediaSameNothing() { assertNull(VolumeKeep.mediaRestore(8, 8, userAsked = false)) }
    @Test fun userCommandRespected() { assertNull(VolumeKeep.mediaRestore(8, 15, userAsked = true)) }

    @Test fun userWindow() {
        VolumeKeep.markUserAsked(1_000L)
        assertTrue(VolumeKeep.userAsked(1_000L + 100))
        assertFalse(VolumeKeep.userAsked(1_000L + VolumeKeep.USER_WINDOW_MS + 1))
    }

    @Test fun wired() {
        assertTrue(src("DKAccessibilityCalls.kt").contains("VolumeKeep.raiseForCall(this)"))
        assertTrue(src("DeviceActions.kt").contains("VolumeKeep.markUserAsked()"))
        val sp = src("Speaker.kt")
        assertEquals(2, Regex("VolumeKeep\\.keepMedia\\(audio\\)").findAll(sp).count())
        val cs = src("CallSpeaker.kt")
        assertTrue(cs.contains("fun pause(c: Context? = null)"))
        assertTrue(cs.contains("VolumeKeep.keepMedia(m) { applyOn(m, c, bt) }"))
        assertTrue(src("GeminiLive.kt").contains("CallSpeaker.pause(svc)"))
        // ring/call सिर्फ़ ऊपर: VolumeKeep में STREAM_MUSIC पर setStreamVolume सिर्फ़ वापस-लाने वाली जगह
        assertEquals(1, Regex("setStreamVolume\\(AudioManager\\.STREAM_MUSIC").findAll(src("VolumeKeep.kt")).count())
        assertFalse(VolumeRule.AUTO_CHANGE)
    }
}
