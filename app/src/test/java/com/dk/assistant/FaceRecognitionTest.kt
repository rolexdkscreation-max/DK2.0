package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceRecognitionTest {

    private val frontKps = floatArrayOf(40f, 50f, 60f, 50f, 50f, 61f, 42f, 72f, 58f, 72f)

    private fun det(k: FloatArray): FaceMath.Det {
        val xs = k.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = k.filterIndexed { i, _ -> i % 2 == 1 }
        val x = xs.min() - 10f
        val y = ys.min() - 15f
        return FaceMath.Det(0.9f, x, y, xs.max() - xs.min() + 20f, ys.max() - ys.min() + 25f, k)
    }

    private fun turned(deg: Double): FloatArray {
        val t = Math.toRadians(deg)
        val p = listOf(
            Triple(-10.0, 50.0, 0.0),
            Triple(10.0, 50.0, 0.0),
            Triple(0.0, 61.0, 12.0),
            Triple(-8.0, 72.0, 2.0),
            Triple(8.0, 72.0, 2.0)
        )
        return p.flatMap { (x, y, z) ->
            listOf((50 + x * cos(t) + z * sin(t)).toFloat(), y.toFloat())
        }.toFloatArray()
    }

    @Test
    fun testFaceMathEvaluateMatchSuccess() {
        val ownerEmb = FloatArray(128) { 0.5f }
        val currentEmb = FloatArray(128) { 0.5f }
        val score = FaceMatch.cosineSimilarity(ownerEmb, currentEmb)
        val res = FaceMath.decide(score, strict = false)
        assertEquals(1.0f, score, 0.001f)
        assertEquals(MatchResult.ACCESS_GRANTED, res)
    }

    @Test
    fun testFaceMathEvaluateMatchMismatch() {
        val ownerEmb = FloatArray(128) { 0.5f }
        val strangerEmb = FloatArray(128) { if (it % 2 == 0) 0.5f else -0.5f }
        val score = FaceMatch.cosineSimilarity(ownerEmb, strangerEmb)
        val res = FaceMath.decide(score, strict = false)
        assertTrue(score < FaceMath.MATCH)
        assertEquals(MatchResult.ACCESS_DENIED, res)
    }

    @Test
    fun testLivenessFull3DFaceTurnSuccess() {
        val liveness = FaceLiveness()
        var time = 0L

        fun step(deg: Double): Boolean = liveness.update(det(turned(deg)), time.also { time += 150 })

        assertFalse(step(0.0))
        assertFalse(step(25.0))
        assertFalse(step(-25.0))
        assertTrue(step(0.0))
        assertEquals(FaceLiveness.Step.LIVE, liveness.step)
    }

    @Test
    fun testLivenessPhotoStaticFails() {
        val liveness = FaceLiveness()
        var time = 0L
        val staticDet = det(frontKps)
        var isLive = false

        for (i in 0 until 20) {
            isLive = liveness.update(staticDet, time)
            time += 150
        }

        assertFalse(isLive)
        assertTrue(liveness.flatCount() >= 5)
    }
}