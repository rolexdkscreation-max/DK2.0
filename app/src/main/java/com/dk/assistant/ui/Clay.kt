package com.dk.assistant.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import com.dk.assistant.R

/**
 * 23 नवंबर 2026 (मालिक: Soft 3D Clay UI): हर स्क्रीन के हर हिस्से का नया रूप - एक ही जगह से.
 *
 * यह सिर्फ़ रूप बदलता है: किसी switch / radio / checkbox / seekbar का listener, isChecked, progress या काम नहीं छूता.
 * [decorate] को [com.dk.assistant.PlainLook] का walker हर स्क्रीन (और बाद में जुड़े views) पर चलाता है.
 */
object Clay {
    private val TAG = R.id.clay_done

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    private fun dpi(c: Context, v: Float) = (dp(c, v) + 0.5f).toInt()

    // ------------------------------------------------------------------ बने-बनाए रूप
    /** उभरा कार्ड / पट्टी (कोने 20–28dp) */
    fun raised(c: Context, radiusDp: Float = 22f, depthDp: Float = 6f, selectable: Boolean = false) =
        ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = dp(c, radiusDp), depthPx = dp(c, depthDp), selectable = selectable)
    /**
     * 23 नवंबर 2026 (मालिक): पूरे ऐप का साझा कार्ड - गोलाई [ClayCard.RADIUS_DP], नरम दो परछाइयाँ, दबाने पर हल्का धँसे.
     * हर कार्ड / बड़ा डिब्बा यही ले, ताकि सब एक जैसे दिखें.
     */
    fun card(c: Context, selectable: Boolean = false) =
        ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = dp(c, ClayCard.RADIUS_DP), depthPx = dp(c, ClayCard.DEPTH_DP), selectable = selectable, card = true)
    /** उभरा छोटा चिप (कार्ड से ज़रा अलग रंग) */
    fun chip(c: Context, radiusDp: Float = ClayDrawable.FULL, depthDp: Float = 4f, selectable: Boolean = false) =
        ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = if (radiusDp >= ClayDrawable.FULL) ClayDrawable.FULL else dp(c, radiusDp),
            depthPx = dp(c, depthDp), selectable = selectable, fill = { ClayTheme.t.chip })
    /** धँसा खाना (text box, usage बार) */
    fun inset(c: Context, radiusDp: Float = 20f, depthDp: Float = 3f) =
        ClayDrawable(ClayDrawable.Style.INSET, radiusPx = if (radiusDp >= ClayDrawable.FULL) ClayDrawable.FULL else dp(c, radiusDp), depthPx = dp(c, depthDp))
    /** accent से भरा उभरा (मुख्य बटन) */
    fun accent(c: Context, radiusDp: Float = ClayDrawable.FULL, depthDp: Float = 5f) =
        ClayDrawable(ClayDrawable.Style.ACCENT, radiusPx = if (radiusDp >= ClayDrawable.FULL) ClayDrawable.FULL else dp(c, radiusDp), depthPx = dp(c, depthDp))
    /** गोल उभरा बटन */
    fun circle(c: Context, depthDp: Float = 4f, accent: Boolean = false) =
        ClayDrawable(if (accent) ClayDrawable.Style.ACCENT else ClayDrawable.Style.RAISED, oval = true, depthPx = dp(c, depthDp),
            fill = if (accent) null else ({ ClayTheme.t.chip }))

    /** पृष्ठभूमि बदलो पर padding वही रखो */
    fun setBg(v: View, d: Drawable) {
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.background = d
        v.setPadding(l, t, r, b)
    }

    // ------------------------------------------------------------------ widgets (सिर्फ़ रूप)
    fun decorate(v: View) {
        when (v) {
            is androidx.appcompat.widget.SwitchCompat -> switchCompat(v)
            is android.widget.Switch -> switchPlain(v)
            is RadioButton -> radio(v)
            is CheckBox -> check(v)
            is SeekBar -> seek(v)
            is ProgressBar -> progress(v)
            is EditText -> edit(v)
            is RadioGroup -> segmentGroup(v)
        }
    }

    private fun once(v: View, key: Any): Boolean {
        if (v.getTag(TAG) == key) return false
        v.setTag(TAG, key); return true
    }

    private fun textStates(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))

    /** Settings के सारे स्विच: उभरी गोल पट्टी + गोल घुंडी. ऑन = पट्टी accent, घुंडी दाएँ (स्विच खुद खिसकाता है) */
    fun switchCompat(s: androidx.appcompat.widget.SwitchCompat) {
        if (!once(s, ClayTheme.version)) return
        val c = s.context
        s.thumbDrawable = ClayKnobDrawable(dpi(c, 28f))
        s.trackDrawable = ClayTrackDrawable(dpi(c, 52f), dpi(c, 30f))
        s.thumbTintList = null; s.trackTintList = null
        s.switchMinWidth = dpi(c, 52f)
        s.showText = false
        s.jumpDrawablesToCurrentState()
    }

    fun switchPlain(s: android.widget.Switch) {
        if (!once(s, ClayTheme.version)) return
        val c = s.context
        s.thumbDrawable = ClayKnobDrawable(dpi(c, 28f))
        s.trackDrawable = ClayTrackDrawable(dpi(c, 52f), dpi(c, 30f))
        s.thumbTintList = null; s.trackTintList = null
        s.switchMinWidth = dpi(c, 52f)
        s.showText = false
        s.jumpDrawablesToCurrentState()
    }

    /** चयन-पंक्ति: उभरी पट्टी, चुनी हो तो accent ढलान से भरी + दाईं ओर सफ़ेद ✓ वाला खाना; न चुनी = खाली धँसा खाना */
    fun radio(r: CompoundButton) {
        val seg = (r.parent as? RadioGroup)?.let { segmented(it) } == true
        if (!once(r, if (seg) "seg" + ClayTheme.version else ClayTheme.version)) return
        val c = r.context
        val k = ClayTheme.t
        r.buttonDrawable = null
        if (seg) {
            // segmented पट्टी: अपना डिब्बा नहीं (पट्टी का उभरा accent बटन खिसकता है), बस अक्षर
            r.background = SegProxy(r.parent as RadioGroup)
            r.gravity = Gravity.CENTER
            r.setTextColor(textStates(k.onAccent, k.gray))
            r.setCompoundDrawablesRelative(null, null, null, null)
            val p = dpi(c, 10f); r.setPadding(p, dpi(c, 8f), p, dpi(c, 8f))
            (r.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                if (lp.weight == 0f) { lp.width = 0; lp.weight = 1f; r.layoutParams = lp }
            }
        } else {
            r.background = ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = dp(c, 20f), depthPx = dp(c, 5f), selectable = true)
            val box = ClayCheckDrawable(dpi(c, 24f)).apply { setBounds(0, 0, dpi(c, 24f), dpi(c, 24f)) }
            r.setCompoundDrawablesRelative(null, null, box, null)
            r.compoundDrawablePadding = dpi(c, 12f)
            r.setTextColor(textStates(k.onAccent, k.text))
            r.setPadding(dpi(c, 18f), dpi(c, 14f), dpi(c, 16f), dpi(c, 14f))
            r.minHeight = dpi(c, 52f)
            (r.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.topMargin < dpi(c, 6f)) { lp.topMargin = dpi(c, 6f); lp.bottomMargin = dpi(c, 4f); r.layoutParams = lp }
            }
        }
        r.jumpDrawablesToCurrentState()
    }

    fun check(b: CheckBox) = radio(b)

    /** 2–4 विकल्प एक पंक्ति में = segmented पट्टी */
    fun segmented(g: RadioGroup) = g.orientation == RadioGroup.HORIZONTAL && g.childCount in 2..4 && (0 until g.childCount).all { g.getChildAt(it) is RadioButton }

    private fun segmentGroup(g: RadioGroup) {
        if (!segmented(g)) return
        if (!once(g, ClayTheme.version)) return
        val c = g.context
        setBg(g, ClaySegmentDrawable(g) { (0 until g.childCount).firstOrNull { (g.getChildAt(it) as? RadioButton)?.isChecked == true } ?: -1 })
        if (g.paddingLeft < dpi(c, 4f)) g.setPadding(dpi(c, 4f), dpi(c, 4f), dpi(c, 4f), dpi(c, 4f))
    }

    /** segmented पट्टी के हर विकल्प का अदृश्य डिब्बा: चुनाव बदले तो पट्टी दोबारा बने (accent बटन खिसके) */
    private class SegProxy(private val g: RadioGroup) : Drawable() {
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean { g.invalidate(); return false }
        override fun draw(c: android.graphics.Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSPARENT
    }

    /** धँसा पतला खाना + अंदर accent भराव */
    private fun barDrawable(c: Context): LayerDrawable {
        val track = inset(c, ClayDrawable.FULL, 1.5f)
        val fill = ClipDrawable(ClayDrawable(ClayDrawable.Style.ACCENT, radiusPx = ClayDrawable.FULL, depthPx = 0f), Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
    }

    fun seek(s: SeekBar) {
        if (!once(s, ClayTheme.version)) return
        val c = s.context
        val p = s.progress
        s.progressDrawable = barDrawable(c)
        s.thumb = ClayKnobDrawable(dpi(c, 24f)).apply { setBounds(0, 0, dpi(c, 24f), dpi(c, 24f)) }
        s.thumbOffset = dpi(c, 12f)
        s.progressTintList = null; s.progressBackgroundTintList = null; s.thumbTintList = null
        if (android.os.Build.VERSION.SDK_INT >= 29) { s.maxHeight = dpi(c, 10f); s.minHeight = dpi(c, 10f) }
        s.progress = p
    }

    fun progress(b: ProgressBar) {
        if (b.isIndeterminate || b is SeekBar) {
            if (b.isIndeterminate && once(b, ClayTheme.version)) b.indeterminateTintList = ColorStateList.valueOf(ClayTheme.t.accent)
            return
        }
        if (!once(b, ClayTheme.version)) return
        val p = b.progress
        b.progressDrawable = barDrawable(b.context)
        b.progressTintList = null; b.progressBackgroundTintList = null
        if (android.os.Build.VERSION.SDK_INT >= 29) { b.maxHeight = dpi(b.context, 10f); b.minHeight = dpi(b.context, 10f) }
        b.progress = p
    }

    /** text box = धँसा खाना (जिसकी पृष्ठभूमि जानबूझकर हटाई गई हो - जैसे DKAI का "Ask anything" - उसे नहीं छूते) */
    fun edit(e: EditText) {
        val bg = e.background ?: return
        if (bg is ClayDrawable) return
        if (!once(e, ClayTheme.version)) return
        val c = e.context
        val keepPad = e.paddingLeft >= dpi(c, 10f)
        setBg(e, inset(c, 18f, 3f))
        if (!keepPad) e.setPadding(dpi(c, 14f), dpi(c, 12f), dpi(c, 14f), dpi(c, 12f))
        val k = ClayTheme.t
        if (k.light) {
            if (com.dk.assistant.PlainLook.isLightColor(e.currentTextColor)) e.setTextColor(k.text)
            if (com.dk.assistant.PlainLook.isLightColor(e.currentHintTextColor)) e.setHintTextColor(k.hint)
        }
    }

    // ------------------------------------------------------------------ पूरा ऐप: हर स्क्रीन पर लागू + थीम बदले तो लौटते ही नई
    object Refresh : Application.ActivityLifecycleCallbacks {
        private val seen = java.util.WeakHashMap<Activity, Int>()

        /** पारदर्शी / overlay वाली स्क्रीनें (DK बुलबुला, अलार्म) - उनकी पृष्ठभूमि नहीं बदलती */
        private fun translucent(a: Activity): Boolean = try {
            val ta = a.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            val t = ta.getBoolean(0, false); ta.recycle(); t
        } catch (_: Exception) { false }

        fun bars(a: Activity) {
            val k = ClayTheme.t
            try {
                a.window?.statusBarColor = k.bg
                a.window?.navigationBarColor = k.bg
                val dv = a.window?.decorView ?: return
                var f = dv.systemUiVisibility
                @Suppress("DEPRECATION")
                f = if (k.light) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    @Suppress("DEPRECATION")
                    f = if (k.light) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
                @Suppress("DEPRECATION")
                dv.systemUiVisibility = f
            } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) {
            seen[a] = ClayTheme.version
            if (translucent(a)) return
            // लाइट थीम: dialog और सिस्टम लिखावट गहरी (theme overlay) - setContentView से पहले
            if (ClayTheme.t.light) try { a.theme.applyStyle(R.style.DK_ClayLightOverlay, true) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            try { a.window?.setBackgroundDrawable(ColorDrawable(ClayTheme.t.bg)) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
            a.window?.decorView?.post { try { bars(a); com.dk.assistant.PlainLook.install(a) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
        }

        override fun onActivityResumed(a: Activity) {
            // थीम बदली (Settings › Theme) = पीछे खुली स्क्रीन लौटते ही नई थीम में बने
            if (seen[a] != null && seen[a] != ClayTheme.version) { seen[a] = ClayTheme.version; if (!translucent(a)) a.recreate() }
        }
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) { seen.remove(a) }
    }
}
