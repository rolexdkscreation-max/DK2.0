package com.dk.assistant.livewall

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import kotlin.math.exp

/**
 * [SkyGfx] का फ़ोन वाला रूप: Android Canvas (hardware). चमक (glow) = एक बार बनी सफ़ेद gaussian तस्वीर,
 * रंग से गुणा करके "जोड़" (ADD) - असली रोशनी की तरह चमक आपस में जुड़ती है, ढकती नहीं.
 */
class CanvasGfx(private val bitmaps: MutableMap<String, Bitmap>) : SkyGfx {
    var canvas: Canvas? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { additive(this) }
    private val rectPaint = Paint()
    private val m = Matrix()
    private val filters = HashMap<Long, LightingColorFilter>()
    private val glowBmp: Bitmap = makeGlow(128)

    private fun additive(p: Paint) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) p.blendMode = BlendMode.PLUS
        else p.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private fun filter(mul: Int, add: Int): LightingColorFilter? {
        if (mul == -1 && add and 0xFFFFFF == 0) return null
        val k = (mul.toLong() and 0xFFFFFF shl 24) or (add.toLong() and 0xFFFFFF)
        if (filters.size > 256) filters.clear()
        return filters.getOrPut(k) { LightingColorFilter(mul and 0xFFFFFF, add and 0xFFFFFF) }
    }

    override fun layer(
        id: String, sx: Float, sy: Float, sw: Float, sh: Float,
        dx: Float, dy: Float, dw: Float, dh: Float,
        mul: Int, add: Int, alpha: Float, flipV: Boolean,
    ) {
        val c = canvas ?: return
        val b = bitmaps[id] ?: return
        if (b.isRecycled || sw <= 0f || sh <= 0f || dw <= 0f || dh <= 0f || alpha <= 0f) return
        paint.colorFilter = filter(mul, add)
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.save()
        c.clipRect(dx, dy, dx + dw, dy + dh)
        m.reset()
        m.setTranslate(-sx, -sy)
        m.postScale(dw / sw, dh / sh)
        if (flipV) { m.postScale(1f, -1f); m.postTranslate(dx, dy + dh) } else m.postTranslate(dx, dy)
        c.drawBitmap(b, m, paint)
        c.restore()
    }

    override fun glow(x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Float) {
        val c = canvas ?: return
        if (alpha <= 0.003f || rx < 0.5f || ry < 0.5f) return
        addPaint.colorFilter = filter(color, 0)
        addPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        m.reset()
        m.setScale(2 * rx / glowBmp.width, 2 * ry / glowBmp.height)
        m.postTranslate(x - rx, y - ry)
        c.drawBitmap(glowBmp, m, addPaint)
    }

    override fun dot(x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        val c = canvas ?: return
        addPaint.colorFilter = null
        addPaint.color = color
        addPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawCircle(x, y, r.coerceAtLeast(0.6f), addPaint)
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float) {
        val c = canvas ?: return
        rectPaint.color = color
        rectPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawRect(x, y, x + w, y + h, rectPaint)
    }

    override fun upload(id: String, px: IntArray, w: Int, h: Int) {
        var b = bitmaps[id]
        if (b == null || b.isRecycled || b.width != w || b.height != h || !b.isMutable) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmaps[id]?.let { old -> if (old !== b && id.startsWith("_")) old.recycle() }
            bitmaps[id] = b
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun release() {
        bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
        if (!glowBmp.isRecycled) glowBmp.recycle()
    }

    companion object {
        /** सफ़ेद गोल चमक: बीच में पूरी, किनारे तक धीरे-धीरे शून्य (gaussian) */
        fun makeGlow(n: Int): Bitmap {
            val px = IntArray(n * n)
            val e = exp(-4.5)
            for (y in 0 until n) for (x in 0 until n) {
                val qx = (x + 0.5) / n * 2 - 1; val qy = (y + 0.5) / n * 2 - 1
                val q = qx * qx + qy * qy
                val p = if (q >= 1) 0.0 else (exp(-4.5 * q) - e) / (1 - e)
                val a = (p * 255).toInt().coerceIn(0, 255)
                px[y * n + x] = (a shl 24) or 0xFFFFFF
            }
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }
    }
}
