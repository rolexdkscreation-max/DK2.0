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

    /**
     * 23 नवंबर 2026 (मालिक का कोलाज): true = ऐप के अंदर वाला आर्टवर्क (अपने गहरे नीले पीछे के साथ) - बीच में,
     * [subject] कभी नहीं कटता, चारों किनारे फीके, पीछे थीम के रंग की गहरी नीली रोशनी + बहती वेव ([drawArt]).
     */
    var art = false
        set(v) { field = v; invalidate() }
    /** ऑर्ब वाला आर्टवर्क: DK बोले तो आवाज़ के साथ धड़के */
    var orb = false
    /** बीच में लिखा छोटा नाम (जैसे "DK"), null = कुछ नहीं */
    var badge: String? = null
    /** तस्वीर का वह हिस्सा जो कभी न कटे (बायाँ, ऊपर, दायाँ, नीचे - 0..1) */
    var subject: List<Float> = HomeAvatars.FULL_SUBJECT
    /** टैबलेट: आर्टवर्क स्क्रीन के बीच में सीमित चौड़ाई तक ([tabletScale]) */
    var tablet = false

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

        /**
         * pure: आर्टवर्क का पैमाना - जितना बड़ा हो सके (view भरे), पर [subject] वाला हिस्सा (कैरेक्टर / ऑर्ब) पूरा view के अंदर.
         * कम से कम पूरी तस्वीर दिखे (Fit) जितना.
         */
        fun artScale(w: Float, h: Float, bw: Float, bh: Float, subject: List<Float>): Float {
            if (bw <= 0f || bh <= 0f || w <= 0f || h <= 0f) return 1f
            val fit = minOf(w / bw, h / bh)
            val sw = (subject.getOrElse(2) { 1f } - subject.getOrElse(0) { 0f }).coerceIn(0.05f, 1f)
            val sh = (subject.getOrElse(3) { 1f } - subject.getOrElse(1) { 0f }).coerceIn(0.05f, 1f)
            val keep = minOf(w / (bw * sw), h / (bh * sh))
            return maxOf(fit, minOf(coverScale(w, h, bw, bh), keep))
        }

        /** pure: टैबलेट पर आर्टवर्क की चौड़ाई view के [TABLET_W] हिस्से तक, ऊँचाई [TABLET_H] तक */
        fun tabletScale(w: Float, h: Float, bw: Float, bh: Float, s: Float): Float =
            if (bw <= 0f || bh <= 0f) s else minOf(s, w * TABLET_W / bw, h * TABLET_H / bh)
        const val TABLET_W = 0.72f
        const val TABLET_H = 0.92f

        /** pure: वेव की हालत - 0 शांत, 1 सुन रहा, 2 बोल रहा, 3 सोच रहा */
        fun waveMode(call: Int): Int = when (call) {
            FluidOrbView.SPEAK -> 2
            FluidOrbView.LISTEN -> 1
            FluidOrbView.THINK -> 3
            else -> 0
        }

        /** pure: वेव की चाल (रेडियन / सेकंड) - शांत धीमी, बोलते समय तेज़, सुनते समय उल्टी दिशा */
        fun waveSpeed(mode: Int): Float = when (mode) { 2 -> 4.2f; 1 -> -2.4f; 3 -> 1.4f; else -> 0.7f }

        /** pure: वेव की ऊँचाई (dp) - बोलते / सुनते समय आवाज़ ([level]) के साथ */
        fun waveAmp(mode: Int, level: Float): Float {
            val l = level.coerceIn(0f, 1f)
            return when (mode) { 2 -> 7f + 20f * l; 1 -> 5f + 12f * l; 3 -> 6f; else -> 6f }
        }

        /** pure: वेव कितनी चमके (0..255) - बोलते समय सबसे ज़्यादा; लाइट मोड में भी फीकी नहीं */
        fun waveAlpha(mode: Int, light: Boolean): Int = (when (mode) { 2 -> 0xE0; 1 -> 0xB8; 3 -> 0xA0; else -> 0x88 } + if (light) 0x18 else 0).coerceAtMost(0xFF)

        /** pure: ऑर्ब का धड़कना - DK बोले तो आवाज़ के साथ (ज़्यादा से ज़्यादा 8%) */
        fun orbPulse(speaking: Boolean, level: Float): Float = if (speaking) 1f + 0.08f * level.coerceIn(0f, 1f) else 1f

        /** pure: बीच (कैरेक्टर / ऑर्ब) पर वेव कम, दोनों तरफ़ पूरी - x = -1..1 (बीच = 0) */
        fun waveEnvelope(x: Float, hole: Float): Float {
            val a = kotlin.math.abs(x)
            if (a >= 1f) return 0f
            val inner = ((a - hole) / 0.18f).coerceIn(0f, 1f)
            val outer = ((1f - a) / 0.2f).coerceIn(0f, 1f)
            return inner * outer
        }

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

    private val waveP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val wavePath = android.graphics.Path()
    private val stageP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        step()
        if (art) { drawArt(cv); return }
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

    /**
     * आर्टवर्क: (1) पीछे थीम के accent से रंगी गहरी नीली रोशनी, (2) तस्वीर बीच में (साँस जैसा ऊपर-नीचे, ऑर्ब = आवाज़ के साथ
     * धड़कना), (3) ऊपर से बहती वेव - सब एक layer में, फिर चारों किनारे फीके. "कम एनिमेशन" = सब स्थिर.
     */
    private fun drawArt(cv: Canvas) {
        val calm = NeonTheme.reducedMotion()
        val t = if (calm) 0f else secs()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dp = resources.displayMetrics.density
        val k = ClayTheme.t
        val want = targetGlow()
        glowNow = if (!glowSet || calm) want else FluidOrbView.mix(glowNow, want, 0.08f)
        glowSet = true
        val st = try { call() } catch (_: Exception) { -1 }
        val mode = waveMode(st)
        val speaking = mode == 2
        // (1) गहरा नीला मंच: थीम का accent काले की ओर (हर थीम में अपना टिंट), किनारों पर थीम का पीछा
        val deep = FluidOrbView.mix(k.accent, 0xFF000000.toInt(), if (k.light) 0.72f else 0.80f)
        val pulse = if (calm) 0.5f else 0.5f + 0.5f * sin(t * 1.6f)
        val glowA = (0x70 + 0x30 * pulse + 0x60 * lv).toInt().coerceAtMost(0xE0)
        val r = maxOf(w, h) * 0.62f
        stageP.shader = RadialGradient(w / 2, h / 2, r,
            intArrayOf(NeonTheme.alpha(FluidOrbView.mix(deep, glowNow, 0.18f), 0xFF), NeonTheme.alpha(deep, 0xFF), NeonTheme.alpha(FluidOrbView.mix(deep, 0xFF000000.toInt(), 0.35f), 0xFF)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val layer = cv.saveLayer(0f, 0f, w, h, null)
        cv.drawRect(0f, 0f, w, h, stageP)
        glowP.shader = RadialGradient(w / 2, h / 2, minOf(w, h) * (0.48f + 0.06f * lv),
            intArrayOf(NeonTheme.alpha(glowNow, glowA), NeonTheme.alpha(glowNow, glowA / 3), NeonTheme.alpha(glowNow, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, glowP)
        // (2) तस्वीर
        val b = bitmap
        if (b != null) {
            var s = artScale(w, h, b.width.toFloat(), b.height.toFloat(), subject)
            if (tablet) s = tabletScale(w, h, b.width.toFloat(), b.height.toFloat(), s)
            val bw = b.width * s; val bh = b.height * s
            // कैरेक्टर का हिस्सा बीच में
            val cx = (subject.getOrElse(0) { 0f } + subject.getOrElse(2) { 1f }) / 2f
            val cy = (subject.getOrElse(1) { 0f } + subject.getOrElse(3) { 1f }) / 2f
            var left = w / 2 - bw * cx; var top = h / 2 - bh * cy
            // तस्वीर view से बड़ी हो तो भी खाली पट्टी नहीं (जहाँ तक कैरेक्टर न कटे)
            if (bw >= w) left = left.coerceIn(w - bw, 0f) else left = (w - bw) / 2
            if (bh >= h) top = top.coerceIn(h - bh, 0f) else top = (h - bh) / 2
            val dy = if (calm) 0f else breath(t, 5f * dp)
            dst.set(left, top + dy, left + bw, top + bh + dy)
            val sc = (if (calm) 1f else 1f + 0.012f * sin(2 * PI.toFloat() * t / BREATH_S)) * if (orb) orbPulse(speaking, lv) else 1f
            val px = dst.left + bw * cx; val py = dst.top + bh * cy
            // तस्वीर अपनी layer में - उसके अपने चारों किनारे फीके (मंच में घुल जाए, कोई सीधी किनार न दिखे)
            val imgLayer = cv.saveLayer(0f, 0f, w, h, null)
            cv.save()
            cv.scale(sc, sc, px, py)
            if (speaking && !calm && !orb) cv.rotate(sway(t, lv) * 0.5f, px, dst.bottom)
            cv.drawBitmap(b, null, dst, img)
            badge?.let { txt ->
                badgeP.textSize = minOf(bw, bh) * 0.16f
                badgeP.color = NeonTheme.alpha(0xFFFFFFFF.toInt(), 0xF0)
                badgeP.setShadowLayer(badgeP.textSize * 0.45f, 0f, 0f, NeonTheme.alpha(glowNow, 0xFF))
                cv.drawText(txt, px, dst.top + bh * HomeAvatars.BADGE_Y + badgeP.textSize * 0.35f, badgeP)   // ऑर्ब के ठीक बीच
            }
            cv.restore()
            val ist = fadeStops(0.2f); val ion = ClayTheme.scrim(1f); val icols = intArrayOf(0, ion, ion, 0)
            val il = dst.left - bw * (sc - 1f) / 2f; val ir = dst.right + bw * (sc - 1f) / 2f
            val it0 = dst.top - bh * (sc - 1f) / 2f; val ib = dst.bottom + bh * (sc - 1f) / 2f
            maskP.shader = LinearGradient(0f, it0, 0f, ib, icols, ist, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w, h, maskP)
            maskP.shader = LinearGradient(il, 0f, ir, 0f, icols, ist, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w, h, maskP)
            cv.restoreToCount(imgLayer)
        }
        // (3) बहती वेव - हर थीम के accent से, बीच (कैरेक्टर / ऑर्ब) पर कम
        drawWaves(cv, w, h, dp, t, mode, k.light)
        // पूरे मंच (READY पट्टी से नीचे की बार तक) के चारों किनारे फीके - थीम के पीछे में घुल जाए
        val stops = fadeStops(0.1f); val on = ClayTheme.scrim(1f)
        val cols = intArrayOf(0, on, on, 0)
        maskP.shader = LinearGradient(0f, 0f, 0f, h, cols, stops, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, maskP)
        maskP.shader = LinearGradient(0f, 0f, w, 0f, cols, floatArrayOf(0f, 0.06f, 0.94f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, maskP)
        cv.restoreToCount(layer)
        if (!calm) again()
    }

    private fun drawWaves(cv: Canvas, w: Float, h: Float, dp: Float, t: Float, mode: Int, light: Boolean) {
        val speed = waveSpeed(mode)
        val amp = waveAmp(mode, lv) * dp
        val baseA = waveAlpha(mode, light)
        val col = when (mode) { 2 -> DkaiTheme.CYAN; 3 -> DkaiTheme.VIOLET; else -> glowNow }
        val cy = if (b0()) dst.top + dst.height() * (if (orb) 0.5f else 0.72f) else h * 0.6f
        val hole = if (orb) 0.30f else 0.22f
        val n = 56
        for (i in 0 until 3) {
            val ph = t * speed * (1f + i * 0.22f) + i * 2.1f
            val freq = (if (mode == 1) 3.4f else 2.2f) + i * 0.5f
            wavePath.reset()
            var started = false
            for (j in 0..n) {
                val x = w * j / n
                val nx = (x / w) * 2f - 1f
                val env = waveEnvelope(nx, hole)
                // सुनते समय: लहर किनारों से बीच की ओर दौड़े (अलग ढंग), बाकी: एक दिशा में बहे
                val arg = if (mode == 1) freq * kotlin.math.abs(nx) * PI.toFloat() + ph else freq * nx * PI.toFloat() + ph
                val y = cy + (i - 1) * 6f * dp + sin(arg) * amp * (0.35f + 0.65f * env)
                if (!started) { wavePath.moveTo(x, y); started = true } else wavePath.lineTo(x, y)
            }
            val a = (baseA * (1f - i * 0.25f)).toInt()
            // पहले चौड़ी हल्की चमक, फिर पतली तेज़ रेखा
            waveP.shader = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(NeonTheme.alpha(col, 0), NeonTheme.alpha(col, a / 3), NeonTheme.alpha(col, a / 8), NeonTheme.alpha(col, a / 3), NeonTheme.alpha(col, 0)),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
            waveP.strokeWidth = (if (mode == 2) 7f else 5f) * dp
            cv.drawPath(wavePath, waveP)
            waveP.shader = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(NeonTheme.alpha(col, 0), NeonTheme.alpha(FluidOrbView.mix(col, 0xFFFFFFFF.toInt(), 0.35f), a), NeonTheme.alpha(col, a / 5),
                    NeonTheme.alpha(FluidOrbView.mix(col, 0xFFFFFFFF.toInt(), 0.35f), a), NeonTheme.alpha(col, 0)),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
            waveP.strokeWidth = (if (mode == 2) 1.8f else 1.3f) * dp
            cv.drawPath(wavePath, waveP)
        }
        waveP.shader = null
    }

    private fun b0(): Boolean = bitmap != null && dst.height() > 0f
}
