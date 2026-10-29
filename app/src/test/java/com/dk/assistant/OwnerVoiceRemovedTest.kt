package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.5: "सिर्फ़ मेरी आवाज़ सुनो" option हटाया - सिर्फ़ उसकी लाइनें; Prefs/Settings/ui_en.tsv/Hey DK/wake words सब बचे रहें */
class OwnerVoiceRemovedTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    @Test fun optionGoneFromSettings() {
        val s = src("SettingsActivity.kt")
        assertFalse(s.contains("card(\"सिर्फ़ मेरी आवाज़ सुनो\""))
        assertFalse(s.contains("fun ownerVoiceCard(") || s.contains("fun enrollOwner(") || s.contains("fun testOwner("))
        assertFalse(s.contains("putBoolean(\"owner_voice_on\""))
        assertFalse(src("SettingsSearch.kt").contains("\"सिर्फ़ मेरी आवाज़ सुनो\""))
    }

    @Test fun neverGatesVoiceEvenIfOnBefore() {
        assertTrue(src("Prefs.kt").contains("fun ownerVoiceOn(c: Context) = false"))
        assertTrue(src("voice/OwnerVoice.kt").contains("Prefs.ownerVoiceOn(c)"))   // active() इसी से - Live/Accessibility दोनों में बंद
    }

    @Test fun nothingElseLost() {
        val s = src("SettingsActivity.kt")
        assertTrue(s.contains("heyDkCard(box)") && s.contains("enrollHeyDk") && s.contains("fun ownerDialog(") && s.contains("private var ownerJob"))
        assertTrue(s.lines().size > 1500)
        val p = src("Prefs.kt")
        assertTrue(p.contains("fun wakeWords(") && p.contains("fun heyDkPrint(") && p.lines().size > 400)
        assertTrue(f("assets/ui_en.tsv").readText().lines().size > 1000)
        val hd = src("voice/HeyDk.kt")
        assertTrue(hd.contains("if (!inSeg) {"))
        assertTrue(src("AgentRunner.kt").contains("Prefs.wakeWords(svc)"))
    }
}
