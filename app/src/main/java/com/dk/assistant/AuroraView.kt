package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
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

/**
 * Liquid Glass ka peechhe ka background: naram neela aasman + dheere chalti safed/aasmaani roshni.
 * Android 12+ par asli blur (frosted); purane phone par radial gradient khud hi naram hain (koi bhaari kaam nahi).
 */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sky = Paint()
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobs = listOf(
        floatArrayOf(0.15f, 0.12f, 0.60f, 0.11f, 102f) to Color.parseColor("#FFFFFF"),
        floatArrayOf(0.90f, 0.30f, 0.55f, 0.15f, 112f) to Color.parseColor("#9ED8FF"),
        floatArrayOf(0.25f, 0.70f, 0.65f, 0.09f, 96f) to Color.parseColor("#A9B8FF"),
        floatArrayOf(0.80f, 0.92f, 0.50f, 0.13f, 90f) to Color.parseColor("#E3F1FF")
    )
    private var skyH = -1

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (skyH != height) {
            skyH = height
            sky.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(Color.parseColor("#163A86"), Color.parseColor("#2A5FC0"), Color.parseColor("#3E78D6")),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            // tirchhi roshni ki patti (kaanch par padti dhoop jaisi)
            sheen.shader = LinearGradient(0f, h * 0.05f, w, h * 0.45f,
                intArrayOf(Color.TRANSPARENT, 0x22FFFFFF, Color.TRANSPARENT), floatArrayOf(0.25f, 0.5f, 0.75f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, sky)
        val t = SystemClock.uptimeMillis() / 1000f
        blobs.forEachIndexed { i, (b, col) ->
            val x = w * (b[0] + 0.12f * sin(t * b[3] + i))
            val y = h * (b[1] + 0.08f * cos(t * b[3] * 1.3f + i * 2))
            val r = w * b[2]
            p.shader = RadialGradient(x, y, r, intArrayOf((b[4].toInt() shl 24) or (col and 0xFFFFFF), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
        }
        c.drawRect(0f, 0f, w, h, sheen)
        postInvalidateDelayed(50)
    }
}
