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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 3D orb: chhote-chhote dots se bane aakaar (prithvi, galaxy, atom, chhalla, jaali, gaanth, dyson, purush/mahila HD chehra).
 * Aakaar badalne par dots bikhar kar ude aur naya aakaar banate hain ("apna chehra dikhao" animation).
 * Rang DK ki haalat se: neela = taiyaar, laal = sun raha, baingani = soch raha, hara = kaam, firozi = bol raha
 */
class OrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** chhota bubble (sab apps ke upar tairta): kam bindu; v3.41 se max ~30 frame/sec (pehle 60 - poore phone me lag) */
    var mini = false
        set(v) { field = v; alloc(if (v) MINI_DOTS else FULL_DOTS) }

    /** Settings me preview ke liye - null ho to Prefs wala aakaar */
    var previewStyle: String? = null

    private var n = 0
    // from (purana aakaar) -> to (naya aakaar)
    private var fx = FloatArray(0); private var fy = FloatArray(0); private var fz = FloatArray(0)
    private var mx = FloatArray(0); private var my = FloatArray(0); private var mz = FloatArray(0)
    private var kFrom = ByteArray(0)
    private var delay = FloatArray(0)
    private var sx = FloatArray(0); private var sy = FloatArray(0); private var sz = FloatArray(0)
    private var cloud: Cloud = Shapes.earth(1)
    private var target = ""
    private var morphStart = 0L
    private var angStart = 0f; private var tiltFrom = 0.41f

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowM = android.graphics.Matrix()
    private var glowCol = 0
    private var glowAlpha = -1
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var ang = 0f
    private var tilt = 0.41f
    private var lastT = 0L
    private var lvl = 0f
    private var colA = Color.parseColor("#2979FF")
    private var colB = Color.parseColor("#00E5FF")
    private var styleCache = "earth"; private var genderCache = "female"; private var cacheT = 0L

    var onFaceShown: (() -> Unit)? = null

    init { alloc(FULL_DOTS) }

    private fun alloc(count: Int) {
        n = count
        fx = FloatArray(n); fy = FloatArray(n); fz = FloatArray(n)
        mx = FloatArray(n); my = FloatArray(n); mz = FloatArray(n)
        sx = FloatArray(n); sy = FloatArray(n); sz = FloatArray(n)
        kFrom = ByteArray(n)
        val r = Random(3)
        delay = FloatArray(n) { r.nextFloat() }
        cloud = Shapes.earth(n)
        for (i in 0 until n) { mx[i] = cloud.x[i]; my[i] = cloud.y[i]; mz[i] = cloud.z[i]; kFrom[i] = cloud.kind[i] }
        target = "earth"; morphStart = 0L; tilt = cloud.tilt
    }

    private fun wanted(): String {
        if (mini) return "earth"
        previewStyle?.let { return it }
        val now = System.currentTimeMillis()
        if (now - cacheT > 700) {
            cacheT = now
            styleCache = Prefs.orbStyle(context); genderCache = Prefs.voiceGender(context)
        }
        if (now < DKState.faceUntil) return if (genderCache == "male") "face_male" else "face_female"
        return styleCache
    }

    private fun morphTo(style: String) {
        for (i in 0 until n) { fx[i] = mx[i]; fy[i] = my[i]; fz[i] = mz[i] }
        val old = cloud
        for (i in 0 until n) kFrom[i] = old.kind[i]
        cloud = Shapes.make(style, n)
        target = style
        morphStart = SystemClock.uptimeMillis()
        angStart = atan2(sin(ang), cos(ang)); tiltFrom = tilt
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

    // v3.41: har bindu, har frame Math.pow (1400 x 60/sec) ki jagah pehle se bani taalika
    private fun pw16(d: Float) = P16[(d * LUT).toInt().coerceIn(0, LUT)]
    private fun pw14(d: Float) = P14[(d * LUT).toInt().coerceIn(0, LUT)]
    private fun pw13(d: Float) = P13[(d * LUT).toInt().coerceIn(0, LUT)]

    private fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
    private fun ease(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceAtMost(0.05f)
        lastT = now
        val t = now / 1000f
        // v3.88: सिर्फ़ तीन रूप - Listening / Thinking (WORKING भी) / Speaking
        val mode = ThreeState.look(DKState.mode, DKState.live, DKState.working())

        val want = wanted()
        if (want != target) morphTo(want)
        // v4: नियॉन ओर्ब - डॉट नहीं, परतों वाला चमकदार गोला (रंग/चमक DK की असली हालत से)
        if (target == Shapes.NEON && !mini) { drawNeon(c, mode, now, dt); return }
        val cl = cloud
        val isFace = cl.sway
        val morphMs = if (isFace) 2300f else 1600f
        val p = if (morphStart == 0L) 1f else ((now - morphStart) / morphMs).coerceIn(0f, 1f)
        val morphing = p < 1f
        val ep = ease(p)
        if (!morphing && morphStart != 0L) { morphStart = 0L; if (isFace) onFaceShown?.invoke() }

        val speed = when (mode) {
            Mode.IDLE -> 0.28f; Mode.LISTENING -> 0.55f; Mode.THINKING -> 1.8f
            Mode.WORKING -> 1.1f; Mode.SPEAKING -> 0.7f
        }
        if (cl.sway) {
            val sw = 0.38f * sin(t * 0.45f) + 0.08f * sin(t * 1.3f)
            ang = if (morphing) angStart + (sw - angStart) * ep else sw
            val nod = 0.06f * sin(t * 0.6f)
            tilt = if (morphing) tiltFrom + (nod - tiltFrom) * ep else nod
        } else {
            ang += speed * cl.spin * dt
            tilt = if (morphing) tiltFrom + (cl.tilt - tiltFrom) * ep else cl.tilt
        }
        lvl += (DKState.level - lvl) * 0.25f
        val (ta, tb) = targetColors(mode)
        // v3.8: haalat ka rang ~150ms me (pehle ~600ms lagte the - purani haalat atki si lagti thi)
        val k = (1f - kotlin.math.exp(-dt * 20f)).coerceIn(0.08f, 1f)
        colA = lerpColor(colA, ta, k); colB = lerpColor(colB, tb, k)
        val female = target == "face_female"
        val tint = if (female) Color.parseColor("#FF5CC8") else Color.parseColor("#4FC3FF")
        val faceA = if (isFace) lerpColor(colA, tint, 0.35f) else colA
        val faceB = if (isFace) lerpColor(colB, Color.WHITE, 0.2f) else colB

        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val baseR = minOf(w, h) * (if (mini) 0.36f else if (isFace) 0.40f else 0.30f) *
            (if (morphing && isFace) 0.75f + 0.25f * ep else 1f)
        val breathe = 1f + 0.025f * sin(t * 1.6f)
        // v3.68: लिप-सिंक - जो आवाज़ सच में बज रही है उसका ज़ोर (कोई भी आवाज़ इंजन); न हो तो पुराना अंदाज़ा
        val voice = if (mode == Mode.SPEAKING) com.dk.assistant.voice.engine.VoiceLevel.mouth() else null
        val pulse = when {
            isFace -> 1f
            mode == Mode.LISTENING -> 1f + lvl * 0.14f
            mode == Mode.SPEAKING -> 1f + (voice?.let { it * 0.12f } ?: (0.04f * sin(t * 9f)))
            // v3.8: soch raha hai - halka dheema pulse (~1.1s), sannata na lage
            mode == Mode.THINKING -> 1f + 0.045f * (0.5f + 0.5f * sin(t * 5.6f))
            else -> 1f
        }
        val R = baseR * breathe * pulse
        // thinking me glow bhi saath-saath halka tez-dheema
        val glowA = if (mode == Mode.THINKING && !isFace) (105 + 60 * (0.5f + 0.5f * sin(t * 5.6f))).toInt() else if (isFace) 70 else 120

        // ---- glow
        // v3.41: shader sirf rang/chamak badalne par naya (pehle har frame naya) - jagah/naap Matrix se
        if (glowCol != faceA || glowAlpha != glowA || glow.shader == null) {
            glowCol = faceA; glowAlpha = glowA
            glow.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(withAlpha(faceA, glowA), withAlpha(faceA, 30), Color.TRANSPARENT),
                floatArrayOf(0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        glowM.setScale(R * 1.75f, R * 1.75f); glowM.postTranslate(cx, cy)
        glow.shader.setLocalMatrix(glowM)
        c.drawCircle(cx, cy, R * 1.75f, glow)

        val cosA = cos(ang); val sinA = sin(ang)
        val cosT = cos(tilt); val sinT = sin(tilt)
        val dotR = (if (mini) 1.6f else 2.3f) * resources.displayMetrics.density * (baseR / (if (mini) 90f else 330f)).coerceIn(0.6f, 1.6f) *
            (if (isFace) 0.8f else 1f)
        val wave = if (isFace) 0f else when (mode) {
            Mode.THINKING -> 0.05f; Mode.SPEAKING -> 0.035f; Mode.LISTENING -> lvl * 0.06f; else -> 0.0f
        }
        // chehra: palak jhapakna + bolte waqt muh khulna
        val blinkPh = (t % 4.2f)
        val blink = if (blinkPh < 0.14f) (1f - sin(blinkPh / 0.14f * PI.toFloat())).coerceAtLeast(0.08f) else 1f
        val talk = if (voice != null) (voice * 1.15f).coerceIn(0f, 1f)
            else if (mode == Mode.SPEAKING) (0.5f + 0.5f * sin(t * 13f)) * (0.6f + 0.4f * sin(t * 3.7f)).coerceAtLeast(0.2f)
            else if (mode == Mode.LISTENING) 0.05f else 0f

        // ---- dots ka hisaab
        for (i in 0 until n) {
            val e = if (!morphing) 1f else ease(((p - delay[i] * 0.35f) / 0.65f).coerceIn(0f, 1f))
            var x0 = fx[i] + (cl.x[i] - fx[i]) * e
            var y0 = fy[i] + (cl.y[i] - fy[i]) * e
            var z0 = fz[i] + (cl.z[i] - fz[i]) * e
            if (isFace && e > 0.95f) {
                val k = cl.kind[i].toInt()
                if (k == 2) y0 = cl.anc[i] + (y0 - cl.anc[i]) * blink
                else if (k == 3) y0 += if (cl.lower[i]) -talk * 0.05f else talk * 0.012f
            }
            mx[i] = x0; my[i] = y0; mz[i] = z0
            if (morphing) {
                // bikhar kar udna (burst) + ghoomte hue jagah par aana
                val burst = 1f + 0.75f * sin(PI.toFloat() * e) * (0.6f + 0.8f * delay[i])
                val sw = (1f - e) * e * 5f * (if (i % 2 == 0) 1f else -1f)
                val cs = cos(sw); val sn = sin(sw)
                val rx = x0 * cs + z0 * sn; val rz = -x0 * sn + z0 * cs
                x0 = rx * burst; y0 *= burst; z0 = rz * burst
            }
            val x1 = x0 * cosA + z0 * sinA
            val z1 = -x0 * sinA + z0 * cosA
            val y2 = y0 * cosT - z1 * sinT
            val z2 = y0 * sinT + z1 * cosT
            val rr = 1f + wave * sin(7f * y0 + 5f * x0 + t * 5f)
            val persp = 4f / (4f - z2.coerceIn(-3f, 2.5f))
            sx[i] = cx + x1 * R * rr * persp
            sy[i] = cy - y2 * R * rr * persp
            sz[i] = z2
        }
        // ---- pehle peeche wale, phir aage wale dots
        for (pass in 0..1) {
            for (i in 0 until n) {
                val z2 = sz[i]
                if ((z2 >= 0f) != (pass == 1)) continue
                val depth = ((z2 + 1f) / 2f).coerceIn(0f, 1f)
                val e = if (!morphing) 1f else ((p - delay[i] * 0.35f) / 0.65f).coerceIn(0f, 1f)
                val k = (if (e > 0.5f) cl.kind[i] else kFrom[i]).toInt()
                if (k == Shapes.HIDDEN) continue      // v3.65: सामान्य आकार में बचे डॉट छिपे (पहले जैसी रफ़्तार)
                val a0: Int; val col: Int; val sc: Float
                when (k) {
                    1 -> { a0 = (80 + 175 * pw16(depth)).toInt(); col = lerpColor(faceB, Color.WHITE, 0.25f * depth); sc = 1.25f }
                    2 -> { a0 = (150 + 105 * depth).toInt(); col = lerpColor(faceB, Color.WHITE, 0.6f); sc = 1.15f }
                    3 -> { a0 = (120 + 135 * depth).toInt(); col = lerpColor(faceB, tint, 0.5f); sc = 1.15f }
                    4 -> { a0 = (45 + 150 * pw13(depth)).toInt(); col = lerpColor(faceA, faceB, 0.35f); sc = 0.8f }
                    else -> {
                        a0 = if (isFace && !morphing) (30 + 150 * pw14(depth)).toInt() else (18 + 100 * pw16(depth)).toInt()
                        col = faceA; sc = 0.7f
                    }
                }
                // v3.65: चेहरे पर रोशनी-छाया (lum) - बाकी आकारों में lum = 1, कोई फ़र्क नहीं
                val lm = if (e > 0.5f) cl.lum[i] else 1f
                val a = if (lm >= 0.999f) a0 else (a0 * (if (k == 0 || k == 4) 0.35f + 0.65f * lm else 0.7f + 0.3f * lm)).toInt()
                dot.color = withAlpha(col, a)
                c.drawCircle(sx[i], sy[i], dotR * (0.45f + 0.75f * depth) * sc, dot)
            }
        }

        when {
            mini -> postInvalidateDelayed(if (mode == Mode.IDLE) 66 else 33)
            else -> postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------ v4 Neon orb
    private val nHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nSpec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nM = android.graphics.Matrix()
    private var nKey = 0L

    /** हालत के रंग (सिर्फ़ असली DKState.mode से - कोई नकली हालत नहीं) */
    private fun neonColors(m: Mode): Pair<Int, Int> {
        val l = com.dk.assistant.ui.NeonTheme.look
        return when (m) {
            Mode.IDLE -> l.accent.main to l.accent.partner
            Mode.LISTENING -> com.dk.assistant.ui.NeonTheme.CYAN to l.accent.main
            // v3.88: सोचते समय अलग रंग (सुनहरा-नारंगी), बोलते समय गुलाबी - तीनों साफ़ अलग
            Mode.THINKING, Mode.WORKING -> THINK_A to THINK_B
            Mode.SPEAKING -> SPEAK_A to SPEAK_B
        }
    }

    private fun near(a: Int, b: Int): Boolean {
        for (sh in intArrayOf(0, 8, 16, 24)) if (kotlin.math.abs(((a ushr sh) and 0xFF) - ((b ushr sh) and 0xFF)) > 2) return false
        return true
    }

    private fun drawNeon(c: Canvas, mode: Mode, now: Long, dt: Float) {
        val T = com.dk.assistant.ui.NeonTheme
        val still = T.reducedMotion()
        val t = now / 1000f
        lvl += (DKState.level - lvl) * 0.25f
        val (ta, tb) = neonColors(mode)
        val k = (1f - kotlin.math.exp(-dt * 20f)).coerceIn(0.08f, 1f)
        colA = lerpColor(colA, ta, k); colB = lerpColor(colB, tb, k)
        // लगभग पहुँच गए तो ठीक वही रंग (हर frame नया shader न बने)
        if (near(colA, ta)) colA = ta
        if (near(colB, tb)) colB = tb
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val voice = if (mode == Mode.SPEAKING) com.dk.assistant.voice.engine.VoiceLevel.mouth() else null
        val pulse = if (still) 1f else when (mode) {
            Mode.LISTENING -> 1f + lvl * 0.12f
            Mode.SPEAKING -> 1f + (voice?.let { it * 0.10f } ?: (0.03f * sin(t * 9f)))
            Mode.THINKING -> 1f + 0.04f * (0.5f + 0.5f * sin(t * 5.6f))
            else -> 1f + 0.018f * sin(t * 1.4f)
        }
        val R = minOf(w, h) * 0.27f * pulse
        val glowF = 0.35f + 0.65f * T.look.glowF

        // शेडर सिर्फ़ रंग/चमक बदलने पर नए (हर frame नहीं) - जगह/नाप Matrix से
        val key = (colA.toLong() shl 32) xor colB.toLong() xor (T.version.toLong() shl 50)
        if (key != nKey || nBody.shader == null) {
            nKey = key
            nHalo.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(T.alphaF(colA, 0.55f * glowF), T.alphaF(colB, 0.22f * glowF), T.alpha(colB, 0)),
                floatArrayOf(0.42f, 0.68f, 1f), Shader.TileMode.CLAMP)
            nBody.shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(T.mix(colA, Color.WHITE, 0.55f), colA, T.mix(colB, 0xFF0A0F3A.toInt(), 0.25f), T.mix(colB, 0xFF050A1F.toInt(), 0.55f)),
                floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP)
            nSpec.shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        // 1. बाहर की नियॉन चमक
        nM.setScale(R * 1.9f, R * 1.9f); nM.postTranslate(cx, cy); nHalo.shader.setLocalMatrix(nM)
        c.drawCircle(cx, cy, R * 1.9f, nHalo)
        val dpx = resources.displayMetrics.density
        // 3. गोला (ऊपर-बाएँ से रोशनी)
        nM.setScale(R * 1.25f, R * 1.25f); nM.postTranslate(cx - R * 0.28f, cy - R * 0.32f); nBody.shader.setLocalMatrix(nM)
        c.drawCircle(cx, cy, R, nBody)
        // 4. चमकदार किनारा + ऊपर की चमक
        nRim.strokeWidth = 1.4f * dpx; nRim.color = T.alphaF(T.mix(colB, Color.WHITE, 0.4f), 0.85f)
        c.drawCircle(cx, cy, R, nRim)
        nM.setScale(R * 0.42f, R * 0.30f); nM.postTranslate(cx - R * 0.32f, cy - R * 0.48f); nSpec.shader.setLocalMatrix(nM)
        c.drawCircle(cx - R * 0.32f, cy - R * 0.48f, R * 0.42f, nSpec)
        if (still) postInvalidateDelayed(if (mode == Mode.LISTENING || mode == Mode.SPEAKING) 120 else 400)
        else if (mode == Mode.IDLE) postInvalidateDelayed(33)      // खाली हालत: ~30fps काफ़ी (कमज़ोर फ़ोन/बैटरी)
        else postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ v3.88: तीन हालत, तीन अलग एनिमेशन

    /**
     * Listening = बाहर से अंदर सिमटते छल्ले (आवाज़ के ज़ोर से तेज़/चमकीले) - "सुन रही हूँ"
     * Thinking  = गोले के चारों ओर घूमते धूमकेतु-चाप और पीछा करते बिंदु - "सोच रही हूँ"
     * Speaking  = अंदर से बाहर फैलती लहरें (जो आवाज़ बज रही है उसके ज़ोर से) - "बोल रही हूँ"
     * कम-एनिमेशन (reduced motion) में स्थिर हल्का निशान, हरकत नहीं.
     */
    private fun drawStateFx(c: Canvas, cx: Float, cy: Float, R: Float, mode: Mode, t: Float, voice: Float?, still: Boolean) {
    }


    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastT = 0L; cacheT = 0L; invalidate() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow() }
    /** chhupa tha, phir dikha -> animation dobara (chhupe hue me koi kaam nahi) */
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) { lastT = 0L; invalidate() } }

    companion object {
        /** v3.88: नियॉन ओर्ब - सोचना (सुनहरा→नारंगी) और बोलना (गुलाबी) के अलग रंग; सुनना = नीला-फ़िरोज़ी (पहले जैसा) */
        val THINK_A = Color.parseColor("#FFB300"); val THINK_B = Color.parseColor("#FF6D00")
        val SPEAK_A = Color.parseColor("#FF4FA3"); val SPEAK_B = Color.parseColor("#FF9AD5")
        /** v3.65: चेहरे (HD) के लिए 3200; बाकी आकार 1400 ही दिखाते हैं (Shapes.PLAIN_DOTS), बचे छिपे */
        const val FULL_DOTS = 3200
        const val MINI_DOTS = 320
        private const val LUT = 64
        private val P16 = FloatArray(LUT + 1) { (it / LUT.toFloat()).toDouble().pow(1.6).toFloat() }
        private val P14 = FloatArray(LUT + 1) { (it / LUT.toFloat()).toDouble().pow(1.4).toFloat() }
        private val P13 = FloatArray(LUT + 1) { (it / LUT.toFloat()).toDouble().pow(1.3).toFloat() }
    }
}
