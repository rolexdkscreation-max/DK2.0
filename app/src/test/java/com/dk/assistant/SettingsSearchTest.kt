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
        // v3.99: "मेरा चेहरा" (चेहरा पहचान) card हटाया गया - वही जाँच (छोटे/बड़े अक्षर + card पर पहुँचना) ओर्ब के card पर
        assertTrue(SettingsSearch.search("dk का चेहरा").any { it.card == "DK का चेहरा" })
    }

    @Test fun cardHit_hasSection_forOpening() {
        // v4.5: "सिर्फ़ मेरी आवाज़ सुनो" card हटाया गया (मालिक की माँग) - वही जाँच (card मिले + सही हिस्सा खुले) Hey DK card पर
        val r = SettingsSearch.search("Hey DK")
        val e = r.first { it.card == "Hey DK (फ़ोन के अंदर)" }
        assertEquals("voice", e.section)
        assertTrue(SettingsSearch.search("सिर्फ़ मेरी आवाज़").none { it.card == "सिर्फ़ मेरी आवाज़ सुनो" })
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

    /** v3.99: चेहरा पहचान ऐप से हटी - सेटिंग्स में न मेनू, न card, न सर्च */
    @Test fun faceRecognition_removedFromSettings() {
        val s = src("src/main/java/com/dk/assistant/SettingsActivity.kt")
        assertTrue(!s.contains("FaceAI.") && !s.contains("secFace") && !s.contains("Triple(\"face\""))
        assertTrue(SettingsSearch.ENTRIES.none { it.section == "face" } && "face" !in SettingsSearch.SECTIONS)
        val v = src("src/main/java/com/dk/assistant/VoiceActivity.kt")
        assertTrue(!v.contains("FaceRecognitionEngine") && !v.contains("FaceScanOverlayView"))
    }
}
