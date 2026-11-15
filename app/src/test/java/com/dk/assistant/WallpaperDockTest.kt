package com.dk.assistant

import com.dk.assistant.ui.NeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 मालिक: Settings में वॉलपेपर (5 + गैलरी से अपना) पूरे ऐप में; होम डॉक में "कीबोर्ड" हटा, + = फोटो / फ़ाइल अपलोड */
class WallpaperDockTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }

    /**
     * 15 नवंबर 2026 (मालिक: "Wallpaper section पूरी तरह हटाओ - button, पेज, विकल्प, code, state, storage, styles"):
     * पहले यहाँ 5 वॉलपेपर + गैलरी picker + download की जाँच थी. मालिक का फ़ैसला बदला - अब जाँच यह कि कुछ न बचे
     * और पुराना सेव मान crash न करे (सिर्फ़ रीसेट पर मिटे, कभी पढ़ा न जाए).
     */
    @Test fun wallpaper_removedCompletely() {
        assertFalse(File("src/main/java/com/dk/assistant/ui/Wallpapers.kt").exists() || File("app/src/main/java/com/dk/assistant/ui/Wallpapers.kt").exists())
        for (i in 1..5) assertFalse(listOf(File("src/main/res/drawable-nodpi/dk_wp_$i.webp"), File("app/src/main/res/drawable-nodpi/dk_wp_$i.webp")).any { it.exists() })
        val look = f("java/com/dk/assistant/SettingsLook.kt").readText()
        assertFalse(look.contains("wallpaperGallery()"))
        assertFalse(look.contains("card(\"वॉलपेपर\""))
        assertFalse(look.contains("pickWall"))
        val act = f("java/com/dk/assistant/SettingsActivity.kt").readText()
        assertFalse(act.contains("pickWall") || act.contains("Wallpapers."))
        assertFalse(f("java/com/dk/assistant/SettingsSearch.kt").readText().contains("E(\"ui\", \"वॉलपेपर\""))
        assertFalse(f("java/com/dk/assistant/AuroraView.kt").readText().contains("wallpaper"))
        val neon = f("java/com/dk/assistant/ui/NeonTheme.kt").readText()
        assertFalse(neon.contains("val wallpaper"))
        // पुराना मान: रीसेट पर मिटे, पर कभी पढ़ा न जाए (crash नहीं)
        assertEquals("ui_wallpaper", NeonTheme.LEGACY_WALL)
        assertTrue(NeonTheme.LEGACY_WALL in NeonTheme.KEYS)
        assertFalse(neon.contains("getString(LEGACY_WALL"))
        assertTrue(f("java/com/dk/assistant/plus/PlusBoot.kt").readText().contains("LEGACY_WALL_DIR).deleteRecursively()"))
    }

    @Test fun homeDock_noKeyboardPill_plusFirst_opensUploadMenu() {
        val xml = f("res/layout/activity_main.xml").readText()
        assertFalse(xml.contains("btnDockKeys"))
        val dock = xml.substringAfter("android:background=\"@drawable/glass_composer\"")
        assertTrue("+ डॉक में सबसे पहले", dock.indexOf("btnDockTools") in 0 until dock.indexOf("btnDockType"))
        val main = f("java/com/dk/assistant/MainActivity.kt").readText()
        assertFalse(main.contains("btnDockKeys"))
        assertTrue(main.contains("\"फोटो अपलोड\"") && main.contains("\"फ़ाइल अपलोड\""))
        assertTrue(main.contains("ComputerActivity.EXTRA_ATTACHED"))
        assertTrue(f("java/com/dk/assistant/ComputerActivity.kt").readText().contains("getStringArrayListExtra(EXTRA_ATTACHED)"))
    }

    @Test fun attachedFiles_types() {
        assertEquals("(जोड़ी गई फ़ाइल: a.jpg, b.pdf) ", ComputerActivity.attachNote(listOf("a.jpg", "b.pdf")))
        assertEquals(".pdf", ComputerActivity.extFor("application/pdf"))
        assertEquals(".jpg", ComputerActivity.extFor("image/jpeg"))
        assertEquals(".png", ComputerActivity.extFor("image/png"))
        assertNull("HEIC = JPEG में बदलो", ComputerActivity.extFor("image/heic"))
        assertEquals(".txt", ComputerActivity.extFor("text/plain"))
        assertEquals(".txt", ComputerActivity.extFor(null))
        assertTrue("image/*" in ComputerActivity.PICK_TYPES && "application/pdf" in ComputerActivity.PICK_TYPES)
    }
}