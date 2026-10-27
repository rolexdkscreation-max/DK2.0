package com.dk.assistant.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/**
 * दिखावट की पसंद बदलने पर पीछे खुली स्क्रीनें भी लौटते ही नए रंग में (बिना ऐप दोबारा खोले).
 * सिर्फ़ redraw - किसी view का click / काम / data नहीं छूता.
 */
object NeonRefresh : Application.ActivityLifecycleCallbacks {
    private val seen = java.util.WeakHashMap<Activity, Int>()

    fun bars(a: Activity) {
        try {
            val bg = NeonTheme.bg()
            a.window?.statusBarColor = bg[0]
            a.window?.navigationBarColor = bg[2]
        } catch (_: Exception) {}
    }

    fun invalidateAll(v: View?) {
        if (v == null) return
        v.background?.invalidateSelf()
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateAll(v.getChildAt(i))
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) { bars(a); seen[a] = NeonTheme.version }
    override fun onActivityResumed(a: Activity) {
        val sysOff = try {
            android.provider.Settings.Global.getFloat(a.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
        val changed = seen[a] != NeonTheme.version || sysOff != NeonTheme.systemNoAnim
        NeonTheme.systemNoAnim = sysOff
        if (changed) { seen[a] = NeonTheme.version; bars(a); invalidateAll(a.window?.decorView) }
    }
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) { seen.remove(a) }
}
