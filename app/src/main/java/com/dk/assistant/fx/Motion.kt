package com.dk.assistant.fx

import android.content.Context
import android.provider.Settings

/** फ़ोन में "animation बंद/कम" चालू है? (तब चमक स्थिर रहती है) */
object Motion {
    fun reduced(c: Context): Boolean = try {
        !android.animation.ValueAnimator.areAnimatorsEnabled() ||
            Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) { false }
}
