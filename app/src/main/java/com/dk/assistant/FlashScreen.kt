package com.dk.assistant

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 22 नवंबर 2026: [FlashLive] का Android हिस्सा - पूरी स्क्रीन पर रंगीन परत (पूरी चमक) और कैमरा फ़्रेम से रंग-नाप.
 * ऐप लॉक ([AppLockActivity]) और टच मोड ([GuardAlarmActivity]) दोनों में.
 */
class FlashScreen(private val act: Activity) {
    private var layer: FrameLayout? = null
    private var oldBright = -1f
    private var bright = false
    @Volatile var flash: FlashLive? = null
        private set
    /** आख़िरी चेहरे का डिब्बा (worker धागा लिखता है, कैमरा धागा पढ़ता है) */
    @Volatile var box: FloatArray? = null

    /** रंग शुरू (UI धागे से). [onShown] = रंग खत्म होने पर */
    fun begin(f: FlashLive, onShown: () -> Unit = {}) {
        flash = f
        f.start(System.currentTimeMillis())
        val v = layer ?: FrameLayout(act).apply {
            elevation = 1000f
            addView(TextView(act).apply {
                text = UiLang.s("लाइव जाँच — रंग बदलेंगे, सीधे कैमरे में देखते रहिए"); setTextColor(Color.WHITE); textSize = 16f
                gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }.also { layer = it; act.addContentView(it, ViewGroup.LayoutParams(-1, -1)) }
        v.visibility = View.VISIBLE
        val w = act.window
        if (!bright) oldBright = w.attributes.screenBrightness
        bright = true
        w.attributes = w.attributes.apply { screenBrightness = 1f }
        tick(v, f, onShown)
    }

    private fun tick(v: View, f: FlashLive, onShown: () -> Unit) {
        if (flash !== f || act.isFinishing) { end(); return }
        val c = f.color(System.currentTimeMillis())
        if (c == 0 && f.started && System.currentTimeMillis() > f.startAt) { end(); onShown(); return }
        v.setBackgroundColor(FlashLive.screen(c))
        v.postOnAnimation { tick(v, f, onShown) }
    }

    /** रंगीन परत हटाओ, चमक पहले जैसी */
    fun end() {
        layer?.visibility = View.GONE
        if (bright) { bright = false; act.window.let { w -> w.attributes = w.attributes.apply { screenBrightness = oldBright } } }
    }

    fun cancel() { flash = null; act.runOnUiThread { end() } }

    /** कैमरा धागा: हर फ़्रेम (busy हो तब भी) - चेहरे और आस-पास का रंग */
    fun sample(b: Bitmap) {
        val f = flash ?: return
        val bx = box ?: return
        val now = System.currentTimeMillis()
        if (!f.started || f.done(now)) return
        try {
            val cx = bx[0] + bx[2] / 2f; val cy = bx[1] + bx[3] / 2f
            val ox = bx[2] * FlashLive.RING_OUT / 2f; val oy = bx[3] * FlashLive.RING_OUT / 2f
            val x0 = (cx - ox).toInt().coerceIn(0, b.width - 1); val y0 = (cy - oy).toInt().coerceIn(0, b.height - 1)
            val x1 = (cx + ox).toInt().coerceIn(x0 + 1, b.width); val y1 = (cy + oy).toInt().coerceIn(y0 + 1, b.height)
            val w = x1 - x0; val h = y1 - y0
            val px = IntArray(w * h); b.getPixels(px, 0, w, x0, y0, w, h)
            FlashLive.measure(px, w, h, bx[0] - x0, bx[1] - y0, bx[2], bx[3])?.let { f.sample(now, it[0], it[1]) }
        } catch (e: Throwable) { Quiet.note(e) }
    }
}
