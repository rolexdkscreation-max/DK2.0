package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (मालिक): स्वभाव तुरंत बदले, हर स्वभाव का अपना संबोधन, बोलकर कॉल उठाना, DK कॉलिंग की सेटिंग */
class PersonaCallTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private fun res(n: String) = listOf(File("src/main/$n"), File("app/src/main/$n")).first { it.exists() }.readText()

    @Test fun address_defaults() {
        assertEquals("बाबू, जान", DkPersona.defaultAddress(DkPersona.P.GIRLFRIEND))
        assertEquals("सर, बॉस", DkPersona.defaultAddress(DkPersona.P.PRO))
        for (p in listOf(DkPersona.P.FRIENDLY, DkPersona.P.NATKHAT, DkPersona.P.CARING)) assertEquals("बॉस, सर", DkPersona.defaultAddress(p))
        assertEquals("बाबू, जान", DkPersona.cleanAddress(" बाबू ,जान,, बाबू/ "))
        assertEquals("बाबू", DkPersona.firstWord("बाबू, जान"))
        assertEquals("", DkPersona.firstWord(""))
    }

    @Test fun finalBlock_hasPersonaAddress() {
        val g = DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "राज")
        assertTrue(g.contains("ADDRESS IN THIS PERSONALITY") && g.contains("\"बाबू\"") && g.contains("\"जान\""))
        assertTrue(g.contains("Never call him बॉस or सर"))
        val f = DkPersona.finalFor(DkPersona.P.FRIENDLY, "राज")
        assertTrue(f.contains("\"बॉस\"") && f.contains("\"सर\"") && f.contains("No बाबू"))
        assertTrue(f.contains("राज"))
        // खाली छोड़ा = पुराना "जी <नाम>" नियम (कोई ADDRESS लाइन नहीं)
        assertFalse(DkPersona.finalFor(DkPersona.P.FRIENDLY, "राज", "").contains("ADDRESS IN THIS PERSONALITY"))
        // मालिक का अपना संबोधन
        assertTrue(DkPersona.finalFor(DkPersona.P.GIRLFRIEND, "", "शोना").contains("\"शोना\""))
        assertTrue(DkPersona.quick(DkPersona.P.GIRLFRIEND).ack.contains("बाबू"))
    }

    @Test fun swap_usesPersonaWord() {
        assertEquals("हाँ बाबू", NameRule.swap("हाँ बॉस", "धीरज", "बाबू"))
        assertEquals("ठीक है सर", NameRule.swap("ठीक है बॉस", "धीरज", "सर"))
        assertEquals("हाँ बॉस", NameRule.swap("हाँ बॉस", "धीरज", "बॉस"))
        assertEquals("बॉस ने कहा था", NameRule.swap("बॉस ने कहा था", "धीरज", "बाबू"))
        // स्वभाव का शब्द नहीं = पहले जैसा
        assertEquals("हाँ जी धीरज", NameRule.swap("हाँ बॉस", "धीरज", ""))
        assertTrue(src("DKApp.kt").contains("NameRule.personaCall = { DkPersona.callWord(this) }"))
    }

    @Test fun live_switchesOnPersonaChange() {
        assertTrue(LivePersonaSwitch.due("friendly|बॉस, सर|false|", "girlfriend|बाबू, जान|false|", false, false, true))
        assertFalse(LivePersonaSwitch.due("a", "a", false, false, true))
        assertFalse(LivePersonaSwitch.due("a", "b", true, false, true))      // DK सुन/बोल रही = रुको
        assertFalse(LivePersonaSwitch.due("a", "b", false, true, true))
        assertFalse(LivePersonaSwitch.due("a", "b", false, false, false))
        assertFalse(LivePersonaSwitch.due("", "b", false, false, true))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("connPersona = DkPersona.liveKey(svc)") && g.contains("LivePersonaSwitch.due(connPersona"))
        assertTrue(src("SettingsVoice.kt").contains("DkPersona.setAddress(this, p, e.text.toString())"))
    }

    @Test fun ring_levels() {
        assertEquals(1, CallTune.levelFor(0, 7))
        assertEquals(7, CallTune.levelFor(100, 7))
        assertEquals(4, CallTune.levelFor(50, 7))
        assertEquals(0, CallTune.levelFor(50, 0))
        assertEquals(7, CallTune.ringTarget(CallTune.FULL, 2, 7, 10, true))       // पहले जैसा
        assertEquals(2, CallTune.ringTarget(CallTune.KEEP, 2, 7, 10, true))
        assertEquals(5, CallTune.ringTarget(CallTune.LEVEL, 2, 7, 70, true))
        assertEquals(0, CallTune.ringTarget(CallTune.LEVEL, 0, 7, 70, false))     // साइलेंट = नहीं छेड़ना
        assertEquals(2, CallTune.listenTarget(7, 7, 25))
        assertEquals(1, CallTune.listenTarget(1, 7, 25))                         // सिर्फ़ नीचे
        assertEquals(5, CallTune.restoreTo(5, 2)); assertNull(CallTune.restoreTo(-1, 2)); assertNull(CallTune.restoreTo(2, 2))
        assertEquals(15, CallTune.secOk(3)); assertEquals(60, CallTune.secOk(99)); assertEquals(30, CallTune.secOk(30))
    }

    @Test fun checks_text() {
        assertTrue(CallTune.checks(true, true, true, true).startsWith("✓"))
        val m = CallTune.checks(false, true, false, false)
        assertTrue(m.contains("ANSWER_PHONE_CALLS") && m.contains("Accessibility") && m.contains("एलान बंद"))
    }

    @Test fun call_wiring() {
        val cm = src("CallManager.kt")
        assertTrue(cm.contains("CallTune.quiet(") && cm.contains("CallTune.restore("))
        val calls = src("DKAccessibilityCalls.kt")
        assertTrue(calls.contains("VolumeKeep.raiseForCall(this)") && calls.contains("calls.tuneRinger()"))
        assertTrue(calls.contains("CallTune.listenMs(this)") && calls.contains("calls.restoreRinger() }"))
        assertTrue(src("DKAccessibilityService.kt").contains("calls.quietRinger(force = true)"))
        assertTrue(src("SettingsSystem.kt").contains("card(\"DK कॉलिंग\""))
        assertTrue(res("AndroidManifest.xml").contains(".CallRingPickActivity"))
    }
}
