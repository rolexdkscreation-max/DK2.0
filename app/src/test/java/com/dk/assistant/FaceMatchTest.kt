package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMatchTest {

    @Test
    fun testCosineSimilarityIdenticalVectors() {
        val v1 = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val v2 = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val similarity = FaceMatch.cosineSimilarity(v1, v2)
        assertEquals(1.0f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOrthogonalVectors() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val similarity = FaceMatch.cosineSimilarity(v1, v2)
        assertEquals(0.0f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOppositeVectors() {
        val v1 = floatArrayOf(1.0f, 0.0f)
        val v2 = floatArrayOf(-1.0f, 0.0f)
        val similarity = FaceMatch.cosineSimilarity(v1, v2)
        assertEquals(-1.0f, similarity, 0.0001f)
    }

    @Test
    fun testCosineSimilarityDifferentLengths() {
        val v1 = floatArrayOf(1.0f, 2.0f)
        val v2 = floatArrayOf(1.0f)
        val similarity = FaceMatch.cosineSimilarity(v1, v2)
        assertEquals(0.0f, similarity, 0.0001f)
    }

    @Test
    fun testNormalizeVector() {
        val vector = floatArrayOf(3.0f, 4.0f)
        val normalized = FaceMatch.normalize(vector)
        assertNotNull(normalized)
        assertEquals(0.6f, normalized!![0], 0.0001f)
        assertEquals(0.8f, normalized[1], 0.0001f)
    }

    @Test
    fun testNormalizeZeroVector() {
        val vector = floatArrayOf(0.0f, 0.0f, 0.0f)
        val normalized = FaceMatch.normalize(vector)
        assertNotNull(normalized)
        assertEquals(0.0f, normalized!![0], 0.0001f)
    }

    @Test
    fun testEvaluateMatchNoFace() {
        val result = FaceMatch.evaluateMatch(null, floatArrayOf(1.0f, 0.0f))
        assertEquals(MatchResult.NO_FACE, result.first)
    }

    @Test
    fun testEvaluateMatchNoStoredTarget() {
        val candidate = floatArrayOf(1.0f, 0.0f)
        val result = FaceMatch.evaluateMatch(candidate, null)
        assertEquals(MatchResult.SCANNING, result.first)
    }

    @Test
    fun testEvaluateMatchAccessGranted() {
        val v1 = floatArrayOf(1.0f, 0.5f, 0.2f)
        val v2 = floatArrayOf(0.98f, 0.52f, 0.19f)
        val result = FaceMatch.evaluateMatch(v1, v2, FaceMatch.NORMAL_THRESHOLD)
        assertEquals(MatchResult.ACCESS_GRANTED, result.first)
    }

    @Test
    fun testEvaluateMatchAccessDenied() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val result = FaceMatch.evaluateMatch(v1, v2, FaceMatch.STRICT_THRESHOLD)
        assertEquals(MatchResult.ACCESS_DENIED, result.first)
    }

    @Test
    fun testAggregateEmbeddings() {
        val e1 = floatArrayOf(1.0f, 0.0f)
        val e2 = floatArrayOf(0.0f, 1.0f)
        val aggregated = FaceMatch.aggregateEmbeddings(listOf(e1, e2))
        assertNotNull(aggregated)
        assertEquals(2, aggregated!!.size)
        val expected = (0.5f / Math.sqrt(0.5)).toFloat()
        assertEquals(expected, aggregated[0], 0.001f)
        assertEquals(expected, aggregated[1], 0.001f)
    }

    @Test
    fun testLivenessTrackerStatic2DPhoto() {
        val tracker = FaceMatch.LivenessTracker()
        val staticKps = floatArrayOf(10.0f, 10.0f, 20.0f, 10.0f, 15.0f, 15.0f, 11.0f, 20.0f, 19.0f, 20.0f)
        val det = Det(0.9f, 0.0f, 0.0f, 100.0f, 100.0f, staticKps)
        var isLive = false
        for (i in 0 until 6) {
            isLive = tracker.addDetection(det)
        }
        assertFalse(isLive)
    }

    @Test
    fun testLivenessTrackerMicroMovingLive() {
        val tracker = FaceMatch.LivenessTracker()
        var isLive = false
        for (i in 0 until 6) {
            val kps = floatArrayOf(
                10.0f + i * 0.5f, 10.0f + (i % 2) * 0.4f,
                20.0f - i * 0.3f, 10.0f,
                15.0f, 15.0f + i * 0.2f,
                11.0f + i * 0.1f, 20.0f,
                19.0f, 20.0f - i * 0.2f
            )
            val det = Det(0.9f, 0.0f, 0.0f, 100.0f, 100.0f, kps)
            isLive = tracker.addDetection(det)
        }
        assertTrue(isLive)
    }
}