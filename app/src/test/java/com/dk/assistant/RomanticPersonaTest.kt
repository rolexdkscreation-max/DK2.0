package com.dk.assistant

import com.dk.assistant.plus.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (हिस्सा 3): रोमांटिक (18+) */
class RomanticPersonaTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()
    private val R = RomanticPersona

    @Test fun stopWordsTurnOff() {
        for (s in listOf("रुको", "बंद करो", "रुको।", "रोमांटिक मोड बंद करो", "Stop")) assertEquals(s, RomanticPersona.Hit.STOP, R.detect(s))
        // फ़ोन के काम नहीं रुकते
        for (s in listOf("टॉर्च बंद करो", "वाईफाई बंद करो", "मौसम बताओ", "मैं 5 मिनट में आता हूँ", "मैं 25 साल का हूँ")) assertNull(s, R.detect(s))
    }

    @Test fun minorSignal() {
        for (s in listOf("मैं 16 साल का हूँ", "मेरी उम्र 15 है", "i am 14 years old", "मैं नाबालिग हूँ", "मैं स्कूल में पढ़ता हूँ", "my age is 13"))
            assertEquals(s, RomanticPersona.Hit.MINOR, R.detect(s))
        assertTrue(R.quick(RomanticPersona.Hit.MINOR).action.getBoolean("minor"))
        assertEquals(RomanticPersona.TYPE, R.quick(RomanticPersona.Hit.STOP).action.getString("type"))
    }

    @Test fun blockIsDecent() {
        val b = R.BLOCK
        assertTrue(b.contains("NO sexual, explicit") && b.contains("under 18") && b.contains("रुको"))
        assertTrue(b.contains("Phone control, tools"))
        assertEquals("मैं 18+ हूँ और मेरी सहमति है", R.CONSENT_TEXT)
    }

    @Test fun flagOffByDefaultAndWired() {
        assertFalse(FeatureFlags.byKey(FeatureFlags.ADULT_STYLE)!!.default)
        assertTrue(FeatureFlags.ADULT_STYLE in FeatureFlags.READY)
        assertEquals(5, DkPersona.P.values().size)        // पुराने स्वभाव वैसे ही
        assertEquals(DkPersona.P.GIRLFRIEND, DkPersona.parse("रोमांटिक मोड ऑन करो"))
        assertTrue(src("DkPersona.kt").contains("RomanticPersona.BLOCK"))
        assertTrue(src("LocalRouter.kt").contains("if (RomanticPersona.live) RomanticPersona.detect(raw)"))
        assertTrue(src("DKAccessibilityService.kt").contains("RomanticPersona.disable(this, minor = a.optBoolean(\"minor\"))"))
        val v = src("SettingsVoice.kt")
        assertTrue(v.contains("romanticSwitch(box)") && v.contains("RomanticPersona.CONSENT_TEXT"))
    }
}
