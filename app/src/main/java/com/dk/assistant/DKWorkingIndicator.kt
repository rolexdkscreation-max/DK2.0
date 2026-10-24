package com.dk.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * DK का छोटा "काम चल रहा है" logo (24-32dp) - ऐप आइकन वाली वही नीयन साउंड-वेव.
 * अवस्था (WorkState) बदलते ही animation तुरंत (पर स्मूद) बदलता है:
 *   IDLE = रुका हुआ logo • LISTENING = धीमी तरंग • THINKING = घूमता घेरा • SPEAKING = तेज़ तरंग • WORKING = धड़कन + घेरा
 * हल्का: सिर्फ़ Canvas (कोई bitmap/Lottie नहीं), onDraw में नई चीज़ नहीं बनती, चलते समय ही frame माँगता है (60fps),
 * screen/ऐप पीछे जाए (window दिखे नहीं), view छिपे या phone में animation बंद हों तो रुक जाता है.
 */
class DKWorkingIndicator(context: Context) : View(context) {

    var state: WorkState = WorkState.IDLE
        set(v) { if (field != v) { field = v; kick() } }

    private val dp = resources.displayMetrics.density
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TILE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val ringBox = RectF()

    // चिकने (smoothed) मान - target की तरफ़ ~90ms में
    private var amp = 0f; private var speed = 0f; private var ringA = 0f; private var pulse = 0f
    private var phase = 0.0; private var angle = 0f; private var pulsePhase = 0.0
    private var last = 0L
    private var running = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = (28 * dp).toInt()
        setMeasuredDimension(resolveSize(d, widthMeasureSpec), resolveSize(d, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = minOf(w, h).toFloat()
        val cx = w / 2f; val cy = h / 2f
        bar.shader = LinearGradient(cx - s * 0.32f, cy - s * 0.3f, cx + s * 0.32f, cy + s * 0.3f,
            intArrayOf(BLUE, MID, PURPLE), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(cx, cy, s * 0.5f, intArrayOf(0x997A5CFF.toInt(), 0x227A5CFF, 0x007A5CFF),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        ring.shader = SweepGradient(cx, cy, intArrayOf(0x002EC5FF, BLUE, PURPLE, 0x00B14DFF), floatArrayOf(0f, 0.35f, 0.7f, 1f))
        ring.strokeWidth = s * 0.06f
        val inset = s * 0.07f
        ringBox.set(cx - s / 2 + inset, cy - s / 2 + inset, cx + s / 2 - inset, cy + s / 2 - inset)
    }

    /** target मान - अवस्था के हिसाब से */
    private fun targets(): FloatArray = when (state) {
        WorkState.IDLE -> floatArrayOf(0f, 0f, 0f, 0f)
        WorkState.LISTENING -> floatArrayOf(0.38f, 4.4f, 0f, 0f)      // धीमी तरंग (~0.7 Hz)
        WorkState.THINKING -> floatArrayOf(0.12f, 3.0f, 1f, 0f)       // घूमता घेरा
        WorkState.SPEAKING -> floatArrayOf(0.85f, 13.8f, 0f, 0f)      // तेज़ तरंग (~2.2 Hz)
        WorkState.WORKING -> floatArrayOf(0.22f, 5.0f, 0.8f, 1f)      // धड़कन + धीमा घेरा
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last).coerceIn(0, 64)) / 1000f
        last = now
        val t = targets()
        val k = 1f - exp(-dt / 0.09f)
        amp += (t[0] - amp) * k; speed += (t[1] - speed) * k; ringA += (t[2] - ringA) * k; pulse += (t[3] - pulse) * k
        phase += speed * dt
        angle = (angle + dt * (if (state == WorkState.WORKING) 225f else 330f) * (0.3f + 0.7f * ringA)) % 360f
        pulsePhase += dt * 2 * PI / 1.2

        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h); val cx = w / 2f; val cy = h / 2f
        // टाइल (ऐप आइकन जैसी गहरी पृष्ठभूमि - नीले/काँच वाली screen पर भी साफ़ दिखे)
        r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        c.drawRoundRect(r, s * 0.3f, s * 0.3f, tile)
        // ग्लो: हमेशा हल्का, काम में धड़कता
        val beat = (0.5 + 0.5 * sin(pulsePhase)).toFloat()
        glow.alpha = (255 * (0.35f + 0.25f * amp + 0.4f * pulse * beat)).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, s * (0.46f + 0.04f * pulse * beat), glow)
        // बार
        val n = H.size
        val step = s * 0.105f; val bw = s * 0.066f
        val scale = 1f + 0.06f * pulse * beat
        for (i in 0 until n) {
            val x = cx + (i - (n - 1) / 2f) * step * scale
            var f = 1f + amp * 0.6f * sin(phase + i * 0.95).toFloat()
            if (state == WorkState.SPEAKING) f += amp * 0.25f * sin(phase * 1.7 + i * 2.1).toFloat()
            val bh = (H[i] * s * 0.62f * scale * f.coerceIn(0.3f, 1.55f)).coerceAtMost(s * 0.78f)
            r.set(x - bw / 2, cy - bh / 2, x + bw / 2, cy + bh / 2)
            c.drawRoundRect(r, bw / 2, bw / 2, bar)
        }
        // घूमता घेरा (सोचना/काम)
        if (ringA > 0.01f) {
            ring.alpha = (255 * ringA).toInt().coerceIn(0, 255)
            c.save(); c.rotate(angle, cx, cy)
            c.drawArc(ringBox, 0f, 250f, false, ring)
            c.restore()
        }
        val settled = state == WorkState.IDLE && amp < 0.004f && ringA < 0.004f && pulse < 0.004f
        if (settled) { amp = 0f; ringA = 0f; pulse = 0f; running = false; last = 0L }
        else if (canAnimate()) postInvalidateOnAnimation()
        else { running = false; last = 0L }
    }

    private fun canAnimate() = isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

    /** animation (फिर से) शुरू - सिर्फ़ जब ज़रूरत हो */
    private fun kick() {
        if (!running && canAnimate()) { running = true; last = 0L; postInvalidateOnAnimation() }
        else if (!canAnimate()) invalidate()
    }

    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v == VISIBLE) kick() else { running = false; last = 0L } }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) kick() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); kick() }
    override fun onDetachedFromWindow() { running = false; last = 0L; super.onDetachedFromWindow() }

    companion object {
        // ब्रांड रंग (branding/gen_brand.py में भी यही)
        val BLUE = Color.parseColor("#2EC5FF")
        val MID = Color.parseColor("#5B6CFF")
        val PURPLE = Color.parseColor("#B14DFF")
        val TILE = Color.parseColor("#E60A0B1C")
        /** आइकन वाले 7 बार की ऊँचाई (सबसे ऊँचा = 1) */
        private val H = floatArrayOf(8f, 15f, 24f, 31f, 19f, 26f, 11f).map { it / 31f }.toFloatArray()

        /**
         * कहीं भी लगाने वाली पंक्ति: [logo] लेबल. DKWork से खुद जुड़ती है - काम शुरू = दिखे/चले, खत्म = स्मूद रुककर छिपे.
         * @param voice वॉइस screen (सुनना/सोचना/बोलना तीनों; हमेशा दिखे)
         * @param focus इस screen का अपना बैकग्राउंड काम पहले (TaskStatus key, जैसे TaskStatus.CODER)
         */
        fun row(ctx: Context, voice: Boolean = false, focus: String? = null, sizeDp: Int = 28): DKWorkingRow =
            DKWorkingRow(ctx, voice, focus, sizeDp)
    }
}

/** logo + छोटा लेबल. छिपना/दिखना ऊँचाई के स्मूद animation से (नीचे की चीज़ें झटके से नहीं हिलतीं) */
class DKWorkingRow(ctx: Context, private val voice: Boolean, private val focus: String?, sizeDp: Int) : LinearLayout(ctx) {
    private val dp = resources.displayMetrics.density
    val indicator = DKWorkingIndicator(ctx)
    private val label = TextView(ctx).apply {
        textSize = 12f; setTextColor(0xE6FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(4f, 0f, 1f, 0x66000000)
    }
    private val full = ((sizeDp + 8) * dp).toInt()
    private var shownNow = voice
    private var heightAnim: ValueAnimator? = null
    private val hideLater = Runnable { setShown(false) }
    private val listener: () -> Unit = { apply(DKWork.snapshot(voice, focus)) }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or (if (voice) Gravity.CENTER_HORIZONTAL else Gravity.START)
        setPadding((2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt())
        val s = (sizeDp * dp).toInt()
        addView(indicator, LayoutParams(s, s))
        addView(label, LayoutParams(if (voice) LayoutParams.WRAP_CONTENT else 0, LayoutParams.WRAP_CONTENT, if (voice) 0f else 1f)
            .apply { marginStart = (8 * dp).toInt() })
        if (!voice) { visibility = GONE; alpha = 0f }
        contentDescription = "DK"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!voice) layoutParams?.height = if (shownNow) full else 0
        DKWork.listen(listener); listener()
    }
    override fun onDetachedFromWindow() {
        DKWork.unlisten(listener); removeCallbacks(hideLater); heightAnim?.cancel(); super.onDetachedFromWindow()
    }

    private fun apply(s: WorkSnap) {
        indicator.state = s.state
        if (s.busy) {
            if (label.text.toString() != s.label) { label.text = s.label; contentDescription = "DK: " + s.label }
            removeCallbacks(hideLater); setShown(true)
        } else {
            if (voice && label.text.isNotEmpty()) label.text = ""
            // logo पहले स्मूद रुके, फिर पंक्ति छिपे
            if (!voice && shownNow) { removeCallbacks(hideLater); postDelayed(hideLater, 420) }
        }
    }

    private fun setShown(on: Boolean) {
        if (voice || on == shownNow) return
        shownNow = on
        val lp = layoutParams ?: return
        heightAnim?.cancel()
        if (on) visibility = VISIBLE
        val from = if (visibility == GONE) 0 else lp.height.coerceAtLeast(0)
        val to = if (on) full else 0
        animate().alpha(if (on) 1f else 0f).setDuration(if (on) 160 else 220).start()
        heightAnim = ValueAnimator.ofInt(from, to).apply {
            duration = if (on) 180 else 240
            addUpdateListener { lp.height = it.animatedValue as Int; requestLayout() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (!shownNow) visibility = GONE }
            })
            start()
        }
    }
}
