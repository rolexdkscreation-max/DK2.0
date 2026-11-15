package com.dk.assistant

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 15 नवंबर 2026 (मालिक: "फ़्यूचरिस्टिक तरीके से स्कैन"): चेहरे पर 3D जाल (wireframe) - MediaPipe का canonical face mesh
 * ([FaceMeshData], 468 बिंदु) YuNet के 5 बिंदुओं पर बिठाया जाता है:
 *  1. सिर के घुमाव (yaw) और झुकाव (pitch) के कई अंदाज़े आज़माओ, हर एक पर 3D ढाँचे को घुमाओ,
 *  2. उसके 5 बिंदु (दोनों आँख, नाक, होंठ के दोनों कोने) -> स्क्रीन के 5 बिंदु पर सबसे अच्छा 2D affine (least squares),
 *  3. जिसकी गलती सबसे कम वही; पीछे की तरफ़ वाले त्रिकोण छिपाओ (backface) - सिर घूमे तो जाल भी घूमता दिखता है.
 * Pure (JVM test). सिर्फ़ दिखावे के लिए - पहचान / असली-नकली का फ़ैसला इससे नहीं होता.
 */
object HoloMesh {
    /** YuNet क्रम: आँख1 (इंसान की दाईं), आँख2, नाक, होंठ-कोना1, होंठ-कोना2 -> mesh के बिंदु (औसत) */
    private val ANCHORS = arrayOf(intArrayOf(33, 133), intArrayOf(362, 263), intArrayOf(4), intArrayOf(61), intArrayOf(291))
    private val YAWS = (-50..50 step 5).map { Math.toRadians(it.toDouble()).toFloat() }
    private val PITCHES = listOf(-20, -10, 0, 10, 20).map { Math.toRadians(it.toDouble()).toFloat() }

    class Fit(val yaw: Float, val pitch: Float, val m: FloatArray, val err: Float)

    private val anchor3: Array<FloatArray> by lazy {
        val v = FaceMeshData.verts
        Array(ANCHORS.size) { i ->
            val ids = ANCHORS[i]; val p = FloatArray(3)
            ids.forEach { id -> for (k in 0..2) p[k] += v[id * 3 + k] / ids.size }
            p
        }
    }

    /** त्रिकोणों की घुमावट का चिह्न (+1/-1) - सामने वाले त्रिकोण z की ओर दिखें */
    private val winding: Float by lazy {
        val v = FaceMeshData.verts; val t = FaceMeshData.tris; var s = 0.0
        var i = 0
        while (i + 2 < t.size) {
            val a = t[i]; val b = t[i + 1]; val c = t[i + 2]; i += 3
            if ((v[a * 3 + 2] + v[b * 3 + 2] + v[c * 3 + 2]) / 3f < 4f) continue
            s += nz(v[a * 3], v[a * 3 + 1], v[b * 3], v[b * 3 + 1], v[c * 3], v[c * 3 + 1])
        }
        if (s >= 0) 1f else -1f
    }

    private fun nz(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    /** 3D बिंदु को yaw (y-अक्ष) फिर pitch (x-अक्ष) से घुमाओ -> [x, y, z] */
    fun rotate(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, out: FloatArray) {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val x1 = x * cy + z * sy; val z1 = -x * sy + z * cy
        out[0] = x1; out[1] = y * cp - z1 * sp; out[2] = y * sp + z1 * cp
    }

    /** src (x,y) -> dst (x,y) सबसे अच्छा affine [a,b,c,d,e,f]: X = a x + b y + c, Y = d x + e y + f. null = बिंदु बेतुके */
    fun affine(src: FloatArray, dst: FloatArray): FloatArray? {
        val n = src.size / 2; if (n < 3 || dst.size < n * 2) return null
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var bx0 = 0.0; var bx1 = 0.0; var bx2 = 0.0; var by0 = 0.0; var by1 = 0.0; var by2 = 0.0
        for (i in 0 until n) {
            val x = src[i * 2].toDouble(); val y = src[i * 2 + 1].toDouble(); val u = dst[i * 2].toDouble(); val w = dst[i * 2 + 1].toDouble()
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y
            bx0 += x * u; bx1 += y * u; bx2 += u; by0 += x * w; by1 += y * w; by2 += w
        }
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, n.toDouble()))
        val a = solve3(m, doubleArrayOf(bx0, bx1, bx2)) ?: return null
        val b = solve3(m, doubleArrayOf(by0, by1, by2)) ?: return null
        return floatArrayOf(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), b[0].toFloat(), b[1].toFloat(), b[2].toFloat())
    }

    private fun solve3(m: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m); if (abs(d) < 1e-9) return null
        return DoubleArray(3) { col -> det(Array(3) { row -> DoubleArray(3) { k -> if (k == col) r[row] else m[row][k] } }) / d }
    }

    /** स्क्रीन के 5 बिंदु ([kps], 10 अंक) पर सबसे अच्छा बैठने वाला घुमाव + affine */
    fun fit(kps: FloatArray): Fit? {
        if (kps.size < 10) return null
        val tmp = FloatArray(3); val src = FloatArray(10)
        var best: Fit? = null
        for (yw in YAWS) for (pt in PITCHES) {
            for (i in 0 until 5) { val p = anchor3[i]; rotate(p[0], p[1], p[2], yw, pt, tmp); src[i * 2] = tmp[0]; src[i * 2 + 1] = tmp[1] }
            val m = affine(src, kps) ?: continue
            var e = 0f
            for (i in 0 until 5) {
                val x = m[0] * src[i * 2] + m[1] * src[i * 2 + 1] + m[2] - kps[i * 2]
                val y = m[3] * src[i * 2] + m[4] * src[i * 2 + 1] + m[5] - kps[i * 2 + 1]
                e += x * x + y * y
            }
            if (best == null || e < best.err) best = Fit(yw, pt, m, e)
        }
        return best
    }

    /**
     * जाल की रेखाएँ (canvas.drawLines के लिए [x1,y1,x2,y2,...]) - सिर्फ़ सामने दिखने वाले त्रिकोण, हर किनारा एक बार.
     * ख़ाली = बिंदु बेतुके.
     */
    fun lines(kps: FloatArray): FloatArray {
        val f = fit(kps) ?: return FloatArray(0)
        val v = FaceMeshData.verts; val t = FaceMeshData.tris; val n = v.size / 3
        val p2 = FloatArray(n * 2); val zr = FloatArray(n * 2); val tmp = FloatArray(3); val m = f.m
        for (i in 0 until n) {
            rotate(v[i * 3], v[i * 3 + 1], v[i * 3 + 2], f.yaw, f.pitch, tmp)
            zr[i * 2] = tmp[0]; zr[i * 2 + 1] = tmp[1]
            p2[i * 2] = m[0] * tmp[0] + m[1] * tmp[1] + m[2]; p2[i * 2 + 1] = m[3] * tmp[0] + m[4] * tmp[1] + m[5]
        }
        val seen = HashSet<Int>(); val out = ArrayList<Float>(t.size * 2)
        var i = 0
        while (i + 2 < t.size) {
            val a = t[i]; val b = t[i + 1]; val c = t[i + 2]; i += 3
            if (winding * nz(zr[a * 2], zr[a * 2 + 1], zr[b * 2], zr[b * 2 + 1], zr[c * 2], zr[c * 2 + 1]) <= 0f) continue
            for ((p, q) in arrayOf(a to b, b to c, c to a)) {
                val k = minOf(p, q) * 1000 + maxOf(p, q)
                if (!seen.add(k)) continue
                out.add(p2[p * 2]); out.add(p2[p * 2 + 1]); out.add(p2[q * 2]); out.add(p2[q * 2 + 1])
            }
        }
        return out.toFloatArray()
    }
}
