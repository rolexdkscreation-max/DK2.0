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

    // 14 नवंबर 2026 (मालिक: "डार्क / लाइट मोड दिखे और काम करे"): लाइट चुनी हो ([com.dk.assistant.dkai.DkaiTheme.light]) = होम जैसे उजले रंग
    const val L_BG = 0xFFF4F6FB.toInt()
    const val L_CARD = 0xFFFFFFFF.toInt()
    const val L_BORDER = 0xFFDCE3EE.toInt()
    const val L_PILL = 0xFFEEF2F7.toInt()
    const val L_FIELD = 0xFFF1F5F9.toInt()
    const val L_ME = 0xFFE0E7FF.toInt()
    const val L_TEXT = 0x0F172A
    private val light get() = com.dk.assistant.dkai.DkaiTheme.light
    private fun bg() = if (light) L_BG else BG
    private fun card() = if (light) L_CARD else CARD
    private fun border() = if (light) L_BORDER else BORDER
    private fun pill() = if (light) L_PILL else PILL
    private fun field() = if (light) L_FIELD else FIELD
    private fun me() = if (light) L_ME else ME

    /** लाइट में: सफ़ेद-सी लिखावट -> गहरी (उतनी ही पारदर्शिता). बाकी रंग (नीला/हरा/लाल) वैसे ही. pure (JVM test) */
    fun lightText(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return if (r >= 0xC8 && g >= 0xC8 && b >= 0xC8) (argb and 0xFF000000.toInt()) or L_TEXT else argb
    }

    /** पूरा अपारदर्शी और लगभग काला-नीला (पृष्ठभूमि) - pure */
    fun darkSolid(argb: Int): Boolean {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return a >= 0xF0 && r < 0x30 && g < 0x30 && b < 0x40
    }

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
        try { com.dk.assistant.dkai.DkaiTheme.load(a) } catch (_: Exception) {}
        try { a.window?.setBackgroundDrawable(ColorDrawable(bg())) } catch (_: Exception) {}
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
        if (light) lighten(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun flatten(v: View) {
        val bg = v.background ?: return
        if (v.getTag(TAG) === bg) return
        // लाइट: गहरी सपाट पृष्ठभूमि (जैसे सेटिंग की @color/bg_space_dark) -> उजली
        if (light && bg is ColorDrawable && darkSolid(bg.color)) {
            val d = ColorDrawable(L_BG); v.background = d; v.setTag(TAG, d); return
        }
        // 14 नवंबर 2026 (मालिक: "सारी स्क्रीन होम जैसी"): डार्क में भी पूरी स्क्रीन की गहरी-नीली पृष्ठभूमि (#0A1033 आदि) -> होम वाला रंग
        if (!light && bg is ColorDrawable && darkSolid(bg.color) && bg.color != BG && fillsScreen(v)) {
            val d = ColorDrawable(BG); v.background = d; v.setTag(TAG, d); return
        }
        val dp = v.resources.displayMetrics.density
        val kind = glassKind(bg)
        val flat: Drawable? = when {
            kind != null -> make(kind, dp)
            bg is GradientDrawable && glassyGradient(bg) ->
                GradientDrawable().apply {
                    cornerRadius = try { bg.cornerRadius } catch (_: Exception) { 16 * dp }
                    setColor(card()); setStroke(dp.toInt().coerceAtLeast(1), border())
                }
            else -> null
        }
        if (flat == null) return
        val d = if (v.isClickable) RippleDrawable(ColorStateList.valueOf(PRESS), flat, null) else flat
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.background = d
        v.setPadding(l, t, r, b)
        v.setTag(TAG, d)
        v.setTag(ACCENT_TAG, kind == Kind.ACCENT || kind == Kind.ACCENT_CIRCLE)
    }

    /** लाइट: लिखावट गहरी (नीले बटन के ऊपर की सफ़ेद लिखावट नहीं), गोल बटन का सफ़ेद चिह्न गहरा */
    // 14 नवंबर 2026 (मालिक: "लाइट में सिर्फ़ बैकग्राउंड सफ़ेद, अक्षर/आइकन/रंग वैसे ही पूरे दिखें"):
    // अब असली पीछे वाला रंग (view + उसके ऊपर वाले) देखकर - उजले पर हो तभी सफ़ेद लिखावट/चिह्न गहरा;
    // रंगीन लिखावट/आइकन कभी नहीं बदलते; गहरे डिब्बे (कोड में बने) भी उजले.
    private fun lighten(v: View) {
        lightBox(v)
        val under = underLight(v)
        if (v is android.widget.TextView && under == true) {
            val c = v.currentTextColor; val n = lightText(c)
            if (n != c) v.setTextColor(n)
            val h = v.currentHintTextColor; val nh = lightText(h)
            if (nh != h) v.setHintTextColor(nh)
            v.compoundDrawableTintList?.defaultColor?.let { t -> if (lightText(t) != t) v.compoundDrawableTintList = ColorStateList.valueOf(lightText(t)) }
        }
        if (v is android.widget.ImageView && under == true) lightIcon(v)
    }

    /** लाइट: कोड में बना ऐप वाला गहरा डिब्बा (GradientDrawable) -> उजला कार्ड + किनारा */
    private fun lightBox(v: View) {
        val bg = v.background ?: return
        if (v.getTag(TAG) === bg) return
        val g = (if (bg is RippleDrawable && bg.numberOfLayers > 0) bg.getDrawable(0) else bg) as? GradientDrawable ?: return
        val c = try { g.color?.defaultColor } catch (_: Exception) { null } ?: return
        if (!appDark(c)) return
        val dp = v.resources.displayMetrics.density
        val fill = if (c == PILL || c == ME) pill() else card()
        val flat = GradientDrawable().apply {
            shape = g.shape
            if (g.shape != GradientDrawable.OVAL) cornerRadius = try { g.cornerRadius } catch (_: Exception) { 16 * dp }
            setColor(fill); setStroke(dp.toInt().coerceAtLeast(1), border())
        }
        val d = if (v.isClickable) RippleDrawable(ColorStateList.valueOf(0x220F172A), flat, null) else flat
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.background = d; v.setPadding(l, t, r, b); v.setTag(TAG, d)
    }

    /** ऐप के अपने गहरे डिब्बे वाले रंग (pure, JVM test) - बाकी (रंगीन/काले कैमरा वगैरह) नहीं छूते */
    fun appDark(argb: Int): Boolean = argb in APP_DARK
    private val APP_DARK = setOf(BG, CARD, PILL, FIELD, ME, 0xFF0A1033.toInt(), 0xFF040711.toInt(), 0xFF090D1A.toInt(),
        0xFF0A0F3A.toInt(), 0xFF050A1F.toInt(), 0xFF0E1440.toInt(), 0xFF0D1324.toInt(), 0xFF101634.toInt(), 0xFF0D1440.toInt())

    /** pure: रंग उजला है? */
    fun isLightColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return r * 299 + g * 587 + b * 114 >= 160_000
    }

    /** pure: पीछे का रंग चुनो - null = पता नहीं, alpha कम = पारदर्शी (ऊपर वाले को देखो) */
    fun opaqueEnough(argb: Int): Boolean = ((argb ushr 24) and 0xFF) >= 0x80

    /** drawable का भरा रंग: 0 = पारदर्शी, null = पता नहीं (फ़ोटो/चित्र) */
    private fun colorOf(d: Drawable?): Int? = when (d) {
        null -> 0
        is ColorDrawable -> d.color
        is GradientDrawable -> try { d.color?.defaultColor ?: d.colors?.firstOrNull() } catch (_: Exception) { null }
        is LayerDrawable -> {
            var c: Int? = 0
            for (i in 0 until d.numberOfLayers) {
                val x = colorOf(d.getDrawable(i))
                if (x == null) { c = null; break }
                if (opaqueEnough(x)) { c = x; break }
            }
            c
        }
        is android.graphics.drawable.InsetDrawable -> colorOf(d.drawable)
        is android.graphics.drawable.DrawableContainer -> colorOf(d.current)
        else -> null
    }

    /** लाइट में view उजले रंग के ऊपर है? (true/false, null = पता नहीं -> मत छुओ) */
    private fun underLight(v: View): Boolean? {
        var cur: View? = v
        while (cur != null) {
            val c = colorOf(cur.background) ?: return null
            if (opaqueEnough(c)) return isLightColor(c)
            cur = cur.parent as? View
        }
        return true   // खिड़की की पृष्ठभूमि = L_BG
    }

    /** उजले पर रखा सफ़ेद-सा चिह्न -> गहरा. रंगीन चिह्न, फ़ोटो, कैमरा - वैसे ही */
    private fun lightIcon(v: android.widget.ImageView) {
        val d = v.drawable ?: return
        val tint = v.imageTintList?.defaultColor
        val key = listOf(System.identityHashCode(d), tint)
        if (v.getTag(ICON_TAG) == key) return
        v.setTag(ICON_TAG, key)
        if (tint != null) {
            if (lightText(tint) != tint) v.imageTintList = ColorStateList.valueOf(lightText(tint))
            return
        }
        if (d is android.graphics.drawable.BitmapDrawable) return
        val dp = v.resources.displayMetrics.density
        if (v.width > 96 * dp || v.height > 96 * dp) return
        if (whiteIcon(d)) v.setColorFilter(0xFF000000.toInt() or L_TEXT, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    /** drawable की प्रति 24px पर बनाकर: दिखते pixel लगभग सब सफ़ेद? */
    private fun whiteIcon(d: Drawable): Boolean = try {
        val copy = d.constantState?.newDrawable()?.mutate() ?: throw IllegalStateException()
        val bmp = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, 24, 24); copy.draw(android.graphics.Canvas(bmp))
        var seen = 0; var white = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = bmp.getPixel(x, y)
            if (((p ushr 24) and 0xFF) < 0x80) continue
            seen++; if (lightText(p) != p) white++
        }
        bmp.recycle()
        whiteShare(white, seen)
    } catch (_: Exception) { false }

    /** pure: 80%+ दिखते pixel सफ़ेद-से = सफ़ेद चिह्न */
    fun whiteShare(white: Int, seen: Int): Boolean = seen > 0 && white * 10 >= seen * 8
    /** view लगभग पूरी स्क्रीन घेरता है (पृष्ठभूमि वाला) */
    private fun fillsScreen(v: View): Boolean {
        val r = v.rootView ?: return false
        return r.width > 0 && r.height > 0 && fills(v.width, v.height, r.width, r.height)
    }
    /** pure (JVM test): चौड़ाई 90%+ और ऊँचाई 60%+ */
    fun fills(w: Int, h: Int, rw: Int, rh: Int): Boolean = rw > 0 && rh > 0 && w * 10 >= rw * 9 && h * 10 >= rh * 6

    private val ACCENT_TAG = R.id.plain_look_accent
    private val ICON_TAG = R.id.plain_look_icon

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
            Kind.CARD -> round(card(), 18f, border())
            Kind.CIRCLE -> oval(pill())
            Kind.ACCENT_CIRCLE -> oval(BLUE)
            Kind.PILL -> round(pill(), 999f)
            Kind.ACCENT -> round(BLUE, 999f)
            Kind.ROW -> round(pill(), 12f)
            Kind.FIELD -> round(field(), 18f, border())
            Kind.BUBBLE_DK -> round(card(), 16f, border())
            Kind.BUBBLE_ME -> round(me(), 16f)
        }
    }
}
