package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक):
 *  1. "Settings में अपडेट के नीचे Google से लॉग इन, Google के फोटो के साथ, टैप करते ही साइन इन"
 *  2. "सब बातें फ़ोन पर सेव; चैट की बातें ज़्यादातर याद; बाकी का सार - जो DK को याद रखने लायक लगे"
 */
class MemoryDigestGoogleRowTest {
    private fun src(f: String) = listOf(File("src/main/$f"), File("app/src/main/$f")).first { it.exists() }.readText()
    private fun t(id: Long, who: String, text: String) = LocalRecall.Turn(id, who, text, 1_700_000_000_000L + id * 1000)

    // ------------------------------------------------------------ Google वाली पंक्ति
    @Test fun googleRowRightBelowUpdate() {
        val s = src("java/com/dk/assistant/SettingsActivity.kt")
        val main = s.substringAfter("if (cat == null) {").substringBefore("return\n")
        val upd = main.indexOf("it.first == \"update\"")
        val g = main.indexOf("googleMenuRow()")
        assertTrue(upd > 0 && g > upd)
        assertEquals("Google वाली पंक्ति सबसे नीचे (अपडेट के बाद कुछ नहीं)", "", main.substring(g + "googleMenuRow()".length).trim())
    }

    @Test fun googleRowHasLogoAndOpensPicker() {
        val s = src("java/com/dk/assistant/SettingsLicense.kt")
        val row = s.substringAfter("internal fun SettingsActivity.googleMenuRow()")
        assertTrue(row.contains("R.drawable.ic_google_g"))
        val tap = s.substringAfter("private fun SettingsActivity.googleRowTap()").substringBefore("\n}\n")
        assertTrue("टैप = सीधे खाता चुनने वाला popup", tap.contains("googleLogin()"))
        assertTrue("सर्वर नहीं = हिंदी संदेश, crash नहीं", tap.contains("GOOGLE_NO_SERVER"))
        assertTrue("लाइसेंस वाला पुराना कार्ड भी वैसा ही", s.contains("card(LICENSE_LOGIN_CARD"))
        val logo = src("res/drawable/ic_google_g.xml")
        listOf("#FFC107", "#FF3D00", "#4CAF50", "#1976D2").forEach { assertTrue(it, logo.contains(it)) }
    }

    @Test fun googleRowText() {
        assertEquals(GOOGLE_ROW_TITLE, googleRowText(false, "").first)
        val (t, sub) = googleRowText(true, "a@b.com")
        assertEquals("Google खाता", t)
        assertTrue(sub.startsWith("✓ a@b.com"))
        assertTrue(sub.contains("लॉग आउट"))
    }

    // ------------------------------------------------------------ DK खुद चुने क्या याद रखना है
    @Test fun dueRules() {
        val m = 60_000L
        assertFalse("बहुत कम बातें", MemoryDigest.due(3, true, 60 * m))
        assertFalse("अभी-अभी बना था", MemoryDigest.due(50, true, 1 * m))
        assertTrue("20 बातें", MemoryDigest.due(20, false, 5 * m))
        assertFalse("बातचीत बीच में, 20 से कम", MemoryDigest.due(10, false, 60 * m))
        assertTrue("बातचीत खत्म, 10 मिनट बाद", MemoryDigest.due(6, true, 11 * m))
        assertFalse("बातचीत खत्म, पर 10 मिनट नहीं हुए", MemoryDigest.due(6, true, 5 * m))
    }

    @Test fun firstRunOnlyRecentTurns() {
        assertEquals(940L, MemoryDigest.startFrom(null, 1000L))
        assertEquals(0L, MemoryDigest.startFrom(null, 10L))
        assertEquals(500L, MemoryDigest.startFrom(500L, 1000L))
    }

    @Test fun promptHasKnownAndStopsAtBudget() {
        val turns = (1L..50L).map { t(it, if (it % 2 == 1L) "u" else "d", "बात नंबर $it " + "क".repeat(200)) }
        val (p, upTo) = MemoryDigest.prompt(turns, listOf("पसंद: चाय"), maxChars = 2000)
        assertTrue(p.startsWith("ALREADY REMEMBERED:\n- पसंद: चाय"))
        assertTrue(p.contains("User: बात नंबर 1 "))
        assertTrue(p.contains("DK: बात नंबर 2 "))
        assertTrue(p.length <= 2000)
        assertTrue("सब नहीं आईं = आगे अगली बार", upTo in 2L..49L)
        assertFalse(p.contains("बात नंबर ${upTo + 1} "))
        val (_, all) = MemoryDigest.prompt(turns.take(3), emptyList())
        assertEquals(3L, all)
    }

    @Test fun parseKeepsOnlyCleanNewItems() {
        val raw = "ठीक है:\n```json\n{\"keep\":[\"पसंद: बॉस को अदरक वाली चाय पसंद है\", \"- लोग: राहुल बॉस का छोटा भाई है\", " +
            "\"पसंद: बॉस को अदरक वाली चाय पसंद है\", \"मेरा ATM पिन 4321 है\", \"हाँ\", \"नासिक में नई दुकान खोलनी है\", \"पसंद: पुरानी बात\"]}\n```"
        val out = MemoryDigest.parse(raw, known = listOf("पसंद: पुरानी बात"), dropped = emptySet())!!
        assertEquals(listOf("पसंद: बॉस को अदरक वाली चाय पसंद है", "लोग: राहुल बॉस का छोटा भाई है", "जानकारी: नासिक में नई दुकान खोलनी है"), out)
    }

    @Test fun parseRespectsDroppedAndLimits() {
        val dropped = setOf(LocalRecall.norm("काम: रिपोर्ट भेजनी है"))
        assertEquals(emptyList<String>(), MemoryDigest.parse("{\"keep\":[\"काम: रिपोर्ट भेजनी है\"]}", emptyList(), dropped))
        val many = (1..10).joinToString(",") { "\"काम: नया काम नंबर $it\"" }
        assertEquals(MemoryDigest.MAX_ITEMS, MemoryDigest.parse("{\"keep\":[$many]}", emptyList(), emptySet())!!.size)
        assertEquals(emptyList<String>(), MemoryDigest.parse("{\"keep\":[]}", emptyList(), emptySet()))
        assertNull("JSON नहीं = बाद में फिर", MemoryDigest.parse("कुछ याद लायक नहीं", emptyList(), emptySet()))
        assertNull(MemoryDigest.parse("{टूटा", emptyList(), emptySet()))
    }

    @Test fun wiring() {
        val lm = src("java/com/dk/assistant/LocalMemory.kt")
        assertTrue(lm.contains("MemoryDigest.noteTurn(ctx)"))
        assertTrue(lm.contains("MemoryDigest.kick(ctx, why, force = true)"))
        assertTrue("पुरानी बातें अब भी अपने-आप नहीं मिटतीं", lm.contains("कोई बात अपने-आप नहीं मिटती"))
        val d = src("java/com/dk/assistant/MemoryDigest.kt")
        assertTrue("DK सर्वर की मुफ़्त सीमा नहीं खाता", d.contains("quickText(SYSTEM, text, router = false)"))
        assertTrue(d.contains("FeatureFlags.INCOGNITO"))
        val b = src("java/com/dk/assistant/BrainManager.kt")
        assertEquals(20, BrainManager.CHAT_TURNS)
        assertEquals(24, BrainManager.PHONE_TURNS)
        assertFalse(b.contains("history.takeLast(10)"))
        assertTrue(src("java/com/dk/assistant/MemoryActivity.kt").contains("MemoryDigest.setOn("))
    }
}
