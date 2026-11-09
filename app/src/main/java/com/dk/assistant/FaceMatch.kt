package com.dk.assistant

import kotlin.math.sqrt

enum class MatchResult {
    SCANNING,
    ACCESS_GRANTED,
    ACCESS_DENIED,
    NO_FACE
}

object FaceMatch {
    const val EMBEDDING_DIM: Int = 128
    const val STRICT_THRESHOLD: Float = 0.70f
    const val NORMAL_THRESHOLD: Float = 0.60f

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        }
        val denom = sqrt(norm1) * sqrt(norm2)
        if (denom <= 1e-12) return 0f
        return (dot / denom).toFloat()
    }

    fun normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) {
            sumSq += x.toDouble() * x.toDouble()
        }
        val norm = sqrt(sumSq)
        if (norm <= 1e-12) return v.copyOf()
        val result = FloatArray(v.size)
        for (i in v.indices) {
            result[i] = (v[i] / norm).toFloat()
        }
        return result
    }

    fun aggregateEmbeddings(embeddings: List<FloatArray>): FloatArray? {
        if (embeddings.isEmpty()) return null
        val dim = embeddings[0].size
        val avg = FloatArray(dim)
        for (emb in embeddings) {
            if (emb.size != dim) continue
            for (i in 0 until dim) {
                avg[i] += emb[i]
            }
        }
        val count = embeddings.size.toFloat()
        if (count == 0f) return null
        for (i in 0 until dim) {
            avg[i] /= count
        }
        return normalize(avg)
    }

    fun evaluateMatch(
        currentEmbedding: FloatArray?,
        storedEmbedding: FloatArray?,
        threshold: Float = NORMAL_THRESHOLD
    ): Pair<MatchResult, Float> {
        if (currentEmbedding == null) {
            return Pair(MatchResult.NO_FACE, 0f)
        }
        if (storedEmbedding == null) {
            return Pair(MatchResult.SCANNING, 0f)
        }
        val score = cosineSimilarity(currentEmbedding, storedEmbedding)
        val result = if (score >= threshold) {
            MatchResult.ACCESS_GRANTED
        } else {
            MatchResult.ACCESS_DENIED
        }
        return Pair(result, score)
    }
}

class LivenessTracker(
    private val windowSize: Int = 5,
    private val minVariance: Float = 0.0001f
) {
    private val history = mutableListOf<FloatArray>()

    fun processFrame(det: Det?): Boolean {
        if (det == null || det.kps.isEmpty() || det.w <= 0f || det.h <= 0f) {
            history.clear()
            return false
        }

        val kps = det.kps
        val count = kps.size / 2
        if (count < 2) return false

        val normalized = FloatArray(kps.size)
        for (i in 0 until count) {
            normalized[i * 2] = (kps[i * 2] - det.x) / det.w
            normalized[i * 2 + 1] = (kps[i * 2 + 1] - det.y) / det.h
        }

        history.add(normalized)
        if (history.size > windowSize) {
            history.removeAt(0)
        }

        return isLive()
    }

    fun isLive(): Boolean {
        if (history.size < windowSize) return false
        return calculateVariance() >= minVariance
    }

    fun reset() {
        history.clear()
    }

    private fun calculateVariance(): Float {
        if (history.size < 2) return 0f
        val numFeatures = history[0].size
        var totalVariance = 0f

        for (i in 0 until numFeatures) {
            var sum = 0f
            for (frame in history) {
                sum += frame[i]
            }
            val mean = sum / history.size
            var sqDiffSum = 0f
            for (frame in history) {
                val diff = frame[i] - mean
                sqDiffSum += diff * diff
            }
            totalVariance += sqDiffSum / history.size
        }
        return totalVariance / numFeatures
    }
}