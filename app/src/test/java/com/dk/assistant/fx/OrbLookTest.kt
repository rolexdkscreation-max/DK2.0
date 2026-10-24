package com.dk.assistant.fx

import com.dk.assistant.Mode
import com.dk.assistant.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbLookTest {
    @Test fun everyModeHasOwnPalette() {
        val all = Mode.values().map { OrbLook.palette(it) }
        assertEquals(all.size, all.toSet().size)
        assertNotEquals(OrbLook.palette(Mode.IDLE), OrbLook.palette(Mode.IDLE, error = true))
    }

    @Test fun thinkingSwirlsFastest() {
        val fastest = Mode.values().maxByOrNull { OrbLook.swirl(it) }
        assertEquals(Mode.THINKING, fastest)
    }

    @Test fun amplitudeFollowsMicWhileListening() {
        val quiet = OrbLook.amplitude(Mode.LISTENING, 0f, 0f)
        val loud = OrbLook.amplitude(Mode.LISTENING, 1f, 0f)
        assertTrue(loud > quiet)
        for (t in 0..50) {
            val a = OrbLook.amplitude(Mode.SPEAKING, 0f, t / 7f)
            assertTrue(a in 0f..1f)
        }
    }

    @Test fun reducedMotionFreezesScale() {
        for (t in 0..20) assertEquals(1f, OrbLook.scale(Mode.LISTENING, t * 0.37f, reducedMotion = true), 0f)
    }

    @Test fun lerpEndsAndMiddle() {
        val a = 0xFF000000.toInt(); val b = 0xFFFFFFFF.toInt()
        assertEquals(a, OrbLook.lerp(a, b, 0f))
        assertEquals(b, OrbLook.lerp(a, b, 1f))
        val m = OrbLook.lerp(a, b, 0.5f)
        assertEquals(0xFF, (m ushr 24) and 0xFF)
        assertTrue(((m shr 16) and 0xFF) in 120..135)
    }

    @Test fun lowPassMovesSlowly() {
        val v = OrbLook.lowPass(0f, 1f)
        assertTrue(v > 0f && v < 0.2f)
    }

    @Test fun sphereStyleListedAndFallsBackToDots() {
        assertTrue(Shapes.STYLES.any { it.first == OrbLook.STYLE })
        // पुराने फ़ोन पर: डॉट वाला आकार बनना चाहिए (crash नहीं)
        val cl = Shapes.make(OrbLook.STYLE, 100)
        assertNotEquals(null, cl)
    }

    @Test fun oldStylesStillThere() {
        val keys = Shapes.STYLES.map { it.first }
        for (k in listOf("earth", "galaxy", "atom", "dna", "torus", "face_male", "face_female")) assertTrue(k in keys)
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
