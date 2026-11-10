package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक): "सिंगल key वाले option में एक key पेस्ट कर दी तो सारे काम होंगे, जितने भी DK में हैं".
 * एक key = बात, चैट, खोज, फ़ोन, फ़ोटो, कोडिंग एजेंट, स्टोरी मोड, DK कंप्यूटर, वेबसाइट बिल्डर - सब.
 */
class OneKeyAllWorkTest {

    @Test fun gate_neverBlocksWhenOneKeyIsPasted() {
        for (w in OneKeyGate.Work.values()) {
            assertFalse(w.hi, OneKeyGate.blocked(oneKeyOn = true, w = w, keyBlank = false))
            assertTrue(w.hi, OneKeyGate.blocked(oneKeyOn = true, w = w, keyBlank = true))
        }
        assertEquals(setOf("coding_task", "story_task", "computer_task", "build_website"), OneKeyGate.LIVE_TOOLS.keys)
    }

    @Test fun gateMessage_saysPasteTheOneKey_notTurnItOff() {
        for (w in OneKeyGate.Work.values()) {
            val m = OneKeyGate.message(w)
            assertTrue(m.contains(w.hi) && m.contains("एक key से सब") && m.contains("पेस्ट"))
            assertTrue(m.contains("सब उसी एक key से"))
            val n = OneKeyGate.liveNote(w)
            assertTrue(n.startsWith("not_started_no_key:") && n.contains("Do NOT say it is done"))
        }
    }

    @Test fun oneKeyIsUsedForGemini() {
        assertEquals("AIzaONE", ApiScopes.oneKeyFor("gemini", "AIzaONE"))
    }

    @Test fun websiteBuilder_ownKeyFirst_elseOneKey() {
        // अपनी key डली = वही (एक-key मोड हो या न हो)
        assertEquals(listOf("F1", "F2"), Prefs.forgeKeysFor(listOf("F1", " F2 "), true, "ONE"))
        assertEquals(listOf("F1"), Prefs.forgeKeysFor(listOf("F1", "F1"), true, "ONE"))
        // अपनी नहीं + एक-key मोड चालू = वही एक key
        assertEquals(listOf("ONE"), Prefs.forgeKeysFor(listOf("", " "), true, " ONE "))
        // एक-key मोड बंद = पहले जैसा, सिर्फ़ अपनी key (खाली = नहीं)
        assertEquals(emptyList<String>(), Prefs.forgeKeysFor(listOf("", ""), false, "ONE"))
        // एक-key मोड में key भी नहीं = खाली (तब Gate साफ़ बताएगा)
        assertEquals(emptyList<String>(), Prefs.forgeKeysFor(listOf("", ""), true, ""))
    }

    @Test fun texts_noLongerSayOneKeyIsOnlyForTalk() {
        val brain = src("BrainManager.kt")
        assertTrue(brain.contains("ONE KEY MODE"))
        assertFalse(brain.contains("do NOT run on one key"))
        assertTrue(brain.contains("story_task, coding_task, computer_task and build_website - call the tool"))
        val settings = src("SettingsBrain.kt")
        assertTrue(settings.contains("Gemini API Key (सारे काम इसी से)"))
        assertFalse(settings.contains("बात + चैट इसी से"))
        assertFalse(settings.contains("वेबसाइट के लिए इसे बंद करके अलग-अलग keys डालें"))
        assertFalse(src("LiveConnect.kt").contains("ONLY for talking"))
        assertTrue(src("Prefs.kt").contains("forgeKeysFor(listOf(FORGE_KEY, FORGE_KEY2)"))
    }

    private fun src(name: String): String = listOf(java.io.File("src/main/java/com/dk/assistant/$name"), java.io.File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
}
