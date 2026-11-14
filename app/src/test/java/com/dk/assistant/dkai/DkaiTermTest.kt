package com.dk.assistant.dkai

import com.dk.assistant.Grounding
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 13 नवंबर 2026: एडवांस DKAI Terminal + DK लाइव "terminal" tool + नए orb + chat में स्रोत - असली नियम */
class DkaiTermTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()












    @Test fun orbs() {
        val keys = DkaiOrbs.ORBS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.size >= 8)
        for (k in listOf("aurora", "sunset", "reactor", "solar", "crystal", "swarm", "classic")) assertTrue(k, k in keys)
        assertEquals(DkaiOrbs.FLUID, DkaiOrbs.valid("बेकार"))
        assertEquals(DkaiOrbs.FLUID, DkaiOrbs.valid(null))
        assertEquals("reactor", DkaiOrbs.valid("reactor"))
        for (k in keys) assertEquals(3, DkaiOrbs.palette(k).size)
        assertEquals(0x80123456.toInt(), DkaiOrbs.a(0xFF123456.toInt(), 0x80))
        val views = src("dkai/DkaiViews.kt")
        for (f in listOf("fun reactor(", "fun solar(", "fun crystal(", "fun swarm(")) assertTrue(f, views.contains(f))
        assertTrue(src("dkai/DkaiVoice.kt").contains("DkaiOrbs.pick"))
        // 13 नवंबर 2026 (मालिक): orb बदलना ☰ मेनू से हटकर Settings → दिखावट में
        assertTrue(src("SettingsLook.kt").contains("card(\"DKAI का orb\""))
        assertTrue(src("SettingsLook.kt").contains("DkaiOrbs.ORBS"))
    }

    @Test fun chatShowsSources() {
        assertEquals("ndtv.com", Grounding.site("ndtv.com", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc"))
        assertEquals("en.wikipedia.org", Grounding.site("Some page", "https://en.wikipedia.org/wiki/X"))
        assertEquals(listOf("a.com", "b.org"), Grounding.sites(listOf("x" to "https://a.com/1", "y" to "https://www.a.com/2", "z" to "https://b.org")))
        val screen = src("dkai/DkaiScreen.kt")
        assertTrue(screen.contains("chatList.addView(sources(m.src))"))
        assertTrue(screen.contains("Grounding.sites"))
        assertTrue(src("SourcesView.kt").contains("Grounding.site("))
    }


}
