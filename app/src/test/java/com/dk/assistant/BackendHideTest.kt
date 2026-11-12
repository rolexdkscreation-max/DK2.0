package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 (मालिक: "जो बैक एंड में रहना चाहिए वो फ्रंटेंड में मत दिखाओ, बैक एंड में रख दो"):
 * तकनीकी चीज़ें स्क्रीन पर नहीं, पर काम वैसे ही चलता रहे.
 */
class BackendHideTest {
    private fun src(n: String): String {
        val root = listOf(java.io.File("src/main/java/com/dk/assistant"), java.io.File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        return java.io.File(root, n).readText()
    }

    @Test fun backendBlockIsHiddenButStillBuilt() {
        val w = src("SettingsWidgets.kt")
        val fn = w.substringAfter("internal fun SettingsActivity.backend(").substringBefore("\n}")
        assertTrue(fn.contains("visibility = View.GONE"))
        assertTrue(fn.contains("body(b)"))
    }

    @Test fun liveEngineTogglesLiveInBackend() {
        val v = src("SettingsVoice.kt")
        val back = v.substringAfter("backend(box) { b ->").substringBefore("\n        }\n")
        for (k in listOf("LiveStay.PREF_TEXT_RESCUE", "LiveSmooth.PREF_CALL_SPEAKER", "LiveSmooth.PREF_SLIM_TOOLS",
                "LiveSmooth.PREF_SMART_VAD", "LiveSmooth.PREF_HEARD_FALLBACK"))
            assertTrue(k, back.contains(k))
        // इस्तेमाल वाले विकल्प सामने ही रहें
        val front = v.substringBefore("backend(box) { b ->")
        for (k in listOf("Prefs.duckMedia(this)", "Prefs.ownerFarOn(this)", "Prefs.bargeHold(this)")) assertTrue(k, front.contains(k))
    }

    @Test fun noRawErrorTextOnScreen() {
        val files = mapOf(
            "TeacherActivity.kt" to listOf("त्रुटि: \${e.message}"),
            "CodingActivity.kt" to listOf("गैलरी नहीं खुली: \${e.message}"),
            "ComputerActivity.kt" to listOf("नहीं खुली: \${e.message"),
            "studio/StudioActivity.kt" to listOf("ZIP नहीं बना: \${e.message"),
            "StoryWriterActivity.kt" to listOf("सेव नहीं हुई: \${e.message}"),
            "SettingsActivity.kt" to listOf("(\${e.javaClass.simpleName})"),
            "SettingsVoiceCmds.kt" to listOf("(\${e.javaClass.simpleName})"))
        for ((f, bad) in files) for (b in bad) assertFalse("$f: $b", src(f).contains(b))
    }

    @Test fun noTechStatsOnScreen() {
        assertFalse(src("forge/ForgeActivity.kt").contains("tok/s"))
        assertTrue(src("voice/engine/VoiceEngineCard.kt").contains("VoiceEngines.latencyLine(a, e.id), 0xFF8FD3FF.toInt()).apply { visibility = View.GONE }"))
        assertFalse(src("SettingsActivity.kt").contains("\${KeySetup.name(id)} • \${Prefs.model(this, id)}"))
    }
}
