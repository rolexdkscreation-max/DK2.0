package com.dk.assistant.ui

import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/**
 * 23 नवंबर 2026: Soft 3D Clay (Neumorphism) के सारे रूप - एक ही जगह. रंग हमेशा [ClayTheme.t] से (draw के समय पढ़े जाते हैं,
 * इसलिए थीम बदलते ही अगली बार हर डिब्बा नए रंग में). कोई glass / blur वाला काँच नहीं.
 *
 *  - [Style.RAISED]  उभरा: ऊपर-बाएँ हल्की चमक + नीचे-दाएँ नरम छाया
 *  - [Style.INSET]   धँसा: अंदर की परछाईं (text box, usage बार का खाना, segmented पट्टी)
 *  - [Style.ACCENT]  उभरा + accent का चमकदार ढलान (मुख्य बटन, चुना हुआ टैब)
 *
 * दबाने पर ([PRESS_MS] = 120ms) डिब्बा थोड़ा अंदर धँसता है. [selectable] हो तो checked / selected / activated पर
 * accent रंग [FILL_MS] = 200ms में बाएँ से बहकर भरता है ("रंगीन तरल भरा हो").
 */
class ClayDrawable(
    val style: Style = Style.RAISED,
    /** कोनों की गोलाई (px). [FULL] = पूरा गोल-सिरा (pill) */
    var radiusPx: Float = 0f,
    val oval: Boolean = false,
    /** परछाईं की दूरी (px) - आकार इतना अंदर बनता है ताकि परछाईं view के अंदर ही दिखे */
    val depthPx: Float = 0f,
    val selectable: Boolean = false,
    /** RAISED का रंग: null = थीम का surface; बाकी = chip जैसे छोटे रूप */
    val fill: (() -> Int)? = null,
    /** साझा कार्ड ([ClayCard]): नरम दो परछाइयाँ, सख़्त किनारा नहीं, दबाने पर हल्का छोटा (scale) */
    val card: Boolean = false
) : Drawable() {
    enum class Style { RAISED, INSET, ACCENT }

    companion object {
        const val PRESS_MS = 120L
        /** छोड़ने पर वापस उभरना (कार्ड) - आराम से */
        const val RELEASE_MS = 180L
        const val FILL_MS = 200L
        const val FULL = 9999f
    }

    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = RectF()
    private val path = Path()
    private val tmp = RectF()
    private var blurFor = -1f
    private var blur: BlurMaskFilter? = null

    /** 1 = पूरा उभरा, 0 = दबा (धँसा) */
    private var depth = 1f
    /** 0..1 accent भराव (selectable) */
    private var fillAmt = 0f
    private var pressed = false
    private var chosen = false
    private var anim: ValueAnimator? = null
    private var fillAnim: ValueAnimator? = null
    private var alphaAll = 255

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        val ch = selectable && (state.contains(android.R.attr.state_checked) || state.contains(android.R.attr.state_selected) || state.contains(android.R.attr.state_activated))
        var changed = false
        if (p != pressed) { pressed = p; animDepth(if (p) 0f else 1f); if (card) pressScale(p); changed = true }
        if (ch != chosen) { chosen = ch; animFill(if (ch) 1f else 0f); changed = true }
        return changed
    }

    /** पहली बार (बिना एनिमेशन) सीधी हालत */
    override fun jumpToCurrentState() {
        anim?.cancel(); fillAnim?.cancel()
        depth = if (pressed) 0f else 1f; fillAmt = if (chosen) 1f else 0f
        invalidateSelf()
    }

    private fun reduced() = NeonTheme.reducedMotion()

    /** कार्ड दबा = पूरा कार्ड हल्का छोटा ([ClayCard.PRESS_SCALE]), छोड़ा = आराम से वापस. लिखावट / आइकन वही (बस साथ में हिलते हैं) */
    private fun pressScale(p: Boolean) {
        val v = callback as? android.view.View ?: return
        if (!v.isClickable && !v.isLongClickable) return
        val to = if (p) ClayCard.PRESS_SCALE else 1f
        if (reduced()) { v.scaleX = to; v.scaleY = to; return }
        v.animate().scaleX(to).scaleY(to).setDuration(if (p) PRESS_MS else RELEASE_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animDepth(to: Float) {
        anim?.cancel()
        if (reduced() || bounds.isEmpty) { depth = to; invalidateSelf(); return }
        anim = ValueAnimator.ofFloat(depth, to).apply {
            duration = if (card && to > depth) RELEASE_MS else PRESS_MS; interpolator = DecelerateInterpolator()
            addUpdateListener { depth = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    private fun animFill(to: Float) {
        fillAnim?.cancel()
        if (reduced() || bounds.isEmpty) { fillAmt = to; invalidateSelf(); return }
        fillAnim = ValueAnimator.ofFloat(fillAmt, to).apply {
            duration = FILL_MS; interpolator = DecelerateInterpolator()
            addUpdateListener { fillAmt = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    /** परछाईं की असली दूरी: छोटे डिब्बे पर कम (आकार बहुत न सिकुड़े) */
    private fun dist(b: Rect): Float {
        val m = minOf(b.width(), b.height()).toFloat()
        return minOf(depthPx, m * 0.10f).coerceAtLeast(0f)
    }

    private fun radius(): Float = if (oval) shape.height() / 2f else minOf(radiusPx, minOf(shape.width(), shape.height()) / 2f)

    override fun onBoundsChange(b: Rect) {
        val d = dist(b)
        shape.set(b.left + d, b.top + d, b.right - d, b.bottom - d)
        if (oval) {
            val s = minOf(shape.width(), shape.height())
            shape.set(shape.centerX() - s / 2, shape.centerY() - s / 2, shape.centerX() + s / 2, shape.centerY() + s / 2)
        }
        path.reset()
        if (oval) path.addOval(shape, Path.Direction.CW) else path.addRoundRect(shape, radius(), radius(), Path.Direction.CW)
    }

    private fun blurOf(r: Float): BlurMaskFilter? {
        if (r <= 0.5f) return null
        if (r != blurFor) { blurFor = r; blur = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) }
        return blur
    }

    private fun a(c: Int, f: Float): Int {
        val base = (c ushr 24) and 0xFF
        return ((base * f * alphaAll / 255f).toInt().coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
    }

    override fun draw(c: Canvas) {
        if (shape.isEmpty) return
        val k = ClayTheme.t
        val d = dist(bounds)
        val r = radius()
        val raised = style != Style.INSET
        // ---- बाहर की दो परछाइयाँ (उभरा) - दबने पर धीमे-धीमे गायब
        val out = if (raised) depth else 0f
        if (card && out > 0.01f && d > 0.5f) {
            // 23 नवंबर 2026 (मालिक: "दबाने वाला नरम रूप ही मूल रूप"): ऊपर-बाएँ हल्की चमकीली + नीचे-दाएँ हल्की गहरी परछाईं,
            // दोनों में बड़ा धुंधलापन. परछाईं डिब्बे के अंदर ही ख़त्म (किनारे पर कटकर सख़्त रेखा न बने) - [ClayCard.shadow]
            val (off, bl) = ClayCard.shadow(d)
            shP.style = Paint.Style.FILL
            shP.maskFilter = blurOf(bl)
            shP.color = a(k.shadowDark, out * ClayCard.DARK_ALPHA)
            tmp.set(shape); tmp.offset(off * out, off * out); drawShape(c, tmp, r, shP)
            shP.color = a(k.shadowLight, out * ClayCard.LIGHT_ALPHA)
            tmp.set(shape); tmp.offset(-off * out, -off * out); drawShape(c, tmp, r, shP)
            shP.maskFilter = null
        } else if (out > 0.01f && d > 0.5f) {
            // 23 नवंबर 2026 (मालिक: "उभार काफ़ी ज़्यादा"): आकार वही ([dist] से), सिर्फ़ परछाईं गहरी/बड़ी - [ClayTheme.LIFT] से
            val lift = ClayTheme.LIFT
            val off = d * 0.55f * lift * out
            shP.style = Paint.Style.FILL
            shP.maskFilter = blurOf(d * 0.9f * lift)
            shP.color = a(k.shadowDark, out)
            tmp.set(shape); tmp.offset(off, off); drawShape(c, tmp, r, shP)
            shP.color = a(k.shadowLight, out)
            tmp.set(shape); tmp.offset(-off, -off); drawShape(c, tmp, r, shP)
            // पास की गहरी छाया (ज़मीन से ठीक ऊपर उठा दिखे)
            shP.maskFilter = blurOf(d * 0.45f)
            shP.color = a(k.shadowDark, out * 0.55f)
            tmp.set(shape); tmp.offset(d * 0.28f * out, d * 0.32f * out); drawShape(c, tmp, r, shP)
            shP.maskFilter = null
        }
        // ---- भराव
        val base = when (style) {
            Style.INSET -> k.well
            Style.ACCENT -> k.accent
            Style.RAISED -> fill?.invoke() ?: k.surface
        }
        if (style == Style.ACCENT) {
            fillP.shader = LinearGradient(0f, shape.top, 0f, shape.bottom, a(k.accentTop, 1f), a(k.accentBottom, 1f), Shader.TileMode.CLAMP)
        } else if (style == Style.RAISED) {
            // हल्का ढलान: ऊपर ज़रा उजला -> नीचे असली रंग (मिट्टी जैसा नरम उभार)
            val top = NeonTheme.mix(base, k.shadowLight or (0xFF shl 24), if (k.light) 0.35f else 0.06f)
            fillP.shader = LinearGradient(0f, shape.top, 0f, shape.bottom, a(top or (0xFF shl 24), 1f), a(base, 1f), Shader.TileMode.CLAMP)
        } else {
            fillP.shader = null; fillP.color = a(base, 1f)
        }
        drawShape(c, shape, r, fillP)
        fillP.shader = null

        // ---- चुना हुआ: accent बाएँ से बहकर भरे
        if (selectable && fillAmt > 0.01f) {
            c.save()
            c.clipPath(path)
            val w = shape.width() * fillAmt
            tmp.set(shape.left, shape.top, shape.left + w + r, shape.bottom)
            c.clipRect(shape.left, shape.top, shape.left + w, shape.bottom)
            fillP.shader = LinearGradient(shape.left, shape.top, shape.right, shape.bottom, a(k.accentTop, 1f), a(k.accentBottom, 1f), Shader.TileMode.CLAMP)
            drawShape(c, shape, r, fillP)
            fillP.shader = null
            c.restore()
        }

        // ---- ऊपर-बाएँ की चमक (हाइलाइट): उभरा डिब्बा स्क्रीन से ऊपर उठा दिखे
        if (raised && depth > 0.01f) {
            val gr = maxOf(shape.width(), shape.height()) * 0.75f
            val hl = NeonTheme.alpha(k.rim, if (style == Style.ACCENT) 0x55 else if (k.light) 0xB0 else 0x30)
            c.save(); c.clipPath(path)
            fillP.shader = android.graphics.RadialGradient(shape.left + shape.width() * 0.16f, shape.top + shape.height() * 0.10f, gr.coerceAtLeast(1f),
                intArrayOf(a(hl, depth), a(hl, 0f)), null, Shader.TileMode.CLAMP)
            drawShape(c, shape, r, fillP)
            fillP.shader = null
            c.restore()
        }

        // ---- अंदर की परछाईं (धँसा / दबा हुआ) - [ClayTheme.SINK] से गहरी
        val inn = if (raised) (1f - depth) else 1f
        if (inn > 0.01f) {
            val id = maxOf(d, 3f * (bounds.height().coerceAtLeast(1) / 48f).coerceIn(0.6f, 1.6f))
            c.save()
            c.clipPath(path)
            shP.style = Paint.Style.STROKE
            val sink = ClayTheme.SINK
            shP.strokeWidth = id * 1.6f * sink
            shP.maskFilter = blurOf(id * 0.8f * sink)
            val off = id * 0.5f * sink
            shP.color = a(k.shadowDark, inn)
            tmp.set(shape); tmp.inset(-id * 0.6f, -id * 0.6f); tmp.offset(off, off); drawShape(c, tmp, r + id * 0.6f, shP)
            shP.color = a(k.shadowLight, inn * (if (k.light) 1f else 0.8f))
            tmp.set(shape); tmp.inset(-id * 0.6f, -id * 0.6f); tmp.offset(-off, -off); drawShape(c, tmp, r + id * 0.6f, shP)
            shP.maskFilter = null; shP.style = Paint.Style.FILL
            c.restore()
        }
        // ---- ऊपर का बहुत पतला उजला किनारा (उभार को साफ़ दिखाने के लिए)
        if (raised && !card && depth > 0.5f) {   // कार्ड पर सख़्त किनारे की रेखा नहीं (मालिक)
            rimP.strokeWidth = maxOf(1.2f, d * 0.16f)
            // ऊपर-बाएँ उजला किनारा -> नीचे-दाएँ हल्का गहरा किनारा (तिरछा)
            rimP.shader = LinearGradient(shape.left, shape.top, shape.right, shape.bottom,
                intArrayOf(a(k.rim, 0.95f), a(k.rim, 0f), a(k.shadowDark, 0.35f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            tmp.set(shape); tmp.inset(rimP.strokeWidth / 2, rimP.strokeWidth / 2)
            drawShape(c, tmp, maxOf(0f, r - rimP.strokeWidth / 2), rimP)
            rimP.shader = null
        }
    }

    /** अभी का मुख्य भरा रंग (लिखावट का रंग चुनने के लिए: उजले पर गहरी) */
    fun baseColor(): Int {
        val k = ClayTheme.t
        return when {
            style == Style.ACCENT || (selectable && chosen) -> k.accent
            style == Style.INSET -> k.well
            else -> fill?.invoke() ?: k.surface
        }
    }

    private fun drawShape(c: Canvas, rr: RectF, r: Float, p: Paint) {
        if (oval) c.drawOval(rr, p) else c.drawRoundRect(rr, r, r, p)
    }

    override fun getOutline(outline: Outline) {
        if (shape.isEmpty) return
        if (oval) outline.setOval(shape.left.toInt(), shape.top.toInt(), shape.right.toInt(), shape.bottom.toInt())
        else outline.setRoundRect(shape.left.toInt(), shape.top.toInt(), shape.right.toInt(), shape.bottom.toInt(), radius())
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) { if (alphaAll != alpha) { alphaAll = alpha; invalidateSelf() } }
    override fun getAlpha() = alphaAll
    override fun setColorFilter(cf: ColorFilter?) { fillP.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * चयन का छोटा गोल खाना (पंक्ति के दाईं ओर): चुना = सफ़ेद भराव + बीच में accent ✓, न चुना = खाली धँसा खाना.
 * checked / selected / activated पर 200ms में बदलता है.
 */
class ClayCheckDrawable(private val sizePx: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Path()
    private var amt = 0f
    private var on = false
    private var anim: ValueAnimator? = null
    private val well = ClayDrawable(ClayDrawable.Style.INSET, oval = true, depthPx = sizePx * 0.08f)

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun isStateful() = true
    override fun onBoundsChange(b: Rect) { well.bounds = b }

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked) || state.contains(android.R.attr.state_selected) || state.contains(android.R.attr.state_activated)
        if (c == on) return false
        on = c
        anim?.cancel()
        if (NeonTheme.reducedMotion() || bounds.isEmpty) { amt = if (c) 1f else 0f; invalidateSelf(); return true }
        anim = ValueAnimator.ofFloat(amt, if (c) 1f else 0f).apply {
            duration = ClayDrawable.FILL_MS; addUpdateListener { amt = it.animatedValue as Float; invalidateSelf() }; start()
        }
        return true
    }

    override fun jumpToCurrentState() { anim?.cancel(); amt = if (on) 1f else 0f; invalidateSelf() }

    override fun draw(c: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val k = ClayTheme.t
        if (amt < 0.99f) { well.alpha = ((1f - amt) * 255).toInt(); well.draw(c) }
        if (amt > 0.01f) {
            val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = minOf(b.width(), b.height()) / 2f * 0.86f
            p.style = Paint.Style.FILL
            p.color = NeonTheme.alphaF(k.onAccent, amt)
            c.drawCircle(cx, cy, r * (0.6f + 0.4f * amt), p)
            // ✓ (accent रंग में)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = r * 0.22f
            p.color = NeonTheme.alphaF(NeonTheme.mix(k.accentBottom, k.text, if (k.light) 0f else 0f), amt)
            tick.reset()
            tick.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            tick.lineTo(cx - r * 0.10f, cy + r * 0.34f)
            tick.lineTo(cx + r * 0.46f, cy - r * 0.30f)
            c.drawPath(tick, p)
        }
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * ऑन/ऑफ़ स्विच की पट्टी: ऑफ़ = धँसी / फीकी, ऑन = accent से भरी (200ms). घुंडी (knob) [ClayKnobDrawable] - स्विच खुद उसे
 * बाएँ से दाएँ खिसकाता है, इसलिए स्विच का काम (listener, isChecked) बिल्कुल वही रहता है.
 */
class ClayTrackDrawable(private val wPx: Int, private val hPx: Int) : Drawable() {
    private val well = ClayDrawable(ClayDrawable.Style.INSET, oval = false, radiusPx = ClayDrawable.FULL, depthPx = hPx * 0.06f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var amt = 0f
    private var on = false
    private var anim: ValueAnimator? = null

    override fun getIntrinsicWidth() = wPx
    override fun getIntrinsicHeight() = hPx
    override fun isStateful() = true
    override fun onBoundsChange(b: Rect) { well.bounds = b }

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked)
        if (c == on) return false
        on = c; anim?.cancel()
        if (NeonTheme.reducedMotion() || bounds.isEmpty) { amt = if (c) 1f else 0f; invalidateSelf(); return true }
        anim = ValueAnimator.ofFloat(amt, if (c) 1f else 0f).apply {
            duration = ClayDrawable.FILL_MS; addUpdateListener { amt = it.animatedValue as Float; invalidateSelf() }; start()
        }
        return true
    }

    override fun jumpToCurrentState() { anim?.cancel(); amt = if (on) 1f else 0f; invalidateSelf() }

    override fun draw(c: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val k = ClayTheme.t
        well.draw(c)
        if (amt > 0.01f) {
            val inset = b.height() * 0.06f
            r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, NeonTheme.alphaF(k.accentTop, amt), NeonTheme.alphaF(k.accentBottom, amt), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            p.shader = null
        }
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; well.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** स्विच की गोल उभरी घुंडी (चारों ओर थोड़ी पारदर्शी जगह - परछाईं के लिए) */
class ClayKnobDrawable(private val sizePx: Int) : Drawable() {
    private val knob = ClayDrawable(ClayDrawable.Style.RAISED, oval = true, depthPx = sizePx * 0.12f,
        fill = { ClayTheme.t.let { if (it.light) NeonTheme.mix(it.surface, 0xFFFFFFFF.toInt(), 0.6f) else NeonTheme.mix(it.surface, it.text, 0.55f) } })

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun isStateful() = true
    override fun onStateChange(state: IntArray) = knob.setState(state)
    override fun onBoundsChange(b: Rect) { knob.bounds = b }
    override fun draw(c: Canvas) = knob.draw(c)
    override fun setAlpha(alpha: Int) { knob.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { knob.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Segmented पट्टी (2–4 विकल्प, जैसे 🔍 | 🖥): धँसी लंबी पट्टी + चुना विकल्प उभरा accent-भरा बटन, जो 200ms में
 * एक विकल्प से दूसरे तक खिसकता है. [pick] बताता है कि अभी कौन-सा child चुना है (-1 = कोई नहीं).
 */
class ClaySegmentDrawable(private val host: android.view.ViewGroup, private val pick: () -> Int) : Drawable() {
    private val well = ClayDrawable(ClayDrawable.Style.INSET, radiusPx = ClayDrawable.FULL, depthPx = 0f)
    private val thumb = ClayDrawable(ClayDrawable.Style.ACCENT, radiusPx = ClayDrawable.FULL, depthPx = 0f)
    private val cur = RectF()
    private val want = RectF()
    private var shown = -2
    private var anim: ValueAnimator? = null

    override fun onBoundsChange(b: Rect) { well.bounds = b }

    override fun draw(c: Canvas) {
        well.draw(c)
        val i = pick()
        val ch = if (i in 0 until host.childCount) host.getChildAt(i) else null
        if (ch == null || ch.width == 0) { shown = i; return }
        want.set(ch.left.toFloat(), ch.top.toFloat(), ch.right.toFloat(), ch.bottom.toFloat())
        if (shown != i) {
            val from = RectF(if (shown < -1 || cur.isEmpty) want else cur)
            shown = i
            anim?.cancel()
            if (NeonTheme.reducedMotion()) cur.set(want) else {
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ClayDrawable.FILL_MS; interpolator = DecelerateInterpolator()
                    addUpdateListener { v ->
                        val f = v.animatedValue as Float
                        cur.set(from.left + (want.left - from.left) * f, from.top + (want.top - from.top) * f,
                            from.right + (want.right - from.right) * f, from.bottom + (want.bottom - from.bottom) * f)
                        invalidateSelf()
                    }
                    start()
                }
            }
        } else if (anim?.isRunning != true) cur.set(want)
        thumb.setBounds(cur.left.toInt(), cur.top.toInt(), cur.right.toInt(), cur.bottom.toInt())
        thumb.draw(c)
    }

    override fun setAlpha(alpha: Int) { well.alpha = alpha; thumb.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** XML (glass_dialog) के लिए: dialog = थीम की surface वाला बड़ा उभरा कार्ड (कोने 28dp). बिना argument का constructor ज़रूरी */
class ClayDialogDrawable : Drawable() {
    private val dens = android.content.res.Resources.getSystem().displayMetrics.density
    private val d = ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = 28f * dens, depthPx = 4f * dens)
    override fun onBoundsChange(b: Rect) { d.bounds = b }
    override fun draw(c: Canvas) = d.draw(c)
    override fun setAlpha(alpha: Int) = d.setAlpha(alpha)
    override fun setColorFilter(cf: android.graphics.ColorFilter?) = d.setColorFilter(cf)
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getPadding(padding: Rect): Boolean { val p = (14f * dens).toInt(); padding.set(p, p, p, p); return true }
}

/** सूचना / नए-पन का छोटा चमकता गोल बिंदु (बीच में ठोस रंग + चारों ओर हल्की चमक) */
class ClayDot(private val sizePx: Int, private val color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setBounds(0, 0, sizePx, sizePx) }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(c: Canvas) {
        val b = bounds; val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = minOf(b.width(), b.height()) / 2f
        p.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(NeonTheme.alpha(color, 0x99), NeonTheme.alpha(color, 0x00)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null; p.color = color
        c.drawCircle(cx, cy, r * 0.52f, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * 23 नवंबर 2026 (मालिक: "सारे कार्ड एक जैसे - दबाने वाला नरम रूप ही मूल रूप"): पूरे ऐप के कार्ड का एक ही नाप.
 * Settings, होम, चैट, स्कैन, मेमोरी, popup - सब [com.dk.assistant.ui.Clay.card] / [com.dk.assistant.PlainLook] से यही लेते हैं.
 * रंग हर थीम के अपने ([ClayTheme.t]); सिर्फ़ आकार, गोलाई और परछाईं की बनावट यहाँ.
 */
object ClayCard {
    /** कोनों की गोलाई - सामान्य और दबी, दोनों में बराबर (कोने कूदें नहीं) */
    const val RADIUS_DP = 30f
    /** परछाईं के लिए किनारे से अंदर की जगह */
    const val DEPTH_DP = 8f
    /** दबाने पर आकार */
    const val PRESS_SCALE = 0.97f
    /** परछाईं की दूरी / धुंधलापन (depth के हिस्से): दूरी + धुंधलापन <= depth, ताकि परछाईं किनारे पर कटे नहीं */
    const val OFFSET_K = 0.25f
    const val BLUR_K = 0.72f
    /** परछाइयों की गहराई (थीम के shadow रंग पर): हल्की */
    const val DARK_ALPHA = 0.85f
    const val LIGHT_ALPHA = 0.9f

    /** pure: [d] (px) depth पर परछाईं की (दूरी, धुंधलापन) */
    fun shadow(d: Float): Pair<Float, Float> = (d * OFFSET_K) to (d * BLUR_K)
}
