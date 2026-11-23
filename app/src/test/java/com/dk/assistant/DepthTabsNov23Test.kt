package com.dk.assistant

import com.dk.assistant.dkai.DkaiHomeCore
import com.dk.assistant.dkai.HomeAvatarView
import com.dk.assistant.ui.ClayPopup
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 23 नवंबर 2026 (मालिक): ज़्यादा उभार, साफ़ पॉपअप, पूरी स्क्रीन कैरेक्टर, साफ़ टॉप बार, समय+नाम अभिवादन, एक ही जगह टैब */
class DepthTabsNov23Test {
    private fun src(p: String): String {
        val f = listOf("src/main/java/com/dk/assistant/$p", "app/src/main/java/com/dk/assistant/$p").map(::File).first { it.exists() }
        return f.readText()
    }

    @Test fun greetingFollowsClock() {
        assertEquals("Good morning,", DkaiHomeCore.greeting(8))
        assertEquals("Good afternoon,", DkaiHomeCore.greeting(14))
        assertEquals("Good evening,", DkaiHomeCore.greeting(18))
        assertEquals("Good night,", DkaiHomeCore.greeting(22))
        assertEquals("Good night,", DkaiHomeCore.greeting(2))
    }

    @Test fun greetingUsesSavedName() {
        assertEquals("boss", DkaiHomeCore.greetName(null))
        assertEquals("boss", DkaiHomeCore.greetName("   "))
        assertEquals("Rahul", DkaiHomeCore.greetName("  Rahul "))
        assertEquals("Good evening, Rahul", DkaiHomeCore.greetLine(19, "Rahul"))
        assertEquals("Good morning, boss", DkaiHomeCore.greetLine(9, ""))
        assertTrue(src("dkai/DkaiScreen.kt").contains("DkaiHomeCore.greetName(try { Prefs.userName(c) }"))
    }

    @Test fun depthIsStrongerEverywhere() {
        assertTrue(ClayTheme.LIFT > 1f); assertTrue(ClayTheme.SINK > 1f)
        for (th in ClayTheme.ALL) {
            val t = ClayTheme.tokens(th)
            assertTrue("${th.name} छाया bg से गहरी", NeonTheme.luminance(t.shadowDark) < NeonTheme.luminance(th.bg))
            assertTrue("${th.name} छाया ठोस", (t.shadowDark ushr 24) >= 0xC0)
            assertTrue("${th.name} चमक bg से हल्की", NeonTheme.luminance(t.shadowLight or (0xFF shl 24)) > NeonTheme.luminance(th.bg))
        }
        val d = src("ui/ClayDrawables.kt")
        assertTrue(d.contains("ClayTheme.LIFT")); assertTrue(d.contains("ClayTheme.SINK"))
    }

    @Test fun popupTextReadableInEveryTheme() {
        for (th in ClayTheme.ALL) {
            val k = ClayTheme.tokens(th)
            // सफ़ेद-पर-सफ़ेद ("Revoke this key?") और काला-पर-काला - दोनों ठीक हों
            for (bad in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), k.surface)) {
                val fixed = ClayPopup.fixColor(bad, false, k)
                assertTrue("${th.name} लिखावट", NeonTheme.contrast(fixed, k.surface) >= 4.5)
            }
            val btn = ClayPopup.fixColor(0xFFFFFFFF.toInt(), true, k)
            assertTrue("${th.name} बटन", NeonTheme.contrast(btn, k.surface) >= 3.0)
        }
        assertFalse(ClayPopup.readable(0x40000000, 0xFFFFFFFF.toInt()))
        assertTrue(ClayPopup.readable(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertTrue(ClayPopup.isPopupType(WindowManager.LayoutParams.TYPE_APPLICATION))
        assertFalse(ClayPopup.isPopupType(WindowManager.LayoutParams.TYPE_BASE_APPLICATION))
        assertTrue(ClayPopup.DIM >= 0.5f)
        assertTrue(src("DKApp.kt").contains("ClayPopup.install()"))
    }

    @Test fun characterCoversScreen() {
        assertEquals(4f, HomeAvatarView.coverScale(200f, 400f, 100f, 100f), 0.001f)   // ऊँचाई भरे, कोई खाली पट्टी नहीं
        assertEquals(3f, HomeAvatarView.coverScale(300f, 100f, 100f, 100f), 0.001f)
        assertEquals(1f, HomeAvatarView.coverScale(300f, 100f, 0f, 100f), 0.001f)
    }

    @Test fun topBarCleanAndTabsInPlace() {
        val d = src("dkai/DkaiScreen.kt")
        val head = d.substringAfter("private fun buildHeader").substringBefore("\n    private fun ")
        for (gone in listOf("DkaiIcon.Kind.CAMERA", "\"+ New\"", "DkaiIcon.Kind.SHARE", "DkaiIcon.Kind.COPY")) assertFalse("$gone टॉप बार में नहीं", head.contains(gone))
        assertTrue(head.contains("PlainLook.SKIP"))
        assertTrue(d.contains("fun showTab("))
        assertTrue(d.contains("MemoryPanel("))
        assertTrue(src("ui/HomeTabs.kt").contains("LiquidTabs"))
    }
}
