package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 15 नवंबर 2026 (मालिक: "फ़्यूचरिस्टिक तरीके से स्कैन - स्कैन हो जाए तो BOSS AUTHORIZED, बॉस नहीं तो DECLINED"):
 * कैमरे के ऊपर hologram जैसा स्कैनर -
 *  - चेहरे पर घूमता 3D जाल ([HoloMesh]), ऊपर-नीचे चलती लेज़र रेखा, घूमते टुकड़ों वाले छल्ले, कोनों के ब्रैकेट,
 *  - नीचे तीन कदम (●●○), MATCH / LIVENESS / AI ANTI-SPOOF की पट्टियाँ,
 *  - फ़ैसला: हरा "BOSS AUTHORIZED" / लाल काँपता "DECLINED" (और कारण),
 *  - DK लेंस में कई चेहरे: हर चेहरे पर नाम + रिश्ता (दोस्त / भाई / मम्मी ...).
 * गार्ड (टच मोड) अलार्म, चेहरा सिखाने वाले dialog और DK लेंस - तीनों में यही.
 * सारे निर्देशांक (coordinates) इसी View के हिसाब से - [place] फ़्रेम से बदल देता है.
 */
class HoloScanView(context: Context) : View(context) {
    enum class Mode { IDLE, SEARCH, SCAN, AUTHORIZED, DECLINED }

    /** tone: [SCAN] नीला, [OK] हरा, [BAD] लाल, [WARN] पीला */
    class Face(val box: RectF, val kps: FloatArray?, val title: String = "", val sub: String = "", val tone: Int = SCAN) {
        internal var mesh: FloatArray? = null
    }

    @Volatile private var mode = Mode.IDLE
    @Volatile private var faces: List<Face> = emptyList()
    @Volatile private var steps = -1
    @Volatile private var stepsTotal = 3
    @Volatile private var bars: List<Pair<String, Float>> = emptyList()
    @Volatile private var banner = ""
    @Volatile private var reason = ""
    @Volatile private var bannerAt = 0L
    /** छोटी जगह (DK लेंस का कोना) = कम लिखावट */
    var compact = false

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mesh = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.7f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 11f * d }
    private val dash = DashPathEffect(floatArrayOf(10f * d, 7f * d), 0f)
    private val r = RectF()

    init { setWillNotDraw(false); setLayerType(LAYER_TYPE_HARDWARE, null) }

    /**
     * नई हालत (किसी भी thread से). [faces] इस View के निर्देशांक में ([place]). [steps] = -1 (नहीं दिखाना) या 0..3.
     * [bars] = ("MATCH", 0..1) जैसे. [banner] ख़ाली = कोई फ़ैसला नहीं.
     */
    fun show(mode: Mode, faces: List<Face>, steps: Int = -1, bars: List<Pair<String, Float>> = emptyList(), banner: String = "", reason: String = "", total: Int = 3) {
        stepsTotal = total.coerceIn(1, 8)
        faces.forEach { f -> f.kps?.let { k -> f.mesh = try { HoloMesh.lines(k) } catch (_: Throwable) { null } } }
        if (banner != this.banner) bannerAt = SystemClock.uptimeMillis()
        this.mode = mode; this.faces = faces; this.steps = steps; this.bars = bars; this.banner = banner; this.reason = reason
        postInvalidateOnAnimation()
    }

    fun clear() = show(Mode.IDLE, emptyList())

    /**
     * फ़्रेम (fw x fh, बिना शीशे वाला) का चेहरा -> इस View पर (PreviewView FILL_CENTER जैसा). [mirror] = आगे वाला कैमरा.
     */
    fun place(det: FaceMath.Det, fw: Int, fh: Int, mirror: Boolean, title: String = "", sub: String = "", tone: Int = SCAN): Face? {
        if (width <= 0 || height <= 0) return null
        val m = FaceMath.viewMap(fw, fh, width, height)
        fun x(v: Float) = (v * m[0] + m[1]).let { if (mirror) width - it else it }
        fun y(v: Float) = v * m[0] + m[2]
        val l = x(det.x); val rr = x(det.x + det.w)
        val box = RectF(min(l, rr), y(det.y), max(l, rr), y(det.y + det.h))
        val k = if (det.kps.size >= 10) FloatArray(10) { i -> if (i % 2 == 0) x(det.kps[i]) else y(det.kps[i]) } else null
        return Face(box, k, title, sub, tone)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (mode == Mode.IDLE) return
        val t = SystemClock.uptimeMillis()
        val w = width.toFloat(); val h = height.toFloat()
        val main = when (mode) { Mode.AUTHORIZED -> OK; Mode.DECLINED -> BAD; else -> SCAN }
        frame(c, w, h, main, t)
        val fs = faces
        if (fs.isEmpty() && mode == Mode.SEARCH) search(c, w, h, t)
        fs.forEachIndexed { i, f -> face(c, f, if (mode == Mode.AUTHORIZED || mode == Mode.DECLINED) main else f.tone, t, i == 0 && fs.size == 1) }
        hud(c, w, h, main)
        if (banner.isNotBlank()) banner(c, w, h, main, t)
        postInvalidateOnAnimation()
    }

    /** पूरे कैमरे के कोने + हल्की ग्रिड */
    private fun frame(c: Canvas, w: Float, h: Float, tone: Int, t: Long) {
        line.color = alpha(tone, 0.85f); line.pathEffect = null
        val k = min(w, h) * 0.09f; val p = 6 * d
        for ((x, y, sx, sy) in listOf(Q(p, p, 1f, 1f), Q(w - p, p, -1f, 1f), Q(p, h - p, 1f, -1f), Q(w - p, h - p, -1f, -1f))) {
            c.drawLine(x, y, x + k * sx, y, line); c.drawLine(x, y, x, y + k * sy, line)
        }
        if (!compact) {
            fill.color = alpha(tone, 0.06f)
            val g = 28 * d; var gx = (t / 40 % g.toLong()).toFloat()
            while (gx < w) { c.drawRect(gx, 0f, gx + 0.6f * d, h, fill); gx += g }
            var gy = 0f
            while (gy < h) { c.drawRect(0f, gy, w, gy + 0.6f * d, fill); gy += g }
        }
    }

    /** चेहरा नहीं मिला - बीच में घूमता खोजी छल्ला */
    private fun search(c: Canvas, w: Float, h: Float, t: Long) {
        val cx = w / 2; val cy = h / 2; val rad = min(w, h) * 0.28f
        rings(c, cx, cy, rad, SCAN, t)
        sweep(c, RectF(cx - rad, cy - rad * 1.2f, cx + rad, cy + rad * 1.2f), SCAN, t)
        if (!compact) label(c, "SEARCHING FACE…", cx, cy + rad * 1.35f, SCAN, center = true)
    }

    private fun face(c: Canvas, f: Face, tone: Int, t: Long, primary: Boolean) {
        val b = f.box; val cx = b.centerX(); val cy = b.centerY()
        // जाल
        f.mesh?.takeIf { it.isNotEmpty() }?.let { m ->
            val pulse = 0.35f + 0.2f * sin(t / 260.0).toFloat()
            mesh.color = alpha(tone, pulse); c.drawLines(m, mesh)
        }
        // कोनों वाला डिब्बा
        line.color = alpha(tone, 0.95f); line.pathEffect = null
        val k = min(b.width(), b.height()) * 0.22f
        for ((x, y, sx, sy) in listOf(Q(b.left, b.top, 1f, 1f), Q(b.right, b.top, -1f, 1f), Q(b.left, b.bottom, 1f, -1f), Q(b.right, b.bottom, -1f, -1f))) {
            c.drawLine(x, y, x + k * sx, y, line); c.drawLine(x, y, x, y + k * sy, line)
        }
        if (primary && mode != Mode.AUTHORIZED) rings(c, cx, cy, max(b.width(), b.height()) * 0.78f, tone, t)
        if (mode == Mode.SCAN || mode == Mode.SEARCH) sweep(c, b, tone, t)
        // आँख/नाक/होंठ के बिंदु
        f.kps?.let { kp -> fill.color = alpha(tone, 0.95f); for (i in 0 until 5) c.drawCircle(kp[i * 2], kp[i * 2 + 1], 2.2f * d, fill) }
        // नाम + रिश्ता
        if (f.title.isNotBlank()) {
            val y0 = b.top - 8 * d
            text.textSize = (if (compact) 10f else 13f) * d
            val tw = text.measureText(f.title); val sw = if (f.sub.isBlank()) 0f else run { text.textSize = (if (compact) 9f else 11f) * d; text.measureText(f.sub) }
            val bw = max(tw, sw) + 12 * d; val bh = if (f.sub.isBlank()) 18 * d else 32 * d
            val bx = (cx - bw / 2).coerceIn(2 * d, max(2 * d, width - bw - 2 * d)); val by = (y0 - bh).coerceAtLeast(2 * d)
            fill.color = 0xB0000A12.toInt(); r.set(bx, by, bx + bw, by + bh); c.drawRoundRect(r, 4 * d, 4 * d, fill)
            line.color = alpha(tone, 0.9f); c.drawRoundRect(r, 4 * d, 4 * d, line)
            text.textSize = (if (compact) 10f else 13f) * d; text.color = alpha(tone, 1f)
            c.drawText(f.title, bx + 6 * d, by + 13 * d, text)
            if (f.sub.isNotBlank()) { text.textSize = (if (compact) 9f else 11f) * d; text.color = 0xE6FFFFFF.toInt(); c.drawText(f.sub, bx + 6 * d, by + 27 * d, text) }
        }
    }

    /** घूमते टुकड़ों वाले छल्ले (दो उल्टी दिशा में) */
    private fun rings(c: Canvas, cx: Float, cy: Float, rad: Float, tone: Int, t: Long) {
        val a = (t % 6000) / 6000f * 360f
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        line.color = alpha(tone, 0.75f); line.pathEffect = null
        for (i in 0 until 4) c.drawArc(r, a + i * 90f, 58f, false, line)
        val r2 = rad * 0.9f; r.set(cx - r2, cy - r2, cx + r2, cy + r2)
        line.color = alpha(tone, 0.4f); line.pathEffect = dash
        c.drawArc(r, -a * 1.4f, 300f, false, line)
        line.pathEffect = null
        // टिक निशान
        fill.color = alpha(tone, 0.7f)
        for (i in 0 until 24) {
            val an = Math.toRadians((i * 15 + a / 2).toDouble())
            val x = cx + (rad * 1.06f) * kotlin.math.cos(an).toFloat(); val y = cy + (rad * 1.06f) * kotlin.math.sin(an).toFloat()
            c.drawCircle(x, y, if (i % 6 == 0) 2f * d else 1f * d, fill)
        }
    }

    /** ऊपर-नीचे चलती लेज़र */
    private fun sweep(c: Canvas, b: RectF, tone: Int, t: Long) {
        val ph = ((t % 1800) / 1800f)
        val y = b.top + b.height() * (0.5f - 0.5f * kotlin.math.cos(ph * 2 * PI).toFloat())
        fill.shader = LinearGradient(b.left, y - 22 * d, b.left, y, Color.TRANSPARENT, alpha(tone, 0.35f), Shader.TileMode.CLAMP)
        c.drawRect(b.left, y - 22 * d, b.right, y, fill); fill.shader = null
        line.color = alpha(tone, 1f); line.pathEffect = null
        c.drawLine(b.left - 6 * d, y, b.right + 6 * d, y, line)
    }

    /** नीचे: कदम + पट्टियाँ */
    private fun hud(c: Canvas, w: Float, h: Float, tone: Int) {
        var y = h - 10 * d
        if (!compact) {
            for ((name, v) in bars.reversed()) {
                val bw = w * 0.42f; val x = 10 * d
                text.textSize = 9.5f * d; text.color = 0xD9FFFFFF.toInt(); c.drawText(name, x, y - 7 * d, text)
                fill.color = 0x40FFFFFF; c.drawRect(x + w * 0.2f, y - 13 * d, x + w * 0.2f + bw, y - 8 * d, fill)
                fill.color = alpha(if (v >= 0.55f) OK else if (v < 0.3f) BAD else WARN, 0.95f)
                c.drawRect(x + w * 0.2f, y - 13 * d, x + w * 0.2f + bw * v.coerceIn(0f, 1f), y - 8 * d, fill)
                text.color = 0xD9FFFFFF.toInt(); c.drawText("${(v * 100).toInt()}%", x + w * 0.2f + bw + 5 * d, y - 7 * d, text)
                y -= 15 * d
            }
        }
        if (steps >= 0) {
            val sp = 18 * d; val x0 = w / 2 - sp * (stepsTotal - 1) / 2f
            for (i in 0 until stepsTotal) {
                fill.color = if (i < steps) alpha(OK, 1f) else 0x55FFFFFF
                c.drawCircle(x0 + i * sp, y - 6 * d, 4.5f * d, fill)
            }
        }
    }

    /** फ़ैसला: AUTHORIZED (हरा, धीरे फैलता) / DECLINED (लाल, काँपता glitch) */
    private fun banner(c: Canvas, w: Float, h: Float, tone: Int, t: Long) {
        val age = (t - bannerAt).coerceAtLeast(0)
        val grow = min(1f, age / 260f)
        text.textSize = (if (compact) 15f else 24f) * d * (0.8f + 0.2f * grow)
        val tw = text.measureText(banner)
        val cx = w / 2; val cy = h * 0.5f
        val bw = tw + 30 * d; val bh = text.textSize * (if (reason.isBlank()) 1.9f else 2.9f)
        fill.color = 0xC8000000.toInt(); r.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2); c.drawRoundRect(r, 8 * d, 8 * d, fill)
        line.color = alpha(tone, 1f); line.pathEffect = null; line.strokeWidth = 2.2f * d; c.drawRoundRect(r, 8 * d, 8 * d, line); line.strokeWidth = 1.6f * d
        val glitch = mode == Mode.DECLINED && (age / 90) % 3 == 0L && age < 1600
        val ty = cy - (if (reason.isBlank()) 0f else text.textSize * 0.45f) + text.textSize * 0.35f
        if (glitch) { text.color = 0xAA00E5FF.toInt(); c.drawText(banner, cx - tw / 2 - 3 * d, ty, text) }
        text.color = alpha(tone, 1f); c.drawText(banner, cx - tw / 2 + if (glitch) 3 * d else 0f, ty, text)
        if (reason.isNotBlank()) {
            text.textSize = (if (compact) 9f else 12f) * d; text.color = 0xEEFFFFFF.toInt()
            val rw = text.measureText(reason); c.drawText(reason, cx - min(rw, w - 8 * d) / 2, ty + text.textSize * 1.8f, text)
        }
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, tone: Int, center: Boolean) {
        text.textSize = 12f * d; text.color = alpha(tone, 0.95f)
        c.drawText(s, if (center) x - text.measureText(s) / 2 else x, y, text)
    }

    private data class Q(val a: Float, val b: Float, val c: Float, val d: Float)

    companion object {
        const val SCAN = 0xFF00E5FF.toInt()
        const val OK = 0xFF00FF9C.toInt()
        const val BAD = 0xFFFF2D55.toInt()
        const val WARN = 0xFFFFC400.toInt()
        const val AUTHORIZED = "BOSS AUTHORIZED"
        const val DECLINED = "DECLINED"
        fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
