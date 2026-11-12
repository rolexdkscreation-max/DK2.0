package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026: Control Center = 6 श्रेणी कार्ड; अंदर वही पुरानी पंक्तियाँ - कोई छूटे नहीं, कोई दो बार नहीं */
class ControlCenterTest {
    private val oldRows = listOf(
        "profile", "lang", "ui", "brain", "voice", "orb", "access", "phonelock", "guard", "perms", "computer",
        "coder", "auto", "templates", "autoreply", "social", "reels", "backup", "connectors", "story", "update", "debug", "about"
    )

    @Test fun sixCategories_inOrder() {
        assertEquals(
            listOf("cat_dk", "cat_brain", "cat_auto", "cat_security", "cat_social", "cat_data"),
            SettingsSearch.CATEGORIES.map { it.first.first }
        )
    }

    @Test fun everyOldRow_inExactlyOneCategory() {
        val all = SettingsSearch.CATEGORIES.flatMap { it.second }
        assertEquals(all.size, all.toSet().size)
        assertEquals(oldRows.toSet(), all.toSet())
    }

    @Test fun everyOldRow_stillInMenuSource() {
        val s = SetSrc.all()
        for (id in oldRows) assertTrue(id, s.contains("Triple(\"$id\", "))
    }

    @Test fun categories_haveTitlePageIconAndSearch() {
        val s = SetSrc.all()
        for ((c, _) in SettingsSearch.CATEGORIES) {
            assertTrue(c.first, s.contains("\"${c.first}\" -> { title.text = \"${c.second}\"; menu(\"${c.first}\") }"))
            assertTrue(c.first, s.contains("\"${c.first}\" -> R.drawable."))
            assertEquals(c.second, SettingsSearch.SECTIONS[c.first])
        }
        assertEquals("cat_security", SettingsSearch.search("सुरक्षा").first { it.section.startsWith("cat_") }.section)
    }

    @Test fun categoryPage_neverSavesEmptyFields() {
        val s = SetSrc.all()
        assertTrue(s.contains("section?.startsWith(\"cat_\") == true || section == \"orb\""))
    }
}
