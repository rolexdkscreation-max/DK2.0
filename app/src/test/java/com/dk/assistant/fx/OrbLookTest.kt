package com.dk.assistant.fx

import com.dk.assistant.Mode
import com.dk.assistant.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.65: "3D रोशनी का गोला" (sphere3d) मालिक के कहने पर हटा - अब पक्का करें कि वह सूची में नहीं और पुराना चुनाव पृथ्वी बने */
class OrbLookTest {
    @Test fun sphereStyleRemovedAndFallsBackToEarth() {
        assertTrue(Shapes.STYLES.none { it.first == "sphere3d" })
        assertEquals("earth", Shapes.valid("sphere3d"))
        val cl = Shapes.make("sphere3d", 100)
        assertEquals(100, cl.count)
    }

    @Test fun oldStylesStillThere() {
        val keys = Shapes.STYLES.map { it.first }
        for (k in listOf("earth", "galaxy", "atom", "torus", "face_male", "face_female")) assertTrue(k in keys)
    }
}

class EdgeGlowRulesTest {
    @Test fun offWhenDisabledOrIdle() {
        assertNull(EdgeGlowRules.color(false, true, Mode.LISTENING))
        assertNull(EdgeGlowRules.color(true, false, Mode.IDLE))
    }

    @Test fun colorsByState() {
        assertEquals(EdgeGlowRules.VIOLET, EdgeGlowRules.color(true, true, Mode.LISTENING))
        assertEquals(EdgeGlowRules.VIOLET, EdgeGlowRules.color(true, false, Mode.THINKING))
        assertEquals(EdgeGlowRules.PINK, EdgeGlowRules.color(true, true, Mode.SPEAKING))
        assertEquals(EdgeGlowRules.RED, EdgeGlowRules.color(true, true, Mode.SPEAKING, error = true))
        assertEquals(EdgeGlowRules.VIOLET, EdgeGlowRules.color(true, true, Mode.IDLE))
    }

    @Test fun intensityInRangeAndStillWhenReduced() {
        for (m in Mode.values()) for (t in 0..40) {
            val v = EdgeGlowRules.intensity(m, 0.5f, t / 9f, false)
            assertTrue(v in 0f..1f)
            assertEquals(0.75f, EdgeGlowRules.intensity(m, 0.5f, t / 9f, true), 0f)
        }
    }
}
