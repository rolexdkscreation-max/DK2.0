package com.dk.assistant

import com.dk.assistant.fx.EdgeGlowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v4.x: हालत के साथ आकार बदलने वाले 5 नए ORB, रंग-बिरंगी किनारों की चमक,
 * और पुरुष/महिला चेहरे के साथ आधा धड़ + अंदर की हल्की रोशनी.
 */
class StateOrbsBustTest {
    private val NEW = StateOrbs.STYLES.map { it.first }
    private val out = FloatArray(4)

    @Test fun fiveNewOrbs_listedInSettings_oldOnesKept() {
        assertEquals(5, NEW.size)
        val keys = Shapes.STYLES.map { it.first }
        for (k in NEW) { assertTrue(k in keys); assertEquals(k, Shapes.valid(k)); assertTrue(StateOrbs.code(k) > 100) }
        assertEquals(keys.size, keys.toSet().size)
        for (k in listOf(Shapes.NEON, "earth", "face_male", "face_female", FutureOrbs.NEURAL, FutureOrbs.STARGATE)) assertTrue(k in keys)
        assertEquals(0, StateOrbs.code("earth")); assertEquals(0, StateOrbs.code(null)); assertFalse(StateOrbs.has("face_male"))
    }

    @Test fun everyStateHasItsOwnShape_andColor() {
        for (s in NEW) {
            val codes = (0..3).map { StateOrbs.code(StateOrbs.key(s, it)) }
            assertEquals("$s - चारों हालत के आकार अलग", 4, codes.toSet().size)
            val cols = (0..3).map { StateOrbs.colors(s, it)[0] }
            assertEquals("$s - चारों हालत के रंग अलग", 4, cols.toSet().size)
            // बनी हुई जगहें भी सच में अलग
            val a = Shapes.make(StateOrbs.key(s, 0), 600); val b = Shapes.make(StateOrbs.key(s, 2), 600)
            var same = 0
            for (i in 0 until 600) if (abs(a.x[i] - b.x[i]) < 1e-4f && abs(a.y[i] - b.y[i]) < 1e-4f && abs(a.z[i] - b.z[i]) < 1e-4f) same++
            assertTrue("$s idle vs thinking same=$same", same < 60)
        }
        // सभी 20 आकार अलग-अलग
        assertEquals(20, NEW.flatMap { s -> (0..3).map { StateOrbs.code(StateOrbs.key(s, it)) } }.toSet().size)
    }

    @Test fun modeMapsToState() {
        assertEquals(StateOrbs.IDLE, StateOrbs.state(Mode.IDLE))
        assertEquals(StateOrbs.LISTEN, StateOrbs.state(Mode.LISTENING))
        assertEquals(StateOrbs.THINK, StateOrbs.state(Mode.THINKING))
        assertEquals(StateOrbs.THINK, StateOrbs.state(Mode.WORKING))
        assertEquals(StateOrbs.SPEAK, StateOrbs.state(Mode.SPEAKING))
        assertEquals("sx_nova|3", StateOrbs.key(StateOrbs.NOVA, 3))
        assertEquals("sx_nova|1", StateOrbs.key("sx_nova|3", 1))
        assertTrue(StateOrbs.isKey("sx_nova|2")); assertFalse(StateOrbs.isKey("sx_nova"))
    }

    @Test fun everyShapeFull_inBounds_andMovesSafely() {
        for (s in NEW) for (st in 0..3) for (n in listOf(1400, 320, 50)) {
            val key = StateOrbs.key(s, st)
            val c = Shapes.make(key, n)
            assertEquals("$key n=$n", n, c.count)
            var bright = 0
            val code = StateOrbs.code(key)
            for (i in 0 until c.count) {
                val d = sqrt(c.x[i] * c.x[i] + c.y[i] * c.y[i] + c.z[i] * c.z[i])
                assertTrue("$key dot $i finite", d.isFinite())
                assertTrue("$key dot $i too far: $d", d <= 1.45f)
                if (c.kind[i].toInt() == 1) bright++
                for (t in listOf(0f, 1.3f, 7.7f, 333.3f)) for (amp in listOf(0f, 1f)) {
                    StateOrbs.move(code, i, c.x[i], c.y[i], c.z[i], c.anc[i], t, amp, out)
                    val m = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
                    assertTrue("$key move $i finite", m.isFinite() && out[3].isFinite())
                    assertTrue("$key move $i too far: $m", m <= 1.5f)
                    assertTrue("$key brightness $i ${out[3]}", out[3] in 0f..2.5f)
                }
            }
            assertTrue("$key has glowing dots", bright > 0)
            assertTrue("$key has normal dots", bright < c.count)
        }
    }

    @Test fun baseNameBuildsIdleShape() {
        val a = Shapes.make(StateOrbs.AURORA, 500); val b = Shapes.make(StateOrbs.key(StateOrbs.AURORA, 0), 500)
        for (i in 0 until 500) assertEquals(a.x[i], b.x[i], 0f)
        // 3200 में बाकी छिपे (पहले जैसी रफ़्तार)
        val big = Shapes.make(StateOrbs.key(StateOrbs.CYBER, 3), 3200)
        var hidden = 0
        for (i in 0 until big.count) if (big.kind[i].toInt() == Shapes.HIDDEN) hidden++
        assertEquals(3200 - Shapes.PLAIN_DOTS, hidden)
    }

    // ------------------------------------------------------------ किनारों की रंग-बिरंगी चमक
    @Test fun edgeGlowRainbowFlows() {
        val r = EdgeGlowRules.RAINBOW
        assertTrue(r.size >= 6)
        assertEquals("घेरे में जोड़ न दिखे", r.first(), r.last())
        assertTrue(r.dropLast(1).toSet().size == r.size - 1)
        for (m in Mode.values()) assertTrue(EdgeGlowRules.flowSpeed(m, 0.5f, false) > 0f)
        for (m in Mode.values()) assertEquals(0f, EdgeGlowRules.flowSpeed(m, 0.5f, true), 0f)
        assertTrue(EdgeGlowRules.flowSpeed(Mode.LISTENING, 1f, false) > EdgeGlowRules.flowSpeed(Mode.LISTENING, 0f, false))
        assertTrue(EdgeGlowRules.flowSpeed(Mode.THINKING, 0f, false) > EdgeGlowRules.flowSpeed(Mode.IDLE, 0f, false))
    }

    // ------------------------------------------------------------ चेहरा + आधा धड़
    private fun count(c: Cloud, f: (Int) -> Boolean): Int { var k = 0; for (i in 0 until c.count) if (f(i)) k++; return k }

    @Test fun faceHasHalfTorso_inBounds() {
        for (female in listOf(false, true)) for (n in listOf(3200, 1400, 320)) {
            val c = Shapes.make(if (female) "face_female" else "face_male", n)
            assertEquals(n, c.count)
            for (i in 0 until c.count) {
                assertTrue(c.x[i].isFinite() && c.y[i].isFinite() && c.z[i].isFinite())
                assertTrue("x $i ${c.x[i]}", abs(c.x[i]) <= 1.3f)
                assertTrue("y $i ${c.y[i]}", abs(c.y[i]) <= 1.45f)
                assertTrue("z $i ${c.z[i]}", abs(c.z[i]) <= 1.3f)
                assertTrue(c.lum[i] in 0f..1f)
                assertTrue(c.kind[i].toInt() in 0..4 || c.kind[i].toInt() == FaceBust.GLOW)
            }
        }
        for (female in listOf(false, true)) {
            val c = FaceBust.build(3200, female)
            val body = count(c) { c.lower[it] && c.kind[it].toInt() <= 1 }
            assertTrue("धड़ के डॉट $body", body > 600)
            // धड़ नीचे तक (सीने के नीचे) - सिर्फ़ गर्दन नहीं
            assertTrue(count(c) { c.lower[it] && c.y[it] < -1.0f } > 100)
            // कंधे / बाँहें चौड़ी
            assertTrue(count(c) { c.lower[it] && abs(c.x[it]) > 0.6f } > 60)
            // अंदर की हल्की रोशनी - सिर और धड़ दोनों में, पर कम (चेहरा ढके नहीं)
            val glow = count(c) { c.kind[it].toInt() == FaceBust.GLOW }
            assertTrue("glow $glow", glow in 150..400)
            assertTrue(count(c) { c.kind[it].toInt() == FaceBust.GLOW && c.y[it] > 0.4f } > 30)
            assertTrue(count(c) { c.kind[it].toInt() == FaceBust.GLOW && c.y[it] < -0.5f } > 60)
            // चेहरा अब भी पूरा: आँखें, होंठ, बाल
            assertTrue(count(c) { c.kind[it].toInt() == 2 } > 100)
            assertTrue(count(c) { c.kind[it].toInt() == 3 } > 40)
            assertTrue(count(c) { c.kind[it].toInt() == 4 } > 350)
            // आँख का बीच (पलक) भी सिर के साथ खिसका
            for (i in 0 until c.count) if (c.kind[i].toInt() == 2) assertTrue(abs(c.anc[i] - c.y[i]) < 0.15f)
        }
    }

    @Test fun maleAndFemaleBodiesDiffer_andStable() {
        val m = FaceBust.build(3200, false); val f = FaceBust.build(3200, true)
        fun width(c: Cloud): Float { var w = 0f; for (i in 0 until c.count) if (c.lower[i] && c.y[i] < -0.5f) w = maxOf(w, abs(c.x[i])); return w }
        assertTrue("पुरुष के कंधे चौड़े ${width(m)} > ${width(f)}", width(m) > width(f) + 0.08f)
        val a = FaceBust.build(1400, true); val b = FaceBust.build(1400, true)
        for (i in 0 until 1400) assertEquals(a.x[i], b.x[i], 0f)
        assertNotEquals(m.x[2500], f.x[2500])
    }
}
