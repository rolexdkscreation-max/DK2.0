package com.dk.assistant

import com.dk.assistant.ui.NeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4 Premium Neon Glass: design tokens + UI कस्टमाइज़ेशन (सिर्फ़ दिखावट) */
class NeonThemeTest {

    private val WHITE = 0xFFFFFFFF.toInt()

    @Test fun defaultsAreDarkNeonBlue() {
        val l = NeonTheme.Look()
        assertEquals(NeonTheme.THEME_DARK, l.theme)
        assertEquals(NeonTheme.Accent.BLUE, l.accent)
        assertEquals(NeonTheme.MOTION_STANDARD, l.motion)
        assertEquals(NeonTheme.DEF_GLASS, l.glass)
        assertEquals(NeonTheme.DEF_GLOW, l.glow)
    }

    @Test fun badSavedValuesFallBackSafely() {
        val l = NeonTheme.sanitize("light??", "rainbow", 400, -9, "fast")
        assertEquals(NeonTheme.THEME_DARK, l.theme)
        assertEquals(NeonTheme.Accent.BLUE, l.accent)
        assertEquals(100, l.glass)
        assertEquals(0, l.glow)
        assertEquals(NeonTheme.MOTION_STANDARD, l.motion)
        val ok = NeonTheme.sanitize("amoled", "magenta", 30, 70, "reduced")
        assertEquals(NeonTheme.THEME_AMOLED, ok.theme)
        assertEquals(NeonTheme.Accent.MAGENTA, ok.accent)
        assertEquals(NeonTheme.MOTION_REDUCED, ok.motion)
    }

    @Test fun allFiveAccentsExist() {
        assertEquals(listOf("blue", "cyan", "violet", "purple", "magenta"), NeonTheme.Accent.values().map { it.key })
    }

    @Test fun reducedMotionFollowsUserOrPhone() {
        assertFalse(NeonTheme.reducedMotion(NeonTheme.Look(), systemOff = false))
        assertTrue(NeonTheme.reducedMotion(NeonTheme.Look(motion = NeonTheme.MOTION_REDUCED), systemOff = false))
        assertTrue(NeonTheme.reducedMotion(NeonTheme.Look(), systemOff = true))
    }

    @Test fun mixAndAlphaMath() {
        assertEquals(0x80123456.toInt(), NeonTheme.alpha(0xFF123456.toInt(), 0x80))
        assertEquals(0xFF000000.toInt(), NeonTheme.mix(0xFF000000.toInt(), WHITE, 0f))
        assertEquals(WHITE, NeonTheme.mix(0xFF000000.toInt(), WHITE, 1f))
        assertTrue(NeonTheme.contrast(WHITE, 0xFF000000.toInt()) > 20.9)
    }

    /** सबसे पारदर्शी काँच पर भी सफ़ेद अक्षर साफ़ (WCAG 4.5:1) - हर थीम, हर रंग, हर चमक */
    @Test fun whiteTextReadableOnGlassForEveryChoice() {
        for (theme in listOf(NeonTheme.THEME_DARK, NeonTheme.THEME_AMOLED)) for (ac in NeonTheme.Accent.values()) for (g in listOf(0, 50, 100)) {
            val l = NeonTheme.Look(theme = theme, accent = ac, glass = g)
            for (bgc in NeonTheme.bg(l)) {
                val surface = NeonTheme.over(NeonTheme.glassTop(l), bgc)
                assertTrue("$theme/$ac/$g", NeonTheme.contrast(WHITE, surface) >= 4.5)
            }
        }
    }

    /** मुख्य बटन / अपने संदेश (accent gradient) पर सफ़ेद अक्षर साफ़ */
    @Test fun whiteTextReadableOnAccentButtons() {
        for (ac in NeonTheme.Accent.values()) {
            val l = NeonTheme.Look(accent = ac)
            for (c in listOf(NeonTheme.accentTop(l), NeonTheme.accentBottom(l))) {
                val surface = NeonTheme.over(c, NeonTheme.bg(l)[1])
                assertTrue("$ac", NeonTheme.contrast(WHITE, surface) >= 4.5)
            }
        }
    }

    @Test fun glassNeverFullyDisappears() {
        val clear = NeonTheme.Look(glass = 100)
        val solid = NeonTheme.Look(glass = 0)
        assertTrue(NeonTheme.glassAlpha(clear) >= 0x70)
        assertTrue(NeonTheme.glassAlpha(solid) > NeonTheme.glassAlpha(clear))
    }

    @Test fun glowZeroMeansNoEdgeGlow() {
        assertEquals(0, NeonTheme.glowAlpha(NeonTheme.Look(glow = 0)))
        assertTrue(NeonTheme.glowAlpha(NeonTheme.Look(glow = 100)) > NeonTheme.glowAlpha(NeonTheme.Look(glow = 30)))
    }

    /** दिखावट की सेटिंग AI / आवाज़ / key की किसी चाबी से नहीं टकराती, और reset सिर्फ़ ui_* हटाता है */
    @Test fun appearanceKeysAreIsolated() {
        assertTrue(NeonTheme.KEYS.all { it.startsWith("ui_") })
        val src = File(root(), "app/src/main/java/com/dk/assistant/ui/NeonTheme.kt").readText()
        val reset = src.substringAfter("fun resetAppearance").substringBefore("fun reducedMotion")
        assertTrue(reset.contains("KEYS.forEach { e.remove(it) }"))
        assertFalse(reset.contains(".clear()"))
        // AI के prompt / आवाज़ वाले हिस्से दिखावट की पसंद नहीं पढ़ते
        for (f in listOf("BrainManager.kt", "CalmPersona.kt", "GirlfriendPersona.kt", "Speaker.kt")) {
            val t = File(root(), "app/src/main/java/com/dk/assistant/$f")
            if (t.exists()) assertFalse(f, t.readText().contains("NeonTheme"))
        }
    }

    /** होम: नीचे के 3 बड़े कार्ड नहीं, नीचे की bar नहीं; मेनू से चैट / आवाज़ / मेमोरी / सेटिंग्स पहुँच में */
    @Test fun homeHasMenuInsteadOfBigCards() {
        val xml = File(root(), "app/src/main/res/layout/activity_main.xml").readText()
        assertFalse(xml.contains("@+id/btnChat"))
        assertFalse(xml.contains("@+id/btnMemory"))
        assertFalse(xml.contains("@+id/btnVoice"))
        assertFalse(xml.contains("BottomNavigationView"))
        assertTrue(xml.contains("@+id/btnMenu"))
        assertTrue(xml.contains("@+id/btnSettings"))
        assertFalse(xml.contains("🖥")); assertFalse(xml.contains("🌐"))
        val main = File(root(), "app/src/main/java/com/dk/assistant/MainActivity.kt").readText()
        val menu = main.substringAfter("R.id.btnMenu").substringBefore("chipIcons()")
        for (w in listOf("openChat()", "openVoice()", "MemoryActivity", "openSettings()", "ComputerActivity", "ForgeActivity"))
            assertTrue(w, menu.contains(w))
    }

    @Test fun settingsHasAppearanceSection() {
        val s = File(root(), "app/src/main/java/com/dk/assistant/SettingsActivity.kt").readText()
        assertTrue(s.contains("Triple(\"ui\""))
        assertTrue(s.contains("\"ui\" -> { title.text = \"दिखावट\""))
        assertTrue(s.contains("resetAppearance(this)"))
    }

    @Test fun neonOrbIsDefaultButOldChoiceStays() {
        assertTrue(Shapes.STYLES.any { it.first == Shapes.NEON })
        assertEquals(Shapes.NEON, Shapes.valid(Shapes.NEON))
        assertEquals("earth", Shapes.valid(null))   // पुराना नियम वैसा ही
        val prefs = File(root(), "app/src/main/java/com/dk/assistant/Prefs.kt").readText()
        assertTrue(prefs.contains("getString(\"orb_style\", Shapes.NEON)"))
    }

    private fun root(): File {
        var f = File("").absoluteFile
        while (!File(f, "settings.gradle").exists() && !File(f, "settings.gradle.kts").exists()) f = f.parentFile
        return f
    }
}
