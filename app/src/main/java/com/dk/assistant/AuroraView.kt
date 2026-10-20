package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Peeche ki dheere-dheere chalti rangeen roshni - liquid glass ke peeche ka background. */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobs = listOf(
        floatArrayOf(0.2f, 0.18f, 0.55f, 0.13f) to Color.parseColor("#7C4DFF"),
        floatArrayOf(0.85f, 0.35f, 0.5f, 0.17f) to Color.parseColor("#00B0FF"),
        floatArrayOf(0.3f, 0.8f, 0.6f, 0.11f) to Color.parseColor("#00E5FF"),
        floatArrayOf(0.8f, 0.9f, 0.45f, 0.15f) to Color.parseColor("#E040FB")
    )

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#070A1C"))
        val t = SystemClock.uptimeMillis() / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        blobs.forEachIndexed { i, (b, col) ->
            val x = w * (b[0] + 0.12f * sin(t * b[3] + i))
            val y = h * (b[1] + 0.08f * cos(t * b[3] * 1.3f + i * 2))
            val r = w * b[2]
            p.shader = RadialGradient(x, y, r, intArrayOf((0x70 shl 24) or (col and 0xFFFFFF), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        postInvalidateDelayed(50)
    }
}
