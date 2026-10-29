package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** कोडिंग एजेंट: 5 Gemini keys - key 4 और 5 भी key 2/3 जैसी (बैकअप, खाली/दोहराई नहीं, एक-key मोड में बंद) */
class CoderFiveKeysTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }.readText()

    @Test fun prefNames() {
        assertEquals("coder_key_gemini4", ApiScopes.pref(ApiScopes.CODER, "key", "gemini4"))
        assertEquals("coder_key_gemini5", ApiScopes.pref(ApiScopes.CODER, "key", "gemini5"))
    }

    @Test fun oneKeyMode_backupsOff() {
        for (id in listOf("gemini4", "gemini5")) assertEquals("", ApiScopes.oneKeyFor(id, "AIzaONE"))
    }

    @Test fun oldUser_withKey4or5_keepsOwnKeys() {
        assertFalse(ApiScopes.oneKeyDefault { k -> if (k == "coder_key_gemini4") "AIza4" else null })
        assertFalse(ApiScopes.oneKeyDefault { k -> if (k == "coder_key_gemini5") "AIza5" else null })
        assertTrue(ApiScopes.oneKeyDefault { null })
    }

    @Test fun fiveKeys_inOrder_noBlankNoDuplicate() {
        assertEquals(listOf("k1", "k2", "k3", "k4", "k5"), GeminiModels.distinctKeys(listOf("k1", "k2", "k3", "k4", "k5")))
        assertEquals(listOf("k1", "k4", "k5"), GeminiModels.distinctKeys(listOf("k1", "", "k1", "k4", " k5 ")))
    }

    @Test fun wired_inPrefsAndSettings() {
        val p = src("Prefs.kt")
        assertTrue(p.contains("scopeKey(c, ApiScopes.CODER, \"gemini4\")") && p.contains("scopeKey(c, ApiScopes.CODER, \"gemini5\")"))
        val s = src("SettingsActivity.kt")
        assertTrue(s.contains("ApiScopes.pref(scope, \"key\", \"gemini4\")") && s.contains("ApiScopes.pref(scope, \"key\", \"gemini5\")"))
    }
}
