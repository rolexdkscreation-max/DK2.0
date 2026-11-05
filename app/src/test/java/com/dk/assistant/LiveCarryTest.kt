package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveCarryTest {
    private val now = 10_000_000_000L
    private fun t(r: String, s: String, agoMin: Int) = LiveCarry.Turn(r, s, now - agoMin * 60_000L)

    @Test fun empty_or_old_nothing() {
        assertEquals("", LiveCarry.note(emptyList(), now))
        assertEquals("", LiveCarry.note(listOf(t("u", "पुरानी बात", 45)), now))
    }

    @Test fun recentTurns_inOrder_sameConversation() {
        val n = LiveCarry.note(listOf(t("u", "मेरा नाम राहुल है", 5), t("d", "ठीक है राहुल", 5), t("u", "कल नासिक जाना है", 2)), now)
        assertTrue(n.contains("SAME conversation"))
        val a = n.indexOf("User: मेरा नाम राहुल है"); val b = n.indexOf("DK: ठीक है राहुल"); val c = n.indexOf("User: कल नासिक जाना है")
        assertTrue(a in 0 until b && b < c)
        assertTrue(n.contains("do NOT greet again"))
    }

    @Test fun capped_lastTurnsKept() {
        val many = (1..40).map { t(if (it % 2 == 0) "d" else "u", "बात नंबर $it " + "क".repeat(400), 1) }
        val n = LiveCarry.note(many, now)
        assertTrue(n.length < LiveCarry.MAX_CHARS + 400)
        assertTrue(n.contains("बात नंबर 40"))
        assertFalse(n.contains("बात नंबर 1 "))
    }

    @Test fun wired_onlyWithoutHandle() {
        val p = "src/main/java/com/dk/assistant/GeminiLiveSetup.kt"
        val s = listOf(File(p), File("app/$p")).first { it.exists() }.readText()
        assertTrue(s.contains("if (resume && handle != null) return \"\""))
        assertTrue(s.contains("NetSpeed.promptNote() + carryNote(resume) +"))
        val r = listOf(File("src/main/java/com/dk/assistant/GeminiLiveRecover.kt"), File("app/src/main/java/com/dk/assistant/GeminiLiveRecover.kt")).first { it.exists() }.readText()
        assertTrue(r.contains("stuckNoSwap()"))
    }
}
