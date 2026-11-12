package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): DK कंप्यूटर / कोडिंग एजेंट / ऐप स्टूडियो - AI जो कोड लिख रहा है, साइड में पूरा दिखे,
 * और कोड का रंग (थीम) बदलने का विकल्प.
 */
class CodeLiveTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Before fun reset() = CodeLive.clear()

    @Test fun writesAreKept_latestPerFile_newestFirst() {
        CodeLive.put(CodeLive.WHO_CODER, "/app/A.kt", "fun a() = 1")
        CodeLive.put(CodeLive.WHO_COMPUTER, "report.md", "# hi")
        CodeLive.put(CodeLive.WHO_CODER, "app/B.kt", "val b = 2")
        val w = CodeLive.put(CodeLive.WHO_CODER, "app/A.kt", "fun a() = 1\nfun c() = 3", "fun a() = 1")
        val coder = CodeLive.files { it == CodeLive.WHO_CODER }
        assertEquals(listOf("app/A.kt", "app/B.kt"), coder.map { it.path })      // "/" हटा, हर फ़ाइल एक बार, नई पहले
        assertEquals(w.id, coder.first().id)
        assertEquals(2, CodeLive.count { it == CodeLive.WHO_CODER })
        assertEquals("report.md", CodeLive.latest { it == CodeLive.WHO_COMPUTER }?.path)
        assertNull(CodeLive.latest { CodeLive.isStudio(it) })
        assertFalse(w.isNew); assertEquals(2, w.lines); assertEquals("A.kt", w.name)
    }

    @Test fun listenersHearEveryWrite_andDelete() {
        val got = mutableListOf<String>()
        val l: (CodeLive.Write) -> Unit = { got.add((if (it.deleted) "-" else "+") + it.path) }
        CodeLive.listen(l)
        CodeLive.put(CodeLive.studio("todo"), "index.html", "<p>x</p>")
        CodeLive.gone(CodeLive.studio("todo"), "old.js", "x")
        CodeLive.unlisten(l)
        CodeLive.put(CodeLive.WHO_CODER, "after.kt", "x")
        assertEquals(listOf("+index.html", "-old.js"), got)
        assertTrue(CodeLive.isStudio(CodeLive.studio("todo")))
    }

    @Test fun keepsOnlyRecent_andCutsHugeFiles() {
        repeat(CodeLive.MAX_ITEMS + 15) { CodeLive.put(CodeLive.WHO_CODER, "f$it.kt", "x") }
        assertEquals(CodeLive.MAX_ITEMS, CodeLive.count { true })
        val big = CodeLive.put(CodeLive.WHO_CODER, "big.txt", "a".repeat(CodeLive.MAX_CHARS + 500))
        assertTrue(big.text.length < CodeLive.MAX_CHARS + 200)
    }

    @Test fun changedLines_onlyTheEditedPart() {
        assertEquals(0 until 3, CodeLive.changedLines(null, "a\nb\nc"))                     // नई फ़ाइल = सब
        assertEquals(1 until 2, CodeLive.changedLines("a\nb\nc", "a\nB\nc"))
        assertEquals(1 until 3, CodeLive.changedLines("a\nc", "a\nx\ny\nc"))               // बीच में जोड़ा
        assertTrue(CodeLive.changedLines("a\nb", "a\nb").isEmpty())                          // कुछ नहीं बदला
        assertEquals(1 until 2, CodeLive.changedLines("a\nb\nc", "a\nc"))                     // लाइन हटी = उसी जगह निशान
    }

    @Test fun colors_kotlinLine() {
        val line = "    val name = \"DK\" // नाम"
        val s = CodeLive.lineSpans(line, "kotlin").spans
        fun kind(word: String) = s.firstOrNull { line.substring(it.start, it.end).startsWith(word) }?.kind
        assertEquals(CodeLive.Kind.KEYWORD, kind("val"))
        assertEquals(CodeLive.Kind.STRING, kind("\"DK\""))
        assertEquals(CodeLive.Kind.COMMENT, kind("// नाम"))
        val t = CodeLive.lineSpans("@Test fun x(): List<String> = listOf(42)", "kotlin").spans.map { it.kind }
        assertTrue(t.contains(CodeLive.Kind.TAG)); assertTrue(t.contains(CodeLive.Kind.TYPE)); assertTrue(t.contains(CodeLive.Kind.NUMBER))
    }

    @Test fun colors_blockCommentAcrossLines() {
        val a = CodeLive.lineSpans("int x = 1; /* शुरू", "c")
        assertTrue(a.inBlock)
        val b = CodeLive.lineSpans("अब भी टिप्पणी", "c", a.inBlock)
        assertTrue(b.inBlock); assertEquals(CodeLive.Kind.COMMENT, b.spans.single().kind)
        val c = CodeLive.lineSpans("खत्म */ return 0;", "c", b.inBlock)
        assertFalse(c.inBlock); assertTrue(c.spans.any { it.kind == CodeLive.Kind.KEYWORD })
    }

    @Test fun colors_htmlAndCssAndPython() {
        val h = "<a href=\"/x\">it's text</a> <!-- note"
        val r = CodeLive.lineSpans(h, CodeLive.langOf("index.html"))
        assertTrue(r.inBlock)
        assertEquals(CodeLive.Kind.TAG, r.spans.first().kind)
        assertTrue(r.spans.any { it.kind == CodeLive.Kind.STRING && h.substring(it.start, it.end) == "\"/x\"" })
        // टैग के बाहर का ' (it's) पाठ है, string नहीं
        assertFalse(r.spans.any { it.kind == CodeLive.Kind.STRING && h.substring(it.start, it.end).startsWith("'") })
        // CSS का #id टिप्पणी नहीं
        assertFalse(CodeLive.lineSpans("#app { color: #fff; }", "css").spans.any { it.kind == CodeLive.Kind.COMMENT })
        assertEquals(CodeLive.Kind.COMMENT, CodeLive.lineSpans("x = 1  # note", "python").spans.last().kind)
        assertTrue(CodeLive.lineSpans("anything 'here'", "text").spans.isEmpty())
    }

    @Test fun langFromFileName() {
        assertEquals("kotlin", CodeLive.langOf("app/src/Main.kt"))
        assertEquals("markup", CodeLive.langOf("res/layout/a.xml"))
        assertEquals("js", CodeLive.langOf("app.js"))
        assertEquals("python", CodeLive.langOf("x.py"))
        assertEquals("css", CodeLive.langOf("style.css"))
        assertEquals("text", CodeLive.langOf("README.md"))
        assertEquals("sh", CodeLive.langOf(".github/workflows/ci.yml"))
    }

    @Test fun sixColorThemes_withSafeDefault() {
        assertEquals(6, CodeLive.THEMES.size)
        assertEquals(CodeLive.THEMES.size, CodeLive.THEMES.map { it.id }.distinct().size)
        assertEquals(CodeLive.DEFAULT_THEME, CodeLive.theme("नहीं-है").id)
        assertEquals("monokai", CodeLive.theme("monokai").id)
        assertTrue(CodeLive.theme("light").light); assertFalse(CodeLive.theme("neon").light)
        // हर थीम में कीवर्ड/पाठ/टिप्पणी के रंग अलग और पृष्ठभूमि से अलग (पढ़ा जा सके)
        for (t in CodeLive.THEMES) {
            assertTrue(t.id, setOf(t.keyword, t.string, t.comment).size == 3)
            assertTrue(t.id, t.fg != t.bg && t.keyword != t.bg)
        }
    }

    @Test fun typingSpeed_bigFileStillQuick() {
        assertEquals(18, CodeLive.revealStep(100))
        assertTrue(CodeLive.ANIMATE_MAX / CodeLive.revealStep(CodeLive.ANIMATE_MAX) * 16 <= 3000)   // ~2.5 सेकंड में पूरी
    }

    @Test fun statusLine() {
        assertTrue(CodeLive.status(null).contains("यहीं दिखेगी"))
        assertTrue(CodeLive.status(CodeLive.put(CodeLive.WHO_CODER, "a.kt", "x\ny")).startsWith("✍ नई फ़ाइल: a.kt • 2 लाइन"))
        assertTrue(CodeLive.status(CodeLive.put(CodeLive.WHO_CODER, "a.kt", "x\nY", "x\ny")).contains("लाइन 2-2"))
        assertTrue(CodeLive.status(CodeLive.gone(CodeLive.WHO_CODER, "a.kt", "x")).startsWith("🗑"))
        assertEquals("ऐप स्टूडियो", CodeLive.whoLabel(CodeLive.studio("x")))
    }

    @Test fun everyAgentReportsItsWrites() {
        val comp = src("ComputerAgent.kt")
        assertTrue(comp.contains("CodeLive.put(CodeLive.WHO_COMPUTER, f.name, a.optString(\"content\"), old)"))
        assertTrue(comp.contains("CodeLive.put(CodeLive.WHO_COMPUTER, \"run_js.js\""))
        val tools = src("CodingAgentTools.kt")
        assertTrue(tools.contains("CodeLive.put(CodeLive.WHO_CODER, path, content, old)"))
        assertTrue(tools.contains("CodeLive.put(CodeLive.WHO_CODER, path, cur, before)"))
        assertTrue(tools.contains("CodeLive.gone(CodeLive.WHO_CODER, path, cur)"))
        assertTrue(src("CodingAgent.kt").contains("CodeLive.put(CodeLive.WHO_CODER, path, content, before)"))   // 7 एजेंट की टीम भी
        val st = src("studio/StudioSession.kt")
        assertTrue(st.contains("CodeLive.put(com.dk.assistant.CodeLive.studio(p.slug), w, body, old)"))
        assertTrue(st.contains("CodeLive.gone(com.dk.assistant.CodeLive.studio(p.slug), d, old)"))
    }

    @Test fun sidePanelOnAllThreeScreens() {
        for (f in listOf("ComputerActivity.kt", "CodingActivity.kt", "studio/StudioActivity.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("CodeLivePanel(this"))
            assertTrue(f, s.contains("codePanel.attachTo("))
            assertTrue(f, s.contains("codePanel.toggle()"))
            assertTrue(f, s.contains("if (codePanel.isOpen) codePanel.close()"))       // back = पहले पैनल बंद
        }
        val p = src("CodeLivePanel.kt")
        assertTrue(p.contains("CodeLive.setTheme(act, th.id)"))                         // रंग बदलो
        assertTrue(p.contains("CodeLive.listen(listener)") && p.contains("CodeLive.unlisten(listener)"))
        assertTrue(p.contains("UiLang.keep(code)"))                                    // कोड कभी अनुवाद नहीं
    }
}
