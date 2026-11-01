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
        // power-button वाला session अपना अलग ✕ ORB नहीं दिखाता जब DK सेवा चालू हो
        val v = src("DKVoiceInteractionSessionService.kt")
        assertTrue(v.contains("svc.refreshOrb()") && v.contains("orbView?.visibility = android.view.View.GONE"))
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

    @Test fun closeOnlyHidesOrb_dkKeepsRunning() {
        val f = src("FloatOrb.kt")
        val h = f.substring(f.indexOf("private fun hideByUser()"), f.indexOf("fun remove()"))
        assertTrue(h.contains("Prefs.setFloatOrbOn(c, false)") && h.contains("OrbNotify.showHidden(c)"))
        assertFalse(h.contains("stopLive") || h.contains("toggleLive") || h.contains("disableSelf") || h.contains("stopService"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(m.contains(".OrbShowReceiver"))
        assertTrue(src("SettingsActivity.kt").contains("card(\"तैरता ORB\""))
    }
}
