package com.dk.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.R

/**
 * v3.69: खुलने-बंद होने वाला सेटिंग हिस्सा (collapsible) - पूरे DK ऐप में एक जैसा.
 *
 * बंद हालत में सिर्फ़ नाम और तीर (›); नाम पर टैप = अंदर की सारी सेटिंग हल्के एनिमेशन से खुलें, दोबारा टैप = बंद.
 * शुरू में बंद. अंदर का सारा हिस्सा पहले ही बन जाता है (बस छिपा रहता है) - इसलिए सेव/पुराना कोड वैसे ही चलता है.
 *
 * नया हिस्सा जोड़ना:
 *     CollapsibleSection.add(this, container, "नाम", "छोटा विवरण (खुलने पर दिखे)") { box -> ...सेटिंग... }
 */
class CollapsibleSection private constructor(ctx: Context, title: String, sub: String?, startOpen: Boolean) {

    /** पूरा कॉलम (glass card) - parent में यही जोड़ो */
    val view: LinearLayout
    /** अंदर की सेटिंग यहाँ जोड़ो */
    val body: LinearLayout

    private val header: LinearLayout
    private val arrow: TextView
    private var anim: ValueAnimator? = null
    var isOpen: Boolean = startOpen
        private set

    init {
        val dp = ctx.resources.displayMetrics.density
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_card)
            val ph = (18 * dp).toInt(); val pv = (16 * dp).toInt(); setPadding(ph, pv, ph, pv)
        }
        header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            minimumHeight = (32 * dp).toInt()
            contentDescription = title
        }
        header.addView(TextView(ctx).apply {
            text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        arrow = TextView(ctx).apply {
            text = "›"; textSize = 26f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER
            rotation = if (startOpen) 90f else 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(arrow, LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()))
        view.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (startOpen) View.VISIBLE else View.GONE
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        if (sub != null) body.addView(TextView(ctx).apply {
            text = sub; textSize = 13f; setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 0, 0, (6 * dp).toInt())
        })
        view.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        header.setOnClickListener { toggle() }
        header.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val st = if (isOpen) "खुला" else "बंद"
                if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = st
                else info.contentDescription = "${host.contentDescription}, $st"
            }
        }
    }

    fun toggle() = setOpen(!isOpen, animate = true)

    fun setOpen(open: Boolean, animate: Boolean = true) {
        if (open == isOpen && anim == null) return
        isOpen = open
        anim?.cancel(); anim = null
        arrow.animate().rotation(if (open) 90f else 0f).setDuration(DURATION).start()
        if (!animate || !view.isAttachedToWindow) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            body.layoutParams = body.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            body.alpha = 1f
            return
        }
        val width = View.MeasureSpec.makeMeasureSpec((view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(0), View.MeasureSpec.EXACTLY)
        body.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val full = body.measuredHeight
        val from = if (open) 0 else body.height.takeIf { it > 0 } ?: full
        val to = if (open) full else 0
        if (open) { body.layoutParams = body.layoutParams.apply { height = 0 }; body.alpha = 0f; body.visibility = View.VISIBLE }
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val h = va.animatedValue as Int
                body.layoutParams = body.layoutParams.apply { height = h }
                body.alpha = if (full > 0) (h.toFloat() / full).coerceIn(0f, 1f) else 1f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    anim = null
                    body.layoutParams = body.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    body.alpha = 1f
                    if (!isOpen) body.visibility = View.GONE
                }
            })
            start()
        }
    }

    companion object {
        const val DURATION = 220L

        /**
         * सबसे आसान तरीका: नया कॉलम parent में जोड़ो (ऊपर 12dp जगह के साथ), body में सेटिंग बनाओ.
         * शुरू में बंद (startOpen = false).
         */
        fun add(ctx: Context, parent: LinearLayout, title: String, sub: String? = null, startOpen: Boolean = false,
                body: (LinearLayout) -> Unit): CollapsibleSection {
            val s = CollapsibleSection(ctx, title, sub, startOpen)
            body(s.body)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (12 * ctx.resources.displayMetrics.density).toInt()
            parent.addView(s.view, lp)
            return s
        }
    }
}
