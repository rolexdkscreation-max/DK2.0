package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): DK Live - डीप रिसर्च, फोटो, डार्क/लाइट मोड, कनेक्शन, सर्च के मुख्य शब्द */
class LiveNov14FixTest {
    private fun src(p: String): String =
        listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun searchFocus_keepsOnlyKeyWords() {
        assertEquals("पवन सिंह का नया सॉन्ग", SearchFocus.core("पवन सिंह का जो नया वाला सॉन्ग आया है उसे"))
        assertEquals("छठ का नया सॉन्ग", SearchFocus.core("उह छट का जो नया वाला सॉन्ग आया है"))
        for (q in listOf("पवन सिंह का गाना", "tom and jerry", "how to use iphone", "कुछ कुछ होता है", "जो तुम मेरे हो"))
            assertEquals(q, SearchFocus.core(q))
        assertEquals("abc", SearchFocus.core("abc"))
    }

    @Test fun news_emptyRetriesWide() {
        assertTrue(SearchFocus.wideRetry(true, 0, "empty"))
        assertFalse(SearchFocus.wideRetry(false, 0, "empty"))
        assertFalse(SearchFocus.wideRetry(true, 429, "empty"))
    }

    @Test fun livePhoto_picksChatThenGallery() {
        val now = 10_000_000L
        assertEquals(LivePhoto.From.CHAT, LivePhoto.pick(now - 60_000, true, now, true))
        assertEquals(LivePhoto.From.GALLERY, LivePhoto.pick(now - LivePhoto.FRESH_MS - 1, true, now, true))
        assertEquals(LivePhoto.From.GALLERY, LivePhoto.pick(0, false, now, true))
        assertEquals(LivePhoto.From.NONE, LivePhoto.pick(0, false, now, false))
        assertEquals(listOf(2, 4, 1, 3), LivePhoto.visionFirst(listOf(1, 2, 3, 4)) { it % 2 == 0 })
    }

    @Test fun liveTools_declaredAndAllowed() {
        assertTrue("deep_research" in LeakedCall.TOOLS && "look_photo" in LeakedCall.TOOLS)
        assertTrue("deep_research" in LiveToolSlim.CORE && "look_photo" in LiveToolSlim.CORE)
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains("\"deep_research\"") && setup.contains("\"look_photo\""))
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.contains("\"look_photo\" -> LivePhoto.liveTool("))
        assertTrue(tools.contains("deepResearchTool("))
    }

    @Test fun liveDeep_resultSaysWhichKind() {
        assertTrue(LiveDeep.result("x", true).startsWith("DEEP RESEARCH"))
        assertTrue(LiveDeep.result("x", false).startsWith("SEARCH RESULT"))
    }

    @Test fun themeMode_inSettings() {
        assertEquals("light", SettingsThemeMode.current(true))
        assertTrue(SettingsThemeMode.isLight("light") && !SettingsThemeMode.isLight("dark"))
        assertTrue(src("SettingsLook.kt").contains("DkaiTheme.setLight("))
        assertTrue(src("SettingsActivity.kt").contains("DkaiTheme.setLight("))
        assertTrue(SettingsSearch.search("लाइट मोड").isNotEmpty())
        assertEquals(0xFF0F172A.toInt(), PlainLook.lightText(0xFFFFFFFF.toInt()))
        assertEquals(0xFF22D3EE.toInt(), PlainLook.lightText(0xFF22D3EE.toInt()))
        assertTrue(PlainLook.darkSolid(0xFF0B1020.toInt()))
        assertFalse(PlainLook.darkSolid(0x330B1020))
    }

    @Test fun live_staysOnAndNoFalseDropSound() {
        val svc = src("DKAccessibilityService.kt")
        val app = svc.substring(svc.indexOf("fun openedInApp("), svc.indexOf("fun openedInApp(") + 700)
        assertTrue(app.contains("keepLiveOn(why)"))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("delay(LiveRecover.RECOVER_MS)") && g.contains("if (!ready && !stopped) LiveChime.disconnected("))
    }
}
