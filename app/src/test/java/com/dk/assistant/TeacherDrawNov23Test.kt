package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 23 नवंबर 2026 (मालिक: "टीचर मोड का UI बाकी जैसा, पेंसिल बाय डिफ़ॉल्ट नहीं - टाइप; DK ड्रॉ करके समझाए;
 * बारीकी से, स्टेप बाय स्टेप, उदाहरण के साथ, न समझ आए तो बार-बार समझाए").
 */
class TeacherDrawNov23Test {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Before fun clean() { TeacherBoard.reset() }

    @Test fun parsesTeacherSketch() {
        val f = TeacherDraw.parse("""
            title सूरज और पृथ्वी
            size 100 70
            circle 25 35 12 orange fill
            circle 75 35 5 blue fill
            arrow 38 35 68 35 red
            text 25 55 "सूरज" big
            text 75 48 पृथ्वी
            poly 10 60 20 50 30 60
            curve 10 10 50 0 90 10 green
            dot 50 50
            rect 5 5 20 10 #FF00AA
        """.trimIndent())
        assertNotNull(f); f!!
        assertEquals("सूरज और पृथ्वी", f.title)
        assertEquals(70f, f.h)
        assertEquals(9, f.shapes.size)
        val sun = f.shapes[0]
        assertEquals(TeacherDraw.Kind.CIRCLE, sun.kind); assertTrue(sun.fill); assertEquals(TeacherDraw.COLORS["orange"], sun.color)
        assertEquals(listOf(25f, 35f, 12f), sun.pts)
        assertEquals("सूरज", f.shapes[3].text); assertEquals(1.4f, f.shapes[3].size)
        assertEquals("पृथ्वी", f.shapes[4].text)                     // बिना quote भी
        assertEquals(6, f.shapes[5].pts.size)
        assertEquals(0xFFFF00AA.toInt(), f.shapes[8].color)
        assertEquals(TeacherDraw.INK, f.shapes[7].color)               // रंग न दिया = काली स्याही
    }

    @Test fun badLinesAreSkipped_neverCrash() {
        assertNull(TeacherDraw.parse(""))
        assertNull(TeacherDraw.parse("hello world\ncircle 1\nline a b c d\ntext 5 5"))
        val f = TeacherDraw.parse("line 0 0 10 10; banana 1 2; circle 5 5 99999")!!
        assertEquals(1, f.shapes.size)
        val many = (1..400).joinToString("\n") { "dot $it 5" }.let { TeacherDraw.parse(it) }!!
        assertTrue(many.shapes.size <= TeacherDraw.MAX_SHAPES)
        assertEquals(TeacherDraw.DEFAULT_H, TeacherDraw.parse("size 100 9999\ndot 1 1")!!.h.coerceAtMost(TeacherDraw.DEFAULT_H))
    }

    @Test fun drawingCodeIsNeverSpoken() {
        val a = "चलो देखते हैं। [DRAW]circle 50 30 10[/DRAW] ऊपर वाला गोला सूरज है।"
        assertEquals("चलो देखते हैं। ऊपर वाला गोला सूरज है।", TeacherDraw.strip(a))
        assertEquals(listOf("circle 50 30 10"), TeacherDraw.figures(a))
        // अधूरा (बंद नहीं) = आगे का कुछ भी नहीं बोला जाता
        assertEquals("देखिए", TeacherDraw.strip("देखिए [DRAW] circle 50 30"))
        assertEquals("बस text", TeacherDraw.strip("बस text"))
    }

    @Test fun boardShowsFigureInPlace() {
        val lines = TeacherBoard.format("प्रकाश संश्लेषण:\n[DRAW]\ncircle 50 20 10 yellow fill\narrow 50 32 50 50\n[/DRAW]\nपत्ती धूप से खाना बनाती है।")
        val fig = lines.indexOfFirst { it.kind == TeacherBoard.Kind.FIGURE }
        assertTrue(fig >= 1)
        assertTrue(lines[fig].text.contains("circle 50 20 10"))
        assertTrue(lines.drop(fig + 1).any { it.text.contains("पत्ती") })
        // ख़राब चित्र = बोर्ड पर कुछ नहीं (कोड लिखा नहीं दिखता)
        assertFalse(TeacherBoard.format("देखो [DRAW]कुछ भी[/DRAW] ठीक").any { it.kind == TeacherBoard.Kind.FIGURE || it.text.contains("कुछ भी") })
    }

    @Test fun liveDrawAddsUnderCurrentQuestion() {
        assertFalse(TeacherBoard.addFigure("x", "not a figure"))
        assertTrue(TeacherBoard.addFigure("दिल", "circle 50 30 10 red fill"))
        var b = TeacherBoard.boardLines()
        assertEquals(TeacherBoard.Kind.QUESTION, b[0].kind); assertTrue(b[0].text.contains("दिल"))
        assertEquals(TeacherBoard.Kind.FIGURE, b[1].kind)
        // बोलकर समझाया = उसी हिस्से में, चित्र नहीं हटता
        TeacherBoard.onReply("यह दिल है। यह खून पंप करता है।", final = true)
        b = TeacherBoard.boardLines()
        assertEquals(TeacherBoard.Kind.FIGURE, b[1].kind)
        assertTrue(b.any { it.text.contains("खून") })
    }

    @Test fun liveToolResults() {
        assertTrue(LiveConnect.drawResult(true, false).startsWith("drawn"))
        assertTrue(LiveConnect.drawResult(true, true).contains("opened"))
        val bad = LiveConnect.drawResult(false, false)
        assertTrue(bad.startsWith("not_drawn") && bad.contains(TeacherDraw.GRAMMAR))
        assertTrue(LiveConnect.teacherResult(true, false).contains(TeacherDraw.GRAMMAR))     // खुलते ही चित्र की भाषा पता
        assertTrue(TeacherBoard.block().contains("teacher_draw"))
        assertTrue(TeacherBoard.block().contains("never repeat the same words"))
        assertTrue("teacher_draw" in LeakedCall.TOOLS)
        assertTrue(src("GeminiLiveSetup.kt").contains("\"teacher_draw\""))
        assertTrue(src("GeminiLiveTools.kt").contains("\"teacher_draw\" -> LiveReport.teacherDraw("))
    }

    @Test fun pencilOffByDefault_typeFirst() {
        val wb = src("WhiteboardView.kt")
        assertTrue(wb.contains("var currentMode: Mode = Mode.TEXT"))
        assertFalse(wb.contains("var currentMode: Mode = Mode.DRAW"))
        val t = src("TeacherActivity.kt")
        assertTrue(t.contains("private var pencilOn = false"))
        assertTrue(t.contains("setPencil(false)"))
        assertTrue(t.contains("if (!pencilOn || !hasInk()) return@Runnable"))   // पेंसिल बंद = अपने-आप सवाल नहीं जाता
    }

    @Test fun sameLookAsRestOfApp_andTeachesWithContext() {
        val t = src("TeacherActivity.kt")
        assertTrue(t.contains("DkaiUi.page(c, "))
        assertTrue(t.contains("Clay.card(c)"))
        assertFalse(t.contains("Color.parseColor(\"#F0F4F8\")"))
        assertFalse(t.contains("setBackgroundColor(Color.WHITE)"))
        assertTrue(t.contains("TeacherDraw.WRITTEN_RULE"))
        assertTrue(t.contains("पिछली पढ़ाई"))
        assertTrue(t.contains("TeacherDraw.strip(response"))
        assertTrue(t.contains("उदाहरण"))
    }
}
