package com.dk.assistant

import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 25 नवंबर 2026: शुरुआती (Midnight) tokens सफ़ेद/काले रंग 0 होने से पहले न बनें - लिखावट साफ़ दिखे */
class ClayThemeInitOrderTest {
    @Test fun startupTokensMatchFreshTokens() {
        val start = ClayTheme.t
        val fresh = ClayTheme.tokens(ClayTheme.current)
        assertEquals(fresh.text, start.text)
        assertEquals(fresh.sub, start.sub)
        assertTrue("Midnight लिखावट पढ़ने लायक", NeonTheme.contrast(start.text, ClayTheme.current.surface) >= 4.5)
        assertEquals(0xFF, (start.text ushr 24) and 0xFF)
    }
}
