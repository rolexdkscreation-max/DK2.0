package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveKeysTest {
    @Test fun order_mainFirst_noBlank_noDuplicate() {
        assertEquals(listOf("A", "B"), LiveKeys.order(listOf(" A ", "", "B")))
        assertEquals(listOf("A", "C"), LiveKeys.order(listOf("A", "A", "C")))
        assertEquals(listOf("B"), LiveKeys.order(listOf("", "B", " ")))
        assertEquals(emptyList<String>(), LiveKeys.order(listOf("", "", "")))
    }

    @Test fun next_switchesUntilKeysEnd() {
        assertEquals(1, LiveKeys.next(0, 3))
        assertEquals(2, LiveKeys.next(1, 3))
        assertEquals(-1, LiveKeys.next(2, 3))
        assertEquals(-1, LiveKeys.next(0, 1))     // सिर्फ़ एक key = पहले जैसा (Fatal)
    }

    @Test fun cooldown_firstKeyNameUnchanged_backupsSeparate() {
        assertEquals("live:gemini-3.8-live", LiveKeys.cooldownName(0, "gemini-3.8-live"))
        assertEquals("live:2:gemini-3.8-live", LiveKeys.cooldownName(1, "gemini-3.8-live"))
        assertNotEquals(LiveKeys.cooldownName(1, "m"), LiveKeys.cooldownName(2, "m"))
    }

    @Test fun backupPrefNames_matchCoderPattern() {
        assertEquals(ApiScopes.pref(ApiScopes.LIVE, "key", "gemini") + "2", ApiScopes.pref(ApiScopes.LIVE, "key", "gemini2"))
    }
}
