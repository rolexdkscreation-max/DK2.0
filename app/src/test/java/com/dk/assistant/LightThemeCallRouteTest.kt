package com.dk.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026: लाइट थीम में सिर्फ़ पृष्ठभूमि उजली (अक्षर/आइकन दिखें) + कॉल: Bluetooth हो तो उसी पर, नहीं तो speaker */
class LightThemeCallRouteTest {
    private fun src(p: String): String =
        listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun lightTheme_onlyBackgroundChanges() {
        // सफ़ेद लिखावट -> गहरी, रंगीन वैसी ही
        assertEquals(0xFF0F172A.toInt(), PlainLook.lightText(0xFFFFFFFF.toInt()))
        assertEquals(0xFF3B82F6.toInt(), PlainLook.lightText(0xFF3B82F6.toInt()))
        assertTrue(PlainLook.isLightColor(PlainLook.L_BG)); assertTrue(PlainLook.isLightColor(PlainLook.L_CARD))
        assertFalse(PlainLook.isLightColor(PlainLook.BLUE)); assertFalse(PlainLook.isLightColor(PlainLook.CARD))
        assertTrue(PlainLook.opaqueEnough(0xFFFFFFFF.toInt())); assertFalse(PlainLook.opaqueEnough(0x00000000))
        // ऐप के गहरे डिब्बे उजले बनते, बाकी रंग नहीं
        assertTrue(PlainLook.appDark(PlainLook.CARD)); assertTrue(PlainLook.appDark(0xFF0A1033.toInt()))
        assertFalse(PlainLook.appDark(0xFF000000.toInt())); assertFalse(PlainLook.appDark(PlainLook.BLUE))
        // सिर्फ़ सफ़ेद चिह्न गहरे, रंगीन चिह्न नहीं
        assertTrue(PlainLook.whiteShare(90, 100)); assertFalse(PlainLook.whiteShare(30, 100)); assertFalse(PlainLook.whiteShare(0, 0))
        val p = src("PlainLook.kt")
        assertTrue(p.contains("if (v is android.widget.TextView && under == true)"))
        assertTrue(p.contains("is android.graphics.drawable.BitmapDrawable) return"))   // फ़ोटो कभी नहीं
    }

    @Test fun call_bluetoothElseSpeaker() {
        assertTrue(CallRoute.hasBt(listOf(2, 7))); assertTrue(CallRoute.hasBt(listOf(26)))
        assertFalse(CallRoute.hasBt(listOf(1, 2, 3)))
        assertTrue(CallRoute.wantSpeaker(false)); assertFalse(CallRoute.wantSpeaker(true))
        val c = src("DKAccessibilityCalls.kt")
        assertTrue(c.contains("val speakerOn = CallRoute.wantSpeaker(bt)"))
        assertTrue(src("PhoneActions.kt").contains("if (CallRoute.wantSpeaker(CallRoute.btConnected(svc)))"))
    }
}
