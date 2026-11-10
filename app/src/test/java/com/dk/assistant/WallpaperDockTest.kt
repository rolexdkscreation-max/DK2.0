package com.dk.assistant

import com.dk.assistant.ui.NeonTheme
import com.dk.assistant.ui.Wallpapers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 मालिक: Settings में वॉलपेपर (5 + गैलरी से अपना) पूरे ऐप में; होम डॉक में "कीबोर्ड" हटा, + = फोटो / फ़ाइल अपलोड */
class WallpaperDockTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }

    @Test fun fiveBuiltInWallpapers_shipInsideApp() {
        assertEquals(5, Wallpapers.BUILT_IN.size)
        assertEquals(5, Wallpapers.BUILT_IN.map { it.id }.toSet().size)
        for (i in 1..5) {
            val w = f("res/drawable-nodpi/dk_wp_$i.webp")
            assertTrue("dk_wp_$i बहुत बड़ी (APK भारी)", w.length() in 2_000..200_000)
        }
    }

    @Test fun validAndResolve_fallBackToTheme() {
        assertTrue(Wallpapers.valid(Wallpapers.NONE)); assertTrue(Wallpapers.valid("wp_3")); assertTrue(Wallpapers.valid(Wallpapers.CUSTOM))
        assertFalse(Wallpapers.valid("wp_9")); assertFalse(Wallpapers.valid("../x")); assertFalse(Wallpapers.valid(null))
        assertEquals(Wallpapers.NONE, Wallpapers.resolve("wp_9", true))
        assertEquals(Wallpapers.NONE, Wallpapers.resolve(null, true))
        assertEquals("wp_2", Wallpapers.resolve("wp_2", false))
        // गैलरी वाला चित्र मिट गया हो तो खाली स्क्रीन नहीं - थीम वाला चित्र
        assertEquals(Wallpapers.NONE, Wallpapers.resolve(Wallpapers.CUSTOM, false))
        assertEquals(Wallpapers.CUSTOM, Wallpapers.resolve(Wallpapers.CUSTOM, true))
    }

    @Test fun newCustomPhoto_changesLook_soEveryScreenRedraws() {
        val a = Wallpapers.key(Wallpapers.CUSTOM, 100L); val b = Wallpapers.key(Wallpapers.CUSTOM, 200L)
        assertNotEquals(a, b)
        assertEquals(Wallpapers.CUSTOM, Wallpapers.idOf(a))
        assertEquals("wp_1", Wallpapers.key("wp_1", 999L))
        // Look बदला = NeonTheme.version++ (AuroraView हर स्क्रीन पर नया चित्र बनाता है)
        assertNotEquals(NeonTheme.Look(wallpaper = a), NeonTheme.Look(wallpaper = b))
        assertNotEquals(NeonTheme.Look(), NeonTheme.Look(wallpaper = "wp_4"))
        assertEquals(Wallpapers.NONE, NeonTheme.Look().wallpaper)
    }

    @Test fun dimAndBlobs_keepTextReadable_andHideOldBlue() {
        assertEquals(0.3f, Wallpapers.dim(Wallpapers.NONE, 0.3f), 0f)
        assertTrue(Wallpapers.dim(Wallpapers.key(Wallpapers.CUSTOM, 1L), 0.3f) > Wallpapers.dim("wp_1", 0.3f))
        assertTrue(Wallpapers.dim("wp_1", 0.3f) > 0.1f)
        assertEquals(1f, Wallpapers.blobFactor("wp_1", false), 0f)
        assertEquals(0.55f, Wallpapers.blobFactor(Wallpapers.NONE, true), 0f)
        assertTrue(Wallpapers.blobFactor("wp_1", true) < 0.3f)
    }

    @Test fun customPhoto_isShrunk() {
        assertEquals(1, Wallpapers.sampleSize(1080, 1920, 1920))
        assertEquals(2, Wallpapers.sampleSize(3000, 4000, 1920))
        assertEquals(4, Wallpapers.sampleSize(8000, 8000, 1920))
        assertEquals(1, Wallpapers.sampleSize(0, 0, 1920))
        assertEquals(1f, Wallpapers.scaleFor(1080, 1920, 1920), 0f)
        assertEquals(0.5f, Wallpapers.scaleFor(1920, 3840, 1920), 0.0001f)
    }

    @Test fun wallpaperPref_isAppearanceKey_resetClearsIt() {
        assertTrue(Wallpapers.K_WALL.startsWith("ui_"))
        assertTrue(Wallpapers.K_WALL in NeonTheme.KEYS)
    }

    @Test fun settingsHasWallpaperGallery_withCustomPicker_andSearch() {
        val look = f("java/com/dk/assistant/SettingsLook.kt").readText()
        assertTrue(look.contains("wallpaperGallery()"))
        assertTrue(look.contains("pickWall.launch"))
        val act = f("java/com/dk/assistant/SettingsActivity.kt").readText()
        assertTrue(act.contains("PickVisualMedia()") && act.contains("Wallpapers.saveCustom"))
        assertTrue(f("java/com/dk/assistant/SettingsSearch.kt").readText().contains("E(\"ui\", \"वॉलपेपर\""))
        // हर स्क्रीन का पीछे का चित्र (AuroraView) वॉलपेपर लेता है
        assertTrue(f("java/com/dk/assistant/AuroraView.kt").readText().contains("look.wallpaper"))
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
