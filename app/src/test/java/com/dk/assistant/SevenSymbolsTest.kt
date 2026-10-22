package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** user का नियम: कोई भी नाम इस्तेमाल करने से पहले फ़ाइल पढ़कर पक्का करो - अंदाज़े से नहीं (रिव्यूअर की पक्की जाँच) */
class SevenSymbolsTest {
    private val svc = """package app
import android.accessibilityservice.AccessibilityService
class DKAccessibilityService : AccessibilityService() {
    val speaker = Speaker(this)
    fun perform(a: String): String = a
}
"""
    private val speaker = """package app
class Speaker(val s: Any) {
    suspend fun speak(t: String) {}
}
"""
    private val live = """package app
class GeminiLive(private val svc: DKAccessibilityService) {
    fun hello() = svc.perform("x")
}
"""
    private fun repo(liveAfter: String) = mapOf("app/DKAccessibilityService.kt" to svc, "app/Speaker.kt" to speaker, "app/GeminiLive.kt" to liveAfter)

    @Test fun pr32_svcSpeak_caughtAsWrongObject() {
        val after = live.replace("    fun hello()", "    fun delegate(svc: DKAccessibilityService) {\n        svc.speak(\"काम सौंपा\")\n    }\n    fun hello()")
        val m = SevenSymbols.check("app/GeminiLive.kt", live, after, repo(after), whole = true)
        val miss = m.single()
        assertTrue(miss.sure)
        assertTrue(miss.hindi(), miss.hindi().contains("Speaker"))
        assertTrue(miss.known.contains("speaker"))       // असली रास्ता दिखे: svc.speaker
    }

    @Test fun realName_passes() {
        val after = live.replace("    fun hello()", "    suspend fun delegate() {\n        svc.speaker.speak(\"काम सौंपा\")\n        val l = listOf(1).map { it + 1 }\n    }\n    fun hello()")
        assertTrue(SevenSymbols.check("app/GeminiLive.kt", live, after, repo(after), whole = true).none { it.sure })
    }

    @Test fun inventedFunction_inWholeRepo_caught_butNotInNormalMode() {
        val after = live.replace("    fun hello()", "    fun x() = makeMagic(3)\n    fun hello()")
        assertTrue(SevenSymbols.check("app/GeminiLive.kt", live, after, repo(after), whole = true).any { it.sure && it.name == "makeMagic()" })
        assertTrue(SevenSymbols.check("app/GeminiLive.kt", live, after, repo(after), whole = false).isEmpty())
    }

    @Test fun inventedClass_caught_importedClass_ok() {
        val bad = live.replace("    fun hello()", "    fun x() = MagicHelper.run()\n    fun hello()")
        assertTrue(SevenSymbols.check("app/GeminiLive.kt", live, bad, repo(bad), whole = true).any { it.sure && it.name == "MagicHelper" })
        val ok = ("import org.json.JSONObject\n" + live).replace("    fun hello()", "    fun x() = JSONObject().put(\"a\", 1)\n    fun hello()")
        assertTrue(SevenSymbols.check("app/GeminiLive.kt", "import org.json.JSONObject\n" + live, ok, repo(ok), whole = true).none { it.sure })
    }

    @Test fun sdkMethodOfExternalParent_notHardRejected_butAskedToReviewer() {
        val after = live.replace("    fun hello()", "    fun back() = svc.performGlobalAction(1)\n    fun hello()")
        val m = SevenSymbols.check("app/GeminiLive.kt", live, after, repo(after), whole = true)
        assertEquals(1, m.size)
        assertFalse(m[0].sure)                          // AccessibilityService में सच में है - AI रिव्यूअर पक्का करे
        assertTrue(m[0].english().contains("performGlobalAction"))
    }

    @Test fun oldLinesNotChecked_onlyNewOnes() {
        val old = live.replace("svc.perform(\"x\")", "svc.speak(\"x\")")    // पुराना code (पहले से था)
        val after = old.replace("    fun hello()", "    fun two() = 2\n    fun hello()")
        assertTrue(SevenSymbols.check("app/GeminiLive.kt", old, after, repo(after), whole = true).isEmpty())
        assertTrue(SevenSymbols.check("README.md", null, "svc.speak()", emptyMap(), whole = true).isEmpty())
    }

    @Test fun plannerUses_verifiedAgainstFile_andSignaturesGiven() {
        val files = mapOf("app/Speaker.kt" to speaker)
        assertTrue(SevenSymbols.badUses(listOf("app/Speaker.kt: speak"), files::get).isEmpty())
        val bad = SevenSymbols.badUses(listOf("app/Speaker.kt: talk", "app/Nope.kt: x"), files::get)
        assertEquals(2, bad.size)
        assertTrue(SevenSymbols.signatures(listOf("app/Speaker.kt: speak"), files::get).contains("suspend fun speak(t: String)"))
    }
}
