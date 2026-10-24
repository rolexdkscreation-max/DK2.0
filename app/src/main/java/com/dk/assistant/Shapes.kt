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
 * kind: 0 = halka dot, 1 = chamakta dot, 2 = aankh, 3 = honth, 4 = baal
 */
class Cloud(val n: Int) {
    val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
    val kind = ByteArray(n)
    /** aankh/honth ka beech (blink / bolte waqt muh hilana) */
    val anc = FloatArray(n)
    val lower = BooleanArray(n)
    var tilt = 0.41f          // X-axis jhukaav
    var spin = 1f             // ghoomne ki speed (0 = nahi)
    var sway = false          // chehra: ghoomta nahi, halka idhar-udhar dekhta hai
    var count = 0
    fun add(px: Float, py: Float, pz: Float, k: Int = 0, a: Float = 0f, low: Boolean = false): Boolean {
        if (count >= n) return false
        x[count] = px; y[count] = py; z[count] = pz; kind[count] = k.toByte(); anc[count] = a; lower[count] = low
        count++; return true
    }
    /** kam points bane to bache hue pehle walon ke paas bhar do */
    fun fill(r: Random) {
        val made = count
        if (made == 0) { while (add(0f, 0f, 0f)) {}; return }
        while (count < n) {
            val j = r.nextInt(made)
            add(x[j] + (r.nextFloat() - .5f) * .01f, y[j] + (r.nextFloat() - .5f) * .01f, z[j], kind[j].toInt(), anc[j], lower[j])
        }
    }
}

object Shapes {
    /** Settings me dikhne wale naam */
    val STYLES = listOf(
        "earth" to "पृथ्वी (सैटेलाइट के साथ)",
        "galaxy" to "आकाशगंगा (गैलेक्सी)",
        "atom" to "एटम",
        "dna" to "DNA",
        "torus" to "छल्ला (रिंग)",
        "face_male" to "पुरुष चेहरा",
        "face_female" to "महिला चेहरा",
        "sphere3d" to "3D रोशनी का गोला (Android 13+)"
    )

    fun make(style: String, n: Int): Cloud = when (style) {
        "galaxy" -> galaxy(n)
        "atom" -> atom(n)
        "dna" -> dna(n)
        "torus" -> torus(n)
        "face_male" -> face(n, female = false)
        "face_female" -> face(n, female = true)
        else -> earth(n)
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

    // ------------------------------------------------------------ DNA: do lipte dhaage + seedhi
    fun dna(n: Int): Cloud {
        val c = Cloud(n); val r = Random(5)
        val strands = (n * 0.62f).toInt()
        for (i in 0 until strands) {
            val y = -1.05f + 2.1f * r.nextFloat()
            val s = i % 2
            val th = y * PI.toFloat() * 1.6f + s * PI.toFloat()
            val rad = 0.42f + gauss(r) * 0.02f
            c.add(cos(th) * rad, y, sin(th) * rad, if (s == 0) 1 else 4)
        }
        val rungs = 22
        while (c.count < n) {
            val k = r.nextInt(rungs)
            val y = -1.0f + 2.0f * k / (rungs - 1)
            val th = y * PI.toFloat() * 1.6f
            val f = r.nextFloat() * 2 - 1
            c.add(cos(th) * 0.42f * f, y + gauss(r) * 0.006f, sin(th) * 0.42f * f, 0)
        }
        c.tilt = 0.25f; c.spin = 0.9f
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

    // ------------------------------------------------------------ chehra (purush / mahila)
    fun face(n: Int, female: Boolean): Cloud {
        val c = Cloud(n); val r = Random(if (female) 21 else 13)
        val a = if (female) 0.50f else 0.55f        // chaudai
        val b = 0.72f                                 // lambai
        val cz = if (female) 0.58f else 0.61f        // gehraai
        val shiftY = 0.24f; val scale = 0.84f

        fun put(x: Float, y: Float, z: Float, k: Int, anc: Float = 0f, low: Boolean = false) =
            c.add(x * scale, (y + shiftY) * scale, z * scale, k, (anc + shiftY) * scale, low)

        /** jabde ki taraf patla chehra */
        fun jaw(y: Float): Float = if (y < -0.08f) 1f - (if (female) 0.42f else 0.30f) * ((-0.08f - y) / (b - 0.08f)).coerceIn(0f, 1f) else 1f
        fun surfZ(x: Float, y: Float): Float {
            val k = jaw(y)
            val q = 1f - (x / (a * k)).pow(2) - (y / b).pow(2)
            return cz * (if (k < 1f) (1f - (1f - k) * 0.4f) else 1f) * sqrt(max(0f, q))
        }

        val eyeY = 0.07f; val eyeX = if (female) 0.18f else 0.19f
        val eyeRx = 0.085f; val eyeRy = if (female) 0.045f else 0.036f
        val mouthY = -0.36f; val mouthW = if (female) 0.14f else 0.16f
        fun inEye(x: Float, y: Float) = ((abs(x) - eyeX) / (eyeRx * 1.25f)).pow(2) + ((y - eyeY) / (eyeRy * 1.6f)).pow(2) < 1f
        fun inMouth(x: Float, y: Float) = (x / (mouthW * 1.2f)).pow(2) + ((y - mouthY) / 0.06f).pow(2) < 1f

        val budget = mapOf(
            "skin" to if (female) 0.36f else 0.44f, "eyes" to 0.09f, "brows" to 0.04f, "nose" to 0.04f,
            "mouth" to if (female) 0.07f else 0.06f, "hair" to if (female) 0.30f else 0.18f
        )
        fun quota(k: String) = (n * budget.getValue(k)).toInt()

        // ---- twacha (sir ki satah)
        var made = 0
        while (made < quota("skin")) {
            val ux = gauss(r); val uy = gauss(r); val uz = gauss(r)
            val l = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-4f)
            val dy = uy / l
            var x = ux / l * a; val y = dy * b; var z = uz / l * cz
            val k = jaw(y); x *= k; z *= (if (k < 1f) (1f - (1f - k) * 0.4f) else 1f)
            if (z > 0f && (inEye(x, y) || inMouth(x, y))) continue
            // aankh ke gadde, gaal ki ubhar, naak ki halki ubhar
            if (z > 0f) {
                z -= 0.035f * exp(-(((abs(x) - eyeX) / 0.09f).pow(2) + ((y - eyeY) / 0.06f).pow(2)))
                z += 0.12f * exp(-((x / 0.05f).pow(2))) * exp(-(((y + 0.06f) / 0.13f).pow(2)))
            }
            if (z < -0.1f && r.nextFloat() < 0.55f) continue   // peeche ke dots kam (chehra saaf dikhe)
            put(x, y, z, 0); made++
        }
        // ---- kaan (purush; mahila ke baal dhak lete hain)
        if (!female) repeat(n / 60) {
            val s = if (r.nextBoolean()) 1f else -1f
            val th = r.nextFloat() * 2 * PI.toFloat()
            put(s * (a + 0.03f), 0.04f + 0.11f * sin(th), 0.03f * cos(th) - 0.02f, 0)
        }

        // ---- aankhein: bahari kinaara + putli
        val eyes = quota("eyes")
        for (i in 0 until eyes) {
            val s = if (i % 2 == 0) 1f else -1f
            val outline = (i / 2) % 10 < 7
            if (outline) {
                val th = r.nextFloat() * 2 * PI.toFloat()
                // badam jaisa aakaar
                val ex = eyeRx * cos(th); val ey = eyeRy * sin(th) * (1f - 0.25f * (ex / eyeRx).pow(2))
                val x = s * eyeX + ex; val y = eyeY + ey
                put(x, y, surfZ(x, y) + 0.012f, 2, eyeY)
            } else {
                val rr = 0.028f * sqrt(r.nextFloat()); val th = r.nextFloat() * 2 * PI.toFloat()
                val x = s * eyeX + rr * cos(th); val y = eyeY + rr * sin(th) * 0.9f
                put(x, y, surfZ(x, y) + 0.018f, 2, eyeY)
            }
        }
        // mahila: palkein (bahar ki taraf)
        if (female) repeat(n / 80) {
            val s = if (r.nextBoolean()) 1f else -1f
            val f = r.nextFloat()
            val x = s * (eyeX + eyeRx * (0.2f + 0.9f * f)); val y = eyeY + eyeRy * (1.05f + 0.6f * f * f)
            put(x, y, surfZ(x, y) + 0.02f, 2, eyeY)
        }
        // ---- bhauhein
        repeat(quota("brows")) {
            val s = if (r.nextBoolean()) 1f else -1f
            val f = r.nextFloat()
            val x = s * (eyeX - 0.08f + 0.19f * f)
            val y = eyeY + 0.10f + 0.035f * sin(f * PI.toFloat()) - (if (female) 0.01f * f else 0f) +
                (if (!female) gauss(r) * 0.008f else 0f)
            put(x, y, surfZ(x, y) + 0.015f, 1)
        }
        // ---- naak
        repeat(quota("nose")) {
            val f = r.nextFloat()
            if (f < 0.6f) {           // naak ki haddi
                val y = 0.03f - 0.22f * (f / 0.6f)
                val x = gauss(r) * 0.01f
                put(x, y, surfZ(x, y) + 0.02f + 0.11f * (0.03f - y) / 0.22f, 1)
            } else if (f < 0.82f) {   // naak ki nok
                val th = r.nextFloat() * 2 * PI.toFloat(); val rr = 0.03f
                val x = rr * cos(th); val y = -0.2f + rr * 0.6f * sin(th)
                put(x, y, surfZ(x, y) + 0.12f, 1)
            } else {                  // nathune
                val s = if (r.nextBoolean()) 1f else -1f; val th = r.nextFloat() * PI.toFloat()
                val x = s * (0.045f + 0.015f * cos(th)); val y = -0.215f + 0.012f * sin(th)
                put(x, y, surfZ(x, y) + 0.07f, 1)
            }
        }
        // ---- honth (upar + neeche)
        repeat(quota("mouth")) {
            val upper = r.nextBoolean()
            val f = r.nextFloat() * 2 - 1
            val x = f * mouthW
            val fullness = if (female) 0.022f else 0.012f
            val y = if (upper) mouthY + 0.012f + 0.012f * cos(f * PI.toFloat() / 2) - 0.012f * exp(-(f / 0.18f).pow(2)) * 0.6f +
                    r.nextFloat() * fullness * (1 - f * f)
                else mouthY - 0.004f - (0.022f + fullness) * cos(f * PI.toFloat() / 2) * r.nextFloat().pow(0.5f)
            put(x, y, surfZ(x, y) + 0.025f, 3, mouthY, low = !upper)
        }
        // ---- baal
        val hair = quota("hair")
        made = 0
        while (made < hair) {
            if (female && made > hair * 0.35f) {
                // lambe baal: kandhon tak, kaano aur peeche
                val th = (r.nextFloat() * 2 - 1) * PI.toFloat()   // 0 = saamne
                if (abs(th) < 0.95f) continue
                val y = 0.35f - 1.25f * r.nextFloat().pow(0.8f)
                val rad = a * 1.12f + 0.10f * ((0.35f - y) / 1.25f) + gauss(r) * 0.015f
                var x = rad * sin(th) + 0.02f * sin(y * 13 + th * 5)
                val z = rad * cos(th) * 0.95f
                if (y < -0.75f) x *= 1.1f
                put(x, y, z, 4); made++
            } else {
                // sir ki topi jaise baal
                val ux = gauss(r); val uy = gauss(r); val uz = gauss(r)
                val l = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-4f)
                val dx = ux / l; val dy = uy / l; val dz = uz / l
                val hairline = if (female) 0.40f - 0.10f * dx else 0.42f   // mahila: side maang
                val keep = (dy > hairline && dz < 0.8f) || (dz < -0.15f && dy > (if (female) -0.6f else -0.15f)) ||
                    (abs(dx) > 0.82f && dy > 0.05f && dz < 0.35f)
                if (!keep) continue
                val s = if (female) 1.10f else 1.06f
                put(dx * a * s, dy * b * (if (female) 1.05f else 1.03f) + 0.01f, dz * cz * s, 4); made++
            }
        }
        // ---- gardan + kandhe
        while (c.count < n) {
            val f = r.nextFloat()
            if (f < 0.35f) {
                val th = (r.nextFloat() * 2 - 1) * PI.toFloat() * 0.75f
                val nr = if (female) 0.16f else 0.20f
                val y = -0.6f - 0.36f * r.nextFloat()
                put(nr * sin(th), y, nr * cos(th) * 0.9f, 0)
            } else {
                val w = if (female) 0.85f else 1.0f
                val x = (r.nextFloat() * 2 - 1) * w
                val zz = (r.nextFloat() * 2 - 1) * 0.26f * sqrt(max(0f, 1f - (x / w).pow(2))) + 0.02f
                val y = -0.98f - 0.24f * (abs(x) / w).pow(2) * (if (abs(x) > 0.25f) 1f else 0.2f) - r.nextFloat().pow(2) * 0.2f
                put(x, y, zz, if (r.nextFloat() < 0.15f) 1 else 0)
            }
        }
        c.fill(r)
        c.tilt = 0f; c.spin = 0f; c.sway = true
        return c
    }

    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f); val v = r.nextFloat()
        return (sqrt(-2.0 * kotlin.math.ln(u.toDouble())) * cos(2 * PI * v)).toFloat()
    }
}
