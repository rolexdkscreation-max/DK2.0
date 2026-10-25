package com.dk.assistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Orb ke 3D aakaar - sab chhote dots se bane.
 * kind: 0 = halka dot, 1 = chamakta dot, 2 = aankh, 3 = honth, 4 = baal, 5 = chhupa (HIDDEN - sirf ginti poori karne ke liye)
 * v3.65: lum = roshni-chhaaya (0..1, chehre par; baaki aakaar = 1)
 */
class Cloud(val n: Int) {
    val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
    val kind = ByteArray(n)
    /** aankh/honth ka beech (blink / bolte waqt muh hilana) */
    val anc = FloatArray(n)
    val lower = BooleanArray(n)
    val lum = FloatArray(n) { 1f }
    var tilt = 0.41f          // X-axis jhukaav
    var spin = 1f             // ghoomne ki speed (0 = nahi)
    var sway = false          // chehra: ghoomta nahi, halka idhar-udhar dekhta hai
    var count = 0
    fun add(px: Float, py: Float, pz: Float, k: Int = 0, a: Float = 0f, low: Boolean = false, l: Float = 1f): Boolean {
        if (count >= n) return false
        x[count] = px; y[count] = py; z[count] = pz; kind[count] = k.toByte(); anc[count] = a; lower[count] = low; lum[count] = l
        count++; return true
    }
    /** kam points bane to bache hue pehle walon ke paas bhar do */
    fun fill(r: Random) {
        val made = count
        if (made == 0) { while (add(0f, 0f, 0f)) {}; return }
        while (count < n) {
            val j = r.nextInt(made)
            add(x[j] + (r.nextFloat() - .5f) * .01f, y[j] + (r.nextFloat() - .5f) * .01f, z[j], kind[j].toInt(), anc[j], lower[j], lum[j])
        }
    }
}

object Shapes {
    /** Settings me dikhne wale naam */
    val STYLES = listOf(
        "earth" to "पृथ्वी (सैटेलाइट के साथ)",
        "galaxy" to "आकाशगंगा (गैलेक्सी)",
        "atom" to "एटम",
        "torus" to "छल्ला (रिंग)",
        "face_male" to "पुरुष चेहरा",
        "face_female" to "महिला चेहरा",
        // v3.62: भविष्य जैसे (futuristic) 3D डॉट वाले आकार
        "geodesic" to "जियोडेसिक गोला (त्रिकोण जाली)",
        "knot" to "क्वांटम गाँठ (ट्रेफ़ॉइल)",
        "dyson" to "डायसन कवच (जाली + कोर)"
    )

    /** v3.65: मालिक के कहने पर पूरी तरह हटाए गए आकार - पहले चुने हों तो पृथ्वी */
    val REMOVED = setOf("dna", "tesseract", "wormhole", "sphere3d")
    /** सामान्य आकार इतने डॉट से (पहले जैसी रफ़्तार/रूप); चेहरे को ज़्यादा डॉट (HD) */
    const val PLAIN_DOTS = 1400
    const val HIDDEN = 5

    fun valid(style: String?): String = if (style != null && STYLES.any { it.first == style }) style else "earth"

    fun make(style: String, n: Int): Cloud {
        if (style == "face_male") return FaceHD.build(n, female = false)
        if (style == "face_female") return FaceHD.build(n, female = true)
        val m = minOf(n, PLAIN_DOTS)
        val base = when (valid(style)) {
            "galaxy" -> galaxy(m)
            "atom" -> atom(m)
            "torus" -> torus(m)
            "geodesic" -> geodesic(m)
            "knot" -> knot(m)
            "dyson" -> dyson(m)
            else -> earth(m)
        }
        return if (m == n) base else pad(base, n)
    }

    /** बाकी डॉट छिपे (HIDDEN) - असली डॉट की जगह पर, ताकि चेहरे से बदलते समय वहीं सिमट जाएँ */
    private fun pad(b: Cloud, n: Int): Cloud {
        val c = Cloud(n)
        for (i in 0 until b.count) c.add(b.x[i], b.y[i], b.z[i], b.kind[i].toInt(), b.anc[i], b.lower[i], b.lum[i])
        var j = 0
        while (c.count < n) { val i = j % b.count.coerceAtLeast(1); c.add(b.x[i], b.y[i], b.z[i], HIDDEN); j++ }
        c.tilt = b.tilt; c.spin = b.spin; c.sway = b.sway
        return c
    }

    // ------------------------------------------------------------ prithvi
    fun earth(n: Int): Cloud {
        val c = Cloud(n)
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val r = sqrt(1f - y * y)
            val th = golden * i
            val x = cos(th) * r; val z = sin(th) * r
            val v = sin(2.7f * x + 1.3f) * cos(2.2f * y - 0.4f) + 0.7f * sin(3.6f * z + 0.8f + 1.5f * y) +
                0.35f * sin(6.1f * x + 4.3f * z) + 0.25f * cos(7.3f * y + 2.1f * x)
            c.add(x, y, z, if (v > 0.1f && abs(y) < 0.93f) 1 else 0)
        }
        c.tilt = (23.4 * PI / 180).toFloat(); c.spin = 1f
        return c
    }

    // ------------------------------------------------------------ galaxy: 2 bhuja wala chakkar
    fun galaxy(n: Int): Cloud {
        val c = Cloud(n); val r = Random(7)
        val core = n / 5
        repeat(core) {
            val g = gauss(r) * 0.16f; val h = gauss(r) * 0.16f; val k = gauss(r) * 0.08f
            c.add(g, k, h, 1)
        }
        while (c.count < n) {
            val u = r.nextFloat()
            val rad = 0.12f + 0.95f * u.pow(0.8f)
            val arm = if (r.nextBoolean()) 0f else PI.toFloat()
            val spread = gauss(r) * 0.22f * (0.4f + rad)
            val th = arm + rad * 3.4f + spread
            val px = rad * cos(th); val pz = rad * sin(th)
            val py = gauss(r) * 0.035f * (1.2f - rad)
            c.add(px, py, pz, if (abs(spread) < 0.09f) 1 else 0)
        }
        c.tilt = 1.05f; c.spin = 0.7f
        return c
    }

    // ------------------------------------------------------------ atom: beech me nucleus, 3 chhalle
    val ATOM_RINGS = listOf(0f to 0f, 1.05f to 0.0f, -1.05f to 0.0f)   // (X par jhukaav, Y par ghumaav)
    fun ringPoint(i: Int, th: Float, rad: Float): Triple<Float, Float, Float> {
        val (incl, _) = ATOM_RINGS[i]
        val node = i * 2.094f
        var x = cos(th) * rad; var y = 0f; var z = sin(th) * rad
        val y1 = y * cos(incl) - z * sin(incl); val z1 = y * sin(incl) + z * cos(incl); y = y1; z = z1
        val x2 = x * cos(node) + z * sin(node); val z2 = -x * sin(node) + z * cos(node); x = x2; z = z2
        return Triple(x, y, z)
    }
    fun atom(n: Int): Cloud {
        val c = Cloud(n); val r = Random(11)
        val nuc = n / 4
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until nuc) {
            val y = 1f - 2f * (i + 0.5f) / nuc; val rr = sqrt(1f - y * y); val th = golden * i
            val s = 0.24f + 0.03f * sin(i * 1.7f)
            c.add(cos(th) * rr * s, y * s, sin(th) * rr * s, 1)
        }
        while (c.count < n) {
            val ring = r.nextInt(3)
            val (px, py, pz) = ringPoint(ring, r.nextFloat() * 2 * PI.toFloat(), 0.95f + gauss(r) * 0.012f)
            c.add(px, py, pz, 0)
        }
        c.tilt = 0.35f; c.spin = 0.5f
        return c
    }


    // ------------------------------------------------------------ torus (chhalla / donut)
    fun torus(n: Int): Cloud {
        val c = Cloud(n)
        val R = 0.72f; val rr = 0.28f
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val u = 2 * PI.toFloat() * i / n
            val v = golden * i * 7f
            val x = (R + rr * cos(v)) * cos(u); val z = (R + rr * cos(v)) * sin(u); val y = rr * sin(v)
            c.add(x, y, z, if (sin(6 * u) * cos(3 * v) > 0.35f) 1 else 0)
        }
        c.tilt = 1.0f; c.spin = 0.8f
        return c
    }


    // ============================================================ v3.62: futuristic 3D डॉट आकार
    /** रेखाओं (a->b) पर लंबाई के हिसाब से बराबर दूरी पर `count` डॉट. sphere = हर डॉट गोले की सतह पर (r) */
    private fun alongSegments(c: Cloud, segs: List<FloatArray>, count: Int, kind: Int, sphere: Float = 0f) {
        if (segs.isEmpty() || count <= 0) return
        val len = segs.map { s -> sqrt((s[3] - s[0]).pow(2) + (s[4] - s[1]).pow(2) + (s[5] - s[2]).pow(2)) }
        val total = len.sum().coerceAtLeast(1e-6f)
        var made = 0
        segs.forEachIndexed { i, sg ->
            val m = if (i == segs.lastIndex) count - made else (count * len[i] / total).toInt()
            for (k in 0 until m) {
                val u = (k + 0.5f) / m
                var x = sg[0] + (sg[3] - sg[0]) * u; var y = sg[1] + (sg[4] - sg[1]) * u; var z = sg[2] + (sg[5] - sg[2]) * u
                if (sphere > 0f) { val d = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f); x = x / d * sphere; y = y / d * sphere; z = z / d * sphere }
                c.add(x, y, z, kind)
            }
            made += m
        }
    }

    // ------------------------------------------------------------ जियोडेसिक गोला: icosahedron (2 बार बाँटा) की त्रिकोण जाली
    fun geodesic(n: Int): Cloud {
        val c = Cloud(n); val r = Random(31)
        val t = ((1 + sqrt(5.0)) / 2).toFloat()
        val v = mutableListOf(
            floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
            floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
            floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f))
        fun norm(p: FloatArray): FloatArray { val d = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); return floatArrayOf(p[0] / d, p[1] / d, p[2] / d) }
        for (i in v.indices) v[i] = norm(v[i])
        var faces = listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1))
        val mid = HashMap<Long, Int>()
        fun midpoint(a: Int, b: Int): Int {
            val key = if (a < b) a.toLong() * 100000 + b else b.toLong() * 100000 + a
            return mid.getOrPut(key) { val pa = v[a]; val pb = v[b]; v.add(norm(floatArrayOf((pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, (pa[2] + pb[2]) / 2))); v.size - 1 }
        }
        val nf = ArrayList<IntArray>()
        for (f in faces) {
            val a = midpoint(f[0], f[1]); val b = midpoint(f[1], f[2]); val d = midpoint(f[2], f[0])
            nf += intArrayOf(f[0], a, d); nf += intArrayOf(f[1], b, a); nf += intArrayOf(f[2], d, b); nf += intArrayOf(a, b, d)
        }
        faces = nf
        val edges = HashSet<Long>()
        val segs = ArrayList<FloatArray>()
        for (f in faces) for (k in 0 until 3) {
            val a = f[k]; val b = f[(k + 1) % 3]
            val key = if (a < b) a.toLong() * 100000 + b else b.toLong() * 100000 + a
            if (edges.add(key)) segs += floatArrayOf(v[a][0], v[a][1], v[a][2], v[b][0], v[b][1], v[b][2])
        }
        // चमकते जोड़ (vertex) - हर जोड़ पर 3 डॉट का छोटा गुच्छा
        for (p in v) repeat(3) { if (c.count < n / 4) c.add(p[0] + gauss(r) * 0.008f, p[1] + gauss(r) * 0.008f, p[2] + gauss(r) * 0.008f, 1) }
        val core = n / 10
        repeat(core) { val g = norm(floatArrayOf(gauss(r), gauss(r), gauss(r))); val rr = 0.18f + 0.06f * r.nextFloat(); c.add(g[0] * rr, g[1] * rr, g[2] * rr, 1) }
        alongSegments(c, segs, n - c.count, 0, sphere = 1f)
        c.fill(r)
        c.tilt = 0.32f; c.spin = 0.45f
        return c
    }


    // ------------------------------------------------------------ क्वांटम गाँठ: trefoil knot की नली
    fun knot(n: Int): Cloud {
        val c = Cloud(n); val r = Random(51)
        fun at(t: Float) = floatArrayOf((sin(t) + 2 * sin(2 * t)) / 3.1f, (cos(t) - 2 * cos(2 * t)) / 3.1f, -sin(3 * t) / 3.1f)
        val tube = 0.10f
        val spine = n / 6
        for (i in 0 until n) {
            val u = 2 * PI.toFloat() * i / n
            val p = at(u); val q = at(u + 0.01f)
            var tx = q[0] - p[0]; var ty = q[1] - p[1]; var tz = q[2] - p[2]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f); tx /= tl; ty /= tl; tz /= tl
            // नली के लिए दो लंब दिशाएँ
            var nx = -ty; var ny = tx; val nz = 0f
            val nl = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-6f); nx /= nl; ny /= nl
            val bx = ty * nz - tz * ny; val by = tz * nx - tx * nz; val bz = tx * ny - ty * nx
            if (i % (n / spine.coerceAtLeast(1)) == 0) { c.add(p[0], p[1], p[2], 1); continue }
            // नली = 7 समानांतर धागे, धीरे-धीरे लिपटते हुए (भविष्य की केबल जैसी)
            val strand = i % 7
            val v = 2 * PI.toFloat() * strand / 7 + u * 3f
            val cv = cos(v) * tube; val sv = sin(v) * tube
            c.add(p[0] + nx * cv + bx * sv, p[1] + ny * cv + by * sv, p[2] + nz * cv + bz * sv, if (strand == 0) 1 else 0)
        }
        c.fill(r)
        c.tilt = 0.2f; c.spin = 0.75f
        return c
    }


    // ------------------------------------------------------------ डायसन कवच: अक्षांश-देशांतर की जाली + चमकती कोर + बाहरी छल्ला
    fun dyson(n: Int): Cloud {
        val c = Cloud(n); val r = Random(71)
        val core = n / 7; val ring = n / 7
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until core) {
            val y = 1f - 2f * (i + 0.5f) / core; val rr = sqrt(1f - y * y); val th = golden * i
            val s = 0.30f
            c.add(cos(th) * rr * s, y * s, sin(th) * rr * s, 1)
        }
        val lats = 7; val lons = 12
        val grid = n - core - ring
        val latN = grid / 2 / lats; val lonN = grid / 2 / lons
        for (a in 1..lats) {
            val phi = PI.toFloat() * a / (lats + 1) - PI.toFloat() / 2
            val y = sin(phi) * 0.92f; val rr = cos(phi) * 0.92f
            for (j in 0 until latN) { val th = 2 * PI.toFloat() * j / latN; c.add(cos(th) * rr, y, sin(th) * rr, if (j % (latN / lons).coerceAtLeast(1) == 0) 1 else 0) }
        }
        for (b in 0 until lons) {
            val th = 2 * PI.toFloat() * b / lons
            for (j in 0 until lonN) {
                val phi = PI.toFloat() * (j + 0.5f) / lonN - PI.toFloat() / 2
                c.add(cos(phi) * cos(th) * 0.92f, sin(phi) * 0.92f, cos(phi) * sin(th) * 0.92f, 0)
            }
        }
        // टेढ़ा बाहरी छल्ला (ऊर्जा पट्टी)
        val inc = 0.5f
        while (c.count < n) {
            val th = r.nextFloat() * 2 * PI.toFloat(); val rad = 1.12f + gauss(r) * 0.01f
            val x = cos(th) * rad; val z0 = sin(th) * rad
            c.add(x, -z0 * sin(inc), z0 * cos(inc), if (r.nextFloat() < 0.35f) 1 else 0)
        }
        c.tilt = 0.38f; c.spin = 0.5f
        return c
    }

    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f); val v = r.nextFloat()
        return (sqrt(-2.0 * kotlin.math.ln(u.toDouble())) * cos(2 * PI * v)).toFloat()
    }
}
