package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 13 नवंबर 2026 (मालिक): कोडिंग एजेंट क्या कर रहा है - एक लाइन, उसी जगह, हर नए कदम पर वही लाइन बदले */
class CoderNowTest {
    private fun src(f: String) = listOf(File("src/main/java/com/dk/assistant/$f"), File("app/src/main/java/com/dk/assistant/$f")).first { it.exists() }.readText()

    @Test fun latestStepAfterUserMessage_replacesOld() {
        val l = mutableListOf("u" to "बटन ठीक करो", "s" to "rolex/DK2.0 (main) की फ़ाइलें ला रहा हूँ…")
        assertEquals("rolex/DK2.0 (main) की फ़ाइलें ला रहा हूँ…", CoderNow.line(l, busy = true))
        l.add("s" to "पढ़ रहा हूँ: app/Main.kt")
        assertEquals("पढ़ रहा हूँ: app/Main.kt", CoderNow.line(l, busy = true))     // पुरानी हटकर नई
        l.add("d" to "बीच का जवाब")
        assertEquals("पढ़ रहा हूँ: app/Main.kt", CoderNow.line(l, busy = true))     // जवाब से कदम नहीं मिटता
        l.add("s" to "PR checks: build चल रहा है")
        assertEquals("PR checks: build चल रहा है", CoderNow.line(l, busy = true))
    }

    @Test fun newTask_noStepYet_thinking_notOldStep() {
        val l = listOf("u" to "पहला", "s" to "लिखा: a.kt", "d" to "हो गया", "u" to "दूसरा काम")
        assertEquals(CoderNow.THINK, CoderNow.line(l, busy = true))
        assertEquals(CoderNow.THINK, CoderNow.line(emptyList(), busy = true))
        assertEquals(CoderNow.THINK, CoderNow.line(listOf("u" to "x", "s" to "…"), busy = true))
    }

    @Test fun notBusy_empty() {
        assertEquals("", CoderNow.line(listOf("u" to "x", "s" to "पढ़ रहा हूँ: a"), busy = false))
    }

    @Test fun oneLine_shortened() {
        assertEquals("खोज: foo bar", CoderNow.clean("  खोज:   foo\tbar \nदूसरी लाइन"))
        val long = "बदला: " + "x".repeat(200)
        val c = CoderNow.clean(long)
        assertEquals(CoderNow.MAX, c.length); assertTrue(c.endsWith("…")); assertFalse(c.contains("\n"))
    }

    @Test fun statusFirst_thenStep() {
        assertEquals("दोबारा कोशिश…", CoderNow.label("दोबारा कोशिश…", "पढ़ रहा हूँ: a"))
        assertEquals("पढ़ रहा हूँ: a", CoderNow.label(null, "पढ़ रहा हूँ: a"))
        assertEquals("पढ़ रहा हूँ: a", CoderNow.label("  ", "पढ़ रहा हूँ: a"))
    }

    @Test fun wired_bubbleSingleLine_smoothSwap() {
        val a = src("CodingActivity.kt")
        assertTrue(a.contains("CoderNow.line(CoderSession.lines, CoderSession.busy"))
        val u = src("DKChatUi.kt")
        assertTrue(u.contains("isSingleLine = true; maxLines = 1"))
        assertTrue(u.contains("translationY(-d).setDuration(LINE_OUT_MS)"))
    }
}
