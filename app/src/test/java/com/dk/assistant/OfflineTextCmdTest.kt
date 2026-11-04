package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): अपडेट के बाद ऑफलाइन में "DK फोन लॉक करो / अनलॉक करो / कहाँ हो" नहीं सुन रहा था -
 * #212 ने सिर्फ़ सिखाए आवाज़-नमूने देखे. अब फ़ोन की on-device पहचान (अपने माइक की पकड़ी बात, बिना बीप) से लिखी बात भी.
 */
class OfflineTextCmdTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    private val names = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private val lock = PhoneLockVoice.Cfg(
        lockOn = true, unlockOn = true,
        names = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_NAMES),
        lock = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_LOCK),
        unlock = PhoneLockVoice.list(PhoneLockVoice.DEFAULT_UNLOCK),
    )

    @Test fun offlineCommandsPass() {
        assertTrue(OfflineListen.keepText("डीके फोन लॉक करो", names, false, lock))
        assertTrue(OfflineListen.keepText("डीके फोन अनलॉक करो", names, false, lock))
        assertTrue(OfflineListen.keepText("डीके कहाँ हो", names, false, lock))
        assertTrue(OfflineListen.keepText("डीके नहीं दिख रहे हो", names, false, lock))
        assertTrue(OfflineListen.keepText("मिल गया", names, true, lock))   // फ़ोन बज रहा हो तब
    }

    @Test fun everythingElseIgnored() {
        assertFalse(OfflineListen.keepText("आज मौसम कैसा है", names, false, lock))
        assertFalse(OfflineListen.keepText("डीके गाना चलाओ", names, false, lock))
        assertFalse(OfflineListen.keepText("फोन लॉक करो", names, false, lock))   // नाम बिना (पास में कोई और) - नहीं
        assertFalse(OfflineListen.keepText("कहाँ हो", names, false, lock))
        assertFalse(OfflineListen.keepText("मिल गया", names, false, lock))       // फ़ोन बज नहीं रहा
        assertFalse(OfflineListen.keepText(null, names, false, lock))
        assertFalse(OfflineListen.keepText("", names, false, lock))
    }

    @Test fun offlineEarUsesOnDeviceTextWithoutOwnMic() {
        val s = src("DKAccessibilityOffline.kt")
        assertTrue(s.contains("OfflineStt.transcribe(this, pcm)"))
        assertTrue(s.contains("OfflineListen.keepText("))
        val stt = src("OfflineStt.kt")
        assertTrue(stt.contains("EXTRA_AUDIO_SOURCE"))       // अपना माइक नहीं खोलता = बीप नहीं
        assertTrue(stt.contains("EXTRA_PREFER_OFFLINE"))
    }
}
