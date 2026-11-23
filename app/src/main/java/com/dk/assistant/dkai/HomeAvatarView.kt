package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.dk.assistant.ui.ClayTheme
import com.dk.assistant.ui.NeonTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * 23 नवंबर 2026 (मालिक): होम के बीच वाला 3D अवतार (ORB की जगह, उतना ही बड़ा).
 *  - साँस जैसा धीमा ऊपर-नीचे ([BREATH_S] सेकंड का एक चक्र)
 *  - पीछे हल्की चमक (glow) - रंग वॉइस की हालत से: सुन रहा = थीम का accent, सोच रहा = बैंगनी, बोल रहा = आसमानी
 *  - बोलते समय आवाज़ ([level]) के हिसाब से हल्का हिलना / झूमना
 * "कम एनिमेशन" चालू = सिर्फ़ तस्वीर + स्थिर चमक. सारे रंग थीम / DkaiTheme के नाम वाले रंगों से.
 */
class HomeAvatarView(c: Context) : DkaiAnimView(c) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    /**
     * 23 नवंबर 2026 (मालिक: "कैरेक्टर पूरी स्क्रीन के बैकग्राउंड में, दूर या छोटा नहीं"): true = तस्वीर पूरी view भरे
     * (अनुपात वही, किनारे कटें - [cover]), हर चुने कैरेक्टर पर एक जैसा.
     */
    var cover = false
        set(v) { field = v; invalidate() }
    /**
     * 23 नवंबर 2026 (मालिक: "अवतार चिपकाया हुआ न लगे, स्क्रीन का हिस्सा लगे"): true = तस्वीर के चारों किनारे धीरे-धीरे
     * पारदर्शी ([FADE] हिस्से तक), ताकि पीछे ऐप का वही थीम-रंग दिखे. हर अवतार (आज के और आगे जुड़ने वाले) पर यही.
     */
    var fade = false
        set(v) { field = v; invalidate() }
    /** [FluidOrbView.LISTEN] / [FluidOrbView.THINK] / [FluidOrbView.SPEAK], -1 = शांत */
    var call: () -> Int = { -1 }

    companion object {
        const val BREATH_S = 4.2f
        /** किनारे का कितना हिस्सा (हर तरफ़) धीरे-धीरे गायब */
        const val FADE = 0.16f

        /** pure: किनारे के फेड के रंग-पड़ाव (पारदर्शी -> पूरा -> पूरा -> पारदर्शी) और उनकी जगह */
        fun fadeStops(f: Float = FADE): FloatArray { val x = f.coerceIn(0.01f, 0.45f); return floatArrayOf(0f, x, 1f - x, 1f) }

        /** pure: साँस का ऊपर-नीचे (px), t सेकंड पर */
        fun breath(t: Float, amp: Float): Float = (sin(2 * PI * t / BREATH_S) * amp).toFloat()

        /**
         * pure: पूरी स्क्रीन भरने का पैमाना - तस्वीर (bw x bh) view (w x h) को पूरा ढके, अनुपात वही.
         */
        fun coverScale(w: Float, h: Float, bw: Float, bh: Float): Float = if (bw <= 0f || bh <= 0f) 1f else maxOf(w / bw, h / bh)

        /** pure: बोलते समय झूमना (डिग्री) - आवाज़ जितनी तेज़ उतना, पर 4° से ज़्यादा नहीं */
        fun sway(t: Float, level: Float): Float = (sin(t * 7.0) * 4.0 * level.coerceIn(0f, 1f)).toFloat()
    }

    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val maskP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var glowNow = 0
    private var glowSet = false

    private fun targetGlow(): Int = when (try { call() } catch (_: Exception) { -1 }) {
        FluidOrbView.SPEAK -> DkaiTheme.CYAN
        FluidOrbView.THINK -> DkaiTheme.VIOLET
        else -> ClayTheme.t.accent
    }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        step()
        val calm = NeonTheme.reducedMotion()
        val t = if (calm) 0f else secs()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dp = resources.displayMetrics.density
        // चमक का रंग धीरे-धीरे बदले (अचानक नहीं)
        val want = targetGlow()
        glowNow = if (!glowSet || calm) want else FluidOrbView.mix(glowNow, want, 0.08f)
        glowSet = true
        val speaking = (try { call() } catch (_: Exception) { -1 }) == FluidOrbView.SPEAK
        val pulse = if (calm) 0.5f else 0.5f + 0.5f * sin(t * 2.2f)
        val strength = (0.55f + 0.25f * pulse + 0.35f * lv).coerceAtMost(1f)
        val r = minOf(w, h) * (0.46f + 0.04f * lv)
        glowP.shader = RadialGradient(w / 2, h * 0.52f, r,
            intArrayOf(NeonTheme.alpha(glowNow, (0x88 * strength).toInt()), NeonTheme.alpha(glowNow, (0x30 * strength).toInt()), NeonTheme.alpha(glowNow, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(w / 2, h * 0.52f, r, glowP)

        val b = bitmap ?: return
        // तस्वीर view में पूरी (अनुपात वही), नीचे-बीच
        val s = if (cover) coverScale(w, h, b.width.toFloat(), b.height.toFloat()) * 1.02f else minOf(w * 0.92f / b.width, h * 0.96f / b.height)
        val bw = b.width * s; val bh = b.height * s
        val dy = if (calm) 0f else breath(t, 4f * dp)
        val scale = 1f + (if (calm) 0f else 0.012f * sin(2 * PI.toFloat() * t / BREATH_S)) + if (speaking) 0.03f * lv else 0f
        // पूरी स्क्रीन: चेहरा ऊपर के हिस्से में रहे (ऊपर से टिकी, नीचे कटे); बाकी: नीचे-बीच
        if (cover) dst.set((w - bw) / 2, minOf(0f, h - bh) * 0.25f + dy, (w + bw) / 2, minOf(0f, h - bh) * 0.25f + bh + dy)
        else dst.set((w - bw) / 2, h - bh + dy, (w + bw) / 2, h + dy)
        val layer = if (fade) cv.saveLayer(0f, 0f, w, h, null) else -1
        cv.save()
        cv.scale(scale, scale, w / 2, h)
        if (speaking && !calm) cv.rotate(sway(t, lv), w / 2, h)
        cv.drawBitmap(b, null, dst, img)
        cv.restore()
        if (layer >= 0) {
            // तस्वीर जहाँ दिख रही है उसी के किनारे (view के भीतर) - ऊपर-नीचे और दाएँ-बाएँ, दोनों ओर से धीरे-धीरे गायब
            val l = maxOf(0f, dst.left); val tp = maxOf(0f, dst.top); val r = minOf(w, dst.right); val bt = minOf(h, dst.bottom)
            val stops = fadeStops(); val on = ClayTheme.scrim(1f)   // सिर्फ़ मास्क (दिखता नहीं)
            val cols = intArrayOf(0, on, on, 0)
            maskP.shader = LinearGradient(0f, tp, 0f, bt, cols, stops, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w, h, maskP)
            maskP.shader = LinearGradient(l, 0f, r, 0f, cols, stops, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w, h, maskP)
            cv.restoreToCount(layer)
        }
        if (!calm) again()
    }
}
