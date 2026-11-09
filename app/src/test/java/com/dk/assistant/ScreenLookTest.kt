package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026 (मालिक): DK लाइव स्क्रीन देखकर प्रोडक्ट, कलर, प्राइस, कंपनी बताए - सिर्फ़ पूछने पर */
class ScreenLookTest {
    @Test fun onlyWhenAsked() {
        assertTrue(ScreenLook.asked("इसका प्राइस कितना है"))
        assertTrue(ScreenLook.asked("ये कौन सी कंपनी का है"))
        assertTrue(ScreenLook.asked("इसका कलर क्या है"))
        assertTrue(ScreenLook.asked("स्क्रीन पर क्या दिख रहा है"))
        assertTrue(ScreenLook.asked("what's this product"))
        assertFalse(ScreenLook.asked("आज मौसम कैसा है"))
        assertFalse(ScreenLook.asked("मुझे एक जोक सुनाओ"))
        assertFalse(ScreenLook.asked(""))
    }

    @Test fun promptAsksForFactsNotGuesses() {
        val p = ScreenLook.prompt("इसका प्राइस कितना है", "com.flipkart.android", "Puma Running Shoes ₹1,299 ₹3,999 67% off 4.2 ★", true)
        assertTrue(p.contains("com.flipkart.android")); assertTrue(p.contains("₹1,299"))
        assertTrue(p.contains("brand/company")); assertTrue(p.contains("colour")); assertTrue(p.contains("price"))
        assertTrue(p.contains("NEVER guess")); assertTrue(p.contains("Hindi"))
        assertTrue(ScreenLook.prompt("x", "", "", false).contains("no screenshot"))
    }
}
