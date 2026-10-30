package com.dk.assistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v4.x: नए futuristic ORB - सब छोटे-छोटे डॉट से बने, और हर ORB का अपना अलग चलता एनीमेशन (आकार के अंदर ही).
 *   न्यूरल नेटवर्क  = डॉट वाले नोड, उनके बीच की लाइनों पर दौड़ती रोशनी, नोड बारी-बारी चमकते
 *   होलोग्राम ग्लोब = अक्षांश के छल्ले उल्टी-सीधी दिशा में घूमते, ऊपर-नीचे चलती स्कैन लाइन
 *   पल्सर          = धड़कता कोर, घूमती डिस्क, दोनों ध्रुवों से बाहर बहती डॉट की धारा
 *   ब्लैक होल      = अंदर तेज़ - बाहर धीमी घूमती डिस्क, धीरे-धीरे अंदर खिंचते डॉट, चमकता फोटॉन छल्ला
 *   तरंग गोला       = गोले पर दौड़ती लहरें (बोलते/सुनते समय आवाज़ के साथ ऊँची)
 *   स्टार-गेट      = अलग-अलग दिशा और रफ़्तार से घूमते छल्ले, बारी-बारी जलते शेवरॉन, बीच में लहराता पानी
 * DK की हालत (सुनना / सोचना / बोलना) से रफ़्तार और ज़ोर बदलता है - रंग OrbView वाले ही.
 * Android import नहीं - JVM test से जाँच.
 */
object FutureOrbs {
    const val NEURAL = "neural"
    const val HOLO = "holo"
    const val PULSAR = "pulsar"
    const val BLACKHOLE = "blackhole"
    const val SOUNDWAVE = "soundwave"
    const val STARGATE = "stargate"

    val STYLES = listOf(
        NEURAL to "न्यूरल नेटवर्क (जुड़े नोड, दौड़ती रोशनी)",
        HOLO to "होलोग्राम ग्लोब (घूमते छल्ले + स्कैन लाइन)",
        PULSAR to "पल्सर (धड़कता कोर + बहती धारा)",
        BLACKHOLE to "ब्लैक होल (घूमती डिस्क)",
        SOUNDWAVE to "तरंग गोला (आवाज़ के साथ लहरें)",
        STARGATE to "स्टार-गेट (घूमते छल्ले + शेवरॉन)",
    )

    fun has(style: String?): Boolean = style != null && STYLES.any { it.first == style }

    /** हर frame string न मिलाएँ - एक बार का नंबर (0 = नया ORB नहीं) */
    fun code(style: String?): Int = when (style) {
        NEURAL -> 1; HOLO -> 2; PULSAR -> 3; BLACKHOLE -> 4; SOUNDWAVE -> 5; STARGATE -> 6; else -> 0
    }

    fun build(style: String, n: Int): Cloud = when (style) {
        NEURAL -> neural(n)
        HOLO -> holo(n)
        PULSAR -> pulsar(n)
        BLACKHOLE -> blackhole(n)
        SOUNDWAVE -> soundwave(n)
        else -> stargate(n)
    }

    /**
     * एक डॉट की इस पल की जगह और चमक. [x],[y],[z] = आकार की असली जगह, [a] = Cloud.anc (डॉट की पहचान),
     * [t] = एनीमेशन का समय (हालत के हिसाब से तेज़/धीमा), [amp] = आवाज़ का ज़ोर 0..1.
     * out[0..2] = नई जगह, out[3] = चमक (1 = सामान्य).
     */
    fun move(code: Int, i: Int, x: Float, y: Float, z: Float, a: Float, t: Float, amp: Float, out: FloatArray) {
        out[0] = x; out[1] = y; out[2] = z; out[3] = 1f
        when (code) {
            1 -> neuralMove(x, y, z, a, t, out)
            2 -> holoMove(x, y, z, a, t, out)
            3 -> pulsarMove(x, y, z, a, t, out)
            4 -> blackholeMove(x, y, z, a, t, out)
            5 -> soundMove(i, x, y, z, t, amp, out)
            6 -> gateMove(x, y, z, a, t, out)
        }
    }

    // ------------------------------------------------------------ helpers
    private val GOLDEN = (PI * (3 - sqrt(5.0))).toFloat()
    private const val TAU = (2 * PI).toFloat()

    private fun frac(v: Float) = v - floor(v)
    private fun hash(k: Int): Float = frac(sin(k * 12.9898f + 78.233f) * 43758.547f)

    private fun fib(n: Int, i: Int, r: Float, c: Cloud, kind: Int, a: Float = 0f) {
        val yy = 1f - 2f * (i + 0.5f) / n
        val rr = sqrt((1f - yy * yy).coerceAtLeast(0f))
        val th = GOLDEN * i
        c.add(cos(th) * rr * r, yy * r, sin(th) * rr * r, kind, a)
    }

    private fun fillRest(c: Cloud, r: Random) { if (c.count < c.n) c.fill(r) }

    // ------------------------------------------------------------ 1. न्यूरल नेटवर्क
    // anc: नोड = -(id+1); लाइन = linkId + u (u = लाइन पर कहाँ, 0..1)
    private const val NODES = 28

    fun neural(n: Int): Cloud {
        val c = Cloud(n); val r = Random(91)
        val nx = FloatArray(NODES); val ny = FloatArray(NODES); val nz = FloatArray(NODES)
        for (k in 0 until NODES) {
            val yy = 1f - 2f * (k + 0.5f) / NODES; val rr = sqrt(1f - yy * yy); val th = GOLDEN * k + (r.nextFloat() - 0.5f) * 0.5f
            val rad = 0.82f + r.nextFloat() * 0.16f
            nx[k] = cos(th) * rr * rad; ny[k] = yy * rad; nz[k] = sin(th) * rr * rad
        }
        // हर नोड से सबसे पास के 3 नोड
        val links = ArrayList<Pair<Int, Int>>()
        for (k in 0 until NODES) {
            val near = (0 until NODES).filter { it != k }.sortedBy {
                val dx = nx[it] - nx[k]; val dy = ny[it] - ny[k]; val dz = nz[it] - nz[k]; dx * dx + dy * dy + dz * dz
            }.take(3)
            for (m in near) if (links.none { (p, q) -> (p == k && q == m) || (p == m && q == k) }) links.add(k to m)
        }
        val nodeDots = (n * 0.22f).toInt().coerceAtLeast(NODES)
        val per = (nodeDots / NODES).coerceAtLeast(1)
        for (k in 0 until NODES) for (j in 0 until per) {
            val s = if (j == 0) 0f else 0.035f
            c.add(nx[k] + (r.nextFloat() - .5f) * s, ny[k] + (r.nextFloat() - .5f) * s, nz[k] + (r.nextFloat() - .5f) * s, 1, -(k + 1f))
        }
        val core = (n * 0.10f).toInt()
        for (i in 0 until core) fib(core, i, 0.28f, c, 4, -100f)
        val linkDots = ((n - c.count) / links.size.coerceAtLeast(1)).coerceAtLeast(1)
        for ((li, l) in links.withIndex()) {
            val (p, q) = l
            for (j in 0 until linkDots) {
                val u = (j + 0.5f) / linkDots
                // गोले पर हल्का उभरा हुआ चाप
                var x = nx[p] + (nx[q] - nx[p]) * u; var y = ny[p] + (ny[q] - ny[p]) * u; var z = nz[p] + (nz[q] - nz[p]) * u
                val d = sqrt(x * x + y * y + z * z).coerceAtLeast(0.01f)
                val want = 0.9f + 0.06f * sin(PI.toFloat() * u)
                x = x / d * want; y = y / d * want; z = z / d * want
                if (!c.add(x, y, z, 0, li + u.coerceAtMost(0.999f))) break
            }
        }
        fillRest(c, r)
        c.tilt = 0.3f; c.spin = 0.55f
        return c
    }

    private fun neuralMove(x: Float, y: Float, z: Float, a: Float, t: Float, out: FloatArray) {
        if (a <= -99f) { // कोर: धीमी साँस
            val s = 1f + 0.12f * sin(t * 2.2f)
            out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = 0.7f + 0.3f * sin(t * 2.2f); return
        }
        if (a < 0f) { // नोड: बारी-बारी चमक
            val id = (-a).toInt()
            val fire = sin(t * 1.7f + hash(id) * TAU)
            out[3] = 0.75f + 0.75f * fire.coerceAtLeast(0f)
            val s = 1f + 0.025f * fire
            out[0] = x * s; out[1] = y * s; out[2] = z * s
            return
        }
        val link = floor(a).toInt(); val u = a - link
        val dir = if (hash(link + 7) > 0.5f) 1f else -1f
        val head = frac(t * (0.35f + 0.3f * hash(link)) + hash(link + 3))
        var d = abs((if (dir > 0) u else 1f - u) - head); if (d > 0.5f) d = 1f - d
        out[3] = 0.35f + 1.35f * exp(-d * d / 0.006f)
    }

    // ------------------------------------------------------------ 2. होलोग्राम ग्लोब
    // anc = छल्ले का नंबर (0..RINGS-1); 50 = ध्रुव से ध्रुव वाली पट्टी
    private const val RINGS = 17

    fun holo(n: Int): Cloud {
        val c = Cloud(n); val r = Random(17)
        val merid = (n * 0.12f).toInt()
        val ringDots = n - merid
        var total = 0f
        val circ = FloatArray(RINGS) { k -> val phi = PI.toFloat() * (k + 1) / (RINGS + 1) - PI.toFloat() / 2; cos(phi).also { total += it } }
        for (k in 0 until RINGS) {
            val phi = PI.toFloat() * (k + 1) / (RINGS + 1) - PI.toFloat() / 2
            val yy = sin(phi); val rr = cos(phi)
            val m = (ringDots * circ[k] / total).toInt().coerceAtLeast(6)
            for (j in 0 until m) {
                val th = TAU * j / m
                c.add(cos(th) * rr, yy, sin(th) * rr, if (j % 9 == 0 || k == RINGS / 2) 1 else 0, k.toFloat())
            }
        }
        for (j in 0 until merid) {
            val phi = PI.toFloat() * (j + 0.5f) / merid - PI.toFloat() / 2
            val side = if (j % 2 == 0) 1f else -1f
            c.add(cos(phi) * 1.06f * side, sin(phi) * 1.06f, 0f, 4, 50f)
        }
        fillRest(c, r)
        c.tilt = 0.22f; c.spin = 0.25f
        return c
    }

    private fun holoMove(x: Float, y: Float, z: Float, a: Float, t: Float, out: FloatArray) {
        val scan = sin(t * 0.9f)
        val dy = y - scan
        val band = exp(-dy * dy / 0.006f)
        if (a >= 49f) { // बाहरी पट्टी - उल्टा घूमे
            val w = -t * 0.6f; val cs = cos(w); val sn = sin(w)
            out[0] = x * cs + z * sn; out[2] = -x * sn + z * cs
            out[3] = 0.6f + 0.9f * band; return
        }
        val k = a.toInt()
        val w = t * (if (k % 2 == 0) 0.55f else -0.4f) * (1f + 0.04f * k)
        val cs = cos(w); val sn = sin(w)
        val push = 1f + 0.07f * band
        out[0] = (x * cs + z * sn) * push; out[1] = y; out[2] = (-x * sn + z * cs) * push
        // झिलमिलाहट: हर छल्ला अपनी लय में
        out[3] = 0.45f + 1.25f * band + 0.2f * sin(t * 3f + k * 1.3f)
    }

    // ------------------------------------------------------------ 3. पल्सर
    // anc: 0 = कोर; 10..12 = डिस्क (10 + r); 20..21 = ऊपर की धारा (20 + u); -20..-21 = नीचे
    fun pulsar(n: Int): Cloud {
        val c = Cloud(n); val r = Random(33)
        val core = (n * 0.26f).toInt(); val disc = (n * 0.30f).toInt()
        for (i in 0 until core) fib(core, i, 0.30f + 0.04f * r.nextFloat(), c, 1, 0f)
        for (i in 0 until disc) {
            val rad = 0.48f + 0.62f * sqrt(r.nextFloat()); val th = r.nextFloat() * TAU
            c.add(cos(th) * rad, (r.nextFloat() - 0.5f) * 0.03f, sin(th) * rad, if (i % 7 == 0) 1 else 0, 10f + rad)
        }
        var j = 0
        while (c.count < n) {
            val up = j % 2 == 0; val u = r.nextFloat(); val th = r.nextFloat() * TAU
            val y = (0.32f + u * 1.08f) * (if (up) 1f else -1f)
            val rad = 0.03f + u * 0.18f
            c.add(cos(th) * rad, y, sin(th) * rad, if (j % 5 == 0) 1 else 4, (if (up) 1f else -1f) * (20f + u * 0.999f))
            j++
        }
        c.tilt = 0.55f; c.spin = 0.45f
        return c
    }

    private fun pulsarMove(x: Float, y: Float, z: Float, a: Float, t: Float, out: FloatArray) {
        if (a < 5f && a > -5f) { // कोर की धड़कन (तेज़ धक्-धक्)
            val beat = exp(-frac(t * 0.9f) * 6f)
            val s = 1f + 0.16f * beat
            out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = 0.8f + 0.7f * beat; return
        }
        if (a in 9f..13f) { // डिस्क: अंदर तेज़
            val rad = a - 10f
            val w = t * 0.9f / (rad * rad + 0.2f); val cs = cos(w); val sn = sin(w)
            out[0] = x * cs + z * sn; out[2] = -x * sn + z * cs
            out[3] = 0.55f + 0.5f * (1.2f - rad); return
        }
        // धारा: बाहर की ओर बहती
        val sign = if (a > 0) 1f else -1f
        val u0 = abs(a) - 20f
        val u = frac(u0 + t * 0.45f)
        val rad0 = 0.03f + u0 * 0.18f
        val rad = 0.03f + u * 0.18f
        val k = if (rad0 > 0.0001f) rad / rad0 else 1f
        out[0] = x * k; out[2] = z * k
        out[1] = sign * (0.32f + u * 1.08f)
        out[3] = (1.5f - 1.2f * u).coerceAtLeast(0.15f)
    }

    // ------------------------------------------------------------ 4. ब्लैक होल
    // anc: 1..2 = डिस्क (1 + (r - IN) / SPAN); -1 = फोटॉन छल्ला; -2 = दूर की धूल
    private const val IN = 0.42f
    private const val SPAN = 0.86f

    fun blackhole(n: Int): Cloud {
        val c = Cloud(n); val r = Random(44)
        val ring = (n * 0.16f).toInt(); val dust = (n * 0.08f).toInt()
        val disc = n - ring - dust
        for (i in 0 until disc) {
            val f = r.nextFloat().let { it * it }            // अंदर घना
            val rad = IN + SPAN * f; val th = r.nextFloat() * TAU
            c.add(cos(th) * rad, (r.nextFloat() - 0.5f) * 0.025f * (1f + f * 2f), sin(th) * rad,
                if (f < 0.18f || i % 11 == 0) 1 else 0, 1f + f.coerceAtMost(0.999f))
        }
        for (i in 0 until ring) { val th = TAU * i / ring; c.add(cos(th) * 0.40f, sin(th) * 0.40f, 0f, 1, -1f) }
        for (i in 0 until dust) fib(dust, i, 1.25f + r.nextFloat() * 0.1f, c, 4, -2f)
        fillRest(c, r)
        c.tilt = 0.32f; c.spin = 0.12f
        return c
    }

    private fun blackholeMove(x: Float, y: Float, z: Float, a: Float, t: Float, out: FloatArray) {
        if (a > 0.5f) {
            val f0 = a - 1f
            val rad0 = IN + SPAN * f0
            // धीरे-धीरे अंदर खिंचना (अंदर पहुँचे तो फिर बाहर से)
            val f = frac(f0 - t * 0.035f)
            val rad = IN + SPAN * f
            val w = t * 0.55f / (rad * sqrt(rad))              // केप्लर: अंदर तेज़
            val cs = cos(w); val sn = sin(w); val k = rad / rad0
            out[0] = (x * cs + z * sn) * k; out[2] = (-x * sn + z * cs) * k
            out[3] = 0.4f + 1.3f * (1f - f) * (1f - f)
            return
        }
        if (a > -1.5f) { // फोटॉन छल्ला - चमक घूमती है
            out[3] = 0.85f + 0.55f * sin(t * 2.4f + 3f * kotlin.math.atan2(y, x))
            return
        }
        out[3] = 0.35f + 0.25f * sin(t + x * 7f)
    }

    // ------------------------------------------------------------ 5. तरंग गोला
    fun soundwave(n: Int): Cloud {
        val c = Cloud(n)
        for (i in 0 until n) {
            val yy = 1f - 2f * (i + 0.5f) / n
            val k = if (i % 23 == 0) 1 else 0
            fib(n, i, 0.95f, c, k, yy)
        }
        c.tilt = 0.5f; c.spin = 0.4f
        return c
    }

    private fun soundMove(i: Int, x: Float, y: Float, z: Float, t: Float, amp: Float, out: FloatArray) {
        val a = 0.035f + 0.11f * amp.coerceIn(0f, 1f)
        val w1 = sin(y * 9f - t * 3.2f)
        val w2 = sin((x + z) * 6f + t * 2.3f)
        val s = 1f + a * w1 + 0.5f * a * w2
        out[0] = x * s; out[1] = y * s; out[2] = z * s
        out[3] = 0.55f + 0.8f * ((w1 + 1f) * 0.5f).let { it * it } + (if (i % 23 == 0) 0.3f else 0f)
    }

    // ------------------------------------------------------------ 6. स्टार-गेट
    // anc: 0..3 = छल्ला; 10..18 = शेवरॉन (बाहरी छल्ले पर); 30..31 = बीच का पानी (30 + r)
    private val GATE_R = floatArrayOf(1.0f, 0.86f, 0.72f, 0.58f)
    private val GATE_SPEED = floatArrayOf(0.35f, -0.6f, 0.9f, -1.3f)
    // हर छल्ले का झुकाव (normal) - अलग-अलग दिशा
    private val GATE_N = arrayOf(
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0.5f, 0f, 0.866f),
        floatArrayOf(0f, 0.6f, 0.8f),
        floatArrayOf(-0.6f, 0.3f, 0.742f),
    )
    private const val CHEV = 9

    fun stargate(n: Int): Cloud {
        val c = Cloud(n); val r = Random(55)
        val water = (n * 0.20f).toInt(); val chev = (n * 0.07f).toInt()
        val ringDots = (n - water - chev) / GATE_R.size
        for ((k, rad) in GATE_R.withIndex()) {
            val nv = GATE_N[k]
            // normal के लंबवत दो दिशाएँ
            val ux: Float; val uy: Float; val uz: Float
            if (abs(nv[1]) < 0.9f) { val l = sqrt(nv[2] * nv[2] + nv[0] * nv[0]); ux = nv[2] / l; uy = 0f; uz = -nv[0] / l }
            else { ux = 1f; uy = 0f; uz = 0f }
            val vx = nv[1] * uz - nv[2] * uy; val vy = nv[2] * ux - nv[0] * uz; val vz = nv[0] * uy - nv[1] * ux
            for (j in 0 until ringDots) {
                val th = TAU * j / ringDots
                val cr = rad + (if (k == 0) (r.nextFloat() - 0.5f) * 0.05f else 0f)
                c.add((ux * cos(th) + vx * sin(th)) * cr, (uy * cos(th) + vy * sin(th)) * cr, (uz * cos(th) + vz * sin(th)) * cr,
                    if (j % 12 == 0) 1 else 0, k.toFloat())
            }
        }
        val per = (chev / CHEV).coerceAtLeast(1)
        for (h in 0 until CHEV) for (j in 0 until per) {
            val th = TAU * h / CHEV + (r.nextFloat() - 0.5f) * 0.06f
            val rad = 1.06f + r.nextFloat() * 0.08f
            c.add(cos(th) * rad, sin(th) * rad, (r.nextFloat() - 0.5f) * 0.04f, 1, 10f + h)
        }
        while (c.count < n) {
            val rad = 0.5f * sqrt(r.nextFloat()); val th = r.nextFloat() * TAU
            c.add(cos(th) * rad, sin(th) * rad, 0f, 4, 30f + rad)
        }
        c.tilt = 0.18f; c.spin = 0.2f
        return c
    }

    private fun gateMove(x: Float, y: Float, z: Float, a: Float, t: Float, out: FloatArray) {
        if (a < 5f) { // छल्ला: अपनी normal के चारों ओर घूमे (Rodrigues; डॉट normal के लंबवत हैं)
            val k = a.toInt().coerceIn(0, GATE_R.size - 1)
            val nv = GATE_N[k]; val w = t * GATE_SPEED[k]; val cs = cos(w); val sn = sin(w)
            val cx = nv[1] * z - nv[2] * y; val cy = nv[2] * x - nv[0] * z; val cz = nv[0] * y - nv[1] * x
            out[0] = x * cs + cx * sn; out[1] = y * cs + cy * sn; out[2] = z * cs + cz * sn
            out[3] = 0.6f + 0.35f * sin(t * 2f + k)
            return
        }
        if (a < 25f) { // शेवरॉन: बारी-बारी जलें
            val h = (a - 10f).toInt()
            val on = frac(t * 0.5f) * CHEV
            var d = abs(on - h); if (d > CHEV / 2f) d = CHEV - d
            out[3] = 0.4f + 1.5f * exp(-d * d / 0.5f)
            return
        }
        // बीच का पानी: लहराता (z में हल्की लहर + चमक)
        val rad = a - 30f
        val wv = sin(rad * 22f - t * 4f)
        out[2] = z + 0.05f * wv * (0.5f - rad)
        out[3] = 0.45f + 0.5f * (wv * 0.5f + 0.5f)
    }
}
