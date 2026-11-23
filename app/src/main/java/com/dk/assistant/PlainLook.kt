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
import com.dk.assistant.ui.ClayDrawable.Style as ClayStyle

/**
 * 14 नवंबर 2026 (मालिक): "सेटिंग पैनल भी साधारण, जैसे होम पैनल है - ग्लासी टाइप का हटा के नॉर्मल वाला"।
 * किसी स्क्रीन पर लगाओ: काँच (Liquid Glass) वाली हर पृष्ठभूमि -> होम (DKAI) जैसा सपाट गहरा रंग,
 * पीछे की चमकती Aurora छिपी. सिर्फ़ दिखावट बदलती है - कोई बटन/काम नहीं छूटता.
 */
object PlainLook {
    // होम (DkaiTheme अँधेरा) वाले रंग - 15 नवंबर 2026: अब सब एक ही theme variables फ़ाइल (ui/ThemeVars) से
    private val D = com.dk.assistant.ui.ThemeVars.DARK
    private val L = com.dk.assistant.ui.ThemeVars.LIGHT
    val BG = D.bg
    val CARD = D.card
    val BORDER = D.border
    val PILL = D.pill
    val FIELD = D.field
    const val BLUE = 0xFF3B82F6.toInt()
    val ME = D.sel
    const val PRESS = 0x33FFFFFF

    // लाइट चुनी हो ([com.dk.assistant.dkai.DkaiTheme.light]) = वही design, सिर्फ़ पृष्ठभूमि / कार्ड / लिखावट उजले-गहरे
    val L_BG = L.bg
    val L_CARD = L.card
    val L_BORDER = L.border
    val L_PILL = L.pill
    val L_FIELD = L.field
    val L_ME = L.sel
    const val L_TEXT = 0x0F172A
    private val light get() = com.dk.assistant.dkai.DkaiTheme.light
    // 23 नवंबर 2026 (Soft Clay): असली रंग चुनी थीम के 3 टोकन (bg / surface / accent) से बने
    private fun bg() = com.dk.assistant.ui.ClayTheme.t.bg
    private fun card() = com.dk.assistant.ui.ClayTheme.t.surface
    private fun border() = com.dk.assistant.ui.ClayTheme.t.rim
    private fun pill() = com.dk.assistant.ui.ClayTheme.t.chip
    private fun field() = com.dk.assistant.ui.ClayTheme.t.well
    private fun me() = com.dk.assistant.ui.ClayTheme.t.sel

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

    /*
     * 15 नवंबर 2026 (मालिक: "light mode के pastel gradient और गुलाबी-नीले कार्ड हटाओ - हर कार्ड solid"):
     * पहले यहाँ light के रंगीन (pastel) कार्ड बनते थे (LIGHT_TINTS / pastel / colorCard). सब हटा - light में
     * हर कार्ड ठोस सफ़ेद + वही हल्का किनारा, बिल्कुल dark वाले कार्ड जैसा आकार.
     */
    /** pure: ठोस कार्ड (कोई gradient नहीं) */
    fun solidCard(fill: Int, radiusPx: Float, strokePx: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(fill); setStroke(strokePx.coerceAtLeast(1), stroke)
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
        // 23 नवंबर 2026: अब हर स्क्रीन पर अपने-आप ([com.dk.assistant.ui.Clay.Refresh]) - दो बार न लगे
        if (root.getTag(INSTALLED) == true) return
        root.setTag(INSTALLED, true)
        try { com.dk.assistant.dkai.DkaiTheme.load(a) } catch (e: Exception) { Quiet.note(e) }
        try { a.window?.setBackgroundDrawable(ColorDrawable(bg())) } catch (e: Exception) { Quiet.note(e) }
        var queued = false
        val pass = Runnable { queued = false; try { walk(root) } catch (e: Exception) { Quiet.note(e) } }
        root.viewTreeObserver.addOnGlobalLayoutListener { if (!queued) { queued = true; root.postDelayed(pass, GAP_MS) } }
        root.post { try { walk(root) } catch (e: Exception) { Quiet.note(e) } }
    }

    /** एक बार (जैसे PopupWindow / dialog का view) */
    fun apply(v: View) { try { walk(v) } catch (e: Exception) { Quiet.note(e) } }

    private fun walk(v: View) {
        if (v is AuroraView) { if (v.visibility != View.GONE) v.visibility = View.GONE; return }
        if (v.getTag(SKIP) == true) return
        flatten(v)
        clayBox(v)
        if (light) lighten(v)
        try { com.dk.assistant.ui.Clay.decorate(v) } catch (e: Exception) { Quiet.note(e) }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun flatten(v: View) {
        val bg = v.background ?: return
        if (v.getTag(TAG) === bg) return
        // लाइट: गहरी सपाट पृष्ठभूमि (जैसे सेटिंग की @color/bg_space_dark) -> उजली
        if (light && bg is ColorDrawable && darkSolid(bg.color)) {
            val d = ColorDrawable(bg()); v.background = d; v.setTag(TAG, d); return
        }
        // 14 नवंबर 2026 (मालिक: "सारी स्क्रीन होम जैसी"): डार्क में भी पूरी स्क्रीन की गहरी-नीली पृष्ठभूमि (#0A1033 आदि) -> होम वाला रंग
        // 23 नवंबर 2026: "होम वाला रंग" = चुनी Clay थीम की पृष्ठभूमि
        if (!light && bg is ColorDrawable && darkSolid(bg.color) && bg.color != bg() && fillsScreen(v)) {
            val d = ColorDrawable(bg()); v.background = d; v.setTag(TAG, d); return
        }
        val dp = v.resources.displayMetrics.density
        val kind = glassKind(bg)
        val flat: Drawable? = when {
            kind != null -> make(kind, dp)
            bg is GradientDrawable && glassyGradient(bg) -> clayLike(v, bg, Kind.CARD)
            else -> null
        }
        if (flat == null) return
        // Clay डिब्बा दबने पर खुद धँसता है (120ms) - ripple की ज़रूरत नहीं
        val d = flat
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
            val c = v.currentTextColor; val n = themeText(c)
            if (n != c) v.setTextColor(n)
            val h = v.currentHintTextColor; val nh = themeText(h)
            if (nh != h) v.setHintTextColor(nh)
            v.compoundDrawableTintList?.defaultColor?.let { t -> if (lightText(t) != t) v.compoundDrawableTintList = ColorStateList.valueOf(themeText(t)) }
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
        val rad = if (g.shape != GradientDrawable.OVAL) (try { g.cornerRadius } catch (_: Exception) { 16 * dp }) else 0f
        val flat = GradientDrawable().apply {
            shape = g.shape
            if (g.shape != GradientDrawable.OVAL) cornerRadius = rad
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
        is com.dk.assistant.ui.ClayDrawable -> d.baseColor()
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
            if (lightText(tint) != tint) v.imageTintList = ColorStateList.valueOf(themeText(tint))
            return
        }
        if (d is android.graphics.drawable.BitmapDrawable) return
        val dp = v.resources.displayMetrics.density
        if (v.width > 96 * dp || v.height > 96 * dp) return
        if (whiteIcon(d)) v.setColorFilter(com.dk.assistant.ui.ClayTheme.t.text, android.graphics.PorterDuff.Mode.SRC_IN)
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

    /*
     * 23 नवंबर 2026 (मालिक: Soft 3D Clay): हर पुराना काँच वाला डिब्बा -> उभरा मिट्टी जैसा (दो परछाइयाँ, कोने 20–28dp).
     * text box / composer = धँसा, accent = चमकदार ढलान. सारे रंग चुनी थीम ([com.dk.assistant.ui.ClayTheme]) से.
     */
    private fun make(k: Kind, dp: Float): Drawable {
        val FULL = com.dk.assistant.ui.ClayDrawable.FULL
        val chip = { com.dk.assistant.ui.ClayTheme.t.chip }
        fun c(style: ClayStyle, r: Float, depth: Float = 5f, oval: Boolean = false, fill: (() -> Int)? = null) =
            com.dk.assistant.ui.ClayDrawable(style, radiusPx = if (r >= FULL) FULL else r * dp, oval = oval, depthPx = depth * dp, fill = fill)
        return when (k) {
            Kind.CARD -> com.dk.assistant.ui.ClayDrawable(ClayStyle.RAISED, radiusPx = com.dk.assistant.ui.ClayCard.RADIUS_DP * dp,
                depthPx = com.dk.assistant.ui.ClayCard.DEPTH_DP * dp, card = true)   // साझा कार्ड ([com.dk.assistant.ui.ClayCard])
            Kind.CIRCLE -> c(ClayStyle.RAISED, 0f, 4f, oval = true, fill = chip)
            Kind.ACCENT_CIRCLE -> c(ClayStyle.ACCENT, 0f, 4f, oval = true)
            Kind.PILL -> c(ClayStyle.RAISED, FULL, 4f, fill = chip)
            Kind.ACCENT -> c(ClayStyle.ACCENT, FULL, 4f)
            Kind.ROW -> c(ClayStyle.RAISED, 20f, 4f, fill = chip)
            Kind.FIELD -> c(ClayStyle.INSET, 20f, 3f)
            Kind.BUBBLE_DK -> c(ClayStyle.RAISED, 22f, 4f)
            Kind.BUBBLE_ME -> c(ClayStyle.ACCENT, 22f, 4f)
        }
    }

    private val INSTALLED = R.id.plain_look_installed
    /** यह view (और अंदर का सब) अपने रूप में ही रहे - जैसे वीडियो / कैमरा / खास चित्र */
    val SKIP = R.id.plain_look_skip

    /** लाइट थीम में सफ़ेद-सी लिखावट -> थीम की गहरी लिखावट (उतनी ही पारदर्शिता); रंगीन वैसी ही */
    private fun themeText(argb: Int): Int =
        if (lightText(argb) != argb) (argb and 0xFF000000.toInt()) or (com.dk.assistant.ui.ClayTheme.t.text and 0xFFFFFF) else argb

    /** ऐप के अपने ठोस डिब्बे (कार्ड / पिल / गहरा खाना) - किसी भी थीम के */
    private fun surfaceColor(c: Int): Boolean = appDark(c) || com.dk.assistant.ui.ClayTheme.isSurface(c) ||
        c == com.dk.assistant.ui.ClayTheme.t.well || c == com.dk.assistant.ui.ClayTheme.t.rim || c == L_CARD && light || c == L_PILL || c == L_FIELD || c == L_ME

    /** GradientDrawable के कोने */
    private fun radiusOf(g: GradientDrawable, dp: Float): Float = try {
        val arr = if (android.os.Build.VERSION.SDK_INT >= 24) g.cornerRadii else null
        arr?.maxOrNull() ?: g.cornerRadius
    } catch (_: Exception) { 16 * dp }

    /** पुराने सपाट डिब्बे जैसा ही आकार, पर Clay रूप में */
    private fun clayLike(v: View, g: GradientDrawable, k: Kind): Drawable {
        val dp = v.resources.displayMetrics.density
        val FULL = com.dk.assistant.ui.ClayDrawable.FULL
        val oval = g.shape == GradientDrawable.OVAL
        val h = if (v.height > 0) v.height else (48 * dp).toInt()
        val r0 = radiusOf(g, dp)
        val r = when {
            oval -> 0f
            r0 >= h / 2f - 1 || r0 >= 200 * dp -> FULL
            h >= 56 * dp -> r0.coerceIn(20 * dp, 28 * dp)
            else -> r0.coerceAtLeast(14 * dp)
        }
        val depth = (if (h >= 80 * dp) 6f else if (h >= 40 * dp) 4.5f else 3f) * dp
        val style = when (k) { Kind.ACCENT, Kind.ACCENT_CIRCLE, Kind.BUBBLE_ME -> ClayStyle.ACCENT; Kind.FIELD -> ClayStyle.INSET; else -> ClayStyle.RAISED }
        val small = k == Kind.PILL || k == Kind.ROW || k == Kind.CIRCLE
        // 23 नवंबर 2026 (मालिक): कोड में बने कार्ड (होम / मेमोरी / चैट...) भी वही साझा कार्ड
        if (k == Kind.CARD && !oval && r < FULL && (v.height == 0 || h >= 56 * dp)) return com.dk.assistant.ui.ClayDrawable(ClayStyle.RAISED,
            radiusPx = com.dk.assistant.ui.ClayCard.RADIUS_DP * dp, depthPx = maxOf(depth, com.dk.assistant.ui.ClayCard.DEPTH_DP * dp), card = true)
        return com.dk.assistant.ui.ClayDrawable(style, radiusPx = r, oval = oval, depthPx = depth,
            fill = if (small && style == ClayStyle.RAISED) ({ com.dk.assistant.ui.ClayTheme.t.chip }) else null)
    }

    /**
     * 23 नवंबर 2026: कोड में बने सादे डिब्बे (DkaiTheme.round / oval - कार्ड, पिल, खाना, नीला बटन) -> Clay.
     * सिर्फ़ ऐप के अपने surface / accent रंग वाले; रंगीन (हरा / लाल / चित्र) वैसे ही.
     */
    private fun clayBox(v: View) {
        val bg = v.background ?: return
        if (v.getTag(TAG) === bg || bg is com.dk.assistant.ui.ClayDrawable) return
        val g = (if (bg is RippleDrawable && bg.numberOfLayers > 0) bg.getDrawable(0) else bg) as? GradientDrawable ?: return
        val c = try { g.color?.defaultColor } catch (_: Exception) { null } ?: return
        if (((c ushr 24) and 0xFF) < 0xF0) return
        val t = com.dk.assistant.ui.ClayTheme.t
        val kind = when {
            c == t.accent || c == BLUE || c == 0xFF1D4ED8.toInt() -> if (g.shape == GradientDrawable.OVAL) Kind.ACCENT_CIRCLE else Kind.ACCENT
            c == t.well || c == FIELD || c == L_FIELD -> Kind.FIELD
            c == t.chip || c == t.sel || c == PILL || c == ME || c == L_PILL || c == L_ME -> if (g.shape == GradientDrawable.OVAL) Kind.CIRCLE else Kind.PILL
            surfaceColor(c) -> if (g.shape == GradientDrawable.OVAL) Kind.CIRCLE else Kind.CARD
            else -> return
        }
        // पूरी स्क्रीन वाला डिब्बा = सादी पृष्ठभूमि (परछाईं नहीं)
        if (fillsScreen(v)) { val d = ColorDrawable(bg()); v.background = d; v.setTag(TAG, d); return }
        val d = clayLike(v, g, kind)
        val l = v.paddingLeft; val tp = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.background = d; v.setPadding(l, tp, r, b); v.setTag(TAG, d)
        v.setTag(ACCENT_TAG, kind == Kind.ACCENT || kind == Kind.ACCENT_CIRCLE)
    }
}
