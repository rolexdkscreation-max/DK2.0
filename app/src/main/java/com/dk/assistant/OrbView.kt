package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D orb: chhote-chhote dots se bana gola, prithvi ki tarah ghoomta hai (23.4° jhuka hua),
 * "mahadweep" wale dots zyada chamakte hain, aur chaaron taraf satellite orbit ghoomte hain.
 * Rang aur speed DK ki haalat ke hisaab se badalte hain:
 *  neela = taiyaar, laal = sun raha, baingani/narangi = soch raha, hara = kaam kar raha, firozi = bol raha
 */
class OrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mini = false
        set(v) { field = v; build(if (v) 320 else 1400) }

    private var n = 0
    private var bx = FloatArray(0); private var by = FloatArray(0); private var bz = FloatArray(0)
    private var land = BooleanArray(0)

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var rotY = 0f
    private var lastT = 0L
    private var lvl = 0f
    private var colA = Color.parseColor("#2979FF")
    private var colB = Color.parseColor("#00E5FF")
    private val tilt = (23.4 * PI / 180).toFloat()

    init { build(1400) }

    private fun build(count: Int) {
        n = count
        bx = FloatArray(n); by = FloatArray(n); bz = FloatArray(n); land = BooleanArray(n)
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val r = sqrt(1f - y * y)
            val th = golden * i
            bx[i] = cos(th) * r; by[i] = y; bz[i] = sin(th) * r
            land[i] = continents(bx[i], by[i], bz[i])
        }
    }

    /** Nakli "mahadweep" - 3D lehron ka mel, taaki gola prithvi jaisa lage. */
    private fun continents(x: Float, y: Float, z: Float): Boolean {
        val v = sin(2.7f * x + 1.3f) * cos(2.2f * y - 0.4f) +
            0.7f * sin(3.6f * z + 0.8f + 1.5f * y) +
            0.35f * sin(6.1f * x + 4.3f * z) +
            0.25f * cos(7.3f * y + 2.1f * x)
        return v > 0.1f && kotlin.math.abs(y) < 0.93f
    }

    private fun targetColors(m: Mode): Pair<Int, Int> = when (m) {
        Mode.IDLE -> Color.parseColor("#2979FF") to Color.parseColor("#00E5FF")
        Mode.LISTENING -> Color.parseColor("#FF1744") to Color.parseColor("#FF80AB")
        Mode.THINKING -> Color.parseColor("#AA00FF") to Color.parseColor("#FFAB40")
        Mode.WORKING -> Color.parseColor("#00C853") to Color.parseColor("#B2FF59")
        Mode.SPEAKING -> Color.parseColor("#00B8D4") to Color.parseColor("#64FFDA")
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceAtMost(0.05f)
        lastT = now
        val t = now / 1000f
        val mode = DKState.mode

        val speed = when (mode) {
            Mode.IDLE -> 0.28f; Mode.LISTENING -> 0.55f; Mode.THINKING -> 1.8f
            Mode.WORKING -> 1.1f; Mode.SPEAKING -> 0.7f
        }
        rotY += speed * dt
        lvl += (DKState.level - lvl) * 0.25f
        val (ta, tb) = targetColors(mode)
        colA = lerpColor(colA, ta, 0.08f); colB = lerpColor(colB, tb, 0.08f)

        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val baseR = minOf(w, h) * (if (mini) 0.36f else 0.30f)
        val breathe = 1f + 0.025f * sin(t * 1.6f)
        val pulse = when (mode) {
            Mode.LISTENING -> 1f + lvl * 0.14f
            Mode.SPEAKING -> 1f + 0.04f * sin(t * 9f)
            else -> 1f
        }
        val R = baseR * breathe * pulse

        // ---- glow (pichhe ki roshni)
        glow.shader = RadialGradient(cx, cy, R * 1.75f,
            intArrayOf(withAlpha(colA, 120), withAlpha(colA, 40), Color.TRANSPARENT),
            floatArrayOf(0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 1.75f, glow)

        val cosA = cos(rotY); val sinA = sin(rotY)
        val cosT = cos(tilt); val sinT = sin(tilt)
        val dotR = (if (mini) 1.6f else 2.3f) * resources.displayMetrics.density * (baseR / (if (mini) 90f else 330f)).coerceIn(0.6f, 1.6f)
        val wave = when (mode) {
            Mode.THINKING -> 0.05f; Mode.SPEAKING -> 0.035f; Mode.LISTENING -> lvl * 0.06f; else -> 0.0f
        }

        // ---- satellite orbit ka peeche wala hissa (gole ke peeche)
        if (!mini) drawOrbits(c, cx, cy, R, dotR, front = false)

        // ---- sphere dots: pehle peeche wale, phir aage wale
        for (pass in 0..1) {
            for (i in 0 until n) {
                val x0 = bx[i]; val y0 = by[i]; val z0 = bz[i]
                val x1 = x0 * cosA + z0 * sinA
                val z1 = -x0 * sinA + z0 * cosA
                val y2 = y0 * cosT - z1 * sinT
                val z2 = y0 * sinT + z1 * cosT
                if ((z2 >= 0f) != (pass == 1)) continue
                val rr = 1f + wave * sin(7f * y0 + 5f * x0 + t * 5f)
                val persp = 4f / (4f - z2)
                val sx = cx + x1 * R * rr * persp
                val sy = cy - y2 * R * rr * persp
                val depth = (z2 + 1f) / 2f
                val isLand = land[i]
                val a = ((if (isLand) 80 else 18) + (if (isLand) 175 else 100) * depth.pow(1.6f)).toInt()
                dot.color = withAlpha(if (isLand) lerpColor(colB, Color.WHITE, 0.25f * depth) else colA, a)
                val r = dotR * (0.45f + 0.75f * depth) * (if (isLand) 1.25f else 0.7f)
                c.drawCircle(sx, sy, r, dot)
            }
        }

        // ---- kinare ki patli chamak
        rim.strokeWidth = dotR * 0.6f
        rim.color = withAlpha(colB, 50)
        c.drawCircle(cx, cy, R * 1.0f, rim)

        // ---- satellite orbit ka aage wala hissa
        if (!mini) drawOrbits(c, cx, cy, R, dotR, front = true)

        if (mini && mode == Mode.IDLE) postInvalidateDelayed(60) else postInvalidateOnAnimation()
    }

    private fun drawOrbits(c: Canvas, cx: Float, cy: Float, R: Float, dotR: Float, front: Boolean) {
        drawOrbit(c, cx, cy, R * 1.22f, 0.45f, 0.35f, rotY * 1.6f + 1f, 72, dotR, front)
        drawOrbit(c, cx, cy, R * 1.36f, -0.9f, 1.1f, -rotY * 1.1f, 90, dotR, front)
    }

    private fun drawOrbit(c: Canvas, cx: Float, cy: Float, radius: Float, incl: Float, node: Float,
                          phase: Float, count: Int, dotR: Float, front: Boolean) {
        val ci = cos(incl); val si = sin(incl); val cn = cos(node); val sn = sin(node)
        for (k in 0..count) {
            val th = (2 * PI * k / count).toFloat()
            val sat = k == count
            val ang = if (sat) phase else th
            var x = cos(ang); var y = 0f; var z = sin(ang)
            // jhukao (inclination) X-axis par
            val y1 = y * ci - z * si; val z1 = y * si + z * ci
            y = y1; z = z1
            // ghumao Y-axis par
            val x2 = x * cn + z * sn; val z2 = -x * sn + z * cn
            x = x2; z = z2
            if ((z >= 0f) != front) continue
            val persp = 6f / (6f - z)
            val sx = cx + x * radius * persp
            val sy = cy - y * radius * persp
            val depth = (z + 1f) / 2f
            if (sat) {
                dot.color = withAlpha(Color.WHITE, (140 + 115 * depth).toInt())
                c.drawCircle(sx, sy, dotR * 2.2f, dot)
                dot.color = withAlpha(colB, 70)
                c.drawCircle(sx, sy, dotR * 4.5f, dot)
            } else {
                dot.color = withAlpha(colB, (25 + 90 * depth).toInt())
                c.drawCircle(sx, sy, dotR * 0.45f, dot)
            }
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastT = 0L; invalidate() }
}
