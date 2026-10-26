package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v3.74: स्क्रीन सिर्फ़ user की इजाज़त से */
class ScreenGuardTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun aiCannotTurnOn() {
        assertFalse(ScreenGuard.allowSet(on = true, byUser = false))
        assertTrue(ScreenGuard.allowSet(on = true, byUser = true))
        assertTrue(ScreenGuard.allowSet(on = false, byUser = false))
    }

    @Test fun viewNeedsBothPrefAndUserOk() {
        assertFalse(ScreenGuard.canView(prefOn = true, userOk = false))   // पुराना अपने-आप वाला true
        assertFalse(ScreenGuard.canView(prefOn = false, userOk = true))
        assertTrue(ScreenGuard.canView(prefOn = true, userOk = true))
    }

    @Test fun neverAutoTapsStart() {
        assertFalse(ScreenGuard.AUTO_TAP_SHARE_START)
        assertFalse(src("LiveVision.kt").contains("autoTapShareStart()"))
    }

    @Test fun userVoiceCommandCarriesUserFlag() {
        val on = QuickCommands.parse("स्क्रीन देखना चालू करो", emptyList())!!
        assertEquals("screen_view", on.action.optString("type"))
        assertTrue(on.action.optBoolean("on"))
        assertTrue(on.action.optBoolean(ScreenGuard.BY_USER))
    }

    @Test fun screenshotDefaultOff() {
        assertTrue(src("Prefs.kt").contains("getBoolean(ScreenGuard.PREF_KEY, false)"))
    }
}
