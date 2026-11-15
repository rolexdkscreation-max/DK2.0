package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 15 नवंबर 2026 (मालिक): "DK AI Plus" का अलग पन्ना नहीं - उसकी असली सुविधाएँ अपनी पुरानी जगह,
 * कोई झूठा "(अगला चरण)" स्विच नहीं, कोई दोहराव नहीं.
 */
class SettingsExtraTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }

    @Test fun plusPage_andName_gone() {
        assertFalse(File(main, "java/com/dk/assistant/plus/PlusSettingsActivity.kt").exists())
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("PlusSettingsActivity"))
        val s = SetSrc.all()
        assertFalse(s.contains("menuRow(\"plus\""))
        assertFalse(s.contains("(अगला चरण)"))
        // बोलकर / tool वाले जवाब में भी पुराना नाम नहीं (टिप्पणियाँ छोड़कर)
        val spoken = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { l -> l.contains("DK AI Plus") && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("$spoken", spoken.isEmpty())
    }

    @Test fun eachFeature_inItsOldSection() {
        val s = SetSrc.all()
        assertTrue(s.contains("secAccess(); secPayGuard(); secDriving(); secEvents() }"))
        assertTrue(s.contains("secGuard(); secPrivacy(); secSos() }"))
        assertTrue(s.contains("secCoder(); secTermux() }"))
        assertTrue(s.contains("secComputer(); secImageGen() }"))
        assertTrue(s.contains("secSocial(); secYouTube() }"))
        assertTrue(s.contains("FeatureFlags.INCOGNITO"))
        for ((sec, card) in listOf("guard" to "इमरजेंसी SOS", "access" to "ड्राइविंग मोड", "access" to "ऑटो इवेंट ट्रिगर",
                "coder" to "Termux ऑटोमेशन", "computer" to "इमेज बनाना (मुफ़्त API)", "social" to "YouTube चैनल जाँच")) {
            assertTrue(card, SettingsSearch.ENTRIES.any { it.section == sec && it.card == card })
        }
        assertTrue(SettingsSearch.search("sos").any { it.card == "इमरजेंसी SOS" })
        assertTrue(SettingsSearch.search("termux").any { it.section == "coder" })
    }

    @Test fun switches_writeRealFlags() {
        val s = SetSrc.all()
        for (k in listOf("SOS_ON", "DRIVING_MODE", "EVENTS_ON", "TERMUX", "IMAGE_GEN", "YT_CHANNEL", "FACE_VAULT"))
            assertTrue(k, s.contains("FeatureFlags.$k"))
        assertTrue(s.contains("FeatureFlags.set(this@flagSwitch, key, on)"))
    }
}
