package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.91: "एक key से सब" - नया install = चालू, पुराना user = बंद; एक-key में सिर्फ़ Gemini */
class OneKeyTest {
    @Test fun newInstallOnOldUserOff() {
        assertTrue(ApiScopes.oneKeyDefault { null })
        assertTrue(ApiScopes.oneKeyDefault { k -> if (k == "key_gemini") "  " else null })   // खाली = कोई key नहीं
        assertFalse(ApiScopes.oneKeyDefault { k -> if (k == "key_gemini") "AIzaOLD" else null })
        assertFalse(ApiScopes.oneKeyDefault { k -> if (k == "live_key_groq") "gsk_x" else null })
        assertFalse(ApiScopes.oneKeyDefault { k -> if (k == "coder_key_openrouter") "sk-or-x" else null })
        assertFalse(ApiScopes.oneKeyDefault { k -> if (k == "voice_key") "AIzaV" else null })
    }

    @Test fun onlyGeminiInOneKeyMode() {
        assertEquals("AIzaONE", ApiScopes.oneKeyFor("gemini", " AIzaONE "))
        for (id in listOf("groq", "openrouter", "custom", "gemini2", "gemini3")) assertEquals("", ApiScopes.oneKeyFor(id, "AIzaONE"))
        // एक key = खोज की keys में भी एक ही बार
        assertEquals(listOf("AIzaONE"), SearchGuard.keys(true, ApiScopes.oneKeyFor("gemini", "AIzaONE"), ApiScopes.oneKeyFor("gemini", "AIzaONE"), ""))
    }

    @Test fun oldPrefsCoverEveryScope() {
        for (id in ApiScopes.PROVIDERS) {
            assertTrue(ApiScopes.OLD_KEY_PREFS.contains("key_$id"))
            for (sc in listOf(ApiScopes.LIVE, ApiScopes.CODER, ApiScopes.PC)) assertTrue(ApiScopes.OLD_KEY_PREFS.contains(ApiScopes.pref(sc, "key", id)))
        }
        assertFalse(ApiScopes.OLD_KEY_PREFS.contains(ApiScopes.ONE_KEY))
    }
}
