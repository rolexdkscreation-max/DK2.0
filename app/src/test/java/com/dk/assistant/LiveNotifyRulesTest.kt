package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 12 नवंबर 2026: DK लाइव minimize -> notification में "कॉल काटें" + "म्यूट" */
class LiveNotifyRulesTest {
    @Test fun shownOnlyWhenLiveAndOutside() {
        assertTrue(LiveNotifyRules.wanted(live = true, inApp = false))
        assertFalse(LiveNotifyRules.wanted(live = true, inApp = true))
        assertFalse(LiveNotifyRules.wanted(live = false, inApp = false))
    }

    @Test fun labels() {
        assertEquals("कॉल काटें", LiveNotifyRules.END_LABEL)
        assertEquals("म्यूट", LiveNotifyRules.muteLabel(false))
        assertEquals("माइक चालू करें", LiveNotifyRules.muteLabel(true))
        assertTrue(LiveNotifyRules.text(true, Mode.LISTENING).contains("म्यूट"))
        assertEquals(IslandCore.modeHi(Mode.SPEAKING), LiveNotifyRules.text(false, Mode.SPEAKING))
    }

    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    @Test fun receiverInManifest_andWired() {
        assertTrue(src("src/main/AndroidManifest.xml").contains("android:name=\".LiveNotifyReceiver\""))
        val a = src("src/main/java/com/dk/assistant/DKAccessibilityService.kt")
        assertTrue(a.contains("LiveNotify.install(this)") && a.contains("LiveNotify.sync(this)"))
    }
}
