package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 19 नवंबर 2026: Settings की हर पंक्ति / श्रेणी पर उसका अपना सही आइकन (डिबग वाला कीड़ा सिर्फ़ डिबग लॉग पर) */
class SettingsIconsTest {
    private val actIds = listOf("auto", "templates", "connectors", "story", "backup")
    private val allIds = SettingsSearch.SECTIONS.keys.filterNot { it.startsWith("act:") } + actIds

    @Test fun everySectionHasOwnIcon() {
        allIds.filter { it != "debug" }.forEach { assertNotEquals("$it पर डिबग आइकन", R.drawable.ic_s_debug, settingsIcon(it)) }
        assertEquals(R.drawable.ic_s_debug, settingsIcon("debug"))
    }

    @Test fun rightIconsForKeyRows() {
        assertEquals(R.drawable.ic_s_lock, settingsIcon("phonelock"))
        assertEquals(R.drawable.ic_s_touch, settingsIcon("guard"))
        assertEquals(R.drawable.ic_s_face, settingsIcon("face"))
        assertEquals(R.drawable.ic_s_profile, settingsIcon("profile"))
        assertEquals(R.drawable.ic_s_brain, settingsIcon("brain"))
        assertEquals(R.drawable.ic_s_mic, settingsIcon("brain_live"))
        assertEquals(R.drawable.ic_s_chat, settingsIcon("brain_chat"))
        assertEquals(R.drawable.ic_s_coder, settingsIcon("brain_coder"))
        assertEquals(R.drawable.ic_s_computer, settingsIcon("brain_pc"))
        assertEquals(R.drawable.ic_s_computer, settingsIcon("computer"))
        assertEquals(R.drawable.ic_s_coder, settingsIcon("coder"))
    }

    @Test fun categoriesDifferFromEachOther() {
        val cats = SettingsSearch.CATEGORIES.map { settingsIcon(it.first.first) }
        assertEquals(cats.size, cats.toSet().size)
    }

    @Test fun mainRowsNotSharingIcons() {
        // अलग-अलग काम वाली पंक्तियों पर एक जैसा आइकन नहीं (कोडिंग एजेंट / DK कंप्यूटर के अंदर वाले पन्ने छोड़कर)
        val main = allIds.filterNot { it.startsWith("brain_") }
        val icons = main.map { settingsIcon(it) }
        assertEquals(main.toString(), icons.size, icons.toSet().size)
    }

    @Test fun drawablesExistAndSearchUsesFullId() {
        val dir = listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable")).first { it.exists() }
        listOf("lock", "touch", "face", "shield_ok", "key", "cat_dk", "cat_brain", "bolt", "robot", "groups", "storage",
            "template", "reply", "trend", "movie", "backup", "about", "mic", "chat", "teacher").forEach {
            assertTrue("ic_s_$it नहीं मिला", File(dir, "ic_s_$it.xml").exists())
        }
        val src = File(dir.parentFile.parentFile, "java/com/dk/assistant/SettingsActivity.kt").readText()
        assertTrue(src.contains("else -> e.section }"))
    }
}
