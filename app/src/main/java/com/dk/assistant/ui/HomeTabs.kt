package com.dk.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.R

/**
 * 15 नवंबर 2026 (मालिक: "अलग 5-टैब वाली नीचे की पट्टी भी बनाओ"):
 * होम • स्कैन • माइक (DK लाइव) • चैट • मेमोरी. हर टैब पहले से बने असली रास्ते खोलता है - कोई नई नकली स्क्रीन नहीं.
 * Settings › दिखावट में "नीचे की 5-टैब पट्टी छुपाएँ" (FeatureFlags.HIDE_HOME_TABS, शुरू में बंद = पट्टी दिखे).
 * Pure हिस्सा ([TABS], [order]) JVM test में.
 */
object HomeTabs {
    enum class Tab(val label: String, val desc: String, val icon: Int) {
        HOME("होम", "होम स्क्रीन", R.drawable.ic_home_tab),
        SCAN("स्कैन", "कैमरे से कॉपी, स्क्रीन या चीज़ पढ़वाएँ", R.drawable.ic_camera),
        MIC("माइक", "DK लाइव - आवाज़ से बात", R.drawable.ic_mic),
        CHAT("चैट", "लिखकर बात", R.drawable.ic_chat),
        MEMORY("मेमोरी", "DK की यादें", R.drawable.ic_memory),
    }

    val TABS: List<Tab> = Tab.values().toList()

    /** पट्टी पर नाम इसी क्रम में (बीच में माइक) */
    fun order(): List<String> = TABS.map { it.label }

    fun visible(c: Context): Boolean = !com.dk.assistant.plus.FeatureFlags.on(c, com.dk.assistant.plus.FeatureFlags.HIDE_HOME_TABS)

    /**
     * पट्टी बनाओ. [fg] = नाम/आइकन का रंग, [accent] = चुना टैब और बीच का माइक, [bg] = पट्टी का रंग.
     * [go] को दबाया टैब मिलता है.
     * 23 नवंबर 2026 (Soft Clay): उभरी गोल पट्टी; चुना टैब accent से भरा; बीच का माइक बड़ा गोल, बाकी से ऊपर उभरा, accent चमक.
     * रंग ([accent] / [bg]) अब चुनी Clay थीम के ही आते हैं - टैब / क्रम / काम वही.
     */
    fun bar(c: Context, fg: Int, accent: Int, bg: Int, selected: Tab = Tab.HOME, go: (Tab) -> Unit): LinearLayout {
        val dm = c.resources.displayMetrics.density
        fun d(v: Float) = (v * dm + 0.5f).toInt()
        val k = ClayTheme.t
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            id = R.id.dk_home_tabs
            setPadding(d(10f), d(8f), d(10f), d(10f))
            clipChildren = false; clipToPadding = false
            setTag(com.dk.assistant.PlainLook.SKIP, true)
        }
        // 23 नवंबर 2026 (मालिक: "नीला तरल जैसा चयन-निशान जिस पर टैप करें उसी पर सरक कर आए"): उभरी पट्टी + एक ही accent बूँद
        val liquid = LiquidTabs(bar, selected, d(28f).toFloat(), d(6f).toFloat(), d(4f).toFloat()).also { it.fg = fg }
        bar.background = liquid
        bar.setTag(R.id.dk_home_tabs, liquid)
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> liquid.invalidateSelf() }
        for (t in TABS) {
            val mid = t == Tab.MIC
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true; contentDescription = "${t.label} - ${t.desc}"
                // बाकी टैब दबाने पर धँसें (120ms); चुने टैब के नीचे तरल बूँद ([LiquidTabs])
                background = if (mid) null else android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ClayDrawable(ClayDrawable.Style.INSET, radiusPx = d(20f).toFloat()))
                }
                if (!mid) setPadding(0, d(4f), 0, d(4f))
                clipChildren = false; clipToPadding = false
                tag = t
                setOnClickListener { if (!mid) select(bar, t); go(t) }
            }
            val icon = ImageView(c).apply {
                setImageResource(t.icon); scaleType = ImageView.ScaleType.FIT_CENTER
                if (mid) {
                    background = ClayDrawable(ClayDrawable.Style.ACCENT, oval = true, depthPx = d(5f).toFloat())
                    val p = d(17f); setPadding(p, p, p, p)
                    // accent चमक (Android 9+: परछाईं का रंग accent)
                    elevation = d(10f).toFloat()
                    if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = k.accent; outlineAmbientShadowColor = k.accent }
                }
            }
            val sz = if (mid) d(66f) else d(24f)
            cell.addView(icon, LinearLayout.LayoutParams(sz, sz))
            cell.addView(TextView(c).apply {
                text = t.label; textSize = 11f; maxLines = 1; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(2f) })
            // बीच का माइक: बाकी टैब से ऊपर उभरा (पट्टी से बाहर निकलता)
            if (mid) cell.translationY = -d(18f).toFloat()
            bar.addView(cell, LinearLayout.LayoutParams(0, if (mid) d(66f) + d(16f) else d(56f), 1f).apply {
                if (!mid) { marginStart = d(3f); marginEnd = d(3f) }
                if (mid) bottomMargin = -d(18f)
            })
        }
        paint(bar, selected, fg)
        return bar
    }

    /** अभी चुना टैब (पट्टी पर) */
    fun selected(bar: View?): Tab? = (bar?.getTag(R.id.dk_home_tabs) as? LiquidTabs)?.sel

    /** चुना टैब बदलो: तरल बूँद उसी पर सरके, लिखावट/आइकन के रंग बदलें (माइक चुनाव नहीं बदलता) */
    fun select(bar: View?, t: Tab) {
        if (bar !is LinearLayout || t == Tab.MIC) return
        val l = bar.getTag(R.id.dk_home_tabs) as? LiquidTabs ?: return
        if (l.sel == t) return
        l.moveTo(t)
        paint(bar, t, l.fg)
    }

    private fun paint(bar: LinearLayout, sel: Tab, fg: Int) {
        val k = ClayTheme.t
        for (i in 0 until bar.childCount) {
            val cell = bar.getChildAt(i) as? LinearLayout ?: continue
            val t = cell.tag as? Tab ?: continue
            val on = t == sel; val mid = t == Tab.MIC
            (cell.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(if (mid || on) k.onAccent else fg)
            (cell.getChildAt(1) as? TextView)?.apply {
                setTextColor(if (on) k.onAccent else if (mid) k.accent else fg)
                setTypeface(Typeface.create(typeface, if (on || mid) Typeface.BOLD else Typeface.NORMAL))
            }
            cell.isSelected = on
        }
    }

    /** पट्टी है तो दिखाओ/छुपाओ (Settings बदलने के बाद onResume पर) */
    fun sync(c: Context, bar: View?) { bar?.visibility = if (visible(c)) View.VISIBLE else View.GONE }
}

/**
 * नीचे की पट्टी का पीछा: उभरी Clay पट्टी + चुने टैब के नीचे एक accent "तरल" बूँद.
 * टैब बदलते ही बूँद सरकती है - आगे वाला सिरा तेज़, पीछे वाला ज़रा देर से (खिंचकर फिर गोल = तरल जैसा).
 * रंग [ClayTheme.t] से ([ClayDrawable]), कम-एनिमेशन चालू हो तो सीधे पहुँचती है.
 */
class LiquidTabs(private val bar: LinearLayout, var sel: HomeTabs.Tab, radius: Float, depth: Float, blobDepth: Float) : android.graphics.drawable.Drawable() {
    private val base = ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = radius, depthPx = depth)
    private val blob = ClayDrawable(ClayDrawable.Style.ACCENT, radiusPx = ClayDrawable.FULL, depthPx = blobDepth)
    private var l = Float.NaN
    private var r = Float.NaN
    private var fromL = 0f
    private var fromR = 0f
    private var anim: android.animation.ValueAnimator? = null
    /** न चुने टैब की लिखावट का रंग */
    var fg: Int = ClayTheme.t.gray

    private fun target(): android.graphics.RectF? {
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            if (v.tag == sel && v.width > 0) return android.graphics.RectF(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
        }
        return null
    }

    fun moveTo(t: HomeTabs.Tab) {
        sel = t
        val to = target() ?: run { invalidateSelf(); return }
        anim?.cancel()
        if (l.isNaN() || NeonTheme.reducedMotion()) { l = to.left; r = to.right; invalidateSelf(); return }
        fromL = l; fromR = r
        val right = to.left > fromL
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                val f = va.animatedFraction
                // आगे वाला सिरा जल्दी (तेज़ ढलान), पीछे वाला देर से - बीच में बूँद खिंची हुई
                val lead = LEAD.getInterpolation(f); val lag = LAG.getInterpolation(f)
                val fl = if (right) lag else lead
                val fr = if (right) lead else lag
                l = fromL + (to.left - fromL) * fl
                r = fromR + (to.right - fromR) * fr
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(c: android.graphics.Canvas) {
        base.bounds = bounds
        base.draw(c)
        val to = target() ?: return
        if (l.isNaN() || (anim?.isRunning != true)) { l = to.left; r = to.right }
        blob.setBounds(l.toInt(), to.top.toInt(), r.toInt(), to.bottom.toInt())
        blob.draw(c)
    }

    override fun setAlpha(alpha: Int) { base.alpha = alpha; blob.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        const val DURATION_MS = 380L
        private val LEAD = android.view.animation.DecelerateInterpolator(2.2f)
        private val LAG = android.view.animation.AccelerateDecelerateInterpolator()
    }
}
