package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026: Futuristic रूप के बिना-Android हिस्से (कोड अलग करना, रंग, इंजन टैग) */
class FutureUiTest {

    @Test fun plainText_staysOnePart() {
        assertFalse(FutureUi.hasCode("नमस्ते"))
        assertEquals(listOf(FutureUi.Part(false, "नमस्ते")), FutureUi.split("नमस्ते"))
        assertTrue(FutureUi.split("  ").isEmpty())
    }

    @Test fun fencedCode_splitsWithLanguage() {
        val p = FutureUi.split("यह लो:\n```kotlin\nval a = 1\nprintln(a)\n```\nबस इतना।")
        assertEquals(3, p.size)
        assertEquals(FutureUi.Part(false, "यह लो:"), p[0])
        assertEquals(FutureUi.Part(true, "val a = 1\nprintln(a)", "kotlin"), p[1])
        assertEquals(FutureUi.Part(false, "बस इतना।"), p[2])
    }

    @Test fun unclosedFence_restIsCode_andTwoBlocks() {
        assertEquals(listOf(FutureUi.Part(false, "a"), FutureUi.Part(true, "x = 1", "py")), FutureUi.split("a\n```py\nx = 1"))
        assertEquals(2, FutureUi.split("```\n1\n```\n```js\n2\n```").count { it.code })
    }

    @Test fun gutterWidth() {
        assertEquals(1, FutureUi.gutter(0)); assertEquals(1, FutureUi.gutter(9)); assertEquals(2, FutureUi.gutter(10)); assertEquals(3, FutureUi.gutter(120))
    }

    @Test fun spans_keywordStringCommentNumber() {
        val s = FutureUi.spans("val name = \"DK\" // नाम")
        assertEquals(FutureUi.Span(0, 3, FutureUi.Kind.KEYWORD), s[0])
        assertEquals(FutureUi.Span(11, 15, FutureUi.Kind.STRING), s[1])
        assertEquals(FutureUi.Kind.COMMENT, s.last().kind)
        assertEquals(listOf(FutureUi.Span(4, 6, FutureUi.Kind.NUMBER)), FutureUi.spans("x = 42"))
        assertTrue(FutureUi.spans("valueX").isEmpty())                  // शब्द के अंदर कीवर्ड नहीं
        assertEquals(FutureUi.Kind.COMMENT, FutureUi.spans("# heading").single().kind)
    }

    @Test fun engineTag_shortensLabel() {
        assertEquals("AI नहीं", FutureUi.engineTag(null))
        assertEquals("claude-opus-5-5", FutureUi.engineTag("Anthropic • claude-opus-5-5"))
        assertEquals("Gemini", FutureUi.engineTag("Gemini"))
        assertTrue(FutureUi.engineTag("x".repeat(60)).length <= 28)
    }
}
