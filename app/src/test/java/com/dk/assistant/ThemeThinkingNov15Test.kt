package com.dk.assistant

import com.dk.assistant.ui.NeonTheme
import com.dk.assistant.ui.ThemeVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): तीन बदलाव -
 *  1) "सोच रहा हूँ…" बुलबुला और header का THINKING = Settings › दिखावट वाला टाइपिंग एनीमेशन (बंद = बंद)
 *  2) Light mode = dark वाला ही design, सिर्फ़ bg / card / text अलग (एक theme variables फ़ाइल)
 *  3) वॉलपेपर पूरी तरह हटा (WallpaperDockTest.wallpaper_removedCompletely)
 */
class ThemeThinkingNov15Test {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    // ------------------------------------------------------------ 1) thinking animation
    @Test fun thinkingBubble_usesChosenTypingAnimation() {
        val sc = src("dkai/DkaiScreen.kt")
        assertFalse("पुराना static बुलबुला", sc.contains("bubble(false, DKState.hintText.ifBlank { \"सोच रहा हूँ…\" }, faint = true)"))
        assertTrue(sc.contains("chatList.addView(thinkingBubble(DKState.hintText.ifBlank { \"सोच रहा हूँ…\" }))"))
        val tb = sc.substringAfter("private fun thinkingBubble(").substringBefore("private fun bubble(")
        assertTrue(tb.contains("DKWorkingIndicator(c).apply { bare = true; state = com.dk.assistant.WorkState.THINKING }"))
        assertTrue(tb.contains("if (thinkAnimOn())"))
        // Settings से ही पढ़ा (उसी Prefs key से) - render पर ताज़ा, बदलते ही बुलबुला दोबारा बनता है
        assertTrue(sc.contains("Prefs.typingStyle(c)"))
        assertTrue(sc.contains("|\${if (thinking) thinkStyle() else \"\"}\".hashCode()"))
        // header का THINKING
        assertTrue(sc.contains("val thinkAnim = st == DkaiCore.Status.THINKING && thinkAnimOn()"))
        assertTrue(sc.contains("readyAnim.state = if (thinkAnim) com.dk.assistant.WorkState.THINKING else com.dk.assistant.WorkState.IDLE"))
    }

    @Test fun typingAnimation_offAndReducedMotion_stopEverywhere() {
        assertTrue(TypingStyles.ALL.any { it.first == TypingStyles.OFF })
        assertEquals(TypingStyles.OFF, TypingStyles.valid("off"))
        assertTrue(TypingStyles.animates("wave", false))
        assertTrue(TypingStyles.animates(TypingStyles.COMPUTER, false))
        assertFalse(TypingStyles.animates(TypingStyles.OFF, false))
        assertFalse(TypingStyles.animates("pulse", true))
        // हर विकल्प (बंद छोड़कर) चलता है
        for ((k, _) in TypingStyles.ALL) assertEquals(k, k != TypingStyles.OFF, TypingStyles.animates(k, false))
        val ind = src("DKWorkingIndicator.kt")
        assertTrue(ind.contains("if (typing == TypingStyles.OFF) { running = false; last = 0L; return }"))
        assertTrue(ind.contains("!(bare && com.dk.assistant.ui.NeonTheme.reducedMotion())"))
        assertTrue(src("DKChatUi.kt").contains("wave.visibility = if (off) GONE else VISIBLE"))
        assertTrue(src("SettingsLook.kt").contains("radios(box, TypingStyles.ALL, cur, 6400)"))
    }

    // ------------------------------------------------------------ 2) light = dark design
    @Test fun themeVars_onlyBgCardTextDiffer_surfacesNeutral() {
        val d = ThemeVars.DARK; val l = ThemeVars.LIGHT
        assertTrue(d.bg != l.bg && d.card != l.card && d.text != l.text)
        assertEquals(0xFFFFFFFF.toInt(), l.card)
        // light की हर सतह सादा सफ़ेद/धूसर - कोई pastel (नीली/गुलाबी झलक) नहीं
        for (c in listOf(l.bg, l.card, l.pill, l.field, l.nav, l.sel, l.border)) assertTrue(Integer.toHexString(c), ThemeVars.neutral(c))
        assertTrue(NeonTheme.luminance(l.bg) > 0.85)
        // contrast: गहरी लिखावट सफ़ेद कार्ड और पृष्ठभूमि पर साफ़
        assertTrue(ThemeVars.contrast(l.text, l.card) >= 7.0)
        assertTrue(ThemeVars.contrast(l.text, l.bg) >= 7.0)
        assertTrue(ThemeVars.contrast(l.sub, l.card) >= 4.5)
        assertTrue(ThemeVars.contrast(l.hint, l.card) >= 4.5)
        assertTrue(ThemeVars.contrast(d.text, d.card) >= 7.0)
    }

    @Test fun dkaiTheme_sharedColorsNoLightBranches() {
        val th = src("dkai/DkaiTheme.kt")
        assertFalse(th.contains("private fun t(dark: Long, lite: Long)"))
        assertTrue(th.contains("val CYAN get() = V.CYAN"))
        assertTrue(th.contains("val ALERT_BG get() = V.ALERT_BG"))
        assertTrue(th.contains("val UPDATE_BG get() = V.UPDATE_BG"))
        for (f in listOf("dkai/DkaiVoice.kt", "dkai/DkaiViews.kt", "dkai/DkaiSearchCard.kt")) {
            val s = src(f)
            // light के अलग (फीके) रंग वाली कोई शाखा नहीं - सफ़ेद बातचीत-डिब्बे के अंदर के रंग दोनों मोड में एक जैसे हैं
            assertFalse("$f: light-only रंग", Regex("if \\(DkaiTheme\\.light\\) 0x").containsMatchIn(s))
            for (old in listOf("0xFFE6ECFA", "0xFFEDE9FE", "0x1F0284C7")) assertFalse("$f: $old", s.contains(old))
        }
        // icon box = ठोस (dark कार्ड पर रखा रंग) - सफ़ेद कार्ड पर फीका नहीं
        val sq = FutureViews.squircleColors(0xFF22D3EE.toInt())
        for (c in sq) assertEquals(0xFF, (c ushr 24) and 0xFF)
        assertTrue(src("FutureUi.kt").contains("GradientDrawable.Orientation.TL_BR, squircleColors(color)"))
    }

    @Test fun plainLook_readsThemeVars_solidCardsOnly() {
        val pl = src("PlainLook.kt")
        assertTrue(pl.contains("private val L = com.dk.assistant.ui.ThemeVars.LIGHT"))
        assertEquals(ThemeVars.LIGHT.bg, PlainLook.L_BG)
        assertEquals(ThemeVars.DARK.card, PlainLook.CARD)
        assertFalse(pl.contains("tintFor("))
        // "मेरा DK" / CONTROL CENTER का सुनहरा शीर्षक दोनों मोड में (सुनहरा रंग light में भी गहरा नहीं होता)
        assertEquals(0xFFFFD700.toInt(), PlainLook.lightText(0xFFFFD700.toInt()))
        assertTrue(src("SettingsActivity.kt").contains("FutureViews.goldShader(title)"))
    }
}
