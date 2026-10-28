package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsSearchTest {

    private fun src(p: String) = listOf(File(p), File("app/$p")).first { it.exists() }.readText()

    /** SettingsActivity के हर (सीधे नाम वाले) card का नाम सर्च में होना चाहिए - नया card जोड़ा और यहाँ भूले तो यह fail */
    @Test fun everyCardInActivity_isSearchable() {
        val s = src("src/main/java/com/dk/assistant/SettingsActivity.kt")
        val titles = Regex("""\bcard\("((?:[^"\\$]|\\.)*)"""").findAll(s).map { it.groupValues[1].replace("\\\"", "\"") }.toSet()
        assertTrue(titles.size > 40)
        val have = SettingsSearch.ENTRIES.map { it.card }.toSet()
        val missing = titles - have
        assertTrue("सर्च में नहीं: $missing", missing.isEmpty())
    }

    @Test fun everySectionOfActivity_isInSearch() {
        val s = src("src/main/java/com/dk/assistant/SettingsActivity.kt")
        val ids = Regex(""""([a-z_]+)" -> \{ title\.text = """).findAll(s).map { it.groupValues[1] }.toSet()
        assertTrue(ids.isNotEmpty())
        assertTrue((ids - SettingsSearch.SECTIONS.keys).isEmpty())
    }

    @Test fun autonomous_foundBySpellings() {
        for (q in listOf("ऑटोनोमस", "ऑटोनॉमस", "autonomous", "Auto", "ऑटो")) {
            val r = SettingsSearch.search(q)
            assertTrue(q, r.isNotEmpty())
            assertEquals(q, SettingsSearch.ACT_AUTO, r.first().section)
        }
    }

    @Test fun templates_alarm_found() {
        assertTrue(SettingsSearch.search("अलार्म").any { it.section == SettingsSearch.ACT_TEMPLATES })
        assertEquals(SettingsSearch.ACT_TEMPLATES, SettingsSearch.search("टेम्पलेट").first().section)
    }

    @Test fun nukta_and_case_ignored() {
        assertTrue(SettingsSearch.search("आवाज").isNotEmpty())          // बिना नुक़्ता
        assertTrue(SettingsSearch.search("GEMINI key").any { it.section.startsWith("brain") })
        assertTrue(SettingsSearch.search("मेरा चेहरा").any { it.card == "मेरा चेहरा" })
    }

    @Test fun cardHit_hasSection_forOpening() {
        val r = SettingsSearch.search("सिर्फ़ मेरी आवाज़")
        val e = r.first { it.card == "सिर्फ़ मेरी आवाज़ सुनो" }
        assertEquals("voice", e.section)
    }

    @Test fun blankOrNoise_nothing() {
        assertTrue(SettingsSearch.search("").isEmpty())
        assertTrue(SettingsSearch.search("   ").isEmpty())
        assertTrue(SettingsSearch.search("qqqzzz").isEmpty())
    }

    @Test fun mainMenu_hasAutoAndTemplatesRows() {
        val s = src("src/main/java/com/dk/assistant/SettingsActivity.kt")
        assertTrue(s.contains("Triple(\"auto\", \"ऑटोनोमस मोड\""))
        assertTrue(s.contains("Triple(\"templates\", \"टेम्पलेट कमांड\""))
        assertTrue(s.contains("AutoSettingsActivity::class.java"))
    }
}
