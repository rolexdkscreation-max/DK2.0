package com.dk.assistant.livewall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.dk.assistant.Quiet

/**
 * सेटिंग के अंदर छोटा, चलता हुआ preview - वही दृश्य जो वॉलपेपर पर दिखेगा.
 * [offsetMin] = समय आगे-पीछे करके देखें (सूर्योदय, दोपहर, शाम, रात) - असली वॉलपेपर हमेशा अभी का समय दिखाता है.
 */
class LiveWallPreview(ctx: Context) : View(ctx) {
    private var scene: SkyScene? = null
    private var gfx: CanvasGfx? = null
    private var loaded: LiveWallStore.Loaded? = null
    private val t0 = SystemClock.elapsedRealtime()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    var offsetMin = 0
        set(v) { field = v; invalidate() }
    var message = "पहले डाउनलोड करें"
        set(v) { field = v; invalidate() }

    /** id का दृश्य लोड करो (डाउनलोड हो चुका हो तो) */
    fun show(id: String) {
        release()
        if (width <= 0) { post { show(id) }; return }
        val l = LiveWallStore.load(context, id, width) ?: run { invalidate(); return }
        loaded = l
        val g = CanvasGfx(l.bitmaps)
        val s = SkyScene(l.pack, l.stars, l.moon, l.moonSize, { k -> l.bitmaps[k]?.let { intArrayOf(it.width, it.height) } }, backdropScale = 3)
        s.lat = LiveWallStore.lat(context); s.lon = LiveWallStore.lon(context)
        s.resize(width, height)
        scene = s; gfx = g
        invalidate()
    }

    fun setLocation(lat: Double, lon: Double) {
        scene?.let { it.lat = lat; it.lon = lon; it.reset() }
        invalidate()
    }

    fun release() {
        gfx?.release(); gfx = null; scene = null; loaded = null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scene?.resize(w, h)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = scene; val g = gfx
        if (s == null || g == null) {
            c.drawColor(0xFF0B1A3A.toInt())
            txt.textSize = width * 0.06f
            c.drawText(message, width / 2f, height / 2f, txt)
            return
        }
        g.canvas = c
        try {
            s.frame(g, System.currentTimeMillis() + offsetMin * 60_000L, (SystemClock.elapsedRealtime() - t0) / 1000.0, 0.5f)
        } catch (e: Exception) { Quiet.note(e) }
        g.canvas = null
        if (isShown) postInvalidateDelayed(41)
    }
}
