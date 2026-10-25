package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** v3.65: HD डॉट चेहरा - पूरे डॉट, सीमा के अंदर, आँख/होंठ/बाल हों, पुरुष-महिला साफ़ अलग दिखें */
class FaceHDTest {
    private fun count(c: Cloud, f: (Int) -> Boolean): Int { var k = 0; for (i in 0 until c.count) if (f(i)) k++; return k }

    @Test fun fullCountFiniteAndInBounds() {
        for (female in listOf(false, true)) for (n in listOf(3200, 1400, 320)) {
            val c = FaceHD.build(n, female)
            assertEquals("female=$female n=$n", n, c.count)
            for (i in 0 until c.count) {
                assertTrue(c.x[i].isFinite() && c.y[i].isFinite() && c.z[i].isFinite())
                assertTrue("x $i", abs(c.x[i]) <= 1.3f)
                assertTrue("y $i", abs(c.y[i]) <= 1.45f)
                assertTrue("z $i", abs(c.z[i]) <= 1.3f)
                assertTrue("lum $i", c.lum[i] in 0f..1f)
                assertTrue(c.kind[i].toInt() in 0..4)
            }
        }
    }

    @Test fun hasEyesLipsHairAndTalkingLip() {
        for (female in listOf(false, true)) {
            val c = FaceHD.build(3200, female)
            assertTrue(count(c) { c.kind[it].toInt() == 2 } > 150)          // आँखें (पलक झपके)
            assertTrue(count(c) { c.kind[it].toInt() == 3 } > 60)           // होंठ
            assertTrue(count(c) { c.kind[it].toInt() == 4 } > 500)          // बाल
            assertTrue(count(c) { c.kind[it].toInt() == 3 && c.lower[it] } > 10)   // बोलते समय निचला होंठ
            assertTrue(count(c) { c.lum[it] < 0.6f } > 100)                 // रोशनी-छाया
        }
    }

    @Test fun maleHasBeardFemaleHasLongHair() {
        val m = FaceHD.build(3200, false); val f = FaceHD.build(3200, true)
        // ठोड़ी/जबड़े पर, सामने की ओर बाल-डॉट = दाढ़ी (सिर्फ़ पुरुष)
        fun beard(c: Cloud) = count(c) { c.kind[it].toInt() == 4 && c.y[it] < -0.05f && c.y[it] > -0.6f && abs(c.x[it]) < 0.6f && c.z[it] > 0.1f }
        assertTrue("male beard ${beard(m)}", beard(m) > 80)
        assertTrue("female no beard ${beard(f)}", beard(f) < 10)
        // ठोड़ी से नीचे, चेहरे के बगल में बाल = लंबे बाल (सिर्फ़ महिला)
        fun longHair(c: Cloud) = count(c) { c.kind[it].toInt() == 4 && c.y[it] < -0.3f && abs(c.x[it]) > 0.3f }
        assertTrue("female long hair ${longHair(f)}", longHair(f) > 40)
        assertTrue("male short hair ${longHair(m)}", longHair(m) < 10)
    }

    @Test fun sameEveryTime() {
        val a = FaceHD.build(1400, true); val b = FaceHD.build(1400, true)
        for (i in 0 until 1400) assertEquals(a.x[i], b.x[i], 0f)
    }

    @Test fun shapesRouteToHdFace() {
        assertEquals(3200, Shapes.make("face_male", 3200).count)
        assertEquals(3200, Shapes.make("face_female", 3200).count)
        assertEquals(0, count(Shapes.make("face_female", 3200)) { Shapes.make("face_female", 3200).kind[it].toInt() == Shapes.HIDDEN })
    }
}
