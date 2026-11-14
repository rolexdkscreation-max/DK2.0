package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): DK कंप्यूटर / कोडिंग एजेंट के "काम Running…" बुलबुले में छोटा रंगीन, हिलता कंप्यूटर + Settings में विकल्प */
class PcTypingTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun computerStyleChoosable() {
        assertTrue(TypingStyles.ALL.any { it.first == TypingStyles.COMPUTER })
        assertEquals(TypingStyles.COMPUTER, TypingStyles.valid(TypingStyles.COMPUTER))
        assertEquals(TypingStyles.COMPUTER, TypingStyles.validAgent(null))
        assertEquals(TypingStyles.COMPUTER, TypingStyles.valid(null))
    }

    @Test fun computerMovesWhileWorking_staysInside() {
        val a = FloatArray(4); val b = FloatArray(4)
        TypingStyles.computer(1.0, 1f, a); TypingStyles.computer(4.0, 1f, b)
        var diff = 0f; for (i in 0 until 4) diff += kotlin.math.abs(a[i] - b[i])
        assertTrue(diff > 0.01f)
        for (p in 0 until 60) {
            TypingStyles.computer(p * 0.37, 1f, a)
            assertTrue(kotlin.math.abs(a[0]) <= 0.1f && kotlin.math.abs(a[1]) <= 0.06f && a[3] in 0f..1f)
        }
        TypingStyles.computer(3.0, 0f, a); assertEquals(0f, a[0], 1e-6f); assertEquals(0f, a[1], 1e-6f)
    }

    @Test fun agentScreensUseIt() {
        assertTrue(src("ComputerActivity.kt").contains("DKInlineReply(list, scroll, 0.86f, agent = true)"))
        assertTrue(src("CodingActivity.kt").contains("DKInlineReply(list, scroll, 0.82f, agent = true)"))
        assertTrue(src("ChatActivity.kt").contains("DKInlineReply(list, scroll, 0.78f)"))
        assertTrue(src("DKWorkingIndicator.kt").contains("if (agent) Prefs.typingStyleAgent(context) else Prefs.typingStyle(context)"))
        assertTrue(src("Prefs.kt").contains("getString(\"typing_style\", TypingStyles.COMPUTER)"))
        assertTrue(src("Prefs.kt").contains("getString(\"typing_style_agent\", TypingStyles.COMPUTER)"))
        val st = src("SettingsLook.kt")
        assertTrue(st.contains("putString(\"typing_style_agent\", v)"))
        assertTrue(st.contains("radios(box, TypingStyles.ALL, cur, 6400)"))
    }
}
