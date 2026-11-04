package com.dk.assistant.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): वेबसाइट बिल्डर में key डालने पर भी "limit खत्म / key नहीं" और मॉडल अपने-आप नहीं चुना जाता था.
 * वजह: सब Pro मॉडल पहले (free key पर Pro की limit 0) और सिर्फ़ 4 कोशिशें. अब हर तरह का एक-एक पहले + साफ़ जाँच.
 */
class ForgeKeyCheckTest {
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    private val ranked = WebsiteForgeClient.rankForWebsite(listOf(
        "gemini-3.5-pro", "gemini-3-pro-preview", "gemini-2.5-pro", "gemini-3.5-flash", "gemini-3-flash", "gemini-3.5-flash-lite"))

    @Test fun diverseTriesFlashSecond() {
        val d = WebsiteForgeClient.diverse(ranked)
        assertEquals("gemini-3.5-pro", d[0])
        assertEquals("gemini-3.5-flash", d[1])
        assertEquals("gemini-3.5-flash-lite", d[2])
        assertEquals(ranked.toSet(), d.toSet())
        assertEquals(ranked.size, d.size)
        // पहली 4 कोशिशों में Flash ज़रूर (पहले सब Pro थे)
        assertTrue(d.take(4).any { WebsiteForgeClient.tier(it) == 1 })
    }

    @Test fun diverseKeepsPreferredFirst() {
        val d = WebsiteForgeClient.withPreferred(WebsiteForgeClient.diverse(ranked), "gemini-3-flash")
        assertEquals("gemini-3-flash", d[0])
        assertEquals(1, d.count { it == "gemini-3-flash" })
    }

    @Test fun diverseEmptyAndSingle() {
        assertTrue(WebsiteForgeClient.diverse(emptyList()).isEmpty())
        assertEquals(listOf("gemini-3.5-pro"), WebsiteForgeClient.diverse(listOf("gemini-3.5-pro")))
    }

    @Test fun tiers() {
        assertEquals(0, WebsiteForgeClient.tier("gemini-3.5-pro"))
        assertEquals(1, WebsiteForgeClient.tier("gemini-3.5-flash"))
        assertEquals(2, WebsiteForgeClient.tier("gemini-3.5-flash-lite"))
    }

    @Test fun moreTriesThanBefore() {
        assertTrue(WebsiteForgeClient.STREAM_TRIES >= 6)
        assertTrue(WebsiteForgeClient.PROBE_MAX >= WebsiteForgeClient.STREAM_TRIES)
    }

    @Test fun probeLabelsAreClear() {
        assertTrue(WebsiteForgeClient.probeLabel(200).startsWith("✓"))
        assertTrue(WebsiteForgeClient.probeLabel(429).contains("limit"))
        assertTrue(WebsiteForgeClient.probeLabel(-1).startsWith("✗"))
        assertFalse(WebsiteForgeClient.probeLabel(503).startsWith("✓"))
    }

    @Test fun wiring() {
        val w = src("forge/WebsiteForgeClient.kt")
        assertTrue(w.contains("models.take(STREAM_TRIES)"))
        assertFalse(w.contains("models.take(4)"))
        assertTrue(w.contains("withPreferred(diverse(rankForWebsite("))
        assertTrue(w.contains("probeHttp.newCall(req)"))
        val c = src("forge/ForgeController.kt")
        assertTrue(c.contains("onModel = { m -> rememberModel(c, m) }"))
        assertTrue(c.contains("putString(Prefs.FORGE_AUTO_MODEL, m)"))
        val s = src("SettingsBrain.kt")
        assertTrue(s.contains("✓ key चल रही है • सबसे अच्छा मॉडल: \$best"))
        assertTrue(s.contains("WebsiteForgeClient.probeLabel(c)"))
        assertTrue(s.contains("ed.postDelayed(autoCheck, 1200)"))
        assertTrue(s.contains("कोडिंग/चैट वाली Gemini key यहाँ लगाएँ"))
        assertTrue(s.contains("वेबसाइट बनाने की key अलग है: AI दिमाग → वेबसाइट बिल्डर"))
    }
}
