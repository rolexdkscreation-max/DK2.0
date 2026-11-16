package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026 (ऑडिट Q1): निगली गई गलतियाँ अब गिनी जाती हैं; काम पहले जैसा, कुछ नहीं फेंकता */
class QuietTest {
    @Test fun countsButNeverThrows() {
        Quiet.clear()
        repeat(3) { try { error("टूटा") } catch (e: Exception) { Quiet.note(e) } }
        Quiet.note(kotlinx.coroutines.CancellationException("रुका"))      // रुकवाया गया काम = गलती नहीं
        assertEquals(3, Quiet.total())
        val s = Quiet.summary()
        assertTrue(s, s.contains("3×") && s.contains("QuietTest.kt:"))
        Quiet.clear()
        assertEquals("कोई दबी गलती नहीं", Quiet.summary())
    }

    @Test fun noSilentExceptionCatchLeft() {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val rx = Regex("""catch \(_: Exception\) *\{ *\}""")
        val bad = dir.walk().filter { it.extension == "kt" && it.name != "Quiet.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (rx.containsMatchIn(l)) "${f.name}:${i + 1}" else null } }.toList()
        assertTrue(bad.take(10).toString(), bad.isEmpty())
        val det = listOf(File("../config/detekt/detekt.yml"), File("config/detekt/detekt.yml")).first { it.exists() }.readText()
        assertFalse(det.contains("allowedExceptionNameRegex: '_"))
    }
}
