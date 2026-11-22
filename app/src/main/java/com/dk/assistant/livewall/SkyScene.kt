package com.dk.assistant.livewall

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 22 नवंबर 2026 - "सूर्योदय-सूर्यास्त" लाइव वॉलपेपर का पूरा दृश्य.
 * असली समय + असली जगह (lat/lon) से सूरज-चाँद-तारे उसी जगह जहाँ असल में होते हैं; आसमान का रंग भौतिकी
 * से (SkyLight); पेड़-पहाड़ उसी रोशनी में रंगे; झील में आसमान/सूरज/चाँद की परछाईं हिलती लहरों के साथ.
 * यह फ़ाइल Android-free है: चित्र सिर्फ़ [SkyGfx] से बनते हैं (फ़ोन पर Canvas, टेस्ट/preview में सॉफ़्टवेयर).
 */
interface SkyGfx {
    /** id वाली तस्वीर का src हिस्सा dst पर; रंग = px*mul/255 + add (LightingColorFilter जैसा) */
    @Suppress("LongParameterList")
    fun layer(
        id: String, sx: Float, sy: Float, sw: Float, sh: Float,
        dx: Float, dy: Float, dw: Float, dh: Float,
        mul: Int, add: Int, alpha: Float, flipV: Boolean,
    )

    /** जोड़ने वाली (additive) गोल/अंडाकार चमक - gaussian जैसा फैलाव */
    @Suppress("LongParameterList")
    fun glow(x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Float)

    /** तारे जैसा छोटा चमकीला बिंदु (additive) */
    fun dot(x: Float, y: Float, r: Float, color: Int, alpha: Float)

    /** सामान्य आयत */
    @Suppress("LongParameterList")
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float)

    /** सॉफ़्टवेयर से बनी तस्वीर (आसमान, सूरज, चाँद) अपलोड करो - ARGB, straight alpha */
    fun upload(id: String, px: IntArray, w: Int, h: Int)
}

class SkyScene(
    private val pack: SkyPack,
    private val stars: List<Star>,
    /** चाँद की असली (NASA) तस्वीर - वर्ग, गोला बीच में; null = सादा धूसर */
    private val moonTex: IntArray?, private val moonTexSize: Int,
    /** पैक की तस्वीरों का आकार (w,h) */
    private val imgSize: (String) -> IntArray?,
    private val backdropScale: Int = 2,
) {
    data class Star(val ra: Double, val dec: Double, val mag: Double, val bv: Double)

    var lat = 18.63
    var lon = 73.80

    private var w = 0; private var h = 0
    private var hy = 0f
    private var pxDeg = 1f

    // ---------- अवस्था (हर ~1 सेकंड) ----------
    class State {
        var sunAlt = 0.0; var sunX = 0f; var sunY = 0f
        var moonAlt = -90.0; var moonX = 0f; var moonY = 0f; var illum = 0.0; var phase = 180.0
        var night = 0.0
        /** चाँद कितना दिखे (0 = दिन में बिल्कुल नहीं) - [SkyLight.moonVisible] */
        var moonVis = 0.0
        var sunCol = DoubleArray(3); var moonCol = DoubleArray(3)
        var amb = DoubleArray(3); var haze = DoubleArray(3); var haze8 = DoubleArray(3); var water = DoubleArray(3)
    }

    val state = State()
    private var lastAstro = Long.MIN_VALUE
    private var lastSky = Long.MIN_VALUE
    private var skyAlt = 999.0
    private var lastMoonSprite = Long.MIN_VALUE
    private var moonSpriteKey = ""
    private var sunSpriteKey = ""
    private var sunLut: SkyLight.Lut? = null
    private var moonLut: SkyLight.Lut? = null
    private var bw = 0; private var bh = 0
    private var starX = FloatArray(0); private var starY = FloatArray(0); private var starA = FloatArray(0)
    private var starR = FloatArray(0); private var starC = IntArray(0); private var starTw = FloatArray(0)
    private var starN = 0
    private val rnd = FloatArray(512) { hash(it) }

    /** फ़ोन पर: नया आसमान दूसरे thread पर बने (फ्रेम न अटके) - host renderBackdrop() चलाकर upload करे */
    var skyAsync: ((Int, Int) -> Unit)? = null
    private var hasSky = false

    private fun needSky(nowMs: Long): Boolean =
        !hasSky || abs(nowMs - lastSky) >= 30_000 || abs(skyAlt - state.sunAlt) > 0.12 || abs(skyMoon - state.moonAlt) > 1.0

    private var skyMoon = 0.0

    fun resize(width: Int, height: Int) {
        if (width == w && height == h) return
        w = width; h = height
        hy = pack.horizon * h
        pxDeg = hy / 90f
        bw = (w + backdropScale - 1) / backdropScale
        bh = ((hy + 2) / backdropScale).toInt() + 2
        reset()
    }

    /** जगह/समय अचानक बदला - सब दोबारा निकालो */
    fun reset() {
        lastAstro = Long.MIN_VALUE; lastSky = Long.MIN_VALUE; sunSpriteKey = ""; moonSpriteKey = ""; hasSky = false
    }

    val sunR get() = pack.sunSize * w
    val moonR get() = pack.moonSize * w

    /** ऊँचाई (°) -> स्क्रीन y */
    fun yOf(alt: Double): Float = (hy * (1 - alt / 90.0)).toFloat()

    /** घंटा-कोण -> x: उगते समय बाएँ (पूरब), दोपहर बीच में, डूबते समय दाएँ (पश्चिम) */
    fun xOf(ha: Double, riseHa: Double): Float {
        val f = ha / riseHa.coerceIn(30.0, 179.0)
        return (w * (0.5 + 0.37 * f)).toFloat()
    }

    /** टेस्ट/preview के लिए: अवस्था अभी निकालो */
    fun update(nowMs: Long) {
        val s = SkyAstro.sun(nowMs, lat, lon)
        val m = SkyAstro.moon(nowMs, lat, lon)
        val st = state
        st.sunAlt = s.alt; st.sunX = xOf(s.ha, s.riseHa); st.sunY = yOf(s.alt)
        st.moonAlt = m.pos.alt; st.moonX = xOf(m.pos.ha, m.pos.riseHa); st.moonY = yOf(m.pos.alt)
        st.illum = m.illum; st.phase = m.phaseAngle
        st.night = SkyLight.night(s.alt)
        st.moonVis = if (m.pos.alt > -1.5) SkyLight.moonVisible(s.alt) else 0.0
        val t = SkyLight.transmittance(s.alt)
        st.sunCol = normMax(t)
        st.moonCol = normMax(SkyLight.transmittance(m.pos.alt).let { tm -> DoubleArray(3) { 0.55 + 0.45 * tm[it] / max(tm[0], 1e-9) } })
        if (sunLut == null || abs(sunLut!!.sourceAlt - s.alt) > 0.12) sunLut = SkyLight.lut(s.alt.coerceAtLeast(-24.0))
        if (m.pos.alt > -3 && st.night > 0.01) {
            if (moonLut == null || abs(moonLut!!.sourceAlt - m.pos.alt) > 0.5) moonLut = SkyLight.lut(m.pos.alt)
        }
        lighting(st)
        computeStars(nowMs)
    }

    private fun normMax(c: DoubleArray): DoubleArray {
        val mx = max(c[0], max(c[1], c[2])).coerceAtLeast(1e-9)
        return DoubleArray(3) { c[it] / mx }
    }

    private val tmp = FloatArray(3)
    private val tmp2 = FloatArray(3)

    /** एक दिशा का अंतिम रंग (रैखिक 0..1, तोन-मैप हो चुका) */
    fun skyColor(el: Double, dAz: Double, out: DoubleArray) {
        val st = state
        val e = SkyLight.exposure(st.sunAlt)
        val sAz = (st.sunX - w / 2f) / pxDeg
        val g = SkyLight.gammaBetween(el, st.sunAlt, dAz - sAz)
        var r = 0.0; var gg = 0.0; var b = 0.0
        val sl = sunLut
        if (sl != null && st.sunAlt > -24) {
            SkyLight.sample(sl, el, g, tmp)
            r = tmp[0] * e; gg = tmp[1] * e; b = tmp[2] * e
        }
        // एक से ज़्यादा बार बिखरी रोशनी (गोधूलि का गहरा नीला) - single-scattering की कमी पूरी करती है
        val tw = SkyLight.smooth(-15.0, -3.0, st.sunAlt) * (1 - SkyLight.smooth(4.0, 18.0, st.sunAlt))
        if (tw > 0) {
            val k = tw * (0.20 + 0.16 * (el / 90.0)) * (1 - 0.6 * exp(-el / 6.0))
            r += 0.22 * k; gg += 0.42 * k; b += 1.0 * k
        }
        val ml = moonLut
        if (ml != null && st.moonAlt > -3 && st.night > 0.01) {
            val mAz = (st.moonX - w / 2f) / pxDeg
            val gm = SkyLight.gammaBetween(el, st.moonAlt, dAz - mAz)
            SkyLight.sample(ml, el, gm, tmp2)
            val k = st.night * MOON_SKY * st.illum.pow(1.5) * SkyLight.exposure(-30.0)
            r += tmp2[0] * k; gg += tmp2[1] * k * 0.98; b += tmp2[2] * k * 1.02
        }
        var tr = SkyLight.tone(r); var tg = SkyLight.tone(gg); var tb = SkyLight.tone(b)
        val y = 0.2126 * tr + 0.7152 * tg + 0.0722 * tb
        tr = y + (tr - y) * SAT; tg = y + (tg - y) * SAT; tb = y + (tb - y) * SAT
        SkyLight.nightFloor(el, st.night, tmp)
        out[0] = (tr + tmp[0]).coerceIn(0.0, 1.0)
        out[1] = (tg + tmp[1]).coerceIn(0.0, 1.0)
        out[2] = (tb + tmp[2]).coerceIn(0.0, 1.0)
    }

    private fun lighting(st: State) {
        val c = DoubleArray(3)
        val day = SkyLight.smooth(-7.0, 8.0, st.sunAlt)
        skyColor(40.0, 0.0, c)
        val sunK = sqrt(max(0.0, sin((st.sunAlt + 1.0) * SkyAstro.RAD))) * 0.80
        val moonK = if (st.moonAlt > 0) st.illum.pow(1.3) * 0.065 * st.night * sqrt(sin(st.moonAlt * SkyAstro.RAD)) else 0.0
        for (i in 0..2) {
            val sk = 0.5 * c[i] + 0.5 * (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2])
            st.amb[i] = (sk * 0.48 + st.sunCol[i] * sunK * day + moonK * doubleArrayOf(0.72, 0.82, 1.0)[i] +
                st.night * doubleArrayOf(0.030, 0.040, 0.062)[i]).coerceIn(0.0, 1.0)
        }
        skyColor(1.5, 0.0, c)
        for (i in 0..2) st.haze[i] = c[i]
        skyColor(8.0, 0.0, c)
        for (i in 0..2) st.haze8[i] = c[i]
        for (i in 0..2) st.water[i] = (c[i] * 0.20 + st.amb[i] * 0.12).coerceIn(0.0, 1.0)
    }

    // ---------- आसमान की पृष्ठभूमि (सॉफ़्टवेयर) ----------
    fun renderBackdrop(): IntArray {
        val px = IntArray(bw * bh)
        val c = DoubleArray(3)
        val srgb = SkyLight.SRGB
        for (by in 0 until bh) {
            val y = (by + 0.5f) * backdropScale
            val el = (90.0 * (hy - y) / hy).coerceIn(0.0, 90.0)
            for (bx in 0 until bw) {
                val x = (bx + 0.5f) * backdropScale
                skyColor(el, ((x - w / 2f) / pxDeg).toDouble(), c)
                val n = rnd[(bx * 7 + by * 13) and 511] - 0.5f
                val r = ((srgb[(c[0] * 4096).toInt()] + n * 256) / 256).toInt().coerceIn(0, 255)
                val g = ((srgb[(c[1] * 4096).toInt()] + n * 256) / 256).toInt().coerceIn(0, 255)
                val b = ((srgb[(c[2] * 4096).toInt()] + n * 256) / 256).toInt().coerceIn(0, 255)
                px[by * bw + bx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return px
    }

    // ---------- सूरज ----------
    private fun sunSprite(g: SkyGfx) {
        val st = state
        val hi = SkyLight.smooth(1.0, 30.0, st.sunAlt)
        val key = "${(hi * 40).toInt()}-${(st.sunCol[1] * 60).toInt()}-${(st.sunCol[2] * 60).toInt()}-${w}"
        if (key == sunSpriteKey) return
        sunSpriteKey = key
        val r = sunR
        val n = (r * 2 + 4).toInt()
        val px = IntArray(n * n)
        val core = DoubleArray(3) { st.sunCol[it] + (1 - st.sunCol[it]) * (0.35 + 0.65 * hi) }
        for (yy in 0 until n) for (xx in 0 until n) {
            val dx = (xx + 0.5 - n / 2.0) / r; val dy = (yy + 0.5 - n / 2.0) / r
            val d = sqrt(dx * dx + dy * dy)
            val a = ((1 - d) * r + 0.5).coerceIn(0.0, 1.0)
            if (a <= 0) continue
            val mu = sqrt(max(0.0, 1 - min(1.0, d * d)))
            val limb = 1.08 - 0.20 * (1 - mu) - 0.08 * (1 - mu * mu)
            val cr = (255 * min(1.0, core[0] * (limb + 0.06))).toInt()
            val cg = (255 * min(1.0, core[1] * limb)).toInt()
            val cb = (255 * min(1.0, core[2] * (limb - 0.04 * (1 - hi)))).toInt().coerceAtLeast(0)
            px[yy * n + xx] = ((a * 255).toInt() shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        g.upload(SUN, px, n, n)
    }

    // ---------- चाँद (असली NASA तस्वीर, असली कला/phase) ----------
    private fun moonSprite(g: SkyGfx) {
        val st = state
        var lx = (st.sunX - st.moonX).toDouble(); var ly = (st.sunY - st.moonY).toDouble()
        val ll = sqrt(lx * lx + ly * ly).coerceAtLeast(1e-6); lx /= ll; ly /= ll
        val key = "${(st.phase * 2).toInt()}-${(lx * 30).toInt()}-${(ly * 30).toInt()}-${(st.night * 20).toInt()}-${(st.moonCol[2] * 30).toInt()}-$w"
        if (key == moonSpriteKey) return
        moonSpriteKey = key
        val r = moonR
        val n = (r * 2 + 4).toInt()
        val px = IntArray(n * n)
        val ph = st.phase * SkyAstro.RAD
        val l0 = sin(ph) * lx; val l1 = -sin(ph) * ly; val l2 = cos(ph)
        val earth = 0.05 * st.night * (1 - st.illum)
        val gain = 1.75
        for (yy in 0 until n) for (xx in 0 until n) {
            val u = (xx + 0.5 - n / 2.0) / r; val v = (yy + 0.5 - n / 2.0) / r
            val d2 = u * u + v * v
            val a = ((1 - sqrt(d2)) * r + 0.5).coerceIn(0.0, 1.0)
            if (a <= 0) continue
            val z = sqrt(max(0.0, 1 - min(1.0, d2)))
            val mu0 = u * l0 + (-v) * l1 + z * l2
            val ls = if (mu0 > 0) 2 * mu0 / (mu0 + z + 1e-6) else 0.0
            val term = SkyLight.smooth(-0.03, 0.05, mu0)
            val bright = ls * term + earth
            var ar = 0.55; var ag = 0.55; var ab = 0.55
            if (moonTex != null) {
                val tx = ((u * 0.985 + 1) / 2 * (moonTexSize - 1)).coerceIn(0.0, moonTexSize - 1.001)
                val ty = ((v * 0.985 + 1) / 2 * (moonTexSize - 1)).coerceIn(0.0, moonTexSize - 1.001)
                val c = bilinear(moonTex, moonTexSize, tx, ty)
                ar = (c shr 16 and 255) / 255.0; ag = (c shr 8 and 255) / 255.0; ab = (c and 255) / 255.0
            }
            val k = bright * gain
            val cr = (255 * SkyLight.tone(ar * k * st.moonCol[0] * 1.6) / SkyLight.tone(1.6)).toInt().coerceIn(0, 255)
            val cg = (255 * SkyLight.tone(ag * k * st.moonCol[1] * 1.6) / SkyLight.tone(1.6)).toInt().coerceIn(0, 255)
            val cb = (255 * SkyLight.tone(ab * k * st.moonCol[2] * 1.6) / SkyLight.tone(1.6)).toInt().coerceIn(0, 255)
            // अँधेरा हिस्सा असल में दिखता नहीं - उसके आगे का आसमान दिखता है (बस हल्की earthshine)
            val lum = (0.3 * cr + 0.59 * cg + 0.11 * cb) / 255.0
            val alpha = a * min(1.0, lum * (2.2 + 2.0 * st.night))
            px[yy * n + xx] = ((alpha * 255).toInt() shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        g.upload(MOON, px, n, n)
    }

    private fun bilinear(t: IntArray, n: Int, x: Double, y: Double): Int {
        val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
        val a = t[y0 * n + x0]; val b = t[y0 * n + x0 + 1]; val c = t[(y0 + 1) * n + x0]; val d = t[(y0 + 1) * n + x0 + 1]
        var out = 0
        for (s in intArrayOf(16, 8, 0)) {
            val v = ((a shr s and 255) * (1 - fx) + (b shr s and 255) * fx) * (1 - fy) +
                ((c shr s and 255) * (1 - fx) + (d shr s and 255) * fx) * fy
            out = out or (v.toInt().coerceIn(0, 255) shl s)
        }
        return out
    }

    // ---------- तारे (असली Yale तारा-सूची) ----------
    private fun computeStars(nowMs: Long) {
        val st = state
        val n = stars.size
        if (starX.size != n) {
            starX = FloatArray(n); starY = FloatArray(n); starA = FloatArray(n); starR = FloatArray(n)
            starC = IntArray(n); starTw = FloatArray(n)
        }
        starN = 0
        val dark = SkyLight.smooth(-3.0, -15.0, st.sunAlt)
        if (dark <= 0.01) return
        val moonUp = if (st.moonAlt > 0) st.illum else 0.0
        val lim = 1.6 + 4.2 * dark - 1.4 * moonUp
        val lst = SkyAstro.lst(SkyAstro.julian(nowMs), lon)
        val sc = w / 1080f
        for (s in stars) {
            if (s.mag > lim + 0.6) continue
            val hz = SkyAstro.horizontal(s.ra, s.dec, lat, lst)
            val alt = hz[0]
            if (alt < 1) continue
            val dAz = SkyAstro.norm180(hz[1] - 180.0)
            val x = (w / 2f + dAz / 92.0 * w * 0.5).toFloat()
            if (x < -8 || x > w + 8) continue
            val y = yOf(alt + SkyAstro.refraction(alt))
            val mdx = x - st.moonX; val mdy = y - st.moonY
            if (st.moonAlt > -1 && mdx * mdx + mdy * mdy < (moonR * 1.4f) * (moonR * 1.4f)) continue
            val ext = SkyLight.smooth(1.0, 14.0, alt)
            val a = ((0.40 + (lim - s.mag) * 0.36).coerceIn(0.0, 1.0) * dark * ext).toFloat()
            if (a < 0.03f) continue
            val i = starN++
            starX[i] = x; starY[i] = y; starA[i] = a
            starR[i] = ((0.75 + 0.42 * max(0.0, 3.6 - s.mag)) * sc).toFloat()
            starC[i] = bvColor(s.bv)
            starTw[i] = (0.16 + 0.42 * (1 - alt / 90.0).pow(3)).toFloat()
        }
    }

    // ---------- एक फ्रेम ----------
    /** tSec = एनीमेशन घड़ी (लहरें/टिमटिमाहट), xOffset = लॉन्चर पेज (0..1) */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun frame(g: SkyGfx, nowMs: Long, tSec: Double, xOffset: Float) {
        if (w == 0) return
        if (abs(nowMs - lastAstro) >= 1000) { update(nowMs); lastAstro = nowMs }
        val st = state
        if (needSky(nowMs)) {
            lastSky = nowMs; skyAlt = st.sunAlt; skyMoon = st.moonAlt
            val a = skyAsync
            if (a == null || !hasSky) { g.upload(SKY, renderBackdrop(), bw, bh); hasSky = true } else a(bw, bh)
        }
        if (st.sunAlt > -2) sunSprite(g)
        if (st.moonVis > 0.01) moonSprite(g)          // दिन में चाँद की तस्वीर बनानी ही नहीं (बैटरी)
        val t = tSec.toFloat()
        val skyH = bh * backdropScale.toFloat()
        g.layer(SKY, 0f, 0f, bw.toFloat(), bh.toFloat(), 0f, 0f, bw * backdropScale.toFloat(), skyH, WHITE, 0, 1f, false)

        // तारे
        for (i in 0 until starN) {
            val ph = rnd[i and 511] * 6.283f; val f1 = 2.2f + rnd[(i * 3) and 511] * 5f
            val tw = 1f + starTw[i] * (0.6f * sin(t * f1 + ph) + 0.4f * sin(t * f1 * 1.7f + ph * 2.3f))
            val a = (starA[i] * tw).coerceIn(0f, 1f)
            g.dot(starX[i], starY[i], starR[i], starC[i], a)
            if (starR[i] > 2.2f * w / 1080f) g.glow(starX[i], starY[i], starR[i] * 4f, starR[i] * 4f, starC[i], a * 0.22f)
        }

        // चाँद
        val mr = moonR
        // दिन में चाँद नहीं (मालिक) - सूरज डूबने के बाद धीरे-धीरे
        val mv = st.moonVis.toFloat()
        if (st.moonAlt > -1.5 && mv > 0.01f) {
            val gl = (st.illum * st.night * mv).toFloat()
            if (gl > 0.02f) {
                g.glow(st.moonX, st.moonY, mr * 5f, mr * 5f, rgb(st.moonCol, 0.75, 0.85, 1.0), 0.16f * gl)
                g.glow(st.moonX, st.moonY, mr * 1.9f, mr * 1.9f, rgb(st.moonCol, 0.9, 0.95, 1.0), 0.28f * gl)
            }
            val sq = squash(st.moonAlt)
            g.layer(MOON, 0f, 0f, (mr * 2 + 4).toInt().toFloat(), (mr * 2 + 4).toInt().toFloat(),
                st.moonX - mr - 2, st.moonY - (mr + 2) * sq, mr * 2 + 4, (mr * 2 + 4) * sq, WHITE, 0, mv, false)
        }

        // सूरज + चमक (दोपहर में तेज़, गरम)
        val sr = sunR
        if (st.sunAlt > -2) {
            val hi = SkyLight.smooth(0.0, 45.0, st.sunAlt).toFloat()
            val low = SkyLight.smooth(-2.0, 3.0, st.sunAlt).toFloat()
            val col = rgb(st.sunCol, 1.0, 1.0, 1.0)
            g.glow(st.sunX, st.sunY, sr * 26f, sr * 26f, col, (0.10f + 0.16f * hi) * low)
            g.glow(st.sunX, st.sunY, sr * 8f, sr * 8f, col, (0.22f + 0.25f * hi) * low)
            val sq = squash(st.sunAlt)
            val n = (sr * 2 + 4).toInt().toFloat()
            g.layer(SUN, 0f, 0f, n, n, st.sunX - n / 2, st.sunY - n / 2 * sq, n, n * sq, WHITE, 0, 1f, false)
            g.glow(st.sunX, st.sunY, sr * 2.4f, sr * 2.4f * sq, col, (0.45f + 0.45f * hi) * low)
        }

        val par = (xOffset - 0.5f)
        // दूर के पहाड़, बीच के पेड़
        for (ly in pack.layers) if (ly.kind != "near") drawLayer(g, ly, par, false)

        // झील
        val waterTop = hy
        g.rect(0f, waterTop, w.toFloat(), h - waterTop, rgb(st.water, 1.0, 1.0, 1.0), 1f)
        val step = max(2f, 3f * w / 1080f)
        var y = waterTop
        val amp = 7f * w / 1080f
        while (y < h) {
            val d = (y - waterTop) / (h - waterTop)
            val sy = 2 * waterTop - y - step
            val srcY = (max(0f, sy) / backdropScale)
            val dx = amp * (0.3f + 1.6f * d) * (sin(t * 1.1f + y * 0.075f) * 0.6f + sin(t * 1.9f + y * 0.21f) * 0.4f)
            val fres = 0.86f - 0.30f * d
            g.layer(SKY, 0f, srcY, bw.toFloat(), step / backdropScale, dx - amp * 2, y, bw * backdropScale + amp * 4, step + 0.6f, WHITE, 0, fres, true)
            y += step
        }

        // सूरज/चाँद की परछाईं + झिलमिलाती चमक
        if (st.sunAlt > -1.5) reflectBody(g, st.sunX, st.sunY, sr, rgb(st.sunCol, 1.0, 1.0, 1.0),
            (SkyLight.smooth(-1.5, 2.0, st.sunAlt) * (1.0 - 0.55 * SkyLight.smooth(20.0, 70.0, st.sunAlt))).toFloat(), t, SUN, squash(st.sunAlt))
        if (st.moonAlt > -1.5 && st.night > 0.05 && mv > 0.01f) reflectBody(g, st.moonX, st.moonY, mr * 0.6f, rgb(st.moonCol, 0.85, 0.92, 1.0),
            (st.illum * st.night * 0.8 * mv).toFloat(), t, MOON, squash(st.moonAlt))

        for (ly in pack.layers) if (ly.kind != "near" && ly.reflect) drawLayer(g, ly, par, true)

        for (ly in pack.layers) if (ly.kind == "near") drawLayer(g, ly, par, false)
    }

    private fun squash(alt: Double): Float = (1.0 - 0.18 * exp(-max(alt, 0.0) / 0.8)).toFloat()

    @Suppress("LongParameterList")
    private fun reflectBody(g: SkyGfx, x: Float, y: Float, r: Float, col: Int, k: Float, t: Float, id: String, sq: Float) {
        if (k <= 0.01f) return
        val my = 2 * hy - y
        if (my < h + r * 2) {
            g.layer(id, 0f, 0f, (if (id == SUN) sunR else moonR) * 2 + 4, (if (id == SUN) sunR else moonR) * 2 + 4,
                x - (if (id == SUN) sunR else moonR) - 2, my - ((if (id == SUN) sunR else moonR) + 2) * sq,
                (if (id == SUN) sunR else moonR) * 2 + 4, ((if (id == SUN) sunR else moonR) * 2 + 4) * sq, WHITE, 0, 0.45f * k, true)
        }
        val bottom = h * 0.98f
        val n = 46
        for (i in 0 until n) {
            val u = rnd[(i * 5 + 1) and 511]
            val gy = hy + 2 + (bottom - hy) * u.pow(1.5f)
            val d = (gy - hy) / (h - hy)
            val spread = w * (0.018f + 0.20f * d) * (0.4f + 0.6f * min(1f, abs(my - hy) / (h - hy) + 0.4f))
            val gx = x + (rnd[(i * 11 + 7) and 511] - 0.5f) * 2 * spread * (0.6f + 0.4f * sin(t * 0.3f + i))
            val fl = sin(t * (2.0f + rnd[(i * 17) and 511] * 4f) + i * 1.7f).coerceAtLeast(0f).pow(3)
            val rx = (5f + 16f * d) * w / 1080f * (0.6f + rnd[(i * 3 + 2) and 511])
            g.glow(gx, gy, rx, rx * 0.22f, col, k * fl * (0.85f - 0.4f * d))
        }
        g.glow(x, hy + 3, r * 2.5f, r * 0.5f, col, k * 0.35f)
    }

    private fun drawLayer(g: SkyGfx, ly: SkyPack.Layer, par: Float, reflect: Boolean) {
        val sz = imgSize(ly.id) ?: return
        val st = state
        val dw = ly.width * w
        val dh = if (ly.height > 0) ly.height * h else dw * sz[1] / sz[0]
        val bottom = ly.bottom * h
        val dx = (w - dw) / 2f - par * ly.parallax * w
        val mul: DoubleArray; val add: DoubleArray
        if (ly.kind == "haze") {
            // दूर के पहाड़: आसमान की धुंध में घुले, आसमान से थोड़े गहरे (उगते/डूबते सूरज के सामने परछाईं जैसे)
            val dark = DoubleArray(3) { st.amb[it] * 0.35 }
            mul = DoubleArray(3) { ((st.amb[it] * 0.4 + st.haze8[it] * 0.6) * 0.92 - dark[it]).coerceAtLeast(0.0) }
            add = dark
        } else {
            mul = DoubleArray(3) { st.amb[it] * (1 - ly.haze) }
            add = DoubleArray(3) { st.haze[it] * ly.haze }
        }
        val mi = rgb(mul, 1.0, 1.0, 1.0); val ai = rgb(add, 1.0, 1.0, 1.0)
        if (!reflect) {
            g.layer(ly.id, 0f, 0f, sz[0].toFloat(), sz[1].toFloat(), dx, bottom - dh, dw, dh, mi, ai, 1f, false)
        } else {
            // उल्टी परछाईं, हल्की लहरों में टूटती हुई
            val strips = 10
            val dk = rgb(DoubleArray(3) { mul[it] * 0.62 }, 1.0, 1.0, 1.0)
            val da = rgb(DoubleArray(3) { add[it] * 0.62 }, 1.0, 1.0, 1.0)
            val top = 2 * hy - bottom
            for (s in 0 until strips) {
                val f0 = s / strips.toFloat()
                val sy = sz[1] * (1 - f0 - 1f / strips)
                val off = sin(s * 1.3f + top * 0.01f) * 1.2f * w / 1080f
                g.layer(ly.id, 0f, sy, sz[0].toFloat(), sz[1].toFloat() / strips, dx + off, top + dh * f0, dw, dh / strips + 0.5f, dk, da, 0.85f, true)
            }
        }
    }

    private fun rgb(c: DoubleArray, a: Double, b: Double, d: Double): Int {
        val r = (SkyLight.toSrgb((c[0] * a).coerceIn(0.0, 1.0)) * 255).toInt()
        val g = (SkyLight.toSrgb((c[1] * b).coerceIn(0.0, 1.0)) * 255).toInt()
        val bl = (SkyLight.toSrgb((c[2] * d).coerceIn(0.0, 1.0)) * 255).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    companion object {
        const val SKY = "_sky"
        const val SUN = "_sun"
        const val MOON = "_moon"
        const val WHITE = -1
        private const val SAT = 1.22
        private const val MOON_SKY = 1.1e-3

        fun hash(i: Int): Float {
            var x = i * 374_761_393 + 668_265_263
            x = (x xor (x ushr 13)) * 1_274_126_177
            x = x xor (x ushr 16)
            return (x and 0xFFFF) / 65_535f
        }

        /** B−V रंग-सूचकांक -> तारे का असली रंग (नीला-सफ़ेद से नारंगी) */
        fun bvColor(bv: Double): Int {
            val b = bv.coerceIn(-0.4, 2.0)
            val r: Double; val g: Double; val bl: Double
            if (b < 0.0) { r = 0.66 + 0.34 * (b + 0.4) / 0.4; g = 0.76 + 0.20 * (b + 0.4) / 0.4; bl = 1.0 }
            else if (b < 0.6) { r = 1.0; g = 0.96 - 0.06 * b / 0.6; bl = 1.0 - 0.18 * b / 0.6 }
            else { val f = ((b - 0.6) / 1.4).coerceIn(0.0, 1.0); r = 1.0; g = 0.90 - 0.28 * f; bl = 0.82 - 0.50 * f }
            return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
        }

        fun parseStars(csv: String): List<Star> = csv.lineSequence().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val p = line.split(',')
            if (p.size < 4) return@mapNotNull null
            val ra = p[0].trim().toDoubleOrNull(); val dec = p[1].trim().toDoubleOrNull()
            val m = p[2].trim().toDoubleOrNull(); val bv = p[3].trim().toDoubleOrNull() ?: 0.6
            if (ra == null || dec == null || m == null) null else Star(ra, dec, m, bv)
        }.toList()

        @Suppress("unused") private const val TAU = 2 * PI
    }
}
