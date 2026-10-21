package com.dk.assistant

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser

/**
 * Premium Liquid Glass surface (sirf dikhawat - koi click/kaam nahi badalta).
 *
 * Ek hi drawable me kaanch ki saari parten:
 *  1. naram gehri parchhai (kaanch ke BAAHAR hi - andar se dikhti nahi, isliye kaanch gandaa nahi hota)
 *  2. paardarshi neela kaanch (peeche ka DK aasman + chalti roshni dikhti hai)
 *  3. upar ki frosted parat + tirchhi specular chamak (upar-baayein se roshni)
 *  4. neeche andar ki halki roshni (kaanch se mudi hui roshni - caustic)
 *  5. patla chamakta safed kinara (upar-baayein tez, beech me halka, neeche-daayein phir chamak)
 *  6. andar ki baal-jitni line (kaanch ki motai) + kone par chamak ki lakeer
 *
 * XML se aise: <drawable class="com.dk.assistant.GlassCardDrawable" />  (API 24+, app minSdk 26).
 * Isliye purane resource naam (glass_card, glass_pill...) wahi rehte hain - kisi screen ka code nahi chhuna padta.
 */
abstract class LiquidGlassDrawable(private val s: Spec) : Drawable() {

    class Spec(
        val oval: Boolean = false,
        /** kone dp me: upar-baayan, upar-daayan, neeche-daayan, neeche-baayan (999 = poora gol) */
        val radii: FloatArray = floatArrayOf(28f, 28f, 28f, 28f),
        val bodyTop: Int,
        val bodyBottom: Int,
        val pressTop: Int = bodyTop,
        val pressBottom: Int = bodyBottom,
        val frost: Float = 1f,          // upar ki frosted/gloss parat
        val spec: Float = 1f,           // tirchhi specular chamak
        val rim: Float = 1f,            // kinare ki chamak
        val shadow: Float = 1f,         // parchhai (0 = nahi)
        val shadowBottomDp: Float = 3f, // parchhai ki jagah (view ke andar hi)
        val recessed: Boolean = false,  // likhne ka dabba: andar dhansa hua
        val focusRing: Boolean = false
    )

    private var d = Resources.getSystem().displayMetrics.density
    private var alphaAll = 255
    private var pressed = false
    private var focused = false

    private val glass = RectF()
    private val path = Path()
    private val rimPath = Path()
    private val hairPath = Path()
    private val hiPath = Path()
    private val loPath = Path()

    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBodyPress = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFrost = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSpec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCaustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pHair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pLo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        d = r.displayMetrics.density
    }

    // ------------------------------------------------------------------ state (dabana / focus)
    override fun isStateful() = true
    override fun hasFocusStateSpecified() = s.focusRing

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        val f = state.contains(android.R.attr.state_focused)
        if (p == pressed && f == focused) return false
        pressed = p; focused = f
        invalidateSelf()
        return true
    }

    // ------------------------------------------------------------------ shape
    private fun radiiPx(rect: RectF, inset: Float): FloatArray {
        val max = minOf(rect.width(), rect.height()) / 2f
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val r = (s.radii[i] * d - inset).coerceIn(0f, max)
            out[i * 2] = r; out[i * 2 + 1] = r
        }
        return out
    }

    private fun shapePath(p: Path, rect: RectF, inset: Float) {
        p.reset()
        val r = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        if (r.width() <= 0f || r.height() <= 0f) return
        if (s.oval) p.addOval(r, Path.Direction.CW) else p.addRoundRect(r, radiiPx(rect, inset), Path.Direction.CW)
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        val side = if (s.shadow > 0f) 1.2f * d else 0f
        val bottom = if (s.shadow > 0f) s.shadowBottomDp * d else 0f
        glass.set(b.left + side, b.top + side * 0.4f, b.right - side, b.bottom - bottom)
        val w = glass.width(); val h = glass.height()
        val L = glass.left; val T = glass.top; val R = glass.right; val B = glass.bottom

        shapePath(path, glass, 0f)
        shapePath(rimPath, glass, 0.5f * d)
        shapePath(hairPath, glass, 1.6f * d)

        // 1. parchhai: gehra neela (kaala nahi) - DK ke aasman se mel khata
        pShadow.color = 0xFF061846.toInt()

        // 2. paardarshi kaanch
        pBody.shader = LinearGradient(0f, T, 0f, B, s.bodyTop, s.bodyBottom, Shader.TileMode.CLAMP)
        pBodyPress.shader = LinearGradient(0f, T, 0f, B, s.pressTop, s.pressBottom, Shader.TileMode.CLAMP)

        // 3a. upar ki frosted parat (upar safed, aadhe tak gayab)
        val fa = (0x30 * s.frost).toInt().coerceIn(0, 255)
        pFrost.shader = LinearGradient(0f, T, 0f, B,
            intArrayOf(fa shl 24 or 0xFFFFFF, (fa / 4) shl 24 or 0xFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)

        // 3b. tirchhi specular chamak: upar-baayein kone par ellipse jaisi roshni
        val sa = (0x2E * s.spec).toInt().coerceIn(0, 255)
        val rg = RadialGradient(0f, 0f, 1f, intArrayOf(sa shl 24 or 0xFFFFFF, (sa / 3) shl 24 or 0xFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        rg.setLocalMatrix(Matrix().apply {
            setScale(maxOf(w * 0.62f, h * 0.9f), maxOf(h * 1.05f, minOf(w, h) * 0.9f))
            postTranslate(L + w * 0.16f, T - h * 0.12f)
        })
        pSpec.shader = rg

        // 4. neeche andar mudi hui halki aasmaani roshni (caustic) - glow nahi, sirf kinare ke paas
        val ca = (0x26 * s.spec).toInt().coerceIn(0, 255)
        pCaustic.shader = LinearGradient(0f, T, 0f, B,
            intArrayOf(0x00CFE8FF, 0x00CFE8FF, ca shl 24 or 0xCFE8FF),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)

        // likhne ka dabba: upar andar ki parchhai (dhansa hua kaanch)
        pInner.shader = if (s.recessed) LinearGradient(0f, T, 0f, T + minOf(h, 22f * d),
            0x40061846, 0x00061846, Shader.TileMode.CLAMP) else null

        // 5. patla chamakta kinara: diagonal - upar-baayein tez, beech halka, neeche-daayein reflection
        val ra = s.rim.coerceIn(0f, 1f)
        fun wa(a: Float) = ((a * ra * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        pRim.strokeWidth = 1f * d
        pRim.shader = LinearGradient(L, T, R, B,
            intArrayOf(wa(0.95f), wa(0.55f), wa(0.16f), wa(0.12f), wa(0.30f), wa(0.70f)),
            floatArrayOf(0f, 0.16f, 0.42f, 0.6f, 0.82f, 1f), Shader.TileMode.CLAMP)

        // 6a. andar ki baal-jitni line: upar safed, neeche gehra neela = kaanch ki motai
        pHair.strokeWidth = 0.8f * d
        pHair.shader = LinearGradient(0f, T, 0f, B,
            intArrayOf((0x3A * ra).toInt() shl 24 or 0xFFFFFF, 0x00FFFFFF, 0x00102E70, (0x30 * ra).toInt() shl 24 or 0x0A2463),
            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)

        // 6b. kone ki chamak ki lakeer: upar-baayan kona (tez) + neeche-daayan kona (halki reflection).
        // Lakeer ke dono sire dheere-dheere gayab - gradient ka "0" theek dono siro ko jodne wali line par.
        hiPath.reset(); loPath.reset()
        val ins = 1.1f * d
        val g = RectF(L + ins, T + ins, R - ins, B - ins)
        val hiA: FloatArray; val hiB: FloatArray; val hiM: FloatArray
        val loA: FloatArray; val loB: FloatArray; val loM: FloatArray
        if (s.oval) {
            hiPath.addArc(g, 160f, 125f); loPath.addArc(g, -5f, 95f)
            hiA = onOval(g, 160f); hiB = onOval(g, 285f); hiM = onOval(g, 222f)
            loA = onOval(g, -5f); loB = onOval(g, 90f); loM = onOval(g, 42f)
        } else {
            val rr = radiiPx(glass, ins)
            val tl = rr[0]; val tr = rr[2]; val br = rr[4]; val bl = rr[6]
            val leftStraight = (g.height() - tl - bl).coerceAtLeast(0f)
            val topStraight = (g.width() - tl - tr).coerceAtLeast(0f)
            val rightStraight = (g.height() - tr - br).coerceAtLeast(0f)
            val bottomStraight = (g.width() - bl - br).coerceAtLeast(0f)
            val y1 = g.top + tl + minOf(h * 0.22f, leftStraight * 0.6f)
            val x2 = g.left + tl + topStraight * 0.55f
            hiPath.moveTo(g.left, y1); hiPath.lineTo(g.left, g.top + tl)
            if (tl > 0f) hiPath.arcTo(RectF(g.left, g.top, g.left + 2 * tl, g.top + 2 * tl), 180f, 90f, false)
            hiPath.lineTo(x2, g.top)
            hiA = floatArrayOf(g.left, y1); hiB = floatArrayOf(x2, g.top); hiM = floatArrayOf(g.left, g.top)
            val y3 = g.bottom - br - minOf(h * 0.16f, rightStraight * 0.5f)
            val x4 = g.right - br - bottomStraight * 0.32f
            loPath.moveTo(g.right, y3); loPath.lineTo(g.right, g.bottom - br)
            if (br > 0f) loPath.arcTo(RectF(g.right - 2 * br, g.bottom - 2 * br, g.right, g.bottom), 0f, 90f, false)
            loPath.lineTo(x4, g.bottom)
            loA = floatArrayOf(g.right, y3); loB = floatArrayOf(x4, g.bottom); loM = floatArrayOf(g.right, g.bottom)
        }
        pHi.strokeWidth = 1.3f * d
        pHi.shader = fadeTo(hiM, hiA, hiB, intArrayOf(wa(1f), wa(0.6f), 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f))
        pLo.strokeWidth = 1f * d
        pLo.shader = fadeTo(loM, loA, loB, intArrayOf(wa(0.6f), wa(0.25f), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f))
    }

    private fun onOval(r: RectF, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        return floatArrayOf(r.centerX() + r.width() / 2f * Math.cos(a).toFloat(), r.centerY() + r.height() / 2f * Math.sin(a).toFloat())
    }

    /** M par sabse tez, A-B wali line par bilkul gayab (lakeer ke sire kat-te nahi dikhte) */
    private fun fadeTo(m: FloatArray, a: FloatArray, b: FloatArray, colors: IntArray, pos: FloatArray): Shader {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        var nx = -dy; var ny = dx
        val len = Math.hypot(nx.toDouble(), ny.toDouble()).toFloat().coerceAtLeast(0.001f)
        nx /= len; ny /= len
        val dist = (a[0] - m[0]) * nx + (a[1] - m[1]) * ny
        return LinearGradient(m[0], m[1], m[0] + nx * dist, m[1] + ny * dist, colors, pos, Shader.TileMode.CLAMP)
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        if (s.oval) outline.setOval(b) else outline.setRoundRect(b, minOf(s.radii.minOrNull() ?: 0f, 999f) * d)
        outline.alpha = 0f    // parchhai hum khud banate hain - system ki elevation shadow nahi
    }

    // ------------------------------------------------------------------ draw
    override fun draw(c: Canvas) {
        if (bounds.isEmpty || glass.width() <= 0f) return
        val layer = if (alphaAll < 255) c.saveLayerAlpha(RectF(bounds), alphaAll) else -1

        // 1. parchhai - sirf kaanch ke bahar (clipOut), taaki paardarshi kaanch ke andar andhera na aaye
        if (s.shadow > 0f) {
            c.save()
            c.clipOutPath(path)
            val steps = 4
            for (i in steps downTo 1) {
                val k = i / steps.toFloat()
                pShadow.alpha = ((0x1A * s.shadow) * (1.15f - k)).toInt().coerceIn(0, 255)
                c.save()
                c.translate(0f, k * (s.shadowBottomDp - 0.6f) * d)
                c.drawPath(path, pShadow)
                c.restore()
            }
            c.restore()
        }

        // 2. kaanch
        c.drawPath(path, if (pressed && !s.recessed) pBodyPress else pBody)

        c.save()
        c.clipPath(path)
        if (s.recessed) c.drawPath(path, pInner)
        // 3. frost + specular (dabaane par thoda zyada roshan - jaise kaanch par ungli se roshni)
        pFrost.alpha = if (pressed) 255 else 215
        c.drawPath(path, pFrost)
        pSpec.alpha = if (pressed) 255 else 230
        c.drawPath(path, pSpec)
        // 4. neeche ki mudi roshni
        c.drawPath(path, pCaustic)
        c.restore()

        // 5-6. kinare
        val ring = s.focusRing && focused
        pRim.alpha = if (pressed || ring) 255 else 225
        pRim.strokeWidth = (if (ring) 1.6f else 1f) * d
        c.drawPath(rimPath, pRim)
        c.drawPath(hairPath, pHair)
        pHi.alpha = if (pressed || ring) 255 else 235
        c.drawPath(hiPath, pHi)
        c.drawPath(loPath, pLo)

        if (layer >= 0) c.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) { if (alphaAll != alpha) { alphaAll = alpha; invalidateSelf() } }
    override fun getAlpha() = alphaAll
    override fun setColorFilter(cf: ColorFilter?) {
        listOf(pBody, pBodyPress, pFrost, pSpec, pCaustic, pRim, pHair, pHi, pLo).forEach { it.colorFilter = cf }
        invalidateSelf()
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val FULL = 999f
        private fun all(r: Float) = floatArrayOf(r, r, r, r)

        // Paardarshi DK neela (peeche ka aasman dikhe, par safed text saaf padha jaaye)
        val CARD = Spec(radii = all(28f), bodyTop = 0xA84D84E0.toInt(), bodyBottom = 0xBA2552AE.toInt(),
            pressTop = 0xC0659AEC.toInt(), pressBottom = 0xCC3364BF.toInt(), shadowBottomDp = 3.5f)
        val CIRCLE = Spec(oval = true, bodyTop = 0xB05B92EA.toInt(), bodyBottom = 0xC22A5BB8.toInt(),
            pressTop = 0xD07AA9F2.toInt(), pressBottom = 0xD83B6CC8.toInt(), frost = 1.15f, spec = 1.2f, shadowBottomDp = 2.2f)
        val PILL = Spec(radii = all(FULL), bodyTop = 0xAC5089E2.toInt(), bodyBottom = 0xBE2856B2.toInt(),
            pressTop = 0xC86A9DEE.toInt(), pressBottom = 0xD23667C4.toInt(), spec = 1.1f, shadowBottomDp = 2.2f)
        // Mukhya (accent) button: zyada gehra chamakdaar neela kaanch - glow nahi, gloss
        val ACCENT = Spec(radii = all(FULL), bodyTop = 0xE65C9CFF.toInt(), bodyBottom = 0xF02254D6.toInt(),
            pressTop = 0xF03F7EEB.toInt(), pressBottom = 0xF51A45B8.toInt(), frost = 1.35f, spec = 1.3f, shadowBottomDp = 2.6f)
        val ACCENT_CIRCLE = Spec(oval = true, bodyTop = 0xE65C9CFF.toInt(), bodyBottom = 0xF02254D6.toInt(),
            pressTop = 0xF03F7EEB.toInt(), pressBottom = 0xF51A45B8.toInt(), frost = 1.4f, spec = 1.35f, shadowBottomDp = 2.6f)
        val LIVE_CIRCLE = Spec(oval = true, bodyTop = 0xF0FF6E82.toInt(), bodyBottom = 0xF5D3253F.toInt(),
            pressTop = 0xF5EE5468.toInt(), pressBottom = 0xF8B81C34.toInt(), frost = 1.4f, spec = 1.35f, shadowBottomDp = 2.6f)
        val BUBBLE_DK = Spec(radii = floatArrayOf(22f, 22f, 22f, 6f), bodyTop = 0xA84D84E0.toInt(), bodyBottom = 0xBA2552AE.toInt(),
            shadowBottomDp = 2.4f)
        val BUBBLE_ME = Spec(radii = floatArrayOf(22f, 22f, 6f, 22f), bodyTop = 0xE65C9CFF.toInt(), bodyBottom = 0xF02254D6.toInt(),
            frost = 1.3f, spec = 1.25f, shadowBottomDp = 2.4f)
        val INPUT = Spec(radii = all(18f), bodyTop = 0xB8173C88.toInt(), bodyBottom = 0xAE2550A6.toInt(),
            frost = 0.35f, spec = 0.5f, rim = 0.62f, shadow = 0f, recessed = true, focusRing = true)
        val ROW = Spec(radii = all(18f), bodyTop = 0x2EFFFFFF, bodyBottom = 0x14FFFFFF,
            pressTop = 0x52FFFFFF, pressBottom = 0x2EFFFFFF, frost = 0.6f, spec = 0.7f, rim = 0.7f, shadow = 0f)
        val DIALOG = Spec(radii = all(28f), bodyTop = 0xF2295AB4.toInt(), bodyBottom = 0xF51C428E.toInt(),
            frost = 1f, spec = 1f, shadow = 0f)
    }
}

// XML ke liye: har roop ka apna public class (bina argument ka constructor zaroori)
class GlassCardDrawable : LiquidGlassDrawable(LiquidGlassDrawable.CARD)
class GlassCircleDrawable : LiquidGlassDrawable(LiquidGlassDrawable.CIRCLE)
class GlassPillDrawable : LiquidGlassDrawable(LiquidGlassDrawable.PILL)
class GlassAccentDrawable : LiquidGlassDrawable(LiquidGlassDrawable.ACCENT)
class GlassAccentCircleDrawable : LiquidGlassDrawable(LiquidGlassDrawable.ACCENT_CIRCLE)
class GlassLiveCircleDrawable : LiquidGlassDrawable(LiquidGlassDrawable.LIVE_CIRCLE)
class GlassBubbleDkDrawable : LiquidGlassDrawable(LiquidGlassDrawable.BUBBLE_DK)
class GlassBubbleMeDrawable : LiquidGlassDrawable(LiquidGlassDrawable.BUBBLE_ME)
class GlassInputDrawable : LiquidGlassDrawable(LiquidGlassDrawable.INPUT)
class GlassRowDrawable : LiquidGlassDrawable(LiquidGlassDrawable.ROW)
class GlassDialogDrawable : LiquidGlassDrawable(LiquidGlassDrawable.DIALOG)
