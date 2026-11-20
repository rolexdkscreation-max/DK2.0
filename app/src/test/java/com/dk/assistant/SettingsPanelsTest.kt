package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (मालिक): "मेरा DK" = प्रोफ़ाइल; DK लाइव पैनल (JARVIS, FRIDAY / VENOM, स्वभाव, याददाश्त, सच्चा दोस्त); आवाज़ अलग */
class SettingsPanelsTest {
    private fun cat(id: String) = SettingsSearch.CATEGORIES.first { it.first.first == id }

    @Test fun profileName_andPanels() {
        assertEquals("प्रोफ़ाइल", cat("cat_dk").first.second)
        assertTrue(listOf("profile", "lang").all { it in cat("cat_dk").second })
        assertEquals(listOf("persona", "memory"), cat("cat_live").second)
        assertEquals(listOf("voice", "st3"), cat("cat_voice").second)
        assertFalse("voice" in cat("cat_brain").second)
        assertEquals("AI दिमाग", cat("cat_brain").first.second)
    }

    @Test fun personaCards_inDkLive_voiceCardsInVoice() {
        for (c in listOf("JARVIS मोड", "FRIDAY / VENOM मोड", "DK का स्वभाव", "रोमांटिक (18+)"))
            assertEquals(c, "persona", SettingsSearch.ENTRIES.first { it.card == c }.section)
        for (c in listOf("Memory (याददाश्त)", "सच्चा दोस्त")) assertEquals(c, "memory", SettingsSearch.ENTRIES.first { it.card == c }.section)
        for (c in listOf("बोलने वाला इंजन (DK Live)", "सुनना (माइक)", "वॉइस गार्डियन")) assertEquals(c, "voice", SettingsSearch.ENTRIES.first { it.card == c }.section)
        assertEquals("persona", SettingsSearch.search("जार्विस").first { !it.section.startsWith("cat_") }.section)
    }

    @Test fun sourceSplit() {
        val v = SetSrc.all()
        val live = v.substringAfter("fun SettingsActivity.secDkLive() {").substringBefore("fun SettingsActivity.secVoice() {")
        assertTrue(live.contains("card(\"JARVIS मोड\"") && live.contains("card(\"FRIDAY / VENOM मोड\"") && live.contains("card(\"DK का स्वभाव\""))
        val voice = v.substringAfter("fun SettingsActivity.secVoice() {").substringBefore("fun SettingsActivity.secDkMemory() {")
        assertTrue(voice.contains("card(\"बोलने वाला इंजन (DK Live)\"") && !voice.contains("card(\"JARVIS मोड\""))
        val mem = v.substringAfter("fun SettingsActivity.secDkMemory() {")
        assertTrue(mem.contains("card(\"Memory (याददाश्त)\"") && mem.contains("card(\"सच्चा दोस्त\""))
        assertTrue(v.contains("\"persona\" -> { title.text = \"DK का स्वभाव और मोड\"; secDkLive() }"))
        assertTrue(v.contains("\"memory\" -> { title.text = \"याददाश्त और सच्चा दोस्त\"; secDkMemory() }"))
    }
}
