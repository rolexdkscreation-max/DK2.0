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
            background = ClayDrawable(ClayDrawable.Style.RAISED, radiusPx = d(28f).toFloat(), depthPx = d(6f).toFloat())
            setPadding(d(10f), d(8f), d(10f), d(10f))
            clipChildren = false; clipToPadding = false
            setTag(com.dk.assistant.PlainLook.SKIP, true)
        }
        for (t in TABS) {
            val on = t == selected
            val mid = t == Tab.MIC
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true; contentDescription = "${t.label} - ${t.desc}"
                // चुना टैब = accent से भरी उभरी गोली; बाकी दबाने पर धँसें (120ms)
                background = if (mid) null else ClayDrawable(if (on) ClayDrawable.Style.ACCENT else ClayDrawable.Style.RAISED,
                    radiusPx = d(20f).toFloat(), depthPx = if (on) d(4f).toFloat() else 0f, fill = { k.surface })
                if (!mid) setPadding(0, d(4f), 0, d(4f))
                clipChildren = false; clipToPadding = false
                tag = t
                setOnClickListener { go(t) }
            }
            val icon = ImageView(c).apply {
                setImageResource(t.icon); scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(if (mid || on) k.onAccent else fg)
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
                setTextColor(if (on) k.onAccent else if (mid) k.accent else fg)
                if (on || mid) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(2f) })
            // बीच का माइक: बाकी टैब से ऊपर उभरा (पट्टी से बाहर निकलता)
            if (mid) cell.translationY = -d(18f).toFloat()
            bar.addView(cell, LinearLayout.LayoutParams(0, if (mid) d(66f) + d(16f) else d(56f), 1f).apply {
                if (!mid) { marginStart = d(3f); marginEnd = d(3f) }
                if (mid) bottomMargin = -d(18f)
            })
        }
        return bar
    }

    /** पट्टी है तो दिखाओ/छुपाओ (Settings बदलने के बाद onResume पर) */
    fun sync(c: Context, bar: View?) { bar?.visibility = if (visible(c)) View.VISIBLE else View.GONE }
}
