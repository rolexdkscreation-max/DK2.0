package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VoicePrintTest {
    private fun vec(seed: Int) = Random(seed).let { r -> FloatArray(VoicePrint.DIM) { r.nextFloat() - 0.5f } }

    @Test fun cosineBasics() {
        val a = vec(1)
        assertEquals(1f, VoicePrint.cosine(a, a), 1e-5f)
        assertEquals(-1f, VoicePrint.cosine(a, FloatArray(a.size) { -a[it] }), 1e-5f)
        assertEquals(0f, VoicePrint.cosine(a, FloatArray(3)), 0f)
        assertEquals(0f, VoicePrint.cosine(a, FloatArray(a.size)), 0f)
    }

    @Test fun strictIsHigherThanNormal() {
        assertTrue(VoicePrint.threshold(true) > VoicePrint.threshold(false))
    }

    @Test fun encodeDecodeRoundTripAndRejectsJunk() {
        val v = VoicePrint.normalize(vec(2))
        val d = VoicePrint.decode(VoicePrint.encode(v))!!
        assertTrue(VoicePrint.cosine(v, d) > 0.9999f)
        assertNull(VoicePrint.decode(null)); assertNull(VoicePrint.decode(""))
        assertNull(VoicePrint.decode("1,2,3"))
        assertNull(VoicePrint.decode(List(VoicePrint.DIM) { "x" }.joinToString(",")))
        assertNull(VoicePrint.decode(List(VoicePrint.DIM) { "NaN" }.joinToString(",")))
    }

    @Test fun samePersonConsistentOtherPersonNot() {
        val base = vec(3)
        val same = (0 until 5).map { k -> val n = vec(100 + k); FloatArray(base.size) { base[it] + 0.3f * n[it] } }
        assertTrue(VoicePrint.consistency(same) > VoicePrint.ENROLL_MIN_CONSISTENCY)
        val mixed = same.take(4) + listOf(vec(999))
        assertTrue(VoicePrint.consistency(mixed) < VoicePrint.ENROLL_MIN_CONSISTENCY)
        assertNotNull(VoicePrint.mean(same)); assertNull(VoicePrint.mean(emptyList()))
    }

    @Test fun farFloorScalesAndOffWhenUnknown() {
        assertEquals(0.0, VoicePrint.farFloor(0.0, false), 0.0)
        assertEquals(400.0, VoicePrint.farFloor(2000.0, false), 1e-6)
        assertTrue(VoicePrint.farFloor(2000.0, true) > VoicePrint.farFloor(2000.0, false))
        assertEquals(2500.0, VoicePrint.farFloor(50000.0, true), 0.0)
    }

    @Test fun speechLevelIgnoresQuietParts() {
        assertEquals(0.0, VoicePrint.speechLevel(listOf(2000.0, 2000.0), 200.0), 0.0)
        val r = listOf(100.0, 120.0, 1800.0, 2000.0, 2200.0, 1900.0, 2100.0, 90.0)
        assertEquals(2000.0, VoicePrint.speechLevel(r, 200.0), 0.0)
    }

    @Test fun adaptLevelStaysNearEnrolled() {
        var cur = 0.0
        repeat(200) { cur = VoicePrint.adaptLevel(2000.0, cur, 100000.0) }
        assertEquals(4000.0, cur, 1e-6)
        repeat(200) { cur = VoicePrint.adaptLevel(2000.0, cur, 1.0) }
        assertEquals(1000.0, cur, 1e-6)
    }
}
