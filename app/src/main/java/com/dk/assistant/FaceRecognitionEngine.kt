package com.dk.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import java.util.concurrent.atomic.AtomicBoolean

class FaceRecognitionEngine(private val context: Context) {

    data class AnalysisResult(
        val result: MatchResult,
        val similarity: Float,
        val faceBounds: RectF?,
        val embedding: FloatArray?,
        val scanProgress: Float
    )

    private val isProcessing = AtomicBoolean(false)
    private var scanFrameCount = 0
    private val requiredScanFrames = 5
    private val accumulatedEmbeddings = mutableListOf<FloatArray>()

    fun processFrame(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }

        try {
            val faceBounds = detectFaceBounds(bitmap)
            if (faceBounds == null) {
                scanFrameCount = 0
                accumulatedEmbeddings.clear()
                onResult(
                    AnalysisResult(
                        result = MatchResult.NO_FACE,
                        similarity = 0f,
                        faceBounds = null,
                        embedding = null,
                        scanProgress = 0f
                    )
                )
                return
            }

            val embedding = generateFaceEmbedding(bitmap, faceBounds)
            val storedEmbedding = Prefs.ownerFacePrint(context)

            if (storedEmbedding == null) {
                scanFrameCount++
                val progress = (scanFrameCount.toFloat() / requiredScanFrames).coerceIn(0f, 1f)
                accumulatedEmbeddings.add(embedding)

                onResult(
                    AnalysisResult(
                        result = MatchResult.SCANNING,
                        similarity = 0f,
                        faceBounds = faceBounds,
                        embedding = embedding,
                        scanProgress = progress
                    )
                )
            } else {
                val (matchResult, similarity) = FaceMatch.evaluateMatch(embedding, storedEmbedding)

                scanFrameCount = (scanFrameCount + 1).coerceAtMost(requiredScanFrames)
                val progress = 1.0f

                onResult(
                    AnalysisResult(
                        result = matchResult,
                        similarity = similarity,
                        faceBounds = faceBounds,
                        embedding = embedding,
                        scanProgress = progress
                    )
                )
            }
        } catch (e: Exception) {
            DKLog.d("FaceRecognitionEngine: Error analyzing frame: ${e.message}")
        } finally {
            isProcessing.set(false)
        }
    }

    fun completeEnrollment(): Boolean {
        if (accumulatedEmbeddings.isEmpty()) return false
        val aggregated = FaceMatch.aggregateEmbeddings(accumulatedEmbeddings)
        Prefs.setOwnerFacePrint(context, aggregated)
        accumulatedEmbeddings.clear()
        scanFrameCount = 0
        return true
    }

    fun reset() {
        scanFrameCount = 0
        accumulatedEmbeddings.clear()
        isProcessing.set(false)
    }

    private fun detectFaceBounds(bitmap: Bitmap): RectF? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        var totalX = 0L
        var totalY = 0L
        var count = 0

        val stepX = (width / 20).coerceAtLeast(1)
        val stepY = (height / 20).coerceAtLeast(1)

        for (x in 0 until width step stepX) {
            for (y in 0 until height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (r > 60 && g > 40 && b > 20 && r > g && r > b && Math.abs(r - g) > 15) {
                    totalX += x
                    totalY += y
                    count++
                }
            }
        }

        if (count < 10) {
            val cx = width / 2f
            val cy = height / 2f
            val size = Math.min(width, height) * 0.4f
            return RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        }

        val centerX = (totalX / count).toFloat()
        val centerY = (totalY / count).toFloat()
        val boxWidth = width * 0.45f
        val boxHeight = height * 0.55f

        val left = (centerX - boxWidth / 2f).coerceIn(0f, width.toFloat() - boxWidth)
        val top = (centerY - boxHeight / 2f).coerceIn(0f, height.toFloat() - boxHeight)

        return RectF(left, top, left + boxWidth, top + boxHeight)
    }

    private fun generateFaceEmbedding(bitmap: Bitmap, bounds: RectF): FloatArray {
        val embedding = FloatArray(128)
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)

        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth <= 0 || cropHeight <= 0) {
            return FaceMatch.normalize(embedding)
        }

        val gridCols = 8
        val gridRows = 16
        var featureIdx = 0

        val stepX = (cropWidth / gridCols).coerceAtLeast(1)
        val stepY = (cropHeight / gridRows).coerceAtLeast(1)

        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                if (featureIdx >= 128) break
                val px = left + (c * stepX).coerceAtMost(cropWidth - 1)
                val py = top + (r * stepY).coerceAtMost(cropHeight - 1)
                val pixel = bitmap.getPixel(px, py)

                val red = Color.red(pixel) / 255.0f
                val green = Color.green(pixel) / 255.0f
                val blue = Color.blue(pixel) / 255.0f
                val gray = 0.299f * red + 0.587f * green + 0.114f * blue

                embedding[featureIdx++] = gray
            }
        }

        return FaceMatch.normalize(embedding)
    }
}