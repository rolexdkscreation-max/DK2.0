package com.dk.assistant

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup

/**
 * 14 नवंबर 2026 (मालिक): "सेटिंग पैनल भी साधारण, जैसे होम पैनल है - ग्लासी टाइप का हटा के नॉर्मल वाला"।
 * किसी स्क्रीन पर लगाओ: काँच (Liquid Glass) वाली हर पृष्ठभूमि -> होम (DKAI) जैसा सपाट गहरा रंग,
 * पीछे की चमकती Aurora छिपी. सिर्फ़ दिखावट बदलती है - कोई बटन/काम नहीं छूटता.
 */
object PlainLook {
    // होम (DkaiTheme अँधेरा) वाले रंग - हमेशा यही, ताकि सफ़ेद लिखावट साफ़ पढ़ी जाए
    const val BG = 0xFF0B101D.toInt()
    const val CARD = 0xFF161E31.toInt()
    const val BORDER = 0xFF26324D.toInt()
    const val PILL = 0xFF1F2940.toInt()
    const val FIELD = 0xFF0B1324.toInt()
    const val BLUE = 0xFF3B82F6.toInt()
    const val ME = 0xFF1E2A47.toInt()
    const val PRESS = 0x33FFFFFF

    private val TAG = R.id.plain_look   // अलग निशान (सिर्फ़ इसी काम का)
    const val GAP_MS = 150L

    enum class Kind { CARD, CIRCLE, ACCENT_CIRCLE, PILL, ACCENT, ROW, FIELD, BUBBLE_DK, BUBBLE_ME }

    /** काँच वाली drawable class का नाम -> सपाट रूप (JVM test) */
    fun kindOf(cls: String): Kind? = when (cls.substringAfterLast('.')) {
        "GlassCardDrawable", "GlassDialogDrawable" -> Kind.CARD
        "GlassCircleDrawable" -> Kind.CIRCLE
        "GlassAccentCircleDrawable", "GlassLiveCircleDrawable" -> Kind.ACCENT_CIRCLE
        "GlassPillDrawable" -> Kind.PILL
        "GlassAccentDrawable" -> Kind.ACCENT
        "GlassRowDrawable" -> Kind.ROW
        "GlassInputDrawable", "GlassComposerDrawable" -> Kind.FIELD
        "GlassBubbleDkDrawable" -> Kind.BUBBLE_DK
        "GlassBubbleMeDrawable" -> Kind.BUBBLE_ME
        else -> null
    }

    /** हल्का पारदर्शी सफ़ेद (#12FFFFFF, #22FFFFFF…) = पुराने काँच के डिब्बे */
    fun isGlassyWhite(argb: Int): Boolean {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return a in 1..0x60 && rgb == 0xFFFFFF
    }

    fun install(a: Activity) {
        val root = a.window?.decorView ?: return
        try { a.window?.setBackgroundDrawable(ColorDrawable(BG)) } catch (_: Exception) {}
        var queued = false
        val pass = Runnable { queued = false; try { walk(root) } catch (_: Exception) {} }
        root.viewTreeObserver.addOnGlobalLayoutListener { if (!queued) { queued = true; root.postDelayed(pass, GAP_MS) } }
        root.post { try { walk(root) } catch (_: Exception) {} }
    }

    /** एक बार (जैसे PopupWindow / dialog का view) */
    fun apply(v: View) { try { walk(v) } catch (_: Exception) {} }

    private fun walk(v: View) {
        if (v is AuroraView) { if (v.visibility != View.GONE) v.visibility = View.GONE; return }
        flatten(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun flatten(v: View) {
        val bg = v.background ?: return
        if (v.getTag(TAG) === bg) return
        val dp = v.resources.displayMetrics.density
        val kind = glassKind(bg)
        val flat: Drawable? = when {
            kind != null -> make(kind, dp)
            bg is GradientDrawable && glassyGradient(bg) ->
                GradientDrawable().apply {
                    cornerRadius = try { bg.cornerRadius } catch (_: Exception) { 16 * dp }
                    setColor(CARD); setStroke(dp.toInt().coerceAtLeast(1), BORDER)
                }
            else -> null
        }
        if (flat == null) return
        val d = if (v.isClickable) RippleDrawable(ColorStateList.valueOf(PRESS), flat, null) else flat
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.background = d
        v.setPadding(l, t, r, b)
        v.setTag(TAG, d)
    }

    private fun glassyGradient(g: GradientDrawable): Boolean = try {
        val c = g.color?.defaultColor
        c != null && isGlassyWhite(c)
    } catch (_: Exception) { false }

    private fun glassKind(d: Drawable): Kind? {
        if (d is LiquidGlassDrawable) return kindOf(d.javaClass.name)
        if (d is LayerDrawable) for (i in 0 until d.numberOfLayers) {
            val k = d.getDrawable(i)?.let { glassKind(it) }
            if (k != null) return k
        }
        return null
    }

    private fun make(k: Kind, dp: Float): Drawable {
        fun round(fill: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply {
            cornerRadius = r * dp; setColor(fill)
            if (stroke != 0) setStroke(dp.toInt().coerceAtLeast(1), stroke)
        }
        fun oval(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        return when (k) {
            Kind.CARD -> round(CARD, 18f, BORDER)
            Kind.CIRCLE -> oval(PILL)
            Kind.ACCENT_CIRCLE -> oval(BLUE)
            Kind.PILL -> round(PILL, 999f)
            Kind.ACCENT -> round(BLUE, 999f)
            Kind.ROW -> round(PILL, 12f)
            Kind.FIELD -> round(FIELD, 18f, BORDER)
            Kind.BUBBLE_DK -> round(CARD, 16f, BORDER)
            Kind.BUBBLE_ME -> round(ME, 16f)
        }
    }
}
