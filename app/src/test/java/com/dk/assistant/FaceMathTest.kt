package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/** v3.91: असली चेहरा पहचान का गणित - अंक OpenCV (FaceDetectorYN / FaceRecognizerSF) से मिलाकर लिए */
class FaceMathTest {
    @Test fun similarityMatchesOpenCvReference() {
        // ओबामा की फ़ोटो के 5 बिंदु (YuNet) -> Python/numpy lstsq का जवाब
        val pts = floatArrayOf(221.38f, 109.31f, 284.23f, 107.684f, 252.73f, 140.998f, 224.239f, 168.257f, 281.514f, 167.411f)
        val m = FaceMath.similarity(pts)
        val want = floatArrayOf(0.60985f, -0.00276f, -97.77328f, 0.00276f, 0.60985f, -13.40338f)
        for (i in want.indices) assertEquals("m[$i]", want[i], m[i], 0.002f)
        assertEquals(1f, m[8], 0f)
    }

    @Test fun similarityIdentity() {
        val m = FaceMath.similarity(FaceMath.REF)
        assertEquals(1f, m[0], 1e-4f); assertEquals(0f, m[1], 1e-4f); assertEquals(0f, m[2], 1e-3f); assertEquals(0f, m[5], 1e-3f)
    }

    @Test fun decodePicksBestFaceInFrameCoords() {
        val out = HashMap<Int, Array<FloatArray>>()
        for (s in FaceMath.STRIDES) { val n = (640 / s) * (640 / s); out[s] = arrayOf(FloatArray(n), FloatArray(n), FloatArray(n * 4), FloatArray(n * 10)) }
        // stride 16, पंक्ति 10, कॉलम 12: score sqrt(0.9*1)=0.949
        val s = 16; val i = 10 * 40 + 12; val o = out[s]!!
        o[0][i] = 0.9f; o[1][i] = 1f
        o[2][i * 4] = 0.5f; o[2][i * 4 + 1] = 0.5f; o[2][i * 4 + 2] = ln(4f); o[2][i * 4 + 3] = ln(5f)
        for (k in 0 until 5) { o[3][i * 10 + 2 * k] = 0.1f * k; o[3][i * 10 + 2 * k + 1] = 0.2f }
        // कमज़ोर वाला (0.5) - छूटे
        out[8]!![0][5] = 0.25f; out[8]!![1][5] = 1f
        val d = FaceMath.decode(out, 0.5f)!!
        assertEquals(0.9487f, d.score, 1e-3f)
        val cx = (12 + 0.5f) * 16; val cy = (10 + 0.5f) * 16
        assertEquals((cx - 32f) / 0.5f, d.x, 1e-2f); assertEquals((cy - 40f) / 0.5f, d.y, 1e-2f)
        assertEquals(128f, d.w, 1e-2f); assertEquals(160f, d.h, 1e-2f)
        assertEquals(12 * 16 / 0.5f, d.kps[0], 1e-2f); assertEquals((10.2f * 16) / 0.5f, d.kps[1], 1e-2f)
        assertNull(FaceMath.decode(out, 0.5f, minScore = 0.96f))
    }

    @Test fun inputsChannelOrder() {
        val px = IntArray(112 * 112) { 0xFF102030.toInt() }   // R=0x10 G=0x20 B=0x30
        val f = FaceMath.sfaceInput(px); val n = 112 * 112
        assertEquals(16f, f[0], 0f); assertEquals(32f, f[n], 0f); assertEquals(48f, f[2 * n], 0f)   // RGB
        val y = FaceMath.yunetInput(IntArray(640 * 640) { 0xFF102030.toInt() }); val m = 640 * 640
        assertEquals(48f, y[0], 0f); assertEquals(32f, y[m], 0f); assertEquals(16f, y[2 * m], 0f)  // BGR
        assertEquals(1f, FaceMath.detScale(1280, 640) * 2, 1e-6f)
    }

    @Test fun enrollAndDecide() {
        val good = FaceMath.Det(0.93f, 0f, 0f, 300f, 360f, FloatArray(10))
        assertNull(FaceMath.enrollProblem(good, 720))
        assertNotNull(FaceMath.enrollProblem(null, 720))
        assertNotNull(FaceMath.enrollProblem(FaceMath.Det(0.82f, 0f, 0f, 300f, 360f, FloatArray(10)), 720))
        assertNotNull(FaceMath.enrollProblem(FaceMath.Det(0.95f, 0f, 0f, 90f, 100f, FloatArray(10)), 720))
        // असली फ़ोटो पर: वही आदमी 0.77, दूसरे <= 0.30
        assertEquals(MatchResult.ACCESS_GRANTED, FaceMath.decide(0.77f))
        assertEquals(MatchResult.ACCESS_DENIED, FaceMath.decide(0.30f))
        assertEquals(MatchResult.ACCESS_DENIED, FaceMath.decide(0.50f, strict = true))
        assertTrue(FaceMath.MATCH > 0.363f)
    }

    @Test fun boxToScreen() {
        // 720x960 फ़्रेम, 1080x1440 स्क्रीन = 1.5 गुना, कोई कटाई नहीं
        val v = FaceMath.toView(100f, 200f, 300f, 400f, 720, 960, 1080, 1440, mirror = false)
        assertEquals(150f, v[0], 1e-3f); assertEquals(300f, v[1], 1e-3f); assertEquals(600f, v[2], 1e-3f); assertEquals(900f, v[3], 1e-3f)
        val m = FaceMath.toView(100f, 200f, 300f, 400f, 720, 960, 1080, 1440, mirror = true)
        assertEquals(1080f - 600f, m[0], 1e-3f); assertEquals(1080f - 150f, m[2], 1e-3f)
        // चौड़ी स्क्रीन: ऊपर-नीचे कटता है
        val c = FaceMath.toView(0f, 0f, 720f, 960f, 720, 960, 1440, 1080, mirror = false)
        assertEquals(0f, c[0], 1e-3f); assertEquals(1440f, c[2], 1e-3f); assertTrue(c[1] < 0f)
    }
}
