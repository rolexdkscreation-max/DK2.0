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