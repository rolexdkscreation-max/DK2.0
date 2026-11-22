package com.dk.assistant.livewall

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 22 नवंबर 2026 (मालिक: "आसमान एकदम रियलिस्टिक, शाम ढलते समय जैसा रंग रहता है वैसा, 12 बजे तेज़ धूप, रात में ठंडक"):
 * आसमान का रंग भौतिकी से - Rayleigh (नीला), Mie (धूल/धुंध, सूरज के पास चमक), Ozone (शाम का गहरा नीला),
 * पृथ्वी की छाया (गोधूलि). Nishita का single-scattering तरीका, एक छोटी तालिका (LUT) में - फिर हर पिक्सेल पर
 * सिर्फ़ तालिका से पढ़ना (तेज़). सारे नंबर Bruneton 2017 / Hillaire 2020 वाले पृथ्वी-वायुमंडल के.
 * Android-free (JUnit से जाँचा जाता है).
 */
object SkyLight {
    private const val RE = 6_360_000.0
    private const val RA = 6_420_000.0
    private const val HR = 8_000.0
    private const val HM = 1_200.0
    private const val OBS_H = 200.0
    private val BR = doubleArrayOf(5.802e-6, 13.558e-6, 33.1e-6)
    private const val BM_S = 3.996e-6
    private const val BM_E = 4.40e-6
    private val BO = doubleArrayOf(0.650e-6, 1.881e-6, 0.085e-6)
    private const val G = 0.78
    private const val N_VIEW = 20
    private const val N_LIGHT = 8

    const val N_EL = 40
    const val N_G = 36

    /** तालिका: ऊँचाई (0..90°, क्षितिज के पास घनी) x सूरज से कोण (0..180°, सूरज के पास घनी), हर खाने में RGB */
    class Lut(val sourceAlt: Double, val data: FloatArray)

    private fun ozone(h: Double): Double = max(0.0, 1.0 - abs(h - 25_000.0) / 15_000.0)

    /** गोले से किरण का दूर वाला कटान (o गोले के अंदर) */
    private fun far(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, r: Double): Double {
        val b = ox * dx + oy * dy + oz * dz
        val c = ox * ox + oy * oy + oz * oz - r * r
        val d = b * b - c
        return if (d < 0) 0.0 else -b + sqrt(d)
    }

    /** ज़मीन से टकराती है? (रोशनी की किरण पृथ्वी की छाया में) */
    private fun hitsGround(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double): Boolean {
        val b = ox * dx + oy * dy + oz * dz
        val c = ox * ox + oy * oy + oz * oz - RE * RE
        val d = b * b - c
        return d > 0 && -b - sqrt(d) > 0
    }

    /** एक दिशा का आसमानी रंग (रैखिक, स्रोत की तीव्रता = 1) - viewAlt, sourceAlt (°), gamma = दोनों के बीच कोण (°) */
    fun scatter(viewAlt: Double, gammaDeg: Double, sourceAlt: Double, out: DoubleArray) {
        val e = viewAlt * SkyAstro.RAD; val se = sourceAlt * SkyAstro.RAD; val g = gammaDeg * SkyAstro.RAD
        val sx = cos(se); val sy = sin(se); val sz = 0.0
        val denom = cos(e) * cos(se)
        val cphi = if (abs(denom) < 1e-9) 1.0 else ((cos(g) - sin(e) * sin(se)) / denom).coerceIn(-1.0, 1.0)
        val sphi = sqrt(max(0.0, 1 - cphi * cphi))
        val vx = cos(e) * cphi; val vy = sin(e); val vz = cos(e) * sphi
        val mu = vx * sx + vy * sy + vz * sz
        val oy0 = RE + OBS_H
        val tMax = far(0.0, oy0, 0.0, vx, vy, vz, RA)
        val step = tMax / N_VIEW
        var odR = 0.0; var odM = 0.0; var odO = 0.0
        var sR0 = 0.0; var sR1 = 0.0; var sR2 = 0.0; var sM0 = 0.0; var sM1 = 0.0; var sM2 = 0.0
        for (i in 0 until N_VIEW) {
            val t = (i + 0.5) * step
            val px = vx * t; val py = oy0 + vy * t; val pz = vz * t
            val h = sqrt(px * px + py * py + pz * pz) - RE
            val dR = exp(-h / HR) * step; val dM = exp(-h / HM) * step
            odR += dR; odM += dM; odO += ozone(h) * step
            if (hitsGround(px, py, pz, sx, sy, sz)) continue
            val tl = far(px, py, pz, sx, sy, sz, RA)
            val ls = tl / N_LIGHT
            var lR = 0.0; var lM = 0.0; var lO = 0.0
            for (j in 0 until N_LIGHT) {
                val u = (j + 0.5) * ls
                val qx = px + sx * u; val qy = py + sy * u; val qz = pz + sz * u
                val hl = sqrt(qx * qx + qy * qy + qz * qz) - RE
                val r = exp(-hl / HR) * ls
                lR += r; lM += exp(-hl / HM) * ls; lO += ozone(hl) * ls
            }
            val tm = BM_E * (odM + lM)
            val a0 = exp(-(BR[0] * (odR + lR) + tm + BO[0] * (odO + lO)))
            val a1 = exp(-(BR[1] * (odR + lR) + tm + BO[1] * (odO + lO)))
            val a2 = exp(-(BR[2] * (odR + lR) + tm + BO[2] * (odO + lO)))
            sR0 += a0 * dR; sR1 += a1 * dR; sR2 += a2 * dR
            sM0 += a0 * dM; sM1 += a1 * dM; sM2 += a2 * dM
        }
        val pR = 3.0 / (16.0 * PI) * (1 + mu * mu)
        val pM = 3.0 / (8.0 * PI) * ((1 - G * G) * (1 + mu * mu)) / ((2 + G * G) * (1 + G * G - 2 * G * mu).pow(1.5))
        out[0] = sR0 * BR[0] * pR + sM0 * BM_S * pM
        out[1] = sR1 * BR[1] * pR + sM1 * BM_S * pM
        out[2] = sR2 * BR[2] * pR + sM2 * BM_S * pM
    }

    fun elAt(i: Int): Double { val f = i / (N_EL - 1.0); return 90.0 * f * f }
    fun gAt(j: Int): Double { val f = j / (N_G - 1.0); return 180.0 * f.pow(1.7) }

    fun lut(sourceAlt: Double): Lut {
        val d = FloatArray(N_EL * N_G * 3)
        val o = DoubleArray(3)
        for (i in 0 until N_EL) for (j in 0 until N_G) {
            scatter(max(elAt(i), 0.05), gAt(j), sourceAlt, o)
            val k = (i * N_G + j) * 3
            d[k] = o[0].toFloat(); d[k + 1] = o[1].toFloat(); d[k + 2] = o[2].toFloat()
        }
        return Lut(sourceAlt, d)
    }

    /** तालिका से (bilinear) - el 0..90, gamma 0..180 */
    fun sample(l: Lut, el: Double, gammaDeg: Double, out: FloatArray) {
        val fe = sqrt((el / 90.0).coerceIn(0.0, 1.0)) * (N_EL - 1)
        val fg = (gammaDeg / 180.0).coerceIn(0.0, 1.0).pow(1.0 / 1.7) * (N_G - 1)
        val i0 = min(fe.toInt(), N_EL - 2); val j0 = min(fg.toInt(), N_G - 2)
        val ti = (fe - i0).toFloat(); val tj = (fg - j0).toFloat()
        val d = l.data
        for (c in 0..2) {
            val a = d[(i0 * N_G + j0) * 3 + c]; val b = d[(i0 * N_G + j0 + 1) * 3 + c]
            val cc = d[((i0 + 1) * N_G + j0) * 3 + c]; val dd = d[((i0 + 1) * N_G + j0 + 1) * 3 + c]
            out[c] = (a + (b - a) * tj) * (1 - ti) + (cc + (dd - cc) * tj) * ti
        }
    }

    /** सीधी धूप का रंग (वायुमंडल से गुज़रकर) - क्षितिज पर नारंगी-लाल, ऊपर सफ़ेद. RGB 0..1 */
    fun transmittance(alt: Double): DoubleArray {
        val e = max(alt, -1.0) * SkyAstro.RAD
        val dx = cos(e); val dy = sin(e)
        val oy0 = RE + OBS_H
        val t = far(0.0, oy0, 0.0, dx, dy, 0.0, RA)
        val n = 40; val s = t / n
        var r = 0.0; var m = 0.0; var o = 0.0
        for (i in 0 until n) {
            val u = (i + 0.5) * s
            val h = sqrt((dx * u) * (dx * u) + (oy0 + dy * u) * (oy0 + dy * u)) - RE
            val rr = exp(-h / HR) * s
            r += rr; m += exp(-h / HM) * s; o += ozone(h) * s
        }
        return DoubleArray(3) { exp(-(BR[it] * r + BM_E * m + BO[it] * o)) }
    }

    /**
     * कैमरे जैसा exposure: दिन में कम, सूरज डूबते-डूबते बढ़ता है (शाम का आसमान दिखे), रात में तय सीमा तक
     * (रात रात जैसी अँधेरी - नीली-काली, तारे दिखें).
     */
    fun exposure(sunAlt: Double): Double {
        val day = 22.0
        if (sunAlt >= 4.0) return day
        val below = (4.0 - sunAlt).coerceAtMost(22.0)
        return day * 10.0.pow(below * 0.105)
    }

    fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** रात का आधार (तोन-मैप के बाद, रैखिक): ऊपर गहरा नीला-काला, क्षितिज पर थोड़ा हल्का + शहर की हल्की गरम रोशनी */
    fun nightFloor(el: Double, night: Double, out: FloatArray) {
        val h = exp(-max(el, 0.0) / 9.0)
        val z = 1 - h
        out[0] = (night * (0.0010 * z + 0.0045 * h + 0.0040 * exp(-max(el, 0.0) / 4.0))).toFloat()
        out[1] = (night * (0.0016 * z + 0.0060 * h + 0.0024 * exp(-max(el, 0.0) / 4.0))).toFloat()
        out[2] = (night * (0.0042 * z + 0.0105 * h + 0.0012 * exp(-max(el, 0.0) / 4.0))).toFloat()
    }

    /** रात कितनी (0 = दिन/शाम, 1 = पूरी रात) */
    fun night(sunAlt: Double): Double = smooth(-5.0, -16.0, sunAlt)

    /** फ़िल्मी तोन-मैप: चमक कितनी भी हो, रंग 0..1 में, हल्का कंधा */
    fun tone(x: Double): Double = 1.0 - exp(-x)

    fun toSrgb(x: Double): Double = if (x <= 0.0031308) 12.92 * x else 1.055 * x.coerceAtMost(1.0).pow(1 / 2.4) - 0.055

    /** हर पिक्सेल के लिए तेज़: रैखिक 0..1 -> sRGB 0..255 (4096 खानों की तालिका) */
    val SRGB: IntArray by lazy { IntArray(4097) { (toSrgb(it / 4096.0) * 255.0 * 256).toInt() } }   // x256 = dither के लिए भिन्न भी

    fun gammaBetween(alt1: Double, alt2: Double, dAzDeg: Double): Double {
        val c = sin(alt1 * SkyAstro.RAD) * sin(alt2 * SkyAstro.RAD) + cos(alt1 * SkyAstro.RAD) * cos(alt2 * SkyAstro.RAD) * cos(dAzDeg * SkyAstro.RAD)
        return acos(c.coerceIn(-1.0, 1.0)) * SkyAstro.DEG
    }
}
