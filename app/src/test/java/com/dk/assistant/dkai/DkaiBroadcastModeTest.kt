package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 13 नवंबर 2026 (मालिक): Broadcast मोड भी Chat / Builder / Pipeline जैसा - चुनते ही अलग पन्ना नहीं खुले */
class DkaiBroadcastModeTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()

    @Test fun pickingBroadcastOnlySwitchesMode() {
        val sheets = src("dkai/DkaiSheets.kt")
        assertFalse(sheets.contains("DkaiBroadcast(s).open()"))
        assertTrue(sheets.contains("s.pickMode(m)"))
    }

    @Test fun sendingInBroadcastModeStartsBroadcast() {
        val sc = src("dkai/DkaiScreen.kt")
        assertFalse(sc.contains("DkaiMode.CHAT, DkaiMode.BROADCAST ->"))
        // 14 नवंबर 2026 (मालिक: "जो स्टोरी बनेगी, वहीं पे वही स्क्रीन पे बनेगी"): अलग पन्ने की जगह होम पर Broadcast कार्ड -
        // भेजते ही वही Broadcast काम (रिसर्च -> स्क्रिप्ट -> आवाज़) शुरू होता है, बस उसी स्क्रीन पर
        assertTrue(sc.contains("DkaiMode.BROADCAST -> cast.start(t)"))
        assertTrue(sc.contains("DkaiMode.BROADCAST -> \"Broadcast topic"))
        val b = src("dkai/DkaiBroadcast.kt")
        assertTrue(b.contains("fun open(topic: String? = null)"))
        assertTrue(b.contains("prompt.setText(t)"))
    }
}
