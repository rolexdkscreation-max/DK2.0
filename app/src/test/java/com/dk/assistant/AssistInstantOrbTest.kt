package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (02 नवंबर 2026): "power बटन / Hey DK से DK खुले तो Siri जैसा तुरंत साफ़ जवाब और Live तुरंत जुड़े.
 * छोटा ORB तैरता रहे - उंगली से कहीं भी खिसकाएँ; DK सुने / हम बोलें / DK बोले तभी दिखे, चुप = छिपे, फिर बोलें = फिर दिखे."
 */
class AssistInstantOrbTest {
    private fun src(rel: String): String =
        listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel")).first { it.exists() }.readText()

    @Test fun orbShowsOnlyWhileTalkingOrListening() {
        // DK बोल / सोच / काम कर रहा = दिखे
        assertTrue(OrbRules.talkWanted(Mode.SPEAKING, live = true, quietMs = 60_000))
        assertTrue(OrbRules.talkWanted(Mode.THINKING, live = false, quietMs = 60_000))
        assertTrue(OrbRules.talkWanted(Mode.WORKING, live = true, quietMs = 60_000))
        // सुन रहा: अभी-अभी बात हुई = दिखे, लंबी चुप्पी = छिपे
        assertTrue(OrbRules.talkWanted(Mode.LISTENING, live = true, quietMs = 500))
        assertFalse(OrbRules.talkWanted(Mode.LISTENING, live = true, quietMs = OrbRules.QUIET_HIDE_MS))
        // Live नहीं, सिर्फ़ "Hey DK" का पीछे वाला सुनना = ORB नहीं
        assertFalse(OrbRules.talkWanted(Mode.LISTENING, live = false, quietMs = 60_000))
        // बातचीत खत्म = थोड़ी देर में छिपे
        assertTrue(OrbRules.talkWanted(Mode.IDLE, live = false, quietMs = 200))
        assertFalse(OrbRules.talkWanted(Mode.IDLE, live = false, quietMs = OrbRules.IDLE_GRACE_MS))
        // आप बोल रहे (माइक की आवाज़) = "बोलना शुरू"
        assertTrue(OrbRules.talkingNow(Mode.LISTENING, 0.5f))
        assertFalse(OrbRules.talkingNow(Mode.LISTENING, 0.05f))
        assertTrue(OrbRules.talkingNow(Mode.SPEAKING, 0f))
        assertFalse(OrbRules.talkingNow(Mode.IDLE, 0.9f))
        val f = src("FloatOrb.kt")
        assertTrue(f.contains("OrbRules.talkWanted(DKState.shownMode(), DKState.live"))
        assertTrue(f.contains("DKState.listen(onState)") && f.contains("DKState.unlisten(onState)"))
        assertTrue(src("DKAccessibilityService.kt").contains("floatOrb?.release()"))
    }

    @Test fun orbDragsAnywhere_staysOnScreen_positionSaved() {
        assertEquals(0 to 0, OrbRules.clampPos(-50, -20, 100, 100, 1080, 2400))
        assertEquals(980 to 2300, OrbRules.clampPos(5000, 9000, 100, 100, 1080, 2400))
        assertEquals(300 to 700, OrbRules.clampPos(300, 700, 100, 100, 1080, 2400))
        val f = src("FloatOrb.kt")
        assertTrue(f.contains("moveTo(startX + (ev.rawX - downX).roundToInt()"))
        assertTrue(f.contains("wm.updateViewLayout(r, p)") && f.contains("Prefs.setFloatOrbPos("))
        assertTrue(f.contains("Prefs.floatOrbFx(c)"))
        assertFalse(f.contains("खींचने से जगह नहीं बदलती"))
    }

    @Test fun powerButtonGivesInstantAck_andLiveStartsSameMoment() {
        assertTrue(InvokeAck.wanted(Mode.IDLE, sinceLastMs = 60_000, inCall = false))
        assertFalse(InvokeAck.wanted(Mode.IDLE, sinceLastMs = 1_000, inCall = false))      // दो बार नहीं
        assertFalse(InvokeAck.wanted(Mode.SPEAKING, sinceLastMs = 60_000, inCall = false)) // DK बोल रहा = रोकने को दबाया
        assertFalse(InvokeAck.wanted(Mode.IDLE, sinceLastMs = 60_000, inCall = true))      // कॉल में नहीं
        InvokeAck.deafUntil = 0L
        assertFalse(InvokeAck.deafNow())
        InvokeAck.deafUntil = System.currentTimeMillis() + 10_000
        assertTrue(InvokeAck.deafNow())
        InvokeAck.deafUntil = 0L
        assertTrue(InvokeAck.ACK_DEAF_MAX_MS <= 3_000L)       // माइक कभी लंबा बंद नहीं
        val s = src("DKAccessibilityService.kt")
        val inv = s.substring(s.indexOf("fun invokedNow("), s.indexOf("fun invokedNow(") + 900)
        // Live पहले शुरू, फिर ack (पीछे के thread पर) - ack Live को नहीं रोकता
        assertTrue(inv.indexOf("startLive()") in 0 until inv.indexOf("InvokeAck.play("))
        assertTrue(inv.contains("LiveNet.warm(") && inv.contains("Dispatchers.IO"))
        // ack बजते समय माइक की आवाज़ Gemini को नहीं (अपनी आवाज़ को सवाल न समझे)
        assertTrue(src("GeminiLiveChecks.kt").contains("InvokeAck.deafNow()"))
        assertTrue(src("voice/HeyDk.kt").contains("fun hasAck("))
    }
}
