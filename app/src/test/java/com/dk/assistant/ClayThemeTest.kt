package com.dk.assistant

import com.dk.assistant.dkai.DkaiHomeCore
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 23 नवंबर 2026: Soft Clay थीम — 8 थीम, हर थीम में अक्षर साफ़ पढ़े जाएँ */
class ClayThemeTest {
    @Test fun eightThemes() {
        assertEquals(listOf("Clay Day", "Midnight", "Nocturne", "Ember", "Abyss", "Gehra navy", "Obsidian", "Rosewood"),
            ClayTheme.ALL.map { it.name })
        assertEquals(8, ClayTheme.ALL.map { it.id }.toSet().size)
        assertEquals(listOf("clay_day"), ClayTheme.ALL.filter { it.light }.map { it.id })
        assertEquals(ClayTheme.MIDNIGHT, ClayTheme.of("nahi-hai"))
    }

    @Test fun readableInEveryTheme() {
        for (th in ClayTheme.ALL) {
            val t = ClayTheme.tokens(th)
            assertTrue("${th.name} text/surface", NeonTheme.contrast(t.text, th.surface) >= 7.0)
            assertTrue("${th.name} text/bg", NeonTheme.contrast(t.text, th.bg) >= 7.0)
            assertTrue("${th.name} sub", NeonTheme.contrast(t.sub, th.surface) >= 4.5)
            assertTrue("${th.name} onAccent", NeonTheme.contrast(t.onAccent, NeonTheme.mix(t.accentTop, t.accentBottom, 0.5f)) >= 3.0)
        }
    }

    @Test fun greetingByHour() {
        assertEquals("Good morning", DkaiHomeCore.greeting(8).trimEnd(','))
        assertEquals("Good afternoon", DkaiHomeCore.greeting(14).trimEnd(','))
        assertEquals("Good evening", DkaiHomeCore.greeting(20).trimEnd(','))
    }
}
