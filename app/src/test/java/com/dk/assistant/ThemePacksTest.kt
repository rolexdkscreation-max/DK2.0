package com.dk.assistant

import com.dk.assistant.ui.NeonTheme
import com.dk.assistant.ui.ThemePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): पूरी थीम (चित्र + काँच + रंग + ओर्ब) - डिफ़ॉल्ट ऐप में, बाकी डाउनलोड वाली */
class ThemePacksTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private fun repoThemes(): File = listOf(File("../themes"), File("themes")).first { it.isDirectory }

    @Test fun defaultPackIsBuiltIn() {
        assertEquals("dk_futuristic", ThemePacks.DEFAULT_ID)
        assertEquals(ThemePacks.DEFAULT_ID, ThemePacks.DEFAULT.id)
        assertEquals(ThemePacks.DEFAULT, NeonTheme.Look().pack)
        assertTrue(File("src/main/res/drawable-nodpi/dk_theme_bg.webp").isFile)
        assertTrue(ThemePacks.CATALOG.size >= 4)
        assertFalse(ThemePacks.CATALOG.any { it.id == ThemePacks.DEFAULT_ID })
    }

    @Test fun idsAreSafe() {
        assertTrue(ThemePacks.validId("cyber_neon"))
        assertFalse(ThemePacks.validId("../etc"))
        assertFalse(ThemePacks.validId("A B"))
        assertFalse(ThemePacks.validId(""))
        assertFalse(ThemePacks.validId(null))
    }

    @Test fun parseKeepsGoodAndRejectsBad() {
        assertNull(ThemePacks.parse("not json"))
        assertNull(ThemePacks.parse("{\"id\":\"../x\"}"))
        val p = ThemePacks.parse("{\"id\":\"t1\",\"name\":\"T\",\"accent\":\"#FF0000\",\"glassTop\":\"#FFFFFF\",\"bg\":[\"#FFFFFF\",\"zz\"],\"dim\":5}")
        assertNotNull(p); p!!
        assertEquals(0xFFFF0000.toInt(), p.accent)
        assertTrue(NeonTheme.luminance(p.glassA) <= 0.035)       // सफ़ेद काँच भी गहरा किया गया
        assertTrue(NeonTheme.luminance(p.bg[0]) <= 0.02)
        assertTrue(p.dim <= 0.85f)
    }

    /** repo की हर डाउनलोड वाली थीम: फ़ाइलें सही, और सफ़ेद अक्षर काँच + मुख्य बटन पर साफ़ (4.5:1) */
    @Test fun repoThemesAreValidAndReadable() {
        val root = repoThemes()
        assertTrue(File(root, "index.json").isFile)
        for (e in ThemePacks.CATALOG) {
            val d = File(root, e.id)
            assertTrue(e.id, File(d, "bg.webp").isFile && File(d, "bg.webp").length() in 1..(600 * 1024))
            val p = ThemePacks.parse(File(d, "theme.json").readText())
            assertNotNull(e.id, p); p!!
            assertEquals(e.id, p.id)
            for (theme in listOf(NeonTheme.THEME_DARK, NeonTheme.THEME_AMOLED)) for (g in listOf(0, 50, 100)) {
                val l = NeonTheme.Look(theme = theme, glass = g, pack = p)
                for (bgc in NeonTheme.bg(l)) {
                    assertTrue("${e.id}/$theme/$g", NeonTheme.contrast(WHITE, NeonTheme.over(NeonTheme.glassTop(l), bgc)) >= 4.5)
                }
            }
            val l = NeonTheme.Look(pack = p)
            for (c in listOf(NeonTheme.accentTop(l), NeonTheme.accentBottom(l))) {
                assertTrue("${e.id} button", NeonTheme.contrast(WHITE, NeonTheme.over(c, NeonTheme.bg(l)[1])) >= 4.5)
            }
        }
    }

    @Test fun settingsHasThemeGalleryAndBlackStrip() {
        val look = File("src/main/java/com/dk/assistant/SettingsLook.kt").readText()
        assertTrue(look.contains("themeGallery()"))
        assertTrue(look.contains("ThemePacks.download") || look.contains("P.download"))
        val xml = File("src/main/res/layout/activity_settings.xml").readText()
        assertTrue(xml.contains("AuroraView"))
    }
}
