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
 * v4.x: DK का पुरुष / महिला चेहरा + आधा धड़ (सीना, कंधे, बाँहों का ऊपरी हिस्सा).
 * पहले सिर्फ़ गर्दन और कंधे की पतली रेखा दिखती थी - अब इंसान जैसा आधा शरीर, डॉट से बना.
 *  - सिर = FaceHD वाला ही (आँख झपकना, होंठ हिलना पहले जैसा), थोड़ा छोटा करके ऊपर रखा ताकि धड़ की जगह बने.
 *  - धड़: कंधों की गोलाई, ऊपरी बाँहें, सीना (पुरुष = चौड़ा, सीने की मांसपेशी; महिला = पतली कमर, हल्का उभार).
 *  - चमकती रेखाएँ (kind 1): बीच की रेखा, सीने की निचली रेखा, शरीर के किनारे.
 *  - अंदर की रोशनी (kind [GLOW]): सिर और धड़ के अंदर हल्के-हल्के टिमटिमाते कण - इतने हल्के कि चेहरा साफ़ दिखे.
 *  - धड़ के डॉट lower = true (OrbView में हल्की साँस).
 * Android import नहीं - JVM test से जाँच. एक बार बनता है, हर frame नहीं.
 */
object FaceBust {
    /** अंदर के चमकते कण */
    const val GLOW = 6
    /** सिर कितना छोटा + कितना ऊपर (orb की इकाई) */
    const val S = 0.78f
    const val DY = 0.40f
    /** हिस्से: सिर / धड़ / अंदर की रोशनी */
    const val HEAD_F = 0.70f
    const val INNER_F = 0.08f

    private val LIGHT = run { val l = floatArrayOf(-0.45f, 0.55f, 0.75f); val d = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]); floatArrayOf(l[0] / d, l[1] / d, l[2] / d) }

    private fun sm(t0: Float): Float { val t = t0.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun g2(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float) =
        exp(-(((x - cx) / rx).pow(2) + ((y - cy) / ry).pow(2)))
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    fun build(n: Int, female: Boolean): Cloud {
        val c = Cloud(n)
        val nHead = (n * HEAD_F).toInt().coerceAtLeast(1)
        val nInner = (n * INNER_F).toInt()
        // ---- सिर (FaceHD) - छोटा करके ऊपर
        val head = FaceHD.build(nHead, female)
        for (i in 0 until head.count) {
            val k = head.kind[i].toInt()
            val a = if (k == 2 || k == 3) head.anc[i] * S + DY else head.anc[i]
            c.add(head.x[i] * S, head.y[i] * S + DY, head.z[i] * S, k, a, head.lower[i], head.lum[i])
        }
        val r = Random(if (female) 57 else 43)

        fun lum(nx: Float, ny: Float, nz: Float): Float {
            val d = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            return 0.25f + 0.75f * max(0f, (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / d)
        }
        /** cm (FaceHD का ढाँचा) -> orb की इकाई */
        fun put(x: Float, y: Float, z: Float, k: Int, l: Float, a: Float = 0f, body: Boolean = true): Boolean =
            c.add(x * FaceHD.K * S, ((y - FaceHD.YC) * FaceHD.K + FaceHD.YOFF) * S + DY, (z - FaceHD.ZC) * FaceHD.K * S,
                k, a, body, l.coerceIn(0f, 1f))

        // ---- धड़ का नाप (cm)
        val nr = if (female) 4.3f else 5.7f              // गर्दन (FaceHD जैसा)
        val w = if (female) 12.5f else 15.5f             // कंधे की चौड़ाई (FaceHD जैसा)
        val zc = -3.0f
        val bottom = -37f
        fun shoulderY(ax: Float): Float =
            if (ax < nr * 1.6f) -14.0f - 0.9f * (ax / nr) else -15.2f - 3.2f * ((ax - nr * 1.6f) / (w - nr * 1.6f)).coerceIn(0f, 1f).pow(1.6f)
        fun halfW(y: Float): Float = if (female) {
            if (y > -25f) lerp(10.6f, 10.9f, (-19f - y) / 6f) else lerp(10.9f, 8.9f, (-25f - y) / 12f)
        } else {
            if (y > -24f) lerp(12.6f, 13.2f, (-19f - y) / 5f) else lerp(13.2f, 11.7f, (-24f - y) / 13f)
        }
        fun halfD(y: Float): Float = if (female) lerp(6.9f, 6.0f, (-18f - y) / 19f) else lerp(8.4f, 7.4f, (-18f - y) / 19f)
        /** सीने का उभार (सामने की ओर) */
        fun chest(ax: Float, y: Float): Float =
            if (female) 3.0f * g2(ax, y, 4.6f, -25.0f, 3.9f, 3.5f) + 0.6f * g2(ax, y, 0f, -21f, 7f, 3f)
            else 1.4f * g2(ax, y, 5.0f, -22.5f, 5.0f, 3.6f) + 0.5f * g2(ax, y, 0f, -31f, 5f, 4f)
        /** नीचे की ओर धीरे-धीरे गायब */
        fun fade(y: Float) = 0.15f + 0.85f * sm((y - bottom) / 6f)
        /** धड़ की सतह पर एक बिंदु (th = 0 सामने) */
        val tp = FloatArray(6)
        fun trunk(th: Float, y: Float) {
            val ww = halfW(y); val dd = halfD(y)
            val x = ww * sin(th); val ct = cos(th)
            val z = zc + dd * ct + (if (ct > 0f) chest(abs(x), y) * ct.pow(0.6f) else 0f)
            tp[0] = x; tp[1] = y; tp[2] = z
            tp[3] = sin(th) / ww; tp[4] = 0.08f; tp[5] = ct / dd
        }

        val nBody = (n - nHead - nInner).coerceAtLeast(0)
        val qTrunk = (nBody * 0.56f).toInt(); val qArm = (nBody * 0.18f).toInt(); val qDelt = (nBody * 0.07f).toInt()
        // ---- 1. धड़ की सतह (ज़्यादातर सामने - पीछे वाले कम)
        var made = 0; var guard = 0
        while (made < qTrunk && guard < qTrunk * 20) {
            guard++
            val front = r.nextFloat() < 0.8f
            val th = if (front) (r.nextFloat() * 2 - 1) * PI.toFloat() * 0.55f else (r.nextFloat() * 2 - 1) * PI.toFloat()
            val y = -14f - 23f * r.nextFloat().pow(0.85f)
            trunk(th, y)
            if (tp[1] > shoulderY(abs(tp[0]))) continue
            val l = lum(tp[3], tp[4], tp[5]) * fade(y)
            put(tp[0], tp[1], tp[2], if (r.nextFloat() < 0.07f) 1 else 0, l)
            made++
        }
        // ---- 2. कंधों की गोलाई (deltoid)
        val dr = if (female) 3.4f else 4.3f
        for (j in 0 until qDelt) {
            val s = if (j % 2 == 0) 1f else -1f
            // बाहर-ऊपर की ओर का आधा गोला
            var ux = r.nextFloat() * 2 - 1; var uy = r.nextFloat() * 2 - 1; var uz = r.nextFloat() * 2 - 1
            val d = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(0.05f); ux /= d; uy /= d; uz /= d
            ux = abs(ux) * s
            if (uy < -0.4f) uy = -uy * 0.5f
            put(s * (w - 2.2f) + ux * dr, -19.6f + uy * dr, zc + uz * dr * 0.95f, if (r.nextFloat() < 0.1f) 1 else 0, lum(ux, uy, uz))
        }
        // ---- 3. ऊपरी बाँहें (धड़ से थोड़ी दूर, नीचे की ओर)
        val ar = if (female) 3.0f else 3.8f
        for (j in 0 until qArm) {
            val s = if (j % 2 == 0) 1f else -1f
            val u = r.nextFloat()
            val y = -20f + (bottom + 1f + 20f) * u
            val ax = s * (w - 1.4f + 1.6f * u)
            val th = (r.nextFloat() * 2 - 1) * PI.toFloat() * 0.9f   // बाँह के चारों ओर (0 = सामने)
            val x = ax + s * ar * sin(th).coerceAtLeast(-0.35f) * (if (sin(th) * s < 0f) 0.6f else 1f)
            val z = zc + ar * cos(th)
            put(x, y, z, if (r.nextFloat() < 0.06f) 1 else 0, lum(s * sin(th), 0.05f, cos(th)) * fade(y))
        }
        // ---- 4. चमकती रेखाएँ
        val qLine = (nBody - qTrunk - qArm - qDelt).coerceAtLeast(0)
        for (j in 0 until qLine) {
            val pick = j % 10
            when {
                pick < 2 -> { // बीच की रेखा (गले के नीचे से)
                    val y = -17.5f - 15.5f * r.nextFloat(); trunk(0f, y)
                    put(tp[0], tp[1], tp[2] + 0.15f, 1, 0.9f * fade(y))
                }
                pick < 5 -> { // सीने की निचली रेखा
                    val s = if (r.nextFloat() < 0.5f) 1f else -1f
                    if (female) {
                        val ph = PI.toFloat() * (0.12f + 0.76f * r.nextFloat())
                        val x = s * (4.6f + 3.7f * cos(ph)); val y = -25.2f - 3.3f * sin(ph)
                        trunk(kotlin.math.asin((x / halfW(y)).coerceIn(-1f, 1f)), y)
                        put(tp[0], tp[1], tp[2] + 0.2f, 1, 0.95f)
                    } else {
                        val ax = 0.8f + 8.2f * r.nextFloat(); val y = -26.2f + 1.3f * (ax / 7f).pow(2)
                        trunk(kotlin.math.asin((s * ax / halfW(y)).coerceIn(-1f, 1f)), y)
                        put(tp[0], tp[1], tp[2] + 0.2f, 1, 0.95f)
                    }
                }
                pick < 8 -> { // शरीर के किनारे (बाएँ-दाएँ)
                    val s = if (r.nextFloat() < 0.5f) 1f else -1f
                    val y = -20f - 15f * r.nextFloat(); trunk(s * PI.toFloat() * 0.47f, y)
                    put(tp[0], tp[1], tp[2], 1, 0.8f * fade(y))
                }
                else -> { // बाँह के बाहरी किनारे
                    val s = if (r.nextFloat() < 0.5f) 1f else -1f; val u = r.nextFloat() * 0.9f
                    val y = -20f + (bottom + 1f + 20f) * u
                    put(s * (w - 1.4f + 1.6f * u + ar), y, zc + 0.6f, 1, 0.75f * fade(y))
                }
            }
        }
        // ---- 5. अंदर की हल्की रोशनी: 60% धड़ में, 40% सिर में (anc = टिमटिमाने का अपना समय 0..1)
        var gi = 0
        while (c.count < n) {
            val ph = r.nextFloat()
            if (gi % 5 < 3) {
                val y = -16f - 19f * r.nextFloat()
                val rr = sqrt(r.nextFloat()) * 0.72f; val th = r.nextFloat() * 2 * PI.toFloat()
                put(halfW(y) * rr * sin(th), y, zc + halfD(y) * rr * cos(th), GLOW, 1f, ph, body = false)
            } else {
                val rr = r.nextFloat().pow(0.33f) * 0.62f
                var ux = r.nextFloat() * 2 - 1; var uy = r.nextFloat() * 2 - 1; var uz = r.nextFloat() * 2 - 1
                val d = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(0.05f); ux /= d; uy /= d; uz /= d
                put(ux * 8.1f * rr, 0.8f + uy * 10f * rr, -2.6f + uz * 9.4f * rr, GLOW, 1f, ph, body = false)
            }
            gi++
        }
        c.tilt = 0f; c.spin = 0f; c.sway = true
        return c
    }
}
