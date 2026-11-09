package com.dk.assistant

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * नवंबर 2026 (मालिक): फ़ोन पर (बड़े अक्षर / बड़ा display) DK कंप्यूटर और कोडिंग एजेंट की ऊपर वाली पट्टी में
 * बटन इतने चौड़े हो जाते थे कि नाम + मॉडल वाली लाइन की चौड़ाई 0 हो जाती थी और आखिरी बटन कट जाता था।
 * टैब पर सब ठीक दिखता था। अब:
 *  1. इन स्क्रीनों पर अक्षरों का साइज़ एक सीमा तक ही बढ़ता है ([UiFitRules.MAX_FONT_SCALE]) — फ़ोन/टैब एक जैसे
 *  2. ऊपर की पट्टी दो लाइन: [वापस + नाम/मॉडल] और नीचे बटन (जगह कम हो तो खिसकाकर)
 */
object UiFitRules {
    /** इससे बड़े system font पर भी यही (टैब और फ़ोन पर एक जैसा लेआउट) */
    const val MAX_FONT_SCALE = 1.0f
    fun fontScale(system: Float): Float = if (system.isNaN() || system <= 0f) 1f else minOf(system, MAX_FONT_SCALE)
}

object UiFit {
    /** Activity.attachBaseContext में: font scale सीमा में */
    fun wrap(base: Context): Context {
        val cfg = base.resources.configuration
        val want = UiFitRules.fontScale(cfg.fontScale)
        if (want == cfg.fontScale) return base
        val c = Configuration(cfg).apply { fontScale = want }
        return base.createConfigurationContext(c)
    }

    /**
     * ऊपर की पट्टी: पहली लाइन [back][title (पूरी चौड़ाई)], दूसरी लाइन बटन (HorizontalScrollView - कभी कटते नहीं)
     */
    fun header(ctx: Context, back: View, titles: View, buttons: List<View>): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(back, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        row1.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row1, LinearLayout.LayoutParams(-1, -2))
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttons.forEachIndexed { i, b ->
            btnRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = (6 * dp).toInt() })
        }
        val hs = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(btnRow)
        }
        box.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        return box
    }
}
