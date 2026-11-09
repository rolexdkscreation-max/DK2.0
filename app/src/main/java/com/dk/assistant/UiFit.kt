package com.dk.assistant

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * नवंबर 2026 (मालिक): DK कंप्यूटर + कोडिंग एजेंट की स्क्रीन हर डिवाइस पर पूरी भरी और सही आकार की।
 *
 * समस्याएँ जो ठीक हुईं:
 *  - फ़ोन (बड़े अक्षर): ऊपर के बटन नाम/मॉडल वाली लाइन को दबा देते थे, आखिरी बटन कट जाता था
 *  - टैबलेट: सब कुछ फ़ोन वाले dp में = अक्षर, बटन, header बहुत छोटे दिखते थे
 *
 * Android native ऐप है (वेब पेज नहीं), इसलिए वेब वाले नियमों के बराबर:
 *  - "clamp()" / rem  = [UiFitRules.scaleFor]: स्क्रीन की चौड़ाई से पूरा UI (अक्षर + बटन + गोलाई + जगह) एक साथ बड़ा, न्यूनतम/अधिकतम सीमा में
 *  - media queries    = फ़ोन < 600dp, टैबलेट 600–1024dp, बड़ी स्क्रीन > 1024dp
 *  - flex-wrap        = [WrapRow]: बटन जगह न हो तो अगली पंक्ति में (आड़ा scroll नहीं)
 *  - min 44px touch   = [UiFitRules.MIN_TOUCH_DP]
 *  - safe-area        = [safeArea]: status bar / notch / नीचे की पट्टी जितनी भीतरी जगह (अगर system ने पहले से नहीं छोड़ी)
 *  - 100dvh / flex:1  = root MATCH_PARENT + बीच की chat weight 1 (Activity में पहले से), keyboard पर adjustResize
 */
object UiFitRules {
    /** इससे बड़े system font पर भी यही (बहुत बड़े अक्षर से लेआउट न टूटे) */
    const val MAX_FONT_SCALE = 1.0f
    fun fontScale(system: Float): Float = if (system.isNaN() || system <= 0f) 1f else minOf(system, MAX_FONT_SCALE)

    const val PHONE_MAX_DP = 600
    const val TABLET_MAX_DP = 1024
    const val TABLET_MIN_SCALE = 1.15f
    const val MAX_SCALE = 1.30f
    /** हर बटन कम से कम इतना ऊँचा (छूने लायक) */
    const val MIN_TOUCH_DP = 44

    enum class Kind { PHONE, TABLET, LARGE }
    fun kind(widthDp: Int): Kind = when {
        widthDp < PHONE_MAX_DP -> Kind.PHONE
        widthDp <= TABLET_MAX_DP -> Kind.TABLET
        else -> Kind.LARGE
    }

    /**
     * पूरे UI का आकार-गुणक (clamp जैसा): फ़ोन 1.0, टैबलेट 1.15 → 1.30 (चौड़ाई के साथ धीरे-धीरे), बड़ी स्क्रीन 1.30
     */
    fun scaleFor(widthDp: Int): Float = when (kind(widthDp)) {
        Kind.PHONE -> 1f
        Kind.TABLET -> TABLET_MIN_SCALE + (MAX_SCALE - TABLET_MIN_SCALE) * (widthDp - PHONE_MAX_DP).toFloat() / (TABLET_MAX_DP - PHONE_MAX_DP)
        Kind.LARGE -> MAX_SCALE
    }

    /** स्क्रीन के किनारे से content की दूरी (dp): टैबलेट/बड़ी स्क्रीन पर कम खाली किनारे */
    fun edgeDp(widthDp: Int): Int = if (kind(widthDp) == Kind.PHONE) 12 else 10
}

object UiFit {
    /**
     * Activity.attachBaseContext में (दोनों स्क्रीन, एक ही जगह): font scale सीमा में + टैबलेट पर पूरा UI बड़ा.
     * density बढ़ाने से dp में बना हर आकार (अक्षर, बटन, गोलाई, जगह) एक साथ बड़ा होता है; pixel चौड़ाई वही रहती है.
     */
    fun wrap(base: Context): Context {
        val cfg = base.resources.configuration
        val font = UiFitRules.fontScale(cfg.fontScale)
        val w = cfg.screenWidthDp
        val scale = if (w == Configuration.SCREEN_WIDTH_DP_UNDEFINED || w <= 0) 1f else UiFitRules.scaleFor(w)
        if (font == cfg.fontScale && scale == 1f) return base
        val c = Configuration(cfg).apply {
            fontScale = font
            if (scale != 1f && cfg.densityDpi != Configuration.DENSITY_DPI_UNDEFINED) {
                densityDpi = (cfg.densityDpi * scale).toInt()
                screenWidthDp = (cfg.screenWidthDp / scale).toInt()
                screenHeightDp = (cfg.screenHeightDp / scale).toInt()
                smallestScreenWidthDp = (cfg.smallestScreenWidthDp / scale).toInt()
            }
        }
        return base.createConfigurationContext(c)
    }

    /** असली (बिना बढ़ाए) स्क्रीन चौड़ाई dp में - फ़ोन/टैबलेट तय करने के लिए */
    fun realWidthDp(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val baseDensity = ctx.applicationContext.resources.displayMetrics.density.takeIf { it > 0f } ?: dm.density
        return (dm.widthPixels / baseDensity).toInt()
    }

    /** स्क्रीन के किनारे की भीतरी जगह (px) */
    fun edgePx(ctx: Context): Int = (UiFitRules.edgeDp(realWidthDp(ctx)) * ctx.resources.displayMetrics.density).toInt()

    /**
     * safe-area: notch / status bar / नीचे की पट्टी content को न ढके.
     * Activity पहले से system bars के अंदर हो (आम हालत) तो यहाँ 0 आता है - दोहरी जगह नहीं बनती.
     */
    fun safeArea(root: View) {
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val s = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(l + s.left, t + s.top, r + s.right, b + s.bottom)
            ins
        }
    }

    /**
     * ऊपर की पट्टी: पहली लाइन [back][नाम + मॉडल (पूरी बची चौड़ाई, लंबा हो तो …)],
     * दूसरी लाइन बटन - जगह न हो तो अगली पंक्ति में लपेटे जाते हैं (कभी कटते नहीं, आड़ा scroll नहीं).
     */
    fun header(ctx: Context, back: View, titles: View, buttons: List<View>): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        val touch = (UiFitRules.MIN_TOUCH_DP * dp).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(back, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        row1.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row1, LinearLayout.LayoutParams(-1, -2))
        val gap = (6 * dp).toInt()
        val wrapRow = WrapRow(ctx, gap, gap)
        buttons.forEach { b ->
            b.minimumHeight = touch
            if (b is android.widget.TextView) { b.minHeight = touch; b.gravity = Gravity.CENTER }
            wrapRow.addView(b, ViewGroup.LayoutParams(-2, -2))
        }
        box.addView(wrapRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        return box
    }
}

/** बच्चों को बाएँ से दाएँ रखता है; जगह खत्म = अगली पंक्ति (CSS flex-wrap जैसा) */
class WrapRow(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0; var y = 0; var rowH = 0; var widest = 0
        val childW = if (unbounded) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            c.measure(childW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val cw = c.measuredWidth
            if (!unbounded && x > 0 && x + cw > maxW) { y += rowH + vGap; x = 0; rowH = 0 }
            x += cw + hGap; rowH = maxOf(rowH, c.measuredHeight); widest = maxOf(widest, x - hGap)
        }
        val h = y + rowH + paddingTop + paddingBottom
        val w = if (unbounded) widest + paddingLeft + paddingRight else MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            val cw = c.measuredWidth; val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) { y += rowH + vGap; x = 0; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + cw, paddingTop + y + ch)
            x += cw + hGap; rowH = maxOf(rowH, ch)
        }
    }
}
