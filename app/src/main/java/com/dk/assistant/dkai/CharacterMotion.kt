package com.dk.assistant.dkai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dk.assistant.ui.NeonTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

/**
 * 26 नवंबर 2026 (मालिक: "Universal Character Animation Engine - हर मौजूदा और आगे जुड़ने वाले कैरेक्टर पर अपने आप"):
 * कैरेक्टर का वैकल्पिक "rig" - तस्वीर के हिसाब से (0..1) आँखों की जगह. rig सिर्फ़ उसी तस्वीर पर लगता है जिसका अनुपात
 * [aspect] से मिले (जैसे 2048x2048 HQ) - दूसरी (छोटी / अलग कटी) तस्वीर पर अपने आप बंद, ताकि पलक गलत जगह न बने.
 * rig न हो / आँखें न हों (बंद आँखों वाली, बिना चेहरे की spirit, ऑर्ब, रोबोट) = पलक नहीं, बाकी सब (साँस, रोशनी, थीम) वही.
 * index.json में भी: `"rig": {"aspect": 1.0, "eyes": [[x, y, w, h], ...]}`
 */
data class CharacterRig(val aspect: Float, val eyes: List<Eye> = emptyList()) {
    /** आँख का बीच (x, y) और चौड़ाई / ऊँचाई - तस्वीर की चौड़ाई / ऊँचाई के हिस्से में */
    data class Eye(val x: Float, val y: Float, val w: Float, val h: Float)

    /** यह rig इस तस्वीर पर लगे? (अनुपात 3% के अंदर, आँखें सही दायरे में) */
    fun fits(bw: Int, bh: Int): Boolean = bw > 0 && bh > 0 && aspect > 0f && eyes.isNotEmpty() &&
        abs(bw.toFloat() / bh - aspect) <= aspect * 0.03f &&
        eyes.all { it.x in 0f..1f && it.y in 0f..1f && it.w in 0.005f..0.4f && it.h in 0.003f..0.3f }

    companion object {
        /** 2048x2048 HQ वाले कैरेक्टर */
        fun square(vararg e: Float): CharacterRig = CharacterRig(1f, e.toList().chunked(4).filter { it.size == 4 }.map { Eye(it[0], it[1], it[2], it[3]) })

        /** index.json का `rig` (गलत / अधूरा = null, कभी crash नहीं) */
        fun parse(o: org.json.JSONObject?): CharacterRig? {
            if (o == null) return null
            return try {
                val asp = o.optDouble("aspect", 1.0).toFloat()
                val arr = o.optJSONArray("eyes")
                val eyes = ArrayList<Eye>()
                if (arr != null) for (i in 0 until minOf(arr.length(), 4)) {
                    val e = arr.optJSONArray(i) ?: continue
                    if (e.length() < 4) continue
                    eyes.add(Eye(e.optDouble(0).toFloat(), e.optDouble(1).toFloat(), e.optDouble(2).toFloat(), e.optDouble(3).toFloat()))
                }
                if (asp.isNaN() || asp <= 0f || eyes.any { it.x.isNaN() || it.y.isNaN() || it.w.isNaN() || it.h.isNaN() }) null
                else CharacterRig(asp, eyes)
            } catch (_: Exception) { null }
        }
    }
}

/**
 * pure: एक ही नियम से हर कैरेक्टर की हलचल - साँस, पलक, थीम की रोशनी की धड़कन. कोई भी कैरेक्टर (महिला / पुरुष / रोबोट /
 * anime / 3D / अपना) - किसी के रूप पर कोई नियम नहीं. साँस = एक ही पैमाना (width / height साथ) - कभी खिंचाव नहीं.
 * "कम एनिमेशन" वाले के लिए बुलाने वाला t = 0 और [blink] = 0 देता है (सब स्थिर).
 */
object CharacterMotion {
    /** एक साँस (सेकंड) */
    const val BREATH_S = 4.8f
    /** साँस में सबसे ज़्यादा बड़ा होना (1.2%) - दोनों दिशा में एक ही */
    const val BREATH_SCALE = 0.012f
    /** एक पलक (सेकंड): बंद 0.07, रुकना 0.03, खुलना 0.09 */
    const val BLINK_S = 0.19f
    /** [gap] का दायरा (सेकंड) - इसी से हर खाने में पलक की जगह */
    const val BLINK_MIN_GAP = 2.6f
    const val BLINK_MAX_GAP = 5.8f
    /** रोशनी की धड़कन (सेकंड) - साँस से अलग ताकि सब एक साथ न हिले */
    const val PULSE_S = 3.6f

    /** साँस 0..1 (धीमा अंदर, थोड़ा जल्दी बाहर जैसा - sin का सीधा रूप, कोई झटका नहीं) */
    fun breath(t: Float): Float = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / BREATH_S - PI.toFloat() / 2f)

    /** साँस का पैमाना (width और height दोनों पर यही) */
    fun breathScale(t: Float, k: Float = 1f): Float = 1f + BREATH_SCALE * k.coerceIn(0f, 2f) * breath(t)

    /** सीना उठने के साथ ऊपर की ओर हल्का सरकना (px) */
    fun breathLift(t: Float, px: Float): Float = -px * breath(t)

    /** pure: [seed] से तय अगली पलक तक का समय - हर कैरेक्टर / view का अपना ताल, हर बार एक जैसा नहीं */
    fun gap(seed: Int, n: Int): Float {
        var h = seed * 0x9E3779B1.toInt() + n * 0x85EBCA77.toInt()
        h = h xor (h ushr 15); h *= 0x2C1B3C6D; h = h xor (h ushr 12)
        val u = ((h ushr 8) and 0xFFFF) / 65535f
        return BLINK_MIN_GAP + (BLINK_MAX_GAP - BLINK_MIN_GAP) * u
    }

    /** एक "खाना" (सेकंड) - हर खाने में एक पलक (कहीं भी), तो दो पलकों के बीच ~[BLINK_MIN_GAP]..[BLINK_MAX_GAP]+ */
    const val SLOT_S = 4.2f

    /** pure: पलक कितनी बंद (0 खुली .. 1 बंद). O(1), घंटों बाद भी वैसी ही. कभी-कभी (हर ~5वीं) दोहरी पलक */
    fun blink(t: Float, seed: Int = 0): Float {
        if (t <= 1.2f) return 0f
        val n = (t / SLOT_S).toInt()
        val u = (gap(seed, n) - BLINK_MIN_GAP) / (BLINK_MAX_GAP - BLINK_MIN_GAP)
        val at = n * SLOT_S + 0.15f + u * (SLOT_S - 0.75f)
        val double = (gap(seed + 7, n) - BLINK_MIN_GAP) / (BLINK_MAX_GAP - BLINK_MIN_GAP) > 0.8f
        val one = lid(t - at)
        if (one > 0f) return one
        return if (double) lid(t - at - BLINK_S - 0.08f) else 0f
    }

    /** एक पलक का रूप ([BLINK_S] के अंदर) */
    fun lid(dt: Float): Float = when {
        dt < 0f || dt > BLINK_S -> 0f
        dt < 0.07f -> CharacterArt.smooth(0f, 1f, dt / 0.07f)
        dt < 0.10f -> 1f
        else -> 1f - CharacterArt.smooth(0f, 1f, (dt - 0.10f) / 0.09f)
    }

    /** थीम की रोशनी की धड़कन 0..1 - बोलते समय आवाज़ के साथ तेज़, बाकी समय धीमी साँस जैसी */
    fun pulse(t: Float, speaking: Boolean, level: Float): Float {
        val slow = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / PULSE_S)
        return if (speaking) (0.35f + 0.35f * slow + 0.5f * level.coerceIn(0f, 1f)).coerceIn(0f, 1f) else 0.25f + 0.5f * slow
    }

    /** रोशनी का रंग: थीम का accent, पीछे के रंग के हिसाब से (हल्की थीम में थोड़ा गहरा - सफ़ेद पर दिखे) */
    fun pulseColor(bg: Int, accent: Int, light: Boolean): Int =
        if (light) NeonTheme.mix(accent, 0xFF0B1220.toInt(), 0.12f) else NeonTheme.mix(accent, 0xFFFFFFFF.toInt(), 0.18f)
}

/**
 * हर view का एक: rig वाली तस्वीर पर पलक बनाता है. पलक का रंग उसी तस्वीर से (आँख के ठीक ऊपर की त्वचा) - एक बार,
 * तस्वीर बदले तभी दोबारा. कोई नया bitmap / blur नहीं - सिर्फ़ दो oval और एक रेखा.
 */
class CharacterAnimator(val seed: Int = 0) {
    private val lidP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lashP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var colorsFor: Bitmap? = null
    private var colorsRig: CharacterRig? = null
    private var lids = IntArray(0)
    private var lashes = IntArray(0)
    private val r = RectF()

    /** आँख के ऊपर की त्वचा का रंग (3x3 का बीच वाला मान - चश्मे की एक रेखा / बाल से न बिगड़े) */
    private fun sample(b: Bitmap, x: Float, y: Float): Int {
        val xs = ArrayList<Int>(9)
        for (dy in -1..1) for (dx in -1..1) {
            val px = ((x + dx * 0.006f) * b.width).toInt().coerceIn(0, b.width - 1)
            val py = ((y + dy * 0.004f) * b.height).toInt().coerceIn(0, b.height - 1)
            xs.add(b.getPixel(px, py))
        }
        xs.sortBy { CharacterArt.luma(it) }
        return xs[xs.size / 2 + 1] or (0xFF shl 24)
    }

    private fun prepare(b: Bitmap, rig: CharacterRig) {
        if (colorsFor === b && colorsRig == rig) return
        colorsFor = b; colorsRig = rig
        lids = IntArray(rig.eyes.size); lashes = IntArray(rig.eyes.size)
        try {
            for ((i, e) in rig.eyes.withIndex()) {
                // पलक की त्वचा = आँख के ठीक नीचे / बगल की त्वचा (ऊपर बाल / भौंह / चश्मा हो सकता है) - सबसे चमकीला नमूना
                val skin = listOf(sample(b, e.x, e.y + e.h * 1.05f), sample(b, e.x - e.w * 0.15f, e.y + e.h * 1.3f),
                    sample(b, e.x + e.w * 0.15f, e.y + e.h * 1.3f)).maxByOrNull { CharacterArt.luma(it) } ?: continue
                lids[i] = skin
                lashes[i] = NeonTheme.mix(skin, 0xFF140A12.toInt(), 0.78f)
            }
        } catch (e: Exception) { com.dk.assistant.Quiet.note(e); lids = IntArray(0) }
    }

    /**
     * [dst] = तस्वीर जहाँ बनी (उसी canvas transform में बुलाओ - साँस के साथ पलक भी हिले). [closure] 0..1.
     * rig इस तस्वीर पर न लगे = कुछ नहीं.
     */
    fun drawBlink(cv: Canvas, b: Bitmap?, rig: CharacterRig?, dst: RectF, closure: Float, alpha: Float = 1f) {
        if (b == null || rig == null || closure <= 0.02f || !rig.fits(b.width, b.height)) return
        prepare(b, rig)
        if (lids.size != rig.eyes.size) return
        val tilt = if (rig.eyes.size >= 2) {
            val a = rig.eyes[0]; val c = rig.eyes[1]
            Math.toDegrees(atan2(((c.y - a.y) * dst.height()).toDouble(), ((c.x - a.x) * dst.width()).toDouble())).toFloat()
        } else 0f
        val al = (alpha.coerceIn(0f, 1f) * 255).toInt()
        for ((i, e) in rig.eyes.withIndex()) {
            val cx = dst.left + e.x * dst.width(); val cy = dst.top + e.y * dst.height()
            val ew = e.w * dst.width() * 1.02f; val eh = e.h * dst.height() * 1.15f
            val lidBottom = cy - eh / 2f + eh * closure
            cv.save()
            cv.rotate(tilt, cx, cy)
            cv.clipRect(cx - ew, cy - eh, cx + ew, lidBottom + eh * 0.08f)
            // ऊपर की पलक: ऊपर हल्की गहरी (crease), नीचे त्वचा - और दो परतें (बाहर फीकी) ताकि किनारा नरम
            val crease = NeonTheme.mix(lids[i], lashes[i], 0.22f)
            lidP.shader = android.graphics.LinearGradient(0f, cy - eh / 2f, 0f, lidBottom,
                NeonTheme.alpha(crease, al), NeonTheme.alpha(lids[i], al), android.graphics.Shader.TileMode.CLAMP)
            lidP.alpha = al / 3
            r.set(cx - ew * 0.62f, cy - eh * 0.64f, cx + ew * 0.62f, cy + eh * 0.64f)
            cv.drawOval(r, lidP)
            lidP.alpha = al
            r.set(cx - ew / 2f, cy - eh / 2f, cx + ew / 2f, cy + eh / 2f)
            cv.drawOval(r, lidP)
            lidP.shader = null
            cv.restore()
            // पलकों की रेखा (बंद होते समय उभरे): नीचे की ओर झुकी हल्की चाप
            val lk = CharacterArt.smooth(0.45f, 1f, closure)
            if (lk > 0f) {
                cv.save(); cv.rotate(tilt, cx, cy)
                lashP.color = NeonTheme.alpha(lashes[i], (al * lk * 0.9f).toInt())
                lashP.strokeWidth = maxOf(1f, eh * 0.09f)
                val sag = eh * 0.18f
                r.set(cx - ew * 0.46f, lidBottom - eh * 0.10f - sag, cx + ew * 0.46f, lidBottom - eh * 0.10f + sag)
                cv.drawArc(r, 12f, 156f, false, lashP)
                cv.restore()
            }
        }
    }
}
