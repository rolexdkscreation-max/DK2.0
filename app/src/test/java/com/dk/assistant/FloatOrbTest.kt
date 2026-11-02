package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (01 नवंबर 2026): "सिर्फ़ एक ORB, नीचे बीच में, पुराने छोटे जितना; slider/pinch से छोटा-बड़ा (सेव);
 * ऐप खुला/minimize/दूसरा ऐप - हर हाल में वही; ✕ = सिर्फ़ ORB छिपे, DK चलता रहे; खींचना चले; बाकी स्क्रीन छूना चले."
 */
class FloatOrbTest {
    @Test fun notInsideApp_onlyOutside_noPopupOverlap() {
        assertFalse(OrbRules.floatWanted(prefOn = true, inApp = true, popup = false))     // ऐप खुला = छोटा ORB नहीं
        assertTrue(OrbRules.floatWanted(prefOn = true, inApp = false, popup = false))     // बाहर गया = आए
        assertFalse(OrbRules.floatWanted(prefOn = true, inApp = false, popup = true))     // popup के रहते नहीं
        assertFalse(OrbRules.floatWanted(prefOn = false, inApp = false, popup = false))   // Settings में बंद
        val f = src("FloatOrb.kt")
        assertTrue(f.contains("OrbRules.floatWanted(Prefs.floatOrbOn(c), AppVisible.visible, OrbRules.popupShowing)"))
        assertTrue(f.contains("OvershootInterpolator"))                                  // सिकुड़कर आना
    }

    @Test fun popupStaysWhileTalking_leavesAfterLive() {
        assertFalse(OrbRules.popupShouldLeave(500, 5_000, Mode.LISTENING, 0f))            // बहुत जल्दी नहीं
        assertFalse(OrbRules.popupShouldLeave(5_000, 5_000, Mode.SPEAKING, 0f))           // बोलते समय नहीं
        assertFalse(OrbRules.popupShouldLeave(5_000, 5_000, Mode.LISTENING, 0.5f))        // आप बोल रहे = नहीं
        assertFalse(OrbRules.popupShouldLeave(5_000, 1_000, Mode.LISTENING, 0f))          // Live अभी-अभी जुड़ा
        assertTrue(OrbRules.popupShouldLeave(5_000, 3_000, Mode.LISTENING, 0f))           // जुड़ा + थोड़ी देर = जाए
        assertTrue(OrbRules.popupShouldLeave(OrbRules.POPUP_MAX_MS, 0, Mode.IDLE, 0f))   // Live न जुड़े तब भी आख़िर में
        assertTrue(OrbRules.liveUp("Live • gemini-3.1-flash-live-preview • key #1 • 820 ms"))
        assertFalse(OrbRules.liveUp("जुड़ रहा है… x"))
        val v = src("DKVoiceInteractionSessionService.kt")
        assertTrue(v.contains("private fun enterAnim()") && v.contains("private fun leave()") && v.contains("DKState.unlisten(onState)"))
    }

    @Test fun notWorkingServiceHealedOnlyIfAlreadyEnabled() {
        val s = src("Setup.kt")
        val h = s.substring(s.indexOf("fun healAccessibility"), s.indexOf("fun selfEnableAccessibility"))
        assertTrue(h.contains("enabledInSettings(c)") && h.contains("canWriteSecure(c)") && h.contains("instance == null"))
        assertTrue(src("DKVoiceInteractionService.kt").contains("Setup.healAccessibility(this)"))
    }

    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }
        return f.readText()
    }

    @Test fun defaultSizeIsOldSmallOrb_andClamped() {
        assertEquals(76, FloatOrbSize.DEFAULT_DP)
        assertEquals(FloatOrbSize.MIN_DP, FloatOrbSize.clamp(1))
        assertEquals(FloatOrbSize.MAX_DP, FloatOrbSize.clamp(9999))
        assertEquals(100, FloatOrbSize.clamp(100))
        assertTrue(FloatOrbSize.MIN_DP < FloatOrbSize.DEFAULT_DP && FloatOrbSize.DEFAULT_DP < FloatOrbSize.MAX_DP)
    }

    @Test fun onlyOneOrb_sideBubbleGone() {
        val s = src("DKAccessibilityService.kt")
        assertFalse(s.contains("fun showBubble(") || s.contains("private var bubble"))
        assertTrue(s.contains("FloatOrb(this)"))
        // ऐप खुला होने पर ORB छिपाना हटाया; ऐप खुले = ✕ से छिपा ORB वापस
        assertFalse(s.contains("bubble?.visibility"))
        assertTrue(s.contains("if (inApp && !Prefs.floatOrbOn(this)) Prefs.setFloatOrbOn(this, true)"))
        // टैप पहले जैसा
        val tap = s.substring(s.indexOf("private fun onOrbTap()"), s.indexOf("private fun onOrbTap()") + 400)
        assertTrue(tap.contains("end_call") && tap.contains("toggleLive()"))
        // 02 नवंबर 2026 (मालिक की नई माँग): power-button popup के रहते छोटा ORB नहीं (OrbRules.popupShowing), popup पर ✕ नहीं
        val v = src("DKVoiceInteractionSessionService.kt")
        assertTrue(v.contains("OrbRules.popupShowing = true") && v.contains("it.refreshOrb()"))
        assertFalse(v.contains("closeButton") || v.contains("ic_menu_close_clear_cancel"))
    }

    @Test fun bottomCenter_passthrough_dragPinch() {
        val f = src("FloatOrb.kt")
        assertTrue(f.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue(f.contains("FLAG_NOT_FOCUSABLE") && f.contains("FLAG_NOT_TOUCH_MODAL"))
        assertTrue(f.contains("x = (dm.widthPixels - w) / 2"))
        assertTrue(f.contains("ScaleGestureDetector") && f.contains("Prefs.setFloatOrbDp"))
        assertTrue(f.contains("MotionEvent.ACTION_MOVE"))
        assertFalse(f.contains("MATCH_PARENT"))   // खिड़की पूरी स्क्रीन की नहीं = बाकी स्क्रीन छूना चले
    }

    // 02 नवंबर 2026 (मालिक): "ORB पर ✕ नहीं होना चाहिए" - पुराना ✕ वाला test इसी माँग से बदला. ORB छिपाना अब सिर्फ़ Settings से
    @Test fun noCloseOnOrb_hideOnlyFromSettings() {
        val f = src("FloatOrb.kt")
        assertFalse(f.contains("\"✕\"") || f.contains("hideByUser"))
        assertFalse(f.contains("stopLive") || f.contains("disableSelf") || f.contains("stopService"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(m.contains(".OrbShowReceiver"))
        assertTrue(src("SettingsActivity.kt").contains("card(\"तैरता ORB\""))
    }
}
