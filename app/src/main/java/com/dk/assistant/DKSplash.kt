package com.dk.assistant

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * ऐप खुलते ही: गहरी पृष्ठभूमि पर DK logo हल्के नीयन ग्लो के साथ स्मूद उभरे (~1.2s), फिर home पर fade.
 * सिर्फ़ एक overlay (window के ऊपर) - home screen का code/लेआउट नहीं बदला. हर process में एक ही बार.
 * Android 12+: system splash में सिर्फ़ यही गहरा रंग (icon पारदर्शी, themes.xml) - फिर यहीं logo उभरता है, कोई झटका नहीं.
 */
object DKSplash {
    const val BG = 0xFF05060D.toInt()
    const val TOTAL_MS = 1200L
    const val FADE_OUT_MS = 320L
    @Volatile private var done = false

    fun show(a: Activity) {
        if (done) return
        done = true
        val decor = a.window?.decorView as? ViewGroup ?: return
        val dp = a.resources.displayMetrics.density
        // phone में animation बंद (battery saver / accessibility) = सीधा home
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val root = FrameLayout(a).apply {
            setBackgroundColor(BG); isClickable = true; isFocusable = true   // बीच में गलती से tap न हो
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val glowSize = (300 * dp).toInt()
        val glow = View(a).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = glowSize / 2f
                colors = intArrayOf(0x887A5CFF.toInt(), 0x332EC5FF, 0x00000000)
            }
            alpha = 0f; scaleX = 0.7f; scaleY = 0.7f
        }
        root.addView(glow, FrameLayout.LayoutParams(glowSize, glowSize, Gravity.CENTER))
        val logoSize = (288 * dp).toInt()        // Android 12 splash icon जितना - वही जगह
        val logo = ImageView(a).apply {
            setImageResource(R.drawable.dk_splash_logo)
            alpha = 0f; scaleX = 0.88f; scaleY = 0.88f; translationY = 10 * dp
        }
        root.addView(logo, FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER))
        decor.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val ease = DecelerateInterpolator(1.6f)
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(650).setStartDelay(60).setInterpolator(ease).withLayer().start()
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(800).setStartDelay(120).setInterpolator(ease)
            .withEndAction { glow.animate().alpha(0.65f).setDuration(280).start() }.start()
        root.postDelayed({
            root.animate().alpha(0f).setDuration(FADE_OUT_MS).withLayer()
                .withEndAction { (root.parent as? ViewGroup)?.removeView(root) }.start()
        }, TOTAL_MS)
    }
}
