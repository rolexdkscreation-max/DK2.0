package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals(0.6f, normalized[0], 0.0001f)
        assertEquals(0.8f, normalized[1], 0.0001f)
    }

    @Test
    fun testNormalizeZeroVector() {
        val vector = floatArrayOf(0.0f, 0.0f, 0.0f)
        val normalized = FaceMatch.normalize(vector)
        assertNotNull(normalized)
        assertEquals(0.0f, normalized[0], 0.0001f)
    }

    @Test
    fun testEvaluateMatchNoFace() {
        val result = FaceMatch.evaluateMatch(null, floatArrayOf(1.0f, 0.0f))
        assertEquals(FaceMatch.MatchResult.NO_FACE, result)
    }

    @Test
    fun testEvaluateMatchNoStoredTarget() {
        val candidate = floatArrayOf(1.0f, 0.0f)
        val result = FaceMatch.evaluateMatch(candidate, null)
        assertEquals(FaceMatch.MatchResult.ACCESS_DENIED, result)
    }

    @Test
    fun testEvaluateMatchAccessGranted() {
        val v1 = floatArrayOf(1.0f, 0.5f, 0.2f)
        val v2 = floatArrayOf(0.98f, 0.52f, 0.19f)
        val result = FaceMatch.evaluateMatch(v1, v2, FaceMatch.NORMAL_THRESHOLD)
        assertEquals(FaceMatch.MatchResult.ACCESS_GRANTED, result)
    }

    @Test
    fun testEvaluateMatchAccessDenied() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val result = FaceMatch.evaluateMatch(v1, v2, FaceMatch.STRICT_THRESHOLD)
        assertEquals(FaceMatch.MatchResult.ACCESS_DENIED, result)
    }

    @Test
    fun testAggregateEmbeddings() {
        val e1 = floatArrayOf(1.0f, 0.0f)
        val e2 = floatArrayOf(0.0f, 1.0f)
        val aggregated = FaceMatch.aggregateEmbeddings(listOf(e1, e2))
        assertNotNull(aggregated)
        assertEquals(2, aggregated.size)
        val expected = (0.5f / Math.sqrt(0.5)).toFloat()
        assertEquals(expected, aggregated[0], 0.001f)
        assertEquals(expected, aggregated[1], 0.001f)
    }
}