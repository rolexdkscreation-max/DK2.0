package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** मालिक (नवंबर 2026): ऑफलाइन में बार-बार माइक ऑन-ऑफ (बीप) नहीं - अपना माइक लगातार, सिर्फ़ आवाज़-कमांड. */
class OfflineListenTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun offlineBackupUsesCmdsOnly() {
        assertTrue(OfflineListen.cmdsOnly(backup = true, liveOn = true, online = false))
        assertFalse(OfflineListen.cmdsOnly(backup = true, liveOn = true, online = true))    // नेट है = पहले जैसा
        // v4.8: बिना नेट Live चालू (बैकअप न भी हो) = अपना माइक, लगातार
        assertTrue(OfflineListen.cmdsOnly(backup = false, liveOn = true, online = false))
        // v4.8: Live बंद पर wake वाला सुनना चालू = भी अपना माइक; DK बंद = नहीं
        assertTrue(OfflineListen.cmdsOnly(backup = false, liveOn = false, online = false, wakeOn = true))
        assertFalse(OfflineListen.cmdsOnly(backup = true, liveOn = false, online = false))
        assertFalse(OfflineListen.cmdsOnly(backup = false, liveOn = false, online = true, wakeOn = true))
    }

    @Test fun neverCutsWhileSpeaking() {
        assertFalse(OfflineListen.stopNow(online = true, liveOn = true, micMuted = false, inCall = false, capturing = true))
        assertTrue(OfflineListen.stopNow(online = true, liveOn = true, micMuted = false, inCall = false, capturing = false))
        assertTrue(OfflineListen.stopNow(online = false, liveOn = false, micMuted = false, inCall = false, capturing = false))
        assertTrue(OfflineListen.stopNow(online = false, liveOn = true, micMuted = true, inCall = false, capturing = false))
        assertTrue(OfflineListen.stopNow(online = false, liveOn = true, micMuted = false, inCall = true, capturing = false))
        // नेट नहीं, कुछ नहीं बदला = माइक चालू रहे
        assertFalse(OfflineListen.stopNow(online = false, liveOn = true, micMuted = false, inCall = false, capturing = false))
    }

    @Test fun hearRoutesOfflineBeforeGoogleBackup() {
        val s = src("DKAccessibilityService.kt")
        val off = s.indexOf("return hearOfflineCmds()")
        assertTrue(off > 0)
        assertTrue(off < s.indexOf("if (backupMode && DKState.live) return hearBackup()"))
        // बिना नेट Live जोड़ने के लिए माइक बंद-चालू नहीं
        assertTrue(s.contains("!callBusy() && !OfflineListen.cmdsOnly(backupMode"))
    }

    @Test fun offlineEarHasNoBeepingRecognizer() {
        val s = src("DKAccessibilityOffline.kt")
        val body = s.substring(s.indexOf("suspend fun DKAccessibilityService.hearOfflineCmds"))
        assertFalse(body.contains("hearGoogle("))
        assertFalse(body.contains("listenOnce("))
        assertTrue(body.contains("ear.nextUtterance()"))
        assertTrue(body.contains("VoiceCmds.match("))
        assertTrue(body.contains("runVoiceCmd(vc"))
    }
}
