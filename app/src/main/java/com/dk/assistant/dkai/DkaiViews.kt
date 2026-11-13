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

    /**
     * 13 नवंबर 2026 (मालिक - वॉइस-कॉल): कॉल की हालत (-1 = कोई नहीं / पुराना जैसा, [LISTEN], [THINK], [SPEAK]).
     * सिर्फ़ FLUID orb पर: सुनना = गुलाबी/नीली/आसमानी लहरें + धीमी साँस, बोलना = आवाज़ के साथ लहरें और बाहर की
     * पतली आसमानी रिंग फैले-सिकुड़े, सोचना = बैंगनी-गुलाबी घूमता रंग. रंग अचानक नहीं, धीरे-धीरे बदलते हैं.
     */
    var call: () -> Int = { -1 }
    private val cur = intArrayOf(0, 0, 0)
    private var curSet = false
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val flow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val flowPath = Path()
    private var spin = 0f
    private var lastT = 0f

    companion object {
        const val LISTEN = 0; const val THINK = 1; const val SPEAK = 2
        private val P_LISTEN = intArrayOf(0xFFF472B6.toInt(), 0xFF3B82F6.toInt(), 0xFF22D3EE.toInt())
        private val P_THINK = intArrayOf(0xFFA855F7.toInt(), 0xFFEC4899.toInt(), 0xFF7C3AED.toInt())
        private val P_SPEAK = intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt(), 0xFF1E3A8A.toInt())
        fun mix(a: Int, b: Int, k: Float): Int {
            fun ch(sh: Int) = (((a shr sh) and 0xFF) + ((((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * k)).toInt().coerceIn(0, 255)
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    init { setLayerType(LAYER_TYPE_HARDWARE, null); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** 13 नवंबर 2026 (मालिक): orb के बीच में DK लोगो (हर orb style पर) */
    var logo = true
    private val dkLogo: android.graphics.drawable.Drawable? by lazy { try { androidx.core.content.ContextCompat.getDrawable(context, com.dk.assistant.R.drawable.ic_dk)?.mutate() } catch (_: Throwable) { null } }
    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (!logo) return
        val lg = dkLogo ?: return
        val half = (min(width, height) / 2f * (if (glow) 0.66f else 0.86f) * 0.42f * (1f + lv * 0.08f)).toInt()
        if (half <= 2) return
        val cx = width / 2; val cy = height / 2
        lg.setBounds(cx - half, cy - half, cx + half, cy + half); lg.alpha = 235; lg.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        step()
        val t = secs()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) { again(); return }
        val cx = w / 2f; val cy = h / 2f
        val on = try { active() } catch (_: Exception) { false }
        val st = try { style() } catch (_: Exception) { DkaiOrbs.FLUID }
        val cs = if (st == DkaiOrbs.FLUID) (try { call() } catch (_: Exception) { -1 }) else -1
        val dt = (t - lastT).coerceIn(0f, 0.1f); lastT = t
        val breathe = when (cs) {
            LISTEN -> sin(t * 1.1f) * 0.035f                                   // धीमी साँस
            THINK -> sin(t * 3.2f) * 0.012f
            else -> sin(t * (if (on) 2.4f else 1.3f)) * 0.025f
        }
        val R = min(w, h) / 2f * (if (glow) 0.66f else 0.86f) * (1f + breathe + lv * (if (cs == SPEAK) 0.16f else 0.12f))
        pal = DkaiOrbs.palette(st)
        if (cs >= 0) {
            val target = when (cs) { THINK -> P_THINK; SPEAK -> P_SPEAK; else -> P_LISTEN }
            if (!curSet) { target.copyInto(cur); curSet = true }
            val k = (dt * 6f).coerceIn(0.05f, 1f)                              // ~0.2 सेकंड में नया रंग
            for (i in 0..2) cur[i] = mix(cur[i], target[i], k)
            pal = cur
        }
        spin = (spin + dt * (if (cs == THINK) 150f else 25f)) % 360f
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
        val lg: Shader = if (cs == THINK) SweepGradient(cx, cy, intArrayOf(pal[0], pal[1], pal[2], pal[0]), null)
            else LinearGradient(cx - R, cy - R, cx + R, cy + R, intArrayOf(pal[0], pal[1], pal[2]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        m.reset(); m.setRotate(if (cs >= 0) spin else (t * 25f) % 360f, cx, cy); lg.setLocalMatrix(m)
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
        if (cs >= 0) flowWaves(canvas, cx, cy, R, t, cs)
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
        if (cs >= 0 && glow) {
            // बाहर की पतली आसमानी रिंग: बोलते समय आवाज़ के साथ फैले-सिकुड़े, सुनते समय साँस के साथ
            val grow = when (cs) { SPEAK -> 0.06f + lv * 0.16f; THINK -> 0.08f + sin(t * 4f) * 0.015f; else -> 0.08f + breathe }
            val rr = min(R * (1.12f + grow), min(w, h) / 2f * 0.97f)
            ring.strokeWidth = DkaiTheme.dpf(context, 1.6f)
            ring.color = if (DkaiTheme.light) 0xFF38BDF8.toInt() else 0xFF7DD3FC.toInt()
            ring.alpha = (150 + lv * 90).toInt().coerceAtMost(240)
            canvas.drawCircle(cx, cy, rr, ring)
        }
        again()
    }

    /** orb के अंदर बहती चमकदार लहरें (बोलते समय ऊँची, सुनते समय धीमी) - Path दोबारा इस्तेमाल, हर फ़्रेम नई चीज़ नहीं */
    private fun flowWaves(canvas: Canvas, cx: Float, cy: Float, R: Float, t: Float, cs: Int) {
        val speed = when (cs) { SPEAK -> 2.6f + lv * 3f; THINK -> 3.4f; else -> 1.2f }
        val ampK = when (cs) { SPEAK -> 0.06f + lv * 0.2f; THINK -> 0.08f; else -> 0.06f }
        flow.strokeWidth = max(1.5f, R * 0.045f)
        for (k in 0 until 3) {
            flowPath.reset()
            val y0 = cy + (k - 1) * R * 0.28f
            val amp = R * ampK * (1f - k * 0.18f)
            var x = cx - R
            while (x <= cx + R) {
                val u = (x - cx) / R
                val y = y0 + amp * sin(u * (3.2f + k) + t * speed * (if (k % 2 == 0) 1f else -0.8f) + k * 1.7f)
                if (x == cx - R) flowPath.moveTo(x, y) else flowPath.lineTo(x, y)
                x += R / 18f
            }
            flow.color = if (k == 1) 0xFFFFFFFF.toInt() else pal[k]
            flow.alpha = (if (k == 1) 0x70 else 0x9A) + (lv * 0x40).toInt()
            canvas.drawPath(flowPath, flow)
        }
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

    /**
     * 13 नवंबर 2026 (मालिक - वॉइस-कॉल): null = पुरानी लहरें. दिया हो = खड़ी नीली पट्टियाँ (true = DK बोल रहा,
     * आवाज़ के हिसाब से ऊपर-नीचे) / छोटे बिंदुओं की कतार (false = सुन रहा या चुप).
     */
    var bars: (() -> Boolean)? = null
    /** पट्टियों की गिनती (छोटी पट्टी में कम) */
    var count = 28
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private var show = 0f                                    // 0 = बिंदु, 1 = पट्टियाँ (धीरे बदले)

    private fun drawBars(canvas: Canvas, speaking: Boolean, t: Float, w: Float, h: Float) {
        show += ((if (speaking) 1f else 0f) - show) * 0.25f
        val n = count.coerceAtLeast(4)
        val gap = w / n
        val bw = min(gap * 0.5f, DkaiTheme.dpf(context, 4f))
        val dotR = min(bw * 0.5f, DkaiTheme.dpf(context, 2f))
        val mid = h / 2f
        bp.strokeWidth = bw
        val c1 = if (DkaiTheme.light) 0xFF2563EB.toInt() else 0xFF38BDF8.toInt()
        val c2 = if (DkaiTheme.light) 0xFF0EA5E9.toInt() else 0xFF3B82F6.toInt()
        for (i in 0 until n) {
            val x = gap * (i + 0.5f)
            val u = i / (n - 1f)
            val env = 0.35f + 0.65f * sin(u * PI.toFloat())
            // आवाज़ के ज़ोर से: हर पट्टी अपनी रफ़्तार से (असली ज़ोर lv पर टिकी)
            val wob = 0.5f + 0.5f * sin(t * (7f + (i % 5) * 1.3f) + i * 0.9f)
            val barH = (h * 0.9f) * env * (0.12f + lv * 0.88f) * (0.45f + 0.55f * wob)
            val hh = max(dotR * 2f, barH * show + dotR * 2f * (1f - show))
            bp.color = FluidOrbView.mix(c1, c2, u)
            bp.alpha = (150 + 105 * show).toInt()
            canvas.drawLine(x, mid - hh / 2f + bw / 2f, x, mid + hh / 2f - bw / 2f, bp)
        }
    }

    override fun onDraw(canvas: Canvas) {
        step()
        val b = bars
        if (b != null) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w > 0f) drawBars(canvas, try { b() } catch (_: Exception) { false }, secs(), w, h)
            again(); return
        }
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

/** बड़ा नीला माइक - बाहर लहरें (ripple) फैलती हैं; [muted] = लाल कटा माइक, शांत */
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
        // 13 नवंबर 2026 (मालिक - वॉइस-कॉल): म्यूट = लाल गोला + कटा हुआ माइक
        g.shader = if (muted) RadialGradient(cx, cy, core * 1.45f, intArrayOf(0x66EF4444, 0x00EF4444), null, Shader.TileMode.CLAMP)
            else RadialGradient(cx, cy, core * 1.45f, intArrayOf(0x883B82F6.toInt(), 0x003B82F6), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, core * 1.45f, g)
        p.alpha = 255
        p.color = if (muted) DkaiTheme.RED else DkaiTheme.BLUE
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
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DkaiTheme.ON; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val m = Matrix()
    private val logo: android.graphics.drawable.Drawable? = try { androidx.core.content.ContextCompat.getDrawable(c, com.dk.assistant.R.drawable.ic_dk)?.mutate() } catch (_: Throwable) { null }
    init { contentDescription = "DKAI" }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f * 0.62f
        val t = secs()
        val gr = min(r * (1.6f + 0.06f * sin(t * 2f)), min(w, h) / 2f * 0.98f)
        glowP.shader = RadialGradient(cx, cy, gr, intArrayOf(0x6600E5FF, 0x33A855F7, 0x00A855F7), floatArrayOf(0.4f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, gr, glowP)
        fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f, intArrayOf(DkaiTheme.BADGE_CORE, DkaiTheme.LOGO_TILE), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        val sg = SweepGradient(cx, cy, intArrayOf(DkaiTheme.ICON_CYAN, DkaiTheme.VIOLET, DkaiTheme.BLUE, DkaiTheme.ICON_CYAN), null)
        m.reset(); m.setRotate((t * 60f) % 360f, cx, cy); sg.setLocalMatrix(m)
        ring.shader = sg; ring.strokeWidth = r * 0.12f
        canvas.drawCircle(cx, cy, r, ring)
        // 13 नवंबर 2026 (मालिक): बीच में "AI" की जगह अपना DK लोगो
        val lg = logo
        if (lg != null) {
            val half = (r * 0.82f).toInt()
            lg.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt()); lg.draw(canvas)
        } else {
            tp.textSize = r * 0.72f
            tp.shader = LinearGradient(cx - r, cy, cx + r, cy, DkaiTheme.ICON_CYAN, DkaiTheme.VIOLET, Shader.TileMode.CLAMP)
            canvas.drawText("DK", cx, cy - (tp.descent() + tp.ascent()) / 2f, tp)
        }
        again()
    }
}
