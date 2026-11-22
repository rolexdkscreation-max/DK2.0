package com.dk.assistant.livewall

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 22 नवंबर 2026 (मालिक: "सूरज जैसे निकलता है, 12 बजे बीच में, शाम को ढलता है; चाँद जैसे निकलेगा उस हिसाब से"):
 * असली खगोल-गणित - फ़ोन के समय और जगह (अक्षांश/देशांतर) से सूरज, चाँद और तारों की असली जगह.
 *  - सूरज: Astronomical Almanac का कम-सटीक सूत्र (~0.01°)
 *  - चाँद: Paul Schlyter का तरीका + मुख्य perturbation (~0.3°), कला (phase) और रोशन हिस्सा
 *  - तारे: RA/Dec (J2000) -> ऊँचाई / दिशा (स्थानीय नक्षत्र-समय से)
 * Android-free (JUnit से जाँचा जाता है).
 */
object SkyAstro {
    const val RAD = PI / 180.0
    const val DEG = 180.0 / PI

    /** आसमान में किसी पिंड की जगह: ऊँचाई (°, क्षितिज = 0), दिशा (°, उत्तर = 0, पूर्व = 90), hour angle (°, -180..180), उगने का hour angle */
    data class Pos(val alt: Double, val az: Double, val ha: Double, val riseHa: Double, val ra: Double, val dec: Double)

    data class Moon(val pos: Pos, val illum: Double, val phaseAngle: Double, val waxing: Boolean, val distEarthRadii: Double)

    fun julian(ms: Long): Double = ms / 86_400_000.0 + 2_440_587.5

    fun norm360(x: Double): Double { val r = x % 360.0; return if (r < 0) r + 360.0 else r }
    fun norm180(x: Double): Double { val r = norm360(x); return if (r > 180.0) r - 360.0 else r }

    /** Greenwich mean sidereal time (°) */
    fun gmst(jd: Double): Double {
        val d = jd - 2_451_545.0
        val t = d / 36_525.0
        return norm360(280.46061837 + 360.98564736629 * d + 0.000387933 * t * t)
    }

    fun lst(jd: Double, lonDeg: Double): Double = norm360(gmst(jd) + lonDeg)

    /** RA/Dec (°) -> ऊँचाई/दिशा */
    fun horizontal(raDeg: Double, decDeg: Double, latDeg: Double, lstDeg: Double): DoubleArray {
        val ha = norm180(lstDeg - raDeg)
        val h = ha * RAD; val d = decDeg * RAD; val p = latDeg * RAD
        val sinAlt = sin(p) * sin(d) + cos(p) * cos(d) * cos(h)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val az = atan2(sin(h), cos(h) * sin(p) - tan(d) * cos(p)) + PI
        return doubleArrayOf(alt * DEG, norm360(az * DEG), ha)
    }

    /** किसी ऊँचाई h0 पर उगने/डूबने का hour angle (°). 0 = कभी नहीं उगता, 180 = कभी नहीं डूबता */
    fun riseHourAngle(decDeg: Double, latDeg: Double, h0Deg: Double): Double {
        val p = latDeg * RAD; val d = decDeg * RAD
        val c = (sin(h0Deg * RAD) - sin(p) * sin(d)) / (cos(p) * cos(d))
        return when { c >= 1.0 -> 0.0; c <= -1.0 -> 180.0; else -> acos(c) * DEG }
    }

    /** वायुमंडल का अपवर्तन (Bennett) - क्षितिज के पास पिंड थोड़ा ऊपर दिखता है (°) */
    fun refraction(altDeg: Double): Double {
        if (altDeg < -2.0) return 0.0
        val h = altDeg.coerceAtLeast(-1.9)
        return 1.02 / tan((h + 10.3 / (h + 5.11)) * RAD) / 60.0
    }

    /** सूरज: RA, Dec, ecliptic देशांतर (°) */
    fun sunEq(jd: Double): DoubleArray {
        val d = jd - 2_451_545.0
        val g = norm360(357.529 + 0.98560028 * d) * RAD
        val q = norm360(280.459 + 0.98564736 * d)
        val l = norm360(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = (23.439 - 0.00000036 * d) * RAD
        val lr = l * RAD
        val ra = norm360(atan2(cos(e) * sin(lr), cos(lr)) * DEG)
        val dec = asin(sin(e) * sin(lr)) * DEG
        return doubleArrayOf(ra, dec, l)
    }

    fun sun(ms: Long, latDeg: Double, lonDeg: Double): Pos {
        val jd = julian(ms)
        val (ra, dec) = sunEq(jd).let { it[0] to it[1] }
        val hz = horizontal(ra, dec, latDeg, lst(jd, lonDeg))
        val alt = hz[0] + refraction(hz[0])
        return Pos(alt, hz[1], hz[2], riseHourAngle(dec, latDeg, -0.833), ra, dec)
    }

    /** चाँद: ecliptic देशांतर, अक्षांश (°), दूरी (पृथ्वी-त्रिज्या) - Schlyter */
    fun moonEcl(jd: Double): DoubleArray {
        val d = jd - 2_451_543.5
        val n = norm360(125.1228 - 0.0529538083 * d) * RAD
        val i = 5.1454 * RAD
        val w = norm360(318.0634 + 0.1643573223 * d) * RAD
        val a = 60.2666; val e = 0.054900
        val mDeg = norm360(115.3654 + 13.0649929509 * d)
        val m = mDeg * RAD
        var ea = m + e * sin(m) * (1.0 + e * cos(m))
        repeat(5) { ea -= (ea - e * sin(ea) - m) / (1 - e * cos(ea)) }
        val xv = a * (cos(ea) - e); val yv = a * sqrt(1 - e * e) * sin(ea)
        val v = atan2(yv, xv); var r = sqrt(xv * xv + yv * yv)
        val xh = r * (cos(n) * cos(v + w) - sin(n) * sin(v + w) * cos(i))
        val yh = r * (sin(n) * cos(v + w) + cos(n) * sin(v + w) * cos(i))
        val zh = r * (sin(v + w) * sin(i))
        var lon = atan2(yh, xh) * DEG
        var lat = atan2(zh, sqrt(xh * xh + yh * yh)) * DEG
        val ms = norm360(356.0470 + 0.9856002585 * d)
        val ws = 282.9404 + 4.70935e-5 * d
        val ls = ms + ws
        val lm = n * DEG + w * DEG + mDeg
        val dd = lm - ls; val f = lm - n * DEG
        fun s(x: Double) = sin(x * RAD)
        fun c(x: Double) = cos(x * RAD)
        lon += -1.274 * s(mDeg - 2 * dd) + 0.658 * s(2 * dd) - 0.186 * s(ms) - 0.059 * s(2 * mDeg - 2 * dd) -
            0.057 * s(mDeg - 2 * dd + ms) + 0.053 * s(mDeg + 2 * dd) + 0.046 * s(2 * dd - ms) + 0.041 * s(mDeg - ms) -
            0.035 * s(dd) - 0.031 * s(mDeg + ms) - 0.015 * s(2 * f - 2 * dd) + 0.011 * s(mDeg - 4 * dd)
        lat += -0.173 * s(f - 2 * dd) - 0.055 * s(mDeg - f - 2 * dd) - 0.046 * s(mDeg + f - 2 * dd) + 0.033 * s(f + 2 * dd) + 0.017 * s(2 * mDeg + f)
        r += -0.58 * c(mDeg - 2 * dd) - 0.46 * c(2 * dd)
        return doubleArrayOf(norm360(lon), lat, r)
    }

    fun eclToEq(lonDeg: Double, latDeg: Double, jd: Double): DoubleArray {
        val d = jd - 2_451_543.5
        val ecl = (23.4393 - 3.563e-7 * d) * RAD
        val l = lonDeg * RAD; val b = latDeg * RAD
        val x = cos(b) * cos(l)
        val y = cos(b) * sin(l) * cos(ecl) - sin(b) * sin(ecl)
        val z = cos(b) * sin(l) * sin(ecl) + sin(b) * cos(ecl)
        return doubleArrayOf(norm360(atan2(y, x) * DEG), atan2(z, sqrt(x * x + y * y)) * DEG)
    }

    fun moon(ms: Long, latDeg: Double, lonDeg: Double): Moon {
        val jd = julian(ms)
        val (mlon, mlat, dist) = moonEcl(jd).let { Triple(it[0], it[1], it[2]) }
        val (ra, dec) = eclToEq(mlon, mlat, jd).let { it[0] to it[1] }
        val hz = horizontal(ra, dec, latDeg, lst(jd, lonDeg))
        val par = asin(1.0 / dist) * DEG                       // क्षितिज-लंबन (~0.95°)
        val geoAlt = hz[0]
        val topo = geoAlt - par * cos(geoAlt * RAD)
        val alt = topo + refraction(topo)
        val slon = sunEq(jd)[2]
        val elong = acos((cos((slon - mlon) * RAD) * cos(mlat * RAD)).coerceIn(-1.0, 1.0)) * DEG
        val phaseAngle = 180.0 - elong                          // 0 = पूर्णिमा, 180 = अमावस
        val illum = (1 + cos(phaseAngle * RAD)) / 2
        val waxing = norm360(mlon - slon) < 180.0
        return Moon(Pos(alt, hz[1], hz[2], riseHourAngle(dec, latDeg, 0.125), ra, dec), illum, phaseAngle, waxing, dist)
    }

    /** दो दिशाओं (ऊँचाई/दिशा, °) के बीच का कोण (°) */
    fun angle(alt1: Double, az1: Double, alt2: Double, az2: Double): Double {
        val c = sin(alt1 * RAD) * sin(alt2 * RAD) + cos(alt1 * RAD) * cos(alt2 * RAD) * cos((az1 - az2) * RAD)
        return acos(c.coerceIn(-1.0, 1.0)) * DEG
    }

    /** सूरज/चाँद का पर्दे पर x: उगने (-H0) से डूबने (+H0) तक बाएँ से दाएँ, बीच = आसमान में सबसे ऊपर (दोपहर) */
    fun pathFraction(haDeg: Double, riseHaDeg: Double): Double {
        val h0 = if (riseHaDeg < 1.0) 1.0 else riseHaDeg
        return haDeg / h0
    }

    fun approxEq(a: Double, b: Double, tol: Double) = abs(a - b) <= tol
}
