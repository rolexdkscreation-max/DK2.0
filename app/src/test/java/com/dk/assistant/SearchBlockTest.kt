package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक का लॉग): मुफ़्त Gemini key + googleSearch = हर चैट पर 429 ("You exceeded your current quota…"),
 * जबकि उसी key से Live चल रहा था. अब खोज वाला 429 = उसी मॉडल से बिना खोज, और key कुछ घंटे बिना खोज.
 */
class SearchBlockTest {
    @After fun reset() = SearchBlock.clear()

    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n"))
        .first { it.exists() }.readText()

    @Test fun logMessageIsRetriedWithoutSearch() {
        val msg = "You exceeded your current quota, please check your plan and billing details. For more information on this error, " +
            "head to: https://aistudio.google.com/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. "
        assertFalse(GroundCite.toolRejected(429, msg))      // पुराना बचाव इसे नहीं पकड़ता था (जड़)
        assertTrue(SearchBlock.retryWithout(429))            // अब पकड़ता है
        assertFalse(SearchBlock.retryWithout(401))
        assertFalse(SearchBlock.retryWithout(500))
    }

    @Test fun blockLastsHoursPerKey() {
        val now = 1_000_000L
        assertFalse(SearchBlock.blocked("AIzaA", now))
        SearchBlock.mark("AIzaA", now)
        assertTrue(SearchBlock.blocked("AIzaA", now + 60_000))
        assertTrue(SearchBlock.blocked(" AIzaA ", now + 60_000))
        assertFalse(SearchBlock.blocked("AIzaB", now + 60_000))          // दूसरी key पर असर नहीं
        assertFalse(SearchBlock.blocked("AIzaA", now + SearchBlock.BLOCK_MS + 1))   // कुछ घंटे बाद फिर खोज आज़माएगा
        SearchBlock.mark("", now)
        assertFalse(SearchBlock.blocked("", now))
    }

    @Test fun wiredInChatAndSearch() {
        val a = src("AiClients.kt")
        assertTrue(a.contains("if (!groundChat || SearchBlock.blocked(key))"))
        assertTrue(a.contains("val quota = SearchBlock.retryWithout(e.code)"))
        assertTrue(a.contains("if (quota) { SearchBlock.mark(key)"))
        assertTrue(a.contains("if (!useTool && !think) throw AiException(429, SearchBlock.MSG)"))
        assertTrue(a.contains("if (useTool) body.put(\"tools\""))
        assertTrue(a.contains("if (attempt == 1) SearchBlock.mark(key)"))
        // key का text कहीं सहेजा नहीं जाता - सिर्फ़ hash
        assertTrue(src("SearchBlock.kt").contains("key.trim().hashCode()"))
    }
}
