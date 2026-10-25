package com.dk.assistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v3.65: बारीक (HD) डॉट वाला पुरुष / महिला चेहरा.
 *
 * असली इंसानी चेहरे का ढाँचा (FaceMeshData, MediaPipe) -> उस पर बराबर दूरी के डॉट, रोशनी-छाया (lum),
 * आँख/पुतली/पलक, भौंह, नाक, होंठ की रेखाएँ, सिर, बाल की लटें, गर्दन, कंधे.
 *  - पुरुष: चौड़ा जबड़ा, चौकोर ठोड़ी, उभरी भौंह की हड्डी, घनी भौंहें, छोटी तराशी दाढ़ी-मूँछ, छोटे बाल, कंठ, चौड़े कंधे.
 *  - महिला: पतला जबड़ा, भरे होंठ, बड़ी आँखें + पलकें, पतली भौंहें, उभरे गाल, साइड-माँग, कंधों तक लहराते बाल, झुमके.
 * kind: 0 त्वचा, 1 चमकती रेखा, 2 आँख (पलक झपके), 3 होंठ (बोलते समय हिलें), 4 बाल/दाढ़ी.
 * सब हिसाब बिना Android के (JVM test) - एक बार बनता है, हर frame नहीं.
 */
object FaceHD {
    private const val K = 1.2f / 17.7f
    private const val YC = -0.57f
    private const val ZC = -2.0f
    private const val YOFF = 0.22f
    private val HC = floatArrayOf(0f, 0.8f, -2.6f)
    private val SK = floatArrayOf(8.1f, 10.0f, 9.4f)
    private val LIGHT = run { val l = floatArrayOf(-0.45f, 0.55f, 0.75f); val d = len(l); floatArrayOf(l[0] / d, l[1] / d, l[2] / d) }

    private fun sm(t0: Float): Float { val t = t0.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun g2(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float) =
        exp(-(((x - cx) / rx).pow(2) + ((y - cy) / ry).pow(2)))
    private fun len(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f); val v = r.nextFloat()
        return (sqrt(-2.0 * kotlin.math.ln(u.toDouble())) * cos(2 * PI * v)).toFloat()
    }

    /** पुरुष/महिला के हिसाब से ढाँचे में बदलाव */
    private fun morph(src: FloatArray, female: Boolean): FloatArray {
        val v = src.copyOf()
        for (i in 0 until v.size / 3) {
            var x = v[i * 3]; var y = v[i * 3 + 1]; var z = v[i * 3 + 2]
            val ax = abs(x)
            if (female) {
                if (y < -1f) x *= 1f - 0.10f * sm((-1f - y) / 8f)                              // पतला जबड़ा
                if (y < -6.5f && ax < 4f) y += 0.35f * sm((-6.5f - y) / 3f) * (1f - ax / 4f)     // छोटी ठोड़ी
                var w = g2(x, y, 0f, -4.3f, 3.0f, 1.5f); y = -4.3f + (y + 4.3f) * (1f + 0.24f * w); z += 0.25f * w  // भरे होंठ
                for (ex in floatArrayOf(-3.4f, 3.4f)) {                                           // बड़ी आँखें
                    w = g2(x, y, ex, 2.55f, 1.9f, 1.1f); x = ex + (x - ex) * (1f + 0.07f * w); y = 2.55f + (y - 2.55f) * (1f + 0.10f * w)
                }
                w = g2(x, y, 0f, -0.6f, 1.6f, 2.6f); x *= 1f - 0.08f * w; z -= 0.25f * w * max(0f, z - 5f) / 2.5f   // छोटी नाक
                z += 0.35f * g2(ax, y, 4.6f, 0.4f, 1.6f, 1.4f)                                     // उभरे गाल
            } else {
                if (y < -1f) x *= 1f + 0.09f * sm((-1f - y) / 6f)                                 // चौड़ा जबड़ा
                if (y < -7f && ax < 4.5f) y -= 0.35f * sm((-7f - y) / 2.4f) * min(1f, ax / 2.2f + 0.3f)   // चौकोर ठोड़ी
                var w = g2(x, y, 0f, -4.3f, 3.0f, 1.4f); y = -4.3f + (y + 4.3f) * (1f - 0.12f * w) // पतले होंठ
                z += 0.45f * g2(ax, y, 2.6f, 4.4f, 2.6f, 0.8f)                                     // भौंह की हड्डी
                w = g2(x, y, 0f, -0.6f, 1.8f, 2.8f); x *= 1f + 0.10f * w; z += 0.2f * w * max(0f, z - 5f) / 2.5f   // बड़ी नाक
            }
            v[i * 3] = x; v[i * 3 + 1] = y; v[i * 3 + 2] = z
        }
        return v
    }

    fun build(n: Int, female: Boolean): Cloud {
        val c = Cloud(n)
        val r = Random(if (female) 21 else 13)
        val V = morph(FaceMeshData.verts, female)
        val T = FaceMeshData.tris
        val nt = T.size / 3
        fun vx(i: Int) = V[i * 3]
        fun vy(i: Int) = V[i * 3 + 1]
        fun vz(i: Int) = V[i * 3 + 2]

        fun lum(nx: Float, ny: Float, nz: Float): Float {
            val d = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            return 0.25f + 0.75f * max(0f, (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / d)
        }
        /** ढाँचे की इकाई (cm) -> orb की इकाई; nrm न दें तो सिर के बीच से बाहर की दिशा */
        fun put(x: Float, y: Float, z: Float, k: Int, ancY: Float = 0f, low: Boolean = false,
                nx: Float = x - HC[0], ny: Float = y - HC[1], nz: Float = z - HC[2]): Boolean =
            c.add(x * K, (y - YC) * K + YOFF, (z - ZC) * K, k, ancY, low, lum(nx, ny, nz))
        fun ancOf(y: Float) = (y - YC) * K + YOFF

        // ---- त्रिकोणों के क्षेत्रफल + normal
        val area = FloatArray(nt); val fn = FloatArray(nt * 3)
        var sumZ = 0f
        for (t in 0 until nt) {
            val a = T[t * 3]; val b = T[t * 3 + 1]; val d = T[t * 3 + 2]
            val ux = vx(b) - vx(a); val uy = vy(b) - vy(a); val uz = vz(b) - vz(a)
            val wx = vx(d) - vx(a); val wy = vy(d) - vy(a); val wz = vz(d) - vz(a)
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            val l = sqrt(cx * cx + cy * cy + cz * cz).coerceAtLeast(1e-9f)
            area[t] = l / 2; fn[t * 3] = cx / l; fn[t * 3 + 1] = cy / l; fn[t * 3 + 2] = cz / l; sumZ += cz / l
        }
        if (sumZ < 0) for (i in fn.indices) fn[i] = -fn[i]
        val cum = FloatArray(nt); var acc = 0f
        for (t in 0 until nt) { acc += area[t]; cum[t] = acc }
        val total = acc
        val tp = FloatArray(3)
        /** क्षेत्रफल के हिसाब से किसी त्रिकोण पर एक बिंदु (tp में) - त्रिकोण का नंबर लौटाता है */
        fun tri(): Int {
            val u = r.nextFloat() * total
            var lo = 0; var hi = nt - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < u) lo = m + 1 else hi = m }
            var a = r.nextFloat(); var b = r.nextFloat()
            if (a + b > 1f) { a = 1f - a; b = 1f - b }
            val i0 = T[lo * 3]; val i1 = T[lo * 3 + 1]; val i2 = T[lo * 3 + 2]
            tp[0] = vx(i0) + (vx(i1) - vx(i0)) * a + (vx(i2) - vx(i0)) * b
            tp[1] = vy(i0) + (vy(i1) - vy(i0)) * a + (vy(i2) - vy(i0)) * b
            tp[2] = vz(i0) + (vz(i1) - vz(i0)) * a + (vz(i2) - vz(i0)) * b
            return lo
        }

        fun q(f: Float) = (n * f).toInt()
        val qSkin = q(0.25f); val qOval = q(0.015f); val qEye = q(0.10f)
        val qBrow = q(if (female) 0.03f else 0.05f); val qNose = q(0.035f); val qLip = q(if (female) 0.055f else 0.03f)
        val qBeard = if (female) 0 else q(0.10f); val qEar = q(if (female) 0.004f else 0.015f)
        val qHead = q(0.06f); val qHair = q(if (female) 0.34f else 0.21f); val qNeck = q(0.05f)

        // ---- त्वचा: बराबर दूरी (आँख, होंठ, नाक के पास ज़्यादा घनी)
        run {
            val md = sqrt(total / qSkin.coerceAtLeast(1)) * 0.66f
            val grid = HashMap<Long, MutableList<FloatArray>>()
            fun key(ix: Int, iy: Int, iz: Int) = ((ix + 512).toLong() shl 40) or ((iy + 512).toLong() shl 20) or (iz + 512).toLong()
            fun dens(x: Float, y: Float) = 1f + 0.9f * g2(abs(x), y, 3.4f, 2.6f, 2.2f, 1.4f) + 0.8f * g2(x, y, 0f, -4.3f, 3f, 1.4f) +
                0.7f * g2(x, y, 0f, 0f, 1.7f, 3.5f)
            var made = 0; var tries = 0
            while (made < qSkin && tries < qSkin * 40) {
                tries++
                val t = tri(); val x = tp[0]; val y = tp[1]; val z = tp[2]
                val d = md / dens(x, y); val d2 = d * d
                val gx = floor(x / md).toInt(); val gy = floor(y / md).toInt(); val gz = floor(z / md).toInt()
                var ok = true
                loop@ for (i in -1..1) for (j in -1..1) for (k in -1..1) {
                    val l = grid[key(gx + i, gy + j, gz + k)] ?: continue
                    for (o in l) if ((o[0] - x).pow(2) + (o[1] - y).pow(2) + (o[2] - z).pow(2) < d2) { ok = false; break@loop }
                }
                if (!ok) continue
                grid.getOrPut(key(gx, gy, gz)) { ArrayList() }.add(floatArrayOf(x, y, z))
                put(x, y, z, 0, nx = fn[t * 3], ny = fn[t * 3 + 1], nz = fn[t * 3 + 2]); made++
            }
        }

        /** ढाँचे की रेखाओं (जोड़ी-जोड़ी बिंदु) पर बराबर फैले डॉट */
        fun line(edges: IntArray, count: Int, k: Int, jit: Float, off: Float, ancY: Float = 0f, mouthY: Float? = null, wid: Float = 0f) {
            val m = edges.size / 2
            if (m == 0 || count <= 0) return
            val L = FloatArray(m) { e ->
                val a = edges[e * 2]; val b = edges[e * 2 + 1]
                sqrt((vx(b) - vx(a)).pow(2) + (vy(b) - vy(a)).pow(2) + (vz(b) - vz(a)).pow(2))
            }
            val tot = L.sum().coerceAtLeast(1e-6f)
            for (ci in 0 until count) {
                var u = (ci + r.nextFloat()) / count * tot; var e = 0
                while (e < m - 1 && u > L[e]) { u -= L[e]; e++ }
                val f = (u / L[e].coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                val a = edges[e * 2]; val b = edges[e * 2 + 1]
                val x = vx(a) + (vx(b) - vx(a)) * f + gauss(r) * jit
                val y = vy(a) + (vy(b) - vy(a)) * f + gauss(r) * (jit + wid)
                val z = vz(a) + (vz(b) - vz(a)) * f + off
                put(x, y, z, k, ancY, low = mouthY != null && y < mouthY, nx = 0f, ny = 0f, nz = 1f)
            }
        }

        // ---- आँखें: किनारा, पुतली का छल्ला + भराई, चमक, पलक की तह, (महिला) पलकें
        for ((side, key) in listOf(1f to FaceMeshData.LEFT_EYE, -1f to FaceMeshData.RIGHT_EYE)) {
            val ed = FaceMeshData.edges(key)
            val ids = ed.toSet()
            val ecx = ids.map { vx(it) }.average().toFloat(); val ecy = ids.map { vy(it) }.average().toFloat()
            val ecz = ids.map { vz(it) }.average().toFloat()
            val anc = ancOf(ecy)
            line(ed, (qEye * 0.30f).toInt(), 2, 0.015f, 0.1f, anc)
            val ring = (qEye * 0.12f).toInt().coerceAtLeast(1)
            for (j in 0 until ring) {
                val th = 2 * PI.toFloat() * j / ring
                put(ecx + 0.62f * cos(th), ecy + 0.62f * sin(th) * 0.95f, ecz + 0.35f, 2, anc, nx = 0f, ny = 0f, nz = 1f)
            }
            repeat((qEye * 0.04f).toInt()) {
                val th = r.nextFloat() * 2 * PI.toFloat(); val rr = 0.42f * sqrt(r.nextFloat()) + 0.12f
                put(ecx + rr * cos(th), ecy + rr * sin(th), ecz + 0.33f, 2, anc, nx = 0f, ny = 0f, nz = 1f)
            }
            for (j in 0 until 3) put(ecx + 0.24f + 0.05f * j, ecy + 0.26f, ecz + 0.5f, 1, anc, nx = 0f, ny = 0f, nz = 1f)
            val upper = ArrayList<Int>()
            for (e in 0 until ed.size / 2) if (vy(ed[e * 2]) > ecy && vy(ed[e * 2 + 1]) > ecy) upper += e
            if (upper.isEmpty()) continue
            for (ci in 0 until (qEye * 0.06f).toInt()) {
                val e = upper[ci % upper.size]; val a = ed[e * 2]; val b = ed[e * 2 + 1]; val u = r.nextFloat()
                val x = vx(a) + (vx(b) - vx(a)) * u; val y = vy(a) + (vy(b) - vy(a)) * u; val z = vz(a) + (vz(b) - vz(a)) * u
                put(x, y + 0.55f + 0.12f * abs(x - ecx), z + 0.05f, 1, nx = 0f, ny = 0.3f, nz = 1f)
            }
            if (female) for (ci in 0 until (qEye * 0.10f).toInt()) {
                val e = upper[ci % upper.size]; val a = ed[e * 2]; val b = ed[e * 2 + 1]; val u = r.nextFloat()
                val x = vx(a) + (vx(b) - vx(a)) * u; val y = vy(a) + (vy(b) - vy(a)) * u; val z = vz(a) + (vz(b) - vz(a)) * u
                val w = sm(((x - ecx) * side + 1.6f) / 3.2f)
                val l = 0.25f + 0.5f * r.nextFloat() * (0.5f + 0.5f * w)
                put(x + 0.4f * side * l * w, y + l, z + 0.25f, 2, anc, nx = 0f, ny = 0f, nz = 1f)
            }
        }
        // ---- भौंहें
        for (key in listOf(FaceMeshData.LEFT_BROW, FaceMeshData.RIGHT_BROW)) {
            if (female) line(FaceMeshData.edges(key), qBrow / 2, 1, 0.03f, 0.12f, wid = 0.03f)
            else line(FaceMeshData.edges(key), qBrow / 2, 1, 0.09f, 0.2f, wid = 0.17f)
        }
        // ---- नाक + नथुने
        line(FaceMeshData.edges(FaceMeshData.NOSE), (qNose * 0.7f).toInt(), 1, 0.02f, 0.08f)
        for (s in floatArrayOf(-1f, 1f)) {
            val m = (qNose * 0.15f).toInt().coerceAtLeast(1)
            for (j in 0 until m) { val th = PI.toFloat() * j / m; put(s * (0.95f + 0.35f * cos(th)), -1.95f - 0.25f * sin(th), 5.6f, 1, nx = 0f, ny = -0.3f, nz = 1f) }
        }
        // ---- होंठ (नीचे वाला होंठ बोलते समय नीचे हिले)
        val mouthY = -4.3f
        line(FaceMeshData.edges(FaceMeshData.LIPS), qLip, 3, 0.02f, 0.1f, ancOf(mouthY), mouthY)
        // ---- चेहरे की हल्की रेखा
        line(FaceMeshData.edges(FaceMeshData.OVAL), qOval, 0, 0.02f, 0f)

        // ---- दाढ़ी-मूँछ (पुरुष): छोटी, घनी, तराशी हुई
        if (!female) {
            var made = 0; var tries = 0
            while (made < qBeard && tries < qBeard * 80) {
                tries++
                val t = tri(); val x = tp[0]; val y = tp[1]; val z = tp[2]; val ax = abs(x)
                if (g2(x, y, 0f, -4.3f, 2.7f, 0.85f) > 0.35f) continue
                val must = y > -3.35f && y < -2.45f && ax < 2.6f
                val cheek = 1.0f - 2.4f * sm((ax - 2.0f) / 3.5f)
                val body = (ax >= 2.0f && y < cheek && y < -0.8f) || (y < -4.9f && ax < 2.0f) || (ax > 5.3f && y < 1.6f)
                if (!(body || must)) continue
                val nx = fn[t * 3]; val ny = fn[t * 3 + 1]; val nz = fn[t * 3 + 2]
                put(x + nx * 0.22f + gauss(r) * 0.04f, y + ny * 0.22f + gauss(r) * 0.04f, z + nz * 0.22f, 4, nx = nx, ny = ny, nz = nz); made++
            }
        }
        // ---- कान
        for (s in floatArrayOf(-1f, 1f)) {
            val m = qEar / 2
            for (j in 0 until m) {
                val th = -0.5f + j.toFloat() / m * 4.3f; val rr = if (j % 3 != 0) 1f else 0.6f
                put(s * (7.65f + 0.25f * rr), 0.6f + 1.9f * sin(th) * rr, -3.4f + 1.3f * cos(th) * rr, 0, nx = s, ny = 0f, nz = 0.3f)
            }
        }

        val u = FloatArray(3)
        /** सिर (अंडाकार खोपड़ी) पर एक बिंदु; u = दिशा */
        fun onSkull(R: Float): FloatArray {
            u[0] = gauss(r); u[1] = gauss(r); u[2] = gauss(r)
            val l = len(u).coerceAtLeast(1e-6f); u[0] /= l; u[1] /= l; u[2] /= l
            return floatArrayOf(HC[0] + u[0] * SK[0] * R, HC[1] + u[1] * SK[1] * R, HC[2] + u[2] * SK[2] * R)
        }
        // ---- सिर का बाकी हिस्सा (चेहरा घूमे तो सिर भरा दिखे)
        run {
            var made = 0; var tries = 0
            while (made < qHead && tries < qHead * 50) {
                tries++
                val p = onSkull(1f)
                if (u[2] > -0.05f && abs(p[0]) < 7.4f) continue
                if (p[1] < -5.5f) continue
                put(p[0], p[1], p[2], 0, nx = u[0], ny = u[1], nz = u[2]); made++
            }
        }
        // ---- बाल: छोटी-छोटी लटें (हर लट 7 डॉट)
        fun scalp(p: FloatArray): Boolean {
            val x = p[0]; val y = p[1]; val front = u[2] > 0.25f
            if (female) {
                val hl = 7.3f - 1.0f * sm((abs(x - 0.8f) - 0.5f) / 5f)
                if (front && abs(x) < 6.6f && y < hl) return false
                if (u[1] < -0.25f && u[2] > -0.5f) return false
            } else {
                val hl = 7.5f - 0.9f * sm((abs(x) - 2f) / 4f)
                if (front && abs(x) < 6.8f && y < hl) return false
                if (abs(x) >= 6.2f && u[2] > -0.35f && y < 3.2f) return false
                if (u[1] < -0.22f) return false
            }
            return true
        }
        val shell = (qHair * (if (female) 0.42f else 1f)).toInt()
        run {
            var made = 0; var guard = 0
            while (made < shell && guard < shell * 60) {
                guard++
                val p = onSkull(1.04f)
                if (!scalp(p)) continue
                val d = if (female) floatArrayOf(sign(p[0] - 0.8f) * 0.55f, -1f, -0.1f)
                    else if (u[2] > -0.2f) floatArrayOf(sign(p[0]) * 0.12f, 0.25f, -1f) else floatArrayOf(0f, -1f, -0.2f)
                val dot = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
                d[0] -= u[0] * dot; d[1] -= u[1] * dot; d[2] -= u[2] * dot
                val dl = len(d).coerceAtLeast(1e-6f)
                val L = (if (female) 1.2f else 0.55f) * (0.7f + 0.6f * r.nextFloat())
                for (k in 0 until 7) {
                    if (made >= shell) break
                    val f = L * k / 6f
                    put(p[0] + d[0] / dl * f + u[0] * 0.05f * k, p[1] + d[1] / dl * f + u[1] * 0.05f * k, p[2] + d[2] / dl * f + u[2] * 0.05f * k,
                        4, nx = u[0], ny = u[1], nz = u[2]); made++
                }
            }
        }
        if (female) {
            // कंधों तक लहराते लंबे बाल
            val per = 22; val ns = (qHair * 0.46f).toInt() / per
            for (s in 0 until ns) {
                val side = if (s % 2 == 1) 1f else -1f
                val th = side * (1.05f + (PI.toFloat() - 1.05f) * r.nextFloat().pow(0.7f))
                val ph = r.nextFloat() * 6f; val r0 = 8.7f + 0.4f * r.nextFloat(); val ln = 0.75f + 0.25f * r.nextFloat()
                for (k in 0 until per) {
                    val f = r.nextFloat() * ln; val y = 6.5f - 21.5f * f
                    val rad = r0 + 3.6f * sm((2.0f - y) / 12f)
                    val x = rad * sin(th) + 0.5f * sin(y * 0.45f + ph)
                    var z = HC[2] + rad * cos(th) * 0.9f
                    if (y < -11f) z += 0.25f * (-11f - y) * cos(th)
                    put(x, y, z, 4, nx = sin(th), ny = 0.15f, nz = cos(th))
                }
            }
            // माथे पर साइड-स्वीप लटें
            val per2 = 8; val sw = (qHair - shell - ns * per) / per2
            for (s in 0 until sw) {
                val f0 = r.nextFloat() * 0.85f; val off = (r.nextFloat() - 0.5f) * 0.9f
                for (k in 0 until per2) {
                    val f = f0 + k * 0.018f
                    val x = 0.8f + (-7.2f - 0.8f) * f; val y = 7.6f - 2.9f * f.pow(1.5f) + off; val z = 4.8f - 4.6f * f * f
                    put(x, y, z + 0.5f, 4, nx = x * 0.1f, ny = 0.6f, nz = 1f)
                }
            }
            // झुमके
            for (s in floatArrayOf(-1f, 1f)) for (j in 0 until 12) {
                val th = 2 * PI.toFloat() * j / 12
                put(s * 7.95f, -1.6f + 0.5f * sin(th), -3.0f + 0.5f * cos(th), 1, nx = s, ny = 0f, nz = 0.6f)
            }
        }
        // ---- गर्दन (+ पुरुष का कंठ)
        val nr = if (female) 4.3f else 5.7f
        repeat(qNeck) {
            val th = (r.nextFloat() * 2 - 1) * PI.toFloat() * 0.85f; val y = -6.0f - 8.0f * r.nextFloat()
            put(nr * sin(th), y, -2.6f + nr * cos(th) * 0.9f, 0, nx = sin(th), ny = 0f, nz = cos(th))
        }
        if (!female) repeat(14) { put(gauss(r) * 0.22f, -10.0f + gauss(r) * 0.35f, -2.6f + nr * 0.9f + 0.3f, 1, nx = 0f, ny = 0f, nz = 1f) }
        // ---- कंधे: साफ़ रेखाएँ (ऊपरी किनारा + हँसली)
        val w = if (female) 12.5f else 15.5f
        var j = 0
        while (c.count < n) {
            val f = r.nextFloat()
            if (j % 3 < 2) {
                val x = (f * 2 - 1) * w; val ax = abs(x)
                val y = if (ax < nr * 1.6f) -14.0f - 0.9f * (ax / nr) else -15.2f - 3.2f * ((ax - nr * 1.6f) / (w - nr * 1.6f)).pow(1.6f)
                put(x, y, -2.4f + 1.8f * sqrt(max(0f, 1f - (x / w).pow(2))), if (r.nextFloat() < 0.25f) 1 else 0,
                    nx = x / w * 0.5f, ny = 1f, nz = 0.5f)
            } else {
                val s = if (f < 0.5f) 1f else -1f; val uu = (f * 2) % 1f
                put(s * (1.2f + uu * (w * 0.55f)), -15.6f + 0.9f * uu - 0.6f * uu * uu, 2.2f - 1.2f * uu, 1, nx = 0f, ny = 0.4f, nz = 1f)
            }
            j++
        }
        c.tilt = 0f; c.spin = 0f; c.sway = true
        return c
    }
}
