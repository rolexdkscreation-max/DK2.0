package com.dk.assistant

import com.dk.assistant.connect.GoogleAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 18 नवंबर 2026 (मालिक: "Google login failed: 8: [8] Unknown error [status=UNKNOWN]") - साफ़ वजह और SHA-1 वहीं */
class GoogleLoginCode8Test {
    @Test fun code8_explainedClearly() {
        val e = Exception("8: [8] Unknown error [status=UNKNOWN]")
        assertEquals(GoogleAuth.INTERNAL_HI, GoogleAuth.explain(e))
        assertTrue(GoogleAuth.INTERNAL_HI.contains("com.dk.assistant") && GoogleAuth.INTERNAL_HI.contains("SHA-1"))
        assertTrue(GoogleAuth.isInternal("INTERNAL_ERROR"))
        assertFalse(GoogleAuth.isInternal("18: something")); assertFalse(GoogleAuth.isInternal("7: network"))
        // पुराने रास्ते वैसे ही
        assertTrue(GoogleAuth.explain(Exception("10: DEVELOPER_ERROR")).contains("DEVELOPER_ERROR"))
        assertEquals("इंटरनेट नहीं मिला", GoogleAuth.explain(Exception("7: NETWORK_ERROR")))
    }

    @Test fun loginCardShowsPackageAndSha1() {
        val f = listOf(File("src/main/java/com/dk/assistant/SettingsLicense.kt"), File("app/src/main/java/com/dk/assistant/SettingsLicense.kt")).first { it.exists() }.readText()
        assertTrue(f.contains("GoogleAuth.signingSha1(this)"))
        assertTrue(f.contains("button(\"SHA-1 कॉपी करें\")"))
    }

    @Test fun englishUiHasNewTexts() {
        val t = listOf(File("src/main/assets/ui_en.tsv"), File("app/src/main/assets/ui_en.tsv")).first { it.exists() }.readText()
        assertTrue(t.contains(GoogleAuth.INTERNAL_HI + "\t"))
    }
}
