package com.dk.assistant.voice.st3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class St3TextTest {
    @Test fun preprocess_wrapsHindi_andEndsSentence() {
        assertEquals("<hi>नमस्ते.</hi>", St3Text.preprocess("नमस्ते"))
        assertTrue(St3Text.preprocess("ठीक है?").endsWith("?</hi>"))          // पहले से विराम हो तो "." नहीं (आधिकारिक कोड जैसा)
        assertTrue(St3Text.preprocess("  बहुत   अच्छा  ").contains("बहुत अच्छा"))
        assertTrue(St3Text.preprocess("अरे <laugh> वाह").contains("<laugh>"))
    }

    @Test fun ids_skipUnknown() {
        val idx = LongArray(200) { -1L }.also { it['a'.code] = 5; it['b'.code] = 7 }
        assertEquals(listOf(5L, 7L), St3Text.ids("a?b", idx).toList())
        assertEquals(listOf(5L), St3Text.ids("a\u0915", idx).toList())     // सूची से बाहर का अक्षर छोड़ा
    }

    @Test fun chunk_respectsMax_andKeepsTagsWhole() {
        val s = (1..40).joinToString(" ") { "यह वाक्य नंबर $it है <laugh> और आगे," } + " अंत।"
        val c = St3Text.chunk(s, 120)
        assertTrue(c.size > 1)
        for (x in c) { assertTrue(x, x.length <= 120); assertTrue(x, St3Text.tagsWhole(x)) }
        assertEquals(listOf("छोटा वाक्य।"), St3Text.chunk("  छोटा वाक्य।  "))
        assertTrue(St3Text.chunk("   ").isEmpty())
        val two = St3Text.chunk("पहला वाक्य है। दूसरा वाक्य है। तीसरा वाक्य है।", 20)
        assertTrue(two.all { it.endsWith("।") })
    }
}
