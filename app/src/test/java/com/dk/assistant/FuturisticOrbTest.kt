package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** v3.62: नए (v3.65 से टेसरैक्ट/वर्महोल हटे) futuristic 3D डॉट ओर्ब - सूची में हैं, पूरे डॉट बनते हैं, आकार गोले के दायरे में */
class FuturisticOrbTest {
    private val NEW = listOf("geodesic", "knot", "dyson")

    @Test fun listedWithNames() {
        val keys = Shapes.STYLES.map { it.first }
        for (k in NEW) assertTrue(k in keys)
        assertEquals(keys.size, keys.toSet().size)
        for (k in listOf("earth", "galaxy", "atom", "torus", "face_male", "face_female")) assertTrue(k in keys)
    }

    @Test fun removedStylesGoneAndFallBackToEarth() {
        val keys = Shapes.STYLES.map { it.first }
        for (k in listOf("dna", "tesseract", "wormhole", "sphere3d")) {
            assertTrue("$k should be removed", k !in keys)
            assertEquals("earth", Shapes.valid(k))
            assertEquals(500, Shapes.make(k, 500).count)
        }
        assertEquals("earth", Shapes.valid(null))
        assertEquals("knot", Shapes.valid("knot"))
    }

    @Test fun plainShapesPadHiddenDotsAtFullCount() {
        val c = Shapes.make("earth", 3200)
        assertEquals(3200, c.count)
        var hidden = 0
        for (i in 0 until c.count) if (c.kind[i].toInt() == Shapes.HIDDEN) hidden++
        assertEquals(3200 - Shapes.PLAIN_DOTS, hidden)
    }

    @Test fun everyDotPlacedAndInBounds() {
        for (style in NEW) for (n in listOf(1400, 300, 50)) {
            val c = Shapes.make(style, n)
            assertEquals("$style n=$n", n, c.count)
            var bright = 0
            for (i in 0 until c.count) {
                val d = sqrt(c.x[i] * c.x[i] + c.y[i] * c.y[i] + c.z[i] * c.z[i])
                assertTrue("$style dot $i finite", d.isFinite())
                assertTrue("$style dot $i too far: $d", d <= 1.45f)
                if (c.kind[i].toInt() == 1) bright++
            }
            assertTrue("$style has glowing dots", bright > 0)
            assertTrue("$style has normal dots", bright < c.count)
        }
    }

    @Test fun shapesDiffer() {
        val a = Shapes.make("geodesic", 400); val b = Shapes.make("knot", 400)
        var same = 0
        for (i in 0 until 400) if (a.x[i] == b.x[i] && a.y[i] == b.y[i]) same++
        assertTrue(same < 40)
    }
}
