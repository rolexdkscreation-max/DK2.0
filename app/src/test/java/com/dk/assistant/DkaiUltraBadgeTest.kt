package com.dk.assistant

import com.dk.assistant.backend.Plan
import com.dk.assistant.dkai.DkaiUltra
import com.dk.assistant.ui.VerifiedBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026: Auto पंक्ति = "DKAI Ultra" (सिर्फ़ नाम/लोगो), DK का अपना मॉडल-जवाब, प्रोफ़ाइल का verified टिक */
class DkaiUltraBadgeTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun ultraIsOnlyANameForAuto() {
        assertEquals("DKAI Ultra", DkaiUltra.NAME)
        assertEquals("हर काम में मदद • All-rounded fast • Free", DkaiUltra.ROW_SUB)
        assertEquals("DKAI Ultra ▾", DkaiUltra.chip("Auto"))
        assertEquals("Gemma 4 ▾", DkaiUltra.chip("Gemma 4"))
        val sheet = src("dkai/DkaiSheets.kt")
        // काम वही: Auto पंक्ति अब भी कुछ न चुनना (null) सेट करती है, चुनी = cur == null
        assertTrue(sheet.contains("DkaiUltra.NAME, DkaiUltra.ROW_SUB, cur == null"))
        assertTrue(sheet.contains("SelectedModel.set(c, null)"))
        assertTrue(sheet.contains("logoRes = R.drawable.ic_dk"))
        assertFalse(sheet.contains("\"Auto\", \"काम के हिसाब से"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("DkaiUltra.chip(if (ch == null) \"Auto\""))
        assertTrue("लाइव निर्देश छोटे रहें", AssistantBrand.ULTRA_RULE.length < 200)
        val ui = src("dkai/DkaiUi.kt")
        assertTrue(ui.contains("ScaleType.FIT_CENTER")); assertTrue(ui.contains("DkaiTheme.LOGO_TILE"))
    }

    @Test fun dkNamesUltraLastAndHonestly() {
        val r = AssistantBrand.IDENTITY_RULE
        assertTrue(r.contains(AssistantBrand.ULTRA_RULE))
        assertTrue(r.endsWith(AssistantBrand.PRIVACY_RULE))
        val u = AssistantBrand.ULTRA_RULE
        for (w in listOf("DKAI Ultra", "fastest", "image generation", "real current ones first", "LAST", "invent nothing")) assertTrue(w, u.contains(w))
        // "मेरा सबसे तेज़ AI मॉडल DKAI Ultra है" हटता नहीं, पर किसी कंपनी से जोड़ा तो हटे
        assertFalse(AnswerGuard.selfIdentity("मेरा सबसे तेज़ AI मॉडल DKAI Ultra है।"))
        assertTrue(AnswerGuard.selfIdentity("मेरा DKAI Ultra Google के Gemini मॉडल पर आधारित है।"))
    }

    @Test fun badgeColoursByLicence() {
        val now = 1_000_000L
        assertEquals(VerifiedBadge.Tier.OWNER, VerifiedBadge.tier(true, Plan.Level.FREE, 0, now))
        assertEquals(VerifiedBadge.Tier.PREMIUM, VerifiedBadge.tier(false, Plan.Level.PREMIUM, 0, now))
        assertEquals(VerifiedBadge.Tier.BASIC, VerifiedBadge.tier(false, Plan.Level.BASIC, now + 10, now))
        assertNull(VerifiedBadge.tier(false, Plan.Level.BASIC, now - 10, now))      // मियाद ख़त्म
        assertNull(VerifiedBadge.tier(false, Plan.Level.FREE, 0, now))
        assertNull(VerifiedBadge.tier(false, Plan.Level.OWNER, 0, now))              // मालिक लॉगिन नहीं = नहीं
        val colors = VerifiedBadge.Tier.entries.map { it.color }.toSet()
        assertEquals(3, colors.size)
        assertNotEquals(VerifiedBadge.Tier.BASIC.color, VerifiedBadge.Tier.PREMIUM.color)
        assertFalse(VerifiedBadge.show("", VerifiedBadge.Tier.BASIC))
        assertFalse(VerifiedBadge.show("Dheeraj", null))
        assertTrue(VerifiedBadge.show("Dheeraj", VerifiedBadge.Tier.OWNER))
        val sheet = src("dkai/DkaiSheets.kt")
        assertTrue(sheet.contains("putString(\"real_name\"")); assertTrue(sheet.contains("putString(\"user_name\""))
        assertTrue(sheet.contains("DK आपको किस नाम से बुलाए"))
    }
}
