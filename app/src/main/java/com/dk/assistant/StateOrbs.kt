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
 * v4.x: हालत के साथ आकार बदलने वाले futuristic ORB - DK जैसे ही सुनना / सोचना / बोलना शुरू करे,
 * डॉट उड़कर उस हालत का अलग आकार बना लेते हैं, और रंग भी उसी हालत का हो जाता है.
 * खाली (idle) = अपना सामान्य आकार.
 *
 *  ऑरोरा क्रिस्टल : क्रिस्टल  | सिमटते छल्ले     | जाइरोस्कोप (घूमते छल्ले)  | आवाज़ की पट्टियाँ (गोल)
 *  नोवा          : गोला      | भँवर (अंदर खिंचे) | डबल हेलिक्स               | काँटेदार तारा
 *  कमल ऊर्जा     : कमल       | लहरों वाली थाली    | अनंत (∞) पट्टी             | फैलते कवच
 *  साइबर क्यूब    : क्यूब      | लहराती जाली        | एक के अंदर एक घूमते क्यूब  | आवाज़ के खंभे
 *  ग्रह-छल्ला     : छल्ले वाला ग्रह | अंदर आती लहरें | इलेक्ट्रॉन का झुंड         | लहराता गोला
 *
 * Settings में नाम = [STYLES] (बेस नाम). OrbView हालत जोड़कर [key] बनाता है ("sx_nova|2") और
 * [build] उस हालत का आकार देता है; हर frame [move] से डॉट चलते हैं (Android import नहीं - JVM test).
 */
object StateOrbs {
    const val AURORA = "sx_aurora"
    const val NOVA = "sx_nova"
    const val LOTUS = "sx_lotus"
    const val CYBER = "sx_cyber"
    const val SATURN = "sx_saturn"

    val STYLES = listOf(
        AURORA to "ऑरोरा क्रिस्टल (हालत के साथ आकार + रंग बदले)",
        NOVA to "नोवा (हालत के साथ आकार + रंग बदले)",
        LOTUS to "कमल ऊर्जा (हालत के साथ आकार + रंग बदले)",
        CYBER to "साइबर क्यूब (हालत के साथ आकार + रंग बदले)",
        SATURN to "ग्रह-छल्ला (हालत के साथ आकार + रंग बदले)",
    )

    /** हालत: 0 खाली, 1 सुनना, 2 सोचना, 3 बोलना */
    const val IDLE = 0; const val LISTEN = 1; const val THINK = 2; const val SPEAK = 3

    fun has(style: String?): Boolean = style != null && base(style) != null
    fun isKey(style: String?): Boolean = style != null && style.contains('|') && has(style)

    /** "sx_nova|2" -> "sx_nova" */
    fun base(style: String): String? {
        val b = style.substringBefore('|')
        return if (STYLES.any { it.first == b }) b else null
    }

    fun state(mode: Mode): Int = when (mode) {
        Mode.IDLE -> IDLE; Mode.LISTENING -> LISTEN; Mode.THINKING, Mode.WORKING -> THINK; Mode.SPEAKING -> SPEAK
    }

    fun key(style: String, st: Int): String = "${base(style) ?: style}|${st.coerceIn(0, 3)}"

    private fun stateOf(key: String): Int = key.substringAfter('|', "0").toIntOrNull()?.coerceIn(0, 3) ?: 0

    // ------------------------------------------------------------ आकार के प्रकार (move के लिए code = 100 + id)
    private const val CRYSTAL = 1; private const val RINGS = 2; private const val GYRO = 3; private const val EQ_RING = 4
    private const val SPHERE = 5; private const val VORTEX = 6; private const val HELIX = 7; private const val SPIKES = 8
    private const val LOTUS_F = 9; private const val RIPPLE = 10; private const val INFINITY = 11; private const val SHELLS = 12
    private const val CUBE = 13; private const val WAVEGRID = 14; private const val NESTCUBE = 15; private const val EQ_CYL = 16
    private const val PLANET = 17; private const val INWAVE = 18; private const val SWARM = 19; private const val WAVESPHERE = 20

    private val TABLE = mapOf(
        AURORA to intArrayOf(CRYSTAL, RINGS, GYRO, EQ_RING),
        NOVA to intArrayOf(SPHERE, VORTEX, HELIX, SPIKES),
        LOTUS to intArrayOf(LOTUS_F, RIPPLE, INFINITY, SHELLS),
        CYBER to intArrayOf(CUBE, WAVEGRID, NESTCUBE, EQ_CYL),
        SATURN to intArrayOf(PLANET, INWAVE, SWARM, WAVESPHERE),
    )

    private fun shapeOf(key: String): Int = TABLE[base(key)]?.get(stateOf(key)) ?: SPHERE

    /** हर frame string न मिलाएँ - एक बार का नंबर (0 = यह ORB नहीं) */
    fun code(key: String?): Int = if (key == null || !has(key)) 0 else 100 + shapeOf(key)

    /**
     * हालत के रंग (ARGB): खाली / सुनना / सोचना / बोलना - हर ORB का अपना.
     * पहला = मुख्य रंग (चमक), दूसरा = चमकते डॉट.
     */
    fun colors(style: String, st: Int): IntArray {
        val p = PALETTE[base(style)] ?: PALETTE.getValue(NOVA)
        val i = st.coerceIn(0, 3) * 2
        return intArrayOf(p[i], p[i + 1])
    }

    private val PALETTE = mapOf(
        AURORA to intArrayOf(0xFF00E5FF.toInt(), 0xFFB2FFF9.toInt(), 0xFF00E676.toInt(), 0xFFCCFF90.toInt(),
            0xFFD500F9.toInt(), 0xFFFF80AB.toInt(), 0xFFFFC400.toInt(), 0xFFFFF59D.toInt()),
        NOVA to intArrayOf(0xFF2979FF.toInt(), 0xFFE3F2FF.toInt(), 0xFFFF1744.toInt(), 0xFFFF8A80.toInt(),
            0xFFFF9100.toInt(), 0xFFFFE57F.toInt(), 0xFF1DE9B6.toInt(), 0xFFA7FFEB.toInt()),
        LOTUS to intArrayOf(0xFFE040FB.toInt(), 0xFFFFC1F3.toInt(), 0xFFFF4081.toInt(), 0xFFFFB2DD.toInt(),
            0xFFFFD740.toInt(), 0xFFFFFFFF.toInt(), 0xFF00E676.toInt(), 0xFFB9F6CA.toInt()),
        CYBER to intArrayOf(0xFF00FF9C.toInt(), 0xFFB9FFE6.toInt(), 0xFF2962FF.toInt(), 0xFF82B1FF.toInt(),
            0xFFFF3D00.toInt(), 0xFFFFAB40.toInt(), 0xFFFFEA00.toInt(), 0xFFFFFFFF.toInt()),
        SATURN to intArrayOf(0xFFFFB300.toInt(), 0xFFFFECB3.toInt(), 0xFF00B8D4.toInt(), 0xFF84FFFF.toInt(),
            0xFF7C4DFF.toInt(), 0xFFEA80FC.toInt(), 0xFFFF4F9A.toInt(), 0xFFFFC1E3.toInt()),
    )

    // ------------------------------------------------------------ बनाना
    fun build(key: String, n: Int): Cloud {
        val c = Cloud(n)
        val r = Random(500 + shapeOf(key))
        when (shapeOf(key)) {
            CRYSTAL -> crystal(c, r); RINGS -> rings(c, r); GYRO -> gyro(c, r); EQ_RING -> eqRing(c, r)
            SPHERE -> sphere(c, r); VORTEX -> vortex(c, r); HELIX -> helix(c, r); SPIKES -> spikes(c, r)
            LOTUS_F -> lotus(c, r); RIPPLE -> ripple(c, r); INFINITY -> infinity(c, r); SHELLS -> shells(c, r)
            CUBE -> cube(c, r); WAVEGRID -> waveGrid(c, r); NESTCUBE -> nestCube(c, r); EQ_CYL -> eqCyl(c, r)
            PLANET -> planet(c, r); INWAVE -> inWave(c, r); SWARM -> swarm(c, r); else -> waveSphere(c, r)
        }
        if (c.count < n) c.fill(r)
        // पक्का: कम से कम एक चमकता और एक सामान्य डॉट (बहुत कम डॉट वाले छोटे ORB में भी)
        if (n >= 2) { c.kind[0] = 1; if (c.kind.all { it.toInt() == 1 }) c.kind[n - 1] = 0 }
        return c
    }

    private val GOLDEN = (PI * (3 - sqrt(5.0))).toFloat()
    private const val TAU = (2 * PI).toFloat()
    private fun frac(v: Float) = v - floor(v)
    private fun hash(k: Int): Float = frac(sin(k * 12.9898f + 78.233f) * 43758.547f)
    private fun q(c: Cloud, f: Float) = (c.n * f).toInt().coerceAtLeast(1)
    private fun fibPt(i: Int, n: Int, out: FloatArray) {
        val yy = 1f - 2f * (i + 0.5f) / n; val rr = sqrt((1f - yy * yy).coerceAtLeast(0f)); val th = GOLDEN * i
        out[0] = cos(th) * rr; out[1] = yy; out[2] = sin(th) * rr
    }
    private val tmp = FloatArray(3)

    /** दो बिंदुओं के बीच की रेखा पर डॉट (anc = id + u) */
    private fun edge(c: Cloud, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, dots: Int, k: Int, id: Int) {
        for (j in 0 until dots) {
            val u = (j + 0.5f) / dots
            if (!c.add(ax + (bx - ax) * u, ay + (by - ay) * u, az + (bz - az) * u, k, id + u.coerceAtMost(0.999f))) return
        }
    }

    // 1. क्रिस्टल: दोहरा पिरामिड (अष्टफलक) - किनारे चमकते, सतह पर हल्के डॉट
    private val OCT = arrayOf(floatArrayOf(0f, 1.15f, 0f), floatArrayOf(0f, -1.15f, 0f), floatArrayOf(0.8f, 0f, 0f),
        floatArrayOf(-0.8f, 0f, 0f), floatArrayOf(0f, 0f, 0.8f), floatArrayOf(0f, 0f, -0.8f))
    private val OCT_E = arrayOf(0 to 2, 0 to 3, 0 to 4, 0 to 5, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 2 to 4, 4 to 3, 3 to 5, 5 to 2)
    private fun crystal(c: Cloud, r: Random) {
        val per = (q(c, 0.45f) / OCT_E.size).coerceAtLeast(1)
        for ((i, e) in OCT_E.withIndex()) { val a = OCT[e.first]; val b = OCT[e.second]; edge(c, a[0], a[1], a[2], b[0], b[1], b[2], per, 1, i) }
        val faces = arrayOf(intArrayOf(0, 2, 4), intArrayOf(0, 4, 3), intArrayOf(0, 3, 5), intArrayOf(0, 5, 2),
            intArrayOf(1, 2, 4), intArrayOf(1, 4, 3), intArrayOf(1, 3, 5), intArrayOf(1, 5, 2))
        val core = q(c, 0.08f)
        for (i in 0 until core) { fibPt(i, core, tmp); c.add(tmp[0] * 0.22f, tmp[1] * 0.22f, tmp[2] * 0.22f, 1, -2f) }
        while (c.count < c.n) {
            val f = faces[r.nextInt(faces.size)]
            var a = r.nextFloat(); var b = r.nextFloat(); if (a + b > 1f) { a = 1f - a; b = 1f - b }
            val p0 = OCT[f[0]]; val p1 = OCT[f[1]]; val p2 = OCT[f[2]]
            c.add(p0[0] + (p1[0] - p0[0]) * a + (p2[0] - p0[0]) * b, p0[1] + (p1[1] - p0[1]) * a + (p2[1] - p0[1]) * b,
                p0[2] + (p1[2] - p0[2]) * a + (p2[2] - p0[2]) * b, 0, -1f)
        }
        c.tilt = 0.25f; c.spin = 0.6f
    }

    // 2. सामने की ओर गोल छल्ले (सुनना: बाहर से अंदर सिमटते)
    private const val RING_N = 7
    private fun rings(c: Cloud, r: Random) {
        val per = (c.n / RING_N).coerceAtLeast(1)
        for (k in 0 until RING_N) for (j in 0 until per) {
            val th = TAU * (j + r.nextFloat() * 0.3f) / per
            val rad = 0.2f + 1.0f * (k + 0.5f) / RING_N
            if (!c.add(cos(th) * rad, sin(th) * rad, (r.nextFloat() - 0.5f) * 0.04f, if (j % 5 == 0) 1 else 0, k.toFloat())) return
        }
        c.tilt = 0f; c.spin = 0f
    }

    // 3. जाइरोस्कोप: तीन अलग दिशा में घूमते छल्ले + धड़कता कोर
    private fun gyro(c: Cloud, r: Random) {
        val core = q(c, 0.12f)
        for (i in 0 until core) { fibPt(i, core, tmp); c.add(tmp[0] * 0.28f, tmp[1] * 0.28f, tmp[2] * 0.28f, 1, -1f) }
        val per = ((c.n - c.count) / 3).coerceAtLeast(1)
        for (k in 0 until 3) for (j in 0 until per) {
            val u = (j + 0.5f) / per; val th = TAU * u; val rad = 0.85f + 0.17f * k
            val a = cos(th) * rad; val b = sin(th) * rad
            val p = when (k) { 0 -> floatArrayOf(a, 0f, b); 1 -> floatArrayOf(a, b, 0f); else -> floatArrayOf(0f, a, b) }
            if (!c.add(p[0], p[1], p[2], if (j % 4 == 0) 1 else 0, k + u.coerceAtMost(0.999f))) return
        }
        c.tilt = 0.35f; c.spin = 0.4f
    }

    // 4. गोल आवाज़-पट्टियाँ (बोलना): बीच में छल्ला, बाहर की ओर पट्टियाँ
    private const val BARS = 48
    private fun eqRing(c: Cloud, r: Random) {
        val ring = q(c, 0.18f)
        for (j in 0 until ring) { val th = TAU * j / ring; c.add(cos(th) * 0.5f, sin(th) * 0.5f, 0f, 1, -1f) }
        val per = ((c.n - c.count) / BARS).coerceAtLeast(1)
        for (b in 0 until BARS) for (j in 0 until per) {
            val u = (j + 0.5f) / per; val th = TAU * b / BARS; val rad = 0.58f + 0.4f * u
            if (!c.add(cos(th) * rad, sin(th) * rad, 0f, if (j == per - 1) 1 else 0, b + u.coerceAtMost(0.999f))) return
        }
        c.tilt = 0f; c.spin = 0f
    }

    // 5. चिकना गोला (सामान्य)
    private fun sphere(c: Cloud, r: Random) {
        val n = c.n
        for (i in 0 until n) { fibPt(i, n, tmp); c.add(tmp[0] * 0.95f, tmp[1] * 0.95f, tmp[2] * 0.95f, if (hash(i) < 0.12f) 1 else 0, hash(i + 9)) }
        c.tilt = 0.4f; c.spin = 0.5f
    }

    // 6. भँवर: सर्पिल भुजाएँ, डॉट अंदर-नीचे खिंचते (anc = भुजा*10 + s)
    private fun vortex(c: Cloud, r: Random) {
        val arms = 5; val per = (c.n / arms).coerceAtLeast(1)
        for (a in 0 until arms) for (j in 0 until per) {
            val s = (j + r.nextFloat()) / per
            if (!c.add(0f, 0f, 0f, if (j % 6 == 0) 1 else 0, a * 10f + s.coerceAtMost(0.999f))) return
        }
        c.tilt = 0.55f; c.spin = 0.3f
    }
    private fun vortexAt(a: Float, t: Float, out: FloatArray) {
        val arm = floor(a / 10f); val s0 = a - arm * 10f
        val s = frac(s0 - t * 0.22f)
        val rad = 0.08f + 1.12f * (1f - s)
        val th = arm * TAU / 5f + s * 7f + t * 0.5f
        out[0] = cos(th) * rad; out[2] = sin(th) * rad; out[1] = 0.55f - 1.25f * s * s
        out[3] = 0.6f + 0.9f * s
    }

    // 7. डबल हेलिक्स (सोचना): दो धागे + सीढ़ी
    private fun helix(c: Cloud, r: Random) {
        val strand = q(c, 0.36f); val rungs = 22
        for (k in 0 until 2) for (j in 0 until strand) {
            val u = (j + 0.5f) / strand; val th = u * TAU * 2.2f + k * PI.toFloat(); val y = -1.15f + 2.3f * u
            if (!c.add(cos(th) * 0.55f, y, sin(th) * 0.55f, if (j % 5 == 0) 1 else 0, k + u.coerceAtMost(0.999f))) return
        }
        val per = ((c.n - c.count) / rungs).coerceAtLeast(1)
        for (g in 0 until rungs) {
            val u = (g + 0.5f) / rungs; val th = u * TAU * 2.2f; val y = -1.15f + 2.3f * u
            edge(c, cos(th) * 0.55f, y, sin(th) * 0.55f, -cos(th) * 0.55f, y, -sin(th) * 0.55f, per, 0, 10 + g)
        }
        c.tilt = 0.15f; c.spin = 1.4f
    }

    // 8. काँटेदार तारा (बोलना): गोला + आवाज़ से लंबे होते काँटे (anc = -1 गोला, id + u काँटा)
    private const val SPK = 42
    private fun spikes(c: Cloud, r: Random) {
        val base = q(c, 0.45f)
        for (i in 0 until base) { fibPt(i, base, tmp); c.add(tmp[0] * 0.62f, tmp[1] * 0.62f, tmp[2] * 0.62f, 0, -1f) }
        val per = ((c.n - c.count) / SPK).coerceAtLeast(1)
        for (s in 0 until SPK) { fibPt(s, SPK, tmp); edge(c, tmp[0] * 0.64f, tmp[1] * 0.64f, tmp[2] * 0.64f, tmp[0], tmp[1], tmp[2], per, 1, s) }
        c.tilt = 0.3f; c.spin = 0.6f
    }

    // 9. कमल: तीन परत की पंखुड़ियाँ (anc = परत + कोण/τ)
    private fun lotus(c: Cloud, r: Random) {
        val core = q(c, 0.08f)
        for (i in 0 until core) { fibPt(i, core, tmp); c.add(tmp[0] * 0.18f, tmp[1] * 0.18f + 0.05f, tmp[2] * 0.18f, 1, -1f) }
        // हर पंखुड़ी: लंबाई में u, चौड़ाई में v (-1..1); बाहर वाली परत लंबी और ज़्यादा खुली
        while (c.count < c.n) {
            val layer = r.nextInt(3); val petals = 6 + layer * 2
            val p = r.nextInt(petals)
            val u = sqrt(r.nextFloat()); val v = r.nextFloat() * 2 - 1
            val len = 0.5f + 0.25f * layer
            val rho = 0.14f + len * u
            val half = (0.34f - 0.05f * layer) * sin(PI.toFloat() * u.coerceIn(0.05f, 1f)) * (1f - 0.25f * u)
            val th = TAU * p / petals + layer * 0.37f + v * half / rho.coerceAtLeast(0.1f)
            val lift = (0.95f - 0.28f * layer) * u * u
            val edgeK = if (abs(v) > 0.9f || u > 0.97f) 1 else 0
            c.add(cos(th) * rho, -0.4f + lift - 0.05f * v * v, sin(th) * rho, edgeK, layer + (th / TAU - floor(th / TAU)) * 0.999f)
        }
        c.tilt = 0.5f; c.spin = 0.35f
    }

    // 10. लहरों वाली थाली (सुनना): चपटी गोल सतह, आवाज़ से लहरें
    private fun ripple(c: Cloud, r: Random) {
        val n = c.n
        for (i in 0 until n) {
            val rr = 1.15f * sqrt((i + 0.5f) / n); val th = GOLDEN * i
            c.add(cos(th) * rr, 0f, sin(th) * rr, if (i % 9 == 0) 1 else 0, rr)
        }
        c.tilt = 0.55f; c.spin = 0.2f
    }

    // 11. अनंत (∞) पट्टी (सोचना): डॉट पट्टी पर दौड़ते
    private fun infinity(c: Cloud, r: Random) {
        val lines = 5; val per = (c.n / lines).coerceAtLeast(1)
        for (k in 0 until lines) for (j in 0 until per) {
            if (!c.add(0f, 0f, 0f, if (k == 2) 1 else 0, k + ((j + 0.5f) / per).coerceAtMost(0.999f))) return
        }
        c.tilt = 0.35f; c.spin = 0.25f
    }
    private fun infAt(a: Float, t: Float, out: FloatArray) {
        val k = floor(a); val u0 = a - k
        val u = frac(u0 + t * 0.12f); val th = u * TAU
        val d = 1f + sin(th) * sin(th)
        val x = 1.2f * cos(th) / d; val y = 1.2f * sin(th) * cos(th) / d
        val off = (k - 2f) * 0.07f
        // पट्टी में मरोड़
        val tw = th * 1f + t * 0.8f
        out[0] = x; out[1] = y + off * cos(tw); out[2] = off * sin(tw) * 1.6f + 0.25f * sin(th * 2f)
        val head = frac(t * 0.3f); var dd = abs(u - head); if (dd > 0.5f) dd = 1f - dd
        out[3] = 0.55f + 1.2f * exp(-dd * dd / 0.004f)
    }

    // 12. तीन कवच (बोलना): अंदर से बाहर फैलती परतें (anc = कवच)
    private fun shells(c: Cloud, r: Random) {
        val per = (c.n / 3).coerceAtLeast(1)
        for (s in 0 until 3) for (i in 0 until per) {
            fibPt(i, per, tmp); val rad = 0.4f + 0.3f * s
            if (!c.add(tmp[0] * rad, tmp[1] * rad, tmp[2] * rad, if ((i + s) % 7 == 0) 1 else 0, s.toFloat())) return
        }
        c.tilt = 0.3f; c.spin = 0.5f
    }

    // 13. क्यूब: चमकते किनारे + हल्की सतह
    private val CUBE_E = run {
        val l = ArrayList<IntArray>()
        for (a in 0 until 8) for (b in a + 1 until 8) { val d = a xor b; if (d == 1 || d == 2 || d == 4) l.add(intArrayOf(a, b)) }
        l
    }
    private fun corner(i: Int, s: Float, out: FloatArray) {
        out[0] = if (i and 1 != 0) s else -s; out[1] = if (i and 2 != 0) s else -s; out[2] = if (i and 4 != 0) s else -s
    }
    private fun cubeEdges(c: Cloud, s: Float, dots: Int, id0: Int) {
        val per = (dots / 12).coerceAtLeast(1); val p = FloatArray(3); val q = FloatArray(3)
        for ((i, e) in CUBE_E.withIndex()) { corner(e[0], s, p); corner(e[1], s, q); edge(c, p[0], p[1], p[2], q[0], q[1], q[2], per, 1, id0 + i) }
    }
    private fun cube(c: Cloud, r: Random) {
        val s = 0.72f
        cubeEdges(c, s, q(c, 0.5f), 0)
        while (c.count < c.n) {
            val f = r.nextInt(6); val a = (r.nextFloat() * 2 - 1) * s; val b = (r.nextFloat() * 2 - 1) * s; val sg = if (f % 2 == 0) s else -s
            when (f / 2) { 0 -> c.add(sg, a, b, 0, -1f); 1 -> c.add(a, sg, b, 0, -1f); else -> c.add(a, b, sg, 0, -1f) }
        }
        c.tilt = 0.5f; c.spin = 0.5f
    }

    // 14. लहराती जाली (सुनना)
    private fun waveGrid(c: Cloud, r: Random) {
        // गोल किनारे वाली जाली (कोने बाहर न निकलें)
        val side = (sqrt(c.n * 4f / PI.toFloat()) + 1f).toInt().coerceAtLeast(2)
        for (i in 0 until side) for (j in 0 until side) {
            val x = -1.1f + 2.2f * i / (side - 1); val z = -1.1f + 2.2f * j / (side - 1)
            if (x * x + z * z > 1.21f) continue
            if (!c.add(x, 0f, z, if (i % 4 == 0 || j % 4 == 0) 1 else 0, 0f)) return
        }
        c.tilt = 0.6f; c.spin = 0.25f
    }

    // 15. एक के अंदर एक क्यूब (सोचना): बाहर वाला एक तरफ़, अंदर वाला उल्टा घूमे (anc: 0..11 बाहर, 20..31 अंदर)
    private fun nestCube(c: Cloud, r: Random) {
        cubeEdges(c, 0.78f, q(c, 0.55f), 0)
        cubeEdges(c, 0.4f, q(c, 0.33f), 20)
        val core = (c.n - c.count).coerceAtLeast(0)
        for (i in 0 until core) { fibPt(i, core, tmp); c.add(tmp[0] * 0.15f, tmp[1] * 0.15f, tmp[2] * 0.15f, 0, -1f) }
        c.tilt = 0.45f; c.spin = 0.35f
    }

    // 16. आवाज़ के खंभे (बोलना): गोल घेरे में खड़ी पट्टियाँ (anc = खंभा + u)
    private const val COLS = 32
    private fun eqCyl(c: Cloud, r: Random) {
        val per = (c.n / COLS).coerceAtLeast(1)
        for (k in 0 until COLS) for (j in 0 until per) {
            val u = (j + 0.5f) / per; val th = TAU * k / COLS
            if (!c.add(cos(th) * 0.9f, (u * 2 - 1) * 0.5f, sin(th) * 0.9f, if (j == 0 || j == per - 1) 1 else 0, k + u.coerceAtMost(0.999f))) return
        }
        c.tilt = 0.3f; c.spin = 0.45f
    }

    // 17. छल्ले वाला ग्रह (सामान्य)
    private fun planet(c: Cloud, r: Random) {
        val body = q(c, 0.55f)
        for (i in 0 until body) { fibPt(i, body, tmp); val band = sin(tmp[1] * 9f) > 0.55f; c.add(tmp[0] * 0.62f, tmp[1] * 0.62f, tmp[2] * 0.62f, if (band) 1 else 0, -1f) }
        while (c.count < c.n) {
            val rr = 0.85f + 0.42f * r.nextFloat(); val th = r.nextFloat() * TAU
            c.add(cos(th) * rr, (r.nextFloat() - 0.5f) * 0.015f, sin(th) * rr, if (rr > 1.2f || abs(rr - 1.0f) < 0.015f) 1 else 0, rr)
        }
        c.tilt = 0.45f; c.spin = 0.5f
    }

    // 18. अंदर आती लहरें (सुनना): सामने की ओर गोल डिस्क, लहरें बाहर से बीच की ओर
    private fun inWave(c: Cloud, r: Random) {
        val n = c.n
        for (i in 0 until n) {
            val rr = 1.15f * sqrt((i + 0.5f) / n); val th = GOLDEN * i
            c.add(cos(th) * rr, sin(th) * rr, 0f, if (i % 8 == 0) 1 else 0, rr)
        }
        c.tilt = 0f; c.spin = 0f
    }

    // 19. इलेक्ट्रॉन का झुंड (सोचना): तीन कक्षाएँ, धूमकेतु जैसे दौड़ते डॉट + कोर
    private fun swarm(c: Cloud, r: Random) {
        val core = q(c, 0.14f)
        for (i in 0 until core) { fibPt(i, core, tmp); c.add(tmp[0] * 0.25f, tmp[1] * 0.25f, tmp[2] * 0.25f, 1, -1f) }
        val per = ((c.n - c.count) / 3).coerceAtLeast(1)
        for (k in 0 until 3) for (j in 0 until per) {
            if (!c.add(0f, 0f, 0f, if (j % 5 == 0) 1 else 0, k + ((j + 0.5f) / per).coerceAtMost(0.999f))) return
        }
        c.tilt = 0.3f; c.spin = 0.3f
    }
    private fun swarmAt(a: Float, t: Float, out: FloatArray) {
        val k = floor(a).toInt(); val u0 = a - k
        val u = frac(u0 + t * (0.18f + 0.07f * k)); val th = u * TAU
        val ex = cos(th) * 1.1f; val ey = sin(th) * 0.42f
        val rot = k * PI.toFloat() / 3f
        out[0] = ex * cos(rot) - ey * sin(rot); out[1] = ex * sin(rot) + ey * cos(rot); out[2] = sin(th) * 0.3f * (if (k == 1) -1f else 1f)
        val head = frac(t * 0.5f + k * 0.33f); var d = u - head; if (d < 0f) d += 1f
        out[3] = if (d > 0.82f) 0.5f + 1.3f * (d - 0.82f) / 0.18f else 0.4f + 0.3f * exp(-d * 8f)
    }

    // 20. लहराता गोला (बोलना): सतह पर बाहर फैलती लहरें
    private fun waveSphere(c: Cloud, r: Random) {
        val n = c.n
        for (i in 0 until n) { fibPt(i, n, tmp); c.add(tmp[0] * 0.85f, tmp[1] * 0.85f, tmp[2] * 0.85f, if (i % 7 == 0) 1 else 0, 0f) }
        c.tilt = 0.35f; c.spin = 0.6f
    }

    // ------------------------------------------------------------ हर frame की चाल
    /**
     * [code] = [code] से मिला नंबर. [x],[y],[z] असली जगह, [a] = Cloud.anc, [t] = एनीमेशन समय, [amp] = आवाज़ 0..1.
     * out[0..2] = नई जगह, out[3] = चमक (1 = सामान्य).
     */
    fun move(code: Int, i: Int, x: Float, y: Float, z: Float, a: Float, t: Float, amp: Float, out: FloatArray) {
        out[0] = x; out[1] = y; out[2] = z; out[3] = 1f
        when (code - 100) {
            CRYSTAL -> {
                val s = if (a <= -1.5f) 1f + 0.18f * sin(t * 2.4f) else 1f + 0.03f * sin(t * 1.2f)
                out[0] = x * s; out[1] = y * s; out[2] = z * s
                val sweep = sin(t * 0.9f) * 1.2f
                out[3] = 0.65f + 0.9f * exp(-(y - sweep) * (y - sweep) / 0.03f)
            }
            RINGS -> {
                val k = a; val r0 = sqrt(x * x + y * y).coerceAtLeast(0.01f)
                val ph = frac((k + 0.5f) / RING_N - t * (0.28f + 0.5f * amp))
                val nr = 0.15f + 1.05f * ph
                out[0] = x / r0 * nr; out[1] = y / r0 * nr
                out[2] = z + 0.08f * amp * sin(t * 9f + k)
                out[3] = (0.35f + 1.1f * ph) * (0.8f + 0.6f * amp)
            }
            GYRO -> {
                if (a < 0f) { val s = 1f + 0.14f * sin(t * 4f); out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = 0.8f + 0.5f * sin(t * 4f); return }
                val k = floor(a).toInt(); val u = a - k
                val ang = t * (1.1f + 0.45f * k) * (if (k == 1) -1f else 1f)
                val cs = cos(ang); val sn = sin(ang)
                when (k) {
                    0 -> { out[1] = y * cs - z * sn; out[2] = y * sn + z * cs }       // X अक्ष पर
                    1 -> { out[0] = x * cs + z * sn; out[2] = -x * sn + z * cs }      // Y अक्ष पर
                    else -> { out[0] = x * cs - y * sn; out[1] = x * sn + y * cs }    // Z अक्ष पर
                }
                val head = frac(t * 0.45f + k * 0.3f); var d = abs(u - head); if (d > 0.5f) d = 1f - d
                out[3] = 0.45f + 1.3f * exp(-d * d / 0.003f)
            }
            EQ_RING -> {
                val rot = t * 0.25f; val cr = cos(rot); val sr = sin(rot)
                if (a < 0f) {
                    val s = 1f + 0.06f * amp
                    out[0] = (x * cr - y * sr) * s; out[1] = (x * sr + y * cr) * s; out[3] = 0.9f + 0.4f * amp; return
                }
                val b = floor(a).toInt(); val u = a - b
                val th = TAU * b / BARS
                val len = 0.08f + (0.15f + 0.85f * amp) * (0.35f + 0.65f * abs(sin(t * (5f + 3f * hash(b)) + b * 0.9f)))
                val rad = 0.58f + 0.5f * len * u
                val cx = cos(th) * rad; val cy = sin(th) * rad
                out[0] = cx * cr - cy * sr; out[1] = cx * sr + cy * cr
                out[3] = 0.55f + 0.9f * u * (0.5f + amp)
            }
            SPHERE -> {
                val s = 1f + 0.03f * sin(t * 1.4f)
                out[0] = x * s; out[1] = y * s; out[2] = z * s
                out[3] = 0.7f + 0.6f * (0.5f + 0.5f * sin(t * 1.6f + a * TAU)).let { it * it }
            }
            VORTEX -> { vortexAt(a, t * (1f + amp), out); out[3] *= 0.8f + 0.5f * amp }
            HELIX -> {
                if (a >= 10f) { out[3] = 0.55f + 0.35f * sin(t * 3f + a); return }
                val u = a - floor(a)
                val head = frac(t * 0.35f); var d = abs(u - head); if (d > 0.5f) d = 1f - d
                out[3] = 0.5f + 1.3f * exp(-d * d / 0.004f)
            }
            SPIKES -> {
                if (a < 0f) { val s = 1f + 0.08f * amp; out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = 0.8f + 0.3f * amp; return }
                val id = floor(a).toInt(); val u = a - id
                val d = sqrt(x * x + y * y + z * z).coerceAtLeast(0.01f)
                val len = 0.12f + (0.2f + 0.8f * amp) * (0.4f + 0.6f * abs(sin(t * (6f + 4f * hash(id)) + id)))
                val rr = 0.64f + len * 0.5f * u
                out[0] = x / d * rr; out[1] = y / d * rr; out[2] = z / d * rr
                out[3] = 0.6f + 0.9f * u
            }
            LOTUS_F -> {
                if (a < 0f) { val s = 1f + 0.15f * sin(t * 2f); out[0] = x * s; out[1] = y * s + 0.0f; out[2] = z * s; out[3] = 1.1f + 0.3f * sin(t * 2f); return }
                val layer = floor(a)
                val bloom = 0.5f + 0.5f * sin(t * 0.7f - layer * 0.6f)
                val h = y + 0.35f
                out[1] = -0.35f + h * (0.75f + 0.45f * bloom)
                val s = 1f + 0.08f * bloom
                out[0] = x * s; out[2] = z * s
                out[3] = 0.7f + 0.5f * bloom
            }
            RIPPLE -> {
                val rr = a
                val w = sin(rr * 11f - t * 4.5f)
                out[1] = (0.05f + 0.28f * amp) * w * (1f - 0.45f * rr)
                out[3] = 0.6f + 0.7f * (0.5f + 0.5f * w)
            }
            INFINITY -> infAt(a, t, out)
            SHELLS -> {
                val s0 = a
                val pulse = frac(t * (0.5f + 0.6f * amp) - s0 * 0.33f)
                val sc = 0.82f + (0.2f + 0.3f * amp) * pulse
                out[0] = x * sc; out[1] = y * sc; out[2] = z * sc
                out[3] = 0.4f + 1.1f * (1f - pulse) * (0.6f + 0.6f * amp)
            }
            CUBE -> {
                val s = 1f + 0.025f * sin(t * 1.3f)
                out[0] = x * s; out[1] = y * s; out[2] = z * s
                if (a >= 0f) {
                    val u = a - floor(a); val head = frac(t * 0.5f + hash(floor(a).toInt()))
                    var d = abs(u - head); if (d > 0.5f) d = 1f - d
                    out[3] = 0.6f + 1.1f * exp(-d * d / 0.01f)
                } else out[3] = 0.6f
            }
            WAVEGRID -> {
                val w = sin(x * 4.5f + t * 3f) * cos(z * 4.5f - t * 2.4f)
                val d = sqrt(x * x + z * z)
                out[1] = (0.06f + 0.3f * amp) * w * (1.1f - 0.5f * d).coerceAtLeast(0f)
                out[3] = 0.55f + 0.8f * (0.5f + 0.5f * w)
            }
            NESTCUBE -> {
                if (a >= 20f || a <= -1f) {
                    val ang = -t * 1.6f; val cs = cos(ang); val sn = sin(ang)
                    val x1 = x * cs + z * sn; val z1 = -x * sn + z * cs
                    val ang2 = t * 1.1f; val c2 = cos(ang2); val s2 = sin(ang2)
                    out[0] = x1; out[1] = y * c2 - z1 * s2; out[2] = y * s2 + z1 * c2
                    out[3] = if (a <= -1f) 1.2f + 0.4f * sin(t * 5f) else 1.0f + 0.4f * sin(t * 3f + a)
                } else {
                    val u = a - floor(a); val head = frac(t * 0.6f + hash(floor(a).toInt()))
                    var d = abs(u - head); if (d > 0.5f) d = 1f - d
                    out[3] = 0.5f + 1.2f * exp(-d * d / 0.008f)
                }
            }
            EQ_CYL -> {
                val k = floor(a).toInt(); val u = a - k
                val h = 0.1f + (0.15f + 0.85f * amp) * (0.35f + 0.65f * abs(sin(t * (4.5f + 3f * hash(k)) + k * 0.7f)))
                out[1] = (u * 2f - 1f) * h * 1.1f
                out[3] = 0.6f + 0.8f * abs(u * 2f - 1f)
            }
            PLANET -> {
                if (a > 0f) { out[3] = 0.55f + 0.6f * (0.5f + 0.5f * sin(a * 40f - t * 2f)) }
                else { val s = 1f + 0.02f * sin(t * 1.2f); out[0] = x * s; out[1] = y * s; out[2] = z * s }
            }
            INWAVE -> {
                val rr = a
                val w = sin(rr * 10f + t * (4f + 3f * amp))
                out[2] = (0.04f + 0.22f * amp) * w
                val s = 1f + (0.015f + 0.05f * amp) * w
                out[0] = x * s; out[1] = y * s
                out[3] = 0.55f + 0.8f * (0.5f + 0.5f * w)
            }
            SWARM -> {
                if (a < 0f) { val s = 1f + 0.12f * sin(t * 3.5f); out[0] = x * s; out[1] = y * s; out[2] = z * s; out[3] = 0.9f + 0.4f * sin(t * 3.5f); return }
                swarmAt(a, t, out)
            }
            WAVESPHERE -> {
                val lon = kotlin.math.atan2(z, x)
                val w = sin(y * 7f - t * 6f) * 0.6f + sin(lon * 5f + t * 3f) * 0.4f
                val s = 1f + (0.03f + 0.17f * amp) * w
                out[0] = x * s; out[1] = y * s; out[2] = z * s
                out[3] = 0.6f + 0.7f * (0.5f + 0.5f * w)
            }
        }
    }
}
