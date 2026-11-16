package com.dk.assistant

import com.dk.assistant.agent.social.SocialSmart
import com.dk.assistant.plus.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026: सोशल पोस्ट स्पेशलिस्ट - स्विच, Settings, DK लाइव tool, बोलने वाला नाम */
class SocialSpecialistWiringTest {
    private fun src(rel: String) = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }.readText()

    @Test fun flag_isReady_offByDefault_andInSettings() {
        val f = FeatureFlags.byKey(FeatureFlags.SOCIAL_SPECIALIST)
        assertNotNull(f); assertEquals(FeatureFlags.Section.SOCIAL, f!!.section)
        assertFalse(FeatureFlags.defaultOf(FeatureFlags.SOCIAL_SPECIALIST))
        assertTrue(FeatureFlags.ready(FeatureFlags.SOCIAL_SPECIALIST))
        val item = DkSettings.ITEMS.first { it.pref == FeatureFlags.SOCIAL_SPECIALIST }
        assertEquals(DkSettings.STORE_FLAGS, item.store)
        assertTrue(SettingsSearch.ENTRIES.any { it.section == "social" && it.card == "सोशल पोस्ट स्पेशलिस्ट" })
    }

    @Test fun settingsCard_isHindi_collapsible_andHonest() {
        val s = src("SettingsSocial.kt")
        assertTrue(s.contains("card(\"सोशल पोस्ट स्पेशलिस्ट\""))
        val start = s.indexOf("card(\"सोशल पोस्ट स्पेशलिस्ट\"")
        assertFalse(s.substring(start, s.indexOf('\n', start)).contains("startOpen = true"))   // बंद (collapsible) शुरू
        assertTrue(s.contains("गाना सिर्फ़ तब लगता है जब आप कहें"))
        assertTrue(s.contains("\\\"हाँ\\\" कहने पर ही पब्लिक"))
        assertTrue(s.contains("मॉडल डाउनलोड करो")); assertTrue(s.contains("गैलरी की इजाज़त दो"))
    }

    @Test fun liveTool_knowsYoutube_andNewFields() {
        val s = src("GeminiLiveSetup.kt")
        val i = s.indexOf("\"social_media_task\")")
        val block = s.substring(i, s.indexOf("social_insights", i))
        listOf("youtube", "media_query", "media_days", "auto_caption", "caption_hint", "caption_language", "\"song\"", "\"tags\"", "latest_gallery", "never add a song on your own")
            .forEach { assertTrue("missing $it", block.contains(it)) }
    }

    @Test fun agent_needsSwitch_forSmartWork() {
        val s = src("agent/android/SocialAgent.kt")
        assertTrue(s.contains("req.smart && !smartOn"))
        assertTrue(s.contains("SocialPlanner.unsupported(req)"))
    }

    @Test fun mediaLabel_speaksNaturally() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        assertEquals("आपकी आज की फोटो", SocialSmart.mediaLabel(now - 3_600_000, now, false))
        assertEquals("आपकी कल की फोटो", SocialSmart.mediaLabel(now - day - 5, now, false))
        assertEquals("आपका कल का वीडियो", SocialSmart.mediaLabel(now - day - 5, now, true))
        assertEquals("आपकी 5 दिन पुरानी फोटो", SocialSmart.mediaLabel(now - 5 * day - 5, now, false))
        assertEquals("आपका पुराना वीडियो", SocialSmart.mediaLabel(now - 400 * day, now, true))
        assertEquals("आपकी फोटो", SocialSmart.mediaLabel(0, now, false))
    }
}
