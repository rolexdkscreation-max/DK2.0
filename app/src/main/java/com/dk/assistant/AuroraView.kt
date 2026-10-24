package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Liquid Glass ka peechhe ka background: v3.61 se gehra graphite (lagbhag kaala) + dheere chalti halki rangeen roshni.
 *
 * v3.41 (UI lag ki jad): pehle har 50ms poori screen par 70px ka RenderEffect blur dobara banta tha aur har frame
 * 4 naye RadialGradient - upar ke saare kaanch wale card bhi har baar dobara jud kar draw hote the. Radial gradient
 * khud hi naram hain, isliye blur hata diya (dikhawat lagbhag wahi). Gradient ek hi baar bante hain, sirf Matrix se
 * khiskate hain; roshni bahut dheere chalti hai isliye ~15 frame/sec kaafi (scroll/touch ke beech aur bhi kam).
 */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val sky = Paint()
    private val sheen = Paint()
    // v3.61: गहरे ग्रेफ़ाइट पर धीमी, धुंधली रंगीन रोशनी (पहले सफ़ेद/आसमानी - उस पर सफ़ेद टेक्स्ट छिपता था).
    // कम चमक (alpha) ताकि टेक्स्ट साफ़ रहे और ऊपर का कांच, उसकी चमक और किनारा अच्छे से दिखें
    private val blobs = listOf(
        floatArrayOf(0.15f, 0.12f, 0.60f, 0.11f, 70f) to Color.parseColor("#3D5A8C"),   // स्टील नीला
        floatArrayOf(0.90f, 0.30f, 0.55f, 0.15f, 56f) to Color.parseColor("#5B4E8F"),   // हल्का बैंगनी
        floatArrayOf(0.25f, 0.70f, 0.65f, 0.09f, 44f) to Color.parseColor("#2F6F73"),   // गहरा फ़िरोज़ी
        floatArrayOf(0.80f, 0.92f, 0.50f, 0.13f, 26f) to Color.parseColor("#C9D3E6")    // बहुत हल्की धुंध
    )
    private val blobPaint = List(blobs.size) { Paint().apply { isDither = true } }
    private val blobR = FloatArray(blobs.size)
    private val m = Matrix()
    private var sizeW = -1
    private var sizeH = -1
    /** scroll / touch abhi hua: background thoda ruk kar (scroll ko poora GPU mile) */
    @Volatile private var busyUntil = 0L
    private val onScroll = android.view.ViewTreeObserver.OnScrollChangedListener { busyUntil = SystemClock.uptimeMillis() + UiPace.AURORA_BUSY_MS }

    init { setWillNotDraw(false) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(onScroll)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(onScroll)
        super.onDetachedFromWindow()
    }

    private fun build(w: Float, h: Float) {
        sky.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#0A0C10"), Color.parseColor("#13161D"), Color.parseColor("#1C2029")),   // v3.61: ग्रेफ़ाइट ढलान
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // tirchhi roshni ki patti (kaanch par padti dhoop jaisi)
        sheen.shader = LinearGradient(0f, h * 0.05f, w, h * 0.45f,
            intArrayOf(Color.TRANSPARENT, 0x14FFFFFF, Color.TRANSPARENT), floatArrayOf(0.25f, 0.5f, 0.75f), Shader.TileMode.CLAMP)
        blobs.forEachIndexed { i, (b, col) ->
            val r = w * b[2]; blobR[i] = r
            // (0,0) par ek baar; har frame sirf Matrix se khiskao (naya shader nahi)
            // blur ki jagah: gaussian jaisa naram dhalaan (beech ka nukeela hissa nahi dikhta)
            val a = b[4].toInt(); val rgb = col and 0xFFFFFF
            blobPaint[i].shader = RadialGradient(0f, 0f, r,
                intArrayOf((a shl 24) or rgb, ((a * 0.72f).toInt() shl 24) or rgb, ((a * 0.28f).toInt() shl 24) or rgb, rgb),
                floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (sizeW != width || sizeH != height) { sizeW = width; sizeH = height; build(w, h) }
        c.drawRect(0f, 0f, w, h, sky)
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        blobs.forEachIndexed { i, (b, _) ->
            val x = w * (b[0] + 0.12f * sin(t * b[3] + i))
            val y = h * (b[1] + 0.08f * cos(t * b[3] * 1.3f + i * 2))
            m.setTranslate(x, y)
            blobPaint[i].shader.setLocalMatrix(m)
            c.drawCircle(x, y, blobR[i], blobPaint[i])
        }
        c.drawRect(0f, 0f, w, h, sheen)
        postInvalidateDelayed(UiPace.auroraDelay(now, busyUntil))     // ~15 frame/sec (pehle 20 + har baar poora blur)
    }
}
