package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * 27 नवंबर 2026 (मालिक: "चुना कैरेक्टर पूरी Home स्क्रीन पर फुल-स्क्रीन फोटो की तरह - सबसे नीचे की परत"):
 *
 * तस्वीर जैसी है वैसी - अपनी ही बैकग्राउंड के साथ, किनारे से किनारे (status bar और nav bar के पीछे भी).
 * कोई कटआउट / अलग रंग की परत / blur / glow / चौकोर पैच / साँस / हिलना नहीं - बिल्कुल स्थिर फोटो.
 * जगह और साइज़ [CharacterFrame.place] से - सिर्फ़ स्क्रीन का साइज़ या तस्वीर बदलने पर दोबारा; बोलते समय / लाइव
 * बॉक्स बढ़ने पर कभी नहीं (बॉक्स ऊपर तैरता है, यह view उससे जुड़ा ही नहीं).
 * चेहरा बचाने के लिए scale cover से कम हुआ = बची जगह तस्वीर के अपने किनारे के रंग से (BitmapShader CLAMP) - खाली पट्टी नहीं.
 * कैरेक्टर बदला = हल्का crossfade ([FADE_MS]); कम-हलचल (reduced motion) में सीधा.
 */
class FullBleedCharacterView(c: Context) : View(c) {
    /** ऊपर की पट्टी (status bar + हेडर + READY) की ऊँचाई px - चेहरा इसके नीचे */
    var topSafe: () -> Int = { 0 }
    /** नीचे की बार (+ nav bar) की ऊँचाई px - चेहरा इसके ऊपर */
    var bottomSafe: () -> Int = { 0 }
    /** टैबलेट = चेहरा छोटा न दिखे ([CharacterFrame.TABLET_FACE_K]) */
    var minFaceK = 0f

    private class Layer(val bmp: Bitmap, val focus: List<Float>, val face: List<Float>) {
        val m = Matrix()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        var key = ""
        var place: CharacterFrame.Place? = null
    }

    private var cur: Layer? = null
    private var old: Layer? = null
    private var fade = 1f
    private var anim: android.animation.ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    val bitmap: Bitmap? get() = cur?.bmp

    /** test / जाँच: अभी वाली जगह (null = अभी नापी नहीं) */
    val place: CharacterFrame.Place? get() = cur?.place

    /** नई तस्वीर (या वही तस्वीर नए focus के साथ). null = खाली */
    fun show(b: Bitmap?, focus: List<Float>, face: List<Float>) {
        val now = cur
        if (now != null && b === now.bmp && now.focus == focus && now.face == face) return
        if (now == null && b == null) return
        anim?.cancel()
        old = now
        cur = b?.let { Layer(it, focus, face) }
        val quiet = old == null || cur == null || !isShown || com.dk.assistant.ui.NeonTheme.reducedMotion()
        if (quiet) { old = null; fade = 1f; invalidate(); return }
        fade = 0f
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_MS
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { old = null; fade = 1f; invalidate() }
            })
            start()
        }
    }

    /** ऊपर / नीचे की पट्टियों का नाप बदला (पहली बार layout) - जगह फिर से नापो */
    fun relayout() { cur?.key = ""; old?.key = ""; invalidate() }

    private fun fit(l: Layer) {
        val w = width; val h = height
        val top = topSafe(); val bottom = bottomSafe()
        val key = "$w|$h|$top|$bottom|$minFaceK"
        if (key == l.key) return
        l.key = key
        val p = CharacterFrame.place(w.toFloat(), h.toFloat(), l.bmp.width.toFloat(), l.bmp.height.toFloat(), l.focus, l.face,
            top.toFloat(), bottom.toFloat(), minFaceK)
        l.place = p
        l.m.reset(); l.m.setScale(p.scale, p.scale); l.m.postTranslate(p.dx, p.dy)
        l.paint.shader.setLocalMatrix(l.m)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        old?.let { fit(it); it.paint.alpha = 255; canvas.drawRect(0f, 0f, w, h, it.paint) }
        cur?.let { fit(it); it.paint.alpha = (255 * fade).toInt().coerceIn(0, 255); canvas.drawRect(0f, 0f, w, h, it.paint) }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    companion object {
        const val FADE_MS = 260L
    }
}
