package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक: "UI कस्टमाइज़ेशन की थीम गैलरी, डार्क नेवी / AMOLED काला और मुख्य रंग पूरी तरह हटाओ"):
 * (पहले ThemePacksTest - वह फ़ीचर हट गया.) अब जाँच: तीनों कहीं नहीं, रंग सिर्फ़ Settings → Theme से.
 */
class ThemeGalleryRemovedTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    @Test fun galleryCodeAndRepoThemesGone() {
        assertFalse(File("src/main/java/com/dk/assistant/ui/ThemePacks.kt").exists())
        assertFalse(File("../themes").exists())
        val all = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (k in listOf("ThemePacks.", "themeGallery()", "THEME_AMOLED", "NeonTheme.Accent")) assertFalse(k, all.contains(k))
    }

    @Test fun uiSectionHasNoOldThemeCards() {
        val look = src("SettingsLook.kt")
        for (k in listOf("card(\"थीम गैलरी\"", "card(\"थीम\"", "card(\"मुख्य रंग (Accent)\"", "T.THEMES", "डार्क नेवी", "AMOLED"))
            assertFalse(k, look.contains(k))
        // बाक़ी दिखावट वैसी ही
        for (k in listOf("card(\"डार्क / लाइट मोड\"", "card(\"DKAI का orb\"", "card(\"एनिमेशन\"", "card(\"तैरता ORB\"", "resetAppearance(this)"))
            assertTrue(k, look.contains(k))
        val search = SettingsSearch.ENTRIES.map { it.card }
        for (k in listOf("थीम गैलरी", "मुख्य रंग (Accent)")) assertFalse(k, search.contains(k))
        assertFalse(SettingsSearch.ENTRIES.any { it.section == "ui" && it.card == "थीम" })
    }

    @Test fun newThemePageStaysAndSettingsBackdropStays() {
        assertTrue(SetSrc.all().contains("\"theme\" -> { title.text = \"Theme\"; secTheme() }"))
        assertTrue(File("src/main/res/layout/activity_settings.xml").readText().contains("AuroraView"))
        assertTrue(File("src/main/res/drawable-nodpi/dk_theme_bg.webp").isFile)
    }
}
