package com.dk.assistant.dkai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** स्क्रीन पर दिखे तभी चलने वाला animation (बंद/छुपा = CPU का काम नहीं) */
abstract class DkaiAnimView(c: Context) : View(c) {
    protected val t0 = SystemClock.uptimeMillis()
    protected fun secs() = (SystemClock.uptimeMillis() - t0) / 1000f
    /** 0..1 - जितना ज़ोर (माइक / DK की आवाज़) */
    var level: () -> Float = { 0f }
    /** false = शांत (धीमी साँस), true = सुन / बोल रहा */
    var active: () -> Boolean = { false }
    protected var lv = 0f
    private var running = false

    protected fun step() {
        val target = (try { level() } catch (_: Exception) { 0f }).coerceIn(0f, 1f)
        lv += (target - lv) * (if (target > lv) 0.35f else 0.08f)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown && running) postInvalidateOnAnimation()
    }
    protected fun again() { if (running && isShown) postInvalidateOnAnimation() }
}

/**
 * 3D जैसा चमकता "Fluid Orb" (Electric Cyan -> Violet): किनारा पानी की तरह लहराता है, अंदर रंग घूमते हैं,
 * ऊपर बायीं ओर चमक, नीचे छाया. सुनते/बोलते समय ज़ोर के साथ फूलता और ज़्यादा लहराता है.
 */
class FluidOrbView(c: Context) : DkaiAnimView(c) {
    var glow = true
    /** कौन सा orb ([DkaiOrbs.ORBS]) - 13 नवंबर 2026 (मालिक): Terminal / DKAI के लिए 4-5 नए orb */
    var style: () -> String = { DkaiOrbs.FLUID }
    private var pal = DkaiOrbs.palette(DkaiOrbs.FLUID)
    private val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val m = Matrix()

    init { setLayerType(LAYER_TYPE_HARDWARE, null); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        step()
        val t = secs()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) { again(); return }
        val cx = w / 2f; val cy = h / 2f
        val on = try { active() } catch (_: Exception) { false }
        val breathe = sin(t * (if (on) 2.4f else 1.3f)) * 0.025f
        val R = min(w, h) / 2f * (if (glow) 0.66f else 0.86f) * (1f + breathe + lv * 0.12f)
        val st = try { style() } catch (_: Exception) { DkaiOrbs.FLUID }
        pal = DkaiOrbs.palette(st)
        when (st) {
            DkaiOrbs.REACTOR -> { reactor(canvas, cx, cy, R, t, on, w, h); again(); return }
            DkaiOrbs.SOLAR -> { solar(canvas, cx, cy, R, t, on, w, h); again(); return }
            DkaiOrbs.CRYSTAL -> { crystal(canvas, cx, cy, R, t, on, w, h); again(); return }
            DkaiOrbs.SWARM -> { swarm(canvas, cx, cy, R, t, on, w, h); again(); return }
        }

        // बाहर की चमक
        if (glow) {
            val gr = min(R * (1.55f + lv * 0.25f), min(w, h) / 2f * 0.98f)      // view के किनारे से न कटे (कटी चमक = लकीरें)
            halo.shader = RadialGradient(cx, cy, gr, intArrayOf(DkaiOrbs.a(pal[0], 0x70), DkaiOrbs.a(pal[2], 0x40), DkaiOrbs.a(pal[2], 0)), floatArrayOf(0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, gr, halo)
        }

        // लहराता किनारा
        val amp = (if (on) 0.035f else 0.014f) + lv * 0.07f
        path.reset()
        val n = 72
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val r = R * (1f + amp * (sin(3 * a + t * 1.6f) * 0.6f + sin(5 * a - t * 2.1f) * 0.3f + sin(2 * a + t * 0.7f) * 0.5f))
            val x = cx + r * cos(a); val y = cy + r * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // घूमता cyan->violet रंग
        val lg = LinearGradient(cx - R, cy - R, cx + R, cy + R, intArrayOf(pal[0], pal[1], pal[2]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        m.reset(); m.setRotate((t * 25f) % 360f, cx, cy); lg.setLocalMatrix(m)
        body.shader = lg
        canvas.drawPath(path, body)

        // अंदर तैरते रंग के गोले (पानी जैसा)
        val save = canvas.save()
        canvas.clipPath(path)
        val br = R * 0.75f
        val p1x = cx + cos(t * 0.9f) * R * 0.45f; val p1y = cy + sin(t * 1.2f) * R * 0.35f
        blob.shader = RadialGradient(p1x, p1y, br, intArrayOf(DkaiOrbs.a(pal[0], 0xCC), DkaiOrbs.a(pal[0], 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(p1x, p1y, br, blob)
        val p2x = cx + cos(t * -1.1f + 2f) * R * 0.5f; val p2y = cy + sin(t * 0.8f + 1f) * R * 0.45f
        blob.shader = RadialGradient(p2x, p2y, br, intArrayOf(DkaiOrbs.a(pal[2], 0xCC), DkaiOrbs.a(pal[2], 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(p2x, p2y, br, blob)
        val p3x = cx + cos(t * 1.5f + 4f) * R * 0.3f; val p3y = cy + sin(t * -1.3f + 3f) * R * 0.3f
        blob.shader = RadialGradient(p3x, p3y, br * 0.6f, intArrayOf((0x66 + (lv * 0x80).toInt()).coerceAtMost(0xE6) shl 24 or 0xFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(p3x, p3y, br * 0.6f, blob)
        // 3D: नीचे-दाएँ छाया
        shade.shader = RadialGradient(cx - R * 0.35f, cy - R * 0.4f, R * 1.7f, intArrayOf(0x00000000, 0x00000000, 0xA0050818.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(cx - R * 1.3f, cy - R * 1.3f, cx + R * 1.3f, cy + R * 1.3f, shade)
        canvas.restoreToCount(save)

        // किनारे की पतली रोशनी + ऊपर की चमक
        rim.strokeWidth = max(1f, R * 0.025f)
        rim.shader = LinearGradient(cx, cy - R, cx, cy + R, 0xAAE0FBFF.toInt(), DkaiOrbs.a(pal[2], 0x33), Shader.TileMode.CLAMP)
        canvas.drawPath(path, rim)
        val sx = cx - R * 0.38f; val sy = cy - R * 0.42f
        spec.shader = RadialGradient(sx, sy, R * 0.32f, intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.drawOval(sx - R * 0.32f, sy - R * 0.22f, sx + R * 0.32f, sy + R * 0.22f, spec)
        again()
    }

    private fun halo(canvas: Canvas, cx: Float, cy: Float, R: Float, w: Float, h: Float, k: Float) {
        if (!glow) return
        val gr = min(R * k, min(w, h) / 2f * 0.98f)
        halo.shader = RadialGradient(cx, cy, gr, intArrayOf(DkaiOrbs.a(pal[0], 0x66), DkaiOrbs.a(pal[2], 0x30), DkaiOrbs.a(pal[2], 0)), floatArrayOf(0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, gr, halo)
    }

    /** Arc Reactor: बीच में चमकता core + उलटे घूमते छल्ले, आवाज़ पर तेज़ */
    private fun reactor(canvas: Canvas, cx: Float, cy: Float, R: Float, t: Float, on: Boolean, w: Float, h: Float) {
        halo(canvas, cx, cy, R, w, h, 1.5f + lv * 0.3f)
        val sp = if (on) 1.8f else 0.7f
        body.shader = RadialGradient(cx, cy, R * 0.5f, intArrayOf(0xFFFFFFFF.toInt(), pal[0], DkaiOrbs.a(pal[1], 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, R * (0.42f + lv * 0.12f), body)
        for (ring in 0 until 3) {
            val rr = R * (0.6f + ring * 0.2f)
            ln.strokeWidth = max(1.5f, R * (0.06f - ring * 0.012f))
            ln.color = if (ring == 1) pal[2] else pal[0]
            ln.alpha = 230 - ring * 50
            val rot = (t * 60f * sp * (if (ring % 2 == 0) 1 else -1) * (1f + ring * 0.3f)) % 360f
            val segs = 3 + ring * 2
            val sweep = 360f / segs * 0.62f
            for (k in 0 until segs) canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, rot + k * 360f / segs, sweep, false, ln)
        }
    }

    /** Solar: गोल सूरज + नाचती किरणें (आवाज़ जितनी तेज़, किरणें उतनी लंबी) */
    private fun solar(canvas: Canvas, cx: Float, cy: Float, R: Float, t: Float, on: Boolean, w: Float, h: Float) {
        halo(canvas, cx, cy, R, w, h, 1.6f + lv * 0.3f)
        val n = 28
        ln.strokeWidth = max(1.5f, R * 0.045f)
        for (i in 0 until n) {
            val a = i * 2f * PI.toFloat() / n + t * (if (on) 0.5f else 0.15f)
            val len = R * (0.18f + 0.12f * (sin(t * 3f + i * 1.7f) * 0.5f + 0.5f) + lv * 0.35f)
            ln.color = if (i % 2 == 0) pal[0] else pal[2]; ln.alpha = 210
            canvas.drawLine(cx + cos(a) * R * 0.78f, cy + sin(a) * R * 0.78f, cx + cos(a) * (R * 0.78f + len), cy + sin(a) * (R * 0.78f + len), ln)
        }
        body.shader = RadialGradient(cx - R * 0.2f, cy - R * 0.2f, R * 0.9f, intArrayOf(0xFFFFF7D6.toInt(), pal[0], pal[1]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, R * 0.72f, body)
    }

    /** Crystal: घूमता 3D हीरा (8 कोने) - आवाज़ पर फैलता */
    private fun crystal(canvas: Canvas, cx: Float, cy: Float, R: Float, t: Float, on: Boolean, w: Float, h: Float) {
        halo(canvas, cx, cy, R, w, h, 1.45f + lv * 0.3f)
        val rot = t * (if (on) 1.2f else 0.5f)
        val k = R * (0.85f + lv * 0.15f)
        val pts = ArrayList<FloatArray>()
        for (i in 0 until 6) { val a = rot + i * PI.toFloat() / 3f; pts.add(floatArrayOf(cx + cos(a) * k, cy + sin(a) * k * 0.35f)) }
        val top = floatArrayOf(cx, cy - k); val bot = floatArrayOf(cx, cy + k)
        for (i in 0 until 6) {
            val p1 = pts[i]; val p2 = pts[(i + 1) % 6]
            val front = sin(rot + i * PI.toFloat() / 3f + PI.toFloat() / 6f) > 0
            for (apex in listOf(top, bot)) {
                path.reset(); path.moveTo(apex[0], apex[1]); path.lineTo(p1[0], p1[1]); path.lineTo(p2[0], p2[1]); path.close()
                dot.shader = null; dot.color = DkaiOrbs.a(if ((i + (if (apex === top) 0 else 1)) % 2 == 0) pal[0] else pal[2], if (front) 0xCC else 0x55)
                canvas.drawPath(path, dot)
                ln.strokeWidth = max(1f, R * 0.015f); ln.color = 0x88FFFFFF.toInt(); canvas.drawPath(path, ln)
            }
        }
    }

    /** Particle Swarm: सैकड़ों छोटे dot का घूमता गोला, बोलने पर फैलता */
    private fun swarm(canvas: Canvas, cx: Float, cy: Float, R: Float, t: Float, on: Boolean, w: Float, h: Float) {
        halo(canvas, cx, cy, R, w, h, 1.35f + lv * 0.3f)
        val n = 220
        val gold = 2.39996f
        val sp = if (on) 0.9f else 0.35f
        for (i in 0 until n) {
            val yy = 1f - 2f * (i + 0.5f) / n
            val rr = kotlin.math.sqrt(1f - yy * yy)
            val th = i * gold + t * sp
            val x = cos(th) * rr; val z = sin(th) * rr
            val wob = 1f + lv * 0.35f * sin(t * 6f + i)
            val px = cx + x * R * wob; val py = cy + yy * R * wob
            val depth = (z + 1f) / 2f
            dot.shader = null
            dot.color = DkaiOrbs.a(if (i % 3 == 0) pal[2] else pal[0], (60 + depth * 195).toInt())
            canvas.drawCircle(px, py, max(1f, R * (0.018f + depth * 0.03f)), dot)
        }
    }
}

/** लहरों वाला audio visualizer - आवाज़ के साथ ऊँचा, चुप में धीमी लहर */
class WaveformView(c: Context) : DkaiAnimView(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        step()
        val t = secs(); val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) { again(); return }
        val mid = h / 2f
        val on = try { active() } catch (_: Exception) { false }
        val base = if (on) 0.18f else 0.08f
        p.strokeWidth = DkaiTheme.dpf(context, 2f)
        val layers = 3
        for (k in 0 until layers) {
            val amp = h * 0.42f * (base + lv * 0.82f) * (1f - k * 0.25f)
            path.reset()
            var x = 0f
            val stepPx = 4f
            while (x <= w) {
                val u = x / w
                val env = sin(u * PI.toFloat())                       // किनारे पर शून्य
                val y = mid + amp * env * sin(u * (10f + k * 3f) + t * (4f + k * 1.3f) * (if (k % 2 == 0) 1 else -1))
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += stepPx
            }
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(DkaiTheme.CYAN, DkaiTheme.BLUE, DkaiTheme.VIOLET), null, Shader.TileMode.CLAMP)
            p.alpha = (255 * (1f - k * 0.3f)).toInt()
            canvas.drawPath(path, p)
        }
        again()
    }
}

/** धड़कता हुआ गोल बिंदु (READY / LISTENING) */
class PulseDot(c: Context) : DkaiAnimView(c) {
    var color = DkaiTheme.GREEN
    var pulsing = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = min(w, h) / 2f
        val ph = (secs() * 1.1f) % 1f
        if (pulsing) {
            p.color = color; p.alpha = (110 * (1f - ph)).toInt()
            canvas.drawCircle(w / 2f, h / 2f, r * (0.45f + 0.55f * ph), p)
        }
        p.color = color; p.alpha = 255
        canvas.drawCircle(w / 2f, h / 2f, r * 0.42f, p)
        if (pulsing) again()
    }
}

/** बड़ा नीला माइक - बाहर लहरें (ripple) फैलती हैं; [muted] = शांत */
@SuppressLint("ViewConstructor")
class RippleMicView(c: Context, private val coreDp: Float) : DkaiAnimView(c) {
    var muted = false
        set(v) { field = v; icon = DkaiIcon(if (v) DkaiIcon.Kind.MIC_OFF else DkaiIcon.Kind.MIC, DkaiTheme.WHITE); invalidate() }
    private var icon = DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.WHITE)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        step()
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h / 2f
        val core = DkaiTheme.dpf(context, coreDp) / 2f
        val maxR = min(w, h) / 2f
        val on = !muted && (try { active() } catch (_: Exception) { false })
        if (on) for (i in 0 until 3) {
            val ph = ((secs() * 0.7f) + i / 3f) % 1f
            p.color = DkaiTheme.BLUE; p.alpha = (120 * (1f - ph)).toInt()
            canvas.drawCircle(cx, cy, core + (maxR - core) * ph, p)
        }
        g.shader = RadialGradient(cx, cy, core * 1.45f, intArrayOf(0x883B82F6.toInt(), 0x003B82F6), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, core * 1.45f, g)
        p.alpha = 255
        p.color = if (muted) 0xFF334155.toInt() else DkaiTheme.BLUE
        canvas.drawCircle(cx, cy, core * (1f + lv * 0.06f), p)
        val s = (core * 0.9f).toInt()
        icon.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
        icon.draw(canvas)
        if (on || lv > 0.01f) again()
    }
}

/** होम का "AI" बैज - धीरे घूमती cyan->violet चमक वाला गोला */
class AiBadgeView(c: Context) : DkaiAnimView(c) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DkaiTheme.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val m = Matrix()
    init { contentDescription = "DKAI" }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f * 0.62f
        val t = secs()
        val gr = min(r * (1.6f + 0.06f * sin(t * 2f)), min(w, h) / 2f * 0.98f)
        glowP.shader = RadialGradient(cx, cy, gr, intArrayOf(0x6600E5FF, 0x33A855F7, 0x00A855F7), floatArrayOf(0.4f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, gr, glowP)
        fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, intArrayOf(0xFF1B2A4A.toInt(), DkaiTheme.CARD), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        val sg = SweepGradient(cx, cy, intArrayOf(DkaiTheme.CYAN, DkaiTheme.VIOLET, DkaiTheme.BLUE, DkaiTheme.CYAN), null)
        m.reset(); m.setRotate((t * 60f) % 360f, cx, cy); sg.setLocalMatrix(m)
        ring.shader = sg; ring.strokeWidth = r * 0.12f
        canvas.drawCircle(cx, cy, r, ring)
        tp.textSize = r * 0.72f
        tp.shader = LinearGradient(cx - r, cy, cx + r, cy, DkaiTheme.CYAN, DkaiTheme.VIOLET, Shader.TileMode.CLAMP)
        canvas.drawText("AI", cx, cy - (tp.descent() + tp.ascent()) / 2f, tp)
        again()
    }
}
