package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (मालिक, लॉग: "Gemini key गलत है" - key #2 पर हर बार पहले कोशिश): गलत key अपने-आप छोड़ो */
class LiveBadKeysTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun badKeyRemembered24h() {
        val h = 60 * 60 * 1000L
        assertFalse(LiveBadKeys.isBad(0L, 1_000_000))
        assertTrue(LiveBadKeys.isBad(1_000L, 1_000L + 23 * h))
        assertFalse(LiveBadKeys.isBad(1_000L, 1_000L + 25 * h))     // 24 घंटे बाद फिर आज़माओ
    }

    @Test fun onlyRealKeyErrors() {
        assertTrue(LiveBadKeys.badKeyError(403, "PERMISSION_DENIED"))
        assertTrue(LiveBadKeys.badKeyError(1007, "API key not valid"))
        assertFalse(LiveBadKeys.badKeyError(429, "quota exceeded"))
        assertFalse(LiveBadKeys.badKeyError(1011, "resource exhausted"))
        assertFalse(LiveBadKeys.badKeyError(1007, "Invalid session handle"))
    }

    @Test fun roundRobinSkipsBadKey() {
        val bad = { i: Int -> i == 1 }                      // key #2 गलत
        assertEquals(2, LiveBadKeys.firstGood(1, 3, bad))   // बारी key #2 की = सीधे #3
        assertEquals(0, LiveBadKeys.firstGood(0, 3, bad))
        assertEquals(1, LiveBadKeys.firstGood(1, 3) { true })   // सब गलत = पहले जैसा
        // अगली key: #1 के बाद #2 गलत = #3
        assertEquals(2, LiveBadKeys.after(LiveKeys.next(0, 3, 0), 3, 0, bad))
        // आगे सिर्फ़ गलत key = आख़िरी कोशिश उसी पर (पहले जैसा)
        assertEquals(1, LiveBadKeys.after(LiveKeys.next(0, 2, 0), 2, 0, bad))
        assertEquals(-1, LiveBadKeys.after(LiveKeys.next(2, 3, 0), 3, 0, bad))
    }

    @Test fun raceQueueBadKeyLast() {
        val q = listOf("a" to 1, "b" to 0, "c" to 1, "d" to 2)
        assertEquals(listOf("b" to 0, "d" to 2, "a" to 1, "c" to 1), LiveBadKeys.last(q) { it.second == 1 })
    }

    @Test fun wired() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("val start = LiveBadKeys.firstGood(LiveKeys.startKey("))
        assertTrue(g.contains("if (e.message == \"Gemini key गलत है\") LiveBadKeys.mark(svc, key)"))
        assertTrue(src("GeminiLiveRace.kt").contains("queue = LiveBadKeys.last(queue)"))
        assertTrue(src("GeminiLiveRace.kt").contains("if (LiveBadKeys.badKeyError(code, reason)) LiveBadKeys.mark(svc, a.key)"))
        assertTrue(src("GeminiLiveSetup.kt").contains("LiveBadKeys.clear(svc, key)"))
        // key खुद फ़ोन की याद में नहीं - सिर्फ़ hash
        assertTrue(src("LiveBadKeys.kt").contains("\"h_\" + key.trim().hashCode()"))
    }
}
