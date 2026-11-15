package com.dk.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
     */
    fun bar(c: Context, fg: Int, accent: Int, bg: Int, selected: Tab = Tab.HOME, go: (Tab) -> Unit): LinearLayout {
        val dm = c.resources.displayMetrics.density
        fun d(v: Float) = (v * dm + 0.5f).toInt()
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            id = R.id.dk_home_tabs
            background = GradientDrawable().apply { setColor(bg); cornerRadius = d(22f).toFloat() }
            setPadding(d(4f), d(4f), d(4f), d(4f))
            elevation = d(4f).toFloat()
        }
        for (t in TABS) {
            val on = t == selected
            val mid = t == Tab.MIC
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true; contentDescription = "${t.label} - ${t.desc}"
                background = android.util.TypedValue().let { tv ->
                    if (c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true))
                        androidx.core.content.ContextCompat.getDrawable(c, tv.resourceId) else null
                }
                tag = t
                setOnClickListener { go(t) }
            }
            val icon = ImageView(c).apply {
                setImageResource(t.icon); scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(if (mid) bg else if (on) accent else fg)
                if (mid) { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }; val p = d(9f); setPadding(p, p, p, p) }
            }
            val sz = if (mid) d(42f) else d(24f)
            cell.addView(icon, LinearLayout.LayoutParams(sz, sz))
            cell.addView(TextView(c).apply {
                text = t.label; textSize = 11f; maxLines = 1; gravity = Gravity.CENTER
                setTextColor(if (on || mid) accent else fg)
                if (on) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(2f) })
            bar.addView(cell, LinearLayout.LayoutParams(0, d(if (mid) 64f else 56f), 1f))
        }
        return bar
    }

    /** पट्टी है तो दिखाओ/छुपाओ (Settings बदलने के बाद onResume पर) */
    fun sync(c: Context, bar: View?) { bar?.visibility = if (visible(c)) View.VISIBLE else View.GONE }
}
