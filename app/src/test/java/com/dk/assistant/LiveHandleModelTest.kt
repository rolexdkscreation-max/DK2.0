package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 नवंबर 2026 (लॉग: "Invalid session handle", code 1007): मॉडल बदला = पुराना handle नहीं, नया सत्र */
class LiveHandleModelTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun handleOnlyForItsOwnModel() {
        assertTrue(LiveHandleStore.sameModel("gemini-3.1-flash-live-preview", "gemini-3.1-flash-live-preview"))
        assertTrue(LiveHandleStore.sameModel("gemini-3.1-flash-live-preview", "models/gemini-3.1-flash-live-preview"))
        assertFalse(LiveHandleStore.sameModel("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-09-2025"))
        assertFalse(LiveHandleStore.sameModel("", "gemini-3.8-live"))     // पता नहीं किसका = मत भेजो
    }

    @Test fun resumeComboIsHandlesOwn() {
        assertEquals("a" to 1, LiveHandleStore.comboFor("a", 1, "b", 0))
        assertEquals("b" to 0, LiveHandleStore.comboFor("", -1, "b", 0))
    }

    @Test fun wired() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("handleModel = model.removePrefix(\"models/\"); handleKey = keyIdx"))
        assertTrue(g.contains("LiveHandleStore.comboFor(handleModel, handleKey, model, ki)"))
        val r = src("GeminiLiveRace.kt")
        assertTrue(r.contains("resume = c.id == resumeId && sameM"))
        assertTrue(r.contains("setupJson(m, resume = sameM)"))
        assertTrue(r.contains("LiveHandleStore.comboFor(handleModel, handleKey, model, keyIdx)"))
        // पुराना नियम वही: handle सिर्फ़ resume वाली जोड़ी पर
        assertTrue(src("GeminiLiveSetup.kt").contains(".put(\"sessionResumption\", if (resume && handle != null) JSONObject().put(\"handle\", handle) else JSONObject())"))
    }
}
